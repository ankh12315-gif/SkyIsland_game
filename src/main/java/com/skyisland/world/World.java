package com.skyisland.world;

import com.skyisland.util.Coords;
import com.skyisland.util.Log;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;
import com.skyisland.world.gen.ChunkWriter;
import com.skyisland.world.gen.WorldGenerator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 世界：区块的集合 + <b>唯一的方块改动入口</b>（TECH_DESIGN §H / M1 指令 B3）。
 *
 * <p><b>本类存在的意义是一条规则：</b>
 * <blockquote>UI、渲染器、玩家都<u>不得</u>直接修改区块数组，所有改动必须经过
 * {@link #breakBlock} / {@link #placeBlock}。</blockquote>
 *
 * <p>这条规则不是靠自觉维持的：{@code Chunk} 的写方法是包级私有，而 {@code Chunk} 与
 * {@code World} 在同一个包里 —— 于是"绕过 World 改方块"这件事**编译不过**。
 * 生成器走 {@link ChunkWriter} 只写门面，同样拿不到 {@code Chunk} 的引用。
 *
 * <p><b>改动之后必须做的事</b>（TECH_DESIGN §H.3），全部由本类统一完成，
 * 调用方不需要（也无法）记得：
 * <ol>
 *   <li>标记本区块 {@code meshDirty} 与 {@code saveDirty}；</li>
 *   <li>若改动位于区块边界，标记<b>相邻区块</b> {@code meshDirty}
 *       —— 否则邻居沿边界的那一圈面会残留或缺失；</li>
 *   <li>把需要重建网格的区块放进重建队列（由渲染层限量消费）。</li>
 * </ol>
 */
public final class World {

    /** 改动来源。不同来源的校验与记账规则不同，因此必须显式传入。 */
    public enum MutationCause {
        /** 世界生成（不标记 saveDirty —— 生成结果不属于"玩家改动"）。 */
        GENERATION,
        /** 存档回放（不标记 saveDirty —— 它本来就已经在存档里了）。 */
        SAVE_LOAD,
        /** 玩家破坏。 */
        PLAYER_BREAK,
        /** 玩家放置。 */
        PLAYER_PLACE,
        /** 自测脚本。与玩家行为同样记账，但日志里可区分。 */
        SELF_TEST
    }

    /** 改动结果。失败时带可读原因 —— 静默失败会让"为什么放不下"无从追查。 */
    public record MutationResult(boolean success, String reason) {

        private static final MutationResult OK = new MutationResult(true, "");

        public static MutationResult ok() {
            return OK;
        }

        public static MutationResult fail(String reason) {
            return new MutationResult(false, reason);
        }
    }

    private final long seed;
    private final WorldGenerator generator;

    /** 已加载区块。用 LinkedHashMap 保持插入顺序，使遍历顺序可复现（便于对比两次运行的网格指标）。 */
    private final Map<Long, Chunk> chunks = new LinkedHashMap<>();

    /** 待重建网格的区块。LinkedHashSet 兼具去重与 FIFO 顺序。 */
    private final LinkedHashSet<Chunk> meshQueue = new LinkedHashSet<>();

    // ---- 统计（M1 性能与正确性可观测性）----
    private long breakCount;
    private long placeCount;
    private long rejectedCount;
    private long neighborMarkCount;
    private long meshBuildCount;
    private long meshBuildTotalNanos;

    /**
     * 自发光方块（光源）。
     *
     * <p><b>为什么世界层要维护这份列表，而不是让光照引擎每次去扫区块：</b>
     * 光照的衰减半径是 6 格，会跨区块边界。若各区块只扫自己内部的光源，
     * 紧贴边界的光源会在两侧得到不同亮度，沿区块边界出现一条硬边 ——
     * 这是"每区块各自算光照"最典型也最难解释的瑕疵。
     * 维护一份世界级的小列表（M1 通常 0–1 项）后，光照引擎可以按"与区块 AABB 的距离"
     * 正确筛选出跨界影响的光源。
     */
    public record EmissiveSource(int x, int y, int z, int level) {
    }

    private final Map<Long, EmissiveSource> emissiveSources = new LinkedHashMap<>();

    /** 世界级光源列表（只读视图；调用方不得修改）。 */
    public Collection<EmissiveSource> emissiveSources() {
        return emissiveSources.values();
    }

    public int emissiveSourceCount() {
        return emissiveSources.size();
    }

    private static long posKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0x3FF) << 28) | (z & 0x3FFFFFF);
    }

    private void updateEmissive(int x, int y, int z, short newId) {
        boolean emits = BlockRegistry.byRuntimeId(newId).lightEmission() > 0;
        long key = posKey(x, y, z);
        if (emits) {
            emissiveSources.put(key, new EmissiveSource(x, y, z,
                    BlockRegistry.byRuntimeId(newId).lightEmission()));
        } else {
            emissiveSources.remove(key);
        }
    }

    /**
     * 生成结束后登记该区块内的光源。
     *
     * <p>必须单独做一次扫描：生成器走的是 {@link ChunkWriter} 只写门面，
     * 直接落到 {@code Chunk.setLocal}，<u>不经过</u> {@link #writeVoxel}，
     * 因此生成期产生的自发光方块不会自动进入光源列表。
     * 这是一处"两条写路径"的差异，靠注释记住不如靠这一处显式调用记住。
     */
    private void registerEmissiveFrom(Chunk chunk) {
        if (emissiveSources.size() > 4096) {
            return;   // 防御：异常场景下不要无限增长（正常世界不会有数千个光源）
        }
        for (int ly = 0; ly < Coords.CHUNK_HEIGHT; ly++) {
            for (int lz = 0; lz < Coords.CHUNK_SIZE; lz++) {
                for (int lx = 0; lx < Coords.CHUNK_SIZE; lx++) {
                    short id = chunk.blockAt(lx, ly, lz);
                    if (id == BlockRegistry.AIR_RUNTIME_ID) {
                        continue;
                    }
                    if (BlockRegistry.byRuntimeId(id).lightEmission() > 0) {
                        updateEmissive(chunk.originX() + lx, ly, chunk.originZ() + lz, id);
                    }
                }
            }
        }
    }

    public World(long seed, WorldGenerator generator) {
        this.seed = seed;
        this.generator = generator;
    }

    public long seed() {
        return seed;
    }

    public WorldGenerator generator() {
        return generator;
    }

    // ============================================================ 区块获取与生成

    /** 查询已加载区块；未加载返回 {@code null}。 */
    public Chunk chunkAt(int cx, int cz) {
        return chunks.get(Coords.chunkKey(cx, cz));
    }

    /**
     * 取区块，不存在则生成。
     *
     * <p>新区块生成后立即入队重建自身与四个邻居的网格：新地形会改变邻居沿边界的可见面。
     */
    public Chunk getOrLoadChunk(int cx, int cz) {
        long key = Coords.chunkKey(cx, cz);
        Chunk existing = chunks.get(key);
        if (existing != null) {
            return existing;
        }
        Chunk chunk = new Chunk(cx, cz);
        new GeneratorWriter(chunk).run();
        chunk.markGenerated();
        chunks.put(key, chunk);
        registerEmissiveFrom(chunk);

        enqueueMesh(chunk);
        markNeighborMeshDirty(cx, cz);
        return chunk;
    }

    /** 确保给定区块矩形范围内全部加载。 */
    public void ensureAreaLoaded(int minCx, int minCz, int maxCx, int maxCz) {
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                getOrLoadChunk(cx, cz);
            }
        }
    }

    /**
     * 卸载区块。
     *
     * <p><b>M1 不会自动调用它</b>：M1 的世界固定 4×4 区块、全部常驻。
     * 这里提供能力是为了让"M1 不卸载"成为<u>一个明确的决定</u>而不是"忘了实现"，
     * 并让加载/卸载在后续里程碑有现成的落点。
     *
     * @return 被卸载的区块；若不存在返回 {@code null}
     */
    public Chunk unloadChunk(int cx, int cz) {
        Chunk removed = chunks.remove(Coords.chunkKey(cx, cz));
        if (removed != null) {
            meshQueue.remove(removed);
            markNeighborMeshDirty(cx, cz);
        }
        return removed;
    }

    public int loadedChunkCount() {
        return chunks.size();
    }

    public Collection<Chunk> loadedChunks() {
        return chunks.values();
    }

    // ============================================================ 体素读取

    /** 越界或未加载区块一律返回空气（DDA 射线因此会"穿过"未加载区域，M1 已记录该限制）。 */
    public Block blockAt(int x, int y, int z) {
        return BlockRegistry.byRuntimeId(blockIdAt(x, y, z));
    }

    public short blockIdAt(int x, int y, int z) {
        if (!Coords.isInWorldY(y)) {
            return BlockRegistry.AIR_RUNTIME_ID;
        }
        Chunk chunk = chunks.get(Coords.chunkKey(Coords.toChunk(x), Coords.toChunk(z)));
        if (chunk == null) {
            return BlockRegistry.AIR_RUNTIME_ID;
        }
        return chunk.blockAt(Coords.localFast(x), y, Coords.localFast(z));
    }

    public boolean isAirAt(int x, int y, int z) {
        return blockIdAt(x, y, z) == BlockRegistry.AIR_RUNTIME_ID;
    }

    /**
     * 是否阻挡移动（碰撞判定口径，TECH_DESIGN §F.4）。
     *
     * <p><b>刻意不直接返回 {@code block.hasCollision()}：</b>
     * 那样"作物误配成 collision=true"就会造出一堵看不见的墙（PRD §7 R1）。
     * 这里走 {@link Block#blocksMovement()}，让<b>形态对玩法开关有否决权</b>。
     */
    public boolean hasCollisionAt(int x, int y, int z) {
        return blockAt(x, y, z).blocksMovement();
    }

    /**
     * 指定格坐标处的方块是否与给定 AABB 相交（<b>已含"是否阻挡"语义</b>）。
     *
     * <p>这是碰撞查询的<b>单一入口</b>：调用方不应先问
     * {@link #hasCollisionAt(int, int, int)} 再自己按满格算相交 ——
     * 半高台阶那样做会把台阶上方的空气也当成障碍（玩家撞上"空气墙"）。
     *
     * @param minX/minY/minZ 查询盒下界
     * @param maxX/maxY/maxZ 查询盒上界
     * @param bx/by/bz 被查询方块的格坐标
     */
    public boolean collidesWith(double minX, double minY, double minZ,
                                 double maxX, double maxY, double maxZ,
                                 int bx, int by, int bz) {
        return blockAt(bx, by, bz).intersects(minX, minY, minZ, maxX, maxY, maxZ, bx, by, bz);
    }

    /**
     * 指定格坐标处方块的<b>碰撞体顶面</b>世界 y 坐标；无碰撞体时返回 {@code by}。
     *
     * <p><b>为什么不能用 {@code by + 1} 代替：</b>
     * 满方块恰好是 {@code by + 1}，但半高台阶是 {@code by + 0.5}。
     * 落地吸附与站立高度都用这个值 —— 写死 {@code +1} 会让玩家
     * "站到台阶上却悬在半空"（视觉上浮空 0.5 格）。
     *
     * <p>无碰撞体（作物）返回 {@code by}，让吸附落在格底 ——
     * 作物本不该被撞到，这个值只是不让求解器除零/失控。
     */
    public double collisionTopAt(int bx, int by, int bz) {
        return by + blockAt(bx, by, bz).shape().collisionTopY();
    }

    /** 是否为实体方块（支撑判定口径）。 */
    public boolean isSolidAt(int x, int y, int z) {
        return blockAt(x, y, z).isSolid();
    }

    // ============================================================ Mutation API

    /**
     * 破坏方块。
     *
     * <p>校验顺序刻意固定：先范围、后存在性、最后可破坏性 ——
     * 这样失败原因总是最具体的那个，而不是被笼统的"不可破坏"掩盖掉"其实是越界"。
     */
    public MutationResult breakBlock(int x, int y, int z, MutationCause cause) {
        if (!Coords.isInWorldY(y)) {
            return reject("Y 超出世界范围: " + y);
        }
        Chunk chunk = chunks.get(Coords.chunkKey(Coords.toChunk(x), Coords.toChunk(z)));
        if (chunk == null) {
            return reject("区块未加载: (" + Coords.toChunk(x) + "," + Coords.toChunk(z) + ")");
        }
        int lx = Coords.localFast(x);
        int lz = Coords.localFast(z);
        short current = chunk.blockAt(lx, y, lz);
        Block block = BlockRegistry.byRuntimeId(current);
        if (block.isAir()) {
            return reject("目标位置是空气");
        }
        if (!block.isBreakable()) {
            return reject("方块不可破坏: " + block.id());
        }

        writeVoxel(chunk, x, y, z, BlockRegistry.AIR_RUNTIME_ID, cause);
        breakCount++;
        return MutationResult.ok();
    }

    /**
     * 放置方块（PRD 5.2 的完整校验，TECH_DESIGN §H.6）。
     *
     * <p>规则：
     * <ol>
     *   <li>y 必须落在可放置范围 {@code [1,127]}；</li>
     *   <li>区块必须已加载；</li>
     *   <li>目标位置必须是空气（M1 不做替换类特例）；</li>
     *   <li>方块必须允许放置；</li>
     *   <li><b>必须有相邻支撑</b> —— 六邻中至少一个是实体方块（PRD：禁止无支撑空气生成）；</li>
     *   <li>不得与实体碰撞箱重叠（由 {@code obstruction} 提供判定）。</li>
     * </ol>
     *
     * @param obstruction 可为 {@code null}，表示调用方明确声明"不考虑实体占位"（例如自测脚本放置装饰方块）
     */
    public MutationResult placeBlock(int x, int y, int z, int runtimeId,
                                     MutationCause cause, VoxelObstruction obstruction) {
        if (!Coords.isPlaceableY(y)) {
            return reject("Y 超出可放置范围 [" + Coords.MIN_PLACEABLE_Y + ","
                    + Coords.MAX_PLACEABLE_Y + "]: " + y);
        }
        Block block = BlockRegistry.byRuntimeId(runtimeId);
        if (block.isAir()) {
            return reject("不能放置空气");
        }
        if (!block.isPlaceable()) {
            return reject("该方块不允许放置: " + block.id());
        }
        Chunk chunk = chunks.get(Coords.chunkKey(Coords.toChunk(x), Coords.toChunk(z)));
        if (chunk == null) {
            return reject("区块未加载: (" + Coords.toChunk(x) + "," + Coords.toChunk(z) + ")");
        }
        if (chunk.blockAt(Coords.localFast(x), y, Coords.localFast(z)) != BlockRegistry.AIR_RUNTIME_ID) {
            return reject("目标位置已被占用: " + blockAt(x, y, z).id());
        }
        if (!hasAdjacentSupport(x, y, z)) {
            return reject("缺少相邻支撑（禁止无支撑空气生成）");
        }
        if (obstruction != null && obstruction.intersectsBlock(x, y, z)) {
            return reject("与实体碰撞箱重叠");
        }

        writeVoxel(chunk, x, y, z, (short) runtimeId, cause);
        placeCount++;
        return MutationResult.ok();
    }

    /** 六邻中至少一个是实体方块。 */
    public boolean hasAdjacentSupport(int x, int y, int z) {
        return isSolidAt(x - 1, y, z)
                || isSolidAt(x + 1, y, z)
                || isSolidAt(x, y - 1, z)
                || isSolidAt(x, y + 1, z)
                || isSolidAt(x, y, z - 1)
                || isSolidAt(x, y, z + 1);
    }

    private MutationResult reject(String reason) {
        rejectedCount++;
        return MutationResult.fail(reason);
    }

    /**
     * 唯一的写体素实现。
     *
     * <p>三种来源的记账差异必须体现在这里，而不是散落在调用方：
     * {@code GENERATION} / {@code SAVE_LOAD} 不改动"需要写回存档"的状态，
     * 其余来源（玩家、自测）标记 {@code saveDirty}。
     */
    private void writeVoxel(Chunk chunk, int x, int y, int z, short runtimeId, MutationCause cause) {
        int lx = Coords.localFast(x);
        int lz = Coords.localFast(z);
        short old = chunk.blockAt(lx, y, lz);
        boolean changed = chunk.setLocal(lx, y, lz, runtimeId);
        if (!changed) {
            return;
        }
        if (cause != MutationCause.GENERATION && cause != MutationCause.SAVE_LOAD) {
            chunk.markSaveDirty();
        }
        updateEmissive(x, y, z, runtimeId);
        enqueueMesh(chunk);
        markBoundaryNeighbors(chunk, lx, lz);
    }

    // ============================================================ 网格重建队列

    private void enqueueMesh(Chunk chunk) {
        chunk.markMeshDirty();
        meshQueue.add(chunk);
        trackQueuePeak();
    }

    /**
     * 边界改动必须让相邻区块一起重建。
     *
     * <p>这是 M1 指令 B6 的核心：只改自己会让邻居沿边界的那一圈面残留（多了不该有的面）
     * 或缺失（少了该有的面）。用 {@link Coords#boundaryMask} 判定，避免手写
     * {@code lx == 0 || lx == 15} 这类容易漏项的判断。
     */
    private void markBoundaryNeighbors(Chunk chunk, int lx, int lz) {
        int mask = Coords.boundaryMask(lx, lz);
        if (mask == 0) {
            return;
        }
        if ((mask & 1) != 0) {
            touchNeighbor(chunk.cx() - 1, chunk.cz());
        }
        if ((mask & 2) != 0) {
            touchNeighbor(chunk.cx() + 1, chunk.cz());
        }
        if ((mask & 4) != 0) {
            touchNeighbor(chunk.cx(), chunk.cz() - 1);
        }
        if ((mask & 8) != 0) {
            touchNeighbor(chunk.cx(), chunk.cz() + 1);
        }
    }

    private void touchNeighbor(int cx, int cz) {
        Chunk neighbor = chunks.get(Coords.chunkKey(cx, cz));
        if (neighbor != null) {
            neighborMarkCount++;
            enqueueMesh(neighbor);
        }
    }

    /** 新区块加载后，四个邻居沿边界的面也会改变，必须一起重建。 */
    private void markNeighborMeshDirty(int cx, int cz) {
        touchNeighbor(cx - 1, cz);
        touchNeighbor(cx + 1, cz);
        touchNeighbor(cx, cz - 1);
        touchNeighbor(cx, cz + 1);
    }

    public int pendingMeshRebuilds() {
        return meshQueue.size();
    }

    /**
     * 取一批待重建区块（<b>限量</b>）。
     *
     * <p>限量是为了避免"一次挖穿一大片"时在同一帧内重建十几个区块造成可见卡顿：
     * 队列会跨帧消化，而重建本身有确定的上界。
     *
     * @param limit 本次最多取多少个；&lt;=0 表示不限
     */
    public List<Chunk> pollMeshRebuilds(int limit) {
        List<Chunk> batch = new ArrayList<>();
        Iterator<Chunk> it = meshQueue.iterator();
        while (it.hasNext() && (limit <= 0 || batch.size() < limit)) {
            Chunk c = it.next();
            it.remove();
            batch.add(c);
        }
        return batch;
    }

    public void recordMeshBuild(long nanos) {
        meshBuildCount++;
        meshBuildTotalNanos += nanos;
    }

    public long meshBuildCount() {
        return meshBuildCount;
    }

    public double meanMeshBuildMs() {
        return meshBuildCount == 0 ? 0 : (meshBuildTotalNanos / 1_000_000.0) / meshBuildCount;
    }

    /**
     * 网格重建队列积压上限。
     *
     * <p>M1 的测试世界只有 16 个区块，正常情况队列长度是个位数。
     * 若队列长期 > 32，说明"改动 → 重建"的节奏被打乱（例如每帧重建全部），
     * 这属于必须被看见的信号，因此提供本方法给 HUD 与自测断言使用。
     */
    public int meshQueueHighWaterMark() {
        return meshQueueHighWaterMark;
    }

    private int meshQueueHighWaterMark = 0;

    private void trackQueuePeak() {
        meshQueueHighWaterMark = Math.max(meshQueueHighWaterMark, meshQueue.size());
    }

    // ============================================================ 统计

    public long breakCount() {
        return breakCount;
    }

    public long placeCount() {
        return placeCount;
    }

    public long rejectedMutationCount() {
        return rejectedCount;
    }

    public long neighborMarkCount() {
        return neighborMarkCount;
    }

    public String statsLine() {
        return String.format("区块=%d 队列=%d 破坏=%d 放置=%d 拒绝=%d 邻块标记=%d 网格重建=%d 平均%.2fms",
                chunks.size(), meshQueue.size(), breakCount, placeCount, rejectedCount,
                neighborMarkCount, meshBuildCount, meanMeshBuildMs());
    }

    // ============================================================ 存档支持

    /** 需要写回存档的区块（真正被玩家改过的）。 */
    public List<Chunk> saveDirtyChunks() {
        List<Chunk> out = new ArrayList<>();
        for (Chunk c : chunks.values()) {
            if (c.isSaveDirty()) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 回放存档里的方块改动。
     *
     * <p>走 {@link MutationCause#SAVE_LOAD}：会重建网格，但<u>不会</u>把区块重新标成 saveDirty
     * —— 否则每次启动都会把刚读出来的内容又写回去，且"有没有改动"这一信息会永久失真。
     */
    public boolean applySavedBlock(int x, int y, int z, short runtimeId) {
        if (!Coords.isInWorldY(y)) {
            Log.noteWarning("World", "存档中的方块 Y 越界，已跳过: y=" + y);
            return false;
        }
        Chunk chunk = chunks.get(Coords.chunkKey(Coords.toChunk(x), Coords.toChunk(z)));
        if (chunk == null) {
            Log.noteWarning("World", "存档中的方块位于未加载区块，已跳过: " + x + "," + y + "," + z);
            return false;
        }
        writeVoxel(chunk, x, y, z, runtimeId, MutationCause.SAVE_LOAD);
        return true;
    }

    public void markChunkSaved(Chunk chunk) {
        chunk.clearSaveDirty();
    }

    // ============================================================ 生成期只写门面

    /**
     * 交给生成器的写门面：只能写本区块、不能读、不能持有 {@link Chunk}。
     *
     * <p>它同时在 {@link #getOrLoadChunk} 之后被丢弃 —— 生成器一旦返回就再也拿不到句柄。
     */
    private final class GeneratorWriter implements ChunkWriter {

        private final Chunk target;

        GeneratorWriter(Chunk target) {
            this.target = target;
        }

        void run() {
            generator.generate(this, target.cx(), target.cz(), seed);
        }

        @Override
        public int chunkX() {
            return target.cx();
        }

        @Override
        public int chunkZ() {
            return target.cz();
        }

        @Override
        public int originX() {
            return target.cx() * Coords.CHUNK_SIZE;
        }

        @Override
        public int originZ() {
            return target.cz() * Coords.CHUNK_SIZE;
        }

        @Override
        public void set(int lx, int ly, int lz, int runtimeId) {
            if (lx < 0 || lx >= Coords.CHUNK_SIZE || lz < 0 || lz >= Coords.CHUNK_SIZE
                    || ly < 0 || ly >= Coords.CHUNK_HEIGHT) {
                return;
            }
            target.setLocal(lx, ly, lz, (short) runtimeId);
        }
    }
}

package com.skyisland.world;

import com.skyisland.util.Coords;
import com.skyisland.world.block.BlockRegistry;

/**
 * 资源核心慢速再生（PRD 4.6）。
 *
 * <h2>它解决的是"防软锁"，不是"产矿"</h2>
 * PRD 4.6 的意图写得很清楚：<b>有限矿脉 + 再生兜底</b>。
 * 三重防软锁必须同时成立，而本类只负责其中第 ② 条：
 * <ol>
 *   <li>核心不可破坏 —— {@code resource_core} 的 {@code breakable=false}，
 *       由 {@link World#breakBlock} 统一把关，本类不参与；</li>
 *   <li><b>慢速再生</b> —— 本类；</li>
 *   <li>怪物掉落兜底 —— 属夜间刷怪（M5b），尚未接线，已登记为遗留。</li>
 * </ol>
 *
 * <h2>★ 为什么速率必须远低于采矿（PRD 4.6 的比值约束）</h2>
 * 玩家远征采矿约 20–40 格/分钟（空手 1.5–3 秒一格），而石矿岛核心是
 * 180 秒 1 格 = <b>0.33 格/分钟</b>，比值 ≤ <b>1/50</b>。
 * 这个约束不是审美选择：站桩不动就能刷满矿，游戏就退化成"守着核心发呆"。
 * <p>⇒ 本类的周期常量是<b>规格值</b>，不是"手感不好就调小"的那种参数。
 * 任何改动都必须重跑 {@code ResourceCoreRegenTest} 的速率判据。
 *
 * <h2>为什么"不做离线计算"（PRD 4.6 明文）</h2>
 * 离线补算意味着"关掉游戏一晚上 = 早上多出几千格矿"，直接违反上面的比值约束 ——
 * 而且它没有任何玩法理由：远征的**乐趣**在路上，不在仓库里。
 * ⇒ 本类只在 {@link #tick} 被驱动时累计，进程停了就停。
 *
 * <h2>为什么走 {@link World#placeBlock} 而不是直接写 Chunk</h2>
 * 三条，都不是洁癖：
 * <ol>
 *   <li><b>不能覆盖玩家方块</b>（PRD 4.6「占用冲突」）：目标是空气才放，
 *       玩家放了石头在那里就必须跳过该格；</li>
 *   <li>必须经过<b>网格脏标记</b>，否则新矿石不可见（这是 M1 踩过的
 *       「live mining never re-meshed」那一类）；</li>
 *   <li>必须记进<b>存档增量</b>，否则退出后这次再生会重放。</li>
 * </ol>
 *
 * <h2>候选格扫描顺序必须是确定性的</h2>
 * 同一个世界状态下，"下一格刷在哪"必须可复现 —— 否则同一份存档读两次
 * 会得到不同的矿脉布局。⇒ 扫描按固定的 y 偏移顺序、x/z 按固定偏移展开，
 * 不使用 {@code java.util.Random}，随机性来自 {@code hash} 的<b>位置</b>。
 */
public final class ResourceCoreRegen {

    public enum IslandKind {
        MAIN, STONE, FOREST, METAL, CRYSTAL
    }

    /**
     * 一次再生的目标矿石种类，按岛类型决定。
     *
     * <p>PRD 4.3 各岛富集表。
     * ⇒ 取"该岛<b>最稀有</b>的那一种"，因为再生的作用是兜底而不是量产：
     * 让玩家总能拿到该岛的主产出（石矿岛→铁，金属岛→金，晶矿岛→晶体），
     * 而不是一个他本来就不缺的品种。
     */
    public static int oreFor(IslandKind kind) {
        return switch (kind) {
            case STONE -> BlockRegistry.ironOre().runtimeId();
            // 森林岛富集的是「原木 / 泥土 / 小麦种」（PRD 4.3），而原木是**方块**不是矿石。
            // ⇒ 这里填泥土：它是"该岛确实富集、且是矿石形态"的那一种
            //   （泥土的 drop 是自身，可被采集链消化）。
            case FOREST -> BlockRegistry.dirt().runtimeId();
            case METAL -> BlockRegistry.goldOre().runtimeId();
            case CRYSTAL -> BlockRegistry.crystalOre().runtimeId();
            // 主岛没有资源核心（PRD 4.6 参数表只列 4 座资源岛），
            // 这一支走不到；写成泥土而不是抛异常，是为了让"给个错岛也不会崩"
            // —— 崩在这里会让整个世界的逻辑步挂掉，而症状与真因毫无相似之处。
            case MAIN -> BlockRegistry.dirt().runtimeId();
        };
    }

    /**
     * 一个资源核心的位置与所属岛。
     *
     * @param islandKind 岛类型（决定再生哪种矿）
     * @param x 核心方块所在的世界 x（PRD 4.6：岛几何中心）
     * @param y 核心方块的 y
     * @param z 核心方块的 z
     */
    public record IslandCore(String key, IslandKind islandKind, int x, int y, int z) {
    }

    /**
     * 岛类型 → 核心的再生参数。
     *
     * @param periodSeconds 周期（秒）
     * @param perRun <b>单次刷新数量</b>（PRD 4.6 参数表的一列，当前全部为 1）
     * @param runPerHour 导出量（格/小时），给测试与报告核对速率用
     */
    record Profile(IslandKind kind, int periodSeconds, int perRun, int runPerHour) {
    }

    /**
     * 再生速率表（PRD 4.6 参数表）。
     *
     * <p>{@code runPerHour} 是给测试与报告看的<b>导出量</b>（格/小时），
     * 由它可以一眼核对"再生 ≤ 采矿的 1/50"这条约束，而不必去心算。
     * <p>★ {@code perRun} 必须真的驱动循环（见 {@link #runOnce}），
     * 否则它就是一个"定义了却从不被读取"的参数 —— 而 PRD 4.6 把它列成规格的一列，
     * 意味着将来调参会直接改它。一个不被读的参数会让那次改动**静默无效**。
     */
    private static final Profile[] PROFILES = {
            // 石矿岛：180 秒 1 格 → 20 格/小时
            new Profile(IslandKind.STONE, 180, 1, 20),
            // 森林岛：180 秒 1 格（PRD 表同石矿岛）
            new Profile(IslandKind.FOREST, 180, 1, 20),
            // 金属岛：180 秒 1 格
            new Profile(IslandKind.METAL, 180, 1, 20),
            // 晶矿岛：240 秒 1 格 → 15 格/小时
            new Profile(IslandKind.CRYSTAL, 240, 1, 15),
    };

    private static Profile profileOf(IslandKind kind) {
        for (Profile p : PROFILES) {
            if (p.kind() == kind) {
                return p;
            }
        }
        throw new IllegalArgumentException("没有为核心类型 " + kind + " 定义再生参数");
    }

    /**
     * 一次性覆写参数表（仅测试用）。
     *
     * <p>★ 为什么不靠反射改 {@code PROFILES}：record 的组件是
     * {@code private final}，JDK 25 下三条路全被封死 ——
     * {@code Field#set} 报 {@code IllegalAccessException}，
     * 设了 {@code setAccessible} 仍报 {@code Can not set final field}，
     * 换成 {@code VarHandle} 报 {@code UnsupportedOperationException}。
     * 症状三次都不像"改不动"，而真因只是通道选错了；继续绕只会写出
     * 一段比被测逻辑还难懂的仪式代码。
     * <p>⇒ 改成**把表交进来**。这比反射诚实：它让"参数表是数据"这件事
     * 在类型上成立，也让"单次刷新数量真的驱动循环"可以用一句构造调用验证。
     * <p>包可见而非 public：它只服务同包的测试，且刻意比产品路径更难被误用
     * （产品侧 {@link #PROFILES} 仍然是唯一默认值）。
     */
    static void overrideProfilesForTest(IslandKind kind, int periodSeconds,
                                        int perRun, int perHour) {
        for (int i = 0; i < PROFILES.length; i++) {
            if (PROFILES[i].kind() == kind) {
                PROFILES[i] = new Profile(kind, periodSeconds, perRun, perHour);
            }
        }
    }

    /** 当前参数表（只读快照），供测试断言覆写后是否还原。 */
    static Profile profileOfForTest(IslandKind kind) {
        return profileOf(kind);
    }

    /**
     * 一次再生的候选格 y 偏移序列（相对核心 y），固定顺序。
     *
     * <p>PRD 4.6：刷新范围 y ∈ [核心 y − 2, 核心 y + 1]。
     * <p>顺序刻意从<b>上往下</b>：玩家挖矿也是从上挖，被填回去的应该是
     * 浅层那些更常被挖到的格子。这个选择不影响合规性（范围固定），
     * 但影响"玩家挖了之后多久看到矿回来"的体感。
     */
    private static final int[] DY_ORDER = {1, 0, -1, -2};

    private final World world;
    private final java.util.List<IslandCore> cores = new java.util.ArrayList<>();

    /** 每个核心已累计的时间（秒）。用累计值而非"上次时间戳"，避免大 dt 的一次性跳变。 */
    private final java.util.Map<String, Double> elapsed = new java.util.LinkedHashMap<>();

    // ---- 可观测计数（"亮"必须能解释自己为什么是亮的）----
    private long ticks;
    private long regenRuns;
    private long oresPlaced;
    private long skippedOccupied;
    private long skippedChunkNotLoaded;
    private long skippedNoCandidate;

    public ResourceCoreRegen(World world) {
        this.world = world;
    }

    /** 登记一个核心。 */
    public void register(IslandCore core) {
        cores.add(core);
        elapsed.putIfAbsent(core.key(), 0.0);
    }

    public java.util.List<IslandCore> cores() {
        return java.util.List.copyOf(cores);
    }

    public int coreCount() {
        return cores.size();
    }

    public long regenRuns() {
        return regenRuns;
    }

    public long oresPlaced() {
        return oresPlaced;
    }

    /** 因为目标格被玩家方块占据而跳过的次数（PRD 4.6「不得覆盖玩家方块」）。 */
    public long skippedOccupied() {
        return skippedOccupied;
    }

    /** 因为候选格所在区块未加载而跳过的次数。 */
    public long skippedChunkNotLoaded() {
        return skippedChunkNotLoaded;
    }

    /**
     * 走完一轮候选扫描但一格都没放下的次数。
     *
     * <p>单列这个计数是因为它与"被玩家占用"是<b>两回事</b>：前者说明该岛
     * 核心周围的矿石已被挖空且玩家把位置全堵住了（正常，兜底已耗尽），
     * 后者说明玩家在核心周围放了方块（也正常，规格要求跳过）。
     * 合成一个计数就分不出这两种情况，而它们对"是否需要调参"的含义相反。
     */
    public long skippedNoCandidate() {
        return skippedNoCandidate;
    }

    /**
     * 最近一次实际放下的矿石 runtimeId；还没放过时返回 -1。
     *
     * <p>给测试与诊断用。它的存在理由与其它计数一样：
     * 「长出来的矿是哪一种」如果只能靠"跑一遍再扫描世界"来回答，
     * 那条断言就既慢又无法在失败时直接读数。
     */
    public int lastOreId() {
        return lastOreId;
    }

    private int lastOreId = -1;

    public long ticks() {
        return ticks;
    }

    /**
     * 推进一个逻辑步。
     *
     * @param dt 逻辑步长（秒）。<b>必须是固定步长的 dt</b>，不能用帧间隔 ——
     *            帧间隔会随负载抖动，导致同一段游戏时长下累计出的
     *            再生量不同（速率判据会随机地红）。
     */
    public void tick(double dt) {
        if (dt <= 0.0 || cores.isEmpty()) {
            return;
        }
        ticks++;
        for (IslandCore core : cores) {
            final Profile profile = profileOf(core.islandKind());
            double acc = elapsed.get(core.key()) + dt;
            int guard = 0;
            while (acc >= profile.periodSeconds() && guard++ < 64) {
                acc -= profile.periodSeconds();
                runOnce(core);
            }
            elapsed.put(core.key(), acc);
        }
    }

    /**
     * 一个周期到了就做一次再生。包可见，便于测试单次行为。
     *
     * <p>★ 不接收 {@code Profile} 参数：周期只决定"什么时候调用"，
     * 而"刷什么、放哪"只依赖 {@link IslandCore#islandKind()} 与三个范围常量。
     * 传进来却不用，是一个**看起来有依据、实则无作用**的签名 ——
     * 后来者会以为"单次行为依赖该岛的完整参数表"，而改表不会改变行为。
     * <p>唯一真的从表里取的是 {@code perRun}（单次刷新数量）。
     */
    void runOnce(IslandCore core) {
        regenRuns++;
        final int ore = oreFor(core.islandKind());
        final int wanted = profileOf(core.islandKind()).perRun();
        int placedThisRun = 0;
        search:
        for (int dy : DY_ORDER) {
            for (int dx = -CORE_HALF_SPAN; dx <= CORE_HALF_SPAN; dx++) {
                for (int dz = -CORE_HALF_SPAN; dz <= CORE_HALF_SPAN; dz++) {
                    if (!inSpawnRange(core, dy)) {
                        continue;
                    }
                    final int x = core.x() + dx;
                    final int y = core.y() + dy;
                    final int z = core.z() + dz;

                    if (world.chunkAt(Coords.toChunk(x), Coords.toChunk(z)) == null) {
                        skippedChunkNotLoaded++;
                        continue;
                    }
                    // ★ 目标是<b>石头</b>格，而不是空气格。
                    //   理由：核心在岛表面之上 1 格（y = 表面 + 1），
                    //   而 PRD 4.6 的刷新范围 y ∈ [核心−2, 核心+1] 正好落在岛体里 ——
                    //   若只在空气格里放矿，唯一的空气是核心正上方那格，
                    //   于是每 180 秒都在空中悬一块矿石，而岛里的矿脉永不恢复。
                    //   那既不解决"矿脉枯竭"，画面上也很怪。
                    //   ⇒ 改石头为矿：玩家挖到的就是它，防软锁才真的成立。
                    final int current = world.blockIdAt(x, y, z);
                    if (current != BlockRegistry.stone().runtimeId()) {
                        // 空气 / 玩家放的方块 / 已经是矿 —— 一律跳过，绝不覆盖
                        // （PRD 4.6「不得覆盖玩家方块」）。
                        skippedOccupied++;
                        continue;
                    }
                    World.MutationResult r = convertToOre(x, y, z, ore);
                    if (r.success()) {
                        oresPlaced++;
                        placedThisRun++;
                        lastOreId = ore;
                        // 放够 PRD 4.6「单次刷新数量」就收工。
                        // 标签而非计数器退出：外层三层的 `continue` 是
                        // "这格不行、试下一格"，不是"这次到此为止" ——
                        // 用计数器跳出三层的唯一办法就是标签。
                        if (placedThisRun >= wanted) {
                            break search;
                        }
                    } else {
                        convertedFailures++;
                    }
                }
            }
        }
        if (placedThisRun == 0) {
            // 走完整轮候选却一格都没放 ⇒ 兜底在该核心周围已耗尽。
            // 分开计数而不与 occupied 合并，原因见字段注释。
            skippedNoCandidate++;
        }
    }

    /**
     * 把一格<b>石头</b>改成矿石。
     *
     * <p>走两次正规改动而不是直接写 Chunk：
     * <ol>
     *   <li>{@code breakBlock} —— 清掉石头、标记网格脏、记破坏计数；</li>
     *   <li>{@code placeBlock} —— 写入矿石。</li>
     * </ol>
     * <p>★ 为什么不能合成一步：直接写会让新矿石<b>不进入存档增量</b>，
     *   退出后这次再生会重放 —— 表现为"重进游戏矿又变多了"。
     *   而 {@code breakBlock} 会给石头掉落，若绕过它则玩家会凭空收到石头。
     */
    private World.MutationResult convertToOre(int x, int y, int z, int ore) {
        World.MutationResult broken = world.breakBlock(x, y, z,
                World.MutationCause.PLAYER_BREAK);
        if (!broken.success()) {
            return broken;
        }
        return world.placeBlock(x, y, z, ore, World.MutationCause.PLAYER_PLACE, null);
    }

    /** 石头改矿石失败的次数（正常应为 0 —— 两个计数分开是为了能归因）。 */
    public long convertedFailures() {
        return convertedFailures;
    }

    private long convertedFailures;

    /**
     * 该 y 偏移是否落在 PRD 4.6 允许的范围内：y ∈ [核心 y − 2, 核心 y + 1]。
     *
     * <p>之所以还要再判一次而不是直接把 DY_ORDER 当作判据：{@link #DY_ORDER}
     * 是"扫描顺序"，而范围是"规格"。两者恰好相等，但把它们绑在一起就等于
     * 放弃了范围这条约束的可读性 —— 将来有人调整扫描顺序时会静默改掉规格。
     */
    private static boolean inSpawnRange(IslandCore core, int dy) {
        final int y = core.y() + dy;
        return y >= core.y() + CORE_MIN_DY && y <= core.y() + CORE_MAX_DY;
    }

    /** PRD 4.6：刷新范围 y ∈ [核心 y − 2, 核心 y + 1]。 */
    public static final int CORE_MIN_DY = -2;
    public static final int CORE_MAX_DY = 1;

    /** PRD 4.6：刷新范围 5×5 水平。 */
    public static final int CORE_HALF_SPAN = 2;

    /** 该岛核心的再生周期（秒）。 */
    public static int periodSecondsOf(IslandKind kind) {
        return profileOf(kind).periodSeconds();
    }

    /** 该岛核心的导出量（格/小时）—— 用于核对"≤ 采矿速率的 1/50"。 */
    public static int gramsPerHourOf(IslandKind kind) {
        return profileOf(kind).runPerHour();
    }

    /** 该岛核心再生哪种矿。 */
    public static int oreOf(IslandKind kind) {
        return oreFor(kind);
    }
}
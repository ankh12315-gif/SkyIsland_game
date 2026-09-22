package com.skyisland.save;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.skyisland.game.Version;
import com.skyisland.item.ItemRegistry;
import com.skyisland.player.Inventory;
import com.skyisland.player.ItemStack;
import com.skyisland.player.Player;
import com.skyisland.util.Log;
import com.skyisland.world.Chunk;
import com.skyisland.world.World;
import com.skyisland.world.block.BlockRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 存档管理（TECH_DESIGN §N.1 / §N.6 / §N.8 / §N.9）。
 *
 * <p><b>保存范围的硬约束（§N.9，PRD 明令"禁止保存整个世界"）：</b>
 * <table border="1">
 *   <tr><th>存</th><th>不存</th></tr>
 *   <tr><td>{@code level.json}（版本、Seed、生成器标识）</td><td>未被修改的区块</td></tr>
 *   <tr><td>{@code player.json}（位置、视向、快捷栏）</td><td>光照缓存（可由光源重算）</td></tr>
 *   <tr><td>{@code chunks/c.&lt;cx&gt;.&lt;cz&gt;.bin}（仅改动过的方块）</td><td>网格数据（可由方块重算）</td></tr>
 * </table>
 *
 * <p><b>"存档损坏时降级而非崩溃"是硬要求（§N.6）。</b>
 * 玩家丢一个区块的改动可以接受；整个存档打不开、程序崩溃不可接受。
 * 因此本类的读取路径上<u>任何</u>异常都转成"跳过并记录"，而不是向上抛。
 *
 * <p><b>Gson 的使用边界：</b>{@code TECH_DESIGN §P} 的包边界表允许 {@code save} 包使用 Gson
 * （{@code data} 包是"定义文件解析"的专属包，与此处的"状态持久化"职责不同）。
 */
public final class SaveManager {

    private final Path saveRoot;
    private final Path worldDir;
    private final Path chunkDir;
    private final String worldName;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public SaveManager(Path saveRoot, String worldName) {
        this.saveRoot = saveRoot;
        this.worldName = worldName;
        this.worldDir = SaveFormat.worldDirectory(saveRoot, worldName);
        this.chunkDir = worldDir.resolve(SaveFormat.CHUNK_DIR);
    }

    public Path saveRoot() {
        return saveRoot;
    }

    public Path worldDirectory() {
        return worldDir;
    }

    public Path chunkDirectory() {
        return chunkDir;
    }

    /** 是否已存在一个可加载的存档（判据是 {@code level.json}，而不是目录是否存在）。 */
    public boolean worldExists() {
        return Files.exists(worldDir.resolve(SaveFormat.LEVEL_FILE));
    }

    // ============================================================ 保存

    /**
     * 保存世界与玩家。
     *
     * <p>只写 {@code saveDirty} 的区块（§N.9）。"生成结果与存档一致"的区块会被
     * 显式<u>跳过</u>；若它此前有增量文件而现在与生成结果一致了（例如玩家把自己挖的坑填回去），
     * 那份过期的增量会被删除 —— 否则下次读档会用一个错误的差异覆盖正确的地形。
     */
    public SaveResult save(World world, Player player) {
        SaveResult result = SaveResult.ok("保存完成");
        try {
            Files.createDirectories(chunkDir);
            AtomicFileWriter.cleanStaleTemp(chunkDir);

            List<Chunk> dirty = world.saveDirtyChunks();
            for (Chunk chunk : dirty) {
                Path target = chunkDir.resolve(SaveFormat.chunkFileName(chunk.cx(), chunk.cz()));
                byte[] data = ChunkSerializer.serialize(chunk, world.generator(), world.seed());
                if (data == null) {
                    // 与生成结果完全一致：存档里不应该留下这份差异
                    if (Files.exists(target)) {
                        Files.delete(target);
                        Log.info("[Save] 区块 (%d,%d) 已恢复为生成态，删除过期增量",
                                chunk.cx(), chunk.cz());
                    }
                    world.markChunkSaved(chunk);
                    continue;
                }
                AtomicFileWriter.write(target, data);
                world.markChunkSaved(chunk);
                result.addChunkWritten();
            }

            LevelMeta meta = buildLevelMeta(world, countChunkFiles());
            writeJson(worldDir.resolve(SaveFormat.LEVEL_FILE), meta);
            writeJson(worldDir.resolve(SaveFormat.PLAYER_FILE), buildPlayerState(player));

            Log.info("[Save] %s", result.oneLine());
            for (String warning : result.warnings()) {
                Log.noteWarning("Save", warning);
            }
            return result;
        } catch (IOException e) {
            Log.error("[Save] 保存失败", e);
            SaveResult failed = SaveResult.failed("保存失败: " + e.getMessage());
            for (String warning : result.warnings()) {
                failed.warn(warning);
            }
            return failed;
        }
    }

    private LevelMeta buildLevelMeta(World world, int modifiedChunkCount) {
        LevelMeta meta = new LevelMeta();
        meta.saveVersion = SaveFormat.SAVE_VERSION;
        meta.worldName = worldName;
        meta.worldSeed = world.seed();
        meta.generatorId = world.generator().id();
        meta.generatorVersion = world.generator().generationVersion();
        meta.createdAtMillis = existingCreatedAtMillis();
        meta.savedAtMillis = System.currentTimeMillis();
        meta.productVersion = Version.version();
        meta.modifiedChunkCount = modifiedChunkCount;
        return meta;
    }

    /** 保留首次创建时间：读不到（首次保存）就用当前时间。 */
    private long existingCreatedAtMillis() {
        LevelMeta existing = readLevelMeta();
        return existing != null && existing.createdAtMillis > 0
                ? existing.createdAtMillis : System.currentTimeMillis();
    }

    private PlayerState buildPlayerState(Player player) {
        PlayerState state = new PlayerState();
        state.saveVersion = SaveFormat.SAVE_VERSION;
        state.x = player.position().x;
        state.y = player.position().y;
        state.z = player.position().z;
        state.yaw = player.camera().yawDeg();
        state.pitch = player.camera().pitchDeg();
        state.onGround = player.onGround();
        state.deaths = player.deaths();
        var safe = player.lastSafePosition();
        state.lastSafeX = safe.x;
        state.lastSafeY = safe.y;
        state.lastSafeZ = safe.z;
        state.selectedSlot = player.inventory().selectedSlot();

        // 稀疏写入：只写非空槽位，且带上显式 slot 索引（§N.3）
        //
        // ★ M2：这里必须写<b>物品</b>的 stable ID，不能写方块的。
        //   M1 的写法是 BlockRegistry.byRuntimeId(stack.blockRuntimeId()).id()，
        //   而 blockRuntimeId() 对"不是方块"的物品返回 −1（0 是空气）——
        //   于是手枪与手枪弹会被写成 "skyisland:air"，读档时变回空气：
        //   <b>玩家的枪在存档里静默消失</b>，而日志里只有一条"非法 runtimeId=-1"的告警，
        //   看起来像是存档损坏，而不是"这里用错了注册表"。
        //   （本次 M2 回归运行正是靠那条告警把它抓出来的 —— 断言全绿，缺陷却真实存在。）
        //
        // ★ 向后兼容：老存档里存的是方块 ID（如 "skyisland:dirt"），
        //   而 ItemRegistry 给方块物品分配的 stable ID <b>与方块 ID 完全相同</b>
        //   （由 ItemRegistry 的"前缀对齐"不变式保证），因此老存档读得出来。
        //   这正是当初选择"方块物品沿用方块 ID"而不是另起一套命名所换来的收益。
        // ★ M2.2：这里写的是 <b>36 格的绝对索引</b>（0..26 主背包、27..35 快捷栏）。
        //   v1 存档写的是"快捷栏内索引 0..8"，同一个数在两代存档里指向不同的格子 ——
        //   因此写入侧一并写下的 saveVersion（= SaveFormat.SAVE_VERSION）不是装饰，
        //   它是读取侧决定"这个 3 到底是主背包第 4 格还是快捷栏第 4 格"的<b>唯一</b>依据。
        //   迁移规则见 applyPlayerState。
        var slots = player.inventory().snapshot();
        for (int slot = 0; slot < slots.size(); slot++) {
            ItemStack stack = slots.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            state.inventory.add(new PlayerState.Slot(slot,
                    ItemRegistry.byRuntimeId(stack.itemRuntimeId()).id(), stack.count()));
        }
        return state;
    }

    private void writeJson(Path target, Object value) throws IOException {
        AtomicFileWriter.write(target, gson.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    private int countChunkFiles() {
        if (!Files.isDirectory(chunkDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(chunkDir)) {
            return (int) stream.filter(p -> p.getFileName().toString().endsWith(".bin")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    // ============================================================ 读取

    /**
     * 读取世界元数据（带 {@code .bak} 回退）。
     *
     * @return 元数据；不存在或无法解析返回 {@code null}
     */
    public LevelMeta readLevelMeta() {
        Path target = worldDir.resolve(SaveFormat.LEVEL_FILE);
        byte[] data = AtomicFileWriter.readWithBackup(target);
        if (data == null) {
            return null;
        }
        try {
            LevelMeta meta = gson.fromJson(new String(data, StandardCharsets.UTF_8), LevelMeta.class);
            if (meta == null) {
                Log.noteWarning("Save", "level.json 解析结果为空");
                return null;
            }
            return meta;
        } catch (RuntimeException e) {
            Log.noteWarning("Save", "level.json 解析失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 加载存档到世界与玩家。
     *
     * <p>执行顺序（§N.6）：读元数据 → 版本检查 → 逐区块应用增量 → 读玩家 → 位置合法性校验。
     * 顺序不可调换：玩家位置校验依赖"方块已经就位"。
     */
    public SaveResult loadInto(World world, Player player) {
        SaveResult result = SaveResult.ok("读档完成");
        if (!worldExists()) {
            return SaveResult.failed("没有可加载的存档（缺少 level.json）");
        }

        LevelMeta meta = readLevelMeta();
        if (meta == null) {
            return SaveResult.failed("level.json 无法解析，且没有可用备份");
        }
        for (String problem : meta.validate()) {
            result.warn("level.json 字段异常: " + problem);
        }

        if (meta.saveVersion > SaveFormat.SAVE_VERSION) {
            return SaveResult.failed("存档由更新版本创建（saveVersion=" + meta.saveVersion
                    + "，当前 " + SaveFormat.SAVE_VERSION + "），拒绝加载以免损坏数据");
        }
        if (meta.saveVersion < SaveFormat.SAVE_VERSION) {
            // ★ 迁移入口预留（§N.6）：MVP 只有 v1，无迁移代码，但入口必须存在，
            //   否则将来加 v2 时会有人直接把"版本不符"当成"损坏"而丢掉老存档。
            result.warn("存档 saveVersion=" + meta.saveVersion + " 低于当前 "
                    + SaveFormat.SAVE_VERSION + "，走迁移入口（当前无迁移实现）");
        }
        if (meta.generatorId != null && !meta.generatorId.equals(world.generator().id())) {
            result.warn("存档的生成器为 " + meta.generatorId + "，当前为 "
                    + world.generator().id() + " —— 地形可能与存档时不同");
        }
        if (meta.generatorVersion > 0
                && meta.generatorVersion != world.generator().generationVersion()) {
            result.warn("地形生成已变更（存档 " + meta.generatorVersion + " → 当前 "
                    + world.generator().generationVersion()
                    + "），未探索区域可能与原世界不同");
        }

        loadChunks(world, result);

        PlayerState state = readPlayerState(result);
        if (state != null) {
            applyPlayerState(player, state, result, world);
        } else {
            result.warn("player.json 不可用，玩家将出现在世界出生点");
        }

        Log.info("[Save] %s", result.oneLine());
        for (String warning : result.warnings()) {
            Log.noteWarning("Save", warning);
        }
        return result;
    }

    private void loadChunks(World world, SaveResult result) {
        if (!Files.isDirectory(chunkDir)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(chunkDir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("c.")
                            && p.getFileName().toString().endsWith(".bin"))
                    .sorted().forEach(files::add);
        } catch (IOException e) {
            result.warn("区块目录读取失败: " + e.getMessage());
            return;
        }

        for (Path file : files) {
            byte[] data = AtomicFileWriter.readWithBackup(file);
            if (data == null) {
                result.addCorrupted();
                result.warn("区块文件不可读，已跳过: " + file.getFileName());
                continue;
            }
            try {
                ChunkSerializer.Decoded decoded = ChunkSerializer.decode(data);
                int applied = ChunkSerializer.applyTo(decoded, world, result);
                result.addBlocksApplied(applied);
                result.addChunkLoaded();
            } catch (IOException e) {
                // ★ 容错：跳过该文件，该区块回到"生成态"，世界仍然可玩（§N.6）
                result.addCorrupted();
                result.warn("区块文件损坏，已跳过（该区块回到生成态）: "
                        + file.getFileName() + " —— " + e.getMessage());
            }
        }
    }

    private PlayerState readPlayerState(SaveResult result) {
        Path target = worldDir.resolve(SaveFormat.PLAYER_FILE);
        byte[] data = AtomicFileWriter.readWithBackup(target);
        if (data == null) {
            return null;
        }
        try {
            PlayerState state = gson.fromJson(new String(data, StandardCharsets.UTF_8), PlayerState.class);
            if (state == null) {
                result.warn("player.json 解析结果为空");
                return null;
            }
            for (String problem : state.validate()) {
                result.warn("player.json 字段异常: " + problem);
            }
            return state;
        } catch (RuntimeException e) {
            result.warn("player.json 解析失败: " + e.getMessage());
            return null;
        }
    }

    private void applyPlayerState(Player player, PlayerState state, SaveResult result, World world) {
        // ★ M2.2：v1 存档的 slot 是"快捷栏内索引 0..8"，v2 起是"36 格绝对索引"。
        //   不迁移的后果是物品一件不少、位置全部错位，而且<b>不触发任何校验</b>
        //   —— 每个字段单独看都合法，只有合起来看才知道读错了代。
        boolean migrateHotbarOnly = state.saveVersion < SaveFormat.SAVE_VERSION_INVENTORY_36;
        int migrated = 0;

        List<ItemStack> slots = new ArrayList<>();
        for (PlayerState.Slot slot : state.inventory) {
            // ★ M2：按<b>物品</b>表还原（与写入侧对称，见 toState 的说明）。
            //   stack 上限也必须按物品查（手枪弹 128、其余 64），
            //   写死 ItemStack.MAX_STACK 会把 128 发弹药截成 64 发。
            int runtimeId = ItemRegistry.runtimeIdOf(slot.item);
            int index = migrateHotbarOnly ? Inventory.HOTBAR_OFFSET + slot.slot : slot.slot;
            if (index < 0 || index >= Inventory.SLOT_COUNT) {
                // 越界的槽位必须说出来：静默丢弃的表现是"读档后少了东西"，
                // 而没有任何日志会指向这里。
                result.warn(String.format("存档中的物品槽位 %d（%s）越界，已丢弃该格",
                        slot.slot, slot.item));
                continue;
            }
            if (migrateHotbarOnly) {
                migrated++;
            }
            while (slots.size() <= index) {
                slots.add(ItemStack.EMPTY);
            }
            slots.set(index, ItemStack.of(runtimeId,
                    Math.min(slot.count, ItemRegistry.maxStackOf(runtimeId))));
        }
        if (migrated > 0) {
            Log.info("[存档] 读取到 v%d 存档：%d 格快捷栏物品已迁移到 36 格背包的槽位 %d..%d",
                    state.saveVersion, migrated,
                    Inventory.HOTBAR_OFFSET, Inventory.SLOT_COUNT - 1);
        }
        player.applyLoadedState(state.x, state.y, state.z, state.yaw, state.pitch,
                safeOrSelf(state.lastSafeX, state.lastSafeY, state.lastSafeZ, state.x, state.y, state.z),
                slots, state.selectedSlot, state.deaths);

        // §N.7：位置合法性校验必须在"方块已经就位"之后做，因此放在读档的最后一步。
        if (player.sanitizePositionAfterLoad(world)) {
            result.warn(String.format("存档中的玩家位置 (%.2f,%.2f,%.2f) 不合法（可能被方块埋住），"
                            + "已修正为 (%.2f,%.2f,%.2f)",
                    state.x, state.y, state.z,
                    player.position().x, player.position().y, player.position().z));
        }
    }

    private static double[] safeOrSelf(double safeX, double safeY, double safeZ,
                                       double x, double y, double z) {
        // lastSafePosition 全 0 是"旧存档没有该字段"的特征（生成式世界不会有 (0,0,0) 的合法落脚点）
        if (safeX == 0 && safeY == 0 && safeZ == 0) {
            return new double[]{x, y, z};
        }
        return new double[]{safeX, safeY, safeZ};
    }

    @Override
    public String toString() {
        return "SaveManager(" + saveRoot + " / " + worldName + ")";
    }
}

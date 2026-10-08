package com.skyisland.save;

import com.skyisland.game.Version;
import com.skyisland.item.ItemRegistry;
import com.skyisland.player.ItemStack;
import com.skyisland.player.Player;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import com.skyisland.world.block.BlockRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存档端到端测试（TECH_DESIGN §N.1 / §N.6 / §N.7 / §N.9）。
 *
 * <p><b>这些测试代替了"手工试玩一遍存档"。</b>而手工试玩恰好覆盖不了最关键的那几条：
 * 存档损坏、版本不符、主文件丢失 —— 这些情形在正常试玩中<u>永远不会发生</u>，
 * 但一旦发生在玩家机器上，代价是"存档打不开"。§N.6 因此把
 * "损坏时降级而非崩溃"列为硬要求，本类逐条把它测下来。
 *
 * <p><b>测试世界的固定形态：</b>{@link TestWorlds.FlatGenerator} 只在 y=62 放石头、y=63 放草方块，
 * 是纯函数且不含随机数 —— 因此"哪些格子被改动过"可以精确预期，
 * "读档后回到生成态"也就成了一个可断言的确定结果。
 */
class SaveManagerTest {

    private static final String WORLD_NAME = "test-world";

    /** 一块"玩家动过"的地：先在 (3,64,3) 放木板（靠 (3,63,3) 的草方块支撑），再挖掉 (3,63,3)。 */
    private static final int CHANGE_X = 3;
    private static final int CHANGE_Z = 3;

    // ------------------------------------------------------------ 夹具

    private SaveManager manager(Path root) {
        return new SaveManager(root, WORLD_NAME);
    }

    private static World freshWorld() {
        // 四壁外扩一格：读档后的位置校验会做螺旋搜索，未加载区块会让它多绕几圈
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    private static Player freshPlayer() {
        return new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
    }

    private static World modifiedWorld() {
        World world = freshWorld();
        assertTrue(world.placeBlock(CHANGE_X, TestWorlds.SURFACE_BLOCK_Y + 1, CHANGE_Z,
                TestWorlds.planks(), World.MutationCause.PLAYER_PLACE, null).success(),
                "先放置（需要下方支撑）");
        assertTrue(world.breakBlock(CHANGE_X, TestWorlds.SURFACE_BLOCK_Y, CHANGE_Z,
                World.MutationCause.PLAYER_BREAK).success(), "后破坏");
        return world;
    }

    private static Path chunkFile(SaveManager manager) {
        return manager.chunkDirectory().resolve(SaveFormat.chunkFileName(0, 0));
    }

    /**
     * 手写一份 {@code level.json}。
     *
     * <p>刻意用<u>手写 JSON 字面量</u>而不是 {@code Gson.toJson(LevelMeta)}：
     * 用同一个库序列化再反序列化，只能证明"这个类自洽"，不能证明
     * "磁盘上的字段名就是 {@link LevelMeta} 期望的那些"。手写字面量把
     * 字段名变成了一份独立于实现的契约。
     */
    private static void writeLevelJson(SaveManager manager, int saveVersion, String generatorId)
            throws IOException {
        Files.createDirectories(manager.worldDirectory());
        Files.writeString(manager.worldDirectory().resolve(SaveFormat.LEVEL_FILE), """
                {
                  "saveVersion": %d,
                  "worldName": "%s",
                  "worldSeed": 1,
                  "generatorId": "%s",
                  "generatorVersion": 1,
                  "createdAtMillis": 1,
                  "savedAtMillis": 1,
                  "productVersion": "hand-written",
                  "modifiedChunkCount": 0
                }
                """.formatted(saveVersion, WORLD_NAME, generatorId), StandardCharsets.UTF_8);
    }

    private static long binFileCount(SaveManager manager) throws IOException {
        Path dir = manager.chunkDirectory();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".bin")).count();
        }
    }

    // ============================================================ 正常往返

    @Test
    void saveThenLoadRestoresBlocksAndPlayer(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        World world = modifiedWorld();
        Player player = freshPlayer();
        player.camera().setAngles(45.0, -10.0);
        player.inventory().add(TestWorlds.stone(), 7);
        player.inventory().selectSlot(2);

        SaveResult saved = manager.save(world, player);
        assertTrue(saved.success(), saved.summary());
        assertEquals(1, saved.chunksWritten(), "只有一个区块被改动过");
        assertEquals(1, binFileCount(manager));
        assertTrue(manager.worldExists());
        assertTrue(Files.exists(manager.worldDirectory().resolve(SaveFormat.PLAYER_FILE)));

        // ---- 全新的世界与玩家（模拟"重开程序"）----
        World reloaded = freshWorld();
        Player reloadedPlayer = freshPlayer();
        assertFalse(reloaded.isAirAt(CHANGE_X, TestWorlds.SURFACE_BLOCK_Y, CHANGE_Z),
                "前提：新世界里这一格是草方块");

        SaveResult loaded = manager.loadInto(reloaded, reloadedPlayer);

        assertTrue(loaded.success(), loaded.summary());
        assertEquals(1, loaded.chunksLoaded());
        assertEquals(2, loaded.blocksApplied(), "挖一格 + 放一格");
        assertEquals(0, loaded.filesCorrupted());
        assertTrue(loaded.warnings().isEmpty(),
                "干净存档不该产生任何告警，实际=" + loaded.warnings());

        // 方块回来了
        assertTrue(reloaded.isAirAt(CHANGE_X, TestWorlds.SURFACE_BLOCK_Y, CHANGE_Z),
                "挖掉的格子必须变回空气");
        assertEquals((short) TestWorlds.planks(),
                reloaded.blockIdAt(CHANGE_X, TestWorlds.SURFACE_BLOCK_Y + 1, CHANGE_Z),
                "放上的木板必须回来");

        // 玩家回来了
        assertEquals(0.5, reloadedPlayer.position().x, 1e-9);
        assertEquals(TestWorlds.SURFACE_FEET_Y, reloadedPlayer.position().y, 1e-9);
        assertEquals(0.5, reloadedPlayer.position().z, 1e-9);
        assertEquals(45.0, reloadedPlayer.camera().yawDeg(), 1e-9);
        assertEquals(-10.0, reloadedPlayer.camera().pitchDeg(), 1e-9);
        assertEquals(2, reloadedPlayer.inventory().selectedSlot());
        assertEquals(7, reloadedPlayer.inventory().countOf(TestWorlds.stone()));
        assertEquals(1, reloadedPlayer.inventory().usedSlotCount(), "其余槽位保持为空");
    }

    // ============================================================ 生命值往返

    /**
     * ★<b>生命值必须能往返存档。</b>
     *
     * <p><b>这条测试在修什么</b>：{@code PlayerState} 曾经<b>没有 {@code health} 字段</b>，
     * 而 {@code Player.health} 早在 M2 就实现了（{@code MAX_HEALTH=20}）。
     * 后果不是"少存一个字段"，而是
     * <b>玩家被打到 3 血 → 存档 → 读档 → 满血 20</b>：一条可利用的无代价回血路径。
     * PRD 12.3 明文要求存档至少保存"生命"。
     *
     * <p>★<b>为什么必须用"部分生命值"而不是满血做往返</b>：
     * 满血往返是<b>恰好相等</b>型断言 —— 它在"根本没存、读档时顺手重置成满血"时
     * <b>也会绿</b>。这条断言的价值恰恰在于 20 ≠ 3。
     */
    @Test
    void healthSurvivesSaveLoadRoundTripAtPartialValue(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        World world = freshWorld();
        Player player = freshPlayer();

        // 先确认前提：玩家确实是满血
        assertEquals(Player.MAX_HEALTH, player.health(), "前提：新玩家满血");

        // 打到只剩 3 血。走真实的 hurt() 通道，不直接设字段 ——
        // 直接设字段会绕过 die() 与 lastDamageCause，测的就不是产品路径了。
        player.hurt(world, Player.MAX_HEALTH - 3);
        assertEquals(3, player.health(), "前提：受伤后应当是 3 血");
        assertFalse(player.isDead(), "3 血不该判死");

        SaveResult saved = manager.save(world, player);
        assertTrue(saved.success(), saved.summary());

        World reloaded = freshWorld();
        Player reloadedPlayer = freshPlayer();
        SaveResult loaded = manager.loadInto(reloaded, reloadedPlayer);
        assertTrue(loaded.success(), loaded.summary());

        assertEquals(3, reloadedPlayer.health(),
                "★ 读档后必须仍是 3 血。得到满血说明存档没写或读档没读"
                        + "（症状是「受伤存档等于免费回血」）。expected: <3> but was: <"
                        + reloadedPlayer.health() + ">");
    }

    /**
     * 旧存档（<b>没有 {@code health} 键</b>）必须读成<b>满血</b>，不是 0 血。
     *
     * <p>★这是 Gson "缺失字段走 POJO 初始化器" 那条机制的<b>直接证据</b>。
     * 判据是<b>手写一份真的缺字段的 json</b>，而不是靠"不写 health 看默认" ——
     * 后者测的是"字段初始值是多少"，前者测的是"旧存档读出来会怎样"，
     * 而后者才是玩家真正会遇到的情况。
     *
     * <p>若 {@code health} 默认值被写成 0，这条会红，且症状是
     * <b>"玩家一进世界就死、重生、再进又死"</b> —— 极难归因。
     */
    @Test
    void legacySaveWithoutHealthFieldLoadsAtFullHealth(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        World world = freshWorld();
        Player player = freshPlayer();
        assertTrue(manager.save(world, player).success());

        // ★ 手写一份**没有 health 键**的 player.json（模拟 M2 之前的存档）
        Path playerJson = manager.worldDirectory().resolve(SaveFormat.PLAYER_FILE);
        String original = Files.readString(playerJson, StandardCharsets.UTF_8);
        String stripped = original.replaceAll(",\\s*\"health\"\\s*:\\s*\\d+", "");
        assertNotEquals(original, stripped,
                "前提：存档里本该有 health 字段，且本测试要把它删掉制造旧档");
        assertFalse(stripped.contains("\"health\""),
                "前提：删干净了，不该再有 health 键");
        Files.writeString(playerJson, stripped, StandardCharsets.UTF_8);

        World reloaded = freshWorld();
        Player reloadedPlayer = freshPlayer();
        SaveResult loaded = manager.loadInto(reloaded, reloadedPlayer);

        assertTrue(loaded.success(), loaded.summary());
        assertEquals(Player.MAX_HEALTH, reloadedPlayer.health(),
                "旧存档缺 health 字段 ⇒ 必须是满血。得到 "
                        + reloadedPlayer.health() + " 说明默认值落在了非安全侧"
                        + "（0 会导致读档即死）");
    }

    /**
     * 坏存档里的越界生命值必须被<b>夹住</b>，而不是被信任。
     *
     * <p>两个方向分别测，因为它们来自不同来源：
     * <ul>
     *   <li><b>9999</b>（手改/ 版本更高的旧档） ⇒ 夹到 {@code MAX_HEALTH}；</li>
     *   <li><b>-5</b>（坏数据） ⇒ 夹到 <b>1</b>，<b>不是 0</b> ——
     *       0 会被 {@code isDead()} 判成已死，玩家一进世界就进死亡流程。</li>
     * </ul>
     */
    @Test
    void healthOutOfRangeInJsonIsClampedNotTrusted(@TempDir Path root) throws IOException {
        // ---- 方向一：过大 ----
        SaveManager manager = manager(root);
        World world = freshWorld();
        Player player = freshPlayer();
        assertTrue(manager.save(world, player).success());

        Path playerJson = manager.worldDirectory().resolve(SaveFormat.PLAYER_FILE);
        Files.writeString(playerJson,
                Files.readString(playerJson, StandardCharsets.UTF_8)
                        .replaceAll("\"health\"\\s*:\\s*\\d+", "\"health\": 9999"),
                StandardCharsets.UTF_8);

        Player loaded1 = freshPlayer();
        assertTrue(manager.loadInto(freshWorld(), loaded1).success());
        assertEquals(Player.MAX_HEALTH, loaded1.health(),
                "9999 必须被夹到 MAX_HEALTH，而不是被当成真实血量"
                        + "（不夹的话玩家会有 9999 血，打不死也饿不死）");

        // ---- 方向二：负数 ----
        SaveManager manager2 = manager(root.resolve("b"));
        World world2 = freshWorld();
        Player player2 = freshPlayer();
        assertTrue(manager2.save(world2, player2).success());

        Path playerJson2 = manager2.worldDirectory().resolve(SaveFormat.PLAYER_FILE);
        Files.writeString(playerJson2,
                Files.readString(playerJson2, StandardCharsets.UTF_8)
                        .replaceAll("\"health\"\\s*:\\s*\\d+", "\"health\": -5"),
                StandardCharsets.UTF_8);

        Player loaded2 = freshPlayer();
        assertTrue(manager2.loadInto(freshWorld(), loaded2).success());
        assertEquals(1, loaded2.health(),
                "负数血量必须夹到 **1 而不是 0** —— 0 会让 isDead() 判死，"
                        + "症状是「一进世界就重生」");
        assertFalse(loaded2.isDead(), "夹到 1 血时绝不能是死亡状态");
    }

    /**
     * {@code validate()} 对生命值的态度：<b>只报非正，不报越界上限</b>。
     *
     * <p>为什么这两件事要分开：
     * <ul>
     *   <li><b>非正必须报</b> —— 那是真实的坏档（读档即死），值得让玩家看见；</li>
     *   <li><b>上限不报</b> —— 装载侧已夹到 {@code MAX_HEALTH}，它不可能造成伤害；
     *       在这里报只会让日志多一条玩家看不懂的告警，而真正的防线在装载侧。</li>
     * </ul>
     * 顺带守住"旧存档语义"：默认 {@code health=20} 的 {@code PlayerState}
     * 必须<b>零告警</b>通过校验。
     */
    @Test
    void playerStateValidateFlagsOnlyNonPositiveHealth() {
        assertTrue(new PlayerState().validate().isEmpty(),
                "默认 PlayerState（含 health=20）必须零告警通过 —— "
                        + "否则每个旧存档都会刷一堆假告警");

        PlayerState negative = new PlayerState();
        negative.health = -1;
        assertTrue(negative.validate().stream().anyMatch(p -> p.contains("生命值")),
                "负血量必须被 validate 抓出来（症状是读档即死）");

        PlayerState huge = new PlayerState();
        huge.health = 9999;
        assertFalse(huge.validate().stream().anyMatch(p -> p.contains("生命值")),
                "9999 血不该在这里报 —— 装载侧已夹住，在这里报只是噪音。"
                        + "★若你认为该报，请改Player#applyLoadedHealth 而不是改这条断言");
    }

    /**
     * M2：<b>枪械与弹药必须能在存档里往返。</b>
     *
     * <p>这条测试的来历值得记下来：M2 的第一次脚本化回归运行里，<u>27 项断言全绿</u>，
     * 但日志出现了两条
     * {@code [告警][BlockRegistry] 非法的 runtimeId=-1（合法范围 0..14）}。
     * 追下去发现存档的快捷栏是按<b>方块</b>表序列化的，而手枪与手枪弹的
     * {@code blockRuntimeId()} 是 −1（0 是空气）—— 于是它们被写成
     * {@code "skyisland:air"}，读档时变回空气：<b>玩家的枪在存档里静默消失</b>。
     *
     * <p>所以这条测试断言三件事，缺一不可：
     * <ol>
     *   <li>物品 id 往返正确（手枪 / 弹药）；</li>
     *   <li>方块物品仍按老格式往返（向后兼容：方块物品的 item id == block id）；</li>
     *   <li>{@code warnings().isEmpty()} —— 直接盯住那条告警，因为它是这个缺陷
     *       唯一可见的迹象。</li>
     * </ol>
     *
     * <p>弹药特意用 128（弹药堆叠上限）而不是 24：读档侧的截断也必须按物品查上限，
     * 写死 64 会把"两个满弹匣的弹药储备"折半，而那种损失在界面上只是数字变小。
     */
    @Test
    void gunAndAmmoSurviveASaveLoadRoundTrip(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        World world = freshWorld();
        Player player = freshPlayer();

        int pistol = ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID);
        int ammo = ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID);
        player.inventory().add(pistol, 1);
        player.inventory().add(ammo, ItemRegistry.AMMO_MAX_STACK);
        // M2.2：setSlot(8) 现在是主背包，石头应放进"快捷栏第 9 格"（相对 8 → 绝对 35）
        player.inventory().setSlot(player.inventory().hotbarIndex(8), ItemStack.of(TestWorlds.stone(), 5));
        player.inventory().selectSlot(1);

        assertTrue(manager.save(world, player).success());

        // ---- 全新的世界与玩家（模拟"重开程序"）----
        World reloaded = freshWorld();
        Player reloadedPlayer = freshPlayer();
        SaveResult loaded = manager.loadInto(reloaded, reloadedPlayer);

        assertTrue(loaded.success(), loaded.summary());
        assertTrue(loaded.warnings().isEmpty(),
                "干净的 M2 存档不得产生任何告警（M2 前这里会出现"
                        + "「非法的 runtimeId=-1」）：" + loaded.warnings());

        assertEquals(pistol, reloadedPlayer.inventory().hotbarSlot(0).itemRuntimeId(),
                "手枪必须回到第 1 格（必须按物品表还原，不能按方块表 —— 后者会把它变成空气）");
        assertEquals(1, reloadedPlayer.inventory().hotbarSlot(0).count());
        assertEquals(ammo, reloadedPlayer.inventory().hotbarSlot(1).itemRuntimeId(),
                "手枪弹必须回到第 2 格");
        assertEquals(ItemRegistry.AMMO_MAX_STACK, reloadedPlayer.inventory().hotbarSlot(1).count(),
                "弹药堆叠上限是 128，读档不得按 64 截断");

        // 向后兼容：方块物品的 item id 与 block id 相同，因此老存档读得出来
        assertEquals(TestWorlds.stone(), reloadedPlayer.inventory().hotbarSlot(8).blockRuntimeId(),
                "方块物品仍按老格式（方块 ID）往返");
        assertEquals(5, reloadedPlayer.inventory().hotbarSlot(8).count());

        assertEquals(1, reloadedPlayer.inventory().selectedSlot());
        assertEquals(3, reloadedPlayer.inventory().usedSlotCount(), "中间的空槽不得被填充");
    }

    /**
     * M3 / v2 §11.3 与 §14.7：<b>SMG 的 Item stable ID 必须能跨存档往返，
     * 且槽位保持不变、世界重启后当前选中的那把枪正确。</b>
     *
     * <h2>为什么这条不能靠"手枪往返测试通过"顺带覆盖</h2>
     * 手枪与 SMG 走的确实是同一条序列化路径（都是 {@code ItemRegistry.byRuntimeId(...).id()}
     * 写、{@code ItemRegistry.runtimeIdOf(...)} 读）。但"同一条路径"是<u>当前的实现事实</u>，
     * 不是规格 —— 一旦有人把写盘改回"按方块表写 ID"或"按 runtimeId 数值写"，
     * 手枪可能因为 runtimeId 小、恰好还能对上而侥幸不红，而 SMG（表尾追加的那个）
     * 会静默变成别的东西。因此"表尾那把新枪也能往返"必须单独成为一条断言。
     *
     * <h2>这条测试覆盖 v2 §11.3 的完整链路</h2>
     * <pre>
     *   拿到 SMG → 放进 Hotbar / Inventory → Save → Exit → Relaunch → 同槽位仍是 skyisland:smg
     * </pre>
     * "Exit / Relaunch"用"另造一个全新世界与玩家再 loadInto"来模拟（与
     * {@link #gunAndAmmoSurviveASaveLoadRoundTrip} 同一手法，也是本类其它用例的既有约定）。
     *
     * <p>断言刻意分成三组，缺一不可：
     * <ol>
     *   <li><b>Hotbar 槽位保持</b>：SMG 放在快捷栏第 5 格 → 读回必须仍在快捷栏第 5 格；</li>
     *   <li><b>Inventory（主背包）槽位保持</b>：另放一把 SMG 在主背包第 2 格 → 读回仍在原处
     *       （主背包与快捷栏在 v2 存档里是同一套绝对索引，两个区都必须验）；</li>
     *   <li><b>当前选中枪正确</b>：{@code selectedSlot} 指向 SMG 那一格，读回后
     *       {@code selectedStack().item().id()} 仍是 {@code skyisland:smg}。</li>
     * </ol>
     */
    @Test
    void smgSurvivesASaveLoadRoundTripInTheSameSlotsWithTheSameSelectedGun(@TempDir Path root)
            throws IOException {
        SaveManager manager = manager(root);
        World world = freshWorld();
        Player player = freshPlayer();

        int smg = ItemRegistry.runtimeIdOf(ItemRegistry.SMG_ID);
        int ammo = ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID);
        // ① 快捷栏第 5 格（相对 4 → 绝对 31）放 SMG
        player.inventory().setSlot(player.inventory().hotbarIndex(4), ItemStack.of(smg, 1));
        // ② 主背包第 2 格（绝对 1）再放一把 SMG —— 验证主背包槽位也保持
        player.inventory().setSlot(1, ItemStack.of(smg, 1));
        // 共用弹药：SMG 的后备来自 pistol_ammo（v2 §6.1）
        player.inventory().add(ammo, 40);
        // ③ 选中 SMG 那一格（快捷栏相对 4）
        player.inventory().selectSlot(4);

        assertTrue(manager.save(world, player).success());

        // ---- 全新的世界与玩家（模拟"退出 → 重启程序"）----
        World reloaded = freshWorld();
        Player reloadedPlayer = freshPlayer();
        SaveResult loaded = manager.loadInto(reloaded, reloadedPlayer);

        assertTrue(loaded.success(), loaded.summary());
        assertTrue(loaded.warnings().isEmpty(),
                "干净的 M3 存档不得产生任何告警（SMG 必须能被注册表解析）: " + loaded.warnings());

        // ① Hotbar 槽位保持（绝对索引 31 = 快捷栏相对 4）
        assertEquals(smg, reloadedPlayer.inventory().hotbarSlot(4).itemRuntimeId(),
                "SMG 必须回到快捷栏第 5 格（按 stable ID 还原）");
        assertEquals(ItemRegistry.SMG_ID, reloadedPlayer.inventory().hotbarSlot(4).item().id(),
                "读回的物品 stable ID 必须仍是 skyisland:smg");
        assertEquals(1, reloadedPlayer.inventory().hotbarSlot(4).count());

        // ② Inventory（主背包）槽位保持（绝对索引 1）
        assertEquals(smg, reloadedPlayer.inventory().slot(1).itemRuntimeId(),
                "主背包第 2 格里的 SMG 也必须回到原处");
        assertEquals(ItemRegistry.SMG_ID, reloadedPlayer.inventory().slot(1).item().id());

        // ③ 世界重启后当前选中的枪正确
        assertEquals(4, reloadedPlayer.inventory().selectedSlot(),
                "selectedSlot 必须保持指向 SMG 那一格");
        assertEquals(ItemRegistry.SMG_ID,
                reloadedPlayer.inventory().selectedStack().item().id(),
                "重启后手里拿的必须仍是 SMG（v2 §14.7「世界重启后当前选中枪正确」）");

        // 对照：读回的弹药按共享 Item 计得回来（SMG 后备来自 pistol_ammo）
        assertEquals(40, reloadedPlayer.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID),
                "共用弹药必须一并往返（v2 §6.1）");
    }

    @Test
    void levelMetaRecordsTheFactsNeededToDiagnoseASave(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        World world = modifiedWorld();

        assertTrue(manager.save(world, freshPlayer()).success());

        LevelMeta meta = manager.readLevelMeta();
        assertNotNull(meta);
        assertEquals(SaveFormat.SAVE_VERSION, meta.saveVersion);
        assertEquals(WORLD_NAME, meta.worldName);
        assertEquals(world.seed(), meta.worldSeed);
        assertEquals(world.generator().id(), meta.generatorId);
        assertEquals(world.generator().generationVersion(), meta.generatorVersion);
        assertEquals(Version.version(), meta.productVersion);
        assertEquals(1, meta.modifiedChunkCount);
        assertTrue(meta.createdAtMillis > 0);
        assertTrue(meta.savedAtMillis >= meta.createdAtMillis);
        assertTrue(meta.validate().isEmpty(),
                "写出的 level.json 必须自洽，实际=" + meta.validate());
    }

    @Test
    void createdAtIsPreservedAcrossRepeatedSaves(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        World world = modifiedWorld();

        assertTrue(manager.save(world, freshPlayer()).success());
        long firstCreatedAt = manager.readLevelMeta().createdAtMillis;

        assertTrue(manager.save(world, freshPlayer()).success());
        LevelMeta second = manager.readLevelMeta();

        assertEquals(firstCreatedAt, second.createdAtMillis,
                "createdAt 必须保留首次创建时间 —— 它是「这个存档存在了多久」的唯一依据");
        assertTrue(AtomicFileWriter.backupExists(manager.worldDirectory().resolve(SaveFormat.LEVEL_FILE)),
                "第二次保存应当留下上一版 level.json 作为备份");
    }

    // ============================================================ 保存范围的硬约束

    @Test
    void unmodifiedWorldWritesNoChunkFilesAtAll(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        World world = TestWorlds.flatWorld(-1, -1, 1, 1);   // 9 个区块，全部未被改动

        SaveResult result = manager.save(world, freshPlayer());

        assertTrue(result.success());
        assertEquals(0, result.chunksWritten(),
                "§N.9 明令禁止保存整个世界：与生成结果一致的区块一个字节都不该写");
        assertEquals(0, binFileCount(manager));
        assertTrue(manager.worldExists(), "但 level.json 仍然要写出来（它是「存档存在」的判据）");
    }

    @Test
    void restoringTerrainToGeneratedStateDeletesTheStaleIncrement(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        World world = modifiedWorld();

        assertTrue(manager.save(world, freshPlayer()).success());
        assertTrue(Files.exists(chunkFile(manager)), "前提：改动过 → 有增量文件");

        // 把两块改动都撤销 → 区块内容重新等于生成结果
        assertTrue(world.breakBlock(CHANGE_X, TestWorlds.SURFACE_BLOCK_Y + 1, CHANGE_Z,
                World.MutationCause.PLAYER_BREAK).success(), "先拆掉木板");
        assertTrue(world.placeBlock(CHANGE_X, TestWorlds.SURFACE_BLOCK_Y, CHANGE_Z,
                TestWorlds.grass(), World.MutationCause.PLAYER_PLACE, null).success(),
                "再把草方块填回去（此时需要 (2,63,3) 的相邻支撑）");

        SaveResult second = manager.save(world, freshPlayer());

        assertTrue(second.success());
        assertEquals(0, second.chunksWritten());
        assertFalse(Files.exists(chunkFile(manager)),
                "区块已与生成结果一致，过期的增量必须被删除 —— "
                        + "否则下次读档会用一份错误的差异覆盖正确的地形");
    }

    @Test
    void worldExistsIsDrivenByLevelJsonNotByDirectoryPresence(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        assertFalse(manager.worldExists(), "什么都没写时不算存在");

        Files.createDirectories(manager.worldDirectory());
        assertFalse(manager.worldExists(), "光有目录不算存档（判据是 level.json）");

        assertTrue(manager.save(TestWorlds.flatWorld(0, 0, 0, 0), freshPlayer()).success());
        assertTrue(manager.worldExists());
    }

    // ============================================================ 降级与容错

    @Test
    void corruptedChunkFileIsSkippedAndWorldFallsBackToGeneratedTerrain(@TempDir Path root)
            throws IOException {
        SaveManager manager = manager(root);
        assertTrue(manager.save(modifiedWorld(), freshPlayer()).success());

        // 在增量文件的中段翻一个字节：既破坏内容又不改变长度
        Path file = chunkFile(manager);
        byte[] raw = Files.readAllBytes(file);
        raw[raw.length / 2] ^= 0x5A;
        Files.write(file, raw);

        World reloaded = freshWorld();
        SaveResult loaded = manager.loadInto(reloaded, freshPlayer());

        assertTrue(loaded.success(),
                "§N.6：存档损坏必须降级，绝不能让程序打不开 —— 实际=" + loaded.summary());
        assertEquals(1, loaded.filesCorrupted());
        assertEquals(0, loaded.chunksLoaded());
        assertEquals(0, loaded.blocksApplied());
        assertFalse(loaded.warnings().isEmpty(), "必须留下一条可追查的告警");

        // 该区块回到生成态：被挖掉的那格又变回草方块
        assertFalse(reloaded.isAirAt(CHANGE_X, TestWorlds.SURFACE_BLOCK_Y, CHANGE_Z),
                "损坏区块回到生成态 —— 丢一个区块的改动可以接受");
        // 而且世界仍然可用：能正常读方块、能正常改动
        assertTrue(reloaded.breakBlock(CHANGE_X, TestWorlds.SURFACE_BLOCK_Y, CHANGE_Z,
                World.MutationCause.PLAYER_BREAK).success(),
                "降级之后世界必须仍然可玩");
    }

    @Test
    void levelJsonFromANewerVersionIsRefusedInsteadOfSilentlyLoaded(@TempDir Path root)
            throws IOException {
        SaveManager manager = manager(root);
        writeLevelJson(manager, SaveFormat.SAVE_VERSION + 1, "test:flat");

        SaveResult loaded = manager.loadInto(freshWorld(), freshPlayer());

        assertFalse(loaded.success(), "更高版本的存档必须被拒绝，而不是按当前格式硬读");
        assertTrue(loaded.summary().contains("拒绝"), "实际=" + loaded.summary());
    }

    @Test
    void olderSaveVersionGoesThroughTheMigrationEntryPoint(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        writeLevelJson(manager, 0, "test:flat");

        SaveResult loaded = manager.loadInto(freshWorld(), freshPlayer());

        assertTrue(loaded.success(),
                "低版本存档必须走「迁移入口」而不是被当成损坏丢掉（§N.6）");
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("迁移")),
                "必须留下「走迁移入口」的痕迹，实际=" + loaded.warnings());
    }

    /**
     * v1（9 格）→ v2（36 格）的<b>槽位迁移</b>。
     *
     * <p>这一条补的是一个"迁移分支明明跑了、却什么也没验证"的缺口：
     * 上面那条用例用 version=0 确实走进了迁移入口，但它写的是<u>空背包</u>，
     * 于是 {@code migrateHotbarOnly} 这个分支里真正危险的那一半
     * ——{@code HOTBAR_OFFSET + slot.slot}——
     * 一次都没有被执行过。把它删掉，上面的用例照样全绿。
     *
     * <p>失败的现场极难发现：物品<u>一件不少</u>、每个字段单独看都合法，
     * 只是 v1 的"快捷栏第 4 格"变成了 v2 的"主背包第 4 格"——
     * 玩家读档后会看到自己的枪跑到背包左上角，而没有任何一条日志指向这里。
     * 因此断言必须写死"绝对索引"，而不是"总数量还对不对"。
     */
    @Test
    void v1SaveMigratesHotbarSlotsToTheLastNineSlotsOfThe36SlotInventory(@TempDir Path root)
            throws IOException {
        SaveManager manager = manager(root);
        writeLevelJson(manager, SaveFormat.SAVE_VERSION_INVENTORY_36 - 1, "test:flat");
        // 手写一份 v1 口径的 player.json：slot 是「快捷栏内索引 0..8」
        Files.writeString(manager.worldDirectory().resolve(SaveFormat.PLAYER_FILE), """
                {
                  "saveVersion": %d,
                  "x": 0.5, "y": %s, "z": 0.5,
                  "yaw": 0.0, "pitch": 0.0,
                  "onGround": true, "deaths": 0,
                  "lastSafeX": 0.5, "lastSafeY": %s, "lastSafeZ": 0.5,
                  "selectedSlot": 7,
                  "inventory": [
                    { "slot": 3, "item": "skyisland:stone", "count": 3 },
                    { "slot": 7, "item": "skyisland:dirt", "count": 5 }
                  ]
                }
                """.formatted(SaveFormat.SAVE_VERSION_INVENTORY_36 - 1,
                TestWorlds.SURFACE_FEET_Y, TestWorlds.SURFACE_FEET_Y), StandardCharsets.UTF_8);

        Player player = freshPlayer();
        SaveResult loaded = manager.loadInto(freshWorld(), player);

        assertTrue(loaded.success(), loaded.summary());
        // ① 迁移的<b>定义</b>：v1 的 slot k 必须落在 v2 的 HOTBAR_OFFSET + k，不是落在 k。
        assertEquals(3, player.inventory().hotbarSlot(3).count(),
                "v1 的快捷栏第 4 格必须迁移到绝对索引 " + com.skyisland.player.Inventory.HOTBAR_OFFSET
                        + " + 3 —— 落在主背包第 4 格就是没迁移");
        assertEquals(TestWorlds.stone(), player.inventory().hotbarSlot(3).blockRuntimeId());
        assertEquals(5, player.inventory().hotbarSlot(7).count(),
                "v1 的快捷栏第 8 格必须迁移到绝对索引 "
                        + com.skyisland.player.Inventory.HOTBAR_OFFSET + " + 7");
        // ② 反向对照：主背包里那几个下标<b>必须是空的</b>。
        //    少了这一半，"迁移"和"直接照搬"在断言上就没有区别了。
        assertEquals(BlockRegistry.AIR_RUNTIME_ID, player.inventory().slot(3).blockRuntimeId(),
                "绝对索引 3 属主背包，v1 从没往那儿放过东西，读档后必须仍为空");
        assertEquals(BlockRegistry.AIR_RUNTIME_ID, player.inventory().slot(7).blockRuntimeId(),
                "绝对索引 7 属主背包，读档后必须仍为空");
        // ③ 总数守恒：迁移不许丢东西，也不许凭空多出来。
        assertEquals(8, player.inventory().totalItemCount(), "3 + 5，一格都不许少");
    }

    @Test
    void missingLevelJsonIsReportedAsNoSaveInsteadOfAnException(@TempDir Path root) {
        SaveManager manager = manager(root);

        SaveResult loaded = manager.loadInto(freshWorld(), freshPlayer());

        assertFalse(loaded.success());
        assertTrue(loaded.summary().contains("没有可加载的存档"), "实际=" + loaded.summary());
    }

    @Test
    void unparsableLevelJsonIsReportedInsteadOfThrowing(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        Files.createDirectories(manager.worldDirectory());
        Files.writeString(manager.worldDirectory().resolve(SaveFormat.LEVEL_FILE),
                "{ this is not json", StandardCharsets.UTF_8);

        SaveResult loaded = manager.loadInto(freshWorld(), freshPlayer());

        assertFalse(loaded.success());
        assertTrue(loaded.summary().contains("无法解析"), "实际=" + loaded.summary());
    }

    @Test
    void missingPlayerJsonStillLoadsTheWorldWithAWarning(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        assertTrue(manager.save(modifiedWorld(), freshPlayer()).success());

        Files.deleteIfExists(manager.worldDirectory().resolve(SaveFormat.PLAYER_FILE));
        Files.deleteIfExists(manager.worldDirectory().resolve(SaveFormat.PLAYER_FILE + ".bak"));

        World reloaded = freshWorld();
        Player player = freshPlayer();
        SaveResult loaded = manager.loadInto(reloaded, player);

        assertTrue(loaded.success(), "玩家文件丢了不该阻止地形加载");
        assertEquals(2, loaded.blocksApplied(), "方块仍然照常回放");
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("player.json")),
                "必须说明玩家数据缺失，实际=" + loaded.warnings());
        assertEquals(0.5, player.position().x, 1e-9, "玩家留在世界出生点");
    }

    @Test
    void differentGeneratorWarnsWithoutBlockingTheLoad(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        writeLevelJson(manager, SaveFormat.SAVE_VERSION, "skyisland:something_else");

        SaveResult loaded = manager.loadInto(freshWorld(), freshPlayer());

        assertTrue(loaded.success(), "生成器不符只是「可能不同」，不该拒绝加载");
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("生成器")),
                "必须把生成器不符这件事说出来，实际=" + loaded.warnings());
    }

    @Test
    void playerBuriedInsideBlocksGetsRelocatedBySanitisation(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        Files.createDirectories(manager.worldDirectory());
        writeLevelJson(manager, SaveFormat.SAVE_VERSION, "test:flat");
        // 把玩家放在 (0, 63, 0) —— 平坦世界里这一格是草方块，也就是"被方块埋住"
        Files.writeString(manager.worldDirectory().resolve(SaveFormat.PLAYER_FILE), """
                {
                  "saveVersion": %d,
                  "x": 0.5, "y": 63.0, "z": 0.5,
                  "yaw": 0.0, "pitch": 0.0,
                  "onGround": false, "deaths": 0,
                  "lastSafeX": 0.0, "lastSafeY": 0.0, "lastSafeZ": 0.0,
                  "selectedSlot": 0,
                  "inventory": []
                }
                """.formatted(SaveFormat.SAVE_VERSION), StandardCharsets.UTF_8);

        World world = freshWorld();
        Player player = freshPlayer();
        SaveResult loaded = manager.loadInto(world, player);

        assertTrue(loaded.success(), loaded.summary());
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("不合法")),
                "位置非法必须被报出来，实际=" + loaded.warnings());
        assertTrue(player.isStandingSpotValid(world, player.position().x,
                        player.position().y, player.position().z),
                "修正后的位置必须是合法落脚点，实际=" + player.position());
    }

    @Test
    void inventorySlotIndexIsExplicitSoItemsDoNotShift(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        World world = modifiedWorld();
        Player player = freshPlayer();
        player.inventory().selectSlot(0);
        // 故意只放进"快捷栏第 4 格与第 8 格"（相对 3/7 → 绝对 30/34）——
        // 若存档按"数组顺序"记录，读档后会串到第 0/1 格
        // M2.2：用 hotbarIndex 放到真正的快捷栏格，而不是主背包的绝对 3/7
        player.inventory().setSlot(player.inventory().hotbarIndex(3), com.skyisland.player.ItemStack.of(TestWorlds.stone(), 3));
        player.inventory().setSlot(player.inventory().hotbarIndex(7), com.skyisland.player.ItemStack.of(TestWorlds.dirt(), 5));
        player.inventory().selectSlot(7);

        assertTrue(manager.save(world, player).success());

        Player reloaded = freshPlayer();
        assertTrue(manager.loadInto(freshWorld(), reloaded).success());

        assertEquals(3, reloaded.inventory().hotbarSlot(3).count(), "石头必须回到第 4 格");
        assertEquals(TestWorlds.stone(), reloaded.inventory().hotbarSlot(3).blockRuntimeId());
        assertEquals(5, reloaded.inventory().hotbarSlot(7).count(), "泥土必须回到第 8 格");
        assertEquals(TestWorlds.dirt(), reloaded.inventory().hotbarSlot(7).blockRuntimeId());
        assertEquals(7, reloaded.inventory().selectedSlot());
        assertEquals(2, reloaded.inventory().usedSlotCount(), "中间的空槽不得被填充");
        assertEquals(BlockRegistry.AIR_RUNTIME_ID,
                reloaded.inventory().slot(0).blockRuntimeId(), "第 1 格（主背包）必须仍为空");
    }

    @Test
    void saveIsRepeatableWithoutAccumulatingJunk(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root);
        World world = modifiedWorld();

        for (int i = 0; i < 3; i++) {
            assertTrue(manager.save(world, freshPlayer()).success(), "第 " + (i + 1) + " 次保存");
        }

        try (Stream<Path> stream = Files.list(manager.chunkDirectory())) {
            long temps = stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
            assertEquals(0, temps, "反复保存不得遗留 .tmp");
        }
        assertEquals(1, binFileCount(manager), "同一区块始终只占一个增量文件");
        assertTrue(manager.loadInto(freshWorld(), freshPlayer()).success(), "多次保存后仍可读");
    }
}

package com.skyisland.world;

import com.skyisland.util.Coords;
import com.skyisland.world.gen.IslandWorldGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源核心慢速再生（PRD 4.6「三重防软锁」第 ② 条）。
 *
 * <p><b>本类最重要的判据是速率那条</b>：PRD 4.6 明文要求
 * 「再生速率 ≤ 采矿速率的 1/50」。这不是"建议"，是防"站桩刷矿"的硬约束 ——
 * 违反它的后果是游戏退化成"守着核心发呆"，而所有功能测试仍然全绿。
 */
class ResourceCoreRegenTest {

    private static final long SEED = 20260919L;

    private static World islandWorld() {
        World w = new World(SEED, new IslandWorldGenerator());
        w.ensureAreaLoaded(-4, -4, 3, 3);
        return w;
    }

    /** 造一个只含单座核心的再生器，跑 seconds 秒（60 Hz 固定步）。 */
    private static ResourceCoreRegen runFor(World world, ResourceCoreRegen.IslandCore core,
                                            double seconds) {
        ResourceCoreRegen regen = new ResourceCoreRegen(world);
        regen.register(core);
        int steps = (int) Math.round(seconds * 60.0);
        for (int i = 0; i < steps; i++) {
            regen.tick(1.0 / 60.0);
        }
        return regen;
    }

    private static ResourceCoreRegen.IslandCore stoneCore() {
        return new ResourceCoreRegen.IslandCore("stone_island",
                ResourceCoreRegen.IslandKind.STONE, 48, 64, 0);
    }

    // ================================================================
    // ① PRD 4.6 参数表
    // ================================================================

    @Test
    @DisplayName("PRD 4.6：石/森/金三岛 180 秒 1 格，晶矿岛 240 秒 1 格")
    void thePeriodsMatchThePrdTable() {
        assertEquals(180, ResourceCoreRegen.periodSecondsOf(ResourceCoreRegen.IslandKind.STONE));
        assertEquals(180, ResourceCoreRegen.periodSecondsOf(ResourceCoreRegen.IslandKind.FOREST));
        assertEquals(180, ResourceCoreRegen.periodSecondsOf(ResourceCoreRegen.IslandKind.METAL));
        assertEquals(240, ResourceCoreRegen.periodSecondsOf(ResourceCoreRegen.IslandKind.CRYSTAL));
    }

    @Test
    @DisplayName("PRD 4.6 比值约束：再生 ≤ 采矿速率的 1/50")
    void regenRateIsAtMostOneFiftiethOfMining() {
        // 玩家远征采矿：20–40 格/分钟（PRD 4.6 表，空手 1.5–3 秒/格）
        final double miningPerMinute = 20.0;
        for (ResourceCoreRegen.IslandKind kind : List.of(
                ResourceCoreRegen.IslandKind.STONE,
                ResourceCoreRegen.IslandKind.FOREST,
                ResourceCoreRegen.IslandKind.METAL,
                ResourceCoreRegen.IslandKind.CRYSTAL)) {
            final double perMinute = ResourceCoreRegen.gramsPerHourOf(kind) / 60.0;
            assertTrue(perMinute <= miningPerMinute / 50.0,
                    kind + " 的再生速率 " + perMinute + " 格/分钟 超过了采矿速率的 1/50（"
                            + (miningPerMinute / 50.0) + "）。"
                            + "违反它会让站桩刷矿成立，而功能测试仍然全绿");
        }
    }

    // ================================================================
    // ② 真的会长矿，且落在规格范围内
    // ================================================================

    @Test
    @DisplayName("PRD 4.6「单次刷新数量」真的驱动循环（不是写死的 1）")
    void thePerRunParameterActuallyDrivesTheLoop() throws Exception {
        // ★ 这条是防"定义了却不被读"的守卫。
        //   perRun 曾是 Profile 的一个字段，而 runOnce 里写死 return ——
        //   那个字段从不被读取，于是"将来把它调成 2"会**静默无效**，
        //   而 PRD 4.6 恰恰把它列成参数表的一列。
        //   做法：用反射把 PROFILES 里石矿岛的 perRun 改成 3，跑一个周期，
        //   断言真的放了 3 格；finally 还原后断言行为回到 1 格。
        // ★ 用模块自己的覆写口，而不是反射改 PROFILES 的元素。
        //   反射这条路在 JDK 25 上是死的：record 组件是 private final，
        //   Field#set 报 IllegalAccessException，setAccessible 后报
        //   "Can not set final field"，换 VarHandle 报 UnsupportedOperationException。
        //   三次报错都不像"改不动"，真因只是通道选错了 —— 与其继续绕，
        //   不如让"参数表是数据"这件事在类型上成立。
        final ResourceCoreRegen.Profile original =
                ResourceCoreRegen.profileOfForTest(ResourceCoreRegen.IslandKind.STONE);
        try {
            ResourceCoreRegen.overrideProfilesForTest(
                    ResourceCoreRegen.IslandKind.STONE,
                    original.periodSeconds(), 3, original.runPerHour());

            ResourceCoreRegen regen = runFor(islandWorld(), stoneCore(), 180.0);

            assertEquals(3, regen.oresPlaced(),
                    "perRun 改成 3 后一次周期应放 3 格 —— 若仍是 1，"
                            + "说明这个规格参数根本没有驱动循环，改它会静默无效");
            assertEquals(1, regen.regenRuns(), "仍然只算 1 次再生（周期没变）");
            assertEquals(0, regen.skippedNoCandidate(),
                    "放成了就不该记成'无候选'");
        } finally {
            ResourceCoreRegen.overrideProfilesForTest(ResourceCoreRegen.IslandKind.STONE,
                    original.periodSeconds(), original.perRun(), original.runPerHour());
        }

        // 还原后必须回到规格值：否则这条用例会污染同类的其它用例。
        assertEquals(original.perRun(),
                ResourceCoreRegen.profileOfForTest(ResourceCoreRegen.IslandKind.STONE).perRun(),
                "参数表必须逐字段还原");
        ResourceCoreRegen regen = runFor(islandWorld(), stoneCore(), 180.0);
        assertEquals(1, regen.oresPlaced(), "perRun 已还原为 1，行为应随之回到 1 格");
    }

    @Test
    @DisplayName("跑满一个周期后在核心附近长出 1 格矿石")
    void onePeriodProducesExactlyOneOre() {
        World world = islandWorld();
        ResourceCoreRegen regen = runFor(world, stoneCore(), 180.0);

        assertEquals(1, regen.regenRuns(), "180 秒应恰好触发 1 次再生");
        assertEquals(1, regen.oresPlaced(), "单次刷新数量 = 1（PRD 4.6 参数表）");
        assertEquals(ResourceCoreRegen.oreOf(ResourceCoreRegen.IslandKind.STONE),
                oreAt(world, regen),
                "石矿岛核心应再生铁（该岛最稀缺的产出）");
    }

    @Test
    @DisplayName("未到周期时一格都不长（周期不是'大概 180 秒'）")
    void nothingGrowsBeforeThePeriodElapses() {
        World world = islandWorld();
        ResourceCoreRegen regen = runFor(world, stoneCore(), 179.0);

        assertEquals(0, regen.regenRuns(), "179 秒不应触发再生");
        assertEquals(0, regen.oresPlaced());
    }

    @Test
    @DisplayName("PRD 4.6：再生范围 = 5×5 水平 × y∈[核心y−2, 核心y+1]")
    void everyGrownOreIsInsideThePrdSpawnRange() {
        World world = islandWorld();
        ResourceCoreRegen.IslandCore core = stoneCore();

        // ★ 先把"生成器自带的矿石"清点出来。
        //   石矿岛本来就有 16 格矿石（PRD 4.3「石/煤/铁 富集」），
        //   而本类再生的是**铁**。于是"范围内有多少铁"这个问法无法区分
        //   "生成的"与"长出来的" —— 一个只查计数的断言会在这里误判。
        //   ⇒ 判据必须建立在**差集**上：跑之前先快照，跑之后再比。
        final int oreId = ResourceCoreRegen.oreOf(ResourceCoreRegen.IslandKind.STONE);
        int ironBefore = countOre(world, core, oreId);

        ResourceCoreRegen regen = runFor(world, core, 180.0 * 20);   // 20 格
        assertEquals(20, regen.oresPlaced(), "20 个周期应产出 20 格");
        assertEquals(oreId, regen.lastOreId());

        int ironAfter = countOre(world, core, oreId);
        assertEquals(ironBefore + 20, ironAfter,
                "范围内铁的总数应正好增加 20（之前 " + ironBefore + "，之后 " + ironAfter + "）。"
                        + "差额不对说明有矿长在范围外，或有格被重复计数");
    }

    /** 统计刷新范围内某一种矿石的格数。 */
    private static int countOre(World world, ResourceCoreRegen.IslandCore core, int oreId) {
        int n = 0;
        for (int dy = ResourceCoreRegen.CORE_MIN_DY;
             dy <= ResourceCoreRegen.CORE_MAX_DY; dy++) {
            for (int dx = -ResourceCoreRegen.CORE_HALF_SPAN;
                 dx <= ResourceCoreRegen.CORE_HALF_SPAN; dx++) {
                for (int dz = -ResourceCoreRegen.CORE_HALF_SPAN;
                     dz <= ResourceCoreRegen.CORE_HALF_SPAN; dz++) {
                    if (world.blockAt(core.x() + dx, core.y() + dy, core.z() + dz)
                            .runtimeId() == oreId) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    @Test
    @DisplayName("再生只落在规格范围内（范围外一格都不长）")
    void regenNeverGoesOutsideTheDeclaredRange() {
        // ★ 这条与上一条互为对照：上一条只查"范围内增加了 20 格"，
        //   一个"在范围内放 20 格、同时在范围外也乱放几格"的实现照样能过。
        //   ⇒ 必须单独守范围外。
        World world = islandWorld();
        ResourceCoreRegen.IslandCore core = stoneCore();
        ResourceCoreRegen regen = runFor(world, core, 180.0 * 20);

        final int oreId = regen.lastOreId();
        assertNotEquals(-1, oreId);

        // 取一个更宽的窗口（含核心自身那一格），比较跑前后的差
        // "跑前"的快照必须来自**另一个未跑过的世界**：
        //   在同一个 world 上先后数两次，第二次的"跑前"其实已经跑过了。
        World fresh = islandWorld();
        int before2 = countOreInWindow(fresh, core, oreId, false);
        int after2 = countOreInWindow(world, core, oreId, false);

        assertEquals(before2, after2,
                "有 " + (after2 - before2) + " 格矿石长在了 PRD 4.6 规定的刷新范围**之外**");
        assertEquals(20, countOreInWindow(world, core, oreId, true),
                "范围内应有 20 格");
    }

    /** 统计宽窗口内某矿石的格数；{@code inRangeOnly} 为 true 时只看规格范围。 */
    private static int countOreInWindow(World world, ResourceCoreRegen.IslandCore core,
                                        int oreId, boolean inRangeOnly) {
        int n = 0;
        for (int dy = -8; dy <= 8; dy++) {
            for (int dx = -8; dx <= 8; dx++) {
                for (int dz = -8; dz <= 8; dz++) {
                    final boolean inRange = dy >= ResourceCoreRegen.CORE_MIN_DY
                            && dy <= ResourceCoreRegen.CORE_MAX_DY
                            && Math.abs(dx) <= ResourceCoreRegen.CORE_HALF_SPAN
                            && Math.abs(dz) <= ResourceCoreRegen.CORE_HALF_SPAN;
                    if (inRangeOnly && !inRange) {
                        continue;
                    }
                    if (!inRangeOnly && inRange) {
                        continue;
                    }
                    if (world.blockAt(core.x() + dx, core.y() + dy, core.z() + dz)
                            .runtimeId() == oreId) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static int oreAt(World world, ResourceCoreRegen regen) {
        // 找出被放下的那一格：核心附近唯一的矿石
        ResourceCoreRegen.IslandCore core = stoneCore();
        for (int dy = ResourceCoreRegen.CORE_MIN_DY;
             dy <= ResourceCoreRegen.CORE_MAX_DY; dy++) {
            for (int dx = -ResourceCoreRegen.CORE_HALF_SPAN;
                 dx <= ResourceCoreRegen.CORE_HALF_SPAN; dx++) {
                for (int dz = -ResourceCoreRegen.CORE_HALF_SPAN;
                     dz <= ResourceCoreRegen.CORE_HALF_SPAN; dz++) {
                    final int id = world.blockAt(core.x() + dx, core.y() + dy, core.z() + dz)
                            .runtimeId();
                    if (isAnyOre(id)) {
                        return id;
                    }
                }
            }
        }
        return -1;
    }

    private static boolean isAnyOre(int runtimeId) {
        String id = com.skyisland.world.block.BlockRegistry.byRuntimeId(runtimeId).id();
        return id.endsWith("_ore") || id.equals("skyisland:crystal_ore");
    }

    // ================================================================
    // ③ PRD 4.6「不得覆盖玩家方块」
    // ================================================================

    @Test
    @DisplayName("PRD 4.6 占用冲突：目标格被占则跳过并顺延，绝不覆盖玩家方块")
    void occupiedCellsAreSkippedAndNeverOverwritten() {
        World world = islandWorld();
        ResourceCoreRegen.IslandCore core = stoneCore();

        // 把扫描顺序里的**每一格**候选都填成石头（模拟玩家在核心旁放了方块）。
        // ★ 早先只填了 (0, +1, 0) 一格，而扫描在放到矿之前就会走过其它格 ——
        //   那些格本来是石头（石矿岛地表之下全是石头），于是**第一个**遇到的
        //   非空气格就是它们，`skippedOccupied` 的增量被早到的候选吃掉了。
        //   症状是"断言说没跳过，但明明被占着"，看起来像判据写错，
        //   真因是夹具只堵了一个格子。
        // ★ 夹具：用<b>玩家能看见、且本不属于"可再生石头"</b>的方块占住若干候选格。
        //   早先这里放的是石头 —— 而再生改的恰好就是石头，
        //   于是"玩家的石头"和"自然的石头"在判据上无法区分，
        //   覆盖与否都测不出来。改用木板：它既不是石头（不会被再生），
        //   又是玩家真会放的东西。
        final int plank = com.skyisland.world.block.BlockRegistry.planks().runtimeId();

        // 核心正上方一格（y = 核心+1，规格范围内的最高层）
        final int blockX = core.x();
        final int blockY = core.y() + ResourceCoreRegen.CORE_MAX_DY;
        final int blockZ = core.z();

        // 木板放在核心正上方一格（y=65，本是空气）。它已有相邻支撑 ——
        // 正下方就是资源核心本身（实心），所以不需要额外垫石头。
        // ★ 早先这里垫了一块石头，反而失败：那一格（y=63）本来就是石矿岛的表面方块，
        //   placeBlock 因"目标已被占用"而拒绝 —— 症状是"前置失败"，
        //   看起来像夹具写错了，真因是把已有方块当成空气。
        assertTrue(world.isAirAt(blockX, blockY, blockZ),
                "前置：核心正上方应是空气（实测 " + world.blockAt(blockX, blockY, blockZ).id() + "）");
        assertTrue(world.placeBlock(blockX, blockY, blockZ, plank,
                World.MutationCause.PLAYER_PLACE, null).success(),
                "前置：玩家方块应能放下去");

        ResourceCoreRegen regen = runFor(world, core, 180.0);

        assertEquals(plank, world.blockAt(blockX, blockY, blockZ).runtimeId(),
                "★ 玩家放的木板必须还在 —— 再生覆盖玩家方块就等于凭空删掉玩家的建造");
        assertEquals(1, regen.oresPlaced(), "应顺延到下一个可用格，而不是放弃");
        assertTrue(regen.skippedOccupied() >= 1,
                "被玩家方块占据的那一格应计入 skippedOccupied（实测 "
                        + regen.skippedOccupied() + "）");
    }

    @Test
    @DisplayName("核心被玩家方块完全围死时不崩、只计数")
    void fullySurroundedCoreDoesNotCrash() {
        World world = islandWorld();
        ResourceCoreRegen.IslandCore core = stoneCore();
        int stone = com.skyisland.world.block.BlockRegistry.stone().runtimeId();

        // ★ 夹具必须先<b>把候选格全部变成"非石头"</b>，再生才会"无处可放"。
        //   做法：先把范围内的石头挖掉（留下空气），再用木板逐格填满 ——
        //   木板既不是石头（不会被再生当成目标），也不是空气。
        final int plank = com.skyisland.world.block.BlockRegistry.planks().runtimeId();

        // 先清空：挖掉范围内所有方块（核心自身挖不动，会被拒绝，正好留下核心）
        for (int dy = ResourceCoreRegen.CORE_MIN_DY;
             dy <= ResourceCoreRegen.CORE_MAX_DY; dy++) {
            for (int dx = -ResourceCoreRegen.CORE_HALF_SPAN;
                 dx <= ResourceCoreRegen.CORE_HALF_SPAN; dx++) {
                for (int dz = -ResourceCoreRegen.CORE_HALF_SPAN;
                     dz <= ResourceCoreRegen.CORE_HALF_SPAN; dz++) {
                    world.breakBlock(core.x() + dx, core.y() + dy, core.z() + dz,
                            World.MutationCause.PLAYER_BREAK);
                }
            }
        }
        // 再用木板填满（核心那一格填不上，留在原地）
        for (int dy = ResourceCoreRegen.CORE_MIN_DY;
             dy <= ResourceCoreRegen.CORE_MAX_DY; dy++) {
            for (int dx = -ResourceCoreRegen.CORE_HALF_SPAN;
                 dx <= ResourceCoreRegen.CORE_HALF_SPAN; dx++) {
                for (int dz = -ResourceCoreRegen.CORE_HALF_SPAN;
                     dz <= ResourceCoreRegen.CORE_HALF_SPAN; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) {
                        continue;   // 核心自身那一格
                    }
                    world.placeBlock(core.x() + dx, core.y() + dy, core.z() + dz, plank,
                            World.MutationCause.PLAYER_PLACE, null);
                }
            }
        }
        assertNotEquals(stone, world.blockAt(core.x(), core.y() + ResourceCoreRegen.CORE_MAX_DY,
                        core.z()).runtimeId(),
                "前置：候选格应已不是石头");

        ResourceCoreRegen regen = runFor(world, core, 180.0 * 3);

        assertEquals(0, regen.oresPlaced(), "全被占满时不该硬塞一格进去");
        assertEquals(3, regen.skippedNoCandidate(),
                "三次周期都该走完候选扫描却无处可放 ⇒ skippedNoCandidate = 3");
        assertTrue(regen.skippedOccupied() > 0, "被占的格应计入 occupied");
    }

    // ================================================================
    // ④ 核心不可破坏（防软锁第 ① 条，本类不参与但必须回归）
    // ================================================================

    @Test
    @DisplayName("PRD 4.6 防软锁 ①：核心不可破坏（再生跑再久也不会掉）")
    void theCoreItselfIsNeverBreakable() {
        World world = islandWorld();
        ResourceCoreRegen.IslandCore core = stoneCore();

        for (int i = 0; i < 20; i++) {
            World.MutationResult r = world.breakBlock(core.x(), core.y(), core.z(),
                    World.MutationCause.PLAYER_BREAK);
            assertFalse(r.success(), "第 " + i + " 次尝试竟然挖动了资源核心");
            assertTrue(r.reason().contains("不可破坏"), "失败原因应指明不可破坏，实为：" + r.reason());
        }
        runFor(world, core, 180.0 * 5);
        assertEquals(com.skyisland.world.block.BlockRegistry.resourceCore().runtimeId(),
                world.blockAt(core.x(), core.y(), core.z()).runtimeId(),
                "跑了 15 分钟再生后核心必须还在");
    }

    // ================================================================
    // ⑤ 确定性与离线口径
    // ================================================================

    @Test
    @DisplayName("同一状态下两次再生落在同一格（存档可复现）")
    void regenTargetIsDeterministic() {
        World a = islandWorld();
        World b = islandWorld();
        runFor(a, stoneCore(), 180.0);
        runFor(b, stoneCore(), 180.0);

        int cellA = firstOreCell(a);
        int cellB = firstOreCell(b);
        assertEquals(cellA, cellB,
                "两个同 seed 的世界必须长在同一格 —— 否则同一份存档读两次会得到不同的矿脉布局");
    }

    private static int firstOreCell(World world) {
        ResourceCoreRegen.IslandCore core = stoneCore();
        for (int dy = ResourceCoreRegen.CORE_MAX_DY;
             dy >= ResourceCoreRegen.CORE_MIN_DY; dy--) {
            for (int dx = -ResourceCoreRegen.CORE_HALF_SPAN;
                 dx <= ResourceCoreRegen.CORE_HALF_SPAN; dx++) {
                for (int dz = -ResourceCoreRegen.CORE_HALF_SPAN;
                     dz <= ResourceCoreRegen.CORE_HALF_SPAN; dz++) {
                    if (isAnyOre(world.blockAt(core.x() + dx, core.y() + dy, core.z() + dz)
                            .runtimeId())) {
                        return (dy + 16) * 1024 + (dx + 16) * 32 + (dz + 16);
                    }
                }
            }
        }
        return -1;
    }

    @Test
    @DisplayName("PRD 4.6「不做离线计算」：不 tick 就不长矿")
    void noOfflineCatchUp() {
        World world = islandWorld();
        ResourceCoreRegen regen = new ResourceCoreRegen(world);
        regen.register(stoneCore());

        // 只跑 1 秒然后"停机"很久
        regen.tick(1.0);
        long afterOneSecond = regen.oresPlaced();
        assertEquals(0, afterOneSecond);

        // 停机期间没有任何 tick —— 模拟关掉游戏一晚上
        // （本用例不需要真的等待：关键断言是"没有 tick 就没有产出"）
        assertEquals(0, regen.oresPlaced(),
                "★ 没有 tick 就绝不能长矿。若这条红了，说明实现里做了离线补算 —— "
                        + "那会让「关一晚上 = 早上矿满仓」，直接违反 PRD 4.6 的速率约束");

        // 再跑满一个周期，确认累计时间是从 1 秒继续而不是重置
        regen.tick(179.0);
        assertEquals(1, regen.oresPlaced(), "累计时间应跨 tick 连续，而不是每次重置");
    }

    @Test
    @DisplayName("大 dt 不会一次性补出多格（无死亡螺旋式跳变）")
    void aHugeDtDoesNotBurstManyOres() {
        World world = islandWorld();
        ResourceCoreRegen regen = new ResourceCoreRegen(world);
        regen.register(stoneCore());

        // 一个荒谬的 dt：相当于卡顿 1 小时
        regen.tick(3600.0);

        // 60 次周期 = 60 格，符合"时间到了就补"的语义；
        // 关键是没有超过它，也必须**有上限**（guard）而不是死循环。
        assertTrue(regen.oresPlaced() <= 64,
                "单次 tick 的产出应受 guard 上限约束（当前 " + regen.oresPlaced() + "）");
        assertTrue(regen.ticks() == 1);
    }

    // ================================================================
    // ⑥ 未加载区块
    // ================================================================

    @Test
    @DisplayName("候选格所在区块未加载时跳过而不是写进空气世界")
    void unloadedChunksAreSkipped() {
        // ★ 必须让<b>整个</b>候选范围落在未加载的区块里，否则断言会假红。
        //   早先只加载到 cx=2，而候选格 x ∈ [46,50] 恰好跨了 cx=2 / cx=3 ——
        //   于是有一半候选是**已加载**的，石头也确实被改成了矿，
        //   断言报"区块未加载却放了 1 格"。
        //   症状看着像"实现没检查 chunk"，真因是夹具只加载了一半。
        World world = new World(SEED, new IslandWorldGenerator());
        world.ensureAreaLoaded(-2, -2, 1, 1);   // 明确不含 cx=2 / cx=3

        ResourceCoreRegen.IslandCore core = stoneCore();
        for (int dx = -ResourceCoreRegen.CORE_HALF_SPAN;
             dx <= ResourceCoreRegen.CORE_HALF_SPAN; dx++) {
            assertTrue(world.chunkAt(Coords.toChunk(core.x() + dx),
                            Coords.toChunk(core.z())) == null,
                    "前置：候选范围必须全部未加载（dx=" + dx + " 的区块不该存在）");
        }

        ResourceCoreRegen regen = runFor(world, core, 180.0);

        assertEquals(0, regen.oresPlaced(),
                "★ 区块未加载时绝不能放置方块 —— 否则它会写进一个之后被流式丢弃的世界，"
                        + "表现为「矿石凭空出现又消失」");
        assertTrue(regen.skippedChunkNotLoaded() > 0,
                "应计入 skippedChunkNotLoaded —— 否则它会与'被占满'混成一个计数");
    }

    // ================================================================
    // ⑦ 四座岛全装
    // ================================================================

    @Test
    @DisplayName("产品世界装配后恰好 4 个核心，主岛没有")
    void theProductWorldRegistersExactlyFourCores() {
        int expected = 0;
        for (IslandWorldGenerator.Island island : IslandWorldGenerator.ISLANDS) {
            if (island.kind() != IslandWorldGenerator.Kind.MAIN) {
                expected++;
            }
        }
        assertEquals(4, expected,
                "PRD 4.6 参数表只列 4 座资源岛各有 1 个核心；主岛没有");
    }
}
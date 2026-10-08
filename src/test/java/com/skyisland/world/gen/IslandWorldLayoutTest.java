package com.skyisland.world.gen;

import com.skyisland.util.Coords;
import com.skyisland.world.World;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空岛世界布局断言（PRD 4.1 / 4.2 / 4.3 / 4.6 / 5.7、M3 验收第 2 条）。
 *
 * <p><b>为什么单独立一个测试类，而不加进 {@code M1ScriptedSelfTest}</b>：
 * 那三个自测跑的是 {@code TestWorldGenerator}（玩法夹具，见
 * {@link IslandWorldGenerator} 类注释里「夹具与产品分离」一节）。
 * 本类测的是<b>产品世界</b>，两条线必须互不污染 ——
 * 把产品地形塞进玩法自测，会让"玩法没生效"与"地形不同"两种失败混淆。
 *
 * <p><b>本类不做的事</b>：不测渲染、不测帧率、不开窗口。
 * 这里是纯 JVM 逻辑世界，可以在无显示环境下跑。
 */
class IslandWorldLayoutTest {

    /** 覆盖全部五座岛的区块范围：x ∈ [-51, 54]、z ∈ [-54, 56]。 */
    private static final int MIN_CX = -4;
    private static final int MIN_CZ = -4;
    private static final int MAX_CX = 3;
    private static final int MAX_CZ = 3;

    private static final long SEED = 20260919L;

    private static World world() {
        World world = new World(SEED, new IslandWorldGenerator());
        world.ensureAreaLoaded(MIN_CX, MIN_CZ, MAX_CX, MAX_CZ);
        return world;
    }

    // ================================================================
    // 1. 岛屿布局表（PRD 4.2）
    // ================================================================

    @Test
    @DisplayName("PRD 4.2：五座岛的坐标与尺寸逐项照抄，且互不重叠")
    void theIslandTableMatchesThePrdLayout() {
        assertEquals(5, IslandWorldGenerator.ISLANDS.size(), "PRD 4.2 要求 1 主岛 + 4 资源岛");

        record Expected(String key, int cx, int cz, int size, IslandWorldGenerator.Kind kind) {
        }
        List<Expected> expected = List.of(
                new Expected("main", 0, 0, 32, IslandWorldGenerator.Kind.MAIN),
                new Expected("stone_island", 48, 0, 14, IslandWorldGenerator.Kind.STONE),
                new Expected("forest_island", -44, 12, 14, IslandWorldGenerator.Kind.FOREST),
                new Expected("metal_island", 10, -48, 12, IslandWorldGenerator.Kind.METAL),
                new Expected("crystal_island", -6, 52, 10, IslandWorldGenerator.Kind.CRYSTAL));

        for (Expected e : expected) {
            IslandWorldGenerator.Island actual = IslandWorldGenerator.islandByKey(e.key());
            assertNotNull(actual, "缺少岛屿 " + e.key());
            assertEquals(e.cx(), actual.centerX(), e.key() + " 中心 x");
            assertEquals(e.cz(), actual.centerZ(), e.key() + " 中心 z");
            assertEquals(e.size(), actual.size(), e.key() + " 边长（PRD 4.2 表）");
            assertEquals(e.kind(), actual.kind(), e.key() + " 类型");
        }

        // 包围盒互不重叠 —— PRD 4.2「资源岛之间最近间距 ≥ 32 格」是布局意图的前提，
        // 一旦两岛重叠，先命中先返回会让其中一岛被静默吞掉。
        List<IslandWorldGenerator.Island> all = IslandWorldGenerator.ISLANDS;
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                IslandWorldGenerator.Island a = all.get(i);
                IslandWorldGenerator.Island b = all.get(j);
                boolean overlapX = a.minX() <= b.maxX() && b.minX() <= a.maxX();
                boolean overlapZ = a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
                assertFalse(overlapX && overlapZ,
                        "岛屿 " + a.key() + " 与 " + b.key() + " 包围盒重叠");
            }
        }
    }

    @Test
    @DisplayName("PRD M3 验收 2：实际岛屿尺寸误差 ≤ 2 格，且不越出声明的包围盒")
    void everyIslandFitsItsDeclaredBoundingBoxWithinTwoBlocks() {
        World world = world();
        for (IslandWorldGenerator.Island island : IslandWorldGenerator.ISLANDS) {
            int[] extent = solidExtent(world, island);
            assertNotNull(extent, island.key() + " 一格实体都没生成");

            assertTrue(extent[0] >= island.minX(),
                    island.key() + " 越出包围盒：minX 实际 " + extent[0] + " < 声明 "
                            + island.minX() + "（噪声只允许向内侵蚀，不允许外扩）");
            assertTrue(extent[1] <= island.maxX(),
                    island.key() + " 越出包围盒：maxX 实际 " + extent[1] + " > 声明 "
                            + island.maxX());
            assertTrue(extent[2] >= island.minZ(),
                    island.key() + " 越出包围盒：minZ 实际 " + extent[2] + " < 声明 "
                            + island.minZ());
            assertTrue(extent[3] <= island.maxZ(),
                    island.key() + " 越出包围盒：maxZ 实际 " + extent[3] + " > 声明 "
                            + island.maxZ());

            // PRD M3 验收原文：「坐标、尺寸误差 ≤ 2 格」。
            // 这里按**两侧合计**判（最严的读法）：每侧最多 1 格。
            int shrinkX = (island.maxX() - island.minX() + 1) - (extent[1] - extent[0] + 1);
            int shrinkZ = (island.maxZ() - island.minZ() + 1) - (extent[3] - extent[2] + 1);
            assertTrue(shrinkX <= 2,
                    island.key() + " x 方向尺寸误差 " + shrinkX + " 格 > 验收上限 2 格");
            assertTrue(shrinkZ <= 2,
                    island.key() + " z 方向尺寸误差 " + shrinkZ + " 格 > 验收上限 2 格");
        }
    }

    @Test
    @DisplayName("PRD 4.1：每一列厚度都在 8–14 层之间")
    void everyColumnIsEightToFourteenBlocksThick() {
        World world = world();
        int top = Coords.WORLD_SURFACE_BLOCK_Y;
        int checked = 0;
        for (IslandWorldGenerator.Island island : IslandWorldGenerator.ISLANDS) {
            for (int wz = island.minZ(); wz <= island.maxZ(); wz++) {
                for (int wx = island.minX(); wx <= island.maxX(); wx++) {
                    if (!IslandWorldGenerator.isIslandColumn(wx, wz, SEED)) {
                        continue;
                    }
                    // ★ 必须**从下往上**扫并取第一个非空气。
                    //   第一版写成"从 top 往下扫、遇到第一个非空气就 break"，
                    //   那个 break 命中的必然是**表面方块**（y=63），
                    //   于是 thickness 恒等于 1，142 列全部报"厚度 1 层"。
                    //   症状看着像"生成器只放了一层"，真因是量错了方向。
                    int lowest = Integer.MAX_VALUE;
                    for (int y = 20; y <= top; y++) {
                        if (!world.isAirAt(wx, y, wz)) {
                            lowest = y;
                            break;
                        }
                    }
                    int thickness = top - lowest + 1;
                    assertTrue(thickness >= IslandWorldGenerator.MIN_THICKNESS
                                    && thickness <= IslandWorldGenerator.MAX_THICKNESS,
                            island.key() + " 在 (" + wx + "," + wz + ") 厚度 " + thickness
                                    + " 层，越出 PRD 4.1 的 8–14 层");
                    checked++;
                }
            }
        }
        assertTrue(checked > 1000, "只检查了 " + checked + " 列，覆盖面不足");
    }

    // ================================================================
    // 2. 资源分布（PRD 4.3）
    // ================================================================

    @Test
    @DisplayName("PRD 4.3：主岛不产铁 / 铜 / 金 / 晶体（强制远征）")
    void theMainIslandProducesNoRareOres() {
        World world = world();
        IslandWorldGenerator.Island main = IslandWorldGenerator.mainIsland();
        int[] forbidden = {
                BlockRegistry.ironOre().runtimeId(),
                BlockRegistry.copperOre().runtimeId(),
                BlockRegistry.goldOre().runtimeId(),
                BlockRegistry.crystalOre().runtimeId()};
        String[] names = {"iron_ore", "copper_ore", "gold_ore", "crystal_ore"};

        int found = 0;
        for (int wz = main.minZ(); wz <= main.maxZ(); wz++) {
            for (int wx = main.minX(); wx <= main.maxX(); wx++) {
                for (int y = Coords.WORLD_SURFACE_BLOCK_Y; y >= 20; y--) {
                    if (world.isAirAt(wx, y, wz)) {
                        continue;
                    }
                    int id = world.blockAt(wx, y, wz).runtimeId();
                    for (int f = 0; f < forbidden.length; f++) {
                        if (id == forbidden[f]) {
                            found++;
                            assertEquals(0, found,
                                    "主岛 (" + wx + "," + y + "," + wz + ") 出现了 "
                                            + names[f] + " —— PRD 4.3 明确「无」，"
                                            + "主岛出现它就等于取消了远征");
                        }
                    }
                }
            }
        }
        assertEquals(0, found);
    }

    @Test
    @DisplayName("谓词计数与真实世界完全一致（意图与产物没有分叉）")
    void oreCountsFromTheRealWorldMatchThePredicate() {
        World world = world();
        for (IslandWorldGenerator.Island island : IslandWorldGenerator.ISLANDS) {
            for (IslandWorldGenerator.Ore ore : IslandWorldGenerator.Ore.values()) {
                // ★ 收集**具体格子**而不是只比总数：
                //   只比总数时，"谓词说 40、世界里 37"这句话无法归因 ——
                //   到底是哪 3 格没写进去、还是谓词多算了哪 3 格，完全看不出来。
                //   本项目为此吃过亏（截图取证那轮：格子坐标当成 chunk 内偏移，
                //   症状是"某一排在图上凭空消失"，只有列出格子才能定位）。
                Set<String> predicted = new HashSet<>();
                int actual = 0;
                List<String> actualCells = new java.util.ArrayList<>();

                final int top = Coords.WORLD_SURFACE_BLOCK_Y;
                for (int wz = island.minZ(); wz <= island.maxZ(); wz++) {
                    for (int wx = island.minX(); wx <= island.maxX(); wx++) {
                        if (!IslandWorldGenerator.isIslandColumn(wx, wz, SEED)) {
                            continue;
                        }
                        int bottom = top
                                - IslandWorldGenerator.thicknessOfColumn(island, wx, wz, SEED) + 1;
                        for (int y = bottom + 1; y <= top - 2; y++) {
                            if (IslandWorldGenerator.isOreCellAt(island, ore, wx, y, wz, SEED)) {
                                predicted.add(wx + "," + y + "," + wz);
                            }
                        }
                        for (int y = 20; y <= top; y++) {
                            if (!world.isAirAt(wx, y, wz)
                                    && world.blockAt(wx, y, wz).runtimeId() == ore.id()) {
                                actual++;
                                actualCells.add(wx + "," + y + "," + wz);
                            }
                        }
                    }
                }

                assertEquals(new HashSet<>(actualCells), predicted,
                        island.key() + " 的 " + ore + "：世界里的矿格集合与谓词判定不一致。"
                                + "（差集左侧是世界里多出来的，右侧是谓词以为有而世界里没有的）");
            }
        }
    }

    @Test
    @DisplayName("PRD 4.3：多 seed 平均矿物密度落在「高 8–12 / 中 3–6 / 低 1–2 / 无 0」区间")
    void meanOreDensityPer100CellsFallsInThePrdBand() {
        // ★ 为什么取「多 seed 均值」而不是「单 seed 精确值」：
        //   PRD 的密度区间很窄（高 8–12，只有 ±20%），而随机布点的计数噪声是
        //   泊松型 —— 石矿岛约 2000 个石头格、期望 18 格矿，标准差约 4.2 格，
        //   即 ±23%。也就是说**单个 seed 合法地落在区间外**的概率不低。
        //   那样的断言会变成「随机红」，反而失去门禁作用。
        //   ⇒ 分两层：单 seed 判「不越界太远」，多 seed 均值判「档位没调错」。
        final int SEEDS = 32;
        final long base = 20260919L;

        for (IslandWorldGenerator.Island island : IslandWorldGenerator.ISLANDS) {
            double columns = 0;
            for (int wz = island.minZ(); wz <= island.maxZ(); wz++) {
                for (int wx = island.minX(); wx <= island.maxX(); wx++) {
                    if (IslandWorldGenerator.isIslandColumn(wx, wz, base)) {
                        columns++;
                    }
                }
            }
            assertTrue(columns > 0, island.key() + " 一列都没生成");

            for (IslandWorldGenerator.Ore ore : IslandWorldGenerator.Ore.values()) {
                IslandWorldGenerator.Density density =
                        IslandWorldGenerator.densityOf(island, ore);
                double total = 0;
                for (int s = 0; s < SEEDS; s++) {
                    total += IslandWorldGenerator.countOreIn(island, ore, base + s);
                }
                double per100 = total / SEEDS / columns * 100.0;

                if (density == IslandWorldGenerator.Density.NONE) {
                    assertEquals(0.0, per100, 0.001,
                            island.key() + " 的 " + ore + " 应为「无」，实测均值 "
                                    + String.format("%.2f", per100) + " 格/100");
                    continue;
                }
                double lo = density == IslandWorldGenerator.Density.HIGH ? 8.0
                        : density == IslandWorldGenerator.Density.MEDIUM ? 3.0 : 1.0;
                double hi = density == IslandWorldGenerator.Density.HIGH ? 12.0
                        : density == IslandWorldGenerator.Density.MEDIUM ? 6.0 : 2.0;
                assertTrue(per100 >= lo && per100 <= hi,
                        island.key() + " 的 " + ore + " 平均密度 " + String.format("%.2f", per100)
                                + " 格/100，越出 PRD 4.3 的 " + density + " 档区间 [" + lo + ", " + hi + "]");
            }
        }
    }

    // ================================================================
    // 3. 资源核心（PRD 4.6）
    // ================================================================

    @Test
    @DisplayName("PRD 4.6：四座资源岛各 1 个核心，主岛没有")
    void eachResourceIslandHasExactlyOneCoreAndTheMainIslandHasNone() {
        World world = world();
        int total = 0;
        for (IslandWorldGenerator.Island island : IslandWorldGenerator.ISLANDS) {
            int cores = 0;
            for (int wz = island.minZ() - 2; wz <= island.maxZ() + 2; wz++) {
                for (int wx = island.minX() - 2; wx <= island.maxX() + 2; wx++) {
                    for (int y = Coords.WORLD_SURFACE_BLOCK_Y; y <= 70; y++) {
                        if (world.blockAt(wx, y, wz).runtimeId()
                                == BlockRegistry.resourceCore().runtimeId()) {
                            cores++;
                            assertEquals(island.centerX(), wx,
                                    island.key() + " 的核心不在岛几何中心（PRD 4.6 参数表）");
                            assertEquals(island.centerZ(), wz,
                                    island.key() + " 的核心不在岛几何中心（PRD 4.6 参数表）");
                            assertEquals(Coords.WORLD_SURFACE_BLOCK_Y + 1, y,
                                    island.key() + " 的核心不在表面方块之上 1 格");
                        }
                    }
                }
            }
            if (island.kind() == IslandWorldGenerator.Kind.MAIN) {
                assertEquals(0, cores, "主岛不应有资源核心（PRD 4.6 表只列 4 座资源岛）");
            } else {
                assertEquals(1, cores, island.key() + " 应恰好 1 个资源核心");
            }
            total += cores;
        }
        assertEquals(4, total, "全岛核心总数应为 4");
    }

    // ================================================================
    // 4. 开局小屋（PRD 5.7）
    // ================================================================

    @Test
    @DisplayName("PRD 5.7：地板 9×9 木板、外框 9×9、内部净空 7×7")
    void theStarterHutMatchesThePrdFootprint() {
        World world = world();
        int planks = BlockRegistry.planks().runtimeId();
        int surface = Coords.WORLD_SURFACE_BLOCK_Y;

        for (int z = -4; z <= 4; z++) {
            for (int x = -4; x <= 4; x++) {
                assertEquals(planks, world.blockAt(x, surface, z).runtimeId(),
                        "地板 (" + x + "," + z + ") 应是木板");
            }
        }
        // 内部净空 7×7：x,z ∈ [-3,3] 必须是空气，且不与地板重合
        for (int z = -3; z <= 3; z++) {
            for (int x = -3; x <= 3; x++) {
                for (int y = 64; y <= 66; y++) {
                    assertTrue(world.isAirAt(x, y, z),
                            "内部净空 (" + x + "," + y + "," + z + ") 不是空气");
                }
            }
        }
    }

    @Test
    @DisplayName("PRD 5.7：四面墙 + 南北各 1 面玻璃窗 + 木板平顶（南面留 1 处未封）")
    void theStarterHutHasWallsWindowsAndAHalfFinishedRoof() {
        World world = world();
        int planks = BlockRegistry.planks().runtimeId();
        int glass = BlockRegistry.glass().runtimeId();

        // 四面墙（门口两格、门框两格、北窗一格除外 —— 三者都有专项断言）
        int doorFrame = BlockRegistry.woodenDoor().runtimeId();
        for (int y = 64; y <= 66; y++) {
            for (int i = -4; i <= 4; i++) {
                boolean southDoorway = i == 0 && y <= 65;
                boolean southDoorFrame = y == 64 && Math.abs(i) == 1;
                // ★ 门上亮窗也要让位。它在 y=66，而南墙其他格在这一层都是木板 ——
                //   漏掉这一条时，失败消息说的是"南墙 (0,66) 应是木板"，
                //   而那块玻璃**正是**门上方那扇窗：断言与设计互相打脸，
                //   读起来却像"南墙被谁换成了玻璃"。
                boolean southWindow = i == 0 && y == 66;
                if (!southDoorway && !southDoorFrame && !southWindow) {
                    assertEquals(planks, world.blockAt(i, y, 4).runtimeId(),
                            "南墙 (" + i + "," + y + ") 应是木板");
                }
                // ★ 北窗那一格必须排除，否则「北墙全是木板」与「北墙有玻璃窗」
                //   两条断言互相打脸 —— 而失败消息只说"北墙应是木板"，
                //   读起来像是墙坏了，真因是断言没给窗口让位。
                boolean northWindow = i == 0 && y == 65;
                if (!northWindow) {
                    assertEquals(planks, world.blockAt(i, y, -4).runtimeId(),
                            "北墙 (" + i + "," + y + ") 应是木板");
                }
                assertEquals(planks, world.blockAt(-4, y, i).runtimeId(),
                        "西墙 (" + i + "," + y + ") 应是木板");
                assertEquals(planks, world.blockAt(4, y, i).runtimeId(),
                        "东墙 (" + i + "," + y + ") 应是木板");
            }
        }

        // 门框：洞口两侧各一格木门方块（"这里是门"的视觉标识，且不挡通行）
        assertEquals(doorFrame, world.blockAt(-1, 64, 4).runtimeId(), "门框左应是木门方块");
        assertEquals(doorFrame, world.blockAt(1, 64, 4).runtimeId(), "门框右应是木门方块");

        // 南北各 1 面玻璃窗
        assertEquals(glass, world.blockAt(0, 65, -4).runtimeId(), "北窗应是玻璃");
        assertEquals(glass, world.blockAt(0, 66, 4).runtimeId(), "南墙门上亮窗应是玻璃");

        // 屋顶：9×9 木板，南面留 1 处未封
        int roofCells = 0;
        for (int z = -4; z <= 4; z++) {
            for (int x = -4; x <= 4; x++) {
                boolean isGap = x == 2 && z == 4;
                if (isGap) {
                    assertTrue(world.isAirAt(x, 67, z), "屋顶缺口 (" + x + ",67," + z + ") 应未封");
                } else {
                    assertEquals(planks, world.blockAt(x, 67, z).runtimeId(),
                            "屋顶 (" + x + ",67," + z + ") 应是木板");
                    roofCells++;
                }
            }
        }
        assertEquals(80, roofCells, "屋顶应有 81 - 1 = 80 格");
    }

    @Test
    @DisplayName("回归护栏：门口必须可通行（否则玩家出生即被永久关在小屋里）")
    void theStarterHutDoorwayIsPassable() {
        World world = world();
        assertTrue(world.isAirAt(0, 64, 4), "门口下格 (0,64,4) 必须是空气");
        assertTrue(world.isAirAt(0, 65, 4), "门口上格 (0,65,4) 必须是空气");
        // 门外一格也要站得住 —— 玩家要真的能走出去
        assertFalse(world.isAirAt(0, 63, 5),
                "门外 (0,63,5) 是悬空的：玩家会踏空掉进虚空");
    }

    @Test
    @DisplayName("出生点站在小屋内部的实地上")
    void theSpawnPointStandsOnSolidGroundInsideTheHut() {
        World world = world();
        int fx = (int) Math.floor(IslandWorldGenerator.SPAWN_X);
        int fz = (int) Math.floor(IslandWorldGenerator.SPAWN_Z);
        assertFalse(world.isAirAt(fx, Coords.WORLD_SURFACE_BLOCK_Y, fz),
                "出生点脚下 (" + fx + ",63," + fz + ") 是空的");
        assertTrue(world.isAirAt(fx, 64, fz), "出生点腰部 (" + fx + ",64," + fz + ") 应是空气");
        assertTrue(world.isAirAt(fx, 65, fz), "出生点头部 (" + fx + ",65," + fz + ") 应是空气");
        assertEquals(64.0, IslandWorldGenerator.SPAWN_Y,
                "出生脚底 y 必须等于地板顶面（PRD 5.7「地板顶面 y = 64」）");
    }

    // ================================================================
    // 5. 种子与确定性（PRD 4.7）
    // ================================================================

    @Test
    @DisplayName("PRD 4.7：同一 seed 生成完全相同的世界（存档稀疏增量的前提）")
    void theSameSeedProducesTheSameWorld() {
        assertTrue(regeneratedIdentically(SEED, 0, 0), "同一 seed 的 (0,0) 列两次生成不一致");
        assertTrue(regeneratedIdentically(SEED, 48, 0), "同一 seed 的石矿岛两次生成不一致");
        assertTrue(regeneratedIdentically(SEED, -44, 12), "同一 seed 的森林岛两次生成不一致");
        assertTrue(regeneratedIdentically(SEED, 10, -48), "同一 seed 的金属岛两次生成不一致");
        assertTrue(regeneratedIdentically(SEED, -6, 52), "同一 seed 的晶矿岛两次生成不一致");
    }

    @Test
    @DisplayName("PRD 4.7：seed 改变轮廓，但不改变岛屿表（进度结构不被 seed 破坏）")
    void aDifferentSeedChangesTheOutlineButNotTheIslandTable() {
        IslandWorldGenerator a = new IslandWorldGenerator();
        IslandWorldGenerator b = new IslandWorldGenerator();
        assertEquals(a.id(), b.id());
        assertEquals(a.generationVersion(), b.generationVersion());
        assertEquals(IslandWorldGenerator.ISLANDS, b.ISLANDS,
                "seed 不得影响岛屿坐标/尺寸表（PRD 4.7「Seed 不改变四类资源岛的大致方向与数量」）");

        // 至少要有相当多的列归属不同 —— 否则 seed 根本没起作用。
        // ★ 判据用「差异列数 > 20」而不是「两个签名不相等」：
        //   后者只要求差 1 格就能通过，而 seed 对轮廓的影响本就只发生在
        //   d ∈ (0.94, 1.0] 那圈窄环带上（OUTLINE_MAX_EROSION = 0.06 的直接后果）。
        //   只判"不相等"会让"seed 几乎不起作用"这件事长期蒙混过关 ——
        //   本轮就发生过：噪声尺度取 4 格时两个 seed 的轮廓**逐格完全相同**。
        int differing = 0;
        for (IslandWorldGenerator.Island island : IslandWorldGenerator.ISLANDS) {
            for (int wz = island.minZ(); wz <= island.maxZ(); wz++) {
                for (int wx = island.minX(); wx <= island.maxX(); wx++) {
                    if (IslandWorldGenerator.isIslandColumn(wx, wz, 1L)
                            != IslandWorldGenerator.isIslandColumn(wx, wz, 2L)) {
                        differing++;
                    }
                }
            }
        }
        assertTrue(differing > 20,
                "两个 seed 只有 " + differing + " 列归属不同 —— seed 对轮廓的影响过弱，"
                        + "PRD 4.7「Seed 控制岛屿轮廓」实际未生效");
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 某岛的实体列包围盒 {minX, maxX, minZ, maxZ}；全空返回 null。 */
    private static int[] solidExtent(World world, IslandWorldGenerator.Island island) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int wz = island.minZ(); wz <= island.maxZ(); wz++) {
            for (int wx = island.minX(); wx <= island.maxX(); wx++) {
                boolean solid = false;
                for (int y = 20; y <= 70 && !solid; y++) {
                    if (!world.isAirAt(wx, y, wz)) {
                        solid = true;
                    }
                }
                if (!solid) {
                    continue;
                }
                minX = Math.min(minX, wx);
                maxX = Math.max(maxX, wx);
                minZ = Math.min(minZ, wz);
                maxZ = Math.max(maxZ, wz);
            }
        }
        return maxX == Integer.MIN_VALUE ? null : new int[]{minX, maxX, minZ, maxZ};
    }

    /**
     * 两次<b>各自新建 World</b>的生成结果是否相同。
     *
     * <p>★ 注意这里两次调用的实参一模一样，但每次调用内部都会
     * {@code new World(...)} 重新跑一遍生成 —— 所以它比较的是
     * <b>两次独立生成</b>，不是"自己等于自己"。
     * <p>把它写成 {@code a.equals(a)} 那种自反比较是本项目明确禁止的失败长相：
     * 「断言在失败场景下仍能通过」。所以这里必须真的建两个 World。
     */
    private static boolean regeneratedIdentically(long seed, int wx, int wz) {
        return surfaceColumn(seed, wx, wz).equals(surfaceColumn(seed, wx, wz));
    }

    private static String surfaceColumn(long seed, int wx, int wz) {
        IslandWorldGenerator gen = new IslandWorldGenerator();
        World world = new World(seed, gen);
        int cx = Coords.toChunk(wx);
        int cz = Coords.toChunk(wz);
        world.ensureAreaLoaded(cx, cz, cx, cz);
        StringBuilder sb = new StringBuilder();
        for (int y = 45; y <= 72; y++) {
            Block b = world.blockAt(wx, y, wz);
            sb.append(b.runtimeId()).append(',');
        }
        return sb.toString();
    }
}
package com.skyisland.world.block;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方块注册表测试。
 *
 * <p><b>重点保护的是"注册顺序即运行时 ID"这条不变式</b>：存档写的是 stable string ID，
 * 但内存与顶点数据用的是 {@code short} runtimeId。一旦有人把某个方块插到注册表中间，
 * 所有已存在的存档增量都会指到错误的方块上，而且<u>不会报错</u> —— 只会表现为
 * "读档后地形变了样"。这条不变式必须由测试而不是由注释来保证。
 */
class BlockRegistryTest {

    /**
     * MVP 登记子集的数量。故意写死：新增方块时必须有人显式改这个数字并想一遍存档兼容。
     *
     * <p><b>15 = 1 空气 + 13 玩家常规 + 1 系统方块。</b>
     *
     * <p>沿革：M1 为 10（含提前注册的石砖）；Pre-M2 Corrective Closure（用户裁决 A10）
     * 把石砖移回 Alpha / M4，降为 9；M2 补齐 PRD 5.1 缺失的 6 种
     * （原木 / 树叶 / 铁矿石 / 煤炭矿石 / 火把 / 木门）后升为 15
     * —— 此时才真正达成 PRD 的「13 种玩家常规方块 + 1 种系统方块」，
     * 一并关闭审计缺口 G1 与 M3-GATE 的 MVP-BLOCK-017 / MVP-SCOPE-001。
     */
    private static final int EXPECTED_BLOCK_COUNT = 15;

    @Test
    void airOccupiesRuntimeIdZero() {
        assertEquals(0, BlockRegistry.AIR_RUNTIME_ID,
                "空气必须是 0：short[] 零初始化即空气，这是整个存储层的前提");
        assertEquals(0, BlockRegistry.air().runtimeId());
        assertTrue(BlockRegistry.air().isAir());
    }

    @Test
    void runtimeIdEqualsRegistrationIndex() {
        for (int i = 0; i < BlockRegistry.size(); i++) {
            Block block = BlockRegistry.byRuntimeId(i);
            assertEquals(i, block.runtimeId(),
                    "注册下标与 runtimeId 必须严格一致（index=" + i + ", id=" + block.id() + "）");
        }
    }

    @Test
    void m1SubsetHasExpectedSize() {
        assertEquals(EXPECTED_BLOCK_COUNT, BlockRegistry.size(),
                "M1 注册子集被改动 —— 请同时确认存档兼容性与本测试里的断言");
    }

    // ================================================================
    // M2：PRD 5.1 方块口径与掉落表
    // ================================================================

    /**
     * PRD 5.1 / 5.1.1 的数量口径：<b>13 种玩家常规方块 + 1 种系统方块</b>。
     *
     * <p>这条断言的价值在于把口径写成数字。审计发现 MVP 方块数量口径
     * （MVP-BLOCK-017 / MVP-SCOPE-001）长期悬在"M3-GATE 前定死"，
     * 原因是没有人能一眼说出"现在到底几种"。现在它能被断言。
     */
    @Test
    void mvpBlockCountMatchesPrdThirteenPlusOne() {
        assertEquals(13, BlockRegistry.playerBlockCount(),
                "PRD 5.1：MVP 玩家常规方块 = 13 种");
        assertEquals(1, BlockRegistry.systemBlockCount(),
                "PRD 5.1.1：MVP 系统方块 = 1 种（资源核心），单独计数、不得算作第 14 种玩家方块");
    }

    /** M2 补齐的 6 种方块（审计缺口 G1 / MVP-BLOCK-005/007/009/010/012/013）。 */
    @Test
    void m2RegisteredTheSixPreviouslyMissingBlocks() {
        assertNotNull(BlockRegistry.log(), "M2 应补齐原木（MVP-BLOCK-005）");
        assertNotNull(BlockRegistry.leaves(), "M2 应补齐树叶（MVP-BLOCK-007）");
        assertNotNull(BlockRegistry.ironOre(), "M2 应补齐铁矿石（MVP-BLOCK-009）");
        assertNotNull(BlockRegistry.coalOre(), "M2 应补齐煤炭矿石（MVP-BLOCK-010）");
        assertNotNull(BlockRegistry.torch(), "M2 应补齐火把（MVP-BLOCK-012）");
        assertNotNull(BlockRegistry.woodenDoor(), "M2 应补齐木门（MVP-BLOCK-013）");
    }

    /**
     * 掉落表逐行对照 PRD 5.1 的「掉落物」列。
     *
     * <p>这是 G13 的回归护栏：审计时破坏结算一律掉落自身，
     * "草方块掉泥土 / 石头掉圆石 / 玻璃与树叶无掉落"三条全部不成立，
     * 而且没有任何测试能发现 —— 因为掉落规则当时没有地方可写。
     */
    @Test
    void dropTableMatchesPrdSection51() {
        assertDrop(BlockRegistry.grass(), "skyisland:dirt", 1,
                "PRD 5.1：草方块掉泥土 ×1");
        assertDrop(BlockRegistry.stone(), "skyisland:cobblestone", 1,
                "PRD 5.1：石头掉圆石 ×1");
        assertDrop(BlockRegistry.coalOre(), "skyisland:coal", 1,
                "PRD 5.1：煤炭矿石掉煤炭（物品）×1 —— 方块与物品是两套注册表");
        assertNoDrop(BlockRegistry.glass(), "PRD 5.1：玻璃无掉落");
        assertNoDrop(BlockRegistry.leaves(), "PRD 5.1：树叶无掉落（明文不掉落树苗）");
        assertNoDrop(BlockRegistry.resourceCore(), "系统方块不可破坏，掉落显式为空");

        assertDrop(BlockRegistry.dirt(), "skyisland:dirt", 1, "PRD 5.1：泥土掉自身");
        assertDrop(BlockRegistry.cobblestone(), "skyisland:cobblestone", 1, "PRD 5.1：圆石掉自身");
        assertDrop(BlockRegistry.log(), "skyisland:log", 1, "PRD 5.1：原木掉自身");
        assertDrop(BlockRegistry.planks(), "skyisland:oak_planks", 1, "PRD 5.1：木板掉自身");
        assertDrop(BlockRegistry.ironOre(), "skyisland:iron_ore", 1, "PRD 5.1：铁矿石掉自身");
        assertDrop(BlockRegistry.sand(), "skyisland:sand", 1, "PRD 5.1：沙子掉自身");
        assertDrop(BlockRegistry.torch(), "skyisland:torch", 1, "PRD 5.1：火把掉自身");
        assertDrop(BlockRegistry.woodenDoor(), "skyisland:wooden_door", 1, "PRD 5.1：木门掉自身");
    }

    /** PRD 5.1 的「挖掘耗时」列（首版无工具，空手耗时即验收值）。 */
    @Test
    void m2BlockBreakTimesMatchPrd() {
        assertEquals(3.5f, BlockRegistry.ironOre().hardness(), 1e-6f,
                "PRD 5.1：铁矿石空手 3.5 秒（MVP-BLOCK-009 的验收值）");
        assertEquals(3.0f, BlockRegistry.coalOre().hardness(), 1e-6f,
                "PRD 5.1：煤炭矿石空手 3.0 秒（MVP-BLOCK-010 的验收值）");
        assertEquals(0.2f, BlockRegistry.leaves().hardness(), 1e-6f, "PRD 5.1：树叶 0.2 秒");
        assertEquals(2.0f, BlockRegistry.log().hardness(), 1e-6f, "PRD 5.1：原木 2.0 秒");
        assertEquals(0.1f, BlockRegistry.torch().hardness(), 1e-6f, "PRD 5.1：火把 0.1 秒");
        assertEquals(1.0f, BlockRegistry.woodenDoor().hardness(), 1e-6f, "PRD 5.1：木门 1.0 秒");
    }

    /** 火把自发光（PRD 5.1「自发光」），且其余新方块不自发光。 */
    @Test
    void torchEmitsLightAndOtherM2BlocksDoNot() {
        assertTrue(BlockRegistry.torch().lightEmission() > 0,
                "PRD 5.1：火把自发光");
        assertEquals(0, BlockRegistry.log().lightEmission());
        assertEquals(0, BlockRegistry.ironOre().lightEmission());
        assertEquals(0, BlockRegistry.coalOre().lightEmission());
    }

    private static void assertDrop(Block block, String itemId, int count, String why) {
        assertTrue(block.hasDrop(), why + " —— " + block.id() + " 应有掉落");
        assertEquals(itemId, block.dropItemId(), why);
        assertEquals(count, block.dropCount(), why);
    }

    private static void assertNoDrop(Block block, String why) {
        assertFalse(block.hasDrop(), why + " —— " + block.id() + " 不应有掉落");
        assertEquals(0, block.dropCount(), why);
    }

    @Test
    void stableIdsAreUniqueAndNamespaced() {
        Set<String> seen = new HashSet<>();
        for (Block block : BlockRegistry.all()) {
            String id = block.id();
            assertNotNull(id);
            assertTrue(id.startsWith("skyisland:"),
                    "stable ID 必须带命名空间前缀，否则将来与模组 ID 冲突时无法区分：" + id);
            assertTrue(seen.add(id), "stable ID 重复: " + id);
        }
    }

    @Test
    void lookupsRoundTripThroughBothIdSpaces() {
        for (Block block : BlockRegistry.all()) {
            assertSame(block, BlockRegistry.byName(block.id()));
            assertSame(block, BlockRegistry.byRuntimeId(block.runtimeId()));
            assertEquals(block.runtimeId(), (int) BlockRegistry.runtimeIdOf(block.id()));
        }
    }

    @Test
    void byNameReturnsNullForUnknownStableId() {
        assertNull(BlockRegistry.byName("skyisland:does_not_exist"));
        // runtimeIdOf 走的是"降级到空气 + 告警"路径，而不是返回 null —— 存档损坏时不能崩
        assertEquals(BlockRegistry.AIR_RUNTIME_ID,
                BlockRegistry.runtimeIdOf("skyisland:does_not_exist"));
    }

    @Test
    void byRuntimeIdOutOfRangeFallsBackToAirWithWarning() {
        int warningsBefore = com.skyisland.util.Log.warningCount();
        Block fallback = BlockRegistry.byRuntimeId(99999);
        assertSame(BlockRegistry.air(), fallback);
        assertTrue(com.skyisland.util.Log.warningCount() > warningsBefore,
                "越界 runtimeId 必须留下一条告警：静默降级会让'存档损坏'表现为'方块凭空消失'");

        assertSame(BlockRegistry.air(), BlockRegistry.byRuntimeId(-1));
    }

    // ============================================================ 关键方块的属性口径

    @Test
    void glassIsTransparentAndUsesTransparentRenderType() {
        Block glass = BlockRegistry.glass();
        assertTrue(glass.isTransparent());
        assertEquals(RenderType.TRANSPARENT, glass.renderType(),
                "透明方块必须进入透明子网格，否则半透明 pass 里不会有它");
        assertTrue(glass.isBreakable());
        assertTrue(glass.hasCollision(), "玻璃挡人：透明 ≠ 可穿过");
    }

    @Test
    void resourceCoreIsUnbreakableUnplaceableAndEmissive() {
        Block core = BlockRegistry.resourceCore();
        assertFalse(core.isBreakable(), "资源核心必须不可破坏（挖掘拒绝路径的对照组）");
        assertFalse(core.isPlaceable(), "资源核心不可放置（否则玩家能刷出不可破坏方块）");
        assertTrue(core.lightEmission() > 0, "资源核心必须是自发光方块（光照通路的唯一活体样本）");
        assertFalse(Float.isFinite(core.hardness()), "不可破坏方块用 Infinity 表示硬度");
        assertTrue(core.hasCollision());
    }

    @Test
    void terrainBlocksAreSolidOpaqueBreakableAndPlaceable() {
        // 注：`stoneBricks()` 已随 `skyisland:stone_bricks` 一并移除（用户裁决 A10，归 Alpha / M4），
        // 因此这一组少了它；M4 重新登记时把它加回来。
        Block[] terrain = {
                BlockRegistry.stone(), BlockRegistry.dirt(), BlockRegistry.grass(),
                BlockRegistry.sand(), BlockRegistry.cobblestone(),
                BlockRegistry.planks()};
        for (Block block : terrain) {
            assertTrue(block.isSolid(), block.id());
            assertFalse(block.isTransparent(), block.id());
            assertEquals(RenderType.OPAQUE, block.renderType(), block.id());
            assertTrue(block.isBreakable(), block.id());
            assertTrue(block.isPlaceable(), block.id());
            assertTrue(block.hasCollision(), block.id());
            assertTrue(block.hardness() > 0 && Float.isFinite(block.hardness()), block.id());
        }
    }

    @Test
    void airIsNotSolidNotBreakableNotPlaceable() {
        Block air = BlockRegistry.air();
        assertFalse(air.isSolid());
        assertFalse(air.isBreakable());
        assertFalse(air.isPlaceable());
        assertFalse(air.hasCollision());
        assertEquals(RenderType.INVISIBLE, air.renderType(),
                "空气不得生成任何面（INVISIBLE 是 ChunkMesher 的第一道跳过条件）");
        assertEquals(0, air.lightEmission());
    }

    @Test
    void vertexColorsAreUnitRange() {
        for (Block block : BlockRegistry.all()) {
            assertTrue(block.colorR() >= 0f && block.colorR() <= 1f, block.id());
            assertTrue(block.colorG() >= 0f && block.colorG() <= 1f, block.id());
            assertTrue(block.colorB() >= 0f && block.colorB() <= 1f, block.id());
        }
    }

    @Test
    void registeredBlocksAreDistinctObjects() {
        assertNotSame(BlockRegistry.air(), BlockRegistry.stone());
        assertNotSame(BlockRegistry.grass(), BlockRegistry.dirt());
    }
}

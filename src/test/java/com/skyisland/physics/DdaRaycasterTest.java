package com.skyisland.physics;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import com.skyisland.world.block.BlockRegistry;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDA 射线测试（TECH_DESIGN §J）。
 *
 * <p>覆盖三类最容易出错的情形：
 * <ol>
 *   <li><b>负坐标</b>：{@code (int)} 截断会让 x=-0.5 落到方块 0 上，于是射线整体错一格；</li>
 *   <li><b>起点在方块内部</b>：此时"命中面"不存在，必须显式标记而不是伪造一个法线 ——
 *       否则放置逻辑会算出 {@code adjacent == 命中方块自己}；</li>
 *   <li><b>方向未归一化</b>：{@code distance} 的口径是<u>世界单位</u>，不是参数 t。
 *       若忘记归一化，reach=5 会变成形状不明的可达范围。</li>
 * </ol>
 */
class DdaRaycasterTest {

    private static final int TOP = TestWorlds.SURFACE_BLOCK_Y;

    private static Vector3d v(double x, double y, double z) {
        return new Vector3d(x, y, z);
    }

    // ============================================================ 基本命中

    @Test
    void straightDownHitsTheTopFaceOfTheSurfaceBlock() {
        World world = TestWorlds.flatWorld();

        RaycastHit hit = DdaRaycaster.castSolid(world, v(0.5, 70.0, 0.5), v(0, -1, 0), 10.0);

        assertNotNull(hit);
        assertEquals(0, hit.blockX());
        assertEquals(TOP, hit.blockY());
        assertEquals(0, hit.blockZ());
        assertEquals(TestWorlds.grass(), hit.blockRuntimeId());
        assertEquals("UP", hit.faceName());
        // ★ 法线指向射线<u>来向</u>：射线向下打，命中的是方块顶面，外法线是 +Y。
        //   写成 -1 是最容易犯的错（把"射线方向"当成了法线）。
        assertEquals(1, hit.faceNormalY());
        assertEquals(0, hit.faceNormalX());
        assertEquals(0, hit.faceNormalZ());
        assertEquals(6.0, hit.distance(), 1e-9, "从 y=70 到方块 63 的顶面 y=64 恰好 6 格");
        assertTrue(hit.hasFace());
        assertFalse(hit.insideOriginBlock());
        assertEquals(0, hit.adjacentX());
        assertEquals(TOP + 1, hit.adjacentY(), "放置位置 = 命中方块 + 法线");
        assertEquals(0, hit.adjacentZ());
    }

    @Test
    void straightUpIntoOpenSkyMisses() {
        World world = TestWorlds.flatWorld();
        assertNull(DdaRaycaster.castSolid(world, v(0.5, 70.0, 0.5), v(0, 1, 0), 100.0));
    }

    @Test
    void reachLimitIsRespected() {
        World world = TestWorlds.flatWorld();
        // 距离 6 格，reach 只有 3 → 必须什么都打不到
        assertNull(DdaRaycaster.castSolid(world, v(0.5, 70.0, 0.5), v(0, -1, 0), 3.0));
        assertNotNull(DdaRaycaster.castSolid(world, v(0.5, 70.0, 0.5), v(0, -1, 0), 6.5));
    }

    @Test
    void directionIsNormalizedSoDistanceIsInWorldUnits() {
        World world = TestWorlds.flatWorld();

        RaycastHit unit = DdaRaycaster.castSolid(world, v(0.5, 70.0, 0.5), v(0, -1, 0), 10.0);
        RaycastHit scaled = DdaRaycaster.castSolid(world, v(0.5, 70.0, 0.5), v(0, -5, 0), 10.0);

        assertNotNull(unit);
        assertNotNull(scaled);
        assertEquals(unit.distance(), scaled.distance(), 1e-9,
                "方向长度不得影响 distance —— 忘记归一化会让 reach 的口径随调用方而变");
        assertEquals(6.0, scaled.distance(), 1e-9);
    }

    // ============================================================ 侧面与法线

    @Test
    void sideFaceNormalPointsOutOfTheHitBlock() {
        World world = TestWorlds.flatWorld();
        // 在地表上放一块木板，供射线横向命中
        assertTrue(world.placeBlock(2, TOP + 1, 0, TestWorlds.planks(),
                World.MutationCause.SELF_TEST, null).success());

        // ★ 起点必须落在木板<u>同一层</u>的空气里（y = TOP + 1.5，即方块 TOP+1 的腰高）。
        //   若写成 TOP + 0.5，起点就在地表的草方块内部 —— DDA 会在第 0 格判为
        //   "起点在方块内"并立刻返回，blockX 恒为 0，整条横向射线的断言全部失效。
        RaycastHit hit = DdaRaycaster.castSolid(world, v(0.5, TOP + 1.5, 0.5), v(1, 0, 0), 5.0);

        assertNotNull(hit);
        assertEquals(2, hit.blockX());
        assertEquals(TOP + 1, hit.blockY());
        assertEquals("-X", hit.faceName(), "射线沿 +X 前进，命中的是方块的 −X 面");
        assertEquals(-1, hit.faceNormalX());
        assertEquals(1.5, hit.distance(), 1e-9, "从 x=0.5 到方块 2 的 −X 面 x=2.0");
        assertEquals(1, hit.adjacentX(), "放置位置在命中方块的 −X 侧");
        assertEquals(TOP + 1, hit.adjacentY());
    }

    @Test
    void allSixFaceNamesAreReachable() {
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(0, 64, 0, TestWorlds.planks()));

        // ★ 法线 = −射线方向：射线朝哪边推进，就命中那个方向<u>对面</u>的脸。
        //   即"从东边打过去命中的是 +X 面"，这里逐条把它写死。
        assertEquals("UP", faceOf(world, v(0.5, 70.0, 0.5), v(0, -1, 0)), "向下 → 命中顶面");
        assertEquals("DOWN", faceOf(world, v(0.5, 60.0, 0.5), v(0, 1, 0)), "向上 → 命中底面");
        assertEquals("+X", faceOf(world, v(5.5, 64.5, 0.5), v(-1, 0, 0)), "从东往西 → 命中 +X 面");
        assertEquals("-X", faceOf(world, v(-5.5, 64.5, 0.5), v(1, 0, 0)), "从西往东 → 命中 −X 面");
        assertEquals("+Z", faceOf(world, v(0.5, 64.5, 5.5), v(0, 0, -1)), "从南往北 → 命中 +Z 面");
        assertEquals("-Z", faceOf(world, v(0.5, 64.5, -5.5), v(0, 0, 1)), "从北往南 → 命中 −Z 面");
    }

    private static String faceOf(World world, Vector3d origin, Vector3d direction) {
        RaycastHit hit = DdaRaycaster.castSolid(world, origin, direction, 20.0);
        assertNotNull(hit, "未命中：origin=" + origin + " dir=" + direction);
        return hit.faceName();
    }

    // ============================================================ 起点在方块内部

    @Test
    void originInsideBlockIsReportedExplicitly() {
        World world = TestWorlds.flatWorld();

        RaycastHit hit = DdaRaycaster.castSolid(world, v(0.5, TOP + 0.5, 0.5), v(0, -1, 0), 5.0);

        assertNotNull(hit);
        assertEquals(0, hit.blockX());
        assertEquals(TOP, hit.blockY());
        assertTrue(hit.insideOriginBlock(), "起点在实体方块内必须被显式标记");
        assertFalse(hit.hasFace(), "没有合法的放置面");
        assertEquals("INSIDE", hit.faceName());
        assertEquals(0.0, hit.distance(), 1e-9);
        assertEquals(0, hit.faceNormalX());
        assertEquals(0, hit.faceNormalY());
        assertEquals(0, hit.faceNormalZ());
        // ★ 关键：adjacent 等于命中方块自己。正因如此才必须用 hasFace() 拦住放置
        assertEquals(hit.blockX(), hit.adjacentX());
        assertEquals(hit.blockY(), hit.adjacentY());
        assertEquals(hit.blockZ(), hit.adjacentZ());
    }

    // ============================================================ 负坐标

    @Test
    void negativeCoordinatesAreFlooredNotTruncated() {
        World world = TestWorlds.flatWorld(-1, -1, 0, 0);

        RaycastHit hit = DdaRaycaster.castSolid(world, v(-0.5, 70.0, -0.5), v(0, -1, 0), 10.0);

        assertNotNull(hit);
        assertEquals(-1, hit.blockX(), "x=-0.5 属于方块 -1（截断会得到 0）");
        assertEquals(TOP, hit.blockY());
        assertEquals(-1, hit.blockZ(), "z=-0.5 属于方块 -1（截断会得到 0）");
        assertEquals(6.0, hit.distance(), 1e-9);
    }

    @Test
    void negativeCoordinateHorizontalRayFindsTheRightBlock() {
        World world = TestWorlds.flatWorld(-1, -1, 0, 0);
        // 在 (-3, TOP+1, 0) 放一块木板，从 x=-0.5 沿 −X 射过去。
        // 起点同样取 y = TOP+1.5（木板腰高、空气层），否则会掉进起点在方块内的退化分支。
        assertTrue(world.placeBlock(-3, TOP + 1, 0, TestWorlds.planks(),
                World.MutationCause.SELF_TEST, null).success());

        RaycastHit hit = DdaRaycaster.castSolid(world, v(-0.5, TOP + 1.5, 0.5), v(-1, 0, 0), 5.0);

        assertNotNull(hit);
        assertEquals(-3, hit.blockX());
        assertEquals("+X", hit.faceName(), "从东侧打向西侧，命中的是 +X 面");
        assertEquals(1, hit.faceNormalX());
        assertEquals(1.5, hit.distance(), 1e-9, "从 x=-0.5 到方块 -3 的 +X 面 x=-2.0");
        assertEquals(-2, hit.adjacentX());
    }

    // ============================================================ 命中判定的可替换性

    @Test
    void castBreakableSkipsUnbreakableBlocks() {
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(0, 64, 0, TestWorlds.resourceCore()),
                TestWorlds.cell(0, 63, 0, TestWorlds.stone()));

        RaycastHit solid = DdaRaycaster.castSolid(world, v(0.5, 70.0, 0.5), v(0, -1, 0), 20.0);
        assertNotNull(solid);
        assertEquals(64, solid.blockY(), "castSolid 命中第一个非空气方块（不可破坏的核心）");
        assertEquals(TestWorlds.resourceCore(), solid.blockRuntimeId());

        RaycastHit breakable = DdaRaycaster.castBreakable(world, v(0.5, 70.0, 0.5), v(0, -1, 0), 20.0);
        assertNotNull(breakable);
        assertEquals(63, breakable.blockY(), "castBreakable 必须穿过不可破坏方块");
        assertEquals(TestWorlds.stone(), breakable.blockRuntimeId());
    }

    @Test
    void customHitTestIsHonoured() {
        World world = TestWorlds.flatWorld();

        // 只命中草方块：石头会被穿过
        RaycastHit hit = DdaRaycaster.cast(world, v(0.5, 70.0, 0.5), v(0, -1, 0), 20.0,
                block -> block == BlockRegistry.grass());

        assertNotNull(hit);
        assertEquals(TOP, hit.blockY());
        assertEquals(TestWorlds.grass(), hit.blockRuntimeId());
    }

    @Test
    void castFirstAirFindsTheHoleThePlayerDug() {
        World world = TestWorlds.flatWorld();
        world.breakBlock(0, TOP, 0, World.MutationCause.PLAYER_BREAK);

        // 从地表内部向下射：第一个空气格就是刚挖出来的那个洞
        RaycastHit hit = DdaRaycaster.castFirstAir(world, v(0.5, TOP + 0.5, 0.5), v(0, -1, 0), 10.0);

        assertNotNull(hit);
        assertEquals(0, hit.blockX());
        assertEquals(TOP, hit.blockY(), "挖掉的是 y=" + TOP + " 那一格");
        assertEquals(0, hit.blockZ());
        assertTrue(world.isAirAt(hit.blockX(), hit.blockY(), hit.blockZ()));
    }

    // ============================================================ 边界与防御

    @Test
    void degenerateArgumentsReturnNullInsteadOfThrowing() {
        World world = TestWorlds.flatWorld();
        assertNull(DdaRaycaster.cast(null, v(0, 0, 0), v(0, -1, 0), 5.0, null));
        assertNull(DdaRaycaster.cast(world, null, v(0, -1, 0), 5.0, null));
        assertNull(DdaRaycaster.cast(world, v(0, 0, 0), null, 5.0, null));
        assertNull(DdaRaycaster.cast(world, v(0.5, 70, 0.5), v(0, 0, 0), 5.0, null),
                "零向量方向必须返回 null，而不是产生 NaN 的单元格推进");
        assertNull(DdaRaycaster.cast(world, v(0.5, 70, 0.5), v(0, -1, 0), 0.0, null));
        assertNull(DdaRaycaster.cast(world, v(0.5, 70, 0.5), v(0, -1, 0), -1.0, null));
    }

    @Test
    void nullHitTestFallsBackToNotAir() {
        World world = TestWorlds.flatWorld();
        RaycastHit hit = DdaRaycaster.cast(world, v(0.5, 70.0, 0.5), v(0, -1, 0), 10.0, null);
        assertNotNull(hit);
        assertEquals(TOP, hit.blockY());
    }

    @Test
    void rayThroughUnloadedChunkSeesAirAndPassesThrough() {
        World world = TestWorlds.flatWorld();
        // 沿地表<u>之上</u>一格（y=64.5，空气层）向 +X 走：区块 (0,0) 内全空，
        // 出界后未加载区块也读作空气 → 必须返回 null 而不是卡住或抛异常
        assertNull(DdaRaycaster.castSolid(world, v(0.5, TOP + 1.5, 0.5), v(1, 0, 0), 60.0));
        // 对照：同一射线若落在地表那一层（y=63.5），起点就已经在实心方块内部
        RaycastHit inside = DdaRaycaster.castSolid(world, v(0.5, TOP + 0.5, 0.5), v(1, 0, 0), 60.0);
        assertNotNull(inside);
        assertTrue(inside.insideOriginBlock());
    }

    @Test
    void blockPosAndAdjacentPosHelpersMatchTheFields() {
        World world = TestWorlds.flatWorld();
        RaycastHit hit = DdaRaycaster.castSolid(world, v(0.5, 70.0, 0.5), v(0, -1, 0), 10.0);
        assertNotNull(hit);

        assertEquals(hit.blockX(), hit.blockPos()[0]);
        assertEquals(hit.blockY(), hit.blockPos()[1]);
        assertEquals(hit.blockZ(), hit.blockPos()[2]);
        assertEquals(hit.adjacentX(), hit.adjacentPos()[0]);
        assertEquals(hit.adjacentY(), hit.adjacentPos()[1]);
        assertEquals(hit.adjacentZ(), hit.adjacentPos()[2]);
    }
}

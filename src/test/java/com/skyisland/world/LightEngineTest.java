package com.skyisland.world;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.block.BlockRegistry;
import com.skyisland.world.gen.TestWorldGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 光照引擎测试（TECH_DESIGN §H.5 的口径：天光=列遮挡模型，火把=切比雪夫衰减，不做全局传播）。
 *
 * <p>这一层最值得固化的是<b>两处"差一格"的边界</b>：
 * <ol>
 *   <li>{@code skyLightAt} 在 {@code ly == columnTop} 处必须是 15
 *       （否则最上面那一层方块会整体变暗，而地形表面恰恰是玩家看得最多的地方）；</li>
 *   <li>火把衰减必须用<u>世界坐标</u>。用局部坐标算距离会让紧贴区块边界的同一个世界位置
 *       在两侧得到不同光照，表现为沿着区块边界的一条硬边 —— 见
 *       {@link #torchLightIsFilteredByDistanceFromChunkNotByLocalCoordinate()}。</li>
 * </ol>
 */
class LightEngineTest {

    private static final int TOP = TestWorlds.SURFACE_BLOCK_Y;   // 63

    // ============================================================ 天光

    @Test
    void columnTopIsTheHighestNonAirBlock() {
        World world = TestWorlds.flatWorld();
        LightEngine light = LightEngine.snapshot(world.chunkAt(0, 0), world);

        for (int lz = 0; lz < 16; lz += 5) {
            for (int lx = 0; lx < 16; lx += 5) {
                assertEquals(TOP, light.columnTop(lx, lz), "列顶应为 " + TOP + " @ " + lx + "," + lz);
            }
        }
    }

    @Test
    void columnTopIsMinusOneForCompletelyEmptyColumn() {
        // 只放一个方块在 x=0,z=0 列 → 其余列整列为空
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(0, 70, 0, TestWorlds.stone()));
        LightEngine light = LightEngine.snapshot(world.chunkAt(0, 0), world);

        assertEquals(70, light.columnTop(0, 0));
        assertEquals(-1, light.columnTop(3, 3), "整列为空必须是 -1（虚空坑的判据）");
    }

    @Test
    void skyLightIsFullAtAndAboveColumnTopAndZeroBelow() {
        World world = TestWorlds.flatWorld();
        LightEngine light = LightEngine.snapshot(world.chunkAt(0, 0), world);

        assertEquals(15, light.skyLightAt(0, TOP, 0),
                "列顶那一格必须满亮度，否则地表方块会整体偏暗");
        assertEquals(15, light.skyLightAt(0, TOP + 1, 0));
        assertEquals(15, light.skyLightAt(0, 127, 0));
        assertEquals(0, light.skyLightAt(0, TOP - 1, 0), "列顶以下无天光（列遮挡模型，不做侧向漫射）");
        assertEquals(0, light.skyLightAt(0, 0, 0));
    }

    @Test
    void skyLightQueryOutOfRangeIsSafe() {
        World world = TestWorlds.flatWorld();
        LightEngine light = LightEngine.snapshot(world.chunkAt(0, 0), world);

        assertEquals(-1, light.columnTop(-1, 0));
        assertEquals(-1, light.columnTop(16, 0));
        // columnTop 为 -1 时任何 ly >= -1 都为真 → 返回满亮度。这是"越界一律当开阔天空"的保守取值
        assertEquals(15, light.skyLightAt(-1, 64, 0));
    }

    // ============================================================ 火把光

    @Test
    void noEmissiveSourceMeansNoTorchLight() {
        World world = TestWorlds.flatWorld();
        LightEngine light = LightEngine.snapshot(world.chunkAt(0, 0), world);

        assertEquals(0, light.sourceCount());
        assertEquals(0, light.torchLightAt(0, 0, 0, TOP, 0));
        assertEquals(0, light.torchLightAt(0, 0, 8, 8, 8));
    }

    @Test
    void torchLightDecaysByChebyshevDistance() {
        // 资源核心（自发光 15）位于 (2,64,2)，属于区块 (0,0)
        World world = new World(1L, new TestWorldGenerator());
        world.ensureAreaLoaded(0, 0, 0, 0);
        LightEngine light = LightEngine.snapshot(world.chunkAt(0, 0), world);

        assertEquals(1, light.sourceCount());

        int ox = 0;
        int oz = 0;
        assertEquals(15, light.torchLightAt(ox, oz, 2, 64, 2), "距离 0 → 满亮度");
        assertEquals(14, light.torchLightAt(ox, oz, 3, 64, 2), "距离 1 → 14");
        assertEquals(12, light.torchLightAt(ox, oz, 2, 64, 5), "距离 3 → 12（切比雪夫：只看最大分量）");
        assertEquals(9, light.torchLightAt(ox, oz, 8, 64, 2), "距离 6 → 9（恰好等于影响半径）");
        assertEquals(0, light.torchLightAt(ox, oz, 9, 64, 2), "距离 7 > 半径 6 → 0");
        assertEquals(0, light.torchLightAt(ox, oz, 2, 56, 2), "竖直距离 8 > 半径 6 → 0");
    }

    @Test
    void combinedLightTakesTheMaximumOfSkyAndTorch() {
        World world = new World(1L, new TestWorldGenerator());
        world.ensureAreaLoaded(0, 0, 0, 0);
        LightEngine light = LightEngine.snapshot(world.chunkAt(0, 0), world);

        int ox = 0;
        int oz = 0;
        // 地表以下（天光 0）→ 只能是火把光
        assertEquals(light.torchLightAt(ox, oz, 2, 62, 2), light.lightAt(ox, oz, 2, 62, 2));
        // 高空（天光 15）→ 取最大，必然是 15
        assertEquals(15, light.lightAt(ox, oz, 2, 100, 2));
        assertTrue(light.lightAt(ox, oz, 2, 64, 2) >= light.torchLightAt(ox, oz, 2, 64, 2));
    }

    @Test
    void torchLightIsFilteredByDistanceFromChunkNotByLocalCoordinate() {
        // 光源在 (17,64,0)：属于区块 (1,0)，但与区块 (0,0) 的水平距离只有 2
        World world = TestWorlds.scatteredWorld(0, 0, 3, 0,
                TestWorlds.cell(17, 64, 0, TestWorlds.resourceCore()));

        Chunk near = world.chunkAt(0, 0);
        Chunk far = world.chunkAt(3, 0);   // 原点 x=48，距离光源 31

        assertEquals(1, LightEngine.snapshot(near, world).sourceCount(),
                "跨界光源必须被纳入，否则区块边界上会出现一条亮度硬边");
        assertEquals(0, LightEngine.snapshot(far, world).sourceCount(),
                "超出半径的光源不该被纳入（否则每区块都要遍历全世界的灯）");

        LightEngine nearLight = LightEngine.snapshot(near, world);
        // ★ 关键断言：区块 (0,0) 的 +X 边界列（lx=15 → 世界 x=15）距光源只有 2，
        //   必须被这个"住在隔壁区块"的光源照亮。若实现按局部坐标算距离，这里会是 0。
        assertEquals(13, nearLight.torchLightAt(0, 0, 15, 64, 0),
                "世界 (15,64,0) 距光源 (17,64,0) 为 2 → 15-2=13");
        assertEquals(12, nearLight.torchLightAt(0, 0, 14, 64, 0), "世界 (14,64,0) 距离 3 → 12");
        assertEquals(0, nearLight.torchLightAt(0, 0, 10, 64, 0), "世界 (10,64,0) 距离 7 > 半径 6 → 0");
        assertEquals(0, nearLight.torchLightAt(0, 0, 0, 64, 0), "世界 (0,64,0) 距离 17 → 0");
    }

    // ============================================================ 明暗映射

    @Test
    void shadeFactorMapsLightRangeToUsableBrightness() {
        World world = TestWorlds.flatWorld();
        LightEngine light = LightEngine.snapshot(world.chunkAt(0, 0), world);

        assertEquals(1.0f, light.shadeFactor(15), 1e-6f, "满亮度必须是 1.0");
        assertEquals(0.45f, light.shadeFactor(0), 1e-6f,
                "全黑保留 0.45 的地板：M1 无火把也无夜晚，映射到 0 会让地下方块变成纯黑看不清形状");
        assertEquals(light.shadeFactor(15), light.shadeFactor(99), 1e-6f, "越界应被夹到 15");
        assertEquals(light.shadeFactor(0), light.shadeFactor(-5), 1e-6f, "负值应被夹到 0");

        assertTrue(light.shadeFactor(8) > light.shadeFactor(4));
        assertTrue(light.shadeFactor(4) > light.shadeFactor(1));
        assertNotEquals(light.shadeFactor(0), light.shadeFactor(15));
    }

    @Test
    void shadeFactorIsMonotonicOverTheWholeRange() {
        World world = TestWorlds.flatWorld();
        LightEngine light = LightEngine.snapshot(world.chunkAt(0, 0), world);

        float previous = -1f;
        for (int level = 0; level <= 15; level++) {
            float shade = light.shadeFactor(level);
            assertTrue(shade > previous, "光照→明暗必须严格单调递增，level=" + level);
            previous = shade;
        }
    }

    // ============================================================ 光源判据

    @Test
    void isEmissiveOnlyForNonAirLitBlocks() {
        assertTrue(LightEngine.isEmissive(BlockRegistry.resourceCore()));
        assertFalse(LightEngine.isEmissive(BlockRegistry.stone()));
        assertFalse(LightEngine.isEmissive(BlockRegistry.glass()));
        assertFalse(LightEngine.isEmissive(BlockRegistry.air()));
        assertFalse(LightEngine.isEmissive(null), "null 不得抛异常（网格化路径上可能被传入）");
    }
}

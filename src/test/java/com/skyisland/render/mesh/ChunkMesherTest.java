package com.skyisland.render.mesh;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.util.Coords;
import com.skyisland.world.Chunk;
import com.skyisland.world.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区块网格化测试（TECH_DESIGN §G.1–§G.4）。
 *
 * <p><b>为什么这些断言可以精确到"面数"：</b>暴露面剔除是一套纯组合规则 ——
 * 一个孤立方块 6 个面、两个相邻方块 10 个面、3×3×3 实心块 54 个面。
 * 面数是<u>整数</u>，因此它比任何"看起来对不对"的观感判断都更适合当门禁。
 *
 * <p>覆盖四类容易写错的地方：
 * <ol>
 *   <li><b>同种透明相邻面剔除</b>（§G.4 第 4 条）：玻璃贴玻璃只应生成一层，
 *       否则同一处被混合两次，看起来"两格厚的玻璃比一格亮"；</li>
 *   <li><b>子网格归属由自身决定、面是否生成由邻居决定</b>：这两件事混在一个判断里，
 *       就会出现"石头贴着玻璃时石头那一侧的透明面跑到透明子网格"这类错位；</li>
 *   <li><b>跨区块邻居查询</b>：界内走区块、界外问世界。既不能一律当空气
 *       （区块交界处多出一层"内部墙面"），也不能假定邻区块已加载；</li>
 *   <li><b>顶点只写区块局部坐标</b>（§G.3）：世界坐标走 uniform，
 *       否则世界坐标到 ±数千后 {@code float} 整数精度丢失，表现为远处地形抖动。</li>
 * </ol>
 */
class ChunkMesherTest {

    private static final int FPV = MeshData.FLOATS_PER_VERTEX;

    /** 一个"什么都不放"的世界，用于拿到空区块。 */
    private static World emptyWorld() {
        return TestWorlds.scatteredWorld(0, 0, 0, 0);
    }

    private static World oneCell(int x, int y, int z, int runtimeId) {
        return TestWorlds.scatteredWorld(0, 0, 0, 0, TestWorlds.cell(x, y, z, runtimeId));
    }

    private static MeshData mesh(World world, int cx, int cz) {
        Chunk chunk = world.getOrLoadChunk(cx, cz);
        return ChunkMesher.build(chunk, world);
    }

    // ============================================================ 基本面数

    @Test
    void isolatedBlockEmitsSixFaces() {
        MeshData data = mesh(oneCell(4, 64, 4, TestWorlds.stone()), 0, 0);

        assertEquals(6, data.opaqueFaceCount(), "孤立方块：6 个方向都是空气 → 6 个面");
        assertEquals(0, data.transparentFaceCount());
        assertEquals(6 * 4 * FPV, data.opaqueVertices().length, "每个面 4 个顶点");
        assertEquals(6 * 6, data.opaqueIndices().length, "每个面 2 个三角形 = 6 个索引");
        assertEquals(36, data.totalIndexCount());
    }

    @Test
    void twoAdjacentBlocksEmitTenFaces() {
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(4, 64, 4, TestWorlds.stone()),
                TestWorlds.cell(5, 64, 4, TestWorlds.stone()));

        MeshData data = mesh(world, 0, 0);
        assertEquals(10, data.opaqueFaceCount(), "6 + 6 − 2（共享的那对内部面必须被剔除）");
    }

    @Test
    void solidCubeOnlyEmitsItsShell() {
        // 3×3×3 实心石头：内部那个方块应当一个面都不产生 → 外壳 6 × 9 = 54 面
        List<int[]> cells = new ArrayList<>();
        for (int x = 3; x <= 5; x++) {
            for (int y = 63; y <= 65; y++) {
                for (int z = 3; z <= 5; z++) {
                    cells.add(TestWorlds.cell(x, y, z, TestWorlds.stone()));
                }
            }
        }
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0, cells.toArray(new int[0][]));

        MeshData data = mesh(world, 0, 0);
        assertEquals(54, data.opaqueFaceCount(),
                "只应有外壳 —— 出现 162（27×6）说明内部面没有被剔除");
    }

    @Test
    void emptyChunkReturnsTheSharedEmptyMesh() {
        World world = emptyWorld();
        MeshData data = mesh(world, 0, 0);

        assertSame(MeshData.EMPTY, data, "空区块必须早退，连顶点数组都不该分配");
        assertTrue(data.isEmpty());
        assertEquals(0, data.totalFaceCount());
        assertEquals(0, data.totalVertexFloatCount());
    }

    // ============================================================ 透明子网格

    @Test
    void isolatedGlassGoesToTheTransparentSubmeshOnly() {
        MeshData data = mesh(oneCell(4, 64, 4, TestWorlds.glass()), 0, 0);

        assertEquals(0, data.opaqueFaceCount(), "玻璃不得混进不透明子网格");
        assertEquals(6, data.transparentFaceCount());
        assertEquals(6 * 4 * FPV, data.transparentVertices().length);
        assertEquals(6 * 6, data.transparentIndices().length);
        assertEquals(0, data.opaqueVertices().length);
    }

    @Test
    void touchingGlassBlocksShareOneFaceNotTwo() {
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(4, 64, 4, TestWorlds.glass()),
                TestWorlds.cell(5, 64, 4, TestWorlds.glass()));

        MeshData data = mesh(world, 0, 0);
        assertEquals(10, data.transparentFaceCount(),
                "同种透明相邻必须只生成一层 —— 12 意味着共享面生成了两遍，"
                        + "视觉上表现为「两格厚的玻璃比一格亮」");
    }

    @Test
    void stoneAgainstGlassKeepsBothSidesRuleCorrect() {
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(4, 64, 4, TestWorlds.stone()),
                TestWorlds.cell(5, 64, 4, TestWorlds.glass()));

        MeshData data = mesh(world, 0, 0);

        // 石头自己 6 个面全生成（+X 邻居是"异种透明"，按 §G.4 第 5 条要生成）
        assertEquals(6, data.opaqueFaceCount(),
                "石头朝向玻璃的那一面必须生成 —— 否则从玻璃一侧看进去会看到空洞");
        // 玻璃朝向石头的那一面被剔除（邻居不透明 → 完全被遮挡）
        assertEquals(5, data.transparentFaceCount(),
                "玻璃朝向石头的那一面必须被剔除（共 11 面而不是 12 面）");
    }

    // ============================================================ 跨区块邻居

    @Test
    void faceAcrossChunkBoundaryIsCulledWhenNeighbourChunkIsLoaded() {
        World world = TestWorlds.scatteredWorld(0, 0, 1, 0,
                TestWorlds.cell(Coords.CHUNK_SIZE - 1, 63, 0, TestWorlds.stone()),   // (15,63,0) 属区块 (0,0)
                TestWorlds.cell(Coords.CHUNK_SIZE, 63, 0, TestWorlds.stone()));      // (16,63,0) 属区块 (1,0)

        MeshData fromLeft = mesh(world, 0, 0);
        MeshData fromRight = mesh(world, 1, 0);

        assertEquals(5, fromLeft.opaqueFaceCount(),
                "区块 (0,0) 的边界方块朝向 (1,0) 的那一面必须被剔除");
        assertEquals(5, fromRight.opaqueFaceCount(),
                "反向同理 —— 两侧都必须问世界，只处理一侧会在交界处留下「内部墙面」");
    }

    @Test
    void faceAcrossChunkBoundaryWithoutWorldAssumesAir() {
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(Coords.CHUNK_SIZE - 1, 63, 0, TestWorlds.stone()));
        Chunk chunk = world.getOrLoadChunk(0, 0);

        MeshData withWorld = ChunkMesher.build(chunk, world);
        MeshData withoutWorld = ChunkMesher.build(chunk);

        assertEquals(6, withWorld.opaqueFaceCount(),
                "邻区块未加载 → 按空气处理并生成该面（宁可多画一面，不可漏画）");
        assertEquals(6, withoutWorld.opaqueFaceCount(),
                "world 传 null 时区块外一律视为空气");
    }

    // ============================================================ 顶点数据

    @Test
    void vertexPositionsAreChunkLocalNotWorld() {
        // 放到区块 (2,3)，世界坐标 x/z 分别是 32/48 附近 —— 局部坐标必须仍在 [0,16]
        int originX = 2 * Coords.CHUNK_SIZE;
        int originZ = 3 * Coords.CHUNK_SIZE;
        World world = TestWorlds.scatteredWorld(2, 3, 2, 3,
                TestWorlds.cell(originX + 4, 64, originZ + 4, TestWorlds.stone()));

        MeshData data = mesh(world, 2, 3);
        assertEquals(6, data.opaqueFaceCount());

        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < data.opaqueVertices().length; i += FPV) {
            minX = Math.min(minX, data.opaqueVertices()[i]);
            maxX = Math.max(maxX, data.opaqueVertices()[i]);
            minZ = Math.min(minZ, data.opaqueVertices()[i + 2]);
            maxZ = Math.max(maxZ, data.opaqueVertices()[i + 2]);
        }
        assertTrue(minX >= 4f && maxX <= 5f, "x 必须落在 [4,5]（局部），实际 [" + minX + "," + maxX + "]");
        assertTrue(minZ >= 4f && maxZ <= 5f, "z 必须落在 [4,5]（局部），实际 [" + minZ + "," + maxZ + "]");
        assertTrue(maxX < Coords.CHUNK_SIZE,
                "出现大于 16 的坐标说明写的是世界坐标 —— 远距离下 float 精度会崩");
    }

    @Test
    void indicesAreDenseAndWithinVertexBounds() {
        MeshData data = mesh(oneCell(4, 64, 4, TestWorlds.stone()), 0, 0);

        int vertexCount = data.opaqueVertices().length / FPV;
        int maxIndex = -1;
        for (int index : data.opaqueIndices()) {
            assertTrue(index >= 0, "索引不得为负");
            assertTrue(index < vertexCount, "索引 " + index + " 越界（顶点数 " + vertexCount + "）");
            maxIndex = Math.max(maxIndex, index);
        }
        assertEquals(vertexCount - 1, maxIndex,
                "索引必须铺满 0..n−1 —— 有空号说明有顶点被写进了却没有任何三角形引用");
    }

    @Test
    void faceCountAndVertexCountStayConsistent() {
        MeshData data = mesh(oneCell(4, 64, 4, TestWorlds.glass()), 0, 0);

        assertEquals(data.opaqueFaceCount() * 4 * FPV, data.opaqueVertices().length);
        assertEquals(data.opaqueFaceCount() * 6, data.opaqueIndices().length);
        assertEquals(data.transparentFaceCount() * 4 * FPV, data.transparentVertices().length);
        assertEquals(data.transparentFaceCount() * 6, data.transparentIndices().length);
    }

    /**
     * 光照被烘焙进顶点色的 alpha 分量（§G.3），因此"面向不同方向的明暗差异"
     * 是可以从数据里读出来的 —— 这条断言把 {@link BlockFace#shade()} 与实际写出的
     * 顶点数据绑在一起，避免将来改网格化时把明暗丢掉（画面会变成一团色块）。
     */
    @Test
    void faceBrightnessIsBakedIntoVertexAlpha() {
        MeshData data = mesh(oneCell(4, 64, 4, TestWorlds.stone()), 0, 0);

        // 该方块位于所在列的最高处 → 天光 15 → shadeFactor(15) = 1.0，
        // 于是顶点 alpha 就等于 BlockFace.shade() 本身。
        float maxShade = -Float.MAX_VALUE;
        float minShade = Float.MAX_VALUE;
        int maxCount = 0;
        int minCount = 0;
        for (int i = 0; i < data.opaqueVertices().length; i += FPV) {
            float alpha = data.opaqueVertices()[i + 6];
            if (alpha > maxShade) {
                maxShade = alpha;
                maxCount = 1;
            } else if (alpha == maxShade) {
                maxCount++;
            }
            if (alpha < minShade) {
                minShade = alpha;
                minCount = 1;
            } else if (alpha == minShade) {
                minCount++;
            }
        }

        assertEquals(BlockFace.POS_Y.shade(), maxShade, 1e-5, "最亮的面应当是顶面");
        assertEquals(BlockFace.NEG_Y.shade(), minShade, 1e-5, "最暗的面应当是底面");
        assertEquals(4, maxCount, "顶面恰好 4 个顶点");
        assertEquals(4, minCount, "底面恰好 4 个顶点");
        assertEquals(2.0, maxShade / minShade, 1e-4,
                "顶/底明暗比应为 1.00 : 0.50 —— 全亮等于立方体糊成一团，看不出体素结构");
    }

    // ============================================================ 与方块改动的联动

    @Test
    void breakingABlockOpensUpNewFaces() {
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(4, 63, 4, TestWorlds.stone()),
                TestWorlds.cell(4, 64, 4, TestWorlds.stone()));

        assertEquals(10, mesh(world, 0, 0).opaqueFaceCount(), "前提：两块上下相邻 → 10 面");

        world.breakBlock(4, 64, 4, World.MutationCause.PLAYER_BREAK);
        MeshData after = mesh(world, 0, 0);

        assertEquals(6, after.opaqueFaceCount(),
                "拆掉上面那块后，下面那块裸露出的顶面必须重新生成");
    }

    @Test
    void placingABlockHidesTheFaceItCovers() {
        World world = oneCell(4, 63, 4, TestWorlds.stone());
        assertEquals(6, mesh(world, 0, 0).opaqueFaceCount());

        // 放在正上方：需要相邻支撑，所以这块放置是合法的
        assertTrue(world.placeBlock(4, 64, 4, TestWorlds.stone(),
                World.MutationCause.PLAYER_PLACE, null).success());
        MeshData after = mesh(world, 0, 0);

        assertEquals(10, after.opaqueFaceCount(), "6 + 6 − 2");
    }

    @Test
    void airBlocksInvisibleSystemBlockEmitsNothing() {
        // resource_core 的 RenderType 是 OPAQUE，不能用来验 INVISIBLE；
        // 这里用"生成器什么都没写"的区块替代，等价于整块空气
        MeshData data = mesh(emptyWorld(), 0, 0);
        assertEquals(0, data.totalFaceCount());
    }
}

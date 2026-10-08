package com.skyisland.render.mesh;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.Chunk;
import com.skyisland.world.World;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockBox;
import com.skyisland.world.block.BlockFixtures;
import com.skyisland.world.block.BlockRegistry;
import com.skyisland.world.block.BlockShape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异形方块（<b>非满方块</b>）的网格与碰撞测试 —— PRD §8 的 S1 步，PRD §7 风险 R1。
 *
 * <p><b>为什么这一组测试是本轮最高风险的守门人：</b>
 * M1 的 {@code ChunkMesher} 与 {@code AABB} 都<b>隐含"每个方块占满整格"</b>这个前提，
 * 且代码里没有任何地方能表达例外。本轮要加的两种方块正好各打破一半前提：
 * <ul>
 *   <li><b>小麦</b>打破"占满整格"的<b>碰撞</b>侧 —— 若仍按满格判，
 *       玩家会撞上一堵<b>看不见的墙</b>；</li>
 *   <li><b>台阶</b>打破"占满整格"的<b>几何</b>侧 —— 网格若是满方块缩小而非半高盒，
 *       玩家会看到台阶上方浮着一层顶面，且站在上面被判为悬空。</li>
 * </ul>
 *
 * <p><b>判据是"顶点数/面数/碰撞盒形状"，不是"能跑不崩"。</b>
 * 理由：一个走错分支的实现很可能<b>照样跑通</b>（不崩、有画面），
 * 只是画面上多了一层看不见的墙 —— 只有精确的面数与盒高能把它抓出来。
 *
 * <p><b>关于"满方块路径未被破坏"：</b>原有 {@link ChunkMesherTest} 全部保持不变并全绿，
 * 那才是最强证据（满方块走的是<b>另一条</b>代码分支，见 {@code emitFace} 的形态分派）。
 * 本类末尾有一条"既有 17 种方块全是满方块、且 S5 新增 2 种确为异形"的显式断言，
 * 防止将来有人顺手把某个既有方块改成异形却忘了重算面数
 * —— 也防止有人反过来把小麦/台阶改成满方块来"修绿"它。
 */
class NonFullBlockMesherTest {

    private static final int FPV = MeshData.FLOATS_PER_VERTEX;

    /** 满方块孤立方块的面数 —— 所有对比的基准。 */
    private static final int FULL_BLOCK_FACES = 6;

    /** 小麦（十字面）应当产生的面数：2 个对角面 × 双向 = 4。 */
    private static final int CROSS_FACES = 4;

    // ============================================================ 测试夹具

    private static final int WHEAT_ID = BlockFixtures.TEST_RUNTIME_ID_BASE;
    private static final int SLAB_ID = BlockFixtures.TEST_RUNTIME_ID_BASE + 1;
    private static final int CROSS_FLAGGED_ID = BlockFixtures.TEST_RUNTIME_ID_BASE + 2;

    private static final Block WHEAT = BlockFixtures.wheat(WHEAT_ID);
    private static final Block SLAB = BlockFixtures.slab(SLAB_ID);
    private static final Block CROSS_FLAGGED = BlockFixtures.crossWithCollisionFlagOn(CROSS_FLAGGED_ID);

    /**
     * 注入测试方块的解析器：命中的返回夹具，<b>其余一律回落到真实注册表</b>。
     *
     * <p><b>为什么必须回落而不是返回空气：</b>
     * 本类有几条测试要在<b>同一个世界里同时</b>放测试方块与真实方块
     * （例如"台阶紧贴石头"要验证剔除规则一致）。
     * 若未命中的返回空气，石头会凭空消失，那条测试就变成在测"两块空气" ——
     * 断言依然可能绿，但<b>它已经不是在测它声称的东西了</b>。
     * 这类"因为夹具造假而假绿"是本项目吃过亏的坑（见 {@code SourceScan} 的类注释）。
     */
    private static java.util.function.IntFunction<Block> testLookup() {
        return runtimeId -> {
            if (runtimeId == WHEAT_ID) {
                return WHEAT;
            }
            if (runtimeId == SLAB_ID) {
                return SLAB;
            }
            if (runtimeId == CROSS_FLAGGED_ID) {
                return CROSS_FLAGGED;
            }
            return BlockRegistry.byRuntimeId(runtimeId);
        };
    }

    /** 放一个测试方块，网格化并把测试解析器注入。 */
    private static MeshData meshOf(int x, int y, int z, int runtimeId) {
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0, TestWorlds.cell(x, y, z, runtimeId));
        Chunk chunk = world.getOrLoadChunk(0, 0);
        return ChunkMesher.build(chunk, world, null, testLookup());
    }

    // ============================================================ 小麦：十字交叉面

    @Test
    void wheatMeshIsFourCrossQuadsNotASixFacedCube() {
        MeshData data = meshOf(4, 64, 4, WHEAT_ID);

        assertEquals(CROSS_FACES, data.transparentFaceCount(),
                "小麦必须是 4 个十字面（PRD §3.2.2），不是满方块的 " + FULL_BLOCK_FACES + " 面 —— "
                        + "6 面说明它退化成了满方块，玩家会看到一堵实心墙而不是一株作物");
        assertEquals(0, data.opaqueFaceCount(),
                "小麦 RenderType=TRANSPARENT（PRD §3.2.2），不得混进不透明子网格");
    }

    @Test
    void wheatVertexCountMatchesFourQuads() {
        MeshData data = meshOf(4, 64, 4, WHEAT_ID);

        assertEquals(CROSS_FACES * MeshData.VERTICES_PER_FACE * FPV, data.transparentVertices().length,
                "4 个面 × 4 顶点；数量对不上说明某个面只发了 3 个顶点（残缺四边形）");
        assertEquals(CROSS_FACES * 6, data.transparentIndices().length,
                "每个面 2 个三角形 = 6 个索引");
        assertEquals(0, data.opaqueVertices().length);
    }

    /**
     * 十字面的<b>竖直性</b>：顶点在 y 与 y+1 两层，而 x/z 落在对角线上。
     *
     * <p>这条比"4 个面"更强：它能抓住"发了 4 个面但几何放错了"的情况
     * （例如把十字面误做成平铺在地面上的 4 个小面）。
     */
    @Test
    void wheatCrossQuadsSpanTheFullCellHeightOnDiagonals() {
        MeshData data = meshOf(4, 64, 4, WHEAT_ID);
        float[] v = data.transparentVertices();

        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        java.util.Set<Float> xs = new java.util.TreeSet<>();
        java.util.Set<Float> zs = new java.util.TreeSet<>();
        for (int i = 0; i < v.length; i += FPV) {
            minY = Math.min(minY, v[i + 1]);
            maxY = Math.max(maxY, v[i + 1]);
            xs.add(v[i]);
            zs.add(v[i + 2]);
        }

        assertEquals(64f, minY, 1e-5, "十字面必须从格底起（作物是'长在土里的一株'）");
        assertEquals(65f, maxY, 1e-5, "十字面必须竖直贯穿整格（缩到半高会像被地面切掉一半）");
        assertEquals(2, xs.size(), "x 只应取格子的两个边界值 —— 出现中间值说明面不是沿对角线");
        assertEquals(2, zs.size(), "z 只应取格子的两个边界值");
        assertEquals(4f, xs.iterator().next(), 1e-5, "对角面必须落在 [4,5]（格角），不能内缩");
        assertEquals(5f, ((java.util.NavigableSet<Float>) xs).last(), 1e-5,
                "另一端必须落在格子的另一侧");
    }

    /**
     * 十字面必须<b>双向</b>可见 —— 每个对角面发正反两个四边形，且法线严格相反。
     *
     * <p><b>为什么用法线而不是"看某条边朝上还是朝下"：</b>
     * 边的方向依赖顶点编号约定，改一次编号就会假红；
     * 而"正反两面的法线互为相反数"是<b>与编号无关</b>的几何事实。
     *
     * <p><b>这条防的是最隐蔽的一类错误：</b>渲染器开着背面剔除，
     * 只发正面的话从另一侧看整株作物会消失。
     * 而"某个面消失"与"某个面被邻居剔除"在画面上<b>完全一样</b>，肉眼极难定位。
     */
    @Test
    void wheatEmitsAntipodalNormalPairsSoItIsVisibleFromBothSides() {
        MeshData data = meshOf(4, 64, 4, WHEAT_ID);
        List<int[]> normals = quadNormals(data.transparentVertices());

        assertEquals(CROSS_FACES, normals.size(), "前提：4 个四边形");

        // 逐个找它的"反向孪生"：法线分量全部相反
        int paired = 0;
        for (int[] a : normals) {
            for (int[] b : normals) {
                if (a != b && isAntipodal(a, b)) {
                    paired++;
                    break;
                }
            }
        }
        assertEquals(CROSS_FACES, paired,
                "每个四边形都必须有一个法线严格相反的孪生面（背面）；"
                        + "配对数不足说明作物单面化 —— 渲染器开着背面剔除，"
                        + "从另一侧看整株作物会消失，而画面上与'被邻居剔除'无法区分");

        // 十字面的法线是<b>对角</b>方向，不可能落在任何一个坐标轴上。
        // 若出现轴对齐法线，说明它退化成了满方块的面。
        for (int[] n : normals) {
            int nonzero = (n[0] != 0 ? 1 : 0) + (n[1] != 0 ? 1 : 0) + (n[2] != 0 ? 1 : 0);
            assertEquals(2, nonzero,
                    "法线 " + Arrays.toString(n) + " 只在" + nonzero + " 个轴上有分量 —— "
                            + "十字面的法线必须是对角方向，轴对齐说明退化成了满方块的面");
        }
    }

    /** 取每个四边形的前两条边，算出叉积法线（未归一化，够判方向与轴对齐）。 */
    private static List<int[]> quadNormals(float[] v) {
        List<int[]> result = new ArrayList<>();
        int stride = FPV * MeshData.VERTICES_PER_FACE;
        for (int base = 0; base < v.length; base += stride) {
            int[] e1 = {
                    Math.round(v[base + FPV] - v[base]),
                    Math.round(v[base + FPV + 1] - v[base + 1]),
                    Math.round(v[base + FPV + 2] - v[base + 2])};
            int[] e2 = {
                    Math.round(v[base + 2 * FPV] - v[base + FPV]),
                    Math.round(v[base + 2 * FPV + 1] - v[base + FPV + 1]),
                    Math.round(v[base + 2 * FPV + 2] - v[base + FPV + 2])};
            result.add(new int[]{
                    e1[1] * e2[2] - e1[2] * e2[1],
                    e1[2] * e2[0] - e1[0] * e2[2],
                    e1[0] * e2[1] - e1[1] * e2[0]});
        }
        return result;
    }

    private static boolean isAntipodal(int[] a, int[] b) {
        return a[0] == -b[0] && a[1] == -b[1] && a[2] == -b[2];
    }

    // ============================================================ 小麦：空碰撞体

    @Test
    void wheatHasNoCollisionBoxAtAll() {
        assertEquals(0, WHEAT.collisionBoxes().length,
                "小麦的碰撞体必须为空（PRD §3.2.2）—— 非空即意味着玩家撞上看不见的墙");
        assertFalse(WHEAT.shape().hasCollision());
        assertFalse(WHEAT.blocksMovement(), "collision=false + 形态无碰撞盒 → 绝不阻挡移动");
        assertEquals(0.0, WHEAT.shape().collisionTopY(), 1e-9,
                "无碰撞体时碰撞顶面必须是 0（不是 1）");
    }

    @Test
    void wheatCollisionBoxVolumeIsZero() {
        // 用体积这条独立口径复核"碰撞盒为空"：即使有人误配了一个退化盒，
        // 体积也必须是 0，否则仍会产生一个厚度为 0 的"面"把玩家卡住。
        double total = 0;
        for (BlockBox box : WHEAT.collisionBoxes()) {
            total += box.volume();
        }
        assertEquals(0.0, total, 1e-12, "小麦碰撞盒总体积必须为 0");
    }

    @Test
    void wheatDoesNotBlockAPlayerStandingInsideIt() {
        // 玩家身体正好占据 (4,64,4) 这一格，脚底 64.0身高 1.8
        boolean collides = WHEAT.intersects(
                4.3, 64.0, 4.3, 4.7, 65.8, 4.7,
                4, 64, 4);
        assertFalse(collides,
                "玩家必须能走进小麦格里（PRD §9.1 判据 5）—— 判为碰撞就是那堵看不见的墙");
    }

    /**
     * <b>反向验证靶点</b>：形态对玩法开关有<b>否决权</b>。
     *
     * <p>小麦本身 {@code collision=false}，所以"它不挡路"可能只是因为布尔字段是 false。
     * 本条用一个<b>刻意把 collision 配成 true</b> 的十字面方块来区分这两种可能：
     * 若 {@code blocksMovement()} 直接返回字段值，这条就会红。
     */
    @Test
    void crossShapeVetoesTheCollisionFlagEvenWhenItIsTrue() {
        assertTrue(CROSS_FLAGGED.hasCollision(),
                "前提：这个夹具的 collision 字段确实被配成了 true");
        assertFalse(CROSS_FLAGGED.blocksMovement(),
                "十字面形态必须否决 collision=true —— 否则'有人把作物误配成挡路'就会造出隐形墙");
        assertFalse(CROSS_FLAGGED.intersects(4.3, 64.0, 4.3, 4.7, 65.8, 4.7, 4, 64, 4));
        assertFalse(CROSS_FLAGGED.isStandable(),
                "不能站在作物上 —— 否则作物会变成隐形的半格台阶");
    }

    // ============================================================ 台阶：半高

    @Test
    void slabMeshTopFaceSitsAtHalfHeight() {
        MeshData data = meshOf(4, 64, 4, SLAB_ID);

        assertEquals(FULL_BLOCK_FACES, data.opaqueFaceCount(),
                "孤立的半高盒仍是 6 个面（底/顶/4 侧）—— 面数与满方块相同，"
                        + "区别在<b>顶面高度</b>，见下一条断言");
        assertEquals(0, data.transparentFaceCount(), "台阶是不透明的");

        float maxY = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float[] v = data.opaqueVertices();
        for (int i = 0; i < v.length; i += FPV) {
            maxY = Math.max(maxY, v[i + 1]);
            minY = Math.min(minY, v[i + 1]);
        }
        assertEquals(64f, minY, 1e-5, "半高盒底面仍在格底");
        assertEquals(64.5f, maxY, 1e-5,
                "顶面必须落在 y = 64.5 —— 出现 65.0 说明网格是'满方块缩小'（顶面留在原处），"
                        + "玩家会看到台阶上方浮着一层顶面，且站在上面被判为悬空");
    }

    @Test
    void slabCollisionBoxIsHalfHeight() {
        assertEquals(1, SLAB.collisionBoxes().length, "半高台阶恰好一个碰撞盒");

        BlockBox box = SLAB.collisionBoxes()[0];
        assertEquals(0.0, box.minY(), 1e-9, "碰撞盒占底部，故下界为 0");
        assertEquals(0.5, box.maxY(), 1e-9,
                "碰撞盒高度必须是半格（PRD §3.2.5「占位 1/2 格」）");
        assertEquals(0.5, box.volume(), 1e-9, "x/z 满格、y 半格 → 体积 0.5");
        assertEquals(0.5, SLAB.shape().collisionTopY(), 1e-9,
                "碰撞顶面是 by+0.5 —— 这是站立吸附高度的依据");
        assertTrue(SLAB.blocksMovement());
        assertTrue(SLAB.isStandable(), "台阶有顶面，可以站");
    }

    @Test
    void slabBlocksOnlyTheLowerHalfOfItsCell() {
        // 玩家身体完全落在下半格内 → 碰撞
        assertTrue(SLAB.intersects(4.3, 64.0, 4.3, 4.7, 64.4, 4.7, 4, 64, 4),
                "身体在台阶下半格内必须被挡住");

        // 玩家身体完全落在上半格（台阶上方的空气）→ 不碰撞
        assertFalse(SLAB.intersects(4.3, 64.6, 4.3, 4.7, 66.0, 4.7, 4, 64, 4),
                "台阶上方的空气不得阻挡玩家 —— 判为碰撞就是 PRD §7 R1 说的'隐形墙'");
    }

    @Test
    void slabTopIsNotAtFullCellHeight() {
        // 这是"半高"最直白的判据：与满方块的顶面差半格。
        assertEquals(BlockBox.FULL.maxY() - SLAB.collisionBoxes()[0].maxY(), 0.5, 1e-9,
                "台阶顶面必须比满方块低半格");
    }

    // ============================================================ 满方块路径未被破坏

/**
     * ★ 原有 17 种方块必须<b>仍然全是满方块</b>；只有 S5 新增的 2 种可以是异形。
     *
     * <p><b>这条断言的意图从未变</b>：防"顺手把既有方块改成异形"——
     * 那样会让一批按 6 面写死的断言集体变红，
     * 而变红的原因（数量变化）与真正的病因（形态被改）相隔很远。
     *
     * <p><b>2026-10-07 的形态化改动（不是放宽）</b>：
     * S5 登记小麦（{@link BlockShape#CROSS}）与台阶（{@link BlockShape#SLAB_BOTTOM}）后，
     * "注册表里所有方块都是满方块"这句话本身就不成立了。
     * 但<b>不能因此把断言删掉或改成 >= </b>——那正好会放过它本来要防的那件事。
     * 因此改为<b>显式白名单</b>：既有的 17 种（含空气）逐一断死为 FULL，
     * 白名单里的 2 种则断死为它们各自的形态（防止形态被悄悄改掉）。
     *
     * <p>白名单用 {@code stable ID} 字符串而不是 {@code runtimeId}：
     * 后者会随每次追加方块整体位移，改一次就得重钉一次。
     */
    @Test
    void everyPreS5BlockIsStillAFullCubeAndS5OnesAreNot() {
        java.util.Set<String> nonFullByS5 = java.util.Set.of(
                "skyisland:wheat",   // 十字交叉面
                "skyisland:slab");   // 半高台阶
        for (Block b : BlockRegistry.all()) {
            if (nonFullByS5.contains(b.id())) {
                continue;
            }
            assertSame(BlockShape.FULL, b.shape(),
                    "既有方块 " + b.id() + " 的形态被改动了 —— 本轮（登记属 S5）不应发生。"
                            + "若确实要改形态，请先重算按 6 面写死的那批断言，并更新本白名单。");
        }
        // 反向：白名单里的两种必须**仍然是**异形，防止"为了让它变绿"而改成满方块。
        assertSame(BlockShape.CROSS, BlockRegistry.wheat().shape(),
                "小麦必须是十字交叉面（PRD §3.2.2）。"
                        + "★ 把它改成 FULL 是本断言最可能的取巧方式 —— 那会让上面那条绿。");
        assertSame(BlockShape.SLAB_BOTTOM, BlockRegistry.slab().shape(),
                "台阶必须是半高（PRD §3.5 降级裁定）。"
                        + "★ 改成 FULL 会让玩家以为站在一整格高的方块上。");
    }

    @Test
    void fullBlockStillProducesSixFacesThroughTheSameEntryPoint() {
        // 用带解析器的重载跑一遍满方块：证明新重载没有改变默认行为。
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(4, 64, 4, TestWorlds.stone()));
        Chunk chunk = world.getOrLoadChunk(0, 0);

        MeshData withInjection = ChunkMesher.build(chunk, world, null, testLookup());
        MeshData withRegistry = ChunkMesher.build(chunk, world);

        assertEquals(FULL_BLOCK_FACES, withRegistry.opaqueFaceCount(), "前提：满方块仍是 6 面");
        assertEquals(withRegistry.totalFaceCount(), withInjection.totalFaceCount(),
                "注入解析器不得改变满方块的面数");
        // 逐顶点比对：注入解析器的路径必须产出与生产路径完全相同的顶点数据
        assertArrayEqualsExactly(withRegistry.opaqueVertices(), withInjection.opaqueVertices());
    }

    @Test
    void slabNextToFullBlockStillCullsSharedFace() {
        // 台阶与满方块相邻时，共享的那一面仍要被剔除 —— 剔除规则对异形方块一样生效。
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(4, 64, 4, SLAB_ID),
                TestWorlds.cell(5, 64, 4, TestWorlds.stone()));
        Chunk chunk = world.getOrLoadChunk(0, 0);

        MeshData data = ChunkMesher.build(chunk, world, null, testLookup());

        // 两块相邻 → 6 + 6 - 2（共享的那对内部面被剔除）= 10 面
        assertEquals(10, data.opaqueFaceCount(),
                "两块相邻 → 6 + 6 - 2 = 10 面；12 说明共享面没被剔除，"
                        + "异形方块的剔除规则与满方块必须是同一套");
    }

    @Test
    void emptyChunkStillReturnsSharedEmptyMeshViaInjectedLookup() {
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0);
        Chunk chunk = world.getOrLoadChunk(0, 0);

        MeshData data = ChunkMesher.build(chunk, world, null, testLookup());

        assertSame(MeshData.EMPTY, data, "空区块必须早退 —— 新重载不得绕过这道优化");
        assertTrue(data.isEmpty());
    }

    // ============================================================ 形态不变式

    @Test
    void collisionBoxesAlwaysStayInsideTheUnitCell() {
        // 越界的碰撞盒会污染相邻格的判定（例如伸到隔壁格里的半高盒
        // 会让紧挨着的满方块莫名判定为相交）
        for (BlockShape shape : BlockShape.values()) {
            for (BlockBox box : shape.collisionBoxes()) {
                assertTrue(box.minX() >= 0 && box.maxX() <= 1
                                && box.minY() >= 0 && box.maxY() <= 1
                                && box.minZ() >= 0 && box.maxZ() <= 1,
                        "形态 " + shape + " 的碰撞盒越出了 [0,1]³：" + box);
            }
        }
    }

    @Test
    void shapesWithNoCollisionBoxMustNotBeFullCubes() {
        // 不变式（BlockShape 的 javadoc 里写明）：碰撞盒为空 ⇒ 网格不是满方块。
        // 反过来若某个形态既是空碰撞又是满方块，就正好是"看不见的墙"。
        for (BlockShape shape : BlockShape.values()) {
            if (!shape.hasCollision()) {
                assertFalse(shape.isFull(),
                        "形态 " + shape + " 既无碰撞盒又是满方块 —— 那就是 PRD §7 R1 的隐形墙");
                assertFalse(shape.intersectsFullCell());
            }
        }
    }

    @Test
    void shapeCatalogueCoversTheTwoFormsThisRoundNeeds() {
        // 钉住"能力已就位"：S5 登记 wheat/slab 时依赖这两个常量存在。
        assertNotNull(BlockShape.CROSS, "十字面形态必须存在（小麦用）");
        assertNotNull(BlockShape.SLAB_BOTTOM, "半高台阶形态必须存在（台阶用）");
        assertTrue(BlockShape.CROSS.isCross());
        assertFalse(BlockShape.SLAB_BOTTOM.isCross(),
                "半高盒必须走 6 面循环（它就是个矮盒子），不能走十字面路径");
        assertFalse(BlockShape.SLAB_BOTTOM.isFull(),
                "半高盒不是满方块 —— 否则网格顶面会留在 y=1");
    }

    // ============================================================ 小工具

    private static void assertArrayEqualsExactly(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length, "顶点数必须一致");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 0.0f,
                    "第 " + i + " 个 float 不一致 —— 注入解析器改变了顶点数据");
        }
    }

    /** 保留：提醒新增断言时优先用精确面数而不是"至少N 个"。 */
    @Test
    void faceCountsAreExactNotLowerBounds() {
        List<Integer> counts = new ArrayList<>();
        counts.add(CROSS_FACES);
        counts.add(FULL_BLOCK_FACES);
        assertEquals(List.of(4, 6), counts,
                "本测试类的面数判据是精确值 —— 若改成 '>= N'，退化实现会假绿");
    }
}
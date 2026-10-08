package com.skyisland.render.mesh;

import com.skyisland.world.block.Block;

/**
 * 异形方块的网格发射（异形网格支持，PRD §3.2.2 / §7 R1）。
 *
 * <p><b>为什么单独成类：</b>{@link ChunkMesher} 的主循环是"满方块逐面剔除"，
 * 它<b>不能</b>被塞进十字面与半高盒 —— 后两者的发射规则与逐面剔除是正交的：
 * <ul>
 *   <li>十字面<b>不参与</b>逐面剔除（它的面朝向由对角线决定，不是 6 个轴向）；</li>
 *   <li>半高盒<b>参与</b>剔除，但剔除后仍需重建"缩到 0.5 高度"的角点
 *       —— 直接复用满方块角点会得到一堵 1 格高的墙（"隐形墙"的另一种形态）。</li>
 * </ul>
 * 硬塞进主循环会让那个循环出现"是不是十字面？要不要缩高？"两类分支，
 * 而它本来应该是干净的 6 次循环。
 *
 * <p><b>为什么本类不做剔除：</b>剔除需要邻居方块，那是 {@code ChunkMesher} 的上下文。
 * 本类只负责"给定一个形态，把顶点写进缓冲"，因此可以<b>纯 CPU 单测</b>
 * （不需要区块、不需要世界、不需要光照）。
 */
final class NonFullMesh {

    /** 十字面单侧的面数：一个对角竖直面 = 1 个四边形。 */
    private NonFullMesh() {
    }

    /**
     * 发射十字交叉面（作物）。
     *
     * <p><b>几何</b>：两个对角竖直面，每个面<b>双向</b>发射（正面 + 背面），
     * 因此单株作物产出 <b>4 个四边形</b>（PRD §3.2.2 要求的十字交叉面）。
     *
     * <p><b>为什么必须双向：</b>渲染器开着背面剔除（{@code GL_CULL_FACE}）。
     * 只发正面的话，从另一侧看这株作物会整个消失 ——
     * 而"某个面消失"与"某个面被邻居剔除"在画面上<b>完全一样</b>，
     * 这是网格化里最难排查的一类错误。代价只是 2 倍面数（4 而非 2），
     * 换来的是"任何角度看都在"。
     *
     * <p><b>为什么竖直贯穿整格（y ∈ [0,1]）而不是半高：</b>
     * 作物是"长在土里的一株"，视觉上从地面往上长满一格；
     * 缩到半高会让它看起来像被地面切掉一半。
     *
     * <p><b>为什么对角线端点取格角而非内缩：</b>
     * 与《我的世界》的十字模型一致 —— 两株相邻作物会精确对齐、交叠成一片，
     * 内缩反而会在格子之间留下可见的缝。
     */
    static void emitCross(MeshSink sink, int lx, int ly, int lz,
                          Block block, float shade, float packedLight, float layer) {
        // 对角面 A：从格角 (0,0) 斜贯到对角 (1,1)
        emitDoubleSided(sink, lx, ly, lz, block, shade, packedLight, layer, 0f, 0f, 1f, 1f);
        // 对角面 B：从格角 (1,0) 斜贯到对角 (0,1)
        emitDoubleSided(sink, lx, ly, lz, block, shade, packedLight, layer, 1f, 0f, 0f, 1f);
    }

    /**
     * 发射一个竖直对角面（4 顶点 + 6 索引），<b>并立即发它的背面</b>。
     *
     * @param x0z0 面的一端（局部 x, z）
     * @param x1z1 面的另一端（局部 x, z）
     */
    private static void emitDoubleSided(MeshSink sink, int lx, int ly, int lz,
                                        Block block, float shade, float packedLight, float layer,
                                        float x0, float z0, float x1, float z1) {
        // 正面：从 +Z/+X 侧看逆时针（与 BlockFace 的环绕约定一致）
        sink.pushQuad(lx + x0, ly, lz + z0,
                lx + x1, ly, lz + z1,
                lx + x1, ly + 1, lz + z1,
                lx + x0, ly + 1, lz + z0,
                block, shade, packedLight, layer);
        // 背面：绕序反转，法线朝另一侧（否则从这一侧看整个作物消失）
        sink.pushQuad(lx + x0, ly, lz + z0,
                lx + x0, ly + 1, lz + z0,
                lx + x1, ly + 1, lz + z1,
                lx + x1, ly, lz + z1,
                block, shade, packedLight, layer);
    }

    /**
     * 半高盒的面（占底部 1/2 格）。
     *
     * <p><b>关键：角点必须重建，不能缩放满方块角点。</b>
     * 缩放会得到"顶面留在 y=1、底面在 y=0"的错位盒（体积 0.5 但位置错了），
     * 玩家站在上面会被判为悬空。这里显式写出每个面的 4 个角。
     */
    static final class SlabFace {

        private SlabFace() {
        }

        /** 顶面（y = 0.5）：站在台阶上看到的那一面。 */
        static final float[] TOP = {0, 0.5f, 0, 1, 0.5f, 0, 1, 0.5f, 1, 0, 0.5f, 1};

        /** 底面（y = 0）。 */
        static final float[] BOTTOM = {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1};

        /** 侧面（-X）：4 角 = (0,0,0) (0,0,1) (0,0.5,1) (0,0.5,0)。 */
        static final float[] NEG_X = {0, 0, 0, 0, 0, 1, 0, 0.5f, 1, 0, 0.5f, 0};

        /** 侧面（+X）。 */
        static final float[] POS_X = {1, 0, 0, 1, 0, 1, 1, 0.5f, 1, 1, 0.5f, 0};

        /** 侧面（-Z）。 */
        static final float[] NEG_Z = {0, 0, 0, 1, 0, 0, 1, 0.5f, 0, 0, 0.5f, 0};

        /** 侧面（+Z）。 */
        static final float[] POS_Z = {0, 0, 1, 1, 0, 1, 1, 0.5f, 1, 0, 0.5f, 1};

        /** 侧面高度（半高）。 */
        static final float HEIGHT = 0.5f;

        /**
         * 按 {@link BlockFace} 取该面的角点（12 个浮点数 = 4 角 × xyz）。
         *
         * <p>方向 → 面的映射必须与 {@link BlockFace} 的角点环绕方向一致，
         * 否则台阶的某个侧面会朝向反了（从外面看是空的，从里面看是实的）。
         * 这是本类最容易写错的地方，因此角点<b>显式写死</b>而不是用公式推导。
         */
        static float[] cornersFor(BlockFace face) {
            return switch (face) {
                case POS_Y -> TOP;
                case NEG_Y -> BOTTOM;
                case NEG_X -> NEG_X;
                case POS_X -> POS_X;
                case NEG_Z -> NEG_Z;
                case POS_Z -> POS_Z;
            };
        }
    }

    /** 顶点写入门面（由 {@code ChunkMesher.MeshBuilder} 实现）。 */
    interface MeshSink {

        /**
         * 压入一个四边形（4 顶点 + 6 索引，索引基准由实现方自行处理）。
         *
         * @param shade 面明暗（含光照因子）
         * @param layer 纹理数组层号（实现方负责连同 UV 一起写入）
         */
        void pushQuad(float x0, float y0, float z0,
                      float x1, float y1, float z1,
                      float x2, float y2, float z2,
                      float x3, float y3, float z3,
                      Block block, float shade, float packedLight, float layer);
    }
}
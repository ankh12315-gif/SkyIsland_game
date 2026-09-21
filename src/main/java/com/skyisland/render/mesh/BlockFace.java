package com.skyisland.render.mesh;

import com.skyisland.world.block.RenderType;

/**
 * 立方体的六个面（TECH_DESIGN §F.6 / §G.4）。
 *
 * <p><b>本类只放"几何常量 + 明暗"，不放任何渲染代码。</b>
 * 这样它可以被纯 CPU 的单元测试直接检查（顶点环绕方向与法线是否一致、
 * 四个角是否恰好构成一个单位正方形），而不必起 GL 上下文 ——
 * 环绕方向写反是网格化里最难靠肉眼发现的错误（表现为整个面消失，
 * 而"面消失"与"面被剔除"的现象完全一样）。
 *
 * <p><b>顶点顺序约定（关键）：</b>每个面的 4 个顶点按<b>从外侧看逆时针</b>（CCW）排列，
 * 三角化固定为 {@code (0,1,2) + (0,2,3)}。配合 {@code glFrontFace(GL_CCW)} 与
 * {@code glCullFace(GL_BACK)}，朝向相机的外表面保留、背向的内表面被丢弃。
 * 每个面的角点都由"屏幕右方向 (u) × 屏幕上方向 (v)"推导，而不是凭感觉手写 ——
 * 手写六个面几乎一定会有一个面是顺时针。
 *
 * <p><b>明暗值的作用：</b>顶点色是占位美术，如果所有面一样亮，立方体会变成一团色块，
 * 完全看不出体素结构。固定的面明暗（顶面最亮、底面最暗）是最小成本的结构提示，
 * 也是后续接入真实光照时的乘性系数基底。
 */
public enum BlockFace {

    /** 朝 −X 的面（西）。 */
    NEG_X(-1, 0, 0, 0.65f,
            new float[]{0, 0, 0}, new float[]{0, 0, 1}, new float[]{0, 1, 1}, new float[]{0, 1, 0}),

    /** 朝 +X 的面（东）。 */
    POS_X(1, 0, 0, 0.65f,
            new float[]{1, 0, 1}, new float[]{1, 0, 0}, new float[]{1, 1, 0}, new float[]{1, 1, 1}),

    /** 朝 −Y 的面（下）。 */
    NEG_Y(0, -1, 0, 0.50f,
            new float[]{0, 0, 0}, new float[]{1, 0, 0}, new float[]{1, 0, 1}, new float[]{0, 0, 1}),

    /** 朝 +Y 的面（上）。 */
    POS_Y(0, 1, 0, 1.00f,
            new float[]{0, 1, 1}, new float[]{1, 1, 1}, new float[]{1, 1, 0}, new float[]{0, 1, 0}),

    /** 朝 −Z 的面（北）。 */
    NEG_Z(0, 0, -1, 0.85f,
            new float[]{1, 0, 0}, new float[]{0, 0, 0}, new float[]{0, 1, 0}, new float[]{1, 1, 0}),

    /** 朝 +Z 的面（南）。 */
    POS_Z(0, 0, 1, 0.85f,
            new float[]{0, 0, 1}, new float[]{1, 0, 1}, new float[]{1, 1, 1}, new float[]{0, 1, 1});

    /** 面法线的三个分量，取值只能是 −1 / 0 / 1。 */
    private final int dx;
    private final int dy;
    private final int dz;

    /** 面明暗（占位美术的固定系数）。 */
    private final float shade;

    /** 4 个角点，每个是 {x, y, z} 偏移，取值 0 或 1。 */
    private final float[][] corners;

    BlockFace(int dx, int dy, int dz, float shade, float[]... corners) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.shade = shade;
        this.corners = corners;
    }

    public int dx() {
        return dx;
    }

    public int dy() {
        return dy;
    }

    public int dz() {
        return dz;
    }

    /** 面明暗系数，[0,1]。 */
    public float shade() {
        return shade;
    }

    /** 第 {@code i} 个角点的偏移分量（{@code axis}：0=x, 1=y, 2=z）。 */
    public float corner(int index, int axis) {
        return corners[index][axis];
    }

    /** 顶点数（恒为 4）。 */
    public int cornerCount() {
        return corners.length;
    }

    /** 两个三角形、6 个索引的固定模式：{@code 0,1,2, 0,2,3}。 */
    public static final int[] QUAD_INDICES = {0, 1, 2, 0, 2, 3};

    /** 该方块面是否应参与渲染（{@link RenderType#INVISIBLE} 不生成任何面）。 */
    public static boolean faceRenderable(RenderType type) {
        return type != RenderType.INVISIBLE;
    }
}

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

    /**
     * 该面第 {@code corner} 个角在贴图内的 UV（M4-S3）。
     *
     * <p><b>UV 规则（PRD §6.1）：每面取该层内的满幅区域（0..1），不跨层取样。</b>
     * 因此每个面的 4 个角恰好落在贴图的 4 个角上，
     * 具体哪个 3D 角对应贴图哪个角，由各面的朝向决定。
     *
     * <p><b>为什么由 CPU 显式给出而不在片元里推导</b>：
     * 片元只能看到插值后的坐标，而方块<b>角落处三条轴都取整数值</b>，
     * 无法判定哪一条是常量轴 —— 强行推导会在每个方块边缘留下 1 texel 宽的接缝，
     * 而症状只是"画面略有差异"，几乎不可能归因。
     * 顶点里带上UV（{@code aUv}）把这件事变成确定的。
     *
     * <p><b>UV 的取向约定</b>：{@code v = 0} 是贴图的<b>上</b>边（美术规格 §2
     * 规定"v=0 是贴图顶边"）。因此顶面/底面用 x/z，四个侧面用"水平轴 + y"，
     * 且侧面的 v 随 y<b>递减</b>，使贴图上边始终朝上
     * ——否则草方块的草边会挂在底面。
     *
     * @param corner 0..3，与 {@link #corner(int, int)} 的编号一致
     * @return 长度2 的数组 {@code {u, v}}
     */
    public float[] cornerUv(int corner) {
        // 各面的 u/v 取自哪两个局部坐标分量（0=x, 1=y, 2=z）。
        // u 轴取"水平且非恒定"的那个；v 轴顶/底面取 z，侧面取 y。
        int uAxis;
        int vAxis;
        boolean flipV;
        switch (this) {
            case NEG_X -> { uAxis = 2; vAxis = 1; flipV = true; }
            case POS_X -> { uAxis = 2; vAxis = 1; flipV = true; }
            case NEG_Z -> { uAxis = 0; vAxis = 1; flipV = true; }
            case POS_Z -> { uAxis = 0; vAxis = 1; flipV = true; }
            case POS_Y -> { uAxis = 0; vAxis = 2; flipV = false; }
            case NEG_Y -> { uAxis = 0; vAxis = 2; flipV = true; }
            default -> throw new IllegalStateException("未处理的面: " + this);
        }
        float u = corner(corner, uAxis);
        float v = corner(corner, vAxis);
        if (flipV) {
            v = 1f - v;
        }
        return new float[]{u, v};
    }

    /** 两个三角形、6 个索引的固定模式：{@code 0,1,2, 0,2,3}。 */
    public static final int[] QUAD_INDICES = {0, 1, 2, 0, 2, 3};

    /** 该方块面是否应参与渲染（{@link RenderType#INVISIBLE} 不生成任何面）。 */
    public static boolean faceRenderable(RenderType type) {
        return type != RenderType.INVISIBLE;
    }
}

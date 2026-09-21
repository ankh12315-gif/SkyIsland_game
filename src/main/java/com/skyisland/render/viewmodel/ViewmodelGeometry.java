package com.skyisland.render.viewmodel;

import com.skyisland.render.geom.Boxes;

/**
 * 第一人称手持物的<b>纯几何</b>：把 {@link ViewmodelKind} + {@link ViewmodelPose} 写成顶点。
 *
 * <h2>它是 viewmodel，不是世界实体</h2>
 * 顶点直接写在<b>视图空间</b>（相机在原点、朝 −Z、+X 右、+Y 上），
 * 渲染时 {@code uView = 单位矩阵}、{@code uChunkOffset = (0,0)}。
 * 因此这里的坐标就是"离眼睛多少米"，与游戏世界没有任何关系 ——
 * 手持物不参与射线、不参与命中、不写世界深度之外的任何东西。
 *
 * <h2>为什么用透视而不是正交</h2>
 * 正交下手持物在任何 FOV 下都是同一个大小，ADS 时也不会"往里收"，
 * 而"举枪瞄准时它离眼睛更近、看起来更大"正是玩家对枪械的空间直觉。
 * 这里用一条<b>独立的窄 FOV 透视</b>（{@value #FOV_DEG}°）：
 * 比世界 FOV（70°）窄，于是手持物不会被拉出夸张的透视畸变，
 * 同时又能随屏幕宽高比正确摆放。
 *
 * <h2>屏幕布局：右下，且绝不碰准星</h2>
 * 锚点常量（{@link #HIP_X} 等）换来的是这两条硬约束，由 {@code ViewmodelRendererTest}
 * 在动画包络上扫一遍断言（不是靠肉眼调）：
 * <ol>
 *   <li>所有顶点恒在<b>右半屏</b>（NDC x > 0）；</li>
 *   <li>没有顶点落进<b>准星禁区</b>（|NDC x| 与 |NDC y| 同时小于 0.06 的中心方块）。</li>
 * </ol>
 * ADS 时锚点向屏幕中心收（"稍前收、更贴近中心"），但仍然保持在中心<b>下方</b>，
 * 于是准星那个点始终露着 —— 准星的全部意义就是"指出中心那一个点"，
 * 被自己的枪盖住等于瞄准了个寂寞。
 */
public final class ViewmodelGeometry {

    /** 手持物专用投影的垂直 FOV（度）。比世界 FOV 窄，透视更平。 */
    public static final double FOV_DEG = 50.0;

    /** 盒体容量：枪 6 个（含枪口闪光）、方块 2 个、空手 2 个，取 8 留余量。 */
    public static final int MAX_BOXES = 8;

    // ---- 锚点（视图空间，米）----
    /** 髋射（常态）：屏幕右下。 */
    public static final double HIP_X = 0.235;
    public static final double HIP_Y = -0.135;
    public static final double HIP_Z = -0.75;

    /**
     * 瞄准（ADS）：向屏幕中心收，并略远一点（于是更小、更不挡视线）。
     *
     * <p>x 只能收到 0.13 而不能一直收到 0：空手的前伸手指与枪管都沿 −Z 伸出
     * 十几厘米，绕 Y 轴的基础朝向会把这段长度换算成 −X 方向的位移；
     * 锚点太靠中心时，最左的顶点会越过屏幕中线并落进准星禁区。
     * 0.13 是"够靠中心"与"绝不压到准星"之间的实测平衡点。
     */
    public static final double ADS_X = 0.13;
    public static final double ADS_Y = -0.095;
    public static final double ADS_Z = -0.80;

    /** 基础朝向：枪管朝屏幕中心方向略微内收。 */
    public static final double BASE_YAW = 0.30;
    public static final double BASE_PITCH = -0.05;
    public static final double BASE_ROLL = -0.10;

    // ---- 配色索引 ----
    private static final int P_GUN_BODY = 0;
    private static final int P_GUN_BARREL = 1;
    private static final int P_GUN_GRIP = 2;
    private static final int P_GUN_SIGHT = 3;
    private static final int P_HAND = 4;
    private static final int P_HAND_DARK = 5;
    private static final int P_FLASH = 6;
    private static final int P_ITEM = 7;      // 取 ViewmodelModel 传进来的颜色

    private static final float[][] PALETTE = {
            {0.26f, 0.28f, 0.32f},   // 枪身：冷灰
            {0.19f, 0.20f, 0.23f},   // 套筒 / 枪管：更暗，读出"金属纵向延伸"
            {0.13f, 0.13f, 0.15f},   // 握把：近黑
            {0.42f, 0.44f, 0.48f},   // 准星：亮一点的小点
            {0.62f, 0.44f, 0.33f},   // 手
            {0.52f, 0.37f, 0.28f},   // 手的暗部
            {1.00f, 0.88f, 0.48f},   // 枪口闪光
            {1.00f, 1.00f, 1.00f},   // 占位（实际取模型色）
    };

    /**
     * 每个 part 七个数：minX, minY, minZ, maxX, maxY, maxZ, 配色索引。
     * 局部坐标以锚点为原点，−Z 是"枪口方向"。
     */
    private static final double[] GUN_PARTS = {
            // 套筒 / 机匣
            -0.030, -0.005, -0.125, 0.030, 0.045, 0.045, P_GUN_BODY,
            // 枪管：比机匣细、更长，是"这是一把枪"的第一识别特征
            -0.020, 0.010, -0.215, 0.020, 0.042, -0.110, P_GUN_BARREL,
            // 握把：斜向下后方
            -0.026, -0.115, -0.005, 0.026, 0.000, 0.070, P_GUN_GRIP,
            // 准星：枪管上方一个小凸起
            -0.007, 0.042, -0.200, 0.007, 0.056, -0.182, P_GUN_SIGHT,
            // 握枪的手
            -0.040, -0.130, 0.030, 0.040, -0.040, 0.125, P_HAND,
            // 枪口闪光：只在开火后的一瞬间画（见 write）
            -0.038, -0.010, -0.265, 0.038, 0.046, -0.215, P_FLASH,
    };

    private static final double[] BLOCK_PARTS = {
            // 方块本体：一个 0.115 的立方体
            -0.0575, -0.0325, -0.0775, 0.0575, 0.0825, 0.0375, P_ITEM,
            // 托着它的手
            -0.045, -0.125, -0.010, 0.045, -0.030, 0.090, P_HAND_DARK,
    };

    private static final double[] EMPTY_PARTS = {
            // 手掌
            -0.052, -0.135, -0.020, 0.052, -0.058, 0.085, P_HAND,
            // 手指：比手掌略暗、向前伸出一点
            -0.052, -0.075, -0.085, 0.052, -0.012, -0.005, P_HAND_DARK,
    };

    /** 每个 part 占的 double 数。 */
    public static final int DOUBLES_PER_PART = 7;

    private ViewmodelGeometry() {
    }

    /** 该形态在"不开火"时的 part 数（枪口闪光不计入）。 */
    public static int partCount(ViewmodelKind kind) {
        return switch (kind) {
            case GUN -> GUN_PARTS.length / DOUBLES_PER_PART - 1;
            case BLOCK -> BLOCK_PARTS.length / DOUBLES_PER_PART;
            case EMPTY -> EMPTY_PARTS.length / DOUBLES_PER_PART;
        };
    }

    /**
     * 写顶点。
     *
     * @return 写入的 float 个数（0 表示这一帧不画 —— 例如空手且开关关着）
     */
    public static int write(float[] out, int start, ViewmodelModel model, ViewmodelPose pose) {
        double[] parts = switch (model.kind) {
            case GUN -> GUN_PARTS;
            case BLOCK -> BLOCK_PARTS;
            case EMPTY -> EMPTY_PARTS;
        };

        double s = pose.scale();
        double px = pose.anchorX();
        double py = pose.anchorY();
        double pz = pose.anchorZ() + pose.recoilPush();
        double yaw = pose.yawRad();
        double pitch = pose.pitchRad();
        double roll = pose.rollRad();
        double flash = pose.recoil01();

        int p = start;
        for (int i = 0; i < parts.length; i += DOUBLES_PER_PART) {
            int palette = (int) parts[i + 6];
            boolean muzzleFlash = palette == P_FLASH;
            if (muzzleFlash && flash < 0.05) {
                // 枪口闪光不画的时候是"完全不存在的几何"，而不是"一块黑的"：
                // voxel.frag 里 aColor.a 是亮度而不是不透明度，
                // 画一个亮度 0 的盒子会得到一块纯黑的方块糊在枪口上。
                continue;
            }
            float[] c = palette == P_ITEM
                    ? new float[]{model.colorR, model.colorG, model.colorB}
                    : PALETTE[palette];
            float brightness = muzzleFlash ? (float) Math.min(1.0, flash * 1.3) : 1.0f;

            p = Boxes.write(out, p,
                    parts[i] * s, parts[i + 1] * s, parts[i + 2] * s,
                    parts[i + 3] * s, parts[i + 4] * s, parts[i + 5] * s,
                    px, py, pz,
                    yaw, pitch, roll,
                    c[0], c[1], c[2], brightness);
        }
        return p;
    }

    /**
     * 把视图空间的一点投到 NDC（与 {@link #FOV_DEG} 的透视一致），供单测核对屏幕布局。
     *
     * @param z 必须为负（相机朝 −Z）
     * @param out2 长度 ≥ 2 的 {@code float[]}，写入 {@code [ndcX, ndcY]}
     */
    public static void toNdc(double x, double y, double z, double aspect, float[] out2) {
        double depth = -z;
        double tanHalf = Math.tan(Math.toRadians(FOV_DEG) * 0.5);
        out2[0] = (float) ((x / depth) / (tanHalf * aspect));
        out2[1] = (float) ((y / depth) / tanHalf);
    }
}

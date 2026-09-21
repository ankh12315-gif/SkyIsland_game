package com.skyisland.render.entity;

/**
 * 近战怪的 <b>parts 模型</b>：把"一个深红盒体"拆成头 / 躯干 / 双腿 / 双臂 / 双眼。
 *
 * <h2>要解决的三个"一眼"</h2>
 * M2 的怪物是一个 0.6×1.8×0.6 的深红盒体，它只回答了"这里有个东西"，
 * 答不了下面三件事，而这三件事正是 M2.1「Combat Feel & Readability」的验收点：
 * <ol>
 *   <li><b>一眼看出是怪物而不是箱子</b>——靠"头 + 躯干 + 双腿"的类人剪影。
 *       箱子是一个凸体，类人剪影在头/躯干/腿之间有三个明显的宽窄变化；</li>
 *   <li><b>一眼看出正面</b>——靠<b>只长在正面的一对发光眼睛</b>。
 *       背面什么都不加，于是"看得到光点 = 它正对着你"，这正是玩家需要的判断
 *       （被从背后接近和面对面对峙的应对完全不同）；</li>
 *   <li><b>一眼看出头/身体/腿的结构</b>——三段各自独立的尺寸与配色。</li>
 * </ol>
 *
 * <h2>最硬的一条不变式：视觉 ⊆ 碰撞箱</h2>
 * 本模型<b>不改</b> {@code MeleeMonster} 的 {@code halfWidth = 0.3 / height = 1.8}，
 * 逻辑碰撞箱一个字节都不动。渲染出来的 parts 并集必须<b>恒在碰撞箱之内</b>：
 * <pre>
 *   x, z ∈ [-0.30, +0.30]      y ∈ [-BOB, 1.80]
 * </pre>
 * 方向是刻意选的：宁可"视觉略小于碰撞箱"，也<b>绝不可</b>"视觉大于碰撞箱"。
 * 后者会让玩家打在"看起来明明没打到"的空气上，而这是射击手感里最致命的一类不一致
 * （原 {@code EntityRenderer} 的类注释把这条写成了"宁可丑，不可不一致"，本模型沿用）。
 *
 * 因此本模型的两条动作规则也是围绕这条不变式设计的：
 * <ul>
 *   <li><b>行走起伏只向下不向上</b>：{@code bob ∈ [-BOB, 0]}。
 *       头顶点因此<b>永不超过 1.80</b>；代价是双脚最低会陷入地面 3 厘米 ——
 *       在 1.8 米高的怪物身上完全看不出来，而换来的是"头永远不会戳出碰撞箱"；</li>
 *   <li><b>攻击前冲在脚印之内做</b>：手臂前摆 0.12、上身前移 0.05，
 *       最前端只到 z = −0.27，仍在 ±0.30 之内。不做"整个身体向前平移"那种前冲，
 *       那会把模型推出碰撞箱。</li>
 * </ul>
 * 这两条都由 {@code MonsterModelTest} 在动画包络上扫一遍断言，不靠肉眼。
 *
 * <h2>局部坐标系</h2>
 * 原点 = 实体<b>脚底中心</b>（与 {@code Entity} 的位置语义一致），
 * +Y 向上，模型面朝 <b>−Z</b>，yaw 口径与 {@link com.skyisland.player.Camera} 相同
 * （yaw = 0 朝 −Z，增大向左转）。旋转由 {@code Boxes.write} 负责，本类只管局部 AABB。
 */
public final class MonsterModel {

    // ---- part 索引 ----
    public static final int PART_HEAD = 0;
    public static final int PART_TORSO = 1;
    public static final int PART_LEG_LEFT = 2;
    public static final int PART_LEG_RIGHT = 3;
    public static final int PART_ARM_LEFT = 4;
    public static final int PART_ARM_RIGHT = 5;
    public static final int PART_EYE_LEFT = 6;
    public static final int PART_EYE_RIGHT = 7;

    /** parts 数量：头 / 躯干 / 双腿 / 双臂 / 双眼。 */
    public static final int PART_COUNT = 8;

    /** 一个 part 写进暂存数组的 float 数（minXYZ + maxXYZ）。 */
    public static final int FLOATS_PER_PART = 6;

    /** 与碰撞箱一致的外廓：宽 0.6、高 1.8。改这两个值前先改 MeleeMonster。 */
    public static final double HALF_WIDTH = 0.30;
    public static final double HEIGHT = 1.80;

    /** 行走起伏幅度（格）。只向下，理由见类注释。 */
    public static final double BOB_AMPLITUDE = 0.03;

    /** 双腿前后摆动幅度（格）。 */
    public static final double LEG_SWING = 0.10;

    /** 攻击时手臂前摆幅度（格）。 */
    public static final double ARM_SWING = 0.08;

    /** 攻击时上身前移幅度（格）。 */
    public static final double LUNGE = 0.03;

    /**
     * 水平方向允许的旋转外扩比例（相对 {@link #HALF_WIDTH}）。
     *
     * <p><b>为什么水平方向要留容差而垂直方向不留：</b>
     * 碰撞箱是<b>轴对齐</b>的，而模型会绕 Y 轴转。一个撑满 ±0.30 的部件
     * （例如手臂的角点 (0.30, 0.07)）转 45° 之后到中心的距离是
     * √(0.30² + 0.07²) ≈ 0.308 —— 也就是说，只要模型用满了脚印，
     * 旋转就<b>必然</b>在四个角上鼓出去一点点，这与"做错"无关，是方盒套方盒的几何必然。
     *
     * <p>因此水平方向给 1.2（±0.36）的容差，并且把摆臂幅度压到"鼓包 ≤ 0.35"
     * （实测最坏 0.3499，见 {@code MonsterModelTest}）。
     * 垂直方向<b>一个都不给</b>：头顶点超过 1.80 就是"能打到看起来没东西的位置"，
     * 那条是红线，而且它不受旋转影响（绕 Y 轴转不改变 y）。
     */
    public static final double HORIZONTAL_ROTATION_TOLERANCE = 1.20;

    // ---- 静止姿态的局部 AABB（顺序与 PART_* 一致）----
    //   x      : 左右（含双臂，撑满 ±0.30）
    //   y      : 绝对高度（0 = 脚底）
    //   z      : 前后（负 = 正面方向）
    private static final double[] PARTS = {
            // head：比躯干窄，与躯干留 0.04 的"脖子"缝，读起来才是一个独立的头
            -0.18, 1.36, -0.18, 0.18, 1.80, 0.18,
            // torso
            -0.22, 0.66, -0.17, 0.22, 1.32, 0.17,
            // legs
            -0.20, 0.00, -0.12, -0.04, 0.72, 0.12,
            0.04, 0.00, -0.12, 0.20, 0.72, 0.12,
            // arms（"前肢"）：撑到 ±0.30，把碰撞箱宽度用满。
            // z 只有 ±0.07（比腿细）是刻意的：手臂前摆时角点 (0.30, z) 到中心的距离
            // 必须仍在旋转容差之内，否则转 45° 就会鼓出碰撞箱（见 HORIZONTAL_ROTATION_TOLERANCE）
            -0.30, 0.72, -0.07, -0.18, 1.30, 0.07,
            0.18, 0.72, -0.07, 0.30, 1.30, 0.07,
            // eyes：只长在正面（z 为负），并从前脸（z = −0.18）凸出 0.03
            -0.13, 1.56, -0.21, -0.05, 1.68, -0.175,
            0.05, 1.56, -0.21, 0.13, 1.68, -0.175,
    };

    // ---- 配色：主色深红/暗红，辅色近黑，眼睛亮琥珀 ----
    private static final float[] COLOR_HEAD = {0.46f, 0.17f, 0.20f};
    private static final float[] COLOR_TORSO = {0.33f, 0.13f, 0.16f};
    private static final float[] COLOR_LEG = {0.19f, 0.10f, 0.12f};
    private static final float[] COLOR_ARM = {0.25f, 0.11f, 0.14f};
    private static final float[] COLOR_EYE = {1.00f, 0.84f, 0.30f};

    private MonsterModel() {
    }

    /**
     * 把 8 个 part 的局部 AABB 写进 {@code out} 的 {@code start} 位置，返回新的写入位置。
     *
     * <p><b>纯函数、零分配</b>：不碰 GL、不读全局状态、不 new 任何对象，
     * 因此"parts 的相对位置"与"并集是否还在碰撞箱里"可以被单测完整举证。
     *
     * @param walkPhaseRad 步态相位（弧度）。由实体的累计行走距离换算而来，
     *                     因此<b>站住不动时相位自然停住</b>，不需要额外的状态
     * @param attackSwing01 攻击摆动强度 0..1（0 = 没在攻击）
     */
    public static int write(float[] out, int start, double walkPhaseRad, double attackSwing01) {
        double swing = Math.sin(walkPhaseRad);
        // bob ∈ [-BOB, 0]：只会向下，头顶点因此永不超过 HEIGHT
        double bob = -BOB_AMPLITUDE * (0.5 - 0.5 * Math.cos(2.0 * walkPhaseRad));
        double s = Math.max(0.0, Math.min(1.0, attackSwing01));
        double lunge = -LUNGE * s;
        double armSwing = -ARM_SWING * s;

        int p = start;
        for (int i = 0; i < PART_COUNT; i++) {
            int b = i * FLOATS_PER_PART;
            double minX = PARTS[b];
            double minY = PARTS[b + 1];
            double minZ = PARTS[b + 2];
            double maxX = PARTS[b + 3];
            double maxY = PARTS[b + 4];
            double maxZ = PARTS[b + 5];

            double dy = bob;
            double dz = 0;

            switch (i) {
                case PART_LEG_LEFT -> dz = LEG_SWING * swing;
                case PART_LEG_RIGHT -> dz = -LEG_SWING * swing;
                case PART_ARM_LEFT, PART_ARM_RIGHT -> {
                    dz = lunge + armSwing;
                    dy = bob - 0.06 * s;      // 摆臂时手往下压
                }
                case PART_EYE_LEFT, PART_EYE_RIGHT -> dz = lunge;
                case PART_HEAD -> {
                    dz = lunge;
                    dy = bob - 0.02 * s;      // 攻击时头略沉
                }
                default -> dz = lunge;        // torso
            }

            out[p] = (float) minX;
            out[p + 1] = (float) (minY + dy);
            out[p + 2] = (float) (minZ + dz);
            out[p + 3] = (float) maxX;
            out[p + 4] = (float) (maxY + dy);
            out[p + 5] = (float) (maxZ + dz);
            p += FLOATS_PER_PART;
        }
        return p;
    }

    /**
     * 取某个 part 的基础色（不含闪白）。
     *
     * <p>返回的是<b>内部常量数组的引用</b>：调用方只读、用完即弃，不要持有
     * （与 {@code HudRenderer#iconColor} 的口径一致）。
     */
    public static float[] color(int partIndex) {
        return switch (partIndex) {
            case PART_HEAD -> COLOR_HEAD;
            case PART_TORSO -> COLOR_TORSO;
            case PART_LEG_LEFT, PART_LEG_RIGHT -> COLOR_LEG;
            case PART_ARM_LEFT, PART_ARM_RIGHT -> COLOR_ARM;
            default -> COLOR_EYE;
        };
    }

    /** 该 part 是不是"发光的眼睛"（渲染层据此决定它是否参与闪白混色）。 */
    public static boolean isEye(int partIndex) {
        return partIndex == PART_EYE_LEFT || partIndex == PART_EYE_RIGHT;
    }
}

package com.skyisland.settings;

/**
 * 鼠标视角换算（M1.5 规格第 7 条）。<b>纯函数，不含状态。</b>
 *
 * <p><b>规格要求的链条是"rawMouseDelta × sensitivity → yaw/pitch delta"。</b>
 * M1 的相机里已经有一个基准系数（{@code Camera.SENSITIVITY_DEG_PER_PIXEL = 0.12 度/像素}），
 * 于是本阶段要回答的问题是"这个基准与用户设置如何相乘"，以及"乘法发生在哪一层"。
 *
 * <p><b>为什么把基准与倍数分开，而不是直接把设置值当成"度/像素"：</b>
 * <ul>
 *   <li>0.12 这个数是<u>手感标定值</u>，来自相机；设置里的 1.0 是<u>用户的相对偏好</u>
 *       （"我要比默认快 1.5 倍"）。两者量纲相同但语义不同，合成一个数会让
 *       "调默认手感"与"调用户偏好"互相污染；</li>
 *   <li>分开之后，"设置 = 1.0 时的行为与 M1 完全一致"是一条<u>可断言的等同关系</u> ——
 *       M1 的 LOOK 断言因此不会被本阶段改动。合成一个数的话，这条关系只能靠人记得。</li>
 * </ul>
 *
 * <p><b>反转 Y 为什么在输入层而不是相机层：</b>反转是"鼠标移动方向 → 视角方向"的
 * 约定问题，属于输入解释；相机只认"像素 → 角度"。放在输入层之后，
 * 自测脚本与回放数据（它们直接产出像素位移）仍然表达"真实鼠标怎么动"，
 * 而反转偏好由输入层统一施加。
 */
public final class LookConfig {

    private LookConfig() {
    }

    /**
     * 基准系数 × 灵敏度倍数 = 实际生效的"度/像素"。
     *
     * <p>倍数先被夹到 {@link GameSettings#MIN_SENSITIVITY}–{@link GameSettings#MAX_SENSITIVITY}：
     * 这个函数可能被命令行 / 自测脚本直接调用，不能假定入参已经过 setter。
     */
    public static double effectiveDegPerPixel(double baseDegPerPixel, double sensitivityMultiplier) {
        double m = sensitivityMultiplier;
        if (!Double.isFinite(m)) {
            m = GameSettings.DEFAULT_SENSITIVITY;
        }
        if (m < GameSettings.MIN_SENSITIVITY) {
            m = GameSettings.MIN_SENSITIVITY;
        }
        if (m > GameSettings.MAX_SENSITIVITY) {
            m = GameSettings.MAX_SENSITIVITY;
        }
        return baseDegPerPixel * m;
    }

    /** 反转 Y：只改纵向。横向反转不提供（那是"左手鼠标"的另一个话题，PRD 未要求）。 */
    public static double applyInvertY(double rawDeltaY, boolean invert) {
        return invert ? -rawDeltaY : rawDeltaY;
    }

    /**
     * 把一次原始鼠标位移换算成相机的角度增量。
     *
     * <p>返回数组的语义与 {@code Camera.addLook} 的入参一致：
     * {@code [0]} = 横向像素位移（右为正），{@code [1]} = 纵向像素位移（<b>下为正</b>）。
     * 也就是这里只处理反转，不做角度换算 —— 角度换算仍在相机内完成，
     * 因为它需要 {@code degPerPixel}，而那是相机的状态。
     */
    public static double[] toLookDelta(double rawDeltaX, double rawDeltaY, boolean invertY) {
        return new double[]{rawDeltaX, applyInvertY(rawDeltaY, invertY)};
    }

    /** 供日志/报告：一行说明当前生效的换算关系。 */
    public static String describe(double baseDegPerPixel, double sensitivityMultiplier) {
        return String.format(java.util.Locale.ROOT,
                "灵敏度倍数 %.2f  ×  基准 %.3f 度/像素  =  %.4f 度/像素（即横移 100 px 转 %.2f°）",
                sensitivityMultiplier, baseDegPerPixel,
                effectiveDegPerPixel(baseDegPerPixel, sensitivityMultiplier),
                effectiveDegPerPixel(baseDegPerPixel, sensitivityMultiplier) * 100.0);
    }
}

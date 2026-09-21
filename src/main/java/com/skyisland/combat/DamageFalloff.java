package com.skyisland.combat;

/**
 * 距离衰减（PRD 5.4.3「距离衰减」）。
 *
 * <p><b>规格原文：</b>「有效射程内 100% 伤害；超出后每超 1 格伤害 ×0.9，最低退至 20%」。
 *
 * <p><b>为什么写成"乘幂"而不是"逐格累乘"：</b>
 * 两者在数学上等价（{@code 0.9^n}），但逐格累乘需要一个循环、
 * 而且在超出范围很大的时候（例如对着 100 格外的目标）循环次数不可控。
 * 直接取幂既是一次调用，也让"每格 ×0.9"这条规格在代码里保持字面可读。
 *
 * <p><b>注意这是"每超 1 格"，不是"每 1 格"：</b>
 * 射程内恒为 1.0，从"超出 0 格"开始才应用衰减，
 * 因此在射程边界处伤害是连续的（32 格 = 100%，32.001 格 ≈ 100%）。
 */
public final class DamageFalloff {

    /** 超出有效射程后，每超 1 格的伤害乘数（PRD 5.4.3）。 */
    public static final double MULTIPLIER_PER_BLOCK = 0.9;

    /** 衰减下限（PRD 5.4.3：最低退至 20%）。 */
    public static final double MIN_MULTIPLIER = 0.20;

    private DamageFalloff() {
    }

    /** 距离相关的伤害乘数，范围 {@code [0.20, 1.0]}。 */
    public static double multiplier(double distance, double effectiveRange) {
        if (!(distance > effectiveRange)) {
            // 含 NaN / 负数距离：一律按"射程内"处理而不是抛异常 ——
            // 这是每发子弹都要走的路径，让它因为一个坏浮点值而中断整局游戏是不划算的。
            return 1.0;
        }
        double over = distance - effectiveRange;
        double m = Math.pow(MULTIPLIER_PER_BLOCK, over);
        return Math.max(MIN_MULTIPLIER, m);
    }

    /**
     * 结算伤害。
     *
     * <p><b>取整规则（PRD 未规定，此处定为向下取整并保底 1 点）</b>：
     * <ul>
     *   <li>向下取整：避免"距离越远伤害反而跳高一截"的观感（四舍五入会让 7.2 变 7、7.6 变 8）；</li>
     *   <li>保底 1 点：乘数下限 20% × 手枪 8 = 1.6，向下取整本就 ≥ 1；
     *       保底是为了让将来的低伤害枪械（霰弹单丸 6 × 20% = 1.2）不会出现"命中但零伤害"，
     *       那种反馈会让玩家以为没打中。</li>
     * </ul>
     *
     * @param baseDamage     枪械基础伤害（手枪 8）
     * @param distance       本次命中距离（格）
     * @param effectiveRange 有效射程（手枪 32）
     */
    public static int damage(int baseDamage, double distance, double effectiveRange) {
        double raw = baseDamage * multiplier(distance, effectiveRange);
        int value = (int) Math.floor(raw + 1e-9);
        return Math.max(1, value);
    }
}

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
 *
 * <h2>★ 为什么每超 1 格的倍率与下限必须由调用方传入（M3 接线修正）</h2>
 * 这两个数在 PRD 里是<b>枪械表的列</b>，不是全局常数：霰弹枪是 ×0.8 / 最低 15%
 * （PRD 5.4.3「霰弹衰减」行），与手枪的 ×0.9 / 20% 不同。
 *
 * <p>M3 之前本类把 {@code 0.9} / {@code 0.20} 写成 {@code public static final} 常量，
 * 于是 {@link com.skyisland.item.GunSpec} 里的 {@code falloffPerUnit} /
 * {@code falloffFloor} 除了被构造期校验之外<b>没有任何读者</b> ——
 * 数据看起来已经"数据化"了，实际是死数据：把注册表里的衰减值改掉不会有任何效果。
 * 这是典型的"接线缺失"缺陷：它带着一份全绿的测试活了下来（测试断言的是常量本身）。
 *
 * <p>现在本类的两个方法<b>都要求显式给出这两个值</b>，因此"每把枪有自己的衰减曲线"
 * 才是真的。基线 0.9 / 0.20 只存在于手枪与冲锋枪的注册项里（单一事实来源），
 * 本类不再持有任何一把枪的数值。
 */
public final class DamageFalloff {

    private DamageFalloff() {
    }

    /**
     * 距离相关的伤害乘数，范围 {@code [floor, 1.0]}。
     *
     * @param distance       本次命中距离（格）
     * @param effectiveRange 该枪的有效射程（格）
     * @param perUnit        超出有效射程后每超 1 格的乘数（{@code GunSpec.falloffPerUnit}）
     * @param floor          最低伤害比例（{@code GunSpec.falloffFloor}）
     */
    public static double multiplier(double distance, double effectiveRange,
                                    double perUnit, double floor) {
        if (!(distance > effectiveRange)) {
            // 含 NaN / 负数距离：一律按"射程内"处理而不是抛异常 ——
            // 这是每发子弹都要走的路径，让它因为一个坏浮点值而中断整局游戏是不划算的。
            return 1.0;
        }
        double over = distance - effectiveRange;
        double m = Math.pow(perUnit, over);
        return Math.max(floor, m);
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
     * @param baseDamage     该枪的每弹丸基础伤害（手枪 8）
     * @param distance       本次命中距离（格）
     * @param effectiveRange 该枪的有效射程（手枪 32）
     * @param perUnit        该枪的逐格衰减倍率（手枪 0.90）
     * @param floor          该枪的最低伤害比例（手枪 0.20）
     */
    public static int damage(int baseDamage, double distance, double effectiveRange,
                             double perUnit, double floor) {
        double raw = baseDamage * multiplier(distance, effectiveRange, perUnit, floor);
        int value = (int) Math.floor(raw + 1e-9);
        return Math.max(1, value);
    }
}

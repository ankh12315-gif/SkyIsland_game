package com.skyisland.item;

/**
 * 枪械数值规格（PRD 5.4.1）。
 *
 * <p><b>为什么是 record 而不是散在 {@link Item} 上的字段：</b>
 * 枪械的四项数值（伤害 / 弹匣 / 射速 / 射程）在 PRD 里是<b>一张表的四列</b>，
 * 它们总是一起被读取、一起被验收。把它们收成一个值对象后，
 * {@code item.gun().damage()} 这种读法让"M2 手枪伤害 = 8"这条验收标准
 * 可以直接对着 PRD 表格核对，而不需要在 {@link Item} 里翻找哪个字段属于枪。
 *
 * <p><b>手枪在 MVP 不可合成（用户裁决 A2）：</b>它作为开局装备直接放进背包，
 * 因此本类不含任何配方字段 —— 配方归 Alpha 的 R14，见 PRD 5.6.2。
 *
 * @param damage        单发伤害（手枪 = 8，偏差 ≤ 5%）
 * @param magazineSize  弹匣容量（手枪 = 12）
 * @param fireRate      射速，发/秒（手枪 = 4.0）
 * @param range         有效射程，格（手枪 = 32）；超出后每格 ×0.9，最低 20%
 * @param reloadSeconds 换弹耗时，秒（PRD 5.4.3：手枪 1.2 / 冲锋枪 1.5 / 步枪 2.0 / 霰弹枪 2.5）
 */
public record GunSpec(int damage, int magazineSize, double fireRate, int range, double reloadSeconds) {

    public GunSpec {
        if (damage <= 0) {
            throw new IllegalArgumentException("枪械伤害必须为正: " + damage);
        }
        if (magazineSize <= 0) {
            throw new IllegalArgumentException("弹匣容量必须为正: " + magazineSize);
        }
        if (!(fireRate > 0)) {
            throw new IllegalArgumentException("射速必须为正: " + fireRate);
        }
        if (range <= 0) {
            throw new IllegalArgumentException("有效射程必须为正: " + range);
        }
        if (!(reloadSeconds > 0)) {
            throw new IllegalArgumentException("换弹耗时必须为正: " + reloadSeconds);
        }
    }

    /** 每发之间的最小间隔（秒）。射速 4.0 → 0.25 s。 */
    public double shotInterval() {
        return 1.0 / fireRate;
    }
}

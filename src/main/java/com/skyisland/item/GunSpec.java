package com.skyisland.item;

/**
 * 枪械逻辑规格（PRD 5.4.1；WEAPON-DOC-001 v2 §3）。
 *
 * <p><b>为什么是 record 而不是散在 {@link Item} 上的字段：</b>
 * 枪械的数值在 PRD 里是<b>一张表的多列</b>，它们总是一起被读取、一起被验收。
 * 把它们收成一个值对象后，{@code item.gun().damage()} 这种读法让
 * "M2 手枪伤害 = 8"这条验收标准可以直接对着 PRD 表格核对，
 * 而不需要在 {@link Item} 里翻找哪个字段属于枪。
 *
 * <p><b>M3 为什么从 5 字段扩到 13 字段（v2 §1.1 / §2.1）：</b>
 * M2 只有一把手枪，战斗层存在"开火节奏 / ADS 参数 / 弹药 ID"的隐性硬编码。
 * M3 要落地第二把枪（SMG，AUTO），必须让这些差异全部由数据表达，
 * 否则每加一把枪就要在战斗逻辑里再写一条 if。本次扩展即把"两把枪的差异"
 * 从代码挪进本 record，使 {@code GunState} / {@code CombatController} 对枪种零感知。
 *
 * <p><b>本类只承载战斗规则。</b>Viewmodel / 图标 / 枪口位置 / 音效等表现资源
 * 归 {@code GunPresentationSpec}（v2 §4），两者的实际落地见 Story 5/6。
 *
 * <p><b>手枪在 MVP 不可合成（用户裁决 A2）：</b>它作为开局装备直接放进背包，
 * 因此本类不含任何配方字段 —— 配方归 Alpha 的 R14，见 PRD 5.6.2。
 *
 * @param damage           每弹丸基础伤害（手枪 = 8，偏差 ≤ 5%）
 * @param magazineSize     弹匣容量（手枪 = 12）
 * @param fireRate         每秒最大击发次数（手枪 = 4.0）
 * @param range            有效射程基准，格（手枪 = 32）；超出后逐格衰减
 * @param reloadSeconds    完整换弹耗时，秒（PRD 5.4.3：手枪 1.2 / 冲锋枪 1.5）
 * @param fireMode         开火模式（{@link FireMode#SINGLE} / {@link FireMode#AUTO}）
 * @param pelletCount      单次击发弹丸数（M3 两把均为 1，为未来霰弹枪预留表达）
 * @param spreadRad        散布半角，弧度（M3 均为 0，保持"准星指向即命中"）
 * @param aimFovDeg        ADS 绝对目标 FOV（度；手枪 = 45），运行时取 min(1, aimFovDeg / baseFov)
 * @param aimMoveSpeedMult ADS 时玩家移速倍率（手枪 = 0.60）
 * @param falloffPerUnit   超出有效射程后每格的伤害衰减倍率（手枪 = 0.90）
 * @param falloffFloor     最低伤害比例（手枪 = 0.20）
 * @param ammoId           对应真实弹药 {@link Item} 的 stable ID（手枪 = skyisland:pistol_ammo）
 */
public record GunSpec(
        int damage,
        int magazineSize,
        double fireRate,
        int range,
        double reloadSeconds,
        FireMode fireMode,
        int pelletCount,
        double spreadRad,
        double aimFovDeg,
        double aimMoveSpeedMult,
        double falloffPerUnit,
        double falloffFloor,
        String ammoId
) {

    /**
     * 构造期校验（v2 §3.4）。
     *
     * <p>所有非法输入一律抛 {@link IllegalArgumentException}，而不是让坏数据流进战斗层。
     * 理由：这些值来自注册表常量，若写错属于开发期错误，应当<b>立刻崩</b>在启动时，
     * 而不是在若干分钟后表现为"打不准 / 打不动 / HUD 显示空弹药"这类难查的症状。
     */
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
        if (fireMode == null) {
            throw new IllegalArgumentException("开火模式不能为空");
        }
        if (pelletCount < 1) {
            throw new IllegalArgumentException("单次击发弹丸数至少为 1: " + pelletCount);
        }
        if (!(spreadRad >= 0)) {
            throw new IllegalArgumentException("散布半角不能为负: " + spreadRad);
        }
        if (!(aimFovDeg > 0 && aimFovDeg < 180)) {
            throw new IllegalArgumentException("ADS 目标 FOV 必须落在 (0, 180) 区间: " + aimFovDeg);
        }
        if (!(aimMoveSpeedMult > 0 && aimMoveSpeedMult <= 1)) {
            throw new IllegalArgumentException("ADS 移速倍率必须落在 (0, 1] 区间: " + aimMoveSpeedMult);
        }
        if (!(falloffPerUnit > 0 && falloffPerUnit <= 1)) {
            throw new IllegalArgumentException("逐格衰减倍率必须落在 (0, 1] 区间: " + falloffPerUnit);
        }
        if (!(falloffFloor >= 0 && falloffFloor < 1)) {
            throw new IllegalArgumentException("最低伤害比例必须落在 [0, 1) 区间: " + falloffFloor);
        }
        if (ammoId == null || ammoId.isBlank()) {
            throw new IllegalArgumentException("弹药 ID 不能为空: " + ammoId);
        }
    }

    /** 每发之间的最小间隔（秒）。射速 4.0 → 0.25 s。 */
    public double shotInterval() {
        return 1.0 / fireRate;
    }
}

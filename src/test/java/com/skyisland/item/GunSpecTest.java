package com.skyisland.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link GunSpec} v2 的字段完整性与构造校验（WEAPON-DOC-001 v2 §3.4 / §3.5 / §14.1）。
 *
 * <p><b>这个类守住两件在 M3 才变得重要、却只有一行代码的事：</b>
 * <ol>
 *   <li><b>手枪的 13 个字段必须逐值等于 PRD 基线。</b>M3 把 GunSpec 从 5 字段扩到 13 字段，
 *       填充注册表时最容易犯的错是"字段顺序写串了"—— 比如把 {@code aimFovDeg} 45
 *       和 {@code aimMoveSpeedMult} 0.60 填反。这类错误不会抛异常（45 与 0.60
 *       都落在各自合法区间内），却会让 ADS 手感完全错掉。逐字段断言是唯一能立刻抓住它的办法。</li>
 *   <li><b>13 条校验必须真的生效。</b>这些值全部来自注册表常量，写错属于开发期错误；
 *       如果校验漏写，坏数据会一路流进战斗层，最后表现为难查的"打不中/伤害不对"。
 *       因此每条区间边界都要有一条"应当抛"的用例，尤其要覆盖半开区间的两端。</li>
 * </ol>
 *
 * <p>本类只测"字段存在且被校验"，<b>不</b>测开火/换弹行为 ——
 * 那些属于 GunState / CombatController（Story 3+）。本 Story 只是让字段存在。
 */
class GunSpecTest {

    /** 一份合法的"基准"构造，供各非法用例只改一个字段来复用。 */
    private static GunSpec valid() {
        return new GunSpec(
                8, 12, 4.0, 32, 1.2,
                FireMode.SINGLE, 1, 0.0,
                45.0, 0.60, 0.90, 0.20,
                ItemRegistry.PISTOL_AMMO_ID);
    }

    // ============================================================ 手枪全字段（v2 §3.5 / §14.1）

    /**
     * 手枪 13 字段逐值断言（v2 §3.5）。
     *
     * <p>逐字段而非整对象 assertEquality：record 的 equals 也能比出不等，但
     * 一条"整个 record 不等价"的失败信息<b>不会告诉你哪个字段串了</b>；
     * 逐字段断言能让 Story 6 加 SMG 时直接复制这张清单做对照。
     */
    @Test
    void pistolMatchesEveryFieldOfV2Baseline() {
        GunSpec spec = ItemRegistry.pistol().gun();
        assertEquals(8, spec.damage(), "damage 基线 = 8");
        assertEquals(12, spec.magazineSize(), "magazineSize 基线 = 12");
        assertEquals(4.0, spec.fireRate(), 1e-9, "fireRate 基线 = 4.0");
        assertEquals(32, spec.range(), "range 基线 = 32");
        assertEquals(1.2, spec.reloadSeconds(), 1e-9, "reloadSeconds 基线 = 1.2");
        assertEquals(FireMode.SINGLE, spec.fireMode(), "手枪必须是单发模式");
        assertEquals(1, spec.pelletCount(), "M3 手枪单次击发 1 颗弹丸");
        assertEquals(0.0, spec.spreadRad(), 1e-9, "M3 手枪无散布（准星指向即命中）");
        assertEquals(45.0, spec.aimFovDeg(), 1e-9, "ADS 绝对目标 FOV = 45°");
        assertEquals(0.60, spec.aimMoveSpeedMult(), 1e-9, "ADS 移速倍率 = 0.60");
        assertEquals(0.90, spec.falloffPerUnit(), 1e-9, "超出射程每格 ×0.90");
        assertEquals(0.20, spec.falloffFloor(), 1e-9, "最低伤害比例 = 0.20");
        assertEquals(ItemRegistry.PISTOL_AMMO_ID, spec.ammoId(),
                "手枪弹药必须指向已注册的真实 Item（v2 §0.4，禁止只挂字符串标签）");
    }

    /** ammoId 必须能在物品注册表里找到真实弹药 —— 否则 Survival 有限弹药路径无法扣弹。 */
    @Test
    void pistolAmmoIdPointsAtARealRegisteredItem() {
        String ammoId = ItemRegistry.pistol().gun().ammoId();
        Item ammo = ItemRegistry.byName(ammoId);
        assertEquals(ItemKind.AMMO, ammo.kind(),
                "手枪 ammoId 必须解析到 AMMO 类物品，而不是 null 或别的类别: " + ammoId);
    }

    /** shotInterval 是唯一的行为方法，扩展字段后不能改变它的语义。 */
    @Test
    void shotIntervalStillDerivesFromFireRate() {
        assertEquals(0.25, valid().shotInterval(), 1e-9, "射速 4.0 → 间隔 0.25 s（扩展后必须不变）");
    }

    /** 合法基准本身必须构造成功 —— 防止某条校验把合法值也拒了。 */
    @Test
    void validBaselineConstructsFine() {
        assertEquals(8, valid().damage());
    }

    // ============================================================ 保留的 5 条旧校验

    @Test
    void rejectsNonPositiveDamage() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                0, 12, 4.0, 32, 1.2, FireMode.SINGLE, 1, 0.0, 45.0, 0.60, 0.90, 0.20,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    @Test
    void rejectsNonPositiveMagazineSize() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 0, 4.0, 32, 1.2, FireMode.SINGLE, 1, 0.0, 45.0, 0.60, 0.90, 0.20,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    @Test
    void rejectsNonPositiveFireRate() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 0.0, 32, 1.2, FireMode.SINGLE, 1, 0.0, 45.0, 0.60, 0.90, 0.20,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    @Test
    void rejectsNonPositiveRange() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 4.0, 0, 1.2, FireMode.SINGLE, 1, 0.0, 45.0, 0.60, 0.90, 0.20,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    @Test
    void rejectsNonPositiveReloadSeconds() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 4.0, 32, 0.0, FireMode.SINGLE, 1, 0.0, 45.0, 0.60, 0.90, 0.20,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    // ============================================================ M3 新增的 8 条校验

    /** fireMode 为 null 必须拒绝：null 模式会让战斗层的 switch 落空。 */
    @Test
    void rejectsNullFireMode() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 4.0, 32, 1.2, null, 1, 0.0, 45.0, 0.60, 0.90, 0.20,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    /** pelletCount 下界是 1（不是 0）：一次击发发出 0 颗弹丸在语义上等于"没开火"。 */
    @Test
    void rejectsZeroPelletCount() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 4.0, 32, 1.2, FireMode.SINGLE, 0, 0.0, 45.0, 0.60, 0.90, 0.20,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    /** spreadRad 允许 0（无散布），不允许负。 */
    @Test
    void rejectsNegativeSpreadRad() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 4.0, 32, 1.2, FireMode.SINGLE, 1, -0.1, 45.0, 0.60, 0.90, 0.20,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    /** aimFovDeg 下界开区间：0 会得到无限大放大倍率，非法。 */
    @Test
    void rejectsZeroAimFovDeg() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 4.0, 32, 1.2, FireMode.SINGLE, 1, 0.0, 0.0, 0.60, 0.90, 0.20,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    /** aimFovDeg 上界开区间：180 已不是有效视场角。 */
    @Test
    void rejectsAimFovDegAtUpperBound() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 4.0, 32, 1.2, FireMode.SINGLE, 1, 0.0, 180.0, 0.60, 0.90, 0.20,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    /** aimMoveSpeedMult 下界开区间：0 会让玩家开镜时完全钉死，非法。 */
    @Test
    void rejectsZeroAimMoveSpeedMult() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 4.0, 32, 1.2, FireMode.SINGLE, 1, 0.0, 45.0, 0.0, 0.90, 0.20,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    /** aimMoveSpeedMult 上界闭区间：> 1 会让开镜反而跑得更快，非法。 */
    @Test
    void rejectsAimMoveSpeedMultAboveOne() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 4.0, 32, 1.2, FireMode.SINGLE, 1, 0.0, 45.0, 1.1, 0.90, 0.20,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    /** falloffPerUnit 下界开区间：0 会让伤害一步掉到 0，非法。 */
    @Test
    void rejectsZeroFalloffPerUnit() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 4.0, 32, 1.2, FireMode.SINGLE, 1, 0.0, 45.0, 0.60, 0.0, 0.20,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    /** falloffFloor 上界开区间：1.0 表示"永不衰减"，与射程衰减机制矛盾，非法。 */
    @Test
    void rejectsFalloffFloorAtUpperBound() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 4.0, 32, 1.2, FireMode.SINGLE, 1, 0.0, 45.0, 0.60, 0.90, 1.0,
                ItemRegistry.PISTOL_AMMO_ID));
    }

    /** ammoId 为 null 必须拒绝 —— 否则有限弹药路径会在运行期 NPE。 */
    @Test
    void rejectsNullAmmoId() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 4.0, 32, 1.2, FireMode.SINGLE, 1, 0.0, 45.0, 0.60, 0.90, 0.20,
                null));
    }

    /** ammoId 为纯空白必须拒绝：空白串既查不到物品，又骗过了 null 检查。 */
    @Test
    void rejectsBlankAmmoId() {
        assertThrows(IllegalArgumentException.class, () -> new GunSpec(
                8, 12, 4.0, 32, 1.2, FireMode.SINGLE, 1, 0.0, 45.0, 0.60, 0.90, 0.20,
                "   "));
    }

    // ============================================================ FireMode 枚举范围（v2 §3.2）

    /**
     * M3 只允许 SINGLE / AUTO —— 不得出现 BURST。
     *
     * <p>这条测试锁的是 v2 §0.5 / §3.2 的产品裁决："没有实际武器使用的功能不提前造死代码"。
     * 若将来有人往枚举里塞回 BURST，这里会立刻变红，提醒先确认武器需求已落地。
     */
    @Test
    void fireModeContainsOnlySingleAndAuto() {
        assertEquals(2, FireMode.values().length,
                "M3 只允许 SINGLE / AUTO 两种开火模式（不得提前加 BURST）");
        assertEquals(FireMode.SINGLE, FireMode.valueOf("SINGLE"));
        assertEquals(FireMode.AUTO, FireMode.valueOf("AUTO"));
    }
}

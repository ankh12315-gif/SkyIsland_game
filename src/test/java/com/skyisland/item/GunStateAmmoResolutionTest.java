package com.skyisland.item;

import com.skyisland.combat.GunState;
import com.skyisland.player.Inventory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GunState} 的后备弹药解析已<b>数据驱动</b>（去手枪硬编码），v2 §5.2。
 *
 * <h2>为什么这些用例放在 {@code com.skyisland.item} 包</h2>
 * 负例需要一把"ammoId 指向不存在弹药"的枪，而 {@link Item} 的构造器是包级私有 ——
 * 只有本包能造出这样的探针物品。用例本身测的是 {@link GunState}（公开 API），
 * 放在这里只是为了让"坏数据"这个夹具能被构造出来。
 *
 * <h2>这两条用例守什么</h2>
 * <ol>
 *   <li><b>ammoId 数据驱动</b>：{@code GunState} 读取的是 {@code spec.ammoId()}
 *       对应的真实物品，而不是写死的 {@code PISTOL_AMMO_ID}。用一个 ammoId 指向
 *       <b>非手枪弹</b>的真实物品（煤炭）作探针：换弹必须从煤炭那格扣减 ——
 *       若代码里还有 {@code PISTOL_AMMO_ID} 字面量，这条会红。</li>
 *   <li><b>禁止静默 fallback</b>：ammoId 在注册表里查不到时，构造期必须直接失败，
 *       而不是"换弹时默默扣不动、表现为打不完的子弹"。</li>
 * </ol>
 */
class GunStateAmmoResolutionTest {

    /** 一把枪：规格与手枪一致，但弹药 ID 可任意指定，用于探针。 */
    private static Item gunWithAmmoId(String ammoId) {
        // runtimeId / id 用一个不与真实物品冲突的占位值即可 —— 本用例只走 GunState，
        // 不经过 ItemRegistry 查询这把枪本身。
        return new Item(999_999, "skyisland:probe_gun", ItemKind.GUN, null, 1,
                new GunSpec(
                        8, 12, 4.0, 32, 1.2,
                        FireMode.SINGLE, 1, 0.0,
                        45.0, 0.60, 0.90, 0.20,
                        ammoId),
                null);   // 探针枪只走 GunState 的弹药解析，不需要表现规格
    }

    /**
     * 规则②③数据驱动：换弹从 {@code spec.ammoId()} 指定的物品扣减，与"是不是手枪"无关。
     *
     * <p>探针枪的 ammoId 指向 <b>煤炭</b>（一个真实存在、但显然不是手枪弹的物品）。
     * 有限口径下换弹 3 发，必须恰好从煤炭扣减 3 —— 这证明代码读的是数据里的 ammoId。
     */
    @Test
    void reserveAmmoIsReadFromSpecAmmoIdNotAHardCodedPistolAmmo() {
        Item probe = gunWithAmmoId(ItemRegistry.COAL_ID);
        GunState gun = new GunState(probe, false);   // 有限后备
        gun.setMagazineAmmo(10);

        Inventory inv = new Inventory();
        inv.add(ItemRegistry.coal().runtimeId(), 5);

        assertEquals(GunState.ReloadOutcome.STARTED, gun.tryStartReload(inv),
                "后备来自 ammoId（煤炭）那格，>0 故允许换弹（规则②）");
        gun.tick(1.2, inv);

        assertEquals(12, gun.magazineAmmo(), "10 + min(2, 5) = 12（规则③部分填充）");
        assertEquals(3, inv.countOfItem(ItemRegistry.COAL_ID),
                "5 − min(2, 5) = 3：必须从 ammoId 指定的煤炭扣减 2 发 —— 这证明解析走的是 spec.ammoId()");
        assertEquals(0, inv.countOfItem(ItemRegistry.PISTOL_AMMO_ID),
                "背包里本就没有手枪弹，更不该去读取它");
    }

    /**
     * ammoId 在注册表里查不到 → 构造期立刻失败（禁止静默 fallback）。
     *
     * <p>这条替代了 M2 对 {@code PISTOL_AMMO_ID} 的硬编码：硬编码"永远找得到"，
     * 数据化后必须显式校验"这个 ID 真的存在"，否则"数据写错"会退化成运行期难查的症状。
     */
    @Test
    void constructionFailsWhenAmmoIdIsNotRegistered() {
        Item bad = gunWithAmmoId("skyisland:no_such_ammo");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new GunState(bad, false),
                "ammoId 未登记时必须构造失败，不得静默 fallback");
        assertTrue(ex.getMessage().contains("skyisland:no_such_ammo"),
                "异常信息必须含出错的 ammoId，方便定位数据问题");
    }

    // ============================================================ SMG（v2 §6.1 / §14.4 / §14.5）

    /**
     * SMG 的换弹同样走 {@code spec.ammoId()} 解析，且解析到的就是手枪弹。
     *
     * <p>这是 v2 §6.1「两把枪共用一种实际弹药」的可执行形式：
     * SMG 的有限口径换弹必须从<b>手枪弹那一格</b>扣减，而不是一个新造的 {@code smg_ammo}。
     *
     * <p><b>反向验证（TEMP_REVERSE_VERIFY）</b>：把 {@code ItemRegistry} 里 SMG 的
     * ammoId 改成 {@code "skyisland:no_such_ammo"}，
     * {@link #smgRealSpecResolvesToPistolAmmo()} 会抛异常变红。
     */
    @Test
    void smgReloadDrawsFromTheSharedPistolAmmoItem() {
        GunState gun = new GunState(ItemRegistry.smg(), false);   // 有限后备
        gun.setMagazineAmmo(20);

        Inventory inv = new Inventory();
        inv.add(ItemRegistry.pistolAmmo().runtimeId(), 10);

        assertEquals(GunState.ReloadOutcome.STARTED, gun.tryStartReload(inv));
        gun.tick(1.5, inv);   // SMG 换弹 1.5 秒

        assertEquals(24, gun.magazineAmmo(), "20 + min(4, 10) = 24（SMG 自己的容量）");
        assertEquals(6, inv.countOfItem(ItemRegistry.PISTOL_AMMO_ID),
                "10 − 4 = 6：SMG 必须从共用弹药那一格扣减（v2 §6.1）");
    }

    /** 真实 SMG 的 spec 必须能解析到真实弹药 —— 数据写错时构造期就崩（v2 §14.5）。 */
    @Test
    void smgRealSpecResolvesToPistolAmmo() {
        assertEquals(ItemRegistry.pistolAmmo(),
                ItemRegistry.byName(ItemRegistry.smg().gun().ammoId()),
                "SMG 的 ammoId 必须指向已登记的手枪弹");
        // 若 ammoId 写错，这一行会抛 IllegalStateException
        GunState smg = new GunState(ItemRegistry.smg(), false);
        assertEquals(24, smg.magazineSize());
        assertEquals(1.5, smg.spec().reloadSeconds(), 1e-9);
    }
}

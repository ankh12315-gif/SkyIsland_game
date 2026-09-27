package com.skyisland.item;

/**
 * 测试用的"探针枪"工厂（仅 {@code src/test}，不进入产品）。
 *
 * <h2>为什么需要它</h2>
 * {@link Item} 的构造器是包级私有的，只有 {@code com.skyisland.item} 包内能 new。
 * 于是"造一把 ammoId 指向别的物品的枪"这件事<u>只有本包能做</u>。
 * 而 M3 Story 7 最关键的一条断言恰恰需要它：<b>证明 HUD / GunState 读的是枪自己的
 * {@code GunSpec.ammoId}，而不是写死的 {@code skyisland:pistol_ammo}</b>。
 *
 * <h2>为什么必须"ammoId 指向另一个真实物品"才能构成强断言</h2>
 * 手枪与 SMG 的 ammoId 都是 {@code pistol_ammo}（v2 §6.1 共用弹药）。
 * 只在这两把真枪上测，产品里即使写死 {@code PISTOL_AMMO_ID} 也会全绿 ——
 * 那是一条"看起来在测数据驱动、实际测不出任何东西"的空断言。
 * 让 probe 枪的 ammoId 指向<b>煤炭</b>（一个真实登记、但显然不是弹药的物品），
 * 才能把"读 spec"与"读常量"区分开。
 *
 * <p>放在 {@code src/test} 而不是 {@code src/main}：探针枪是测试夹具，
 * 不得进入产品代码（那会变成"第 3 把枪"，违反 v2 §18）。
 */
public final class TestGuns {

    private TestGuns() {
    }

    /**
     * 造一把探针枪：弹药 ID 任选，其余规格与手枪一致。
     *
     * <p>runtimeId 用一个明显不与真实物品冲突的占位值（999_999）：
     * 本夹具只用于 {@code GunState} / {@code GunSpec} 的读取路径，
     * 不经过 {@link ItemRegistry} 查询这把枪自身，因此不会污染注册表。
     *
     * @param ammoId 该枪对应的弹药 stable ID（必须已在 {@link ItemRegistry} 登记，
     *               否则 {@code new GunState(...)} 会在构造期抛出）
     */
    public static Item probeGunWithAmmo(String ammoId) {
        return new Item(999_999, "skyisland:probe_gun", ItemKind.GUN, null, 1,
                new GunSpec(
                        8, 12, 4.0, 32, 1.2,
                        FireMode.SINGLE, 1, 0.0,
                        45.0, 0.60, 0.90, 0.20,
                        ammoId),
                null);   // 探针枪只走逻辑/弹药解析，不需要表现规格
    }
}

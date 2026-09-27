package com.skyisland.game;

import com.skyisland.combat.CombatController;
import com.skyisland.combat.GunState;
import com.skyisland.entity.EntityManager;
import com.skyisland.item.Item;
import com.skyisland.item.ItemRegistry;
import com.skyisland.item.TestGuns;
import com.skyisland.player.Inventory;
import com.skyisland.player.ItemStack;
import com.skyisland.player.Player;
import com.skyisland.ui.Localization;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HUD 弹药 / 枪名的<b>数据驱动</b>（WEAPON-DOC-001 v2 §19 第 9 条「HUD 不硬编码手枪」+
 * 第 10 条「ammoId 不硬编码手枪弹」，§14.6「切枪后 HUD、Viewmodel、GunState 同步」）。
 *
 * <h2>为什么断言直接打在 {@link SkyIslandGame#reserveAmmoIdOf} 上</h2>
 * 后备弹药读数是本 Story 唯一的硬编码修正点，产品实现把它抽成了一个纯函数
 * {@link SkyIslandGame#reserveAmmoIdOf(ItemStack, GunState)}：它无 GL 依赖、只读不建对象。
 * 测试因此<b>直接调用产品那一行</b>去断言，而不是在测试里把同样的三元表达式再抄一遍 ——
 * 抄一遍的写法与产品解耦：把产品改回硬编码 {@code PISTOL_AMMO_ID} 时，抄出来的那份
 * 测试逻辑照样是对的，于是断言不会变红（这正是"反向验证"要抓的假绿）。
 *
 * <h2>为什么必须用"异 ammoId 的枪"而不是只用 SMG</h2>
 * 手枪与 SMG 的 ammoId 恰好都指向 {@code skyisland:pistol_ammo}（v2 §6.1 共用弹药），
 * 所以只在这两把真枪上测，产品即使写死 {@code PISTOL_AMMO_ID} 也会全绿 ——
 * 那是一条测不出东西的空断言。要真正区分"读 spec"与"读常量"，必须让手持枪的
 * ammoId 指向<b>另一个</b>真实物品：见 {@link #reserveAmmoIdFollowsTheGunsOwnAmmoIdNotAPistolConstant()}。
 *
 * <p>探针枪由 {@link TestGuns#probeGunWithAmmo(String)} 提供（{@code Item} 构造器是
 * 包级私有，只有 {@code com.skyisland.item} 包能 new），它落在 {@code src/test} 里，
 * 不进入产品、不构成 v2 §18 禁止的"第 3 把枪"。
 */
class HudAmmoDataSourceTest {

    // ------------------------------------------------------------ 夹具

    private static Player freshPlayer() {
        return new Player(0.5, 64.0, 0.5);
    }

    /**
     * 复刻 {@code SkyIslandGame.updateHud} 里"由 ammoId 落到 HUD 读数"的那一步
     * （即产品 :2513 的 {@code ammoId == null ? 0 : countOfItem(ammoId)}）。
     *
     * <p>与产品<b>共享</b>同一份 {@link SkyIslandGame#reserveAmmoIdOf} —— 这保证
     * "产品里 ammoId 是怎么选的"这件事只有一处实现，测试断言的就是它。
     */
    private static int hudReserve(Player player, CombatController combat) {
        ItemStack held = player.inventory().selectedStack();
        GunState gun = combat.existingGun(player);
        String ammoId = SkyIslandGame.reserveAmmoIdOf(held, gun);
        return ammoId == null ? 0 : player.inventory().countOfItem(ammoId);
    }

    /** 把某把枪放进快捷栏第 1 格并选中它，同时在背包里放好若干发手枪弹。 */
    private static Player playerHolding(String gunStableId, int pistolAmmoCount) {
        Player player = freshPlayer();
        Inventory inv = player.inventory();
        inv.setSlot(inv.hotbarIndex(0),
                ItemStack.of(ItemRegistry.runtimeIdOf(gunStableId), 1));
        if (pistolAmmoCount > 0) {
            inv.add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), pistolAmmoCount);
        }
        inv.selectSlot(0);
        return player;
    }

    // ============================================================ §19-10：ammoId 数据驱动

    /**
     * §19-10：HUD 后备弹药读的是<b>手持枪自己的 ammoId</b>，不是写死的 {@code pistol_ammo}。
     *
     * <ul>
     *   <li>手枪（ammoId = pistol_ammo，背包 24）→ 24；</li>
     *   <li>SMG（ammoId = pistol_ammo，v2 §6.1 共用，背包 24）→ 24；</li>
     *   <li>空手（背包里有 24 发弹药）→ 0，且枪名为空串。</li>
     * </ul>
     * 最后一条是关键反向对照：证明读数不是"背包里有多少手枪弹"，
     * 而是"手持枪的 ammoId 那一格有多少"。
     */
    @Test
    void hudReserveFollowsTheHeldGunsAmmoId() {
        CombatController combat = new CombatController(new EntityManager());

        Player withPistol = playerHolding(ItemRegistry.PISTOL_ID, 24);
        assertEquals(24, hudReserve(withPistol, combat),
                "手枪的后备 = 背包里 pistol_ammo 的计数");
        assertEquals(ItemRegistry.PISTOL_AMMO_ID,
                SkyIslandGame.reserveAmmoIdOf(withPistol.inventory().selectedStack(),
                        combat.existingGun(withPistol)),
                "手枪的 ammoId 解析结果 = pistol_ammo");

        Player withSmg = playerHolding(ItemRegistry.SMG_ID, 24);
        assertEquals(24, hudReserve(withSmg, combat),
                "SMG 与手枪共用 pistol_ammo（v2 §6.1），读数同样是那一格的计数");

        Player emptyHanded = freshPlayer();
        emptyHanded.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), 24);
        assertEquals(0, hudReserve(emptyHanded, combat),
                "空手时 HUD 不显示弹药读数（0），而不是把背包里的弹药数当成后备 —— "
                        + "这条反向对照证明读数绑定的是「手持枪的 ammoId」，不是「背包有多少手枪弹」");
        assertNull(SkyIslandGame.reserveAmmoIdOf(emptyHanded.inventory().selectedStack(),
                        combat.existingGun(emptyHanded)),
                "空手时 ammoId 解析结果必须是 null（HUD 据此记 0）");
    }

    /**
     * <b>最强的一条</b>：手持枪的 {@code ammoId} 指向<b>另一个真实物品</b>（煤炭）时，
     * HUD 读数必须跟着它走 —— 证明读的是枪自己的数据，而不是写死的手枪弹。
     *
     * <h2>为什么这条能穿透"共用弹药"造成的假绿</h2>
     * 手枪与 SMG 的 ammoId 都是 {@code pistol_ammo}，在真枪上无论产品读
     * {@code spec().ammoId()} 还是 {@code PISTOL_AMMO_ID}，结果都一样 ——
     * 那是测不出东西的断言。这里用 {@link TestGuns#probeGunWithAmmo(String)} 造一把
     * "煤炭口径"的枪：背包 coal = 7、pistol_ammo = 99。
     * 读数必须是 <b>7</b>；若产品读的是手枪弹就会得到 99，本用例精确变红。
     *
     * <p>断言打在 {@link SkyIslandGame#reserveAmmoIdOf} 上，因此产品若把那一行
     * 改回 {@code ItemRegistry.PISTOL_AMMO_ID}，本用例立刻变红（已做破坏注入验证）。
     */
    @Test
    void reserveAmmoIdFollowsTheGunsOwnAmmoIdNotAPistolConstant() {
        // 一把"煤炭口径"的枪：除 ammoId = 煤炭外，规格与手枪一致。
        // ★ 探针枪用 ItemStack.of(runtimeId) 放不进背包（runtimeId 未登记 → 会是空槽），
        //   因此这里只用它可以走通的路径：GunState 直接持有 Item，spec 就在手上。
        Item probeGun = TestGuns.probeGunWithAmmo(ItemRegistry.COAL_ID);
        GunState probeState = new GunState(probeGun, true);

        // gun != null 路径（HUD 在主循环里的常态）：取 gun.spec().ammoId()
        assertEquals(ItemRegistry.COAL_ID,
                SkyIslandGame.reserveAmmoIdOf(null, probeState),
                "gun 存在时必须读 gun.spec().ammoId()（煤炭），而不是手枪弹常量 —— "
                        + "held 参数在此路径下不被使用，传 null 也证明这一点");
        assertFalse(ItemRegistry.PISTOL_AMMO_ID.equals(
                        SkyIslandGame.reserveAmmoIdOf(null, probeState)),
                "绝不能解析成手枪弹那一格（§19-10 的核心反例）");

        // 落到"背包计数"这一步：coal = 7 / pistol_ammo = 99，读数必须是 7。
        Inventory inv = freshPlayer().inventory();
        inv.add(ItemRegistry.runtimeIdOf(ItemRegistry.COAL_ID), 7);
        inv.add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), 99);
        int reserve = inv.countOfItem(SkyIslandGame.reserveAmmoIdOf(null, probeState));
        assertEquals(7, reserve, "HUD 后备必须读煤炭那格（7），不是手枪弹那格（99）");
        assertEquals(99, inv.countOfItem(ItemRegistry.PISTOL_AMMO_ID),
                "对照：手枪弹那一格确实是 99（若产品读错格，上面的断言就会是 99）");

        // gun == null 路径（枪尚未建态）：退化到手持物自己的 gun().ammoId()。
        // 真枪里用 SMG 当代表 —— 它的 spec.ammoId() 与手枪一致（共用弹药），
        // 但这条断言的目的是"解析结果来自手持物的 Item，而不是某个常量"：
        // 若把该路径改成返回 PISTOL_AMMO_ID 常量，会与"非枪手持物返回 null"这条对照冲突。
        Player smgHolder = playerHolding(ItemRegistry.SMG_ID, 5);
        assertEquals(ItemRegistry.SMG_ID,
                smgHolder.inventory().selectedStack().item().id(), "前提：手持 SMG");
        assertEquals(ItemRegistry.PISTOL_AMMO_ID,
                SkyIslandGame.reserveAmmoIdOf(smgHolder.inventory().selectedStack(), null),
                "gun 尚未建出时读手持物 spec.ammoId()（SMG 与手枪共用弹药，故同为 pistol_ammo）");
        // 反向对照：手持非枪物品时该路径必须返回 null，不能返回手枪弹常量。
        Player blockHolder = freshPlayer();
        blockHolder.inventory().setSlot(blockHolder.inventory().hotbarIndex(0),
                ItemStack.of(com.skyisland.world.block.BlockRegistry.stone().runtimeId(), 1));
        blockHolder.inventory().selectSlot(0);
        assertNull(SkyIslandGame.reserveAmmoIdOf(blockHolder.inventory().selectedStack(), null),
                "手持方块且无 GunState 时必须返回 null —— 若这里是常量 PISTOL_AMMO_ID，本条会红");

        // 真枪的 spec.ammoId() 来自注册表数据（两把都是手枪弹，属 v2 §6.1 的刻意共用）。
        assertEquals(ItemRegistry.PISTOL_AMMO_ID, ItemRegistry.smg().gun().ammoId(),
                "SMG 的 ammoId 来自它自己的 spec（v2 §6.1）");
        assertEquals(ItemRegistry.PISTOL_AMMO_ID, ItemRegistry.pistol().gun().ammoId(),
                "手枪的 ammoId 同样来自 spec —— 两者都是数据，不是代码分支");
    }

    // ============================================================ §14.6：切枪同步

    /**
     * §14.6：手持从手枪切到 SMG 后，HUD 显示的枪名 / 后备读数 / GunState 的枪身份三者一致。
     *
     * <p>"切枪后 HUD、Viewmodel、GunState 同步"的可判定形式：
     * <ol>
     *   <li>HUD 枪名是<b>当前手持枪</b>的中文名（手枪→"手枪"，SMG→"冲锋枪"）；</li>
     *   <li>HUD 枪名与 {@code GunState.gun().id()} 指向同一把枪；</li>
     *   <li>弹药读数所用的 ammoId 与当前 GunState 的 spec 同源。</li>
     * </ol>
     */
    @Test
    void switchingGunsKeepsHudNameAmmoAndGunStateInSync() {
        CombatController combat = new CombatController(new EntityManager());
        Player player = freshPlayer();
        Inventory inv = player.inventory();
        inv.setSlot(inv.hotbarIndex(0),
                ItemStack.of(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID), 1));
        inv.setSlot(inv.hotbarIndex(1),
                ItemStack.of(ItemRegistry.runtimeIdOf(ItemRegistry.SMG_ID), 1));
        inv.add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), 30);

        // ---- 手持手枪 ----
        inv.selectSlot(0);
        assertEquals("手枪", Localization.displayName(inv.selectedStack().item().id()),
                "HUD 枪名必须是当前手持枪的中文名（按 stable id 查表）");
        assertEquals(30, hudReserve(player, combat), "后备读数是共用弹药的计数");
        GunState pistolState = combat.gunFor(player);   // 模拟"拿到枪后正常开工"的惰性建态
        assertEquals(ItemRegistry.PISTOL_ID, pistolState.gun().id(),
                "切到手枪时 GunState 必须是手枪");
        assertEquals(ItemRegistry.PISTOL_AMMO_ID, pistolState.spec().ammoId(),
                "手枪 ammoId = 手枪弹（数据来源）");

        // ---- 切到 SMG ----
        inv.selectSlot(1);
        assertEquals("冲锋枪", Localization.displayName(inv.selectedStack().item().id()),
                "切到 SMG 后枪名必须立刻变为「冲锋枪」—— 切枪同步的可见证据");
        assertEquals(30, hudReserve(player, combat), "两把枪共用弹药，故后备数值相同");

        GunState smgState = combat.gunFor(player);
        assertEquals(ItemRegistry.SMG_ID, smgState.gun().id(),
                "切到 SMG 时 GunState 必须换成 SMG（不是沿用上一把的弹匣状态）");
        assertEquals(ItemRegistry.PISTOL_AMMO_ID, smgState.spec().ammoId(),
                "SMG ammoId 也是手枪弹（v2 §6.1 共用弹药）");

        // 三者的"枪身份"必须相互一致：HUD 名 ↔ GunState ↔ 弹药来源
        assertEquals(smgState.gun().id(), itemIdBehindDisplayName("冲锋枪"),
                "HUD 显示的枪名必须就是当前 GunState 那把枪的中文名");
        assertEquals(smgState.spec().ammoId(),
                SkyIslandGame.reserveAmmoIdOf(inv.selectedStack(), combat.existingGun(player)),
                "HUD 弹药读数所用的 ammoId 必须与当前 GunState 的 spec 同源");
    }

    // ============================================================ 工具

    /** 由中文显示名反查 stable id（"冲锋枪" → "skyisland:smg"）。仅测试内部使用。 */
    private static String itemIdBehindDisplayName(String displayName) {
        for (Item item : ItemRegistry.all()) {
            if (item.isGun() && displayName.equals(Localization.displayName(item.id()))) {
                return item.id();
            }
        }
        return "?";
    }
}

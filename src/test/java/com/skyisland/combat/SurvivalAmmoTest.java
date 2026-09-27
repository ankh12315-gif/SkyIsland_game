package com.skyisland.combat;

import com.skyisland.entity.EntityManager;
import com.skyisland.item.ItemRegistry;
import com.skyisland.player.Player;
import com.skyisland.player.PlayerIntent;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.ui.Localization;
import com.skyisland.world.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 Story 8：<b>资源 → 弹药 → 战斗链</b>（v2 §17 第 8 条 Gate / §19-6 / §19-7 / §14.5）。
 *
 * <h2>这条 Story 的实质</h2>
 * 有限后备的<b>规则</b>在 M3 §12 就已经完整实现并有单测（{@code GunStateAmmoResolutionTest}、
 * {@code CombatCoreTest} 里 {@code new GunState(pistol, false)} 的用例）。Story 8 要做的是
 * <b>切换正式玩法的默认口径</b>，并把它从 {@code GunState} 的全局默认常量
 * 提升为上层显式传入的 Combat Rule / Run Mode 配置（v2 §5.3）。因此本类的断言分三层：
 * <ol>
 *   <li><b>口径接线</b> —— 正式流程（{@code CombatController} 的惰性建态路径）建出的枪
 *       必须是<b>有限</b>后备；Debug 路径显式声明后必须是<b>无限</b>；</li>
 *   <li><b>资源真的会少</b> —— 换弹前后 {@code inventory.countOfItem(ammoId)} 的
 *       <b>计数级</b>断言：装了多少，就必须少多少。这是"弹药是资源"这句话唯一能被举证的形式；</li>
 *   <li><b>耗尽后打不出去</b> —— 后备为 0 时换弹被拒（{@code NO_RESERVE}）且提示文案出现，
 *       弹匣打空后只出空枪（{@code onDryFire}），后备不再被凭空补回来。</li>
 * </ol>
 *
 * <h2>为什么这些断言不能指望 {@code CombatCoreTest}</h2>
 * {@code CombatCoreTest} 直接 {@code new GunState(...)}，绕过了"谁来决定口径"这件事 ——
 * 它证明的是规则本身。口径接线的失效方式恰恰在那一层之外：
 * <b>控制器仍然建无限口径的枪，而每个 {@code GunState} 单测都绿</b>。
 * 所以本类的每一条都从 {@code CombatController} 出发，走 {@code combat.step} 这条真实路径。
 *
 * <p>反向验证见每条用例的注释；两次注入的结论记在 Story 8 简报里。
 */
class SurvivalAmmoTest {

    /** 逻辑步长恒为 1/60 s（与 GameLoop.FIXED_DT 一致）。 */
    private static final double DT = 1.0 / 60.0;

    /** 走完一次 1.2 秒换弹所需的步数（含浮点余量）。 */
    private static final int RELOAD_STEPS = 80;

    private static final String AMMO = ItemRegistry.PISTOL_AMMO_ID;

    private static World world() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    /** 手持某把枪、背包里有 {@code ammoCount} 发手枪弹的玩家。 */
    private static Player armed(String gunStableId, int ammoCount) {
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.inventory().add(ItemRegistry.runtimeIdOf(gunStableId), 1);
        if (ammoCount > 0) {
            player.inventory().add(ItemRegistry.runtimeIdOf(AMMO), ammoCount);
        }
        return player;
    }

    private static final PlayerIntent RELOAD =
            PlayerIntent.combat(0, 0, false, 0, 0, false, false, true);
    private static final PlayerIntent IDLE =
            PlayerIntent.combat(0, 0, false, 0, 0, false, false, false);

    /** 手枪的一次"开火"意图（SINGLE = 按下沿，见 v2 §7.1）。 */
    private static PlayerIntent firePistol() {
        return PlayerIntent.combat(0, 0, false, 0, 0, true, false, false)
                .withAttackPressed(true);
    }

    /** 事件记录器：只记本类用得上的四类回调。 */
    private static final class Recorder implements CombatController.Listener {
        final List<GunState.ReloadOutcome> reloadRequests = new ArrayList<>();
        final List<String> textKeys = new ArrayList<>();
        int reloadCompleted;
        int dryFires;
        int shots;

        @Override
        public void onShotFired(double mx, double my, double mz,
                                double ex, double ey, double ez, boolean hitAnything) {
            shots++;
        }

        @Override
        public void onBlockHit(double x, double y, double z,
                               double nx, double ny, double nz, int blockRuntimeId) {
        }

        @Override
        public void onEntityHit(com.skyisland.entity.Entity entity, int damage, double distance) {
        }

        @Override
        public void onDryFire() {
            dryFires++;
        }

        @Override
        public void onReloadRequest(GunState.ReloadOutcome outcome) {
            reloadRequests.add(outcome);
        }

        @Override
        public void onReloadCompleted(int magazineAmmo, int magazineSize) {
            reloadCompleted++;
        }

        @Override
        public void onMessage(String textKey, Object... args) {
            textKeys.add(textKey);
        }
    }

    private static void step(CombatController combat, World world, Player player,
                             PlayerIntent intent, Recorder recorder) {
        combat.step(world, player, intent, DT, recorder);
    }

    /** 按 R 并把一次换弹走完。 */
    private static void reloadFully(CombatController combat, World world, Player player,
                                    Recorder recorder) {
        step(combat, world, player, RELOAD, recorder);
        for (int i = 0; i < RELOAD_STEPS; i++) {
            step(combat, world, player, IDLE, recorder);
        }
    }

    // ============================================================ ① 口径接线

    /**
     * <b>正式流程（不配置任何东西）建出的枪必须是有限后备</b> —— v2 §19-6 在接线层的断言。
     *
     * <p>{@code CombatController.gunFor} 是产品里唯一的运行期建态点
     * （渲染路径的 {@code existingGun} 不建）。它建的枪就是玩家手里那把枪，
     * 因此这条断言等价于"M3 正式游戏里弹药是资源"。
     *
     * <p><b>反向验证（TEMP_REVERSE_VERIFY）</b>：把 {@code CombatController} 的
     * {@code reserveMode} 字段初值改回 {@code PROTOTYPE}（即 Story 8 之前的口径），
     * 本用例立刻变红。
     */
    @Test
    void combatControllerBuildsGunsWithFiniteReserveByDefault() {
        CombatController combat = new CombatController(new EntityManager());
        assertEquals(GunState.ReserveMode.SURVIVAL, combat.reserveMode(),
                "M3 正式玩法的默认口径是 Survival（有限后备）");

        Player player = armed(ItemRegistry.PISTOL_ID, 24);
        GunState gun = combat.gunFor(player);
        assertNotNull(gun);
        assertFalse(gun.reserveInfinite(),
                "正式流程建出的枪必须是有限后备（v2 §19-6）—— 否则弹药不是资源");
        assertEquals(GunState.ReserveMode.SURVIVAL, gun.reserveMode(),
                "枪状态上的口径必须与控制器配置同源");
    }

    /**
     * <b>Debug / Prototype 路径显式声明后必须是无限后备</b>，且立刻对新老枪状态生效
     * —— v2 §19-7 在接线层的断言。
     *
     * <p>这里额外钉住 {@code setReserveMode} 的"换口径同时丢弃已建状态"这一半：
     * 先按正式口径建出一把枪，再切到原型口径，<b>重新取到的必须是无限口径的那把</b>。
     * 不丢弃的话会读到旧状态，症状是"设了口径但枪没变" —— 一个只在时序上看得出来的失效。
     */
    @Test
    void debugPathCanSwitchToInfiniteReserveAndItTakesEffectImmediately() {
        CombatController combat = new CombatController(new EntityManager());
        Player player = armed(ItemRegistry.PISTOL_ID, 24);

        GunState before = combat.gunFor(player);
        assertFalse(before.reserveInfinite(), "先以正式口径（Survival）建态");

        combat.setReserveMode(GunState.ReserveMode.PROTOTYPE);
        GunState after = combat.gunFor(player);
        assertTrue(after.reserveInfinite(),
                "显式切到 Prototype 后必须是无限后备（v2 §19-7：Debug 模式仍可无限备弹）");
        assertEquals(GunState.ReserveMode.PROTOTYPE, after.reserveMode());

        // 再切回正式口径也必须立刻生效 —— 这条方向同样重要：原型跑完之后
        // 若还留着无限口径，正式玩法就会悄悄回到"不消耗弹药"。
        combat.setReserveMode(GunState.ReserveMode.SURVIVAL);
        assertFalse(combat.gunFor(player).reserveInfinite(),
                "切回 Survival 必须立刻生效（否则正式玩法会悄悄沿用无限口径）");
    }

    // ============================================================ ② 资源真的会少（计数级）

    /**
     * <b>v2 §17-8 Gate 的核心：「资源 → 弹药 → 战斗链成立」。</b>
     *
     * <h2>具体数字（手枪，弹匣容量 12）</h2>
     * <pre>
     *   开局：背包 pistol_ammo = 24，弹匣 = 0（PRD 5.7.1：入手时需先上一次膛）
     *   换弹前：countOfItem(pistol_ammo) = 24  ← reserveBefore
     *   换弹后：countOfItem(pistol_ammo) = 12  ← reserveAfter，真的少了 12 发
     *   弹匣补到 12（即 装载量 load = min(12 − 0, 24) = 12）
     * </pre>
     * 断言把"少了多少"与"装了多少"绑在同一个算式上（{@code reserveBefore − reserveAfter
     * == magazineAmmo}），因此任何一侧对不上都会红 —— 包括"扣了不装"与"装了不扣"。
     *
     * <p><b>反向验证（TEMP_REVERSE_VERIFY）</b>：把 {@code CombatController.reserveMode}
     * 的初值改回 {@code PROTOTYPE}，{@code reserveAfter} 会停在 24（换弹只读后备），
     * 两个断言一起变红。
     */
    @Test
    void survivalReloadSpendsExactlyTheAmmoItLoads() {
        World world = world();
        Player player = armed(ItemRegistry.PISTOL_ID, 24);
        CombatController combat = new CombatController(new EntityManager());
        Recorder recorder = new Recorder();

        // 弹匣为空（0/12），后备 24
        assertEquals(0, combat.gunFor(player).magazineAmmo(), "开局弹匣为空");
        int reserveBefore = player.inventory().countOfItem(AMMO);
        assertEquals(24, reserveBefore, "开局后备 = PRD 5.4.1 的 24 发");

        reloadFully(combat, world, player, recorder);

        GunState gun = combat.gunFor(player);
        int reserveAfter = player.inventory().countOfItem(AMMO);
        assertEquals(1, recorder.reloadCompleted, "换弹必须走完一次");
        assertEquals(12, gun.magazineAmmo(), "弹匣补到容量 12");
        assertEquals(12, reserveAfter,
                "★ 弹药是资源：换弹真的从 Inventory 扣了 12 发（24 → 12）");
        assertEquals(reserveBefore - reserveAfter, gun.magazineAmmo(),
                "★ 装了多少就必须少多少：24 − 12 = 12，两侧同一个算式，"
                        + "「扣了不装」与「装了不扣」都会让这条变红");
    }

    /**
     * 后备不足时<b>部分填充</b>，且扣减量精确等于装载量（PRD 5.4.3 规则③在正式口径下）。
     *
     * <pre>
     *   弹匣 10/12（缺口 2），后备 3 → 装载 load = min(2, 3) = 2
     *   换弹前 countOfItem = 3 → 换弹后 = 1（精确少 2）
     * </pre>
     */
    @Test
    void survivalPartialReloadSpendsOnlyWhatItCouldLoad() {
        World world = world();
        Player player = armed(ItemRegistry.PISTOL_ID, 3);
        CombatController combat = new CombatController(new EntityManager());
        Recorder recorder = new Recorder();

        GunState gun = combat.gunFor(player);
        gun.setMagazineAmmo(10);   // 只留 2 发缺口

        int reserveBefore = player.inventory().countOfItem(AMMO);
        assertEquals(3, reserveBefore);

        reloadFully(combat, world, player, recorder);

        assertEquals(12, gun.magazineAmmo(), "10 + min(2, 3) = 12");
        assertEquals(1, player.inventory().countOfItem(AMMO),
                "★ 只应取走 2 发：3 → 1（部分填充公式 load = min(缺口, 后备) 的落地）");
    }

    /**
     * <b>两把枪抢同一个弹药池</b>（v2 §6.1：pistol 与 SMG 共用 {@code pistol_ammo}）。
     *
     * <p>这条断言的价值在于它把"弹药池"当成一个共享资源来验：SMG 换弹吃掉的后备，
     * 手枪那一边必须立刻看得到。若哪天有人给每把枪配一份独立的计数器
     * （"看起来更干净"的写法），本用例会精确变红 —— 而各自的单枪测试仍然全绿。
     *
     * <pre>
     *   开局后备 24
     *   SMG 换弹（容量 24）→ 装 24、扣 24 → 后备 0
     *   切回手枪 → 后备仍是 0（同一格），按 R 被拒（NO_RESERVE）
     * </pre>
     */
    @Test
    void bothGunsDrawFromTheSameAmmoPool() {
        World world = world();
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        var inv = player.inventory();
        inv.setSlot(inv.hotbarIndex(0), com.skyisland.player.ItemStack.of(
                ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID), 1));
        inv.setSlot(inv.hotbarIndex(1), com.skyisland.player.ItemStack.of(
                ItemRegistry.runtimeIdOf(ItemRegistry.SMG_ID), 1));
        inv.add(ItemRegistry.runtimeIdOf(AMMO), 24);
        inv.selectSlot(1);   // 手持 SMG

        CombatController combat = new CombatController(new EntityManager());
        Recorder recorder = new Recorder();

        GunState smg = combat.gunFor(player);
        assertEquals(ItemRegistry.SMG_ID, smg.gun().id(), "前提：手持 SMG");
        assertEquals(24, smg.magazineSize());

        reloadFully(combat, world, player, recorder);
        // SMG 换弹 1.5 秒 = 90 步 > RELOAD_STEPS(80) —— 再补几步走完 SMG 自己的时间线。
        for (int i = 0; i < 20 && smg.isReloading(); i++) {
            step(combat, world, player, IDLE, recorder);
        }

        assertEquals(24, smg.magazineAmmo(), "SMG 补满到它自己的容量 24");
        assertEquals(0, inv.countOfItem(AMMO),
                "★ SMG 把共用的 24 发全部吃掉了（24 → 0）");

        // 切回手枪：它读同一格后备，因此读到 0
        inv.selectSlot(0);
        GunState pistol = combat.gunFor(player);
        assertEquals(ItemRegistry.PISTOL_ID, pistol.gun().id(), "前提：切回手枪");
        assertEquals(0, inv.countOfItem(AMMO), "★ 手枪看到的后备与 SMG 是同一格（0）");

        step(combat, world, player, RELOAD, recorder);
        assertEquals(GunState.ReloadOutcome.NO_RESERVE,
                recorder.reloadRequests.get(recorder.reloadRequests.size() - 1),
                "★ 弹药池被 SMG 吃空后，手枪按 R 必须被拒（共用弹药池的直接后果）");
    }

    // ============================================================ ③ 耗尽后打不出去

    /**
     * 后备耗尽后按 R 被拒，且给出「没有后备弹药」提示（PRD 5.4.3 规则②）。
     *
     * <p><b>这是正式玩法里玩家会真实遇到的一步</b>，而 M2.1 之后它一直是"不可达"的
     * （无限口径下 {@code NO_RESERVE} 永不返回）。Story 8 把它恢复成常态。
     */
    @Test
    void survivalRefusesReloadOnceTheAmmoPoolIsDry() {
        World world = world();
        Player player = armed(ItemRegistry.PISTOL_ID, 0);   // 背包里一发都没有
        CombatController combat = new CombatController(new EntityManager());
        Recorder recorder = new Recorder();

        GunState gun = combat.gunFor(player);
        gun.setMagazineAmmo(3);

        step(combat, world, player, RELOAD, recorder);

        assertEquals(GunState.ReloadOutcome.NO_RESERVE,
                recorder.reloadRequests.get(recorder.reloadRequests.size() - 1),
                "★ 后备为 0 → 拒绝换弹（v2 §19-6：弹药是资源，打光就是打光）");
        assertFalse(gun.isReloading(), "被拒绝后不得进入换弹态");
        assertTrue(recorder.textKeys.contains(Localization.MSG_NO_RESERVE),
                "必须给出「没有后备弹药」文案（Localization key，不是硬编码字符串）");
    }

    /**
     * 弹匣打空 + 后备打空 → 只能出空枪，<b>后备不会被凭空补回来</b>。
     *
     * <p>这条是这个 Story 最容易"看起来对"的地方：打空后 {@code dryFires} 涨了、
     * 弹匣仍是 0，单看不出来问题。真正要守住的是<b>计数不回升</b> ——
     * 若有人把"无限"实现成"扣到 0 再补回来"，或者把 {@code availableReserve}
     * 写成无限，那么这里会读到 24 之外的数。
     *
     * <pre>
     *   开局：后备 24、弹匣 0
     *   按 R 装满 → 弹匣 12、后备 12
     *   打空 12 发 → 弹匣 0、后备仍是 12（射击不碰后备）
     *   再按 R → 弹匣回到 12、后备 0
     *   再打空 + 再按 R → NO_RESERVE，后备恒为 0，空枪只累加 dryFires
     * </pre>
     */
    @Test
    void survivalDryFireNeverRefillsTheReserve() {
        World world = world();
        Player player = armed(ItemRegistry.PISTOL_ID, 24);
        CombatController combat = new CombatController(new EntityManager());
        Recorder recorder = new Recorder();

        // ① 装满弹匣：24 → 12
        reloadFully(combat, world, player, recorder);
        assertEquals(12, player.inventory().countOfItem(AMMO), "① 换弹后后备 12");

        // ② 打空弹匣（射击只动弹匣，不动后备）
        for (int i = 0; i < 12; i++) {
            step(combat, world, player, firePistol(), recorder);
            for (int k = 0; k < 16; k++) {   // 0.25 秒射速节流 = 15 步
                step(combat, world, player, IDLE, recorder);
            }
        }
        assertEquals(0, combat.gunFor(player).magazineAmmo(), "② 弹匣已打空");
        assertEquals(12, player.inventory().countOfItem(AMMO), "② 射击不消耗后备（弹匣才是唯一弹药去处）");

        // ③ 再装满：12 → 0
        reloadFully(combat, world, player, recorder);
        assertEquals(12, combat.gunFor(player).magazineAmmo(), "③ 第二次换弹补满");
        assertEquals(0, player.inventory().countOfItem(AMMO), "③ 后备被吃干（12 → 0）");

        // ④ 再打空 + 再按 R：必须被拒，且后备恒为 0
        for (int i = 0; i < 12; i++) {
            step(combat, world, player, firePistol(), recorder);
            for (int k = 0; k < 16; k++) {
                step(combat, world, player, IDLE, recorder);
            }
        }
        int dryBefore = recorder.dryFires;
        step(combat, world, player, RELOAD, recorder);
        assertEquals(GunState.ReloadOutcome.NO_RESERVE,
                recorder.reloadRequests.get(recorder.reloadRequests.size() - 1),
                "④ 弹尽粮绝后按 R 被拒");
        assertEquals(0, player.inventory().countOfItem(AMMO),
                "★ 后备不会被凭空补回来");
        assertFalse(combat.gunFor(player).reserveInfinite(),
                "★ 正式口径下不存在『无限』这条路");

        // ⑤ 空枪：不产生击发、不消耗任何弹药
        step(combat, world, player, firePistol(), recorder);
        assertEquals(dryBefore + 1, recorder.dryFires, "⑤ 空弹匣只出空枪");
        assertEquals(0, player.inventory().countOfItem(AMMO), "⑤ 空枪不得动后备");
    }

    // ============================================================ ④ Debug 口径：不扣背包

    /**
     * <b>v2 §19-7：Debug 口径下换弹补满弹匣，且背包一发不少。</b>
     *
     * <p>与 {@link #survivalReloadSpendsExactlyTheAmmoItLoads} 构成一对对照：
     * <b>同一条换弹路径、同一个玩家配置，只差口径</b> ——
     * 一个扣 0 发、一个扣 12 发。两条同时绿，才说明"口径"这件事真的起了作用，
     * 而不是某处写死了一个数。
     *
     * <p>数字：后备恒为 24 与 5（两种起始量都试），弹匣一律补满，计数一次不变。
     */
    @Test
    void debugReserveReloadFillsTheMagazineAndSpendsNothing() {
        for (int startingAmmo : new int[]{24, 5}) {
            World world = world();
            Player player = armed(ItemRegistry.PISTOL_ID, startingAmmo);
            CombatController combat = new CombatController(new EntityManager());
            combat.setReserveMode(GunState.ReserveMode.PROTOTYPE);
            Recorder recorder = new Recorder();

            GunState gun = combat.gunFor(player);
            gun.setMagazineAmmo(1);
            int before = player.inventory().countOfItem(AMMO);

            reloadFully(combat, world, player, recorder);

            assertEquals(12, gun.magazineAmmo(),
                    "Debug 口径：后备 " + startingAmmo + " 也补满 12（不是 1 + min(11, 剩余量)）");
            assertEquals(before, player.inventory().countOfItem(AMMO),
                    "★ Debug 口径换弹只读后备、不写背包（起始 " + startingAmmo + " 发，一发不少）");
            assertEquals(1, recorder.reloadCompleted);
        }
    }

    /**
     * Debug 口径下<b>空背包也能换弹</b> —— {@code NO_RESERVE} 不可达（§19-7 的另一半）。
     *
     * <p>与 {@link #survivalRefusesReloadOnceTheAmmoPoolIsDry} 互为对照：
     * 同样是"后备 0"，正式口径拒绝、Debug 口径照补。
     */
    @Test
    void debugReserveMakesNoReserveUnreachable() {
        World world = world();
        Player player = armed(ItemRegistry.PISTOL_ID, 0);
        CombatController combat = new CombatController(new EntityManager());
        combat.setReserveMode(GunState.ReserveMode.PROTOTYPE);
        Recorder recorder = new Recorder();

        GunState gun = combat.gunFor(player);
        gun.setMagazineAmmo(3);

        reloadFully(combat, world, player, recorder);

        assertEquals(12, gun.magazineAmmo(), "Debug 口径：空背包也补满 12");
        assertEquals(0, player.inventory().countOfItem(AMMO), "换弹不会往背包里塞东西");
        assertFalse(recorder.textKeys.contains(Localization.MSG_NO_RESERVE),
                "★ Debug 口径下不得出现「没有后备弹药」提示");
    }
}

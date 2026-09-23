package com.skyisland.combat;

import com.skyisland.entity.MeleeMonster;
import com.skyisland.item.ItemRegistry;
import com.skyisland.physics.AABB;
import com.skyisland.player.Inventory;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import com.skyisland.world.block.BlockRegistry;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 战斗核心测试：射线求交 / Hitscan 最近命中 / 距离衰减 / 换弹规则。
 *
 * <p>这四条正好覆盖 M2 通过标准的第 2、3、4、5 条
 * （伤害 8 / 换弹 1.2 秒 / Hitscan 最近命中且不隔墙 / 距离衰减）。
 */
class CombatCoreTest {

    // ============================================================ RayBox

    @Test
    void rayBoxHitsTheNearFaceNotTheFarOne() {
        AABB box = new AABB(0, 0, 0, 1, 1, 1);
        Vector3d origin = new Vector3d(-5, 0.5, 0.5);
        Vector3d dir = new Vector3d(1, 0, 0);
        assertEquals(5.0, RayBox.intersect(origin, dir, box), 1e-9,
                "应返回近面距离 5，而不是远面 6");
    }

    @Test
    void rayBoxMissesWhenBoxIsBehind() {
        AABB box = new AABB(0, 0, 0, 1, 1, 1);
        Vector3d origin = new Vector3d(-5, 0.5, 0.5);
        assertEquals(-1, RayBox.intersect(origin, new Vector3d(-1, 0, 0), box),
                "盒子在身后不得命中");
    }

    @Test
    void rayBoxMissesWhenParallelAndOutside() {
        AABB box = new AABB(0, 0, 0, 1, 1, 1);
        // 沿 X 轴前进，但 y = 5 远高于盒子
        assertEquals(-1, RayBox.intersect(new Vector3d(-5, 5, 0.5), new Vector3d(1, 0, 0), box));
        // 沿 X 轴前进，x 方向平行但 z 在盒外
        assertEquals(-1, RayBox.intersect(new Vector3d(-5, 0.5, 9), new Vector3d(1, 0, 0), box));
    }

    @Test
    void rayBoxDetectsOriginInsideTheBox() {
        AABB box = new AABB(0, 0, 0, 1, 1, 1);
        assertEquals(0.0, RayBox.intersect(new Vector3d(0.5, 0.5, 0.5), new Vector3d(1, 0, 0), box),
                1e-9, "起点在盒内时最近交点距离为 0");
    }

    // ============================================================ 距离衰减（M2 通过标准第 5 条）

    @Test
    void noFalloffWithinEffectiveRange() {
        for (double d = 0; d <= 32.0; d += 4) {
            assertEquals(1.0, DamageFalloff.multiplier(d, 32), 1e-12,
                    "有效射程内（" + d + " 格）必须是 100% 伤害");
        }
    }

    @Test
    void falloffIsContinuousAtTheRangeBoundary() {
        assertEquals(1.0, DamageFalloff.multiplier(32.0, 32), 1e-12,
                "恰好 32 格仍为 100%（PRD：有效射程内 100%）");
        assertEquals(0.9, DamageFalloff.multiplier(33.0, 32), 1e-9,
                "超出 1 格 → ×0.9");
    }

    @Test
    void falloffFollowsNineTenthsPerBlock() {
        assertEquals(0.81, DamageFalloff.multiplier(34, 32), 1e-9, "超 2 格 → 0.9²");
        assertEquals(Math.pow(0.9, 5), DamageFalloff.multiplier(37, 32), 1e-12, "超 5 格 → 0.9⁵");
    }

    @Test
    void falloffClampsAtTwentyPercent() {
        assertEquals(DamageFalloff.MIN_MULTIPLIER, DamageFalloff.multiplier(1000, 32), 1e-12,
                "PRD 5.4.3：最低退至 20%");
        // 0.9^n = 0.2 大约在 n = 15.3，因此超过 15 格就开始触底
        assertEquals(0.20, DamageFalloff.multiplier(48, 32), 1e-9);
    }

    @Test
    void pistolDamageIsExactlyEightWithinRange() {
        // M2 通过标准第 2 条：手枪单发伤害 8（偏差 ≤ 5%）
        for (double d = 0; d <= 32; d += 2) {
            assertEquals(8, DamageFalloff.damage(8, d, 32),
                    "32 格内每发必须是 8 点伤害（本次距离 " + d + "）");
        }
    }

    @Test
    void outOfRangeDamageDecaysButNeverDropsToZero() {
        assertEquals(7, DamageFalloff.damage(8, 33, 32), "8 × 0.9 = 7.2 → 向下取整 7");
        assertEquals(1, DamageFalloff.damage(8, 200, 32),
                "即使远到 20% 下限，命中也不得是 0 伤害（否则玩家以为没打中）");
    }

    // ============================================================ Hitscan（M2 通过标准第 4 条）

    /** 射线水平指向一只站在空地上的怪物：命中实体。 */
    @Test
    void hitscanHitsAnEntityInTheOpen() {
        World world = TestWorlds.flatWorld();
        MeleeMonster monster = new MeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);

        // 从怪物正前方 4 格、与它躯干同高，朝 -Z 射
        Hitscan.Result r = Hitscan.resolve(world,
                new Vector3d(0.5, TestWorlds.SURFACE_FEET_Y + 1.0, 5.0),
                new Vector3d(0, 0, -1), 16, List.of(monster));

        assertTrue(r.hitEntity(), "开阔地带的怪物必须能被命中");
        assertSame(monster, r.entity());
        assertNull(r.blockHit());
        assertTrue(r.distance() > 0 && r.distance() < 5,
                "命中距离应落在 (0,5)，实际 " + r.distance());
    }

    /**
     * M2 通过标准第 4 条的后半句：<b>隔着实体方块不能命中怪物</b>。
     *
     * <p>怪物在墙后、射线先撞到墙 —— 必须记为方块命中，且不得结算伤害。
     * 这是 Hitscan 最容易实现错的一条：先射实体、命中就结算的写法会让玩家隔墙杀怪。
     */
    @Test
    void hitscanCannotShootThroughASolidWall() {
        int stone = BlockRegistry.stone().runtimeId();
        int feetY = (int) TestWorlds.SURFACE_FEET_Y;
        // 一堵 2 格高的墙立在 z = 2
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(0, feetY, 2, stone),
                TestWorlds.cell(0, feetY + 1, 2, stone),
                TestWorlds.cell(0, feetY - 1, 2, stone));

        MeleeMonster monster = new MeleeMonster(0.5, feetY, 0.5);

        Hitscan.Result r = Hitscan.resolve(world,
                new Vector3d(0.5, feetY + 0.5, 5.5),
                new Vector3d(0, 0, -1), 16, List.of(monster));

        assertFalse(r.hitEntity(), "隔着实体方块不得命中怪物");
        assertNotNull(r.blockHit(), "应记为方块命中");
        assertEquals(2, r.blockHit().blockZ(), "命中的是那堵墙（z=2）");
        assertTrue(r.distance() < 4.7,
                "方块命中距离必须小于到怪物的距离 4.7，实际 " + r.distance());
    }

    /** 走廊两侧都没有实体/方块时返回未命中。 */
    @Test
    void hitscanMissesWhenNothingIsInTheWay() {
        World world = TestWorlds.flatWorld();
        Hitscan.Result r = Hitscan.resolve(world,
                new Vector3d(0.5, TestWorlds.SURFACE_FEET_Y + 3.0, 0.5),
                new Vector3d(0, 1, 0), 8, List.of());
        assertFalse(r.hitAnything(), "朝天开枪不应命中任何东西");
        assertEquals(Double.POSITIVE_INFINITY, r.distance());
    }

    @Test
    void hitscanIgnoresDeadEntities() {
        World world = TestWorlds.flatWorld();
        MeleeMonster monster = new MeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        monster.hurt(999);
        assertFalse(monster.isAlive());

        Hitscan.Result r = Hitscan.resolve(world,
                new Vector3d(0.5, TestWorlds.SURFACE_FEET_Y + 1.0, 5.0),
                new Vector3d(0, 0, -1), 16, List.of(monster));
        assertFalse(r.hitEntity(), "已死亡的实体不应再吃伤害");
    }

    /** 最近的实体优先（两怪前后排列，命中近的那只）。 */
    @Test
    void hitscanPicksTheNearestEntity() {
        World world = TestWorlds.flatWorld();
        MeleeMonster near = new MeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, 3.0);
        MeleeMonster far = new MeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, 1.0);

        Hitscan.Result r = Hitscan.resolve(world,
                new Vector3d(0.5, TestWorlds.SURFACE_FEET_Y + 1.0, 8.0),
                new Vector3d(0, 0, -1), 16, List.of(far, near));

        assertSame(near, r.entity(), "必须命中沿射线更近的那一只（z=3 比 z=1 近）");
    }

    // ============================================================ 换弹（M2 通过标准第 3 条 + A3 规则）

    private static final String AMMO = ItemRegistry.PISTOL_AMMO_ID;

    private static Inventory inventoryWithAmmo(int amount) {
        Inventory inv = new Inventory();
        inv.add(ItemRegistry.pistolAmmo().runtimeId(), amount);
        return inv;
    }

    @Test
    void pistolReloadTakesExactlyOnePointTwoSeconds() {
        assertEquals(1.2, ItemRegistry.pistol().gun().reloadSeconds(), 1e-9,
                "M2 通过标准第 3 条：手枪换弹 1.2 秒");

        Inventory inv = inventoryWithAmmo(12);
        GunState gun = GunState.forPistol();
        assertEquals(GunState.ReloadOutcome.STARTED, gun.tryStartReload(inv));

        gun.tick(1.19, inv);
        assertTrue(gun.isReloading(), "1.19 秒时仍在换弹");
        assertEquals(0, gun.magazineAmmo(), "完成前不得转移弹药（A3 规则④）");
        assertEquals(12, inv.countOfItem(AMMO), "完成前后备弹药不得减少");

        gun.tick(0.02, inv);
        assertFalse(gun.isReloading(), "1.21 秒时必须已完成");
        assertEquals(12, gun.magazineAmmo());
        // M2.1-A：产品口径从"有限后备"改为"无限后备"，因此完成换弹时<b>不从背包取弹</b>。
        // 这条断言在 M2.1 之前是 assertEquals(0, ...)。它随产品口径一起改，
        // 不是为了让绿灯亮起来而放宽 —— 有限口径的那条语义仍在
        // partialReloadFillsExactlyWhatIsAvailable 里用显式构造继续被断言。
        assertEquals(12, inv.countOfItem(AMMO),
                "M2.1 无限后备：换弹只读后备、不写背包，12 发一发不少");
    }

    /** A3 规则①：满弹匣按 R 无操作（v0.3.1 的"弹出余弹"口径已作废）。 */
    @Test
    void reloadingAFullMagazineDoesNothing() {
        Inventory inv = inventoryWithAmmo(50);
        GunState gun = GunState.forPistol();
        gun.setMagazineAmmo(12);

        assertEquals(GunState.ReloadOutcome.ALREADY_FULL, gun.tryStartReload(inv));
        assertFalse(gun.isReloading());
        assertEquals(12, gun.magazineAmmo(), "满弹匣不得被换弹改变");
        assertEquals(50, inv.countOfItem(AMMO), "满弹匣换弹不得消耗弹药");
    }

    /**
     * A3 规则②的另一侧：后备弹药为 0 时不得开始换弹。
     *
     * <p><b>M2.1 起这条规则只在有限口径下可达</b>（产品默认是无限后备，
     * 此时 NO_RESERVE 永远不会被返回）。因此本用例<b>显式</b>构造有限口径，
     * 而不是把产品默认改回有限 —— 后者会让"玩家在原型阶段打到第 25 发就断供"
     * 重新成为常态。保留它的目的是让 PRD 5.4.3 规则②一直是活代码。
     */
    @Test
    void reloadWithoutReserveAmmoIsRefused() {
        Inventory inv = new Inventory(); // 空背包
        GunState gun = GunState.forPistolWithFiniteReserve();
        gun.setMagazineAmmo(3);

        assertEquals(GunState.ReloadOutcome.NO_RESERVE, gun.tryStartReload(inv));
        assertFalse(gun.isReloading());
    }

    /**
     * A3 规则③：后备不足时部分填充 {@code load = min(容量 − 弹匣内, 后备)}。
     *
     * <p>与 {@link #reloadWithoutReserveAmmoIsRefused} 同理：M2.1 起规则③只在
     * 有限口径下可达，因此这里显式构造有限口径，好让这条规则继续被真的执行到。
     */
    @Test
    void partialReloadFillsExactlyWhatIsAvailable() {
        Inventory inv = inventoryWithAmmo(3);
        GunState gun = GunState.forPistolWithFiniteReserve();
        gun.setMagazineAmmo(10);

        assertEquals(GunState.ReloadOutcome.STARTED, gun.tryStartReload(inv));
        gun.tick(1.2, inv);

        assertEquals(12, gun.magazineAmmo(), "10 + min(2, 3) = 12");
        assertEquals(1, inv.countOfItem(AMMO), "只应取走 2 发");
    }

    // ============================================================ M2.1-A：无限后备口径

    /**
     * 产品默认口径就是无限后备 —— 这条断言是 M2.1-A 的<b>口径护栏</b>。
     *
     * <p>它守的不是某个行为，而是"这一局弹药到底是资源还是无限"这个决定本身：
     * 有人把 {@code INFINITE_RESERVE_DEFAULT} 改回 false（或让 {@code forPistol()}
     * 不再走它）时，这里必须变红，而不是等到试玩时才发现"打到第 25 发就没了"。
     */
    @Test
    void theProductDefaultIsInfiniteReserve() {
        assertTrue(GunState.INFINITE_RESERVE_DEFAULT, "M2.1-A 的产品口径就是无限后备");
        assertTrue(GunState.forPistol().reserveInfinite(), "forPistol() 必须走产品口径");
        assertFalse(GunState.forPistolWithFiniteReserve().reserveInfinite(),
                "有限口径必须能被显式构造出来，否则 PRD 5.4.3 规则②③会退化成死代码");
    }

    /** 无限后备：换弹把弹匣补满，背包里的弹药一个数都不动。 */
    @Test
    void infiniteReserveFillsTheMagazineAndLeavesTheInventoryUntouched() {
        Inventory inv = inventoryWithAmmo(7);
        GunState gun = GunState.forPistol();
        gun.setMagazineAmmo(1);

        assertEquals(GunState.ReloadOutcome.STARTED, gun.tryStartReload(inv));
        gun.tick(1.2, inv);

        assertEquals(12, gun.magazineAmmo(), "无限后备下弹匣补满（不是 1 + min(11, 7)）");
        assertEquals(7, inv.countOfItem(AMMO), "背包里的 7 发一发不少");
        assertEquals(1, gun.reloadsCompleted());
    }

    /**
     * 无限后备使 {@link GunState.ReloadOutcome#NO_RESERVE} <b>不可达</b>。
     *
     * <p>这是"无限"这个词在代码里的准确含义：不是"后备很大"，而是
     * "『没子弹可装』这句话不成立"。空背包也必须能换弹。
     */
    @Test
    void infiniteReserveMakesNoReserveUnreachable() {
        Inventory empty = new Inventory();
        GunState gun = GunState.forPistol();
        gun.setMagazineAmmo(3);

        assertEquals(GunState.ReloadOutcome.STARTED, gun.tryStartReload(empty),
                "空背包也必须能换弹 —— 无限后备下不存在『后备 = 0』");
        gun.tick(1.2, empty);

        assertEquals(12, gun.magazineAmmo(), "空背包也补满 12");
        assertEquals(0, empty.countOfItem(AMMO), "换弹不会往背包里塞东西");
        assertEquals(1, gun.reloadsCompleted());
    }

    /**
     * M2.2 修订：换弹进度<b>只由时间驱动</b>，玩家移动不再影响它。
     *
     * <h2>这条用例替代了什么</h2>
     * M2.2 之前这里是 {@code movingCancelsReloadAndLosesNothing}：断言
     * {@code gun.tick(0.5, true, inv)} 会取消换弹（旧 A3 规则⑤「移动打断换弹 = 取消」）。
     * 该规则已被<b>废止</b> —— PRD 5.4.3 改写为「移动不打断换弹」，
     * {@link GunState#tick} 移除了 {@code moving} 参数，{@code cancelReload()} 与
     * {@code reloadsCancelled} 一并删除。
     *
     * <p><b>为什么"移动不打断"这条性质在 GunState 这一层只能被间接断言：</b>
     * 移动是 {@code PlayerIntent} 的概念，{@code GunState} 根本收不到它。
     * "收不到"没法用行为断言表达，只能靠"参数表里没有它"这个结构事实本身。
     * 因此这里守住的是它的另一半：<b>换弹一旦开始，就走完一条只与时间有关的时间线</b> ——
     * 不多不少、中途不因任何外部读数而变形、弹药只在完成这一刻转移。
     *
     * <p>真正的"边走边换"行为断言在两处：
     * {@code CombatControllerTest#walkingDoesNotInterruptReload}（驱动带移动意图的
     * {@code combat.step}，那里才有"移动"这个输入）与游戏内自测
     * {@code RELOAD_WHILE_WALKING} 阶段（按 R 之后一路按 W，断言换弹走完全程）。
     *
     * <p><b>为什么按 {@code 1/60} 逐步行进，而不是一次 {@code tick(1.2)}：</b>
     * 换弹 1.2 秒 = 72 个固定步（{@code GameLoop.FIXED_DT}）—— 这个算式是游戏内自测
     * 预算（{@code STAGE_BUDGET}）的前提。用固定步长走一遍，等于把该前提也钉住。
     */
    @Test
    void reloadRunsToCompletionOnAFixedStepClock() {
        Inventory inv = inventoryWithAmmo(12);
        GunState gun = GunState.forPistol();
        gun.setMagazineAmmo(3);

        assertEquals(GunState.ReloadOutcome.STARTED, gun.tryStartReload(inv));
        for (int i = 0; i < 71; i++) {
            gun.tick(1.0 / 60.0, inv);
        }
        assertTrue(gun.isReloading(), "71 步 = 1.1833 s，仍在换弹");
        assertEquals(3, gun.magazineAmmo(), "完成前不得转移弹药（A3 规则④）");
        assertEquals(12, inv.countOfItem(AMMO), "完成前后备弹药不得减少");
        assertEquals(0, gun.reloadsCompleted());

        // 第 72 步 = 1.2000 s 是理论完成点。倒计时是逐步累减 dt，
        // 浮点误差可能把完成推到第 73 步（与 M2CombatSelfTest 的预算口径一致），
        // 因此这里允许一个步长的余量 —— 但绝不允许"永远不完成"。
        gun.tick(1.0 / 60.0, inv);
        if (gun.isReloading()) {
            gun.tick(1.0 / 60.0, inv);
        }
        assertFalse(gun.isReloading(), "第 72~73 步之内换弹必须完成（1.2 s = 72 步）");
        assertEquals(12, gun.magazineAmmo(), "完成后补满 12");
        assertEquals(12, inv.countOfItem(AMMO),
                "M2.1 无限后备：换弹只读后备、不写背包，12 发一发不少");
        assertEquals(1, gun.reloadsCompleted());
    }

    /** 换弹期间不得开枪（PRD 5.4.3；与"移动打不打断换弹"无关，M2.2 未改动这条）。 */
    @Test
    void cannotFireWhileReloading() {
        Inventory inv = inventoryWithAmmo(12);
        GunState gun = GunState.forPistol();
        gun.setMagazineAmmo(2);
        gun.tryStartReload(inv);

        assertEquals(GunState.ShotOutcome.RELOADING, gun.tryFire());
        assertEquals(2, gun.magazineAmmo(), "换弹期间开火不得消耗弹匣");
    }

    @Test
    void dryFireIsReportedAndDoesNotConsumeAnything() {
        Inventory inv = inventoryWithAmmo(5);
        GunState gun = GunState.forPistol();   // 弹匣为空

        assertEquals(GunState.ShotOutcome.NO_AMMO, gun.tryFire());
        assertEquals(1, gun.dryFires(), "空枪必须被计数，HUD 据此提示「弹药不足」");
        assertEquals(0, gun.shotsFired());
        assertEquals(5, inv.countOfItem(AMMO), "空枪不得动后备弹药");
    }

    @Test
    void fireRateThrottlesToFourShotsPerSecond() {
        Inventory inv = inventoryWithAmmo(12);
        GunState gun = GunState.forPistol();
        gun.setMagazineAmmo(2);

        assertEquals(GunState.ShotOutcome.FIRED, gun.tryFire());
        assertEquals(1, gun.magazineAmmo());
        assertEquals(GunState.ShotOutcome.COOLDOWN, gun.tryFire(), "同一瞬间不得连发");
        assertEquals(1, gun.magazineAmmo());

        gun.tick(0.25, inv);
        assertEquals(GunState.ShotOutcome.FIRED, gun.tryFire(), "0.25 秒后才允许第二发");
        assertEquals(0, gun.magazineAmmo());
        assertEquals(2, gun.shotsFired());
    }

    @Test
    void gunStateRejectsNonGunItems() {
        try {
            new GunState(ItemRegistry.coal());   // 煤炭不是枪械
            org.junit.jupiter.api.Assertions.fail("非枪械物品不得构造 GunState");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("枪械"), "异常信息应说明原因");
        }
    }

    /**
     * 换弹进度必须是"已过时间 / 总时长"的线性映射。
     *
     * <p>{@link GunState#reloadProgress01()} 的定义是 {@code 1 - 剩余/总时长}，
     * 所以每次 tick(dt) 之后读到的进度是<b>累计</b>已走的时间，不是这一次 dt。
     * 断言必须按累计值写：0.3 s → 25%，再 0.3 s（共 0.6 s）→ 50%。
     */
    @Test
    void reloadProgressIsMonotonic() {
        Inventory inv = inventoryWithAmmo(12);
        GunState gun = GunState.forPistol();
        gun.setMagazineAmmo(0);
        gun.tryStartReload(inv);

        double p0 = gun.reloadProgress01();
        gun.tick(0.3, inv);
        double p1 = gun.reloadProgress01();
        gun.tick(0.3, inv);
        double p2 = gun.reloadProgress01();

        assertEquals(0.0, p0, 1e-9, "刚开始换弹时进度为 0");
        assertTrue(p0 < p1 && p1 < p2, "换弹进度必须单调递增");
        assertEquals(0.25, p1, 1e-9, "0.3 秒 / 1.2 秒 = 25%");
        assertEquals(0.5, p2, 1e-9, "累计 0.6 秒 / 1.2 秒 = 50%");
    }
}

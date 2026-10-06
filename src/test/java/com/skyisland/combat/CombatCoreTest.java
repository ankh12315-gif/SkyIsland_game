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

    /**
     * PRD 5.4.3 基线口径：每超 1 格 ×0.9、最低退至 20%（手枪 / 冲锋枪在注册表里的取值）。
     *
     * <p><b>这里写成测试内的字面量，而不是引用 {@code DamageFalloff} 的常量</b> ——
     * 那正是这次 M3 接线修正要拆掉的东西：常量放在生产类里，就会有人拿它当"规格"，
     * 而真正的规格在 PRD 表格里、在每把枪的注册项里。
     * 本类断言的因此是"公式（PRD 的文字）"，注册项与公式的一致性由
     * {@code ItemRegistryTest} 与 {@code WeaponDataWiringTest} 分开举证。
     */
    private static final double BASELINE_PER_UNIT = 0.9;
    private static final double BASELINE_FLOOR = 0.20;

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
            assertEquals(1.0, DamageFalloff.multiplier(d, 32, BASELINE_PER_UNIT, BASELINE_FLOOR),
                    1e-12, "有效射程内（" + d + " 格）必须是 100% 伤害");
        }
    }

    @Test
    void falloffIsContinuousAtTheRangeBoundary() {
        assertEquals(1.0, DamageFalloff.multiplier(32.0, 32, BASELINE_PER_UNIT, BASELINE_FLOOR),
                1e-12, "恰好 32 格仍为 100%（PRD：有效射程内 100%）");
        assertEquals(0.9, DamageFalloff.multiplier(33.0, 32, BASELINE_PER_UNIT, BASELINE_FLOOR),
                1e-9, "超出 1 格 → ×0.9");
    }

    @Test
    void falloffFollowsNineTenthsPerBlock() {
        assertEquals(0.81, DamageFalloff.multiplier(34, 32, BASELINE_PER_UNIT, BASELINE_FLOOR),
                1e-9, "超 2 格 → 0.9²");
        assertEquals(Math.pow(0.9, 5),
                DamageFalloff.multiplier(37, 32, BASELINE_PER_UNIT, BASELINE_FLOOR),
                1e-12, "超 5 格 → 0.9⁵");
    }

    @Test
    void falloffClampsAtTwentyPercent() {
        assertEquals(BASELINE_FLOOR,
                DamageFalloff.multiplier(1000, 32, BASELINE_PER_UNIT, BASELINE_FLOOR), 1e-12,
                "PRD 5.4.3：最低退至 20%");
        // 0.9^n = 0.2 大约在 n = 15.3，因此超过 15 格就开始触底
        assertEquals(0.20, DamageFalloff.multiplier(48, 32, BASELINE_PER_UNIT, BASELINE_FLOOR),
                1e-9);
    }

    /**
     * ★ M3 接线修正的核心断言：<b>衰减必须由传入的参数决定，而不是由生产类里的常量决定。</b>
     *
     * <p>霰弹枪在 PRD 5.4.3 里的口径是"超出 12 格每格 ×0.8、最低 15%"。
     * 这条用同一把"基础伤害 8、射程 12"的假设枪，分别按霰弹口径与手枪口径结算，
     * 断言两者在同一距离上给出<b>不同</b>的伤害 ——
     * 若 {@code DamageFalloff} 还像 M3 之前那样把 0.9 / 0.20 写死，
     * 第二个参数就会被无声忽略，这条断言立刻变红。
     */
    @Test
    void falloffCurveComesFromTheParametersNotFromAConstant() {
        double distance = 25.0;
        assertEquals(8, DamageFalloff.damage(8, distance, 32, BASELINE_PER_UNIT, BASELINE_FLOOR),
                "25 格在 32 格射程内 → 100% → 8 点伤害");
        // 同一把"基础 8、射程 12"的枪，按霰弹口径（0.8 / 0.15）结算：
        // 超出 13 格 → 0.8^13 ≈ 0.0549，被 0.15 截断 → 8 × 0.15 = 1.2 → 保底 1。
        int shotgunCaliber = DamageFalloff.damage(8, 25.0, 12, 0.8, 0.15);
        // 按手枪口径（0.9 / 0.20）算同一发：射程 12 → 超 13 格 → 0.9^13 ≈ 0.2542 →
        // 8 × 0.2542 ≈ 2.03 → 2。两者必须不同。
        int pistolCaliber = DamageFalloff.damage(8, 25.0, 12, BASELINE_PER_UNIT, BASELINE_FLOOR);
        assertEquals(1, shotgunCaliber, "霰弹口径下限 15% → 8 × 0.15 = 1.2 → 1");
        assertEquals(2, pistolCaliber, "手枪口径 0.9^13 → 8 × 0.2542 ≈ 2.03 → 2");
        assertTrue(shotgunCaliber != pistolCaliber,
                "同一距离上两种口径必须给出不同伤害，否则说明参数根本没被读");
    }

    @Test
    void pistolDamageIsExactlyEightWithinRange() {
        // M2 通过标准第 2 条：手枪单发伤害 8（偏差 ≤ 5%）
        for (double d = 0; d <= 32; d += 2) {
            assertEquals(8, DamageFalloff.damage(8, d, 32, BASELINE_PER_UNIT, BASELINE_FLOOR),
                    "32 格内每发必须是 8 点伤害（本次距离 " + d + "）");
        }
    }

    @Test
    void outOfRangeDamageDecaysButNeverDropsToZero() {
        assertEquals(7, DamageFalloff.damage(8, 33, 32, BASELINE_PER_UNIT, BASELINE_FLOOR),
                "8 × 0.9 = 7.2 → 向下取整 7");
        assertEquals(1, DamageFalloff.damage(8, 200, 32, BASELINE_PER_UNIT, BASELINE_FLOOR),
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

    /**
     * 换弹时长与"完成前不转移"这两条<b>与后备口径无关</b>：两条口径下都成立。
     *
     * <p>因此这里<b>显式</b>选无限口径（{@code PROTOTYPE}）——
     * 目的是把"验换弹时长规则"与"正式玩法默认是什么口径"解耦。
     * 只写单参构造的话，本用例的语义会跟着 {@code CombatController} 的默认值一起漂移：
     * M2.1 时它暗中验的是"无限后备不扣背包"，Story 8 之后又会变成"有限后备扣背包" ——
     * 同一个用例名底下换了被测性质，而看名字看不出来。
     */
    @Test
    void pistolReloadTakesExactlyOnePointTwoSeconds() {
        assertEquals(1.2, ItemRegistry.pistol().gun().reloadSeconds(), 1e-9,
                "M2 通过标准第 3 条：手枪换弹 1.2 秒");

        Inventory inv = inventoryWithAmmo(12);
        GunState gun = new GunState(ItemRegistry.pistol(), true);
        assertEquals(GunState.ReloadOutcome.STARTED, gun.tryStartReload(inv));

        gun.tick(1.19, inv);
        assertTrue(gun.isReloading(), "1.19 秒时仍在换弹");
        assertEquals(0, gun.magazineAmmo(), "完成前不得转移弹药（A3 规则④）");
        assertEquals(12, inv.countOfItem(AMMO), "完成前后备弹药不得减少");

        gun.tick(0.02, inv);
        assertFalse(gun.isReloading(), "1.21 秒时必须已完成");
        assertEquals(12, gun.magazineAmmo());
        // 无限口径（PROTOTYPE）：换弹只读后备、不写背包，12 发一发不少。
        // 有限口径下"扣多少"的对应断言在 partialReloadFillsExactlyWhatIsAvailable
        // 与 CombatControllerTest 的计数级用例里（那里断言的是"真的少了多少"）。
        assertEquals(12, inv.countOfItem(AMMO),
                "PROTOTYPE 无限后备：换弹只读后备、不写背包，12 发一发不少");
    }

    /** A3 规则①：满弹匣按 R 无操作（v0.3.1 的"弹出余弹"口径已作废）。 */
    @Test
    void reloadingAFullMagazineDoesNothing() {
        Inventory inv = inventoryWithAmmo(50);
        GunState gun = new GunState(ItemRegistry.pistol(), true);
        gun.setMagazineAmmo(12);

        assertEquals(GunState.ReloadOutcome.ALREADY_FULL, gun.tryStartReload(inv));
        assertFalse(gun.isReloading());
        assertEquals(12, gun.magazineAmmo(), "满弹匣不得被换弹改变");
        assertEquals(50, inv.countOfItem(AMMO), "满弹匣换弹不得消耗弹药");
    }

    /**
     * A3 规则②的另一侧：后备弹药为 0 时不得开始换弹。
     *
     * <p>规则②只在有限口径下可达（无限口径下"后备 = 0"这句话不成立），
     * 因此本用例显式构造有限口径（{@code SURVIVAL}）。
     *
     * <p><b>M3 Story 8 起，这个口径就是正式玩法口径</b>（v2 §19-6）——
     * 也就是说本用例描述的不再是"原型阶段用不到的一条规则"，
     * 而是玩家在正式游戏里会真实遇到的一步：弹药打光后按 R 会被拒绝。
     * 断言本身没变，但它从"防死代码"升级成了"正式口径的行为契约"。
     */
    @Test
    void reloadWithoutReserveAmmoIsRefused() {
        Inventory inv = new Inventory(); // 空背包
        GunState gun = new GunState(ItemRegistry.pistol(), false);
        gun.setMagazineAmmo(3);

        assertEquals(GunState.ReloadOutcome.NO_RESERVE, gun.tryStartReload(inv));
        assertFalse(gun.isReloading());
    }

    /**
     * A3 规则③：后备不足时部分填充 {@code load = min(容量 − 弹匣内, 后备)}。
     *
     * <p>与 {@link #reloadWithoutReserveAmmoIsRefused} 同理：显式构造有限口径，
     * 好让这条规则继续被真的执行到。M3 Story 8 起它也是正式玩法口径（v2 §19-6）。
     */
    @Test
    void partialReloadFillsExactlyWhatIsAvailable() {
        Inventory inv = inventoryWithAmmo(3);
        GunState gun = new GunState(ItemRegistry.pistol(), false);
        gun.setMagazineAmmo(10);

        assertEquals(GunState.ReloadOutcome.STARTED, gun.tryStartReload(inv));
        gun.tick(1.2, inv);

        assertEquals(12, gun.magazineAmmo(), "10 + min(2, 3) = 12");
        assertEquals(1, inv.countOfItem(AMMO), "只应取走 2 发");
    }

    // ============================================================ 后备弹药口径（M3 Story 8 收紧）

    /**
     * 后备口径由<b>构造器第二实参</b>显式决定，且 <b>M3 Survival 的正式口径是有限后备</b>。
     *
     * <h2>这条用例在 Story 8 之前是什么</h2>
     * Story 3 留下的是 {@code theProductDefaultIsInfiniteReserve()}：断言
     * {@code GunState.INFINITE_RESERVE_DEFAULT == true}（M2.1-A 的 Combat Prototype 产品口径），
     * 并注明「TODO：Story 8 将改为有限」。Story 8 按 v2 §5.3 / §19-6 收紧了正式玩法口径：
     * <ul>
     *   <li>{@code INFINITE_RESERVE_DEFAULT} 这个<b>全局默认常量已删除</b> ——
     *       一个全局默认值无法同时表达"正式玩法有限、原型无限"两种合法口径
     *       （把其中之一改对就必然把另一个改错）；</li>
     *   <li>口径提升为上限明确的 Run Mode 配置 {@link GunState.ReserveMode}，
     *       并由 {@code CombatController#setReserveMode} 显式传入（v2 §5.3 原文建议）；
     *   <li><b>正式玩法口径 = {@code ReserveMode.SURVIVAL}（有限）</b>，
     *       由 {@code CombatController} 的字段默认值给出，见
     *       {@code CombatControllerTest#combatControllerBuildsGunsWithFiniteReserveByDefault()}。</li>
     * </ul>
     *
     * <h2>这里守住的性质</h2>
     * <ol>
     *   <li>两条口径都能被显式构造出来，且 {@code reserveInfinite()} 与之一一对应
     *       （{@code true} ↔ PROTOTYPE / {@code false} ↔ SURVIVAL）——
     *       这条替代了旧的"显式 true/false 等价性"断言；</li>
     *   <li><b>正式玩法的默认口径是有限</b>：{@code CombatController} 不配置任何东西时
     *       建出的枪必须是有限后备。这正是 v2 §19-6「M3 Survival 弹药有限并真实从
     *       Inventory 消耗」在规则层的最小断言；</li>
     *   <li>有限口径必须真的可达（PRD 5.4.3 规则②③不能退化成死代码）。</li>
     * </ol>
     *
     * <p><b>为什么 {@code CombatController} 的默认值是 SURVIVAL、而不是"必须显式配置"：</b>
     * 因为"忘记配置"的后果必须偏向安全的一侧 —— 忘记配置时弹药是资源，
     * 而不是凭空多出无限炮弹。想拿无限必须像 {@code M2CombatSelfTest} 那样显式声明自己是原型。
     */
    @Test
    void reserveCaliberIsExplicitAndTheProductDefaultIsFinite() {
        assertEquals(GunState.ReserveMode.SURVIVAL,
                new CombatController(new com.skyisland.entity.EntityManager()).reserveMode(),
                "M3 正式玩法口径是 Survival（有限后备，v2 §19-6）：不显式配置就是有限，"
                        + "而不是'不配置就送无限炮弹'");

        assertTrue(new GunState(ItemRegistry.pistol(), true).reserveInfinite(),
                "显式 true → 无限口径（Combat Prototype / Debug，v2 §19-7）");
        assertEquals(GunState.ReserveMode.PROTOTYPE,
                new GunState(ItemRegistry.pistol(), true).reserveMode(),
                "显式 true 对应的口径枚举必须是 PROTOTYPE");

        assertFalse(new GunState(ItemRegistry.pistol(), false).reserveInfinite(),
                "显式 false → 有限口径（M3 Survival，v2 §19-6）：弹药真实从 Inventory 扣除");
        assertEquals(GunState.ReserveMode.SURVIVAL,
                new GunState(ItemRegistry.pistol(), false).reserveMode(),
                "显式 false 对应的口径枚举必须是 SURVIVAL");

        // 枚举与它的布尔投影必须一致 —— 这两条不是重复：上面验的是"构造器怎么解释实参"，
        // 这里验的是"枚举自己的值没错"。任何一处写反都会让其中一条变红。
        assertTrue(GunState.ReserveMode.PROTOTYPE.infiniteReserve(), "PROTOTYPE = 无限");
        assertFalse(GunState.ReserveMode.SURVIVAL.infiniteReserve(), "SURVIVAL = 有限");
    }

    /** 无限后备：换弹把弹匣补满，背包里的弹药一个数都不动。 */
    @Test
    void infiniteReserveFillsTheMagazineAndLeavesTheInventoryUntouched() {
        Inventory inv = inventoryWithAmmo(7);
        GunState gun = new GunState(ItemRegistry.pistol(), true);
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
        GunState gun = new GunState(ItemRegistry.pistol(), true);
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
        GunState gun = new GunState(ItemRegistry.pistol(), true);
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
                "PROTOTYPE 无限后备：换弹只读后备、不写背包，12 发一发不少");
        assertEquals(1, gun.reloadsCompleted());
    }

    /** 换弹期间不得开枪（PRD 5.4.3；与"移动打不打断换弹"无关，M2.2 未改动这条）。 */
    @Test
    void cannotFireWhileReloading() {
        Inventory inv = inventoryWithAmmo(12);
        GunState gun = new GunState(ItemRegistry.pistol(), true);
        gun.setMagazineAmmo(2);
        gun.tryStartReload(inv);

        assertEquals(GunState.ShotOutcome.RELOADING, gun.tryFire());
        assertEquals(2, gun.magazineAmmo(), "换弹期间开火不得消耗弹匣");
    }

    @Test
    void dryFireIsReportedAndDoesNotConsumeAnything() {
        Inventory inv = inventoryWithAmmo(5);
        GunState gun = new GunState(ItemRegistry.pistol(), true);   // 弹匣为空

        assertEquals(GunState.ShotOutcome.NO_AMMO, gun.tryFire());
        assertEquals(1, gun.dryFires(), "空枪必须被计数，HUD 据此提示「弹药不足」");
        assertEquals(0, gun.shotsFired());
        assertEquals(5, inv.countOfItem(AMMO), "空枪不得动后备弹药");
    }

    @Test
    void fireRateThrottlesToFourShotsPerSecond() {
        Inventory inv = inventoryWithAmmo(12);
        GunState gun = new GunState(ItemRegistry.pistol(), true);
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
            new GunState(ItemRegistry.coal(), true);   // 煤炭不是枪械
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
        GunState gun = new GunState(ItemRegistry.pistol(), true);
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

    // ============================================================ SMG 换弹（v2 §12 / §14.4）
    //
    // v2 §12 的四条换弹规则必须对<b>两把枪都成立</b>。GunState 已完全数据驱动
    // （读 spec.magazineSize / reloadSeconds / shotInterval），因此这里逐条对 SMG 复验：
    // 若哪天有人把某个值硬编码回手枪，这些用例会变红。
    //
    // 口径都写成显式实参（true = 原型无限 / false = Survival 有限），
    // 不依赖任何默认值 —— 这样"某条用例到底在验哪种口径"从调用点一眼可见。

    /**
     * SMG 的换弹耗时是它自己的 1.5 秒，不是手枪的 1.2 秒。
     *
     * <p>口径显式取无限（PROTOTYPE），理由同手枪那条：
     * "验换弹时长"与"正式口径是什么"必须解耦。
     */
    @Test
    void smgReloadTakesItsOwnOnePointFiveSeconds() {
        assertEquals(1.5, ItemRegistry.smg().gun().reloadSeconds(), 1e-9,
                "v2 §10：SMG 换弹 1.5 秒");

        Inventory inv = inventoryWithAmmo(24);
        GunState gun = new GunState(ItemRegistry.smg(), true);
        assertEquals(GunState.ReloadOutcome.STARTED, gun.tryStartReload(inv));

        // SMG 的换弹是 1.5 秒 = 90 步；1.49 秒时必须仍在换弹
        gun.tick(1.49, inv);
        assertTrue(gun.isReloading(), "1.49 秒时 SMG 仍在换弹（1.5 秒还没走满）");
        assertEquals(0, gun.magazineAmmo(), "完成前不得转移弹药（规则④）");

        gun.tick(0.02, inv);
        assertFalse(gun.isReloading(), "1.51 秒时必须已完成");
        assertEquals(24, gun.magazineAmmo(), "补满到 SMG 自己的容量 24");
    }

    /** 规则①：SMG 满弹匣按 R 无操作。 */
    @Test
    void smgReloadingAFullMagazineDoesNothing() {
        Inventory inv = inventoryWithAmmo(50);
        GunState gun = new GunState(ItemRegistry.smg(), true);
        gun.setMagazineAmmo(24);

        assertEquals(GunState.ReloadOutcome.ALREADY_FULL, gun.tryStartReload(inv));
        assertFalse(gun.isReloading());
        assertEquals(24, gun.magazineAmmo(), "满弹匣不得被换弹改变");
        assertEquals(50, inv.countOfItem(AMMO), "满弹匣换弹不得消耗弹药");
    }

    /** 规则②③：SMG 有限口径下后备不足只能部分填充 —— 证明 SMG 也复用同一条弹药规则。 */
    @Test
    void smgPartialReloadFillsExactlyWhatIsAvailable() {
        Inventory inv = inventoryWithAmmo(10);
        GunState gun = new GunState(ItemRegistry.smg(), false);
        gun.setMagazineAmmo(20);

        assertEquals(GunState.ReloadOutcome.STARTED, gun.tryStartReload(inv));
        gun.tick(1.5, inv);

        assertEquals(24, gun.magazineAmmo(), "20 + min(4, 10) = 24");
        assertEquals(6, inv.countOfItem(AMMO), "只应取走 4 发");
    }

    /** 规则②：SMG 有限口径下后备为 0 拒绝换弹。 */
    @Test
    void smgReloadWithoutReserveIsRefused() {
        Inventory empty = new Inventory();
        GunState gun = new GunState(ItemRegistry.smg(), false);
        gun.setMagazineAmmo(3);

        assertEquals(GunState.ReloadOutcome.NO_RESERVE, gun.tryStartReload(empty));
        assertFalse(gun.isReloading());
    }

    /**
     * SMG 的射速节流是 0.1 秒（不是手枪的 0.25 秒）。
     *
     * <p>它同时证明 v2 §10 的"不同射速"确实由数据驱动，而不是沿用某个写死的间隔。
     */
    @Test
    void smgFireRateThrottlesToTenShotsPerSecond() {
        Inventory inv = inventoryWithAmmo(24);
        GunState gun = new GunState(ItemRegistry.smg(), true);
        gun.setMagazineAmmo(2);

        assertEquals(GunState.ShotOutcome.FIRED, gun.tryFire());
        assertEquals(1, gun.magazineAmmo());
        assertEquals(GunState.ShotOutcome.COOLDOWN, gun.tryFire(), "同一瞬间不得连发");

        gun.tick(0.09, inv);
        assertEquals(GunState.ShotOutcome.COOLDOWN, gun.tryFire(),
                "0.09 秒还不够 0.1 秒的间隔 → 仍在冷却");
        gun.tick(0.02, inv);
        assertEquals(GunState.ShotOutcome.FIRED, gun.tryFire(), "累计 0.11 秒 > 0.1 秒 → 允许第二发");
        assertEquals(0, gun.magazineAmmo());
        assertEquals(2, gun.shotsFired());
    }

    /**
     * v2 §14.5 反向验证项：<b>ammoId 不存在时，有限备弹路径必须失败，不允许静默 fallback</b>。
     *
     * <p>GunState 在<b>构造期</b>校验 {@code spec.ammoId()} 能解析到真实弹药，
     * 因此这里用一个 ammoId 指向不存在 ID 的 GunSpec 构造，必须立刻抛
     * {@link IllegalStateException}，而不是在换弹时静默地"打不完的子弹"。
     *
     * <p>{@link Item} 的构造器是包内可见，因此这条用例放在 {@code combat} 包里就只能
     * 从<b>已注册的</b>枪出发去构造 GunSpec。<b>完整的那条（指向不存在 ammoId）在
     * {@code item.GunStateAmmoResolutionTest} 里</b>（那个包能构造探针 Item）。
     * 这里守的是另一半：SMG 的 ammoId 必须真能解析，且解析出来的就是手枪弹。
     *
     * <p><b>反向验证（TEMP_REVERSE_VERIFY）</b>：把 SMG 的 ammoId 改成
     * {@code "skyisland:no_such_ammo"}，{@code new GunState(smg, false)} 会抛
     * {@link IllegalStateException} —— 本用例与
     * {@link #smgReloadTakesItsOwnOnePointFiveSeconds()} 一起变红。
     */
    @Test
    void smgAmmoIdResolvesToARealRegisteredItem() {
        assertNotNull(ItemRegistry.byName(ItemRegistry.smg().gun().ammoId()),
                "SMG 的 ammoId 必须能解析到真实弹药 Item（v2 §14.5：禁止静默 fallback）");
        assertEquals(ItemRegistry.pistolAmmo(), ItemRegistry.byName(ItemRegistry.smg().gun().ammoId()),
                "SMG 的 ammoId 指的就是手枪弹（v2 §6.1：两把枪共用一种弹药）");
        // 构造必须成功 —— 若 ammoId 不存在，这一行会抛 IllegalStateException
        assertNotNull(new GunState(ItemRegistry.smg(), false));
    }
}

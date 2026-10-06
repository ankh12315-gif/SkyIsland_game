package com.skyisland.combat;

import com.skyisland.item.FireMode;
import com.skyisland.item.GunSpec;
import com.skyisland.item.ItemRegistry;
import com.skyisland.player.Player;
import com.skyisland.player.PlayerIntent;
import com.skyisland.testutil.SourceScan;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GunSpec} 六个字段的<b>接线证明</b>（M3 接线修正）。
 *
 * <h2>它要防的是什么</h2>
 * 在写这条测试之前，{@code GunSpec} 的 13 个字段里有 <b>6 个没有任何读者</b>：
 * {@code pelletCount} / {@code spreadRad} / {@code aimFovDeg} / {@code aimMoveSpeedMult} /
 * {@code falloffPerUnit} / {@code falloffFloor}。它们被构造期校验（所以看起来"被用了"）、
 * 被 {@code ItemRegistryTest} / {@code GunSpecTest} 逐值断言（所以看起来很受关注），
 * 却没有任何一条玩法代码读它们。
 *
 * <p>后果是<b>文档与实机不一致</b>：v2 §10 写"SMG ADS 48° / 移速 ×0.65"，
 * 而 {@code Player} 读的是全局常量 45/70 与 0.60 —— 拿 SMG 按右键永远是手枪的手感。
 * 这类缺陷不会崩、不会被试玩发现（如果没人在意那 3° 和 5%），
 * 只会让"数据驱动"这句话变成装修。
 *
 * <h2>本类的两条取证手段（缺一不可）</h2>
 * <ol>
 *   <li><b>行为取证</b>：凡是注册表里两把枪取不同值的字段，就用"手枪 vs 冲锋枪"直接对比。
 *       这是最强的证据 —— 它不是"代码里有这行"，而是"数据改了行为跟着改"。</li>
 *   <li><b>结构取证</b>：注册表里取值相同、因而无法行为区分的字段（衰减那对），
 *       用<b>剥掉注释后的方法体扫描</b>证明"结算路径确实读的是 spec 而不是常量"。
 *       之所以必须剥注释：本项目真实发生过"把调用注释掉、扫描断言依然全绿"的事故
 *       （注释里当然也写着同一串文本）。见 {@link SourceScan}。</li>
 * </ol>
 */
class WeaponDataWiringTest {

    private static final double DT = 1.0 / 60.0;

    private static World world() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    /** 按住右键的意图（useHeld = true）。 */
    private static PlayerIntent aim() {
        return PlayerIntent.combat(0, 0, false, 0, 0, false, true, false);
    }

    /** 一边瞄准一边前进的意图，用于测 ADS 移速倍率。 */
    private static PlayerIntent aimAndWalk() {
        return PlayerIntent.combat(1f, 0, false, 0, 0, false, true, false);
    }

    private static PlayerIntent walk() {
        return PlayerIntent.combat(1f, 0, false, 0, 0, false, false, false);
    }

    /**
     * 只带一把枪的玩家。
     *
     * <p>枪放在快捷栏第 1 格（{@code Inventory.add} 的填充顺序是"先快捷栏、再从第 1 格起"），
     * 因此 {@code selectedStack()} 就是它。
     */
    private static Player playerWith(String gunId) {
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.setBaseFovDeg(70.0);
        player.inventory().add(ItemRegistry.runtimeIdOf(gunId), 1);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), 24);
        return player;
    }

    /** 连续走 2 秒的水平位移；{@code aiming} 决定是否按住右键。 */
    private static double walkDistance(Player player, boolean aiming) {
        World world = world();
        double x0 = player.position().x;
        double z0 = player.position().z;
        for (int i = 0; i < 120; i++) {
            player.step(world, aiming ? aimAndWalk() : walk(), DT);
        }
        double dx = player.position().x - x0;
        double dz = player.position().z - z0;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ============================================================ aimFovDeg

    /**
     * {@code aimFovDeg} 的承重证明：两把枪的 ADS 目标角不同（45 / 48），实机 FOV 必须跟着不同。
     *
     * <p>这条断言在改造前会失败 —— 那时两把枪 ADS 之后都是 45.0（读的是全局常量 45/70）。
     */
    @Test
    void adsTargetFovComesFromTheHeldGunsOwnSpec() {
        record AdsCase(String gunId, double expectedAdsFovDeg, double baseFovDeg) {
        }
        for (AdsCase c : List.of(
                new AdsCase(ItemRegistry.PISTOL_ID, 45.0, 70.0),
                new AdsCase(ItemRegistry.SMG_ID, 48.0, 70.0),
                // 绝对目标角语义（v2 §5.2-8 / §9.1）：基础 FOV 变了，落点不变。
                new AdsCase(ItemRegistry.PISTOL_ID, 45.0, 90.0),
                new AdsCase(ItemRegistry.SMG_ID, 48.0, 90.0))) {
            Player player = playerWith(c.gunId());
            player.setBaseFovDeg(c.baseFovDeg());
            GunSpec spec = ItemRegistry.byName(c.gunId()).gun();
            assertNotNull(spec, c.gunId() + " 必须有 GunSpec");

            player.step(world(), aim(), DT);

            assertTrue(player.isAiming(), c.gunId() + " 持枪按住右键必须进入瞄准");
            assertEquals(c.expectedAdsFovDeg(), player.camera().fovDeg(), 1e-9,
                    c.gunId() + " 的 ADS 目标角必须是它自己 spec 里的 " + spec.aimFovDeg()
                            + "°（基础 FOV " + c.baseFovDeg() + "）");
            assertEquals(Math.min(1.0, spec.aimFovDeg() / c.baseFovDeg()), player.fovScale(), 1e-12,
                    "倍率必须等于 min(1, " + spec.aimFovDeg() + " / " + c.baseFovDeg() + ")");
        }
    }

    // ============================================================ aimMoveSpeedMult

    /**
     * {@code aimMoveSpeedMult} 的承重证明：手枪 ADS ×0.60、SMG ADS ×0.65，实测比值必须分开。
     *
     * <p>容差取 5%：两者理论值相差 0.05，即 7.7%（相对 0.65），因此 5% 的窗口
     * <b>足以把"两把枪读了同一个常量"区分出来</b> —— 这正是本断言存在的意义。
     */
    @Test
    void adsMoveSpeedMultiplierComesFromTheHeldGunsOwnSpec() {
        for (String gunId : List.of(ItemRegistry.PISTOL_ID, ItemRegistry.SMG_ID)) {
            GunSpec spec = ItemRegistry.byName(gunId).gun();
            double expected = spec.aimMoveSpeedMult();

            double walk = walkDistance(playerWith(gunId), false);
            double aimed = walkDistance(playerWith(gunId), true);
            double ratio = aimed / walk;

            assertTrue(walk > 1.0 && aimed > 1.0,
                    "两段位移都必须足够大，比值才不会被 0/0 污染（" + walk + " / " + aimed + "）");
            assertEquals(expected, ratio, 0.05 * expected,
                    gunId + " 的 ADS 移速倍率必须是 spec 里的 " + expected
                            + "（实测比值 " + ratio + "）");
        }
        // 显式钉住"两者确实不同"：否则上面那条即使读的是同一个常量也会通过。
        assertFalse(Math.abs(ItemRegistry.pistol().gun().aimMoveSpeedMult()
                        - ItemRegistry.smg().gun().aimMoveSpeedMult()) < 1e-9,
                "两把枪的 ADS 移速倍率若相同，本测试就无法区分'读了 spec'与'读了同一个常量'");
    }

    /**
     * 瞄准途中换枪：FOV 必须立刻按新枪重算，而不是停在上一把枪的角度。
     *
     * <p>这是一条只会在真人手里出现的路径（按住右键时按 1/2 切枪）。
     * 旧实现的判据是"瞄准状态没变就直接 return"，因此 45° 会一直挂到玩家松手再按。
     */
    @Test
    void switchingGunsWhileStillAimingRecomputesTheAdsFovImmediately() {
        World world = world();
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.setBaseFovDeg(70.0);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID), 1);   // 快捷栏 1
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.SMG_ID), 1);      // 快捷栏 2

        player.step(world, aim(), DT);
        assertEquals(45.0, player.camera().fovDeg(), 1e-9, "手枪 ADS = 45°");

        // 右键一直按着，只切枪。
        player.inventory().selectSlot(1);
        player.step(world, aim(), DT);
        assertTrue(player.isAiming(), "右键没松，瞄准状态不该被打断");
        assertEquals(48.0, player.camera().fovDeg(), 1e-9,
                "切到 SMG 之后必须先变成 48°（而不是等玩家松手再按一次右键）");

        player.inventory().selectSlot(0);
        player.step(world, aim(), DT);
        assertEquals(45.0, player.camera().fovDeg(), 1e-9, "切回手枪必须回到 45°");
    }

    // ============================================================ falloffPerUnit / falloffFloor

    /**
     * 衰减那两个字段的承重证明：{@link CombatController#damageFor} 必须<b>按 spec 算</b>。
     *
     * <p>为什么用一把"合成枪"而不拿注册表里的枪：两把在册枪的衰减值都是基线 0.90 / 0.20，
     * 拿它们断言无法把"读了 spec"与"读了写死的 0.9/0.20"区分开（这是同值巧合，
     * 上一轮正是这种巧合让死数据活了下来）。给一把 {@code falloffPerUnit = 0.5} 的枪，
     * 两者立刻分开。
     */
    @Test
    void damageForReadsTheSpecsFalloffPairInsteadOfAHardcodedBaseline() {
        // 合成枪：基础伤害 8、射程 32，但衰减远快于手枪（每格 ×0.5、下限 5%）。
        GunSpec fast = new GunSpec(8, 12, 4.0, 32, 1.2, FireMode.SINGLE, 1, 0.0,
                45.0, 0.60, 0.5, 0.05, ItemRegistry.PISTOL_AMMO_ID);

        // 射程内：两支完全一样（射程内的伤害与衰减参数无关）。
        assertEquals(8, CombatController.damageFor(fast, 20.0), "射程内 100% → 8");

        // 超出 13 格：0.5^13 ≈ 0.000122 → 被 0.05 截断 → 8 × 0.05 = 0.4 → 保底 1。
        assertEquals(1, CombatController.damageFor(fast, 45.0),
                "快衰减枪在 45 格处应触到自己的下限 5%（8 × 0.05 = 0.4 → 保底 1）");

        // 同一距离、同一基础伤害，只换衰减参数 → 结果必须不同。
        int baseline = CombatController.damageFor(ItemRegistry.pistol().gun(), 45.0);
        assertEquals(2, baseline, "手枪在 45 格处 = floor(8 × 0.9^13) = 2（PRD 承诺值）");
        assertTrue(CombatController.damageFor(fast, 45.0) != baseline,
                "换掉 falloffPerUnit / falloffFloor 必须改变伤害，否则这两个字段根本没被读");
    }

    // ============================================================ pelletCount / spreadRad + 结构取证

    /**
     * {@code pelletCount} / {@code spreadRad} 在结算路径上确实被读（结构取证）。
     *
     * <p>这两条无法用行为区分：在册的两把枪都是 1 / 0，行为上与"写死单发无散布"完全一致。
     * 因此改为断言"结算方法体内读了它们"，并配合 {@link ShotSpreadTest} 对散布几何本身
     * 做行为取证 —— 两者合起来覆盖"数据 → 方向序列"这条链。
     *
     * <p>扫描只取 {@code resolveShot} 的<b>方法体</b>且已剥注释，因此：
     * 把调用注释掉 → 变红；把循环删掉 → 变红；只在别处提到这两个名字 → 不通过。
     */
    @Test
    void resolveShotConsumesPelletCountAndSpreadRad() {
        String body = SourceScan.methodBody(
                SourceScan.readMain("com/skyisland/combat/CombatController.java"),
                "private void resolveShot(");

        assertTrue(body.contains("spec.pelletCount()"),
                "resolveShot 必须读 spec.pelletCount()（否则霰弹枪会退化成'伤害 6 倍的单发手枪'）");
        assertTrue(body.contains("spec.spreadRad()"),
                "resolveShot 必须读 spec.spreadRad()");
        assertTrue(body.contains("ShotSpread.offset("),
                "每颗弹丸的方向必须由 ShotSpread.offset 在散布锥内给出");
        // 循环必须真的按 pelletCount 迭代：出现 for/while 才说明"一发改多条射线"。
        assertTrue(body.contains("for (") || body.contains("while ("),
                "resolveShot 里必须有逐弹丸循环");
    }

    /**
     * 结算路径必须经由 {@link CombatController#damageFor}（结构取证）。
     *
     * <p>这是"衰减参数真的进了战斗路径"的最后一段路：{@code damageFor} 本身读 spec
     * 已由行为断言钉住，但若 {@code resolveShot} 绕过它、自己内联一段算术，
     * 那段算术就可能再退化成写死的常量。剥注释的方法体扫描正是为这种
     * "函数写对了、但没人调用它"的形态准备的。
     */
    @Test
    void resolveShotRoutesDamageThroughDamageFor() {
        String body = SourceScan.methodBody(
                SourceScan.readMain("com/skyisland/combat/CombatController.java"),
                "private void resolveShot(");
        assertTrue(body.contains("damageFor(spec"),
                "resolveShot 必须调用 damageFor(spec, …) 结算伤害");
        assertFalse(body.contains("DamageFalloff."),
                "resolveShot 不应直接调 DamageFalloff —— 那会绕开'按 spec 传参'这一层");
    }

    /**
     * ADS 参数在 {@code Player} 里确实读的是手持枪的 spec（结构取证）。
     *
     * <p>同时钉住"两个全局常量已经不存在"：它们不是被改名藏起来了，
     * 而是彻底删掉了 —— 只要还留着一个 {@code AIM_FOV_RATIO} / {@code AIM_MOVE_SPEED_RATIO}
     * 这样的常量，下一个人就会顺手用它，v2 §10 的两套手感会再次退化成一套。
     */
    @Test
    void playerReadsAdsParametersFromTheHeldGunSpec() {
        String player = SourceScan.withoutComments(
                SourceScan.readMain("com/skyisland/player/Player.java"));

        assertTrue(player.contains("spec.aimFovDeg()"),
                "Player 必须从 GunSpec 取 ADS 目标 FOV");
        assertTrue(player.contains("spec.aimMoveSpeedMult()"),
                "Player 必须从 GunSpec 取 ADS 移速倍率");
        assertFalse(player.contains("AIM_FOV_RATIO"),
                "AIM_FOV_RATIO 这个全局常量必须已删除（它会掩盖每把枪的差异）");
        assertFalse(player.contains("AIM_MOVE_SPEED_RATIO"),
                "AIM_MOVE_SPEED_RATIO 这个全局常量必须已删除");
    }

    // ============================================================ 死数据审计（防复发）

    /**
     * 审计：{@code GunSpec} 的每个组件在生产代码里都必须有<b>读者</b>。
     *
     * <p>这是防"死数据复发"的那一条。它把这次事故的形态本身变成了断言：
     * 一旦有人给 record 加字段、填进注册表、写好单测，却忘了接到玩法上，
     * 这里会直接列出字段名。
     *
     * <p>扫描范围是主源码树<b>去掉 {@code GunSpec.java} 自己</b>之后的全部代码，
     * 并已剥掉注释。排除自己的原因：record 的组件名会出现在它自己的访问器签名里，
     * 不排除的话每条断言都会被自己满足（这正是"看起来被用了"的成因之一）。
     *
     * <p><b>唯一的例外是 {@code fireRate}，而且它不是漏洞：</b>{@code GunSpec} 有意把
     * "开火间隔"封装成派生访问器 {@code shotInterval() = 1 / fireRate}，
     * 外部只该读 {@code shotInterval()}（射击节流的语义单位是"秒/发"，不是"发/秒"）。
     * 因此这里显式要求 {@code shotInterval()} 有外部读者，把"两跳读者"这条路走通 ——
     * 否则这个例外就会变成一个永远不报的死角。
     */
    @Test
    void everyGunSpecComponentHasAReaderInProductionCode() {
        String code = SourceScan.allMainCodeExcept("item/GunSpec.java");

        List<String> components = List.of(
                "damage", "magazineSize", "fireRate", "range", "reloadSeconds", "fireMode",
                "pelletCount", "spreadRad", "aimFovDeg", "aimMoveSpeedMult",
                "falloffPerUnit", "falloffFloor", "ammoId");
        Set<String> readThroughADerivedAccessor = Set.of("fireRate");

        List<String> dead = new ArrayList<>();
        for (String component : components) {
            if (readThroughADerivedAccessor.contains(component)) {
                continue;
            }
            if (!code.contains("." + component + "()")) {
                dead.add(component);
            }
        }
        assertTrue(dead.isEmpty(),
                "这些 GunSpec 字段在生产代码里没有任何读者（死数据）：" + dead
                        + "。要么接到玩法上，要么按 v2 §8.3 的口径把它连同「未使用」这件事一起处理。");

        // 例外必须真的走通："两跳读者"的第二跳存在。
        assertTrue(code.contains(".shotInterval()"),
                "fireRate 是通过派生访问器 shotInterval() 被消费的 —— "
                        + "若连 shotInterval() 也没有外部读者，那个例外就变成了死角");
    }
}

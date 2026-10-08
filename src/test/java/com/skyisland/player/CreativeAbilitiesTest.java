package com.skyisland.player;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.util.Coords;
import com.skyisland.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ M4-S8b：创造模式五项能力的行为守卫（PRD §5.2 – §5.5）。
 *
 * <p>五项能力是：破坏瞬时 / 放置不消耗 / 免疫伤害 / 虚空不死亡 / 可飞行。
 * 每一条都配了<b>生存模式的对照断言</b> —— 只有"创造下成立"的断言是脆的：
 * 把开关永远设成 {@code true} 也能全绿，而那正是"能力覆盖了生存玩法"
 * 这个最该被抓到的回归。
 *
 * <p><b>为什么能力开关放在 {@code Player} 上：</b>物理层不该依赖存档层；
 * 模式由 {@code SaveManager#effectiveGameMode()} 在装配期定死（PRD §4.3 模式锁定），
 * 这里只持有那个决定的结果（与 {@code CombatController#setReserveMode} 同一口径）。
 */
class CreativeAbilitiesTest {

    private static final double DT = 1.0 / 60.0;

    // ------------------------------------------------------------ 夹具

    /** 站在平坦世界 (0.5,64,0.5) 的玩家。 */
    private static Player playerOnFlatGround() {
        return new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
    }

    /** 设视线（yaw/pitch）。yaw=-90 → 正对 +x；pitch=-90 → 垂直向下。 */
    private static void look(Player player, double yaw, double pitch) {
        player.applyLoadedState(0.5, TestWorlds.SURFACE_FEET_Y, 0.5, yaw, pitch,
                null, List.of(), 0, 0);
    }

    /** 在 (2,64,0) 与 (2,65,0) 立一道两格高的墙：平视时能命中它，且放置位 (1,65,0) 是空的。 */
    private static World worldWithAWall() {
        World world = TestWorlds.flatWorld();
        world.applySavedBlock(2, 64, 0, (short) TestWorlds.stone());
        world.applySavedBlock(2, 65, 0, (short) TestWorlds.stone());
        return world;
    }

    private static PlayerIntent intent(boolean attackHeld, boolean usePressed,
                                       boolean jump, boolean sneak) {
        return new PlayerIntent(0f, 0f, jump, 0, 0,
                attackHeld, false, usePressed, false, false, false,
                false, false, false, 0, -1, sneak);
    }

    private static PlayerIntent holdAttack() {
        return intent(true, false, false, false);
    }

    // ============================================================ §5.2 破坏瞬时

    @Test
    void creativeBreaksABlockInASingleStep() {
        World world = TestWorlds.flatWorld();
        Player player = playerOnFlatGround();
        look(player, 0.0, -90.0);
        player.setCreativeMode(true);

        player.step(world, holdAttack(), DT);

        assertTrue(world.isAirAt(0, TestWorlds.SURFACE_BLOCK_Y, 0),
                "★ 创造模式破坏必须瞬时：一个逻辑步之内脚下那一格就该没了");
        assertEquals(1, player.blocksBroken());
    }

    /**
     * 对照：生存模式<b>不</b>瞬时。
     *
     * <p>缺了这条，"把破坏改成永远瞬时"这个回归可以全绿 ——
     * 而它恰恰是本条能力最容易写坏的方向：少一个分支就"顺便"把生存模式也改了。
     */
    @Test
    void survivalStillNeedsTheFullMiningTime() {
        World world = TestWorlds.flatWorld();
        Player player = playerOnFlatGround();
        look(player, 0.0, -90.0);
        assertFalse(player.isCreativeMode(), "默认必须是生存模式");

        player.step(world, holdAttack(), DT);

        assertFalse(world.isAirAt(0, TestWorlds.SURFACE_BLOCK_Y, 0),
                "生存模式一个逻辑步（1/60 秒）绝对挖不完一格 —— 挖完说明进度条被绕过了");
        assertEquals(0, player.blocksBroken());
    }

    /**
     * ★ 资源核心在创造模式下<b>仍然挖不动</b>（PRD §5.2 明文）。
     *
     * <p>这条守的是"创造模式不得成为绕过 PRD 硬约束的又一条路"：
     * {@code breakable = false} 不被创造模式覆盖。把瞬时分支放到
     * "不可破坏"判定<u>之前</u>就能绕过它，而那种写法看起来完全合理。
     */
    @Test
    void theResourceCoreIsStillUnbreakableInCreativeMode() {
        World world = TestWorlds.flatWorld();
        assertTrue(world.applySavedBlock(0, TestWorlds.SURFACE_BLOCK_Y, 0,
                (short) TestWorlds.resourceCore()));
        Player player = playerOnFlatGround();
        look(player, 0.0, -90.0);
        player.setCreativeMode(true);

        for (int i = 0; i < 20; i++) {
            player.step(world, holdAttack(), DT);
        }

        assertEquals(TestWorlds.resourceCore(),
                world.blockIdAt(0, TestWorlds.SURFACE_BLOCK_Y, 0),
                "★ 创造模式也不能挖掉资源核心（PRD §5.1.1 / §5.2 硬约束）");
        assertEquals(0, player.blocksBroken());
    }

    /**
     * 长按的重复率必须受控（PRD 留白处的裁定）。
     *
     * <p>不设冷却的话，按住左键扫一圈会以 <b>60 格/秒</b> 摧毁地形 —— 那不是"瞬时"，
     * 是"不可控"。这条把"瞬时"与"连发"分开：点一次立刻破坏，按住则限速。
     */
    @Test
    void holdingAttackIsRateLimitedInsteadOfBreakingEveryStep() {
        World world = TestWorlds.flatWorld();
        world.applySavedBlock(0, 61, 0, (short) TestWorlds.stone());
        world.applySavedBlock(0, 60, 0, (short) TestWorlds.stone());
        Player player = playerOnFlatGround();
        look(player, 0.0, -90.0);
        player.setCreativeMode(true);

        int steps = 30;   // 0.5 秒
        for (int i = 0; i < steps; i++) {
            player.step(world, holdAttack(), DT);
        }

        assertTrue(player.blocksBroken() >= 2,
                "长按必须能连续破坏（冷却不该把它变成只能挖一格），实际 " + player.blocksBroken());
        assertTrue(player.blocksBroken() < steps,
                "★ 长按不得以每步一格的速率破坏（那等于 60 格/秒），实际 "
                        + player.blocksBroken() + " 格 / " + steps + " 步");
        int ceiling = (int) Math.floor(steps * DT / Player.CREATIVE_BREAK_COOLDOWN_SECONDS) + 1;
        assertTrue(player.blocksBroken() <= ceiling,
                "破坏速率必须落在冷却给出的上界内（0.5 秒内最多 " + ceiling + " 格）");
    }

    // ============================================================ §5.3 放置不消耗

    @Test
    void creativePlacementDoesNotConsumeTheStack() {
        World world = worldWithAWall();
        Player player = playerOnFlatGround();
        look(player, -90.0, 0.0);
        player.setCreativeMode(true);
        // ★ selectedStack() 取的是【快捷栏相对槽 0 → 绝对索引 27】，
        //   不是 slots[0]（那是主背包第一格）。放错格子会得到"手里是空的"这种假失败。
        player.inventory().setSlot(27, ItemStack.of(TestWorlds.dirt(), 64));

        player.step(world, intent(false, true, false, false), DT);

        assertEquals(64, player.inventory().selectedStack().count(),
                "★ 创造模式放置不消耗：放一格之后手里必须还是 64（PRD §5.3）");
        assertEquals(1, player.blocksPlaced(), "前提：这一格必须真的放成功了");
    }

    /** 对照：生存模式放置必须扣 1。 */
    @Test
    void survivalPlacementConsumesExactlyOne() {
        World world = worldWithAWall();
        Player player = playerOnFlatGround();
        look(player, -90.0, 0.0);
        player.inventory().setSlot(27, ItemStack.of(TestWorlds.dirt(), 64));

        player.step(world, intent(false, true, false, false), DT);

        assertEquals(63, player.inventory().selectedStack().count(),
                "生存模式放置必须扣 1 —— 扣 0 说明创造模式的分支污染了生存路径");
    }

    // ============================================================ §5.5 免疫伤害

    @Test
    void creativeModeNegatesAllDamage() {
        World world = TestWorlds.flatWorld();
        Player player = playerOnFlatGround();
        player.setCreativeMode(true);

        player.hurt(world, 3, Player.DamageCause.GENERIC);
        player.hurt(world, 4, Player.DamageCause.FALL);

        assertEquals(player.maxHealth(), player.health(), "创造模式受伤不得扣血");
        assertEquals(2, player.damageNegatedCount(),
                "免疫次数必须可观测 —— 否则「是不是真的免疫了」只能靠猜");
        assertFalse(player.isDead());
    }

    /** 对照：生存模式照常扣血。 */
    @Test
    void survivalModeStillTakesDamage() {
        World world = TestWorlds.flatWorld();
        Player player = playerOnFlatGround();

        player.hurt(world, 3, Player.DamageCause.GENERIC);

        assertEquals(player.maxHealth() - 3, player.health());
        assertEquals(0, player.damageNegatedCount());
    }

    // ============================================================ §5.5 虚空不死亡

    /**
     * ★ 创造模式坠入虚空：不死亡、不掉落，视为"停在虚空底部"。
     *
     * <p>这是 PRD §5.5 明文写出的<b>例外</b>：创造模式下玩家会主动飞下虚空，
     * 若按生存规则处理，飞行就变成自杀。
     */
    @Test
    void creativeModeParksAtTheBottomOfTheVoidInsteadOfDying() {
        World world = TestWorlds.flatWorld();
        Player player = playerOnFlatGround();
        player.setCreativeMode(true);
        int deathsBefore = player.deaths();

        player.applyLoadedState(0.5, -50.0, 0.5, 0.0, 0.0, null, List.of(), 0, deathsBefore);
        for (int i = 0; i < 10; i++) {
            player.step(world, PlayerIntent.NONE, DT);
        }

        assertFalse(player.isDead(), "★ 创造模式坠入虚空不得死亡（PRD §5.5 例外）");
        assertEquals(deathsBefore, player.deaths(), "不死就不该计一次死亡");
        assertEquals(Coords.VOID_KILL_Y, player.position().y, 1e-6,
                "★ 必须停在虚空底部而不是继续下沉");
    }

    /** 对照：生存模式的虚空死亡规则一字不改（PRD 4.5）。 */
    @Test
    void survivalModeStillDiesInTheVoid() {
        World world = TestWorlds.flatWorld();
        Player player = playerOnFlatGround();

        player.applyLoadedState(0.5, -50.0, 0.5, 0.0, 0.0, null, List.of(), 0, 0);
        player.step(world, PlayerIntent.NONE, DT);

        assertTrue(player.isDead(), "生存模式的虚空死亡必须原样保留");
    }

    // ============================================================ §5.4 飞行

    @Test
    void flightIsRefusedWithoutCreativeMode() {
        Player player = playerOnFlatGround();
        player.setFlying(true);
        assertFalse(player.isFlying(),
                "★ 生存模式不得开启飞行 —— 这是「飞行只有创造模式能开」唯一的落点");
    }

    @Test
    void doubleTappingJumpTogglesFlight() {
        World world = TestWorlds.flatWorld();
        Player player = playerOnFlatGround();
        player.setCreativeMode(true);
        assertFalse(player.isFlying(), "创造模式默认关闭飞行（PRD §5.4）");

        // 按下 → 松开 → 窗口内再按下
        player.step(world, intent(false, false, true, false), DT);
        player.step(world, intent(false, false, false, false), DT);
        player.step(world, intent(false, false, true, false), DT);
        assertTrue(player.isFlying(), "双击空格必须开启飞行");

        // 再双击一次必须关闭
        player.step(world, intent(false, false, false, false), DT);
        player.step(world, intent(false, false, true, false), DT);
        player.step(world, intent(false, false, false, false), DT);
        player.step(world, intent(false, false, true, false), DT);
        assertFalse(player.isFlying(), "再双击一次必须关闭飞行");
    }

    /**
     * 对照：<b>单击</b>与<b>长按</b>都不能起飞。
     *
     * <p>若把判据写成"按住时长"，生存模式里最常见的"长按空格连跳"会被判成双击，
     * 玩家跳着跳着就飞起来了 —— 这条是那个失败形状的唯一抓手。
     */
    @Test
    void aSingleJumpDoesNotToggleFlight() {
        World world = TestWorlds.flatWorld();
        Player player = playerOnFlatGround();
        player.setCreativeMode(true);

        player.step(world, intent(false, false, true, false), DT);
        assertFalse(player.isFlying(), "单击空格只是跳，不是起飞");

        for (int i = 0; i < 30; i++) {
            player.step(world, intent(false, false, true, false), DT);
        }
        assertFalse(player.isFlying(), "长按空格是连跳，不得被判成双击");
    }

    @Test
    void flyingFollowsTheVerticalKeysAndIgnoresGravity() {
        World world = TestWorlds.flatWorld(-1, -1, 1, 1);
        Player player = playerOnFlatGround();
        player.setCreativeMode(true);
        player.setFlying(true);

        // 悬停：不按键，竖直速度保持 0（无重力）
        for (int i = 0; i < 60; i++) {
            player.step(world, intent(false, false, false, false), DT);
        }
        assertEquals(TestWorlds.SURFACE_FEET_Y, player.position().y, 1e-6,
                "★ 飞行中无重力：不按键必须停在原高度，掉下去说明重力没被替换掉");

        // 上升：按住空格
        double before = player.position().y;
        for (int i = 0; i < 30; i++) {
            player.step(world, intent(false, false, true, false), DT);
        }
        assertTrue(player.position().y > before + 0.5,
                "空格必须上升，实际从 " + before + " 到 " + player.position().y);

        // 下降：按住 Shift
        double top = player.position().y;
        for (int i = 0; i < 60; i++) {
            player.step(world, intent(false, false, false, true), DT);
        }
        assertTrue(player.position().y < top - 0.5,
                "Shift 必须下降，实际从 " + top + " 到 " + player.position().y);
    }

    @Test
    void turningCreativeOffStopsFlight() {
        World world = TestWorlds.flatWorld();
        Player player = playerOnFlatGround();
        player.setCreativeMode(true);
        player.setFlying(true);
        assertTrue(player.isFlying(), "前提：飞行已开启");

        player.setCreativeMode(false);

        assertFalse(player.isFlying(),
                "★ 关掉创造能力必须同时停飞：否则玩家会「以生存模式的身体继续飞着」，"
                        + "而生存模式既没有免疫也没有虚空保护");
    }

    /**
     * 飞行中的升降不得被记成坠落距离。
     *
     * <p>否则"飞高 → 关掉飞行 → 落地"会结算出一大截坠落伤害。
     * 创造模式免疫了它，但那个数会留在 HUD 上，看起来就是一个 bug。
     */
    @Test
    void flyingDoesNotAccumulateFallDistance() {
        World world = TestWorlds.flatWorld(-1, -1, 1, 1);
        Player player = playerOnFlatGround();
        player.setCreativeMode(true);
        player.setFlying(true);

        for (int i = 0; i < 120; i++) {
            player.step(world, intent(false, false, false, true), DT);   // 一路下降
        }
        assertEquals(0.0, player.fallDistance(), 1e-9,
                "飞行中的下降不是坠落，不得累计坠落距离");
        assertEquals(0, player.lastFallDamage());
    }

    @Test
    void thePlayerCanFlyOutOfTheVoid() {
        World world = TestWorlds.flatWorld();
        Player player = playerOnFlatGround();
        player.setCreativeMode(true);
        player.applyLoadedState(0.5, -50.0, 0.5, 0.0, 0.0, null, List.of(), 0, 0);
        player.step(world, PlayerIntent.NONE, DT);
        assertEquals(Coords.VOID_KILL_Y, player.position().y, 1e-6, "前提：先停在虚空底部");

        player.setFlying(true);
        for (int i = 0; i < 180; i++) {
            player.step(world, intent(false, false, true, false), DT);   // 按住空格上升
        }
        assertTrue(player.position().y > Coords.VOID_KILL_Y + 5,
                "★ 停在虚空底部之后必须能飞出来，否则创造模式的虚空就是一个单向陷阱");
    }
}

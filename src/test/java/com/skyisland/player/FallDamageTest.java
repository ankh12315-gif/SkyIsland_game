package com.skyisland.player;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坠落伤害测试（Pre-M2 Corrective Closure E1；PRD 5.3）。
 *
 * <p>规则：{@code 伤害 = max(0, floor(坠落格数 − 3))}。
 * 3 格无伤、4 格 1 点、5 格 2 点。
 *
 * <p><b>这些用例为什么必须存在：</b>MVP_AUDIT 发现 MVP-SURV-003 / MVP-GATE-005
 * （坠落伤害 + "坠落 4 格造成 1 点伤害"）在 M1 完全未实现，
 * 而它们<b>本就是 PRD §11 的 M1 通过标准第 5 条</b> —— M1 当年在未验证该项的情况下判了 PASS。
 * 本文件把这条历史漏验补成可执行断言。
 *
 * <p><b>关于"起跳高度基准"：</b>碰撞求解会给脚底留 {@code 1e-4} 贴面余量，
 * 所以"站在地表时的脚底 y"不是整数。基准值由 {@link #restFeetY()} 实测得到，
 * 落差一律相对它构造，避免把 3.9999 当成 4 格。
 */
class FallDamageTest {

    private static final double DT = 1.0 / 60.0;

    private static World flat() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    /** 站在平坦地表时的脚底 y（含贴面余量），由实测得出而不是硬编码。 */
    private static double restFeetY() {
        Player probe = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        run(probe, flat(), PlayerIntent.NONE, 200);
        return probe.position().y;
    }

    private static void run(Player player, World world, PlayerIntent intent, int steps) {
        for (int i = 0; i < steps; i++) {
            player.step(world, intent, DT);
        }
    }

    /** 一直跑到落地为止；返回用掉的步数。跑满上限仍未落地则判定为失败前提。 */
    private static int runUntilGrounded(Player player, World world, int maxSteps) {
        for (int i = 1; i <= maxSteps; i++) {
            player.step(world, PlayerIntent.NONE, DT);
            if (player.onGround()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从 restY + blocks 格的高度自由落体到地表，返回<b>落地后的玩家</b>。
     *
     * <p>为什么返回整个 {@code Player} 而不是只返回 {@code lastFallDamage()}：
     * {@code lastFallDamage} 是<u>中间变量</u>，它变对不代表伤害真的施加了。
     * 断言必须落到 {@code health()} 这个可观测后果上（本轮 T7 修复的一部分）。
     */
    private static Player land(double blocks) {
        World world = flat();
        Player player = new Player(0.5, restFeetY() + blocks, 0.5);
        int steps = runUntilGrounded(player, world, 600);
        assertTrue(steps > 0, "前提不成立：玩家在 600 步内没有落地");
        return player;
    }

    /** 从 restY + blocks 格的高度自由落体，返回结算时实测到的落差。 */
    private static double fallDistanceFrom(double blocks) {
        World world = flat();
        Player player = new Player(0.5, restFeetY() + blocks, 0.5);
        int steps = runUntilGrounded(player, world, 600);
        assertTrue(steps > 0, "前提不成立：玩家在 600 步内没有落地");
        return player.lastFallDistance();
    }

    // ============================================================ 公式

    @Test
    @DisplayName("PRD 公式：3 格无伤 / 4 格 1 点 / 5 格 2 点")
    void damageFormulaMatchesPrd() {
        assertEquals(0, Player.fallDamageFor(0.0));
        assertEquals(0, Player.fallDamageFor(3.0), "坠落 3 格：max(0, floor(3-3)) = 0");
        assertEquals(1, Player.fallDamageFor(4.0), "坠落 4 格：max(0, floor(4-3)) = 1");
        assertEquals(2, Player.fallDamageFor(5.0));
        assertEquals(0, Player.fallDamageFor(Double.NaN), "非有限值不得凭空造出伤害");
        assertEquals(0, Player.fallDamageFor(-10.0));
    }

    // ============================================================ 实机坠落

    @Test
    @DisplayName("实机：坠落 3 格不掉伤害")
    void fallingThreeBlocksDealsNoDamage() {
        assertEquals(3.0, fallDistanceFrom(3.0), 0.05, "实测落差应约等于 3 格");
        Player player = land(3.0);
        assertEquals(0, player.lastFallDamage(), "中间量：3 格 = max(0, floor(3−3)) = 0");
        assertEquals(Player.MAX_HEALTH, player.health(),
                "★ 3 格坠落后生命必须纹丝不动（下限那一侧也要钉住，否则会把「落地一律扣血」误当修好）");
    }

    @Test
    @DisplayName("实机：坠落 4 格造成 1 点伤害")
    void fallingFourBlocksDealsOneDamage() {
        assertEquals(4.0, fallDistanceFrom(4.0), 0.05, "实测落差应约等于 4 格");
        Player player = land(4.0);
        assertEquals(1, player.lastFallDamage(), "中间量：4 格 = floor(4−3) = 1");
        assertEquals(1, Player.MAX_HEALTH - player.health(),
                "★ 生命必须真的下降 1 点（旧代码此处为 0 —— 只算不施加）");
    }

    @Test
    @DisplayName("实机：坠落 5 格造成 2 点伤害")
    void fallingFiveBlocksDealsTwoDamage() {
        assertEquals(5.0, fallDistanceFrom(5.0), 0.05, "实测落差应约等于 5 格");
        Player player = land(5.0);
        assertEquals(2, player.lastFallDamage(), "中间量：5 格 = floor(5−3) = 2");
        assertEquals(2, Player.MAX_HEALTH - player.health(),
                "★ 生命必须真的下降 2 点（旧代码此处为 0）");
    }

    /**
     * T7 的钉子：坠落必须<b>真的把生命扣掉</b>，并且来源归因为 {@link Player.DamageCause#FALL}。
     *
     * <p>这是"断言名断言语义、断言体测中间变量"的反面 —— 它断言的是玩家的
     * {@code health()} 这个可观测后果，而不再是 {@code lastFallDamage()} 这个中间量。
     * 旧代码（算完不施加）下本用例必红：{@code lastFallDamage()} 是 1，而 {@code health()} 仍是 20。
     */
    @Test
    @DisplayName("实机：坠落 4 格真的扣 1 点生命，且来源归因为「坠落」")
    void fallDamageActuallyReducesHealthAndIsAttributedToFall() {
        Player player = land(4.0);
        assertEquals(1, player.lastFallDamage(), "中间量：坠落 4 格 = floor(4−3) = 1");
        assertEquals(1, Player.MAX_HEALTH - player.health(),
                "★ 生命值必须真的下降 1 点（旧代码此处为 0，因为从不调用 hurt）");
        assertEquals(Player.DamageCause.FALL, player.lastDamageCause(),
                "来源必须归因为坠落（不是 GENERIC，也不是近战）");
    }

    // ============================================================ 不得误判

    @Test
    @DisplayName("原地跳跃后正常落地：不得产生伤害")
    void jumpingAndLandingDealsNoDamage() {
        World world = flat();
        Player player = new Player(0.5, restFeetY(), 0.5);
        run(player, world, PlayerIntent.NONE, 60);

        player.step(world, PlayerIntent.moving(0f, 0f, true), DT);   // 起跳
        run(player, world, PlayerIntent.NONE, 120);                 // 落回地面并静止

        assertTrue(player.onGround(), "前提不成立：跳跃后没有回到地面");
        assertEquals(0, player.lastFallDamage(),
                "原地起跳再落回原地不是坠落，不得结算伤害");
        assertEquals(Player.MAX_HEALTH, player.health(),
                "★ 原地起跳再落地不得掉血（跳跃上升段不计入坠落距离）");
    }

    @Test
    @DisplayName("分段台阶：分 4 次各降 1 格，累计 4 格但不得产生伤害")
    void steppingDownStairsDoesNotAccumulate() {
        int stone = TestWorlds.stone();
        // 每级踏面刻意做成 2 格深：下落 1 格耗时约 0.25 s，水平速度 4.317 格/s，
        // 因此一次踏空约前移 1.08 格 —— 踏面只有 1 格深时会连跨两级，测的就不是"分段"了。
        World world = TestWorlds.scatteredWorld(-1, -1, 1, 1,
                TestWorlds.cell(0, 63, 0, stone),      // 顶面 64
                TestWorlds.cell(0, 63, -1, stone),
                TestWorlds.cell(0, 62, -2, stone),     // 顶面 63
                TestWorlds.cell(0, 62, -3, stone),
                TestWorlds.cell(0, 61, -4, stone),     // 顶面 62
                TestWorlds.cell(0, 61, -5, stone),
                TestWorlds.cell(0, 60, -6, stone),     // 顶面 61（以下为平台，防止走出边界）
                TestWorlds.cell(0, 60, -7, stone),
                TestWorlds.cell(0, 60, -8, stone),
                TestWorlds.cell(0, 60, -9, stone),
                TestWorlds.cell(0, 60, -10, stone));

        Player player = new Player(0.5, 64.0, 0.5);
        double maxFallDistance = 0;
        int maxDamage = 0;
        for (int i = 0; i < 150; i++) {
            player.step(world, PlayerIntent.moving(1f, 0f, false), DT);
            maxFallDistance = Math.max(maxFallDistance, player.fallDistance());
            maxDamage = Math.max(maxDamage, player.lastFallDamage());
        }

        assertEquals(0, player.deaths(), "前提不成立：测试过程中玩家掉出了世界");
        assertTrue(player.onGround(), "前提不成立：走完台阶后玩家没有站在地面上");
        assertEquals(0, maxDamage,
                "每段只有 1 格落差，落地即清零、不得跨段累计（实测最大落差 %.4f）"
                        .formatted(maxFallDistance));
        assertTrue(maxFallDistance < 1.5,
                "每次落地都应清零坠落距离，实测最大 %.4f 格".formatted(maxFallDistance));
        assertEquals(Player.MAX_HEALTH, player.health(),
                "★ 走完 4 级台阶（每级 1 格）不得掉血：每次落地的落差都低于 3 格阈值");
    }

    @Test
    @DisplayName("空中不得连续扣血：伤害只在落地那一步结算一次")
    void damageIsSettledOnlyOnLanding() {
        World world = flat();
        Player player = new Player(0.5, restFeetY() + 6.0, 0.5);

        int settlements = 0;
        int previous = 0;
        for (int i = 0; i < 300; i++) {
            player.step(world, PlayerIntent.NONE, DT);
            if (player.lastFallDamage() != previous) {
                settlements++;
                previous = player.lastFallDamage();
            }
        }

        assertEquals(1, settlements, "整个下落—落地过程只应结算一次坠落伤害");
        assertEquals(3, player.lastFallDamage(), "6 格坠落：floor(6-3) = 3");
        assertEquals(Player.MAX_HEALTH - 3, player.health(),
                "★ 6 格坠落应恰好扣 3 点（且只扣这一次 —— 空中不得连续扣血）");
    }

    // ============================================================ 不得继承

    @Test
    @DisplayName("虚空死亡后重生：坠落状态清零，不得继承 fallDistance")
    void voidDeathClearsFallState() {
        // 空世界：没有任何方块，玩家必然坠入虚空
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0);
        Player player = new Player(0.5, 64.0, 0.5);

        boolean died = false;
        for (int i = 0; i < 900 && !died; i++) {
            player.step(world, PlayerIntent.NONE, DT);
            died = player.deaths() > 0;
        }

        assertTrue(died, "前提不成立：空世界里玩家没有触发虚空死亡");
        assertEquals(0.0, player.fallDistance(), "重生当帧坠落距离必须清零");
        assertEquals(0, player.lastFallDamage(), "虚空死亡走的是触底判定，不得结算普通坠落伤害");
        assertEquals(Player.DamageCause.VOID, player.lastDamageCause(),
                "★ 虚空死亡的归因必须是 VOID，而不是 FALL —— 证明它确实没走坠落结算这条路径");
    }
}

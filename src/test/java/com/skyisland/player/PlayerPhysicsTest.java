package com.skyisland.player;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.util.Coords;
import com.skyisland.world.World;
import com.skyisland.world.gen.TestWorldGenerator;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家物理与交互测试（M1 指令 B8–B13；TECH_DESIGN §I.1 的规格表）。
 *
 * <p><b>这些测试是"手工试玩"的替代品，而不是补充。</b>本机确认无法向窗口注入合成键盘输入
 * （TR7：{@code SendInput} 到任何窗口都收不到回调），因此"移动/跳跃/碰撞/挖掘/放置
 * 到底有没有实现"只能靠这种<u>直接驱动 {@link Player#step} 的确定性测试</u>来举证。
 *
 * <p>规格常量与断言值的关系必须一眼可见，所以这里刻意把推导写进断言消息：
 * <ul>
 *   <li>跳跃：{@code v²/(2g) = 8.95²/64 = 1.2518}；离散积分下实测约 1.176
 *       （因为每步"先跳后重力"，第一步就已被削掉 {@code g·dt}）；</li>
 *   <li>落地：脚底位置恒为 {@code 63 + 1 + 1e-4 = 64.0001}，把"贴面吸附"这件事写死；</li>
 *   <li>虚空：{@code y < -8} 当帧直接致死，<b>再等 3 秒重生</b>（M2 起引入的死亡倒计时，
 *       见 {@link Player#isDead()} 与 {@link Player#deathTimer()}；
 *       {@code deaths()} 计的是"已完成的重生"，不是"死亡帧"）。</li>
 * </ul>
 */
class PlayerPhysicsTest {

    /** 逻辑步长恒为 1/60 s（与 {@code GameLoop.FIXED_DT} 一致）。 */
    private static final double DT = 1.0 / 60.0;

    private static final double GROUND_Y = TestWorlds.SURFACE_FEET_Y;   // 64.0

    /** 平坦世界的四壁外扩一格，避免任何测试意外走到未加载区块。 */
    private static World world() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    private static Player playerAt(double x, double y, double z) {
        return new Player(x, y, z);
    }

    private static void run(Player player, World world, PlayerIntent intent, int steps) {
        for (int i = 0; i < steps; i++) {
            player.step(world, intent, DT);
        }
    }

    private static void settle(Player player, World world) {
        run(player, world, PlayerIntent.NONE, 120);
    }

    /**
     * 推进到"死亡倒计时走完、玩家已复活"为止。
     *
     * <p><b>M2 起虚空不再当帧重生。</b>M1 的语义是"y &lt; −8 → 立刻回安全点"，
     * 因此当时 {@code deaths()} 在踩空那一帧就 +1；M2 按 PRD 5.3 改成
     * "直接致死 → 倒下 3 秒 → 重生"，{@code deaths()} 随 {@code respawn()} 一起发生。
     * 断言必须等倒计时走完，否则测的是死亡帧而不是重生。
     *
     * <p>护栏 10 秒：倒计时坏掉时（例如 {@code step} 的死亡短路吞掉了
     * {@code updateDeathTimer}）测试会明确报错，而不是静默通过。
     */
    private static void advanceUntilRespawn(Player player, World world) {
        int guard = 0;
        while (player.isDead()) {
            player.step(world, PlayerIntent.NONE, DT);
            if (++guard > 600) {
                throw new AssertionError("死亡倒计时超过 10 秒仍未重生，重生链路可能断了");
            }
        }
    }

    // ============================================================ 初始状态

    @Test
    void constructorSeedsPositionAndCamera() {
        Player player = playerAt(0.5, GROUND_Y, 0.5);

        assertEquals(0.5, player.position().x, 1e-12);
        assertEquals(GROUND_Y, player.position().y, 1e-12);
        assertEquals(0.5, player.position().z, 1e-12);
        assertEquals(GROUND_Y + Player.EYE_HEIGHT, player.camera().y(), 1e-12,
                "相机位置 = 脚底 + 眼高，这个换算由 Player 负责");
        assertEquals(0.0, player.velocity().y, 1e-12);

        assertEquals(0, player.deaths());
        assertEquals(0, player.blocksBroken());
        assertEquals(0, player.blocksPlaced());
        assertEquals(0.0, player.walkDistance(), 1e-12);
        assertEquals(0, player.inventory().totalItemCount());
        assertFalse(player.onGround(), "构造时不假定站在地上，交给第一步的站立判定");
        assertNull(player.currentTarget());
        assertFalse(player.isMining());
        assertEquals("-", player.miningTargetId());
    }

    @Test
    void declaredSpecConstantsMatchTheDesignTable() {
        assertEquals(0.3, Player.HALF_WIDTH, 1e-12);
        assertEquals(1.8, Player.HEIGHT, 1e-12);
        assertEquals(1.62, Player.EYE_HEIGHT, 1e-12);
        assertEquals(32.0, Player.GRAVITY, 1e-12);
        assertEquals(8.95, Player.JUMP_VELOCITY, 1e-12, "起跳初速 8.95（不是 9.0）");
        assertEquals(60.0, Player.TERMINAL_VELOCITY, 1e-12, "向下终端速度 60（不是 78）");
        assertEquals(4.317, Player.WALK_SPEED, 1e-12);
        assertEquals(5.0, Player.REACH, 1e-12);
        // JUMP_HEIGHT 是<u>推导量</u>而不是独立常量。如果这里写死 1.25，
        // 一旦有人改了 JUMP_VELOCITY，断言仍然通过而 v²/2g 已经悄悄脱节。
        // 所以先钉"推导关系"，再钉"落在规格表的 1.25 档位"。
        double derivedJumpHeight = (Player.JUMP_VELOCITY * Player.JUMP_VELOCITY) / (2 * Player.GRAVITY);
        assertEquals(derivedJumpHeight, Player.JUMP_HEIGHT, 1e-12,
                "跳跃高度必须由 v²/(2g) 推出，不得独立硬编码");
        assertEquals(1.2516, Player.JUMP_HEIGHT, 1e-3, "v²/2g = 8.95²/64 ≈ 1.2516，规格表取 1.25");
        assertTrue(Player.JUMP_HEIGHT > 1.0 && Player.JUMP_HEIGHT < 2.0,
                "跳跃高度必须落在「能上 1 格、上不了 2 格」之间");
    }

    // ============================================================ 重力与碰撞

    @Test
    void gravityPullsThePlayerDownOntoTheTerrain() {
        World world = world();
        Player player = playerAt(0.5, 70.0, 0.5);

        run(player, world, PlayerIntent.NONE, 120);

        assertEquals(GROUND_Y, player.position().y, 0.01,
                "脚底必须停在方块 63 的顶面 + 碰撞 epsilon 上");
        assertEquals(0.0, player.velocity().y, 1e-9, "落地后竖直速度必须被清零");
        assertTrue(player.onGround(), "站立判定靠向下探测，静止时也必须为真");
    }

    @Test
    void playerDoesNotSinkOrTunnelThroughTheTerrain() {
        World world = world();
        Player player = playerAt(0.5, 70.0, 0.5);
        settle(player, world);

        double landedY = player.position().y;
        run(player, world, PlayerIntent.NONE, 300);   // 5 秒持续静置

        assertEquals(landedY, player.position().y, 1e-9,
                "长期静置不得缓慢下沉（站立判定与碰撞求解必须一致）");
        assertTrue(player.onGround());
    }

    @Test
    void terminalVelocityCapsFallSpeed() {
        World world = world();
        // 从世界顶部自由落体，观察出现过的最大下落速度
        Player player = playerAt(0.5, 120.0, 0.5);
        double fastest = 0;

        for (int i = 0; i < 200; i++) {
            player.step(world, PlayerIntent.NONE, DT);
            fastest = Math.min(fastest, player.velocity().y);
        }

        assertTrue(fastest <= -1.0, "自由落体必须真的加速：实测最快 " + fastest);
        assertTrue(fastest >= -Player.TERMINAL_VELOCITY - 1e-9,
                "下落速度不得突破终端速度 " + Player.TERMINAL_VELOCITY + "，实测 " + fastest);
    }

    @Test
    void walkingIntoWallIsStoppedAtTheContactFace() {
        World world = world();
        // 在 x=2 立一道两层高的墙（第二层靠第一层支撑，因此两次放置都合法）
        assertTrue(world.placeBlock(2, TestWorlds.SURFACE_BLOCK_Y + 1, 0, TestWorlds.planks(),
                World.MutationCause.SELF_TEST, null).success());
        assertTrue(world.placeBlock(2, TestWorlds.SURFACE_BLOCK_Y + 2, 0, TestWorlds.planks(),
                World.MutationCause.SELF_TEST, null).success());

        Player player = playerAt(0.5, GROUND_Y, 0.5);
        player.camera().setAngles(-90, 0);   // yaw=-90 → 前向 = +X
        run(player, world, PlayerIntent.moving(1f, 0f, false), 90);

        assertEquals(2.0 - Player.HALF_WIDTH, player.position().x, 0.01,
                "必须停在墙面外 halfWidth + epsilon 处，而不是穿进墙里");
        assertTrue(player.position().x < 2.0, "绝不越过墙面");
        assertTrue(player.onGround(), "撞墙不应把玩家抬离地面");
    }

    @Test
    void collisionAlongOneAxisDoesNotBlockTheOther() {
        World world = world();
        // 只在 z=0 立墙：(0.5, 64.5, -0.5) 的玩家沿 -Z 会被挡住，但横向仍可移动
        assertTrue(world.placeBlock(0, TestWorlds.SURFACE_BLOCK_Y + 1, 0, TestWorlds.planks(),
                World.MutationCause.SELF_TEST, null).success());

        Player player = playerAt(0.5, GROUND_Y, -0.5);
        run(player, world, PlayerIntent.moving(1f, 1f, false), 60);

        // 前向 -Z 被墙挡住，但右向 +X 应当真的移动了（分轴求解的直接结论）
        assertTrue(player.position().x > 1.0, "横向不该被纵向的碰撞牵连，x=" + player.position().x);
    }

    // ============================================================ 移动

    @Test
    void walkingForwardAtYawZeroMovesTowardMinusZ() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        double startX = player.position().x;
        double startZ = player.position().z;

        run(player, world, PlayerIntent.moving(1f, 0f, false), 60);

        assertTrue(player.position().z < startZ - 3.0,
                "yaw=0 时前向即 −Z，1 秒应走过 3 格以上，实际 Δz=" + (player.position().z - startZ));
        assertEquals(startX, player.position().x, 0.01, "纯前进不应产生横向偏移");
        assertTrue(player.walkDistance() > 3.0, "行走距离应累加，实际 " + player.walkDistance());
    }

    @Test
    void strafingMovesAlongTheCameraRightVector() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        double startX = player.position().x;

        run(player, world, PlayerIntent.moving(0f, 1f, false), 60);

        assertTrue(player.position().x > startX + 3.0,
                "yaw=0 时右向量为 +X，实际 Δx=" + (player.position().x - startX));
    }

    @Test
    void diagonalMovementIsNotFasterThanStraightMovement() {
        World worldA = world();
        Player straight = playerAt(0.5, GROUND_Y, 0.5);
        settle(straight, worldA);
        run(straight, worldA, PlayerIntent.moving(1f, 0f, false), 60);
        double straightDistance = straight.walkDistance();

        World worldB = world();
        Player diagonal = playerAt(0.5, GROUND_Y, 0.5);
        settle(diagonal, worldB);
        run(diagonal, worldB, PlayerIntent.moving(1f, 1f, false), 60);
        double diagonalDistance = diagonal.walkDistance();

        assertEquals(straightDistance, diagonalDistance, 0.05,
                "斜向移动必须归一化，否则'按两个键跑得更快'成为隐形的速度加成");
    }

    @Test
    void walkDistanceIgnoresPureVerticalMotion() {
        World world = world();
        Player player = playerAt(0.5, 90.0, 0.5);

        run(player, world, PlayerIntent.NONE, 25);   // 高空自由落体，尚未落地

        assertFalse(player.onGround());
        assertEquals(0.0, player.walkDistance(), 1e-9,
                "walkDistance 只统计水平位移，否则'站不稳'会被记成'走了很远'");
    }

    // ============================================================ 跳跃

    @Test
    void jumpClearsOneBlockButNotTwo() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        double groundY = player.position().y;
        double maxY = groundY;
        boolean airborneObserved = false;

        for (int i = 0; i < 60; i++) {
            PlayerIntent intent = i < 3 ? PlayerIntent.moving(0f, 0f, true) : PlayerIntent.NONE;
            player.step(world, intent, DT);
            maxY = Math.max(maxY, player.position().y);
            if (!player.onGround()) {
                airborneObserved = true;
            }
        }

        double height = maxY - groundY;
        assertTrue(airborneObserved, "起跳后必须真的离地");
        assertTrue(height >= 1.0,
                "跳跃高度必须 ≥ 1.0 格才能上台阶，实测 " + height);
        assertTrue(height < 1.45,
                "跳跃高度必须 < 1.45 格（跳不上 2 格），实测 " + height);
        assertTrue(player.onGround(), "1 秒内必须已经落地（滞空约 0.57 秒）");
    }

    @Test
    void holdingJumpDoesNotStackTheInitialVelocity() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        double groundY = player.position().y;
        double maxY = groundY;
        for (int i = 0; i < 120; i++) {
            player.step(world, PlayerIntent.moving(0f, 0f, true), DT);
            maxY = Math.max(maxY, player.position().y);
        }

        assertTrue(maxY - groundY < 1.45,
                "按住空格不得叠加起跳初速（applyJump 必须是赋值而不是累加），实测 "
                        + (maxY - groundY));
    }

    @Test
    void jumpingWhileAirborneHasNoEffect() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        // 先跳起来，再在空中持续请求跳跃
        player.step(world, PlayerIntent.moving(0f, 0f, true), DT);
        double afterLaunch = player.position().y;
        double velocityAfterLaunch = player.velocity().y;

        for (int i = 0; i < 5; i++) {
            player.step(world, PlayerIntent.moving(0f, 0f, true), DT);
        }

        assertTrue(velocityAfterLaunch > 0, "起跳后竖直速度应为正，实测 " + velocityAfterLaunch);
        assertTrue(player.position().y > afterLaunch, "起跳后应继续上升");
        assertTrue(player.velocity().y < velocityAfterLaunch,
                "空中无法再次起跳，竖直速度必须持续被重力削减");
    }

    // ============================================================ 虚空与重生

    /**
     * M2 语义：虚空 → 当帧致死 → 3 秒后重生在<b>世界固定点</b>。
     *
     * <p>注意重生点不是 {@code lastSafePosition}（PRD 5.3.1 把两者职责分开）：
     * B 节的重生点是 (0, 64, 0) + 螺旋搜索；A 节的 lastSafePosition 只用于
     * <b>虚空死亡的掉落物落点</b>。这里两条都断言，正是为了钉住这个分工。
     */
    @Test
    void fallingIntoVoidKillsThenRespawnsAtTheWorldCenter() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        // 站稳后安全点已被记录（0.5 s 节流），它是虚空死亡的掉落物落点
        Vector3d safeBefore = player.lastSafePosition();

        player.teleport(0.5, -30.0, 0.5);
        player.step(world, PlayerIntent.NONE, DT);

        assertTrue(player.isDead(), "y < " + Coords.VOID_KILL_Y + " 必须直接致死");
        assertEquals(0, player.deaths(), "死亡帧只进入倒计时，不算一次已完成的重生");
        assertTrue(player.lastDeathDropPosition().y > Coords.VOID_KILL_Y,
                "掉落点必须在虚空线之上（PRD 5.3.1 A：严禁坠入虚空）");
        assertEquals(safeBefore.z, player.lastDeathDropPosition().z, 0.01,
                "虚空死亡的掉落物应当落在 lastSafePosition 附近");

        advanceUntilRespawn(player, world);

        assertEquals(1, player.deaths());
        assertEquals(0.5, player.position().x, 0.01, "重生点是世界中心 (0, 64, 0)，不是坠落处");
        assertEquals(TestWorlds.SURFACE_FEET_Y, player.position().y, 0.01);
        assertEquals(0.5, player.position().z, 0.01);
        assertEquals(0.0, player.velocity().y, 1e-9, "重生必须清零速度，否则会继续下坠");
        assertTrue(player.isStandingSpotValid(world,
                player.position().x, player.position().y, player.position().z));
    }

    @Test
    void voidDeathThresholdIsExclusiveAtMinusEight() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        int deathsBefore = player.deaths();

        player.teleport(0.5, Coords.VOID_KILL_Y + 0.5, 0.5);
        player.step(world, PlayerIntent.NONE, DT);
        assertFalse(player.isDead(), "y > −8 时还不该死");
        assertEquals(deathsBefore, player.deaths());

        player.teleport(0.5, Coords.VOID_KILL_Y - 0.5, 0.5);
        player.step(world, PlayerIntent.NONE, DT);
        assertTrue(player.isDead(), "y < −8 当帧致死");

        advanceUntilRespawn(player, world);
        assertEquals(deathsBefore + 1, player.deaths(), "跨过虚空线且完成重生后，死亡计数 +1");
    }

    @Test
    void forcedRespawnIsDrivenThroughTheIntentPath() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        player.teleport(0.5, 100.0, 0.5);
        // F9 的语义：重生走的是 PlayerIntent，而不是从 UI 直接调 respawn()
        player.step(world, PlayerIntent.NONE.withRespawn(), DT);

        assertEquals(1, player.deaths());
        assertEquals(GROUND_Y, player.position().y, 0.01, "强制重生应回到世界中心的落脚点");
    }

    @Test
    void respawnCountsEveryDeath() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        // 每次死亡都要等 3 秒倒计时走完才能再次坠亡 —— 倒计时期间 step() 直接短路，
        // 此时再 teleport 进虚空是无效的（这一点本身也值得钉住）。
        for (int i = 0; i < 3; i++) {
            player.teleport(0.5, -30.0, 0.5);
            player.step(world, PlayerIntent.NONE, DT);
            assertTrue(player.isDead(), "第 " + (i + 1) + " 次坠入虚空必须致死");
            advanceUntilRespawn(player, world);
        }

        assertEquals(3, player.deaths());
    }

    // ============================================================ 挖掘

    @Test
    void lookingStraightDownAndHoldingAttackBreaksTheBlockUnderfoot() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        player.camera().setAngles(0, -89.5);   // 视线垂直向下

        run(player, world, PlayerIntent.of(0f, 0f, false, 0, 0, true, false), 120);

        assertTrue(player.blocksBroken() >= 1, "破坏计数应增加");
        assertTrue(world.isAirAt(0, TestWorlds.SURFACE_BLOCK_Y, 0),
                "脚下那一格 (" + TestWorlds.SURFACE_BLOCK_Y + ") 必须变成空气");
        assertTrue(player.inventory().totalItemCount() >= 1,
                "M1/M2 都没有掉落物实体（ItemEntity 属 M3），挖到的方块直接进入快捷栏");
        // PRD 5.1 的「掉落物」列：草方块掉泥土 ×1。
        // M1 这里断言的是"掉草方块自身"—— 那正是审计缺口 G13（方块掉落表完全未实现）
        // 在测试里被固化的形态：错误的行为一旦有了断言，就不再显得像错误。
        assertEquals(TestWorlds.dirt(), player.inventory().slot(0).blockRuntimeId(),
                "PRD 5.1：草方块的掉落物是泥土，不是草方块自身（G13 修复点）");
    }

    @Test
    void miningProgressIsVisibleWhileDigging() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        player.camera().setAngles(0, -89.5);

        run(player, world, PlayerIntent.of(0f, 0f, false, 0, 0, true, false), 8);

        assertTrue(player.isMining(), "按住左键时应处于挖掘态");
        assertTrue(player.miningProgressFraction() > 0.0, "8 步（0.133 秒）应有可见进度");
        assertTrue(player.miningProgressFraction() < 1.0,
                "草方块硬度 0.6 秒，8 步不该挖穿，进度=" + player.miningProgressFraction());
        assertEquals(TestWorlds.SURFACE_BLOCK_Y, player.currentTarget().blockY(),
                "射线目标应当是脚下那一格");
        assertEquals(0, player.blocksBroken(), "还没挖穿，破坏计数应仍为 0");
    }

    @Test
    void releasingAttackResetsMiningState() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        player.camera().setAngles(0, -89.5);

        run(player, world, PlayerIntent.of(0f, 0f, false, 0, 0, true, false), 10);
        assertTrue(player.isMining());

        run(player, world, PlayerIntent.NONE, 1);

        assertFalse(player.isMining(), "松开左键必须立刻退出挖掘态");
        assertEquals(0.0, player.miningProgressFraction(), 1e-12, "进度必须归零（PRD：中断即重置）");
        assertEquals("-", player.miningTargetId());
    }

    @Test
    void switchingTargetResetsMiningProgress() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        player.camera().setAngles(0, -89.5);
        PlayerIntent attack = PlayerIntent.of(0f, 0f, false, 0, 0, true, false);
        run(player, world, attack, 20);

        double progressBefore = player.miningProgressFraction();
        assertTrue(progressBefore > 0.3, "20 步（0.333 秒）应有约 55% 进度，实测 " + progressBefore);
        assertNotNull(player.currentTarget());
        int minedColumnX = player.currentTarget().blockX();

        // ★ 换目标必须真的换到<u>另一格</u>。原地旋转 yaw 是不行的：垂直俯视时
        //   视线几乎贴着自己的脚垂直向下，转多少度命中的都是脚下同一格，
        //   于是进度只会继续累加（0.5556 → 0.5833），断言看起来"没重置"而其实是没换目标。
        //   这里把玩家平移到相隔 3 格的一列，脚下自然变成另一格。
        player.teleport(minedColumnX + 3.5, GROUND_Y, 0.5);
        player.camera().setAngles(0, -89.5);
        player.step(world, attack, DT);

        assertNotNull(player.currentTarget());
        assertNotEquals(minedColumnX, player.currentTarget().blockX(), "前提：射线目标确实换成了另一格");
        // 换算：progressAfter = 1·DT/h，progressBefore = 20·DT/h ⇒ 比值恒为 1/20，
        // 与方块硬度无关。因此用"小于 1/5"而不是"等于 0"，
        // 既允许"本步已开始累积"，又能抓住"根本没重置"。
        assertTrue(player.miningProgressFraction() < progressBefore / 5,
                "换目标必须从零重新开始（PRD 明确要求），进度 " + progressBefore
                        + " → " + player.miningProgressFraction());
    }

    @Test
    void unbreakableBlockIsNeverBroken() {
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(0, TestWorlds.SURFACE_BLOCK_Y, 0, TestWorlds.resourceCore()));
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        player.camera().setAngles(0, -89.5);

        run(player, world, PlayerIntent.of(0f, 0f, false, 0, 0, true, false), 200);

        assertEquals(0, player.blocksBroken(), "不可破坏方块永远挖不掉");
        assertFalse(world.isAirAt(0, TestWorlds.SURFACE_BLOCK_Y, 0));
        assertTrue(player.miningTargetId().contains("不可破坏"),
                "HUD 应显示被拒绝的原因，实际=" + player.miningTargetId());
        assertEquals(0.0, player.miningProgressFraction(), 1e-12, "不可破坏方块的进度恒为 0");
    }

    // ============================================================ 放置

    @Test
    void lookingAtGroundAndUsingPlacesTheHeldBlock() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        // 手上有方块才可能放置（M1 没有创造模式物品栏）
        player.inventory().setSlot(0, ItemStack.of(TestWorlds.planks(), 5));
        player.inventory().selectSlot(0);

        // 与脚本化自测同一套初始条件：退出到 (0.5,64,-5.5) 并低头 60°，
        // 水平前伸量 ≈ 眼高/tan60° ≈ 0.94 格，因此放置到的那一格不会被自己的碰撞箱占住
        player.teleport(0.5, GROUND_Y, -5.5);
        player.camera().setAngles(0, -60);

        player.step(world, PlayerIntent.of(0f, 0f, false, 0, 0, false, true), DT);

        assertNotNull(player.currentTarget(), "视线必须命中地面");
        assertEquals(1, player.blocksPlaced(), "放置计数应为 1，最近反馈：" + player.lastPlacementMessage());

        RaycastHitHint hint = placedCell(player);
        assertEquals(TestWorlds.planks(), world.blockIdAt(hint.x, hint.y, hint.z),
                "放置位置的方块 ID 必须与手持一致，实际位置 (" + hint.x + "," + hint.y + "," + hint.z + ")");
        assertEquals(4, player.inventory().slot(0).count(), "放置必须消耗 1 个");
    }

    /** 小工具：从玩家最后的目标推算"刚刚放置到哪一格"。 */
    private record RaycastHitHint(int x, int y, int z) {
    }

    private static RaycastHitHint placedCell(Player player) {
        // 放置后射线目标仍指向被点击的地面方块，因此 adjacent 就是放置位置
        var hit = player.currentTarget();
        assertNotNull(hit);
        return new RaycastHitHint(hit.adjacentX(), hit.adjacentY(), hit.adjacentZ());
    }

    @Test
    void placementIsRejectedWhenTheSelectedSlotIsEmpty() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        player.teleport(0.5, GROUND_Y, -5.5);
        player.camera().setAngles(0, -60);

        player.step(world, PlayerIntent.of(0f, 0f, false, 0, 0, false, true), DT);

        assertEquals(0, player.blocksPlaced());
        assertEquals(1, player.placementRejections());
        assertTrue(player.lastPlacementMessage().contains("空"),
                "反馈应说明是空槽，实际=" + player.lastPlacementMessage());
    }

    @Test
    void placementIsRejectedWhenNothingIsInReach() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        player.inventory().setSlot(0, ItemStack.of(TestWorlds.planks(), 5));
        player.camera().setAngles(0, 89.5);   // 抬头看天

        player.step(world, PlayerIntent.of(0f, 0f, false, 0, 0, false, true), DT);

        assertNull(player.currentTarget(), "朝天上没有可命中的方块");
        assertEquals(0, player.blocksPlaced());
        assertTrue(player.placementRejections() >= 1);
        assertTrue(player.lastPlacementMessage().contains("没有瞄准"),
                "实际=" + player.lastPlacementMessage());
    }

    @Test
    void placementIsRejectedWhenTheTargetCellIsInsideThePlayer() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        player.inventory().setSlot(0, ItemStack.of(TestWorlds.planks(), 5));
        // 低头 60° 但站在格子正上方 → 命中点离自己不到一格，邻格与自己的碰撞箱重叠
        player.camera().setAngles(0, -89.5);

        player.step(world, PlayerIntent.of(0f, 0f, false, 0, 0, false, true), DT);

        assertEquals(0, player.blocksPlaced(), "不得把方块放进玩家自己的身体里");
        assertTrue(player.placementRejections() >= 1);
    }

    @Test
    void usingWithNothingHeldIsRejectedAndCounted() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        player.teleport(0.5, GROUND_Y, -5.5);
        player.camera().setAngles(0, -60);
        int rejectionsBefore = player.placementRejections();

        // 右键只取"按下沿"：连续两步都带 usePressed 只应产生一次尝试？
        // —— 不是。PlayerIntent 的 usePressed 是"本帧按下过"，自测脚本每步都构造 true
        //    就等于每步都按了一次；这里显式只放一步，避免把语义混在一起。
        player.step(world, PlayerIntent.of(0f, 0f, false, 0, 0, false, true), DT);

        assertEquals(rejectionsBefore + 1, player.placementRejections());
    }

    // ============================================================ 渲染插值

    @Test
    void interpolationMovesOnlyTheCameraNotTheAuthoritativePosition() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        run(player, world, PlayerIntent.moving(1f, 0f, false), 30);

        double authoritativeZ = player.position().z;
        double authoritativeY = player.position().y;

        player.applyInterpolatedCamera(1.0);
        double cameraZAtOne = player.camera().z();

        player.applyInterpolatedCamera(0.0);
        double cameraZAtZero = player.camera().z();

        player.applyInterpolatedCamera(0.5);
        double cameraZAtHalf = player.camera().z();

        assertEquals(authoritativeZ, player.position().z, 1e-12,
                "插值只允许改相机：写回权威位置会让玩家在没有逻辑步的帧里挖到不该挖的方块");
        assertEquals(authoritativeY, player.position().y, 1e-12);
        assertEquals(authoritativeZ, cameraZAtOne, 1e-12, "alpha=1 应精确落在当前步位置上");
        assertTrue(cameraZAtZero >= cameraZAtHalf && cameraZAtHalf >= cameraZAtOne,
                "向 −Z 前进时插值位置必须落在上一步与当前步之间："
                        + cameraZAtZero + " / " + cameraZAtHalf + " / " + cameraZAtOne);
    }

    @Test
    void interpolationClampsAlphaToUnitRange() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        player.applyInterpolatedCamera(-5.0);
        double clampedLow = player.camera().z();
        player.applyInterpolatedCamera(0.0);
        assertEquals(player.camera().z(), clampedLow, 1e-12, "负 alpha 必须被夹到 0");

        player.applyInterpolatedCamera(42.0);
        assertEquals(player.position().z, player.camera().z(), 1e-12, "大于 1 的 alpha 必须被夹到 1");
    }

    // ============================================================ 读档

    @Test
    void applyLoadedStateRestoresEverythingAtOnce() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        player.applyLoadedState(3.5, GROUND_Y, -7.5, 45.0, -10.0,
                new double[]{3.5, GROUND_Y, -7.5},
                List.of(ItemStack.of(TestWorlds.stone(), 12), ItemStack.EMPTY), 1, 4);

        assertEquals(3.5, player.position().x, 1e-12);
        assertEquals(-7.5, player.position().z, 1e-12);
        assertEquals(45.0, player.camera().yawDeg(), 1e-9);
        assertEquals(-10.0, player.camera().pitchDeg(), 1e-9);
        assertEquals(GROUND_Y + Player.EYE_HEIGHT, player.camera().y(), 1e-12,
                "读档后相机必须同步刷新，否则画面还朝着读档前的方向");
        assertEquals(0.0, player.velocity().y, 1e-12, "读档必须清零速度");
        assertEquals(4, player.deaths());
        assertEquals(1, player.inventory().selectedSlot());
        assertEquals(12, player.inventory().slot(0).count());
        assertEquals(0.0, player.walkDistance(), 1e-12);
        assertFalse(player.isMining());
        assertNull(player.currentTarget());
    }

    @Test
    void applyLoadedStateWithoutLastSafeFallsBackToCurrentPosition() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);

        player.applyLoadedState(1.5, GROUND_Y, 1.5, 0, 0, null, List.of(), 0, 0);

        assertEquals(1.5, player.lastSafePosition().x, 1e-12);
        assertEquals(1.5, player.lastSafePosition().z, 1e-12);
    }

    @Test
    void sanitizeFindsAFreeSpotWhenTheSavedPositionIsBuried() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);

        // y=63.5 → 方块 (0,63,0) 是草方块，玩家会被埋在里面
        player.applyLoadedState(0.5, TestWorlds.SURFACE_BLOCK_Y + 0.5, 0.5, 0, 0,
                null, List.of(), 0, 0);

        assertTrue(player.sanitizePositionAfterLoad(world), "被埋住的位置必须被修正");
        assertEquals(GROUND_Y, player.position().y, 0.01, "修正后应当站在地表顶面上");
        assertTrue(player.isStandingSpotValid(world,
                player.position().x, player.position().y, player.position().z));
    }

    @Test
    void sanitizeLeavesALegalPositionUntouched() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        double x = player.position().x;
        double y = player.position().y;
        double z = player.position().z;

        assertFalse(player.sanitizePositionAfterLoad(world),
                "合法位置不该被'修正'（否则每次读档都会漂移）");
        assertEquals(x, player.position().x, 1e-12);
        assertEquals(y, player.position().y, 1e-12);
        assertEquals(z, player.position().z, 1e-12);
    }

    @Test
    void isStandingSpotValidRequiresSolidBelowAndFreeSpaceAbove() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);

        assertTrue(player.isStandingSpotValid(world, 0.5, GROUND_Y, 0.5));
        assertFalse(player.isStandingSpotValid(world, 0.5, 100.0, 0.5), "半空中没有支撑");
        assertFalse(player.isStandingSpotValid(world, 0.5, TestWorlds.SURFACE_BLOCK_Y + 0.5, 0.5),
                "位置本身在实体方块内");
    }

    // ============================================================ 碰撞箱与辅助查询

    @Test
    void boundsAndOccupancyQueriesAgree() {
        Player player = playerAt(0.5, GROUND_Y, 0.5);

        assertTrue(player.occupiesBlock(0, 64, 0), "脚底那一格必须被判为被占据");
        assertTrue(player.occupiesBlock(0, 65, 0), "身体上半段那一格");
        assertFalse(player.occupiesBlock(0, 63, 0), "脚底下方那一格不算被占据");
        assertFalse(player.occupiesBlock(1, 64, 0));

        assertEquals(0.6, player.bounds().widthX(), 1e-12);
        assertEquals(1.8, player.bounds().heightY(), 1e-12);
    }

    @Test
    void eyePositionIsFeetPlusEyeHeight() {
        Player player = playerAt(1.25, 70.0, -3.5);
        assertEquals(1.25, player.eyePosition().x, 1e-12);
        assertEquals(70.0 + Player.EYE_HEIGHT, player.eyePosition().y, 1e-12);
        assertEquals(-3.5, player.eyePosition().z, 1e-12);
    }

    @Test
    void lastSafePositionGetterDoesNotLeakInternalState() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        var copy = player.lastSafePosition();
        copy.x = 9999;
        assertTrue(player.lastSafePosition().x < 100,
                "lastSafePosition() 必须返回拷贝，否则调用方能悄悄改掉重生点");
    }

    @Test
    void raycastTargetMatchesTheCameraDirection() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        player.camera().setAngles(0, -89.5);

        var hit = player.raycastTarget(world);

        assertNotNull(hit);
        assertEquals(0, hit.blockX());
        assertEquals(TestWorlds.SURFACE_BLOCK_Y, hit.blockY());
        assertEquals(0, hit.blockZ());
        assertEquals("UP", hit.faceName());
    }

    @Test
    void debugPlaceAtUsesTheWorldMutationApi() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);

        var result = player.debugPlaceAt(world, 4, TestWorlds.SURFACE_BLOCK_Y + 1, 4,
                TestWorlds.planks());

        assertTrue(result.success(), result.reason());
        assertEquals(TestWorlds.planks(), world.blockIdAt(4, TestWorlds.SURFACE_BLOCK_Y + 1, 4));
        assertEquals(0, player.blocksPlaced(), "调试入口不参与玩家记账");
    }

    @Test
    void toStringMentionsPositionAndInventory() {
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        String text = player.toString();
        assertTrue(text.startsWith("Player("), text);
        assertTrue(text.contains("HOTBAR["), text);
    }

    @Test
    void testWorldGeneratorSpawnIsAValidStandingSpot() {
        World world = new World(1L, new TestWorldGenerator());
        world.ensureAreaLoaded(TestWorldGenerator.MIN_CHUNK, TestWorldGenerator.MIN_CHUNK,
                TestWorldGenerator.MAX_CHUNK, TestWorldGenerator.MAX_CHUNK);
        Player player = playerAt(TestWorldGenerator.spawnX(),
                TestWorldGenerator.spawnY(), TestWorldGenerator.spawnZ());

        assertTrue(player.isStandingSpotValid(world, TestWorldGenerator.spawnX(),
                        TestWorldGenerator.spawnY(), TestWorldGenerator.spawnZ()),
                "M1 的出生点必须是一个合法落脚点，否则开局第一帧就触发位置修正");

        settle(player, world);
        assertTrue(player.onGround(), "出生点必须站得住：实际 " + player.position());
        assertEquals(TestWorldGenerator.spawnY(), player.position().y, 0.05);
    }

    // ============================================================ 松键之后会不会停（M1.5 缺陷回归）

    /**
     * <b>这组测试守护的是"移动的负向路径"。</b>
     *
     * <p>M1 的 42 项物理断言全都只问"能不能动起来"，没有一条问"松开之后会不会停"。
     * 而 {@code applyMovementInput} 当时在"没有移动输入"时<u>直接 return</u>，
     * 于是水平速度再也没人衰减 —— 玩家松开按键后会以行走速度一直滑到撞墙。
     * 这个缺陷在脚本化自测里永远不出现（每一步都显式给出意图，且从不检查静止），
     * 只由人工试玩暴露出来（"按一下方向键之后会一直平移"）。
     *
     * <p>教训写在这里以免重犯：<b>验证"启动"的断言不能替代验证"停止"。</b>
     * 任何由输入驱动的持续状态（移动、挖掘、加速）都必须有一条"输入撤走之后会收敛"的断言。
     */
    private static double horizontalSpeed(Player player) {
        return Math.hypot(player.velocity().x, player.velocity().z);
    }

    @Test
    void releasingMoveKeyStopsThePlayerOnGround() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        run(player, world, PlayerIntent.moving(1f, 0f, false), 60);   // 按住 W 走 1 秒
        double speedWhileHeld = horizontalSpeed(player);
        assertTrue(speedWhileHeld > 4.0,
                "前提：按住 1 秒后应接近 WALK_SPEED(" + Player.WALK_SPEED + ")，实际 " + speedWhileHeld);
        double zWhenReleased = player.position().z;

        run(player, world, PlayerIntent.NONE, 60);                     // 松开 1 秒

        assertTrue(horizontalSpeed(player) < 1e-6,
                "松开移动键后水平速度必须收敛到 0（M1 缺陷：无输入时速度从不衰减），实际 "
                        + horizontalSpeed(player));
        double glide = Math.abs(player.position().z - zWhenReleased);
        assertTrue(glide < 0.4, "松手后的滑行距离应远小于一格，实际 " + glide + " 格");
    }

    @Test
    @DisplayName("松手后 0.25 秒内速度就应降到不可见（指数逼近是渐近的，但收敛必须够快）")
    void stoppingIsFastEnoughToFeelImmediate() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        run(player, world, PlayerIntent.moving(1f, 0f, false), 60);

        run(player, world, PlayerIntent.NONE, 15);   // 0.25 秒

        assertTrue(horizontalSpeed(player) < Player.WALK_SPEED * 0.02,
                String.format("0.25 秒后速度应低于 %.3f，实际 %.5f",
                        Player.WALK_SPEED * 0.02, horizontalSpeed(player)));
    }

    @Test
    void stoppingDistanceMatchesTheDeclaredAcceleration() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        run(player, world, PlayerIntent.moving(1f, 0f, false), 60);

        double speed = horizontalSpeed(player);
        double zWhenReleased = player.position().z;
        run(player, world, PlayerIntent.NONE, 60);
        double glide = Math.abs(player.position().z - zWhenReleased);

        // 指数逼近下 v(t)=v0·e^{-k·t}，"从 v0 到 0"的距离收敛于 v0/k（与 dt 无关）
        double theoretical = speed / 18.0;   // GROUND_ACCEL = 18
        assertTrue(glide > theoretical * 0.5 && glide < theoretical * 1.5,
                String.format("滑行距离 %.4f 格应接近 v/k = %.4f 格（v=%.3f）",
                        glide, theoretical, speed));
    }

    @Test
    void releasingMoveKeyInAirKeepsMomentum() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);

        run(player, world, PlayerIntent.moving(1f, 0f, true), 4);      // 起跳并前进
        assertFalse(player.onGround(), "前提：应当已经在空中");
        double speedInAir = horizontalSpeed(player);

        run(player, world, PlayerIntent.NONE, 10);                     // 空中松开方向键

        assertEquals(speedInAir, horizontalSpeed(player), 1e-9,
                "空中松开方向键必须保留水平动量 —— 跳到一半'空中刹车'是明确的手感错误");
    }

    @Test
    void airbornePlayerStillStopsAfterLanding() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        run(player, world, PlayerIntent.moving(1f, 0f, true), 4);

        run(player, world, PlayerIntent.NONE, 240);                    // 松手并等落地

        assertTrue(player.onGround(), "应当已经落回地面");
        assertTrue(horizontalSpeed(player) < 1e-6,
                "落地后（无输入）必须停住，空中动量不得无限保留，实际 " + horizontalSpeed(player));
    }

    @Test
    void counterInputDeceleratesThroughZeroInsteadOfSnapping() {
        World world = world();
        Player player = playerAt(0.5, GROUND_Y, 0.5);
        settle(player, world);
        run(player, world, PlayerIntent.moving(1f, 0f, false), 60);

        double beforeZ = player.velocity().z;
        assertTrue(beforeZ < -3.0, "前提：yaw=0 时前进方向是 -Z，速度 z 应为负，实际 " + beforeZ);

        player.step(world, PlayerIntent.moving(-1f, 0f, false), DT);   // 反向按一帧
        double afterZ = player.velocity().z;

        assertTrue(afterZ > beforeZ, "反向输入必须让速度朝 0 靠拢，实际 " + beforeZ + " → " + afterZ);
        assertTrue(afterZ < 0, "一帧反向输入不应把速度直接翻到对侧，实际 " + afterZ);
    }
}

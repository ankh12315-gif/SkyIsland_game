package com.skyisland.entity;

import com.skyisland.player.Player;
import com.skyisland.player.PlayerIntent;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.util.Coords;
import com.skyisland.world.World;
import com.skyisland.world.block.BlockRegistry;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 实体与生存闭环测试：受击 / 近战怪 7 步 AI / 实体清理 / 生命与重生。
 */
class EntityCombatTest {

    private static final double DT = 1.0 / 60.0;

    /**
     * 贴面吸附后的静止脚底高度。
     *
     * <p>测试世界地表方块顶面是 {@code TestWorlds.SURFACE_FEET_Y = 64.0}，
     * 但碰撞求解会把玩家停在"接触面之上的一个极小余量"处 —— 实测 {@code 64.0001}，
     * 也就是 {@code 63（方块下标）+ 1（方块高）+ 1e-4}。
     * 这条余量在 {@code PlayerPhysicsTest} 的类注释里写死过（"落地：脚底位置恒为 63 + 1 + 1e-4"）。
     *
     * <p>{@code lastSafePosition} 记录的是<b>玩家实际站的位置</b>，不是"理论地表高度"，
     * 所以凡是断言真实站立坐标的地方都必须带上这 1e-4；断言"重生点"这类
     * 由 {@code findRespawnPoint} 主动算出来的坐标时才该用 {@code SURFACE_FEET_Y}。
     */
    private static final double RESTING_FEET_Y = TestWorlds.SURFACE_FEET_Y + 1e-4;

    private static World world() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    private static Player playerAt(double x, double y, double z) {
        return new Player(x, y, z);
    }

    // ============================================================ Entity 基类

    @Test
    void hurtReducesHealthAndStartsFlash() {
        MeleeMonster m = new MeleeMonster(0, 64, 0);
        assertEquals(20, m.maxHealth(), "PRD 5.5.1：近战怪生命 20");
        assertFalse(m.isHurtFlashing());

        m.hurt(8);
        assertEquals(12, m.health());
        assertTrue(m.isHurtFlashing(), "受击必须产生闪白状态（PRD 5.4.3）");
        assertTrue(m.isAlive());
    }

    @Test
    void flashDecaysBackToZero() {
        MeleeMonster m = new MeleeMonster(0, 64, 0);
        m.hurt(1);

        m.tick(world(), playerAt(100, 64, 100), 0.1);
        assertTrue(m.isHurtFlashing(), "0.1 秒时闪白尚未结束");
        m.tick(world(), playerAt(100, 64, 100), 0.1);
        assertFalse(m.isHurtFlashing(), "0.2 秒后闪白必须结束");
        assertEquals(0.0, m.hurtFlashSeconds(), 1e-9);
    }

    @Test
    void healthNeverGoesNegativeAndDeathIsOneWay() {
        MeleeMonster m = new MeleeMonster(0, 64, 0);
        m.hurt(999);
        assertEquals(0, m.health(), "生命不得为负");
        assertFalse(m.isAlive());

        m.hurt(1);
        assertEquals(0, m.health(), "已死亡的实体不再受伤害");
    }

    @Test
    void nonPositiveDamageIsIgnored() {
        MeleeMonster m = new MeleeMonster(0, 64, 0);
        m.hurt(0);
        m.hurt(-5);
        assertEquals(20, m.health());
        assertFalse(m.isHurtFlashing(), "0 伤害不得触发闪白这种假反馈");
    }

    // ============================================================ 近战怪 AI

    /** 第 6 步：接近玩家后攻击，每次 4 点伤害（PRD 5.5.1）。 */
    @Test
    void monsterAttacksPlayerWhenAdjacentAndDealsFourDamage() {
        World world = world();
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        MeleeMonster m = new MeleeMonster(1.2, TestWorlds.SURFACE_FEET_Y, 0.5);

        assertEquals(20, player.health());
        m.tick(world, player, DT);

        assertEquals(16, player.health(), "近战怪每次攻击 4 点伤害");
        assertEquals(1, m.attackCount());
    }

    /**
     * 竖直判定：玩家高出 7 格时，正下方的怪<b>不得</b>咬中。
     *
     * <p>这正是 2026-09-22 试玩里「看不见怪却一直掉血」的现场：怪在塔底、
     * 玩家站在 7 格高的台上，水平距离只有 0.7 格。旧代码的攻击判定不含竖直分量
     * （见 {@code docs/testing/M2_1_PLAYTEST_EVIDENCE_2026-09-22.md} §4.2），
     * 因此本用例在旧代码上必红：玩家会被连咬数口（每口 4 点）。
     */
    @Test
    void monsterCannotBiteAPlayerFarAbove() {
        World world = world();
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y + 7.0, 0.5);
        MeleeMonster m = new MeleeMonster(1.2, TestWorlds.SURFACE_FEET_Y, 0.5);

        // 水平距离 0.7 ≤ ATTACK_RANGE，旧代码会咬中；跑满数个攻击冷却窗口（≈ 3.3 s）
        for (int i = 0; i < 200; i++) {
            m.tick(world, player, DT);
        }

        assertEquals(Player.MAX_HEALTH, player.health(),
                "★ 玩家高出 7 格时不得被咬（旧代码此处会掉血）");
        assertEquals(0, m.attackCount(), "★ 攻击计数不得增加（旧代码此处 > 0）");
        assertTrue(Double.isNaN(m.lastBiteHorizontalDistance()),
                "从未咬击时几何仪器应保持 NaN（表示「本次会话尚未咬过」）");
    }

    /**
     * 反方向：竖直门不得把判定收紧到误伤正常情形。
     * 同层（Δy = 0）与一级台阶（Δy = 1）都必须仍能咬中，且记录的几何量自洽。
     */
    @Test
    void monsterStillBitesAtSameLevelAndOneBlockStep() {
        // 同层
        World world1 = world();
        Player onLevel = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        MeleeMonster sameLevel = new MeleeMonster(1.2, TestWorlds.SURFACE_FEET_Y, 0.5);
        sameLevel.tick(world1, onLevel, DT);
        assertEquals(16, onLevel.health(), "同层（Δy = 0）必须仍能咬中（每次 4 点）");
        assertEquals(1, sameLevel.attackCount());
        assertEquals(0.7, sameLevel.lastBiteHorizontalDistance(), 1e-9,
                "记录的水平距离 = |0.5 − 1.2| = 0.7 格");
        assertTrue(sameLevel.lastBiteHorizontalDistance() <= MeleeMonster.ATTACK_RANGE,
                "记录的水平距离必须落在 ATTACK_RANGE 之内");
        assertEquals(0.0, sameLevel.lastBiteVerticalOffset(), 1e-9, "同层记录的竖直 Δy ≈ 0");

        // 一级台阶：玩家站在高 1 格处（Δy = 1.0 ≤ ATTACK_VERTICAL_RANGE = 1.5）
        World world2 = world();
        Player onStep = playerAt(0.5, TestWorlds.SURFACE_FEET_Y + 1.0, 0.5);
        MeleeMonster stepped = new MeleeMonster(1.2, TestWorlds.SURFACE_FEET_Y, 0.5);
        stepped.tick(world2, onStep, DT);
        assertEquals(16, onStep.health(), "一级台阶（Δy = 1）必须仍能咬中");
        assertEquals(1, stepped.attackCount());
    }

    /** 攻击冷却：1 秒内不得连击两次。 */
    @Test
    void monsterAttackIsThrottledByCooldown() {
        World world = world();
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        MeleeMonster m = new MeleeMonster(1.2, TestWorlds.SURFACE_FEET_Y, 0.5);

        m.tick(world, player, DT);
        m.tick(world, player, DT);
        assertEquals(1, m.attackCount(), "冷却期内不得二次攻击");

        for (int i = 0; i < 60; i++) {
            m.tick(world, player, DT);
        }
        assertEquals(2, m.attackCount(), "约 1 秒后应能再次攻击");
    }

    /** 已倒下的玩家不再被补刀（死亡 3 秒倒计时期间不应继续掉血）。 */
    @Test
    void monsterStopsAttackingADeadPlayer() {
        World world = world();
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.hurt(world, 999);
        assertTrue(player.isDead());

        MeleeMonster m = new MeleeMonster(1.2, TestWorlds.SURFACE_FEET_Y, 0.5);
        m.tick(world, player, DT);
        assertEquals(0, m.attackCount(), "玩家已倒下时不得攻击");
    }

    /** 第 1–3 步：远处不追、进入范围后追。 */
    @Test
    void monsterChasesOnlyWithinChaseRange() {
        World world = world();
        // 平坦世界只加载了 (-1..1) 三个区块，取一个足够远但仍在世界内的位置
        Player far = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 40.0);
        MeleeMonster m = new MeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);

        m.tick(world, far, DT);
        assertFalse(m.isChasing(), "40 格超出追击范围 " + MeleeMonster.CHASE_RANGE);
        assertEquals(0.5, m.position().z, 1e-9, "不在追击范围时不得移动");

        Player near = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 10.0);
        m.tick(world, near, DT);
        assertTrue(m.isChasing());
        assertTrue(m.position().z > 0.5, "进入范围后应朝玩家移动（+Z）");
    }

    /**
     * 第 5 步：前方为虚空时<b>不主动踏入</b>。
     *
     * <p>地形是一格孤岛，玩家在孤岛外的空中。怪物若照直走就会坠入虚空，
     * 因此它必须在岛边缘停住 —— 且这个"停住"要能被断言，而不只是"看起来没掉下去"。
     */
    @Test
    void monsterRefusesToWalkOffTheEdgeIntoTheVoid() {
        int grass = BlockRegistry.grass().runtimeId();
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(0, TestWorlds.SURFACE_BLOCK_Y, 0, grass));

        MeleeMonster m = new MeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 8.5);

        for (int i = 0; i < 120; i++) {
            m.tick(world, player, DT);
        }

        assertTrue(m.wasVoidBlocked(), "应记录一次「因前方虚空而拒绝前进」");
        assertTrue(m.position().z < 1.0,
                "怪物不得走出那一格平台（实际 z=" + m.position().z + "）");
        assertTrue(m.isAlive(), "怪物不得把自己走进虚空");
    }

    /** 边界：真的掉进虚空时立即移除，不留尸体。 */
    @Test
    void monsterBelowVoidLineIsRemoved() {
        World world = world();
        MeleeMonster m = new MeleeMonster(0.5, Coords.VOID_KILL_Y - 1, 0.5);
        m.tick(world, playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5), DT);
        assertFalse(m.isAlive(), "掉入虚空的怪物必须被移除（PRD 5.5.2）");
    }

    // ============================================================ EntityManager

    @Test
    void managerSpawnsAndCleansUpDeadEntities() {
        EntityManager em = new EntityManager();
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        World world = world();

        MeleeMonster m = em.spawnMeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, 2.5);
        assertEquals(1, em.aliveCount());
        assertEquals(1, em.totalSpawned());

        m.hurt(999);
        em.tick(world, player, DT);
        assertEquals(0, em.aliveCount());
        assertEquals(0, em.size(), "死亡实体应在帧末被清理");
        assertEquals(1, em.totalRemoved());
    }

    @Test
    void managerCleansUpEntitiesThatFellIntoTheVoid() {
        EntityManager em = new EntityManager();
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        em.add(new MeleeMonster(0.5, Coords.VOID_KILL_Y - 2, 0.5));

        em.tick(world(), player, DT);
        assertEquals(0, em.size(), "坠入虚空的实体必须在帧末被移除");
        assertEquals(1, em.totalRemoved());
    }

    @Test
    void managerTicksEveryEntityExactlyOnce() {
        EntityManager em = new EntityManager();
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        World world = world();

        MeleeMonster a = em.spawnMeleeMonster(1.2, TestWorlds.SURFACE_FEET_Y, 0.5);
        MeleeMonster b = em.spawnMeleeMonster(1.4, TestWorlds.SURFACE_FEET_Y, 0.5);

        em.tick(world, player, DT);
        assertEquals(1, a.attackCount(), "每只怪物每帧只应 tick 一次");
        assertEquals(1, b.attackCount());
        assertEquals(12, player.health(), "两只怪各 4 点，共扣 8 点");
    }

    @Test
    void nearestToFindsTheClosestAliveEntity() {
        EntityManager em = new EntityManager();
        MeleeMonster near = em.spawnMeleeMonster(0.5, 64, 2.0);
        em.spawnMeleeMonster(0.5, 64, 20.0);

        assertEquals(near, em.nearestTo(new Vector3d(0.5, 64, 0.5), 10.0));
        assertEquals(null, em.nearestTo(new Vector3d(0.5, 64, 0.5), 1.0), "超出范围返回 null");
    }

    // ============================================================ 生命与重生（PRD 5.3 / 5.3.1）

    @Test
    void playerStartsAtTwentyHealthAndDiesWhenItReachesZero() {
        World world = world();
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);

        assertEquals(20, player.health(), "PRD 5.3：生命上限 20 点");
        player.hurt(world, 19);
        assertEquals(1, player.health());
        assertFalse(player.isDead());

        player.hurt(world, 1);
        assertEquals(0, player.health());
        assertTrue(player.isDead(), "生命降至 0 必须进入死亡流程");
    }

    /**
     * PRD 5.3：死亡 3 秒后重生，且重生态为满血。
     *
     * <p><b>这是"整 3.00 秒"边界的钉子：</b>179 步（2.9833 s）必须还躺着，
     * 第 180 步（3.00 s）必须已经站起来。倒计时是"每步累加 {@code 1/60}"，
     * 而 {@code 1/60} 在二进制下不精确，累加 180 次实测落在 3.0 的 1e-16 邻域内 ——
     * 所以 {@code Player.updateDeathTimer} 的判据带了 {@code 1e-9} 容差。
     * 本测试与那条容差是一体的：去掉任何一个，"整 3 秒重生"都会变成
     * "第 181 步才重生"这种一帧级的随机红绿。
     */
    @Test
    void playerRespawnsThreeSecondsAfterDeathAtFullHealth() {
        World world = world();
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.hurt(world, 999);
        assertTrue(player.isDead());

        for (int i = 0; i < 179; i++) {
            player.step(world, PlayerIntent.NONE, DT);
            assertTrue(player.isDead(), "未到 3 秒不得提前重生（已过 " + (i + 1) * DT + " 秒）");
        }
        player.step(world, PlayerIntent.NONE, DT);
        assertFalse(player.isDead(), "3 秒后必须重生");
        assertEquals(20, player.health(), "重生后生命 20/20");
        assertEquals(1, player.deaths());
    }

    /** 死亡期间玩家不可移动。 */
    @Test
    void deadPlayerCannotMove() {
        World world = world();
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.hurt(world, 999);
        double x0 = player.position().x;
        double z0 = player.position().z;

        PlayerIntent forward = PlayerIntent.moving(1f, 0f, false);
        for (int i = 0; i < 60; i++) {
            player.step(world, forward, DT);
        }
        assertEquals(x0, player.position().x, 1e-9, "已倒下的玩家不得移动");
        assertEquals(z0, player.position().z, 1e-9);
    }

    /** PRD 5.3：坠落至 y < -8 直接死亡。 */
    @Test
    void fallingIntoTheVoidKillsInstantly() {
        World world = world();
        Player player = playerAt(0.5, Coords.VOID_KILL_Y - 1, 0.5);
        player.step(world, PlayerIntent.NONE, DT);
        assertTrue(player.isDead(), "虚空必须直接致死，而不是等 3 秒倒计时以外的流程");
    }

    /**
     * PRD 5.3.1 B：重生点 = 世界固定点 + 螺旋搜索，<b>不是 lastSafePosition</b>。
     */
    @Test
    void respawnPointIsTheWorldCenterPlusSpiralSearch() {
        World world = world();
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);

        Vector3d normal = player.findRespawnPoint(world);
        assertEquals(0.5, normal.x, 1e-9, "平坦世界下重生点即 (0,64,0) 的中心");
        assertEquals(0.5, normal.z, 1e-9);
        assertEquals(TestWorlds.SURFACE_FEET_Y, normal.y, 1e-9);

        // 把重生点正下方挖空 → 必须螺旋搜索到邻居，而不是把玩家丢在虚空上方
        world.breakBlock(0, TestWorlds.SURFACE_BLOCK_Y, 0, World.MutationCause.PLAYER_BREAK);
        Vector3d shifted = player.findRespawnPoint(world);
        assertNotNull(shifted, "必须能找到邻居位置或生成兜底地台");
        assertNotEquals(0.5, shifted.x, "中心不合法时必须换位置");
        assertTrue(player.isStandingSpotValid(world, shifted.x, shifted.y, shifted.z),
                "搜索出来的重生点必须是合法的落脚点");
    }

    /** 16 格内无合法点时的兜底地台（PRD 5.3.1 B：保证永不软锁）。 */
    @Test
    void respawnFallsBackToATemporaryPlatform() {
        // 一个完全没有地面的世界
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0);
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);

        Vector3d point = player.findRespawnPoint(world);
        assertNotNull(point);
        assertTrue(player.isStandingSpotValid(world, point.x, point.y, point.z),
                "兜底地台必须真的造出来了，否则玩家会陷入「重生即坠亡」的死循环");
    }

    /** PRD 5.3.1 A：虚空死亡的掉落物落在 lastSafePosition 附近，严禁坠入虚空。 */
    @Test
    void voidDeathDropsItemsNearLastSafePosition() {
        World world = world();
        Player player = playerAt(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);

        // 站稳 1 秒，让 lastSafePosition 被写入
        for (int i = 0; i < 60; i++) {
            player.step(world, PlayerIntent.NONE, DT);
        }
        Vector3d safe = player.lastSafePosition();
        assertEquals(RESTING_FEET_Y, safe.y, 1e-6, "安全点应在站稳后被记录（含贴面余量 1e-4）");

        // 直接落进虚空
        player.position().set(0.5, Coords.VOID_KILL_Y - 1, 0.5);
        player.step(world, PlayerIntent.NONE, DT);
        assertTrue(player.isDead());

        Vector3d drop = player.lastDeathDropPosition();
        assertTrue(drop.y > Coords.VOID_KILL_Y,
                "掉落点必须在虚空线之上（严禁坠入虚空），实际 y=" + drop.y);
        assertEquals(RESTING_FEET_Y, drop.y, 1e-6);
    }
}

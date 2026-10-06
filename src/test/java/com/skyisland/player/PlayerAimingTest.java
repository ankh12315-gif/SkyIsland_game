package com.skyisland.player;

import com.skyisland.item.ItemRegistry;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 瞄准（PRD 5.4.3「瞄准」行）：
 * <b>手持枪械按住右键 → FOV 由 70 收窄至 45、移动速度降至 60%</b>。
 *
 * <p><b>M3 接线修正后的两处口径变化（都在本类里有对应断言）：</b>
 * <ol>
 *   <li>FOV 的收窄不再是固定倍率 {@code 45/70}，而是 v2 §5.2 第 8 条 / §9.1 的
 *       <b>绝对目标 FOV</b>：{@code scale = min(1, 本枪 aimFovDeg / 基础 FOV)}。
 *       默认 FOV 70 时两者数值相同；玩家把 FOV 改成 90 时，旧实现给 57.86°，新实现给 45°；</li>
 *   <li>移速倍率与上面那个 45 都<b>取自手持枪的 {@code GunSpec}</b>，不再是 {@code Player} 上的
 *       全局常量 —— 因此"手枪 45/×0.60、冲锋枪 48/×0.65"（v2 §10）是真的两套手感。
 *       两把枪分别的断言见 {@code WeaponDataWiringTest}。</li>
 * </ol>
 *
 * <p><b>为什么瞄准状态必须由意图派生，而不是可以随便置位：</b>
 * 它是"持枪"与"按住右键"两个事实的<b>合成</b>。允许外部直接置位的话，
 * 就会出现"手里只有一块泥土、视野却是 45°"这种自相矛盾的状态 ——
 * 而那时没有任何一条代码路径会把它改回来。本类因此同时断言"不该瞄准时不瞄准"。
 *
 * <p>右键在 M1 是"放置"、在 M2 是"瞄准"，两个语义共用一次按键：
 * 判据里带上"手里是不是枪"，两者就自动互斥，不需要在放置代码里再写一次例外。
 */
class PlayerAimingTest {

    private static final double DT = 1.0 / 60.0;

    private static World world() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    /** 手持手枪的玩家（与开局装备一致）。 */
    private static Player armedPlayer() {
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID), 1);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), 24);
        return player;
    }

    /** 按住右键的意图（useHeld = true，usePressed = false）。 */
    private static PlayerIntent aim() {
        return PlayerIntent.combat(0, 0, false, 0, 0, false, true, false);
    }

    private static PlayerIntent idle() {
        return PlayerIntent.combat(0, 0, false, 0, 0, false, false, false);
    }

    @Test
    void aimingNarrowsFovFromSeventyToFortyFive() {
        World world = world();
        Player player = armedPlayer();
        player.setBaseFovDeg(70.0);
        assertEquals(70.0, player.camera().fovDeg(), 1e-9, "非瞄准时 FOV 就是设置里的基础值");

        player.step(world, aim(), DT);

        assertTrue(player.isAiming());
        assertEquals(45.0, player.camera().fovDeg(), 1e-9,
                "PRD 5.4.3：FOV 由 70 收窄至 45");
        assertEquals(45.0 / 70.0, player.fovScale(), 1e-12);
    }

    @Test
    void releasingAimRestoresTheBaseFov() {
        World world = world();
        Player player = armedPlayer();

        player.step(world, aim(), DT);
        assertEquals(45.0, player.camera().fovDeg(), 1e-9);

        player.step(world, idle(), DT);
        assertFalse(player.isAiming());
        assertEquals(70.0, player.camera().fovDeg(), 1e-9, "松开右键必须回到基础 FOV");
    }

    /**
     * ADS 是<b>绝对目标 FOV</b>（v2 §5.2 第 8 条 / §9.1）：无论玩家的基础 FOV 调成多少，
     * 手枪 ADS 之后都落在 45°。
     *
     * <p><b>★ 这条断言在 M3 接线修正时被改写，也是本轮唯一的玩家可见行为变化。</b>
     * 改写前 {@code Player} 用的是固定倍率 {@code 45/70}，于是把基础 FOV 调到 90 的玩家
     * 按右键得到 57.86° —— 而 v2 §9.1 写得很直白：
     * 「玩家 base FOV = 70，手枪 aim=45 → 45°；玩家 base FOV = 90，手枪 aim=45 → 45°」。
     * 也就是说"文档承诺绝对目标角、实机按倍率收窄"，两者只在默认 70 时偶然重合。
     * 现在实现与文档一致；代价是<b>改过 FOV 设置的玩家会感到 ADS 视野比之前更窄</b>
     * （这不是回退，而是把文档里早已冻结的口径真正接上）。
     */
    @Test
    void adsLandsOnTheAbsoluteTargetFovNotOnAFixedRatio() {
        World world = world();
        Player player = armedPlayer();
        player.setBaseFovDeg(90.0);

        player.step(world, aim(), DT);

        assertEquals(45.0, player.camera().fovDeg(), 1e-9,
                "v2 §9.1：base FOV = 90 时手枪 ADS 仍是 45°（绝对目标角）");
        assertTrue(player.camera().fovDeg() < 90.0, "瞄准必须比基础视野更窄");
        assertEquals(45.0 / 90.0, player.fovScale(), 1e-12,
                "倍率是 min(1, 45 / baseFov)，不是固定 45/70");
    }

    /**
     * 防御性下界：若基础 FOV 已经比 ADS 目标角还窄，{@code min(1, …)} 保证瞄准<b>不放大</b>视野。
     *
     * <p>v2 §9.1 第三条：「若未来错误配置 aimFov &gt; baseFov，则不会'开镜反而拉远'」。
     * 设置界面允许的范围是 60–90，因此当前两把枪（45 / 48）都到不了这个分支 ——
     * 这条断言保护的是"以后有人把某把枪的 aimFovDeg 配得比基础 FOV 还大"以及
     * "设置范围被放宽"这两种未来情形。
     */
    @Test
    void aimingNeverWidensTheViewWhenTheBaseFovIsAlreadyNarrow() {
        World world = world();
        Player player = armedPlayer();
        player.setBaseFovDeg(40.0);   // 比手枪的 ADS 目标角 45° 还窄

        player.step(world, aim(), DT);

        assertEquals(1.0, player.fovScale(), 1e-12, "min(1, 45/40) 必须夹到 1.0");
        assertEquals(40.0, player.camera().fovDeg(), 1e-9,
                "瞄准只许变窄：不得因为目标角更大就把视野撑宽");
    }

    @Test
    void holdingSomethingOtherThanAGunDoesNotAim() {
        World world = world();
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.inventory().add(TestWorlds.stone(), 10);

        player.step(world, aim(), DT);

        assertFalse(player.isAiming(), "手里不是枪时按右键是放置，不是瞄准");
        assertEquals(70.0, player.camera().fovDeg(), 1e-9, "不得因为按了右键就收窄视野");
    }

    @Test
    void aimingCutsMoveSpeedToSixtyPercent() {
        double walk = horizontalDistanceAfterMovingAlone(false);
        double aim = horizontalDistanceAfterMovingAlone(true);

        // ★ 期望值取自"手持这把枪的 spec"，不再是 Player 上的全局常量（M3 接线修正）：
        //   手枪的 ADS 移速倍率在注册表里是 0.60（PRD 5.4.3 / v2 §10）。
        double expected = ItemRegistry.pistol().gun().aimMoveSpeedMult();
        assertEquals(0.60, expected, 1e-12, "手枪 ADS 移速倍率 = 0.60（PRD 5.4.3）");
        assertEquals(expected, aim / walk, 0.05,
                "PRD 5.4.3：瞄准时移动速度降至 60%（实测 " + walk + " → " + aim + " 格）");
    }

    /**
     * 连续前进 2 秒的水平位移。
     *
     * <p>走 2 秒而不是几步：速度是指数逼近目标值的，短时间内两种配置的位移差
     * 会被加速度主导，"比值 ≈ 0.6"这件事只有进入稳态之后才可断言。
     */
    private static double horizontalDistanceAfterMovingAlone(boolean aiming) {
        World world = world();
        Player player = armedPlayer();
        double x0 = player.position().x;
        double z0 = player.position().z;
        for (int i = 0; i < 120; i++) {
            player.step(world, PlayerIntent.combat(1f, 0, false, 0, 0, false, aiming, false), DT);
        }
        double dx = player.position().x - x0;
        double dz = player.position().z - z0;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Test
    void deathClearsAimingAndRestoresFullFov() {
        World world = world();
        Player player = armedPlayer();
        player.step(world, aim(), DT);
        assertEquals(45.0, player.camera().fovDeg(), 1e-9);

        player.hurt(world, 999);

        assertFalse(player.isAiming(), "倒下必须退出瞄准，否则会出现「躺着但视野仍是 45°」");
        assertEquals(70.0, player.camera().fovDeg(), 1e-9);
    }

    @Test
    void loadingASaveClearsAiming() {
        World world = world();
        Player player = armedPlayer();
        player.step(world, aim(), DT);
        assertTrue(player.isAiming());

        player.applyLoadedState(1.5, TestWorlds.SURFACE_FEET_Y, 1.5, 0, 0,
                null, java.util.List.of(), 0, 0);

        assertFalse(player.isAiming(), "瞄准是每步从意图派生的，存档里不存在这个状态");
        assertEquals(70.0, player.camera().fovDeg(), 1e-9);
    }

    @Test
    void holdingAGunDisablesMining() {
        World world = world();
        Player player = armedPlayer();
        player.camera().setAngles(0, -89.5);   // 视线垂直向下，脚下就是方块

        // 按住左键 120 步（远超草方块 0.6 秒的挖掘时间）
        for (int i = 0; i < 120; i++) {
            player.step(world, PlayerIntent.combat(0, 0, false, 0, 0, true, false, false), DT);
        }

        assertEquals(0, player.blocksBroken(),
                "PRD 5.4「使用左键挖掘或开枪」：手持枪械时左键是开火，不得破坏方块");
        assertFalse(player.isMining(), "举着枪不该出现挖掘进度条");
        assertTrue(world.hasCollisionAt(0, TestWorlds.SURFACE_BLOCK_Y, 0),
                "脚下那一格必须还在");
    }
}

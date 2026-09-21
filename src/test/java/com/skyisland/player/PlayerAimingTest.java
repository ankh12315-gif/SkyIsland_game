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

    @Test
    void aimIsARelativeNarrowingSoACustomFovStillWorks() {
        World world = world();
        Player player = armedPlayer();
        player.setBaseFovDeg(90.0);

        player.step(world, aim(), DT);

        // 写死绝对值 45 的话，把 FOV 调到 90 的玩家一按右键视野反而变宽 ——
        // 这条断言钉的就是"收窄是相对基础值的"。
        assertEquals(90.0 * 45.0 / 70.0, player.camera().fovDeg(), 1e-9);
        assertTrue(player.camera().fovDeg() < 90.0, "瞄准必须比基础视野更窄");
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

        assertEquals(Player.AIM_MOVE_SPEED_RATIO, aim / walk, 0.05,
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

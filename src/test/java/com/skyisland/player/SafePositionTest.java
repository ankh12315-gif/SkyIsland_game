package com.skyisland.player;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * lastSafePosition 更新节流测试（Pre-M2 Corrective Closure E2；PRD 5.3.1）。
 *
 * <p>PRD 明文：{@code lastSafePosition} 只在<b>稳定站立 ≥ 0.5 秒</b>后更新。
 * M1 实现时写成了 0.25 秒（MVP_AUDIT 登记的 MVP-SURV-012），本轮修正为 0.5，
 * 并把"重新开始计时"的条件补成断言：
 * <ul>
 *   <li>离地（或脚下不合法）→ 计时作废；</li>
 *   <li>换到另一个安全面 → 必然先经过一次离地，计时照样从头再来。</li>
 * </ul>
 *
 * <p><b>口径改判（B 案）：</b>初版把"换安全面"实现成"脚下所在格（方块列）变化即重置"，
 * 后果是平地行走每跨一格约 0.23 秒就清零，计时永远攒不到 0.5 秒 ——
 * 从出生点一路走到崖边坠落，{@code lastSafePosition} 还停在出生点。
 * 回查 <b>PRD 5.3.1 / L449</b>：排除项只有"跳跃 / 坠落 / 虚空坠落"三种<u>离地</u>情形，
 * TECH_DESIGN §I.6 要防的危害也只是"把悬空位置记成安全位置"。
 * 故改判为<b>只在离地时重置</b>，行走时间允许累计（见
 * {@link #landingOnANewSurfaceRestartsTheTimer()}）。
 */
class SafePositionTest {

    private static final double DT = 1.0 / 60.0;

    private static World flat() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    private static void run(Player player, World world, PlayerIntent intent, int steps) {
        for (int i = 0; i < steps; i++) {
            player.step(world, intent, DT);
        }
    }

    /** 站在平坦地表时的脚底 y（含贴面余量），实测得出而不是硬编码。 */
    private static double restFeetY() {
        Player probe = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        run(probe, flat(), PlayerIntent.NONE, 200);
        return probe.position().y;
    }

    /** 从 2 格高度落回地表并落地（落地那一步计入步数 1）。 */
    private static Player landed(World world, double restY) {
        Player player = new Player(0.5, restY + 2.0, 0.5);
        for (int i = 0; i < 600; i++) {
            player.step(world, PlayerIntent.NONE, DT);
            if (player.onGround()) {
                return player;
            }
        }
        throw new AssertionError("前提不成立：玩家在 600 步内没有落地");
    }

    // ============================================================ 0.5 秒门槛

    @Test
    @DisplayName("0.49 秒不更新，0.50 秒才更新")
    void updatesOnlyAfterHalfSecond() {
        double restY = restFeetY();
        World world = flat();
        Player player = landed(world, restY);

        // 落地那一步已经计入 1 步，因此再走 28 步 = 累计 29/60 ≈ 0.4833 s
        run(player, world, PlayerIntent.NONE, 28);
        assertEquals(restY + 2.0, player.lastSafePosition().y, 1e-6,
                "29/60 秒 < 0.5 秒：不得更新 lastSafePosition");

        // 再走 1 步 = 累计 30/60 = 0.5 s
        run(player, world, PlayerIntent.NONE, 1);
        assertEquals(restY, player.lastSafePosition().y, 1e-6,
                "站立满 0.5 秒：必须更新 lastSafePosition 到当前落脚点");
    }

    // ============================================================ 离地不更新

    @Test
    @DisplayName("离地期间不得更新 lastSafePosition")
    void airborneDoesNotUpdateSafePosition() {
        double restY = restFeetY();
        World world = flat();
        Player player = new Player(0.5, restY, 0.5);
        run(player, world, PlayerIntent.NONE, 120);     // 先站满，让 lastSafe = 地面位置

        double safeBeforeY = player.lastSafePosition().y;
        assertEquals(restY, safeBeforeY, 1e-6);

        player.step(world, PlayerIntent.moving(0f, 0f, true), DT);   // 起跳
        run(player, world, PlayerIntent.NONE, 20);                  // 仍在空中

        assertEquals(false, player.onGround(), "前提不成立：20 步后玩家已经落地了");
        assertEquals(safeBeforeY, player.lastSafePosition().y, 1e-6,
                "滞空中的位置不是合法落脚点，不得被记成 lastSafePosition");
    }

    // ============================================================ 行走累计 + 换面重新计时

    /**
     * 高台世界：y=63 铺草方块作地面，z∈[8,14] 上再叠一层 y=64 石头作高台（顶面标高 65）。
     * 玩家从高台 +z 端朝 −z 方向走，走出台面会掉 1 格落到草地上 —— 即"换到另一个安全面"。
     */
    private static World platformWorld() {
        List<int[]> cells = new ArrayList<>();
        int grass = TestWorlds.grass();
        int stone = TestWorlds.stone();
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 15; z++) {
                cells.add(TestWorlds.cell(x, 63, z, grass));
                if (z >= 8 && z <= 14) {
                    cells.add(TestWorlds.cell(x, 64, z, stone));
                }
            }
        }
        return TestWorlds.scatteredWorld(0, 0, 0, 0, cells.toArray(new int[0][]));
    }

    /** 站在高台上时的脚底 y（实测得出而不是硬编码）。 */
    private static double platformRestFeetY(World world) {
        Player probe = new Player(2.5, TestWorlds.SURFACE_FEET_Y + 1.0, 14.0);
        run(probe, world, PlayerIntent.NONE, 200);
        return probe.position().y;
    }

    @Test
    @DisplayName("落到新的安全面后重新计时；行走期间也持续更新，且更新点必为合法站立点")
    void landingOnANewSurfaceRestartsTheTimer() {
        World world = platformWorld();
        double groundY = restFeetY();                    // 草地顶面（脚底）
        double platformY = platformRestFeetY(world);     // 高台顶面（脚底 = 草地 + 1 格）

        Player player = new Player(2.5, platformY, 14.0);
        run(player, world, PlayerIntent.NONE, 40);       // 先站定，lastSafe = 高台起点
        double startZ = player.lastSafePosition().z;
        assertEquals(platformY, player.lastSafePosition().y, 1e-6,
                "前提不成立：起始 lastSafePosition 不在高台顶面");

        // ---- ① 行走期间也持续更新（B 案：计时只在离地时重置，不因换了落脚格而重置）
        double maxLag = 0.0;
        double recordedZ = startZ;
        for (int i = 0; i < 70; i++) {
            player.step(world, PlayerIntent.moving(1f, 0f, false), DT);
            Vector3d safe = player.lastSafePosition();
            if (Math.abs(safe.z - recordedZ) > 1e-9) {
                recordedZ = safe.z;
                assertTrue(player.isStandingSpotValid(world, safe.x, safe.y, safe.z),
                        "每次写入的 lastSafePosition 都必须是合法站立点（实测 (%.3f, %.3f, %.3f)）"
                                .formatted(safe.x, safe.y, safe.z));
            }
            maxLag = Math.max(maxLag, Math.hypot(player.position().x - safe.x,
                    player.position().z - safe.z));
        }
        assertTrue(player.lastSafePosition().z < startZ - 1.0,
                "行走（未换面）期间 lastSafePosition 也必须持续更新"
                        + "（起点 z=%.3f，现 z=%.3f）".formatted(startZ, player.lastSafePosition().z));
        assertEquals(platformY, player.lastSafePosition().y, 1e-6,
                "前提不成立：70 步内玩家已经掉下高台了");
        assertTrue(maxLag <= 0.5 * Player.WALK_SPEED + 1e-6,
                "滞后上限 = 0.5 秒 × 行走速度（实测 %.3f 格，上限 %.3f 格）"
                        .formatted(maxLag, 0.5 * Player.WALK_SPEED));

        // ---- ② 走出高台边缘 → 落到草地这个新的安全面 → 计时从头再来
        int guard = 0;
        while (player.onGround() && guard++ < 300) {
            player.step(world, PlayerIntent.moving(1f, 0f, false), DT);
        }
        assertFalse(player.onGround(), "前提不成立：300 步内没有走出高台边缘");
        guard = 0;
        while (!player.onGround() && guard++ < 300) {
            player.step(world, PlayerIntent.NONE, DT);
        }
        assertTrue(player.onGround(), "前提不成立：300 步内没有落到草地");
        assertEquals(groundY, player.position().y, 1e-6,
                "前提不成立：落地高度不是草地顶面");

        double safeOnPlatformY = player.lastSafePosition().y;
        assertEquals(platformY, safeOnPlatformY, 1e-6,
                "落地那一刻 lastSafePosition 必须仍是高台上的最后一个安全点");

        run(player, world, PlayerIntent.NONE, 28);       // 落地那一步 + 28 = 29/60 ≈ 0.4833 s
        assertEquals(safeOnPlatformY, player.lastSafePosition().y, 1e-6,
                "落到新安全面后计时重新开始：29/60 秒 < 0.5 秒，不得更新");

        run(player, world, PlayerIntent.NONE, 1);        // 累计 30/60 = 0.5 s
        assertEquals(groundY, player.lastSafePosition().y, 1e-6,
                "在新安全面站满 0.5 秒：必须更新为该面的落脚点");
        assertTrue(player.isStandingSpotValid(world, player.lastSafePosition().x,
                        player.lastSafePosition().y, player.lastSafePosition().z),
                "更新后的 lastSafePosition 必须是合法站立点（严禁写入悬空位置）");
    }
}

package com.skyisland.player;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ <b>创造会话</b>：生存存档里「双击空格 = 进创造 + 直接起飞」。
 *
 * <h2>它与 §4.3 的关系（先说清，因为这是本类存在的前提）</h2>
 * PRD_BLOCK_CREATIVE §4.3 原本裁定「模式在存档创建时确定，永不切换」，
 * 两条理由：①创造刷出的无限方块会留在世界里；②存档没有"这个方块是创造来的"标记。
 * <p>主理人 2026-10-08 要求这个功能。②<b>无法绕过</b>（绕过就是存档格式的破坏性变更），
 * 因此采用的折中是：<b>会话不写入存档</b>。
 * <ul>
 *   <li>{@code level.json} 的 {@code gameMode} 全程不动，仍是 survival；</li>
 *   <li>退出游戏再进来自动回到生存；</li>
 *   <li>残留：<b>用无限方块盖的建筑留在世界里</b>（理由①的残留），主理人知情接受。</li>
 * </ul>
 * 本类守的是"会话真的按这个口径工作"，存档那一半由
 * {@code CreativeSessionWiringTest} 守（它读源码确认没有任何写盘路径）。
 *
 * <h2>三态循环</h2>
 * 生存未开会话 → 进会话+起飞 → 会话中在飞 → 停飞 → 会话中未飞 → 退出会话。
 * 创造存档（无会话）→ 仍然只切飞行，<b>没有退出路径</b>。
 */
class CreativeSessionTest {

    private static final double DT = 1.0 / 60.0;

    private static Player survivalPlayer() {
        Player p = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        p.setCreativeMode(false);
        p.setCreativeSessionAllowed(true);      // 生存存档才允许开会话
        return p;
    }

    private static Player creativeSavePlayer() {
        Player p = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        p.setCreativeMode(true);                // 创造存档
        p.setCreativeSessionAllowed(false);     // ★ 不得有退出路径
        return p;
    }

    private static PlayerIntent jump(boolean pressed) {
        return new PlayerIntent(0f, 0f, pressed, 0, 0,
                false, false, false, false, false, false,
                false, false, false, 0, -1, false);
    }

    /** 完成一次双击空格：按下 → 松开 → 再按下 → 松开。 */
    private static void doubleTap(Player player, World world) {
        player.step(world, jump(true), DT);
        player.step(world, jump(false), DT);
        player.step(world, jump(true), DT);
        player.step(world, jump(false), DT);
    }

    // ============================================================ ① 主路径

    @Test
    @DisplayName("生存世界里双击空格 = 进创造会话 + 直接起飞")
    void doubleTapInSurvivalEntersSessionAndFlies() {
        Player player = survivalPlayer();
        World world = TestWorlds.flatWorld();
        assertFalse(player.isCreativeSession(), "起点必须是没有会话的生存玩家");

        doubleTap(player, world);

        assertTrue(player.isCreativeSession(),
                "★ 双击空格必须进入创造会话 —— 这是本功能存在的全部理由");
        assertTrue(player.isCreativeMode(),
                "进会话必须真的打开五项能力总开关，而不只是置一个标记");
        assertTrue(player.isFlying(),
                "★ 必须**直接起飞**（主理人原话「直接可以起飞」），"
                        + "而不是只进创造、要再按一次才飞");
    }

    @Test
    @DisplayName("单击空格仍然是跳跃，不会误开会话")
    void aSingleTapNeverEntersTheSession() {
        Player player = survivalPlayer();
        World world = TestWorlds.flatWorld();

        // ★ 每次点击间隔 0.5 s，**大于**双击窗口（0.3 s）。
        //   第一版这里按 1/60 s 连点，间隔只有 0.033 s —— 那本来就该判成双击
        //   （两次按下沿都在窗口内），于是"进了会话"。
        //   症状看着像"实现把单击误判成双击"，真因是**夹具在模拟双击**。
        //   与本项目反复踩的"注入/夹具本身错了却报成产品缺陷"同源。
        final int gapSteps = (int) Math.round(0.5 / DT);
        for (int i = 0; i < 12; i++) {
            player.step(world, jump(true), DT);
            player.step(world, jump(false), DT);
            for (int k = 0; k < gapSteps; k++) {
                player.step(world, jump(false), DT);
            }
        }

        assertFalse(player.isCreativeSession(),
                "★ 间隔超出双击窗口的连续单击只是跳跃。若这里进了会话，"
                        + "玩家连跳几下就莫名其妙获得创造能力");
        assertFalse(player.isFlying());
    }

    @Test
    @DisplayName("长按空格（连跳）不得进会话")
    void holdingSpaceNeverEntersTheSession() {
        Player player = survivalPlayer();
        World world = TestWorlds.flatWorld();

        // 按住不放 3 秒：只有一个按下沿，因此不构成双击
        player.step(world, jump(true), DT);
        for (int i = 0; i < 180; i++) {
            player.step(world, jump(true), DT);
        }
        player.step(world, jump(false), DT);

        assertFalse(player.isCreativeSession(),
                "★ 长按空格是连跳。若按住就能进会话，"
                        + "那么任何「按住空格往上跳」的玩家都会进创造 —— "
                        + "而那是生存里最自然的操作");
        assertFalse(player.isFlying());
    }

    // ============================================================ ② 三态循环

    @Test
    @DisplayName("三态循环：进会话+起飞 → 停飞 → 退出会话")
    void theCycleIsEnterThenLandThenExit() {
        Player player = survivalPlayer();
        World world = TestWorlds.flatWorld();

        doubleTap(player, world);
        assertTrue(player.isFlying(), "第 1 次双击：进会话并起飞");

        doubleTap(player, world);
        assertTrue(player.isCreativeSession(), "第 2 次双击：仍在会话内（要站着搭东西）");
        assertFalse(player.isFlying(), "第 2 次双击：停飞");

        doubleTap(player, world);
        assertFalse(player.isCreativeSession(), "★ 第 3 次双击必须退出会话 —— "
                + "退出路径若不存在，玩家会被永久留在创造里，"
                + "而那正是 §4.3 理由①描述的坏结果，只是不再有提示");
        assertFalse(player.isCreativeMode(), "退出会话必须真的关掉能力总开关");
        assertFalse(player.isFlying(), "退出后不得残留飞行状态（PRD §5.5）");
    }

    @Test
    @DisplayName("退出前那一步保留免伤害与虚空保护（站着搭台要用）")
    void stoppingFlightKeepsTheOtherAbilities() {
        Player player = survivalPlayer();
        World world = TestWorlds.flatWorld();

        doubleTap(player, world);
        doubleTap(player, world);   // 停飞，但仍能无限方块 + 免伤害

        assertFalse(player.isFlying());
        assertTrue(player.isCreativeMode(),
                "★ 停飞不得顺手关掉创造 —— 一次双击就把创造整个关掉的话，"
                        + "「站着无限方块搭一会儿」这个动作会顺带失去免伤害与虚空保护，"
                        + "而那两样在高空搭台时是刚需");
    }

    // ============================================================ ③ §4.3 不得被绕过

    @Test
    @DisplayName("创造存档里双击空格只切飞行，**没有**退出路径（§4.3 原样保留）")
    void aCreativeSaveNeverGainsAnExitPath() {
        Player player = creativeSavePlayer();
        World world = TestWorlds.flatWorld();

        for (int i = 0; i < 8; i++) {
            doubleTap(player, world);
        }

        assertFalse(player.isCreativeSession(),
                "★ 创造存档不得出现创造会话 —— 一旦它有，双击空格就成了"
                        + "「单键绕过 §4.3 模式锁定」，那比不做这个功能严重得多");
        assertTrue(player.isCreativeMode(),
                "创造存档的双击空格必须仍然只是切飞行（与本功能引入之前完全一致）");
    }

    @Test
    @DisplayName("会话默认不允许开会话（不设开关就开会话 = 绕过装配期判据）")
    void sessionsAreOffUnlessExplicitlyAllowed() {
        // 构造时没有调用 setCreativeSessionAllowed(true)
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.setCreativeMode(false);
        World world = TestWorlds.flatWorld();

        doubleTap(player, world);

        assertFalse(player.isCreativeSession(),
                "★ 默认必须是「不允许」—— 安全侧。"
                        + "若默认允许，那么任何忘了设该开关的地方都会拿到退出路径");
        assertFalse(player.isFlying());
    }

    // ============================================================ ④ 回调

    @Test
    @DisplayName("会话开关会回调，且只在会话边界触发（停飞不触发）")
    void theSessionListenerFiresOnlyOnSessionEdges() {
        Player player = survivalPlayer();
        World world = TestWorlds.flatWorld();
        int[] fired = {0};
        player.setCreativeSessionListener(() -> fired[0]++);

        doubleTap(player, world);
        assertEquals(1, fired[0], "进入会话必须回调一次 —— 创造面板要靠它建，"
                + "否则玩家会得到「能飞但背包里没有创造标签」的半套能力");

        doubleTap(player, world);   // 停飞，仍在会话内
        assertEquals(1, fired[0],
                "★ 停飞**不得**触发回调：面板在会话内一直有效，没必要重建。"
                        + "第一版这里写成期望 3 次，症状是「回调少了一次」，"
                        + "真因是我把「停飞也要回调」当成了需求 —— "
                        + "而它没有任何消费者，属于凭空要求的无用工作");

        doubleTap(player, world);   // 退出会话
        assertEquals(2, fired[0], "退出会话必须回调（面板要清掉，"
                + "否则会出现「能力没了但创造标签还在」，点了格子什么都拿不到）");
    }
}
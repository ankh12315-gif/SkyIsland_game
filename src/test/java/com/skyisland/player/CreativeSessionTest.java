package com.skyisland.player;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /** 同上但可带潜行（下降）位 —— 用于复现「按住 Ctrl 降到地面」。 */
    private static PlayerIntent intent(boolean jump, boolean sneak) {
        return new PlayerIntent(0f, 0f, jump, 0, 0,
                false, false, false, false, false, false,
                false, false, false, 0, -1, sneak);
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
    @DisplayName("★ 双击空格**只**切飞行：永不退出创造会话")
    void doubleTapOnlyTogglesFlightAndNeverExitsTheSession() {
        Player player = survivalPlayer();
        World world = TestWorlds.flatWorld();

        doubleTap(player, world);
        assertTrue(player.isFlying(), "第 1 次双击：进会话并起飞");

        // 循环 8 次 —— 必须是纯粹的飞行开关，可以来回切
        for (int i = 2; i <= 8; i++) {
            final boolean wasFlying = player.isFlying();
            doubleTap(player, world);
            assertNotEquals(wasFlying, player.isFlying(),
                    "第 " + i + " 次双击：飞行必须切换");
            assertTrue(player.isCreativeSession(),
                    "★ 第 " + i + " 次双击后仍必须在创造会话里 —— "
                            + "原来的三态循环会在这里**退出创造**，"
                            + "那是不可预测且不可逆的：一次手感键的连按"
                            + "静默拿走了全部五项能力");
            assertTrue(player.isCreativeMode(),
                    "第 " + i + " 次双击后能力总开关必须仍开着");
        }
    }

    @Test
    @DisplayName("★ 主理人实测场景：按 Ctrl 降到地面后，双击只会切飞行，不会退出创造")
    void landingThenDoubleTapDoesNotExitCreative() {
        // 这条是**用户报上来的那条**，逐字复现它的操作序列。
        // 起因：按 Ctrl 下降到地面后，flying 仍然是 true（悬停），
        // 而玩家站在地上走，感觉自己"在正常行走、飞行已关" ——
        // 于是同一个键在**他看来一样的两种状态下**做不同的事。
        // 实测原三态循环的结果是：第 1 次停飞、第 2 次**退出创造**、
        // 第 3 次才重新起飞。他要的"又是飞行模式"中间隔了一次能力全丢。
        World world = TestWorlds.flatWorld();
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y + 12, 0.5);
        player.setCreativeMode(false);
        player.setCreativeSessionAllowed(true);

        // 先跳离地面，再双击进会话并起飞
        for (int i = 0; i < 10; i++) {
            player.step(world, intent(true, false), DT);
        }
        doubleTap(player, world);
        assertTrue(player.isFlying(), "前置：应已进入创造会话并在飞行");

        // 一直按下降键直到着地
        int steps = 0;
        while (!player.onGround() && steps < 900) {
            player.step(world, intent(false, true), DT);
            steps++;
        }
        assertTrue(steps < 900, "前置：应当能降到地面");
        assertTrue(player.onGround(), "前置：已着地");

        // ★ 关键事实：着地后 flying 仍然是 true。把它钉住 ——
        //   若将来有人"顺手"在着地时清掉 flying，本用例后面的
        //   "双击只会切飞行"仍然成立，但玩家看到的界面反馈会变；
        //   而这条断言记录的是**当前真实行为**，不是期望。
        assertTrue(player.isFlying(),
                "★ 着地后飞行状态**仍然**是开着的（悬停在地面上方）—— "
                        + "这正是玩家预判不了双击行为的原因："
                        + "站着时它与「没在飞」看起来一模一样");

        // 现在按用户描述双击：必须回到「又起飞」，而不是退出创造
        doubleTap(player, world);
        assertFalse(player.isFlying(), "第 1 次双击：停飞（他以为自己在走路，所以预期是起飞）");

        doubleTap(player, world);
        assertTrue(player.isFlying(),
                "★ 第 2 次双击必须**又起飞** —— 这正是主理人要的「又是飞行模式」。"
                        + "原三态循环在这一步会退出创造、带走全部能力");
        assertTrue(player.isCreativeSession(),
                "★ 全程不得退出创造会话：双击空格的语义是「飞行开关」，不是「退出创造」");
        assertTrue(player.isCreativeMode(), "能力总开关必须仍开着");
    }

    @Test
    @DisplayName("★ 结束会话只能走显式入口，且不可逆")
    void endingTheSessionIsExplicitAndIdempotent() {
        Player player = survivalPlayer();
        World world = TestWorlds.flatWorld();
        doubleTap(player, world);
        assertTrue(player.isCreativeSession());

        assertTrue(player.endCreativeSession(), "第一次结束必须成功");
        assertFalse(player.isCreativeSession());
        assertFalse(player.isCreativeMode());
        assertFalse(player.isFlying(), "结束后不得残留飞行状态（PRD §5.5）");

        assertFalse(player.endCreativeSession(),
                "★ 已经不在会话里时再结束一次必须返回 false —— "
                        + "一个总是返回 true 的接口会让人以为它还有效");
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

        // ★ 2026-10-09：双击空格不再有"第三态"，所以这里必须换成显式入口。
        //   换掉之前，这个用例的第二次 doubleTap 恰好走了"退出会话"那条分支，
        //   于是它**顺带**测到了退出回调 —— 那是巧合，不是设计。
        player.endCreativeSession();
        assertEquals(2, fired[0],
                "★ 结束会话必须回调（面板要清掉），否则会出现"
                        + "「能力没了但创造标签还在」，点了格子什么都拿不到");
    }
}
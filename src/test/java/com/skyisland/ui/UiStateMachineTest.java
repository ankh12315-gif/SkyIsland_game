package com.skyisland.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 界面状态机（M1.5 规格第 2/3/8/10 条）。
 *
 * <p><b>为什么所有非法迁移都要被断言，而不是只测"正常流程能走通"：</b>
 * 状态机的价值<u>全部</u>在于拦住非法状态组合。只测正常路径的话，
 * 一个"任何迁移都返回 true"的假实现会百分之百通过测试 ——
 * 那正是"用三个 boolean 表示界面"的老写法会退化成的东西。
 * 因此本类对每个非法请求都要求：返回 false、状态不变、并且<a>被计数</a>
 * （计数是证据：报告里能引用"本次运行拒绝了 N 次非法迁移"）。
 *
 * <p><b>其中最重要的一条是"设置界面的来源记忆"：</b>
 * 设置可以从主菜单进，也可以从暂停菜单进。若"返回"一律回主菜单，
 * 那么"暂停 → 设置 → 返回"会把玩家直接踢出正在进行的世界。
 * 这个 bug 只在这一条路径上出现，且看起来像"返回键坏了"。
 */
class UiStateMachineTest {

    // ============================================================ 派生属性

    @Test
    void onlyPlayingAndInventoryRunSimulation() {
        for (UiState s : UiState.values()) {
            boolean sim = (s == UiState.PLAYING || s == UiState.INVENTORY);
            assertEquals(sim, s.simulationRunning(),
                    s + " 仅 PLAYING 与 INVENTORY 推进物理（开背包不暂停世界）");
            if (s == UiState.PLAYING) {
                assertTrue(s.mouseCaptured());
                assertTrue(s.gameplayHudVisible());
                assertFalse(s.menuVisible());
            } else {
                assertFalse(s.mouseCaptured(), s + " 必须放开光标，否则菜单/背包点不了");
                assertFalse(s.gameplayHudVisible());
                assertTrue(s.menuVisible(), s + " 菜单层需可见");
            }
        }
    }

    @Test
    void stateLabelsAreAsciiExceptInventoryWhichIsChinese() {
        for (UiState s : UiState.values()) {
            if (s == UiState.INVENTORY) {
                assertTrue(s.label().chars().anyMatch(c -> c >= 0x4E00),
                        "背包界面标签需为中文，写进窗口标题（如 'SkyIsland 0.3.x | 背包'），实际=" + s.label());
            } else {
                assertTrue(s.label().chars().allMatch(c -> c < 128),
                        "状态名会进窗口标题与 HUD，必须是 ASCII，实际=" + s.label());
            }
        }
    }

    // ============================================================ 主菜单

    @Test
    void freshMachineStartsAtMainMenu() {
        UiStateMachine m = new UiStateMachine(UiState.MAIN_MENU);

        assertEquals(UiState.MAIN_MENU, m.state());
        assertFalse(m.isSimulationRunning());
        assertFalse(m.isQuitRequested());
        assertFalse(m.history().isEmpty(), "初始状态也要留一条记录，便于对照日志");
    }

    @Test
    void startGameGoesFromMenuToPlaying() {
        UiStateMachine m = new UiStateMachine(UiState.MAIN_MENU);

        assertTrue(m.startGame());
        assertEquals(UiState.PLAYING, m.state());
        assertTrue(m.isSimulationRunning());
        assertTrue(m.isMouseCaptured());
    }

    @Test
    void startGameFromPlayingIsRejected() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);

        assertFalse(m.startGame(), "PLAYING → PLAYING 不是迁移，必须被拒绝");
        assertEquals(UiState.PLAYING, m.state());
        assertEquals(1, m.rejectedTransitions());
    }

    // ============================================================ ESC 语义

    @Test
    void escapePausesWhilePlayingAndResumesWhilePaused() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);

        assertTrue(m.onEscape());
        assertEquals(UiState.PAUSED, m.state());
        assertEquals(1, m.pauseCount());
        assertFalse(m.isSimulationRunning());

        assertTrue(m.onEscape());
        assertEquals(UiState.PLAYING, m.state());
        assertEquals(1, m.resumeCount());
        assertTrue(m.isSimulationRunning());
    }

    @Test
    void escapeDoesNothingAtTheMainMenu() {
        UiStateMachine m = new UiStateMachine(UiState.MAIN_MENU);

        assertFalse(m.onEscape(),
                "主菜单是'家'，ESC 不能退出 —— 否则一次误按就结束游戏");
        assertEquals(UiState.MAIN_MENU, m.state());
        assertFalse(m.isQuitRequested());
        assertEquals(0, m.rejectedTransitions(), "这不是非法迁移，只是无动作");
    }

    @Test
    void escapeReturnsSettingsToItsOrigin() {
        UiStateMachine fromMenu = new UiStateMachine(UiState.MAIN_MENU);
        assertTrue(fromMenu.openSettings());
        assertTrue(fromMenu.onEscape());
        assertEquals(UiState.MAIN_MENU, fromMenu.state());

        UiStateMachine fromPause = new UiStateMachine(UiState.PAUSED);
        assertTrue(fromPause.openSettings());
        assertTrue(fromPause.onEscape());
        assertEquals(UiState.PAUSED, fromPause.state(),
                "'暂停 → 设置 → ESC' 必须回到暂停，而不是把玩家踢出世界");
    }

    @Test
    void repeatedEscapeTogglesOnlyOncePerCall() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);

        for (int i = 0; i < 5; i++) {
            m.onEscape();
        }

        // 5 次 ESC：PAUSED, PLAYING, PAUSED, PLAYING, PAUSED
        assertEquals(UiState.PAUSED, m.state());
        assertEquals(3, m.pauseCount());
        assertEquals(2, m.resumeCount());
        assertEquals(0, m.rejectedTransitions(), "来回切换全程都是合法迁移");
    }

    // ============================================================ 背包界面（M2.2）

    @Test
    void inventoryOpensFromPlayingAndClosesBack() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);

        assertTrue(m.openInventory(), "PLAYING → INVENTORY 必须成功");
        assertEquals(UiState.INVENTORY, m.state());

        assertTrue(m.closeInventory(), "INVENTORY → PLAYING 必须成功");
        assertEquals(UiState.PLAYING, m.state());
    }

    /**
     * 产品语义核心断言（M2.2 规格）：打开背包<u>不暂停世界</u>。
     * 这条单独成条，任何一条不成立都必须变红。
     */
    @Test
    void inventoryKeepsSimulationRunningAndReleasesCursor() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);
        assertTrue(m.openInventory());

        assertTrue(m.isSimulationRunning(),
                "INVENTORY 下世界必须继续推进（怪物动、玩家掉血、方块被挖）—— 这是与 PAUSED 的根本区别");
        assertFalse(m.isMouseCaptured(), "INVENTORY 下光标必须可见，才能点格子");
        assertTrue(m.state().menuVisible(), "INVENTORY 是模态层，菜单层必须可见（吞掉游戏内输入）");
        assertFalse(m.state().gameplayHudVisible(), "INVENTORY 下不画准星/快捷栏/挖掘条（快捷栏由背包界面自己画）");
        assertTrue(m.state().vitalsVisible(), "INVENTORY 下生命条与通知仍要显示，玩家不能开背包就看不见挨打");
    }

    @Test
    void inventoryCannotBeOpenedFromMenuPauseOrSettings() {
        for (UiState from : new UiState[] { UiState.MAIN_MENU, UiState.PAUSED, UiState.SETTINGS }) {
            UiStateMachine m = new UiStateMachine(from);
            int before = m.rejectedTransitions();
            assertFalse(m.openInventory(), from + " → INVENTORY 必须被拒绝");
            assertEquals(from, m.state(), from + " 下被拒的 openInventory 不得改变状态");
            assertEquals(before + 1, m.rejectedTransitions(), from + " 下的非法请求必须被计数");
        }
    }

    @Test
    void inventoryBlocksOpenSettings() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);
        assertTrue(m.openInventory());

        assertFalse(m.openSettings(), "背包里开设置会让'背包还开着吗'变成歧义，必须被拒绝");
        assertEquals(UiState.INVENTORY, m.state(), "被拒的 openSettings 不得改变状态");
    }

    @Test
    void escapeClosesInventoryBackToPlaying() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);
        assertTrue(m.openInventory());

        assertTrue(m.onEscape(), "INVENTORY 下 ESC 必须关闭背包");
        assertEquals(UiState.PLAYING, m.state(), "INVENTORY → ESC 必须回到游玩");
    }

    @Test
    void toggleInventoryReturnsToOriginAfterTwoToggles() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);

        assertTrue(m.toggleInventory());
        assertEquals(UiState.INVENTORY, m.state());

        assertTrue(m.toggleInventory());
        assertEquals(UiState.PLAYING, m.state(), "连按两次 toggle 必须回到原点");
    }

    @Test
    void pauseFromInventoryIsLegalAndGoesToPaused() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);
        assertTrue(m.openInventory());

        assertTrue(m.pause(), "背包里按暂停必须合法（状态机不处理光标持有物，由调用方负责）");
        assertEquals(UiState.PAUSED, m.state());
        assertFalse(m.isSimulationRunning(), "转到 PAUSED 后世界冻结");
    }

    // ============================================================ 设置界面来源

    @Test
    void settingsOriginIsRememberedIndependentlyOfWhereItWasOpened() {
        UiStateMachine m = new UiStateMachine(UiState.MAIN_MENU);
        assertTrue(m.openSettings());
        assertEquals(UiState.MAIN_MENU, m.settingsOrigin());

        UiStateMachine m2 = new UiStateMachine(UiState.PAUSED);
        assertTrue(m2.openSettings());
        assertEquals(UiState.PAUSED, m2.settingsOrigin());
    }

    @Test
    void settingsCannotBeOpenedWhilePlaying() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);

        assertFalse(m.openSettings(),
                "游玩中直接开设置会让光标突然解锁（隐藏→可见），必须先暂停");
        assertEquals(UiState.PLAYING, m.state());
        assertEquals(1, m.rejectedTransitions());
    }

    @Test
    void settingsCannotBeOpenedFromSettings() {
        UiStateMachine m = new UiStateMachine(UiState.PAUSED);
        m.openSettings();

        assertFalse(m.openSettings(), "不能自我嵌套进入设置");
        assertEquals(1, m.rejectedTransitions());
    }

    @Test
    void closingSettingsWhenNotInSettingsIsRejected() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);

        assertFalse(m.closeSettings());
        assertEquals(UiState.PLAYING, m.state());
        assertEquals(1, m.rejectedTransitions());
    }

    // ============================================================ 返回主菜单

    @Test
    void backToMainMenuIsAllowedFromPauseAndSettings() {
        UiStateMachine fromPause = new UiStateMachine(UiState.PAUSED);
        assertTrue(fromPause.backToMainMenu());
        assertEquals(UiState.MAIN_MENU, fromPause.state());

        UiStateMachine fromSettings = new UiStateMachine(UiState.PAUSED);
        fromSettings.openSettings();
        assertTrue(fromSettings.backToMainMenu());
        assertEquals(UiState.MAIN_MENU, fromSettings.state());
    }

    @Test
    void backToMainMenuIsRejectedWhilePlaying() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);

        assertFalse(m.backToMainMenu(), "游玩中'回主菜单'必须先暂停，否则没有存档时机");
        assertEquals(UiState.PLAYING, m.state());
    }

    @Test
    void backToMainMenuFromMainMenuIsRejected() {
        UiStateMachine m = new UiStateMachine(UiState.MAIN_MENU);

        assertFalse(m.backToMainMenu());
        assertEquals(1, m.rejectedTransitions());
    }

    // ============================================================ 退出

    @Test
    void quitRequestIsRecordedOnceAndIsNotAStateTransition() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);

        assertTrue(m.requestQuit());
        assertTrue(m.isQuitRequested());
        assertEquals(UiState.PLAYING, m.state(),
                "退出不是界面状态 —— 状态机只请求，真正的收尾由主循环做");
        assertFalse(m.requestQuit(), "重复请求必须被吸收，避免退出流程跑两遍");
    }

    @Test
    void quitCanBeRequestedFromMainMenu() {
        UiStateMachine m = new UiStateMachine(UiState.MAIN_MENU);

        assertTrue(m.requestQuit());
        assertTrue(m.isQuitRequested());
    }

    // ============================================================ 历史与计数

    @Test
    void historyRecordsEveryTransitionForPostMortemReading() {
        UiStateMachine m = new UiStateMachine(UiState.MAIN_MENU);
        m.startGame();
        m.pause();
        m.openSettings();
        m.closeSettings();
        m.resume();

        String log = String.join("\n", m.history());
        assertTrue(log.contains("开始游戏"));
        assertTrue(log.contains("暂停"));
        assertTrue(log.contains("打开设置"));
        assertTrue(log.contains("关闭设置"));
        assertTrue(log.contains("继续游戏"));
    }

    @Test
    void historyIsBoundedSoALongSessionCannotGrowItForever() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);

        for (int i = 0; i < 500; i++) {
            m.onEscape();
        }

        assertTrue(m.history().size() <= 64,
                "长时间运行后历史不能无界增长，实际=" + m.history().size());
    }

    @Test
    void rejectedTransitionsAreCountedForTheReport() {
        UiStateMachine m = new UiStateMachine(UiState.PLAYING);

        m.openSettings();
        m.backToMainMenu();
        m.closeSettings();
        m.startGame();

        assertEquals(4, m.rejectedTransitions(),
                "拒绝次数是报告里'状态机真的在拦截'的证据，不能只返回 false 不计数");
        assertEquals(UiState.PLAYING, m.state(), "被拒绝的请求不得改变状态");
    }

    @Test
    void toStringNamesTheCurrentState() {
        assertTrue(new UiStateMachine(UiState.PAUSED).toString().contains("Paused"));
    }
}

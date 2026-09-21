package com.skyisland.ui;

import com.skyisland.settings.Action;
import com.skyisland.settings.GameSettings;
import com.skyisland.settings.InputBinding;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置界面的行为（M1.5 规格第 3/5 条）。
 *
 * <p><b>抽取这个控制器的理由，正是本类能被写出来的理由：</b>
 * "按左键让 FOV 减 1"、"回车让 VSync 翻转"、"点 Back 时进行中的重绑要被放弃"
 * 这些规则本身与 GL 毫无关系，但若它们写在游戏主类里，就只能靠在真实窗口里
 * 敲键盘来验证 —— 于是"左右键到底能不能调值"这类问题只能靠肉眼。
 * 抽出来之后，界面逻辑的每一条分支都变成可断言的纯函数。
 *
 * <p><b>另外两条容易做错、本类专门守住的：</b>
 * <ul>
 *   <li>取消重绑<u>不</u>触发 {@code SETTINGS_CHANGED}。它确实什么都不改，
 *       触发的话调用方会多写一次盘 —— 而"什么都没改却写了盘"是没人会发现的浪费，
 *       直到它把一次真实写入的备份挤掉；</li>
 *   <li>刷新界面必须<u>保留选中项</u>。否则调一次灵敏度，光标就跳回"Controls"分节标题上。</li>
 * </ul>
 */
class SettingsMenuControllerTest {

    // ============================================================ 滑杆

    @Test
    void enterOnASliderTurnsItUpByOneStep() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);

        assertEquals(SettingsMenuController.Effect.SETTINGS_CHANGED,
                c.activate(Menus.ID_SENSITIVITY));

        assertEquals(1.05, s.mouseSensitivity(), 1e-9);
    }

    @Test
    void leftAndRightAdjustSlidersInOppositeDirections() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);

        c.adjust(Menus.ID_FOV, SettingsMenuController.DIR_UP);
        assertEquals(71.0, s.fovDeg(), 1e-9);
        c.adjust(Menus.ID_FOV, SettingsMenuController.DIR_DOWN);
        assertEquals(70.0, s.fovDeg(), 1e-9);

        c.adjust(Menus.ID_MASTER_VOLUME, SettingsMenuController.DIR_DOWN);
        assertEquals(75, s.masterVolume());
    }

    @Test
    void adjustingWithZeroDirectionDoesNothing() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);

        assertEquals(SettingsMenuController.Effect.NONE, c.adjust(Menus.ID_FOV, 0));
        assertEquals(70.0, s.fovDeg(), 1e-9);
    }

    @Test
    void slidersClampAtTheirBoundsThroughTheUi() {
        GameSettings s = new GameSettings();
        s.setFovDeg(60);
        SettingsMenuController c = new SettingsMenuController(s);

        for (int i = 0; i < 50; i++) {
            c.adjust(Menus.ID_FOV, SettingsMenuController.DIR_DOWN);
        }

        assertEquals(GameSettings.MIN_FOV, s.fovDeg(), 1e-9);
    }

    @Test
    void nonSliderRowsIgnoreHorizontalInput() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);

        assertEquals(SettingsMenuController.Effect.NONE, c.adjust(Menus.ID_RESTORE_DEFAULTS, 1));
        assertEquals(SettingsMenuController.Effect.NONE, c.adjust(Menus.ID_BACK, 1));
        assertEquals(SettingsMenuController.Effect.NONE, c.adjust(null, 1));
        assertEquals(SettingsMenuController.Effect.NONE, c.adjust("nope", 1));
    }

    // ============================================================ 开关

    @Test
    void enterTogglesBooleans() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);

        assertEquals(SettingsMenuController.Effect.SETTINGS_CHANGED, c.activate(Menus.ID_VSYNC));
        assertTrue(s.vsync());
        c.activate(Menus.ID_VSYNC);
        assertFalse(s.vsync());

        c.activate(Menus.ID_INVERT_Y);
        assertTrue(s.invertMouseY());
        c.activate(Menus.ID_SHOW_FPS);
        assertTrue(s.showFps());
    }

    @Test
    void horizontalInputOnABooleanSetsItExplicitly() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);

        c.adjust(Menus.ID_INVERT_Y, SettingsMenuController.DIR_UP);
        assertTrue(s.invertMouseY());
        c.adjust(Menus.ID_INVERT_Y, SettingsMenuController.DIR_DOWN);
        assertFalse(s.invertMouseY(), "左右调布尔应表达'开/关'，而不是翻转");
    }

    // ============================================================ 不可选行

    @Test
    void activatingANonSelectableRowDoesNothing() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);

        assertEquals(SettingsMenuController.Effect.NONE, c.activate("__spacer__"));
        assertEquals(SettingsMenuController.Effect.NONE, c.activate("__header__Controls"));
        assertEquals(SettingsMenuController.Effect.NONE, c.activate(null));
        assertEquals(SettingsMenuController.Effect.NONE, c.activate("no_such_row"));
        assertEquals(1.0, s.mouseSensitivity(), 1e-9);
    }

    // ============================================================ 恢复默认

    @Test
    void restoreDefaultsResetsEverythingIncludingRebindings() {
        GameSettings s = new GameSettings();
        s.setFovDeg(90);
        s.setInvertMouseY(true);
        s.keyBindings().set(Action.JUMP, InputBinding.key(GLFW.GLFW_KEY_J));
        SettingsMenuController c = new SettingsMenuController(s);

        assertEquals(SettingsMenuController.Effect.SETTINGS_CHANGED, c.activate(Menus.ID_RESTORE_DEFAULTS));

        assertTrue(s.isAllDefaults());
    }

    @Test
    void restoreDefaultsWhileWaitingForInputDoesNotLeaveTheUiStuck() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);
        c.activate(Menus.bindId(Action.JUMP));
        assertTrue(c.rebind().isWaiting());

        c.activate(Menus.ID_RESTORE_DEFAULTS);

        assertFalse(c.rebind().isWaiting(), "否则界面会一直停在'正在等待输入'");
        assertEquals("SPACE", c.screen().valueOf(Menus.bindId(Action.JUMP)));
    }

    // ============================================================ 返回

    @Test
    void backReportsBackAndAbandonsAnyOngoingRebind() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);
        c.activate(Menus.bindId(Action.JUMP));

        assertEquals(SettingsMenuController.Effect.BACK, c.activate(Menus.ID_BACK));

        assertFalse(c.rebind().isWaiting());
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_SPACE), s.keyBindings().get(Action.JUMP));
    }

    // ============================================================ 重绑流程

    @Test
    void activatingABindingRowOpensTheWaitingForInputState() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);

        assertEquals(SettingsMenuController.Effect.OPEN_REBIND, c.activate(Menus.bindId(Action.JUMP)));

        assertTrue(c.rebind().isWaiting());
        assertEquals(Action.JUMP, c.rebind().target());
    }

    @Test
    void theTargetRowShowsThatItIsWaiting() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);

        c.activate(Menus.bindId(Action.JUMP));

        assertEquals("(press a key)", c.screen().valueOf(Menus.bindId(Action.JUMP)),
                "界面必须显示出'正在等你按键'，否则看起来像卡住了");
    }

    @Test
    void captureAssignsAndTheRowShowsTheNewKey() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);
        c.activate(Menus.bindId(Action.JUMP));

        assertEquals(SettingsMenuController.Effect.SETTINGS_CHANGED,
                c.onRebindCapture(InputBinding.key(GLFW.GLFW_KEY_J)));

        assertEquals(InputBinding.key(GLFW.GLFW_KEY_J), s.keyBindings().get(Action.JUMP));
        assertEquals("J", c.screen().valueOf(Menus.bindId(Action.JUMP)));
    }

    @Test
    void conflictMarksBothRowsSoThePlayerSeesWhoLosesTheKey() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);
        c.activate(Menus.bindId(Action.MOVE_BACKWARD));

        c.onRebindCapture(InputBinding.key(GLFW.GLFW_KEY_W));

        assertEquals("(conflict)", c.screen().valueOf(Menus.bindId(Action.MOVE_BACKWARD)));
        String victimRow = c.screen().valueOf(Menus.bindId(Action.MOVE_FORWARD));
        assertTrue(victimRow.contains("(none)"),
                "必须显示'替换后前进会失去键位'，实际=" + victimRow);
    }

    @Test
    void confirmingTheConflictUpdatesBothRows() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);
        c.activate(Menus.bindId(Action.MOVE_BACKWARD));
        c.onRebindCapture(InputBinding.key(GLFW.GLFW_KEY_W));

        assertEquals(SettingsMenuController.Effect.SETTINGS_CHANGED, c.onConfirmReplace());

        assertEquals("W", c.screen().valueOf(Menus.bindId(Action.MOVE_BACKWARD)));
        assertEquals("(none)", c.screen().valueOf(Menus.bindId(Action.MOVE_FORWARD)));
        assertEquals(InputBinding.UNBOUND, s.keyBindings().get(Action.MOVE_FORWARD));
    }

    @Test
    void cancellingARebindDoesNotAskForAWriteBack() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);
        c.activate(Menus.bindId(Action.JUMP));

        assertEquals(SettingsMenuController.Effect.NONE, c.onCancelRebind(),
                "取消什么都不改，不该触发'需要落盘'");
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_SPACE), s.keyBindings().get(Action.JUMP));
        assertFalse(c.rebind().isWaiting());
    }

    @Test
    void cancellingAConflictAlsoDoesNotAskForAWriteBack() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);
        c.activate(Menus.bindId(Action.MOVE_BACKWARD));
        c.onRebindCapture(InputBinding.key(GLFW.GLFW_KEY_W));

        assertEquals(SettingsMenuController.Effect.NONE, c.onCancelRebind());

        assertEquals(InputBinding.key(GLFW.GLFW_KEY_W), s.keyBindings().get(Action.MOVE_FORWARD));
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_S), s.keyBindings().get(Action.MOVE_BACKWARD));
        assertEquals("S", c.screen().valueOf(Menus.bindId(Action.MOVE_BACKWARD)));
    }

    @Test
    void recapturingTheSameKeyChangesNothingAndClosesTheFlow() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);
        c.activate(Menus.bindId(Action.JUMP));

        assertEquals(SettingsMenuController.Effect.NONE,
                c.onRebindCapture(InputBinding.key(GLFW.GLFW_KEY_SPACE)));
        assertFalse(c.rebind().isWaiting());
    }

    // ============================================================ 刷新与选中项

    @Test
    void refreshKeepsTheSelectedRowAcrossValueChanges() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);
        c.screen().selectById(Menus.ID_FOV);

        c.activate(Menus.ID_FOV);

        assertEquals(Menus.ID_FOV, c.screen().selectedId(),
                "调一次 FOV 就让光标跳回第一行，是玩家立刻能感觉到的 bug");
    }

    @Test
    void refreshIsIdempotentWhenNothingChanged() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);
        String before = c.screen().valueOf(Menus.ID_SENSITIVITY);

        c.refresh();

        assertEquals(before, c.screen().valueOf(Menus.ID_SENSITIVITY));
        assertEquals(Menus.ID_SENSITIVITY, c.screen().selectedId());
    }

    @Test
    void everyValueChangeKeepsTheDisplayedValueInSyncWithTheModel() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);

        c.activate(Menus.ID_SENSITIVITY);
        assertEquals(String.format(java.util.Locale.ROOT, "%.2f", s.mouseSensitivity()),
                c.screen().valueOf(Menus.ID_SENSITIVITY));

        c.activate(Menus.ID_FOV);
        assertEquals(String.format(java.util.Locale.ROOT, "%.0f", s.fovDeg()),
                c.screen().valueOf(Menus.ID_FOV));

        c.activate(Menus.ID_VSYNC);
        assertEquals(s.vsync() ? "ON" : "OFF", c.screen().valueOf(Menus.ID_VSYNC));

        c.activate(Menus.ID_MASTER_VOLUME);
        assertEquals(String.valueOf(s.masterVolume()), c.screen().valueOf(Menus.ID_MASTER_VOLUME));
    }

    @Test
    void theControllerMutatesTheRealSettingsObjectInPlace() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);

        c.activate(Menus.ID_FOV);
        c.activate(Menus.bindId(Action.JUMP));
        c.onRebindCapture(InputBinding.key(GLFW.GLFW_KEY_J));

        // 若控制器在内部持有一份副本，游戏层拿到的设置就永远是旧的 ——
        // 现象是"设置界面上改了，游戏里没变"，而且不会有任何报错。
        assertEquals(71.0, s.fovDeg(), 1e-9);
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_J), s.keyBindings().get(Action.JUMP));
    }

    @Test
    void changingASettingThroughTheUiIsVisibleInTheSettingsSummary() {
        GameSettings s = new GameSettings();
        SettingsMenuController c = new SettingsMenuController(s);
        c.activate(Menus.bindId(Action.JUMP));
        c.onRebindCapture(InputBinding.key(GLFW.GLFW_KEY_J));

        String summary = String.join("\n", s.summaryLines());

        assertTrue(summary.contains("keybindings_custom  = 1"),
                "被改动的键位数量必须体现在摘要里，实际=" + summary);
        assertNotEquals(0, s.keyBindings().customized().size());
    }
}

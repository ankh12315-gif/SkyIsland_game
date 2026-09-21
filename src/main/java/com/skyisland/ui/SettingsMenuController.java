package com.skyisland.ui;

import com.skyisland.settings.Action;
import com.skyisland.settings.GameSettings;
import com.skyisland.settings.InputBinding;
import com.skyisland.util.Log;

/**
 * 设置界面的行为控制器（M1.5 规格第 3/5 条）：把"某一行的激活"翻译成"设置发生了什么变化"。
 *
 * <p><b>为什么不让游戏主类直接处理菜单项：</b>激活逻辑有十来条分支，且每条都可能
 * 连带三件事（改值 → 重新生成界面行 → 让运行时生效 → 落盘）。
 * 放在主类里就变成了主类的一半篇幅，而且<u>没有 GL 就无法测试</u>。
 * 抽出来之后，"按左键让 FOV 减 1"与"回车让 VSync 翻转"都是可断言的纯逻辑，
 * 主类只剩下"把这个 Effect 派发给渲染 / 存档 / 音频"三行。
 *
 * <p><b>{@link Effect#SETTINGS_CHANGED} 的含义是有意的粗粒度：</b>
 * 它不区分"改了哪个字段" —— 因为改任何一个字段的后续处理完全相同
 * （重新生成界面 + 应用到运行时 + 落盘）。细分成十个枚举值只会让调用点多十个分支，
 * 而分支体还是一模一样。
 */
public final class SettingsMenuController {

    /** 一次交互的后果，由游戏层派发。 */
    public enum Effect {
        /** 什么都没发生（例如对着不可选行按了回车）。 */
        NONE,
        /** 设置被修改（调用方需要：刷新界面 + 应用运行时 + 落盘）。 */
        SETTINGS_CHANGED,
        /** 进入"等待输入"状态（界面需要切到提示层）。 */
        OPEN_REBIND,
        /** 请求返回上一级界面。 */
        BACK
    }

    /** 方向：减少。 */
    public static final int DIR_DOWN = -1;

    /** 方向：增加。 */
    public static final int DIR_UP = 1;

    private final GameSettings settings;
    private final KeyRebindController rebind;
    private final MenuScreen screen;

    public SettingsMenuController(GameSettings settings) {
        this.settings = settings;
        this.rebind = new KeyRebindController(settings);
        this.screen = Menus.settingsMenu(settings);
    }

    public MenuScreen screen() {
        return screen;
    }

    public KeyRebindController rebind() {
        return rebind;
    }

    // ============================================================ 交互

    /**
     * 激活一行（回车 / 鼠标点击）。
     *
     * <p>滑杆行在"回车"时按 {@code +1} 步处理：只有左右键才能调值的界面，
     * 对不知道这个约定的玩家等于"点不动"。让回车也有效果，是"两种输入都能用"的最低成本实现。
     */
    public Effect activate(String entryId) {
        if (entryId == null) {
            return Effect.NONE;
        }
        MenuEntry entry = screen.entry(entryId);
        if (entry == null || !entry.selectable()) {
            return Effect.NONE;
        }

        switch (entryId) {
            case Menus.ID_SENSITIVITY -> {
                settings.adjustMouseSensitivity(DIR_UP);
                return changed();
            }
            case Menus.ID_FOV -> {
                settings.adjustFov(DIR_UP);
                return changed();
            }
            case Menus.ID_MASTER_VOLUME -> {
                settings.adjustMasterVolume(DIR_UP);
                return changed();
            }
            case Menus.ID_SFX_VOLUME -> {
                settings.adjustSfxVolume(DIR_UP);
                return changed();
            }
            case Menus.ID_INVERT_Y -> {
                settings.setInvertMouseY(!settings.invertMouseY());
                return changed();
            }
            case Menus.ID_VSYNC -> {
                settings.setVsync(!settings.vsync());
                return changed();
            }
            case Menus.ID_SHOW_FPS -> {
                settings.setShowFps(!settings.showFps());
                return changed();
            }
            case Menus.ID_RESTORE_DEFAULTS -> {
                rebind.restoreDefaults();
                settings.resetToDefaults();
                Log.info("[设置] 已恢复全部默认（含键位）");
                return changed();
            }
            case Menus.ID_BACK -> {
                if (rebind.cancel()) {
                    refresh();
                }
                return Effect.BACK;
            }
            default -> {
                Action action = Menus.actionOfBindId(entryId);
                if (action != null) {
                    if (rebind.begin(action)) {
                        refresh();
                        return Effect.OPEN_REBIND;
                    }
                    return Effect.NONE;
                }
                return Effect.NONE;
            }
        }
    }

    /** 调整一行（左右键 / 滚轮 / 点击滑杆两侧）。非滑杆行返回 {@link Effect#NONE}。 */
    public Effect adjust(String entryId, int direction) {
        if (entryId == null) {
            return Effect.NONE;
        }
        int dir = Integer.signum(direction);
        if (dir == 0) {
            return Effect.NONE;
        }
        switch (entryId) {
            case Menus.ID_SENSITIVITY -> settings.adjustMouseSensitivity(dir);
            case Menus.ID_FOV -> settings.adjustFov(dir);
            case Menus.ID_MASTER_VOLUME -> settings.adjustMasterVolume(dir);
            case Menus.ID_SFX_VOLUME -> settings.adjustSfxVolume(dir);
            case Menus.ID_VSYNC -> {
                // 布尔项也可用左右调：与开关行保持一致的"左右 = 改值"手感
                settings.setVsync(dir > 0);
            }
            case Menus.ID_INVERT_Y -> settings.setInvertMouseY(dir > 0);
            case Menus.ID_SHOW_FPS -> settings.setShowFps(dir > 0);
            default -> {
                return Effect.NONE;
            }
        }
        return changed();
    }

    /** 等待输入阶段收到的按键。 */
    public Effect onRebindCapture(InputBinding binding) {
        KeyRebindController.CaptureOutcome outcome = rebind.capture(binding);
        refresh();
        return switch (outcome) {
            case ASSIGNED, CONFLICT -> Effect.SETTINGS_CHANGED;
            case UNCHANGED, CANCELLED, IGNORED -> Effect.NONE;
        };
    }

    /** 冲突对话框上按回车 = 确认替换。 */
    public Effect onConfirmReplace() {
        boolean ok = rebind.confirmReplace();
        refresh();
        if (ok) {
            Log.info("[设置] 冲突已确认替换 → %s", rebind.describe());
        }
        return ok ? Effect.SETTINGS_CHANGED : Effect.NONE;
    }

    /** 冲突对话框上按 ESC，或等待输入中按 ESC = 取消。 */
    public Effect onCancelRebind() {
        if (rebind.isResolvingConflict()) {
            rebind.cancelReplace();
        } else {
            rebind.cancel();
        }
        refresh();
        // 取消不修改任何设置，因此不触发 SETTINGS_CHANGED（不需要落盘）
        return Effect.NONE;
    }

    // ============================================================ 界面刷新

    /**
     * 依据当前设置（与重绑阶段）重新生成行内容，保持选中项不变。
     *
     * <p>按键位行在等待输入 / 冲突时显示占位文案，让"界面卡住了"与
     * "正在等你按键"这两种状态在观感上完全不同。
     */
    public void refresh() {
        boolean selectionMoved = screen.rebuild(Menus.settingsEntries(settings));
        if (selectionMoved) {
            Log.info("[设置] 界面行内容重建后，原选中项已不存在，选中回到第一项");
        }
        applyRebindDecorations();
    }

    private void applyRebindDecorations() {
        if (rebind.phase() == KeyRebindController.Phase.IDLE) {
            return;
        }
        Action action = rebind.target();
        if (action != null) {
            String text = rebind.isResolvingConflict()
                    ? "(conflict)" : "(press a key)";
            screen.setValue(Menus.bindId(action), text);
        }
        if (rebind.isResolvingConflict() && rebind.conflictOwner() != null) {
            // 冲突的另一方也标出来，玩家才知道"替换后谁会失去键位"
            screen.setValue(Menus.bindId(rebind.conflictOwner()),
                    settings.keyBindings().get(rebind.conflictOwner()).display() + " -> (none)");
        }
    }

    private Effect changed() {
        refresh();
        return Effect.SETTINGS_CHANGED;
    }
}

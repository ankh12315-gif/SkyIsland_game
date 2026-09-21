package com.skyisland.ui;

import com.skyisland.settings.Action;
import com.skyisland.settings.GameSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 三个菜单屏的构建（M1.5 规格第 3/5/9 条）。
 *
 * <p><b>为什么集中在一个工厂里：</b>菜单项的 id 会被三个地方引用 ——
 * 游戏层的激活处理（"点了 start_game 要做什么"）、自测脚本（"我要点第 3 项"）、
 * 以及本类的构建代码。三者若各写字符串字面量，改名时漏一处就会变成
 * "点了没反应"且编译期完全静默。因此 id 全部是这里的常量，
 * 其余地方一律引用常量。
 *
 * <p><b>文案一律 ASCII：</b>HUD 文字用程序化 5×7 点阵字模（{@code BitmapFont}），
 * 它只覆盖 ASCII 32–126。写中文不会报错 —— 会渲染成一串 {@code '?'}，
 * 也就是"看起来像字体加载失败"。这个限制已记入 M1.5 报告的技术债。
 */
public final class Menus {

    // ---- 主菜单 / 暂停菜单 ----
    public static final String ID_START_GAME = "start_game";
    public static final String ID_RESUME = "resume";
    public static final String ID_OPEN_SETTINGS = "open_settings";
    public static final String ID_SAVE_TO_MAIN_MENU = "save_to_main_menu";
    public static final String ID_QUIT_GAME = "quit_game";

    // ---- 设置界面 ----
    public static final String ID_SENSITIVITY = "set_mouse_sensitivity";
    public static final String ID_INVERT_Y = "set_invert_mouse_y";
    public static final String ID_FOV = "set_fov";
    public static final String ID_VSYNC = "set_vsync";
    public static final String ID_SHOW_FPS = "set_show_fps";
    public static final String ID_MASTER_VOLUME = "set_master_volume";
    public static final String ID_SFX_VOLUME = "set_sfx_volume";
    public static final String ID_RESTORE_DEFAULTS = "restore_defaults";
    public static final String ID_BACK = "back";

    /** 键位行的 id 前缀：{@code bind_move_forward}。 */
    public static final String BIND_PREFIX = "bind_";

    public static String bindId(Action action) {
        return BIND_PREFIX + action.id();
    }

    /** 从键位行 id 反查动作；不是键位行时返回 {@code null}。 */
    public static Action actionOfBindId(String entryId) {
        if (entryId == null || !entryId.startsWith(BIND_PREFIX)) {
            return null;
        }
        return Action.byId(entryId.substring(BIND_PREFIX.length()));
    }

    private Menus() {
    }

    // ============================================================ 主菜单

    /**
     * 主菜单封面（规格第 9 条）：标题 + 副标题 + 三项。
     *
     * <p>世界已经在内存里（装配阶段完成），因此主菜单是"以真实场景为背景"的封面，
     * 不需要另做一张背景图 —— 这也让"开始游戏"是瞬时的。
     */
    public static MenuScreen mainMenu() {
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(MenuEntry.action(ID_START_GAME, "Start Game"));
        entries.add(MenuEntry.action(ID_OPEN_SETTINGS, "Settings"));
        entries.add(MenuEntry.action(ID_QUIT_GAME, "Quit Game"));
        return new MenuScreen("SKYISLAND", "Voxel Survival Prototype", entries);
    }

    // ============================================================ 暂停菜单

    /** 暂停菜单（规格第 2 条）。 */
    public static MenuScreen pauseMenu() {
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(MenuEntry.action(ID_RESUME, "Resume"));
        entries.add(MenuEntry.action(ID_OPEN_SETTINGS, "Settings"));
        entries.add(MenuEntry.action(ID_SAVE_TO_MAIN_MENU, "Save & Return to Main Menu"));
        entries.add(MenuEntry.spacer());
        entries.add(MenuEntry.action(ID_QUIT_GAME, "Quit Game"));
        return new MenuScreen("PAUSED", "World time is frozen", entries);
    }

    // ============================================================ 设置界面

    public static MenuScreen settingsMenu(GameSettings settings) {
        return new MenuScreen("SETTINGS", "Enter = toggle / rebind    Left-Right = adjust    Esc = back",
                settingsEntries(settings));
    }

    /**
     * 设置界面的行内容。每次设置变化后重新生成（{@link MenuScreen#rebuild} 会保留选中项）。
     *
     * <p><b>音量两项为什么显式标注"无音频后端"：</b>M1.5 没有音频子系统
     * （PRD 的音频属后续里程碑），这两项此刻只落盘、不发声。
     * 界面上直接写出来，胜过让人对着一个没有任何效果的滑杆猜。
     */
    public static List<MenuEntry> settingsEntries(GameSettings s) {
        List<MenuEntry> entries = new ArrayList<>();

        entries.add(MenuEntry.header("Controls"));
        entries.add(MenuEntry.slider(ID_SENSITIVITY, "Mouse Sensitivity",
                String.format(Locale.ROOT, "%.2f", s.mouseSensitivity())));
        entries.add(MenuEntry.toggle(ID_INVERT_Y, "Invert Mouse Y", s.invertMouseY()));
        entries.add(MenuEntry.slider(ID_FOV, "Field of View",
                String.format(Locale.ROOT, "%.0f", s.fovDeg())));
        entries.add(MenuEntry.toggle(ID_VSYNC, "VSync", s.vsync()));
        entries.add(MenuEntry.toggle(ID_SHOW_FPS, "Show FPS", s.showFps()));

        entries.add(MenuEntry.spacer());
        entries.add(MenuEntry.header("Audio (no audio backend in M1.5)"));
        entries.add(MenuEntry.slider(ID_MASTER_VOLUME, "Master Volume",
                String.valueOf(s.masterVolume())));
        entries.add(MenuEntry.slider(ID_SFX_VOLUME, "Sound Volume",
                String.valueOf(s.sfxVolume())));

        entries.add(MenuEntry.spacer());
        entries.add(MenuEntry.header("Key Bindings"));
        for (Action a : Action.values()) {
            String note = a.shortNote();
            String label = note.isEmpty() ? a.label() : a.label() + "  [" + note + "]";
            entries.add(MenuEntry.binding(bindId(a), label, s.keyBindings().get(a).display()));
        }

        entries.add(MenuEntry.spacer());
        entries.add(MenuEntry.action(ID_RESTORE_DEFAULTS, "Restore All Defaults"));
        entries.add(MenuEntry.action(ID_BACK, "Back"));
        return entries;
    }

    /** 当前选中的是不是一个滑杆行（决定左右键是否有意义）。 */
    public static boolean isSlider(MenuScreen screen) {
        MenuEntry e = screen.selectedEntry();
        return e != null && e.kind() == MenuEntry.Kind.SLIDER;
    }

    /** 当前选中的是不是"音量"类滑杆（M1.5 无音频后端，供报告与界面提示使用）。 */
    public static boolean isVolumeSlider(String entryId) {
        return ID_MASTER_VOLUME.equals(entryId) || ID_SFX_VOLUME.equals(entryId);
    }
}

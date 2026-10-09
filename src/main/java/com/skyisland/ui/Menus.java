package com.skyisland.ui;

import com.skyisland.settings.Action;
import com.skyisland.settings.GameSettings;

import static com.skyisland.ui.Localization.*;

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
 * <p><b>文案一律走 Localization（zh-CN）：</b>M1.5 时菜单文案是 ASCII，理由是
 * 当时的点阵字模只覆盖 ASCII。那是把<b>实现缺口</b>当成了<b>产品决定</b>
 * （见 {@link Localization} 的类注释，已明确纠正过一次）。M2.1 起
 * {@code CjkFont} 已烘焙 1484 个 CJK 字形，菜单完全能显示中文，因此本文件里
 * <b>不得出现任何中文（或英文）文案字面量</b> —— 每一句玩家可见文案都通过
 * {@link Localization#text(String)} 取，唯一来源是 {@code Localization}（PRD §6.7）。
 * 唯一的例外是品牌名 {@code SKYISLAND}（保持 ASCII）与键位行
 * （{@code Action#label()} 是给开发者/老配置文件看的落盘契约，按规格不翻译）。
 */
public final class Menus {

    // ---- 主菜单 / 暂停菜单 ----
    /**
     * 已废弃于 M2.2：主菜单不再有单条的"开始游戏"，改为"继续游戏 / 新建世界"两项。
     * 该常量保留是为了不破坏 {@code SkyIslandGame} 与 {@code M1_5UiSelfTest} 里对它的引用
     * （这两个文件不在 M2.2 菜单重构的改动范围内）；新的主菜单项用 {@link #ID_CONTINUE} /
     * {@link #ID_NEW_WORLD}。本常量对应的激活分支在 {@code SkyIslandGame} 里已不会再被命中。
     */
    @Deprecated
    public static final String ID_START_GAME = "start_game";
    public static final String ID_CONTINUE = "continue";
    public static final String ID_NEW_WORLD = "new_world";
    public static final String ID_RESUME = "resume";
    public static final String ID_OPEN_SETTINGS = "open_settings";
    public static final String ID_SAVE_TO_MAIN_MENU = "save_to_main_menu";
    public static final String ID_QUIT_GAME = "quit_game";
    /**
     * 结束创造会话（本次运行内）。
     *
     * <p>★ 它之所以在这里而不是"双击空格的第三态"：退出创造会
     * <b>带走全部五项能力</b>。那种操作不该藏在某个手感键的第三次按下里 ——
     * 实测那个版本让主理人遇到了"再双击一次，能力全没了"，
     * 而且因为飞行状态在站着时不可见，他连自己会触发它都预判不了。
     * ESC 菜单是玩家<b>预期找得到</b>的地方，且不会误触。
     */
    public static final String ID_END_CREATIVE_SESSION = "end_creative_session";

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
     * 主菜单封面（规格第 9 条）：品牌名标题 + 副标题 + 四项。
     *
     * <p>世界已经在内存里（装配阶段完成），因此主菜单是"以真实场景为背景"的封面，
     * 不需要另做一张背景图 —— 这也让"开始游戏"是瞬时的。
     *
     * <p>四项顺序（M2.2）：<b>继续游戏 / 新建世界 / 设置 / 退出游戏</b>。
     * {@code hasSave=false} 时"继续游戏"这一行降级为不可选的 INFO 行（"尚无存档"），
     * 而不是"可点但点了没反应" —— 那种行会让玩家以为菜单坏了。
     */
    public static MenuScreen mainMenu(boolean hasSave) {
        List<MenuEntry> entries = new ArrayList<>();
        if (hasSave) {
            entries.add(MenuEntry.action(ID_CONTINUE, Localization.text(MENU_MAIN_CONTINUE)));
        } else {
            entries.add(MenuEntry.info(Localization.text(MENU_MAIN_NO_SAVE)));
        }
        entries.add(MenuEntry.action(ID_NEW_WORLD, Localization.text(MENU_MAIN_NEW_WORLD)));
        entries.add(MenuEntry.action(ID_OPEN_SETTINGS, Localization.text(MENU_MAIN_SETTINGS)));
        entries.add(MenuEntry.action(ID_QUIT_GAME, Localization.text(MENU_MAIN_QUIT)));
        return new MenuScreen("SKYISLAND", Localization.text(MENU_MAIN_SUBTITLE), entries);
    }

    /**
     * 无参重载：默认认为存在存档（保留以免破坏既有调用点与测试）。
     *
     * <p>真正的"是否有存档"由 {@code SkyIslandGame} 在装配主菜单屏时决定并调用
     * {@link #mainMenu(boolean)}；这里给一个保守的默认（true），使其它调用方
     * 不传参时不会退化成"连继续游戏都没有"。
     */
    public static MenuScreen mainMenu() {
        return mainMenu(true);
    }

    // ============================================================ 暂停菜单

    /**
     * 暂停菜单（规格第 2 条）。标题 / 副标题 / 各项文案全部走 Localization。
     *
     * @param creativeSession 本局是否处在创造会话中。为真时多一行"结束创造模式"。
     */
    public static MenuScreen pauseMenu(boolean creativeSession) {
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(MenuEntry.action(ID_RESUME, Localization.text(MENU_PAUSE_RESUME)));
        // ★ 只在创造会话里才出现：常驻一个在生存模式下点不动的菜单项，
        //   是"看起来有能力、其实按了没反应"的另一种形式。
        if (creativeSession) {
            entries.add(MenuEntry.action(ID_END_CREATIVE_SESSION,
                    Localization.text(MENU_PAUSE_END_CREATIVE)));
        }
        entries.add(MenuEntry.action(ID_OPEN_SETTINGS, Localization.text(MENU_MAIN_SETTINGS)));
        entries.add(MenuEntry.action(ID_SAVE_TO_MAIN_MENU, Localization.text(MENU_PAUSE_SAVE_EXIT)));
        entries.add(MenuEntry.spacer());
        entries.add(MenuEntry.action(ID_QUIT_GAME, Localization.text(MENU_MAIN_QUIT)));
        return new MenuScreen(Localization.text(MENU_PAUSE_TITLE),
                Localization.text(MENU_PAUSE_SUBTITLE), entries);
    }

    /** 无创造会话的暂停菜单（自测与旧调用方）。 */
    public static MenuScreen pauseMenu() {
        return pauseMenu(false);
    }

    // ============================================================ 设置界面

    public static MenuScreen settingsMenu(GameSettings settings) {
        return new MenuScreen(Localization.text(MENU_SETTINGS_TITLE),
                Localization.text(MENU_SETTINGS_SUBTITLE), settingsEntries(settings));
    }

    /**
     * 设置界面的行内容。每次设置变化后重新生成（{@link MenuScreen#rebuild} 会保留选中项）。
     *
     * <p><b>分组（M2.2，全部文案走 Localization）：</b>
     * <ul>
     *   <li>控制：鼠标灵敏度、反转鼠标 Y、键位绑定（整个"键位绑定"小节归到控制）；</li>
     *   <li>显示：视野、垂直同步、显示 FPS；</li>
     *   <li>音频：主音量、音效音量。</li>
     * </ul>
     *
     * <p><b>音频小节为什么改为诚实表述：</b>M1.5 写"无音频后端"是因为那时确实没有；
     * M2.1 已接入 OpenAL，音量调节已生效。现在直说"音量已生效，是否有声音取决于本机音频设备"
     * （{@link Localization#MENU_SETTINGS_AUDIO_NOTE}），既承认有后端，也不夸大"一定能出声"。
     * 那段说明做成不可选的 INFO 行，避免占一个可选中行让上下键跳过一个空动作。
     */
    public static List<MenuEntry> settingsEntries(GameSettings s) {
        List<MenuEntry> entries = new ArrayList<>();

        // ---- 控制 ----
        entries.add(MenuEntry.header(Localization.text(MENU_SETTINGS_GROUP_CONTROL)));
        entries.add(MenuEntry.slider(ID_SENSITIVITY, Localization.text(MENU_SETTINGS_SENSITIVITY),
                String.format(Locale.ROOT, "%.2f", s.mouseSensitivity())));
        entries.add(MenuEntry.toggle(ID_INVERT_Y, Localization.text(MENU_SETTINGS_INVERT_Y), s.invertMouseY()));
        entries.add(MenuEntry.header(Localization.text(MENU_SETTINGS_KEY_BINDINGS)));
        for (Action a : Action.values()) {
            String note = a.shortNote();
            String label = note.isEmpty() ? a.label() : a.label() + "  [" + note + "]";
            entries.add(MenuEntry.binding(bindId(a), label, s.keyBindings().get(a).display()));
        }

        // ---- 显示 ----
        entries.add(MenuEntry.spacer());
        entries.add(MenuEntry.header(Localization.text(MENU_SETTINGS_GROUP_DISPLAY)));
        entries.add(MenuEntry.slider(ID_FOV, Localization.text(MENU_SETTINGS_FOV),
                String.format(Locale.ROOT, "%.0f", s.fovDeg())));
        entries.add(MenuEntry.toggle(ID_VSYNC, Localization.text(MENU_SETTINGS_VSYNC), s.vsync()));
        entries.add(MenuEntry.toggle(ID_SHOW_FPS, Localization.text(MENU_SETTINGS_SHOW_FPS), s.showFps()));

        // ---- 音频 ----
        entries.add(MenuEntry.spacer());
        entries.add(MenuEntry.header(Localization.text(MENU_SETTINGS_GROUP_AUDIO)));
        entries.add(MenuEntry.info(Localization.text(MENU_SETTINGS_AUDIO_NOTE)));
        entries.add(MenuEntry.slider(ID_MASTER_VOLUME, Localization.text(MENU_SETTINGS_MASTER_VOLUME),
                String.valueOf(s.masterVolume())));
        entries.add(MenuEntry.slider(ID_SFX_VOLUME, Localization.text(MENU_SETTINGS_SFX_VOLUME),
                String.valueOf(s.sfxVolume())));

        // ---- 末尾 ----
        entries.add(MenuEntry.spacer());
        entries.add(MenuEntry.action(ID_RESTORE_DEFAULTS, Localization.text(MENU_SETTINGS_RESTORE_DEFAULTS)));
        entries.add(MenuEntry.action(ID_BACK, Localization.text(MENU_SETTINGS_BACK)));
        return entries;
    }

    /** 当前选中的是不是一个滑杆行（决定左右键是否有意义）。 */
    public static boolean isSlider(MenuScreen screen) {
        MenuEntry e = screen.selectedEntry();
        return e != null && e.kind() == MenuEntry.Kind.SLIDER;
    }

    /** 当前选中的是不是"音量"类滑杆（M2.1 已接入 OpenAL，音量已生效，是否有声音取决于本机音频设备）。 */
    public static boolean isVolumeSlider(String entryId) {
        return ID_MASTER_VOLUME.equals(entryId) || ID_SFX_VOLUME.equals(entryId);
    }
}

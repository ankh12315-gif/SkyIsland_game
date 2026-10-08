package com.skyisland.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 菜单屏的选中项与内容更新（M1.5 规格第 3/5/9 条）。
 *
 * <p><b>三条不变式，每条对应一个玩家真能遇到的 bug：</b>
 * <ol>
 *   <li><b>选中项必须永远可选中。</b>设置界面里 26 行中有 4 行是分节标题或空行。
 *       若光标能停在这些行上，按回车就"什么都没发生" —— 玩家会认为菜单坏了。</li>
 *   <li><b>上下移动必须环绕。</b>否则在暂停菜单（只有几行）里连按向下会"卡住不动"，
 *       看起来像键盘失灵。</li>
 *   <li><b>重建内容必须按 id 保留选中项。</b>设置界面在每次改值后都要重建
 *       （显示的数值变了）。若不保留，玩家每调一次灵敏度，光标就跳回第一行 ——
 *       这是自动化测试最容易漏、玩家却立刻能感觉到的一类 bug。
 *       本类的 {@link #rebuildKeepsTheSelectionOnTheSameRow()} 就是钉它。</li>
 * </ol>
 */
class MenuScreenTest {

    private static MenuScreen threeItems() {
        return new MenuScreen("T", "sub", List.of(
                MenuEntry.action("a", "A"),
                MenuEntry.spacer(),
                MenuEntry.action("b", "B"),
                MenuEntry.header("H"),
                MenuEntry.action("c", "C")));
    }

    // ============================================================ 初始选中

    @Test
    void selectionStartsOnTheFirstSelectableRow() {
        MenuScreen s = new MenuScreen("T", "sub", List.of(
                MenuEntry.header("H"),
                MenuEntry.spacer(),
                MenuEntry.action("a", "A"),
                MenuEntry.action("b", "B")));

        assertEquals(2, s.selectedIndex(), "分节标题与空行不能被选中");
        assertEquals("a", s.selectedId());
    }

    @Test
    void aMenuWithNoSelectableRowHasNoSelection() {
        MenuScreen s = new MenuScreen("T", "sub", List.of(
                MenuEntry.header("H"),
                MenuEntry.spacer()));

        assertEquals(-1, s.selectedIndex());
        assertNull(s.selectedEntry());
        assertNull(s.selectedId());
    }

    @Test
    void mainMenuWithSaveShowsSelectableContinue() {
        MenuScreen m = Menus.mainMenu(true);

        assertEquals(Menus.ID_CONTINUE, m.selectedId(),
                "有存档时封面默认停在'继续游戏'，玩家按回车就能进游戏");
        MenuEntry cont = m.entry(Menus.ID_CONTINUE);
        assertNotNull(cont, "有存档时主菜单应有'继续游戏'项");
        assertTrue(cont.selectable(), "'继续游戏'应当可选");
        assertEquals("继续游戏", cont.label());
        assertEquals("SKYISLAND", m.title(), "品牌名保持 ASCII，不被本地化");
        assertEquals("体素生存原型", m.subtitle(), "副标题应来自 Localization");
    }

    @Test
    void mainMenuWithoutSaveShowsNonSelectableNoSaveHint() {
        MenuScreen m = Menus.mainMenu(false);

        assertNull(m.entry(Menus.ID_CONTINUE),
                "无存档时不应有可选的'继续游戏'项（否则会变成'点了没反应'）");
        boolean hasNoSaveInfo = m.entries().stream()
                .anyMatch(e -> e.kind() == MenuEntry.Kind.INFO && e.label().contains("尚无存档"));
        assertTrue(hasNoSaveInfo, "无存档时应以不可选的 INFO 行提示'尚无存档'");
        // 无存档时第一项可选中行应是"新建世界"
        assertEquals(Menus.ID_NEW_WORLD, m.selectedId(),
                "无存档时默认选中'新建世界'");
    }

    // ============================================================ 导航

    @Test
    void moveDownSkipsNonSelectableRows() {
        MenuScreen s = threeItems();

        s.moveDown();
        assertEquals("b", s.selectedId(), "空行要被跳过");
        s.moveDown();
        assertEquals("c", s.selectedId(), "分节标题要被跳过");
    }

    @Test
    void navigationWrapsAroundInBothDirections() {
        MenuScreen s = threeItems();

        s.moveUp();
        assertEquals("c", s.selectedId(), "在第一项按向上应跳到最后一个可选项");
        s.moveDown();
        assertEquals("a", s.selectedId(), "在最后一项按向下应回到第一个可选项");
    }

    @Test
    void repeatedMoveDownNeverGetsStuck() {
        MenuScreen s = threeItems();

        for (int i = 0; i < 30; i++) {
            s.moveDown();
            assertNotNull(s.selectedEntry(), "任何一次移动之后都必须有选中项");
            assertTrue(s.selectedEntry().selectable());
        }
        assertEquals("a", s.selectedId(), "30 次向下 = 10 圈，回到起点");
    }

    @Test
    void movingInAMenuWithNoSelectableRowIsANoOp() {
        MenuScreen s = new MenuScreen("T", "sub", List.of(MenuEntry.header("H")));

        s.moveDown();
        s.moveUp();

        assertEquals(-1, s.selectedIndex(), "没有可选项时不能把索引移到非法值上");
    }

    // ============================================================ 鼠标悬停

    @Test
    void hoverAcceptsSelectableRowsOnly() {
        MenuScreen s = threeItems();

        assertTrue(s.hover(2));
        assertEquals("b", s.selectedId());

        assertFalse(s.hover(1), "悬停在空行上不得改变选中项");
        assertFalse(s.hover(3), "悬停在分节标题上不得改变选中项");
        assertFalse(s.hover(-1));
        assertFalse(s.hover(99));
        assertEquals("b", s.selectedId());
    }

    @Test
    void hoverOnTheAlreadySelectedRowReportsNoChange() {
        MenuScreen s = threeItems();

        assertFalse(s.hover(0), "没有变化时返回 false，避免调用方做多余的重绘");
    }

    @Test
    void selectByIdRejectsUnknownOrNonSelectableIds() {
        MenuScreen s = threeItems();

        assertFalse(s.selectById("nope"));
        assertFalse(s.selectById("__spacer__"));
        assertTrue(s.selectById("c"));
        assertEquals("c", s.selectedId());
    }

    // ============================================================ 重建

    @Test
    void rebuildKeepsTheSelectionOnTheSameRow() {
        MenuScreen s = Menus.settingsMenu(new com.skyisland.settings.GameSettings());
        assertTrue(s.selectById(Menus.ID_FOV));

        boolean moved = s.rebuild(Menus.settingsEntries(new com.skyisland.settings.GameSettings()));

        assertFalse(moved, "行内容重建后，选中项必须原地不动");
        assertEquals(Menus.ID_FOV, s.selectedId(),
                "改一次 FOV 就让光标跳回第一行，是玩家立刻能感觉到的 bug");
    }

    @Test
    void rebuildFallsBackToTheFirstRowOnlyWhenTheSelectionDisappeared() {
        MenuScreen s = threeItems();
        s.selectById("c");

        boolean moved = s.rebuild(List.of(MenuEntry.action("a", "A"), MenuEntry.action("b", "B")));

        assertTrue(moved, "选中项消失时必须报告'位置变了'，让日志能解释光标为什么跳了");
        assertEquals("a", s.selectedId());
    }

    @Test
    void rebuildOnAnEmptyListLeavesNoSelection() {
        MenuScreen s = threeItems();

        s.rebuild(List.of());

        assertEquals(-1, s.selectedIndex());
        assertEquals(0, s.size());
    }

    @Test
    void rebuildAcceptingAListThatMutatesLaterDoesNotBreakTheScreen() {
        MenuScreen s = threeItems();
        List<MenuEntry> external = new java.util.ArrayList<>(List.of(MenuEntry.action("a", "A")));

        s.rebuild(external);
        external.clear();

        assertEquals(1, s.size(), "重建必须把行复制进来，不能持有外部列表的引用");
    }

    // ============================================================ 就地更新

    @Test
    void setValueUpdatesOneRowWithoutMovingTheSelection() {
        MenuScreen s = Menus.settingsMenu(new com.skyisland.settings.GameSettings());
        s.selectById(Menus.ID_SENSITIVITY);

        assertTrue(s.setValue(Menus.ID_SENSITIVITY, "1.50"));

        assertEquals("1.50", s.valueOf(Menus.ID_SENSITIVITY));
        assertEquals(Menus.ID_SENSITIVITY, s.selectedId());
    }

    @Test
    void setValueOnAnUnknownIdReportsFailure() {
        MenuScreen s = threeItems();

        assertFalse(s.setValue("nope", "x"));
    }

    @Test
    void entriesAreExposedAsAnImmutableCopy() {
        MenuScreen s = threeItems();

        List<MenuEntry> copy = s.entries();
        assertEquals(5, copy.size());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> copy.add(MenuEntry.action("z", "Z")),
                "对外暴露的列表必须不可变，否则外部代码能绕过选中项不变式");
    }

    // ============================================================ 设置界面的行构成

    @Test
    void settingsMenuExposesEverySettingAndEveryAction() {
        com.skyisland.settings.GameSettings settings = new com.skyisland.settings.GameSettings();
        MenuScreen s = Menus.settingsMenu(settings);

        for (String id : new String[]{Menus.ID_SENSITIVITY, Menus.ID_INVERT_Y, Menus.ID_FOV,
                Menus.ID_VSYNC, Menus.ID_SHOW_FPS, Menus.ID_MASTER_VOLUME, Menus.ID_SFX_VOLUME,
                Menus.ID_RESTORE_DEFAULTS, Menus.ID_BACK}) {
            assertNotNull(s.entry(id), "设置界面缺少行: " + id);
        }
        for (com.skyisland.settings.Action a : com.skyisland.settings.Action.values()) {
            assertNotNull(s.entry(Menus.bindId(a)), "设置界面缺少键位行: " + a.id());
        }
    }

    /**
     * M2.2：设置界面的可见标签必须来自 {@link Localization}（中文），不得有英文/中文硬编码残留。
     *
     * <p>判据：每个<b>非键位绑定</b>的行（分组标题 / 滑杆 / 开关 / 动作 / INFO 说明）都必须含有
     * CJK 字符 —— 因为 Localization 里这些 key 的中文文案是 CJK，而 {@code Action#label()}
     * 是英文 ASCII。若有人"顺手把某行写成英文字面量"或"绕开 Localization 直接写中文"，
     * 这条断言就会抓住它。键位绑定行按规格保留英文（落盘契约），单独豁免。
     */
    @Test
    void settingsVisibleLabelsAreLocalizedChineseNotHardcodedAscii() {
        MenuScreen s = Menus.settingsMenu(new com.skyisland.settings.GameSettings());

        for (MenuEntry e : s.entries()) {
            if (e.kind() == MenuEntry.Kind.BINDING) {
                continue; // 键位行允许英文 ASCII（Action.label 是落盘契约）
            }
            if (e.kind() == MenuEntry.Kind.SPACER) {
                // 分组之间的空行本来就没有文字。但它必须**真的**没有文字 ——
                // 否则"空行"就成了一个能藏英文字面量的盲区。
                assertEquals("", e.label(), "空行不得携带任何可见文字: " + e.label());
                continue;
            }
            assertTrue(containsCjk(e.label()),
                    "设置界面的可见标签必须是中文（来自 Localization），不得是英文硬编码: " + e.label());
        }
        assertTrue(containsCjk(s.subtitle()), "设置副标题（操作提示）必须来自 Localization");
        assertTrue(containsCjk(s.title()), "设置标题必须来自 Localization");
    }

    /** 各设置项标签确实等于 {@code Localization.text(...)} —— 证明走了唯一来源，而不是又写了一份字面量。 */
    @Test
    void settingsItemLabelsComeFromLocalization() {
        MenuScreen s = Menus.settingsMenu(new com.skyisland.settings.GameSettings());
        assertEquals(Localization.text(Localization.MENU_SETTINGS_SENSITIVITY), s.entry(Menus.ID_SENSITIVITY).label());
        assertEquals(Localization.text(Localization.MENU_SETTINGS_INVERT_Y), s.entry(Menus.ID_INVERT_Y).label());
        assertEquals(Localization.text(Localization.MENU_SETTINGS_FOV), s.entry(Menus.ID_FOV).label());
        assertEquals(Localization.text(Localization.MENU_SETTINGS_VSYNC), s.entry(Menus.ID_VSYNC).label());
        assertEquals(Localization.text(Localization.MENU_SETTINGS_SHOW_FPS), s.entry(Menus.ID_SHOW_FPS).label());
        assertEquals(Localization.text(Localization.MENU_SETTINGS_MASTER_VOLUME), s.entry(Menus.ID_MASTER_VOLUME).label());
        assertEquals(Localization.text(Localization.MENU_SETTINGS_SFX_VOLUME), s.entry(Menus.ID_SFX_VOLUME).label());
        assertEquals(Localization.text(Localization.MENU_SETTINGS_RESTORE_DEFAULTS), s.entry(Menus.ID_RESTORE_DEFAULTS).label());
        assertEquals(Localization.text(Localization.MENU_SETTINGS_BACK), s.entry(Menus.ID_BACK).label());
        assertEquals(Localization.text(Localization.MENU_SETTINGS_GROUP_CONTROL),
                s.entries().stream().filter(e -> e.kind() == MenuEntry.Kind.HEADER).findFirst().orElseThrow().label());
    }

    /** 三个分组标题都存在，且相对顺序为 控制 → 显示 → 音频（键位绑定是控制下的小节，不计入独立分组）。 */
    @Test
    void threeSettingGroupsAppearInOrderControlDisplayAudio() {
        MenuScreen s = Menus.settingsMenu(new com.skyisland.settings.GameSettings());
        java.util.List<String> headers = s.entries().stream()
                .filter(e -> e.kind() == MenuEntry.Kind.HEADER)
                .map(MenuEntry::label)
                .toList();
        String control = Localization.text(Localization.MENU_SETTINGS_GROUP_CONTROL);
        String display = Localization.text(Localization.MENU_SETTINGS_GROUP_DISPLAY);
        String audio = Localization.text(Localization.MENU_SETTINGS_GROUP_AUDIO);
        assertTrue(headers.contains(control) && headers.contains(display) && headers.contains(audio),
                "三个分组标题都必须存在");
        assertTrue(headers.indexOf(control) < headers.indexOf(display), "控制 必须在 显示 之前");
        assertTrue(headers.indexOf(display) < headers.indexOf(audio), "显示 必须在 音频 之前");
    }

    @Test
    void pauseMenuOffersResumeSettingsSaveAndQuit() {
        MenuScreen p = Menus.pauseMenu();

        assertNotNull(p.entry(Menus.ID_RESUME));
        assertNotNull(p.entry(Menus.ID_OPEN_SETTINGS));
        assertNotNull(p.entry(Menus.ID_SAVE_TO_MAIN_MENU));
        assertNotNull(p.entry(Menus.ID_QUIT_GAME));
        assertEquals(Menus.ID_RESUME, p.selectedId(), "暂停菜单默认选中'继续游戏'");
    }

    /** 主菜单与暂停菜单的可见动作项、标题、副标题都必须来自 Localization（中文），不得是英文硬编码。 */
    @Test
    void mainAndPauseMenuLabelsAreLocalizedChinese() {
        MenuScreen main = Menus.mainMenu(true);
        for (MenuEntry e : main.entries()) {
            if (e.kind() == MenuEntry.Kind.ACTION) {
                assertTrue(containsCjk(e.label()), "主菜单动作项必须中文: " + e.label());
            }
        }

        MenuScreen pause = Menus.pauseMenu();
        for (MenuEntry e : pause.entries()) {
            if (e.selectable()) {
                assertTrue(containsCjk(e.label()), "暂停菜单动作项必须中文: " + e.label());
            }
        }
        assertEquals("已暂停", pause.title());
        assertEquals("世界时间已冻结", pause.subtitle());
    }

    @Test
    void everySelectableRowHasAUniqueId() {
        MenuScreen s = Menus.settingsMenu(new com.skyisland.settings.GameSettings());
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (MenuEntry e : s.entries()) {
            if (e.selectable()) {
                assertTrue(seen.add(e.id()),
                        "可选中行的 id 重复会让'按 id 保留选中'指向错误的行：" + e.id());
            }
        }
    }

    @Test
    void unconsumedActionsAreLabeledWithTheirMilestone() {
        MenuScreen s = Menus.settingsMenu(new com.skyisland.settings.GameSettings());

        String crouch = s.entry(Menus.bindId(com.skyisland.settings.Action.CROUCH)).label();
        String forward = s.entry(Menus.bindId(com.skyisland.settings.Action.MOVE_FORWARD)).label();
        String reload = s.entry(Menus.bindId(com.skyisland.settings.Action.RELOAD)).label();

        // ★ 2026-10-08：蹲下这一格的内容变了，所以本条的两个判据都反过来了。
        //   上午它断言 crouch 带 [M2]（因为没有消费方）；现在它**有了**消费方
        //   （飞行下降，InputMapper 改走 Action.CROUCH），所以 [M2] 必须摘掉。
        assertFalse(crouch.contains("[M"),
                "★ 蹲下/飞行下降自 M4-S8b 起已有真实消费方（创造飞行下降），"
                        + "不得再挂里程碑标注 —— 留着会让玩家以为「按这个键没用」：" + crouch);

        // ★ 但更重要的是**显示名**：它现在只驱动飞行下降，不驱动蹲下。
        //   留着 "Crouch" 会让玩家以为按它能蹲，而实际只有飞的时候才有反应 ——
        //   那正是本用例想避免的"「能改键」被误读成「能蹲下」"，只是换了个方向。
        assertTrue(crouch.startsWith("Fly Descend"),
                "★ 显示名必须写明它驱动的是飞行下降，而不是蹲下：" + crouch);

        assertFalse(forward.contains("M"),
                "已消费的动作不需要标注：" + forward);
        // M2 起换弹有了真实消费方（GunState.tryStartReload），标注必须摘掉 ——
        // 留着 [M2] 会让玩家以为"按 R 没用"，而它其实已经生效。
        assertFalse(reload.contains("[M"),
                "M2 已消费换弹，界面上不得再挂 [M2] 标注：" + reload);
    }

    @Test
    void audioGroupStatesTheHonestOpenAlStatus() {
        // M2.1 已接入 OpenAL：界面必须诚实说明"音量已生效，是否有声音取决于本机音频设备"，
        // 而不是 M1.5 那种"无音频后端"。这条 INFO 行来自 Localization（中文），不得消失。
        MenuScreen s = Menus.settingsMenu(new com.skyisland.settings.GameSettings());

        boolean noted = s.entries().stream()
                .anyMatch(e -> e.kind() == MenuEntry.Kind.INFO
                        && e.label().equals(Localization.text(Localization.MENU_SETTINGS_AUDIO_NOTE)));
        assertTrue(noted, "音频小节必须诚实说明 M2.1 的 OpenAL 状态，不得再写'no audio backend'");
    }

    @Test
    void bindIdRoundTripsThroughTheActionTable() {
        for (com.skyisland.settings.Action a : com.skyisland.settings.Action.values()) {
            assertEquals(a, Menus.actionOfBindId(Menus.bindId(a)));
        }
        assertNull(Menus.actionOfBindId(Menus.ID_FOV));
        assertNull(Menus.actionOfBindId("bind_not_an_action"));
        assertNull(Menus.actionOfBindId(null));
    }

    /** 字符串是否含至少一个 CJK 统一表意文字（U+4E00–U+9FFF），用于判断标签是否来自 Localization。 */
    private static boolean containsCjk(String text) {
        return text.chars().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF);
    }
}

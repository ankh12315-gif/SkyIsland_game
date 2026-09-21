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
    void mainMenuStartsWithStartGameSelected() {
        MenuScreen m = Menus.mainMenu();

        assertEquals(Menus.ID_START_GAME, m.selectedId(),
                "封面默认停在'开始游戏'，玩家按回车就能进游戏");
        assertEquals("SKYISLAND", m.title());
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

    @Test
    void settingsMenuTextIsAsciiOnly() {
        MenuScreen s = Menus.settingsMenu(new com.skyisland.settings.GameSettings());

        for (MenuEntry e : s.entries()) {
            assertTrue(isAscii(e.label()), "行标题必须是 ASCII：" + e.label());
            assertTrue(isAscii(e.value()), "行值必须是 ASCII：" + e.value());
        }
        assertTrue(isAscii(s.subtitle()));
        assertTrue(isAscii(s.title()));
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

        assertTrue(crouch.contains("M2"),
                "蹲下至今没有玩法消费方，界面上必须写出来，否则'能改键'会被误读成'能蹲下'：" + crouch);
        assertFalse(forward.contains("M"),
                "已消费的动作不需要标注：" + forward);
        // M2 起换弹有了真实消费方（GunState.tryStartReload），标注必须摘掉 ——
        // 留着 [M2] 会让玩家以为"按 R 没用"，而它其实已经生效。
        assertFalse(reload.contains("[M"),
                "M2 已消费换弹，界面上不得再挂 [M2] 标注：" + reload);
    }

    @Test
    void volumeRowsAreMarkedAsHavingNoAudioBackend() {
        MenuScreen s = Menus.settingsMenu(new com.skyisland.settings.GameSettings());

        boolean marked = s.entries().stream()
                .anyMatch(e -> e.kind() == MenuEntry.Kind.HEADER
                        && e.label().toLowerCase().contains("no audio backend"));

        assertTrue(marked, "音量在本阶段没有任何听觉效果，界面上必须直说");
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

    private static boolean isAscii(String text) {
        return text.chars().allMatch(c -> c >= 32 && c < 127);
    }
}

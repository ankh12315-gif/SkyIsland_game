package com.skyisland.render.ui;

import com.skyisland.settings.GameSettings;
import com.skyisland.ui.MenuEntry;
import com.skyisland.ui.MenuScreen;
import com.skyisland.ui.Menus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 菜单几何布局与命中判定（M1.5 规格第 3/5/9 条）。
 *
 * <p><b>为什么布局值得单元测试：</b>菜单同时被鼠标与键盘驱动，两者必须指向<u>同一行</u>。
 * 如果命中判定用一套坐标、绘制用另一套，症状是"鼠标点在第 5 行，触发的却是第 6 行"。
 * 这类缺陷<u>无法通过看代码发现</u>（两处各自都"看起来对"），只能靠把
 * "给定一个像素点，应该命中哪一行"逐条断言下来。
 *
 * <p>本类因此不检查"画得好不好看"，只检查三件可判定的事：
 * <ol>
 *   <li>行矩形<b>不重叠</b>且<b>自上而下单调递增</b>（重叠会让点击落到错误的行）；</li>
 *   <li>命中判定<b>只在列范围内</b>生效（在界面左侧的空白处点击不该触发任何行）；</li>
 *   <li>同一个像素点<b>最多命中一行</b>（否则"点一下触发两件事"）；</li>
 *   <li>行高按行类型区分（空行最矮）—— 否则 720p 下 26 行的设置界面会溢出屏幕。</li>
 * </ol>
 */
class MenuLayoutTest {

    private static List<MenuEntry> settingsEntries() {
        return Menus.settingsEntries(new GameSettings());
    }

    // ============================================================ 行矩形

    @Test
    void rowsAreOrderedAndNeverOverlap() {
        List<MenuEntry> entries = settingsEntries();
        MenuLayout layout = MenuLayout.compute(1280, 720, entries, MenuLayout.Style.PANEL);

        for (int i = 1; i < layout.rowCount(); i++) {
            int prevBottom = layout.rowY(i - 1) + layout.rowHeight(i - 1);
            assertTrue(layout.rowY(i) >= prevBottom,
                    "第 " + i + " 行与上一行重叠了：上行的底是 " + prevBottom
                            + "，本行的顶是 " + layout.rowY(i));
            assertTrue(layout.rowHeight(i) > 0, "行高必须为正，第 " + i + " 行");
        }
    }

    @Test
    void rowHeightsDependOnTheRowKind() {
        List<MenuEntry> entries = settingsEntries();
        MenuLayout layout = MenuLayout.compute(1280, 720, entries, MenuLayout.Style.PANEL);

        int headerHeight = -1;
        int spacerHeight = -1;
        int itemHeight = -1;
        for (int i = 0; i < entries.size(); i++) {
            switch (entries.get(i).kind()) {
                case HEADER -> headerHeight = layout.rowHeight(i);
                case SPACER -> spacerHeight = layout.rowHeight(i);
                case SLIDER, TOGGLE, BINDING, ACTION -> itemHeight = layout.rowHeight(i);
                default -> {
                }
            }
        }

        assertTrue(spacerHeight < headerHeight, "空行必须比分节标题更矮");
        assertTrue(spacerHeight < itemHeight, "空行必须比可操作行更矮");
        assertTrue(itemHeight > 0 && headerHeight > 0);
    }

    @Test
    void theWholeSettingsMenuFitsOnASevenTwentyPenceScreen() {
        List<MenuEntry> entries = settingsEntries();
        MenuLayout layout = MenuLayout.compute(1280, 720, entries, MenuLayout.Style.PANEL);

        int lastBottom = layout.rowY(layout.rowCount() - 1) + layout.rowHeight(layout.rowCount() - 1);

        assertTrue(lastBottom < 720,
                "设置界面共 " + layout.rowCount() + " 行，最后一行底部 " + lastBottom
                        + " 已经越出屏幕 —— 玩家会看不到 'Back'，从而出不去");
    }

    @Test
    void scaleGrowsWithTheFramebuffer() {
        MenuLayout small = MenuLayout.compute(1280, 720, settingsEntries(), MenuLayout.Style.PANEL);
        MenuLayout big = MenuLayout.compute(2560, 1440, settingsEntries(), MenuLayout.Style.PANEL);

        assertEquals(1, small.uiScale());
        assertEquals(2, big.uiScale());
        assertTrue(big.rowHeight(0) > small.rowHeight(0), "高分屏下行高必须跟着放大");
    }

    @Test
    void columnIsCenteredAndNeverWiderThanTheFramebuffer() {
        for (int[] size : new int[][]{{1280, 720}, {1920, 1080}, {800, 600}, {3840, 2160}}) {
            MenuLayout layout = MenuLayout.compute(size[0], size[1], settingsEntries(),
                    MenuLayout.Style.PANEL);

            int left = layout.columnX();
            int right = layout.columnX() + layout.columnWidth();
            assertTrue(left >= 0, "列左边界越出屏幕：" + size[0] + "x" + size[1]);
            assertTrue(right <= size[0], "列右边界越出屏幕：" + size[0] + "x" + size[1]);
            assertTrue(Math.abs((size[0] - layout.columnWidth()) / 2 - left) <= 1,
                    "菜单列必须水平居中");
        }
    }

    // ============================================================ 命中判定

    @Test
    void aPointInTheMiddleOfARowHitsThatRow() {
        List<MenuEntry> entries = settingsEntries();
        MenuLayout layout = MenuLayout.compute(1280, 720, entries, MenuLayout.Style.PANEL);

        for (int i = 0; i < layout.rowCount(); i++) {
            double x = layout.columnX() + layout.columnWidth() / 2.0;
            double y = layout.rowY(i) + layout.rowHeight(i) / 2.0;
            assertEquals(i, layout.hitTestAny(x, y, layout.rowCount()),
                    "第 " + i + " 行的中点必须命中第 " + i + " 行");
        }
    }

    @Test
    void aGivenPointHitsAtMostOneRow() {
        List<MenuEntry> entries = settingsEntries();
        MenuLayout layout = MenuLayout.compute(1280, 720, entries, MenuLayout.Style.PANEL);

        for (int y = 0; y < 720; y += 3) {
            for (int x = 0; x < 1280; x += 37) {
                int hits = 0;
                for (int i = 0; i < layout.rowCount(); i++) {
                    if (layout.hitTest(i, x, y)) {
                        hits++;
                    }
                }
                assertTrue(hits <= 1,
                        "像素点 (" + x + ", " + y + ") 同时命中了 " + hits + " 行 —— 会造成一次点击触发多件事");
            }
        }
    }

    @Test
    void clicksOutsideTheColumnHitNothing() {
        MenuLayout layout = MenuLayout.compute(1280, 720, settingsEntries(), MenuLayout.Style.PANEL);
        double y = layout.rowY(5) + layout.rowHeight(5) / 2.0;

        assertEquals(-1, layout.hitTestAny(layout.columnX() - 40, y, layout.rowCount()),
                "菜单两侧的空白区域不该触发任何行");
        assertEquals(-1, layout.hitTestAny(layout.columnX() + layout.columnWidth() + 40, y,
                layout.rowCount()));
    }

    @Test
    void clicksInTheTitleAreaHitNothing() {
        MenuLayout layout = MenuLayout.compute(1280, 720, settingsEntries(), MenuLayout.Style.PANEL);

        assertEquals(-1, layout.hitTestAny(layout.columnX() + 10, layout.titleY(), layout.rowCount()));
        assertEquals(-1, layout.hitTestAny(layout.columnX() + 10, layout.subtitleY(), layout.rowCount()));
    }

    @Test
    void clicksBelowTheLastRowHitNothing() {
        MenuLayout layout = MenuLayout.compute(1280, 720, settingsEntries(), MenuLayout.Style.PANEL);
        int last = layout.rowCount() - 1;

        assertEquals(-1, layout.hitTestAny(layout.columnX() + 10,
                layout.rowY(last) + layout.rowHeight(last) + 200, layout.rowCount()));
    }

    @Test
    void outOfRangeIndicesReportNoHitInsteadOfThrowing() {
        MenuLayout layout = MenuLayout.compute(1280, 720, settingsEntries(), MenuLayout.Style.PANEL);

        assertFalse(layout.hitTest(-1, 100, 100));
        assertFalse(layout.hitTest(9999, 100, 100));
        assertTrue(layout.hitTestAny(100, 100, 0) == -1, "count = 0 时不该命中任何行");
    }

    // ============================================================ 与键盘导航的一致性

    @Test
    void theSelectedRowIsAlwaysHittableByTheMouse() {
        MenuScreen screen = Menus.settingsMenu(new GameSettings());
        MenuLayout layout = MenuLayout.compute(1280, 720, screen.entries(), MenuLayout.Style.PANEL);

        for (int step = 0; step < screen.size() * 2; step++) {
            screen.moveDown();
            int index = screen.selectedIndex();
            if (index < 0) {
                continue;
            }
            double x = layout.columnX() + layout.columnWidth() / 2.0;
            double y = layout.rowY(index) + layout.rowHeight(index) / 2.0;
            assertEquals(index, layout.hitTestAny(x, y, screen.size()),
                    "键盘选中的行必须能被鼠标在同一位置命中 —— 两套坐标若漂移，"
                            + "就会出现'键盘在第 7 行、鼠标点下去触发第 8 行'");
        }
    }

    @Test
    void hoverAtTheCenterOfARowSelectsExactlyThatRow() {
        MenuScreen screen = Menus.settingsMenu(new GameSettings());
        MenuLayout layout = MenuLayout.compute(1280, 720, screen.entries(), MenuLayout.Style.PANEL);
        int target = screen.indexOf(Menus.ID_BACK);

        int hit = layout.hitTestAny(layout.columnX() + layout.columnWidth() / 2.0,
                layout.rowY(target) + layout.rowHeight(target) / 2.0, screen.size());
        assertTrue(screen.hover(hit));

        assertEquals(target, screen.selectedIndex());
        assertEquals(Menus.ID_BACK, screen.selectedId());
    }

    // ============================================================ 封面式布局

    @Test
    void coverStylePlacesItsRowsLowerAndTallerThanPanelStyle() {
        List<MenuEntry> entries = Menus.mainMenu().entries();

        MenuLayout cover = MenuLayout.compute(1280, 720, entries, MenuLayout.Style.COVER);
        MenuLayout panel = MenuLayout.compute(1280, 720, entries, MenuLayout.Style.PANEL);

        assertTrue(cover.firstRowY() > panel.firstRowY(),
                "主菜单的行要落在画面偏下处，标题才有地方大摆");
        assertTrue(cover.rowHeight(0) > panel.rowHeight(0),
                "封面式行更高：主菜单只有三项，不需要为行数妥协");
        assertEquals(MenuLayout.Style.COVER, cover.style());
        assertEquals(MenuLayout.Style.PANEL, panel.style());
    }

    @Test
    void theMainMenuTitleIsInTheUpperAreaAndRowsBelowIt() {
        MenuLayout cover = MenuLayout.compute(1280, 720, Menus.mainMenu().entries(),
                MenuLayout.Style.COVER);

        assertTrue(cover.titleY() < cover.firstRowY(), "标题必须在菜单项上方");
        assertTrue(cover.subtitleY() > cover.titleY(), "副标题在标题下方");
        assertTrue(cover.hintY() > cover.firstRowY(), "提示行在底部");
        assertTrue(cover.versionY() > cover.hintY(), "版本行在最底部，不遮挡提示");
    }

    @Test
    void anEmptyMenuStillProducesAUsableLayout() {
        MenuLayout layout = MenuLayout.compute(1280, 720, List.of(), MenuLayout.Style.PANEL);

        assertEquals(0, layout.rowCount());
        assertEquals(-1, layout.hitTestAny(100, 100, 0));
        assertTrue(layout.columnWidth() > 0);
    }

    @Test
    void toStringNamesTheStyleForLogs() {
        String text = MenuLayout.compute(1280, 720, Menus.mainMenu().entries(),
                MenuLayout.Style.COVER).toString();

        assertTrue(text.contains("COVER"));
    }
}

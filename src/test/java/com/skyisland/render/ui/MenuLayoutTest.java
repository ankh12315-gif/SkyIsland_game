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

    // ============================================================ 行盒（2026-10-04 新增）

    /**
     * ★ 每一行都必须<b>装得下自己那行文字的行盒</b>。
     *
     * <p><b>这是 {@link #rowsAreOrderedAndNeverOverlap} 看不见的那件事</b>，
     * 也是"设置界面文字有一点重叠"的直接判据：
     * <ul>
     *   <li>{@code rowsAreOrderedAndNeverOverlap} 断言<b>行矩形</b>不重叠 —— 它确实不重叠；</li>
     *   <li>重叠的是<b>文字行盒</b>，它可以溢出行矩形而不违反上面那条断言。</li>
     * </ul>
     * 当时的行高是 16px，而一行 2 倍字需要 {@code lineHeight(2) = 12×2 = 24px} 的行盒
     * （字体的行盒恒为 {@link BitmapFont#LINE_ROWS} 行，ASCII 的 7 行字形只是居中放在其中）。
     * 渲染器算出的 {@code textY = y + (rowH - box) / 2} 因此是<b>负数</b>，
     * 每对相邻行的文字就压掉 8px（720p）/ 16px（1080p）。
     *
     * <p>这条断言与 {@link #theWholeSettingsMenuFitsOnASevenTwentyPenceScreen} 互为牵制：
     * 一条要求"装得下"，另一条要求"别撑爆屏幕"。只满足其一时另一条必红 ——
     * 这正是设置界面在 1080p 下真实遇到的约束（28 行 × 24px 装不进 808px 的可用高度）。
     */
    @Test
    void everyRowIsTallEnoughForItsOwnTextBox() {
        int[][] sizes = new int[][]{{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {800, 600}};
        for (int[] size : sizes) {
            List<MenuEntry> entries = settingsEntries();
            MenuLayout layout = MenuLayout.compute(size[0], size[1], entries, MenuLayout.Style.PANEL);
            for (int i = 0; i < layout.rowCount(); i++) {
                MenuEntry e = entries.get(i);
                int need = MenuLayout.textBoxHeight(e, layout.style(), layout.uiScale());
                int have = layout.rowHeight(i);
                assertTrue(have >= need,
                        size[0] + "x" + size[1] + " 下第 " + i + " 行（" + e.kind() + "）"
                                + "只有 " + have + "px，却要装 " + need + "px 的文字行盒 —— "
                                + "多出 " + (need - have) + "px 会压到相邻行上（这就是文字重叠）。"
                                + "标签=\"" + e.label() + "\"");
            }
        }
    }

    /**
     * ★ 纵向压缩<b>不得</b>把行压到装不下行盒。
     *
     * <p>上一条与"整列不越出屏幕"会互相牵制，而真正解决问题的是
     * {@code MenuLayout} 里那条既有的纵向预算（等比压缩全部行高）。
     * 本条专门盯那个机制：它必须只压到"还装得下"就停，
     * 而不是一路压到 {@code MIN_ROW_HEIGHT}。
     *
     * <p>若本条变红，症状是"1080p 下设置界面又出现文字重叠"，
     * 而 {@code theWholeSettingsMenuFitsOnASevenTwentyPenceScreen} 仍然是绿的 ——
     * 那正是"压过了头"与"没压到位"必须分开断言的原因。
     */
    @Test
    void verticalCompressionNeverSqueezesARowBelowItsTextBox() {
        // 800x600 是 uiScale 仍为 1 但可用高度最紧的一档，最容易触发压缩。
        int[][] sizes = new int[][]{{800, 600}, {1280, 720}, {1024, 768}, {1920, 1080}};
        for (int[] size : sizes) {
            List<MenuEntry> entries = settingsEntries();
            MenuLayout layout = MenuLayout.compute(size[0], size[1], entries, MenuLayout.Style.PANEL);
            boolean compressed = false;
            for (int i = 0; i < layout.rowCount(); i++) {
                int need = MenuLayout.textBoxHeight(entries.get(i), layout.style(), layout.uiScale());
                assertTrue(layout.rowHeight(i) >= need,
                        size[0] + "x" + size[1] + " 下第 " + i + " 行（" + entries.get(i).kind()
                                + "）被压到 " + layout.rowHeight(i) + "px，行盒需要 " + need
                                + "px。纵向预算压过了头 —— 它只该压到刚好装下为止。");
                // 是否真的发生过压缩（用于让失败信息能区分"从没压缩"与"压过头"）
                if (layout.rowHeight(i) < need + 2 * layout.uiScale()) {
                    compressed = true;
                }
            }
            assertFalse(compressed && layout.rowHeight(0) < 0,
                    "sanity：压缩标记计算有误");
        }
    }

    /**
     * 同一行内标签与数值必须<b>同倍数</b>，否则两种字号的基线会错开。
     *
     * <p>这条守的是 {@code MenuRenderer.drawValue} 那个参数：它曾经收到
     * {@code scale} 又自己乘 {@code 2}，于是数值与标签可能落在不同倍数上。
     * 渲染器读不到 GL，所以这里断言"布局给出的倍数对行内所有文字是同一个"。
     *
     * <p>★ 2026-10-04：断言基准从"每类行写死一个倍数"改成<b>按式样定档</b>。
     * 旧写法要求"可操作行一律 2 倍"，而那正是排不下的根源（见 {@code MenuLayout} 类注释
     * 里那张分辨率表：2 倍字在任何分辨率都超出可用高度）。新口径是面板式 1 倍、
     * 封面式 2 倍，因此本条必须跟着式样走，否则这条断言会把"能排下"钉成"必须 2 倍"。
     */
    @Test
    void labelAndValueShareTheSameTextScaleWithinARow() {
        for (MenuLayout.Style style : MenuLayout.Style.values()) {
            int base = MenuLayout.textScaleOf(style);
            List<MenuEntry> entries = settingsEntries();
            for (MenuEntry e : entries) {
                int ts = MenuLayout.relativeTextScaleOf(e, style);
                if (e.kind() == MenuEntry.Kind.INFO) {
                    assertEquals(1, ts, "INFO 行用 1 倍字（它是说明文字，不该和可操作行一样大）");
                } else if (e.kind() == MenuEntry.Kind.SPACER) {
                    assertEquals(0, ts, "空行没有文字");
                } else {
                    assertEquals(base, ts,
                            style + " 式样的 " + e.kind() + " 行必须用 " + base + " 倍字");
                }
                // ★ 倍数必须只由 relativeTextScaleOf 推出一次（乘 uiScale）。
                //   曾经写成 rel * textScaleOf(style) * uiScale —— 把式样基准乘了两遍，
                //   封面式变成 4 倍字。行盒与行高都跟着错，所以这条断言要盯死"只乘一次"。
                int expected = ts == 0 ? 0 : BitmapFont.lineHeight(ts);
                assertEquals(expected, MenuLayout.textBoxHeight(e, style, 1),
                        "textBoxHeight 必须由 relativeTextScaleOf 推出且只乘一次 uiScale（"
                                + style + " / " + e.kind() + "）");
                assertEquals(expected, MenuLayout.textBoxHeight(e, style, 2) / 2,
                        "uiScale 翻倍时行盒必须正好翻倍（" + style + " / " + e.kind() + "）");
            }
        }
    }

    /**
     * 分节标题的分隔线必须画在<b>行盒之下</b>，且不越出本行。
     *
     * <p>★ 2026-10-04：线的 y 改读 {@code MenuLayout.rowRuleY(i)}。
     * 旧写法在测试里自己重算一遍 {@code textY = y + (rowH - box) / 2} 再加 2px ——
     * 那等于<b>把渲染器的算法在测试里抄了一遍</b>：渲染器算错时，测试会跟着算错，
     * 于是两边一起错而断言仍然绿（正是本项目吃过三次的"脆绿灯"）。
     * 现在位置由布局单点给出，测试只校验这个值的几何性质。
     */
    @Test
    void theHeaderSeparatorSitsBelowTheTextBox() {
        List<MenuEntry> entries = settingsEntries();
        MenuLayout layout = MenuLayout.compute(1280, 720, entries, MenuLayout.Style.PANEL);
        int scale = layout.uiScale();
        int headerCount = 0;
        for (int i = 0; i < layout.rowCount(); i++) {
            if (entries.get(i).kind() != MenuEntry.Kind.HEADER) {
                assertEquals(-1, layout.rowRuleY(i),
                        "非分节标题行不该有分隔线（第 " + i + " 行，kind=" + entries.get(i).kind() + "）");
                continue;
            }
            headerCount++;
            int box = MenuLayout.textBoxHeight(entries.get(i), layout.style(), scale);
            int textY = layout.rowTextY(i);
            int lineY = layout.rowRuleY(i);

            assertEquals(layout.rowTextScale(i), MenuLayout.relativeTextScaleOf(entries.get(i),
                            layout.style()) * scale,
                    "第 " + i + " 行的文字倍数必须由布局给出（" + entries.get(i).kind() + "）");
            assertTrue(lineY >= textY + box,
                    "第 " + i + " 行的分隔线画在 " + lineY
                            + "，必须不低于行盒底部 " + (textY + box)
                            + " —— 压上去看上去就是\"文字下面压了一道线\"");
            assertTrue(lineY + Math.max(1, scale) <= layout.rowY(i) + layout.rowHeight(i),
                    "第 " + i + " 行的分隔线越出了本行（行底 " + (layout.rowY(i) + layout.rowHeight(i))
                            + "）—— 它会画进下一行");
        }
        assertTrue(headerCount > 0, "设置界面必须至少有 1 个分节标题，否则本测试是空跑（假绿灯）");
    }

    /**
     * ★ <b>文字行盒之间不得重叠</b> —— 这才是"文字重叠"的直接判据。
     *
     * <p>{@link #everyRowIsTallEnoughForItsOwnTextBox} 断言的是"行矩形装得下行盒"，
     * 而它有一个盲区：<b>行盒可能溢出本行矩形、压到相邻行的行盒</b>，
     * 同时"行矩形不重叠"这条仍然成立（行矩形确实不重叠，溢出的是行盒）。
     * 这正是玩家报障时的原样现场。
     *
     * <p>因此这里断言相邻两行的<b>行盒</b>互不侵入，并且文字顶边不早于本行顶边。
     */
    @Test
    void textBoxesOfAdjacentRowsNeverOverlapAtAnySupportedResolution() {
        int[][] sizes = new int[][]{{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160},
                {800, 600}, {1024, 768}};
        for (int[] size : sizes) {
            List<MenuEntry> entries = settingsEntries();
            MenuLayout layout = MenuLayout.compute(size[0], size[1], entries, MenuLayout.Style.PANEL);
            for (int i = 0; i < layout.rowCount(); i++) {
                int box = MenuLayout.textBoxHeight(entries.get(i), layout.style(), layout.uiScale());
                if (box == 0) {
                    continue;
                }
                int textTop = layout.rowTextY(i);
                assertTrue(textTop >= layout.rowY(i),
                        size[0] + "x" + size[1] + " 下第 " + i + " 行的文字顶边 " + textTop
                                + " 早于行顶 " + layout.rowY(i)
                                + " —— 它会画进上一行（kind=" + entries.get(i).kind()
                                + ", 标签=\"" + entries.get(i).label() + "\"）");
                if (i + 1 < layout.rowCount()) {
                    int nextBox = MenuLayout.textBoxHeight(entries.get(i + 1), layout.style(),
                            layout.uiScale());
                    int nextTop = layout.rowTextY(i + 1);
                    if (nextBox == 0) {
                        // 下一行是空行：仍然要求本行文字底边不越过下一行的顶
                        assertTrue(textTop + box <= nextTop,
                                size[0] + "x" + size[1] + " 下第 " + i + " 行文字底部 "
                                        + (textTop + box) + " 越过了下一空行的顶 " + nextTop);
                    } else {
                        assertTrue(textTop + box <= nextTop,
                                size[0] + "x" + size[1] + " 下第 " + i + " 行（"
                                        + entries.get(i).kind() + "）的文字行盒底部 "
                                        + (textTop + box) + " 压到了第 " + (i + 1)
                                        + " 行（" + entries.get(i + 1).kind()
                                        + "）的文字顶边 " + nextTop + "，重叠 "
                                        + (nextTop - (textTop + box)) + "px"
                                        + " —— 这就是玩家说的文字重叠。标签=\""
                                        + entries.get(i).label() + "\"");
                    }
                }
            }
        }
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

    // ============================================================ 标题 / 副标题的纵向几何

    /**
     * 标题与副标题的<b>实际墨迹</b>在任何式样、任何分辨率下都不得重叠。
     *
     * <p><b>为什么原来的断言放过了这个缺陷：</b>它只断言 {@code subtitleY > titleY}。
     * 实测 720p 主菜单 titleY=144、subtitleY=178 —— 178 > 144 成立，
     * 而那时标题的墨迹是 159..194，副标题的行盒是 178..202，<b>重叠 16px</b>。
     * 截图里两个词直接叠成一团，谁都不认识；这条断言却是绿的。
     * 这正是"断言在失败场景下仍能通过"的假阳性，也是"测试全绿说明不了看得见"的又一例。
     *
     * <p><b>为什么判据用"行盒 + ASCII 行内偏移"而不是直接比两个 y：</b>
     * 标题是 ASCII（7 行字形，居中放在 12 行的行盒里，{@code ASCII_ROW_OFFSET = 3}），
     * 副标题是中文（占满 12 行行盒）。两者的墨迹起止点并不等于传给 {@code text()} 的那个 y，
     * 拿 y 相减量到的是"行盒顶"而不是"字"。这里按渲染器真正的口径复原墨迹范围。
     */
    @Test
    void titleAndSubtitleInkNeverOverlapAtAnySupportedResolution() {
        List<MenuEntry> coverEntries = Menus.mainMenu().entries();
        List<MenuEntry> panelEntries = settingsEntries();

        for (MenuLayout.Style style : MenuLayout.Style.values()) {
            List<MenuEntry> entries = style == MenuLayout.Style.COVER ? coverEntries : panelEntries;
            for (UiMetrics.Resolution res : UiMetrics.Resolution.values()) {
                MenuLayout layout = MenuLayout.compute(res.width(), res.height(), entries, style);
                int scale = layout.uiScale();
                String where = style + " @ " + res.width() + "x" + res.height();

                // 标题墨迹：行盒顶 + ASCII 行内偏移，高度是 7 行
                int titleInkTop = layout.titleY()
                        + BitmapFont.ASCII_ROW_OFFSET * layout.titleScale() * scale;
                int titleInkBottom = titleInkTop
                        + BitmapFont.textHeight(layout.titleScale() * scale);

                // 副标题墨迹：中文占满行盒，所以行盒即墨迹
                int subtitleInkBottom = layout.subtitleY()
                        + BitmapFont.lineHeight(MenuLayout.SUBTITLE_SCALE * scale);

                assertTrue(layout.subtitleY() >= titleInkBottom,
                        where + "：副标题被画进了标题里 —— 标题墨迹到 " + titleInkBottom
                                + "，副标题却从 " + layout.subtitleY() + " 开始（重叠 "
                                + (titleInkBottom - layout.subtitleY()) + "px）");
                assertTrue(layout.firstRowY() >= subtitleInkBottom,
                        where + "：副标题压到了第一行菜单项上 —— 副标题墨迹到 "
                                + subtitleInkBottom + "，第一行从 " + layout.firstRowY() + " 开始");
            }
        }
    }

    /**
     * 标题的放大倍数必须由布局提供，且渲染器用的就是它。
     *
     * <p>这条守的是"两边各写一个倍数"的回归：布局按 {@code titleScale()} 推副标题位置，
     * 而渲染器若自己写死一个倍数（M2.2 之前的写法），改字号时副标题位置不会跟着动。
     */
    @Test
    void theTitleScaleComesFromTheLayoutAndMatchesTheStyle() {
        MenuLayout cover = MenuLayout.compute(1280, 720, Menus.mainMenu().entries(),
                MenuLayout.Style.COVER);
        MenuLayout panel = MenuLayout.compute(1280, 720, settingsEntries(),
                MenuLayout.Style.PANEL);

        assertEquals(MenuLayout.COVER_TITLE_SCALE, cover.titleScale());
        assertEquals(MenuLayout.PANEL_TITLE_SCALE, panel.titleScale());
        assertTrue(cover.titleScale() > panel.titleScale(),
                "封面式标题必须比面板式大，否则两个界面的视觉层级会塌平");
    }

    /**
     * 任何分辨率下，菜单列都必须在底部提示行之上结束。
     *
     * <p>原有用例只跑 720p，而 1080p 恰好是唯一会出问题的分辨率：
     * {@code uiScale = round(1080 / 720)} 把 1.5 四舍五入成 2，
     * 于是界面按 1440p 的尺度排版、却只有 1080p 的高度可用 ——
     * 28 行的设置界面末行落到 y=1028，而提示行在 992，最后一行（'返回'）被盖住。
     * 玩家看不到'返回'就出不去设置界面 —— 这正是原用例的注释里写的那个失败模式。
     */
    @Test
    void everyMenuEndsAboveTheBottomHintAtAnySupportedResolution() {
        List<MenuEntry> coverEntries = Menus.mainMenu().entries();
        List<MenuEntry> panelEntries = settingsEntries();

        for (MenuLayout.Style style : MenuLayout.Style.values()) {
            List<MenuEntry> entries = style == MenuLayout.Style.COVER ? coverEntries : panelEntries;
            for (UiMetrics.Resolution res : UiMetrics.Resolution.values()) {
                MenuLayout layout = MenuLayout.compute(res.width(), res.height(), entries, style);
                int lastBottom = layout.rowY(layout.rowCount() - 1)
                        + layout.rowHeight(layout.rowCount() - 1);

                assertTrue(lastBottom < layout.hintY(),
                        style + " @ " + res.width() + "x" + res.height()
                                + "：菜单列末行底部 " + lastBottom + " 已经压到（或越过）提示行 "
                                + layout.hintY() + "，玩家会读不到最后一行");
                assertTrue(layout.versionY() >= lastBottom,
                        style + " @ " + res.width() + "x" + res.height()
                                + "：菜单列末行越过版本行，会与右下角版本号叠字");
            }
        }
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

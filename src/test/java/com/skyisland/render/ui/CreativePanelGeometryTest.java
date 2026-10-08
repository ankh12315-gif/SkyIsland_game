package com.skyisland.render.ui;

import com.skyisland.world.block.BlockCategory;
import com.skyisland.world.block.BlockRegistry;
import com.skyisland.world.block.CreativePalette;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ M4-S7 创造面板的<b>几何与命中</b>护栏（接线层的行为一半）。
 *
 * <h2>与 {@link CreativePanelWiringTest} 的分工</h2>
 * 同一条不变式的两个方向：
 * <ul>
 *   <li>{@code CreativePanelWiringTest} 钉「接线<b>存在</b>」——
 *       门控读的是哪个字段、点击会不会穿透、取出落在哪。</li>
 *   <li>本类钉「接上之后<b>算得对</b>」—— 三种分辨率下格子不重叠、
 *       不出面板、命中与绘制同源、切标签时内容区纹丝不动。</li>
 * </ul>
 * 少任何一半都会漏：只有扫描守卫，接线可以在（几何错的）状态下全绿；
 * 只有行为断言，18 条内容断言全绿而 UI 根本没接线（S6 撞过的那一形状）。
 *
 * <h2>为什么必须遍历三种分辨率</h2>
 * 与 {@code InventoryLayoutTest} 同一理由：{@code uiScale} 会让槽位、间隙、
 * 标签条、分类标题一起翻倍。任何写死的像素常量都会在翻倍时露出破绽。
 */
class CreativePanelGeometryTest {

    private static final CreativePalette PALETTE = CreativePalette.build();

    /** 面板视图（一次建好，列数与布局常量一致）。 */
    private static CreativePalette.CreativeView view() {
        return PALETTE.view(InventoryLayout.CREATIVE_COLUMNS);
    }

    private static InventoryLayout creativeLayout(int w, int h) {
        return InventoryLayout.compute(w, h, 0, 2, view().rows());
    }

    // ============================================================ 格子几何

    /**
     * ★ 每一格都必须在面板内（不出框、不越界）。
     *
     * <p>出框的症状在 1080p 最严重（{@code uiScale=2} 使所有尺寸翻倍）。
     * 「出框」是玩家唯一能看见的失败：面板右下角有一格被切掉一半。
     */
    @Test
    void everyEntrySitsInsideThePanelAtEveryResolution() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout layout = creativeLayout(r.width(), r.height());
            for (int i = 0; i < layout.creative().entryCount(); i++) {
                int x = layout.creative().entryX(i, layout.creativeGridX());
                int y = layout.creative().entryY(i);
                int s = layout.creative().slotSize();
                assertTrue(x >= layout.panelX() && x + s <= layout.panelX() + layout.panelWidth(),
                        "第 " + i + " 格横向出框（" + r + "）：x=" + x
                                + " 面板=[" + layout.panelX() + ","
                                + (layout.panelX() + layout.panelWidth()) + "]");
                assertTrue(y >= layout.panelY() && y + s <= layout.panelY() + layout.panelHeight(),
                        "第 " + i + " 格纵向出框（" + r + "）：y=" + y
                                + " 面板=[" + layout.panelY() + ","
                                + (layout.panelY() + layout.panelHeight()) + "]");
            }
        }
    }

    /**
     * ★ 任意两格不重叠。
     *
     * <p>重叠的症状是"两格画在同一个位置，看起来像一格"——
     * 玩家点其中一格，取出来的却是另一格，而界面上没有任何异常。
     */
    @Test
    void noTwoEntriesOverlapAtAnyResolution() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout layout = creativeLayout(r.width(), r.height());
            InventoryLayout.CreativeGridGeometry g = layout.creative();
            for (int a = 0; a < g.entryCount(); a++) {
                for (int b = a + 1; b < g.entryCount(); b++) {
                    boolean disjoint = g.entryY(a) + g.slotSize() <= g.entryY(b)
                            || g.entryY(b) + g.slotSize() <= g.entryY(a)
                            || g.entryX(a, layout.creativeGridX()) + g.slotSize()
                            <= g.entryX(b, layout.creativeGridX())
                            || g.entryX(b, layout.creativeGridX()) + g.slotSize()
                            <= g.entryX(a, layout.creativeGridX());
                    assertTrue(disjoint, "第 " + a + " 格与第 " + b + " 格重叠（" + r + "）");
                }
            }
        }
    }

    // ============================================================ 命中与绘制同源

    /**
     * ★ 「命中每格的正中心」必须命中<b>那一格本身</b>。
     *
     * <p>这就是"点第 N 格取出来的就是第 N 格"的可执行形式。
     * 断言写成"逐格遍历它自己的中心"而不是"试几个固定点"——
     * 后者会在某些列上恰好通过，而错位往往只在靠后的列上出现。
     */
    @Test
    void hittingTheCentreOfEveryEntryHitsThatEntry() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout layout = creativeLayout(r.width(), r.height());
            for (int i = 0; i < layout.creative().entryCount(); i++) {
                int cx = layout.creative().entryX(i, layout.creativeGridX())
                        + layout.creative().slotSize() / 2;
                int cy = layout.creative().entryY(i) + layout.creative().slotSize() / 2;
                assertEquals(i, layout.hitTestCreativeAny(cx, cy),
                        "第 " + i + " 格的正中心命中了别的格（" + r + "）");
            }
        }
    }

    /** 面板外的点必须命中 -1（否则点在面板空白处会取出一格方块）。 */
    @Test
    void pointsOutsideThePanelHitNothing() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout layout = creativeLayout(r.width(), r.height());
            assertEquals(-1, layout.hitTestCreativeAny(2, 2), "左上角（" + r + "）不应命中任何格");
            assertEquals(-1, layout.hitTestCreativeAny(r.width() - 2, r.height() - 2),
                    "右下角（" + r + "）不应命中任何格");
        }
    }

    /**
     * ★ 标签条区域<b>不</b>命中任何创造格。
     *
     * <p>它俩在几何上不重叠，而"不重叠"必须被断言 ——
     * 一旦布局改动让标签条压到内容区上，症状是"点标签切页的同时取出一格方块"。
     */
    @Test
    void theTabStripNeverOverlapsTheContentArea() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout layout = creativeLayout(r.width(), r.height());
            int stripBottom = layout.tab().y() + layout.tab().height();
            int firstEntryTop = layout.creative().rowTop(0);
            assertTrue(stripBottom <= firstEntryTop,
                    "标签条压到了内容区（" + r + "）：stripBottom=" + stripBottom
                            + " firstEntryTop=" + firstEntryTop);
        }
    }

    // ============================================================ 切标签时几何稳定

    /**
     * ★ 从「背包」切到「创造」，面板高度与内容区起点必须<b>不变</b>。
     *
     * <p>这是"标签页"与"两个并列面板"的分界：切页时底板在动画中途变高
     * 是最容易看出来也最刺眼的 UI 缺陷。
     * <p>判据用<b>同一份 {@code rows} 两次 compute</b>——
     * 真正的风险是"渲染层按 activeTab 决定传不传 rows"，
     * 那会让第二次 compute 拿到 null。本类钉的是布局层的承诺：
     * 只要 rows 一样，高度就一样。
     */
    @Test
    void switchingTabsDoesNotChangeThePanelHeight() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout withRows = InventoryLayout.compute(r.width(), r.height(), 0, 2, view().rows());
            InventoryLayout again = InventoryLayout.compute(r.width(), r.height(), 0, 2, view().rows());
            assertEquals(withRows.panelHeight(), again.panelHeight(),
                    "同样输入两次 compute 得到不同面板高度（" + r + "）：compute 不是纯函数");
        }
    }

    /**
     * ★ 面板高度必须<b>容得下创造区</b>（取两区高者），而不是"两页各自算各自的"。
     *
     * <p>★ 这里的判据<b>不是</b>"背包页与创造页高度相同"——
     * 那是错的：{@code compute} 只算一次几何，两页共用同一份，
     * 所以"高度相同"这句话在布局层是<b>恒真</b>的，写成断言等于没断言。
     * 真正要钉的是<b>方向</b>：有创造区时面板不能比"只有背包"更矮。
     * 反例的症状是切到创造页时最后一行被面板底边裁掉（或算出屏幕外）。
     */
    @Test
    void thePanelIsTallEnoughForTheCreativeArea() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout creative = creativeLayout(r.width(), r.height());
            int backpackOnly = InventoryLayout.compute(r.width(), r.height(), 0, 2, null).panelHeight();
            assertTrue(creative.panelHeight() >= backpackOnly,
                    "有创造区时面板比「只有背包」更矮（" + r + "）："
                            + creative.panelHeight() + " < " + backpackOnly
                            + "；切到创造页时最后一行会被裁掉");
        }
    }

    // ============================================================ 生存模式几何不变

    /**
     * ★ 生存模式（{@code tabs == 1}、无 rows）的几何必须与 M2.2 完全一致。
     *
     * <p>这是"没有破坏既有玩法"的<b>可执行</b>形式：S7 加了标签条与创造面板，
     * 若它悄悄改了生存模式的背包尺寸，那是一种没人能一眼看出的回归。
     */
    @Test
    void survivalModeGeometryIsUnchangedByThisFeature() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout threeArg = InventoryLayout.compute(r.width(), r.height(), 3);
            InventoryLayout fiveArg = InventoryLayout.compute(r.width(), r.height(), 3, 1, null);
            assertEquals(threeArg.panelX(), fiveArg.panelX(), "panelX 变了（" + r + "）");
            assertEquals(threeArg.panelY(), fiveArg.panelY(), "panelY 变了（" + r + "）");
            assertEquals(threeArg.panelWidth(), fiveArg.panelWidth(), "panelWidth 变了（" + r + "）");
            assertEquals(threeArg.panelHeight(), fiveArg.panelHeight(), "panelHeight 变了（" + r + "）");
            assertEquals(threeArg.separatorY(), fiveArg.separatorY(), "separatorY 变了（" + r + "）");
            for (int i = 0; i < 36; i++) {
                assertEquals(threeArg.slotX(i), fiveArg.slotX(i), "第 " + i + " 格 x 变了");
                assertEquals(threeArg.slotY(i), fiveArg.slotY(i), "第 " + i + " 格 y 变了");
            }
        }
    }

    /** 生存模式下 {@code tabs == 1} ⇒ 标签条不占任何高度（height 为 0）。 */
    @Test
    void noTabStripMeansNoVerticalSpaceIsTaken() {
        InventoryLayout layout = InventoryLayout.compute(1280, 720, 3, 1, null);
        assertEquals(0, layout.tab().height(), "tabs==1 时标签条仍占高度：生存模式画面会多出一条空白");
        assertEquals(-1, layout.hitTestTabAny(640, layout.tab().y() + 1),
                "tabs==1 时标签条仍可命中：点内容区会被当成点标签");
    }

    // ============================================================ 行结构

    /**
     * ★ 行结构必须自洽（{@code verifySelfConsistent} 不是装饰）。
     *
     * <p>它由 {@code compute} 在算几何<b>之前</b>调用 ——
     * 否则越界要到渲染阶段才炸，栈里全是渲染代码，与真因（内容侧计数错了）无关。
     *
     * <p>★ 这里刻意<b>不</b>写「行数 == 条目数 + 标题数」：
     * 那个等式只在"每行一格"时成立，而 9 列的面板一行有 9 格 ——
     * 写下来它会在真实布局上假红，而真因（我以为一行一格）离症状很远。
     * 正确的恒等式是「行数 == <b>有格子的行数</b> + 标题行数」。
     */
    @Test
    void theRowStructureIsSelfConsistent() {
        InventoryLayout.CreativeRows rows = view().rows();
        rows.verifySelfConsistent();
        assertEquals(PALETTE.size(), rows.entryCount(), "行结构条目数与面板条目数不等");

        int rowsCarryingEntries = 0;
        boolean[] seen = new boolean[rows.rowCount()];
        for (int i = 0; i < rows.entryCount(); i++) {
            int r = rows.rowOf(i);
            if (!seen[r]) {
                seen[r] = true;
                rowsCarryingEntries++;
            }
        }
        assertEquals(rows.rowCount(), rowsCarryingEntries + countGroupTitles(rows),
                "行数 " + rows.rowCount() + " != 有格子的行数 " + rowsCarryingEntries
                        + " + 标题行数 " + countGroupTitles(rows)
                        + "；多出来的行是空行（面板里会有一条没有内容的带子）");
    }

    private static int countGroupTitles(InventoryLayout.CreativeRows rows) {
        int n = 0;
        for (int r = 0; r < rows.rowCount(); r++) {
            if (rows.rowIsGroupTitle(r)) {
                n++;
            }
        }
        return n;
    }

    /** 恰好四个分类标题行（PRD 5.1：自然 / 建材 / 矿物 / 作物）。 */
    @Test
    void thereIsExactlyOneGroupTitleRowPerPrdCategory() {
        InventoryLayout.CreativeRows rows = view().rows();
        assertEquals(BlockCategory.values().length, countGroupTitles(rows),
                "分类标题行数与 BlockCategory 的取值数不等（PRD 5.1 恰好四类）");
    }

    /**
     * ★ 分类标题行与方块行<b>不共用行</b>。
     *
     * <p>共用的症状："建材"的方块紧贴在"自然"的标题下面，读起来像自然的一部分。
     */
    @Test
    void aGroupTitleRowNeverAlsoCarriesEntries() {
        InventoryLayout.CreativeRows rows = view().rows();
        for (int r = 0; r < rows.rowCount(); r++) {
            if (!rows.rowIsGroupTitle(r)) {
                continue;
            }
            for (int i = 0; i < rows.entryCount(); i++) {
                assertNotEquals(r, rows.rowOf(i),
                        "第 " + r + " 行既是分类标题又放了第 " + i + " 格：两类内容挤在一行");
            }
        }
    }

    /**
     * ★ 同一行内条目数不超过列数（不会"溢出到看不见的地方"）。
     *
     * <p>溢出时第 10 格会画到面板外——而 {@code entryX} 用 {@code columnOf = i % 9}
     * 会把它送回第 1 列，于是"两格叠在一起"。
     */
    @Test
    void noRowCarriesMoreEntriesThanThereAreColumns() {
        InventoryLayout.CreativeRows rows = view().rows();
        int[] perRow = new int[rows.rowCount()];
        for (int i = 0; i < rows.entryCount(); i++) {
            perRow[rows.rowOf(i)]++;
        }
        for (int r = 0; r < rows.rowCount(); r++) {
            assertTrue(perRow[r] <= InventoryLayout.CREATIVE_COLUMNS,
                    "第 " + r + " 行有 " + perRow[r] + " 格，超过 " + InventoryLayout.CREATIVE_COLUMNS
                            + " 列：多出来的会画到面板外或叠到第一列上");
        }
    }

    // ============================================================ 视图与内容一致

    /** 视图的条目集合必须恰好等于面板条目集合（不多不少不重）。 */
    @Test
    void theViewContainsExactlyThePaletteEntries() {
        CreativePalette.CreativeView v = view();
        assertEquals(PALETTE.size(), v.ordered().size(), "视图条目数与面板不等");
        Set<String> ids = new HashSet<>();
        for (CreativePalette.Entry e : v.ordered()) {
            assertTrue(ids.add(e.block().id()), "视图里出现重复条目：" + e.block().id());
        }
        for (CreativePalette.Entry e : PALETTE.entries()) {
            assertTrue(ids.contains(e.block().id()), "视图漏了条目：" + e.block().id());
        }
    }

    /**
     * ★ 视图里<b>不得</b>出现资源核心（PRD §5.1 硬约束）。
     *
     * <p>这里是对 {@code CreativePaletteTest} 的<b>补充</b>而非重复：
     * 那条钉的是「面板不含它」，本条钉的是「<b>排版之后</b>的视图也不含它」——
     * 切段/排序是另一段代码，它同样可能把不该出现的条目带进来。
     */
    @Test
    void theSortedViewStillExcludesTheResourceCore() {
        for (CreativePalette.Entry e : view().ordered()) {
            assertNotEquals("skyisland:resource_core", e.block().id(),
                    "创造面板视图里出现了资源核心：那是用 UI 绕过 PRD 5.1.1 的硬约束");
        }
    }

    /** 视图的行分类表长度必须与行数一致（渲染层按它给标题行取名字）。 */
    @Test
    void theRowCategoryTableMatchesTheRowCount() {
        CreativePalette.CreativeView v = view();
        assertEquals(v.rows().rowCount(), v.rowCategory().size(),
                "分类表长度与行数不等：渲染层给标题行取名字会越界或漏名");
    }

    // ============================================================ 列数契约

    /**
     * ★ 面板列数与背包一致，且<b>每列的 x 与背包同列的 x 相同</b>。
     *
     * <p>PRD §5.1 只说"列出全部 20 种"，没说几列。
     * 取 9 列的判据是<b>肌肉记忆</b>：玩家在 9 列的背包里形成的
     * "第 3 格在第三格上面"的空间预期，在 8 列面板上会整体错位。
     *
     * <p>★ 判据写成"逐列与背包同列对齐"而不是"面板右边界 == 背包右边界"：
     * 后者只在<b>最后一行恰好排满</b>时成立，而 20 格在 9 列下最后一行只有 2 格 ——
     * 那样的断言会在某一版方块数（19 或 27）下假红，而它本来想防的是"左右不对齐"。
     */
    @Test
    void everyPanelColumnLinesUpWithTheSameBackpackColumn() {
        assertEquals(InventoryLayout.COLUMNS, InventoryLayout.CREATIVE_COLUMNS,
                "创造面板列数与背包不一致：玩家的空间预期会整体错位");
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout layout = creativeLayout(r.width(), r.height());
            for (int i = 0; i < layout.creative().entryCount(); i++) {
                int column = layout.creative().columnOf(i);
                assertEquals(layout.slotX(column), layout.creative().entryX(i, layout.creativeGridX()),
                        "第 " + column + " 列的面板格与背包同列不对齐（" + r + "）："
                                + "切换标签时内容会横跳");
            }
        }
    }

    /** 20 格在 9 列下是 3 行（2 行 9 格 + 1 行 2 格）—— 逐类核对行数。 */
    @Test
    void eachGroupsRowCountIsItsCeilOverColumns() {
        CreativePalette.CreativeView v = view();
        int columns = InventoryLayout.CREATIVE_COLUMNS;
        for (BlockCategory category : BlockCategory.values()) {
            int n = 0;
            for (CreativePalette.Entry e : v.ordered()) {
                if (e.category() == category) {
                    n++;
                }
            }
            int expected = (n + columns - 1) / columns;
            int actual = 0;
            Set<Integer> rows = new HashSet<>();
            for (int i = 0; i < v.ordered().size(); i++) {
                if (v.at(i).category() == category) {
                    rows.add(v.rows().rowOf(i));
                }
            }
            actual = rows.size();
            assertEquals(expected, actual,
                    "分组 " + category + " 有 " + n + " 格，" + columns + " 列下应是 "
                            + expected + " 行，实际 " + actual + " 行");
        }
    }

    // ============================================================ 门控输入的边界

    /** 越界的标签页序号必须返回 {@code null}（而不是抛异常或回绕到 0）。 */
    @Test
    void anOutOfRangeTabIndexYieldsNull() {
        assertNotNull(InventoryRenderModel.Tab.of(0));
        assertNotNull(InventoryRenderModel.Tab.of(1));
        assertEquals(null, InventoryRenderModel.Tab.of(-1));
        assertEquals(null, InventoryRenderModel.Tab.of(2));
    }

    /** 模型的 {@code creativePanelActive()} 必须在「无视图」时为假。 */
    @Test
    void theCreativePanelIsNotActiveWithoutAView() {
        InventoryRenderModel m = new InventoryRenderModel();
        m.activeTab = InventoryRenderModel.Tab.CREATIVE;
        assertFalse(m.creativePanelActive(), "没有视图却报创造面板激活：会画出空白内容区");

        CreativePalette.CreativeView v = view();
        m.creativeView = v;
        assertTrue(m.creativePanelActive(), "有视图且在创造页却报未激活：面板画不出来");

        m.activeTab = InventoryRenderModel.Tab.BACKPACK;
        assertFalse(m.creativePanelActive(), "在背包页却报创造面板激活：两个内容区会同时画");
    }

    /** 面板条目数必须恒等于 PRD 口径（与注册表对齐，防止悄悄漂移）。 */
    @Test
    void thePanelCountStillMatchesTheRegistryContract() {
        assertEquals(BlockRegistry.playerBlockCount(), PALETTE.size(),
                "面板条目数与 playerBlockCount() 不等："
                        + "两者若各用一套判据，就会出现「面板 19 格 / 口径 20 种」");
    }

    /** 视图必须是可复用的（同一份引用被布局缓存判据依赖）。 */
    @Test
    void theViewIsImmutableSoTheLayoutCacheCanKeyOnIt() {
        CreativePalette.CreativeView v = view();
        List<CreativePalette.Entry> ordered = v.ordered();
        try {
            ordered.clear();
            org.junit.jupiter.api.Assertions.fail("视图的条目列表可被修改："
                    + "布局缓存以它为判据，可变内容会让缓存命中一个已经过期的几何");
        } catch (UnsupportedOperationException expected) {
            // 正是我们要的
        }
    }
}

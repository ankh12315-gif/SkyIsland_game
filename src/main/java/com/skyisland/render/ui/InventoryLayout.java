package com.skyisland.render.ui;

import com.skyisland.player.Inventory;

/**
 * 背包界面的<b>几何布局</b>：36 个格子各画在哪里、哪个格子被点中了。
 *
 * <h2>为什么它必须是一个纯函数</h2>
 * 与 {@link MenuLayout} 同一个理由，只是后果更严重：背包要支持<b>拖放</b>，
 * 于是"鼠标落在第几格"这个判定每帧都在跑，而且绘制与命中必须指向<u>同一格</u>。
 * 两套坐标一旦漂移，症状是"点了第 3 格，动的是第 12 格" ——
 * 在 36 格里这种错位肉眼很难立刻看出来，但玩家会觉得"这个背包是坏的"。
 * 本类让命中判定与绘制读同一份结果，把这类 bug 从"迟早发生"变成"不可能"。
 *
 * <h2>槽位编号契约</h2>
 * 与 {@link Inventory} 完全一致：绝对索引 {@code 0..26} = 主背包（3 行 × 9），
 * {@code 27..35} = 快捷栏（第 4 行）。本类<b>不</b>自己定义这个划分 ——
 * 它从 {@link Inventory#MAIN_SIZE} / {@link Inventory#HOTBAR_OFFSET} 读，
 * 这样"扩容"只需要改一处。
 *
 * <h2>为什么不在本类里碰 GL</h2>
 * 它是纯计算，因此"面板在 2560×1440 下会不会画出屏幕外"这类问题可以单元测试，
 * 不需要真的开一个窗口。绘制在 {@link InventoryRenderer}。
 */
public final class InventoryLayout {

    /** 每行 9 格（与快捷栏宽度一致，玩家从快捷栏迁移过来的肌肉记忆才不会失效）。 */
    public static final int COLUMNS = 9;

    /** 主背包与快捷栏之间的分隔带高度（基准像素）。 */
    private static final int SEPARATOR_HEIGHT = 6;

    /**
     * 右侧合成栏与背包格子之间的间隔（基准像素）。
     *
     * <p>它比槽位间隙（2）大得多是刻意的：两侧是<b>两个不同性质的区域</b>
     * （"我有什么" vs "我能做什么"），而不是同一片网格里相邻的两格。
     * 留一条明显的空白带，玩家才不会把配方行当成背包第 10 列。
     */
    private static final int CRAFT_COLUMN_GAP = 14;

    /**
     * 合成栏的宽度（基准像素）。
     *
     * <p><b>它是按"最长那一行的文字"反推的，不是随手给的数。</b>
     * 最长的一行是 R16 步枪：产物名 + 五项材料，形如
     * {@code 步枪 ×1  铁锭 8/8 铜锭 3/3 晶体 1/1 火药 6/6 木棍 2/2 [合成]}。
     * 中文按 12 px/字、ASCII 按 6 px/字估（见 {@link BitmapFont}），
     * 约 16 个汉字 + 30 个半角字符 ≈ 372 px，取 380 留一点余量。
     *
     * <p>这个值只在 1280×720 给过一次校准（见 {@code InventoryLayoutTest} 里
     * "最长那一行必须放得进合成栏"那条断言）—— 若将来加更长的配方，
     * 那条断言会先变红，提醒这里要改，而不是让文字被画到面板外面去。
     */
    private static final int CRAFT_COLUMN_WIDTH = 380;

    /** 一行的高度（基准像素）：图标 20 + 上下各留 2，与槽位同一套节奏。 */
    private static final int CRAFT_ROW_HEIGHT = 24;

    /** 合成栏标题的占位（基准像素；与面板标题同为 LABEL_SCALE 的中文行盒）。 */
    private static final int CRAFT_TITLE_HEIGHT = 24;

    /**
     * ★ M4-S7：标签页条的高度（基准像素）。
     *
     * <p>PRD §5.1「打开 = 背包界面（E）内的独立标签页『创造』」。
     * 它是<b>标签</b>而不是又一个右侧栏，原因有两条：
     * <ol>
     *   <li>右侧已被合成栏占了（{@link #CRAFT_COLUMN_GAP} 明确说"两侧是两个不同性质的区域"）；</li>
     *   <li>创造模式<b>不给枪</b>（PRD §5.6）⇒ 创造模式没有合成需求，
     *       两个右栏会同时出现但其中一个必然永远是空的。</li>
     * </ol>
     * 而"两个内容区在同一个包里切换"正是标签页的定义。
     */
    private static final int TAB_STRIP_HEIGHT = 22;

    /** ★ M4-S7：创造面板的列数（与背包格子同宽，保持肌肉记忆一致）。 */
    public static final int CREATIVE_COLUMNS = COLUMNS;

    /**
     * ★ M4-S7：一个分类标题行的高度（基准像素）。
     *
     * <p>它比槽位高：分类标题是<b>分组名</b>（自然 / 建材 / 矿物 / 作物），
     * 与具体方块不是同一级的东西，压到同高会让"这一格属于哪组"读不出来。
     */
    private static final int CREATIVE_GROUP_HEIGHT = 20;

    private final int fbWidth;
    private final int fbHeight;
    private final int uiScale;
    private final int slotSize;
    private final int gap;
    private final int pad;

    private final int panelX;
    private final int panelY;
    private final int panelWidth;
    private final int panelHeight;
    private final int titleY;
    private final int firstRowY;
    private final int hotbarRowY;
    private final int separatorY;

    // ---- 右侧合成栏（2026-10-03）----
    private final int craftX;
    private final int craftWidth;
    private final int craftTitleY;
    private final int craftFirstRowY;
    private final int craftRowHeight;
    private final int craftRowCount;

    // ---- M4-S7：标签页与创造面板 ----
    // ★ 为什么收成 record 而不是继续加 int：构造器已经 20 个参数了，
    //   再加 3 个 int 会让"第 7 个参数到底是 tabY 还是 groupH"这种问题
    //   只能靠数位置回答 —— 那正是本项目反复付过学费的"读代码才知道它存在"。
    private final TabGeometry tab;
    private final CreativeGridGeometry creative;

    /**
     * ★ M4-S7 标签页条的几何。
     *
     * @param y       标签页条顶边
     * @param height  标签页条高度
     * @param width   每个标签的宽度（两个标签等宽平分面板宽度）
     * @param count   标签个数（背包 / 创造 = 2）
     */
    public record TabGeometry(int y, int height, int width, int count) {

        /** 第 {@code index} 个标签的左边界。 */
        public int tabX(int index, int panelX) {
            return panelX + index * width;
        }

        /** 第 {@code index} 个标签的中心 x（文本居中用）。 */
        public float tabCenterX(int index, int panelX) {
            return tabX(index, panelX) + width / 2f;
        }

        /** 标签条下沿（内容区顶边）。 */
        public int contentY() {
            return y + height;
        }

        /** 命中第 {@code index} 个标签；越界返回 {@code false}。 */
        public boolean hitTest(int index, int panelX, double mouseX, double mouseY) {
            if (index < 0 || index >= count) {
                return false;
            }
            int x = tabX(index, panelX);
            return mouseX >= x && mouseX < x + width
                    && mouseY >= y && mouseY < y + height;
        }
    }

    /**
     * ★ M4-S7 创造面板网格的几何（{@link TabGeometry} 的同级）。
     *
     * <p>★ <b>它只描述"格子在哪"，不描述"每一格放什么"</b> ——
     * 那是 {@link com.skyisland.world.block.CreativePalette} 的职责。
     * 布局与内容分离的同一条理由见 {@code MenuRendererTextGeometryWiringTest}：
     * 让渲染器"只读不算"，否则同一套算术会在两处各写一遍，然后一起错。
     *
     * <p>★ <b>槽位尺寸被显式放进本 record，而不是用静态字段"回填"</b>：
     * 静态可变的 {@code slotSizeRef} 会让两个不同分辨率的布局互相污染
     * （先算 1080p 再算 720p 就会读到后者），而那种 bug 只在"玩家恰好改过分辨率"
     * 时出现，且症状是格子整体偏移 —— 与真因毫无关系。
     * 代价只是多两个分量，换来的是 record 真的不可变。
     *
     * @param slotSize   单格边长（含 uiScale）
     * @param gap        格间间隙
     * @param groupHeight 每个分类标题行的高度
     * @param rowY       每一行方块格的顶边（含分类标题占位）
     * @param rowOfEntry 每个面板条目落在第几行
     * @param groupTitleOfRow 每一行<b>是否是分类标题行</b>（{@code null} = 本帧不画创造面板）
     * @param entryCount 面板条目数
     */
    public record CreativeGridGeometry(int slotSize,
                                       int gap,
                                       int groupHeight,
                                       int[] rowY,
                                       int[] rowOfEntry,
                                       boolean[] groupTitleOfRow,
                                       int entryCount) {

        /** 本帧是否需要画创造面板（生存模式 / 无面板时为 {@code false}）。 */
        public boolean present() {
            return rowOfEntry != null && entryCount > 0;
        }

        /** 面板条目数（渲染层循环边界）。 */
        public int entryCount() {
            return entryCount;
        }

        /** 第 {@code index} 个条目的列号。 */
        public int columnOf(int index) {
            return index % CREATIVE_COLUMNS;
        }

        /** 第 {@code index} 个条目的行号。 */
        public int rowOf(int index) {
            return rowOfEntry[index];
        }

        /** 第 {@code index} 格方块的顶边 y。 */
        public int entryY(int index) {
            return rowY[rowOfEntry[index]];
        }

        /** 第 {@code index} 格方块的左边界 x。 */
        public int entryX(int index, int gridX) {
            return gridX + columnOf(index) * (slotSize + gap);
        }

        /** 第 {@code row} 行是否是一条分类标题。 */
        public boolean rowIsGroupTitle(int row) {
            return groupTitleOfRow != null && groupTitleOfRow[row];
        }

        /** 命中面板第 {@code index} 格；越界或本帧无面板返回 {@code false}。 */
        public boolean hitTestEntry(int index, int gridX, double mouseX, double mouseY) {
            if (!present() || index < 0 || index >= entryCount) {
                return false;
            }
            int x = entryX(index, gridX);
            int y = entryY(index);
            return mouseX >= x && mouseX < x + slotSize
                    && mouseY >= y && mouseY < y + slotSize;
        }

        /** 总行数（含分类标题行）；本帧无面板时为 {@code 0}。 */
        public int rowCount() {
            return rowY == null ? 0 : rowY.length;
        }

        /** 第 {@code row} 行的顶边 y（绝对坐标）。 */
        public int rowTop(int row) {
            return rowY[row];
        }

        /** 分类标题行的高度；方块行的高度是 {@link #slotSize}。 */
        public int rowHeight(int row) {
            return rowIsGroupTitle(row) ? groupHeight : slotSize;
        }
    }

    private InventoryLayout(int fbWidth, int fbHeight, int uiScale, int slotSize, int gap, int pad,
                            int panelX, int panelY, int panelWidth, int panelHeight,
                            int titleY, int firstRowY, int hotbarRowY, int separatorY,
                            int craftX, int craftWidth, int craftTitleY, int craftFirstRowY,
                            int craftRowHeight, int craftRowCount,
                            TabGeometry tab, CreativeGridGeometry creative) {
        this.tab = tab;
        this.creative = creative;
        this.fbWidth = fbWidth;
        this.fbHeight = fbHeight;
        this.uiScale = uiScale;
        this.slotSize = slotSize;
        this.gap = gap;
        this.pad = pad;
        this.panelX = panelX;
        this.panelY = panelY;
        this.panelWidth = panelWidth;
        this.panelHeight = panelHeight;
        this.titleY = titleY;
        this.firstRowY = firstRowY;
        this.hotbarRowY = hotbarRowY;
        this.separatorY = separatorY;
        this.craftX = craftX;
        this.craftWidth = craftWidth;
        this.craftTitleY = craftTitleY;
        this.craftFirstRowY = craftFirstRowY;
        this.craftRowHeight = craftRowHeight;
        this.craftRowCount = craftRowCount;
    }

    public static InventoryLayout compute(int fbWidth, int fbHeight) {
        return compute(fbWidth, fbHeight, 0);
    }

    /**
     * 计算布局（既有签名，不含标签页）。
     *
     * @param craftRowCount 合成栏要显示的行数（0 = 本帧不画合成栏）
     */
    public static InventoryLayout compute(int fbWidth, int fbHeight, int craftRowCount) {
        return compute(fbWidth, fbHeight, craftRowCount, 0, null);
    }

    /**
     * ★ M4-S7：带标签页与创造面板的完整布局计算。
     *
     * <p><b>它必须走 {@link #compute(int, int, int)} 的同一套算术</b>，
     * 不另起一份 —— 两份"算面板高度"的代码必然在某次改动后分叉，
     * 而症状是"标题与格子错开几像素"，极难查。
     *
     * @param tabCount       标签页个数（0 = 本帧不画标签条；生存模式且未实现切页时传 0）
     * @param creativeRows   创造面板的行结构（含分类标题行）；{@code null} = 本帧不画创造面板
     */
    public static InventoryLayout compute(int fbWidth, int fbHeight, int craftRowCount,
                                          int tabCount, CreativeRows creativeRows) {
        int scale = UiMetrics.uiScale(fbHeight);
        int slot = UiMetrics.px(UiMetrics.SLOT_SIZE, scale);
        int gap = UiMetrics.px(UiMetrics.SLOT_GAP, scale);
        int pad = UiMetrics.px(UiMetrics.PANEL_PAD, scale);
        int separator = UiMetrics.px(SEPARATOR_HEIGHT, scale);

        int gridWidth = COLUMNS * slot + (COLUMNS - 1) * gap;
        int mainRows = Inventory.MAIN_SIZE / COLUMNS;
        int mainHeight = mainRows * slot + (mainRows - 1) * gap;

        int titleH = BitmapFont.lineHeight(UiMetrics.px(UiMetrics.LABEL_SCALE, scale));
        int contentHeight = mainHeight + separator + slot;

        // ---- 右侧合成栏（2026-10-03：背包内合成）----
        int craftWidth = craftRowCount > 0
                ? Math.min(UiMetrics.px(CRAFT_COLUMN_WIDTH, scale),
                        Math.max(0, fbWidth - (gridWidth + 2 * pad
                                + UiMetrics.px(CRAFT_COLUMN_GAP, scale) + pad)))
                : 0;
        int craftRowH = UiMetrics.px(CRAFT_ROW_HEIGHT, scale);
        int craftTitleH = craftRowCount > 0 ? UiMetrics.px(CRAFT_TITLE_HEIGHT, scale) : 0;
        int craftColumnHeight = craftRowCount > 0
                ? craftTitleH + UiMetrics.px(4, scale) + craftRowCount * craftRowH : 0;

        // ★ M4-S7：标签条在标题之下、内容之上。
        //   它<b>不</b>替换标题 —— 标题是"这是背包"这一层的说明，
        //   标签条是"在背包与创造之间选哪一层"，两者回答的不是同一个问题。
        //
        // ★ <b>判据是 {@code > 1} 而不是 {@code > 0}</b>：生存模式只挂一个标签（背包），
        //   而<b>只有一个标签时不画标签条</b> —— 画出来是一条写着「背包」、
        //   点不动的窄条，玩家会去找那个不存在的第二页。
        //   这不是审美问题而是几何问题：画了就得留 22px 高度，
        //   于是生存模式的背包面板凭空多出一条空白，且整体上移 uiScale×11px（居中偏移）。
        //   ★ 这条是被一条"生存模式几何必须与 M2.2 逐像素相同"的守卫逼出来的。
        int tabH = tabCount > 1 ? UiMetrics.px(TAB_STRIP_HEIGHT, scale) : 0;

        // ★ M4-S7：创造面板区的高度（分类标题 + 方块格），以及降级预算。
        //   ★ 降级顺序与 MenuLayout 的三档退让一致（见其类注释）：
        //   ① 砍空行 → ② 砍每行富余 → ③ **绝不动文字/格子本身**。
        //   这里的"文字"是分类标题与方块格，因此它们是<b>最后才动</b>的，
        //   而实际上它们根本不该被压缩 —— 方块格压小就看不出是什么方块了。
        int creativeAreaHeight = 0;
        int[] creativeRowY = null;
        int[] creativeRowOfEntry = null;
        boolean[] creativeRowIsGroup = null;
        if (creativeRows != null && creativeRows.entryCount() > 0) {
            // ★ 先自检再算几何：行结构不自洽时，越界只会在渲染阶段炸，
            //   而那时候栈里全是渲染代码，与真因（内容侧的计数错了）毫无关系。
            creativeRows.verifySelfConsistent();
            int groupH = UiMetrics.px(CREATIVE_GROUP_HEIGHT, scale);
            int rows = creativeRows.rowCount();
            creativeRowY = new int[rows];
            creativeRowIsGroup = new boolean[rows];
            int y = 0;
            for (int r = 0; r < rows; r++) {
                creativeRowY[r] = y;
                creativeRowIsGroup[r] = creativeRows.rowIsGroupTitle(r);
                y += creativeRowIsGroup[r] ? groupH : slot;
            }
            creativeRowOfEntry = new int[creativeRows.entryCount()];
            System.arraycopy(creativeRows.rowOfEntry(), 0, creativeRowOfEntry, 0,
                    creativeRows.entryCount());
            creativeAreaHeight = y + UiMetrics.px(4, scale);
        }

        int leftWidth = gridWidth + 2 * pad;
        int panelWidth = craftWidth > 0
                ? gridWidth + 2 * pad + UiMetrics.px(CRAFT_COLUMN_GAP, scale) + craftWidth
                : leftWidth;
        // ★ 创造面板激活时，面板取"创造区与背包区取大"——
        //   两个内容区是<b>切换</b>关系而不是并列，面板高度必须容得下较大的那个，
        //   否则切到创造页时底板会在动画中途变高（或者更糟：格子被裁掉）。
        int mainAreaHeight = Math.max(contentHeight, creativeAreaHeight);
        int panelHeight = pad + titleH + (tabH > 0 ? tabH + pad : pad) + mainAreaHeight + pad;

        int panelX = Math.max(0, (fbWidth - panelWidth) / 2);
        int panelY = Math.max(0, (fbHeight - panelHeight) / 2);

        int titleY = panelY + pad;
        // ★ 三处都用 tabH（而不是 tabCount > 0）判断"有没有标签条"：
        //   判据散在三处而其中一处用了另一个条件，就是"标签条留了高度但没画"
        //   这类半接线的温床 —— 症状是面板底部一条空白，内容整体上移几像素。
        int tabY = titleY + titleH + (tabH > 0 ? pad : 0);
        int firstRowY = (tabH > 0 ? tabY + tabH : titleY + titleH) + pad;
        int separatorY = firstRowY + mainHeight;
        int hotbarRowY = separatorY + separator;

        int craftX = craftWidth > 0
                ? panelX + pad + gridWidth + UiMetrics.px(CRAFT_COLUMN_GAP, scale) : 0;
        int craftTitleY = firstRowY;
        int craftFirstRowY = craftRowCount > 0
                ? craftTitleY + craftTitleH + UiMetrics.px(4, scale) : 0;

        // ★ 创造网格的绝对坐标 = 内容区左上角（与背包格子同一起点，保持左对齐）。
        //   标签页是"同一个位置换内容"，所以起点必须与背包一致 ——
        //   若另起一个 x，玩家会看到内容在切换时横跳。
        int creativeGridX = panelX + pad;
        if (creativeRowY != null) {
            int base = firstRowY;
            int[] absolute = new int[creativeRowY.length];
            for (int r = 0; r < creativeRowY.length; r++) {
                absolute[r] = base + creativeRowY[r];
            }
            creativeRowY = absolute;
        }

        TabGeometry tabGeometry = new TabGeometry(tabY, tabH,
                tabCount > 0 ? panelWidth / tabCount : 0, tabCount);
        CreativeGridGeometry creativeGeometry = new CreativeGridGeometry(
                slot, gap, UiMetrics.px(CREATIVE_GROUP_HEIGHT, scale),
                creativeRowY, creativeRowOfEntry, creativeRowIsGroup,
                creativeRows == null ? 0 : creativeRows.entryCount());

        return new InventoryLayout(fbWidth, fbHeight, scale, slot, gap, pad,
                panelX, panelY, panelWidth, panelHeight,
                titleY, firstRowY, hotbarRowY, separatorY,
                craftX, craftWidth, craftTitleY, craftFirstRowY, craftRowH, craftRowCount,
                tabGeometry, creativeGeometry);
    }

    /**
     * ★ M4-S7：创造面板的行结构（布局的输入，纯数据）。
     *
     * <p>★ <b>为什么不让布局自己去遍历 {@code CreativePalette}</b>：
     * 那样布局就把"面板里有什么"也管上了，于是
     * 「内容变了但没重新 compute」会表现为<b>格子错位</b>而不是"内容不对"，
     * 而错位比缺一个方块难查得多。
     * 现在的分工是：<b>内容 → 行结构（{@code CreativeRows}）→ 几何（布局）</b>，
     * 每一步都可独立单测。
     *
     * @param entryCount   面板条目数
     * @param rowCount     总行数（含分类标题行）
     * @param rowOfEntry   每个条目落在第几行
     * @param rowIsGroup   每一行是否是一条分类标题
     */
    public record CreativeRows(int entryCount, int rowCount,
                               int[] rowOfEntry, boolean[] rowIsGroup) {

        /** 第 {@code row} 行是否是一条分类标题。 */
        public boolean rowIsGroupTitle(int row) {
            return row >= 0 && row < rowIsGroup.length && rowIsGroup[row];
        }

        /** 第 {@code index} 个条目落在第几行。 */
        public int rowOf(int index) {
            return rowOfEntry[index];
        }

        /**
         * ★ 自检：条目数、行数、两个数组长度必须自洽。
         *
         * <p>它们是<b>平行的数组</b>，长度不一致时症状是"某格画到了别人的位置上"，
         * 而 {@code ArrayIndexOutOfBoundsException} 只会来得更晚（渲染时），
         * 甚至在边界上不抛而静默取到相邻元素。
         */
        public void verifySelfConsistent() {
            if (rowOfEntry.length != entryCount) {
                throw new IllegalStateException(
                        "CreativeRows 自相矛盾：entryCount=" + entryCount
                                + " 但 rowOfEntry.length=" + rowOfEntry.length);
            }
            if (rowIsGroup.length != rowCount) {
                throw new IllegalStateException(
                        "CreativeRows 自相矛盾：rowCount=" + rowCount
                                + " 但 rowIsGroup.length=" + rowIsGroup.length);
            }
            for (int i = 0; i < rowOfEntry.length; i++) {
                if (rowOfEntry[i] < 0 || rowOfEntry[i] >= rowCount) {
                    throw new IllegalStateException(
                            "CreativeRows 第 " + i + " 项落在越界行 " + rowOfEntry[i]
                                    + "（合法 0.." + (rowCount - 1) + "）");
                }
            }
        }
    }


    // ============================================================ 查询

    public int fbWidth() {
        return fbWidth;
    }

    // ============================================================ M4-S7：标签页与创造面板

    /** 标签页条几何；本帧无标签页时 {@code count() == 0}。 */
    public TabGeometry tab() {
        return tab;
    }

    /** 创造面板网格几何；本帧不画创造面板时 {@code present()} 为 {@code false}。 */
    public CreativeGridGeometry creative() {
        return creative;
    }

    /** 创造网格的绝对左边界 x（与背包格子同一起点）。 */
    public int creativeGridX() {
        return panelX + pad;
    }

    /** 本帧是否要画创造面板（视图非空且标签页为创造时由渲染层决定，这里只看几何是否存在）。 */
    public boolean hasCreativeGrid() {
        return creative.present();
    }

    /**
     * 命中第几个标签页；没命中返回 {@code -1}。
     *
     * <p>★ <b>它是"先判标签、后判内容"这条顺序的落点</b>：
     * 标签条与内容区在几何上不重叠，因此两条通道可以各自独立判定；
     * 输入层按"标签先、内容后"的顺序问，标签那一问命中就直接返回，
     * 不再往下问内容 —— 于是"点标签条边缘"不会被当成"点内容区第一行"。
     */
    public int hitTestTabAny(double mouseX, double mouseY) {
        for (int i = 0; i < tab.count; i++) {
            if (tab.hitTest(i, panelX, mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 命中创造面板第几格；没命中或本帧无面板返回 {@code -1}。
     *
     * <p>与 {@link #hitTestCreativeRow} 一样是"遍历求下标"，不用算术反推：
     * 反推需要"格号 → 行 → 列"的整除，而分类标题行让行与列不再有固定步长 ——
     * 那种算术一旦写错，症状是<b>点第 3 格取出第 7 格</b>，且不报错。
     */
    public int hitTestCreativeAny(double mouseX, double mouseY) {
        if (!hasCreativeGrid()) {
            return -1;
        }
        for (int i = 0; i < creative.entryCount(); i++) {
            if (creative.hitTestEntry(i, creativeGridX(), mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    public int fbHeight() {
        return fbHeight;
    }

    public int uiScale() {
        return uiScale;
    }

    /** 槽位边长（已含 uiScale 的实际像素）。 */
    public int slotSize() {
        return slotSize;
    }

    public int panelX() {
        return panelX;
    }

    public int panelY() {
        return panelY;
    }

    public int panelWidth() {
        return panelWidth;
    }

    public int panelHeight() {
        return panelHeight;
    }

    public int titleY() {
        return titleY;
    }

    /** 主背包 3 行与快捷栏 1 行之间的分隔线 y。 */
    public int separatorY() {
        return separatorY;
    }

    // ============================================================ 右侧合成栏（2026-10-03）

    /** 本布局是否含合成栏（{@code craftRowCount > 0}）。 */
    public boolean hasCraftColumn() {
        return craftRowCount > 0 && craftWidth > 0;
    }

    /** 合成栏左边界 x。 */
    public int craftX() {
        return craftX;
    }

    /** 合成栏宽度。 */
    public int craftWidth() {
        return craftWidth;
    }

    /** 合成栏标题的 y。 */
    public int craftTitleY() {
        return craftTitleY;
    }

    /** 一行的高度。 */
    public int craftRowHeight() {
        return craftRowHeight;
    }

    /** 行数。 */
    public int craftRowCount() {
        return craftRowCount;
    }

    /** 第 {@code index} 行的上边界 y。 */
    public int craftRowY(int index) {
        return craftFirstRowY + index * craftRowHeight;
    }

    /** 第 {@code index} 行的矩形是否包含给定像素点。 */
    public boolean hitTestCraftRow(int index, double mouseX, double mouseY) {
        if (!hasCraftColumn() || index < 0 || index >= craftRowCount) {
            return false;
        }
        double y = craftRowY(index);
        return mouseX >= craftX && mouseX < craftX + craftWidth
                && mouseY >= y && mouseY < y + craftRowHeight;
    }

    /**
     * 命中第几行；没命中返回 {@code -1}。
     *
     * <p><b>它与 {@link #hitTestAny} 是两个互不相干的通道</b>，刻意不做成一个
     * 返回"槽位或配方行"的联合下标：两者一个是 {@code 0..35}、一个是 {@code 0..N-1}，
     * 合成一个整数区间会让"第 3 个"到底是格子还是配方变成一个要靠注释才能分清的事。
     */
    public int hitTestCraftAny(double mouseX, double mouseY) {
        if (!hasCraftColumn()) {
            return -1;
        }
        for (int i = 0; i < craftRowCount; i++) {
            if (hitTestCraftRow(i, mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    /** 第 {@code index} 行中心的帧缓冲像素坐标（自测用它把光标"瞄准"到行上）。 */
    public double[] craftRowCenter(int index) {
        return new double[]{craftX + craftWidth / 2.0, craftRowY(index) + craftRowHeight / 2.0};
    }

    /** 某绝对槽位的左上角 x。 */
    public int slotX(int index) {
        int column = index < Inventory.MAIN_SIZE
                ? index % COLUMNS
                : (index - Inventory.HOTBAR_OFFSET) % COLUMNS;
        return panelX + pad + column * (slotSize + gap);
    }

    /** 某绝对槽位的左上角 y。 */
    public int slotY(int index) {
        if (index < Inventory.MAIN_SIZE) {
            return firstRowY + (index / COLUMNS) * (slotSize + gap);
        }
        return hotbarRowY;
    }

    /** 该槽位矩形是否包含给定像素点。 */
    public boolean hitTest(int index, double mouseX, double mouseY) {
        if (index < 0 || index >= Inventory.SLOT_COUNT) {
            return false;
        }
        double x = slotX(index);
        double y = slotY(index);
        return mouseX >= x && mouseX < x + slotSize
                && mouseY >= y && mouseY < y + slotSize;
    }

    /**
     * 命中哪个绝对槽位；没命中返回 {@code -1}。
     *
     * <p>遍历顺序是先主背包后快捷栏 —— 两者不重叠，顺序不影响结果，
     * 但保持与 {@link Inventory} 的索引顺序一致能让"第 N 格"在日志里对得上。
     */
    public int hitTestAny(double mouseX, double mouseY) {
        for (int i = 0; i < Inventory.SLOT_COUNT; i++) {
            if (hitTest(i, mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 窗口坐标 → 帧缓冲像素。
     *
     * <p><b>为什么单独成一个纯函数：</b>本类的全部几何都写在<b>帧缓冲像素</b>里，
     * 而输入层给的鼠标位置是<b>窗口坐标</b>（GLFW 回调原始值）。两者在 DPI = 1 时
     * 恰好相等 —— 也就是说开发机上这条换算写错也看不出来，而在
     * "窗口 1280×720 / 帧缓冲 1920×1080"这类缩放下，
     * 症状是"点第 3 格命中第 12 格"：命中判定本身没错，错的是喂给它的单位。
     *
     * <p>抽成静态纯函数之后，这条换算可以在<b>窗口尺寸 ≠ 帧缓冲尺寸</b>的
     * 参数下被直接断言 —— 那是它唯一会出错、也唯一没法靠试玩发现的区间。
     *
     * @return 长度 2 的数组 {@code {fbX, fbY}}
     */
    public static double[] windowToFramebuffer(double windowX, double windowY,
                                               int fbWidth, int fbHeight,
                                               int windowWidth, int windowHeight) {
        double scaleX = fbWidth / (double) Math.max(1, windowWidth);
        double scaleY = fbHeight / (double) Math.max(1, windowHeight);
        return new double[]{windowX * scaleX, windowY * scaleY};
    }

    /** 某绝对槽位中心的帧缓冲像素坐标（自测用它把光标"瞄准"到格子上）。 */
    public double[] slotCenter(int index) {
        return new double[]{slotX(index) + slotSize / 2.0, slotY(index) + slotSize / 2.0};
    }

    @Override
    public String toString() {
        return "InventoryLayout(" + fbWidth + "x" + fbHeight + " uiScale=" + uiScale
                + " 面板 " + panelWidth + "x" + panelHeight + " @(" + panelX + "," + panelY + "))";
    }
}

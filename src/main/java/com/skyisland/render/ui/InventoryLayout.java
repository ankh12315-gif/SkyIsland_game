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

    private InventoryLayout(int fbWidth, int fbHeight, int uiScale, int slotSize, int gap, int pad,
                            int panelX, int panelY, int panelWidth, int panelHeight,
                            int titleY, int firstRowY, int hotbarRowY, int separatorY,
                            int craftX, int craftWidth, int craftTitleY, int craftFirstRowY,
                            int craftRowHeight, int craftRowCount) {
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
     * 计算布局。
     *
     * @param craftRowCount 合成栏要显示的行数（0 = 本帧不画合成栏）
     */
    public static InventoryLayout compute(int fbWidth, int fbHeight, int craftRowCount) {
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
        // 它把面板从"只有 36 格"变成"左背包 + 右配方列表"。
        // 栏宽先按常量算，再夹到"面板不得越出帧缓冲"这条硬约束之内 ——
        // 极窄的帧缓冲下降级是"栏变窄"，而不是"整块面板被挤出屏幕"。
        int craftWidth = craftRowCount > 0
                ? Math.min(UiMetrics.px(CRAFT_COLUMN_WIDTH, scale),
                        Math.max(0, fbWidth - (gridWidth + 2 * pad
                                + UiMetrics.px(CRAFT_COLUMN_GAP, scale) + pad)))
                : 0;
        int craftRowH = UiMetrics.px(CRAFT_ROW_HEIGHT, scale);
        int craftTitleH = craftRowCount > 0 ? UiMetrics.px(CRAFT_TITLE_HEIGHT, scale) : 0;
        int craftColumnHeight = craftRowCount > 0
                ? craftTitleH + UiMetrics.px(4, scale) + craftRowCount * craftRowH : 0;

        int leftWidth = gridWidth + 2 * pad;
        int panelWidth = craftWidth > 0
                ? gridWidth + 2 * pad + UiMetrics.px(CRAFT_COLUMN_GAP, scale) + craftWidth
                : leftWidth;
        int panelHeight = pad + titleH + pad + Math.max(contentHeight, craftColumnHeight) + pad;

        int panelX = Math.max(0, (fbWidth - panelWidth) / 2);
        int panelY = Math.max(0, (fbHeight - panelHeight) / 2);

        int titleY = panelY + pad;
        int firstRowY = titleY + titleH + pad;
        int separatorY = firstRowY + mainHeight;
        int hotbarRowY = separatorY + separator;

        int craftX = craftWidth > 0
                ? panelX + pad + gridWidth + UiMetrics.px(CRAFT_COLUMN_GAP, scale) : 0;
        int craftTitleY = firstRowY;
        int craftFirstRowY = craftRowCount > 0
                ? craftTitleY + craftTitleH + UiMetrics.px(4, scale) : 0;

        return new InventoryLayout(fbWidth, fbHeight, scale, slot, gap, pad,
                panelX, panelY, panelWidth, panelHeight,
                titleY, firstRowY, hotbarRowY, separatorY,
                craftX, craftWidth, craftTitleY, craftFirstRowY, craftRowH, craftRowCount);
    }

    // ============================================================ 查询

    public int fbWidth() {
        return fbWidth;
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

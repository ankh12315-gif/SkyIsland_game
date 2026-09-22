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

    private InventoryLayout(int fbWidth, int fbHeight, int uiScale, int slotSize, int gap, int pad,
                            int panelX, int panelY, int panelWidth, int panelHeight,
                            int titleY, int firstRowY, int hotbarRowY, int separatorY) {
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
    }

    public static InventoryLayout compute(int fbWidth, int fbHeight) {
        int scale = UiMetrics.uiScale(fbHeight);
        int slot = UiMetrics.px(UiMetrics.SLOT_SIZE, scale);
        int gap = UiMetrics.px(UiMetrics.SLOT_GAP, scale);
        int pad = UiMetrics.px(UiMetrics.PANEL_PAD, scale);
        int separator = UiMetrics.px(SEPARATOR_HEIGHT, scale);

        int gridWidth = COLUMNS * slot + (COLUMNS - 1) * gap;
        int mainRows = Inventory.MAIN_SIZE / COLUMNS;
        int mainHeight = mainRows * slot + (mainRows - 1) * gap;

        int titleH = UiMetrics.px(UiMetrics.PANEL_TITLE_H, scale);
        int contentHeight = mainHeight + separator + slot;

        int panelWidth = gridWidth + 2 * pad;
        int panelHeight = pad + titleH + pad + contentHeight + pad;

        int panelX = (fbWidth - panelWidth) / 2;
        int panelY = Math.max(0, (fbHeight - panelHeight) / 2);

        int titleY = panelY + pad;
        int firstRowY = titleY + titleH + pad;
        int separatorY = firstRowY + mainHeight;
        int hotbarRowY = separatorY + separator;

        return new InventoryLayout(fbWidth, fbHeight, scale, slot, gap, pad,
                panelX, panelY, panelWidth, panelHeight,
                titleY, firstRowY, hotbarRowY, separatorY);
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

    @Override
    public String toString() {
        return "InventoryLayout(" + fbWidth + "x" + fbHeight + " uiScale=" + uiScale
                + " 面板 " + panelWidth + "x" + panelHeight + " @(" + panelX + "," + panelY + "))";
    }
}

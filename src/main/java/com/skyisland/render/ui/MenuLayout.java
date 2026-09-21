package com.skyisland.render.ui;

import com.skyisland.ui.MenuEntry;

import java.util.List;

/**
 * 菜单的<b>几何布局</b>（M1.5 规格第 3/5/9 条）：每一行画在哪里、哪块区域算"点在它上面"。
 *
 * <p><b>为什么布局必须是一个可被两方共用的纯函数：</b>
 * 菜单同时被鼠标与键盘驱动。鼠标点击要做命中判定，键盘移动要靠"当前选中项"，
 * 而两者必须指向<u>同一行</u>。如果命中判定用一套坐标（在输入处理里算）、
 * 绘制用另一套（在渲染里算），那么"鼠标点第 5 行却触发第 6 行"就只是
 * 两套坐标何时漂移的问题。本类让它们读同一份结果，这类 bug 从"迟早发生"变成"不可能"。
 *
 * <p><b>为什么行高按行类型区分：</b>设置界面有 26 行（分节标题 / 空行 / 数值 / 键位）。
 * 若所有行等高，那么在 720p 下要么空行占掉一整行高度（界面被撑出屏幕），
 * 要么把数值行压到读不清。分节标题与空行本来就"只是分隔"，
 * 给它们更矮的行高是排版需求，不是偷工。
 *
 * <p><b>为什么不在本类里碰 GL：</b>它是纯计算，因此"点在空白处不应该命中任何行"
 * 这类判定可以单元测试。绘制在 {@link MenuRenderer}。
 */
public final class MenuLayout {

    /** 基准高度：所有尺寸以 1280×720 下的像素给出，再乘 uiScale。 */
    public static final int REFERENCE_HEIGHT = 720;

    /** 布局风格：封面式（主菜单，行少而大）与面板式（设置/暂停，行多而密）。 */
    public enum Style {
        COVER,
        PANEL
    }

    private static final int COVER_ROW_HEIGHT = 24;
    private static final int PANEL_ROW_HEIGHT = 16;
    private static final int HEADER_ROW_HEIGHT = 18;
    private static final int SPACER_ROW_HEIGHT = 8;
    private static final int INFO_ROW_HEIGHT = 14;

    private final int fbWidth;
    private final int fbHeight;
    private final int uiScale;
    private final Style style;
    private final int columnX;
    private final int columnWidth;
    private final int titleY;
    private final int subtitleY;
    private final int firstRowY;
    private final int[] rowY;
    private final int[] rowHeight;

    private MenuLayout(int fbWidth, int fbHeight, int uiScale, Style style,
                       int columnX, int columnWidth, int titleY, int subtitleY,
                       int firstRowY, int[] rowY, int[] rowHeight) {
        this.fbWidth = fbWidth;
        this.fbHeight = fbHeight;
        this.uiScale = uiScale;
        this.style = style;
        this.columnX = columnX;
        this.columnWidth = columnWidth;
        this.titleY = titleY;
        this.subtitleY = subtitleY;
        this.firstRowY = firstRowY;
        this.rowY = rowY;
        this.rowHeight = rowHeight;
    }

    public static MenuLayout compute(int fbWidth, int fbHeight, List<MenuEntry> entries, Style style) {
        int scale = Math.max(1, Math.round(fbHeight / (float) REFERENCE_HEIGHT));
        int width = Math.max(200 * scale, Math.min(fbWidth - 80 * scale, 620 * scale));
        int x = (fbWidth - width) / 2;

        int titleY;
        int subtitleY;
        int rowsY;
        if (style == Style.COVER) {
            titleY = Math.round(fbHeight * 0.20f);
            subtitleY = titleY + 34 * scale;
            rowsY = Math.round(fbHeight * 0.44f);
        } else {
            titleY = 30 * scale;
            subtitleY = titleY + 22 * scale;
            rowsY = 84 * scale;
        }

        int n = entries.size();
        int[] ys = new int[n];
        int[] hs = new int[n];
        int cursor = rowsY;
        for (int i = 0; i < n; i++) {
            ys[i] = cursor;
            hs[i] = rowHeightOf(entries.get(i), style, scale);
            cursor += hs[i];
        }
        return new MenuLayout(fbWidth, fbHeight, scale, style, x, width, titleY, subtitleY,
                rowsY, ys, hs);
    }

    private static int rowHeightOf(MenuEntry entry, Style style, int scale) {
        return switch (entry.kind()) {
            case HEADER -> HEADER_ROW_HEIGHT * scale;
            case SPACER -> SPACER_ROW_HEIGHT * scale;
            case INFO -> INFO_ROW_HEIGHT * scale;
            default -> (style == Style.COVER ? COVER_ROW_HEIGHT : PANEL_ROW_HEIGHT) * scale;
        };
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

    public Style style() {
        return style;
    }

    /** 菜单列的左边界。 */
    public int columnX() {
        return columnX;
    }

    public int columnWidth() {
        return columnWidth;
    }

    /** 数值右对齐的基准 x。 */
    public int valueRightX() {
        return columnX + columnWidth - 12 * uiScale;
    }

    public int labelX() {
        return columnX + 14 * uiScale;
    }

    public int titleY() {
        return titleY;
    }

    public int subtitleY() {
        return subtitleY;
    }

    public int firstRowY() {
        return firstRowY;
    }

    public int rowY(int index) {
        return rowY[index];
    }

    public int rowHeight(int index) {
        return rowHeight[index];
    }

    public int rowCount() {
        return rowY.length;
    }

    /** 该行矩形是否包含给定像素点（命中判定；含 1 像素取整容差）。 */
    public boolean hitTest(int index, double mouseX, double mouseY) {
        if (index < 0 || index >= rowY.length) {
            return false;
        }
        double top = rowY[index];
        double bottom = top + rowHeight[index];
        return mouseX >= columnX - uiScale && mouseX <= columnX + columnWidth + uiScale
                && mouseY >= top - 1 && mouseY < bottom - 1;
    }

    /** 命中哪一行；没命中返回 -1。从后往前扫，保证重叠时取靠下的那一行不会被上面的吞掉。 */
    public int hitTestAny(double mouseX, double mouseY, int count) {
        int n = Math.min(count, rowY.length);
        for (int i = 0; i < n; i++) {
            if (hitTest(i, mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    /** 提示行（底部）的 y。 */
    public int hintY() {
        return fbHeight - 44 * uiScale;
    }

    /** 版本行（右下角）的 y。 */
    public int versionY() {
        return fbHeight - 18 * uiScale;
    }

    @Override
    public String toString() {
        return "MenuLayout(" + style + " " + rowCount() + " 行, uiScale=" + uiScale
                + ", 列 x=" + columnX + " w=" + columnWidth + ")";
    }
}

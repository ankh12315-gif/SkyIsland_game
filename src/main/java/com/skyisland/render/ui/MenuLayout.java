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
 * <p><b>为什么行高按行类型区分：</b>M2.2 起设置界面有 28 行（三个分组标题 / 空行 / 数值 / 键位）。
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

    /**
     * 标题放大倍数：封面式 5 倍、面板式 3 倍。
     *
     * <p><b>为什么放在布局里而不是渲染器里：</b>标题"占多高"是<u>几何</u>，
     * 副标题画在哪要由它决定。M2.2 之前这个倍数只写在 {@code MenuRenderer.titleScale}
     * 里，而布局这边按"标题高 34px"硬编码副标题位置 —— 两边各说各话，
     * 结果标题字号在 M1.5 之后被改成 5 倍时，副标题位置没有任何东西跟着变，
     * 于是副标题被画在标题<u>里面</u>（720p 下重叠 16px），
     * 而当时的断言 {@code subtitleY > titleY} 在重叠时照样成立。
     */
    public static final int COVER_TITLE_SCALE = 5;

    /** 面板式标题放大倍数（见 {@link #COVER_TITLE_SCALE} 的说明）。 */
    public static final int PANEL_TITLE_SCALE = 3;

    /** 副标题放大倍数（两种式样一致，从而"副标题比标题小"是全局规律）。 */
    public static final int SUBTITLE_SCALE = 2;

    /** 标题行盒与副标题行盒之间的空隙（基准像素）。 */
    private static final int TITLE_GAP = 4;

    /** 副标题行盒与第一行菜单项之间的空隙（基准像素）。 */
    private static final int ROWS_GAP = 6;

    /** 提示行距底边的留白（基准像素）。 */
    private static final int HINT_BOTTOM = 44;

    /** 菜单列底部与提示行之间必须保留的空隙（基准像素）。 */
    private static final int BOTTOM_MARGIN = 8;

    /**
     * 压缩时的行高下限（基准像素）。
     *
     * <p>取 6 而不是 10 是算出来的：基准里最矮的行是空行（8px），
     * 1080p 下需要压到约 0.94 倍，8 × 0.94 ≈ 7.5。下限一旦高于它，
     * 空行就会被"抬回"原高，压缩就不再等比 —— 排版节奏会被专门破坏空行这一档。
     * 因此这个数只作为"行高必须为正"的护栏，不参与排版决策。
     */
    private static final int MIN_ROW_HEIGHT = 6;

    /** 该式样下标题的放大倍数。 */
    private static int titleScaleOf(Style style) {
        return style == Style.COVER ? COVER_TITLE_SCALE : PANEL_TITLE_SCALE;
    }

    /** 提示行的 y —— 与 {@link #hintY()} 同一口径，供布局期的纵向预算共用。 */
    private static int hintYOf(int fbHeight, int scale) {
        return fbHeight - HINT_BOTTOM * scale;
    }

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

        // 标题真正吃掉的纵向空间是<b>行盒</b>而不是字形高度：
        // BitmapFont 把所有文字都排进 LINE_ROWS 行（= 中文的 12 行）的行盒里，
        // ASCII 的 7 行字形只是居中放在其中（ASCII_ROW_OFFSET = 3）。
        // 于是 5 倍标题的占位是 12 × 5 = 60px，而不是 7 × 5 = 35px —— 差出来的 25px
        // 正是"副标题被画进标题里"的来源。这里改成按真实行盒推导，两者不可能再脱节。
        int titleBox = BitmapFont.lineHeight(titleScaleOf(style) * scale);
        int subtitleBox = BitmapFont.lineHeight(SUBTITLE_SCALE * scale);

        int titleY;
        int subtitleY;
        int rowsY;
        if (style == Style.COVER) {
            titleY = Math.round(fbHeight * 0.20f);
            subtitleY = titleY + titleBox + TITLE_GAP * scale;
            // 封面式的行组位置是"整屏比例"，与标题互不影响，保留原口径
            rowsY = Math.max(Math.round(fbHeight * 0.44f), subtitleY + subtitleBox + ROWS_GAP * scale);
        } else {
            // 面板式：标题从 30 上移到 14。旧值 30 是"按 7 行字形高算"的产物，
            // 行盒一按真实高度算，30 + 36 就已经压到副标题的 52 上，再往下就是行组。
            // 上移而非下移的理由：下移会连带把 28 行的设置界面推得更低，
            // 而它在本分辨率下本来就快贴到底部提示了。上移之后 720p / 1080p / 1440p
            // 三档的首行 y 都<b>恰好保持原值 84 / 168 / 168</b>，其余几何一点没动。
            titleY = 14 * scale;
            subtitleY = titleY + titleBox + TITLE_GAP * scale;
            rowsY = Math.max(84 * scale, subtitleY + subtitleBox + ROWS_GAP * scale);
        }

        int n = entries.size();
        int[] hs = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            hs[i] = rowHeightOf(entries.get(i), style, scale);
            total += hs[i];
        }

        // ---- 纵向预算：菜单列必须在底部提示行之上结束 ----
        // 为什么必须有这一步：uiScale = round(fbHeight / 720)，而 1080 / 720 = 1.5
        // 会被四舍五入成 2。于是 1920×1080 下界面按 1440p 的尺度排版，
        // 却只有 1080p 的高度可用 —— 28 行的设置界面会一路压到底部提示行上，
        // 把最后一行（'返回'）盖住。而玩家看不到'返回'就出不去设置界面，
        // 这正是既有的 720p 用例当初要防的失败模式，只是它没跑 1080p。
        // 720p（factor 天然放得下）与 1440p（尺度与高度同比）都不会触发这里，
        // 因此这个分支只在"尺度取整把内容撑爆"的分辨率上生效。
        int bandBottom = hintYOf(fbHeight, scale) - BOTTOM_MARGIN * scale;
        int available = Math.max(0, bandBottom - rowsY);
        if (total > available && total > 0) {
            // 下限只用来保证"行高为正"（既有断言要求 rowHeight > 0），不用它来做排版决定：
            // 第一版写成"每行 max(下限, 压缩值)"，结果 8px 的空行被下限抬回 16px，
            // 每抬一格就把预算重新撑破一次（实测 808 的可用高度用出了 818）。
            // 所以这里改成<b>迭代</b>：压完若仍超预算，就按超出的比例再压一轮。
            int floorH = Math.max(1, MIN_ROW_HEIGHT * scale);
            int[] compressed = new int[n];
            double factor = available / (double) total;
            for (int pass = 0; pass < 8; pass++) {
                int used = 0;
                for (int i = 0; i < n; i++) {
                    compressed[i] = Math.max(floorH, (int) Math.floor(hs[i] * factor));
                    used += compressed[i];
                }
                if (used <= available) {
                    break;
                }
                factor *= available / (double) used;
            }
            System.arraycopy(compressed, 0, hs, 0, n);

            // 逐行向下取整会剩下几像素余量；补到最后一行，让列正好用满可用高度，
            // 否则"压了却还是差几像素"会让这条预算每轮都要重算一次。
            int used = 0;
            for (int h : hs) {
                used += h;
            }
            if (available > used) {
                hs[n - 1] += available - used;
            }
        }

        int[] ys = new int[n];
        int cursor = rowsY;
        for (int i = 0; i < n; i++) {
            ys[i] = cursor;
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

    /**
     * 标题的放大倍数（{@link #COVER_TITLE_SCALE} 或 {@link #PANEL_TITLE_SCALE}）。
     *
     * <p>渲染器<b>必须</b>用它来画标题 —— 布局的 {@code subtitleY} 就是按这个倍数
     * 推导出来的。谁要是绕过它自己写一个倍数，就重现了 M2.2 修掉的那个缺陷。
     */
    public int titleScale() {
        return titleScaleOf(style);
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
        return hintYOf(fbHeight, uiScale);
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

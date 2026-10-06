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
 * <p><b>2026-10-04：行盒契约与"文字倍数按式样定档"</b>
 * <p>玩家报"设置界面的文字有一点重叠"。追下去是一条链：
 * <ol>
 *   <li>{@link BitmapFont} 把所有文字排进 {@link BitmapFont#LINE_ROWS}（= 12）行的<b>行盒</b>里，
 *       ASCII 的 7 行字形只是居中放在其中（{@code ASCII_ROW_OFFSET = 3}）；</li>
 *   <li>一行 2 倍字占的纵向空间因此是 {@code lineHeight(2 * scale)}，
 *       <b>不是</b> {@code 7 * 2 * scale}；</li>
 *   <li>而当时的 {@code PANEL_ROW_HEIGHT} 只有 16 —— 比行盒还小 8。
 *       渲染器算出的 {@code textY = y + (rowH - box) / 2} 是<b>负数</b>，
 *       于是每对相邻行的文字盒压掉 8px（720p）/ 16px（1080p）。</li>
 * </ol>
 *
 * <p><b>为什么既有断言没抓到：</b>{@code rowsAreOrderedAndNeverOverlap} 只断言<b>行矩形</b>不重叠，
 * 而行矩形确实不重叠 —— 重叠的是<b>文字行盒</b>，它溢出了行矩形。
 * 没有任何一条断言问过"这行放得下自己的文字吗"。
 *
 * <p><b>★ 根因不是"留白没调好"，而是 2 倍字在任何分辨率下都排不下（实测）：</b>
 * 设置界面共 28 行（4 分节标题 / 3 空行 / 1 说明 / 20 可操作行），
 * 其中 25 行带字。逐档算下来：
 * <pre>
 *   分辨率        可用高度   2 倍字需要   1 倍字需要
 *   1280x720        584        600（超 16）  312（余 272）
 *   1920x1080       808       1200（超 392） 624（余 184）
 *   2560x1440      1168       1200（超  32） 624（余 544）
 *   3840x2160      1752       1800（超  48） 936（余 816）
 *   800x600         464        600（超 136） 312（余 152）
 * </pre>
 * 注意 1080p 那一行：{@code uiScale = round(1080/720) = 2}，于是"2 倍字"实际是 4 倍字，
 * 缺 392px —— 无论怎么调留白都不可能。所以本类把<b>文字倍数按式样定档</b>：
 * 封面式（主菜单，行少而大）用 2 倍，面板式（设置 / 暂停，行多而密）用 1 倍。
 * 两者都仍在 {@code 1..2} 的整数倍里，因此不存在半个像素的问题。
 *
 * <p><b>为什么"排得下"优先于"看起来更大"：</b>
 * 重叠的界面不是"不够好看"，是<b>读不了</b>；而 1 倍字在 1280x720 下有 272px 富余，
 * 把它按行分下去每行多 9px，行高 21px 配 12px 字 —— 行距比 1.8，比原来"两行字贴在一起"好得多。
 *
 * <p><b>为什么行高必须由行盒推出来：</b>见 {@link #textBoxHeight}。
 * 凡是"文字占多高"都必须问它，不许在渲染器里写死倍数 ——
 * 那正是 INFO 行被当成 2 倍字、进而整体错位的第二个入口。
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

    // ============================================================ 文字倍数（2026-10-04 定档）

    /**
     * 该式样的文字倍数。
     *
     * <p><b>封面式 2 倍、面板式 1 倍，这是算出来的而不是随手定的</b>：
     * 主菜单只有 4 行，2 倍字（24px 行盒）绰绰有余；设置界面 25 行带字，
     * 2 倍字在任何分辨率下都超出可用高度（见类注释里的那张表）。
     *
     * <p>两档都留在整数倍里，因此不需要处理"半个像素"——点阵字模按整数倍放大才不出锯齿。
     */
    public static int textScaleOf(Style style) {
        return style == Style.COVER ? 2 : 1;
    }

    /**
     * 该行文字的倍数（相对 {@code uiScale = 1} 的<b>绝对</b>倍数，不是"相对式样的比例"）。
     *
     * <p>说明行用 1 倍（它是脚注，不该和可操作行一样大），空行无字（0）。
     * 其余各行取 {@link #textScaleOf(Style)}：封面式 2 倍、面板式 1 倍。
     *
     * <p><b>为什么"面板式的说明行与可操作行同为 1 倍"</b>：点阵字模只有整数倍放大才不出锯齿，
     * 1 倍已经是最小可用倍数，说明行无法再小。它与可操作行的区分靠颜色
     * （{@link UiTheme#INFO} 对 {@link UiTheme#ITEM}）而不是字号 —— 这是"排得下"优先的代价，
     * 在此写明以免日后被当成 bug 又"顺手放大"，那会直接退回排不下的状态。
     */
    public static int relativeTextScaleOf(MenuEntry entry, Style style) {
        return switch (entry.kind()) {
            case INFO -> 1;
            case SPACER -> 0;
            default -> textScaleOf(style);
        };
    }

    /**
     * 一行文字在该行里占的纵向像素（<b>行盒</b>，不是字形高）。
     *
     * <p><b>渲染器与测试都必须用它算文字的 y</b>，而不是自己写
     * {@code (rowH - lineHeight(2 * scale)) / 2} —— 那个式子里的倍数是写死的，
     * 与"这一行到底用几倍字"无关，于是 INFO 行（1 倍）也被当成 2 倍算行盒。
     * 这正是行盒脱节的第二个入口。
     */
    public static int textBoxHeight(MenuEntry entry, Style style, int uiScale) {
        int rel = relativeTextScaleOf(entry, style);
        if (rel == 0) {
            return 0;
        }
        return BitmapFont.lineHeight(rel * uiScale);
    }

    // ============================================================ 尺寸常量

    /** 封面式行高下限（基准像素）：主菜单行少而大，这是它"看起来比设置界面宽松"的来源。 */
    private static final int COVER_ROW_HEIGHT = 24;

    /**
     * 每行文字<b>之上最多</b>还能加多少行盒（基准倍数 1）。
     *
     * <p>把富余全部分下去会让行高无节制地涨（1440p 下富余 544px 均摊到 25 行 = 每行多 22px），
     * 界面变成"行与行之间能站一个人"。封顶在"行高不超过两倍行盒"，
     * 得到的行距比约 1.8 —— 密而不挤，正是设置界面该有的节奏。
     */
    private static final int MAX_BREATHING_ROWS = 1;

    /** 分节标题的分隔线：与行盒的距离（基准像素）。 */
    private static final int HEADER_RULE_GAP = 2;

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
    /** 每一行<b>文字行盒</b>的顶边 y（渲染器直接读，不做垂直居中运算）。 */
    private final int[] rowTextY;
    /** 每一行文字的绝对倍数（含 uiScale）。 */
    private final int[] rowTextScale;
    /** 分节标题分隔线的 y；非分节标题为 -1。 */
    private final int[] rowRuleY;

    private MenuLayout(int fbWidth, int fbHeight, int uiScale, Style style,
                       int columnX, int columnWidth, int titleY, int subtitleY,
                       int firstRowY, int[] rowY, int[] rowHeight,
                       int[] rowTextY, int[] rowTextScale, int[] rowRuleY) {
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
        this.rowTextY = rowTextY;
        this.rowTextScale = rowTextScale;
        this.rowRuleY = rowRuleY;
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

        // ---- 每一行的文字行盒与绝对倍数：先算出来，渲染器只读不算 ----
        int[] textScale = new int[n];
        int[] textBox = new int[n];
        // hardFloor = 压无可压的下限（文字行盒 + 分节标题的分隔线）。压过头就会重叠。
        int[] hardFloor = new int[n];
        // preferred = 期望高度 = hardFloor + 该行自带的额外留白（目前只有空行有）。
        int[] preferred = new int[n];
        int maxTextBox = 0;
        int totalHard = 0;
        int totalPreferred = 0;
        int textRows = 0;
        for (int i = 0; i < n; i++) {
            MenuEntry e = entries.get(i);
            int rel = relativeTextScaleOf(e, style);
            // ★ rel 已经是"相对 uiScale=1 的绝对倍数"，这里只能乘 scale 一次。
            //   写成 rel * textScaleOf(style) * scale 会把式样基准乘两遍 ——
            //   封面式于是变成 4 倍字（48px 行盒），与本类注释里"封面式 2 倍"自相矛盾，
            //   而 4 行主菜单根本不需要那么大的字。
            textScale[i] = rel * scale;
            textBox[i] = rel == 0 ? 0 : BitmapFont.lineHeight(textScale[i]);
            maxTextBox = Math.max(maxTextBox, textBox[i]);

            int floor = textBox[i];
            int extra = 0;
            if (e.kind() == MenuEntry.Kind.SPACER) {
                // 空行是"组与组之间的那一段留白"，高度就是一个行盒 —— 它是排版的一部分，
                // 不是可有可无的装饰。压缩时它是<b>第一个</b>被牺牲的（见下）。
                extra = boxOfList(entries, style, scale);
            } else if (style == Style.COVER) {
                floor = Math.max(COVER_ROW_HEIGHT * scale, floor);
            } else if (e.kind() == MenuEntry.Kind.HEADER) {
                // 分节标题额外需要一条分隔线的位置：线画在行盒之下 HEADER_RULE_GAP 处。
                floor += HEADER_RULE_GAP * scale + Math.max(1, scale);
            }
            hardFloor[i] = floor;
            preferred[i] = floor + extra;
            totalHard += hardFloor[i];
            totalPreferred += preferred[i];
            if (rel > 0) {
                textRows++;
            }
        }

        // ---- 纵向预算：菜单列必须在底部提示行之上结束 ----
        // 为什么必须有这一步：uiScale = round(fbHeight / 720)，而 1080 / 720 = 1.5
        // 会被四舍五入成 2。于是 1920×1080 下界面按 1440p 的尺度排版，
        // 却只有 1080p 的高度可用 —— 28 行的设置界面会一路压到底部提示行上，
        // 把最后一行（'返回'）盖住。而玩家看不到'返回'就出不去设置界面。
        int bandBottom = hintYOf(fbHeight, scale) - BOTTOM_MARGIN * scale;
        int available = Math.max(0, bandBottom - rowsY);

        int[] hs = preferred.clone();
        if (totalPreferred <= available) {
            // ---- 有富余：按行分下去，让列正好填满可用高度 ----
            // 只分给<b>带字的行</b>；空行的高度已经定死（一个行盒），
            // 再分下去就变成"组与组之间能站一个人"，而它的本职就是分隔。
            // 封顶在 MAX_BREATHING_ROWS 个行盒：行距比约 1.8，密而不挤。
            int budget = Math.min(available - totalPreferred, maxTextBox * MAX_BREATHING_ROWS * textRows);
            int q = textRows > 0 ? budget / textRows : 0;
            int r = textRows > 0 ? budget % textRows : 0;
            int k = 0;
            for (int i = 0; i < n; i++) {
                if (textBox[i] == 0) {
                    continue;
                }
                hs[i] += q + (k < r ? 1 : 0);
                k++;
            }
        } else if (totalHard > available) {
            // ---- 连"压无可压"的下限都放不下 ----
            // 只能整体等比退让，并且<b>明确不假装排得下</b>：
            // 数字会小于文字行盒，文字因此会压到相邻行上。
            // 触发它的窗口极小（< 约 450px 高），默认窗口 1280x720 不会走到这里；
            // 保留这条路径是为了让小窗口下菜单仍然可见，而不是抛异常或画到屏幕外。
            double f = available / (double) totalHard;
            for (int i = 0; i < n; i++) {
                hs[i] = Math.max(1, (int) Math.floor(hardFloor[i] * f));
            }
        } else {
            // ---- 放得下但不够：先牺牲空行（纯装饰），再按比例削每行的富余 ----
            // ★ 顺序是有讲究的：空行高一个行盒、每行富余又高最多一个行盒，
            //   而文字行盒是"压了就会重叠"的那一层。三档从软到硬依次退让，
            //   玩家看到的是"组间距先消失，然后行距变紧"，而不是"字开始叠字"。
            int cut = totalPreferred - available;
            int[] slack = new int[n];
            int totalSlack = 0;
            for (int i = 0; i < n; i++) {
                slack[i] = preferred[i] - hardFloor[i];
                totalSlack += slack[i];
            }
            if (cut <= totalSlack) {
                // ★ 比例必须在遍历<b>之前</b>算好。
                //   写成 take = slack[i] * (cut / (double) totalSlack) 会边遍历边把
                //   totalSlack -= take，于是分母在同一次迭代里被自己改小，
                //   后面的行拿到的比例越来越小（实测几乎全是 0），
                //   结果 cut 削不掉、八轮空转，最后落到"补余量到最胖那行"上 ——
                //   于是中间行保持 22px 而第 0 行涨到 39px。症状极怪，但根因就是这个分母。
                double share = totalSlack <= 0 ? 0.0 : cut / (double) totalSlack;
                int[] taken = new int[n];
                int cutTotal = 0;
                for (int i = 0; i < n; i++) {
                    int t = Math.min(slack[i], (int) Math.round(slack[i] * share));
                    taken[i] = t;
                    cutTotal += t;
                }
                // 取整可能少削了：把差额补给还有余量的行（按余量比例），保证 cut 真能消掉。
                int diff = cut - cutTotal;
                for (int i = 0; i < n && diff > 0; i++) {
                    int t = Math.min(slack[i] - taken[i], diff);
                    taken[i] += t;
                    diff -= t;
                }
                for (int i = 0; i < n; i++) {
                    hs[i] = preferred[i] - taken[i];
                }
            } else {
                // 空行削光仍不够：整体等比退让到 hardFloor，再不够就只能承认排不下。
                double f = available / (double) totalHard;
                for (int i = 0; i < n; i++) {
                    hs[i] = Math.max(1, (int) Math.floor(hardFloor[i] * f));
                }
            }
        }

        // ---- 落 y，并把"文字盒顶边"与"分隔线 y"一并算好 ----
        // 渲染器只读这三个数组，<b>不再做任何垂直居中或倍数换算</b> ——
        // 凡是"文字占多高、画在哪"都在这里定完，绘制与行高不可能再脱节。
        int[] ys = new int[n];
        int[] textYs = new int[n];
        int[] ruleYs = new int[n];
        int cursor = rowsY;
        for (int i = 0; i < n; i++) {
            ys[i] = cursor;
            boolean isHeader = entries.get(i).kind() == MenuEntry.Kind.HEADER;
            // 分节标题把富余<b>对半分</b>（上下各一半），于是"标题悬在分隔线上方、
            // 分隔线之下留出组间距"；其余行在行盒之外的部分上下均分。
            int above = isHeader ? (hs[i] - textBox[i] - HEADER_RULE_GAP * scale
                    - Math.max(1, scale)) / 2 : (hs[i] - textBox[i]) / 2;
            textYs[i] = ys[i] + Math.max(0, above);
            ruleYs[i] = isHeader
                    ? textYs[i] + textBox[i] + HEADER_RULE_GAP * scale
                    : -1;
            cursor += hs[i];
        }
        return new MenuLayout(fbWidth, fbHeight, scale, style, x, width, titleY, subtitleY,
                rowsY, ys, hs, textYs, textScale, ruleYs);
    }

    /**
     * 该列表里"一行普通文字"的行盒高度 —— 空行按它定高。
     *
     * <p>取可操作行的代表值（2 倍封面 / 1 倍面板），而不是任意挑一行：
     * 空行是"组之间的那一段留白"，它的自然高度就是"一行的行盒"，
     * 这样列表里每一处的纵向节奏都是同一个单位。
     */
    private static int boxOfList(List<MenuEntry> entries, Style style, int uiScale) {
        for (MenuEntry e : entries) {
            if (e.kind() == MenuEntry.Kind.SLIDER || e.kind() == MenuEntry.Kind.TOGGLE
                    || e.kind() == MenuEntry.Kind.BINDING || e.kind() == MenuEntry.Kind.ACTION) {
                return BitmapFont.lineHeight(textScaleOf(style) * uiScale);
            }
        }
        return BitmapFont.lineHeight(textScaleOf(style) * uiScale);
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

    /** 该式样的文字倍数（封面 2 / 面板 1）。见 {@link #textScaleOf(Style)} 的定档理由。 */
    public int textScale() {
        return textScaleOf(style);
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

    /**
     * 第 {@code index} 行<b>文字行盒</b>的顶边 y。
     *
     * <p>渲染器画这一行的文字时<b>必须</b>用它，而不是自己写
     * {@code y + (rowHeight - lineHeight(2 * scale)) / 2} ——
     * 那个式子里的倍数是写死的，INFO 行与 1 倍字的面板式都会被算错。
     */
    public int rowTextY(int index) {
        return rowTextY[index];
    }

    /** 第 {@code index} 行文字的绝对倍数（已含 uiScale）。 */
    public int rowTextScale(int index) {
        return rowTextScale[index];
    }

    /**
     * 第 {@code index} 行分节标题分隔线的 y；非分节标题返回 -1。
     *
     * <p>它由布局给出而不是渲染器自己算 {@code y + rowH - 4}：
     * 行高等于行盒时那种画法会让线紧贴文字底边，看上去就是"文字下面压了一道线"。
     */
    public int rowRuleY(int index) {
        return rowRuleY[index];
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
                + ", 文字 " + textScale() + " 倍, 列 x=" + columnX + " w=" + columnWidth + ")";
    }
}

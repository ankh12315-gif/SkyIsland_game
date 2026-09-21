package com.skyisland.render.ui;

/**
 * 程序化点阵字模（HUD 文字用，<b>零外部资产</b>）：ASCII 5×7 与简体中文 12×12 两套字形。
 *
 * <p><b>为什么自己做字模而不是引入字体库或 PNG 图集：</b>
 * <ul>
 *   <li>M1 的 HUD 只需要"把数字念出来"（FPS / 坐标 / 方块 ID），不涉及排版；</li>
 *   <li>引入 TTF 渲染要带 FreeType/STB 依赖或自写字形光栅化，成本远超收益；</li>
 *   <li>引入 PNG 图集就要引入 TextureArray 那一整套资源管线 —— 而它已被明确顺延到 M2
 *       （TECH_DESIGN_v0.1.1 §S′）。为 HUD 提前把它拉回来属于范围蔓延。</li>
 * </ul>
 * 点阵数据直接写在代码里，因此不存在"资产丢失导致 HUD 空白"这种失败模式。
 *
 * <p><b>两套字形与"12 行行高"的统一口径：</b>ASCII 是 5 列 × 7 行，中文是 12 列 × 12 行。
 * 一行文字必须有一个统一的排版高度，否则中英混排会互相错位，所以这里规定
 * <b>行高恒为 {@value #LINE_ROWS} 行</b>（= 中文的 {@link CjkFont#ROWS}），
 * ASCII 的 7 行在这个行高里<b>垂直居中</b>，占第 {@value #ASCII_ROW_OFFSET}..9 行 ——
 * 上下各留 3/2 行，视觉重心与中文字面基本齐平。
 * 行空间坐标一律用 {@link #pixelAt(char, int, int)} 读，它内部负责这次偏移换算；
 * 老的 {@link #pixel(char, int, int)} 保留为"字形自身坐标系"（0..6 行）的访问器。
 *
 * <p><b>中文点阵从哪来：</b>{@link CjkFont} 由 {@code tools/fontgen/GenCjkFont.java} 用本机 AWT
 * 光栅化离线烘焙而成（字符集是扫描源码得到的）。运行期不加载任何字体文件，
 * 因此仍是确定性的、可被单元测试逐字形校验的。
 *
 * <p><b>编码方式：</b>ASCII 每字符 5 个字节，一个字节一列；字节的 bit0 = 最上一行，
 * bit6 = 最下一行（bit7 恒为 0）。共覆盖 ASCII 32–126（95 个可打印字符）。
 * 中文每字符 12 列 × 2 字节，位序同上（见 {@link CjkFont}）。两者约定一致，
 * 好处是可以被<u>单元测试逐字符校验</u>（见 {@code BitmapFontTest}：查表长度、空字形普查、抽样比对）。
 *
 * <p><b>边界（有意为之）：</b>没有字形的字符（未烘焙的中文、控制字符等）渲染为<b>空白</b>，
 * 只有 ASCII 里的越界字符才回落 {@code '?'}（这是 M1 的既有行为，保持不变）。
 * 之所以不给中文也回落 {@code '?'}：一个空白字形一眼就能看出"缺字"，
 * 而顶替上去的 {@code '?'} 会被当成文案问题漏过去 —— 缺字必须显眼。
 */
public final class BitmapFont {

    /** 第一个可用字符。 */
    public static final char FIRST_CHAR = 32;

    /** 最后一个可用字符。 */
    public static final char LAST_CHAR = 126;

    public static final int GLYPH_COLUMNS = 5;
    public static final int GLYPH_ROWS = 7;

    /** 字形之间的空隙（像素）。 */
    public static final int GLYPH_SPACING = 1;

    /** 一个 ASCII 字符在屏幕上占的宽度（像素，缩放 = 1 时）。 */
    public static final int ADVANCE = GLYPH_COLUMNS + GLYPH_SPACING;

    /**
     * 一行文字的排版行高（行数），恒定等于中文点阵的高度。
     *
     * <p>取值等于 {@link CjkFont#ROWS} 而不是直接写 12：两边必须一致，
     * 否则 ASCII 的居中偏移会与中文高度脱节。
     */
    public static final int LINE_ROWS = CjkFont.ROWS;

    /**
     * ASCII 的 7 行字形在 {@link #LINE_ROWS} 行行高里的垂直偏移。
     *
     * <p>= (12 − 7) / 2 下取整 = 3：上方留 3 行、下方留 2 行。
     * 之所以取整后偏上而不是偏下，是因为汉字的字面（视觉重心）本来就略偏上。
     */
    public static final int ASCII_ROW_OFFSET = 3;

    private static final int GLYPH_COUNT = LAST_CHAR - FIRST_CHAR + 1;

    /**
     * 点阵数据：{@code GLYPH_COUNT × GLYPH_COLUMNS} 字节，列优先。
     *
     * <p>每行注释标出该行第一个字节对应的字符，便于定位写错的字形。
     */
    private static final byte[] GLYPHS = {
            // ' ' .. '/'
            0x00, 0x00, 0x00, 0x00, 0x00,   // (32) ' '
            0x00, 0x00, 0x5F, 0x00, 0x00,   // (33) '!'
            0x00, 0x07, 0x00, 0x07, 0x00,   // (34) '"'
            0x14, 0x7F, 0x14, 0x7F, 0x14,   // (35) '#'
            0x24, 0x2A, 0x7F, 0x2A, 0x12,   // (36) '$'
            0x23, 0x13, 0x08, 0x64, 0x62,   // (37) '%'
            0x36, 0x49, 0x55, 0x22, 0x50,   // (38) '&'
            0x00, 0x05, 0x03, 0x00, 0x00,   // (39) '\''
            0x00, 0x1C, 0x22, 0x41, 0x00,   // (40) '('
            0x00, 0x41, 0x22, 0x1C, 0x00,   // (41) ')'
            0x14, 0x08, 0x3E, 0x08, 0x14,   // (42) '*'
            0x08, 0x08, 0x3E, 0x08, 0x08,   // (43) '+'
            0x00, 0x50, 0x30, 0x00, 0x00,   // (44) ','
            0x08, 0x08, 0x08, 0x08, 0x08,   // (45) '-'
            0x00, 0x60, 0x60, 0x00, 0x00,   // (46) '.'
            0x20, 0x10, 0x08, 0x04, 0x02,   // (47) '/'

            // '0' .. '?'
            0x3E, 0x51, 0x49, 0x45, 0x3E,   // (48) '0'
            0x00, 0x42, 0x7F, 0x40, 0x00,   // (49) '1'
            0x42, 0x61, 0x51, 0x49, 0x46,   // (50) '2'
            0x21, 0x41, 0x45, 0x4B, 0x31,   // (51) '3'
            0x18, 0x14, 0x12, 0x7F, 0x10,   // (52) '4'
            0x27, 0x45, 0x45, 0x45, 0x39,   // (53) '5'
            0x3C, 0x4A, 0x49, 0x49, 0x30,   // (54) '6'
            0x01, 0x71, 0x09, 0x05, 0x03,   // (55) '7'
            0x36, 0x49, 0x49, 0x49, 0x36,   // (56) '8'
            0x06, 0x49, 0x49, 0x29, 0x1E,   // (57) '9'
            0x00, 0x36, 0x36, 0x00, 0x00,   // (58) ':'
            0x00, 0x56, 0x36, 0x00, 0x00,   // (59) ';'
            0x08, 0x14, 0x22, 0x41, 0x00,   // (60) '<'
            0x14, 0x14, 0x14, 0x14, 0x14,   // (61) '='
            0x00, 0x41, 0x22, 0x14, 0x08,   // (62) '>'
            0x02, 0x01, 0x51, 0x09, 0x06,   // (63) '?'

            // '@' .. 'O'
            0x32, 0x49, 0x79, 0x41, 0x3E,   // (64) '@'
            0x7E, 0x11, 0x11, 0x11, 0x7E,   // (65) 'A'
            0x7F, 0x49, 0x49, 0x49, 0x36,   // (66) 'B'
            0x3E, 0x41, 0x41, 0x41, 0x22,   // (67) 'C'
            0x7F, 0x41, 0x41, 0x22, 0x1C,   // (68) 'D'
            0x7F, 0x49, 0x49, 0x49, 0x41,   // (69) 'E'
            0x7F, 0x09, 0x09, 0x09, 0x01,   // (70) 'F'
            0x3E, 0x41, 0x49, 0x49, 0x7A,   // (71) 'G'
            0x7F, 0x08, 0x08, 0x08, 0x7F,   // (72) 'H'
            0x00, 0x41, 0x7F, 0x41, 0x00,   // (73) 'I'
            0x20, 0x40, 0x41, 0x3F, 0x01,   // (74) 'J'
            0x7F, 0x08, 0x14, 0x22, 0x41,   // (75) 'K'
            0x7F, 0x40, 0x40, 0x40, 0x40,   // (76) 'L'
            0x7F, 0x02, 0x0C, 0x02, 0x7F,   // (77) 'M'
            0x7F, 0x04, 0x08, 0x10, 0x7F,   // (78) 'N'
            0x3E, 0x41, 0x41, 0x41, 0x3E,   // (79) 'O'

            // 'P' .. '_'
            0x7F, 0x09, 0x09, 0x09, 0x06,   // (80) 'P'
            0x3E, 0x41, 0x51, 0x21, 0x5E,   // (81) 'Q'
            0x7F, 0x09, 0x19, 0x29, 0x46,   // (82) 'R'
            0x46, 0x49, 0x49, 0x49, 0x31,   // (83) 'S'
            0x01, 0x01, 0x7F, 0x01, 0x01,   // (84) 'T'
            0x3F, 0x40, 0x40, 0x40, 0x3F,   // (85) 'U'
            0x1F, 0x20, 0x40, 0x20, 0x1F,   // (86) 'V'
            0x7F, 0x20, 0x18, 0x20, 0x7F,   // (87) 'W'
            0x63, 0x14, 0x08, 0x14, 0x63,   // (88) 'X'
            0x03, 0x04, 0x78, 0x04, 0x03,   // (89) 'Y'
            0x61, 0x51, 0x49, 0x45, 0x43,   // (90) 'Z'
            0x00, 0x7F, 0x41, 0x41, 0x00,   // (91) '['
            0x02, 0x04, 0x08, 0x10, 0x20,   // (92) '\\'
            0x00, 0x41, 0x41, 0x7F, 0x00,   // (93) ']'
            0x04, 0x02, 0x01, 0x02, 0x04,   // (94) '^'
            0x40, 0x40, 0x40, 0x40, 0x40,   // (95) '_'

            // '`' .. 'o'
            0x00, 0x01, 0x02, 0x04, 0x00,   // (96) '`'
            0x20, 0x54, 0x54, 0x54, 0x78,   // (97) 'a'
            0x7F, 0x48, 0x44, 0x44, 0x38,   // (98) 'b'
            0x38, 0x44, 0x44, 0x44, 0x20,   // (99) 'c'
            0x38, 0x44, 0x44, 0x48, 0x7F,   // (100) 'd'
            0x38, 0x54, 0x54, 0x54, 0x18,   // (101) 'e'
            0x08, 0x7E, 0x09, 0x01, 0x02,   // (102) 'f'
            0x0C, 0x52, 0x52, 0x52, 0x3E,   // (103) 'g'
            0x7F, 0x08, 0x04, 0x04, 0x78,   // (104) 'h'
            0x00, 0x44, 0x7D, 0x40, 0x00,   // (105) 'i'
            0x20, 0x40, 0x44, 0x3D, 0x00,   // (106) 'j'
            0x7F, 0x10, 0x28, 0x44, 0x00,   // (107) 'k'
            0x00, 0x41, 0x7F, 0x40, 0x00,   // (108) 'l'
            0x7C, 0x04, 0x18, 0x04, 0x78,   // (109) 'm'
            0x7C, 0x08, 0x04, 0x04, 0x78,   // (110) 'n'
            0x38, 0x44, 0x44, 0x44, 0x38,   // (111) 'o'

            // 'p' .. '~'
            0x7C, 0x14, 0x14, 0x14, 0x08,   // (112) 'p'
            0x08, 0x14, 0x14, 0x18, 0x7C,   // (113) 'q'
            0x7C, 0x08, 0x04, 0x04, 0x08,   // (114) 'r'
            0x48, 0x54, 0x54, 0x54, 0x20,   // (115) 's'
            0x04, 0x3F, 0x44, 0x40, 0x20,   // (116) 't'
            0x3C, 0x40, 0x40, 0x20, 0x7C,   // (117) 'u'
            0x1C, 0x20, 0x40, 0x20, 0x1C,   // (118) 'v'
            0x3C, 0x40, 0x30, 0x40, 0x3C,   // (119) 'w'
            0x44, 0x28, 0x10, 0x28, 0x44,   // (120) 'x'
            0x0C, 0x50, 0x50, 0x50, 0x3C,   // (121) 'y'
            0x44, 0x64, 0x54, 0x4C, 0x44,   // (122) 'z'
            0x00, 0x08, 0x36, 0x41, 0x00,   // (123) '{'
            0x00, 0x00, 0x7F, 0x00, 0x00,   // (124) '|'
            0x00, 0x41, 0x36, 0x08, 0x00,   // (125) '}'
            0x08, 0x04, 0x08, 0x10, 0x08,   // (126) '~'
    };

    private BitmapFont() {
    }

    /** 字模表是否覆盖完整的可打印 ASCII 区间。 */
    public static boolean isAsciiCoverageComplete() {
        return GLYPHS.length == GLYPH_COUNT * GLYPH_COLUMNS;
    }

    public static int glyphCount() {
        return GLYPH_COUNT;
    }

    /**
     * 该字符是否走中文（12×12）字形。
     *
     * <p>判据就是"生成器是否烘焙过它"：{@link CjkFont#has(char)} 不成立的非 ASCII 字符
     * 既没有 ASCII 字形也没有中文字形，会渲染成空白（见类注释的"边界"）。
     */
    public static boolean isCjk(char ch) {
        return CjkFont.has(ch);
    }

    /**
     * 该字符字形占用的列数：中文 {@value CjkFont#COLS} 列，其余 {@value #GLYPH_COLUMNS} 列。
     *
     * <p>空白的字符（无字形）即便返回 5，画出来也是空的 —— 这里只表达"格位宽度"，
     * 不表达"是否有字形"。
     */
    public static int columnCount(char ch) {
        return isCjk(ch) ? CjkFont.COLS : GLYPH_COLUMNS;
    }

    /** 该字符字形占用的行数。恒等于排版行高 —— 见类注释的"12 行行高"口径。 */
    public static int rowCount(char ch) {
        return LINE_ROWS;
    }

    /**
     * 字符 {@code column} 列的点阵字节。
     *
     * <p><b>这是 ASCII 5 列点阵的专用访问器</b>，不是通用渲染入口（渲染请用
     * {@link #pixelAt(char, int, int)} 或 {@link CjkFont#pixel(char, int, int)}）。
     * 越界列返回 0。
     *
     * <p><b>非 ASCII 的语义：</b>对已烘焙的中文，本方法把它 12×12 字形的前 5 列、
     * 前 8 行<b>投影成同样的字节格式</b>（bit i = 第 i 行）—— 也就是"代理到 CJK 点阵的低字节"。
     * <ul>
     *   <li>另一个候选方案是继续回落 {@code '?'}（M1 的行为）。但那样 {@code column('中', c)}
     *       会返回一个与 {@code '中'} 毫无关系的字形，任何基于它的断言都只能证明"回落生效了"，
     *       无法反映"中文确实有字形"这件事。</li>
     *   <li>投影方案让这个老访问器仍<u>确定地</u>对应真实点阵，代价是只覆盖左 5 列 / 上 8 行 ——
     *       它是窄视图，所以真实渲染一律走 {@link #pixelAt(char, int, int)}。</li>
     * </ul>
     * 既没有 ASCII 字形也没有中文字形的字符（控制字符等）仍然回落 {@code '?'}，
     * 而不是抛异常 —— HUD 上出现一个怪字符远好于整个帧因为一个字符而崩掉。
     */
    public static byte column(char ch, int column) {
        if (column < 0 || column >= GLYPH_COLUMNS) {
            return 0;
        }
        if (isCjk(ch)) {
            int bits = 0;
            for (int row = 0; row < Byte.SIZE; row++) {
                if (CjkFont.pixel(ch, column, row)) {
                    bits |= 1 << row;
                }
            }
            return (byte) bits;
        }
        char c = ch;
        if (c < FIRST_CHAR || c > LAST_CHAR) {
            c = '?';
        }
        return GLYPHS[(c - FIRST_CHAR) * GLYPH_COLUMNS + column];
    }

    /**
     * 该字符在<b>字形自身坐标系</b>里第 {@code row} 行、第 {@code column} 列是否点亮。越界返回 false。
     *
     * <p>这里沿用了 M1 的 7 行口径：{@code row} 只接受 0..{@value #GLYPH_ROWS}−1。
     * 对中文来说它只是字形最上面 7 行的窄视图（完整 12 行请读
     * {@link #pixelAt(char, int, int)} 或 {@link CjkFont#pixel(char, int, int)}）。
     *
     * <p>排版请改用 {@link #pixelAt(char, int, int)}，它是"行空间"坐标，中英混排不会错位。
     */
    public static boolean pixel(char ch, int column, int row) {
        if (row < 0 || row >= GLYPH_ROWS) {
            return false;
        }
        return (column(ch, column) & (1 << row)) != 0;
    }

    /**
     * 该字符在<b>行空间</b>（row ∈ 0..{@link #LINE_ROWS}−1）里是否点亮。
     *
     * <p>中文直接映射到 {@link CjkFont}；ASCII 把 {@code row - }{@value #ASCII_ROW_OFFSET}
     * 换算回它自己的 7 行坐标系，从而在 12 行的行高里垂直居中。
     * 越界（含列超出 {@link #columnCount(char)}）一律返回 false。
     */
    public static boolean pixelAt(char ch, int column, int row) {
        if (row < 0 || row >= LINE_ROWS || column < 0 || column >= columnCount(ch)) {
            return false;
        }
        if (isCjk(ch)) {
            return CjkFont.pixel(ch, column, row);
        }
        return pixel(ch, column, row - ASCII_ROW_OFFSET);
    }

    /** 一个字符占用的像素宽度（含字间空隙）。ASCII 的 6 像素等价于 {@code advanceWidth(ch, scale)}。 */
    public static int advanceWidth(int scale) {
        return ADVANCE * scale;
    }

    /** 该字符占用的像素宽度（含字间空隙）：中文 {@value CjkFont#ADVANCE} 列，ASCII {@value #ADVANCE} 列。 */
    public static int advanceWidth(char ch, int scale) {
        return (isCjk(ch) ? CjkFont.ADVANCE : ADVANCE) * scale;
    }

    /**
     * 一行文本的像素宽度：逐字符累加 {@link #advanceWidth(char, int)}，
     * 再扣掉最后一个字符之后的 {@link #GLYPH_SPACING}（末尾不留空隙）。
     *
     * <p>纯 ASCII 输入下这与 M1 的 {@code length × advance - spacing} 完全等价，
     * 中英混排时也自然正确。
     */
    public static int textWidth(String text, int scale) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += advanceWidth(text.charAt(i), scale);
        }
        return total - GLYPH_SPACING * scale;
    }

    /** 单行 ASCII 字形的像素高度（不含行高留白）。背景框之类的旧调用点仍指望它。 */
    public static int textHeight(int scale) {
        return GLYPH_ROWS * scale;
    }

    /** 一行文字的排版高度（含 ASCII 在行高内的居中留白）。新排版代码应当用这个。 */
    public static int lineHeight(int scale) {
        return LINE_ROWS * scale;
    }

    /** 供测试：把某字符渲染成 7 行 × 5 列的文本图（{@code '#'} = 点亮）。 */
    public static String[] toAsciiArt(char ch) {
        String[] rows = new String[GLYPH_ROWS];
        for (int row = 0; row < GLYPH_ROWS; row++) {
            StringBuilder sb = new StringBuilder(GLYPH_COLUMNS);
            for (int column = 0; column < GLYPH_COLUMNS; column++) {
                sb.append(pixel(ch, column, row) ? '#' : '.');
            }
            rows[row] = sb.toString();
        }
        return rows;
    }
}

package com.skyisland.render.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 点阵字模测试（{@link BitmapFont}）。
 *
 * <p><b>编码约定（断言全部建立在这条上）：</b>每字符 {@value BitmapFont#GLYPH_COLUMNS} 个字节、
 * 一字节一列；字节的 <b>bit0 = 最上一行</b>，bit6 = 最下一行。
 * 上下颠倒（bit0 当最下行）是最容易犯又最难看出的错 —— 每个字形都会变成它的垂直镜像，
 * "F" 会看起来像 "E 的某种变体"，肉眼扫过去往往只当是"字体就这样"。
 * 因此这里把 {@code '1'} 与 {@code 'A'} 的点阵<u>逐行写死</u>。
 *
 * <p><b>覆盖完整性的意义：</b>HUD 要显示坐标、FPS、日志消息，任何漏掉的字符都会渲染成
 * 一个方块或空白 —— 而"某个字符没字形"在 5×7 的小尺寸下极难与"字形设计得不好"区分。
 */
class BitmapFontTest {

    // ============================================================ 覆盖范围

    @Test
    void glyphTableCoversTheWholePrintableAsciiRange() {
        assertTrue(BitmapFont.isAsciiCoverageComplete(),
                "字模表长度必须恰好是 (126−32+1) × 5 —— 缺字节会让后面的字形整体串位");
        assertEquals(95, BitmapFont.glyphCount());
        assertEquals(32, BitmapFont.FIRST_CHAR, "第一个字符是空格");
        assertEquals(126, BitmapFont.LAST_CHAR, "最后一个字符是 '~'");
    }

    @Test
    void exactlyOneGlyphIsBlankAndItIsTheSpace() {
        int blank = 0;
        char blankChar = 0;
        for (char c = BitmapFont.FIRST_CHAR; c <= BitmapFont.LAST_CHAR; c++) {
            boolean allEmpty = true;
            for (int column = 0; column < BitmapFont.GLYPH_COLUMNS; column++) {
                if (BitmapFont.column(c, column) != 0) {
                    allEmpty = false;
                    break;
                }
            }
            if (allEmpty) {
                blank++;
                blankChar = c;
            }
        }
        assertEquals(1, blank, "只允许空格是空白字形；出现 " + blank + " 个说明有字形漏填");
        assertEquals(' ', blankChar);
    }

    @Test
    void spaceOccupiesItsAdvanceButDrawsNothing() {
        for (String row : BitmapFont.toAsciiArt(' ')) {
            assertEquals(".....", row, "空格不得点亮任何像素");
        }
    }

    @Test
    void highBitIsNeverUsed() {
        for (char c = BitmapFont.FIRST_CHAR; c <= BitmapFont.LAST_CHAR; c++) {
            for (int column = 0; column < BitmapFont.GLYPH_COLUMNS; column++) {
                int bits = BitmapFont.column(c, column) & 0xFF;
                assertEquals(0, bits & 0x80,
                        "字符 '" + c + "' 第 " + column + " 列用了 bit7 —— 字形只有 7 行");
            }
        }
    }

    // ============================================================ 逐行点阵

    @Test
    void digitOneMatchesThePinnedBitmap() {
        assertArt('1',
                "..#..",
                ".##..",
                "..#..",
                "..#..",
                "..#..",
                "..#..",
                ".###.");
    }

    @Test
    void letterAMatchesThePinnedBitmap() {
        assertArt('A',
                ".###.",
                "#...#",
                "#...#",
                "#...#",
                "#####",
                "#...#",
                "#...#");
    }

    private static void assertArt(char ch, String... expectedRows) {
        String[] actual = BitmapFont.toAsciiArt(ch);
        assertEquals(expectedRows.length, actual.length);
        for (int row = 0; row < expectedRows.length; row++) {
            assertEquals(expectedRows[row], actual[row],
                    "字符 '" + ch + "' 第 " + row + " 行不符（bit0 必须是最上一行）");
        }
    }

    // ============================================================ 越界降级

    @Test
    void outOfRangeColumnReadsZero() {
        assertEquals(0, BitmapFont.column('A', -1));
        assertEquals(0, BitmapFont.column('A', BitmapFont.GLYPH_COLUMNS));
        assertEquals(0, BitmapFont.column('A', 100));
    }

    @Test
    void outOfRangeRowReadsUnlit() {
        assertFalse(BitmapFont.pixel('A', 0, -1));
        assertFalse(BitmapFont.pixel('A', 0, BitmapFont.GLYPH_ROWS));
        assertTrue(BitmapFont.pixel('A', 2, 4), "'A' 的第 4 行应有一条横杠");
    }

    @Test
    void unprintableCharactersFallBackToQuestionMark() {
        for (int column = 0; column < BitmapFont.GLYPH_COLUMNS; column++) {
            assertEquals(BitmapFont.column('?', column), BitmapFont.column('\n', column),
                    "换行符等不可打印字符必须回落到 '?' 而不是抛异常");
            assertEquals(BitmapFont.column('?', column), BitmapFont.column((char) 127, column));
        }
    }

    @Test
    void cjkCharactersAreProjectedIntoTheLegacyFiveColumnView() {
        // 这条断言在 M1 的写法是 assertEquals(column('?', c), column('中', c))，
        // 即"非 ASCII 一律回落 '?'"——那是当时只支持 ASCII 的固化。
        // M2 起 '中' 有真实字形，这个 5 列 ASCII 视口改为代理到 CJK 点阵的前 5 列 / 前 8 行
        // （bit i = 第 i 行），因此它必须等于 CJK 字形的低字节投影，而不再等于 '?'。
        boolean anyDiffersFromQuestionMark = false;
        for (int column = 0; column < BitmapFont.GLYPH_COLUMNS; column++) {
            int projected = 0;
            for (int row = 0; row < Byte.SIZE; row++) {
                if (CjkFont.pixel('中', column, row)) {
                    projected |= 1 << row;
                }
            }
            assertEquals((byte) projected, BitmapFont.column('中', column),
                    "第 " + column + " 列应当是 CJK 字形前 8 行的投影");
            if (BitmapFont.column('中', column) != BitmapFont.column('?', column)) {
                anyDiffersFromQuestionMark = true;
            }
        }
        assertTrue(anyDiffersFromQuestionMark,
                "至少有一列应当与 '?' 不同，否则说明代理没生效、仍然是老的回落行为");
    }

    // ============================================================ CJK 与 12 行行高

    @Test
    void cjkGlyphsUseTwelveColumnsWhileAsciiKeepsFive() {
        assertTrue(BitmapFont.isCjk('中'));
        assertFalse(BitmapFont.isCjk('A'), "'A' 走 ASCII 字形");
        assertFalse(BitmapFont.isCjk('\n'), "未烘焙字符不是 CJK");

        assertEquals(CjkFont.COLS, BitmapFont.columnCount('中'));
        assertEquals(BitmapFont.GLYPH_COLUMNS, BitmapFont.columnCount('A'));

        // 行高统一成 12：中英混排不能各排各的
        assertEquals(CjkFont.ROWS, BitmapFont.LINE_ROWS);
        assertEquals(BitmapFont.LINE_ROWS, BitmapFont.rowCount('中'));
        assertEquals(BitmapFont.LINE_ROWS, BitmapFont.rowCount('A'));
        assertEquals(12, BitmapFont.lineHeight(1));
        assertEquals(24, BitmapFont.lineHeight(2));
        // 旧的 textHeight 只描述 ASCII 字形本身的高度，不能跟着改成 12
        assertEquals(7, BitmapFont.textHeight(1));
    }

    @Test
    void asciiGlyphsAreVerticallyCentredInsideTheLineBox() {
        assertEquals(3, BitmapFont.ASCII_ROW_OFFSET);
        // ASCII 在行空间里的位置 = 自身行 + 偏移；行空间 0..2 与 10..11 必须空着
        for (int row = 0; row < BitmapFont.LINE_ROWS; row++) {
            int glyphRow = row - BitmapFont.ASCII_ROW_OFFSET;
            boolean inGlyph = glyphRow >= 0 && glyphRow < BitmapFont.GLYPH_ROWS;
            assertEquals(inGlyph && BitmapFont.pixel('A', 0, glyphRow),
                    BitmapFont.pixelAt('A', 0, row),
                    "'A' 第 0 列第 " + row + " 行的行空间取值不符");
        }
        for (int row = 0; row < BitmapFont.ASCII_ROW_OFFSET; row++) {
            assertFalse(BitmapFont.pixelAt('A', 0, row), "字形上方应留白");
        }
        for (int row = BitmapFont.ASCII_ROW_OFFSET + BitmapFont.GLYPH_ROWS;
                row < BitmapFont.LINE_ROWS; row++) {
            assertFalse(BitmapFont.pixelAt('A', 0, row), "字形下方应留白");
        }
    }

    @Test
    void cjkPixelsComeStraightFromTheBakedGlyph() {
        for (int column = 0; column < CjkFont.COLS; column++) {
            for (int row = 0; row < CjkFont.ROWS; row++) {
                assertEquals(CjkFont.pixel('中', column, row),
                        BitmapFont.pixelAt('中', column, row),
                        "'中' 第 " + column + " 列第 " + row + " 行");
            }
        }
    }

    @Test
    void outOfRangeRowSpaceCoordinatesReadUnlit() {
        assertFalse(BitmapFont.pixelAt('A', -1, 0));
        assertFalse(BitmapFont.pixelAt('A', BitmapFont.GLYPH_COLUMNS, 0),
                "列数超出该字符的 columnCount 就该是空白");
        assertFalse(BitmapFont.pixelAt('\n', 0, 0), "未烘焙字符整格空白");
    }

    @Test
    void mixedTextWidthAddsUpPerCharacter() {
        assertEquals(13, BitmapFont.advanceWidth('中', 1), "中文占 12 列 + 1 列空隙");
        assertEquals(26, BitmapFont.advanceWidth('中', 2));
        assertEquals(6, BitmapFont.advanceWidth('A', 1));

        // 纯 ASCII 的结果与 M1 的 length × advance − spacing 完全一致
        assertEquals(5, BitmapFont.textWidth("A", 1));
        assertEquals(11, BitmapFont.textWidth("AB", 1));
        assertEquals(34, BitmapFont.textWidth("ABC", 2));

        // 混排：13 + 6 − 1 = 18
        assertEquals(18, BitmapFont.textWidth("中A", 1));
        // 全中文：13 + 13 − 1 = 25
        assertEquals(25, BitmapFont.textWidth("中文", 1));
    }

    // ============================================================ 尺寸计算

    @Test
    void textMetricsFollowTheDocumentedFormula() {
        assertEquals(5, BitmapFont.GLYPH_COLUMNS);
        assertEquals(7, BitmapFont.GLYPH_ROWS);
        assertEquals(1, BitmapFont.GLYPH_SPACING);
        assertEquals(6, BitmapFont.ADVANCE, "一个字符占 5 列 + 1 列空隙");

        assertEquals(6, BitmapFont.advanceWidth(1));
        assertEquals(12, BitmapFont.advanceWidth(2));

        // 末字符后面不留空隙：n 个字符的宽度 = n × advance − spacing
        assertEquals(5, BitmapFont.textWidth("A", 1));
        assertEquals(11, BitmapFont.textWidth("AB", 1));
        assertEquals(34, BitmapFont.textWidth("ABC", 2));

        assertEquals(0, BitmapFont.textWidth("", 1));
        assertEquals(0, BitmapFont.textWidth(null, 1));

        assertEquals(7, BitmapFont.textHeight(1));
        assertEquals(14, BitmapFont.textHeight(2));
    }

    @Test
    void textWidthGrowsMonotonicallyWithScale() {
        String sample = "FPS 60 (0.5, 64.0, 0.5)";
        int previous = 0;
        for (int scale = 1; scale <= 4; scale++) {
            int width = BitmapFont.textWidth(sample, scale);
            assertTrue(width > previous, "缩放 " + scale + " 的宽度必须更大");
            previous = width;
        }
    }
}

package com.skyisland.render.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中文点阵字模测试（{@link CjkFont}）。
 *
 * <p><b>这里最重要的是一条"护栏"而不是某条精度断言：</b>
 * 中文字模是<i>扫描源码</i>烘出来的，所以"以后有人新写一句中文、却忘了重跑生成器"是这套方案
 * 唯一真正的失败模式 —— 而且它不会报错，只会在游戏里悄悄渲染成空白。
 * {@link #everyNonAsciiCharUsedInSourcesHasAGlyph()} 就是拦这件事的。
 *
 * <p><b>结构断言不写死形状：</b>字模由字体光栅化而来，换字体/换字号就会变。
 * 所以这里只断言"形状的性质"（横画集中在少数行且跨度宽、口字四边都有墨、日比口墨多），
 * 而不是逐点比对 —— 逐点比对会把测试变成一份"当前字体的快照"，改字体就碎。
 */
class CjkFontTest {

    /** 源码根，与生成器（{@code tools/fontgen/GenCjkFont.java}）扫描的是同一批目录。 */
    private static final Path[] SOURCE_ROOTS = {
        Paths.get("src/main/java"),
        Paths.get("src/test/java"),
    };

    // ============================================================ 覆盖护栏

    @Test
    void everyNonAsciiCharUsedInSourcesHasAGlyph() throws IOException {
        Set<Character> used = new TreeSet<>();
        for (Path root : SOURCE_ROOTS) {
            assertTrue(Files.isDirectory(root),
                    root + " 不是目录 —— 护栏必须在项目根目录下运行，否则它会「扫不到东西而假通过」");
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < text.length(); i++) {
                        char ch = text.charAt(i);
                        if (isBakeable(ch)) {
                            used.add(ch);
                        }
                    }
                }
            }
        }

        // 先确认扫描本身有效：如果口径写错导致一个字符都没扫到，下面的断言会"空集通过"
        assertTrue(used.size() > 500,
                "只在源码里扫到 " + used.size() + " 个非 ASCII 字符，明显不对，先检查扫描口径");

        List<String> missing = new ArrayList<>();
        for (char ch : used) {
            if (!CjkFont.has(ch)) {
                missing.add(String.format("%c(U+%04X)", ch, (int) ch));
            }
        }
        assertTrue(missing.isEmpty(),
                "以下 " + missing.size() + " 个字符出现在源码里但没有字形，运行时会渲染成空白。"
                        + "新增中文文案后必须重跑 tools/fontgen/GenCjkFont.java："
                        + String.join(" ", missing));
    }

    /**
     * 需要烘焙的字符口径，必须与生成器一致：{@code >= 0x00A0} 的 BMP 字符，
     * 排除代理区（单个 char 不是完整字符）与零宽/格式符（没有可见字形）。
     */
    private static boolean isBakeable(char ch) {
        if (ch < 0x00A0) {
            return false;
        }
        if (ch >= 0xD800 && ch <= 0xDFFF) {
            return false;
        }
        if (ch == '\uFEFF' || ch == '\u00AD') {
            return false;
        }
        return ch < 0x200B || ch > 0x200F;
    }

    // ============================================================ 字符集本身

    @Test
    void charsetIsSortedAscendingAndFreeOfDuplicates() {
        String charset = CjkFont.charset();
        assertTrue(charset.length() > 0);
        for (int i = 1; i < charset.length(); i++) {
            char previous = charset.charAt(i - 1);
            char current = charset.charAt(i);
            assertTrue(previous < current,
                    "charset() 必须严格升序且不重复，第 " + i + " 位是 "
                            + describe(previous) + " 之后又出现了 " + describe(current)
                            + " —— 二分查找依赖升序，重排会让 has()/pixel() 静默失效");
        }
    }

    @Test
    void glyphCountMatchesCharsetLength() {
        assertEquals(CjkFont.charset().length(), CjkFont.glyphCount(),
                "glyphCount() 与 charset().length() 必须一致，否则数据表与字符集已经对不上了");
    }

    @Test
    void noGlyphIsBlank() {
        String charset = CjkFont.charset();
        List<String> blank = new ArrayList<>();
        for (int i = 0; i < charset.length(); i++) {
            if (litCount(charset.charAt(i)) == 0) {
                blank.add(describe(charset.charAt(i)));
            }
        }
        assertTrue(blank.isEmpty(),
                "以下字形一个亮点都没有（等于运行时是空白）：" + String.join(" ", blank));
    }

    @Test
    void sourceFontIsRecordedForTheReport() {
        assertTrue(CjkFont.sourceFont().contains("px"),
                "sourceFont() 要能说明用的是哪个字体、哪个字号，便于出问题时回溯：" + CjkFont.sourceFont());
    }

    // ============================================================ 结构性抽样

    @Test
    void singleStrokeCharactersHaveOneWideRowEach() {
        for (char ch : new char[]{'一', '二', '三'}) {
            assertTrue(litCount(ch) > 0, describe(ch) + " 必须有字形");
            int rowsWithInk = 0;
            int widest = 0;
            for (int row = 0; row < CjkFont.ROWS; row++) {
                int lit = rowLitCount(ch, row);
                if (lit > 0) {
                    rowsWithInk++;
                }
                widest = Math.max(widest, rowSpan(ch, row));
            }
            assertTrue(rowsWithInk <= 3,
                    describe(ch) + " 只应有少数几条横画，实测占了 " + rowsWithInk + " 行");
            assertTrue(widest >= 8,
                    describe(ch) + " 的横画应当很宽，实测最宽的一行只有 " + widest + " 列");
        }
    }

    @Test
    void mouthCharacterHasInkOnAllFourBorders() {
        char ch = '口';
        int rowsWithInk = 0;
        int columnsWithInk = 0;
        for (int row = 0; row < CjkFont.ROWS; row++) {
            if (rowLitCount(ch, row) >= 8) {
                rowsWithInk++;
            }
        }
        for (int column = 0; column < CjkFont.COLS; column++) {
            if (columnLitCount(ch, column) >= 8) {
                columnsWithInk++;
            }
        }
        assertTrue(rowsWithInk >= 2, "'口' 上下两条横画应当各占满一行，实测只有 " + rowsWithInk + " 条");
        assertTrue(columnsWithInk >= 2, "'口' 左右两条竖画应当各占满一列，实测只有 " + columnsWithInk + " 条");
    }

    @Test
    void sunCharacterHasMoreInkThanMouthCharacter() {
        assertTrue(litCount('日') > litCount('口'),
                "'日' 比 '口' 多一条中间横画，点亮像素必须更多：日=" + litCount('日')
                        + " 口=" + litCount('口'));
    }

    @Test
    void glyphsSitInsideTheBoxAndAreNotSolid() {
        String charset = CjkFont.charset();
        for (int i = 0; i < charset.length(); i++) {
            char ch = charset.charAt(i);
            int lit = litCount(ch);
            assertTrue(lit < CjkFont.COLS * CjkFont.ROWS,
                    describe(ch) + " 整个 12×12 都点亮了 —— 这是「把包围盒填成实心」的典型症状");
        }
    }

    // ============================================================ 越界与未烘焙

    @Test
    void outOfRangeCoordinatesReadUnlit() {
        char ch = '中';
        assertFalse(CjkFont.pixel(ch, -1, 0));
        assertFalse(CjkFont.pixel(ch, CjkFont.COLS, 0));
        assertFalse(CjkFont.pixel(ch, 0, -1));
        assertFalse(CjkFont.pixel(ch, 0, CjkFont.ROWS));
        assertTrue(CjkFont.has(ch), "'中' 必须已烘焙，否则上面的断言全都恒真、测不到东西");
    }

    @Test
    void unbakedCharactersReadUnlitAndAreNotSubstituted() {
        char unbaked = 0;
        for (char c = 0x9FA0; c < 0x9FFF; c++) {
            if (!CjkFont.has(c)) {
                unbaked = c;
                break;
            }
        }
        assertTrue(unbaked != 0, "应当能在 CJK 区尾部找到一个未烘焙字符，否则这个测试没意义");

        for (char ch : new char[]{unbaked, '\n', '\t', 0}) {
            assertFalse(CjkFont.has(ch), describe(ch) + " 不应有字形");
            for (int column = 0; column < CjkFont.COLS; column++) {
                for (int row = 0; row < CjkFont.ROWS; row++) {
                    assertFalse(CjkFont.pixel(ch, column, row),
                            describe(ch) + " 没有字形，必须整格空白（不回落 '?'）：第 "
                                    + column + " 列第 " + row + " 行不该点亮");
                }
            }
        }
    }

    // ============================================================ 常量

    @Test
    void constantsMatchTheDocumentedEncoding() {
        assertEquals(12, CjkFont.COLS);
        assertEquals(12, CjkFont.ROWS);
        assertEquals(2, CjkFont.BYTES_PER_COL, "ceil(12 / 8) = 2");
        assertEquals(1, CjkFont.GLYPH_SPACING);
        assertEquals(13, CjkFont.ADVANCE, "12 列 + 1 列空隙");
    }

    // ============================================================ 小工具

    private static int litCount(char ch) {
        int total = 0;
        for (int column = 0; column < CjkFont.COLS; column++) {
            total += columnLitCount(ch, column);
        }
        return total;
    }

    private static int rowLitCount(char ch, int row) {
        int total = 0;
        for (int column = 0; column < CjkFont.COLS; column++) {
            if (CjkFont.pixel(ch, column, row)) {
                total++;
            }
        }
        return total;
    }

    private static int columnLitCount(char ch, int column) {
        int total = 0;
        for (int row = 0; row < CjkFont.ROWS; row++) {
            if (CjkFont.pixel(ch, column, row)) {
                total++;
            }
        }
        return total;
    }

    /** 该行墨迹的首末列跨度（无墨返回 0）。 */
    private static int rowSpan(char ch, int row) {
        int first = -1;
        int last = -1;
        for (int column = 0; column < CjkFont.COLS; column++) {
            if (CjkFont.pixel(ch, column, row)) {
                if (first < 0) {
                    first = column;
                }
                last = column;
            }
        }
        return first < 0 ? 0 : last - first + 1;
    }

    private static String describe(char ch) {
        return String.format("%c(U+%04X)", ch, (int) ch);
    }
}

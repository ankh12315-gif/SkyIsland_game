import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 离线烘焙简体中文点阵字库，产出 {@code src/main/java/com/skyisland/render/ui/CjkFont.java}。
 *
 * <p><b>为什么用"离线烘焙成 Java 源码"而不是"运行期加载 TTF"：</b>
 * 见 {@link com.skyisland.render.ui.BitmapFont} 的类注释 —— 本项目明确不引入 FreeType/STB
 * （成本远超收益），也不引入 PNG 图集（会把 TextureArray 资源管线提前拉进来）。
 * 把点阵数据烘进代码里，就不存在"资源丢失导致 HUD 整片空白"这种失败模式，
 * 而且生成结果完全确定、可被单元测试逐个字形校验。
 *
 * <p><b>为什么字符集必须扫描源码得到：</b>手写字符集一定会漏，而"漏一个字符"
 * 的表现是该字渲染成空白（不是报错），是最难在实机里发现的回归。
 * 因此这里递归扫描 {@code src/main/java} 与 {@code src/test/java} 里所有 {@code .java} 文件，
 * 把出现过的非 ASCII 字符全部纳入。
 *
 * <p><b>用法：</b>在项目根目录执行 {@code java tools/fontgen/GenCjkFont.java}。
 * 可选第一个参数指定项目根目录。有任何字符烘焙失败（空白字形）时以非 0 退出，
 * 避免把"漏字"悄悄提交出去。
 */
public final class GenCjkFont {

    /** 字形尺寸：12 列 × 12 行。 */
    private static final int BOX = 12;

    /** 每列占用的字节数 = ceil(12 / 8)。 */
    private static final int BYTES_PER_COL = 2;

    /**
     * 抗锯齿灰度二值化的阈值（0.5 × 255）。
     *
     * <p>取 0.5 而不是更低的值：阈值越低越容易把笔画之外的光晕算成实心，
     * 12×12 下相邻笔画会糊在一起（"日"会变成"目"）。
     */
    private static final int ALPHA_THRESHOLD = 128;

    /** 烘焙画布的留白：字形先画在放大的画布上，量出真实墨迹包围盒后再平移进 12×12。 */
    private static final int PAD = 10;

    /** 字体优先级（同组内是同一字体的不同语言名，取第一个能在本机解析出来的）。 */
    private static final String[][] FONT_PRIORITY = {
        {"Microsoft YaHei", "微软雅黑"},
        {"SimHei", "黑体"},
        {"DengXian", "Deng", "等线"},
        {"SimSun", "宋体"},
    };

    /**
     * 链尾的符号补字体。
     *
     * <p>实测 Windows 上四个中文字体都不含 {@code ⇒(U+21D2)}、{@code ▶(U+25B6)}、
     * {@code ✓(U+2713)}、{@code ⟷(U+27F7)}；它们只是散落在源码注释里的示意图符号，
     * 但仍然属于"源码里出现过"的字符，必须有自己的字形。Segoe UI Symbol 覆盖这 4 个，
     * 且是 Windows 自带字体，不引入任何外部资产。
     */
    private static final String SYMBOL_FONT = "Segoe UI Symbol";

    /** 候选字号。太小则笔画糊成一团，太大则装不进 12×12，取这个区间逐号实测。 */
    private static final int[] SIZE_CANDIDATES = {12, 13, 14, 15};

    /** 个别密字允许降到的下限字号（见 main 第 4 步）。 */
    private static final int MIN_SIZE = 10;

    /** 生成文件里每块内联的字形数：单方法的字节码上限是 64KB，块太大就编不过。 */
    private static final int CHUNK_GLYPHS_DEFAULT = 256;

    private static final int FIT_OK = 0;
    private static final int FIT_BLANK = 1;
    private static final int FIT_OVERFLOW = 2;

    private GenCjkFont() {
    }

    public static void main(String[] args) throws IOException {
        // 控制台默认编码是 GBK，而本报告几乎全是中文；固定成 UTF-8 才能被上层工具正确读出
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        Path projectRoot = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path mainRoot = projectRoot.resolve("src/main/java");
        Path testRoot = projectRoot.resolve("src/test/java");
        Path output = mainRoot.resolve("com/skyisland/render/ui/CjkFont.java");

        // 1) 扫描源码得到字符集
        List<Path> sources = new ArrayList<>();
        collectSources(mainRoot, sources);
        collectSources(testRoot, sources);
        char[] charset = collectCharset(sources);
        System.out.printf("扫描 .java 文件 %d 个，得到待烘焙字符 %d 个%n", sources.size(), charset.length);

        // 2) 解析字体链。可用性判定不能看字体家族名列表 —— 中文本机返回的是本地化名（"微软雅黑"），
        //    而 Java 的字体配置能解析出英文别名。因此改为"构造一次，看是否被替换成逻辑字体 Dialog"。
        List<String> chain = new ArrayList<>();
        for (String[] group : FONT_PRIORITY) {
            String resolved = null;
            for (String alias : group) {
                Font probe = new Font(alias, Font.PLAIN, SIZE_CANDIDATES[0]);
                if (!"Dialog".equals(probe.getFamily())) {
                    resolved = alias;
                    break;
                }
            }
            if (resolved == null) {
                System.out.printf("字体 '%s' 未安装，跳过%n", group[0]);
            } else {
                chain.add(resolved);
            }
        }
        // 中文字体普遍不含 ⇒ ▶ ✓ ⟷ 这类符号（实测 YaHei/SimHei/DengXian/SimSun 四个都没有），
        // 因此在链尾补一个符号字体。回退是<b>逐字符</b>的：每个字符取链上第一个能显示它的字体，
        // 这样"某个符号没字形"就不会让整批烘焙失败，也不会逼我们把源码里的符号从字符集里删掉。
        chain.add(SYMBOL_FONT);
        String primaryFamily = chain.get(0);
        System.out.printf("字符集覆盖检查（字体链：%s）%n", String.join(" -> ", chain));

        String[] owner = new String[charset.length];
        List<String> uncovered = new ArrayList<>();
        for (int i = 0; i < charset.length; i++) {
            for (String family : chain) {
                if (new Font(family, Font.PLAIN, SIZE_CANDIDATES[0]).canDisplay(charset[i])) {
                    owner[i] = family;
                    break;
                }
            }
            if (owner[i] == null) {
                uncovered.add(describe(charset[i]));
            }
        }
        for (String family : chain) {
            int count = 0;
            for (String o : owner) {
                if (family.equals(o)) {
                    count++;
                }
            }
            System.out.printf("  %-16s 负责 %d 个字符%n", family, count);
        }
        if (!uncovered.isEmpty()) {
            System.err.printf("字体链无法显示 %d 个字符：%s%n", uncovered.size(),
                    String.join(" ", uncovered));
            System.err.println("请把能显示它们的字体加进 FONT_PRIORITY 后重跑。");
            System.exit(1);
        }

        // 3) 选字号：让"能完整落进 12×12"的字符最多；同分取更大的字号（框内细节更多、更易辨认）
        int bestSize = -1;
        int bestFit = -1;
        boolean[] grid = new boolean[BOX * BOX];
        for (int size : SIZE_CANDIDATES) {
            int fit = 0;
            for (int i = 0; i < charset.length; i++) {
                if (bake(new Font(owner[i], Font.PLAIN, size), charset[i], grid) == FIT_OK) {
                    fit++;
                }
            }
            System.out.printf("字号 %2dpx：%d/%d 个字形完整落进 %d×%d%n",
                    size, fit, charset.length, BOX, BOX);
            if (fit >= bestFit) {
                bestFit = fit;
                bestSize = size;
            }
        }
        System.out.printf("选定字体：%s %dpx（另有 %d 个符号由回退字体承担）%n",
                primaryFamily, bestSize, charset.length - countOwned(owner, primaryFamily));

        // 4) 正式烘焙。
        //    12px 下仍有 30 来个笔画极密的字（塞/墙/藏/魔…）的墨迹会超出 12×12。
        //    对它们按 11→10 逐级降号重烘，而不是裁掉笔画或标成空白 ——
        //    密字缩小一号正是手工点阵字库的常规做法，视力上也更像"这个字本来就密"。
        byte[][] data = new byte[charset.length][];
        List<String> blanks = new ArrayList<>();
        List<String> overflows = new ArrayList<>();
        int shrunk = 0;
        for (int i = 0; i < charset.length; i++) {
            char ch = charset[i];
            for (int size = bestSize; size >= MIN_SIZE; size--) {
                int fit = bake(new Font(owner[i], Font.PLAIN, size), ch, grid);
                if (fit == FIT_BLANK) {
                    blanks.add(describe(ch) + "/" + owner[i]);
                    break;
                }
                if (fit == FIT_OK) {
                    data[i] = encode(grid);
                    if (size < bestSize) {
                        shrunk++;
                    }
                    break;
                }
                if (size == MIN_SIZE) {
                    overflows.add(describe(ch) + "/" + owner[i]);
                }
            }
        }

        // 5) QA 报告
        int[] lit = new int[charset.length];
        int baked = 0;
        for (int i = 0; i < charset.length; i++) {
            if (data[i] == null) {
                continue;
            }
            baked++;
            int n = 0;
            for (byte b : data[i]) {
                n += Integer.bitCount(b & 0xFF);
            }
            lit[i] = n;
        }
        int[] bakedLit = Arrays.copyOf(lit, baked);
        System.out.println("---- QA 报告 ----");
        System.out.printf("字符集大小 : %d%n", charset.length);
        System.out.printf("主字体     : %s %dpx%n", primaryFamily, bestSize);
        System.out.printf("实际落点   : %s%n", describeOwners(owner, chain));
        System.out.printf("空白字形   : %d %s%n", blanks.size(), String.join(" ", blanks));
        System.out.printf("溢出字形   : %d %s%n", overflows.size(), String.join(" ", overflows));
        System.out.printf("降号字形   : %d 个（%dpx 装不下，按 11/10px 重烘）%n", shrunk, bestSize);
        System.out.printf("点亮像素   : min=%d median=%d max=%d（仅统计已烘焙字形）%n",
                min(bakedLit), median(bakedLit), max(bakedLit));
        System.out.println("-----------------");
        printThinnest(charset, data, lit);
        printSamples(charset, data);

        if (!blanks.isEmpty() || !overflows.isEmpty()) {
            System.err.printf("烘焙失败：空白 %d 个、溢出 %d 个。请更换字体或调整字号后重跑，"
                    + "不允许把渲染为空的字形提交进仓库。%n", blanks.size(), overflows.size());
            System.exit(1);
        }

        // 6) 生成 CjkFont.java
        String sourceFont = primaryFamily + " " + bestSize + "px"
                + (chain.size() > 1 ? "（" + describeOwners(owner, chain) + "）" : "");
        String source = renderSource(sourceFont, charset, data);
        Files.createDirectories(output.getParent());
        Files.write(output, source.getBytes(StandardCharsets.UTF_8));
        System.out.printf("已生成 %s（%d 字节）%n", output, source.getBytes(StandardCharsets.UTF_8).length);
    }

    /** 统计每个字体在字符集里承担的字符数，形如 {@code 微软雅黑 1308 / Segoe UI Symbol 4}。 */
    private static String describeOwners(String[] owner, List<String> chain) {
        List<String> parts = new ArrayList<>();
        for (String family : chain) {
            int count = countOwned(owner, family);
            if (count > 0) {
                parts.add(family + " " + count);
            }
        }
        return String.join(" / ", parts);
    }

    private static int countOwned(String[] owner, String family) {
        int count = 0;
        for (String o : owner) {
            if (family.equals(o)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 列出点亮像素最少的若干个字形。
     *
     * <p>烘焙"成功"不等于"看得见"：一个只剩 1–2 个点的字形在游戏里跟空白没区别。
     * 把它们列出来，是让"字太细"这个问题在生成阶段就暴露，而不是等实机截图才发现。
     */
    private static void printThinnest(char[] charset, byte[][] data, int[] lit) {
        Integer[] order = new Integer[charset.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int va = data[a] == null ? Integer.MAX_VALUE : lit[a];
            int vb = data[b] == null ? Integer.MAX_VALUE : lit[b];
            return Integer.compare(va, vb);
        });
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 10; k++) {
            int i = order[k];
            sb.append(describe(charset[i])).append('=').append(lit[i]).append("  ");
        }
        System.out.println("最细的 10 个字形：" + sb);
    }

    /** 抽样打印几个字形，人工一眼就能看出"是不是糊成一坨"或"上下颠倒"。 */
    private static void printSamples(char[] charset, byte[][] data) {
        String samples = "一口日中田云";
        System.out.println("---- 抽样字形 ----");
        for (int s = 0; s < samples.length(); s++) {
            char ch = samples.charAt(s);
            int index = -1;
            for (int i = 0; i < charset.length; i++) {
                if (charset[i] == ch) {
                    index = i;
                    break;
                }
            }
            if (index < 0 || data[index] == null) {
                continue;
            }
            System.out.println(ch);
            for (int row = 0; row < BOX; row++) {
                StringBuilder line = new StringBuilder("  ");
                for (int col = 0; col < BOX; col++) {
                    int offset = col * BYTES_PER_COL + (row >> 3);
                    line.append(((data[index][offset] & 0xFF) >> (row & 7) & 1) != 0 ? '#' : '.');
                }
                System.out.println(line);
            }
        }
        System.out.println("-----------------");
    }

    // ============================================================ 字符集

    private static void collectSources(Path root, List<Path> out) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(out::add);
        }
    }

    /**
     * 从源码文本里提取所有需要烘焙的字符，升序去重。
     *
     * <p>纳入规则：所有 {@code >= 0x00A0} 的 BMP 字符（覆盖 CJK 标点、全角形式、统一表意文字，
     * 以及 {@code → ≈ ≤ ² — ·} 这类散落在代码/注释里的符号），
     * 排除代理区与零宽/格式符 —— 它们不携带可见字形，纳入只会在自检里报假失败。
     */
    private static char[] collectCharset(List<Path> sources) throws IOException {
        TreeSet<Character> set = new TreeSet<>();
        for (Path path : sources) {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (isBakeable(ch)) {
                    set.add(ch);
                }
            }
        }
        char[] out = new char[set.size()];
        int i = 0;
        for (char ch : set) {
            out[i++] = ch;
        }
        return out;
    }

    private static boolean isBakeable(char ch) {
        if (ch < 0x00A0) {
            return false;
        }
        if (ch >= 0xD800 && ch <= 0xDFFF) {
            return false;   // 代理区：单个 char 不是完整字符
        }
        if (ch == '\uFEFF' || ch == '\u00AD') {
            return false;   // BOM / 软连字符
        }
        return ch < 0x200B || ch > 0x200F;   // 零宽字符
    }

    private static String describe(char ch) {
        return String.format("%c(U+%04X)", ch, (int) ch);
    }

    // ============================================================ 光栅化

    /**
     * 把单个字形烘成 {@code BOX×BOX} 的点亮矩阵（行优先）。
     *
     * <p>先用 {@link GlyphVector#getVisualBounds()} 拿到字形的<b>真实轮廓边界</b>，
     * 按整数像素把墨迹中心对齐到画布中心（整数平移保证同机多次运行结果完全一致），
     * 再读回画布上 alpha 阈值二值化后的墨迹包围盒，判断是否装得进 12×12 并居中落位。
     * 不依赖任何"经验偏移"，因此换字体/换字号都不需要手工调参。
     *
     * @param gridOut 结果写进这里（长度必须为 {@code BOX*BOX}）
     * @return {@link #FIT_OK} / {@link #FIT_BLANK} / {@link #FIT_OVERFLOW}
     */
    private static int bake(Font font, char ch, boolean[] gridOut) {
        int canvas = BOX + 2 * PAD;
        BufferedImage img = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(font);
            g.setColor(Color.WHITE);
            FontRenderContext frc = g.getFontRenderContext();
            GlyphVector gv = font.createGlyphVector(frc, new char[]{ch});
            Rectangle2D bounds = gv.getVisualBounds();
            if (bounds.isEmpty()) {
                return FIT_BLANK;
            }
            int originX = (int) Math.round(canvas / 2.0 - bounds.getCenterX());
            int originY = (int) Math.round(canvas / 2.0 - bounds.getCenterY());
            g.drawGlyphVector(gv, originX, originY);
        } finally {
            g.dispose();
        }

        int minX = canvas;
        int minY = canvas;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < canvas; y++) {
            for (int x = 0; x < canvas; x++) {
                if ((img.getRGB(x, y) >>> 24) >= ALPHA_THRESHOLD) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        if (maxX < 0) {
            return FIT_BLANK;
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        Arrays.fill(gridOut, false);
        // 注意：包围盒内并不是每个像素都点亮，必须回到原图逐像素取 alpha；
        // 早期版本把整个包围盒填成实心，结果所有汉字都变成一个方块。
        // 溢出的字形也照样写进 gridOut（超出部分被裁掉），它只用于诊断打印，不会被落盘。
        int copyWidth = Math.min(width, BOX);
        int copyHeight = Math.min(height, BOX);
        int offX = (BOX - copyWidth) / 2;
        int offY = (BOX - copyHeight) / 2;
        for (int y = 0; y < copyHeight; y++) {
            for (int x = 0; x < copyWidth; x++) {
                if ((img.getRGB(minX + x, minY + y) >>> 24) >= ALPHA_THRESHOLD) {
                    gridOut[(offY + y) * BOX + offX + x] = true;
                }
            }
        }
        if (width > BOX || height > BOX) {
            return FIT_OVERFLOW;
        }
        return FIT_OK;
    }

    /**
     * 把 {@code BOX×BOX} 矩阵编码成点阵字节。
     *
     * <p>编码与 {@code BitmapFont} 完全一致：列优先，每列 {@value #BYTES_PER_COL} 个字节，
     * 位序 {@code bit i = 该列第 (band*8 + i) 行}，即
     * {@code pixel = ((data[col*BYTES_PER_COL + (row>>3)] & 0xFF) >> (row & 7)) & 1}。
     */
    private static byte[] encode(boolean[] grid) {
        byte[] out = new byte[BOX * BYTES_PER_COL];
        for (int col = 0; col < BOX; col++) {
            for (int row = 0; row < BOX; row++) {
                if (grid[row * BOX + col]) {
                    out[col * BYTES_PER_COL + (row >> 3)] |= (byte) (1 << (row & 7));
                }
            }
        }
        return out;
    }

    // ============================================================ 统计

    private static int min(int[] values) {
        int m = Integer.MAX_VALUE;
        for (int v : values) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static int max(int[] values) {
        int m = Integer.MIN_VALUE;
        for (int v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static int median(int[] values) {
        int[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    // ============================================================ 源码生成

    private static String renderSource(String sourceFontDesc, char[] charset, byte[][] data) {
        String today = LocalDate.now().toString();
        String header = String.join("\n",
                "package com.skyisland.render.ui;",
                "",
                "/**",
                " * 简体中文点阵字模（" + BOX + "×" + BOX + "，1 bit/像素）。",
                " *",
                " * <p>本文件由 {@code tools/fontgen/GenCjkFont.java} <b>自动生成，请勿手工编辑</b>。",
                " * <p>源字体：" + sourceFontDesc + "；生成日期：" + today
                        + "；字形数：" + charset.length + "。",
                " * <p>字符集来自扫描 {@code src/main/java} 与 {@code src/test/java} 下全部 {@code .java} 文件得到的",
                " * 非 ASCII 字符全集 —— {@code <b>新增中文文案后必须重新运行生成器</b>}，",
                " * 否则新字会渲染成空白（{@link #has(char)} 为 false 时不回落 {@code '?'}，",
                " * 这是有意的：空白比错字更容易被发现）。",
                " *",
                " * <p><b>编码：</b>列优先，每列 {@value #BYTES_PER_COL} 个字节，",
                " * {@code bit i = 该列第 (band*8 + i) 行}；与 {@link BitmapFont} 的约定一致，",
                " * 因此 {@code pixel = ((data[col*BYTES_PER_COL + (row>>3)] & 0xFF) >> (row & 7)) & 1}。",
                " */",
                "public final class CjkFont {",
                "",
                "    public static final int COLS = " + BOX + ";",
                "    public static final int ROWS = " + BOX + ";",
                "    public static final int BYTES_PER_COL = " + BYTES_PER_COL + ";",
                "    public static final int GLYPH_SPACING = 1;",
                "    public static final int ADVANCE = COLS + GLYPH_SPACING;",
                "",
                "    /** 生成时的字体与字号（仅供报告/日志引用）。 */",
                "    private static final String SOURCE_FONT = \"" + sourceFontDesc + "\";",
                "",
                "    /** 已烘焙字形个数。 */",
                "    private static final int GLYPH_COUNT = " + charset.length + ";",
                "",
                "    /** 已烘焙字符集，升序、去重。二分查找依赖这个顺序，不能重排。 */",
                "    private static final String CHARSET =");

        StringBuilder sb = new StringBuilder();
        sb.append(header).append('\n');
        sb.append(renderCharset(charset));
        sb.append("\n\n");
        sb.append(renderData(charset, data));
        sb.append('\n');
        sb.append(String.join("\n",
                "    private CjkFont() {",
                "    }",
                "",
                "    /** 该字符是否有已烘焙的字形。 */",
                "    public static boolean has(char ch) {",
                "        return indexOf(ch) >= 0;",
                "    }",
                "",
                "    /**",
                "     * 该字符第 {@code column} 列、第 {@code row} 行是否点亮。",
                "     *",
                "     * <p>越界或未烘焙一律返回 {@code false}：未烘焙字符渲染为空白，不回落 {@code '?'} ——",
                "     * 空白的字形一眼就能看出来，而一个顶替上去的 {@code '?'} 会被当成普通的文案问题漏过去。",
                "     */",
                "    public static boolean pixel(char ch, int column, int row) {",
                "        if (column < 0 || column >= COLS || row < 0 || row >= ROWS) {",
                "            return false;",
                "        }",
                "        int index = indexOf(ch);",
                "        if (index < 0) {",
                "            return false;",
                "        }",
                "        int offset = index * (COLS * BYTES_PER_COL) + column * BYTES_PER_COL + (row >> 3);",
                "        return ((DATA[offset] & 0xFF) >> (row & 7) & 1) != 0;",
                "    }",
                "",
                "    public static int glyphCount() {",
                "        return GLYPH_COUNT;",
                "    }",
                "",
                "    /** 已烘焙字符集，升序、去重。 */",
                "    public static String charset() {",
                "        return CHARSET;",
                "    }",
                "",
                "    /** 生成时用的字体与字号（供报告/日志引用）。 */",
                "    public static String sourceFont() {",
                "        return SOURCE_FONT;",
                "    }",
                "",
                "    /** 在升序的 {@link #CHARSET} 上二分查找，未烘焙返回 -1。 */",
                "    private static int indexOf(char ch) {",
                "        int low = 0;",
                "        int high = CHARSET.length() - 1;",
                "        while (low <= high) {",
                "            int mid = (low + high) >>> 1;",
                "            char current = CHARSET.charAt(mid);",
                "            if (current == ch) {",
                "                return mid;",
                "            }",
                "            if (current < ch) {",
                "                low = mid + 1;",
                "            } else {",
                "                high = mid - 1;",
                "            }",
                "        }",
                "        return -1;",
                "    }",
                "}"));
        return sb.toString();
    }

    private static String renderCharset(char[] charset) {
        final int perLine = 40;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < charset.length; i += perLine) {
            int end = Math.min(i + perLine, charset.length);
            sb.append("            \"");
            for (int j = i; j < end; j++) {
                sb.append(escape(charset[j]));
            }
            sb.append('"');
            sb.append(i + perLine < charset.length ? "\n            + " : ";");
        }
        return sb.toString();
    }

    /**
     * 生成点阵数据段。
     *
     * <p>两个不能想当然的坑：
     * <ol>
     *   <li><b>不能用 byte[] 字面量。</b>值超过 0x7F 时必须写成 {@code (byte)0x8E} 或负数，
     *       三万多个元素挤在一起基本无法校对。</li>
     *   <li><b>更不能把所有元素放进一个数组字面量。</b>数组字面量会被编译成
     *       {@code newarray + 逐元素 store} 的字节码，单个方法上限 64KB，
     *       三万多个元素必定 "code too large"。因此这里按 {@link #CHUNK_GLYPHS_DEFAULT}
     *       个字形一块，每块一个私有方法（各自独立享受 64KB 额度），
     *       再由静态块 {@code System.arraycopy} 拼进扁平的 {@code DATA}，
     *       这样 {@code pixel()} 里的索引公式仍然是最朴素的线性公式。</li>
     * </ol>
     */
    private static String renderData(char[] charset, byte[][] data) {
        StringBuilder sb = new StringBuilder();
        sb.append("    /** 每个字形的字节数。 */\n");
        sb.append("    private static final int BYTES_PER_GLYPH = COLS * BYTES_PER_COL;\n\n");
        sb.append("    /**\n");
        sb.append("     * 点阵数据：").append(charset.length).append(" × BYTES_PER_GLYPH 个字节值（0..255），列优先，\n");
        sb.append("     * 字形顺序与 {@link #CHARSET} 一一对应。\n");
        sb.append("     *\n");
        sb.append("     * <p>用 {@code int[]} 而不是 {@code byte[]} 纯粹是为了可校对性：\n");
        sb.append("     * byte 字面量超过 0x7F 就得写成 {@code (byte)0x8E} 或负数，三万多元素挤在一起没法看。\n");
        sb.append("     * 取值口径不变 —— 每个元素就是一个 8 位字节，读取时 {@code & 0xFF} 只看低 8 位。\n");
        sb.append("     */\n");
        sb.append("    private static final int[] DATA = new int[GLYPH_COUNT * BYTES_PER_GLYPH];\n\n");
        sb.append("    static {\n");
        int chunks = (charset.length + CHUNK_GLYPHS_DEFAULT - 1) / CHUNK_GLYPHS_DEFAULT;
        for (int chunk = 0; chunk < chunks; chunk++) {
            sb.append("        fillGlyphs").append(chunk).append("();\n");
        }
        sb.append("    }\n\n");
        for (int chunk = 0; chunk < chunks; chunk++) {
            int from = chunk * CHUNK_GLYPHS_DEFAULT;
            int to = Math.min(from + CHUNK_GLYPHS_DEFAULT, charset.length);
            sb.append("    // 第 ").append(from).append("..").append(to - 1)
                    .append(" 个字形的点阵（分块的原因见 DATA 的注释）。\n");
            sb.append("    private static void fillGlyphs").append(chunk).append("() {\n");
            sb.append("        int[] part = {\n");
            for (int i = from; i < to; i++) {
                byte[] glyph = data[i];
                StringBuilder line = new StringBuilder("            ");
                for (byte b : glyph) {
                    line.append(String.format("0x%02X", b & 0xFF)).append(',');
                }
                line.append("   // ").append(String.format("U+%04X ", (int) charset[i]))
                        .append(escape(charset[i]));
                sb.append(line).append('\n');
            }
            sb.append("        };\n");
            sb.append("        System.arraycopy(part, 0, DATA, ")
                    .append(from).append(" * BYTES_PER_GLYPH, part.length);\n");
            sb.append("    }\n\n");
        }
        return sb.toString();
    }

    /** 生成的是 Java 字符串字面量，反斜杠与引号必须转义（当前字符集里其实没有这两个）。 */
    private static String escape(char ch) {
        if (ch == '\\' || ch == '"') {
            return "\\" + ch;
        }
        return String.valueOf(ch);
    }
}

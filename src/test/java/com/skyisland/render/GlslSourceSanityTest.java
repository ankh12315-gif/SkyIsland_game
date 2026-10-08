package com.skyisland.render;

import com.skyisland.testutil.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ M5a 新增：<b>GLSL 保留字守卫</b>。
 *
 * <h3>为什么必须有它（本项目真踩过）</h3>
 * <p>M5a 第一版在 {@code voxel.frag} 里用了一个叫 {@code packed} 的局部变量。
 * 它在 C/C++ 里只是个普通词，<b>在 GLSL 里是保留字</b>：
 * <pre>
 *   ERROR: 0:89: 'packed' : reserved word
 *   ERROR: 0:89: 'packed' : syntax error
 * </pre>
 * 后果是<b>整个着色器编译不过 → 画面全黑或根本没有画面</b>。
 *
 * <p>而当时<b>所有单元测试全绿</b>，因为：
 * <ul>
 *   <li>Java 单测不需要 GPU，不会编译 GLSL；</li>
 *   <li>已有的着色器扫描守卫只检查"某一行是否存在"，
 *       而那一行<b>确实在</b>，只是它语法非法。</li>
 * </ul>
 * 也就是说：<b>"文本扫描"与"能编译"是两个独立事实</b>，
 * 前者全绿对后者没有任何保证。
 *
 * <h3>★ 为什么不能"全文扫到保留字就报错"（这个坑我一开始就踩了）</h3>
 * <p>第一版本类就是把每个 token 拿去撞保留字表。它立刻是<b>错的</b>：
 * <ul>
 *   <li>{@code max} / {@code mod} / {@code step} / {@code texture} / {@code mix} / {@code clamp}
 *       是<b>内置函数</b>，{@code float torchN = mod(x, 16.0) / 15.0;} 完全合法 ——
 *       它们不能被当变量名，但<b>当函数名调用是天天在写的正常代码</b>。</li>
 *   <li>{@code in} / {@code out} / {@code const} / {@code uniform} 是<b>关键字</b>，
 *       {@code out vec4 fragColor;} 里的 {@code out} 根本不是标识符。</li>
 * </ul>
 * 换句话说，判据必须是<b>位置</b>而不是"出现"：
 * <b>只有"被拿来当标识符声明出来"才是非法</b>。
 * 若不做这个区分，这个守卫会变成一盏永远红的红灯 —— 而红灯守卫的真正后果是
 * 被人加 {@code @Disabled} 或删掉，从此再没人报警。
 *
 * <h3>本守卫覆盖什么、不覆盖什么（不许夸大）</h3>
 * <p><b>覆盖</b>：把保留字 / 内置函数名当变量名声明（{@code float packed = ...}），
 * 以及把不可调用的保留字当语句开头用（{@code packed = 3.0;}）。
 * <p><b>不覆盖</b>：类型不匹配、参数个数错误、swizzle 非法等<b>一切其他语法错误</b>。
 * 单靠词法无法判定它们。
 * <p>因此<b>本守卫不是替代品，是廉价前置筛</b>。真正的编译验证由
 * {@code M5DayNightEvidence} 承担 —— 它启动时就真编译两个着色器，
 * 失败立刻非 0 退出。<b>两条防线都摆着，不是二选一。</b>
 *
 * <h3>★★ 守卫自己也要有反证（本类最有价值的三个用例）</h3>
 * <p>"一个扫描守卫全绿"有两种可能：真的没问题，或者<b>它的判据从来没匹配过任何东西</b>。
 * 后者是本项目反复踩到的"脆绿灯"。因此本类最后三个用例
 * <b>把违规代码当夹具喂进扫描器，断言它确实被咬出来</b> ——
 * 判据一旦被改坏（例如正则漏了 {@code =} 后的类型、或忘了逗号分隔的第二个声明名），
 * 这几个用例会先变红，而不是等到某天有人在着色器里踩同一个坑。
 */
class GlslSourceSanityTest {

    // ============================================================ 规则 1：声明位置

    /**
     * GLSL 里所有能出现在<b>声明处</b>的类型名（含 sampler / 矩阵全家族）。
     *
     * <p>只要某处出现 "类型名 + 空白 + 标识符"，那个标识符就是<b>被声明出来的名字</b>，
     * 此时它若落在 {@link #NOT_ALLOWED_AS_IDENTIFIER} 里就是非法的。
     */
    private static final Pattern DECLARATION = Pattern.compile(
            "\\b(float|vec2|vec3|vec4|bvec2|bvec3|bvec4|ivec2|ivec3|ivec4|uvec2|uvec3|uvec4"
                    + "|mat2|mat3|mat4|mat2x2|mat2x3|mat2x4|mat3x2|mat3x3|mat3x4"
                    + "|mat4x2|mat4x3|mat4x4|bool|int|uint|void"
                    + "|sampler2D|sampler3D|samplerCube|sampler2DArray|sampler2DShadow"
                    + "|atomic_uint|shared)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * 不能被拿来当<b>标识符（变量 / 参数 / 函数名）</b>的词。
     *
     * <p>包含两类，缺一不可：
     * <ol>
     *   <li><b>关键字</b>：{@code in} / {@code out} / {@code const} / {@code struct} /
     *       {@code discard} / {@code packed} / {@code row_major} …</li>
     *   <li><b>内置函数名</b>：{@code max} / {@code mod} / {@code step} / {@code texture} …
     *       它们<b>不能当变量名</b>，但<b>当函数调用是合法的</b> ——
     *       所以它们只能由"声明位置"这条规则去判，不能全文判死。</li>
     * </ol>
     */
    private static final Set<String> NOT_ALLOWED_AS_IDENTIFIER = Set.of(
            // 关键字
            "attribute", "const", "uniform", "varying", "buffer", "shared",
            "centroid", "flat", "smooth", "noperspective", "patch", "sample", "subroutine",
            "in", "out", "inout", "layout", "coherent", "volatile", "restrict",
            "readonly", "writeonly",
            "if", "else", "switch", "case", "default", "while", "do", "for",
            "break", "continue", "return", "discard", "struct",
            "common", "partition", "active", "asm", "class", "union", "enum",
            "typedef", "template", "this", "resource", "goto", "inline", "noinline",
            "public", "static", "extern", "external", "interface", "long", "short",
            "double", "half", "fixed", "unsigned", "superp", "input", "output",
            "hvec2", "hvec3", "hvec4", "fvec2", "fvec3", "fvec4",
            "sizeof", "cast", "namespace", "using", "packed", "precise",
            "row_major", "atomic_uint",
            // 内置类型（写在这里是因为"当变量名"同样非法）
            "void", "bool", "int", "uint", "float",
            "vec2", "vec3", "vec4", "bvec2", "bvec3", "bvec4",
            "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4",
            "mat2", "mat3", "mat4", "mat2x2", "mat2x3", "mat2x4",
            "mat3x2", "mat3x3", "mat3x4", "mat4x2", "mat4x3", "mat4x4",
            "sampler2D", "sampler3D", "samplerCube", "sampler2DArray", "sampler2DShadow",
            // 内置函数
            "texture", "texture2D", "textureLod", "mix", "clamp", "step", "mod",
            "max", "min", "abs", "dot", "cross", "length", "normalize", "pow",
            "floor", "ceil", "fract", "modf", "sign", "saturate", "reflect");

    // ============================================================ 规则 2：语句开头

    /**
     * 不可调用的保留字若出现在<b>语句开头</b>，说明它被当成了裸标识符使用。
     *
     * <p>为什么能安全地排除内置函数名：GLSL 的语句可以以表达式开头，
     * {@code max(a, b);} 语法合法，所以"行首是 max"不能直接判死；
     * 而 {@code packed} / {@code row_major} / {@code precise} 这类
     * <b>根本不可调用</b>，出现在行首就只可能是在当变量用。
     * <p>这条规则是对规则 1 的补充：{@code packed = 3.0;} 没有类型前缀，
     * 规则 1 的正则看不见它。
     */
    private static final Set<String> NOT_CALLABLE_RESERVED = Set.of(
            "packed", "precise", "row_major", "atomic_uint",
            "class", "template", "this", "namespace", "using", "goto",
            "union", "enum", "typedef", "asm", "resource", "sampler2DShadow");

    /** 一个标识符：字母或下划线开头。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Path SHADER_DIR = SourceScan.projectRoot()
            .resolve("src/main/resources/shaders");

    // ============================================================ 主守卫

    @Test
    void noShaderDeclaresAnIdentifierThatGlslReserves() {
        List<Path> shaders = shaderFiles();
        List<String> offences = new ArrayList<>();
        for (Path shader : shaders) {
            String rel = SHADER_DIR.relativize(shader).toString();
            for (String offence : scan(SourceScan.read(shader))) {
                offences.add(rel + " :: " + offence);
            }
        }
        assertTrue(offences.isEmpty(),
                "着色器里有标识符撞上 GLSL 保留字 —— "
                        + "GLSL 会在编译期报「reserved word」，**整个着色器编译不过**（画面全黑），"
                        + "而源码扫描类守卫对此完全无法报警（那行确实在，只是语法非法）。\n"
                        + "真实验证：M5DayNightEvidence 在启动时真编译两个着色器。\n"
                        + String.join("\n", offences));
    }

    // ============================================================ ★ 守卫自身的反证

    /**
     * 反证 A：把 M5a 真实踩过的那个坑当夹具喂进去，扫描器<b>必须</b>咬出来。
     *
     * <p>没有这条，本类的"全绿"可能只是因为规则 1 的正则从未匹配过。
     */
    @Test
    void theScannerCatchesTheReservedWordThatActuallyBrokeTheBuild() {
        List<String> found = scan("""
                #version 330 core
                out vec4 fragColor;
                void main() {
                    float lightPack = 1.0;
                    float packed = lightPack;
                    fragColor = vec4(packed);
                }
                """);
        assertEquals(1, found.size(), "应当恰好报出 1 处（float packed），实际: " + found);
        assertTrue(found.get(0).contains("'packed'"), "报出的是: " + found.get(0));
    }

    /**
     * 反证 B：<b>内置函数调用与关键字前缀不得被误报</b> —— 这是本守卫最可能的失败方式。
     *
     * <p>第一版本类就是"全文扫到保留字就报错"，它对着 {@code voxel.frag} 的真实内容
     * 一定是红的。若有人把规则改回无差别判死，这条会立刻抓住。
     */
    @Test
    void theScannerDoesNotFlagBuiltinCallsOrKeywordQualifiers() {
        List<String> found = scan("""
                #version 330 core
                uniform sampler2DArray uBlockAtlas;
                in vec2 vLayerLight;
                out vec4 fragColor;
                void main() {
                    vec4 texel = texture(uBlockAtlas, vec3(vLayerLight, 1.0));
                    float lightPack = vLayerLight.y;
                    float skyN = step(16.0, lightPack);
                    float torchN = mod(lightPack, 16.0) / 15.0;
                    float dayLit = max(skyN, torchN);
                    float shade = texel.a * clamp(dayLit, 0.0, 1.0);
                    if (shade > 0.5) { discard; }
                    fragColor = vec4(texel.rgb * shade, mix(0.0, 1.0, shade));
                }
                """);
        assertTrue(found.isEmpty(), "合法写法被误报了（这会让守卫变成永远红的红灯，"
                + "而红灯守卫的真正后果是被人删掉，从此再没人报警）: " + found);
    }

    /**
     * 反证 C：判据不能只看"类型名后第一个标识符"。
     *
     * <p>{@code float a, packed;} 里第二个声明名同样非法，而只看第一个会漏。
     * <p>另外 {@code packed = 3.0;} 没有类型前缀，规则 1 完全看不见 ——
     * 所以规则 2 存在的原因就是它，这条用例把这两点一起钉住。
     */
    @Test
    void theScannerCatchesLaterDeclaratorsAndBareAssignments() {
        List<String> multi = scan("""
                #version 330 core
                void main() {
                    float a, packed;
                    float b = 1.0, texture = 2.0;
                }
                """);
        assertTrue(multi.stream().anyMatch(s -> s.contains("'packed'")), "漏了逗号后的第二个声明名: " + multi);
        assertTrue(multi.stream().anyMatch(s -> s.contains("'texture'")), "漏了把内置函数名当变量: " + multi);

        List<String> bare = scan("""
                #version 330 core
                void main() {
                    packed = 3.0;
                    row_major = vec4(1.0);
                }
                """);
        assertEquals(2, bare.size(), "语句开头的裸保留字应当各报一次: " + bare);
        assertTrue(bare.stream().anyMatch(s -> s.contains("'packed'")), "实际: " + bare);
        assertTrue(bare.stream().anyMatch(s -> s.contains("'row_major'")), "实际: " + bare);
    }

    // ============================================================ 工具

    /**
     * 扫一段着色器源码，返回所有"把保留字当标识符"的位置描述（空列表 = 干净）。
     *
     * <p>先剥注释与预处理行 —— 否则"注释里写了 {@code float packed}"会变成一个假红，
     * 而 {@code voxel.frag} 的注释里恰好大量讨论 {@code lightPack} 与 {@code packed} 的历史。
     */
    static List<String> scan(String source) {
        String code = stripCommentsAndPreprocessor(source);
        List<String> offences = new ArrayList<>();
        collectDeclaredNames(code, offences);
        collectBareReservedStatements(code, offences);
        return offences;
    }

    /** 规则 1：抓声明位置的名字，含 {@code float a, packed;} 的第二个及以后。 */
    private static void collectDeclaredNames(String code, List<String> offences) {
        Matcher m = DECLARATION.matcher(code);
        // 用显式游标而不是 Matcher.region()：region 边界算错会抛 IndexOutOfBoundsException，
        // 而且"找完一条声明后要跳到它末尾"这件事用游标最直白。
        int cursor = 0;
        while (cursor < code.length() && m.find(cursor)) {
            int declarationStart = m.start();
            int listEnd = declaratorListEnd(code, m.end());
            int line = lineOf(code, declarationStart);

            // 第一个声明名就是紧跟类型的那一个：float packed = ... 里的 packed。
            // ★ 不能靠"扫声明符列表取第一个标识符"来代替 ——
            //   `float packed = lightPack;` 会取到 lightPack（赋值右侧），
            //   于是真 offender 被放过、合法名字反被审。必须用 group(2)。
            checkDeclared(code, m.group(2), line, offences);

            // 之后只有逗号分隔的后续声明名才需要逐个查：
            // float a, packed; / float b = 1.0, texture = 2.0;
            // 每段取"第一个标识符"是对的 —— 逗号之后的段里 `= 右侧` 一定在名字之后。
            String list = code.substring(m.end(), listEnd);
            int from = list.indexOf(',');
            if (from >= 0) {
                for (String rest : list.substring(from + 1).split(",")) {
                    Matcher id = IDENTIFIER.matcher(rest);
                    if (!id.find()) {
                        // 例如 float f = vec3(1.0, 0.0, 0.0).x 的后续片段以数字开头
                        continue;
                    }
                    checkDeclared(code, id.group(), line, offences);
                }
            }
            cursor = Math.max(listEnd, m.end());
        }
    }

    private static void checkDeclared(String code, String name, int line, List<String> offences) {
        if (NOT_ALLOWED_AS_IDENTIFIER.contains(name)) {
            offences.add("第 " + line + " 行声明了保留字 '" + name + "'");
        }
    }

    private static int lineOf(String code, int offset) {
        return (int) code.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }

    /** 声明符列表的结束位置：下一个 {@code ';'} / {@code '('} / {@code '{'} / {@code ')'}。 */
    private static int declaratorListEnd(String code, int from) {
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            // ')' 必须算终止符：函数参数表 f(float a, packed) 里，
            // 没有 ';' 也没有 '(' 跟着，若不认它就会一路吃到下一个 ';'，
            // 把别的语句的标识符当成本函数的声明名。
            if (c == ';' || c == '(' || c == '{' || c == ')') {
                return i;
            }
        }
        return code.length();
    }

    /** 规则 2：抓"语句开头的裸保留字"（{@code packed = 3.0;}）。 */
    private static void collectBareReservedStatements(String code, List<String> offences) {
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.charAt(0) == '#') {
                continue;
            }
            int i0 = 0;
            while (i0 < line.length() && Character.isWhitespace(line.charAt(i0))) {
                i0++;
            }
            Matcher id = IDENTIFIER.matcher(line.substring(i0));
            if (!id.find()) {
                continue;
            }
            String first = id.group();
            // 后面紧跟 '(' ⇒ 是调用而不是声明，交给规则 1 处理
            boolean called = id.end() < line.length()
                    && line.substring(id.end()).trim().startsWith("(");
            if (!called && NOT_CALLABLE_RESERVED.contains(first)) {
                offences.add("语句开头用了不可调用的保留字 '" + first + "'（第 " + (i + 1) + " 行）");
            }
        }
    }

    /** 列出着色器目录下的所有 .vert / .frag（不写死文件名清单）。 */
    private static List<Path> shaderFiles() {
        if (!Files.isDirectory(SHADER_DIR)) {
            throw new UncheckedIOException(
                    new IOException("着色器目录不存在: " + SHADER_DIR + "（守卫会因扫不到文件而全绿）"));
        }
        try (Stream<Path> stream = Files.list(SHADER_DIR)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".vert")
                            || p.getFileName().toString().endsWith(".frag"))
                    .sorted()
                    .toList();
            assertFalse(files.isEmpty(), SHADER_DIR + " 下没有任何 .vert / .frag，"
                    + "此时保留字守卫会因为扫不到任何东西而全绿");
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("列着色器目录失败: " + SHADER_DIR, e);
        }
    }

    /** 去掉注释与预处理行；只留真正会被编译的代码。 */
    private static String stripCommentsAndPreprocessor(String source) {
        String src = SourceScan.withoutComments(source);
        StringBuilder out = new StringBuilder(src.length());
        boolean inLine = false;
        boolean inBlock = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '#') {                       // #version / #define 等整行丢弃
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
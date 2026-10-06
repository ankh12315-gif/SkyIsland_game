package com.skyisland.testutil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 源码扫描类断言的公共工具。
 *
 * <h2>它为什么存在（这是一条踩过坑的经验）</h2>
 * 有一类不变式无法用行为断言表达，只能"扫源码"：例如
 * "装配期确实调用了 {@code combat.setReserveMode(...)}"、
 * "启动器那行 java 命令确实带了这个开关"。
 *
 * <p><b>而朴素的 {@code source.contains("...")} 会被注释满足。</b>
 * 该项目真实发生过一次：把装配期那行调用<b>注释掉</b>之后，扫描断言依然全绿 ——
 * 因为文件里原本就有一段解释这行代码的注释，注释里也写着同一串文本。
 * 也就是说，那条断言当时<b>根本不承重</b>：它钉住的是"这段文字出现过"，
 * 而不是"这段代码执行得到"。反向验证（把接线破坏掉，断言必须变红）才把它暴露出来。
 *
 * <p>因此本类提供两件东西：
 * <ol>
 *   <li>{@link #withoutComments(String)} —— 先剥掉注释，再让断言去匹配；</li>
 *   <li>{@link #methodBody(String, String)} —— 只取某个<b>方法体内部</b>的代码，
 *       把"这段调用确实在这个方法里"变成可断言的事实，而不是"在这个文件里"。</li>
 * </ol>
 *
 * <p>两件都是"减法"：断言越具体，越不容易被无关文本意外满足。
 */
public final class SourceScan {

    private SourceScan() {
    }

    /** 项目根目录（surefire 的工作目录就是它）。 */
    public static Path projectRoot() {
        return Path.of("").toAbsolutePath();
    }

    /** 读一个源码文本文件（UTF-8；本项目的 Java 源码与启动器都是 UTF-8）。 */
    public static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读不到源码文件: " + path, e);
        }
    }

    /** 读主源码树里的一个文件，例如 {@code "com/skyisland/combat/CombatController.java"}。 */
    public static String readMain(String relativePath) {
        return read(projectRoot().resolve("src/main/java").resolve(relativePath));
    }

    /**
     * 剥掉所有注释，只留下"会被编译的文本"。
     *
     * <p>按状态机处理，因此不会被字符串/字符字面量里的 {@code //} 或 {@code /*} 骗到
     * （javadoc、行尾注释、被注释掉的整段代码都会消失）。
     * 换行符保留，这样"哪一行"的直觉仍然成立。
     */
    public static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        boolean lineComment = false;
        boolean blockComment = false;
        boolean string = false;
        boolean character = false;
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                }
                i++;
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i += 2;
                    continue;
                }
                if (c == '\n') {
                    out.append(c);
                }
                i++;
                continue;
            }
            if (string || character) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(source.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (string && c == '"') {
                    string = false;
                } else if (character && c == '\'') {
                    character = false;
                }
                i++;
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                i += 2;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i += 2;
                continue;
            }
            if (c == '"') {
                string = true;
            } else if (c == '\'') {
                character = true;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 取出某个方法/构造器的<b>方法体</b>（不含外层大括号），且已剥掉注释。
     *
     * <p>{@code signatureFragment} 是签名里足以定位到这个方法的一段文本
     * （通常写成 {@code "private void resolveShot("} 这样的形式，带上可见性修饰符，
     * 避免匹配到调用点或同名的重载）。
     *
     * <p><b>找不到时抛异常而不是返回空串</b>：返回空串会让"该方法被改名/删掉"表现为
     * `contains(...) == false` 的<b>通过</b>（对"不应包含某物"的断言而言），
     * 或者更糟 —— 让"应包含"的断言以一句指向错误方向的失败信息变红。
     * 直接抛 {@link IllegalStateException} 是把"测量仪器坏了"与"被测量对象坏了"分开。
     */
    public static String methodBody(String source, String signatureFragment) {
        String code = withoutComments(source);
        int at = code.indexOf(signatureFragment);
        if (at < 0) {
            throw new IllegalStateException("源码里找不到签名片段: " + signatureFragment
                    + "（方法被改名或删除了？）");
        }
        int brace = code.indexOf('{', at);
        if (brace < 0) {
            throw new IllegalStateException("签名之后没有找到方法体 '{': " + signatureFragment);
        }
        // 括号配对必须<b>跳过字符串/字符字面量</b>：日志文案里出现一个 '{' 就会让配对提前结束，
        // 取出半截方法体 —— 而半截方法体对"应包含"的断言表现为假红、
        // 对"不应包含"的断言表现为假绿。两种都很难查。
        int depth = 0;
        boolean string = false;
        boolean character = false;
        for (int i = brace; i < code.length(); i++) {
            char c = code.charAt(i);
            if (string || character) {
                if (c == '\\') {
                    i++;
                } else if (string && c == '"') {
                    string = false;
                } else if (character && c == '\'') {
                    character = false;
                }
                continue;
            }
            if (c == '"') {
                string = true;
            } else if (c == '\'') {
                character = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(brace + 1, i);
                }
            }
        }
        throw new IllegalStateException("方法体大括号不配对: " + signatureFragment);
    }

    /** 列出主源码树里所有 {@code .java} 文件（相对项目根）。 */
    public static List<Path> allMainSources() {
        Path root = projectRoot().resolve("src/main/java");
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> result = new ArrayList<>();
            stream.filter(p -> p.toString().endsWith(".java")).sorted().forEach(result::add);
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("扫描主源码树失败: " + root, e);
        }
    }

    /**
     * 把主源码树里除 {@code excludedRelativeSuffix} 之外的所有文件拼成一份"已剥注释"的文本。
     *
     * <p>用途：审计"某个数据字段在整个生产代码里有没有读者"。
     * 排除 {@code GunSpec.java} 是必须的 —— 定义自己的文件当然会提到自己的组件名
     * （record 的组件名会出现在它的访问器签名里），不排除的话每条断言都会被自己满足。
     */
    public static String allMainCodeExcept(String... excludedRelativeSuffix) {
        StringBuilder combined = new StringBuilder();
        for (Path path : allMainSources()) {
            String name = path.toString().replace('\\', '/');
            boolean skip = false;
            for (String suffix : excludedRelativeSuffix) {
                if (name.endsWith(suffix)) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                combined.append(withoutComments(read(path))).append('\n');
            }
        }
        return combined.toString();
    }
}

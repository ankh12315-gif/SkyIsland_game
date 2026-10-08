package com.skyisland.game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动器 / 门禁运行器的 <b>jar 解析守卫</b>的回归守卫。
 *
 * <h2>它守的是一个"守卫自己写错、却一直没人跑过"的坑</h2>
 * 2026-10-03 主理人双击 {@code play.bat} 撞到：
 * <pre>
 *   [ERROR] Expected exactly 1 jar in target\, found 2.
 * </pre>
 * 现场是 {@code target\} 下确实有两个匹配 {@code skyisland-*.jar} 的文件 ——
 * {@code skyisland-<v>.jar} 与 {@code skyisland-<v>-shaded.jar}，
 * 且两者 <b>sha256 完全相同</b>（都是 5794458 B；另有 {@code original-<v>.jar}
 * 是 shade 之前的 thin 备份，678889 B，本来就不匹配 glob）。
 *
 * <p>也就是说，那条守卫把 <b>maven-shade-plugin 留下的字节相同别名</b>
 * 当成了"第二个版本"。而它<b>不是</b>一次偶发：只要不 {@code clean} 就重新构建
 * （开发与试玩的常态），别名就在那里 —— 于是守卫会在最常见的迭代路径上
 * 把人卡在"我明明刚构建完"。
 *
 * <h2>为什么修法不是"把守卫删掉"</h2>
 * 这条守卫真正要防的东西是有效的：<b>版本号变了却跑着上一版 jar</b>，
 * 那会让报告里引用的证据指向一个不存在的构建，而运行器照样打印"通过"。
 * 所以唯一性校验保留，判据从"恰好 1 个<b>文件</b>"改成
 * "恰好 1 个<b>真 jar</b>"——显式排除 {@code -shaded.jar} 与 {@code original-*}。
 *
 * <h2>★ 为什么本类真的去跑 cmd，而不是只扫源码</h2>
 * 修这条守卫的过程中，它自己就错了一次：作者以为 cmd 的字符串比较
 * {@code if /i neq "skyisland-*-shaded"} 支持通配符，实测
 * <b>不支持</b> —— 它匹配一切，把别名也数进去（{@code JARCOUNT=2}）。
 * 换句话说，"源码里有排除逻辑"这条扫描断言<b>照样会绿</b>，而守卫仍然是坏的。
 *
 * <p>因此本类把 {@code play.bat} 里那一段<b>原样抽出来</b>，
 * 喂一个<b>合成夹具目录</b>（两个同名前缀的 jar），再用真实的 {@code cmd /c} 跑它，
 * 断言它真的解析出 1 个、并且选中的那个<b>不是</b>别名。
 * 再加一条反向用例：两个真 jar 时必须报"2"（证明它不是永远返回 1 的假绿）。
 *
 * <p>夹具放在 {@code tmp/} 下、用完删掉，<b>不碰 target/</b>，
 * 也不依赖本机此刻构建到哪一步 —— 门禁在任何机器上跑出来的结论都相同。
 */
class LauncherJarResolutionTest {

    private static final Path LAUNCHER = Path.of("play.bat");
    private static final Path FIXTURE_ROOT = Path.of("tmp", "__jar_resolve_fixture");
    private static final Path FIXTURE_TARGET = FIXTURE_ROOT.resolve("target");
    private static final Path SCRATCH_BAT = Path.of("tmp", "_jar_resolve_probe.bat");

    @AfterEach
    void cleanUp() {
        deleteQuietly(SCRATCH_BAT);
        deleteRecursively(FIXTURE_ROOT);
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 临时夹具清理失败不应让测试变红：它不影响任何产品行为。
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            List<Path> all = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path p : all) {
                deleteQuietly(p);
            }
        } catch (IOException ignored) {
            // 同上：清理失败只会在下次运行时被覆盖。
        }
    }

    private static void touchJar(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        // 只建空文件：守卫只看<b>文件名</b>，不看内容。
        Files.write(dir.resolve(name), new byte[]{'P', 'K'});
    }

    /**
     * 把 {@code play.bat} 里的 jar 解析块抽成一份可独立运行的探针。
     *
     * <p><b>块边界必须包含 {@code exit /b 3} 之后那一行</b>（收尾的右括号）：
     * 少了它，cmd 的括号不配对，会直接以 255 退出且<b>不打印任何东西</b> ——
     * 那看起来像"守卫没输出"，很容易被误读成"没找到 jar"。
     */
    private static String buildProbe(String projectDir) throws IOException {
        String bat = Files.readString(LAUNCHER, StandardCharsets.ISO_8859_1);
        String[] lines = bat.split("\r?\n", -1);

        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("set \"JAR=\"")) {
                start = i;
                break;
            }
        }
        assertTrue(start >= 0, "play.bat 里找不到 'set \"JAR=\"' —— 启动器结构变了，本用例需要跟着改");

        int exit = -1;
        for (int i = start; i < lines.length; i++) {
            if (lines[i].contains("exit /b 3")) {
                exit = i;
                break;
            }
        }
        assertTrue(exit >= 0, "play.bat 里找不到 'exit /b 3' —— 守卫被删掉了？");

        // 守卫本体（`if not "%JARCOUNT%"=="1"` …）单独定位。
        // ★ 探针的计数行必须插在<b>守卫之前</b>：负向用例里守卫会 `exit /b 3`，
        //   计数行若排在守卫之后就永远打不出来 —— 那时测试看到的是"探针没输出"，
        //   而不是"守卫算出了 2"，报错方向完全指错。
        int guard = -1;
        for (int i = start; i < exit; i++) {
            if (lines[i].contains("if not \"%JARCOUNT%\"==\"1\"")) {
                guard = i;
                break;
            }
        }
        assertTrue(guard > start,
                "play.bat 里找不到唯一性校验行 `if not \"%JARCOUNT%\"==\"1\"` —— "
                        + "守卫被改弱了？（本类的负向用例正是为了钉住它还在）");

        StringBuilder probe = new StringBuilder();
        probe.append("@echo off\r\n");
        probe.append("setlocal enabledelayedexpansion\r\n");
        // ① 解析段（for 循环 + 注释）
        for (int i = start; i < guard; i++) {
            probe.append(lines[i].replace("%PROJ%", projectDir)).append("\r\n");
        }
        // ② 计数器：无论守卫随后是放行还是拦下，这里都已经打出来了
        probe.append("echo RESULT_JARCOUNT=!JARCOUNT!\r\n");
        probe.append("echo RESULT_JAR=!JAR!\r\n");
        // ③ 守卫本体：它自己的判定与报错也要看到
        for (int i = guard; i <= exit + 1; i++) {
            String line = lines[i].replace("%PROJ%", projectDir);
            // ★ 必须中和 pause：守卫的失败分支里有一个 pause（双击运行时等人按键是对的），
            //   而探针是无头的 —— 不中和它，负向用例会以"60 秒没退出"报错，
            //   看起来像守卫坏了，其实只是有人在那儿等回车。
            if (line.trim().equalsIgnoreCase("pause")) {
                line = "rem pause neutralized for the headless probe";
            }
            probe.append(line).append("\r\n");
        }
        probe.append("endlocal\r\n");

        Files.write(SCRATCH_BAT, probe.toString().getBytes(StandardCharsets.ISO_8859_1));
        return Files.readString(SCRATCH_BAT, StandardCharsets.ISO_8859_1);
    }

    private static String runProbe() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", SCRATCH_BAT.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean done = p.waitFor(60, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException("cmd 探针 60 秒没退出（守卫里可能有 pause？）");
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        return "exit=" + p.exitValue() + "\n" + out;
    }

    private static String valueOf(String probeOutput, String key) {
        for (String line : probeOutput.split("\r?\n")) {
            String t = line.trim();
            if (t.startsWith(key + "=")) {
                return t.substring(key.length() + 1).trim();
            }
        }
        return "";
    }

    // ============================================================ 正向：别名 + 真 jar 并存

    /**
     * ★ 主 jar 与 <b>字节相同的 -shaded 别名</b>并存时，必须解析出 <b>1</b> 个，
     * 而且选中的那个<b>不是</b>别名。
     *
     * <p>这就是主理人 2026-10-03 撞到的那一幕的最小复现。
     */
    @Test
    void theShadeAliasDoesNotCountAsASecondJar() throws Exception {
        touchJar(FIXTURE_TARGET, "skyisland-9.9.9-TEST.jar");
        touchJar(FIXTURE_TARGET, "skyisland-9.9.9-TEST-shaded.jar");
        // shade 之前的 thin 备份：本来就不匹配 glob，放进来是为了让夹具贴近真实现场。
        touchJar(FIXTURE_TARGET, "original-skyisland-9.9.9-TEST.jar");

        buildProbe(FIXTURE_ROOT.toAbsolutePath().toString() + "\\");
        String out = runProbe();

        assertEquals("1", valueOf(out, "RESULT_JARCOUNT"),
                "别名必须被排除（实测现场：主 jar + -shaded 别名 + original 备份）。探针输出：\n" + out);
        assertTrue(valueOf(out, "RESULT_JAR").endsWith("skyisland-9.9.9-TEST.jar"),
                "必须选中主 jar 而不是别名。实际选中：" + valueOf(out, "RESULT_JAR"));
        assertFalse(valueOf(out, "RESULT_JAR").endsWith("-shaded.jar"),
                "绝不能把 -shaded 别名当成可运行 jar");
    }

    // ============================================================ 反向：两个真 jar 仍然必须报错

    /**
     * ★ 两个<b>真</b> jar（不同版本号）时必须仍然判为"不唯一"。
     *
     * <p>这一条是给"修法"设的边界：把守卫改成"永远返回 1"也能让上一条变绿，
     * 但那就把"版本升了却跑旧 jar"这个真正要防的东西一起放过了。
     * 守卫必须<b>只</b>放过别名与备份，其余情况照旧拦下。
     */
    @Test
    void twoRealJarsAreStillRejectedAsNonUnique() throws Exception {
        touchJar(FIXTURE_TARGET, "skyisland-9.9.8-OLD.jar");
        touchJar(FIXTURE_TARGET, "skyisland-9.9.9-NEW.jar");
        touchJar(FIXTURE_TARGET, "skyisland-9.9.9-NEW-shaded.jar");

        buildProbe(FIXTURE_ROOT.toAbsolutePath().toString() + "\\");
        String out = runProbe();

        assertEquals("2", valueOf(out, "RESULT_JARCOUNT"),
                "两个真 jar 必须仍然被判为不唯一 —— 唯一性校验是这条守卫真正要防的东西。探针输出：\n" + out);
    }

    // ============================================================ 结构：失败时要能让人继续

    /**
     * 失败分支必须把 {@code target\} 里实际有什么打出来。
     *
     * <p>理由与 {@code tmp/verify_launcher_jar_resolution.js} 的注释同源：
     * 一个只会说"found 2"的守卫，把人卡在"我到底有什么"这一步；
     * 一条 {@code dir /b} 能让人 3 秒内继续。守卫的价值有一半在失败信息里。
     */
    @Test
    void theFailureBranchListsWhatIsActuallyThere() throws IOException {
        String bat = Files.readString(LAUNCHER, StandardCharsets.ISO_8859_1);
        assertTrue(bat.contains("dir /b"),
                "失败分支应当把 target\\ 的实际内容列出来，而不是只报一个数字");
        assertTrue(bat.contains("original-*.jar and *-shaded.jar are ignored on purpose."),
                "失败信息要说明哪些文件是被<b>故意</b>忽略的 —— 否则人会以为守卫坏了");
    }

    // ============================================================ 那个踩过的坑，钉在这里

    /**
     * ★ 排除别名必须用<b>定长后缀比较</b>，不能靠 cmd 的字符串通配。
     *
     * <p>实测：{@code if /i "skyisland-0.3.2-...-shaded.jar" neq "skyisland-*-shaded"}
     * 判定为<b>不等</b>（cmd 的 {@code if} 字符串比较不做通配，只有 {@code if exist} 做），
     * 于是别名被计入，{@code JARCOUNT=2}，守卫原样报错 —— 第一版修法就是这样失败的。
     *
     * <p>正确写法是延迟展开的定长子串 {@code !CAND:~-11!=="-shaded.jar"}。
     * 这条断言把这个写法钉住：改成通配的那一版不会让上一条"跑 cmd"的用例变绿吗？会。
     * 但它会让本条立刻变红，而失败信息直接指向"通配不被支持"这个具体原因。
     */
    @Test
    void theAliasIsExcludedByAFixedWidthSuffixCompareNotAWildcard() throws IOException {
        String bat = Files.readString(LAUNCHER, StandardCharsets.ISO_8859_1);
        assertTrue(bat.contains("!CAND:~-11!"),
                "必须用定长子串比较 !CAND:~-11! 来识别 -shaded.jar 后缀");
        assertTrue(bat.contains("==\"-shaded.jar\""),
                "被比较的字面量必须是 \"-shaded.jar\"（11 个字符，与 ~-11 对应）");
        assertTrue(bat.contains("setlocal enabledelayedexpansion"),
                "循环体内的 !CAND! 需要延迟展开；没有它这段会静默失效");
        List<String> wildcardStyle = new ArrayList<>();
        for (String line : bat.split("\r?\n", -1)) {
            // ★ 必须跳过 REM 注释行：本守卫自己在注释里写了那句**错误的**写法作为反例
            //   （"cmd string comparison does NOT glob -- \"if /i neq skyisland-*-shaded\""），
            //   不排除注释的话，这条断言会被它自己钉的那段反例满足 —— 而那段反例恰恰是
            //   永远不该出现在可执行行里的东西。这是本项目记过的老坑：
            //   **扫描断言必须先剥注释**（见 SourceScan.withoutComments 的来历）。
            if (line.trim().regionMatches(true, 0, "REM", 0, 3)) {
                continue;
            }
            if (line.contains("neq") && line.contains("*")) {
                wildcardStyle.add(line.trim());
            }
        }
        assertTrue(wildcardStyle.isEmpty(),
                "不要用 if ... neq \"...*...\" 来排除别名：cmd 的字符串比较不做通配，"
                        + "实测会把别名一起数进去。发现这些行：" + wildcardStyle);
    }
}

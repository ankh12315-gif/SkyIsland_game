package com.skyisland.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原生启动器（{@code launcher/skyisland_launcher.c} → Desktop 的 .exe）的<b>口径守卫</b>。
 *
 * <h2>它守的是一个"源码改了、但没人重编"的坑 —— 而且已经真实发生过</h2>
 * 2026-10-03 主理人问"桌面的启动也一并改好吧"。当时 {@code launcher/SkyIsland.exe}
 * 的时间戳是 <b>09-23</b>，而源码里硬编码的仍是 <b>M2.1</b> 的世界：
 * <pre>
 *   static const wchar_t* const kWorldName = L"m21-play";
 *   static const wchar_t* const kSaveRelDir = L"tmp\\m21-play-saves";
 * </pre>
 * 后果是：<b>桌面的入口点开的是另一个世界</b> —— 那个存档经过 12 次死亡后子弹已打光，
 * 背包里只剩一把手枪 + 圆石 + 泥土。看不到 SMG、看不到步枪、看不到材料包，
 * 而所有门禁、单测、{@code play-m3.bat} 全部是绿的。
 * <p>
 * 更糟的一层：{@code .gitignore} 排除了 {@code launcher/*.exe}
 * （为了避免 300 KB 二进制 diff），于是<b>源码是唯一的入库产物</b>，
 * "重编"这一步<b>没有任何东西会提醒你去做</b>。
 * 于是 {@code play-m3.bat} 每次都在修，桌面那个 exe 一直躺着不动 ——
 * 这就是"两处入口口径漂移"，而本项目最贵的一次假绿灯正是同一形状。
 *
 * <h2>为什么三条断言都在读 C 源码，而不是读编出来的 exe</h2>
 * 因为.exe <b>不入库</b>，测试环境里可能根本没有它（fresh clone 就没有），
 * 断言一个不存在的二进制等于没有断言。所以本类守的是<b>源码里的口径</b>，
 * 再用一条<b>结构</b>断言守"构建脚本存在且会产出两个变体"。
 *
 * <h2>★ 为什么不直接断言"exe 的时间戳比源码新"</h2>
 * 想过，很脆：{@code mvn clean package} 会重写所有 class 但不碰 exe，
 * 而一次 {@code git checkout} 就能让时间戳关系反转。它会把"我刚构建过"和
 * "我重编过 launcher"混为一谈 —— 而这正是本项目最警惕的<b>脆代理量</b>。
 * 判据只落在"源码里写的是什么口径"，那才是真正会决定运行行为的东西。
 */
class NativeLauncherWiringTest {

    private static final Path C_SOURCE = Path.of("launcher", "skyisland_launcher.c");
    private static final Path RC_SOURCE = Path.of("launcher", "skyisland_launcher.rc");
    private static final Path BUILD_SCRIPT = Path.of("tmp", "build_launcher.js");

    private static String c() throws IOException {
        return Files.readString(C_SOURCE, StandardCharsets.UTF_8);
    }

    /**
     * 把 {@code C_SOURCE} 里的注释剥掉再返回。
     *
     * <p>★ 本项目的老坑，第三次以新形式出现：{@code C_SOURCE} 的文件头<b>大量解释</b>
     * 这几个常量的来历（包括本文件在做的事），所以"全文件 contains"会被
     * <b>注释本身</b>满足 —— 把 {@code kWorldName} 改回 M2.1，断言照样绿。
     * 参见 {@code SourceScan.withoutComments} 与 {@code LauncherJarResolutionTest}
     * 里对 {@code REM} 行做的同一件事。
     */
    private static String cWithoutComments() throws IOException {
        StringBuilder out = new StringBuilder();
        boolean inBlock = false;
        String src = c();
        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (inBlock) {
                if (ch == '*' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                    inBlock = false;
                    ++i;
                    out.append(' ');
                } else {
                    out.append(ch == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (ch == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                inBlock = true;
                ++i;
                out.append("  ");
                continue;
            }
            if (ch == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    ++i;
                }
                out.append('\n');
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }

    // ============================================================ ① 世界名口径

    /**
     * ★ 启动器必须指向 <b>M3</b> 的试玩世界，不是 M2.1 的。
     *
     * <p>这是本类存在的第一理由：主理人实际双击的正是桌面那个 exe，
     * 而它在 2026-10-03 之前一直开的是 {@code m21-play}。
     */
    @Test
    void theNativeLauncherPointsAtTheM3PlayWorldNotTheM21One() throws IOException {
        String src = cWithoutComments();

        assertTrue(src.contains("kWorldName = L\"m3-play\""),
                "★ 桌面启动器的世界名必须是 m3-play —— 2026-10-03 之前它是 m21-play，"
                        + "于是桌面入口开的是一个子弹已打光、只剩一把手枪的老存档，"
                        + "而 play-m3.bat 与全部门禁都是绿的。"
                        + "（判据读的是去掉注释后的源码：文件头解释过这些常量。）");

        assertTrue(src.contains("tmp\\\\m3-play-saves") || src.contains("tmp\\m3-play-saves"),
                "存档目录必须同步改成 tmp\\m3-play-saves，否则换个目录等于把旧存档的"
                        + "子弹耗尽状态带进 M3 试玩");

        assertTrue(src.contains("tmp\\\\m3-play-settings.json") || src.contains("tmp\\m3-play-settings.json"),
                "设置文件路径必须同步");

        // 反向：m21 字样不得再出现在可执行代码里（注释里可以，因为它在讲历史）。
        List<String> stale = new ArrayList<>();
        int line = 0;
        for (String l : src.split("\r?\n")) {
            line++;
            if (l.contains("m21")) {
                stale.add("line " + line + ": " + l.trim());
            }
        }
        assertTrue(stale.isEmpty(),
                "去掉注释后的源码里不该再有 m21 —— 桌面入口必须和 play-m3.bat 同世界。发现：" + stale);
    }

    // ============================================================ ② 试玩开关

    /**
     * ★ 桌面启动器必须带与 {@code play-m3.bat} <b>完全相同</b>的两个试玩开关。
     *
     * <p>不一致的后果是"两处入口给出两个游戏"：无限后备、DEV 装备（步枪 + 材料包）。
     * 门禁与 bat 都有这两个开关，exe 没有 —— 那桌面入口就成了唯一一个
     * "看不到新内容"的地方，而它恰恰是主理人最常用的那个。
     */
    @Test
    void theNativeLauncherPassesTheSameTwoPlaySwitchesAsTheBat() throws IOException {
        String src = cWithoutComments();

        assertTrue(src.contains("-Dskyisland.infiniteReserve=true"),
                "★ 桌面启动器必须带 -Dskyisland.infiniteReserve=true —— "
                        + "play-m3.bat 有，缺了它两处入口的后备弹药口径就不同"
                        + "（判据读去掉注释后的源码，文件头写了这个参数名。）");

        assertTrue(src.contains("-Dskyisland.loadout=dev"),
                "★ 桌面启动器必须带 -Dskyisland.loadout=dev —— 这是玩家能看到步枪与"
                        + "过渡材料包的那个开关；缺了它桌面入口只有手枪 + 冲锋枪");

        // 两个开关必须一起出现在同一个常量里，而不是散落在别处：
        // 散落时"其中一个被删掉"的失败方向会指向错误的行。
        assertTrue(src.contains("kExtraSwitches"),
                "两个开关应当收敛到 kExtraSwitches 一个常量，"
                        + "这样它们被一起删/一起改，而不是各自漂移");
    }

    // ============================================================ ③ jar 解析

    /**
     * ★ jar 解析必须<b>排除 shade 插件的字节相同别名</b>，且用定长尾比较。
     *
     * <p>桌面 exe 复现了和 {@code play-m3.bat} <b>一字不差</b>的故障
     * （{@code Expected exactly 1 jar ... found 2}）—— 实测方式是：把
     * {@code target/} 里的主 jar 复制一份成 {@code -shaded.jar} 再跑它。
     * 所以修复规则必须与 bat 那一版<b>同源</b>，不能各写各的。
     */
    @Test
    void theNativeLauncherExcludesTheShadeAliasLikeTheBatDoes() throws IOException {
        String src = cWithoutComments();

        assertTrue(src.contains("w_is_shade_artefact"),
                "必须有一个专门的谓词来识别 shade 产物，而不是把判断内联在循环里 —— "
                        + "内联时『已排除』这件事不会被任何断言看见");

        // 定长尾比较，不是通配。措辞与 LauncherJarResolutionTest 的理由同源：
        // bat 那边的第一版修法就是误以为 `if neq "*-shaded"` 支持通配。
        assertTrue(src.contains("-shaded.jar") && src.contains("_wcsicmp"),
                "排除必须基于字符串比较（_wcsicmp / _wcsnicmp），"
                        + "不能用 FindFirstFile 的通配或 strstr 之类的子串匹配");

        assertTrue(src.contains("original") && src.contains("_wcsnicmp"),
                "original-*.jar 也必须在口径里被处理（它不匹配 skyisland-* glob，"
                        + "但源码里那个 skyisland- 前缀检查要留着并被显式说明，"
                        + "否则会有人以为它被漏了）");

        // 失败信息必须列出目录内容：play-m3.bat 那一版补了 `dir /b`，
        // 理由是"守卫的价值有一半在失败信息里"，这里必须同样。
        assertTrue(src.contains("Which is actually there"),
                "jar 不唯一时的报错必须把 target\\ 的实际内容列出来，"
                        + "而不是只说一个数字（与 play-m3.bat 的 `dir /b` 同源理由）");
    }

    // ============================================================ ④ JDK 口径

    /**
     * ★ 内置 JDK 25 必须<b>优先于</b> {@code JAVA_HOME}。
     *
     * <p>实测 2026-10-03：老 exe 的查找顺序是 {@code SKYISLAND_JDK > JAVA_HOME > 内置}，
     * 而本机 {@code JAVA_HOME} 指向 <b>jdk-23</b>，于是桌面入口跑的是 Java 23，
     * 而所有日志、门禁、{@code play-m3.bat} 说的都是 25。
     * 这种差异不会立刻炸，它会在某个版本相关的行为上炸，而那时证据里
     * <b>没有任何一处</b>指向真正的原因。
     */
    @Test
    void theBuiltInJdk25IsPreferredOverJavaHome() throws IOException {
        String src = cWithoutComments();

        int builtIn = src.indexOf("kDefaultJdkDir, L\"bin\\\\java.exe\"");
        int javaHome = src.indexOf("L\"JAVA_HOME\"");

        assertTrue(builtIn >= 0, "找不到内置 JDK 的探测（kDefaultJdkDir/bin\\java.exe）");
        assertTrue(javaHome >= 0, "找不到 JAVA_HOME 探测");

        assertTrue(builtIn < javaHome,
                "★ 内置 JDK 的探测必须排在 JAVA_HOME 之前 —— 本机 JAVA_HOME 指向 jdk-23，"
                        + "老顺序让桌面入口跑 Java 23 而所有门禁说 25。"
                        + "内置位置：" + builtIn + " / JAVA_HOME 位置：" + javaHome);

        assertTrue(src.contains("D:\\\\software\\\\jdk-25"),
                "内置 JDK 仍是 D:\\software\\jdk-25（本项目用它构建）");
    }

    // ============================================================ ⑤ 构建入口

    /**
     * 必须有一个<b>入库的</b>构建脚本。
     *
     * <p>这是本类最结构性的断言。{@code .gitignore} 排除了 {@code launcher/*.exe}，
     * 所以"重编"这一步<b>没有任何机制会提醒你</b> —— 而"源码改了、exe 没编"
     * 正是 2026-10-03 那次桌面入口漂移的成因。
     * 有了入库的构建脚本 + 白名单，fresh clone 至少知道该跑什么。
     */
    @Test
    void theLauncherHasATrackedBuildScript() throws IOException {
        assertTrue(Files.exists(BUILD_SCRIPT),
                "找不到 " + BUILD_SCRIPT + " —— launcher/*.exe 被 .gitignore 排除，"
                        + "所以构建脚本必须入库，否则换台机器/换个克隆根本编不出 exe");

        String gitignore = Files.readString(Path.of(".gitignore"), StandardCharsets.UTF_8);
        assertTrue(gitignore.contains("!tmp/build_launcher.js"),
                "tmp/build_launcher.js 必须在 .gitignore 的白名单里（tmp/* 被整体忽略，"
                        + "尾随斜杠的目录忽略规则会让子树整体不可匹配，白名单必须显式放行）");

        String js = Files.readString(BUILD_SCRIPT, StandardCharsets.UTF_8);
        assertTrue(js.contains("skyisland_launcher.c") && js.contains("skyisland_launcher.rc"),
                "构建脚本必须真的编译那两个源文件");
        assertTrue(js.contains("SkyIsland-console.exe"),
                "必须同时产出 SkyIsland-console.exe —— 双击用的变体和取证用的变体"
                        + "只有一个可构建，取证通道就没了");
        assertTrue(js.contains("SKYISLAND_KEEP_CONSOLE"),
                "console 变体靠 -DSKYISLAND_KEEP_CONSOLE=1 区分，别名写错就编出两个一样的 exe");

        // .exe 不入库是刻意的，rc 里的版本与描述却必须跟着口径更新。
        String rc = Files.readString(RC_SOURCE, StandardCharsets.UTF_8);
        assertTrue(rc.contains("play-m3.bat"),
                "rc 的 Comments 还在说 mimics play-m2.bat —— 口径改了、资源里的说明也要改，"
                        + "否则在属性页里读到的仍是 M2 的故事");
    }

    // ============================================================ ⑥ 两处入口不许漂移

    /**
     * ★ {@code play-m3.bat} 与桌面 exe 的世界名/开关必须<b>一致</b>。
     *
     * <p>这是本类的落点：前五条各自守一个字段，这一条守<b>关系</b>。
     * 历史上两者已经漂移过一次（bat 早就 m3-play / dev，exe 还是 m21-play / 无开关），
     * 而且<b>没有任何测试会变红</b> —— 因为它们分别在各自的轨道上都"符合自己的定义"。
     */
    @Test
    void theBatAndTheExeAgreeOnWorldAndSwitches() throws IOException {
        Path bat = Path.of("play-m3.bat");
        assertTrue(Files.exists(bat), "找不到 play-m3.bat，无法比对两个入口");

        // 纯 ASCII 的 .bat 用 ISO-8859-1 读回来（1 字节 → 1 字符），
        // 顺便保证本断言不会因为编码问题误判。
        String batSrc = Files.readString(bat, StandardCharsets.ISO_8859_1);
        String cSrc = cWithoutComments();

        // 找启动 java 的那一行（两个文件里"java.exe" 和 "-jar" 同行）。
        String batJavaLine = null;
        for (String line : batSrc.split("\r?\n")) {
            if (line.contains("java.exe") && line.contains("-jar")) {
                batJavaLine = line;
                break;
            }
        }
        assertNotEquals(null, batJavaLine, "play-m3.bat 里找不到启动 java 的那一行（结构变了？）");

        assertTrue(batJavaLine.contains("-Dskyisland.loadout=dev")
                        && cSrc.contains("-Dskyisland.loadout=dev"),
                "两个入口都必须带 loadout=dev");
        assertTrue(batJavaLine.contains("-Dskyisland.infiniteReserve=true")
                        && cSrc.contains("-Dskyisland.infiniteReserve=true"),
                "两个入口都必须带 infiniteReserve=true");

        assertTrue(batJavaLine.contains("m3-play") && cSrc.contains("m3-play"),
                "两个入口都必须开 m3-play 世界");

        assertFalse(batJavaLine.contains("m21") || cSrc.contains("m21"),
                "任一入口还带着 m21 就是漂移");
    }
}

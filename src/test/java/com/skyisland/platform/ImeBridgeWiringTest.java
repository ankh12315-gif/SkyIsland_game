package com.skyisland.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ <b>IME 桥的接线与降级守卫</b>。
 *
 * <h2>这个修复解决什么</h2>
 * 中文 Windows 的输入法默认开着「用 SHIFT 切换中/英文」。游戏窗口挂着 IME 上下文时，
 * 按 SHIFT 切的是**输入法状态**而不是游戏按键 —— 于是飞行下降（SHIFT）静默失灵。
 * 做法是把 IME 从窗口上摘掉（{@code ImmAssociateContextEx(hwnd, NULL, IACE_DEFAULT)}），
 * 失焦后系统输入法自动恢复，正是大型网游的行为。
 *
 * <h2>为什么判据读源码而不直接测行为</h2>
 * 「摘除后窗口没有 IME」需要真窗口 + 真人按 SHIFT。本类守的是<b>接线与降级</b>；
 * 那条状态跃迁由<b>实机运行</b>取证（日志里那两行
 * {@code IME：摘除前=有，摘除后=无}），不靠这里假装能测。
 *
 * <h2>判据一律读<b>去掉注释后的源码</b></h2>
 * 本类的注释里必然出现 {@code glfwFocusWindow}、{@code ImeBridge} 等字样
 * （它要解释的就是"为什么必须在聚焦之后摘"），
 * 全文 {@code contains} 会被说明文字满足 —— 那条断言就永远绿。
 */
class ImeBridgeWiringTest {

    private static final Path WINDOW =
            Path.of("src", "main", "java", "com", "skyisland", "render", "Window.java");
    private static final Path GAME =
            Path.of("src", "main", "java", "com", "skyisland", "game", "SkyIslandGame.java");
    private static final Path BRIDGE =
            Path.of("src", "main", "java", "com", "skyisland", "platform", "ImeBridge.java");
    private static final Path C_SRC = Path.of("launcher", "skyisland_ime.c");
    private static final Path BUILD = Path.of("tmp", "build_launcher.js");

    private static String codeWithoutComments(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    @Test
    @DisplayName("★ 窗口创建后真的调用了 IME 桥（不是只把类写出来）")
    void theBridgeIsActuallyCalledFromWindowCreation() throws IOException {
        String win = codeWithoutComments(WINDOW);

        // ★ 判据必须是**调用点** `detachIme(handle);`，而不是"文件里出现 ImeBridge"。
        //   反向验证注入 A（把调用换成注释）时，第一版判据**照样全绿** ——
        //   因为 `private static void detachIme(long handle)` 这个**方法定义**
        //   里就含 "ImeBridge"。症状是"守卫没守住接线"，真因是判据把
        //   "定义"当成了"调用" —— 而这正是本项目 M2.1 付过学费的那类假绿。
        //   带参数 + 带分号的形式只出现在调用点。
        // ★ 判据必须锚在**创建处那一次**调用上，不能只判"文件里有 detachIme(handle,"。
        //   反向验证注入 A（删掉创建处的调用）时第一版全绿 —— 因为
        //   `claimForeground` 里还有一次 `detachIme(handle, ...)` 满足它。
        //   与注入 E 那条同源：**断言被另一个调用点顶替了**。
        //   ⇒ 带上前缀里那段"何时"的字面量，只有创建处才有。
        assertTrue(win.contains("detachIme(handle, \"窗口创建后\")"),
                "★ Window 创建后必须**调用** ImeBridge —— 只把方法写出来而不接线，"
                        + "编译通过、单测全绿、门禁全绿，而 SHIFT 仍然失灵。"
                        + "本项目为「已定义、从未被调用」付过 M2.1 一次抓出 8 处的学费");
        assertTrue(win.contains("ImeBridge.installForWindow(handle)"),
                "★ 必须真的调用 installForWindow（装消息子类才是解决问题的那一步）");
        assertTrue(win.contains("ImeBridge.disableForWindow(handle)"),
                "★ 必须真的调用 disableForWindow —— 只装子类不摘 IMM 上下文等于少了一半");
    }

    @Test
    @DisplayName("★ 摘除必须发生在 glfwFocusWindow 之后")
    void theDetachHappensAfterFocus() throws IOException {
        String win = codeWithoutComments(WINDOW);

        int focus = win.indexOf("glfwFocusWindow(handle)");
        int detach = win.indexOf("detachIme(handle,");
        assertTrue(focus >= 0, "找不到 glfwFocusWindow(handle)");
        assertTrue(detach >= 0, "找不到 detachIme(handle, ...) 调用");
        assertTrue(detach > focus,
                "★ 摘 IME 必须在 glfwFocusWindow **之后** —— 聚焦会重新激活 IME，"
                        + "先摘后聚焦等于没摘。那正是「看起来做了、实测仍然失灵」的顺序错");

        // ★ 而且 claimForeground 里**又**聚焦了一次，所以那里必须再摘一次。
        //   第一版只摘创建那一处，于是 claimForeground 把它撤销了 ——
        //   而实测 IMM 上下文确实回到了「有」。这条判据就是为那次返工立的。
        int claim = win.indexOf("claimForeground(int timeoutMs)");
        assertTrue(claim >= 0, "找不到 claimForeground");
        String claimBody = win.substring(claim);
        assertTrue(claimBody.contains("detachIme(handle,"),
                "★ claimForeground 会再次 glfwFocusWindow，而聚焦会重新激活 IME —— "
                        + "那里必须再摘一次。第一版只摘创建那一处，"
                        + "实测「摘除前=有、摘除后=无」在几十毫秒后就被撤销了");
    }

    @Test
    @DisplayName("★ 桥不可用时优雅降级，绝不阻止游戏启动")
    void theBridgeDegradesInsteadOfBlockingStartup() throws IOException {
        String bridge = codeWithoutComments(BRIDGE);
        String win = codeWithoutComments(WINDOW);

        // 加载失败必须是"记录状态"，不是抛异常
        assertFalse(bridge.contains("throw new RuntimeException"),
                "★ ImeBridge 加载失败不得抛异常 —— 一个可选的原生辅助件"
                        + "绝不能有能力阻止游戏启动");
        assertTrue(bridge.contains("isAvailable()"),
                "必须提供 isAvailable()，让调用方能问「能不能用」而不是靠 try/catch");

        // Window 侧必须先判可用性再调用，且不得让异常冒泡
        assertTrue(win.contains("if (!com.skyisland.platform.ImeBridge.isAvailable())"),
                "★ Window 必须先判 isAvailable() —— 直接调 native 方法在 dll 缺失时"
                        + "抛 UnsatisfiedLinkError，会一路冒泡到主循环把窗口打不开");
        assertTrue(win.contains("noteWarning"),
                "降级时必须留一条警告 —— 静默降级会让「SHIFT 还是会被切」变成无解现象");
    }

    @Test
    @DisplayName("★ 必须留下 before/after 两条读数，且先读 before 再 install")
    void theBeforeAndAfterStateIsLogged() throws IOException {
        String win = codeWithoutComments(WINDOW);

        assertTrue(win.contains("isEnabledForWindow(handle)")
                        && win.contains("disableForWindow(handle)"),
                "★ 必须既查摘除前的状态、又调摘除 —— 只调不查的话，"
                        + "一个失败的 API 调用会被记成成功，而那正是会骗人的日志");

        // ★ 顺序：先读 before，再 install。
        //   nInstallForWindow 自己就摘一次上下文，所以先装后读的话
        //   「摘除前」永远读到"无" —— 那是**自己骗自己**的日志。
        //   上一版就是这个顺序，它输出了「IMM前=无」，看起来像"本来就没有 IME"，
        //   实际上只是量错了地方，把一个真问题误判成"不用改"。
        int before = win.indexOf("isEnabledForWindow(handle)");
        int install = win.indexOf("installForWindow(handle)");
        assertTrue(before >= 0 && install >= 0,
                "找不到 isEnabledForWindow 或 installForWindow 调用");
        assertTrue(before < install,
                "★ 必须**先**读摘除前状态再 install —— install 内部就会摘一次，"
                        + "先装后读会让「摘除前」恒为「无」，日志因此失去意义");
    }

    @Test
    @DisplayName("★ 诊断计数必须在运行末尾读，而不是装完立刻读")
    void theCountersAreReadAfterRealFramesNotAtInstall() throws IOException {
        String win = codeWithoutComments(WINDOW);
        String game = codeWithoutComments(GAME);

        // 装子类的那一刻读计数必然是 0 —— 一条消息都还没 pump 过。
        // 第一版就在安装后同一函数里读，于是输出了「子类被调=0 次」
        // 并据此发出"方向选错了"的警告，而那个 0 不证明任何事。
        int install = win.indexOf("installForWindow(handle)");
        int diag = win.indexOf("imeDiagnostics()");
        assertTrue(diag >= 0, "必须提供运行末尾的诊断读取口");
        assertTrue(diag > install,
                "★ 诊断计数必须在安装之后、真实运行之后再读 —— "
                        + "装完立刻读必然是 0，而那个 0 会把人引向错误的结论");
        assertTrue(game.contains("imeDiagnostics()"),
                "★ 诊断必须进**测量摘要** —— 那是唯一在一段真实运行之后才会读的通道");
    }

    @Test
    @DisplayName("C 源必须入库、dll 必须不入库（源是唯一可追溯的产物）")
    void theCSourceIsTrackedAndBuilt() throws IOException {
        assertTrue(Files.exists(C_SRC), "找不到 " + C_SRC
                + " —— dll 与 exe 一样被 .gitignore 排除，源是唯一的入库产物。"
                + "没有它，换个克隆就既编不出也读不懂这个修复");

        // ★ 编译产物**必须**被忽略。这不是洁癖 —— 本轮漏过一次：
        //   `git status` 直接把 43 KB 的 dll 列成 `??`，
        //   而"忘了加忽略规则"的默认后果是**它会被提交进库**。
        //   二进制入库之后，下一个改 C 的人会撞上「我改了怎么没变化」
        //   （构建可能加载库里那份旧的）。
        String gitignore = Files.readString(Path.of(".gitignore"), StandardCharsets.UTF_8);
        assertTrue(gitignore.contains("/launcher/*.dll"),
                "★ .gitignore 必须排除 /launcher/*.dll —— 与 .exe 同理。"
                        + "漏掉的后果不是「多一个二进制入库」，而是下次构建可能加载旧的那份");

        // ★ 两条都必须读**剥掉注释的**构建脚本，且用**调用点**而不是名字：
        //   反向验证注入 E（删掉 `buildImeBridge();` 那一行调用）时第一版全绿，
        //   因为 `function buildImeBridge()` 的**定义**里就含同一串；
        //   注入 F（删掉 `'-limm32',`）也全绿，因为我给这个函数写的
        //   文档注释里就写着 "WHY -limm32: ..."，而测试读的是原文。
        //   两个症状都指向同一个真因：**判据同时被"定义"与"注释"满足**。
        String build = codeWithoutComments(BUILD);
        assertTrue(build.contains("buildImeBridge();"),
                "★ 构建脚本必须**调用** 编 dll 的那一步 —— 源码在库里而没人编它，"
                        + "就是本项目记过的那个「冻结二进制没人重建」的同一个坑。"
                        + "（判据带分号：函数定义不含分号）");
        assertTrue(build.contains("skyisland_ime.c"),
                "构建脚本必须引用真实的 .c 文件名");
        // -limm32 是 ImmAssociateContextEx / ImmGetContext 的导入库。
        assertTrue(build.contains("-limm32"),
                "★ 链接必须带 -limm32 —— ImmAssociateContextEx / ImmGetContext 在 imm32.lib"
                        + "（判据读剥掉注释的脚本：本函数的文档注释里就解释着 -limm32，"
                        + "读原文会被那段说明满足）");
    }

    @Test
    @DisplayName("★ 路径兜底：找不到 dll 时要说清试过哪些位置")
    void theLoadDiagnosticsNameTheCandidatesTried() throws IOException {
        String bridge = codeWithoutComments(BRIDGE);

        assertTrue(bridge.contains("loadStatus()"),
                "必须提供 loadStatus() —— 一条「IME 桥没加载」而不说为什么的警告，"
                        + "会让人以为游戏坏了");
        // ★ 必须断言**候选表达式本身**，不能只判源码里出现 "launcher"。
        //   反向验证注入 H（删掉 launcher/ 那个候选）时第一版全绿 ——
        //   因为诊断字符串里就写着"已试过: … / launcher/ / target/"。
        //   症状是"守卫没守住候选路径"，真因是判据被**一句说明文字**满足。
        //   这是本项目第三次栽在 `contains("某个词")` 上（前两次分别是
        //   方法定义冒充调用点、文档注释冒充链接参数）。
        //   ⇒ 纪律：凡"某个位置必须被覆盖"的判据，断言那个**表达式**。
        assertTrue(bridge.contains("cwd.resolve(\"launcher\").resolve(LIB_FILE)"),
                "★ 加载候选必须覆盖 launcher/ —— dll 编译产物就在那里，"
                        + "而游戏运行时的工作目录是项目根"
                        + "（判据断言候选表达式：只判源码里出现 launcher 会被"
                        + "诊断字符串满足）");
        assertTrue(bridge.contains("System.getProperty(\"skyisland.imeBridge\""),
                "必须支持 -Dskyisland.imeBridge=<绝对路径> 覆盖 —— "
                        + "构建产物不在预期位置时，这是唯一的手动出路");
    }
}
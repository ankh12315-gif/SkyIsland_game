package com.skyisland.platform;

import org.lwjgl.glfw.GLFWNativeWin32;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ★ <b>把输入法（IME）从游戏窗口上摘掉</b>，让 SHIFT 只属于游戏。
 *
 * <h2>为什么需要它</h2>
 * 中文 Windows 的输入法（微软拼音）默认开着「用 SHIFT 键切换中/英文」。
 * 当游戏窗口拥有 IME 上下文时，按 SHIFT 切换的是**输入法状态**而不是游戏按键 ——
 * 于是创造飞行的下降键（SHIFT）**静默失灵**，玩家除了改系统设置别无办法。
 * 大型网游的做法是：游戏窗口聚焦时禁用 IME，失焦后自动恢复。
 *
 * <h2>为什么需要一个自带的 .dll</h2>
 * 正确的 API 是 {@code ImmAssociateContextEx(hwnd, NULL, IACE_DEFAULT)}（imm32.dll）。
 * 动手前核实过两条"看起来更省事"的路都不通：
 * <ul>
 *   <li><b>LWJGL 没有绑 imm32</b>：{@code org.lwjgl.system.windows.User32} 上只有
 *       {@code WM_IME_*} 消息常量，没有任何 {@code Imm*} 函数。</li>
 *   <li><b>GLFW 自己的原生库根本没 import imm32</b>：{@code objdump -p glfw.dll}
 *       只列 KERNEL32 / USER32 / GDI32 / SHELL32 ⇒ {@code GLFW_IME} 那个窗口 hint
 *       <b>在 Windows 上不是出路</b>。</li>
 * </ul>
 * ⇒ 只能自己写 C。本项目本来就用 mingw-w64 编启动器，工具链现成。
 *
 * <h2>★ 为什么额外暴露一个查询接口</h2>
 * 「我们调了那个 API」<b>不等于</b>「它生效了」。{@link #isEnabledForWindow(long)}
 * 让游戏把调用前后的状态打进日志、让测试断言这个跃迁 ——
 * 于是这个修复是<b>可自动验证</b>的，而不是"你按 SHIFT 试试看"。
 * 理由与本项目反复吃过的那类亏同源：一个会自己说谎的"成功"比没有更坏。
 *
 * <h2>★ 加载失败必须优雅降级</h2>
 * 找不到 dll 时 {@link #isAvailable()} 返回 false，游戏打<b>一条</b>警告说明
 * 什么还能用、什么不能，然后照常运行。
 * 一个可选的原生辅助件**绝不能有能力阻止游戏启动** ——
 * 那正是本项目反复付过学费的失败形态（"新构建有问题" → 整个入口拒绝启动）。
 *
 * @see #loadStatus() 诊断信息
 */
public final class ImeBridge {

    /** dll 文件名。放在 {@code launcher/} 下，与启动器 exe 同处。 */
    public static final String LIB_FILE = "skyisland-ime.dll";

    private static final String LOAD_ERROR;

    static {
        String err = load();
        LOAD_ERROR = err;
    }

    private ImeBridge() {
    }

    /** 桥是否可用。false 时 SHIFT 仍会被输入法吃掉（其余功能不受影响）。 */
    public static boolean isAvailable() {
        return LOAD_ERROR == null;
    }

    /**
     * 加载失败的诊断信息；成功时返回 {@code null}。
     *
     * <p>存在的理由：一条"IME 桥没加载"而不说为什么的警告，会让人以为游戏坏了。
     * 路径候选必须全部列出来 —— 找不到 dll 时最想知道的就是"它到底去哪了"。
     */
    public static String loadStatus() {
        return LOAD_ERROR;
    }

    private static String load() {
        String override = System.getProperty("skyisland.imeBridge", "");
        if (!override.isBlank()) {
            return tryLoad(Path.of(override), "-Dskyisland.imeBridge=" + override);
        }
        // 候选顺序：先项目根（两个入口都先 cd 到项目根），再 launcher/，
        // 最后交给 java.library.path 兜底。
        Path cwd = Path.of(System.getProperty("user.dir", "."));
        String last = null;
        for (Path candidate : new Path[]{
                cwd.resolve(LIB_FILE),
                cwd.resolve("launcher").resolve(LIB_FILE),
                cwd.resolve("target").resolve(LIB_FILE)}) {
            last = tryLoad(candidate, candidate.toString());
            if (last == null) {
                return null;
            }
        }
        try {
            System.loadLibrary("skyisland-ime");
            return null;
        } catch (UnsatisfiedLinkError e) {
            return "System.loadLibrary(\"skyisland-ime\") 失败: " + e.getMessage()
                    + "（已试过: " + cwd.resolve(LIB_FILE) + " / launcher/ / target/）";
        }
    }

    private static String tryLoad(Path p, String what) {
        if (!Files.isRegularFile(p)) {
            return what + " 不存在";
        }
        try {
            System.load(p.toAbsolutePath().toString());
            return null;
        } catch (UnsatisfiedLinkError e) {
            return "加载 " + what + " 失败: " + e.getMessage();
        }
    }

    // ---- 原生方法（实现在 launcher/skyisland_ime.c）----

    /**
     * 给窗口装上消息子类，拦 {@code WM_IME_SETCONTEXT} 与 {@code WM_ACTIVATE}。
     *
     * @return 是否真的装上了（读回 wndproc 核对，不是信 API 返回值）
     */
    public static native boolean nInstallForWindow(long hwnd);

    /** 当前 wndproc 是否正是我们装的那个（用来证明子类真的在生效）。 */
    public static native boolean nIsInstalledForWindow(long hwnd);

    /** 我们的消息过程被调用过多少次。{@code 0} = 一次都没被调用过。 */
    public static native int nProcCallCount();

    /** 拦下过多少次 {@code WM_IME_SETCONTEXT}。{@code 0} = 方向选错了。 */
    public static native int nImeContextMsgCount();

    /** 拦下过多少次 {@code WM_INPUTLANGCHANGEREQUEST}（IME 要求切换输入语言）。 */
    public static native int nInputLangMsgCount();

    /** 摘掉窗口的 IME 上下文。返回**事后**是否已无 IME（不是 API 的返回值）。 */
    public static native boolean nDisableForWindow(long hwnd);

    /** 窗口当前是否挂着 IME 上下文。这是证据接口。 */
    public static native boolean nIsEnabledForWindow(long hwnd);

    /**
     * 对给定 GLFW 窗口装上 IME 子类（真正解决问题的那一步）。
     *
     * @return 是否装上；桥不可用或参数非法时返回 {@code false}
     */
    public static boolean installForWindow(long glfwHandle) {
        if (!isAvailable()) {
            return false;
        }
        long hwnd = hwndOf(glfwHandle);
        if (hwnd == 0L) {
            return false;
        }
        return nInstallForWindow(hwnd);
    }

    /** 子类是否已装。 */
    public static boolean isInstalledForWindow(long glfwHandle) {
        if (!isAvailable()) {
            return false;
        }
        long hwnd = hwndOf(glfwHandle);
        if (hwnd == 0L) {
            return false;
        }
        return nIsInstalledForWindow(hwnd);
    }

    private static long hwndOf(long glfwHandle) {
        return GLFWNativeWin32.glfwGetWin32Window(glfwHandle);
    }

    /**
     * 对给定 GLFW 窗口摘掉 IME。
     *
     * @return 事后是否已无 IME；桥不可用或参数非法时返回 {@code false}
     */
    public static boolean disableForWindow(long glfwHandle) {
        if (!isAvailable()) {
            return false;
        }
        long hwnd = hwndOf(glfwHandle);
        if (hwnd == 0L) {
            return false;
        }
        return nDisableForWindow(hwnd);
    }

    /** 窗口当前是否挂着 IME。桥不可用时返回 {@code false}（"查不到"不等于"有"）。 */
    public static boolean isEnabledForWindow(long glfwHandle) {
        if (!isAvailable()) {
            return false;
        }
        long hwnd = hwndOf(glfwHandle);
        if (hwnd == 0L) {
            return false;
        }
        return nIsEnabledForWindow(hwnd);
    }
}
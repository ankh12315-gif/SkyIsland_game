package com.skyisland.render;

import com.skyisland.game.Version;
import com.skyisland.input.InputState;
import com.skyisland.util.Log;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

/**
 * GLFW 窗口与 OpenGL 上下文（TECH_DESIGN_v0.1 §A / §C.5 / §Q.3）。
 *
 * <p>M0 口径（PRD 12.5 的阶段性口径差异）：
 * <ul>
 *   <li>1280×720、窗口模式、可缩放</li>
 *   <li><b>VSync 关闭</b>（PRD 12.5 的测试条件）</li>
 *   <li>OpenGL 3.3 Core Profile + Forward Compatible</li>
 *   <li>开发构建开启 Debug Context</li>
 * </ul>
 *
 * <p><b>高 DPI 注意：</b>本机面板为 2880×1800，Windows 缩放会使命中
 * <b>窗口尺寸 ≠ 帧缓冲尺寸</b>。性能统计必须以帧缓冲实际像素为准（PRD 12.5），
 * 因此本类同时暴露两者。
 */
public final class Window {

    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 720;

    /** OpenGL 目标版本（PRD 12.1）。 */
    public static final int GL_MAJOR = 3;
    public static final int GL_MINOR = 3;

    private final long handle;
    private final String title;
    private boolean vsyncEnabled;
    private final boolean debugContextRequested;

    private int windowWidth;
    private int windowHeight;
    private int framebufferWidth;
    private int framebufferHeight;

    private GLCapabilities capabilities;
    private boolean rawMouseMotionEnabled = false;

    /** 当前是否处于"隐藏并锁定光标"状态（M1.5：由界面状态驱动）。 */
    private boolean mouseCaptured = false;

    private Window(long handle, String title, int w, int h,
                   boolean vsyncEnabled, boolean debugContextRequested) {
        this.handle = handle;
        this.title = title;
        this.vsyncEnabled = vsyncEnabled;
        this.debugContextRequested = debugContextRequested;
        this.windowWidth = w;
        this.windowHeight = h;
    }

    /**
     * 创建窗口并建立 OpenGL 上下文。
     *
     * @param vsync       是否开启垂直同步（M0 测试口径为 false）
     * @param debugGL     是否请求 Debug Context（开发构建为 true）
     * @param hideCursor  是否隐藏并锁定光标（游戏态）；false 用于「保留可选鼠标」的排查场景
     */
    public static Window create(String title, int width, int height,
                                boolean vsync, boolean debugGL, boolean hideCursor) {
        // ---- GLFW 错误回调必须在 glfwInit 之前设置，否则初始化阶段的错误会被丢弃 ----
        GLFW.glfwSetErrorCallback((error, description) -> {
            String desc = GLFWErrorCallback.getDescription(description);
            Log.noteWarning("GLFW", "errorCode=" + error + " (" + glfwErrorName(error) + ") " + desc);
        });

        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("glfwInit() 失败 —— 无法初始化 GLFW");
        }

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);      // 先隐藏，避免创建过程中的白屏闪烁
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, GL_MAJOR);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, GL_MINOR);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_DEBUG_CONTEXT,
                debugGL ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, 0);
        GLFW.glfwWindowHint(GLFW.GLFW_DOUBLEBUFFER, GLFW.GLFW_TRUE);

        long handle = GLFW.glfwCreateWindow(width, height, title, MemoryUtil.NULL, MemoryUtil.NULL);
        if (handle == MemoryUtil.NULL) {
            throw new IllegalStateException(
                    "glfwCreateWindow() 返回 NULL —— 无法创建 OpenGL "
                            + GL_MAJOR + "." + GL_MINOR + " Core 上下文。"
                            + "请检查显卡驱动是否支持 OpenGL 3.3 Core。");
        }

        Window w = new Window(handle, title, width, height, vsync, debugGL);

        GLFW.glfwMakeContextCurrent(handle);

        // ★ VSync（PRD 12.5：测试条件为关闭）
        GLFW.glfwSwapInterval(vsync ? 1 : 0);

        // ★ 建立 OpenGL 能力表（必须在 makeContextCurrent 之后）
        w.capabilities = GL.createCapabilities();
        if (!w.capabilities.OpenGL33) {
            throw new IllegalStateException(
                    "当前上下文未提供 OpenGL 3.3 能力。实际 GL_VERSION = " + w.glVersion());
        }

        // ---- 光标模式 ----
        // M1.5 起"光标模式"由界面状态驱动（主菜单/设置/暂停可见，游玩中锁定），
        // 因此这里只建立**初始**状态，之后一律走 setMouseCaptured()。
        // 保持字段与 GLFW 状态一致，setMouseCaptured 的幂等判断才不会失效。
        if (hideCursor) {
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            if (GLFW.glfwRawMouseMotionSupported()) {
                GLFW.glfwSetInputMode(handle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);
                w.rawMouseMotionEnabled = true;
            } else {
                Log.warn("当前平台不支持原始鼠标运动（GLFW_RAW_MOUSE_MOTION），退回普通光标增量");
            }
        } else {
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
        w.mouseCaptured = hideCursor;

        // ---- 尺寸（区分窗口尺寸与帧缓冲尺寸 —— 高 DPI）----
        int[] wa = new int[1];
        int[] ha = new int[1];
        GLFW.glfwGetWindowSize(handle, wa, ha);
        w.windowWidth = wa[0];
        w.windowHeight = ha[0];
        int[] fw = new int[1];
        int[] fh = new int[1];
        GLFW.glfwGetFramebufferSize(handle, fw, fh);
        w.framebufferWidth = fw[0];
        w.framebufferHeight = fh[0];

        GLFW.glfwShowWindow(handle);
        GLFW.glfwFocusWindow(handle);

        return w;
    }

    // ============================================================ 回调绑定

    /** 绑定输入与窗口回调。必须在主循环开始前调用。 */
    public void installCallbacks(InputState input, Runnable onCloseRequested) {
        GLFW.glfwSetKeyCallback(handle, (win, key, scancode, action, mods) ->
                input.onKey(key, scancode, action, mods));

        GLFW.glfwSetMouseButtonCallback(handle, (win, button, action, mods) ->
                input.onMouseButton(button, action, mods));

        GLFW.glfwSetCursorPosCallback(handle, (win, x, y) ->
                input.onCursorPos(x, y));

        GLFW.glfwSetScrollCallback(handle, (win, dx, dy) ->
                input.onScroll(dx, dy));

        GLFW.glfwSetFramebufferSizeCallback(handle, (win, width, height) -> {
            this.framebufferWidth = width;
            this.framebufferHeight = height;
            input.onFramebufferSize(width, height);
        });

        GLFW.glfwSetWindowSizeCallback(handle, (win, width, height) -> {
            this.windowWidth = width;
            this.windowHeight = height;
        });

        GLFW.glfwSetWindowFocusCallback(handle, (win, focused) ->
                input.onWindowFocus(focused));

        GLFW.glfwSetWindowRefreshCallback(handle, win ->
                input.onWindowRefresh());

        if (onCloseRequested != null) {
            GLFW.glfwSetWindowCloseCallback(handle, win -> onCloseRequested.run());
        }

        // 播种基线：窗口创建时的初始尺寸与初始焦点不会产生回调，
        // 不播种就会把第一次真实 resize 当成基准吞掉（M0 实测发现，见 InputState#seedWindowState）。
        boolean focused = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
        input.seedWindowState(framebufferWidth, framebufferHeight, focused);
    }

    // ============================================================ 前台焦点

    /** 窗口当前是否为操作系统层面的前台（键盘）焦点窗口。 */
    public boolean isFocused() {
        return GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
    }

    public boolean isIconified() {
        return GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE;
    }

    /**
     * 尝试使本窗口成为前台窗口，并等待焦点生效。
     *
     * <p><b>为什么需要它（M0 实测发现）：</b>Windows 对键盘消息与鼠标消息的投递规则不同：
     * <ul>
     *   <li>键盘消息只投递给<u>前台</u>窗口</li>
     *   <li>鼠标消息按<u>光标位置</u>投递给光标下的窗口</li>
     * </ul>
     * 因此"鼠标有事件、键盘却一个都没有"是前台焦点缺失的典型症状，
     * 而不是回调注册错误。窗口创建时虽然调用过 {@code glfwFocusWindow}，
     * 但随后被其它进程抢走前台并不会产生任何提示。
     *
     * <p>优先使用 GLFW 的 {@code GLFW_FOCUS_ON_SHOW}（这是 GLFW 为"显示即前台"
     * 提供的正式机制），再退回 {@code glfwFocusWindow}。
     * 注意 Windows 的 {@code SetForegroundWindow} 有前台锁限制，可能被系统拒绝 ——
     * 因此本方法返回"是否真的拿到了焦点"，调用方必须检查返回值而不是假定成功。
     *
     * @param timeoutMs 等待焦点的上限
     * @return 是否已成为前台窗口
     */
    public boolean claimForeground(int timeoutMs) {
        if (isIconified()) {
            GLFW.glfwRestoreWindow(handle);
        }
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_TRUE);
        GLFW.glfwShowWindow(handle);
        GLFW.glfwFocusWindow(handle);

        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            GLFW.glfwPollEvents();      // 焦点变化要经过事件队列才能反映到 window attrib
            if (isFocused()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return isFocused();
    }

    // ============================================================ 帧操作

    public void pollEvents() {
        GLFW.glfwPollEvents();
    }

    public void swapBuffers() {
        GLFW.glfwSwapBuffers(handle);
    }

    public boolean shouldClose() {
        return GLFW.glfwWindowShouldClose(handle);
    }

    public void requestClose() {
        GLFW.glfwSetWindowShouldClose(handle, true);
    }

    public void setTitle(String newTitle) {
        GLFW.glfwSetWindowTitle(handle, newTitle);
    }

    public void setCursorHidden(boolean hidden) {
        setMouseCaptured(hidden);
    }

    // ============================================================ 运行期可变的显示/输入模式（M1.5）

    /**
     * 切换"光标是否隐藏并锁定"。
     *
     * <p><b>为什么 M1.5 需要运行期切换：</b>M1 全程锁定光标（第一人称），
     * 但主菜单 / 设置 / 暂停菜单需要<u>可点击的光标</u>。
     * 于是"光标模式"从"启动参数"变成了"界面状态的函数"。
     *
     * <p><b>原始鼠标运动（{@code GLFW_RAW_MOUSE_MOTION}）随之开关：</b>
     * 它只在锁定状态下有意义（绕开系统加速、提高瞄准线性度）。
     * 菜单期间若继续开着，部分平台上会看到光标抖动。
     *
     * <p><b>幂等：</b>重复设置成同一状态不产生 GLFW 调用 ——
     * 本方法每帧都可能被调用（由界面状态驱动），不做幂等就会每帧打扰 GLFW。
     *
     * @return 是否发生了实际变化（调用方据此决定要不要重置输入累积量）
     */
    public boolean setMouseCaptured(boolean captured) {
        if (captured == mouseCaptured) {
            return false;
        }
        mouseCaptured = captured;
        GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR,
                captured ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
        if (captured) {
            if (GLFW.glfwRawMouseMotionSupported()) {
                GLFW.glfwSetInputMode(handle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);
                rawMouseMotionEnabled = true;
            } else {
                Log.warn("当前平台不支持原始鼠标运动（GLFW_RAW_MOUSE_MOTION），退回普通光标增量");
            }
        } else if (rawMouseMotionEnabled) {
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_FALSE);
            rawMouseMotionEnabled = false;
        }
        Log.info("[窗口] 光标模式 → %s（原始鼠标运动=%s）",
                captured ? "隐藏并锁定（游玩）" : "可见可用（界面）",
                rawMouseMotionEnabled ? "开" : "关");
        return true;
    }

    public boolean isMouseCaptured() {
        return mouseCaptured;
    }

    /**
     * 运行期切换 VSync（M1.5 设置项）。
     *
     * <p>GLFW 的交换间隔可以随时改，不需要重建窗口 —— 因此设置界面里切换 VSync
     * 是立即生效的。注意性能测量口径：{@code PRD 12.5} 要求测试条件下 VSync 关闭，
     * 因此自动化性能运行必须确保该设置为 false。
     */
    public void setVsync(boolean enabled) {
        if (enabled == vsyncEnabled) {
            return;
        }
        GLFW.glfwSwapInterval(enabled ? 1 : 0);
        vsyncEnabled = enabled;
        Log.info("[窗口] VSync → %s", enabled ? "ON" : "OFF");
    }

    /** 当前光标位置（像素）。无窗口或读取失败时返回 {@code {NaN, NaN}}。 */
    public double[] cursorPosition() {
        if (handle == MemoryUtil.NULL) {
            return new double[]{Double.NaN, Double.NaN};
        }
        try {
            double[] x = new double[1];
            double[] y = new double[1];
            GLFW.glfwGetCursorPos(handle, x, y);
            return new double[]{x[0], y[0]};
        } catch (RuntimeException e) {
            return new double[]{Double.NaN, Double.NaN};
        }
    }

    /** 销毁窗口。可重复调用。 */
    public void destroy() {
        if (handle != MemoryUtil.NULL) {
            GLFW.glfwDestroyWindow(handle);
        }
        if (capabilities != null) {
            // 释放 GL 能力表引用，避免在 glfwTerminate 之后再访问
            GL.setCapabilities(null);
            capabilities = null;
        }
    }

    // ============================================================ OpenGL / GLFW 信息

    public String glVersion() {
        return safeGlString(GL11.GL_VERSION);
    }

    public String glVendor() {
        return safeGlString(GL11.GL_VENDOR);
    }

    public String glRenderer() {
        return safeGlString(GL11.GL_RENDERER);
    }

    public String glslVersion() {
        return safeGlString(GL20_GL_SHADING_LANGUAGE_VERSION);
    }

    /** GL_SHADING_LANGUAGE_VERSION 常量（避免为它单独 import 整个 GL20）。 */
    private static final int GL20_GL_SHADING_LANGUAGE_VERSION = 0x8B8C;

    private static String safeGlString(int name) {
        try {
            String s = GL11.glGetString(name);
            return s == null ? "(null)" : s;
        } catch (RuntimeException e) {
            return "(读取失败: " + e.getMessage() + ")";
        }
    }

    public static String glfwVersionString() {
        String s = GLFW.glfwGetVersionString();
        return s == null ? "(null)" : s;
    }

    public static int[] glfwVersion() {
        int[] major = new int[1];
        int[] minor = new int[1];
        int[] rev = new int[1];
        GLFW.glfwGetVersion(major, minor, rev);
        return new int[]{major[0], minor[0], rev[0]};
    }

    public static String platformName() {
        int platform = GLFW.glfwGetPlatform();
        return switch (platform) {
            case GLFW.GLFW_PLATFORM_WIN32 -> "Win32";
            case GLFW.GLFW_PLATFORM_COCOA -> "Cocoa";
            case GLFW.GLFW_PLATFORM_WAYLAND -> "Wayland";
            case GLFW.GLFW_PLATFORM_X11 -> "X11";
            case GLFW.GLFW_PLATFORM_NULL -> "Null";
            default -> "Unknown(" + platform + ")";
        };
    }

    /** 主显示器的当前视频模式（用于记录面板分辨率与刷新率）。 */
    public static String primaryMonitorMode() {
        long monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor == MemoryUtil.NULL) {
            return "(无主显示器)";
        }
        GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
        if (mode == null) {
            return "(无法读取视频模式)";
        }
        return mode.width() + "×" + mode.height() + " @ " + mode.refreshRate() + " Hz"
                + "  RGB=" + mode.redBits() + "/" + mode.greenBits() + "/" + mode.blueBits();
    }

    /** 环境检查与启动横幅（写入日志文件，供 M0 报告摘录）。 */
    public void logEnvironment() {
        int[] v = glfwVersion();
        Log.info("--- 图形环境 ---");
        Log.info("LWJGL 版本          : %s", org.lwjgl.Version.getVersion());
        Log.info("LWJGL build 类型    : %s", lwjglBuildType());
        Log.info("GLFW 版本字符串     : %s", glfwVersionString());
        Log.info("GLFW 版本号         : %d.%d.%d", v[0], v[1], v[2]);
        Log.info("GLFW 平台           : %s", platformName());
        Log.info("主显示器视频模式    : %s", primaryMonitorMode());
        Log.info("OpenGL 版本         : %s", glVersion());
        Log.info("OpenGL 厂商         : %s", glVendor());
        Log.info("OpenGL 渲染器       : %s", glRenderer());
        Log.info("GLSL 版本           : %s", glslVersion());
        Log.info("请求的上下文        : OpenGL %d.%d Core, Forward-Compatible, Debug=%s",
                GL_MAJOR, GL_MINOR, debugContextRequested);
        Log.info("已获得的能力表 OpenGL33 : %s", capabilities != null && capabilities.OpenGL33);
        Log.info("窗口尺寸 / 帧缓冲尺寸 : %d×%d / %d×%d%s",
                windowWidth, windowHeight, framebufferWidth, framebufferHeight,
                (windowWidth != framebufferWidth || windowHeight != framebufferHeight)
                        ? "  ← 高 DPI：两者不同，性能统计以帧缓冲为准" : "");
        Log.info("VSync               : %s", vsyncEnabled ? "ON" : "OFF");
        Log.info("原始鼠标运动        : %s", rawMouseMotionEnabled ? "已启用" : "未启用");

        // ★ 原生窗口句柄：M0 的确定性键鼠验证需要它（由外部进程向该 HWND 投递窗口消息，
        //   从而绕开"键盘只投递给前台窗口"的系统限制）。获取它只用 lwjgl-glfw，不需要额外依赖。
        long hwnd = win32Handle();
        Log.info("原生窗口句柄        : %s", hwnd == 0L ? "(非 Win32 平台)" : "HWND=0x" + Long.toHexString(hwnd));
    }

    /**
     * Win32 原生窗口句柄（HWND）。非 Win32 平台返回 0。
     *
     * <p>仅供 M0 的输入验证使用 —— 它让外部进程可以绕过前台焦点限制，
     * 直接向本窗口投递窗口消息，从而取得<u>确定性</u>的键鼠事件证据。
     */
    public long win32Handle() {
        try {
            return org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(handle);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** LWJGL 是否为调试构建（debug 版会带来巨大性能差异，必须在报告中记录）。 */
    public static String lwjglBuildType() {
        boolean debug = org.lwjgl.Version.getVersion().contains("debug")
                || Boolean.getBoolean("org.lwjgl.util.Debug");
        return debug ? "DEBUG（含校验与额外检查，性能不代表发布态）" : "RELEASE";
    }

    // ============================================================ 访问器

    public long handle() {
        return handle;
    }

    public String title() {
        return title;
    }

    public int windowWidth() {
        return windowWidth;
    }

    public int windowHeight() {
        return windowHeight;
    }

    public int framebufferWidth() {
        return framebufferWidth;
    }

    public int framebufferHeight() {
        return framebufferHeight;
    }

    public boolean isVsyncEnabled() {
        return vsyncEnabled;
    }

    public boolean isDebugContextRequested() {
        return debugContextRequested;
    }

    public float aspectRatio() {
        return framebufferHeight == 0 ? 1f : (float) framebufferWidth / framebufferHeight;
    }

    public GLCapabilities capabilities() {
        return capabilities;
    }

    private static String glfwErrorName(int code) {
        return switch (code) {
            case GLFW.GLFW_NOT_INITIALIZED -> "NOT_INITIALIZED";
            case GLFW.GLFW_NO_CURRENT_CONTEXT -> "NO_CURRENT_CONTEXT";
            case GLFW.GLFW_INVALID_ENUM -> "INVALID_ENUM";
            case GLFW.GLFW_INVALID_VALUE -> "INVALID_VALUE";
            case GLFW.GLFW_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
            case GLFW.GLFW_API_UNAVAILABLE -> "API_UNAVAILABLE";
            case GLFW.GLFW_VERSION_UNAVAILABLE -> "VERSION_UNAVAILABLE";
            case GLFW.GLFW_PLATFORM_ERROR -> "PLATFORM_ERROR";
            case GLFW.GLFW_FORMAT_UNAVAILABLE -> "FORMAT_UNAVAILABLE";
            case GLFW.GLFW_NO_WINDOW_CONTEXT -> "NO_WINDOW_CONTEXT";
            default -> "UNKNOWN";
        };
    }

    /** 供 M0 报告使用：版本标签。 */
    public String describeBuild() {
        return Version.display();
    }
}

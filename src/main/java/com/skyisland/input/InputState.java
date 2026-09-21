package com.skyisland.input;

import com.skyisland.util.Log;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;

/**
 * 原始输入状态（M0 范围）。
 *
 * <p><b>M0 只做「Keyboard Input / Mouse Input」</b>：本类只记录并统计原始输入，
 * <b>不做语义映射</b>（{@code PlayerIntent} 属 M1，见 TECH_DESIGN §S 启用矩阵）。
 * 这样切分是为了让 M0 能明确回答「输入是否被正确读取」，
 * 而不掺入「输入是否被正确解释」这一 M1 的问题。
 *
 * <p>本类被 GLFW 回调写入、被主循环读取。GLFW 回调发生在 {@code glfwPollEvents()}
 * 内部的渲染线程上，因此访问天然同线程，无需加锁。
 */
public final class InputState {

    /** GLFW 的键值上限为 348（GLFW_KEY_LAST），取 512 留余量。 */
    private static final int KEY_TABLE_SIZE = 512;
    private static final int MOUSE_BUTTON_TABLE_SIZE = 16;

    private final boolean[] keyDown = new boolean[KEY_TABLE_SIZE];
    private final boolean[] mouseDown = new boolean[MOUSE_BUTTON_TABLE_SIZE];

    /**
     * 按键"按下沿"标志（M1 新增，用于修复 M0 的 I-14 缺陷）。
     *
     * <p>为什么必须在这里记录，而不能在每帧开头靠"和上一帧比较"推出来：
     * 若用户极快地点一下（或输入是被合成的），"按下"与"抬起"会落在<u>同一个
     * {@code glfwPollEvents()} 批次</u>里。帧末再去比较状态，看到的永远是"没按下"，
     * 这次按键就被彻底丢失 —— M0 的 ESC 退出正是这样失效的（实测 PRESS 与 RELEASE
     * 时间戳完全相同，程序不退出）。
     *
     * <p>边沿标志在回调里置位，由 {@code InputMapper} 消费，帧末统一清除，
     * 因此"一帧内按下过"这一事实不会因为同帧内的抬起而消失。
     */
    private final boolean[] keyPressedEdge = new boolean[KEY_TABLE_SIZE];
    private final boolean[] mousePressedEdge = new boolean[MOUSE_BUTTON_TABLE_SIZE];

    // ---- 位置与增量 ----
    private double cursorX = Double.NaN;
    private double cursorY = Double.NaN;
    private double frameDeltaX = 0;
    private double frameDeltaY = 0;
    private double accumulatedDeltaX = 0;
    private double accumulatedDeltaY = 0;

    // ---- 滚动 ----
    private double scrollAccumX = 0;
    private double scrollAccumY = 0;

    // ---- 统计（供 M0 报告引用）----
    private long keyEventCount = 0;
    private long unmappableKeyEventCount = 0;
    private long mouseButtonEventCount = 0;
    private long scrollEventCount = 0;
    private long mouseMoveSamples = 0;
    private final boolean[] keyEverSeen = new boolean[KEY_TABLE_SIZE];
    private int distinctKeysSeen = 0;
    private int distinctMouseButtonsSeen = 0;
    private final boolean[] mouseButtonEverSeen = new boolean[MOUSE_BUTTON_TABLE_SIZE];

    // ---- 窗口事件统计 ----
    private long resizeCount = 0;
    private long focusGainCount = 0;
    private long focusLossCount = 0;
    private long refreshCount = 0;
    private int lastFramebufferWidth = -1;
    private int lastFramebufferHeight = -1;

    public InputState() {
    }

    // ============================================================ GLFW 回调入口

    public void onKey(int key, int scancode, int action, int mods) {
        boolean down;
        String actionName;
        switch (action) {
            case GLFW.GLFW_PRESS -> {
                down = true;
                actionName = "PRESS  ";
            }
            case GLFW.GLFW_RELEASE -> {
                down = false;
                actionName = "RELEASE";
            }
            case GLFW.GLFW_REPEAT -> {
                down = true;
                actionName = "REPEAT ";
            }
            default -> {
                return;
            }
        }

        // ★ 不可映射的键必须被<u>记录</u>，不能静默丢弃。
        // 否则"事件根本没到达窗口"与"事件到达了但 GLFW 无法映射该 scancode"
        // 这两种完全不同的根因会产生一模一样的观测结果（都是日志里什么都没有），
        // 使得故障无法定位。M0 的教训是：测量环节不允许静默丢数据。
        boolean mappable = key >= 0 && key < KEY_TABLE_SIZE;
        if (mappable) {
            keyDown[key] = down;
            if (action == GLFW.GLFW_PRESS) {
                keyPressedEdge[key] = true;
            }
            if (!keyEverSeen[key]) {
                keyEverSeen[key] = true;
                distinctKeysSeen++;
            }
        } else {
            unmappableKeyEventCount++;
        }
        keyEventCount++;

        Log.info("键盘事件: %s  key=%s (%d)  scancode=%d  mods=0x%X%s",
                actionName, mappable ? keyName(key) : "UNMAPPED", key, scancode, mods,
                mappable ? "" : "   [事件已到达窗口，但 GLFW 无法把该 scancode 映射为键码]");
    }

    /** GLFW 无法映射为键码、但确实到达了窗口的键盘事件数（诊断用）。 */
    public long unmappableKeyEventCount() {
        return unmappableKeyEventCount;
    }

    public void onMouseButton(int button, int action, int mods) {
        if (button < 0 || button >= MOUSE_BUTTON_TABLE_SIZE) {
            return;
        }
        boolean down;
        String actionName;
        if (action == GLFW.GLFW_PRESS) {
            down = true;
            actionName = "PRESS  ";
        } else if (action == GLFW.GLFW_RELEASE) {
            down = false;
            actionName = "RELEASE";
        } else {
            return;
        }
        mouseDown[button] = down;
        if (action == GLFW.GLFW_PRESS) {
            mousePressedEdge[button] = true;
        }
        mouseButtonEventCount++;
        if (!mouseButtonEverSeen[button]) {
            mouseButtonEverSeen[button] = true;
            distinctMouseButtonsSeen++;
        }
        Log.info("鼠标按键: %s  button=%s (%d)  mods=0x%X",
                actionName, mouseButtonName(button), button, mods);
    }

    public void onCursorPos(double x, double y) {
        if (Double.isNaN(cursorX)) {
            // 第一次回调只用于建立基准，不产生位移
            cursorX = x;
            cursorY = y;
            return;
        }
        double dx = x - cursorX;
        double dy = y - cursorY;
        cursorX = x;
        cursorY = y;
        frameDeltaX += dx;
        frameDeltaY += dy;
        accumulatedDeltaX += dx;
        accumulatedDeltaY += dy;
        mouseMoveSamples++;
    }

    public void onScroll(double dx, double dy) {
        scrollAccumX += dx;
        scrollAccumY += dy;
        scrollEventCount++;
        Log.info("滚轮事件: offset=(%.2f, %.2f)", dx, dy);
    }

    public void onFramebufferSize(int width, int height) {
        if (lastFramebufferWidth >= 0
                && (width != lastFramebufferWidth || height != lastFramebufferHeight)) {
            resizeCount++;
            Log.info("窗口帧缓冲尺寸变化: %d×%d → %d×%d",
                    lastFramebufferWidth, lastFramebufferHeight, width, height);
        }
        lastFramebufferWidth = width;
        lastFramebufferHeight = height;
    }

    public void onWindowFocus(boolean focused) {
        if (focused) {
            focusGainCount++;
            Log.info("窗口获得焦点");
        } else {
            focusLossCount++;
            Log.info("窗口失去焦点（按住中的键与鼠标键将全部释放）");
            // ★ 失去焦点必须清空"按住"状态 —— 理由见 releaseAllInputs 的说明。
            releaseAllInputs();
        }
    }

    /**
     * 释放所有"按住"状态并清除本帧按下沿。
     *
     * <p><b>为什么必须有它（这是"输入源消失"类故障的标准处置）：</b>
     * GLFW 只把键盘/鼠标事件投递给<b>拥有焦点</b>的窗口。玩家按住移动键时切出去
     * （Alt-Tab、点击别的窗口、弹窗抢焦点），<b>松开那个键的 RELEASE 事件会送到
     * 另一个窗口</b>，本窗口永远等不到它。于是 {@code keyDown[key]} 长期为真 ——
     * 表现为"按一下方向键之后，玩家一直平移下去"，而且回来后按什么键都停不下来
     * （玩家只能再按一次那个键、再松开，才能把状态纠正回来）。
     *
     * <p>这条与 §C.4′ 第 1 条同源：<b>不允许把"没收到事件"当成"事情没发生"</b>。
     * 焦点离开是一个明确的信号，据此归零比等待一个可能永不到来的 RELEASE 可靠。
     *
     * <p>按下沿也一并清除：否则切回来时会把"切出去之前按过的键"重新消费一次。
     */
    public void releaseAllInputs() {
        Arrays.fill(keyDown, false);
        Arrays.fill(mouseDown, false);
        clearPressedEdges();
    }

    public void onWindowRefresh() {
        refreshCount++;
    }

    /**
     * 播种窗口基线状态。必须在 {@code installCallbacks} 之后、主循环之前调用一次。
     *
     * <p><b>为什么必须播种（M0 实测发现）：</b>GLFW 只在尺寸<u>发生变化</u>时触发
     * framebuffer-size 回调，窗口创建时的初始尺寸不产生回调。若不先把初始值写入基线，
     * {@link #onFramebufferSize} 会把第一次真实 resize 当成"建立基准"而吞掉，
     * 使 resize 计数少记一次 —— M0 首轮实测正是把 2 次 resize 记成了 1 次。
     *
     * <p>焦点同理：窗口在回调注册之前就已获得焦点，该事件无法由回调观察到，
     * 因此在这里补记一次，使「获得焦点次数」反映真实发生过的事实。
     */
    public void seedWindowState(int fbWidth, int fbHeight, boolean focused) {
        lastFramebufferWidth = fbWidth;
        lastFramebufferHeight = fbHeight;
        if (focused) {
            focusGainCount++;
        }
        Log.info("窗口初始状态: 已获得焦点=%s  帧缓冲=%d×%d", focused, fbWidth, fbHeight);
    }

    // ============================================================ 查询

    public boolean isKeyDown(int key) {
        return key >= 0 && key < KEY_TABLE_SIZE && keyDown[key];
    }

    public boolean isMouseDown(int button) {
        return button >= 0 && button < MOUSE_BUTTON_TABLE_SIZE && mouseDown[button];
    }

    /** 取走本帧累积的鼠标位移，并清零。 */
    public double[] consumeFrameDelta() {
        double[] d = {frameDeltaX, frameDeltaY};
        frameDeltaX = 0;
        frameDeltaY = 0;
        return d;
    }

    // ============================================================ 光标位置（M1.5）

    /**
     * 当前光标位置（像素，左上角为原点）。
     *
     * <p>菜单需要一个<u>绝对</u>位置来做悬停命中判定。锁定光标模式下这个值会一直
     * 在窗口中心附近抖动（因为 GLFW 每次把光标拉回中心），因此它<u>只</u>在
     * 菜单（光标可见）状态下有意义 —— 调用方负责区分，见 {@code MenuNav}。
     *
     * @return 长度 2 的数组；尚未收到任何位置回调时两个分量均为 {@code NaN}
     */
    public double[] cursorPosition() {
        return new double[]{cursorX, cursorY};
    }

    /**
     * 把光标基线与当前位置对齐，<b>不产生位移</b>。
     *
     * <p><b>为什么必须有它（这是光标模式切换的必备步骤）：</b>
     * 从"光标锁定"切到"光标可见"时，光标会出现在上次被拉回中心前的位置；
     * 再从"可见"切回"锁定"时，GLFW 会把它重新拉回中心 —— 这两次位置跳变
     * 都会被 {@link #onCursorPos} 记成<u>真实位移</u>，表现为
     * "一按 ESC 回到游戏，视角猛地转过去一大截"。
     * 每次切换光标模式后调用本方法，就是把这条虚假位移的基准重新钉住。
     */
    public void rebaseCursor() {
        cursorX = Double.NaN;
        cursorY = Double.NaN;
    }

    /** 用已知的绝对位置播种光标基线（进入菜单时由 {@code glfwGetCursorPos} 得到）。 */
    public void seedCursor(double x, double y) {
        cursorX = x;
        cursorY = y;
    }

    /**
     * 注入一段<b>相对</b>鼠标位移（测试仪器，M1.5 的 UI 自测用）。
     *
     * <p><b>为什么必须有它：</b>{@link #onCursorPos} 的语义是"光标现在在 (x,y)"，
     * 因此它需要知道当前基准才能算出位移。而"玩家把鼠标向右挪了 100 像素"
     * 这件事在自测里应当被直接表达，而不是先猜一个绝对坐标。
     * 走这里注入的位移与真实鼠标位移在后续链路里<u>完全同路</u>
     * （{@code consumeFrameDelta} → {@code InputMapper} → 灵敏度换算 → 相机角度），
     * 被绕开的只有 {@code OS → GLFW} 这一段 —— 与 M1 的 TR7 应对方案同一条原则。
     */
    public void injectCursorDelta(double dx, double dy) {
        frameDeltaX += dx;
        frameDeltaY += dy;
        accumulatedDeltaX += dx;
        accumulatedDeltaY += dy;
        mouseMoveSamples++;
    }

    // ============================================================ 边沿查询（M1）

    /**
     * 本帧是否发生过该键的"按下"。读取<u>不清除</u>标志 ——
     * 多个消费方（退出检测、切槽、调试开关）都要读同一批边沿。
     */
    public boolean wasKeyPressed(int key) {
        return key >= 0 && key < KEY_TABLE_SIZE && keyPressedEdge[key];
    }

    /** 本帧是否发生过该鼠标键的"按下"。 */
    public boolean wasMouseButtonPressed(int button) {
        return button >= 0 && button < MOUSE_BUTTON_TABLE_SIZE && mousePressedEdge[button];
    }

    // ============================================================ "本帧按了什么"（M1.5 重绑界面用）

    /**
     * 本帧按下过的第一个键码（按码升序），没有则返回 {@code -1}。
     *
     * <p><b>为什么重绑界面需要它：</b>"等待输入"阶段要接受的是<u>任意</u>键，
     * 而不是某个预先知道的键 —— 否则就变成"只能绑到我已知的键上"。
     * 遍历的是按下沿数组（本帧置位、帧末清除），因此不会把"上一帧按住的键"
     * 误当成"这一帧刚按下"。
     */
    public int firstPressedKeyThisFrame() {
        for (int i = 0; i < keyPressedEdge.length; i++) {
            if (keyPressedEdge[i]) {
                return i;
            }
        }
        return -1;
    }

    /** 本帧按下过的第一个鼠标键，没有则返回 {@code -1}。 */
    public int firstPressedMouseButtonThisFrame() {
        for (int i = 0; i < mousePressedEdge.length; i++) {
            if (mousePressedEdge[i]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 帧末统一清除边沿标志。
     *
     * <p>必须在<u>所有</u>消费方读完之后的同一帧末尾调用。
     * 若在 {@code glfwPollEvents()} 之后立刻清除，逻辑步就会看不到本帧的按下；
     * 若忘记清除，一次点击会被后续每一帧重复消费。
     */
    public void clearPressedEdges() {
        java.util.Arrays.fill(keyPressedEdge, false);
        java.util.Arrays.fill(mousePressedEdge, false);
    }

    /** 取走并清零本帧累积的滚轮纵向增量（正 = 向上/向前）。 */
    public double consumeScrollY() {
        double v = scrollAccumY;
        scrollAccumY = 0;
        return v;
    }

    /** 丢弃本帧剩余输入（用于把一帧的边沿与位移标记为"已被处理"）。 */
    public void discardFrameAccumulators() {
        frameDeltaX = 0;
        frameDeltaY = 0;
        scrollAccumX = 0;
        scrollAccumY = 0;
    }

    /** 当前是否按住任意移动键（仅用于 M0 的视觉反馈，不是游戏逻辑）。 */
    public boolean anyMoveKeyDown() {
        return isKeyDown(GLFW.GLFW_KEY_W) || isKeyDown(GLFW.GLFW_KEY_A)
                || isKeyDown(GLFW.GLFW_KEY_S) || isKeyDown(GLFW.GLFW_KEY_D);
    }

    public int currentlyDownKeyCount() {
        int n = 0;
        for (boolean b : keyDown) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    // ============================================================ 统计快照

    public record Stats(
            long keyEvents, int distinctKeys,
            long mouseButtonEvents, int distinctMouseButtons,
            long mouseMoveSamples, double totalMouseDeltaX, double totalMouseDeltaY,
            long scrollEvents, double scrollY,
            long resizeCount, long focusGainCount, long focusLossCount, long refreshCount
    ) {
        public String oneLine() {
            return String.format(
                    "键事件=%d 不同键=%d | 鼠标按键事件=%d 不同按键=%d | 移动样本=%d 累计位移=(%.0f, %.0f) "
                            + "| 滚轮=%d (Σ%.1f) | 尺寸变化=%d 获得焦点=%d 失去焦点=%d 重绘=%d",
                    keyEvents, distinctKeys, mouseButtonEvents, distinctMouseButtons,
                    mouseMoveSamples, totalMouseDeltaX, totalMouseDeltaY,
                    scrollEvents, scrollY, resizeCount, focusGainCount, focusLossCount, refreshCount);
        }

        public boolean keyboardObserved() {
            return keyEvents > 0 && distinctKeys > 0;
        }

        public boolean mouseMoveObserved() {
            return mouseMoveSamples > 0;
        }

        public boolean mouseButtonObserved() {
            return mouseButtonEvents > 0;
        }
    }

    public Stats stats() {
        return new Stats(
                keyEventCount, distinctKeysSeen,
                mouseButtonEventCount, distinctMouseButtonsSeen,
                mouseMoveSamples, accumulatedDeltaX, accumulatedDeltaY,
                scrollEventCount, scrollAccumY,
                resizeCount, focusGainCount, focusLossCount, refreshCount);
    }

    public void resetStats() {
        keyEventCount = 0;
        mouseButtonEventCount = 0;
        scrollEventCount = 0;
        mouseMoveSamples = 0;
        accumulatedDeltaX = 0;
        accumulatedDeltaY = 0;
        scrollAccumX = 0;
        scrollAccumY = 0;
        resizeCount = 0;
        focusGainCount = 0;
        focusLossCount = 0;
        refreshCount = 0;
        Arrays.fill(keyEverSeen, false);
        Arrays.fill(mouseButtonEverSeen, false);
        distinctKeysSeen = 0;
        distinctMouseButtonsSeen = 0;
    }

    // ============================================================ 名称（可读日志）

    /**
     * GLFW 键值 → 可读名。
     *
     * <p>M1.5 起委托给 {@code com.skyisland.settings.InputNames}：这张表同时被
     * 设置界面与重绑冲突提示使用，三处各写一份迟早出现"日志写 F5、界面写 KEY_294"。
     */
    public static String keyName(int key) {
        return com.skyisland.settings.InputNames.keyName(key);
    }

    public static String mouseButtonName(int button) {
        return com.skyisland.settings.InputNames.mouseButtonName(button);
    }
}

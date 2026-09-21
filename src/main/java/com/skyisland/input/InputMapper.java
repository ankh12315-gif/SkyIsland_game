package com.skyisland.input;

import com.skyisland.player.Camera;
import com.skyisland.player.PlayerIntent;
import com.skyisland.settings.Action;
import com.skyisland.settings.InputBinding;
import com.skyisland.settings.KeyBindings;
import com.skyisland.settings.LookConfig;
import org.lwjgl.glfw.GLFW;

/**
 * 原始输入 → {@link PlayerIntent} 的映射（M1 指令 B7/B12；M1.5 改为逻辑动作驱动）。
 *
 * <p><b>每帧只调用一次</b>（在 {@code beginFrame} 内），因为：
 * <ul>
 *   <li>鼠标位移是"帧累积量"，被 {@code consumeFrameDelta()} 取走即清零 ——
 *       若每个逻辑步都调用，后面的逻辑步就再也看不到位移；</li>
 *   <li>按下沿标志（{@code wasKeyPressed}）在所有逻辑步之间共享，
 *       同一帧内多次逻辑步必须消费同一个意图快照。</li>
 * </ul>
 *
 * <p><b>按一下触发一次"的键一律走按下沿</b>，这是 M0 缺陷 I-14 的修复
 * （见 {@code TECH_DESIGN_v0.1.1} §A′.3）：ESC/F3/F5/F2/F9 与数字键 1–9
 * 若用电平判定，"极快按点"（按下与抬起落在同一个 {@code glfwPollEvents} 批次内）
 * 会被完全漏掉。持续动作（移动、跳跃、左键挖掘、右键瞄准）才用电平。
 *
 * <h2>M1.5 的改动：从"硬编码键码"到"读动作表"</h2>
 * M1 里本类直接写 {@code in.isKeyDown(GLFW.GLFW_KEY_W)}，因此"哪个键是前进"是
 * <u>编译期</u>决定的。M1.5 要求键位可重绑，于是改成先查
 * {@link KeyBindings}（动作 → 物理输入），再由物理输入查 {@link InputState}。
 * 三段式的收益：重绑只改中间那张表；自测脚本可以只替换表就完成"改了键位之后
 * 前进是否还工作"的验证；而本类不再认识任何具体键码的含义。
 *
 * <p><b>仍然写死、不可重绑的键（以及原因）：</b>
 * <ul>
 *   <li>{@code F2 / F3 / F5 / F9} —— 调试与截图，不属于玩家里程碑的输入契约
 *       （PRD 把它们定义为开发期快捷键）；</li>
 *   <li>{@code 1..9} —— 快捷栏直选。快捷栏内容与"第几格"是绑定的，
 *       重绑让 1 号键变成 3 号槽只会制造困惑；</li>
 *   <li>菜单导航（方向键 / 回车 / 鼠标左键）—— 见 {@link MenuNav} 的说明。</li>
 * </ul>
 *
 * <p><b>灵敏度的位置：</b>本类<u>不</u>做"像素 → 角度"的换算，只负责把反转偏好
 * 施加到纵向位移上（{@link LookConfig#toLookDelta}）。换算发生在相机
 * （{@code Player#lookDegPerPixel} → {@code Camera#addLook}），
 * 因此灵敏度可以在运行期随时改变且立即生效，不需要经过输入层。
 */
public final class InputMapper {

    /**
     * 鼠标基准：度 / 像素（灵敏度倍数 = 1.0 时的换算率）。
     *
     * <p>这里<b>不另外定义数值</b>，而是指向 {@link Camera#SENSITIVITY_DEG_PER_PIXEL} ——
     * 灵敏度属于相机的角度语义，输入层只负责交出"鼠标移动了多少像素"。
     * 两处各写一个 0.12 迟早会漂移成两个不同的手感。
     *
     * <p>M1.5 之后它只是<b>基准</b>：实际生效值 = 本值 × 用户灵敏度倍数，
     * 由 {@link LookConfig#effectiveDegPerPixel} 计算。
     */
    public static final double BASE_DEG_PER_PIXEL = Camera.SENSITIVITY_DEG_PER_PIXEL;

    private double scrollCarry = 0;

    // ============================================================ 意图

    /**
     * 生成本帧意图。
     *
     * @param in            原始输入状态（读取会消费鼠标位移与滚轮累积）
     * @param bindings      当前键位表（M1.5：可重绑）
     * @param invertMouseY  是否反转鼠标纵向
     */
    public PlayerIntent poll(InputState in, KeyBindings bindings, boolean invertMouseY) {
        float forward = 0f;
        if (actionHeld(in, bindings, Action.MOVE_FORWARD)) {
            forward += 1f;
        }
        if (actionHeld(in, bindings, Action.MOVE_BACKWARD)) {
            forward -= 1f;
        }
        float strafe = 0f;
        if (actionHeld(in, bindings, Action.MOVE_RIGHT)) {
            strafe += 1f;
        }
        if (actionHeld(in, bindings, Action.MOVE_LEFT)) {
            strafe -= 1f;
        }

        double[] raw = in.consumeFrameDelta();
        double[] look = LookConfig.toLookDelta(raw[0], raw[1], invertMouseY);

        boolean jump = actionHeld(in, bindings, Action.JUMP);
        boolean attackHeld = actionHeld(in, bindings, Action.PRIMARY_ACTION);
        boolean usePressed = actionPressed(in, bindings, Action.SECONDARY_ACTION);
        // M2：右键同时要"电平"（持枪瞄准）与"按下沿"（放置）。
        // 同一个动作查两次是刻意的 —— 两个消费者要的是同一次按键的不同时间切片，
        // 见 PlayerIntent#useHeld 的说明。
        boolean useHeld = actionHeld(in, bindings, Action.SECONDARY_ACTION);
        boolean reloadPressed = actionPressed(in, bindings, Action.RELOAD);

        boolean respawnPressed = in.wasKeyPressed(GLFW.GLFW_KEY_F9);
        boolean toggleDebug = in.wasKeyPressed(GLFW.GLFW_KEY_F3);
        boolean savePressed = in.wasKeyPressed(GLFW.GLFW_KEY_F5);
        boolean screenshotPressed = in.wasKeyPressed(GLFW.GLFW_KEY_F2);

        // 数字键 1..9：取最后一个被按下的（同一帧内连按两个键时以更靠后的为准）
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            if (in.wasKeyPressed(GLFW.GLFW_KEY_1 + i)) {
                slot = i;
            }
        }

        int scrollSteps = consumeScrollSteps(in);

        return new PlayerIntent(forward, strafe, jump,
                look[0], look[1],
                attackHeld, usePressed, useHeld, reloadPressed, respawnPressed,
                toggleDebug, savePressed, screenshotPressed,
                scrollSteps, slot);
    }

    // ============================================================ 动作查询

    /**
     * 该动作当前是否被按住（电平）。
     *
     * <p>对未绑定的动作恒返回 {@code false} —— "未绑定"必须是"永远不触发"，
     * 而不是"任何键都触发"。这条听起来显然，但键码 {@code -1} 若不显式拦截，
     * 某些实现会让它命中 {@code keyDown[0]} 或越界判断失败后返回 true。
     */
    public static boolean actionHeld(InputState in, KeyBindings bindings, Action action) {
        InputBinding b = bindings.get(action);
        if (!b.isBound()) {
            return false;
        }
        return b.isKey() ? in.isKeyDown(b.code()) : in.isMouseDown(b.code());
    }

    /** 该动作本帧是否发生了按下沿（"按一下触发一次"用它）。 */
    public static boolean actionPressed(InputState in, KeyBindings bindings, Action action) {
        InputBinding b = bindings.get(action);
        if (!b.isBound()) {
            return false;
        }
        return b.isKey() ? in.wasKeyPressed(b.code()) : in.wasMouseButtonPressed(b.code());
    }

    /**
     * 全局"返回 / 暂停"键是否被按下。
     *
     * <p><b>ESC 是保留键：</b>无论玩家把 {@code PAUSE} 绑成什么，
     * ESC 永远具有"返回 / 暂停 / 继续"的语义。这样做的理由是
     * "可配置性不得把自己锁死"——若 PAUSE 被解绑，而"返回"又只认 PAUSE，
     * 玩家就会卡在某个界面里出不来。
     * {@code PAUSE} 动作因此是"额外的暂停键"：默认就是 ESC（符合规格），
     * 玩家也可以再绑一个（例如 {@code P}）。
     */
    public static boolean globalBackPressed(InputState in, KeyBindings bindings) {
        return in.wasKeyPressed(GLFW.GLFW_KEY_ESCAPE)
                || actionPressed(in, bindings, Action.PAUSE);
    }

    // ============================================================ 菜单

    /**
     * 收集本帧的菜单导航输入。
     *
     * <p>鼠标位置是<b>绝对坐标</b>（菜单时光标可见，不是锁定状态下的累积位移），
     * 因此这里不消费 {@code frameDelta}。反之，菜单期间必须把累积位移丢掉，
     * 否则"在菜单里晃了一圈鼠标"会在返回游戏的瞬间变成一次剧烈的视角甩动 ——
     * 这一步由调用方通过 {@link InputState#discardFrameAccumulators()} 完成。
     */
    public MenuNav pollMenuNav(InputState in) {
        double[] pointer = in.cursorPosition();
        return new MenuNav(
                in.wasKeyPressed(GLFW.GLFW_KEY_UP),
                in.wasKeyPressed(GLFW.GLFW_KEY_DOWN),
                in.wasKeyPressed(GLFW.GLFW_KEY_LEFT),
                in.wasKeyPressed(GLFW.GLFW_KEY_RIGHT),
                in.wasKeyPressed(GLFW.GLFW_KEY_ENTER) || in.wasKeyPressed(GLFW.GLFW_KEY_KP_ENTER),
                in.wasKeyPressed(GLFW.GLFW_KEY_ESCAPE),
                in.wasMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT),
                pointer[0], pointer[1],
                in.consumeScrollY());
    }

    /**
     * 滚轮增量 → 整数格数。
     *
     * <p>需要累积：精细滚轮或高分屏触控板一次可能只报 0.1 格。
     * 直接取整会让这类设备"滚不动"，而累积既保留整数语义又不丢输入。
     *
     * <p>方向遵循习惯：<b>向上滚 = 切到前一格</b>。
     */
    private int consumeScrollSteps(InputState in) {
        double dy = in.consumeScrollY();
        if (dy == 0) {
            return 0;
        }
        scrollCarry += dy;
        int steps = 0;
        while (scrollCarry >= 1.0) {
            scrollCarry -= 1.0;
            steps -= 1;
        }
        while (scrollCarry <= -1.0) {
            scrollCarry += 1.0;
            steps += 1;
        }
        return steps;
    }

    /** 重置累积量（自测脚本切换阶段时使用，避免残留位移影响下一段断言）。 */
    public void reset() {
        scrollCarry = 0;
    }
}

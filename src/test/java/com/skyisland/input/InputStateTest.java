package com.skyisland.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原始输入状态的状态清理（M1.5 缺陷回归）。
 *
 * <p>被守护的事实：<b>焦点离开时必须把"按住"状态清零。</b>
 * GLFW 只把事件投递给拥有焦点的窗口，玩家按住移动键切出去之后，
 * 那个键的 RELEASE 事件会送到别的窗口，本窗口永远等不到 ——
 * 于是 {@code keyDown[W]} 长期为真，表现为"按一下移动键之后玩家一直平移"。
 * 这类"输入源消失"的故障不能用"等下一个事件"来兜，只能靠焦点信号主动归零。
 */
class InputStateTest {

    private static void press(InputState in, int key) {
        in.onKey(key, 0, GLFW.GLFW_PRESS, 0);
    }

    private static void release(InputState in, int key) {
        in.onKey(key, 0, GLFW.GLFW_RELEASE, 0);
    }

    @Test
    @DisplayName("按住 W 时失去焦点 → W 被释放（否则玩家会一直平移）")
    void focusLossReleasesHeldKeys() {
        InputState in = new InputState();
        press(in, GLFW.GLFW_KEY_W);
        assertTrue(in.isKeyDown(GLFW.GLFW_KEY_W), "前提：按下后处于按住状态");

        in.onWindowFocus(false);

        assertFalse(in.isKeyDown(GLFW.GLFW_KEY_W),
                "失去焦点后不得再认为 W 被按住 —— 它的 RELEASE 大概率已经送到了别的窗口");
    }

    @Test
    @DisplayName("失去焦点同时释放鼠标键（否则会一直挖/一直放）")
    void focusLossReleasesHeldMouseButtons() {
        InputState in = new InputState();
        in.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
        in.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS, 0);

        in.onWindowFocus(false);

        assertFalse(in.isMouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(in.isMouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        assertEquals(0, in.currentlyDownKeyCount());
    }

    @Test
    @DisplayName("失去焦点也清除按下沿（切回来不得把切出去之前按过的键再消费一次）")
    void focusLossClearsPressedEdges() {
        InputState in = new InputState();
        press(in, GLFW.GLFW_KEY_F5);
        assertTrue(in.wasKeyPressed(GLFW.GLFW_KEY_F5));

        in.onWindowFocus(false);

        assertFalse(in.wasKeyPressed(GLFW.GLFW_KEY_F5),
                "边沿必须在失焦时清掉，否则会在切回来的那一帧触发一次存档/截图");
    }

    @Test
    @DisplayName("失去焦点不清除'E 键曾经出现过'这类观测统计（那是历史事实，不是状态）")
    void focusLossKeepsStatistics() {
        InputState in = new InputState();
        press(in, GLFW.GLFW_KEY_E);
        long eventsBefore = in.stats().keyEvents();

        in.onWindowFocus(false);

        assertEquals(eventsBefore, in.stats().keyEvents(), "事件计数属于已发生的事实，不该被回滚");
        assertTrue(in.stats().distinctKeys() >= 1);
    }

    @Test
    @DisplayName("获得焦点不改变任何按住状态（只有失去焦点才归零）")
    void focusGainDoesNotReleaseAnything() {
        InputState in = new InputState();
        press(in, GLFW.GLFW_KEY_W);

        in.onWindowFocus(true);

        assertTrue(in.isKeyDown(GLFW.GLFW_KEY_W));
        assertEquals(1, in.stats().focusGainCount());
    }

    @Test
    @DisplayName("失焦释放后再按下同一个键仍按正常路径工作（没有被锁死）")
    void keysStillWorkAfterFocusLoss() {
        InputState in = new InputState();
        press(in, GLFW.GLFW_KEY_W);
        in.onWindowFocus(false);

        press(in, GLFW.GLFW_KEY_W);
        assertTrue(in.isKeyDown(GLFW.GLFW_KEY_W));
        assertTrue(in.wasKeyPressed(GLFW.GLFW_KEY_W));

        release(in, GLFW.GLFW_KEY_W);
        assertFalse(in.isKeyDown(GLFW.GLFW_KEY_W));
    }

    @Test
    @DisplayName("releaseAllInputs 可被显式调用（窗口最小化等场景共用同一条清理）")
    void releaseAllInputsIsUsableDirectly() {
        InputState in = new InputState();
        press(in, GLFW.GLFW_KEY_A);
        in.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);

        in.releaseAllInputs();

        assertFalse(in.isKeyDown(GLFW.GLFW_KEY_A));
        assertFalse(in.isMouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT));
        // 失焦计数不应被这次直接调用改动
        assertEquals(0, in.stats().focusLossCount());
    }
}

package com.skyisland.input;

import com.skyisland.player.PlayerIntent;
import com.skyisland.settings.Action;
import com.skyisland.settings.InputBinding;
import com.skyisland.settings.KeyBindings;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "物理输入 → 动作 → 意图"这条链（M1.5 规格第 4/5/7 条）。
 *
 * <p><b>这是整个 M1.5 最该被测的一段，因为它是本阶段唯一"改动既有行为"的地方。</b>
 * M1 里 {@code InputMapper} 直接读 {@code GLFW_KEY_W}；M1.5 之后它先查动作表。
 * 一旦中间的映射写错，症状是"键位设置界面上显示 W，游戏里却不动" ——
 * 而<u>设置界面本身完全正常</u>，因此很容易被误判成"设置没保存"。
 *
 * <p>本类因此不满足于"默认键位能出正确意图"，而是把<u>重绑之后</u>的行为也断言下来：
 * 把前进改到 K 之后，K 必须真的产生前进、W 必须真的什么都不做。
 * 这条是"键位系统真的接通了玩法"的唯一证据。
 *
 * <p><b>TR7 说明：</b>本机合成键鼠事件无法送达窗口，因此这里注入的是
 * {@link InputState#onKey} 级别的原始事件 —— 被绕开的只有
 * {@code OS → GLFW} 这一段，映射、灵敏度、反转、意图生成全在真实链路上。
 */
class InputMapperActionsTest {

    /** 造一个输入状态，并把给定键置为"按住"。 */
    private static InputState withKeyDown(int key) {
        InputState in = new InputState();
        in.onKey(key, 0, GLFW.GLFW_PRESS, 0);
        return in;
    }

    private static InputState withMouseDown(int button) {
        InputState in = new InputState();
        in.onMouseButton(button, GLFW.GLFW_PRESS, 0);
        return in;
    }

    // ============================================================ 默认键位

    @Test
    void defaultKeysProduceTheSameIntentAsM1() {
        InputMapper mapper = new InputMapper();
        KeyBindings kb = KeyBindings.defaults();

        PlayerIntent fwd = mapper.poll(withKeyDown(GLFW.GLFW_KEY_W), kb, false);
        assertEquals(1f, fwd.moveForward(), 1e-6, "W 必须仍然是前进 —— M1 的契约不能在本阶段改变");

        InputMapper m2 = new InputMapper();
        PlayerIntent back = m2.poll(withKeyDown(GLFW.GLFW_KEY_S), kb, false);
        assertEquals(-1f, back.moveForward(), 1e-6);

        InputMapper m3 = new InputMapper();
        PlayerIntent left = m3.poll(withKeyDown(GLFW.GLFW_KEY_A), kb, false);
        assertEquals(-1f, left.moveStrafe(), 1e-6);

        InputMapper m4 = new InputMapper();
        PlayerIntent right = m4.poll(withKeyDown(GLFW.GLFW_KEY_D), kb, false);
        assertEquals(1f, right.moveStrafe(), 1e-6);

        InputMapper m5 = new InputMapper();
        assertTrue(m5.poll(withKeyDown(GLFW.GLFW_KEY_SPACE), kb, false).jump());
    }

    @Test
    void oppositeKeysCancelOut() {
        InputState in = new InputState();
        in.onKey(GLFW.GLFW_KEY_W, 0, GLFW.GLFW_PRESS, 0);
        in.onKey(GLFW.GLFW_KEY_S, 0, GLFW.GLFW_PRESS, 0);

        PlayerIntent intent = new InputMapper().poll(in, KeyBindings.defaults(), false);

        assertEquals(0f, intent.moveForward(), 1e-6, "同时按住前进与后退应互相抵消，而不是取其一");
    }

    @Test
    void mouseButtonsDriveThePrimaryAndSecondaryActions() {
        KeyBindings kb = KeyBindings.defaults();

        PlayerIntent mine = new InputMapper()
                .poll(withMouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT), kb, false);
        assertTrue(mine.attackHeld(), "左键的语义在 M1 是'长按挖掘'，用的是电平");

        PlayerIntent place = new InputMapper()
                .poll(withMouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT), kb, false);
        assertTrue(place.usePressed(), "右键是'按一下放一块'，用的是按下沿");
    }

    // ============================================================ 重绑真的生效

    @Test
    void rebindingForwardMovesTheBehaviourToTheNewKey() {
        KeyBindings kb = KeyBindings.defaults();
        kb.set(Action.MOVE_FORWARD, InputBinding.key(GLFW.GLFW_KEY_K));

        PlayerIntent onNewKey = new InputMapper().poll(withKeyDown(GLFW.GLFW_KEY_K), kb, false);
        assertEquals(1f, onNewKey.moveForward(), 1e-6,
                "把前进绑到 K 之后，K 必须真的能前进 —— 这是'键位系统接通了玩法'的证据");

        PlayerIntent onOldKey = new InputMapper().poll(withKeyDown(GLFW.GLFW_KEY_W), kb, false);
        assertEquals(0f, onOldKey.moveForward(), 1e-6,
                "W 已被腾出来，按它不该还有前进效果");
    }

    @Test
    void rebindingToAMouseButtonWorksAcrossNamespaces() {
        KeyBindings kb = KeyBindings.defaults();
        kb.set(Action.JUMP, InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));

        assertTrue(new InputMapper()
                .poll(withMouseDown(GLFW.GLFW_MOUSE_BUTTON_MIDDLE), kb, false).jump());
        assertFalse(new InputMapper()
                .poll(withKeyDown(GLFW.GLFW_KEY_SPACE), kb, false).jump(),
                "原来的 SPACE 必须失效");
    }

    @Test
    void anUnboundActionNeverTriggers() {
        KeyBindings kb = KeyBindings.defaults();
        kb.set(Action.MOVE_FORWARD, InputBinding.UNBOUND);
        kb.set(Action.PRIMARY_ACTION, InputBinding.UNBOUND);

        InputState in = new InputState();
        in.onKey(GLFW.GLFW_KEY_W, 0, GLFW.GLFW_PRESS, 0);
        in.onMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
        PlayerIntent intent = new InputMapper().poll(in, kb, false);

        assertEquals(0f, intent.moveForward(), 1e-6,
                "未绑定必须是'永远不触发'，而不是'任何键都触发'（键码 -1 不显式拦截时会命中 0 号键）");
        assertFalse(intent.attackHeld());
    }

    @Test
    void heldAndPressedAreIndependentMechanisms() {
        KeyBindings kb = KeyBindings.defaults();
        InputState in = withKeyDown(GLFW.GLFW_KEY_W);

        // 刚按下的一帧：电平与边沿<b>同时</b>为真 —— 这是正确行为，
        // 不是重复计数。按下这一瞬间它既"被按住"又"刚发生过按下"。
        assertTrue(InputMapper.actionHeld(in, kb, Action.MOVE_FORWARD));
        assertTrue(InputMapper.actionPressed(in, kb, Action.MOVE_FORWARD));

        // 帧末清除边沿之后，两者必须分开：按住不放的动作继续生效，
        // "按一下触发一次"的动作用不再触发。这正是 M0 缺陷 I-14 的修复点。
        in.clearPressedEdges();
        assertTrue(InputMapper.actionHeld(in, kb, Action.MOVE_FORWARD),
                "按住不放的移动必须持续生效");
        assertFalse(InputMapper.actionPressed(in, kb, Action.MOVE_FORWARD),
                "边沿已被消费，若不区分这两者，'按一下放一块'会变成'按住连续放'");
    }

    @Test
    void actionQueriesAreNullSafeOnUnboundEntries() {
        KeyBindings kb = KeyBindings.defaults();
        kb.set(Action.RELOAD, null);
        InputState in = withKeyDown(GLFW.GLFW_KEY_R);

        assertFalse(InputMapper.actionHeld(in, kb, Action.RELOAD));
        assertFalse(InputMapper.actionPressed(in, kb, Action.RELOAD));
    }

    // ============================================================ 灵敏度与反转

    @Test
    void rawMouseDeltaPassesThroughTheInputLayerUntouched() {
        InputState in = new InputState();
        in.injectCursorDelta(30, -12);

        PlayerIntent intent = new InputMapper().poll(in, KeyBindings.defaults(), false);

        assertEquals(30, intent.lookDeltaX(), 1e-9);
        assertEquals(-12, intent.lookDeltaY(), 1e-9,
                "输入层只交出'鼠标移动了多少像素'，角度换算在相机里 —— 这样灵敏度才能立即生效");
    }

    @Test
    void invertMouseYFlipsOnlyTheVerticalComponent() {
        InputState in = new InputState();
        in.injectCursorDelta(30, -12);

        PlayerIntent intent = new InputMapper().poll(in, KeyBindings.defaults(), true);

        assertEquals(30, intent.lookDeltaX(), 1e-9);
        assertEquals(12, intent.lookDeltaY(), 1e-9);
    }

    @Test
    void frameDeltaIsConsumedExactlyOnce() {
        InputState in = new InputState();
        in.injectCursorDelta(30, -12);
        InputMapper mapper = new InputMapper();

        mapper.poll(in, KeyBindings.defaults(), false);
        PlayerIntent second = mapper.poll(in, KeyBindings.defaults(), false);

        assertEquals(0, second.lookDeltaX(), 1e-9,
                "鼠标位移是帧累积量：取走即清零，否则同一段位移会在每一帧被重复应用（视角持续狂转）");
        assertEquals(0, second.lookDeltaY(), 1e-9);
    }

    // ============================================================ ESC 是保留键

    @Test
    void escapeAlwaysTriggersBackEvenWhenPauseWasRebound() {
        KeyBindings kb = KeyBindings.defaults();
        kb.set(Action.PAUSE, InputBinding.key(GLFW.GLFW_KEY_P));

        assertTrue(InputMapper.globalBackPressed(withKeyDown(GLFW.GLFW_KEY_ESCAPE), kb),
                "ESC 必须是保留键：若 PAUSE 被改走、返回又只认 PAUSE，玩家会卡在界面里出不来");
        assertTrue(InputMapper.globalBackPressed(withKeyDown(GLFW.GLFW_KEY_P), kb),
                "重新绑定的暂停键同时也应该有效（它是'额外'的暂停键）");
        assertFalse(InputMapper.globalBackPressed(withKeyDown(GLFW.GLFW_KEY_W), kb));
    }

    @Test
    void escapeStillWorksWhenPauseIsUnbound() {
        KeyBindings kb = KeyBindings.defaults();
        kb.set(Action.PAUSE, InputBinding.UNBOUND);

        assertTrue(InputMapper.globalBackPressed(withKeyDown(GLFW.GLFW_KEY_ESCAPE), kb));
    }

    // ============================================================ 调试键与快捷栏

    @Test
    void debugKeysStayHardcodedAndUnaffectedByTheActionTable() {
        KeyBindings kb = KeyBindings.defaults();
        kb.set(Action.MOVE_FORWARD, InputBinding.key(GLFW.GLFW_KEY_I));

        assertTrue(new InputMapper().poll(withKeyDown(GLFW.GLFW_KEY_F9), kb, false).respawnPressed());
        assertTrue(new InputMapper().poll(withKeyDown(GLFW.GLFW_KEY_F3), kb, false).toggleDebugPressed());
        assertTrue(new InputMapper().poll(withKeyDown(GLFW.GLFW_KEY_F5), kb, false).savePressed());
        assertTrue(new InputMapper().poll(withKeyDown(GLFW.GLFW_KEY_F2), kb, false).screenshotPressed());
    }

    @Test
    void numberKeysSelectHotbarSlots() {
        assertEquals(2, new InputMapper()
                .poll(withKeyDown(GLFW.GLFW_KEY_3), KeyBindings.defaults(), false).hotbarSlot());
        assertEquals(-1, new InputMapper()
                .poll(new InputState(), KeyBindings.defaults(), false).hotbarSlot(),
                "没有按数字键时必须是 -1（'不切换'），而不是 0（'切到第一格'）");
    }

    // ============================================================ 菜单导航

    @Test
    void menuNavigationUsesEdgeTriggersSoHoldingAKeyDoesNotScrollForever() {
        KeyBindings kb = KeyBindings.defaults();
        InputState in = new InputState();
        in.onKey(GLFW.GLFW_KEY_DOWN, 0, GLFW.GLFW_PRESS, 0);

        InputMapper mapper = new InputMapper();
        assertTrue(mapper.pollMenuNav(in).down(), "刚按下时应产生一次导航");

        in.clearPressedEdges();
        assertFalse(mapper.pollMenuNav(in).down(),
                "菜单读的是按下沿：若读电平，按住向下键会让光标疯狂滚动");
    }

    @Test
    void menuConfirmAcceptsBothMainAndKeypadEnter() {
        InputMapper mapper = new InputMapper();

        assertTrue(mapper.pollMenuNav(withKeyDown(GLFW.GLFW_KEY_ENTER)).confirm());
        assertTrue(new InputMapper().pollMenuNav(withKeyDown(GLFW.GLFW_KEY_KP_ENTER)).confirm(),
                "小键盘回车也要能用 —— 很多键盘上它是更顺手的那一个");
    }

    @Test
    void menuPointerPositionIsAbsoluteAndMayBeUnknownOnTheFirstFrame() {
        InputState in = new InputState();
        MenuNav nav = new InputMapper().pollMenuNav(in);

        assertFalse(nav.hasPointer(), "第一帧还没有位置回调，命中判定必须能识别出'位置未知'");
        assertFalse(nav.clicked());

        in.seedCursor(640, 360);
        MenuNav seeded = new InputMapper().pollMenuNav(in);
        assertTrue(seeded.hasPointer());
        assertEquals(640, seeded.mouseX(), 1e-9);
    }

    @Test
    void menuScrollDirectionFollowsTheUsualConvention() {
        InputState in = new InputState();
        in.onScroll(0, 1.0);       // 向上滚

        assertEquals(-1, new InputMapper().pollMenuNav(in).scrollSteps(),
                "向上滚 = 切到上一项");
    }

    @Test
    void menuBackIsReportedSeparatelyFromPlayingInput() {
        MenuNav nav = new InputMapper().pollMenuNav(withKeyDown(GLFW.GLFW_KEY_ESCAPE));

        assertTrue(nav.back());
    }

    // ============================================================ 滚轮累积

    @Test
    void smallScrollDeltasAccumulateInsteadOfBeingLost() {
        InputState in = new InputState();
        InputMapper mapper = new InputMapper();

        in.onScroll(0, 0.4);
        assertEquals(0, mapper.poll(in, KeyBindings.defaults(), false).hotbarScroll(),
                "0.4 格不该被直接取整成 0 并丢掉");

        in.onScroll(0, 0.4);
        assertEquals(0, mapper.poll(in, KeyBindings.defaults(), false).hotbarScroll());

        in.onScroll(0, 0.4);
        assertEquals(-1, mapper.poll(in, KeyBindings.defaults(), false).hotbarScroll(),
                "三次 0.4 格应恰好累积成一次切换（向上滚 = 切到前一格，即 -1）—— "
                        + "高分辨率触控板依赖这一点");
    }

    @Test
    void resetClearsTheScrollCarry() {
        InputState in = new InputState();
        InputMapper mapper = new InputMapper();
        in.onScroll(0, 0.9);
        mapper.poll(in, KeyBindings.defaults(), false);

        mapper.reset();
        in.onScroll(0, 0.9);

        assertEquals(0, mapper.poll(in, KeyBindings.defaults(), false).hotbarScroll(),
                "切换自测阶段时要清掉残留，否则上一段的零点几格会污染下一段");
    }
}

package com.skyisland.ui;

import com.skyisland.settings.Action;
import com.skyisland.settings.GameSettings;
import com.skyisland.settings.InputBinding;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 键位重绑的三段式流程（M1.5 规格第 5 条）。
 *
 * <p><b>冲突的语义是"转移"而不是"交换"，这是本类最重要的一条断言。</b>
 * 把 W 绑给"后退"时，"前进"必须变成<b>未绑定</b>。理由不是技术上的，
 * 而是：用户表达的是"我要把 W 用于后退"；顺手把"前进"换到 S 是替他做决定，
 * 而且换个场景（把鼠标左键绑给副操作）会得到一个更奇怪的组合。
 * 未绑定是<u>可表达、可恢复</u>的（再绑回去，或"恢复默认"），因此不危险。
 *
 * <p><b>第二条：ESC 在等待输入期间必须"取消"而不是"绑定"。</b>
 * ESC 是全局返回键。若允许把它绑给某个动作，用户在等待输入时想放弃，
 * 反而会完成一次绑定 —— 而且此后就再也无法取消（ESC 已被占用）。
 * 代价是"ESC 不能作为自定义键位"，这是刻意接受的限制，已记入报告。
 *
 * <p><b>第三条（顺序问题）：</b>捕获时必须先判 ESC、再判"是否与原键相同"、
 * 最后才判冲突。若先判冲突，按 ESC 会先弹出一个"ESC 已被 Pause 占用"的确认框 ——
 * 用户想放弃却得到一个需要回答的问题。
 */
class KeyRebindControllerTest {

    private static GameSettings freshSettings() {
        return new GameSettings();
    }

    // ============================================================ 阶段流转

    @Test
    void beginsIdleAndNeedsAnExplicitBegin() {
        KeyRebindController c = new KeyRebindController(freshSettings());

        assertEquals(KeyRebindController.Phase.IDLE, c.phase());
        assertFalse(c.isWaiting());
        assertFalse(c.isResolvingConflict());
        assertEquals("", c.promptLine());
    }

    @Test
    void beginEntersTheWaitingPhaseAndNamesItsTarget() {
        KeyRebindController c = new KeyRebindController(freshSettings());

        assertTrue(c.begin(Action.JUMP));

        assertEquals(KeyRebindController.Phase.WAITING_FOR_INPUT, c.phase());
        assertTrue(c.isWaiting());
        assertEquals(Action.JUMP, c.target());
        assertTrue(c.promptLine().contains("Jump"));
        assertTrue(c.promptLine().contains("Esc"), "提示行必须告诉用户怎么放弃");
    }

    @Test
    void beginIsRejectedWhileAlreadyBusy() {
        KeyRebindController c = new KeyRebindController(freshSettings());
        c.begin(Action.JUMP);

        assertFalse(c.begin(Action.RELOAD), "等待输入期间不能嵌套开始另一次重绑");
        assertEquals(Action.JUMP, c.target());
    }

    @Test
    void beginWithNullTargetIsRejected() {
        KeyRebindController c = new KeyRebindController(freshSettings());

        assertFalse(c.begin(null));
        assertEquals(KeyRebindController.Phase.IDLE, c.phase());
    }

    @Test
    void captureOutsideTheWaitingPhaseIsIgnored() {
        KeyRebindController c = new KeyRebindController(freshSettings());

        assertEquals(KeyRebindController.CaptureOutcome.IGNORED,
                c.capture(InputBinding.key(GLFW.GLFW_KEY_J)),
                "不在等待输入时按下的键属于别的用途，不能被当成重绑输入");
    }

    // ============================================================ 直接赋值

    @Test
    void captureAssignsAFreeKeyImmediately() {
        GameSettings s = freshSettings();
        KeyRebindController c = new KeyRebindController(s);
        c.begin(Action.JUMP);

        assertEquals(KeyRebindController.CaptureOutcome.ASSIGNED,
                c.capture(InputBinding.key(GLFW.GLFW_KEY_J)));

        assertEquals(InputBinding.key(GLFW.GLFW_KEY_J), s.keyBindings().get(Action.JUMP));
        assertEquals(KeyRebindController.Phase.IDLE, c.phase());
        assertEquals(1, c.assignedCount());
        assertNull(c.target(), "流程结束后不得留下悬空的目标动作");
    }

    @Test
    void captureAcceptsMouseButtonsToo() {
        GameSettings s = freshSettings();
        KeyRebindController c = new KeyRebindController(s);
        c.begin(Action.SECONDARY_ACTION);

        assertEquals(KeyRebindController.CaptureOutcome.ASSIGNED,
                c.capture(InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE)));

        assertEquals(InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE),
                s.keyBindings().get(Action.SECONDARY_ACTION));
    }

    @Test
    void recapturingTheSameKeyIsANoOp() {
        GameSettings s = freshSettings();
        KeyRebindController c = new KeyRebindController(s);
        c.begin(Action.JUMP);

        assertEquals(KeyRebindController.CaptureOutcome.UNCHANGED,
                c.capture(InputBinding.key(GLFW.GLFW_KEY_SPACE)));

        assertEquals(InputBinding.key(GLFW.GLFW_KEY_SPACE), s.keyBindings().get(Action.JUMP));
        assertEquals(0, c.assignedCount(), "'没变化'不能算作一次赋值，否则统计会骗人");
        assertEquals(KeyRebindController.Phase.IDLE, c.phase());
    }

    @Test
    void captureWithNullBindingIsIgnoredAndKeepsWaiting() {
        KeyRebindController c = new KeyRebindController(freshSettings());
        c.begin(Action.JUMP);

        assertEquals(KeyRebindController.CaptureOutcome.IGNORED, c.capture(null));
        assertTrue(c.isWaiting(), "一次无效输入不该把等待状态取消掉");
    }

    // ============================================================ 冲突

    @Test
    void capturingAnOccupiedKeyRaisesAConflictWithoutChangingAnything() {
        GameSettings s = freshSettings();
        KeyRebindController c = new KeyRebindController(s);
        c.begin(Action.MOVE_BACKWARD);

        assertEquals(KeyRebindController.CaptureOutcome.CONFLICT,
                c.capture(InputBinding.key(GLFW.GLFW_KEY_W)));

        assertEquals(KeyRebindController.Phase.CONFLICT, c.phase());
        assertTrue(c.isResolvingConflict());
        assertEquals(Action.MOVE_FORWARD, c.conflictOwner(),
                "界面必须知道'替换后谁会失去键位'，否则玩家是在盲选");
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_S), s.keyBindings().get(Action.MOVE_BACKWARD),
                "确认之前不得修改任何绑定");
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_W), s.keyBindings().get(Action.MOVE_FORWARD));
        assertTrue(c.promptLine().contains("Forward"));
        assertTrue(c.promptLine().contains("Enter"));
    }

    @Test
    void confirmingAConflictTransfersTheKeyAndUnbindsThePreviousOwner() {
        GameSettings s = freshSettings();
        KeyRebindController c = new KeyRebindController(s);
        c.begin(Action.MOVE_BACKWARD);
        c.capture(InputBinding.key(GLFW.GLFW_KEY_W));

        assertTrue(c.confirmReplace());

        assertEquals(InputBinding.key(GLFW.GLFW_KEY_W), s.keyBindings().get(Action.MOVE_BACKWARD));
        assertEquals(InputBinding.UNBOUND, s.keyBindings().get(Action.MOVE_FORWARD),
                "冲突是'转移'不是'交换'：原占用者变未绑定，而不是与请求方互换");
        assertFalse(s.keyBindings().get(Action.MOVE_FORWARD).isBound());
        assertEquals(1, c.replacedCount());
        assertEquals(KeyRebindController.Phase.IDLE, c.phase());
        assertNull(c.conflictOwner());
    }

    @Test
    void theTransferredKeyBecomesFreeAgainAndCanBeRebound() {
        GameSettings s = freshSettings();
        KeyRebindController c = new KeyRebindController(s);

        c.begin(Action.MOVE_BACKWARD);
        c.capture(InputBinding.key(GLFW.GLFW_KEY_W));
        c.confirmReplace();

        // 之前属于 MOVE_FORWARD 的 W，现在归 MOVE_BACKWARD；S 变成了空闲键。
        assertNull(s.keyBindings().findOwner(InputBinding.key(GLFW.GLFW_KEY_S)),
                "被腾出来的键必须真的变回空闲，否则玩家再也用不上它");

        c.begin(Action.MOVE_FORWARD);
        assertEquals(KeyRebindController.CaptureOutcome.ASSIGNED,
                c.capture(InputBinding.key(GLFW.GLFW_KEY_S)),
                "玩家可以自己把前进绑回去 —— 未绑定是可恢复的，这正是它不危险的原因");
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_S), s.keyBindings().get(Action.MOVE_FORWARD));
    }

    @Test
    void cancellingAConflictChangesNothing() {
        GameSettings s = freshSettings();
        s.setFovDeg(88);
        KeyRebindController c = new KeyRebindController(s);
        c.begin(Action.MOVE_BACKWARD);
        c.capture(InputBinding.key(GLFW.GLFW_KEY_W));

        assertTrue(c.cancelReplace());

        assertEquals(InputBinding.key(GLFW.GLFW_KEY_S), s.keyBindings().get(Action.MOVE_BACKWARD));
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_W), s.keyBindings().get(Action.MOVE_FORWARD));
        assertEquals(1, c.cancelledCount());
        assertEquals(KeyRebindController.Phase.IDLE, c.phase());
    }

    @Test
    void confirmAndCancelAreRejectedOutsideTheConflictPhase() {
        KeyRebindController c = new KeyRebindController(freshSettings());

        assertFalse(c.confirmReplace());
        assertFalse(c.cancelReplace());

        c.begin(Action.JUMP);
        assertFalse(c.confirmReplace(), "还在等待输入时确认替换没有任何意义");
        assertFalse(c.cancelReplace());
        assertTrue(c.isWaiting(), "被拒绝的确认不得把等待状态弄丢");
    }

    // ============================================================ ESC 的语义

    @Test
    void escapeCancelsInsteadOfBeingBound() {
        GameSettings s = freshSettings();
        KeyRebindController c = new KeyRebindController(s);
        c.begin(Action.JUMP);

        assertEquals(KeyRebindController.CaptureOutcome.CANCELLED,
                c.capture(InputBinding.key(GLFW.GLFW_KEY_ESCAPE)));

        assertEquals(InputBinding.key(GLFW.GLFW_KEY_SPACE), s.keyBindings().get(Action.JUMP));
        assertEquals(1, c.cancelledCount());
        assertEquals(KeyRebindController.Phase.IDLE, c.phase());
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_ESCAPE), s.keyBindings().get(Action.PAUSE),
                "ESC 是保留键，取消操作不该动到 Pause 的绑定");
    }

    @Test
    void escapeCancelsEvenWhenItIsAlreadyOwnedByAnotherAction() {
        GameSettings s = freshSettings();
        // PAUSE 的默认键就是 ESC。若捕获顺序写反（先判冲突），
        // 这一次按 ESC 会弹出一个"ESC 已被 Pause 占用"的确认框，而不是取消。
        KeyRebindController c = new KeyRebindController(s);
        c.begin(Action.JUMP);

        assertEquals(KeyRebindController.CaptureOutcome.CANCELLED,
                c.capture(InputBinding.key(GLFW.GLFW_KEY_ESCAPE)),
                "ESC 必须在冲突判定之前被处理掉 —— 用户想放弃时不该被反问一个问题");
        assertFalse(c.isResolvingConflict());
    }

    @Test
    void escapeCannotBeBoundToAnyAction() {
        GameSettings s = freshSettings();
        KeyRebindController c = new KeyRebindController(s);
        c.begin(Action.INVENTORY);

        c.capture(InputBinding.key(GLFW.GLFW_KEY_ESCAPE));

        assertEquals(InputBinding.key(GLFW.GLFW_KEY_E), s.keyBindings().get(Action.INVENTORY),
                "ESC 不可被绑定：全局返回能力不得被配置性取消掉");
    }

    @Test
    void bindingWithTheUnboundCodeIsNotMistakenForEscape() {
        GameSettings s = freshSettings();
        KeyRebindController c = new KeyRebindController(s);
        c.begin(Action.JUMP);

        // 只有"键盘 ESC"才是取消。这条防的是"把所有 code < 0 都当作 ESC"的实现错误：
        // 那会让"把某个动作解绑"这个操作直接失效。
        assertEquals(KeyRebindController.CaptureOutcome.ASSIGNED,
                c.capture(InputBinding.UNBOUND));
        assertEquals(InputBinding.UNBOUND, s.keyBindings().get(Action.JUMP));
    }

    // ============================================================ 通用取消与恢复默认

    @Test
    void cancelWorksFromBothWaitingAndConflictPhases() {
        GameSettings s = freshSettings();
        KeyRebindController c = new KeyRebindController(s);

        c.begin(Action.JUMP);
        assertTrue(c.cancel());
        assertEquals(KeyRebindController.Phase.IDLE, c.phase());

        c.begin(Action.MOVE_BACKWARD);
        c.capture(InputBinding.key(GLFW.GLFW_KEY_W));
        assertTrue(c.cancel());
        assertEquals(2, c.cancelledCount(),
                "计数是跨整个会话累加的：等待中输入 ESC 与在冲突对话框上选'否'各算一次");
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_W), s.keyBindings().get(Action.MOVE_FORWARD));
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_S), s.keyBindings().get(Action.MOVE_BACKWARD));
    }

    @Test
    void cancelWhenIdleReportsFailure() {
        KeyRebindController c = new KeyRebindController(freshSettings());

        assertFalse(c.cancel());
    }

    @Test
    void restoreDefaultsResetsTheWholeTableAndAbandonsAnOngoingRebind() {
        GameSettings s = freshSettings();
        KeyRebindController c = new KeyRebindController(s);
        s.keyBindings().set(Action.MOVE_FORWARD, InputBinding.key(GLFW.GLFW_KEY_I));
        c.begin(Action.JUMP);

        assertTrue(c.restoreDefaults());

        assertTrue(s.keyBindings().allDefaults());
        assertEquals(KeyRebindController.Phase.IDLE, c.phase(),
                "恢复默认必须顺手放弃进行中的重绑，否则'恢复默认'之后还会突然绑定一个键");
    }

    @Test
    void restoreDefaultsWorksWhenNothingIsInProgress() {
        GameSettings s = freshSettings();
        s.keyBindings().set(Action.JUMP, InputBinding.key(GLFW.GLFW_KEY_J));
        KeyRebindController c = new KeyRebindController(s);

        assertTrue(c.restoreDefaults());

        assertTrue(s.keyBindings().allDefaults());
        assertEquals(0, c.cancelledCount());
    }

    // ============================================================ 统计

    @Test
    void describeSummarizesTheSessionForTheReport() {
        GameSettings s = freshSettings();
        KeyRebindController c = new KeyRebindController(s);

        c.begin(Action.JUMP);
        c.capture(InputBinding.key(GLFW.GLFW_KEY_J));       // 直接赋值
        c.begin(Action.MOVE_BACKWARD);
        c.capture(InputBinding.key(GLFW.GLFW_KEY_W));       // 冲突
        c.confirmReplace();                                  // 确认替换
        c.begin(Action.RELOAD);
        c.capture(InputBinding.key(GLFW.GLFW_KEY_ESCAPE));  // 取消

        String text = c.describe();
        assertTrue(text.contains("已直接赋值=1"), text);
        assertTrue(text.contains("已确认替换=1"), text);
        assertTrue(text.contains("已取消=1"), text);
    }
}

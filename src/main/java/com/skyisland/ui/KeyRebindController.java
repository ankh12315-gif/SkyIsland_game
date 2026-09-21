package com.skyisland.ui;

import com.skyisland.settings.Action;
import com.skyisland.settings.GameSettings;
import com.skyisland.settings.InputBinding;
import com.skyisland.util.Log;
import org.lwjgl.glfw.GLFW;

/**
 * 键位重绑流程（M1.5 规格第 5 条）。<b>三段式：等待输入 → （可能的）冲突确认 → 完成。</b>
 *
 * <p><b>为什么"等待输入"必须是一个显式阶段：</b>点一下就立刻把"下一次任意按键"吃掉，
 * 是同一件事的另一种写法，但它无法回答三个问题 ——
 * 现在到底在等谁的输入？用户能不能取消？按到已占用的键该怎么办？
 * 把等待做成状态之后，界面可以明确地显示"正在为 Reload 等待输入（Esc 取消）"，
 * 而不是让界面看起来毫无变化、却在后台悄悄监听按键。
 *
 * <p><b>冲突的语义是"转移"而不是"交换"：</b>把 W 绑给"后退"时，
 * "前进"会变成<b>未绑定</b>，而不是与"后退"互换。
 * 理由：用户表达的是"我要把 W 用于后退"，"顺便把前进换到 S"是替他做决定 ——
 * 而且换个场景（把 MOUSE_LEFT 绑给副操作）"交换"会得到一个更奇怪的组合。
 * 未绑定是<u>可表达、可恢复</u>的（再点一次绑回去，或"恢复默认"），因此不危险。
 *
 * <p><b>ESC 在等待输入期间不参与绑定，而是取消：</b>ESC 是全局"返回"，
 * 若允许把它绑给某个动作，用户在等待输入时想放弃就会反而完成一次绑定。
 * 代价是"ESC 不能作为自定义键位"，这是刻意接受的限制，写在报告里。
 */
public final class KeyRebindController {

    public enum Phase {
        IDLE,
        WAITING_FOR_INPUT,
        CONFLICT
    }

    /** 一次输入捕获的结果。 */
    public enum CaptureOutcome {
        /** 已直接生效（无冲突）。 */
        ASSIGNED,
        /** 捕获到的就是它原本的键，无变化。 */
        UNCHANGED,
        /** 该键已被别的动作占用，等待用户确认替换。 */
        CONFLICT,
        /** 用户按 ESC 放弃本次重绑，未做任何修改。 */
        CANCELLED,
        /** 当前并不在等待输入，本次输入被忽略。 */
        IGNORED
    }

    private final GameSettings settings;

    private Phase phase = Phase.IDLE;
    private Action target;
    private InputBinding pending;
    private Action conflictOwner;
    private int assignedCount;
    private int replacedCount;
    private int cancelledCount;

    public KeyRebindController(GameSettings settings) {
        this.settings = settings;
    }

    // ============================================================ 查询

    public Phase phase() {
        return phase;
    }

    public boolean isWaiting() {
        return phase == Phase.WAITING_FOR_INPUT;
    }

    public boolean isResolvingConflict() {
        return phase == Phase.CONFLICT;
    }

    public Action target() {
        return target;
    }

    public InputBinding pending() {
        return pending;
    }

    /** 冲突时"当前占用着这个键"的动作。 */
    public Action conflictOwner() {
        return conflictOwner;
    }

    public int assignedCount() {
        return assignedCount;
    }

    public int replacedCount() {
        return replacedCount;
    }

    public int cancelledCount() {
        return cancelledCount;
    }

    /** 供界面显示的提示行（纯 ASCII）。 */
    public String promptLine() {
        return switch (phase) {
            case WAITING_FOR_INPUT -> "Press a key for \"" + label(target)
                    + "\"    (Esc = cancel)";
            case CONFLICT -> "\"" + pending.display() + "\" is already bound to \""
                    + label(conflictOwner) + "\". Replace?    (Enter = yes, Esc = no)";
            case IDLE -> "";
        };
    }

    // ============================================================ 流程

    /**
     * 开始为某个动作等待输入。
     *
     * @return 是否成功进入等待（已处于等待 / 冲突中时拒绝，避免嵌套）
     */
    public boolean begin(Action action) {
        if (phase != Phase.IDLE) {
            Log.warn("[键位] 已在 %s 阶段，忽略新的重绑请求（%s）", phase, action);
            return false;
        }
        if (action == null) {
            return false;
        }
        target = action;
        pending = null;
        conflictOwner = null;
        phase = Phase.WAITING_FOR_INPUT;
        Log.info("[键位] 开始重绑 %s（当前 %s）—— 等待输入，ESC 取消",
                action.id(), settings.keyBindings().get(action).display());
        return true;
    }

    /**
     * 捕获一次输入。
     *
     * <p>顺序很重要：<b>先判 ESC（取消），再判是否与原键相同，最后才判冲突</b>。
     * 若先判冲突，按 ESC 会先弹出一个"ESC 已被 Pause 占用"的确认框 ——
     * 用户想放弃却得到一个问题，这是纯粹的交互噪声。
     */
    public CaptureOutcome capture(InputBinding binding) {
        if (phase != Phase.WAITING_FOR_INPUT) {
            return CaptureOutcome.IGNORED;
        }
        if (binding == null) {
            return CaptureOutcome.IGNORED;
        }
        if (binding.isKey() && binding.code() == GLFW.GLFW_KEY_ESCAPE) {
            cancelledCount++;
            Log.info("[键位] 重绑已取消（ESC），%s 保持 %s",
                    target.id(), settings.keyBindings().get(target).display());
            reset();
            return CaptureOutcome.CANCELLED;
        }
        if (binding.sameAs(settings.keyBindings().get(target))) {
            Log.info("[键位] 捕获到的键与 %s 原绑定相同（%s），无变化",
                    target.id(), binding.display());
            reset();
            return CaptureOutcome.UNCHANGED;
        }
        Action owner = settings.keyBindings().findOwner(binding, target);
        if (owner != null) {
            pending = binding;
            conflictOwner = owner;
            phase = Phase.CONFLICT;
            Log.noteWarning("键位", String.format(
                    "冲突：%s 已绑定到 %s，需用户确认是否替换", binding.display(), owner.id()));
            return CaptureOutcome.CONFLICT;
        }
        assign(target, binding);
        assignedCount++;
        reset();
        return CaptureOutcome.ASSIGNED;
    }

    /** 确认替换：目标动作拿到该键，原占用者变为未绑定。 */
    public boolean confirmReplace() {
        if (phase != Phase.CONFLICT) {
            Log.warn("[键位] 当前不在冲突确认阶段（%s），忽略确认", phase);
            return false;
        }
        Action owner = conflictOwner;
        InputBinding b = pending;
        assign(target, b);
        assign(owner, InputBinding.UNBOUND);
        replacedCount++;
        Log.info("[键位] 已确认替换：%s 得到 %s，%s 变为未绑定", target.id(), b.display(), owner.id());
        reset();
        return true;
    }

    /** 取消替换：什么都不改，回到设置界面。 */
    public boolean cancelReplace() {
        if (phase != Phase.CONFLICT) {
            return false;
        }
        cancelledCount++;
        Log.info("[键位] 已取消替换：%s 保持 %s，%s 保持 %s",
                target.id(), settings.keyBindings().get(target).display(),
                conflictOwner.id(), settings.keyBindings().get(conflictOwner).display());
        reset();
        return true;
    }

    /** 通用取消（等待输入中按 ESC、或离开设置界面时调用）。 */
    public boolean cancel() {
        if (phase == Phase.IDLE) {
            return false;
        }
        cancelledCount++;
        Log.info("[键位] 重绑流程被放弃（原阶段 %s）", phase);
        reset();
        return true;
    }

    /** 恢复全部默认键位。任何阶段都可调用；会先放弃进行中的重绑。 */
    public boolean restoreDefaults() {
        boolean mid = phase != Phase.IDLE;
        if (mid) {
            reset();
        }
        settings.keyBindings().restoreDefaults();
        Log.info("[键位] 已恢复默认键位表（%s）", mid ? "同时放弃了进行中的重绑" : "当前无进行中的重绑");
        return true;
    }

    private void assign(Action action, InputBinding binding) {
        settings.keyBindings().set(action, binding);
        Log.info("[键位] %s ← %s", action.id(), binding.display());
    }

    private void reset() {
        phase = Phase.IDLE;
        target = null;
        pending = null;
        conflictOwner = null;
    }

    private static String label(Action a) {
        return a == null ? "(none)" : a.label();
    }

    public String describe() {
        return String.format("阶段=%s 已直接赋值=%d 已确认替换=%d 已取消=%d",
                phase, assignedCount, replacedCount, cancelledCount);
    }
}

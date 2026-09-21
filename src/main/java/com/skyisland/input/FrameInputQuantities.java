package com.skyisland.input;

import com.skyisland.player.PlayerIntent;

/**
 * <b>帧级</b>输入量的暂存与发放（M1.5 修正：视角位移与滚轮）。
 *
 * <h2>它修的是什么</h2>
 * 主循环是"固定逻辑步 + 可变渲染帧"（{@code GameLoop}）：一帧里可能跑
 * <b>0 个</b>、1 个或 <b>多个</b>逻辑步。而鼠标位移与滚轮是<b>帧的</b>物理量
 * （"这一帧玩家把鼠标挪了 100 像素"），不是"每个逻辑步各挪 100 像素"。
 * 因此在 M1.5 之前的两处行为都是错的，而且方向相反：
 * <ul>
 *   <li><b>一帧多个逻辑步 → 重复施加。</b>{@code frameIntent} 被同一帧的每个逻辑步
 *       复用，于是同一份位移被施加 2–3 次。40 FPS 下的视角速度因此是 120 FPS 的
 *       2–3 倍 —— 灵敏度变成了帧率的函数。</li>
 *   <li><b>一帧零个逻辑步 → 静默丢弃。</b>位移在 {@code InputMapper#poll} 里被
 *       {@code consumeFrameDelta()} 一次取走并清零，但没有任何逻辑步去用它；
 *       下一帧 {@code frameIntent} 被整体覆盖，这份位移就永远消失了。
 *       120 Hz 渲染 + 60 Hz 逻辑下约一半的鼠标输入被丢掉。</li>
 * </ul>
 * 这两个缺陷都<b>不会</b>被"M1 的脚本化自测"发现：M1 自测直接注入
 * {@code PlayerIntent}，注入粒度就是逻辑步，从构造上绕开了"帧 ⟷ 逻辑步"
 * 的粒度错配。它由 M1.5 的界面自测（注入原始键鼠、走完整产品链路）暴露出来。
 *
 * <h2>契约</h2>
 * <ol>
 *   <li>每帧开始调用 {@link #beginFrame()}；</li>
 *   <li>输入层读到帧级量后调用 {@link #accumulate}（无逻辑步的帧会自然留存）；</li>
 *   <li>每个逻辑步调用 {@link #apply}；<b>本帧第一个</b>逻辑步取走全部积累量，
 *       之后的逻辑步拿到的是 0（不重复施加）。</li>
 * </ol>
 *
 * <p><b>为什么"取走"而不是"复制"：</b>取走之后，若本帧没有任何逻辑步，
 * 积累量仍在容器里，会被下一帧继续等到 —— 这正是"不丢"的来源。
 * 若做成"每帧复制一份给 intent"，零逻辑步的帧照样会丢。
 *
 * <p><b>它不是 {@code InputState} 的一部分：</b>{@code InputState} 记录的是
 * "OS 报了什么"，属于输入层的原始事实；本类记录的是"游戏还没来得及消费的量"，
 * 属于主循环的调度状态。把两者混在一起会让 {@code InputState} 的语义变得模糊
 * （它现在能被安全地跨逻辑步共享）。
 */
public final class FrameInputQuantities {

    private double pendingLookX;
    private double pendingLookY;
    private int pendingScroll;

    /**
     * 已暂存但尚未发放的<b>离散</b>帧级量（按下沿 / 选槽）。
     *
     * <p>它们在 M2 之前一直留在 {@code frameIntent} 里，因此同时带着
     * "零逻辑步的帧丢掉"与"多逻辑步的帧重复施加"两个毛病 ——
     * 与视角位移当初的问题完全同源，只是量是布尔/整数而不是浮点。
     */
    private boolean pendingUsePressed;
    private boolean pendingReloadPressed;
    private int pendingSlot = -1;

    /** 本帧是否已经把帧级量发放出去。 */
    private boolean appliedThisFrame;

    /** 帧开始：新的一帧，帧级量尚未发放。 */
    public void beginFrame() {
        appliedThisFrame = false;
    }

    /**
     * 并入本帧从输入层读到的帧级量。
     *
     * @param lookX       本帧鼠标横移（像素，右为正）
     * @param lookY       本帧鼠标纵移（像素，下为正）
     * @param scrollSteps 本帧滚轮切槽格数
     */
    public void accumulate(double lookX, double lookY, int scrollSteps) {
        pendingLookX += lookX;
        pendingLookY += lookY;
        pendingScroll += scrollSteps;
    }

    /**
     * 并入本帧从输入层读到的<b>离散</b>帧级量（按下沿与选槽）。
     *
     * <p>与连续量分开成两个方法，是为了让调用点一眼看出"这两类量走的是同一条通道"，
     * 又不必把 {@link #accumulate} 的签名撑成五个参数。
     *
     * @param usePressed    本帧是否按下使用键（右键 = 放置）
     * @param reloadPressed 本帧是否按下换弹键（R）
     * @param slot          数字键选槽目标，{@code -1} = 本帧没有选槽
     */
    public void accumulateDiscrete(boolean usePressed, boolean reloadPressed, int slot) {
        pendingUsePressed |= usePressed;
        pendingReloadPressed |= reloadPressed;
        if (slot >= 0) {
            pendingSlot = slot;
        }
    }

    /**
     * 把帧级量施加到意图上。
     *
     * @param base                    本逻辑步的意图（不含帧级量，或含自测脚本自己的量）
     * @return {@code base} 的副本：本帧第一个逻辑步带上全部积累量，其余逻辑步的帧级量为 0
     */
    public PlayerIntent apply(PlayerIntent base) {
        if (appliedThisFrame) {
            // 同一帧的后续逻辑步：帧级量已经发放过，必须归零，否则物理会被重复推进
            return base.withLook(0, 0).withScroll(0)
                    .withUsePressed(false).withReloadPressed(false).withHotbarSlot(-1);
        }
        appliedThisFrame = true;
        PlayerIntent out = base.withLook(pendingLookX, pendingLookY).withScroll(pendingScroll)
                .withUsePressed(pendingUsePressed)
                .withReloadPressed(pendingReloadPressed)
                .withHotbarSlot(pendingSlot);
        pendingLookX = 0;
        pendingLookY = 0;
        pendingScroll = 0;
        pendingUsePressed = false;
        pendingReloadPressed = false;
        pendingSlot = -1;
        return out;
    }

    /**
     * 丢弃待发放量。
     *
     * <p>进入菜单 / 暂停 / 界面状态切换时调用：菜单期间光标是"可见指针"，
     * 此时积累的位移与"锁定视角"的位移不是一回事，带回游戏会变成一次莫名其妙的甩视角。
     * 离散量同理：在菜单里点的那几下不该带进游戏变成放置。
     */
    public void discard() {
        pendingLookX = 0;
        pendingLookY = 0;
        pendingScroll = 0;
        pendingUsePressed = false;
        pendingReloadPressed = false;
        pendingSlot = -1;
        appliedThisFrame = false;
    }

    /** 是否还有未被任何逻辑步取走的帧级量。 */
    public boolean hasPending() {
        return pendingLookX != 0 || pendingLookY != 0 || pendingScroll != 0
                || pendingUsePressed || pendingReloadPressed || pendingSlot >= 0;
    }

    /** 待发放的"使用键按下沿"。 */
    public boolean pendingUsePressed() {
        return pendingUsePressed;
    }

    /** 待发放的"换弹按下沿"。 */
    public boolean pendingReloadPressed() {
        return pendingReloadPressed;
    }

    /** 待发放的选槽目标（{@code -1} = 无）。 */
    public int pendingSlot() {
        return pendingSlot;
    }

    /** 待发放的鼠标横移（像素）。 */
    public double pendingLookX() {
        return pendingLookX;
    }

    /** 待发放的鼠标纵移（像素）。 */
    public double pendingLookY() {
        return pendingLookY;
    }

    /** 待发放的滚轮格数。 */
    public int pendingScroll() {
        return pendingScroll;
    }

    /** 本帧是否已发放过帧级量。 */
    public boolean appliedThisFrame() {
        return appliedThisFrame;
    }
}

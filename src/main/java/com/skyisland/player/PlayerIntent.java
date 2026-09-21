package com.skyisland.player;

/**
 * 一帧的玩家意图（TECH_DESIGN §B.1 单向数据流的"意图"环节）。
 *
 * <p><b>为什么要有这层，而不是让游戏逻辑直接读键盘：</b>
 * <ul>
 *   <li>逻辑步与帧率解耦后，一帧内可能跑 0 次或多次逻辑步。如果逻辑步直接读键位状态，
 *       就会出现"同一次按键被消费多次"或"某次按键一次都没被消费"。意图每帧<u>只冻结一次</u>，
 *       逻辑步消费同一份不可变快照，行为才可复现。</li>
 *   <li>它把"输入从哪来"这件事隔离掉。本机实测无法用合成输入驱动窗口
 *       （见 TECH_DESIGN_v0.1.1 §T′ TR7），因此 M1 的自测必须能<u>直接构造意图</u>，
 *       绕过 OS 输入层而仍然走完全同一条游戏逻辑链路 —— 这个记录就是那个注入口。</li>
 * </ul>
 *
 * <p>所有字段都是原始类型，值本身不代表"按键"，而代表"这一帧玩家想做什么"。
 *
 * <p><b>为什么字段说明写成 {@code @param} 而不是逐个写在参数列表里：</b>
 * 在 record 的组件列表上写 {@code /** ... *}{@code /} 会触发 javac 的
 * "文档注释未附加到任何声明"告警（{@code -Xlint:all} 下可见），
 * 而它并不是真的没生效 —— 属于噪声。集中写在这里既无告警，读起来也更像一份规格表。
 *
 * @param moveForward   前后：+1 = 前进（W），-1 = 后退（S）
 * @param moveStrafe    左右：+1 = 右移（D），-1 = 左移（A）
 * @param jump          是否想跳（按住空格；由物理层按"在地面"判定是否生效）
 * @param lookDeltaX    本帧鼠标位移 X（像素，右为正）
 * @param lookDeltaY    本帧鼠标位移 Y（像素，下为正）
 * @param attackHeld    是否按住攻击键（左键）：M1 用于持续挖掘，M2 持枪时同时是"持续开火"
 *                      （射速由 {@code GunState} 按 4 发/秒节流，因此不需要半自动的按下沿）
 * @param usePressed    本帧是否按下使用键（右键，用于放置 —— 只取按下沿）
 * @param useHeld       是否按住使用键（右键，电平）。<b>M2 新增</b>：持枪时右键是"瞄准"，
 *                      而瞄准是一个<b>持续状态</b>，用按下沿表达不了"松手退出瞄准"。
 *                      它与 {@link #usePressed} 是<b>同一个物理键的两个语义</b>，
 *                      两者并存而不是二选一：放置只关心"按下的那一刻"（按下沿能天然
 *                      去重长按），瞄准只关心"此刻是否按着"（电平）。
 *                      若把放置也改成电平，长按右键会每步都尝试放置一次。
 * @param reloadPressed 本帧是否按下换弹键（R，动作 {@code Action.RELOAD}）。按下沿 ——
 *                      "按一下开始一次换弹"，重复触发由 {@code GunState} 自己挡住。
 * @param respawnPressed 本帧是否按下强制重生键（F9；M1 无枪械，R 键留给 M2 的换弹）
 * @param toggleDebugPressed 本帧是否按下调试开关（F3）
 * @param savePressed   本帧是否按下存档（F5）
 * @param screenshotPressed 本帧是否按下截图（F2）
 * @param hotbarScroll  滚轮切槽增量（格数）
 * @param hotbarSlot    数字键直接选槽：-1 = 不选，0..8 = 目标槽
 */
public record PlayerIntent(
        float moveForward,
        float moveStrafe,
        boolean jump,
        double lookDeltaX,
        double lookDeltaY,
        boolean attackHeld,
        boolean usePressed,
        boolean useHeld,
        boolean reloadPressed,
        boolean respawnPressed,
        boolean toggleDebugPressed,
        boolean savePressed,
        boolean screenshotPressed,
        int hotbarScroll,
        int hotbarSlot
) {

    /** 空意图：不产生任何动作。用于"本帧没有输入"与自测脚本的静止帧。 */
    public static final PlayerIntent NONE = new PlayerIntent(
            0f, 0f, false, 0, 0, false, false, false, false, false, false, false, false, 0, -1);

    public boolean hasMovement() {
        return moveForward != 0f || moveStrafe != 0f;
    }

    public boolean hasLook() {
        return lookDeltaX != 0 || lookDeltaY != 0;
    }

    /**
     * 复制本意图并替换视角位移。
     *
     * <p><b>为什么需要它：</b>视角位移是<u>帧级</u>量，而意图会被同一帧的每个逻辑步复用。
     * 调用方需要"第一份带位移、后续几份把位移清零"的两种版本，因此这里只做替换，
     * 不改动其余任何字段（包括自测脚本注入的其他标志）。
     */
    public PlayerIntent withLook(double deltaX, double deltaY) {
        return new PlayerIntent(moveForward, moveStrafe, jump, deltaX, deltaY,
                attackHeld, usePressed, useHeld, reloadPressed, respawnPressed,
                toggleDebugPressed, savePressed, screenshotPressed, hotbarScroll, hotbarSlot);
    }

    /** 复制本意图并替换滚轮切槽增量（同为帧级量，理由见 {@link #withLook}）。 */
    public PlayerIntent withScroll(int scrollSteps) {
        return new PlayerIntent(moveForward, moveStrafe, jump, lookDeltaX, lookDeltaY,
                attackHeld, usePressed, useHeld, reloadPressed, respawnPressed,
                toggleDebugPressed, savePressed, screenshotPressed, scrollSteps, hotbarSlot);
    }

    /**
     * 复制本意图并替换"使用键按下沿"（右键 = 放置）。
     *
     * <p><b>它也是帧级量。</b>按下沿说的是"这一帧玩家点了一下"，不是
     * "每个逻辑步各点一下"。若让它留在逐逻辑步复用的意图里，会同时产生
     * 两个方向相反的错误：零逻辑步的帧把它丢掉、多逻辑步的帧把它重复施加。
     * 这与 {@link #withLook} 记录的问题同源，因此走同一条"暂存 → 首次发放"通道。
     */
    public PlayerIntent withUsePressed(boolean pressed) {
        return new PlayerIntent(moveForward, moveStrafe, jump, lookDeltaX, lookDeltaY,
                attackHeld, pressed, useHeld, reloadPressed, respawnPressed,
                toggleDebugPressed, savePressed, screenshotPressed, hotbarScroll, hotbarSlot);
    }

    /** 复制本意图并替换"换弹按下沿"（R）。理由见 {@link #withUsePressed}。 */
    public PlayerIntent withReloadPressed(boolean pressed) {
        return new PlayerIntent(moveForward, moveStrafe, jump, lookDeltaX, lookDeltaY,
                attackHeld, usePressed, useHeld, pressed, respawnPressed,
                toggleDebugPressed, savePressed, screenshotPressed, hotbarScroll, hotbarSlot);
    }

    /** 复制本意图并替换数字键选槽（{@code -1} = 不选）。理由见 {@link #withUsePressed}。 */
    public PlayerIntent withHotbarSlot(int slot) {
        return new PlayerIntent(moveForward, moveStrafe, jump, lookDeltaX, lookDeltaY,
                attackHeld, usePressed, useHeld, reloadPressed, respawnPressed,
                toggleDebugPressed, savePressed, screenshotPressed, hotbarScroll, slot);
    }

    /** 便于自测脚本构造：只关心移动与跳跃。 */
    public static PlayerIntent moving(float forward, float strafe, boolean jump) {
        return new PlayerIntent(forward, strafe, jump, 0, 0, false, false, false, false,
                false, false, false, false, 0, -1);
    }

    /**
     * 便于自测脚本构造：常用动作的显式表述。
     */
    public static PlayerIntent of(float forward, float strafe, boolean jump,
                                 double lookX, double lookY,
                                 boolean attackHeld, boolean usePressed) {
        return new PlayerIntent(forward, strafe, jump, lookX, lookY,
                attackHeld, usePressed, false, false, false, false, false, false, 0, -1);
    }

    /** 便于自测脚本构造：只切换快捷栏槽位（十五个组件的 record 手写构造点越少越安全）。 */
    public static PlayerIntent selectSlot(int slot) {
        return new PlayerIntent(0f, 0f, false, 0, 0, false, false, false, false,
                false, false, false, false, 0, slot);
    }

    /**
     * 便于自测脚本构造：M2 战斗动作的显式表述。
     *
     * <p>刻意做成独立工厂而不是给 {@link #of} 再加两个参数：调用点越少，
     * "这个布尔到底是瞄准还是换弹"这类顺序错位就越难发生。
     */
    public static PlayerIntent combat(float forward, float strafe, boolean jump,
                                      double lookX, double lookY,
                                      boolean attackHeld, boolean useHeld,
                                      boolean reloadPressed) {
        return new PlayerIntent(forward, strafe, jump, lookX, lookY,
                attackHeld, false, useHeld, reloadPressed,
                false, false, false, false, 0, -1);
    }

    /** 复制本意图并把"强制重生"置为真（帧级边沿动作，由游戏层注入）。 */
    public PlayerIntent withRespawn() {
        return new PlayerIntent(moveForward, moveStrafe, jump, lookDeltaX, lookDeltaY,
                attackHeld, usePressed, useHeld, reloadPressed,
                true, toggleDebugPressed, savePressed, screenshotPressed,
                hotbarScroll, hotbarSlot);
    }
}

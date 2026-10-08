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
 * @param attackPressed 本帧是否按下攻击键（左键，用于 SINGLE 半自动开火 —— 只取按下沿）。
 *                      <b>v2 §7.3 新增</b>：与 {@link #attackHeld} 是<b>同一个物理键（左键）的两个语义切片</b>，
 *                      二者<b>并存</b>而不是二选一（与 {@link #usePressed}/{@link #useHeld} 的先例一致）：
 *                      持续开火（AUTO）只关心"此刻是否按着"（电平），
 *                      半自动（SINGLE）只关心"按下的那一刻"（按下沿能天然去重长按）。
 *                      若把 SINGLE 也做成电平，长按左键会按射速反复走"新一次开火"的路径。
 *                      <p><b>它也是帧级量，必须走与 {@code usePressed} 相同的可靠通道</b>：
 *                      本项目已证明"只活一个渲染帧的一次性输入会在 0 逻辑步的帧里丢失"
 *                      （见 {@code FrameInputQuantities} 的类注释）。若把它留在逐逻辑步
 *                      复用的 {@code frameIntent} 里，症状是同源的"按了左键不开火"。
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
 * @param sneak         是否按住潜行键（左/右 Shift，电平）。
 *                      <b>M4-S8b 新增</b>：它是创造模式飞行的<b>下降</b>键。
 *                      PRD §5.4 只写了"垂直速度可控"，没指定下降键；
 *                      不补一个下降键的话，飞上去就下不来（连落地都做不到），
 *                      飞行因此是不完整的 —— 这条填补的是 PRD 的留白而不是加需求。
 */
public record PlayerIntent(
        float moveForward,
        float moveStrafe,
        boolean jump,
        double lookDeltaX,
        double lookDeltaY,
        boolean attackHeld,
        boolean attackPressed,
        boolean usePressed,
        boolean useHeld,
        boolean reloadPressed,
        boolean respawnPressed,
        boolean toggleDebugPressed,
        boolean savePressed,
        boolean screenshotPressed,
        int hotbarScroll,
        int hotbarSlot,
        boolean sneak
) {

    /**
     * 兼容构造（旧 16 参签名）：{@code sneak} 缺省为 {@code false}。
     *
     * <p>存在的理由：{@code sneak} 是 M4-S8b 才加的组件，而自测脚本与夹具里
     * 已有十几处 17 参构造点。为"多一个组件"去改十几个调用点，收益是签名更整齐，
     * 代价是<b>每一处都要复核一次参数顺序</b> —— 而顺序错位在 record 上是<b>静默</b>的
     * （两个 boolean 换位置照样编译得过）。留一个默认值明确的兼容构造更划算。
     */
    public PlayerIntent(float moveForward, float moveStrafe, boolean jump,
                         double lookDeltaX, double lookDeltaY,
                         boolean attackHeld, boolean attackPressed,
                         boolean usePressed, boolean useHeld,
                         boolean reloadPressed, boolean respawnPressed,
                         boolean toggleDebugPressed, boolean savePressed,
                         boolean screenshotPressed, int hotbarScroll, int hotbarSlot) {
        this(moveForward, moveStrafe, jump, lookDeltaX, lookDeltaY,
                attackHeld, attackPressed, usePressed, useHeld, reloadPressed, respawnPressed,
                toggleDebugPressed, savePressed, screenshotPressed,
                hotbarScroll, hotbarSlot, false);
    }
    /** 空意图：不产生任何动作。用于"本帧没有输入"与自测脚本的静止帧。 */
    public static final PlayerIntent NONE = new PlayerIntent(
            0f, 0f, false, 0, 0, false, false, false, false, false, false, false, false, false, 0, -1, false);

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
                attackHeld, attackPressed, usePressed, useHeld, reloadPressed, respawnPressed,
                toggleDebugPressed, savePressed, screenshotPressed, hotbarScroll, hotbarSlot, sneak);
    }

    /** 复制本意图并替换滚轮切槽增量（同为帧级量，理由见 {@link #withLook}）。 */
    public PlayerIntent withScroll(int scrollSteps) {
        return new PlayerIntent(moveForward, moveStrafe, jump, lookDeltaX, lookDeltaY,
                attackHeld, attackPressed, usePressed, useHeld, reloadPressed, respawnPressed,
                toggleDebugPressed, savePressed, screenshotPressed, scrollSteps, hotbarSlot, sneak);
    }

    /**
     * 复制本意图并替换"攻击键按下沿"（左键 = SINGLE 半自动开火）。
     *
     * <p><b>它也是帧级量。</b>理由与 {@link #withUsePressed} 完全同源：按下沿说的是
     * "这一帧玩家点了一下"，不是"每个逻辑步各点一下"。若让它留在逐逻辑步复用的意图里，
     * 会同时产生两个方向相反的错误：零逻辑步的帧把它丢掉、多逻辑步的帧把它重复施加。
     * AUTO 用的 {@code attackHeld}（电平）不走这里，它逐逻辑步复用。
     */
    public PlayerIntent withAttackPressed(boolean pressed) {
        return new PlayerIntent(moveForward, moveStrafe, jump, lookDeltaX, lookDeltaY,
                attackHeld, pressed, usePressed, useHeld, reloadPressed, respawnPressed,
                toggleDebugPressed, savePressed, screenshotPressed, hotbarScroll, hotbarSlot, sneak);
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
                attackHeld, attackPressed, pressed, useHeld, reloadPressed, respawnPressed,
                toggleDebugPressed, savePressed, screenshotPressed, hotbarScroll, hotbarSlot, sneak);
    }

    /** 复制本意图并替换"换弹按下沿"（R）。理由见 {@link #withUsePressed}。 */
    public PlayerIntent withReloadPressed(boolean pressed) {
        return new PlayerIntent(moveForward, moveStrafe, jump, lookDeltaX, lookDeltaY,
                attackHeld, attackPressed, usePressed, useHeld, pressed, respawnPressed,
                toggleDebugPressed, savePressed, screenshotPressed, hotbarScroll, hotbarSlot, sneak);
    }

    /** 复制本意图并替换数字键选槽（{@code -1} = 不选）。理由见 {@link #withUsePressed}。 */
    public PlayerIntent withHotbarSlot(int slot) {
        return new PlayerIntent(moveForward, moveStrafe, jump, lookDeltaX, lookDeltaY,
                attackHeld, attackPressed, usePressed, useHeld, reloadPressed, respawnPressed,
                toggleDebugPressed, savePressed, screenshotPressed, hotbarScroll, slot, sneak);
    }

    /** 便于自测脚本构造：只关心移动与跳跃。 */
    public static PlayerIntent moving(float forward, float strafe, boolean jump) {
        return new PlayerIntent(forward, strafe, jump, 0, 0, false, false, false, false, false,
                false, false, false, false, 0, -1, false);
    }

    /**
     * 便于自测脚本构造：常用动作的显式表述。
     */
    public static PlayerIntent of(float forward, float strafe, boolean jump,
                                 double lookX, double lookY,
                                 boolean attackHeld, boolean usePressed) {
        return new PlayerIntent(forward, strafe, jump, lookX, lookY,
                attackHeld, false, usePressed, false, false, false, false, false, false, 0, -1, false);
    }

    /** 便于自测脚本构造：只切换快捷栏槽位（十六个组件的 record 手写构造点越少越安全）。 */
    public static PlayerIntent selectSlot(int slot) {
        return new PlayerIntent(0f, 0f, false, 0, 0, false, false, false, false,
                false, false, false, false, false, 0, slot, false);
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
                attackHeld, false, false, useHeld, reloadPressed,
                false, false, false, false, 0, -1, false);
    }

    /** 复制本意图并把"强制重生"置为真（帧级边沿动作，由游戏层注入）。 */
    public PlayerIntent withRespawn() {
        return new PlayerIntent(moveForward, moveStrafe, jump, lookDeltaX, lookDeltaY,
                attackHeld, attackPressed, usePressed, useHeld, reloadPressed,
                true, toggleDebugPressed, savePressed, screenshotPressed,
                hotbarScroll, hotbarSlot, sneak);
    }
}

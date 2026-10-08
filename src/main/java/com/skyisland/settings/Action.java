package com.skyisland.settings;

/**
 * 游戏的<b>逻辑动作</b>（M1.5 规格第 4 条：Key Binding System）。
 *
 * <p><b>为什么必须先有这一层，而不是直接读 {@code GLFW_KEY_W}：</b>
 * 键位可重绑意味着"哪个物理键 = 前进"这件事在运行时是<u>可变的</u>。
 * 若游戏逻辑（{@code Player}/{@code World}）继续直接引用具体键码，那么
 * "重绑生效"就要求改遍所有逻辑代码；而且"W 键"这个字面量会同时承担
 * "物理键"与"前进"两个含义，二者一旦需要分离（重绑、手柄、回放、自测脚本注入）
 * 就只能靠加分支解决。分成 {@code 物理输入 → Action → PlayerIntent} 三段之后，
 * 重绑只改中间那段的映射表。
 *
 * <p><b>为什么 id 与 label 分开：</b>{@code id} 是<b>落盘契约</b>
 * （{@code settings.json} 的键名），一旦发布就不能改，否则老配置文件会读不出来；
 * {@code label} 是给人看的界面文案，随时可以改。把两者合成一个字符串，
 * 就会出现"为了改界面文案而让老配置文件失效"。
 *
 * <p><b>关于 {@code consumedBy}：</b>M1.5 阶段存在的意义是"把键位系统立起来"，
 * 而不是"把全部动作实现出来"。至今仍然<b>没有消费方</b>的只剩 {@code CROUCH}
 * —— 它<u>有默认键位、可重绑、可落盘，但按不动</u>；这一点必须显式记录，
 * 否则"能改键"会被误读成"能蹲下"。
 * <b>M2 起 {@code RELOAD} 有了真实消费方</b>（{@code GunState}），
 * <b>M2.2 起 {@code INVENTORY} 也有了</b>（{@code SkyIslandGame#openInventoryScreen}），
 * 两处标注随之摘掉；这同时是报告里"规格完成度"的诚实依据。
 */
public enum Action {

    // ---- 移动（M1.5 全部有消费方）----
    MOVE_FORWARD("move_forward", "Forward", Group.MOVEMENT, "M1.5 已消费"),
    MOVE_BACKWARD("move_backward", "Backward", Group.MOVEMENT, "M1.5 已消费"),
    MOVE_LEFT("move_left", "Strafe Left", Group.MOVEMENT, "M1.5 已消费"),
    MOVE_RIGHT("move_right", "Strafe Right", Group.MOVEMENT, "M1.5 已消费"),
    JUMP("jump", "Jump", Group.MOVEMENT, "M1.5 已消费"),

    /**
     * 蹲下 / 飞行下降。
     *
     * <p><b>2026-10-08 起它有了真实消费方</b>：创造模式的飞行下降键
     * （{@code PlayerIntent#sneak} → {@code Player#applyFlightVertical}）。
     * 在那之前 {@link InputMapper} 是<b>直读</b> {@code LEFT_SHIFT || RIGHT_SHIFT}
     * 的，理由写的是"Shift 是与创造飞行绑定的固定修饰键，不进键位表"。
     *
     * <p>那个理由站不住，主理人报"按 Shift 会切换中英文"才暴露出来：
     * <b>直读 = 不可重绑</b>，而中文 Windows 上 Shift 被输入法占用，
     * 于是飞行下降在一个完全正常的系统配置下不可用，
     * 而玩家除了改系统设置之外无从下手。
     * 加上"键位表里早就有 CROUCH、却零消费方"，
     * 同一件事存在两套真相（设置界面让人改一个按不动的键）。
     *
     * <p>★ <b>界面显示名改成 "Fly Descend"，因为它现在只驱动飞行下降。</b>
     * {@code label} 刻意不动（它是落盘契约里给人看的那一半之外的另一项，
     * 但 {@code id="crouch"} 才是真正的落盘契约，{@code label} 可以改）。
     * 留着 "Crouch" 会让玩家在设置里看到一个叫蹲下的键，
     * 按下去发现只有飞的时候有用 —— 那正是本条注释一直想避免的
     * "「能改键」被误读成「能蹲下」"。
     * <p>PRD 里的<b>蹲下玩法本身仍未实现</b>（{@code Player} 不含 crouch 姿态）。
     */
    CROUCH("crouch", "Fly Descend", Group.MOVEMENT, "M4-S8b 已消费（飞行下降）"),

    // ---- 交互（M1.5 全部有消费方）----
    /** 主操作：M1 的语义是"长按挖掘"，M2 加枪后同时是"开火"。 */
    PRIMARY_ACTION("primary_action", "Mine / Attack", Group.INTERACTION, "M1.5 已消费"),
    /** 副操作：M1 的语义是"放置方块"。 */
    SECONDARY_ACTION("secondary_action", "Place / Use", Group.INTERACTION, "M1.5 已消费"),

    /**
     * 换弹。M1.5 只确认键位；<b>M2 起有了真实消费方</b>（{@code GunState.tryStartReload}）。
     * 它占用的默认键 {@code R} 在 M1 里不被任何调试动作占用
     * （M1 把强制重生放在 F9，正是为了把 R 留给这里）。
     */
    RELOAD("reload", "Reload", Group.INTERACTION, "M2 已消费"),

    // ---- 界面 ----
    /**
     * 背包。<b>M2.2 起有了真实消费方</b>（{@code SkyIslandGame#openInventoryScreen}，
     * 默认键 {@code E}，{@code E} 或 {@code Esc} 关闭）。
     *
     * <p>它比原计划提前了一个里程碑落地：M1.5 时这一行的标注写的是"界面消费方在 M3"，
     * 而 M2.2 把 36 格背包做进了本里程碑，<b>标注必须同时摘掉</b> ——
     * 否则设置界面会显示 {@code "Inventory [M3]"}，等于告诉玩家"这个键要等下个版本才有用"，
     * 而它现在就有用。{@link #shortNote()} 的存在意义正是"不要误导玩家"。
     */
    INVENTORY("inventory", "Inventory", Group.INTERFACE, "M2.2 已消费"),
    /** 暂停 / 返回。这是 M1.5 的界面主线动作。 */
    PAUSE("pause", "Pause / Back", Group.INTERFACE, "M1.5 已消费");

    /** 动作分组，仅用于设置界面的排版。 */
    public enum Group {
        MOVEMENT("Movement"),
        INTERACTION("Interaction"),
        INTERFACE("Interface");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final String id;
    private final String label;
    private final Group group;
    private final String consumedBy;

    Action(String id, String label, Group group, String consumedBy) {
        this.id = id;
        this.label = label;
        this.group = group;
        this.consumedBy = consumedBy;
    }

    /** 落盘用的稳定 id（{@code settings.json} 的键名）。发布后不得更改。 */
    public String id() {
        return id;
    }

    /** 界面显示名（纯 ASCII —— 点阵字模只覆盖 ASCII 32–126）。 */
    public String label() {
        return label;
    }

    public Group group() {
        return group;
    }

    /**
     * 该动作是否已经有真实的玩法消费方。
     *
     * <p>判据是 {@code consumedBy} 里出现"已消费"三个字，而不是 {@code startsWith("M1.5")}。
     * M1.5 时这两者等价（那时只有 M1.5 的动作被消费），但 M2 开始消费
     * {@code RELOAD} 之后，"本阶段"这个说法就绑不住里程碑了 ——
     * 写成前缀判断会让换弹在设置界面里一直挂着 {@code [M2]} 标注，
     * 而它已经不成立（玩家按 R 真的会换弹）。标注存在的意义正是"不要误导玩家"。
     */
    public boolean isConsumedHere() {
        return consumedBy.contains("已消费");
    }

    public String consumedBy() {
        return consumedBy;
    }

    /**
     * 界面上的短标注：本阶段已消费的动作返回空串，其余返回里程碑标签（如 {@code "M2"}）。
     *
     * <p>放在设置界面的键位行后面（{@code "Reload [M2]"}），是为了让"能改键"与
     * "改完真的有效果"这两件事在界面上就<u>不混淆</u>。少了这个标注，
     * 玩家会合理地认为"我把换弹改到 F 键了，按 F 应该能换弹"。
     */
    public String shortNote() {
        if (isConsumedHere()) {
            return "";
        }
        int idx = consumedBy.indexOf("M2");
        if (idx < 0) {
            idx = consumedBy.indexOf("M3");
        }
        return idx < 0 ? "" : consumedBy.substring(idx, idx + 2);
    }

    /** 按落盘 id 反查；未知 id 返回 {@code null}（调用方决定是告警还是忽略）。 */
    public static Action byId(String id) {
        for (Action a : values()) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }
}

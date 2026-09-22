package com.skyisland.ui;

/**
 * 界面状态（M1.5 规格第 8 条）。
 *
 * <p><b>为什么是"状态机"而不是几个 boolean：</b>若用 {@code menuOpen} /
 * {@code settingsOpen} / {@code paused} 三个布尔量表达当前界面，合法组合有 8 种，
 * 其中 6 种没有意义（例如"主菜单打开 + 游戏暂停 + 设置打开"），
 * 而代码里没有任何地方能阻止它们同时为真。这类"非法组合"的表现是
 * "界面看起来对、输入却走错分支"，而且只在特定操作顺序下出现。
 * 换成枚举之后，"当前是什么界面"只有 4 种取值，非法状态<u>无法被表达</u>。
 *
 * <p>两个派生属性也放在这里，而不是散在调用点：
 * <ul>
 *   <li>{@link #simulationRunning()} —— 决定 {@code stepLogic} 是否推进物理与世界时间；</li>
 *   <li>{@link #mouseCaptured()} —— 决定光标是隐藏锁定还是可见可用。</li>
 * </ul>
 * 它们都是状态的<u>函数</u>，不是独立状态。散开写就会出现"暂停了但物理还在跑"
 * 这种单点遗漏。
 */
public enum UiState {

    /** 主菜单封面：世界已在内存中并作为背景渲染，但没有任何输入进入玩法。 */
    MAIN_MENU("Main Menu", false, false),

    /** 设置界面。可从主菜单或暂停菜单进入（来源由 {@link UiStateMachine} 记住）。 */
    SETTINGS("Settings", false, false),

    /** 游玩中：物理推进、世界时间流动、光标锁定。 */
    PLAYING("Playing", true, true),

    /** 暂停菜单：世界仍然渲染，但物理 / 世界时间 / 玩家输入全部冻结。 */
    PAUSED("Paused", false, false),

    /**
     * 背包界面：<b>世界不暂停</b>（物理 / 实体 / 世界时间继续推进，怪物继续动、
     * 玩家继续掉血、方块继续被挖）。这是"单机生存"的手感，也是本状态与
     * {@link #PAUSED} 的根本区别。光标必须可见（要拿鼠标点格子），
     * 玩法 HUD（准星 / 快捷栏 / 挖掘条）不画（快捷栏由背包界面自己画
     * —— 它是 36 格里的后 9 格，属"同一模型、同一渲染器"，不许画两份），
     * 但生命条与通知（vitals）仍然显示，否则玩家看不到自己正在挨打。
     */
    INVENTORY("背包", true, false);

    private final String label;
    private final boolean simulationRunning;
    private final boolean mouseCaptured;

    UiState(String label, boolean simulationRunning, boolean mouseCaptured) {
        this.label = label;
        this.simulationRunning = simulationRunning;
        this.mouseCaptured = mouseCaptured;
    }

    public String label() {
        return label;
    }

    /** 该状态下是否推进游戏模拟（物理 / 实体 / 世界时间）。 */
    public boolean simulationRunning() {
        return simulationRunning;
    }

    /** 该状态下是否隐藏并锁定光标（即"鼠标用于看视角"而不是"用于点界面"）。 */
    public boolean mouseCaptured() {
        return mouseCaptured;
    }

    /** 该状态下是否显示玩法 HUD（准星 / 快捷栏 / 挖掘条）。 */
    public boolean gameplayHudVisible() {
        return this == PLAYING;
    }

    /** 该状态下是否显示菜单层。 */
    public boolean menuVisible() {
        return this == MAIN_MENU || this == SETTINGS || this == PAUSED || this == INVENTORY;
    }

    /**
     * 该状态下是否显示生命条与通知（vitals）。
     *
     * <p>主菜单 / 设置界面下没有"玩家在挨打"的语义，故为 {@code false}；
     * 游玩中、暂停、以及<u>背包打开时</u>都为 {@code true} ——
     * 玩家不能因为开了背包就看不见自己在掉血。
     */
    public boolean vitalsVisible() {
        return this == PLAYING || this == PAUSED || this == INVENTORY;
    }
}

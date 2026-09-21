package com.skyisland.ui;

/**
 * 菜单里的一行（M1.5 规格第 3/5/9 条）。
 *
 * <p><b>为什么菜单是数据而不是一堆绘制调用：</b>菜单需要一个"当前选中项"的概念，
 * 而选中项要被三种输入共同驱动（鼠标悬停、上下键、滚轮）。
 * 若把菜单写成"每帧按顺序画几行文字"，那么"哪一行被选中"就要在绘制代码里靠
 * 一个外部索引去猜 —— 键鼠两路的命中判定与绘制布局必然逐渐漂移
 * （典型症状：鼠标明明点在第 3 行，结果触发的是第 4 行）。
 * 变成数据之后，布局是纯函数，命中判定与绘制读的是同一份结果。
 *
 * <p>行的类型决定"可用什么操作"：
 * <ul>
 *   <li>{@link Kind#ACTION} —— 回车 / 点击即触发；</li>
 *   <li>{@link Kind#SLIDER} —— 左右键 / 点击两侧调整数值；</li>
 *   <li>{@link Kind#TOGGLE} —— 回车 / 点击翻转布尔值；</li>
 *   <li>{@link Kind#BINDING} —— 回车 / 点击进入"等待输入"；</li>
 *   <li>{@link Kind#HEADER}、{@link Kind#SPACER}、{@link Kind#INFO} —— <b>不可选中</b>，
 *       导航时被跳过。把它们也做成可选项，会让"上下键按了却像是没反应"。</li>
 * </ul>
 */
public record MenuEntry(String id, String label, Kind kind, String value) {

    public enum Kind {
        /** 分节标题（不可选）。 */
        HEADER,
        /** 空行（不可选）。 */
        SPACER,
        /** 说明性文字（不可选）。 */
        INFO,
        /** 触发一个动作。 */
        ACTION,
        /** 数值滑杆：显示 {@link #value}，左右调整。 */
        SLIDER,
        /** 开 / 关。 */
        TOGGLE,
        /** 键位绑定：显示当前键名，回车进入等待输入。 */
        BINDING;

        public boolean selectable() {
            return this == ACTION || this == SLIDER || this == TOGGLE || this == BINDING;
        }
    }

    public static MenuEntry header(String label) {
        return new MenuEntry("__header__" + label, label, Kind.HEADER, "");
    }

    public static MenuEntry spacer() {
        return new MenuEntry("__spacer__", "", Kind.SPACER, "");
    }

    public static MenuEntry info(String label) {
        return new MenuEntry("__info__" + label, label, Kind.INFO, "");
    }

    public static MenuEntry action(String id, String label) {
        return new MenuEntry(id, label, Kind.ACTION, "");
    }

    public static MenuEntry slider(String id, String label, String valueText) {
        return new MenuEntry(id, label, Kind.SLIDER, valueText);
    }

    public static MenuEntry toggle(String id, String label, boolean on) {
        return new MenuEntry(id, label, Kind.TOGGLE, on ? "ON" : "OFF");
    }

    public static MenuEntry binding(String id, String label, String keyName) {
        return new MenuEntry(id, label, Kind.BINDING, keyName);
    }

    /** 复制一份并替换显示值（用于"只刷新变化的那一行"）。 */
    public MenuEntry withValue(String newValue) {
        return new MenuEntry(id, label, kind, newValue);
    }

    public MenuEntry withLabel(String newLabel) {
        return new MenuEntry(id, newLabel, kind, value);
    }

    public boolean selectable() {
        return kind.selectable();
    }
}

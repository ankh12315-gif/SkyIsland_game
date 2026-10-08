package com.skyisland.world;

/**
 * 昼夜的四个阶段（PRD §4.4）。
 *
 * <p><b>顺序是硬性的</b>：{@link #DAWN} → {@link #DAY} → {@link #DUSK} → {@link #NIGHT} → 回到
 * {@link #DAWN}。PRD §4.4 的一句话「一天 = 从黎明开始到下一次黎明前的完整周期」
 * 决定了 {@code values()} 的顺序<b>就是</b>周期内的先后顺序 ——
 * {@link DayClock} 直接按 {@code ordinal()} 切分时间轴，
 * 因此**在这里调整声明顺序会静默改写整个昼夜时间表**，而不报任何错。
 *
 * <p><b>为什么时长比例写在本枚举里而不是写在 {@link DayClock} 里</b>：
 * 「哪个阶段占多少」是阶段自身的性质，写在一起才能一眼看出四个数加起来是不是 1。
 * 若把它们散在 {@code DayClock} 的分支里，改一个阶段就要在两处对齐，
 * 而"改了但没对齐"的表现只是"白天似乎短了点"，没人会想到是时长表错了。
 *
 * <p><b>为什么用 {@code double} 分数而不是秒数</b>：
 * 总时长可被 {@code -Dskyisland.dayLengthSeconds} 覆盖（自测/试玩要能快进），
 * 存分数才能让四个阶段**按比例**一起缩放；
 * 存秒数的话，改总时长就会得到一个"黎明仍是 60 秒、夜晚却被压扁"的错误时间表。
 */
public enum DayPhase {

    /** 黎明（1 分钟 / 全天 5%）：天空由暗转亮；结算新一天。 */
    DAWN(0.05, "黎明"),

    /** 白天（11 分钟 / 全天 55%）：全亮；采集与建造的主要时段。 */
    DAY(0.55, "白天"),

    /** 黄昏（1 分钟 / 全天 5%）：天空由亮转暗。 */
    DUSK(0.05, "黄昏"),

    /** 夜晚（7 分钟 / 全天 35%）：昏暗，火把成为主要照明。 */
    NIGHT(0.35, "夜晚");

    /** 本阶段占全天的比例。四者之和必须恰为 1（由 {@code DayPhaseFractionsTest} 钉死）。 */
    private final double fraction;

    /** HUD 与提示用的中文名。 */
    private final String displayName;

    DayPhase(double fraction, String displayName) {
        this.fraction = fraction;
        this.displayName = displayName;
    }

    /** 本阶段占全天的比例（0–1）。 */
    public double fraction() {
        return fraction;
    }

    /** 中文名：「黎明」/「白天」/「黄昏」/「夜晚」。 */
    public String displayName() {
        return displayName;
    }
}

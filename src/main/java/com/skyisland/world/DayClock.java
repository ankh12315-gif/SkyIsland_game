package com.skyisland.world;

import com.skyisland.util.Log;

/**
 * 昼夜时钟（PRD §4.4）。
 *
 * <h2>时间表（PRD §4.4 的原文，不要凭记忆改）</h2>
 * <pre>
 *   黎明 1 分钟（5%）  → 白天 11 分钟（55%） → 黄昏 1 分钟（5%） → 夜晚 7 分钟（35%）
 *   合计 20 分钟 = 1200 秒
 * </pre>
 * 一天的<b>起点是黎明</b>（§4.4 补充规则：「一天 = 从黎明开始到下一次黎明前的完整周期；
 * 存活天数以黎明为结算点递增」），因此**跨过 {@code timeSeconds == totalSeconds} 回绕
 * 就是"黎明的结算点"**，天数在此递增。
 *
 * <h2>★ 为什么要有一个"连续日照标量" {@link #daylight()}</h2>
 * 光照是<b>烘焙进顶点色</b>的（{@link LightEngine} 的类注释：快照语义）。
 * 若让昼夜直接改烘焙值，那么天光每变一点就要重建全部区块网格 —— 半径 4 的稳态是
 * 121 块、单块网格化 0.8 ms 量级，<b>整轮重建接近 100 ms</b>，
 * 而黎明/黄昏各只有 60 秒：那等于在每分钟里塞进十几帧百毫秒级的卡顿。
 *
 * <p>因此本类只输出<b>连续</b>的日照标量 {@code d ∈ [0,1]}，
 * 由片元着色器按 uniform 施加（见 {@code voxel.frag} 的 {@code uSkyLevel}）：
 * <b>顶点一次烘焙、昼夜零重建</b>。这就是"光照连续化"的全部含义。
 *
 * <h2>★ 为什么黎明/黄昏是线性而不是阶跃</h2>
 * 若 {@code d} 只取 0 与 1（"天亮了 / 天黑了"），画面会在某一帧整体跳变一大截亮度，
 * 玩家的第一反应是"闪了一下"。60 秒的线性过渡下每帧变化约
 * {@code 1 / (60 × 60) ≈ 0.028%}，肉眼不可见 —— 这就是"连续"。
 *
 * <h2>夜晚的两个亮度参数（PRD §4.4 补充规则：「夜晚最低亮度为白天的 15%」）</h2>
 * <ul>
 *   <li>{@link #skyLevel()} —— 天光档位。白天 1.0，夜晚 {@link #NIGHT_BRIGHTNESS_RATIO}（0.15）；</li>
 *   <li>{@link #ambientFloor()} —— 明暗地板。白天 {@link LightEngine#AMBIENT_FLOOR}，
 *       夜晚为它的 15%。</li>
 * </ul>
 * 两者都由同一个 {@code d} 驱动，因此<b>不会各自漂移</b>。
 *
 * <h2>为什么火把不受夜晚影响</h2>
 * 天光档位只乘在<b>天光分量</b>上，火把分量不参与（见 {@code voxel.frag}）。
 * 这不是"顺手"，是 §4.4 明写的「夜晚昏暗，<b>火把成为主要照明</b>」——
 * 若把火把一起压暗，夜晚就只剩"更暗"，火把失去意义，PRD 的夜晚玩法也随之消失。
 */
public final class DayClock {

    /** 一个完整昼夜的默认时长（秒）：PRD §4.4 的 20 分钟。 */
    public static final double DEFAULT_TOTAL_SECONDS = 1200.0;

    /** 覆盖总时长的系统属性（自测与试玩要能快进）。 */
    public static final String SYSTEM_PROPERTY = "skyisland.dayLengthSeconds";

    /**
     * 夜晚亮度相对白天的比例（PRD §4.4：「夜晚最低亮度为白天的 15%」）。
     *
     * <p>★ 它同时作用于<b>天光档位</b>与<b>明暗地板</b>两处 ——
     * 只作用于一处的话，"夜晚"会变成"只有天空变暗但地面照旧"或反过来，
     * 而两种都<b>不报任何错</b>，只是夜晚看起来不像夜晚。
     */
    public static final float NIGHT_BRIGHTNESS_RATIO = 0.15f;

    /** 一天的起点所在时刻（黎明结束 = 白天开始）占全天的比例。 */
    public static double dayStartFraction() {
        return DayPhase.DAWN.fraction();
    }

    private final double totalSeconds;

    /** 当前时刻在一天内的秒数，恒在 {@code [0, totalSeconds)}。 */
    private double timeSeconds;

    /** 存活天数；第 1 天从开局算起，每次跨过黎明的结算点 +1。 */
    private int dayCount = 1;

    /** 无参构造：总时长取 {@link #DEFAULT_TOTAL_SECONDS}，并停在 {@link #dayStartFraction()}。 */
    public DayClock() {
        this(DEFAULT_TOTAL_SECONDS);
    }

    public DayClock(double totalSeconds) {
        this.totalSeconds = sanitizeTotal(totalSeconds);
        this.timeSeconds = this.totalSeconds * dayStartFraction();
    }

    /** 总时长（秒）。可被 {@code -Dskyisland.dayLengthSeconds} 缩放。 */
    public double totalSeconds() {
        return totalSeconds;
    }

    /** 当前时刻在一天内的秒数，恒在 {@code [0, totalSeconds)}。 */
    public double timeSeconds() {
        return timeSeconds;
    }

    /** 存活天数（从 1 开始）。 */
    public int dayCount() {
        return dayCount;
    }

    // ============================================================ 推进

    /**
     * 推进时钟。
     *
     * <p><b>回绕即结算点</b>：{@code timeSeconds} 回绕到 0 的时刻正是黎明开始，
     * 按 §4.4「存活天数以黎明为结算点递增」在此递增天数。
     *
     * <p><b>为什么用整除而不是 while 循环扣减</b>：{@code dt} 来自逻辑步，
     * 理论上被帧上限截断，但暂停恢复 / 大步长自测会给一个很大的值；
     * 用 {@code floor} 一次算完是 O(1)，while 循环在 dt 极大时会明显变慢 ——
     * 而"慢"发生在一帧里，正好是玩家最容易察觉的时刻。
     *
     * @param dt 秒；{@code NaN} / ≤ 0 一律不推进（不抛、不回绕）
     * @return 本次跨过的结算点个数（通常 0；{@code dt} 极小时也不会是负数）
     */
    public int advance(double dt) {
        if (!(dt > 0) || Double.isNaN(dt) || Double.isInfinite(dt)) {
            return 0;
        }
        double t = timeSeconds + dt;
        if (t < totalSeconds) {
            timeSeconds = t;
            return 0;
        }
        int rolled = (int) Math.floor(t / totalSeconds);
        timeSeconds = t - rolled * totalSeconds;
        // 浮点误差可能让 timeSeconds 正好落在 totalSeconds 上
        if (timeSeconds >= totalSeconds) {
            timeSeconds -= totalSeconds;
            rolled++;
        }
        dayCount += rolled;
        return rolled;
    }

    // ============================================================ 查询

    /** 当前阶段。 */
    public DayPhase phase() {
        double p = timeSeconds / totalSeconds;
        double acc = 0.0;
        for (DayPhase candidate : DayPhase.values()) {
            acc += candidate.fraction();
            if (p < acc) {
                return candidate;
            }
        }
        // p 因浮点误差取到 1.0 时会走到这里；最后一段是夜晚，与 p→1⁻ 一致。
        return DayPhase.NIGHT;
    }

    /** 当前阶段已走过的比例 {@code [0,1)}。 */
    public double phaseProgress() {
        double p = timeSeconds / totalSeconds;
        double acc = 0.0;
        for (DayPhase candidate : DayPhase.values()) {
            double next = acc + candidate.fraction();
            if (p < next) {
                double span = candidate.fraction();
                return span <= 0 ? 0.0 : (p - acc) / span;
            }
            acc = next;
        }
        return 1.0;
    }

    /** 当前阶段剩余秒数（HUD 倒计时用）。 */
    public double phaseSecondsLeft() {
        return Math.max(0.0, (1.0 - phaseProgress()) * phase().fraction() * totalSeconds);
    }

    /**
     * 连续日照标量 {@code d ∈ [0,1]}：夜晚 0，白天 1，黎明/黄昏线性过渡。
     *
     * <p><b>四个阶段在衔接处的值必须相等</b>（黎明末 = 白天 = 黄昏初 = 1；
     * 黄昏末 = 夜晚 = 黎明初 = 0），否则画面会在阶段切换那一帧跳变。
     * 这条由 {@code DayClockTest#daylightIsContinuousAcrossEveryPhaseBoundary} 钉死。
     */
    public double daylight() {
        double progress = phaseProgress();
        double d;
        switch (phase()) {
            case DAWN -> d = progress;
            case DAY -> d = 1.0;
            case DUSK -> d = 1.0 - progress;
            case NIGHT -> d = 0.0;
            default -> d = 1.0;
        }
        return Math.max(0.0, Math.min(1.0, d));
    }

    /** 天光档位（0–1）：白天 1.0，夜晚 {@link #NIGHT_BRIGHTNESS_RATIO}。 */
    public float skyLevel() {
        return ratio(daylight());
    }

    /**
     * 明暗地板：白天 {@link LightEngine#AMBIENT_FLOOR}，夜晚为它的
     * {@link #NIGHT_BRIGHTNESS_RATIO} 倍。
     */
    public float ambientFloor() {
        return LightEngine.AMBIENT_FLOOR * ratio(daylight());
    }

    /** 天空色插值系数 = {@link #daylight()}（白天天空 → 夜晚天空）。 */
    public float skyBlend() {
        return (float) daylight();
    }

    private static float ratio(double d) {
        return (float) (NIGHT_BRIGHTNESS_RATIO + (1.0 - NIGHT_BRIGHTNESS_RATIO) * d);
    }

    // ============================================================ 读档

    /**
     * 读档回填时刻。非法值（NaN / 无穷 / 负数）一律落到 {@link #dayStartFraction()}，
     * <b>不抛异常</b> —— 存档损坏时降级而不是崩溃（§N.6）。
     */
    public void setTimeSeconds(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds)) {
            timeSeconds = totalSeconds * dayStartFraction();
            return;
        }
        double t = seconds % totalSeconds;
        if (t < 0) {
            t += totalSeconds;
        }
        timeSeconds = t;
    }

    /** 读档回填天数；小于 1 一律按 1（"第 0 天"没有意义）。 */
    public void setDayCount(int days) {
        dayCount = Math.max(1, days);
    }

    /** HUD / 日志用的一行摘要，例如「第 1 天 白天 剩余 06:41」。 */
    public String statusLine() {
        long left = (long) Math.ceil(phaseSecondsLeft());
        return String.format("第 %d 天 %s 剩余 %02d:%02d",
                dayCount, phase().displayName(), left / 60, left % 60);
    }

    /** 从系统属性读总时长；缺失或非法时回落 {@link #DEFAULT_TOTAL_SECONDS} 并告警。 */
    public static double totalSecondsFromProperty() {
        String raw = System.getProperty(SYSTEM_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TOTAL_SECONDS;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (parsed > 0 && !Double.isInfinite(parsed)) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 落到下面的告警分支
        }
        Log.warn("[昼夜] -D%s=%s 不是正数，已回落默认 %.0f 秒",
                SYSTEM_PROPERTY, raw.trim(), DEFAULT_TOTAL_SECONDS);
        return DEFAULT_TOTAL_SECONDS;
    }

    private static double sanitizeTotal(double totalSeconds) {
        if (totalSeconds > 0 && !Double.isInfinite(totalSeconds)) {
            return totalSeconds;
        }
        return DEFAULT_TOTAL_SECONDS;
    }
}

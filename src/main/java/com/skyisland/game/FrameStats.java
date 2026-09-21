package com.skyisland.game;

/**
 * 帧时间与逻辑步统计（TECH_DESIGN_v0.1 §C.3 / §C.4）。
 *
 * <p><b>为什么用 P95 而不是平均帧率：</b>PRD 12.5 的判定口径就是
 * 「第 95 百分位帧时间 ≤ 16.7 ms，且不得出现 &gt; 50 ms 卡顿」。
 * 平均帧率会把 1% 的 200 ms 卡顿平均掉 —— 而卡顿恰恰是玩家唯一能感知的部分。
 * 统计必须与判定口径同构。
 *
 * <h2>实现：定宽桶直方图，而不是环形缓冲</h2>
 *
 * <p>M0 首次运行时，本类原本使用容量 3600 的环形缓冲。该设计在实测中暴露了
 * <b>两个真实缺陷</b>，因此被替换：
 *
 * <ol>
 *   <li><b>覆盖窗口不足却无提示。</b>不限帧率时本机实测约 836 FPS，
 *       3600 个样本只覆盖 <b>4.3 秒</b>。而 M0 报告声称统计窗口是 60 秒 ——
 *       也就是说 P95/P99/max 实际只描述了最后 4 秒，属于静默失真。</li>
 *   <li><b>饱和后 FPS 会退化成假值。</b>吞吐量按 {@code count / elapsed} 计算，
 *       当 {@code count} 被容量截断而 {@code elapsed} 继续增长时，
 *       该比值随 1/t 衰减，最终报出 {@code FPS ≈ 60}，
 *       而真实值约为 836 —— 恰好会让"FPS 显著高于 60"这条门禁被误判。</li>
 * </ol>
 *
 * <p>当前实现改用宽度 0.1 ms、覆盖 [0, 200] ms 的定宽桶直方图（外加一个溢出桶）：
 * <ul>
 *   <li>内存恒定：2001 个 int ≈ 8 KB，与运行时长无关</li>
 *   <li>窗口完整：覆盖自 {@link #reset()} 起的<u>全部</u>帧，不再静默截断</li>
 *   <li>分位数精确到 ±0.1 ms（桶内线性插值）</li>
 *   <li>{@code max} 与 {@code min} 精确记录，不受桶宽影响</li>
 * </ul>
 *
 * <p><b>为什么不做全样本排序：</b>60 秒 × 836 FPS ≈ 50k 样本，每 5 秒快照都排序
 * 会引入每秒数毫秒的开销，而统计行为本身不应该影响被统计的对象。
 */
public final class FrameStats {

    /** 直方图桶宽（毫秒）。决定分位数的分辨率。 */
    public static final double BUCKET_MS = 0.1;

    /** 常规桶数量：覆盖 [0, 200) ms。 */
    public static final int BUCKET_COUNT = 2000;

    /** 溢出桶下标：所有 ≥ 200 ms 的帧归入此桶（仅用于计数，取值回落到 max）。 */
    public static final int OVERFLOW_INDEX = BUCKET_COUNT;

    /** PRD 12.5 的卡顿阈值。 */
    public static final double SPIKE_THRESHOLD_MS = 50.0;

    /** PRD 12.5 的帧时间目标（60 FPS）。 */
    public static final double FRAME_TARGET_MS = 1000.0 / 60.0;

    private final int[] buckets = new int[BUCKET_COUNT + 1];

    private long frameCount = 0;
    private double sumMs = 0;
    private double maxMs = 0;
    private double minMs = Double.MAX_VALUE;
    private long spikeCount = 0;
    private long overflowCount = 0;

    private long logicSteps = 0;
    private long overrunCount = 0;
    private long clampCount = 0;

    /** 记录一帧的耗时。 */
    public void recordFrame(double frameDeltaSeconds) {
        double ms = frameDeltaSeconds * 1000.0;
        if (ms < 0 || Double.isNaN(ms)) {
            return;   // 时钟异常不应污染统计
        }
        frameCount++;
        sumMs += ms;
        if (ms > maxMs) {
            maxMs = ms;
        }
        if (ms < minMs) {
            minMs = ms;
        }
        if (ms > SPIKE_THRESHOLD_MS) {
            spikeCount++;
        }
        int b = (int) (ms / BUCKET_MS);
        if (b < 0) {
            b = 0;
        }
        if (b >= BUCKET_COUNT) {
            b = OVERFLOW_INDEX;
            overflowCount++;
        }
        buckets[b]++;
    }

    /** 记录一次逻辑步。 */
    public void recordLogicStep() {
        logicSteps++;
    }

    /** 记录一次「单帧逻辑步数触顶」的欠账丢弃。 */
    public void recordOverrun() {
        overrunCount++;
    }

    /**
     * 记录一次帧间隔钳制（{@code rawDelta > MAX_FRAME_DELTA}）。
     *
     * <p>单独计数而不是靠 {@code maxMs} 判断：钳制次数反映"逻辑时间丢了多少"，
     * 而 maxMs 反映"最长停顿有多久"，二者是不同的问题，报告里要分开说。
     */
    public void recordClamp() {
        clampCount++;
    }

    public void reset() {
        java.util.Arrays.fill(buckets, 0);
        frameCount = 0;
        sumMs = 0;
        maxMs = 0;
        minMs = Double.MAX_VALUE;
        spikeCount = 0;
        overflowCount = 0;
        logicSteps = 0;
        overrunCount = 0;
        clampCount = 0;
    }

    public long frameCount() {
        return frameCount;
    }

    public long logicSteps() {
        return logicSteps;
    }

    public long overrunCount() {
        return overrunCount;
    }

    /** 统计窗口的实际时长（毫秒）。恒等于窗口内所有帧耗时之和。 */
    public double windowMs() {
        return sumMs;
    }

    /**
     * 统计窗口的实际时长（秒）。
     *
     * <p>用「帧耗时求和」而不是「首末样本墙钟差」：前者与 {@code frameCount} 严格自洽，
     * 因此 {@code fps = frameCount / windowSeconds} 不会出现环形缓冲饱和那类偏差。
     */
    public double elapsedSeconds() {
        return sumMs / 1000.0;
    }

    // ------------------------------------------------------------- 快照

    public Snapshot snapshot() {
        if (frameCount == 0) {
            return Snapshot.EMPTY;
        }
        double elapsed = elapsedSeconds();
        return new Snapshot(
                frameCount,
                elapsed,
                frameCount / elapsed,
                logicSteps / elapsed,
                sumMs / frameCount,
                percentile(0.50),
                percentile(0.95),
                percentile(0.99),
                maxMs,
                minMs,
                spikeCount,
                countAtLeast(100.0),
                countAtLeast(150.0),
                overflowCount,
                logicSteps,
                overrunCount,
                clampCount
        );
    }

    /**
     * 统计 ≥ 给定阈值的帧数（直接累加直方图，无需额外状态）。
     *
     * <p>用来把"卡顿"分级：{@code >50 ms} 是 PRD 12.5 的判定阈值，
     * 但 50 ms 与 175 ms 是完全不同性质的事件。只有分级之后，
     * 才能判断一次超标是"轻微抖动"还是"真实停顿"。
     */
    private long countAtLeast(double msThreshold) {
        int from = (int) Math.ceil(msThreshold / BUCKET_MS);
        if (from < 0) {
            from = 0;
        }
        long n = 0;
        for (int i = from; i <= BUCKET_COUNT; i++) {
            n += buckets[i];
        }
        return n;
    }

    /**
     * 直方图分位数（桶内线性插值）。
     *
     * @param q 0..1
     */
    private double percentile(double q) {
        long target = (long) Math.ceil(q * frameCount);
        if (target < 1) {
            target = 1;
        }
        long cumulative = 0;
        for (int i = 0; i <= BUCKET_COUNT; i++) {
            cumulative += buckets[i];
            if (cumulative >= target) {
                if (i == OVERFLOW_INDEX) {
                    // ≥ 200 ms 的样本无法在桶内定位，回落到精确的 max
                    return maxMs;
                }
                // 桶内线性插值：假设该桶内样本均匀分布
                long before = cumulative - buckets[i];
                double frac = buckets[i] == 0 ? 0.0 : (target - before) / (double) buckets[i];
                return (i + frac) * BUCKET_MS;
            }
        }
        return maxMs;
    }

    /** 不可变的指标快照。 */
    public record Snapshot(
            long sampleCount,
            double elapsedSeconds,
            double fps,
            double tps,
            double meanMs,
            double medianMs,
            double p95Ms,
            double p99Ms,
            double maxMs,
            double minMs,
            long spikeCount,
            long over100Count,
            long over150Count,
            long overflowCount,
            long logicSteps,
            long overrunCount,
            long clampCount
    ) {
        public static final Snapshot EMPTY =
                new Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        /** PRD 12.5 判定口径：P95 ≤ 16.7 ms 且无 &gt; 50 ms 卡顿。 */
        public boolean meetsPerfGate() {
            return p95Ms <= FRAME_TARGET_MS && spikeCount == 0;
        }

        /** 桶宽造成的最大分辨率误差（毫秒），用于在报告中标注分位数精度。 */
        public static double percentileResolutionMs() {
            return BUCKET_MS;
        }

        public String oneLine() {
            return String.format(
                    "窗口=%.1fs n=%d FPS=%.1f TPS=%.2f mean=%.2fms median=%.2fms "
                            + "p95=%.2fms p99=%.2fms max=%.2fms min=%.2fms "
                            + ">50ms=%d >100ms=%d >150ms=%d clamped=%d overruns=%d",
                    elapsedSeconds, sampleCount, fps, tps, meanMs, medianMs,
                    p95Ms, p99Ms, maxMs, minMs,
                    spikeCount, over100Count, over150Count, clampCount, overrunCount);
        }
    }
}

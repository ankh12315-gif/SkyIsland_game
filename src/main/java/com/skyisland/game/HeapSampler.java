package com.skyisland.game;

/**
 * ★ P3：<b>堆内存与 GC 采样器</b> —— 给"占用过多内存"这条要求一个可举证的数字。
 *
 * <h2>★ 为什么它必须存在（这不是"锦上添花的监控"）</h2>
 * 主理人明确要求「不要占用过多内存」。而在本项目之前，
 * <b>没有任何一个数字能回答"这游戏吃多少内存"</b>：
 * <ul>
 *   <li>启动横幅只打 {@code Runtime.maxMemory()}（JVM 的<b>上限</b>，不是占用）；</li>
 *   <li>{@link FrameStats} 只管帧时间，与堆无关；</li>
 *   <li>M3 的性能报告（A/B 对照）比的是 mean/p95/FPS，没有一行提到内存。</li>
 * </ul>
 * ⇒ "内存没问题"这句话此前<b>没有任何依据</b>。缺了数字，
 * 后续任何"优化一下内存"都是猜：改完也不知道是变好还是变坏。
 *
 * <h2>★ 为什么"占用"必须拆成三个数，而不能只报一个</h2>
 * 单报 {@code used} 会把三件事混在一起，而它们需要<b>不同的修法</b>：
 * <table border="1">
 *   <tr><th>指标</th><th>含义</th><th>超标说明什么</th></tr>
 *   <tr><td>{@code usedHeap}</td><td>已分配、可能还活着的对象</td>
 *       <td><b>真泄漏</b>（该回收的没回收）或缓存无上限</td></tr>
 *   <tr><td>{@code usedAfterGc}</td><td>强制 GC 之后仍活着的</td>
 *       <td><b>确凿的活对象量</b> —— 泄漏的唯一硬证据</td></tr>
 *   <tr><td>{@code maxHeap}</td><td>JVM 允许的上限</td>
 *       <td>不是占用，是"天花板"</td></tr>
 * </table>
 * ★ 只报 used 的话，一次"看起来很高"的读数可能只是 GC 还没跑 ——
 *   而那个读数会让一次<b>并不存在</b>的泄漏被当成事实去修。
 *   这与本项目反复付过学费的「脆代理量」同族：<b>测错的对象比没有测量更贵</b>。
 *
 * <h2>★ 为什么要<b>周期性</b>采样而不是只测首末两点</h2>
 * 首末两点会漏掉最关键的一种故障：<b>中途涨上去不回来</b>
 * （区块加载器把每个见过的区块永久缓存、FX 池只增不减、事件订阅链把整张地图串住）。
 * 它的末值看起来完全正常，而玩家玩十分钟后 OOM。
 * ⇒ 采样器保留<b>峰值</b>与<b>趋势</b>（后半段均值 − 前半段均值），
 * 后者能把"稳定占用"与"持续增长"分开：<b>趋势比终值更能预测 OOM</b>。
 *
 * <h2>★ 为什么 {@link #take()} 允许传 {@code forceGc}
 * 而默认路径不强制 GC
 * <ul>
 *   <li>不强制：反映<b>玩家真实体验</b>（GC 该来的时候就会来），是 usedHeap 的来源；</li>
 *   <li>强制：反映<b>活对象真值</b>，是"有没有泄漏"的唯一硬证据。
 *       但强制 GC 是 STW，<b>绝不能放进每帧路径</b>，
 *       否则它自己就成了性能问题（长暂停正是它造成的）。</li>
 * </ul>
 */
public final class HeapSampler {

    /** 采样间隔的默认值（毫秒）。1 秒足够看出趋势，又不至于让采样本身成为负载。 */
    public static final long DEFAULT_INTERVAL_MS = 1000L;

    private final Runtime runtime = Runtime.getRuntime();

    /** 采样时刻（单调时钟，毫秒）。 */
    private long elapsedMs;

    private int sampleCount;

    /** 已用堆（字节，未经 GC）。 */
    private long usedHeap;
    /** 已用堆的峰值（未经 GC）。 */
    private long peakUsedHeap;

    /** 存活堆（字节，强制 GC 之后）。{@code -1} 表示尚未测过。 */
    private long usedAfterGc = -1L;
    private long peakUsedAfterGc;

    private long totalGcCount;
    private long totalGcTimeMs;

    /**
     * ★ 上一次读到的 GC 累计值（跨收集器求和后）。
     *
     * <p>没有它，每次采样都把<b>累计值</b>再加一遍 ⇒ 采样 N 次就把同一批事件数累加 N 次。
     * 那个 bug 的症状特别坏：{@code gc_per_sec} 会随运行时长<b>单调上升</b>，
     * 于是报告里出现一个"GC 越来越频繁"的结论，而实际上什么都没发生。
     */
    private long gcLastCount;
    private long gcLastTime;
    private boolean gcSeen;

    /**
     * ★ 相邻样本的差值序列（真存，不是反推）。
     *
     * <p><b>容量有界</b>：测量窗口可以是几分钟到几十分钟（每次差值 8 字节，
     * 1 小时 = 3600 个 = 28 KB，可忽略），但仍然设上限，
     * 因为"统计器自己无限增长"正是本类要监控的那类问题。
     */
    private double[] deltas = new double[256];
    private int deltaCount;
    private long previousUsedHeap;
    private boolean hasPrevious;

    private long maxHeapBytes;

    /**
     * 采一次样。
     *
     * @param deltaMs 自上次采样以来经过的毫秒（由调用方的单调时钟给出，
     *                <b>不</b>用 {@code System.currentTimeMillis()} ——
     *                后者会被调时与夏令时改动，一个负的 delta 会让趋势变成 NaN）
     * @param forceGc  是否在采样前强制一次 GC（<b>只允许在测量/诊断路径调用</b>）
     */
    public void sample(long deltaMs, boolean forceGc) {
        elapsedMs += Math.max(0L, deltaMs);
        sampleCount++;
        maxHeapBytes = runtime.maxMemory();

        if (forceGc) {
            // System.gc() 是<b>建议</b>，不是命令：JVM 允许忽略它。
            // 因此 usedAfterGc 只能作为"下界证据"用 ——
            // 判据不能是"usedAfterGc 必须等于某个值"，而是"usedAfterGc < 上限"这类。
            System.gc();
            try {
                Thread.sleep(60L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.gc();
        }

        long used = runtime.totalMemory() - runtime.freeMemory();
        usedHeap = used;
        // ★ 记差值（真存，不反推）—— 趋势必须由实际观测到的变化推出。
        if (hasPrevious) {
            appendDelta(used - previousUsedHeap);
        }
        previousUsedHeap = used;
        hasPrevious = true;
        if (used > peakUsedHeap) {
            peakUsedHeap = used;
        }
        if (forceGc) {
            usedAfterGc = used;
            if (used > peakUsedAfterGc) {
                peakUsedAfterGc = used;
            }
        }

        for (java.lang.management.GarbageCollectorMXBean gc : java.lang.management.ManagementFactory
                .getGarbageCollectorMXBeans()) {
            long count = Math.max(0L, gc.getCollectionCount());
            long time = Math.max(0L, gc.getCollectionTime());
            // ★ 必须存"上一次读到的值"再取差量，直接累加累计值是错的：
            //   采样 N 次就会把同一个 GC 事件数累加 N 次（甚至指数放大），
            //   而症状是"gc_per_sec 随时间单调上升"——一个纯属虚构的泄漏信号。
            //   与本项目已犯的「把累计量当增量用」同族。
            if (!gcSeen) {
                gcSeen = true;
                gcLastCount = count;
                gcLastTime = time;
                continue;
            }
            totalGcCount += Math.max(0L, count - gcLastCount);
            totalGcTimeMs += Math.max(0L, time - gcLastTime);
            gcLastCount = count;
            gcLastTime = time;
        }
    }

    /** 只按固定间隔采样（不强制 GC）：玩家体验路径用它。 */
    public void sample(long deltaMs) {
        sample(deltaMs, false);
    }

    /**
     * ★ 趋势 = 后半段均值 − 前半段均值（字节/样本）。
     *
     * <p><b>为什么不是"末值 − 首值"</b>：首值是启动瞬间的快照，噪声极大；
     * 而两个半段均值各自把 1 秒级抖动平均掉了。
     * 正值 = 持续增长（泄漏嫌疑），接近 0 = 稳定。
     *
     * <p>★★★ <b>这一段曾经是一个"看起来能跑"的假实现</b>：
     * 第一版用 {@code (peak - used)} 与 {@code used/2} 反推半段均值，
     * 于是实测读出 {@code trend = +34.9 MB} —— 而同一份报告里
     * {@code peak_after_gc == used == 7.31 MB}（<b>活对象 7 MB，零泄漏</b>）。
     * 那个 +34.9 完全来自预热期的一次性分配被当成"前半段基线"。
     *
     * <p>⇒ <b>教训：一个用"反推"代替"真存"的判据，会编出一个方向明确、量级合理的假结论</b>，
     * 比没有这个数字危险得多（人会去优化一个不存在的问题）。
     * 现在的实现<b>真的存每一对相邻样本</b>，趋势是这些差值的均值。
     */
    public double trendBytes() {
        if (deltas.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double d : deltas) {
            sum += d;
        }
        return sum / deltas.length;
    }

    /**
     * ★ 从"趋势"里排除<b>首样本</b>（warmup 尾声的类加载尖峰）。
     *
     * <p>调用方应优先用它：泄漏是<b>持续</b>行为，一次性尖峰不是。
     */
    public double trendBytesExcludingFirstSample() {
        if (deltas.length < 2) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 1; i < deltas.length; i++) {
            sum += deltas[i];
        }
        return sum / (deltas.length - 1);
    }

    /** 每秒回收次数（近似）。 */
    public double gcPerSecond() {
        double seconds = elapsedMs / 1000.0;
        return seconds <= 0 ? 0 : totalGcCount / seconds;
    }

    /** GC 占用时间的占比（0..1）。 */
    public double gcTimeShare() {
        return elapsedMs <= 0 ? 0 : (totalGcTimeMs / (double) elapsedMs);
    }

    /**
     * 追加一个差值，超容量则<b>丢掉最早的</b>（保留最近的一段）。
     *
     * <p>★ 为什么丢最早而不是丢最新：泄漏的证据在"最近还在涨"，
     * 而开头那段已经被预热尖峰污染，没有判别力。
     */
    private void appendDelta(double d) {
        if (deltaCount == deltas.length) {
            System.arraycopy(deltas, 1, deltas, 0, deltas.length - 1);
            deltaCount--;
        }
        deltas[deltaCount++] = d;
    }

    /**
     * 清零重来（与 {@link FrameStats#reset()} 同一时刻调用）。
     *
     * <p>★ <b>必须连 {@link #gcLastCount} 一起清</b>：不清的话，
     * 新窗口的第一次采样会把"上个窗口累计到现在"的差量一次性算进本窗口
     * ⇒ 第一秒的 GC 次数虚高，而报告里恰恰会拿第一秒当基准。
     */
    public void reset() {
        elapsedMs = 0L;
        sampleCount = 0;
        usedHeap = 0L;
        peakUsedHeap = 0L;
        usedAfterGc = -1L;
        peakUsedAfterGc = 0L;
        totalGcCount = 0L;
        totalGcTimeMs = 0L;
        gcLastCount = 0L;
        gcLastTime = 0L;
        gcSeen = false;
        deltaCount = 0;
        previousUsedHeap = 0L;
        hasPrevious = false;
        maxHeapBytes = 0L;
    }

    /** 可比对的采样摘要。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("--\n");
        sb.append("mem_samples        = ").append(sampleCount).append('\n');
        sb.append("mem_window_ms      = ").append(elapsedMs).append('\n');
        sb.append("mem_used_heap_mb   = ").append(mb(usedHeap)).append('\n');
        sb.append("mem_peak_heap_mb   = ").append(mb(peakUsedHeap)).append('\n');
        sb.append("mem_after_gc_mb    = ")
                .append(usedAfterGc < 0 ? "(未测)" : mb(usedAfterGc)).append('\n');
        sb.append("mem_peak_after_gc_mb = ")
                .append(peakUsedAfterGc <= 0 ? "(未测)" : mb(peakUsedAfterGc)).append('\n');
        sb.append("mem_max_heap_mb    = ").append(mb(maxHeapBytes)).append('\n');
        sb.append("mem_gc_count       = ").append(totalGcCount).append('\n');
        sb.append("mem_gc_time_ms     = ").append(totalGcTimeMs).append('\n');
        sb.append("mem_gc_per_sec     = ").append(String.format("%.3f", gcPerSecond())).append('\n');
        sb.append("mem_gc_share       = ")
                .append(String.format("%.5f", gcTimeShare())).append('\n');
        sb.append("mem_trend_mb       = ")
                .append(String.format("%.3f", trendBytes() / (1024.0 * 1024.0))).append('\n');
        // ★ 剔首样本的趋势是**判定泄漏该用哪个**：
        //   首样本紧跟预热，那一跳全是类加载/纹理/字模的一次性分配。
        //   两个数都打出来，读者才不会把一次性分配当成"越来越占内存"。
        sb.append("mem_trend_ex1_mb   = ")
                .append(String.format("%.3f",
                        trendBytesExcludingFirstSample() / (1024.0 * 1024.0))).append('\n');
        sb.append("mem_delta_count    = ").append(deltaCount).append('\n');
        return sb.toString();
    }

    private static String mb(long bytes) {
        return String.format("%.2f", bytes / (1024.0 * 1024.0));
    }

    // ------------------------------------------------------------- 供单测读数

    public int sampleCount() {
        return sampleCount;
    }

    public long usedHeap() {
        return usedHeap;
    }

    public long peakUsedHeap() {
        return peakUsedHeap;
    }

    public long usedAfterGc() {
        return usedAfterGc;
    }

    public long maxHeapBytes() {
        return maxHeapBytes;
    }

    public long elapsedMs() {
        return elapsedMs;
    }
}

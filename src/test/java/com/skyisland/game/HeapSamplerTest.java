package com.skyisland.game;

import com.skyisland.testutil.SourceScan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 {@link HeapSampler} 的守卫 —— 钉住「内存数字是可信的」这件事本身。
 *
 * <h2>★ 为什么需要一个守卫来守"测量工具"</h2>
 * 本项目已经付出过两次代价，形状都是"仪器坏了，于是坏结论被当成事实"：
 * <ul>
 *   <li>{@code FrameStats} 早期的环形缓冲：容量 3600 在 836 FPS 下只覆盖 <b>4.3 秒</b>，
 *       而报告声称窗口是 60 秒 —— <b>静默失真</b>；</li>
 *   <li>同一类的第二例：吞吐量按 {@code count/elapsed} 算，而 count 被容量截断，
 *       于是 FPS 随时间衰减并最终报出 ≈60 的<b>假值</b>。</li>
 * </ul>
 * ⇒ 一个新加的测量工具若不自带守卫，它的第一个 bug 会直接变成一份看起来很正式的性能报告。
 */
class HeapSamplerTest {

    // ============================================================ 累加语义

    /**
     * ★ GC 计数必须是<b>差量累加</b>，不能把累计值重复加。
     *
     * <p>反例的症状特别恶劣：{@code gc_per_sec} 随运行时长单调上升，
     * 报告会写"GC 越来越频繁"，而实际上一件事都没发生 ——
     * <b>一个纯属虚构的性能退化</b>，还会让人去优化不存在的问题。
     *
     * <p>本条是<b>源码级</b>断言（读数断言做不到：真跑一次要 1 秒且依赖外部 GC），
     * 但它带对照：既要求"存了上一次的值"，也要求"取的是差"。
     */
    @Test
    void gcCountsAreAccumulatedAsDeltasNotAsCumulativeTotals() {
        String body = SourceScan.methodBody(
                SourceScan.readMain("com/skyisland/game/HeapSampler.java"),
                "public void sample(long deltaMs, boolean forceGc)");
        assertTrue(body.contains("gcLastCount") && body.contains("gcLastTime"),
                "GC 采样没有保存上一次的累计值：下次只能直接累加累计值，"
                        + "而那会让 GC 次数随采样次数线性膨胀（虚构的性能退化）");
        assertTrue(body.contains("count - gcLastCount"),
                "GC 计数不是差量（count - gcLastCount）：这是累计值被重复累加的确切形态");
        assertTrue(body.contains("time - gcLastTime"),
                "GC 耗时不是差量（time - gcLastTime）：同上");
    }

    /** 对照：类里必须有"上一次读到的值"字段，否则差量无从谈起。 */
    @Test
    void thePreviousGcReadingIsActuallyStoredAsAField() {
        String code = SourceScan.withoutComments(
                SourceScan.readMain("com/skyisland/game/HeapSampler.java"));
        assertTrue(code.contains("private long gcLastCount;")
                        && code.contains("private long gcLastTime;"),
                "缺少 gcLastCount / gcLastTime 字段：差量累加无从实现");
    }

    /**
     * ★ {@code reset()} 必须同时清掉 GC 基线。
     *
     * <p>不清的后果：新窗口的第一次采样把"上个窗口到现在"的全部 GC 算进本窗口
     * ⇒ 第一秒的 GC 次数虚高，而报告恰恰拿第一秒当基准。
     */
    @Test
    void resetAlsoClearsTheGcBaseline() {
        String body = SourceScan.methodBody(
                SourceScan.readMain("com/skyisland/game/HeapSampler.java"),
                "public void reset()");
        assertTrue(body.contains("gcLastCount = 0L") && body.contains("gcLastTime = 0L"),
                "reset() 没清 GC 基线：新窗口的第一次 GC 读数会包含上个窗口的全部累计量");
        assertTrue(body.contains("gcSeen = false"),
                "reset() 没清 gcSeen：新窗口第一次采样会跳过差量计算（首样本被丢弃，"
                        + "于是 GC 次数凭空少一次 —— 少的那次恰好是 reset 之后最容易观察到的）");
        assertTrue(body.contains("deltaCount = 0") && body.contains("hasPrevious = false"),
                "reset() 没清趋势状态：新窗口的趋势会把上个窗口的差值算进来");
    }

    // ============================================================ 三个读数都要有

    /**
     * ★ 摘要必须同时含 {@code used} / {@code peak} / {@code afterGc}。
     *
     * <p>只报 used 的危害：一次"看起来很高"的读数可能只是 GC 还没跑，
     * 而那会让一个<b>并不存在</b>的泄漏被当成事实去修。
     */
    @Test
    void theSummarySeparatesUsedFromAfterGc() {
        String body = SourceScan.methodBody(
                SourceScan.readMain("com/skyisland/game/HeapSampler.java"),
                "public String summary()");
        assertTrue(body.contains("mem_used_heap_mb"),
                "摘要缺 used heap：无法与玩家实际体验对应");
        assertTrue(body.contains("mem_peak_heap_mb"),
                "摘要缺 peak heap：中途涨上去又降下来的尖峰会被完全看不见");
        assertTrue(body.contains("mem_after_gc_mb"),
                "摘要缺 after-GC 读数：没有泄漏的硬证据，"
                        + "而 used 偏高可能只是 GC 还没跑");
        assertTrue(body.contains("mem_trend_mb"),
                "摘要缺趋势：末值会掩盖「中途涨上去不回来」的故障（那才是玩家十分钟后 OOM 的形态）");
    }

    /** ★ {@code maxHeap} 必须与 {@code used} 分开报 —— 它是天花板不是占用。 */
    @Test
    void maxHeapIsReportedSeparatelyFromUsage() {
        String body = SourceScan.methodBody(
                SourceScan.readMain("com/skyisland/game/HeapSampler.java"),
                "public String summary()");
        assertTrue(body.contains("mem_max_heap_mb"), "摘要缺 max heap（JVM 上限）");
        assertTrue(body.indexOf("mem_max_heap_mb") > body.indexOf("mem_used_heap_mb"),
                "max heap 报在 used 之前：读者会把天花板当成占用读");
    }

    // ============================================================ 接线

    /**
     * ★ 测量摘要必须真的打出内存段。
     *
     * <p>不接线的症状：{@link HeapSampler} 存在、测试全绿，而报告里仍然一个字都没有
     * —— 与本项目 S6/S7 撞到的「单测从不走真实启动路径」同一形状。
     */
    @Test
    void theMeasurementSummaryActuallyEmitsTheHeapBlock() {
        String game = SourceScan.readMain("com/skyisland/game/SkyIslandGame.java");
        String body = SourceScan.methodBody(game, "private void emitMeasurementSummary(");
        assertTrue(body.contains("heap.summary()"),
                "测量摘要没有输出堆内存：HeapSampler 会被全绿的测试带着一起躺平");
    }

    /**
    /**
     * ★ 强制 GC 只能出现在<b>测量窗口结束</b>那一个地方。
     *
     * <p>{@code System.gc()} 是 STW 停顿；放进每帧路径等于自己制造卡顿，
     * 而"让游戏不卡"的努力会被这个诊断代码抵消。
     *
     * <p>★ 判据是<b>数调用点</b>而不是"在某处看到 true"：
     * 后者只要生产代码里出现一次 {@code heap.sample(..., true)} 就通过，
     * 而它<b>不检查那一次是不是唯一的</b> ——
     * 有人在 tick 里也加了一次强制 GC，这条断言依然绿。
     * 数量判据 + 位置判据一起给，才既防"多了一次"也防"位置错了"。
     */
    @Test
    void forcedGcHappensOnlyAtTheEndOfTheMeasurementWindow() {
        String game = SourceScan.withoutComments(
                SourceScan.readMain("com/skyisland/game/SkyIslandGame.java"));
        int calls = countOccurrences(game, "heap.sample(");
        assertEquals(2, calls,
                "heap.sample 的调用点数 = " + calls + "（期望 2：周期采样 + 测量结束时各一次）；"
                        + "多了就意味着有采样被放进了不该放的路径");
        int forced = countOccurrences(game, "lastHeapSampleSeconds) * 1000.0), true)");
        assertEquals(1, forced,
                "forceGc=true 的调用点数 = " + forced + "（期望恰好 1，且必须在测量窗口结束处）");

        // 位置：强制那次必须在 measurementDone 的分支里
        int at = game.indexOf("lastHeapSampleSeconds) * 1000.0), true)");
        assertTrue(at > 0, "找不到强制 GC 采样点");
        String window = game.substring(Math.max(0, at - 400), at);
        assertTrue(window.contains("measurementDone = true"),
                "强制 GC 采样不在 measurementDone 分支里：它会跑在每个测量步上，"
                        + "STW 停顿被放进游戏循环，「不卡」的目标被诊断代码自己抵消");
    }

    /** ★ 内存统计必须与帧统计<b>同一次</b> reset（两个窗口对齐）。 */
    @Test
    void theHeapWindowIsResetAtTheSameMomentAsTheFrameWindow() {
        String body = SourceScan.methodBody(
                SourceScan.readMain("com/skyisland/game/SkyIslandGame.java"),
                "public void stepLogic(");
        int at = body.indexOf("loop.stats().reset()");
        assertTrue(at > 0, "预热结束的分支里找不到 loop.stats().reset()");
        String window = body.substring(at, Math.min(body.length(), at + 400));
        assertTrue(window.contains("heap.reset()"),
                "预热结束时没有同时重置内存统计：两个统计窗口错开，"
                        + "「每帧堆增长」这个最关键的读数会算在错误的分母上");
    }

    // ============================================================ 趋势不许"反推"

    /**
     * ★★ 趋势必须由<b>真实相邻差值</b>推出，<b>不许从 (peak, used) 反推</b>。
     *
     * <p>★ 这条是被一次<b>真实的假读数</b>逼出来的（不是预防性断言）：
     * 第一版用 {@code (peak - used)} 与 {@code used/2} 反推"前后半段均值"，
     * 60 秒实测读出 {@code trend = +34.9 MB}；
     * 而同一份报告里 {@code peak_after_gc == used == 7.31 MB}（<b>活对象 7 MB，零泄漏</b>）。
     * 那个 +34.9 全部来自预热期的一次性分配（类加载/纹理/字模）被当成前半段基线。
     *
     * <p>⇒ <b>一个用反推代替真存的判据，会编出方向明确、量级合理的假结论</b>，
     * 比没有这个数字危险得多：人会去优化一个不存在的问题。
     */
    @Test
    void theTrendIsComputedFromRealDeltasNeverBackDerivedFromThePeak() {
        String code = SourceScan.withoutComments(
                SourceScan.readMain("com/skyisland/game/HeapSampler.java"));
        String body = SourceScan.methodBody(
                SourceScan.readMain("com/skyisland/game/HeapSampler.java"),
                "public double trendBytes()");
        assertTrue(body.contains("deltas"),
                "trendBytes() 没有读差值序列：趋势必须是观测到的变化，不是反推出来的");
        assertFalse(body.contains("peakUsedHeap"),
                "trendBytes() 里出现了 peakUsedHeap：那是反推的信号 —— "
                        + "预热尖峰会污染基线并编出 +30MB 级的假增长");
        assertTrue(code.contains("appendDelta(used - previousUsedHeap)"),
                "没有把相邻样本的差值真存下来：趋势无从正确计算");
    }

    /** 对照：差值序列必须<b>有界</b>（统计器自己无限增长正是它要监控的那类问题）。 */
    @Test
    void theDeltaSeriesIsBoundedAndDropsTheOldest() {
        String body = SourceScan.methodBody(
                SourceScan.readMain("com/skyisland/game/HeapSampler.java"),
                "private void appendDelta(double d)");
        assertTrue(body.contains("System.arraycopy"),
                "差值序列满了之后没有丢弃最旧的：长时间测量会让统计器自己吃内存");
        String code = SourceScan.withoutComments(
                SourceScan.readMain("com/skyisland/game/HeapSampler.java"));
        assertTrue(code.contains("new double["),
                "差值序列是定长数组（容量有界）：用 List 的话内存随测量时长增长");
    }

    /** ★ 摘要必须同时给出「含首样本」与「剔首样本」两个趋势。 */
    @Test
    void theSummaryReportsBothTheRawAndTheFirstSampleExcludedTrend() {
        String body = SourceScan.methodBody(
                SourceScan.readMain("com/skyisland/game/HeapSampler.java"),
                "public String summary()");
        assertTrue(body.contains("mem_trend_mb") && body.contains("mem_trend_ex1_mb"),
                "摘要只给一个趋势数：读者无法判断那个正数是一次性分配还是持续增长");
    }
    // ============================================================ 纯逻辑读数

    /**
     * ★ 未采样时 {@code usedAfterGc} 必须是 {@code -1} 而不是 {@code 0}。
     *
     * <p>{@code 0} 会被摘要渲染成「0.00 MB」—— 读起来像"活对象为零"
     * （不可能），而正确语义是"没测过"。这与 {@code LevelMeta.gameMode} 必须为
     * {@code null} 才能区分"缺字段"与"值为 survival"是同一条纪律。
     */
    @Test
    void anUnsampledAfterGcReadsAsUnmeasuredNotAsZero() {
        HeapSampler sampler = new HeapSampler();
        assertEquals(-1L, sampler.usedAfterGc(),
                "未采样时 usedAfterGc 应为 -1（=未测过），0 会被读成「活对象为零」");
        String summary = sampler.summary();
        assertTrue(summary.contains("(未测)"),
                "未采样时摘要没有标注「未测」：读者会把 0.00 MB 当成测量结果");
    }

    /** 未采样时趋势必须是 0（而不是 NaN —— NaN 会一路传到报告里）。 */
    @Test
    void theTrendIsZeroBeforeEnoughSamplesExist() {
        HeapSampler sampler = new HeapSampler();
        assertEquals(0.0, sampler.trendBytes(), 1e-9, "未采样时趋势应为 0");
        sampler.sample(1000L, false);
        assertEquals(0.0, sampler.trendBytes(), 1e-9, "单样本时趋势应为 0");
        assertTrue(!Double.isNaN(sampler.gcPerSecond()), "gcPerSecond 不应为 NaN");
        assertTrue(!Double.isNaN(sampler.gcTimeShare()), "gcTimeShare 不应为 NaN");
    }

    /** 负的 delta（时钟回拨）不得让窗口变成负数。 */
    @Test
    void aNegativeDeltaDoesNotCorruptTheWindow() {
        HeapSampler sampler = new HeapSampler();
        sampler.sample(-5000L, false);
        assertEquals(0L, sampler.elapsedMs(), "负 delta 污染了统计窗口");
        sampler.sample(1000L, false);
        assertEquals(1000L, sampler.elapsedMs());
    }

    /** 采样计数必须真的增长（接线自查：不然 summary 永远是空的）。 */
    @Test
    void samplingAdvancesTheCounter() {
        HeapSampler sampler = new HeapSampler();
        for (int i = 0; i < 5; i++) {
            sampler.sample(1000L, false);
        }
        assertEquals(5, sampler.sampleCount(), "采样计数没有推进");
        assertTrue(sampler.maxHeapBytes() > 0, "maxMemory 读数为 0：JVM 没报上来");
        assertTrue(sampler.usedHeap() > 0, "已用堆读数为 0：采样没有真的读 Runtime");
    }

    /** reset 之后计数归零（否则跨窗口比较会读到上一窗口的尾巴）。 */
    @Test
    void resetClearsEverything() {
        HeapSampler sampler = new HeapSampler();
        for (int i = 0; i < 4; i++) {
            sampler.sample(1000L, false);
        }
        sampler.reset();
        assertEquals(0, sampler.sampleCount(), "reset 后采样计数没归零");
        assertEquals(0L, sampler.elapsedMs(), "reset 后窗口没归零");
        assertEquals(0L, sampler.peakUsedHeap(), "reset 后峰值没归零");
        assertEquals(-1L, sampler.usedAfterGc(), "reset 后 afterGc 应回到「未测」");
    }
    /** 数子串出现次数（守卫里多处需要它，避免每处重抄一遍循环）。 */
    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int at = 0;
        while (true) {
            int i = haystack.indexOf(needle, at);
            if (i < 0) {
                return n;
            }
            n++;
            at = i + needle.length();
        }
    }

}

package com.skyisland.audio;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PcmSynth} 的性质测试。
 *
 * <p><b>为什么波形也要测试：</b>"合成的到底是不是一段能听的 PCM"这件事，
 * 在没有声卡的机器上是没有第二种举证方式的。它不受任何断言保护的话，
 * 一次系数笔误（比如把衰减时间常数写成采样数）会产出一秒全零的音频，
 * 而代码编译通过、日志一切正常、只是玩家什么都没听到。
 *
 * <p>这里断言的是波形的<b>性质</b>而不是具体样本值：
 * 时长、峰值、均方根、首尾是否归零、是否决定性。
 * 断言具体样本会把测试变成"当前合成器的快照"，改一次配方就碎一片 ——
 * 与 {@code CjkFontTest} 不写死字模形状是同一个立场。
 */
class PcmSynthTest {

    /** 合格的响度下限。远低于满刻度，只用来排除"全是零 / 全是极小数"这种情况。 */
    private static final int MIN_PEAK = 4000;

    /** 五个音之间必须能分辨 —— 用频谱性质近似这一点，见 {@link #theFiveSoundsAreNotTheSameWaveform()}。 */
    private static final double MIN_RMS = 0.005;

    /**
     * 过零率：波形每秒穿过零线的次数，是"这个音有多亮"的粗略读数。
     * 用它区分"低频闷响"与"高频咔哒"比看均值有效得多。
     */
    private static double zeroCrossingRate(short[] pcm) {
        int crossings = 0;
        for (int i = 1; i < pcm.length; i++) {
            if ((pcm[i - 1] < 0) != (pcm[i] < 0)) {
                crossings++;
            }
        }
        return crossings / (pcm.length / (double) PcmSynth.SAMPLE_RATE);
    }

    /**
     * 包络轮廓：把波形切成 {@code buckets} 段，每段取均方根，再按自身最大段归一。
     *
     * <p>归一这一步是必需的：不归一的话比的是"谁更响"，而这里要问的是
     * "能量在时间上怎么分布"。
     */
    private static double[] envelopeProfile(short[] pcm, int buckets) {
        double[] profile = new double[buckets];
        for (int b = 0; b < buckets; b++) {
            int from = pcm.length * b / buckets;
            int to = Math.max(from + 1, pcm.length * (b + 1) / buckets);
            double sum = 0;
            for (int i = from; i < to && i < pcm.length; i++) {
                double v = pcm[i] / 32767.0;
                sum += v * v;
            }
            profile[b] = Math.sqrt(sum / (to - from));
        }
        double max = 0;
        for (double v : profile) {
            max = Math.max(max, v);
        }
        if (max > 0) {
            for (int i = 0; i < profile.length; i++) {
                profile[i] /= max;
            }
        }
        return profile;
    }

    private static double maxBucketDifference(double[] a, double[] b) {
        double max = 0;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, Math.abs(a[i] - b[i]));
        }
        return max;
    }

    // ============================================================ 事件表覆盖

    @Test
    void everyEventHasAtLeastOneVariantAndAGain() {
        assertEquals(5, AudioEvent.values().length,
                "M2.1 的事件表就是这五个音，多一个少一个都要在报告里说明");

        for (AudioEvent event : AudioEvent.values()) {
            assertTrue(event.variants() >= 1, event.id() + " 至少要有一个变体");
            assertTrue(event.baseGain() > 0f && event.baseGain() <= 1f,
                    event.id() + " 的基准增益必须落在 (0, 1]");
            // 变体下标必须归一化到合法区间：多打几次调用也不该越界
            for (int raw = -3; raw <= 7; raw++) {
                int normalized = event.normalizeVariant(raw);
                assertTrue(normalized >= 0 && normalized < event.variants(),
                        event.id() + " 变体 " + raw + " 归一化后越界：" + normalized);
            }
        }
    }

    @Test
    void variantCursorRotatesInsteadOfRepeating() {
        AudioEvent event = AudioEvent.GUN_FIRE;
        assertTrue(event.variants() >= 2, "本测试需要至少一个多变的事件");

        int first = event.nextVariant();
        int second = event.nextVariant();
        assertNotEquals(first, second, "连续两次取变体必须轮换，否则连发时每枪完全一样");

        // 走满一轮后必须回到起点：否则"轮换"其实是随机/乱序
        int seen = 2;
        while (seen < event.variants()) {
            event.nextVariant();
            seen++;
        }
        assertEquals(first, event.nextVariant(), "走满一轮后应回到第一个变体");
    }

    // ============================================================ 波形性质

    @Test
    void everyClipIsAudibleFiniteAndFreeOfClipping() {
        for (AudioEvent event : AudioEvent.values()) {
            for (int variant = 0; variant < event.variants(); variant++) {
                short[] pcm = PcmSynth.render(event, variant);

                assertTrue(pcm.length > 0, event.id() + "#" + variant + " 不该是空缓冲");
                assertEquals((int) Math.round(PcmSynth.durationSeconds(event) * PcmSynth.SAMPLE_RATE),
                        pcm.length, event.id() + "#" + variant + " 的样本数应与声明时长一致");

                int peak = PcmSynth.peak(pcm);
                assertTrue(peak >= MIN_PEAK,
                        event.id() + "#" + variant + " 峰值只有 " + peak + "，听起来等于没有声音");
                assertTrue(peak <= 32767,
                        event.id() + "#" + variant + " 峰值 " + peak + " 溢出 16 位，会削波");

                double rms = PcmSynth.rms(pcm);
                assertTrue(rms > MIN_RMS,
                        event.id() + "#" + variant + " 的均方根只有 " + rms + "，能量过低");
            }
        }
    }

    @Test
    void clipsStartAndEndAtZeroToAvoidClicks() {
        for (AudioEvent event : AudioEvent.values()) {
            for (int variant = 0; variant < event.variants(); variant++) {
                short[] pcm = PcmSynth.render(event, variant);
                assertEquals(0, pcm[0],
                        event.id() + "#" + variant + " 第一个样本不为 0 —— 播放开始时会有一次阶跃（爆音）");
                assertEquals(0, pcm[pcm.length - 1],
                        event.id() + "#" + variant + " 最后一个样本不为 0 —— 播放结束时会爆一下");
            }
        }
    }

    @Test
    void clipsHaveNoDcOffset() {
        for (AudioEvent event : AudioEvent.values()) {
            short[] pcm = PcmSynth.render(event, 0);
            long sum = 0;
            for (short s : pcm) {
                sum += s;
            }
            double mean = sum / (double) pcm.length;
            assertTrue(Math.abs(mean) < 20,
                    event.id() + " 的直流偏置是 " + mean + "，会把扬声器的可用量程吃掉一部分");
        }
    }

    @Test
    void synthesisIsDeterministicForTheSameEventAndVariant() {
        for (AudioEvent event : AudioEvent.values()) {
            for (int variant = 0; variant < event.variants(); variant++) {
                short[] first = PcmSynth.render(event, variant);
                short[] second = PcmSynth.render(event, variant);
                assertArrayEquals(first, second,
                        event.id() + "#" + variant + " 两次合成必须逐样本相同（否则测试无法复现）");
            }
        }
    }

    /**
     * 五个音的包络形状必须彼此不同 —— 否则"我开枪了"与"我被打中了"在听感上合并。
     *
     * <p><b>为什么用包络轮廓而不是"时长 + 亮度"：</b>五个音的时长本来就全不一样，
     * 拿时长当判据的话这条断言恒真，等于没写。把每个音切成 16 段、按自身峰值归一，
     * 得到的是"能量在时间上怎么分布" —— 这才是一个音区别于另一个音的东西
     * （一声撞击是"立刻到顶然后迅速没"，换弹是"三下"，受伤是"慢慢退"）。
     *
     * <p>阈值 0.10 是实测出来的：最接近的一对（gun_empty 与 hit_enemy）实测差 0.17。
     * 留出约 1.7 倍余量，配方微调不会让它变红，而"两个音被改成一样"一定会被抓住。
     */
    @Test
    void theFiveSoundsHaveDistinctEnvelopeShapes() {
        AudioEvent[] all = AudioEvent.values();
        Map<AudioEvent, double[]> profiles = new EnumMap<>(AudioEvent.class);
        for (AudioEvent event : all) {
            profiles.put(event, envelopeProfile(PcmSynth.render(event, 0), 16));
        }

        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                double difference = maxBucketDifference(profiles.get(all[i]), profiles.get(all[j]));
                assertTrue(difference > 0.10,
                        all[i].id() + " 与 " + all[j].id() + " 的包络轮廓只差 " + difference
                                + "，听感上会合并（实测最接近的一对是 0.17）");
            }
        }
    }

    /**
     * 空仓必须是五个音里最短的，换弹必须是最长的。
     *
     * <p>前者是"咔哒"的定义（它得短到不像一个音，才能被读成"没打出去"），
     * 后者是"一次完整机械行程"的定义。这两条不是审美，是<b>语义</b>：
     * 把空仓做成 0.3 秒，玩家会以为自己真的开了一枪。
     */
    @Test
    void dryFireIsTheShortestClipAndReloadIsTheLongest() {
        for (AudioEvent other : AudioEvent.values()) {
            if (other != AudioEvent.GUN_EMPTY) {
                assertTrue(PcmSynth.durationSeconds(AudioEvent.GUN_EMPTY)
                                < PcmSynth.durationSeconds(other),
                        "空仓必须是五个音里最短的，却比 " + other.id() + " 长");
            }
            if (other != AudioEvent.RELOAD) {
                assertTrue(PcmSynth.durationSeconds(AudioEvent.RELOAD)
                                > PcmSynth.durationSeconds(other),
                        "换弹必须是五个音里最长的，却比 " + other.id() + " 短");
            }
        }
    }

    /**
     * 受伤音必须比枪声与命中音明显更低沉。
     *
     * <p>这是五个音里唯一一条<b>有方向性</b>的混音约束，因此单独成条：
     * 受伤是要传达"退"的，而高频亮音在听觉上天然是"进"的信号。
     * 实测比值为 1.5，这里取 1.3 作为下限。
     */
    @Test
    void playerHurtIsDarkerThanTheShotAndTheHitConfirm() {
        double hurt = zeroCrossingRate(PcmSynth.render(AudioEvent.PLAYER_HURT, 0));
        double fire = zeroCrossingRate(PcmSynth.render(AudioEvent.GUN_FIRE, 0));
        double hit = zeroCrossingRate(PcmSynth.render(AudioEvent.HIT_ENEMY, 0));

        assertTrue(fire > hurt * 1.3, "枪声必须比受伤音更亮：fire=" + fire + " hurt=" + hurt);
        assertTrue(hit > hurt * 1.3, "命中音必须比受伤音更亮：hit=" + hit + " hurt=" + hurt);
    }

    @Test
    void memoryFootprintStaysTiny() {
        long samples = PcmSynth.totalSampleCount();
        long bytes = samples * 2L;

        assertTrue(bytes > 0, "样本总数必须大于 0");
        assertTrue(bytes < 512 * 1024,
                "M2.1 的全部占位音一共 " + bytes + " 字节（" + (bytes / 1024) + " KiB），"
                        + "超过 512 KiB 就不再是「程序化合成」该有的量级，需要复核配方");
    }

    @Test
    void nullEventIsRejectedInsteadOfSilentlyRendering() {
        assertThrows(IllegalArgumentException.class, () -> PcmSynth.render(null, 0));
        assertThrows(IllegalArgumentException.class, () -> PcmSynth.durationSeconds(null));
    }

    /** 每个音都必须有"实打实在响"的中间段，而不只是首尾各有一两个非零样本。 */
    @Test
    void everyClipHasSubstantialNonSilentContent() {
        for (AudioEvent event : AudioEvent.values()) {
            short[] pcm = PcmSynth.render(event, 0);
            int nonZero = 0;
            for (short s : pcm) {
                if (s != 0) {
                    nonZero++;
                }
            }
            double ratio = nonZero / (double) pcm.length;
            assertTrue(ratio > 0.5,
                    event.id() + " 只有 " + String.format("%.1f%%", ratio * 100)
                            + " 的样本非零 —— 听起来会是一串断续的爆音而不是一个音");
        }
    }
}

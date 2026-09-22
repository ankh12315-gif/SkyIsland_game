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
     *
     * <p><b>它的适用范围严格限于"跨音色比较"（{@link #playerHurtIsDarkerThanTheShotAndTheHitConfirm}），
     * 不能用来测同一个音内部的音高走向。</b>实测记录（M2.2，见 {@link #uiOpenAndUiCloseAreAPair}）：
     * ui_open 的源码滑音是 620→1271 Hz（明确的<span>上行</span>），但用"后半段过零率 ÷ 前半段"
     * 量出来是 2205→1423 Hz，也就是<span>下行</span>——符号是反的。
     * 原因：合成器里有 0.14 增益的宽带"空气"噪声层，过零率量的是噪声与瞬态亮度，
     * 不是基频。同一原因让自相关与频带能量比也在这三个新音上失效，
     * 四轮仪器尝试的完整记录见 {@code docs/testing/M2_2_UI_INVENTORY_REPORT.md}。
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
        // M2.1 是 5 个战斗音；M2.2 增补 4 个 UI 音（open / close / move / denied），共 9 个。
        // 这条数字是刻意写死的：事件表变动必须有人在这里改一行，而不是悄无声息地扩容 ——
        // 每多一个音都会同时影响内存预算、混音总响度与"哪些交互有声音"的产品口径。
        assertEquals(9, AudioEvent.values().length,
                "M2.2 的音频事件表是 5 个战斗音 + 4 个 UI 音，多一个少一个都要在报告里说明");

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
     * 除"方向对"之外，全部音的包络形状必须<b>两两</b>不同 —— 否则"我开枪了"与"我被打中了"在听感上合并。
     *
     * <p><b>为什么用包络轮廓而不是"时长 + 亮度"：</b>各音的时长本来就全不一样，
     * 拿时长当判据的话这条断言恒真，等于没写。把每个音切成 16 段、按自身峰值归一，
     * 得到的是"能量在时间上怎么分布" —— 这才是一个音区别于另一个音的东西
     * （一声撞击是"立刻到顶然后迅速没"，换弹是"三下"，受伤是"慢慢退"）。
     *
     * <p>阈值 0.10 是实测出来的：M2.1 最接近的一对（gun_empty 与 hit_enemy）实测差 0.17。
     * 留出约 1.7 倍余量，配方微调不会让它变红，而"两个音被改成一样"一定会被抓住。
     *
     * <p><b>M2.2 唯一的一处豁免是"开 / 关"这一对（{@link #isDirectionPair}）。</b>
     * 理由<b>不是</b>它们"差不多"，而是<b>这条判据对它们的区别是盲的</b>：
     * 按设计，开 / 关共享基频、时长、包络与噪声层，只有滑音方向相反
     * （见 {@code PcmSynth#uiOpen} 的类注释）。包络轮廓看不见方向 ——
     * 实测这一对的包络差只有 0.070，而四种信号域仪器（过零率 / 自相关基频 /
     * 频带能量比 / 带负对照的差分比较）全都测不出这个方向，
     * 失败记录见 {@code docs/testing/M2_2_UI_INVENTORY_REPORT.md}。
     * 因此这一对的可分辨性<b>不由本断言负责</b>，而由人工试听负责
     * （{@code docs/testing/M2_2_PLAYTEST_CHECKLIST.md} 的"UI 音效"一节）。
     *
     * <p><b>为什么不干脆把阈值调低：</b>豁免写成"具名的一对 + 断言被跳过的对数恰好为 1"，
     * 这样它不会变成"UI 音都差不多"的通行证 —— 想再塞一对进来，必须同时改两个地方，
     * 而 diff 上一眼就能看见。把阈值放宽则会静默地放过所有音。
     *
     * <p><b>已知的余量偏紧（M2.2 收尾记录）：</b>豁免之后最接近的一对是
     * ui_close / player_hurt，实测 0.111，只比阈值高 1.11 倍（豁免前是 1.7 倍）。
     * 这是"把开 / 关做成精确镜像"的代价，属于设计取舍而非缺陷；
     * 若将来再调 UI 音的配方，先变红的会是这一对。
     */
    @Test
    void allSoundsHaveDistinctEnvelopeShapes() {
        AudioEvent[] all = AudioEvent.values();
        Map<AudioEvent, double[]> profiles = new EnumMap<>(AudioEvent.class);
        for (AudioEvent event : all) {
            profiles.put(event, envelopeProfile(PcmSynth.render(event, 0), 16));
        }

        double closest = Double.MAX_VALUE;
        String closestPair = "";
        int skipped = 0;
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                if (isDirectionPair(all[i], all[j])) {
                    skipped++;
                    continue;
                }
                double difference = maxBucketDifference(profiles.get(all[i]), profiles.get(all[j]));
                if (difference < closest) {
                    closest = difference;
                    closestPair = all[i].id() + " / " + all[j].id();
                }
                assertTrue(difference > 0.10,
                        all[i].id() + " 与 " + all[j].id() + " 的包络轮廓只差 " + difference
                                + "，听感上会合并");
            }
        }

        // 豁免恰好一对：多出来的一对说明有人把别的音也塞进豁免，而不是去修那个音。
        assertEquals(1, skipped,
                "包络豁免只允许「开 / 关」这一对，实测跳过了 " + skipped + " 对 —— "
                        + "多出来的豁免等于把这条断言变松（阈值一个都没改，但覆盖面少了一对）");

        // 把最接近的一对打印出来：将来配方微调时，先变红的会是这一对，
        // 而报告里引用的"实测最接近"这个数字不该一直是 M2.1 的旧值。
        System.out.printf("[PcmSynth] 最接近的一对（不含方向对）：%s，包络差 %.3f%n", closestPair, closest);
    }

    /**
     * 设计上的"方向对"：两个音刻意共享全部包络参数，只有滑音方向相反。
     *
     * <p>目前只有开 / 关这一对。豁免是具名的，不是按阈值放宽的 —— 见
     * {@link #allSoundsHaveDistinctEnvelopeShapes} 的说明。
     */
    private static boolean isDirectionPair(AudioEvent a, AudioEvent b) {
        return (a == AudioEvent.UI_OPEN && b == AudioEvent.UI_CLOSE)
                || (a == AudioEvent.UI_CLOSE && b == AudioEvent.UI_OPEN);
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

    /**
     * UI 音必须整体比战斗音轻 —— 这是全部约束里唯一一条<b>跨类别</b>的混音约束。
     *
     * <p>背包可以被连续点几十下，而战斗音是稀缺事件（一枪一次、受伤一次）。
     * 若两类同响度，翻背包就会盖住枪声与受伤音 —— 而那三个音才是"我该不该退"的信息源。
     * 一句话：<b>交互音给确认，战斗音给决策；确认不得盖过决策。</b>
     */
    @Test
    void uiSoundsAreQuieterThanCombatSounds() {
        AudioEvent[] combat = {AudioEvent.GUN_FIRE, AudioEvent.GUN_EMPTY, AudioEvent.RELOAD,
                AudioEvent.HIT_ENEMY, AudioEvent.PLAYER_HURT};
        AudioEvent[] ui = {AudioEvent.UI_OPEN, AudioEvent.UI_CLOSE,
                AudioEvent.UI_MOVE, AudioEvent.UI_DENIED};

        float loudestUi = 0f;
        for (AudioEvent e : ui) {
            loudestUi = Math.max(loudestUi, e.baseGain());
        }
        float quietestCombat = 1f;
        for (AudioEvent e : combat) {
            quietestCombat = Math.min(quietestCombat, e.baseGain());
        }
        assertTrue(loudestUi < quietestCombat,
                "UI 音的最大基准增益 " + loudestUi + " 必须低于战斗音的最小值 " + quietestCombat
                        + "（否则连点背包会盖住枪声）");

        // 峰值也不能达到枪声量级：连续点击时总响度会被叠加，这里留出余量
        int gunPeak = PcmSynth.peak(PcmSynth.render(AudioEvent.GUN_FIRE, 0));
        for (AudioEvent e : ui) {
            int peak = PcmSynth.peak(PcmSynth.render(e, 0));
            assertTrue(peak < gunPeak * 0.8,
                    e.id() + " 的峰值 " + peak + " 达到了枪声 " + gunPeak + " 的八成以上");
        }
    }

    /**
     * 开 / 关这一对音必须共享"配对框架"，且不是同一个波形的复制品。
     *
     * <p><b>这条断言只覆盖"一对"里可被机器验证的那一半：</b>同长、同增益、同变体数
     * （这样两音在响度与节奏上不会被听成两个不相干的事件），以及波形确实不同
     * （防止有人把 {@code uiClose} 直接抄成 {@code uiOpen}）。
     *
     * <p><b>它抓不住什么 —— 写清楚，以免它被当成"方向已验证"：</b>
     * 若有人把 {@code uiClose} 的滑音也改成上行（方向信息丢失、但两个音单独听
     * 仍各自"像一个 UI 音"），本断言<span>仍然会通过</span>：两个波形照样不同。
     * 方向是听感属性，本仓库没有可靠的信号域仪器能证明它 —— 四轮尝试（过零率 /
     * 自相关 / 频带能量比 / 带负对照的差分比较）全部失败，最后一轮甚至在两个变体上
     * 给出相反的符号。因此方向由<b>人工试听</b>覆盖：
     * {@code docs/testing/M2_2_PLAYTEST_CHECKLIST.md} 的"UI 音效"一节有专门一条。
     * 把它记在这里，是为了让下一个人先看到"为什么没测"再决定要不要补。
     */
    @Test
    void uiOpenAndUiCloseAreAPair() {
        assertEquals(PcmSynth.durationSeconds(AudioEvent.UI_OPEN),
                PcmSynth.durationSeconds(AudioEvent.UI_CLOSE), 1e-9,
                "开 / 关必须是同一时长，否则听不出是一对");
        assertEquals(AudioEvent.UI_OPEN.baseGain(), AudioEvent.UI_CLOSE.baseGain(), 1e-6f,
                "开 / 关必须是同一基准增益，否则一个盖住另一个");
        assertEquals(AudioEvent.UI_OPEN.variants(), AudioEvent.UI_CLOSE.variants(),
                "开 / 关必须有同样的变体数（轮换听起来才是一对）");

        for (int variant = 0; variant < AudioEvent.UI_OPEN.variants(); variant++) {
            short[] open = PcmSynth.render(AudioEvent.UI_OPEN, variant);
            short[] close = PcmSynth.render(AudioEvent.UI_CLOSE, variant);
            assertFalse(java.util.Arrays.equals(open, close),
                    "第 " + variant + " 个变体上开 / 关的样本完全相同 —— 关背包听起来会和开背包一模一样");
        }
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

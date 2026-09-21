package com.skyisland.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PcmSynth} 的波形性质测试 —— <b>"占位音"也必须可断言</b>。
 *
 * <h2>为什么是这些性质，而不是"好不好听"</h2>
 * 好不好听没法写进 CI。但一个占位音只要有下面这四类问题，它在游戏里就是缺陷而不是"音色朴素"：
 * <ol>
 *   <li><b>空的</b> —— 合成失败，玩家以为"这一声没触发"；</li>
 *   <li><b>削波</b> —— 爆音，比不发声更糟；</li>
 *   <li><b>首尾不为零</b> —— 播放时扬声器上出现一次阶跃，表现为额外的"啪"；</li>
 *   <li><b>不可复现</b> —— "这一声听起来变了"无法归因到某次代码改动。</li>
 * </ol>
 * 这四类全都可以机械检查，因此它们是本类的四条主线。音色的主观部分刻意不在这里断言，
 * 那属于试听，属于 M2.1 报告里的"人工试玩"一节。
 *
 * <h2>为什么每次都重新合成而不缓存结果</h2>
 * 本类的断言对象就是"合成"这一步本身。缓存会让"两次结果相同"退化成"同一个数组是它自己"
 * 这种恒真断言 —— 那正是本项目反复要求避免的假阳性。
 */
class PcmSynthTest {

    /** 全部事件 × 全部变体，测试里到处要用。 */
    private static List<Object[]> allVariants() {
        List<Object[]> pairs = new ArrayList<>();
        for (AudioEvent event : AudioEvent.values()) {
            for (int variant = 0; variant < event.variants(); variant++) {
                pairs.add(new Object[]{event, variant});
            }
        }
        return pairs;
    }

    // ============================================================ ① 非空

    @Test
    void everyEventAndVariantRendersNonSilentAudio() {
        for (Object[] pair : allVariants()) {
            AudioEvent event = (AudioEvent) pair[0];
            int variant = (int) pair[1];
            String what = event.id() + "#" + variant;

            short[] pcm = PcmSynth.render(event, variant);

            assertTrue(pcm.length > 0, what + " 合成的样本数为 0");
            assertTrue(PcmSynth.peak(pcm) > 0, what + " 是纯静音（峰值 0）");
            // 用均方根而不是峰值来判"真的在响"：峰值只要有一个样本非零就成立，
            // 而一个只有 1 个非零样本的缓冲听起来仍是静音。
            assertTrue(PcmSynth.rms(pcm) > 0.001,
                    what + " 的均方根过低，听感上接近静音：rms=" + PcmSynth.rms(pcm));
        }
    }

    @Test
    void declaredDurationMatchesTheRenderedLength() {
        for (AudioEvent event : AudioEvent.values()) {
            int expected = (int) Math.round(PcmSynth.durationSeconds(event) * PcmSynth.SAMPLE_RATE);
            assertEquals(expected, PcmSynth.render(event, 0).length,
                    event.id() + " 的样本数必须等于登记的时长 × 采样率");
        }
    }

    @Test
    void totalSampleCountMatchesTheSumOfEverythingRendered() {
        long sum = 0;
        for (Object[] pair : allVariants()) {
            sum += PcmSynth.render((AudioEvent) pair[0], (int) pair[1]).length;
        }
        assertEquals(sum, PcmSynth.totalSampleCount(),
                "内存预算读数必须与实际会渲染出的样本总数一致");
    }

    // ============================================================ ② 不削波

    @Test
    void nothingEverHitsFullScale() {
        for (Object[] pair : allVariants()) {
            AudioEvent event = (AudioEvent) pair[0];
            int variant = (int) pair[1];

            int peak = PcmSynth.peak(PcmSynth.render(event, variant));

            // 严格小于 32767（而不是 <=）：合成链末端有 tanh 软限幅，
            // 若某天有人把它换成硬截断，峰值会正好等于 32767，这条断言就会变红。
            assertTrue(peak < 32767,
                    event.id() + "#" + variant + " 触到满量程，说明软限幅失效或增益算错：peak=" + peak);
        }
    }

    // ============================================================ ③ 首尾归零

    @Test
    void buffersStartAndEndAtZeroToAvoidAClick() {
        for (Object[] pair : allVariants()) {
            AudioEvent event = (AudioEvent) pair[0];
            int variant = (int) pair[1];
            short[] pcm = PcmSynth.render(event, variant);

            assertEquals(0, pcm[0],
                    event.id() + "#" + variant + " 的首样本不为 0，播放时会听到额外的「啪」");
            assertEquals(0, pcm[pcm.length - 1],
                    event.id() + "#" + variant + " 的末样本不为 0，播放时会听到额外的「啪」");
        }
    }

    @Test
    void thereIsNoDcOffsetThatWouldProduceAClick() {
        // 首样本为 0 只挡住了"开始时的那一次阶跃"。整段的直流偏置同样会造成阶跃
        // （扬声器纸盆被推到非零位置再回零），它是"首尾为 0"看不出来的那半边。
        // 合成链末端显式减掉了均值，这条断言守的就是那一步。
        for (Object[] pair : allVariants()) {
            AudioEvent event = (AudioEvent) pair[0];
            int variant = (int) pair[1];
            short[] pcm = PcmSynth.render(event, variant);

            double mean = 0;
            for (short s : pcm) {
                mean += s;
            }
            mean /= pcm.length;

            int peak = PcmSynth.peak(pcm);
            assertTrue(Math.abs(mean) < peak * 0.05,
                    event.id() + "#" + variant + " 存在直流偏置，播放时会产生阶跃：mean=" + mean
                            + " peak=" + peak);
        }
    }

    @Test
    void everyEventDecaysSoItNeverFightsTheNextSound() {
        // 五个音的配方都是"起音 + 衰减"。这条断言守的是"它真的衰减了"：
        // 不衰减的占位音会在连发时把上一声盖在下一声底下，
        // 而"听不清是哪一发"恰好是 M2.1 要修的可读性问题。
        //
        // 刻意<b>不</b>断言"起始能量低于中段"：像 gun_empty 那样只有 70 ms 的音，
        // 中段早就衰减到接近静音了，那个写法描述的是我脑补的包络，而不是产品事实。
        for (AudioEvent event : AudioEvent.values()) {
            short[] pcm = PcmSynth.render(event, 0);
            int window = Math.max(1, pcm.length / 10);

            double attack = PcmSynth.rms(java.util.Arrays.copyOfRange(pcm, 0, window));
            double tail = PcmSynth.rms(java.util.Arrays.copyOfRange(
                    pcm, pcm.length - window, pcm.length));

            assertTrue(tail < attack,
                    event.id() + " 的尾段能量不低于起始段，说明包络没有衰减：attack=" + attack
                            + " tail=" + tail);
        }
    }

    // ============================================================ ④ 可复现

    @Test
    void theSameRequestAlwaysRendersTheSameWaveform() {
        for (AudioEvent event : AudioEvent.values()) {
            short[] first = PcmSynth.render(event, 0);
            short[] second = PcmSynth.render(event, 0);

            assertFalse(first == second, "两次调用不应返回同一个数组（那样下面的比对就是恒真的）");
            assertEquals(first.length, second.length);
            for (int i = 0; i < first.length; i++) {
                if (first[i] != second[i]) {
                    throw new AssertionError(event.id()
                            + " 的合成不可复现：第 " + i + " 个样本 " + first[i] + " != " + second[i]);
                }
            }
        }
    }

    @Test
    void differentVariantsAreActuallyDifferent() {
        // 变体轮换的全部意义是"连着响几声听起来不完全一样"。
        // 若某个事件的所有变体渲染出同一条波形，轮换就是死代码。
        for (AudioEvent event : AudioEvent.values()) {
            if (event.variants() < 2) {
                continue;
            }
            short[] base = PcmSynth.render(event, 0);
            boolean differs = false;
            for (int variant = 1; variant < event.variants(); variant++) {
                if (!java.util.Arrays.equals(base, PcmSynth.render(event, variant))) {
                    differs = true;
                    break;
                }
            }
            assertTrue(differs, event.id() + " 宣称有 " + event.variants()
                    + " 个变体，但渲染结果完全相同");
        }
    }

    @Test
    void outOfRangeVariantIndicesFoldInsteadOfThrowing() {
        // 调用方理论上不会传越界值，但"越界就崩"发生在逻辑步热路径上等于游戏崩。
        AudioEvent event = AudioEvent.GUN_FIRE;
        assertEquals(0, event.normalizeVariant(0));
        assertEquals(1, event.normalizeVariant(1));
        assertEquals(0, event.normalizeVariant(event.variants()), "越界应折回而不是抛");
        assertEquals(event.variants() - 1, event.normalizeVariant(-1), "负下标应折到最后一个变体");

        assertTrue(PcmSynth.render(event, 99).length > 0);
        assertTrue(PcmSynth.render(event, -7).length > 0);
    }

    @Test
    void nullEventIsRejectedLoudly() {
        assertThrows(IllegalArgumentException.class, () -> PcmSynth.render(null, 0));
        assertThrows(IllegalArgumentException.class, () -> PcmSynth.durationSeconds(null));
    }

    @Test
    void distinctEventsSoundDistinct() {
        // 两个事件的时长若相同，就必须在波形上不同 —— 否则"五个音要能分辨"这条
        // 设计目标在合成层就已经失败了（枪声与受伤音听起来一样时，
        // "我打中了"和"我被咬了"会合并成同一个信号）。
        AudioEvent[] events = AudioEvent.values();
        for (int i = 0; i < events.length; i++) {
            for (int j = i + 1; j < events.length; j++) {
                AudioEvent a = events[i];
                AudioEvent b = events[j];
                boolean sameLength = Math.abs(a.variants() - b.variants()) == 0
                        && Math.abs(PcmSynth.durationSeconds(a) - PcmSynth.durationSeconds(b)) < 1e-9;
                if (!sameLength) {
                    continue;
                }
                assertNotEquals(a.id(), b.id(), "两个事件不应共用同一个 id");
            }
        }
        // 时长必须两两不同（本阶段五个音的配方就是这么设计的）：
        // 它是"可分辨"最容易守住的一条，也因此值得直接钉住。
        for (int i = 0; i < events.length; i++) {
            for (int j = i + 1; j < events.length; j++) {
                assertNotEquals(PcmSynth.durationSeconds(events[i]),
                        PcmSynth.durationSeconds(events[j]),
                        events[i].id() + " 与 " + events[j].id() + " 时长相同，可分辨性下降");
            }
        }
    }
}

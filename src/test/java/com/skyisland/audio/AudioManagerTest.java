package com.skyisland.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AudioManager} 的行为测试 —— <b>全部断言都不依赖声卡</b>。
 *
 * <p><b>这一层为什么必须存在：</b>"开枪时有没有触发 gun_fire"是一件软件可以做主的事，
 * 而"这一声有没有被人听见"取决于运行它的机器上有没有音频设备。
 * 把它们混在一句话里（"我没听到声音"）是排查音频问题最典型的死局。
 * 本测试把可判定的那一半（事件是否被正确触发、音量是否真的进了增益、
 * 后端炸了会不会连累游戏）全部钉死，剩下"到底响没响"这一个问号，
 * 由收尾日志里的 {@code 音频后端} 一行单独回答。
 *
 * <p><b>为什么用注入后端而不是"真的开一次 OpenAL"：</b>真的开一次的话，
 * 测试结果就变成了机器性质的读数（这在有卡和没卡的机器上给出相反的结论），
 * 而同一个测试必须给出同一个结论。见 {@link AudioManager#AudioManager(AudioBackend)}。
 */
class AudioManagerTest {

    /** 记录每一次发声的可观测后端。 */
    private static final class FakeBackend implements AudioBackend {
        final List<AudioEvent> events = new ArrayList<>();
        final List<Integer> variants = new ArrayList<>();
        final List<Float> gains = new ArrayList<>();
        int closeCount;
        boolean failNextPlay;

        @Override
        public boolean open() {
            return true;
        }

        @Override
        public void play(AudioEvent event, int variant, float gain) {
            if (failNextPlay) {
                throw new IllegalStateException("模拟一次 OpenAL 故障");
            }
            events.add(event);
            variants.add(variant);
            gains.add(gain);
        }

        @Override
        public void close() {
            closeCount++;
        }

        @Override
        public String describe() {
            return "fake";
        }
    }

    private String savedAudioProperty;

    @BeforeEach
    void rememberProperty() {
        savedAudioProperty = System.getProperty(AudioManager.PROP_AUDIO);
        // 测试不得依赖"这台机器上恰好有没有声卡"，也不得因为另一条测试留下的属性而变味
        System.clearProperty(AudioManager.PROP_AUDIO);
    }

    @AfterEach
    void restoreProperty() {
        if (savedAudioProperty == null) {
            System.clearProperty(AudioManager.PROP_AUDIO);
        } else {
            System.setProperty(AudioManager.PROP_AUDIO, savedAudioProperty);
        }
    }

    // ============================================================ 事件与播放的分离

    @Test
    void eventsAreRecordedEvenWhenThereIsNoPlaybackBackendAtAll() {
        AudioManager audio = new AudioManager(null);
        RecordingAudioSink probe = new RecordingAudioSink();
        audio.setForwardSink(probe);

        audio.play(AudioEvent.GUN_FIRE);
        audio.play(AudioEvent.HIT_ENEMY);

        assertEquals(1, audio.audit().countOf(AudioEvent.GUN_FIRE));
        assertEquals(1, audio.audit().countOf(AudioEvent.HIT_ENEMY));
        assertEquals(0, audio.audit().countOf(AudioEvent.PLAYER_HURT),
                "没响过的事件也必须是 0，不能是「没记录」这种说不清的状态");
        assertEquals(2, probe.size(), "注入的 sink 必须收到全部事件");
        assertFalse(audio.isPlaying(), "没有后端时 isPlaying 必须是 false");
    }

    @Test
    void playbackReachesTheBackendTogetherWithTheEvent() {
        FakeBackend backend = new FakeBackend();
        AudioManager audio = new AudioManager(backend);

        audio.play(AudioEvent.GUN_FIRE);

        assertEquals(1, backend.events.size());
        assertSame(AudioEvent.GUN_FIRE, backend.events.get(0));
        assertEquals(1, audio.audit().countOf(AudioEvent.GUN_FIRE),
                "同一个事件必须同时进入记录与播放 —— 两者缺一都说明链路断了");
    }

    @Test
    void consecutiveShotsUseDifferentVariants() {
        FakeBackend backend = new FakeBackend();
        AudioManager audio = new AudioManager(backend);

        for (int i = 0; i < AudioEvent.GUN_FIRE.variants(); i++) {
            audio.play(AudioEvent.GUN_FIRE);
        }

        assertEquals(AudioEvent.GUN_FIRE.variants(), backend.variants.size());
        assertEquals(AudioEvent.GUN_FIRE.variants(),
                new java.util.HashSet<>(backend.variants).size(),
                "连发必须轮换变体，否则每一枪听起来完全一样（打字机效应）");
    }

    // ============================================================ 音量滑杆

    @Test
    void volumeSliderChangesTheGainThatReachesTheBackend() {
        FakeBackend backend = new FakeBackend();
        AudioManager audio = new AudioManager(backend);

        audio.setVolumes(100, 100);
        audio.play(AudioEvent.GUN_FIRE);
        float full = backend.gains.get(0);

        audio.setVolumes(50, 100);
        audio.play(AudioEvent.GUN_FIRE);
        float half = backend.gains.get(1);

        audio.setVolumes(0, 100);
        audio.play(AudioEvent.GUN_FIRE);
        float muted = backend.gains.get(2);

        assertEquals(AudioEvent.GUN_FIRE.baseGain(), full, 1e-6f,
                "两个滑杆都在 100 时，增益应等于事件的基准响度");
        assertEquals(full * 0.5f, half, 1e-5f, "主音量减半 → 增益减半");
        assertEquals(0f, muted, 1e-6f, "主音量为 0 时必须真的静音");
    }

    @Test
    void masterAndSfxVolumeMultiplyRatherThanOneOverridingTheOther() {
        FakeBackend backend = new FakeBackend();
        AudioManager audio = new AudioManager(backend);

        audio.setVolumes(50, 50);
        audio.play(AudioEvent.GUN_FIRE);
        float both = backend.gains.get(0);

        audio.setVolumes(100, 25);
        audio.play(AudioEvent.GUN_FIRE);
        float sfxOnly = backend.gains.get(1);

        assertEquals(AudioEvent.GUN_FIRE.baseGain() * 0.25f, both, 1e-5f);
        assertEquals(AudioEvent.GUN_FIRE.baseGain() * 0.25f, sfxOnly, 1e-5f,
                "50×50 与 100×25 必须得到同一个结果 —— 否则两个滑杆不是相乘关系");
    }

    @Test
    void outOfRangeVolumesAreClampedInsteadOfProducingNegativeGain() {
        AudioManager audio = new AudioManager(new FakeBackend());
        audio.setVolumes(-999, 9999);

        assertEquals(0f, audio.effectiveGainFor(AudioEvent.GUN_FIRE), 1e-6f);
        assertEquals(0, audio.masterVolumePercent());
        assertEquals(100, audio.sfxVolumePercent());
    }

    // ============================================================ 降级

    @Test
    void aBackendThatExplodesDoesNotTakeTheGameDownWithIt() {
        FakeBackend backend = new FakeBackend();
        backend.failNextPlay = true;
        AudioManager audio = new AudioManager(backend);

        assertDoesNotThrow(() -> audio.play(AudioEvent.GUN_FIRE),
                "play() 在逻辑步的热路径上，抛出任何东西的表现都是「游戏崩了」而不是「没声音」");

        assertFalse(audio.isPlaying(), "后端炸过一次之后必须标记为不可用");
        assertEquals(1, audio.audit().countOf(AudioEvent.GUN_FIRE),
                "播放失败不该回过头去影响事件的记录 —— 那会让「有没有触发」重新变得不可判定");
        assertEquals(1, backend.closeCount, "失败后必须把后端关掉，不留 native 资源");

        // 之后继续调用仍然安全
        assertDoesNotThrow(() -> audio.play(AudioEvent.HIT_ENEMY));
    }

    @Test
    void aForwardSinkThatThrowsIsDetachedAndPlaybackContinues() {
        FakeBackend backend = new FakeBackend();
        AudioManager audio = new AudioManager(backend);
        audio.setForwardSink(event -> {
            throw new RuntimeException("sink 自己写错了");
        });

        assertDoesNotThrow(() -> audio.play(AudioEvent.GUN_FIRE));
        assertEquals(1, backend.events.size(), "转发 sink 的 bug 不该截住真正的播放");

        // 第二次：已经摘掉了那个 sink，不该再抛
        RecordingAudioSink replacement = new RecordingAudioSink();
        audio.setForwardSink(replacement);
        assertDoesNotThrow(() -> audio.play(AudioEvent.HIT_ENEMY));
        assertEquals(1, replacement.size());
    }

    @Test
    void audioOffPropertySkipsPlaybackButKeepsRecording() {
        System.setProperty(AudioManager.PROP_AUDIO, "off");
        AudioManager audio = new AudioManager();

        assertFalse(audio.isOpened());
        audio.open();

        assertTrue(audio.isOpened(), "open() 必须留下「已经试过」这个事实");
        assertFalse(audio.isPlaying(), "-Dskyisland.audio=off 时不得发声");

        audio.play(AudioEvent.GUN_FIRE);
        assertEquals(1, audio.audit().countOf(AudioEvent.GUN_FIRE),
                "关掉播放不等于关掉事件记录 —— 记录是唯一的诊断依据");
        assertTrue(audio.statusText().contains("off"), audio.statusText());
    }

    @Test
    void nullEventIsIgnoredSilently() {
        AudioManager audio = new AudioManager(new FakeBackend());
        assertDoesNotThrow(() -> audio.play(null));
        assertEquals(0, audio.audit().size());
    }

    // ============================================================ 生命周期

    @Test
    void closeIsIdempotentAndReleasesTheBackendExactlyOnce() {
        FakeBackend backend = new FakeBackend();
        AudioManager audio = new AudioManager(backend);

        audio.close();
        audio.close();

        assertEquals(1, backend.closeCount, "重复 close 不得二次释放同一个 native 资源");
        assertFalse(audio.isPlaying());
    }

    @Test
    void closeWithoutOpenIsHarmless() {
        AudioManager audio = new AudioManager();
        assertDoesNotThrow(audio::close, "收尾路径上 close 可能先于 open 到达");
    }

    @Test
    void openIsOnlyEffectiveOnce() {
        AudioManager audio = new AudioManager();
        System.setProperty(AudioManager.PROP_AUDIO, "off");
        audio.open();
        String first = audio.statusText();
        audio.open();
        assertEquals(first, audio.statusText(), "重复 open 不得重复初始化");
    }

    // ============================================================ 记录器本身

    @Test
    void theAuditSinkKeepsCountsAndSequenceInSync() {
        AudioManager audio = new AudioManager(null);
        audio.play(AudioEvent.GUN_FIRE);
        audio.play(AudioEvent.GUN_EMPTY);
        audio.play(AudioEvent.GUN_FIRE);

        assertEquals(3, audio.audit().size());
        assertEquals(List.of(AudioEvent.GUN_FIRE, AudioEvent.GUN_EMPTY, AudioEvent.GUN_FIRE),
                audio.audit().events(), "事件必须保留顺序：计数看不出次序错乱");
        assertTrue(audio.audit().countsMatchSequence(), "计数表与序列必须始终一致");
        assertTrue(audio.audit().summaryLine().contains("gun_fire=2"), audio.audit().summaryLine());
        assertTrue(audio.audit().summaryLine().contains("player_hurt=0"),
                "没响过的事件也要写出来，否则读日志的人分不清「没接」与「没发生」：" + audio.audit().summaryLine());
    }

    @Test
    void teeForwardsToBothSinksInOrder() {
        List<String> order = new ArrayList<>();
        AudioEventSink combined = AudioEventSink.tee(
                event -> order.add("first:" + event.id()),
                event -> order.add("second:" + event.id()));

        combined.accept(AudioEvent.RELOAD);
        assertEquals(List.of("first:reload", "second:reload"), order);

        assertNotNull(AudioEventSink.tee(null, null), "两个都为空时必须退化成 DISCARD 而不是 NPE");
        assertDoesNotThrow(() -> AudioEventSink.DISCARD.accept(AudioEvent.RELOAD));
    }

    @Test
    void wrapRejectsNullManager() {
        assertThrows(IllegalArgumentException.class, () -> AudioFeedback.wrap(null));
    }
}

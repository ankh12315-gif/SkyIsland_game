package com.skyisland.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AudioManager} 的降级行为与音量语义测试。
 *
 * <h2>为什么这些断言全部在"没有播放后端"的前提下跑</h2>
 * 单测必须能在无头 CI 上跑，因此本类一律用 {@code open(false)}（或显式关掉播放的系统属性），
 * <b>绝不真的去开音频设备</b>。这不是回避 —— 恰恰是这套设计最该被验证的那一面：
 * <b>没有声卡时，事件记录、音量计算、生命周期必须完全照常工作</b>。
 * 真实设备上的播放链路由门禁运行（{@code realOpen()} 那条路径）与日志中的
 * OpenAL 状态行举证，两者分工明确。
 *
 * <h2>为什么"关掉播放仍记录事件"要单独成条</h2>
 * 无声环境下"没听到声音"与"事件压根没触发"在观测上一模一样。把这件事钉死之后，
 * 门禁日志里的 {@code gun_fire=12} 才是一个可以据以归因的事实。
 */
class AudioManagerTest {

    /** 一个已经"打开但关掉播放"的实例：无头环境下的标准测试夹具。 */
    private static AudioManager openedWithoutPlayback() {
        AudioManager audio = new AudioManager();
        audio.open(false);
        return audio;
    }

    // ============================================================ 降级

    @Test
    void openingWithoutPlaybackStillRecordsEveryEvent() {
        AudioManager audio = openedWithoutPlayback();

        assertTrue(audio.isOpened(), "open(false) 之后必须处于已打开状态");
        assertFalse(audio.isPlaying(), "open(false) 不得进入播放态");

        audio.play(AudioEvent.GUN_FIRE);
        audio.play(AudioEvent.GUN_FIRE);
        audio.play(AudioEvent.HIT_ENEMY);

        assertEquals(2, audio.audit().countOf(AudioEvent.GUN_FIRE),
                "没有播放后端时事件仍必须被完整记录");
        assertEquals(1, audio.audit().countOf(AudioEvent.HIT_ENEMY));
        assertEquals(3, audio.audit().size());
    }

    @Test
    void theOffPropertyDisablesPlaybackButKeepsRecording() {
        String saved = System.getProperty(AudioManager.PROP_AUDIO);
        try {
            System.setProperty(AudioManager.PROP_AUDIO, "off");
            AudioManager audio = new AudioManager();
            // 走不带参数的 open()：它必须自己去读系统属性并跳过播放侧
            audio.open();

            assertTrue(audio.isOpened());
            assertFalse(audio.isPlaying(), "-Dskyisland.audio=off 不得打开播放侧");
            assertTrue(audio.statusText().contains("off"),
                    "状态行必须说明是被属性关掉的，而不是留下一个含糊的『未初始化』："
                            + audio.statusText());

            audio.play(AudioEvent.RELOAD);
            assertEquals(1, audio.audit().countOf(AudioEvent.RELOAD),
                    "播放被关掉，事件记录不受影响");
        } finally {
            if (saved == null) {
                System.clearProperty(AudioManager.PROP_AUDIO);
            } else {
                System.setProperty(AudioManager.PROP_AUDIO, saved);
            }
        }
    }

    @Test
    void playNeverThrowsAndIgnoresNull() {
        AudioManager audio = openedWithoutPlayback();

        audio.play(null);   // 不得抛

        assertEquals(0, audio.audit().size(), "null 事件不得被记进审计队列");
    }

    @Test
    void closeIsIdempotentAndSafeBeforeOpen() {
        AudioManager fresh = new AudioManager();
        fresh.close();      // 从未 open 过就 close：不得抛
        fresh.close();

        AudioManager audio = openedWithoutPlayback();
        audio.close();
        audio.close();      // 重复 close：不得抛

        assertFalse(audio.isPlaying());
    }

    @Test
    void aThrowingForwardSinkIsIsolatedAndThenDropped() {
        AudioManager audio = openedWithoutPlayback();
        List<AudioEvent> received = new ArrayList<>();

        audio.setForwardSink(event -> {
            throw new IllegalStateException("注入的转发目标故意抛异常");
        });
        audio.play(AudioEvent.GUN_FIRE);

        // ① 转发目标抛异常不得让 play 抛；② 事件仍必须进审计队列
        assertEquals(1, audio.audit().countOf(AudioEvent.GUN_FIRE),
                "转发目标抛异常不得影响审计记录");

        // 抛过一次之后，转发目标应被替换为 DISCARD：再播一声不得再触发那个坏目标
        audio.setForwardSink(received::add);
        audio.play(AudioEvent.RELOAD);
        assertEquals(List.of(AudioEvent.RELOAD), received,
                "重新注入之后应当只收到新事件（说明旧的坏目标已被丢弃）");
    }

    // ============================================================ 音量

    @Test
    void masterAndSfxVolumesMultiplyRatherThanOverride() {
        AudioManager audio = openedWithoutPlayback();
        float base = AudioEvent.GUN_FIRE.baseGain();

        audio.setVolumes(100, 100);
        assertEquals(base, audio.effectiveGainFor(AudioEvent.GUN_FIRE), 1e-6,
                "两个滑杆都拉满时，实际增益就是事件基准响度");

        audio.setVolumes(50, 50);
        assertEquals(base * 0.25f, audio.effectiveGainFor(AudioEvent.GUN_FIRE), 1e-6,
                "总闸与分项必须相乘（各 50% → 25%），取其一会让『总闸拉到 0 还能出声』");
    }

    @Test
    void masterVolumeZeroSilencesEverythingEvenWithSfxAtFull() {
        AudioManager audio = openedWithoutPlayback();
        audio.setVolumes(0, 100);

        for (AudioEvent event : AudioEvent.values()) {
            assertEquals(0f, audio.effectiveGainFor(event), 1e-9,
                    "总闸为 0 时 " + event.id() + " 必须彻底静音");
        }
    }

    @Test
    void volumesAreClampedInsteadOfOverflowing() {
        AudioManager audio = openedWithoutPlayback();

        audio.setVolumes(-40, 999);
        assertEquals(0, audio.masterVolumePercent(), "负值必须夹到 0");
        assertEquals(100, audio.sfxVolumePercent(), "超过上限必须夹到 100");

        audio.setVolumes(65, 45);
        assertEquals(65, audio.masterVolumePercent());
        assertEquals(45, audio.sfxVolumePercent());
    }

    @Test
    void effectiveGainForNullIsZero() {
        AudioManager audio = openedWithoutPlayback();
        assertEquals(0f, audio.effectiveGainFor(null), 1e-9);
    }

    // ============================================================ 观测

    @Test
    void statusTextIsAlwaysNonEmptySoTheLogCanExplainItself() {
        AudioManager fresh = new AudioManager();
        assertFalse(fresh.statusText().isBlank(), "尚未初始化时也必须有可读的状态");

        AudioManager audio = openedWithoutPlayback();
        assertFalse(audio.statusText().isBlank(), "打开之后状态行不得为空");
    }

    @Test
    void settingTheForwardSinkToNullMeansDiscard() {
        AudioManager audio = openedWithoutPlayback();
        audio.setForwardSink(null);
        // 只是不得抛，并且事件仍进审计
        audio.play(AudioEvent.PLAYER_HURT);
        assertEquals(1, audio.audit().countOf(AudioEvent.PLAYER_HURT));
    }
}

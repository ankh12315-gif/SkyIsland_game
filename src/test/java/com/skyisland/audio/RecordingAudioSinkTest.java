package com.skyisland.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecordingAudioSink} 的测试。
 *
 * <h2>为什么这个"很简单的记录器"值得有自己的测试</h2>
 * 它是<b>无声环境下唯一的证据源</b>：门禁日志里那句
 * {@code 总计 37 次（gun_fire=12, gun_empty=3, ...）}就是它产出的，
 * 而验收人据此判断"触发链是通的"。一个会算错数的记录器不会让任何功能失效，
 * 却会让所有基于它的结论失真 —— 这类"仪器本身有问题"的失败在本项目里已经出现过，
 * 因此仪器必须先被验证。
 */
class RecordingAudioSinkTest {

    @Test
    void countsAndSequenceStayConsistent() {
        RecordingAudioSink sink = new RecordingAudioSink();
        sink.accept(AudioEvent.GUN_FIRE);
        sink.accept(AudioEvent.HIT_ENEMY);
        sink.accept(AudioEvent.GUN_FIRE);

        assertEquals(3, sink.size());
        assertEquals(2, sink.countOf(AudioEvent.GUN_FIRE));
        assertEquals(1, sink.countOf(AudioEvent.HIT_ENEMY));
        assertEquals(0, sink.countOf(AudioEvent.RELOAD), "没收到过的事件计数必须是 0");
        assertTrue(sink.countsMatchSequence(),
                "计数表与事件序列必须一致（只改一边是这类记录器最常见的内伤）");
    }

    @Test
    void orderIsPreservedNotJustCounts() {
        RecordingAudioSink sink = new RecordingAudioSink();
        // 真实的射击序列：开火 → 命中 → 开火 → 命中 → 打空
        sink.accept(AudioEvent.GUN_FIRE);
        sink.accept(AudioEvent.HIT_ENEMY);
        sink.accept(AudioEvent.GUN_FIRE);
        sink.accept(AudioEvent.HIT_ENEMY);
        sink.accept(AudioEvent.GUN_EMPTY);

        assertEquals(List.of(
                        AudioEvent.GUN_FIRE, AudioEvent.HIT_ENEMY, AudioEvent.GUN_FIRE,
                        AudioEvent.HIT_ENEMY, AudioEvent.GUN_EMPTY),
                sink.events(),
                "顺序本身是证据：计数相同而次序错乱（先空仓后开火）是一种计数看不出来的缺陷");
    }

    @Test
    void nullIsIgnoredRatherThanRecorded() {
        RecordingAudioSink sink = new RecordingAudioSink();
        sink.accept(null);

        assertEquals(0, sink.size(), "null 不得被记成一条事件");
        assertEquals(0, sink.countOf(null), "查 null 必须返回 0 而不是抛");
    }

    @Test
    void clearResetsBothTheSequenceAndTheCounts() {
        RecordingAudioSink sink = new RecordingAudioSink();
        sink.accept(AudioEvent.GUN_FIRE);
        sink.clear();

        assertEquals(0, sink.size());
        assertEquals(0, sink.countOf(AudioEvent.GUN_FIRE));
        assertFalse(sink.everHeard(AudioEvent.GUN_FIRE));
        assertTrue(sink.countsMatchSequence(), "清空之后两条结构仍必须一致");
    }

    @Test
    void summaryLineListsEveryEventIncludingTheSilentOnes() {
        RecordingAudioSink sink = new RecordingAudioSink();
        sink.accept(AudioEvent.GUN_FIRE);

        String line = sink.summaryLine();

        assertTrue(line.contains("总计 1 次"), "汇总必须以总数开头： " + line);
        // ★ 把 0 也写出来，读者才分得清"这一类没接"与"这一类恰好没发生"。
        //   少了任何一个 id，那句 0 就变成了"没提到"。
        for (AudioEvent event : AudioEvent.values()) {
            assertTrue(line.contains(event.id() + "="),
                    "汇总必须列出每一个事件 id（含计数为 0 的）：缺 " + event.id() + " → " + line);
        }
        assertTrue(line.contains("gun_fire=1"), line);
        assertTrue(line.contains("player_hurt=0"), "没发生过的事件必须以 =0 出现：" + line);
    }

    @Test
    void theReturnedCollectionsAreCopiesSoCallersCannotCorruptTheRecord() {
        RecordingAudioSink sink = new RecordingAudioSink();
        sink.accept(AudioEvent.RELOAD);

        List<AudioEvent> copy = sink.events();
        // List.copyOf 返回不可变副本；试图改它会抛，因此这里只断言它不是同一个内部列表
        assertFalse(copy == sink.events(), "每次调用都应返回副本而不是内部列表本身");
        assertEquals(1, sink.size(), "外部拿到副本之后记录本身不受影响");
    }

    @Test
    void idSequenceIsAnUppercaseArrowJoinedChain() {
        RecordingAudioSink sink = new RecordingAudioSink();
        sink.accept(AudioEvent.GUN_FIRE);
        sink.accept(AudioEvent.GUN_EMPTY);

        assertEquals("GUN_FIRE>GUN_EMPTY", sink.idSequence(),
                "id 序列用于与另一侧记录直接比对，格式必须稳定");
    }
}

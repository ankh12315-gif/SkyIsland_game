package com.skyisland.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把收到的音频事件逐条记下来 —— 测试与自测的断言源，也是收尾日志的数据源。
 *
 * <p><b>为什么放在 main 而不是 test：</b>记录器有两种用途。一是单测，二是运行期的
 * 观测：每次会话结束时 {@link AudioManager#close()} 要打印"本次会话各类事件各响了
 * 几次"。后者若依赖 test 源码里的类就永远用不上，而它恰恰是"无声环境里唯一的证据"
 * —— 门禁日志里写着 {@code gun_fire=12}，就证明触发链是通的，哪怕一位 arguing 的
 * 验收人听不到任何声音。
 *
 * <p><b>为什么保留顺序而不是只存计数：</b>计数能证明"响过 12 次"，
 * 顺序还能证明"它是按 <i>开火 → 命中 → 开火 → 空仓</i> 这个次序响的"。
 * 事件次序错乱（例如先播了空仓才播开火）是一种计数看不出来的缺陷。
 */
public final class RecordingAudioSink implements AudioEventSink {

    private final List<AudioEvent> events = new ArrayList<>();
    private final Map<AudioEvent, Integer> counts = new EnumMap<>(AudioEvent.class);

    @Override
    public synchronized void accept(AudioEvent event) {
        if (event == null) {
            return;
        }
        events.add(event);
        counts.merge(event, 1, Integer::sum);
    }

    /** 按时间顺序的事件序列（副本）。 */
    public synchronized List<AudioEvent> events() {
        return List.copyOf(events);
    }

    /** 某个事件被触发的次数。 */
    public synchronized int countOf(AudioEvent event) {
        return event == null ? 0 : counts.getOrDefault(event, 0);
    }

    /** 全部事件的总数。 */
    public synchronized int size() {
        return events.size();
    }

    /** 清空记录（同一段配置要跑多种场景时用它隔离）。 */
    public synchronized void clear() {
        events.clear();
        counts.clear();
    }

    /** 是否曾收到某个事件。 */
    public boolean everHeard(AudioEvent event) {
        return countOf(event) > 0;
    }

    /**
     * 一行式汇总，供日志与报告摘录。
     *
     * <p><b>为什么把没响过的事件也列出来：</b>只打印响过的，读者无法区分
     * "这一类事件没接"与"这一类事件恰好没发生"。把 0 也写出来，
     * "(...player_hurt=0)"就是一个可以立刻追问的事实。
     */
    public synchronized String summaryLine() {
        StringBuilder sb = new StringBuilder();
        for (AudioEvent e : AudioEvent.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.id()).append('=').append(counts.getOrDefault(e, 0));
        }
        sb.insert(0, "总计 " + events.size() + " 次（");
        sb.append(')');
        return sb.toString();
    }

    /** 不变式检查用：计数表与事件序列必须始终一致（防止将来有人只改一边）。 */
    synchronized boolean countsMatchSequence() {
        Map<AudioEvent, Integer> recount = new EnumMap<>(AudioEvent.class);
        for (AudioEvent e : events) {
            recount.merge(e, 1, Integer::sum);
        }
        return recount.equals(counts);
    }

    /** 与 {@link #events()} 同步unmodifiable视图（避免调用方每帧复制一次列表）。 */
    public synchronized List<AudioEvent> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    /** 时间戳式的id序列，便于与另一侧的记录直接比对（例如断言两份记录完全一致）。 */
    public synchronized String idSequence() {
        StringBuilder sb = new StringBuilder();
        for (AudioEvent e : events) {
            if (sb.length() > 0) {
                sb.append('>');
            }
            sb.append(e.id().toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }
}

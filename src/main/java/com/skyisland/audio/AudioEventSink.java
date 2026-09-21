package com.skyisland.audio;

/**
 * 音频事件的<b>接收端</b>（可注入 seam）。
 *
 * <p><b>为什么必须有这一层：这是本阶段唯一能被单测举证的那一环。</b>
 * 音效能否被人听见取决于机器上有无声卡、驱动是否正常、自动化门禁是否跑在
 * 无音频设备的容器里 —— 这些都是<b>不可断言的环境</b>。但"开火时有没有
 * 真的触发了 gun_fire 这个事件"是软件的决定，它必须在任何环境下都可断言。
 *
 * <p>因此音频链被拆成两半：
 * <ul>
 *   <li><b>事件侧（本接口）</b>：确定性、可记录、可在无声环境里断言；</li>
 *   <li><b>播放侧（{@link AudioBackend}）</b>：OpenAL 的设备/上下文/缓冲/源，
 *       允许在任何时候缺席。</li>
 * </ul>
 * {@link AudioManager#play(AudioEvent)} 无条件写入事件侧，再"尽力"交给播放侧。
 * 于是"没听清声音"与"事件根本没触发"在证据上不再长得一样 ——
 * 后者会直接让单测变红。
 */
@FunctionalInterface
public interface AudioEventSink {

    /** 收到一个音频事件。实现必须是非阻塞的（它在逻辑步的热路径上被调用）。 */
    void accept(AudioEvent event);

    /** 丢弃一切（默认）：什么都不播放时占住这个位置，避免到处判 null。 */
    AudioEventSink DISCARD = event -> {
    };

    /**
     * 串联两个 sink：先 {@code first} 后 {@code second}。
     *
     * <p><b>为什么要串联而不是替换：</b>自测需要"产品自己的通道保持通畅"的同时
     * 抄送一份给自己作记录。若采用替换，自测开启期间产品侧的通路就被切断了 ——
     * 那么自测证明的只是"自测自己收到了事件"。
     */
    static AudioEventSink tee(AudioEventSink first, AudioEventSink second) {
        if (first == null) {
            return second == null ? DISCARD : second;
        }
        if (second == null) {
            return first;
        }
        return event -> {
            first.accept(event);
            second.accept(event);
        };
    }
}

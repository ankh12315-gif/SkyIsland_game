package com.skyisland.audio;

/**
 * 播放侧抽象：一个"能把 {@link AudioEvent} 变成声音"的东西。
 *
 * <p><b>这一层存在的全部理由，是让 OpenAL 可以整体缺席。</b>
 * {@link AudioManager} 只依赖本接口；真正的 OpenAL 实现
 * （{@code OpenAlBackend}）是全工程唯一 import {@code org.lwjgl.openal} 的类。
 * 于是"开放式音频库没链接上 / 没有声卡 / 驱动拒绝开设备"这三种失败，
 * 在调用点看来都是同一种结果 —— 构造失败或 {@link #open()} 返回 false ——
 * 而不是一段藏在 <i>使用的某一行</i> 里的 UnsatisfiedLinkError。
 *
 * <p><b>为什么三条方法都不允许抛异常：</b>它们每一行都在逻辑步或收尾路径上被调用。
 * 这里抛出哪怕一个 NPE，表现都不是"没声音"，而是"游戏崩了"或"收尾卡住"，
 * 而后者会直接把自动化门禁变成红色。实现方必须自己在内部把错误吃掉并降级。
 */
public interface AudioBackend {

    /**
     * 建立设备与上下文，并上传全部音效。
     *
     * @return true 表示可用；false 表示"已经体面地放弃了"，调用方应照常运行
     */
    boolean open();

    /**
     * 播一声。
     *
     * @param event   事件
     * @param variant 变体下标（调用方保证已归一化到合法区间）
     * @param gain    最终增益（0..1，已经把玩家音量与事件基准响度相乘）
     */
    void play(AudioEvent event, int variant, float gain);

    /** 释放设备与上下文。必须可重复调用（幂等）。 */
    void close();

    /** 一行式状态说明（进日志，便于远程排查"为什么没声音"）。 */
    String describe();
}

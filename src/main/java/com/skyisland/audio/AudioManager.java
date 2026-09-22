package com.skyisland.audio;

import com.skyisland.util.Log;

import java.util.Locale;

/**
 * 音频门面：游戏代码唯一需要认识的那一个音频类。
 *
 * <h2>它做的事只有三件</h2>
 * <ol>
 *   <li><b>收到一个 {@link AudioEvent} 就无条件写进事件 sink</b> —— 无论有没有声卡；</li>
 *   <li><b>尽力</b>把同一个事件交给播放侧 {@link AudioBackend}；</li>
 *   <li>管理生命周期（{@link #open()} / {@link #close()}）与音量。</li>
 * </ol>
 *
 * <h2>降级是设计里的头等公民，不是异常爱好者的退路</h2>
 * 自动化运行（{@code -Dskyisland.selfTest=true} / {@code uiSelfTest} /
 * {@code combatSelfTest}）是无头的，跑它的机器<b>可能根本没有音频设备</b>。
 * 因此本类对"没有 OpenAL / 没有设备 / 设备被占用 / 原生库没链接"这四种情形
 * 的处理是完全一样的：记一条日志，把 {@link #isPlaying()} 置为 false，
 * 然后继续像一个正常的、安静的对象那样工作。
 *
 * <p>具体到实现上有三条硬约定：
 * <ul>
 *   <li><b>不重试、不等待。</b>{@link #open()} 只尝试一次；失败就是失败。
 *       任何"等一会儿再试"或"同步等待设备就绪"都会让无设备环境下的启动
 *       变慢或直接挂住 —— 而挂住会让门禁超时，那是比"没有声音"严重得多的失败；</li>
 *   <li><b>catch 的是 {@code Throwable} 而不是 {@code Exception}。</b>
 *       原生库没链接上来时抛的是 {@code UnsatisfiedLinkError}，它属于 {@code Error}；
 *       只写 {@code catch (Exception)} 的话，那条降级分支永远走不到，
 *       看上去像是"考虑得很周全"，实际上什么都没做；</li>
 *   <li><b>{@link #play(AudioEvent)} 永远不抛。</b>它在逻辑步的热路径上被调用，
 *       这里抛出任何一个 NPE 的表现都不是"这一声没了"，而是"游戏崩了"。</li>
 * </ul>
 *
 * <h2>为什么事件的接收 sink 要独立于播放后端</h2>
 * 后端缺席时若什么都不做，"没听到声音"和"事件压根没触发"在日志上完全一样，
 * 排查只能靠加 println。让 {@code play} 无条件写 {@link RecordingAudioSink}，
 * 就把这两件事彻底拆开了：门禁日志里的 {@code gun_fire=12} 证明触发链是通的，
 * 而 {@code player_hurt=0} 是一个可以立刻追问的事实。
 */
public final class AudioManager {

    /**
     * 系统属性 {@code skyisland.audio}：
     * {@code auto}（默认，尝试初始化）/ {@code on}（同 auto）/ {@code off}（强制不初始化）。
     *
     * <p><b>为什么需要一个"主动关掉"的开关：</b>排查"到底有没有播放"、
     * 或者在一台音频驱动会把整个进程拖慢的机器上跑性能测试时，
     * 都需要一个不改动代码就能把播放侧摘掉的手段。它是给排障用的，
     * 不是给玩家用的。
     */
    public static final String PROP_AUDIO = "skyisland.audio";

    /** 音量百分制的上限（与 {@code GameSettings.MAX_VOLUME} 一致，独立定义以免音频层依赖设置层）。 */
    public static final int MAX_VOLUME_PERCENT = 100;

    /** 永远在线的记录 sink：无论播放后端在不在，它都在记。 */
    private final RecordingAudioSink audit = new RecordingAudioSink();

    /** 转发 sink：外部（自测、HUD 计数）可以注入。 */
    private volatile AudioEventSink forwardSink = AudioEventSink.DISCARD;

    private AudioBackend backend;
    private boolean opened;
    private boolean playing;
    private float masterGain = 1.0f;
    private float sfxGain = 0.8f;
    private String status = "尚未初始化";

    // ============================================================ 生命周期

    /**
     * 尝试初始化（是否被 {@code -Dskyisland.audio=off} 关掉由本方法自己判断）。
     *
     * <p>多数调用点只需要这一种形式；需要显式控制是否尝试时请用
     * {@link #open(boolean)}。
     */
    public void open() {
        open(!isDisabledByProperty());
    }

    /**
     * 初始化播放侧。
     *
     * @param allowPlayback false 时连试都不试（等价于 {@code -Dskyisland.audio=off}）
     */
    public void open(boolean allowPlayback) {
        if (opened) {
            return;
        }
        opened = true;
        if (!allowPlayback) {
            playing = false;
            status = "已按 -D" + PROP_AUDIO + "=off 关闭播放（事件仍会记录）";
            Log.info("[音频] %s", status);
            return;
        }
        AudioBackend candidate = null;
        try {
            // ★ 这一行是整个级联降级的开关：它可能在"原生库没链接"这一步就炸，
            //   而抛出来的是 Error 而不是 Exception —— 所以下面抓的是 Throwable。
            candidate = new OpenAlBackend();
            if (candidate.open()) {
                backend = candidate;
                playing = true;
                status = candidate.describe();
                Log.info("[音频] %s", status);
                return;
            }
            // open() 返回 false：后端自己已经把它们失败的原因写进 describe() 了
            status = candidate.describe();
            candidate.close();
        } catch (Throwable t) {
            status = "OpenAL 不可用：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " — " + t.getMessage());
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (Throwable ignored) {
                    // 已经在一个降级路径上了，这里再抛只会盖掉真正的原因
                }
            }
        }
        backend = null;
        playing = false;
        // ★ 这条 WARN 是刻意留在这里的：无设备不是故障，但必须可见 ——
        //   否则"为什么没有声音"这个问题要重新查一遍才知道是环境问题。
        Log.warn("[音频] 未启用播放：%s。游戏继续运行，音效事件照常记录。"
                + "（这是 M2.1 的设计行为，不是故障）", status);
    }

    /**
     * 释放播放侧。
     *
     * <p><b>幂等：</b>{@code main} 的 {@code finally}、 {@code shutdown()}、
     * 以及"启动到一半失败"这三条路径都可能到达这里，因此本方法必须能被重复调用，
     * 第二次调用不产生任何日志与 native 调用。
     */
    public void close() {
        if (opened) {
            Log.info("[音频] 会话事件计数：%s", audit.summaryLine());
        }
        AudioBackend victim = backend;
        backend = null;
        playing = false;
        if (victim != null) {
            try {
                victim.close();
                Log.info("[音频] OpenAL device / context 已释放（无残留 native 资源）");
            } catch (Throwable t) {
                Log.warn("[音频] 释放过程中出错（不阻塞收尾）：%s", t.getMessage());
            }
        } else if (opened) {
            Log.info("[音频] 播放侧本就未启用，无需释放。");
        }
        status = "已释放";
    }

    // ============================================================ 播放

    /**
     * 触发一个音效。
     *
     * <p><b>本方法不抛任何异常、不做任何 IO、不等待任何东西。</b>
     * 它在每个逻辑步都可能被调用若干次，因此实现里所有可以被跳过的工作
     * （比如"后端根本不存在"）都在第一行就跳过了。
     */
    public void play(AudioEvent event) {
        if (event == null) {
            return;
        }
        int variant = event.nextVariant();
        audit.accept(event);
        try {
            forwardSink.accept(event);
        } catch (Throwable t) {
            // 转发目标的选择权在调用方手上，它的 bug 不该让整个游戏停下来
            Log.warn("[音频] 转发 sink 抛异常（已忽略）：%s", t.getMessage());
            forwardSink = AudioEventSink.DISCARD;
        }
        AudioBackend target = backend;
        if (target == null) {
            return;
        }
        try {
            target.play(event, variant, masterGain * sfxGain * event.baseGain());
        } catch (Throwable t) {
            // ★ 播放出错后本会话就不再尝试发声，但事件仍然继续被记录 ——
            //   于是日志能区分"曾经能响、后来坏了"与"从来没响过"，
            //   而这两种情形在"我什么都听不到"这句话里是完全一样的。
            Log.warn("[音频] 播放失败，本次会话的后续发声将跳过：%s", t.getMessage());
            AudioBackend victim = backend;
            backend = null;
            playing = false;
            status = "播放中断：" + t.getMessage();
            try {
                if (victim != null) {
                    victim.close();
                }
            } catch (Throwable ignored) {
                // 已经在降级路径上了
            }
        }
    }

    // ============================================================ 音量

    /**
     * 施加玩家的音量设置。
     *
     * <p><b>为什么主音量与音效音量是相乘而不是取其一：</b>两个滑杆语义上是
     * "总闸"与"分项"，相乘才是这个隐喻的准确实现。取其一的话，
     * 把总闸拉到 0 之后单独调音效还能出声 —— 那会让人怀疑总闸是不是坏了。
     */
    public void setVolumes(int masterPercent, int sfxPercent) {
        masterGain = normalized(masterPercent);
        sfxGain = normalized(sfxPercent);
    }

    /** 当前实际生效的总增益（0..1），含事件基准响度。仅供诊断。 */
    public float effectiveGainFor(AudioEvent event) {
        return event == null ? 0f : masterGain * sfxGain * event.baseGain();
    }

    public int masterVolumePercent() {
        return Math.round(masterGain * MAX_VOLUME_PERCENT);
    }

    public int sfxVolumePercent() {
        return Math.round(sfxGain * MAX_VOLUME_PERCENT);
    }

    // ============================================================ 观测

    /** 本次会话的记录 sink（自测与收尾日志都读它）。 */
    public RecordingAudioSink audit() {
        return audit;
    }

    /** 注入一个额外的事件接收方（已有的会被替换）。传 null 表示取消注入。 */
    public void setForwardSink(AudioEventSink sink) {
        forwardSink = sink == null ? AudioEventSink.DISCARD : sink;
    }

    /** 是否真的在发声（有后端且没坏）。 */
    public boolean isPlaying() {
        return playing;
    }

    /** 是否已经走过 {@link #open()}（无论成功与否）。 */
    public boolean isOpened() {
        return opened;
    }

    /** 一行式状态说明：进设置应用的那条日志，也进收尾摘要。 */
    public String statusText() {
        return status;
    }

    /**
     * 产品构造器：<b>不带后端</b>。
     *
     * <p><b>为什么必须显式写出来（不能靠隐式默认构造器）：</b>
     * 下面那个 {@code AudioManager(AudioBackend)} 一旦存在，Java 就<u>不再</u>生成
     * 隐式无参构造器 —— 于是 {@code new AudioManager()} 会编译失败，
     * 而报错信息（"需要 AudioBackend，找到：没有参数"）读起来像是调用方写错了，
     * 真正的成因却在一个看起来毫不相关的"加了个测试构造器"的改动里。
     * 显式写出来之后，这条依赖变成可见的：谁删了它，产品侧立刻编译失败而不是静默换语义。
     */
    public AudioManager() {
        this.backend = null;
        this.opened = false;
        this.playing = false;
        this.status = "尚未初始化";
    }

    // ============================================================ 测试专用

    /**
     * 注入一个固定的后端（仅供同包测试使用）。
     *
     * <p><b>为什么需要它：</b>{@link #open()} 的真实后果取决于运行它的机器上
     * 有没有声卡 —— 那是环境的性质，不是被测行为的性质。要断言
     * "音量滑杆真的改变了增益"，就必须能在<b>任何</b>机器上拿到一个可观测的后端。
     * 本构造器不改任何产品语义，只是把"后端是怎么来的"这一步跳过。
     */
    AudioManager(AudioBackend fixedBackend) {
        this.backend = fixedBackend;
        this.opened = true;
        this.playing = fixedBackend != null;
        this.status = fixedBackend == null ? "（测试）无后端" : "（测试）注入后端";
    }

    // ============================================================ 内部

    private static boolean isDisabledByProperty() {
        String raw = System.getProperty(PROP_AUDIO);
        if (raw == null) {
            return false;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.equals("off") || value.equals("false") || value.equals("0") || value.equals("no");
    }

    private static float normalized(int percent) {
        if (percent <= 0) {
            return 0f;
        }
        if (percent >= MAX_VOLUME_PERCENT) {
            return 1f;
        }
        return percent / (float) MAX_VOLUME_PERCENT;
    }
}

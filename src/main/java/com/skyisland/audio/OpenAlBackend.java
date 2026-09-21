package com.skyisland.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.openal.AL10.AL_BUFFER;
import static org.lwjgl.openal.AL10.AL_FALSE;
import static org.lwjgl.openal.AL10.AL_FORMAT_MONO16;
import static org.lwjgl.openal.AL10.AL_GAIN;
import static org.lwjgl.openal.AL10.AL_LOOPING;
import static org.lwjgl.openal.AL10.AL_NO_ERROR;
import static org.lwjgl.openal.AL10.AL_PLAYING;
import static org.lwjgl.openal.AL10.AL_SOURCE_STATE;
import static org.lwjgl.openal.AL10.alBufferData;
import static org.lwjgl.openal.AL10.alDeleteBuffers;
import static org.lwjgl.openal.AL10.alDeleteSources;
import static org.lwjgl.openal.AL10.alGenBuffers;
import static org.lwjgl.openal.AL10.alGenSources;
import static org.lwjgl.openal.AL10.alGetError;
import static org.lwjgl.openal.AL10.alGetSourcei;
import static org.lwjgl.openal.AL10.alSourcePlay;
import static org.lwjgl.openal.AL10.alSourceStop;
import static org.lwjgl.openal.AL10.alSourcef;
import static org.lwjgl.openal.AL10.alSourcei;
import static org.lwjgl.openal.ALC10.ALC_DEFAULT_DEVICE_SPECIFIER;
import static org.lwjgl.openal.ALC10.alcCloseDevice;
import static org.lwjgl.openal.ALC10.alcCreateContext;
import static org.lwjgl.openal.ALC10.alcDestroyContext;
import static org.lwjgl.openal.ALC10.alcGetError;
import static org.lwjgl.openal.ALC10.alcGetString;
import static org.lwjgl.openal.ALC10.alcMakeContextCurrent;
import static org.lwjgl.openal.ALC10.alcOpenDevice;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * 全工程唯一接触 OpenAL 的类：<b>device → context → buffer / source</b> 这条最小链路。
 *
 * <h2>为什么把它单独隔离成一个类</h2>
 * import {@code org.lwjgl.openal} 的那一刻，类就被绑上了"本机必须有
 * {@code OpenAL32.dll}（或由 LWJGL 携带的软实现）"这个前提。
 * 若这条链路散在 {@link AudioManager} 里，那么在<b>没有 OCX native 的环境</b>里
 * 一加载就会抛 {@code UnsatisfiedLinkError} —— 而它属于 {@code Error}，
 * 不是 {@code Exception}，只写 {@code catch (Exception)} 是拦不住的。
 * 隔离之后，{@link AudioManager} 只要在构造这里的地方
 * {@code catch (Throwable)}，整个音频子系统就变成"可以整体缺席"。
 *
 * <h2>生命周期</h2>
 * <ol>
 *   <li>{@link #open()}：开设备 → 建上下文 → 置为当前 → 上传全部音效到 buffer
 *       → 建一组 source 作为播放池；</li>
 *   <li>{@link #play(AudioEvent, int, float)}：从池里取一个 source，重新绑定 buffer 并播；</li>
 *   <li>{@link #close()}：停全部 source → 删 source → 删 buffer →
 *       解除当前上下文 → 销毁上下文 → 关设备。幂等。</li>
 * </ol>
 *
 * <h2>为什么用 source 池而不是每播一声 new 一个</h2>
 * 击发间隔 250 ms、单次发声最长 0.55 s，8 个 source 已经是实际使用的数倍；
 * 每播一声就 gen/delete 一个 source 的话，资源分配会恰好发生在开枪最密集的时刻
 * —— 而"卡顿出现在最需要手感的那一刻"是这里最不能接受的失败形式。
 *
 * <h2>本阶段的诚实边界</h2>
 * <ul>
 *   <li><b>没有 3D 定位</b>：全部 source 都是非空间化的（{@code AL_SOURCE_RELATIVE}
 *       未设置、也不用 {@code AL_POSITION}）。M2.1 只要求"听见"，不要求"听出方位"；</li>
 *   <li><b>没有距离衰减与总线结构</b>：音量只用 {@code AL_GAIN} 一处乘算完成；</li>
 *   <li><b>没有流式</b>：全部音都很短（最长 0.55 秒），一次性上传进 buffer。</li>
 * </ul>
 */
final class OpenAlBackend implements AudioBackend {

    /**
     * 播放池里的 source 数量。
     *
     * <p>手枪射速 4 发/秒 → 相邻两枪间隔 0.25 s，而最长的一个音是换弹的 0.55 s。
     * 同发上限的真正来源不是平均射速，而是"一次移动可能同时催生几个事件"
     * （同一逻辑步里：命中怪物 + 玩家被咬）。取 8 是 Practical headroom，
     * 而不是某个精算出来的下界 —— 池被占满时的行为是抢占最老的一个，不会丢音。
     */
    private static final int SOURCE_COUNT = 8;

    private long device = NULL;
    private long context = NULL;
    private int[] sourcePool = new int[0];
    private int[] buffers = new int[0];
    private int roundRobin;
    private int uploadCount;
    private String description = "未初始化";
    /** 最近一次读到的 AL 错误码（每次发声后被清空）。 */
    private final List<Integer> lastErrors = new ArrayList<>();

    OpenAlBackend() {
    }

    // ============================================================ 生命周期

    @Override
    public boolean open() {
        try {
            /*
             * 第一步不是"直接开设备"，而是先问一句"默认设备叫什么"。
             *
             * alcOpenDevice 在没有任何音频设备的机器上确实会返回 NULL，但不同驱动对
             * "不存在"的反应差别很大：有的立刻返回、有的会先尝试初始化整个音频栈。
             * 相比之下 alcGetString(NULL, ALC_DEFAULT_DEVICE_SPECIFIER) 是一次
             * 纯粹的枚举，不开句柄、不启动 mixer —— 拿它当"这台机器有没有设备"的
             * 廉价探针，可以在绝大多数无头环境里完全绕开 alcOpenDevice。
             */
            String defaultDevice = alcGetString(NULL, ALC_DEFAULT_DEVICE_SPECIFIER);
            if (defaultDevice == null || defaultDevice.isBlank()) {
                description = "无可用音频设备（默认设备名为空）";
                return false;
            }

            device = alcOpenDevice(defaultDevice);
            if (device == NULL) {
                description = "alcOpenDevice 失败（设备被占用或驱动拒绝）";
                return false;
            }

            ALCCapabilities deviceCaps = ALC.createCapabilities(device);
            context = alcCreateContext(device, (int[]) null);
            if (context == NULL) {
                safeCloseDeviceOnly();
                description = "alcCreateContext 失败";
                return false;
            }
            if (!alcMakeContextCurrent(context)) {
                safeCloseDeviceOnly();
                description = "alcMakeContextCurrent 失败";
                return false;
            }
            AL.createCapabilities(deviceCaps);

            if (!uploadClips()) {
                close();
                description = "音效上传失败";
                return false;
            }
            if (!createSources()) {
                close();
                description = "source 池创建失败";
                return false;
            }

            description = "OpenAL 就绪（设备=" + defaultDevice + "）";
            return true;
        } catch (Throwable t) {
            description = "open() 失败：" + t.getClass().getSimpleName() + " " + t.getMessage();
            close();
            return false;
        }
    }

    @Override
    public void close() {
        try {
            stopAllSources();
            if (sourcePool.length > 0) {
                alDeleteSources(sourcePool);
                sourcePool = new int[0];
            }
            if (buffers.length > 0) {
                alDeleteBuffers(buffers);
                buffers = new int[0];
            }
        } catch (Throwable ignored) {
            // 收尾路径上任何失败都不得冒泡：它唯一的后果是"这一声没播完"，
            // 而让它冒泡的后果是"整个进程收尾失败"。
        } finally {
            try {
                alcMakeContextCurrent(NULL);
                if (context != NULL) {
                    alcDestroyContext(context);
                }
            } catch (Throwable ignored) {
                // 同上
            }
            context = NULL;
            safeCloseDeviceOnly();
        }
    }

    // ============================================================ 播放

    @Override
    public void play(AudioEvent event, int variant, float gain) {
        if (sourcePool.length == 0 || event == null) {
            return;
        }
        int index = event.normalizeVariant(variant);
        int buffer = buffers[event.ordinal() * MAX_VARIANTS + index];
        if (buffer == 0) {
            return;
        }
        int source = nextSource();
        if (source == 0) {
            return;
        }
        // 抢占：若这个 source 还在响，先停再重绑。
        // 不停直接 rebind 的话 OpenAL 会拒绝该次缓冲绑定（INVALID_OPERATION），
        // 表现为"偶发地某一枪没有声音"，且这种偶发很难与随机听觉疲劳区分。
        alSourceStop(source);
        alSourcei(source, AL_BUFFER, buffer);
        alSourcei(source, AL_LOOPING, AL_FALSE);
        alSourcef(source, AL_GAIN, clampGain(gain));
        alSourcePlay(source);
        drainErrors();
    }

    @Override
    public String describe() {
        String base = description + "，source 池=" + sourcePool.length
                + "，已上传=" + uploadCount + " 个音";
        return lastErrors.isEmpty() ? base : base + "，最近 AL 错误码 " + lastErrors;
    }

    // ============================================================ 内部

    /** 每个事件预留的 buffer 槽位数。大于任何事件的变体数即可。 */
    private static final int MAX_VARIANTS = 8;

    private boolean uploadClips() {
        AudioEvent[] all = AudioEvent.values();
        buffers = new int[all.length * MAX_VARIANTS];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = 0;
        }
        for (AudioEvent event : all) {
            for (int variant = 0; variant < event.variants(); variant++) {
                short[] pcm = PcmSynth.render(event, variant);
                if (pcm.length == 0) {
                    return false;
                }
                int buffer = alGenBuffers();
                if (buffer == 0 || alGetError() != AL_NO_ERROR) {
                    return false;
                }
                alBufferData(buffer, AL_FORMAT_MONO16, pcm, PcmSynth.SAMPLE_RATE);
                if (alGetError() != AL_NO_ERROR) {
                    return false;
                }
                buffers[event.ordinal() * MAX_VARIANTS + variant] = buffer;
                uploadCount++;
            }
        }
        return uploadCount > 0;
    }

    private boolean createSources() {
        sourcePool = new int[SOURCE_COUNT];
        alGenSources(sourcePool);
        if (alGetError() != AL_NO_ERROR) {
            sourcePool = new int[0];
            return false;
        }
        for (int source : sourcePool) {
            if (source == 0) {
                return false;
            }
            alSourcef(source, AL_GAIN, 1.0f);
            alSourcei(source, AL_LOOPING, AL_FALSE);
        }
        return drainErrors();
    }

    /**
     * 取下一个 source：优先取已经播完（或空闲）的，全都在响就轮转抢占最老的那个。
     *
     * <p><b>为什么不简单地按顺序轮转：</b>手指挡住第二枪的时刻，
     * "按下一枪没声音"是最容易被玩家察觉的缺陷之一。先找空闲 source，
     * 让抢占成为"池真的被占满时"才发生的退路，而不是常态。
     */
    private int nextSource() {
        for (int attempt = 0; attempt < sourcePool.length; attempt++) {
            int candidate = sourcePool[roundRobin];
            roundRobin = (roundRobin + 1) % sourcePool.length;
            if (candidate != 0 && alGetSourcei(candidate, AL_SOURCE_STATE) != AL_PLAYING) {
                return candidate;
            }
        }
        // 全在响：退化为严格轮转（等价于抢占最老的一个）
        int fallback = sourcePool[roundRobin];
        roundRobin = (roundRobin + 1) % sourcePool.length;
        return fallback;
    }

    private void stopAllSources() {
        if (sourcePool.length == 0) {
            return;
        }
        for (int source : sourcePool) {
            if (source != 0) {
                try {
                    alSourceStop(source);
                } catch (Throwable ignored) {
                    // 单个 source 停止失败不该挡住其余的释放
                }
            }
        }
    }

    /** 关设备（含错误判别）。单独抽出来是因为失败路径上有两处需要它。 */
    private void safeCloseDeviceOnly() {
        try {
            if (device != NULL) {
                alcCloseDevice(device);
            }
        } catch (Throwable ignored) {
            // 同上：收尾失败必须 swallowed
        } finally {
            device = NULL;
        }
    }

    /**
     * 把 OpenAL 的错误队列读空。
     *
     * <p><b>为什么需要一个空转读取：</b>错误状态是栈式的且不会自清。
     * 不在每次发声后读空的话，下一次判断"这次调用成没成"时会读到上一次的错误，
     * 于是第一次失败之后，所有后续调用看起来都在失败。
     */
    private boolean drainErrors() {
        lastErrors.clear();
        for (int i = 0; i < 32; i++) {
            int error = alGetError();
            if (error == AL_NO_ERROR) {
                break;
            }
            lastErrors.add(error);
        }
        return lastErrors.isEmpty();
    }

    private static float clampGain(float gain) {
        if (!Float.isFinite(gain) || gain < 0f) {
            return 0f;
        }
        return Math.min(1f, gain);
    }
}

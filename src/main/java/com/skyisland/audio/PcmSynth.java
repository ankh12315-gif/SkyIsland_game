package com.skyisland.audio;

/**
 * 程序化合成 M2.1 的全部占位音 —— <b>不读任何外部音频文件</b>。
 *
 * <h2>为什么合成而不是引入 wav / ogg</h2>
 * 本项目从 M0 起就是"零外部资产"：中文字形是离线烘焙成点阵后写进 Java 源文件的，
 * 纹理是 {@code Renderer} 里按方块 id 算出来的色值，没有一个 PNG 图集。
 * 音频若在这里破例引入二进制素材，就会同时引入三件本阶段付不起的代价：
 * <ul>
 *   <li><b>许可证</b> —— 任何一段现成音效都要溯源授权，而合成的波形没有作者；</li>
 *   <li><b>打包</b> —— 现有的 fat jar 是把依赖 jar 拆开重封，资源要走另一套路径，
 *       而本项目至今没有资源目录协议；</li>
 *   <li><b>不可断言</b> —— 二进制素材的正确性只能靠"听"，而合成波形的
 *       时长 / 峰值 / 均方根 / 无爆音这几个性质是<b>可以写进单元测试的</b>。
 *       本阶段的目标是证明"触发 → 合成 → 播放"这条链是通的，
 *       可断言远比好听重要。</li>
 * </ul>
 * M2.1 的合成音允许难听。它是占位音，不是最终音频资产库。
 *
 * <h2>为什么不是"一行白噪声生成一个音"</h2>
 * 五个音要有五种<b>可分辨</b>的音色，否则"我开枪了"与"我命中了"在听感上合并，
 * 事件反馈的信息量就丢了。因此每个音都由若干层叠加：
 * 一层负责"这是什么动作"（音高层），一层负责"它发生了"（瞬态层），
 * 需要时再加一层低频尾巴作为"体量"。三者相加后统一归一。
 *
 * <h2>决定性</h2>
 * 同一个 {@code (事件, 变体)} 永远合成出逐样本一致的波形（内部用线性同余发生器，
 * 不碰 {@code Math.random}、不碰 {@code nanoTime}）。这是 {@code PcmSynthTest}
 * 能断言"两次合成结果相同"的前提，也让"这一声听起来变了"必定对应一次代码改动。
 */
public final class PcmSynth {

    /** 采样率。统一 44100：再低一些，几个高频瞬态就会开始出现混叠。 */
    public static final int SAMPLE_RATE = 44100;

    private static final float FULL_SCALE = 32767f;

    /**
     * 淡入淡出窗口：用于消除 buffer 首尾的阶跃（它会表现为额外的一声"啪"）。
     *
     * <p><b>淡入必须极短（16 个样本 ≈ 0.36 ms）：</b>短音的起音本来就在
     * 0.2–0.6 ms 量级，淡入窗口一旦与它同宽，被削掉的就<b>正好是那个瞬态</b> ——
     * 空仓咔哒声会因此变成一声闷响。实测过 1.5 ms 的窗口：gun_empty 的峰值
     * 从满量程的 70% 掉到 35%，听上去像"隔着一层布"。淡入只需要让第 0 个样本为 0
     * 即可，这 16 个样本已经足够。
     */
    private static final int FADE_IN_SAMPLES = 16;
    private static final int FADE_OUT_SAMPLES = (int) (0.0030 * SAMPLE_RATE);

    private PcmSynth() {
    }

    // ============================================================ 对外 API

    /** 合成某个事件的第 {@code variant} 个变体（下标已归一化）。 */
    public static short[] render(AudioEvent event, int variant) {
        if (event == null) {
            throw new IllegalArgumentException("event 不得为 null");
        }
        int index = event.normalizeVariant(variant);
        long seed = 0x9E3779B97F4A7C15L ^ (event.ordinal() * 7919L + index * 104729L);
        Rng rng = new Rng(seed);

        double seconds = durationSeconds(event);
        int n = Math.max(1, (int) Math.round(seconds * SAMPLE_RATE));
        float[] buffer = new float[n];

        switch (event) {
            case GUN_FIRE -> gunFire(buffer, rng, index);
            case GUN_EMPTY -> gunEmpty(buffer, rng, index);
            case RELOAD -> reload(buffer, rng, index);
            case HIT_ENEMY -> hitEnemy(buffer, rng, index);
            case PLAYER_HURT -> playerHurt(buffer, rng, index);
            case UI_OPEN -> uiOpen(buffer, rng, index);
            case UI_CLOSE -> uiClose(buffer, rng, index);
            case UI_MOVE -> uiMove(buffer, rng, index);
            case UI_DENIED -> uiDenied(buffer, rng, index);
        }

        return finalize(event, buffer);
    }

    /** 该事件单个变体的时长（秒）。 */
    public static double durationSeconds(AudioEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event 不得为 null");
        }
        return switch (event) {
            case GUN_FIRE -> 0.20;
            case GUN_EMPTY -> 0.07;
            case RELOAD -> 0.55;
            case HIT_ENEMY -> 0.12;
            case PLAYER_HURT -> 0.40;
            // UI 音全部落在 (0.07, 0.55) 这个区间内：
            //   · 必须比空仓那声"咔哒"长 —— 否则 UI 音会被读成"操作失败了"
            //     （空仓音的语义就是"没生效"）；
            //   · 必须比换弹（一次完整机械行程）短 —— 交互确认不该占住 0.55 秒。
            case UI_OPEN -> 0.16;
            case UI_CLOSE -> 0.16;
            case UI_MOVE -> 0.11;
            case UI_DENIED -> 0.22;
        };
    }

    /** 全部事件 × 全部变体的样本总数（内存预算用）。 */
    public static long totalSampleCount() {
        long total = 0;
        for (AudioEvent e : AudioEvent.values()) {
            total += (long) e.variants() * Math.round(durationSeconds(e) * SAMPLE_RATE);
        }
        return total;
    }

    /** 峰值绝对值（0..32767）。 */
    public static int peak(short[] pcm) {
        int peak = 0;
        for (short s : pcm) {
            peak = Math.max(peak, Math.abs((int) s));
        }
        return peak;
    }

    /** 均方根（0..1）。比峰值更能说明"这一段是不是真的在响"。 */
    public static double rms(short[] pcm) {
        if (pcm.length == 0) {
            return 0;
        }
        double sum = 0;
        for (short s : pcm) {
            double v = s / FULL_SCALE;
            sum += v * v;
        }
        return Math.sqrt(sum / pcm.length);
    }

    // ============================================================ 各事件的合成配方

    /**
     * 枪声：低频"体" + 高频"裂" + 低频尾，三层。
     *
     * <p>一层版本听起来像电视机噪声；三层里真正让人认出"这是枪"的是
     * <b>第一层那条快速下滑的低频</b>（枪口的压力释放），而不是噪声本身。
     */
    private static void gunFire(float[] out, Rng rng, int variant) {
        int n = out.length;
        float glideTop = 220f + variant * 14f;

        // ① 低频体：始于 220 Hz 上下，90 ms 内滑到 70 Hz
        float[] layer = new float[n];
        glide(layer, glideTop, 70f, 0.09, /* attack = */ 0.0008, /* tau = */ 0.045, 0.85f);

        // ② 裂：高通白噪声，起音极快、衰减极快 —— 这是"击发"这个瞬间的全部信息
        float[] crack = new float[n];
        white(crack, rng);
        highpass(crack, 0.38f);
        envelope(crack, 0.0005, 0.012);
        scale(crack, 0.75f);

        // ③ 尾：重低通的噪声尾巴，给枪一点体量（"这是在室外"的暗示）
        float[] tail = new float[n];
        white(tail, rng);
        lowpass(tail, 0.05f);
        envelope(tail, 0.006, 0.075);
        scale(tail, 0.35f);

        mix(out, layer, crack, tail);
    }

    /**
     * 空仓击针：极短的金属咔哒声。
     *
     * <p><b>为什么刻意几乎没有低频：</b>它必须在刚响过的枪声尾巴里被听出来。
     * 低频区域已经被枪声的体占满了（掩蔽效应），把这一声的能量全部推到
     * 2 kHz 以上，即便紧接着枪声也能分辨。
     */
    private static void gunEmpty(float[] out, Rng rng, int variant) {
        int n = out.length;
        float base = 2600f + variant * 180f;

        float[] metal = new float[n];
        decayingTone(metal, base, 0.008, 0.0003, 0.70f);
        add(metal, decayingTone(new float[n], base * 1.5f, 0.005, 0.0002, 0.45f));

        // 注意：局部变量不能叫 transient —— 它是 Java 保留字
        float[] click = new float[n];
        white(click, rng);
        highpass(click, 0.5f);
        envelope(click, 0.0003, 0.0025);
        scale(click, 0.5f);

        mix(out, metal, click);
    }

    /**
     * 换弹：把"退弹匣 / 推新弹匣 / 闭锁"三段机械行程压缩进一声。
     *
     * <p>三次撞击的时间间隔（0 / 0.16 / 0.44 秒）是<b>唯一让这一声被认成换弹
     * 而不是三声堆叠</b>的东西，因此它比音色本身更不应该被改。
     */
    private static void reload(float[] out, Rng rng, int variant) {
        int n = out.length;
        float center = 1200f + variant * 90f;

        float[] phase = new float[n];
        resonantClack(phase, rng, center, 0.030, 0, 0.80f);
        resonantClack(phase, rng, center * 0.82f, 0.022, 0.16, 0.70f);

        // 中间那一段"弹匣在导轨上滑动"：低通噪声，被一段缓升缓降的窗罩住
        float[] slide = new float[n];
        white(slide, rng);
        lowpass(slide, 0.10f);
        bandGate(slide, 0.26, 0.36);
        scale(slide, 0.25f);

        float[] latch = new float[n];
        resonantClack(latch, rng, center * 1.45f, 0.018, 0.44, 0.65f);

        mix(out, phase, slide, latch);
    }

    /**
     * 命中确认：短、亮、向前的一记"哒"。
     *
     * <p>这是五项里唯一<b>单纯为了信息</b>存在的音 —— 怪物已经会闪白、
     * 已经有了溅射粒子，但那些都需要玩家把视线锁在准星附近。
     * 一个不容忽视的中频短音能让"打中了"这件事进入听觉通道。
     */
    private static void hitEnemy(float[] out, Rng rng, int variant) {
        int n = out.length;
        float fundamental = 860f + variant * 70f;

        float[] body = new float[n];
        decayingTone(body, fundamental, 0.030, 0.0006, 0.70f);
        add(body, decayingTone(new float[n], fundamental * 1.5f, 0.022, 0.0004, 0.45f));
        add(body, decayingTone(new float[n], fundamental * 2.0f, 0.016, 0.0003, 0.30f));

        // 注意：局部变量不能叫 transient —— 它是 Java 保留字
        float[] tick = new float[n];
        white(tick, rng);
        highpass(tick, 0.45f);
        envelope(tick, 0.0003, 0.0030);
        scale(tick, 0.40f);

        mix(out, body, tick);
    }

    /**
     * 玩家受伤：低频闷响 + 下行滑音。
     *
     * <p>与其他四项相反，这个音应该让人<b>不舒服</b> ——
     * 一路下行的滑音是这五个音里唯一带有负面情绪色彩的信号。
     */
    private static void playerHurt(float[] out, Rng rng, int variant) {
        int n = out.length;
        float top = 420f + variant * 26f;

        float[] thud = new float[n];
        glide(thud, 130f, 55f, 0.10, 0.001, 0.100, 0.80f);

        float[] pain = new float[n];
        glide(pain, top, top * 0.45f, 0.16, 0.004, 0.160, 0.30f);
        lowpass(pain, 0.30f);

        float[] grime = new float[n];
        white(grime, rng);
        lowpass(grime, 0.04f);
        envelope(grime, 0.002, 0.090);
        scale(grime, 0.22f);

        mix(out, thud, pain, grime);
    }

    // ============================================================ M2.2 UI 音

    /**
     * 背包打开：<b>上行</b>双音滑音。
     *
     * <p>与 {@link #uiClose} 成对设计：两者共享基频、时长、包络与噪声层，
     * 唯一的结构性差别是滑音方向 —— 上行 = 展开，下行 = 收起，方向本身就是语义。
     * 这条"精确镜像"由 {@code PcmSynthTest#uiOpenAndUiCloseAreAPair} 从
     * 时间域（时长 / 增益 / 变体数 / 包络轮廓）钉住。
     *
     * <p><b>方向本身没有机器判据，靠人工试听：</b>本仓库试过四种信号域仪器
     * （过零率、自相关基频、频带能量比、带负对照的差分比较）都测不出这个方向 ——
     * 0.14 增益的宽带"空气"噪声层会主导任何高频统计量，最后一轮甚至在两个变体上
     * 给出相反的符号。四轮失败记录见 {@code docs/testing/M2_2_UI_INVENTORY_REPORT.md}，
     * 试听条目见 {@code docs/testing/M2_2_PLAYTEST_CHECKLIST.md}。
     */
    private static void uiOpen(float[] out, Rng rng, int variant) {
        int n = out.length;
        float base = 620f + variant * 40f;

        float[] rise = new float[n];
        // 起音略慢（6 ms）—— UI 音不该有"爆"的瞬态，那是战斗音的语言。
        // 滑音铺满整段（0.15 s，音长 0.16 s）：方向感必须贯穿始终，
        // 而不是"前半段上行、后半段停在一个音高上"（那就只剩一个音，没有走向）。
        glide(rise, base, base * 2.05f, 0.15, 0.006, 0.055, 0.75f);
        // 叠一个伴随音，"展开"听起来厚一点而不是单薄的口哨。
        // 用 1.25 / 2.55 而不是 1.5 / 2.9：伴随音要与主音的行程同比例（都是 ×2.04），
        // 否则两个音会先后到达终点、方向感被拆成两段。
        float[] third = new float[n];
        glide(third, base * 1.25f, base * 2.55f, 0.15, 0.006, 0.055, 0.28f);
        add(rise, third);

        float[] air = new float[n];
        white(air, rng);
        lowpass(air, 0.30f);
        envelope(air, 0.004, 0.045);
        scale(air, 0.14f);

        mix(out, rise, air);
    }

    /**
     * 背包关闭：{@link #uiOpen} 的<b>精确频率镜像</b>。
     *
     * <p>取同一个基频 {@code base}，把主音与伴随音的起止点原样对调 ——
     * 于是两个音走的是同一组频率、同一段时间、同一个包络，只是顺序相反。
     * 攻击 / 衰减 / 噪声层的全部参数与 {@link #uiOpen} 逐字相同：
     * 一旦这几个参数分叉，"一对"就只剩口头声明，听感上会散成两个不相干的提示音。
     */
    private static void uiClose(float[] out, Rng rng, int variant) {
        int n = out.length;
        float base = 620f + variant * 40f;

        float[] fall = new float[n];
        glide(fall, base * 2.05f, base, 0.15, 0.006, 0.055, 0.75f);
        float[] third = new float[n];
        glide(third, base * 2.55f, base * 1.25f, 0.15, 0.006, 0.055, 0.28f);
        add(fall, third);

        float[] air = new float[n];
        white(air, rng);
        lowpass(air, 0.30f);
        envelope(air, 0.004, 0.045);
        scale(air, 0.14f);

        mix(out, fall, air);
    }

    /**
     * 物品搬运成功：一记<b>软起音</b>的短促点击。
     *
     * <p><b>为什么必须是"软"起音（4 ms）而不是像空仓那样的硬咔哒：</b>
     * 空仓音的语义是"没打出去"，它是一个<u>否定</u>；
     * 搬运成功是一个<u>确认</u>。两者若都是硬瞬态，在噪声环境里会被听混 ——
     * 而背包里可能连续点几十下，听混的代价是玩家以为自己在失败。
     * 因此这里把起音放慢、把中心频率压到空仓（2.6 kHz）的一半以下。
     */
    private static void uiMove(float[] out, Rng rng, int variant) {
        int n = out.length;
        float tone = 1180f + variant * 130f;

        float[] body = new float[n];
        decayingTone(body, tone, 0.0040, 0.022, 0.80f);
        add(body, decayingTone(new float[n], tone * 1.9f, 0.0025, 0.012, 0.25f));

        float[] soft = new float[n];
        white(soft, rng);
        lowpass(soft, 0.35f);
        envelope(soft, 0.0035, 0.014);
        scale(soft, 0.22f);

        mix(out, body, soft);
    }

    /**
     * 操作被拒绝：低频短促的一声"嗯"。
     *
     * <p><b>为什么整个音里几乎没有高频：</b>它是"不允许"的信号。
     * 高频亮音在听觉上天然是"进 / 成功"的语言（命中确认音就是这么做的），
     * 拒绝必须落在相反的语义侧。慢衰减（90 ms）也是同一个目的：
     * 它让这一声显得"沉重"，而不是一个轻快的提示。
     */
    private static void uiDenied(float[] out, Rng rng, int variant) {
        int n = out.length;
        float low = 196f + variant * 14f;

        float[] buzz = new float[n];
        decayingTone(buzz, low, 0.0080, 0.090, 0.85f);
        // 叠一个稍微失谐的二次谐波：单纯正弦会听起来像"电话音"，
        // 失谐让它带上"被打断"的粗糙感
        add(buzz, decayingTone(new float[n], low * 2.06f, 0.0060, 0.055, 0.30f));

        float[] body = new float[n];
        white(body, rng);
        lowpass(body, 0.10f);
        envelope(body, 0.005, 0.070);
        scale(body, 0.30f);

        mix(out, buzz, body);
    }

    // ============================================================ 后处理

    /**
     * 归一 → 去直流 → 软限幅 → 首尾淡入淡出 → 转 16 位定点。
     *
     * <p><b>为什么最后一定要淡到零：</b>缓冲的第一/最后一个样本若不为 0，
     * 播放开始时会在扬声器上产生一次阶跃（听到的是额外的"啪"）。
     * 这种缺陷在母带上会被当作素材问题，但在这里它是合成代码的责任。
     */
    private static short[] finalize(AudioEvent event, float[] samples) {
        int n = samples.length;

        double mean = 0;
        for (float v : samples) {
            mean += v;
        }
        mean /= n;
        for (int i = 0; i < n; i++) {
            samples[i] -= (float) mean;
        }

        float peak = 0;
        for (float v : samples) {
            peak = Math.max(peak, Math.abs(v));
        }
        float target = targetPeak(event);
        if (peak > 1e-6f) {
            float gain = target / peak;
            for (int i = 0; i < n; i++) {
                samples[i] *= gain;
            }
        }

        int fadeIn = Math.min(FADE_IN_SAMPLES, n / 4);
        int fadeOut = Math.min(FADE_OUT_SAMPLES, n / 4);
        for (int i = 0; i < fadeIn; i++) {
            samples[i] *= i / (float) fadeIn;
        }
        for (int i = 0; i < fadeOut; i++) {
            samples[n - 1 - i] *= i / (float) fadeOut;
        }

        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            float v = softClip(samples[i]);
            pcm[i] = (short) Math.round(Math.max(-1f, Math.min(1f, v)) * FULL_SCALE);
        }
        return pcm;
    }

    /** 各事件的目标峰值：为叠加留出余量，避免几个音同时响时互相挤到削波。 */
    private static float targetPeak(AudioEvent event) {
        return switch (event) {
            case GUN_FIRE -> 0.95f;
            case GUN_EMPTY -> 0.70f;
            case RELOAD -> 0.80f;
            case HIT_ENEMY -> 0.85f;
            case PLAYER_HURT -> 0.90f;
            // UI 音的目标峰值整体压低：它们的目标不是"被听见"，而是"被注意到但不打断"。
            // 与 baseGain 低是两件事：baseGain 管"这一类相对另一类有多响"，
            // targetPeak 管"这一个音自己波形里的动态余量"。
            case UI_OPEN -> 0.55f;
            case UI_CLOSE -> 0.55f;
            case UI_MOVE -> 0.45f;
            case UI_DENIED -> 0.60f;
        };
    }

    private static float softClip(float v) {
        return (float) Math.tanh(v * 1.1);
    }

    // ============================================================ 基础 DSP 零件

    /** 白噪声（-1..1）。 */
    private static void white(float[] out, Rng rng) {
        for (int i = 0; i < out.length; i++) {
            out[i] = rng.nextSigned();
        }
    }

    /** 一阶低通（就地）。系数越大越亮。 */
    private static void lowpass(float[] out, float coefficient) {
        float previous = 0;
        for (int i = 0; i < out.length; i++) {
            previous += coefficient * (out[i] - previous);
            out[i] = previous;
        }
    }

    /** 一阶高通（就地）。 */
    private static void highpass(float[] out, float coefficient) {
        float previous = 0;
        float lastInput = 0;
        for (int i = 0; i < out.length; i++) {
            float input = out[i];
            previous = coefficient * (previous + input - lastInput);
            out[i] = previous;
            lastInput = input;
        }
    }

    /** 起音 + 指数衰减包络（就地）。 */
    private static void envelope(float[] out, double attackSeconds, double tau) {
        int n = out.length;
        int attack = Math.max(1, (int) (attackSeconds * SAMPLE_RATE));
        for (int i = 0; i < n; i++) {
            float attackGain = i < attack ? (float) i / attack : 1f;
            double decay = Math.exp(-(i / (double) SAMPLE_RATE) / tau);
            out[i] *= (float) (attackGain * decay);
        }
    }

    /**
     * 单个衰减正弦。
     *
     * <p>相位是累加出来的而不是 {@code sin(2πft)}，因此滑音（频率逐样本变）
     * 与固定音共用同一套代码 —— 分开写两份的话，"滑音版本忘了累加相位"
     * 会得到一个频率正确但波形错误的音，而它听起来只是"有点怪"。
     */
    private static float[] decayingTone(float[] out, float frequency, double tau,
                                        double attackSeconds, float amplitude) {
        int n = out.length;
        int attack = Math.max(1, (int) (attackSeconds * SAMPLE_RATE));
        double phase = 0;
        for (int i = 0; i < n; i++) {
            float attackGain = i < attack ? (float) i / attack : 1f;
            double decay = Math.exp(-(i / (double) SAMPLE_RATE) / tau);
            out[i] = (float) (Math.sin(phase) * attackGain * decay * amplitude);
            phase += 2.0 * Math.PI * frequency / SAMPLE_RATE;
        }
        return out;
    }

    /** 从 {@code from} Hz 在 {@code glideSeconds} 内滑到 {@code to} Hz 的衰减音。 */
    private static void glide(float[] out, float from, float to, double glideSeconds,
                              double attackSeconds, double tau, float amplitude) {
        int n = out.length;
        int attack = Math.max(1, (int) (attackSeconds * SAMPLE_RATE));
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            double k = Math.min(1.0, t / glideSeconds);
            float frequency = (float) (from + (to - from) * k);
            float attackGain = i < attack ? (float) i / attack : 1f;
            double decay = Math.exp(-t / tau);
            out[i] = (float) (Math.sin(phase) * attackGain * decay * amplitude);
            phase += 2.0 * Math.PI * frequency / SAMPLE_RATE;
        }
    }

    /**
     * 一次有谐振的机械撞击：短促噪声激励 + 二阶谐振器。
     *
     * <p>用"噪声过谐振器"而不是"纯正弦衰减"，是因为真实的塑料/金属撞击声里
     * 宽带成分（"哒"）与窄带成分（"嗡"）是同时出现的，纯正弦听起来像玻璃。
     *
     * @param tau 谐振衰减时间常数，同时也近似决定这一击的长度
     */
    private static void resonantClack(float[] out, Rng rng, float center, double tau,
                                      double offsetSeconds, float amplitude) {
        int n = out.length;
        int start = (int) (offsetSeconds * SAMPLE_RATE);
        if (start >= n) {
            return;
        }
        float omega = (float) (2.0 * Math.PI * center / SAMPLE_RATE);
        float resonance = (float) Math.exp(-1.0 / (tau * SAMPLE_RATE));
        float radius = resonance;
        float cos = (float) Math.cos(omega);
        float coefficient = 2f * radius * cos;
        float squared = radius * radius;

        // 归一化增益：让不同中心频率的谐振器在同样的撞击力度下同样响，
        // 否则换弹的第二声会比第一声闷一大截，听上去像"这一枪没卡到位"。
        float driveScale = (1f - coefficient + squared) * 2.0f;
        float previous1 = 0;
        float previous2 = 0;
        for (int i = start; i < n; i++) {
            float drive = i - start < EXCITATION_SAMPLES ? rng.nextSigned() : 0f;
            float value = drive * driveScale + coefficient * previous1 - squared * previous2;
            previous2 = previous1;
            previous1 = value;
            out[i] += value * amplitude;
        }
    }

    /** 谐振器的激励长度：24 个样本 ≈ 0.5 ms，足够"敲"出宽带成分又不至于听成噪声。 */
    private static final int EXCITATION_SAMPLES = 24;

    /** 只让某一段时间窗通过（其余归零），用于"弹匣在导轨上滑动"这类持续成分。 */
    private static void bandGate(float[] out, double fromSeconds, double toSeconds) {
        int n = out.length;
        int from = Math.max(1, (int) (fromSeconds * SAMPLE_RATE));
        int to = (int) (toSeconds * SAMPLE_RATE);
        int ramp = Math.max(1, (int) (0.004 * SAMPLE_RATE));
        for (int i = 0; i < n; i++) {
            float gate;
            if (i < from || i > to) {
                gate = 0f;
            } else {
                gate = Math.min(1f, (i - from) / (float) ramp)
                        * Math.min(1f, (to - i) / (float) ramp);
            }
            out[i] *= gate;
        }
    }

    private static void add(float[] target, float[] source) {
        for (int i = 0; i < target.length; i++) {
            target[i] += source[i];
        }
    }

    private static void mix(float[] out, float[] first, float[]... rest) {
        System.arraycopy(first, 0, out, 0, out.length);
        for (float[] layer : rest) {
            add(out, layer);
        }
    }

    private static void scale(float[] out, float factor) {
        for (int i = 0; i < out.length; i++) {
            out[i] *= factor;
        }
    }

    // ============================================================ 随机源

    /**
     * 线性同余发生器。
     *
     * <p><b>为什么不用 {@code Random}：</b>{@code java.util.Random} 的序列没有被
     * 承诺跨 JDK 版本稳定，而"两次合成逐样本相同"是本站在这类测试里的断言形式。
     * 自己实现一份六行的 LCG，就把"音色会不会变"这件事锁在生产代码里。
     */
    private static final class Rng {
        private long state;

        Rng(long seed) {
            this.state = seed == 0 ? 0x123456789ABCDEFL : seed;
        }

        /** [-1, 1) 上的均匀分布。 */
        float nextSigned() {
            state = state * 6364136223846793005L + 1442695040888963407L;
            int bits = (int) (state >>> 16);
            return bits / 32768f;
        }
    }
}

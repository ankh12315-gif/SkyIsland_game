package com.skyisland.render.fx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 战斗表现层的<b>纯状态机</b>（PRD_v0.3.2 §5.2 破坏特效 / §5.4.3 击中反馈与弹道表现）。
 *
 * <h2>为什么把"数据"和"绘制"拆成两个类</h2>
 * 本类零 GL 依赖、完全确定性，因此可以在没有窗口的机器上被单测完整举证；
 * 真正的 GL 调用全部在 {@link CombatFxRenderer}。这与 M1 已确立的
 * {@code HudModel}（数据）/ {@code HudRenderer}（绘制）分工一致 ——
 * 沿用同一套分工，而不是为战斗特效另创一套。
 *
 * <h2>确定性：为什么不用 {@code Math.random()} / 默认种子的 {@code Random}</h2>
 * PRD 要求破坏时生成 8–12 个粒子，这个数量必须是<b>可复现</b>的：
 * 父代理传入的 {@code seed} 是"方块坐标 + 帧序号"的混合值，
 * 同一 seed 必须永远得到同样的粒子数与同样的初速度，否则单测无法断言、
 * 回归时也无法区分"表现变了"和"随机数变了"。
 * 因此这里自带一个 mulberry32（32 位整数状态，纯算术），
 * 并且<b>不调用 {@code Math.sin/cos}</b> —— 三角函数只保证在同一个平台上一致，
 * 而 IEEE 754 要求 {@code + − × ÷} 与 {@code sqrt} 正确舍入，
 * 于是本类的输出在跨平台、跨运行时都逐位一致。
 * 方位角改用"单位圆上拒绝采样"得到，代价是偶尔多抽一两次随机数，换来可复现性。
 *
 * <h2>为什么粒子不做世界碰撞</h2>
 * 逐帧对每个粒子做体素查询，成本与收益不成比例：占位表现只需要"看起来像碎屑飞溅"，
 * 而半真的碰撞（碰巧在方块边界被弹开、在空气里穿过）反而比"直接穿地"更让人出戏。
 * 因此粒子一律穿地，靠<b>寿命</b>自然消失 —— 落在方块下方的粒子会被地形遮挡
 * （深度测试开着），玩家看到的效果就是"碎屑掉进去了"。Alpha 阶段做正式粒子管线时
 * 再引入碰撞与淡出。
 *
 * <h2>音效不在这里</h2>
 * PRD §5.2 / §5.4.3 把破坏音效、命中音效与"每段裂纹伴随一次挖掘音效"都标为【MVP 必须】，
 * 但本项目目前<b>没有音频后端</b>（OpenAL 与音频设备是独立工作项，已在 M1 报告中
 * 登记为顺延项）。本类只负责<b>视觉</b>部分，音效的归属是 Alpha 阶段的音频工作项，
 * 不要在接线时以为"漏做了"。
 *
 * <p><b>线程模型：</b>单线程（渲染 / 逻辑同一线程），不做同步；
 * {@code particles()} 返回的是内部列表的只读视图，调用方只能读。
 */
public final class CombatFxModel {

    /** 曳光存活时长（秒）。PRD §5.4.3 原文规定 0.05 秒。 */
    public static final double TRACER_SECONDS = 0.05;

    /** 破坏粒子数的下界。PRD §5.2 原文规定 8–12 个。 */
    public static final int BREAK_PARTICLES_MIN = 8;

    /** 破坏粒子数的上界（闭区间）。 */
    public static final int BREAK_PARTICLES_MAX = 12;

    /**
     * 命中方块时溅射的粒子数。
     *
     * <p>PRD §5.4.3 只规定"命中方块：溅射粒子"，<b>没有</b>给出数量。
     * 取一个固定小值 5：命中是高频事件（按住左键连点、连射），
     * 数量必须明显小于破坏的 8–12，否则连续命中会把屏幕刷成粒子墙。
     * 这是 MVP 占位规格，Alpha 阶段做正式粒子表现时再按材质区分。
     */
    /**
     * 命中方块时溅射的粒子数。
     *
     * <p>PRD §5.4.3 只规定"命中方块：溅射粒子"，<b>没有</b>给出数量。
     * 取一个固定小值 5：命中是高频事件（按住左键连点、连射），
     * 数量必须明显小于破坏的 8–12，否则连续命中会把屏幕刷成粒子墙。
     * 这是 MVP 占位规格，Alpha 阶段做正式粒子表现时再按材质区分。
     */
    public static final int HIT_PARTICLES = 5;

    /**
     * 命中<b>实体</b>时溅射的粒子数（M2.1）。
     *
     * <p>比方块命中（{@link #HIT_PARTICLES}）多一成载荷是有意的：
     * 打中怪物是"这一枪有结果"的关键反馈，而打中墙只是"子弹停下了"。
     * 取 6 而不是 10+ 是因为它也得上 4 发/秒的射速 —— 每发都堆 10 个粒子，
     * 扫一轮下来屏幕会糊成一片红。
     */
    public static final int ENTITY_HIT_PARTICLES = 6;

    /**
     * M2.1：枪口闪光的存活时长（秒）。
     *
     * <p>取值 0.05 秒 = 60 Hz 下整整 3 帧，落在 M2.1 要求的"2–4 帧"中间。
     * <b>为什么必须是"帧数"而不是"随便一个短的时间"：</b>
     * 比 2 帧更短在一部分机器上会被垂直同步吞掉（玩家看到"随机地没火光"）；
     * 比 4 帧更长则会盖住接下来 0.25 秒一发的第二枪 —— 那不是"明显"，是"糊"。
     * 与曳光 {@link #TRACER_SECONDS} 取同一个值是巧合但合适：
     * 火光与弹道同时熄灭，读起来就是同一次事件。
     */
    public static final double MUZZLE_FLASH_SECONDS = 0.05;

    /**
     * M2.1：枪口闪光立方体的边长（格）。
     *
     * <p>0.13 格 ≈ 13 厘米。推导：枪口点在眼睛前方 {@code MUZZLE_FORWARD}（0.55）格，
     * 并向右下偏移（见 {@code com.skyisland.game.SkyIslandGame} 的 {@code MUZZLE_*} 常量），
     * 70° FOV 下该距离的竖直视野范围是 {@code 2 × 0.55 × tan(35°) ≈ 0.77 格}，
     * 于是这块光斑占屏高约 17%。
     *
     * <p><b>为什么偏小而不是偏大：</b>"能不能看见准星"是本作 Readability 的底线
     * （准星即射线，见 M1 的一致性裁定）。缺陷 A 时的 0.16 之所以刺眼，
     * 不只是因为它大 —— 而是因为枪口偏移根本没生效，光斑生在眼睛处、被相机吞进
     * 立方体内部，内部面被光栅化成一片盖住准星的白。把枪口挪到右下之后，
     * 尺寸收一点（0.13）作为第二道保险：光斑落在屏幕右下角，不再覆盖中心区域的准星。
     */
    public static final double MUZZLE_FLASH_SIZE = 0.13;

    /**
     * M2.1：命中标记的存活时长（秒）。
     *
     * <p>0.18 秒 ≈ 11 帧 @60 Hz。下界来自"短于 0.1 秒的视觉变化会被当成没发生"，
     * 上界来自"钉住 1 秒以上的标记会变成常驻 UI，失去'就是这一下'的语义"。
     * 它与 {@code Entity.HURT_FLASH_DURATION}（0.15 秒）同量级是有意的：
     * 怪物闪白与准星标记必须看起来是同一次命中的两个投影，而不是两件事。
     */
    public static final double HIT_MARKER_SECONDS = 0.18;

    /**
     * 枪口闪光的容量上限。
     *
     * <p>8 对应"同一帧里有 8 个枪口在闪"，而手枪射速只有 4 发/秒、闪光只活 3 帧，
     * 正常流程里最多同时存在 1 个。留到 8 纯粹是为了让自测脚本可以安全地
     * 连续 spawn 而不触发淘汰逻辑（淘汰会把读数变得不可预测）。
     */
    public static final int MAX_FLASHES = 8;

    /**
     * 活性粒子容量上限。
     *
     * <p>上限存在的理由是<b>自测脚本</b>而不是正常玩法：自测会按帧狂刷
     * {@code spawnBlockBreak}（一帧几十次），没有上限时列表会随帧数线性增长，
     * 最终把内存吃满、且让"顶点容量对齐"这件事无法静态确定。
     * 512 对应约 40 次连续破坏同时存活，远超正常观感需要。
     * 超限时丢弃<b>最旧</b>的（淘汰最老的，保留最新的），
     * 因为"最新发生的爆炸应该可见"比"最老的还没消失"更重要。
     */
    public static final int MAX_PARTICLES = 512;

    /** 曳光容量上限，理由同 {@link #MAX_PARTICLES}；曳光只活 0.05 秒，64 条已极宽裕。 */
    public static final int MAX_TRACERS = 64;

    /**
     * 单次 {@link #tick(double)} 允许消化的最大时长（秒）。
     *
     * <p>{@code dt} 来自帧间隔，可能因为 GC、窗口拖动、断点调试而突然变成几秒。
     * 直接把几秒喂给积分会让所有粒子瞬间掉出视野、寿命全部清零 ——
     * 玩家看到的是"一卡顿特效就全没了"。钳到 0.25 秒（≈4 FPS 的一帧）
     * 意味着长帧只是"特效推进得少一点"，而不是被一次抹掉。
     * 钳位同时保证了不会出现 {@code life} 为负的残留项。
     */
    public static final double MAX_STEP_SECONDS = 0.25;

    /**
     * 粒子重力（格/秒²）。
     *
     * <p>取 −16 的依据是"让粒子在寿命内完成恰好一条抛物线"：
     * 竖直初速度区间是 1.37–3.50 格/秒（见 {@link #BREAK_SPEED_MIN} 与方向偏置），
     * 完整飞行时间 {@code 2v/g} 落在 0.17–0.44 秒，而寿命是 0.4–0.8 秒 ——
     * 即绝大多数粒子"升空 → 到顶 → 落回原高度"之后才消失，
     * 而不是在半空中凭空不见。这与 PRD 要的"碎片感"一致。
     */
    public static final double GRAVITY = -16.0;

    // ------------------------------------------------------------ 破坏粒子参数

    /**
     * 破坏粒子初速度模长区间（格/秒）。
     *
     * <p>上下界的推导：上界受"碎屑不该飞出一个方块太多"约束 ——
     * 水平分量最大时整段寿命的水平位移约 1.12 格（见注释末），
     * 视觉上正好是"炸开一角"；下界保证最慢的粒子也能升到 0.06 格以上，
     * 不至于刚出现就趴在地上看不出动。
     */
    public static final double BREAK_SPEED_MIN = 1.5;
    public static final double BREAK_SPEED_MAX = 3.5;

    /**
     * 破坏粒子方向竖直分量的下限（单位向量的 y 分量）。
     *
     * <p>0.70 ≈ 仰角 44.4°，即方向<b>恒在上半球</b>且离地平线足够远 ——
     * 这直接落实了"向上的半球随机方向"。若取 0 附近，会有大量近水平的粒子
     * 贴着地面飞，看起来像"漏气"而不是"碎屑"。
     */
    public static final double BREAK_ELEVATION_MIN_SIN = 0.70;

    /**
     * 破坏粒子水平分量的压缩系数。
     *
     * <p>在球面上均匀取方向会得到"向外炸开"的观感，而 PRD 要的是<b>碎片感</b>：
     * 应当以向上为主、水平散开为辅。把水平分量乘 0.45 再归一化，
     * 相当于把锥体收窄，竖直分量占比从 ≥0.70 提到实测 ≥0.91。
     */
    public static final double BREAK_HORIZONTAL_SQUASH = 0.45;

    /** 破坏粒子边长区间（格）：6–10 厘米，与体素风格一致（方块边长 1 格）。 */
    public static final double BREAK_SIZE_MIN = 0.06;
    public static final double BREAK_SIZE_MAX = 0.10;

    /** 破坏粒子寿命区间（秒）：0.4–0.8，与"一条抛物线"的飞行时间（0.17–0.44 秒）配套。 */
    public static final double BREAK_LIFE_MIN = 0.4;
    public static final double BREAK_LIFE_MAX = 0.8;

    /**
     * 破坏粒子的生成位置散布（格）。
     *
     * <p>0.8 表示在方块中心 ±0.4 范围内均匀取点，即粒子全部落在
     * 方块中心的 [−0.4, +0.4] 之内 —— 既不会溢出到相邻方块里显得突兀，
     * 又不会密集到只像一个点。全都在 {@code [blockX-0.6, blockX+1.6]} 内（有单测钉死）。
     */
    public static final double BREAK_POSITION_SPREAD = 0.8;

    // ------------------------------------------------------------ 命中和曳光参数

    /** 命中溅射粒子的速度区间（格/秒）：比破坏慢，观感上"溅起"而非"炸开"。 */
    public static final double HIT_SPEED_MIN = 1.0;
    public static final double HIT_SPEED_MAX = 2.5;

    /** 命中溅射方向与法线的最大夹角：竖直分量下限 0.55 ≈ 56.6°，压缩后实测锥半角 ≤ 43°。 */
    public static final double HIT_ELEVATION_MIN_SIN = 0.55;

    /** 命中溅射的水平压缩系数（比破坏更收窄，溅射是"一簇"而不是"一片"）。 */
    public static final double HIT_HORIZONTAL_SQUASH = 0.60;

    /** 命中溅射粒子尺寸与寿命：比破坏更小更短命，避免高频命中堆积成视觉噪声。 */
    public static final double HIT_SIZE_MIN = 0.05;
    public static final double HIT_SIZE_MAX = 0.08;
    public static final double HIT_LIFE_MIN = 0.25;
    public static final double HIT_LIFE_MAX = 0.5;

    /** 命中溅射的生成位置散布（格）：±0.15，聚在命中点周围。 */
    public static final double HIT_POSITION_SPREAD = 0.3;

    // ------------------------------------------------------------ M2.1：刷怪生成提示物

    /**
     * M2.1：刷怪生成提示物的存活时长（秒）。
     *
     * <p>1.5 与 HUD 刷怪提示的时长对齐（玩家先看到提示物、再读到文字，两者是同一次事件）。
     * 它不是"随便一个短时间"：见 {@link #SPAWN_CUE_SPEED_MIN}，整簇粒子在这段时间内
     * 恰好完成一条"升起 → 落回"的抛物线。
     */
    public static final double SPAWN_CUE_LIFE_SECONDS = 1.5;

    /**
     * M2.1：刷怪生成提示物的初速度区间（格/秒）。
     *
     * <p>粒子受共同重力 {@link #GRAVITY}（−16 格/秒²）。从初速 v 升空到落回起点用时
     * {@code 2v / 16}：取 v ∈ [8, 12] → 回程 1.0–1.5 秒，恰好落进
     * {@link #SPAWN_CUE_LIFE_SECONDS} 的寿命内 —— 于是这簇亮粒子是"升起 → 到顶 → 落回原点"
     * 的一条完整抛物线，<b>全程都停在落点附近</b>，把玩家的视线钉在那里。
     *
     * <p>为什么不取更小的速度：v 太小（例如 4）回程只有 0.5 秒，
     * 之后粒子会被重力拖到地面之下、被地形遮挡，提示物就"提前消失"了。
     */
    public static final double SPAWN_CUE_SPEED_MIN = 8.0;
    public static final double SPAWN_CUE_SPEED_MAX = 12.0;

    /** 提示物方向的竖直分量下限：接近竖直向上，形成一道"信标"而不是四散的碎屑。 */
    public static final double SPAWN_CUE_ELEVATION_MIN_SIN = 0.85;

    /** 提示物粒子尺寸（格）：比破坏碎屑更大，远距离也看得见。 */
    public static final double SPAWN_CUE_SIZE_MIN = 0.10;
    public static final double SPAWN_CUE_SIZE_MAX = 0.16;

    /** 提示物的生成位置散布（格）：±0.25，聚在落点周围，不溢出到相邻方块里。 */
    public static final double SPAWN_CUE_POSITION_SPREAD = 0.5;

    /** 拒绝采样的最大尝试次数：32 次全被拒的概率低于 2^-32，兜底返回 +X 保证必然终止。 */
    private static final int MAX_REJECTION_ATTEMPTS = 32;

    // ------------------------------------------------------------ 状态

    private final List<Particle> particles = new ArrayList<>();
    private final List<Tracer> tracers = new ArrayList<>();
    private final List<Flash> flashes = new ArrayList<>();

    /** 只读视图：一次性包好反复返回，避免每帧 {@code List.copyOf} 拷贝一份几百元素的列表。 */
    private final List<Particle> particleView = Collections.unmodifiableList(particles);
    private final List<Tracer> tracerView = Collections.unmodifiableList(tracers);
    private final List<Flash> flashView = Collections.unmodifiableList(flashes);

    /**
     * M2.1：命中标记的剩余寿命（秒）。
     *
     * <p><b>为什么这个"屏幕空间"的量住在"世界特效"的模型里：</b>
     * 它需要一个确定性的、能<u>不依赖窗口</u>被单测逐帧断言的生命周期载体，
     * 而这样的载体在本项目里只有这一处 —— {@code HudModel} 是纯数据传参包（不推进时间），
     * 放进 {@code SkyIslandGame} 则只能在带窗口的自测里取证。
     * 结论：本类做唯一的计时方，HUD 只镜像它的读数。
     */
    private double hitMarkerSeconds;

    /** 单位圆采样的复用缓冲（避免每次生成粒子都产生临时数组）。 */
    private final double[] circle = new double[2];

    private int totalBreakParticles;
    private int lastBreakParticleCount;
    private long totalSpawnCalls;
    /** M2.1：累计生成过的枪口闪光数（自测证据，不受 {@link #clear()} 影响）。 */
    private int totalMuzzleFlashes;
    /** M2.1：累计触发过的命中标记次数（自测证据）。 */
    private int totalHitMarkers;
    /** M2.1：累计生成过的实体命中粒子簇数（自测证据）。 */
    private int totalEntityHitBursts;
    /** M2.1：累计触发过的刷怪生成提示物次数（自测证据）。 */
    private int totalSpawnCues;

    // ============================================================ 生成

    /**
     * 破坏方块：在方块中心附近生成 8–12 个该方块颜色的粒子（PRD_v0.3.2 §5.2）。
     *
     * @param seed 父代理传入的确定性种子（"方块坐标 + 帧序号"的混合值）；
     *             同一 seed 必然得到同样的粒子数与同样的初速度
     */
    public void spawnBlockBreak(int blockX, int blockY, int blockZ,
                                float r, float g, float b, long seed) {
        Mulberry32 rng = new Mulberry32(seed);

        // 数量是第一抽样，保证"同 seed → 同数量"这件事与后续抽样顺序无关。
        int count = BREAK_PARTICLES_MIN
                + (int) Math.floor(rng.nextDouble() * (BREAK_PARTICLES_MAX - BREAK_PARTICLES_MIN + 1));
        count = clamp(count, BREAK_PARTICLES_MIN, BREAK_PARTICLES_MAX);

        double cx = blockX + 0.5;
        double cy = blockY + 0.5;
        double cz = blockZ + 0.5;

        for (int i = 0; i < count; i++) {
            // 抽样顺序（固定！改动它就是改动所有既有 seed 的表现）：
            // 竖直分量 → 单位圆（1–2 次）→ 速度 → 尺寸 → 寿命 → 位置 x/y/z
            double vertical = BREAK_ELEVATION_MIN_SIN
                    + (1.0 - BREAK_ELEVATION_MIN_SIN) * rng.nextDouble();
            sampleUnitCircle(rng);
            double horizontalRaw = Math.sqrt(Math.max(0.0, 1.0 - vertical * vertical));
            double horizontal = horizontalRaw * BREAK_HORIZONTAL_SQUASH;

            double dx = circle[0] * horizontal;
            double dy = vertical;
            double dz = circle[1] * horizontal;
            double norm = Math.sqrt(dx * dx + dy * dy + dz * dz);

            double speed = lerp(BREAK_SPEED_MIN, BREAK_SPEED_MAX, rng.nextDouble());
            double size = lerp(BREAK_SIZE_MIN, BREAK_SIZE_MAX, rng.nextDouble());
            double life = lerp(BREAK_LIFE_MIN, BREAK_LIFE_MAX, rng.nextDouble());

            double px = cx + jitter(rng.nextDouble(), BREAK_POSITION_SPREAD);
            double py = cy + jitter(rng.nextDouble(), BREAK_POSITION_SPREAD);
            double pz = cz + jitter(rng.nextDouble(), BREAK_POSITION_SPREAD);

            addParticle(new Particle(px, py, pz,
                    dx / norm * speed, dy / norm * speed, dz / norm * speed,
                    r, g, b, size, life, life));
        }

        totalBreakParticles += count;
        lastBreakParticleCount = count;
        totalSpawnCalls++;
    }

    /**
     * 命中方块：在命中点生成一簇溅射粒子，方向朝<b>世界上方</b>的半球。
     *
     * <p>这是"不知道命中面法线"时的便捷重载（父代理的射线结果里未必带法线）。
     * 知道法线时请用 {@link #spawnBlockHit(double, double, double,
     * double, double, double, float, float, float, long)}，
     * 沿法线向外的溅射才符合"打在墙上、碎屑朝自己这边飞"的直觉。
     */
    public void spawnBlockHit(double x, double y, double z,
                              float r, float g, float b, long seed) {
        spawnBlockHit(x, y, z, 0.0, 1.0, 0.0, r, g, b, seed);
    }

    /**
     * 命中方块：在命中点生成一簇溅射粒子，方向为<b>沿命中面法线向外</b>的半球。
     *
     * <p>"向外"= 与射线入射方向相反的一侧 = 玩家的方向。物理解释：
     * 碎屑被撞击后从表面反弹回来，所以它应当朝<b>法线正方向</b>散开，
     * 而不是穿进方块内部。法线全零时退化为世界上方，不抛异常 ——
     * 表现层不该因为一个退化输入让整个渲染循环中断。
     */
    public void spawnBlockHit(double x, double y, double z,
                              double nx, double ny, double nz,
                              float r, float g, float b, long seed) {
        spawnHitBurst(x, y, z, nx, ny, nz, r, g, b, seed, HIT_PARTICLES);
    }

    /**
     * M2.1：命中<b>实体</b> —— 在命中点生成一簇溅射粒子，方向朝世界上方半球。
     *
     * <p><b>为什么与 "命中方块" 共用同一套生成代码（{@link #spawnHitBurst}）：</b>
     * M2.1 的要求是"命中怪物要有 hit 粒子，且与 block break 粒子一致"。
     * 一致不是靠"看起来差不多"，而是靠<b>同一份确定性实现</b>：
     * 同一个 {@code seed} 在这里得到的粒子初速度与打中墙面时逐位相同，
     * 于是"溅射"在玩家眼里是同一种物理现象，而不是两套手感。
     * 差别只有数量（见 {@link #ENTITY_HIT_PARTICLES}）与颜色（由调用方给）。
     *
     * <p>法线取世界上方而不是"沿射线反向"：实体被击中时我们从射线结果里拿不到
     * 一个可信的表面法线（射线命中的是 AABB，面法线只有六个候选且常常与视觉轮廓不符），
     * 硬凑一个只会在某些角度显得正确；世界上方是唯一不撒谎的选择。
     */
    public void spawnEntityHit(double x, double y, double z,
                               float r, float g, float b, long seed) {
        spawnHitBurst(x, y, z, 0.0, 1.0, 0.0, r, g, b, seed, ENTITY_HIT_PARTICLES);
        totalEntityHitBursts++;
    }

    /**
     * M2.1：刷怪生成提示物 —— 在落点播一小簇亮色粒子，把玩家的视线引过去（任务 B）。
     *
     * <p><b>为什么走"粒子"而不是新造一种提示物：</b>本项目已有 {@link CombatFxRenderer}
     * 这一条世界空间叠加层链路（与破坏 / 命中粒子共用 {@link #MAX_PARTICLES} 容量、
     * 共用同一套 {@link #tick(double)} 生命周期）。提示物只是"同一簇粒子换一个颜色与方向"，
     * 为它新开一条几何 / 渲染 pass 既没有收益，也会多出一处必须同步维护的容量上限。
     *
     * <p>粒子数沿用 PRD §5.2 的 8–12 占位口径（与破坏粒子同一常量），
     * 方向接近竖直向上（见 {@link #SPAWN_CUE_ELEVATION_MIN_SIN}），
     * 初速见 {@link #SPAWN_CUE_SPEED_MIN}：整簇粒子在 {@link #SPAWN_CUE_LIFE_SECONDS}
     * 内完成一条"升起 → 落回"的抛物线，全程停在落点附近。
     *
     * @param seed 确定性种子（与其它特效同源，保证同一次运行里可复现）
     */
    public void spawnSpawnCue(double x, double y, double z,
                              float r, float g, float b, long seed) {
        Mulberry32 rng = new Mulberry32(seed);

        // 与 spawnBlockBreak 相同：数量是第一抽样，与后续抽样顺序无关。
        int count = BREAK_PARTICLES_MIN
                + (int) Math.floor(rng.nextDouble() * (BREAK_PARTICLES_MAX - BREAK_PARTICLES_MIN + 1));
        count = clamp(count, BREAK_PARTICLES_MIN, BREAK_PARTICLES_MAX);

        for (int i = 0; i < count; i++) {
            double vertical = SPAWN_CUE_ELEVATION_MIN_SIN
                    + (1.0 - SPAWN_CUE_ELEVATION_MIN_SIN) * rng.nextDouble();
            sampleUnitCircle(rng);
            double horizontalRaw = Math.sqrt(Math.max(0.0, 1.0 - vertical * vertical));
            // 水平分量收窄：提示物是一道竖直的"信标"，不是向四周炸开。
            double horizontal = horizontalRaw * BREAK_HORIZONTAL_SQUASH;

            double dx = circle[0] * horizontal;
            double dy = vertical;
            double dz = circle[1] * horizontal;
            double norm = Math.sqrt(dx * dx + dy * dy + dz * dz);

            double speed = lerp(SPAWN_CUE_SPEED_MIN, SPAWN_CUE_SPEED_MAX, rng.nextDouble());
            double size = lerp(SPAWN_CUE_SIZE_MIN, SPAWN_CUE_SIZE_MAX, rng.nextDouble());

            double px = x + jitter(rng.nextDouble(), SPAWN_CUE_POSITION_SPREAD);
            double py = y + jitter(rng.nextDouble(), SPAWN_CUE_POSITION_SPREAD);
            double pz = z + jitter(rng.nextDouble(), SPAWN_CUE_POSITION_SPREAD);

            addParticle(new Particle(px, py, pz,
                    dx / norm * speed, dy / norm * speed, dz / norm * speed,
                    r, g, b, size, SPAWN_CUE_LIFE_SECONDS, SPAWN_CUE_LIFE_SECONDS));
        }

        totalSpawnCues++;
        totalSpawnCalls++;
    }

    /**
     * M2.1：枪口闪光 —— 在给定的枪口世界坐标点亮一个短命的亮方块。
     *
     * <p><b>为什么是"世界空间的一个方块"而不是"屏幕空间的一张贴片"：</b>
     * 贴片需要一套新的屏幕 quad 管线（新的着色器 uniform 或新的 pass），
     * 而本项目已经有一套<u>被实践验证过</u>的世界空间叠加层写法
     * （见 {@link CombatFxRenderer} 的类注释）。更重要的是：方块会被地形正确遮挡 ——
     * 贴脸对着墙开枪时，火光自然被墙挡住一半，而贴片会整片糊在最上层，
     * 那正是"枪口 ModelIgnore 了世界"的廉价感来源。
     *
     * <p>这里<u>只存数据</u>：位置、尺寸、寿命都由 {@link #MUZZLE_FLASH_SIZE} 与
     * {@link #MUZZLE_FLASH_SECONDS} 给出，因此"闪了多久、多大"在无 GL 的单测里可读、可断言
     * （{@code CombatFxModelTest} 负责这一层）。
     *
     * <p><b>为什么还要收下眼睛与视线（缺陷 A）：</b>闪光只活 3 帧，自测在收尾（shutdown）
     * 才校验，届时 {@link #flashes()} 早已清空 —— "闪光到底生在哪、离眼睛多远"
     * 会变成不可观测的量，而缺陷 A 恰恰是"生在眼睛上却没有任何断言看得见"。
     * 因此把<u>生成当刻</u>的眼睛与视线一并留档，让"枪口是否真的离开了眼睛"
     * 成为一条可以事后断言、且与读取时刻无关的事实（见 {@link MuzzleFlashSample}）。
     *
     * <p>参数里的 {@code eyeX/eyeY/eyeZ} 是这一发的相机眼睛世界坐标（枪口必须离开它），
     * {@code forwardX/forwardY/forwardZ} 是这一发的视线方向（用于判定闪光在眼睛前方）。
     */
    public void spawnMuzzleFlash(double x, double y, double z,
                                 double eyeX, double eyeY, double eyeZ,
                                 double forwardX, double forwardY, double forwardZ) {
        addFlash(new Flash(x, y, z, MUZZLE_FLASH_SIZE, MUZZLE_FLASH_SECONDS));
        lastMuzzleFlashSample = new MuzzleFlashSample(
                x, y, z, eyeX, eyeY, eyeZ, forwardX, forwardY, forwardZ);
        totalMuzzleFlashes++;
        totalSpawnCalls++;
    }

    /** M2.1 缺陷 A：最近一次枪口闪光的留档（位置 + 生成当刻的眼睛与视线）。 */
    private MuzzleFlashSample lastMuzzleFlashSample;

    /**
     * M2.1 缺陷 A：一次枪口闪光的可事后断言的快照。
     *
     * <p>闪光寿命只有 3 帧，{@link #flashes()} 到不了自测的收尾时刻；因此这里
     * 把"闪光位置"与"生成当刻的眼睛、视线"一起留档。{@link #distanceFromEye()} 与
     * {@link #forwardDot()} 把"闪光不能生在眼睛上、且必须在眼睛前方"变成两个
     * 与读取时刻无关的纯函数 —— 这正是旧代码（闪光即眼睛）无法满足、也无人能断言的地方。
     */
    public record MuzzleFlashSample(double x, double y, double z,
                                    double eyeX, double eyeY, double eyeZ,
                                    double forwardX, double forwardY, double forwardZ) {

        /** 闪光到眼睛的距离（格）。缺陷 A 时它约为 0。 */
        public double distanceFromEye() {
            double dx = x - eyeX;
            double dy = y - eyeY;
            double dz = z - eyeZ;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /** 闪光相对眼睛的位移在视线方向上的投影（> 0 表示在眼睛前方）。 */
        public double forwardDot() {
            double dx = x - eyeX;
            double dy = y - eyeY;
            double dz = z - eyeZ;
            return dx * forwardX + dy * forwardY + dz * forwardZ;
        }
    }

    /** M2.1 缺陷 A：最近一次枪口闪光的留档；从未闪过返回 {@code null}。 */
    public MuzzleFlashSample lastMuzzleFlashSample() {
        return lastMuzzleFlashSample;
    }

    /**
     * M2.1：触发一次命中标记（准星的瞬时变化）。
     *
     * <p><b>重复命中 = 刷新而不是叠加</b>：直接把计时器写满。
     * 累加会让连打三发的标记强度达到 3，而渲染层只能按 0..1 解释，
     * 于是"连发时标记会卡在最大强度很久" —— 那看起来像准星坏了。
     * 每一次新的命中都应当把标记重置到"刚打中"这个唯一的状态。
     */
    public void spawnHitMarker() {
        hitMarkerSeconds = HIT_MARKER_SECONDS;
        totalHitMarkers++;
        totalSpawnCalls++;
    }

    /** 溅射粒子簇的唯一实现：{@code count} 个粒子，方向为法线朝外的半球。 */
    private void spawnHitBurst(double x, double y, double z,
                               double nx, double ny, double nz,
                               float r, float g, float b, long seed, int count) {
        Mulberry32 rng = new Mulberry32(seed);

        // 法线正交基：把"世界上方半球"整体旋转到"法线半球"。
        // 用叉积构造，只需要 +−×÷ 与 sqrt，因此仍是逐位可复现的。
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(nl > 1e-9)) {
            nx = 0.0; ny = 1.0; nz = 0.0; nl = 1.0;     // NaN 也走这里
        }
        double ux = nx / nl, uy = ny / nl, uz = nz / nl;
        double refX = Math.abs(uy) > 0.999 ? 1.0 : 0.0;
        double refY = Math.abs(uy) > 0.999 ? 0.0 : 1.0;
        // axisU = normalize(ref × n)：ref 是 (refX, refY, 0)
        double ax = refY * uz;
        double ay = -refX * uz;
        double az = refX * uy - refY * ux;
        double al = Math.sqrt(ax * ax + ay * ay + az * az);
        if (!(al > 1e-9)) {
            ax = 1.0; ay = 0.0; az = 0.0; al = 1.0;
        }
        ax /= al; ay /= al; az /= al;
        // axisV = n × axisU（与 axisU 一起构成右手基，满足 axisU × axisV = n）
        double vx = uy * az - uz * ay;
        double vy = uz * ax - ux * az;
        double vz = ux * ay - uy * ax;

        for (int i = 0; i < count; i++) {
            double vertical = HIT_ELEVATION_MIN_SIN
                    + (1.0 - HIT_ELEVATION_MIN_SIN) * rng.nextDouble();
            sampleUnitCircle(rng);
            double horizontalRaw = Math.sqrt(Math.max(0.0, 1.0 - vertical * vertical));
            double horizontal = horizontalRaw * HIT_HORIZONTAL_SQUASH;

            // 局部 (horizontal*ca, horizontal*cb, vertical) 映射到 (axisU, axisV, n)
            double dx = ax * circle[0] * horizontal + vx * circle[1] * horizontal + ux * vertical;
            double dy = ay * circle[0] * horizontal + vy * circle[1] * horizontal + uy * vertical;
            double dz = az * circle[0] * horizontal + vz * circle[1] * horizontal + uz * vertical;
            double norm = Math.sqrt(dx * dx + dy * dy + dz * dz);

            double speed = lerp(HIT_SPEED_MIN, HIT_SPEED_MAX, rng.nextDouble());
            double size = lerp(HIT_SIZE_MIN, HIT_SIZE_MAX, rng.nextDouble());
            double life = lerp(HIT_LIFE_MIN, HIT_LIFE_MAX, rng.nextDouble());

            double px = x + jitter(rng.nextDouble(), HIT_POSITION_SPREAD);
            double py = y + jitter(rng.nextDouble(), HIT_POSITION_SPREAD);
            double pz = z + jitter(rng.nextDouble(), HIT_POSITION_SPREAD);

            addParticle(new Particle(px, py, pz,
                    dx / norm * speed, dy / norm * speed, dz / norm * speed,
                    r, g, b, size, life, life));
        }

        totalSpawnCalls++;
    }

    /**
     * 曳光：从枪口到命中点（或最大射程点）的一条轨迹，存活 {@link #TRACER_SECONDS} 秒。
     *
     * <p>PRD §5.4.3 明确"瞬时命中，辅以一条持续 0.05 秒的曳光轨迹作视觉反馈"，
     * 所以曳光<b>不参与</b>命中判定，纯粹是事后补的视觉解释 ——
     * 这就解释了为什么它可以被独立地 spawn / 到点即除，而不需要与射线同步生命周期。
     */
    public void spawnTracer(double x1, double y1, double z1,
                            double x2, double y2, double z2) {
        addTracer(new Tracer(x1, y1, z1, x2, y2, z2, TRACER_SECONDS));
        totalSpawnCalls++;
    }

    // ============================================================ 推进

    /**
     * 推进一帧。
     *
     * <p><b>语义（被单测钉死）：</b>
     * <ul>
     *   <li>{@code dt} 为 0 / 负数 / NaN：<b>什么都不做</b>。直接减的话负 dt 会让寿命变长，
     *       NaN 会污染一切后续比较；早退比事后补救便宜。</li>
     *   <li>{@code dt} 过大：按 {@link #MAX_STEP_SECONDS} 截断（理由见该常量）。</li>
     *   <li>积分用半隐式欧拉（先更新速度再更新位置）：显式欧拉在大 dt 下会发散，
     *       半隐式是同类开销下唯一稳定的选择。</li>
     *   <li>移除条件是 {@code life <= 0}（含 -0.0），保证事后不存在负寿命残留项。</li>
     * </ul>
     */
    public void tick(double dt) {
        if (!(dt > 0.0)) {
            return;
        }
        double step = Math.min(dt, MAX_STEP_SECONDS);

        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            double life = p.life() - step;
            if (life <= 0.0) {
                particles.remove(i);
                continue;
            }
            double vy = p.vy() + GRAVITY * step;
            particles.set(i, new Particle(
                    p.x() + p.vx() * step,
                    p.y() + vy * step,
                    p.z() + p.vz() * step,
                    p.vx(), vy, p.vz(),
                    p.r(), p.g(), p.b(), p.size(), life, p.maxLife()));
        }

        for (int i = tracers.size() - 1; i >= 0; i--) {
            Tracer t = tracers.get(i);
            double life = t.life() - step;
            if (life <= 0.0) {
                tracers.remove(i);
            } else {
                tracers.set(i, new Tracer(t.x1(), t.y1(), t.z1(), t.x2(), t.y2(), t.z2(), life));
            }
        }

        // M2.1：枪口闪光与曳光同为"纯时间驱动的短命表现"，用同一套 {life <= 0 即除} 语义。
        for (int i = flashes.size() - 1; i >= 0; i--) {
            Flash f = flashes.get(i);
            double life = f.life() - step;
            if (life <= 0.0) {
                flashes.remove(i);
            } else {
                flashes.set(i, new Flash(f.x(), f.y(), f.z(), f.size(), life));
            }
        }

        // M2.1：命中标记的时间线由本类独占（理由见 hitMarkerSeconds 的注释）。
        // 同样 clamp 到 0 而不是留负值：HUD 会用它做长度插值，负值会让斜臂算出一个负 reach。
        if (hitMarkerSeconds > 0.0) {
            hitMarkerSeconds = Math.max(0.0, hitMarkerSeconds - step);
        }
    }

    /**
     * 清空当前所有粒子与曳光（例如世界重载、切场景）。
     *
     * <p><b>累计读数是刻意不重置的</b>：{@link #totalBreakParticles()} 与
     * {@link #totalSpawnCalls()} 是"整个会话累计生成过多少"的自测证据，
     * 一旦被 {@code clear()} 抹掉，"清空后又刷了一轮"这件事就看不出来了。
     * 需要归零累计值时请新建一个模型实例。
     */
    public void clear() {
        particles.clear();
        tracers.clear();
        flashes.clear();
        hitMarkerSeconds = 0.0;
        lastBreakParticleCount = 0;
    }

    // ============================================================ 读数

    /** 当前粒子列表（只读视图；不可 add/remove，写入会抛 UnsupportedOperationException）。 */
    public List<Particle> particles() {
        return particleView;
    }

    /** 当前曳光列表（只读视图）。 */
    public List<Tracer> tracers() {
        return tracerView;
    }

    /** M2.1：当前枪口闪光列表（只读视图）。 */
    public List<Flash> flashes() {
        return flashView;
    }

    public int particleCount() {
        return particles.size();
    }

    public int tracerCount() {
        return tracers.size();
    }

    /** M2.1：当前存活的枪口闪光数。 */
    public int flashCount() {
        return flashes.size();
    }

    /**
     * M2.1：命中标记的剩余强度 0..1（1 = 刚命中，0 = 不显示）。
     *
     * <p>给强度而不是给剩余秒数：渲染层要的是"混多少 / 画多长"，让它自己再除一遍
     * {@link #HIT_MARKER_SECONDS} 就等于把时长定义散到两处 —— 改时长时会漏改一处，
     * 症状是"标记看起来越来越短但没人动过它"。与 {@code Entity.hurtFlash01} 同口径。
     */
    public double hitMarker01() {
        if (hitMarkerSeconds <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, hitMarkerSeconds / HIT_MARKER_SECONDS);
    }

    /** 本会话累计生成过的破坏粒子数（自测断言用；不受 {@link #clear()} 影响）。 */
    public int totalBreakParticles() {
        return totalBreakParticles;
    }

    /** 最近一次 {@link #spawnBlockBreak} 生成的数量（自测断言用）。 */
    public int lastBreakParticleCount() {
        return lastBreakParticleCount;
    }

    /** 累计 spawn 调用次数（破坏 + 命中 + 曳光 + 枪口闪光 + 命中标记 + 刷怪提示物，自测断言用）。 */
    public long totalSpawnCalls() {
        return totalSpawnCalls;
    }

    /** M2.1：累计生成过的枪口闪光数（{=}每发实弹一次，自测断言用）。 */
    public int totalMuzzleFlashes() {
        return totalMuzzleFlashes;
    }

    /** M2.1：累计触发过的命中标记次数（{=}命中实体的次数，自测断言用）。 */
    public int totalHitMarkers() {
        return totalHitMarkers;
    }

    /** M2.1：累计生成过的实体命中粒子簇数（自测断言用）。 */
    public int totalEntityHitBursts() {
        return totalEntityHitBursts;
    }

    /** M2.1：累计触发过的刷怪生成提示物次数（自测断言用）。 */
    public int totalSpawnCues() {
        return totalSpawnCues;
    }

    // ============================================================ 内部

    private void addParticle(Particle p) {
        if (particles.size() >= MAX_PARTICLES) {
            // 位置 0 是最旧的：ArrayList.remove(0) 是 O(n)，但只在满载时发生，
            // 而 n ≤ 512，与其引入环形缓冲把它降到 O(1)，代价是索引与只读视图都变复杂。
            particles.remove(0);
        }
        particles.add(p);
    }

    private void addTracer(Tracer t) {
        if (tracers.size() >= MAX_TRACERS) {
            tracers.remove(0);
        }
        tracers.add(t);
    }

    /** M2.1：满载时同样丢弃最旧的一条（理由与 addTracer 完全一致）。 */
    private void addFlash(Flash f) {
        if (flashes.size() >= MAX_FLASHES) {
            flashes.remove(0);
        }
        flashes.add(f);
    }

    /** 在单位圆上均匀取一个方向，结果写入 {@link #circle}。只用 +−×÷ 与 sqrt。 */
    private void sampleUnitCircle(Mulberry32 rng) {
        for (int attempt = 0; attempt < MAX_REJECTION_ATTEMPTS; attempt++) {
            double a = rng.nextDouble() * 2.0 - 1.0;
            double b = rng.nextDouble() * 2.0 - 1.0;
            double d2 = a * a + b * b;
            if (d2 <= 1.0 && d2 > 1e-12) {
                double inv = 1.0 / Math.sqrt(d2);
                circle[0] = a * inv;
                circle[1] = b * inv;
                return;
            }
        }
        circle[0] = 1.0;    // 兜底：不偏向任何方向即可，绝不能返回 0 向量（后面要除以模长）
        circle[1] = 0.0;
    }

    /** 把 [0,1) 的随机数映射成 ±{@code spread/2} 的偏移。 */
    private static double jitter(double unit, double spread) {
        return (unit - 0.5) * spread;
    }

    private static double lerp(double lo, double hi, double unit) {
        return lo + (hi - lo) * unit;
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    // ============================================================ 数据结构

    /**
     * 一个粒子：位置、速度、颜色、边长、剩余寿命与总寿命。
     *
     * <p>用 {@code record} 而不是可变对象：{@link #tick(double)} 每次只替换被推进的那几个，
     * 其余实例可原样复用，避免了"帧中间被观测到半更新状态"的可能，
     * 也让 {@link #particles()} 的只读视图真的只读（record 无 setter）。
     * {@code maxLife} 单独留存是为了让渲染层以后能按 {@code life/maxLife} 做淡出。
     */
    public record Particle(double x, double y, double z,
                           double vx, double vy, double vz,
                           float r, float g, float b,
                           double size, double life, double maxLife) {
    }

    /** 一条曳光：两个端点的世界坐标 + 剩余寿命。 */
    public record Tracer(double x1, double y1, double z1,
                         double x2, double y2, double z2, double life) {
    }

    /**
     * M2.1：一发子弹的枪口闪光 —— 枪口世界坐标 + 立方体边长 + 剩余寿命。
     *
     * <p>用 {@code record} 且与 {@link Tracer} 同构：只读、可比，
     * 且让"这一帧的闪光"在单测里可以逐字段断言（不受渲染环境影响）。
     */
    public record Flash(double x, double y, double z, double size, double life) {
    }

    /**
     * mulberry32：32 位整数状态的确定性 PRNG。
     *
     * <p>选它而不是 {@code java.util.Random} 的理由：{@code Random} 的算法虽也确定，
     * 但它的公开契约不保证跨 JDK 版本一致，而本类需要"同一 seed 的结果永远不变"
     * 作为单测与现实表现之间的<b>契约</b>。自带 10 行实现就把这个契约攥在自己手里。
     */
    private static final class Mulberry32 {
        private int state;

        Mulberry32(long seed) {
            // 先做一次雪崩：同一方块的相邻帧序号（seed 只差 1）否则会产生高度相似的序列，
            // 表现为"每一帧的碎屑形状几乎一样"。
            int s = (int) seed ^ (int) (seed >>> 32);
            s ^= s >>> 16;
            s *= 0x7feb352d;
            s ^= s >>> 15;
            s *= 0x846ca68b;
            s ^= s >>> 16;
            state = s;
        }

        /** [0,1) 的双精度随机数：无符号整数除法，逐位可复现。 */
        double nextDouble() {
            state += 0x6D2B79F5;
            int t = state;
            t = (t ^ (t >>> 15)) * (t | 1);
            t ^= t + (t ^ (t >>> 7)) * (t | 61);
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }
    }
}

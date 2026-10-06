package com.skyisland.render.fx;

import com.skyisland.player.Camera;
import com.skyisland.render.VertexFormat;
import com.skyisland.render.mesh.BlockTextureLayers;
import com.skyisland.render.shader.ShaderProgram;
import com.skyisland.util.Log;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * 战斗表现层的 GL 绘制层：粒子（小立方体）与曳光（细长盒体）。
 *
 * <h2>它照抄的是谁</h2>
 * 本类是 {@code com.skyisland.render.mesh.CrackOverlay} 的同构兄弟：同样是
 * "自带 VBO/VAO、用同一个 voxel shader、在世界 pass 里画在区块之后、被地形正确遮挡"
 * 的世界空间叠加层。结构、状态管理、顶点格式（{@code aPos vec3 + aColor vec4} = 7 float）
 * 都与它保持一致 —— 本项目已经有了一套世界空间叠加层的写法，战斗特效没有理由另创一套。
 *
 * <p>三处必要的偏离，逐条在这里交代清楚：
 * <ol>
 *   <li><b>不用 {@code MemoryStack}</b>：{@code CrackOverlay.render} 也没有这个参数，
 *       矩阵上传走 {@link ShaderProgram#setMatrix4f(String, org.joml.Matrix4fc)}，
 *       它内部自己压栈。多一个形参只会让调用点多一个必须传对的东西。</li>
 *   <li><b>要保存/恢复 GL 状态</b>：{@code CrackOverlay} 把 {@code GL_CULL_FACE}
 *       关掉之后没有恢复，于是它之后的每一次区块绘制都失去背面剔除。
 *       本类不重复这个坑 —— 进入前读一遍被改动的状态，退出时原样写回，
 *       保证 HUD pass 与下一帧的世界 pass 拿到的是自己期望的状态（M1 已记录过脏状态问题）。</li>
 *   <li><b>不做淡出</b>：{@code voxel.frag} 的透明度是全局 uniform {@code uAlpha}，
 *       不支持逐顶点 alpha。把淡出烘进 {@code vColor.rgb} 只会让粒子<b>变黑</b>而不是变淡
 *       （片元里 {@code fragColor.rgb = vColor.rgb * vColor.a}，a 是预乘明暗不是透明度）。
 *       为此拆批次按寿命分档得不偿失：粒子寿命不到 1 秒，PRD 也没要求淡出。
 *       正式粒子管线（Alpha 阶段）再接淡出与碰撞。</li>
 * </ol>
 *
 * <h2>顶点预算</h2>
 * 每个立方体 6 个面 × 2 个三角形 × 3 顶点 = 36 顶点。不用 EBO 是刻意的：
 * 顶点数上限是静态已知的（见 {@link CombatFxModel#MAX_PARTICLES}），
 * 而 {@code glDrawArrays} 少一次绑定与索引上传，与 {@code CrackOverlay} 的取向一致。
 * 面的绕序一律保证"从外侧看是 CCW"（{@code u × v = 面法线}），
 * 因此即使外部状态恰好是开着剔除也不会把面剔掉。
 */
public final class CombatFxRenderer {

    /** 顶点格式与 {@code voxel.vert} 一致：aPos(vec3) + aColor(vec4) + aLayerAo(vec2)。 */
    private static final int FLOATS_PER_VERTEX = VertexFormat.FLOATS_PER_VERTEX;

    /** 每面 2 个三角形 = 6 个顶点。 */
    private static final int VERTS_PER_FACE = 6;

    /**
     * 本渲染器实际使用的每顶点 float 数（<b>供跨类一致性守卫读取</b>）。
     *
     * <p>与 {@code CrackOverlay.vertexFloatsPerVertex()} 同理：
     * 守卫必须能读到每个消费方的真实值，而不是靠扫源码猜。
     */
    public static int vertexFloatsPerVertex() {
        return FLOATS_PER_VERTEX;
    }

    /** 每立方体 6 个面。 */
    private static final int FACES_PER_BOX = 6;

    /** 每立方体顶点数。 */
    private static final int VERTS_PER_BOX = FACES_PER_BOX * VERTS_PER_FACE;

    /** 粒子顶点缓冲容量（float 个数），与 {@link CombatFxModel#MAX_PARTICLES} 对齐。 */
    public static final int PARTICLE_CAPACITY_FLOATS =
            CombatFxModel.MAX_PARTICLES * VERTS_PER_BOX * FLOATS_PER_VERTEX;

    /** 曳光顶点缓冲容量（float 个数），与 {@link CombatFxModel#MAX_TRACERS} 对齐。 */
    public static final int TRACER_CAPACITY_FLOATS =
            CombatFxModel.MAX_TRACERS * VERTS_PER_BOX * FLOATS_PER_VERTEX;

    /** M2.1：枪口闪光顶点缓冲容量（float 个数），与 {@link CombatFxModel#MAX_FLASHES} 对齐。 */
    public static final int FLASH_CAPACITY_FLOATS =
            CombatFxModel.MAX_FLASHES * VERTS_PER_BOX * FLOATS_PER_VERTEX;

    /**
     * M2.1：曳光盒体的横截面半边长（格）。<b>0.040 = 8 厘米粗的一道光。</b>
     *
     * <p>从 M2 的 0.015 加粗到 0.040 的依据是"它必须占到一个以上的像素，且维持在 <2% 视角"：
     * 在 3 格距离、70° FOV 下，竖直视野跨度为 {@code 2 × 3 × tan35° ≈ 4.2 格}，
     * 0.08 格的直径约占视野的 1.9%（≈ 13 px @720p）—— 一眼可见，又不至于变成一根柱子。
     * 而旧的 0.015 只有 0.7%（≈ 5 px 且边缘被 alpha 吃掉一半），
     * 实际表现是"知道有曳光、看不见它在哪"。
     */
    public static final float TRACER_HALF_WIDTH = 0.040f;

    /** 曳光的颜色：偏暖的亮黄白，与天空的冷蓝形成对比，在草地与石头上都读得出来。 */
    private static final float TRACER_R = 1.00f;
    private static final float TRACER_G = 0.97f;
    private static final float TRACER_B = 0.80f;

    /**
     * 曳光的不透明度。
     *
     * <p>M2.1 从 0.55 提到 0.85。<b>为什么不是直接拉到 1.0：</b>
     * 曳光是"事后补的视觉解释"（PRD §5.4.3 说命中是瞬时的），
     * 全不透明的一道白光会像一根实体激光棒留在画面里；
     * 而 0.55 在加粗之前尚可、加粗之后会因为"面积 × 透明度"的总墨量不足
     * 在明亮草地上几乎消失。0.85 是"看清它是一条朝远处的线"与"它是一闪而过"之间的折中。
     */
    public static final float TRACER_ALPHA = 0.85f;

    // ------------------------------------------------------------ M2.1：枪口闪光

    /**
     * 枪口闪光的颜色：暖橙的火星色，而不是接近纯白。
     *
     * <p>M2.1 缺陷 A：原本取 (1.00, 0.95, 0.72) —— 接近纯白、只略偏暖。
     * 在"火光贴在屏幕上、盖住准星"的旧行为下，它糊成一块刺眼的白。
     * 改成 (1.00, 0.84, 0.52)：明显偏橙，既能读出"这是火花"，
     * 又不会把中心区域照成白色。火焰仍是画面里最亮的东西，只是不再是白光。
     */
    private static final float FLASH_R = 1.00f;
    private static final float FLASH_G = 0.84f;
    private static final float FLASH_B = 0.52f;

    /**
     * 枪口闪光的不透明度：0.55。
     *
     * <p><b>缺陷 A 修正：</b>原本是 0.90，"比曳光更实"在当时是刻意的。
     * 但那是在光斑<u>不会盖住准星</u>的前提下的判断；缺陷 A 里光斑生在眼睛处、
     * 直接糊住屏幕中心，0.90 的实度让它变成刺眼的白。降到 0.55 后，
     * 即使光斑偶尔扫过准星附近，也仍能透过它看见准星 —— 保住
     * "能不能看见准星"这条 Readability 底线。它依然比曳光（0.85）更透，
     * 但"面光源把背景压过去"的意图不变：0.55 在明亮草地上依然读得出来。
     */
    public static final float FLASH_ALPHA = 0.55f;

    /**
     * 立方体的四个角在 (a,b) 平面上的偏移（单位量，实际乘半边长的分量）。
     * 顺序 (-1,-1) → (1,-1) → (1,1) → (-1,1) 是 (a,b) 平面上的逆时针。
     */
    private static final float[] CORNER_A = {-1f, 1f, 1f, -1f};
    private static final float[] CORNER_B = {-1f, -1f, 1f, 1f};

    /** 四边形拆成两个三角形：与 {@code CrackOverlay} 的发射顺序一致。 */
    private static final int[] QUAD_ORDER = {0, 1, 2, 0, 2, 3};

    /** 曳光长度下限（格）：短于此视为退化输入，不生成几何（也避免除以零）。 */
    private static final double MIN_TRACER_LENGTH = 1e-6;

    private final float[] particleScratch = new float[PARTICLE_CAPACITY_FLOATS];
    private final float[] tracerScratch = new float[TRACER_CAPACITY_FLOATS];
    private final float[] flashScratch = new float[FLASH_CAPACITY_FLOATS];

    private int particleVao;
    private int particleVbo;
    private int tracerVao;
    private int tracerVbo;
    private int flashVao;
    private int flashVbo;
    private FloatBuffer particleStaging;
    private FloatBuffer tracerStaging;
    private FloatBuffer flashStaging;

    private int lastParticleQuads;
    private int lastTracerQuads;
    private int lastFlashQuads;
    private long drawCount;

    // ============================================================ 生命周期

    /**
     * 分配三组暂存缓冲（纯内存，不需要 GL 上下文）。
     *
     * <p>与 {@link #init()} 分开是刻意的：{@code init()} 里混着 {@code glGenVertexArrays}
     * 这类必须有当前 GL 上下文才能跑的调用，导致"三组缓冲是否都分配了"这件事在无头
     * 单测里<b>无法断言</b>。而 M2.1 恰好就在这里翻过车 —— 加入枪口闪光那一组时把曳光
     * 的分配整段顶掉了，{@code tracerStaging} 保持 null，直到第一次开火才在
     * {@code upload()} 里抛 NPE（堆栈指向的是渲染循环，离真正的原因十万八千里）。
     *
     * <p>拆出来之后，{@code CombatFxRendererLifecycleTest} 能在无 GL 环境下直接断言
     * "有几组特效，就有几组缓冲"，把这类"少分配一组"的编辑事故挡在门禁之前。
     */
    void allocateStagingBuffers() {
        particleStaging = MemoryUtil.memAllocFloat(PARTICLE_CAPACITY_FLOATS);
        tracerStaging = MemoryUtil.memAllocFloat(TRACER_CAPACITY_FLOATS);
        flashStaging = MemoryUtil.memAllocFloat(FLASH_CAPACITY_FLOATS);
    }

    public void init() {
        allocateStagingBuffers();

        particleVao = GL30.glGenVertexArrays();
        particleVbo = GL15.glGenBuffers();
        tracerVao = GL30.glGenVertexArrays();
        tracerVbo = GL15.glGenBuffers();
        flashVao = GL30.glGenVertexArrays();
        flashVbo = GL15.glGenBuffers();

        initBuffer(particleVao, particleVbo, PARTICLE_CAPACITY_FLOATS);
        initBuffer(tracerVao, tracerVbo, TRACER_CAPACITY_FLOATS);
        initBuffer(flashVao, flashVbo, FLASH_CAPACITY_FLOATS);

        Log.info("[战斗特效] 叠加层已就绪（粒子容量 %d，曳光容量 %d，枪口闪光容量 %d，各 %d 顶点/个）",
                CombatFxModel.MAX_PARTICLES, CombatFxModel.MAX_TRACERS,
                CombatFxModel.MAX_FLASHES, VERTS_PER_BOX);
    }

    private static void initBuffer(int vao, int vbo, int capacityFloats) {
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) capacityFloats * Float.BYTES,
                GL15.GL_STREAM_DRAW);

        // 三个属性槽位统一由 VertexFormat 绑定（粒子与地形/实体/裂纹/手持物共用 voxelShader）。
        VertexFormat.bindVoxelAttribs();

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    public void dispose() {
        if (particleVbo != 0) {
            GL15.glDeleteBuffers(particleVbo);
            particleVbo = 0;
        }
        if (particleVao != 0) {
            GL30.glDeleteVertexArrays(particleVao);
            particleVao = 0;
        }
        if (tracerVbo != 0) {
            GL15.glDeleteBuffers(tracerVbo);
            tracerVbo = 0;
        }
        if (tracerVao != 0) {
            GL30.glDeleteVertexArrays(tracerVao);
            tracerVao = 0;
        }
        if (flashVbo != 0) {
            GL15.glDeleteBuffers(flashVbo);
            flashVbo = 0;
        }
        if (flashVao != 0) {
            GL30.glDeleteVertexArrays(flashVao);
            flashVao = 0;
        }
        freeStagingBuffers();
    }

    /**
     * 释放三组暂存缓冲（纯内存，不需要 GL 上下文），并把字段置回 null。
     *
     * <p>与 {@link #allocateStagingBuffers()} 成对：分配与释放各自收敛到一处，
     * 于是"加了一组特效却忘了分配"与"忘了释放"这两件事都能被
     * {@code CombatFxRendererLifecycleTest} 用同一面镜子照出来 ——
     * 而这正是 M2.1 真实翻过的那个跟头（见 {@code allocateStagingBuffers} 的注释）。
     */
    void freeStagingBuffers() {
        if (particleStaging != null) {
            MemoryUtil.memFree(particleStaging);
            particleStaging = null;
        }
        if (tracerStaging != null) {
            MemoryUtil.memFree(tracerStaging);
            tracerStaging = null;
        }
        if (flashStaging != null) {
            MemoryUtil.memFree(flashStaging);
            flashStaging = null;
        }
    }

    // ============================================================ 每帧

    /**
     * 在世界 pass 内、区块之后绘制粒子与曳光。顺序由 {@code Renderer} 统一安排
     * （与 {@code CrackOverlay} 同级）。
     *
     * <p>本方法自己绑定着色器、矩阵与顶点数组：它跑在 {@code ChunkRenderer} 之后，
     * 后者在 pass 结束时已经解绑了程序。让本方法自理，是为了避免
     * "上一个 pass 恰好在某条提前返回的路径上忘了解绑"这类隐式耦合。
     *
     * @param model 可为 null 或空 —— 此时不产生任何 GL 调用（提前返回，
     *              连着色器都不绑），保证"没有特效"时不付任何代价
     */
    public void render(ShaderProgram shader, Camera camera, CombatFxModel model) {
        lastParticleQuads = 0;
        lastTracerQuads = 0;
        lastFlashQuads = 0;
        if (shader == null || camera == null || model == null) {
            return;
        }
        if (model.particleCount() == 0 && model.tracerCount() == 0 && model.flashCount() == 0) {
            return;
        }

        // 世界偏移：与 CrackOverlay / voxel.vert 同口径 —— x/z 走 uniform（整数，精确），
        // y 直接是绝对高度（着色器不给 y 加偏移）。
        // 原点取相机所在的整数格：粒子寿命不到 1 秒、速度不到 4 格/秒，
        // 恒在相机附近几格内，于是顶点里的 x/z 只有个位数大小，float 精度绰绰有余。
        float originX = (float) Math.floor(camera.x());
        float originZ = (float) Math.floor(camera.z());

        int particleFloats = buildParticles(model, originX, originZ);
        int tracerFloats = buildTracers(model, originX, originZ);
        int flashFloats = buildFlashes(model, originX, originZ);
        if (particleFloats == 0 && tracerFloats == 0 && flashFloats == 0) {
            return;
        }

        // ---- 保存将被改动的状态（理由见类注释第 2 条）----
        boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        shader.bind();
        shader.setMatrix4f("uProjection", camera.projectionMatrix());
        shader.setMatrix4f("uView", camera.viewMatrix());
        shader.setVec2f("uChunkOffset", originX, originZ);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        // 关剔除是刻意的：立方体的绕序已经保证从外侧看是 CCW（单测钉死了这个约定），
        // 但关掉剔除等于再买一道保险 —— 绕序写反时开着剔除的症状是"完全看不见"，
        // 那是最难归因的一类 bug，而代价只是每个粒子多画 3 个面（边长 0.06~0.10 格，
        // 屏幕覆盖不过几十像素，过绘制可以忽略）。
        GL11.glDisable(GL11.GL_CULL_FACE);

        // ---- 粒子：不透明碎屑，写深度（后来的曳光与几何才会被它正确遮挡）----
        if (particleFloats > 0) {
            upload(particleVbo, particleStaging, particleScratch, particleFloats);
            shader.setFloat("uAlpha", 1.0f);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDepthMask(true);

            GL30.glBindVertexArray(particleVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, particleFloats / FLOATS_PER_VERTEX);
            GL30.glBindVertexArray(0);

            lastParticleQuads = particleFloats / (VERTS_PER_FACE * FLOATS_PER_VERTEX);
        }

        // ---- 曳光：半透明，不写深度（透明物体写深度会把后面的特效挖空）----
        if (tracerFloats > 0) {
            upload(tracerVbo, tracerStaging, tracerScratch, tracerFloats);
            shader.setFloat("uAlpha", TRACER_ALPHA);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);

            GL30.glBindVertexArray(tracerVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, tracerFloats / FLOATS_PER_VERTEX);
            GL30.glBindVertexArray(0);

            lastTracerQuads = tracerFloats / (VERTS_PER_FACE * FLOATS_PER_VERTEX);
        }

        /*
         * ---- M2.1：枪口闪光 ----
         * 画在曳光之后：枪口点在眼睛前方 MUZZLE_FORWARD（0.55）格、并向右下偏移
         * （见 SkyIslandGame 的 MUZZLE_* 常量），是近处的一层 —— 任何在它之后画的半透明
         * 东西都会与它的深度关系纠缠不清。自身不写深度，避免把后面的曳光挖空。
         */
        if (flashFloats > 0) {
            upload(flashVbo, flashStaging, flashScratch, flashFloats);
            shader.setFloat("uAlpha", FLASH_ALPHA);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);

            GL30.glBindVertexArray(flashVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, flashFloats / FLOATS_PER_VERTEX);
            GL30.glBindVertexArray(0);

            lastFlashQuads = flashFloats / (VERTS_PER_FACE * FLOATS_PER_VERTEX);
        }

        // ---- 原样写回 ----
        GL11.glDepthMask(prevDepthMask);
        if (prevBlend) {
            GL11.glEnable(GL11.GL_BLEND);
        } else {
            GL11.glDisable(GL11.GL_BLEND);
        }
        if (prevCull) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        ShaderProgram.unbind();

        drawCount++;
    }

    private void upload(int vbo, FloatBuffer staging, float[] source, int floats) {
        staging.clear();
        staging.put(source, 0, floats);
        staging.flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, staging);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    // ============================================================ 顶点生成（纯几何）

    /**
     * 把模型里的粒子全部展开成立方体顶点。
     *
     * @return 写入的 float 个数（0 表示没有几何）
     */
    private int buildParticles(CombatFxModel model, float originX, float originZ) {
        int w = 0;
        for (CombatFxModel.Particle p : model.particles()) {
            // 容量对齐检查：正常情况下永远进不来（模型自己就卡在 MAX_PARTICLES），
            // 但两处上限一旦失去同步，宁可这一帧少画一个粒子，
            // 也不要抛 ArrayIndexOutOfBoundsException 把整个渲染循环打断。
            if (w + VERTS_PER_BOX * FLOATS_PER_VERTEX > particleScratch.length) {
                break;
            }
            double half = p.size() * 0.5;
            w = emitCube(particleScratch, w,
                    p.x() - originX, p.y(), p.z() - originZ, half,
                    p.r(), p.g(), p.b());
        }
        return w;
    }

    /** 把模型里的曳光全部展开成细长盒体顶点。 */
    private int buildTracers(CombatFxModel model, float originX, float originZ) {
        int w = 0;
        for (CombatFxModel.Tracer t : model.tracers()) {
            if (w + VERTS_PER_BOX * FLOATS_PER_VERTEX > tracerScratch.length) {
                break;
            }
            w = emitTracer(tracerScratch, w, t, originX, originZ);
        }
        return w;
    }

    /**
     * M2.1：把模型里的枪口闪光全部展开成立方体顶点。
     *
     * <p>直接复用 {@link #emitCube}：闪光就是一个"不参与物理、很短命、很亮的碎屑"，
     * 与粒子在几何上完全同构。为它单独写一套发射器只会多出一处需要同步维护的副本。
     */
    private int buildFlashes(CombatFxModel model, float originX, float originZ) {
        int w = 0;
        for (CombatFxModel.Flash f : model.flashes()) {
            if (w + VERTS_PER_BOX * FLOATS_PER_VERTEX > flashScratch.length) {
                break;
            }
            w = emitCube(flashScratch, w,
                    f.x() - originX, f.y(), f.z() - originZ, f.size() * 0.5,
                    FLASH_R, FLASH_G, FLASH_B);
        }
        return w;
    }

    /**
     * 生成一个立方体的 36 个顶点（6 面 × 6 顶点）。
     *
     * @param cx,cy,cz 中心。x/z 需已减去世界原点，y 是绝对高度
     * @param half     半边长
     */
    private static int emitCube(float[] out, int w, double cx, double cy, double cz,
                                double half, float r, float g, float b) {
        float h = (float) half;
        float x = (float) cx;
        float y = (float) cy;
        float z = (float) cz;

        // 每个面用一组 (u, v) 描述，满足 u × v = 该面的外法线 ——
        // 这是"从外侧看是 CCW"的唯一保证，改任何一个都要重新验证叉积。
        w = emitQuad(out, w, x + h, y, z, 0f, 1f, 0f, 0f, 0f, 1f, h, h, r, g, b);  // +X
        w = emitQuad(out, w, x - h, y, z, 0f, 0f, 1f, 0f, 1f, 0f, h, h, r, g, b);  // -X
        w = emitQuad(out, w, x, y + h, z, 0f, 0f, 1f, 1f, 0f, 0f, h, h, r, g, b);  // +Y
        w = emitQuad(out, w, x, y - h, z, 1f, 0f, 0f, 0f, 0f, 1f, h, h, r, g, b);  // -Y
        w = emitQuad(out, w, x, y, z + h, 1f, 0f, 0f, 0f, 1f, 0f, h, h, r, g, b);  // +Z
        return emitQuad(out, w, x, y, z - h, 0f, 1f, 0f, 1f, 0f, 0f, h, h, r, g, b);  // -Z
    }

    /**
     * 生成一条曳光：沿两端点连线的细长盒体。
     *
     * <p>用法向基 (e1, u, v) 表达六个面，e1 为盒体长轴（从起点指向终点）。
     * e1 与 u 正交、v = e1 × u，于是 (e1, u, v) 是右手基，
     * 六个面的 u × v 分别等于各自的外法线（推导见下方注释）。
     *
     * @return 写入的 float 个数；两端点重合时返回原值（不生成几何）
     */
    private static int emitTracer(float[] out, int w, CombatFxModel.Tracer t,
                                  float originX, float originZ) {
        double dx = t.x2() - t.x1();
        double dy = t.y2() - t.y1();
        double dz = t.z2() - t.z1();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > MIN_TRACER_LENGTH)) {
            return w;   // 退化输入：命中点与枪口重合（例如贴脸挖方块）时不画
        }

        double ex = dx / len, ey = dy / len, ez = dz / len;

        // u = normalize(ref × e1)。ref 换成 (1,0,0) 是为了避开 e1 ∥ Y 时的退化叉积。
        double refX = Math.abs(ey) > 0.99 ? 1.0 : 0.0;
        double refY = Math.abs(ey) > 0.99 ? 0.0 : 1.0;
        double ux = refY * ez;
        double uy = -refX * ez;
        double uz = refX * ey - refY * ex;
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (!(ul > 1e-9)) {
            ux = 1.0; uy = 0.0; uz = 0.0; ul = 1.0;
        }
        ux /= ul; uy /= ul; uz /= ul;

        // v = e1 × u  →  满足 (e1, u, v) 右手：u × v = e1
        double vx = ey * uz - ez * uy;
        double vy = ez * ux - ex * uz;
        double vz = ex * uy - ey * ux;

        float ox = (float) ((t.x1() + t.x2()) * 0.5 - originX);
        float oy = (float) ((t.y1() + t.y2()) * 0.5);
        float oz = (float) ((t.z1() + t.z2()) * 0.5 - originZ);

        float hl = (float) (len * 0.5);
        float hw = TRACER_HALF_WIDTH;
        float r = TRACER_R, g = TRACER_G, b = TRACER_B;

        // 六个面的 (外法线方向, 面内基底, 两个方向的半长)：
        //   +e1 / -e1 : 基底 (u,v) / (v,u)   —— 横截面是正方形，两边都是 hw
        //   +u  / -u  : 基底 (v,e1) / (e1,v) —— 沿 e1 的半长是 hl
        //   +v  / -v  : 基底 (e1,u) / (u,e1)
        w = emitQuad(out, w, ox + (float) (ex * hl), oy + (float) (ey * hl), oz + (float) (ez * hl),
                (float) ux, (float) uy, (float) uz, (float) vx, (float) vy, (float) vz,
                hw, hw, r, g, b);
        w = emitQuad(out, w, ox - (float) (ex * hl), oy - (float) (ey * hl), oz - (float) (ez * hl),
                (float) vx, (float) vy, (float) vz, (float) ux, (float) uy, (float) uz,
                hw, hw, r, g, b);
        w = emitQuad(out, w, ox + (float) (ux * hw), oy + (float) (uy * hw), oz + (float) (uz * hw),
                (float) vx, (float) vy, (float) vz, (float) ex, (float) ey, (float) ez,
                hw, hl, r, g, b);
        w = emitQuad(out, w, ox - (float) (ux * hw), oy - (float) (uy * hw), oz - (float) (uz * hw),
                (float) ex, (float) ey, (float) ez, (float) vx, (float) vy, (float) vz,
                hl, hw, r, g, b);
        w = emitQuad(out, w, ox + (float) (vx * hw), oy + (float) (vy * hw), oz + (float) (vz * hw),
                (float) ex, (float) ey, (float) ez, (float) ux, (float) uy, (float) uz,
                hl, hw, r, g, b);
        return emitQuad(out, w, ox - (float) (vx * hw), oy - (float) (vy * hw), oz - (float) (vz * hw),
                (float) ux, (float) uy, (float) uz, (float) ex, (float) ey, (float) ez,
                hw, hl, r, g, b);
    }

    /**
     * 生成一个四边形（两个三角形）的顶点，顶点格式与 {@code voxel.vert} 一致。
     *
     * <p>四个角为 {@code origin ± hu·u ± hv·v}，按 (a,b) 平面逆时针取序；
     * 因为调用方保证 {@code u × v =} 外法线，所以从面外看是 CCW。
     *
     * @param hu 沿 u 方向的半长
     * @param hv 沿 v 方向的半长
     * @return 新的写入位置
     */
    private static int emitQuad(float[] out, int w,
                                float ox, float oy, float oz,
                                float ux, float uy, float uz,
                                float vx, float vy, float vz,
                                float hu, float hv,
                                float r, float g, float b) {
        for (int k = 0; k < QUAD_ORDER.length; k++) {
            int c = QUAD_ORDER[k];
            float a = CORNER_A[c] * hu;
            float bb = CORNER_B[c] * hv;
            out[w++] = ox + ux * a + vx * bb;
            out[w++] = oy + uy * a + vy * bb;
            out[w++] = oz + uz * a + vz * bb;
            out[w++] = r;
            out[w++] = g;
            out[w++] = b;
            out[w++] = 1.0f;    // aColor.a 是预乘明暗：碎屑不自遮挡，恒为 1
            // S2/S3 新增：粒子/曳光/闪光都不是体素，采样纯白层
            // （texel.rgb = 1，故画面与 S2 逐像素一致）。
            // 必须写满 —— 少写两个 float 会让下一个顶点整体前移 8 字节。
            out[w++] = BlockTextureLayers.NEUTRAL_WHITE;
            out[w++] = VertexFormat.DEFAULT_AO;
            // S3 新增：UV（纯白层上取何值都不影响结果）。
            out[w++] = VertexFormat.DEFAULT_UV;
            out[w++] = VertexFormat.DEFAULT_UV;
        }
        return w;
    }

    // ============================================================ 读数

    /** 上一帧提交的粒子四边形数（= 粒子数 × 6）。自测断言用。 */
    public int lastParticleQuads() {
        return lastParticleQuads;
    }

    /** 上一帧提交的曳光四边形数（= 曳光数 × 6）。自测断言用。 */
    public int lastTracerQuads() {
        return lastTracerQuads;
    }

    /** M2.1：上一帧提交的枪口闪光四边形数（= 闪光数 × 6）。自测断言用。 */
    public int lastFlashQuads() {
        return lastFlashQuads;
    }

    /** 累计有绘制的帧数（用于确认叠加层真的在工作，而不是永远走空路径）。 */
    public long drawCount() {
        return drawCount;
    }
}

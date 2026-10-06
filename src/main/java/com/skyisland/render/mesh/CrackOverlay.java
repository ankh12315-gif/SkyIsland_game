package com.skyisland.render.mesh;

import com.skyisland.physics.RaycastHit;
import com.skyisland.player.Camera;
import com.skyisland.player.Player;
import com.skyisland.render.VertexFormat;
import com.skyisland.render.shader.ShaderProgram;
import com.skyisland.util.Log;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * 挖掘破坏反馈：目标方块面上的"10 段裂纹"（PRD_v0.3.1 §挖掘「破坏反馈」）。
 *
 * <h2>为什么有这个类（它不是新功能，是补一条从未实现的【MVP 必须】项）</h2>
 * PRD_v0.3.1 第 412 行把「破坏反馈：方块表面显示 10 段裂纹动画；每段伴随一次挖掘音效」
 * 标为【MVP 必须】，PRD 的 M1 里程碑交付内容里也写着"挖掘进度与<b>破坏反馈</b>"。
 * 但 M1 只实现了 HUD 底部的进度条，裂纹与音效两项都不存在，也没有被登记进
 * M1 报告的 T-8 顺延清单 —— 也就是说它是**悄悄缩小的范围**。
 * 人工试玩的反馈"长按打碎方块没有动画显示出来打碎"指的就是这一条。
 *
 * <p>本类只补<b>视觉</b>部分（10 段裂纹）。音效需要引入 OpenAL 与音频设备，
 * 属于独立工作项，仍留在顺延清单里（已在报告登记）。
 *
 * <h2>怎么画（不引入纹理，与 M1 的"无 TextureArray"约束一致）</h2>
 * 每段裂纹是一个贴在命中面上的<b>世界空间小四边形</b>，颜色为近乎黑的深色，
 * 混合叠加在方块表面。10 段从命中面中心向外呈放射状排列，
 * 段数由挖掘进度决定（见 {@link #segmentCount}）—— 于是"裂纹逐渐长出来"
 * 就是进度条的方块的等价物。
 *
 * <p><b>位置为什么由方块坐标哈希决定：</b>裂纹图案必须是<b>稳定</b>的 ——
 * 若每帧重新随机，视觉上是噪声闪烁而不是裂纹；若全局固定一张图案，
 * 所有方块看起来同款。用 (blockX, blockY, blockZ) 做哈希种子，
 * 得到"同一方块永远同一图案、不同方块不同图案"，且不占任何存储。
 *
 * <p><b>精度处理：</b>顶点坐标拆成"世界偏移走 uniform + 局部量"两段，
 * 与 {@code voxel.vert} 的既有约定一致（局部量恒在 [0,1]，世界偏移是整数，
 * 二者都在 float 的精确表示范围内；不这样做的话，世界坐标超过 2^24 后
 * 贴面会抖动）。命中方块的 (blockX, blockZ) 作偏移，顶点里只放块内相对坐标。
 */
public final class CrackOverlay {

    /** PRD 规定的裂纹段数：10 段。 */
    public static final int MAX_SEGMENTS = 10;

    /**
     * 裂纹不透明度。
     *
     * <p>取 0.85 而不是 1.0：纯黑不透明贴片会显得像"方块上贴了张纸"，
     * 留 15% 的底色透出来才像裂开。这个值也决定了重叠处的观感 ——
     * 放射状的 10 段在中心附近天然重叠，叠加后中心接近全黑，
     * 正好形成"裂纹起源点"的观感，因此不需要额外画中心点。
     */
    public static final float CRACK_ALPHA = 0.85f;

    /**
     * 贴面沿法线向外的偏移（格）。
     *
     * <p>必须非零：贴面与方块表面共面时，两者的光栅化深度会因三角化方式不同而
     * 出现±1 LSB 的差异，表现为"裂缝边缘一闪一闪"的 z-fighting。
     * 2.5e-3 格 = 方块边长的 1/400，肉眼不可见，但远大于该量级。
     */
    public static final float FACE_OFFSET = 0.0025f;

    /** 裂纹最短/最长半径（格，命中面局部单位）：让 10 段长度不一，看起来像裂纹而不是齿轮。 */
    private static final float RADIUS_MIN = 0.03f;
    private static final float RADIUS_BASE = 0.20f;
    private static final float RADIUS_JITTER = 0.16f;

    /** 半宽范围：近处的段细、远处的段略粗。 */
    private static final float HALF_WIDTH_MIN = 0.011f;
    private static final float HALF_WIDTH_JITTER = 0.010f;

    /**
     * 贴面边界内缩量。
     *
     * <p>裂纹必须<b>完全落在方块面内</b>：越界的部分会"飘"到相邻方块表面或空气里，
     * 视觉上像悬空的黑色碎片。把面局部坐标钳在 [内缩, 1−内缩] 即可保证不越界，
     * 代价只是裂纹不能贴到面的最边缘 —— 这个代价可以忽略。
     */
    private static final float INNER_MARGIN = 0.06f;

    /** 顶点格式与 {@code voxel.vert} 一致：aPos(vec3) + aColor(vec4) + aLayerAo(vec2)。 */
    private static final int FLOATS_PER_VERTEX = VertexFormat.FLOATS_PER_VERTEX;
    /**
     * 每段 6 个顶点（两个三角形，不用 EBO：总顶点数最多 60，索引省不下什么）。
     *
     * <p><b>public 是因为 S2 起它成了跨类契约的一部分</b>：
     * 单测用它推导"每段应占多少 float"，从而<b>不需要在测试里再写死 7</b>。
     */
    public static final int VERTS_PER_SEGMENT = 6;
    /** 顶点缓冲容量（float 个数）。 */
    public static final int CAPACITY_FLOATS = MAX_SEGMENTS * VERTS_PER_SEGMENT * FLOATS_PER_VERTEX;

    /**
     * 本渲染器实际使用的每顶点 float 数（<b>供跨类一致性守卫读取</b>）。
     *
     * <p><b>为什么需要这个看似多余的转发方法：</b>
     * {@link #FLOATS_PER_VERTEX} 是 private，而一致性守卫必须能<b>读到每个消费方的真实值</b> ——
     * 若只能靠扫源码猜，那正是"某处漏改却全绿"的成因。
     * 公开一个只读转发口，让"五者是否一致"变成可断言的事实。
     */
    public static int vertexFloatsPerVertex() {
        return FLOATS_PER_VERTEX;
    }

    /** 裂纹颜色：近乎黑。与 aColor.a = 1 配合 —— 裂纹不吃面明暗，它是一层覆盖物。 */
    private static final float CRACK_R = 0.05f;
    private static final float CRACK_G = 0.045f;
    private static final float CRACK_B = 0.045f;

    private final float[] scratch = new float[CAPACITY_FLOATS];

    private int vao;
    private int vbo;
    private FloatBuffer staging;
    private int lastSegments;
    private long drawCount;

    // ============================================================ 生命周期

    public void init() {
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        staging = MemoryUtil.memAllocFloat(CAPACITY_FLOATS);

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) CAPACITY_FLOATS * Float.BYTES,
                GL15.GL_STREAM_DRAW);

        // 三个属性槽位统一由 VertexFormat 绑定（裂纹与地形/实体/粒子/手持物共用 voxelShader）。
        VertexFormat.bindVoxelAttribs();

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        Log.info("[裂纹] 叠加层已就绪（最多 %d 段，容量 %d 顶点）",
                MAX_SEGMENTS, MAX_SEGMENTS * VERTS_PER_SEGMENT);
    }

    public void dispose() {
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
    }

    // ============================================================ 每帧

    /**
     * 绘制当前挖掘目标的裂纹。没有挖掘、没有合法命中面、进度为 0 时什么都不画。
     *
     * <p>本方法<b>自己绑定着色器与矩阵</b>：它跑在 {@code ChunkRenderer} 之后，
     * 而后者在 pass 结束时已经解绑了程序。让本方法自理，是为了避免
     * "上一個 pass 恰好在某条提前返回的路径上忘了解绑"这类隐式耦合。
     *
     * @param player 可为 null（菜单期间没有玩家）—— 此时不画
     */
    public void render(ShaderProgram shader, Camera camera, Player player) {
        lastSegments = 0;
        if (player == null || !player.isMining()) {
            return;
        }
        RaycastHit hit = player.currentTarget();
        int segments = segmentCount(player.miningProgressFraction());
        int floats = buildVertices(hit, segments, scratch);
        if (floats == 0) {
            return;
        }
        upload(floats);

        shader.bind();
        shader.setMatrix4f("uProjection", camera.projectionMatrix());
        shader.setMatrix4f("uView", camera.viewMatrix());

        // 状态：贴面是半透明覆盖层 —— 不写深度（深度缓冲里只留真实几何），开混合，关剔除。
        // 关剔除是刻意的：贴面是单面四边形，可见性由深度缓冲决定；绕序写错时
        // 若开着剔除会表现为"完全看不见"，而这是最难归因的一类症状。
        // （本类的单测另外断言绕序为正，两道保险。）
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);

        // 世界偏移走 uniform（整数，精确）；顶点里只放块内相对坐标（[0,1]）。
        // 这个偏移量必须与 buildVertices 里"减去命中方块坐标"的口径一致 ——
        // 两处写不一致的话，裂纹会整体飘到别处，且只在大坐标世界才明显。
        shader.setVec2f("uChunkOffset", hit.blockX(), hit.blockZ());
        shader.setFloat("uAlpha", CRACK_ALPHA);

        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, floats / FLOATS_PER_VERTEX);
        GL30.glBindVertexArray(0);

        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        ShaderProgram.unbind();

        lastSegments = segments;
        drawCount++;
    }

    private void upload(int floats) {
        staging.clear();
        staging.put(scratch, 0, floats);
        staging.flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, staging);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    // ============================================================ 纯几何（可单测）

    /**
     * 进度 → 裂纹段数（PRD 的"10 段"）。
     *
     * <p>用向上取整而不是向下：取整方式决定"第 1 段什么时候出现"。
     * 向下取整的话，进度 0.099 时一段都没有 —— 玩家按下的头 0.1 秒完全没反馈；
     * 向上取整则让反馈从"进度 &gt; 0 的第一帧"就出现，符合"每段裂纹伴随一次音效"
     * 的时间语义（音效是第一声，裂纹也该是第一段）。
     *
     * <p>NaN / 负数 / 0 一律返回 0：进度来自 {@code miningProgressSeconds / hardness}，
     * 若哪天 hardness 变成 0 就可能是 NaN —— 而 NaN 参与比较会全部为 false，
     * 于是 {@code Math.min} 会把它漏过去，最终变成一个非法的顶点数。
     * 先挡掉比事后"应该不会发生"划算。
     */
    public static int segmentCount(double progress) {
        if (!(progress > 0.0)) {
            return 0;
        }
        return Math.min(MAX_SEGMENTS, (int) Math.ceil(progress * MAX_SEGMENTS));
    }

    /**
     * 生成裂纹顶点（纯函数，不碰 GL，便于单测）。
     *
     * <p>顶点布局与 {@code voxel.vert} 一致：每顶点 7 个 float —
     * {@code (localX, worldY, localZ, r, g, b, a)}。
     * 注意 y 分量是<b>绝对世界高度</b>（着色器只给 x/z 加偏移），
     * x/z 是相对命中方块原点的块内量。这个不对称来自既有顶点着色器的约定，
     * 单测里对两者分别断言。
     *
     * @param hit      命中结果；为 null、起点在方块内（无合法面）、法线为零时返回 0
     * @param segments 要画几段（0 表示不画）
     * @param out      输出缓冲，长度至少 {@link #CAPACITY_FLOATS}
     * @return 实际写入的 float 个数（0 表示没有几何）
     */
    public static int buildVertices(RaycastHit hit, int segments, float[] out) {
        if (hit == null || !hit.hasFace() || segments <= 0) {
            return 0;
        }
        int n = Math.min(segments, MAX_SEGMENTS);

        // ---- 面基：u × v = 法线（右手系）→ 从外侧看是 CCW ----
        int nx = hit.faceNormalX();
        int ny = hit.faceNormalY();
        int nz = hit.faceNormalZ();
        float ux, uy, uz, vx, vy, vz, ox, oy, oz;
        if (ny == 1) {
            ox = hit.blockX(); oy = hit.blockY() + 1; oz = hit.blockZ();
            ux = 0; uy = 0; uz = 1;  vx = 1; vy = 0; vz = 0;
        } else if (ny == -1) {
            ox = hit.blockX(); oy = hit.blockY(); oz = hit.blockZ();
            ux = 1; uy = 0; uz = 0;  vx = 0; vy = 0; vz = 1;
        } else if (nx == 1) {
            ox = hit.blockX() + 1; oy = hit.blockY(); oz = hit.blockZ();
            ux = 0; uy = 1; uz = 0;  vx = 0; vy = 0; vz = 1;
        } else if (nx == -1) {
            ox = hit.blockX(); oy = hit.blockY(); oz = hit.blockZ();
            ux = 0; uy = 0; uz = 1;  vx = 0; vy = 1; vz = 0;
        } else if (nz == 1) {
            ox = hit.blockX(); oy = hit.blockY(); oz = hit.blockZ() + 1;
            ux = 1; uy = 0; uz = 0;  vx = 0; vy = 1; vz = 0;
        } else if (nz == -1) {
            ox = hit.blockX(); oy = hit.blockY(); oz = hit.blockZ();
            ux = 0; uy = 1; uz = 0;  vx = 1; vy = 0; vz = 0;
        } else {
            return 0;   // 法线全零：没有可用面
        }

        int seed = hash(hit.blockX(), hit.blockY(), hit.blockZ());
        float centreU = 0.5f + (jitter(seed, 0) - 0.5f) * 0.18f;
        float centreV = 0.5f + (jitter(seed, 1) - 0.5f) * 0.18f;
        float baseAngle = jitter(seed, 2) * 360.0f;
        float step = 360.0f / MAX_SEGMENTS;

        int w = 0;
        float[] cornerBu = new float[4];
        float[] cornerBv = new float[4];
        for (int i = 0; i < n; i++) {
            double ang = Math.toRadians(baseAngle + i * step);
            float dx = (float) Math.cos(ang);
            float dy = (float) Math.sin(ang);
            float px = -dy;                 // 垂直于线段方向的单位向量
            float py = dx;
            float r0 = RADIUS_MIN;
            float r1 = RADIUS_BASE + RADIUS_JITTER * jitter(seed, 10 + i);
            float hw = HALF_WIDTH_MIN + HALF_WIDTH_JITTER * jitter(seed, 20 + i);

            cornerBu[0] = centreU + dx * r0 - px * hw;
            cornerBv[0] = centreV + dy * r0 - py * hw;
            cornerBu[1] = centreU + dx * r1 - px * hw;
            cornerBv[1] = centreV + dy * r1 - py * hw;
            cornerBu[2] = centreU + dx * r1 + px * hw;
            cornerBv[2] = centreV + dy * r1 + py * hw;
            cornerBu[3] = centreU + dx * r0 + px * hw;
            cornerBv[3] = centreV + dy * r0 + py * hw;

            // 四边形 (0,1,2) + (0,2,3)：从 u×v = 法线 的一侧看是 CCW
            int[] order = {0, 1, 2, 0, 2, 3};
            for (int k : order) {
                float a = clampInward(cornerBu[k]);
                float b = clampInward(cornerBv[k]);
                float wx = ox + ux * a + vx * b + nx * FACE_OFFSET;
                float wy = oy + uy * a + vy * b + ny * FACE_OFFSET;
                float wz = oz + uz * a + vz * b + nz * FACE_OFFSET;

                out[w++] = wx - hit.blockX();
                out[w++] = wy;                       // 绝对高度：着色器不给 y 加偏移
                out[w++] = wz - hit.blockZ();
                out[w++] = CRACK_R;
                out[w++] = CRACK_G;
                out[w++] = CRACK_B;
                out[w++] = 1.0f;                     // 预乘明暗 = 1：裂纹不受面明暗影响
                // S2/S3 新增：裂纹不是体素，采样纯白层（画面因此逐像素不变）。
                // 必须写满 —— 少写两个 float 会让下一个顶点整体前移 8 字节。
                out[w++] = BlockTextureLayers.NEUTRAL_WHITE;
                out[w++] = VertexFormat.DEFAULT_AO;
                // S3 新增：UV（纯白层上取何值都不影响结果）。
                out[w++] = VertexFormat.DEFAULT_UV;
                out[w++] = VertexFormat.DEFAULT_UV;
            }
        }
        return w;
    }

    /** 面局部坐标钳进 [内缩, 1−内缩]，保证裂纹不越出方块面。 */
    private static float clampInward(float t) {
        float lo = INNER_MARGIN;
        float hi = 1.0f - INNER_MARGIN;
        if (!(t > lo)) {
            return lo;      // NaN 也走这里
        }
        return Math.min(t, hi);
    }

    /** 方块坐标 → 图案种子（位置哈希：同一方块永远得到同一张图案）。 */
    static int hash(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= (h >>> 13);
        h *= 0x5bd1e995;
        h ^= (h >>> 15);
        return h;
    }

    /** 第 k 个确定性伪随机数，落在 [0,1)。用整数运算，跨平台/跨运行完全一致。 */
    static float jitter(int seed, int k) {
        int h = seed + k * 0x9E3779B9;
        h ^= (h >>> 16);
        h *= 0x85EBCA6B;
        h ^= (h >>> 13);
        return (h >>> 8) / (float) (1 << 24);
    }

    // ============================================================ 读数

    /** 上一帧实际提交的裂纹段数（自测断言用）。 */
    public int lastSegments() {
        return lastSegments;
    }

    /** 累计有绘制的帧数（用于确认叠加层真的在工作，而不是永远走空路径）。 */
    public long drawCount() {
        return drawCount;
    }
}

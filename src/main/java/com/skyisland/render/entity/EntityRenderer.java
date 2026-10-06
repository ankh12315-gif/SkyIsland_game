package com.skyisland.render.entity;

import com.skyisland.entity.Entity;
import com.skyisland.entity.MeleeMonster;
import com.skyisland.player.Camera;
import com.skyisland.render.VertexFormat;
import com.skyisland.render.geom.Boxes;
import com.skyisland.render.shader.ShaderProgram;
import com.skyisland.util.Log;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * 实体绘制（M2 引入，M2.1 升级为 parts 模型）：把 {@link Entity} 画成世界空间的盒体组合。
 *
 * <h2>M2 的取向：所见即所中</h2>
 * M2 没有模型与纹理管线（TECH_DESIGN_v0.1.1 §S′ 把 TextureArray 与资源管线顺延到 Alpha），
 * 而"目标必须能被看见"是 M2 通过标准第 1 条（完成一场基础枪战）的前提：
 * 看不见的目标既无法瞄准，也无法让"击中反馈"成立。
 *
 * <p>盒体的尺寸<b>直接取实体自己的碰撞箱</b>（{@link Entity#boundingBox()}），
 * 于是"所见即所中"。若为了好看画一个比碰撞箱小的模型，玩家会打在
 * "看起来明明没打到"的位置上 —— 这类不一致对射击手感是致命的，
 * 而"方块感"只是不好看。<b>宁可丑，不可不一致。</b>
 *
 * <h2>M2.1 的升级：一个盒体 → 一组 parts</h2>
 * 单盒体只回答了"这里有个东西"，答不了"它是不是怪物 / 它朝哪边 / 哪边是头"。
 * 因此怪物改画 {@link MonsterModel} 定义的 8 个 parts（头/躯干/双腿/双臂/双眼）。
 * 升级的同时<b>上面那条不变式一点没放松</b>，只是口径从"等于"放宽成
 * "恒在碰撞箱之内"（推导见 {@link MonsterModel} 的类注释与 {@code MonsterModelTest}）：
 * 视觉略小于碰撞箱可接受，视觉大于碰撞箱不可接受。
 *
 * <h2>为什么不按实体逐个 draw call</h2>
 * 顶点坐标沿用 {@code voxel.vert} 的既有约定：世界偏移走 uniform，顶点里只放
 * 相对量（见 {@link com.skyisland.render.mesh.CrackOverlay} 的说明）。
 * uniform 只有一份，因此<b>整批实体必须共用同一个偏移</b> —— 取相机所在方块的
 * 整数坐标。实体总是出现在相机附近，相对坐标因此恒在一个很小的范围内，
 * float 精度绰绰有余；代价是全部实体一次 draw call，与本项目的批处理取向一致。
 *
 * <p><b>parts 也必须共用同一份偏移</b>：这就是为什么 {@link MonsterModel} 只算局部 AABB，
 * 由本类在最后一步统一加偏移，而不是每个 part 自己记一份世界坐标。
 *
 * <h2>刻意不开启背面剔除</h2>
 * 盒体是封闭凸体，开剔除能省一半片元。但绕序写错时开着剔除的症状是
 * "<b>实体完全看不见</b>"——而这是最难归因的一类问题（画面里"没有东西"
 * 与"渲染没跑"无法区分）。实体数量只有个位数，省下的片元可以忽略。
 * {@link #buildBoxVertices} 是纯函数，单测另外断言六面朝向全部向外，
 * 将来若真要开剔除，那道保险已经在了。
 */
public final class EntityRenderer {

    /** 顶点格式与体素着色器一致：{@code pos vec3 + color vec4}。 */
    private static final int FLOATS_PER_VERTEX = Boxes.FLOATS_PER_VERTEX;

    /** 每个盒体 6 面 × 2 三角形 × 3 顶点。 */
    public static final int VERTS_PER_BOX = Boxes.VERTS_PER_BOX;

    /**
     * 一次能画的实体数上限。
     *
     * <p>取 64：M2 的怪物是手工刷出来的个位数，64 已经是一个不合理的量级 ——
     * 真超了说明刷怪逻辑出了问题，那时"少画几个"远好于无限增长缓冲。
     */
    public static final int MAX_ENTITIES = 64;

    /**
     * 每只实体要占的盒体数上限。
     *
     * <p><b>M2.1 的关键扩容：</b>原值是"1 个盒体 = 1 个实体"，直接拿实体数当盒体上限。
     * 怪物改成 parts 之后这个等式不成立 —— 8 个 parts 的怪物按旧口径要吃掉 8 份预算，
     * 于是"64 只怪"会在第 8 只就被截断，症状是<b>远处的怪不画、近处的怪正常</b>，
     * 而没人会往"容量算错"上想。
     * 这里把语义显式写成"实体数 × 每实体盒体数"，并把调试线框单独算一份容量。
     */
    public static final int BOXES_PER_ENTITY = MonsterModel.PART_COUNT;

    /** 一次能画的盒体上限 = 实体数 × 每实体盒体数。 */
    public static final int MAX_BOXES = MAX_ENTITIES * BOXES_PER_ENTITY;

    private static final int CAPACITY_FLOATS = MAX_BOXES * Boxes.FLOATS_PER_BOX;

    /** 调试线框：一个 AABB = 12 条棱 × 2 顶点。与实体同批量上限。 */
    private static final int VERTS_PER_WIRE_BOX = 24;

    private static final int DEBUG_CAPACITY_FLOATS =
            MAX_ENTITIES * VERTS_PER_WIRE_BOX * FLOATS_PER_VERTEX;

    /**
     * 调试线框的 12 条棱（角编号与 {@link Boxes} 相同：x位 | y位<<1 | z位<<2）。
     * 两条编号只差一个二进制位 = 一条棱。
     */
    private static final int[] WIRE_EDGES = {
            0, 1, 0, 2, 0, 4, 1, 3, 1, 5, 2, 3,
            2, 6, 3, 7, 4, 5, 4, 6, 5, 7, 6, 7,
    };

    /**
     * 调试碰撞箱开关。
     *
     * <p>M2.1 阶段没有把它接到 F3 overlay 上（F3 的数据源是 {@code HudModel}，
     * 而世界 pass 拿不到它），因此先做成一个代码开关：
     * 打开后每只实体额外画一个亮绿线框 AABB，用来<b>肉眼核对"视觉 ⊆ 碰撞箱"</b>。
     * 将来接调试 overlay 时，只需让某个界面开关去调 {@link #setDebugHitbox}。
     */
    private static volatile boolean debugHitbox;

    /** 调试线框的颜色：亮绿，与怪物的深红在任何背景下都分得开。 */
    private static final float DEBUG_R = 0.25f;
    private static final float DEBUG_G = 1.00f;
    private static final float DEBUG_B = 0.35f;

    /** 未知实体类型的回落色（中性灰；只告警一次，见 {@link #colorOf}）。 */
    private static final float[] COLOR_UNKNOWN = {0.55f, 0.55f, 0.58f, 1.0f};

    /** 命中反馈的闪白混色目标（PRD 5.4.3：怪物受击闪白）。 */
    private static final float FLASH_R = 1.0f;
    private static final float FLASH_G = 0.96f;
    private static final float FLASH_B = 0.92f;

    /**
     * 步态周期（格/完整周期）。
     *
     * <p>取 1.6：怪物速度 2.0 格/秒 → 约 1.25 个周期/秒 ≈ 每秒 2.5 步。
     * 相位由<b>累计行走距离</b>换算而不是由时间换算 —— 于是怪物停下来时
     * 步态<b>自然停在原地</b>，不会出现"站着原地踏步"。
     */
    public static final double GAIT_CYCLE_BLOCKS = 1.6;

    private int vao;
    private int vbo;
    private int debugVao;
    private int debugVbo;

    private FloatBuffer staging;
    private FloatBuffer debugStaging;

    private final float[] scratch = new float[CAPACITY_FLOATS];
    private final float[] debugScratch = new float[DEBUG_CAPACITY_FLOATS];
    /** parts 暂存：8 个 part × 6 float，写顶点时逐 part 取用（零分配）。 */
    private final float[] parts = new float[MonsterModel.PART_COUNT * MonsterModel.FLOATS_PER_PART];

    private int lastBoxes;
    private int lastWireBoxes;
    private long drawCount;
    private boolean warnedUnknownType;

    public void init() {
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        staging = MemoryUtil.memAllocFloat(CAPACITY_FLOATS);

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) CAPACITY_FLOATS * Float.BYTES,
                GL15.GL_STREAM_DRAW);
        setupAttribs();
        GL30.glBindVertexArray(0);

        debugVao = GL30.glGenVertexArrays();
        debugVbo = GL15.glGenBuffers();
        debugStaging = MemoryUtil.memAllocFloat(DEBUG_CAPACITY_FLOATS);

        GL30.glBindVertexArray(debugVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, debugVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) DEBUG_CAPACITY_FLOATS * Float.BYTES,
                GL15.GL_STREAM_DRAW);
        setupAttribs();
        GL30.glBindVertexArray(0);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        Log.info("[实体] 渲染器已就绪（最多 %d 只实体 × %d 个 parts = %d 个盒体，顶点容量 %d）",
                MAX_ENTITIES, BOXES_PER_ENTITY, MAX_BOXES, MAX_BOXES * VERTS_PER_BOX);
    }

    private static void setupAttribs() {
        // 三个属性槽位统一由 VertexFormat 绑定（实体与地形/粒子/裂纹/手持物共用 voxelShader）。
        VertexFormat.bindVoxelAttribs();
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
        if (debugVbo != 0) {
            GL15.glDeleteBuffers(debugVbo);
            debugVbo = 0;
        }
        if (debugVao != 0) {
            GL30.glDeleteVertexArrays(debugVao);
            debugVao = 0;
        }
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
        if (debugStaging != null) {
            MemoryUtil.memFree(debugStaging);
            debugStaging = null;
        }
    }

    /** 打开 / 关闭碰撞箱调试线框（默认关）。 */
    public static void setDebugHitbox(boolean enabled) {
        debugHitbox = enabled;
    }

    public static boolean isDebugHitbox() {
        return debugHitbox;
    }

    // ============================================================ 每帧

    /**
     * 绘制全部实体。列表为空或全部已死亡时什么都不画。
     *
     * <p>本方法自己绑定着色器与矩阵（同 {@code CrackOverlay} 的理由：
     * 它跑在 ChunkRenderer 之后，而后者在 pass 结束时已经解绑了程序）。
     */
    public void render(ShaderProgram shader, Camera camera, List<Entity> entities) {
        lastBoxes = 0;
        lastWireBoxes = 0;
        if (entities == null || entities.isEmpty()) {
            return;
        }

        // 世界偏移整批一致：取相机所在方块的整数坐标（见类注释）
        int offsetX = (int) Math.floor(camera.x());
        int offsetZ = (int) Math.floor(camera.z());

        boolean wire = debugHitbox;
        int floats = 0;
        int wireFloats = 0;
        int boxes = 0;

        for (Entity entity : entities) {
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            if (boxes + MonsterModel.PART_COUNT > MAX_BOXES) {
                // 容量满了：宁可这一帧少画一只，也不要越界写坏缓冲
                break;
            }
            float flash = (float) entity.hurtFlash01();
            if (MeleeMonster.TYPE_ID.equals(entity.typeId())) {
                floats = buildMonsterVertices(entity, offsetX, offsetZ, flash, scratch, floats);
                boxes += MonsterModel.PART_COUNT;
            } else {
                float[] color = colorOf(entity.typeId());
                floats = appendBox(entity, offsetX, offsetZ,
                        color[0], color[1], color[2], flash, scratch, floats);
                boxes++;
            }
            if (wire) {
                wireFloats = appendWireBox(entity, offsetX, offsetZ, debugScratch, wireFloats);
                lastWireBoxes++;
            }
        }
        if (floats == 0 && wireFloats == 0) {
            return;
        }
        lastBoxes = boxes;

        upload(vbo, staging, scratch, floats);
        upload(debugVbo, debugStaging, debugScratch, wireFloats);

        shader.bind();
        shader.setMatrix4f("uProjection", camera.projectionMatrix());
        shader.setMatrix4f("uView", camera.viewMatrix());
        shader.setVec2f("uChunkOffset", offsetX, offsetZ);
        // uAlpha 对实体恒为 1：实体是不透明几何，不是覆盖层。
        shader.setFloat("uAlpha", 1.0f);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);

        if (floats > 0) {
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, floats / FLOATS_PER_VERTEX);
            GL30.glBindVertexArray(0);
        }
        if (wireFloats > 0) {
            // 线框画在实体之后且仍然写深度：它只是"描边"，不参与遮挡判断，
            // 但被地形挡住的部分必须看不见，否则就成了透视外挂。
            GL30.glBindVertexArray(debugVao);
            GL11.glDrawArrays(GL11.GL_LINES, 0, wireFloats / FLOATS_PER_VERTEX);
            GL30.glBindVertexArray(0);
        }

        // 恢复被本类改过的两项状态："关剔除"（上面刚关的）与深度写入。
        // 不恢复也不会立刻出错（ChunkRenderer 每帧自己重设状态），但那是"靠别人擦桌子"——
        // 一旦哪天 pass 顺序变了，症状会是世界渲染开始画背面，而原因在这里。
        GL11.glEnable(GL11.GL_CULL_FACE);

        ShaderProgram.unbind();
        drawCount++;
    }

    /**
     * 把一只怪物展开成 {@link MonsterModel#PART_COUNT} 个盒体。
     *
     * <p>朝向用实体的 {@code facingDeg()}（与 {@link Camera} 同口径），
     * 于是"眼睛朝向"和"它朝谁走"是同一件事 —— 玩家看到的光点就是它的正面。
     *
     * <p><b>为什么是 public 的实例方法：</b>它是纯函数（只写 {@code out}，
     * 唯一的实例状态是复用的 parts 暂存数组），不碰 GL，
     * 于是"带着朝向旋转之后 parts 的并集是否还在碰撞箱里"可以被单测直接举证 ——
     * 而那正是 parts 模型唯一可能悄悄变坏的方式。
     * 实例方法而不是静态方法，是为了复用 {@link #parts} 暂存而不必每帧 new。
     *
     * <h2>pivot 的三个分量不是一回事（这里曾经写错过）</h2>
     * {@link Boxes#write} 的最后一步是"旋转后平移 {@code (pivotX, pivotY, pivotZ)}"，
     * 三个分量必须各自填对，但它们的口径<b>并不相同</b>：
     * <ul>
     *   <li><b>X / Z 要减世界偏移</b>（{@code -offsetX / -offsetZ}）：
     *       整批实体共用一份 {@code uChunkOffset}，顶点里只放相对量。要减偏移的理由是
     *       <b>浮点精度</b> —— 世界坐标可以到几百上千格，直接写进 float 顶点会让远处的实体
     *       抖动；减掉相机所在方块后相对量恒在小范围内。offset 只作用于水平两轴
     *       （世界偏移本身就是水平概念，见 {@link com.skyisland.render.mesh.CrackOverlay}）。</li>
     *   <li><b>Y 不需要、也不可能减世界偏移</b>：竖直方向没有偏移量可比。更关键的是，
     *       它必须等于实体<b>脚底</b>的世界高度 —— {@link MonsterModel} 的局部 y 就是
     *       "相对脚底的绝对高度"（见其类注释「局部坐标系」），所以这个平移项
     *       <b>恰好等于 {@code entity.position().y}</b>，不多一个常数、也不少一个常数。
     *       写成 {@code 0.0} 的后果不是"丑"，而是<b>整具模型的世界 Y 与实体位置脱钩</b>：
     *       局部高度 0–1.8 会被当成世界高度，怪被画在 y≈0–1.8 的地下，
     *       而它的逻辑碰撞箱仍在 {@code position().y} 处 —— 所见与所中彻底分离。</li>
     * </ul>
     * 这条曾经真的写错过（pivotY 被硬编码成 {@code 0.0}），后果是"按 F4 生成的怪
     * 在画面里一个像素都没有，却能正常追人咬人"。抓它的断言见
     * {@code MonsterModelTest#rotatedPartsStillFitInsideTheCollisionBox} 的<b>下界</b>
     * 与"部件确实在顶部"的存在性断言 —— 单边上界看不见"整体沉到地下"。
     */
    public int buildMonsterVertices(Entity entity, int offsetX, int offsetZ, float flash,
                                    float[] out, int start) {
        double walkDistance = entity.walkDistance();
        double phase = walkDistance * (2.0 * Math.PI / GAIT_CYCLE_BLOCKS);
        double swing = entity.attackSwing01();
        MonsterModel.write(parts, 0, phase, swing);

        double yaw = Math.toRadians(entity.facingDeg());
        // X/Z 减世界偏移（浮点精度）；Y 不做偏移，且必须 = 脚底世界高度 = position().y。
        double pivotX = entity.position().x - offsetX;
        double pivotY = entity.position().y;
        double pivotZ = entity.position().z - offsetZ;

        int p = start;
        for (int i = 0; i < MonsterModel.PART_COUNT; i++) {
            int b = i * MonsterModel.FLOATS_PER_PART;
            float[] base = MonsterModel.color(i);
            // 眼睛也一起混：整只怪亮起来，"被打中了"才是一个整体信号。
            // 眼睛本来就近乎全亮，混色后变化很小 —— 闪白的主作用在躯干与四肢上。
            float cr = flashMix(base[0], flash, FLASH_R);
            float cg = flashMix(base[1], flash, FLASH_G);
            float cb = flashMix(base[2], flash, FLASH_B);
            p = Boxes.write(out, p,
                    parts[b], parts[b + 1], parts[b + 2],
                    parts[b + 3], parts[b + 4], parts[b + 5],
                    pivotX, pivotY, pivotZ,
                    yaw, 0.0, 0.0,
                    cr, cg, cb, 1.0f);
        }
        return p;
    }

    /** 非怪物实体（以及未知类型）的回落：直接画碰撞箱一个盒体。 */
    private int appendBox(Entity entity, int offsetX, int offsetZ,
                          float r, float g, float b, float flash, float[] out, int start) {
        var box = entity.boundingBox();
        return Boxes.write(out, start,
                box.minX() - offsetX, box.minY(), box.minZ() - offsetZ,
                box.maxX() - offsetX, box.maxY(), box.maxZ() - offsetZ,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                flashMix(r, flash, FLASH_R),
                flashMix(g, flash, FLASH_G),
                flashMix(b, flash, FLASH_B),
                1.0f);
    }

    /** 碰撞箱调试线框：12 条棱，用 GL_LINES 画（比 12 根细盒体便宜 18 倍）。 */
    private int appendWireBox(Entity entity, int offsetX, int offsetZ, float[] out, int start) {
        var box = entity.boundingBox();
        double minX = box.minX() - offsetX;
        double minY = box.minY();
        double minZ = box.minZ() - offsetZ;
        double maxX = box.maxX() - offsetX;
        double maxY = box.maxY();
        double maxZ = box.maxZ() - offsetZ;

        int p = start;
        for (int e = 0; e < WIRE_EDGES.length; e += 2) {
            for (int k = 0; k < 2; k++) {
                int corner = WIRE_EDGES[e + k];
                out[p] = (float) ((corner & 1) != 0 ? maxX : minX);
                out[p + 1] = (float) ((corner & 2) != 0 ? maxY : minY);
                out[p + 2] = (float) ((corner & 4) != 0 ? maxZ : minZ);
                out[p + 3] = DEBUG_R;
                out[p + 4] = DEBUG_G;
                out[p + 5] = DEBUG_B;
                out[p + 6] = 1.0f;
                p += FLOATS_PER_VERTEX;
            }
        }
        return p;
    }

    private void upload(int target, FloatBuffer buffer, float[] source, int floats) {
        if (floats == 0) {
            return;
        }
        buffer.clear();
        buffer.put(source, 0, floats);
        buffer.flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, target);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, buffer);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private float[] colorOf(String typeId) {
        if (!warnedUnknownType) {
            warnedUnknownType = true;
            Log.noteWarning("实体", "类型 " + typeId + " 没有 parts 模型，暂画一个中性灰盒体 —— "
                    + "新增实体类型时请在此处接上它的模型，否则它看起来会像一块石头。");
        }
        return COLOR_UNKNOWN;
    }

    // ============================================================ 纯几何（可单测）

    /**
     * 把一个实体的碰撞箱写成 36 个顶点，追加到 {@code out} 的 {@code start} 位置，
     * 返回新的写入位置。
     *
     * <p><b>纯函数</b>：不碰 GL、不读全局状态，因此盒体是否正确（尺寸、朝向、
     * 顶点数）可以被单元测试完整举证。
     *
     * <p><b>它现在是"回落路径"的公开入口</b>：怪物走 {@link #appendMonster}，
     * 而没有 parts 模型的实体类型仍然画一个与碰撞箱等大的盒体。
     * 保留这个签名是因为 {@code EntityRendererTest} 用它钉住了顶点数、
     * 尺寸一致性与绕序三件事 —— 那三条对 parts 同样成立（parts 走的也是
     * {@link Boxes#write}，同一张面表）。
     *
     * @param flash 0..1 的闪白强度（命中反馈）；按线性插值混向亮白
     */
    public static int buildBoxVertices(Entity entity, int offsetX, int offsetZ,
                                       float r, float g, float b, float flash,
                                       float[] out, int start) {
        var box = entity.boundingBox();
        float f = Math.max(0f, Math.min(1f, flash));
        return Boxes.write(out, start,
                box.minX() - offsetX, box.minY(), box.minZ() - offsetZ,
                box.maxX() - offsetX, box.maxY(), box.maxZ() - offsetZ,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                r + (FLASH_R - r) * f,
                g + (FLASH_G - g) * f,
                b + (FLASH_B - b) * f,
                1.0f);
    }

    /** 一个盒体需要的 float 数量（供调用方预分配缓冲）。 */
    public static int floatsPerBox() {
        return Boxes.FLOATS_PER_BOX;
    }

    /** 把基础色按 {@code flash} 混向闪白目标色（0 = 不变，1 = 全白）。 */
    public static float flashMix(float base, float flash, float flashTarget) {
        float f = Math.max(0f, Math.min(1f, flash));
        return base + (flashTarget - base) * f;
    }

    // ============================================================ 统计

    /** 上一帧实际绘制的盒体数（自测断言用）。 */
    public int lastBoxes() {
        return lastBoxes;
    }

    /** 上一帧实际绘制的调试线框数（0 表示调试线框关着）。 */
    public int lastWireBoxes() {
        return lastWireBoxes;
    }

    /** 累计有实体绘制的帧数。 */
    public long drawCount() {
        return drawCount;
    }
}

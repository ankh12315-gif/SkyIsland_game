package com.skyisland.render.viewmodel;

import com.skyisland.render.VertexFormat;
import com.skyisland.render.geom.Boxes;
import com.skyisland.render.shader.ShaderProgram;
import com.skyisland.util.Log;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * 第一人称手持物的 GL 绘制层：一个<b>独立 pass</b>，跑在世界 pass 之后、HUD pass 之前。
 *
 * <h2>为什么必须是一个独立 pass（而不能塞进 UI 层）</h2>
 * {@code ui.vert} 的顶点属性只有 {@code vec2 aPos}，直接输出 NDC，
 * <b>没有任何矩阵 uniform</b> —— 它画不了 3D。
 * 所以手持物复用 {@code voxel} 着色器（顶点 {@code aPos vec3 + aColor vec4}），
 * 自己设一套专有的 {@code uProjection}（窄 FOV 透视）、{@code uView = 单位矩阵}、
 * {@code uChunkOffset = (0,0)}。
 *
 * <h2>★ 为什么必须先清一次深度缓冲</h2>
 * 手持物离眼睛只有 0.75 米，但它<b>不参与世界</b>。
 * 不清深度的话，它会拿世界 pass 留下的深度值做测试：
 * 玩家贴着墙站时，墙的深度比 0.75 米还近，于是手持物被<b>整块切掉</b>，
 * 症状是"贴墙时手消失了"——而这个 bug 只在贴墙时出现，极难联想到深度缓冲。
 * 清完之后手持物独占深度区间，与世界互不干涉。
 *
 * <p>代价只有一条：手持物不会再被地形遮挡（本来也不该被遮挡 ——
 * 它就长在眼睛上）。这与"实体必须被地形正确遮挡"是两类东西，不冲突。
 *
 * <h2>GL 状态</h2>
 * 进入前读一遍将要改动的开关，退出时原样写回（{@code CrackOverlay} 把
 * {@code GL_CULL_FACE} 关掉之后不恢复，于是它之后的每一次区块绘制都失去背面剔除；
 * 本类不重复这个坑）。
 */
public final class ViewmodelRenderer {

    private static final int FLOATS_PER_VERTEX = Boxes.FLOATS_PER_VERTEX;

    private static final int CAPACITY_FLOATS =
            ViewmodelGeometry.MAX_BOXES * Boxes.FLOATS_PER_BOX;

    /** 近平面：手持物最近也在 10 厘米外，0.01 足够。 */
    private static final float NEAR = 0.01f;

    /** 远平面：手持物最长不到 0.4 米，8 米是极大的余量。 */
    private static final float FAR = 8f;

    private int vao;
    private int vbo;
    private FloatBuffer staging;
    private final float[] scratch = new float[CAPACITY_FLOATS];

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final ViewmodelPose pose = new ViewmodelPose();

    /** 上一帧的时间（秒），用来在渲染层自己算 dt —— 调用方不必多传一个参数。 */
    private double lastTimeSeconds = -1;

    private int lastBoxes;
    private long drawCount;

    public void init() {
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        staging = MemoryUtil.memAllocFloat(CAPACITY_FLOATS);

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) CAPACITY_FLOATS * Float.BYTES,
                GL15.GL_STREAM_DRAW);

        // 三个属性槽位统一由 VertexFormat 绑定（手持物与地形/实体/粒子/裂纹共用 voxelShader）。
        VertexFormat.bindVoxelAttribs();

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        Log.info("[手持物] viewmodel pass 已就绪（容量 %d 个盒体，FOV %.0f°）",
                ViewmodelGeometry.MAX_BOXES, ViewmodelGeometry.FOV_DEG);
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

    /**
     * 绘制一帧手持物。
     *
     * @param model 可为 null；{@code visible == false} 时不产生任何 GL 调用
     *              （连着色器都不绑），保证菜单 / 死亡期间不付代价
     */
    public void render(ShaderProgram shader, ViewmodelModel model, int fbWidth, int fbHeight) {
        lastBoxes = 0;
        if (shader == null || model == null || !model.visible) {
            return;
        }

        double now = model.timeSeconds;
        double dt = lastTimeSeconds < 0 ? 0 : now - lastTimeSeconds;
        lastTimeSeconds = now;

        // 动画恒定推进（哪怕这一帧的 kind 是 EMPTY）：
        // 否则从菜单切回来时 lastTimeSeconds 还停在旧值，会"补"一个巨大的 dt。
        pose.update(model, dt);

        int floats = ViewmodelGeometry.write(scratch, 0, model, pose);
        if (floats == 0) {
            return;
        }
        lastBoxes = floats / Boxes.FLOATS_PER_BOX;

        staging.clear();
        staging.put(scratch, 0, floats);
        staging.flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, staging);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        // ---- 保存将被改动的状态 ----
        boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        float aspect = (float) Math.max(1, fbWidth) / (float) Math.max(1, fbHeight);
        projection.identity().perspective((float) Math.toRadians(ViewmodelGeometry.FOV_DEG),
                aspect, NEAR, FAR);
        view.identity();

        shader.bind();
        shader.setMatrix4f("uProjection", projection);
        shader.setMatrix4f("uView", view);
        shader.setVec2f("uChunkOffset", 0f, 0f);
        shader.setFloat("uAlpha", 1.0f);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);

        // ★ 清深度：手持物独占深度区间，不被地形切掉（理由见类注释）
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, floats / FLOATS_PER_VERTEX);
        GL30.glBindVertexArray(0);

        // ---- 原样写回 ----
        GL11.glDepthMask(prevDepthMask);
        setEnabled(GL11.GL_BLEND, prevBlend);
        setEnabled(GL11.GL_CULL_FACE, prevCull);
        setEnabled(GL11.GL_DEPTH_TEST, prevDepthTest);
        ShaderProgram.unbind();

        drawCount++;
    }

    private static void setEnabled(int cap, boolean enabled) {
        if (enabled) {
            GL11.glEnable(cap);
        } else {
            GL11.glDisable(cap);
        }
    }

    /** 上一帧实际绘制的盒体数（自测断言用）。 */
    public int lastBoxes() {
        return lastBoxes;
    }

    /** 累计有绘制的帧数。 */
    public long drawCount() {
        return drawCount;
    }

    /** 当前动画状态（自测断言 ADS / 后坐 / 切槽用）。 */
    public ViewmodelPose pose() {
        return pose;
    }
}

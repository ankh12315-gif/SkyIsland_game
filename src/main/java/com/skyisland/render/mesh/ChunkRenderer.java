package com.skyisland.render.mesh;

import com.skyisland.render.Frustum;
import com.skyisland.render.shader.ShaderProgram;
import com.skyisland.player.Camera;
import com.skyisland.util.Log;
import com.skyisland.world.Chunk;
import com.skyisland.world.LightEngine;
import com.skyisland.world.World;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区块网格的构建队列消费 + 上传 + 分 pass 绘制（TECH_DESIGN §G.5 / §G.7 / §G.8）。
 *
 * <p><b>明确禁止的事：每帧重新网格化全世界。</b>本类只消费
 * {@link World#pollMeshRebuilds(int)} 交出来的脏区块，且每帧<u>限量</u>
 * （{@link #MAX_CHUNK_REBUILDS_PER_FRAME}）。
 *
 * <p><b>原子替换（§G.5）：</b>若要重建的区块已有旧网格，在构建+上传完成之前
 * 一直显示旧网格。不允许"先清空 VBO 再逐步写入"—— 那会在挖掘的瞬间让整片地形闪一下。
 * 本类通过"构建完才 {@code upload}"实现这一点，并<u>不</u>在构建前删除旧资源。
 *
 * <p><b>两个 pass 的理由</b>（§G.7）：不透明 pass 开深度写入、关混合；
 * 半透明 pass 关深度写入、开混合，并按距离由远及近绘制。
 * "Pass 内不排序、Pass 间排序"：不透明几何靠深度缓冲解决遮挡，
 * 逐三角形排序几万三角形毫无收益；半透明的排序粒度是<b>区块</b>而非三角形。
 *
 * <p><b>已知限制（§G.7 记录）：</b>同一区块内不同深度的玻璃可能排序错误。
 * M1 只有玻璃一种半透明方块、玩家通常不建多层玻璃结构，可接受。
 */
public final class ChunkRenderer {

    /**
     * 每帧最多重建多少个区块。
     *
     * <p>取值依据（§G.5）：16 个区块全部重建需要 4 帧；这是"加载地形"的耗时上限。
     * 每帧只占 4/60 的预算。若实测单区块构建 &gt; 4 ms 需下调；若加载过慢可上调
     * 或引入工作线程（M1 不做线程，理由：网格化还没被证明是瓶颈，
     * 提前并行化会让"卡顿到底来自哪里"变得不可归因）。
     */
    public static final int MAX_CHUNK_REBUILDS_PER_FRAME = 4;

    /** 单区块构建耗时超过此值就记一条 WARN，用于定位"某区块异常慢"。 */
    public static final long SLOW_REBUILD_WARN_NANOS = 8_000_000L;

    /** 半透明统一不透明度。M1 只有玻璃一种透明方块，逐材质 alpha 随贴图阶段（M2）引入。 */
    public static final float TRANSPARENT_ALPHA = 0.72f;

    private final Map<Chunk, ChunkMesh> meshes = new IdentityHashMap<>();

    /** 本帧可见的不透明区块（保持世界加载顺序 → 绘制顺序可复现，便于两次运行对比指标）。 */
    private final List<Chunk> visibleOpaque = new ArrayList<>();
    /** 本帧可见的透明区块（按到相机距离由远及近）。 */
    private final List<Chunk> visibleTransparent = new ArrayList<>();
    private final List<DistanceChunk> sortScratch = new ArrayList<>();

    private int drawCalls;
    private int renderedTriangles;
    private int culledChunks;

    private record DistanceChunk(Chunk chunk, double distanceSquared) {
    }

    // ============================================================ 队列消费

    /**
     * 限量消费网格重建队列并上传。
     *
     * @return 本次实际重建的区块数
     */
    public int processRebuildQueue(World world) {
        List<Chunk> batch = world.pollMeshRebuilds(MAX_CHUNK_REBUILDS_PER_FRAME);
        int processed = 0;
        for (Chunk chunk : batch) {
            if (!isStillLoaded(world, chunk)) {
                // 区块被卸载：它的网格必须一起释放，否则 GPU 资源会随加载/卸载循环泄漏
                disposeMesh(chunk);
                continue;
            }
            long started = System.nanoTime();
            LightEngine light = LightEngine.snapshot(chunk, world);
            MeshData data = ChunkMesher.build(chunk, world, light);
            long elapsed = System.nanoTime() - started;

            ChunkMesh mesh = meshes.get(chunk);
            if (mesh == null) {
                mesh = new ChunkMesh();
                meshes.put(chunk, mesh);
            }
            mesh.upload(data);

            chunk.recordMeshBuild(elapsed, data.opaqueFaceCount(), data.transparentFaceCount());
            world.recordMeshBuild(elapsed);
            chunk.clearMeshDirty();
            processed++;

            if (elapsed > SLOW_REBUILD_WARN_NANOS) {
                ChunkMesher.logSlowBuild(chunk, elapsed, data);
            }
        }
        return processed;
    }

    private boolean isStillLoaded(World world, Chunk chunk) {
        return world.chunkAt(chunk.cx(), chunk.cz()) == chunk;
    }

    // ============================================================ 绘制

    /**
     * 绘制所有可见区块。
     *
     * <p>调用方负责清屏与帧缓冲尺寸设置 —— 本类只画世界几何。
     */
    public void render(World world, Camera camera, ShaderProgram shader, Frustum frustum) {
        drawCalls = 0;
        renderedTriangles = 0;
        culledChunks = 0;
        visibleOpaque.clear();
        visibleTransparent.clear();

        double eyeX = camera.x();
        double eyeY = camera.y();
        double eyeZ = camera.z();

        for (Chunk chunk : world.loadedChunks()) {
            ChunkMesh mesh = meshes.get(chunk);
            if (mesh == null) {
                continue;   // 还没构建过网格（首帧尚未被队列消费到）
            }
            if (!frustum.intersectsAABB(
                    chunk.originX(), 0f, chunk.originZ(),
                    chunk.originX() + Chunk.SIZE, Chunk.HEIGHT, chunk.originZ() + Chunk.SIZE)) {
                culledChunks++;
                continue;
            }
            if (mesh.hasOpaque()) {
                visibleOpaque.add(chunk);
            }
            if (mesh.hasTransparent()) {
                double cx = chunk.originX() + Chunk.SIZE * 0.5;
                double cz = chunk.originZ() + Chunk.SIZE * 0.5;
                double dy = eyeY - Chunk.HEIGHT * 0.5;
                double distanceSquared = (eyeX - cx) * (eyeX - cx) + dy * dy + (eyeZ - cz) * (eyeZ - cz);
                sortScratch.add(new DistanceChunk(chunk, distanceSquared));
            }
        }

        // 半透明按距离由远及近。排序粒度是区块（§G.7）：区块内不排序，已知限制。
        sortScratch.sort((a, b) -> Double.compare(b.distanceSquared(), a.distanceSquared()));
        for (DistanceChunk dc : sortScratch) {
            visibleTransparent.add(dc.chunk());
        }
        sortScratch.clear();

        shader.bind();
        shader.setMatrix4f("uProjection", camera.projectionMatrix());
        shader.setMatrix4f("uView", camera.viewMatrix());

        // ---------- Pass 1：不透明 ----------
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glFrontFace(GL11.GL_CCW);
        shader.setFloat("uAlpha", 1.0f);
        for (Chunk chunk : visibleOpaque) {
            shader.setVec2f("uChunkOffset", chunk.originX(), chunk.originZ());
            ChunkMesh mesh = meshes.get(chunk);
            mesh.drawOpaque();
            drawCalls++;
            renderedTriangles += mesh.opaqueFaceCount() * 2;
        }

        // ---------- Pass 2：半透明（由远及近，关深度写入，开混合） ----------
        if (!visibleTransparent.isEmpty()) {
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            shader.setFloat("uAlpha", TRANSPARENT_ALPHA);
            for (Chunk chunk : visibleTransparent) {
                shader.setVec2f("uChunkOffset", chunk.originX(), chunk.originZ());
                ChunkMesh mesh = meshes.get(chunk);
                mesh.drawTransparent();
                drawCalls++;
                renderedTriangles += mesh.transparentFaceCount() * 2;
            }
            // 恢复默认状态，避免污染后续 pass（HUD 或下一帧）
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND);
        }

        GL11.glDisable(GL11.GL_CULL_FACE);
        ShaderProgram.unbind();
    }

    // ============================================================ 释放与统计

    private void disposeMesh(Chunk chunk) {
        ChunkMesh mesh = meshes.remove(chunk);
        if (mesh != null) {
            mesh.dispose();
        }
    }

    /** 释放全部 GPU 网格资源。必须在 GL 上下文销毁之前调用。 */
    public void disposeAll() {
        int count = meshes.size();
        for (ChunkMesh mesh : meshes.values()) {
            mesh.dispose();
        }
        meshes.clear();
        visibleOpaque.clear();
        visibleTransparent.clear();
        sortScratch.clear();
        Log.info("[Mesh] 已释放 %d 个区块网格", count);
    }

    public int meshCount() {
        return meshes.size();
    }

    public int drawCalls() {
        return drawCalls;
    }

    public int renderedTriangles() {
        return renderedTriangles;
    }

    public int culledChunks() {
        return culledChunks;
    }

    /** 已构建网格的区块数 / 世界已加载区块数，用于 HUD 与自测断言。 */
    public int pendingMeshCount(World world) {
        return world.loadedChunkCount() - meshes.size();
    }

    public List<Chunk> lastVisibleOpaque() {
        return Collections.unmodifiableList(visibleOpaque);
    }
}

package com.skyisland.render.mesh;

import com.skyisland.util.Coords;
import com.skyisland.util.Log;
import com.skyisland.world.Chunk;
import com.skyisland.world.LightEngine;
import com.skyisland.world.World;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;
import com.skyisland.world.block.RenderType;

/**
 * 区块网格化（TECH_DESIGN §G.1–§G.4）：<b>只做暴露面剔除，不做 Greedy Meshing</b>。
 *
 * <p><b>为什么 M1 不做 Greedy：</b>§G.1 —— 收益在"大片同材质平面"，成本是
 * "每次方块改动都要重建更大的合并单元"。在玩家频繁挖放的阶段反而可能更慢。
 * 门禁实测未通过前不引入（结论已冻结，本类不预留 Greedy 的开关）。
 *
 * <p><b>核心循环</b>（与 §G.4 的伪码逐行对应）：
 * <pre>
 *   for ly, lz, lx:
 *     b = 该格方块
 *     if b 是空气: continue          // 最常见分支放最前
 *     for face in 6 个方向:
 *       n = 邻居方块
 *       if (!b.rendersFaceAgainst(n)) continue
 *       发射一个四边形（4 顶点 + 6 索引）到对应子网格
 * </pre>
 *
 * <p><b>两处必须做对的细节（§G.4）：</b>
 * <ol>
 *   <li><b>跨区块邻居查询</b>：{@code lx/lz == 0/15} 的方块，其面朝向区块外。
 *       既不能简单视为空气（会在每个区块边界生成多余面 → 交界处出现"内部墙面"），
 *       也不能假定邻区块已加载。本实现的做法是：<u>界内</u>用 {@code chunk.blockAt}
 *       （快），<u>界外</u>问 {@link World#blockIdAt}（未加载区块返回空气 → 生成面）。
 *       "宁可多画一面，不可漏画"：邻区块加载后本区块会被标脏并重建，多出来的一面自行消失。</li>
 *   <li><b>子网格归属</b>：按<u>自身</u>方块的 {@link RenderType} 决定进入哪个子网格，
 *       而面是否生成由 {@code rendersFaceAgainst} 决定 —— 两件事不能混在一个判断里。</li>
 * </ol>
 *
 * <p><b>为什么顶点只写区块局部坐标：</b>见 §G.3 —— 世界坐标可达 ±数千，
 * {@code float} 超过 2^24 后整数精度丢失；局部坐标恒在 [0,16]，世界偏移走一个 uniform。
 */
public final class ChunkMesher {

    private ChunkMesher() {
    }

    /** 便利入口：不带世界（不可跨区块查询，仅供单元测试构造孤立区块）。 */
    public static MeshData build(Chunk chunk) {
        return build(chunk, null, null);
    }

    /**
     * 构建区块网格。
     *
     * @param world 用于跨区块邻居与光源查询；{@code null} 表示"区块外一律视为空气且无光源"
     *              （单区块单元测试用）
     */
    public static MeshData build(Chunk chunk, World world) {
        return build(chunk, world, null);
    }

    public static MeshData build(Chunk chunk, World world, LightEngine light) {
        if (chunk.isEmpty()) {
            // 空区块直接返回空网格：不必跑 32768 次循环，也不必分配顶点数组。
            // 这是"挖空地形的区块不占 GPU 资源"的落点。
            return MeshData.EMPTY;
        }
        LightEngine lights = light != null ? light : LightEngine.snapshot(chunk, world);

        MeshBuilder opaque = new MeshBuilder();
        MeshBuilder transparent = new MeshBuilder();

        final int originX = chunk.originX();
        final int originZ = chunk.originZ();

        for (int ly = 0; ly < Coords.CHUNK_HEIGHT; ly++) {
            for (int lz = 0; lz < Coords.CHUNK_SIZE; lz++) {
                for (int lx = 0; lx < Coords.CHUNK_SIZE; lx++) {
                    short id = chunk.blockAt(lx, ly, lz);
                    if (id == BlockRegistry.AIR_RUNTIME_ID) {
                        continue;
                    }
                    Block block = BlockRegistry.byRuntimeId(id);
                    RenderType type = block.renderType();
                    if (type == RenderType.INVISIBLE) {
                        continue;
                    }
                    MeshBuilder target = (type == RenderType.TRANSPARENT) ? transparent : opaque;

                    for (BlockFace face : BlockFace.values()) {
                        Block neighbor = neighborBlock(chunk, world, lx, ly, lz, face);
                        if (!block.rendersFaceAgainst(neighbor)) {
                            continue;
                        }
                        int lightLevel = lights.lightAt(originX, originZ, lx, ly, lz);
                        float shade = face.shade() * lights.shadeFactor(lightLevel);
                        emitFace(target, lx, ly, lz, face, block, shade);
                    }
                }
            }
        }

        return new MeshData(opaque.toVertexArray(), opaque.toIndexArray(),
                transparent.toVertexArray(), transparent.toIndexArray(),
                opaque.faceCount(), transparent.faceCount());
    }

    /**
     * 邻居方块查询（跨越区块边界时问世界）。
     *
     * <p>界内走 {@code chunk.blockAt} 是为了性能：一次网格化要问 6 × 非空气方块数次，
     * 每次都走 {@code World.blockIdAt}（含 {@code floorDiv}/{@code floorMod}/哈希查表）
     * 会让构建耗时翻数倍。只有真正落在区块外的查询才付那个代价 ——
     * 而落在区块外的只有表面上的一圈（16×16×128 中 lx=0/15 或 lz=0/15 的那些）。
     */
    private static Block neighborBlock(Chunk chunk, World world, int lx, int ly, int lz, BlockFace face) {
        int nx = lx + face.dx();
        int ny = ly + face.dy();
        int nz = lz + face.dz();

        boolean insideX = nx >= 0 && nx < Coords.CHUNK_SIZE;
        boolean insideZ = nz >= 0 && nz < Coords.CHUNK_SIZE;
        boolean insideY = ny >= 0 && ny < Coords.CHUNK_HEIGHT;

        if (insideX && insideZ && insideY) {
            return BlockRegistry.byRuntimeId(chunk.blockAt(nx, ny, nz));
        }
        if (world == null) {
            return BlockRegistry.air();
        }
        return world.blockAt(chunk.originX() + nx, ny, chunk.originZ() + nz);
    }

    /**
     * 发射一个四边形。
     *
     * <p>顶点顺序由 {@link BlockFace} 保证"从外侧看逆时针"，
     * 索引固定为 {@code 0,1,2, 0,2,3}（两个三角形共用一条对角线）。
     * EBO 让顶点数从 6 降到 4（§G.6），节省 33% 顶点带宽。
     */
    private static void emitFace(MeshBuilder out, int lx, int ly, int lz, BlockFace face,
                                 Block block, float shade) {
        int baseIndex = out.vertexCount();
        for (int i = 0; i < BlockFace.QUAD_INDICES.length; i++) {
            // 索引先写，值由下面的顶点顺序决定 —— 这里不直接写 0,1,2,0,2,3 而是加基准，
            // 因为一个区块里所有面共用一个 VBO。
            out.pushIndex(baseIndex + BlockFace.QUAD_INDICES[i]);
        }
        for (int corner = 0; corner < MeshData.VERTICES_PER_FACE; corner++) {
            out.pushVertex(
                    lx + face.corner(corner, 0),
                    ly + face.corner(corner, 1),
                    lz + face.corner(corner, 2),
                    block.colorR(), block.colorG(), block.colorB(), shade);
        }
        out.faceEmitted();
    }

    /** 一次网格化的结果 + 耗时日志（供 {@code ChunkRenderer} 调用）。 */
    public static void logSlowBuild(Chunk chunk, long nanos, MeshData data) {
        Log.noteWarning("Mesh", String.format(
                "区块 (%d,%d) 网格构建耗时 %.2f ms（超过阈值）—— 面数 %d，顶点 %.0f KB",
                chunk.cx(), chunk.cz(), nanos / 1_000_000.0, data.totalFaceCount(),
                data.totalVertexFloatCount() * Float.BYTES / 1024.0));
    }

    /** 顶点/索引的可增长缓冲。刻意不写"两遍扫描先数面再填充"—— 那会让网格化跑两遍。 */
    private static final class MeshBuilder {

        private float[] vertices = new float[1024 * MeshData.FLOATS_PER_VERTEX];
        private int[] indices = new int[1024 * 6];
        private int vertexFloats = 0;
        private int indexCount = 0;
        private int faces = 0;

        void pushVertex(float x, float y, float z, float r, float g, float b, float shade) {
            if (vertexFloats + MeshData.FLOATS_PER_VERTEX > vertices.length) {
                float[] bigger = new float[vertices.length * 2];
                System.arraycopy(vertices, 0, bigger, 0, vertexFloats);
                vertices = bigger;
            }
            vertices[vertexFloats++] = x;
            vertices[vertexFloats++] = y;
            vertices[vertexFloats++] = z;
            vertices[vertexFloats++] = r;
            vertices[vertexFloats++] = g;
            vertices[vertexFloats++] = b;
            vertices[vertexFloats++] = shade;
        }

        void pushIndex(int index) {
            if (indexCount + 1 > indices.length) {
                int[] bigger = new int[indices.length * 2];
                System.arraycopy(indices, 0, bigger, 0, indexCount);
                indices = bigger;
            }
            indices[indexCount++] = index;
        }

        void faceEmitted() {
            faces++;
        }

        int vertexCount() {
            return vertexFloats / MeshData.FLOATS_PER_VERTEX;
        }

        int faceCount() {
            return faces;
        }

        /** 截断到实际长度 —— 否则每区块都会把 2 倍容量的空数组传给 GPU。 */
        float[] toVertexArray() {
            float[] out = new float[vertexFloats];
            System.arraycopy(vertices, 0, out, 0, vertexFloats);
            return out;
        }

        int[] toIndexArray() {
            int[] out = new int[indexCount];
            System.arraycopy(indices, 0, out, 0, indexCount);
            return out;
        }
    }
}

package com.skyisland.render.mesh;

import com.skyisland.render.VertexFormat;
import com.skyisland.util.Coords;
import com.skyisland.util.Log;
import com.skyisland.world.Chunk;
import com.skyisland.world.LightEngine;
import com.skyisland.world.World;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;
import com.skyisland.world.block.BlockShape;
import com.skyisland.world.block.RenderType;

import java.util.function.IntFunction;

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
        return build(chunk, world, light, BlockRegistry::byRuntimeId);
    }

    /**
     * 构建区块网格（可注入方块解析器）。
     *
     * <p><b>为什么需要这个重载：</b>异形方块（十字面 / 半高台阶）在正式登记之前
     * （登记属S5）必须能被<b>端到端</b>验证 —— 只测"辅助类能算出 4 个面"
     * 是不够的，那无法排除"主循环压根没调用它"这种死接线。
     * 而把测试方块塞进 {@code BlockRegistry} 会污染全局注册表
     * （它一旦 bootstrap 就冻结，且 {@code BlockRegistryTest} 会因数量变化而变红），
     * 因此这里把"runtimeId → Block"的解析<b>提成一个参数</b>。
     *
     * <p>生产路径一律走 {@link BlockRegistry#byRuntimeId}，
     * 行为与本方法引入之前<b>逐字节一致</b>。
     *
     * @param blockLookup runtimeId → 方块；{@code null} 表示退回默认注册表
     */
    public static MeshData build(Chunk chunk, World world, LightEngine light,
                                 IntFunction<Block> blockLookup) {
        IntFunction<Block> lookup = blockLookup != null ? blockLookup : BlockRegistry::byRuntimeId;
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
                    Block block = lookup.apply(id);
                    RenderType type = block.renderType();
                    if (type == RenderType.INVISIBLE) {
                        continue;
                    }
                    MeshBuilder target = (type == RenderType.TRANSPARENT) ? transparent : opaque;

                    // 异形方块分派（PRD §7 R1）。十字面<b>不走 6 面循环</b>，
                    // 半高盒走 6 面循环但角点被替换为半高版本。
                    if (block.shape().isCross()) {
                        int skyLight = lights.skyLightAt(lx, ly, lz);
                        int torchLight = lights.torchLightAt(originX, originZ, lx, ly, lz);
                        float shade = BlockFace.NEG_Z.shade()
                                * lights.shadeFactor(Math.max(skyLight, torchLight));
                        float packedLight = VertexFormat.packLight(skyLight > 0, torchLight);
                        // 十字面作物只有一张层（美术规格 §3.20 裁定两向共用）
                        float layer = BlockTextureLayers.layerFor(block, BlockFace.POS_Y);
                        BlockTextureLayers.isValid((int) layer, "十字面方块 " + block.id());
                        NonFullMesh.emitCross(target, lx, ly, lz, block, shade, packedLight, layer);
                        continue;
                    }

                    for (BlockFace face : BlockFace.values()) {
                        Block neighbor = neighborBlock(chunk, world, lx, ly, lz, face, lookup);
                        if (!block.rendersFaceAgainst(neighbor)) {
                            continue;
                        }
                        // ★ M5a：必须把天光与火把**分开**取，不能只取 lightAt 的 max。
                        //   只取 max 会让顶点里丢失"这一份亮度是天光给的还是火把给的"，
                        //   而片元只有拿到两者才能做到「夜里火把不随天光变暗」（PRD §4.4）。
                        //   shade 仍按 max 计算 ⇒ 与改动前逐位一致（白天不会因为这次改动变色）。
                        int skyLight = lights.skyLightAt(lx, ly, lz);
                        int torchLight = lights.torchLightAt(originX, originZ, lx, ly, lz);
                        float shade = face.shade() * lights.shadeFactor(Math.max(skyLight, torchLight));
                        float packedLight = VertexFormat.packLight(skyLight > 0, torchLight);
                        // ★ 层号按**面**决定（三贴图规则，PRD §6.3）：
                        // 草方块顶面用 grass_top、侧面用 grass_side、底面用 dirt。
                        // 层号越界在GPU 上表现为黑块且不报错，故此处廉价断言一次。
                        float layer = BlockTextureLayers.layerFor(block, face);
                        BlockTextureLayers.isValid((int) layer,
                                "方块 " + block.id() + " 的 " + face + " 面");
                        emitFace(target, lx, ly, lz, face, block, shade, packedLight,
                                block.shape(), layer);
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
    private static Block neighborBlock(Chunk chunk, World world, int lx, int ly, int lz, BlockFace face,
                                   IntFunction<Block> lookup) {
        int nx = lx + face.dx();
        int ny = ly + face.dy();
        int nz = lz + face.dz();

        boolean insideX = nx >= 0 && nx < Coords.CHUNK_SIZE;
        boolean insideZ = nz >= 0 && nz < Coords.CHUNK_SIZE;
        boolean insideY = ny >= 0 && ny < Coords.CHUNK_HEIGHT;

        if (insideX && insideZ && insideY) {
            return lookup.apply(chunk.blockAt(nx, ny, nz));
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
     *
     * <p><b>{@code shape} 决定角点从哪来</b>：满方块用 {@link BlockFace} 的角点，
     * 半高台阶用 {@link NonFullMesh.SlabFace} 的半高角点。
     * 这是唯一需要为形态分派的地方 —— 剔除、光照、子网格归属三件事对所有形态一致。
     */
    private static void emitFace(MeshBuilder out, int lx, int ly, int lz, BlockFace face,
                                 Block block, float shade, float packedLight,
                                 BlockShape shape, float layer) {
        int baseIndex = out.vertexCount();
        for (int i = 0; i < BlockFace.QUAD_INDICES.length; i++) {
            // 索引先写，值由下面的顶点顺序决定 —— 这里不直接写 0,1,2,0,2,3 而是加基准，
            // 因为一个区块里所有面共用一个 VBO。
            out.pushIndex(baseIndex + BlockFace.QUAD_INDICES[i]);
        }
        if (shape.isFull()) {
            for (int corner = 0; corner < MeshData.VERTICES_PER_FACE; corner++) {
                float[] uv = face.cornerUv(corner);
                out.pushVertex(
                        lx + face.corner(corner, 0),
                        ly + face.corner(corner, 1),
                        lz + face.corner(corner, 2),
                        block.colorR(), block.colorG(), block.colorB(), shade,
                        packedLight, layer, uv[0], uv[1]);
            }
        } else {
            // 半高盒：角点显式给出（12 个 float = 4 角 × xyz），顺序与 BlockFace 一致
            float[] corners = NonFullMesh.SlabFace.cornersFor(face);
            for (int corner = 0; corner < MeshData.VERTICES_PER_FACE; corner++) {
                int base = corner * 3;
                // ★ UV 仍取自 BlockFace（满方块那一套）：半高盒与满方块的贴图
                // 朝向必须一致，否则同一张贴图在台阶与整块上会上下颠倒。
                float[] uv = face.cornerUv(corner);
                out.pushVertex(
                        lx + corners[base],
                        ly + corners[base + 1],
                        lz + corners[base + 2],
                        block.colorR(), block.colorG(), block.colorB(), shade,
                        packedLight, layer, uv[0], uv[1]);
            }
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
    private static final class MeshBuilder implements NonFullMesh.MeshSink {

        private float[] vertices = new float[1024 * MeshData.FLOATS_PER_VERTEX];
        private int[] indices = new int[1024 * 6];
        private int vertexFloats = 0;
        private int indexCount = 0;
        private int faces = 0;

        void pushVertex(float x, float y, float z, float r, float g, float b, float shade,
                        float packedLight, float layer, float u, float v) {
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
            // S2 新增的两个 float：纹理层号与（当时叫 AO 的）第二分量。
            // ★ M5a：第二分量改派为**打包光照**（VertexFormat#packLight），不再是 AO。
            // ★ 异形方块（十字面）也走这里 —— NonFullMesh.MeshSink 的实现
            // 就是本方法，所以"十字面补写了 layer"不是靠另写一遍，
            // 而是结构上不可能漏。这一点由 NonFullBlockMesherTest 钉死。
            vertices[vertexFloats++] = layer;
            vertices[vertexFloats++] = packedLight;
            // S3 新增：该面在贴图内的 UV（由 BlockFace.cornerUv 显式给出）。
            vertices[vertexFloats++] = u;
            vertices[vertexFloats++] = v;
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

        /**
         * 实现 {@link NonFullMesh.MeshSink}：异形方块（十字面）经此写入。
         *
         * <p>索引基准的处理与 {@link #emitFace} 完全相同 ——
         * 两条路径必须产出<b>同构</b>的缓冲（都是"4 顶点 + 6 索引"），
         * 否则 {@code MeshData} 里 {@code faceCount} 的换算关系会在两个分支间不一致，
         * 而那种不一致<b>不会报错</b>，只会在某个子网格里留下空洞。
         */
        @Override
        public void pushQuad(float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             Block block, float shade, float packedLight, float layer) {
            int baseIndex = vertexCount();
            for (int i = 0; i < BlockFace.QUAD_INDICES.length; i++) {
                pushIndex(baseIndex + BlockFace.QUAD_INDICES[i]);
            }
            // ★ 十字面是竖直对角面：4 个角恰好铺满整张贴图，
            // 因此 UV 直接取 (0,0)(1,0)(1,1)(0,1)（v=0 在上，符合美术规格 §2）。
            float r = block.colorR();
            float g = block.colorG();
            float b = block.colorB();
            pushVertex(x0, y0, z0, r, g, b, shade, packedLight, layer, 0f, 0f);
            pushVertex(x1, y1, z1, r, g, b, shade, packedLight, layer, 1f, 0f);
            pushVertex(x2, y2, z2, r, g, b, shade, packedLight, layer, 1f, 1f);
            pushVertex(x3, y3, z3, r, g, b, shade, packedLight, layer, 0f, 1f);
            faceEmitted();
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

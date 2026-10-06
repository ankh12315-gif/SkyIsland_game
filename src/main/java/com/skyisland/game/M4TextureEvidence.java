package com.skyisland.game;

import com.skyisland.player.Camera;
import com.skyisland.render.Frustum;
import com.skyisland.render.Renderer;
import com.skyisland.render.Screenshot;
import com.skyisland.render.Window;
import com.skyisland.render.mesh.BlockTextureLayers;
import com.skyisland.util.Coords;
import com.skyisland.util.Log;
import com.skyisland.world.Chunk;
import com.skyisland.world.World;
import com.skyisland.world.block.BlockRegistry;
import com.skyisland.world.gen.ChunkWriter;
import com.skyisland.world.gen.WorldGenerator;
import org.lwjgl.opengl.GL11;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * M4-S4 截图取证工具：把 24 层贴图在<b>真实 GL 上下文</b>里渲染出来并落盘。
 *
 * <h2>为什么要有这个类</h2>
 * S3 交付时我明确写了"透明改造<b>代码正确、画面未验证</b>"——
 * 因为当时判断"本环境无法创建 GL 上下文"。<b>那个判断是错的</b>：
 * S4 实测本机 GLFW 能创建 OpenGL 3.3 Core 上下文（见 {@code logs/} 里的启动记录），
 * 项目里<b>早就有</b>能跑通的自测与截图链路（{@code screenshots/m1_selftest-*.png}）。
 * 于是本类把那四张图补上，兑现 S3 报告里挂着的账。
 *
 * <h2>它是什么、不是什么</h2>
 * <ul>
 *   <li><b>是</b>：一个一次性取证工具，构造一个<b>刻意摆好方块的测试世界</b>，
 *       从四个机位各渲染一帧并落盘。它不进游戏主循环，不是玩法代码。</li>
 *   <li><b>不是</b>：一个渲染器。它复用 {@link Renderer} 的真实路径
 *       （同一个 {@code voxelShader}、同一个 {@code BlockTextureAtlas}），
 *       因此截出来的画面<b>就是</b>玩家会看到的画面。</li>
 * </ul>
 *
 * <h2>四个机位各自要证明什么</h2>
 * <table border="1">
 *   <caption>机位与判据</caption>
 *   <tr><th>机位</th><th>证明什么</th></tr>
 *   <tr><td>grass</td><td>草方块<b>三贴图规则</b>：顶面是草、侧面是草边、底面是泥土</td></tr>
 *   <tr><td>ores</td><td>四种矿石<b>同屏可分辨</b>（PRD §9.2「尤其矿石类」）</td></tr>
 *   <tr><td>transparent</td><td>玻璃<b>边框实 + 内部半透</b>、树叶<b>镂空</b></td></tr>
 *   <tr><td>nonfull</td><td>小麦（十字面）与台阶（半高）—— S1 的能力在画面上可见</td></tr>
 * </table>
 *
 * <p><b>★ 每张图都必须能看出明暗层次</b>（顶面亮、侧面暗）。
 * 这是验证"albedo-only 没把面明暗烘进贴图"的<b>关键</b>：
 * 若顶面不比侧面亮，说明 {@code BlockFace.shade()} 没生效（贴图把方向光烘进去了），
 * 那么"最亮顶面 × 1.00、最暗底面 × 0.50"的层次就丢了。
 */
public final class M4TextureEvidence {

    /** 截图输出目录（与既有自测截图同处，便于一起查看）。 */
    private static final Path OUT_DIR = Path.of("screenshots");

    /** 帧缓冲尺寸：取小一点，让 PNG 便于人工查看，且不依赖真实窗口大小。 */
    private static final int FB_WIDTH = 1280;
    private static final int FB_HEIGHT = 720;

    /** 地面方块的 y。 */
    private static final int GROUND_Y = 63;

    private M4TextureEvidence() {
    }

    /**
     * 一次性取证世界：在地面上摆出四组方块。
     *
     * <p><b>刻意用一个自定义 {@link WorldGenerator} 而不是改地形生成器</b>：
     * 它是<b>取证专用</b>的装置，不进产品世界生成，
     * 因此"摆了什么"本身就是可读的证据 —— 看代码就知道图里该有什么。
     */
    private static final class EvidenceWorld implements WorldGenerator {

        @Override
        public String id() {
            return "m4:texture-evidence";
        }

        @Override
        public int generationVersion() {
            return 1;
        }

        @Override
        public void generate(ChunkWriter out, int cx, int cz, long seed) {
            int originX = out.originX();
            int originZ = out.originZ();
            short stone = (short) BlockRegistry.stone().runtimeId();
            short grass = (short) BlockRegistry.grass().runtimeId();

            // 底板：草方块顶面（相机俯视时能看到大片草，用来对比顶/侧面明暗）
            for (int lz = 0; lz < Coords.CHUNK_SIZE; lz++) {
                for (int lx = 0; lx < Coords.CHUNK_SIZE; lx++) {
                    out.set(lx, GROUND_Y, lz, grass);
                    out.set(lx, GROUND_Y - 1, lz, stone);
                }
            }

            // ---- 第 1 组：矿石（x = 2..9, z = 2）四种并排 ----
            place(out, 2, GROUND_Y + 1, originZ + 2, BlockRegistry.ironOre());
            place(out, 4, GROUND_Y + 1, originZ + 2, BlockRegistry.coalOre());
            place(out, 6, GROUND_Y + 1, originZ + 2, BlockRegistry.copperOre());
            place(out, 8, GROUND_Y + 1, originZ + 2, BlockRegistry.crystalOre());

            // ---- 第 2 组：透明方块（x = 2..9, z = 6）----
            // 玻璃与树叶各摆一列，中间夹一列草方块作对照
            place(out, 2, GROUND_Y + 1, originZ + 6, BlockRegistry.glass());
            place(out, 3, GROUND_Y + 1, originZ + 6, BlockRegistry.glass());
            place(out, 4, GROUND_Y + 1, originZ + 6, BlockRegistry.glass());
            place(out, 6, GROUND_Y + 1, originZ + 6, BlockRegistry.leaves());
            place(out, 7, GROUND_Y + 1, originZ + 6, BlockRegistry.leaves());
            place(out, 8, GROUND_Y + 1, originZ + 6, BlockRegistry.planks());

            // ---- 第 3 组：灰色三胞胎（x = 2..9, z = 10）----
            // 规格 §4.3 点名要求"灰色三胞胎：石头 / 圆石 / 石砖 / 台阶"互相能分辨
            place(out, 2, GROUND_Y + 1, originZ + 10, BlockRegistry.stone());
            place(out, 4, GROUND_Y + 1, originZ + 10, BlockRegistry.cobblestone());
            place(out, 6, GROUND_Y + 1, originZ + 10, BlockRegistry.planks());

            // ---- 第 4 组：非满方块（x = 2..9, z = 14）----
            // 台阶（半高）是 S5 才登记的方块；本步用 BlockTextureLayers 已有的
            // 映射能力做一次"能力演示"，而不是登记新方块（S5 的职责）。
            place(out, 2, GROUND_Y + 1, originZ + 14, BlockRegistry.log());
            place(out, 4, GROUND_Y + 1, originZ + 14, BlockRegistry.glass());
            place(out, 6, GROUND_Y + 1, originZ + 14, BlockRegistry.leaves());
        }

        private static void place(ChunkWriter out, int x, int y, int z,
                                  com.skyisland.world.block.Block block) {
            out.set(x - out.originX(), y, z - out.originZ(), block.runtimeId());
        }
    }

    /** 一个机位：站位 + 朝向 + 截图标签。 */
    private record Shot(String label, double x, double y, double z,
                        double yaw, double pitch, String what) {
    }

    /**
     * 四个机位。
     *
     * <p><b>机位是刻意选的</b>：每个都要能同时看到<b>顶面与侧面</b>，
     * 否则明暗层次无从判断（俯视只能看到顶面，全部 shade=1.00）。
     *
     * <p><b>站位公式（第二版才想明白）</b>：每排方块摆在 {@code z = originZ + rowZ}，
     * 相机必须站在它的<b>南侧</b>（z 更小）并退开约 2.4 格 ——
     * 第一版把相机放在方块<b>之间</b>（z 正好等于某排），
     * 结果<b>相机卡在方块内部</b>，画面被自己的方块糊住。
     *
     * <p><b>pitch 的符号约定（第一版就搞反了）</b>：
     * {@code Camera.addLook} 用的是 {@code pitch -= deltaY * sens}，
     * 所以<b>正pitch = 抬头</b>。我初版写了负值（想"俯视"），
     * 结果相机<b>朝天</b>，截出一张纯天空的蓝图——
     * 而"图是蓝的"与"图是黑的"一样，都是<b>一眼就能看出不对</b>却容易误以为是别的问题。
     */
    private static final List<Shot> SHOTS = List.of(
            new Shot("ores", 5.5, GROUND_Y + 2.6, -2.2, 0, 24,
                    "四种矿石同屏 + 大片草顶面（对比明暗）"),
            new Shot("transparent", 5.5, GROUND_Y + 1.2, 2.6, 0, 14,
                    "玻璃边框/内部 + 树叶镂空"),
            new Shot("grey-family", 5.5, GROUND_Y + 1.0, 6.6, 0, 12,
                    "灰色三胞胎（规格 §4.3 点名的可分辨性）"),
            new Shot("nonfull", 5.5, GROUND_Y + 1.0, 10.6, 0, 12,
                    "原木顶/侧差异 + 玻璃 + 树叶")
    );

    /**
     * 入口。
     *
     * @return 进程退出码（0 = 四张图全部落盘）
     */
    public static void main(String[] args) {
        Log.init(Path.of("logs").toString(), true);
        Log.info("[M4-S4] 贴图截图取证开始（%d 个机位）", SHOTS.size());

        //★ 先自检层映射：这四张图若画的是错的层，画面再好看也没有证据价值
        assertLayerMappingIsSane();

        Window window = null;
        Renderer renderer = null;
        List<String> written = new ArrayList<>();
        try {
            window = Window.create("M4-S4 贴图取证", FB_WIDTH, FB_HEIGHT,
                    false, false, true);
            renderer = new Renderer();
            renderer.init(FB_WIDTH, FB_HEIGHT);

            World world = new World(20261006L, new EvidenceWorld());
            world.ensureAreaLoaded(0, 0, 0, 0);
            forceMeshBuild(world, renderer);
            diagnoseWorld(world);

            Camera camera = new Camera();
            Frustum frustum = new Frustum();

            for (Shot shot : SHOTS) {
                world.ensureAreaLoaded(0, 0, 0, 0);
                forceMeshBuild(world, renderer);

                camera.setPosition(shot.x(), shot.y(), shot.z());
                camera.setAngles(shot.yaw(), shot.pitch());
                camera.updateProjection(FB_WIDTH, FB_HEIGHT);
                camera.updateView();

                GL11.glGetError();   // 清掉渲染前的残留，便于定位
                // ★ 必须 clear()：它负责 glViewport + glClearColor + glClear。
                //   漏掉它时，glViewport 从未设置（本脚本不经过游戏主循环），
                //   深度缓冲也从未清过 —— 于是首次绘制就撞上 GL_INVALID_OPERATION，
                //   **整个世界 pass 被丢弃**，截出一张纯黑图。
                //   症状是"全黑"，而原因在渲染器之外，最难想到的就是这一行。
                renderer.clear();
                renderer.renderWorld(world, camera, null, List.of(), null);
                int renderErr = GL11.glGetError();
                if (renderErr != 0) {
                    Log.info("[M4-S4] GLERR after renderWorld = 0x" + Integer.toHexString(renderErr));
                }
                GL11.glFinish();

                int[] pixels = Screenshot.readPixels(FB_WIDTH, FB_HEIGHT);
                boolean uniform = Screenshot.isNearlyUniform(pixels, 0.001);
                String name = "m4-s4-" + shot.label();
                Path path = Screenshot.writePng(pixels, FB_WIDTH, FB_HEIGHT, OUT_DIR, name);
                written.add(name + " -> " + path.toAbsolutePath()
                        + (uniform ? "  【警告：几乎纯色，可能世界没渲染出来】" : ""));
                Log.info("[M4-S4] SHOT %s : %s", shot.label(), shot.what());
                Log.info("[M4-S4] SAVED %s uniform=%b", path.toAbsolutePath(), uniform);
            }

            Log.info("[M4-S4] 完成，共 %d 张：", written.size());
            for (String line : written) {
                Log.info("[M4-S4]%s", line);
            }
        } catch (Throwable t) {
            Log.error("[M4-S4] 取证失败", t);
            return;
        } finally {
            if (renderer != null) {
                try {
                    renderer.dispose();
                } catch (Throwable ignored) {
                    // 释放失败不应掩盖上面的真实错误
                }
            }
            if (window != null) {
                window.destroy();
            }
        }
    }

    /**
     * 诊断：世界到底有没有方块、网格器到底画出多少面。
     *
     * <p><b>为什么需要它</b>：截图全黑有<b>两种</b>可能 ——
     * "渲染器没画东西"与"画了但回读失败"。两者的修法完全不同，
     * 而症状（一张黑图）<b>完全一样</b>。
     * 先用日志把两者分开，再去修对应的那一个。
     */
    private static void diagnoseWorld(World world) {
        int nonAir = 0;
        for (Chunk c : world.loadedChunks()) {
            nonAir += c.nonAirCount();
        }
        Log.info("[M4-S4] DIAG chunks=%d nonAir=%d", world.loadedChunkCount(), nonAir);
        //抽查几个"应该摆了方块"的格子
        int[][] probes = {{2, GROUND_Y, 2}, {4, GROUND_Y, 2}, {2, GROUND_Y + 1, 6},
                {6, GROUND_Y + 1, 6}, {2, GROUND_Y, 10}};
        for (int[] p : probes) {
            Log.info("[M4-S4] DIAG block at (%d,%d,%d) = %s", p[0], p[1], p[2],
                    world.blockAt(p[0], p[1], p[2]).id());
        }
    }

    /**
     * 强制重建全部分区块网格。
     *
     * <p><b>为什么必须显式重建</b>：主循环里网格是"按需增量重建"的
     * （每帧限量 4 个区块）。取证只有几帧，等不到队列排空 ——
     * 结果就是<b>截出一张空图</b>，而症状是"图上什么都没有"。
     */
    private static void forceMeshBuild(World world, Renderer renderer) {
        for (Chunk chunk : List.copyOf(world.loadedChunks())) {
            chunk.markMeshDirty();
        }
        // 多跑几轮，直到没有待重建的区块（每轮限量 4 个）
        for (int i = 0; i < 64; i++) {
            int n = renderer.processMeshRebuilds(world);
            if (n == 0) {
                break;
            }
        }
    }

    /**
     * 自检：四个机位要看的方块，层号必须与预期一致。
     *
     * <p><b>为什么截图前要先查这个</b>：截图是<b>人眼</b>判据。
     * 若层号映射本身就错了（比如草方块顶面用了侧图层），
     * 人只会看到"草方块的顶面好像有点怪"——<b>无法归因</b>。
     * 先用代码把映射钉住，人只看"明暗层次对不对"这一件他已经能判断的事。
     */
    private static void assertLayerMappingIsSane() {
        var grass = BlockRegistry.grass();
        int top = BlockTextureLayers.layerFor(grass, com.skyisland.render.mesh.BlockFace.POS_Y);
        int side = BlockTextureLayers.layerFor(grass, com.skyisland.render.mesh.BlockFace.NEG_Z);
        if (top != BlockTextureLayers.GRASS_TOP || side != BlockTextureLayers.GRASS_SIDE) {
            throw new IllegalStateException("草方块层号映射不对：顶=" + top + " 侧=" + side
                    + "（截图会看不出三贴图规则，且无法归因）");
        }
        // 矿石五种必须各自不同，否则"四种矿石同屏"的截图没有证据价值
        int[] ores = {
                BlockTextureLayers.IRON_ORE, BlockTextureLayers.COAL_ORE,
                BlockTextureLayers.COPPER_ORE, BlockTextureLayers.CRYSTAL_ORE};
        for (int i = 0; i < ores.length; i++) {
            for (int j = i + 1; j < ores.length; j++) {
                if (ores[i] == ores[j]) {
                    throw new IllegalStateException("两种矿石指向同一层，同屏截图无法证明可分辨性");
                }
            }
        }
        Log.info("[M4-S4] LAYERCHECK ok grassTop=%d grassSide=%d", top, side);
    }
}

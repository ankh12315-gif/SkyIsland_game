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

    /**
     * 四排方块的 z 坐标 —— ★ <b>世界坐标</b>（不是 chunk 内偏移）。
     *
     * <p>★ 2026-10-07 从 2/6/10/14 拉开到 2/12/22/32，原因是<b>视野覆盖</b>：
     * 相机后退到 6.5 格才能把五种的 8 格横向跨度拍全（3.4 格时半宽只有约 2.4 格，
     * 金矿石与树叶会掉出画面），而相机一后退，前排就会挡住后排
     * —— 方块朝相机的那一面是渲染出来的，遮挡是真实的。
     * ⇒ 排间距必须大于相机距离，各排互不遮挡。
     *
     * <p>拉开的直接后果：四排分布在 cz = 0/ 0 / 1 / 2 三个 chunk 里，
     * 所以 {@code ensureAreaLoaded} 的**纵向**范围已相应扩大。
     *
     * <p>★ 中途踩过的坑：常量原本被当作"chunk 内偏移"用（写成 {@code originZ + ROW_Z}），
     * 改成 22 之后它就<b>越界</b>了 —— 于是第 3、4 排静默地一个都没摆，
     * 而画面依然有方块（底板在）、nonAir 依然正常、纯色检查依然通过。
     * ⇒ 现在常量一律是世界 z，由 {@code place()} 判断"是否落在本 chunk 内"。
     */
    private static final int ROW1_Z = 2;
    private static final int ROW2_Z = 12;
    private static final int ROW3_Z = 22;
    private static final int ROW4_Z = 32;

    /** 相机与目标排的距离（格）。必须小于排间距，否则拍不到全排。 */
    private static final double CAM_DIST = 6.5;

    /** 各排方块的横向中心（x）。五种跨度 x = 2..10，中心为 6。 */
    private static final double CAM_X_CENTER = 6.0;

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

            // ★ 2026-10-07：展示方块**不再**按"只让(0,0) 摆"来做。
            //   那种做法把"只在一个 chunk 摆"变成硬规则，而本轮四排分布在
            //   cz = 0 / 0 / 1 / 2 三个 chunk 里 —— 于是第 3、4 排**根本没有被摆**。
            //   症状极具欺骗性：画面非纯色、nonAir 正常，只是"最近的一排不见了"。
            //   正确做法见 place()：各 chunk 只摆落在自己范围内的那些方块。

            // 底板：草方块顶面（相机俯视时能看到大片草，用来对比顶/侧面明暗）。
            // ★ 底板**每chunk 都要铺**：只在 (0,0) 铺的话，后面两个 chunk 是空的，
            //   相机后退时画面下缘会出现"世界边缘"的黑洞，
            //   那个洞与"渲染器没画东西"在画面上很像。
            for (int lz = 0; lz < Coords.CHUNK_SIZE; lz++) {
                for (int lx = 0; lx < Coords.CHUNK_SIZE; lx++) {
                    out.set(lx, GROUND_Y, lz, grass);
                    out.set(lx, GROUND_Y - 1, lz, stone);
                }
            }

            // ★ 展示用方块的摆放由 place() 自行判断归属chunk（见 place 的注释）

            // ---- 第 1 组：矿石五种（x = 2..10, z = 2）----
            // ★ 2026-10-07 S5 落地：新增金矿石后"矿石类"由四种变五种。
            //   PRD §9.2 原文点名"尤其矿石类"必须互相能分辨，
            //   所以这一排必须补 —— 否则新增的那一种没有出现在任何证据里。
            place(out, 2, GROUND_Y + 1, ROW1_Z, BlockRegistry.ironOre());
            place(out, 4, GROUND_Y + 1, ROW1_Z, BlockRegistry.coalOre());
            place(out, 6, GROUND_Y + 1, ROW1_Z, BlockRegistry.copperOre());
            place(out, 8, GROUND_Y + 1, ROW1_Z, BlockRegistry.crystalOre());
            place(out, 10, GROUND_Y + 1, ROW1_Z, BlockRegistry.goldOre());

            // ---- 第 2 组：透明方块（x = 2..8, z = 12）----
            // 玻璃与树叶各摆一列，中间夹一列草方块作对照
            place(out, 2, GROUND_Y + 1, ROW2_Z, BlockRegistry.glass());
            place(out, 3, GROUND_Y + 1, ROW2_Z, BlockRegistry.glass());
            place(out, 4, GROUND_Y + 1, ROW2_Z, BlockRegistry.glass());
            place(out, 6, GROUND_Y + 1, ROW2_Z, BlockRegistry.leaves());
            place(out, 7, GROUND_Y + 1, ROW2_Z, BlockRegistry.leaves());
            place(out, 8, GROUND_Y + 1, ROW2_Z, BlockRegistry.planks());

            // ---- 第 3 组：灰色四胞胎（x = 2..8, z = 22）----
            // 规格 §4.3 点名要求"灰色三胞胎：石头 / 圆石 / 石砖 / 台阶"互相能分辨。
            // ★ 2026-10-07 S5 落地：石砖与台阶此前**不存在**，
            //   所以这一排当时只摆了石头 / 圆石 / 木板（拿木板占位）——
            //   规格点名的四种只到两种，规格 §4.3 实际上**没有被验证过**。
            //   现在两种都登记了，替换掉那个占位。
            place(out, 2, GROUND_Y + 1, ROW3_Z, BlockRegistry.stone());
            place(out, 4, GROUND_Y + 1, ROW3_Z, BlockRegistry.cobblestone());
            place(out, 6, GROUND_Y + 1, ROW3_Z, BlockRegistry.stoneBrick());
            place(out, 8, GROUND_Y + 1, ROW3_Z, BlockRegistry.slab());

            // ---- 第 4 组：非满方块 + 新增建材（x = 2..10, z = 32）----
            // ★ 2026-10-07 S5：这原本是"用已有方块演示异形能力"（台阶当时不存在），
            //   现在 wheat（十字面）与 slab（半高）都真登记了，
            //   这一排改为**真正拍到它们** —— 小麦是唯一牵动"看不见的墙"风险的方块，
            //   不拍它等于本轮最大风险没有画面证据。
            place(out, 2, GROUND_Y + 1, ROW4_Z, BlockRegistry.wheat());
            place(out, 4, GROUND_Y + 1, ROW4_Z, BlockRegistry.slab());
            place(out, 6, GROUND_Y + 1, ROW4_Z, BlockRegistry.ironBlock());
            place(out, 8, GROUND_Y + 1, ROW4_Z, BlockRegistry.log());
            place(out, 10, GROUND_Y + 1, ROW4_Z, BlockRegistry.leaves());
        }

        private static void place(ChunkWriter out, int x, int y, int z,
                                  com.skyisland.world.block.Block block) {
            int lx = x - out.originX();
            int lz = z - out.originZ();
            // ★★ 不在本 chunk 范围内就**跳过**，由那个 chunk 自己摆。
            //
            // 【真踩过】原先没有这个判断，直接 `out.set(x - originX, y, z - originZ, ...)`。
            // 只加载一个 chunk 时它是对的；一旦为了拉开排间距而加载多个 chunk，
            // 落在别的 chunk 里的方块会被算成**负数或越界的本地坐标**写进去
            // —— 而症状是"那一排在图上凭空消失"，很容易被误读成渲染问题。
            //
            // 中途试过的另一版修法（在 generate 里加 `cx==0 && cz==0` 守卫）**更糟**：
            // 它把"只让一个 chunk 摆"变成硬规则，于是**所有落在其他 chunk 的方块全都不摆**，
            // 画面上表现为"最近的那一排不见了，只拍到很远的一排"——
            // 而 nonAir 计数与纯色检查**全都正常**，因为地上确实有方块。
            if (lx < 0 || lx >= Coords.CHUNK_SIZE || lz < 0 || lz >= Coords.CHUNK_SIZE) {
                return;
            }
            out.set(lx, y, lz, block.runtimeId());
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
    /**
     * ★★ 机位方向约定（<b>2026-10-07 实测得出</b>，不是从代码推的）：
     *
     * <p>原来四个机位<b>全都拍错了对象</b>：它们分别站在 z = -2.2 / 2.6 / 6.6 / 10.6，
     * 却都以为自己在拍 z = 2 / 6 / 10 / 14 那四排。
     * 打开 grey-family 那张图才看出来 —— 画面里是<b>矿石</b>（铁/煤/铜/晶体），
     * 不是石砖与台阶，而且背景整片是天空。
     *
     * <p>由此确定两件事（都与"角度大小"无关）：
     * <ol>
     *   <li><b>{@code yaw = 0 是看向 -Z</b> ⇒ 相机必须站在目标排<b>z 更大</b>的一侧；</li>
     *   <li><b>正 pitch = 抬头</b> ⇒ 想拍到地面与方块顶面必须用<b>负 pitch</b>（低头）。</li>
     * </ol>
     *
     * <p>⇒ 定稿机位：{@code camera.z = 目标排 z + 3.4}，相机高度只比方块顶面高约 0.5 格，
     * {@code pitch = -12} 轻微俯视。
     *
     * <p>★ 这条与记忆里那条「非满方块测试机位第一版把相机放在方块之间，结果卡在方块内部」
     * 是同一类错误的两面：<b>机位必须对着被拍的对象验证过一次</b>，
     * 而"图不纯色"只说明<b>渲染器工作了</b>，不说明<b>拍的是要看的东西</b>。
     * 本例里三张图都"可用"（非纯色），却都在拍错的对象 —— 只有人眼看图才发现。
     */
    private static final List<Shot> SHOTS = List.of(
            new Shot("ores", CAM_X_CENTER, GROUND_Y + 1.5, ROW1_Z + CAM_DIST, 0, -12,
                    "五种矿石同屏（铁/煤/铜/晶体/金）"),
            new Shot("transparent", CAM_X_CENTER, GROUND_Y + 1.5, ROW2_Z + CAM_DIST, 0, -12,
                    "玻璃边框/内部 + 树叶镂空"),
            new Shot("grey-family", CAM_X_CENTER, GROUND_Y + 1.5, ROW3_Z + CAM_DIST, 0, -12,
                    "灰色四胞胎：石头 / 圆石 / 石砖 / 台阶（规格 §4.3 点名的可分辨性）"),
            new Shot("nonfull", CAM_X_CENTER, GROUND_Y + 1.5, ROW4_Z + CAM_DIST, 0, -12,
                    "小麦十字面 + 台阶半高 + 铁块最亮 + 原木顶/侧 + 树叶")
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
        List<String> uniformShots = new ArrayList<>();
        try {
            window = Window.create("M4-S4 贴图取证", FB_WIDTH, FB_HEIGHT,
                    false, false, true);
            renderer = new Renderer();
            renderer.init(FB_WIDTH, FB_HEIGHT);

            World world = new World(20261006L, new EvidenceWorld());
            // (minCx, minCz, maxCx, maxCz) —— 要的是**纵向** cz 0..2（四排 z=2/12/22/32）
        world.ensureAreaLoaded(0, 0, 0, 2);
            forceMeshBuild(world, renderer);
            diagnoseWorld(world);

            Camera camera = new Camera();
            Frustum frustum = new Frustum();

            for (Shot shot : SHOTS) {
                world.ensureAreaLoaded(0, 0, 0, 2);
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
                if (uniform) {
                    // ★★ 纯色图 = **没有证据价值**，必须让它变成一次失败，
                    //   而不是"打一行警告然后继续"。
                    //   理由（本项目真踩过）：原机位截出一张纯天空，
                    //   工具只 warn 了，进程照常退出 0 ——
                    //   于是"P1 截图取证完成"这句话是**假的**，
                    //   而证据链上看不出任何异常。
                    //   纯色 PNG 只有 4 KB，人工翻看时也最容易一眼滑过去。
                    uniformShots.add(shot.label());
                }
            }

            Log.info("[M4-S4] 完成，共 %d 张：", written.size());
            for (String line : written) {
                Log.info("[M4-S4]%s", line);
            }
            if (!uniformShots.isEmpty()) {
                // 用异常而非 System.exit：本工具是 main，抛出会被上面的 catch 抓住，
                // 所以直接落一条 ERROR 并让进程以非 0 结束。
                throw new IllegalStateException(
                        "以下机位截出的是**几乎纯色**的图（没有证据价值）：" + uniformShots
                                + "。截图取证不算完成。"
                                + "★ 常见根因：相机抬头角度过大 / 离目标太远，视线从方块排上方掠过，"
                                + "画面里只剩天空（表现为纯蓝）或只剩地面（表现为纯色）。"
                                + "修法：让机位与其余可用机位同构（距离约 3.4 格、pitch 12-14）。");
            }
        } catch (Throwable t) {
            Log.error("[M4-S4] 取证失败", t);
            // ★ 必须以非 0 退出。catch 之后 return 会让进程 exit 0，
            //   于是"取证失败"在自动化流程里与"取证成功"**无法区分**
            //   —— 这正是"完成"这句话会变成假话的地方。
            System.exit(1);
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
        // 抽查几个"应该摆了方块"的格子。
        // ★ S5 之后新增的格子也必须在列 —— 否则"格子坐标写错了"这件事
        //   只会表现为"那五种方块没出现在图上"，而人会误以为是贴图生成的问题。
        int[][] probes = {{2, GROUND_Y, ROW1_Z}, {4, GROUND_Y, ROW1_Z},
                {2, GROUND_Y + 1, ROW2_Z}, {6, GROUND_Y + 1, ROW2_Z},
                {2, GROUND_Y, ROW3_Z},
                // S5 新增：矿石第五种 + 灰色四胞胎的两种 + 非满方块两种
                {10, GROUND_Y + 1, ROW1_Z},   // 金矿石
                {6, GROUND_Y + 1, ROW3_Z},    // 石砖
                {8, GROUND_Y + 1, ROW3_Z},    // 台阶
                {2, GROUND_Y + 1, ROW4_Z},    // 小麦
                {10, GROUND_Y + 1, ROW4_Z}};  // 树叶

        // ★★ 诊断不能只"打印"这些格子，必须**逐个核对是不是预期的方块**。
        //   本轮真踩过：加了 `只让 (0,0) chunk 摆` 的守卫之后，
        //   第 3、4 排（落在 cz=1 / cz=2）**一个都没被摆**，
        //   而当时这 10 个探针里只有 3 个落在 cz=0 —— 它照样打印了 10 行 ID，
        //   看上去一切正常，真实情况是图上第 3 排消失、只拍到很远的一排。
        //   ⇒ 探针必须"期望值 vs 实际值"成对出现，缺一个就红。
        String[][] expected = {
                {"skyisland:grass_block", "第1排底板"},
                {"skyisland:grass_block", "第1排底板"},
                {"skyisland:glass", "第2排玻璃"},
                {"skyisland:leaves", "第2排树叶"},
                {"skyisland:grass_block", "第3排底板"},
                {"skyisland:gold_ore", "S5金矿石"},
                {"skyisland:stone_brick", "S5 石砖"},
                {"skyisland:slab", "S5 台阶"},
                {"skyisland:wheat", "S5 小麦"},
                {"skyisland:leaves", "第4排树叶"},
        };
        List<String> diagBad = new ArrayList<>();
        for (int i = 0; i < probes.length; i++) {
            int[] p = probes[i];
            String actual = world.blockAt(p[0], p[1], p[2]).id();
            String want = expected[i][0];
            Log.info("[M4-S4] DIAG block at (%d,%d,%d) = %s  期望 %s (%s)",
                    p[0], p[1], p[2], actual, want, expected[i][1]);
            if (!actual.equals(want)) {
                diagBad.add("(" + p[0] + "," + p[1] + "," + p[2] + ") 期望 "
                        + want + "(" + expected[i][1] + ") 实为 " + actual);
            }
        }
        if (!diagBad.isEmpty()) {
            throw new IllegalStateException("取证世界的探针与预期不符，截图**不可作为证据**：\n  "
                    + String.join("\n  ", diagBad)
                    + "\n★ 常见根因：那一排落在了别的 chunk 而没被摆"
                    + "（generate 只在部分 chunk 里摆方块）。");
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
        // 矿石五种必须各自不同，否则"五种矿石同屏"的截图没有证据价值
        int[] ores = {
                BlockTextureLayers.IRON_ORE, BlockTextureLayers.COAL_ORE,
                BlockTextureLayers.COPPER_ORE, BlockTextureLayers.CRYSTAL_ORE,
                BlockTextureLayers.GOLD_ORE};
        for (int i = 0; i < ores.length; i++) {
            for (int j = i + 1; j < ores.length; j++) {
                if (ores[i] == ores[j]) {
                    throw new IllegalStateException("两种矿石指向同一层，同屏截图无法证明可分辨性");
                }
            }
        }

        // ★★ S5 新增 5 种：逐一查它们是否**显式登记**，且层号合法。
        //   为什么必须查"显式登记"而不是查"层号合法"：
        //   layerForId 对未登记的方块会兜底成 FALLBACK = STONE，
        //   层号**照样合法**，于是截图里金矿石会变成一块石头 ——
        //   而画面上"矿石变成了石头"人未必一眼归因得了。
        //   （这条判据的出处见 BlockTexturesTest#everyRealBlockIsExplicitlyMappedNotFallingBack）
        String[] s5Ids = {"skyisland:gold_ore", "skyisland:wheat", "skyisland:stone_brick",
                "skyisland:iron_block", "skyisland:slab"};
        for (String id : s5Ids) {
            for (var face : com.skyisland.render.mesh.BlockFace.values()) {
                if (!BlockTextureLayers.isExplicitlyMapped(id, face)) {
                    throw new IllegalStateException(id + " 的 " + face + " 面没在贴图层映射表里显式登记 —— "
                            + "会静默画成石头（截图会看不出，且无法归因）");
                }
            }
        }
        // 灰色四胞胎（规格 §4.3 点名）：石头 / 圆石 / 石砖 / 台阶 必须是四个不同层
        int[] greys = {
                BlockTextureLayers.layerFor(BlockRegistry.stone(),
                        com.skyisland.render.mesh.BlockFace.POS_Y),
                BlockTextureLayers.layerFor(BlockRegistry.cobblestone(),
                        com.skyisland.render.mesh.BlockFace.POS_Y),
                BlockTextureLayers.layerFor(BlockRegistry.stoneBrick(),
                        com.skyisland.render.mesh.BlockFace.POS_Y),
                BlockTextureLayers.layerFor(BlockRegistry.slab(),
                        com.skyisland.render.mesh.BlockFace.POS_Y)};
        for (int i = 0; i < greys.length; i++) {
            for (int j = i + 1; j < greys.length; j++) {
                if (greys[i] == greys[j]) {
                    throw new IllegalStateException("灰色四胞胎里两种指向同一层（" + greys[i]
                            + "），规格 §4.3 的可分辨性在这张图上无法验证");
                }
            }
        }
        Log.info("[M4-S4] LAYERCHECK ok grassTop=%d grassSide=%d ores=%d greys=%d s5Mapped=5",
                top, side, ores.length, greys.length);
    }
}

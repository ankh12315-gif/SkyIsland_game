package com.skyisland.game;

import com.skyisland.player.Camera;
import com.skyisland.render.Renderer;
import com.skyisland.render.Screenshot;
import com.skyisland.render.Window;
import com.skyisland.util.Log;
import com.skyisland.world.Chunk;
import com.skyisland.world.DayClock;
import com.skyisland.world.World;
import com.skyisland.world.gen.WorldGenerator;
import com.skyisland.world.block.BlockRegistry;
import org.lwjgl.opengl.GL11;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ★ M5a：昼夜循环的<b>像素级取证</b>工具。
 *
 * <h3>为什么单测证明不了这件事</h3>
 * <p>昼夜的画面公式写在 {@code voxel.frag} 里，而 GLSL <b>不能在 JVM 单测里执行</b>。
 * 于是唯一诚实的做法有两条，本工具走第二条：
 * <ol>
 *   <li>把公式抄一份到测试里 —— <b>本项目明令禁止</b>：两边一起错而断言仍然全绿，
 *       这正是记忆里那条「测试里重抄一遍渲染器算法」的失败模式；</li>
 *   <li><b>真的把画面渲染出来读像素</b>。这需要窗口与 GPU，
 *       所以它是一个 {@code main} 而不是 {@code @Test}。</li>
 * </ol>
 *
 * <h3>本工具断言的四件事</h3>
 * <ol>
 *   <li><b>白天画面可用</b>：不是纯色，且平均亮度落在合理区间；</li>
 *   <li><b>夜晚真的更暗</b>：平均亮度显著低于白天（PRD §4.4 的 15% 口径）；</li>
 *   <li><b>★ 火把在夜里不跟着天光暗</b>：火把方块自身像素的亮度，
 *       昼夜之比必须<b>显著高于</b>普通地表方块的亮度比 ——
 *       这是 PRD §4.4「火把成为主要照明」唯一能被像素证明的形式；</li>
 *   <li><b>纯色图 = 失败</b>：沿用 M4-S4 的判据（纯色图没有证据价值，
 *       而且只有 4 KB，人工翻看时最容易一眼滑过去）。</li>
 * </ol>
 *
 * <h3>失败必须以非 0 退出</h3>
 * <p>{@code catch} 之后 {@code return} 会让进程 exit 0，于是"取证失败"在自动化流程里
 * 与"取证成功"<b>无法区分</b> —— 这正是"完成"这句话会变成假话的地方。
 */
public final class M5DayNightEvidence {

    private static final Path OUT_DIR = Path.of("screenshots");
    private static final Path SHADER_SRC_DIR = Path.of("src/main/resources/shaders");

    /**
     * 自证 classpath：classpath 里读到的着色器必须与 {@code src/main/resources/shaders}
     * 下的源文件<b>逐字节一致</b>。
     *
     * <p>不一致意味着 classpath 上排在前面的那份是过期副本（典型是 Maven 复制进
     * {@code target/classes} 的那份）。此时工具会渲染一份<b>与当前源码无关</b>的画面，
     * 然后给出 PASS —— <b>比直接失败更坏，因为它让人相信证据已经拿到了</b>。
     *
     * <p>修法在调用方：classpath 必须是 {@code src/main/resources;target/classes;…}，
     * 源码树在前。
     */
    private static void assertShadersComeFromSourceTree() {
        String[] names = {"voxel.vert", "voxel.frag", "ui.vert", "ui.frag"};
        for (String name : names) {
            String resource = "shaders/" + name;
            byte[] fromClasspath;
            try (java.io.InputStream in = M5DayNightEvidence.class
                    .getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("classpath 里读不到着色器: " + resource);
                }
                fromClasspath = in.readAllBytes();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("读取着色器资源失败: " + resource, e);
            }
            Path source = SHADER_SRC_DIR.resolve(name);
            if (!java.nio.file.Files.exists(source)) {
                throw new IllegalStateException("着色器源文件不存在: " + source
                        + "（本守卫要求在项目根目录下运行）");
            }
            byte[] fromSource;
            try {
                fromSource = java.nio.file.Files.readAllBytes(source);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("读着色器源文件失败: " + source, e);
            }
            if (!java.util.Arrays.equals(fromClasspath, fromSource)) {
                throw new IllegalStateException(
                        "★ classpath 里的 " + resource + " 与源码树 " + source + " 不一致 ——"
                                + "渲染器读到的是过期副本，本次取证的画面与当前源码无关。"
                                + "请把 classpath 改为 src/main/resources 在 target/classes **之前**："
                                + "  -cp \"src/main/resources;target/classes;…\"");
            }
        }
        Log.info("[M5a] classpath 自证通过：4 个着色器与 src/main/resources 逐字节一致");
    }
    private static final int FB_WIDTH = 1280;
    private static final int FB_HEIGHT = 720;

    /** 取证世界的地面高度。 */
    private static final int GROUND_Y = 63;

    /** 火把摆在这里（世界坐标）。它自发光等级 14，是唯一的自然光源。 */
    private static final int TORCH_X = 6;
    private static final int TORCH_Z = 12;

    /**
     * 要做对比测量的两个像素位置（帧缓冲坐标）。
     *
     * <p><b>为什么必须选定两个具体点</b>：只比"整幅平均亮度"是不够的 ——
     * 夜里天空占了画面一半以上，它一变暗，平均值必然下降，
     * 于是"火把也变暗了"这个 bug 会被平均掉。
     * 要证明火把免疫，必须<b>指着火把那一块</b>去比。
     */
    private static final int PROBE_TORCH_X = 640;
    private static final int PROBE_TORCH_Y = 470;

    private M5DayNightEvidence() {
    }

    /** 取证专用世界：地面 + 一根火把。刻意不改产品地形生成器。 */
    private static final class EvidenceWorld implements WorldGenerator {
        @Override
        public String id() {
            return "m5:day-night-evidence";
        }

        @Override
        public int generationVersion() {
            return 1;
        }

        @Override
        public void generate(com.skyisland.world.gen.ChunkWriter out, int cx, int cz, long seed) {
            int originX = out.originX();
            int originZ = out.originZ();
            short stone = (short) BlockRegistry.stone().runtimeId();
            short grass = (short) BlockRegistry.grass().runtimeId();
            short torch = (short) BlockRegistry.torch().runtimeId();
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int y = GROUND_Y - 3; y <= GROUND_Y - 1; y++) {
                        out.set(lx, y, lz, stone);
                    }
                    out.set(lx, GROUND_Y, lz, grass);
                }
            }
            int tlx = TORCH_X - originX;
            int tlz = TORCH_Z - originZ;
            if (tlx >= 0 && tlx < 16 && tlz >= 0 && tlz < 16) {
                out.set(tlx, GROUND_Y + 1, tlz, torch);
            }
        }
    }

    public static void main(String[] args) {
        Log.init(Path.of("logs").toString(), true);

        // ★★ 先自证 classpath：classpath 里若 target/classes 排在 src/main/resources 之前，
        //   着色器会读到 Maven 复制过去的**旧副本** —— 于是"改了源文件、跑取证、
        //   拿到漂亮的 PASS"而实际验证的根本不是当前代码。
        //   本项目真踩过：第一版注入保留字 packed 进源文件后，工具照样编译成功并 PASS，
        //   因为它读的是干净的旧副本，那次证据**完全无效**。
        //   与「Maven 增量 compile 不可信」「jar 必须先冻结」是同一族：
        //   被测物与证据源不是同一个。守卫必须自己咬人，不能只靠人记得 classpath 顺序。
        assertShadersComeFromSourceTree();

        Log.info("[M5a] 昼夜像素取证开始");

        Window window = null;
        Renderer renderer = null;
        List<String> failures = new ArrayList<>();
        try {
            window = Window.create("M5a 昼夜取证", FB_WIDTH, FB_HEIGHT, false, false, true);
            renderer = new Renderer();
            renderer.init(FB_WIDTH, FB_HEIGHT);

            World world = new World(20261008L, new EvidenceWorld());
            world.ensureAreaLoaded(0, 0, 0, 0);
            forceMeshBuild(world, renderer);

            Camera camera = new Camera();
            camera.setPosition(6.0, GROUND_Y + 2.2, 16.0);
            camera.setAngles(0, -10);
            camera.updateProjection(FB_WIDTH, FB_HEIGHT);
            camera.updateView();

            DayClock clock = new DayClock();
            double dayLuma = renderAt(renderer, world, camera, clock, 300.0, "day");
            double nightLuma = renderAt(renderer, world, camera, clock, 900.0, "night");

            double torchDay = torchPixel(renderer, world, camera, clock, 300.0);
            double torchNight = torchPixel(renderer, world, camera, clock, 900.0);

            Log.info("[M5a] 平均亮度 day=%.1f night=%.1f（比值 %.3f）",
                    dayLuma, nightLuma, dayLuma <= 0 ? 0 : nightLuma / dayLuma);
            Log.info("[M5a] 火把点亮度 day=%.1f night=%.1f（比值 %.3f）",
                    torchDay, torchNight, torchDay <= 0 ? 0 : torchNight / torchDay);

            // ① 白天可用
            if (dayLuma < 8.0) {
                failures.add("白天画面平均亮度只有 " + dayLuma + " —— 几乎全黑，没有证据价值");
            }
            // ② 夜晚显著更暗
            double globalRatio = dayLuma <= 0 ? 0 : nightLuma / dayLuma;
            if (!(globalRatio > 0.0 && globalRatio < 0.75)) {
                failures.add("夜晚与白天的整幅亮度比是 " + globalRatio
                        + "，应在 0..0.75 之间（PRD §4.4 夜晚为白天的 15%）");
            }
            // ③ ★ 火把免疫：火把点的亮度比必须明显高于整幅亮度比
            double torchRatio = torchDay <= 0 ? 0 : torchNight / torchDay;
            if (!(torchRatio > 0.0 && torchRatio > globalRatio + 0.15)) {
                failures.add("火把点亮度比 " + torchRatio
                        + " 没有显著高于整幅亮度比 " + globalRatio
                        + " —— 说明火把跟着天光一起暗了，违反 PRD §4.4「火把成为主要照明」");
            }

            if (failures.isEmpty()) {
                Log.info("[M5a] PASS：白天可用、夜晚更暗、火把保持亮度");
                Log.info("[M5a] 截图见 %s", OUT_DIR.toAbsolutePath());
            } else {
                for (String f : failures) {
                    Log.error("[M5a] FAIL %s", f);
                }
                throw new IllegalStateException("昼夜像素取证失败：" + failures);
            }
        } catch (Throwable t) {
            Log.error("[M5a] 取证失败", t);
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

    /** 在指定时刻渲染一帧、落盘，并返回平均亮度（0..255）。 */
    private static double renderAt(Renderer renderer, World world, Camera camera,
                                   DayClock clock, double timeSeconds, String label)
            throws java.io.IOException {
        int[] pixels = shoot(renderer, world, camera, clock, timeSeconds, label);
        if (Screenshot.isNearlyUniform(pixels, 0.001)) {
            throw new IllegalStateException(
                    "「" + label + "」截出的是**几乎纯色**的图（没有证据价值）。"
                            + "常见根因：相机抬头角度过大 / 离目标太远，视线从方块上方掠过，"
                            + "画面里只剩天空。");
        }
        return meanLuma(pixels);
    }

    /** 只取火把所在那一块像素的亮度（不落盘，避免多出一堆图）。 */
    private static double torchPixel(Renderer renderer, World world, Camera camera,
                                     DayClock clock, double timeSeconds)
            throws java.io.IOException {
        int[] pixels = shoot(renderer, world, camera, clock, timeSeconds, "probe");
        int idx = PROBE_TORCH_Y * FB_WIDTH + PROBE_TORCH_X;
        return luma(pixels[idx]);
    }

    private static int[] shoot(Renderer renderer, World world, Camera camera,
                               DayClock clock, double timeSeconds, String label)
            throws java.io.IOException {
        clock.setTimeSeconds(timeSeconds);
        renderer.setDaylight(clock);

        GL11.glGetError();
        // ★ 必须 clear()：它负责 glViewport + glClearColor + glClear。
        renderer.clear();
        renderer.renderWorld(world, camera, null, List.of(), null);
        GL11.glFinish();

        int[] pixels = Screenshot.readPixels(FB_WIDTH, FB_HEIGHT);
        if (!"probe".equals(label)) {
            Path path = Screenshot.writePng(pixels, FB_WIDTH, FB_HEIGHT, OUT_DIR,
                    "m5a-daynight-" + label);
            Log.info("[M5a] SAVED %s", path.toAbsolutePath());
        }
        return pixels;
    }

    private static void forceMeshBuild(World world, Renderer renderer) {
        for (Chunk chunk : List.copyOf(world.loadedChunks())) {
            chunk.markMeshDirty();
        }
        for (int i = 0; i < 64; i++) {
            if (renderer.processMeshRebuilds(world) == 0) {
                break;
            }
        }
    }

    private static double meanLuma(int[] argb) {
        double sum = 0;
        for (int p : argb) {
            sum += luma(p);
        }
        return sum / Math.max(1, argb.length);
    }

    /** Rec. 601 亮度（ARGB 打包整数）。 */
    private static double luma(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }
}
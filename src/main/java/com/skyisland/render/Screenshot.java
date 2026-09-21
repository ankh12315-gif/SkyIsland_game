package com.skyisland.render;

import com.skyisland.util.Log;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * 帧缓冲截图（TECH_DESIGN_v0.1.1 §T′.1 第 2 条）。
 *
 * <p><b>为什么截图是 M1 的必需能力，而不是"顺手加的调试功能"：</b>
 * 本机实测<u>无法从外部注入键盘输入</u>（TR7：{@code SendInput} 到任何窗口都收不到回调），
 * 因此"交互功能是否真的存在"不能靠"模拟按键然后看画面"来举证。
 * {@code glReadPixels} 从帧缓冲直接取像素，<b>完全不经过 OS 输入系统</b>，
 * 是这台机器上唯一可用的可视化证据通道。
 *
 * <p><b>两段式设计（为了不污染性能统计）：</b>
 * <ol>
 *   <li>{@link #readPixels} —— GL 调用，<b>必须在 GL 线程、且在两缓冲交换之前</b>执行。
 *       代价是一次 GPU→CPU 的像素回读（1280×720 约 3.7 MB，几毫秒）；</li>
 *   <li>{@link #writePngAsync} —— 落盘（PNG 编码通常几十毫秒），放到后台线程执行。</li>
 * </ol>
 * 这样"截图"对帧时间的贡献只有回读那几毫秒，而不是编码+写盘的全部开销。
 * 依据是 §C.4′ 第 7 条：测试脚手架不得在主循环内制造假卡顿。
 * 即便如此，M1 的性能测量窗口仍然与截图时刻<u>分开</u>（见 M1 报告）。
 */
public final class Screenshot {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private Screenshot() {
    }

    /**
     * 从当前帧缓冲回读像素，返回<b>已上下翻转</b>的 ARGB 数组（便于直接写 PNG）。
     *
     * <p>OpenGL 的像素原点在左下角，而图像文件的原点在左上角 ——
     * 不翻转会得到一张上下颠倒的图（这在"世界看起来是反的"时会误导排查方向）。
     *
     * @return ARGB 数组，长度 = width × height
     */
    public static int[] readPixels(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int[] argb = new int[w * h];

        // ★ 必须设 PACK_ALIGNMENT = 1：默认 4 字节对齐在宽度不是 4 的倍数时会把每行补齐，
        //   导致读回来的像素整体错位（画面呈斜向撕裂）。
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

        ByteBuffer buffer = MemoryUtil.memAlloc(w * h * 4);
        try {
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            for (int row = 0; row < h; row++) {
                int sourceRow = h - 1 - row;           // 上下翻转
                int sourceBase = sourceRow * w * 4;
                int targetBase = row * w;
                for (int column = 0; column < w; column++) {
                    int offset = sourceBase + column * 4;
                    int r = buffer.get(offset) & 0xFF;
                    int g = buffer.get(offset + 1) & 0xFF;
                    int b = buffer.get(offset + 2) & 0xFF;
                    int a = buffer.get(offset + 3) & 0xFF;
                    argb[targetBase + column] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
        } finally {
            MemoryUtil.memFree(buffer);
        }
        String glError = GlDiagnostics.drainError();
        if (glError != null) {
            Log.noteWarning("Screenshot", "glReadPixels 后检测到 GL 错误: " + glError);
        }
        return argb;
    }

    /** 同步写 PNG（供测试与收尾阶段使用；正常截图走 {@link #writePngAsync}）。 */
    public static Path writePng(int[] argb, int width, int height, Path directory, String baseName)
            throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(baseName + ".png");
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        if (!ImageIO.write(image, "png", target.toFile())) {
            throw new IOException("没有可用的 PNG 编码器（ImageIO 未找到 writer）");
        }
        return target;
    }

    /**
     * 后台线程写 PNG。
     *
     * @param onDone 写入完成后的回调（在主循环线程之外执行，回调里不要碰 GL）
     */
    public static Thread writePngAsync(int[] argb, int width, int height,
                                      Path directory, String baseName, Consumer<Path> onDone) {
        Thread thread = new Thread(() -> {
            try {
                Path path = writePng(argb, width, height, directory, baseName);
                Log.info("[截图] 已写入 %s", path.toAbsolutePath());
                if (onDone != null) {
                    onDone.accept(path);
                }
            } catch (Throwable t) {
                Log.error("[截图] 写 PNG 失败", t);
            }
        }, "skyisland-screenshot");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** 时间戳文件名（不含扩展名）。 */
    public static String timestampName(String prefix) {
        return prefix + "-" + LocalDateTime.now().format(STAMP);
    }

    /** 图像是否"几乎全是同一个颜色"——自测用：全黑/全天空色通常意味着渲染没有产出。 */
    public static boolean isNearlyUniform(int[] argb, double tolerance) {
        if (argb.length == 0) {
            return true;
        }
        int first = argb[0];
        int different = 0;
        int sampleStep = Math.max(1, argb.length / 20000);
        int sampled = 0;
        for (int i = 0; i < argb.length; i += sampleStep) {
            sampled++;
            if (argb[i] != first) {
                different++;
            }
        }
        return sampled == 0 || (different / (double) sampled) < tolerance;
    }
}

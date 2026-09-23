package com.skyisland.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 截图证据本身的可信度护栏。
 *
 * <p><b>为什么需要这些断言：</b>M2.2 的视觉验收截图一度被误读成"背包面板没画出来" ——
 * 实测面板像素是 {@code #151D29}（深色底 + 亮格线，对比清晰），
 * 但 PNG 里带着帧缓冲那套<b>混合副产物 alpha</b>（0.67–0.78），
 * 任何会做合成的看图工具把它合到白底，就渲染成一片发灰。
 *
 * <p>一条更早的教训是同族的：M2.1 的"看不见的怪物"里，
 * <u>证据通道本身没有被验证过</u>，于是"绿灯"解释不了自己为什么绿。
 * 这里补的就是证据通道：先证明写出来的图是可信的，再拿它当视觉验收。
 */
class ScreenshotTest {

    /** 一组带"半透明 alpha"的像素：模拟帧缓冲混合后留下的那种 alpha。 */
    private static int[] framebufferLike(int count) {
        int[] argb = new int[count];
        for (int i = 0; i < count; i++) {
            int r = (i * 7) & 0xFF;
            int g = (i * 13) & 0xFF;
            int b = (i * 29) & 0xFF;
            int a = 0x80 + (i % 0x40);   // 明显不是 255，也不是常量
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return argb;
    }

    @Test
    void writtenPngIsOpaqueEvenWhenTheFramebufferAlphaIsNot(@TempDir Path dir) throws Exception {
        int[] argb = framebufferLike(320);
        Path png = Screenshot.writePng(argb, 16, 20, dir, "probe");

        BufferedImage image = ImageIO.read(png.toFile());
        assertFalse(image.getColorModel().hasAlpha(),
                "写出的 PNG 带 alpha 通道 —— 看图工具会做合成，界面会被误读成发灰");

        int[] back = image.getRGB(0, 0, 16, 20, null, 0, 16);
        for (int i = 0; i < back.length; i++) {
            assertEquals(0xFF, (back[i] >>> 24) & 0xFF,
                    "第 " + i + " 个像素不是不透明的（alpha=" + ((back[i] >>> 24) & 0xFF) + "）");
        }
    }

    @Test
    void writtenPngPreservesRgbExactly(@TempDir Path dir) throws Exception {
        int[] argb = framebufferLike(64);
        Path png = Screenshot.writePng(argb, 8, 8, dir, "rgb");

        BufferedImage image = ImageIO.read(png.toFile());
        int[] back = image.getRGB(0, 0, 8, 8, null, 0, 8);
        for (int i = 0; i < back.length; i++) {
            assertEquals(argb[i] & 0xFFFFFF, back[i] & 0xFFFFFF,
                    "第 " + i + " 个像素的 RGB 被改动了 —— 读图方（像素门 / 证据统计）"
                            + "拿到的不再是屏幕上那个颜色");
        }
    }

    @Test
    void uniformDetectionStillWorksOnTheWrittenImage(@TempDir Path dir) throws Exception {
        // 这张图应当被判为"几乎全同色"——它正是 isNearlyUniform 要抓的那种失败画面
        int[] flat = new int[32 * 32];
        java.util.Arrays.fill(flat, 0xC0202020);
        Path png = Screenshot.writePng(flat, 32, 32, dir, "flat");
        BufferedImage image = ImageIO.read(png.toFile());
        int[] back = image.getRGB(0, 0, 32, 32, null, 0, 32);
        assertTrue(Screenshot.isNearlyUniform(back, 0.01), "全同色画面必须被判定为无产出");

        // 而这张有内容
        int[] varied = framebufferLike(32 * 32);
        Path png2 = Screenshot.writePng(varied, 32, 32, dir, "varied");
        int[] back2 = ImageIO.read(png2.toFile()).getRGB(0, 0, 32, 32, null, 0, 32);
        assertFalse(Screenshot.isNearlyUniform(back2, 0.01), "有内容的画面不得被判为无产出");
    }
}

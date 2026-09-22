package com.skyisland.game;

import java.util.HashMap;
import java.util.Map;

/**
 * M2.1：把"帧缓冲里到底有没有怪物"变成一个<b>可断言的像素判据</b>（纯函数，无 GL 依赖）。
 *
 * <h2>为什么需要它（用户规格 §8 的硬要求）</h2>
 * 在它出现之前，"怪物出现在视野里"的全部证据只有三种读数：写出的顶点数、盒体数、
 * 以及一张<b>从来没人看过</b>的截图。前两者证明的只是"提交了多少几何"，
 * 而"提交了几何"与"屏幕上真的出现了像素"是两件可以独立失败的事 ——
 * 本项目恰好发生过一次：怪物顶点的世界 Y 丢了（{@code EntityRenderer} 的 pivotY 被写成 0），
 * 几何照样提交、盒体数照样正确、日志照样打印"已生成近战怪"，
 * 而屏幕上<b>一个像素都没有</b>。所以规格明确要求：
 * <blockquote>至少一个截图或像素区域证据……不能只测试 entity count。</blockquote>
 *
 * <h2>颜色口径：为什么是 {@code round(分量 × 255)}</h2>
 * 本项目渲染管线不做 sRGB 与线性的互相转换（源码里没有 {@code GL_FRAMEBUFFER_SRGB}，
 * 着色器里也没有 gamma 运算），实体顶点色是直接写进帧缓冲的显示值；
 * 且实体路径的 {@code aColor.a} 恒为 1.0，实体<b>没有面明暗</b>
 * （见 {@code MonsterModel} 的配色注释与 {@code EntityRenderer#buildMonsterVertices}）。
 * 因此期望帧缓冲值就是 {@code round(分量 × 255)}。
 * <b>但这条换算口径本身也必须先被实测验证</b>（纪律：测量仪器必须先被验证），
 * 而不是凭注释写死 —— 见交付报告里"实测到的像素值"与 {@link #DEFAULT_TOLERANCE} 的取值依据。
 *
 * <h2>为什么用"中央有界区域"而不是整屏</h2>
 * 如果只在整屏范围里数"怪物色的像素"，画面边缘恰好像怪物色的地形/物品也会被算进来 ——
 * 那是把背景噪声当成证据。因此命中必须落在<b>中央的一个有界矩形</b>内
 * （由 {@link #analyze} 的 {@code regionW}/{@code regionH} 指定，例如中央 40% 宽 × 60% 高），
 * 同时<b>分开报告</b>中央命中数与整屏命中数，让"中央有、整屏几乎也是这些"这件事可见。
 */
public final class MonsterPixelEvidence {

    /**
     * 与 {@code MonsterModel.COLOR_*} 一一对应的期望 8 位颜色（{@code round(c*255)}）。
     *
     * <p>顺序：头 / 躯干 / 臂 / 腿（四个"体色"），眼睛单列。体色偏红、在天空与草地前都分得开，
     * 是判"怪真的出现了"的稳健依据；眼睛接近纯黄，且准星也是黄色系，故只报告、不进主判据。
     */
    public static final int[][] BODY_COLORS = {
            {235, 117, 97},   // HEAD   (0.92, 0.46, 0.38)
            {199, 99, 84},    // TORSO  (0.78, 0.39, 0.33)
            {163, 82, 69},    // ARM    (0.64, 0.32, 0.27)
            {128, 64, 54},    // LEG    (0.50, 0.25, 0.21)
    };

    /** 眼睛色 {@code (1.00, 0.84, 0.30)}。与准星同色，只作参考。 */
    public static final int[] EYE_COLOR = {255, 214, 77};

    /**
     * 命中判据的通道容差（每通道 {@code |实测 − 期望| <= tol} 即算命中）。
     *
     * <p>取值依据是<b>实测</b>而非推算：把 monster-in-view 截图里的体色逐点回读，
     * 与 {@code round(c*255)} 的最大偏差在 1 以内（显卡把 float 转 unorm8 就是四舍五入），
     * 12 已是一个数量级以上的余量；同时它把准星黄 {@code (248,216,96)} 挡在眼睛色之外
     * （该色的蓝通道偏差 19 > 12）——即"容差够松到容纳真实渲染、又够紧到不误收背景"。
     */
    public static final int DEFAULT_TOLERANCE = 12;

    private MonsterPixelEvidence() {
    }

    /**
     * 一次分析的读数。
     *
     * @param width/height          帧尺寸
     * @param regionX0..regionY1    判定用的中央有界区域（右/下开区间）
     * @param bodyHitsCenter        中央区域内命中四种体色的像素数（<b>主判据</b>）
     * @param bodyHitsFull          整屏命中四种体色的像素数（与上者分开报告）
     * @param eyeHitsCenter         中央区域内命中眼睛色的像素数（参考；准星也会落在这里）
     * @param bodyPerColorCenter    中央区域内四种体色各自的命中数（顺序同 {@link #BODY_COLORS}）
     */
    public record Result(int width, int height,
                         int regionX0, int regionY0, int regionX1, int regionY1,
                         int bodyHitsCenter, int bodyHitsFull,
                         int eyeHitsCenter, int[] bodyPerColorCenter) {

        /** 一句话读数，供日志/摘要起见。 */
        public String describe() {
            return String.format(
                    "帧 %dx%d，中央区域 [%d,%d)-[%d,%d)；体色命中：中央=%d 整屏=%d；"
                            + "眼睛色命中（参考）=%d；四体色分项(头/躯干/臂/腿)=%s",
                    width, height, regionX0, regionY0, regionX1, regionY1,
                    bodyHitsCenter, bodyHitsFull, eyeHitsCenter,
                    java.util.Arrays.toString(bodyPerColorCenter));
        }

        /** 中央区域的像素总数（用来把命中数写成占比）。 */
        public int centerPixelCount() {
            return Math.max(0, regionX1 - regionX0) * Math.max(0, regionY1 - regionY0);
        }
    }

    /**
     * 在 {@code argb}（ARGB 打包整数，与 {@code Screenshot.readPixels} /
     * {@code BufferedImage.getRGB} 同一布局）上统计怪物配色的像素。
     *
     * @param regionW/regionH 中央判定区域的宽/高占比（0..1]，居中放置
     * @param tol             通道容差，见 {@link #DEFAULT_TOLERANCE}
     */
    public static Result analyze(int[] argb, int width, int height,
                                 double regionW, double regionH, int tol) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int rw = Math.max(0, Math.min(w, (int) Math.round(w * regionW)));
        int rh = Math.max(0, Math.min(h, (int) Math.round(h * regionH)));
        int x0 = (w - rw) / 2;
        int y0 = (h - rh) / 2;
        int x1 = x0 + rw;
        int y1 = y0 + rh;

        int bodyCenter = 0;
        int bodyFull = 0;
        int eyeCenter = 0;
        int[] perColor = new int[BODY_COLORS.length];

        for (int y = 0; y < h; y++) {
            int rowBase = y * w;
            boolean rowInRegion = y >= y0 && y < y1;
            for (int x = 0; x < w; x++) {
                int rgb = argb[rowBase + x] & 0xFFFFFF;
                int bodyIndex = matchBody(rgb, tol);
                if (bodyIndex >= 0) {
                    bodyFull++;
                    if (rowInRegion && x >= x0 && x < x1) {
                        bodyCenter++;
                        perColor[bodyIndex]++;
                    }
                }
                if (rowInRegion && x >= x0 && x < x1 && matches(rgb, EYE_COLOR, tol)) {
                    eyeCenter++;
                }
            }
        }
        return new Result(w, h, x0, y0, x1, y1, bodyCenter, bodyFull, eyeCenter, perColor);
    }

    /** 命中任一"体色"则返回其下标，否则 −1（眼睛不在其中）。 */
    private static int matchBody(int rgb, int tol) {
        for (int i = 0; i < BODY_COLORS.length; i++) {
            if (matches(rgb, BODY_COLORS[i], tol)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matches(int rgb, int[] expected, int tol) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return Math.abs(r - expected[0]) <= tol
                && Math.abs(g - expected[1]) <= tol
                && Math.abs(b - expected[2]) <= tol;
    }

    /**
     * 中央区域出现最多的颜色（人可读），用于把"实测到的像素值"打进日志 ——
     * 让"分量 × 255"这条换算口径可以被人工核对，而不是只信断言。
     *
     * @return 形如 {@code rgb(r,g,b)×n; ...} 的前 {@code topN} 名
     */
    public static String topColors(int[] argb, int width, int height,
                                   double regionW, double regionH, int topN) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int rw = Math.max(0, Math.min(w, (int) Math.round(w * regionW)));
        int rh = Math.max(0, Math.min(h, (int) Math.round(h * regionH)));
        int x0 = (w - rw) / 2;
        int y0 = (h - rh) / 2;
        Map<Integer, Integer> hist = new HashMap<>();
        for (int y = y0; y < y0 + rh; y++) {
            int rowBase = y * w;
            for (int x = x0; x < x0 + rw; x++) {
                hist.merge(argb[rowBase + x] & 0xFFFFFF, 1, Integer::sum);
            }
        }
        StringBuilder sb = new StringBuilder();
        hist.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(topN)
                .forEach(e -> sb.append(String.format("rgb(%d,%d,%d)×%d ",
                        (e.getKey() >> 16) & 0xFF, (e.getKey() >> 8) & 0xFF, e.getKey() & 0xFF,
                        e.getValue())));
        return sb.toString().trim();
    }
}

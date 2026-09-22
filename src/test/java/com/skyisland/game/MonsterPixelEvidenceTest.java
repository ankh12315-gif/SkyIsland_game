package com.skyisland.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MonsterPixelEvidence} 的判据自证：<b>仪器在用它之前，先证明它分得清"有怪"与"没怪"。</b>
 *
 * <p>这一组测试不碰 GL、不需要游戏进程，因此在每次 {@code mvn test} 里都会跑。
 * 它把"帧缓冲像素判据"的逻辑缺陷挡在前面：判据若写得太松（容差过大 / 只数整屏），
 * 它会在背景噪声上误报；若写得只认中央，它又会漏掉"怪真的出现了"。
 * 下面每条用一个合成帧把其中一种失效钉死。
 */
class MonsterPixelEvidenceTest {

    private static final int W = 1280;
    private static final int H = 720;

    /** 天空色 rgb(117,161,219)、草地色 rgb(76,139,63) —— 与真实截图里实测到的背景一致。 */
    private static final int SKY = 0xFF75A1DB;
    private static final int GRASS = 0xFF4C8B3F;

    /** MonsterModel 的体色（round(c*255)）。 */
    private static final int HEAD = argb(MonsterPixelEvidence.BODY_COLORS[0]);
    private static final int TORSO = argb(MonsterPixelEvidence.BODY_COLORS[1]);
    private static final int ARM = argb(MonsterPixelEvidence.BODY_COLORS[2]);
    private static final int LEG = argb(MonsterPixelEvidence.BODY_COLORS[3]);

    /** 准星黄：与眼睛色接近、蓝通道偏差 19 > 默认容差 12，用来钉容差口径。 */
    private static final int CROSSHAIR_YELLOW = 0xFFF8D860;

    private static int argb(int[] rgb) {
        return 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
    }

    private static int[] backgroundFrame() {
        int[] p = new int[W * H];
        java.util.Arrays.fill(p, SKY);
        // 下半屏铺草地：给"背景本来就暖/杂"一个最接近现实的底板
        for (int y = H / 2; y < H; y++) {
            java.util.Arrays.fill(p, y * W, y * W + W, GRASS);
        }
        return p;
    }

    private static void fillRect(int[] p, int x0, int y0, int x1, int y1, int color) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                p[y * W + x] = color;
            }
        }
    }

    @Test
    void detectsMonsterBodyPixelsInTheCenter() {
        int[] p = backgroundFrame();
        // 正对镜头的怪：中心附近一块体色矩形（头/躯干/臂/腿各占一段）
        fillRect(p, 580, 300, 700, 360, HEAD);
        fillRect(p, 575, 360, 705, 470, TORSO);
        fillRect(p, 560, 360, 575, 470, ARM);
        fillRect(p, 600, 470, 620, 540, LEG);
        fillRect(p, 620, 470, 640, 540, LEG);

        MonsterPixelEvidence.Result r = MonsterPixelEvidence.analyze(
                p, W, H, 0.40, 0.60, MonsterPixelEvidence.DEFAULT_TOLERANCE);

        assertTrue(r.bodyHitsCenter() > 1000,
                "中央区域必须数出怪物体色像素，实测 " + r.bodyHitsCenter());
        // 全部体色都在中央，因此整屏命中应当几乎等于中央命中（这里严格相等：矩形都落在中央）
        assertEquals(r.bodyHitsFull(), r.bodyHitsCenter(),
                "本帧体色只在中央，整屏命中应等于中央命中");
        for (int per : r.bodyPerColorCenter()) {
            assertTrue(per > 0, "四种体色都应当被数到，分项=" + java.util.Arrays.toString(r.bodyPerColorCenter()));
        }
    }

    @Test
    void reportsZeroWhenOnlyBackgroundIsPresent() {
        MonsterPixelEvidence.Result r = MonsterPixelEvidence.analyze(
                backgroundFrame(), W, H, 0.40, 0.60, MonsterPixelEvidence.DEFAULT_TOLERANCE);

        assertEquals(0, r.bodyHitsCenter(),
                "只有天空与草地时，中央体色命中必须是 0（否则判据会把背景当怪）");
        assertEquals(0, r.bodyHitsFull(), "整屏也不该有体色像素");
        // pre-fix 的现场正是这一条：怪沉到地下，屏幕上零像素 —— 断言据此变红。
    }

    /**
     * <b>中央有界区域必须是真的起作用</b>：把体色放到画面左下角（模拟"画面边缘恰好是暖色地形"），
     * 中央命中必须为 0，而整屏命中 > 0 —— 二者分开报告，"边缘暖色"就无法冒充"中央有怪"。
     */
    @Test
    void bodyColorAtScreenEdgeDoesNotCountAsCenterEvidence() {
        int[] p = backgroundFrame();
        fillRect(p, 0, 0, 60, 60, TORSO);   // 左上角一小块暖色

        MonsterPixelEvidence.Result r = MonsterPixelEvidence.analyze(
                p, W, H, 0.40, 0.60, MonsterPixelEvidence.DEFAULT_TOLERANCE);

        assertEquals(0, r.bodyHitsCenter(), "边缘的暖色不得计入中央命中");
        assertTrue(r.bodyHitsFull() > 0, "整屏命中应当看到那块边缘暖色（分开报告才有意义）");
    }

    /**
     * 容差口径的可证伪性：默认容差必须<b>紧到</b>把准星黄挡在眼睛色之外。
     * 若有人把容差放宽到 ≥ 19，这条会变红 —— 提醒"放宽容差"会顺带给准星开后门。
     */
    @Test
    void defaultToleranceIsTightEnoughToRejectCrosshairYellow() {
        int[] p = backgroundFrame();
        fillRect(p, 600, 320, 680, 400, CROSSHAIR_YELLOW);

        MonsterPixelEvidence.Result r = MonsterPixelEvidence.analyze(
                p, W, H, 0.40, 0.60, MonsterPixelEvidence.DEFAULT_TOLERANCE);

        assertEquals(0, r.eyeHitsCenter(),
                "默认容差 " + MonsterPixelEvidence.DEFAULT_TOLERANCE
                        + " 不应把准星黄当成眼睛色（蓝通道偏差 19 > 容差）");
        assertEquals(0, r.bodyHitsCenter(), "准星黄也不是体色");
    }

    @Test
    void topColorsSummarisesTheCenterForHumanInspection() {
        String summary = MonsterPixelEvidence.topColors(backgroundFrame(), W, H, 0.40, 0.60, 4);
        assertTrue(summary.contains("rgb(117,161,219)"), "应当报告出中央区域的天空色，实测：" + summary);
    }
}

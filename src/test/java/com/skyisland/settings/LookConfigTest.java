package com.skyisland.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灵敏度与反转 Y 的换算（M1.5 规格第 7 条）。
 *
 * <p><b>这个类存在的唯一理由，是钉住一条"兼容性等同关系"：</b>
 * <blockquote>
 *   灵敏度倍数 = 1.0 时，实际换算率必须<u>逐位等于</u> M1 的基准值，
 *   也就是"装上 M1.5 之后，默认手感与 M1 完全一致"。
 * </blockquote>
 * 这条关系必须由测试来守，因为 M1 的整条 LOOK 断言链
 * （像素位移 → 相机角度 → 视线方向）都建立在那个基准值上。
 * 如果实现者图省事"把设置值直接当成度/像素"，那么默认值 1.0 会变成
 * <b>1.0 度/像素</b>（比原来快 8 倍多），而 M1 的断言会以一堆
 * "角度对不上"的形式失败 —— 看起来像相机坏了，实际是设置层的量纲错误。
 *
 * <p>第二条契约：<b>反转必须在纵向、且只影响纵向</b>。横向也反转是另一个话题
 * （左手鼠标），PRD 未要求；而"把反转放在相机层"会让回放与自测脚本产出的
 * 位移数据表达的就不再是"真实鼠标怎么动"。
 */
class LookConfigTest {

    private static final double BASE = 0.12;
    private static final double EPS = 1e-12;

    // ============================================================ 与 M1 的等同关系

    @Test
    void multiplierOfOneReproducesTheM1BaselineExactly() {
        assertEquals(BASE, LookConfig.effectiveDegPerPixel(BASE, 1.0), 0.0,
                "1.0 倍必须逐位等于 M1 基准 —— 这是'M1.5 不改变默认手感'的硬证据");
    }

    @Test
    void defaultSettingsValueIsExactlyOne() {
        GameSettings s = new GameSettings();

        assertEquals(1.0, s.mouseSensitivity(), 0.0);
        assertEquals(BASE, LookConfig.effectiveDegPerPixel(BASE, s.mouseSensitivity()), 0.0);
    }

    // ============================================================ 线性缩放

    @Test
    void higherMultiplierTurnsFasterProportionally() {
        assertEquals(BASE * 1.5, LookConfig.effectiveDegPerPixel(BASE, 1.5), EPS);
        assertEquals(BASE * 0.5, LookConfig.effectiveDegPerPixel(BASE, 0.5), EPS);
        assertEquals(BASE * 2.0, LookConfig.effectiveDegPerPixel(BASE, 2.0), EPS);
        assertEquals(BASE * 0.1, LookConfig.effectiveDegPerPixel(BASE, 0.1), EPS);
    }

    @Test
    void outOfRangeMultipliersAreClampedAsADefenceInDepth() {
        // 自测脚本与命令行可以绕过 setter 直接调这里，因此本函数必须自己兜住。
        assertEquals(BASE * GameSettings.MAX_SENSITIVITY,
                LookConfig.effectiveDegPerPixel(BASE, 100.0), EPS);
        assertEquals(BASE * GameSettings.MIN_SENSITIVITY,
                LookConfig.effectiveDegPerPixel(BASE, -3.0), EPS);
    }

    @Test
    void nonFiniteMultiplierFallsBackToTheDefault() {
        assertEquals(BASE, LookConfig.effectiveDegPerPixel(BASE, Double.NaN), 0.0,
                "NaN 会让相机角度变成 NaN，整个视角永久失效且不报错");
        assertEquals(BASE, LookConfig.effectiveDegPerPixel(BASE, Double.POSITIVE_INFINITY), 0.0);
    }

    @Test
    void zeroMultiplierCannotFreezeTheCamera() {
        assertTrue(LookConfig.effectiveDegPerPixel(BASE, 0.0) > 0,
                "换算率为 0 会让视角完全失灵（转不动），必须被夹到正的下限");
    }

    // ============================================================ 反转 Y

    @Test
    void invertYFlipsOnlyTheVerticalComponent() {
        assertEquals(7.0, LookConfig.applyInvertY(7.0, false), EPS);
        assertEquals(-7.0, LookConfig.applyInvertY(7.0, true), EPS);
        assertEquals(0.0, LookConfig.applyInvertY(0.0, true), EPS);
    }

    @Test
    void toLookDeltaKeepsHorizontalUntouched() {
        double[] normal = LookConfig.toLookDelta(12.5, 4.0, false);
        double[] inverted = LookConfig.toLookDelta(12.5, 4.0, true);

        assertEquals(12.5, normal[0], EPS);
        assertEquals(4.0, normal[1], EPS);
        assertEquals(12.5, inverted[0], EPS, "反转 Y 不得改变横向 —— 那是两件不同的事");
        assertEquals(-4.0, inverted[1], EPS);
    }

    @Test
    void toLookDeltaHandlesPureVerticalAndPureHorizontalMoves() {
        assertArrayEquals(new double[]{0, -9}, LookConfig.toLookDelta(0, 9, true), EPS);
        assertArrayEquals(new double[]{-3, 0}, LookConfig.toLookDelta(-3, 0, true), EPS);
    }

    // ============================================================ 可读说明

    @Test
    void describeShowsTheArithmeticSoTheLogCanBeCheckedByHand() {
        String text = LookConfig.describe(BASE, 1.0);

        assertTrue(text.contains("1.00"), "应包含倍数，实际=" + text);
        assertTrue(text.contains("0.120"), "应包含基准值，实际=" + text);
        assertFalse(text.contains("NaN"));
    }
}

package com.skyisland.render.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UiTheme} 的护栏 —— <b>"配色调过之后还能看清吗"的可执行形式</b>。
 *
 * <h2>为什么"编译通过 + 测试全绿"说明不了这件事</h2>
 * M2.1 刚为这个教训付过钱：怪物在帧缓冲里客观存在、所有断言全绿，
 * 而玩家看不见它 —— 因为没人把"看得见"写成数字。UI 更容易重犯：
 * 槽位底色、边框、悬停高亮、选中高亮、物品图标全都压在同一个深色底上，
 * 有一次"顺手把边框调暗一点"就会让悬停与选中看起来一样，
 * 而 compiles / 帧率 / 单测都不会有任何反应。
 *
 * <h2>判据为什么不复用实体的阈值</h2>
 * 实体走 {@code voxel.frag}（写入时亮度恒为 1，颜色原样落到帧缓冲），
 * UI 走 {@code ui.frag}。两者路径不同，阈值必须各自推导 ——
 * 借来的数字只在借来的那条路上成立。因此这里只定义本文件自己的下限。
 *
 * <h2>算法</h2>
 * 用 Rec.709 相对亮度。带 alpha 的色先<b>合成</b>到它实际压着的那一层底上再算 ——
 * 例如 {@code SLOT_BG} 是 0.66 不透明，直接拿它的 rgb 算亮度会高估对比，
 * 正是这类"看起来算了其实没算"的检查最容易给出假绿灯。
 */
class UiThemeTest {

    /** 界面的最底层（{@code DIM} 的 rgb，视为全不透明）。 */
    private static final float[] BASE = {0.02f, 0.03f, 0.05f};

    /** 亮度差下限：低于它的两色在深色底上基本分不出。 */
    private static final double MIN_LUMA_GAP = 0.12;

    /** 彩度差下限：用于"亮度接近但色相不同"的场合（如白准星 vs 黄准星）。 */
    private static final double MIN_CHROMA_GAP = 0.15;

    // ============================================================ 工具

    /** 把带 alpha 的色合成到给定底色上，返回 rgb。 */
    private static float[] flatten(float[] c, float[] backdrop) {
        float a = c[3];
        return new float[]{
                c[0] * a + backdrop[0] * (1f - a),
                c[1] * a + backdrop[1] * (1f - a),
                c[2] * a + backdrop[2] * (1f - a),
        };
    }

    /** Rec.709 相对亮度。 */
    private static double luma(float[] rgb) {
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
    }

    /** 彩度 = 最大分量 − 最小分量。灰度色为 0。 */
    private static double chroma(float[] rgb) {
        double max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
        double min = Math.min(rgb[0], Math.min(rgb[1], rgb[2]));
        return max - min;
    }

    private static float[] over(float[] c) {
        return flatten(c, BASE);
    }

    /** 在"底色 b"之上画"前景 f"，返回两者的亮度差。 */
    private static double lumaGapOver(float[] f, float[] b) {
        return Math.abs(luma(flatten(f, flatten(b, BASE))) - luma(flatten(b, BASE)));
    }

    private record Pair(String name, float[] foreground, float[] backdrop, double minLumaGap) {
    }

    // ============================================================ 结构不变量

    /** 所有色值必须是合法的 0..1 rgba。一条"把 255 进制误写进来"的记录一旦出现就会红。 */
    @Test
    void everyColourIsANormalizedRgbaQuad() throws Exception {
        List<String> names = new ArrayList<>();
        for (java.lang.reflect.Field f : UiTheme.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    || f.getType() != float[].class) {
                continue;
            }
            float[] c = (float[]) f.get(null);
            names.add(f.getName());
            assertEquals(4, c.length, f.getName() + " 必须是 {r,g,b,a} 四分量");
            for (int i = 0; i < 4; i++) {
                assertTrue(c[i] >= 0f && c[i] <= 1f,
                        f.getName() + " 的分量越界（" + i + " = " + c[i] + "）—— 是不是把 255 进制写了进来？");
            }
        }
        assertTrue(names.size() >= 35,
                "反射应当扫到 UiTheme 的全部色值常量（实际 " + names.size() + "）");
    }

    /**
     * 槽位的四种状态必须构成<b>单调亮度阶梯</b>：
     * 底 &lt; 普通边框 &lt; 悬停边框 &lt; 选中边框。
     *
     * <p>这条断言挡的是"调色时把两个相邻态调到同一个亮度"——
     * 那会让"鼠标停在哪一格"和"哪一格被选中"变得无法区分，
     * 而这两件事正是背包界面存在的意义。
     */
    @Test
    void slotStatesFormAMonotoneBrightnessRamp() {
        double bg = luma(over(UiTheme.SLOT_BG));
        double normal = luma(flatten(UiTheme.SLOT_BORDER, flatten(UiTheme.SLOT_BG, BASE)));
        double hover = luma(flatten(UiTheme.SLOT_HOVER, flatten(UiTheme.SLOT_BG, BASE)));
        double selected = luma(flatten(UiTheme.SLOT_SELECTED, flatten(UiTheme.SLOT_BG, BASE)));

        assertTrue(bg < normal, "槽位底色必须比普通边框暗（" + bg + " vs " + normal + "）");
        assertTrue(normal < hover, "悬停边框必须比普通边框亮（" + normal + " vs " + hover + "）");
        assertTrue(hover < selected, "选中边框必须比悬停边框亮（" + hover + " vs " + selected + "）");
    }

    // ============================================================ 对比度

    @Test
    void textAndChromeStayReadableAgainstTheirOwnBackdrops() {
        List<Pair> pairs = List.of(
                new Pair("主文本 / 面板底", UiTheme.TEXT_PRIMARY, UiTheme.PANEL_BG, 0.60),
                new Pair("暗文本 / 面板底", UiTheme.TEXT_DIM, UiTheme.PANEL_BG, 0.45),
                new Pair("警示文本 / 面板底", UiTheme.TEXT_WARN, UiTheme.PANEL_BG, 0.45),
                new Pair("菜单条目 / 面板底", UiTheme.ITEM, UiTheme.PANEL_BG, 0.60),
                new Pair("数值 / 面板底", UiTheme.VALUE, UiTheme.PANEL_BG, 0.55),
                new Pair("节标题 / 面板底", UiTheme.HEADER, UiTheme.PANEL_BG, 0.45),
                new Pair("说明文字 / 面板底", UiTheme.INFO, UiTheme.PANEL_BG, 0.30),
                new Pair("禁用文字 / 面板底", UiTheme.DISABLED_TEXT, UiTheme.PANEL_BG, 0.18),
                new Pair("菜单标题 / 压暗层", UiTheme.TITLE, UiTheme.DIM, 0.70),
                new Pair("菜单副标题 / 压暗层", UiTheme.SUBTITLE, UiTheme.DIM, 0.35),
                new Pair("版本行 / 压暗层", UiTheme.VERSION_COLOR, UiTheme.DIM, 0.20),
                new Pair("提示框文字 / 提示框底", UiTheme.TOOLTIP_TEXT, UiTheme.TOOLTIP_BG, 0.60),
                new Pair("提示框边框 / 提示框底", UiTheme.TOOLTIP_BORDER, UiTheme.TOOLTIP_BG, 0.25),
                new Pair("对话框边框 / 对话框底", UiTheme.DIALOG_EDGE, UiTheme.DIALOG_BG, 0.40),
                new Pair("普通边框 / 槽位底", UiTheme.SLOT_BORDER, UiTheme.SLOT_BG, 0.18),
                new Pair("悬停边框 / 槽位底", UiTheme.SLOT_HOVER, UiTheme.SLOT_BG, 0.30),
                new Pair("选中边框 / 槽位底", UiTheme.SLOT_SELECTED, UiTheme.SLOT_BG, 0.45),
                new Pair("面板标题 / 面板底", UiTheme.PANEL_HEADER, UiTheme.PANEL_BG, 0.30),
                new Pair("满心 / 空心", UiTheme.HEART_FULL, UiTheme.HEART_EMPTY, 0.15));

        for (Pair p : pairs) {
            double gap = lumaGapOver(p.foreground(), p.backdrop());
            assertTrue(gap >= p.minLumaGap(),
                    p.name() + " 的亮度差只有 " + String.format(java.util.Locale.ROOT, "%.3f", gap)
                            + "，低于下限 " + p.minLumaGap()
                            + " —— 在深色底上这两者基本分不出来（M2.1 那个肉眼看不见的怪物就是这么发生的）");
        }
    }

    /**
     * 三种准星状态必须两两可分。
     *
     * <p>判据是"亮度差够大 <b>或</b> 彩度差够大"：白准星与黄准星亮度接近，
     * 但一个是灰度色、一个彩度很高，人眼靠色相就能分开。
     * 只盯亮度会误判，只盯彩度会漏掉"深红 vs 深蓝"这种真的看不清的组合。
     */
    @Test
    void theThreeCrosshairStatesAreMutuallyDistinguishable() {
        assertDistinguishable("普通准星 vs 瞄准准星", UiTheme.CROSSHAIR, UiTheme.CROSSHAIR_TARGET);
        assertDistinguishable("普通准星 vs 命中准星", UiTheme.CROSSHAIR, UiTheme.CROSSHAIR_HIT);
        assertDistinguishable("瞄准准星 vs 命中准星", UiTheme.CROSSHAIR_TARGET, UiTheme.CROSSHAIR_HIT);
    }

    private static void assertDistinguishable(String name, float[] a, float[] b) {
        float[] fa = over(a);
        float[] fb = over(b);
        double dLuma = Math.abs(luma(fa) - luma(fb));
        double dChroma = Math.abs(chroma(fa) - chroma(fb));
        assertTrue(dLuma >= MIN_LUMA_GAP || dChroma >= MIN_CHROMA_GAP,
                name + " 分不开：亮度差 " + String.format(java.util.Locale.ROOT, "%.3f", dLuma)
                        + "（下限 " + MIN_LUMA_GAP + "）、彩度差 "
                        + String.format(java.util.Locale.ROOT, "%.3f", dChroma)
                        + "（下限 " + MIN_CHROMA_GAP + "）");
    }

    // ============================================================ 唯一来源

    /**
     * HUD 与菜单渲染器里<b>不得</b>再出现自己的色值常量表。
     *
     * <p>M2.2 之前两处各藏了一套 {@code private static final float[]}，数值逐渐漂移。
     * 把它们收拢到 {@link UiTheme} 之后，"再写一份"必须有东西挡着 ——
     * 否则下一个人加一个 hover 色就会顺手在渲染器里再开一张表，
     * 而这次迁移的全部收益会在两三个里程碑内悄悄流失。
     */
    @Test
    void renderersNoLongerDeclareTheirOwnColourTables() throws Exception {
        for (String file : new String[]{"HudRenderer.java", "MenuRenderer.java"}) {
            Path p = Paths.get("src/main/java/com/skyisland/render/ui", file);
            assertTrue(Files.isRegularFile(p), "找不到源文件：" + p);

            int lineNo = 0;
            for (String line : Files.readString(p, StandardCharsets.UTF_8).split("\r?\n")) {
                lineNo++;
                assertTrue(!line.contains("static final float[]"),
                        file + ":" + lineNo + " 又声明了一份色值常量表 —— 配色必须只有 UiTheme 一个来源："
                                + line.trim());
            }
        }
    }
}

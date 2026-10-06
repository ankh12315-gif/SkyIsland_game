package com.skyisland.render.ui;

import com.skyisland.testutil.SourceScan;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ★ <b>{@link MenuRenderer} 必须只读布局，不得自己重算文字几何。</b>
 *
 * <p><b>为什么 {@code MenuLayoutTest} 的 25 条断言还不够：</b>
 * 它们全部只调用 {@link MenuLayout#compute}，一行渲染代码都没碰过。
 * 于是一个具体到不能再具体的回归会<b>全绿通过</b>：
 * <pre>
 *   // MenuRenderer.drawRow 里重新写回旧算法：
 *   int box = BitmapFont.lineHeight(2 * scale);          // 倍数写死
 *   batch.text(layout.labelX(), y + (rowH - box) / 2, entry.label(), 2 * scale, c);
 * </pre>
 * 布局还是那个正确的布局，测试还是全绿，而玩家的屏幕上文字又叠回去了。
 * 这正是本项目反复吃过的那类<b>脆绿灯</b> ——
 * 布局与绘制"各说各话"，而没有任何一条断言站在它们之间。
 *
 * <p>所以这里做的是<b>接线对账</b>：渲染器里凡是"文字画在哪、用几倍字"的表达式，
 * 必须<b>来自</b> {@code layout.rowTextY(index)} / {@code layout.rowTextScale(index)}；
 * 渲染器<b>不许</b>再出现 {@code lineHeight(...)} 的自行换算，
 * 也不许再出现"行高减行盒除以二"这种垂直居中式。
 *
 * <h2>为什么必须剥注释（已在 {@link SourceScan#withoutComments} 里踩过三次）</h2>
 * 本文件自己就写着 {@code lineHeight(2 * scale)} 这个反例。
 * 若不剥注释，断言会被<b>注释里的反例</b>满足 —— 扫描断言最阴险的失败方式：
 * 代码是坏的，测试是绿的，而且恰恰是因为测试里写了正确的话。
 */
class MenuRendererTextGeometryWiringTest {

    /** 相对 {@code src/main/java} 的路径（{@code SourceScan.readMain} 自带那段前缀）。 */
    private static final String RENDERER = "com/skyisland/render/ui/MenuRenderer.java";

    @Test
    void drawRowTakesItsTextGeometryFromTheLayout() {
        String body = SourceScan.withoutComments(
                SourceScan.methodBody(SourceScan.readMain(RENDERER),
                        "private void drawRow("));

        assertTrue(body.contains("layout.rowTextY("),
                "drawRow 必须从布局取文字的 y（layout.rowTextY(index)）。"
                        + "若它自己算 y + (rowH - box) / 2，布局的正确性就与屏幕无关了。");
        assertTrue(body.contains("layout.rowTextScale("),
                "drawRow 必须从布局取文字的倍数（layout.rowTextScale(index)）。");

        assertFalse(body.contains("BitmapFont.lineHeight("),
                "drawRow 里出现了 BitmapFont.lineHeight(...) —— 渲染器不得自行把"
                        + "\"文字占多高\"重算一遍。行盒是 MenuLayout.textBoxHeight 的唯一职责，"
                        + "渲染与布局各算一次就等于各说各话（旧 bug 正是这么来的）。");
        assertFalse(Pattern.compile("\\(\\s*\\w*[rR]owH\\w*\\s*-\\s*\\w*[bB]ox\\w*\\s*\\)\\s*/\\s*2")
                        .matcher(body).find(),
                "drawRow 里出现了\"（行高 - 行盒）/ 2\"这种自行垂直居中的算式 —— "
                        + "文字顶边必须由 MenuLayout.rowTextY 给出，而不是渲染时再居中一次。");
    }

    /**
     * ★ 除覆盖层外，整个渲染器都不许重算文字行盒。
     *
     * <p><b>{@code drawOverlay} 是本条唯一豁免的方法，理由要说清楚，否则日后有人"顺手"也把它禁掉：</b>
     * 覆盖层是居中的对话框 / 等待输入提示，它<b>不是菜单的一行</b>，
     * 因此没有 {@code MenuLayout} 的行矩形、行高与文字盒可读 ——
     * 它的行高必须由自己的文本与缩放算出，这是它的本职，不是脱节。
     * 反过来说，它<b>不参与</b>行流，也就无从与相邻行互相重叠。
     *
     * <p>豁免是<b>按方法</b>做的（把 {@code drawOverlay} 的方法体从待扫描文本里整段剔除），
     * 不是按"这一行不算"这种会随编辑漂移的口头承诺 ——
     * 那种豁免一旦有人往里多写两行就会静默失效。
     */
    @Test
    void noRowDrawingMethodRecomputesTextGeometry() {
        // 覆盖层整段剔除（方法体由 SourceScan 做字面量感知的括号配对）。
        String all = SourceScan.withoutComments(SourceScan.readMain(RENDERER));
        String overlay = SourceScan.methodBody(all, "private void drawOverlay(");
        String rowsOnly = all.replace(overlay, "");

        Matcher m = Pattern.compile("BitmapFont\\.lineHeight\\(").matcher(rowsOnly);
        if (m.find()) {
            fail("MenuRenderer 里（drawOverlay 之外）出现了第 " + lineOf(rowsOnly, m.start())
                    + " 行的 BitmapFont.lineHeight(...)。菜单行的绘制不许重算文字行盒："
                    + "\"文字占多高、用几倍字、画在哪\"三个答案必须全部来自 MenuLayout，"
                    + "否则绘制与命中判定会各自漂移，而两边都能各自通过各自的测试。"
                    + "（drawOverlay 是居中对话框、没有行矩形可读，属正当豁免。）");
        }
    }

    /** 豁免必须真的只豁免了 drawOverlay —— 防止日后有人给它再加一个"例外"。 */
    @Test
    void theOverlayExemptionCoversOnlyTheOverlay() {
        String all = SourceScan.withoutComments(SourceScan.readMain(RENDERER));
        String overlay = SourceScan.methodBody(all, "private void drawOverlay(");

        assertFalse(overlay.isEmpty(), "找不到 drawOverlay 的方法体 —— 豁免前提失效，"
                + "要么方法被改名/删除，要么本守卫已不再覆盖真正的豁免对象");
        assertTrue(overlay.contains("BitmapFont.lineHeight("),
                "drawOverlay 应当仍在自己算行高（它没有 MenuLayout 的行可读）。"
                        + "若它改成读布局，本条的豁免就该收回并同步改断言 —— "
                        + "豁免本身也是契约，不许悄悄扩大。");
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}

package com.skyisland.render.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模态压暗层的<b>层级</b>测试 —— 抓的是"压暗把先画好的 HUD 一起压暗了"。
 *
 * <h2>为什么这条测试必须存在</h2>
 * M2.2 的 {@code UiState#vitalsVisible()} 明确决定："开背包时生命条仍要显示，
 * 因为玩家不能因为开了背包就看不见自己在挨打"。生命条确实画了，单元测试全绿，
 * 门禁全绿 —— 但压暗层是作为"面板渲染的第一步"画的，而面板排在 HUD <b>之后</b>，
 * 于是它盖在生命条上面，把它压暗了 66%。
 *
 * <p>像素证据（1280×720 背包态截图）：满心本应是 {@code rgb(229,51,61)}
 * （= {@code UiTheme.HEART_FULL}），实测 {@code rgb(81,23,29)} —— 恰为 0.34 倍，
 * 而 {@code 1 − DIM.alpha(0.66) = 0.34}。同一张图里的地形也被压暗了同样比例。
 *
 * <p>这是 M2.1"看不见的怪物"的同一族缺陷：<b>画了 ≠ 看得见</b>，
 * 而"看得见"这件事在编译期与断言里都没有代言人。本测试给它一个代言人。
 *
 * <h2>判据为什么是结构而不是像素</h2>
 * 真正的像素判据需要跑起 GL 并读回帧缓冲（那条路径由 M2 的怪物像素门禁负责）。
 * 在无 GL 的单测里能falsifiable地钉住的，是**层序**：
 * "压暗必须排在 HUD 之前" + "一个帧里只能压暗一次" + "面板渲染器不得自己画全屏压暗"。
 * 这三条一旦被违反，压暗就会再次盖到生命条上 —— 而且违反的方式一定落在本文件里。
 */
class ModalDimLayerTest {

    private static final Path GAME_SOURCE =
            Paths.get("src/main/java/com/skyisland/game/SkyIslandGame.java");
    private static final Path INVENTORY_RENDERER =
            Paths.get("src/main/java/com/skyisland/render/ui/InventoryRenderer.java");
    private static final Path MENU_RENDERER =
            Paths.get("src/main/java/com/skyisland/render/ui/MenuRenderer.java");

    private static String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 去掉注释，避免"注释里写了这句话"被判成已接线。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    // ============================================================ 几何

    /**
     * 压暗必须铺满<b>整个</b>帧缓冲 —— 少一个像素就在边上留一条亮边，
     * 那正是"压暗不彻底"的可见形态。
     */
    @Test
    void theDimCoversTheWholeFramebufferAtEverySupportedResolution() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            float[] quad = ModalDimRenderer.coverage(r.width(), r.height());
            assertEquals(4, quad.length, "coverage 必须返回 [x, y, w, h]");
            assertEquals(0f, quad[0], r + "：压暗必须从 x=0 开始");
            assertEquals(0f, quad[1], r + "：压暗必须从 y=0 开始");
            assertEquals(r.width(), quad[2],
                    r + "：压暗的宽度必须等于帧缓冲宽度，否则右边会留一条亮边");
            assertEquals(r.height(), quad[3],
                    r + "：压暗的高度必须等于帧缓冲高度，否则下边会留一条亮边");
        }
    }

    /** 退化尺寸（窗口最小化/初始化中途）不得返回 0 或负数，否则 GL 会画出未定义的东西。 */
    @Test
    void theDimIsStillWellFormedForDegenerateFramebufferSizes() {
        for (int bad : new int[]{0, -1, -720}) {
            float[] quad = ModalDimRenderer.coverage(bad, bad);
            assertTrue(quad[2] >= 1f && quad[3] >= 1f,
                    "帧缓冲尺寸 " + bad + " 时必须被夹到 ≥1，实测 w=" + quad[2] + " h=" + quad[3]);
        }
    }

    // ============================================================ 层序

    /**
     * 压暗 pass 必须在 HUD <b>之前</b>被调用。
     *
     * <p>这是本文件的核心断言。若有人把 {@code renderer.renderModalDim()} 挪到
     * {@code renderer.renderHud(...)} 之后（看起来更"顺手"，因为它属于界面），
     * 生命条与通知就会重新被压暗 66%，而不会有任何别的测试变红。
     */
    @Test
    void theDimIsDrawnBeforeTheHudSoVitalsStayReadable() throws Exception {
        String src = stripComments(read(GAME_SOURCE));
        int dim = src.indexOf("renderer.renderModalDim()");
        int hud = src.indexOf("renderer.renderHud(");

        assertTrue(dim >= 0, "SkyIslandGame 必须显式调用 renderer.renderModalDim() —— "
                + "否则模态层没有压暗，或者压暗又回到了面板内部（那会压暗 HUD）");
        assertTrue(hud >= 0, "找不到 renderer.renderHud(...)，本断言的前提已失效");
        assertTrue(dim < hud,
                "压暗 pass 必须在 renderHud 之前调用（实际 压暗@" + dim + " / HUD@" + hud + "）。"
                        + "排在 HUD 之后会把生命条与通知压暗 66%，"
                        + "而 vitalsVisible 的产品决定是它们在背包打开时仍要可读");
    }

    /**
     * 压暗必须<b>只画一次</b>。面板渲染器里再画一次全屏压暗，就会把 HUD 重新盖住 ——
     * 而且因为它排在 HUD 之后，这一次是致命的。
     */
    @Test
    void noPanelRendererDrawsItsOwnFullscreenDim() throws Exception {
        for (Path p : new Path[]{INVENTORY_RENDERER, MENU_RENDERER}) {
            String src = stripComments(read(p));
            assertFalse(src.contains("UiTheme.DIM"),
                    p.getFileName() + " 不得自己画全屏压暗 —— 面板排在 HUD 之后，"
                            + "在这里画的压暗会盖住生命条与通知。"
                            + "压暗的唯一来源是 Renderer#renderModalDim");
        }
    }

    /**
     * 压暗层自己必须画的是"铺满整个帧缓冲"的那一块，而不是某个面板尺寸。
     *
     * <p>这条同时保证了上面 {@link #theDimCoversTheWholeFramebufferAtEverySupportedResolution}
     * 测的几何真的被绘制路径用到 —— 否则那个纯函数只是一段没人调用的死代码。
     */
    @Test
    void theDimRendererActuallyDrawsTheCoverageItAdvertises() throws Exception {
        String src = stripComments(read(
                Paths.get("src/main/java/com/skyisland/render/ui/ModalDimRenderer.java")));

        assertTrue(src.contains("ModalDimRenderer.coverage(fbWidth, fbHeight)")
                        || src.contains("coverage(fbWidth, fbHeight)"),
                "render(...) 必须用 coverage(fbWidth, fbHeight) 取几何 —— "
                        + "否则测过的几何与画出来的几何是两回事（死接线）");
        assertTrue(src.contains("batch.rect("),
                "压暗层最终必须真的画一块 rect");
    }
}

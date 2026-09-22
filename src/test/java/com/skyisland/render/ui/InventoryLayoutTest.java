package com.skyisland.render.ui;

import com.skyisland.player.Inventory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InventoryLayout} 的护栏 —— <b>"点第 N 格动的就是第 N 格"的可执行形式</b>。
 *
 * <h2>为什么布局与命中必须是同一份结果</h2>
 * 背包要支持拖放，所以"鼠标落在第几格"这个判定每帧都在跑，而且<b>绘制</b>与<b>命中</b>
 * 必须指向同一格。两套坐标一旦漂移，症状是"点了第 3 格，动的是第 12 格"——
 * 在 36 格里这种错位肉眼很难立刻看出来，玩家只会觉得"这个背包是坏的"。
 * {@link InventoryLayout} 让两者读同一份结果，本测试则证明这件事在三种分辨率下都成立。
 *
 * <h2>为什么遍历三种分辨率</h2>
 * 1280×720 是基准（uiScale=1），而 1920×1080 与 2560×1440 会让 uiScale 变成 2，
 * 槽位、间隙、内边距一起翻倍。任何"写死的像素常量"都会在翻倍时露出破绽 ——
 * 典型表现是面板长出屏幕外，或者第 4 行（快捷栏）被挤出画面。
 */
class InventoryLayoutTest {

    // ============================================================ 面板几何

    /**
     * 标题预留的高度必须容得下标题<b>真正被画出来的行盒</b>。
     *
     * <p>这条守的是一个已经在菜单上发生过、又在背包里重演一次的错误：
     * 标题占位被写成常量 {@code PANEL_TITLE_H = 16}（≈ ASCII 的 7 行 × LABEL_SCALE 2），
     * 而标题画的是中文 —— 中文占满 12 行行盒，实际占位是 12 × 2 × scale = 24 × scale。
     * 少留的那 8 × scale 让标题墨迹正好顶到第一行格子的上沿（实测间隙 0–2px）。
     *
     * <p>判据刻意从"渲染器用的那个倍数"出发（{@code UiMetrics.px(LABEL_SCALE, scale)}），
     * 而不是照抄一个数字：哪天有人把标题改成 3 倍，这条会自动跟着要求更大的预留高度，
     * 而写死的期望值不会。
     */
    @Test
    void theReservedTitleBandIsTallEnoughForTheLineBoxItActuallyDraws() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout layout = InventoryLayout.compute(r.width(), r.height());
            int scale = layout.uiScale();
            int drawnLineBox = BitmapFont.lineHeight(UiMetrics.px(UiMetrics.LABEL_SCALE, scale));
            int pad = UiMetrics.px(UiMetrics.PANEL_PAD, scale);

            // 标题墨迹的底 = titleY + 行盒；它到第一个格子上沿之间必须还剩下至少一个内边距。
            // 判据刻意写成"墨迹 + pad"而不是只要"墨迹不越界"：
            // 按后者，旧常量 16px 会算出 16 + pad = 24，恰好等于行盒 24 ——
            // "贴死但不相交"也算通过，而贴死在画面上就是标题挨着格子，非常难看。
            int gap = layout.slotY(0) - (layout.titleY() + drawnLineBox);

            assertTrue(gap >= pad,
                    r + "：标题行盒之外只剩 " + gap + "px，不足一个内边距 " + pad
                            + "px —— 标题会贴到（或压到）第一行格子上");
        }
    }

    @Test
    void thePanelFitsInsideEveryTargetResolution() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout layout = InventoryLayout.compute(r.width(), r.height());
            assertTrue(layout.panelX() >= 0,
                    r + "：面板左边界必须非负（实际 " + layout.panelX() + "）");
            assertTrue(layout.panelY() >= 0,
                    r + "：面板上边界必须非负（实际 " + layout.panelY() + "）");
            assertTrue(layout.panelX() + layout.panelWidth() <= r.width(),
                    r + "：面板右边界越出帧缓冲（" + (layout.panelX() + layout.panelWidth())
                            + " > " + r.width() + "）");
            assertTrue(layout.panelY() + layout.panelHeight() <= r.height(),
                    r + "：面板下边界越出帧缓冲（" + (layout.panelY() + layout.panelHeight())
                            + " > " + r.height() + "）");
        }
    }

    @Test
    void everySlotFitsInsideThePanelAtEveryResolution() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout layout = InventoryLayout.compute(r.width(), r.height());
            for (int i = 0; i < Inventory.SLOT_COUNT; i++) {
                int right = layout.slotX(i) + layout.slotSize();
                int bottom = layout.slotY(i) + layout.slotSize();
                assertTrue(layout.slotX(i) >= layout.panelX() && right <= layout.panelX() + layout.panelWidth(),
                        r + "：第 " + i + " 格横向越出面板（x=" + layout.slotX(i) + " 右=" + right + "）");
                assertTrue(layout.slotY(i) >= layout.panelY() && bottom <= layout.panelY() + layout.panelHeight(),
                        r + "：第 " + i + " 格纵向越出面板（y=" + layout.slotY(i) + " 下=" + bottom + "）");
            }
        }
    }

    // ============================================================ 命中判定

    /**
     * 36 格逐格自证：格子中心的点必须只命中它自己。
     *
     * <p>这个判据比"某一格命中对了"强得多 —— 它同时否掉了"两格重叠"
     * （重叠时中心点会命中先被遍历到的那一格）与"某格完全被别的格盖住"。
     */
    @Test
    void everySlotCentreHitsExactlyThatSlotAtEveryResolution() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout layout = InventoryLayout.compute(r.width(), r.height());
            for (int i = 0; i < Inventory.SLOT_COUNT; i++) {
                double cx = layout.slotX(i) + layout.slotSize() / 2.0;
                double cy = layout.slotY(i) + layout.slotSize() / 2.0;

                assertEquals(i, layout.hitTestAny(cx, cy),
                        r + "：格子中心的点命中了别的格（期望 " + i + "）");
                assertTrue(layout.hitTest(i, cx, cy), r + "：第 " + i + " 格应包含自己的中心点");
            }
        }
    }

    @Test
    void noTwoSlotsOverlapAtEveryResolution() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout layout = InventoryLayout.compute(r.width(), r.height());
            for (int a = 0; a < Inventory.SLOT_COUNT; a++) {
                for (int b = a + 1; b < Inventory.SLOT_COUNT; b++) {
                    boolean separated = layout.slotX(a) + layout.slotSize() <= layout.slotX(b)
                            || layout.slotX(b) + layout.slotSize() <= layout.slotX(a)
                            || layout.slotY(a) + layout.slotSize() <= layout.slotY(b)
                            || layout.slotY(b) + layout.slotSize() <= layout.slotY(a);
                    assertTrue(separated, r + "：第 " + a + " 格与第 " + b + " 格重叠");
                }
            }
        }
    }

    /**
     * 格子之间的间隙<b>不是</b>格子。
     *
     * <p>判据写死这一条，是为了防止有人把命中的边界条件写成"小于等于"从而让
     * 间隙也吃掉点击 —— 那种实现下，两个槽位之间 2 像素宽的一带会归给左/右邻居，
     * 拖放时表现为"差一像素就放错格"。间隙两侧的 <b>边缘像素</b>仍然属于槽位，
     * 这一点也一并钉住。
     */
    @Test
    void theGapBetweenSlotsIsNotASlotButTheEdgesAre() {
        InventoryLayout layout = InventoryLayout.compute(1280, 720);
        int slot = layout.slotSize();
        int gap = UiMetrics.px(UiMetrics.SLOT_GAP, layout.uiScale());
        double cy = layout.slotY(0) + slot / 2.0;

        int leftRight = layout.slotX(0) + slot;
        assertEquals(slot + gap, layout.slotX(1) - layout.slotX(0),
                "第 0 格与第 1 格之间应当是 slot+gap 的步长（实际步长 "
                        + (layout.slotX(1) - layout.slotX(0)) + "）");

        // 左格的最后一个像素仍属于左格
        assertEquals(0, layout.hitTestAny(leftRight - 0.5, cy), "槽位的最后一个像素必须仍属于它自己");
        // 间隙里的像素不属于任何格
        assertEquals(-1, layout.hitTestAny(leftRight + gap / 2.0, cy), "槽位之间的间隙不得吃掉点击");
        // 右格的第一个像素属于右格
        assertEquals(1, layout.hitTestAny(leftRight + gap + 0.5, cy), "右格的首个像素必须属于右格");
    }

    @Test
    void theSeparatorBandBetweenMainGridAndHotbarIsNotASlot() {
        InventoryLayout layout = InventoryLayout.compute(1280, 720);
        int lastMainBottom = layout.slotY(Inventory.MAIN_SIZE - 1) + layout.slotSize();
        double cx = layout.slotX(0) + layout.slotSize() / 2.0;

        assertTrue(layout.slotY(Inventory.HOTBAR_OFFSET) > lastMainBottom,
                "快捷栏必须排在主背包下方，中间留出分隔带");
        for (double y = lastMainBottom; y < layout.slotY(Inventory.HOTBAR_OFFSET); y += 0.5) {
            assertEquals(-1, layout.hitTestAny(cx, y),
                    "主背包与快捷栏之间的分隔带不得被当成格子（y=" + y + "）");
        }
    }

    @Test
    void clicksOutsideTheGridHitNothing() {
        InventoryLayout layout = InventoryLayout.compute(1280, 720);

        assertEquals(-1, layout.hitTestAny(-1, -1), "左上角之外");
        assertEquals(-1, layout.hitTestAny(0, 0), "面板标题区不应命中任何格");
        assertEquals(-1, layout.hitTestAny(1279, 719), "右下角之外");
        assertEquals(-1, layout.hitTestAny(
                layout.panelX() - 1, layout.slotY(0) + 1), "面板左外一像素");
    }

    // ============================================================ 槽位编号契约

    /**
     * 主背包 3 行 + 快捷栏 1 行，且快捷栏的列与主背包<b>逐像素对齐</b>。
     *
     * <p>列对齐不是审美问题：玩家的肌肉记忆来自快捷栏（一直是 9 格一行），
     * 背包开放后如果第 4 行的列位置偏了一点，"按列数格子"就会数错。
     */
    @Test
    void theHotbarIsTheBottomRowAndItsColumnsAlignWithTheMainGrid() {
        for (UiMetrics.Resolution r : UiMetrics.Resolution.values()) {
            InventoryLayout layout = InventoryLayout.compute(r.width(), r.height());

            int hotbarY = layout.slotY(Inventory.HOTBAR_OFFSET);
            for (int i = Inventory.HOTBAR_OFFSET; i < Inventory.SLOT_COUNT; i++) {
                assertEquals(hotbarY, layout.slotY(i), r + "：快捷栏必须全部落在同一行（第 " + i + " 格）");
                assertEquals(layout.slotX(i - Inventory.HOTBAR_OFFSET), layout.slotX(i),
                        r + "：快捷栏第 " + (i - Inventory.HOTBAR_OFFSET) + " 格必须与主背包同列对齐");
            }
            for (int i = 0; i < Inventory.MAIN_SIZE - 1; i++) {
                assertTrue(layout.slotY(i) <= layout.slotY(i + 1),
                        r + "：主背包行号必须自上而下单调");
            }
            assertTrue(hotbarY > layout.slotY(Inventory.MAIN_SIZE - 1),
                    r + "：快捷栏必须在主背包下方");
        }
    }

    @Test
    void theMainGridIsThreeRowsOfNineAndTheHotbarIsTheFourthRow() {
        InventoryLayout layout = InventoryLayout.compute(1280, 720);

        assertEquals(9, InventoryLayout.COLUMNS);
        assertEquals(27, Inventory.MAIN_SIZE);
        assertEquals(9, Inventory.HOTBAR_SIZE);
        assertEquals(36, Inventory.SLOT_COUNT);

        long distinctMainRows = java.util.stream.IntStream.range(0, Inventory.MAIN_SIZE)
                .map(layout::slotY).distinct().count();
        assertEquals(3, distinctMainRows, "主背包必须是 3 行");

        assertNotEquals(layout.slotY(26), layout.slotY(27), "第 27 格属于快捷栏，不与主背包同行");
    }

    @Test
    void uiScaleFollowsTheFramebufferHeightWithAFloorOfOne() {
        assertEquals(1, UiMetrics.uiScale(720));
        assertEquals(1, UiMetrics.uiScale(1), "极小帧缓冲也必须至少缩放 1（不得为 0）");
        assertEquals(2, UiMetrics.uiScale(1080));
        assertEquals(2, UiMetrics.uiScale(1440));

        // 三种目标分辨率下槽位都必须是正数且随缩放增长
        int at720 = InventoryLayout.compute(1280, 720).slotSize();
        int at1440 = InventoryLayout.compute(2560, 1440).slotSize();
        assertTrue(at720 > 0 && at1440 > at720,
                "高分辨率下槽位必须变大，否则 4K 全屏会缩成看不见的细线");
    }

    // ============================================================ 窗口坐标 → 帧缓冲像素

    /**
     * 窗口坐标 → 帧缓冲像素的换算，必须在<b>两者不相等</b>时也正确。
     *
     * <h2>为什么这条测试只能在"不一致"的参数下写</h2>
     * 输入层给的鼠标位置是<b>窗口坐标</b>（GLFW 回调原始值），而全部槽位几何都写在
     * <b>帧缓冲像素</b>里。DPI = 1 的开发机上这两个数恒等 —— 于是这条换算
     * <b>写反、写错、甚至整段删掉都不会被任何测试或试玩发现</b>。
     * 它的失效方式是：在缩放屏幕上"点第 3 格，动的是第 12 格"，
     * 而开发者本机永远复现不了。所以判据必须选在窗口 ≠ 帧缓冲的区间。
     */
    @Test
    void windowToFramebufferScalesByTheRatioOfTheTwoSizes() {
        // 窗口 1280×720 / 帧缓冲 1920×1080（1.5 倍缩放）
        double[] p = InventoryLayout.windowToFramebuffer(640, 360, 1920, 1080, 1280, 720);
        assertEquals(960.0, p[0], 1e-9, "窗口横坐标 640 在 1.5 倍缩放下对应帧缓冲 960");
        assertEquals(540.0, p[1], 1e-9, "窗口纵坐标 360 在 1.5 倍缩放下对应帧缓冲 540");

        // 反向的一半：窗口 1920×1080 / 帧缓冲 1280×720（0.667 倍，逻辑分辨率大于窗口）
        double[] q = InventoryLayout.windowToFramebuffer(960, 540, 1280, 720, 1920, 1080);
        assertEquals(640.0, q[0], 1e-9);
        assertEquals(360.0, q[1], 1e-9);

        // 长度维度不同（横竖缩放比不一致）时，两轴必须各自换算，不能共用一个比例
        double[] r = InventoryLayout.windowToFramebuffer(100, 100, 2000, 1000, 1000, 500);
        assertEquals(200.0, r[0], 1e-9);
        assertEquals(200.0, r[1], 1e-9);
    }

    /**
     * 换算之后必须命中"瞄准的那一格"——这是整条链路的可执行形式。
     *
     * <p>用 {@link InventoryLayout#slotCenter} 取格心（帧缓冲口径），换算回窗口坐标，
     * 再让产品那条正向换算把它变回来、交给 {@code hitTestAny}。
     * 换算只有"乘错方向"这一种错法，因此这一步只要方向反了就必然落在别的格子上。
     */
    @Test
    void aimingAtASlotCentreThroughWindowCoordinatesStillHitsThatSlot() {
        int fbW = 2560;
        int fbH = 1440;
        int winW = 1280;   // 窗口是帧缓冲的一半：最能暴露"乘/除"方向写反
        int winH = 720;
        InventoryLayout layout = InventoryLayout.compute(fbW, fbH);

        for (int slot = 0; slot < Inventory.SLOT_COUNT; slot++) {
            double[] center = layout.slotCenter(slot);
            // 正向换算的逆运算（刻意写成另一个表达式，不共用产品的实现）
            double winX = center[0] * winW / (double) fbW;
            double winY = center[1] * winH / (double) fbH;
            double[] back = InventoryLayout.windowToFramebuffer(winX, winY, fbW, fbH, winW, winH);
            assertEquals(slot, layout.hitTestAny(back[0], back[1]),
                    "第 " + slot + " 格的中心经窗口坐标往返后应当仍然命中第 " + slot + " 格");
        }
    }
}

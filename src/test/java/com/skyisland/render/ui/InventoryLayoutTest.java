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
}

package com.skyisland.player;

import com.skyisland.testutil.TestWorlds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 9 格快捷栏测试。
 *
 * <p>覆盖两条直接决定"玩起来对不对"的行为：
 * <ol>
 *   <li><b>先并入同种未满的槽，再占用空槽。</b>反过来做会出现"同一种方块散在三格里"，
 *       玩家会以为背包出了问题；</li>
 *   <li><b>切槽到两端时循环。</b>"滚到头没反应"在游戏里很难与"滚轮坏了"区分。</li>
 * </ol>
 */
class InventoryTest {

    private static final int GRASS = TestWorlds.grass();
    private static final int DIRT = TestWorlds.dirt();
    private static final int STONE = TestWorlds.stone();

    @Test
    void newInventoryIsNineEmptySlotsWithFirstSelected() {
        Inventory inventory = new Inventory();

        assertEquals(9, inventory.size());
        assertEquals(Inventory.HOTBAR_SIZE, inventory.size());
        assertEquals(0, inventory.selectedSlot());
        assertEquals(0, inventory.totalItemCount());
        assertEquals(0, inventory.usedSlotCount());
        for (int i = 0; i < inventory.size(); i++) {
            assertTrue(inventory.slot(i).isEmpty(), "槽 " + i + " 应为空");
        }
        assertTrue(inventory.selectedStack().isEmpty());
    }

    @Test
    void addFillsFirstEmptySlotThenStacks() {
        Inventory inventory = new Inventory();

        assertEquals(0, inventory.add(GRASS, 10), "全部放入时返回 0 余量");
        assertEquals(10, inventory.slot(0).count());
        assertEquals(1, inventory.usedSlotCount());

        assertEquals(0, inventory.add(GRASS, 5));
        assertEquals(15, inventory.slot(0).count(), "同种方块必须并入已有槽而不是占用新槽");
        assertEquals(1, inventory.usedSlotCount());
    }

    @Test
    void addSpillsIntoAdditionalSlotsWhenStackIsFull() {
        Inventory inventory = new Inventory();

        assertEquals(0, inventory.add(GRASS, ItemStack.MAX_STACK + 20));

        assertEquals(ItemStack.MAX_STACK, inventory.slot(0).count());
        assertEquals(20, inventory.slot(1).count());
        assertEquals(2, inventory.usedSlotCount());
        assertEquals(ItemStack.MAX_STACK + 20, inventory.totalItemCount());
    }

    @Test
    void addReturnsLeftoverWhenInventoryIsFull() {
        Inventory inventory = new Inventory();
        int capacity = Inventory.HOTBAR_SIZE * ItemStack.MAX_STACK;

        assertEquals(0, inventory.add(STONE, capacity));
        assertEquals(capacity, inventory.totalItemCount());

        int leftover = inventory.add(DIRT, 7);
        assertEquals(7, leftover, "满了以后必须把放不下的数量交回调用方（用于告警/掉落）");
        assertEquals(capacity, inventory.totalItemCount());
    }

    @Test
    void addIgnoresNonPositiveAmountsAndAirBlock() {
        Inventory inventory = new Inventory();
        // add() 的返回值口径是"未能放入的余量"，不是"已放入的数量"。
        // 因此"全部被拒"应当返回整笔数量（调用方据此告警/掉落），而不是 0。
        assertEquals(0, inventory.add(GRASS, 0));
        assertEquals(0, inventory.add(GRASS, -5), "负数数量按「无法放入」处理，余量下限为 0");
        assertEquals(10, inventory.add(0, 10), "空气必须被整笔退回：余量 = 10");
        assertEquals(0, inventory.totalItemCount(), "空气不得占用任何槽位");
        assertEquals(0, inventory.usedSlotCount());
    }

    @Test
    void differentBlocksGetDifferentSlots() {
        Inventory inventory = new Inventory();
        inventory.add(GRASS, 1);
        inventory.add(DIRT, 1);

        assertEquals(GRASS, inventory.slot(0).blockRuntimeId());
        assertEquals(DIRT, inventory.slot(1).blockRuntimeId());
        assertEquals(2, inventory.usedSlotCount());
    }

    @Test
    void cycleSlotWrapsAroundBothEnds() {
        Inventory inventory = new Inventory();

        inventory.cycleSlot(1);
        assertEquals(1, inventory.selectedSlot());

        inventory.selectSlot(0);
        inventory.cycleSlot(-1);
        assertEquals(Inventory.HOTBAR_SIZE - 1, inventory.selectedSlot(),
                "向前滚到头必须绕到最后一格，而不是停住");

        inventory.selectSlot(Inventory.HOTBAR_SIZE - 1);
        inventory.cycleSlot(1);
        assertEquals(0, inventory.selectedSlot(), "向后滚到底必须绕回第一格");

        inventory.cycleSlot(0);
        assertEquals(0, inventory.selectedSlot(), "0 步不改变任何东西");
    }

    @Test
    void selectSlotIgnoresOutOfRangeIndices() {
        Inventory inventory = new Inventory();
        inventory.selectSlot(4);
        assertEquals(4, inventory.selectedSlot());

        inventory.selectSlot(-1);
        assertEquals(4, inventory.selectedSlot(), "越界选择必须被忽略，而不是抛异常");
        inventory.selectSlot(Inventory.HOTBAR_SIZE);
        assertEquals(4, inventory.selectedSlot());
    }

    @Test
    void consumeSelectedOnlyTouchesTheSelectedSlot() {
        Inventory inventory = new Inventory();
        inventory.add(GRASS, 10);
        inventory.add(DIRT, 10);

        inventory.selectSlot(1);
        assertTrue(inventory.consumeSelected(4));
        assertEquals(6, inventory.slot(1).count());
        assertEquals(10, inventory.slot(0).count(), "不得动到未选中的槽");

        assertTrue(inventory.consumeSelected(6));
        assertTrue(inventory.slot(1).isEmpty(), "消耗到 0 应变为空槽");
    }

    @Test
    void consumeSelectedReportsFailureWhenAmountIsInsufficient() {
        Inventory inventory = new Inventory();
        inventory.add(GRASS, 2);

        assertFalse(inventory.consumeSelected(5), "数量不足必须返回 false");

        inventory.selectSlot(5);
        assertFalse(inventory.consumeSelected(1), "空槽消耗必然失败");
    }

    @Test
    void consumeSelectedWithNonPositiveAmountIsNoOpSuccess() {
        Inventory inventory = new Inventory();
        inventory.add(GRASS, 3);
        assertTrue(inventory.consumeSelected(0));
        assertEquals(3, inventory.slot(0).count());
    }

    @Test
    void countOfAggregatesAcrossSlots() {
        Inventory inventory = new Inventory();
        inventory.add(GRASS, ItemStack.MAX_STACK + 3);
        inventory.add(GRASS, 3);

        assertEquals(ItemStack.MAX_STACK + 6, inventory.countOf(GRASS));
        assertEquals(0, inventory.countOf(STONE));
    }

    @Test
    void snapshotAndRestoreRoundTrip() {
        Inventory source = new Inventory();
        source.add(GRASS, 12);
        source.add(DIRT, 7);
        source.selectSlot(1);

        List<ItemStack> snapshot = source.snapshot();

        Inventory restored = new Inventory();
        restored.restore(snapshot, source.selectedSlot());

        assertEquals(12, restored.slot(0).count());
        assertEquals(7, restored.slot(1).count());
        assertEquals(1, restored.selectedSlot());
        assertEquals(source.totalItemCount(), restored.totalItemCount());
    }

    @Test
    void restoreHandlesShortAndNullSnapshots() {
        Inventory inventory = new Inventory();
        inventory.add(GRASS, 5);

        inventory.restore(List.of(ItemStack.of(DIRT, 3)), 0);
        assertEquals(3, inventory.slot(0).count());
        assertTrue(inventory.slot(1).isEmpty(), "快照未覆盖的槽必须被清空，而不是保留旧值");
        assertTrue(inventory.slot(8).isEmpty());

        inventory.restore(null, 3);
        assertEquals(3, inventory.selectedSlot());
        assertEquals(0, inventory.totalItemCount());
    }

    @Test
    void restoreClearsSlotsBeyondTheSnapshot() {
        Inventory inventory = new Inventory();
        inventory.add(GRASS, 1);
        inventory.add(DIRT, 1);
        inventory.add(STONE, 1);
        assertEquals(3, inventory.usedSlotCount());

        inventory.restore(List.of(ItemStack.of(STONE, 2)), 0);
        assertEquals(1, inventory.usedSlotCount(), "旧内容必须整体被替换掉，而不是与快照合并");
        assertEquals(2, inventory.totalItemCount());
    }

    @Test
    void slotAccessIsSafeOutOfRange() {
        Inventory inventory = new Inventory();
        inventory.add(GRASS, 4);

        assertTrue(inventory.slot(-1).isEmpty());
        assertTrue(inventory.slot(99).isEmpty());

        inventory.setSlot(99, ItemStack.of(DIRT, 1));
        assertEquals(4, inventory.totalItemCount(), "越界写入必须被忽略");
    }

    @Test
    void toStringMarksTheSelectedSlot() {
        Inventory inventory = new Inventory();
        inventory.add(GRASS, 2);
        inventory.selectSlot(3);

        String text = inventory.toString();
        assertTrue(text.startsWith("HOTBAR["));
        assertTrue(text.contains(">"), "必须标出当前选中的槽：" + text);
        assertTrue(text.contains("x2"), text);
    }
}

package com.skyisland.player;

import com.skyisland.item.ItemRegistry;
import com.skyisland.testutil.TestWorlds;
import org.junit.jupiter.api.Test;

import static com.skyisland.player.InventoryInteraction.Result.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InventoryInteraction} 纯逻辑交互语义测试（M2.2）。无 GL、无渲染。
 *
 * <p>覆盖：leftClick 六条核心语义 + 满堆无操作 + 越界拒绝；
 * shiftClick 跨区搬运、同物并入、放不下时原子拒绝（原格不变）；
 * closeScreen 塞回 / 丢弃且不复制。
 */
class InventoryInteractionTest {

    private static final int STONE = TestWorlds.stone();
    private static final int DIRT = TestWorlds.dirt();
    private static final int PISTOL = ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID);

    // ============================================================ leftClick

    @Test
    void leftClickPicksUpWholeStackWhenHandEmpty() {
        Inventory inv = new Inventory();
        inv.setSlot(0, ItemStack.of(STONE, 5));

        var o = InventoryInteraction.leftClick(inv, 0);

        assertEquals(PICKUP_ALL, o.result());
        assertEquals(0, o.slotIndex());
        assertTrue(inv.slot(0).isEmpty(), "原格清空");
        assertEquals(5, inv.cursorStack().count());
        assertEquals(STONE, inv.cursorStack().itemRuntimeId());
    }

    @Test
    void leftClickPlacesWholeStackOnEmptySlot() {
        Inventory inv = new Inventory();
        inv.setCursorStack(ItemStack.of(STONE, 5));

        var o = InventoryInteraction.leftClick(inv, 3);

        assertEquals(PLACE_ALL, o.result());
        assertEquals(3, o.slotIndex());
        assertEquals(5, inv.slot(3).count());
        assertTrue(inv.cursorStack().isEmpty(), "手上变空");
    }

    @Test
    void leftClickMergesPartiallyWhenTargetHasRoom() {
        Inventory inv = new Inventory();
        inv.setSlot(1, ItemStack.of(STONE, 40)); // 上限 64，余 24
        inv.setCursorStack(ItemStack.of(STONE, 30));

        var o = InventoryInteraction.leftClick(inv, 1);

        assertEquals(MERGE_PARTIAL, o.result());
        assertEquals(64, inv.slot(1).count(), "目标补满");
        assertEquals(6, inv.cursorStack().count(), "余下留手上");
    }

    @Test
    void leftClickMergesFullyWhenCursorFits() {
        Inventory inv = new Inventory();
        inv.setSlot(1, ItemStack.of(STONE, 54));
        inv.setCursorStack(ItemStack.of(STONE, 10));

        var o = InventoryInteraction.leftClick(inv, 1);

        assertEquals(MERGE_FULL, o.result());
        assertEquals(64, inv.slot(1).count());
        assertTrue(inv.cursorStack().isEmpty(), "手上清空");
    }

    @Test
    void leftClickSwapsDifferentItems() {
        Inventory inv = new Inventory();
        inv.setSlot(2, ItemStack.of(STONE, 3));
        inv.setCursorStack(ItemStack.of(DIRT, 2));

        var o = InventoryInteraction.leftClick(inv, 2);

        assertEquals(SWAP, o.result());
        assertEquals(DIRT, inv.slot(2).itemRuntimeId());
        assertEquals(2, inv.slot(2).count());
        assertEquals(STONE, inv.cursorStack().itemRuntimeId());
        assertEquals(3, inv.cursorStack().count());
    }

    @Test
    void leftClickDoesNothingWhenHandEmptyAndTargetEmpty() {
        Inventory inv = new Inventory();
        var o = InventoryInteraction.leftClick(inv, 5);
        assertEquals(NONE, o.result());
        assertTrue(inv.cursorStack().isEmpty());
    }

    @Test
    void leftClickDoesNothingWhenTargetStackIsFull() {
        Inventory inv = new Inventory();
        inv.setSlot(4, ItemStack.of(STONE, ItemStack.MAX_STACK)); // 已满
        inv.setCursorStack(ItemStack.of(STONE, 10));
        int beforeCursor = inv.cursorStack().count();

        var o = InventoryInteraction.leftClick(inv, 4);

        assertEquals(NONE, o.result(), "满堆 + 同物：塞不进，无操作");
        assertEquals(ItemStack.MAX_STACK, inv.slot(4).count(), "原格不变");
        assertEquals(beforeCursor, inv.cursorStack().count(), "手上不变");
    }

    @Test
    void leftClickRejectsOutOfRangeSlot() {
        Inventory inv = new Inventory();
        var over = InventoryInteraction.leftClick(inv, 99);
        assertEquals(REJECTED, over.result());
        assertEquals(99, over.slotIndex());

        var under = InventoryInteraction.leftClick(inv, -1);
        assertEquals(REJECTED, under.result());
        assertEquals(-1, under.slotIndex());
    }

    // ============================================================ shiftClick

    /** 主背包 → 快捷栏：整格搬过去，原格清空。 */
    @Test
    void shiftClickMovesMainToHotbar() {
        Inventory inv = new Inventory();
        inv.setSlot(0, ItemStack.of(STONE, 7));

        var o = InventoryInteraction.shiftClick(inv, 0);

        assertEquals(SHIFT_MOVED, o.result());
        assertTrue(inv.slot(0).isEmpty(), "原格清空");
        assertEquals(7, inv.hotbarSlot(0).count(), "落到快捷栏第 1 格（绝对 27）");
    }

    /** 快捷栏 → 主背包：整格搬过去，原格清空。 */
    @Test
    void shiftClickMovesHotbarToMain() {
        Inventory inv = new Inventory();
        inv.setSlot(inv.hotbarIndex(0), ItemStack.of(STONE, 7));

        var o = InventoryInteraction.shiftClick(inv, inv.hotbarIndex(0));

        assertEquals(SHIFT_MOVED, o.result());
        assertTrue(inv.hotbarSlot(0).isEmpty(), "原快捷栏格清空");
        assertEquals(7, inv.slot(0).count(), "落到主背包第 1 格（绝对 0）");
    }

    /** 目标区有同种物品时先并入，而不是占新格（主背包 → 并入快捷栏已有堆）。 */
    @Test
    void shiftClickMergesIntoSameItemInTargetRegion() {
        Inventory inv = new Inventory();
        inv.setSlot(inv.hotbarIndex(0), ItemStack.of(STONE, 40));
        inv.setSlot(0, ItemStack.of(STONE, 10));

        var o = InventoryInteraction.shiftClick(inv, 0);

        assertEquals(SHIFT_MOVED, o.result());
        assertEquals(50, inv.hotbarSlot(0).count(), "并入快捷栏已有堆（40+10）");
        assertTrue(inv.slot(0).isEmpty(), "原主背包格清空");
    }

    /** 目标区放不下：原子拒绝，原格必须完全不变（不允许移动一半）。 */
    @Test
    void shiftClickBlockedLeavesSourceUntouched() {
        Inventory inv = new Inventory();
        // 把快捷栏 9 格全部塞满异种物品（石头搬不过去：无同物可并、无空槽）
        for (int h = 0; h < Inventory.HOTBAR_SIZE; h++) {
            inv.setSlot(inv.hotbarIndex(h), ItemStack.of(DIRT, ItemStack.MAX_STACK));
        }
        inv.setSlot(0, ItemStack.of(STONE, 5));

        var o = InventoryInteraction.shiftClick(inv, 0);

        assertEquals(SHIFT_BLOCKED, o.result());
        assertEquals(5, inv.slot(0).count(), "原格保持不变（原子性）");
        assertEquals(DIRT, inv.hotbarSlot(0).itemRuntimeId(), "快捷栏未被改动");
        assertEquals(ItemStack.MAX_STACK, inv.hotbarSlot(0).count());
    }

    @Test
    void shiftClickDoesNothingOnEmptySlot() {
        Inventory inv = new Inventory();
        var o = InventoryInteraction.shiftClick(inv, 10);
        assertEquals(NONE, o.result());
    }

    // ============================================================ closeScreen

    /** 光标上的东西能全部塞回背包：总数不增、光标清空。 */
    @Test
    void closeScreenReturnsEverythingToInventory() {
        Inventory inv = new Inventory();
        inv.setSlot(0, ItemStack.of(STONE, 60)); // 余 4
        inv.setCursorStack(ItemStack.of(STONE, 10));

        // 注意：totalItemCount() 只数 36 个槽位，**不含光标持有堆**（见其 javadoc）。
        // 因此"不复制"的正确形式是 before + carried == after，
        // 而不是 before == after —— 后者会永远失败，并被误读成"实现有 bug"。
        int carried = inv.cursorStack().count();
        int before = inv.totalItemCount();

        var o = InventoryInteraction.closeScreen(inv);

        assertEquals(PLACE_ALL, o.result());
        assertEquals(-1, o.slotIndex());
        assertTrue(inv.cursorStack().isEmpty());
        assertEquals(before + carried, inv.totalItemCount(),
                "全部塞回：关屏后的槽位总数必须等于 关屏前槽位总数 + 光标持有数（多一个就是凭空复制）");

        // 归位走的是 Inventory.add()，因此遵守 add() 的「先快捷栏、后主背包」契约：
        // 快捷栏还空着，那 10 个石头就落在快捷栏首格，而不是并回主背包那堆 60 个里。
        // 这是契约行为而不是缺陷 —— "不丢失、不复制"由上一行守住，这一行守的是填充顺序。
        // 写死它的理由：哪天有人为了"归位更顺手"把 add 的顺序改成主背包优先，
        // 挖到的方块就会开始落进主背包、快捷栏空着，玩家手感与既有断言一起崩，
        // 而崩的现场在这里（一行 diff 就能看出来），不必等到试玩才发现。
        assertEquals(60, inv.slot(0).count(), "主背包里那堆 60 个一个都不该被动过");
        assertEquals(10, inv.hotbarSlot(0).count(),
                "光标持有物按 add() 契约落进快捷栏首格（MAIN_SIZE=27 → 绝对索引 27）");
        assertEquals(0, inv.slot(1).count(), "不得凭空多出一堆");
    }

    /** 背包已满、部分塞回：只塞得进的部分留下，其余丢弃，总数不得虚增（不复制）。 */
    @Test
    void closeScreenDiscardsLeftoverWithoutDuplicating() {
        Inventory inv = new Inventory();
        inv.add(STONE, Inventory.SLOT_COUNT * ItemStack.MAX_STACK); // 填满
        int full = inv.totalItemCount();
        inv.setCursorStack(ItemStack.of(STONE, 5));

        var o = InventoryInteraction.closeScreen(inv);

        assertEquals(SHIFT_BLOCKED, o.result(), "有物品因满被丢弃");
        assertTrue(inv.cursorStack().isEmpty(), "丢弃后光标清空");
        assertEquals(full, inv.totalItemCount(), "丢弃不得让总数虚增（不凭空复制）");
    }

    /** 空手关闭：无操作、无丢弃。 */
    @Test
    void closeScreenWithEmptyHandIsNoOp() {
        Inventory inv = new Inventory();
        inv.add(STONE, 10);
        int before = inv.totalItemCount();

        var o = InventoryInteraction.closeScreen(inv);

        assertEquals(PLACE_ALL, o.result());
        assertEquals(before, inv.totalItemCount());
        assertTrue(inv.cursorStack().isEmpty());
    }

    /** 枪（maxStack=1）走交互层也不被当 64：拿起 / 放下逐把进行。 */
    @Test
    void interactionHonorsGunMaxStackOfOne() {
        Inventory inv = new Inventory();
        inv.setSlot(0, ItemStack.of(PISTOL, 1));

        var pick = InventoryInteraction.leftClick(inv, 0);
        assertEquals(PICKUP_ALL, pick.result());
        assertEquals(1, inv.cursorStack().count(), "枪每次只拿 1 把，不被 64 截断");

        var place = InventoryInteraction.leftClick(inv, 5);
        assertEquals(PLACE_ALL, place.result());
        assertEquals(1, inv.slot(5).count());
    }
}

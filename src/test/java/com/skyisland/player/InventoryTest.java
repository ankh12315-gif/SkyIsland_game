package com.skyisland.player;

import com.skyisland.item.ItemRegistry;
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

        assertEquals(36, inventory.size());
        assertEquals(Inventory.SLOT_COUNT, inventory.size());
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
        assertEquals(10, inventory.hotbarSlot(0).count());
        assertEquals(1, inventory.usedSlotCount());

        assertEquals(0, inventory.add(GRASS, 5));
        assertEquals(15, inventory.hotbarSlot(0).count(), "同种方块必须并入已有槽而不是占用新槽");
        assertEquals(1, inventory.usedSlotCount());
    }

    @Test
    void addSpillsIntoAdditionalSlotsWhenStackIsFull() {
        Inventory inventory = new Inventory();

        assertEquals(0, inventory.add(GRASS, ItemStack.MAX_STACK + 20));

        assertEquals(ItemStack.MAX_STACK, inventory.hotbarSlot(0).count());
        assertEquals(20, inventory.hotbarSlot(1).count());
        assertEquals(2, inventory.usedSlotCount());
        assertEquals(ItemStack.MAX_STACK + 20, inventory.totalItemCount());
    }

    @Test
    void addReturnsLeftoverWhenInventoryIsFull() {
        Inventory inventory = new Inventory();
        int capacity = Inventory.SLOT_COUNT * ItemStack.MAX_STACK;

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

        assertEquals(GRASS, inventory.hotbarSlot(0).blockRuntimeId());
        assertEquals(DIRT, inventory.hotbarSlot(1).blockRuntimeId());
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
        assertEquals(6, inventory.hotbarSlot(1).count());
        assertEquals(10, inventory.hotbarSlot(0).count(), "不得动到未选中的槽");

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
        assertEquals(3, inventory.hotbarSlot(0).count());
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

        assertEquals(12, restored.hotbarSlot(0).count());
        assertEquals(7, restored.hotbarSlot(1).count());
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
        assertTrue(text.startsWith("INV["));
        assertTrue(text.contains(">"), "必须标出当前选中的槽：" + text);
        assertTrue(text.contains("x2"), text);
    }

    // ============================================================ M2.2：27+9 契约

    @Test
    void slotCountAndHotbarIndexContractAreFixed() {
        Inventory inventory = new Inventory();
        assertEquals(36, Inventory.SLOT_COUNT);
        assertEquals(27, Inventory.MAIN_SIZE);
        assertEquals(9, Inventory.HOTBAR_SIZE);
        assertEquals(27, Inventory.HOTBAR_OFFSET);

        // 快捷栏相对 0..8 → 绝对 27..35（写死的契约，别人都依赖它）
        assertEquals(27, inventory.hotbarIndex(0));
        assertEquals(35, inventory.hotbarIndex(8));
        // 越界返回 -1（明确契约，不在外部自行 +27 否则会变成写主背包）
        assertEquals(-1, inventory.hotbarIndex(-1));
        assertEquals(-1, inventory.hotbarIndex(9));
        assertTrue(inventory.hotbarSlot(0).isEmpty());
        assertTrue(inventory.hotbarSlot(9).isEmpty(), "越界读取必须安全返回空");
    }

    /**
     * M2.2 最关键的不变量：{@code add()} 先填快捷栏 27..35，再填主背包 0..26。
     * 这条一旦写反，M1/M2 既有断言（"挖到的方块应出现在快捷栏"）与玩家手感一起崩。
     */
    @Test
    void addPrefersHotbarThenMain() {
        Inventory inventory = new Inventory();
        assertEquals(0, inventory.add(STONE, 70), "全部放入");

        // 70 个石头：快捷栏第 1 格满 64，第 2 格 6，主背包全空
        assertEquals(64, inventory.hotbarSlot(0).count());
        assertEquals(6, inventory.hotbarSlot(1).count());
        assertEquals(2, inventory.usedSlotCount());
        for (int i = 0; i < Inventory.MAIN_SIZE; i++) {
            assertTrue(inventory.slot(i).isEmpty(), "主背包在快捷栏未满前不得被占用，槽 " + i + " 不应有东西");
        }
    }

    /** 快捷栏（9 格）填满后，下一批才落到主背包 0..26。 */
    @Test
    void addFillsMainOnlyAfterHotbarIsFull() {
        Inventory inventory = new Inventory();
        // 先把快捷栏 9 格全部填满（每格 64）
        assertEquals(0, inventory.add(STONE, Inventory.HOTBAR_SIZE * ItemStack.MAX_STACK));
        for (int h = 0; h < Inventory.HOTBAR_SIZE; h++) {
            assertEquals(ItemStack.MAX_STACK, inventory.hotbarSlot(h).count(), "快捷栏第 " + h + " 格必须满");
        }

        // 再加 10 个 → 只能进主背包第 1 格（绝对索引 0）
        assertEquals(0, inventory.add(STONE, 10));
        assertEquals(10, inventory.slot(0).count(), "快捷栏满后必须落进主背包第 1 格");
        assertEquals(64, inventory.hotbarSlot(8).count(), "快捷栏不被本次 add 改变");
    }

    /**
     * 2026-10-02（步枪材料包）引入的第二条通道：{@code addToMain} 只填主背包 0..26，
     * <b>快捷栏一格都不碰</b>。
     *
     * <p>开局材料包走的就是它。材料是<b>囤积物</b>，快捷栏要留给玩家当场要用的枪 / 弹药；
     * 而且快捷栏在开局已被 5 格装备占住，若材料走 {@code add}，"哪几种材料进快捷栏"
     * 就会变成"材料有几种"的函数（6 种里前 4 种进快捷栏、后 2 种溢出到背包），
     * 既无法解释，也会改变 {@code M1ScriptedSelfTest} 扫描"第一个方块物品槽"的结果。
     */
    @Test
    void addToMainNeverTouchesTheHotbar() {
        Inventory inventory = new Inventory();
        assertEquals(0, inventory.addToMain(STONE, 10), "全部放入");

        assertEquals(10, inventory.slot(0).count(), "必须落在主背包第 1 格（绝对索引 0）");
        assertEquals(1, inventory.usedSlotCount());
        for (int h = 0; h < Inventory.HOTBAR_SIZE; h++) {
            assertTrue(inventory.hotbarSlot(h).isEmpty(),
                    "addToMain 不得占用快捷栏第 " + h + " 格");
        }
    }

    /** {@code addToMain} 与 {@code add} 共用堆叠规则：先并入主背包里同种未满堆，再占空槽。 */
    @Test
    void addToMainMergesWithExistingMainStackBeforeTakingANewSlot() {
        Inventory inventory = new Inventory();
        assertEquals(0, inventory.addToMain(STONE, 30));
        assertEquals(0, inventory.addToMain(STONE, 40), "64 以内应并入同一格");

        assertEquals(ItemStack.MAX_STACK, inventory.slot(0).count(), "并入后第 1 格满 64");
        assertEquals(6, inventory.slot(1).count(), "超出的 6 个落到主背包第 2 格");
        assertEquals(2, inventory.usedSlotCount());
        assertTrue(inventory.hotbarSlot(0).isEmpty(), "全程不碰快捷栏");
    }

    /**
     * 主背包装满后，{@code addToMain} 如实返回未放入数量，且<b>不会</b>溢到快捷栏。
     * 这条是"它没有偷偷退化成 {@code add}"的反向证据。
     */
    @Test
    void addToMainReportsLeftoverWithoutSpillingIntoTheHotbar() {
        Inventory inventory = new Inventory();
        assertEquals(0, inventory.addToMain(STONE, Inventory.MAIN_SIZE * ItemStack.MAX_STACK),
                "主背包刚好装满");

        assertEquals(10, inventory.addToMain(STONE, 10), "放不下的 10 个必须如实返回");
        for (int h = 0; h < Inventory.HOTBAR_SIZE; h++) {
            assertTrue(inventory.hotbarSlot(h).isEmpty(),
                    "宁可返回余量，也不得改用快捷栏（那样就退化成 add 了）");
        }
    }

    /** {@code addToMain} 与 {@code add} 一样忽略非正数量与空气，且口径（返回余量）一致。 */
    @Test
    void addToMainIgnoresNonPositiveAmountsAndAirBlock() {
        Inventory inventory = new Inventory();
        assertEquals(0, inventory.addToMain(GRASS, 0));
        assertEquals(0, inventory.addToMain(GRASS, -5), "负数按「无法放入」处理，余量下限为 0");
        assertEquals(10, inventory.addToMain(0, 10), "空气必须被整笔退回：余量 = 10");
        assertEquals(0, inventory.totalItemCount(), "空气不得占用任何槽位");
        assertEquals(0, inventory.usedSlotCount());
    }

    /** 枪（maxStack = 1）不得堆叠：两次 add 各占一格。堆叠上限来自物品本身，不是写死的 64。 */
    @Test
    void gunDoesNotStackAcrossSlots() {
        Inventory inventory = new Inventory();
        int pistol = ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID);

        assertEquals(0, inventory.add(pistol, 1));
        assertEquals(0, inventory.add(pistol, 1), "第二把枪也必须能放入（占新格），而不是被拒");

        assertEquals(2, inventory.countOf(pistol), "持有两把枪");
        assertEquals(2, inventory.usedSlotCount(), "两把枪占两个格");
        assertEquals(1, inventory.hotbarSlot(0).count(), "每格只有 1 把");
        assertEquals(1, inventory.hotbarSlot(1).count());
    }
}

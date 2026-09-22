package com.skyisland.player;

import com.skyisland.item.ItemRegistry;
import com.skyisland.util.Log;

/**
 * 背包鼠标交互的<b>纯逻辑语义层</b>（M2.2）。
 *
 * <p><b>无 GL、无渲染、可单测：</b>本类只读写 {@link Inventory} 的数据，不碰任何 GL / 界面状态，
 * 因此可以直接用 JUnit 验证"左键 / Shift 点击 / 安全关闭"的每一条结果。具体的渲染与输入映射
 * 留给 M3 的 UI 层，本层只给出"给定槽位，应该发生什么"的权威答案。
 *
 * <p><b>堆叠上限一律来自 {@link ItemStack#maxStack()} / {@link ItemRegistry#maxStackOf(int)}</b>
 * （枪 = 1，弹药 = 128，方块 = 64）。本层<b>禁止写死 64</b> —— 否则手枪会变成可叠 64 把、弹药被当 64 截断。
 *
 * <p><b>标准 Minecraft 风格语义：</b>
 * <ul>
 *   <li>{@link #leftClick}：拿起 / 放下 / 并入 / 交换一格；</li>
 *   <li>{@link #shiftClick}：把整格在"主背包 ↔ 快捷栏"之间搬运，<b>原子</b>（放不下就整体不动）；</li>
 *   <li>{@link #closeScreen}：安全关闭，先把光标上的东西塞回背包，塞不下的丢弃。</li>
 * </ul>
 *
 * <p><b>右键分堆（{@code rightClick}）属 M3，本里程碑不实现。</b>规格把它列为 M3 TODO；
 * 写了不接线就是死代码，而本项目明确禁止死接线，因此这里只留契约说明、不放空壳方法。
 *
 * <p><b>{@link #closeScreen} 的丢弃是设计决定：</b>M2 没有 {@code ItemEntity}（掉落物实体属 M3），
 * 关闭背包时若光标上还捏着放不回去的东西，只能丢弃，<b>不可凭空复制</b>。调用方若想保留，应在 UI 层
 * 先把光标清空或提示玩家。返回值 {@code SHIFT_BLOCKED} 表示"有物品因背包满被丢弃"。
 */
public final class InventoryInteraction {

    /** 一次交互的结果分类。 */
    public enum Result {
        /** 什么都没发生（空手点空槽 / 满堆点同物 / 空格 Shift 搬运）。 */
        NONE,
        /** 整堆拿到手上（空手点非空槽）。 */
        PICKUP_ALL,
        /** 整堆放到空格（手上有东西点空槽）。 */
        PLACE_ALL,
        /** 手上的部分并入目标，剩下留在手上。 */
        MERGE_PARTIAL,
        /** 手上的全部并入目标，手上变空。 */
        MERGE_FULL,
        /** 手上与目标交换。 */
        SWAP,
        /** Shift 搬运成功（整格移到另一区）。 */
        SHIFT_MOVED,
        /** Shift 搬运失败：目标区放不下，原格保持不变（原子性）。 */
        SHIFT_BLOCKED,
        /** 槽索引越界，交互被拒。 */
        REJECTED
    }

    /** 交互结果：{@link Result} + 受影响的绝对槽索引（越界 / 关闭时为 -1）。 */
    public record Outcome(Result result, int slotIndex) {
    }

    private InventoryInteraction() {
    }

    /**
     * 左键点击某绝对槽位（标准 Minecraft 风格）。
     *
     * <p>语义矩阵（cursor = 手上，target = 目标槽）：
     * <table>
     *   <tr><th>cursor</th><th>target</th><th>结果</th></tr>
     *   <tr><td>空</td><td>非空</td><td>{@link Result#PICKUP_ALL} 整堆拿起</td></tr>
     *   <tr><td>空</td><td>空</td><td>{@link Result#NONE} 无操作</td></tr>
     *   <tr><td>非空</td><td>空</td><td>{@link Result#PLACE_ALL} 整堆放下</td></tr>
     *   <tr><td>非空</td><td>同物未满</td><td>{@link Result#MERGE_FULL}（全并入）或 {@link Result#MERGE_PARTIAL}（部分并入，余留手上）</td></tr>
     *   <tr><td>非空</td><td>同物已满</td><td>{@link Result#NONE} 无操作（已满，塞不进）</td></tr>
     *   <tr><td>非空</td><td>异物</td><td>{@link Result#SWAP} 交换</td></tr>
     *   <tr><td>—</td><td>越界槽</td><td>{@link Result#REJECTED}</td></tr>
     * </table>
     *
     * @param inv      背包（会被就地修改）
     * @param slotIndex 绝对槽索引（0..35）；越界返回 {@link Result#REJECTED}
     */
    public static Outcome leftClick(Inventory inv, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= Inventory.SLOT_COUNT) {
            return new Outcome(Result.REJECTED, slotIndex);
        }
        ItemStack cursor = inv.cursorStack();
        ItemStack target = inv.slot(slotIndex);

        if (cursor.isEmpty()) {
            if (target.isEmpty()) {
                return new Outcome(Result.NONE, slotIndex);
            }
            // 整堆拿起
            inv.setSlot(slotIndex, ItemStack.EMPTY);
            inv.setCursorStack(target);
            return new Outcome(Result.PICKUP_ALL, slotIndex);
        }

        // 手上有东西
        if (target.isEmpty()) {
            inv.setSlot(slotIndex, cursor);
            inv.setCursorStack(ItemStack.EMPTY);
            return new Outcome(Result.PLACE_ALL, slotIndex);
        }

        if (cursor.itemRuntimeId() == target.itemRuntimeId()) {
            int space = target.maxStack() - target.count(); // 上限来自 ItemStack.maxStack()
            if (space <= 0) {
                // 目标已满，塞不进 —— 无操作（与越界拒绝区分：这是"合法但有边界"）。
                return new Outcome(Result.NONE, slotIndex);
            }
            int move = Math.min(space, cursor.count());
            inv.setSlot(slotIndex, target.grown(move));
            ItemStack newCursor = cursor.shrunk(move);
            inv.setCursorStack(newCursor);
            return new Outcome(newCursor.isEmpty() ? Result.MERGE_FULL : Result.MERGE_PARTIAL, slotIndex);
        }

        // 不同物品 → 交换（上限不受影响，整堆互换）
        inv.setSlot(slotIndex, cursor);
        inv.setCursorStack(target);
        return new Outcome(Result.SWAP, slotIndex);
    }

    /**
     * Shift 点击：把整格在"主背包 ↔ 快捷栏"之间搬运。
     *
     * <p><b>原子性：</b>先在目标区模拟"同种未满先并入、再占空槽"，若整格放不下则<b>原格保持不变</b>
     * 并返回 {@link Result#SHIFT_BLOCKED}（不允许移动一半）。只有确认全部放得下才真正写入。
     *
     * <p>目标区搜索顺序：主背包 {@code 0..26} 自上而下；快捷栏 {@code 27..35} 自左向右。
     *
     * @param inv       背包（会被就地修改，或保持不变当放不下时）
     * @param slotIndex 绝对槽索引（0..35）；空格返回 {@link Result#NONE}，越界返回 {@link Result#REJECTED}
     */
    public static Outcome shiftClick(Inventory inv, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= Inventory.SLOT_COUNT) {
            return new Outcome(Result.REJECTED, slotIndex);
        }
        ItemStack source = inv.slot(slotIndex);
        if (source.isEmpty()) {
            return new Outcome(Result.NONE, slotIndex);
        }

        // 源在快捷栏 → 目标区是主背包；源在主背包 → 目标区是快捷栏。两区不相交。
        boolean fromHotbar = slotIndex >= Inventory.HOTBAR_OFFSET;
        int to = fromHotbar ? Inventory.MAIN_SIZE : Inventory.SLOT_COUNT;
        int from = fromHotbar ? 0 : Inventory.HOTBAR_OFFSET;

        int itemRuntimeId = source.itemRuntimeId();
        int perStack = source.maxStack(); // 上限来自 ItemStack.maxStack()
        int toMove = source.count();

        // ---- 先用只读模拟确认整格放得下（原子性护栏）----
        int remaining = toMove;
        for (int i = from; i < to && remaining > 0; i++) {
            ItemStack s = inv.slot(i);
            if (!s.isEmpty() && s.itemRuntimeId() == itemRuntimeId && s.count() < s.maxStack()) {
                remaining -= Math.min(remaining, s.freeSpace());
            }
        }
        for (int i = from; i < to && remaining > 0; i++) {
            if (inv.slot(i).isEmpty()) {
                remaining -= Math.min(remaining, perStack);
            }
        }
        if (remaining > 0) {
            // 放不下：原格不动，整体拒绝。
            return new Outcome(Result.SHIFT_BLOCKED, slotIndex);
        }

        // ---- 确实放得下：先把源格清空（源在另一区，不影响目标区模拟），再写入目标区 ----
        inv.setSlot(slotIndex, ItemStack.EMPTY);
        int left = toMove;
        for (int i = from; i < to && left > 0; i++) {
            ItemStack s = inv.slot(i);
            if (!s.isEmpty() && s.itemRuntimeId() == itemRuntimeId && s.count() < s.maxStack()) {
                int put = Math.min(left, s.freeSpace());
                inv.setSlot(i, s.grown(put));
                left -= put;
            }
        }
        for (int i = from; i < to && left > 0; i++) {
            if (inv.slot(i).isEmpty()) {
                int put = Math.min(left, perStack);
                inv.setSlot(i, ItemStack.of(itemRuntimeId, put));
                left -= put;
            }
        }
        return new Outcome(Result.SHIFT_MOVED, slotIndex);
    }

    /**
     * 安全关闭背包：先尝试把光标上的堆叠塞回背包，塞不下的丢弃。
     *
     * <p><b>丢弃是设计决定（见类文档）：</b>M2 无掉落物实体，光标上放不回去的东西只能丢弃，
     * <b>不可凭空复制</b>。因此关闭后 {@link Inventory#totalItemCount()} 最多增加"成功塞回的部分"，
     * 绝不会因丢弃而虚增。
     *
     * @return {@link Result#PLACE_ALL}（光标本就空 / 全部塞回）或
     *         {@link Result#SHIFT_BLOCKED}（有物品因背包满被丢弃）；{@code slotIndex} 恒为 -1。
     */
    public static Outcome closeScreen(Inventory inv) {
        ItemStack cursor = inv.cursorStack();
        if (cursor.isEmpty()) {
            return new Outcome(Result.PLACE_ALL, -1);
        }
        int total = cursor.count();
        int leftover = inv.add(cursor.itemRuntimeId(), total);
        if (leftover <= 0) {
            inv.setCursorStack(ItemStack.EMPTY);
            return new Outcome(Result.PLACE_ALL, -1);
        }
        // 仍剩 leftover 个无法塞回：按设计决定丢弃（M2 无 ItemEntity，物品不得凭空复制）。
        Log.noteWarning("背包", "关闭背包时 " + leftover + " 个 " + cursor.item().id()
                + " 因背包已满被丢弃（M2 无掉落物实体，不复制）");
        inv.setCursorStack(ItemStack.EMPTY);
        return new Outcome(Result.SHIFT_BLOCKED, -1);
    }
}

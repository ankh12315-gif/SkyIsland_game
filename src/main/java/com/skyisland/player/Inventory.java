package com.skyisland.player;

import com.skyisland.item.Item;
import com.skyisland.item.ItemRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * M1 最小背包：<b>只有一条 9 格快捷栏</b>（M1 指令 B12）。
 *
 * <p><b>为什么 M1 不做 27 格完整背包界面：</b>First Playable 需要证明的闭环是
 * "挖 → 拿到 → 放回去"。9 格快捷栏 + 数字键切槽已经能完整走通这条链，
 * 而完整背包需要一套 UI、拖放、右键分堆与对应的输入映射 —— 那些属于 M3 的 UI 阶段。
 * 提前实现会挤占网格/物理/存档这些真正阻塞 M1 的部分。
 *
 * <p><b>但数据结构不缩水：</b>{@link ItemStack} 与"槽位数组 + 选中槽"的模型
 * 与后续完整背包一致，扩容只是把数组变成 27+9 并加上界面，不需要改动物品语义。
 *
 * <p><b>掉落直接进背包（不是掉在地上）：</b>M1 没有 {@code ItemEntity}（属 M2），
 * 所以破坏方块后物品直接入包。这条差异会写进 M1 报告的"与 PRD 的差距"一节。
 */
public final class Inventory {

    public static final int HOTBAR_SIZE = 9;

    private final ItemStack[] slots = new ItemStack[HOTBAR_SIZE];
    private int selectedSlot = 0;

    public Inventory() {
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            slots[i] = ItemStack.EMPTY;
        }
    }

    // ------------------------------------------------------------ 槽位

    public int size() {
        return HOTBAR_SIZE;
    }

    public ItemStack slot(int index) {
        return index < 0 || index >= HOTBAR_SIZE ? ItemStack.EMPTY : slots[index];
    }

    public void setSlot(int index, ItemStack stack) {
        if (index < 0 || index >= HOTBAR_SIZE) {
            return;
        }
        slots[index] = stack == null ? ItemStack.EMPTY : stack;
    }

    public int selectedSlot() {
        return selectedSlot;
    }

    public ItemStack selectedStack() {
        return slots[selectedSlot];
    }

    /** 直接选中某个槽（数字键）。 */
    public void selectSlot(int index) {
        if (index >= 0 && index < HOTBAR_SIZE) {
            selectedSlot = index;
        }
    }

    /** 相对切换槽位（滚轮）。超出两端时循环，避免"滚到头没反应"的困惑。 */
    public void cycleSlot(int delta) {
        if (delta == 0) {
            return;
        }
        int next = Math.floorMod(selectedSlot + delta, HOTBAR_SIZE);
        selectedSlot = next;
    }

    // ------------------------------------------------------------ 增删

    /**
     * 加入若干方块。
     *
     * <p>先并入同种未满的槽，再占用空槽 —— 与玩家预期一致（不要出现同种物品散在三格里）。
     *
     * @return 实际未能放入的数量（0 表示全部放入）
     */
    public int add(int itemRuntimeId, int amount) {
        if (itemRuntimeId == 0 || amount <= 0) {
            return Math.max(0, amount);
        }
        Item item = ItemRegistry.byRuntimeId(itemRuntimeId);
        if (item.isEmpty()) {
            return Math.max(0, amount);
        }
        // 上限按物品查询：方块 64、弹药 128、枪械 1（PRD 5.4.2 / 5.1）
        int perStack = item.maxStack();
        int remaining = amount;

        for (int i = 0; i < HOTBAR_SIZE && remaining > 0; i++) {
            ItemStack s = slots[i];
            if (!s.isEmpty() && s.itemRuntimeId() == itemRuntimeId && s.count() < s.maxStack()) {
                int put = Math.min(remaining, s.freeSpace());
                slots[i] = s.grown(put);
                remaining -= put;
            }
        }
        for (int i = 0; i < HOTBAR_SIZE && remaining > 0; i++) {
            if (slots[i].isEmpty()) {
                int put = Math.min(remaining, perStack);
                slots[i] = ItemStack.of(itemRuntimeId, put);
                remaining -= put;
            }
        }
        return remaining;
    }

    /** 消耗选中槽若干个，成功返回 true（数量不足则消费并返回 false）。 */
    public boolean consumeSelected(int amount) {
        if (amount <= 0) {
            return true;
        }
        ItemStack s = slots[selectedSlot];
        if (s.isEmpty() || s.count() < amount) {
            return false;
        }
        slots[selectedSlot] = s.shrunk(amount);
        return true;
    }

    /** 统计某物品的持有数量（自测断言用）。对方块物品，实参即方块 ID。 */
    public int countOf(int itemRuntimeId) {
        int total = 0;
        for (ItemStack s : slots) {
            if (!s.isEmpty() && s.itemRuntimeId() == itemRuntimeId) {
                total += s.count();
            }
        }
        return total;
    }

    /** 按 stable ID 统计持有数量（用于枪械 / 弹药的业务判定）。 */
    public int countOfItem(String stableItemId) {
        return countOf(ItemRegistry.runtimeIdOf(stableItemId));
    }

    /** 是否持有某物品（至少一个）。 */
    public boolean hasItem(String stableItemId) {
        return countOfItem(stableItemId) > 0;
    }

    /** 消耗若干某物品（跨槽扣减，从后往前）。数量不足则不做任何改动并返回 false。 */
    public boolean consumeItem(String stableItemId, int amount) {
        if (amount <= 0) {
            return true;
        }
        int itemRuntimeId = ItemRegistry.runtimeIdOf(stableItemId);
        if (itemRuntimeId == ItemRegistry.EMPTY_RUNTIME_ID) {
            return false;
        }
        if (countOf(itemRuntimeId) < amount) {
            return false;
        }
        int left = amount;
        for (int i = HOTBAR_SIZE - 1; i >= 0 && left > 0; i--) {
            ItemStack s = slots[i];
            if (s.isEmpty() || s.itemRuntimeId() != itemRuntimeId) {
                continue;
            }
            int take = Math.min(left, s.count());
            slots[i] = s.shrunk(take);
            left -= take;
        }
        return true;
    }

    /** 清空全部槽位并返回原内容（死亡掉落用）。 */
    public List<ItemStack> drainAll() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (!slots[i].isEmpty()) {
                out.add(slots[i]);
            }
            slots[i] = ItemStack.EMPTY;
        }
        return out;
    }

    public int usedSlotCount() {
        int n = 0;
        for (ItemStack s : slots) {
            if (!s.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    public int totalItemCount() {
        int n = 0;
        for (ItemStack s : slots) {
            n += s.count();
        }
        return n;
    }

    public List<ItemStack> snapshot() {
        return new ArrayList<>(List.of(slots));
    }

    /** 用快照覆盖（存档读取）。 */
    public void restore(List<ItemStack> stacks, int selected) {
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            slots[i] = (stacks != null && i < stacks.size() && stacks.get(i) != null)
                    ? stacks.get(i) : ItemStack.EMPTY;
        }
        selectSlot(selected);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("HOTBAR[");
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (i == selectedSlot) {
                sb.append('>');
            }
            sb.append(slots[i]);
            if (i < HOTBAR_SIZE - 1) {
                sb.append(' ');
            }
        }
        return sb.append(']').toString();
    }
}

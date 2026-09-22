package com.skyisland.player;

import com.skyisland.item.Item;
import com.skyisland.item.ItemRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * M2.2 背包数据层：<b>27 格主背包 + 9 格快捷栏 = 36 格</b>（M1 指令 B12 的扩容）。
 *
 * <p><b>绝对索引契约（写死，全里程碑共用）：</b>
 * <ul>
 *   <li>{@link #MAIN_SIZE} = 27，{@link #HOTBAR_SIZE} = 9，{@link #SLOT_COUNT} = 36；</li>
 *   <li>绝对索引 {@code 0..26} = 主背包（界面上 3 行 × 9）；绝对索引 {@code 27..35} = 快捷栏；</li>
 *   <li>{@link #HOTBAR_OFFSET} = {@link #MAIN_SIZE} = 27，是快捷栏在主数组里的起点；</li>
 *   <li>{@code selectedSlot} <b>保持 0..8 的快捷栏内相对索引语义</b>（不改成绝对索引），
 *       因此 {@link #selectedStack()} = {@code slot(hotbarIndex(selectedSlot))}；</li>
 *   <li>取快捷栏绝对索引用 {@link #hotbarIndex(int)}，取快捷栏内容用 {@link #hotbarSlot(int)}。</li>
 * </ul>
 *
 * <p><b>为什么 M1 是 9 格、M2.2 才扩到 36：</b>First Playable 要证明的闭环是
 * "挖 → 拿到 → 放回去"，9 格快捷栏 + 数字键切槽已能走通。完整背包界面属于 M3 的 UI 阶段，
 * 但数据结构从一开始就按"槽位数组 + 选中槽"建模，扩容只是把数组变 27+9，物品语义不变。
 *
 * <p><b>{@code add()} 的填充顺序（关键，写死）：</b>先扫快捷栏 {@code 27..35}，再扫主背包
 * {@code 0..26}；每一遍里先"并入同种未满堆"，再"占用空槽"。理由：M1/M2 的既有断言假设
 * "挖到的方块会出现在快捷栏里"（那时只有 9 格）。若改成先填 {@code 0..26}，物品会落进主背包、
 * 快捷栏空着，所有既有断言与玩家手感一起崩。该顺序由 {@code InventoryTest} 的
 * {@code addPrefersHotbarThenMain} 单测定钉。
 *
 * <p><b>掉落直接进背包（不是掉在地上）：</b>M2 仍没有 {@code ItemEntity}（属 M3），所以破坏方块后
 * 物品直接入包。这条差异会写进 M2 报告的"与 PRD 的差距"一节。
 *
 * <p><b>光标持有堆叠（"手上拿着的那一堆"）：</b>放在本类里，使关闭背包时的安全处理与持久化都能
 * 统一访问（见 {@link #cursorStack()}/{@link #setCursorStack(ItemStack)}）。具体鼠标交互语义在
 * {@link InventoryInteraction}（纯逻辑、无 GL、可单测）。
 */
public final class Inventory {

    /** 主背包格数（界面 3 行 × 9）。 */
    public static final int MAIN_SIZE = 27;

    /** 快捷栏格数（数字键 1..9）。 */
    public static final int HOTBAR_SIZE = 9;

    /** 快捷栏在主数组里的绝对起点 = {@link #MAIN_SIZE}。 */
    public static final int HOTBAR_OFFSET = MAIN_SIZE;

    /** 总格数 = 主背包 + 快捷栏。 */
    public static final int SLOT_COUNT = MAIN_SIZE + HOTBAR_SIZE;

    private final ItemStack[] slots = new ItemStack[SLOT_COUNT];
    private int selectedSlot = 0;

    /** 光标持有堆叠：鼠标"拿起"后悬在手上、尚未放回格子的那堆。EMPTY 表示空手。 */
    private ItemStack cursorStack = ItemStack.EMPTY;

    public Inventory() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = ItemStack.EMPTY;
        }
    }

    // ------------------------------------------------------------ 槽位

    /** 总格数（主背包 + 快捷栏 = 36）。调用方一律把它当"总槽数"用。 */
    public int size() {
        return SLOT_COUNT;
    }

    /** 按绝对索引取格；越界返回 {@link ItemStack#EMPTY}（不抛异常）。 */
    public ItemStack slot(int index) {
        return index < 0 || index >= SLOT_COUNT ? ItemStack.EMPTY : slots[index];
    }

    /** 按绝对索引写格；越界忽略（不抛异常）。null 当作 {@link ItemStack#EMPTY}。 */
    public void setSlot(int index, ItemStack stack) {
        if (index < 0 || index >= SLOT_COUNT) {
            return;
        }
        slots[index] = stack == null ? ItemStack.EMPTY : stack;
    }

    /**
     * 快捷栏相对槽位（0..8）→ 绝对索引（27..35）。
     *
     * <p>越界（{@code < 0} 或 {@code >= HOTBAR_SIZE}）返回 {@code -1}，调用方据此自行决定行为。
     * 这是明确的契约：不要在外部自行 {@code + HOTBAR_OFFSET}，否则越界会变成"写入主背包"。
     */
    public int hotbarIndex(int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot >= HOTBAR_SIZE) {
            return -1;
        }
        return HOTBAR_OFFSET + hotbarSlot;
    }

    /** 按快捷栏相对槽位（0..8）取格；越界返回 {@link ItemStack#EMPTY}。 */
    public ItemStack hotbarSlot(int hotbarSlot) {
        int abs = hotbarIndex(hotbarSlot);
        return abs < 0 ? ItemStack.EMPTY : slot(abs);
    }

    public int selectedSlot() {
        return selectedSlot;
    }

    /** 当前选中的快捷栏格（绝对索引在 {@code 27..35}）。空手时为 {@link ItemStack#EMPTY}。 */
    public ItemStack selectedStack() {
        return hotbarSlot(selectedSlot);
    }

    /** 直接选中某个快捷栏相对槽位（数字键）。越界忽略。 */
    public void selectSlot(int index) {
        if (index >= 0 && index < HOTBAR_SIZE) {
            selectedSlot = index;
        }
    }

    /** 相对切换快捷栏槽位（滚轮）。超出两端时循环，避免"滚到头没反应"的困惑。 */
    public void cycleSlot(int delta) {
        if (delta == 0) {
            return;
        }
        int next = Math.floorMod(selectedSlot + delta, HOTBAR_SIZE);
        selectedSlot = next;
    }

    // ------------------------------------------------------------ 光标持有堆叠

    /** 手上拿着的堆叠；空手为 {@link ItemStack#EMPTY}。 */
    public ItemStack cursorStack() {
        return cursorStack;
    }

    /** 设置手上拿着的堆叠；null 当作空手（{@link ItemStack#EMPTY}）。 */
    public void setCursorStack(ItemStack stack) {
        cursorStack = stack == null ? ItemStack.EMPTY : stack;
    }

    /** 是否正拿着东西（光标非空）。 */
    public boolean isHoldingCursorStack() {
        return !cursorStack.isEmpty();
    }

    // ------------------------------------------------------------ 增删

    /**
     * 加入若干物品。
     *
     * <p><b>填充顺序（写死，见类文档）：</b>先快捷栏 {@code 27..35}、再主背包 {@code 0..26}；
     * 每一区内部先并入同种未满的槽、再占用空槽 —— 与玩家预期一致（不要出现同种物品散在三格里）。
     * 该顺序由 {@code InventoryTest#addPrefersHotbarThenMain} 单测定钉。
     *
     * @return 实际未能放入的数量（0 表示全部放入）。调用方据此告警 / 掉落。
     */
    public int add(int itemRuntimeId, int amount) {
        if (itemRuntimeId == 0 || amount <= 0) {
            return Math.max(0, amount);
        }
        Item item = ItemRegistry.byRuntimeId(itemRuntimeId);
        if (item.isEmpty()) {
            return Math.max(0, amount);
        }
        // 上限按物品查询：方块 64、弹药 128、枪械 1（PRD 5.4.2 / 5.1）。
        // 交互层与这里都不得写死 64。
        int perStack = item.maxStack();
        int remaining = amount;

        // 先快捷栏，再主背包。
        remaining = fillRegion(HOTBAR_OFFSET, SLOT_COUNT, itemRuntimeId, perStack, remaining);
        remaining = fillRegion(0, MAIN_SIZE, itemRuntimeId, perStack, remaining);
        return remaining;
    }

    /**
     * 在 [from, to) 区间内放入 {@code remaining} 个物品：先并入同种未满堆，再占空槽。
     * 返回放完后仍未放入的剩余量。
     */
    private int fillRegion(int from, int to, int itemRuntimeId, int perStack, int remaining) {
        if (remaining <= 0) {
            return remaining;
        }
        for (int i = from; i < to && remaining > 0; i++) {
            ItemStack s = slots[i];
            if (!s.isEmpty() && s.itemRuntimeId() == itemRuntimeId && s.count() < s.maxStack()) {
                int put = Math.min(remaining, s.freeSpace());
                slots[i] = s.grown(put);
                remaining -= put;
            }
        }
        for (int i = from; i < to && remaining > 0; i++) {
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
        int abs = hotbarIndex(selectedSlot);
        ItemStack s = slots[abs];
        if (s.isEmpty() || s.count() < amount) {
            return false;
        }
        slots[abs] = s.shrunk(amount);
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
        for (int i = SLOT_COUNT - 1; i >= 0 && left > 0; i--) {
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

    /** 清空全部槽位并返回原内容（死亡掉落用）。光标堆叠不受影响（死亡掉落由调用方另处理）。 */
    public List<ItemStack> drainAll() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < SLOT_COUNT; i++) {
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

    /**
     * 36 个槽位里的物品总数（<b>不含</b>光标持有堆）。
     *
     * <p><b>为什么必须写明这条：</b>背包界面打开时，玩家手上可能正拿着
     * {@link #cursorStack()}。若这里把它算进来，"总数"就会随鼠标动作变化，
     * 而它最典型的用途恰恰是"关屏前后比一比，证明没有原地复制"——
     * 那个比较只有在光标<b>不被计入</b>、因而两侧公式必须是
     * {@code before + carried} 的前提下才有意义。
     * 口径不写清的代价已经出现过一次：有人把断言写成 {@code before == after}
     * 并因此得到一个看起来像产品缺陷的假红。
     */
    public int totalItemCount() {
        int n = 0;
        for (ItemStack s : slots) {
            n += s.count();
        }
        return n;
    }

    /** 全量快照（绝对索引 0..35）。用于存档读取与跨存档比对。 */
    public List<ItemStack> snapshot() {
        return new ArrayList<>(List.of(slots));
    }

    /**
     * 用快照按绝对索引覆盖 36 格（存档读取）。
     *
     * <p>{@code stacks} 长度不足 36 时，未覆盖的格被清空（而不是保留旧值）。
     * {@code stacks} 为 null 时全部清空。{@code selected} 按快捷栏相对语义校验。
     */
    public void restore(List<ItemStack> stacks, int selected) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = (stacks != null && i < stacks.size() && stacks.get(i) != null)
                    ? stacks.get(i) : ItemStack.EMPTY;
        }
        selectSlot(selected);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("INV[");
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (i == hotbarIndex(selectedSlot)) {
                sb.append('>');
            }
            sb.append(slots[i]);
            if (i < SLOT_COUNT - 1) {
                sb.append(' ');
            }
        }
        return sb.append(']').toString();
    }
}

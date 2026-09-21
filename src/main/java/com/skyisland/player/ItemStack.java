package com.skyisland.player;

import com.skyisland.item.Item;
import com.skyisland.item.ItemRegistry;

/**
 * 一格物品（M2：改用 <b>item runtimeId</b>）。
 *
 * <p><b>M2 的这次改动是 M1 时就预告过的。</b>M1 用 {@code blockRuntimeId} 当物品标识，
 * 因为当时"挖到的方块 = 可放置的物品"成立。M2 引入手枪与子弹后等式破掉：
 * 手枪没有对应方块、不能放置、还有自己的伤害与弹匣。物品因此有了独立注册表
 * （{@link ItemRegistry}）。
 *
 * <p><b>兼容性由"前缀对齐"保证，而不是靠改调用方：</b>
 * {@link ItemRegistry} 让方块物品的 item runtimeId 与 block runtimeId 严格相等，
 * 于是：
 * <ul>
 *   <li>{@link #of(int, int)} 的实参对方块物品而言仍是"方块 ID"，所有 M1 调用点无需改动；</li>
 *   <li>{@link #blockRuntimeId()} 保留为桥接口径（空槽 → 0，方块物品 → 其方块 ID，非方块物品 → −1），
 *       使 M1 的背包 / 存档 / 自测继续按原语义工作；</li>
 *   <li>存档本来就用 stable string ID（{@code PlayerState.Slot.item}），物品 runtimeId 变化不影响老存档。</li>
 * </ul>
 *
 * <p><b>堆叠上限不再是全局常量：</b>M1 所有物品都是方块，统一 64。
 * M2 起弹药是 128、枪械是 1，因此上限按物品查询（{@link #maxStack()}）。
 * {@link #MAX_STACK} 保留为"默认上限"，仍可被测试与旧代码引用。
 *
 * @param itemRuntimeId 物品运行时 ID；{@link #EMPTY} 时为 0
 * @param count         数量，0 表示空
 */
public record ItemStack(int itemRuntimeId, int count) {

    /** 默认单格上限（PRD 5.1：方块统一 64）。弹药另有 128，见 {@link ItemRegistry#AMMO_MAX_STACK}。 */
    public static final int MAX_STACK = ItemRegistry.DEFAULT_MAX_STACK;

    /** 空槽。用 runtimeId = 0 表示，避免引入 null 检查。 */
    public static final ItemStack EMPTY = new ItemStack(0, 0);

    public boolean isEmpty() {
        return count <= 0 || itemRuntimeId == 0;
    }

    /** 本格物品定义；空槽返回 {@link ItemRegistry#empty()}。 */
    public Item item() {
        return ItemRegistry.byRuntimeId(itemRuntimeId);
    }

    /**
     * 本格物品的堆叠上限。
     *
     * <p>空槽返回默认上限而不是 0：调用方问"这格还能放多少"时，
     * 空的格子显然应该回答"能放满一格"，回答 0 会让 {@link #freeSpace()} 失去意义。
     */
    public int maxStack() {
        if (isEmpty()) {
            return MAX_STACK;
        }
        return ItemRegistry.maxStackOf(itemRuntimeId);
    }

    public ItemStack withCount(int newCount) {
        if (newCount <= 0) {
            return EMPTY;
        }
        return new ItemStack(itemRuntimeId, Math.min(newCount, maxStack()));
    }

    public ItemStack grown(int amount) {
        return withCount(count + amount);
    }

    public ItemStack shrunk(int amount) {
        return withCount(count - amount);
    }

    /** 是否与另一格可以合并（同种物品且双方都未满）。 */
    public boolean canMergeWith(ItemStack other) {
        return !isEmpty() && !other.isEmpty()
                && other.itemRuntimeId == itemRuntimeId
                && count < maxStack()
                && other.count < other.maxStack();
    }

    public int freeSpace() {
        return maxStack() - count;
    }

    /**
     * 桥接口径：本格对应的<b>方块</b> runtimeId。
     *
     * <ul>
     *   <li>空槽 → {@code 0}（空气）：M1 语义把"空"等同于空气，保持这条等式可让旧断言继续成立；</li>
     *   <li>方块物品 → 其方块 runtimeId（与 {@link #itemRuntimeId()} 相等，由前缀对齐保证）；</li>
     *   <li>非方块物品（枪械 / 弹药 / 煤炭）→ {@code -1}。</li>
     * </ul>
     *
     * <p>返回 {@code -1} 而不是 {@code 0}：{@code 0} 是空气、是"合法方块 ID"，
     * 用它表示"这不是方块"会让放置逻辑把弹药当成空气方块去放置。
     */
    public int blockRuntimeId() {
        if (isEmpty()) {
            return 0;
        }
        return item().blockRuntimeId();
    }

    /** 是否为方块物品（可放置）。 */
    public boolean isBlockItem() {
        return !isEmpty() && item().isBlock();
    }

    public static ItemStack of(int itemRuntimeId, int count) {
        if (itemRuntimeId == 0 || count <= 0) {
            return EMPTY;
        }
        Item it = ItemRegistry.byRuntimeId(itemRuntimeId);
        if (it.isEmpty()) {
            return EMPTY;
        }
        return new ItemStack(itemRuntimeId, Math.min(count, it.maxStack()));
    }

    /** 按方块构造（M1 兼容入口；对方块而言 item runtimeId 与 block runtimeId 相等）。 */
    public static ItemStack of(com.skyisland.world.block.Block block, int count) {
        return block == null ? EMPTY : of(block.runtimeId(), count);
    }

    @Override
    public String toString() {
        return isEmpty() ? "-" : (item().id() + "x" + count);
    }
}

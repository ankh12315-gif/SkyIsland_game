package com.skyisland.item;

import com.skyisland.util.Log;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品注册表（TECH_DESIGN §L.1）。M2 引入。
 *
 * <p><b>核心不变式 —— 方块物品的 item runtimeId == block runtimeId。</b>
 * 初始化时按 {@link BlockRegistry#all()} 的顺序逐个生成方块物品，并断言
 * {@code block.runtimeId() == item.runtimeId()}。这条对齐带来两个好处：
 * <ol>
 *   <li>M1 的背包 / 存档 / 自测全部不需要改动：对方块物品，"物品 ID"与"方块 ID"是同一个数；</li>
 *   <li>方块数量扩容时，所有方块物品的 ID 自动跟着走，不会出现"物品表与方块表错位"。</li>
 * </ol>
 * 它由 {@link #verify()} 在构建期校验，一旦违反立刻抛异常而不是静默错位。
 *
 * <p><b>与 {@link BlockRegistry} 的关系是单向的：</b>ItemRegistry 依赖 BlockRegistry，
 * BlockRegistry 不认识 ItemRegistry。方块的掉落物因此用 <b>stable string ID</b> 表达
 * （见 {@link Block#dropItemId()}），而不是持有 {@link Item} ——
 * 否则两个类会互相 static 初始化，产生难以诊断的类初始化死锁。
 */
public final class ItemRegistry {

    /** 空槽固定占用 runtimeId = 0（与空气方块同号，语义一致：都是"这里没有东西"）。 */
    public static final int EMPTY_RUNTIME_ID = 0;

    public static final String PISTOL_AMMO_ID = "skyisland:pistol_ammo";
    public static final String PISTOL_ID = "skyisland:pistol";

    /** 煤炭：煤炭矿石的掉落物（PRD 5.1）。MVP 第一种"方块掉非方块物品"。 */
    public static final String COAL_ID = "skyisland:coal";

    /** 弹药的堆叠上限（PRD 5.4.2）。 */
    public static final int AMMO_MAX_STACK = 128;

    /** 默认堆叠上限（PRD 5.1：方块统一 64）。 */
    public static final int DEFAULT_MAX_STACK = 64;

    private static final List<Item> BY_RUNTIME_ID = new ArrayList<>();
    private static final Map<String, Item> BY_STABLE_ID = new HashMap<>();

    private static boolean bootstrapped = false;

    static {
        bootstrap();
    }

    private ItemRegistry() {
    }

    // ============================================================ 注册

    private static void bootstrap() {
        // ---- 0：空槽。它不是"一种物品"，只是槽位为空，因此 maxStack = 0 ----
        Item empty = new Item(EMPTY_RUNTIME_ID, "skyisland:empty", ItemKind.EMPTY, null, 0, null);
        BY_RUNTIME_ID.add(empty);
        BY_STABLE_ID.put(empty.id(), empty);

        // ---- 方块物品：runtimeId 与方块严格对齐（air 对应空槽，跳过）----
        for (Block b : BlockRegistry.all()) {
            if (b.runtimeId() == BlockRegistry.AIR_RUNTIME_ID) {
                continue;
            }
            addBlockItem(b);
        }

        // ---- 非方块物品（M2）----
        // 煤炭：煤炭矿石的掉落物（PRD 5.1）。它证明"方块 ≠ 物品"这条等式已经破掉。
        register(COAL_ID, ItemKind.MATERIAL, DEFAULT_MAX_STACK, null);
        // 手枪弹：铁锭 ×1 + 火药 ×1 → 8 发（PRD 5.4.2，v0.3 已由铜锭改铁锭）
        register(PISTOL_AMMO_ID, ItemKind.AMMO, AMMO_MAX_STACK, null);
        // 手枪：伤害 8 / 弹匣 12 / 射速 4.0 发每秒 / 有效射程 32 格（PRD 5.4.1）
        // 换弹 1.2 秒（PRD 5.4.3）。
        // MVP 为开局装备、不可合成（用户裁决 A2）；配方 R14 归 Alpha。
        register(PISTOL_ID, ItemKind.GUN, 1, new GunSpec(8, 12, 4.0, 32, 1.2));

        bootstrapped = true;
        verify();
    }

    private static void addBlockItem(Block block) {
        if (block.runtimeId() != BY_RUNTIME_ID.size()) {
            throw new IllegalStateException(
                    "方块物品对齐失败：方块 " + block + " 的 runtimeId=" + block.runtimeId()
                            + "，但物品表当前长度为 " + BY_RUNTIME_ID.size()
                            + "。方块物品的 runtimeId 必须与方块 runtimeId 严格相等。");
        }
        if (BY_STABLE_ID.containsKey(block.id())) {
            throw new IllegalStateException("物品 stable ID 与已登记 ID 冲突: " + block.id());
        }
        Item item = new Item(block.runtimeId(), block.id(), ItemKind.BLOCK, block,
                DEFAULT_MAX_STACK, null);
        BY_RUNTIME_ID.add(item);
        BY_STABLE_ID.put(item.id(), item);
    }

    private static Item register(String stableId, ItemKind kind, int maxStack, GunSpec gun) {
        if (bootstrapped) {
            throw new IllegalStateException("注册表已冻结，不允许运行期追加物品: " + stableId);
        }
        if (BY_STABLE_ID.containsKey(stableId)) {
            throw new IllegalStateException("物品 stable ID 重复注册: " + stableId);
        }
        Item item = new Item(BY_RUNTIME_ID.size(), stableId, kind, null, maxStack, gun);
        BY_RUNTIME_ID.add(item);
        BY_STABLE_ID.put(stableId, item);
        return item;
    }

    /**
     * 校验两条不变式：
     * <ol>
     *   <li>每个物品的 runtimeId == 它在列表中的下标（无空洞、无错位）；</li>
     *   <li>方块物品与方块一一对应。</li>
     * </ol>
     */
    private static void verify() {
        for (int i = 0; i < BY_RUNTIME_ID.size(); i++) {
            Item it = BY_RUNTIME_ID.get(i);
            if (it.runtimeId() != i) {
                throw new IllegalStateException(
                        "物品 runtimeId 与注册下标不一致：index=" + i + " runtimeId=" + it.runtimeId()
                                + " id=" + it.id());
            }
            if (it.isBlock() && it.blockRuntimeId() != it.runtimeId()) {
                throw new IllegalStateException(
                        "方块物品的 runtimeId 未与方块对齐: item=" + it.id()
                                + " itemRuntimeId=" + it.runtimeId()
                                + " blockRuntimeId=" + it.blockRuntimeId());
            }
        }
    }

    // ============================================================ 查询

    public static Item empty() {
        return BY_RUNTIME_ID.get(EMPTY_RUNTIME_ID);
    }

    public static Item coal() {
        return byName(COAL_ID);
    }

    public static Item pistol() {
        return byName(PISTOL_ID);
    }

    public static Item pistolAmmo() {
        return byName(PISTOL_AMMO_ID);
    }

    /**
     * 按运行时 ID 查询。
     *
     * <p>与 {@link BlockRegistry#byRuntimeId(int)} 同口径：越界属于数据损坏，
     * 记录 WARNING 后返回空物品，让程序能继续跑但不掩盖问题。
     */
    public static Item byRuntimeId(int runtimeId) {
        if (runtimeId < 0 || runtimeId >= BY_RUNTIME_ID.size()) {
            Log.noteWarning("ItemRegistry",
                    "非法的物品 runtimeId=" + runtimeId + "（合法范围 0.."
                            + (BY_RUNTIME_ID.size() - 1) + "），本次按空物品处理。");
            return empty();
        }
        return BY_RUNTIME_ID.get(runtimeId);
    }

    /** 按 stable string ID 查询；不存在返回 {@code null}。 */
    public static Item byName(String stableId) {
        return BY_STABLE_ID.get(stableId);
    }

    /**
     * 按 stable string ID 查 runtimeId；未登记返回空槽 ID 并告警。
     *
     * <p>存档读取走这条路径：旧存档里的物品 ID 若在本版本已不存在，
     * 得到的是"空槽"而不是"指向另一个物品的错误数值"。
     */
    public static int runtimeIdOf(String stableId) {
        Item it = BY_STABLE_ID.get(stableId);
        if (it == null) {
            Log.noteWarning("ItemRegistry", "存档中出现未登记的物品 ID: " + stableId + "，按空槽处理。");
            return EMPTY_RUNTIME_ID;
        }
        return it.runtimeId();
    }

    /** 某物品的堆叠上限；空槽返回 0。 */
    public static int maxStackOf(int itemRuntimeId) {
        return byRuntimeId(itemRuntimeId).maxStack();
    }

    /** 已登记物品数量（含空槽）。 */
    public static int size() {
        return BY_RUNTIME_ID.size();
    }

    public static List<Item> all() {
        return Collections.unmodifiableList(BY_RUNTIME_ID);
    }
}

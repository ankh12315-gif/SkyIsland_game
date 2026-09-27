package com.skyisland.item;

import com.skyisland.world.block.Block;

/**
 * 物品定义（不可变）。M2 引入的第二套注册表（TECH_DESIGN §L.1）。
 *
 * <p><b>为什么 M2 必须分家：</b>M1 的 {@code ItemStack} 直接用 {@code blockRuntimeId} 当物品标识，
 * 因为当时"挖到的方块 = 可放置的物品"这一等式成立，多一套注册表无法被验证。
 * M2 引入手枪与子弹后等式立刻破掉：{@code skyisland:pistol} 没有对应的方块，
 * 它不能被放置、不能堆叠、还有自己的伤害与弹匣。
 *
 * <p><b>兼容策略 —— 方块物品的 item runtimeId 与 block runtimeId 严格对齐：</b>
 * {@link ItemRegistry} 在初始化时按 {@link com.skyisland.world.block.BlockRegistry} 的注册顺序
 * 逐个生成方块物品，并断言 {@code block.runtimeId() == item.runtimeId()}。
 * 于是对方块物品而言"物品 ID"与"方块 ID"是同一个数值，
 * M1 已经写好的背包 / 存档 / 测试不需要任何改动就能继续工作。
 * 这条不变式由 {@link ItemRegistry} 在构建期校验，不靠记忆维持。
 *
 * <p>非方块物品的 runtimeId 从 {@code BlockRegistry.size()} 起顺延分配，
 * 因此<b>新增方块会让非方块物品的 runtimeId 整体位移</b> ——
 * 这正是 runtimeId 禁止写入存档的原因（PRD 12.3），存档一律用 {@link #id()}。
 */
public final class Item {

    private final int runtimeId;
    private final String id;
    private final ItemKind kind;

    /** 方块物品持有对应方块；其余种类为 {@code null}。 */
    private final Block block;

    /** 单格堆叠上限（方块 64 / 弹药 128 / 枪械 1）。 */
    private final int maxStack;

    /** 枪械规格；非枪械为 {@code null}。 */
    private final GunSpec gun;

    /**
     * 枪械表现规格；非枪械为 {@code null}（WEAPON-DOC-001 v2 §4）。
     *
     * <p>与 {@link #gun} 同级但在语义上正交：{@code gun} 是"怎么打人"（战斗规则），
     * 本字段是"长什么样、听起来什么样、火光从哪冒"（表现资源）。它只能是<b>查表键</b>，
     * 见 {@link GunPresentationSpec} 的类注释。
     */
    private final GunPresentationSpec presentation;

    Item(int runtimeId, String id, ItemKind kind, Block block, int maxStack,
         GunSpec gun, GunPresentationSpec presentation) {
        this.runtimeId = runtimeId;
        this.id = id;
        this.kind = kind;
        this.block = block;
        this.maxStack = maxStack;
        this.gun = gun;
        // 不变式：表现规格只挂在枪上。给它挂到方块/弹药/材料上会让"表现层按枪查 presentation"
        // 这条接缝失去意义 —— 那类物品的表现由 kind()/block() 决定，不经过本字段。
        if (presentation != null && gun == null) {
            throw new IllegalArgumentException(
                    "非枪械物品不得携带 GunPresentationSpec: " + id);
        }
        this.presentation = presentation;
    }

    // ------------------------------------------------------------ 标识

    public int runtimeId() {
        return runtimeId;
    }

    /** stable string ID（存档口径，例如 {@code skyisland:pistol}）。 */
    public String id() {
        return id;
    }

    public ItemKind kind() {
        return kind;
    }

    public boolean isEmpty() {
        return kind == ItemKind.EMPTY;
    }

    // ------------------------------------------------------------ 方块物品

    /** 对应方块；仅 {@link ItemKind#BLOCK} 非 {@code null}。 */
    public Block block() {
        return block;
    }

    public boolean isBlock() {
        return block != null;
    }

    /**
     * 对应方块的 runtimeId；非方块物品返回 {@code -1}。
     *
     * <p>返回 {@code -1} 而不是 {@code 0}：{@code 0} 是空气，是"合法的方块 ID"，
     * 用它表示"这不是方块"会让调用方把枪械误当成空气方块处理。
     */
    public int blockRuntimeId() {
        return block == null ? -1 : block.runtimeId();
    }

    // ------------------------------------------------------------ 堆叠

    public int maxStack() {
        return maxStack;
    }

    // ------------------------------------------------------------ 枪械

    /** 枪械规格；非枪械为 {@code null}。 */
    public GunSpec gun() {
        return gun;
    }

    public boolean isGun() {
        return gun != null;
    }

    /**
     * 枪械表现规格；非枪械为 {@code null}（WEAPON-DOC-001 v2 §4）。
     *
     * <p>表现层（第一人称模型 / 图标 / 枪口锚点 / 音频）通过它把"战斗逻辑"与
     * "资源查表"解耦：逻辑层不认识几何与音效，表现层不认识伤害与弹匣。见
     * {@link GunPresentationSpec}。枪械的 {@code presentation()} 由注册表在构建期
     * 保证非 {@code null}（见 {@link ItemRegistry}）。
     */
    public GunPresentationSpec presentation() {
        return presentation;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Item other && other.runtimeId == runtimeId;
    }

    @Override
    public int hashCode() {
        return runtimeId;
    }

    @Override
    public String toString() {
        return id + "#" + runtimeId + "(" + kind + ")";
    }
}

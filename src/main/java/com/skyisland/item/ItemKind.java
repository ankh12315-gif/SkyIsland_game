package com.skyisland.item;

/**
 * 物品种类（M2 引入，TECH_DESIGN §L.1 的"两套注册表"落地）。
 *
 * <p><b>为什么要有 kind 而不是只判断"有没有 block 字段"：</b>
 * 堆叠上限、能否放置、能否被枪械消耗，这三件事是<b>按种类</b>而不是按实例决定的。
 * 把判断散进 {@code (item.block() != null)} 这类条件里，等 M4 出现"可放置的方块物品 +
 * 不可放置的方块物品"时就会失准。种类是一个显式的、可穷举的标签。
 */
public enum ItemKind {

    /** 空槽（runtimeId = 0）。不是"一种物品"，只是槽位为空。 */
    EMPTY,

    /** 方块物品：持有 {@link com.skyisland.world.block.Block}，可放置。 */
    BLOCK,

    /** 枪械：不可堆叠，持有 {@link GunSpec}。 */
    GUN,

    /** 弹药：堆叠上限 128（PRD 5.4.2）。 */
    AMMO,

    /** 合成材料（铁锭 / 火药 / 煤炭等）。M2 只登记定义，合成归 M3。 */
    MATERIAL
}

package com.skyisland.render.viewmodel;

import com.skyisland.item.Item;

/**
 * 第一人称手持物的三种形态。
 *
 * <p><b>为什么只有三种而不是按物品一一对应：</b>
 * M2.1 要做的是"手里拿着东西能被看见"，而不是一套武器模型库。
 * 物品种类（方块 / 枪械 / 弹药 / 材料）映射到三种<b>几何形态</b>：
 * <ul>
 *   <li>{@link #GUN}——枪械，有枪管 / 套筒 / 握把 / 准星的剪影；</li>
 *   <li>{@link #BLOCK}——一个方块（或一叠弹药 / 一份材料）握在手里；</li>
 *   <li>{@link #EMPTY}——空手，只显示一只简化的右手。</li>
 * </ul>
 * 弹药与材料走 {@link #BLOCK}：它们不是方块，但"手里攥着一坨东西"的剪影是一样的，
 * 区别只在颜色（颜色沿用 {@code HudRenderer#iconColor} 的表，
 * 于是快捷栏图标与手里的东西是同一个色，玩家能认出来是同一个物品）。
 *
 * <p>等到 Alpha 有了真正的资源管线，这里应当换成"按物品 ID 查模型"，
 * 而不是继续往枚举里加分支 —— 枚举的意义是把"形态"从"物品"里解耦出来。
 */
public enum ViewmodelKind {

    /** 空手（只画一只简化的右手）。 */
    EMPTY,
    /** 枪械。 */
    GUN,
    /** 方块 / 弹药 / 材料：握在手里的一坨。 */
    BLOCK;

    /**
     * 按物品判定形态。
     *
     * @param item 可以为 null 或 {@link Item#isEmpty()}；此时是 {@link #EMPTY}
     */
    public static ViewmodelKind of(Item item) {
        if (item == null || item.isEmpty()) {
            return EMPTY;
        }
        if (item.isGun()) {
            return GUN;
        }
        return BLOCK;
    }
}

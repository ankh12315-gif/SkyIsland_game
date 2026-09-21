package com.skyisland.world.block;

/**
 * 方块的渲染分类（TECH_DESIGN §G.2 网格分裂依据）。
 *
 * <p>M1 只需要区分"是否参与渲染"与"是否走透明 pass"两件事，
 * 因此这里不引入更细的材质分组——那属于 M3 的贴图阶段。
 */
public enum RenderType {

    /** 不渲染（空气等）。 */
    INVISIBLE,

    /** 不透明：写入深度，先渲染。 */
    OPAQUE,

    /** 透明：后渲染、关闭深度写入、开启混合。 */
    TRANSPARENT
}

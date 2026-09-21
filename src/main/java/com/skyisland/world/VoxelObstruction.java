package com.skyisland.world;

/**
 * "这个方块位置会不会挡住玩家"的判定接口。
 *
 * <p>{@code World} 在放置方块时必须检查"会不会把方块放进玩家身体里"（PRD 5.2 / TECH_DESIGN §H.6），
 * 但世界层不应该反过来依赖玩家或物理模块（依赖方向 §B.2）。
 * 因此这里用一个函数式接口把判定**注入进来**：由玩家层提供"我的碰撞箱是否与这个方块重叠"，
 * 世界层只负责"问一次、然后决定放不放"。
 *
 * <p>这样放置规则<u>仍然只有一个执行点</u>（{@code World.placeBlock}），
 * 不会被绕过，但依赖方向保持单向。
 */
@FunctionalInterface
public interface VoxelObstruction {

    /** @return true 表示该方块位置与某个实体（通常是玩家）的碰撞箱重叠，不允许放置 */
    boolean intersectsBlock(int bx, int by, int bz);
}

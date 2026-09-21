package com.skyisland.combat;

import com.skyisland.entity.Entity;
import com.skyisland.physics.DdaRaycaster;
import com.skyisland.physics.RaycastHit;
import com.skyisland.world.World;
import org.joml.Vector3d;

import java.util.List;

/**
 * Hitscan 命中判定（PRD 5.4.3「命中判定（v0.3 修订）」）。
 *
 * <p><b>规格原文：</b>「同一射线同时计算方块命中与实体命中，取沿射线距离最近的合法碰撞结果；
 * 不允许隔着墙命中怪物；最近命中为方块时记为方块命中，仅当最近命中为实体时才结算伤害。」
 *
 * <p><b>这一条是 M2 通过标准的第 4 条，也是最容易被实现错的一条。</b>
 * 直觉写法是"先射实体，命中了就结算；没命中再射方块"，
 * 那样玩家可以隔着一堵墙把墙后的怪物打死 —— 而且画面看上去完全正常，
 * 因为准星确实指着怪物的方向。正确的做法是<u>两条射线都算，然后比距离</u>。
 *
 * <p><b>平局取方块：</b>当实体距离与方块距离完全相等时判为方块命中。
 * 这是刻意的保守选择 —— 平局几乎只出现在"怪物紧贴墙面"的情形，
 * 此时若判为实体命中，就会出现"子弹穿过了它身后的那格墙"的错误观感。
 */
public final class Hitscan {

    /**
     * 命中结果。
     *
     * @param entity    命中的实体；未命中实体为 {@code null}
     * @param blockHit  命中的方块；未命中方块为 {@code null}
     * @param distance  到最近合法碰撞的距离（无命中时为 {@link Double#POSITIVE_INFINITY}）
     * @param hitEntity 最近命中是否为实体（为 true 时才结算伤害）
     */
    public record Result(Entity entity, RaycastHit blockHit, double distance, boolean hitEntity) {

        public boolean hitAnything() {
            return hitEntity || blockHit != null;
        }
    }

    /** 什么都没打中。 */
    public static final Result MISS = new Result(null, null, Double.POSITIVE_INFINITY, false);

    private Hitscan() {
    }

    /**
     * 解析一条射线。
     *
     * @param origin     射线起点（通常是眼睛位置）
     * @param direction  射线方向（不必归一化，内部会归一化）
     * @param range      最大射程（格）
     * @param candidates 参与判定的实体（通常是 {@code EntityManager.all()}）
     */
    public static Result resolve(World world, Vector3d origin, Vector3d direction,
                                 double range, List<Entity> candidates) {
        if (range <= 0 || direction.lengthSquared() < 1e-18) {
            return MISS;
        }
        // 归一化后 t 才是"以格计的距离"；RayBox 依赖这一前提
        Vector3d dir = new Vector3d(direction).normalize();

        RaycastHit blockHit = DdaRaycaster.castSolid(world, origin, dir, range);
        double blockDistance = blockHit == null ? Double.POSITIVE_INFINITY : blockHit.distance();

        Entity best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        if (candidates != null) {
            for (Entity e : candidates) {
                if (!e.isAlive()) {
                    continue;
                }
                double t = RayBox.intersect(origin, dir, e.boundingBox());
                if (t >= 0 && t <= range && t < bestDistance) {
                    bestDistance = t;
                    best = e;
                }
            }
        }

        // 取最近者；平局取方块（见类注释）
        if (best != null && bestDistance < blockDistance) {
            return new Result(best, null, bestDistance, true);
        }
        if (blockHit != null) {
            return new Result(null, blockHit, blockDistance, false);
        }
        return MISS;
    }
}

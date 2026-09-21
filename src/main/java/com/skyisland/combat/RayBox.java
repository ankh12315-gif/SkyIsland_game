package com.skyisland.combat;

import com.skyisland.physics.AABB;
import org.joml.Vector3d;

/**
 * 射线与轴对齐包围盒求交（slab 方法）。M2 为 Hitscan 引入。
 *
 * <p><b>为什么不用"把实体当方块逐个体素检查"：</b>
 * 实体是 0.6 × 1.8 × 0.6 的连续盒，而体素是整数格。
 * 若把实体近似成"它覆盖的那几格体素"，站在方块边缘的怪物会产生
 * "明明准星在它身上却打不中"或反之的偏差 —— 而且偏差随位置漂移，极难复现。
 * slab 方法直接对连续盒求解，精度只受浮点限制。
 *
 * <p><b>方向必须是单位向量</b>：返回值是沿射线的参数 {@code t}，
 * 只有当 {@code |dir| = 1} 时 {@code t} 才等于"以格为单位的距离"。
 * Hitscan 会在调用前归一化，这里不做二次归一化以避免重复开销。
 */
public final class RayBox {

    private static final double EPS = 1e-12;

    private RayBox() {
    }

    /**
     * @return 从起点沿射线到包围盒最近交点的距离；射线与盒不相交（或在身后）返回 {@code -1}
     */
    public static double intersect(Vector3d origin, Vector3d direction, AABB box) {
        double tmin = 0.0;
        double tmax = Double.POSITIVE_INFINITY;

        // ---- X slab ----
        if (Math.abs(direction.x) < EPS) {
            if (origin.x < box.minX() || origin.x > box.maxX()) {
                return -1;
            }
        } else {
            double t1 = (box.minX() - origin.x) / direction.x;
            double t2 = (box.maxX() - origin.x) / direction.x;
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
            if (tmin > tmax) {
                return -1;
            }
        }

        // ---- Y slab ----
        if (Math.abs(direction.y) < EPS) {
            if (origin.y < box.minY() || origin.y > box.maxY()) {
                return -1;
            }
        } else {
            double t1 = (box.minY() - origin.y) / direction.y;
            double t2 = (box.maxY() - origin.y) / direction.y;
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
            if (tmin > tmax) {
                return -1;
            }
        }

        // ---- Z slab ----
        if (Math.abs(direction.z) < EPS) {
            if (origin.z < box.minZ() || origin.z > box.maxZ()) {
                return -1;
            }
        } else {
            double t1 = (box.minZ() - origin.z) / direction.z;
            double t2 = (box.maxZ() - origin.z) / direction.z;
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
            if (tmin > tmax) {
                return -1;
            }
        }

        return tmin;
    }
}

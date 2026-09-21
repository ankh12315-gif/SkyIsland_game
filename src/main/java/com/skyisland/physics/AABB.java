package com.skyisland.physics;

/**
 * 轴对齐包围盒（TECH_DESIGN §I.2）。
 *
 * <p><b>接口刻意只做三件事</b>：构造、与方块求交、平移。碰撞求解（谁被推开、推到哪里）
 * 不在本类里 —— 那是 {@code PlayerPhysics} 的职责。把"几何"和"决策"分开，
 * 是为了让碰撞逻辑可以被单元测试直接驱动，而不必造一个玩家出来。
 *
 * <p><b>为什么不用 epsilon 处理"刚好贴着"：</b>
 * 判定写成严格不等式 {@code maxA > minB && minA < maxB} 之后，
 * "玩家脚底 y 正好等于方块顶面 y"这一情形天然不算相交
 * （{@code 64.0 < 64.0} 为假）。而浮点漂移导致的 {@code 63.99999} 会被判为相交，
 * 于是下一次求解把它推回接触面 —— 这正是我们想要的收敛行为。
 * 引入 epsilon 反而会制造"永远差一点点"的抖动。
 */
public record AABB(double minX, double minY, double minZ,
                   double maxX, double maxY, double maxZ) {

    /** 由"脚底中心点 + 半宽 + 全高"构造。玩家位置语义见 TECH_DESIGN §I.4。 */
    public static AABB ofFeetCenter(double x, double feetY, double z, double halfWidth, double height) {
        return new AABB(x - halfWidth, feetY, z - halfWidth,
                x + halfWidth, feetY + height, z + halfWidth);
    }

    public double centerX() {
        return (minX + maxX) * 0.5;
    }

    public double centerY() {
        return (minY + maxY) * 0.5;
    }

    public double centerZ() {
        return (minZ + maxZ) * 0.5;
    }

    public double widthX() {
        return maxX - minX;
    }

    public double heightY() {
        return maxY - minY;
    }

    /** 平移。 */
    public AABB moved(double dx, double dy, double dz) {
        return new AABB(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** 在 +Y 方向外扩（用于"脚下是否有地面"的探测）。 */
    public AABB expandY(double amount) {
        return new AABB(minX, minY - amount, minZ, maxX, maxY, maxZ);
    }

    /** 与某个方块（占据 [bx,bx+1]×[by,by+1]×[bz,bz+1]）是否相交。 */
    public boolean intersectsBlock(int bx, int by, int bz) {
        return maxX > bx && minX < bx + 1
                && maxY > by && minY < by + 1
                && maxZ > bz && minZ < bz + 1;
    }

    @Override
    public String toString() {
        return String.format("AABB[%.3f..%.3f, %.3f..%.3f, %.3f..%.3f]",
                minX, maxX, minY, maxY, minZ, maxZ);
    }
}

package com.skyisland.entity;

import com.skyisland.physics.AABB;
import com.skyisland.world.World;
import org.joml.Vector3d;

/**
 * 实体物理：重力 + 分轴体素碰撞（TECH_DESIGN §I.1 / §I.2 的同一套口径）。
 *
 * <p><b>为什么与 {@code Player} 的碰撞求解分开实现而不是抽公共类：</b>
 * {@code Player} 的碰撞是 M1 用 27 项自测与 566 个单元用例覆盖过的路径，
 * 把它重构进一个共享工具、再让 M2 的新代码走同一条路，
 * 等于把"M2 的新风险"注入"M1 已验收的代码"。
 * 本类因此<u>照抄</u>分轴与子步进的语义，但独立存在。
 * 代价是逻辑重复（一条已登记的技术债），换来的是 M1 的验收结果在 M2 期间保持有效。
 *
 * <p><b>两条必须保留的语义：</b>
 * <ul>
 *   <li><b>分轴</b>（Y → X → Z）：一次只动一个轴，撞到就停在该轴。
 *       于是"贴着墙往前挪"自然出现，不需要处理"该往哪个方向推"的歧义。</li>
 *   <li><b>子步进</b>：单轴单次推进不超过 0.4 格。终端速度 60 格/秒下一帧位移可达 1.0 格，
 *       不分子步会直接穿过一格厚的墙。这是"平时看着没事、偶发穿透一次"的典型来源。</li>
 * </ul>
 */
public final class EntityPhysics {

    /** 单轴单次推进上限（格）。 */
    public static final double STEP_LIMIT = 0.4;

    /** 重力加速度（格/秒²），与 TECH_DESIGN §I.1 一致。 */
    public static final double GRAVITY = 32.0;

    /** 向下终端速度（格/秒）。 */
    public static final double TERMINAL_VELOCITY = 60.0;

    private EntityPhysics() {
    }

    /** 一次移动求解的结果。 */
    public record MoveResult(boolean onGround, boolean blockedHorizontally) {
    }

    /**
     * 包围盒是否与世界中的实体方块相交。
     *
     * <p>能否相交由 {@link AABB#intersectsBlock(int, int, int)} 判定 ——
     * 它用的是严格不等式，所以"脚底正好落在方块顶面"不算相交，
     * 而浮点漂移出来的 63.99999 会被判相交并在下一次求解里被推回接触面。
     */
    public static boolean collides(World world, AABB box) {
        int x0 = (int) Math.floor(box.minX());
        int x1 = (int) Math.floor(box.maxX());
        int y0 = (int) Math.floor(box.minY());
        int y1 = (int) Math.floor(box.maxY());
        int z0 = (int) Math.floor(box.minZ());
        int z1 = (int) Math.floor(box.maxZ());
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (world.collidesWith(box.minX(), box.minY(), box.minZ(),
                            box.maxX(), box.maxY(), box.maxZ(), x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 位置是否可站立（脚底所在格有碰撞，且身体所在格无碰撞）。 */
    public static boolean canStandAt(World world, double x, double feetY, double z,
                                     double halfWidth, double height) {
        AABB body = AABB.ofFeetCenter(x, feetY, z, halfWidth, height);
        if (collides(world, body)) {
            return false;
        }
        // 脚下 1e-3 探一层，避免"正好贴面"时判为悬空
        AABB below = AABB.ofFeetCenter(x, feetY - 1e-3, z, halfWidth, height);
        return collides(world, below);
    }

    /**
     * 分轴移动求解，就地修改 {@code position}（脚底中心语义）。
     *
     * @param dx 本步期望的 X 位移
     * @param dy 本步期望的 Y 位移
     * @param dz 本步期望的 Z 位移
     */
    public static MoveResult move(World world, Vector3d position,
                                  double dx, double dy, double dz,
                                  double halfWidth, double height) {
        boolean onGround = false;

        // ---- Y：向下撞到 → 站在地上 ----
        double remainY = dy;
        while (remainY != 0) {
            double step = clampStep(remainY);
            AABB next = AABB.ofFeetCenter(position.x, position.y + step, position.z, halfWidth, height);
            if (collides(world, next)) {
                if (step < 0) {
                    onGround = true;
                }
                break;
            }
            position.y += step;
            remainY -= step;
        }

        // ---- X ----
        boolean blockedX = false;
        double remainX = dx;
        while (remainX != 0) {
            double step = clampStep(remainX);
            AABB next = AABB.ofFeetCenter(position.x + step, position.y, position.z, halfWidth, height);
            if (collides(world, next)) {
                blockedX = true;
                break;
            }
            position.x += step;
            remainX -= step;
        }

        // ---- Z ----
        boolean blockedZ = false;
        double remainZ = dz;
        while (remainZ != 0) {
            double step = clampStep(remainZ);
            AABB next = AABB.ofFeetCenter(position.x, position.y, position.z + step, halfWidth, height);
            if (collides(world, next)) {
                blockedZ = true;
                break;
            }
            position.z += step;
            remainZ -= step;
        }

        return new MoveResult(onGround, blockedX || blockedZ);
    }

    private static double clampStep(double value) {
        if (value > STEP_LIMIT) {
            return STEP_LIMIT;
        }
        if (value < -STEP_LIMIT) {
            return -STEP_LIMIT;
        }
        return value;
    }
}

package com.skyisland.physics;

import com.skyisland.world.World;
import com.skyisland.world.block.Block;
import org.joml.Vector3dc;

import java.util.function.Predicate;

/**
 * 3D 体素 DDA（Amanatides &amp; Woo）—— 瞄准、挖掘与放置共用的唯一射线入口
 * （TECH_DESIGN §J）。
 *
 * <p><b>为什么不用"沿射线每 0.01 步采样"：</b>采样法要么漏掉薄结构（步长太大），
 * 要么代价与距离成正比（步长太小）。DDA 只在<u>跨越方块边界</u>时推进，
 * 步数上界 ≈ 射线穿过的方块数，与精度无关。
 *
 * <p><b>起点在方块内部的特殊情形必须被显式表达：</b>
 * 此时"命中面"不存在，法线没有意义。若把它当作普通命中返回，
 * 放置逻辑会算出 {@code adjacent == 命中方块本身}，于是"往自己身上放"，
 * 最终被世界拒绝但原因看起来莫名其妙。因此本类返回
 * {@link RaycastHit#insideOriginBlock()} = true 并且不推进任何格子。
 */
public final class DdaRaycaster {

    /** 步数上界：M1 的最大 reach 为 5，穿过 5 个方块最多几十步；256 是安全余量。 */
    public static final int MAX_STEPS = 256;

    /** 默认命中判定：任何非空气方块。 */
    public static final Predicate<Block> NOT_AIR = b -> !b.isAir();

    private DdaRaycaster() {
    }

    /**
     * 投射射线。
     *
     * @param origin      起点（世界坐标，通常为相机位置，但本类<u>不</u>关心它是不是相机）
     * @param direction   方向，无需预先归一化（内部会归一化）
     * @param maxDistance 最大距离（世界单位）；游戏内 reach = 5.0
     * @param hitTest     命中判定；{@code null} 等价于 {@link #NOT_AIR}
     * @return 命中结果；未命中返回 {@code null}
     */
    public static RaycastHit cast(World world, Vector3dc origin, Vector3dc direction,
                                  double maxDistance, Predicate<Block> hitTest) {
        if (world == null || origin == null || direction == null) {
            return null;
        }
        double dirLength = Math.sqrt(direction.x() * direction.x()
                + direction.y() * direction.y()
                + direction.z() * direction.z());
        if (dirLength < 1e-9 || maxDistance <= 0) {
            return null;
        }
        final double dx = direction.x() / dirLength;
        final double dy = direction.y() / dirLength;
        final double dz = direction.z() / dirLength;
        final Predicate<Block> test = hitTest == null ? NOT_AIR : hitTest;

        final double ox = origin.x();
        final double oy = origin.y();
        final double oz = origin.z();

        int bx = (int) Math.floor(ox);
        int by = (int) Math.floor(oy);
        int bz = (int) Math.floor(oz);

        final int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        final int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        final int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        final double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        final double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        final double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

        // 走到下一个格子边界所需的参数距离。
        // 注意 stepX < 0 时 (bx - ox) 必为负、dx 亦为负，比值仍为正 —— 这正是必须用
        // floor 与带符号 delta 一起推导的原因（用 (int) 截断会在负坐标整体错位）。
        double tMaxX = stepX > 0 ? (bx + 1 - ox) / dx
                : (stepX < 0 ? (bx - ox) / dx : Double.POSITIVE_INFINITY);
        double tMaxY = stepY > 0 ? (by + 1 - oy) / dy
                : (stepY < 0 ? (by - oy) / dy : Double.POSITIVE_INFINITY);
        double tMaxZ = stepZ > 0 ? (bz + 1 - oz) / dz
                : (stepZ < 0 ? (bz - oz) / dz : Double.POSITIVE_INFINITY);

        int normalX = 0;
        int normalY = 0;
        int normalZ = 0;
        double t = 0;
        boolean first = true;

        for (int step = 0; step < MAX_STEPS; step++) {
            if (!first && t > maxDistance) {
                return null;
            }
            Block block = world.blockAt(bx, by, bz);
            if (test.test(block)) {
                boolean inside = first;
                return new RaycastHit(bx, by, bz, block.runtimeId(),
                        inside ? 0 : normalX, inside ? 0 : normalY, inside ? 0 : normalZ,
                        t,
                        bx + normalX, by + normalY, bz + normalZ,
                        inside);
            }
            first = false;

            // 三个候选边界里选最近的那个跨越
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                bx += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
                normalX = -stepX;
                normalY = 0;
                normalZ = 0;
            } else if (tMaxY < tMaxZ) {
                by += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
                normalX = 0;
                normalY = -stepY;
                normalZ = 0;
            } else {
                bz += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                normalX = 0;
                normalY = 0;
                normalZ = -stepZ;
            }
            if (t > maxDistance) {
                return null;
            }
        }
        return null;
    }

    /** 便利方法：命中任意非空气方块。 */
    public static RaycastHit castSolid(World world, Vector3dc origin, Vector3dc direction,
                                       double maxDistance) {
        return cast(world, origin, direction, maxDistance, NOT_AIR);
    }

    /** 便利方法：只命中"可破坏方块"（资源核心等系统方块会被穿过，便于自测断言）。 */
    public static RaycastHit castBreakable(World world, Vector3dc origin, Vector3dc direction,
                                           double maxDistance) {
        return cast(world, origin, direction, maxDistance, b -> !b.isAir() && b.isBreakable());
    }

    /**
     * 便利方法：命中第一个<u>空气</u>格并返回它。
     *
     * <p>这是给自测用的独立通道：断言"这里现在确实是空的"不必依赖"看得见"，
     * 也不必把空气当成固体去射（那样返回的永远是起点格）。
     */
    public static RaycastHit castFirstAir(World world, Vector3dc origin,
                                          Vector3dc direction, double maxDistance) {
        return cast(world, origin, direction, maxDistance, Block::isAir);
    }
}

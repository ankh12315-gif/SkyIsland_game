package com.skyisland.physics;

/**
 * 体素 DDA 的命中结果（TECH_DESIGN §J.1）。
 *
 * <p>字段一次给全，因为挖掘与放置要的是不同的东西，而**再射一次**会引入
 * "两次射线结果不一致"这种极难复现的 bug：
 * <ul>
 *   <li>挖掘需要 {@code (blockX, blockY, blockZ)}；</li>
 *   <li>放置需要 {@link #adjacentX()} 等 —— 命中方块的<u>外侧</u>那一格；</li>
 *   <li>表现层需要 {@link #faceNormalX()} 知道该贴哪个面。</li>
 * </ul>
 *
 * @param blockX       命中方块坐标
 * @param blockY       命中方块坐标
 * @param blockZ       命中方块坐标
 * @param blockRuntimeId 命中方块的运行时 ID（避免调用方再查一次世界）
 * @param faceNormalX  命中面的法线（指向射线来向）；{@link #insideOriginBlock()} 为 true 时全 0
 * @param faceNormalY  同上
 * @param faceNormalZ  同上
 * @param distance     从起点到命中面的距离（世界单位，即"像素/方块"数）
 * @param adjacentX    放置位置 = 命中方块 + 法线
 * @param adjacentY    同上
 * @param adjacentZ    同上
 * @param insideOriginBlock 射线起点本身就位于方块内部（此时没有合法的放置面）
 */
public record RaycastHit(int blockX, int blockY, int blockZ,
                         int blockRuntimeId,
                         int faceNormalX, int faceNormalY, int faceNormalZ,
                         double distance,
                         int adjacentX, int adjacentY, int adjacentZ,
                         boolean insideOriginBlock) {

    /**
     * 是否有可用的面。
     *
     * <p>把"起点在方块内部"单独标出来而不是靠法线全 0 判断，是因为调用方很容易
     * 忘记检查法线：只要用 {@code adjacent} 就会得到一个和命中方块相同的坐标，
     * 于是放置请求变成"往方块自己身上放"，最终被拒绝但原因莫名其妙。
     */
    public boolean hasFace() {
        return !insideOriginBlock;
    }

    /** 面的可读名称（日志与 HUD 用）。 */
    public String faceName() {
        if (insideOriginBlock) {
            return "INSIDE";
        }
        if (faceNormalY == 1) {
            return "UP";
        }
        if (faceNormalY == -1) {
            return "DOWN";
        }
        if (faceNormalX == 1) {
            return "+X";
        }
        if (faceNormalX == -1) {
            return "-X";
        }
        if (faceNormalZ == 1) {
            return "+Z";
        }
        return "-Z";
    }

    public int[] blockPos() {
        return new int[]{blockX, blockY, blockZ};
    }

    public int[] adjacentPos() {
        return new int[]{adjacentX, adjacentY, adjacentZ};
    }

    @Override
    public String toString() {
        return String.format("hit(%d,%d,%d) face=%s dist=%.2f adjacent=(%d,%d,%d)%s",
                blockX, blockY, blockZ, faceName(), distance,
                adjacentX, adjacentY, adjacentZ, insideOriginBlock ? " [起点在方块内]" : "");
    }
}

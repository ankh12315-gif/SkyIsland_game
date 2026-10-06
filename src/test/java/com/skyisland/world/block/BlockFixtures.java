package com.skyisland.world.block;

/**
 * <b>测试专用</b>的方块工厂 —— 提供 PRD §3.2.2（小麦）与§3.2.5（台阶）规格的方块，
 * 但<b>不把它们登记进 {@link BlockRegistry}</b>。
 *
 * <h2>为什么不登记</h2>
 * 登记是 S5 的职责（PRD §8），而 {@code BlockRegistry} 一旦 bootstrap 就冻结，
 * 且 {@code BlockRegistryTest} 对 {@code size()} 与 {@code playerBlockCount()} 有硬编码断言。
 * 在S1 期间登记测试方块会让那些断言变红，等于<b>把后续步骤的账先欠下</b>。
 *
 * <h2>为什么需要它</h2>
 * 异形网格与碰撞的判据是"顶点数/碰撞盒形状"，这些都必须在<b>真实的主循环</b>上验证
 * （只测辅助类无法排除"主循环压根没调用它"的死接线）。
 * 而主循环读的是 {@code ChunkMesher.build(..., IntFunction<Block>)} 里的解析器 ——
 * 注入本类产物即可端到端跑通，<b>且对生产注册表零污染</b>。
 *
 * <h2>runtimeId 的取法</h2>
 * 用 {@link #TEST_RUNTIME_ID_BASE} 起的一段<b>远离现有 0..16</b> 的高位 ID：
 * 若测试世界里的测试方块 ID 与真实方块 ID 撞了，
 * 区块里就会<b>同时</b>出现两种方块，断言会以一种极难定位的方式失败。
 */
public final class BlockFixtures {

    /**
     * 测试方块的 runtimeId 起点。
     *
     * <p>现有注册表占 0..16（17 种，见 {@code BlockRegistryTest}）。
     * 取 <b>1000</b> 起，与之相距甚远，撞车时肉眼即可发现。
     */
    public static final int TEST_RUNTIME_ID_BASE = 1000;

    private BlockFixtures() {
    }

    /**
     * 小麦（PRD §3.2.2）：作物方块，<b>solid=false / collision=false</b>，
     * RenderType=<b>TRANSPARENT</b>，形态=<b>十字交叉面</b>。
     *
     * <p>它是本轮唯一牵动"非满方块"的作物，也是 PRD §7 R1 的核心用例。
     */
    public static Block wheat(int runtimeId) {
        return new Block(runtimeId, "skyisland:wheat",
                false,     // solid=false（作物不是实体方块）
                true,      // transparent
                true,      // breakable
                true,      // placeable
                false,     // collision=false —— PRD 明确要求作物不阻挡通行
                0.0f, 0, RenderType.TRANSPARENT,
                0xD8 / 255f, 0xB2 / 255f, 0x4C / 255f,
                "skyisland:wheat", 1,
                BlockShape.CROSS);
    }

    /**
     * 台阶（PRD §3.2.5）：<b>半高</b>方块，solid/collision 均为 true，
     * 形态=<b>下半格台阶</b>。
     *
     * <p>PRD §3.5 已裁定本轮只做"下半形态"，因此这里不造上半形态。
     */
    public static Block slab(int runtimeId) {
        return new Block(runtimeId, "skyisland:slab",
                true,      // solid
                false,     // transparent
                true,      // breakable
                true,      // placeable
                true,      // collision=true —— 但只有下半格参与碰撞
                2.0f, 0, RenderType.OPAQUE,
                0x9A / 255f, 0x9A / 255f, 0x92 / 255f,
                "skyisland:slab", 1,
                BlockShape.SLAB_BOTTOM);
    }

    /**
     * 一个"半高但<b>没有</b>碰撞体"的方块 —— 用于验证
     * "形态对玩法开关有否决权"这条不变式（见 {@link Block#blocksMovement()}）。
     *
     * <p>如果 {@code blocksMovement()} 直接返回 {@code collision} 字段，
     * 这个方块就会挡住玩家 —— 那正是"看不见的墙"的一个变种。
     */
    public static Block crossWithCollisionFlagOn(int runtimeId) {
        return new Block(runtimeId, "skyisland:test_cross_collides",
                true, false, true, true,
                true,      // 故意把 collision 配成 true
                0.0f, 0, RenderType.TRANSPARENT,
                1f, 1f, 1f,
                null, 0,
                BlockShape.CROSS);
    }
}
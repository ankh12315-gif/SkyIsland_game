package com.skyisland.world.block;

/**
 * <b>测试专用</b>的方块工厂 —— 提供 PRD §3.2.2（小麦）与§3.2.5（台阶）规格的方块。
 *
 * <h2>★ 更新（2026-10-07，M4-S5 落地后）</h2>
 * <p>S5 已在 {@link BlockRegistry} 里<b>正式登记</b>了小麦与台阶
 * （见 {@code BlockRegistryS5Test}）。本类<b>仍然保留</b>，但用途已变：
 * <ul>
 *   <li><b>不再是</b> wheat / slab 的规格来源 —— 那两项现在由 {@code BlockRegistry} 断言；</li>
 *   <li>它现在只提供<b>真实注册表无法表达的东西</b>：
 *       {@link #crossWithCollisionFlagOn} 那种"故意把 collision 配成 true 的十字面方块"。
 *       这在生产注册表里<b>不可能合法存在</b>，而它正是验证
 *       「形态对玩法开关有否决权」这条不变式唯一的构造方式
 *       （见 {@code BlockRegistryS5Test#crossShapeHasVetoPowerOverTheCollisionFlag}）。</li>
 * </ul>
 *
 * <h2>为什么它仍然不污染生产注册表</h2>
 * {@code BlockRegistry} 一旦 bootstrap 就冻结，且 {@code BlockRegistryTest}
 * 对 {@code size()} 与 {@code playerBlockCount()} 有硬编码断言。
 * 把这些方块塞进测试世界时必须用 {@link #TEST_RUNTIME_ID_BASE} 起的
 * <b>远离生产 ID 段（0..21）</b>的高位 ID：若撞了，
 * 区块里就会<b>同时</b>出现两种方块，断言会以极难定位的方式失败。
 */
public final class BlockFixtures {

    /**
     * 测试方块的 runtimeId 起点。
     *
     * <p>现有注册表占 0..21（22 种，见 {@code BlockRegistryTest}；2026-10-07 S5 之后）。
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
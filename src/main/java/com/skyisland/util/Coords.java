package com.skyisland.util;

/**
 * 坐标换算（TECH_DESIGN_v0.1 §D.4）—— <b>全工程唯一的坐标口径来源</b>。
 *
 * <p><b>核心约定：</b>方块坐标 = 该方块所占据立方体的最小角整数坐标。
 * 方块 {@code (x, y, z)} 占据半开区间 {@code [x,x+1) × [y,y+1) × [z,z+1)}。
 * 因此「方块 y 的顶面标高」= {@code y + 1}。
 *
 * <p><b>为什么必须走本类：</b>Java 的 {@code /} 与 {@code %} 对负数是「向零截断」，
 * 直接用于区块划分会出错：
 * <pre>
 *   int bx = -1;
 *   bx / 16   == 0    // 错误！正确区块应为 -1
 *   bx % 16   == -1   // 错误！正确局部坐标应为 15，且会导致数组下标为负
 * </pre>
 *
 * <p>本类的正确性由 {@code com.skyisland.util.CoordsTest} 固化（含用户指定的
 * {@code blockX = -1 → chunkX = -1, localX = 15} 用例）。
 *
 * <p><b>M0 阶段说明：</b>本类在 M0 尚无调用方（M0 不涉及 Chunk / World / 坐标），
 * 但它是 M1 的前置冻结项，因此提前落地并由单元测试保护 —— 让规则由测试而非文档来保证。
 */
public final class Coords {

    /** Chunk 水平尺寸（TECH_DESIGN §D.3 / PRD M1）。 */
    public static final int CHUNK_SIZE = 16;

    /** Chunk 垂直尺寸：区块全高覆盖 y ∈ [0,127]，因此区块坐标只有 (cx, cz)。 */
    public static final int CHUNK_HEIGHT = 128;

    /** 世界可放置 / 可挖掘范围（PRD 4.1 / 5.2）。 */
    public static final int MIN_PLACEABLE_Y = 1;
    public static final int MAX_PLACEABLE_Y = 127;

    /** 虚空致死线（PRD 4.5）：世界坐标 y &lt; -8 立即死亡。 */
    public static final double VOID_KILL_Y = -8.0;

    /**
     * 岛表面方块所占据的层（TECH_DESIGN §D.6 的裁定）。
     *
     * <p>PRD 4.1 与 5.3.1/5.7/M2 在 y 口径上存在 1 层偏差，本工程统一采用
     * 「表面块占据 y = 63，顶面标高 64」这一侧（三处独立证据指向该侧）。
     * <b>若用户裁定改为另一侧，只需翻转这两个常量。</b>
     */
    public static final int WORLD_SURFACE_BLOCK_Y = 63;

    /** 站在地表时的脚底世界坐标 y（= 表面块顶面标高）。 */
    public static final double WORLD_SURFACE_FEET_Y = 64.0;

    private Coords() {
    }

    // ------------------------------------------------------ 世界坐标 → 方块坐标

    /**
     * 世界坐标 → 方块坐标（必须 floor，不能用 {@code (int)}）。
     *
     * <p>{@code (int)} 对负数是向零截断：{@code x = -0.5} 会得到 {@code 0}，
     * 而正确结果是 {@code -1}。
     */
    public static int toBlock(double worldCoord) {
        return (int) Math.floor(worldCoord);
    }

    // ------------------------------------------------------ 方块坐标 → 区块坐标

    /** 方块坐标 → 区块坐标（必须 floorDiv）。 */
    public static int toChunk(int blockCoord) {
        return Math.floorDiv(blockCoord, CHUNK_SIZE);
    }

    /**
     * 方块坐标 → 区块内局部坐标（必须 floorMod）。
     *
     * <p>非热点路径用本方法以保持可读性；热点路径（DDA / 网格化 / 碰撞）可用
     * {@link #localFast(int)}，两者必须产生相同结果（由测试交叉验证）。
     */
    public static int toLocal(int blockCoord) {
        return Math.floorMod(blockCoord, CHUNK_SIZE);
    }

    /**
     * 热点路径版本：{@code blockCoord & 15}。
     *
     * <p>Java 的 int 为二补码，因此位与运算与 {@code Math.floorMod(blockCoord, 16)}
     * 在当前取值范围内等价且更快。
     */
    public static int localFast(int blockCoord) {
        return blockCoord & (CHUNK_SIZE - 1);
    }

    // ------------------------------------------------------ 区块 + 局部 → 方块坐标

    public static int toWorld(int chunkCoord, int localCoord) {
        return chunkCoord * CHUNK_SIZE + localCoord;
    }

    // ------------------------------------------------------ 区块键（供 ChunkStore）

    /**
     * 区块坐标 → 64 位键。
     *
     * <p>{@code cz} 必须用 {@code & 0xFFFFFFFFL} 掩码，否则 <b>负 cz</b> 在高位
     * 扩展时会污染 cx 的位域，导致不同区块产生相同键。
     */
    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int keyCx(long key) {
        return (int) (key >> 32);
    }

    public static int keyCz(long key) {
        return (int) key;
    }

    // ------------------------------------------------------ 区块内线性索引

    /**
     * 区块内线性索引：{@code idx = (ly &lt;&lt; 8) | (lz &lt;&lt; 4) | lx}，值域 [0, 32767]。
     *
     * <p>选 {@code ly} 作最高位：网格化与光照更新都以「某一 y 层的 16×16 平面」
     * 为单位遍历，同层数据在数组内连续可提升缓存命中率。
     */
    public static int chunkIndex(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    public static int indexX(int index) {
        return index & 15;
    }

    public static int indexZ(int index) {
        return (index >> 4) & 15;
    }

    public static int indexY(int index) {
        return (index >> 8) & 127;
    }

    // ------------------------------------------------------ 范围校验

    public static boolean isPlaceableY(int by) {
        return by >= MIN_PLACEABLE_Y && by <= MAX_PLACEABLE_Y;
    }

    public static boolean isInWorldY(int by) {
        return by >= 0 && by < CHUNK_HEIGHT;
    }

    public static boolean isVoidDeath(double worldY) {
        return worldY < VOID_KILL_Y;
    }

    // ------------------------------------------------------ 跨区块邻居偏移

    /**
     * 判断某局部坐标是否位于区块边界（网格化与 mutation 时需同时标记相邻区块）。
     *
     * @return 位掩码：1 = −X 边界，2 = +X 边界，4 = −Z 边界，8 = +Z 边界，0 = 不在边界
     */
    public static int boundaryMask(int lx, int lz) {
        int m = 0;
        if (lx == 0) {
            m |= 1;
        }
        if (lx == CHUNK_SIZE - 1) {
            m |= 2;
        }
        if (lz == 0) {
            m |= 4;
        }
        if (lz == CHUNK_SIZE - 1) {
            m |= 8;
        }
        return m;
    }
}

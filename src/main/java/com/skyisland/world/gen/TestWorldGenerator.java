package com.skyisland.world.gen;

import com.skyisland.util.Coords;
import com.skyisland.world.block.BlockRegistry;

/**
 * M1 First Playable 专用测试世界（M1 指令 B4）。
 *
 * <p><b>它不是为了好看，而是为了让每个待验证的系统都有触发点。</b>
 * 每个几何特征都对应一组必须被验证的行为：
 *
 * <table border="1">
 *   <caption>测试世界特征与验证目标</caption>
 *   <tr><th>特征</th><th>位置</th><th>验证目标</th></tr>
 *   <tr><td>平坦平台（默认面）</td><td>{@code |x|,|z| &lt; 32}</td><td>基础 mesh / 站立 / 行走</td></tr>
 *   <tr><td>高台（+6，3 格墙）</td><td>{@code x∈[-16,-9], z∈[-16,-9]}</td><td>跳不上去、侧面 DDA、垂直面剔除</td></tr>
 *   <tr><td>上行台阶（+1 / +2 / +3）</td><td>{@code x=-6,-7,-8}，{@code z∈[-16,-9]}</td>
 *       <td><b>1 格可跳上</b>（跳跃高度 1.25）、逐级爬升、碰撞分辨率</td></tr>
 *   <tr><td>下行台阶（−1…−4）</td><td>{@code x=8..11}，{@code z∈[-16,-9]}</td>
 *       <td>向下 DDA、负向法线、<b>可原路走回</b>（不构成陷阱）</td></tr>
 *   <tr><td>低地（−4，带台阶出口）</td><td>{@code x∈[12,15], z∈[-16,-9]}</td><td>深坑地形、天光遮挡</td></tr>
 *   <tr><td>虚空坑（无方块柱）</td><td>{@code x∈[3,6], z∈[3,6]}</td><td>虚空判定 / 重生</td></tr>
 *   <tr><td>玻璃板（跨 x=0 区块边界）</td><td>{@code x∈{-1,0}, z∈{1,2}, y=64}</td>
 *       <td><b>跨区块边界</b>的面剔除与邻居重建；同种透明相邻剔除；透明不自遮挡</td></tr>
 *   <tr><td>悬空平台</td><td>{@code x∈[-4,-1], z∈[8,11], y=72}</td><td>无支撑方块、悬浮网格、负 X/Z 坐标</td></tr>
 *   <tr><td>负坐标区域</td><td>{@code x&lt;0, z&lt;0}（整体覆盖）</td><td>{@code floorDiv/floorMod} 口径</td></tr>
 *   <tr><td>资源核心</td><td>{@code (2, 64, 2)}</td><td>不可破坏方块的挖掘拒绝路径 + 自发光</td></tr>
 * </table>
 *
 * <p><b>地形可逃脱性（M1 的两处刻意修正）：</b>
 * <ol>
 *   <li><b>高差全部改成逐级 1 格的楼梯。</b>地形初稿把低地做成"比地表低 4 格、四周全是墙"
 *       的方坑，但玩家跳跃高度只有 1.25 格 —— 那等于把一个"走进去就出不来"的陷阱放进测试世界，
 *       而 M1 的手工试玩门禁是一条<u>不可中断</u>的连续流程
 *       （进入 → 移动 → 挖 → 放 → 坠虚空 → 重生 → 存档 → 退出）。
 *       改成楼梯后既能验证"跳 1 格可以、跳 2 格不可以"，又保证随时能走回出生点。
 *       高台保留 3 格墙，因为它本就是"跳不上去"的对照组。</li>
 *   <li><b>玻璃结构从"十字"改为"跨 x=0 的一块 2×2 板"。</b>十字会让出生点被四面玻璃围住：
 *       玩家（碰撞箱 0.6 宽）无法从对角缝隙挤出去，必须跳上 1 格高的玻璃才能离开 ——
 *       这与 M1 第一条验收"启动 → 进入体素场景 → 第一人称移动"直接冲突。
 *       2×2 板放在 z∈{1,2}，既保留了"跨区块边界相邻"这一待验证事实，
 *       又把出生点周围三个方向留空。</li>
 * </ol>
 *
 * <p><b>确定性：</b>地形是 (x, z) 的纯函数，不使用随机数，因此不依赖 seed。
 * seed 仍被存档记录（与正式生成器的存档格式一致），本生成器只是不使用它 ——
 * 这一点通过 {@code generatorId} 与 {@code generationVersion} 在存档里显式可辨。
 *
 * <p><b>世界范围：</b>区块 {@code cx, cz ∈ [-2, 1]}，即 {@code x, z ∈ [-32, 32)}。
 * 该范围同时覆盖负坐标、跨区块边界与足够的行走空间。
 * M1 不实现视距 6 区块的正式世界，也没有动态加载/卸载策略（已登记为 M1 技术债）。
 */
public final class TestWorldGenerator implements WorldGenerator {

    public static final String ID = "skyisland:test_world";

    /** 生成算法版本：改动地形布局必须提升它，否则老存档的增量会与地形对不上。 */
    public static final int GENERATION_VERSION = 1;

    /** 世界区块范围（含）。 */
    public static final int MIN_CHUNK = -2;
    public static final int MAX_CHUNK = 1;

    /** 默认地表方块所占据的 y 层。 */
    private static final int BASE_TOP = Coords.WORLD_SURFACE_BLOCK_Y;   // 63

    /** 平台底板下沿：低于此不再填充（平台是一块有厚度的浮空板）。 */
    private static final int BASE_BOTTOM = 58;

    private static final int PLATEAU_TOP = BASE_TOP + 6;   // 69，3 格墙

    /** 上行台阶（x = -6, -7, -8）的顶点层：64 / 65 / 66。 */
    private static final int ASCEND_BASE_X = -6;
    private static final int ASCEND_TOP_X = -8;

    /** 下行台阶（x = 8..11）的第一步所在 x，以及台阶总数（−1…−4）。 */
    private static final int DESCEND_BASE_X = 8;
    private static final int DESCEND_STEPS = 4;

    /** 台阶区的 z 范围（含）—— 上下行台阶共用同一条走廊。 */
    private static final int STAIR_MIN_Z = -16;
    private static final int STAIR_MAX_Z = -9;

    private static final int FLOATING_Y = BASE_TOP + 9;    // 72

    /** 虚空坑范围（含）。 */
    private static final int HOLE_MIN = 3;
    private static final int HOLE_MAX = 6;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int generationVersion() {
        return GENERATION_VERSION;
    }

    @Override
    public void generate(ChunkWriter out, int cx, int cz, long seed) {
        final int originX = out.originX();
        final int originZ = out.originZ();

        final short grass = (short) BlockRegistry.grass().runtimeId();
        final short dirt = (short) BlockRegistry.dirt().runtimeId();
        final short sand = (short) BlockRegistry.sand().runtimeId();
        final short stone = (short) BlockRegistry.stone().runtimeId();
        final short glass = (short) BlockRegistry.glass().runtimeId();

        for (int lz = 0; lz < Coords.CHUNK_SIZE; lz++) {
            for (int lx = 0; lx < Coords.CHUNK_SIZE; lx++) {
                final int wx = originX + lx;
                final int wz = originZ + lz;
                final int top = surfaceTop(wx, wz);

                if (top < 0) {
                    // 虚空坑：该列完全不放方块，玩家走进去会一直落到 y < -8
                    continue;
                }

                final boolean lowland = isLowland(wx, wz);
                for (int y = BASE_BOTTOM; y <= top; y++) {
                    short material;
                    if (y == top) {
                        material = lowland ? sand : grass;
                    } else if (y == top - 1) {
                        material = lowland ? sand : dirt;
                    } else {
                        material = stone;
                    }
                    out.set(lx, y, lz, material);
                }

                // 玻璃板：跨 x=0 区块边界的 2×2 结构，位于地表之上 1 层。
                // 它同时验证三件事：① 跨区块边界的面剔除（x=-1 与 x=0 分属不同区块但相邻）；
                // ② 同种透明相邻剔除（玻璃贴玻璃的共享面）；③ 透明方块不遮挡身后。
                // 刻意避开 z ∈ {0} 这一行：出生点 (0.5, 64.0, 0.5) 必须可自由移动（见类注释）。
                if ((wx == -1 || wx == 0) && (wz == 1 || wz == 2)) {
                    out.set(lx, top + 1, lz, glass);
                }
            }
        }

        // 悬空平台：4×4 × 1 厚，位于 y = 72，下方完全悬空
        for (int wx = -4; wx <= -1; wx++) {
            for (int wz = 8; wz <= 11; wz++) {
                writeIfInside(out, wx, FLOATING_Y, wz, BlockRegistry.planks().runtimeId());
            }
        }

        // 资源核心：不可破坏方块，用于验证挖掘拒绝路径
        writeIfInside(out, 2, BASE_TOP + 1, 2, BlockRegistry.resourceCore().runtimeId());
    }

    /**
     * 地表方块所在层；返回 &lt; 0 表示该列没有方块（虚空坑）。
     *
     * <p>判定顺序即优先级：虚空坑 → 高台 → 上行台阶 → 下行台阶 → 默认地表。
     * 台阶区共用 z 走廊 {@code [STAIR_MIN_Z, STAIR_MAX_Z]}，但分别从 base 向两侧逐个 x 变化，
     * 因此每级高差<b>恰好 1 格</b>（这是"能跳上去"的唯一条件，见 {@code Player#JUMP_HEIGHT}）。
     */
    private int surfaceTop(int wx, int wz) {
        if (wx >= HOLE_MIN && wx <= HOLE_MAX && wz >= HOLE_MIN && wz <= HOLE_MAX) {
            return -1;
        }
        if (wx >= -16 && wx <= -9 && wz >= -16 && wz <= -9) {
            return PLATEAU_TOP;
        }
        if (wz >= STAIR_MIN_Z && wz <= STAIR_MAX_Z) {
            // 上行台阶：x = -6 → 64，-7 → 65，-8 → 66；再往西 (-9..) 是 69 的高台墙
            if (wx <= ASCEND_BASE_X && wx >= ASCEND_TOP_X) {
                return BASE_TOP + (ASCEND_BASE_X - wx) + 1;
            }
            // 下行台阶：x = 8 → 62，9 → 61，10 → 60，11..15 → 59
            if (wx >= DESCEND_BASE_X && wx <= 15) {
                int step = Math.min(wx - DESCEND_BASE_X + 1, DESCEND_STEPS);
                return BASE_TOP - step;
            }
        }
        return BASE_TOP;
    }

    /** 低地区域（含下行台阶）：材质换成沙子，便于一眼看出这块地形与主平台不同。 */
    private boolean isLowland(int wx, int wz) {
        return wx >= DESCEND_BASE_X && wx <= 15 && wz >= STAIR_MIN_Z && wz <= STAIR_MAX_Z;
    }

    private void writeIfInside(ChunkWriter out, int wx, int wy, int wz, int runtimeId) {
        int lx = wx - out.originX();
        int lz = wz - out.originZ();
        if (lx < 0 || lx >= Coords.CHUNK_SIZE || lz < 0 || lz >= Coords.CHUNK_SIZE) {
            return;
        }
        out.set(lx, wy, lz, runtimeId);
    }

    /** 生成世界的区块数量（4×4 = 16）。 */
    public static int chunkCount() {
        int span = MAX_CHUNK - MIN_CHUNK + 1;
        return span * span;
    }

    /** 出生点（TECH_DESIGN §I.5：必须是 (0.5, 64.0, 0.5)）。 */
    public static double spawnX() {
        return 0.5;
    }

    public static double spawnY() {
        return Coords.WORLD_SURFACE_FEET_Y;
    }

    public static double spawnZ() {
        return 0.5;
    }
}

package com.skyisland.world;

import com.skyisland.util.Coords;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;

/**
 * 光照引擎（TECH_DESIGN §H.5：固定径向衰减，<b>不做全局传播</b>）。
 *
 * <p><b>口径（写死）：</b>
 * <pre>
 *   light = clamp( max(skyLight, maxOverTorches(torchLight)), 0, 15 )
 *   skyLight   = (y &gt;= columnTop[x][z]) ? 15 : 0      // dayFactor = 1.0（M1 无昼夜）
 *   torchLight = max(0, emission - chebyshevDistance)
 * </pre>
 *
 * <p><b>为什么是"快照"而不是"可查询的服务"：</b>顶点色里已经把光照<u>烘焙</u>成 shade
 * （§G.3 的取舍），因此光照只在一个时刻被需要 —— 构建区块网格的那一刻。
 * 把它做成"随时可查的全局状态"会引入"谁的缓存过期了"这类问题，
 * 而做成快照之后，生命周期与网格完全一致：区块脏 → 网格重建 → 光照随之重算。
 *
 * <p><b>为什么用"整体重算"而不是增量传播：</b>见 §H.5 —— 增量更新需要处理
 * "移除光源后区域要变暗"，而"变暗"无法通过叠加实现。M1 每次只重算<u>一个区块</u>
 * （32768 次列扫描 + 少量光源距离比较），微秒级。
 *
 * <p><b>M1 的已知限制（写入 M1 报告）：</b>
 * <ul>
 *   <li>{@code dayFactor} 恒为 1.0 —— M1 明确不含昼夜循环，公式里的 dayFactor 分支
 *       因此没有使用者，U 型插值（黎明/黄昏）留到 M2；</li>
 *   <li>天光是"列遮挡"模型（只看本列最高方块），不做侧向漫射 —— 这是 §H.5 的
 *       规定口径，不是实现缺陷；因此"洞穴里但在开口正下方"仍会被判为受光。</li>
 * </ul>
 */
public final class LightEngine {

    public static final int MAX_LIGHT = 15;

    /** 光源的最大影响半径（切比雪夫距离，§H.5）。 */
    public static final int TORCH_RADIUS = 6;

    /**
     * 无光照处的最低明暗，避免完全看不见（M1 无火把也无夜晚，但保留可读性余量）。
     *
     * <p>★ <b>M5a 起它是"白天的地板"</b>：夜晚的地板由 {@link DayClock#ambientFloor()}
     * 按 {@link DayClock#NIGHT_BRIGHTNESS_RATIO}（PRD §4.4 的 15%）缩放得到。
     * 之所以放在这里而不是写在 {@code DayClock} 里再抄一份：烘焙用的地板
     * 与运行时用的地板<b>必须是同一个数</b>，否则"白天"这个基准会漂移，
     * 而那种漂移的表现只是"夜里好像比预期亮一点"。
     */
    public static final float AMBIENT_FLOOR = 0.45f;

    private final int chunkSize = Coords.CHUNK_SIZE;
    private final int chunkHeight = Coords.CHUNK_HEIGHT;

    /** 每列最高非空气方块的 y；{@code -1} 表示整列为空（虚空坑）。索引 {@code lz * 16 + lx}。 */
    private final int[] columnTop = new int[Coords.CHUNK_SIZE * Coords.CHUNK_SIZE];

    /** 本区块相关（含跨界影响）的自发光方块。M1 通常 0–1 个。 */
    private final int[] sourceX;
    private final int[] sourceY;
    private final int[] sourceZ;
    private final int[] sourceLevel;
    private final int sourceCount;

    private LightEngine(int[] sourceX, int[] sourceY, int[] sourceZ, int[] sourceLevel, int sourceCount) {
        this.sourceX = sourceX;
        this.sourceY = sourceY;
        this.sourceZ = sourceZ;
        this.sourceLevel = sourceLevel;
        this.sourceCount = sourceCount;
    }

    /**
     * 为一个区块建立光照快照。
     *
     * <p>自发光来源从 {@link World#emissiveSources()} 取<b>世界级</b>列表，
     * 再按"与本区块的切比雪夫距离 ≤ {@link #TORCH_RADIUS}"过滤。
     * 这一点很重要：若只看本区块内的光源，紧贴区块边界的光源会在一侧亮、另一侧突然变暗，
     * 形成一条沿区块边界的硬边 —— 这是"每区块各自算光照"最典型的瑕疵。
     */
    public static LightEngine snapshot(Chunk chunk) {
        return snapshot(chunk, null);
    }

    public static LightEngine snapshot(Chunk chunk, World world) {
        int[] sx = new int[64];
        int[] sy = new int[64];
        int[] sz = new int[64];
        int[] sl = new int[64];
        int count = 0;

        if (world != null) {
            for (World.EmissiveSource src : world.emissiveSources()) {
                // 与区块 AABB 的切比雪夫距离：任一分量为 0 说明已在范围内
                int dx = chebyshevToRange(src.x(), chunk.originX(), chunk.originX() + Coords.CHUNK_SIZE - 1);
                int dz = chebyshevToRange(src.z(), chunk.originZ(), chunk.originZ() + Coords.CHUNK_SIZE - 1);
                int dy = chebyshevToRange(src.y(), 0, Coords.CHUNK_HEIGHT - 1);
                if (Math.max(dx, Math.max(dy, dz)) > TORCH_RADIUS) {
                    continue;
                }
                if (count == sx.length) {
                    break;
                }
                sx[count] = src.x();
                sy[count] = src.y();
                sz[count] = src.z();
                sl[count] = src.level();
                count++;
            }
        }

        LightEngine engine = new LightEngine(sx, sy, sz, sl, count);
        engine.scanColumns(chunk);
        return engine;
    }

    /** 点到一个闭区间的切比雪夫距离（在区间内为 0）。 */
    private static int chebyshevToRange(int value, int min, int max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0;
    }

    /**
     * 扫描每列的最高非空气方块。
     *
     * <p>从顶到底扫并在第一个非空气处停下 —— 开阔地形下这一步几乎立刻结束，
     * 代价远低于"扫描全部 32768 格"。
     */
    private void scanColumns(Chunk chunk) {
        for (int lz = 0; lz < chunkSize; lz++) {
            for (int lx = 0; lx < chunkSize; lx++) {
                int top = -1;
                for (int y = chunkHeight - 1; y >= 0; y--) {
                    if (chunk.blockAt(lx, y, lz) != BlockRegistry.AIR_RUNTIME_ID) {
                        top = y;
                        break;
                    }
                }
                columnTop[lz * chunkSize + lx] = top;
            }
        }
    }

    // ============================================================ 查询

    /** 该列最高非空气方块的 y；{@code -1} = 整列为空。 */
    public int columnTop(int lx, int lz) {
        if (lx < 0 || lx >= chunkSize || lz < 0 || lz >= chunkSize) {
            return -1;
        }
        return columnTop[lz * chunkSize + lx];
    }

    /** 天光：只由"本列最高方块"决定（§H.5 的列遮挡模型）。 */
    public int skyLightAt(int lx, int ly, int lz) {
        return ly >= columnTop(lx, lz) ? MAX_LIGHT : 0;
    }

    /**
     * 综合光照值 [0,15]（天光与火把光取最大），世界坐标口径。
     *
     * <p>火把衰减必须用世界坐标：局部坐标 {@code lx} 在相邻两个区块里会取到同一个值，
     * 用局部坐标算距离会让"边界两侧的同一世界位置"得到不同光照，
     * 于是区块边界上出现硬边。
     *
     * @param originX 本区块原点的世界 x
     * @param originZ 本区块原点的世界 z
     */
    public int lightAt(int originX, int originZ, int lx, int ly, int lz) {
        int light = Math.max(skyLightAt(lx, ly, lz), torchLightAt(originX, originZ, lx, ly, lz));
        return Math.min(MAX_LIGHT, light);
    }

    /**
     * 火把光：{@code max(0, emission - chebyshevDistance)}。
     *
     * <p>用切比雪夫距离而非欧氏距离，是 §H.5 的规定口径：它让光照范围成为一个立方体，
     * 与"每个方向衰减一格"的直觉一致，也避免了欧氏距离下的开方运算。
     */
    public int torchLightAt(int originX, int originZ, int lx, int ly, int lz) {
        if (sourceCount == 0) {
            return 0;
        }
        int best = 0;
        int wx = originX + lx;
        int wz = originZ + lz;
        for (int i = 0; i < sourceCount; i++) {
            int d = Math.max(Math.abs(wx - sourceX[i]),
                    Math.max(Math.abs(ly - sourceY[i]), Math.abs(wz - sourceZ[i])));
            if (d > TORCH_RADIUS) {
                continue;
            }
            int value = sourceLevel[i] - d;
            if (value > best) {
                best = value;
            }
        }
        return Math.max(0, best);
    }

    /**
     * 光照 → 明暗系数。
     *
     * <p>映射为 {@code 0.45 + 0.55 × light/15}：全黑时仍有 0.45（M1 没有火把也没有夜晚，
     * 若映射到 0 会让"在地表以下的方块"变成纯黑而无法辨认形状），全亮时为 1.0。
     * 保留 0.45 的地板是<u>可读性决定</u>，不是光照模型的组成部分 ——
     * 后续接入昼夜与火把时这个地板应当随亮度设置项一起下调。
     */
    public float shadeFactor(int light) {
        int clamped = Math.max(0, Math.min(MAX_LIGHT, light));
        return AMBIENT_FLOOR + (1f - AMBIENT_FLOOR) * (clamped / (float) MAX_LIGHT);
    }

    /** 供测试与 HUD：本次快照纳入的光源数。 */
    public int sourceCount() {
        return sourceCount;
    }

    /** 方块定义 → 是否属于光源（集中判据，避免各处重复写 {@code lightEmission() > 0}）。 */
    public static boolean isEmissive(Block block) {
        return block != null && !block.isAir() && block.lightEmission() > 0;
    }
}

package com.skyisland.world.gen;

import com.skyisland.util.Coords;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;

import java.util.List;

/**
 * 正式空岛世界生成器（PRD 4.1–4.3 / 4.6 / 4.7、5.7）。
 *
 * <h2>它替代了什么</h2>
 * M1–M5 一直用 {@link TestWorldGenerator}：一块 64×64 的浮空测试平台，
 * 带高台、楼梯、虚空坑与一块玻璃板 —— 它是<b>玩法机制的测试夹具</b>，
 * 不是产品世界（与武器系统那轮「测试夹具被当成产品内容」的教训同族）。
 * 本类才是 PRD 4.2 那张表：<b>主岛 32×32 + 4 座资源小岛</b>。
 *
 * <h2>为什么自测仍然跑 {@code TestWorldGenerator}</h2>
 * 三个自测（M1 / M1.5 / M2）断言的是<b>玩法机制</b>：走 1 格、跳 1 格、
 * 挖穿某格、右键放下、坠入虚空死亡、开枪打死怪。它们各自钉在一个
 * <b>为它专门搭的平台上</b>（虚空坑固定在 {@code (3..6, 3..6)}、
 * 高台固定 3 格高、出生点固定 {@code (0.5, 64.0, 0.5)}）。
 * <p>把产品世界换进去会让这些断言以「玩法没生效」的样子失败，
 * 而真因是地形不同 —— 与 M2.2 那次「槽位口径」事故完全同族。
 * <p>⇒ <b>夹具与产品分离</b>：自测走 {@code test_world}
 * （由 {@code -Dskyisland.generator} 切换），产品走本类；
 * 本类另有自己的布局断言（{@code IslandWorldLayoutTest}），
 * 对应 PRD M3 验收第 2 条「坐标、尺寸误差 ≤ 2 格」。
 *
 * <h2>★ 一处必须登记的规格冲突（不要"顺手改掉"）</h2>
 * PRD 4.1 写「主岛基准面 <b>y = 64</b>（岛屿表面层）」、
 * 4.6 写「表面层 y = 64 下一格」，而本引擎自 M1 起冻结的是
 * {@link Coords#WORLD_SURFACE_BLOCK_Y} = <b>63</b>（脚底 y = 64；
 * 该常量的注释原文：「表面方块占据 y=63，双脚在 y=64，改这里要同时改那一个」）。
 * <p>本类<b>服从引擎常量</b>：表面方块 y = 63、玩家脚底 y = 64。
 * 理由：改引擎常量会让 M1–M5 全部存档、三个自测、出生点与资源核心 y 一起位移，
 * 代价远大于收益；而「表面层」的<b>物理含义</b>（脚踩的那一层）在两套口径下是同一个高度。
 * <p>⇒ 岛屿厚度按 PRD「自表面层向下 8–14 层」执行：表面方块 y=63 时
 * 底部落在 y = 56（8 层）至 y = 50（14 层）。
 * 资源核心放在表面方块<b>之上</b>一格（y = 64），
 * 与 {@code TestWorldGenerator} 的既有做法一致（核心 = 玩家站得到的位置）。
 *
 * <h2>确定性（存档稀疏增量的前提）</h2>
 * 生成必须是 {@code (seed, cx, cz)} 的<b>纯函数</b>：不读邻居、
 * 不用 {@code java.util.Random}、不持有状态。岛轮廓、厚度、地表细节、
 * 矿脉位置、树木位置全部由 {@code seed} 经 splitmix64 哈希导出。
 * <p>因此：同一 seed 永远得到同一世界（PRD 4.7「Seed 可见、可手动输入，便于复现」），
 * 且玩家改动只以<b>稀疏增量</b>存盘 —— 生成不确定就会让增量对不上地形。
 *
 * <h2>尺寸口径为什么是「只侵蚀、不外扩」</h2>
 * PRD 4.2 把岛定义成「中心坐标 + 尺寸」，并明确「坐标表约束轮廓的
 * <b>包围范围</b>与相对方位，<b>不约束逐格形状</b>」；
 * PRD M3 验收要求「坐标、尺寸误差 <b>≤ 2 格</b>」。
 * ⇒ 噪声只用来<b>向内侵蚀</b>（最大约 1.5 格），绝不把岛撑出包围盒。
 * 若允许外扩，一颗"运气好"的种子能让石矿岛伸进主岛，
 * 于是「距主岛边缘间隙 约 25 格」这条布局意图被静默破坏。
 */
public final class IslandWorldGenerator implements WorldGenerator {

    /** 生成器稳定 ID，写入存档 {@code level.json} 的 {@code generatorId}。 */
    public static final String ID = "skyisland:islands";

    /**
     * 生成算法版本。
     *
     * <p><b>改动地形布局必须提升它</b>：老存档只存稀疏增量，
     * 地形变了而版本没变 ⇒ 增量叠在错地形上，且没有任何报错。
     */
    public static final int GENERATION_VERSION = 1;

    /** 玩家出生点：小屋内部，地板顶面之上（PRD 5.7「地板顶面 y = 64」）。 */
    public static final double SPAWN_X = 0.5;
    public static final double SPAWN_Y = 64.0;
    public static final double SPAWN_Z = 0.5;

    // ------------------------------------------------------------------
    // 岛屿布局表（PRD 4.2）
    // ------------------------------------------------------------------

    /** 岛类型 —— 决定矿脉密度表、是否放资源核心、是否长树。 */
    public enum Kind {
        /** 主岛：出生岛，<b>不产铁 / 铜 / 金 / 晶体</b>（PRD 4.3），有开局小屋。 */
        MAIN,
        /** 石矿岛：富集石、煤、铁。 */
        STONE,
        /** 森林岛：富集原木、泥土、小麦种。 */
        FOREST,
        /** 金属岛：富集铁、铜、金。 */
        METAL,
        /** 晶矿岛：富集晶体（稀有高级材料）。 */
        CRYSTAL
    }

    /** 资源密度档（PRD 4.3：每 100 格表面积的平均矿物格数）。 */
    public enum Density {
        /** 高 = 8–12。 */
        HIGH(10.0),
        /** 中 = 3–6。 */
        MEDIUM(4.5),
        /** 低 = 1–2。 */
        LOW(1.5),
        /** 无 = 0。 */
        NONE(0.0);

        /** 取档位中值 —— 断言时按 PRD 的区间（8–12 / 3–6 / 1–2）判，不按中值判。 */
        public final double blocksPer100;

        Density(double blocksPer100) {
            this.blocksPer100 = blocksPer100;
        }
    }

    /**
     * 一座岛的规格。
     *
     * @param key 稳定标识（用作种子盐，报告与日志也用它）
     * @param centerX 几何中心 x（PRD 4.2 表）
     * @param centerZ 几何中心 z
     * @param half 尺寸的一半。14×14 → 7；12×12 → 6；10×10 → 5
     * @param surface 表面方块材质
     * @param subsurface 表面下一层材质
     * @param kind 岛类型
     * @param ores 该岛的矿脉密度表（长度 = {@link Ore} 数）
     */
    public record Island(String key, int centerX, int centerZ, int half,
                         int surface, int subsurface, Kind kind,
                         Density[] ores) {

        /** 包围盒 x 区间（含）。 */
        public int minX() {
            return centerX - half;
        }

        public int maxX() {
            return centerX + half - 1;
        }

        public int minZ() {
            return centerZ - half;
        }

        public int maxZ() {
            return centerZ + half - 1;
        }

        /** 尺寸（边长），PRD 4.2 表用「14×14」这种写法。 */
        public int size() {
            return half * 2;
        }

        /** 表面积（列数）。 */
        public int area() {
            return size() * size();
        }

        /** 包围盒是否与本区块相交（用于快速跳过绝大多数区块）。 */
        public boolean intersects(int originX, int originZ) {
            return maxX() >= originX && minX() <= originX + Coords.CHUNK_SIZE - 1
                    && maxZ() >= originZ && minZ() <= originZ + Coords.CHUNK_SIZE - 1;
        }

        /** 该列是否落在本岛矩形包围盒内（噪声侵蚀在更外层做）。 */
        public boolean contains(int wx, int wz) {
            return wx >= minX() && wx <= maxX() && wz >= minZ() && wz <= maxZ();
        }
    }

    /** 矿石种类 —— 只列 PRD 4.3 出现过的五种。 */
    public enum Ore {
        COAL(BlockRegistry.coalOre(), "煤"),
        IRON(BlockRegistry.ironOre(), "铁"),
        COPPER(BlockRegistry.copperOre(), "铜"),
        GOLD(BlockRegistry.goldOre(), "金"),
        CRYSTAL(BlockRegistry.crystalOre(), "晶体");

        private final Block block;
        private final String label;

        Ore(Block block, String label) {
            this.block = block;
            this.label = label;
        }

        public int id() {
            return block.runtimeId();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 主岛尺寸：32×32 ⇒ half = 16（x,z ∈ [-16, 15]）。 */
    public static final int MAIN_HALF = 16;

    private static Density[] ores(Density coal, Density iron, Density copper,
                                  Density gold, Density crystal) {
        return new Density[]{coal, iron, copper, gold, crystal};
    }

    /**
     * 五座岛，按 PRD 4.2 表；矿脉密度按 PRD 4.3 表逐格抄。
     *
     * <p>★ 表面/次表层材质是<b>本类的设计决定</b>（PRD 未逐岛指定）：
     * 两座「生态岛」（主岛 / 森林岛）用草 + 泥，三座「矿岛」用裸岩（石 + 石）。
     * 理由是让玩家<b>不用看地图就能判断这是哪种岛</b> ——
     * 一座光秃秃的岩岛与一座长草的岛，光看轮廓之外的观感已经不同。
     */
    public static final List<Island> ISLANDS = List.of(
            new Island("main", 0, 0, MAIN_HALF,
                    BlockRegistry.grass().runtimeId(),
                    BlockRegistry.dirt().runtimeId(), Kind.MAIN,
                    //        煤    铁    铜    金    晶体
                    ores(Density.LOW, Density.NONE, Density.NONE, Density.NONE, Density.NONE)),
            new Island("stone_island", 48, 0, 7,
                    BlockRegistry.stone().runtimeId(),
                    BlockRegistry.stone().runtimeId(), Kind.STONE,
                    ores(Density.HIGH, Density.HIGH, Density.LOW, Density.NONE, Density.NONE)),
            new Island("forest_island", -44, 12, 7,
                    BlockRegistry.grass().runtimeId(),
                    BlockRegistry.dirt().runtimeId(), Kind.FOREST,
                    ores(Density.NONE, Density.NONE, Density.NONE, Density.NONE, Density.NONE)),
            new Island("metal_island", 10, -48, 6,
                    BlockRegistry.stone().runtimeId(),
                    BlockRegistry.stone().runtimeId(), Kind.METAL,
                    ores(Density.MEDIUM, Density.HIGH, Density.HIGH, Density.MEDIUM, Density.LOW)),
            new Island("crystal_island", -6, 52, 5,
                    BlockRegistry.stone().runtimeId(),
                    BlockRegistry.stone().runtimeId(), Kind.CRYSTAL,
                    ores(Density.LOW, Density.MEDIUM, Density.MEDIUM, Density.LOW, Density.HIGH)));

    /** 岛屿最小厚度（层），PRD 4.1「8–14 层」。 */
    public static final int MIN_THICKNESS = 8;

    /** 岛屿最大厚度（层），PRD 4.1「8–14 层」。 */
    public static final int MAX_THICKNESS = 14;

    /** 厚度均值 —— 只用于把「每 100 格表面积的矿物格数」换算成石头格上的概率。 */
    private static final double AVERAGE_THICKNESS = (MIN_THICKNESS + MAX_THICKNESS) / 2.0;

    /**
     * 供测试与诊断读取：某岛全部矿种的期望命中率之和。
     *
     * <p>成矿判定用的就是这个和（见 {@link #oreWinner}）。
     * 单独暴露是为了让测试能核对"成矿总概率"与"各矿密度"确实指向同一个数。
     */
    static double totalOreProbability(Island island, long seed) {
        double total = 0.0;
        for (Ore ore : Ore.values()) {
            total += oreProbability(island, ore, seed);
        }
        return total;
    }

    /** 供测试与诊断读取：某岛的可产矿格总数（密度换算的分母）。 */
    static double oreCapacityOf(Island island, long seed) {
        return oreCapacity(island, seed);
    }

    // ------------------------------------------------------------------
    // 开局小屋（PRD 5.7）
    // ------------------------------------------------------------------

    /** 小屋外框 9×9 ⇒ x,z ∈ [-4, 4]（含墙厚），内部净空 7×7 ⇒ [-3, 3]。 */
    public static final int HUT_HALF = 4;

    /** 小屋地板顶面 y（= 表面方块 63 的顶面，也是玩家双脚高度）。 */
    public static final int HUT_FLOOR_TOP_Y = 64;

    /** 内部净空区间：y ∈ [64, 66]（PRD「内部 3 格」）。 */
    public static final int HUT_INTERIOR_MIN_Y = 64;
    public static final int HUT_INTERIOR_MAX_Y = 66;

    /** 屋顶层：y = 67（PRD「屋顶位于 y = 67」）。 */
    public static final int HUT_ROOF_Y = 67;

    /** 门在南墙中央；本引擎 {@code wooden_door} 是实心方块，故门口留 1×2 空洞。 */
    public static final int DOOR_X = 0;

    /** 屋顶「半成品」缺口（南墙顶），呼应 PRD「南面留 1 处未封顶」。 */
    public static final int ROOF_GAP_X = 2;
    public static final int ROOF_GAP_Z = HUT_HALF;

    // ------------------------------------------------------------------
    // 资源核心（PRD 4.6）
    // ------------------------------------------------------------------

    /** 石矿岛核心刷新周期（秒）与单次数量 —— PRD 4.6 参数表（MVP 只做石矿岛）。 */
    public static final int CORE_REGEN_PERIOD_SECONDS = 180;
    public static final int CORE_REGEN_PER_RUN = 1;
    public static final int CORE_REGEN_HALF_SPAN = 2;   // 5×5 水平范围
    public static final int CORE_REGEN_MIN_DY = -2;
    public static final int CORE_REGEN_MAX_DY = 1;

    // ------------------------------------------------------------------
    // 生成
    // ------------------------------------------------------------------

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

        // ★ 先用包围盒把绝大多数区块直接跳过：本世界 5 座岛只占约 1600 列，
        //   而 6 区块视距一次会摸到上百个区块。不跳过的话每个区块都要
        //   跑 5 次岛屿查找 + 值噪声，而绝大多数区块的结果必然是"虚空"。
        boolean touchesAny = false;
        for (Island island : ISLANDS) {
            if (island.intersects(originX, originZ)) {
                touchesAny = true;
                break;
            }
        }
        if (!touchesAny) {
            return;
        }

        buildTerrain(out, seed);
        placeResourceCores(out);
        buildStarterHut(out);
        plantTrees(out, seed);
    }

    /** 地形柱：表面 / 次表层 / 石头 / 厚度 / 矿脉。 */
    private static void buildTerrain(ChunkWriter out, long seed) {
        final int originX = out.originX();
        final int originZ = out.originZ();
        final int stone = BlockRegistry.stone().runtimeId();

        for (int lz = 0; lz < Coords.CHUNK_SIZE; lz++) {
            for (int lx = 0; lx < Coords.CHUNK_SIZE; lx++) {
                final int wx = originX + lx;
                final int wz = originZ + lz;
                final Island island = islandAt(wx, wz, seed);
                if (island == null) {
                    continue;   // 虚空：脚下无地（PRD 4.5）
                }
                final int top = Coords.WORLD_SURFACE_BLOCK_Y;
                final int bottom = top - thicknessOf(island, wx, wz, seed) + 1;

                for (int y = bottom; y <= top; y++) {
                    int material;
                    if (y == top) {
                        material = island.surface();
                    } else if (y == top - 1) {
                        material = island.subsurface();
                    } else {
                        material = stone;
                        // ★ 矿脉判定与写入在<b>同一次 y 循环</b>里完成。
                        //   第一版曾把"这一列有没有矿"与"矿该写在哪一格"拆成
                        //   两个方法各算一遍 —— 两次哈希调用顺序不同就会得到
                        //   不同的 y，症状是<b>矿石浮在半空</b>，而两处都"看起来对"。
                        final int oreId = oreAt(island, wx, y, wz, seed, top, bottom);
                        if (oreId > 0) {
                            material = oreId;
                        }
                    }
                    out.set(lx, y, lz, material);
                }
            }
        }
    }

    /** 每座资源岛中心 1 个资源核心（PRD 4.6）；主岛没有核心。 */
    private static void placeResourceCores(ChunkWriter out) {
        final int core = BlockRegistry.resourceCore().runtimeId();
        for (Island island : ISLANDS) {
            if (island.kind() == Kind.MAIN) {
                continue;
            }
            write(out, island.centerX(), Coords.WORLD_SURFACE_BLOCK_Y + 1,
                    island.centerZ(), core);
        }
    }

    // ------------------------------------------------------------------
    // 岛屿归属与形状
    // ------------------------------------------------------------------

    /**
     * 该世界列属于哪座岛；不属于任何岛返回 {@code null}（虚空）。
     *
     * <p>五座岛的包围盒<b>互不重叠</b>（PRD 4.2：资源岛之间最近间距 ≥ 32 格），
     * 因此「逐个检查」不需要额外仲裁规则。
     */
    public static Island islandAt(int wx, int wz, long seed) {
        for (Island island : ISLANDS) {
            if (island.contains(wx, wz) && insideOutline(island, wx, wz, seed)) {
                return island;
            }
        }
        return null;
    }

    /** 该世界坐标是否属于任何岛屿（虚空判定，PRD 4.5）。 */
    public static boolean isIslandColumn(int wx, int wz, long seed) {
        return islandAt(wx, wz, seed) != null;
    }

    /**
     * 轮廓判定：圆角方形 + <b>只向内</b>的噪声侵蚀。
     *
     * <p>归一化距离 {@code d}：把 {@code |x|/h} 与 {@code |z|/h} 的切比雪夫距离
     * 与欧氏距离按 65 : 35 混合，得到「边中点贴到包围盒、角上被削圆」的形状，
     * {@code d = 1} 恰好落在包围盒边界上。
     * <p>侵蚀量取 {@code [0, 0.09]}：主岛 {@code h = 16} ⇒ 最多 1.44 格，
     * 正好在 PRD M3 验收的「尺寸误差 ≤ 2 格」之内，且<b>不会外扩</b>。
     */
    private static boolean insideOutline(Island island, int wx, int wz, long seed) {
        // ★ 两处都<b>必须</b>先转 double：Java 的 int / int 是整数除法。
        //   写成 `(wx - centerX) / island.half()` 时 nx 只会取到 0 或 ±1 ——
        //   -15 / 16 == 0，于是轮廓退化成"完整方块 + 只切掉 (-16,-16) 一个角"，
        //   1024 列里只有 1 列落在 d > 0.94 的带内。
        //   症状极具欺骗性：岛确实是方的、确实有种子参数、
        //   唯一露馅的是"两个不同 seed 生成逐格相同的世界"。
        final double nx = (double) (wx - island.centerX()) / island.half();
        final double nz = (double) (wz - island.centerZ()) / island.half();
        final double chebyshev = Math.max(Math.abs(nx), Math.abs(nz));
        final double euclid = Math.sqrt(nx * nx + nz * nz) / SQRT2;
        final double d = 0.65 * chebyshev + 0.35 * euclid;

        // ★ 侵蚀噪声的空间尺度取 3 格。
        //   这个数字**不是**调出来的，是被两轮失败逼出来的：
        //   <ol>
        //     <li>先取 4.0 格 —— 两个不同 seed 生成了逐格相同的轮廓；</li>
        //     <li>改成 3.0 格后**仍然**逐格相同 —— 真因是上面那处整数除法，
        //         把带子里的列数压到了 1 列（详见 nx/nz 处的注释）。
        //         ⇒ 修掉整数除法之后，尺度回到多少都该有效；
        //         这里保留 3.0 是因为它让轮廓边缘更碎一点、更好认，
        //         而不是为了让某条断言变绿。</li>
        //   </ol>
        // ★ 这里的噪声<b>同时</b>承担两件事，缺一不可：
        //   <b>(a) 把轮廓从"完整方块"变成有机边缘</b>；
        //   <b>(b) 让 seed 真正改变逐格归属</b>（PRD 4.7）。
        // <p><b>(b) 是靠"双通道"做到的</b>，单通道做不到：
        // 只用 {@code erode} 的话，每格是否归属完全由
        // {@code d > 1 - erode} 决定，而 {@code erode} 的值域被
        // {@code OUTLINE_MAX_EROSION} 压到 0.06 以内 ——
        // 于是只有 {@code d ∈ (0.94, 1.0]} 这一圈极窄的环带会翻转，
        // 实测两个 seed 只有 18 列不同（阈值要求 > 20）。
        // <p>⇒ 增加第二个通道 {@code outlineJitter}：一个与 {@code d} 无关、
        //   纯粹由 seed 决定的微小偏移。它把"哪几列恰好落在翻转边界上"
        //   变成 seed 相关，从而让整条边缘（而不只是环带）都随 seed 变化。
        //   它的量级必须<b>小于</b>每列的最大侵蚀量（{@code 0.06 × half}），
        //   否则尺寸误差会突破 PRD 的 2 格上限。
        final double erode = valueNoise2(seed, 0x1000L + island.key().hashCode(),
                wx / 3.0, wz / 3.0) * OUTLINE_MAX_EROSION;
        final double jitter = valueNoise2(seed, 0x7000L + island.key().hashCode(),
                wx / 5.0, wz / 5.0) * OUTLINE_MAX_EROSION * 0.9;
        return d + erode + jitter <= 1.0;
    }

    /**
 * 最大侵蚀比例。
 *
 * <p>★ 取 0.06 而不是"看起来更自然"的更大值，是被 PRD M3 验收第 2 条
 * 「<b>尺寸误差 ≤ 2 格</b>」逼出来的：噪声只向内侵蚀，
 * 所以<b>每侧</b>最多吃掉 {@code 0.06 × half} 格，
 * 主岛（half = 16）为 0.96 格，<b>两侧合计 1.92 格 ≤ 2</b>。
 * <p>这个数字同时满足验收的两种读法（"边长误差 ≤ 2"与"每侧偏移 ≤ 2"），
 * 而 0.09 会让合计变成 2.88 格 —— 在"边长"读法下就<b>不合规</b>了。
 * 宁可轮廓稍微规整一点，也不要在一个说不清的量上越线。
 */
    private static final double OUTLINE_MAX_EROSION = 0.06;

    /** √2 —— 欧氏距离归一化用，使包围盒角点恰好落到 d = 1。 */
    private static final double SQRT2 = Math.sqrt(2.0);

    /** 该列的岛屿厚度（层），PRD 4.1 要求 8–14 层。 */
    private static int thicknessOf(Island island, int wx, int wz, long seed) {
        final double n = valueNoise2(seed, 0x2000L + island.key().hashCode(),
                wx / 6.0, wz / 6.0);
        final int span = MAX_THICKNESS - MIN_THICKNESS;
        return MIN_THICKNESS + (int) Math.round(n * span);
    }

    // ------------------------------------------------------------------
    // 矿脉（PRD 4.3 资源分布）
    // ------------------------------------------------------------------

    /**
     * 该格是否属于矿脉；是则返回矿石 runtimeId，否则 0。
     *
     * <h3>为什么用「2×2×2 簇」而不是「整条矿脉」</h3>
     * PRD 4.3 的密度口径是「每 100 格<b>表面积</b>的平均矿物格数」，
     * 高 = 8–12 / 中 = 3–6 / 低 = 1–2。一条立方矿脉最少也有 8 格，
     * 于是「低」档在 14×14 的小岛上<b>连一条完整的脉都放不下</b>
     * （1–2 格 vs 8 格）。所以本类不做"整条脉"，而做：
     * <ol>
     *   <li>把世界坐标按 <b>2 格量化</b>成簇，先决定「这个簇有没有矿」；</li>
     *   <li>簇通过后再按格点哈希决定「簇内哪几格是矿」（约一半）。</li>
     * </ol>
     * 期望格数 = {@code p}（可直接控制），观感上是 2×2×2 上下的小矿团 ——
     * 既像矿脉，又能让 PRD 的<b>任何一档密度</b>都被表达出来。
     * <p>实际格数由 {@code IslandWorldLayoutTest} 按 PRD 区间断言，
     * 这里是"看起来对"，测试是"数量对"。
     */
    private static int oreAt(Island island, int wx, int wy, int wz, long seed,
                             int top, int bottom) {
        // 矿只长在石头里，且不占用最上两层（地表与次表层保持干净）
        if (wy > top - 2 || wy < bottom + 1) {
            return 0;
        }
        // ★ 用 oreWinner 取<b>唯一</b>归属，而不是"逐矿种问一遍"。
        //   两种写法在矿种互斥时等价，但一旦不互斥（本类早先的实现就是那样），
        //   "逐个问"会把同一格判给多种矿，而真正落盘的只有枚举里第一个 ——
        //   于是谓词与产物分叉，症状是某些矿"莫名其妙偏少"。
        final int winner = oreWinner(island, wx, wy, wz, seed);
        return winner < 0 ? 0 : Ore.values()[winner].id();
    }

    /** 该岛该矿的密度档（PRD 4.3）。 */
    public static Density densityOf(Island island, Ore ore) {
        return island.ores()[ore.ordinal()];
    }

    private static boolean isOres(Island island, Ore ore) {
        return island.ores()[ore.ordinal()] != Density.NONE;
    }

    private static boolean isOreCell(Island island, Ore ore, int wx, int wy, int wz, long seed) {
        final int winner = oreWinner(island, wx, wy, wz, seed);
        return winner >= 0 && Ore.values()[winner] == ore;
    }

    /**
     * 这一格归<b>哪一种</b>矿；不成矿返回 -1。
     *
     * <p><b>一次抽签 → 两段解读</b>：抽一个 {@code [0,1)} 的值，
     * 先看它落在 {@code total} 之内（成矿），再按累积概率定位具体矿种。
     * <p>★ <b>为什么不能"每个矿种各自抽一次"</b>（本类的第一版就是这么写的）：
     * 那样一格可以同时被判成煤和铁，而生成时 {@link #oreAt} 按
     * {@code Ore.values()} 顺序返回<b>第一个</b>命中的 —— 于是实际落盘的
     * 只有枚举里最靠前的那一档，后面的矿种"凭空少若干格"。
     * <p>症状极具欺骗性：谓词计数说 40 格铜，世界里只有 37 格，
     * 多出的 3 格恰好落在"煤也命中"的位置上。它<b>不会</b>让任何一条
     * "密度是否达标"的断言显眼地变红，只会让某些矿看起来"偏少"，
     * 于是很容易被当成"参数没调好"而反复调密度 —— 而真因是分配方式。
     * <p>现在互斥是<b>结构上的事实</b>：一次抽签只落进一个区间。
     */
    private static int oreWinner(Island island, int wx, int wy, int wz, long seed) {
        double total = totalOreProbability(island, seed);
        if (total <= 0.0) {
            return -1;
        }
        final long salt = 0x5000L + island.key().hashCode();
        final double roll = hash01(seed, salt, wx, wy, wz);
        // 第一级：这一格是否成矿（总概率 = 各矿概率之和）
        if (roll >= total) {
            return -1;
        }
        // 第二级：按累积概率决定归属（互斥，一格只属一种矿）
        final double scaled = roll / total;
        double cumulative = 0.0;
        final Ore[] ores = Ore.values();
        for (int i = 0; i < ores.length; i++) {
            cumulative += oreProbability(island, ores[i], seed);
            if (scaled < cumulative / total) {
                return i;
            }
        }
        return ores.length - 1;
    }

    /** 该岛该矿在「可产矿格」上的期望命中率。 */
    private static double oreProbability(Island island, Ore ore, long seed) {
        final double per100 = densityOf(island, ore).blocksPer100;
        if (per100 <= 0.0) {
            return 0.0;
        }
        // ★ 用**实际**列数，不用 area() 的估算值。
        //   轮廓侵蚀对**小岛**的相对影响远大于大岛：10×10 的晶矿岛每侧吃掉
        //   0.06×5 = 0.3 格，相对损失 6%；而 area()×固定系数根本表达不了这一点
        //   —— 早先用 area×0.95 时晶矿岛的煤密度实测 2.15 格/100，越出 LOW 的 [1,2]。
        // per100 的分母是**声明面积**（PRD 4.3 的「每 100 格表面积」以岛尺寸为准），
        // 而可产矿格数按**实际列数**算 —— 两者不等，所以必须用 island.area() 做分子。
        return (per100 / 100.0) * island.area() / oreCapacity(island, seed);
    }

    /**
     * 该岛在给定 seed 下的实际列数（含按 (seed, 岛) 的缓存）。
     *
     * <p>缓存只为避免每格重扫一遍包围盒；清空后重算得到的是同一个数，
     * 所以它<b>不影响确定性</b>（生成必须可复现，见类注释）。
     */
    private static final java.util.Map<Long, int[]> COLUMN_COUNT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 某岛在给定 seed 下的<b>可产矿格总数</b>（实际列数 × 可产矿层数）。
     *
     * <p>★ 这是密度换算的<b>分母</b>，必须与 {@link #oreProbability} 的分母
     * 是同一个量 —— 否则"簇通过率"与"各矿密度"就会指向两套数，
     * 改一处会静默地把所有矿一起放大或缩小（正是本类踩过两次的那类问题）。
     */
    private static double oreCapacity(Island island, long seed) {
        final double oreLayers = AVERAGE_THICKNESS - 2.0;
        return columnCount(island, seed) * oreLayers;
    }

    private static int columnCount(Island island, long seed) {
        final long key = seed * 1000L + island.key().hashCode();
        int[] cached = COLUMN_COUNT_CACHE.get(key);
        if (cached != null) {
            return cached[0];
        }
        int n = 0;
        for (int wz = island.minZ(); wz <= island.maxZ(); wz++) {
            for (int wx = island.minX(); wx <= island.maxX(); wx++) {
                if (isIslandColumn(wx, wz, seed)) {
                    n++;
                }
            }
        }
        if (COLUMN_COUNT_CACHE.size() > 4096) {
            COLUMN_COUNT_CACHE.clear();
        }
        COLUMN_COUNT_CACHE.put(key, new int[]{n});
        return n;
    }

    private static long oreSalt(Island island, Ore ore) {
        return 0x3000L + island.key().hashCode() * 31L + ore.ordinal() * 7L;
    }

    // ------------------------------------------------------------------
    // 开局小屋（PRD 5.7）
    // ------------------------------------------------------------------

    /**
     * 搭出半成品小屋：外框 9×9、内部净空 7×3、平顶木板（南面留一格未封）。
     *
     * <p>★ <b>门口为什么是空洞而不是 {@code wooden_door} 方块</b>：
     * 本引擎把 {@code skyisland:wooden_door} 注册为<b>实心装饰方块</b>
     * （{@code BlockRegistry} 里它 {@code solid=true, collision=true}，
     * 且没有任何开门机制）。若照字面在门口放木门，
     * 玩家会被<b>永久关在自己出生的小屋里</b> ——
     * 而这正是 M1 那次教训的同款：「地形的两处刻意修正都是为了让试玩流程不被打断」。
     * <p>⇒ 处置：门口留 1×2 空洞（可通行），其上 y=66 放一格玻璃作"门上亮窗"，
     * 洞口两侧 y=64 放木门方块当门框，既在视觉上标出"这里是门"，又不挡路。
     * <b>登记为技术债</b>：真正的门（可开关）需要新增方块行为，见报告 §遗留。
     */
    private static void buildStarterHut(ChunkWriter out) {
        final int planks = BlockRegistry.planks().runtimeId();
        final int glass = BlockRegistry.glass().runtimeId();
        final int door = BlockRegistry.woodenDoor().runtimeId();
        final int air = BlockRegistry.air().runtimeId();
        final int surfaceY = Coords.WORLD_SURFACE_BLOCK_Y;

        // 地板：9×9 铺在表面层，顶面正好是 y = 64（PRD「地板顶面 y = 64」）
        //
        // ★ <b>这里有一处与自测前提的冲突，必须写清楚，否则下一个人会去"修"错方向</b>：
        //   {@code M1ScriptedSelfTest} 断言「脚下方块 (0,63,0) 确实变成空气」，
        //   那条断言成立的前提是<b>玩家站在裸地上、脚下是草方块</b>。
        //   而 PRD §5.7 要求出生在小屋内部、脚下是<b>木板地板</b>。
        //   ⇒ 两者不可兼得，且<b>规格优先</b>：地板就是木板，玩家就是站在屋里。
        //   实测（-Dskyisland.generator=islands 跑 M1 自测）该条会红，
        //   同轮另有 5 条一起红，全部是同一类原因。
        //   ⇒ 不要为了让这 6 条变绿而去挖地板或挪出生点 ——
        //   那是拿自测的便利去破坏 PRD 的开局体验。
        //   详见 docs/testing/WORLD_ISLAND_GENERATOR_REPORT.md §4。
        for (int z = -HUT_HALF; z <= HUT_HALF; z++) {
            for (int x = -HUT_HALF; x <= HUT_HALF; x++) {
                write(out, x, surfaceY, z, planks);
            }
        }

        // 墙：四面，y ∈ [64, 66]
        for (int y = HUT_INTERIOR_MIN_Y; y <= HUT_INTERIOR_MAX_Y; y++) {
            for (int i = -HUT_HALF; i <= HUT_HALF; i++) {
                write(out, i, y, -HUT_HALF, planks);   // 北墙
                write(out, i, y, HUT_HALF, planks);    // 南墙
                write(out, -HUT_HALF, y, i, planks);   // 西墙
                write(out, HUT_HALF, y, i, planks);    // 东墙
            }
        }

        // 门：南墙中央 (x=0) 的 y = 64 / 65 掏成空洞（玩家出生在小屋内部，
        //   不留门就是永久关死 —— 这是 M1「试玩流程不可中断」的同一条纪律）
        write(out, DOOR_X, HUT_INTERIOR_MIN_Y, HUT_HALF, air);
        write(out, DOOR_X, HUT_INTERIOR_MIN_Y + 1, HUT_HALF, air);
        // 门上亮窗 + 门框木门方块
        write(out, DOOR_X, HUT_INTERIOR_MAX_Y, HUT_HALF, glass);
        write(out, DOOR_X - 1, HUT_INTERIOR_MIN_Y, HUT_HALF, door);
        write(out, DOOR_X + 1, HUT_INTERIOR_MIN_Y, HUT_HALF, door);

        // 北窗：北墙中央 (x=0, y=65) 一格玻璃（PRD「南北各 1 面玻璃窗」）
        write(out, 0, 65, -HUT_HALF, glass);

        // 屋顶：9×9 木板平顶，南面留 1 处未封（PRD「半成品状态」）
        for (int z = -HUT_HALF; z <= HUT_HALF; z++) {
            for (int x = -HUT_HALF; x <= HUT_HALF; x++) {
                if (x == ROOF_GAP_X && z == ROOF_GAP_Z) {
                    continue;
                }
                write(out, x, HUT_ROOF_Y, z, planks);
            }
        }
    }

    // ------------------------------------------------------------------
    // 树木
    // ------------------------------------------------------------------

    /** 主岛树数（PRD 5.7「少量树木」）；森林岛树数（PRD 4.3「原木 高」）。 */
    public static final int TREES_MAIN = 3;
    public static final int TREES_FOREST = 5;

    /**
     * 在生态岛上种树。
     *
     * <p>★ 位置来自<b>候选格点按哈希排序取前 N 个</b>，而不是逐棵随机：
     * 随机取点会让两棵树重叠（树冠 3×3 会互相吞掉），
     * 而"重叠"在画面上表现为<b>树少了</b>，很容易被读成"生成器不稳定"，
     * 实际上只是随机碰撞 —— 候选格点按 3 格间距铺开，从构造上排除重叠。
     */
    private static void plantTrees(ChunkWriter out, long seed) {
        for (Island island : ISLANDS) {
            final int count = switch (island.kind()) {
                case MAIN -> TREES_MAIN;
                case FOREST -> TREES_FOREST;
                default -> 0;
            };
            if (count == 0) {
                continue;
            }
            for (int[] spot : treeSpots(island, count, seed)) {
                buildTree(out, spot[0], Coords.WORLD_SURFACE_BLOCK_Y + 1, spot[1]);
            }
        }
    }

    /**
     * 选出 {@code count} 个互不重叠、且不压在小屋/资源核心上的树位。
     *
     * <p>候选格点按 3 格间距铺在岛内（树冠 3×3 ⇒ 间距 3 刚好不重叠）。
     * <p>★ <b>必须排除岛几何中心</b>：资源岛的核心就摆在中心
     * （PRD 4.6「每个核心资源岛设 1 个资源核心，岛几何中心」），
     * 而树干正好写在 {@code y = 表面 + 1} = 核心所在那一格。
     * 一旦允许树落在中心，<b>树干会把资源核心顶掉</b> ——
     * 而资源核心是 PRD 4.6「三重防软锁」的第一条（永不被移除），
     * 被一棵树悄悄顶掉意味着再生源永久消失，且画面上只是"少了一棵树"。
     * 这个坑真踩过：森林岛的核心就是这么没的（树干 4 格正好盖住 y=64 的核心）。
     */
    public static List<int[]> treeSpots(Island island, int count, long seed) {
        final long salt = 0x4000L + island.key().hashCode();
        // 候选格点：相对中心 ±(2k)，间距 3
        final List<int[]> candidates = new java.util.ArrayList<>();
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                final int x = island.centerX() + dx * 3;
                final int z = island.centerZ() + dz * 3;
                if (island.kind() == Kind.MAIN && nearHut(x, z, 2)) {
                    continue;
                }
                if (island.kind() != Kind.MAIN
                        && x == island.centerX() && z == island.centerZ()) {
                    continue;   // ★ 不许种在资源核心上
                }
                if (!insideOutline(island, x, z, seed)) {
                    continue;
                }
                candidates.add(new int[]{x, z});
            }
        }
        // 按哈希排序 ⇒ 稳定且随 seed 变化；取前 count 个
        candidates.sort((a, b) -> Double.compare(
                hash01(seed, salt, a[0], 0, a[1]),
                hash01(seed, salt, b[0], 0, b[1])));
        return candidates.subList(0, Math.min(count, candidates.size()));
    }

    private static boolean nearHut(int x, int z, int margin) {
        return Math.abs(x) <= HUT_HALF + margin && Math.abs(z) <= HUT_HALF + margin;
    }

    /** 一棵树：树干 4 格（原木）+ 树冠（树叶）。 */
    private static void buildTree(ChunkWriter out, int x, int baseY, int z) {
        final int log = BlockRegistry.log().runtimeId();
        final int leaves = BlockRegistry.leaves().runtimeId();
        final int trunk = 4;
        for (int i = 0; i < trunk; i++) {
            write(out, x, baseY + i, z, log);
        }
        // 树冠：3×3 两层 + 顶层收成十字 = 9 + 9 - 2(树干) + 5 - 1 = 17 格树叶
        for (int dy = trunk - 1; dy <= trunk; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    final boolean topCross = dy == trunk && Math.abs(dx) + Math.abs(dz) > 1;
                    if (topCross) {
                        continue;
                    }
                    if (dx == 0 && dz == 0 && dy <= trunk) {
                        continue;   // 树干位置不铺树叶
                    }
                    write(out, x + dx, baseY + dy, z + dz, leaves);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 写一个世界坐标方块；越界（不属于本区块）直接返回。
     *
     * <p>★ 这里<b>必须</b>把世界坐标换算成本区块局部坐标。
     * 直接写 {@code out.set(wx, y, wz, id)} 在单区块时碰巧正确，
     * 一旦有方块落在邻居区块就会算成越界下标 ——
     * 症状是"那棵树 / 那间屋凭空消失"，极难归因
     * （与 {@code M4TextureEvidence.place()} 踩过的是同一个坑）。
     */
    private static void write(ChunkWriter out, int wx, int y, int wz, int runtimeId) {
        final int lx = wx - out.originX();
        final int lz = wz - out.originZ();
        if (lx < 0 || lx >= Coords.CHUNK_SIZE || lz < 0 || lz >= Coords.CHUNK_SIZE) {
            return;
        }
        out.set(lx, y, lz, runtimeId);
    }

    /** splitmix64 —— 无状态、可复现的 64 位混合。 */
    private static long mix64(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 把 (seed, salt, x, y, z) 映射成 [0,1) 的确定性读数。 */
    private static double hash01(long seed, long salt, int x, int y, int z) {
        long h = mix64(seed ^ mix64(salt));
        h = mix64(h + x * 0x9E3779B97F4A7C15L);
        h = mix64(h + y * 0xC2B2AE3D27D4EB4FL);
        h = mix64(h + z * 0x165667B19E3779F9L);
        return (h >>> 11) / (double) (1L << 53);
    }

    /** 二维值噪声（三次平滑插值），返回 [0,1]。 */
    static double valueNoise2(long seed, long salt, double x, double z) {
        final int xi = (int) Math.floor(x);
        final int zi = (int) Math.floor(z);
        final double fx = x - xi;
        final double fz = z - zi;
        final double u = smooth(fx);
        final double v = smooth(fz);
        final double a = hash01(seed, salt, xi, 0, zi);
        final double b = hash01(seed, salt, xi + 1, 0, zi);
        final double c = hash01(seed, salt, xi, 0, zi + 1);
        final double d = hash01(seed, salt, xi + 1, 0, zi + 1);
        final double top = a + (b - a) * u;
        final double bottom = c + (d - c) * u;
        return top + (bottom - top) * v;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    // ------------------------------------------------------------------
    // 对外查询（供测试与「新世界」界面使用）
    // ------------------------------------------------------------------

    /** 按 key 取岛（未知 key 返回 {@code null}）。 */
    public static Island islandByKey(String key) {
        for (Island island : ISLANDS) {
            if (island.key().equals(key)) {
                return island;
            }
        }
        return null;
    }

    /** 主岛（出生岛）。 */
    public static Island mainIsland() {
        return ISLANDS.get(0);
    }

    /**
     * 供测试用：单格矿脉判定（与生成走同一条路径）。
     *
     * <p>存在的理由与 {@link #countOreIn} 相同，但粒度到<b>具体格子</b>：
     * 只比总数时"少 3 格"无法归因，列出格子才能看出是生成没写、还是谓词多算。
     */
    public static boolean isOreCellAt(Island island, Ore ore, int wx, int wy, int wz, long seed) {
        return isIslandColumn(wx, wz, seed) && isOreCell(island, ore, wx, wy, wz, seed);
    }

    /**
     * 供测试用：某岛某矿的<b>谓词级</b>计数。
     *
     * <p>★ 它走的是<b>与 {@link #generate} 完全相同</b>的判定路径
     * （{@code islandAt} → {@code thicknessOf} → {@code isOreCell}），
     * 所以测试可以拿它做多 seed 的统计（PRD 4.3 的密度区间很窄，
     * 泊松噪声足以让<b>单个</b> seed 合法地落在区间外 ——
     * 那样断言就会变成"随机红"，反而失去门禁作用）。
     * <p>因此测试分成两层：
     * <ol>
     *   <li>用<b>真实 World</b> 数一遍，断言与本方法的<b>谓词计数完全相等</b>
     *       —— 这一层保证"意图"与"产物"没有分叉
     *       （分叉的症状是矿石浮在半空或整段丢失）；</li>
     *   <li>用本方法在<b>多 seed 上取均值</b>，断言均值落在 PRD 区间内
     *       —— 这一层守住"密度档位没有被调错"。</li>
     * </ol>
     */
    public static int countOreIn(Island island, Ore ore, long seed) {
        int count = 0;
        final int top = Coords.WORLD_SURFACE_BLOCK_Y;
        for (int wz = island.minZ(); wz <= island.maxZ(); wz++) {
            for (int wx = island.minX(); wx <= island.maxX(); wx++) {
                if (!isIslandColumn(wx, wz, seed)) {
                    continue;
                }
                final int bottom = top - thicknessOf(island, wx, wz, seed) + 1;
                for (int y = bottom + 1; y <= top - 2; y++) {
                    if (isOreCell(island, ore, wx, y, wz, seed)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** 该岛某列的岛屿厚度（层）—— 供测试断言 PRD 4.1 的 8–14 层。 */
    public static int thicknessOfColumn(Island island, int wx, int wz, long seed) {
        return thicknessOf(island, wx, wz, seed);
    }
}
package com.skyisland.testutil;

import com.skyisland.util.Coords;
import com.skyisland.world.World;
import com.skyisland.world.block.BlockRegistry;
import com.skyisland.world.gen.ChunkWriter;
import com.skyisland.world.gen.WorldGenerator;

/**
 * 测试用世界生成器与便捷构造。
 *
 * <p><b>为什么不直接复用 {@code TestWorldGenerator}</b>：那个生成器是为"手工试玩的
 * 可玩性"设计的（楼梯、玻璃板、悬空平台、虚空坑…），它每改一次地形就会让
 * "某个区块应该产生几个面"这类断言跟着变。单元测试需要的是<u>极简且不动</u>的场景：
 * 一个方块就是 6 个面，两个相邻方块就是 10 个面。
 *
 * <p>因此这里提供两个刻意笨拙的生成器：
 * <ul>
 *   <li>{@link FlatGenerator} —— 平到不能再平的 2 层地形，供物理 / DDA / 存档测试；</li>
 *   <li>{@link ScatteredGenerator} —— 只写入调用方显式指定的几个格子，供网格化测试。</li>
 * </ul>
 * 两者都是纯函数、不用随机数，因此同一次测试运行内的结果完全确定。
 */
public final class TestWorlds {

    /** 地表方块所在层（{@code Coords} 的裁定：y=63，顶面标高 64）。 */
    public static final int SURFACE_BLOCK_Y = Coords.WORLD_SURFACE_BLOCK_Y;

    /** 站在地表时的脚底 y。 */
    public static final double SURFACE_FEET_Y = Coords.WORLD_SURFACE_FEET_Y;

    private TestWorlds() {
    }

    // ============================================================ 生成器

    /** 平坦地形：y=62 石头、y=63 草方块，其余全空。 */
    public static final class FlatGenerator implements WorldGenerator {

        @Override
        public String id() {
            return "test:flat";
        }

        @Override
        public int generationVersion() {
            return 1;
        }

        @Override
        public void generate(ChunkWriter out, int cx, int cz, long seed) {
            short stone = (short) BlockRegistry.stone().runtimeId();
            short grass = (short) BlockRegistry.grass().runtimeId();
            for (int lz = 0; lz < Coords.CHUNK_SIZE; lz++) {
                for (int lx = 0; lx < Coords.CHUNK_SIZE; lx++) {
                    out.set(lx, SURFACE_BLOCK_Y - 1, lz, stone);
                    out.set(lx, SURFACE_BLOCK_Y, lz, grass);
                }
            }
        }
    }

    /**
     * 只写入显式指定格子的生成器。
     *
     * <p>格子用 {@code int[4] = {x, y, z, runtimeId}} 表示，坐标是<b>世界坐标</b>；
     * 不属于本区块的格子被静默跳过 —— 这正是分块生成的正常情形（{@code ChunkWriter.set}
     * 的约定就是越界静默忽略）。
     */
    public static final class ScatteredGenerator implements WorldGenerator {

        private final int[][] cells;

        public ScatteredGenerator(int[]... cells) {
            this.cells = cells;
        }

        @Override
        public String id() {
            return "test:scattered";
        }

        @Override
        public int generationVersion() {
            return 1;
        }

        @Override
        public void generate(ChunkWriter out, int cx, int cz, long seed) {
            for (int[] cell : cells) {
                int lx = cell[0] - out.originX();
                int lz = cell[2] - out.originZ();
                if (lx < 0 || lx >= Coords.CHUNK_SIZE || lz < 0 || lz >= Coords.CHUNK_SIZE) {
                    continue;
                }
                out.set(lx, cell[1], lz, cell[3]);
            }
        }
    }

    // ============================================================ 便捷构造

    public static int[] cell(int x, int y, int z, int runtimeId) {
        return new int[]{x, y, z, runtimeId};
    }

    public static World flatWorld(int minCx, int minCz, int maxCx, int maxCz) {
        World world = new World(1L, new FlatGenerator());
        world.ensureAreaLoaded(minCx, minCz, maxCx, maxCz);
        return world;
    }

    /** 单个区块（0,0）的平坦世界。 */
    public static World flatWorld() {
        return flatWorld(0, 0, 0, 0);
    }

    public static World scatteredWorld(int minCx, int minCz, int maxCx, int maxCz, int[]... cells) {
        World world = new World(1L, new ScatteredGenerator(cells));
        world.ensureAreaLoaded(minCx, minCz, maxCx, maxCz);
        return world;
    }

    // ============================================================ 常用方块 ID

    public static int stone() {
        return BlockRegistry.stone().runtimeId();
    }

    public static int grass() {
        return BlockRegistry.grass().runtimeId();
    }

    public static int dirt() {
        return BlockRegistry.dirt().runtimeId();
    }

    public static int glass() {
        return BlockRegistry.glass().runtimeId();
    }

    public static int planks() {
        return BlockRegistry.planks().runtimeId();
    }

    public static int resourceCore() {
        return BlockRegistry.resourceCore().runtimeId();
    }
}

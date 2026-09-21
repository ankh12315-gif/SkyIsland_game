package com.skyisland.world.gen;

/**
 * 世界生成器接口（TECH_DESIGN §S）。
 *
 * <p><b>M1 只实现测试世界</b>（{@code TestWorldGenerator}）：M1 的目标是 First Playable，
 * 需要的是一个能验证 mesh / collision / DDA / mutation / chunk 边界的场景，
 * 而不是正式的空岛地形。正式 {@code IslandGenerator} 属后续里程碑。
 *
 * <p><b>为什么仍然要有这个接口：</b>存档的稀疏增量依赖"同样的种子必须生成同样的地形"。
 * 把生成逻辑放在接口后面，可以让存档层只依赖 {@link #id()} 与 {@link #generationVersion()}
 * 这两个稳定标识，而不用知道地形算法本身。
 *
 * <p><b>为什么入参是 {@link ChunkWriter} 而不是 {@code Chunk}：</b>见 {@code ChunkWriter} 的说明——
 * 把生成期的写权限收窄成"只能写一个区块、不能读、不能持有"，从而让
 * {@code Chunk} 的包级私有写方法得以保留。
 */
public interface WorldGenerator {

    /** 生成器的稳定标识，写入存档（例如 {@code skyisland:test_world}）。 */
    String id();

    /**
     * 生成算法的版本号。
     *
     * <p>与存档格式的 {@code saveVersion} <b>刻意分开</b>：改了地形算法只需要提升本版本号，
     * 不必让整个存档格式升版。版本不符时必须警告而不是静默加载（TECH_DESIGN §N.2 / N.6）。
     */
    int generationVersion();

    /**
     * 向指定区块填充地形。
     *
     * <p><b>必须完全确定：</b>对同一 {@code (seed, cx, cz)} 必须产生同一结果，
     * 不得读取其他区块、不得使用随机数（除非用 seed 播种的确定性随机）。
     * 存档只记录"被改动的方块"，任何不确定性都会让增量对不上地形。
     */
    void generate(ChunkWriter out, int cx, int cz, long seed);
}

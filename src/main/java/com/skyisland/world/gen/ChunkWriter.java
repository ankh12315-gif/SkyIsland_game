package com.skyisland.world.gen;

/**
 * 生成期只写门面。
 *
 * <p><b>为什么要多这一层：</b>{@code Chunk} 的写方法被刻意设为包级私有，
 * 使"所有 Block 改动必须走 World Mutation API"成为<u>编译期</u>约束而不是口头约定
 * （M1 指令 B3）。但生成器需要往区块里填地形 —— 如果为此把 {@code Chunk.set} 公开，
 * 写权限就漏了。
 *
 * <p>因此生成器拿到的不是 {@code Chunk}，而是这个**只能写、不能读、也不能持有**的门面：
 * <ul>
 *   <li>没有读取方法 —— 生成逻辑应当只依赖 (seed, cx, cz)，读邻居会让生成变得不可确定；</li>
 *   <li>没有暴露 {@code Chunk} —— 生成器无法把新区块泄漏到注册表之外；</li>
 *   <li>写下标由 {@link #set} 自行做范围校验，越界是<u>静默忽略</u>而不是抛异常，
 *       因为"这个方块不属于本区块"在分块生成中是正常情形。</li>
 * </ul>
 */
public interface ChunkWriter {

    int chunkX();

    int chunkZ();

    /** 本区块原点的世界 x 坐标（= {@code chunkX * 16}）。 */
    int originX();

    /** 本区块原点的世界 z 坐标（= {@code chunkZ * 16}）。 */
    int originZ();

    /**
     * 写入一个体素。
     *
     * @param lx 区块内局部坐标 [0,15]
     * @param ly 区块内高度 [0,127]
     * @param lz 区块内局部坐标 [0,15]
     * @param runtimeId 方块运行时 ID
     */
    void set(int lx, int ly, int lz, int runtimeId);
}

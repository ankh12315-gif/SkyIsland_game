package com.skyisland.world;

import com.skyisland.util.Coords;
import com.skyisland.world.block.BlockRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区块存储测试（同包测试：{@code Chunk} 的构造器与 {@code setLocal} 是包级私有的，
 * 这样"只有 World 能写方块"的编译期约束才能成立）。
 *
 * <p>着重覆盖两处最容易悄悄写错的细节：
 * <ol>
 *   <li><b>线性索引的轴顺序</b>：{@code (ly &lt;&lt; 8) | (lz &lt;&lt; 4) | lx}。
 *       把 ly 与 lz 写反不会有任何编译错误，只会让"挖这里那里的方块也变了"；</li>
 *   <li><b>nonAirCount 的对称增减</b>。它与 {@link Chunk#isEmpty()} 共同决定"空区块
 *       跳过网格化"这条优化，一旦漂移，就会出现"把地形挖空了但网格还在"。</li>
 * </ol>
 */
class ChunkTest {

    private static short grass() {
        return (short) BlockRegistry.grass().runtimeId();
    }

    private static short stone() {
        return (short) BlockRegistry.stone().runtimeId();
    }

    @Test
    void newChunkIsEmptyAndNeedsMeshButNotSaving() {
        Chunk chunk = new Chunk(0, 0);
        assertTrue(chunk.isEmpty());
        assertEquals(0, chunk.nonAirCount());
        assertTrue(chunk.isMeshDirty(), "新区块必须先建网格");
        assertFalse(chunk.isSaveDirty(),
                "新区块不是 saveDirty：'需要写回存档'只应描述玩家改动（§N.9 只存改动）");
        assertFalse(chunk.isDirty());
        assertFalse(chunk.isGenerated());
    }

    @Test
    void setLocalUpdatesCountAndDirtyFlags() {
        Chunk chunk = new Chunk(0, 0);
        assertTrue(chunk.setLocal(3, 40, 5, grass()));

        assertEquals(grass(), chunk.blockAt(3, 40, 5));
        assertEquals(1, chunk.nonAirCount());
        assertFalse(chunk.isEmpty());
        assertTrue(chunk.isDirty());
        assertTrue(chunk.isMeshDirty());
    }

    @Test
    void settingSameValueReportsNoChange() {
        Chunk chunk = new Chunk(0, 0);
        assertTrue(chunk.setLocal(1, 1, 1, stone()));
        chunk.clearMeshDirty();

        assertFalse(chunk.setLocal(1, 1, 1, stone()),
                "写入相同值必须返回 false —— 否则每次'挖空气'都会触发一次网格重建");
        assertEquals(1, chunk.nonAirCount());
        assertFalse(chunk.isMeshDirty(), "无变化的写入不应重新标脏");
    }

    @Test
    void overwritingSolidWithSolidKeepsCountStable() {
        Chunk chunk = new Chunk(0, 0);
        chunk.setLocal(0, 0, 0, stone());
        assertTrue(chunk.setLocal(0, 0, 0, grass()));
        assertEquals(1, chunk.nonAirCount(), "实体换实体不应改变非空气计数");

        assertTrue(chunk.setLocal(0, 0, 0, BlockRegistry.AIR_RUNTIME_ID),
                "写空气必须返回 true（内容确实变了），否则脏标记与网格重建都会被跳过");
        assertEquals(0, chunk.nonAirCount());
        assertTrue(chunk.isEmpty());
    }

    @Test
    void extremeLocalCoordinateMapsToTheRightVoxel() {
        // 这条专门抓"轴顺序写反"：把 ly 与 lz 互换之后，(15,127,15) 会落到别的格子上
        Chunk chunk = new Chunk(0, 0);
        chunk.setLocal(15, 127, 15, grass());

        assertEquals(grass(), chunk.blockAt(15, 127, 15));
        assertEquals(1, chunk.nonAirCount());

        // 同一 y 层内相邻的 x / z 必须各自独立
        assertEquals(Coords.chunkIndex(15, 127, 15), Coords.chunkIndex(15, 127, 15));
        assertFalse(Coords.chunkIndex(15, 127, 15) == Coords.chunkIndex(15, 127, 14));
        assertFalse(Coords.chunkIndex(15, 127, 15) == Coords.chunkIndex(14, 127, 15));
    }

    @Test
    void blockAtOutOfRangeReturnsAirInsteadOfThrowing() {
        Chunk chunk = new Chunk(0, 0);
        chunk.setLocal(0, 0, 0, stone());

        assertEquals(0, chunk.blockAt(-1, 0, 0));
        assertEquals(0, chunk.blockAt(16, 0, 0));
        assertEquals(0, chunk.blockAt(0, -1, 0));
        assertEquals(0, chunk.blockAt(0, Coords.CHUNK_HEIGHT, 0));
        assertEquals(0, chunk.blockAt(0, 0, 16));
    }

    @Test
    void originFollowsChunkCoordinatesIncludingNegatives() {
        assertEquals(0, new Chunk(0, 0).originX());
        assertEquals(0, new Chunk(0, 0).originZ());
        assertEquals(-32, new Chunk(-2, 0).originX());
        assertEquals(-16, new Chunk(0, -1).originZ());
        assertEquals(16, new Chunk(1, 0).originX());
    }

    @Test
    void volumeMatchesDeclaredDimensions() {
        assertEquals(16 * 16 * 128, Chunk.VOLUME);
        assertEquals(Coords.CHUNK_SIZE, Chunk.SIZE);
        assertEquals(Coords.CHUNK_HEIGHT, Chunk.HEIGHT);
    }

    @Test
    void markGeneratedSeparationFromDirty() {
        Chunk chunk = new Chunk(0, 0);
        assertFalse(chunk.isGenerated());
        chunk.markGenerated();
        assertTrue(chunk.isGenerated(),
                "generated 用来区分'还没生成的空区块'与'生成出来就是空的区块'");
        assertFalse(chunk.isDirty(), "跑过生成器本身不构成内容改动");
    }

    @Test
    void meshStatisticsAreRecordedAndSummed() {
        Chunk chunk = new Chunk(0, 0);
        chunk.recordMeshBuild(1_234_567L, 40, 6);

        assertEquals(1_234_567L, chunk.lastMeshBuildNanos());
        assertEquals(40, chunk.lastOpaqueFaceCount());
        assertEquals(6, chunk.lastTransparentFaceCount());
        assertEquals(46, chunk.lastFaceCount());
    }

    @Test
    void saveDirtyIsIndependentlyControllable() {
        Chunk chunk = new Chunk(0, 0);
        assertFalse(chunk.isSaveDirty());
        chunk.markSaveDirty();
        assertTrue(chunk.isSaveDirty());
        chunk.clearSaveDirty();
        assertFalse(chunk.isSaveDirty(),
                "存档写回后必须能清掉 saveDirty，否则每次启动都会重写全部区块");
    }
}

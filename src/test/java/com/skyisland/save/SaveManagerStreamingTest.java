package com.skyisland.save;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.Chunk;
import com.skyisland.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ M4-S8a：存档侧为<b>流式加载</b>新增的两条能力的守卫。
 *
 * <h3>为什么必须有这两条能力</h3>
 * <p>区块变成按需加载之后，存档遇到了一个"全世界常驻"年代不存在的矛盾：
 * <ol>
 *   <li>{@link SaveManager#save} 遍历的是 {@code World#saveDirtyChunks()}，
 *       它只看<b>当前已加载</b>的区块 —— 区块一旦被卸载，玩家的改动就从这份列表里消失了；</li>
 *   <li>读档若仍是"启动时统一回放一遍"，那么远在加载半径之外的改动
 *       会因为"区块还没生成"被整条跳过。</li>
 * </ol>
 * 于是必须补上：{@code saveChunk}（卸载前落盘）与 {@code applyChunkDelta}（按坐标回放）。
 * 少任何一条的表现都是<u>同一句</u>玩家报告："我建的东西没了"，
 * 而两者分别对应不同的根因 —— 所以必须分开测，不能只测"端到端能存能读"。
 */
class SaveManagerStreamingTest {

    private static final String WORLD_NAME = "stream-world";

    @TempDir
    Path root;

    private SaveManager manager() {
        return new SaveManager(root, WORLD_NAME);
    }

    private static World flatWorld() {
        return TestWorlds.flatWorld(0, 0, 0, 0);
    }

    // ============================================================ 文件名解析

    @Test
    void chunkFileNameRoundTripsIncludingNegativeCoordinates() {
        assertEquals(new SaveFormat.ChunkCoordinate(3, -4),
                SaveFormat.parseChunkFileName(SaveFormat.chunkFileName(3, -4)),
                "负坐标必须能原样往返 —— 世界出生点在 (0,0)，负方向的区块占一半");
        assertEquals(new SaveFormat.ChunkCoordinate(0, 0),
                SaveFormat.parseChunkFileName("c.0.0.bin"));
        assertEquals("c.-12.7.bin", SaveFormat.chunkFileName(-12, 7));
    }

    @Test
    void nonChunkFileNamesAreRejectedInsteadOfThrowing() {
        assertNull(SaveFormat.parseChunkFileName("level.json"));
        assertNull(SaveFormat.parseChunkFileName("c.a.b.bin"));
        assertNull(SaveFormat.parseChunkFileName("c.1.bin"), "只有一个坐标段");
        assertNull(SaveFormat.parseChunkFileName("c.1..bin"), "坐标段为空");
        assertNull(SaveFormat.parseChunkFileName("c.1.2.dat"), "扩展名不对");
        assertNull(SaveFormat.parseChunkFileName(null));
    }

    // ============================================================ 卸载前落盘

    @Test
    void saveChunkWritesNothingForACleanChunk() {
        World world = flatWorld();
        SaveManager manager = manager();
        Chunk chunk = world.chunkAt(0, 0);
        assertFalse(chunk.isSaveDirty());

        assertTrue(manager.saveChunk(world, chunk).success());
        assertEquals(0, manager.saveChunk(world, chunk).chunksWritten(),
                "干净区块不许产生文件 —— 否则「走到哪都写一次磁盘」会让流式变成 I/O 抖动源");
        assertTrue(manager.savedChunkCoords().isEmpty());
    }

    @Test
    void saveChunkPersistsTheEditAndClearsTheDirtyFlag() {
        World world = flatWorld();
        SaveManager manager = manager();
        assertTrue(world.breakBlock(3, TestWorlds.SURFACE_BLOCK_Y, 4,
                World.MutationCause.PLAYER_BREAK).success());
        Chunk chunk = world.chunkAt(0, 0);
        assertTrue(chunk.isSaveDirty());

        SaveResult result = manager.saveChunk(world, chunk);
        assertTrue(result.success());
        assertEquals(1, result.chunksWritten());
        assertFalse(chunk.isSaveDirty(), "落盘后必须清脏，否则下次保存会重复写同一个文件");

        assertEquals(1, manager.savedChunkCoords().size());
        assertEquals(new SaveFormat.ChunkCoordinate(0, 0), manager.savedChunkCoords().get(0));
    }

    // ============================================================ 按坐标回放

    @Test
    void applyChunkDeltaIsZeroWhenThatChunkWasNeverModified() {
        assertEquals(0, manager().applyChunkDelta(flatWorld(), 7, 7),
                "没有增量文件是<b>最常见</b>的路径（绝大多数区块从未被玩家改过），必须静默返回 0");
    }

    /**
     * ★ 端到端的那一条：玩家的改动在"卸载 → 重新生成"之后仍然在。
     *
     * <p>这是流式加载<b>唯一</b>可能悄悄丢数据的地方，而且丢的方式很隐蔽：
     * 地形看起来完全正常（重新生成是确定的），只有玩家挖过的那一格变了回去。
     */
    @Test
    void theEditSurvivesAnUnloadAndRegenerateRoundTrip() {
        World world = flatWorld();
        SaveManager manager = manager();

        int x = 3;
        int y = TestWorlds.SURFACE_BLOCK_Y;
        int z = 5;
        assertTrue(world.breakBlock(x, y, z, World.MutationCause.PLAYER_BREAK).success());
        Chunk victim = world.chunkAt(0, 0);
        manager.saveChunk(world, victim);

        // 卸载 → 全新区块按生成器重新生成（与卸载再走回来的路径完全一致）
        world.unloadChunk(0, 0);
        assertNull(world.chunkAt(0, 0));
        World reloaded = flatWorld();
        assertEquals(TestWorlds.grass(), reloaded.blockIdAt(x, y, z),
                "前提：新生成的区块里那一格是草方块（尚未回放差异）");

        // 回放：这一步在真实链路里由 World.ChunkDeltaSource 在 getOrLoadChunk 内触发
        World target = new World(1L, new TestWorlds.FlatGenerator());
        target.setChunkDeltaSource((w, cx, cz) -> manager.applyChunkDelta(w, cx, cz));
        target.getOrLoadChunk(0, 0);

        assertEquals(0, target.blockIdAt(x, y, z),
                "★ 挖掉的那一格必须在重新生成后仍然是空的 —— 否则流式加载就是在悄悄丢玩家的进度");
        assertEquals(1, target.deltaAppliedCount());
        assertFalse(target.chunkAt(0, 0).isSaveDirty(),
                "SAVE_LOAD 来源不得把区块标成脏，否则每次加载都会把它再写回磁盘一次");
    }

    @Test
    void aCorruptDeltaFileDegradesToGeneratedTerrainInsteadOfThrowing() {
        World world = flatWorld();
        SaveManager manager = manager();
        assertTrue(world.breakBlock(3, TestWorlds.SURFACE_BLOCK_Y, 4,
                World.MutationCause.PLAYER_BREAK).success());
        manager.saveChunk(world, world.chunkAt(0, 0));

        Path file = manager.worldDirectory().resolve(SaveFormat.CHUNK_DIR)
                .resolve(SaveFormat.chunkFileName(0, 0));
        assertNotNull(file);
        try {
            java.nio.file.Files.write(file, new byte[]{'x', 'y', 'z'});
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }

        // ★ 先生成再回放：真实链路里回放就发生在 getOrLoadChunk 内部，
        //   区块此刻已经存在了。不先生成的话读到 0 只能证明"区块没加载"。
        World target = new World(1L, new TestWorlds.FlatGenerator());
        target.getOrLoadChunk(0, 0);
        assertEquals(0, manager.applyChunkDelta(target, 0, 0),
                "§N.6 的降级口径：单个区块的增量损坏 → 回到生成态，不抛异常、不放弃整个存档");
        assertEquals(TestWorlds.grass(), target.blockIdAt(3, TestWorlds.SURFACE_BLOCK_Y, 4),
                "损坏的增量不得污染已生成的地形");
    }
}

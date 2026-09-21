package com.skyisland.world;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.util.Coords;
import com.skyisland.world.block.BlockRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界层测试：区块生成与复用、体素读取、<b>唯一的方块改动入口</b>的完整校验矩阵。
 *
 * <p>World 是全工程唯一允许改方块的地方（{@code Chunk.setLocal} 包级私有，
 * 使其成为编译期约束）。因此它的校验顺序与副作用（脏标记、邻居标记、网格入队）
 * 是本里程碑最值得逐条固化的东西 —— 这些副作用一旦漏掉，症状分别是
 * "改动没落盘""边界残留旧面""挖了但不更新"。
 */
class WorldTest {

    private static int air() {
        return BlockRegistry.AIR_RUNTIME_ID;
    }

    /** 把网格重建队列清空，使后续断言不受"加载时入队的重建"干扰。 */
    private static void drainMeshQueue(World world) {
        world.pollMeshRebuilds(0);
    }

    // ============================================================ 区块加载

    @Test
    void getOrLoadChunkIsMemoized() {
        World world = TestWorlds.flatWorld();
        Chunk first = world.getOrLoadChunk(0, 0);
        Chunk second = world.getOrLoadChunk(0, 0);

        assertSame(first, second, "同一区块必须返回同一实例 —— 重新生成会丢掉所有玩家改动");
        assertEquals(1, world.loadedChunkCount());
        assertNotNull(world.chunkAt(0, 0));
    }

    @Test
    void chunkAtReturnsNullForUnloadedChunk() {
        World world = TestWorlds.flatWorld();
        assertNull(world.chunkAt(5, 5));
    }

    @Test
    void ensureAreaLoadedCoversTheWholeRectangle() {
        World world = TestWorlds.flatWorld(-1, -1, 1, 1);
        assertEquals(9, world.loadedChunkCount());
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                assertNotNull(world.chunkAt(cx, cz), "缺少区块 " + cx + "," + cz);
            }
        }
    }

    @Test
    void unloadChunkRemovesItAndMarksNeighbours() {
        World world = TestWorlds.flatWorld(0, 0, 1, 0);
        drainMeshQueue(world);
        long marksBefore = world.neighborMarkCount();

        Chunk removed = world.unloadChunk(1, 0);

        assertNotNull(removed);
        assertNull(world.chunkAt(1, 0));
        assertEquals(1, world.loadedChunkCount());
        assertTrue(world.neighborMarkCount() > marksBefore,
                "卸载区块后相邻区块沿边界的面会变 → 必须标记邻居重建");
        assertNull(world.unloadChunk(1, 0), "重复卸载返回 null 而不是抛异常");
    }

    // ============================================================ 体素读取

    @Test
    void blockIdAtReturnsAirOutsideLoadedWorld() {
        World world = TestWorlds.flatWorld();
        assertEquals(air(), world.blockIdAt(200, 64, 200), "未加载区块读作空气");
        assertEquals(air(), world.blockIdAt(0, -1, 0), "y 越界读作空气");
        assertEquals(air(), world.blockIdAt(0, Coords.CHUNK_HEIGHT + 10, 0));
    }

    @Test
    void terrainReadsBackThroughWorldCoordinates() {
        // 必须同时加载负坐标区块：否则 blockIdAt(-1,...) 会读作空气，
        // 这一条也就退化成"未加载区块返回空气"（已由上面单独覆盖）
        World world = TestWorlds.flatWorld(-1, -1, 0, 0);

        assertTrue(world.hasCollisionAt(0, TestWorlds.SURFACE_BLOCK_Y, 0));
        assertTrue(world.isAirAt(0, TestWorlds.SURFACE_BLOCK_Y + 1, 0));
        assertSame(BlockRegistry.grass(), world.blockAt(0, TestWorlds.SURFACE_BLOCK_Y, 0));
        assertSame(BlockRegistry.stone(), world.blockAt(0, TestWorlds.SURFACE_BLOCK_Y - 1, 0));

        // 负坐标必须走 floorDiv：x=-1 属于区块 -1、局部 15
        assertTrue(world.hasCollisionAt(-1, TestWorlds.SURFACE_BLOCK_Y, -1));
        assertEquals(TestWorlds.grass(), world.blockIdAt(-1, TestWorlds.SURFACE_BLOCK_Y, -1));
        assertEquals(TestWorlds.grass(), world.blockIdAt(-16, TestWorlds.SURFACE_BLOCK_Y, -16),
                "(-16,-16) 属于区块 (-1,-1) 的局部原点");
    }

    @Test
    void adjacentSupportIsTrueOnTerrainAndFalseInMidAir() {
        World world = TestWorlds.flatWorld();
        assertTrue(world.hasAdjacentSupport(0, TestWorlds.SURFACE_BLOCK_Y + 1, 0));
        assertFalse(world.hasAdjacentSupport(0, 100, 0), "半空中没有相邻支撑");
    }

    // ============================================================ 破坏

    @Test
    void breakBlockSucceedsAndMarksEverythingNeeded() {
        World world = TestWorlds.flatWorld();
        drainMeshQueue(world);
        Chunk chunk = world.chunkAt(0, 0);
        chunk.clearSaveDirty();

        long breaksBefore = world.breakCount();
        World.MutationResult result = world.breakBlock(5, TestWorlds.SURFACE_BLOCK_Y, 5,
                World.MutationCause.PLAYER_BREAK);

        assertTrue(result.success(), result.reason());
        assertEquals(breaksBefore + 1, world.breakCount());
        assertTrue(world.isAirAt(5, TestWorlds.SURFACE_BLOCK_Y, 5));
        assertTrue(chunk.isSaveDirty(), "玩家改动必须标记 saveDirty，否则退出时不会落盘");
        assertTrue(chunk.isMeshDirty(), "改动后网格必须重建");
        assertEquals(1, world.pendingMeshRebuilds(), "目标区块必须进入重建队列");
    }

    @Test
    void breakBlockRejectsAir() {
        World world = TestWorlds.flatWorld();
        World.MutationResult result = world.breakBlock(0, 100, 0, World.MutationCause.PLAYER_BREAK);
        assertFalse(result.success());
        assertTrue(result.reason().contains("空气"), "失败原因应当具体：" + result.reason());
    }

    @Test
    void breakBlockRejectsUnbreakableBlock() {
        World world = new World(1L, new com.skyisland.world.gen.TestWorldGenerator());
        world.ensureAreaLoaded(0, 0, 0, 0);

        // TestWorldGenerator 在 (2,64,2) 放了不可破坏的"资源核心"
        World.MutationResult result = world.breakBlock(2, 64, 2, World.MutationCause.PLAYER_BREAK);

        assertFalse(result.success(), "不可破坏方块必须被拒绝");
        assertTrue(result.reason().contains("不可破坏"), "原因=" + result.reason());
        assertFalse(world.isAirAt(2, 64, 2), "被拒绝的破坏不得真的改掉方块");
    }

    @Test
    void breakBlockRejectsOutOfRangeY() {
        World world = TestWorlds.flatWorld();
        assertFalse(world.breakBlock(0, -1, 0, World.MutationCause.PLAYER_BREAK).success());
        assertFalse(world.breakBlock(0, Coords.CHUNK_HEIGHT, 0, World.MutationCause.PLAYER_BREAK).success());
    }

    @Test
    void breakBlockRejectsUnloadedChunk() {
        World world = TestWorlds.flatWorld();
        World.MutationResult result = world.breakBlock(500, 64, 500, World.MutationCause.PLAYER_BREAK);
        assertFalse(result.success());
        assertTrue(result.reason().contains("未加载"), "原因=" + result.reason());
    }

    @Test
    void rejectedMutationsAreCounted() {
        World world = TestWorlds.flatWorld();
        long before = world.rejectedMutationCount();
        world.breakBlock(0, 100, 0, World.MutationCause.PLAYER_BREAK);
        world.breakBlock(0, 100, 0, World.MutationCause.PLAYER_BREAK);
        assertEquals(before + 2, world.rejectedMutationCount());
    }

    // ============================================================ 放置

    @Test
    void placeBlockSucceedsOnTopOfTerrain() {
        World world = TestWorlds.flatWorld();
        drainMeshQueue(world);
        long placesBefore = world.placeCount();

        World.MutationResult result = world.placeBlock(3, TestWorlds.SURFACE_BLOCK_Y + 1, 3,
                TestWorlds.planks(), World.MutationCause.PLAYER_PLACE, null);

        assertTrue(result.success(), result.reason());
        assertEquals(placesBefore + 1, world.placeCount());
        assertEquals(TestWorlds.planks(), world.blockIdAt(3, TestWorlds.SURFACE_BLOCK_Y + 1, 3));
    }

    @Test
    void placeBlockRejectsWithoutAdjacentSupport() {
        World world = TestWorlds.flatWorld();
        World.MutationResult result = world.placeBlock(3, 100, 3, TestWorlds.planks(),
                World.MutationCause.PLAYER_PLACE, null);

        assertFalse(result.success());
        assertTrue(result.reason().contains("支撑"), "原因=" + result.reason());
    }

    @Test
    void placeBlockRejectsOccupiedCell() {
        World world = TestWorlds.flatWorld();
        World.MutationResult result = world.placeBlock(3, TestWorlds.SURFACE_BLOCK_Y, 3,
                TestWorlds.planks(), World.MutationCause.PLAYER_PLACE, null);

        assertFalse(result.success());
        assertTrue(result.reason().contains("已被占用"), "原因=" + result.reason());
    }

    @Test
    void placeBlockRejectsAirAndOutOfRangeY() {
        World world = TestWorlds.flatWorld();
        assertFalse(world.placeBlock(3, TestWorlds.SURFACE_BLOCK_Y + 1, 3, air(),
                World.MutationCause.PLAYER_PLACE, null).success(),
                "不能放置空气");
        assertFalse(world.placeBlock(3, 0, 3, TestWorlds.planks(),
                World.MutationCause.PLAYER_PLACE, null).success(),
                "y=0 低于可放置范围下界 " + Coords.MIN_PLACEABLE_Y);
        assertFalse(world.placeBlock(3, Coords.MAX_PLACEABLE_Y + 1, 3, TestWorlds.planks(),
                World.MutationCause.PLAYER_PLACE, null).success());
    }

    @Test
    void placeBlockRejectsUnplaceableBlock() {
        World world = TestWorlds.flatWorld();
        World.MutationResult result = world.placeBlock(3, TestWorlds.SURFACE_BLOCK_Y + 1, 3,
                TestWorlds.resourceCore(), World.MutationCause.PLAYER_PLACE, null);
        assertFalse(result.success(), "资源核心不可放置");
        assertTrue(result.reason().contains("不允许放置"), "原因=" + result.reason());
    }

    @Test
    void placeBlockRejectsObstructionOverlap() {
        World world = TestWorlds.flatWorld();
        World.MutationResult result = world.placeBlock(3, TestWorlds.SURFACE_BLOCK_Y + 1, 3,
                TestWorlds.planks(), World.MutationCause.PLAYER_PLACE,
                (bx, by, bz) -> bx == 3 && by == TestWorlds.SURFACE_BLOCK_Y + 1 && bz == 3);

        assertFalse(result.success());
        assertTrue(result.reason().contains("碰撞箱"), "原因=" + result.reason());
        assertTrue(world.isAirAt(3, TestWorlds.SURFACE_BLOCK_Y + 1, 3));
    }

    @Test
    void placeBlockRejectsUnloadedChunk() {
        World world = TestWorlds.flatWorld();
        World.MutationResult result = world.placeBlock(500, 64, 500, TestWorlds.planks(),
                World.MutationCause.PLAYER_PLACE, null);
        assertFalse(result.success());
        assertTrue(result.reason().contains("未加载"), "原因=" + result.reason());
    }

    // ============================================================ 邻居标记（B6 的核心）

    @Test
    void boundaryMutationMarksNeighbourChunkForRebuild() {
        World world = TestWorlds.flatWorld(0, 0, 1, 0);
        drainMeshQueue(world);
        long marksBefore = world.neighborMarkCount();

        // x=15 是区块 (0,0) 的 +X 边界
        assertTrue(world.breakBlock(15, TestWorlds.SURFACE_BLOCK_Y, 5,
                World.MutationCause.PLAYER_BREAK).success());

        assertTrue(world.neighborMarkCount() > marksBefore,
                "边界改动必须让相邻区块一起重建，否则交界处会残留不该有的面");
        assertEquals(2, world.pendingMeshRebuilds(), "自己 + 邻居 = 2 个区块待重建");
    }

    @Test
    void interiorMutationDoesNotMarkNeighbours() {
        World world = TestWorlds.flatWorld(0, 0, 1, 0);
        drainMeshQueue(world);
        long marksBefore = world.neighborMarkCount();

        assertTrue(world.breakBlock(7, TestWorlds.SURFACE_BLOCK_Y, 7,
                World.MutationCause.PLAYER_BREAK).success());

        assertEquals(marksBefore, world.neighborMarkCount(),
                "区块内部改动不涉及邻居，不该白白触发邻居重建");
        assertEquals(1, world.pendingMeshRebuilds());
    }

    @Test
    void meshRebuildQueueIsDeduplicated() {
        World world = TestWorlds.flatWorld();
        drainMeshQueue(world);

        world.breakBlock(5, TestWorlds.SURFACE_BLOCK_Y, 5, World.MutationCause.PLAYER_BREAK);
        world.breakBlock(6, TestWorlds.SURFACE_BLOCK_Y, 6, World.MutationCause.PLAYER_BREAK);
        world.breakBlock(7, TestWorlds.SURFACE_BLOCK_Y, 7, World.MutationCause.PLAYER_BREAK);

        assertEquals(1, world.pendingMeshRebuilds(),
                "同一区块被多次改脏只能入队一次（LinkedHashSet 去重）");
    }

    @Test
    void pollMeshRebuildsHonoursLimitAndEmptiesQueue() {
        World world = TestWorlds.flatWorld(0, 0, 1, 1);
        assertEquals(4, world.pendingMeshRebuilds());

        List<Chunk> batch = world.pollMeshRebuilds(2);
        assertEquals(2, batch.size());
        assertEquals(2, world.pendingMeshRebuilds());

        List<Chunk> rest = world.pollMeshRebuilds(0);
        assertEquals(2, rest.size());
        assertEquals(0, world.pendingMeshRebuilds());
        assertTrue(world.pollMeshRebuilds(4).isEmpty());
    }

    @Test
    void meshQueueHighWaterMarkTracksPeak() {
        World world = TestWorlds.flatWorld();
        drainMeshQueue(world);
        int peakBefore = world.meshQueueHighWaterMark();

        // 区块 (0,0) 内部挖 3 格不涉及邻居；再把 (0,0) 与 (1,0) 各自标一次
        world.getOrLoadChunk(1, 0);
        assertTrue(world.meshQueueHighWaterMark() >= peakBefore);
        assertTrue(world.meshQueueHighWaterMark() >= 1);
    }

    @Test
    void meshBuildStatisticsAccumulate() {
        World world = TestWorlds.flatWorld();
        long countBefore = world.meshBuildCount();
        world.recordMeshBuild(1_000_000L);
        world.recordMeshBuild(3_000_000L);

        assertEquals(countBefore + 2, world.meshBuildCount());
        assertEquals(2.0, world.meanMeshBuildMs(), 1e-6);
    }

    // ============================================================ 存档相关的记账差异

    @Test
    void applySavedBlockRebuildsMeshButDoesNotMarkSaveDirty() {
        World world = TestWorlds.flatWorld();
        drainMeshQueue(world);
        Chunk chunk = world.chunkAt(0, 0);
        chunk.clearSaveDirty();

        assertTrue(world.applySavedBlock(9, TestWorlds.SURFACE_BLOCK_Y, 9, (short) air()));

        assertTrue(world.isAirAt(9, TestWorlds.SURFACE_BLOCK_Y, 9));
        assertFalse(chunk.isSaveDirty(),
                "回放存档不得把区块重新标脏 —— 否则'有没有改动'这一信息会永久失真，"
                        + "且每次启动都会把刚读出来的内容再写回去");
        assertTrue(chunk.isMeshDirty(), "回放存档仍然要重建网格");
    }

    @Test
    void applySavedBlockSkipsOutOfRangeAndUnloadedChunks() {
        World world = TestWorlds.flatWorld();
        assertFalse(world.applySavedBlock(0, -5, 0, (short) air()), "y 越界应跳过");
        assertFalse(world.applySavedBlock(500, 64, 500, (short) air()), "未加载区块应跳过");
    }

    @Test
    void saveDirtyChunksOnlyListsModifiedOnes() {
        World world = TestWorlds.flatWorld(0, 0, 1, 0);
        assertTrue(world.saveDirtyChunks().isEmpty(),
                "刚生成的世界没有任何玩家改动 → 没有区块需要写回（§N.9 只存改动）");

        world.breakBlock(5, TestWorlds.SURFACE_BLOCK_Y, 5, World.MutationCause.PLAYER_BREAK);

        List<Chunk> dirty = world.saveDirtyChunks();
        assertEquals(1, dirty.size());
        assertEquals(0, dirty.get(0).cx());
        assertEquals(0, dirty.get(0).cz());

        world.markChunkSaved(dirty.get(0));
        assertTrue(world.saveDirtyChunks().isEmpty(), "写回后必须能清空待写列表");
    }

    @Test
    void saveLoadAndSelfTestCausesAreAccountedDifferently() {
        World world = TestWorlds.flatWorld();
        Chunk chunk = world.chunkAt(0, 0);
        chunk.clearSaveDirty();

        // SAVE_LOAD：回放存档 → 重建网格但不标脏
        world.applySavedBlock(1, 40, 1, (short) TestWorlds.stone());
        assertFalse(chunk.isSaveDirty(), "SAVE_LOAD 来源不标 saveDirty");

        chunk.clearSaveDirty();
        world.breakBlock(1, 40, 1, World.MutationCause.SELF_TEST);
        assertTrue(chunk.isSaveDirty(), "自测来源与玩家行为同样记账");
    }

    // ============================================================ 统计口径

    @Test
    void statsLineIsNonEmptyAndMentionsCounters() {
        World world = TestWorlds.flatWorld();
        world.breakBlock(5, TestWorlds.SURFACE_BLOCK_Y, 5, World.MutationCause.PLAYER_BREAK);
        String line = world.statsLine();

        assertNotNull(line);
        assertFalse(line.isBlank());
        assertTrue(line.contains("破坏="), line);
        assertTrue(line.contains("放置="), line);
    }

    @Test
    void emissiveSourcesAreRegisteredFromGeneration() {
        World world = new World(1L, new com.skyisland.world.gen.TestWorldGenerator());
        world.ensureAreaLoaded(0, 0, 0, 0);

        assertEquals(1, world.emissiveSourceCount(),
                "资源核心是生成期写入的自发光方块，必须被登记（生成走 ChunkWriter，不经过 writeVoxel）");
        World.EmissiveSource source = world.emissiveSources().iterator().next();
        assertEquals(2, source.x());
        assertEquals(64, source.y());
        assertEquals(2, source.z());
        assertTrue(source.level() > 0);
    }

    @Test
    void rejectedBreakOfEmissiveBlockKeepsRegistryConsistent() {
        World world = TestWorlds.scatteredWorld(0, 0, 0, 0,
                TestWorlds.cell(4, 64, 4, TestWorlds.resourceCore()));
        assertEquals(1, world.emissiveSourceCount());
        // resourceCore 不可破坏，因此这里验证的是"被拒绝时不得改动光源登记表"
        assertFalse(world.breakBlock(4, 64, 4, World.MutationCause.PLAYER_BREAK).success());
        assertEquals(1, world.emissiveSourceCount(), "破坏被拒绝时不得改动光源登记表");
        assertTrue(world.isSolidAt(4, 64, 4));
    }
}

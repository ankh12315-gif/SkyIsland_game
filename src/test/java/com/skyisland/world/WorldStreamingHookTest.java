package com.skyisland.world;

import com.skyisland.testutil.TestWorlds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ M4-S8a：{@link World} 上两个流式钩子的守卫。
 *
 * <p>两个钩子为什么挂在 {@code World} 而不是挂在 {@code ChunkStreamer} 上：
 * "加载一个区块"和"卸载一个区块"各自都只有一个入口
 * （{@link World#getOrLoadChunk} / {@link World#unloadChunk}）。
 * 钩子挂在入口上，"忘记清理"这条路径就<b>不存在</b>；
 * 挂在流式器上，任何将来新增的加载/卸载点都会悄悄绕过去。
 */
class WorldStreamingHookTest {

    private static World world() {
        return new World(1L, new TestWorlds.FlatGenerator());
    }

    // ============================================================ 卸载监听

    @Test
    void unloadingIsSilentWhenNoListenerIsAttached() {
        World world = world();
        world.getOrLoadChunk(0, 0);
        assertNull(world.chunkUnloadListener());
        assertNotNull(world.unloadChunk(0, 0), "没接监听也应当正常工作（老代码路径不受影响）");
        assertNull(world.unloadChunk(0, 0), "重复卸载返回 null");
    }

    @Test
    void theUnloadListenerFiresExactlyOncePerRemovedChunk() {
        World world = world();
        List<Chunk> seen = new ArrayList<>();
        world.setChunkUnloadListener(seen::add);
        world.ensureAreaLoaded(-1, -1, 1, 1);

        world.unloadChunk(0, 0);
        world.unloadChunk(0, 0);   // 已经不在了：不该再回调

        assertEquals(1, seen.size(), "每移除一个区块恰好回调一次；重复卸载不得重复回调");
        assertEquals(0, seen.get(0).cx());
        assertEquals(0, seen.get(0).cz());
    }

    /**
     * ★ 回调时区块必须仍是一个内容完整的对象。
     *
     * <p>卸载监听要做两件事：释放 GPU 网格（只需要身份）、把改动落盘（<b>需要内容</b>）。
     * 若实现成"先清空再回调"，落盘那一步会写出一份空区块 ——
     * 而它的表现是"玩家的改动没了"，且没有任何告警。
     */
    @Test
    void theUnloadedChunkStillCarriesItsContentWhenTheListenerRuns() {
        World world = world();
        world.getOrLoadChunk(0, 0);
        assertTrue(world.breakBlock(3, TestWorlds.SURFACE_BLOCK_Y, 4,
                World.MutationCause.PLAYER_BREAK).success());

        boolean[] dirtySeen = {false};
        world.setChunkUnloadListener(chunk -> dirtySeen[0] = chunk.isSaveDirty());
        world.unloadChunk(0, 0);

        assertTrue(dirtySeen[0],
                "★ 回调拿到的区块必须仍然是脏的 —— 落盘那一步正是靠这个标志决定要不要写文件");
    }

    // ============================================================ 增量来源

    /**
     * ★ 重入安全：这是"把回放挂在生成里"最容易写坏的一处。
     *
     * <p>{@code ChunkSerializer.applyTo} 内部会调 {@code getOrLoadChunk}。
     * 若回放发生在<b>入表之前</b>，那一句会再生成一次同一个区块 → 无限递归；
     * 若发生在入表之后，它拿到的是已经在表里的那一个，递归在第一层就收敛。
     */
    @Test
    void theDeltaSourceSeesTheChunkAlreadyRegisteredSoReplayIsReentrant() {
        World world = world();
        int[] calls = {0};
        world.setChunkDeltaSource((w, cx, cz) -> {
            assertNotNull(w.chunkAt(cx, cz),
                    "★ 回放差异时区块必须已经在 chunks 表里，否则 applyTo 里的 getOrLoadChunk 会递归回这里");
            calls[0]++;
            return 0;
        });

        world.getOrLoadChunk(2, -2);
        assertEquals(1, calls[0], "每个新生成的区块恰好回放一次");

        world.getOrLoadChunk(2, -2);
        assertEquals(1, calls[0], "已经加载的区块不得重复回放（否则玩家的改动会被应用两次）");
    }

    @Test
    void theDeltaSourceCanActuallyMutateTheFreshlyGeneratedChunk() {
        World world = world();
        world.setChunkDeltaSource((w, cx, cz) ->
                w.applySavedBlock(cx * 16 + 3, 70, cz * 16 + 4, (short) TestWorlds.stone()) ? 1 : 0);

        world.getOrLoadChunk(0, 0);

        assertEquals(TestWorlds.stone(), world.blockIdAt(3, 70, 4),
                "回放的方块必须真的落到区块里");
        assertEquals(1, world.deltaAppliedCount());
        assertFalse(world.chunkAt(0, 0).isSaveDirty(),
                "SAVE_LOAD 来源不得把区块标成脏 —— 否则「加载一次就写回一次」，存档会自我放大");
    }

    @Test
    void aWorldWithoutAHookBehavesExactlyAsBefore() {
        World world = world();
        assertNull(world.chunkDeltaSource());
        assertNotNull(world.getOrLoadChunk(1, 1));
        assertEquals(0, world.deltaAppliedCount());
    }
}

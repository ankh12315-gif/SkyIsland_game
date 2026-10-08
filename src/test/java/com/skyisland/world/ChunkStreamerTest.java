package com.skyisland.world;

import com.skyisland.testutil.TestWorlds;
import com.skyisland.util.Coords;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ M4-S8a：区块流式加载 / 卸载的<b>行为</b>守卫。
 *
 * <p>配套文件：{@code ChunkStreamingWiringTest}（接线的存在性）、
 * {@code SaveManagerStreamingTest}（卸载前落盘与按坐标回放）。
 * 三者缺一：只有行为测试会让"根本没接线的实现"全绿，只有接线测试会让
 * "接上了但算错了"全绿 —— 这正是 S7 撞过一次的同一形状。
 */
class ChunkStreamerTest {

    private static World world() {
        return new World(1L, new TestWorlds.FlatGenerator());
    }

    /** 反复 update 直到不再变化：预算限制决定了"一次 update 做不完"是正常路径。 */
    private static void settle(ChunkStreamer streamer, double x, double z) {
        for (int i = 0; i < 40; i++) {
            streamer.update(x, z);
        }
    }

    /** 区块中心的世界坐标。 */
    private static double centerOf(int c) {
        return c * Coords.CHUNK_SIZE + Coords.CHUNK_SIZE * 0.5;
    }

    @Test
    void resetLoadsTheWholeSquareAroundTheCenter() {
        World world = world();
        ChunkStreamer streamer = new ChunkStreamer(world, 2);
        streamer.reset(0.0, 0.0);

        assertEquals(25, world.loadedChunkCount(),
                "半径 2 → (2*2+1)^2 = 25 个区块，一个都不能少（少了说明环的遍历漏了角）");
        assertEquals(0, streamer.centerCx());
        assertEquals(0, streamer.centerCz());
        assertTrue(streamer.isCentered());
        assertEquals(3, streamer.keepRadius(), "保留半径 = 加载半径 + 滞回 1");
    }

    @Test
    void theCenterIsDerivedFromThePlayerPositionNotFromAStoredChunkPair() {
        World world = world();
        ChunkStreamer streamer = new ChunkStreamer(world, 1);
        streamer.reset(17.0, -33.0);

        assertEquals(1, streamer.centerCx(), "17 / 16 = 1");
        assertEquals(-3, streamer.centerCz(), "floorDiv(-33,16) = -3（负坐标必须用 floorDiv 而不是 /）");
        assertNotNull(world.chunkAt(1, -3), "玩家脚下那一块必须在 reset 之后立刻可用");
    }

    @Test
    void standingStillNeitherLoadsNorUnloads() {
        World world = world();
        ChunkStreamer streamer = new ChunkStreamer(world, 2);
        streamer.reset(0.0, 0.0);
        int loaded = world.loadedChunkCount();

        assertEquals(0, streamer.update(0.0, 0.0));
        assertEquals(loaded, world.loadedChunkCount());
        assertEquals(0, streamer.unloadCount());
    }

    /**
     * ★ 滞回：这是"加载半径 ≠ 卸载半径"存在的<b>唯一</b>理由。
     *
     * <p>若两者相等，站在区块边界来回走一步就会触发一次卸载 + 一次重新生成，
     * 表现为规律性的帧时间尖峰。注意它的失败模式：画面完全正常
     * （地形是确定性的，重新生成出来一模一样），只有帧时间会抖 ——
     * 所以这条必须写成断言，不能指望试玩时看得出来。
     */
    @Test
    void hysteresisKeepsOneExtraRingSoWalkingOnTheEdgeDoesNotThrash() {
        World world = world();
        ChunkStreamer streamer = new ChunkStreamer(world, 2);
        streamer.reset(0.0, 0.0);

        // 走到 cx=3：(0,0) 距新中心 3，已超出加载半径 2，但仍在保留半径 3 之内
        settle(streamer, centerOf(3), 0.0);
        assertNotNull(world.chunkAt(0, 0),
                "距离恰好等于保留半径的区块必须留着 —— 留着它才不会在边界上反复生成/卸载");

        // 再走一格：距离 4 > 保留半径 3，这才轮到卸载
        settle(streamer, centerOf(4), 0.0);
        assertNull(world.chunkAt(0, 0),
                "距离超过保留半径的区块必须被卸掉，否则流式等于没卸载（内存仍随行程增长）");
    }

    @Test
    void theChunkUnderThePlayerIsNeverUnloaded() {
        World world = world();
        ChunkStreamer streamer = new ChunkStreamer(world, 2);
        streamer.reset(0.0, 0.0);

        // ★ 一次只走一格区块（跳变 <= 1 → 走"按预算渐变"那条路，而不是传送那条）
        for (int step = 1; step <= 12; step++) {
            settle(streamer, centerOf(step), centerOf(step));
            assertNotNull(world.chunkAt(streamer.centerCx(), streamer.centerCz()),
                    "第 " + step + " 段行程之后，玩家脚下的区块不见了 —— 那会直接掉进虚空");
        }
    }

    /**
     * ★ 稳态常驻集合的上界是<b>保留</b>半径，不是加载半径。
     *
     * <p>这一条很容易被直觉搞错：走动时"已生成的那一圈"要等玩家再走出一格才卸，
     * 因此它长期留在内存里。用加载半径估内存会低估近一倍 ——
     * 而低估的预算比没有预算更危险，因为它让人以为还有余量。
     */
    @Test
    void theSteadyStateResidentSetIsBoundedByTheKeepRadius() {
        World world = world();
        ChunkStreamer streamer = new ChunkStreamer(world, 2);
        streamer.reset(0.0, 0.0);
        settle(streamer, centerOf(20), centerOf(20));

        for (Chunk chunk : new ArrayList<>(world.loadedChunks())) {
            int d = Math.max(Math.abs(chunk.cx() - 20), Math.abs(chunk.cz() - 20));
            assertTrue(d <= streamer.keepRadius(),
                    "稳态下不该有距离 " + d + " > 保留半径 " + streamer.keepRadius()
                            + " 的区块还留在内存里 —— 那正是「内存随行程增长」的定义");
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                assertNotNull(world.chunkAt(20 + dx, 20 + dz),
                        "加载半径内的区块在稳态下必须全部就位");
            }
        }
    }

    /**
     * ★ 由近及远不是优化而是<b>正确性</b>要求。
     *
     * <p>有预算时若先加载远处，玩家会先看到远处地形冒出来，而自己脚下那一圈还没生成 ——
     * 表现为"跨过区块边界时短暂掉进虚空"，且只在预算被用满时才出现。
     */
    @Test
    void budgetForcesLoadsToProceedNearestFirst() {
        World world = world();
        ChunkStreamer streamer = new ChunkStreamer(world, 3);
        streamer.reset(0.0, 0.0);
        for (Chunk chunk : new ArrayList<>(world.loadedChunks())) {
            if (chunk.cx() != 0 || chunk.cz() != 0) {
                world.unloadChunk(chunk.cx(), chunk.cz());
            }
        }
        assertEquals(1, world.loadedChunkCount());

        int loaded = streamer.update(0.0, 0.0);
        assertEquals(ChunkStreamer.MAX_LOADS_PER_UPDATE, loaded,
                "单次 update 的生成量必须受预算约束（这是不卡的前提）");
        assertEquals(3, world.loadedChunkCount());
        for (Chunk chunk : world.loadedChunks()) {
            int d = Math.max(Math.abs(chunk.cx()), Math.abs(chunk.cz()));
            assertTrue(d <= 1, "预算只有 " + ChunkStreamer.MAX_LOADS_PER_UPDATE
                    + " 个时，加载的必须是最近的一圈，实际加载到了距离 " + d);
        }
    }

    @Test
    void aTeleportIsServedByAFullReloadInsteadOfThePerStepBudget() {
        World world = world();
        ChunkStreamer streamer = new ChunkStreamer(world, 2);
        streamer.reset(0.0, 0.0);

        streamer.update(centerOf(50), centerOf(50));
        assertEquals(50, streamer.centerCx());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                assertNotNull(world.chunkAt(50 + dx, 50 + dz),
                        "传送后半径内必须<b>立刻</b>全部就位 —— 按预算慢慢来会让玩家在空气里下落好几帧");
            }
        }
    }

    @Test
    void unloadCanBeSwitchedOffForAutoVerification() {
        World world = world();
        ChunkStreamer streamer = new ChunkStreamer(world, 2);
        streamer.reset(0.0, 0.0);
        streamer.setUnloadEnabled(false);

        settle(streamer, centerOf(30), centerOf(30));
        assertFalse(streamer.isUnloadEnabled());
        assertEquals(0, streamer.unloadCount(), "关掉之后一次都不许卸");
        assertNotNull(world.chunkAt(0, 0), "自测脚本的断言读的是当前状态，区块不许在它脚下消失");
    }

    /**
     * ★ 卸载必须通知监听者 —— 这是"卸载 = 释放网格 + 落盘"这条规则的<b>接口</b>证据。
     *
     * <p>它只能证明"回调被调了"；"回调里真的释放了网格"由
     * {@code ChunkStreamingWiringTest} 的源码扫描钉住。
     */
    @Test
    void unloadingNotifiesTheListenerSoResourcesCanBeReleased() {
        World world = world();
        List<Chunk> released = new ArrayList<>();
        world.setChunkUnloadListener(released::add);
        ChunkStreamer streamer = new ChunkStreamer(world, 2);
        streamer.reset(0.0, 0.0);

        settle(streamer, centerOf(40), centerOf(0));

        assertFalse(released.isEmpty(), "卸载了区块却没有通知监听者 → GPU 网格与脏数据都会留下");
        assertEquals(streamer.unloadCount(), released.size(),
                "每卸载一个区块必须恰好回调一次（多一次说明有第二个卸载漏斗）");
    }

    @Test
    void radiusMustBeAtLeastOneChunk() {
        World world = world();
        assertThrows(IllegalArgumentException.class, () -> new ChunkStreamer(world, 0),
                "半径 0 意味着「只加载脚下这一块」，卸载半径会是 1，玩家迈一步就掉下去");
        assertThrows(IllegalArgumentException.class, () -> new ChunkStreamer(world, -3));
    }

    @Test
    void theDefaultRadiusHasAnAffordableResidentFootprint() {
        // 单区块体素 short[16*16*128] = 64 KB；半径 R 的常驻量是 (2R+1)^2 块。
        // 这条断言的作用是：把"默认半径"从一个拍脑袋的数字变成一条有上限的承诺
        // —— 谁把 DEFAULT_RADIUS 调大到 8，这里会立刻红，而不是等玩家报告"变卡了"。
        // ★ 判据用【保留】半径而不是加载半径。
        //   走动时的稳态常驻量是保留方块，不是加载方块：已生成的那一圈要等玩家
        //   再走出一格才卸，于是它长期留在内存里（见 theSteadyStateResidentSetIsBoundedByTheKeepRadius）。
        //   用加载半径算会低估近一倍 —— 那种"算错的预算"比没有预算更危险。
        int keep = ChunkStreamer.DEFAULT_RADIUS + ChunkStreamer.UNLOAD_MARGIN;
        int blocks = (2 * keep + 1) * (2 * keep + 1);
        long bytes = blocks * 64L * 1024L;
        assertTrue(bytes <= 8L * 1024 * 1024,
                "默认半径的常驻体素必须 <= 8 MB，实际 " + blocks + " 块 = " + (bytes / 1024 / 1024) + " MB");
        assertEquals(4, ChunkStreamer.DEFAULT_RADIUS, "改默认值必须同时改上面这条预算口径");
    }
}

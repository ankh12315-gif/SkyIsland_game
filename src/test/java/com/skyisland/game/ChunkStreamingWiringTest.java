package com.skyisland.game;

import com.skyisland.testutil.SourceScan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ M4-S8a 区块流式的<b>接线</b>守卫（源码扫描）。
 *
 * <h2>为什么行为测试不够，还必须有这一层</h2>
 * {@code ChunkStreamerTest} 有十几条断言把"半径、滞回、预算、由近及远"钉得很死，
 * 但它们全部直接 {@code new ChunkStreamer}，<b>从不走 SkyIslandGame</b>。
 * 于是下面这些断线可以让那些断言<u>全部保持绿</u>：
 * <ul>
 *   <li>{@code stepLogic} 里根本没有调用 {@code update}（世界仍然一次性生成）；</li>
 *   <li>卸载时只释放了网格、忘了落盘（走回来时挖掉的坑又长回来）；</li>
 *   <li>卸载时只落盘、忘了释放网格（显存缓慢上涨，跑十分钟才看得出来）；</li>
 *   <li>{@code startNewWorld} 换了 World 却没重新接流式（新世界用着旧的中心）。</li>
 * </ul>
 *
 * <h2>★ 判据为什么必须落在方法体上并剥注释</h2>
 * 与 S7 同一条纪律：这些文件里注释会写下被断言的调用名
 * （例如"卸载要释放网格"这句就在注释里），不剥注释的话删掉真正的调用断言依然绿。
 * 本项目已中过三次，不再单独论证。
 */
class ChunkStreamingWiringTest {

    private static String game() {
        return SourceScan.readMain("com/skyisland/game/SkyIslandGame.java");
    }

    private static String world() {
        return SourceScan.readMain("com/skyisland/world/World.java");
    }

    // ============================================================ 每步驱动

    /**
     * ★ 流式的 update 必须排在物理<b>之前</b>。
     *
     * <p>顺序反了的表现：玩家跨过区块边界的那一步会站在"未加载 = 空气"上开始下落。
     * 由于半径远大于单步位移，这个窗口只有一帧 —— 试玩时几乎必然被当成偶发抖动忽略掉，
     * 因此必须写成断言。
     */
    @Test
    void theStreamerRunsBeforePlayerPhysics() {
        String body = SourceScan.methodBody(game(), "public void stepLogic(");
        int updateAt = body.indexOf("chunkStreamer.update(");
        int physicsAt = body.indexOf("player.step(");
        assertTrue(updateAt >= 0, "stepLogic 里没有 chunkStreamer.update(...)：世界根本没被流式维护");
        assertTrue(physicsAt >= 0, "stepLogic 里找不到 player.step(");
        assertTrue(updateAt < physicsAt,
                "★ chunkStreamer.update 必须排在 player.step 之前"
                        + "（现状：update@" + updateAt + " vs physics@" + physicsAt + "）；"
                        + "反过来的后果是玩家在跨区块的那一步踩到空气");
    }

    @Test
    void theStartupNoLongerPinsTheWholeTestWorld() {
        String body = SourceScan.methodBody(game(), "private void start(");
        assertFalse(body.contains("ensureAreaLoaded(TestWorldGenerator.MIN_CHUNK"),
                "start() 里仍在一次性生成整个 TestWorldGenerator 的全部区块 —— "
                        + "那正是 S8a 要替换掉的做法（世界大小被地形边界而不是玩法限制住）");
        assertTrue(body.contains("attachStreaming("),
                "start() 必须走 attachStreaming 以出生点为中心建立流式半径");
    }

    // ============================================================ 卸载的连带动作

    /**
     * ★ 卸载必须<b>同时</b>释放 GPU 网格与落盘 —— 只做一件都会得到"能跑但慢慢坏"。
     *
     * <p>只释放网格 → 走回来时挖掉的坑又长回来了（增量没写）；
     * 只落盘 → 显存一路涨。两者都不报错、都不崩溃，只有玩家走一圈回来才发现。
     */
    @Test
    void unloadReleasesTheGpuMeshAndFlushesTheDirtyChunk() {
        String body = SourceScan.methodBody(game(), "private void onChunkUnloaded(");
        assertTrue(body.contains("releaseMesh("),
                "卸载回调里没有 releaseMesh：GPU 网格会随加载/卸载循环泄漏"
                        + "（ChunkRenderer 只在'该块恰好排在重建队列里'时才顺手释放，绝大多数卸载不走那条路）");
        assertTrue(body.contains("saveChunk("),
                "卸载回调里没有 saveChunk：玩家在该区块里的改动会在卸载时丢失，"
                        + "表现为「走回来时挖掉的坑又长回来了」");
    }

    /** 对照：{@code World} 侧的卸载漏斗必须真的去调监听者 —— 否则回调永远不会被触发。 */
    @Test
    void worldsUnloadFunnelActuallyInvokesTheListener() {
        String body = SourceScan.methodBody(world(), "public Chunk unloadChunk(");
        assertTrue(body.contains("onChunkUnloaded"),
                "World.unloadChunk 没有调 onChunkUnloaded：卸载的连带动作将永远不执行，"
                        + "而 ChunkStreamerTest 里的行为断言仍会全绿（它只测回调被调了这件事）");
    }

    // ============================================================ 装配

    @Test
    void attachStreamingWiresAllThreeHooksAtOnce() {
        String body = SourceScan.methodBody(game(), "private void attachStreaming(");
        assertTrue(body.contains("setChunkUnloadListener("), "缺卸载监听 → 卸载不清理");
        assertTrue(body.contains("setChunkDeltaSource("),
                "缺增量来源 → 远离出生点的玩家改动永远读不回来（存档文件在，世界不认）");
        assertTrue(body.contains("new ChunkStreamer("), "缺流式器本身");
        assertTrue(body.contains("chunkStreamer.reset("),
                "attachStreaming 必须以给定中心把半径内补齐（无预算），否则开局脚下没有地形");
    }

    @Test
    void replacingTheWorldReattachesStreaming() {
        String body = SourceScan.methodBody(game(), "private void startNewWorld(");
        assertTrue(body.contains("attachStreaming("),
                "startNewWorld 换了 World 却没重新接流式：新世界会用旧的中心与旧的钩子，"
                        + "而旧 World 的卸载监听还指向已被丢弃的实例");
        assertFalse(body.contains("ensureAreaLoaded(TestWorldGenerator.MIN_CHUNK"),
                "新建世界也不该一次性生成全部区块（与 start() 同一口径）");
    }

    @Test
    void autoVerificationRunsWithUnloadDisabled() {
        String source = SourceScan.withoutComments(game());
        assertTrue(source.contains("setUnloadEnabled(false)"),
                "没有任何地方关掉卸载：自测脚本的断言读的是 World 当前状态，"
                        + "区块在它脚下消失会把「断言红」变成「偶发红」，而偶发红没有诊断价值");
        assertTrue(source.contains("isAutoVerification()"),
                "关掉卸载的判据必须走 isAutoVerification()（覆盖三种自测），"
                        + "散着写 selfTest == null 之类的判断会在新增自测时漏掉");
        String body = SourceScan.methodBody(game(), "private boolean isAutoVerification(");
        assertTrue(body.contains("selfTest != null"), "三种自测都必须覆盖：脚本化自测漏了");
        assertTrue(body.contains("uiSelfTest != null"), "三种自测都必须覆盖：界面自测漏了");
        assertTrue(body.contains("combatSelfTest != null"), "三种自测都必须覆盖：战斗自测漏了");
    }

    @Test
    void theDeltaSourceReadsFromTheSaveManagerByCoordinate() {
        String body = SourceScan.methodBody(game(), "private int applySavedChunkDelta(");
        assertTrue(body.contains("saveManager.applyChunkDelta("),
                "增量来源没有接到 saveManager：区块重新生成时不会回放它自己的存档差异");
    }

    // ============================================================ 可观测性

    @Test
    void theMeasurementSummaryReportsTheStreamingEvidence() {
        String body = SourceScan.methodBody(game(), "private void emitMeasurementSummary(");
        assertTrue(body.contains("stream_chunks_load"), "汇总里没有累计加载数");
        assertTrue(body.contains("stream_chunks_drop"),
                "汇总里没有累计卸载数 —— 没有它就无法区分「按需加载」与「全世界常驻」");
        assertTrue(body.contains("delta_applied"),
                "汇总里没有增量回放数 —— 那是「世界记得玩家改动」的直接证据");
    }

    @Test
    void theDebugHudShowsTheLiveStreamerState() {
        String body = SourceScan.methodBody(game(), "private void updateHud(");
        assertTrue(body.contains("chunkStreamer.statsLine()"),
                "调试 HUD 没有显示流式状态：现场排查「为什么这里没地形」时将无从下手");
    }
}

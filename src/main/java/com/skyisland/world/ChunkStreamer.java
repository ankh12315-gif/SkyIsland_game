package com.skyisland.world;

import com.skyisland.util.Coords;
import com.skyisland.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ M4-S8a：以玩家为中心的<b>区块流式加载 / 卸载</b>。
 *
 * <p><b>它解决的是什么：</b>M1 起世界是"固定 4×4 区块、全部常驻"，
 * 于是玩家的活动范围被地形边界而不是玩法限制住 —— 走出那 64×64 格就是一片
 * 没有尽头的平地（生成器对范围外一律返回平地）。S8a 之后世界在半径内<b>按需生成</b>、
 * 走出半径<b>卸载</b>，世界可以是无限的，而常驻内存只与半径有关。
 *
 * <h3>三条必须一起成立的规则</h3>
 * <ol>
 *   <li><b>加载半径 ≠ 卸载半径。</b>卸载半径 = {@code radius + UNLOAD_MARGIN}。
 *       两者相等时，站在区块边界来回走会触发"加载 → 卸载 → 再加载"的抖动，
 *       每走一步都重新生成一次地形 —— 表现为<b>规律性的卡顿</b>，
 *       而由于地形是确定性的，画面还看不出异常，极难归因。</li>
 *   <li><b>加载按切比雪夫距离由近及远。</b>有预算限制时先加载近的，
 *       否则玩家可能先看到远处的地形冒出来、脚下的区块还没生成。</li>
 *   <li><b>卸载必须连带清理。</b>本类只负责调 {@link World#unloadChunk}，
 *       释放 GPU 网格与落盘由 {@link World.ChunkUnloadListener} 完成 ——
 *       卸载只有那一个漏斗，清理无法被绕过。</li>
 * </ol>
 *
 * <p><b>确定性前提：</b>卸载之所以安全，是因为地形生成是
 * {@code (x, z)} 的<u>纯函数</u>（无随机数、无状态），且存档是<b>增量式</b>的。
 * 于是"卸载后重新生成"必然得到同一份地形；而玩家的改动以增量形式留在磁盘上，
 * 由 {@code World} 的生成钩子在重新加载时回放。
 * 这两条只要破一条（生成引入随机、或存档改成写全量），卸载就变成数据丢失。
 */
public final class ChunkStreamer {

    /**
     * 默认加载半径（区块数）。
     *
     * <p>取值依据：单区块体素 {@code short[16×16×128]} = 64 KB。
     * <b>算常驻量要用保留半径而不是加载半径</b> —— 走动时的稳态集合是保留方块
     * （已生成的那一圈要等玩家再走出一格才卸），用加载半径估会低估近一倍：
     * <pre>
     *   R=3 → 加载  49 块 ≈ 3.1 MB / 稳态  81 块 ≈ 5.1 MB     视距 48 格
     *   R=4 → 加载  81 块 ≈ 5.2 MB / 稳态 121 块 ≈ 7.6 MB     视距 64 格
     *   R=6 → 加载 169 块 ≈ 10.8 MB / 稳态 225 块 ≈ 14.4 MB   视距 96 格
     * </pre>
     * 取 4：视距 64 格已超出单屏可视范围（视锥剔除会先起作用），
     * 而 7.6 MB 量级的常驻体素对"不占过多内存"这条要求是能交代的数字。
     * 上限由 {@code ChunkStreamerTest#theDefaultRadiusHasAnAffordableResidentFootprint} 钉住，
     * 调大默认值会立刻变红，而不是等玩家报告"变卡了"。
     */
    public static final int DEFAULT_RADIUS = 4;

    /**
     * 卸载半径相对加载半径的外扩量（滞回）。
     *
     * <p>1 的含义是"多留一圈"：玩家走出加载半径后，已经生成的那一圈不会立刻被卸掉，
     * 要再走一格才卸。于是"在边界上反复横跳"不会触发重复的生成/卸载。
     */
    public static final int UNLOAD_MARGIN = 1;

    /** 每次 {@link #update} 最多生成多少个新区块（避免一次跨区块时帧时间尖峰）。 */
    public static final int MAX_LOADS_PER_UPDATE = 2;

    /** 每次 {@link #update} 最多卸载多少个区块（卸载同样有落盘开销）。 */
    public static final int MAX_UNLOADS_PER_UPDATE = 4;

    /**
     * 中心区块一次跳变超过这个值就判定为"传送"而不是"走过去"。
     *
     * <p>传送的特征是"新位置附近一个已加载区块都没有"，按预算慢慢加载会让玩家
     * 在空气里自由落体好几帧。判成传送就走 {@link #reset}：一次性把半径内补齐。
     */
    public static final int TELEPORT_JUMP_CHUNKS = 1;

    private final World world;
    private final int radius;

    /**
     * 是否允许卸载。
     *
     * <p>自测脚本<b>必须</b>关掉它：脚本的断言读的是 {@code World} 的<b>当前</b>状态，
     * 让区块在它脚下消失会把"断言红"变成"偶发红" —— 而偶发红是没有任何诊断价值的。
     * 这不是偷懒：它把"自测看到的世界不会变"升级成一条显式契约。
     */
    private boolean unloadEnabled = true;

    private int centerCx;
    private int centerCz;
    private boolean centered;

    private long loadCount;
    private long unloadCount;

    public ChunkStreamer(World world) {
        this(world, DEFAULT_RADIUS);
    }

    public ChunkStreamer(World world, int radius) {
        if (radius < 1) {
            throw new IllegalArgumentException("加载半径必须 >= 1，收到 " + radius);
        }
        this.world = world;
        this.radius = radius;
    }

    public int radius() {
        return radius;
    }

    /** 实际保留半径（超出这个距离才会被卸载）。 */
    public int keepRadius() {
        return radius + UNLOAD_MARGIN;
    }

    public boolean isUnloadEnabled() {
        return unloadEnabled;
    }

    public void setUnloadEnabled(boolean enabled) {
        this.unloadEnabled = enabled;
    }

    public int centerCx() {
        return centerCx;
    }

    public int centerCz() {
        return centerCz;
    }

    public boolean isCentered() {
        return centered;
    }

    public long loadCount() {
        return loadCount;
    }

    public long unloadCount() {
        return unloadCount;
    }

    /**
     * 把中心<b>无预算地</b>补齐（开局与传送用）。
     *
     * <p>这里<u>故意</u>不设预算：开局玩家脚下必须有地形，传送后也一样。
     * 预算是为了"走路时不尖峰"，而不是为了"任何时候都不许一次多生成几个"。
     *
     * @return 本次新生成的区块数
     */
    public int reset(double playerX, double playerZ) {
        int cx = Coords.toChunk(Coords.toBlock(playerX));
        int cz = Coords.toChunk(Coords.toBlock(playerZ));
        centerCx = cx;
        centerCz = cz;
        centered = true;
        int loaded = loadAround(cx, cz, -1);
        int unloaded = unloadEnabled ? unloadOutside(cx, cz, -1) : 0;
        if (loaded > 0 || unloaded > 0) {
            Log.info("[流式] 重建中心 (%d,%d)：新生成 %d 块、卸载 %d 块，当前 %d 块",
                    cx, cz, loaded, unloaded, world.loadedChunkCount());
        }
        return loaded;
    }

    /**
     * 按玩家位置维护加载集合（每逻辑步调用）。
     *
     * @return 本次新生成的区块数
     */
    public int update(double playerX, double playerZ) {
        if (!centered) {
            return reset(playerX, playerZ);
        }
        int cx = Coords.toChunk(Coords.toBlock(playerX));
        int cz = Coords.toChunk(Coords.toBlock(playerZ));
        if (Math.abs(cx - centerCx) > TELEPORT_JUMP_CHUNKS
                || Math.abs(cz - centerCz) > TELEPORT_JUMP_CHUNKS) {
            return reset(playerX, playerZ);
        }
        centerCx = cx;
        centerCz = cz;
        int loaded = loadAround(cx, cz, MAX_LOADS_PER_UPDATE);
        if (unloadEnabled) {
            unloadOutside(cx, cz, MAX_UNLOADS_PER_UPDATE);
        }
        return loaded;
    }

    /**
     * 加载半径内的缺失区块，<b>按切比雪夫距离由近及远</b>。
     *
     * <p>由近及远不是优化而是<b>正确性</b>要求：有预算时若先加载远的，
     * 玩家会先看到远处地形冒出来，而自己脚下那一圈还没生成 ——
     * 表现为"跨过区块边界时短暂掉进虚空"。
     *
     * @param budget 最多生成多少个；&lt;=0 表示不限
     */
    private int loadAround(int cx, int cz, int budget) {
        int loaded = 0;
        for (int d = 0; d <= radius; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    // 只走第 d 圈的边框（内部格子在更小的 d 时已经处理过）
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) {
                        continue;
                    }
                    if (world.chunkAt(cx + dx, cz + dz) != null) {
                        continue;
                    }
                    world.getOrLoadChunk(cx + dx, cz + dz);
                    loadCount++;
                    loaded++;
                    if (budget > 0 && loaded >= budget) {
                        return loaded;
                    }
                }
            }
        }
        return loaded;
    }

    private int unloadOutside(int cx, int cz, int budget) {
        int keep = keepRadius();
        // 快照：卸载会改 world 的底层集合，不能边遍历边删
        List<Chunk> victims = new ArrayList<>();
        for (Chunk chunk : world.loadedChunks()) {
            if (Math.max(Math.abs(chunk.cx() - cx), Math.abs(chunk.cz() - cz)) > keep) {
                victims.add(chunk);
            }
        }
        int unloaded = 0;
        for (Chunk chunk : victims) {
            if (world.unloadChunk(chunk.cx(), chunk.cz()) != null) {
                unloadCount++;
                unloaded++;
                if (budget > 0 && unloaded >= budget) {
                    break;
                }
            }
        }
        return unloaded;
    }

    /** 给 HUD / 自测用的一行状态。 */
    public String statsLine() {
        return String.format("半径=%d(保留%d) 中心=(%d,%d) 已加载=%d 累计加载=%d 累计卸载=%d 卸载=%s",
                radius, keepRadius(), centerCx, centerCz, world.loadedChunkCount(),
                loadCount, unloadCount, unloadEnabled ? "开" : "关");
    }
}

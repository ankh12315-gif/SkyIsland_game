package com.skyisland.world;

import com.skyisland.util.Coords;

/**
 * 区块：16 × 16 × 128 的体素存储单元（TECH_DESIGN §E）。
 *
 * <p><b>存储形态：</b>一维 {@code short[]}，按 {@code (ly &lt;&lt; 8) | (lz &lt;&lt; 4) | lx} 线性化
 * （{@link Coords#chunkIndex}）。32768 个体素 = 64 KB，且按"同一 y 层的 16×16 平面连续"排布，
 * 与网格化、光照更新的遍历顺序一致。
 *
 * <p><b>禁止 {@code Block[][][]}：</b>三层数组会带来 16×16 个独立对象 + 128 层间接寻址，
 * 内存与缓存表现都不可接受；而且它让"整块连续拷贝/比较"（存档、脏区检测）无法实现。
 *
 * <p><b>写权限：</b>{@link #setLocal} 是 <b>包级私有</b>，只有 {@link World} 能调用。
 * 这是"所有 Block 改动必须走 World Mutation API"这条规则在编译期的落地手段 ——
 * 渲染器、玩家、UI 即使想绕过也编译不过。
 */
public final class Chunk {

    public static final int SIZE = Coords.CHUNK_SIZE;
    public static final int HEIGHT = Coords.CHUNK_HEIGHT;
    public static final int VOLUME = SIZE * SIZE * HEIGHT;

    private final int cx;
    private final int cz;

    /** 体素数据。runtimeId = 0 即空气（零初始化天然成立）。 */
    private final short[] blocks = new short[VOLUME];

    private int nonAirCount = 0;

    /** 内容自生成/读取以来是否被改动过（不直接用于存档判断，见 saveDirty）。 */
    private boolean dirty;

    /** 网格是否需要重建。 */
    private boolean meshDirty = true;

    /** 是否需要写回存档（只有真正被改动的区块才为 true）。 */
    private boolean saveDirty;

    /** 是否已经跑过生成器（区分"未生成的空区块"与"生成出来就是空的区块"）。 */
    private boolean generated;

    // ---- 网格统计（性能可观测性，TECH_DESIGN §G.5）----
    private long lastMeshBuildNanos;
    private int lastOpaqueFaceCount;
    private int lastTransparentFaceCount;

    Chunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
    }

    public int cx() {
        return cx;
    }

    public int cz() {
        return cz;
    }

    /** 区块原点在世界中的 x 坐标。 */
    public int originX() {
        return cx * SIZE;
    }

    /** 区块原点在世界中的 z 坐标。 */
    public int originZ() {
        return cz * SIZE;
    }

    // ============================================================ 体素读写

    /**
     * 读局部坐标（无边界检查，仅 {@link World} 与网格化在已确认范围内调用）。
     */
    short getLocal(int lx, int ly, int lz) {
        return blocks[Coords.chunkIndex(lx, ly, lz)];
    }

    /**
     * 写局部坐标 —— <b>包级私有，只有 {@link World} 可调用</b>。
     *
     * @return 是否真的发生了变化（用于避免无意义的脏标记与网格重建）
     */
    boolean setLocal(int lx, int ly, int lz, short runtimeId) {
        int index = Coords.chunkIndex(lx, ly, lz);
        short old = blocks[index];
        if (old == runtimeId) {
            return false;
        }
        if (old == 0 && runtimeId != 0) {
            nonAirCount++;
        } else if (old != 0 && runtimeId == 0) {
            nonAirCount--;
        }
        blocks[index] = runtimeId;
        dirty = true;
        meshDirty = true;
        return true;
    }

    /** 只读访问（网格化 / 存档 / 光照）。 */
    public short blockAt(int lx, int ly, int lz) {
        if (lx < 0 || lx >= SIZE || lz < 0 || lz >= SIZE || ly < 0 || ly >= HEIGHT) {
            return 0;
        }
        return blocks[Coords.chunkIndex(lx, ly, lz)];
    }

    /** 非空气体素数量。用于"空区块跳过网格构建"这一优化。 */
    public int nonAirCount() {
        return nonAirCount;
    }

    /** 是否一个非空气体素都没有。 */
    public boolean isEmpty() {
        return nonAirCount == 0;
    }

    // ============================================================ 脏标志

    public boolean isDirty() {
        return dirty;
    }

    public boolean isMeshDirty() {
        return meshDirty;
    }

    public void markMeshDirty() {
        this.meshDirty = true;
    }

    public void clearMeshDirty() {
        this.meshDirty = false;
    }

    public boolean isSaveDirty() {
        return saveDirty;
    }

    public void markSaveDirty() {
        this.saveDirty = true;
    }

    public void clearSaveDirty() {
        this.saveDirty = false;
    }

    public boolean isGenerated() {
        return generated;
    }

    void markGenerated() {
        this.generated = true;
    }

    // ============================================================ 网格统计

    public void recordMeshBuild(long nanos, int opaqueFaces, int transparentFaces) {
        this.lastMeshBuildNanos = nanos;
        this.lastOpaqueFaceCount = opaqueFaces;
        this.lastTransparentFaceCount = transparentFaces;
    }

    public long lastMeshBuildNanos() {
        return lastMeshBuildNanos;
    }

    public int lastOpaqueFaceCount() {
        return lastOpaqueFaceCount;
    }

    public int lastTransparentFaceCount() {
        return lastTransparentFaceCount;
    }

    public int lastFaceCount() {
        return lastOpaqueFaceCount + lastTransparentFaceCount;
    }

    @Override
    public String toString() {
        return "Chunk(" + cx + "," + cz + ") nonAir=" + nonAirCount
                + (meshDirty ? " meshDirty" : "") + (saveDirty ? " saveDirty" : "");
    }
}

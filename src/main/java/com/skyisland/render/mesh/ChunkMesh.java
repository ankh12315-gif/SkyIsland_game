package com.skyisland.render.mesh;

/**
 * 一个区块的网格 = <b>两个</b>子网格（TECH_DESIGN §G.2）。
 *
 * <p><b>为什么必须拆成两个而不是合成一个：</b>三条理由各自独立成立：
 * <ol>
 *   <li><b>渲染状态不同</b>：不透明 pass 开深度写入、关混合；半透明 pass 关深度写入、开混合；</li>
 *   <li><b>排序需求不同</b>：不透明不需要排序；半透明必须按距离由远及近；</li>
 *   <li><b>剔除规则不同</b>：不透明被不透明邻居遮挡即剔除；玻璃贴玻璃时共享面要剔除。
 *       合并成一个网格后，同一个 draw call 里既要有深度写入的三角形又要有不写的，
 *       只能拆回去 —— 所以"合并"省下的不是调用次数，而是把问题推到了不可能解决的地方。</li>
 * </ol>
 *
 * <p>本类不做任何排序或状态切换，只回答"有哪些几何可以画"；
 * 状态与顺序由 {@code ChunkRenderer} 统一管理，保证 pass 顺序只有一个执行点。
 */
public final class ChunkMesh {

    private final SubMesh opaque = new SubMesh();
    private final SubMesh transparent = new SubMesh();

    private int opaqueFaceCount;
    private int transparentFaceCount;

    /** 用一份构建好的数据整体替换两个子网格。 */
    public void upload(MeshData data) {
        opaque.upload(data.opaqueVertices(), data.opaqueIndices());
        transparent.upload(data.transparentVertices(), data.transparentIndices());
        opaqueFaceCount = data.opaqueFaceCount();
        transparentFaceCount = data.transparentFaceCount();
    }

    public void drawOpaque() {
        opaque.draw();
    }

    public void drawTransparent() {
        transparent.draw();
    }

    public boolean hasOpaque() {
        return opaque.hasGeometry();
    }

    public boolean hasTransparent() {
        return transparent.hasGeometry();
    }

    public int opaqueFaceCount() {
        return opaqueFaceCount;
    }

    public int transparentFaceCount() {
        return transparentFaceCount;
    }

    public int totalFaceCount() {
        return opaqueFaceCount + transparentFaceCount;
    }

    /** 两个子网格的三角形总数（HUD 与性能报告的"渲染了多少几何"）。 */
    public int triangleCount() {
        return opaque.triangleCount() + transparent.triangleCount();
    }

    public int indexCount() {
        return opaque.indexCount() + transparent.indexCount();
    }

    public void dispose() {
        opaque.dispose();
        transparent.dispose();
        opaqueFaceCount = 0;
        transparentFaceCount = 0;
    }

    @Override
    public String toString() {
        return String.format("ChunkMesh(不透明 %d 面, 透明 %d 面)", opaqueFaceCount, transparentFaceCount);
    }
}

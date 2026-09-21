package com.skyisland.render.mesh;

/**
 * 一个区块构建出的网格数据（纯 CPU，<b>不含任何 GL 对象</b>）。
 *
 * <p><b>为什么把"数据"与"GPU 资源"分开：</b>这份数据可以被单元测试直接检查
 * （顶点数、索引数、面数、位置是否落在 [0,16] 内），而不需要 OpenGL 上下文。
 * 若把 {@code build} 写成"直接往 VBO 里写"，网格化的正确性就只能靠肉眼看画面来验证 ——
 * 而"某个面莫名其妙少了一个"这种错误在画面上极难定位。
 *
 * <p><b>顶点格式（交错，stride = 28 字节 = 7 个 float）：</b>
 * <pre>
 *   offset  0 : position  vec3  区块局部坐标，值域 [0,16]
 *   offset 12 : color     vec4  rgb = 方块顶点色，a = 预乘明暗（面明暗 × 光照）
 * </pre>
 * 不含 UV / 纹理层号：M1 使用顶点色作为占位美术，TextureArray 顺延至 M2
 * （TECH_DESIGN_v0.1.1 §S′，已登记为技术债）。
 *
 * @param opaqueVertices      不透明子网格顶点
 * @param opaqueIndices       不透明子网格索引
 * @param transparentVertices 透明子网格顶点
 * @param transparentIndices  透明子网格索引
 * @param opaqueFaceCount     不透明面数（统计用，与顶点数互为校验）
 * @param transparentFaceCount 透明面数
 */
public record MeshData(float[] opaqueVertices, int[] opaqueIndices,
                       float[] transparentVertices, int[] transparentIndices,
                       int opaqueFaceCount, int transparentFaceCount) {

    /** 顶点浮点数个数（pos 3 + color 4）。 */
    public static final int FLOATS_PER_VERTEX = 7;

    /** 顶点字节数。 */
    public static final int VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    /** 每个面 4 个顶点。 */
    public static final int VERTICES_PER_FACE = 4;

    /** 空网格（空气区块、或全部面被剔除）。 */
    public static final MeshData EMPTY = new MeshData(
            new float[0], new int[0], new float[0], new int[0], 0, 0);

    public int totalFaceCount() {
        return opaqueFaceCount + transparentFaceCount;
    }

    public boolean isEmpty() {
        return opaqueIndices.length == 0 && transparentIndices.length == 0;
    }

    public int totalVertexFloatCount() {
        return opaqueVertices.length + transparentVertices.length;
    }

    public int totalIndexCount() {
        return opaqueIndices.length + transparentIndices.length;
    }

    @Override
    public String toString() {
        return String.format("MeshData(不透明 %d 面/%d 顶点/%d 索引, 透明 %d 面/%d 顶点/%d 索引)",
                opaqueFaceCount, opaqueVertices.length / FLOATS_PER_VERTEX, opaqueIndices.length,
                transparentFaceCount, transparentVertices.length / FLOATS_PER_VERTEX,
                transparentIndices.length);
    }
}

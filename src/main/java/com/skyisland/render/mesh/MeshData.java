package com.skyisland.render.mesh;

import com.skyisland.render.VertexFormat;

/**
 * 一个区块构建出的网格数据（纯 CPU，<b>不含任何 GL 对象</b>）。
 *
 * <p><b>为什么把"数据"与"GPU 资源"分开：</b>这份数据可以被单元测试直接检查
 * （顶点数、索引数、面数、位置是否落在 [0,16] 内），而不需要 OpenGL 上下文。
 * 若把 {@code build} 写成"直接往 VBO 里写"，网格化的正确性就只能靠肉眼看画面来验证 ——
 * 而"某个面莫名其妙少了一个"这种错误在画面上极难定位。
 *
 * <p><b>顶点格式（交错，stride = 36 字节 = 9 个 float）：</b>
 * <pre>
 *   offset  0 : position  vec3  区块局部坐标，值域 [0,16]
 *   offset 12 : colorvec4  rgb = 方块顶点色，a = 预乘明暗（面明暗 × 光照）
 *   offset 28 : layerAo   vec2  x = 纹理数组层号，y = 环境光遮蔽
 * </pre>
 * 不含 UV：每面 UV 取该层内的满幅区域（0..1），因此不需要独立的 UV 属性。
 * 纹理数组本体在 S3 接入；<b>S2 期间 layer 一律为 0，画面与 S1 一致</b>。
 *
 * <p><b>stride 的唯一真相源是 {@link com.skyisland.render.VertexFormat}</b>，
 * 本类不得自行写死数字 —— 本着色器被五个渲染器共用，
 * 少改一个不会编译报错，只会让它静默读到别人的字节。
 * 守卫见 {@code VertexFormatConsistencyTest}。
 *
 * @param opaqueVertices      不透明子网格顶点
 * @param opaqueIndices不透明子网格索引
 * @param transparentVertices 透明子网格顶点
 * @param transparentIndices  透明子网格索引
 * @param opaqueFaceCount     不透明面数（统计用，与顶点数互为校验）
 * @param transparentFaceCount 透明面数
 */
public record MeshData(float[] opaqueVertices, int[] opaqueIndices,
                       float[] transparentVertices, int[] transparentIndices,
                       int opaqueFaceCount, int transparentFaceCount) {

    /**
     * 顶点浮点数个数（pos 3 + color 4 + layerAo 2）。
     *
     * <p><b>刻意委托给 {@link com.skyisland.render.VertexFormat}而不是写 {@code 7}：</b>
     * 这个常量在本步之前的正确值就是 7，写死它会让下一次格式变更<b>静默漏改</b>。
     */
    public static final int FLOATS_PER_VERTEX = VertexFormat.FLOATS_PER_VERTEX;

    /** 顶点字节数。 */
    public static final int VERTEX_STRIDE_BYTES = VertexFormat.VERTEX_STRIDE_BYTES;

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

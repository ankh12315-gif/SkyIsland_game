package com.skyisland.render.mesh;

import com.skyisland.util.Log;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * 一个子网格的 GPU 资源（VAO + VBO + EBO）。
 *
 * <p><b>为什么用 EBO（§G.6）：</b>一个方块面 = 4 个顶点 + 6 个索引。
 * 不用 EBO 就是 6 个顶点，顶点数多 50%。对内存带宽受限的机器（本机 Intel Iris Xe），
 * 这个比例直接体现在帧时间上。代价是"同一面的 4 个顶点必须连续写入"——
 * 而这与按面发射的顺序天然一致，不增加复杂度。
 *
 * <p><b>索引用 32 位无符号：</b>一个区块理论上可以有上万张面，
 * 顶点数会超过 {@code GL_UNSIGNED_SHORT} 的 65535 上限。
 * 用 32 位索引的代价是每索引多 2 字节，但避免了"复杂区块顶点溢出后几何整体错乱"
 * 这种只在特定地形才出现的 bug。
 *
 * <p><b>上传用 {@link MemoryUtil} 而不是 {@code MemoryStack}：</b>
 * 区块网格可以有几百 KB，而 {@code MemoryStack} 的默认容量是 64 KB 量级 ——
 * 用它会在稍大的区块上直接抛栈溢出。这是一处"小场景测不出来、真实场景必炸"的陷阱。
 *
 * <p><b>原子替换（§G.5）：</b>{@link #upload} 先创建新缓冲、再切换引用、最后删除旧缓冲，
 * 因此不存在"清空了 VBO 而新数据还没写好"的中间状态 ——
 * 那会在挖掘的瞬间让整片地形闪一下。
 */
public final class SubMesh {

    private int vao;
    private int vbo;
    private int ebo;
    private int indexCount;

    /**
     * 上传（或替换）几何数据。必须在渲染线程、且有当前 GL 上下文时调用。
     *
     * @param vertices 交错顶点，stride = {@link MeshData#VERTEX_STRIDE_BYTES}
     * @param indices  三角形索引
     */
    public void upload(float[] vertices, int[] indices) {
        if (indices.length == 0) {
            clear();
            return;
        }
        int newVao = GL30.glGenVertexArrays();
        int newVbo = GL15.glGenBuffers();
        int newEbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(newVao);

        FloatBuffer vertexBuf = MemoryUtil.memAllocFloat(vertices.length);
        IntBuffer indexBuf = MemoryUtil.memAllocInt(indices.length);
        try {
            vertexBuf.put(vertices).flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, newVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuf, GL15.GL_STATIC_DRAW);

            indexBuf.put(indices).flip();
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, newEbo);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuf, GL15.GL_STATIC_DRAW);

            int stride = MeshData.VERTEX_STRIDE_BYTES;
            // location 0: aPos (vec3)
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0L);
            // location 1: aColor (vec4) —— rgb 顶点色 + a 预乘明暗
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);
        } finally {
            MemoryUtil.memFree(vertexBuf);
            MemoryUtil.memFree(indexBuf);
        }

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        // ★ 先建后删：到这里新资源已经完全就绪，才可以丢掉旧的
        disposeBuffers();
        this.vao = newVao;
        this.vbo = newVbo;
        this.ebo = newEbo;
        this.indexCount = indices.length;
    }

    /** 绑定并绘制。不设任何渲染状态 —— pass 状态由 {@code ChunkRenderer} 统一管理。 */
    public void draw() {
        if (indexCount == 0) {
            return;
        }
        GL30.glBindVertexArray(vao);
        GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, 0L);
        GL30.glBindVertexArray(0);
    }

    public int indexCount() {
        return indexCount;
    }

    public boolean hasGeometry() {
        return indexCount > 0;
    }

    public int triangleCount() {
        return indexCount / 3;
    }

    /** 释放到"空"状态（例如区块被挖成完全空气）。可重复调用。 */
    public void clear() {
        disposeBuffers();
        indexCount = 0;
    }

    public void dispose() {
        clear();
    }

    private void disposeBuffers() {
        if (ebo != 0) {
            GL15.glDeleteBuffers(ebo);
            ebo = 0;
        }
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
    }

    @Override
    public String toString() {
        return "SubMesh(" + triangleCount() + " 三角形)";
    }

    /** 供诊断：仅在异常路径打印，避免正常流程刷日志。 */
    public void warnIfEmpty(String context) {
        if (indexCount == 0) {
            Log.debug("[Mesh] %s 是空子网格", context);
        }
    }
}

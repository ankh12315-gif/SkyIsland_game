package com.skyisland.render.ui;

import com.skyisland.render.shader.ShaderProgram;
import com.skyisland.util.Log;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * HUD 的四边形批处理器：把准星、快捷栏、文字点阵全部攒成<b>一次 draw call</b>。
 *
 * <p><b>为什么必须批处理：</b>若每个图元单独一次 draw call，一个 10 行的 F3 overlay
 * 就有约 40 字符 × 10 行 × 每个字符十几个点阵段 ≈ 几千次调用；
 * 配合 M0 实测的 3000+ FPS，就是每秒上亿次绘制调用 —— 这些调用本身就是帧时间的主要来源。
 * 批处理之后 HUD 恒定 1 次调用，与文字量无关。
 *
 * <p><b>坐标口径：</b>对外接口用<b>像素、左上角为原点、y 向下</b>（与"读代码时的直觉"一致），
 * 内部换算成 NDC。换算只用帧缓冲尺寸，因此高 DPI 下（窗口尺寸 ≠ 帧缓冲尺寸）不会错位 ——
 * 这是 M0 已经踩过一次的坑（用窗口尺寸会把画面拉伸）。
 *
 * <p><b>顶点格式（stride = 24 字节）：</b>{@code pos vec2 + color vec4}。
 */
public final class UiBatch {

    private static final int FLOATS_PER_VERTEX = 6;
    private static final int VERTICES_PER_QUAD = 6;   // 两个三角形，不用 EBO（HUD 图元太小，省不下什么）

    private int vao;
    private int vbo;

    private float[] data = new float[4096 * FLOATS_PER_VERTEX];
    private int floatCount;

    private int framebufferWidth = 1;
    private int framebufferHeight = 1;

    private int quadCount;
    private boolean begun;

    // ============================================================ 生命周期

    public void init() {
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, stride, 2L * Float.BYTES);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        Log.info("[HUD] UiBatch 已就绪（VAO=%d VBO=%d，容量 %d 顶点）", vao, vbo,
                data.length / FLOATS_PER_VERTEX);
    }

    /** 开始一帧的收集。HUD 每帧重建顶点（内容随指标变化），因此不做"脏了才上传"的优化。 */
    public void begin(int fbWidth, int fbHeight) {
        this.framebufferWidth = Math.max(1, fbWidth);
        this.framebufferHeight = Math.max(1, fbHeight);
        this.floatCount = 0;
        this.quadCount = 0;
        this.begun = true;
    }

    // ============================================================ 图元

    /** 一个实心矩形（像素坐标，左上角为原点，y 向下）。 */
    public void rect(float x, float y, float width, float height,
                     float r, float g, float b, float a) {        if (!begun || width <= 0 || height <= 0) {
            return;
        }
        float x1 = toNdcX(x);
        float y1 = toNdcY(y);
        float x2 = toNdcX(x + width);
        float y2 = toNdcY(y + height);
        // 两个三角形：(x1,y1)-(x2,y1)-(x2,y2) 与 (x1,y1)-(x2,y2)-(x1,y2)
        push(x1, y1, r, g, b, a);
        push(x2, y1, r, g, b, a);
        push(x2, y2, r, g, b, a);
        push(x1, y1, r, g, b, a);
        push(x2, y2, r, g, b, a);
        push(x1, y2, r, g, b, a);
        quadCount++;
    }

    /** 只描边的矩形（四条 1×thickness 的实心边拼成）。 */
    public void rectOutline(float x, float y, float width, float height, float thickness,
                            float r, float g, float b, float a) {
        rect(x, y, width, thickness, r, g, b, a);
        rect(x, y + height - thickness, width, thickness, r, g, b, a);
        rect(x, y + thickness, thickness, height - 2 * thickness, r, g, b, a);
        rect(x + width - thickness, y + thickness, thickness, height - 2 * thickness, r, g, b, a);
    }

    /** {@code rgba} 数组重载 —— HUD 的配色集中定义为 {@code float[]} 常量，避免逐处重复四个参数。 */
    public void rect(float x, float y, float width, float height, float[] rgba) {
        rect(x, y, width, height, rgba[0], rgba[1], rgba[2], rgba[3]);
    }

    public void rectOutline(float x, float y, float width, float height, float thickness, float[] rgba) {
        rectOutline(x, y, width, height, thickness, rgba[0], rgba[1], rgba[2], rgba[3]);
    }

    /**
     * 绘制一行点阵文字（ASCII 与中文混排）。
     *
     * <p>实现用<b>行内游程合并</b>：同一行里连续的亮点合并成一个矩形。
     * 一个 5×7 字形通常只有 8–12 段，而逐像素画是 15–20 个四边形 ——
     * 合并后文字部分的顶点量约减半，且完全不影响显示效果。
     *
     * <p><b>两种字形高度的统一：</b>中文是 12 行、ASCII 是 7 行，
     * 都按 {@link BitmapFont#rowCount(char)} = 12 行的行空间来画，
     * ASCII 由 {@link BitmapFont#pixelAt(char, int, int)} 自动居中到第 3..9 行。
     * 因此 {@code y} 仍是"行顶部"，但一行的实际高度是 {@code 12 × scale}
     * （不是 ASCII 的 7×scale）；排版要用 {@link BitmapFont#lineHeight(int)}。
     *
     * @param scale 像素缩放（1 = 中文 12×12 / ASCII 5×7 的原尺寸）
     * @return 下一段文字的起始 x（便于拼接）
     */
    public float text(float x, float y, String text, int scale,
                      float r, float g, float b, float a) {
        if (!begun || text == null || text.isEmpty() || scale <= 0) {
            return x;
        }
        float cursor = x;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '\n') {
                continue;
            }
            int columns = BitmapFont.columnCount(ch);
            for (int row = 0; row < BitmapFont.rowCount(ch); row++) {
                int runStart = -1;
                for (int column = 0; column <= columns; column++) {
                    boolean lit = column < columns && BitmapFont.pixelAt(ch, column, row);
                    if (lit && runStart < 0) {
                        runStart = column;
                    } else if (!lit && runStart >= 0) {
                        rect(cursor + runStart * scale, y + row * scale,
                                (column - runStart) * scale, scale, r, g, b, a);
                        runStart = -1;
                    }
                }
            }
            cursor += BitmapFont.advanceWidth(ch, scale);
        }
        return cursor;
    }

    /**
     * {@code rgba} 数组重载 —— 与 {@link #rect(float, float, float, float, float[])} 对称，
     * 让集中定义在 {@link UiTheme} 的配色能<b>直接传数组</b>，避免在调用方写
     * {@code color[0], color[1], color[2], color[3]} 这类下标访问。
     */
    public float text(float x, float y, String text, int scale, float[] rgba) {
        return text(x, y, text, scale, rgba[0], rgba[1], rgba[2], rgba[3]);
    }

    /**
     * 带底色的文字行（先铺半透明底再写字），用于 F3 overlay 的可读性。
     *
     * <p><b>M2：底色高度改用 {@link BitmapFont#lineHeight(int)}（每行 12 像素），
     * 不再用 ASCII 的 7 像素。</b>一行里只要出现中文，字形就占满 12 行；
     * 沿用 7 像素会让底色从文字的中段开始，看起来像"文字漏在框外面"。
     * 代价是纯 ASCII 的行底色上下各多出 2–3 像素留白 ——
     * 换来的是中英混排时不出现"有的行有框、有的行没框"的参差。
     */
    public float textWithBackground(float x, float y, String text, int scale, float padding,
                                    float bgR, float bgG, float bgB, float bgA,
                                    float fgR, float fgG, float fgB, float fgA) {
        rect(x - padding, y - padding,
                BitmapFont.textWidth(text, scale) + padding * 2,
                BitmapFont.lineHeight(scale) + padding * 2, bgR, bgG, bgB, bgA);
        return text(x, y, text, scale, fgR, fgG, fgB, fgA);
    }

    /**
     * {@code bg / fg} 数组重载 —— 与 {@link #text(float, float, String, int, float[])} 对称，
     * 让 {@link UiTheme} 的配色能直接传数组，避免调用方写下标访问。
     */
    public float textWithBackground(float x, float y, String text, int scale, float padding,
                                    float[] bg, float[] fg) {
        return textWithBackground(x, y, text, scale, padding,
                bg[0], bg[1], bg[2], bg[3], fg[0], fg[1], fg[2], fg[3]);
    }

    // ============================================================ 上传与绘制

    /** 上传并绘制收集到的所有图元。 */
    public void flush(ShaderProgram uiShader) {
        if (!begun || floatCount == 0) {
            return;
        }
        uiShader.bind();

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        FloatBuffer buffer = MemoryUtil.memAllocFloat(floatCount);
        try {
            buffer.put(data, 0, floatCount).flip();
            // GL_STREAM_DRAW：数据每帧都变，驱动可以放心地"先给新地址再回收旧的"
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);
        } finally {
            MemoryUtil.memFree(buffer);
        }

        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, floatCount / FLOATS_PER_VERTEX);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        ShaderProgram.unbind();

        begun = false;
    }

    private void push(float x, float y, float r, float g, float b, float a) {
        if (floatCount + FLOATS_PER_VERTEX > data.length) {
            float[] bigger = new float[data.length * 2];
            System.arraycopy(data, 0, bigger, 0, floatCount);
            data = bigger;
        }
        data[floatCount++] = x;
        data[floatCount++] = y;
        data[floatCount++] = r;
        data[floatCount++] = g;
        data[floatCount++] = b;
        data[floatCount++] = a;
    }

    private float toNdcX(float pixelX) {
        return (pixelX / framebufferWidth) * 2f - 1f;
    }

    private float toNdcY(float pixelY) {
        return 1f - (pixelY / framebufferHeight) * 2f;
    }

    // ============================================================ 统计与释放

    public int quadCount() {
        return quadCount;
    }

    public int vertexCount() {
        return floatCount / FLOATS_PER_VERTEX;
    }

    public void dispose() {
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
        Log.info("[HUD] UiBatch 已释放");
    }

    /** 供单元测试：像素 → NDC 的换算（与 {@link #rect} 使用同一套逻辑）。 */
    public static float pixelToNdcX(float pixelX, int fbWidth) {
        return (pixelX / Math.max(1, fbWidth)) * 2f - 1f;
    }

    public static float pixelToNdcY(float pixelY, int fbHeight) {
        return 1f - (pixelY / Math.max(1, fbHeight)) * 2f;
    }

    /** 每个四边形 6 个顶点 × 6 个 float。 */
    public static int floatsPerQuad() {
        return VERTICES_PER_QUAD * FLOATS_PER_VERTEX;
    }
}

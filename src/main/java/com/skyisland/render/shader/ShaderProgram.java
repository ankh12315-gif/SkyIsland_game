package com.skyisland.render.shader;

import com.skyisland.util.Log;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 着色器程序（TECH_DESIGN §P 的 {@code render.shader.ShaderProgram}）。
 *
 * <p><b>它与 M0 的 {@code CapabilityProbe} 的区别是本类存在的理由：</b>
 * 探针里的着色器编译是"一次性验证 GL 通路"的脚手架；本类是一个可反复使用的资源对象，
 * 负责编译、链接、uniform 定位缓存与释放，并且在编译失败时给出<u>带源码行号</u>的错误。
 *
 * <p><b>为什么 uniform 位置要缓存：</b>{@code glGetUniformLocation} 是驱动侧的字符串查找，
 * 每帧对每个 uniform 调用一次（本工程 4 个 uniform × 每帧多次）在 3000 FPS 下会变成
 * 每秒几万次字符串查找。缓存后只在首次使用时查一次。
 * 位置为 -1 表示"该 uniform 被优化掉了"（例如某次编译中 uAlpha 未参与计算）——
 * 这不是错误，因此只在第一次遇到时记一条 DEBUG 日志，不作为异常抛出。
 *
 * <p><b>为什么着色器源码放在 classpath 资源里：</b>Maven Shade 打成 fat jar 后
 * 无法用 {@code java.io.File} 访问 jar 内资源（TECH_DESIGN §O.2），
 * 只能走 {@code ClassLoader.getResourceAsStream}。
 */
public final class ShaderProgram {

    private final String label;
    private int program;
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    private ShaderProgram(String label, int program) {
        this.label = label;
        this.program = program;
    }

    // ============================================================ 创建

    /**
     * 从 classpath 资源编译并链接一个程序。
     *
     * @param label    日志与错误信息里用的名字（例如 {@code voxel}）
     * @param vertPath 顶点着色器资源路径（相对于 classpath 根）
     * @param fragPath 片元着色器资源路径
     */
    public static ShaderProgram fromResources(String label, String vertPath, String fragPath) {
        String vertSource = loadResource(vertPath);
        String fragSource = loadResource(fragPath);

        int vert = compile(GL20.GL_VERTEX_SHADER, vertSource, label + ":" + vertPath);
        int frag;
        try {
            frag = compile(GL20.GL_FRAGMENT_SHADER, fragSource, label + ":" + fragPath);
        } catch (RuntimeException e) {
            GL20.glDeleteShader(vert);
            throw e;
        }

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vert);
        GL20.glAttachShader(program, frag);
        GL20.glLinkProgram(program);

        // ★ 无论链接成败都必须 detach + delete，否则 shader 对象会泄漏到进程结束
        GL20.glDetachShader(program, vert);
        GL20.glDetachShader(program, frag);
        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new IllegalStateException("着色器程序链接失败 (" + label + "):\n" + log);
        }
        String linkLog = GL20.glGetProgramInfoLog(program);
        if (!linkLog.isBlank()) {
            Log.noteWarning("GL-Shader", label + " 链接日志: " + linkLog.trim());
        }

        Log.info("[Shader] %s 已就绪（program=%d，%s + %s）", label, program, vertPath, fragPath);
        return new ShaderProgram(label, program);
    }

    private static int compile(int type, String source, String label) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("着色器编译失败 (" + label + "):\n" + log);
        }
        String log = GL20.glGetShaderInfoLog(shader);
        if (!log.isBlank()) {
            Log.noteWarning("GL-Shader", label + " 编译日志: " + log.trim());
        }
        return shader;
    }

    private static String loadResource(String path) {
        try (InputStream in = ShaderProgram.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("着色器资源不存在: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取着色器资源失败: " + path, e);
        }
    }

    // ============================================================ 绑定与 uniform

    public void bind() {
        GL20.glUseProgram(program);
    }

    public static void unbind() {
        GL20.glUseProgram(0);
    }

    /**
     * 查询 uniform 位置（带缓存）。
     *
     * @return 位置；-1 表示被驱动优化掉（调用方必须容忍，不能当作错误）
     */
    public int uniformLocation(String name) {
        Integer cached = uniformLocations.get(name);
        if (cached != null) {
            return cached;
        }
        int location = GL20.glGetUniformLocation(program, name);
        uniformLocations.put(name, location);
        if (location < 0) {
            Log.debug("[Shader] %s 中 uniform '%s' 不存在（多数情况是它不参与最终输出，被驱动优化掉了）",
                    label, name);
        }
        return location;
    }

    public void setMatrix4f(String name, FloatBuffer columnMajorValues) {
        int location = uniformLocation(name);
        if (location < 0) {
            return;
        }
        GL20.glUniformMatrix4fv(location, false, columnMajorValues);
    }

    public void setVec2f(String name, float x, float y) {
        int location = uniformLocation(name);
        if (location >= 0) {
            GL20.glUniform2f(location, x, y);
        }
    }

    public void setFloat(String name, float value) {
        int location = uniformLocation(name);
        if (location >= 0) {
            GL20.glUniform1f(location, value);
        }
    }

    /**
     * 设置 {@code int} uniform —— 目前唯一用途是<b>采样器的纹理单元号</b>。
     *
     * <p><b>为什么必须有它，且必须"逐 pass"重设</b>：
     * {@code glUniform1i} 写的是<b>当前 program</b> 的 uniform 值，
     * 而采样器单元是 program 状态而非全局状态。
     * 换到另一个 program（地形 → 手持物，它们各自 {@code shader.bind()}）时，
     * 采样器会退回默认单元 0 —— 那里通常绑着 UI 纹理，
     * 症状是"方块显示出背包面板的图案"，且**不报任何错**。
     *
     * <p>它同时也是 {@code sampler2DArray} 唯一的绑定入口：
     * 声明了 {@code uniform sampler2DArray} 但从不告知单元号，
     * GLSL 默认取 0，同样不报错。
     */
    public void setInt(String name, int value) {
        int location = uniformLocation(name);
        if (location >= 0) {
            GL20.glUniform1i(location, value);
        }
    }

    /** 便捷重载：把 JOML 矩阵直接喂进去（JOML 的矩阵本身就是列主序）。 */
    public void setMatrix4f(String name, org.joml.Matrix4fc matrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            setMatrix4f(name, matrix.get(stack.mallocFloat(16)));
        }
    }

    // ============================================================ 释放

    public void dispose() {
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
        uniformLocations.clear();
        Log.info("[Shader] %s 已释放", label);
    }

    public int programId() {
        return program;
    }

    public String label() {
        return label;
    }
}

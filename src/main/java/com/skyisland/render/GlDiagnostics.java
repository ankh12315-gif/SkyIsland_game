package com.skyisland.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * GL 错误队列诊断（从 M0 的 {@code CapabilityProbe} 里搬出来的那个静态方法）。
 *
 * <p><b>为什么它必须单独成类：</b>M0 时它挂在探针上，而探针在 M1 交付时被删除
 * （TECH_DESIGN_v0.1.1 §U′.2 的 T-6b）。但"检查并清空 GL 错误队列"这件事
 * 与探针无关 —— 它是一个通用的诊断设施，任何一次 GL 调用之后都可能需要。
 * 把它一起删掉会让"GL 报错了但没人看"成为默认状态。
 *
 * <p><b>为什么要"清空"而不是只查一次：</b>GL 的错误是<u>累积</u>的队列
 * （保留最后的若干个）。只读一条会让后续调用一直读到同一条旧错误，
 * 从而把一个错误误判成"持续报错"。
 */
public final class GlDiagnostics {

    private GlDiagnostics() {
    }

    /**
     * 读出并清空整个 GL 错误队列。
     *
     * @return 以 {@code GL_INVALID_ENUM(0x0500); ...} 形式拼接的错误串；
     *         队列为空时返回 {@code null}（调用方据此判断"没有错误"，而不是比较空串）
     */
    public static String drainError() {
        StringBuilder sb = null;
        int error;
        // 设上限，避免在极端情况下死循环（GL 队列不会无限增长，但防御性写法更安全）
        for (int guard = 0; guard < 64; guard++) {
            error = GL11.glGetError();
            if (error == GL11.GL_NO_ERROR) {
                break;
            }
            if (sb == null) {
                sb = new StringBuilder();
            } else {
                sb.append("; ");
            }
            sb.append(errorName(error));
        }
        return sb == null ? null : sb.toString();
    }

    public static String errorName(int error) {
        return switch (error) {
            case GL11.GL_INVALID_ENUM -> "GL_INVALID_ENUM(0x0500)";
            case GL11.GL_INVALID_VALUE -> "GL_INVALID_VALUE(0x0501)";
            case GL11.GL_INVALID_OPERATION -> "GL_INVALID_OPERATION(0x0502)";
            case GL11.GL_STACK_OVERFLOW -> "GL_STACK_OVERFLOW(0x0503)";
            case GL11.GL_STACK_UNDERFLOW -> "GL_STACK_UNDERFLOW(0x0504)";
            case GL11.GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY(0x0505)";
            case GL30.GL_INVALID_FRAMEBUFFER_OPERATION -> "GL_INVALID_FRAMEBUFFER_OPERATION(0x0506)";
            default -> "0x" + Integer.toHexString(error);
        };
    }
}

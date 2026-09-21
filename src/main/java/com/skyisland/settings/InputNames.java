package com.skyisland.settings;

import org.lwjgl.glfw.GLFW;

/**
 * 键码 / 鼠标键码 → 可读名（<b>全工程唯一的一张表</b>）。
 *
 * <p><b>为什么必须唯一：</b>这张表会同时出现在三个地方 ——
 * 日志（"键盘事件: PRESS key=W"）、设置界面（"键位: W"）、
 * 以及重绑冲突对话框（"该键已绑定到 Forward"）。三处各写一份，
 * 迟早出现"日志里显示 F5、设置界面里显示 KEY_294"这种让人怀疑配置没生效的差异。
 *
 * <p>引用 {@code GLFW.GLFW_KEY_*} 常量而不是字面数字：这些常量在编译期被内联为 int，
 * 因此本类在运行期<b>不会</b>触发 GLFW 库加载 —— 纯逻辑单元测试可以直接调用它。
 */
public final class InputNames {

    private InputNames() {
    }

    /**
     * 键码 → 可读名。未收录的键回落到 {@code KEY_<code>}，
     * 未绑定（{@link #UNBOUND_CODE}）回落为 {@code (none)}。
     */
    public static String keyName(int key) {
        if (key < 0) {
            return "(none)";
        }
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> "SPACE";
            case GLFW.GLFW_KEY_APOSTROPHE -> "'";
            case GLFW.GLFW_KEY_COMMA -> ",";
            case GLFW.GLFW_KEY_MINUS -> "-";
            case GLFW.GLFW_KEY_PERIOD -> ".";
            case GLFW.GLFW_KEY_SLASH -> "/";
            case GLFW.GLFW_KEY_SEMICOLON -> ";";
            case GLFW.GLFW_KEY_EQUAL -> "=";
            case GLFW.GLFW_KEY_LEFT_BRACKET -> "[";
            case GLFW.GLFW_KEY_BACKSLASH -> "\\";
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> "]";
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> "`";
            case GLFW.GLFW_KEY_ESCAPE -> "ESC";
            case GLFW.GLFW_KEY_ENTER -> "ENTER";
            case GLFW.GLFW_KEY_TAB -> "TAB";
            case GLFW.GLFW_KEY_BACKSPACE -> "BACKSPACE";
            case GLFW.GLFW_KEY_INSERT -> "INSERT";
            case GLFW.GLFW_KEY_DELETE -> "DELETE";
            case GLFW.GLFW_KEY_RIGHT -> "RIGHT";
            case GLFW.GLFW_KEY_LEFT -> "LEFT";
            case GLFW.GLFW_KEY_DOWN -> "DOWN";
            case GLFW.GLFW_KEY_UP -> "UP";
            case GLFW.GLFW_KEY_PAGE_UP -> "PAGE_UP";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PAGE_DOWN";
            case GLFW.GLFW_KEY_HOME -> "HOME";
            case GLFW.GLFW_KEY_END -> "END";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "CAPS_LOCK";
            case GLFW.GLFW_KEY_SCROLL_LOCK -> "SCROLL_LOCK";
            case GLFW.GLFW_KEY_NUM_LOCK -> "NUM_LOCK";
            case GLFW.GLFW_KEY_PRINT_SCREEN -> "PRINT_SCREEN";
            case GLFW.GLFW_KEY_PAUSE -> "PAUSE";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LEFT_SHIFT";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LEFT_CONTROL";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LEFT_ALT";
            case GLFW.GLFW_KEY_LEFT_SUPER -> "LEFT_SUPER";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RIGHT_SHIFT";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RIGHT_CONTROL";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RIGHT_ALT";
            case GLFW.GLFW_KEY_RIGHT_SUPER -> "RIGHT_SUPER";
            case GLFW.GLFW_KEY_MENU -> "MENU";
            default -> fallbackKey(key);
        };
    }

    private static String fallbackKey(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            return String.valueOf((char) ('A' + (key - GLFW.GLFW_KEY_A)));
        }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
            return String.valueOf((char) ('0' + (key - GLFW.GLFW_KEY_0)));
        }
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25) {
            return "F" + (1 + key - GLFW.GLFW_KEY_F1);
        }
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) {
            return "KP_" + (key - GLFW.GLFW_KEY_KP_0);
        }
        if (key >= GLFW.GLFW_KEY_KP_DECIMAL && key <= GLFW.GLFW_KEY_KP_EQUAL) {
            return switch (key) {
                case GLFW.GLFW_KEY_KP_DECIMAL -> "KP_DECIMAL";
                case GLFW.GLFW_KEY_KP_DIVIDE -> "KP_DIVIDE";
                case GLFW.GLFW_KEY_KP_MULTIPLY -> "KP_MULTIPLY";
                case GLFW.GLFW_KEY_KP_SUBTRACT -> "KP_SUBTRACT";
                case GLFW.GLFW_KEY_KP_ADD -> "KP_ADD";
                case GLFW.GLFW_KEY_KP_ENTER -> "KP_ENTER";
                default -> "KP_EQUAL";
            };
        }
        return "KEY_" + key;
    }

    public static String mouseButtonName(int button) {
        return switch (button) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> "MOUSE_LEFT";
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> "MOUSE_RIGHT";
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> "MOUSE_MIDDLE";
            case 3 -> "MOUSE_4";
            case 4 -> "MOUSE_5";
            case 5 -> "MOUSE_6";
            case 6 -> "MOUSE_7";
            case 7 -> "MOUSE_8";
            default -> button < 0 ? "(none)" : "MOUSE_" + (button + 1);
        };
    }
}

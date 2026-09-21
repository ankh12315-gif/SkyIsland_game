package com.skyisland.settings;

import java.util.Locale;

/**
 * 一条输入绑定（M1.5 规格第 4/5 条）：一个 {@link Action} 当前挂在哪个物理输入上。
 *
 * <p><b>为什么键盘与鼠标必须是两个命名空间：</b>GLFW 的键码与鼠标键码是两套独立的
 * 整数序列（{@code GLFW_KEY_W = 87}，{@code GLFW_MOUSE_BUTTON_LEFT = 0}）。
 * 若只存一个裸 int，那么"键盘 0 号键"与"鼠标左键"就无法区分，
 * 冲突检测会莫名其妙地把两者判成同一个键 —— 于是"把前进绑到鼠标左键"会被
 * 误判为与某个键盘键冲突，而且这种错误只在特定键码上出现，极难定位。
 * 因此这里显式带 {@code Kind}。
 *
 * <p><b>落盘格式：</b>{@code "key:87"} / {@code "mouse:0"}。
 * 用字符串而不是嵌套对象，是因为它要出现在 {@code settings.json} 里被人工阅读与手改 ——
 * {@code {"kind":"KEY","code":87}} 在同一行里要占三倍的宽度，
 * 而配置文件的可读性直接决定了"出问题时能不能自己改回去"。
 * 同时字符串格式让"未知动作 id"这类前向兼容问题退化成一行的容错解析。
 *
 * <p><b>未绑定：</b>用 {@code code = -1} 表示。冲突确认时被抢走键位的一方会进入未绑定态；
 * 若不允许未绑定，就必须立刻给它找一个替代键，那是替用户做决定。
 */
public record InputBinding(Kind kind, int code) {

    /** 输入类别。 */
    public enum Kind {
        KEY("key"),
        MOUSE("mouse");

        private final String tag;

        Kind(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }

        static Kind fromTag(String tag) {
            for (Kind k : values()) {
                if (k.tag.equalsIgnoreCase(tag)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** 未绑定：{@code code = -1}。 */
    public static final int UNBOUND_CODE = -1;

    public static final InputBinding UNBOUND = new InputBinding(Kind.KEY, UNBOUND_CODE);

    public static InputBinding key(int keyCode) {
        return new InputBinding(Kind.KEY, keyCode);
    }

    public static InputBinding mouse(int button) {
        return new InputBinding(Kind.MOUSE, button);
    }

    public boolean isBound() {
        return code >= 0;
    }

    public boolean isKey() {
        return kind == Kind.KEY;
    }

    public boolean isMouse() {
        return kind == Kind.MOUSE;
    }

    /** 落盘字符串，如 {@code "key:87"}。 */
    public String serialize() {
        return kind.tag() + ':' + code;
    }

    /** 界面显示名，如 {@code "W"} / {@code "MOUSE_LEFT"} / {@code "(none)"}。 */
    public String display() {
        if (!isBound()) {
            return "(none)";
        }
        return isKey() ? InputNames.keyName(code) : InputNames.mouseButtonName(code);
    }

    /**
     * 解析落盘字符串。非法输入抛 {@link IllegalArgumentException}。
     *
     * <p>这里<b>故意抛异常而不是静默返回未绑定</b>：跨进程边界的数据（文件）损坏时，
     * "静默降级"会让损坏的配置看起来像是用户自己设的。抛出后由
     * {@link KeyBindings#fromMap} 捕获、告警、并在<u>保留其余正确项</u>的前提下回退默认值。
     */
    public static InputBinding parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("绑定字符串为 null");
        }
        String trimmed = text.trim();
        int colon = trimmed.indexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            throw new IllegalArgumentException("缺少 '类别:代码' 结构: " + trimmed);
        }
        String tag = trimmed.substring(0, colon);
        String codeText = trimmed.substring(colon + 1);
        Kind kind = Kind.fromTag(tag);
        if (kind == null) {
            throw new IllegalArgumentException("未知输入类别: " + tag);
        }
        int code;
        try {
            code = Integer.parseInt(codeText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("键码不是整数: " + codeText);
        }
        if (code < UNBOUND_CODE) {
            throw new IllegalArgumentException("键码越界: " + code);
        }
        return new InputBinding(kind, code);
    }

    /** 解析并容忍失败：失败时返回 {@code fallback}。 */
    public static InputBinding parseOrDefault(String text, InputBinding fallback) {
        try {
            return parse(text);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** 供日志使用：{@code "key:87 (W)"}。 */
    @Override
    public String toString() {
        return serialize() + " (" + display() + ")";
    }

    /** 大小写无关的相等判定（供测试与容错路径复用）。 */
    public boolean sameAs(InputBinding other) {
        return other != null && kind == other.kind && code == other.code;
    }

    /** 便于日志输出的短标签：{@code kind=KEY code=87}。 */
    public String describe() {
        return String.format(Locale.ROOT, "kind=%s code=%d display=%s",
                kind.name(), code, display());
    }
}

package com.skyisland.settings;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 输入绑定的表示与容错解析（M1.5 规格第 4/5 条）。
 *
 * <p><b>这个类要守住的东西，"看起来"只是几个 getter，实际上是三件容易做错的判断：</b>
 * <ol>
 *   <li><b>键盘与鼠标必须是两个命名空间。</b>GLFW 的 {@code KEY_W = 87} 与
 *       {@code MOUSE_BUTTON_LEFT = 0} 是两套互不相干的整数序列。若把它们塞进同一个
 *       裸 int，{@code key:0} 与 {@code mouse:0} 就会互相判等 ——
 *       冲突检测会莫名其妙地拒绝一次合法的重绑，而且只在特定键码上复现。
 *       这里的 {@link #keyAndMouseWithSameNumericCodeAreNotEqual()} 就是钉住这一点。</li>
 *   <li><b>未绑定必须是可表达的状态。</b>冲突确认后原占用者进入未绑定态。
 *       若不允许未绑定，就必须替用户挑一个替代键 —— 那是替用户做决定。</li>
 *   <li><b>非法字符串必须抛异常，而不是静默降级。</b>跨进程边界的数据（settings.json）
 *       损坏时，"悄悄当作未绑定"会让一次文件损坏看起来像是用户自己设的。
 *       抛出后由 {@link KeyBindings#fromMap} 捕获、告警、并保留其余正确项。</li>
 * </ol>
 */
class InputBindingTest {

    // ============================================================ 序列化往返

    @Test
    void serializesToStableHumanReadableForm() {
        assertEquals("key:87", InputBinding.key(GLFW.GLFW_KEY_W).serialize());
        assertEquals("mouse:0", InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_LEFT).serialize());
        assertEquals("key:-1", InputBinding.UNBOUND.serialize(),
                "未绑定也要有稳定写法 —— 否则它在文件里会消失，和'没这项'无法区分");
    }

    @Test
    void parseRoundTripsEveryKind() {
        for (InputBinding b : new InputBinding[]{
                InputBinding.key(GLFW.GLFW_KEY_W),
                InputBinding.key(GLFW.GLFW_KEY_SPACE),
                InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_LEFT),
                InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT),
                InputBinding.UNBOUND}) {
            assertEquals(b, InputBinding.parse(b.serialize()),
                    "写出再读回必须得到同一个绑定：" + b.serialize());
        }
    }

    @Test
    void parseToleratesSurroundingWhitespaceAndCase() {
        assertEquals(InputBinding.key(87), InputBinding.parse("  KEY:87  "),
                "手改配置文件时很容易带上空格或改成大写，不应因此失效");
        assertEquals(InputBinding.mouse(0), InputBinding.parse("Mouse:0"));
    }

    // ============================================================ 命名空间

    @Test
    void keyAndMouseWithSameNumericCodeAreNotEqual() {
        InputBinding keyZero = InputBinding.key(0);
        InputBinding mouseZero = InputBinding.mouse(0);

        assertNotEquals(keyZero, mouseZero);
        assertFalse(keyZero.sameAs(mouseZero),
                "键盘 0 号键与鼠标左键必须被判为不同输入，否则冲突检测会误报");
        assertFalse(mouseZero.sameAs(keyZero));
    }

    @Test
    void sameAsIsNullSafe() {
        assertFalse(InputBinding.key(87).sameAs(null));
    }

    // ============================================================ 未绑定

    @Test
    void unboundIsNotBoundAndDisplaysAsNone() {
        assertFalse(InputBinding.UNBOUND.isBound());
        assertEquals("(none)", InputBinding.UNBOUND.display());
        assertEquals("(none)", InputNames.keyName(InputBinding.UNBOUND_CODE));
    }

    @Test
    void negativeCodeOtherThanUnboundIsStillUnbound() {
        // 文件里可能出现更小的负数（手工写坏或旧版本遗留）。它不该被当成"某个键"。
        assertFalse(new InputBinding(InputBinding.Kind.KEY, -7).isBound());
    }

    // ============================================================ 非法输入

    @Test
    void parseRejectsMalformedStrings() {
        for (String bad : new String[]{null, "", "   ", "key", "key:", ":87", "gamepad:3",
                "key:abc", "key:1.5", "key:--1", "key:-2"}) {
            assertThrows(IllegalArgumentException.class, () -> InputBinding.parse(bad),
                    "非法绑定串必须抛出，交由调用方告警并保留默认值：" + bad);
        }
    }

    @Test
    void parseOrDefaultFallsBackInsteadOfThrowing() {
        InputBinding fallback = InputBinding.key(GLFW.GLFW_KEY_W);

        assertEquals(fallback, InputBinding.parseOrDefault("garbage", fallback));
        assertEquals(InputBinding.key(75), InputBinding.parseOrDefault("key:75", fallback),
                "合法输入不得被 fallback 覆盖");
    }

    // ============================================================ 显示名

    @Test
    void displayUsesTheSharedNameTable() {
        assertEquals("W", InputBinding.key(GLFW.GLFW_KEY_W).display());
        assertEquals("SPACE", InputBinding.key(GLFW.GLFW_KEY_SPACE).display());
        assertEquals("ESC", InputBinding.key(GLFW.GLFW_KEY_ESCAPE).display());
        assertEquals("MOUSE_LEFT", InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_LEFT).display());
        assertEquals("MOUSE_RIGHT", InputBinding.mouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT).display());
    }

    @Test
    void unknownKeyCodeFallsBackToReadableNumericForm() {
        // 200 落在 GLFW 的保留空洞里（不在字母/数字/F 键/小键盘任何区间内），
        // 因此必然走 "KEY_<code>" 这条回落路径。
        String name = InputBinding.key(200).display();
        assertEquals("KEY_200", name,
                "未收录的键必须回落成可读形式，而不是空白或抛异常");
    }

    @Test
    void toStringCarriesBothFormsForLogs() {
        assertEquals("key:87 (W)", InputBinding.key(GLFW.GLFW_KEY_W).toString());
    }
}

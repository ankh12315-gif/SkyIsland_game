package com.skyisland.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ <b>飞行下降键必须走键位表</b>（PRD：可重绑）。
 *
 * <h2>为什么这件事值得一条守卫</h2>
 * 主理人报「我按 shift 键会切换中英文，中文状态下我没法玩了」。查下来切换来自
 * <b>Windows 输入法</b>（游戏里根本没有语言动作：{@code Action} 枚举 10 项里没有它，
 * {@code Localization} 也只有 zh-CN 一种实现）。但顺着这条线查出了一处真缺陷：
 *
 * <p>{@code InputMapper} 曾经<b>直读</b> {@code LEFT_SHIFT || RIGHT_SHIFT}，
 * 注释里的理由是「Shift 是与创造飞行绑定的固定修饰键，不进键位表」。
 * 那个理由的代价是：<b>不可重绑</b>。于是输入法占用 Shift 时，
 * 飞行下降在一个完全正常的系统配置下不可用，而玩家除了改系统设置无从下手。
 *
 * <p>更矛盾的是：键位表里<b>早就有</b> {@code Action.CROUCH}，默认绑 LEFT_SHIFT、
 * 可重绑、可落盘 —— 却<b>零消费方</b>，设置界面里挂着一行
 * {@code "Crouch [M2]"}（意思是"这个键还按不动"）。
 * 同一件事两套真相：真正在用的键绕开了键位表，而键位表里那一格是死的。
 *
 * <p>⇒ 本类守住"消费方读动作、不直读物理键"这条，不再退回硬编码。
 *
 * <h2>判据一律读<b>去掉注释后的源码</b></h2>
 * 解释性注释里必然会出现 {@code GLFW_KEY_LEFT_SHIFT} 这类字样
 * （本类要解释的就是"以前为什么直读它"），
 * 全文 {@code contains} 会被说明文字满足 —— 那条断言就永远绿。
 */
class SneakKeyBindingTest {

    private static final Path MAPPER =
            Path.of("src", "main", "java", "com", "skyisland", "input", "InputMapper.java");
    private static final Path GAME =
            Path.of("src", "main", "java", "com", "skyisland", "game", "SkyIslandGame.java");
    private static final Path KB =
            Path.of("src", "main", "java", "com", "skyisland", "settings", "KeyBindings.java");

    private static String codeWithoutComments(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    @Test
    @DisplayName("★ 飞行下降读 Action.CROUCH，不再直读 GLFW 的 Shift")
    void sneakGoesThroughTheBindingTable() throws IOException {
        String mapper = codeWithoutComments(MAPPER);

        assertTrue(mapper.contains("actionHeld(in, bindings, Action.CROUCH)"),
                "★ InputMapper 的 sneak 必须读 Action.CROUCH —— 直读意味着不可重绑，"
                        + "而中文 Windows 上 Shift 被输入法占用（中英切换），"
                        + "于是飞行下降在完全正常的系统配置下不可用");

        assertFalse(mapper.contains("GLFW_KEY_LEFT_SHIFT") || mapper.contains("GLFW_KEY_RIGHT_SHIFT"),
                "★ InputMapper 不得再出现 GLFW_KEY_*_SHIFT —— 一旦它回来了，"
                        + "就会同时存在「两套真相」：消费方直读物理键，"
                        + "而键位表里那一格看起来可改、实际按不动");
    }

    @Test
    @DisplayName("CROUCH 的默认键位仍是左 Shift（改动不改变手感）")
    void theDefaultKeyIsUnchanged() throws IOException {
        String kb = codeWithoutComments(KB);
        assertTrue(kb.contains("Action.CROUCH, InputBinding.key(GLFW.GLFW_KEY_LEFT_SHIFT)"),
                "★ CROUCH 的默认键位必须仍是左 Shift —— 改成别的会让现有玩家的手感无故改变。"
                        + "这次改动的全部意义是「终于可以改了」，不是「默认变了」");
    }

    @Test
    @DisplayName("CROUCH 必须在设置界面存在（否则玩家无处可改）")
    void crouchIsListedInTheSettingsMenu() throws IOException {
        String menus = codeWithoutComments(
                Path.of("src", "main", "java", "com", "skyisland", "ui", "Menus.java"));

        assertTrue(menus.contains("bindId(a)") && menus.contains("a.shortNote()"),
                "设置界面必须逐个列出 Action —— 若键位菜单不再遍历 Action，"
                        + "那么「可重绑」这件事对 CROUCH 就是假的");
    }

    @Test
    @DisplayName("背包 Shift+左键仍刻意直读（取舍必须写在注释里，否则会被当漏改）")
    void theInventoryShiftClickIsDocumentedAsIntentional() throws IOException {
        String game = codeWithoutComments(GAME);

        // 它确实还在直读 Shift —— 这是**刻意**的：鼠标操作的修饰键不是游戏动作，
        // 放进键位表会让"改 CROUCH"顺手把背包快速搬运也改了。
        assertTrue(game.contains("boolean shift = input.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)"),
                "背包的 Shift+左键快速搬运应当仍然是直读 —— "
                        + "它是鼠标修饰键而非游戏动作。若要改成可重绑，"
                        + "必须先确认那是产品想要的（默认键会变、UI 提示也要改）");

        // 判据是"取舍被写下来"：本项目反复吃亏于"刻意为之"被后人当成漏改。
        String raw = Files.readString(GAME, StandardCharsets.UTF_8);
        assertTrue(raw.contains("刻意保持直读"),
                "★ 直读的取舍必须在源码里写明理由 —— 否则下一个人会"
                        + "「顺手修正」它，而那会把背包快速搬运悄悄改成可重绑");
    }
}
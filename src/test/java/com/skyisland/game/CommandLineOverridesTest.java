package com.skyisland.game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行参数到系统属性的提升（M1.5 对 M1 启动契约的修复）。
 *
 * <p><b>这项测试守的是一个"静默失效"的缺陷。</b>启动脚本是
 * {@code java <jvm-args> -jar skyisland.jar <脚本参数>}，
 * 因此写在前者里的 {@code -D...} 是程序参数、不会变成系统属性；
 * 而 {@code main} 在 M1.5 之前从不读 {@code args}。
 * 两个"不"叠起来，导致脚本注释里承诺的
 * {@code run-m1.bat -Dskyisland.selfTest=true} 从未生效 ——
 * 现象仅仅是"窗口开着不退出"，与"我本来就想试玩一下"无法区分，
 * 所以它<u>只能靠这一类测试暴露</u>。
 */
class CommandLineOverridesTest {

    private static final String KEY = "skyisland.__test.override";
    private static final String PATH_KEY = "skyisland.__test.path";

    @AfterEach
    void clear() {
        System.clearProperty(KEY);
        System.clearProperty(PATH_KEY);
    }

    @Test
    void aDashDArgumentBecomesASystemProperty() {
        assertNull(System.getProperty(KEY));

        List<String> notes = SkyIslandGame.applyCommandLineOverrides(
                new String[]{"-D" + KEY + "=true"});

        assertEquals("true", System.getProperty(KEY),
                "脚本把 -D 放在 -jar 之后，因此必须由程序自己把它提升为系统属性");
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains(KEY));
    }

    @Test
    void aKeyWithoutAValueMeansTrue() {
        SkyIslandGame.applyCommandLineOverrides(new String[]{"-D" + KEY});

        assertEquals("true", System.getProperty(KEY),
                "只有键名时按 true 处理，这样 'skyisland.selfTest' 这种写法也能用");
    }

    @Test
    void valuesMayContainEqualsSigns() {
        SkyIslandGame.applyCommandLineOverrides(
                new String[]{"-D" + PATH_KEY + "=a=b=c"});

        assertEquals("a=b=c", System.getProperty(PATH_KEY),
                "路径里带 = 是常见的（如 base64 或查询串），不能在第一个 = 处截断");
    }

    @Test
    void aRealJvmPropertyWinsOverTheScriptValue() {
        System.setProperty(KEY, "fromJvm");

        List<String> notes = SkyIslandGame.applyCommandLineOverrides(
                new String[]{"-D" + KEY + "=fromScript"});

        assertEquals("fromJvm", System.getProperty(KEY),
                "写在 -jar 之前的 JVM 参数优先级更高：手动指定不该被脚本默认值盖掉");
        assertTrue(notes.get(0).contains("被忽略"),
                "被忽略的参数必须留下记录，否则'我明明传了'会变成一场无证据的争论");
    }

    @Test
    void unknownArgumentsAreReportedNotSilentlyDropped() {
        List<String> notes = SkyIslandGame.applyCommandLineOverrides(
                new String[]{"--verbose", "play"});

        assertEquals(2, notes.size());
        assertTrue(notes.get(0).contains("--verbose"));
        assertTrue(notes.get(1).contains("play"),
                "拼错的参数必须被报出来 —— 静默忽略会让'参数没效果'无从定位");
    }

    @Test
    void blankArgumentsAreIgnoredWithoutNoise() {
        List<String> notes = SkyIslandGame.applyCommandLineOverrides(new String[]{"", "   ", null});

        assertTrue(notes.isEmpty());
    }

    @Test
    void applyIsNullSafe() {
        assertNotNull(SkyIslandGame.applyCommandLineOverrides(null));
        assertTrue(SkyIslandGame.applyCommandLineOverrides(null).isEmpty());
    }

    @Test
    void aDashWithoutAPropertyNameIsReported() {
        List<String> notes = SkyIslandGame.applyCommandLineOverrides(new String[]{"-D=1"});

        assertEquals(1, notes.size());
        assertFalse(notes.get(0).isBlank());
    }

    @Test
    void multipleOverridesAllApplyInOrder() {
        SkyIslandGame.applyCommandLineOverrides(new String[]{
                "-D" + KEY + "=x",
                "-Dskyisland.uiSelfTest=true",
                "-Dskyisland.startState=menu"});

        assertEquals("x", System.getProperty(KEY));
        assertEquals("true", System.getProperty("skyisland.uiSelfTest"));
        assertEquals("menu", System.getProperty("skyisland.startState"));

        System.clearProperty("skyisland.uiSelfTest");
        System.clearProperty("skyisland.startState");
    }
}

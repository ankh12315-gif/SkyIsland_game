package com.skyisland.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本号护栏。
 *
 * <h2>为什么版本号值得一条断言</h2>
 * 本项目的所有取证工作都建立在"报告里写的版本 = 玩家跑的那个构建"之上。
 * 三处版本来源里有两处是手工同步的（{@code pom.xml} 与 {@code version.properties} 的
 * {@code version} 行），因此"升了 pom 忘了升资源"或"资源没打进 jar"这两件事都可能发生，
 * 而它们的表现都只是<u>屏幕上一行不起眼的数字不对</u>。
 *
 * <p>于是这里把版本号钉成一个<b>显式常量</b>：每次升版本都必须同时改这里，
 * 而这个改动会出现在 diff 里 —— 它把"忘了升"从一种静默失误变成一次看得见的动作。
 *
 * <p>另一条同样重要：{@link Version#version()} 必须是 jar 里真实读到的那份资源，
 * 而不是 {@code FALLBACK} 哨兵。资源一旦没打进去，"报告里引用的版本号"就会变成假证据，
 * 而当时不会有任何东西变红。
 */
class VersionTest {

    /** 与 {@code pom.xml} 的 {@code project.version} 必须一致。 */
    private static final String EXPECTED_VERSION = "0.3.1-M2_1-COMBAT-FEEL";

    @Test
    void theVersionResourceIsOnTheClasspathNotFallingBackToTheSentinel() {
        String version = Version.version();

        assertFalse(version.contains("MISSING"),
                "version.properties 没被打进 classpath —— 当前读到的是哨兵值：" + version
                        + "（报告里引用它就是假证据）");
        assertTrue(version.contains("M2_1"),
                "读到的版本看起来不属于 M2.1：" + version);
    }

    @Test
    void theVersionMatchesTheBuildThisReportTalksAbout() {
        assertEquals(EXPECTED_VERSION, Version.version(),
                "版本号与 M2.1 报告里引用的不一致 —— 要么忘了升 pom/version.properties，"
                        + "要么忘了改这条常量");
    }

    @Test
    void displayCombinesTheVersionWithAHumanReadableLabel() {
        String display = Version.display();

        assertTrue(display.startsWith(EXPECTED_VERSION), display);
        assertTrue(display.contains(Version.buildLabel()), display);
        assertFalse(Version.buildLabel().isBlank(), "构建标签不得为空");
    }
}

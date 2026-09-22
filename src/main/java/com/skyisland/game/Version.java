package com.skyisland.game;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 版本信息。构建时由 {@code pom.xml} 的 {@code project.version} 写入
 * {@code src/main/resources/version.properties}（当前为手工同步）。
 *
 * <p>用途：启动横幅、大厅/主菜单封面上的版本号、以及后续里程碑的存档兼容提示
 * （PRD 12.3）。三处必须同源 —— 否则"报告里写的版本"与"玩家看到的版本"
 * 可能不是同一个构建，这类不一致会让所有取证工作都失去基准。
 */
public final class Version {

    /*
     * FALLBACK 只在 version.properties 加载失败时出现。
     *
     * 它以前写的是 "0.2.0-M1-FIRST-PLAYABLE"，那是个陷阱：资源一旦没打进 jar，
     * 程序会<u>静默</u>报出一个旧里程碑的版本号，而报告里引用这个版本号就变成了
     * 假证据。现在改成一眼可辨的哨兵值 —— 加载失败必须是<u>可见的</u>故障，
     * 不能伪装成"一个正常的旧版本"。
     */
    private static final String FALLBACK = "0.0.0-VERSION-RESOURCE-MISSING";
    private static final String BUILD_LABEL = "M2.2 UI & Inventory";

    private static final String VERSION = load();

    private Version() {
    }

    public static String version() {
        return VERSION;
    }

    public static String buildLabel() {
        return BUILD_LABEL;
    }

    public static String display() {
        return VERSION + " (" + BUILD_LABEL + ")";
    }

    private static String load() {
        try (InputStream in = Version.class.getClassLoader()
                .getResourceAsStream("version.properties")) {
            if (in == null) {
                return FALLBACK;
            }
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version", FALLBACK);
        } catch (IOException e) {
            return FALLBACK;
        }
    }
}

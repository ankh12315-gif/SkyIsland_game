package com.skyisland.save;

import java.nio.file.Path;

/**
 * 存档格式的常量与路径规则（TECH_DESIGN §N.1 / §N.4）。
 *
 * <p><b>集中定义的意义：</b>"存档目录名"、"魔数"、"格式版本"这三样东西
 * 一旦分散在多个文件里，改动时漏掉一处就会表现为"存档读不出来"，
 * 而这类问题的排查成本极高（要对着十六进制看文件头）。
 */
public final class SaveFormat {

    /**
     * 存档<b>格式</b>版本。
     *
     * <p>与"地形生成版本"（{@code WorldGenerator#generationVersion()}）<u>必须分开</u>：
     * 改变存档结构的后果是"需要迁移代码或拒绝加载"；
     * 改变地形算法的后果只是"未探索区域与新版本不同"，增量数据仍然可读。
     * 把两者合成一个版本号会让"只改了地形"也触发一次存档迁移。
     */
    public static final int SAVE_VERSION = 1;

    /** 区块文件魔数：{@code "SKIC"} = SkyIsland Chunk。 */
    public static final byte[] CHUNK_MAGIC = {'S', 'K', 'I', 'C'};

    /** 区块二进制格式版本（与 {@link #SAVE_VERSION} 独立演进）。 */
    public static final int CHUNK_FORMAT_VERSION = 1;

    public static final String LEVEL_FILE = "level.json";
    public static final String PLAYER_FILE = "player.json";
    public static final String CHUNK_DIR = "chunks";

    /** 便携模式标记文件：与可执行文件同目录存在时，存档放 {@code ./saves/}（§N.1）。 */
    public static final String PORTABLE_MARKER = "portable.txt";

    /** 默认世界名。M1 只有一个世界，因此不提供世界选择界面。 */
    public static final String DEFAULT_WORLD_NAME = "first-playable";

    private SaveFormat() {
    }

    /** 区块文件名：{@code c.<cx>.<cz>.bin}（§N.1 冻结的命名）。 */
    public static String chunkFileName(int cx, int cz) {
        return "c." + cx + "." + cz + ".bin";
    }

    /**
     * 解析存档根目录。
     *
     * <p>规则（§N.1，零成本地兼顾"打包分发后免安装试玩"）：
     * <ol>
     *   <li>显式系统属性 {@code -Dskyisland.saveDir=...} 优先（自动化测试与自测脚本用）；</li>
     *   <li>工作目录下存在 {@code portable.txt} → 用 {@code ./saves}；</li>
     *   <li>否则用 {@code %APPDATA%/SkyIsland/saves}（非 Windows 回落到用户主目录）。</li>
     * </ol>
     */
    public static Path resolveSaveRoot() {
        String override = System.getProperty("skyisland.saveDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        Path cwd = Path.of("").toAbsolutePath();
        if (java.nio.file.Files.exists(cwd.resolve(PORTABLE_MARKER))) {
            return cwd.resolve("saves");
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData).resolve("SkyIsland").resolve("saves");
        }
        return Path.of(System.getProperty("user.home", ".")).resolve(".skyisland").resolve("saves");
    }

    /** 世界目录：{@code <saveRoot>/<世界名>/}。 */
    public static Path worldDirectory(Path saveRoot, String worldName) {
        return saveRoot.resolve(worldName);
    }
}

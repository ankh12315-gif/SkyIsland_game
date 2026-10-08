package com.skyisland.save;

import com.skyisland.game.GameMode;
import com.skyisland.player.Player;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★<b>M4-S6 接线守卫：模式真的被写进 {@code level.json}，且真的锁得住。</b>
 *
 * <h3>为什么纯枚举测试不够</h3>
 * {@link LevelMetaGameModeTest} 证的是"枚举与字段的语义正确"，
 * <b>它完全可以在一个从未被调用的 {@code gameMode} 字段上全绿</b>。
 * 那是本项目最典型的"死接线"形态：定义齐全、语义正确、报告漂亮，
 * 而 {@code level.json} 里就是没有这个键。
 *
 * <p>本类因此只问<b>磁盘上的事实</b>：{@code level.json} 里有没有那个键、
 * 值是什么、改了命令行之后会不会变。
 * <b>判据全部落在文件内容上，不落在任何 Java 对象上。</b>
 *
 * <h3>★ 为什么"读文件看有没有那个键"必须是独立断言</h3>
 * 可以只用 {@code SaveManager#readLevelMeta()} 读回来断言 —— 但那样
 * {@code readLevelMeta()} 若把整个文件读坏了返回 {@code null}，
 * 测试会因为 NPE 或断言失败而红，<b>看起来是同一件事</b>，实际诊断信息完全不同。
 * 直接 {@link Files#readString} 让"字段没写出去"与"字段写出去但读不回来"
 * 成为两个可区分的失败。
 */
class SaveManagerGameModeWiringTest {

    private static final String WORLD_NAME = "s6-world";

    private static SaveManager manager(Path root, GameMode configured) {
        return new SaveManager(root, WORLD_NAME, configured);
    }

    private static World world() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    private static Player player() {
        return new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
    }

    /** 直接读磁盘上的 {@code level.json} 原文（不走 Gson / 不走生产读取路径）。 */
    private static String rawLevelJson(SaveManager manager) throws IOException {
        return Files.readString(
                manager.worldDirectory().resolve(SaveFormat.LEVEL_FILE),
                StandardCharsets.UTF_8);
    }

    // ============================================================ ① 真的写进磁盘

    /**
     * ★ <b>新存档保存后，{@code level.json} 里必须出现 {@code gameMode} 键。</b>
     *
     * <p>这是本类最直接的一条接线断言：它读的是<b>字节</b>，不是对象。
     * 若有人删掉 {@code buildLevelMeta} 里那行 {@code meta.gameMode = ...}，本条立刻红。
     */
    @Test
    void aFreshSaveActuallyWritesTheGameModeKey(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root, GameMode.CREATIVE);
        assertTrue(manager.save(world(), player()).success());

        String json = rawLevelJson(manager);
        assertTrue(json.contains("\"gameMode\""),
                "★ level.json 里必须真的有 gameMode 键。实际内容：\n" + json);
        assertTrue(json.contains("\"gameMode\": \"creative\"")
                        || json.contains("\"gameMode\": \"creative\""),
                "★ 首次创建时命令行给的 creative 必须落盘。实际内容：\n" + json);
    }

    /** 默认（不给任何配置）时必须写 survival —— 缺省即生存，磁盘上是可查的。 */
    @Test
    void aFreshSaveWithNoConfigurationWritesSurvival(@TempDir Path root) throws IOException {
        SaveManager manager = manager(root, null);
        assertTrue(manager.save(world(), player()).success());

        String json = rawLevelJson(manager);
        assertTrue(json.contains("\"gameMode\": \"survival\""),
                "★ 未配置时必须写 survival（PRD §4.1「缺省即生存」）。实际内容：\n" + json);
    }

    /** 两参数构造器（既有调用点全用它）必须等价于"配置为 null"，而不是崩或写错。 */
    @Test
    void theTwoArgConstructorBehavesLikeNoConfiguration(@TempDir Path root) throws IOException {
        SaveManager manager = new SaveManager(root, WORLD_NAME);
        assertTrue(manager.save(world(), player()).success());
        assertTrue(rawLevelJson(manager).contains("\"gameMode\": \"survival\""),
                "★ 两参数构造器必须落 survival —— 它是既有 30+ 处调用点走的路径，"
                        + "不能因为新增字段而改变行为。");
    }

    // ============================================================ ③ 模式锁定

    /**
     * ★★<b>§4.3「模式一旦创建，永不切换」的核心接线断言。</b>
     *
     * <p>流程刻意做成"两次独立启动"：先用一个模式存盘，
     * <b>再用另一个模式重开程序</b>，看磁盘上的值会不会被改掉。
     *
     * <p>★ <b>为什么不能只测一次</b>：同一进程内连续两次
     * {@code save()} 会被 {@code readLevelMeta()} 的"回读"掩盖 ——
     * 第二次保存时它读到的正是第一次写的值，于是"锁定"看起来自动成立。
     * 只有<b>重开程序</b>（新的 {@code SaveManager} 实例，读同一份磁盘文件）
     * 才真正复现玩家"下次启动"的场景。
     */
    @Test
    void aCreativeSaveSurvivesALaterSurvivalLaunch(@TempDir Path root) throws IOException {
        // 第一次启动：创造模式，存盘
        SaveManager first = manager(root, GameMode.CREATIVE);
        assertTrue(first.save(world(), player()).success());
        assertTrue(rawLevelJson(first).contains("\"gameMode\": \"creative\""));

        // ★ 第二次启动：命令行改口要 survival，存档必须拒绝
        SaveManager second = manager(root, GameMode.SURVIVAL);
        assertEquals(GameMode.CREATIVE, second.effectiveGameMode(),
                "★ 创造存档 + survival 开关 = 仍应是创造（§4.3 不可切换）");
        assertTrue(second.save(world(), player()).success());

        String json = rawLevelJson(second);
        assertTrue(json.contains("\"gameMode\": \"creative\""),
                "★★ 第二次保存<b>不得</b>把 level.json 里的 creative 改写成 survival。"
                        + "这是「模式锁定」的字面含义：它必须表现为<b>磁盘上的值没变</b>，"
                        + "而不只是「某个 getter 返回 creative」。实际内容：\n" + json);
    }

    /** 反方向同样锁：生存存档不能被 -Dskyisland.gameMode=creative 升级。 */
    @Test
    void aSurvivalSaveSurvivesALaterCreativeLaunch(@TempDir Path root) throws IOException {
        SaveManager first = manager(root, GameMode.SURVIVAL);
        assertTrue(first.save(world(), player()).success());
        assertTrue(rawLevelJson(first).contains("\"gameMode\": \"survival\""));

        SaveManager second = manager(root, GameMode.CREATIVE);
        assertEquals(GameMode.SURVIVAL, second.effectiveGameMode(),
                "生存存档不能被命令行升级（§4.3：不可改，不是只不可降级）");
        assertTrue(second.save(world(), player()).success());
        assertTrue(rawLevelJson(second).contains("\"gameMode\": \"survival\""),
                "第二次保存不得把 survival 改成 creative。实际内容：\n" + rawLevelJson(second));
    }

    // ============================================================ ② 旧存档

    /**
     * ★★<b>端到端的旧存档路径。</b>
     *
     * <p>手写一份<b>字面上没有 {@code gameMode} 键</b>的 {@code level.json}（模拟 M2 时代的存档），
     * 然后用 {@link SaveManager#loadInto} 真的读一次。
     *
     * <p>★ <b>为什么必须走 {@code loadInto} 而不是只调 {@code readLevelMeta}</b>：
     * {@code loadInto} 才是玩家实际走的路径，也是记录"旧存档日志"的那段代码所在。
     * 只测 {@code readLevelMeta} 会漏掉"加载失败时模式是什么"这个问题 ——
     * 而那恰恰是最需要答案的时刻（存档半坏不坏的时候）。
     */
    @Test
    void loadingALegacySaveWithoutTheFieldYieldsSurvival(@TempDir Path root) throws IOException {
        SaveManager setup = manager(root, null);
        Files.createDirectories(setup.worldDirectory());
        String legacyJson = """
                {
                  "saveVersion": 2,
                  "worldName": "%s",
                  "worldSeed": 1,
                  "generatorId": "test:flat",
                  "generatorVersion": 1,
                  "createdAtMillis": 1,
                  "savedAtMillis": 1,
                  "productVersion": "m2-era",
                  "modifiedChunkCount": 0
                }
                """.formatted(WORLD_NAME);
        Files.writeString(
                setup.worldDirectory().resolve(SaveFormat.LEVEL_FILE),
                legacyJson, StandardCharsets.UTF_8);
        assertFalse(legacyJson.contains("gameMode"),
                "★ 夹具前提：这份 JSON 必须真的没有 gameMode 键。");

        SaveManager manager = manager(root, null);
        World w = world();
        Player p = player();
        SaveResult result = manager.loadInto(w, p);

        assertTrue(result.success(), "旧存档必须能正常读入：" + result.summary());
        assertEquals(GameMode.SURVIVAL, manager.effectiveGameMode(),
                "★ PRD §4.2【必须】：旧存档缺 gameMode 字段时按 survival 处理。");
    }

    /**
     * ★ <b>旧存档 + creative 开关 = 创造</b>（§4.1「新建世界选创造」必须能落盘）。
     *
     * <p>★ <b>这是本类最容易被漏掉的一条</b>：它与上一条
     * {@link #aCreativeSaveSurvivesALaterSurvivalLaunch} 看起来方向相反
     * （一个"开关不生效"、一个"开关生效"），但它们其实问的是<b>同一个字段存不存在</b>：
     * <ul>
     *   <li>字段<b>不存在</b> → 开关生效（存档还没定）</li>
     *   <li>字段<b>存在</b> → 开关不生效（存档已定，§4.3）</li>
     * </ul>
     * 只测前者会以为"命令行根本没用"，只测后者会以为"命令行永远没用"。
     * <b>两条必须同时存在，否则其中一半的实现会被当成全部。</b>
     */
    @Test
    void aLegacySaveStillHonorsTheCommandLineBecauseItHasNoModeYet(@TempDir Path root)
            throws IOException {
        writeLegacyLevelJson(root);

        SaveManager manager = manager(root, GameMode.CREATIVE);
        assertEquals(GameMode.CREATIVE, manager.effectiveGameMode(),
                "★ 旧存档里没有该字段 = 尚未定死，命令行必须能决定模式。");
        assertTrue(manager.save(world(), player()).success());
        assertTrue(rawLevelJson(manager).contains("\"gameMode\": \"creative\""),
                "★ 定下来的模式必须被写回磁盘，否则下次启动又会当成旧存档。"
                        + "实际内容：\n" + rawLevelJson(manager));
    }

    // ============================================================ 健壮性

    /**
     * ★★<b>本类曾经的真实缺陷，现场钉死。</b>
     *
     * <p>{@code effectiveGameMode()} 最初写成
     * {@code resolve(parse(existing.recordedGameMode()), configured)}，
     * 而 {@code parse(null) == SURVIVAL} —— 于是"缺字段"被提前翻译成"已定为生存"，
     * <b>{@code -Dskyisland.gameMode=creative} 对每一个旧存档永久失效</b>。
     *
     * <p>★ <b>为什么它能活过"1236 条全绿"</b>：那一版的
     * {@code LevelMeta#gameMode} 默认值是 {@code "survival"}，
     * 所以 {@code recordedGameMode()} 对旧存档返回 {@code "survival"} 而非 {@code null}；
     * 两处缺陷<b>互相抵消</b>，只在"默认值改成 null 之后"才暴露成一条红。
     * <p>★ <b>为什么"只测新存档"永远测不到</b>：新存档走"字段存在"分支，
     * 两条路径答案完全相同。
     *
     * <p>本条直接调生产方法，因此它覆盖纯函数测试覆盖不到的那一层接线。
     */
    @Test
    void effectiveGameModeDistinguishesAbsentFromExplicitSurvival(@TempDir Path root)
            throws IOException {
        writeLegacyLevelJson(root);

        // 缺字段 + creative 开关 → 创造（尚未定死，配置说话）
        assertEquals(GameMode.CREATIVE, manager(root, GameMode.CREATIVE).effectiveGameMode(),
                "★ 旧存档（无该字段）+ creative 开关 = 创造。");

        // 对照：显式写了 survival 的存档 + creative 开关 → 生存（已定死，配置无效）
        SaveManager setup = manager(root, null);
        Files.writeString(
                setup.worldDirectory().resolve(SaveFormat.LEVEL_FILE),
                """
                {
                  "saveVersion": 2,
                  "worldName": "%s",
                  "worldSeed": 1,
                  "generatorId": "test:flat",
                  "generatorVersion": 1,
                  "createdAtMillis": 1,
                  "savedAtMillis": 1,
                  "productVersion": "explicit",
                  "modifiedChunkCount": 0,
                  "gameMode": "survival"
                }
                """.formatted(WORLD_NAME), StandardCharsets.UTF_8);

        assertEquals(GameMode.SURVIVAL, manager(root, GameMode.CREATIVE).effectiveGameMode(),
                "★ 对照：显式写了 survival 的存档 + creative 开关 = 生存（§4.3 已定死）。"
                        + "上一条与本条的差别<b>只有 JSON 里那个键</b>，"
                        + "因此这两条一起才能证明「区分」真的存在。");
    }

    /** 手写一份字面上没有 {@code gameMode} 键的旧存档（各条旧存档测试共用）。 */
    private static void writeLegacyLevelJson(Path root) throws IOException {
        SaveManager setup = manager(root, null);
        Files.createDirectories(setup.worldDirectory());
        Files.writeString(
                setup.worldDirectory().resolve(SaveFormat.LEVEL_FILE),
                """
                {
                  "saveVersion": 2,
                  "worldName": "%s",
                  "worldSeed": 1,
                  "generatorId": "test:flat",
                  "generatorVersion": 1,
                  "createdAtMillis": 1,
                  "savedAtMillis": 1,
                  "productVersion": "m2-era",
                  "modifiedChunkCount": 0
                }
                """.formatted(WORLD_NAME), StandardCharsets.UTF_8);
    }

    /**
     * ★<b>手改成错字的存档必须仍能读出来（§N.6 降级而非崩溃 + §4.2 落生存）。</b>
     *
     * <p>★ <b>本条的真正价值在于它验证了一个"反直觉的设计"是真的</b>：
     * {@code gameMode} 落盘类型是 {@code String} 而不是 {@code GameMode} 枚举，
     * <p>就是为了让这一条成立。若有人"顺手优化"成枚举，本条会红，
     * <p>而错误信息会直接指向原因（见 {@link LevelMetaGameModeTest#theFieldIsAStringNotAnEnum}）。
     */
    @Test
    void aMisspelledModeOnDiskStillLoadsAsSurvival(@TempDir Path root) throws IOException {
        SaveManager setup = manager(root, null);
        Files.createDirectories(setup.worldDirectory());
        Files.writeString(
                setup.worldDirectory().resolve(SaveFormat.LEVEL_FILE),
                """
                {
                  "saveVersion": 2,
                  "worldName": "%s",
                  "worldSeed": 1,
                  "generatorId": "test:flat",
                  "generatorVersion": 1,
                  "createdAtMillis": 1,
                  "savedAtMillis": 1,
                  "productVersion": "hand-edited",
                  "modifiedChunkCount": 0,
                  "gameMode": "survivall"
                }
                """.formatted(WORLD_NAME), StandardCharsets.UTF_8);

        SaveManager manager = manager(root, null);
        World w = world();
        Player p = player();
        SaveResult result = manager.loadInto(w, p);

        assertTrue(result.success(),
                "★ 手改成 \"survivall\" 的存档必须仍能加载 —— "
                        + "§N.6 要求「存档损坏时降级而非崩溃」，"
                        + "一个模式的错别字不该让整个存档打不开。");
        assertEquals(GameMode.SURVIVAL, manager.effectiveGameMode(),
                "错字必须落生存（§4.2 写错值一律留在生存）。");
    }

    /** 存档里的错字值在下次保存时被纠正回合法字面量（而不是把错字一直传下去）。 */
    @Test
    void aMisspelledModeOnDiskIsRewrittenToALegalValueOnSave(@TempDir Path root)
            throws IOException {
        SaveManager setup = manager(root, null);
        Files.createDirectories(setup.worldDirectory());
        Files.writeString(
                setup.worldDirectory().resolve(SaveFormat.LEVEL_FILE),
                """
                {
                  "saveVersion": 2,
                  "worldName": "%s",
                  "worldSeed": 1,
                  "generatorId": "test:flat",
                  "generatorVersion": 1,
                  "createdAtMillis": 1,
                  "savedAtMillis": 1,
                  "productVersion": "hand-edited",
                  "modifiedChunkCount": 0,
                  "gameMode": "survivall"
                }
                """.formatted(WORLD_NAME), StandardCharsets.UTF_8);

        SaveManager manager = manager(root, null);
        assertTrue(manager.save(world(), player()).success());
        String json = rawLevelJson(manager);
        assertTrue(json.contains("\"gameMode\": \"survival\""),
                "★ 错字值在保存时应被规范化回 survival（否则每次读档都要重新判一次错）。"
                        + "实际内容：\n" + json);
        assertFalse(json.contains("survivall"),
                "错字不得被原样写回。实际内容：\n" + json);
    }
}

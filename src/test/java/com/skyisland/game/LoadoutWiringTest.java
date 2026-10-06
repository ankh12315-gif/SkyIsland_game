package com.skyisland.game;

import com.skyisland.testutil.SourceScan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开局装备口径开关（{@code -Dskyisland.loadout}）的<b>接线守门人</b>。
 *
 * <h2>它守的是什么：一条"测试夹具被当成产品内容"的通道</h2>
 * 2026-10-02 为了让步枪链可被验证，开局装备里被加上了<b>步枪 + 步枪弹 + 一整套原始材料</b>。
 * 那本来是一次有理由的临时偏离，但它挂在唯一一条开局装备路径上，于是
 * "正式 M3 Survival 的新游戏"与"自测 / 试玩"共用了同一份发放表 ——
 * 玩家开一局正式新游戏，背包里就躺着一份只有为了让断言能跑才存在的材料包。
 *
 * <p>{@link Loadout} 把两者拆开了，而本类守的是<b>拆开之后那条线真的接上了</b>：
 * 与 {@link InfiniteReserveWiringTest} 完全同一套三层结构（解析规则 → 属性到配置 →
 * 配置到装配期 → 启动器），因为断开了的表现也完全同构 ——
 * <b>全部单测照旧全绿，只有玩家 / 门禁看到与预期不符的背包</b>。
 *
 * <h2>为什么门禁与启动器也要被扫</h2>
 * 三个门禁都依赖 DEV 口径：
 * <ul>
 *   <li>m2 的 {@code GEAR_CHECK} 断言快捷栏第 4 格是步枪、第 5 格是步枪弹；</li>
 *   <li>ui 的合成阶段要用过渡材料包把 R01 / R03 / R06 真的合一遍；</li>
 *   <li>m1 沿用 DEV 以免唯一一条发放路径的行为在无人注意时变化。</li>
 * </ul>
 * 而 {@code play-m3.bat} 是真人试玩的唯一入口，也必须显式带 {@code dev}。
 * 这些文件各自被脚本生成 / 手工编辑，漏掉那一段不会让任何行为测试变红 ——
 * 只会让门禁在某天突然红在"步枪不见了"上。因此它们也是这条接线的一部分。
 */
class LoadoutWiringTest {

    /** 玩家可见开关的属性名。改它等于改对外契约，所以单独写出来。 */
    private static final String KEY = "skyisland.loadout";

    /** 指向一个不存在的存档目录，免得 {@code resolveSaveRoot()} 去读 {@code %APPDATA%}。 */
    private static final String SAVE_DIR_KEY = "skyisland.saveDir";

    private static final Path GAME_SOURCE =
            Path.of("src", "main", "java", "com", "skyisland", "game", "SkyIslandGame.java");

    private static final Path LAUNCHER = Path.of("play-m3.bat");
    private static final Path GATE_PS = Path.of("tmp", "run_gate_ps.ps1");
    private static final Path GATE_JS = Path.of("tmp", "run_frozen_gate.js");

    @AfterEach
    void clear() {
        System.clearProperty(KEY);
        System.clearProperty(SAVE_DIR_KEY);
    }

    /** 按给定原始值构造一份真实配置（走产品唯一的构造点 {@code fromSystemProperties()}）。 */
    private static SkyIslandGame.M1Config configWith(String rawValue) {
        System.setProperty(SAVE_DIR_KEY, "tmp/__loadout_wiring_test");
        if (rawValue == null) {
            System.clearProperty(KEY);
        } else {
            System.setProperty(KEY, rawValue);
        }
        return SkyIslandGame.M1Config.fromSystemProperties();
    }

    // ============================================================ ① 解析规则

    /**
     * <b>只有显式写 {@code dev} 才切 DEV 口径</b>，其余一切（含错字）留在 SURVIVAL。
     *
     * <p>为什么安全侧是"留在正式口径"：DEV 口径发的是<b>步枪与一整套材料</b>，
     * 它们本不该出现在每一局正式新游戏里。若把规则放宽成"非 survival 即 dev"，
     * 一次手滑（{@code =1}、{@code =yes}、{@code =de v}）就会让
     * "开一局正式存档"变成"开局一把步枪 + 全套材料"，
     * 而玩法规则不该由一个错字决定 —— 这正是 v2 §19-15「未偷跑」要防的那类污染。
     */
    @Test
    void onlyAnExplicitDevSwitchesToDev() {
        for (String yes : List.of("dev", "DEV", "Dev", "  dev  ", "\tdev\n")) {
            assertEquals(Loadout.DEV, Loadout.parse(yes),
                    "「" + yes + "」应当被解析为 DEV（忽略大小写、允许首尾空白）");
        }

        for (String no : List.of("survival", "false", "1", "0", "yes", "on",
                "dev1", "de v", "devv", "", "   ", "test")) {
            assertEquals(Loadout.SURVIVAL, Loadout.parse(no),
                    "「" + no + "」必须留在正式口径 SURVIVAL（错字不得改变开局内容）");
        }

        assertEquals(Loadout.SURVIVAL, Loadout.parse(null),
                "属性没给（null）必须留在正式口径 —— 双击启动器 / 正式运行的默认情形");
    }

    // ============================================================ ② 属性 → 配置

    @Test
    void thePropertyReachesTheConfigAndNothingElseDoes() {
        assertEquals(Loadout.SURVIVAL, configWith(null).loadout(),
                "属性缺失 → 正式口径（这是产品默认，v2 的 M3 范围 = 两把枪）");
        assertEquals(Loadout.DEV, configWith("dev").loadout(),
                "★ 开关必须真的把配置翻成 DEV —— 否则整条接线是空的");

        assertEquals(Loadout.SURVIVAL, configWith("survival").loadout());
        assertEquals(Loadout.SURVIVAL, configWith("1").loadout(), "手滑 =1 必须留在正式侧");
        assertEquals(Loadout.SURVIVAL, configWith("yes").loadout());
        assertEquals(Loadout.SURVIVAL, configWith("devv").loadout());
    }

    // ============================================================ ③ 两种口径的内容差异

    /**
     * ★ 正式 Survival 口径<b>不发步枪、不发材料包</b>；DEV 口径两者都发。
     *
     * <p>这一格是"裁定第 4 条"（两套装备分开）在代码层的唯一表达。
     * 写成枚举上的两个谓词而不是散落在 {@code grantStartingGear} 里的 if，
     * 是为了让"哪一套发什么"有<b>一处定义</b> —— 否则界面、F6 补给、
     * 新的发放路径各写一份判断，很快就对不上。
     */
    @Test
    void survivalGrantsNeitherTheRifleNorTheMaterialKit() {
        assertFalse(Loadout.SURVIVAL.grantsRifle(),
                "正式 Survival 口径不得发步枪（M3 的正式范围是 v2 的两把枪）");
        assertFalse(Loadout.SURVIVAL.grantsMaterialKit(),
                "正式 Survival 口径不得发 DEV / TRANSITION MATERIAL KIT");
        assertTrue(Loadout.DEV.grantsRifle(), "DEV 口径要发步枪 —— 门禁与试玩靠它验证步枪链");
        assertTrue(Loadout.DEV.grantsMaterialKit(), "DEV 口径要发过渡材料包 —— 合成链靠它可完成");

        assertEquals(Loadout.SURVIVAL, Loadout.DEFAULT, "产品默认必须是正式口径");
        assertTrue(Loadout.SURVIVAL.describe().contains("SURVIVAL"), "启动日志要能直接读出口径");
    }

    // ============================================================ ④ 配置 → 装配期（源码扫描）

    /**
     * {@code M1Config} 解析出来的口径必须有人在装配期<b>读</b>它，
     * 并且那个读者就是发放开局装备的那一段。
     *
     * <p>两端的断开都不会让行为测试变红：属性名拼错 → 永远 SURVIVAL（门禁红在"步枪不见了"）；
     * 发放处不读配置 → 永远发一整套（正式玩法被污染，而没人测这一格）。
     *
     * <p>扫描用 {@link SourceScan#withoutComments} / {@link SourceScan#methodBody}：
     * 与 {@link InfiniteReserveWiringTest} 同一个教训 ——
     * 朴素的 {@code contains} 会被"解释这行代码"的注释满足。
     */
    @Test
    void theGrantPathActuallyConsumesTheParsedLoadout() throws IOException {
        String raw = Files.readString(GAME_SOURCE, StandardCharsets.UTF_8);
        assertTrue(raw.length() > 100_000,
                "SkyIslandGame.java 只有 " + raw.length() + " 字符，不像是真源码（路径写错？）");

        // 属性名不是"源码里出现过这串字符"，而是"开关读的就是这个常量"。
        // 写成两段是因为这两件事会各自独立地坏：常量值被改（对外契约变了，玩家传的参数失效）
        // 与装配期改成就地写字面量（契约与实现脱钩，改一处漏一处）。
        assertEquals(KEY, Loadout.SYSTEM_PROPERTY,
                "对外契约：开关名就是 " + KEY + "（改它等于改玩家要传的参数）");
        String source = SourceScan.withoutComments(raw);
        assertTrue(source.contains("Loadout.SYSTEM_PROPERTY"),
                "装配期必须读 Loadout.SYSTEM_PROPERTY 这个常量，而不是就地写字面量 —— "
                        + "就地写会让『合约』与『实现』各有一份，改一处漏一处");
        assertTrue(source.contains("parseLoadout(System.getProperty(Loadout.SYSTEM_PROPERTY))"),
                "属性必须经由 M1Config.parseLoadout 解析（唯一一处解析规则）");

        String grant = SourceScan.methodBody(raw, "private void grantStartingGear(");
        assertTrue(grant.contains("config.loadout()"),
                "★ 发放开局装备的那一段必须真的读 config.loadout() —— "
                        + "「配置解析出来了但没人读」会让正式新游戏照样发一整套测试内容，"
                        + "而没有任何一条行为测试会因此变红");
        assertTrue(grant.contains("grantsRifle()") && grant.contains("grantsMaterialKit()"),
                "步枪与材料包必须各自走口径谓词（不是凭空给，也不是只判其中一个）");
    }

    // ============================================================ ⑤ 启动器与门禁

    /**
     * {@code play-m3.bat} 的<b>启动行</b>必须带 {@code -Dskyisland.loadout=dev}。
     *
     * <p>判据锚定到启动行而不是全文件：与 {@link InfiniteReserveWiringTest} 同一个坑 ——
     * 文件头的 {@code REM} 说明里会明文解释这个参数，全文件 {@code contains}
     * 会被那段说明满足，于是"java 行上真的删掉了开关"也照样全绿。
     */
    @Test
    void thePlayLauncherPassesDev() throws IOException {
        assertTrue(Files.exists(LAUNCHER), "找不到 " + LAUNCHER + "（测试必须在项目根目录下运行）");
        String bat = Files.readString(LAUNCHER, StandardCharsets.ISO_8859_1);

        String javaLine = null;
        for (String line : bat.split("\n", -1)) {
            if (line.contains("java.exe") && line.contains("-jar")) {
                javaLine = line;
                break;
            }
        }
        assertNotNull(javaLine, "play-m3.bat 里找不到启动 java 的那一行（应同时含 java.exe 与 -jar）");
        assertTrue(javaLine.contains("-D" + KEY + "=dev"),
                "★ play-m3.bat 的**启动行**必须带 -D" + KEY + "=dev —— "
                        + "否则真人试玩走的是正式口径，看不到步枪也拿不到材料包，"
                        + "而本轮要验证的正是这两样。实测启动行：<" + javaLine.trim() + ">");
    }

    /**
     * 三个门禁都必须显式跑 DEV 口径（m2 的 GEAR_CHECK 与 ui 的合成阶段都依赖它）。
     *
     * <p>两个运行器都扫：{@code run_gate_ps.ps1} 是本机唯一可用通道（node 的
     * {@code spawnSync} 会间歇性 EBUSY），{@code run_frozen_gate.js} 是它的 node 孪生版。
     * 只扫其中一个的话，另一个会悄悄退化成正式口径，然后在某天红在"步枪不见了"。
     */
    @Test
    void bothGateRunnersPassDev() throws IOException {
        String ps = Files.readString(GATE_PS, StandardCharsets.UTF_8);
        int psHits = ps.split("-D" + KEY + "=dev", -1).length - 1;
        assertEquals(3, psHits,
                "run_gate_ps.ps1 应当给三个门禁（m1 / ui / m2）各传一次 -D" + KEY + "=dev，实际 " + psHits + " 次");

        String js = Files.readString(GATE_JS, StandardCharsets.UTF_8);
        assertTrue(js.contains("'-D" + KEY + "=dev'"),
                "run_frozen_gate.js 必须给门禁传 -D" + KEY + "=dev");
    }
}

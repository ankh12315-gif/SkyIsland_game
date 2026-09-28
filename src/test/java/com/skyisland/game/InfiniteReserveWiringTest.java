package com.skyisland.game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 Story 10：后备弹药开关（{@code -Dskyisland.infiniteReserve}）的<b>接线守门人</b>。
 *
 * <h2>它守的是什么：一个"只差最后一公里"的能力</h2>
 * 有限 / 无限两种后备口径在 Story 8 就已经完整存在：
 * <ul>
 *   <li>{@code GunState.ReserveMode}（{@code SURVIVAL} 有限 / {@code PROTOTYPE} 无限）；</li>
 *   <li>{@code CombatController.setReserveMode} 能换口径并丢弃已建状态；</li>
 *   <li>{@link com.skyisland.combat.SurvivalAmmoTest} 把两条口径的<b>行为差异</b>
 *       （换弹扣不扣背包、{@code NO_RESERVE} 是否可达）逐条钉住。</li>
 * </ul>
 * 但<b>玩家侧一个入口都没有</b>：v2 §19-7「Debug 模式仍可无限备弹」这句话，
 * 在 Story 8 之后只能由战斗自测在代码里显式声明，没有第二个调用点。
 * 于是"无限备弹"是一个<b>存在、有测试、但没人能打开</b>的能力 ——
 * 这正是本项目已经用代价学过多次的那一类缺口（断言全绿 ≠ 无缺口）。
 *
 * <h2>为什么本类不能只写成"调一个 setter 再断言 getter"</h2>
 * 那样的测试证明的是 {@code setter} 本身，而不是<b>开关到 setter 之间那条线</b>。
 * 这条线有三个独立的断开点，每个都只会在运行时表现为"我明明传了参数，弹药还是扣"：
 * <ol>
 *   <li>属性名拼错（{@code infiniteReserve} 与 {@code infiniteAmmo} 之类）；
 *   <li>解析规则被就地写成别的形式（例如 {@code Boolean.getBoolean}，
 *       它对 {@code " true "} 这类带空白的值返回 {@code false}）；
 *   <li>{@code M1Config} 解析出来了，但装配期<b>没人读它</b> ——
 *       参数进了配置对象、然后就停在那里。</li>
 * </ol>
 * ①② 由 {@code M1Config} 层面的行为断言覆盖；③ 需要窗口（GL）才能在运行时证伪，
 * 无头门禁里跑不起来，因此改用<b>源码扫描</b> —— 与
 * {@link com.skyisland.audio.UiAudioWiringTest} 同一口径，并且同样只断言"呼唤点存在"，
 * 不去声称"运行起来一定生效"（那由 {@code tmp/verify_m3_play.ps1} 的真实启动日志举证）。
 *
 * <h2>为什么连 {@code play-m3.bat} 一起扫</h2>
 * 这个开关存在的<b>唯一理由</b>就是让启动器默认无限（主理人裁定：
 * 产品默认仍是有限的 Survival 口径，v2 §19-6 不动；无限只作为 Debug 入口）。
 * 启动器是用 {@code tmp/gen_play_m3_bat.js} 生成的，重新生成一次就可能把那一段漏掉 ——
 * 而漏掉之后不会有任何东西变红：配置层测试全绿、口径测试全绿，只有玩家发现
 * "怎么还是有限"。所以启动器本身也是这条接线的一部分。
 */
class InfiniteReserveWiringTest {

    /** 玩家可见开关的属性名。改这个名字等于改对外契约，因此本类把它单独写出来。 */
    private static final String KEY = "skyisland.infiniteReserve";

    /** 指向一个不存在的存档目录，免得 {@code resolveSaveRoot()} 去读 {@code %APPDATA%}。 */
    private static final String SAVE_DIR_KEY = "skyisland.saveDir";

    private static final Path GAME_SOURCE =
            Path.of("src", "main", "java", "com", "skyisland", "game", "SkyIslandGame.java");

    private static final Path LAUNCHER = Path.of("play-m3.bat");

    @AfterEach
    void clear() {
        System.clearProperty(KEY);
        System.clearProperty(SAVE_DIR_KEY);
    }

    /** 按给定原始值构造一份真实配置（走产品唯一的构造点 {@code fromSystemProperties()}）。
     *
     * @param rawValue {@code null} 表示"属性根本没给"（双击启动器的默认情形）
     */
    private static SkyIslandGame.M1Config configWith(String rawValue) {
        System.setProperty(SAVE_DIR_KEY, "tmp/__infinite_reserve_wiring_test");
        if (rawValue == null) {
            System.clearProperty(KEY);
        } else {
            System.setProperty(KEY, rawValue);
        }
        return SkyIslandGame.M1Config.fromSystemProperties();
    }

    /**
     * 把 Java 源码里的<b>整行注释</b>去掉，只留可执行文本。
     *
     * <p><b>这个helper是反向验证逼出来的，不是预防性的洁癖。</b>本类第一版直接用
     * {@code source.contains("combat.setReserveMode(...)")}，反向验证把装配期那一行
     * <b>注释掉</b>并换成 {@code of(false)} —— 结果测试<b>照旧全绿</b>：
     * 因为被注释掉的那一行里，原样的调用文本还在。也就是说那条断言
     * <b>把注释也算成了接线</b>，"有人把这一行注释掉"这种最可能的失效方式恰好从它的缝里漏过去。
     * 一条被注释满足的接线断言，等于没有断言，而且它会带着"已反向验证通过"的说法活着。
     */
    private static String withoutLineComments(String source) {
        StringBuilder kept = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue;   // 整行注释（含 javadoc 体、块注释内部行）一律不算代码
            }
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    // ============================================================ ① 解析规则

    /**
     * 解析规则：<b>只有显式写 {@code true} 才切无限</b>，其余一切（含错字）留在有限侧。
     *
     * <p>为什么"错字留在有限"而不是"错字也当无限"：弹药到底扣不扣是玩家
     * <b>立刻能感觉到</b>的东西，它不该由一个错字决定；而两种错法的代价并不对称 ——
     * 手滑写成 {@code =1} 却仍然有限，玩家会看到弹药在扣，行为与默认一致、无需排查；
     * 若反过来把 {@code =false} 之外的任何值都当无限，一次手滑就会让"打完就没了"
     * 这个玩法规则静默消失，而它在日志里只表现为"弹药怎么不扣"。
     *
     * <p>这条断言同时把"解析规则只有一处来源"这件事写下来：
     * 若有人把它换成 {@code Boolean.getBoolean}（它不 trim），
     * 带空白的 {@code "  true  "} 这一格就会翻面。
     */
    @Test
    void onlyAnExplicitTrueSwitchesToInfinite() {
        for (String yes : List.of("true", "TRUE", "True", "  true  ", "\ttrue\n")) {
            assertTrue(SkyIslandGame.M1Config.parseInfiniteReserve(yes),
                    "「" + yes + "」应当被解析为无限（忽略大小写、允许首尾空白）");
        }

        List<String> stayFinite = new ArrayList<>(List.of(
                "false", "FALSE", "1", "0", "yes", "on", "ture", "treu", "", "   ", "truee"));
        for (String no : stayFinite) {
            assertFalse(SkyIslandGame.M1Config.parseInfiniteReserve(no),
                    "「" + no + "」必须留在有限侧（错字不得改变玩法口径）");
        }

        assertFalse(SkyIslandGame.M1Config.parseInfiniteReserve(null),
                "属性没给（null）必须留在正式口径 —— 双击启动器的默认情形就是这一格");
    }

    // ============================================================ ② 属性 → 配置

    /**
     * 属性真的会到达配置对象，且<b>只有那一种写法会改变它</b>。
     *
     * <p>本用例的每一格都是产品真实会遇到的输入：双击启动器（属性缺失）、
     * 手动加 {@code =true}、以及几种手滑。断言写成"逐格期望值"而不是"两个端点"，
     * 是为了让"有人把解析改成 startsWith("t")"这类放宽立刻暴露在中间那几格上。
     */
    @Test
    void thePropertyReachesTheConfigAndNothingElseDoes() {
        assertFalse(configWith(null).infiniteReserve(),
                "属性缺失 → 正式口径（有限），这是 v2 §19-6 的产品默认");
        assertTrue(configWith("true").infiniteReserve(),
                "★ 开关必须真的把配置翻成无限 —— 否则整条接线是空的");

        assertFalse(configWith("false").infiniteReserve());
        assertFalse(configWith("1").infiniteReserve(), "手滑 =1 必须留在有限侧（安全侧）");
        assertFalse(configWith("yes").infiniteReserve());
        assertFalse(configWith("ture").infiniteReserve());
    }

    /**
     * 解析结果经 {@code ReserveMode.of} 之后落在正确的枚举上（口径的唯一入口）。
     *
     * <p>这一格把"配置里的布尔"与"控制器读的枚举"显式连起来：两者之间隔着一个
     * {@code ReserveMode.of}，而枚举本身的语义由
     * {@link com.skyisland.combat.SurvivalAmmoTest} 逐条守。
     */
    @Test
    void theParsedFlagMapsOntoTheRightReserveMode() {
        assertEquals(com.skyisland.combat.GunState.ReserveMode.PROTOTYPE,
                com.skyisland.combat.GunState.ReserveMode.of(configWith("true").infiniteReserve()),
                "开关打开时必须映射到 PROTOTYPE —— 这一格是 §19-7 在配置层的入口");
        assertEquals(com.skyisland.combat.GunState.ReserveMode.SURVIVAL,
                com.skyisland.combat.GunState.ReserveMode.of(configWith(null).infiniteReserve()),
                "开关关闭时必须映射到 SURVIVAL（正式玩法）");
    }

    // ============================================================ ③ 配置 → 装配期（源码扫描）

    /**
     * {@code M1Config} 解析出来的口径必须有人在装配期<b>读</b>它。
     *
     * <p>抓的是最朴素也最常见的缺口：属性解析好了、配置字段也有了，
     * 但装配期那一行不存在（或被人重构掉）。那种状态下本类前三个用例全绿、
     * 全部单测全绿，而玩家拿到的仍是有限口径。
     *
     * <p>断言分三层，缺一层就会留下假绿：
     * <ol>
     *   <li>属性名一致（{@code "skyisland.infiniteReserve"} 字面量在源码里）；</li>
     *   <li>解析走 {@code parseInfiniteReserve} 这唯一一处（不是就地 {@code Boolean.getBoolean}）；</li>
     *   <li>装配期把 {@code config.infiniteReserve()} 交给 {@code combat.setReserveMode}。</li>
     * </ol>
     *
     * <p>源码扫描的固有代价是"它只证明字符串出现"。这里的补偿有三层：
     * 被扫描的那一行是<b>单行表达式</b>（改坏会先编译失败）；扫描前<b>去掉整行注释</b>
     * （见 {@link #withoutLineComments}）；以及本类通过反向验证确认过"把这一行注释掉会红"
     * （第一版没有这一层，反向验证时确实是绿的 —— 见该 helper 的注释）。
     */
    @Test
    void theAssemblyLineActuallyConsumesTheParsedFlag() throws IOException {
        String raw = Files.readString(GAME_SOURCE, StandardCharsets.UTF_8);

        // 反向验证用的自检：路径写错时 readString 会抛异常，但"读到空壳"不会 ——
        // 那会让下面所有 contains 恒假、"接线缺失"恒真，变成一个永远红又找不到原因的测试。
        assertTrue(raw.length() > 100_000,
                "SkyIslandGame.java 只有 " + raw.length() + " 字符，不像是真源码（路径写错？）");

        String source = withoutLineComments(raw);
        assertTrue(source.length() > raw.length() * 0.5,
                "去掉注释后只剩 " + source.length() + " / " + raw.length()
                        + " 字符 —— 去注释逻辑本身可能写坏了（那样下面的断言会变成另一种假绿）");

        assertTrue(source.contains("\"" + KEY + "\""),
                "源码里必须出现属性名 \"" + KEY + "\" —— 名字对不上，玩家传的参数就永远不会被读");

        assertTrue(source.contains("parseInfiniteReserve(System.getProperty(\"" + KEY + "\"))"),
                "属性必须经由 M1Config.parseInfiniteReserve 解析（唯一一处解析规则）；"
                        + "就地写 Boolean.getBoolean 会让本类第 ① 节的空白用例失去守卫");

        assertTrue(source.contains("combat.setReserveMode(GunState.ReserveMode.of(config.infiniteReserve()))"),
                "★ 装配期必须把 config.infiniteReserve() 交给 combat.setReserveMode —— "
                        + "「配置解析出来了但没人读」正是这条接线唯一的断开方式，"
                        + "而它不会让任何一条行为测试变红。"
                        + "（注意：这里读的是**去掉整行注释**后的文本，注释掉这一行会被判为缺失）");
    }

    // ============================================================ ④ 启动器（源码扫描）

    /**
     * 启动器必须真的把开关传给 JVM —— 主理人裁定的"play-m3 默认无限"的落地形式。
     *
     * <p>启动器由 {@code tmp/gen_play_m3_bat.js} 生成，重生成时最容易漏的就是这一行；
     * 漏掉之后配置层与口径层的测试<b>全部照旧全绿</b>，只有玩家发现"弹药怎么还在扣"。
     * 因此这里把启动器也纳入守卫范围。
     *
     * <p>顺带守一条本项目踩过的坑：{@code .bat} 必须<b>纯 ASCII</b>。
     * 中文 Windows 的 {@code cmd.exe} 按 GBK 读批处理，UTF-8 中文会变乱码，
     * 更糟的是多字节字符可能把后续命令行（例如 {@code -jar} 那一段）解析崩。
     * 所以这里用 ISO-8859-1 逐字节读回来，再断言没有一个字符超过 127 ——
     * 有人在编辑器里"顺手写个中文注释并另存 UTF-8"会在这一条上变红。
     *
     * <p><b>为什么必须锚定到"启动 java 的那一行"而不是全文件 {@code contains}：</b>
     * 同样是反向验证发现的 —— 启动器文件头为了让玩家知道怎么切回正式口径，
     * <b>明文解释了</b> {@code -Dskyisland.infiniteReserve=true} 这个参数（写在 {@code REM} 里）。
     * 于是"全文件 contains"会被这段<b>说明文字</b>满足：把 java 行上的开关真的删掉，
     * 断言仍然全绿。判据必须落在<b>真正的启动行</b>上。
     */
    @Test
    void thePlayLauncherPassesTheSwitchAndStaysPureAscii() throws IOException {
        assertTrue(Files.exists(LAUNCHER),
                "找不到 " + LAUNCHER + "（测试必须在项目根目录下运行）；"
                        + "这个文件是 M3 唯一的双击启动入口，不能缺失");

        // ISO-8859-1 是 1 字节 → 1 字符的映射，读回来不会替换、不会抛异常，
        // 因此可以用它精确地判断"文件里到底有没有非 ASCII 字节"。
        String bat = Files.readString(LAUNCHER, StandardCharsets.ISO_8859_1);

        // 真正的启动行：同时含解释器与 -jar 的那一行（REM 说明行不含 java.exe，因此不会被选中）
        String javaLine = null;
        for (String line : bat.split("\n", -1)) {
            if (line.contains("java.exe") && line.contains("-jar")) {
                javaLine = line;
                break;
            }
        }
        assertNotNull(javaLine,
                "play-m3.bat 里找不到启动 java 的那一行（应同时含 java.exe 与 -jar）—— "
                        + "启动器结构变了，本断言需要跟着改，而不是删掉");
        assertTrue(javaLine.contains("-D" + KEY + "=true"),
                "★ play-m3.bat 的**启动行**必须带 -D" + KEY + "=true —— "
                        + "否则「默认无限」只是一句注释（文件头里确实写了这个参数名，"
                        + "所以判据不能是全文件 contains）。实测启动行：<" + javaLine.trim() + ">");

        List<Integer> nonAscii = new ArrayList<>();
        for (int i = 0; i < bat.length(); i++) {
            if (bat.charAt(i) > 127) {
                nonAscii.add(i);
            }
        }
        assertTrue(nonAscii.isEmpty(),
                "play-m3.bat 含非 ASCII 字符（偏移 " + nonAscii
                        + "）：GBK 的 cmd.exe 会读乱，且可能把 -jar 那一段解析崩。"
                        + "该文件由 tmp/gen_play_m3_bat.js 生成，请改生成器而不是直接编辑它");
    }
}

package com.skyisland.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2.2：UI 音效的<b>接线</b>守门人。
 *
 * <h2>它守的是什么</h2>
 * "事件表里有这个音"与"这个音真的会被播出来"是两件事。
 * 本项目已经用代价学过三次同一条教训（<b>断言全绿 ≠ 无缺陷</b>）：
 * <ul>
 *   <li>M2 的"枪在存档里静默消失"是靠一条无人断言的 WARN 抓到的；</li>
 *   <li>M2 的"右键放不下方块"表现为 {@code 放置=0} <b>且</b> {@code 拒绝=0} ——
 *       代码路径齐全，但 95% 的点击被丢在了错误的意图载体上；</li>
 *   <li>M2.2 的"继续游戏 / 新建世界"<b>存在于菜单里</b>，但 {@code activateEntry} 没有分支
 *       —— 点得动、没反应。</li>
 * </ul>
 * 一个"定义了但从未被播放"的音效与上面三例同构：它会让
 * {@link PcmSynthTest} 全绿、让 {@code AudioEvent.values().length} 也对，
 * 而玩家永远听不到。因此这里断言的不是"音效存在"，而是"<b>呼唤点存在</b>"。
 *
 * <h2>为什么用源码扫描而不是行为测试</h2>
 * UI 音的触发点全在 {@code SkyIslandGame} 里，而它需要真实窗口（GL）才能构造。
 * 无头门禁里跑不起来，于是行为测试这条路是堵死的。
 * 源码扫描的代价是"它只证明字符串出现"，所以它必须与
 * {@link #everyWiredUiEventAlsoExistsInTheEventTable()} 配对使用 ——
 * 后者保证被引用的常量<b>真的存在于枚举里</b>，
 * 两条合起来才等价于"这个音会被播出来"。
 */
class UiAudioWiringTest {

    private static final Path GAME_SOURCE =
            Path.of("src", "main", "java", "com", "skyisland", "game", "SkyIslandGame.java");

    /** M2.2 引入的四条 UI 音，以及它们各自的触发场景（写在这里是为了让遗漏可见）。 */
    private static final List<String> UI_EVENTS = List.of(
            "UI_OPEN", "UI_CLOSE", "UI_MOVE", "UI_DENIED");

    @Test
    void everyUiEventHasAPlayCallSite() throws IOException {
        String source = Files.readString(GAME_SOURCE, StandardCharsets.UTF_8);

        List<String> missing = new ArrayList<>();
        for (String name : UI_EVENTS) {
            if (!source.contains("audio.play(AudioEvent." + name + ")")) {
                missing.add(name);
            }
        }

        assertTrue(missing.isEmpty(),
                "这些 UI 音在事件表里有定义、却没有任何播放点（等于永远听不到）：" + missing
                        + " —— 定义与呼唤点必须成对出现");
    }

    /**
     * 被播放的常量必须真的存在于 {@link AudioEvent} 枚举里。
     *
     * <p>源码扫描的假阳性防线：若有人把常量改名却漏改了播放点，
     * 上一条断言会因为字符串对不上而变红 —— 但反过来，
     * 若有人把播放点写成 {@code AudioEvent.SOMETHING_THAT_DOES_NOT_EXIST}，
     * 编译就会先拦住。两条一起才是完整的"接线存在"。
     */
    @Test
    void everyWiredUiEventAlsoExistsInTheEventTable() {
        List<String> actual = new ArrayList<>();
        for (AudioEvent event : AudioEvent.values()) {
            if (event.id().startsWith("ui_")) {
                actual.add(event.name());
            }
        }
        assertEquals(UI_EVENTS, actual,
                "本测试写死的 UI 事件清单必须与 AudioEvent 里 id 以 ui_ 开头的常量完全一致；"
                        + "不一致说明有人加了音却没登记（或反过来）");
    }

    /**
     * UI 音的 baseGain 必须整体低于战斗音。
     *
     * <p>这是产品口径里最容易在"某次微调音色"时被顺手破坏的一条：
     * 把 UI 音调响一点，单听没问题，代价是翻背包时枪声被盖住。
     * {@link PcmSynthTest#uiSoundsAreQuieterThanCombatSounds()} 从波形侧守同一条约束，
     * 这里从事件表侧守 —— 两条独立，任一方单独被改都会红。
     */
    @Test
    void uiEventsKeepTheirGainCeilingBelowCombat() {
        for (AudioEvent event : AudioEvent.values()) {
            if (!event.id().startsWith("ui_")) {
                continue;
            }
            assertTrue(event.baseGain() <= 0.50f,
                    event.id() + " 的基准增益 " + event.baseGain()
                            + " 超过了 UI 音的 0.50 上限（会盖住枪声与受伤音）");
        }
    }

    /**
     * 反向验证用的自检：本类扫描的源文件必须真的有内容。
     *
     * <p>路径写错时 {@code Files.readString} 会抛异常 —— 这条断言防的是更隐蔽的情形：
     * 文件读到了、但内容是空壳（比如被误当成生成物清空），
     * 于是上面所有 {@code contains} 都恒假、"缺播放点"的报警恒真 ——
     * 那会是一个永远红但永远找不到原因的测试。反过来，
     * 若把整类断言改成 {@code assertFalse}，它也会在这里被拦下。
     */
    @Test
    void theScannedSourceIsNotAnEmptyShell() throws IOException {
        String source = Files.readString(GAME_SOURCE, StandardCharsets.UTF_8);
        assertTrue(source.length() > 100_000,
                "SkyIslandGame.java 只有 " + source.length() + " 字符，不像是真源码");
    }

    /**
     * 反向验证的注入标记不得残留在<b>任何</b>主源码文件里。
     *
     * <p>本项目的反向验证做法是"临时把实现改错，确认断言真的会红，然后恢复"。
     * 漏恢复的后果不是红，而是<b>绿</b> —— 而且是在一条已经证明过能红的断言的掩护下绿：
     * 判据本身被改坏了，却拿着"反向验证通过"这句话当结论。
     * 这类残留比一个普通 bug 更危险，因为它污染的是证据链而不是产品。
     *
     * <p><b>为什么扫描整个 src/main/java 而不是只扫 SkyIslandGame：</b>
     * 这条断言最初只扫 {@code SkyIslandGame.java}。M2.2 收尾时的一次反向验证
     * 同时往三个文件（游戏接线、背包渲染器、压暗层）里注入了标记，其中只有第一个
     * 在守卫范围内 —— 另外两个漏恢复的话不会有任何东西变红。
     * 教训很直接：<b>守卫的覆盖面必须跟着实践的范围走</b>，
     * 否则守卫本身就是一个"看起来在守"的假象。
     */
    @Test
    void noReverseVerificationMarkerIsLeftBehindInMainSources() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (var paths = Files.walk(Paths.get("src/main/java"))) {
            for (Path p : paths.filter(x -> x.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (text.contains("TEMP_REVERSE_VERIFY")) {
                    offenders.add(p.toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下主源码里残留了反向验证用的注入标记，说明有一轮反向验证没有恢复："
                        + offenders);
    }
}

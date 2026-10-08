package com.skyisland.game;

import com.skyisland.testutil.SourceScan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★★<b>M4-S6 启动顺序守卫：{@code logStartupBanner} 里不许碰 {@code saveManager}。</b>
 *
 * <h2>★ 本类存在的理由：一次"1267 条单测全绿 + 三档门禁全崩"的事故</h2>
 * 我在 {@code logStartupBanner()} 里打了这一行：
 * <pre>{@code saveManager.effectiveGameMode().displayName()}</pre>
 * 而 {@code logStartupBanner()} 在 {@code start()} 的<b>第 982 行</b>被调用，
 * {@code saveManager} 却要到<b>第 1009 行</b>才被 new 出来 ——
 * <b>早 27 行</b>，此刻它必为 {@code null}。
 *
 * <p>后果：<b>m1 / ui / m2 三档门禁全部 NPE 崩溃</b>（各 1 秒内退出 1），
 * 而 <b>1267 条单测全绿、0 失败 0 错误</b>。
 *
 * <h3>★★ 为什么单测抓不到（这才是本类的重点）</h3>
 * 本项目<b>所有</b> S6 测试都是直接 {@code new SaveManager(...)} 再调
 * {@code effectiveGameMode()} —— 它们<b>从不</b>走 {@code SkyIslandGame.start()}。
 * 于是"启动时哪个字段先就绪"这件事<b>没有任何测试覆盖</b>：
 * <ul>
 *   <li>单测测的是「方法在对象已就绪时行为正确」；</li>
 *   <li>没人测「方法在真实启动序列的第几步被调用」。</li>
 * </ul>
 * ★ 这与项目里已登记的教训同族：
 * <b>「判接线是否存在必须用源码扫描 / 方法体断言」</b> ——
 * 行为断言只能证明"接对了"，证明不了"接的<em>时候</em>对不对"。
 *
 * <h3>★ 判据为什么是"方法体里不许出现 saveManager"而不是别的</h3>
 * <ul>
 *   <li><b>不能用"加 null 判空"当修法</b>：判空会让这一行在旧存档上
 *       <b>静默不打印</b>，而"静默不打印"正是 §4.2【必须】要防的事 ——
 *       <b>守卫会因此失去意义，却仍然全绿</b>。</li>
 *   <li><b>不能用"调用点在 banner 之后"当判据</b>：那只约束了调用顺序，
 *       约束不了 banner 内部读了哪个字段。</li>
 * </ul>
 * 本条直接锚<b>方法体</b>，是这里能取的最强约束。
 */
class GameModeStartupOrderWiringTest {

    private static final String GAME = "com/skyisland/game/SkyIslandGame.java";

    private static String startBody() {
        return SourceScan.methodBody(SourceScan.readMain(GAME), "private void start(");
    }

    private static String bannerBody() {
        return SourceScan.methodBody(SourceScan.readMain(GAME),
                "private void logStartupBanner(");
    }

    // ============================================================ ①事故的直接防线

    /**
     * ★★<b>banner 方法体里不得出现 {@code saveManager}。</b>
     *
     * <p>banner 在 {@code saveManager} 创建之前被调用，因此任何对它的解引用都是 NPE。
     * 本条剥掉注释后再匹配（{@code SourceScan#methodBody} 已做），
     * 所以<b>注释里提到 {@code saveManager} 不会让本条通过</b> ——
     * 这一点很重要，因为事故的复盘注释里就大段提到了它。
     */
    @Test
    void theStartupBannerMustNotDereferenceSaveManager() {
        assertFalse(bannerBody().contains("saveManager"),
                "★★ logStartupBanner() 在 start() 里被调用的位置早于 saveManager 的创建"
                        + "（历史上是 982 行 vs 1009 行），此刻 saveManager 必为 null。"
                        + "\n症状：m1 / ui / m2 三档门禁全部 NPE 崩溃，"
                        + "而单测因为从不走 SkyIslandGame.start() 而全绿。"
                        + "\n正确处置：banner 只打 config.gameMode() 并标注「以存档为准」，"
                        + "生效值由 logEffectiveGameMode() 在 saveManager 就绪后补打。"
                        + "★ 不要在这里加 null 判空 —— 那会让这一行静默不打印。");
    }

    /** 对照：{@code logEffectiveGameMode} 就<b>应该</b>用 saveManager —— 两条一起才有意义。 */
    @Test
    void theEffectiveGameModeLoggerDoesUseSaveManager() {
        String body = SourceScan.methodBody(SourceScan.readMain(GAME),
                "private void logEffectiveGameMode(");
        assertTrue(body.contains("saveManager.effectiveGameMode()"),
                "对照断言：生效值必须在 saveManager 就绪后打印。"
                        + "只有①没有②的话，玩家永远看不到真正生效的模式。");
    }

    // ============================================================ ②启动顺序本身

    /**
     * ★ <b>{@code logStartupBanner()} 的调用必须早于 {@code saveManager = new SaveManager(...)}。</b>
     *
     * <p>这条与①是<b>互补</b>而不是重复：①说"banner 不能用 saveManager"，
     * 这条说"两者之间的相对顺序确实是 banner 在前" ——
     * 万一将来有人把 banner 移到存档加载<b>之后</b>，
     * ①会立刻失去意义（banner 里用 saveManager 就变成合法的了），
     * 而这条会红，把"①的前提"本身钉住。
     *
     * ★ <b>没有对照的顺序断言是最脆的</b>：它看起来在钉"顺序"，
     * 实际上一旦顺序反过来它就自动失效。因此必须显式断言"banner 在前"。
     */
    @Test
    void theBannerIsCalledBeforeSaveManagerIsCreated() {
        String start = startBody();
        int bannerAt = start.indexOf("logStartupBanner()");
        int saveAt = start.indexOf("new SaveManager(");
        assertTrue(bannerAt >= 0, "start() 里应当调用 logStartupBanner()");
        assertTrue(saveAt >= 0, "start() 里应当创建 SaveManager");
        assertTrue(bannerAt < saveAt,
                "★ logStartupBanner() 必须在 new SaveManager(...) 之前调用"
                        + "（现状：banner@" + bannerAt + " < saveManager@" + saveAt + "）。"
                        + "若有人把 banner 移到存档加载之后，本条会红 —— "
                        + "这是有意的：banner 里的「只能读配置」这个前提必须显式成立。");
    }

    /**
     * ★ <b>{@code logEffectiveGameMode()} 必须在 {@code saveManager = new SaveManager(...)} 之后调用。</b>
     *
     * <p>与上一条构成<b>一组对照</b>：banner 在前、生效值在后。
     * 两条一起才能证明"分两次打印"这个设计真的被执行了，
     * 而不是只写了一个方法没人调（死接线）。
     */
    @Test
    void theEffectiveGameModeLoggerIsCalledAfterSaveManagerIsCreated() {
        String start = startBody();
        int saveAt = start.indexOf("new SaveManager(");
        int logAt = start.indexOf("logEffectiveGameMode()");
        assertTrue(saveAt >= 0 && logAt >= 0,
                "start() 里应当既创建 SaveManager 又调用 logEffectiveGameMode()");
        assertTrue(logAt > saveAt,
                "★ logEffectiveGameMode() 必须在 saveManager 创建之后调用"
                        + "（现状：saveManager@" + saveAt + " < log@" + logAt + "）；"
                        + "在它之前调用就会重演 NPE 事故。");
    }

    // ============================================================ ③配置值也要被读

    /**
     * banner 仍然必须打一行游戏模式（§4.2【必须】"启动日志打印一行"）。
     *
     * <p>★ <b>为什么这条与①不矛盾</b>：①禁止的是 banner 里<b>解引用 saveManager</b>，
     * 不是禁止它打模式。修法正是"改打 {@code config.gameMode()}"，
     * 所以本条钉的正是那个修法本身 ——
     * 若有人把整行删掉以回避 NPE，本条会红。
     */
    @Test
    void theBannerStillLogsTheConfiguredGameMode() {
        String banner = bannerBody();
        assertTrue(banner.contains("config.gameMode()"),
                "★ banner 阶段应当打 config.gameMode()（PRD §4.2【必须】："
                        + "启动日志要有一行游戏模式）。"
                        + "★ 不要为了绕开 NPE 把整行删掉 —— 那是把【必须】删掉。");
    }

    // ============================================================ ④主理人可读性

    /**
     * §4.2 要求的字面是「游戏模式 : 生存 / 创造」。
     *
     * <p>★ <b>为什么要钉"游戏模式"这四个字</b>：现在日志分两行打
     * （配置值 / 生效值），而 {@code describeGameModeSource()} 里有大段中文注释。
     * 若有人为了"让日志更整齐"而把行首改成别的措辞，排查时
     * "grep 游戏模式"就会落空 —— 而这行日志的<b>全部价值</b>就是能被 grep 到。
     */
    @Test
    void bothGameModeLogLinesStartWithTheGreppablePrefix() {
        String source = SourceScan.readMain(GAME);
        int count = countOccurrences(SourceScan.withoutComments(source), "\"游戏模式");
        assertTrue(count >= 2,
                "★ 应当有<b>两行</b>以「游戏模式」开头的日志（配置值 + 生效值），"
                        + "实际只有 " + count + " 行。"
                        + "这行日志的全部价值就是能被 grep 到，措辞不能随意改。");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            n++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return n;
    }
}

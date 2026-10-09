package com.skyisland.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ <b>创造会话绝不写进存档</b>的接线守卫（PRD_BLOCK_CREATIVE §4.3 于 2026-10-08 修订）。
 *
 * <h2>为什么这条要单立一个类</h2>
 * {@link CreativeSessionTest} 验的是<b>运行时行为</b>（双击进会话、起飞、能停、能退）。
 * 但那些断言<b>全部可以在存档被写坏的情况下照样全绿</b> ——
 * 因为它们只看内存里的字段，没有一条会去读磁盘。
 *
 * <p>而"不写入存档"恰恰是这次折中的<b>全部安全性来源</b>：
 * 主理人明确选择了"只在本次运行有效"，理由就是 §4.3 的两条不能被破坏。
 * 若某个改动顺手把会话状态接进了 {@code SaveManager}，
 * §4.2「存档必须记录模式」与 §4.3「模式不可改」会同时失效，
 * 而症状是：玩家双击几次空格，退出重进后那个生存世界**变成了创造世界**。
 *
 * <p>⇒ 必须有一类断言去读"写盘路径上有没有这个字段"。
 *
 * <h2>判据一律读<b>去掉注释后的源码</b></h2>
 * 因为解释性注释里必然会提到 {@code gameMode} / {@code activeGameMode} 这些名字，
 * 全文 {@code contains} 会被说明文字满足：真正接上写盘了，断言照样全绿。
 */
class CreativeSessionWiringTest {

    private static final Path GAME =
            Path.of("src", "main", "java", "com", "skyisland", "game", "SkyIslandGame.java");
    private static final Path PLAYER =
            Path.of("src", "main", "java", "com", "skyisland", "player", "Player.java");
    private static final Path SAVE =
            Path.of("src", "main", "java", "com", "skyisland", "save", "SaveManager.java");

    private static String codeWithoutComments(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    @Test
    @DisplayName("写盘路径上没有创造会话：存档的 gameMode 只来自存档本身")
    void theSessionNeverReachesTheSaveFile() throws IOException {
        String save = codeWithoutComments(SAVE);

        // ★ 判据是"写盘的那一行只用 effectiveGameMode()"，而不是"文件里没有
        //   某个新字段名" —— 后者会被"我换个字段名再接上"绕过。
        //   meta.gameMode 必须仍然只由 effectiveGameMode() 提供。
        assertTrue(save.contains("meta.gameMode = effectiveGameMode().persisted()"),
                "★ 存档里的 gameMode 必须仍然只来自 effectiveGameMode()。"
                        + "若改成读玩家/会话状态，生存存档会在玩家开过创造会话后被永久转成创造 —— "
                        + "而那正是 §4.3 要防的「用 UI 绕过模式锁定」");
    }

    @Test
    @DisplayName("游戏层从不因会话去改存档层的模式")
    void theGameLayerNeverRewritesTheSaveMode() throws IOException {
        String game = codeWithoutComments(GAME);

        // 会话只允许做两件事：开/关 Player 上的能力开关 + 重建创造面板。
        // 任何对 saveManager 模式字段的赋值都是越界。
        for (String forbidden : new String[]{
                "setGameMode(",
                "activeGameMode =",
                "setCreativeModeForSave("}) {
            assertFalse(game.contains(forbidden),
                    "★ 游戏层出现 `" + forbidden + "` —— 创造会话不得改存档模式。"
                            + "会话只是本次运行内的能力开关");
        }
    }

    @Test
    @DisplayName("装配期判据：只有生存存档才允许开会话")
    void sessionsAreAllowedOnlyInSurvivalSaves() throws IOException {
        String game = codeWithoutComments(GAME);

        // ★ 判据必须挂在 effectiveGameMode() 上，而不是 config.gameMode()。
        //   后者只对"尚未定死"的世界有效：用它门控会让「创造存档 + survival 开关」
        //   的世界也拿到退出路径，于是生存模式被命令行一次性绕过。
        assertTrue(game.contains("setCreativeSessionAllowed(!creative)"),
                "★ 允许开会话必须与 effectiveGameMode() 的判定取反 —— "
                        + "它是 survival 时才为 true");
        assertFalse(game.contains("setCreativeSessionAllowed(config."),
                "★ 不得用 config.gameMode() 门控：它只对尚未定死的世界有效，"
                        + "用它会让「创造存档 + survival 开关」拿到退出路径");
    }

    @Test
    @DisplayName("会话回调必须真的接上（否则创造面板不会重建）")
    void theSessionListenerIsActuallyWired() throws IOException {
        String game = codeWithoutComments(GAME);
        String player = codeWithoutComments(PLAYER);

        assertTrue(game.contains("setCreativeSessionListener("),
                "★ 必须注册会话回调 —— 否则中途进会话时创造面板不会重建，"
                        + "玩家拿到的是「能飞但背包里没有创造标签」的半套能力，"
                        + "而那五项能力里缺的恰好是唯一能看出自己在创造模式的界面证据");

        assertTrue(player.contains("creativeSessionListener"),
                "Player 侧必须有监听器字段与触发点");
        // 触发点必须落在"会话状态改变"的两条边上，而不是每个逻辑步都触发。
        assertTrue(player.contains("notifyCreativeSession(true)")
                        && player.contains("notifyCreativeSession(false)"),
                "进入与退出两条边都必须触发回调");

        // ★ 判据必须是"触发方法的判据只有监听器非空"这一个精确串。
        //   第一版这里写的是"源码里不存在某种排版"，那是个**从不匹配**的模式 ——
        //   于是无论代码怎么改它都成立。反向验证注入 `&& creativeSession`
        //   时它照样全绿，把这条无效断言当场揪了出来。
        //   那个注入破坏的到底是什么？退出那条边先把 creativeSession 置 false，
        //   再触发 notify —— 判据里加上 && creativeSession 就把**退出回调整个吞掉**，
        //   于是退出时创造面板永不清除（能力没了但标签还在）。
        assertTrue(player.contains("if (creativeSessionListener != null) {"),
                "★ notifyCreativeSession 的判据必须**只有**「监听器非空」。"
                        + "若额外加上 `&& creativeSession`，退出那条边（此时该标志已为 false）"
                        + "就永远不触发 —— 创造面板清不掉，"
                        + "症状是「能力没了但创造标签还在，点了格子什么都拿不到」");
    }

    @Test
    @DisplayName("★ 创造面板必须跟着会话重建（tabs 由 creativeView 决定）")
    void theCreativePaletteFollowsTheSession() throws IOException {
        String game = codeWithoutComments(GAME);

        // 标签数由 creativeView 是否为 null 决定，所以"面板跟着会话走"就等于
        // "会话的两个边都重建/清空 creativeView"。
        assertTrue(game.contains("buildCreativeView()"),
                "必须有统一的建面板方法，且会话进入时调用它");

        // ★ 判据必须把"清空"**绑在会话那条边上**，不能只判"文件里有 creativeView = null"。
        //   第一版写的是后者 —— 而那一串在启动装配的 else 分支里也有一处
        //   （生存存档启动时把面板置 null），于是断言被**无关的那处**满足。
        //   反向验证注入"退出会话不清面板"时它照样全绿，当场把这条无效断言揪了出来。
        //   ⇒ 锚点必须包含只有会话边才有的 `buildCreativeView();`。
        assertTrue(game.contains("buildCreativeView();\n        } else {\n"
                        + "            creativeView = null;"),
                "★ 退出会话的那一条边必须把 creativeView 清成 null —— "
                        + "否则会出现「能力没了但创造标签还在」，"
                        + "点了格子却什么都拿不到，而症状看起来像「创造面板坏了」。"
                        + "（判据绑在会话边：启动装配里也有 creativeView = null，"
                        + "只判全文件会被那一处满足。）");

        // 标签门控本身必须仍然是 creativeView，而不是改成别的判据
        assertTrue(game.contains("creativeView == null ? 1 : 2"),
                "背包标签数必须仍然由 creativeView 是否存在决定 —— "
                        + "改成别的判据会让「会话开着但只有一个标签」或反过来");
    }

    @Test
    @DisplayName("★ 双击空格的实现里不得再有「退出会话」分支")
    void doubleTapNeverExitsTheSession() throws IOException {
        String player = codeWithoutComments(PLAYER);

        // ★ 判据是「applyDoubleTapSpace 的**方法体里**没有结束会话的调用」。
        //   只判「整个文件里没有 endCreativeSession」是不够的 ——
        //   ESC 菜单那条路径必须调它，所以它当然存在于文件里。
        //   这与本文件第 ① 条同源：判据必须锚在正确的位置，
        //   否则"某个东西存在"会被另一处的同名东西满足。
        final int start = player.indexOf("private void applyDoubleTapSpace()");
        assertTrue(start >= 0, "找不到 applyDoubleTapSpace");
        final int end = player.indexOf("public boolean endCreativeSession()", start);
        assertTrue(end > start, "找不到 endCreativeSession 的定义位置");
        final String body = player.substring(start, end);

        assertFalse(body.contains("endCreativeSession()"),
                "★ 双击空格里不得出现退出会话的调用 —— 2026-10-09 主理人实测后已移除"
                        + "那个「第三态」。退出创造会**带走全部五项能力**，"
                        + "而触发它的是一次手感键的连按，且玩家预判不到"
                        + "（飞行状态站着时不可见）。一个键静默拿走全部能力，"
                        + "比它什么都不做更糟");
        assertFalse(body.contains("creativeSession = false"),
                "★ 同上：双击空格里不得把会话标志置回 false");
    }

    @Test
    @DisplayName("★ 结束会话必须有可发现的入口（ESC 暂停菜单）")
    void theSessionExitIsReachableFromThePauseMenu() throws IOException {
        String menus = codeWithoutComments(
                Path.of("src", "main", "java", "com", "skyisland", "ui", "Menus.java"));
        String game = codeWithoutComments(GAME);

        assertTrue(menus.contains("ID_END_CREATIVE_SESSION"),
                "★ 暂停菜单必须有「结束创造模式」这一项 —— "
                        + "把退出藏起来就会让玩家被留在创造里，"
                        + "而 ESC 菜单是他们预期找得到的地方");
        assertTrue(menus.contains("MENU_PAUSE_END_CREATIVE"),
                "该菜单项必须走 Localization（玩家可见文案统一简体中文）");
        assertTrue(game.contains("Menus.ID_END_CREATIVE_SESSION.equals(entryId)"),
                "★ 游戏层必须真的处理这个菜单项 —— 菜单里有一行点了没反应，"
                        + "比没有这一行更糟");
        assertTrue(game.contains("player.endCreativeSession()"),
                "处理时必须真的结束会话");
        assertTrue(game.contains("Menus.pauseMenu(false)"),
                "★ 结束后必须重建暂停菜单 —— 否则那一行会留在屏幕上，"
                        + "第二次点它只会得到「本来就不在会话里」");
    }

    @Test
    @DisplayName("★ 飞行/会话状态必须在 HUD 常驻（不可见 = 不可预测）")
    void flightAndSessionAreShownOnTheHud() throws IOException {
        String hud = codeWithoutComments(
                Path.of("src", "main", "java", "com", "skyisland", "render", "ui", "HudModel.java"));
        String renderer = codeWithoutComments(
                Path.of("src", "main", "java", "com", "skyisland", "render", "ui", "HudRenderer.java"));
        String game = codeWithoutComments(GAME);

        assertTrue(hud.contains("public boolean flying;"),
                "HUD 模型必须有 flying —— 站在地面时飞行状态**看不出来**，"
                        + "而这就是玩家预判不了双击行为的直接原因");
        assertTrue(hud.contains("public boolean creativeSession;"),
                "HUD 模型必须有 creativeSession —— 玩家有权知道"
                        + "「我现在手上有没有无限方块」");
        assertTrue(renderer.contains("drawFlightBadge"),
                "必须有渲染入口，且**常驻**（不是只在状态变化时弹一行）");
        assertTrue(game.contains("hud.flying = player.isFlying()"),
                "★ 每帧必须把真实飞行状态刷进 HUD —— 指示与状态脱钩比没有指示更糟");

        // ★ 指示不得依赖 onGround，否则等于没有。
        //   判据是「drawFlightBadge 的**方法体里**不得出现 model.onGround」，
        //   而不是匹配某一种写法 —— 反向验证注入用的是
        //   `!model.flying || model.onGround`，与 `&&` 那种形状不同，
        //   第一版断言因为写得太窄而漏判（症状：注入没被抓住，却报成守卫的问题）。
        final int badgeStart = renderer.indexOf("private void drawFlightBadge(");
        assertTrue(badgeStart >= 0, "找不到 drawFlightBadge");
        final int badgeEnd = renderer.indexOf("private int textLine(", badgeStart);
        assertTrue(badgeEnd > badgeStart, "找不到 drawFlightBadge 的方法尾");
        final String badgeBody = renderer.substring(badgeStart, badgeEnd);

        assertFalse(badgeBody.contains("model.onGround"),
                "★ 飞行指示**不得**依赖 onGround —— 站着的时候 onGround 为真，"
                        + "挂上去就等于在玩家最需要它的时候永远不显示。"
                        + "这正是主理人预判不了双击行为的直接原因");
    }

    @Test
    @DisplayName("§4.3 的两条文案都在（玩家必须当场知道不写存档）")
    void bothSessionNoticesExist() throws IOException {
        String loc = codeWithoutComments(
                Path.of("src", "main", "java", "com", "skyisland", "ui", "Localization.java"));

        assertTrue(loc.contains("MSG_CREATIVE_SESSION_ON"),
                "缺少进入会话的提示 key");
        assertTrue(loc.contains("MSG_CREATIVE_SESSION_OFF"),
                "缺少退出会话的提示 key");

        // ★ 文案内容必须含"不写入存档"与"建筑仍然留在世界里"。
        //   这两条是玩家唯一的信息来源（没有常驻界面），而它们恰恰是
        //   这次折中代价的准确描述。漏掉任何一条都是在隐瞒。
        assertTrue(loc.contains("不写入存档"),
                "★ 进入会话的文案必须写明「不写入存档」 —— "
                        + "否则玩家以为存档被改了，不敢退出去");
        assertTrue(loc.contains("仍然留在世界里"),
                "★ 退出会话的文案必须写明「建筑仍然留在世界里」 —— "
                        + "这是 §4.3 理由①的残留代价，不说就是隐瞒");
    }
}
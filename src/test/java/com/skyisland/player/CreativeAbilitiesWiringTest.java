package com.skyisland.player;

import com.skyisland.testutil.SourceScan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ M4-S8b 创造能力的<b>接线</b>守卫（源码扫描）。
 *
 * <h2>为什么行为测试不够</h2>
 * {@code CreativeAbilitiesTest} 有 17 条断言把五项能力钉得很死，
 * 但它们全部直接 {@code new Player}，<b>从不走 SkyIslandGame</b>。
 * 于是下面这些断线可以让那 17 条<u>全部保持绿</u>：
 * <ul>
 *   <li>能力开关从未被接上（{@code setCreativeMode} 没有调用点）→ 玩家永远是生存模式；</li>
 *   <li>开关接了，但门控读的是配置值而不是存档定死的模式 → 创造存档失去能力；</li>
 *   <li>瞬时破坏的分支放到了"不可破坏"判定<b>之前</b> → 创造模式能挖掉资源核心；</li>
 *   <li>飞行的垂直控制叠加在"跳跃 + 重力"之上而不是替换它们 → 起飞瞬间上弹一下。</li>
 * </ul>
 *
 * <h2>★ 判据为什么锚方法体并剥注释</h2>
 * 与 S7/S8a 同一条纪律：注释里必然写着被断言的调用名
 * （例如"关掉创造能力必须同时停飞"就在注释里），不剥注释的话删掉真正的调用断言依然绿。
 */
class CreativeAbilitiesWiringTest {

    private static String player() {
        return SourceScan.readMain("com/skyisland/player/Player.java");
    }

    private static String game() {
        return SourceScan.readMain("com/skyisland/game/SkyIslandGame.java");
    }

    private static String body(String source, String signature) {
        return SourceScan.methodBody(source, signature);
    }

    // ============================================================ 接线存在性

    @Test
    void theGameSwitchesTheAbilitiesWithTheSameGateAsThePalette() {
        String start = body(game(), "private void start(");
        int at = start.indexOf("player.setCreativeMode(");
        assertTrue(at >= 0,
                "★ 没有任何地方调用 player.setCreativeMode：五项创造能力永远不会生效，"
                        + "而 CreativeAbilitiesTest 会全绿（它自己设的开关）");
        String window = start.substring(Math.max(0, at - 400), at);
        assertTrue(window.contains("effectiveGameMode()"),
                "能力开关的门控没有读 effectiveGameMode()；必须由存档定死的模式决定（PRD §4.3），"
                        + "否则配置开关能绕过模式锁定");
        assertFalse(window.contains("config.gameMode()"),
                "能力开关读了 config.gameMode()：那只是「尚未定死时的缺省来源」，"
                        + "用它门控会让已定死为创造的世界凭空失去能力");
    }

    @Test
    void playerHurtCarriesTheImmunityGuard() {
        // ★ 签名必须写全：这里有两个 hurt 重载，只写 "public void hurt(" 会命中
        //   两参的那个（它只是转发），于是断言在"守卫其实在"的情况下假红。
        String hurt = body(player(),
                "public void hurt(World world, int amount, DamageCause cause)");
        assertTrue(hurt.contains("creativeMode"),
                "Player.hurt 里没有创造模式分支。免疫必须放在这里而不是调用方 —— "
                        + "调用方有三个（落地结算 / 实体 tick / 战斗），在任何一侧加判断都会漏掉另外两个");
        assertTrue(hurt.contains("damageNegated"),
                "免疫必须可计数：否则「是不是真的免疫了」只能靠猜");
    }

    @Test
    void checkVoidCarriesTheCreativeException() {
        String voidCheck = body(player(), "private void checkVoid(");
        assertTrue(voidCheck.contains("creativeMode"),
                "★ checkVoid 里没有创造模式分支：创造模式下飞行会变成自杀（PRD §5.5 明文例外）");
        assertTrue(voidCheck.contains("Coords.VOID_KILL_Y"),
                "创造模式必须「停在虚空底部」，而不是继续下沉或原地不动地穿过去");
    }

    /**
     * ★ 顺序守卫：瞬时破坏必须在"不可破坏"判定<b>之后</b>。
     *
     * <p>把分支放到前面，创造模式就能挖掉资源核心 —— 即用 UI 绕过 PRD §5.1.1 的硬约束，
     * 而那种写法从代码上看完全合理。
     */
    @Test
    void theInstantBreakBranchSitsAfterTheUnbreakableCheck() {
        String mining = body(player(), "private void updateMining(");
        int unbreakableAt = mining.indexOf("block.isBreakable()");
        int creativeAt = mining.indexOf("creativeMode");
        assertTrue(unbreakableAt >= 0, "updateMining 里找不到不可破坏判定");
        assertTrue(creativeAt >= 0, "updateMining 里找不到创造模式瞬时破坏分支");
        assertTrue(unbreakableAt < creativeAt,
                "★ 瞬时破坏必须排在「不可破坏」判定之后（现状：isBreakable@" + unbreakableAt
                        + " vs creative@" + creativeAt + "）；"
                        + "排到前面会让创造模式挖掉资源核心（PRD §5.2 明文禁止）");
        assertTrue(mining.contains("executeBreak("),
                "瞬时破坏与进度破坏必须共用同一段结算代码 —— "
                        + "各写一份迟早会出现「创造模式挖石头不掉圆石」这种只在一种模式下成立的偏差");
    }

    @Test
    void placementSkipsConsumptionOnlyInCreativeMode() {
        String placement = body(player(), "private void handlePlacement(");
        assertTrue(placement.contains("consumeSelected("), "放置路径不再消耗物品了？");
        assertTrue(placement.contains("!creativeMode"),
                "★ 消耗必须显式地只在非创造模式下发生（PRD §5.3 放置不消耗）。"
                        + "写成「创造模式多补 1 个」之类的等价实现会让背包数量出现幽灵增减");
    }

    // ============================================================ 飞行

    @Test
    void flightReplacesJumpAndGravityInsteadOfStackingOnTopOfThem() {
        String step = body(player(), "public void step(");
        assertTrue(step.contains("updateFlightToggle("), "step 里没有驱动飞行开关");
        assertTrue(step.contains("applyFlightVertical("), "step 里没有施加飞行垂直速度");
        assertTrue(step.contains("if (flying)"),
                "★ 飞行必须<b>替换</b>「跳跃 + 重力」而不是叠加在上面："
                        + "叠加的表现是玩家在飞行中按住空格会同时获得上升速度与一次起跳初速，"
                        + "一按就往上弹一下，且只在恰好贴着地面时发生");
    }

    @Test
    void theFlightToggleRunsBeforeMovement() {
        String step = body(player(), "public void step(");
        int toggleAt = step.indexOf("updateFlightToggle(");
        int moveAt = step.indexOf("applyMovementInput(");
        assertTrue(toggleAt >= 0 && moveAt >= 0, "step 里缺少飞行开关或移动调用");
        assertTrue(toggleAt < moveAt,
                "★ 飞行开关必须在移动之前判定（现状：toggle@" + toggleAt + " vs move@" + moveAt
                        + "）；反过来的后果是本步起飞却仍按走路速度移动，起飞那一步会「顿」一下");
    }

    @Test
    void setFlyingRefusesSurvivalMode() {
        String setter = body(player(), "public void setFlying(");
        assertTrue(setter.contains("creativeMode"),
                "★ setFlying 里没有创造模式判据：生存模式将可以开启飞行。"
                        + "这是「飞行只有创造模式能开」唯一的落点，不能只靠调用方自觉");
    }

    @Test
    void turningCreativeOffAlsoStopsFlight() {
        String setter = body(player(), "public void setCreativeMode(");
        assertTrue(setter.contains("flying = false"),
                "★ 关掉创造能力必须同时停飞：否则玩家会「以生存模式的身体继续飞着」，"
                        + "而生存模式既没有免疫也没有虚空保护 —— 一次飞行变成一次必死的下坠");
    }

    // ============================================================ 可观测性

    @Test
    void theFlightNoticeIsUpdatedEveryLogicStep() {
        String stepLogic = body(game(), "public void stepLogic(");
        assertTrue(stepLogic.contains("updateFlightNotice()"),
                "stepLogic 里没有更新飞行提示：双击空格是本项目自己选的键位（PRD 未指定），"
                        + "不给反馈就没有人知道它存在");
    }

    @Test
    void theDebugHudShowsTheFlightState() {
        String hud = body(game(), "private void updateHud(");
        assertTrue(hud.contains("player.isFlying()"),
                "调试 HUD 不显示飞行状态：现场排查「为什么飞不起来」时将无从下手");
        assertTrue(hud.contains("player.isCreativeMode()"),
                "调试 HUD 不显示创造模式：无法判断本局到底按哪个口径在跑");
    }
}

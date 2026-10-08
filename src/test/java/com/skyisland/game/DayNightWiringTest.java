package com.skyisland.game;

import com.skyisland.testutil.SourceScan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ M5a：昼夜循环的<b>接线</b>守卫（只看接线，不看数值）。
 *
 * <h3>为什么这一类必须存在</h3>
 * <p>昼夜是本项目第一条<b>横跨六个文件</b>的特性：
 * {@code DayClock}（模型）→ {@code SkyIslandGame}（推进）→ {@code Renderer}（uniform）
 * → {@code voxel.frag}（公式）→ {@code ChunkMesher}（顶点数据）→ {@code SaveManager}（落盘）。
 * 任何一环断掉，**画面都不会报错**：uniform 停在默认值就是"一直白天"，
 * 顶点里少写一个 float 就是"某些东西夜里特别暗"，而两者都跑得好好的。
 *
 * <h3>为什么用源码扫描而不是行为断言</h3>
 * <p>其中两条<b>无法</b>用单测表达：
 * <ul>
 *   <li>{@code glUniform} 必须写在 {@code shader.bind()} <b>之后</b> ——
 *       顺序错了 uniform 会写进上一个 program，症状是"昼夜完全不生效"，
 *       而<b>没有任何取值断言会红</b>；</li>
 *   <li>{@code renderer.setDaylight} 必须排在 {@code clear()} <b>之前</b> ——
 *       否则本帧天空用旧值、地形用新值。</li>
 * </ul>
 * 这两条都是"顺序"，而"顺序"在源码扫描里与"没写"长得一模一样 ——
 * 所以判据必须是<b>方法体内的相对位置</b>，而不是"存在某行"。
 *
 * <p>★ 全程 {@link SourceScan#withoutComments}：注释能满足任何"contains"断言，
 * 本项目已经被它咬过三次。
 */
class DayNightWiringTest {

    private static String main(String path) {
        return SourceScan.withoutComments(SourceScan.readMain(path));
    }

    private static String game() {
        return main("com/skyisland/game/SkyIslandGame.java");
    }

    private static String renderer() {
        return main("com/skyisland/render/Renderer.java");
    }

    // ============================================================ ① uniform 必须在 bind 之后

    @Test
    void theWorldPassWritesTheDaylightUniformsAfterBindingTheShader() {
        String body = SourceScan.withoutComments(
                SourceScan.methodBody(renderer(), "public void renderWorld("));
        int bind = body.indexOf("voxelShader.bind()");
        int atlas = body.indexOf("setInt(\"uBlockAtlas\"");
        int daylight = body.indexOf("applyDaylightUniforms()");
        int chunk = body.indexOf("chunkRenderer.render(");

        assertTrue(bind >= 0, "renderWorld 必须绑定 voxelShader");
        assertTrue(daylight >= 0, "renderWorld 必须写昼夜 uniform");
        assertTrue(bind < daylight,
                "★ 昼夜 uniform 必须写在 voxelShader.bind() 之后 —— "
                        + "glUniform 写的是「当前程序」，顺序反了会写进上一个 program，"
                        + "症状是昼夜完全不生效且不报任何错");
        assertTrue(atlas < daylight && bind < atlas,
                "昼夜 uniform 应与 uBlockAtlas 挨在一起（同一个 bind 之后的批次）");
        assertTrue(daylight < chunk,
                "必须在 chunkRenderer.render 之前 —— 之后 uniform 就来不及影响这一帧的地形了");
    }

    @Test
    void theViewmodelPassRepeatsTheDaylightUniforms() {
        String body = SourceScan.withoutComments(
                SourceScan.methodBody(renderer(), "public void renderViewmodel("));
        int bind = body.indexOf("voxelShader.bind()");
        int daylight = body.indexOf("applyDaylightUniforms()");
        int draw = body.indexOf("viewmodelRenderer.render(");

        assertTrue(daylight >= 0, "手持物 pass 必须重新写昼夜 uniform");
        assertTrue(bind >= 0 && bind < daylight,
                "手持物 pass 里 bind 之后必须紧跟 applyDaylightUniforms —— "
                        + "uniform 是逐程序存的，不重写就会退回世界 pass 的值（"
                        + "手持物与环境不同步，白天环境 + 夜晚枪口）");
        assertTrue(daylight < draw, "必须在绘制之前");
    }

    @Test
    void applyDaylightUniformsWritesAllThreeAndOnlyTheThree() {
        String body = SourceScan.withoutComments(
                SourceScan.methodBody(renderer(), "private void applyDaylightUniforms("));
        assertTrue(body.contains("setFloat(\"uSkyLevel\", skyLevel)"), "缺 uSkyLevel");
        assertTrue(body.contains("setFloat(\"uAmbientFloor\", ambientFloor)"), "缺 uAmbientFloor");
        assertTrue(body.contains("setFloat(\"uDayFloor\""),
                "★ 缺 uDayFloor —— 它是分母基准，漏了它着色器取默认值 0，"
                        + "于是除以 0 → 画面全白或全黑，且不报任何错");
    }

    // ============================================================ ② setDaylight 必须早于 clear

    @Test
    void setDaylightIsCalledBeforeClear() {
        String game = SourceScan.withoutComments(game());
        int set = game.indexOf("renderer.setDaylight(dayClock)");
        int clear = game.indexOf("renderer.clear()");
        assertTrue(set >= 0, "主循环必须把昼夜交给渲染器");
        assertTrue(clear >= 0, "主循环必须清屏");
        assertTrue(set < clear,
                "★ setDaylight 必须排在 clear() 之前 —— clear() 也要用它插值天空色，"
                        + "顺序反了会得到'天空用旧时刻、地形用新时刻'的撕裂帧");
    }

    // ============================================================ ③ 逻辑步推进时钟

    @Test
    void theLogicStepAdvancesTheClockWithTheSameDeltaAsElapsedTime() {
        String body = SourceScan.withoutComments(
                SourceScan.methodBody(game(), "public void stepLogic("));
        int elapsed = body.indexOf("elapsedSeconds += fixedDt");
        int advance = body.indexOf("dayClock.advance(fixedDt)");
        assertTrue(elapsed >= 0 && advance >= 0, "时钟必须由逻辑步推进");
        assertTrue(elapsed < advance,
                "时钟推进应与 elapsedSeconds 同一处、同一个 dt —— "
                        + "两者用不同的 dt 会让画面时刻与性能读数对不上");
    }

    @Test
    void theClockIsAdvancedInsideTheSimulationGuard() {
        String body = SourceScan.withoutComments(
                SourceScan.methodBody(game(), "public void stepLogic("));
        int pauseGuard = body.indexOf("isSimulationRunning()");
        int advance = body.indexOf("dayClock.advance(fixedDt)");
        assertTrue(pauseGuard >= 0, "stepLogic 必须有暂停守卫");
        assertTrue(pauseGuard < advance,
                "★ 暂停期间不得推进昼夜 —— 否则玩家开一次菜单，天就黑了一半");
    }

    // ============================================================ ④ 存档与读档

    @Test
    void savingPassesTheClockSoTheTimeIsWritten() {
        String body = SourceScan.withoutComments(
                SourceScan.methodBody(game(), "private SaveResult performSave("));
        assertTrue(body.contains("saveManager.save(world, player, dayClock)"),
                "★ 存档必须带时钟 —— 漏传的症状是'每次读档时刻都被重置'，且不报任何错");
        assertFalse(body.contains("saveManager.save(world, player)"),
                "不得调用不带时钟的旧签名（它会把时刻抹掉，见 SaveManagerDayTimeTest）");
    }

    @Test
    void loadingRestoresTheTimeRightAfterTheWorldIsRead() {
        String game = SourceScan.withoutComments(game());
        int load = game.indexOf("saveManager.loadInto(world, player)");
        int apply = game.indexOf("saveManager.applyWorldTime(dayClock)");
        assertTrue(load >= 0 && apply >= 0, "读档后必须回填时刻");
        assertTrue(load < apply, "必须在读档之后回填（否则读到的是空世界）");
    }

    @Test
    void aNewWorldResetsTheClockToTheStartOfDay() {
        String body = SourceScan.withoutComments(
                SourceScan.methodBody(game(), "private void startNewWorld("));
        assertTrue(body.contains("dayClock = new DayClock(dayClock.totalSeconds())"),
                "★ 新建世界必须把时钟复位 —— 否则玩家在第 3 天夜里新建世界，"
                        + "会直接站在几乎全黑的夜里，看上去像'世界坏了'");
    }

    // ============================================================ ⑤ 顶点侧：天光与火把必须分开取

    @Test
    void theMesherTakesSkyAndTorchSeparatelyForThePackedLight() {
        String mesher = main("com/skyisland/render/mesh/ChunkMesher.java");
        assertTrue(mesher.contains("lights.skyLightAt("), "必须单独取天光");
        assertTrue(mesher.contains("lights.torchLightAt("), "必须单独取火把光");
        assertTrue(mesher.contains("VertexFormat.packLight("), "必须走唯一编码点打包");
        assertFalse(mesher.contains("lights.lightAt("),
                "★ 顶点里不得再用 lightAt 的 max 结果打包光照 —— "
                        + "只存 max 就丢掉了'这一份亮度来自天光还是火把'，"
                        + "片元无法让火把免受昼夜影响（PRD §4.4「火把成为主要照明」）");
    }

    @Test
    void theMesherStillBakesTheSameDayShadeAsBefore() {
        // ★★ 必须剥注释：本项目第四次栽在这条上。
        //   ChunkMesher 第 123 行的 javadoc 里写着同一串
        //   `lights.shadeFactor(Math.max(skyLight, torchLight));`，
        //   所以朴素的 source.contains(...) 在**代码被改掉之后仍然为真** ——
        //   断线 G 注入（shade 只看天光）实测 0 红。
        //   与「注释能满足扫描断言」是同一条纪律：判据必须作用于"会被编译的文本"。
        String mesher = SourceScan.withoutComments(main("com/skyisland/render/mesh/ChunkMesher.java"));
        assertTrue(mesher.contains("shadeFactor(Math.max(skyLight, torchLight))"),
                "★ shade 必须仍按 max 计算 —— 这保证白天与改动前逐位一致，"
                        + "也就是'昼夜首帧不改变任何一个像素'");
    }

    // ============================================================ ⑥ HUD

    @Test
    void theHudLabelGoesThroughLocalizationAndNotALiteral() {
        String game = SourceScan.withoutComments(game());
        assertTrue(game.contains("Localization.text(Localization.HUD_DAY_STATUS"),
                "时刻条文案必须走 Localization（PRD 6.7 禁止 UI 拼句子）");
        assertTrue(game.contains("hud.dayStatusLabel ="),
                "updateHud 必须填充时刻条");
        assertTrue(game.contains("hud.dayPhaseProgress ="),
                "必须填充分阶段进度条");
        assertTrue(game.contains("hud.dayIsNight ="),
                "必须填充夜晚标记（否则黄昏仍是白色，玩家以为还没开始）");
    }

    @Test
    void theClockAndTheHudAreDrivenByTheSameClockInstance() {
        String game = SourceScan.withoutComments(game());
        assertTrue(game.contains("dayClock.advance(fixedDt)"));
        assertTrue(game.contains("dayClock.phaseSecondsLeft()"),
                "HUD 倒计时必须与推进用的是同一个时钟 —— "
                        + "两个时钟源的倒计时会与画面里的天色对不上");
    }

    @Test
    void theDaylightUniformsAreVisibleToTheDebugReadout() {
        String game = SourceScan.withoutComments(game());
        assertTrue(game.contains("renderer.skyLevel()") && game.contains("renderer.ambientFloor()"),
                "debug 行应回读渲染器**实际**持有的昼夜值 —— "
                        + "回读 DayClock 只能证明时钟在动，证明不了 uniform 被送到了着色器");
    }

    // ============================================================ ⑦ 渲染器自身的默认值

    @Test
    void theRendererDefaultsToFullDaylight() {
        String renderer = renderer();
        assertTrue(renderer.contains("private float skyBlend = 1f;"),
                "未设置时必须是白天才对 —— 任何在 setDaylight 之前就开画的路径都不该看见夜景");
        assertTrue(renderer.contains("private float skyLevel = 1f;"));
    }

    @Test
    void aNullClockFallsBackToFullDaylightInsteadOfThrowing() {
        String body = SourceScan.withoutComments(
                SourceScan.methodBody(renderer(), "public void setDaylight("));
        assertTrue(body.contains("if (clock == null)"),
                "★ setDaylight(null) 必须回落到白天而不是抛异常 —— "
                        + "自测与启动早期都可能在时钟建好之前调用它");
    }
}
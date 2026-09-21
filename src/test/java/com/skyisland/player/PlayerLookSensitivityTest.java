package com.skyisland.player;

import com.skyisland.input.InputMapper;
import com.skyisland.input.InputState;
import com.skyisland.settings.Action;
import com.skyisland.settings.GameSettings;
import com.skyisland.settings.InputBinding;
import com.skyisland.settings.LookConfig;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灵敏度与反转 Y <b>端到端</b>接通玩法（M1.5 规格第 7 条）。
 *
 * <p><b>为什么需要这一层测试，而不止是 {@code LookConfigTest}：</b>
 * 换算公式对，不等于"设置真的改变了视角"。中间还有两步可能断：
 * 相机是否收到了新的换算率（{@code Player.lookDegPerPixel}）、
 * 以及反转偏好是否被施加在正确的层（输入层）。任一处断开，
 * 症状都是"设置界面上滑杆能拖，视角却纹丝不动"。
 *
 * <p>本类的链路与真实运行时<u>完全同路</u>，只绕开了 {@code OS → GLFW} 那一段
 * （TR7 已知无法注入合成事件）：
 * <pre>
 *   InputState.injectCursorDelta
 *     → InputMapper.poll（施加反转 Y）
 *     → Player.step → Camera.addLook（施加灵敏度）
 * </pre>
 *
 * <p><b>灵敏度 = 1.0 时的手感必须与 M1 逐位一致</b>，这是本阶段"不改变既有行为"
 * 承诺的可执行形式：0.12 度/像素 × 100 像素 = 12°。
 */
class PlayerLookSensitivityTest {

    private static final double DT = 1.0 / 60.0;
    private static final double EPS = 1e-9;

    private static World world() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    /**
     * 走一次完整的"鼠标位移 → 意图 → 相机角度"。
     *
     * @return {@code {Δyaw, Δpitch}}
     */
    private static double[] look(GameSettings settings, double dx, double dy) {
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        double yaw0 = player.camera().yawDeg();
        double pitch0 = player.camera().pitchDeg();

        // 装配阶段会做的事：把设置里的倍数换算成相机的换算率
        player.setLookDegPerPixel(LookConfig.effectiveDegPerPixel(
                InputMapper.BASE_DEG_PER_PIXEL, settings.mouseSensitivity()));

        InputState in = new InputState();
        in.injectCursorDelta(dx, dy);
        PlayerIntent intent = new InputMapper().poll(in, settings.keyBindings(),
                settings.invertMouseY());

        player.step(world(), intent, DT);

        return new double[]{
                player.camera().yawDeg() - yaw0,
                player.camera().pitchDeg() - pitch0,
        };
    }

    // ============================================================ 与 M1 的等同

    @Test
    void defaultSettingsReproduceTheM1LookSensitivityExactly() {
        GameSettings s = new GameSettings();

        double[] d = look(s, 100, 0);

        assertEquals(-12.0, d[0], EPS,
                "100 像素 × 0.12 度/像素 = 12°。这条若变了，M1 的 LOOK 断言会以一种"
                        + "看起来像'相机坏了'的方式失败，实际根因却在设置层");
    }

    @Test
    void yawIsNegatedForRightwardMouseMovement() {
        GameSettings s = new GameSettings();

        assertEquals(-12.0, look(s, 100, 0)[0], EPS, "鼠标右移 → 视角右转");
        assertEquals(12.0, look(s, -100, 0)[0], EPS);
    }

    @Test
    void pitchIsNegatedForDownwardMouseMovement() {
        GameSettings s = new GameSettings();

        assertEquals(-2.4, look(s, 0, 20)[1], EPS, "鼠标下移 20 px → 视角下俯 2.4°");
        assertEquals(2.4, look(s, 0, -20)[1], EPS);
    }

    // ============================================================ 灵敏度生效

    @Test
    void doublingTheSensitivityDoublesTheTurn() {
        GameSettings s = new GameSettings();
        s.setMouseSensitivity(2.0);

        assertEquals(-24.0, look(s, 100, 0)[0], EPS,
                "灵敏度滑杆必须真的改变视角速度 —— 否则界面能动而手感不变");
    }

    @Test
    void halvingTheSensitivityHalvesTheTurn() {
        GameSettings s = new GameSettings();
        s.setMouseSensitivity(0.5);

        assertEquals(-6.0, look(s, 100, 0)[0], EPS);
    }

    @Test
    void theMinimumSensitivityStillTurnsTheCamera() {
        GameSettings s = new GameSettings();
        s.setMouseSensitivity(GameSettings.MIN_SENSITIVITY);

        double turned = look(s, 100, 0)[0];

        assertEquals(-1.2, turned, EPS);
        assertTrue(turned != 0, "最小值也必须能转动视角 —— 0 会让游戏无法操作");
    }

    @Test
    void theMaximumSensitivityStaysProportional() {
        GameSettings s = new GameSettings();
        s.setMouseSensitivity(GameSettings.MAX_SENSITIVITY);

        assertEquals(-24.0, look(s, 100, 0)[0], EPS);
    }

    @Test
    void sensitivityAppliesToVerticalLookingAsWell() {
        GameSettings s = new GameSettings();
        s.setMouseSensitivity(1.5);

        assertEquals(-3.6, look(s, 0, 20)[1], EPS, "20 px × 0.18 度/像素 = 3.6°");
    }

    @Test
    void changingTheSensitivityTakesEffectImmediatelyWithoutRestart() {
        GameSettings s = new GameSettings();
        double before = look(s, 100, 0)[0];

        s.setMouseSensitivity(1.5);   // 相当于玩家在设置界面拖了一下滑杆
        double after = look(s, 100, 0)[0];

        assertEquals(-12.0, before, EPS);
        assertEquals(-18.0, after, EPS,
                "规格要求'立即生效'：设置改变后必须立刻影响下一次视角更新，不需要重开游戏");
    }

    // ============================================================ 反转 Y

    @Test
    void invertingMouseYFlipsOnlyVerticalLooking() {
        GameSettings s = new GameSettings();
        s.setInvertMouseY(true);

        double[] d = look(s, 100, 20);

        assertEquals(-12.0, d[0], EPS, "反转 Y 不得影响横向 —— 那是另一个话题（左手鼠标）");
        assertEquals(2.4, d[1], EPS);
    }

    @Test
    void invertingMouseYIsIndependentOfSensitivity() {
        GameSettings s = new GameSettings();
        s.setInvertMouseY(true);
        s.setMouseSensitivity(2.0);

        assertEquals(-24.0, look(s, 100, 0)[0], EPS);
        assertEquals(4.8, look(s, 0, 20)[1], EPS);
    }

    @Test
    void invertingThenRevertingRestoresTheOriginalBehaviour() {
        GameSettings s = new GameSettings();
        double original = look(s, 0, 20)[1];

        s.setInvertMouseY(true);
        double inverted = look(s, 0, 20)[1];

        s.setInvertMouseY(false);
        double restored = look(s, 0, 20)[1];

        assertEquals(-2.4, original, EPS);
        assertEquals(2.4, inverted, EPS);
        assertEquals(original, restored, EPS, "反转是一个可逆开关，不能有累积副作用");
    }

    // ============================================================ 换算率的兜底

    @Test
    void aDegenerateLookRateFallsBackToTheBaseline() {
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);

        assertEquals(Camera.SENSITIVITY_DEG_PER_PIXEL, player.lookDegPerPixel(), EPS,
                "默认值必须就是 M1 的基准");

        player.setLookDegPerPixel(0);
        assertEquals(Camera.SENSITIVITY_DEG_PER_PIXEL, player.lookDegPerPixel(), EPS,
                "0 会让视角完全失灵，且它是可持久化的设置 —— 一旦写进文件就每次都复现");

        player.setLookDegPerPixel(-1);
        assertEquals(Camera.SENSITIVITY_DEG_PER_PIXEL, player.lookDegPerPixel(), EPS);

        player.setLookDegPerPixel(Double.NaN);
        assertEquals(Camera.SENSITIVITY_DEG_PER_PIXEL, player.lookDegPerPixel(), EPS,
                "NaN 会让相机角度变成 NaN，表现为画面彻底黑掉且不报错");
    }

    @Test
    void aValidCustomRateIsKeptAsIs() {
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);

        player.setLookDegPerPixel(0.3);

        assertEquals(0.3, player.lookDegPerPixel(), EPS);
    }

    @Test
    void theBaselineIsSharedWithTheCameraSoTheTwoCannotDrift() {
        assertEquals(Camera.SENSITIVITY_DEG_PER_PIXEL, InputMapper.BASE_DEG_PER_PIXEL, 0.0,
                "输入层的基准必须指向相机的常量；两处各写一个 0.12 迟早漂移成两种手感");
    }

    @Test
    void keyBindingsDoNotAffectLooking() {
        GameSettings s = new GameSettings();
        double before = look(s, 100, 0)[0];

        s.keyBindings().set(Action.MOVE_FORWARD, InputBinding.key(GLFW.GLFW_KEY_K));

        assertEquals(before, look(s, 100, 0)[0], EPS,
                "改键位不该影响视角换算 —— 两条链路必须彼此独立");
        assertEquals(GLFW.GLFW_KEY_K, s.keyBindings().get(Action.MOVE_FORWARD).code());
    }
}

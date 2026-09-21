package com.skyisland.settings;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置数据与范围钳制（M1.5 规格第 3/6/7 条）。
 *
 * <p><b>为什么"钳制"值得一整个测试类：</b>设置项有<u>四个</u>来源 ——
 * 默认值、{@code settings.json}、设置界面、自测脚本。任何一个来源绕过校验，
 * 都会得到一个"可持久化"的坏值：{@code fovDeg: 1000} 一旦写进文件，
 * 之后每次启动都复现，且现象是"画面完全不对"，与配置文件很难联系起来。
 * 钳制放进 setter 之后，"从哪儿来"就不再重要 —— 本类就是这条设计决定的守卫。
 *
 * <p><b>取向是"夹取"而不是"拒绝"：</b>文件里写着 1000 时，拒绝会让设置项悄悄停在
 * 默认值、丢弃用户意图；夹到上限 90 至少保留了意图的方向，并由
 * {@link SettingsStore} 记一条"发生过夹取"的告警。
 */
class GameSettingsTest {

    private static final double EPS = 1e-9;

    // ============================================================ 默认值

    @Test
    void defaultsAreTheDocumentedOnes() {
        GameSettings s = new GameSettings();

        assertEquals(1.0, s.mouseSensitivity(), EPS, "灵敏度默认必须是 1.0 —— M1 的手感标定值");
        assertFalse(s.invertMouseY());
        assertEquals(70.0, s.fovDeg(), EPS, "FOV 默认 70 是 M1 的既有值");
        assertFalse(s.vsync(), "M1 的 vsync 默认关闭，M1.5 不得改变它");
        assertFalse(s.showFps());
        assertEquals(80, s.masterVolume());
        assertEquals(80, s.sfxVolume());
        assertTrue(s.keyBindings().allDefaults());
        assertTrue(s.isAllDefaults());
    }

    @Test
    void defaultsFactoryProducesIndependentInstances() {
        GameSettings a = GameSettings.defaults();
        GameSettings b = GameSettings.defaults();

        a.setFovDeg(90);

        assertEquals(70.0, b.fovDeg(), EPS, "两个默认实例不能共享状态");
    }

    // ============================================================ 灵敏度

    @Test
    void sensitivityIsClampedToItsRange() {
        GameSettings s = new GameSettings();

        s.setMouseSensitivity(0.0);
        assertEquals(GameSettings.MIN_SENSITIVITY, s.mouseSensitivity(), EPS);
        s.setMouseSensitivity(-5);
        assertEquals(GameSettings.MIN_SENSITIVITY, s.mouseSensitivity(), EPS);
        s.setMouseSensitivity(99);
        assertEquals(GameSettings.MAX_SENSITIVITY, s.mouseSensitivity(), EPS);
    }

    @Test
    void nonFiniteSensitivityFallsBackToDefault() {
        GameSettings s = new GameSettings();

        s.setMouseSensitivity(Double.NaN);
        assertEquals(GameSettings.DEFAULT_SENSITIVITY, s.mouseSensitivity(), EPS,
                "NaN 一旦进入换算链，视角会永久失灵且不报错");
        s.setMouseSensitivity(Double.POSITIVE_INFINITY);
        assertEquals(GameSettings.DEFAULT_SENSITIVITY, s.mouseSensitivity(), EPS);
    }

    @Test
    void sensitivityStepsByTheDocumentedGranularity() {
        GameSettings s = new GameSettings();

        s.adjustMouseSensitivity(1);
        assertEquals(1.05, s.mouseSensitivity(), EPS);
        s.adjustMouseSensitivity(-1);
        assertEquals(1.00, s.mouseSensitivity(), EPS);
    }

    @Test
    void repeatedAdjustmentsStayInsideTheRangeAndStayExact() {
        GameSettings s = new GameSettings();

        for (int i = 0; i < 200; i++) {
            s.adjustMouseSensitivity(1);
        }
        assertEquals(GameSettings.MAX_SENSITIVITY, s.mouseSensitivity(), EPS);

        for (int i = 0; i < 400; i++) {
            s.adjustMouseSensitivity(-1);
        }
        assertEquals(GameSettings.MIN_SENSITIVITY, s.mouseSensitivity(), EPS,
                "反复加减之后不能因为浮点累积误差落到范围外");

        for (int i = 0; i < 200; i++) {
            s.adjustMouseSensitivity(1);
        }
        assertEquals(GameSettings.MAX_SENSITIVITY, s.mouseSensitivity(), EPS);

        // 回到中点，验证步进的精确性（每一步都要能落回 1.00）
        for (int i = 0; i < 20; i++) {
            s.adjustMouseSensitivity(-1);
        }
        assertEquals(1.00, s.mouseSensitivity(), EPS, "步进累积不能漂移");
    }

    // ============================================================ FOV / 音量

    @Test
    void fovIsClampedAndStepped() {
        GameSettings s = new GameSettings();

        s.setFovDeg(1000);
        assertEquals(GameSettings.MAX_FOV, s.fovDeg(), EPS);
        s.setFovDeg(0);
        assertEquals(GameSettings.MIN_FOV, s.fovDeg(), EPS);

        s.setFovDeg(70);
        s.adjustFov(1);
        assertEquals(71.0, s.fovDeg(), EPS);
        s.adjustFov(-1);
        assertEquals(70.0, s.fovDeg(), EPS);
    }

    @Test
    void volumeIsClampedAndSteppedInFives() {
        GameSettings s = new GameSettings();

        s.setMasterVolume(1000);
        assertEquals(GameSettings.MAX_VOLUME, s.masterVolume());
        s.setMasterVolume(-1);
        assertEquals(GameSettings.MIN_VOLUME, s.masterVolume());

        s.setSfxVolume(50);
        s.adjustSfxVolume(1);
        assertEquals(55, s.sfxVolume());
        s.setSfxVolume(98);
        s.adjustSfxVolume(1);
        assertEquals(100, s.sfxVolume(), "接近上限时必须夹取，而不是溢出成 103");
    }

    // ============================================================ 布尔项

    @Test
    void booleansToggleIndependently() {
        GameSettings s = new GameSettings();

        s.setInvertMouseY(true);
        s.setVsync(true);
        s.setShowFps(true);

        assertTrue(s.invertMouseY());
        assertTrue(s.vsync());
        assertTrue(s.showFps());

        s.setInvertMouseY(false);
        assertFalse(s.invertMouseY());
        assertTrue(s.vsync(), "关掉反转 Y 不该顺手改掉 VSync");
    }

    // ============================================================ 整表

    @Test
    void resetToDefaultsRestoresEveryFieldIncludingBindings() {
        GameSettings s = new GameSettings();
        s.setMouseSensitivity(1.8);
        s.setInvertMouseY(true);
        s.setFovDeg(90);
        s.setVsync(true);
        s.setShowFps(true);
        s.setMasterVolume(5);
        s.setSfxVolume(10);
        s.keyBindings().set(Action.MOVE_FORWARD, InputBinding.key(GLFW.GLFW_KEY_I));
        assertFalse(s.isAllDefaults());

        s.resetToDefaults();

        assertTrue(s.isAllDefaults());
        assertEquals(1.0, s.mouseSensitivity(), EPS);
        assertEquals(70.0, s.fovDeg(), EPS);
        assertEquals(80, s.masterVolume());
        assertFalse(s.vsync());
        assertTrue(s.keyBindings().allDefaults(),
                "'恢复默认'若不包含键位，玩家改坏键位后就再也回不来了");
    }

    @Test
    void copyIsADeepCopy() {
        GameSettings s = new GameSettings();
        s.setMouseSensitivity(1.5);
        s.keyBindings().set(Action.JUMP, InputBinding.key(GLFW.GLFW_KEY_J));

        GameSettings copy = s.copy();
        assertEquals(s, copy);
        assertNotSame(s, copy);

        copy.setMouseSensitivity(0.5);
        copy.keyBindings().set(Action.JUMP, InputBinding.key(GLFW.GLFW_KEY_K));

        assertEquals(1.5, s.mouseSensitivity(), EPS, "改副本不能影响原件");
        assertEquals(InputBinding.key(GLFW.GLFW_KEY_J), s.keyBindings().get(Action.JUMP),
                "键位表必须是深拷贝 —— 共享一张表会让'预览'直接改到真实设置");
    }

    @Test
    void equalsAndHashCodeCoverEveryField() {
        GameSettings a = new GameSettings();
        GameSettings b = new GameSettings();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(null));
        assertFalse(a.equals("not settings"));

        b.setShowFps(true);
        assertNotEquals(a, b);

        GameSettings c = new GameSettings();
        c.keyBindings().set(Action.INVENTORY, InputBinding.UNBOUND);
        assertNotEquals(a, c, "键位差异也必须体现在 equals 上");
    }

    @Test
    void summaryLinesMentionTheHonestAudioLimitation() {
        List<String> lines = GameSettings.defaults().summaryLines();

        assertEquals(8, lines.size());
        String joined = String.join("\n", lines);
        assertTrue(joined.contains("无音频消费方"),
                "音量在本阶段没有消费方，这一点必须出现在日志里，而不是只写在报告里");
        assertTrue(joined.contains("keybindings_custom"));
    }
}

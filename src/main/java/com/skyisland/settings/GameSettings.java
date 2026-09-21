package com.skyisland.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 用户设置（M1.5 规格第 3/6/7 条）。<b>纯数据 + 范围钳制，不含任何 IO 与 GL。</b>
 *
 * <p><b>为什么范围钳制放在 setter 里而不是校验函数里：</b>
 * 设置项的值有四个来源 —— 默认值、settings.json、设置界面、命令行/自测脚本。
 * 只要有一个来源绕过校验，就会出现"FOV = 1000 度"这类会让画面直接失效的值
 * （而且它是<u>可持久化</u>的：写进文件后每次启动都复现）。
 * 把钳制放进 setter 之后，"从哪儿来"就不再重要。
 *
 * <p><b>钳制的取向是"夹取"而不是"拒绝"：</b>文件里写着 {@code fovDeg: 1000} 时，
 * 拒绝会让设置项停在默认值、静默丢弃用户的意图；夹取到上限 90 至少保留了意图的方向，
 * 并且由 {@link SettingsStore} 记一条告警说明发生过夹取。配置系统报错的最佳形式是
 * "照做但记录"，而不是"沉默地回到默认"。
 *
 * <p><b>本阶段的诚实边界：</b>{@code masterVolume / sfxVolume} 在 M1.5 <u>没有任何音频子系统</u>
 * 可以作用（PRD 的音频属后续里程碑）。它们被完整地保存、读取、在界面上可调，
 * 但此刻不产生任何听觉效果 —— 这一点写在报告的技术债里，而不是靠"看起来做了"蒙过去。
 */
public final class GameSettings {

    // ---- 取值范围（同时是设置界面的滑杆边界）----
    public static final double MIN_SENSITIVITY = 0.1;
    public static final double MAX_SENSITIVITY = 2.0;
    public static final double DEFAULT_SENSITIVITY = 1.0;
    public static final double SENSITIVITY_STEP = 0.05;

    public static final double MIN_FOV = 60.0;
    public static final double MAX_FOV = 90.0;
    public static final double DEFAULT_FOV = 70.0;
    public static final double FOV_STEP = 1.0;

    public static final int MIN_VOLUME = 0;
    public static final int MAX_VOLUME = 100;
    public static final int DEFAULT_VOLUME = 80;
    public static final int VOLUME_STEP = 5;

    private double mouseSensitivity = DEFAULT_SENSITIVITY;
    private boolean invertMouseY = false;
    private double fovDeg = DEFAULT_FOV;
    private boolean vsync = false;
    private boolean showFps = false;
    private int masterVolume = DEFAULT_VOLUME;
    private int sfxVolume = DEFAULT_VOLUME;

    private final KeyBindings keyBindings = KeyBindings.defaults();

    public GameSettings() {
    }

    public static GameSettings defaults() {
        return new GameSettings();
    }

    // ============================================================ 取值

    public double mouseSensitivity() {
        return mouseSensitivity;
    }

    public boolean invertMouseY() {
        return invertMouseY;
    }

    public double fovDeg() {
        return fovDeg;
    }

    public boolean vsync() {
        return vsync;
    }

    public boolean showFps() {
        return showFps;
    }

    public int masterVolume() {
        return masterVolume;
    }

    public int sfxVolume() {
        return sfxVolume;
    }

    public KeyBindings keyBindings() {
        return keyBindings;
    }

    // ============================================================ 赋值（全部带钳制）

    public void setMouseSensitivity(double value) {
        this.mouseSensitivity = clampDouble(value, MIN_SENSITIVITY, MAX_SENSITIVITY, DEFAULT_SENSITIVITY);
    }

    public void setInvertMouseY(boolean value) {
        this.invertMouseY = value;
    }

    public void setFovDeg(double value) {
        this.fovDeg = clampDouble(value, MIN_FOV, MAX_FOV, DEFAULT_FOV);
    }

    public void setVsync(boolean value) {
        this.vsync = value;
    }

    public void setShowFps(boolean value) {
        this.showFps = value;
    }

    public void setMasterVolume(int value) {
        this.masterVolume = Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, value));
    }

    public void setSfxVolume(int value) {
        this.sfxVolume = Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, value));
    }

    // ---- 滑块式调整（界面用；方向 -1 / +1）----

    public void adjustMouseSensitivity(int direction) {
        setMouseSensitivity(roundTo(mouseSensitivity + direction * SENSITIVITY_STEP, 2));
    }

    public void adjustFov(int direction) {
        setFovDeg(fovDeg + direction * FOV_STEP);
    }

    public void adjustMasterVolume(int direction) {
        setMasterVolume(masterVolume + direction * VOLUME_STEP);
    }

    public void adjustSfxVolume(int direction) {
        setSfxVolume(sfxVolume + direction * VOLUME_STEP);
    }

    // ============================================================ 整表操作

    /** 恢复到出厂默认（就地修改，界面的"恢复默认"用它）。键位表一并复位。 */
    public void resetToDefaults() {
        this.mouseSensitivity = DEFAULT_SENSITIVITY;
        this.invertMouseY = false;
        this.fovDeg = DEFAULT_FOV;
        this.vsync = false;
        this.showFps = false;
        this.masterVolume = DEFAULT_VOLUME;
        this.sfxVolume = DEFAULT_VOLUME;
        this.keyBindings.restoreDefaults();
    }

    /** 深拷贝（键位表也复制，避免两个 GameSettings 共享一张映射表）。 */
    public GameSettings copy() {
        GameSettings s = new GameSettings();
        s.mouseSensitivity = mouseSensitivity;
        s.invertMouseY = invertMouseY;
        s.fovDeg = fovDeg;
        s.vsync = vsync;
        s.showFps = showFps;
        s.masterVolume = masterVolume;
        s.sfxVolume = sfxVolume;
        s.keyBindings.setAll(this.keyBindings);
        return s;
    }

    /** 是否与出厂默认完全一致（含键位）。 */
    public boolean isAllDefaults() {
        GameSettings d = new GameSettings();
        return equals(d);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GameSettings other)) {
            return false;
        }
        return Double.compare(mouseSensitivity, other.mouseSensitivity) == 0
                && invertMouseY == other.invertMouseY
                && Double.compare(fovDeg, other.fovDeg) == 0
                && vsync == other.vsync
                && showFps == other.showFps
                && masterVolume == other.masterVolume
                && sfxVolume == other.sfxVolume
                && keyBindings.equals(other.keyBindings);
    }

    @Override
    public int hashCode() {
        int h = Double.hashCode(mouseSensitivity);
        h = 31 * h + (invertMouseY ? 1 : 0);
        h = 31 * h + Double.hashCode(fovDeg);
        h = 31 * h + (vsync ? 1 : 0);
        h = 31 * h + (showFps ? 1 : 0);
        h = 31 * h + masterVolume;
        h = 31 * h + sfxVolume;
        h = 31 * h + keyBindings.hashCode();
        return h;
    }

    /** 供日志/报告的结构化一行式清单。 */
    public List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "mouse_sensitivity   = %.2f   (范围 %.1f–%.1f，默认 %.1f)",
                mouseSensitivity, MIN_SENSITIVITY, MAX_SENSITIVITY, DEFAULT_SENSITIVITY));
        lines.add(String.format(Locale.ROOT, "invert_mouse_y      = %s", invertMouseY));
        lines.add(String.format(Locale.ROOT, "fov_deg             = %.1f   (范围 %.0f–%.0f，默认 %.0f)",
                fovDeg, MIN_FOV, MAX_FOV, DEFAULT_FOV));
        lines.add(String.format(Locale.ROOT, "vsync               = %s", vsync));
        lines.add(String.format(Locale.ROOT, "show_fps            = %s", showFps));
        lines.add(String.format(Locale.ROOT, "master_volume       = %d   (范围 %d–%d，默认 %d，本阶段无音频消费方)",
                masterVolume, MIN_VOLUME, MAX_VOLUME, DEFAULT_VOLUME));
        lines.add(String.format(Locale.ROOT, "sfx_volume          = %d   (范围 %d–%d，默认 %d，本阶段无音频消费方)",
                sfxVolume, MIN_VOLUME, MAX_VOLUME, DEFAULT_VOLUME));
        lines.add(String.format(Locale.ROOT, "keybindings_custom  = %d 项被改动（共 %d 个动作）",
                keyBindings.customized().size(), Action.values().length));
        return lines;
    }

    // ============================================================ 内部

    private static double clampDouble(double value, double min, double max, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static double roundTo(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}

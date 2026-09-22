package com.skyisland.render.ui;

/**
 * UI 视觉语言基座（M2.2）：尺寸与缩放的<b>唯一来源</b>。
 *
 * <p><b>为什么要有这一个类：</b>缩放与基准尺寸原本散落在 {@code HudRenderer}
 * （{@code uiScale = round(fbHeight / 720)}）与 {@code MenuLayout}
 * （同口径 {@code round(fbHeight / 720)}）两处，且快捷栏槽位尺寸
 * （{@code 20 * uiScale}）只在 {@code HudRenderer.drawHotbar} 里写成魔法数字。
 * 把"多少像素算一格"收拢到这里，将来的背包界面才能与快捷栏用同一套数字，
 * 否则两套布局迟早漂移。
 *
 * <p><b>像素口径：</b>所有基准尺寸都以 1280×720 下的像素给出
 * （{@link #px(int, int)} 再乘 {@code uiScale}），
 * 这样 4K 全屏下 HUD 不会缩成看不见的细线。
 */
public final class UiMetrics {

    private UiMetrics() {
    }

    // ---- 基准尺寸（1280×720 下的像素；运行时再乘 uiScale）----

    /** 一个槽位（快捷栏 / 背包）的边长。 */
    public static final int SLOT_SIZE = 20;
    /** 槽位之间的间隙。 */
    public static final int SLOT_GAP = 2;
    /** 面板内边距。 */
    public static final int PANEL_PAD = 8;
    /** 面板标题条高度。 */
    public static final int PANEL_TITLE_H = 16;
    /** 提示框内边距。 */
    public static final int TOOLTIP_PAD = 4;
    /** 正文文字缩放（基准 1 = 12×12 点阵原尺寸）。 */
    public static final int TEXT_SCALE = 1;
    /** 标签文字缩放（基准 2）。 */
    public static final int LABEL_SCALE = 2;

    /** 基准帧缓冲高度：1280×720。 */
    private static final float REFERENCE_HEIGHT = 720f;

    /**
     * 由帧缓冲高度推导整数 uiScale（沿用 {@code HudRenderer} 与 {@code MenuLayout} 的同一口径）。
     *
     * <p>高 DPI / 全屏 4K 下 {@code fbHeight} 更大，比例取整后得到 2、3 等整数缩放，
     * 保证 1 像素的线不会被压成看不见。最小为 1。
     */
    public static int uiScale(int framebufferHeight) {
        return Math.max(1, Math.round(framebufferHeight / REFERENCE_HEIGHT));
    }

    /** 基准像素 × uiScale → 实际像素。 */
    public static int px(int base, int scale) {
        return base * scale;
    }

    /** 三类目标分辨率（供布局测试遍历）。 */
    public enum Resolution {
        R1280x720(1280, 720),
        R1920x1080(1920, 1080),
        R2560x1440(2560, 1440);

        private final int width;
        private final int height;

        Resolution(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }
    }
}

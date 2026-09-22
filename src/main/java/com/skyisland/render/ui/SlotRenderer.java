package com.skyisland.render.ui;

/**
 * 槽位渲染器（M2.2）：快捷栏与背包界面<b>共用</b>的格子绘制。
 *
 * <p><b>为什么抽出来：</b>原 {@code HudRenderer.drawHotbar} 手写了槽位绘制，
 * 而 M2.2 的 36 格背包若再写一遍，两处的底色 / 边框 / 选中高亮 / 数量标签
 * 必然慢慢漂移。这里把"画一个格子"收敛成单一实现，HUD 与背包都调
 * {@link #drawSlot(UiBatch, float, float, float, int, int, int, SlotState)}。
 *
 * <p><b>绘制顺序：</b>底色 → 边框 → 物品图标 → 数量标签。
 * 选中态沿用原快捷栏的<b>外扩描边</b>（边框画在槽位外一圈），让"当前选中"更扎眼；
 * 其余状态边框贴在槽位边缘。这些数字与 {@code HudRenderer.drawHotbar} 原写法一致，
 * 保证迁移后快捷栏观感不变。
 */
public final class SlotRenderer {

    private SlotRenderer() {
    }

    /** 槽位状态：决定边框颜色（见 {@link UiTheme}）。 */
    public enum SlotState {
        NORMAL,
        HOVER,
        SELECTED
    }

    /** 图标在槽位内的内缩（像素，基准口径，再乘 uiScale）。与原快捷栏 {@code 3 * uiScale} 一致。 */
    private static final int ICON_INSET = 3;

    /**
     * 画一个槽位：底色 + 边框 + 物品图标（若有）+ 数量标签（若 &gt; 1）。
     *
     * @param size  槽位边长（已含 uiScale 的实际像素）
     * @param scale uiScale（用于内缩、边框粗细、文字大小）
     */
    public static void drawSlot(UiBatch batch, float x, float y, float size, int scale,
                                int itemRuntimeId, int count, SlotState state) {
        batch.rect(x, y, size, size, UiTheme.SLOT_BG);

        if (state == SlotState.SELECTED) {
            // 外扩描边：比槽位大一圈，沿用原快捷栏选中态
            batch.rectOutline(x - scale, y - scale, size + 2 * scale, size + 2 * scale,
                    scale, UiTheme.SLOT_SELECTED);
        } else {
            float[] border = (state == SlotState.HOVER) ? UiTheme.SLOT_HOVER : UiTheme.SLOT_BORDER;
            batch.rectOutline(x, y, size, size, scale, border);
        }

        if (itemRuntimeId > 0 && count > 0) {
            float inner = size - 2 * ICON_INSET * scale;
            ItemIcon.draw(batch, itemRuntimeId, x + ICON_INSET * scale, y + ICON_INSET * scale,
                    inner, scale);
            if (count > 1) {
                drawCount(batch, x, y, size, scale, count);
            }
        }
    }

    /**
     * 跟随光标的那堆物品（"手上拿着的"）。图标以光标为中心，半透明着色让其浮在界面之上。
     *
     * @param mouseX 光标 x（帧缓冲像素）
     * @param mouseY 光标 y
     * @param size   图标边长（已含 uiScale）
     */
    public static void drawHeldStack(UiBatch batch, int itemRuntimeId, int count,
                                     float mouseX, float mouseY, float size, int scale) {
        if (itemRuntimeId <= 0) {
            return;
        }
        float bx = mouseX - size / 2f;
        float by = mouseY - size / 2f;
        batch.rect(bx, by, size, size, UiTheme.CURSOR_STACK_TINT);
        ItemIcon.draw(batch, itemRuntimeId, bx, by, size, scale);
        if (count > 1) {
            drawCount(batch, bx, by, size, scale, count);
        }
    }

    /** 右下角白色数量标签（沿用原快捷栏排版：右对齐、贴在槽底边上）。 */
    private static void drawCount(UiBatch batch, float x, float y, float size, int scale, int count) {
        String label = String.valueOf(count);
        float textWidth = BitmapFont.textWidth(label, scale);
        batch.text(x + size - textWidth - 2 * scale,
                y + size - BitmapFont.lineHeight(scale),
                label, scale, 1f, 1f, 1f, 1f);
    }
}

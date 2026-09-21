package com.skyisland.render.ui;

import com.skyisland.render.shader.ShaderProgram;
import com.skyisland.ui.MenuEntry;
import com.skyisland.ui.MenuScreen;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单绘制（M1.5 规格第 2/3/5/9 条）：主菜单封面 / 暂停菜单 / 设置界面 / 等待输入与冲突提示。
 *
 * <p><b>为什么单独一个渲染器而不是塞进 {@link HudRenderer}：</b>
 * 两者的"状态契约"不同 —— HUD 是<b>游戏内叠加层</b>（准星、快捷栏、读数），
 * 菜单是<b>模态层</b>（有选中项、有输入焦点、会遮住游戏输入）。
 * 混在一起之后，"暂停时该不该画准星"这种问题就会变成同一个类里的 if 分支，
 * 而且两套尺寸体系（HUD 的 uiScale、菜单的列宽）会互相污染。
 *
 * <p><b>文字全部是 ASCII：</b>字模 {@link BitmapFont} 覆盖 ASCII 32–126。
 * 写中文不会报错、会渲染成 {@code ?} —— 因此这里所有文案都是英文，
 * 并把该限制记入报告的技术债（CJK 字库属资源管线，PRD 把它排在 TextureArray 之后）。
 *
 * <p><b>状态管理约定与 HUD 一致：</b>进入时自行关闭深度测试与剔除、开启混合，
 * 离开时恢复为"深度测试开、混合关"。不依赖上一帧的残留状态。
 */
public final class MenuRenderer {

    // ---- 配色（集中在顶部，避免各处硬编码导致观感不一致）----
    private static final float[] DIM = {0.02f, 0.03f, 0.05f, 0.66f};
    private static final float[] PANEL_BG = {0.03f, 0.04f, 0.06f, 0.62f};
    private static final float[] TITLE = {0.95f, 0.97f, 1.00f, 1.00f};
    private static final float[] SUBTITLE = {0.62f, 0.68f, 0.76f, 1.00f};
    private static final float[] HEADER = {0.55f, 0.78f, 0.98f, 1.00f};
    private static final float[] ITEM = {0.86f, 0.89f, 0.93f, 1.00f};
    private static final float[] ITEM_SELECTED = {1.00f, 1.00f, 1.00f, 1.00f};
    private static final float[] VALUE = {0.72f, 0.86f, 1.00f, 1.00f};
    private static final float[] VALUE_CUSTOM = {1.00f, 0.85f, 0.45f, 1.00f};
    private static final float[] INFO = {0.52f, 0.57f, 0.64f, 1.00f};
    private static final float[] SELECT_BAR = {0.24f, 0.50f, 0.78f, 0.55f};
    private static final float[] SELECT_EDGE = {0.60f, 0.84f, 1.00f, 0.95f};
    private static final float[] SEPARATOR = {0.30f, 0.36f, 0.44f, 0.80f};
    private static final float[] DIALOG_BG = {0.06f, 0.07f, 0.10f, 0.94f};
    private static final float[] DIALOG_EDGE = {0.72f, 0.82f, 0.95f, 1.00f};
    private static final float[] VERSION_COLOR = {0.45f, 0.50f, 0.58f, 1.00f};

    private final UiBatch batch = new UiBatch();

    public void init() {
        batch.init();
    }

    // ============================================================ 主入口

    /**
     * 绘制一整个菜单屏。
     *
     * @param versionLine 右下角版本行（ASCII）
     * @param footerHint  底部操作提示（ASCII，可为空）
     * @param overlayText 覆盖层文案（等待输入 / 冲突确认）；为空则不画
     * @param overlayDialog {@code true} = 画成带边框的对话框（冲突确认），
     *                      {@code false} = 画成单行提示（等待输入）
     */
    public void render(ShaderProgram uiShader, MenuScreen screen, MenuLayout layout,
                       String versionLine, String footerHint, String overlayText,
                       boolean overlayDialog, int fbWidth, int fbHeight) {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        batch.begin(fbWidth, fbHeight);

        int scale = layout.uiScale();

        // ---- 1) 背景压暗：让游戏画面退到背景，同时保留"这是一个真实场景"的观感 ----
        batch.rect(0, 0, fbWidth, fbHeight, DIM);

        // ---- 2) 标题与副标题 ----
        drawCentered(screen.title(), layout.titleY(), titleScale(layout), scale, TITLE, fbWidth);
        if (screen.subtitle() != null && !screen.subtitle().isEmpty()) {
            drawCentered(screen.subtitle(), layout.subtitleY(), 2, scale, SUBTITLE, fbWidth);
        }

        // ---- 3) 菜单列底板 ----
        int rowsBottom = layout.rowCount() == 0
                ? layout.firstRowY()
                : layout.rowY(layout.rowCount() - 1) + layout.rowHeight(layout.rowCount() - 1);
        batch.rect(layout.columnX() - 8 * scale, layout.firstRowY() - 8 * scale,
                layout.columnWidth() + 16 * scale, rowsBottom - layout.firstRowY() + 16 * scale,
                PANEL_BG);

        // ---- 4) 各行 ----
        List<MenuEntry> entries = screen.entries();
        for (int i = 0; i < entries.size(); i++) {
            drawRow(screen, entries.get(i), i, layout);
        }

        // ---- 5) 底部提示与版本 ----
        if (footerHint != null && !footerHint.isEmpty()) {
            drawCentered(footerHint, layout.hintY(), 1, scale, INFO, fbWidth);
        }
        if (versionLine != null && !versionLine.isEmpty()) {
            float w = BitmapFont.textWidth(versionLine, 1 * scale);
            batch.text(fbWidth - w - 8 * scale, layout.versionY(), versionLine, 1 * scale,
                    VERSION_COLOR[0], VERSION_COLOR[1], VERSION_COLOR[2], VERSION_COLOR[3]);
        }

        // ---- 6) 覆盖层（等待输入 / 冲突确认）----
        if (overlayText != null && !overlayText.isEmpty()) {
            drawOverlay(overlayText, overlayDialog, layout, fbWidth, fbHeight);
        }

        batch.flush(uiShader);

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    // ============================================================ 行

    private void drawRow(MenuScreen screen, MenuEntry entry, int index, MenuLayout layout) {
        int scale = layout.uiScale();
        int y = layout.rowY(index);
        int rowH = layout.rowHeight(index);
        int textY = y + (rowH - BitmapFont.lineHeight(2 * scale)) / 2;

        switch (entry.kind()) {
            case SPACER -> {
                // 只占位
            }
            case HEADER -> {
                batch.text(layout.labelX(), y + 4 * scale, entry.label(), 2 * scale,
                        HEADER[0], HEADER[1], HEADER[2], HEADER[3]);
                batch.rect(layout.columnX() + 6 * scale, y + rowH - 4 * scale,
                        layout.columnWidth() - 12 * scale, Math.max(1, scale), SEPARATOR);
            }
            case INFO -> batch.text(layout.labelX(), y + 2 * scale, entry.label(), 1 * scale,
                    INFO[0], INFO[1], INFO[2], INFO[3]);
            default -> {
                boolean selected = index == screen.selectedIndex();
                if (selected) {
                    batch.rect(layout.columnX() + 2 * scale, y, layout.columnWidth() - 4 * scale,
                            rowH, SELECT_BAR);
                    batch.rect(layout.columnX() + 2 * scale, y, 3 * scale, rowH, SELECT_EDGE);
                }
                float[] labelColor = selected ? ITEM_SELECTED : ITEM;
                batch.text(layout.labelX(), textY, entry.label(), 2 * scale,
                        labelColor[0], labelColor[1], labelColor[2], labelColor[3]);
                drawValue(entry, layout, textY, scale, selected);
            }
        }
    }

    /** 右对齐画数值。数值以 {@code "(none)"} / {@code "ON"} / {@code "OFF"} 等形式出现。 */
    private void drawValue(MenuEntry entry, MenuLayout layout, int textY, int scale, boolean selected) {
        String value = entry.value();
        if (value == null || value.isEmpty() || entry.kind() == MenuEntry.Kind.ACTION) {
            return;
        }
        float[] color = VALUE;
        if (entry.kind() == MenuEntry.Kind.BINDING) {
            // 未绑定 / 等待中 / 冲突：用告警色，让"这行现在不可用"一眼可见
            if ("(none)".equals(value) || value.startsWith("(") || value.contains("->")) {
                color = VALUE_CUSTOM;
            }
        } else if (entry.kind() == MenuEntry.Kind.SLIDER
                && ("0".equals(value) || "100".equals(value))) {
            // 滑杆到端点时也提示一下（避免"按了没反应"的错觉）
            color = VALUE_CUSTOM;
        }
        if (selected) {
            color = new float[]{VALUE[0], VALUE[1], VALUE[2], 1.0f};
        }
        float w = BitmapFont.textWidth(value, 2 * scale);
        batch.text(layout.valueRightX() - w, textY, value, 2 * scale,
                color[0], color[1], color[2], color[3]);
    }

    // ============================================================ 覆盖层

    /**
     * 覆盖层：等待输入（单行提示）与冲突确认（带边框的对话框）。
     *
     * <p>它<b>不</b>做成一个"新的菜单屏"：覆盖层出现时，背后的设置界面仍然可见、
     * 仍然保留选中项 —— 用户能看清"我正在改的是哪一行"。
     * 换成新屏会把上下文清掉，而这正是冲突确认最需要的信息。
     */
    private void drawOverlay(String text, boolean dialog, MenuLayout layout,
                             int fbWidth, int fbHeight) {
        int scale = layout.uiScale();
        int textScale = dialog ? 2 : 2;
        int maxChars = Math.max(16, (int) ((fbWidth * 0.78f) / (6.0f * textScale * scale)));
        List<String> lines = wrap(text, maxChars);

        int lineH = BitmapFont.lineHeight(textScale * scale) + 4 * scale;
        int boxW = 0;
        for (String line : lines) {
            boxW = Math.max(boxW, BitmapFont.textWidth(line, textScale * scale));
        }
        int padX = 18 * scale;
        int padY = 14 * scale;
        int boxH = lines.size() * lineH + padY * 2 - 4 * scale;
        int boxX = (fbWidth - (boxW + padX * 2)) / 2;
        int boxY = (fbHeight - boxH) / 2;

        if (dialog) {
            batch.rect(boxX, boxY, boxW + padX * 2, boxH, DIALOG_BG);
            batch.rectOutline(boxX, boxY, boxW + padX * 2, boxH, Math.max(1, scale), DIALOG_EDGE);
        } else {
            batch.rect(boxX, boxY, boxW + padX * 2, boxH, PANEL_BG);
        }

        int y = boxY + padY;
        for (String line : lines) {
            batch.text((fbWidth - BitmapFont.textWidth(line, textScale * scale)) / 2f, y,
                    line, textScale * scale,
                    TITLE[0], TITLE[1], TITLE[2], TITLE[3]);
            y += lineH;
        }
    }

    /**
     * 按空格折行。仅用于提示与对话框文案 —— 它们可能很长
     * （例如 {@code "MOUSE_LEFT is already bound to Mine / Attack. Replace?"}），
     * 在窗口较窄或 uiScale 较大时不分行就会画出屏幕外。
     */
    static List<String> wrap(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > maxChars) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    // ============================================================ 工具

    private void drawCentered(String text, int y, int textScale, int scale, float[] color,
                              int fbWidth) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int effective = textScale * scale;
        float w = BitmapFont.textWidth(text, effective);
        batch.text((fbWidth - w) / 2f, y, text, effective,
                color[0], color[1], color[2], color[3]);
    }

    private static int titleScale(MenuLayout layout) {
        return layout.style() == MenuLayout.Style.COVER ? 5 : 3;
    }

    public int menuQuadCount() {
        return batch.quadCount();
    }

    public void dispose() {
        batch.dispose();
    }
}

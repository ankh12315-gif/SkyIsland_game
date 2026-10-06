package com.skyisland.render.ui;

import com.skyisland.craft.CraftingPanel;
import com.skyisland.player.Inventory;
import com.skyisland.player.ItemStack;
import com.skyisland.render.shader.ShaderProgram;
import com.skyisland.ui.Localization;
import org.lwjgl.opengl.GL11;

/**
 * 背包界面的绘制（M2.2）。
 *
 * <h2>为什么单独一个渲染器，而不是塞进 {@link HudRenderer}</h2>
 * 与 {@link MenuRenderer} 从 HUD 里分出来的同一个理由：状态契约不同。
 * HUD 是<b>游戏内叠加层</b>（没有输入焦点、不吞事件）；背包是<b>模态层</b>
 * （有悬停格、有手上拿着的那一堆、会吞掉游戏输入）。
 * 混在一起之后"开着背包时该不该画准星"就会变成同一个类里的 if 分支，
 * 而两套尺寸体系（HUD 的 uiScale、背包的面板布局）会互相污染。
 *
 * <h2>为什么它同时是"布局的持有者"</h2>
 * 输入层要做命中判定，必须拿到与绘制<b>完全相同</b>的那份布局。
 * 若两边各算一次 {@link InventoryLayout#compute}，漂移就只是时间问题
 * （典型症状：悬停高亮在第 5 格，点击却动了第 12 格）。
 * 因此布局由本渲染器缓存并以 {@link #ensureLayout} 暴露，输入层只读不重算。
 *
 * <h2>状态管理约定</h2>
 * 与 {@link HudRenderer}/{@link MenuRenderer} 一致：进入时自行关闭深度测试与剔除、
 * 开启混合，离开时恢复为"深度测试开、混合关"。不依赖上一帧的残留状态。
 *
 * <h2>文案</h2>
 * 一律走 {@link Localization}（PRD §6.7：禁止在 Java UI 代码里散落中文字面量）。
 */
public final class InventoryRenderer {

    private final UiBatch batch = new UiBatch();

    private InventoryLayout layout;

    public void init() {
        batch.init();
    }

    /**
     * 取得（必要时重算）当前帧缓冲尺寸下的布局。
     *
     * <p><b>输入层必须走这里取布局，不许自己 {@code compute}</b> —— 见类注释。
     *
     * @param craftRowCount 合成栏行数；{@code 0} = 本帧不画合成栏。
     *                      <b>它必须参与"布局是否要重算"的判据</b>：
     *                      否则从"没有合成栏"切到"有合成栏"时缓存不会失效，
     *                      命中判定会拿着旧布局去接新画面的点击。
     */
    public InventoryLayout ensureLayout(int fbWidth, int fbHeight, int craftRowCount) {
        if (layout == null || layout.fbWidth() != fbWidth || layout.fbHeight() != fbHeight
                || layout.craftRowCount() != craftRowCount) {
            layout = InventoryLayout.compute(fbWidth, fbHeight, craftRowCount);
        }
        return layout;
    }

    /** 上一帧使用的布局；尚未渲染过时返回 {@code null}。 */
    public InventoryLayout layout() {
        return layout;
    }

    public void render(ShaderProgram uiShader, InventoryRenderModel model,
                       int fbWidth, int fbHeight) {
        if (model == null || !model.visible) {
            return;
        }
        int craftRowCount = model.craftingPanel == null ? 0 : model.craftingPanel.size();
        InventoryLayout l = ensureLayout(fbWidth, fbHeight, craftRowCount);
        int scale = l.uiScale();

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        batch.begin(fbWidth, fbHeight);

        // ---- 1) 压暗 ----
        // M2.2：压暗已<b>移出本类</b>，改由 Renderer#renderModalDim 作为独立 pass
        // 在 HUD 之前绘制。原来写在这里的后果是：它跟着面板一起排在 HUD 之后，
        // 于是把先画好的生命条与通知压暗了 66%（实测满心 229,51,61 → 81,23,29），
        // 而 vitalsVisible 的产品决定是"开背包也要看得见自己在挨打"。
        // 不要把它搬回来 —— 谁在这里画全屏压暗，谁就会再次压暗 HUD。

        // ---- 2) 面板底板 ----
        batch.rect(l.panelX(), l.panelY(), l.panelWidth(), l.panelHeight(), UiTheme.PANEL_BG);
        batch.rectOutline(l.panelX(), l.panelY(), l.panelWidth(), l.panelHeight(),
                scale, UiTheme.PANEL_HEADER);

        // ---- 3) 标题 ----
        String title = Localization.text(Localization.INV_TITLE);
        int titleScale = UiMetrics.px(UiMetrics.LABEL_SCALE, scale);
        batch.text((fbWidth - BitmapFont.textWidth(title, titleScale)) / 2f, l.titleY(),
                title, titleScale,
                UiTheme.TITLE[0], UiTheme.TITLE[1], UiTheme.TITLE[2], UiTheme.TITLE[3]);

        // ---- 4) 36 个格子 ----
        Inventory inv = model.inventory;
        for (int i = 0; i < Inventory.SLOT_COUNT; i++) {
            ItemStack stack = inv == null ? ItemStack.EMPTY : inv.slot(i);
            SlotRenderer.SlotState state = SlotRenderer.SlotState.NORMAL;
            if (i >= Inventory.HOTBAR_OFFSET
                    && (i - Inventory.HOTBAR_OFFSET) == model.selectedHotbarSlot) {
                state = SlotRenderer.SlotState.SELECTED;
            } else if (i == model.hoverSlot) {
                state = SlotRenderer.SlotState.HOVER;
            }
            SlotRenderer.drawSlot(batch, l.slotX(i), l.slotY(i), l.slotSize(), scale,
                    stack.itemRuntimeId(), stack.count(), state);
        }

        // ---- 5) 主背包与快捷栏之间的分隔线 ----
        batch.rect(l.panelX() + l.uiScale() * UiMetrics.PANEL_PAD, l.separatorY(),
                l.panelWidth() - 2 * l.uiScale() * UiMetrics.PANEL_PAD, scale, UiTheme.SEPARATOR);

        // ---- 6) 右侧合成栏 ----
        if (l.hasCraftColumn() && model.craftingPanel != null && inv != null) {
            drawCraftingColumn(model, l, scale);
        }

        // ---- 7) 悬停格的 tooltip ----
        if (inv != null && model.hoverSlot >= 0 && model.hoverSlot < Inventory.SLOT_COUNT) {
            ItemStack hovered = inv.slot(model.hoverSlot);
            if (!hovered.isEmpty()) {
                drawTooltip(hovered, l, model, fbWidth, fbHeight, scale);
            }
        }

        // ---- 7) 手上拿着的那一堆（跟随光标，最后画，压在所有格子之上）----
        if (inv != null && model.hasPointer()) {
            ItemStack cursor = inv.cursorStack();
            if (!cursor.isEmpty()) {
                SlotRenderer.drawHeldStack(batch, cursor.itemRuntimeId(), cursor.count(),
                        (float) model.mouseX, (float) model.mouseY, l.slotSize(), scale);
            }
        }

        // ---- 8) 底部操作提示 ----
        // 这里必须给<b>操作</b>提示，而不只是"怎么关"。
        // 玩家第一次打开背包时，唯一的操作知识来源就是这一行 ——
        // 左键怎么取放、Shift+左键怎么搬运，不写在这里就没有别的地方可写。
        // （M2.2 收尾时这里原本只画"E 或 Esc 关闭背包"，而写好了完整操作的
        //   INV_CURSOR_HINT 一直没人画：界面能用，但只能靠猜。）
        String hint = Localization.text(Localization.INV_CURSOR_HINT);
        int hintScale = UiMetrics.px(UiMetrics.TEXT_SCALE, scale);
        batch.text((fbWidth - BitmapFont.textWidth(hint, hintScale)) / 2f,
                l.panelY() + l.panelHeight() + UiMetrics.px(6, scale),
                hint, hintScale,
                UiTheme.INFO[0], UiTheme.INFO[1], UiTheme.INFO[2], UiTheme.INFO[3]);

        batch.flush(uiShader);

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /**
     * 右侧合成栏：一行一条配方，每行 = 产物图标 + 中文名 ×产出数量 + 材料需求 + 状态 / [合成]。
     *
     * <p><b>为什么"整行"就是按钮，而不画一个独立的小按钮：</b>
     * 12×12 点阵下，一条配方的文字已经占满整行；再切出一个几十像素宽的按钮，
     * 玩家要精确点中它就得瞄准。整行可点让"看得懂的那一行"与"点得到的那一行"
     * 是同一块区域 —— 而 {@code [合成]} 那两个字仍然画出来，作为"这里能点"的明示。
     *
     * <p><b>状态必须看得见：</b>缺料时画的是 {@code 缺少 铁锭 ×3} 而不是灰掉的
     * {@code [合成]}。裁定第 7 条禁止"点了按钮没反应"，而"没反应"最常见的成因
     * 恰恰是玩家不知道这一行现在不可合成 —— 把原因写在原地，反应就提前发生了。
     */
    private void drawCraftingColumn(InventoryRenderModel model, InventoryLayout l, int scale) {
        int textScale = UiMetrics.px(UiMetrics.TEXT_SCALE, scale);
        int lineH = BitmapFont.lineHeight(textScale);

        String title = Localization.text(Localization.CRAFT_SECTION_TITLE);
        int titleScale = UiMetrics.px(UiMetrics.LABEL_SCALE, scale);
        batch.text(l.craftX(), l.craftTitleY(), title, titleScale,
                UiTheme.TITLE[0], UiTheme.TITLE[1], UiTheme.TITLE[2], UiTheme.TITLE[3]);

        CraftingPanel panel = model.craftingPanel;
        Inventory inv = model.inventory;
        for (int i = 0; i < panel.size(); i++) {
            CraftingPanel.Row row = panel.row(i);
            int rowY = l.craftRowY(i);

            // 悬停行给一条底衬：它是"我会点中这一行"的唯一反馈
            if (i == model.hoverCraftRow) {
                batch.rect(l.craftX() - UiMetrics.px(2, scale), rowY,
                        l.craftWidth() + UiMetrics.px(4, scale), l.craftRowHeight(),
                        UiTheme.SLOT_HOVER);
            }

            // ① 产物图标（与背包格子同一套 SlotRenderer，于是"栏里的图标"与
            //    "合出来拿在手上的图标"是同一个图样）
            int iconSize = l.slotSize();
            SlotRenderer.drawSlot(batch, l.craftX(), rowY + (l.craftRowHeight() - iconSize) / 2,
                    iconSize, scale,
                    CraftingPanel.outputRuntimeId(row.recipe()), row.recipe().outputCount(),
                    SlotRenderer.SlotState.NORMAL);

            // ② 文字：产物名 ×数量 + 材料需求 + 状态/按钮
            int textX = l.craftX() + iconSize + UiMetrics.px(4, scale);
            int textY = rowY + (l.craftRowHeight() - lineH) / 2;

            String label = CraftingPanel.outputName(row.recipe()) + " ×" + row.recipe().outputCount();
            String materials = panel.ingredientText(row, inv);
            String status = panel.statusText(row);

            float x = textX;
            batch.text(x, textY, label, textScale,
                    UiTheme.TEXT_PRIMARY[0], UiTheme.TEXT_PRIMARY[1],
                    UiTheme.TEXT_PRIMARY[2], UiTheme.TEXT_PRIMARY[3]);
            x += BitmapFont.textWidth(label, textScale) + UiMetrics.px(6, scale);
            batch.text(x, textY, materials, textScale,
                    UiTheme.TEXT_DIM[0], UiTheme.TEXT_DIM[1],
                    UiTheme.TEXT_DIM[2], UiTheme.TEXT_DIM[3]);
            x += BitmapFont.textWidth(materials, textScale) + UiMetrics.px(6, scale);

            float[] color = row.craftable() ? UiTheme.VALUE : UiTheme.TEXT_WARN;
            batch.text(x, textY, status, textScale, color[0], color[1], color[2], color[3]);
        }
    }

    /** 悬停格的信息框：中文物品名 + 数量 + 堆叠上限。
     *
     * <p><b>为什么显示"上限"：</b>玩家判断"这一格还能不能合并"时，
     * 需要同时看到当前数量与上限。只给数量时，"为什么我拿着的 30 个放不进这格"
     * 是一个没有答案的问题（真实答案：这格已有 40 个、上限 64）。
     */
    private void drawTooltip(ItemStack stack, InventoryLayout l, InventoryRenderModel model,
                             int fbWidth, int fbHeight, int scale) {
        int textScale = UiMetrics.px(UiMetrics.TEXT_SCALE, scale);
        String name = Localization.displayName(stack.item().id());
        String countLine = Localization.text(Localization.INV_TOOLTIP_COUNT, stack.count());
        String maxLine = Localization.text(Localization.INV_TOOLTIP_MAX_STACK, stack.maxStack());

        int lineH = BitmapFont.lineHeight(textScale) + UiMetrics.px(2, scale);
        int width = 0;
        width = Math.max(width, BitmapFont.textWidth(name, textScale));
        width = Math.max(width, BitmapFont.textWidth(countLine, textScale));
        width = Math.max(width, BitmapFont.textWidth(maxLine, textScale));

        int padX = UiMetrics.px(UiMetrics.TOOLTIP_PAD, scale);
        int padY = UiMetrics.px(UiMetrics.TOOLTIP_PAD, scale);
        int boxW = width + 2 * padX;
        int boxH = 3 * lineH + 2 * padY - UiMetrics.px(2, scale);

        // 贴在光标右下；贴不下就翻到左侧 / 上侧，避免画出屏幕外
        float bx = (float) model.mouseX + UiMetrics.px(10, scale);
        float by = (float) model.mouseY + UiMetrics.px(10, scale);
        if (bx + boxW > fbWidth) {
            bx = (float) model.mouseX - boxW - UiMetrics.px(10, scale);
        }
        if (by + boxH > fbHeight) {
            by = (float) model.mouseY - boxH - UiMetrics.px(10, scale);
        }
        bx = Math.max(0, bx);
        by = Math.max(0, by);

        batch.rect(bx, by, boxW, boxH, UiTheme.TOOLTIP_BG);
        batch.rectOutline(bx, by, boxW, boxH, scale, UiTheme.TOOLTIP_BORDER);

        float ty = by + padY;
        batch.text(bx + padX, ty, name, textScale,
                UiTheme.TOOLTIP_TEXT[0], UiTheme.TOOLTIP_TEXT[1],
                UiTheme.TOOLTIP_TEXT[2], UiTheme.TOOLTIP_TEXT[3]);
        ty += lineH;
        batch.text(bx + padX, ty, countLine, textScale,
                UiTheme.TEXT_DIM[0], UiTheme.TEXT_DIM[1], UiTheme.TEXT_DIM[2], UiTheme.TEXT_DIM[3]);
        ty += lineH;
        batch.text(bx + padX, ty, maxLine, textScale,
                UiTheme.TEXT_DIM[0], UiTheme.TEXT_DIM[1], UiTheme.TEXT_DIM[2], UiTheme.TEXT_DIM[3]);
    }

    public int quadCount() {
        return batch.quadCount();
    }

    public void dispose() {
        batch.dispose();
    }
}

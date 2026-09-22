package com.skyisland.render.ui;

import com.skyisland.render.shader.ShaderProgram;
import com.skyisland.ui.Localization;
import static com.skyisland.render.ui.UiTheme.*;
import org.lwjgl.opengl.GL11;

import java.util.Locale;

/**
 * HUD 绘制（M1 指令 B15）：准星 / 快捷栏 / 性能与坐标读数 / F3 详细 overlay。
 *
 * <p><b>为什么 M1 只做 debug HUD：</b>TECH_DESIGN_v0.1.1 §S′ 的裁定 ——
 * 正式 UI 屏属于后续里程碑；M1 需要的是"能看见自己站在哪、手里有什么、
 * 瞄准了什么、帧率是多少"。M1.5 补齐了<b>菜单层</b>（主菜单 / 暂停 / 设置），
 * 但那部分在 {@link MenuRenderer} 里，本类仍然只负责游戏内的叠加显示 ——
 * 两者的状态契约不同（见 MenuRenderer 的说明）。
 *
 * <p><b>M1.5 起本类的显示受界面状态约束：</b>{@code showGameplayHud} 为假时
 * 不画准星与快捷栏；{@code showFps} 为假时不画帧率行。
 *
 * <p><b>像素口径与缩放：</b>所有尺寸都以"1280×720 下 1 像素"为基准，
 * 再乘以 {@code uiScale = round(fbHeight / 720)}。这样在高 DPI 或全屏 4K 下
 * HUD 不会变成看不见的细线 —— 若直接用绝对像素，1 像素的准星在 2880 宽的帧缓冲上
 * 只有 0.03% 的宽度。
 *
 * <p><b>状态管理约定：</b>本类进入时自己关闭深度测试与剔除、开启混合，
 * 离开时把"深度测试开、混合关"恢复回去。<u>不依赖上一帧的残留状态</u>，
 * 也不把"HUD 状态"泄漏给世界渲染 pass —— "HUD 被地形挡住"（文字时有时无）
 * 是这类 bug 里最难解释的一种。
 *
 * <p><b>文字（M2 修订）：</b>M1.5 曾把全部 HUD 文案改成 ASCII，理由是"点阵字模只覆盖
 * ASCII 32–126，中文会渲染成 {@code ?}"。PRD v0.3.2 §6.7 把这条口径<b>明确否掉</b>：
 * 「不得因为当前字体渲染能力而把产品规格降级为英文」——字体覆盖不足是<u>实现缺口</u>，
 * 不是改产品规格的理由。M2 因此补上了 CJK 点阵字库（{@code CjkFont}），
 * 玩家可见文案回归简体中文，且全部来自 {@link Localization}
 * （PRD 禁止在 UI 代码里散落中文字面量）。
 *
 * <p><b>仍然保持 ASCII 的只有调试 overlay</b>：F3 的字段名是开发者读的，
 * 翻译它只会让日志与代码的对应关系变模糊。
 */
public final class HudRenderer {

    // 配色已集中到 UiTheme（M2.2）：本类只引用 UiTheme.*，不再持有任何 float[] 色值。
    // 缩放口径集中到 UiMetrics（uiScale）。

    // ---- M2：生命与枪械（尺寸常量，非配色）----
    /** 一颗心的基准尺寸（9×8 单位，见 {@link #drawHeart}）。 */
    private static final int HEART_SIZE = 9;
    /** 生命低值预警阈值（PRD 6.1：生命 ≤ 6 时闪烁）。 */
    private static final int LOW_HEALTH_THRESHOLD = 6;

    private final UiBatch batch = new UiBatch();

    private int uiScale = 1;

    public void init() {
        batch.init();
    }

    public void render(HudModel model, ShaderProgram uiShader, int fbWidth, int fbHeight) {
        uiScale = UiMetrics.uiScale(fbHeight);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        batch.begin(fbWidth, fbHeight);

        // ---- 玩法层：只在 PLAYING 显示（M1.5）----
        // 准星与快捷栏是"我正在操作这个世界"的直接提示。放在暂停/菜单下继续显示，
        // 会让人误以为攻击键仍然有效 —— 而暂停期间它们是明确被冻结的。
        if (model.showGameplayHud) {
            drawCrosshair(model, fbWidth, fbHeight);
            drawHotbar(model, fbWidth, fbHeight);
            drawMiningBar(model, fbWidth, fbHeight);
            drawWeaponPanel(model, fbWidth, fbHeight);
        }
        // M2.2：生命条从"玩法层"里拿出来，单独由 showVitals 控制。
        // 开背包时世界仍在跑（怪物照常走过来），生命必须还在屏幕上 ——
        // 但它不再属于"我正在操作世界"那一层，所以不能跟准星一起被关掉。
        if (model.showVitals) {
            drawHealthBar(model, fbWidth, fbHeight);
        }
        // 死亡遮罩放在玩法层之外：玩家倒下时它是<b>唯一</b>该看的东西，
        // 而"死亡提示也在 PLAYING 里显示"这件事不能依赖 gameplayHud 的开关。
        if (model.dead) {
            drawDeathOverlay(model, fbWidth, fbHeight);
        }
        drawEventMessage(model, fbWidth, fbHeight);

        if (model.showDebugOverlay) {
            drawDebugOverlay(model, fbWidth, fbHeight);
        } else {
            drawCompactReadout(model);
        }

        batch.flush(uiShader);

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    // ============================================================ 准星

    /**
     * 准星（PRD 6.1：普通十字；瞄准时密集十字；对准可交互方块时高亮）。
     *
     * <p><b>"密集十字"为什么是"更短 + 留中点"而不是"更粗"：</b>
     * 准星的全部意义是"指出屏幕正中心那一个点"。加粗会把那个点<u>盖住</u>，
     * 于是瞄准反而比不瞄准更看不清落点。缩短臂长 + 补一个中点，视觉上更聚焦，
     * 也保留了"中央那个像素就是命中点"的可读性。
     */
    private void drawCrosshair(HudModel model, int fbWidth, int fbHeight) {
        int centerX = fbWidth / 2;
        int centerY = fbHeight / 2;
        // 高亮：指着可命中的东西时换色（PRD 6.1「对准可交互方块时高亮」）
        float[] color = model.targetInteractable ? UiTheme.CROSSHAIR_TARGET : UiTheme.CROSSHAIR;
        int thickness = 1 * uiScale;
        // M2.1：命中标记的剩余强度（0..1）。它来自 CombatFxModel 的同一份读数，
        // HUD 不自己计时 —— 否则"命中的那一刻"在两条时间线上会各说各话。
        double marker = Math.max(0.0, Math.min(1.0, model.hitMarker));
        int arm = model.aiming ? 3 * uiScale : 6 * uiScale;

        if (model.aiming) {
            // 四臂紧贴中心（无缺口）+ 中点：视觉上"收拢"
            batch.rect(centerX - arm, centerY, arm, thickness, marker > 0 ? UiTheme.CROSSHAIR_HIT : color);
            batch.rect(centerX + thickness, centerY, arm, thickness, marker > 0 ? UiTheme.CROSSHAIR_HIT : color);
            batch.rect(centerX, centerY - arm, thickness, arm, marker > 0 ? UiTheme.CROSSHAIR_HIT : color);
            batch.rect(centerX, centerY + thickness, thickness, arm, marker > 0 ? UiTheme.CROSSHAIR_HIT : color);
            batch.rect(centerX, centerY, thickness, thickness, marker > 0 ? UiTheme.CROSSHAIR_HIT : color);
        } else {
            // 中间留出缺口，准星不遮挡真正瞄准的那个点
            batch.rect(centerX - arm, centerY, arm - thickness, thickness, marker > 0 ? UiTheme.CROSSHAIR_HIT : color);
            batch.rect(centerX + thickness, centerY, arm - thickness, thickness, marker > 0 ? UiTheme.CROSSHAIR_HIT : color);
            batch.rect(centerX, centerY - arm, thickness, arm - thickness, marker > 0 ? UiTheme.CROSSHAIR_HIT : color);
            batch.rect(centerX, centerY + thickness, thickness, arm - thickness, marker > 0 ? UiTheme.CROSSHAIR_HIT : color);
        }

        if (marker > 0) {
            drawHitMarker(centerX, centerY, thickness, arm, marker);
        }
    }

    /**
     * M2.1：命中标记 —— 在准星四角长出四道斜向短臂（X 形），长度随剩余强度收缩。
     *
     * <p><b>为什么能<u>举证</u>：</b>这个形状完全由 {@link HudModel#hitMarker} 这一个
     * 数值驱动，而那个数值是 {@code CombatFxModel.hitMarker01()} 的镜像，
     * 后者在无 GL 环境的单测里被逐帧断言（spawn 后为 1、0.19 秒后为 0）。
     * 因此"看到标记"与"状态机真的走到了那一步"是同一件事的两面，
     * 不存在"只有肉眼能判别"的部分。
     *
     * <p><b>斜臂为什么用阶梯方点拼：</b>{@link UiBatch} 只有轴对齐矩形，没有线段图元；
     * 为了一道 1 像素宽的斜线去新增一套线段发射器，不如用 {@code max(1, thickness)}
     * 的小方块逐级铺出来 —— 12 行点阵 UI 的像素口径本来就是方的，
     * 阶梯在这种尺度下正是"像素该有的样子"。
     */
    private void drawHitMarker(int centerX, int centerY, int thickness, int baseArm, double strength) {
        int step = Math.max(1, thickness);
        int reach = (int) Math.round((baseArm + 4 * uiScale) * strength);
        for (int d = 1; d <= reach; d += step) {
            // 四个象限各一枚小方块，合起来是 X 的四条臂
            batch.rect(centerX + d, centerY + d, step, step, UiTheme.CROSSHAIR_HIT);
            batch.rect(centerX - d - step, centerY + d, step, step, UiTheme.CROSSHAIR_HIT);
            batch.rect(centerX + d, centerY - d - step, step, step, UiTheme.CROSSHAIR_HIT);
            batch.rect(centerX - d - step, centerY - d - step, step, step, UiTheme.CROSSHAIR_HIT);
        }
    }

    // ============================================================ 生命条（M2）

    /**
     * 生命条（PRD 6.1：屏幕底部偏左、快捷栏上方；10 颗心，每颗 2 点；
     * 生命 ≤ 6 时闪烁）。
     *
     * <p><b>为什么要用心形而不是一根进度条：</b>PRD 明确写的是"10 颗心"。
     * 心形还能表达"半颗心"这件事 —— 1 点生命对应半颗心，
     * 而进度条画到 1/20 时玩家读不出"还剩半颗心"。
     *
     * <p>心形用若干矩形拼出来（不引入纹理）：顶部两个圆角凸起、中部横条、底部收尖。
     * 与整个项目的"零外部资产点阵"取向一致。
     */
    private void drawHealthBar(HudModel model, int fbWidth, int fbHeight) {
        int scale = uiScale;
        int size = HEART_SIZE * scale;
        int gap = 2 * scale;
        int count = 10;                                  // PRD 6.1：10 颗心
        int totalWidth = count * size + (count - 1) * gap;
        int x0 = 6 * scale;                              // "底部偏左"
        int y = fbHeight - 10 * scale - 20 * scale - 12 * scale - size;   // 快捷栏上方

        // 生命 ≤ 6 时闪烁（PRD 6.1「生命低值预警」）：4 Hz 方波。
        // 用"帧时间"而不是"帧计数"：60 Hz 与 3000 Hz 下闪烁频率必须一样，
        // 否则高频机器上会闪到看不清（这正是 §C.4′ 一类"帧率影响语义"的坑）。
        boolean blinkOn = ((int) (model.uiTimeSeconds * 4.0)) % 2 == 0;
        if (model.health <= LOW_HEALTH_THRESHOLD && !blinkOn) {
            return;
        }

        int health = Math.max(0, Math.min(model.maxHealth, model.health));
        for (int i = 0; i < count; i++) {
            int points = health - i * 2;                 // 每颗心 2 点生命
            int x = x0 + i * (size + gap);
            drawHeart(x, y, scale, 0.0f, UiTheme.HEART_EMPTY);
            if (points >= 2) {
                drawHeart(x, y, scale, 9.0f, UiTheme.HEART_FULL);
            } else if (points == 1) {
                drawHeart(x, y, scale, 4.5f, UiTheme.HEART_FULL);   // 半颗心
            }
        }
    }

    /**
     * 画一颗心。
     *
     * @param maxColumns 只画前 {@code maxColumns} 个横向单位（9 = 整颗、4.5 = 半颗）
     */
    private void drawHeart(int x, int y, int scale, float maxColumns, float[] color) {
        // 形状按 9×8 单位定义（单位 = scale 像素），逐行给 [起点, 宽度]
        heartRow(x, y, 0, scale, maxColumns, color, 1, 3, 5, 3);
        heartRow(x, y, 2, scale, maxColumns, color, 0, 9);
        heartRow(x, y, 4, scale, maxColumns, color, 1, 7);
        heartRow(x, y, 6, scale, maxColumns, color, 2, 5);
        heartRow(x, y, 7, scale, maxColumns, color, 4, 1);
    }

    /** 一条心形横带：{@code pairs} 是若干 (起点, 宽度) 单位对。 */
    private void heartRow(int x, int y, int row, int scale, float maxColumns, float[] color,
                          int... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            int start = pairs[i];
            int width = pairs[i + 1];
            // 按 maxColumns 裁剪（半颗心用）
            float end = Math.min(start + width, maxColumns);
            float visible = end - start;
            if (visible <= 0) {
                continue;
            }
            batch.rect(x + start * scale, y + row * scale,
                    visible * scale, scale, color);
        }
    }

    // ============================================================ 死亡遮罩（M2）

    /**
     * 死亡提示（PRD 6.7「死亡文案」：「你倒下了」+「物品已掉落」）。
     *
     * <p>文案全部来自 {@code Localization}（PRD 6.7 硬性禁止在 UI 代码里散落中文字面量）。
     * 倒计时用的是 {@code Player.deathTimer} 的剩余量，因此屏幕上读到的秒数与
     * 真实的重生时刻是同一个来源 —— 自己另起一个计时器迟早会和它对不上。
     */
    private void drawDeathOverlay(HudModel model, int fbWidth, int fbHeight) {
        int scale = Math.max(1, uiScale);
        batch.rect(0, 0, fbWidth, fbHeight, 0.35f, 0.02f, 0.02f, 0.35f);

        String title = Localization.text(Localization.DEATH_TITLE);
        float titleWidth = BitmapFont.textWidth(title, 2 * scale);
        batch.text((fbWidth - titleWidth) / 2f, fbHeight / 2f - 12 * scale,
                title, 2 * scale, 1f, 0.92f, 0.92f, 1f);

        String detail = Localization.text(Localization.DEATH_ITEMS_DROPPED)
                + String.format(Locale.ROOT, "  %.1f", model.deathTimerLeft);
        float detailWidth = BitmapFont.textWidth(detail, scale);
        batch.text((fbWidth - detailWidth) / 2f, fbHeight / 2f + 6 * scale,
                detail, scale, UiTheme.TEXT_DIM);
    }

    // ============================================================ 快捷栏

    private void drawHotbar(HudModel model, int fbWidth, int fbHeight) {
        int slot = UiMetrics.SLOT_SIZE * uiScale;
        int gap = UiMetrics.SLOT_GAP * uiScale;
        int count = model.hotbarRuntimeId.length;
        int totalWidth = count * slot + (count - 1) * gap;
        int startX = (fbWidth - totalWidth) / 2;
        int y = fbHeight - slot - 10 * uiScale;

        for (int i = 0; i < count; i++) {
            int x = startX + i * (slot + gap);
            int itemId = model.hotbarItemRuntimeId[i];
            int itemCount = model.hotbarCount[i];
            SlotRenderer.SlotState state = (i == model.hotbarSelected)
                    ? SlotRenderer.SlotState.SELECTED
                    : SlotRenderer.SlotState.NORMAL;
            SlotRenderer.drawSlot(batch, x, y, slot, uiScale, itemId, itemCount, state);
        }
    }

    /**
     * 快捷栏图标的颜色。
     *
     * <p><b>M2.2 迁移：</b>原实现在这里查方块表 / 按 {@code ItemKind} 分色，
     * 现直接委托 {@link ItemIcon#baseColor(int)} —— 物品图标的"主色"来源收拢到
     * {@code ItemIcon}，HUD 与背包共用同一份口径，不再各写一遍。
     */
    private float[] iconColor(int blockRuntimeId, int itemRuntimeId) {
        return ItemIcon.baseColor(itemRuntimeId);
    }

    // ============================================================ 枪械面板（M2）

    /**
     * 右下角的枪械面板（PRD 6.1：弹药计数在屏幕右下角，当前枪械名在其上方）。
     *
     * <p>显示格式按 PRD 6.7「数值展示」：「弹匣 / 后备」，如 {@code 12 / 36}。
     * 只在手持枪械时出现 —— 空手时右下角什么都没有，而不是显示 {@code 0 / 0}：
     * 后者会让玩家以为自己把弹药打光了。
     *
     * <p>换弹进度条画在弹药下方：换弹是 1.2 秒的等待，没有进度反馈时
     * 玩家唯一的判断依据是"能不能开枪"，那要等到结束才知道。
     */
    private void drawWeaponPanel(HudModel model, int fbWidth, int fbHeight) {
        if (!model.holdingGun) {
            return;
        }
        int scale = uiScale;
        int right = fbWidth - 8 * scale;
        // 与快捷栏保持同一水平带：快捷栏底边 = fbHeight - 10*scale，槽高 20*scale
        int y = fbHeight - 10 * scale - 20 * scale - 10 * scale;

        // 弹药（先算宽度，因为枪名要跟它右对齐）
        // M2.1：无限后备走另一条格式串（``12 / ∞``）。两条都来自 Localization，
        // 这里不拼接任何文案 —— PRD 6.7 禁止 UI 代码里散落字面量。
        String ammo = model.reserveInfinite
                ? Localization.text(Localization.HUD_AMMO_FORMAT_INFINITE, model.magazineAmmo)
                : Localization.text(Localization.HUD_AMMO_FORMAT,
                        model.magazineAmmo, model.reserveAmmo);
        float ammoWidth = BitmapFont.textWidth(ammo, 2 * scale);
        batch.text(right - ammoWidth, y, ammo, 2 * scale,
                UiTheme.TEXT_PRIMARY);

        // 枪械名（简体中文显示名，PRD 6.1「当前枪械名」）
        String gunName = model.gunDisplayName;
        float nameWidth = BitmapFont.textWidth(gunName, scale);
        batch.text(right - nameWidth, y - BitmapFont.lineHeight(scale) - 4 * scale,
                gunName, scale, UiTheme.TEXT_DIM);

        // 换弹进度
        if (model.reloading) {
            int barWidth = 60 * scale;
            int barHeight = 4 * scale;
            int barY = y + BitmapFont.lineHeight(2 * scale) + 3 * scale;
            batch.rect(right - barWidth, barY, barWidth, barHeight, 0.18f, 0.18f, 0.20f, 1f);
            float progress = (float) Math.max(0, Math.min(1, model.reloadProgress));
            batch.rect(right - barWidth, barY, barWidth * progress, barHeight,
                    UiTheme.TEXT_WARN);
            String label = Localization.text(Localization.HUD_RELOADING);
            float labelWidth = BitmapFont.textWidth(label, scale);
            batch.text(right - labelWidth, barY + barHeight + 2 * scale,
                    label, scale, UiTheme.TEXT_WARN);
        }
    }

    // ============================================================ 挖掘进度

    private void drawMiningBar(HudModel model, int fbWidth, int fbHeight) {
        if (!model.showMiningBar()) {
            return;
        }
        int barWidth = 140 * uiScale;
        int barHeight = 5 * uiScale;
        int x = (fbWidth - barWidth) / 2;
        int y = fbHeight - 56 * uiScale;

        batch.rect(x - uiScale, y - uiScale, barWidth + 2 * uiScale, barHeight + 2 * uiScale,
                0f, 0f, 0f, 0.70f);
        batch.rect(x, y, barWidth, barHeight, 0.18f, 0.18f, 0.20f, 1f);
        float progress = (float) Math.max(0, Math.min(1, model.miningProgress));
        batch.rect(x, y, barWidth * progress, barHeight, 0.95f, 0.90f, 0.55f, 1f);

        /*
         * M2 字体垂直统一时的漏网点（本行之前用的是 BitmapFont.textHeight）。
         *
         * textHeight 是 ASCII 字形的像素高（7 行），lineHeight 才是一行的排版高度（12 行）。
         * 现在的约定是"文本的 y = 行盒顶部"，而 ASCII 字形由 BitmapFont 居中到行盒的第 3..9 行，
         * 所以字形实际占据 y+3×uiScale .. y+10×uiScale。
         * 沿用 7 行当行高会把标签底部算到 y+uiScale，而进度条外边框顶边在 y-uiScale ——
         * 两者相叠 2×uiScale 像素，表现是标签压在黑边框上。
         * 这里与该文件 drawWeaponPanel 的口径对齐：整行贴在进度条上方，留 3×uiScale 间隙。
         */
        String label = "Mining " + model.miningTargetId + " " + Math.round(progress * 100) + "%";
        int labelY = y - 4 * uiScale - BitmapFont.lineHeight(uiScale);
        batch.text((fbWidth - BitmapFont.textWidth(label, uiScale)) / 2f,
                labelY,
                label, uiScale, UiTheme.TEXT_PRIMARY);
    }

    // ============================================================ 事件提示

    private void drawEventMessage(HudModel model, int fbWidth, int fbHeight) {
        if (model.eventMessage == null || model.eventMessage.isEmpty() || model.eventSecondsLeft <= 0) {
            return;
        }
        float alpha = (float) Math.min(1.0, model.eventSecondsLeft / 0.8);   // 最后 0.8 秒淡出
        float width = BitmapFont.textWidth(model.eventMessage, uiScale);
        float x = (fbWidth - width) / 2f;
        float y = fbHeight - 84 * uiScale;
        // ★ 这里刻意展开成四个 float 而不是传 float[]：背景的 alpha 要随淡出变化，
        //   而 UiTheme 里的色板是常量数组（改它就等于改全局配色）。
        //   数组重载只适用于"整块用同一个色"的场景（如 F3 overlay）。
        batch.textWithBackground(x, y, model.eventMessage, uiScale, 4 * uiScale,
                0.02f, 0.03f, 0.05f, 0.70f * alpha,
                UiTheme.TEXT_PRIMARY[0], UiTheme.TEXT_PRIMARY[1],
                UiTheme.TEXT_PRIMARY[2], alpha);
    }

    // ============================================================ 读数

    /**
     * 常驻紧凑读数。
     *
     * <p>用 4 行短行而不是一行长行：一行超过约 60 个字符后，人就失去"扫一眼找到数字"的能力；
     * 分行之后每行的语义固定（性能 / 坐标 / 区块 / 瞄准），位置稳定，可以用眼角读。
     *
     * <p><b>M1.5 的两处改动：</b>
     * <ol>
     *   <li>FPS 行受设置项 {@code showFps} 控制（默认关闭）。关闭时坐标那几行仍然显示 ——
     *       M1 的验收闭环要读它们，而"帧率"是调试信息；</li>
     *   <li>文案全部改为 ASCII。点阵字模只覆盖 ASCII 32–126，
     *       M1 里的中文文案实际渲染成了一串 {@code '?'}，属于"看起来像字体坏了"的显示缺陷
     *       （M1.5 报告把它记为发现并修复的 Bug）。</li>
     * </ol>
     */
    private void drawCompactReadout(HudModel model) {
        int scale = uiScale;
        int lineHeight = BitmapFont.lineHeight(scale) + 2 * scale;
        int x = 6 * scale;
        int y = 6 * scale;

        if (model.showFps) {
            y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_PRIMARY, String.format(
                    "FPS %.0f  TPS %.1f  %.2f ms  P99 %.2f ms",
                    model.fps, model.tps, model.meanFrameMs, model.p99FrameMs));
        }
        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_PRIMARY, String.format(
                "XYZ %.2f %.2f %.2f  Yaw/Pitch %.1f/%.1f%s",
                model.playerX, model.playerY, model.playerZ, model.yaw, model.pitch,
                model.onGround ? "  onGround" : ""));
        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_PRIMARY, String.format(
                "CHUNK %d %d  LOCAL %d %d  BLOCK %d %d %d",
                model.chunkX, model.chunkZ, model.localX, model.localZ,
                model.blockX, model.blockY, model.blockZ));
        textLine(x, y, scale, lineHeight, UiTheme.TEXT_PRIMARY, String.format(
                "AIM %s [%s] %.2f m   Broke %d  Placed %d  Deaths %d",
                model.targetBlockId, model.targetFace, model.targetDistance,
                model.blocksBroken, model.blocksPlaced, model.deaths));
    }

    private int textLine(int x, int y, int scale, int lineHeight, float[] color, String text) {
        batch.textWithBackground(x, y, text, scale, 2 * scale,
                UiTheme.PANEL_BG, color);
        return y + lineHeight;
    }

    /**
     * F3 详细 overlay。
     *
     * <p>刻意把"性能/世界/玩家/渲染"分成四段并各留空行 —— 这不是排版偏好：
     * 调试时人是<u>按问题去找段</u>的（"帧时间异常 → 看性能段"），
     * 分段之后不必逐行读；全平铺的二十几行只能从头看。
     *
     * <p><b>文案为 ASCII</b>（同 {@link #drawCompactReadout} 的理由）：
     * M1 写在这里的中文段落标题实测渲染成了 {@code ==== ?? ====}。
     */
    private void drawDebugOverlay(HudModel model, int fbWidth, int fbHeight) {
        int scale = uiScale;
        int lineHeight = BitmapFont.lineHeight(scale) + 2 * scale;
        int x = 6 * scale;
        int y = 6 * scale;

        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_PRIMARY, "==== PERF ====");
        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_DIM, String.format(
                "FPS %.2f  TPS %.2f  frame %.3f ms  P99 %.3f ms  max %.3f ms",
                model.fps, model.tps, model.meanFrameMs, model.p99FrameMs, model.maxFrameMs));
        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_DIM, String.format(
                "logic steps/frame %.3f  frames>50ms %d  clamped %d",
                model.fps > 1e-9 ? model.tps / model.fps : 0, model.spikesOver50Ms, model.clampedFrames));
        y += lineHeight;

        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_PRIMARY, "==== WORLD ====");
        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_DIM, String.format(
                "chunks %d  meshes %d  %s",
                model.loadedChunks, model.meshCount,
                model.loadedChunks == model.meshCount ? "all ready" : "rebuild pending"));
        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_DIM, String.format(
                "rebuild queue %d  peak %d  total %d  mean %.3f ms",
                model.pendingMeshRebuilds, model.meshQueueHighWaterMark,
                model.meshBuildCount, model.meanMeshBuildMs));
        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_DIM, String.format(
                "broke %d  placed %d  rejected %d  neighbor marks %d  emitters %d",
                model.breakCount, model.placeCount, model.rejectedCount,
                model.neighborMarkCount, model.emissiveSourceCount));
        y += lineHeight;

        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_PRIMARY, "==== PLAYER ====");
        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_DIM, String.format(
                "pos %.3f %.3f %.3f  yaw/pitch %.2f / %.2f  vy %.3f",
                model.playerX, model.playerY, model.playerZ, model.yaw, model.pitch, model.velocityY));
        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_DIM, String.format(
                "block %d %d %d  chunk %d %d  local %d %d  onGround %s",
                model.blockX, model.blockY, model.blockZ, model.chunkX, model.chunkZ,
                model.localX, model.localZ, model.onGround));
        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_DIM, String.format(
                "deaths %d  broke %d  placed %d  hotbar slot %d",
                model.deaths, model.blocksBroken, model.blocksPlaced, model.hotbarSelected + 1));
        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_DIM, String.format(
                "aim %s  face=%s  dist=%.3f  %s",
                model.targetBlockId, model.targetFace, model.targetDistance,
                model.mining ? ("mining " + Math.round(model.miningProgress * 100) + "%") : "idle"));
        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_WARN, "place result: " + model.lastPlacementMessage);
        y += lineHeight;

        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_PRIMARY, "==== RENDER ====");
        y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_DIM, String.format(
                "draw calls %d  triangles %d  frustum-culled %d  fb %d×%d  uiScale %d",
                model.drawCalls, model.renderedTriangles, model.culledChunks,
                fbWidth, fbHeight, uiScale));

        for (String extra : model.extraDebugLines) {
            y = textLine(x, y, scale, lineHeight, UiTheme.TEXT_DIM, extra);
        }
    }

    public int uiScale() {
        return uiScale;
    }

    public int hudQuadCount() {
        return batch.quadCount();
    }

    public void dispose() {
        batch.dispose();
    }
}

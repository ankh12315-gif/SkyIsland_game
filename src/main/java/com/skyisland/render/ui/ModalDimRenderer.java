package com.skyisland.render.ui;

import com.skyisland.render.shader.ShaderProgram;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;

/**
 * 模态压暗层（M2.2）：一层铺满帧缓冲的半透明色块，把游戏画面压到背景里，
 * 让背包 / 菜单面板成为视觉焦点。
 *
 * <p><b>为什么它必须是一个独立的 pass，而不是"面板渲染的第一步"：</b>
 * 压暗此前写在 {@code MenuRenderer} 与 {@code InventoryRenderer} 内部的第一行，
 * 于是它的<b>绘制时机就是面板的绘制时机</b> —— 而面板在 HUD <u>之后</u>绘制。
 * 结果是：压暗把先画好的 HUD 一起压暗了。
 *
 * <p>实测代价（像素证据，1280×720 背包态）：生命条满心本应是
 * {@code rgb(229,51,61)}（= {@link UiTheme#HEART_FULL}），实测 {@code rgb(81,23,29)}
 * —— 恰好是 0.34 倍，而 {@code 1 − DIM.alpha(0.66) = 0.34}。
 * 同一张截图里的地形背景也被压暗了同样的比例，因此这不是生命条自己的问题，
 * 而是"压暗层盖在了谁上面"的问题。
 *
 * <p>这与 {@code UiState#vitalsVisible()} 的产品决定直接冲突：
 * 那条决定写的是"开背包时生命条仍要显示，因为玩家不能因为开了背包就看不见自己在挨打"。
 * 压暗 66% 之后它确实还在画，但已经不承担这个职责了 ——
 * <b>"画了"和"看得见"是两件事</b>，这正是 M2.1 在怪物身上付过学费的那条教训。
 *
 * <p><b>正确的层序：</b>世界 → 手持物 → <b>压暗</b> → HUD（生命条 / 通知）→ 面板。
 * 于是压暗只压世界，生命条与通知以原色浮在压暗之上。
 * 层序由 {@code SkyIslandGame#render} 显式安排，本类只负责画那一层。
 */
public final class ModalDimRenderer {

    private final UiBatch batch = new UiBatch();

    public void init() {
        batch.init();
    }

    /**
     * 压暗层的几何：{@code [x, y, width, height]}，单位为帧缓冲像素。
     *
     * <p>抽成纯函数是为了能<b>无 GL 断言</b>："必须铺满整个帧缓冲"这件事
     * 有 falsifiable 的判据（少一个像素就会在边上留一条亮边），
     * 而不必靠人盯着截图看。
     */
    public static float[] coverage(int fbWidth, int fbHeight) {
        return new float[]{0f, 0f, Math.max(1, fbWidth), Math.max(1, fbHeight)};
    }

    public void render(ShaderProgram uiShader, float[] rgba, int fbWidth, int fbHeight) {
        float[] quad = coverage(fbWidth, fbHeight);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        batch.begin(fbWidth, fbHeight);
        batch.rect(quad[0], quad[1], quad[2], quad[3], rgba);
        batch.flush(uiShader);

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    public void dispose() {
        batch.dispose();
    }
}

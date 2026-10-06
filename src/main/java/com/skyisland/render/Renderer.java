package com.skyisland.render;

import com.skyisland.render.mesh.ChunkRenderer;
import com.skyisland.render.mesh.CrackOverlay;
import com.skyisland.render.shader.ShaderProgram;
import com.skyisland.render.ui.HudModel;
import com.skyisland.render.ui.HudRenderer;
import com.skyisland.render.ui.InventoryRenderModel;
import com.skyisland.render.ui.InventoryRenderer;
import com.skyisland.render.ui.MenuLayout;
import com.skyisland.render.ui.MenuRenderer;
import com.skyisland.render.viewmodel.ViewmodelModel;
import com.skyisland.render.viewmodel.ViewmodelRenderer;
import com.skyisland.entity.Entity;
import com.skyisland.render.fx.CombatFxModel;
import com.skyisland.render.fx.CombatFxRenderer;
import com.skyisland.player.Camera;
import com.skyisland.player.Player;
import com.skyisland.ui.MenuScreen;
import com.skyisland.util.Log;
import com.skyisland.world.World;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * 渲染总入口：清屏 → 世界 pass → HUD pass（TECH_DESIGN §G.7 的 pass 顺序）。
 *
 * <p><b>为什么要有一个总入口而不是让游戏主类直接调各渲染器：</b>
 * pass 顺序与 GL 状态的设置/恢复必须只有<u>一个</u>执行点。分散之后必然出现
 * "某人开了混合忘了关，于是世界渲染开始半透明"这类跨模块的隐式耦合 ——
 * 症状出现在 A 模块，原因在 B 模块。
 *
 * <p><b>本类负责的状态切换（每帧固定重设，不依赖上一帧残留）：</b>
 * <ol>
 *   <li>清屏：颜色 + 深度，清屏色固定为天空色（M1 无昼夜，TECH_DESIGN_v0.1.1 §S′）；</li>
 *   <li>世界 pass：开深度测试、开背面剔除（{@link ChunkRenderer} 内再按 pass 调整）；</li>
 *   <li>HUD pass：关深度测试、关剔除、开混合；</li>
 *   <li>结束：恢复"深度测试开、混合关"，交给下一帧。</li>
 * </ol>
 */
public final class Renderer {

    /**
     * 天空清屏色。偏亮的蓝 —— M1 的测试世界是一个悬浮在空中的平台，
     * 清屏色就是"天空"，因此它同时承担"能看出自己没有站到地形外面"的作用。
     */
    public static final float SKY_R = 0.46f;
    public static final float SKY_G = 0.63f;
    public static final float SKY_B = 0.86f;

    private final ChunkRenderer chunkRenderer = new ChunkRenderer();
    private final CrackOverlay crackOverlay = new CrackOverlay();
    private final com.skyisland.render.entity.EntityRenderer entityRenderer =
            new com.skyisland.render.entity.EntityRenderer();
    private final CombatFxRenderer combatFxRenderer = new CombatFxRenderer();
    private final ViewmodelRenderer viewmodelRenderer = new ViewmodelRenderer();
    private final HudRenderer hudRenderer = new HudRenderer();
    private final MenuRenderer menuRenderer = new MenuRenderer();
    private final InventoryRenderer inventoryRenderer = new InventoryRenderer();
    /** M2.2：模态压暗层。它是独立 pass，因此必须排在 HUD 之前（见类注释）。 */
    private final com.skyisland.render.ui.ModalDimRenderer modalDimRenderer =
            new com.skyisland.render.ui.ModalDimRenderer();
    private final Frustum frustum = new Frustum();

    /**
     * 方块纹理数组（M4-S3）。
     *
     * <p><b>为什么由 Renderer 持有而不是 ChunkRenderer</b>：
     * 它是<b>整帧唯一</b>的资源，被六个渲染器共用。
     * 若挂在 ChunkRenderer 下，实体 / 粒子 / 裂纹 / 手持物就得反向依赖区块渲染器
     * 才能拿到纹理 —— 而它们与地形没有任何归属关系。
     */
    private final com.skyisland.render.mesh.BlockTextureAtlas blockAtlas =
            new com.skyisland.render.mesh.BlockTextureAtlas();

    private ShaderProgram voxelShader;
    private ShaderProgram uiShader;

    private int framebufferWidth = 1280;
    private int framebufferHeight = 720;

    // ============================================================ 生命周期

    public void init(int fbWidth, int fbHeight) {
        resize(fbWidth, fbHeight);
        voxelShader = ShaderProgram.fromResources("voxel",
                "shaders/voxel.vert", "shaders/voxel.frag");
        uiShader = ShaderProgram.fromResources("ui",
                "shaders/ui.vert", "shaders/ui.frag");
        hudRenderer.init();
        menuRenderer.init();
        inventoryRenderer.init();
        modalDimRenderer.init();
        crackOverlay.init();
        entityRenderer.init();
        combatFxRenderer.init();
        viewmodelRenderer.init();
        // ★ 纹理数组必须在所有渲染器 init 之后创建：
        // 它们 init 时会glVertexAttribPointer，而 VAO 记录的是
        // "当前绑定的纹理单元 + 采样器 uniform"，顺序反了会绑到错的纹理上。
        blockAtlas.create();

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glFrontFace(GL11.GL_CCW);
        GL11.glDisable(GL11.GL_BLEND);
        Log.info("[Renderer] 渲染器已就绪（帧缓冲 %d×%d，pass 顺序：清屏 → 世界 → 手持物 → 压暗 → HUD → 背包 → 菜单）",
                fbWidth, fbHeight);
    }

    /** 帧缓冲尺寸变化时必须调用 —— 否则投影矩阵仍是旧宽高比，画面会被拉长。 */
    public void resize(int fbWidth, int fbHeight) {
        this.framebufferWidth = Math.max(1, fbWidth);
        this.framebufferHeight = Math.max(1, fbHeight);
    }

    // ============================================================ 每帧

    public int framebufferWidth() {
        return framebufferWidth;
    }

    public int framebufferHeight() {
        return framebufferHeight;
    }

    /** 清屏。必须在本帧任何绘制之前调用。 */
    public void clear() {
        GL11.glViewport(0, 0, framebufferWidth, framebufferHeight);
        GL11.glClearColor(SKY_R, SKY_G, SKY_B, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    /**
     * 世界 pass。
     *
     * <p>视锥矩阵在此更新（每帧一次），随后交给 {@link ChunkRenderer} 做剔除与两趟绘制。
     *
     * <p><b>裂纹叠加层在区块之后、同一 pass 内绘制</b>：它需要深度缓冲已经装好
     * 真实几何（才能被前面的方块正确遮挡），也需要相机矩阵 —— 两者都只在世界 pass 里成立。
     * 放到 HUD pass 会被当成屏幕空间图元，放到下一帧更不可能。因此这一步的顺序
     * 由本类统一安排，与 {@link #renderHud} / {@link #renderMenu} 的分工原则一致。
     *
     * <p>player 允许为 null：菜单期间没有"当前挖掘目标"，此时叠加层自己跳过。
     *
     * <p><b>M2 的两处补充：</b>
     * <ul>
     *   <li><b>实体在区块之后、裂纹之前绘制</b>：实体是真实几何，必须被地形正确遮挡；
     *       而裂纹是贴在方块面上的覆盖层，画在最后才不会把实体"糊"上一层黑色；</li>
     *   <li>{@code entities} 允许为 null 或空：菜单 / 自测期间没有实体，此时跳过。
     *       用"空集合等价于不画"而不是"必须传一个非空列表"，是为了让调用方
     *       不必为了满足签名而构造一个假列表。</li>
     * </ul>
     *
     * @param player   挖掘反馈的数据源；null 表示不画裂纹
     * @param entities 本帧要绘制的实体；null / 空表示没有实体
     * @param fx       战斗表现（粒子 + 曳光）的状态；null 表示不画
     */
    public void renderWorld(World world, Camera camera, Player player,
                            List<Entity> entities, CombatFxModel fx) {
        camera.updateProjection(framebufferWidth, framebufferHeight);
        frustum.update(camera.projectionMatrix(), camera.viewMatrix());
        //★ 绑定纹理数组（整帧唯一资源，六个渲染器共用同一份绑定状态）。
        blockAtlas.bind();
        // ★★ 采样器单元必须在**着色器已绑定之后**告知—— 这是 S4 修掉的一个真 bug。
        //
        //   glUniform1i 写的是「**当前程序**」的 uniform。若在 shader.bind() 之前调用，
        //   它写的是上一个还处于当前状态的程序（首帧是 0 号，即默认 program），
        //   voxelShader 的 uBlockAtlas 仍是驱动给的默认值 0。
        //   于是片元去 **纹理单元 0** 采样 —— 那里什么都没有，
        //   texture() 返回 (0,0,0,1)：**方块全黑，但不报任何错**。
        //
        //   为什么 S3 的 1211 个测试没抓到：单测只断言「源码里有 setInt 这行」，
        //   而这行**确实在**、也确实写了正确的单元号 —— 只是写到了错的程序上。
        //   「顺序错了」与「没写」在源码扫描里长得一模一样。
        //
        //   因此这里 bind 一次，之后各pass 复用同一份绑定状态。
        voxelShader.bind();
        voxelShader.setInt("uBlockAtlas",
                com.skyisland.render.mesh.BlockTextureAtlas.TEXTURE_UNIT);
        chunkRenderer.render(world, camera, voxelShader, frustum);
        entityRenderer.render(voxelShader, camera, entities);
        combatFxRenderer.render(voxelShader, camera, fx);
        crackOverlay.render(voxelShader, camera, player);
    }

    /** 上一帧实际绘制的裂纹段数（自测断言用；0 表示没有在画裂纹）。 */
    public int crackSegments() {
        return crackOverlay.lastSegments();
    }

    /** 累计有裂纹绘制的帧数。 */
    public long crackDrawCount() {
        return crackOverlay.drawCount();
    }

    /**
     * 第一人称手持物 pass（M2.1）。
     *
     * <p><b>位置是硬性的：世界之后、HUD 之前。</b>
     * <ul>
     *   <li>在世界之后 —— 它要复用 {@code voxel} 着色器并<b>独占深度缓冲</b>
     *       （自己清一次深度，见 {@link ViewmodelRenderer} 的类注释）。
     *       放到世界之前，清掉的就还是上一帧的深度，等于白清；</li>
     *   <li>在 HUD 之前 —— 手持物是"世界里的东西"（虽然在视图空间），
     *       准星必须盖在它上面：准星指出的是屏幕正中心那一个点，
     *       被自己的枪盖住的话，瞄准就失去了意义。HUD pass 关着深度测试，
     *       因此它天然盖在所有 3D 之上。</li>
     * </ul>
     *
     * @param model 可为 null 或 {@code visible == false}：此时不画，也不碰 GL 状态
     */
    public void renderViewmodel(ViewmodelModel model) {
        // 手持物与地形共用 voxelShader 与同一张纹理数组 —— 绑定状态仍在。
        // 但采样器 uniform 是**逐程序**存的：换 program 必须重新 bind 后再告知，
        // 否则会退回默认单元 0（详见 renderWorld 里那段注释）。
        blockAtlas.bind();
        voxelShader.bind();
        voxelShader.setInt("uBlockAtlas",
                com.skyisland.render.mesh.BlockTextureAtlas.TEXTURE_UNIT);
        viewmodelRenderer.render(voxelShader, model, framebufferWidth, framebufferHeight);
    }

    /** HUD pass（正交屏幕空间，与相机无关）。 */
    public void renderHud(HudModel model) {
        hudRenderer.render(model, uiShader, framebufferWidth, framebufferHeight);
    }

    /**
     * 模态压暗 pass（M2.2）。<b>调用顺序：世界 → 手持物 → 压暗 → HUD → 背包 → 菜单。</b>
     *
     * <p><b>为什么压暗必须排在 HUD 之前：</b>它此前是"面板渲染的第一步"，
     * 而面板在 HUD 之后，于是压暗顺手把生命条与通知一起压暗了 66%
     * （实测满心 {@code rgb(229,51,61)} → {@code rgb(81,23,29)}，恰为 0.34 倍）。
     * 这与 {@code UiState#vitalsVisible()} 的产品决定冲突：那条决定要求
     * "开背包时生命条仍要显示，玩家不能因为开了背包就看不见自己在挨打"。
     *
     * <p>压暗只该压<b>世界</b>。分开之后，生命条与通知以原色浮在压暗之上，
     * 而面板仍然盖在全部之上（模态性不变）。
     */
    public void renderModalDim() {
        modalDimRenderer.render(uiShader, com.skyisland.render.ui.UiTheme.DIM,
                framebufferWidth, framebufferHeight);
    }

    /**
     * 菜单 pass（M1.5）：在 HUD 之后绘制，因此菜单永远盖在 HUD 与世界之上。
     *
     * <p>顺序是硬性的：菜单是模态层，被 HUD 盖住的话"暂停了却看不见菜单"就只是
     * 谁先画的问题。而这一步由本类统一安排，调用方不需要知道 pass 顺序
     * （同 {@link #renderWorld} / {@link #renderHud} 的分工原则）。
     */
    public void renderMenu(MenuScreen screen, MenuLayout layout,
                           String versionLine, String footerHint,
                           String overlayText, boolean overlayDialog) {
        menuRenderer.render(uiShader, screen, layout, versionLine, footerHint,
                overlayText, overlayDialog, framebufferWidth, framebufferHeight);
    }

    /**
     * 背包 pass（M2.2）。<b>调用顺序：世界 → 手持物 → HUD → 背包 → 菜单。</b>
     *
     * <p><b>为什么背包必须盖住 HUD：</b>背包是模态层。若 HUD 画在它之上，
     * 开着背包时准星仍会浮在面板中央 —— 而那一刻鼠标正在点格子、不是在瞄准，
     * 玩家会合理地以为"我还能开枪"（实际上 INVENTORY 状态下攻击是被吞掉的）。
     *
     * <p><b>为什么背包与菜单的先后无所谓：</b>二者互斥 ——
     * {@code UiStateMachine} 判定"从背包打开设置"非法，而从背包暂停会直接转到
     * PAUSED（那一刻已经不是 INVENTORY 了）。因此两者不会同帧出现。
     * 这里把背包放在菜单之前，只是为了让"背包 → 暂停"这条路径上
     * 菜单永远是最上面那一层。
     *
     * @param model 可为 null 或 {@code visible == false}：此时不画，也不碰 GL 状态
     */
    public void renderInventory(InventoryRenderModel model) {
        inventoryRenderer.render(uiShader, model, framebufferWidth, framebufferHeight);
    }

    /**
     * 输入层取背包布局的<b>唯一</b>入口。
     *
     * <p><b>为什么必须经过这里，而不是让输入层自己 {@code InventoryLayout.compute}：</b>
     * 命中判定与绘制必须读<u>同一份</u>布局。两边各算一次的话，
     * "悬停高亮在第 5 格、点击却动了第 12 格"就只是两份坐标何时漂移的问题。
     */
    public InventoryRenderer inventoryRenderer() {
        return inventoryRenderer;
    }

    /** 限量消费区块网格重建队列。返回实际重建的区块数。 */
    public int processMeshRebuilds(World world) {
        return chunkRenderer.processRebuildQueue(world);
    }

    // ============================================================ 统计与释放

    public ChunkRenderer chunkRenderer() {
        return chunkRenderer;
    }

    public Frustum frustum() {
        return frustum;
    }

    /** 实体渲染器（自测断言"实体确实被画出来了"用 —— 它只能由像素或绘制计数证明）。 */
    public com.skyisland.render.entity.EntityRenderer entityRenderer() {
        return entityRenderer;
    }

    /** 战斗表现渲染器（自测断言粒子/曳光确实被画出来了用）。 */
    public CombatFxRenderer combatFxRenderer() {
        return combatFxRenderer;
    }

    /** 第一人称手持物渲染器（自测断言 viewmodel 确实被画出来了用）。 */
    public ViewmodelRenderer viewmodelRenderer() {
        return viewmodelRenderer;
    }

    public void dispose() {
        chunkRenderer.disposeAll();
        crackOverlay.dispose();
        entityRenderer.dispose();
        combatFxRenderer.dispose();
        viewmodelRenderer.dispose();
        hudRenderer.dispose();
        menuRenderer.dispose();
        inventoryRenderer.dispose();
        modalDimRenderer.dispose();
        blockAtlas.dispose();
        if (voxelShader != null) {
            voxelShader.dispose();
            voxelShader = null;
        }
        if (uiShader != null) {
            uiShader.dispose();
            uiShader = null;
        }
        Log.info("[Renderer] 渲染器已释放");
    }
}

package com.skyisland.game;

import com.skyisland.audio.AudioEvent;
import com.skyisland.audio.AudioFeedback;
import com.skyisland.audio.AudioManager;
import com.skyisland.combat.CombatController;
import com.skyisland.combat.GunState;
import com.skyisland.craft.Crafting;
import com.skyisland.craft.CraftingPanel;
import com.skyisland.entity.Entity;
import com.skyisland.entity.EntityManager;
import com.skyisland.entity.MeleeMonster;
import com.skyisland.input.InputMapper;
import com.skyisland.input.InputState;
import com.skyisland.input.MenuNav;
import com.skyisland.item.ItemRegistry;
import com.skyisland.physics.DdaRaycaster;
import com.skyisland.physics.RaycastHit;
import com.skyisland.player.Camera;
import com.skyisland.player.Inventory;
import com.skyisland.player.ItemStack;
import com.skyisland.player.Player;
import com.skyisland.player.PlayerIntent;
import com.skyisland.render.Frustum;
import com.skyisland.render.GlDiagnostics;
import com.skyisland.render.Renderer;
import com.skyisland.render.entity.EntityRenderer;
import com.skyisland.render.Screenshot;
import com.skyisland.render.Window;
import com.skyisland.render.fx.CombatFxModel;
import com.skyisland.render.ui.HudModel;
import com.skyisland.render.ui.MenuLayout;
import com.skyisland.render.viewmodel.ViewmodelModel;
import com.skyisland.save.LevelMeta;
import com.skyisland.save.SaveFormat;
import com.skyisland.save.SaveManager;
import com.skyisland.save.SaveResult;
import com.skyisland.settings.GameSettings;
import com.skyisland.settings.InputBinding;
import com.skyisland.settings.LookConfig;
import com.skyisland.settings.SettingsStore;
import com.skyisland.ui.Localization;
import com.skyisland.ui.MenuScreen;
import com.skyisland.ui.Menus;
import com.skyisland.ui.SettingsMenuController;
import com.skyisland.ui.UiState;
import com.skyisland.ui.UiStateMachine;
import com.skyisland.player.InventoryInteraction;
import com.skyisland.render.ui.InventoryLayout;
import com.skyisland.render.ui.InventoryRenderModel;
import com.skyisland.settings.Action;
import com.skyisland.util.Coords;
import com.skyisland.util.Log;
import com.skyisland.world.Chunk;
import com.skyisland.world.ChunkStreamer;
import com.skyisland.world.DayClock;
import com.skyisland.world.DayPhase;
import com.skyisland.world.ResourceCoreRegen;
import com.skyisland.world.World;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;
import com.skyisland.world.block.CreativePalette;
import com.skyisland.world.gen.TestWorldGenerator;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SkyIsland 入口（<b>M1.5 Front-End &amp; Settings Shell</b>）。
 *
 * <p><b>M1.5 交付范围</b>（规格第 1–13 条）：主菜单封面 → 设置界面 → 开始游戏；
 * ESC 暂停 / 继续；键位重绑与冲突确认；设置独立持久化（{@code settings.json}）；
 * 鼠标灵敏度 / FOV / VSync / 显示 FPS 立即生效；三条退出路径全部经保存与 GL 清理。
 *
 * <p><b>M1.5 之前的既有范围</b>（与 TECH_DESIGN_v0.1.1 §S′ 的启用矩阵一致）：
 * 体素区块 / 第一人称相机与移动 / 碰撞与重力与跳跃 / 方块射线 /
 * 挖掘 / 拾取 / 放置 / 快捷栏 / 跨区块边界 / 虚空死亡与重生 / 存档与读档 /
 * debug HUD / 截图。
 *
 * <p><b>M2.1 起已交付</b>：枪械（无限后备弹药）/ 近战怪物 / 命中与后坐等战斗手感 /
 * 音频（OpenAL + 程序化合成，五个战斗音）。
 *
 * <p><b>M2.2 起已交付</b>：界面中文化（CJK 点阵字模）/ 主菜单"继续 / 新建世界" /
 * 设置分控制·显示·音频三类 / <b>27+9 格背包界面</b>（取放、Shift 搬运、光标持有堆、
 * tooltip、存档持久化与 v1→v2 槽位迁移）/ HUD 分层（F3 调试浮层与玩法层分离）/
 * 背包与 HUD 快捷栏共用同一份模型与同一个槽位渲染器。
 *
 * <p><b>尚未交付（M3 及以后）</b>：昼夜与天数 HUD / 饥饿 / 掉落物实体（死亡不掉落）/
 * 合成的配方系统 / 背包右键分堆 / 第二种怪物与武器 / 岛屿地形生成 / 蹲下。
 *
 * <h2>这个类在 M1.5 里的职责仍然是"接线"</h2>
 * 所有算法都在各自的包里。本类只做五件事：
 * <ol>
 *   <li><b>装配</b>：先读设置（因为 VSync 会影响窗口创建），再按顺序创建
 *       窗口 → 世界 → 玩家 → 存档 → 渲染器；</li>
 *   <li><b>界面状态机接线</b>：把键鼠输入翻译成状态迁移或菜单激活，
 *       并把状态迁移的后果（光标模式、HUD 可见性、是否推进模拟）施加到运行时；</li>
 *   <li><b>每帧意图冻结</b>：一帧只取一次输入，冻结成 {@link PlayerIntent}，
 *       供本帧的 0..N 个逻辑步共享（依据见 {@link GameLoop} 与 {@code TECH_DESIGN §B.1}）；</li>
 *   <li><b>把边沿动作与持续动作分开处理</b>：见下方"为什么帧级动作不能走意图快照"；</li>
 *   <li><b>收尾</b>：保存 → 释放 GPU 资源 → 输出结构化摘要（供报告摘录）。</li>
 * </ol>
 *
 * <h2>为什么帧级动作不能走"每帧一次"的意图快照</h2>
 * 逻辑恒为 60 Hz，而渲染帧率实测可达上千。这意味着<u>多数渲染帧里逻辑步数为 0</u>
 * —— 一次按键的"按下沿"只存在于它所在的那一帧的意图快照里；
 * 若那一帧恰好没有逻辑步，这个按下就被静默丢弃了。
 * （M0 的 ESC 失效是同一类问题的更低级版本，见 {@code TECH_DESIGN_v0.1.1 §A′.3}。）
 *
 * <p>因此本类按动作的性质分成两条通道：
 * <table border="1">
 *   <caption>动作通道划分</caption>
 *   <tr><th>动作</th><th>通道</th><th>理由</th></tr>
 *   <tr><td>移动 / 视角 / 左键挖掘</td><td>意图快照，逻辑步消费</td>
 *       <td>持续量。多消费一次无害（幂等），且必须与物理同频</td></tr>
 *   <tr><td>右键放置 / R 换弹 / 数字键切槽</td>
 *       <td><b>帧级一次性</b>，暂存后由本帧第一个逻辑步发放</td>
 *       <td>按下沿，语义是"一次"。它们一度直接搭在逐逻辑步复用的意图快照上，
 *           于是"一帧零逻辑步就静默丢弃、一帧多逻辑步就重复施加"（M2 缺陷 9）：
 *           实测 34455 个渲染帧只跑了 3518 个逻辑步，约九成右键点击被丢，
 *           症状就是"放置方块按了没反应"。发放点仍在逻辑步内，故不破坏与物理的同步</td></tr>
 *   <tr><td>F3 调试开关 / F2 截图 / F5 存档 / F4 刷怪 / F6 补给 / F7 清怪</td>
 *       <td><b>帧级边沿</b>，帧内立即处理</td>
 *       <td>与逻辑步无关。放进逻辑步会在"本帧无逻辑步"时丢失</td></tr>
 *   <tr><td>ESC 暂停 / 菜单激活</td><td><b>帧级边沿</b>，帧内立即处理</td>
 *       <td>界面状态不属于模拟。若与逻辑步同频，暂停会在无逻辑步的帧里失效，
 *           表现为"按了 ESC 没反应"</td></tr>
 *   <tr><td>F9 强制重生</td><td>帧级边沿 → 置位 → <b>下一个逻辑步</b>注入</td>
 *       <td>重生要清速度、要判落脚点，必须与物理同频，不能脱离逻辑步</td></tr>
 * </table>
 *
 * <h2>暂停的语义（规格第 2 条）</h2>
 * 暂停期间 {@code stepLogic} 会<u>被调用但立即返回</u>（并由 {@code pausedStepSkips} 计数）——
 * 不是"没人调用逻辑步"。这个区别是刻意的：前者证明"产品在暂停时<u>拒绝</u>推进模拟"，
 * 后者只说明"这一帧没轮到逻辑步"，两者在观测上不能长得一样
 * （同 {@code TECH_DESIGN_v0.1.1 §A′.3 E-7} 的立场）。
 * 渲染与菜单输入不受影响，因此玩家能看见冻结的世界并操作菜单。
 *
 * <h2>保存与截图的时机</h2>
 * <ul>
 *   <li><b>截图</b>必须在 {@code glfwSwapBuffers} <u>之前</u>：交换之后后台缓冲的内容
 *       是未定义的，此时回读会得到上一帧或垃圾（{@link Screenshot} 的设计前提）；</li>
 *   <li><b>存档</b>放在 {@code swapBuffers} <u>之后</u>：它要写十几个文件，
 *       放在交换前会让"这一帧"的时间里混入磁盘 IO。放在交换后，
 *       卡顿至少发生在玩家已经看到画面之后，且本类会把耗时显式记进日志
 *       （依据 {@code §C.4′ 第 7 条}：测试脚手架不得在被测量的窗口内制造假卡顿）。</li>
 * </ul>
 */
public final class SkyIslandGame implements GameLoop.FrameCallbacks {

    /** 自测完成后再多渲染若干帧：让最后一张截图与终态画面落定，然后自动退出。 */
    private static final int SELFTEST_LINGER_FRAMES = 30;

    /** 预热阶段（启动装配 + 首帧网格）最多尝试消费多少批网格重建。防御性上限。 */
    private static final int WARMUP_MESH_BATCH_LIMIT = 256;

    // ============================================================ M2.1 战斗表现常量

    /**
     * M2.1 缺陷 A：枪口相对眼睛的<b>前向</b>偏移（格）。
     *
     * <p>闪光（以及将来的枪械模型）都以"眼睛前方 0.55 格、且向右下偏一点"为枪口。
     * 这个偏移曾经只写在<u>注释</u>里 —— {@code CombatFxModel} 与 {@code CombatFxRenderer}
     * 都引用过"见 SkyIslandGame 的 {@code MUZZLE_*} 偏移"，但代码里根本不存在它。
     * 于是枪口闪光被直接生成在眼睛处，被相机吞进立方体内部，关闭背面剔除后
     * 内部面被光栅化成一片刺眼白光。现在把它写成真正的常量，注释的引用才成立。
     */
    public static final double MUZZLE_FORWARD = 0.55;

    /** M2.1 缺陷 A：枪口相对视线的右向偏移（格），让火光落在屏幕中心右侧。 */
    public static final double MUZZLE_RIGHT = 0.20;

    /** M2.1 缺陷 A：枪口相对视线的向下偏移（格），让火光落在屏幕中心下方（枪握在右下）。 */
    public static final double MUZZLE_DOWN = 0.12;

    /**
     * M2.1 缺陷 B：F4 刷怪时，从建议落点向下搜索可站立地面的最大深度（格）。
     *
     * <p><b>为什么不能复用 {@code Player.findNearestStandable}：</b>它只沿<u>同一 y 平面</u>
     * 做水平螺旋搜索（见其 javadoc），完全没有竖直方向 —— 玩家站在自建高塔上按 F4 时，
     * 建议点在同一高度找不到地面，怪物就被留在半空自由落体（缺陷 B 的现场）。
     *
     * <p><b>为什么是 32：</b>玩家可以垒到远高于地表的位置（测试世界地表顶面在 y=63，
     * 玩家可站到 y≈71 甚至更高），这个深度要能覆盖"高台 / 台阶 / 自建塔"到地表的全部落差；
     * 32 格足够，又不会一路穿到虚空把"找不到地面"这种情况误判成"脚下有地"。
     */
    public static final int SPAWN_GROUND_SEARCH_DEPTH = 32;

    // ------------------------------------------------------------ M2.1：F4 刷怪的候选搜索规格

    /**
     * M2.1：F4 刷怪候选点到玩家的<b>水平</b>距离下界（格）。
     *
     * <p>沿用旧语义的"约 5 格"直觉（超出攻击距离 1.6、远小于追击距离 24），
     * 但从"写死 5.0"改成"从 4.0 起逐 0.5 格试到 8.0，取第一个合法候选"——
     * 4.0 作为下界保证"一按就能看到怪走过来"（不会贴脸），
     * 8.0 作为上界保证"仍在准星视锥的中心区、一眼能看见"。
     */
    public static final double SPAWN_CANDIDATE_MIN = 4.0;

    /** M2.1：F4 刷怪候选点到玩家的水平距离上界（格）。见 {@link #SPAWN_CANDIDATE_MIN}。 */
    public static final double SPAWN_CANDIDATE_MAX = 8.0;

    /**
     * M2.1：候选距离的步长（格）。0.5 格 = 半个方块，
     * 9 个候选（4.0 … 8.0 每 0.5 一个）在"够密以避免空档"与"够少以免搜索次数过多"之间平衡。
     */
    public static final double SPAWN_CANDIDATE_STEP = 0.5;

    /**
     * M2.1：落点相对<b>玩家脚底</b>的竖直落差上限（格，判据用绝对值）。
     *
     * <p><b>为什么必须有这条：</b>旧实现只检查"候选点脚下能不能站"，完全不看
     * "这个落点与玩家差了几层"。玩家站在高塔上时，正前方 5 格的地表可能低十层 ——
     * 即使脚下能找到地面，刷出来的怪也在玩家下方很远，玩家"生成成功了却找不到"。
     * 本阈值把落点锁在玩家所在层的邻层之内。
     *
     * <p><b>为什么取 3：</b>测试世界的地表起伏是"逐级 1 格的台阶"（见
     * {@code TestWorldGenerator}），一级台阶高 1.0；取 3 足以容忍
     * "站在台阶上、脚下是上一级/下一级"甚至跨两级的情形，
     * 又严格小于"玩家与地表之间的任意高处"（测试里把玩家抬到 74，落差 10，必被拒）。
     * 它同时是"不刷到玩家上一层/下一层"这条产品语义的量化口径。
     */
    public static final double SPAWN_MAX_VERTICAL_OFFSET = 3.0;

    /**
     * 怪（近战怪）碰撞箱中心相对脚底的高度（格）。
     *
     * <p>近战怪高 {@code 1.8}（见 {@code MeleeMonster}），中心即 {@code 1.8 / 2 = 0.9}。
     * 视锥与视线判定都用"怪的身体中心"这个点，而不是脚底（脚底贴地，
     * 用它做视线起点会被自己的地面方块挡出一个假的"无视线"）。
     */
    public static final double SPAWN_MONSTER_CENTER_HEIGHT = 0.9;

    /**
     * 生成提示物的颜色：高明度的暖黄。
     *
     * <p>选暖黄而不是复用怪物红/草地绿/天空蓝：它是画面里饱和度最高、
     * 与三者都不同相的颜色，第一眼就能把视线从任何背景上拉过去 ——
     * 这正是"生成提示物"唯一要办的事。
     */
    private static final float SPAWN_CUE_R = 1.00f;
    private static final float SPAWN_CUE_G = 0.92f;
    private static final float SPAWN_CUE_B = 0.35f;

    /**
     * M2.1：F3 overlay 最多逐只列出的实体数。
     *
     * <p>{@code HudRenderer} 是按行自上而下画的，行数过多会溢出屏幕（挤爆 HUD）。
     * 实体多时最有价值的是"离我最近的那几只"（它们才是"冲我来的"这个判断的对象），
     * 因此按到玩家的水平距离取最近的 8 只，其余用一行"存活 N / 显示 M"汇总 ——
     * 让"被省略了多少"这件事不会被静默吞掉。
     */
    static final int DEBUG_ENTITY_LINES_MAX = 8;

    // ============================================================ 运行参数

    /**
     * 运行参数。全部可用系统属性覆盖，便于自动化复现与不同门禁口径复用。
     *
     * <p><b>为什么默认不自动退出：</b>验收动作之一是"人工试玩完整闭环"，
     * 需要窗口一直开着直到玩家退出。只有显式给 {@code measureSeconds > 0}
     * 或自测开关时才自动退出 —— 自动化运行必须自己声明"我什么时候结束"，
     * 而不是靠默认值把人工试玩也一起掐掉。
     *
     * <p><b>M1.5 新增 {@code uiSelfTest} 与 {@code startState}：</b>
     * 前者跑"M1.5 前段界面自测"；后者决定初始界面（{@code menu} / {@code playing}）。
     * 自动化运行（自测或性能测量）一律从 {@code playing} 开始 ——
     * 否则性能运行会停在主菜单里，永远达不到测量窗口，也永远不会退出。
     * 这是一条<u>必须显式记录</u>的规则，因为"性能数字为什么没出来"的答案
     * 通常就藏在这里。
     */
    record M1Config(
            int width,
            int height,
            String vsyncOverride,
            boolean debugGL,
            boolean hideCursor,
            long seed,
            String worldName,
            Path saveRoot,
            String logDir,
            String screenshotDir,
            int warmupSeconds,
            int measureSeconds,
            boolean selfTest,
            boolean saveEnabled,
            boolean uiSelfTest,
            boolean combatSelfTest,
            boolean infiniteReserve,
            Loadout loadout,
            GameMode gameMode,
            String startState
    ) {
        /**
         * 后备弹药口径开关的解析（GL-free 静态纯函数，便于单测）。
         *
         * <p><b>只有显式写 {@code true}（忽略大小写、允许首尾空白）才切无限后备。</b>
         * {@code null}（属性没给）、{@code "false"}、以及任何其它值一律留在正式口径（有限）。
         *
         * <p>为什么不做成"非 false 即无限"：那样一次拼写手滑（{@code =1}、{@code =yes}、
         * {@code =ture}）就会把口径静默翻成无限，而"弹药到底扣不扣"是玩家<b>立刻能感觉到</b>
         * 的东西 —— 它不该由一个错字决定。宁可对错字保持"留在有限"这个安全侧，
         * 并且把最终生效的口径打进启动日志（见 {@code logRuntimeProfile}），
         * 让"为什么弹药不扣"永远不需要靠猜。
         */
        static boolean parseInfiniteReserve(String raw) {
            return raw != null && "true".equalsIgnoreCase(raw.trim());
        }

        /**
         * 开局装备口径开关的解析（GL-free 静态纯函数，便于单测）。
         *
         * <p><b>与 {@link #parseInfiniteReserve} 同一条安全侧原则</b>：
         * 只有显式写 {@code dev}（忽略大小写、允许首尾空白）才切 DEV 口径，
         * 属性缺失 / {@code "false"} / 任何错字一律留在 {@link Loadout#SURVIVAL}。
         *
         * <p>为什么这一格必须"错字留在正式侧"：DEV 口径发的是<b>步枪与一整套材料</b>，
         * 它们本不该出现在正式存档里。若把"非 survival 即 dev"当成规则，
         * 一次手滑就会让"开一局正式新游戏"变成"开局一把步枪 + 全套材料" ——
         * 玩法规则由一个错字决定，而这正是 {@code v2 §19-15}「未偷跑」要防的那类污染。
         */
        static Loadout parseLoadout(String raw) {
            return Loadout.parse(raw);
        }

        /**
         * 游戏模式的解析（GL-free 静态纯函数，便于单测）。
         *
         * <p><b>与 {@link #parseLoadout} 同一条安全侧原则</b>：只有显式写
         * {@code creative} 才切创造，属性缺失 / 错字 / 空串一律落
         * {@link GameMode#SURVIVAL}（PRD §4.1「缺省即生存，防止手滑进创造毁档」）。
         *
         * <p><b>但这一格还多一条"存档优先"</b>：本方法返回的只是"配置怎么说"，
         * 最终生效的模式由 {@code SaveManager#effectiveGameMode()} 按
         * {@link GameMode#resolve} 决定 —— 存档里已有值时，命令行开关一律不生效
         * （PRD §4.3「模式一旦创建，永不切换」）。
         *
         * <p>★ <b>不要</b>把"存档优先"实现在这里：那是本项目 P0b 修过一次的同类缺陷
         * （把"该由谁决定"写在了错误的层，导致某一类存档的行为与另一类不同）。
         */
        static GameMode parseGameMode(String raw) {
            return GameMode.parse(raw);
        }

        static M1Config fromSystemProperties() {
            Path saveRoot = SaveFormat.resolveSaveRoot();
            return new M1Config(
                    Integer.getInteger("skyisland.width", Window.DEFAULT_WIDTH),
                    Integer.getInteger("skyisland.height", Window.DEFAULT_HEIGHT),
                    System.getProperty("skyisland.vsync"),
                    !"false".equalsIgnoreCase(System.getProperty("skyisland.debugGL", "true")),
                    !"false".equalsIgnoreCase(System.getProperty("skyisland.hideCursor", "true")),
                    Long.getLong("skyisland.seed", 20260919L),
                    System.getProperty("skyisland.worldName", SaveFormat.DEFAULT_WORLD_NAME),
                    saveRoot,
                    System.getProperty("skyisland.logDir", "logs"),
                    System.getProperty("skyisland.screenshotDir", "screenshots"),
                    Integer.getInteger("skyisland.warmupSeconds", 5),
                    Integer.getInteger("skyisland.measureSeconds", 0),
                    Boolean.getBoolean("skyisland.selfTest"),
                    !Boolean.getBoolean("skyisland.noSave"),
                    Boolean.getBoolean("skyisland.uiSelfTest"),
                    // ★ M2：独立的战斗自测开关，刻意<b>不</b>复用 skyisland.selfTest ——
                    //   见 start() 里的互斥检查。
                    Boolean.getBoolean("skyisland.combatSelfTest"),
                    // ★ M3 Story 10：后备弹药口径的**玩家可见开关**。
                    //   默认 false = 保持正式口径 SURVIVAL（有限、真实扣 Inventory，v2 §19-6）。
                    //   这是 v2 §19-7「Debug 模式仍可无限备弹」的玩家入口 ——
                    //   Story 8 之后这个能力只以枚举形式存在，玩家侧一直没有入口
                    //   （唯一设成 PROTOTYPE 的地方是战斗自测路径）。
                    parseInfiniteReserve(System.getProperty("skyisland.infiniteReserve")),
                    // ★ 开局装备口径：默认 SURVIVAL（正式玩法，v2 的 M3 范围 = 两把枪）。
                    //   步枪与过渡材料包只属于 DEV / TEST 口径：门禁与 play.bat 显式传 dev。
                    //   详见 Loadout 的类注释（"为什么必须拆开"）。
                    parseLoadout(System.getProperty(Loadout.SYSTEM_PROPERTY)),
                    // ★ M4-S6：游戏模式。默认 SURVIVAL（PRD §4.1 缺省即生存）。
                    //   存档优先由 SaveManager.effectiveGameMode() 负责，
                    //   这里的值只在"该存档还没有 gameMode 字段"时被写盘。
                    parseGameMode(System.getProperty(GameMode.SYSTEM_PROPERTY)),
                    System.getProperty("skyisland.startState", "")
            );
        }

        boolean autoExit() {
            return measureSeconds > 0;
        }

        /**
         * 是否属于"自动化运行"（自测 / 界面自测 / 性能测量）。
         *
         * <p>这个判定被用来统一处理两件与门禁有关的事：
         * <ol>
         *   <li><b>不碰玩家真实的设置文件</b> —— 门禁运行会改灵敏度、改键位、
         *       故意写坏配置文件（{@code CORRUPT_FALLBACK} 阶段），
         *       若落在真实路径上，一次验收就会把开发者的手感设置毁掉；</li>
         *   <li><b>从出厂默认设置起步</b> —— M1 的 LOOK 断言与 M1.5 的
         *       "灵敏度 1.25 / FOV 80"断言都是<u>绝对值</u>。
         *       若继承上一次运行留下的设置，第二次运行必然失败，
         *       而失败信息看起来像产品坏了，实际是"起点不同"。
         *       门禁运行必须可重复，这是它的前提。</li>
         * </ol>
         */
        boolean automated() {
            return selfTest || uiSelfTest || combatSelfTest || measureSeconds > 0;
        }

        /**
         * 本次运行用哪个世界生成器。
         *
         * <p>★ <b>夹具与产品必须分开</b>（PRD 4.2 的正式空岛世界 vs M1 的测试平台）：
         * <ul>
         *   <li><b>产品 / 试玩</b> → {@code IslandWorldGenerator}（主岛 32×32 + 4 资源岛 + 开局小屋）；</li>
         *   <li><b>三个自测</b> → {@code TestWorldGenerator}，因为它们各自钉在一个
         *       <b>专门为它搭的平台上</b>：M1 的虚空坑固定在 {@code (3..6, 3..6)}、
         *       高台固定 3 格（跳不上去的对照组）、上行楼梯逐级 1 格、
         *       玻璃板固定跨 {@code x=0} 区块边界、出生点固定
         *       {@code (0.5, 64.0, 0.5)}。换地形会让这些断言以「玩法没生效」的
         *       样子失败，而真因是地形不同 —— 与 M2.2 那次「槽位口径」事故同族。</li>
         * </ul>
         * <p>判定口径刻意<b>只看三个自测开关</b>，不看 {@code automated()}：
         * 性能测量（{@code measureSeconds > 0}）<b>必须</b>跑产品世界 ——
         * 否则测的是一块测试平台的数字，而 PRD 12.5 的性能门禁针对的是玩家实际会加载的世界。
         * <p>可用 {@code -Dskyisland.generator=test|islands} 显式覆盖，
         * 用于"用产品世界跑一遍自测"这类诊断（那时失败才是真信息）。
         */
        boolean useProductWorld() {
            String override = System.getProperty("skyisland.generator", "");
            if ("islands".equalsIgnoreCase(override)) {
                return true;
            }
            if ("test".equalsIgnoreCase(override)) {
                return false;
            }
            return !selfTest && !uiSelfTest && !combatSelfTest;
        }

        double totalSeconds() {
            return warmupSeconds + measureSeconds;
        }

        /** VSync 的命令行覆盖值；{@code null} 表示"听设置的"。 */
        Boolean vsyncOverrideOrNull() {
            return vsyncOverride == null || vsyncOverride.isBlank()
                    ? null : Boolean.parseBoolean(vsyncOverride);
        }
    }

    // ============================================================ 字段

    private final M1Config config;

    /** 原始输入（GLFW 回调写入，本类读取）。 */
    private final InputState input = new InputState();

    /** 原始输入 → 意图。每帧调用一次（消费鼠标位移累积量）。 */
    private final InputMapper inputMapper = new InputMapper();

    /** HUD 数据汇集（渲染层只读它的字段）。 */
    private final HudModel hud = new HudModel();

    /** M2.1：第一人称手持物数据汇集（渲染层只读它的字段）。 */
    private final ViewmodelModel viewmodel = new ViewmodelModel();

    /**
     * M2.2：背包界面数据汇集。
     *
     * <p><b>为什么它持有 {@code Inventory} 的引用而不是 36 个 int 的快照：</b>
     * 背包界面与 HUD 快捷栏必须是<b>同一份模型</b>。若这里存快照，
     * 就必然存在"什么时候把 Inventory 拷进来"这一时刻 —— 拷漏了界面不更新，
     * 拷早了界面显示旧数据。M2 已经为"网格重建队列没人消费"付过一次学费，
     * 那次的症状正是"方块挖掉了但画面没变"，与本类若做快照会出的症状同构。
     */
    private final InventoryRenderModel inventoryModel = new InventoryRenderModel();

    /**
     * 背包内合成区的界面模型（2026-10-03）。
     *
     * <p><b>它是 {@code craft} 包第一个产品消费者。</b>上一轮把 PRD 5.6.2 的配方表与
     * {@link com.skyisland.craft.Crafting} 的纯逻辑都写完了，但全工程没有任何一处
     * 调用它们 —— 没有界面、没有命令、没有按键：数据在、逻辑对、单测全绿，
     * 而玩家<b>永远碰不到</b>。把它挂进背包界面之后，
     * "合成"才第一次成为一个玩家能做的事。
     *
     * <p>刷新时机刻意是<b>事件驱动</b>（开背包 / 一次搬运之后 / 一次合成之后），
     * 而不是每帧重算：10 条配方 × 跨槽计数在 3000 FPS 下是每秒几百万次
     * 没有意义的扫描，而"材料够不够"只会在背包内容变化的那一刻改变。
     */
    private final CraftingPanel craftingPanel = new CraftingPanel();

    // ============================================================ M4-S7：创造面板
    /**
     * ★ 创造面板的分组视图；{@code null} = 生存模式（本字段保持 null）。
     *
     * <p>★ <b>它在读档之后才建，且只在创造模式下建</b>：
     * 门控判据是 {@link SaveManager#effectiveGameMode()}（存档定死，PRD §4.3），
     * 而<b>不是</b> {@code -Dskyisland.gameMode} —— 后者只对"还没定死"的世界有效，
     * 用它门控会让"用创造存档启动却带了 survival 开关"的世界凭空多出创造面板。
     *
     * <p>★ <b>它是一个字段而不是每帧 {@code CreativePalette.build()}</b>：
     * {@code build()} 会遍历整个 {@code BlockRegistry} 并排序，每帧调一次是纯浪费；
     * 更要紧的是"每帧一个新对象"会让 {@code InventoryRenderer} 的布局缓存
     * 每帧失效 —— 而缓存失效的失败模式是<b>看不出</b>的（只是变慢，不是变错）。
     */
    private CreativePalette.CreativeView creativeView;

    // ---- M1.5：设置与界面 ----
    private GameSettings settings;
    private SettingsStore.LoadResult settingsLoad;
    private UiStateMachine ui;
    private SettingsMenuController settingsMenu;
    private MenuScreen mainMenuScreen;
    private MenuScreen pauseMenuScreen;
    private MenuLayout menuLayout;
    private M1_5UiSelfTest uiSelfTest;
    private String lastWindowTitle;
    private double lastSettingsWriteMs;
    private int settingsWriteCount;

    private Window window;
    private GameLoop loop;
    private World world;
    private Player player;
    private SaveManager saveManager;
    private Renderer renderer;
    /**
     * ★ M4-S8a：以玩家为中心的区块流式加载 / 卸载。
     *
     * <p>它是 {@code world} 的<b>附属品</b>而不是独立的系统：换世界（新建世界）
     * 必须换它，因为流式的中心与"这个世界加载了哪些区块"是一件事。
     */
    private ChunkStreamer chunkStreamer;

    // ---- M2：战斗 ----
    /** 世界上会动的东西（M2 起只有近战怪）。 */
    private EntityManager entities;
    /** 枪械玩法：开火 / 换弹 / 最近命中结算。 */
    private CombatController combat;
    /** 战斗表现（粒子 + 曳光）的纯状态机；GL 部分在 Renderer 里。 */
    private CombatFxModel combatFx;
    /** 刷怪用的调试计数器：兼作粒子种子与"刷了几只"的观测值。 */
    private int debugSpawnCount;
    /** 瞄准状态的上一次观测值 —— 只在"刚进入瞄准"时提示一次，避免每帧刷屏。 */
    private boolean wasAiming;
    /** 本次会话是否已经给过瞄准提示（PRD 5.7 的提示语义是"教一次"，不是"每次提醒"）。 */
    private boolean aimHintShown;
    /** 是否已经给过"首次进入世界"的操作提示（PRD 6.7：仅首次）。 */
    private boolean firstJoinHintShown;
    /** 飞行状态的上一次观测值（M4-S8b：只在切换时提示一次）。 */
    private boolean wasFlying;

    /**
     * ★ M5a：昼夜时钟（PRD §4.4，20 分钟一个昼夜）。
     *
     * <p><b>它归本类所有而不是 {@code World}</b>：
     * 昼夜是<b>整局游戏</b>的属性而不是世界数据 —— 世界可以被换（新开一局、
     * 读档重建），而"现在是第几天"必须跟着玩家延续。
     * 放进 {@code World} 会让它在 {@code startNewWorld()} 里被悄悄重置，
     * 而那次重置的现场是"玩家新建了一个世界"，看起来完全无害。
     */
    private DayClock dayClock;

    /**
     * M3 资源核心慢速再生（PRD 4.6）。<b>仅产品世界装配</b>，自测为 {@code null}。
     *
     * <p>具体类而非接口：只有一个实现，且本类要读它的可观测计数
     * （进测量摘要）而不是替换它。
     */
    private com.skyisland.world.ResourceCoreRegen coreRegen;
    /** 表现层种子的递增源：让"同一次运行"里的粒子分布可复现。 */
    private long fxSeedCounter;

    /**
     * 视线（LOS）射线方向的可复用缓冲。
     *
     * <p>{@link DdaRaycaster} 需要 {@code Vector3dc}；F3 overlay 每帧可能对多只实体各做一次
     * LOS，因此复用同一个实例，避免在渲染路径上制造临时分配（core 零热路径分配）。
     */
    private final Vector3d losDirection = new Vector3d();

    /**
     * F3 overlay 排序用的可复用实体列表。
     *
     * <p>避免在"F3 打开"的渲染路径上每帧 {@code new} 一个 ArrayList 再排序
     * （与 {@link #losDirection} 同一取向：渲染路径上不制造临时分配）。
     */
    private final List<Entity> debugEntityScratch = new ArrayList<>();

    /**
     * M2.1：上一轮 {@link #debugSpawnMonster()} 里四个硬条件各自的<b>拒绝计数</b>
     * （索引 0=地面 1=落差 2=视锥 3=视线）。
     *
     * <p><b>为什么必须由产品路径自己累积：</b>失败提示只告诉玩家"没有合适的位置"，
     * 却说不清是哪一条判据把所有候选都拒了 —— 排查"F4 按了没反应"时，
     * 唯一的线索就是这个分布。若由自测另算一套，它证明的是脚手架的判断而不是产品的。
     * 每次 {@code debugSpawnMonster()} 开始时清零，因此它<u>恒指最近一次</u>。
     */
    private final int[] lastSpawnRejectCounts = new int[4];

    /**
     * M2.1：上一轮 {@link #debugSpawnMonster()} 里视锥判据是否<b>真的参与</b>。
     *
     * <p>无渲染器时 {@link #refreshCameraFrustum()} 返回 {@code null}，
     * 视锥判据因短路<u>整条不执行</u>。此时它既不是"通过"、也不是"拒绝" ——
     * 本字段让这两种情形在日志里可辨，绝不把"没判"伪装成"判过了"。
     */
    private boolean lastFrustumEvaluated;

    // ---- M2.1：最小音频反馈链 ----

    /**
     * 音频门面。它是"可以整体缺席"的：没有声卡 / 没有 OpenAL 时
     * {@link AudioManager#open()} 只记一条日志，游戏照常运行（见该类类注释）。
     */
    private final AudioManager audio = new AudioManager();

    /**
     * 战斗事件 → 音效的翻译层。{@code null} 表示还没接线（构造期与"音频被关掉"都算）。
     *
     * <p>之所以不像 {@link #combatFeedback} 那样写成字段初始化器：
     * 它依赖 {@link #combatFx} 之外的东西（{@link #audio}），
     * 而"接线"这件事要在自测选择监听器<u>之前</u>完成，否则自测会用一条
     * 不含音频的链跑到结束 —— 那样"自测里没有声音"就无法与"音频坏了"区分。
     */
    private AudioFeedback audioFeedback;

    /**
     * 当前手持枪的表现规格；手持物不是枪（或没有表现规格）时返回 {@code null}。
     *
     * <p>M3（Story 5）引入：枪口火光的位置来源从本类的全局常量改为"拿的那把枪的表现规格"。
     * 取法刻意与 HUD 一致 —— 都读 {@code inventory.selectedStack()}，
     * 于是"HUD 显示的枪"与"火光的枪"不可能不是同一把。
     *
     * <p>不缓存、每次现取：本方法只在<u>开火那一刻</u>被调用（不是渲染热路径），
     * 缓存在换槽/换枪时需要失效，而失效时机正是最容易漏的地方。
     */
    private com.skyisland.item.GunPresentationSpec heldGunPresentation() {
        ItemStack held = player.inventory().selectedStack();
        if (held == null || held.item() == null || !held.item().isGun()) {
            return null;
        }
        return held.item().presentation();
    }

    /**
     * 当前手持枪的<b>后坐档案</b>（{@code GunPresentationSpec.recoilProfileId} 的解析结果）。
     *
     * <p>与 {@link #heldGunPresentation()} 同一条口径：不缓存、每次现取，
     * 于是"开火那一刻的抬枪幅度"与"这一步的回落速度"必然来自同一把枪。
     *
     * @return 手持非枪时为 {@link RecoilProfile#DEFAULT}
     *         （= 手枪曲线 —— 沿用既有表现，不制造一套从未被验收过的"零后坐"）
     */
    private com.skyisland.item.RecoilProfile heldRecoilProfile() {
        com.skyisland.item.GunPresentationSpec pres = heldGunPresentation();
        return pres == null
                ? com.skyisland.item.RecoilProfile.DEFAULT
                : com.skyisland.item.RecoilProfile.byId(pres.recoilProfileId());
    }

    /**
     * 战斗事件 → 表现 / 提示的接线（M2）。
     *
     * <p>把 {@link CombatController} 的事件翻译成粒子、曳光与 HUD 提示。
     * 做成内部类而不是让 {@code CombatController} 直接依赖 {@code render} 包，
     * 是为了保住"玩法单向依赖表现"的方向：战斗逻辑不认识粒子，也不认识 HUD。
     */
    private final CombatController.Listener combatFeedback = new CombatController.Listener() {
        @Override
        public void onShotFired(double mx, double my, double mz,
                                double ex, double ey, double ez, boolean hitAnything) {
            combatFx.spawnTracer(mx, my, mz, ex, ey, ez);
            // ---- M2.1：枪口闪光 + 后坐力 ----
            // 三样东西（曳光 / 闪光 / 后坐）必须挂在<u>同一个</u>事件上，因为它们共同回答
            // "这一发真的打出去了吗"：曳光说"打到哪去"，闪光说"从哪出发"，
            // 后坐说"有多大力"。少了任何一样，玩家仍读得出"我开了枪"，
            // 但那份确认会明显变弱。
            //
            // 更重要的是：它们分散在三个不同的地方触发时，最容易发生的失效是
            // "其中一条从来没被接上，而且没有任何断言看得见" ——
            // M2.1 就真的这样交付过一次（spawnMuzzleFlash / spawnHitMarker /
            // addRecoilPitch / decayRecoil 四个方法全部只定义、从不被调用，
            // 编译通过、单测全绿、三门禁全绿）。因此这里刻意把三者写在一起，
            // 并让 M2 自测对它们的累计计数逐条断言（见 M2CombatSelfTest 的表现层校验）。
            // ---- M2.1 缺陷 A：闪光挂在真正的枪口，而不是眼睛 ----
            // mx/my/mz 是射线起点 = 眼睛（对射线与曳光都是对的，见 resolveShot 的 javadoc），
            // 但闪光不能生在眼睛处：它是边长 0.13 格的立方体，生在眼睛处会被相机吞进
            // 立方体内部，关闭背面剔除后内部面被光栅化，糊成一片刺眼白光。
            // 因此按"眼睛 + 前向×MUZZLE_FORWARD + 右向×MUZZLE_RIGHT − 上向×MUZZLE_DOWN"
            // 推出右下方的枪口点。方向依据：right() = forward × worldUp（见 Camera.updateBasis），
            // yaw=0 朝 −Z 时指向 +X（屏幕右）；up() 恒为世界 +Y（Camera 的 up 字段固定），
            // 故 −up 即向下。曳光<u>不</u>改起点：它必须与准星射线对齐，仍用 mx/my/mz。
            // ---- M3（Story 5）：枪口位置改为从「手持枪的表现规格」读取 ----
            // M2.2 这里用的是本类的全局常量 MUZZLE_FORWARD/RIGHT/DOWN。只有一把枪时
            // 看不出问题，但第二把枪（SMG）一旦落地，"两把枪从同一点冒火光"就会成为
            // 一次看得见的表现回归（v2 §4.2 第 3 点：两把枪的枪口位置必须有区别）。
            // 因此枪口偏移改由 item.presentation().muzzle* 提供 —— 数据挂在拿的那把枪上。
            // 对<u>手枪</u>而言这组值与旧常量逐值相等（0.55/0.20/0.12），故本步对画面零影响；
            // 常量保留为"手持物品没有表现规格时的兜底"（例如将来某种非枪的射击道具）。
            org.joml.Vector3d eye = player.eyePosition();
            org.joml.Vector3fc fwd = player.camera().forward();
            org.joml.Vector3fc right = player.camera().right();
            org.joml.Vector3fc up = player.camera().up();
            com.skyisland.item.GunPresentationSpec pres = heldGunPresentation();
            double[] muzzle = new double[3];
            if (pres != null) {
                // 数据驱动路径：手持枪的表现规格说了算。
                com.skyisland.render.viewmodel.MuzzleAnchor.compute(muzzle, eye, fwd, right, up, pres);
            } else {
                // 兜底路径：没有表现规格时沿用 M2.2 的全局常量（保持旧行为，不制造新分支语义）。
                com.skyisland.render.viewmodel.MuzzleAnchor.compute(muzzle, eye, fwd, right, up,
                        MUZZLE_FORWARD, MUZZLE_RIGHT, MUZZLE_DOWN);
            }
            combatFx.spawnMuzzleFlash(muzzle[0], muzzle[1], muzzle[2], eye.x, eye.y, eye.z,
                    fwd.x(), fwd.y(), fwd.z());
            // 后坐力"只加不回落"：回落由逻辑步里的 decayRecoil(dt, profile) 推进。
            // 分成两处是刻意的 —— 本方法每次开火调用一次，而回落必须按时长推进，
            // 两者的频率不同源。
            //
            // 2026-10-03：抬枪量与累计上限来自当前枪的后坐档案
            // （RecoilProfile：手枪 0.9° / SMG 0.35° / 步枪 1.6°）。
            // 在此之前这里写的是 Camera.RECOIL_PITCH_PER_SHOT_DEG 这个全局常量 ——
            // 于是 GunPresentationSpec.recoilProfileId 是一条没有任何读者的死键：
            // 拿步枪开一枪，画面抬起的角度与手枪逐位相同。
            player.camera().addRecoil(heldRecoilProfile());
        }

        @Override
        public void onBlockHit(double x, double y, double z,
                               double nx, double ny, double nz, int blockRuntimeId) {
            Block block = BlockRegistry.byRuntimeId(blockRuntimeId);
            combatFx.spawnBlockHit(x, y, z, nx, ny, nz,
                    block.colorR(), block.colorG(), block.colorB(), nextFxSeed());
        }

        @Override
        public void onEntityHit(Entity entity, int damage, double distance) {
            // 怪物受击闪白由 Entity.hurt 自己维护（渲染层读 hurtFlash01 画出来）。
            // M2.1 起"打中了"另有听觉通道：AudioFeedback.onEntityHit → hit_enemy
            // （见 audio 包）。本方法负责视觉与文字，三者是并联的。
            //
            // ---- M2.1：命中标记 + 溅射粒子 ----
            // 命中标记只对"打中怪物"给出，不对打中方块给出：它的含义是
            // "我打中的是会动的东西"，对墙开枪也弹十字线会让这个标记失去信息量。
            combatFx.spawnHitMarker();
            // 溅射位置取躯干中段而不是脚底：脚底的粒子会被脚下的地形挡掉一半。
            // 颜色取怪物躯干色，与"破坏方块时用方块自己的颜色"同一条口径 ——
            // 于是玩家眼里只有一种"表面被击中"的物理现象，差别只有粒子数量。
            org.joml.Vector3d p = entity.position();
            float[] body = com.skyisland.render.entity.MonsterModel.color(
                    com.skyisland.render.entity.MonsterModel.PART_TORSO);
            combatFx.spawnEntityHit(p.x,
                    p.y + com.skyisland.render.entity.MonsterModel.HEIGHT * 0.5, p.z,
                    body[0], body[1], body[2], nextFxSeed());
            //
            // 文字走"5 秒内同类不重复"（PRD 6.7「提示时长」）：射速是 4 发/秒，
            // 不去重的话这条提示会独自霸占屏幕。每一次命中的即时感由
            // 命中标记 + 怪物闪白 + 溅射粒子承担 —— 那三样才是"每发都有反馈"的正确载体，
            // 一行需要阅读的文字不适合承担这个职责。
            //
            // 注意：上面那句"命中标记 + 闪白 + 溅射粒子"在 M2.1 交付时曾经是一句
            // <b>许愿</b> —— 三个里有两个从未被调用。注释描述一条行为，不等于那条行为
            // 被登记成了断言；这正是本项目反复吃到的那个跟头。
            showEventDeduped(Localization.MSG_HIT_ENTITY, Localization.text(
                    Localization.MSG_HIT_ENTITY,
                    Localization.displayName(entity.typeId()), distance, damage), 2.0);
        }

        @Override
        public void onDryFire() {
            // 空枪的听觉反馈是 gun_empty（见 AudioFeedback）；本方法不重复处理，
            // 文案由 onMessage 统一显示。
        }

        @Override
        public void onReloadRequest(com.skyisland.combat.GunState.ReloadOutcome outcome) {
        }

        @Override
        public void onReloadCompleted(int magazineAmmo, int magazineSize) {
        }

        @Override
        public void onMessage(String textKey, Object... args) {
            // 走去重通道：PRD 6.7 要求"同类提示 5 秒内不重复、每条 2 秒后淡出"。
            showEventDeduped(textKey, Localization.text(textKey, args), 2.0);
        }
    };

    /**
     * 逻辑步真正传给 {@code combat.step} 的事件接收方。
     *
     * <p>默认就是产品自己的 {@link #combatFeedback}；M2 战斗自测开启时会被换成
     * "产品反馈 + 自测事件记录器"的串联体（见 {@code M2CombatSelfTest.tee}）。
     * 之所以要串联而不是替换：自测必须同时证明<u>表现层真的收到了事件</u>
     * （粒子/曳光的累计读数就是证据之一），而不是把产品反馈整个屏蔽掉。
     */
    private CombatController.Listener combatListener = combatFeedback;

    /** 破坏方块 → 8–12 个方块色粒子（PRD 5.2 表，v0.3.2 A1 裁决锚定在 M2）。 */
    private final Player.BlockBreakListener breakFeedback = (bx, by, bz, block, leftover) -> {
        combatFx.spawnBlockBreak(bx, by, bz,
                block.colorR(), block.colorG(), block.colorB(), nextFxSeed());
        if (leftover > 0) {
            showEventDeduped(Localization.MSG_INVENTORY_FULL,
                    Localization.text(Localization.MSG_INVENTORY_FULL), 2.0);
        }
    };

    private long nextFxSeed() {
        return ++fxSeedCounter * 0x9E3779B97F4A7C15L;
    }

    // ---- 每帧意图快照与帧级边沿 ----
    private PlayerIntent frameIntent = PlayerIntent.NONE;
    private boolean pendingRespawn;

    /**
     * 帧级输入量（鼠标位移 / 滚轮）的暂存与发放。
     *
     * <p>一帧可能跑 0 个或多个逻辑步，而帧级量只能被施加<u>恰好一次</u> ——
     * 详见 {@link com.skyisland.input.FrameInputQuantities} 的类说明。
     * M1.5 之前它是不存在的，因此同时存在"零逻辑步丢输入"与"多逻辑步重复施加"
     * 两个方向相反的缺陷。
     */
    private final com.skyisland.input.FrameInputQuantities frameQuantities =
            new com.skyisland.input.FrameInputQuantities();

    // ---- 帧级动作（在帧内确定的时点执行）----
    private String pendingScreenshotLabel;

    /**
     * 截图文件名的前缀。
     *
     * <p><b>为什么要按来源分开：</b>M1 自测、M1.5 界面自测、M2 战斗自测可能把截图写到
     * 同一个目录（{@code -Dskyisland.screenshotDir} 可以指向同一处）。
     * 从前缀一律写死 {@code m1_}，于是 M2 的截图会叫 {@code m1_m2_selftest-...}，
     * 在证据文档里"哪张属于哪个里程碑"变得要靠猜。
     * 默认值保持 {@code m1_} 不变 —— M1 报告里已经引用了那批文件名，不能改。
     */
    private String pendingScreenshotPrefix = "m1_";
    private boolean pendingSave;

    // ---- 测量状态 ----
    private double elapsedSeconds;
    private boolean warmupDone;
    private boolean measurementDone;
    private int nextProgressLog = 5;
    private final long startedAtNanos = System.nanoTime();
    private String glErrorSeen;

    // ---- P3：堆内存采样 ----
    // ★ 采样间隔用「秒」而不是「帧」：不限帧率时每帧不足 1 ms，
    //   按帧采样会每秒几千个样本（无信息量）且让采样本身成为负载。
    private static final double HEAP_SAMPLE_INTERVAL_SECONDS = 1.0;
    private final HeapSampler heap = new HeapSampler();
    private double lastHeapSampleSeconds;

    // ---- M1.5：模拟推进计数（暂停证明用）----
    private int simulationSteps;
    private long pausedStepSkips;

    // ---- 事件提示（HUD 上一行短消息，带倒计时）----
    private String eventMessage = "";
    private double eventSecondsLeft;
    /** 上一条提示的 key 与时刻，用于"同类提示 5 秒内不重复"（PRD 6.7）。 */
    private String lastEventKey = "";
    private double lastEventKeyAt = Double.NEGATIVE_INFINITY;

    // ---- 自测 ----
    private M1ScriptedSelfTest selfTest;
    /** M2 战斗脚本化自测（与 M1 自测互斥，见 start() 的启动期检查）。 */
    private M2CombatSelfTest combatSelfTest;
    private int selfTestLingerFrames = -1;

    // ---- 记录（供收尾摘要与报告摘录）----
    private final List<String> screenshotPaths = new ArrayList<>();
    private boolean lastScreenshotUniform;
    private SaveResult initialLoadResult;
    private SaveResult lastSaveResult;
    private double warmupMeshMillis;
    private int resizeEventCount;
    private int lastFramebufferWidth;
    private int lastFramebufferHeight;

    private SkyIslandGame(M1Config config) {
        this.config = config;
    }

    // ============================================================ 入口

    public static void main(String[] args) {
        List<String> appliedArgs = applyCommandLineOverrides(args);

        M1Config config = M1Config.fromSystemProperties();

        // ★ 日志必须在 GLFW 初始化之前就绪，否则 GLFW 自身的告警会丢失
        Log.init(config.logDir(), true);

        // 命令行覆盖要在日志就绪之后打印：这些参数决定"这次运行到底是什么性质"，
        // 排查"为什么没跑自测"时它是第一条线索。
        for (String note : appliedArgs) {
            Log.info("[参数] 命令行覆盖：%s", note);
        }

        SkyIslandGame game = new SkyIslandGame(config);
        int exitCode = 0;
        try {
            game.start();
            game.loop();
        } catch (Throwable t) {
            Log.error("运行失败 —— 致命异常", t);
            exitCode = 1;
        } finally {
            // ★ 退出流程的唯一终点：保存 + 释放 GPU 资源 + 销毁窗口 + glfwTerminate。
            //   System.exit 只在 cleanup 全部完成之后（见下方），任何"直接退出"的
            //   实现都会绕过存档与 native 资源释放 —— 这正是 M1.5 规格第 10 条禁止的。
            try {
                game.shutdown();
            } catch (Throwable t) {
                Log.error("清理阶段异常", t);
                exitCode = Math.max(exitCode, 1);
            }
            // ★ 自测的判定必须反映到退出码 —— 见 automationExitCode() 的说明。
            //   这一步放在 shutdown 之后：即使收尾阶段出错，自测的失败也不会被"退出码 0"掩盖。
            int automation = game.automationExitCode();
            if (automation != 0) {
                Log.error("自测判定为失败 —— 进程退出码置为 %d（日志里的 FAIL 与退出码必须一致）",
                        automation);
            }
            exitCode = Math.max(exitCode, automation);
            Log.info("退出码 = %d", exitCode);
        }
        System.exit(exitCode);
    }

    /**
     * 把命令行上的 {@code -Dkey=value} 提升为系统属性。
     *
     * <p><b>为什么必须有这一步（M1 的启动契约一直是坏的）：</b>
     * 本工程的启动脚本是
     * {@code java <jvm-args> -jar skyisland.jar <脚本参数>}，
     * 因此 {@code run-m1.bat -Dskyisland.selfTest=true} 里的 {@code -D...}
     * 是<b>程序参数</b>，不是 JVM 参数 —— JVM 不会把它变成系统属性，
     * 而 {@code main} 又从不读 {@code args}。
     * 两个"不"叠在一起的结果是：脚本注释里承诺的自动化开关
     * <u>在 M1 期间从未真正生效</u>，运行会照常进入交互模式并永远不退出。
     *
     * <p>这类缺陷无法靠"跑一次看看"发现 —— 现象只是"窗口开着不退出"，
     * 与"我本来就想试玩一下"完全一样。因此这里把它变成一个可被日志证明的事实：
     * 每次覆盖都打印一行。
     *
     * <p>只覆盖<b>尚未设置</b>的属性：真正的 JVM 参数（写在 {@code -jar} 之前）
     * 优先级更高，这样"脚本里的默认值"与"我这次手动指定的值"不会互相打架。
     *
     * @return 供日志打印的覆盖清单（含被忽略的参数，便于发现拼写错误）
     */
    static List<String> applyCommandLineOverrides(String[] args) {
        List<String> notes = new ArrayList<>();
        if (args == null) {
            return notes;
        }
        for (String arg : args) {
            if (arg == null || !arg.startsWith("-D")) {
                if (arg != null && !arg.isBlank()) {
                    notes.add("忽略无法识别的参数（本程序只接受 -Dkey=value）：" + arg);
                }
                continue;
            }
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            String key = eq < 0 ? body : body.substring(0, eq);
            String value = eq < 0 ? "true" : body.substring(eq + 1);
            if (key.isBlank()) {
                notes.add("忽略缺少属性名的参数：" + arg);
                continue;
            }
            if (System.getProperty(key) != null) {
                notes.add(key + " 已由 JVM 参数指定为 " + System.getProperty(key)
                        + "，命令行值 " + value + " 被忽略");
                continue;
            }
            System.setProperty(key, value);
            notes.add(key + " = " + value);
        }
        return notes;
    }

    // ============================================================ 启动（装配）

    /**
     * 自动化运行时，把设置文件改到一次性路径（除非调用方已显式指定）。
     *
     * <p><b>为什么必须改：</b>界面自测会主动改灵敏度、改键位、并且<u>故意写坏</u>
     * 一个设置文件来验证损坏回退。若这些动作落在玩家真实的
     * {@code %APPDATA%/SkyIsland/settings.json} 上，一次验收就会把开发者
     * （或任何在这台机器上跑过门禁的人）的手感与键位毁掉 ——
     * 而这类损坏是<b>静默</b>的：下次正常游玩时才会发现"灵敏度怎么变了"。
     *
     * <p>{@code -Dskyisland.settingsFile=<路径>} 仍然优先：验收脚本需要能指定
     * 一个可预测的位置，这样证据文件（settings.json、.corrupt-* 备份）能被直接引用。
     */
    private void redirectSettingsFileForAutomatedRun() {
        if (!config.automated()) {
            return;
        }
        if (System.getProperty("skyisland.settingsFile") != null) {
            return;
        }
        Path target = Path.of("tmp", "automated-settings", "settings.json").toAbsolutePath();
        System.setProperty("skyisland.settingsFile", target.toString());
        Log.noteWarning("设置", "自动化运行未指定 settingsFile，已改用 " + target
                + "（不会触碰玩家真实设置；如需固定位置请显式传 -Dskyisland.settingsFile=...）");
    }

    /**
     * 自动化运行时把设置复位为出厂默认并立刻落盘。
     *
     * <p><b>为什么门禁运行必须从默认起步：</b>两处断言是<u>绝对值</u> ——
     * M1 自测的"100 px 横移 → 12°"依赖灵敏度 1.0，M1.5 界面自测的
     * "灵敏度可由界面调到 1.25 / FOV 调到 80"依赖起点为 1.0 与 70.0。
     * 若继承上一次运行留下的设置，第二次运行就会失败，而失败信息
     * （"期望 1.25，实际 1.50"）看起来像产品缺陷，实际只是起点不同。
     *
     * <p>落盘是必需的：{@code CORRUPT_FALLBACK} 与 {@code SETTINGS_PERSISTENCE}
     * 阶段要读回这个文件，内存里的值不写下去就不构成证据。
     *
     * @return 是否发生了复位（供调用方补一行说明）
     */
    private boolean resetSettingsForAutomatedRun() {
        if (!config.automated()) {
            return false;
        }
        settings.resetToDefaults();
        boolean saved = SettingsStore.save(settingsLoad.path(), settings);
        Log.noteWarning("设置", "自动化运行：设置已复位为出厂默认并写出（"
                + (saved ? "成功" : "失败") + "）—— 门禁断言是绝对值，起点必须固定");
        return true;
    }

    /**
     * 本次运行的世界生成器（见 {@code M1Config#useProductWorld()} 的取舍说明）。
     */
    private com.skyisland.world.gen.WorldGenerator productGenerator() {
        return config.useProductWorld()
                ? new com.skyisland.world.gen.IslandWorldGenerator()
                : new TestWorldGenerator();
    }

    /** 出生点 x —— 与所选生成器一致（两者的出生点都在地板顶面 y = 64）。 */
    private double spawnX() {
        return config.useProductWorld()
                ? com.skyisland.world.gen.IslandWorldGenerator.SPAWN_X
                : TestWorldGenerator.spawnX();
    }

    private double spawnY() {
        return config.useProductWorld()
                ? com.skyisland.world.gen.IslandWorldGenerator.SPAWN_Y
                : TestWorldGenerator.spawnY();
    }

    private double spawnZ() {
        return config.useProductWorld()
                ? com.skyisland.world.gen.IslandWorldGenerator.SPAWN_Z
                : TestWorldGenerator.spawnZ();
    }

    private void start() {
        /*
         * 启动期互斥检查：M1 脚本化自测与 M2 战斗自测都靠"每个逻辑步返回一个意图"驱动。
         *
         * 同时开启时两者会争夺同一条意图通道（stepLogic 里 currentIntent() 只能返回一个），
         * 于是必然有一个静默地从未被喂过意图 —— 它的断言会以"阶段超时/功能缺失"的形式红掉，
         * 而失败信息指向的是产品，不是参数写错。这类"看起来像产品坏了"的假失败
         * 正是本项目 T′ 系列最想消灭的东西。
         *
         * 因此这里在<u>装配任何东西之前</u>就判错，并在日志里写明原因；异常由 main 捕获后
         * 退出码置 1（automationExitCode 之外的第二条非零退出路径）。
         */
        if (config.selfTest() && config.combatSelfTest()) {
            Log.error("启动参数冲突：-Dskyisland.selfTest 与 -Dskyisland.combatSelfTest 不能同时开启。"
                    + "两者都靠「每个逻辑步注入一个意图」驱动，同时开启会争夺同一条意图通道，"
                    + "结论互相污染。请只开启其中一个。");
            throw new IllegalStateException(
                    "selfTest 与 combatSelfTest 互斥（见日志中的说明）");
        }

        logStartupBanner();
        Log.info("[世界] 生成器 = %s（%s）", productGenerator().id(),
                config.useProductWorld() ? "产品世界：空岛 + 资源岛" : "自测夹具：测试平台");

        // ---------- 0) 用户设置（必须在窗口之前：VSync 是窗口创建参数）----------
        redirectSettingsFileForAutomatedRun();
        settingsLoad = SettingsStore.loadDefault();
        settings = settingsLoad.settings();
        boolean settingsReset = resetSettingsForAutomatedRun();
        Boolean vsyncOverride = config.vsyncOverrideOrNull();
        boolean vsync = vsyncOverride != null ? vsyncOverride : settings.vsync();
        if (vsyncOverride != null && vsync != settings.vsync()) {
            Log.noteWarning("设置", "VSync 被命令行 -Dskyisland.vsync=" + vsyncOverride
                    + " 覆盖（设置文件里是 " + settings.vsync() + "），本次运行以命令行为准");
            settings.setVsync(vsync);
        }
        logSettingsState(vsyncOverride != null);
        if (settingsReset) {
            Log.info("  （本次为自动化运行：设置已复位为出厂默认，见上方说明）");
        }

        // ---------- 1) 窗口与 OpenGL 上下文 ----------
        Log.info("正在创建窗口 %d×%d（VSync=%s, Debug context=%s）...",
                config.width(), config.height(), vsync, config.debugGL());
        // 光标模式不再由启动参数决定（M1.5 由界面状态驱动），因此创建时先给普通光标。
        window = Window.create("SkyIsland " + Version.display(),
                config.width(), config.height(),
                vsync, config.debugGL(), /* hideCursor = */ false);
        window.logEnvironment();
        window.installCallbacks(input, this::onCloseRequested);
        lastFramebufferWidth = window.framebufferWidth();
        lastFramebufferHeight = window.framebufferHeight();
        // 播种光标基线：菜单需要绝对坐标做悬停命中判定，而 GLFW 只在移动时回调 ——
        // 不播种的话"鼠标不动就点不到任何菜单项"。
        double[] cursor = window.cursorPosition();
        input.seedCursor(cursor[0], cursor[1]);

        // ---------- 2) 世界与玩家 ----------
        // ★ M4-S8a：世界不再是"一次性生成固定 4×4 并全部常驻"，而是以玩家为中心的
        //   **按需加载 + 走出半径卸载**。因此这里的 ensureAreaLoaded 换成了流式装配：
        //   attachStreaming 会以出生点为中心，把半径内的区块一次性补齐（无预算），
        //   之后的渐变由 stepLogic 每步按预算驱动。
        //
        // ★ 读档仍然排在生成之后：存档的 applySavedBlock 要求目标区块**已加载**，
        //   否则改动会被跳过并告警 —— 反过来做会得到"读档成功但改动全丢"这种
        //   最难查的静默失败。流式之后这条顺序反而更重要了：
        //   只有在半径内的区块才会在这一步被应用。
        world = new World(config.seed(), productGenerator());
        player = new Player(spawnX(), spawnY(), spawnZ());
        long genStart = System.nanoTime();
        attachStreaming(spawnX(), spawnZ());
        Log.info("[世界] 已生成 %d 个区块（流式半径 %d，保留半径 %d），耗时 %.1f ms —— %s",
                world.loadedChunkCount(), chunkStreamer.radius(), chunkStreamer.keepRadius(),
                (System.nanoTime() - genStart) / 1e6, world.statsLine());

        // ---------- 3) 存档：有则读，无则新世界 ----------
        // ★ M4-S6：把配置的游戏模式交给 SaveManager，由它按"存档优先"决定真正生效的模式。
        saveManager = new SaveManager(config.saveRoot(), config.worldName(), config.gameMode());
        // ★ M5a：时钟必须在读档**之前**建好，读档之后立刻回填时刻与天数。
        //   总时长可由 -Dskyisland.dayLengthSeconds 缩放（自测与试玩要能快进）。
        dayClock = new DayClock(DayClock.totalSecondsFromProperty());
        if (saveManager.worldExists()) {
            initialLoadResult = saveManager.loadInto(world, player);
            // ★ M5a：把存档里的时刻与天数回填给时钟。
            //   旧存档没有这两个字段（LevelMeta 里是包装类型，判据是 != null），
            //   此时 applyWorldTime 返回 false，时钟保持"白天开头、第 1 天"。
            if (saveManager.applyWorldTime(dayClock)) {
                Log.info("[昼夜] 已从存档恢复：%s", dayClock.statusLine());
            } else {
                Log.info("[昼夜] 存档没有时间字段（旧存档），本局从 %s 开始", dayClock.statusLine());
            }
            if (initialLoadResult.success()) {
                showEvent(Localization.text(Localization.MSG_WORLD_LOADED,
                        config.worldName(), initialLoadResult.chunksLoaded(),
                        initialLoadResult.blocksApplied()), 4.0);
            } else {
                showEvent(Localization.text(Localization.MSG_WORLD_LOAD_FAILED,
                        initialLoadResult.summary()), 6.0);
            }
        } else {
            showEvent(Localization.text(Localization.MSG_WORLD_STARTED,
                    config.worldName(), config.seed()), 4.0);
            Log.info("[存档] 未找到 %s，按新世界启动", saveManager.worldDirectory());
        }

        // ★ M4-S6：saveManager 就绪后补打生效模式（banner 阶段它还是 null，见 logStartupBanner 注释）。
        //   放在读档之后而不是紧跟 new：effectiveGameMode() 只读磁盘、不依赖读档结果，
        //   但这样排日志读起来是「先看到世界加载结果 → 再看到模式判定」，符合排查顺序。
        logEffectiveGameMode();

        // ---------- 3.1) M4-S7：创造面板（仅创造模式）----------
        // ★ 门控用 effectiveGameMode()（存档定死）而不是 config.gameMode()：
        //   后者只对"尚未定死"的世界有效，用它门控会让"创造存档 + survival 开关"
        //   的世界凭空多出一个创造面板 —— 而那是**用 UI 绕过了模式锁定**（PRD §4.3）。
        // ★ M4-S8b：创造能力总开关（PRD §5.2–§5.5 五项）。
        //   门控与下面的创造面板用<b>同一个</b>取值，而不是各自再判一次 ——
        //   分开判会出现"有面板但挖不动"或"能飞却没有面板"，
        //   而这两种都是"模式只生效了一半"，排查时会被当成两个独立的 bug。
        boolean creative = saveManager.effectiveGameMode() == GameMode.CREATIVE;
        player.setCreativeMode(creative);
        // ★ §4.3（2026-10-08 修订）：只有生存存档才允许"双击空格进创造会话"。
        //   创造存档把它关掉 ⇒ 那个世界的双击空格行为与本功能引入之前完全一致，
        //   也就是没有退出路径（§4.3 的核心裁定原样保留）。
        //   判据用 effectiveGameMode()（存档定死）而不是 config.gameMode()，
        //   否则"创造存档 + survival 开关"的世界会拿到退出路径 ——
        //   那是用命令行绕过模式锁定。
        player.setCreativeSessionAllowed(!creative);
        // 中途开关会话时必须同步重建创造面板，见 setCreativeSessionHandler。
        player.setCreativeSessionListener(this::onCreativeSessionChanged);
        if (creative) {
            // 面板那一行由 buildCreativeView 自己打（它也是会话进入时的落点）——
            // 两处各打一份会在创造存档启动时看到同一句话重复两次，
            // 而重复的日志会让人以为面板被建了两遍。
            buildCreativeView();
            Log.info("[创造] 本世界为创造存档：标签页常驻，五项能力已开"
                    + "（瞬时破坏 / 放置不消耗 / 免疫伤害 / 虚空不死 / 双击空格飞行）；"
                    + "双击空格只切飞行，**没有**退出路径（PRD 4.3）。");
        } else {
            creativeView = null;
            Log.info("[创造] 本世界为生存存档：双击空格可进入**本次运行内**的创造会话并直接起飞；"
                    + "该会话不写入存档（§4.3 修订），退出游戏后自动回到生存。");
        }

        // ---------- 3.5) 进程重启级的持久化校验（可选） ----------
        if (Boolean.getBoolean("skyisland.verifyPersistence")) {
            verifyPersistenceAfterRestart();
        }
        // ★ M1 时刻意不发放初始物品，理由是"M1 要验证的闭环是挖 → 拾取 → 放置，
        //   开局就送方块会让放置这一环在完全不挖掘的情况下通过"。
        //   M2 起这条口径被 PRD 5.4.1 覆盖（手枪是<b>开局装备</b>，合成归 Alpha）：
        //   现在只发枪与弹药，<b>仍然不发任何方块</b> —— 于是"挖 → 拾取 → 放置"
        //   那条闭环的证据强度一点没变，而枪战闭环被解锁了。见 3.6 节。

        // ---------- 3.6) M2 战斗系统 ----------
        // 顺序说明：EntityManager 与 CombatController 都不碰 GL，因此可以在渲染器之前建；
        // 拖到渲染器之后建只会让"战斗状态依赖渲染"这条错误的方向有机会长出来。
        entities = new EntityManager();
        combat = new CombatController(entities);
        combatFx = new CombatFxModel();

        // ---- M3 Story 10：后备弹药口径（Run Mode 配置）----
        // 正式口径是 SURVIVAL（有限后备、换弹真实从 Inventory 扣减，v2 §19-6），
        // 这是 CombatController 的字段初值，本行在默认情况下不改变任何东西。
        // 只有显式 -Dskyisland.infiniteReserve=true 才切 PROTOTYPE（无限，v2 §19-7 Debug 口径）。
        //
        // 为什么把开关放在**装配期**、而不是让 GunState 或 HUD 在运行时判断：
        //   口径是"本局怎么玩"的配置，一次会话内不该漂移；换口径还会丢弃已建的 GunState
        //   （见 CombatController#setReserveMode 的说明），放到运行时就等于允许"打到一半
        //   弹药突然不再扣了"。装配期一次定死，读的人只需要看这一行。
        //   自测路径（下面 §7）会在之后把它强制成 PROTOTYPE —— 那是自测自己的口径声明，
        //   与本开关无关，两者不冲突。
        combat.setReserveMode(GunState.ReserveMode.of(config.infiniteReserve()));

        // ---- M2.1：音频链 ----
        // ① open()：失败不是故障。机器没有声卡、原生库没链上、设备被占用，
        //    三种情形走同一条降级路径 —— 播放侧摘掉，事件照常记进 audit sink。
        //    于是日志里的 gun_fire=12 证明"触发链是通的"，而 player_hurt=0
        //    是一个可以立刻追问的事实，两者不会再混成一句"我听不到声音"。
        audio.open();
        // ② 把 AudioFeedback 串在 combatFeedback <b>前面</b>，而不是替换它。
        //    andThen 的语义是"先发声、再把同一个事件原样转发给下游"，因此粒子、
        //    曳光、HUD 提示一条都不会少。反过来若写成"替换"，症状会是
        //    "开了音频之后曳光没了" —— 而这种失效在接口语义下是完全静默的。
        audioFeedback = AudioFeedback.wrap(audio, player);
        combatListener = audioFeedback.andThen(combatFeedback);
        // 破坏反馈的接线放在这里而不是 Player 的构造里：Player 是纯逻辑类，
        // 不该知道"破坏要撒粒子"这件表现层的事。
        player.setBlockBreakListener(breakFeedback);

        // 开局装备（PRD 5.4.1：「手枪 = 开局装备，MVP 不可合成」，初始物资含手枪弹 ×24）。
        // 只发给"新世界"：读档时装备本来就在存档里，再发一次就是凭空复制。
        // 注意这不是为了好玩 —— M2 的通过标准第 1 条是"能完成一场基础枪战"，
        // 而没有枪就永远走不到开火那一步；PRD 也正是因此把合成归到 Alpha。
        if (initialLoadResult == null || !initialLoadResult.success()) {
            grantStartingGear("新世界开局装备");
        }

        // ---------- 4) 渲染器 ----------
        renderer = new Renderer();
        renderer.init(window.framebufferWidth(), window.framebufferHeight());

        // ---------- 5) 预热：把开局全部区块的网格建完 ----------
        // 放在主循环之外，因为这段耗时（16 个区块）属于"加载"，不是"运行时帧开销"。
        // 若留在循环里，前几帧会被 4 次/帧的限量消费摊开，表现为"开局有一瞬间地形不全"，
        // 而且会把加载成本混进 M1 的帧率统计。统计在预热结束后会被重置（见 stepLogic）。
        warmUpMeshes();

        // ---------- 6) 界面与设置应用 ----------
        buildUiLayer();
        applySettings();
        applyUiMode();

        if (settingsLoad.needsWriteBack()) {
            persistSettings("启动时补写（" + settingsLoad.status() + "）");
        }

        // ---------- 7) 自测 ----------
        if (config.selfTest()) {
            selfTest = new M1ScriptedSelfTest(new SelfTestHost());
        }
        if (config.uiSelfTest()) {
            uiSelfTest = new M1_5UiSelfTest(new UiSelfTestHost());
        }
        if (config.combatSelfTest()) {
            // 事件接收方在装配期就串好，而不是每步 new 一个转发对象：
            // combat.step 每个逻辑步都会被调用一次，热路径上的对象分配会被记进帧时间。
            combatSelfTest = new M2CombatSelfTest(new CombatSelfTestHost());
            // ★ M2.1：tee 的底子是<b>当前的</b> combatListener，而不是 combatFeedback。
            //   第 3.6 节已经把"音效 → 产品反馈"串成了 combatListener；这里若拿
            //   combatFeedback 当底子，等于把音频那一环从链上摘掉，
            //   自测日志里就再也看不到 gun_fire / hit_enemy 的计数。
            combatListener = M2CombatSelfTest.tee(combatListener, combatSelfTest.listener());

            // ★ M3 Story 8：战斗自测跑的是 <b>M2.1 Combat Prototype 口径</b>，
            //   因此它必须显式声明"本局是原型"（v2 §5.3 / §19-7：Debug 模式仍可无限备弹）。
            //
            //   为什么放在这里而不是让 GunState 自己兜底：M3 Survival 的正式口径是
            //   <b>有限后备并真实扣 Inventory</b>（v2 §19-6）。两种口径必须能同时存在，
            //   靠一个全局默认值表达不了；靠"自测时偷偷把默认值改回无限"则会让
            //   "正式玩法到底消耗不消耗弹药"重新变成一件看不出来的事。
            //   显式设定 → 这条选择在代码里可见、可被测试断言，正式玩法一个字都不受影响。
            combat.setReserveMode(GunState.ReserveMode.PROTOTYPE);
        }

        // ★ M4-S8a：自测一旦装配起来，就关掉区块卸载。
        //   装配顺序决定了这里必须补一次：attachStreaming 在第 2 节（世界创建时）跑，
        //   而自测对象要到第 7 节才 new 出来 —— 那时它看到的是"没有自测"。
        if (chunkStreamer != null && isAutoVerification()) {
            chunkStreamer.setUnloadEnabled(false);
        }

        loop = new GameLoop(/* frameRateCapFps = */ 0);   // 0 = 不限速，测量真实吞吐

        logProtocol();
        // GL 错误必须在装配完成后清空一次：装配期（着色器编译/Mesh 上传）产生的错误
        // 不应被算到"运行时渲染"头上，否则无法区分"一次性配置错误"与"每帧错误"。
        String preLoopGlError = GlDiagnostics.drainError();
        if (preLoopGlError != null) {
            Log.noteWarning("GL", "装配阶段检测到 GL 错误（已记入报告，不计入运行时）: " + preLoopGlError);
        }
    }

    /**
     * 发放开局装备（口径由 {@link Loadout} 决定）。
     *
     * <ul>
     *   <li>{@link Loadout#SURVIVAL}（<b>默认</b>）：手枪 ×1 + 冲锋枪 ×1 + 手枪弹 = 2 × 弹匣容量。</li>
     *   <li>{@link Loadout#DEV}：上述全部 + 步枪 ×1 + 步枪弹 = 2 × 弹匣容量
     *       + {@code DEV / TRANSITION MATERIAL KIT} 6 种。</li>
     * </ul>
     * （PRD 5.4.1 初始物资表 + v2 §10 武器表 + 2026-10-02 的步枪材料链；
     * 2026-10-03 起按口径分开，理由见 {@link Loadout} 的类注释。）
     *
     * <p><b>步枪与材料包是 2026-10-02 主理人显式放行后追加的</b>（v2 §6.2「真正落地 rifle 时
     * 必须新增相应 Recipe」）。材料包给的是<b>原始材料</b>并把矿石生成排除在本轮范围外，
     * 因此它是一次<b>显式的临时偏离</b>，理由与移除条件写在下面那段注释里。
     *
     * <p>24 发不是随手取的数：弹匣容量 12，PRD 明文写"2 个满弹匣"。
     * 写成 {@code 2 * magazineSize} 而不是字面量 24，是为了让"改弹匣容量"这件事
     * 只改一处 —— 否则数值一改，这条初始物资就悄悄变得不是"两个满弹匣"了。
     *
     * <p><b>为什么 M3 起开局就发两把枪（主理人 2026-09-27 裁决）：</b>
     * v2 §19 通过标准第 2 条写的是「SMG 可正常<b>获得</b>/持有/显示/射击/换弹/存档」，
     * 第 14 条写的是「真人试玩能明确感知两把枪的差异」。而 SMG 此前<b>只存在于
     * {@code ItemRegistry} 内部</b> —— 玩家在任何正常玩法路径上都拿不到它，
     * 这两条在事实上都不成立。合成配方在 M3 之外（见 :1226 附近注释，合成属 M4），
     * 所以在 M3 里唯一站得住的获取路径就是<b>开局装备</b>（F6 调试补给复用本方法，
     * 因此同步生效）。两把枪共用 {@code skyisland:pistol_ammo}（v2 §6.1：M3 只需要一种实际弹药）。
     *
     * <p>发完<b>不主动切换选中槽</b>：手枪会落进第一个空槽（新世界即第 1 格），
     * 玩家开局手里就是枪，这符合"开局装备"的直觉。
     * 反过来说，任何"手里必须是空手"的脚本都必须显式声明 ——
     * {@code M1ScriptedSelfTest} 的挖掘与放置阶段正是这么做的（它先切到空格）。
     *
     * <p><b>落格规则有两条，不要混：</b>枪 / 弹药走 {@link com.skyisland.player.Inventory#add}
     * （快捷栏优先，玩家一伸手就能拿到）；材料包走
     * {@link com.skyisland.player.Inventory#addToMain}（<b>只进背包</b>）。
     * 于是开局快捷栏是「手枪 手枪弹 冲锋枪 步枪 步枪弹 + 4 个空格」，
     * 材料整齐地待在背包里 —— 空格必须留着，玩家挖到的第一块石头要能落进快捷栏。
     */
    private void grantStartingGear(String reason) {
        int ammo = 2 * ItemRegistry.pistol().gun().magazineSize();
        int leftoverGun = player.inventory().add(
                ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID), 1);
        int leftoverAmmo = player.inventory().add(
                ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), ammo);
        int smgRuntimeId = ItemRegistry.runtimeIdOf(ItemRegistry.SMG_ID);
        int leftoverSmg = smgRuntimeId > 0 ? player.inventory().add(smgRuntimeId, 1) : 0;

        // ---- 步枪 + 步枪弹 + 步枪材料包（2026-10-02 主理人显式放行；2026-10-03 改为 DEV 口径专有）----
        // 与 SMG 进开局装备是同一条理由：v2 §19 的通过标准里有「可正常**获得**」这一项，
        // 而只把物品注册进 ItemRegistry 并不等于玩家拿得到它。
        //
        // ★ 但它**不属于正式 Survival 口径**（2026-10-03 主理人裁定）：
        //   M3 的正式范围是 v2 的两把枪（手枪 + 冲锋枪）。步枪是"为验证步枪链"才放行的，
        //   把一把尚未进入正式获取链的枪发进每一局正式新游戏，等于用测试内容污染玩法。
        //   因此这一段（以及下面的材料包）只在 {@link Loadout#DEV} 下执行 ——
        //   门禁与 play.bat 显式传 -Dskyisland.loadout=dev，真人试玩走的正是这条路径。
        //
        // 步枪弹按与手枪同一条规则给：2 × 弹匣容量（PRD：手枪"初始物资 = 2 个满弹匣"）。
        int rifleAmmo = 2 * ItemRegistry.rifle().gun().magazineSize();
        int leftoverRifle = 0;
        int leftoverRifleAmmo = 0;
        if (config.loadout().grantsRifle()) {
            int rifleRuntimeId = ItemRegistry.runtimeIdOf(ItemRegistry.RIFLE_ID);
            leftoverRifle = rifleRuntimeId > 0 ? player.inventory().add(rifleRuntimeId, 1) : 0;
            int rifleAmmoRuntimeId = ItemRegistry.runtimeIdOf(ItemRegistry.RIFLE_AMMO_ID);
            leftoverRifleAmmo = rifleAmmoRuntimeId > 0
                    ? player.inventory().add(rifleAmmoRuntimeId, rifleAmmo) : 0;
        }

        // ---- DEV / TRANSITION MATERIAL KIT（过渡材料包；仅 DEV 口径）----
        // ★ 名称里的 DEV / TRANSITION 是刻意写进代码的：它提醒每一个读到这段的人，
        //   这份材料包不是产品内容，而是"矿石世界生成 + 正式合成获取链"闭合之前的替代品。
        //
        // ★ 这是一处**显式的、临时的偏离**，必须写清楚：
        //   PRD 5.6.2 的配方链默认"材料从世界里挖"，但本轮范围裁定不做矿石生成
        //   （见 BlockRegistry 里铜矿石/晶体矿石的注释），因此矿石在世界上刷不出来 ——
        //   不发放就意味着"配方永远无法满足、提示永远是缺少 ×N"。
        //
        // ★ 删除条件（到点必须删，不要"先留着"）：
        //   **矿石世界生成 + 正式合成获取链闭合后删除本段。**
        //   保留会让"从零采集"这条闭环失去意义 —— 玩家不用挖就能拿到全部材料，
        //   而"能不能挖到"恰恰是那条链唯一要证明的事。
        //
        // 数量按"刚好够打完整条链"取，见 CraftingTest#theWholeRifleChainIsCraftableFromRawMaterials
        // 里用到的同一组数字，两者互为对照。
        //
        // ★ 走 addToMain 而不是 add（2026-10-02）：材料是**囤积物**，应当落在背包里，
        //   快捷栏留给玩家当场要用的枪 / 弹药。若走 add，6 种材料会先霸占快捷栏仅剩的
        //   4 个空格、后 2 种溢出到背包 —— 这个切分点取决于"材料有几种"，无法解释；
        //   而且会让 M1ScriptedSelfTest#firstBlockSlot 在挖掘之前就命中 log/iron_ore。
        //   详见 Inventory#addToMain 的 javadoc。
        String[][] materialKit = {
                {"skyisland:log", "4"},
                {"skyisland:iron_ore", "9"},
                {"skyisland:copper_ore", "3"},
                {"skyisland:coal", "20"},
                {"skyisland:sand", "4"},
                {"skyisland:crystal", "1"},
        };
        int leftoverKit = 0;
        if (config.loadout().grantsMaterialKit()) {
            for (String[] entry : materialKit) {
                int rid = ItemRegistry.runtimeIdOf(entry[0]);
                leftoverKit += rid > 0
                        ? player.inventory().addToMain(rid, Integer.parseInt(entry[1])) : 0;
            }
        }

        if (leftoverGun != 0 || leftoverAmmo != 0 || leftoverSmg != 0
                || leftoverRifle != 0 || leftoverRifleAmmo != 0 || leftoverKit != 0) {
            // 快捷栏只有 9 格，装不下就是真的装不下 —— 必须说出来，
            // 否则症状是"开局没枪"，而原因看起来像是掉落了。
            Log.noteWarning("战斗", "开局装备未能全部放入快捷栏（手枪余 " + leftoverGun
                    + " / 弹药余 " + leftoverAmmo + " / 冲锋枪余 " + leftoverSmg
                    + " / 步枪余 " + leftoverRifle + " / 步枪弹余 " + leftoverRifleAmmo
                    + " / 材料包余 " + leftoverKit
                    + "），请检查快捷栏容量。");
        }
        showEvent(Localization.text(Localization.MSG_GEAR_GRANTED, ammo, rifleAmmo), 4.0);
        if (config.loadout().grantsRifle()) {
            Log.info("[战斗] %s（口径=%s）：手枪 ×1、冲锋枪 ×1、手枪弹 ×%d（弹匣容量 %d）、"
                            + "步枪 ×1、步枪弹 ×%d（弹匣容量 %d）、DEV / TRANSITION MATERIAL KIT ×6 种",
                    reason, config.loadout().describe(), ammo,
                    ItemRegistry.pistol().gun().magazineSize(),
                    rifleAmmo, ItemRegistry.rifle().gun().magazineSize());
        } else {
            Log.info("[战斗] %s（口径=%s）：手枪 ×1、冲锋枪 ×1、手枪弹 ×%d（弹匣容量 %d）；"
                            + "无步枪、无材料包（正式 Survival 口径）",
                    reason, config.loadout().describe(), ammo,
                    ItemRegistry.pistol().gun().magazineSize());
        }
    }

    /** 建完开局所有区块的网格。必须在 GL 上下文就绪之后调用。 */
    private void warmUpMeshes() {        long started = System.nanoTime();
        int batches = 0;
        while (world.pendingMeshRebuilds() > 0 && batches < WARMUP_MESH_BATCH_LIMIT) {
            renderer.processMeshRebuilds(world);
            batches++;
        }
        warmupMeshMillis = (System.nanoTime() - started) / 1e6;
        Log.info("[渲染] 预热完成：%d 个区块网格，%d 批，耗时 %.1f ms（平均 %.2f ms/区块）",
                renderer.chunkRenderer().meshCount(), batches, warmupMeshMillis,
                world.meanMeshBuildMs());
        if (world.pendingMeshRebuilds() > 0) {
            Log.noteWarning("渲染", "预热后仍剩 " + world.pendingMeshRebuilds()
                    + " 个待重建区块（超过批次上限 " + WARMUP_MESH_BATCH_LIMIT + "），交由主循环继续消费");
        }
    }

    /** 建立界面层：状态机、三个菜单屏、设置控制器。 */
    private void buildUiLayer() {
        UiState initial = resolveInitialUiState();
        ui = new UiStateMachine(initial);
        mainMenuScreen = Menus.mainMenu(saveManager.worldExists());
        pauseMenuScreen = Menus.pauseMenu();
        settingsMenu = new SettingsMenuController(settings);
        Log.info("[界面] 菜单层已就绪：主菜单 %d 项 / 暂停 %d 项 / 设置 %d 项（键位 %d 个）",
                mainMenuScreen.size(), pauseMenuScreen.size(),
                settingsMenu.screen().size(), com.skyisland.settings.Action.values().length);
    }

    /**
     * 重建主菜单屏。
     *
     * <p><b>为什么需要它：</b>「继续游戏」是否可选取决于<u>此刻</u>磁盘上有没有存档，
     * 而这个事实会变 —— 玩家在暂停菜单里按「保存并返回主菜单」之后存档才存在。
     * 若菜单只在装配期建一次，刚存过档的玩家回到主菜单会看到"尚无存档"，
     * 而磁盘上明明有；反过来第一次启动时若菜单默认写着"继续游戏"，
     * 玩家点下去只会在日志里得到一句 WARN —— 一个可点但毫无反应的按钮，
     * 正是本里程碑明令禁止的形态。
     */
    private void refreshMainMenu() {
        mainMenuScreen.rebuild(Menus.mainMenu(saveManager.worldExists()).entries());
    }

    /**
     * 初始界面。
     *
     * <p>默认是主菜单。M1 的脚本化自测与性能测量必须从 {@code PLAYING} 开始：
     * 否则性能运行会停在主菜单里既达不到测量窗口、也永远不退出。
     *
     * <p><b>但界面自测是例外，而且必须是例外：</b>它要验证的第一件事就是
     * "启动后初始界面是主菜单"。若把它也归进"自动化运行 → PLAYING"，
     * 这个自测会在第一阶段就失败，而失败信息是"实际=Playing"——
     * 看起来像产品的前台启动流程坏了，实际是<u>测试脚手架自己把前提改掉了</u>。
     * 这类"测试前提被脚手架破坏"的失败最费时间，因此在这里显式分开。
     *
     * <p>可用 {@code -Dskyisland.startState=menu|playing|inventory} 覆盖以上全部判断。
     *
     * <p><b>为什么要 {@code inventory} 这一档（M2.2）：</b>背包界面是新增的一条渲染路径。
     * 要测它的帧开销，唯一的确定性办法是<b>让测量窗全程处于背包态</b>。
     * 若改成"跑一会儿再按 E 打开"，开关动作本身会落在测量窗内 ——
     * 首轮性能跑正是这样撞到一次 134 ms 尖峰的：那一格恰好混进了人手操作，
     * 于是"尖峰来自背包渲染"和"尖峰来自外部交互"无法区分。
     * 从背包态启动把首次开销完全压进预热窗，测量窗里就只剩稳态渲染成本。
     */
    private UiState resolveInitialUiState() {
        String explicit = config.startState();
        if (!explicit.isBlank()) {
            UiState requested = "playing".equalsIgnoreCase(explicit) ? UiState.PLAYING
                    : ("inventory".equalsIgnoreCase(explicit) ? UiState.INVENTORY : UiState.MAIN_MENU);
            Log.info("[界面] 初始状态由 -Dskyisland.startState=%s 指定 → %s", explicit, requested);
            return requested;
        }
        if (config.uiSelfTest()) {
            Log.info("[界面] 界面自测 —— 初始状态保持主菜单（这正是被验证的前提之一）");
            return UiState.MAIN_MENU;
        }
        if (config.selfTest() || config.combatSelfTest() || config.autoExit()) {
            String reason = config.selfTest() ? "脚本化自测"
                    : (config.combatSelfTest() ? "M2 战斗自测" : "性能测量（measureSeconds > 0）");
            Log.info("[界面] 自动化运行（%s）—— 初始状态直接设为 PLAYING，跳过主菜单", reason);
            return UiState.PLAYING;
        }
        return UiState.MAIN_MENU;
    }

    private void logSettingsState(boolean vsyncOverridden) {
        Log.info("");
        Log.info("==================== 用户设置 ====================");
        Log.info("  来源            : %s", settingsLoad.path());
        Log.info("  载入结果        : %s", settingsLoad.status());
        if (!settingsLoad.notes().isEmpty()) {
            for (String note : settingsLoad.notes()) {
                Log.info("  · 备注          : %s", note);
            }
        }
        for (String line : settings.summaryLines()) {
            Log.info("  %s", line);
        }
        Log.info("  视角换算        : %s", LookConfig.describe(
                InputMapper.BASE_DEG_PER_PIXEL, settings.mouseSensitivity()));
        Log.info("  VSync 来源      : %s", vsyncOverridden ? "命令行覆盖" : "设置文件");
        Log.info("==================================================");
        Log.info("");
    }

    private void logStartupBanner() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        List<String> jvmArgs = rt.getInputArguments();

        Log.info("=== SkyIsland 启动（M1.5 Front-End & Settings Shell）===");
        Log.info("版本                : %s", Version.display());
        Log.info("Java 版本           : %s", System.getProperty("java.version"));
        Log.info("Java 厂商           : %s", System.getProperty("java.vendor"));
        Log.info("Java 安装目录       : %s", System.getProperty("java.home"));
        Log.info("JVM 名称 / 版本     : %s / %s",
                System.getProperty("java.vm.name"), System.getProperty("java.vm.version"));
        Log.info("操作系统            : %s %s (%s)",
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"));
        Log.info("可用逻辑处理器      : %d", Runtime.getRuntime().availableProcessors());
        Log.info("最大堆              : %d MB",
                Runtime.getRuntime().maxMemory() / (1024 * 1024));
        Log.info("工作目录            : %s", Path.of("").toAbsolutePath());
        Log.info("源码编码 / 平台编码 : %s / %s",
                System.getProperty("file.encoding"), System.getProperty("native.encoding"));
        Log.info("实际生效的 JVM 参数 : %s",
                jvmArgs.isEmpty() ? "(无 —— 空参数集启动)" : String.join(" ", jvmArgs));
        Log.info("JVM 参数条数        : %d", jvmArgs.size());
        Log.info("--- 游戏参数 ---");
        Log.info("世界名              : %s", config.worldName());
        Log.info("Seed                : %d", config.seed());
        Log.info("存档根目录          : %s", config.saveRoot());
        Log.info("设置文件            : %s", SettingsStore.resolvePath());
        Log.info("截图目录            : %s", Path.of(config.screenshotDir()).toAbsolutePath());
        Log.info("存档开关            : %s", config.saveEnabled() ? "开启" : "关闭（skyisland.noSave=true）");
        // ★ M3 Story 10：把最终生效的弹药口径打进启动日志。
        // 为什么这行不是可有可无的装饰：口径有两处来源（配置开关 / 自测路径强制 PROTOTYPE），
        // 而"弹药不扣"在体感上与"弹药被扣了但我没注意"完全不同 —— 没有这行日志时，
        // 判断口径只能靠翻代码。启动即声明，读日志的人 3 秒就能定性。
        Log.info("后备弹药口径        : %s", config.infiniteReserve()
                ? "无限（PROTOTYPE —— -Dskyisland.infiniteReserve=true，v2 §19-7 Debug 口径）"
                : "有限（SURVIVAL —— 换弹真实扣 Inventory，v2 §19-6 正式口径）");
        // ★ 开局装备口径同样打进启动日志：与弹药口径同一条理由 ——
        //   "开局怎么没有步枪 / 怎么有一堆材料"这个问题的答案必须在日志里，而不是在代码里。
        Log.info("开局装备口径        : %s（-D%s=dev 才切 DEV）",
                config.loadout().describe(), Loadout.SYSTEM_PROPERTY);
        // ★ M4-S6：游戏模式（PRD §4.2【必须】"启动日志打印一行 游戏模式 : 生存 / 创造"）。
        //
        // ★★ 这里是本项目最贵的一次教训：banner 在 start() 的第 982 行被调用，
        //   而 saveManager 要到第 1009 行才 new 出来 —— **早 27 行**。
        //   我最初在这里打 saveManager.effectiveGameMode()，结果
        //   **三档门禁（m1/ui/m2）全部 NPE 崩溃，而 1267 条单测全绿**。
        //   原因：单测全部直接 new SaveManager，**没有任何一条走 SkyIslandGame.start()
        //   这条真实启动路径** ⇒ 启动顺序没人管。
        //   ⇒ 这不是"加个 null 判空"能解决的：判空会让这一行**静默不打印**，
        //   而"静默不打印"恰好是 §4.2【必须】要防的事。
        // ⇒ 现在的做法：banner 阶段先打**配置值**（此时确实只知道配置），
        //   并明确标注"以存档为准"；saveManager 就绪后再打一次**生效值**（见
        //   logEffectiveGameMode）。两行都在，玩家与排查者都能定性。
        Log.info("游戏模式(配置)      : %s（-D%s=creative 才切创造；实际生效值以存档为准，见下一行）",
                config.gameMode().displayName(), GameMode.SYSTEM_PROPERTY);
        Log.info("输入来源            : %s", config.selfTest() || config.combatSelfTest()
                ? "进程内脚本化意图（TR7：本机无法注入合成键盘输入）"
                : "GLFW 真实键鼠");
        Log.info("方块注册表          : %d 种", BlockRegistry.size());
    }

    /**
     * ★ M4-S6：<b>在 {@code saveManager} 就绪之后</b>打印真正生效的游戏模式。
     *
     * <p>★ <b>为什么必须分两次打印</b>：banner 阶段（{@code start()} 第 982 行）
     * 还没有 {@code saveManager}，而模式按 §4.3 又是<b>存档优先</b>的 ——
     * 那时能确定的只有"配置说了什么"。两次打印合起来才把话说完整：
     * <ul>
     *   <li>第一次（banner）：配置值 + 明确标注"以存档为准"；</li>
     *   <li>第二次（本方法）：生效值 + <b>是谁定的</b>。</li>
     * </ul>
     *
     * <p>★ <b>不要为了省一行而合并，也不要在 banner 里 null 判空</b>：
     * 判空会让这一行在旧存档上"恰好不打印"，而那正是 §4.3 争议最难查的情形。
     */
    private void logEffectiveGameMode() {
        Log.info("游戏模式(生效)      : %s（%s）",
                saveManager.effectiveGameMode().displayName(),
                describeGameModeSource());
    }

    /**
     * ★ 说明"当前生效的游戏模式是<b>谁</b>定的"（PRD §4.3 的排查入口）。
     *
     * <p><b>为什么这行不能省</b>：§4.3 裁定模式不可切换，于是
     * {@code -Dskyisland.gameMode=creative} 在新存档上生效、在旧存档上<b>静默不生效</b>。
     * 只打最终模式时，玩家看到"生存"却想不通"我明明加了参数" ——
     * 而答案（这个存档早就定死了）只存在于某个人脑子里。
     * 打印来源之后，那一行日志本身就把话说完了。
     *
     * <p>三种来源，与 {@link GameMode#resolve} 的分支一一对应：
     * <ul>
     *   <li><b>存档已定</b>：{@code level.json} 里有 {@code gameMode} → 不可改（§4.3）；</li>
     *   <li><b>命令行</b>：存档没有该字段（本轮首次创建）→ 由 {@code -Dskyisland.gameMode} 决定；</li>
     *   <li><b>缺省</b>：两者都没有 → 生存（§4.1「缺省即生存」）。</li>
     * </ul>
     */
    private String describeGameModeSource() {
        LevelMeta existing = saveManager == null ? null : saveManager.readLevelMeta();
        if (existing != null && existing.recordedGameMode() != null) {
            return "由存档定死（存档值 " + existing.recordedGameMode() + "，不可中途切换）";
        }
        if (config.gameMode() != GameMode.DEFAULT) {
            return "由命令行 -D" + GameMode.SYSTEM_PROPERTY + " 指定（本存档首次创建，将写盘）";
        }
        return "缺省即生存";
    }

    /** 打印操作说明与验收闭环清单。 */
    private void logProtocol() {
        Log.info("");
        Log.info("==================== M1.5 Front-End & Settings ====================");
        Log.info("  ---- 界面流程（规格第 1/2/3/10 条）----");
        Log.info("        启动 → 主菜单（开始游戏 / 设置 / 退出游戏）");
        Log.info("        游玩中 ESC → 暂停菜单（继续 / 设置 / 保存并返回主菜单 / 退出游戏）");
        Log.info("        暂停中 ESC → 继续游戏      设置中 ESC → 返回上一级");
        Log.info("        菜单导航：上下键 / 鼠标悬停   确认：回车 / 鼠标左键");
        Log.info("        调整数值：左右键               设置里回车 = 切换 / 进入重绑");
        Log.info("  ---- 游玩操作 ----");
        Log.info("        移动 / 跳跃 / 视角 / 挖掘 / 放置 / 切槽均为可重绑动作（默认 W/S/A/D/SPACE/鼠标左右键）");
        Log.info("        F2 截图   F3 调试 overlay   F5 存档   F9 强制重生（调试键，不可重绑）");
        Log.info("        F4 刷一只怪   F6 补满枪与弹药   F7 清空所有实体（M2 调试键，不进可重绑表）");
        Log.info("        注：M2 里弹药只有开局那 24 发，合成（手枪弹 R11）属 M4 范围；打光了按 F6");
        Log.info("        M2 的怪只有 F4 一条产生路径（尚无自然刷怪）—— 想看战斗必须按 F4");
        Log.info("  ---- 待验证闭环 ----");
        Log.info("        主菜单 → 设置 → 改灵敏度/键位 → 返回 → 开始游戏 → 生效");
        Log.info("        → ESC 暂停（物理/世界时间冻结）→ ESC 继续 → ESC 暂停");
        Log.info("        → 保存并返回主菜单 → 退出 → 重启 → 设置与键位保留");
        Log.info("  ---- 运行模式 ----");
        Log.info("        初始界面   : %s", ui.state().label());
        Log.info("        预热 %.0f 秒（不计入统计） / 正式测量 %s",
                (double) config.warmupSeconds(),
                config.measureSeconds() > 0 ? (config.measureSeconds() + " 秒后自动退出")
                        : "不限时（手动退出）");
        Log.info("        M1 脚本化自测 : %s", config.selfTest() ? "启用" : "未启用");
        Log.info("        M1.5 界面自测 : %s", config.uiSelfTest() ? "启用" : "未启用");
        Log.info("        M2 战斗自测   : %s", config.combatSelfTest() ? "启用" : "未启用");
        Log.info("        帧率上限   : 无（VSync=%s）", window.isVsyncEnabled() ? "ON" : "OFF");
        Log.info("===================================================================");
        Log.info("");
    }

    // ============================================================ 主循环

    private void loop() {
        loop.run(this);
    }

    // ============================================================ 帧钩子 1/6：关闭

    @Override
    public boolean shouldClose() {
        return window.shouldClose();
    }

    // ============================================================ 帧钩子 2/6：轮询与意图冻结

    /**
     * 轮询事件 + 处理 ESC + 冻结本帧意图。
     *
     * <p>顺序不可调换：{@code pollEvents} 先把 GLFW 事件灌进 {@link InputState}
     * （回调产生按下沿标志），之后才能读到它们。反过来做会永远差一帧的输入。
     *
     * <p><b>M1.5 的关键改动：ESC 从"电平判定"改为"全局返回键的按下沿"。</b>
     * M1 的 ESC 是"保存并退出"，用电平判定最多是重复触发同一个幂等动作；
     * M1.5 的 ESC 是"暂停 / 继续"<u>开关</u>，用电平判定会在按住的那几帧里
     * 反复切换 —— 表现为"按一下 ESC，菜单闪一下就回去了"。
     * 按下沿的清除在帧末统一进行（{@code endFrame}），因此一次按键只迁移一次。
     */
    @Override
    public void pollEvents() {
        // ★ M1.5 界面自测必须跑在<u>本帧事件消费之前</u>。
        //   它注入的是原始键事件（InputState.onKey）与鼠标位移，而 ESC 的判定就在
        //   本方法内、紧接 window.pollEvents() 之后；按下沿标志则在帧末（endFrame）
        //   统一清除。若把脚本放在 beginFrame 里注入，pollEvents 已经在同一帧跑过，
        //   这次注入的按下沿会先被帧末清掉，永远等不到下一个 ESC 消费方 ——
        //   现象是"注入的 ESC 完全无效"，而真实玩家按键（由 glfwPollEvents 在
        //   本方法内产生）却一切正常。这类"注入相位错了"的失败信息极易被误读成
        //   "ESC 暂停功能坏了"，因此顺序在这里写死。
        //   界面自测的其余注入（菜单激活、设置改值）走的是直接调用，与相位无关。
        if (uiSelfTest != null && !uiSelfTest.isFinished()) {
            uiSelfTest.onFrame();
        }

        window.pollEvents();

        // M1 / M2 的脚本化自测都注入意图、不经过输入层，因此都不参与 ESC 处理：
        // 它们是无人在场的自动化运行，一次误触 ESC 会让模拟暂停、自测永远跑不完，
        // 而那会以"自测没跑完 → 退出码 1"的形式出现，与产品缺陷无法区分。
        if (selfTest == null && combatSelfTest == null
                && InputMapper.globalBackPressed(input, settings.keyBindings())) {
            if (isRebindInProgress()) {
                // 重绑期间 ESC 属于"取消这次重绑"，不上报给界面状态机。
                // 若在这里就上报，会出现"取消重绑的同时把设置界面也关掉"。
                Log.info("[界面] ESC 被重绑流程占用（当前阶段 %s）", settingsMenu.rebind().phase());
            } else {
                ui.onEscape();
            }
        }
    }

    private boolean isRebindInProgress() {
        return ui.state() == UiState.SETTINGS && settingsMenu != null
                && settingsMenu.rebind().phase() != com.skyisland.ui.KeyRebindController.Phase.IDLE;
    }

    @Override
    public void beginFrame() {
        // 注意：M1.5 界面自测不在这里 —— 它在 pollEvents() 的最前面，
        // 因为它注入的键事件必须与"本帧的 ESC 判定"处在同一相位。

        if (selfTest != null || combatSelfTest != null) {
            // 脚本化自测模式：意图由脚本按<u>逻辑步</u>产生（见 stepLogic），这里不读 OS 输入。
            // 为什么不在这一步取：脚本的阶段预算是以逻辑步计的（60 步 = 1 秒），
            // 若改成每帧取一次，在 1000+ FPS 下"1.5 秒前进"会缩短成 0.09 秒，断言直接失真。
            frameIntent = PlayerIntent.NONE;
            return;
        }

        if (ui.state() == UiState.PLAYING) {
            // ★ 一帧一次：consumeFrameDelta() 取走即清零，多次调用会让后续逻辑步看不到位移
            PlayerIntent polled = inputMapper.poll(input, settings.keyBindings(),
                    settings.invertMouseY());

            // ★ 视角位移与滚轮是"帧级量"，交给暂存器，由本帧第一个真正执行的逻辑步
            //   一次性取走。直接把它们留在 frameIntent 里会有两个方向相反的错误：
            //     · 本帧没有逻辑步 → 位移被静默丢弃（120 Hz 渲染 / 60 Hz 逻辑下丢掉约一半）；
            //     · 本帧有多个逻辑步 → 同一份位移被施加多次（40 FPS 下视角速度翻倍）。
            //   逐逻辑步复用的 frameIntent 因此只保留"连续量"（移动、跳跃、按住攻击）。
            frameQuantities.beginFrame();
            frameQuantities.accumulate(polled.lookDeltaX(), polled.lookDeltaY(),
                    polled.hotbarScroll());
            // M2 缺陷修正：一次性语义（右键放置 / R 换弹 / 数字键选槽）也并入同一条
            // "暂存 → 本帧第一个逻辑步一次性发放"的通道。
            // 它们此前留在逐逻辑步复用的 frameIntent 里，症状与视角位移当初完全同源：
            // 实测 34455 个渲染帧只跑了 3518 个逻辑步（约 10%），
            // 于是约九成的右键点击被静默丢弃 —— 表现就是"放置方块按了没反应"。
            // 反方向（40 FPS 下一帧多个逻辑步）则会重复施加，变成一次点击放置两格。
            // v2 §7.3：左键按下沿（SINGLE 半自动开火）是同一类一次性语义，走同一条通道。
            frameQuantities.accumulateDiscrete(polled.usePressed(), polled.reloadPressed(),
                    polled.attackPressed(), polled.hotbarSlot());
            // 逐逻辑步复用的 frameIntent 里只留连续量：这里把三个帧级离散通道清掉，
            // 交给本帧第一个逻辑步经 frameQuantities.apply 一次性发放（不重复、不丢）。
            frameIntent = polled.withLook(0, 0).withScroll(0)
                    .withUsePressed(false).withReloadPressed(false).withAttackPressed(false)
                    .withHotbarSlot(-1);
            handleFrameEdges();
            // M2.2：开背包是一个"帧级边沿"动作 —— 与 F2/F3/F5 同类，
            //   不能走"逐逻辑步复用的 frameIntent"（那样在 60 Hz 逻辑下
            //   约九成的按键会被丢，症状与 M2 修过的"右键放置按了没反应"完全同源）。
            if (inventoryTogglePressed()) {
                openInventoryScreen();
            }
        } else if (ui.state() == UiState.INVENTORY) {
            // ★ 背包打开时：世界继续跑（INVENTORY.simulationRunning() == true），
            //   但玩家不再操作世界 —— 移动 / 挖掘 / 放置 / 开火 / 换弹全部吞掉。
            //   这是"单机生存"的手感：怪物照常走过来，你不能一边翻包一边打。
            frameIntent = PlayerIntent.NONE;
            input.consumeFrameDelta();
            frameQuantities.discard();
            handleInventoryInput();
        } else {
            frameIntent = PlayerIntent.NONE;
            // 菜单期间鼠标是"可见指针"而不是"锁定视角"：必须丢弃累积位移，
            // 否则在菜单里晃一圈鼠标，回到游戏会瞬间甩视角。
            input.consumeFrameDelta();
            frameQuantities.discard();
            handleMenuInput();
        }

        applyUiMode();
    }

    /** 与逻辑步解耦的帧级边沿动作：本帧内立即处理。 */
    private void handleFrameEdges() {
        if (frameIntent.toggleDebugPressed()) {
            toggleDebugOverlay();
        }
        if (frameIntent.screenshotPressed()) {
            pendingScreenshotLabel = "manual";
            showEvent("Screenshot queued", 1.5);
        }
        if (frameIntent.savePressed()) {
            pendingSave = true;
        }
        if (frameIntent.respawnPressed()) {
            // F9 要清速度、要判落脚点，必须与物理同频 → 置位，由下一个逻辑步注入意图
            pendingRespawn = true;
        }

        // ---- M2 调试快捷键（与 F2/F3/F5/F9 同类：开发期用，不进可重绑动作表）----
        // 为什么不做成可重绑动作：F4/F6/F7 不属玩家里程碑的输入契约（PRD 键位表里没有它们）。
        // 放进动作表会让"键位设置"界面多出三行玩家永远不该关心的东西，
        // 而且 M2 的可玩性验证不应该依赖一张可能被改乱的表。
        if (input.wasKeyPressed(GLFW.GLFW_KEY_F4)) {
            debugSpawnMonster();
        }
        if (input.wasKeyPressed(GLFW.GLFW_KEY_F6)) {
            grantStartingGear("调试补给");
        }
        if (input.wasKeyPressed(GLFW.GLFW_KEY_F7)) {
            int removed = entities.size();
            entities.clear();
            showEvent("已清空 " + removed + " 个实体", 2.0);
        }
    }

    /**
     * M2.1：F3 调试 overlay 的<b>唯一切换点</b>（玩家按 F3 与自测走同一条）。
     *
     * <p>它同时把 {@code EntityRenderer.setDebugHitbox} 与 overlay 状态绑在一起。
     * 该方法此前是一处<b>死接线</b>（方法齐备、渲染分支也在，但全项目零调用，
     * 于是 F3 永远看不到碰撞箱）—— 把切换逻辑收进一个命名方法，是为了让它可被自测
     * 直接调用并断言"碰撞箱开关确实跟着 F3 走"，而不是只能靠读代码相信。
     *
     * @return 切换后的 overlay 状态（true = 开）
     */
    private boolean toggleDebugOverlay() {
        hud.showDebugOverlay = !hud.showDebugOverlay;
        // 注意 setDebugHitbox 是 static：只加调用点，不改 EntityRenderer 本身。
        EntityRenderer.setDebugHitbox(hud.showDebugOverlay);
        Log.info("[HUD] F3 调试 overlay: %s（实体碰撞箱线框 %s）",
                hud.showDebugOverlay ? "开" : "关",
                hud.showDebugOverlay ? "开" : "关");
        return hud.showDebugOverlay;
    }

    /**
     * 在<b>相机水平前方</b>的候选带上刷一只近战怪（M2 调试快捷键 F4）。
     *
     * <h2>M2.1 重定义的产品语义（"刷在看得见的地方"）</h2>
     * 旧实现取的是 {@code camera.forward()} —— 一个<b>含俯仰</b>的三维单位向量。
     * 于是"前方 5 格"在水平面上的投影会随俯仰角缩短：抬头 / 低头时落点会明显漂移；
     * 更糟的是它<u>只</u>检查"脚下能不能站"，从不检查落点是否在视野内、有没有被方块挡住。
     * 结果"生成成功"与"看得见"变成两件互不保证的事 —— 这正是缺陷 B 的另一半。
     *
     * <p>新语义把落点定义成一个<b>候选搜索</b>：
     * <ol>
     *   <li>取<b>相机水平前方</b>（只用 yaw，丢掉俯仰，归一化到水平单位向量）；</li>
     *   <li>沿它取候选距离 t ∈ {4.0, 4.5, …, 8.0}（共 9 个，<b>从最近开始</b>）；</li>
     *   <li>对每个候选做竖直落地搜索（复用 {@link #findSpawnGroundY}）；</li>
     *   <li>候选必须<b>同时</b>满足四条硬条件，否则试下一个：
     *     <ul>
     *       <li><b>(a) 地面</b>：落点下方能找到可站立地面，且落点相对玩家脚底的竖直落差
     *           |Δy| ≤ {@link #SPAWN_MAX_VERTICAL_OFFSET}；</li>
     *       <li><b>(b) 头顶净空</b>：由 (a) 的 {@link Player#isStandingSpotValid} 一并保证
     *           —— 它同时要求"脚底格与头格都是空气、下方是实体"，怪才有 1.8 格空间站立，
     *           这里<u>不再重复实现</u>；</li>
     *       <li><b>(c) 视锥</b>：落点怪物中心（脚底 + {@link #SPAWN_MONSTER_CENTER_HEIGHT}）
     *           必须落在相机视锥内 —— 复用区块剔除用的 {@link Frustum}；</li>
     *       <li><b>(d) 视线</b>：从玩家眼睛到怪物中心的<b>体素射线</b>不得被实体方块挡住
     *           —— 复用挖掘用的 {@link DdaRaycaster}。</li>
     *     </ul>
     *   </li>
     *   <li>取第一个满足全部硬条件的候选（最近的，既近又在视野里）；</li>
     *   <li>一个都不满足 → <b>不生成</b>，提示玩家换个方向再试。</li>
     * </ol>
     *
     * <p><b>为什么不自己写第二套投影 / 射线数学：</b>视锥与射线在本项目各只有一处权威实现
     * （{@link Frustum} 供区块剔除、{@link DdaRaycaster} 供瞄准 / 挖掘 / 放置）。
     * 刷怪的判据若另起一套，就必然出现"区块按 A 口径剔除、刷怪按 B 口径判定"的漂移。
     *
     * @return 生成的近战怪；没有任何候选满足硬条件时<b>不生成</b>并返回 {@code null}。
     *         返回它而不是 {@code void}，是为了让 M2 战斗自测
     *         能够"复用同一条刷怪路径"并直接对这只怪断言 ——
     *         自测若另造一套刷怪代码，就不构成"玩家按 F4 时这条路是通的"的证据。
     */
    private MeleeMonster debugSpawnMonster() {
        var cam = player.camera();

        // 1) 相机水平前方：只取 yaw，丢掉俯仰分量。
        //    不用 cam.forward()：它含俯仰，水平投影会随抬头 / 低头缩短（旧缺陷的成因）。
        double yaw = Math.toRadians(cam.yawDeg());
        double fx = -Math.sin(yaw);
        double fz = -Math.cos(yaw);
        double flen = Math.hypot(fx, fz);
        if (!(flen > 1e-9)) {
            // 兜底：cos/sin 不会同时为 0，但绝不返回零向量（后面要除以模长）。
            fx = 0.0;
            fz = -1.0;
            flen = 1.0;
        }
        fx /= flen;
        fz /= flen;

        double feetX = player.position().x;
        double feetY = player.position().y;
        double feetZ = player.position().z;
        double pitchDeg = cam.pitchDeg();

        // 视锥在判据使用前用"此刻相机"的矩阵刷新一次：保证它反映的是玩家此刻看到的东西，
        // 而不是上一帧 renderWorld 留下的残留（两者在正常帧里相同，在天旋地转的边界帧里不同）。
        Frustum frustum = refreshCameraFrustum();

        // 归因计数清零，并记下"视锥判据这一次到底参没参与"。
        // 二者都是产品路径自己产生的观测值 —— 不是为了自测另算一套。
        Arrays.fill(lastSpawnRejectCounts, 0);
        lastFrustumEvaluated = frustum != null;

        int candidateCount = (int) Math.round(
                (SPAWN_CANDIDATE_MAX - SPAWN_CANDIDATE_MIN) / SPAWN_CANDIDATE_STEP) + 1;

        for (int i = 0; i < candidateCount; i++) {
            double t = SPAWN_CANDIDATE_MIN + i * SPAWN_CANDIDATE_STEP;
            double cx = feetX + fx * t;
            double cz = feetZ + fz * t;

            // (a)+(b) 地面与头顶净空：复用缺陷 B 的竖直落地搜索。
            // 每条判据只有一个执行点：布尔结果既是"是否 continue"的依据，
            // 也是成功后写进日志的那一项 —— 不再是裸字面量。
            Double groundY = findSpawnGroundY(cx, feetY, cz);
            boolean groundOk = groundY != null;
            if (!groundOk) {
                lastSpawnRejectCounts[0]++;
                continue;
            }
            double dy = groundY - feetY;
            boolean offsetOk = Math.abs(dy) <= SPAWN_MAX_VERTICAL_OFFSET;
            if (!offsetOk) {
                lastSpawnRejectCounts[1]++;
                continue;
            }

            double centerY = groundY + SPAWN_MONSTER_CENTER_HEIGHT;

            // (c) 视锥：单点测试用一个退化的 AABB（min == max）。
            // Frustum 的 p-vertex 判定是保守的（可能多判可见），对"别把该看见的刷没"这个方向是对的。
            // frustum == null ⇒ 判据整条未参与（短路），此时 frustumOk 保持 true 但【不】计入
            // 拒绝计数、也【不】在日志里写成"通过"（见 success 处的 frustumLabel）。
            boolean frustumOk = true;
            if (frustum != null) {
                frustumOk = frustum.intersectsAABB(
                        (float) cx, (float) centerY, (float) cz,
                        (float) cx, (float) centerY, (float) cz);
                if (!frustumOk) {
                    lastSpawnRejectCounts[2]++;
                    continue;
                }
            }

            // (d) 视线：眼睛 → 怪物中心的体素射线。
            boolean losOk = hasLineOfSight(cx, centerY, cz);
            if (!losOk) {
                lastSpawnRejectCounts[3]++;
                continue;
            }

            // 四条硬条件全过 → 采用这个（最近的）候选。
            MeleeMonster monster = entities.spawnMeleeMonster(cx, groundY, cz);
            debugSpawnCount++;

            // 生成提示物（任务 B）：在落点播一小簇亮色粒子，把玩家的视线拉过去。
            // 复用既有的战斗特效粒子链路 —— 容量上限与生命周期都走同一条路径，不新建渲染 pass。
            combatFx.spawnSpawnCue(cx, centerY, cz,
                    SPAWN_CUE_R, SPAWN_CUE_G, SPAWN_CUE_B, nextFxSeed());

            // 可发现性：把"相对玩家的水平距离 + 竖直落差"写进提示与日志。
            double horizontal = Math.hypot(cx - feetX, cz - feetZ);
            String vertical = dy < 0
                    ? String.format("下方 %.1f 格", -dy)
                    : String.format("上方 %.1f 格", dy);
            showEvent("已生成 " + Localization.displayName(monster.typeId())
                    + "（第 " + debugSpawnCount + " 只，存活 " + entities.aliveCount()
                    + "，相对玩家 水平 " + String.format("%.1f", horizontal)
                    + " 格 / 竖直 " + vertical + "）", 2.0);

            // 自证日志：把四个判据的输入与结果一次写全。特别保留"相机前方向与落点方向的夹角"
            // —— 它是"明明刷在前面却不在视野里"这类问题唯一能一眼看穿的数字：旧实现下它随俯仰漂移，
            // 新实现下它等于"从准星中心到怪物中心的真实偏轴角"（受视锥判据约束，必在视野内）。
            //
            // ★ 修正（M2.1 收口）：四个字段全部来自上面真实的布尔结果，不再是裸字面量 true。
            //   旧写法 "...视锥=%b 视线=%b", ..., true, true 是常量 —— 它天然无法区分
            //   "判过且可见"与"根本没判"（视锥在 frustum==null 时短路，日志照样写 true）。
            //   现在"未参与"显式写成"未参与(无渲染器)"，与"通过"彻底分开。
            Vector3d eye = player.eyePosition();
            double angleDeg = angleDegBetween(
                    cam.forward().x(), cam.forward().y(), cam.forward().z(),
                    cx - eye.x, centerY - eye.y, cz - eye.z);
            String frustumLabel = frustum == null ? "未参与(无渲染器)" : String.valueOf(frustumOk);
            Log.info("[战斗] F4 刷怪 水平前向=(%.3f, %.3f) 俯仰=%.1f° 选中距离 t=%.1f "
                            + "落点=(%.2f, %.2f, %.2f) 水平距离=%.2f 竖直落差 Δy=%.2f "
                            + "地面=%b 落差=%b 视锥=%s 视线=%b 相机前向与落点方向夹角=%.1f°",
                    fx, fz, pitchDeg, t, cx, groundY, cz, horizontal, dy,
                    groundOk, offsetOk, frustumLabel, losOk, angleDeg);
            return monster;
        }

        // 一个候选都不满足 → 绝不生成（宁可什么都不发生，也不刷一只玩家找不到的怪）。
        // 失败提示带上四条判据各自的拒绝计数：让"按了没反应"可归因（哪一条把候选全拒了）。
        Log.noteWarning("SkyIslandGame", String.format(
                "F4 刷怪失败：水平前方 %.0f–%.0f 格内没有同时满足 地面 / 落差 / 视锥 / 视线 的候选位置"
                        + "（拒绝计数 共 %d 个候选：地面=%d 落差=%d 视锥=%d%s 视线=%d）",
                SPAWN_CANDIDATE_MIN, SPAWN_CANDIDATE_MAX, candidateCount,
                lastSpawnRejectCounts[0], lastSpawnRejectCounts[1], lastSpawnRejectCounts[2],
                lastFrustumEvaluated ? "" : "(未参与:无渲染器)", lastSpawnRejectCounts[3]));
        showEvent("前方 4–8 格内没有合适的位置，转个方向再试", 2.5);
        return null;
    }

    /**
     * 用相机当前的投影 / 视图矩阵刷新 {@link Frustum} 并返回它。
     *
     * <p>视锥的唯一权威实例挂在 {@code Renderer} 上（区块剔除也用它）。这里在判据使用前
     * 用"此刻相机"的矩阵再 {@code update} 一次，保证单点测试与屏幕上正在画的东西同源。
     * 无渲染器（纯逻辑 / 自测早期）时返回 {@code null}，调用方把它当作"全部可见"处理 ——
     * 剔除失效只会多判可见，不会少判。
     */
    private Frustum refreshCameraFrustum() {
        if (renderer == null) {
            return null;
        }
        Frustum frustum = renderer.frustum();
        frustum.update(player.camera().projectionMatrix(), player.camera().viewMatrix());
        return frustum;
    }

    /**
     * 玩家眼睛到目标世界点之间是否没有实体方块遮挡（体素射线）。
     *
     * <p><b>复用挖掘用的 {@link DdaRaycaster}，不另写第二套射线数学。</b>
     * 命中任意非空气方块即视为"被挡住"。射线终点取两点距离本身当上限：
     * 目标点在空中，只要中途没有方块，DDA 自然返回 {@code null}。
     */
    private boolean hasLineOfSight(double tx, double ty, double tz) {
        if (world == null) {
            return true;
        }
        Vector3d eye = player.eyePosition();
        double dx = tx - eye.x;
        double dy = ty - eye.y;
        double dz = tz - eye.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1e-6) {
            return true;
        }
        losDirection.set(dx, dy, dz);
        return DdaRaycaster.castSolid(world, eye, losDirection, dist) == null;
    }

    /** 两个三维向量的夹角（度）；任一为零向量时返回 0。 */
    private static double angleDegBetween(double ax, double ay, double az,
                                          double bx, double by, double bz) {
        double la = Math.sqrt(ax * ax + ay * ay + az * az);
        double lb = Math.sqrt(bx * bx + by * by + bz * bz);
        if (!(la > 1e-9) || !(lb > 1e-9)) {
            return 0.0;
        }
        double c = (ax * bx + ay * by + az * bz) / (la * lb);
        c = Math.max(-1.0, Math.min(1.0, c));
        return Math.toDegrees(Math.acos(c));
    }

    /**
     * M2.1 缺陷 B：把建议落点向下吸附到最近的"能站住"的地面。
     *
     * <p>从 {@code y} 起逐格向下，返回第一个 {@link Player#isStandingSpotValid}
     * 为真的高度；最多向下 {@link #SPAWN_GROUND_SEARCH_DEPTH} 格。找不到返回 {@code null}。
     *
     * <p><b>为什么不复用 {@code Player.findNearestStandable}：</b>那个方法是
     * 同一 y 平面上的水平螺旋搜索（见其 javadoc），<u>没有竖直方向</u>；
     * 用它正是缺陷 B 的成因。本方法补的正是那段竖直搜索。
     */
    private Double findSpawnGroundY(double x, double y, double z) {
        int startY = (int) Math.floor(y);
        for (int dyy = 0; dyy <= SPAWN_GROUND_SEARCH_DEPTH; dyy++) {
            double candidate = startY - dyy;
            if (player.isStandingSpotValid(world, x, candidate, z)) {
                return candidate;
            }
        }
        return null;
    }

    // ============================================================ 菜单输入（M1.5）

    /**
     * 菜单输入处理。
     *
     * <p>三条优先级规则，顺序不可调换：
     * <ol>
     *   <li><b>等待输入</b>：任何按键（含 ESC，ESC 由重绑控制器解释为"取消"）
     *       都被当作"要绑定的键"，此时不做任何导航 ——
     *       否则玩家想绑 W，结果光标同时往下跳了一格；</li>
     *   <li><b>冲突确认</b>：只认回车（替换）与 ESC（取消），
     *       其余按键一律忽略 —— 这是一个"必须明确回答"的问题；</li>
     *   <li><b>常规导航</b>：上下移动、滚轮移动、鼠标悬停与点击、左右调值。</li>
     * </ol>
     */
    private void handleMenuInput() {
        MenuScreen screen = activeMenu();
        if (screen == null) {
            return;
        }
        ensureMenuLayout(screen);
        MenuNav nav = inputMapper.pollMenuNav(input);

        if (ui.state() == UiState.SETTINGS) {
            if (settingsMenu.rebind().isWaiting()) {
                InputBinding captured = captureAnyPressedInput();
                if (captured != null) {
                    applySettingsEffect(settingsMenu.onRebindCapture(captured));
                }
                return;
            }
            if (settingsMenu.rebind().isResolvingConflict()) {
                if (nav.confirm()) {
                    applySettingsEffect(settingsMenu.onConfirmReplace());
                } else if (nav.back()) {
                    settingsMenu.onCancelRebind();
                    Log.info("[界面] 冲突确认已取消（ESC）");
                }
                return;
            }
        }

        // ---- 常规导航 ----
        if (nav.down()) {
            screen.moveDown();
        }
        if (nav.up()) {
            screen.moveUp();
        }
        int scroll = nav.scrollSteps();
        if (scroll > 0) {
            screen.moveDown();
        } else if (scroll < 0) {
            screen.moveUp();
        }

        if (nav.hasPointer()) {
            int hovered = menuLayout.hitTestAny(nav.mouseX(), nav.mouseY(), screen.size());
            if (hovered >= 0) {
                screen.hover(hovered);
            }
            if (nav.clicked()) {
                if (hovered >= 0 && screen.entries().get(hovered).selectable()) {
                    activateEntry(screen.entries().get(hovered).id());
                } else {
                    Log.info("[界面] 点击落在菜单行之外，忽略");
                }
            }
        }

        if (nav.hasHorizontal() && ui.state() == UiState.SETTINGS) {
            applySettingsEffect(settingsMenu.adjust(screen.selectedId(), nav.right() ? 1 : -1));
        }

        if (nav.confirm()) {
            activateEntry(screen.selectedId());
        }
    }

    // ============================================================ M2.2：背包界面

    /**
     * 背包键（{@link Action#INVENTORY}）本帧是否被按下。
     *
     * <p><b>为什么走可重绑的动作表而不是直接读 {@code GLFW_KEY_E}：</b>
     * 默认键是 {@code E}，但玩家可以改 —— 直接读键码会让"重绑生效"变成"要改逻辑代码"。
     *
     * <p><b>它在 M1.5 时的标注是"界面消费方在 M3"</b>（那行标注的存在正是为了避免
     * "能改键"被误读成"能用"）。M2.2 给了它真实消费方，因此那行标注<b>已同时摘掉</b>
     * （见 {@link Action#INVENTORY} 的注释）—— 否则设置界面会显示 {@code "Inventory [M3]"}，
     * 等于告诉玩家"这个键要等下个版本"，而它现在就有用。
     */
    private boolean inventoryTogglePressed() {
        return InputMapper.actionPressed(input, settings.keyBindings(), Action.INVENTORY);
    }

    private void openInventoryScreen() {
        if (ui.openInventory()) {
            // ★ 开背包的这一刻必须重新评估一遍每条配方：
            //   玩家在打开界面之前刚挖到的那块矿石，决定了"哪些行能合"。
            craftingPanel.refresh(player.inventory());
            audio.play(AudioEvent.UI_OPEN);
            Log.info("[界面] 打开背包");
        }
    }

    /**
     * 合成第 {@code row} 行，并<b>无论如何都给出反馈</b>。
     *
     * <p><b>为什么三种结果都要出声 + 出字：</b>裁定第 7 条禁止"点了按钮没反应"。
     * 而"没反应"最常见的形态不是代码没写，而是"写了但只在成功分支给反馈" ——
     * 玩家点了缺料的那一行，屏幕一动不动，于是他会一直点下去。
     * 缺料时把"缺少 铁锭 ×3"直接写在提示条上，反应就提前发生了。
     *
     * <p><b>扣料与出货全部由 {@link com.skyisland.craft.Crafting#craft} 裁决</b>，
     * 本方法一行都不碰背包 —— 界面层自己算一次扣料，就等于给"材料扣了、产物没了"
     * 这种事故开了一扇门（见 {@link CraftingPanel} 的类注释）。
     */
    private void craftRow(int row) {
        Crafting.Outcome outcome = craftingPanel.craft(row, player.inventory());
        if (outcome == null) {
            return;
        }
        switch (outcome.result()) {
            case CRAFTED -> audio.play(AudioEvent.UI_MOVE);
            case MISSING_INGREDIENTS -> {
                audio.play(AudioEvent.UI_DENIED);
                showEvent(Crafting.describeMissing(outcome), 3.0);
            }
            case NO_ROOM -> {
                audio.play(AudioEvent.UI_DENIED);
                showEvent(Localization.text(Localization.MSG_CRAFT_NO_ROOM), 3.0);
            }
        }
    }

    /**
     * 关闭背包，并处理"手上还拿着东西"这一情况。
     *
     * <p><b>为什么必须先归位再切状态：</b>若先切回 PLAYING，
     * 那么"手上那堆去哪了"就变成了一个无人负责的问题 ——
     * 玩家会看到物品凭空消失，而日志里什么都没有。
     * {@link InventoryInteraction#closeScreen} 的语义是"塞得下就塞回，塞不下才丢弃并如实说"，
     * 丢弃是有意为之（M2 没有掉落物实体，物品不得凭空复制），但它<b>必须可见</b>。
     */
    private void closeInventoryScreen() {
        int held = player.inventory().cursorStack().count();
        InventoryInteraction.Outcome outcome = InventoryInteraction.closeScreen(player.inventory());
        if (outcome.result() == InventoryInteraction.Result.SHIFT_BLOCKED) {
            // 丢弃是唯一"物品真的少了"的情形，因此它必须同时有视觉与听觉两条通道。
            audio.play(AudioEvent.UI_DENIED);
            showEvent(Localization.text(Localization.MSG_INV_DROPPED_ON_CLOSE, held), 3.0);
        }
        if (ui.closeInventory()) {
            audio.play(AudioEvent.UI_CLOSE);
            inventoryModel.visible = false;
            inventoryModel.hoverSlot = -1;
            // ★ M4-S7：关背包时把标签页拉回背包。
            //   否则玩家在创造页按 E 关闭、再按 E 打开时，面板直接出现在创造页 ——
            //   而多数玩家 reopen 的目的是"看一眼背包还剩多少材料"。
            //   症状对照：不重置的话，第一次关背包就再也回不到 36 格那页（除非先点标签）。
            inventoryModel.activeTab = InventoryRenderModel.Tab.BACKPACK;
            inventoryModel.hoverCreativeEntry = -1;
            inventoryModel.hoverTab = -1;
            Log.info("[界面] 关闭背包");
        }
    }

    /**
     * 背包打开时的鼠标交互。
     *
     * <p><b>坐标换算是这里最关键的一行。</b>输入层与 {@link InventoryLayout} 的口径不同：
     * 前者是<b>窗口坐标</b>（GLFW 回调原始值），后者按<b>帧缓冲像素</b>命中。
     * DPI = 1 时两者恰好相等，问题不会暴露；一旦窗口尺寸 ≠ 帧缓冲尺寸，
     * "点第 3 格却命中第 12 格"就是真 bug —— 而它在开发机上永远复现不了。
     *
     * <p><b>为什么读 {@code input.cursorPosition()} 而不是 {@code window.cursorPosition()}：</b>
     * 这两个来源在正常使用时数值相同，但性质不同 —— 后者每次都向 GLFW 现问一次，
     * 前者是<b>已跟踪的输入状态</b>（菜单命中判定用的就是它），并在光标模式切换时由
     * {@code applyUiMode} 用 GLFW 的值重新播种。用同一个来源的收益是：
     * <ul>
     *   <li>菜单与背包的"光标在哪"不会再出现两个真相（一个来自 GLFW、一个来自输入层）；</li>
     *   <li>它可以被<b>进程内脚本化注入</b>（{@code InputState#seedCursor}）——
     *       于是"点某一格"这件事能在自测里被真实地走一遍，
     *       而不是把坐标换算这段胶水留在无人验证的角落（TR7 的同一条原则：
     *       本机合成键鼠送不到窗口，凡是能脚本化的状态都应当能从进程内播种）。</li>
     * </ul>
     */
    private void handleInventoryInput() {
        if (inventoryTogglePressed()
                || InputMapper.globalBackPressed(input, settings.keyBindings())) {
            closeInventoryScreen();
            return;
        }

        int fbWidth = window.framebufferWidth();
        int fbHeight = window.framebufferHeight();
        double[] cursor = input.cursorPosition();
        double[] fb = InventoryLayout.windowToFramebuffer(cursor[0], cursor[1],
                fbWidth, fbHeight, window.windowWidth(), window.windowHeight());
        inventoryModel.mouseX = fb[0];
        inventoryModel.mouseY = fb[1];

        // ★ M4-S7：门控在这里再判一次，而不是只靠启动时建不建 creativeView。
        //   单点门控的失败模式是"启动时是生存、中途被改成创造"（本项目当前不允许，
        //   但 §4.3 的锁定语义正是在防这件事）—— 那时 tabs 仍是 1，
        //   于是点"创造"什么也不会发生，而画面上确实只有「背包」一个标签。
        //   反过来（tabs==2 但 view 为 null）则会让标签条点得动、点进去是空白。
        inventoryModel.tabs = creativeView == null ? 1 : 2;
        if (inventoryModel.tabs == 1 && inventoryModel.activeTab != InventoryRenderModel.Tab.BACKPACK) {
            // 无面板可显示时把标签页**拉回**背包，而不是保持一个画不出来的页。
            // 症状对照：保持 CREATIVE 会让内容区整片空掉，且底部提示写着"单击取满一组"——
            // 一个没有任何格子可点的界面，却明确告诉你这里可以取东西。
            inventoryModel.activeTab = InventoryRenderModel.Tab.BACKPACK;
        }

        InventoryLayout layout = renderer.inventoryRenderer()
                .ensureLayout(fbWidth, fbHeight, craftingPanel.size(),
                        inventoryModel.tabs,
                        creativeView == null ? null : creativeView.rows());

        // ---- 标签页（先判标签，后判内容）----
        // ★ 顺序不是随意的：标签条与内容区不重叠，因此两条通道可以独立判定；
        //   但"点了标签就切页"必须**不穿透**到内容区 —— 标签条紧贴内容区上沿，
        //   若不 return，一次点标签会同时"切页 + 点中切页后那一页的第一格"。
        int hoveredTab = layout.hitTestTabAny(inventoryModel.mouseX, inventoryModel.mouseY);
        inventoryModel.hoverTab = hoveredTab;
        boolean pressed = input.wasMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_1);
        if (hoveredTab >= 0) {
            if (pressed) {
                InventoryRenderModel.Tab target = InventoryRenderModel.Tab.of(hoveredTab);
                if (target != null && target != inventoryModel.activeTab) {
                    inventoryModel.activeTab = target;
                    // ★ 换页后必须清掉另一页的悬停态：hoverSlot 描述的是"背包第几格"，
                    //   拿到创造页上毫无意义，而它会顺带触发背包 tooltip 画在创造面板上。
                    inventoryModel.hoverSlot = -1;
                    inventoryModel.hoverCraftRow = -1;
                    inventoryModel.hoverCreativeEntry = -1;
                    audio.play(AudioEvent.UI_MOVE);
                }
            }
            return;
        }

        // ---- 创造面板（仅创造页）----
        if (inventoryModel.creativePanelActive()) {
            int hoveredEntry = layout.hitTestCreativeAny(inventoryModel.mouseX, inventoryModel.mouseY);
            inventoryModel.hoverCreativeEntry = hoveredEntry;
            if (hoveredEntry >= 0 && pressed) {
                takeFromCreativePalette(hoveredEntry);
            }
            return;
        }
        // 非创造页：创造面板的悬停态必须清零，否则切回背包时它还留着
        // （症状：背包页左上角凭空高亮一个不存在的格子）。
        inventoryModel.hoverCreativeEntry = -1;

        int hovered = layout.hitTestAny(inventoryModel.mouseX, inventoryModel.mouseY);
        inventoryModel.hoverSlot = hovered;
        int hoveredRow = layout.hitTestCraftAny(inventoryModel.mouseX, inventoryModel.mouseY);
        inventoryModel.hoverCraftRow = hoveredRow;

        // ---- 合成区优先 ----
        // 两个区域在几何上不重叠，因此"优先"不是为了解决冲突，而是为了让
        // "点在合成栏上"这件事不会被下面的"没点中格子就返回"提前吞掉。
        if (pressed && hoveredRow >= 0) {
            craftRow(hoveredRow);
            return;
        }
        if (hovered < 0 || !pressed) {
            return;
        }
        // ★ 与 InputMapper 的 sneak 不同，这里**刻意保持直读**：
        //   它是鼠标操作的修饰键（Shift+左键 = 快速搬运），不是"游戏动作"。
        //   放进键位表会让"改 CROUCH"顺手把背包搬运也改了 —— 而那两件事
        //   在中文 Windows 上面对的是同一个输入法占用问题（Shift 会被中英切换吃掉），
        //   所以玩家若要绕开，他会在设置里改 CROUCH，然后发现背包搬运还是失灵。
        //   明确写出这个取舍，免得后来者以为它是漏改。
        boolean shift = input.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)
                || input.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
        InventoryInteraction.Outcome outcome = shift
                ? InventoryInteraction.shiftClick(player.inventory(), hovered)
                : InventoryInteraction.leftClick(player.inventory(), hovered);

        // ★ 声音必须按「结果」分派，而不是按「点了」分派。
        //   若把 UI_MOVE 挂在"鼠标左键按下"上，对着空格点一下也会响 ——
        //   玩家据此会以为东西被搬动了。失败与成功在画面上的差别有时只有一个高亮的明暗，
        //   听觉通道承担的就是"立刻知道自己做了什么"这件事。
        switch (outcome.result()) {
            case PICKUP_ALL, PLACE_ALL, MERGE_PARTIAL, MERGE_FULL, SWAP, SHIFT_MOVED ->
                    audio.play(AudioEvent.UI_MOVE);
            case SHIFT_BLOCKED -> {
                // 搬运失败必须说出来：原子性保证了原格不变，
                // 于是"点了没反应"和"背包满了"在画面上长得一模一样。
                showEventDeduped("inv_move_blocked",
                        Localization.text(Localization.MSG_INV_MOVE_BLOCKED), 2.0);
                audio.play(AudioEvent.UI_DENIED);
            }
            case NONE, REJECTED -> {
                // 空格上点一下、或越过面板边界点击：既没搬动也没出错，不该有任何声音。
            }
        }
        // 一次搬运之后背包内容变了（取走 / 放下 / 合并 / 交换都会改变"我有什么"），
        // 因此合成栏必须重新评估 —— 否则会出现"刚把铁矿放进背包，合成栏还显示缺料"。
        craftingPanel.refresh(player.inventory());
    }

    /**
     * ★ M4-S7：从创造面板取一组方块到手上（PRD §5.1「取出」行）。
     *
     * <p>规格原文：「单击取满一组（64）；<b>不占用背包格</b>，直接从面板进手持」。
     * 因此落点是 {@link Inventory#setCursorStack}（光标堆），
     * <b>不是</b> {@code add()} —— 用 {@code add()} 会让 64 个方块占掉 1~2 格背包，
     * 而规格明确说不占格；更糟的是玩家会以为"背包满了就不能拿了"，
     * 于是创造模式被误当成生存模式。
     *
     * <p>★ <b>手上已有东西时必须拒绝并说出来，不能静默覆盖</b>：
     * 静默覆盖等于凭空销毁玩家手上的物品，而 M2 已经定过一条铁律 ——
     * 物品不得凭空消失或复制（无掉落物实体时尤其致命）。
     * 提示走 {@code showEventDeduped}（与背包搬运失败同一条通道）。
     */
    private void takeFromCreativePalette(int entryIndex) {
        if (creativeView == null) {
            return;
        }
        CreativePalette.Entry entry = creativeView.at(entryIndex);
        if (entry == null) {
            return;
        }
        Inventory inv = player.inventory();
        ItemStack held = inv.cursorStack();
        if (!held.isEmpty() && held.itemRuntimeId() != entry.itemRuntimeId()) {
            // 同种可以叠满后继续取满（"取满一组"= 目标 64，不是"再加 64"）。
            // 不同种则拒绝：手上那一堆是玩家刚搬出来的东西，覆盖它就是销毁。
            showEventDeduped("creative_hands_busy",
                    Localization.text(Localization.MSG_CREATIVE_HANDS_BUSY), 2.0);
            audio.play(AudioEvent.UI_DENIED);
            return;
        }
        int already = held.isEmpty() ? 0 : held.count();
        int want = Math.max(already, entry.stackSize());
        inv.setCursorStack(ItemStack.of(entry.itemRuntimeId(), want));
        audio.play(AudioEvent.UI_MOVE);
    }

    /** 当前界面下的菜单屏；游玩中没有菜单。 */
    private MenuScreen activeMenu() {
        return switch (ui.state()) {
            case MAIN_MENU -> mainMenuScreen;
            case PAUSED -> pauseMenuScreen;
            case SETTINGS -> settingsMenu == null ? null : settingsMenu.screen();
            case PLAYING -> null;
            case INVENTORY -> null;
        };
    }

    private void ensureMenuLayout(MenuScreen screen) {
        MenuLayout.Style style = ui.state() == UiState.MAIN_MENU
                ? MenuLayout.Style.COVER : MenuLayout.Style.PANEL;
        menuLayout = MenuLayout.compute(window.framebufferWidth(), window.framebufferHeight(),
                screen.entries(), style);
    }

    /**
     * 从"本帧按下过的输入"里取一个绑定。
     *
     * <p>顺序是 <b>先键盘后鼠标</b>，且返回的是<u>码值最小</u>的那个键：
     * 一帧内同时按下多个键时总有先后，但按下沿数组不记时序，
     * 因此需要一个确定性规则 —— 否则"同一帧按了两个键"会随机绑上其中一个。
     */
    private InputBinding captureAnyPressedInput() {
        int key = input.firstPressedKeyThisFrame();
        if (key >= 0) {
            return InputBinding.key(key);
        }
        int mouseButton = input.firstPressedMouseButtonThisFrame();
        if (mouseButton >= 0) {
            return InputBinding.mouse(mouseButton);
        }
        return null;
    }

    /** 按当前界面状态执行一次菜单项激活。<b>鼠标点击与回车走的是同一个入口。</b> */
    /**
     * 主菜单「新建世界」：把当前会话重置成一局全新开局。
     *
     * <p><b>它做什么（顺序不可换）：</b>
     * <ol>
     *   <li>释放全部区块网格，再用同一个 seed 与确定性地形函数重建 {@link World}
     *       —— 玩家挖掉 / 放下的方块全部复位，拿到的是<u>未被改动过</u>的原始地形；</li>
     *   <li>玩家回到出生点：位置、速度、视角、保险位置、背包 36 格、快捷栏选中、死亡计数一起清零；</li>
     *   <li>清空实体与枪械运行时状态（上一局刷出来的怪、打空的弹匣都不该跟过来）；</li>
     *   <li>重发开局装备，并<u>立刻写盘</u>。</li>
     * </ol>
     *
     * <p><b>为什么换 World 之前必须先 {@code disposeAll()}：</b>
     * {@code ChunkRenderer} 用 {@code IdentityHashMap<Chunk, ChunkMesh>} —— 网格是按
     * <b>Chunk 对象的身份</b>索引的，不是按区块坐标。直接 {@code new World(...)}，
     * 新区块与原对象引用不同，旧网格既不会被覆盖也不会被回收：
     * {@code meshCount()} 会从 16 涨到 32，16 份 GL buffer 永久泄漏，
     * 而画面上看起来完全正常。这类缺陷只在长会话里发作，所以在这里一次性堵掉，
     * 而不是等它变成"玩久了会掉帧"再查。
     *
     * <p><b>为什么必须重建 World，而不是"清一清玩家状态"：</b>
     * 方块改动是写在 World 里的（读档的 {@code applySavedBlock} 也往它里面写）。
     * 只重置玩家，玩家会站在自己上一局挖出来的坑里，而菜单上写着"新建世界"——
     * 界面在说假话，且没有任何断言会因此变红。
     *
     * <p><b>为什么必须立刻写盘：</b>不写的话这一次"新建世界"只活在内存里，
     * 玩家退出再进，磁盘上的旧进度会原封不动回来 ——
     * 那是"按了新建世界、重启后旧世界复活"的静默矛盾。
     *
     * <p><b>它刻意不做的事：</b>不生成新地形（地形生成属 M2.2 禁止范围）。
     * 因此 seed 与地形布局保持不变，改变的是"这一局的进度"。
     * 这条边界写进了 {@link Localization#MSG_WORLD_RESET} 的玩家可见文案里，
     * 不需要读代码就能知道。
     */
    private void startNewWorld() {
        // ① 先释放旧网格。理由见上：IdentityHashMap 按对象身份索引，换 World 等于换键。
        renderer.chunkRenderer().disposeAll();

        // ② 全新世界 + 以出生点为中心把流式半径内的区块补齐
        world = new World(config.seed(), productGenerator());
        // ★ M5a：新世界从"白天开头、第 1 天"开始 —— 与首次启动同一时刻。
        //   不重置的话，玩家在第 3 天夜里新建世界会直接站在几乎全黑的夜里，
        //   看上去像"世界坏了"。
        dayClock = new DayClock(dayClock.totalSeconds());
        // ★ "新建世界"也要重装核心：世界刚被清空重建，
        //   而 attachStreaming 内部的 attachResourceCores 只在装配期跑一次。
        //   不重装的话，玩家新建世界后资源核心就停止再生了 ——
        //   而症状是"核心还在、就是不长矿"，极难归因（它看起来像参数问题）。
        coreRegen = null;
        attachStreaming(TestWorldGenerator.spawnX(), TestWorldGenerator.spawnZ());
        warmUpMeshes();

        // ③ 玩家复位。刻意不 new 一个 Player：别处（音频轮询、反馈链、自测宿主）
        //    都持有这个实例的引用，换实例会让它们悄悄指向一个"上一局的玩家"。
        //    yaw/pitch 取 0/0 不是随手写的 —— 那正是 Camera 的默认朝向，
        //    与 new Player(...) 的初始朝向逐位一致，因此"新建世界"看到的画面
        //    与进程首次启动时完全相同。
        player.applyLoadedState(spawnX(), spawnY(), spawnZ(), 0.0, 0.0, null, List.of(), 0, 0);
        player.healFull();
        player.camera().clearRecoil();

        // ④ 实体与枪械运行时状态
        entities.clear();
        combat.resetGuns();

        // ⑤ 开局装备 + 立刻落盘
        initialLoadResult = null;
        grantStartingGear("新建世界开局装备");
        SaveResult result = performSave("新建世界");
        if (result != null && !result.success()) {
            Log.noteWarning("存档", "新建世界后的初始写盘失败：" + result.summary()
                    + "（世界仍在内存中，退出时还会再存一次）");
        }
        showEvent(Localization.text(Localization.MSG_WORLD_RESET), 5.0);
    }

    // ============================================================ M4-S8a 区块流式

    /**
     * 给（可能是刚新建的）世界接上流式加载与卸载，并以给定坐标为中心把半径内补齐。
     *
     * <p><b>三件必须同时接上的事</b>，少一件都会得到"能跑但慢慢坏"的失效：
     * <ol>
     *   <li>{@link World.ChunkUnloadListener} —— 卸载时释放 GPU 网格并把脏区块落盘；</li>
     *   <li>{@link World.ChunkDeltaSource} —— 区块生成时立刻回放它自己的存档差异；</li>
     *   <li>{@code ChunkStreamer} 本身 —— 按玩家位置维护"该加载哪些"。</li>
     * </ol>
     * 三者都挂在 {@code World} 上而不是散在本类的各个调用点，是因为
     * "加载一个区块"这件事只有 {@link World#getOrLoadChunk} 一个入口 ——
     * 钩子挂在那里，任何绕过清理的路径都不存在。
     */
    private void attachStreaming(double centerX, double centerZ) {
        world.setChunkUnloadListener(this::onChunkUnloaded);
        world.setChunkDeltaSource(this::applySavedChunkDelta);
        chunkStreamer = new ChunkStreamer(world, streamRadius());
        // ★ 自测期间不卸载：脚本的断言读的是 World 的当前状态，
        //   让区块在它脚下消失会把"断言红"变成"偶发红"，而偶发红没有诊断价值。
        chunkStreamer.setUnloadEnabled(!isAutoVerification());
        chunkStreamer.reset(centerX, centerZ);
        attachResourceCores();
    }

    /**
     * 装配资源核心慢速再生（PRD 4.6「三重防软锁」的第② 条）。
     *
     * <p>★ <b>只在产品世界装配</b>：自测跑的是 {@code TestWorldGenerator}，
     * 那块平台上恰好也放了一个 resource_core（用于验证"不可破坏"拒绝路径）。
     * 若那里也驱动再生，自测会周期性地长出矿石 ——
     * 而 M1 自测断言的"挖掉之后仍然是空气"这类状态会被后台动作改写。
     * <p>⇒ 判据走 {@link M1Config#useProductWorld()}，与生成器同一道闸门。
     * 不写"selfTest == null"这种散判：新增自测时它会被漏掉
     * （本项目为此专门写过守卫，见 S8A 报告 §7）。
     */
    private void attachResourceCores() {
        if (!config.useProductWorld()) {
            return;
        }
        coreRegen = new ResourceCoreRegen(world);
        com.skyisland.world.gen.IslandWorldGenerator gen =
                (com.skyisland.world.gen.IslandWorldGenerator) world.generator();
        for (com.skyisland.world.gen.IslandWorldGenerator.Island island
                : com.skyisland.world.gen.IslandWorldGenerator.ISLANDS) {
            if (island.kind() == com.skyisland.world.gen.IslandWorldGenerator.Kind.MAIN) {
                continue;   // 主岛无资源核心（PRD 4.6 表只列 4 座资源岛）
            }
            coreRegen.register(new ResourceCoreRegen.IslandCore(island.key(),
                    regenKindOf(island.kind()),
                    island.centerX(),
                    Coords.WORLD_SURFACE_BLOCK_Y + 1,
                    island.centerZ()));
        }
        Log.info("[世界] 资源核心再生已装配：%d 个核心（石/森/金/晶 四岛）", coreRegen.coreCount());
    }

    private static ResourceCoreRegen.IslandKind regenKindOf(
            com.skyisland.world.gen.IslandWorldGenerator.Kind kind) {
        return switch (kind) {
            case MAIN -> ResourceCoreRegen.IslandKind.MAIN;
            case STONE -> ResourceCoreRegen.IslandKind.STONE;
            case FOREST -> ResourceCoreRegen.IslandKind.FOREST;
            case METAL -> ResourceCoreRegen.IslandKind.METAL;
            case CRYSTAL -> ResourceCoreRegen.IslandKind.CRYSTAL;
        };
    }

    /** 加载半径；{@code -Dskyisland.chunkRadius=N} 可覆盖（调参与压测用）。 */
    private static int streamRadius() {
        int radius = Integer.getInteger("skyisland.chunkRadius", ChunkStreamer.DEFAULT_RADIUS);
        if (radius < 1) {
            Log.noteWarning("世界", "skyisland.chunkRadius=" + radius + " 非法，回落到 "
                    + ChunkStreamer.DEFAULT_RADIUS);
            return ChunkStreamer.DEFAULT_RADIUS;
        }
        return radius;
    }

    /**
     * 区块被卸载：释放它的 GPU 网格，并把玩家在里面的改动落盘。
     *
     * <p><b>两件事的顺序不能反、也不能只做一件：</b>
     * 只释放网格 → 走回来时挖掉的坑又长回来了（增量没写）；
     * 只落盘 → 显存一路涨，跑十分钟才看得出来。
     */
    private void onChunkUnloaded(Chunk chunk) {
        if (renderer != null) {
            renderer.chunkRenderer().releaseMesh(chunk);
        }
        if (saveManager != null && chunk.isSaveDirty()) {
            SaveResult r = saveManager.saveChunk(world, chunk);
            if (!r.success()) {
                Log.noteWarning("存档", "区块 (" + chunk.cx() + "," + chunk.cz()
                        + ") 卸载前落盘失败：" + r.summary());
            }
        }
    }

    /** 是否正在跑任何一种自动化验证（脚本化自测 / 界面自测 / 战斗自测）。 */
    private boolean isAutoVerification() {
        return selfTest != null || uiSelfTest != null || combatSelfTest != null;
    }

    /** 区块生成时回放它自己的存档差异（saveManager 尚未创建时恒为 0，见 attachStreaming）。 */
    private int applySavedChunkDelta(World target, int cx, int cz) {
        return saveManager == null ? 0 : saveManager.applyChunkDelta(target, cx, cz);
    }

    private void activateEntry(String entryId) {
        if (entryId == null) {
            return;
        }
        switch (ui.state()) {
            case MAIN_MENU -> {
                if (Menus.ID_CONTINUE.equals(entryId) || Menus.ID_START_GAME.equals(entryId)) {
                    // ID_START_GAME 是 M1 的旧 id，自动化脚本仍在用它驱动前段闭环，
                    // 因此保留为别名而不是删掉 —— 删掉只会让脚本静默走进 default 分支打一句 WARN。
                    if (ui.startGame()) {
                        Log.info("[界面] 继续游戏（进入已在内存中的会话：%s）",
                                initialLoadResult != null && initialLoadResult.success()
                                        ? "读档成功" : "新世界");
                    }
                } else if (Menus.ID_NEW_WORLD.equals(entryId)) {
                    startNewWorld();
                    if (ui.startGame()) {
                        Log.info("[界面] 新建世界（地形复位 + 进度清空）");
                    }
                } else if (Menus.ID_OPEN_SETTINGS.equals(entryId)) {
                    if (ui.openSettings()) {
                        settingsMenu.refresh();
                    }
                } else if (Menus.ID_QUIT_GAME.equals(entryId)) {
                    // 主菜单退出：只登记请求，真正的退出由主循环收尾（保存 → 释放 GL → 销毁窗口）
                    ui.requestQuit();
                } else {
                    Log.warn("[界面] 主菜单收到未知菜单项: %s", entryId);
                }
            }
            case PAUSED -> {
                if (Menus.ID_RESUME.equals(entryId)) {
                    ui.resume();
                } else if (Menus.ID_OPEN_SETTINGS.equals(entryId)) {
                    if (ui.openSettings()) {
                        settingsMenu.refresh();
                    }
                } else if (Menus.ID_SAVE_TO_MAIN_MENU.equals(entryId)) {
                    saveAndReturnToMainMenu();
                } else if (Menus.ID_QUIT_GAME.equals(entryId)) {
                    ui.requestQuit();
                } else {
                    Log.warn("[界面] 暂停菜单收到未知菜单项: %s", entryId);
                }
            }
            case SETTINGS -> applySettingsEffect(settingsMenu.activate(entryId));
            default -> Log.warn("[界面] 游玩中不应收到菜单激活: %s", entryId);
        }
    }

    /**
     * 派发设置界面的交互后果。
     *
     * <p>{@code SETTINGS_CHANGED} 一律触发"应用 + 落盘"：设置项的语义是
     * "改完就生效"，没有"确认/取消"这一步 —— 因此不存在"改了但没保存"的中间态。
     * 写盘耗时被显式记录，用于证明它<u>不</u>落在性能测量窗口内
     * （自动化性能运行从 PLAYING 开始，不会打开菜单）。
     */
    private void applySettingsEffect(SettingsMenuController.Effect effect) {
        switch (effect) {
            case SETTINGS_CHANGED -> {
                applySettings();
                persistSettings("设置界面改动");
            }
            case BACK -> ui.closeSettings();
            case OPEN_REBIND -> Log.info("[界面] 进入等待输入（下一次按键将绑定到所选动作，ESC 取消）");
            case NONE -> {
                // 无事发生
            }
        }
    }

    // ============================================================ 设置应用与落盘

    /**
     * 把设置施加到运行时。<b>这是"立即生效"的唯一注入点。</b>
     *
     * <p>把它做成一个集中方法（而不是在每处改设置的代码后面各写一遍）的理由：
     * 漏掉任意一项的表现都是"设置界面里改了、游玩时没变"，而这类问题
     * 一旦分散就很难穷举验证。集中之后，"所有设置项都生效"是一条可检查的性质。
     */
    private void applySettings() {
        if (player == null) {
            return;
        }
        double degPerPixel = LookConfig.effectiveDegPerPixel(
                InputMapper.BASE_DEG_PER_PIXEL, settings.mouseSensitivity());
        player.setLookDegPerPixel(degPerPixel);
        // M2：FOV 走 player.setBaseFovDeg(...) 而不是直接写相机 ——
        // 实际生效的 FOV = 基础值 × 瞄准倍率（45/70），由 Player 自己合成；
        // 直接写相机会在"瞄准中改设置"时被下一次 step 覆盖回旧值。
        player.setBaseFovDeg(settings.fovDeg());
        hud.showFps = settings.showFps();
        if (window != null) {
            window.setVsync(settings.vsync());
        }
        // 音量：M2.1 起有了真正的消费方（M2 之前这条注释写的是"没有可施加的对象"）。
        // 这条日志刻意把音频侧<b>实际持有的</b>增益与播放侧状态一起报出来：
        // 只打印 80/80 会让人以为"设置生效了 ⇒ 一定听得见"，而 M2.1 的真实口径是
        // "设置一定生效，但有没有声音取决于这台机器"。两个数放在一行，
        // "设置没生效"与"设置生效了但没声卡"就不再是同一条日志。
        audio.setVolumes(settings.masterVolume(), settings.sfxVolume());
        Log.info("[设置] 已应用：灵敏度 %.2f（%.4f 度/像素）  FOV %.0f  反转Y %s  VSync %s  显示FPS %s"
                        + "  音量 %d/%d（音频侧持有 %d/%d，播放侧：%s）",
                settings.mouseSensitivity(), degPerPixel, settings.fovDeg(),
                settings.invertMouseY(), settings.vsync(), settings.showFps(),
                settings.masterVolume(), settings.sfxVolume(),
                audio.masterVolumePercent(), audio.sfxVolumePercent(), audio.statusText());
    }

    /** 把设置写盘并记录耗时。 */
    private boolean persistSettings(String reason) {
        long started = System.nanoTime();
        boolean ok = SettingsStore.save(settingsLoad.path(), settings);
        lastSettingsWriteMs = (System.nanoTime() - started) / 1e6;
        settingsWriteCount++;
        Log.info("[设置] 落盘（触发=%s，耗时 %.2f ms，累计 %d 次）",
                reason, lastSettingsWriteMs, settingsWriteCount);
        return ok;
    }

    // ============================================================ 光标模式与窗口标题

    /**
     * 把界面状态施加到窗口（光标模式 + 标题）。
     *
     * <p>每帧调用一次，因此两个动作都必须幂等 ——
     * {@code Window.setMouseCaptured} 与标题比较都做了短路，重复调用不产生 GLFW 调用。
     *
     * <p><b>光标模式切换后必须重置输入累积量：</b>把光标从"锁定"切回"可见"（或反向）时，
     * GLFW 会把光标位置复位，这次跳变会被 {@code onCursorPos} 记成一次真实位移，
     * 表现为"一按 ESC，视角猛地转过去"。{@code rebaseCursor} 把基准重新钉住。
     */
    private void applyUiMode() {
        if (window == null || ui == null) {
            return;
        }
        boolean capture = ui.state().mouseCaptured();
        if (window.setMouseCaptured(capture)) {
            if (capture) {
                input.rebaseCursor();
            } else {
                double[] p = window.cursorPosition();
                input.seedCursor(p[0], p[1]);
            }
            input.discardFrameAccumulators();
        }
        String title = "SkyIsland " + Version.version() + "  |  " + ui.state().label();
        if (!title.equals(lastWindowTitle)) {
            lastWindowTitle = title;
            window.setTitle(title);
        }
    }

    // ============================================================ 帧钩子 3/6：逻辑步

    /**
     * 推进一个固定步长的逻辑步。
     *
     * <p><b>M1.5：非 PLAYING 状态下直接返回。</b>这就是"暂停期间
     * Game Logic / Physics / Entity / World Time 全部暂停"的实现，
     * 而 {@code pausedStepSkips} 计数器证明"逻辑步确实被调用过并被拒绝"
     * （而不是这一帧根本没轮到逻辑步）。渲染与菜单输入不经过本方法，因此不受影响。
     */
    @Override
    public void stepLogic(double fixedDt) {
        if (!ui.isSimulationRunning()) {
            pausedStepSkips++;
            return;
        }
        simulationSteps++;

        elapsedSeconds += fixedDt;

        // ★ M5a：昼夜时钟。
        //   与 elapsedSeconds 用**同一个** fixedDt —— 两者的"时间"必须是同一个时间，
        //   否则暂停/掉帧之后，画面里的时刻会与性能读数对不上（极难归因）。
        //   跨过黎明的结算点由 DayClock 内部计数，这里不需要知道，也不需要通知谁。
        dayClock.advance(fixedDt);

        tickEventMessage(fixedDt);

        // ---- 预热结束：重置统计，开始正式测量 ----
        if (!warmupDone && elapsedSeconds >= config.warmupSeconds()) {
            warmupDone = true;
            loop.stats().reset();
            input.resetStats();
            // ★ 内存统计与帧统计**必须同一次重置**。
            //   分开放会让两个窗口错开（内存从启动算、帧从预热后算），
            //   于是"每帧堆增长"这种最关键的读数会算在错误的分母上，
            //   而那个读数恰恰是判断泄漏的依据。
            heap.reset();
            Log.info("[测量] 预热结束（%.1f 秒），统计已重置，正式测量开始。", elapsedSeconds);
        }

        // ---- 周期采样：内存（P3：给「不占过多内存」一个可举证的数字）----
        // ★ 采样必须<b>按真实时间</b>而不是按帧数：不限帧率时每帧不到 1 ms，
        //   按帧数采样会得到「每秒几千个样本」而没有额外信息，
        //   且 1 秒一次正好与 GC 的量级对齐。
        if (elapsedSeconds - lastHeapSampleSeconds >= HEAP_SAMPLE_INTERVAL_SECONDS) {
            heap.sample((long) ((elapsedSeconds - lastHeapSampleSeconds) * 1000.0), false);
            lastHeapSampleSeconds = elapsedSeconds;
        }

        // ---- 周期进度 ----
        if (elapsedSeconds >= nextProgressLog) {
            nextProgressLog += 5;
            Log.info("[进度] t=%.0fs %s", elapsedSeconds, loop.stats().snapshot().oneLine());
            Log.info("[进度] %s | %s | %s", world.statsLine(), player, ui.state().label());
        }

        // ---- 测量窗口结束 ----
        if (config.autoExit() && !measurementDone && elapsedSeconds >= config.totalSeconds()) {
            measurementDone = true;
            Log.info("[测量] 达到计划时长 %.0f 秒，准备收尾。", elapsedSeconds);
            // ★ P3：退出前做一次**强制 GC** 采样。
            //   usedHeap 会被"GC 还没来得及跑"放大，而 usedAfterGc 才是活对象的硬证据。
            //   这是全程唯一允许 forceGc 的地方 —— 它是 STW，放进每帧路径就是自己制造卡顿。
            heap.sample((long) ((elapsedSeconds - lastHeapSampleSeconds) * 1000.0), true);
            lastHeapSampleSeconds = elapsedSeconds;
            window.requestClose();
        }

        // ---- 意图 ----
        PlayerIntent intent = currentIntent();

        // ★ 帧级量（鼠标位移 / 滚轮）只在"本帧第一个真正执行的逻辑步"上施加一次。
        //   两个方向都必须挡住：零逻辑步的帧不能丢（留在暂存器里等下一帧），
        //   多逻辑步的帧不能重复施加（后续逻辑步拿到 0）。详见
        //   {@link com.skyisland.input.FrameInputQuantities}。
        //   M1 脚本化自测例外：它按逻辑步注入意图，位移就是"每步各一份"，不参与本规则。
        //   M2 战斗自测同理 —— 它同样按逻辑步注入，而且它的断言依赖"每步恰好施加一次移动"。
        if (selfTest == null && combatSelfTest == null) {
            intent = frameQuantities.apply(intent);
        }

        // 切槽属于"意图层"动作而非物理：它不改变位置，只改变手持物。
        // 滚轮已经由帧级量通道保证"一帧只施加一次"，因此这里不需要额外的消费标志。
        if (intent.hotbarSlot() >= 0) {
            player.inventory().selectSlot(intent.hotbarSlot());
        }
        if (intent.hotbarScroll() != 0) {
            player.inventory().cycleSlot(intent.hotbarScroll());
        }

        // ---- M4-S8a：区块流式加载 / 卸载 ----
        // ★ 必须排在物理<u>之前</u>：物理会读脚下与周围的方块，而"未加载"一律读作空气。
        //   若先物理再加载，玩家跨过区块边界的那一步会站在空气上开始自由落体。
        //   半径（默认 4 块 = 64 格）远大于单步位移（不足 0.1 格），
        //   因此"即将进入的区块"总是提前很久就生成好了。
        if (chunkStreamer != null) {
            chunkStreamer.update(player.position().x, player.position().z);
        }

        // ---- M3：资源核心慢速再生（PRD 4.6）----
        // ★ 排在物理之前、且用**固定步长的 dt**：本类的速率判据
        //   （≤ 采矿速率的 1/50）只有在"逻辑步恒为 60 Hz"时才成立 ——
        //   换成帧间隔的话，同一段游戏时长会因负载抖动而产出不同的矿量，
        //   判据就会随机地红。
        //   而它排在物理之前，是因为它会改方块：新建的矿石必须在
        //   玩家同一逻辑步的碰撞判定里就已经存在。
        if (coreRegen != null) {
            coreRegen.tick(fixedDt);
        }

        // ---- 物理 / 交互 ----
        player.step(world, intent, fixedDt);

        // ---- M2：实体与战斗 ----
        // 顺序是"先实体、后开火"，理由是一个同一时刻的因果必须落在同一个逻辑步里：
        //   ① 实体先 tick → 怪物这一步的追击 / 攻击 / 掉虚空都结算完；
        //   ② 再结算玩家这一枪 → 打中的是"这一步结束时的怪物位置"。
        // 反过来（先开火再 tick）会出现"打死了本步已经扑到脸上的怪"，而怪物的那一口
        // 要到下一步才结算 —— 表现成"我明明先打中的，却被咬了"，且无法从代码上一眼看出。
        entities.tick(world, player, fixedDt);
        // M2.1：玩家受伤的听觉反馈。必须紧跟在 entities.tick 之后 ——
        // 怪物咬人是在实体 tick 里结算的，跑到它前面会让这一声晚整整一个逻辑步
        // （理由详见 AudioFeedback.poll 的注释）。
        if (audioFeedback != null) {
            audioFeedback.poll(player);
        }
        combat.step(world, player, intent, fixedDt, combatListener);
        combatFx.tick(fixedDt);
        // M2.1：后坐力按逻辑步的 dt 回落，而不是"每帧衰减一个固定量"——
        // 后者的后果是后坐力持续时间与帧率绑定（3000 FPS 下 3 毫秒就消失），
        // 那正是本项目 §C.4′ 反复强调的"帧率不得影响行为"。
        // 2026-10-03：回落速度也来自当前枪的后坐档案（手枪 5.0 / SMG 2.4 / 步枪 4.0 度每秒）。
        player.camera().decayRecoil(fixedDt, heldRecoilProfile());
        updateAimHint();
        updateFlightNotice();

        if (selfTest != null) {
            selfTest.observeAfterStep(player);
        }
        if (combatSelfTest != null) {
            combatSelfTest.observeAfterStep(player);
        }
    }

    /**
     * 首次进入瞄准时给一次操作提示（PRD 5.7「进入瞄准提示：右键瞄准，R 换弹」）。
     *
     * <p><b>只提示一次，而不是每次瞄准都提示。</b>PRD 的原话是"在关键情境给出短提示"，
     * 语义是"教一次"；每次按住右键都在屏幕中间弹一行字，玩家第二次就会开始讨厌它 ——
     * 那比不提示更糟。本次会话内只提示一次：重开游戏时再教一遍是合理的。
     */
    private void updateAimHint() {
        // 首次进入世界的最简操作提示（PRD 6.7「首次进入提示」）：3 秒、仅首次。
        if (!firstJoinHintShown) {
            firstJoinHintShown = true;
            showEvent(Localization.text(Localization.MSG_FIRST_JOIN), 3.0);
        }

        boolean aiming = player.isAiming();
        if (aiming && !wasAiming && !aimHintShown) {
            aimHintShown = true;
            showEvent(Localization.text(Localization.MSG_AIM_HINT), 3.0);
        }
        wasAiming = aiming;
    }

    /**
     * 飞行开关的可见反馈（PRD §5.4）。
     *
     * <p><b>只在状态变化时提示一次</b>，与 {@link #updateAimHint} 同一条纪律：
     * 每步都弹一行字会让玩家立刻开始讨厌它。
     * 而"完全不提示"更糟 —— 双击空格是本项目自己选的键位（PRD 未指定），
     * 不告诉玩家就没有人知道它存在。
     */
    private void updateFlightNotice() {
        boolean flying = player.isFlying();
        if (flying == wasFlying) {
            return;
        }
        wasFlying = flying;
        showEvent(Localization.text(flying ? Localization.MSG_FLY_ON : Localization.MSG_FLY_OFF), 3.0);
    }

    /**
     * 创造会话开关时的善后：<b>重建创造面板</b>并给出可见提示。
     *
     * <p>★ 面板为什么必须跟着会话走：它是 {@link #creativeView}，而那个字段
     * 决定背包有几个标签（{@code tabs = creativeView == null ? 1 : 2}）。
     * 只开能力不建面板的后果是"能飞、能瞬时破坏，但背包里没有创造标签" ——
     * 五项能力少了一项，而那一项恰好是**唯一能让玩家看出自己在创造模式的界面证据**，
     * 于是"我是不是开着创造"只能靠猜。症状看起来像"创造面板没做出来"。
     *
     * <p>退出时反向清掉，让标签数回到 1 —— 否则会出现"能力没了但标签还在"，
     * 点了格子却什么都拿不到。
     */
    private void onCreativeSessionChanged() {
        // 状态从 Player 读，不从参数拿 —— 参数与字段不一致是典型的半途状态。
        final boolean active = player.isCreativeSession();
        if (active) {
            buildCreativeView();
        } else {
            creativeView = null;
        }
        showEvent(Localization.text(active
                ? Localization.MSG_CREATIVE_SESSION_ON
                : Localization.MSG_CREATIVE_SESSION_OFF), 4.0);
    }

    /**
     * 建创造面板。会话开关与启动装配共用它，避免两处各写一份。
     *
     * <p>★ 日志打在这里而不是调用点：它是"面板已建"的<b>唯一</b>落点，
     * 两个调用方各打一份会让创造存档启动时同一句话出现两次，
     * 而重复的日志会让人误以为面板被建了两遍。
     */
    private void buildCreativeView() {
        CreativePalette palette = CreativePalette.build();
        creativeView = palette.view(InventoryLayout.CREATIVE_COLUMNS);
        Log.info("[创造] 面板就绪：%d 格（口径 playerBlockCount=%d），标签页已开启",
                creativeView.size(), BlockRegistry.playerBlockCount());
    }

    /** 取本逻辑步应当施加的意图。 */
    private PlayerIntent currentIntent() {
        if (selfTest != null) {
            return selfTest.nextIntent(GameLoop.FIXED_DT);
        }
        if (combatSelfTest != null) {
            return combatSelfTest.nextIntent(GameLoop.FIXED_DT);
        }
        if (pendingRespawn) {
            pendingRespawn = false;
            // M2.1：重生是把玩家瞬移回出生点，新的位置不该带着上一处的后坐抖动。
            // Camera.clearRecoil 的注释里列的三个场景（读档 / 传送 / 重生）中，
            // 只有"重生"会发生在这个进程里 —— 读档与传送都在启动期，那时相机还是新的。
            player.camera().clearRecoil();
            // 复制一份意图并把"强制重生"置为真（PlayerIntent 是 record，复制逻辑由它自己提供）
            return frameIntent.withRespawn();
        }
        return frameIntent;
    }

    private void tickEventMessage(double dt) {
        if (eventSecondsLeft > 0) {
            eventSecondsLeft = Math.max(0, eventSecondsLeft - dt);
            if (eventSecondsLeft == 0) {
                eventMessage = "";
            }
        }
    }

    /**
     * 事件提示（屏幕中下部，PRD 6.7「即时提示」）。
     *
     * <p><b>M2 起这里可以显示中文了。</b>M1.5 的注释写的是"事件提示一律 ASCII
     * （点阵字模只覆盖 ASCII，中文会渲染成 ?）" —— 那是把实现缺口当成了产品决定。
     * PRD v0.3.2 §6.7 明文否定这种做法（"不得因为当前字体渲染能力而把产品规格降级为英文"），
     * M2 补上了 CJK 点阵字库，因此调用方可以直接传中文文案
     * （文案本身仍必须来自 {@code Localization}，不得就地写中文字面量）。
     *
     * <p>时长由调用方给：PRD 要求 2 秒后淡出，个别提示（如开局装备）需要更久。
     * "同类提示 5 秒内不重复"由 {@link #showEventDeduped} 负责。
     */
    private void showEvent(String message, double seconds) {
        eventMessage = message;
        eventSecondsLeft = seconds;
        Log.info("[提示] %s", message);
    }

    /**
     * 同类提示 5 秒内不重复（PRD 6.7「提示时长」）。
     *
     * <p>为什么需要它：{@code 背包已满} 这类提示由"每一次失败的拾取"触发。
     * 玩家站在一堆掉落物里连点几下，屏幕上就会反复弹出同一行字 ——
     * 而它每次都会把其他提示顶掉。去重之后，提示的含义回到"情况发生了"，
     * 而不是"这个情况发生了 N 次"。
     */
    private void showEventDeduped(String key, String message, double seconds) {
        if (key.equals(lastEventKey) && elapsedSeconds - lastEventKeyAt < EVENT_DEDUPE_SECONDS) {
            return;
        }
        lastEventKey = key;
        lastEventKeyAt = elapsedSeconds;
        showEvent(message, seconds);
    }

    /** 同类提示的去重窗口（秒）。PRD 6.7：5 秒内不重复。 */
    private static final double EVENT_DEDUPE_SECONDS = 5.0;

    // ============================================================ 帧钩子 4/6：渲染

    /**
     * 渲染一帧。<b>只读游戏状态</b>（唯一例外是"把相机放到插值位置"，
     * 它只动相机、不动权威位置，见 {@link Player#applyInterpolatedCamera}）。
     */
    @Override
    public void render(double alpha) {
        // ---- 插值：逻辑 60 Hz / 渲染上千 FPS，不插值画面会一顿一顿 ----
        player.applyInterpolatedCamera(alpha);

        // ★ 消费"方块改动 → 区块网格重建"队列。**必须在这里**：网格要上传到 GL，
        //   只有渲染钩子持有上下文，而且必须赶在 renderWorld 之前，
        //   否则本帧画的还是旧网格（表现就是"方块挖掉了但还看得见"）。
        //
        //   M2 缺陷修正：这一句此前只存在于 warmUpMeshes()，也就是说
        //   <b>只有启动预热那一轮会重建网格，正式游玩期间一次都不会</b>。
        //   实测代价：某次人工试玩破坏 6 个方块，mesh_build_count 始终是 16（预热值）、
        //   待重建队列从 0 攒到 3 再没下降 —— 玩家看到的是"方块还在原地，但走过去发现碰不到"。
        //   设计文档早就把这条失败模式写在失败模式表里了："挖了方块但视觉不变"。
        renderer.processMeshRebuilds(world);

        // ★ M5a：先把昼夜交给渲染器，再清屏 —— clear() 也要用它插值天空色。
        //   顺序反了的话，本帧天空用旧值、地形用新值，
        //   症状是"天空与地面的明暗对不上"，而那不会让任何单测变红。
        renderer.setDaylight(dayClock);
        renderer.clear();
        // 玩家一起传进去：裂纹叠加层需要"当前挖掘目标 + 进度"。
        // 菜单期间 player 非 null 但玩家没有挖掘动作，叠加层自然不画。
        // M2：实体随世界一起传，它们在区块之后、裂纹之前绘制（见 Renderer.renderWorld）。
        renderer.renderWorld(world, player.camera(), player, entities.all(), combatFx);

        // M2.1：手持物 pass 在世界之后、HUD 之前（顺序理由见 Renderer#renderViewmodel）。
        updateViewmodel();
        renderer.renderViewmodel(viewmodel);

        updateHud();
        // ---- M2.2：模态压暗 pass —— 在 HUD 之前 ----
        // 压暗只该压世界。它此前是"面板渲染的第一步"，而面板排在 HUD 之后，
        // 于是它顺手把生命条与通知压暗了 66%（像素证据：满心 229,51,61 → 81,23,29，
        // 恰为 1 − DIM.alpha）。这与 vitalsVisible 的产品决定冲突 ——
        // "开背包时生命条仍要显示，玩家不能因为开了背包就看不见自己在挨打"。
        // 所以它必须在这里，由本方法显式安排顺序，而不是藏在某个面板里。
        if (ui.state().menuVisible()) {
            renderer.renderModalDim();
        }
        renderer.renderHud(hud);

        // ---- M2.2：背包 pass —— 在 HUD 之后、菜单之前 ----
        // 顺序理由见 Renderer#renderInventory：背包是模态层，必须盖住 HUD
        // （否则准星会浮在面板上，而那一刻鼠标在点格子、不是在瞄准）。
        // inventoryModel.visible 为假时该方法直接返回，不碰 GL 状态。
        renderer.renderInventory(inventoryModel);

        // ---- 菜单 pass：永远在 HUD 之后，因此菜单不会被读数盖住 ----
        if (ui.state().menuVisible()) {
            MenuScreen screen = activeMenu();
            if (screen != null) {
                ensureMenuLayout(screen);
                String overlay = ui.state() == UiState.SETTINGS
                        ? settingsMenu.rebind().promptLine() : "";
                boolean dialog = ui.state() == UiState.SETTINGS
                        && settingsMenu.rebind().isResolvingConflict();
                renderer.renderMenu(screen, menuLayout,
                        "SkyIsland " + Version.version(),
                        footerHint(), overlay, dialog);
            }
        }

        // ---- 截图必须在 swapBuffers 之前（交换后后台缓冲内容未定义）----
        if (pendingScreenshotLabel != null) {
            String label = pendingScreenshotLabel;
            pendingScreenshotLabel = null;
            captureScreenshot(label);
        }

        // ---- GL 错误：只取第一次，避免把同一个错误刷满日志 ----
        if (glErrorSeen == null) {
            glErrorSeen = GlDiagnostics.drainError();
            if (glErrorSeen != null) {
                Log.noteWarning("GL", "渲染过程中检测到 GL 错误: " + glErrorSeen);
            }
        }
    }

    /**
     * 各界面下的底部操作提示。
     *
     * <p><b>M2.2 之前这里是四条英文 ASCII 字面量。</b>菜单文案中文化之后，
     * "中文标题 + 中文菜单项 + 英文底部提示"会同时出现在同一屏上 —— 而
     * {@code Localization} 里那四个 {@code HINT_*} key 当时<b>已经登记、却没有任何消费方</b>
     * （{@code HINT_INVENTORY} 由背包面板自己画，{@code HINT_SETTINGS} 被当成了设置屏的副标题，
     * 另两个纯粹是死的）。登记了不接线的东西会伪装成"已完成"：{@code LocalizationTest}
     * 只检查"每个 key 都有文案"，不检查"有人用它"。
     *
     * <p><b>唯一来源是 Localization</b>（PRD §6.7）。PLAYING / INVENTORY 返回空串是产品决定：
     * 游玩中不该有操作提示挡着画面；背包面板内部已经自己画了一行提示，
     * 再在底部画第二行就会重复。
     */
    private String footerHint() {
        return switch (ui.state()) {
            case MAIN_MENU -> Localization.text(Localization.HINT_MAIN);
            case PAUSED -> Localization.text(Localization.HINT_PAUSE);
            case SETTINGS -> Localization.text(Localization.HINT_SETTINGS);
            case PLAYING -> "";
            case INVENTORY -> "";
        };
    }

    /**
     * 由"手持物 + 已存在的枪械状态"解析出 HUD 后备弹药要读哪个物品 ID（v2 §19-10）。
     *
     * <p><b>为什么是 static 且无 GL 依赖：</b>这是本 Story 唯一的硬编码修正点。
     * 它必须是<b>纯函数</b>，才能让单元测试直接对着生产代码断言"读的是枪自己的 ammoId"，
     * 而不是在测试里把同一段读法再抄一遍 —— 后者与产品解耦，破坏注入时压根不会变红。
     *
     * <p><b>两条取值路径：</b>
     * <ol>
     *   <li>{@code gun != null}（手持枪且它已建出 {@link com.skyisland.combat.GunState}）
     *       → 取 {@code gun.spec().ammoId()}；</li>
     *   <li>{@code gun == null} 但手持物确实是枪（还没开工、状态未惰性建出）
     *       → 取 {@code held.item().gun().ammoId()}；</li>
     *   <li>不是枪（空手 / 方块 / 弹药）→ 返回 {@code null}，调用方据此记 0。</li>
     * </ol>
     *
     * <p><b>渲染路径约束：</b>{@code updateHud} 每渲染帧调用本方法，因此这里
     * <b>只读取、绝不创建对象</b> —— 两条路径都是 {@code GunState} / {@link com.skyisland.item.Item}
     * 上的纯 getter，不 new 任何东西（尤其是不能在这里 {@code gunFor} 惰性建 GunState，
     * 否则"打开一次菜单"就会给还没拿到的枪建出弹匣状态）。
     */
    static String reserveAmmoIdOf(ItemStack held, com.skyisland.combat.GunState gun) {
        if (gun != null) {
            return gun.spec().ammoId();
        }
        if (held != null && held.item().isGun()) {
            return held.item().gun().ammoId();
        }
        return null;
    }

    /** 把游戏状态汇总进 HUD 模型。每帧一次，全部是读取。 */
    private void updateHud() {
        FrameStats.Snapshot s = loop.stats().snapshot();
        hud.fps = s.fps();
        hud.tps = s.tps();
        hud.meanFrameMs = s.meanMs();
        hud.p99FrameMs = s.p99Ms();
        hud.maxFrameMs = s.maxMs();
        hud.spikesOver50Ms = (int) s.spikeCount();
        hud.clampedFrames = (int) s.clampCount();

        hud.playerX = player.position().x;
        hud.playerY = player.position().y;
        hud.playerZ = player.position().z;
        hud.yaw = player.camera().yawDeg();
        hud.pitch = player.camera().pitchDeg();
        hud.onGround = player.onGround();
        hud.velocityY = player.velocity().y;
        hud.deaths = player.deaths();

        hud.blockX = Coords.toBlock(player.position().x);
        hud.blockY = Coords.toBlock(player.position().y);
        hud.blockZ = Coords.toBlock(player.position().z);
        hud.chunkX = Coords.toChunk(hud.blockX);
        hud.chunkZ = Coords.toChunk(hud.blockZ);
        hud.localX = Coords.localFast(hud.blockX);
        hud.localZ = Coords.localFast(hud.blockZ);

        // ★ M5a：时刻条。文案由 Localization 格式化（PRD 6.7 禁止 UI 拼句子）；
        //   阶段名来自 DayPhase（领域名），本类不持有任何中文字面量。
        long dayLeft = (long) Math.ceil(dayClock.phaseSecondsLeft());
        DayPhase dayPhase = dayClock.phase();
        hud.dayStatusLabel = Localization.text(Localization.HUD_DAY_STATUS,
                dayClock.dayCount(), dayPhase.displayName(), dayLeft / 60, dayLeft % 60);
        hud.dayPhaseProgress = (float) dayClock.phaseProgress();
        // ★ 黄昏算夜晚：PRD §4.4 把"开始刷怪"记在黄昏，
        //   时刻条若在黄昏仍是白的，玩家会以为"还没开始"。
        hud.dayIsNight = dayPhase == DayPhase.DUSK || dayPhase == DayPhase.NIGHT;
        hud.loadedChunks = world.loadedChunkCount();
        hud.meshCount = renderer.chunkRenderer().meshCount();
        hud.pendingMeshRebuilds = world.pendingMeshRebuilds();
        hud.meshQueueHighWaterMark = world.meshQueueHighWaterMark();
        hud.breakCount = world.breakCount();
        hud.placeCount = world.placeCount();
        hud.rejectedCount = world.rejectedMutationCount();
        hud.neighborMarkCount = world.neighborMarkCount();
        hud.meshBuildCount = world.meshBuildCount();
        hud.meanMeshBuildMs = world.meanMeshBuildMs();
        hud.emissiveSourceCount = world.emissiveSourceCount();

        hud.drawCalls = renderer.chunkRenderer().drawCalls();
        hud.renderedTriangles = renderer.chunkRenderer().renderedTriangles();
        hud.culledChunks = renderer.chunkRenderer().culledChunks();

        RaycastHit hit = player.currentTarget();
        if (hit == null) {
            hud.targetBlockId = "-";
            hud.targetFace = "-";
            hud.targetDistance = 0;
        } else {
            hud.targetBlockId = BlockRegistry.byRuntimeId(hit.blockRuntimeId()).id();
            hud.targetFace = hit.faceName();
            hud.targetDistance = hit.distance();
        }
        hud.mining = player.isMining();
        hud.miningProgress = player.miningProgressFraction();
        hud.miningTargetId = player.miningTargetId();
        hud.lastPlacementMessage = player.lastPlacementMessage();
        hud.blocksBroken = player.blocksBroken();
        hud.blocksPlaced = player.blocksPlaced();

        hud.hotbarSelected = player.inventory().selectedSlot();
        // 快捷栏在绝对索引 27..35（M2.2 契约）。HUD 只画这 9 格，必须用 hotbarSlot 读，
        // 不能读 slot(i) —— 后者现在读的是主背包 0..8，会让 HUD 显示与手里拿的完全脱节。
        for (int i = 0; i < hud.hotbarRuntimeId.length; i++) {
            ItemStack stack = player.inventory().hotbarSlot(i);
            hud.hotbarRuntimeId[i] = stack.blockRuntimeId();
            hud.hotbarItemRuntimeId[i] = stack.itemRuntimeId();
            hud.hotbarCount[i] = stack.count();
        }

        // ---- M2：生命与枪械（PRD 5.3 / 5.4.3 / 6.1）----
        hud.health = player.health();
        hud.maxHealth = player.maxHealth();
        hud.dead = player.isDead();
        hud.deathTimerLeft = player.isDead()
                ? Math.max(0, Player.RESPAWN_DELAY_SECONDS - player.deathTimer()) : 0;

        ItemStack held = player.inventory().selectedStack();
        hud.holdingGun = held.item().isGun();
        hud.gunDisplayName = hud.holdingGun ? Localization.displayName(held.item().id()) : "";
        // 只读取已存在的枪械状态：updateHud 跑在渲染路径上，这里绝不能创建对象
        // （否则"打开一次菜单"就会给枪建一份弹匣状态，而玩家还没拿到枪）。
        com.skyisland.combat.GunState gun = combat.existingGun(player);
        hud.magazineAmmo = gun == null ? 0 : gun.magazineAmmo();
        hud.magazineSize = gun == null ? 0 : gun.magazineSize();
        // M3 §19-10：<b>后备弹药必须按手持枪自己的 ammoId 统计</b>，不能写死手枪弹。
        // 解析这一步抽到 {@link #reserveAmmoIdOf(ItemStack, GunState)} —— 它是纯函数、
        // 无 GL 依赖，因此"读的是枪自己的 ammoId"这件事能被单元测试直接按生产代码验，
        // 而不是靠测试里再抄一遍读法（抄一遍的断言与产品解耦，注入破坏时不会变红）。
        String ammoId = reserveAmmoIdOf(held, gun);
        hud.reserveAmmo = ammoId == null ? 0 : player.inventory().countOfItem(ammoId);
        // M2.1：弹药读数是显示成「12 / 24」还是「12 / ∞」，由枪械状态里的<b>规则</b>
        // 决定，而不是由上面那个背包计数是否够大来猜。二者必须同源：
        // 后备无限时背包里的数字仍有信息价值（"我捡到过多少"），但它已经不是后备量了。
        hud.reserveInfinite = gun != null && gun.reserveInfinite();
        hud.reloading = gun != null && gun.isReloading();
        hud.reloadProgress = gun == null ? 0 : gun.reloadProgress01();
        hud.aiming = player.isAiming();
        // M2.1：命中标记。HudRenderer 只读这一个数值，而它是 CombatFxModel 那套时间线的
        // <b>镜像</b>（同一个 0..1、同一条衰减曲线）。刻意镜像而不是各记一套计时器：
        // 两套计时器会在连打时逐渐错位，而那种错位不报错、只表现为"准星手感不对"。
        hud.hitMarker = combatFx.hitMarker01();

        hud.aliveEntities = entities.aliveCount();
        hud.totalSpawnedEntities = entities.totalSpawned();

        // 准星高亮与目标名：用与开火<b>完全相同</b>的那次射线判定（同一起点、同一方向、
        // 同一套最近命中规则），因此"准星说指着怪物"与"子弹打到了怪物"不可能相互矛盾。
        // 代价是每渲染帧一次体素射线（几十次整数迭代，微秒级）——
        // 换来的是准星与弹道不可能不一致，这个交换是划算的。
        double aimRange = hud.holdingGun && gun != null
                ? com.skyisland.combat.CombatController.rayRange(gun.spec()) : Player.REACH;
        com.skyisland.combat.Hitscan.Result aim = com.skyisland.combat.Hitscan.resolve(
                world, player.eyePosition(),
                new org.joml.Vector3d(player.camera().forward()).normalize(),
                aimRange, entities.all());
        if (aim.hitEntity()) {
            hud.targetInteractable = true;
            hud.targetDisplayName = Localization.displayName(aim.entity().typeId());
        } else if (aim.blockHit() != null) {
            hud.targetInteractable = true;
            hud.targetDisplayName = Localization.displayName(
                    BlockRegistry.byRuntimeId(aim.blockHit().blockRuntimeId()).id());
        } else {
            hud.targetInteractable = false;
            hud.targetDisplayName = "-";
        }

        hud.eventMessage = eventMessage;
        hud.eventSecondsLeft = eventSecondsLeft;

        // ---- M1.5：可见性由界面状态与设置决定 ----
        hud.showGameplayHud = ui.state().gameplayHudVisible();
        // M2.2：生命层与"操作层"分离 —— 背包打开时准星消失、生命保留（见 HudModel#showVitals）。
        hud.showVitals = ui.state().vitalsVisible();
        hud.showFps = settings.showFps();
        hud.uiStateLabel = ui.state().label();
        hud.uiTimeSeconds = elapsedSeconds;

        // ---- M2.2：背包界面的输入模型 ----
        // 这里只填"来自游戏状态"的部分；鼠标位置与悬停格由 handleInventoryInput 填，
        // 因为它们属于界面态而不是游戏态（而且必须在同一帧里与命中判定使用同一份布局）。
        inventoryModel.visible = ui.state() == UiState.INVENTORY;
        inventoryModel.inventory = player.inventory();
        inventoryModel.selectedHotbarSlot = player.inventory().selectedSlot();
        inventoryModel.craftingPanel = craftingPanel;
        // ★ 视图是同一个引用（启动时建一次），这里只是把它交给渲染层。
        //   绝不能在这里现 build：每帧新建对象会让渲染层的布局缓存每帧失效，
        //   而缓存失效的失败模式是**看不出来**的（只是变慢，不是变错）——
        //   那种"悄悄劣化"比崩溃更难在事后追认。
        inventoryModel.creativeView = creativeView;

        hud.extraDebugLines.clear();
        String mode = selfTest != null ? "M1 脚本化自测"
                : (combatSelfTest != null ? "M2 战斗自测"
                : (uiSelfTest != null ? "M1.5 界面自测" : "交互试玩"));
        hud.extraDebugLines.add("模式 " + mode + "  UI " + ui.state().label()
                + "  世界 " + config.worldName() + "  seed=" + config.seed());
        hud.extraDebugLines.add("存档 " + saveManager.worldDirectory());
        if (player.isCreativeMode()) {
            hud.extraDebugLines.add(String.format("创造模式  飞行=%s  免疫伤害 %d 次",
                    player.isFlying() ? "开" : "关", player.damageNegatedCount()));
        }
        if (chunkStreamer != null) {
            hud.extraDebugLines.add("流式 " + chunkStreamer.statsLine());
        }
        // ★ M5a：光照读数。sky_level / ambient_floor 是**实际送进着色器**的三个
        //   uniform 里的两个，贴在这里是为了让"画面太暗/太亮"能被一眼归因到具体参数。
        hud.extraDebugLines.add(String.format("昼夜  %s  sky=%.3f floor=%.3f blend=%.2f",
                dayClock.statusLine(), renderer.skyLevel(), renderer.ambientFloor(),
                renderer.skyBlend()));
        hud.extraDebugLines.add("设置 " + settingsLoad.path() + "  (" + settingsLoad.status() + ")");
        hud.extraDebugLines.add(String.format("灵敏度 %.2f (%.4f 度/px)  FOV %.0f  音效 %d/%d",
                settings.mouseSensitivity(), player.lookDegPerPixel(), settings.fovDeg(),
                settings.masterVolume(), settings.sfxVolume()));
        if (selfTest != null) {
            hud.extraDebugLines.add("自测阶段 " + selfTest.currentStageLabel());
        }
        if (combatSelfTest != null) {
            hud.extraDebugLines.add("战斗自测阶段 " + combatSelfTest.currentStageLabel());
        }
        if (uiSelfTest != null) {
            hud.extraDebugLines.add("界面自测阶段 " + uiSelfTest.currentStageLabel());
        }
        hud.extraDebugLines.add("预热网格 " + String.format("%.1f ms", warmupMeshMillis)
                + "  截图 " + screenshotPaths.size() + " 张");

        // ---- M2.1：F3 overlay 的实体信息（任务 C）----
        // 只在 overlay 打开时计算：LOS 是体素射线，虽然只有个位数实体、微秒级，
        // 但没必要在 F3 关着时每帧白跑。
        appendEntityDebugLinesIfEnabled();
    }

    /**
     * M2.1：F3 实体叠层的<b>唯一产出闸门</b>。
     *
     * <p>把 {@code if (hud.showDebugOverlay)} 这条判据收进一个命名方法，是为了让它成为
     * 一条<u>唯一</u>且可被自测直接调用的执行点：自测按 overlay 开 / 关各跑一次，
     * 断言"开 → 每只存活实体一行 + 汇总；关 → 一行都不产出"。
     * 若把闸门删掉或改成恒真，自测的"关 → 0 行"就会变红。
     */
    private void appendEntityDebugLinesIfEnabled() {
        if (hud.showDebugOverlay) {
            appendEntityDebugLines();
        }
    }

    /**
     * M2.1：把世界里的实体逐只写成 F3 overlay 行（任务 C）。
     *
     * <p>每行给出：类型 / 坐标 / <b>相对玩家的水平距离</b> / <b>竖直落差 Δy</b> /
     * 是否在相机视锥内 / 是否有视线 / 部件数（怪 8，其它 1）。
     *
     * <p><b>为什么只列最近的 {@link #DEBUG_ENTITY_LINES_MAX} 只并给一行汇总：</b>
     * 见该常量的注释 —— HUD 按行渲染会溢出，而最近的那几只才是玩家真正关心的。
     *
     * <p>视锥与视线判据与 F4 用<b>同一套</b>工具（{@link Frustum} / {@link DdaRaycaster}），
     * 因此 overlay 上写"视锥 是 / 视线 是"与"F4 会不会在这里刷怪"是同一条口径。
     */
    private void appendEntityDebugLines() {
        debugEntityScratch.clear();
        for (Entity e : entities.all()) {
            if (e != null && e.isAlive()) {
                debugEntityScratch.add(e);
            }
        }
        if (debugEntityScratch.isEmpty()) {
            hud.extraDebugLines.add("实体 存活 0");
            return;
        }

        Vector3d feet = player.position();
        debugEntityScratch.sort((a, b) -> Double.compare(
                a.position().distanceSquared(feet), b.position().distanceSquared(feet)));
        Frustum frustum = refreshCameraFrustum();

        int shown = Math.min(debugEntityScratch.size(), DEBUG_ENTITY_LINES_MAX);
        for (int i = 0; i < shown; i++) {
            Entity e = debugEntityScratch.get(i);
            var box = e.boundingBox();
            double centerX = (box.minX() + box.maxX()) * 0.5;
            double centerY = (box.minY() + box.maxY()) * 0.5;
            double centerZ = (box.minZ() + box.maxZ()) * 0.5;

            double horizontal = Math.hypot(centerX - feet.x, centerZ - feet.z);
            double dy = e.position().y - feet.y;

            boolean inFrustum = frustum == null || frustum.intersectsAABB(
                    (float) centerX, (float) centerY, (float) centerZ,
                    (float) centerX, (float) centerY, (float) centerZ);
            boolean los = hasLineOfSight(centerX, centerY, centerZ);

            int parts = MeleeMonster.TYPE_ID.equals(e.typeId())
                    ? com.skyisland.render.entity.MonsterModel.PART_COUNT : 1;

            hud.extraDebugLines.add(String.format(
                    "实体 #%d %s 坐标(%.1f,%.1f,%.1f) 水平 %.2f Δy %.2f 视锥 %s 视线 %s 部件 %d",
                    i + 1, Localization.displayName(e.typeId()),
                    e.position().x, e.position().y, e.position().z,
                    horizontal, dy, inFrustum ? "是" : "否", los ? "是" : "否", parts));
        }
        hud.extraDebugLines.add("实体 存活 " + debugEntityScratch.size() + " / 显示 " + shown);
    }

    /**
     * M2.1：把游戏状态汇总进第一人称手持物模型。每帧一次，全部是读取。
     *
     * <p><b>为什么与 {@link #updateHud()} 分开：</b>
     * 手持物需要的字段（开火累计次数、行走强度）HUD 一项都用不到，
     * 而 HUD 那三十来个字段手持物一个都用不到。合成一个方法会让两件不相干的事
     * 共享一份"每帧必跑"的代码 —— 将来任一方加字段都要读一遍另一方。
     *
     * <p><b>这里绝不创建对象：</b>它跑在渲染路径上，
     * 与 {@code updateHud} 里"只读取已存在的枪械状态"是同一条规矩
     * （否则"打开一次菜单"就会给枪建一份弹匣状态，而玩家还没拿到枪）。
     */
    private void updateViewmodel() {
        Inventory inventory = player.inventory();
        viewmodel.apply(inventory.selectedStack(), inventory.selectedSlot());

        com.skyisland.combat.GunState gun = combat.existingGun(player);
        viewmodel.shotCount = gun == null ? 0 : gun.shotsFired();
        viewmodel.reloading = gun != null && gun.isReloading();
        viewmodel.reloadProgress01 = gun == null ? 0 : gun.reloadProgress01();

        viewmodel.aiming = player.isAiming();
        viewmodel.mining = player.isMining();
        viewmodel.timeSeconds = elapsedSeconds;

        // 行走强度：用水平速度而不是"是否在按移动键"，于是"被击退/掉落中"
        // 也不会误判成在走路。除以 4.0 归一化（玩家步行速度约 4.3 格/秒）。
        double vx = player.velocity().x;
        double vz = player.velocity().z;
        double horizontal = Math.hypot(vx, vz);
        viewmodel.moveSpeed01 = player.onGround() ? Math.min(1.0, horizontal / 4.0) : 0.0;

        viewmodel.visible = ui.state().gameplayHudVisible() && !player.isDead();
    }

    /**
     * 回读帧缓冲并异步落盘。
     *
     * <p>回读是 GL 调用（必须在渲染线程、交换之前，约几毫秒）；
     * PNG 编码与写盘走后台线程（几十毫秒），不阻塞主循环 ——
     * 依据 {@code §C.4′ 第 7 条}：截图是测试脚手架，不得在主循环内制造假卡顿。
     */
    private void captureScreenshot(String label) {
        int width = renderer.framebufferWidth();
        int height = renderer.framebufferHeight();
        int[] pixels;
        try {
            pixels = Screenshot.readPixels(width, height);
        } catch (Throwable t) {
            Log.error("[截图] 回读帧缓冲失败（label=" + label + "）", t);
            return;
        }
        // 同色判定是"渲染有没有真的产出"的廉价证据：全天空色通常意味着地形没画出来
        lastScreenshotUniform = Screenshot.isNearlyUniform(pixels, 0.001);
        if (lastScreenshotUniform) {
            Log.noteWarning("截图", "截图像素几乎全为同色（label=" + label + "）——"
                    + "可能世界没有渲染出来，需人工确认：" + Path.of(config.screenshotDir()));
        }
        String name = Screenshot.timestampName(pendingScreenshotPrefix + label);
        Path dir = Path.of(config.screenshotDir());
        Screenshot.writePngAsync(pixels, width, height, dir, name, path -> {
            synchronized (screenshotPaths) {
                screenshotPaths.add(label + " -> " + path.toAbsolutePath());
            }
        });
    }

    // ============================================================ 帧钩子 5/6：帧末

    @Override
    public void endFrame() {
        window.swapBuffers();

        // ---- 帧缓冲尺寸变化 → 投影矩阵必须跟着换，否则画面被拉长 ----
        int fbWidth = window.framebufferWidth();
        int fbHeight = window.framebufferHeight();
        if (fbWidth != lastFramebufferWidth || fbHeight != lastFramebufferHeight) {
            resizeEventCount++;
            Log.info("[窗口] 帧缓冲 %d×%d → %d×%d，已更新投影矩阵",
                    lastFramebufferWidth, lastFramebufferHeight, fbWidth, fbHeight);
            lastFramebufferWidth = fbWidth;
            lastFramebufferHeight = fbHeight;
            renderer.resize(fbWidth, fbHeight);
        }

        // ---- 存档放在交换之后：不让磁盘 IO 混进本帧的渲染时间 ----
        if (pendingSave) {
            pendingSave = false;
            performSave("F5 手动");
        }

        // ---- 输入边沿：必须在所有消费方读完之后的同一帧末尾清除 ----
        input.clearPressedEdges();

        // ---- 自测完成后多留几帧，再自动退出 ----
        if (selfTest != null && selfTest.isFinished()) {
            if (selfTestLingerFrames < 0) {
                selfTestLingerFrames = 0;
                Log.info("[自测] 脚本已结束，%d 帧后自动退出（留时间让末张截图与终态画面落定）。",
                        SELFTEST_LINGER_FRAMES);
            } else if (++selfTestLingerFrames > SELFTEST_LINGER_FRAMES) {
                window.requestClose();
            }
        }
        if (combatSelfTest != null && combatSelfTest.isFinished()) {
            if (selfTestLingerFrames < 0) {
                selfTestLingerFrames = 0;
                Log.info("[自测] M2 战斗自测已结束，%d 帧后自动退出。", SELFTEST_LINGER_FRAMES);
            } else if (++selfTestLingerFrames > SELFTEST_LINGER_FRAMES) {
                window.requestClose();
            }
        }
        if (uiSelfTest != null && uiSelfTest.isFinished()) {
            if (selfTestLingerFrames < 0) {
                selfTestLingerFrames = 0;
                Log.info("[UI自测] 脚本已结束，%d 帧后自动退出。", SELFTEST_LINGER_FRAMES);
            } else if (++selfTestLingerFrames > SELFTEST_LINGER_FRAMES) {
                window.requestClose();
            }
        }

        // ---- 界面状态机请求退出 → 交给主循环收尾（不在这里直接结束进程）----
        if (ui.isQuitRequested() && !window.shouldClose()) {
            Log.info("[界面] 退出请求生效，交由主循环收尾（保存 → 释放 GL → 销毁窗口）。");
            window.requestClose();
        }
    }

    private void onCloseRequested() {
        Log.info("收到窗口关闭请求（关闭按钮）—— 仍会走完整收尾流程。");
        // 关窗按钮的语义与"退出游戏"一致：不绕过保存与资源释放（规格第 10 条）。
        if (ui != null) {
            ui.requestQuit();
        }
    }

    // ============================================================ 退出路径

    /**
     * 暂停菜单 → "保存并返回主菜单"（规格第 10 条）。
     *
     * <p><b>为什么先存档再换界面：</b>存档是同步写盘（约 200 ms）。
     * 若先切界面再存档，玩家会在"已经回到主菜单"之后遇到一次莫名卡顿；
     * 而且中途出错时"已经离开游戏"与"没有存上"会同时成立。
     * 先存后切，失败时还能停在暂停菜单里重试。
     */
    private void saveAndReturnToMainMenu() {
        SaveResult result = performSave("保存并返回主菜单");
        if (result != null && !result.success()) {
            showEvent("Save failed: " + result.summary(), 6.0);
            Log.noteWarning("界面", "返回主菜单前的存档失败，仍继续返回（世界仍在内存中，可再次保存）");
        }
        if (ui.backToMainMenu()) {
            // 存档刚刚（可能）被创建：「继续游戏」这一行的可选性取决于磁盘上此刻有没有存档，
            // 因此必须重建主菜单屏，而不是沿用装配期那一份。
            refreshMainMenu();
            Log.info("[界面] 已返回主菜单（世界保留在内存中，可「继续游戏」接着玩，或「新建世界」重开一局）");
        }
    }

    // ============================================================ 存档

    // ============================================================ 持久化校验（进程重启级）

    /**
     * 进程重启后的持久化校验：M1 门禁「退出 → 重进 → 改动持久化」的硬证据。
     *
     * <p><b>为什么自测里的 {@code verifyReload} 还不够：</b>它在<u>同一次进程</u>内另造一个世界
     * 再 {@code loadInto}，证明的是"存档格式与加载路径正确"。它没有覆盖"上一个进程已经退出、
     * JVM 里的世界对象全部消失"这一层。门禁条目写的是"退出 → 重进"，所以必须真的重启一次进程。
     *
     * <p>校验对象是自测脚本留下的两处确定性改动（测试世界地形是固定函数，坐标可跨进程引用）：
     * <ol>
     *   <li>{@code (0, 63, 0)} —— 出生点正下方那一格，自测垂直下挖时被破坏 → 必须是空气；</li>
     *   <li>{@code (0, 64, -7)} —— 自测放置草方块的位置 → 必须仍是草方块。</li>
     * </ol>
     * 两条都通过才说明"改动被写进磁盘、并在新进程里被重新应用到世界"。
     *
     * <p><b>它不做的事：</b>不修改任何状态、不参与门禁判定（判定仍由自测与性能门禁负责）。
     * 它只输出一行可被外部脚本采集的 PASS/FAIL，避免把"检查"变成"干预"。
     */
    private void verifyPersistenceAfterRestart() {
        int dugX = 0;
        int dugY = Coords.WORLD_SURFACE_BLOCK_Y;
        int dugZ = 0;
        int placeX = 0;
        int placeY = Coords.WORLD_SURFACE_BLOCK_Y + 1;
        int placeZ = -7;

        boolean loaded = initialLoadResult != null && initialLoadResult.success();
        boolean dugIsAir = world.isAirAt(dugX, dugY, dugZ);
        int placedId = world.blockIdAt(placeX, placeY, placeZ);
        boolean placedIsGrass = placedId == BlockRegistry.grass().runtimeId();

        boolean passed = loaded && dugIsAir && placedIsGrass;

        Log.info("==================== 持久化校验（进程重启后）====================");
        Log.info("  世界                : %s", config.worldName());
        Log.info("  启动读档            : %s", loaded ? "成功 [PASS]" : "失败 [FAIL]");
        Log.info("  被挖方块 (%d,%d,%d) : %s [%s]", dugX, dugY, dugZ,
                dugIsAir ? "仍是空气" : "已被重新填回（非预期）", dugIsAir ? "PASS" : "FAIL");
        Log.info("  放置方块 (%d,%d,%d): %s [%s]", placeX, placeY, placeZ,
                BlockRegistry.byRuntimeId(placedId).id(),
                placedIsGrass ? "PASS" : "FAIL");
        Log.info("  persistence_passed  = %s", passed);
        Log.info("==================================================================");
    }

    /** 执行一次存档并记录耗时与结果。 */
    private SaveResult performSave(String reason) {
        if (!config.saveEnabled()) {
            Log.info("[存档] 已禁用（skyisland.noSave=true），跳过：%s", reason);
            return SaveResult.failed("存档已禁用");
        }
        long started = System.nanoTime();
        // ★ M5a：带上时钟，level.json 里的时刻与天数才会跟着更新。
        //   漏传的表现是"每次读档都被重置到白天开头"，且不报任何错。
        SaveResult result = saveManager.save(world, player, dayClock);
        double millis = (System.nanoTime() - started) / 1e6;
        lastSaveResult = result;
        Log.info("[存档] %s（触发=%s，耗时 %.1f ms）", result.oneLine(), reason, millis);
        for (String warning : result.warnings()) {
            Log.noteWarning("存档", warning);
        }
        return result;
    }

    // ============================================================ 自测宿主（M1）

    /**
     * 把游戏的实际对象交给自测脚本，而不是让脚本自己造一套。
     *
     * <p>这是 TR7 应对方案的关键：被测对象是<u>同一条游戏逻辑链路</u>
     * （{@code 玩家物理 → 射线 → World Mutation API → 网格重建 → 存档}），
     * 被绕开的只有 {@code OS → GLFW} 这一段输入投递。
     */
    private final class SelfTestHost implements M1ScriptedSelfTest.Host {

        @Override
        public World world() {
            return world;
        }

        @Override
        public Player player() {
            return player;
        }

        @Override
        public SaveManager saveManager() {
            return saveManager;
        }

        @Override
        public SaveResult requestSave() {
            return performSave("自测脚本");
        }

        @Override
        public boolean saveEnabled() {
            return config.saveEnabled();
        }

        @Override
        public void requestScreenshot(String label) {
            // 只置位：GL 回读必须发生在渲染阶段、且在两缓冲交换之前
            pendingScreenshotLabel = "selftest-" + label;
        }
    }

    // ============================================================ 自测宿主（M1.5）

    /**
     * M1.5 界面自测的宿主。
     *
     * <p>它把"注入原始输入"与"激活菜单项"两条通道交给脚本，
     * 而两者都<u>走产品自身的代码路径</u>（{@code InputState} 回调入口、
     * 与鼠标点击同一个 {@code activateEntry}）。脚本因此能驱动完整的前段闭环，
     * 同时不与实现细节耦合。
     */
    private final class UiSelfTestHost implements M1_5UiSelfTest.Host {

        @Override
        public UiStateMachine ui() {
            return ui;
        }

        @Override
        public GameSettings settings() {
            return settings;
        }

        @Override
        public SettingsMenuController settingsMenu() {
            return settingsMenu;
        }

        @Override
        public MenuScreen mainMenu() {
            return mainMenuScreen;
        }

        @Override
        public MenuScreen pauseMenu() {
            return pauseMenuScreen;
        }

        @Override
        public Player player() {
            return player;
        }

        @Override
        public World world() {
            return world;
        }

        @Override
        public Path settingsPath() {
            return settingsLoad.path();
        }

        @Override
        public Path worldDirectory() {
            return saveManager.worldDirectory();
        }

        @Override
        public void applySettings() {
            SkyIslandGame.this.applySettings();
        }

        @Override
        public boolean persistSettings(String reason) {
            return SkyIslandGame.this.persistSettings(reason);
        }

        @Override
        public SaveResult lastSaveResult() {
            return lastSaveResult;
        }

        @Override
        public int simulationSteps() {
            return simulationSteps;
        }

        @Override
        public int crackSegments() {
            return renderer.crackSegments();
        }

        @Override
        public long pausedStepSkips() {
            return pausedStepSkips;
        }

        @Override
        public double gameTimeSeconds() {
            return elapsedSeconds;
        }

        @Override
        public boolean mouseCaptured() {
            return window.isMouseCaptured();
        }

        @Override
        public boolean vsyncEnabled() {
            return window.isVsyncEnabled();
        }

        @Override
        public boolean hudShowFps() {
            return hud.showFps;
        }

        @Override
        public void injectKey(int key, boolean press) {
            input.onKey(key, 0,
                    press ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0);
        }

        @Override
        public void injectMouseButton(int button, boolean press) {
            input.onMouseButton(button, press ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0);
        }

        @Override
        public void injectCursorDelta(double dx, double dy) {
            input.injectCursorDelta(dx, dy);
        }

        @Override
        public void seedCursorPosition(double x, double y) {
            // 直接把光标状态播种到绝对位置，不产生位移。
            // 与 injectCursorDelta 的分工：那个表达"玩家把鼠标挪了一段"（用于视角），
            // 这个表达"光标现在停在这一格上"（用于命中判定）。
            // 绕开的只有 OS → GLFW 这一段，与 TR7 的应对方案同一条原则。
            input.seedCursor(x, y);
        }

        @Override
        public int inventoryHoverSlot() {
            return inventoryModel.hoverSlot;
        }

        // ---- 背包内合成区（2026-10-03）----
        // 下面四个读数让 M1.5 界面自测能<b>走完整产品链路</b>验证合成：
        // 播种光标到某一行的中心 → 注入左键 → 读背包。
        // 刻意不提供"直接调 craft(row)"的快捷方式 —— 那样绕开的正是最容易断的
        // 那一段（像素 → 命中行号 → 调用），而它恰恰是裁定第 8 条要钉住的那一段。

        @Override
        public int craftingRowCount() {
            return craftingPanel.size();
        }

        @Override
        public int inventoryHoverCraftRow() {
            return inventoryModel.hoverCraftRow;
        }

        @Override
        public String craftingRowStatusText(int row) {
            CraftingPanel.Row r = craftingPanel.row(row);
            return r == null ? "" : craftingPanel.statusText(r);
        }

        @Override
        public String craftingRowRecipeId(int row) {
            CraftingPanel.Row r = craftingPanel.row(row);
            return r == null ? "" : r.recipe().id();
        }

        @Override
        public double[] craftingRowCenterWindow(int row) {
            int fbW = window.framebufferWidth();
            int fbH = window.framebufferHeight();
            int winW = window.windowWidth();
            int winH = window.windowHeight();
            double[] center = renderer.inventoryRenderer()
                    .ensureLayout(fbW, fbH, craftingPanel.size()).craftRowCenter(row);
            double backX = center[0] * (winW / (double) Math.max(1, fbW));
            double backY = center[1] * (winH / (double) Math.max(1, fbH));
            return new double[]{backX, backY};
        }

        @Override
        public boolean hudGameplayVisible() {
            return hud.showGameplayHud;
        }

        @Override
        public boolean hudVitalsVisible() {
            return hud.showVitals;
        }

        @Override
        public double[] inventorySlotCenterWindow(int slot) {
            // ★ 这里**故意**不调用 InventoryLayout.windowToFramebuffer 的包装，
            //   而是把逆换算写成"乘以窗口/帧缓冲之比"：
            //   产品的正向换算是"乘以 fb/win"。若哪天有人把正向改成"乘以 win/fb"
            //   （DPI 换算写反，这是这个函数唯一可能的错法），
            //   两者不再互为逆运算 —— 自测瞄准的像素会落到别的格子上，
            //   于是"点第 3 格却命中了别的格"会在自测里当场暴露，
            //   而不是等玩家在缩放屏幕上点到错的格子。
            int fbW = window.framebufferWidth();
            int fbH = window.framebufferHeight();
            int winW = window.windowWidth();
            int winH = window.windowHeight();
            double[] center = renderer.inventoryRenderer()
                    .ensureLayout(fbW, fbH, craftingPanel.size()).slotCenter(slot);
            double backX = center[0] * (winW / (double) Math.max(1, fbW));
            double backY = center[1] * (winH / (double) Math.max(1, fbH));
            return new double[]{backX, backY};
        }

        @Override
        public double cursorPixelsAccumulatedX() {
            return input.stats().totalMouseDeltaX();
        }

        @Override
        public double cursorPixelsAccumulatedY() {
            return input.stats().totalMouseDeltaY();
        }

        @Override
        public void activateMenuEntry(String entryId) {
            activateEntry(entryId);
        }

        @Override
        public void setSensitivityAndApply(double value) {
            settings.setMouseSensitivity(value);
            SkyIslandGame.this.applySettings();
        }

        @Override
        public void setInvertYAndApply(boolean value) {
            settings.setInvertMouseY(value);
            SkyIslandGame.this.applySettings();
        }

        @Override
        public void requestQuit() {
            ui.requestQuit();
        }

        @Override
        public void requestScreenshot(String label) {
            // 只置位：GL 回读必须发生在渲染阶段、且在两缓冲交换之前。
            // 前缀刻意与 M1 自测区分开：两者的截图目录可能被指向同一处，
            // 文件名撞车会让"哪一张属于哪个里程碑"变得不可考。
            pendingScreenshotLabel = "ui_selftest-" + label;
        }
    }

    // ============================================================ 自测宿主（M2 战斗）

    /**
     * M2 战斗自测的宿主。
     *
     * <p>它只暴露"游戏本来就持有的对象"与两条<u>产品自带的调试路径</u>
     * （F4 刷怪、F6 补给），不提供任何"直接改结果"的后门 ——
     * 否则自测证明的就不是产品，而是脚手架自己。
     */
    private final class CombatSelfTestHost implements M2CombatSelfTest.Host {

        @Override
        public World world() {
            return world;
        }

        @Override
        public Player player() {
            return player;
        }

        @Override
        public SaveManager saveManager() {
            return saveManager;
        }

        @Override
        public EntityManager entities() {
            return entities;
        }

        @Override
        public CombatController combat() {
            return combat;
        }

        @Override
        public CombatFxModel combatFx() {
            return combatFx;
        }

        @Override
        public AudioManager audio() {
            return SkyIslandGame.this.audio;
        }

        @Override
        public SaveResult requestSave() {
            return performSave("M2 战斗自测");
        }

        @Override
        public void requestScreenshot(String label) {
            // 只置位：GL 回读必须发生在渲染阶段、且在两缓冲交换之前。
            // 前缀刻意与 M1 / M1.5 的截图区分开，避免同名文件互相覆盖。
            pendingScreenshotPrefix = "m2_";
            pendingScreenshotLabel = "selftest-" + label;
        }

        @Override
        public boolean saveEnabled() {
            return config.saveEnabled();
        }

        @Override
        public MeleeMonster spawnMonsterInFront() {
            return debugSpawnMonster();
        }

        @Override
        public int[] lastSpawnRejectCounts() {
            // 复制一份：这是产品的观测值，自测只读，不得反过来改它。
            return lastSpawnRejectCounts.clone();
        }

        @Override
        public boolean lastFrustumEvaluated() {
            return lastFrustumEvaluated;
        }

        @Override
        public boolean toggleDebugOverlay() {
            return SkyIslandGame.this.toggleDebugOverlay();
        }

        @Override
        public boolean debugHitboxEnabled() {
            return EntityRenderer.isDebugHitbox();
        }

        @Override
        public List<String> debugEntityOverlayLines(boolean overlayOn) {
            // 走产品自己的闸门 appendEntityDebugLinesIfEnabled()，不在这里重写 if：
            // 若闸门被改成恒真，"overlay 关 → 0 行"这条断言就会红。
            hud.showDebugOverlay = overlayOn;
            hud.extraDebugLines.clear();
            appendEntityDebugLinesIfEnabled();
            return List.copyOf(hud.extraDebugLines);
        }

        @Override
        public void grantDebugSupply() {
            grantStartingGear("M2 战斗自测补给（F6 路径）");
        }

        @Override
        public int grantSmgForSustain() {
            // M3 Story 10：SMG 已进开局装备，本方法因此<b>不再发放</b>，只负责"找到并选中"。
            //
            // 为什么改成"只选中"（这是一次有意的口径收紧）：
            //   Story 9 里 SMG 不在开局装备表内，本阶段只能自己发一把，于是阶段末必须
            //   再删掉它（releaseSmgAfterSustain）—— 否则退出自动存档会多出一把 SMG，
            //   与存档阶段快照不一致，把"夹具污染"伪装成"逐格不一致"的产品缺陷。
            //   SMG 进开局装备之后，"发一把再删一把"这套夹具不再必要，而且更危险：
            //   它会把玩家本来就有的那把一起删掉。现在本阶段的唯一前提是
            //   「开局装备给了 SMG」—— 这一点不成立，本阶段就该红。
            int smgId = ItemRegistry.runtimeIdOf(ItemRegistry.SMG_ID);
            if (smgId <= 0) {
                // 注册表里没有 SMG（理论上不会发生 —— ItemRegistryTest 会挡住）：
                // 返回 -1，由自测把它变成一条会变红的断言，而不是在这里静默失败。
                Log.noteWarning("战斗", "SMG 未在 ItemRegistry 中注册，SMG 稳定性阶段无法前置装备");
                return -1;
            }
            // 找到 SMG 落在快捷栏的哪一格并选中它 —— 不假定它一定落在某个固定槽。
            for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
                if (player.inventory().hotbarSlot(i).itemRuntimeId() == smgId) {
                    player.inventory().selectSlot(i);
                    Log.info("[战斗] SMG 稳定性阶段装备：选中槽=%d，手持=%s",
                            player.inventory().selectedSlot(), player.inventory().selectedStack().item().id());
                    return smgId;
                }
            }
            // 开局装备没给 SMG：本阶段的前提不成立。返回 -1 让自测把它变成红断言，
            // 而不是在这里偷偷补一把 —— 那会把"玩家拿不到 SMG"这个真问题藏起来。
            Log.noteWarning("战斗", "快捷栏里找不到 SMG（开局装备未发放？），SMG 稳定性阶段无法前置装备");
            return -1;
        }

        @Override
        public void releaseSmgAfterSustain(int restoreSelectedSlot) {
            // M3 Story 10：只还原"选中槽位"，不再动背包内容。
            //
            // 为什么删掉了原来那段"清空所有 SMG 槽位"：SMG 现在是开局装备的一部分，
            // 它本来就该留在背包里，也本就该被写进退出存档 —— 存档阶段的快照里也有它，
            // 两者一致才是正确的产品行为。再去删它反而会造成不一致（夹具污染）。
            // 唯一还需要还原的是"选中了哪一格"：存档快照记录的是阶段前的选中槽。
            player.inventory().selectSlot(restoreSelectedSlot);
            Log.info("[战斗] SMG 稳定性阶段夹具已还原：背包内容保持原样，选中槽位恢复为 %d",
                    restoreSelectedSlot);
        }
    }

    // ============================================================ 收尾

    /**
     * 自动化自测的结果 → 进程退出码。
     *
     * <p><b>为什么这件事必须做：</b>日志里写着 {@code ui_selftest_result = FAIL}、
     * 进程退出码却是 0，是自动化门禁里最危险的一种状态 ——
     * 只检查退出码的脚本（CI、批处理、人工看 {@code ERRORLEVEL}）会把它判成通过。
     * 仪器必须让"它自己的判定"与"它交给外界的信号"一致。
     *
     * <p>三种情形：
     * <ul>
     *   <li>没有请求任何自测 → 0（人工试玩退出不该被判为失败）；</li>
     *   <li>请求了自测、跑完、全部通过 → 0；</li>
     *   <li>请求了自测但失败，或者<b>根本没跑完</b> → 1。
     *       后者尤其重要：窗口被提前关掉时"没有失败"只等于"没有证据"，
     *       不能当作通过。</li>
     * </ul>
     */
    int automationExitCode() {
        if (selfTest != null && !selfTest.allPassed()) {
            return 1;
        }
        if (uiSelfTest != null && !uiSelfTest.allPassed()) {
            return 1;
        }
        if (combatSelfTest != null && !combatSelfTest.allPassed()) {
            return 1;
        }
        return 0;
    }

    private void shutdown() {
        Log.info("");
        Log.info("=== 开始收尾（Clean Shutdown）===");

        // ---- 1) 退出即保存（PRD：退出时落盘）----
        if (world != null && player != null && saveManager != null && loop != null) {
            Log.info("[收尾] 退出存档...");
            performSave("退出");
        }

        // ---- 1.5) 设置落盘（M1.5：设置独立于世界存档，但同样必须持久化）----
        if (settings != null && settingsLoad != null) {
            persistSettings("退出");
        }

        // ---- 2) 自测的循环外验证：另造一个世界重放存档 ----
        if (selfTest != null) {
            try {
                selfTest.verifyReload();
            } catch (Throwable t) {
                Log.error("[自测] 读档校验过程异常", t);
            }
        }
        // ---- 2.5) M2 战斗自测的循环外验证（理由与 M1 相同：不得在 game loop 内制造假卡顿）----
        if (combatSelfTest != null) {
            try {
                combatSelfTest.verifyReload();
            } catch (Throwable t) {
                Log.error("[自测] M2 读档校验过程异常", t);
            }
            // 2.6) 音频链校验：必须在 audio.close() 之前跑 —— close() 只打印计数，
            // 而这里要把计数变成会变红的断言（"包建好了但一行没接线"正是本阶段
            // 真实发生过的失败形态，它不会自己变红）。
            try {
                combatSelfTest.verifyAudio();
            } catch (Throwable t) {
                Log.error("[自测] M2 音频链校验过程异常", t);
            }
            // 2.7) 表现层（视觉）触发链校验。与音频校验并列而不是合并 ——
            // 二者失败的原因完全不同（一个是"事件没送到音频层"，一个是"特效方法从没被调用"），
            // 合并成一条会让失败信息同时指向两个方向。
            try {
                combatSelfTest.verifyCombatFx();
            } catch (Throwable t) {
                Log.error("[自测] M2 表现层校验过程异常", t);
            }
            // 2.8) 缺陷 B：F4 刷怪落点必须接地（绝不生成悬空怪）。
            // 与表现层校验并列：它失败的原因既不是"事件没送到音频层"、也不是"特效没被调用"，
            // 而是"怪被放在了半空"，合并会让失败信息指向三个方向。
            try {
                combatSelfTest.verifySpawnGrounding();
            } catch (Throwable t) {
                Log.error("[自测] M2 刷怪落点校验过程异常", t);
            }
            // 2.9) 缺陷 B 的另一半：近战攻击判定的竖直分量 + 咬击几何仪器（本轮新增）。
            // 单独一条而不是并进 2.8：它失败的原因既不是"落点在半空"、也不是"特效没被调用"，
            // 而是"水平够近就咬"这个判定本身漏了竖直项——合并会让失败信息指向三个方向。
            try {
                combatSelfTest.verifyMeleeVerticalGate();
            } catch (Throwable t) {
                Log.error("[自测] M2 近战竖直判定校验过程异常", t);
            }
            // 2.10) M2.1 任务 C：F3 实体叠层 + 碰撞箱同步。与前几条并列而不是合并 ——
            // 它失败的原因（"叠层没产出/字段是写死的" 或 "碰撞箱开关没跟着 F3 走"）
            // 与刷怪落点、近战判定都无关，合并会让失败信息指向错误方向。
            try {
                combatSelfTest.verifyDebugEntityOverlay();
            } catch (Throwable t) {
                Log.error("[自测] M2 实体叠层校验过程异常", t);
            }
        }

        // ---- 3) 结构化摘要（写入日志文件，供报告摘录）----
        if (loop != null) {
            emitMeasurementSummary();
        }

        // ---- 3.5) 音频：释放 native 资源，并打印本次会话的事件计数 ----
        // 位置在 GPU 释放之前、摘要之后：close() 会把每条音效各响了几次写进日志，
        // 那条行是"触发链是通的"的唯一证据（无声卡环境也一样要留下它）。
        audio.close();

        // ---- 4) GPU 资源：必须在窗口/上下文销毁之前释放 ----
        if (renderer != null) {
            renderer.dispose();
        }

        // ---- 5) 窗口与 GLFW ----
        if (!GLFW.glfwInit()) {
            Log.warn("GLFW 已不可用，跳过 glfwTerminate");
        } else {
            GLFW.glfwSetKeyCallback(window == null ? 0 : window.handle(), null);
            GLFW.glfwSetMouseButtonCallback(window == null ? 0 : window.handle(), null);
            GLFW.glfwSetCursorPosCallback(window == null ? 0 : window.handle(), null);
            GLFW.glfwSetScrollCallback(window == null ? 0 : window.handle(), null);
            GLFW.glfwSetFramebufferSizeCallback(window == null ? 0 : window.handle(), null);
            GLFW.glfwSetWindowSizeCallback(window == null ? 0 : window.handle(), null);
            GLFW.glfwSetWindowFocusCallback(window == null ? 0 : window.handle(), null);
            GLFW.glfwSetWindowRefreshCallback(window == null ? 0 : window.handle(), null);
            GLFW.glfwSetWindowCloseCallback(window == null ? 0 : window.handle(), null);

            if (window != null) {
                window.destroy();
                Log.info("窗口已销毁。");
            }

            GLFW.glfwTerminate();
            Log.info("glfwTerminate() 完成。");

            GLFWErrorCallback cb = GLFW.glfwSetErrorCallback(null);
            if (cb != null) {
                cb.free();
                Log.info("GLFW 错误回调已释放。");
            }
        }

        Log.info("=== 收尾完成（无残留 native 资源）===");
    }

    /** 输出便于摘录的结构化摘要（同时写入日志文件）。 */
    private void emitMeasurementSummary() {
        FrameStats.Snapshot s = loop.stats().snapshot();
        InputState.Stats is = input.stats();

        StringBuilder sb = new StringBuilder();

        // ---------- 构建 ----------
        sb.append("product_version    = ").append(Version.version()).append('\n');
        sb.append("build_label        = ").append(Version.buildLabel()).append('\n');
        sb.append("input_source       = ")
          .append(selfTest != null || combatSelfTest != null
                  ? "脚本化意图（进程内注入）" : "GLFW 真实键鼠").append('\n');
        sb.append("ui_selftest        = ").append(config.uiSelfTest()).append('\n');
        sb.append("combat_selftest    = ").append(config.combatSelfTest()).append('\n');

        // ---------- M1.5：界面与设置 ----------
        sb.append("--\n");
        sb.append("settings_path      = ").append(settingsLoad.path()).append('\n');
        sb.append("settings_status    = ").append(settingsLoad.status()).append('\n');
        sb.append("settings_notes     = ").append(settingsLoad.notes().size()).append('\n');
        sb.append("settings_writes    = ").append(settingsWriteCount).append('\n');
        sb.append("settings_last_write_ms = ").append(String.format("%.2f", lastSettingsWriteMs)).append('\n');
        for (String line : settings.summaryLines()) {
            sb.append("  ").append(line).append('\n');
        }
        sb.append("--\n");
        sb.append("ui_state_initial   = ").append(ui.history().isEmpty() ? "(无)" : ui.history().get(0)).append('\n');
        sb.append("ui_state_final     = ").append(ui.state().label()).append('\n');
        sb.append("ui_pause_count     = ").append(ui.pauseCount()).append('\n');
        sb.append("ui_resume_count    = ").append(ui.resumeCount()).append('\n');
        sb.append("ui_rejected_transitions = ").append(ui.rejectedTransitions()).append('\n');
        sb.append("simulation_steps   = ").append(simulationSteps).append('\n');
        sb.append("paused_step_skips  = ").append(pausedStepSkips).append('\n');
        sb.append("keybindings_custom = ").append(settings.keyBindings().customized().size()).append('\n');
        sb.append("mouse_deg_per_px   = ").append(String.format("%.5f", player.lookDegPerPixel())).append('\n');
        sb.append("camera_fov_deg     = ").append(String.format("%.1f", player.camera().fovDeg())).append('\n');
        sb.append("window_vsync       = ").append(window.isVsyncEnabled()).append('\n');
        sb.append("ui_transitions     = ").append(ui.history().size()).append('\n');
        for (String line : ui.history()) {
            sb.append("  · ").append(line).append('\n');
        }

        // ---------- 世界与存档 ----------
        sb.append("--\n");
        sb.append("world_name         = ").append(config.worldName()).append('\n');
        sb.append("world_seed         = ").append(config.seed()).append('\n');
        sb.append("generator_id       = ").append(world.generator().id()).append('\n');
        sb.append("generator_version  = ").append(world.generator().generationVersion()).append('\n');
        sb.append("loaded_chunks      = ").append(world.loadedChunkCount()).append('\n');
        // ★ M4-S8a：流式证据。三行一起才有意义 ——
        //   只打 loaded_chunks 看不出"是常驻全世界还是按需加载"，
        //   只打累计加载/卸载看不出"当前到底占了多少"。
        if (chunkStreamer != null) {
            sb.append("stream_radius      = ").append(chunkStreamer.radius())
                    .append(" (keep ").append(chunkStreamer.keepRadius()).append(")\n");
            sb.append("stream_chunks_load = ").append(chunkStreamer.loadCount()).append('\n');
            sb.append("stream_chunks_drop = ").append(chunkStreamer.unloadCount()).append('\n');
        }
        sb.append("delta_applied      = ").append(world.deltaAppliedCount()).append('\n');
        // ★ M3：资源核心再生读数。**必须打，且必须成组** ——
        //   core_count=0 就说明没装配（自测世界），那是设计内；
        //   但如果 core_count>0 而 regen_runs 在一个长窗口里恒为 0，
        //   那就是"装了却没在跑"，与本项目反复修的"写了不接线"同类。
        //   skipped_* 四项分列的理由：占用 / 区块未加载 / 无候选是三种
        //   不同原因，合成一个计数就分不出"该调参"还是"玩家堵住了"。
        if (coreRegen != null) {
            sb.append("core_count         = ").append(coreRegen.coreCount()).append('\n');
            sb.append("regen_runs         = ").append(coreRegen.regenRuns()).append('\n');
            sb.append("regen_ores         = ").append(coreRegen.oresPlaced()).append('\n');
            sb.append("regen_skip_occup   = ").append(coreRegen.skippedOccupied()).append('\n');
            sb.append("regen_skip_nochunk = ").append(coreRegen.skippedChunkNotLoaded()).append('\n');
            sb.append("regen_skip_nocand  = ").append(coreRegen.skippedNoCandidate()).append('\n');
        }
        // ★ M5a：昼夜读数。与内存/帧读数同一次输出，避免"要看两处"。
        sb.append("day_seconds        = ")
                .append(String.format("%.1f", dayClock.timeSeconds())).append('\n');
        sb.append("day_total_seconds  = ")
                .append(String.format("%.1f", dayClock.totalSeconds())).append('\n');
        sb.append("day_count          = ").append(dayClock.dayCount()).append('\n');
        sb.append("day_phase          = ").append(dayClock.phase().name()).append('\n');
        sb.append("day_sky_level      = ")
                .append(String.format("%.4f", dayClock.skyLevel())).append('\n');
        sb.append("day_ambient_floor  = ")
                .append(String.format("%.4f", dayClock.ambientFloor())).append('\n');
        sb.append("save_root          = ").append(config.saveRoot()).append('\n');
        sb.append("save_enabled       = ").append(config.saveEnabled()).append('\n');
        sb.append("load_on_start      = ").append(initialLoadResult == null
                ? "未读档（新世界）" : initialLoadResult.oneLine()).append('\n');
        sb.append("save_on_exit       = ").append(lastSaveResult == null
                ? "(未执行)" : lastSaveResult.oneLine()).append('\n');
        sb.append("warmup_mesh_ms     = ").append(String.format("%.2f", warmupMeshMillis)).append('\n');
        sb.append("mesh_build_count   = ").append(world.meshBuildCount()).append('\n');
        sb.append("mesh_build_mean_ms = ").append(String.format("%.3f", world.meanMeshBuildMs())).append('\n');
        sb.append("mesh_queue_peak    = ").append(world.meshQueueHighWaterMark()).append('\n');

        // ---------- 游玩统计 ----------
        sb.append("--\n");
        sb.append("blocks_broken      = ").append(player.blocksBroken()).append('\n');
        sb.append("blocks_placed      = ").append(player.blocksPlaced()).append('\n');
        sb.append("placement_rejected = ").append(player.placementRejections()).append('\n');
        sb.append("deaths             = ").append(player.deaths()).append('\n');
        sb.append("walk_distance      = ").append(String.format("%.2f", player.walkDistance())).append('\n');
        sb.append("final_position     = ").append(String.format("%.3f, %.3f, %.3f",
                player.position().x, player.position().y, player.position().z)).append('\n');
        sb.append("inventory          = ").append(player.inventory()).append('\n');
        sb.append("emissive_sources   = ").append(world.emissiveSourceCount()).append('\n');

        // ---------- M2：战斗观测（全部是"只能由真的发生了来推进"的计数器）----------
        sb.append("--\n");
        sb.append("combat_shots_fired = ").append(combat.shotsFired()).append('\n');
        sb.append("combat_dry_fires   = ").append(combat.dryFires()).append('\n');
        sb.append("combat_block_hits  = ").append(combat.blockHits()).append('\n');
        sb.append("combat_entity_hits = ").append(combat.entityHits()).append('\n');
        sb.append("combat_total_damage = ").append(combat.totalDamageDealt()).append('\n');
        sb.append("combat_last_damage = ").append(combat.lastDamage()).append('\n');
        sb.append("combat_last_distance = ").append(String.format("%.3f", combat.lastDistance())).append('\n');
        sb.append("entities_alive     = ").append(entities.aliveCount()).append('\n');
        sb.append("entities_total_spawned = ").append(entities.totalSpawned()).append('\n');
        sb.append("entities_total_removed = ").append(entities.totalRemoved()).append('\n');
        sb.append("health             = ").append(player.health())
          .append('/').append(player.maxHealth()).append('\n');
        sb.append("fx_break_particles = ").append(combatFx.totalBreakParticles()).append('\n');
        sb.append("fx_spawn_calls     = ").append(combatFx.totalSpawnCalls()).append('\n');

        // ---------- P3：堆内存（此前项目里没有任何内存数字）----------
        // ★ 必须打 used / peak / **after_gc** 三个数，只打 used 会把
        //   "GC 还没跑" 误读成 "泄漏"（见 HeapSampler 类注释）。
        sb.append(heap.summary());

        // ---------- 性能（与 M0 完全同口径，便于跨里程碑对比）----------
        sb.append("--\n");
        sb.append("预热时长_秒        = ").append(config.warmupSeconds()).append('\n');
        sb.append("计划测量时长_秒    = ").append(config.measureSeconds()).append('\n');
        sb.append("实际统计窗口_秒    = ").append(String.format("%.3f", s.elapsedSeconds())).append('\n');
        sb.append("样本数_帧          = ").append(s.sampleCount()).append('\n');
        sb.append("FPS                = ").append(String.format("%.2f", s.fps())).append('\n');
        sb.append("TPS                = ").append(String.format("%.2f", s.tps())).append('\n');
        sb.append("mean_frame_ms      = ").append(String.format("%.3f", s.meanMs())).append('\n');
        sb.append("median_frame_ms    = ").append(String.format("%.3f", s.medianMs())).append('\n');
        sb.append("p95_frame_ms       = ").append(String.format("%.3f", s.p95Ms())).append('\n');
        sb.append("p99_frame_ms       = ").append(String.format("%.3f", s.p99Ms())).append('\n');
        sb.append("max_frame_ms       = ").append(String.format("%.3f", s.maxMs())).append('\n');
        sb.append("spikes_gt_50ms     = ").append(s.spikeCount()).append('\n');
        sb.append("spikes_gt_100ms    = ").append(s.over100Count()).append('\n');
        sb.append("spikes_gt_150ms    = ").append(s.over150Count()).append('\n');
        sb.append("clamped_frames     = ").append(s.clampCount()).append('\n');
        sb.append("logic_steps        = ").append(s.logicSteps()).append('\n');
        sb.append("overruns           = ").append(s.overrunCount()).append('\n');
        sb.append("perf_gate_met      = ").append(s.meetsPerfGate()).append('\n');

        // ---------- 渲染 ----------
        sb.append("--\n");
        sb.append("draw_calls         = ").append(renderer.chunkRenderer().drawCalls()).append('\n');
        sb.append("rendered_triangles = ").append(renderer.chunkRenderer().renderedTriangles()).append('\n');
        sb.append("culled_chunks      = ").append(renderer.chunkRenderer().culledChunks()).append('\n');
        sb.append("mesh_count         = ").append(renderer.chunkRenderer().meshCount()).append('\n');
        // 裂纹叠加层（PRD「破坏反馈」）的工作量凭证：
        // 不挖掘时它一帧都不画，因此性能门禁的测量条件不因它而改变；
        // 挖掘时它每帧只多 1 次 draw call、最多 60 个三角形。
        sb.append("crack_draw_frames  = ").append(renderer.crackDrawCount()).append('\n');
        sb.append("crack_segments     = ").append(renderer.crackSegments()).append('\n');
        sb.append("resize_events      = ").append(resizeEventCount).append('\n');
        sb.append("framebuffer        = ").append(renderer.framebufferWidth())
          .append('x').append(renderer.framebufferHeight()).append('\n');

        // ---------- 输入 ----------
        sb.append("--\n");
        sb.append("键事件数           = ").append(is.keyEvents()).append('\n');
        sb.append("不同键数           = ").append(is.distinctKeys()).append('\n');
        sb.append("鼠标按键事件数     = ").append(is.mouseButtonEvents()).append('\n');
        sb.append("鼠标移动样本数     = ").append(is.mouseMoveSamples()).append('\n');
        sb.append("滚轮事件数         = ").append(is.scrollEvents()).append('\n');
        sb.append("窗口尺寸变化次数   = ").append(is.resizeCount()).append('\n');

        // ---------- 截图 ----------
        sb.append("--\n");
        sb.append("screenshots        = ").append(screenshotPaths.size()).append('\n');
        sb.append("screenshot_dir     = ").append(Path.of(config.screenshotDir()).toAbsolutePath()).append('\n');
        sb.append("last_shot_uniform  = ").append(lastScreenshotUniform).append('\n');
        synchronized (screenshotPaths) {
            for (String line : screenshotPaths) {
                sb.append("  · ").append(line).append('\n');
            }
        }

        // ---------- 健康 ----------
        sb.append("--\n");
        sb.append("gl_error_seen      = ").append(glErrorSeen == null ? "(无)" : glErrorSeen).append('\n');
        sb.append("warn_count         = ").append(Log.warningCount()).append('\n');
        // ★ IME 子类诊断放**测量摘要**里，而不是装类的那一刻。
        //   理由与 Window#imeDiagnostics 的注释相同：装完立刻读计数必然是 0，
        //   那条 0 不证明任何事；必须等跑过若干帧、pump 过若干消息之后才有意义。
        //   四个数字合起来才回答得了"输入法在哪里动手、动的是什么"：
        //     子类调用=0        ⇒ 我们的 wndproc 一次都没被调用（子类没生效）
        //     WM_IME_SETCONTEXT=0 ⇒ 这条消息根本不来（该杠杆无效）
        //     WM_INPUTLANG=0      ⇒ 输入法也没有要求本程序切语言
        //     SHIFT键事件=0        ⇒ ★ 输入法**真的把按键吞了**（不是只翻指示器）
        final String imeDiag = com.skyisland.render.Window.imeDiagnostics();
        if (imeDiag != null) {
            sb.append("ime                = ").append(imeDiag).append('\n');
        }

        // ---------- 脚本化自测 ----------
        if (selfTest != null) {
            sb.append("--\n").append(selfTest.summary());
        }
        if (uiSelfTest != null) {
            sb.append("--\n").append(uiSelfTest.summary());
        }
        if (combatSelfTest != null) {
            sb.append("--\n").append(combatSelfTest.summary());
        }

        // ---------- 门禁 ----------
        boolean selfTestPassed = selfTest != null && selfTest.allPassed();
        // 断言范围必须进判定：noSave 对照运行会跳过存档断言，它的"通过"不等于功能闭环通过
        boolean fullScope = selfTest != null && selfTest.isFullScope();
        boolean perfGate = s.meetsPerfGate();
        boolean glClean = glErrorSeen == null;
        boolean uiPassed = uiSelfTest != null && uiSelfTest.allPassed();
        sb.append("--\n");
        sb.append("m1_selftest_passed = ").append(selfTestPassed).append('\n');
        sb.append("m1_selftest_scope  = ")
                .append(selfTest == null ? "(无自测)" : selfTest.scope()).append('\n');
        sb.append("m1_functional_closure = ").append(selfTestPassed && fullScope).append('\n');
        sb.append("m1_perf_gate_met   = ").append(perfGate).append('\n');
        sb.append("m1_gl_error_clean  = ").append(glClean).append('\n');
        sb.append("m1_gate_met        = ")
                .append(selfTestPassed && fullScope && perfGate && glClean).append('\n');
        sb.append("--\n");
        sb.append("m1_5_ui_selftest_passed = ").append(uiPassed).append('\n');
        sb.append("m1_5_ui_selftest_based  = ").append(uiSelfTest != null).append('\n');
        // ---------- M2：战斗自测门禁（对齐 m1_functional_closure 的语义）----------
        boolean combatPassed = combatSelfTest != null && combatSelfTest.allPassed();
        boolean combatFullScope = combatSelfTest != null && combatSelfTest.isFullScope();
        sb.append("--\n");
        sb.append("m2_selftest_passed = ").append(combatPassed).append('\n');
        sb.append("m2_selftest_scope  = ")
                .append(combatSelfTest == null ? "(无自测)" : combatSelfTest.scope()).append('\n');
        sb.append("m2_selftest_assertions = ")
                .append(combatSelfTest == null ? 0 : combatSelfTest.results().size()).append('\n');
        sb.append("m2_selftest_failures   = ")
                .append(combatSelfTest == null ? 0 : combatSelfTest.failures().size()).append('\n');
        // ★ 战斗闭环 = "自测通过" 且 "断言范围是全量"。
        //   与 m1_functional_closure 同口径：跳过存档断言的对照运行不得被当作闭环证据。
        sb.append("m2_combat_closure  = ").append(combatPassed && combatFullScope).append('\n');

        Log.info("");
        Log.info("==================== 测量摘要 ====================");
        for (String line : sb.toString().split("\\R")) {
            if (!line.isEmpty()) {
                Log.info("  %s", line);
            }
        }
        Log.info("=================================================");

        Log.appendSectionToFile("MEASUREMENT_SUMMARY", sb.toString());
    }

    // ============================================================ 供外部检查

    /** 供自动化脚本从日志确认截图目录确实有产物（不做断言，只提供事实）。 */
    int screenshotCountOnDisk() {
        Path dir = Path.of(config.screenshotDir());
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(p -> p.getFileName().toString().endsWith(".png")).count();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 供测试查证：日志中的启动耗时（毫秒）。 */
    long uptimeMillis() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}

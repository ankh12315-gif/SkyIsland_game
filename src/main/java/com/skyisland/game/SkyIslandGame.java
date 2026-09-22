package com.skyisland.game;

import com.skyisland.audio.AudioFeedback;
import com.skyisland.audio.AudioManager;
import com.skyisland.combat.CombatController;
import com.skyisland.entity.Entity;
import com.skyisland.entity.EntityManager;
import com.skyisland.entity.MeleeMonster;
import com.skyisland.input.InputMapper;
import com.skyisland.input.InputState;
import com.skyisland.input.MenuNav;
import com.skyisland.item.ItemRegistry;
import com.skyisland.physics.DdaRaycaster;
import com.skyisland.physics.RaycastHit;
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
import com.skyisland.util.Coords;
import com.skyisland.util.Log;
import com.skyisland.world.World;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;
import com.skyisland.world.gen.TestWorldGenerator;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <p><b>M2 及以后</b>：枪械 / 怪物 / 昼夜 / 生命值与饥饿 / 掉落物实体 /
 * 完整背包界面 / 音频。
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
    private static final int DEBUG_ENTITY_LINES_MAX = 8;

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
            String startState
    ) {
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
            org.joml.Vector3d eye = player.eyePosition();
            org.joml.Vector3fc fwd = player.camera().forward();
            org.joml.Vector3fc right = player.camera().right();
            org.joml.Vector3fc up = player.camera().up();
            double muzzleX = eye.x
                    + fwd.x() * MUZZLE_FORWARD + right.x() * MUZZLE_RIGHT - up.x() * MUZZLE_DOWN;
            double muzzleY = eye.y
                    + fwd.y() * MUZZLE_FORWARD + right.y() * MUZZLE_RIGHT - up.y() * MUZZLE_DOWN;
            double muzzleZ = eye.z
                    + fwd.z() * MUZZLE_FORWARD + right.z() * MUZZLE_RIGHT - up.z() * MUZZLE_DOWN;
            combatFx.spawnMuzzleFlash(muzzleX, muzzleY, muzzleZ, eye.x, eye.y, eye.z,
                    fwd.x(), fwd.y(), fwd.z());
            // 后坐力"只加不回落"：回落由逻辑步里的 decayRecoil(dt) 推进。
            // 分成两处是刻意的 —— 本方法每次开火调用一次，而回落必须按时长推进，
            // 两者的频率不同源。
            player.camera().addRecoilPitch(com.skyisland.player.Camera.RECOIL_PITCH_PER_SHOT_DEG);
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
        public void onReloadCancelled() {
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
        // 顺序不可调换：先 ensureAreaLoaded 生成全部区块，再读档应用增量。
        // 存档的 applySavedBlock 要求目标区块<u>已加载</u>，否则改动会被跳过并告警 ——
        // 反过来做会得到"读档成功但改动全丢"这种最难查的静默失败。
        world = new World(config.seed(), new TestWorldGenerator());
        player = new Player(TestWorldGenerator.spawnX(),
                TestWorldGenerator.spawnY(), TestWorldGenerator.spawnZ());
        long genStart = System.nanoTime();
        world.ensureAreaLoaded(TestWorldGenerator.MIN_CHUNK, TestWorldGenerator.MIN_CHUNK,
                TestWorldGenerator.MAX_CHUNK, TestWorldGenerator.MAX_CHUNK);
        Log.info("[世界] 已生成 %d 个区块（%d×%d），耗时 %.1f ms —— %s",
                world.loadedChunkCount(),
                TestWorldGenerator.MAX_CHUNK - TestWorldGenerator.MIN_CHUNK + 1,
                TestWorldGenerator.MAX_CHUNK - TestWorldGenerator.MIN_CHUNK + 1,
                (System.nanoTime() - genStart) / 1e6, world.statsLine());

        // ---------- 3) 存档：有则读，无则新世界 ----------
        saveManager = new SaveManager(config.saveRoot(), config.worldName());
        if (saveManager.worldExists()) {
            initialLoadResult = saveManager.loadInto(world, player);
            if (initialLoadResult.success()) {
                showEvent("Loaded save " + config.worldName() + " ("
                        + initialLoadResult.chunksLoaded() + " chunks / "
                        + initialLoadResult.blocksApplied() + " block edits)", 4.0);
            } else {
                showEvent("Load failed, continuing as new world: "
                        + initialLoadResult.summary(), 6.0);
            }
        } else {
            showEvent("New world: " + config.worldName() + "  seed=" + config.seed(), 4.0);
            Log.info("[存档] 未找到 %s，按新世界启动", saveManager.worldDirectory());
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
        audioFeedback = AudioFeedback.wrap(audio);
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
     * 发放 M2 开局装备：手枪 ×1 + 手枪弹 ×24（PRD 5.4.1 / 初始物资表）。
     *
     * <p>24 发不是随手取的数：弹匣容量 12，PRD 明文写"2 个满弹匣"。
     * 写成 {@code 2 * magazineSize} 而不是字面量 24，是为了让"改弹匣容量"这件事
     * 只改一处 —— 否则数值一改，这条初始物资就悄悄变得不是"两个满弹匣"了。
     *
     * <p>发完<b>不主动切换选中槽</b>：手枪会落进第一个空槽（新世界即第 1 格），
     * 玩家开局手里就是枪，这符合"开局装备"的直觉。
     * 反过来说，任何"手里必须是空手"的脚本都必须显式声明 ——
     * {@code M1ScriptedSelfTest} 的挖掘与放置阶段正是这么做的（它先切到空格）。
     */
    private void grantStartingGear(String reason) {
        int ammo = 2 * ItemRegistry.pistol().gun().magazineSize();
        int leftoverGun = player.inventory().add(
                ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID), 1);
        int leftoverAmmo = player.inventory().add(
                ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), ammo);
        if (leftoverGun != 0 || leftoverAmmo != 0) {
            // 快捷栏只有 9 格，装不下就是真的装不下 —— 必须说出来，
            // 否则症状是"开局没枪"，而原因看起来像是掉落了。
            Log.noteWarning("战斗", "开局装备未能全部放入快捷栏（枪余 " + leftoverGun
                    + " / 弹药余 " + leftoverAmmo + "），请检查快捷栏容量。");
        }
        showEvent(Localization.text(Localization.MSG_GEAR_GRANTED, ammo), 4.0);
        Log.info("[战斗] %s：手枪 ×1、手枪弹 ×%d（弹匣容量 %d）",
                reason, ammo, ItemRegistry.pistol().gun().magazineSize());
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
        mainMenuScreen = Menus.mainMenu();
        pauseMenuScreen = Menus.pauseMenu();
        settingsMenu = new SettingsMenuController(settings);
        Log.info("[界面] 菜单层已就绪：主菜单 %d 项 / 暂停 %d 项 / 设置 %d 项（键位 %d 个）",
                mainMenuScreen.size(), pauseMenuScreen.size(),
                settingsMenu.screen().size(), com.skyisland.settings.Action.values().length);
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
     * <p>可用 {@code -Dskyisland.startState=menu|playing} 覆盖以上全部判断。
     */
    private UiState resolveInitialUiState() {
        String explicit = config.startState();
        if (!explicit.isBlank()) {
            UiState requested = "playing".equalsIgnoreCase(explicit) ? UiState.PLAYING : UiState.MAIN_MENU;
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
        Log.info("输入来源            : %s", config.selfTest() || config.combatSelfTest()
                ? "进程内脚本化意图（TR7：本机无法注入合成键盘输入）"
                : "GLFW 真实键鼠");
        Log.info("方块注册表          : %d 种", BlockRegistry.size());
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
            frameQuantities.accumulateDiscrete(polled.usePressed(), polled.reloadPressed(),
                    polled.hotbarSlot());
            frameIntent = polled.withLook(0, 0).withScroll(0)
                    .withUsePressed(false).withReloadPressed(false).withHotbarSlot(-1);
            handleFrameEdges();
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
            hud.showDebugOverlay = !hud.showDebugOverlay;
            // M2.1：F3 同时开关实体的碰撞箱线框。EntityRenderer.setDebugHitbox 此前是
            // 一处"死接线"——方法齐备、渲染分支也在，但全项目零调用，于是 F3 永远看不到碰撞箱。
            // 这里把它接到唯一的调试开关上：F3 开 = overlay + 碰撞箱都开，两者语义同为"给我的眼睛看"。
            // 注意它是 static 方法，只加调用点，不改 EntityRenderer 本身。
            EntityRenderer.setDebugHitbox(hud.showDebugOverlay);
            Log.info("[HUD] F3 调试 overlay: %s（实体碰撞箱线框 %s）",
                    hud.showDebugOverlay ? "开" : "关",
                    hud.showDebugOverlay ? "开" : "关");
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

        int candidateCount = (int) Math.round(
                (SPAWN_CANDIDATE_MAX - SPAWN_CANDIDATE_MIN) / SPAWN_CANDIDATE_STEP) + 1;

        for (int i = 0; i < candidateCount; i++) {
            double t = SPAWN_CANDIDATE_MIN + i * SPAWN_CANDIDATE_STEP;
            double cx = feetX + fx * t;
            double cz = feetZ + fz * t;

            // (a)+(b) 地面与头顶净空：复用缺陷 B 的竖直落地搜索。
            Double groundY = findSpawnGroundY(cx, feetY, cz);
            if (groundY == null) {
                continue;
            }
            double dy = groundY - feetY;
            if (Math.abs(dy) > SPAWN_MAX_VERTICAL_OFFSET) {
                continue;
            }

            double centerY = groundY + SPAWN_MONSTER_CENTER_HEIGHT;

            // (c) 视锥：单点测试用一个退化的 AABB（min == max）。
            // Frustum 的 p-vertex 判定是保守的（可能多判可见），对"别把该看见的刷没"这个方向是对的。
            if (frustum != null && !frustum.intersectsAABB(
                    (float) cx, (float) centerY, (float) cz,
                    (float) cx, (float) centerY, (float) cz)) {
                continue;
            }

            // (d) 视线：眼睛 → 怪物中心的体素射线。
            if (!hasLineOfSight(cx, centerY, cz)) {
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
            Vector3d eye = player.eyePosition();
            double angleDeg = angleDegBetween(
                    cam.forward().x(), cam.forward().y(), cam.forward().z(),
                    cx - eye.x, centerY - eye.y, cz - eye.z);
            Log.info("[战斗] F4 刷怪 水平前向=(%.3f, %.3f) 俯仰=%.1f° 选中距离 t=%.1f "
                            + "落点=(%.2f, %.2f, %.2f) 水平距离=%.2f 竖直落差 Δy=%.2f "
                            + "视锥=%b 视线=%b 相机前向与落点方向夹角=%.1f°",
                    fx, fz, pitchDeg, t, cx, groundY, cz, horizontal, dy, true, true, angleDeg);
            return monster;
        }

        // 一个候选都不满足 → 绝不生成（宁可什么都不发生，也不刷一只玩家找不到的怪）。
        Log.noteWarning("SkyIslandGame", String.format(
                "F4 刷怪失败：水平前方 %.0f–%.0f 格内没有同时满足 地面 / 落差 / 视锥 / 视线 的候选位置",
                SPAWN_CANDIDATE_MIN, SPAWN_CANDIDATE_MAX));
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

    /** 当前界面下的菜单屏；游玩中没有菜单。 */
    private MenuScreen activeMenu() {
        return switch (ui.state()) {
            case MAIN_MENU -> mainMenuScreen;
            case PAUSED -> pauseMenuScreen;
            case SETTINGS -> settingsMenu == null ? null : settingsMenu.screen();
            case PLAYING -> null;
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
    private void activateEntry(String entryId) {
        if (entryId == null) {
            return;
        }
        switch (ui.state()) {
            case MAIN_MENU -> {
                if (Menus.ID_START_GAME.equals(entryId)) {
                    if (ui.startGame()) {
                        Log.info("[界面] 开始游戏");
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
        tickEventMessage(fixedDt);

        // ---- 预热结束：重置统计，开始正式测量 ----
        if (!warmupDone && elapsedSeconds >= config.warmupSeconds()) {
            warmupDone = true;
            loop.stats().reset();
            input.resetStats();
            Log.info("[测量] 预热结束（%.1f 秒），统计已重置，正式测量开始。", elapsedSeconds);
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
        player.camera().decayRecoil(fixedDt);
        updateAimHint();

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

        renderer.clear();
        // 玩家一起传进去：裂纹叠加层需要"当前挖掘目标 + 进度"。
        // 菜单期间 player 非 null 但玩家没有挖掘动作，叠加层自然不画。
        // M2：实体随世界一起传，它们在区块之后、裂纹之前绘制（见 Renderer.renderWorld）。
        renderer.renderWorld(world, player.camera(), player, entities.all(), combatFx);

        // M2.1：手持物 pass 在世界之后、HUD 之前（顺序理由见 Renderer#renderViewmodel）。
        updateViewmodel();
        renderer.renderViewmodel(viewmodel);

        updateHud();
        renderer.renderHud(hud);

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

    /** 各界面下的底部操作提示（ASCII）。 */
    private String footerHint() {
        return switch (ui.state()) {
            case MAIN_MENU -> "Up/Down = move    Enter / Click = select";
            case PAUSED -> "Up/Down = move    Enter / Click = select    Esc = resume";
            case SETTINGS -> "Enter = toggle / rebind    Left/Right = adjust    Esc = back";
            case PLAYING -> "";
        };
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
        for (int i = 0; i < hud.hotbarRuntimeId.length; i++) {
            ItemStack stack = player.inventory().slot(i);
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
        hud.reserveAmmo = player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID);
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
        hud.showFps = settings.showFps();
        hud.uiStateLabel = ui.state().label();
        hud.uiTimeSeconds = elapsedSeconds;

        hud.extraDebugLines.clear();
        String mode = selfTest != null ? "M1 脚本化自测"
                : (combatSelfTest != null ? "M2 战斗自测"
                : (uiSelfTest != null ? "M1.5 界面自测" : "交互试玩"));
        hud.extraDebugLines.add("模式 " + mode + "  UI " + ui.state().label()
                + "  世界 " + config.worldName() + "  seed=" + config.seed());
        hud.extraDebugLines.add("存档 " + saveManager.worldDirectory());
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
            Log.info("[界面] 已返回主菜单（世界保留在内存中，可再次开始游戏继续游玩）");
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
        SaveResult result = saveManager.save(world, player);
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
        public void grantDebugSupply() {
            grantStartingGear("M2 战斗自测补给（F6 路径）");
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

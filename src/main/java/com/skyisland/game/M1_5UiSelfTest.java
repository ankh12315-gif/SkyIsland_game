package com.skyisland.game;

import com.skyisland.input.InputMapper;
import com.skyisland.physics.RaycastHit;
import com.skyisland.player.Camera;
import com.skyisland.player.Inventory;
import com.skyisland.player.ItemStack;
import com.skyisland.player.Player;
import com.skyisland.save.SaveFormat;
import com.skyisland.save.SaveResult;
import com.skyisland.settings.Action;
import com.skyisland.settings.GameSettings;
import com.skyisland.settings.InputBinding;
import com.skyisland.settings.SettingsStore;
import com.skyisland.ui.KeyRebindController;
import com.skyisland.ui.MenuScreen;
import com.skyisland.ui.Menus;
import com.skyisland.ui.SettingsMenuController;
import com.skyisland.ui.UiState;
import com.skyisland.ui.UiStateMachine;
import com.skyisland.util.Log;
import com.skyisland.world.World;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * M1.5 进程内脚本化自测：<b>前段界面闭环</b>（M1.5 规格第 11/12 条）。
 *
 * <p><b>它与 M1 自测的关系：</b>两者是同一条原则的两个实例 ——
 * 被测对象是"同一条产品代码链路"，被绕开的只有 {@code OS → GLFW} 这一段
 * （TR7：本机无法把合成键鼠事件送达窗口）。区别在于：
 * M1 自测注入的是 {@code PlayerIntent}（绕开整个输入层），
 * 而本类注入的是<b>原始键事件与鼠标位移</b>（{@code InputState.onKey} /
 * {@code injectCursorDelta}），因此它<u>额外覆盖了输入层</u>：
 * 键位表查询、动作映射、灵敏度换算、按下沿语义全都在这条链路上。
 *
 * <p><b>为什么它必须存在（单元测试覆盖不到的部分）：</b>
 * 下列事实只有在真正的窗口 + GLFW + 主循环里才能被观察到：
 * <ul>
 *   <li>暂停时<b>物理真的没有推进</b>——这要求"逻辑步被调用但被拒绝"这件事
 *       真的发生过（{@code pausedStepSkips} 计数），而不是"没人调用逻辑步"；</li>
 *   <li>ESC 的按下沿只触发<b>一次</b>状态迁移（M1 里 ESC 用的是电平判定，
 *       换成"暂停/继续"开关后，电平判定会在一帧内来回切换）；</li>
 *   <li>光标模式（隐藏锁定 / 可见）随界面状态真的切了；</li>
 *   <li>设置写盘之后，<b>重新读回来</b>与内存中的值一致；</li>
 *   <li>损坏的设置文件不会让启动失败，而是备份 + 回默认。</li>
 * </ul>
 *
 * <p><b>阶段之间用"条件 + 上限帧数"推进，而不是固定帧预算：</b>
 * 帧率在 60（VSync 开）到数千之间浮动，固定帧预算会让"等 60 个逻辑步"
 * 这类条件在不同机器上表现完全不同（M1 自测的 JUMP 预算教训：
 * 凡断言"某过程已结束"，预算必须由时间常数推导）。这里改成"条件满足即推进，
 * 超上限才失败"，既快又与环境解耦。
 */
public final class M1_5UiSelfTest {

    /** 单个阶段的帧数上限。超过即判该阶段失败 —— 保证自动化运行不会挂死。 */
    private static final int MAX_FRAMES_PER_STAGE = 20000;

    /** 暂停期间要求被抑制的逻辑步数：≥60 才足以说明"物理真的没走"。 */
    private static final int REQUIRED_PAUSED_SKIPS = 60;

    /** 视角子检查注入的横移量（像素）：1.5 倍下合 18°，肉眼明确可辨，且可逐像素对账。 */
    private static final double LOOK_INJECT_PIXELS = 100;

    /**
     * 灵敏度断言的容差（度）。
     *
     * <p><b>为什么可以从 ±1.0 收紧到 ±0.05：</b>"像素 × 换算率"是一条纯算术链路，
     * 中间没有任何量化或平滑。在"测量窗口里只有本次注入的那一份位移"这一前提下，
     * 实测值与期望值的差就只有浮点舍入（量级 1e-15 度）—— 也就是说 ±1.0° 的容差
     * 比它要容忍的误差大 15 个数量级，实际上等于没有断言。
     * 而"窗口里只有本次注入"这件事由 {@link #LOOK_MAX_ATTEMPTS} 的记账与重测保证
     * （见 {@code evaluate} 的 LOOK_SENSITIVITY 分支）。
     * 0.05° 在 1.5 倍灵敏度下相当于 0.28 px，仍比浮点舍入宽 13 个数量级，
     * 却比原来严 20 倍 —— 这是收紧，不是放宽。
     */
    private static final double YAW_TOLERANCE_DEG = 0.05;

    /**
     * 线性断言的容差（度）——<b>由分量容差推导，不是挑出来的</b>。
     *
     * <p>线性断言比较的是两个各自带容差的量。若 {@code Δ15 = 18 + e1}、
     * {@code Δ05 = 6 + e2} 且 {@code |e| ≤ YAW_TOLERANCE_DEG}，则
     * {@code |Δ15 − 3·Δ05| = |e1 − 3·e2| ≤ (1 + 3)·YAW_TOLERANCE_DEG}。
     * 这就是本常量的全部依据：分量容差的四倍。
     *
     * <p>上一版把这条硬编码成 {@code < 0.5}，而分量的容差是 ±1.0 —— 两个输入各偏
     * 1.0 时组合差异可以合法地到 4.0，因此那条断言比它自己的分量断言还严，
     * 在算术上不可能同时自洽。实测 {@code 17.280 vs 3 × 6.060} 正是这么来的：
     * 分量各自都在容差内，组合差异被一条推导不出来的阈值判死。
     */
    private static final double LOOK_LINEARITY_TOLERANCE_DEG = 4 * YAW_TOLERANCE_DEG;

    /** 视角子检查注入后最多等多少帧才放弃等待（放弃即断言失败，但绝不挂死）。 */
    private static final int LOOK_MAX_WAIT_FRAMES = 1200;

    /**
     * 一条视角子检查最多重测几次。
     *
     * <p>注入的位移与真实鼠标位移走的是<b>同一个累加器</b>（{@code InputState#frameDeltaX/Y}：
     * {@code injectCursorDelta} 与 GLFW 回调 {@code onCursorPos} 都往里加），
     * 因此本机只要有真实鼠标（或触控板）动一下，测量窗口里的像素量就不再是
     * "脚本注入的 100"。那不是产品的换算错误，而是自测<b>没法把自己的注入与环境输入分开</b>。
     * 处理方式是把这个窗口<b>作废重测</b>（不是放宽容差：放宽容差会把"产品真的算错了"
     * 一并放过），重试用尽才失败，并且失败信息必须点名"环境鼠标输入"。
     */
    private static final int LOOK_MAX_ATTEMPTS = 5;

    /** 视角子检查的条数：1.5 倍 / 0.5 倍 / 反转 Y。 */
    private static final int LOOK_CHECKS = 3;

    /**
     * 挖掘阶段：按下左键后最多等多少个逻辑步仍无任何进度，就判定前提不成立。
     *
     * <p>正常实现下"进度 > 0"在按下的<u>同一个逻辑步</u>就会出现
     * （{@code updateMining} 先把进度置 0、再加 dt）。300 步 = 5 秒是 300 倍裕量，
     * 不可能误判。它的价值在于：条件不可能达成时立刻给出可读原因，
     * 而不是烧掉 20000 帧让人误以为是性能问题或死锁 —— 此前正是如此。
     */
    private static final int MINE_PROGRESS_WATCHDOG_STEPS = 300;

    /**
     * 松开移动键后，等多少个<b>逻辑步</b>再判定"玩家停下了"。
     *
     * <p>取 30 步 = 0.5 秒，比人眼"立刻停住"的感受阈值（≈0.1 秒）宽 5 倍，
     * 因此它证明的是"确实会停"，而不是"停得多快"——后者是手感问题，
     * 由 {@code PlayerPhysicsTest} 用解析式推导出的时间常数单独断言。
     */
    private static final int STOP_CHECK_STEPS = 30;

    /**
     * 挖掘阶段把视线压到的俯仰角（度）。
     *
     * <p>用相机 setter 直接设角度，而不是注入几百像素的鼠标位移：
     * 前者是<b>确定</b>的，后者要依赖当前灵敏度倍数（换个设置值就得重算位移量）。
     * 取 −89.0 而不是下限 −89.5，是为了让"贴着上限"这种边界情况
     * 由 M1 自测的 pitch 钳制断言单独负责，这里的失败原因保持单一。
     */
    private static final double MINE_PITCH_DEG = -89.0;

    /**
     * 截图取景门槛：挖掘进度达到该比例才拍"挖掘中"那张。
     *
     * <p>进度条宽 140 px，35% ≈ 49 px 填充 —— 肉眼能明确分辨"条在动"。
     * 若在 1% 时截图，填充只有 1.4 px，等于没证据。
     */
    private static final double MINE_SCREENSHOT_PROGRESS = 0.35;

    /**
     * 第二张取证图的取景门槛：临近破坏时裂纹应已长出大半。
     *
     * <p>0.85 × 10 段 = 9 段。只拍 35% 那一张的话，"裂纹在长"只有前 4 段作证；
     * 两张一对比，人才看得出它是"逐渐浮现"而不是"一次性画上"。
     */
    private static final double MINE_LATE_SCREENSHOT_PROGRESS = 0.85;

    /**
     * 开背包后至少要再推进的逻辑步数（M2.2）。
     *
     * <p>这是"开背包不暂停世界"这条产品语义<b>唯一能变成数字</b>的地方：
     * 10 步 ≈ 1/6 秒，足以与"一个逻辑步都没跑"区分开，又不至于把自测拖长。
     * {@code INVENTORY.simulationRunning() == true} 本身由
     * {@code UiStateMachineTest} 单测钉住，这里钉的是<b>接线</b>：
     * 状态说"世界在跑"，运行时就必须真的在跑。
     */
    private static final int INVENTORY_RUNNING_STEPS = 10;

    /** 宿主：自测需要访问游戏的真实对象，但不该自己造一套。 */
    public interface Host {

        UiStateMachine ui();

        GameSettings settings();

        SettingsMenuController settingsMenu();

        MenuScreen mainMenu();

        MenuScreen pauseMenu();

        Player player();

        World world();

        Path settingsPath();

        Path worldDirectory();

        /** 立即把设置应用到运行时（相机 FOV / 视角换算率 / VSync / HUD 开关）。 */
        void applySettings();

        /** 把设置写盘。 */
        boolean persistSettings(String reason);

        SaveResult lastSaveResult();

        /** 已真正推进过的模拟步数（暂停期间不增加）。 */
        int simulationSteps();

        /**
         * 上一帧世界 pass 实际绘制的裂纹段数（0 = 没画裂纹）。
         *
         * <p><b>读的是上一帧的值</b>：本自测脚本跑在 {@code pollEvents} 的最前面，
         * 此时本帧还没渲染。裂纹是"画出来的东西"，只能在渲染完成之后被观测 ——
         * 要求"同一帧内读到自己画的东西"会造成不可能满足的断言。
         * 等待一个逻辑步之后再读，就自然是上一帧的渲染结果。
         */
        int crackSegments();

        /** 被抑制的逻辑步次数（暂停 / 菜单期间累加）。 */
        long pausedStepSkips();

        /** 游戏内累计时间（秒）。暂停期间不增加。 */
        double gameTimeSeconds();

        /** 窗口当前是否为"隐藏并锁定光标"。 */
        boolean mouseCaptured();

        /** 运行时 VSync 是否开启。 */
        boolean vsyncEnabled();

        /** HUD 是否显示 FPS 行。 */
        boolean hudShowFps();

        /** 注入一次键事件（走 {@code InputState.onKey}，与 GLFW 回调同一条路径）。 */
        void injectKey(int key, boolean press);

        /** 注入一次鼠标键事件。 */
        void injectMouseButton(int button, boolean press);

        /** 注入一段相对鼠标位移。 */
        void injectCursorDelta(double dx, double dy);

        /**
         * 把光标<b>绝对位置</b>播种到输入层（不产生位移）。
         *
         * <p><b>为什么背包阶段需要它：</b>背包的命中判定读的是输入层跟踪的光标位置，
         * 而自测里没有任何办法把真实鼠标搬到"第 3 格的中心"上（本机合成键鼠送不到窗口）。
         * 没有这个方法，"光标像素 → DPI 换算 → 命中哪一格"这段胶水就只能靠人眼试玩。
         * 绕开的只有 {@code OS → GLFW} 这一段：播种之后，判定链路
         * （输入层 → 帧缓冲换算 → {@code InventoryLayout#hitTestAny} → 交互语义）全部是真的。
         *
         * @param x 窗口坐标（与 GLFW 回调同一口径，不是帧缓冲像素）
         */
        void seedCursorPosition(double x, double y);

        /** 上一帧背包界面命中的绝对槽位（{@code -1} = 没命中任何格子）。 */
        int inventoryHoverSlot();

        /**
         * 渲染模型上的"游玩 HUD 层"开关（{@code hud.showGameplayHud}）。
         *
         * <p>读模型而不是读 {@code UiState.gameplayHudVisible()}：后者只是枚举上的一个
         * 派生属性，已经在单测里覆盖；真正会静默失效的是"状态 → HUD 模型"这一步接线。
         */
        boolean hudGameplayVisible();

        /** 渲染模型上的"生命与通知层"开关（{@code hud.showVitals}）。 */
        boolean hudVitalsVisible();

        /**
         * 某绝对槽位的<b>中心</b>，换算回窗口坐标（可直接交给
         * {@link #seedCursorPosition(double, double)}）。
         *
         * <p>返回窗口坐标而不是帧缓冲像素，是为了让"瞄准某一格"这件事与玩家的实际操作
         * 同口径；窗口→帧缓冲的换算仍然由产品代码负责 —— 那正是被测的那一段。
         */
        double[] inventorySlotCenterWindow(int slot);

        /**
         * 输入层<u>累计接收</u>的鼠标横移（像素，含自测注入）。
         *
         * <p>注入与真实回调往同一个累加器里加数，因此只有"读差值"才能算出
         * 某个测量窗口里<u>实际投递</u>了多少像素 —— 这是把"脚本注入的 100 px"
         * 与"环境鼠标动的几 px"分开记账的唯一手段（见 {@link #LOOK_MAX_ATTEMPTS}）。
         */
        double cursorPixelsAccumulatedX();

        /** 输入层累计接收的鼠标纵移（像素，含自测注入）。 */
        double cursorPixelsAccumulatedY();

        /** 按当前界面状态执行一次菜单项激活（与鼠标点击/回车走同一条产品路径）。 */
        void activateMenuEntry(String entryId);

        /** 直接设置灵敏度并立即应用（设置界面里改值的同一条 setter + apply 路径）。 */
        void setSensitivityAndApply(double value);

        /** 直接设置反转 Y 并立即应用。 */
        void setInvertYAndApply(boolean value);

        /** 请求主循环收尾退出。 */
        void requestQuit();

        /**
         * 请求在下一帧渲染阶段截图（GL 调用必须在渲染线程、两缓冲交换之前）。
         *
         * <p><b>为什么界面自测也要截图：</b>本里程碑的大部分交付物是"看得见的东西"
         * ——主菜单封面、设置列表的行高与对齐、重绑提示、暂停菜单。
         * 断言只能证明"状态对了"，证明不了"画面没截断、没重叠、没画到屏幕外面"。
         * 截图是这类问题唯一的证据形式。
         */
        void requestScreenshot(String label);
    }

    private enum Stage {
        INIT("初始状态为主菜单且未推进模拟"),
        OPEN_SETTINGS("主菜单 → 设置"),
        SETTINGS_EDIT("设置项可改且立即生效并落盘"),
        REBIND_ASSIGN("重绑：无冲突直接生效"),
        REBIND_CONFLICT("重绑：冲突 → 确认替换 + 取消（ESC）→ 恢复默认"),
        BACK_TO_MAIN("设置 → 返回主菜单"),
        START_GAME("主菜单 → 开始游戏（光标锁定、模拟开始推进）"),
        MOVEMENT_INPUT("注入 W 后玩家真的移动（输入层 → 物理）"),
        MINE_BY_MOUSE("按住鼠标左键挖掘：进度推进并破坏方块（输入层 → 物理 → 世界）"),
        LOOK_SENSITIVITY("灵敏度 1.5 / 0.5 与反转 Y 立即生效"),
        PAUSE("ESC → 暂停菜单"),
        PAUSED_FREEZE("暂停期间物理/世界时间/交互全部冻结"),
        RESUME("ESC → 继续游戏"),
        INVENTORY_OPEN("E → 打开背包：世界继续推进、光标可见、准星隐藏"),
        INVENTORY_INTERACT("背包内点击取放：命中链路（光标像素 → DPI 换算 → 槽位）真的走通"),
        INVENTORY_CLOSE("E → 关闭背包：回到游玩中且手上不留东西"),
        SAVE_TO_MAIN("暂停菜单 → 保存并返回主菜单"),
        SETTINGS_PERSISTENCE("设置落盘后可被重新读回且一致"),
        CORRUPT_FALLBACK("损坏设置文件 → 备份 + 回默认 + 不崩溃"),
        QUIT("主菜单退出 → 请求收尾（不绕过保存与 GL 清理）"),
        DONE("结束");

        final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    private final Host host;
    private final List<String> results = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    private int stageIndex;
    private boolean entered;
    private int framesInStage;
    private boolean conditionMet;
    private boolean finished;
    private boolean aborted;
    private int totalFrames;

    // ---- 阶段内观测量 ----
    private int simStepsBefore;
    private double gameTimeBefore;
    private double posXBefore;
    private double posYBefore;
    private double posZBefore;
    private long breaksBefore;
    private long placesBefore;
    private int deathsBefore;
    private long worldBreakBefore;
    private long worldPlaceBefore;

    // ---- 背包子检查（M2.2）----
    /** 开背包前的模拟步数：用来证明"开背包不暂停世界"。 */
    private long invStepsBefore = -1;
    /** 第一步点击取走的物品与数量（第二步要放回去的是同一份东西）。 */
    private int invTakenRuntimeId = -1;
    private int invTakenCount = -1;
    private int invPickSlot = -1;
    private int invPlaceSlot = -1;
    /** 点击前那一格里的数量（用来证明"整堆被取走"而不是只取走一部分）。 */
    private int invPickCountBefore = -1;
    /** 点下去那一帧界面认为光标停在哪个槽位（坐标换算是否正确的直接证据）。 */
    private int invHoverAtPick = -2;
    /** 取放前后的槽位物品总数：用来钉住"不丢失、不复制"。 */
    private int invTotalBefore = -1;
    private boolean invCursorChecked;
    private boolean invPlaced;

    // ---- 视角/灵敏度子检查（0 = 1.5 倍，1 = 0.5 倍，2 = 反转 Y）----
    private int lookCheckIndex;
    private boolean lookInjected;
    private int lookInjectSteps;
    private int lookInjectFrame;
    private double lookBefore;
    private double measuredSens15 = Double.NaN;
    /** 本窗口注入前的累计像素量（差值 = 实际投递量）。 */
    private double lookAccumXBefore;
    private double lookAccumYBefore;
    /** 本次测量窗口实际投递的像素量（注入 + 环境）。 */
    private double lookDeliveredX;
    private double lookDeliveredY;
    /** 当前子检查已重测次数。 */
    private int lookAttempts;
    /** 累计因环境鼠标输入而作废的窗口数（写进摘要，供报告引用）。 */
    private int lookContaminatedWindows;

    // ---- 松键收敛子检查 ----
    private boolean moveReleased;
    private int moveReleaseFrame;
    private long moveReleaseSteps;
    private double moveReleaseZ;
    private double moveStopSpeed;
    private double moveStopGlide;

    // ---- MINE_BY_MOUSE：按住鼠标左键（真实鼠标链路）挖掘 ----
    private boolean mineInjected;
    /** 挖掘阶段开始前切到的槽位（第一个"不是枪"的槽位）。 */
    private int mineTargetSlot = -1;
    /** 前置条件（手持物不是枪械）是否已经检查过。 */
    private boolean mineHeldChecked;
    private double mineFirstProgress = Double.NaN;
    private double mineProgressAtScreenshot = Double.NaN;
    private boolean mineShotTaken;
    private long mineStepsAtPress;
    private long mineBreaksBefore;
    private long mineBreakSteps = -1;
    private boolean mineReleased;
    private long mineReleaseSteps;
    private double mineProgressAfterRelease = Double.NaN;
    // 裂纹（PRD「破坏反馈」的 10 段）观测值
    private boolean mineLateShotTaken;
    private double mineLateProgress = Double.NaN;
    private int mineLastCrackSegments;
    private int mineMaxCrackSegments;
    private boolean mineCracksMonotonic = true;
    private int mineCrackSegmentsAfterRelease = -1;

    public M1_5UiSelfTest(Host host) {
        this.host = host;
        Log.info("[UI自测] M1.5 前段界面自测已装载：%d 个阶段（进程内注入原始键事件与鼠标位移）",
                Stage.values().length - 1);
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean allPassed() {
        return failures.isEmpty() && finished;
    }

    public List<String> failures() {
        return List.copyOf(failures);
    }

    // ============================================================ 主入口（每帧一次，在输入轮询之前）

    public void onFrame() {
        if (finished) {
            return;
        }
        totalFrames++;
        Stage stage = Stage.values()[stageIndex];
        if (!entered) {
            enterStage(stage);
            entered = true;
        }
        framesInStage++;
        conditionMet = false;
        try {
            evaluate(stage);
        } catch (Throwable t) {
            Log.error("[UI自测] 阶段 " + stage + " 执行异常", t);
            record("阶段「" + stage.label + "」执行无异常", false, t.toString());
            finish();
            return;
        }
        if (conditionMet) {
            boolean ok = assertStage(stage);
            advance();
            if (!ok) {
                finish();
            }
            return;
        }
        if (framesInStage > MAX_FRAMES_PER_STAGE) {
            record("阶段「" + stage.label + "」在 " + MAX_FRAMES_PER_STAGE + " 帧内达成条件",
                    false, "超时（状态=" + host.ui().state() + "，框架计数=" + framesInStage + "）");
            finish();
        }
    }

    private void advance() {
        // 每个阶段收尾时留一张截图：断言证明"状态对了"，截图证明"画面没坏"。
        // 两者缺一不可 —— 一个把行高写错、把文字画到屏幕外的界面，
        // 所有"状态类"断言都会通过。
        host.requestScreenshot(Stage.values()[stageIndex].name().toLowerCase(java.util.Locale.ROOT));
        stageIndex++;
        entered = false;
        framesInStage = 0;
        if (stageIndex >= Stage.values().length - 1) {
            finish();
        }
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        String skipped = skippedLabels();
        if (aborted && !skipped.isEmpty()) {
            Log.error("[UI自测] 因前序失败而提前结束（跳过阶段：%s）", skipped);
        }
        Log.info("[UI自测] 脚本执行完毕：%d 项断言，%d 项失败，共 %d 帧",
                results.size(), failures.size(), totalFrames);
    }

    private String skippedLabels() {
        StringBuilder sb = new StringBuilder();
        for (int i = stageIndex + 1; i < Stage.values().length - 1; i++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(Stage.values()[i].label);
        }
        return sb.length() == 0 ? "(无)" : sb.toString();
    }

    // ============================================================ 阶段实现

    private void enterStage(Stage stage) {
        Log.info("[UI自测] ▶ 阶段 %d/%d %s", stageIndex + 1, Stage.values().length - 1, stage.label);
        switch (stage) {
            case LOOK_SENSITIVITY -> {
                lookCheckIndex = 0;
                lookAttempts = 0;
                lookContaminatedWindows = 0;
                prepareLookCheck(0);
            }
            case MOVEMENT_INPUT -> {
                // 松键收敛的两段式状态：进入阶段时归零，避免与上一阶段残留混淆
                moveReleased = false;
                moveReleaseFrame = 0;
                moveReleaseSteps = 0;
                moveReleaseZ = 0.0;
                moveStopSpeed = Double.NaN;
                moveStopGlide = Double.NaN;
            }
            case MINE_BY_MOUSE -> {
                mineInjected = false;
                mineTargetSlot = -1;
                mineHeldChecked = false;
                mineFirstProgress = Double.NaN;
                mineProgressAtScreenshot = Double.NaN;
                mineShotTaken = false;
                mineStepsAtPress = 0;
                mineBreaksBefore = 0;
                mineBreakSteps = -1;
                mineReleased = false;
                mineReleaseSteps = 0;
                mineProgressAfterRelease = Double.NaN;
                mineLateShotTaken = false;
                mineLateProgress = Double.NaN;
                mineLastCrackSegments = 0;
                mineMaxCrackSegments = 0;
                mineCracksMonotonic = true;
                mineCrackSegmentsAfterRelease = -1;
            }
            case PAUSE -> {
                simStepsBefore = host.simulationSteps();
                gameTimeBefore = host.gameTimeSeconds();
            }
            case INVENTORY_OPEN -> {
                // 世界必须继续跑（这是 INVENTORY 与 PAUSED 的根本区别）
                invStepsBefore = host.simulationSteps();
            }
            case INVENTORY_INTERACT -> {
                invTakenRuntimeId = -1;
                invTakenCount = -1;
                invPickSlot = -1;
                invPlaceSlot = -1;
                invPickCountBefore = -1;
                invHoverAtPick = -2;
                invCursorChecked = false;
                invPlaced = false;
                invTotalBefore = host.player().inventory().totalItemCount();
            }
            case PAUSED_FREEZE -> {
                simStepsBefore = host.simulationSteps();
                gameTimeBefore = host.gameTimeSeconds();
                posXBefore = host.player().position().x;
                posYBefore = host.player().position().y;
                posZBefore = host.player().position().z;
                breaksBefore = host.player().blocksBroken();
                placesBefore = host.player().blocksPlaced();
                deathsBefore = host.player().deaths();
                worldBreakBefore = host.world().breakCount();
                worldPlaceBefore = host.world().placeCount();
            }
            default -> {
                // 其余阶段不需要额外准备
            }
        }
    }

    private void evaluate(Stage stage) {
        switch (stage) {
            case INIT -> {
                if (framesInStage >= 3) {
                    conditionMet = true;
                }
            }
            case OPEN_SETTINGS -> {
                if (framesInStage == 1) {
                    host.activateMenuEntry(Menus.ID_OPEN_SETTINGS);
                }
                conditionMet = host.ui().state() == UiState.SETTINGS;
            }
            case SETTINGS_EDIT -> {
                if (framesInStage == 1) {
                    // 灵敏度 +0.25（5 × 0.05）、FOV +10（10 × 1）、三个开关各翻转一次
                    for (int i = 0; i < 5; i++) {
                        host.activateMenuEntry(Menus.ID_SENSITIVITY);
                    }
                    for (int i = 0; i < 10; i++) {
                        host.activateMenuEntry(Menus.ID_FOV);
                    }
                    host.activateMenuEntry(Menus.ID_VSYNC);
                    host.activateMenuEntry(Menus.ID_INVERT_Y);
                    host.activateMenuEntry(Menus.ID_SHOW_FPS);
                }
                GameSettings s = host.settings();
                conditionMet = Math.abs(s.mouseSensitivity() - 1.25) < 1e-6
                        && Math.abs(s.fovDeg() - 80.0) < 1e-6
                        && s.vsync() && s.invertMouseY() && s.showFps();
            }
            case REBIND_ASSIGN -> {
                if (framesInStage == 1) {
                    host.activateMenuEntry(Menus.bindId(Action.MOVE_FORWARD));
                }
                KeyRebindController rebind = host.settingsMenu().rebind();
                if (framesInStage == 2) {
                    // 先留一张"等待输入"的提示画面，再注入按键 ——
                    // 若在同一帧里既截图又注入，截图会拍到注入之后的状态，提示框就丢了。
                    host.requestScreenshot("rebind-waiting");
                }
                if (framesInStage == 3) {
                    host.injectKey(GLFW.GLFW_KEY_UP, true);
                    host.injectKey(GLFW.GLFW_KEY_UP, false);
                }
                conditionMet = rebind.phase() == KeyRebindController.Phase.IDLE
                        && framesInStage > 4
                        && host.settings().keyBindings().get(Action.MOVE_FORWARD)
                        .sameAs(InputBinding.key(GLFW.GLFW_KEY_UP));
            }
            case REBIND_CONFLICT -> {
                KeyRebindController rebind = host.settingsMenu().rebind();
                switch (framesInStage) {
                    case 1 -> host.activateMenuEntry(Menus.bindId(Action.MOVE_BACKWARD));
                    case 2 -> {
                        host.injectKey(GLFW.GLFW_KEY_UP, true);   // UP 此刻属于 MOVE_FORWARD
                        host.injectKey(GLFW.GLFW_KEY_UP, false);
                    }
                    case 3 -> host.requestScreenshot("rebind-conflict");
                    case 4 -> {
                        // 冲突必须已经出现，才允许确认
                        if (rebind.phase() == KeyRebindController.Phase.CONFLICT) {
                            // ★ 趁"冲突成立"的这一刻记录占用方是谁 —— 下一帧它就会被替换掉
                            Action owner = rebind.conflictOwner();
                            record("冲突被检出（把 MOVE_BACKWARD 绑到 UP 时提示占用方）",
                                    owner == Action.MOVE_FORWARD,
                                    "conflictOwner=" + (owner == null ? "null" : owner.id())
                                            + "，pending=" + rebind.pending());
                            host.settingsMenu().onConfirmReplace();
                            // ★ 趁"转移刚刚发生"的这一刻记录结果 —— 第 9 帧的"恢复默认"会把它抹掉
                            record("确认替换后原占用方变为未绑定（MOVE_FORWARD）",
                                    host.settings().keyBindings().get(Action.MOVE_BACKWARD)
                                            .sameAs(InputBinding.key(GLFW.GLFW_KEY_UP))
                                            && !host.settings().keyBindings().isBound(Action.MOVE_FORWARD),
                                    "MOVE_BACKWARD=" + host.settings().keyBindings()
                                            .get(Action.MOVE_BACKWARD).display()
                                            + "，MOVE_FORWARD=" + host.settings().keyBindings()
                                            .get(Action.MOVE_FORWARD).display());
                        } else {
                            // 没进冲突态也要留下失败的证据，否则后面只会看到"恢复默认"通过
                            record("冲突被检出（把 MOVE_BACKWARD 绑到 UP 时提示占用方）",
                                    false, "第 4 帧时重绑流程处于 " + rebind.phase()
                                            + "，而非 CONFLICT（目标 "
                                            + (rebind.target() == null ? "null" : rebind.target().id()) + "）");
                            record("确认替换后原占用方变为未绑定（MOVE_FORWARD）",
                                    false, "未发生替换，无法验证");
                        }
                    }
                    case 6 -> host.activateMenuEntry(Menus.bindId(Action.RELOAD));
                    case 7 -> {
                        host.injectKey(GLFW.GLFW_KEY_ESCAPE, true);   // 等待输入中 ESC = 取消
                        host.injectKey(GLFW.GLFW_KEY_ESCAPE, false);
                    }
                    case 9 -> host.activateMenuEntry(Menus.ID_RESTORE_DEFAULTS);
                    default -> {
                        // 其余帧只等条件
                    }
                }
                if (framesInStage >= 10) {
                    conditionMet = rebind.phase() == KeyRebindController.Phase.IDLE
                            && host.settings().keyBindings().allDefaults();
                }
            }
            case BACK_TO_MAIN -> {
                if (framesInStage == 1) {
                    host.activateMenuEntry(Menus.ID_BACK);
                }
                conditionMet = host.ui().state() == UiState.MAIN_MENU;
            }
            case START_GAME -> {
                if (framesInStage == 1) {
                    host.activateMenuEntry(Menus.ID_START_GAME);
                }
                conditionMet = host.ui().state() == UiState.PLAYING && host.simulationSteps() > 0;
            }
            case MOVEMENT_INPUT -> {
                // ★ 两段式：先证明"按下去会走"，再证明"松开手会停"。
                //   只做前半段是 M1 的漏洞：那时候松键之后水平速度从不衰减，
                //   玩家按下一次方向键就会永久平移（人工试玩才发现）。
                //   规则：任何由输入驱动的持续状态，都必须有一条"输入撤走之后收敛"的断言。
                if (framesInStage == 1) {
                    host.injectKey(GLFW.GLFW_KEY_W, true);
                }
                if (!moveReleased) {
                    boolean movedEnough = host.player().position().z < -0.5;
                    if (movedEnough || framesInStage > 600) {
                        host.injectKey(GLFW.GLFW_KEY_W, false);
                        moveReleased = true;
                        moveReleaseFrame = framesInStage;
                        moveReleaseSteps = host.simulationSteps();
                        moveReleaseZ = host.player().position().z;
                    }
                } else {
                    // 用逻辑步（而不是帧数）计时：帧率在 60～数千之间浮动，
                    // 固定帧数会让"等了多久"随环境变化。逻辑步与物理时间一一对应。
                    if (host.simulationSteps() > moveReleaseSteps + STOP_CHECK_STEPS) {
                        Vector3d v = host.player().velocity();
                        moveStopSpeed = Math.hypot(v.x, v.z);
                        moveStopGlide = Math.abs(host.player().position().z - moveReleaseZ);
                        conditionMet = true;
                    }
                }
            }
            case MINE_BY_MOUSE -> {
                // ★ 为什么必须补这一阶段：
                //   M1 自测与 M1.5 的 MOVEMENT_INPUT 都只覆盖到"脚本给出 PlayerIntent"或
                //   "键盘按住"，而"玩家按住鼠标左键"这条真实链路
                //   （GLFW 鼠标键 → InputState.isMouseDown → InputMapper.actionHeld
                //    → PlayerIntent.attackHeld → 射线 → 进度 → 世界破坏）从未被断言过。
                //   人工试玩报的"长按打碎方块没有反馈"恰好落在这条链路上，
                //   当时只能靠人眼判断，说明覆盖有洞。这条阶段把洞补上。
                if (framesInStage == 1) {
                    // 视线竖直向下：射线必须先打到脚下的方块，否则"没反馈"无法与
                    // "没挖到"区分。角度用 setter 设，不依赖灵敏度设置。
                    host.player().camera().setAngles(0.0, MINE_PITCH_DEG);
                    // ★ M2：手枪是开局装备，占住快捷栏第 1 格并被默认选中 —— 一进游戏
                    //   就是"手持枪械"。而手持枪械时左键是"开火"而不是"挖掘"
                    //   （PRD 5.4；Player#updateMining 在 isGun() 时提前 return 并
                    //   resetMining），于是"按住左键 → 进度推进"永远不可能发生。
                    //   脚本必须显式声明自己手里拿的是什么：按<内容>扫描出第一个
                    //   "不是枪"的槽位（与 M1ScriptedSelfTest#firstNonGunSlot 是同一条
                    //   规则 —— 开局装备的格子布局与数量都可能变，写死槽号的失效方式
                    //   就是"挖掘阶段莫名超时"），再走<真实的数字键切槽链路>把枪换掉。
                    //   直接改 inventory 会把本阶段要断言的"输入层 → 物理 → 世界"绕过一段。
                    mineTargetSlot = firstNonGunSlot();
                    host.injectKey(GLFW.GLFW_KEY_1 + mineTargetSlot, true);
                    host.injectKey(GLFW.GLFW_KEY_1 + mineTargetSlot, false);
                    return;
                }
                if (!mineHeldChecked) {
                    // ★ 前置条件本身也是断言。不这么写的话，"开局装备又改了"这件事
                    //   会以"20000 帧超时（状态=PLAYING）"的样子重现，而真正的原因
                    //   在一个与挖掘毫无关系的文件里。
                    ItemStack held = host.player().inventory().selectedStack();
                    boolean mineable = !held.item().isGun();
                    record("挖掘阶段开始前手里不是枪械（手持枪械时左键是开火，不会挖掘）",
                            mineable,
                            "槽位=" + mineTargetSlot + "，手持=" + held.item().id()
                                    + "，isGun=" + held.item().isGun());
                    mineHeldChecked = true;
                    if (!mineable) {
                        failFast(stage, "前置条件不成立：手里是枪械（" + held.item().id()
                                + "），左键只会开火 —— 本阶段的「进度推进并破坏方块」"
                                + "在前提上不可能达成，因此不进入 20000 帧死等");
                        return;
                    }
                }
                if (!mineInjected) {
                    // 按下放在第 3 帧：让第 1 帧设置的视角与第 2 帧的切槽各走完一次
                    // poll → 逻辑步 → 物理，否则本帧的意图仍由上一帧的朝向/手持物生成。
                    host.injectMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, true);
                    mineInjected = true;
                    mineStepsAtPress = host.simulationSteps();
                    mineBreaksBefore = host.player().blocksBroken();
                    return;
                }
                if (Double.isNaN(mineFirstProgress)) {
                    // ★ 两个"立刻可诊断"的看门狗，取代 20000 帧的死等。
                    //   条件不可达成时，"继续等"永远不会让情况变好，只会把原因埋掉。
                    ItemStack held = host.player().inventory().selectedStack();
                    if (held.item().isGun()) {
                        failFast(stage, "按着左键却手持枪械（" + held.item().id()
                                + "）：左键是开火而不是挖掘，挖掘进度永远不会推进");
                        return;
                    }
                    if (host.simulationSteps() > mineStepsAtPress + MINE_PROGRESS_WATCHDOG_STEPS) {
                        RaycastHit hit = host.player().currentTarget();
                        failFast(stage, "按下左键后 " + MINE_PROGRESS_WATCHDOG_STEPS + " 个逻辑步（"
                                + (MINE_PROGRESS_WATCHDOG_STEPS / 60.0) + " 秒）内进度始终为 0："
                                + "手持=" + held.item().id()
                                + "，射线目标=" + (hit == null ? "无（没打到任何方块）"
                                : hit.blockX() + "," + hit.blockY() + "," + hit.blockZ())
                                + "，状态=" + host.ui().state());
                        return;
                    }
                }
                double progress = host.player().miningProgressFraction();
                if (Double.isNaN(mineFirstProgress) && progress > 0.0) {
                    mineFirstProgress = progress;
                }
                if (!mineShotTaken && progress >= MINE_SCREENSHOT_PROGRESS) {
                    // 进度条此刻已填充 ≥49 px —— 截图里的人眼证据是"条在动"，
                    // 而断言记录的是"进度真的在推进"，两者互补。
                    mineShotTaken = true;
                    mineProgressAtScreenshot = progress;
                    host.requestScreenshot("mine_by_mouse-progress");
                }
                if (!mineLateShotTaken && progress >= MINE_LATE_SCREENSHOT_PROGRESS) {
                    // 第二张取证图：临近破坏时裂纹应已长出大半（≥8 段），
                    // 只拍 35% 那一张的话"裂纹在长"这件事只有前 4 段的证据。
                    mineLateShotTaken = true;
                    mineLateProgress = progress;
                    host.requestScreenshot("mine_by_mouse-late");
                }
                long breaks = host.player().blocksBroken();
                if (mineBreakSteps < 0 && breaks > mineBreaksBefore) {
                    mineBreakSteps = host.simulationSteps() - mineStepsAtPress;
                    host.requestScreenshot("mine_by_mouse-broken");
                    // ★ 破坏当帧必须停止采样裂纹段数：
                    //   破坏会立刻 resetMining()（进度归零），而 host.crackSegments()
                    //   读的是"上一帧的渲染结果"—— 那一帧的渲染发生在破坏之后，
                    //   段数已经是 0。继续采样就会把 9→0 记成"裂纹倒退"，
                    //   于是一条正确的实现被判为单调性失败。这类"采样点跨越了状态跃迁"
                    //   的坑，只能靠把跃迁检测放在采样之前来避免。
                } else if (mineBreakSteps < 0) {
                    int cracks = host.crackSegments();
                    if (cracks < mineLastCrackSegments) {
                        mineCracksMonotonic = false;
                    }
                    mineLastCrackSegments = cracks;
                    mineMaxCrackSegments = Math.max(mineMaxCrackSegments, cracks);
                }
                if (!mineReleased && mineBreakSteps >= 0) {
                    // 破坏成功之后立刻松手：既结束本轮挖掘，又顺带验证"松手后进度归零"
                    // ——与 MOVEMENT_INPUT 的"松键必须停"是同一条原则的两个实例。
                    host.injectMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, false);
                    mineReleased = true;
                    mineReleaseSteps = host.simulationSteps();
                    return;
                }
                if (mineReleased && host.simulationSteps() > mineReleaseSteps + STOP_CHECK_STEPS) {
                    mineProgressAfterRelease = host.player().miningProgressFraction();
                    mineCrackSegmentsAfterRelease = host.crackSegments();
                    conditionMet = true;
                }
            }
            case LOOK_SENSITIVITY -> {
                // ★ 步调由"逻辑步"而不是"帧数"决定。
                //   原因：注入的位移是帧级量，它被施加的时点是"本帧第一个真正执行的逻辑步"。
                //   渲染帧率（VSync 开时通常 120 Hz）与逻辑频率（60 Hz）不成整数倍时，
                //   有些帧根本没有逻辑步 —— 那时位移还没被施加。用固定帧数去等，
                //   会让断言的结果取决于"第几帧恰好轮到逻辑步"，表现为随机的
                //   "同一份位移有时 18°、有时 0°"。等一个逻辑步跑过再读，才是确定的。
                if (lookCheckIndex >= LOOK_CHECKS) {
                    conditionMet = true;
                    return;
                }
                if (!lookInjected) {
                    // 记账：注入前后各读一次"输入层累计接收的鼠标位移"，差值就是本测量窗口
                    // 里<u>实际投递</u>的像素量。注入与环境鼠标走的是同一个累加器
                    // （injectCursorDelta 与 GLFW 回调 onCursorPos 都往里加），
                    // 只有这样才能把两者分开 —— 见 LOOK_MAX_ATTEMPTS 的说明。
                    lookAccumXBefore = host.cursorPixelsAccumulatedX();
                    lookAccumYBefore = host.cursorPixelsAccumulatedY();
                    // 横移用 X 分量、反转 Y 用 Y 分量：两者走的是同一条换算链路的两条支路
                    if (lookCheckIndex == 2) {
                        host.injectCursorDelta(0, LOOK_INJECT_PIXELS);
                    } else {
                        host.injectCursorDelta(LOOK_INJECT_PIXELS, 0);
                    }
                    lookInjected = true;
                    lookInjectSteps = host.simulationSteps();
                    lookInjectFrame = framesInStage;
                    return;
                }
                // 等<b>至少 2 个</b>逻辑步，而不是"至少 1 个"：
                // "一帧多个逻辑步导致同一份位移被重复施加"这一侧的缺陷，只有在
                // 注入之后确实跑过 ≥2 步的帧才会显形（若发生，读数会是 18° 的整数倍）。
                // 正确实现下无论跑多少步，总量都恒等于注入量 —— 因此多等几步只会更严。
                boolean stepRan = host.simulationSteps() > lookInjectSteps + 1;
                if (!stepRan && framesInStage - lookInjectFrame <= LOOK_MAX_WAIT_FRAMES) {
                    return;
                }
                lookDeliveredX = host.cursorPixelsAccumulatedX() - lookAccumXBefore;
                lookDeliveredY = host.cursorPixelsAccumulatedY() - lookAccumYBefore;
                double wantX = lookCheckIndex == 2 ? 0 : LOOK_INJECT_PIXELS;
                double wantY = lookCheckIndex == 2 ? LOOK_INJECT_PIXELS : 0;
                if (lookDeliveredX != wantX || lookDeliveredY != wantY) {
                    // 本窗口混进了真实鼠标位移。那不是"产品的换算错了"，是自测没法把自己
                    // 的注入与环境输入分开（两者共用一个累加器）。
                    // ★ 处理方式是作废重测，<u>不是</u>放宽容差：
                    //   放宽容差会把"产品当真算错了"也一并放过，
                    //   而重测只要有一次窗口是干净的，断言就仍然是逐像素严格的。
                    if (lookAttempts + 1 >= LOOK_MAX_ATTEMPTS) {
                        failFast(stage, String.format(java.util.Locale.ROOT,
                                "视角子检查第 %d 项连测 %d 次，每次测量窗口里都混着额外的真实鼠标位移"
                                        + "（本次投递 Δ=(%.1f, %.1f) px，而脚本只注入了 (%.0f, %.0f) px）"
                                        + "：本机正在接收物理鼠标/触控板输入，自测无法隔离自己的注入量。"
                                        + "这不是换算错误，请在无人操作鼠标的情况下重跑",
                                lookCheckIndex + 1, LOOK_MAX_ATTEMPTS,
                                lookDeliveredX, lookDeliveredY, wantX, wantY));
                        return;
                    }
                    lookAttempts++;
                    lookContaminatedWindows++;
                    Log.warn("[UI自测] 视角子检查第 %d 项：本窗口投递 Δ=(%.1f, %.1f) px，"
                                    + "而非脚本注入的 (%.0f, %.0f) px —— 判定为环境鼠标输入，作废重测（第 %d 次）",
                            lookCheckIndex + 1, lookDeliveredX, lookDeliveredY,
                            wantX, wantY, lookAttempts);
                    prepareLookCheck(lookCheckIndex);   // 角度归零 + 重新注入
                    return;
                }
                measureLookCheck(lookCheckIndex);
                lookCheckIndex++;
                lookAttempts = 0;
                if (lookCheckIndex < LOOK_CHECKS) {
                    prepareLookCheck(lookCheckIndex);
                } else {
                    // 复位到"出厂手感"，避免本阶段把设置留给后续阶段
                    host.setInvertYAndApply(false);
                    host.setSensitivityAndApply(1.0);
                    conditionMet = true;
                }
            }
            case PAUSE -> {
                if (framesInStage == 1) {
                    host.injectKey(GLFW.GLFW_KEY_ESCAPE, true);
                    host.injectKey(GLFW.GLFW_KEY_ESCAPE, false);
                }
                conditionMet = host.ui().state() == UiState.PAUSED;
            }
            case PAUSED_FREEZE -> {
                if (framesInStage == 1) {
                    // 暂停期间仍然"按住"移动与攻击键：产品必须完全不响应
                    host.injectKey(GLFW.GLFW_KEY_W, true);
                    host.injectMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, true);
                }
                conditionMet = host.pausedStepSkips() >= REQUIRED_PAUSED_SKIPS;
            }
            case RESUME -> {
                if (framesInStage == 1) {
                    host.injectKey(GLFW.GLFW_KEY_ESCAPE, true);
                    host.injectKey(GLFW.GLFW_KEY_ESCAPE, false);
                }
                conditionMet = host.ui().state() == UiState.PLAYING
                        && host.simulationSteps() > simStepsBefore + 10;
                if (conditionMet) {
                    host.injectKey(GLFW.GLFW_KEY_W, false);
                    host.injectMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, false);
                }
            }
            case INVENTORY_OPEN -> {
                if (framesInStage == 1) {
                    host.injectKey(GLFW.GLFW_KEY_E, true);
                    host.injectKey(GLFW.GLFW_KEY_E, false);
                }
                // 两个条件缺一不可：界面进了背包，<b>而且</b>世界还在推进。
                // 只断言前者的话，"背包一开世界就停了"（把它做成了 PAUSED 的孪生兄弟）
                // 会照样通过 —— 而那正是本状态存在的意义。
                conditionMet = host.ui().state() == UiState.INVENTORY
                        && host.simulationSteps() > invStepsBefore + INVENTORY_RUNNING_STEPS;
            }
            case INVENTORY_INTERACT -> {
                // ★ 这一阶段要证明的是"光标像素 → DPI 换算 → 命中哪一格 → 交互语义"
                //   这条链真的通。第一次点击取走、第二次点击放下，两次都走真实输入路径
                //   （播种光标 + 注入鼠标左键），因此任何一环错位都会让"物品没被搬动"
                //   或"搬到了别的格子"当场暴露。
                if (framesInStage == 1) {
                    invPickSlot = firstNonEmptySlot();
                    if (invPickSlot < 0) {
                        failFast(stage, "背包里没有任何物品，无法验证取放："
                                + "本阶段需要至少一个非空槽位");
                        return;
                    }
                    invPickCountBefore = host.player().inventory().slot(invPickSlot).count();
                    aimAndClick(invPickSlot);
                    return;
                }
                if (!invCursorChecked) {
                    // 第一次点击的结果：东西应当到了"手上"
                    ItemStack held = host.player().inventory().cursorStack();
                    invTakenRuntimeId = held.isEmpty() ? -1 : held.itemRuntimeId();
                    invTakenCount = held.count();
                    invHoverAtPick = host.inventoryHoverSlot();
                    invCursorChecked = true;
                    if (held.isEmpty()) {
                        failFast(stage, "点击第 " + invPickSlot + " 格后光标上仍然是空的 —— "
                                + "命中链路没走通（悬停槽位=" + host.inventoryHoverSlot() + "）");
                        return;
                    }
                    invPlaceSlot = firstEmptySlot();
                    if (invPlaceSlot < 0) {
                        failFast(stage, "背包里没有空槽位，无法验证放下");
                        return;
                    }
                    aimAndClick(invPlaceSlot);
                    invPlaced = true;
                    return;
                }
                conditionMet = invPlaced;
            }
            case INVENTORY_CLOSE -> {
                if (framesInStage == 1) {
                    host.injectKey(GLFW.GLFW_KEY_E, true);
                    host.injectKey(GLFW.GLFW_KEY_E, false);
                }
                conditionMet = host.ui().state() == UiState.PLAYING;
            }
            case SAVE_TO_MAIN -> {
                if (framesInStage == 1) {
                    host.injectKey(GLFW.GLFW_KEY_ESCAPE, true);
                    host.injectKey(GLFW.GLFW_KEY_ESCAPE, false);
                }
                if (framesInStage == 3 && host.ui().state() == UiState.PAUSED) {
                    host.activateMenuEntry(Menus.ID_SAVE_TO_MAIN_MENU);
                }
                conditionMet = host.ui().state() == UiState.MAIN_MENU && framesInStage > 4;
            }
            case SETTINGS_PERSISTENCE -> {
                if (framesInStage == 1) {
                    host.persistSettings("UI 自测");
                }
                conditionMet = framesInStage >= 2;
            }
            case CORRUPT_FALLBACK -> {
                if (framesInStage == 1) {
                    writeCorruptSibling();
                }
                conditionMet = framesInStage >= 2;
            }
            case QUIT -> {
                if (framesInStage == 1) {
                    host.activateMenuEntry(Menus.ID_QUIT_GAME);
                }
                conditionMet = host.ui().isQuitRequested();
            }
            case DONE -> conditionMet = true;
            default -> throw new IllegalStateException("未处理的阶段: " + stage);
        }
    }

    /**
     * 断言阶段结果。<b>只看"结果事实"，不看实现方式</b>：
     * 例如暂停阶段断言的是"模拟步数没变、位置没变、世界没变"，
     * 而不是"某个 boolean 被置成了 true"。
     *
     * @return 是否全部通过（false 表示应当终止后续阶段）
     */
    private boolean assertStage(Stage stage) {
        boolean ok = true;
        switch (stage) {
            case INIT -> {
                ok &= record("启动后初始界面是主菜单",
                        host.ui().state() == UiState.MAIN_MENU, "实际=" + host.ui().state());
                ok &= record("主菜单期间没有推进任何模拟步",
                        host.simulationSteps() == 0, "simulation_steps=" + host.simulationSteps());
                ok &= record("主菜单期间光标可见（不锁定）",
                        !host.mouseCaptured(), "mouseCaptured=" + host.mouseCaptured());
            }
            case OPEN_SETTINGS -> {
                ok &= record("从主菜单进入设置界面",
                        host.ui().state() == UiState.SETTINGS, "实际=" + host.ui().state());
                ok &= record("设置界面记住来源为主菜单（用于返回）",
                        host.ui().settingsOrigin() == UiState.MAIN_MENU,
                        "origin=" + host.ui().settingsOrigin());
            }
            case SETTINGS_EDIT -> {
                GameSettings s = host.settings();
                ok &= record("灵敏度可由界面调到 1.25",
                        Math.abs(s.mouseSensitivity() - 1.25) < 1e-6, "实际 " + s.mouseSensitivity());
                ok &= record("FOV 可由界面调到 80",
                        Math.abs(s.fovDeg() - 80.0) < 1e-6, "实际 " + s.fovDeg());
                ok &= record("VSync / 反转Y / 显示FPS 三项开关可翻转",
                        s.vsync() && s.invertMouseY() && s.showFps(),
                        "vsync=" + s.vsync() + " invertY=" + s.invertMouseY() + " showFps=" + s.showFps());
                // ---- 立即生效（"立即"的判据是运行期对象的实际取值，不是"设置对象里的值"）----
                double expected = InputMapper.BASE_DEG_PER_PIXEL * s.mouseSensitivity();
                ok &= record("灵敏度改完立即生效（玩家换算率已更新）",
                        Math.abs(host.player().lookDegPerPixel() - expected) < 1e-9,
                        String.format("实际 %.5f 度/像素，期望 %.5f", host.player().lookDegPerPixel(), expected));
                ok &= record("FOV 改完立即生效（相机 FOV 已更新）",
                        Math.abs(host.player().camera().fovDeg() - s.fovDeg()) < 1e-9,
                        "相机 FOV=" + host.player().camera().fovDeg());
                ok &= record("VSync 改完立即生效（窗口交换间隔已更新）",
                        host.vsyncEnabled() == s.vsync(), "窗口 vsync=" + host.vsyncEnabled());
                ok &= record("显示 FPS 改完立即生效（HUD 行已开启）",
                        host.hudShowFps() == s.showFps(), "hud.showFps=" + host.hudShowFps());
                ok &= record("设置已写入磁盘", Files.exists(host.settingsPath()),
                        host.settingsPath().toString());
            }
            case REBIND_ASSIGN -> {
                ok &= record("无冲突时重绑立即生效（MOVE_FORWARD ← UP）",
                        host.settings().keyBindings().get(Action.MOVE_FORWARD)
                                .sameAs(InputBinding.key(GLFW.GLFW_KEY_UP)),
                        "实际 " + host.settings().keyBindings().get(Action.MOVE_FORWARD).display());
            }
            case REBIND_CONFLICT -> {
                // ★ "冲突被检出"与"原占用方变未绑定"这两条<u>不在阶段末尾断言</u>，
                //   必须在本阶段内、事实成立的那一帧记录。
                //   理由：本阶段刻意在最后一步执行"恢复默认"，而恢复默认会把
                //   这两条事实<u>抹掉</u> —— 等到 assertStage 再看，MOVE_BACKWARD 已经
                //   回到 S、MOVE_FORWARD 已经回到 W，断言必然失败，
                //   而失败信息会指向产品（"替换没生效"），实际是测试自己把证据清了。
                //   这类"断言的位置比内容更容易错"的情况，在验收里非常常见。
                ok &= record("等待输入中按 ESC 取消，RELOAD 保持默认 R",
                        host.settings().keyBindings().get(Action.RELOAD)
                                .sameAs(InputBinding.key(GLFW.GLFW_KEY_R)),
                        "RELOAD=" + host.settings().keyBindings().get(Action.RELOAD).display());
                ok &= record("恢复默认后键位表与出厂默认完全一致",
                        host.settings().keyBindings().allDefaults(),
                        "被改动项=" + host.settings().keyBindings().customized().size());
            }
            case BACK_TO_MAIN -> ok &= record("设置界面返回主菜单",
                    host.ui().state() == UiState.MAIN_MENU, "实际=" + host.ui().state());
            case START_GAME -> {
                ok &= record("开始游戏后界面状态为游玩中",
                        host.ui().state() == UiState.PLAYING, "实际=" + host.ui().state());
                ok &= record("游玩中光标被隐藏并锁定", host.mouseCaptured(),
                        "mouseCaptured=" + host.mouseCaptured());
                ok &= record("游玩中模拟步开始推进",
                        host.simulationSteps() > 0, "simulation_steps=" + host.simulationSteps());
            }
            case MOVEMENT_INPUT -> {
                ok &= record("注入 W 键后玩家向 -Z 前进超过 0.5 格",
                        host.player().position().z < -0.5,
                        String.format("z=%.3f", host.player().position().z));
                ok &= record("移动确实走完了输入层 → 意图 → 物理链路",
                        host.player().walkDistance() > 0.5,
                        String.format("行走距离 %.3f 格", host.player().walkDistance()));
                // ---- 松键之后必须停（M1 缺陷回归：无输入时水平速度从不衰减）----
                // 阈值 1e-3 = 行走速度的 0.023%：松键前是 4.317 度/秒量级，衰减 0.5 秒后
                // 只剩 5.4e-4；缺陷状态下这里恒等于 4.317，两者差 4 个数量级，不存在误判。
                // 不要求精确到 0 —— 指数逼近是渐近的，永不严格归零。
                ok &= record("松开移动键后玩家停下（不再一直平移）",
                        moveStopSpeed < 1e-3,
                        String.format("第 %d 帧松键，再等 %.1f 秒（%d 逻辑步）后水平速度 %.3e 格/秒（阈值 1e-3）",
                                moveReleaseFrame, STOP_CHECK_STEPS / 60.0, STOP_CHECK_STEPS, moveStopSpeed));
                ok &= record("松手后的滑行距离远小于一格",
                        moveStopGlide < 0.4,
                        String.format("滑行 %.4f 格", moveStopGlide));
            }
            case MINE_BY_MOUSE -> {
                // 人工试玩报的"长按打碎方块没有动画"到底是不是渲染缺陷？
                // 这三条断言把链路拆成三段分别钉住：
                //   进度是否推进（输入层到物理）→ 方块是否真的被破坏（物理到世界）
                //   → 松手是否归零（输入撤走后收敛）。
                // 若前两条通过而人工仍然"看不到"，问题就只在"反馈强度"（条太细／
                // 没有方块本体的破坏表现），而不在链路 —— 这是两种完全不同的修法。
                ok &= record("按住鼠标左键后进入挖掘状态（进度 > 0）",
                        !Double.isNaN(mineFirstProgress),
                        Double.isNaN(mineFirstProgress)
                                ? "进度始终为 0：真实鼠标链路没有让 attackHeld 变为 true"
                                : String.format("首次观测到进度 %.4f", mineFirstProgress));
                ok &= record("持续按住可破坏方块（走 World Mutation API）",
                        mineBreakSteps >= 0,
                        mineBreakSteps >= 0
                                ? String.format("从按下到破坏共用 %d 逻辑步（%.2f 秒）",
                                        mineBreakSteps, mineBreakSteps / 60.0)
                                : "按下后始终没有方块被破坏");
                ok &= record("截图取到挖掘进行中的画面（进度 ≥ "
                                + Math.round(MINE_SCREENSHOT_PROGRESS * 100) + "%）",
                        mineShotTaken,
                        mineShotTaken
                                ? String.format("截图时进度 %.3f", mineProgressAtScreenshot)
                                : "未取到挖掘中截图（进度没到取景门槛）");
                ok &= record("松开鼠标左键后挖掘进度归零",
                        mineProgressAfterRelease == 0.0,
                        String.format("松手 %d 步后进度=%.4f", STOP_CHECK_STEPS,
                                mineProgressAfterRelease));
                // ---- 破坏反馈（PRD_v0.3.1 §挖掘「破坏反馈」的 10 段裂纹）----
                // 这三条针对的是"补上一条从未实现的【MVP 必须】项"：
                // 前面几条只证明"挖掘逻辑对"，证明不了"方块面上有东西被画出来"。
                ok &= record("挖掘期间方块面上画出了裂纹，且随进度长到 8 段以上",
                        mineMaxCrackSegments >= 8,
                        "观测到的最大段数=" + mineMaxCrackSegments
                                + "（PRD 规定共 10 段；0 表示叠加层从未绘制。"
                                + "精确的「进度→段数」映射由 CrackOverlayTest 断言）");
                ok &= record("裂纹段数随进度单调不减（不闪烁、不倒退）",
                        mineCracksMonotonic,
                        String.format("末尾采样=%d，最大=%d", mineLastCrackSegments, mineMaxCrackSegments));
                ok &= record("停止挖掘后裂纹消失（段数归零）",
                        mineCrackSegmentsAfterRelease == 0,
                        "松手 " + STOP_CHECK_STEPS + " 步后段数=" + mineCrackSegmentsAfterRelease);
                ok &= record("临近破坏时的取证图已取到（进度 ≥ "
                                + Math.round(MINE_LATE_SCREENSHOT_PROGRESS * 100) + "%）",
                        mineLateShotTaken,
                        mineLateShotTaken
                                ? String.format("截图时进度 %.3f（应有 %d 段）", mineLateProgress,
                                        (int) Math.ceil(mineLateProgress * 10))
                                : "未取到（本阶段在到达该进度前就破坏了方块）");
            }
            case LOOK_SENSITIVITY -> ok = true;   // 断言已在阶段内逐条记录
            case PAUSE -> {
                ok &= record("ESC 使界面进入暂停菜单",
                        host.ui().state() == UiState.PAUSED, "实际=" + host.ui().state());
                ok &= record("暂停计数 +1（ESC 只触发一次迁移）",
                        host.ui().pauseCount() == 1, "pause_count=" + host.ui().pauseCount());
                // ★ 光标模式由界面状态驱动，应用点是 beginFrame 末尾的 applyUiMode() ——
                //   也就是"状态迁移所在的那一帧内"就完成。本断言读的是下一帧的值：
                //   脚本跑在 pollEvents 的最前面，此刻上一帧的 beginFrame 已经执行完，
                //   因此这里读到的是"迁移后已施加"的光标模式，而不是迁移前的旧值。
                ok &= record("暂停后光标恢复可见",
                        !host.mouseCaptured(), "mouseCaptured=" + host.mouseCaptured());
            }
            case PAUSED_FREEZE -> {
                ok &= record("暂停期间被抑制的逻辑步 ≥ " + REQUIRED_PAUSED_SKIPS
                                + "（确实有逻辑步被拒绝，而非没人调用）",
                        host.pausedStepSkips() >= REQUIRED_PAUSED_SKIPS,
                        "skipped=" + host.pausedStepSkips());
                ok &= record("暂停期间模拟步数为 0 增长",
                        host.simulationSteps() == simStepsBefore,
                        simStepsBefore + " → " + host.simulationSteps());
                ok &= record("暂停期间游戏内时间冻结",
                        Math.abs(host.gameTimeSeconds() - gameTimeBefore) < 1e-9,
                        String.format("%.4f → %.4f", gameTimeBefore, host.gameTimeSeconds()));
                double moved = Math.abs(host.player().position().x - posXBefore)
                        + Math.abs(host.player().position().y - posYBefore)
                        + Math.abs(host.player().position().z - posZBefore);
                ok &= record("暂停期间玩家位置完全不变（按住 W 也不动）", moved < 1e-12,
                        String.format("位移 %.12f 格", moved));
                ok &= record("暂停期间玩家交互计数不变（按住左键也不挖）",
                        host.player().blocksBroken() == breaksBefore
                                && host.player().blocksPlaced() == placesBefore
                                && host.player().deaths() == deathsBefore,
                        "broke " + breaksBefore + "→" + host.player().blocksBroken()
                                + ", placed " + placesBefore + "→" + host.player().blocksPlaced());
                ok &= record("暂停期间世界未被修改（破坏/放置计数不变）",
                        host.world().breakCount() == worldBreakBefore
                                && host.world().placeCount() == worldPlaceBefore,
                        "world broke " + worldBreakBefore + "→" + host.world().breakCount()
                                + ", placed " + worldPlaceBefore + "→" + host.world().placeCount());
            }
            case RESUME -> {
                ok &= record("ESC 使界面回到游玩中",
                        host.ui().state() == UiState.PLAYING, "实际=" + host.ui().state());
                ok &= record("继续计数 +1", host.ui().resumeCount() == 1,
                        "resume_count=" + host.ui().resumeCount());
                ok &= record("继续后模拟步恢复推进",
                        host.simulationSteps() > simStepsBefore, "simulation_steps="
                                + simStepsBefore + " → " + host.simulationSteps());
            }
            case INVENTORY_OPEN -> {
                ok &= record("E 使界面进入背包",
                        host.ui().state() == UiState.INVENTORY, "实际=" + host.ui().state());
                ok &= record("开背包期间世界仍在推进（背包 ≠ 暂停）",
                        host.simulationSteps() > invStepsBefore + INVENTORY_RUNNING_STEPS,
                        "simulation_steps=" + invStepsBefore + " → " + host.simulationSteps()
                                + "（要求至少 +" + INVENTORY_RUNNING_STEPS + "）");
                ok &= record("开背包后光标可见（要用它点格子）",
                        !host.mouseCaptured(), "mouseCaptured=" + host.mouseCaptured());
                // 这两条读的是<b>渲染模型</b>上的开关，而不是 UiState 的派生属性 ——
                // 派生属性已由 UiStateMachineTest 单测覆盖，这里要钉的是"状态 → 模型"的接线。
                ok &= record("开背包后准星/挖掘条/快捷栏不再绘制"
                                + "（快捷栏改由背包面板自己画，不能画两份）",
                        !host.hudGameplayVisible(), "hud.showGameplayHud=" + host.hudGameplayVisible());
                ok &= record("开背包后生命与通知仍然显示（否则开了背包就看不见自己在挨打）",
                        host.hudVitalsVisible(), "hud.showVitals=" + host.hudVitalsVisible());
            }
            case INVENTORY_INTERACT -> {
                // ① 坐标链路：界面认为光标停在"我瞄准的那一格"上。
                //    这一条是 DPI/坐标换算写错时唯一会先红的地方 ——
                //    换算反了的话，点下去命中的会是另一格，而"物品确实被搬动了"
                //    这种弱断言照样会绿。
                ok &= record("光标瞄准第 " + invPickSlot + " 格时，界面命中的就是这一格",
                        invHoverAtPick == invPickSlot,
                        "hover_slot=" + invHoverAtPick + "，aim=" + invPickSlot);
                // ② 整堆取走：数量与格子原值一致（部分丢失会在这里红）
                ok &= record("点击后整堆到了光标上（数量与原格一致）",
                        invTakenCount == invPickCountBefore && invTakenCount > 0,
                        "取走 " + invTakenCount + "，原格 " + invPickCountBefore);
                ok &= record("被取走的格子已空",
                        host.player().inventory().slot(invPickSlot).isEmpty(),
                        "slot(" + invPickSlot + ")=" + host.player().inventory().slot(invPickSlot));
                // ③ 放到空格：同一份东西落在第 invPlaceSlot 格
                ItemStack placed = host.player().inventory().slot(invPlaceSlot);
                ok &= record("再次点击空格后，物品落在第 " + invPlaceSlot + " 格",
                        !placed.isEmpty() && placed.itemRuntimeId() == invTakenRuntimeId
                                && placed.count() == invTakenCount,
                        "slot(" + invPlaceSlot + ")=" + placed);
                ok &= record("光标上的东西已清空（放下就是放下）",
                        host.player().inventory().cursorStack().isEmpty(),
                        "cursor=" + host.player().inventory().cursorStack());
                // ④ 守恒：两次点击合计不能凭空多也不能少
                ok &= record("取放两轮后槽位物品总数不变（不复制、不丢失）",
                        host.player().inventory().totalItemCount() == invTotalBefore,
                        invTotalBefore + " → " + host.player().inventory().totalItemCount());
            }
            case INVENTORY_CLOSE -> {
                ok &= record("E 使界面回到游玩中",
                        host.ui().state() == UiState.PLAYING, "实际=" + host.ui().state());
                ok &= record("关闭背包后光标重新锁定（视角控制交还鼠标）",
                        host.mouseCaptured(), "mouseCaptured=" + host.mouseCaptured());
                ok &= record("关闭背包后手上不留东西",
                        host.player().inventory().cursorStack().isEmpty(),
                        "cursor=" + host.player().inventory().cursorStack());
                ok &= record("关闭背包后槽位物品总数仍与取放前一致",
                        host.player().inventory().totalItemCount() == invTotalBefore,
                        invTotalBefore + " → " + host.player().inventory().totalItemCount());
            }
            case SAVE_TO_MAIN -> {
                ok &= record("暂停菜单返回主菜单成功",
                        host.ui().state() == UiState.MAIN_MENU, "实际=" + host.ui().state());
                SaveResult r = host.lastSaveResult();
                ok &= record("返回主菜单前执行了一次真实存档",
                        r != null && r.success(), r == null ? "null" : r.oneLine());
                ok &= record("level.json 存在",
                        Files.exists(host.worldDirectory().resolve(SaveFormat.LEVEL_FILE)),
                        host.worldDirectory().resolve(SaveFormat.LEVEL_FILE).toString());
            }
            case SETTINGS_PERSISTENCE -> {
                SettingsStore.LoadResult reloaded = SettingsStore.load(host.settingsPath());
                ok &= record("设置文件可被重新读取", reloaded.status() == SettingsStore.Status.LOADED,
                        reloaded.oneLine());
                GameSettings live = host.settings();
                GameSettings readBack = reloaded.settings();
                ok &= record("读回的所有设置项与内存一致",
                        Math.abs(readBack.mouseSensitivity() - live.mouseSensitivity()) < 1e-6
                                && Math.abs(readBack.fovDeg() - live.fovDeg()) < 1e-6
                                && readBack.invertMouseY() == live.invertMouseY()
                                && readBack.vsync() == live.vsync()
                                && readBack.showFps() == live.showFps()
                                && readBack.masterVolume() == live.masterVolume()
                                && readBack.sfxVolume() == live.sfxVolume()
                                && readBack.keyBindings().equals(live.keyBindings()),
                        String.format("磁盘 %.2f/%.0f/%s  内存 %.2f/%.0f/%s",
                                readBack.mouseSensitivity(), readBack.fovDeg(),
                                readBack.keyBindings().get(Action.JUMP).display(),
                                live.mouseSensitivity(), live.fovDeg(),
                                live.keyBindings().get(Action.JUMP).display()));
            }
            case CORRUPT_FALLBACK -> {
                SettingsStore.LoadResult r = corruptResult;
                ok &= record("损坏设置被识别并回退默认",
                        r != null && r.status() == SettingsStore.Status.RECOVERED_FROM_CORRUPT,
                        r == null ? "未执行" : r.oneLine());
                ok &= record("回退后得到的是出厂默认设置",
                        r != null && r.settings().isAllDefaults(),
                        r == null ? "未执行" : "customized=" + r.settings().keyBindings().customized().size());
                ok &= record("损坏文件已被备份留证",
                        SettingsStore.backupExists(corruptPath),
                        corruptPath + " 的 .corrupt-* 备份");
            }
            case QUIT -> ok &= record("主菜单退出请求已被记录（由主循环收尾，不直接结束进程）",
                    host.ui().isQuitRequested(), "quitRequested=" + host.ui().isQuitRequested());
            case DONE -> ok = true;
            default -> throw new IllegalStateException("未处理的阶段断言: " + stage);
        }
        if (conditionMet) {
            Log.info("[UI自测] ✓ 阶段 %s 完成", stage.label);
        }
        return ok;
    }

    // ============================================================ 视角 / 灵敏度子检查

    /**
     * 准备一条视角子检查。
     *
     * <p><b>为什么每条子检查都把相机角度归零：</b>yaw 会被 {@code Camera#addLook}
     * 收敛到 {@code (-180,180]}，pitch 被夹在 ±89.5°。若承接着前一条子检查留下的角度，
     * 一次 18° 的位移可能跨过 180° 边界（读数从 +179 跳到 −163），
     * 或撞上 pitch 上限而"转不动"，断言就会随机失败 ——
     * 而失败信息看起来像"灵敏度没生效"。归零让每条子检查只依赖自己的注入量。
     */
    private void prepareLookCheck(int index) {
        Camera cam = host.player().camera();
        switch (index) {
            case 0 -> {
                host.setSensitivityAndApply(1.5);
                cam.setAngles(0, 0);
                lookBefore = cam.yawDeg();
            }
            case 1 -> {
                host.setSensitivityAndApply(0.5);
                cam.setAngles(0, 0);
                lookBefore = cam.yawDeg();
            }
            case 2 -> {
                // 反转 Y 单独验证"方向"：把灵敏度放回 1.0，期望值就是一个整齐的 +12°
                host.setSensitivityAndApply(1.0);
                host.setInvertYAndApply(true);
                cam.setAngles(0, 0);
                lookBefore = cam.pitchDeg();
            }
            default -> throw new IllegalStateException("未知的视角子检查: " + index);
        }
        lookInjected = false;
    }

    /** 读取并断言一条视角子检查的结果。 */
    private void measureLookCheck(int index) {
        Camera cam = host.player().camera();
        // 期望值从常量推导，不在这里写死角度：基准换算率 × 灵敏度倍数 × 注入像素量。
        double base = InputMapper.BASE_DEG_PER_PIXEL;
        switch (index) {
            case 0 -> {
                double delta = Math.abs(cam.yawDeg() - lookBefore);
                measuredSens15 = delta;
                double expected = base * 1.5 * LOOK_INJECT_PIXELS;
                record(String.format(java.util.Locale.ROOT,
                                "灵敏度 1.5：注入 +%.0f px 横移 → 视角变化 %.1f°"
                                        + "（1.5 × 基准 %.2f 度/像素 × %.0f px）",
                                LOOK_INJECT_PIXELS, expected, base, LOOK_INJECT_PIXELS),
                        Math.abs(delta - expected) < YAW_TOLERANCE_DEG,
                        String.format(java.util.Locale.ROOT,
                                "实测 Δyaw=%.4f°（期望 %.4f°±%.2f；本窗口投递 %.1f px）",
                                delta, expected, YAW_TOLERANCE_DEG, lookDeliveredX));
            }
            case 1 -> {
                double delta = Math.abs(cam.yawDeg() - lookBefore);
                double expected = base * 0.5 * LOOK_INJECT_PIXELS;
                record(String.format(java.util.Locale.ROOT,
                                "灵敏度 0.5：注入 +%.0f px 横移 → 视角变化 %.1f°",
                                LOOK_INJECT_PIXELS, expected),
                        Math.abs(delta - expected) < YAW_TOLERANCE_DEG,
                        String.format(java.util.Locale.ROOT,
                                "实测 Δyaw=%.4f°（期望 %.4f°±%.2f；本窗口投递 %.1f px）",
                                delta, expected, YAW_TOLERANCE_DEG, lookDeliveredX));
                record("两档灵敏度呈线性差异（1.5 : 0.5 = 3 : 1）",
                        Double.isFinite(measuredSens15)
                                && Math.abs(measuredSens15 - 3 * delta)
                                < LOOK_LINEARITY_TOLERANCE_DEG,
                        String.format(java.util.Locale.ROOT,
                                "%.4f° vs 3 × %.4f° = %.4f°（差 %.4f°，容差 %.2f°"
                                        + " = 4 × 分量容差 %.2f°，推导见常量注释）",
                                measuredSens15, delta, 3 * delta,
                                Math.abs(measuredSens15 - 3 * delta),
                                LOOK_LINEARITY_TOLERANCE_DEG, YAW_TOLERANCE_DEG));
            }
            case 2 -> {
                double delta = cam.pitchDeg() - lookBefore;
                double expected = base * 1.0 * LOOK_INJECT_PIXELS;
                record(String.format(java.util.Locale.ROOT,
                                "反转 Y 开启：鼠标向下 → pitch 增大 %.1f°（方向被真正反转）",
                                expected),
                        Math.abs(delta - expected) < YAW_TOLERANCE_DEG,
                        String.format(java.util.Locale.ROOT,
                                "Δpitch=%.4f°（期望 +%.4f°±%.2f；未反转时为 −%.4f°；本窗口投递 %.1f px）",
                                delta, expected, YAW_TOLERANCE_DEG, expected, lookDeliveredY));
            }
            default -> throw new IllegalStateException("未知的视角子检查: " + index);
        }
    }

    // ============================================================ 手持物与死等

    /**
     * 快捷栏里第一个"不是枪"的槽位。
     *
     * <p>与 {@code M1ScriptedSelfTest#firstNonGunSlot} 是<b>同一条规则</b>
     * （判据都是"非枪械即可挖掘"），刻意按<u>内容</u>扫描而不是写死槽号：
     * 开局装备的格子布局与数量都可能变，而写死槽号的失效方式是"挖掘阶段超时"，
     * 真正的原因却在另一个文件里。
     */
    // M2.2：扫描快捷栏、返回相对槽位（0..8），与 M1ScriptedSelfTest#firstNonGunSlot 同一条规则。
    private int firstNonGunSlot() {
        for (int h = 0; h < Inventory.HOTBAR_SIZE; h++) {
            if (!host.player().inventory().hotbarSlot(h).item().isGun()) {
                return h;
            }
        }
        return 0;   // 9 格全是枪：不可能，但返回 0 比返回 −1 更不容易把调用方带进坑
    }

    /**
     * 把光标瞄准某个绝对槽位的中心，并点一次左键。
     *
     * <p><b>为什么"瞄准"这一步是真的：</b>种子写进输入层之后，
     * 剩下的链路全是产品代码 —— 帧缓冲换算、{@code InventoryLayout#hitTestAny}、
     * {@code InventoryInteraction}。自测只替换了"玩家的手在哪里"，
     * 没有替换"游戏怎么判断点到哪一格"。这与 M1 的 TR7 应对方案是同一条原则。
     *
     * <p>注意右键分堆属 M3，因此这里只点左键。
     */
    private void aimAndClick(int slot) {
        double[] center = host.inventorySlotCenterWindow(slot);
        host.seedCursorPosition(center[0], center[1]);
        host.injectMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, true);
        host.injectMouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, false);
    }

    /** 背包里第一个非空槽位（按绝对索引扫 36 格）；没有则 −1。 */
    private int firstNonEmptySlot() {
        Inventory inv = host.player().inventory();
        for (int i = 0; i < Inventory.SLOT_COUNT; i++) {
            if (!inv.slot(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 背包里第一个空槽位（按绝对索引扫 36 格）；没有则 −1。 */
    private int firstEmptySlot() {
        Inventory inv = host.player().inventory();
        for (int i = 0; i < Inventory.SLOT_COUNT; i++) {
            if (inv.slot(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 立刻以失败结束当前阶段。
     *
     * <p><b>为什么需要它：</b>阶段的上限是 {@link #MAX_FRAMES_PER_STAGE} 帧。
     * 当条件在<u>前提上</u>就不可能达成时（例如"手持枪械却等挖掘进度"），
     * 等到超时只会得到一条"超时（状态=PLAYING，框架计数=20001）"——
     * 它看起来像性能问题或死锁，而真正的原因在几分钟之前的一行开局装备日志里。
     * 这类失败必须在发现的那一刻就把原因说出来，这也是"两分钟换一句'超时'"不值。
     */
    private void failFast(Stage stage, String reason) {
        record("阶段「" + stage.label + "」在 " + MAX_FRAMES_PER_STAGE + " 帧内达成条件",
                false, reason);
        finish();
    }

    // ============================================================ 损坏文件构造

    private Path corruptPath;
    private SettingsStore.LoadResult corruptResult;

    /**
     * 在与真实设置<u>同级</u>的目录里造一个损坏文件并尝试载入。
     *
     * <p>为什么不直接破坏真实设置文件：那会让"损坏回退"这条测试反过来
     * 影响后续阶段（例如之后还要用真实设置）。同级临时文件既复用了
     * 真实的目录与权限环境，又不动真实数据。
     */
    private void writeCorruptSibling() {
        try {
            Path dir = host.settingsPath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            corruptPath = dir == null
                    ? Path.of("settings-corrupt-probe.json")
                    : dir.resolve("settings-corrupt-probe.json");
            Files.writeString(corruptPath, "{ this is not valid json ,,,",
                    StandardCharsets.UTF_8);
            corruptResult = SettingsStore.load(corruptPath);
            Log.info("[UI自测] 损坏文件探测完成：%s", corruptResult.oneLine());
        } catch (Exception e) {
            Log.error("[UI自测] 构造损坏设置文件失败", e);
            corruptResult = null;
        }
    }

    // ============================================================ 记录

    private boolean record(String name, boolean passed, String detail) {
        String line = (passed ? "PASS" : "FAIL") + " · " + name + " — " + detail;
        results.add(line);
        if (passed) {
            Log.info("[UI自测] %s", line);
        } else {
            failures.add(name);
            aborted = true;
            Log.error("[UI自测] %s", line);
        }
        return passed;
    }

    /** 结构化摘要（写入日志并供报告摘录）。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("ui_selftest_stages    = ").append(Stage.values().length - 1).append('\n');
        sb.append("ui_selftest_asserts   = ").append(results.size()).append('\n');
        sb.append("ui_selftest_failures  = ").append(failures.size()).append('\n');
        sb.append("ui_selftest_frames    = ").append(totalFrames).append('\n');
        sb.append("ui_selftest_look_contaminated_windows = ")
                .append(lookContaminatedWindows).append('\n');
        sb.append("ui_selftest_result    = ").append(allPassed() ? "PASS" : "FAIL").append('\n');
        sb.append("--\n");
        for (String line : results) {
            sb.append(line).append('\n');
        }
        if (!failures.isEmpty()) {
            sb.append("--\n失败项:\n");
            for (String name : failures) {
                sb.append("  · ").append(name).append('\n');
            }
        }
        return sb.toString();
    }

    /** 供 HUD 显示当前阶段。 */
    public String currentStageLabel() {
        return finished ? "完成" : Stage.values()[Math.min(stageIndex, Stage.values().length - 1)].label;
    }
}

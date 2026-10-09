package com.skyisland.render.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD 需要的全部数据（渲染层的输入模型）。
 *
 * <p><b>为什么用可变对象而不是每帧构造一个 record：</b>字段有三十来个，
 * 每帧构造意味着每帧一次大对象分配 + 十几个字符串拼接。虽然 JVM 的 TLAB 能扛住，
 * 但在 3000 FPS 下就是每秒 3000 次无意义的分配与 GC 压力 —— 而 HUD 的数据来源
 * （世界、玩家、帧统计）本来就已经存在，本类只是把它们<u>汇集</u>到一个地方，
 * 让 HUD 渲染器不去反向依赖游戏逻辑模块（依赖方向 §B.2）。
 *
 * <p><b>字段全部 public 且无访问器</b>：这是刻意的 —— 它是一份"传参包"，
 * 包装成 getter/setter 只会增加噪音而不增加任何安全性（同一包内可见性不变）。
 */
public final class HudModel {

    // ---- 开关 ----
    public boolean showDebugOverlay;

    /**
     * M1.5：是否显示玩法 HUD（准星 / 快捷栏 / 挖掘条 / 读数）。
     *
     * <p>由界面状态决定（只有 {@code PLAYING} 为真）。暂停与菜单期间仍会渲染世界，
     * 但"准星"这种"我现在正瞄着某处"的提示必须消失 —— 否则玩家会以为
     * 自己的鼠标仍在控制视角。
     */
    public boolean showGameplayHud = true;

    /**
     * M2.2：是否显示"生命"这一层（与 {@link #showGameplayHud} 分开）。
     *
     * <p><b>为什么要分成两个开关：</b>{@code showGameplayHud} 的含义是
     * "玩家正在操作这个世界"（准星、快捷栏、挖掘条、武器面板）。
     * 背包打开时它必须为假 —— 否则准星会浮在背包面板上，
     * 而那一刻鼠标正在点格子、不是在瞄准。
     * 但<b>生命不能跟着一起消失</b>：背包打开时世界仍在跑，怪物仍在走过来，
     * 玩家不能因为翻了个包就看不见自己在挨打。
     *
     * <p>把两者合成一个开关的代价，正是"要不一起显示、要不一起消失" ——
     * 而这两种状态在背包里是分别成立的。
     */
    public boolean showVitals = true;

    /**
     * M1.5：是否显示 FPS 读数（设置项 {@code showFps}，默认关闭）。
     *
     * <p>默认关闭是规格要求：FPS 属于调试信息，正式界面里常驻会破坏观感。
     * 关闭时坐标与瞄准信息仍然显示（它们是 M1 验收闭环的一部分）。
     */
    public boolean showFps;

    /** M1.5：当前界面状态名（用于 HUD 的附加调试行）。 */
    public String uiStateLabel = "-";

    /**
     * 游戏内累计时间（秒），供 HUD 做<b>基于时间</b>的动画。
     *
     * <p>为什么不用帧计数：同一个"4 Hz 闪烁"在 60 Hz 与 3000 Hz 下用帧计数实现
     * 会得到两种完全不同的观感（后者快到看不见）。用秒做相位，
     * 帧率高低都不改变语义 —— 这也是 §C.4′ 一类"帧率不得影响行为"的要求。
     */
    public double uiTimeSeconds;

    // ---- 性能 ----
    public double fps;
    public double tps;
    public double meanFrameMs;
    public double p99FrameMs;
    public double maxFrameMs;
    public int spikesOver50Ms;
    public int clampedFrames;

    // ---- 玩家 ----
    public double playerX;
    public double playerY;
    public double playerZ;
    public double yaw;
    public double pitch;
    public boolean onGround;
    public double velocityY;
    public int deaths;

    // ---- 位置派生 ----
    public int blockX;
    public int blockY;
    public int blockZ;
    public int chunkX;
    public int chunkZ;
    public int localX;
    public int localZ;

    // ---- 世界 ----
    public int loadedChunks;
    public int meshCount;
    public int pendingMeshRebuilds;
    public int meshQueueHighWaterMark;
    public long breakCount;
    public long placeCount;
    public long rejectedCount;
    public long neighborMarkCount;
    public long meshBuildCount;
    public double meanMeshBuildMs;
    public int emissiveSourceCount;

    // ---- 渲染 ----
    public int drawCalls;
    public int renderedTriangles;
    public int culledChunks;

    // ---- 瞄准与交互 ----
    public String targetBlockId = "-";
    public String targetFace = "-";
    public double targetDistance;
    public boolean mining;
    public double miningProgress;
    public String miningTargetId = "-";
    public String lastPlacementMessage = "-";
    public long blocksBroken;
    public long blocksPlaced;

    // ---- 快捷栏 ----
    public int hotbarSelected;
    public final int[] hotbarRuntimeId = new int[9];
    /**
     * M2：槽位的<b>物品</b> runtimeId。
     *
     * <p>为什么不复用 {@link #hotbarRuntimeId}：那个字段存的是
     * {@code ItemStack.blockRuntimeId()}，对枪与弹药返回 {@code -1}
     * （0 是空气，−1 才是"不是方块"）。若把二者合成一个字段，
     * "手里是枪"与"这个槽是空的"就会长得一模一样 —— 快捷栏上的手枪会消失。
     * 两个数组并存代价是 9 个 int，换来的是"非方块物品能被画出来"。
     */
    public final int[] hotbarItemRuntimeId = new int[9];
    public final int[] hotbarCount = new int[9];

    // ---- M2：生命与枪械（PRD 5.3 / 5.4.3 / 5.7）----
    /** 生命值；上限由 {@link #maxHealth} 给出（PRD 5.3：20 点）。 */
    public int health = 20;
    public int maxHealth = 20;
    /** 是否处于死亡状态（HUD 据此显示死亡提示而不是生命条）。 */
    public boolean dead;
    /** 死亡倒计时剩余秒数（PRD 5.3：3 秒后重生）。 */
    public double deathTimerLeft;

    /** 手持物是不是枪械 —— 决定 HUD 右下角显示"弹药"还是"手持方块名"。 */
    public boolean holdingGun;
    /**
     * 手持枪械的<b>中文</b>显示名（PRD v0.3.2：玩家可见文案统一简体中文，
     * Display Name 有唯一数据来源）。枪械名属"玩家可见"，因此必须是中文而不是 stable ID。
     */
    public String gunDisplayName = "";
    public int magazineAmmo;
    public int magazineSize;
    /**
     * 后备弹药（不在弹匣里的手枪弹总数）。
     *
     * <p>M2.1：Combat Prototype 口径下后备弹药是<b>无限</b>的，此时本字段保留为
     * 背包里的实际数量（仍有信息价值：它告诉玩家自己"捡到过多少"），
     * 但弹药读数是否显示 {@code ∞} 由 {@link #reserveInfinite} 决定 ——
     * "显示什么"必须跟着"规则是什么"走，不能反过来靠数字猜。
     */
    public int reserveAmmo;
    /** M2.1：后备弹药是否为无限口径（true 时弹药读数画成 {@code 12 / ∞}）。 */
    public boolean reserveInfinite;
    /**
     * M2.1：命中标记的剩余强度 0..1（0 = 不显示）。
     *
     * <p>数值而不是布尔的原因见 {@code CombatFxModel#hitMarker01}：
     * 它是一个<u>会衰减</u>的量，渲染层需要按它做强度插值（收缩 / 淡出）。
     * 本字段是那个量在 HUD 侧的<b>镜像</b> —— 生命周期不在这里，这里不推进时间。
     */
    public double hitMarker;
    public boolean reloading;
    /** 换弹进度 0..1。 */
    public double reloadProgress;

    /** 是否正在瞄准（PRD 5.4.3：准星切换为密集十字）。 */
    public boolean aiming;
    /**
     * 屏幕中心是否指着"可交互的东西"（方块或实体）。
     * PRD 5.4.3 准星行要求"对准可交互方块时高亮"，M2 把可命中的实体也算进来。
     */
    public boolean targetInteractable;
    /** 瞄准目标的中文/可读名称（方块显示名或"怪物"）。 */
    public String targetDisplayName = "-";

    // ---- M4-S8b′：飞行状态常驻显示（2026-10-09）----

    /**
     * ★ 当前是否处于<b>飞行</b>状态。与 {@code onGround} <b>无关</b>。
     *
     * <p>★ 为什么必须有这个常驻指示（这是主理人实测逼出来的）：
     * 站着不动时 {@code onGround == true}，而飞行状态**照样可以是 true**
     * （悬停在地面上方一格）。两者看起来一模一样，于是玩家
     * <b>无法预判双击空格会发生什么</b> ——
     * 实测里就出现了"我以为在走路、双击却是把飞行关掉"，
     * 以及"再双击一次竟然把创造能力全没了"。
     *
     * <p>⇒ 只要处于飞行，这个指示就必须在屏幕上常驻，
     * <b>不论是否站在地面</b>。它是把"不可见的状态"变成"可见的状态"，
     * 而不可见的状态就是不可预测的操作。
     */
    public boolean flying;

    /**
     * ★ 是否处于<b>创造会话</b>（本次运行内的五项能力已开）。
     *
     * <p>与会话退出只差一次 ESC 菜单点击，所以它必须在屏幕上一直写着 ——
     * 玩家有权知道"我现在手上有没有无限方块"。
     */
    public boolean creativeSession;

    /**
     * 下降键当前绑定的显示名（如 {@code "LEFT_CONTROL"}）。
     *
     * <p>为什么提示里要写出来：下降键的默认位从 Shift 改成 Ctrl 是为了绕开
     * 中文输入法（实测 Shift 会被输入法吞掉，见 M4_IME_DETACH_REPORT）。
     * 既然默认键因机器而异，把它<b>显示出来</b>才不会出现
     * "按住提示里那个键却没反应"的困惑。
     */
    public String sneakKeyDisplay = "";

    // ---- M2：世界里的活体 ----
    public int aliveEntities;
    public int totalSpawnedEntities;

    // ---- 事件提示（"已保存"之类，带倒计时淡出） ----
    public String eventMessage = "";
    public double eventSecondsLeft;

    // ---- F3 overlay 的附加行（由游戏层补充，如存档路径、注入模式） ----
    public final List<String> extraDebugLines = new ArrayList<>();

    /** 轻量标量：本帧 HUD 是否处于"挖掘中"（供渲染器决定是否画进度条）。 */
    /** M5a：右上角时刻条文案（由 {@code Localization#HUD_DAY_STATUS} 格式化）。 */
    public String dayStatusLabel = "";

    /** M5a：当前阶段已走过的比例 0..1（画进度条用；1 表示即将换阶段）。 */
    public float dayPhaseProgress;

    /** M5a：是否处于夜晚（含黄昏）。用来给时刻条上色。 */
    public boolean dayIsNight;

    public boolean showMiningBar() {
        return mining && miningProgress > 0;
    }
}

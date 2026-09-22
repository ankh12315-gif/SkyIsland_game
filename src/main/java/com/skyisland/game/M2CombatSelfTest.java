package com.skyisland.game;

import com.skyisland.audio.AudioEvent;
import com.skyisland.audio.AudioManager;
import com.skyisland.audio.RecordingAudioSink;
import com.skyisland.combat.CombatController;
import com.skyisland.combat.DamageFalloff;
import com.skyisland.combat.GunState;
import com.skyisland.entity.Entity;
import com.skyisland.entity.EntityManager;
import com.skyisland.entity.MeleeMonster;
import com.skyisland.item.ItemRegistry;
import com.skyisland.player.ItemStack;
import com.skyisland.player.Player;
import com.skyisland.player.PlayerIntent;
import com.skyisland.render.fx.CombatFxModel;
import com.skyisland.save.SaveManager;
import com.skyisland.save.SaveResult;
import com.skyisland.util.Coords;
import com.skyisland.util.Log;
import com.skyisland.world.World;
import com.skyisland.world.block.BlockRegistry;
import com.skyisland.world.gen.TestWorldGenerator;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * M2 战斗脚本化自测：端到端举证 PRD §12.3 通过标准 #1「能在体素场景中完成一场基础枪战」。
 *
 * <h2>为什么必须是"进程内脚本化意图"而不是"模拟按键"</h2>
 * 本机实测证明合成键鼠输入<u>无法送达任何窗口</u>（TECH_DESIGN_v0.1.1 §T′ TR7：
 * {@code SendInput} 走系统输入队列，已确认前台且持有键盘焦点的窗口仍收不到 KeyDown，
 * 用自建 WinForms 窗口做过对照实验）。因此交互功能只能这样举证：
 * <blockquote>
 *   被测对象是<u>同一条游戏逻辑链路</u>；被绕开的只有 {@code OS → GLFW} 这一段。
 * </blockquote>
 * 本类产出的 {@link PlayerIntent} 与 {@code InputMapper} 产出的完全是同一种对象，
 * 之后经过 {@code 玩家物理 → 实体 tick → 战斗结算 → 射线 → 实体受伤 → 表现粒子 → HUD} 一步不少。
 *
 * <h2>它补的是哪一段缺口</h2>
 * M2 的既有单元测试全部是"同一进程内直接调 API"，它们证明的是各算子的<u>个体正确性</u>；
 * 没有任何一条覆盖「意图 → 固定步长逻辑 → 实体/战斗/表现 → HUD → 截图」这条<u>整链</u>。
 * 本类跑在 {@code SkyIslandGame.stepLogic} 的真实调用点上
 * （{@code entities.tick → combat.step → combatFx.tick}），
 * 因此它是"这场枪战在游戏里真的打得起来"的证据。
 *
 * <h2>脚本是"设置初始条件 + 施加意图 + 断言结果"</h2>
 * {@code teleport} / {@code setAngles} 只用于把玩家摆到可复现的起手位置
 * （否则断言会依赖上一阶段走了多远这种脆弱前提），<u>不跳过</u>任何被测逻辑。
 * 每一处都标注了原因。断言失败一律输出<u>实测值</u>，不允许只说"失败"。
 *
 * <h2>为什么每阶段的步数预算都要写算式</h2>
 * M1 的报告里已记录两次因为"预算拍脑袋"导致的假失败：预算比过程的时间常数短，
 * 于是"过程还没结束"被读成"功能坏了"。这类假失败与真缺陷在日志上长得一样，
 * 排查成本极高。因此本类的每个预算都由该过程的时间常数推导（{@code 1 步 = 1/60 s}），
 * 并在 {@link #STAGE_BUDGET} 的注释里写出算式与余量来源。
 *
 * <h2>与 M1 自测的互斥</h2>
 * 两者都靠"每个逻辑步返回一个意图"驱动，同时开启会争夺同一条意图通道，
 * 结论互相污染。因此 {@code SkyIslandGame} 在启动期就把"两个开关同时打开"判为错误并非零退出，
 * 而不是让其中一个静默获胜。
 */
public final class M2CombatSelfTest implements CombatController.Listener {

    /** 宿主：自测需要访问游戏的实际对象，但不该自己造一套。 */
    public interface Host {
        World world();

        Player player();

        SaveManager saveManager();

        /** 会动的东西（M2 只有近战怪）；自测要读数量、并复用产品的刷怪入口。 */
        EntityManager entities();

        /** 枪械玩法：读统计量（shotsFired / dryFires / blockHits / lastDistance）。 */
        CombatController combat();

        /** 战斗表现的累计读数（破坏粒子数等）。 */
        CombatFxModel combatFx();

        /** 触发一次真实存档（走游戏用的同一条路径）。 */
        SaveResult requestSave();

        /** 请求在下一帧渲染阶段截图（GL 调用必须在渲染线程、两缓冲交换之前）。 */
        void requestScreenshot(String label);

        /** 存档功能是否开启（{@code skyisland.noSave} 的反面）。 */
        boolean saveEnabled();

        /**
         * 走产品自己的 F4 刷怪路径（{@code SkyIslandGame.debugSpawnMonster}）：
         * "准星前方 5 格、落在可站立方块上"。
         *
         * <p>自测刻意<b>不</b>另造一套刷怪代码 —— 否则"刷怪"这条路径在门禁里
         * 与玩家按 F4 时走的不是同一条，通过了也说明不了产品可用。
         *
         * @return 生成的怪物；刷怪失败返回 {@code null}
         */
        MeleeMonster spawnMonsterInFront();

        /** 走产品的 F6「调试补给」路径（{@code SkyIslandGame.grantStartingGear}）。 */
        void grantDebugSupply();

        /**
         * M2.1：音频门面。
         *
         * <p>暴露它不是为了"顺便看看音效有没有响"，而是因为"音频子系统被整块建好却一行没接线"
         * 恰好是 M2.1 里真实发生过的一种失败：<b>包能编译、单测能过、类注释写得很完整，
         * 而游戏主类里没有任何一行创建它</b>。那种状态下"没有声音"与"没有声卡"在观测上
         * 完全一样。{@link RecordingAudioSink} 的事件计数是唯一能把这两件事分开的东西。
         */
        AudioManager audio();
    }

    private enum Stage {
        SETTLE("静置并站稳"),
        GEAR_CHECK("开局装备（手枪 + 2 个满弹匣）"),
        MINE_BLOCKED_WHILE_HOLDING_GUN("持枪时左键是开火不是挖掘"),
        DRY_FIRE("空弹匣空枪"),
        RELOAD_FULL("完整换弹（1.2 秒）"),
        RELOAD_INTERRUPTED("移动打断换弹（弹药不提前转移）"),
        AIM("瞄准（FOV 45/70、移动速度 60%）"),
        SPAWN_AND_APPROACH("刷怪与追击"),
        SHOOT_KILL("逐发击中并致死（20→12→4→0）"),
        WALL_BLOCKS_BULLET("最近合法碰撞（子弹不穿墙）"),
        DAMAGE_FALLOFF("超出有效射程的距离衰减"),
        BREAK_PARTICLES("破坏粒子 8–12 个"),
        DEATH_AND_RESPAWN("死亡与 3 秒重生"),
        SAVE_RELOAD_ROUNDTRIP("存档（读档校验在收尾阶段执行）"),
        DONE("结束");

        final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    /**
     * 各阶段的逻辑步预算。<b>每个数字都由该过程的时间常数推出</b>，算式见行尾与下方注释。
     *
     * <p>常数：{@code 1 步 = 1/60 s}（{@code GameLoop.FIXED_DT}）。
     */
    private static final int[] STAGE_BUDGET = {
            // SETTLE：落定判定是"向下前瞻 0.02 格"，单步自由落体 0.5·g·dt² = 0.5·32·(1/60)² ≈ 0.0044 格，
            // 最多 5 步就能把间隙落完。取 30 步 = 0.5 s，6 倍余量。
            30,
            // GEAR_CHECK：纯读断言，零等待。
            3,
            // MINE_BLOCKED_WHILE_HOLDING_GUN：草方块硬度 0.6 s = 36 步。
            // "没被挖掉"这条断言只有在"刺激时间显著超过本可挖掉的时间"时才有验证力，
            // 因此取 90 步 = 1.5 s（2.5 倍硬度），而不是"够用就好"。
            90,
            // DRY_FIRE：空枪每步都会触发一次 onDryFire（空枪不设冷却），1 步即可观测；取 30 步留观察窗口。
            30,
            // RELOAD_FULL：换弹 1.2 s = 1.2/(1/60) = 72 步。
            // 倒计时是每步累减 dt，浮点误差可能让完成落在第 73 步，取 90 步（1.5 s）留 18 步余量，
            // 同时保证"完成事件"一定在阶段内被观测到（否则断言读到的永远是"未完成"）。
            90,
            /*
             * RELOAD_INTERRUPTED：预算 = 打空 + 按键 + 移动 + 按键 + 走完一次换弹。
             *   · 打空 12 发：射速 4 发/秒 → 发间隔 0.25 s = 15 步；12 发跨越 11 个间隔 = 165 步。
             *     浮点累减会让某个间隔退化为 16 步 → 上界 11×16 = 176 步（首发于第 0 步）。
             *   · 按 R：1 步。
             *   · 移动打断：产品在"移动的那一步"就取消换弹（gun.tick 先判 moving），
             *     1 步即够；取 20 步是为了让位移本身也可观测（约 1.3 格）。
             *   · 再次按 R：1 步。
             *   · 走完换弹：1.2 s = 72 步，浮点上界 73 步。
             *   合计上界 = 176 + 1 + 20 + 1 + 73 = 271 步；取 360 步（余 89 步）。
             */
            360,
            /*
             * AIM：两段各 60 步的位移比较，外加 5 步状态切换。
             *   · 窗口长度取 60 步 = 1 s：速度曲线是 v(t) = target·(1−e^(−18t))，
             *     1 s 时已达 target 的 1 − e^(−18) ≈ 1（加速段早已结束），
             *     两段都从静止起步（teleport 清零速度），因此位移比严格等于目标速度比 0.60。
             *   · 60 步 × 4.317 格/秒 ≈ 4.1 格，仍在平坦平台内（|x|,|z| < 32），不会撞墙。
             *   合计 125 步；取 130 步（余 5 步用于阶段收尾）。
             */
            130,
            /*
             * SPAWN_AND_APPROACH：只验证"它会追人"，因此让怪物走 1.5 格即可（初始距离 4.0 格）。
             *   · 追击速度 2.0 格/秒 → 1.5 格需要 0.75 s = 45 步。
             *   · 必须<b>在进入攻击距离（1.6 格）之前</b>收尾：否则玩家会被咬，
             *     后续阶段的玩家生命就不再是已知量。4.0 − 1.5 = 2.5 > 1.6，安全。
             *   取 45 步（无额外余量是刻意的：多走一步就少一分"没被咬"的保证）。
             */
            45,
            /*
             * SHOOT_KILL：3 发致死（20 → 12 → 4 → 0，每发 8 点，近战怪 20 血）。
             *   · 发间隔 0.25 s = 15 步，浮点上界 16 步；3 发跨越 2 个间隔 → 上界 32 步。
             *   · 命中在该步的 combat.step 内结算完毕（无飞行时间）。
             *   取 90 步 = 1.5 s，留出"第 3 发之后尸体被 EntityManager 清理"的观测余量。
             */
            90,
            /*
             * WALL_BLOCKS_BULLET：开枪 1 发即可，其余步数用于让怪物走到墙前并被挡住。
             *   · 怪物初始 z = −4.5、墙在 z = −3，走近 1.2 格需要 1.2/2.0 = 0.6 s = 36 步。
             *   取 60 步 = 1 s（余 24 步观察"它没能穿过墙来咬人"）。
             */
            60,
            // DAMAGE_FALLOFF：只开 1 枪；预算是为"上一阶段的射击冷却"留的保险
            // （射速节流 0.25 s = 15 步，取 30 步 = 2 倍）。
            30,
            /*
             * BREAK_PARTICLES：两次挖掘（第一次用非枪物品挖出泥土，第二次手持该泥土方块再挖一格）。
             *   · 每次草的硬度 0.6 s = 36 步，浮点上界 37 步 → 两次 74 步。
             *   · 两次挖掘之间要切槽 + 改俯仰角，约 4 步。
             *   合计 78 步；取 150 步（约 1.9 倍），余量给"破坏后射线目标重新指向"的稳定过程。
             */
            150,
            /*
             * DEATH_AND_RESPAWN：虚空坠落 + 死亡倒计时（与 M1 的 FALL_INTO_VOID 同口径，直接复用其推导）。
             *   · 自由落体：起始 y = 72，致死线 y < −8，落差 80 格；g = 32 格/秒²，终端速度 60 格/秒。
             *     加速段 t1 = 60/32 = 1.875 s，位移 56.25 格；余 23.75 格 → t2 = 0.396 s；
             *     合计 ≈ 2.271 s ≈ 137 步。
             *   · 死亡倒计时 3.0 s = 180 步。
             *   合计 ≈ 317 步；取 420 步 = 7 s（余 100 步），远小于"卡住不动"的判定尺度。
             */
            420,
            // SAVE_RELOAD_ROUNDTRIP：F6 补给 1 步 + 同步存档（写盘耗时不计入步数）+ 断言若干步。
            20,
            // DONE：收尾。
            1
    };

    // ---- 与世界一致性有关的常量（避免为读一个常量而暴露整个类）----

    /** 世界出生点（与 {@link TestWorldGenerator} 一致）。 */
    private static final double SPAWN_X = TestWorldGenerator.spawnX();
    private static final double SPAWN_Y = TestWorldGenerator.spawnY();
    private static final double SPAWN_Z = TestWorldGenerator.spawnZ();

    /** 换弹打断阶段：移动多少步（预算算式见 STAGE_BUDGET）。 */
    private static final int INTERRUPT_MOVE_STEPS = 20;

    /** 石墙（跨 x ∈ [−2,2]、高 2 格）所在的 z 层。 */
    private static final int WALL_Z = -3;
    private static final int WALL_MIN_X = -2;
    private static final int WALL_MAX_X = 2;

    /**
     * 石墙两格所在的方块 y。
     *
     * <p>地面方块在 {@code y = WORLD_SURFACE_BLOCK_Y = 63}，玩家脚位在其上（{@code FEET_Y = 64.0}），
     * 视线原点（相机眼位）≈ 64.0 + 1.62 = 65.62。因此要挡住水平射线，必须占据
     * 方块 y = 64（覆盖 64.0–65.0）与 y = 65（覆盖 65.0–66.0）两层。
     */
    private static final int WALL_BASE_Y = Coords.WORLD_SURFACE_BLOCK_Y + 1;   // 64
    private static final int WALL_TOP_Y = WALL_BASE_Y + 1;                    // 65

    /** 距离衰减阶段的几何：玩家 (−20.5, 64, −20.5)、怪物 (−20.5, 64, 24.5)，间距 45 格。 */
    private static final double FALLOFF_PLAYER_X = -20.5;
    private static final double FALLOFF_PLAYER_Z = -20.5;
    private static final double FALLOFF_MONSTER_X = -20.5;
    private static final double FALLOFF_MONSTER_Z = 24.5;

    private final Host host;
    private final List<String> results = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    /**
     * 本次运行是否跳过了存档相关断言（即 {@code skyisland.noSave=true} 的对照运行）。
     *
     * <p>它决定 {@link #scope()}，进而决定这次"通过"能不能被当作 M2 战斗闭环的证据
     * ——{@code m2_combat_closure = 通过 且 全量作用域}。
     * 一个静默跳过的断言集与一个真正跑通的断言集在观测上不能长得一样
     * （同 {@code TECH_DESIGN_v0.1.1 §A′.3 E-7} 的立场）。
     */
    private boolean saveAssertionsSkipped;

    private int stageIndex;
    private int stageStep;
    /** 本步（0 起）在阶段内的序号；事件回调要靠它把"发生在第几步"记下来。 */
    private int currentStep;
    /**
     * 本逻辑步开始时所属的阶段。
     *
     * <p>{@code nextIntent()} 会在本阶段的最后一步内推进 {@code stageIndex}，
     * 而 {@code observeAfterStep()} 在那之后才被调用 —— 用这个字段取代"事后读 stageIndex"，
     * 避免最后一步的观测被派发给下一阶段的 observer。
     */
    private Stage stageAtStepStart = Stage.SETTLE;
    private boolean finished;

    // ============================================================ 事件记录（Listener）

    private int eventBlockHits;
    private int eventEntityHits;
    private int eventDryFires;
    private int eventReloadCompletions;
    private int eventReloadCancellations;

    /** 最近一次 {@code onBlockHit} 的方块 runtimeId（用于断言"打在石头上"）。 */
    private int lastBlockHitRuntimeId = -1;
    /** 最近一次 {@code onEntityHit} 的实测数据（按发生顺序）。 */
    private final List<Integer> entityHitDamages = new ArrayList<>();
    private final List<Double> entityHitDistances = new ArrayList<>();
    private final List<Integer> entityHitHealths = new ArrayList<>();

    /** 换弹完成那一刻的步序号（事件发生在 stageStep 已自增之后，因此要减 1 才是"第几步"）。 */
    private int reloadCompletedAtStep = -1;
    /** 按下换弹键的那一步；用于断言"按下之后立刻进入换弹态"。 */
    private int reloadRequestedAtStep = -1;
    /** 按下换弹键之后，同一步观测到的 {@code isReloading()} 是否为真。 */
    private boolean reloadingObservedAfterRequest;

    @Override
    public void onShotFired(double muzzleX, double muzzleY, double muzzleZ,
                            double endX, double endY, double endZ, boolean hitAnything) {
        // 本自测不直接断言曳光端点（那属于渲染层），但事件必须被记下来，
        // 否则"开了几枪"就只能靠 shotsFired 这一个数字，无法区分"击发"与"渲染"两步是否都跑到。
    }

    @Override
    public void onBlockHit(double x, double y, double z,
                           double nx, double ny, double nz, int blockRuntimeId) {
        eventBlockHits++;
        lastBlockHitRuntimeId = blockRuntimeId;
    }

    @Override
    public void onEntityHit(Entity entity, int damage, double distance) {
        eventEntityHits++;
        entityHitDamages.add(damage);
        entityHitDistances.add(distance);
        entityHitHealths.add(entity.health());
        if (isCurrentStage(Stage.SHOOT_KILL)) {
            // PRD 5.5.1 / 5.4.1：近战怪 20 血、手枪单发 8 点 → 3 发致死。
            // 每一发都单独断言一次血量，这样"打中但不掉血"或"掉血量错"会立刻定位到第几发。
            //
            // ★ 期望值必须钳到 0：`Entity.hurt()` 在 `health - amount <= 0` 时把生命写成 0
            //   并置 alive=false，因此第 3 发的实测值是 0 而不是 20 − 24 = −4。
            //   若直接写 `20 − 8·index`，第 3 发会稳定假红（实测值见输出）。
            //   "总伤害确实 ≥ 20"这件事由 checkShootKill() 用伤害累加单独断言，不靠这条兜。
            int index = entityHitHealths.size();
            int expected = Math.max(0, MeleeMonster.MAX_HEALTH - 8 * index);
            record("第 " + index + " 发命中后怪物生命 = " + expected,
                    entity.health() == expected,
                    String.format("实测生命=%d 伤害=%d 距离=%.3f 格（期望 %d = max(0, 20 − 8×%d)）",
                            entity.health(), damage, distance, expected, index));
            record("第 " + index + " 发造成 8 点伤害（有效射程内 100%）", damage == 8,
                    "伤害=" + damage);
        }
    }

    @Override
    public void onDryFire() {
        eventDryFires++;
    }

    @Override
    public void onReloadRequest(GunState.ReloadOutcome outcome) {
        // 结果（STARTED / ALREADY_FULL / NO_RESERVE / ALREADY_RELOADING）由 GunState 自己的单测钉死，
        // 这里只关心"请求被受理"这一事实，具体分支不影响本自测的断言。
    }

    @Override
    public void onReloadCompleted(int magazineAmmo, int magazineSize) {
        eventReloadCompletions++;
        reloadCompletedAtStep = currentStep;
    }

    @Override
    public void onReloadCancelled() {
        eventReloadCancellations++;
    }

    @Override
    public void onMessage(String textKey, Object... args) {
        // 提示文案由 Localization 负责，自测不重复断言 UI 文案（那不是 PRD 12.3 #1 的内容）。
    }

    /** 当前阶段是否为 {@code stage}（越界与结束态一律返回 false）。 */
    private boolean isCurrentStage(Stage stage) {
        return !finished && stageIndex < Stage.values().length && Stage.values()[stageIndex] == stage;
    }

    /**
     * 把两个监听器串成一条。<b>顺序是先产品反馈、后自测记录</b>，
     * 因为产品的反馈里没有任何会改变状态的动作（它只碰表现层），
     * 而自测记录里可能写断言 —— 先做完产品该做的事，记录才描述的是同一时刻的状态。
     *
     * <p>做成静态工厂而不是让 {@code SkyIslandGame} 自己写一个 8 方法的转发类：
     * 监听器接口每加一个事件，转发类就要跟着改，漏掉一个方法会静默丢掉那类事件，
     * 而"自测没看见事件"与"事件没发生"在日志上是一样的。
     */
    public static CombatController.Listener tee(CombatController.Listener first,
                                               CombatController.Listener second) {
        return new CombatController.Listener() {
            @Override
            public void onShotFired(double mx, double my, double mz,
                                    double ex, double ey, double ez, boolean hitAnything) {
                first.onShotFired(mx, my, mz, ex, ey, ez, hitAnything);
                second.onShotFired(mx, my, mz, ex, ey, ez, hitAnything);
            }

            @Override
            public void onBlockHit(double x, double y, double z,
                                   double nx, double ny, double nz, int blockRuntimeId) {
                first.onBlockHit(x, y, z, nx, ny, nz, blockRuntimeId);
                second.onBlockHit(x, y, z, nx, ny, nz, blockRuntimeId);
            }

            @Override
            public void onEntityHit(Entity entity, int damage, double distance) {
                first.onEntityHit(entity, damage, distance);
                second.onEntityHit(entity, damage, distance);
            }

            @Override
            public void onDryFire() {
                first.onDryFire();
                second.onDryFire();
            }

            @Override
            public void onReloadRequest(GunState.ReloadOutcome outcome) {
                first.onReloadRequest(outcome);
                second.onReloadRequest(outcome);
            }

            @Override
            public void onReloadCompleted(int magazineAmmo, int magazineSize) {
                first.onReloadCompleted(magazineAmmo, magazineSize);
                second.onReloadCompleted(magazineAmmo, magazineSize);
            }

            @Override
            public void onReloadCancelled() {
                first.onReloadCancelled();
                second.onReloadCancelled();
            }

            @Override
            public void onMessage(String textKey, Object... args) {
                first.onMessage(textKey, args);
                second.onMessage(textKey, args);
            }
        };
    }

    /** 事件接收口（交给 {@code SkyIslandGame} 串进 combat.step）。 */
    public CombatController.Listener listener() {
        return this;
    }

    // ============================================================ 阶段内观测量

    private int stageStartShots;
    private int stageStartDryFires;
    private int stageStartBlockHits;
    private int stageStartEntityHits;
    private long stageStartBreaks;

    /**
     * 本轮自测<b>开始之前</b>的区块网格重建次数（即启动预热已经消耗掉的那一批）。
     *
     * <p>用来做"游玩期间到底有没有重建过网格"的全轮断言 —— 见 {@code Stage.DONE}。
     */
    private long meshBuildsAtStart = -1;
    private int stageStartDeaths;
    private int stageStartEventBlockHits;
    private int stageStartEventEntityHits;
    private int stageStartEventDryFires;
    private int stageStartEventReloadCompletions;
    private int stageStartEventReloadCancellations;
    private long stageStartBreakParticles;
    private int stageStartEntitySize;
    private int stageStartEntityAlive;
    private int stageStartEntitySpawned;
    private int stageStartEntityRemoved;

    // ---- 跨阶段的记录 ----

    private MeleeMonster monster;
    private double spawnDistance;
    private double approachDistance;
    /** 刷怪当步（{@code currentStep == 1}）的落点；阶段末尾它的位置已被追击改变。 */
    private double monsterSpawnX;
    private double monsterSpawnY;
    private double monsterSpawnZ;

    private int interruptPhase;
    private int interruptMoveSteps;
    private int magAfterEmptying = -1;
    private int reserveAfterEmptying = -1;
    private int magAfterInterrupt = -1;
    private int reserveAfterInterrupt = -1;

    private double aimStartZ;
    private double aimMoveDistance;
    private double noAimStartZ;
    private double noAimMoveDistance;
    private boolean aimScreenshotTaken;
    /** 在"仍按着右键"的那一步（第 60 步）采样，阶段末再断言 —— 见 checkAim。 */
    private boolean aimObservedAiming;
    private double aimObservedFovScale = -1;
    private double aimObservedCameraFov = -1;

    private boolean approachScreenshotTaken;

    /**
     * M2.1 像素证据：请求 {@code monster-in-view} 截图时的墙钟时刻（毫秒）。
     *
     * <p>截图目录是<b>共享</b>的（多次运行会累积同名文件），因此收尾做像素校验时
     * 不能只挑"最新的 monster-in-view PNG"——必须挑<b>本次运行之后新产生的</b>那一张。
     * 用请求时刻做下界即可唯一定位。
     */
    private long approachScreenshotRequestedAtMs = -1;

    private long breakParticlesAfterFirstBreak = -1;
    private boolean breakScreenshotTaken;
    private int breakTargetAX = Integer.MIN_VALUE;
    private int breakTargetAY;
    private int breakTargetAZ;
    private int breakTargetBX = Integer.MIN_VALUE;
    private int breakTargetBY;
    private int breakTargetBZ;

    private int wallPlaced;
    private int wallBroken;

    private double falloffDistance = -1;
    private int falloffDamage = -1;

    /**
     * M2.1：开火后坐力的<b>观测峰值</b>（度）。
     *
     * <p>之所以要专门记一个峰值：后坐力是瞬态的。收尾时读相机只能读到 0
     * （那正是它应该的样子），而"最终回到 0"证明不了"开火时抬过枪" ——
     * 这两件事各自可能单独成立：只接了 {@code decayRecoil} 而没接
     * {@code addRecoilPitch} 时，读数会一直是 0，看上去和"回落正常"一模一样。
     */
    private double peakRecoilDeg;

    private int deathAtStep = -1;
    private int respawnAtStep = -1;
    private int healthAtDeath = -1;
    private double respawnX;
    private double respawnY;
    private double respawnZ;

    private final List<ItemStack> savedHotbar = new ArrayList<>();
    private int savedPistolCount = -1;
    private int savedAmmoCount = -1;
    private boolean saveVerifiedInLoop;

    public M2CombatSelfTest(Host host) {
        this.host = host;
        Log.info("[自测] M2 战斗自测已装载：%d 个阶段（进程内意图注入，不依赖 OS 输入）",
                Stage.values().length);
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean allPassed() {
        return failures.isEmpty() && finished;
    }

    /** {@code true} 表示本次运行覆盖了全部断言（含存档），可作为 M2 战斗闭环的证据。 */
    public boolean isFullScope() {
        return !saveAssertionsSkipped;
    }

    /** 本次运行的断言范围，用于摘要与门禁判定。 */
    public String scope() {
        return saveAssertionsSkipped ? "no_save_control" : "full";
    }

    public List<String> failures() {
        return List.copyOf(failures);
    }

    public List<String> results() {
        return List.copyOf(results);
    }

    // ============================================================ 主入口（每个逻辑步调用一次）

    public PlayerIntent nextIntent(double dt) {
        if (finished) {
            return PlayerIntent.NONE;
        }
        if (stageStep == 0) {
            onStageBegin();
        }
        currentStep = stageStep;
        // 记下"这一步属于哪个阶段"：本步是本阶段的最后一步时，下面的 onStageEnd() 会立刻
        // 把 stageIndex 推到下一阶段，而 observeAfterStep() 是在 nextIntent() 返回<b>之后</b>
        // 才被 stepLogic 调用的。若那时再读 stageIndex，本步的观测就会被派发给下一阶段的
        // observer（越界一帧）。用一个显式字段把归属钉死，比在每个 observer 里加
        // "currentStep 是否落在本阶段预算内"的隐式守卫更难写错。
        stageAtStepStart = Stage.values()[stageIndex];
        PlayerIntent intent = intentFor(stageAtStepStart);
        stageStep++;
        if (stageStep >= STAGE_BUDGET[stageIndex]) {
            onStageEnd();
        }
        return intent;
    }

    /** 阶段内的逐步观测：只记录，不断言（断言集中在 {@code checkXxx} 与事件回调里）。 */
    public void observeAfterStep(Player player) {
        if (finished) {
            return;
        }
        // M2.1：后坐力峰值必须在这里逐<b>逻辑步</b>采集，不能在收尾时读一次。
        // 后坐力是瞬态：单发 0.9°、5 度/秒回落 → 0.18 秒（≈ 11 步）后精确归零，
        // 而 SHOOT_KILL 阶段有几十步。收尾读到的必然是 0，无论中间有没有抬过枪。
        peakRecoilDeg = Math.max(peakRecoilDeg, player.camera().recoilPitchDeg());
        switch (stageAtStepStart) {
            case AIM -> observeAim(player);
            case SPAWN_AND_APPROACH -> observeApproach(player);
            case SHOOT_KILL -> {
                if (currentStep == 0 && monster != null) {
                    approachDistance = horizontalDistanceTo(player, monster);
                }
            }
            case BREAK_PARTICLES -> observeBreak(player);
            case DEATH_AND_RESPAWN -> observeDeath(player);
            case RELOAD_FULL, RELOAD_INTERRUPTED -> observeReloadStarted(player);
            default -> {
            }
        }
    }

    /**
     * 观测"按下 R 的那一步结束时，枪是否已经进入换弹态"。
     *
     * <p><b>为什么必须在这一步结束时采样，而不是在 {@code checkXxx} 里事后推断：</b>
     * 换弹是一个<b>状态</b>，checkXxx 在阶段末尾执行，那时若换弹已经完成
     * （{@code isReloading()} 又变回 false）也完全正常。只有"按下 R 的同一个逻辑步内
     * 就已经是换弹态"才是"R 键立刻生效"这条规格的可观测证据。
     *
     * <p><b>判据是 {@code currentStep == reloadRequestedAtStep}（同一个步号），不是 +1：</b>
     * {@code currentStep} 由 {@code nextIntent()} 在步首赋值为 {@code stageStep}，
     * 而该步执行期间的 {@code stageStep++} 不会回写 {@code currentStep} ——
     * 也就是说 {@code observeAfterStep()} 看到的 {@code currentStep} 仍然是"这一步"的步号。
     * 首轮实现按 +1 写，结果 RELOAD_FULL 侥幸命中（观察落在下一步、那时换弹还没结束），
     * 而 RELOAD_INTERRUPTED 稳定失败（下一步已经在按 W，换弹恰好被那一步取消）。
     */
    private void observeReloadStarted(Player player) {
        if (reloadRequestedAtStep >= 0 && currentStep == reloadRequestedAtStep) {
            GunState gun = host.combat().existingGun(player);
            reloadingObservedAfterRequest = gun != null && gun.isReloading();
        }
    }

    private void onStageBegin() {
        Player player = host.player();
        stageStartShots = host.combat().shotsFired();
        stageStartDryFires = host.combat().dryFires();
        stageStartBlockHits = host.combat().blockHits();
        stageStartEntityHits = host.combat().entityHits();
        stageStartBreaks = player.blocksBroken();
        stageStartDeaths = player.deaths();
        stageStartEventBlockHits = eventBlockHits;
        stageStartEventEntityHits = eventEntityHits;
        stageStartEventDryFires = eventDryFires;
        stageStartEventReloadCompletions = eventReloadCompletions;
        stageStartEventReloadCancellations = eventReloadCancellations;
        stageStartBreakParticles = host.combatFx().totalBreakParticles();
        stageStartEntitySize = host.entities().size();
        stageStartEntityAlive = host.entities().aliveCount();
        stageStartEntitySpawned = host.entities().totalSpawned();
        stageStartEntityRemoved = host.entities().totalRemoved();
        if (meshBuildsAtStart < 0) {
            // 第 0 阶段开始前的值 = 启动预热的成果。之后只要有人动过方块，
            // 渲染钩子就应该把重建队列消费掉、让这个计数继续涨。
            meshBuildsAtStart = host.world().meshBuildCount();
        }

        // 每个阶段开始时重置"本阶段的按键时点"记录，避免跨阶段串味。
        // reloadingObservedAfterRequest 必须一起清：它被 RELOAD_FULL 与 RELOAD_INTERRUPTED
        // 共用，不清的话第二个阶段会读到第一个阶段留下的 true 而永远"通过"。
        reloadRequestedAtStep = -1;
        reloadingObservedAfterRequest = false;

        Stage stage = Stage.values()[stageIndex];
        Log.info("[自测] ▶ 阶段 %d/%d %s（预算 %d 步）",
                stageIndex + 1, Stage.values().length, stage.label, STAGE_BUDGET[stageIndex]);
    }

    /** 快捷栏里第一个"不是枪"的槽位（挖掘阶段必须先声明自己手里是什么）。 */
    private int firstNonGunSlot(Player player) {
        for (int i = 0; i < player.inventory().size(); i++) {
            if (!player.inventory().slot(i).item().isGun()) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 快捷栏里第一个方块物品的槽位；没有则返回 −1。
     *
     * <p>写成"扫描内容"而不是硬编码槽号：开局装备的格子布局与后续挖到的物品都会变，
     * 硬编码的失效方式是"挖掘阶段失败"，而真正的原因在别处。
     */
    private int firstBlockSlot(Player player) {
        for (int i = 0; i < player.inventory().size(); i++) {
            if (player.inventory().slot(i).isBlockItem()) {
                return i;
            }
        }
        return -1;
    }

    private static double horizontalDistanceTo(Player player, Entity entity) {
        double dx = entity.position().x - player.position().x;
        double dz = entity.position().z - player.position().z;
        return Math.hypot(dx, dz);
    }

    // ============================================================ 意图

    private PlayerIntent intentFor(Stage stage) {
        Player player = host.player();
        return switch (stage) {
            case SETTLE, GEAR_CHECK, DONE -> PlayerIntent.NONE;

            case MINE_BLOCKED_WHILE_HOLDING_GUN -> {
                if (currentStep == 0) {
                    // 初始条件（不跳过被测逻辑）：回到出生点正上方、视线垂直向下。
                    // 为什么需要：把位置钉死在出生点是"脚下那一格是确定的"的前提，
                    // 否则"方块没被破坏"的断言没有可引用的坐标。
                    player.teleport(SPAWN_X, SPAWN_Y, SPAWN_Z);
                    player.camera().setAngles(0, -89.5);
                    yield PlayerIntent.NONE;   // 本步只摆姿势：下一步才开始按左键
                }
                // ★ 关键：此时手持的是手枪（第 1 格）。左键在这里应当是"开火"而不是"挖掘"。
                yield PlayerIntent.combat(0f, 0f, false, 0, 0, true, false, false);
            }

            case DRY_FIRE -> PlayerIntent.combat(0f, 0f, false, 0, 0, true, false, false);

            case RELOAD_FULL -> {
                if (currentStep == 0) {
                    // 视线回到水平：本阶段的射击（若有）不应当打在脚下的方块上。
                    player.camera().setAngles(0, 0);
                    reloadRequestedAtStep = currentStep;
                    yield PlayerIntent.combat(0f, 0f, false, 0, 0, false, false, true);
                }
                yield PlayerIntent.NONE;
            }

            case RELOAD_INTERRUPTED -> {
                GunState gun = host.combat().existingGun(player);
                if (interruptPhase == 0) {
                    // ① 按住左键打空 12 发（射速由 GunState 节流）
                    if (gun != null && gun.magazineAmmo() > 0) {
                        yield PlayerIntent.combat(0f, 0f, false, 0, 0, true, false, false);
                    }
                    interruptPhase = 1;
                    magAfterEmptying = gun == null ? -1 : gun.magazineAmmo();
                    reserveAfterEmptying = player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID);
                    yield PlayerIntent.NONE;
                }
                if (interruptPhase == 1) {
                    // ② 按 R 开始换弹
                    interruptPhase = 2;
                    reloadRequestedAtStep = currentStep;
                    yield PlayerIntent.combat(0f, 0f, false, 0, 0, false, false, true);
                }
                if (interruptPhase == 2) {
                    // ③ 按住 W：移动即取消换弹（PRD 5.4.3「换弹打断」）
                    if (interruptMoveSteps < INTERRUPT_MOVE_STEPS) {
                        interruptMoveSteps++;
                        yield PlayerIntent.combat(1f, 0f, false, 0, 0, false, false, false);
                    }
                    interruptPhase = 3;
                    yield PlayerIntent.NONE;
                }
                if (interruptPhase == 3) {
                    // ④ 采样"被打断之后"的弹药读数，然后立刻再按一次 R
                    magAfterInterrupt = gun == null ? -1 : gun.magazineAmmo();
                    reserveAfterInterrupt = player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID);
                    interruptPhase = 4;
                    yield PlayerIntent.combat(0f, 0f, false, 0, 0, false, false, true);
                }
                yield PlayerIntent.NONE;
            }

            case AIM -> intentForAim(player);

            case SPAWN_AND_APPROACH -> {
                if (currentStep == 0) {
                    // 初始条件：回到出生点、视线水平朝 −Z。
                    // 为什么：刷怪落点是"准星前方 5 格"，而准星方向由相机决定；
                    // 不平掉上一阶段遗留的朝向，落点就不可预测。
                    player.teleport(SPAWN_X, SPAWN_Y, SPAWN_Z);
                    player.camera().setAngles(0, 0);
                    yield PlayerIntent.NONE;
                }
                if (currentStep == 1) {
                    // ★ 复用产品的 F4 刷怪路径，而不是另造一套刷怪代码
                    monster = host.spawnMonsterInFront();
                    yield PlayerIntent.NONE;
                }
                yield PlayerIntent.NONE;
            }

            case SHOOT_KILL -> {
                if (eventEntityHits - stageStartEventEntityHits >= 3) {
                    // 3 发已致死：立刻松手，保证 shotsFired 的增量恰好是 3
                    yield PlayerIntent.NONE;
                }
                yield PlayerIntent.combat(0f, 0f, false, 0, 0, true, false, false);
            }

            case WALL_BLOCKS_BULLET -> intentForWall(player);

            case DAMAGE_FALLOFF -> intentForFalloff(player);

            case BREAK_PARTICLES -> intentForBreak(player);

            case DEATH_AND_RESPAWN -> {
                if (currentStep == 0) {
                    // 初始条件：站到虚空坑（x,z ∈ [3,6]）正上方 72 格高，然后什么都不做。
                    // 走过去的路径依赖地形细节，而这里要验证的是"重力 + 虚空判定 + 死亡倒计时 + 重生"。
                    player.teleport(4.5, 72.0, 4.5);
                    player.camera().setAngles(0, 0);
                }
                yield PlayerIntent.NONE;
            }

            case SAVE_RELOAD_ROUNDTRIP -> {
                if (currentStep == 0) {
                    /*
                     * 取一次调试补给（产品自带的 F6 路径）。
                     *
                     * 为什么需要它：PRD 5.4.1 只给"手枪 ×1 + 手枪弹 ×24（2 个满弹匣）"，
                     * 而本脚本为了举证换弹规则必须把这 24 发全部打进弹匣 —— 到存档阶段时
                     * 后备弹药恰好是 0，"弹药仍在"这条断言会退化成 "0 == 0"，验证力接近零。
                     * 补一次后，这条断言才真正检验"弹药 item id + 数量跨存档往返不变"。
                     *
                     * 副作用如实登记：F6 会把第二把手枪放进下一个空槽（枪的堆叠上限是 1），
                     * 因此存档里的快捷栏会出现 2 把枪 —— 这是 F6 的既有行为，不是本自测的产物。
                     */
                    host.grantDebugSupply();
                }
                yield PlayerIntent.NONE;
            }
        };
    }

    private PlayerIntent intentForAim(Player player) {
        if (currentStep == 0) {
            // 初始条件 A：回到出生点、视线水平。窗口 A 从"静止"起步（teleport 清零速度）。
            player.teleport(SPAWN_X, SPAWN_Y, SPAWN_Z);
            player.camera().setAngles(0, 0);
            aimStartZ = player.position().z;
            return PlayerIntent.NONE;
        }
        if (currentStep <= 60) {
            if (!aimScreenshotTaken && currentStep == 5) {
                aimScreenshotTaken = true;
                host.requestScreenshot("aiming");
            }
            // 按住右键（useHeld）+ 前进：瞄准状态与 60% 移动速度在这一段里同时生效
            return PlayerIntent.combat(1f, 0f, false, 0, 0, false, true, false);
        }
        if (currentStep == 61) {
            return PlayerIntent.NONE;   // 松开右键，观察还原
        }
        if (currentStep == 62) {
            // 初始条件 B：同样的起点与朝向，窗口 B 也从静止起步。
            // 两段位移因此可逐位比较（线性系统 + 相同初始条件 → 位移比 = 目标速度比）。
            player.teleport(SPAWN_X, SPAWN_Y, SPAWN_Z);
            player.camera().setAngles(0, 0);
            noAimStartZ = player.position().z;
            return PlayerIntent.NONE;
        }
        if (currentStep <= 122) {
            return PlayerIntent.combat(1f, 0f, false, 0, 0, false, false, false);
        }
        return PlayerIntent.NONE;
    }

    private void observeAim(Player player) {
        if (currentStep == 60) {
            aimMoveDistance = Math.abs(player.position().z - aimStartZ);
            // 瞄准中的三条断言必须在这一步采样：第 61 步松开右键后 aiming 立刻为 false。
            aimObservedAiming = player.isAiming();
            aimObservedFovScale = player.fovScale();
            aimObservedCameraFov = player.camera().fovDeg();
        }
        if (currentStep == 122) {
            noAimMoveDistance = Math.abs(player.position().z - noAimStartZ);
        }
    }

    private void observeApproach(Player player) {
        if (currentStep == 1 && monster != null) {
            // 刷怪发生在 intentFor(SPAWN_AND_APPROACH) 的 currentStep == 1 那一步，
            // 而本方法在该步<b>结束时</b>执行，因此这里读到的是"刚落地的初始位置"。
            // 为什么不放到 checkSpawnAndApproach() 里读：那只怪整段预算都在朝玩家走，
            // 阶段末尾的位置已经不是落点了（实测 45 步里它前进了约 1.47 格）。
            spawnDistance = horizontalDistanceTo(player, monster);
            monsterSpawnX = monster.position().x;
            monsterSpawnY = monster.position().y;
            monsterSpawnZ = monster.position().z;
        }
        // 注意：本阶段末尾的水平距离**不在这里**采样。
        // 原因见 checkSpawnAndApproach() —— onStageEnd() 是在 nextIntent() 内部、
        // 最后一步执行<b>之前</b>被调用的，因此"最后一步的观测"永远晚于该阶段的断言。
        if (!approachScreenshotTaken && currentStep == 30) {
            // 怪物此时约在 3.0 格外（4.0 − 30 tick × 0.0333）、正对镜头，
            // HUD 上同时挂着生命条与弹药读数 ——
            // 这正是"画面里同时有怪物、生命条、弹药"这一条截图要求所指的时刻。
            approachScreenshotTaken = true;
            // 记下请求时刻：收尾的像素校验据此在共享截图目录里唯一定位本轮的 PNG。
            approachScreenshotRequestedAtMs = System.currentTimeMillis();
            host.requestScreenshot("monster-in-view");
        }
    }

    private PlayerIntent intentForWall(Player player) {
        if (currentStep == 0) {
            player.teleport(SPAWN_X, SPAWN_Y, SPAWN_Z);
            player.camera().setAngles(0, 0);
            return PlayerIntent.NONE;
        }
        if (currentStep == 1) {
            monster = host.spawnMonsterInFront();
            return PlayerIntent.NONE;
        }
        if (currentStep == 2) {
            // 建墙：必须"先下层后上层"——placeBlock 要求六邻中至少一个是实体方块，
            // 而 y=65 那一格的唯一支撑就是刚放下的 y=64。顺序写反会全部被拒。
            World world = host.world();
            wallPlaced = 0;
            for (int x = WALL_MIN_X; x <= WALL_MAX_X; x++) {
                if (world.placeBlock(x, WALL_BASE_Y, WALL_Z,
                        BlockRegistry.stone().runtimeId(),
                        World.MutationCause.SELF_TEST, null).success()) {
                    wallPlaced++;
                }
                if (world.placeBlock(x, WALL_TOP_Y, WALL_Z,
                        BlockRegistry.stone().runtimeId(),
                        World.MutationCause.SELF_TEST, null).success()) {
                    wallPlaced++;
                }
            }
            return PlayerIntent.NONE;
        }
        if (eventBlockHits - stageStartEventBlockHits == 0) {
            // 只开一枪：多开会让"怪物血量不变"这条断言无法区分"被墙挡住"与"压根没打"
            return PlayerIntent.combat(0f, 0f, false, 0, 0, true, false, false);
        }
        return PlayerIntent.NONE;
    }

    private PlayerIntent intentForFalloff(Player player) {
        if (currentStep == 0) {
            /*
             * 清场 + 摆位。
             *
             * 清场的原因：本阶段要断言"这一枪的伤害恰好来自 45 格外的这只怪"，
             * 上一阶段留下的怪（在原点附近）会让"命中了谁"变得不唯一。
             * 这是测试夹具的清理，不是跳过产品逻辑 —— 刷怪本身仍走 EntityManager 的公开入口。
             *
             * 摆位的原因：45 格的间距在 64×64 的测试世界里只有"贴对角"才放得下，
             * 且这条线必须全程没有方块（视线在 y = 65.62，高于地表顶面 y = 64）。
             */
            host.entities().clear();
            player.teleport(FALLOFF_PLAYER_X, SPAWN_Y, FALLOFF_PLAYER_Z);
            player.camera().setAngles(180, 0);   // yaw = 180° → 前向为 +Z
            return PlayerIntent.NONE;
        }
        if (currentStep == 1) {
            monster = host.entities().spawnMeleeMonster(
                    FALLOFF_MONSTER_X, SPAWN_Y, FALLOFF_MONSTER_Z);
            return PlayerIntent.NONE;
        }
        if (eventEntityHits - stageStartEventEntityHits == 0) {
            return PlayerIntent.combat(0f, 0f, false, 0, 0, true, false, false);
        }
        return PlayerIntent.NONE;
    }

    private enum BreakPhase {
        /** 用"不是枪"的物品挖第一格，产出方块物品。 */
        MINE_FOR_BLOCK_ITEM,
        /** 手持刚挖到的方块物品再挖一格 —— 这一格的粒子增量就是要断言的对象。 */
        MINE_HOLDING_BLOCK_ITEM,
        FINISHED
    }

    private BreakPhase breakPhase = BreakPhase.MINE_FOR_BLOCK_ITEM;

    private PlayerIntent intentForBreak(Player player) {
        if (currentStep == 0) {
            /*
             * 初始条件：回到出生点、俯角 −45°、切到第一个"不是枪"的槽位。
             *
             * 为什么是 −45° 而不是垂直向下：垂直向下挖掉的是玩家自己脚下那一格，
             * 挖完之后玩家会立刻掉进坑里，后续断言就建立在一个正在下坠的玩家上。
             * −45° 命中的是前方的地表（射线长度 1.62/sin45° ≈ 2.29 格，在 reach 5 之内），
             * 挖掉它不会移除玩家的支撑。
             *
             * 为什么必须切槽位：M2 开局装备只给"枪 + 弹药"，手里没有方块物品；
             * 而持枪时左键是开火（阶段 3 已经单独举证过），所以挖掘阶段必须先声明自己手里是什么。
             * 第一格挖出来的草方块掉泥土（PRD 5.1 掉落表），第二格就可以真正手持方块物品来挖。
             */
            player.teleport(SPAWN_X, SPAWN_Y, SPAWN_Z);
            player.camera().setAngles(0, -45);
            return PlayerIntent.selectSlot(firstNonGunSlot(player));
        }
        if (breakPhase == BreakPhase.MINE_FOR_BLOCK_ITEM) {
            int slot = firstBlockSlot(player);
            if (slot >= 0) {
                // 第一格已经挖掉、方块物品已在背包里 → 切到它，进入第二阶段
                breakPhase = BreakPhase.MINE_HOLDING_BLOCK_ITEM;
                breakParticlesAfterFirstBreak = host.combatFx().totalBreakParticles();
                player.camera().setAngles(0, -60);   // 换一列，命中另一格草方块
                return PlayerIntent.selectSlot(slot);
            }
            if (breakTargetAX == Integer.MIN_VALUE) {
                var hit = player.currentTarget();
                if (hit != null && hit.hasFace()) {
                    breakTargetAX = hit.blockX();
                    breakTargetAY = hit.blockY();
                    breakTargetAZ = hit.blockZ();
                }
            }
            return PlayerIntent.combat(0f, 0f, false, 0, 0, true, false, false);
        }
        if (breakPhase == BreakPhase.MINE_HOLDING_BLOCK_ITEM) {
            if (player.blocksBroken() - stageStartBreaks >= 2) {
                breakPhase = BreakPhase.FINISHED;
                return PlayerIntent.NONE;
            }
            if (breakTargetBX == Integer.MIN_VALUE) {
                var hit = player.currentTarget();
                if (hit != null && hit.hasFace()) {
                    breakTargetBX = hit.blockX();
                    breakTargetBY = hit.blockY();
                    breakTargetBZ = hit.blockZ();
                }
            }
            return PlayerIntent.combat(0f, 0f, false, 0, 0, true, false, false);
        }
        return PlayerIntent.NONE;
    }

    private void observeBreak(Player player) {
        // 第 1 格挖掉的当步：立刻拍一张"破坏粒子刚产生"的截图。
        // 粒子的寿命是 0.4–0.8 秒（24–48 步），阶段末再拍已经全没了 ——
        // 这与 M1 的"挖掘进度条只在过程中可见"是同一类观测点选择问题。
        if (!breakScreenshotTaken && player.blocksBroken() - stageStartBreaks >= 1) {
            breakScreenshotTaken = true;
            host.requestScreenshot("break-particles");
        }
    }

    private void observeDeath(Player player) {
        if (deathAtStep < 0 && player.isDead()) {
            deathAtStep = currentStep;
            healthAtDeath = player.health();
        } else if (deathAtStep >= 0 && respawnAtStep < 0 && !player.isDead()) {
            respawnAtStep = currentStep;
            respawnX = player.position().x;
            respawnY = player.position().y;
            respawnZ = player.position().z;
        }
    }

    // ============================================================ 阶段结束断言

    private void onStageEnd() {
        Stage stage = Stage.values()[stageIndex];
        switch (stage) {
            case SETTLE -> {
                // 前置条件排在最前：它是"后面的失败到底怪谁"的唯一线索（见方法注释）。
                checkFreshWorldPrecondition();
                checkSettle();
            }
            case GEAR_CHECK -> checkGear();
            case MINE_BLOCKED_WHILE_HOLDING_GUN -> checkMineBlocked();
            case DRY_FIRE -> checkDryFire();
            case RELOAD_FULL -> checkReloadFull();
            case RELOAD_INTERRUPTED -> checkReloadInterrupted();
            case AIM -> checkAim();
            case SPAWN_AND_APPROACH -> checkSpawnAndApproach();
            case SHOOT_KILL -> checkShootKill();
            case WALL_BLOCKS_BULLET -> checkWall();
            case DAMAGE_FALLOFF -> checkFalloff();
            case BREAK_PARTICLES -> checkBreak();
            case DEATH_AND_RESPAWN -> checkDeath();
            case SAVE_RELOAD_ROUNDTRIP -> checkSave();
            case DONE -> {
                /*
                 * M2 缺陷回归（全轮断言）：游玩期间到底有没有重建过区块网格。
                 *
                 * 此前 render() 从不消费重建队列，队列只在启动预热里被消费一次 ——
                 * 代价是"方块挖掉了但还看得见"（世界数据与碰撞已更新、画面还是旧网格）。
                 * 现场读数：某次人工试玩破坏 6 个方块，mesh_build_count 始终停在 16，
                 * 待重建队列从 0 攒到 3 再没下降。
                 *
                 * 为什么断言放在 DONE 而不是 BREAK_PARTICLES 阶段内：网格重建由
                 * 渲染钩子消费，而 checkBreak() 可能与最后一次破坏落在同一帧 ——
                 * 那样"队列还没轮到渲染"会被读成"重建没发生"，变成一条偶发红的断言。
                 * 跑到 DONE 时已经过去几十秒、上万帧，这个问题不存在。
                 */
                record("游玩期间区块网格被重建过（此前只有启动预热会重建 —— 破坏 6 个方块而计数停在 16）",
                        host.world().meshBuildCount() > meshBuildsAtStart,
                        "mesh_build_count " + meshBuildsAtStart + " → "
                                + host.world().meshBuildCount() + "（自测开始时的值即预热成果）");
                record("重建队列未被积压（每帧 render 都在消费）",
                        host.world().pendingMeshRebuilds() == 0,
                        "pendingMeshRebuilds=" + host.world().pendingMeshRebuilds());
                finished = true;
                host.requestScreenshot("final");
                Log.info("[自测] 脚本执行完毕：%d 项断言，%d 项失败", results.size(), failures.size());
            }
        }
        if (stage != Stage.DONE) {
            host.requestScreenshot(stage.name().toLowerCase(java.util.Locale.ROOT));
        }
        stageIndex++;
        stageStep = 0;
    }

    private void checkSettle() {
        Player player = host.player();
        boolean grounded = player.onGround();
        boolean atSurface = Math.abs(player.position().y - SPAWN_Y) < 0.05;
        record("静置后站在地面（物理已落定）", grounded, "onGround=" + grounded);
        record("静置后脚底仍在地表高度", atSurface,
                String.format("y=%.4f 期望 %.1f", player.position().y, SPAWN_Y));
    }

    /**
     * 前置条件：本次运行必须从<b>全新存档</b>开始。
     *
     * <p><b>为什么这是一条真断言而不是免责声明：</b>
     * 本自测会<b>改进世界并落盘</b>（挖掉草方块、放/拆石墙、F6 补给把匕首格换成手枪弹，
     * 最后走一遍存档）。因此第二轮若复用同一存档目录，开局状态就不再是"新世界"：
     * 快捷栏里会多出上一轮挖到的泥土、脚下那格已经是空气、粒子计数器带着上一轮的累计值。
     * 那种运行里 GEAR_CHECK / MINE_BLOCKED / BREAK_PARTICLES 等 20 余条断言会连锁报错，
     * 但根因只有一个，而输出里看不出是哪一个 —— "把前置条件写成第一条断言"就是解药：
     * 它会排在整个 {@code results} 列表的最前面（SETTLE 是第 0 阶段）。
     *
     * <p>判据用 {@code SaveManager.worldExists()}（看的是 {@code level.json}，
     * 而不是"目录存在"），与产品自己判断"要不要读档"的口径完全一致。
     */
    private void checkFreshWorldPrecondition() {
        SaveManager saves = host.saveManager();
        boolean preexisted = saves != null && saves.worldExists();
        record("前置条件：本次运行从全新存档开始（存档目录里没有上一轮的 level.json）",
                !preexisted,
                preexisted
                        ? "存档已存在 → " + (saves == null ? "?" : saves.worldDirectory())
                        + "。请先清空该目录再跑（推荐用 -Dskyisland.saveDir=<tmp 目录> 把自测与真实存档隔离）；"
                        + "否则装备 / 地形 / 粒子断言会因上一轮残留而连锁失败。"
                        : "存档目录为空：" + (saves == null ? "?" : saves.worldDirectory()));
    }

    private void checkGear() {
        Player player = host.player();
        ItemStack slot0 = player.inventory().slot(0);
        ItemStack slot1 = player.inventory().slot(1);
        int expectedAmmo = 2 * ItemRegistry.pistol().gun().magazineSize();

        // 按 item id 比对而不是只比数量：M1 时"物品 id 就是方块 id"，M2 起手枪与弹药
        // 都不是方块，只有 id 才能区分"手枪"与"一块颜色相近的方块"。
        record("快捷栏第 1 格是手枪（按 item id）",
                ItemRegistry.PISTOL_ID.equals(slot0.item().id()),
                "slot0=" + slot0 + " id=" + slot0.item().id());
        record("快捷栏第 1 格手枪数量 = 1", slot0.count() == 1, "count=" + slot0.count());
        record("快捷栏第 2 格是手枪弹（按 item id）",
                ItemRegistry.PISTOL_AMMO_ID.equals(slot1.item().id()),
                "slot1=" + slot1 + " id=" + slot1.item().id());
        record("快捷栏第 2 格弹药数量 = 2 × 弹匣容量", slot1.count() == expectedAmmo,
                "count=" + slot1.count() + " 期望 " + expectedAmmo
                        + "（弹匣容量 " + ItemRegistry.pistol().gun().magazineSize() + "）");
        record("开局手持物是枪（左键语义因此是开火）",
                player.inventory().selectedStack().item().isGun(),
                "selectedSlot=" + player.inventory().selectedSlot()
                        + " item=" + player.inventory().selectedStack().item().id());
    }

    private void checkMineBlocked() {
        Player player = host.player();
        World world = host.world();
        long breaksDelta = player.blocksBroken() - stageStartBreaks;
        int belowId = world.blockIdAt(0, Coords.WORLD_SURFACE_BLOCK_Y, 0);

        var hit = player.currentTarget();
        boolean aimingBlockBelow = hit != null && hit.hasFace()
                && hit.blockX() == 0 && hit.blockY() == Coords.WORLD_SURFACE_BLOCK_Y && hit.blockZ() == 0;
        record("射线确实指向脚下方块（不是因为没有目标才没挖）", aimingBlockBelow,
                hit == null ? "currentTarget=null"
                        : String.format("currentTarget=(%d,%d,%d) face=%s",
                                hit.blockX(), hit.blockY(), hit.blockZ(), hit.faceName()));

        record("长按左键后破坏计数增量为 0（持枪时左键不是挖掘）", breaksDelta == 0,
                "blocksBroken 增量=" + breaksDelta + "（刺激 1.5 s，是草硬度 0.6 s 的 2.5 倍）");
        record("脚下方块 (0,63,0) 仍是草方块（世界没被改动）",
                belowId == BlockRegistry.grass().runtimeId(),
                "实际=" + BlockRegistry.byRuntimeId(belowId).id());
        record("玩家未进入挖掘状态", !player.isMining(),
                "isMining=" + player.isMining() + " 进度=" + player.miningProgressFraction());

        // 左键必须<b>确实</b>被送到了开火路径：空弹匣时表现为 onDryFire，有弹时表现为击发。
        int dryDelta = host.combat().dryFires() - stageStartDryFires;
        int shotDelta = host.combat().shotsFired() - stageStartShots;
        record("同一段左键被记为空枪/击发（证明左键语义是开火）", dryDelta + shotDelta > 0,
                "dryFires 增量=" + dryDelta + " shotsFired 增量=" + shotDelta);
    }

    private void checkDryFire() {
        Player player = host.player();
        GunState gun = host.combat().existingGun(player);
        int mag = gun == null ? -1 : gun.magazineAmmo();
        int dryDelta = eventDryFires - stageStartEventDryFires;

        record("开火前弹匣为空（PRD 5.7.1：入手时需先上一次膛）", mag == 0, "magazineAmmo=" + mag);
        record("空弹匣按左键收到 onDryFire", dryDelta >= 1, "onDryFire 事件数=" + dryDelta);
        record("空枪未命中实体", eventEntityHits == stageStartEventEntityHits,
                "onEntityHit 增量=" + (eventEntityHits - stageStartEventEntityHits));
        record("空枪未命中方块", eventBlockHits == stageStartEventBlockHits,
                "onBlockHit 增量=" + (eventBlockHits - stageStartEventBlockHits));
        record("空枪不消耗弹药（弹匣数不变）", mag == 0,
                "magazineAmmo=" + mag);
    }

    private void checkReloadFull() {
        Player player = host.player();
        GunState gun = host.combat().existingGun(player);
        int size = gun == null ? -1 : gun.magazineSize();
        int mag = gun == null ? -1 : gun.magazineAmmo();
        int reserve = player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID);

        record("按下 R 之后立刻进入换弹态", reloadingObservedAfterRequest,
                "reloadingObservedAfterRequest=" + reloadingObservedAfterRequest
                        + "（在第 " + reloadRequestedAtStep + " 步按下 R，同一逻辑步内观测）");
        int completions = eventReloadCompletions - stageStartEventReloadCompletions;
        record("收到一次 onReloadCompleted", completions == 1, "完成事件数=" + completions);
        double elapsed = reloadCompletedAtStep < 0 ? -1 : reloadCompletedAtStep * GameLoop.FIXED_DT;
        // 换算：换弹 1.2 s ÷ (1/60 s/步) = 72 步；倒计时每步累减 dt，浮点误差允许第 73 步完成 → 1.2167 s。
        record("换弹完成时刻 = 1.2 s ± 0.1 s",
                elapsed >= 1.1 && elapsed <= 1.3,
                String.format("实测 %.4f s（第 %d 步；理论 72 步 = 1.2000 s）",
                        elapsed, reloadCompletedAtStep));
        record("换弹完成后弹匣 = 12（满）", mag == size, "magazineAmmo=" + mag + " size=" + size);
        // M2.1-A：无限后备下换弹不从背包扣弹 —— 后备读数应当<b>等于开局那 24 发</b>，一发不少。
        // （改动前这里断言的是 12 = 24 − 12，已随产品口径一并作废。）
        record("换弹完成后后备弹药仍是 24（M2.1 无限口径：换弹只读后备、不写背包）",
                reserve == 24, "reserveAmmo=" + reserve);
    }

    private void checkReloadInterrupted() {
        Player player = host.player();
        GunState gun = host.combat().existingGun(player);
        int mag = gun == null ? -1 : gun.magazineAmmo();
        int reserve = player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID);

        record("连续开火把弹匣打空", magAfterEmptying == 0, "打空后 magazineAmmo=" + magAfterEmptying);
        record("按 R 之后立刻进入换弹态", reloadingObservedAfterRequest,
                "reloadingObservedAfterRequest=" + reloadingObservedAfterRequest);
        int cancels = eventReloadCancellations - stageStartEventReloadCancellations;
        record("按住 W 移动使换弹被取消（收到 onReloadCancelled）", cancels == 1,
                "取消事件数=" + cancels);
        record("被打断后换弹态已退出", gun != null && !gun.isReloading(),
                "isReloading=" + (gun != null && gun.isReloading()));
        // ★ A3 规则：完成前不得提前转移弹药 —— 打断等价于什么都没发生。
        //   弹匣这一条<b>不是</b>恒真断言：它取 0，而"打断没生效、换弹其实走完了"
        //   会把它变成 12，因此这条 0 == 0 有真实的鉴别力，保留原样。
        record("被打断后弹匣数与打断前一致（弹药不提前转移）",
                magAfterInterrupt == magAfterEmptying,
                "打断前=" + magAfterEmptying + " 打断后=" + magAfterInterrupt);
        //
        //   M2.1-A 注意：后备那一条则不同 —— 无限后备下"两次读数相同"会退化成
        //   24 == 24 的<b>恒真断言</b>（保留原写法等于留一个假阳性）。
        //   因此改成与开局弹药数直接比对，它断言的是更强的性质：
        //   整个"打空 → 按 R → 移动打断"过程里，背包弹药<b>从未被扣减过</b>。
        record("打空+换弹+打断全程后备弹药未被扣减（仍为 24）",
                reserveAfterInterrupt == 24 && reserveAfterEmptying == 24,
                "打空后=" + reserveAfterEmptying + " 打断后=" + reserveAfterInterrupt
                        + "（M2.1：换弹只读后备、不写背包）");
        int completions = eventReloadCompletions - stageStartEventReloadCompletions;
        record("停下之后能再次成功换弹", completions == 1, "完成事件数=" + completions);
        record("第二次换弹完成后弹匣 = 12", mag == 12, "magazineAmmo=" + mag);
        // 同上：M2.1 无限口径下第二次换弹同样不动背包。
        record("第二次换弹完成后后备弹药仍是 24（始终未被扣减）", reserve == 24,
                "reserveAmmo=" + reserve);
    }

    private void checkAim() {
        Player player = host.player();
        // 本方法在阶段末执行，此时玩家已经松开右键（第 61 步），
        // 因此"瞄准中"的三条断言用的是阶段内第 60 步（仍按着右键）采样的观测值。
        record("按住右键时进入瞄准状态", aimObservedAiming,
                "第 60 步 isAiming=" + aimObservedAiming);
        record("瞄准时 fovScale = 45/70（容差 1e-9）",
                Math.abs(aimObservedFovScale - Player.AIM_FOV_RATIO) <= 1e-9,
                String.format("实测 %.12f 期望 %.12f", aimObservedFovScale, Player.AIM_FOV_RATIO));
        record("瞄准时相机 FOV = 基础 FOV × 45/70",
                Math.abs(aimObservedCameraFov - player.baseFovDeg() * Player.AIM_FOV_RATIO) < 1e-6,
                String.format("实测 %.4f° 期望 %.4f°（基础 %.1f°）",
                        aimObservedCameraFov, player.baseFovDeg() * Player.AIM_FOV_RATIO,
                        player.baseFovDeg()));
        record("松开右键后退出瞄准状态", !player.isAiming(), "isAiming=" + player.isAiming());
        record("松开右键后 fovScale 还原为 1.0",
                Math.abs(player.fovScale() - 1.0) <= 1e-9,
                "fovScale=" + player.fovScale());
        record("松开右键后相机 FOV 还原为基础值",
                Math.abs(player.camera().fovDeg() - player.baseFovDeg()) < 1e-6,
                String.format("实测 %.4f° 基础 %.4f°",
                        player.camera().fovDeg(), player.baseFovDeg()));

        boolean moved = aimMoveDistance > 1.0 && noAimMoveDistance > 1.0;
        double ratio = noAimMoveDistance <= 1e-9 ? -1 : aimMoveDistance / noAimMoveDistance;
        record("两段位移都足够大（比值不会被 0/0 污染）", moved,
                String.format("瞄准 %.4f 格 / 非瞄准 %.4f 格（各 60 步 = 1 s，均从静止起步）",
                        aimMoveDistance, noAimMoveDistance));
        record("瞄准时水平移动速度 ≈ 非瞄准的 0.60 倍（容差 5%）",
                Math.abs(ratio - Player.AIM_MOVE_SPEED_RATIO) <= 0.05 * Player.AIM_MOVE_SPEED_RATIO,
                String.format("实测比值 %.6f 期望 %.6f（Δ=%.6f，容差 %.4f）",
                        ratio, Player.AIM_MOVE_SPEED_RATIO,
                        Math.abs(ratio - Player.AIM_MOVE_SPEED_RATIO),
                        0.05 * Player.AIM_MOVE_SPEED_RATIO));
    }

    private void checkSpawnAndApproach() {
        Player player = host.player();
        int sizeDelta = host.entities().size() - stageStartEntitySize;
        int aliveDelta = host.entities().aliveCount() - stageStartEntityAlive;
        int spawnedDelta = host.entities().totalSpawned() - stageStartEntitySpawned;

        // ★ 这里直接现读"阶段末尾的水平距离"，不走 observeAfterStep 采样。
        //   为什么：onStageEnd()（→ checkXxx）是在 nextIntent() 内部、在"本阶段最后一步
        //   真正执行之前"被调用的。也就是说 checkXxx 里能看到的最后一次逐步观测，
        //   只到倒数第二步为止 —— 在最后一步采样会永远晚于这里读取它的断言。
        //   与其把采样点挪到 <b>预算 − 2</b> 并在注释里维护这个偏置，不如就地读一次实测值。
        //
        //   步数核算（预算 45 步 = 第 0…44 步）：
        //     · 第 0 步摆姿势；第 1 步在 intentFor 内刷怪，该步的 entities.tick 随即跑了一次；
        //     · 本方法在"第 44 步执行之前"被调用 → 实体共 tick 了第 1…43 步 = 43 次。
        //     · spawnDistance 采于第 1 步结束（已 tick 1 次）= 4.0 − 1×0.03333 = 3.9667 格；
        //       （4.0 是 M2.1 新语义下"最近的合法候选"：候选带从 4.0 起、步长 0.5，见
        //        SkyIslandGame.debugSpawnMonster —— 不再是从前写死的 5.0）
        //       approachDistance 采于此 = 4.0 − 43×0.03333 = 2.5667 格；
        //       Δ = 42 个追击步 × 2.0/60 = 1.4000 格（实测 1.400，逐位吻合）。
        //   阈值 1.0 格的依据：它必须严格小于理论位移 1.400（否则理论值本身就会假红），
        //   又必须远大于 0（"完全没动"必然失败）；取 1.0 使两侧余量分别约 0.4 / 1.0 格。
        approachDistance = monster == null ? -1 : horizontalDistanceTo(player, monster);

        record("刷怪后实体总数 +1（走产品的 F4 刷怪路径）", sizeDelta == 1, "size 增量=" + sizeDelta);
        record("刷怪后存活数 +1", aliveDelta == 1, "aliveCount 增量=" + aliveDelta);
        record("刷怪后 totalSpawned +1", spawnedDelta == 1, "totalSpawned 增量=" + spawnedDelta);
        record("刷出的怪物类型是近战怪",
                monster != null && MeleeMonster.TYPE_ID.equals(monster.typeId()),
                "typeId=" + (monster == null ? "null" : monster.typeId()));
        record("刷出的怪物满血（生命 20/20）", monster != null
                        && monster.health() == MeleeMonster.MAX_HEALTH
                        && monster.maxHealth() == MeleeMonster.MAX_HEALTH,
                monster == null ? "monster=null"
                        : "health=" + monster.health() + "/" + monster.maxHealth());
        // ★ 这里必须比对"刷怪当步的落点"，不能读 monster.position()（阶段末尾它已经在走了）。
        //   期望值的推导（M2.1 新语义）：玩家在 (0.5, 64, 0.5) 视线水平朝 −Z，
        //   debugSpawnMonster 取"相机水平前方（yaw，丢弃俯仰）"，沿它从最近的候选 t=4.0 起试：
        //   第一个候选即 (0.5, 64, 0.5 − 4.0) = (0.5, 64, −3.5)。
        //   该点所在列 (0, 63, −4) 是草方块、上方两格是空气 → 合法落脚点；
        //   落差 Δy=0、视锥内、视线无遮挡 → 四条硬条件全过，直接采用（不再试更远的候选）。
        //   这与旧语义的 5.0 格不同是<b>产品语义变更的结果</b>，不是测试放宽。
        //
        //   容差必须容纳"刷怪那一步怪物就已经走过一格"这件事：
        //   spawn 发生在 currentIntent() 内，而 entities.tick() 在同一个逻辑步里随后执行，
        //   因此 observeAfterStep 读到的位置已经前移了 MOVE_SPEED × FIXED_DT = 2.0/60 = 0.0333 格。
        //   容差取 1.5 倍该步长 = 0.05 格：足以容纳这一步（实测偏差 0.0333），
        //   又远小于"落点被吸附到相邻格"（那会是整格 = 1.0 格的偏差）—— 即容差能区分这两种情形。
        double spawnTolerance = MeleeMonster.MOVE_SPEED * GameLoop.FIXED_DT * 1.5;   // 0.05
        record("刷怪落点 = 相机水平前方最近的合法候选 (0.5, 64.0, −3.5)",
                monster != null
                        && Math.abs(monsterSpawnX - 0.5) < spawnTolerance
                        && Math.abs(monsterSpawnY - 64.0) < spawnTolerance
                        && Math.abs(monsterSpawnZ - (-3.5)) < spawnTolerance,
                monster == null ? "monster=null"
                        : String.format("刷怪当步落点 (%.3f, %.3f, %.3f)，容差 %.4f 格"
                                        + "（= 1.5 × 追击步长 2.0/60）；阶段末尾已移动到 %.3f",
                                monsterSpawnX, monsterSpawnY, monsterSpawnZ, spawnTolerance,
                                monster.position().z));
        record("怪物进入追击状态（水平距离 ≤ 24 格）", monster != null && monster.isChasing(),
                "isChasing=" + (monster != null && monster.isChasing())
                        + "，初始水平距离=" + String.format("%.3f", spawnDistance));
        // 预算 45 步里的 42 个"净追击步"：追击速度 2.0 格/秒 × 42/60 s = 1.400 格，
        // 距离应从 3.967（第 1 步结束的读数）降到 2.567（本断言的读数）。
        // 阈值 1.0 格：严格小于理论位移 1.400（理论值本身不会假红），又远大于 0。
        record("怪物朝玩家走近（水平距离下降）",
                approachDistance > 0 && spawnDistance - approachDistance > 1.0,
                String.format("%.3f → %.3f 格（Δ=%.3f；理论 2.0 格/秒 × 42/60 s = 1.400 格）",
                        spawnDistance, approachDistance, spawnDistance - approachDistance));
        // 攻击距离 1.6 格：初始 3.967 − 1.433 = 2.53 > 1.6，因此整段预算内玩家都不会被咬，
        // 后续阶段（SHOOT_KILL 起）的"玩家生命是已知量"才站得住。
        record("收尾时怪物尚未进入攻击距离（玩家不会被咬，后续阶段生命是已知量）",
                approachDistance > MeleeMonster.ATTACK_RANGE,
                String.format("水平距离 %.3f > 攻击距离 %.1f",
                        approachDistance, MeleeMonster.ATTACK_RANGE));
        record("玩家未被咬伤（生命仍为满值）", player.health() == Player.MAX_HEALTH,
                "health=" + player.health() + "/" + Player.MAX_HEALTH);
    }

    private void checkShootKill() {
        int shotsDelta = host.combat().shotsFired() - stageStartShots;
        int hitsDelta = eventEntityHits - stageStartEventEntityHits;
        record("三次击发全部命中怪物（用满即止）", shotsDelta == 3 && hitsDelta == 3,
                "shotsFired 增量=" + shotsDelta + " onEntityHit 增量=" + hitsDelta);
        record("三发的伤害序列都是 8（有效射程内 100%）",
                entityHitDamages.size() >= 3
                        && entityHitDamages.get(entityHitDamages.size() - 3) == 8
                        && entityHitDamages.get(entityHitDamages.size() - 2) == 8
                        && entityHitDamages.get(entityHitDamages.size() - 1) == 8,
                "伤害序列=" + entityHitDamages);
        record("三发的命中距离都在有效射程 32 格内",
                entityHitDistances.size() >= 3
                        && entityHitDistances.get(entityHitDistances.size() - 3) < 32
                        && entityHitDistances.get(entityHitDistances.size() - 2) < 32
                        && entityHitDistances.get(entityHitDistances.size() - 1) < 32,
                "距离序列=" + entityHitDistances.stream()
                        .map(d -> String.format("%.3f", d)).toList());
        // 补上"生命被钳到 0"这一条的因果：三发累计结算 24 点，确实 ≥ 20。
        // 没有它，"第 3 发后生命 = 0"既可能是钳制，也可能是伤害根本没结算。
        int cumulativeDamage = entityHitDamages.size() >= 3
                ? entityHitDamages.get(entityHitDamages.size() - 3)
                + entityHitDamages.get(entityHitDamages.size() - 2)
                + entityHitDamages.get(entityHitDamages.size() - 1)
                : -1;
        record("三发累计结算伤害 ≥ 怪物总生命（所以末发生命是被钳到 0，不是伤害没生效）",
                cumulativeDamage >= MeleeMonster.MAX_HEALTH,
                cumulativeDamage + " ≥ " + MeleeMonster.MAX_HEALTH
                        + "（伤害序列末三发 = " + cumulativeDamage + "）");
        record("生命归零后怪物已死亡", monster != null && !monster.isAlive(),
                monster == null ? "monster=null" : "alive=" + monster.isAlive()
                        + " health=" + monster.health());
        record("死亡后存活实体数归零", host.entities().aliveCount() == 0,
                "aliveCount=" + host.entities().aliveCount());
        record("死亡后尸体已被 EntityManager 移除", host.entities().size() == 0,
                "size=" + host.entities().size());
        record("清理计数 +1（尸体确实被移除而不是变成隐身实体）",
                host.entities().totalRemoved() - stageStartEntityRemoved == 1,
                "totalRemoved 增量=" + (host.entities().totalRemoved() - stageStartEntityRemoved));
    }

    private void checkWall() {
        Player player = host.player();
        World world = host.world();

        record("石墙已建好（5 列 × 2 层 = 10 格，全部通过 World Mutation API）", wallPlaced == 10,
                "放置成功=" + wallPlaced + "/10");
        record("怪物仍在墙后存活且血量未变",
                monster != null && monster.isAlive() && monster.health() == MeleeMonster.MAX_HEALTH,
                monster == null ? "monster=null"
                        : "alive=" + monster.isAlive() + " health=" + monster.health()
                                + "（水平距离 " + String.format("%.3f", horizontalDistanceTo(player, monster))
                                + " 格）");
        int blockHitsDelta = host.combat().blockHits() - stageStartBlockHits;
        int entityHitsDelta = host.combat().entityHits() - stageStartEntityHits;
        record("这一枪被记为方块命中", blockHitsDelta == 1, "blockHits 增量=" + blockHitsDelta);
        record("这一枪没有被记为实体命中（不穿墙）", entityHitsDelta == 0,
                "entityHits 增量=" + entityHitsDelta);
        record("onBlockHit 带的是石头的 runtimeId",
                lastBlockHitRuntimeId == BlockRegistry.stone().runtimeId(),
                "runtimeId=" + lastBlockHitRuntimeId + "（"
                        + BlockRegistry.byRuntimeId(lastBlockHitRuntimeId).id() + "）");
        record("本阶段没有收到任何 onEntityHit", eventEntityHits == stageStartEventEntityHits,
                "onEntityHit 增量=" + (eventEntityHits - stageStartEventEntityHits));
        // ★ 期望值是"到墙面"而不是"到方块中心"：`RaycastHit.distance()` 的定义是
        //   「从起点到命中面（face）的距离」（见 RaycastHit 的 @param distance）——
        //   射线不是打到方块中心，而是打到它朝向射线的那个面。
        //   算式：墙占方块 z = WALL_Z，其朝向玩家的 +Z 面位于 z = WALL_Z + 1。
        //        玩家脚位 z = 0.5 → 墙面 z = WALL_Z + 1 = −2.0 → 距离 = 0.5 − (−2.0) = 2.5 格。
        //   （若按"到方块中心 z = −3.0"算会得到 3.5，那是把方块当成零厚度点的错误前提；
        //     首轮运行实测 2.5000，正是墙面口径。见 M2_SELFTEST_EVIDENCE.md。）
        //   对照组：同一时刻怪物在这堵墙后面 —— 它的水平距离由 monster 实测给出。
        //   本题要区分的就是"子弹停在墙面（2.5）"与"子弹穿过墙打到怪（怪物距离）"。
        double expectedWallHitDistance = 0.5 - (WALL_Z + 1.0);   // 2.5
        double d = host.combat().lastDistance();
        double monsterDist = monster == null ? -1 : horizontalDistanceTo(player, monster);
        record(String.format("命中距离 = 到墙面的距离（%.1f 格），而不是到怪物的距离（%.3f 格）",
                        expectedWallHitDistance, monsterDist),
                Math.abs(d - expectedWallHitDistance) < 0.1,
                String.format("实测 %.4f 格；墙面在 z=%d（方块 z=%d 的 +Z 面）、"
                                + "怪物在 z=%.3f（水平距离 %.3f 格）、玩家脚位 z=%.2f",
                        d, WALL_Z + 1, WALL_Z,
                        monster == null ? Double.NaN : monster.position().z,
                        monsterDist, player.position().z));

        // ---- 清场：拆掉石墙，避免它影响后续阶段的地形前提 ----
        for (int x = WALL_MIN_X; x <= WALL_MAX_X; x++) {
            if (world.breakBlock(x, WALL_BASE_Y, WALL_Z,
                    World.MutationCause.SELF_TEST).success()) {
                wallBroken++;
            }
            if (world.breakBlock(x, WALL_TOP_Y, WALL_Z,
                    World.MutationCause.SELF_TEST).success()) {
                wallBroken++;
            }
        }
        record("石墙已拆除（10 格全部恢复为空气，不留测试残留地形）",
                wallBroken == 10 && world.isAirAt(0, WALL_TOP_Y, WALL_Z),
                "拆除=" + wallBroken + "/10");
        host.entities().clear();   // 这只怪已经完成它的举证，不再参与后续阶段
    }

    private void checkFalloff() {
        Player player = host.player();
        falloffDistance = entityHitDistances.isEmpty()
                ? -1 : entityHitDistances.get(entityHitDistances.size() - 1);
        falloffDamage = entityHitDamages.isEmpty()
                ? -1 : entityHitDamages.get(entityHitDamages.size() - 1);

        record("命中的是 45 格外的目标（超出有效射程 32 格）",
                falloffDistance > 40 && falloffDistance < 50,
                String.format("实测命中距离 %.4f 格（放置距离 45.0；射线长度上限 = 32 × 2 = 64）",
                        falloffDistance));
        record("命中距离确实超出有效射程 32 格", falloffDistance > 32,
                String.format("%.4f > 32", falloffDistance));
        record("超出有效射程后单发伤害 < 8（不再是 100%）", falloffDamage < 8,
                "实测伤害=" + falloffDamage + "（基础伤害 8）");

        // 断言公式本身：floor(base × max(0.20, 0.9^(d−32)))，保底 1。
        // 用<u>实测距离</u>反推期望值，因此"距离算错"与"衰减算错"会分别暴露。
        double multiplier = Math.max(DamageFalloff.MIN_MULTIPLIER,
                Math.pow(DamageFalloff.MULTIPLIER_PER_BLOCK, falloffDistance - 32.0));
        int expected = Math.max(1, (int) Math.floor(8 * multiplier + 1e-9));
        record("伤害等于 floor(8 × max(0.20, 0.9^(d−32)))（保底 1）",
                falloffDamage == expected,
                String.format("实测=%d 期望=%d（d=%.4f → 乘数 %.6f → 8×乘数 %.6f）",
                        falloffDamage, expected, falloffDistance, multiplier, 8 * multiplier));
        record("伤害不低于保底 1（不会出现命中却零伤害）", falloffDamage >= 1,
                "实测伤害=" + falloffDamage);
        record("45 格处的手枪伤害 = 2（PRD 5.4.3 承诺值）", falloffDamage == 2,
                "实测伤害=" + falloffDamage + "（0.9^12.7 ≈ 0.262，8 × 0.262 ≈ 2.10 → 2）");
        record("怪物实际扣除的血量等于结算伤害",
                monster != null && monster.health() == MeleeMonster.MAX_HEALTH - falloffDamage,
                monster == null ? "monster=null"
                        : "health=" + monster.health() + " 期望="
                                + (MeleeMonster.MAX_HEALTH - falloffDamage));
        record("玩家在 45 格外仍保持静止（没有走过去缩短距离）",
                Math.abs(player.position().x - FALLOFF_PLAYER_X) < 0.05
                        && Math.abs(player.position().z - FALLOFF_PLAYER_Z) < 0.05,
                String.format("位置 (%.3f, %.3f, %.3f)", player.position().x,
                        player.position().y, player.position().z));
    }

    private void checkBreak() {
        Player player = host.player();
        World world = host.world();
        long breaksDelta = player.blocksBroken() - stageStartBreaks;

        record("第一次挖掘成功（手持非枪物品即可挖掘）", breaksDelta >= 1,
                "blocksBroken 增量=" + breaksDelta);
        if (breakTargetAX != Integer.MIN_VALUE) {
            boolean air = world.isAirAt(breakTargetAX, breakTargetAY, breakTargetAZ);
            record(String.format("第一格 (%d,%d,%d) 已变成空气", breakTargetAX, breakTargetAY, breakTargetAZ),
                    air, "isAirAt=" + air);
        } else {
            record("第一次挖掘前捕获到有效瞄准面", false, "currentTarget 为空或没有可用面");
        }
        record("挖掘产出方块物品（草 → 泥土，PRD 5.1 掉落表）",
                firstBlockSlot(player) >= 0,
                "快捷栏=" + player.inventory());
        record("第二次挖掘（手持该方块物品）成功", breaksDelta >= 2,
                "blocksBroken 增量=" + breaksDelta);
        if (breakTargetBX != Integer.MIN_VALUE) {
            boolean air = world.isAirAt(breakTargetBX, breakTargetBY, breakTargetBZ);
            record(String.format("第二格 (%d,%d,%d) 已变成空气", breakTargetBX, breakTargetBY, breakTargetBZ),
                    air, "isAirAt=" + air);
        } else {
            record("第二次挖掘前捕获到有效瞄准面", false, "currentTarget 为空或没有可用面");
        }
        record("两次挖掘命中不同的方块（第二次不是对同一格的重复结算）",
                breakTargetAX != breakTargetBX || breakTargetAY != breakTargetBY
                        || breakTargetAZ != breakTargetBZ,
                String.format("第一格 (%d,%d,%d) / 第二格 (%d,%d,%d)",
                        breakTargetAX, breakTargetAY, breakTargetAZ,
                        breakTargetBX, breakTargetBY, breakTargetBZ));

        int lastCount = host.combatFx().lastBreakParticleCount();
        record("破坏粒子数落在 8–12 的闭区间内（PRD 5.2 占位规格）",
                lastCount >= CombatFxModel.BREAK_PARTICLES_MIN
                        && lastCount <= CombatFxModel.BREAK_PARTICLES_MAX,
                "最近一次 spawnBlockBreak 生成 " + lastCount + " 个（区间 "
                        + CombatFxModel.BREAK_PARTICLES_MIN + ".."
                        + CombatFxModel.BREAK_PARTICLES_MAX + "）");

        long delta = host.combatFx().totalBreakParticles() - breakParticlesAfterFirstBreak;
        record("第二次破坏的累计粒子增量同样落在 8–12 内",
                breakParticlesAfterFirstBreak >= 0
                        && delta >= CombatFxModel.BREAK_PARTICLES_MIN
                        && delta <= CombatFxModel.BREAK_PARTICLES_MAX,
                "totalBreakParticles " + breakParticlesAfterFirstBreak + " → "
                        + host.combatFx().totalBreakParticles() + "（增量 " + delta + "）");
        record("累计粒子总数 > 0（表现层确实被调用过）",
                host.combatFx().totalBreakParticles() > 0,
                "totalBreakParticles=" + host.combatFx().totalBreakParticles()
                        + " totalSpawnCalls=" + host.combatFx().totalSpawnCalls());
    }

    private void checkDeath() {
        Player player = host.player();
        int deathsDelta = player.deaths() - stageStartDeaths;

        record("走真实虚空路径致死（不是直接调 die()）", deathsDelta == 1,
                "死亡计数增量=" + deathsDelta);
        record("倒下当帧生命归零", healthAtDeath == 0, "倒下时生命=" + healthAtDeath);
        double countdown = deathAtStep < 0 || respawnAtStep < 0
                ? -1 : (respawnAtStep - deathAtStep) * GameLoop.FIXED_DT;
        // 换算：PRD 5.3 规定 3 秒；倒计时每步累减 dt，180 步 = 3.0 s，浮点误差 ±1 步。
        record("死亡到重生的倒计时 = 3.0 s（容差 0.1 s）",
                countdown >= 2.9 && countdown <= 3.1,
                String.format("实测 %.4f s（第 %d 步倒下 → 第 %d 步重生）",
                        countdown, deathAtStep, respawnAtStep));
        record("重生后生命回满 20", player.health() == Player.MAX_HEALTH,
                "health=" + player.health() + "/" + Player.MAX_HEALTH);
        boolean legal = player.isStandingSpotValid(host.world(), respawnX, respawnY, respawnZ);
        record("重生落点是合法落脚点", legal,
                String.format("(%.3f, %.3f, %.3f)", respawnX, respawnY, respawnZ));
        // PRD 5.3.1 B：沿 y = 64 平面螺旋搜索，半径 ≤ 16 格。
        // 判据取"到 (0,64,0) 的水平切比雪夫距离 ≤ 16 且 y = 64"：螺旋搜索的每一环
        // 都是切比雪夫距离为 r 的方块，而搜索起点就是方块 (0,64,0)（站姿中心 = (0.5,64,0.5)）。
        double chebyshev = Math.max(Math.abs(respawnX - 0.5), Math.abs(respawnZ - 0.5));
        boolean inRadius = chebyshev <= Player.RESPAWN_SEARCH_RADIUS
                && Math.abs(respawnY - SPAWN_Y) < 0.05;
        record("重生点在世界出生点的螺旋搜索半径内（≤ 16 格、y = 64）", inRadius,
                String.format("切比雪夫距离 %.3f ≤ %d，y=%.4f",
                        chebyshev, Player.RESPAWN_SEARCH_RADIUS, respawnY));
        record("重生点即世界出生点 (0.5, 64, 0.5)（该列方块未被本次自测改动）",
                Math.abs(respawnX - SPAWN_X) < 1e-9 && Math.abs(respawnY - SPAWN_Y) < 1e-9
                        && Math.abs(respawnZ - SPAWN_Z) < 1e-9,
                String.format("实测 (%.4f, %.4f, %.4f) 期望 (%.1f, %.1f, %.1f)",
                        respawnX, respawnY, respawnZ, SPAWN_X, SPAWN_Y, SPAWN_Z));
    }

    private void checkSave() {
        Player player = host.player();
        savedPistolCount = player.inventory().countOfItem(ItemRegistry.PISTOL_ID);
        savedAmmoCount = player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID);
        savedHotbar.clear();
        savedHotbar.addAll(player.inventory().snapshot());

        record("调试补给后快捷栏里有弹药（让'弹药往返'这条断言有意义）", savedAmmoCount > 0,
                "手枪=" + savedPistolCount + " 手枪弹=" + savedAmmoCount);

        if (!host.saveEnabled()) {
            /*
             * 对照运行（skyisland.noSave=true）：存档被产品开关关掉了。
             * 此时"存档成功 / 读档一致"这类断言必然失败，而失败原因与引擎无关，
             * 因此显式跳过并记录范围 —— 不让它变成假失败，也不让它伪装成真通过。
             */
            saveAssertionsSkipped = true;
            Log.info("[自测] 存档已被 skyisland.noSave=true 关闭，跳过存档相关断言。"
                    + "本次运行仅用于无存档对照，不得单独作为 M2 战斗闭环证据。");
            return;
        }
        SaveResult result = host.requestSave();
        record("存档返回成功", result != null && result.success(),
                result == null ? "null" : result.oneLine());
        record("存档没有产生任何警告", result != null && result.warnings().isEmpty(),
                result == null ? "null" : ("warnings=" + result.warnings()));
        if (result != null) {
            for (String warning : result.warnings()) {
                Log.noteWarning("自测", "存档警告: " + warning);
            }
        }
        saveVerifiedInLoop = true;
    }

    // ============================================================ 循环之外的读档校验

    /**
     * 独立世界里重放存档，验证"快捷栏确实落盘并可恢复"。
     *
     * <p><b>为什么这一步在循环之外（与 M1 的做法一致，理由也一样）：</b>
     * {@code §C.4′ 第 7 条} —— 测试脚手架不得在 game loop 里制造假卡顿。
     * 造一个世界（16 个区块的生成）是纯 CPU 工作，耗时以百毫秒计；
     * 放进逻辑步会被记进帧时间统计，让"引擎稳态帧时间"这个数字失真。
     * 放在收尾阶段则完全不影响测量窗口（此时窗口已经关闭）。
     *
     * <p>它与在循环内做的"存档成功 + warnings 为空"合起来构成 PRD 要求的存档往返举证：
     * 循环内证明<u>写</u>成功，这里证明<u>读</u>回来一致。
     */
    public void verifyReload() {
        Log.info("==================== M2 自测：读档校验 ====================");
        if (!host.saveEnabled()) {
            saveAssertionsSkipped = true;
            Log.info("  读档校验: 已跳过（skyisland.noSave=true，本次为对照运行）");
            Log.info("==========================================================");
            return;
        }
        if (!saveVerifiedInLoop) {
            record("读档校验的前提：循环内已成功存档", false,
                    "saveVerifiedInLoop=false（自测没跑到 SAVE 阶段就结束了）");
            Log.info("==========================================================");
            return;
        }

        World reloaded = new World(host.world().seed(), new TestWorldGenerator());
        reloaded.ensureAreaLoaded(TestWorldGenerator.MIN_CHUNK, TestWorldGenerator.MIN_CHUNK,
                TestWorldGenerator.MAX_CHUNK, TestWorldGenerator.MAX_CHUNK);
        Player reloadedPlayer = new Player(SPAWN_X, SPAWN_Y, SPAWN_Z);
        SaveResult result = host.saveManager().loadInto(reloaded, reloadedPlayer);

        record("读档返回成功", result != null && result.success(),
                result == null ? "null" : result.oneLine());
        record("读档没有产生任何警告", result != null && result.warnings().isEmpty(),
                result == null ? "null" : ("warnings=" + result.warnings()));

        // ★ 逐格比对 item id 与数量：这是"枪与弹药真的跨存档往返"的硬证据。
        //   只比总数是不够的 —— 把枪与弹药对调槽位在总数上完全看不出来。
        int mismatches = 0;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < savedHotbar.size(); i++) {
            ItemStack before = savedHotbar.get(i);
            ItemStack after = reloadedPlayer.inventory().slot(i);
            boolean same = before.itemRuntimeId() == after.itemRuntimeId()
                    && before.count() == after.count();
            if (!same) {
                mismatches++;
            }
            detail.append(String.format("[%d]%s→%s ", i, before, after));
        }
        record("读档后快捷栏逐格 item id 与数量一致（" + savedHotbar.size() + " 格）",
                mismatches == 0, "不一致格数=" + mismatches + " " + detail);

        record("读档后手枪仍在（按 item id 计数量）",
                reloadedPlayer.inventory().countOfItem(ItemRegistry.PISTOL_ID) == savedPistolCount,
                "读回=" + reloadedPlayer.inventory().countOfItem(ItemRegistry.PISTOL_ID)
                        + " 存档前=" + savedPistolCount);
        record("读档后手枪弹仍在（按 item id 计数量）",
                reloadedPlayer.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID) == savedAmmoCount,
                "读回=" + reloadedPlayer.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID)
                        + " 存档前=" + savedAmmoCount);
        record("读档后弹药数量 > 0（不是退化成 0 == 0 的空断言）", savedAmmoCount > 0
                        && reloadedPlayer.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID) > 0,
                "存档前=" + savedAmmoCount + " 读回="
                        + reloadedPlayer.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID));
        Log.info("==========================================================");
    }

    /**
     * M2.1：音频链接线校验（循环外执行，与存档校验并列）。
     *
     * <h2>为什么这几条断言值得存在</h2>
     * 本阶段的音频包在实现完成时是<b>整块未接线</b>的：类齐全、注释完整、
     * 单独编译通过，而 {@code SkyIslandGame} 里没有任何一行创建过它。
     * 那种状态下玩家听不到声音，但这与"这台机器没有声卡"在日志上完全一样 ——
     * 于是"没声音"这件事既无法归因，也无法在门禁里变红。
     *
     * <p>本方法把二者彻底分开：{@link RecordingAudioSink} 记录的是<b>事件</b>，
     * 与播放后端在不在无关。下面每条音效对应的事件在本次运行里都<u>必然</u>发生过
     * （依据是各阶段的定义，见 STAGE_BUDGET 的注释）：
     * <ul>
     *   <li>{@code gun_empty} ← DRY_FIRE 阶段每步都会产生一次空枪事件；</li>
     *   <li>{@code gun_fire} ← SHOOT_KILL 阶段打了 3 发；</li>
     *   <li>{@code hit_enemy} ← 同上，3 发全部命中；</li>
     *   <li>{@code reload} ← RELOAD_FULL / RELOAD_INTERRUPTED 各按过一次 R 且被受理；</li>
     *   <li>{@code player_hurt} ← DEATH_AND_RESPAWN 阶段掉进虚空，血量下降。</li>
     * </ul>
     * 因此"计数为 0"只有两种可能：触发链断了，或音频层没接上 —— 两者都必须判红。
     *
     * <p><b>为什么不断言"真的发声了"：</b>门禁运行是无头的，可能根本没有音频设备。
     * 把"isPlaying"当断言会让音频环境决定门禁结果，那是把环境噪声引进判定。
     * 播放侧链路本身由 {@code OpenAlBackend} 的降级设计与单测覆盖，
     * 这里只负责证明"事件确实被送到了音频层"。
     */
    public void verifyAudio() {
        Log.info("==================== M2.1 自测：音频链校验 ====================");
        AudioManager audio = host.audio();
        if (audio == null) {
            record("音频门面已接线（host.audio() 非 null）", false,
                    "audio=null —— 音频子系统没有被游戏主类创建");
            Log.info("==========================================================");
            return;
        }
        record("音频门面已接线（host.audio() 非 null）", true, "status=" + audio.statusText());

        RecordingAudioSink audit = audio.audit();
        // 用"期望至少 N 次"而不是"恰好 N 次"：这些音效的条数由阶段预算的浮点边界决定，
        // 锁死次数会让一条无害的时序变化变成假红 —— 而这里要守的是"链通了没有"。
        record("空枪事件已送达音频层（gun_empty ≥ 1）",
                audit.countOf(AudioEvent.GUN_EMPTY) >= 1,
                "gun_empty=" + audit.countOf(AudioEvent.GUN_EMPTY));
        record("开火事件已送达音频层（gun_fire ≥ 3，对应 SHOOT_KILL 的 3 发）",
                audit.countOf(AudioEvent.GUN_FIRE) >= 3,
                "gun_fire=" + audit.countOf(AudioEvent.GUN_FIRE));
        record("命中事件已送达音频层（hit_enemy ≥ 3）",
                audit.countOf(AudioEvent.HIT_ENEMY) >= 3,
                "hit_enemy=" + audit.countOf(AudioEvent.HIT_ENEMY));
        record("换弹事件已送达音频层（reload ≥ 1）",
                audit.countOf(AudioEvent.RELOAD) >= 1,
                "reload=" + audit.countOf(AudioEvent.RELOAD));
        record("玩家受伤事件已送达音频层（player_hurt ≥ 1，来自坠落死亡）",
                audit.countOf(AudioEvent.PLAYER_HURT) >= 1,
                "player_hurt=" + audit.countOf(AudioEvent.PLAYER_HURT));
        /*
         * ★ 伤害来源归因 —— 首轮试玩复盘时发现「怎么死的」根本无法回答：
         *   日志里只有一个总数 player_hurt=5，Player.hurt() 不打印任何东西，
         *   于是"被近战怪咬"与"摔落"在证据上完全区分不开
         *   （见 docs/testing/M2_1_PLAYTEST_EVIDENCE_2026-09-22.md 第 4.1 节）。
         *
         * 本阶段（DEATH_AND_RESPAWN）的血量下降走的正是"坠入虚空 → die()"路径，
         * 因此来源必须是 VOID —— 既不是近战（MELEE）也不是坠落伤害（FALL）。
         * 旧代码里 Player 不记录来源，这个 accessor 也还不存在：把它还原成"不记录"
         * 时，本条读到 null 并判红（等价回退实验见交付报告）。
         */
        Player.DamageCause hurtCause = host.player().lastDamageCause();
        record("玩家受伤来源已归因（DEATH_AND_RESPAWN 虚空致死 → 来源=虚空 VOID）",
                hurtCause == Player.DamageCause.VOID,
                "lastDamageCause=" + hurtCause
                        + "（期望 VOID；旧代码此处没有来源可读）");
        int sum = audit.countOf(AudioEvent.GUN_EMPTY) + audit.countOf(AudioEvent.GUN_FIRE)
                + audit.countOf(AudioEvent.HIT_ENEMY) + audit.countOf(AudioEvent.RELOAD)
                + audit.countOf(AudioEvent.PLAYER_HURT);
        record("事件记录自洽（总数不小于五类之和）", audit.size() >= sum,
                "size=" + audit.size() + " 五类之和=" + sum);
        Log.info("[自测] 音频会话计数：%s", audit.summaryLine());
        Log.info("==========================================================");
    }

    /**
     * M2.1：<b>表现层</b>的触发链校验 —— 枪口闪光 / 命中标记 / 命中溅射 / 后坐力。
     *
     * <h2>为什么听觉有断言、视觉也必须有一条</h2>
     * M2.1 交付时发生过这样一件事：{@code spawnMuzzleFlash}、{@code spawnHitMarker}、
     * {@code spawnEntityHit}、{@code addRecoilPitch}、{@code decayRecoil} 五个方法
     * <b>全部只被定义、从不被调用</b>，而编译通过、815 条单测全绿、
     * M1 / UI / M2 三门禁也全绿。原因是同一个：它们各自有一个"看起来成功"的读数 ——
     * 粒子与曳光还在正常累计（{@code totalSpawnCalls > 0} 依然成立），
     * 于是既有的那条"表现层收到了事件"的断言照样通过。
     *
     * <p>这就是"断言全绿 ≠ 无缺陷"的教科书版本：<b>缺少的那几条根本没有对应的断言</b>，
     * 而不是已有的断言判错了。所以这里补的是一组<b>与播放环境无关</b>的计数断言 ——
     * 它们和无头运行兼容，且只有"接线真的存在"才能变绿。
     *
     * <p>计数为什么用 {@code ≥} 而不是 {@code ==}：各阶段打了不止 3 发
     * （SHOOT_KILL、WALL_BLOCKS_BULLET、DAMAGE_FALLOFF 都会开火），
     * 锁死次数会把一次无害的时序调整变成假红。这里要守的是"链通了没有"。
     */
    public void verifyCombatFx() {
        Log.info("================ M2.1 自测：表现层触发链校验 ================");
        CombatFxModel fx = host.combatFx();
        if (fx == null) {
            record("表现层门面已接线（host.combatFx() 非 null）", false,
                    "combatFx=null —— 特效模型没有被游戏主类创建");
            Log.info("==========================================================");
            return;
        }
        record("枪口闪光已触发（muzzle_flash ≥ 3）", fx.totalMuzzleFlashes() >= 3,
                "totalMuzzleFlashes=" + fx.totalMuzzleFlashes());
        record("命中标记已触发（hit_marker ≥ 3）", fx.totalHitMarkers() >= 3,
                "totalHitMarkers=" + fx.totalHitMarkers());
        record("命中溅射粒子已触发（entity_hit_burst ≥ 3，与命中次数同源）",
                fx.totalEntityHitBursts() >= 3,
                "totalEntityHitBursts=" + fx.totalEntityHitBursts());
        // 三条计数之间的一致性：每次命中都应当同时给出标记与溅射。
        // 只对上"≥ 3"是不够的 —— 那三条各自独立成立时，仍然可能是"标记接了、溅射没接"。
        record("命中标记数与命中溅射数一致（每发命中一份视觉确认）",
                fx.totalHitMarkers() == fx.totalEntityHitBursts(),
                "hitMarkers=" + fx.totalHitMarkers()
                        + " entityHitBursts=" + fx.totalEntityHitBursts());

        record("开火后坐力确实抬过视角（观测峰值 > 0）", peakRecoilDeg > 0.0,
                "peakRecoilDeg=" + peakRecoilDeg + "（单发 "
                        + com.skyisland.player.Camera.RECOIL_PITCH_PER_SHOT_DEG
                        + "°，上限 "
                        + com.skyisland.player.Camera.MAX_RECOIL_PITCH_DEG + "°）");
        record("后坐力峰值不超过上限",
                peakRecoilDeg <= com.skyisland.player.Camera.MAX_RECOIL_PITCH_DEG + 1e-9,
                "peakRecoilDeg=" + peakRecoilDeg + " ≤ "
                        + com.skyisland.player.Camera.MAX_RECOIL_PITCH_DEG);
        // 线性回落的承诺是"精确回到 0"（见 Camera.RECOIL_RECOVER_DEG_PER_SEC 的注释：
        // 留残差的后果是静止瞄准时画面永远差一点，且无法归因）。
        // 收尾时距最后一次开火已隔了若干阶段（含一次 3 秒重生），必然早已归零。
        double resting = host.player().camera().recoilPitchDeg();
        record("后坐力已精确回落到 0（回落链也已接通，而不是只加不减）",
                resting == 0.0, "收尾时 recoilPitchDeg=" + resting);

        /*
         * ---- M2.1 缺陷 A：枪口闪光必须离开眼睛 ----
         * 现场症状："枪口火光有点刺眼"。根因是枪口偏移只写在注释里、代码里根本不存在，
         * 于是闪光生在眼睛处，被相机吞进 0.16 格的立方体内部；关闭背面剔除后，
         * 内部面被光栅化成一片盖住准星的白。旧代码里没有任何断言看得见这一点
         * （既有的闪光断言只数 muzzle_flash / quad 数量）。
         *
         * 为什么读"留档"而不是 fx.flashes()：闪光只活 3 帧，本方法在收尾（shutdown）
         * 才跑，实时列表早已清空。CombatFxModel 因此把"生成当刻"的闪光位置 + 眼睛 + 视线
         * 留成一条 MuzzleFlashSample（与读取时刻无关）。
         *
         * 这条断言在旧代码上必红：那时闪光坐标 == 眼睛坐标，distanceFromEye() 约 0。
         */
        CombatFxModel.MuzzleFlashSample muzzle = fx.lastMuzzleFlashSample();
        double muzzleFromEye = muzzle == null ? -1.0 : muzzle.distanceFromEye();
        record("枪口闪光不生在眼睛处（距眼睛 ≥ 0.40 格 —— 旧代码约 0）",
                muzzle != null && muzzleFromEye >= 0.40,
                muzzle == null ? "没有留档的枪口闪光样本"
                        : String.format("距眼睛 %.4f 格（MUZZLE_FORWARD=%.2f）",
                                muzzleFromEye, SkyIslandGame.MUZZLE_FORWARD));
        double muzzleForwardDot = muzzle == null ? Double.NaN : muzzle.forwardDot();
        record("枪口闪光在眼睛前方（与视线点积 > 0）",
                muzzle != null && muzzleForwardDot > 0.0,
                muzzle == null ? "没有留档的枪口闪光样本"
                        : String.format("前向点积 %.4f", muzzleForwardDot));

        /*
         * M2.1 缺陷（实体世界 Y）：把"怪物确实出现在帧缓冲里"变成一条会变红的断言。
         *
         * 为什么不新开一个独立 verify 由 SkyIslandGame 调用：该主类的收尾调用点由另一位
         * worker 持有（本轮并发修改），因此把像素校验<b>挂在本就接线好的表现层校验之后</b>
         * ——它和上面的枪口闪光/命中标记同属"视觉是否真的产出"，只是口径不同：
         * 上面数的是"特效方法被调了几次"，这里数的是"屏幕上有没有像素"。
         */
        verifyMonsterFramebuffer();
        Log.info("==========================================================");
    }

    /**
     * M2.1：<b>帧缓冲像素证据</b> —— 数出屏幕上真的出现了怪物像素，而不只是"提交了几何"。
     *
     * <h2>它补的是哪一段缺口</h2>
     * 在它出现之前，"怪物在视野里"的全部证据是顶点数 / 盒体数 / 一张没人看过的截图。
     * 三者都只回答"提交了多少几何"，而"几何提交了没有"与"屏幕上出现了没有"可以独立失败：
     * {@code EntityRenderer#buildMonsterVertices} 的 pivotY 曾被写成 {@code 0.0}，
     * 于是怪被画到地下、<b>屏幕上一个像素都没有</b>，而盒体数、日志提示全部正常。
     *
     * <h2>为什么复用截图链路而不是自造一套渲染</h2>
     * 像素来自产品自己的链路：{@code requestScreenshot("monster-in-view")}
     * → {@code SkyIslandGame.captureScreenshot} → {@code Screenshot.readPixels}
     * （真正的 {@code glReadPixels}，在两缓冲交换之前）→ 落盘 PNG（无损）。
     * 被校验的这一帧，是 {@code SPAWN_AND_APPROACH} 第 30 步：
     * 玩家被摆到出生点、视线水平朝 −Z，F4 刷出的怪在其正前方约 4 格 ——
     * 即"怪正对着镜头、居中"的确定性位姿。
     *
     * <h2>判据为什么能变红、且能区分背景</h2>
     * 命中必须落在<b>屏幕中央的有界区域</b>（中央 40% 宽 × 60% 高），并与"整屏命中数"
     * 分开报告，避免把画面边缘的地形色算进来；主判据只数四个"体色"（偏红，在天空/草地前分得开），
     * 眼睛色（近纯黄，与准星同色系）只作参考。pre-fix 状态下怪沉在地下、中央区域命中为 0，
     * 本方法必红；post-fix 下中央区域命中为数千像素。
     */
    public void verifyMonsterFramebuffer() {
        Log.info("================ M2.1 自测：怪物帧缓冲像素证据 ================");
        Path dir = Path.of(System.getProperty("skyisland.screenshotDir", "screenshots"));
        Path png = newestMonsterPng(dir);
        if (png == null) {
            record("monster-in-view 截图已落盘（否则无像素可读）", false,
                    "目录 " + dir.toAbsolutePath() + " 下找不到本轮（请求于 epochMs="
                            + approachScreenshotRequestedAtMs + "）的 monster-in-view PNG");
            return;
        }

        BufferedImage image;
        try {
            image = ImageIO.read(png.toFile());
        } catch (IOException e) {
            record("monster-in-view 截图可读", false, "读取失败：" + e);
            return;
        }
        if (image == null) {
            record("monster-in-view 截图可读（不是空/损坏文件）", false,
                    png.getFileName().toString() + " 无法解码为图像");
            return;
        }
        record("monster-in-view 截图已落盘且可解码", true, png.getFileName().toString());

        int w = image.getWidth();
        int h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        // 中央有界区域：宽 40% × 高 60%，居中。
        double regionW = 0.40;
        double regionH = 0.60;
        MonsterPixelEvidence.Result r = MonsterPixelEvidence.analyze(
                argb, w, h, regionW, regionH, MonsterPixelEvidence.DEFAULT_TOLERANCE);
        Log.info("[自测] %s", r.describe());
        Log.info("[自测] 中央区域出现最多的颜色（实测，用于核对 round(分量×255) 口径）：%s",
                MonsterPixelEvidence.topColors(argb, w, h, regionW, regionH, 8));

        /*
         * 主判据：中央有界区域内必须存在足够多的"体色"像素。
         * 阈值 1000 取在"0（pre-fix，怪在地下 → 屏幕上无像素）"与
         * "数千（post-fix，怪正对镜头）"之间很远处：既有判别力，又对朝向/抖动不敏感。
         */
        final int minBodyPixels = 1000;
        record("中央有界区域内存在怪物体色像素（体色命中 ≥ " + minBodyPixels + "）",
                r.bodyHitsCenter() >= minBodyPixels,
                "中央命中=" + r.bodyHitsCenter() + "（占中央 " + r.centerPixelCount() + " 像素）"
                        + "，整屏命中=" + r.bodyHitsFull()
                        + "，容差=" + MonsterPixelEvidence.DEFAULT_TOLERANCE);
        /*
         * 第二判据：命中集中在中央区域 —— 单独一条，防止"整屏到处都是近似体色"时
         * 靠背景凑数。post-fix 下中央命中应当占了整屏命中的绝大部分
         * （怪就在中央，边缘没有别的体色）；pre-fix 下两者都是 0。
         */
        record("怪物像素集中在中央区域（中央命中 ≥ 整屏命中的 60%）",
                r.bodyHitsCenter() > 0 && r.bodyHitsCenter() * 5 >= r.bodyHitsFull() * 3,
                "中央=" + r.bodyHitsCenter() + " 整屏=" + r.bodyHitsFull());
        Log.info("==========================================================");
    }

    /**
     * 在截图目录里找<b>本轮</b>写出的 {@code monster-in-view} PNG：
     * 文件名匹配 {@code *monster-in-view*.png}，且修改时间不早于请求时刻（留 5 秒宽限）。
     * 找不到"本轮的"则回退到最新一张（仅用于给出可读的失败信息）。
     */
    private Path newestMonsterPng(Path dir) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        long floor = approachScreenshotRequestedAtMs < 0
                ? Long.MIN_VALUE : approachScreenshotRequestedAtMs - 5_000L;
        Path best = null;
        long bestTs = Long.MIN_VALUE;
        try (Stream<Path> walk = Files.list(dir)) {
            List<Path> candidates = walk
                    .filter(p -> p.getFileName().toString().contains("monster-in-view"))
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .sorted(Comparator.comparingLong(this::mtimeMs))
                    .toList();
            for (Path p : candidates) {
                long ts = mtimeMs(p);
                if (ts >= floor && ts >= bestTs) {
                    best = p;
                    bestTs = ts;
                }
            }
        } catch (IOException e) {
            Log.noteWarning("自测", "列举截图目录失败：" + e);
            return null;
        }
        return best;
    }

    private long mtimeMs(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * M2.1：F4 刷怪落点必须落在<b>玩家看得见的地方</b>（不是半空、不是玩家找不到的远处）。
     *
     * <h2>为什么这条断言必须摆位、且必须在收尾跑</h2>
     * 缺陷 B 有前后两半：前半"怪被放在半空"（渲染层另修）；后半"落点虽接地，却可能在
     * 视野之外或被方块挡住"。M2.1 把 F4 语义定义成"相机水平前方 4–8 格里第一个同时满足
     * 地面 / 落差 / 视锥 / 视线的候选"（见 {@code SkyIslandGame.debugSpawnMonster}）。
     * 本方法直接走<b>产品的 F4 刷怪路径</b>（{@link Host#spawnMonsterInFront()}）对这两半一起下断言。
     *
     * <h2>为什么两条用例的摆位与旧版不同（场景问题，不是判据问题）</h2>
     * 旧版用例 1 把玩家抬到地表上方 10 格、视线朝正上，逼出"射线落空 → 按前向 5 格刷"的
     * 回退分支。新语义<u>不再有那条回退分支</u>：落点恒取"相机水平前方"，且要求
     * |Δy| ≤ {@code SPAWN_MAX_VERTICAL_OFFSET}（3 格）—— 玩家高出地表 10 格时，
     * 正前方任何候选的落差都超限，F4 会（正确地）拒绝生成。因此：
     * <ul>
     *   <li><b>用例 1</b> 改成"玩家站在地表、视线水平朝平地"，让四条硬条件全部成立 →
     *       断言"刷得出来、落在合法落脚点、并且播了生成提示物"；</li>
     *   <li><b>用例 2</b> 保留"玩家高出地表 10 格"的摆位，但其含义变成
     *       "落差超限 → 一个候选都不满足 → 不生成" —— 这正是新语义下该摆位应得的答案。</li>
     * </ul>
     * <b>这是场景摆位问题，不是判据问题</b>：两条用例的布尔判据（生成了 / 没生成）
     * 一个字没改，改的只是"在什么姿势下试"。
     */
    public void verifySpawnGrounding() {
        Log.info("================ M2.1 自测：F4 刷怪落点接地校验 ================");
        Player player = host.player();
        World world = host.world();
        EntityManager entities = host.entities();

        // ---- 用例 1：站在地表、视线水平朝 -Z 刷怪：四条硬条件全过，怪必须落在地面上 ----
        // 摆位：回到出生点 (0.5, 64, 0.5)（地表可站 y=64）、视线水平（pitch 0，yaw 0 → 朝 -Z）。
        // 走一个真实逻辑步让相机视图矩阵与权威位置同步 —— setAngles 只重算 basis、不更新视图矩阵，
        // step 末尾的 refreshCamera 才会，而视锥判据读的正是这份矩阵。
        player.teleport(SPAWN_X, SPAWN_Y, SPAWN_Z);
        player.camera().setAngles(0, 0);
        player.step(world, PlayerIntent.NONE, GameLoop.FIXED_DT);
        int aliveBefore = entities.aliveCount();
        int spawnedBefore = entities.totalSpawned();
        int cuesBefore = host.combatFx().totalSpawnCues();
        MeleeMonster grounded = host.spawnMonsterInFront();
        record("地表水平视线刷怪：怪不为 null（四条硬条件全过）",
                grounded != null,
                grounded == null ? "返回 null（一个候选都没通过硬条件）"
                        : String.format("玩家 y=%.2f，怪 y=%.2f",
                                player.position().y, grounded.position().y));
        boolean standing = grounded != null && player.isStandingSpotValid(world,
                grounded.position().x, grounded.position().y, grounded.position().z);
        record("地表水平视线刷怪：怪站在合法落脚点上（不是半空）",
                standing,
                grounded == null ? "monster=null"
                        : String.format("怪 (%.2f, %.2f, %.2f) isStandingSpotValid=%b",
                                grounded.position().x, grounded.position().y,
                                grounded.position().z, standing));
        record("地表水平视线刷怪：实体计数 +1（确实生成了一只）",
                entities.aliveCount() - aliveBefore == 1
                        && entities.totalSpawned() - spawnedBefore == 1,
                "alive " + aliveBefore + "→" + entities.aliveCount()
                        + "，spawned " + spawnedBefore + "→" + entities.totalSpawned());
        // 生成提示物必须有断言跟着：否则"播没播提示物"没有任何证据，
        // 而这正是"生成成功却看不见"要补的那一半。
        int cueDelta = host.combatFx().totalSpawnCues() - cuesBefore;
        record("刷怪成功时确实播了生成提示物（走 CombatFx 粒子链路）",
                cueDelta == 1, "生成提示物次数增量=" + cueDelta);

        // ---- 用例 2：玩家高出地表 10 格时落差超限 → 一个候选都不满足 → 绝不生成 ----
        // 摆位：虚空坑 x,z ∈ [3,6] 之上 10 格、视线水平朝 -Z。无论正前方是否有地面，
        // 正前方候选的 |Δy| ≈ 10 > 3，debugSpawnMonster 走"不生成"分支。断言实体数不变。
        player.teleport(4.5, 74.0, 4.5);
        player.camera().setAngles(0, 0);
        player.step(world, PlayerIntent.NONE, GameLoop.FIXED_DT);
        int aliveBefore2 = entities.aliveCount();
        int spawnedBefore2 = entities.totalSpawned();
        MeleeMonster floating = host.spawnMonsterInFront();
        record("落差超限时刷怪返回 null（不生成玩家够不着的怪）", floating == null,
                floating == null ? "返回 null"
                        : String.format("竟生成了 (%.2f, %.2f, %.2f) —— 落差 %.1f 格",
                                floating.position().x, floating.position().y,
                                floating.position().z,
                                floating.position().y - player.position().y));
        record("落差超限时实体数不变（没有偷偷塞一只）",
                entities.aliveCount() == aliveBefore2
                        && entities.totalSpawned() == spawnedBefore2,
                "alive " + aliveBefore2 + "→" + entities.aliveCount()
                        + "，spawned " + spawnedBefore2 + "→" + entities.totalSpawned());
        Log.info("==========================================================");
    }

    /**
     * 竖直近战判定 + 咬击几何校验（本轮新增；缺陷 B / 仪器 C）。
     *
     * <h2>为什么必须在收尾阶段单独校验</h2>
     * 主循环的各阶段里，怪与玩家始终在同一水平面（{@code SPAWN_AND_APPROACH} 刻意在进入攻击距离前
     * 收尾），因此"竖直判定"与"咬击几何"这两件事在主循环里<b>从未被触发过</b>。要让冻结 jar 的门禁
     * 真正证明"隔空咬人已修"，必须在这里主动构造两个场景：
     * <ol>
     *   <li><b>玩家高在 7 格之上、怪在正下方</b>（水平 ≈ 0.2 格）：跑满数个攻击冷却窗口 →
     *       生命不得下降、攻击计数不得增加（旧代码会连咬数口 —— 这正是试玩里玩家
     *       "看不见怪却一直掉血"的现场，见证据文档 §4.2）；</li>
     *   <li><b>同层相邻</b>：必须咬中，且记录的几何量自洽（水平 ≤ {@code ATTACK_RANGE}、竖直 ≈ 0）。
     *       日志里那行咬击几何必须有断言跟着 —— 一行没人断言的日志不是证据。</li>
     * </ol>
     * 两条都直接走 {@code EntityManager.tick} 这个产品入口，不另造一套 AI 驱动。
     */
    public void verifyMeleeVerticalGate() {
        Log.info("================ M2.1 自测：近战竖直判定 + 咬击几何 ================");
        World world = host.world();
        Player player = host.player();
        EntityManager entities = host.entities();

        // ---- 用例 1：玩家高在 7 格之上（怪在正下方，水平 ≈ 0.2 格）----
        entities.clear();
        entities.spawnMeleeMonster(SPAWN_X + 0.2, SPAWN_Y, SPAWN_Z);
        MeleeMonster below = (MeleeMonster) entities.all().get(0);
        player.teleport(SPAWN_X, SPAWN_Y + 7.0, SPAWN_Z);
        int healthBefore = player.health();
        // 200 步 ≈ 3.3 s ≈ 3 个多攻击冷却窗口：旧代码足以连咬 3 口（12 点）
        for (int i = 0; i < 200; i++) {
            entities.tick(world, player, GameLoop.FIXED_DT);
        }
        record("玩家高出 7 格：生命未被咬伤（旧代码会掉血）",
                player.health() == healthBefore,
                "health " + healthBefore + "→" + player.health() + "（期望不变）");
        record("玩家高出 7 格：攻击计数为 0（旧代码此处 > 0）",
                below.attackCount() == 0,
                "attackCount=" + below.attackCount());

        // ---- 用例 2：同层相邻必须咬中，且几何量自洽 ----
        entities.clear();
        player.teleport(SPAWN_X, SPAWN_Y, SPAWN_Z);
        MeleeMonster biter = entities.spawnMeleeMonster(SPAWN_X + 1.2, SPAWN_Y, SPAWN_Z);
        int healthBefore2 = player.health();
        entities.tick(world, player, GameLoop.FIXED_DT);
        record("同层相邻：玩家被咬中（生命下降 4 点）",
                player.health() == healthBefore2 - MeleeMonster.ATTACK_DAMAGE,
                "health " + healthBefore2 + "→" + player.health());
        record("同层相邻：咬击计数 = 1",
                biter.attackCount() == 1,
                "attackCount=" + biter.attackCount());
        double biteH = biter.lastBiteHorizontalDistance();
        double biteV = biter.lastBiteVerticalOffset();
        record("同层咬击：记录的水平距离落在 ATTACK_RANGE 之内（几何仪器可断言）",
                !Double.isNaN(biteH) && biteH <= MeleeMonster.ATTACK_RANGE,
                "水平 = " + biteH + " 格（ATTACK_RANGE = " + MeleeMonster.ATTACK_RANGE + "）");
        record("同层咬击：记录的竖直偏移 ≈ 0",
                !Double.isNaN(biteV) && Math.abs(biteV) < 1e-6,
                "竖直 Δy = " + biteV + " 格");
        Log.info("==========================================================");
    }

    // ============================================================ 记录与摘要

    private void record(String name, boolean passed, String detail) {
        String line = (passed ? "PASS" : "FAIL") + " · " + name + " — " + detail;
        results.add(line);
        if (passed) {
            Log.info("[自测] %s", line);
        } else {
            failures.add(name);
            Log.error("[自测] %s", line);
        }
    }

    /** 结构化摘要（写入日志与 M2 证据文档）。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("自测阶段数      = ").append(Stage.values().length).append('\n');
        sb.append("断言总数        = ").append(results.size()).append('\n');
        sb.append("失败数          = ").append(failures.size()).append('\n');
        sb.append("断言范围        = ").append(scope()).append('\n');
        sb.append("整体结果        = ").append(allPassed() ? "PASS" : "FAIL").append('\n');
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

    /** 供 HUD/日志：当前阶段标签。 */
    public String currentStageLabel() {
        return finished ? "完成" : Stage.values()[stageIndex].label;
    }
}

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
import com.skyisland.item.GunSpec;
import com.skyisland.item.Item;
import com.skyisland.item.ItemRegistry;
import com.skyisland.player.Inventory;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

        /**
         * M2.1：上一轮 {@code debugSpawnMonster} 里四个硬条件的<b>拒绝计数</b>
         * （索引 0=地面 1=落差 2=视锥 3=视线）。
         *
         * <p>由产品刷怪路径自己累积，自测只读 —— 用来证明"失败是哪一条判据造成的"，
         * 而不是"碰巧没刷出来"。
         */
        int[] lastSpawnRejectCounts();

        /**
         * M2.1：上一轮 {@code debugSpawnMonster} 里视锥判据是否<b>真的参与</b>判定
         * （无渲染器时短路 → false，不伪装成"通过"）。
         */
        boolean lastFrustumEvaluated();

        /**
         * M2.1：切换 F3 调试 overlay（与玩家按 F3 走<b>同一条产品方法</b>），返回切换后的状态。
         */
        boolean toggleDebugOverlay();

        /** M2.1：{@code EntityRenderer} 当前的碰撞箱调试开关（用于断言它跟随 F3）。 */
        boolean debugHitboxEnabled();

        /**
         * M2.1：按给定 overlay 开关真跑一遍 F3 实体叠层（走产品的产出闸门），返回它产出的行。
         *
         * <p>自测不自造行格式：返回的就是 {@code HudModel.extraDebugLines} 里那一批。
         */
        java.util.List<String> debugEntityOverlayLines(boolean overlayOn);

        /** 走产品的 F6「调试补给」路径（{@code SkyIslandGame.grantStartingGear}）。 */
        void grantDebugSupply();

        /**
         * M3 Story 10：在快捷栏里找到第二把枪（SMG）并切到手上，
         * 用于"连续按住 30 秒"的稳定性阶段。
         *
         * <p><b>为什么它只"选中"、不"发放"：</b>Story 9 时 SMG 不在开局装备里，本阶段
         * 只能自己发一把、末了再删一把（{@code releaseSmgAfterSustain}）。
         * Story 10 按主理人裁决把 SMG 放进了开局装备（v2 §19-2「SMG 可正常获得」），
         * 于是"发一把再删一把"这套夹具不但多余，还会把玩家本来就有的那把一起删掉。
         * 现在本阶段的<b>唯一前提</b>就是「开局装备给了 SMG」—— 这条前提不成立时，
         * 它应该变成一条会红的断言，而不是被静默补发掩盖。
         *
         * @return SMG 的 runtimeId；注册表里没有 SMG、或快捷栏里找不到它时返回 {@code -1}
         */
        int grantSmgForSustain();

        /**
         * M3 Story 10：SMG 稳定性阶段结束后，把选中的快捷栏槽位恢复为阶段开始前那一格。
         *
         * <p><b>为什么现在不动背包内容：</b>本阶段<b>在存档阶段之后</b>运行，
         * 而 SMG 作为开局装备本来就应该一直留在背包里、并被写进退出存档 ——
         * 这与存档阶段快照是一致的，逐格校验不会因为多一把枪而红。
         * 反过来，若在这里把 SMG 删掉，退出存档就会比快照少一把枪，
         * 那才是<b>夹具污染</b>（把测试自己的副作用读成产品缺陷）。
         *
         * <p>真正需要还原的只有选中槽位：存档快照记录的是阶段前的那一格。
         *
         * @param restoreSelectedSlot 阶段开始前选中的快捷栏相对槽位（{@code 0..8}）
         */
        void releaseSmgAfterSustain(int restoreSelectedSlot);

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
        GEAR_CHECK("开局装备（手枪 + 冲锋枪 + 2 个满弹匣）"),
        MINE_BLOCKED_WHILE_HOLDING_GUN("持枪时左键是开火不是挖掘"),
        DRY_FIRE("空弹匣空枪"),
        RELOAD_FULL("完整换弹（1.2 秒）"),
        RELOAD_WHILE_WALKING("边走边换弹（移动不打断·弹药不提前转移）"),
        AIM("瞄准（FOV 45/70、移动速度 60%）"),
        SPAWN_AND_APPROACH("刷怪与追击"),
        SHOOT_KILL("逐发击中并致死（20→12→4→0）"),
        WALL_BLOCKS_BULLET("最近合法碰撞（子弹不穿墙）"),
        DAMAGE_FALLOFF("超出有效射程的距离衰减"),
        BREAK_PARTICLES("破坏粒子 8–12 个"),
        DEATH_AND_RESPAWN("死亡与 3 秒重生"),
        SAVE_RELOAD_ROUNDTRIP("存档（读档校验在收尾阶段执行）"),
        SMG_SUSTAINED("SMG 连续按住 30 秒（稳定性 / 无异常增长）"),
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
             * RELOAD_WHILE_WALKING：预算 = 打空 + 按键 + 全程按住 W 走满一次换弹。
             *   · 打空 12 发：射速 4 发/秒 → 发间隔 0.25 s = 15 步；12 发跨越 11 个间隔 = 165 步。
             *     浮点累减会让某个间隔退化为 16 步 → 上界 11×16 = 176 步（首发于第 0 步）。
             *   · 按 R：1 步。
             *   · 换弹全程按住 W：1.2 s = 72 步，浮点上界 73 步；再加 1 步把"完成事件"观测到。
             *     ★ 这 73 步必须真的走起来：位移就是"边走边换"这条规格的物证（见 checkReloadWhileWalking）。
             *   合计上界 = 176 + 1 + 74 = 251 步；取 360 步（余 109 步，留足位移与观测窗口）。
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
            /*
             * SMG_SUSTAINED（Story 9 / v2 §15）：连续按住 SMG 30 秒的稳定性。
             *
             *   · 30 秒 = 60 TPS × 30 = 1800 步。这是 v2 §15 明文要求的时长，不是"够用就好"。
             *   · 阶段内一路 attackHeld=true（SMG 是 AUTO，电平驱动）；弹匣 24 发打空后
             *     本阶段会在同一步内按 R 开始换弹（换弹 1.5 s = 90 步），走完继续连发。
             *   · 本阶段的目的是"无异常增长"：曳光 ≤ MAX_TRACERS、粒子 ≤ MAX_PARTICLES、
             *     干枪/开火计数与射速一致、不 OOM、不抛异常 —— 这些都由 checkSmgSustained()
             *     在阶段末逐条断言。frame time 的 p95/p99 由独立的性能对照运行取证（见文档）。
             *   取 1800 步（无额外余量：这就是被测规格本身）。
             */
            1800,
             // DONE：收尾。
            1
    };

    // ---- 与世界一致性有关的常量（避免为读一个常量而暴露整个类）----

    /**
     * SMG 稳定性阶段的总步数：30 秒 × 60 TPS = 1800（v2 §15 明文时长）。
     *
     * <p>与 {@link #STAGE_BUDGET} 里 SMG_SUSTAINED 的预算保持同一个数：
     * 意图脚本要用它算"收尾的松开扳机窗口"从哪一步开始。
     */
    private static final int SMG_STAGE_STEPS = 1800;

    /**
     * SMG 阶段末尾"松开扳机"的步数（不产生新的击发，只让后坐力回落）。
     *
     * <p>取值 30 步 = 0.5 s。推导：后坐力上限 {@code MAX_RECOIL_PITCH_DEG = 1.8°}，
     * 回落速度 {@code RECOIL_RECOVER_DEG_PER_SEC = 5.0°/s} → 从满值回落需要 1.8/5 = 0.36 s = 22 步。
     * 取 30 步留 8 步余量。这 30 步内 SMG 仍然"在手上、在按住之外"，只是松开了扳机。
     */
    private static final int SMG_RELEASE_SETTLE_STEPS = 30;

    /** 世界出生点（与 {@link TestWorldGenerator} 一致）。 */
    private static final double SPAWN_X = TestWorldGenerator.spawnX();
    private static final double SPAWN_Y = TestWorldGenerator.spawnY();
    private static final double SPAWN_Z = TestWorldGenerator.spawnZ();

    /**
     * 边走边换阶段：换弹期间至少要走多少步，才足以证明"全程都在走"。
     *
     * <p>换弹 1.2 s = 72 步（浮点上界 73）。本阶段在按 R 之后<b>每一步</b>都按 W，
     * 因此实测值应落在 72–73。取 70 而不是 72 作阈值，是为了容忍
     * "完成事件要到下一步才被观测到"这一确定性的时序差（见 {@code intentFor} 内注释）；
     * 而任何"只走了一两步换弹就被取消"的回归会立刻掉到阈值以下，因此它仍然有鉴别力。
     */
    private static final int MIN_WALK_STEPS_WHILE_RELOADING = 70;

    /**
     * 边走边换阶段：换弹期间至少要走出多少格，才足以证明"人是真在动"。
     *
     * <p>移速 4.317 格/秒（PRD 5.4.2 基础移速），1.2 s 理论 ≈ 5.2 格；
     * 扣掉加速段（v(t) = target·(1−e^(−18t))，前 0.2 s 才爬到目标速度）的亏欠，
     * 实测约 4.9 格。阈值取 3.0 格：它要能区分"真的走起来了"与
     * "intent 写了 W 但人被挡在原地"，又不至于被加速段的那零点几格判红。
     */
    private static final double MIN_WALK_DISTANCE_WHILE_RELOADING = 3.0;

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
     * <p>做成静态工厂而不是让 {@code SkyIslandGame} 自己写一个把接口方法逐个转发的类：
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

    /**
     * 边走边换阶段的分步推进相位：0=打空弹匣，1=按 R，2=按住 W 走完换弹，3=收尾。
     */
    private int walkPhase;
    /**
     * 换弹期间"确实在换弹 且 确实在按 W"的逻辑步数（逐步累加）。
     *
     * <p>它是"边走边换"最直接的一条物证：旧口径（移动即取消）下这个数只能停在 1 步 ——
     * 因为第二步起 {@code isReloading()} 已经是 false，累加器不再增长。
     */
    private int walkStepsDuringReload;
    /** 按 R 那一步的脚位（用来量换弹期间的总位移）。 */
    private double walkStartX;
    private double walkStartZ;
    /** 换弹完成那一刻的累计水平位移（格）；由相位 2→3 时采样。 */
    private double walkDistanceDuringReload = -1;
    private int magAfterEmptying = -1;
    private int reserveAfterEmptying = -1;
    /** 换弹走完那一刻的弹匣读数（期望 = 满匣）。 */
    private int magAtCompletion = -1;

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
     * M2.1 像素证据：请求 {@code monster-in-view} 截图时的墙钟时刻（毫秒）；
     * <b>本轮未发起请求时恒为 {@code -1}</b>。
     *
     * <p>截图目录是<b>共享</b>的（多次运行会累积同名文件），因此收尾做像素校验时
     * 不能只挑"最新的 monster-in-view PNG"——必须挑<b>本轮之后新产生的</b>那一张。
     *
     * <p>本字段既作 mtime 下界，也是把判据绑定到"本轮"的<b>硬门</b>：它只在跑到
     * {@code SPAWN_AND_APPROACH} 第 30 步时才会被赋值。若本轮没跑到那一步，它就一直是
     * {@code -1} —— 此时目录里<b>任何</b> monster-in-view PNG 都必然来自别的轮次，
     * 不存在"本轮的截图"。见 {@link #screenshotRequestedThisRun()} 与
     * {@link #newestMonsterPng(Path)}。
     */
    private long approachScreenshotRequestedAtMs = -1;

    /**
     * 本轮自测的起始墙钟时刻（毫秒）：在自测对象构造时即固定。
     *
     * <p><b>它已不再承担"兜底下界"的角色</b>：中间版本曾用它兜底（请求时刻未赋值时
     * 退化为 {@code runStartMillis} 作下界），但那条退路本身就是一种假绿可能 ——
     * 它给绿灯留了一条"读到的时间恰好落在容差里"的解释。现在改为硬门
     * （{@link #screenshotRequestedThisRun()}），本字段只用于在失败消息里给 mtime
     * 一个本轮参照。
     */
    private final long runStartMillis = System.currentTimeMillis();

    /**
     * 截图"新近度"容差（毫秒）：<b>仅</b>用于吸收"请求时刻与落盘 mtime 落在同一秒
     * （文件系统 mtime 秒级截断）"的抖动。它<b>不再承担"兜底下界"的角色</b>，
     * 且远小于两轮之间的间隔，因此不会让"上一轮的截图"冒充"本轮的"。当前取 2 秒。
     */
    private static final long SCREENSHOT_FRESHNESS_TOLERANCE_MS = 2_000L;

    /**
     * 本轮自测<b>起始时</b>截图目录里已存在的 {@code monster-in-view} PNG <b>文件名集合</b>。
     *
     * <p>这是与"时间容差"<b>互相独立的第二道门</b>：
     * <ul>
     *   <li><b>时间门</b>（{@link #thisRunScreenshotFloorMs()}）按 mtime 判断"够不够新"，
     *       为吸收"请求时刻与落盘 mtime 落在同一秒"的抖动，必须留 2 秒容差；
     *   <li><b>文件名门</b>（本集合）按<b>身份</b>判断"是不是起始时就已存在的那张" ——
     *       文件名带毫秒时间戳、每轮唯一，因此"名字在快照里"等价于"这张图早于本轮就已存在"，
     *       与 mtime 精度完全无关，取不到巧。
     * </ul>
     * 两道门各堵一类漏洞：时间门堵"时间太旧"，文件名门堵"时间恰好落在容差里"。命中若落在
     * 集合内，与 {@code png == null} 同样处理。
     *
     * <p>快照在构造时即固定，只增不减。
     */
    private final Set<String> preexistingMonsterShotNames = snapshotPreexistingMonsterShots();

    /** 见 {@link #preexistingMonsterShotNames}；目录不存在或无匹配文件时返回空集合。 */
    private static Set<String> snapshotPreexistingMonsterShots() {
        Path dir = Path.of(System.getProperty("skyisland.screenshotDir", "screenshots"));
        Set<String> names = new HashSet<>();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (Stream<Path> walk = Files.list(dir)) {
            walk.filter(p -> p.getFileName().toString().contains("monster-in-view"))
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .forEach(p -> names.add(p.getFileName().toString()));
        } catch (IOException e) {
            Log.noteWarning("自测", "快照 monster-in-view 文件名失败（按空集合处理）：" + e);
        }
        return names;
    }

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

    // ---- M3 Story 9：SMG 连续按住 30 秒稳定性阶段的观测值 ----

    /** SMG 阶段开始时的开火计数（阶段末据此算增量）。 */
    private int smgStartShots;
    /** SMG 阶段开始时的干枪计数。 */
    private int smgStartDryFires;
    /** SMG 阶段开始时的累计曳光生成数（自测证据，不受 clear() 影响）。 */
    private long smgStartTracersGenerated;
    /** SMG 阶段开始时的累计枪口闪光数。 */
    private int smgStartMuzzleFlashes;
    /** SMG 阶段内观测到的曳光存活峰值（应 ≤ {@code CombatFxModel.MAX_TRACERS}）。 */
    private int smgPeakTracers;
    /** SMG 阶段内观测到的粒子存活峰值（应 ≤ {@code CombatFxModel.MAX_PARTICLES}）。 */
    private int smgPeakParticles;
    /** SMG 阶段内观测到的枪口闪光存活峰值（应 ≤ {@code CombatFxModel.MAX_FLASHES}）。 */
    private int smgPeakFlashes;
    /** SMG 阶段内本阶段按 R 的次数（用于核对"打空→换弹"节奏）。 */
    private int smgReloadRequests;
    /** SMG 阶段开始时的后备弹药数（PROTOTYPE 口径下不应被扣减）。 */
    private int smgStartReserve;
    /** SMG 阶段结束时读到的 SMG runtimeId（-1 表示注册表里没有 SMG）。 */
    private int smgRuntimeId = -1;
    /** SMG 阶段内是否观测到过一次"弹匣打空"（应当出现，才说明真的连发到空）。 */
    private boolean smgObservedEmptyMagazine;
    /** SMG 阶段开始时的世界时刻（收尾用，仅记录）。 */
    private int smgStageSteps;
    /** SMG 阶段开始前选中的快捷栏相对槽位（阶段末还原用，见 {@code releaseSmgAfterSustain}）。 */
    private int smgPrevSelectedSlot = 0;

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
            case RELOAD_FULL, RELOAD_WHILE_WALKING -> observeReloadStarted(player);
            case SMG_SUSTAINED -> observeSmgSustained(player);
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
     * 而当时的 RELOAD_INTERRUPTED 稳定失败（下一步已经在按 W，那一步把换弹取消了）——
     * 该阶段在 M2.2 已改写成"边走边换"（走路不再取消换弹），但这条时序判据不变。
     */
    private void observeReloadStarted(Player player) {
        if (reloadRequestedAtStep >= 0 && currentStep == reloadRequestedAtStep) {
            GunState gun = host.combat().existingGun(player);
            reloadingObservedAfterRequest = gun != null && gun.isReloading();
        }
    }

    /**
     * M3 Story 9：SMG 连续按住 30 秒阶段里的逐步观测。
     *
     * <p><b>只采样峰值与"是否见过空弹匣"，不做断言。</b>断言集中在阶段末的
     * {@link #checkSmgSustained()}：本方法每个逻辑步都会跑 1800 次，任何在这里
     * {@code record(...)} 的写法都会把断言数炸成上千条（并让摘要不可读）。
     *
     * <p>峰值采样是"无异常增长"的关键物证：若高射速下粒子/曳光/闪光某一环只进不出，
     * 峰值会在阶段内顶到容量上限。因此峰值本身就是一个会变红的量 ——
     * 正常节奏下曳光寿命 0.05 s（3 帧）、10 发/秒（每 6 步一发）→ 同时存活 ≈ 1 条。
     */
    private void observeSmgSustained(Player player) {
        smgStageSteps++;
        CombatFxModel fx = host.combatFx();
        if (fx != null) {
            smgPeakTracers = Math.max(smgPeakTracers, fx.tracerCount());
            smgPeakParticles = Math.max(smgPeakParticles, fx.particleCount());
            smgPeakFlashes = Math.max(smgPeakFlashes, fx.flashCount());
        }
        GunState gun = host.combat().existingGun(player);
        if (gun != null && gun.magazineAmmo() == 0) {
            smgObservedEmptyMagazine = true;
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
        // reloadingObservedAfterRequest 必须一起清：它被 RELOAD_FULL 与 RELOAD_WHILE_WALKING
        // 共用，不清的话第二个阶段会读到第一个阶段留下的 true 而永远"通过"。
        reloadRequestedAtStep = -1;
        reloadingObservedAfterRequest = false;

        Stage stage = Stage.values()[stageIndex];

        // M3 Story 9：SMG 阶段的起始快照。放在这里（每个阶段开始）而不是只给 SMG 阶段，
        // 是为了让"阶段内增量"的口径与其它阶段一致，读的人不用去记哪个阶段有特殊初始化。
        if (stage == Stage.SMG_SUSTAINED) {
            smgStartShots = host.combat().shotsFired();
            smgStartDryFires = host.combat().dryFires();
            smgStartTracersGenerated = host.combatFx() == null ? 0 : host.combatFx().totalTracers();
            smgStartMuzzleFlashes = host.combatFx() == null ? 0 : host.combatFx().totalMuzzleFlashes();
            smgPeakTracers = 0;
            smgPeakParticles = 0;
            smgPeakFlashes = 0;
            smgReloadRequests = 0;
            smgObservedEmptyMagazine = false;
            smgStageSteps = 0;
            // 先记下阶段前的选中槽位，再发 SMG —— 阶段末要把选中槽位还原回去，
            // 否则退出自动存档里"选中的是 SMG"会与存档阶段的快照不一致（夹具污染）。
            smgPrevSelectedSlot = player.inventory().selectedSlot();
            smgRuntimeId = host.grantSmgForSustain();
            smgStartReserve = player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID);
            Log.info("[自测] SMG 阶段：runtimeId=%d，手持=%s，后备弹药=%d",
                    smgRuntimeId, player.inventory().selectedStack().item().id(), smgStartReserve);
        }

        Log.info("[自测] ▶ 阶段 %d/%d %s（预算 %d 步）",
                stageIndex + 1, Stage.values().length, stage.label, STAGE_BUDGET[stageIndex]);
    }

    /**
     * 快捷栏里第一个"不是枪"的<b>相对</b>槽位（0..8）。挖掘阶段必须先声明自己手里是什么。
     *
     * <p><b>★ 返回的是快捷栏相对索引，不是绝对索引。</b>它的唯一消费方是
     * {@link PlayerIntent#selectSlot(int)} → {@code Inventory#selectSlot(int)}，
     * 而后者收的就是 0..8。M2.2 把 {@code Inventory.size()} 从 9 改成 36 之后，
     * 这里一度是"扫整个 36 格数组、返回绝对索引"，于是主背包的空格（绝对 0）
     * 被判成"第一个不是枪的槽位"，{@code selectSlot(0)} 实际选中了快捷栏第 1 格
     * —— 也就是那把手枪。症状是挖掘阶段整段失败（持枪左键 = 开火，不是挖掘），
     * 而失败现场指向"挖掘没生效"，真正的错在槽位口径上。
     *
     * <p>因此本方法与 {@link #firstBlockSlot} 都只扫快捷栏（{@code hotbarSlot(i)}），
     * 并返回 0..8。这是"下标自带单位"这件事必须被写下来的地方。
     */
    private int firstNonGunSlot(Player player) {
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            if (!player.inventory().hotbarSlot(i).item().isGun()) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 快捷栏里第一个方块物品的<b>相对</b>槽位（0..8）；没有则返回 −1。
     *
     * <p>写成"扫描内容"而不是硬编码槽号：开局装备的格子布局与后续挖到的物品都会变，
     * 硬编码的失效方式是"挖掘阶段失败"，而真正的原因在别处。
     *
     * <p>返回相对索引的理由同 {@link #firstNonGunSlot}。注意第 1670 行的
     * {@code firstBlockSlot(player) >= 0} 只把它当"有没有"用，与口径无关。
     */
    private int firstBlockSlot(Player player) {
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            if (player.inventory().hotbarSlot(i).isBlockItem()) {
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
                yield singleShotIntent(player);
            }

            case DRY_FIRE -> singleShotIntent(player);

            case RELOAD_FULL -> {
                if (currentStep == 0) {
                    // 视线回到水平：本阶段的射击（若有）不应当打在脚下的方块上。
                    player.camera().setAngles(0, 0);
                    reloadRequestedAtStep = currentStep;
                    yield PlayerIntent.combat(0f, 0f, false, 0, 0, false, false, true);
                }
                yield PlayerIntent.NONE;
            }

            case RELOAD_WHILE_WALKING -> {
                GunState gun = host.combat().existingGun(player);
                if (walkPhase == 0) {
                    // ① 按住左键打空 12 发（射速由 GunState 节流；SINGLE 走按下沿，见 singleShotIntent）
                    if (gun != null && gun.magazineAmmo() > 0) {
                        yield singleShotIntent(player);
                    }
                    walkPhase = 1;
                    magAfterEmptying = gun == null ? -1 : gun.magazineAmmo();
                    reserveAfterEmptying = player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID);
                    yield PlayerIntent.NONE;
                }
                if (walkPhase == 1) {
                    // ② 按 R 开始换弹，并记下起点脚位（后面用来量"边走边换"的位移）
                    walkPhase = 2;
                    reloadRequestedAtStep = currentStep;
                    walkStartX = player.position().x;
                    walkStartZ = player.position().z;
                    yield PlayerIntent.combat(0f, 0f, false, 0, 0, false, false, true);
                }
                if (walkPhase == 2) {
                    // 换弹已经走完（事件在上一步/本步的 combat.step 里到达）→ 收尾采样。
                    // 判据只看"完成事件有没有到"，不依赖"玩家此刻是否还在走"：
                    // 本阶段从按 R 之后一路按着 W，因此完成必然发生在行走中。
                    if (eventReloadCompletions - stageStartEventReloadCompletions >= 1) {
                        walkPhase = 3;
                        magAtCompletion = gun == null ? -1 : gun.magazineAmmo();
                        walkDistanceDuringReload = Math.hypot(player.position().x - walkStartX,
                                player.position().z - walkStartZ);
                        yield PlayerIntent.NONE;
                    }
                    // ③ 边走边换：换弹中依然按住 W（PRD 5.4.3 M2.2 修订：移动不打断换弹）。
                    //    计数条件把"换弹中"与"在按 W"同时钉住 —— 只有两者同时成立才累加，
                    //    因此这个数就是"边走边换"持续了多少步的直接物证。
                    if (gun != null && gun.isReloading()) {
                        walkStepsDuringReload++;
                    }
                    yield PlayerIntent.combat(1f, 0f, false, 0, 0, false, false, false);
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
                yield singleShotIntent(player);
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
                     * 副作用如实登记：F6 走的是 grantStartingGear，它会把**第二套**开局装备
                     * 放进后面的空槽（枪的堆叠上限是 1），因此存档里的快捷栏会出现
                     * 手枪 ×2 + 冲锋枪 ×2 —— 这是 F6 的既有行为，不是本自测的产物，
                     * 也不是"多跑了一次补给"造成的污染。（Story 10 起开局装备含冲锋枪，
                     * 所以这里翻倍的是两把枪而不是一把。）
                     */
                    host.grantDebugSupply();
                }
                yield PlayerIntent.NONE;
            }

            case SMG_SUSTAINED -> intentForSmgSustained(player);
        };
    }

    /**
     * M3 Story 9：SMG 连续按住 30 秒的输入脚本。
     *
     * <p><b>为什么"按住"之外还要按 R：</b>SMG 是 AUTO（电平驱动，{@code attackHeld=true}），
     * 射速 10 发/秒、弹匣 24 发 —— 弹匣 2.4 秒就见底。若只按住左键不换弹，30 秒里
     * 绝大部分时间是"打空后空扣扳机"，那样测到的是空枪路径，不是连发路径。
     * 因此在"弹匣已空且未在换弹"的那一步按一次 R，让连发-换弹-再连发的循环真的跑起来。
     *
     * <p>判据落在 {@code gun.magazineAmmo() == 0 && !gun.isReloading()}：
     * 不在"正在换弹"时重复按 R（{@code tryStartReload} 本来也会返回 ALREADY_RELOADING，
     * 但在这里挡住可以让 {@code smgReloadRequests} 如实反映"真的发起了几次换弹"）。
     */
    private PlayerIntent intentForSmgSustained(Player player) {
        GunState gun = host.combat().existingGun(player);
        // 收尾的"松开扳机"窗口：最后 SMG_RELEASE_SETTLE_STEPS 步不再开火，
        // 让后坐力按 Camera.RECOIL_RECOVER_DEG_PER_SEC 自然回落到 0。
        // 为什么必须留这个窗口：DONE 阶段有一条断言"后坐力已精确回落到 0"，
        // 它原本假定"收尾时距最后一次开火已隔了若干阶段"。SMG 阶段是最后一个开火阶段，
        // 若一直扣着扳机到第 1800 步，后坐力就会停在 1.3° 让那条断言变红 ——
        // 那是被测行为（松开扳机后自然回落）没被跑完，而不是产品缺陷。
        if (currentStep >= SMG_STAGE_STEPS - SMG_RELEASE_SETTLE_STEPS) {
            return PlayerIntent.NONE;
        }
        boolean needReload = gun != null && gun.magazineAmmo() == 0 && !gun.isReloading();
        if (needReload) {
            smgReloadRequests++;
        }
        // 换弹期间仍然按住左键（AUTO 语义：按住即持续尝试；换弹中 tryFire 返回 RELOADING，
        // 不消耗弹药、不产生曳光）。这正好也覆盖了"换弹中按住左键"这条交互。
        return PlayerIntent.combat(0f, 0f, false, 0, 0, true, false, needReload);
    }

    /**
     * M3 Story 9：手枪「按住左键开火」的输入脚本。
     *
     * <p><b>为什么这里不能直接写 {@code attackHeld=true} 了事：</b>
     * v2 §7.1 把 {@code FireMode.SINGLE}（手枪）的击发依据从<b>电平</b>
     * {@link PlayerIntent#attackHeld()} 改成了<b>按下沿</b>
     * {@link PlayerIntent#attackPressed()}（{@code CombatController.fireRequested}：
     * {@code case SINGLE -> intent.attackPressed()}）。按下沿是<b>帧级</b>量，
     * 走 {@code FrameInputQuantities} 的 latch 通道；而自测<b>绕过了</b>
     * {@code frameQuantities.apply}（{@code SkyIslandGame} 在 {@code combatSelfTest != null}
     * 时直接使用脚本意图），因此自测必须自己把「这一帧点了左键」这件事显式表达出来 ——
     * 否则每步都送 {@code attackPressed=false}，手枪永远不开火（Story 7 之后暴露的正是这条）。
     *
     * <p><b>为什么用「武器可击发时才给按下沿」而不是「每步都给」：</b>
     * 半自动的真实语义是「一次点击 = 一发」。玩家按住左键不放时，硬件只会产生<b>一个</b>
     * 按下沿，长按不会连发（这正是 §7.1 要的）。但本自测要复现的旧口径是
     * 「按住左键 → 按射速持续开火」（4 发/秒的手枪在 1.5 秒刺激窗口里要出好几发），
     * 两者在<b>观测层面等价</b>的实现是：只要枪「此刻可以击发」（不在换弹、射速冷却已过），
     * 就在这一步给出一个按下沿。这样击发节奏仍然被 {@code GunState.fireCooldown} 节流，
     * 与旧口径逐发一致，而每一次击发都确实携带了合法的按下沿。
     *
     * <p><b>为什么可以同一步里既 {@code attackHeld=true} 又 {@code attackPressed=true}：</b>
     * 二者是同一物理键（左键）的两个语义切片、<b>并存</b>而非二选一
     * （见 {@link PlayerIntent#attackPressed()} 的类注释）。手枪只读按下沿，
     * 电平字段对它是无害的冗余；一旦脚本被复用到 SMG 阶段，电平字段仍然表达
     * 「此刻按着左键」。因此本方法对手枪与 SMG 是安全统一的。
     *
     * @param player 当前玩家（用于取本枪 {@link GunState}）
     * @return 携带合法按下沿（仅当本步可击发）的战斗意图
     */
    private PlayerIntent singleShotIntent(Player player) {
        GunState gun = host.combat().existingGun(player);
        // 「可击发」= 有枪、不在换弹、射速冷却已过。空弹匣也照样给按下沿 ——
        // 那样会走到 NO_AMMO 分支、产生 dryFires/onDryFire，正是 DRY_FIRE /
        // MINE_BLOCKED 阶段要观测的「左键进入开火路径」物证。
        boolean ready = gun != null && !gun.isReloading() && gun.fireCooldownRemaining() <= 1e-9;
        return PlayerIntent.combat(0f, 0f, false, 0, 0, true, false, false)
                .withAttackPressed(ready);
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
            return singleShotIntent(player);
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
            return singleShotIntent(player);
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
            case RELOAD_WHILE_WALKING -> checkReloadWhileWalking();
            case AIM -> checkAim();
            case SPAWN_AND_APPROACH -> checkSpawnAndApproach();
            case SHOOT_KILL -> checkShootKill();
            case WALL_BLOCKS_BULLET -> checkWall();
            case DAMAGE_FALLOFF -> checkFalloff();
            case BREAK_PARTICLES -> checkBreak();
            case DEATH_AND_RESPAWN -> checkDeath();
            case SAVE_RELOAD_ROUNDTRIP -> checkSave();
            case SMG_SUSTAINED -> {
                checkSmgSustained();
                // 断言跑完再还原阶段夹具：移除 SMG、恢复原选中槽位。
                // 必须先 check 后 release —— 否则 checkSmgSustained() 就得在"SMG 已被拿走"
                // 的状态下读运行时统计，语义立刻变味。
                host.releaseSmgAfterSustain(smgPrevSelectedSlot);
            }
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
        // M3 Story 10：开局装备是"手枪 → 手枪弹 → 冲锋枪"，经 add() 落在快捷栏相对槽 0/1/2。
        // 必须用 hotbarSlot 读，不能读 slot(0)/slot(1)/slot(2) —— 后者现在是主背包，会读空。
        ItemStack slot0 = player.inventory().hotbarSlot(0);
        ItemStack slot1 = player.inventory().hotbarSlot(1);
        ItemStack slot2 = player.inventory().hotbarSlot(2);
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
        // M3 Story 10：v2 §19 通过标准第 2 条写的是「SMG 可正常**获得**/持有/显示/射击/
        // 换弹/存档」。此前 SMG 只存在于 ItemRegistry 内部、玩家拿不到，"获得"这一项
        // 在事实上不成立。主理人裁决后 SMG 进了开局装备，这条断言就是它的物证 ——
        // 哪天开局装备又只发手枪，这里立刻红，而不是等到真人试玩才发现"没有第二把枪"。
        record("快捷栏第 3 格是冲锋枪（v2 §19-2：SMG 可正常获得）",
                ItemRegistry.SMG_ID.equals(slot2.item().id()),
                "slot2=" + slot2 + " id=" + slot2.item().id());
        record("快捷栏第 3 格冲锋枪数量 = 1", slot2.count() == 1, "count=" + slot2.count());
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

    /**
     * 边走边换阶段断言（M2.2 修订：移动<b>不</b>打断换弹）。
     *
     * <h2>这一阶段为什么从"被打断"改成了"边走边换"</h2>
     * v0.3.2 原本规定「移动打断换弹 = 取消换弹」，本阶段当时叫
     * {@code RELOAD_INTERRUPTED}，做法是"按 R → 按 W → 断言换弹被取消、弹药没转移"。
     * M2.2 收尾时用户改口径为「移动不打断换弹」：按 R 之后照常行走，1.2 秒走满照样上膛。
     * 因此本阶段改为断言<u>相反的</u>性质，而相位机保留"打空 → 按 R → 一路按 W"的形状 ——
     * 刺激序列没变，变的是期望（旧期望=被取消，新期望=走完全程）。
     *
     * <h2>判据的可鉴别性（为什么不只是"换弹完成了"）</h2>
     * <ol>
     *   <li>{@code walkStepsDuringReload}：只有"换弹中<b>且</b>在按 W"的步才累加。
     *       旧口径（移动即取消）下这个数会停在 1 —— 第二步起 {@code isReloading()} 已是 false，
     *       累加器不再增长。因此"≥ 70"直接否证旧行为；</li>
     *   <li>{@code completions == 1}：旧口径下按住 W 会把换弹在第 2 步取消，
     *       完成事件<b>一次都不会到</b>。这条与上一条互为交叉证据；</li>
     *   <li>{@code walkDistanceDuringReload ≥ 3 格}：排除"intent 写了 W 但人被挡住"的假通过 ——
     *       否则"原地不动也没被打断"会被误读成"边走边换成立"。</li>
     * </ol>
     * 反向验证（M2.2 报告第 10 节口径）：把 {@code GunState.tick} 的 moving 分支还原，
     * 第 1、2 条立刻变红，第 3 条也会降到 0 格附近 —— 三条一起红，不是单点巧合。
     */
    private void checkReloadWhileWalking() {
        Player player = host.player();
        GunState gun = host.combat().existingGun(player);
        int mag = gun == null ? -1 : gun.magazineAmmo();
        int reserve = player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID);

        record("连续开火把弹匣打空", magAfterEmptying == 0, "打空后 magazineAmmo=" + magAfterEmptying);
        record("按 R 之后立刻进入换弹态", reloadingObservedAfterRequest,
                "reloadingObservedAfterRequest=" + reloadingObservedAfterRequest
                        + "（在第 " + reloadRequestedAtStep + " 步按下 R，同一逻辑步内观测）");
        // ★ 核心：旧口径下这个数只能是 1（第二步就被取消），因此它是对"移动不打断"的直接否证点。
        record("换弹期间全程按住 W 且未被取消（边走边换步数 ≥ "
                        + MIN_WALK_STEPS_WHILE_RELOADING + "）",
                walkStepsDuringReload >= MIN_WALK_STEPS_WHILE_RELOADING,
                "边走边换步数=" + walkStepsDuringReload + "（换弹 1.2 s = 72 步；旧口径下停在 1）");
        // ★ 排除"人没动"的假通过：这条与上一条必须同时成立，"边走"才算数。
        record("换弹期间确实产生了水平位移（≥ "
                        + MIN_WALK_DISTANCE_WHILE_RELOADING + " 格）",
                walkDistanceDuringReload >= MIN_WALK_DISTANCE_WHILE_RELOADING,
                String.format("位移=%.3f 格（4.317 格/秒 × 1.2 s ≈ 5.2 格）", walkDistanceDuringReload));
        //
        //   ★ 旧口径下按住 W 会在第 2 步把换弹取消，完成事件一次都不会到 ——
        //   因此"完成事件恰好 1 次"本身就是"移动不打断"的第二条独立证据。
        int completions = eventReloadCompletions - stageStartEventReloadCompletions;
        record("边走边换：换弹走完全程（收到一次 onReloadCompleted）", completions == 1,
                "完成事件数=" + completions + "（旧口径下按住 W 会取消换弹 → 0）");
        record("换弹完成那一刻弹匣已补满 = 12", magAtCompletion == 12,
                "magazineAmmo=" + magAtCompletion + "（完成时即时采样）");
        // ★ A3 规则④：完成前不得提前转移弹药 —— 完成是唯一的转移时刻。
        //   它<b>不是</b>恒真断言：若"完成时没补"会读到 0，若"提前补了"会在完成前就变 12。
        record("换弹走完后弹匣 = 12（弹药在完成这一刻才转移）", mag == 12, "magazineAmmo=" + mag);
        record("换弹走完后换弹态已退出", gun != null && !gun.isReloading(),
                "isReloading=" + (gun != null && gun.isReloading()));
        //
        //   M2.1-A：无限后备下换弹只读后备、不写背包 —— 因此整个"打空 → 按 R → 边走边换"
        //   过程里，背包弹药应当<b>始终等于开局那 24 发</b>。
        //   （这条不能写成"打断前 == 打断后"：无限口径下那是 24 == 24 的恒真断言。）
        record("打空 + 边走边换全程后备弹药未被扣减（仍为 24）",
                reserve == 24 && reserveAfterEmptying == 24,
                "打空后=" + reserveAfterEmptying + " 换弹后=" + reserve
                        + "（M2.1：换弹只读后备、不写背包）");
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

        // ★ 前置条件自证：本阶段下面每一条结论都建立在"手里不是枪"之上。
        //
        //   为什么这条必须存在：M2.2 把 Inventory.size() 从 9 变成 36 时，
        //   扫描槽位的辅助方法一度返回**绝对索引**，而 selectSlot 只认 0..8。
        //   于是"切到第一个非枪物品"实际选中了快捷栏第 1 格的枪，接下来 7 条挖掘断言
        //   一起以"挖掘没生效"的样子失败 —— 失败现场指向玩法，真因在槽位口径上。
        //
        //   把"手里确实不是枪"写成断言之后，同类错误会以"槽位口径"的措辞直接指出自己。
        //   这与本项目那条审计判据是同一条：**测量仪器必须先被验证**。
        ItemStack heldForMining = player.inventory().selectedStack();
        record("挖掘阶段手上持有的不是枪（槽位口径自证）", !heldForMining.item().isGun(),
                "selectedSlot=" + player.inventory().selectedSlot()
                        + " → 绝对索引 "
                        + player.inventory().hotbarIndex(player.inventory().selectedSlot())
                        + "，item=" + heldForMining.item().id()
                        + "（若这里是手枪，说明槽位口径错了：扫描结果必须是 0..8 相对索引）");

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

    /**
     * M3 Story 9：SMG 连续按住 30 秒的稳定性断言（v2 §15）。
     *
     * <h2>这一阶段要证明的四件事</h2>
     * <ol>
     *   <li><b>连发真的发生了</b>：30 秒里 {@code shotsFired} 的增量与"射速 × 净开火时间"
     *       相符，且观测到过弹匣打空（说明确实连发到空，而不是偶尔点射）；</li>
     *   <li><b>无异常增长</b>：曳光 / 粒子 / 枪口闪光的<b>同时存活峰值</b>都远低于各自容量上限
     *       （只进不出的泄漏会把峰值顶到上限）；</li>
     *   <li><b>表现与逻辑一一对应</b>：阶段内新增曳光数 == 新增枪口闪光数 == 非换弹路径的开火数，
     *       高射速下漏接某一环会被这三条交叉计数直接暴露；</li>
     *   <li><b>没有异常</b>：全程不抛异常、不 OOM（能跑到这里本身就是证据；若中途异常，
     *       自测会以"没跑完"收场而 {@code allPassed()=false}）。</li>
     * </ol>
     *
     * <h2>数字口径</h2>
     * <p>SMG：{@code fireRate=10.0}、弹匣 24、换弹 1.5 s。一个"打空 + 换弹"周期约
     * 24/10 + 1.5 = 3.9 s，30 s 约 7.7 个周期 → 约 185 发。因此本方法<b>不</b>断言 300 发
     * （那是"从不换弹"才可能的上界），而是断言一个与周期模型一致的下界：
     * 若连发路径正常，30 秒至少能打出 ~150 发；任一环卡死（例如换弹没完成、开火被吞）
     * 都会把这数字显著拉低。上下都留了余量，避免浮点/调度抖动造成假红。
     *
     * <p><b>为什么不断言 frame time：</b>本方法是逐逻辑步的确定性脚本，不含墙钟时间；
     * p95/p99 帧时间的无回归由独立的性能对照运行（1080p / vsync=false / measureSeconds=60）
     * 取证，两者分工不同，见 {@code docs/testing/M3_WEAPON_S9_PERF_GATE.md}。
     */
    private void checkSmgSustained() {
        Player player = host.player();
        CombatFxModel fx = host.combatFx();
        int shots = host.combat().shotsFired() - smgStartShots;
        int dry = host.combat().dryFires() - smgStartDryFires;
        int tracers = fx == null ? 0 : (int) (fx.totalTracers() - smgStartTracersGenerated);
        int flashes = fx == null ? 0 : (fx.totalMuzzleFlashes() - smgStartMuzzleFlashes);
        int reloadCompletions = eventReloadCompletions - stageStartEventReloadCompletions;
        int reserve = player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID);

        record("SMG 已从开局装备拿到并切到手上（v2 §19-2「SMG 可正常获得」）", smgRuntimeId >= 0,
                "smgRuntimeId=" + smgRuntimeId + " 手持=" + player.inventory().selectedStack().item().id());
        // 断言 >= SMG_STAGE_STEPS - 1 而不是 == SMG_STAGE_STEPS：
        // onStageEnd()（本方法在其中被调用）是在本阶段最后一步的 nextIntent() 内部、
        // 该步的 observeAfterStep() 之前被触发的 —— 因此"最后一步的观测"永远晚于本断言，
        // 计数器最多到 SMG_STAGE_STEPS - 1。这与 spawn/approach 阶段文档里记的
        // "onStageEnd 早于最后一步观测"是同一条确定性时序（不是抖动）。
        // 标签写「≥ 1799」而不是「= 1800」：判据必须和标签一致，否则只看标签的人
        // 会以为真的数满了 1800 步。1799 是确定性上界，不是抖动留的容差。
        record("阶段步数 ≥ 1799（30 秒 × 60 TPS = 1800 步；onStageEnd 早于最后一步观测 → 上界 1799）",
                smgStageSteps >= SMG_STAGE_STEPS - 1,
                "smgStageSteps=" + smgStageSteps + "（onStageEnd 早于最后一步观测 → 上界 "
                        + (SMG_STAGE_STEPS - 1) + "）");

        // ---- ① 连发真的发生了 ----
        // 下界 150：周期模型各周期约 24 发 / 3.9 s，30 s ≈ 185；取 150 留 ~19% 余量。
        // 上界 300 = fireRate × 30（理论上界，实际上做不到因为要换弹）—— 用来挡"计数被重复累加"。
        record("SMG 连发击发数落在合理区间（150 ≤ shots ≤ 300）",
                shots >= 150 && shots <= 300,
                "shots=" + shots + "（周期模型 30s ≈ 185；上界 300 = 10 发/秒 × 30 秒）");
        record("连发过程中确实打空过弹匣（说明是连发而非零星点射）", smgObservedEmptyMagazine,
                "smgObservedEmptyMagazine=" + smgObservedEmptyMagazine);
        record("阶段内至少完成过一次换弹（打空 → 按 R → 上膛的循环真的跑起来）",
                reloadCompletions >= 5,
                "reloadCompletions=" + reloadCompletions + " 发起请求=" + smgReloadRequests);

        // ---- ② 无异常增长（峰值远低于容量上限）----
        record("曳光同时存活峰值 ≤ 容量上限（无泄漏）",
                smgPeakTracers <= CombatFxModel.MAX_TRACERS,
                "peakTracers=" + smgPeakTracers + " ≤ MAX_TRACERS=" + CombatFxModel.MAX_TRACERS);
        record("粒子同时存活峰值 ≤ 容量上限（无泄漏）",
                smgPeakParticles <= CombatFxModel.MAX_PARTICLES,
                "peakParticles=" + smgPeakParticles + " ≤ MAX_PARTICLES=" + CombatFxModel.MAX_PARTICLES);
        record("枪口闪光同时存活峰值 ≤ 容量上限（无泄漏）",
                smgPeakFlashes <= CombatFxModel.MAX_FLASHES,
                "peakFlashes=" + smgPeakFlashes + " ≤ MAX_FLASHES=" + CombatFxModel.MAX_FLASHES);
        // 更严的一条：正常节奏下（10 发/秒、曳光活 3 帧、闪光活 3 帧）同时存活应为个位数。
        // 这条比"≤ 容量"有鉴别力得多 —— 容量断言只挡"顶到上限"，这条挡"数量失控增长"。
        record("曳光同时存活峰值处于正常量级（≤ 8，无随按住时长增长）",
                smgPeakTracers <= 8, "peakTracers=" + smgPeakTracers);

        // ---- ③ 表现与逻辑一一对应 ----
        record("新增曳光数 == 新增枪口闪光数（每发一份表现，高射速下无漏接）",
                tracers == flashes,
                "tracers=" + tracers + " flashes=" + flashes);
        record("新增曳光数 ≤ 击发数（曳光不因连发被重复生成）",
                tracers <= shots, "tracers=" + tracers + " shots=" + shots);

        // ---- ④ 弹药口径 ----
        // 自测走 PROTOTYPE（无限后备）—— 换弹只读后备、不写背包，故 SMG 连发 30 秒后备弹药不变。
        record("SMG 连发 30 秒后备弹药未被扣减（PROTOTYPE 口径：换弹只读后备）",
                reserve == smgStartReserve,
                "reserve=" + reserve + " 起始=" + smgStartReserve);

        // ---- ⑤ 每发临时对象分配（v2 §15 硬约束：禁止因高射速增加每发分配）----
        // resolveShot 的每发分配集中在命中判定链（eyePosition 的 Vector3d、方向归一化的
        // Vector3d、Hitscan.Result），这些与射速<b>无关</b>，只与"打了几发"成线性 ——
        // SMG 与手枪走的是同一条 resolveShot。
        // 真正会"因高射速而增加每发分配"的是<b>每发循环</b>：弹丸数（pelletCount）与散布采样。
        // 二者都是 GunSpec 的数据字段：pelletCount=1 且 spreadRad=0 → 每发恰好一条射线、
        // 不进入任何散布采样循环，因此每发分配量与手枪（同为 1/0）逐值相同。
        // 这条断言钉住的就是"SMG 的数据没被写成 N 弹丸/带散布"，从而断死"因高射速增加每发分配"。
        Item smgItem = ItemRegistry.byRuntimeId(smgRuntimeId);
        GunSpec smgSpec = smgItem == null ? null : smgItem.gun();
        record("SMG 每发恰好一条射线（pelletCount=1，无每发内层循环）",
                smgSpec != null && smgSpec.pelletCount() == 1,
                "pelletCount=" + (smgSpec == null ? "(无枪)" : smgSpec.pelletCount()));
        record("SMG 无散布采样（spreadRad=0，无每发随机分配）",
                smgSpec != null && smgSpec.spreadRad() == 0.0,
                "spreadRad=" + (smgSpec == null ? "(无枪)" : smgSpec.spreadRad()));
        // 交叉验证：曳光累计数 == 击发数（每发有且仅有一条曳光 → 表现层每发 O(1)、无随射速放大）。
        record("新增曳光数 == 击发数（表现层每发恰一份，不随射速放大）",
                tracers == shots, "tracers=" + tracers + " shots=" + shots);

        Log.info("[自测] SMG 30 秒稳定性：shots=%d dry=%d reloads=%d peak(tr=%d,pa=%d,fl=%d) reserve=%d",
                shots, dry, reloadCompletions, smgPeakTracers, smgPeakParticles, smgPeakFlashes, reserve);
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
     *   <li>{@code reload} ← RELOAD_FULL / RELOAD_WHILE_WALKING 各按过一次 R 且被受理；</li>
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
        record("换弹事件已送达音频层（reload ≥ 2：RELOAD_FULL 与 RELOAD_WHILE_WALKING 各一次）",
                audit.countOf(AudioEvent.RELOAD) >= 2,
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
     *
     * <h2>曾经存在的假绿漏洞（务必保留这段历史）</h2>
     * 本方法最初用 {@code newestMonsterPng(dir)} 从<b>共享、跨轮追加</b>的截图目录里挑
     * "最新的 monster-in-view PNG"。若<b>本轮</b>因故没产出这张图（阶段没跑到第 30 步 /
     * 截图请求被丢弃 / 目录不可写），选取逻辑会安静地返回<b>上一轮</b>留下的那张，
     * 于是判据名为"本轮这一帧里有怪"、实测却是"历史某轮有怪"，<b>在缺陷仍然存在时也会变绿</b>。
     * 修法见 {@link #newestMonsterPng(Path)}：先过硬门
     * {@link #screenshotRequestedThisRun()}（本轮必须真的发起过请求），再把 mtime 下界
     * 绑定到本轮请求时刻（见 {@link #thisRunScreenshotFloorMs()}），并把"新近度"单独升格成
     * 一条 {@code record(...)} 断言，消息里同时打印 PNG 的 mtime、本轮起始与本轮请求时刻。
     *
     * <p><b>兜底下界也曾经是一条退路，现在被删掉了。</b>中间版本曾允许"请求时刻未赋值时
     * 退化为 {@link #runStartMillis} 作下界"；那等于给绿灯留了一条"读到的时间恰好落在容差里"
     * 的解释。现在未发起请求即直接判无截图，绿灯只剩一条解释：<b>本轮确实产出了这张新图</b>。
     *
     * <p><b>时间容差与文件名快照是两道不同的门</b>：时间门（mtime ≥ 本轮请求时刻 − 2 秒容差）
     * 堵"时间太旧"，但为吸收"请求时刻与落盘 mtime 落在同一秒"的抖动必须留容差；文件名门
     * （{@link #preexistingMonsterShotNames}：起始时把目录里已有的 monster-in-view 文件名记下，
     * 命中必须落在集合之外）按<b>身份</b>判定"是不是起始即存在的旧图"，与 mtime 精度无关，
     * 专门堵"时间恰好落在容差里"这条缝。两者互相独立，谁也替代不了谁。
     */
    public void verifyMonsterFramebuffer() {
        Log.info("================ M2.1 自测：怪物帧缓冲像素证据 ================");
        Path dir = Path.of(System.getProperty("skyisland.screenshotDir", "screenshots"));
        boolean requested = screenshotRequestedThisRun();
        Path png = newestMonsterPng(dir);                 // 先过硬门，再只认本轮产出的
        Path newestAny = newestMonsterPngAnyRun(dir);     // 仅用于失败时打印目录现状
        long pngTs = png == null ? Long.MIN_VALUE : mtimeMs(png);
        long newestAnyTs = newestAny == null ? Long.MIN_VALUE : mtimeMs(newestAny);

        /*
         * 独立的"新近度"断言：把"有没有截图"与"截图是不是本轮的"分成两条各自会变红的判据。
         * 三种失败原因在消息里各自可辨：①本轮压根没发起请求；②请求了但目录里没有本轮的新图；
         * ③有图但 mtime 早于本轮下界（旧图）。这是对历史上那个假绿漏洞的正面封堵。
         */
        record("monster-in-view 截图来自本轮运行",
                png != null,
                monsterScreenshotFreshnessDetail(requested, png, pngTs, newestAny, newestAnyTs));

        if (png == null) {
            record("monster-in-view 截图已落盘（否则无像素可读）", false,
                    "目录 " + dir.toAbsolutePath() + " 下找不到本轮（起始 epochMs="
                            + runStartMillis + "，请求 epochMs=" + approachScreenshotRequestedAtMs
                            + "）的 monster-in-view PNG");
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
     * 在截图目录里找<b>本轮</b>写出的 {@code monster-in-view} PNG。
     *
     * <h2>第一道：硬门（绑定"本轮"）</h2>
     * 本轮必须真的发起过 {@code monster-in-view} 请求（{@link #screenshotRequestedThisRun()}）。
     * 未发起 ⇒ 直接返回 {@code null}，<b>不退化成 runStartMillis 兜底</b>：此时目录里任何
     * monster-in-view PNG 都必然来自别的轮次，不存在"本轮的截图"。
     *
     * <h2>第二、三道：文件名门 + 时间门（两道互相独立的门）</h2>
     * 过门后，把所有文件名匹配 {@code *monster-in-view*.png} 的候选<b>按 mtime 降序</b>排列，
     * 取第一个<b>同时</b>满足下面两条的：
     * <ol>
     *   <li><b>文件名门</b>：文件名不在本轮起始快照
     *       {@link #preexistingMonsterShotNames} 内（按<b>身份</b>排除"起始时就已存在的旧图"）；</li>
     *   <li><b>时间门</b>：mtime {@code >=} 本轮请求时刻 − 容差
     *       （见 {@link #thisRunScreenshotFloorMs()}）。</li>
     * </ol>
     * 两道门各堵一类漏洞：时间门堵"时间太旧"，文件名门堵"时间恰好落在容差里"，谁也替代不了谁。
     * 一个候选都不满足就返回 {@code null}。<b>不要</b>再退回"只挑最新一张"：共享目录里最新的
     * 一张可能来自上一轮，那样判据测的会是"历史某轮有怪"而不是"本轮这一帧有怪"（历史假绿漏洞）。
     *
     * @return 本轮产出且最新的 monster-in-view PNG；本轮没有则 {@code null}
     */
    private Path newestMonsterPng(Path dir) {
        if (!screenshotRequestedThisRun()) {
            return null;                                  // 硬门：本轮没发起请求 → 不存在本轮的截图
        }
        return newestMonsterPngAtLeast(dir, thisRunScreenshotFloorMs(), preexistingMonsterShotNames);
    }

    /**
     * 本轮是否真的发起过 {@code monster-in-view} 截图请求。
     *
     * <p>这是把判据绑定到"本轮"的<b>硬门</b>：请求时刻只在跑到 {@code SPAWN_AND_APPROACH}
     * 第 30 步时才会被赋值。判据只有过了这道门，才有资格谈"本轮的截图"。
     */
    private boolean screenshotRequestedThisRun() {
        return approachScreenshotRequestedAtMs >= 0;
    }

    /**
     * 不限定轮次的最新 monster-in-view PNG，<b>仅供失败时打印"目录里其实有什么"</b>
     * 以便人工排查 —— 绝不可拿它当作像素判据的输入（那正是历史假绿的来源）。
     */
    private Path newestMonsterPngAnyRun(Path dir) {
        return newestMonsterPngAtLeast(dir, Long.MIN_VALUE, Set.of());
    }

    /**
     * "本轮"截图允许的最小 mtime（毫秒）= 本轮请求时刻 − {@link #SCREENSHOT_FRESHNESS_TOLERANCE_MS}。
     *
     * <p>容差只用来吸收"请求时刻与落盘 mtime 落在同一秒（mtime 秒级截断）"的抖动，
     * <b>不再承担"兜底下界"的角色</b>。本方法<b>只有</b>在本轮确实发起过请求时才有意义，
     * 调用前必须先过 {@link #screenshotRequestedThisRun()}。
     *
     * @throws IllegalStateException 在本轮未发起请求时被调用 —— 这是编程错误（漏了硬门），
     *                               不是运行期分支；宁可显式炸掉也不静默退化成兜底下界
     */
    private long thisRunScreenshotFloorMs() {
        if (!screenshotRequestedThisRun()) {
            throw new IllegalStateException(
                    "thisRunScreenshotFloorMs() 只能在本轮已发起 monster-in-view 请求时调用；"
                            + "调用方必须先判 screenshotRequestedThisRun()");
        }
        return approachScreenshotRequestedAtMs - SCREENSHOT_FRESHNESS_TOLERANCE_MS;
    }

    /**
     * 组装"本轮是否有截图"的消息，让四种失败原因在日志里各自可辨：
     * <ol>
     *   <li>本轮没发起请求（{@code 请求 epochMs=-1}）；</li>
     *   <li>发起过请求，但目录里根本没有 monster-in-view 图；</li>
     *   <li>命中的最新图与起始快照同名 —— 文件名门判定为"起始即存在的旧图"；</li>
     *   <li>命中的最新图名字是新图，但 mtime 早于本轮下界 —— 时间门判定为旧图。</li>
     * </ol>
     * 成功时消息里也会带上"起始快照张数"，便于一眼看出这轮开始时目录里堆了多少历史图。
     */
    private String monsterScreenshotFreshnessDetail(boolean requested, Path png, long pngTs,
            Path newestAny, long newestAnyTs) {
        StringBuilder sb = new StringBuilder();
        sb.append("本轮起始 epochMs=").append(runStartMillis);
        if (requested) {
            sb.append("，本轮请求 epochMs=").append(approachScreenshotRequestedAtMs)
                    .append("，本轮 mtime 下界 epochMs=").append(thisRunScreenshotFloorMs());
        } else {
            sb.append("，本轮请求 epochMs=-1（本轮未发起 monster-in-view 请求）");
        }
        // 文件名门的快照规模：一眼看出"本轮开始时目录里已经堆了多少张历史图"。
        sb.append("，起始快照 monster-in-view 文件 ").append(preexistingMonsterShotNames.size())
                .append(" 张");
        if (png != null) {
            sb.append("；本轮 PNG=").append(png.getFileName()).append(" mtime=").append(pngTs);
            return sb.toString();
        }
        /*
         * 四种失败原因各自可辨：
         *   ①本轮没发起请求；
         *   ②请求了但目录里根本没有 monster-in-view 图；
         *   ③命中的最新图与起始快照同名 —— 文件名门判定为"起始即存在的旧图"；
         *   ④命中的最新图名字是新图，但 mtime 早于本轮下界 —— 时间门判定为旧图。
         */
        if (!requested) {
            sb.append("；结论：本轮根本没请求过截图，目录里任何图都来自别的轮次");
        } else if (newestAny == null) {
            sb.append("；目录里没有任何 monster-in-view PNG（本轮请求了却没落盘？）");
        } else if (preexistingMonsterShotNames.contains(newestAny.getFileName().toString())) {
            sb.append("；目录里最新的是 ").append(newestAny.getFileName())
                    .append(" mtime=").append(newestAnyTs)
                    .append("（与起始快照同名 —— 文件名门判定为旧图）");
        } else if (newestAnyTs < thisRunScreenshotFloorMs()) {
            sb.append("；目录里最新的是 ").append(newestAny.getFileName())
                    .append(" mtime=").append(newestAnyTs)
                    .append("（早于本轮下界 —— 时间门判定为旧图）");
        } else {
            sb.append("；目录里最新的是 ").append(newestAny.getFileName())
                    .append(" mtime=").append(newestAnyTs)
                    .append("（两道门都过却没被选中？请复查选择逻辑）");
        }
        return sb.toString();
    }

    /**
     * 按 mtime <b>降序</b>排列候选，取第一个<b>同时</b>满足：文件名不在 {@code excludedNames} 内、
     * 且 mtime {@code >= floor} 者；没有则 {@code null}。
     *
     * <p>{@code excludedNames} 即"本轮起始快照"（文件名门）；诊断用法传空集
     * （见 {@link #newestMonsterPngAnyRun(Path)}），此时只剩 mtime 一个约束。
     */
    private Path newestMonsterPngAtLeast(Path dir, long floor, Set<String> excludedNames) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> walk = Files.list(dir)) {
            return walk
                    .filter(p -> p.getFileName().toString().contains("monster-in-view"))
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .filter(p -> !excludedNames.contains(p.getFileName().toString()))
                    .filter(p -> mtimeMs(p) >= floor)
                    .sorted(Comparator.comparingLong(this::mtimeMs).reversed())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            Log.noteWarning("自测", "列举截图目录失败：" + e);
            return null;
        }
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

        // ---- 用例 3（用户规格 §2 点名「边缘」）：水平前方 4–8 格全是虚空（脚下无地面）----
        // 摆位：出生点 (0.5, 64, 0.5)，yaw=-135° → 水平前向 = 归一化 (+1,+1)/√2，
        // 正好指向测试世界的虚空坑（x∈[3,6], z∈[3,6] 无方块柱）。9 个候选落点
        // （t=4.0…8.0 沿对角线的投影全落在 x=z∈[3,6]）都在无方块柱上 →
        // findSpawnGroundY 一路探不到地面 → 每条候选都在「地面」这一条被拒。
        // 这条是上一轮把"下方无地面则返回 null"换成语义后【净损失】的那块覆盖，这里补回。
        player.teleport(SPAWN_X, SPAWN_Y, SPAWN_Z);
        player.camera().setAngles(-135, 0);
        player.step(world, PlayerIntent.NONE, GameLoop.FIXED_DT);
        int aliveBefore3 = entities.aliveCount();
        int spawnedBefore3 = entities.totalSpawned();
        MeleeMonster overVoid = host.spawnMonsterInFront();
        int[] rejEdge = host.lastSpawnRejectCounts();
        int cand = spawnCandidateCount();
        record("边缘（水平前方全虚空）刷怪返回 null（脚下无可站立地面）",
                overVoid == null,
                overVoid == null
                        ? "返回 null；拒绝计数 地面=" + rejEdge[0] + " 落差=" + rejEdge[1]
                                + " 视锥=" + rejEdge[2] + " 视线=" + rejEdge[3]
                                + "（候选数=" + cand + "）"
                        : String.format("竟生成了 (%.2f, %.2f, %.2f)",
                                overVoid.position().x, overVoid.position().y, overVoid.position().z));
        record("边缘（水平前方全虚空）实体数不变（没有偷偷塞一只）",
                entities.aliveCount() == aliveBefore3
                        && entities.totalSpawned() == spawnedBefore3,
                "alive " + aliveBefore3 + "→" + entities.aliveCount()
                        + "，spawned " + spawnedBefore3 + "→" + entities.totalSpawned());
        // 归因：这条位姿下 9 个候选必须【全部】被「地面」拒（其余三条一个都没轮到），
        // 否则"边缘"这个场景就没被真正构造出来（可能被别的判据顺手拒了）。
        record("边缘（水平前方全虚空）拒绝归因：全部来自「地面」判据",
                rejEdge[0] == cand && rejEdge[1] == 0 && rejEdge[2] == 0 && rejEdge[3] == 0,
                "拒绝计数 地面=" + rejEdge[0] + " 落差=" + rejEdge[1]
                        + " 视锥=" + rejEdge[2] + " 视线=" + rejEdge[3] + "（候选数=" + cand + "）");

        // ---- 用例 4（用户规格 §2 点名「4–8 格高台」）：站在 ≥4 格高台上朝外 → 绝不生成 ----
        // 摆位：测试世界高台顶面 y=69（比地表顶面 63 高 6 格，≥4），站 (-12, 70, -12)、
        // yaw=180° → 水平前向 +Z（朝台外）。前方 4–8 格的列全在地表（顶面 63）→
        // findSpawnGroundY 得 64 → Δy = 64-70 = -6，|Δy| > 3，9 个候选全部被「落差」拒。
        player.teleport(-12.0, 70.0, -12.0);
        player.camera().setAngles(180, 0);
        player.step(world, PlayerIntent.NONE, GameLoop.FIXED_DT);
        int aliveBefore4 = entities.aliveCount();
        int spawnedBefore4 = entities.totalSpawned();
        MeleeMonster offPlateau = host.spawnMonsterInFront();
        int[] rejPlateau = host.lastSpawnRejectCounts();
        record("≥4 格高台朝外：刷怪返回 null（落差超限，不刷到台下/脚下）",
                offPlateau == null,
                offPlateau == null
                        ? "返回 null；拒绝计数 地面=" + rejPlateau[0] + " 落差=" + rejPlateau[1]
                                + " 视锥=" + rejPlateau[2] + " 视线=" + rejPlateau[3]
                                + "（候选数=" + cand + "）"
                        : String.format("竟生成了 (%.2f, %.2f, %.2f) —— 落差 %.1f 格",
                                offPlateau.position().x, offPlateau.position().y,
                                offPlateau.position().z,
                                offPlateau.position().y - player.position().y));
        record("≥4 格高台朝外：实体数不变（没有偷偷塞一只）",
                entities.aliveCount() == aliveBefore4
                        && entities.totalSpawned() == spawnedBefore4,
                "alive " + aliveBefore4 + "→" + entities.aliveCount()
                        + "，spawned " + spawnedBefore4 + "→" + entities.totalSpawned());
        record("≥4 格高台朝外：拒绝归因：全部来自「落差」判据",
                rejPlateau[1] == cand && rejPlateau[0] == 0
                        && rejPlateau[2] == 0 && rejPlateau[3] == 0,
                "拒绝计数 地面=" + rejPlateau[0] + " 落差=" + rejPlateau[1]
                        + " 视锥=" + rejPlateau[2] + " 视线=" + rejPlateau[3] + "（候选数=" + cand + "）");

        // ---- 用例 4b：同一个高台上朝台内（前方仍有同层地面）→ 成功且 Δy≈0 ----
        // 摆位：站高台西南角 (-15.5, 70, -15.5)，yaw=-90° → 水平前向 +X（朝台内）。
        // 候选 t=4 → x=-11.5（floor=-12 仍在高台内，顶面 69）→ groundY 70 → Δy=0 →
        // 四条硬条件全过。这让"高台"这个场景【正反两面】都有断言（不是只有拒绝）。
        player.teleport(-15.5, 70.0, -15.5);
        player.camera().setAngles(-90, 0);
        player.step(world, PlayerIntent.NONE, GameLoop.FIXED_DT);
        MeleeMonster onPlateau = host.spawnMonsterInFront();
        record("≥4 格高台朝台内：刷怪成功（前方仍有同层地面）",
                onPlateau != null,
                onPlateau == null
                        ? "返回 null（本应成功 —— 台内前 4 格就是同层地面）"
                        : String.format("怪 (%.2f, %.2f, %.2f)，Δy=%.2f",
                                onPlateau.position().x, onPlateau.position().y,
                                onPlateau.position().z,
                                onPlateau.position().y - player.position().y));
        record("≥4 格高台朝台内：Δy≈0（怪与玩家同层）",
                onPlateau != null
                        && Math.abs(onPlateau.position().y - player.position().y) < 0.05,
                onPlateau == null ? "monster=null"
                        : String.format("玩家 y=%.4f，怪 y=%.4f，Δy=%.4f",
                                player.position().y, onPlateau.position().y,
                                onPlateau.position().y - player.position().y));

        // ---- 用例 5（用户规格 §4/§7）：只有「视锥」会拒的位姿 —— 视锥硬条件的专属守门人 ----
        // 摆位：回到地表出生点、水平朝 -Z（候选全在平地：地面/落差/视线都成立），
        // 但把 pitch 压到 89°（几乎朝天）—— 候选方向仍取"相机水平前向"，而怪物中心
        // 相对视线（几乎垂直向上）的偏轴角 ≈ 90° > 半 FOV（35°），落在视锥之外。
        // 这条专门给「视锥」一个会变红的断言：把它改成恒真（always-true），本用例必红。
        player.teleport(SPAWN_X, SPAWN_Y, SPAWN_Z);
        player.camera().setAngles(0, 89.0);
        player.step(world, PlayerIntent.NONE, GameLoop.FIXED_DT);
        int aliveBefore5 = entities.aliveCount();
        int spawnedBefore5 = entities.totalSpawned();
        MeleeMonster offScreen = host.spawnMonsterInFront();
        int[] rejFrustum = host.lastSpawnRejectCounts();
        boolean frustumEvaluated = host.lastFrustumEvaluated();
        record("视锥外刷怪返回 null（候选在视野外）",
                offScreen == null,
                offScreen == null ? "返回 null"
                        : String.format("竟生成了 (%.2f, %.2f, %.2f)",
                                offScreen.position().x, offScreen.position().y,
                                offScreen.position().z));
        record("视锥外实体数不变（没有偷偷塞一只）",
                entities.aliveCount() == aliveBefore5
                        && entities.totalSpawned() == spawnedBefore5,
                "alive " + aliveBefore5 + "→" + entities.aliveCount()
                        + "，spawned " + spawnedBefore5 + "→" + entities.totalSpawned());
        // 关键：证明"是视锥拒的"而不是碰巧被别的判据拒的 —— 视锥必须真的参与，
        // 且 9 个候选全部【只】被视锥这一条拒（地面/落差/视线均为 0）。
        record("视锥外拒绝归因：全部来自「视锥」判据（地面/落差/视线均为 0）",
                frustumEvaluated && rejFrustum[0] == 0 && rejFrustum[1] == 0
                        && rejFrustum[3] == 0 && rejFrustum[2] == cand,
                "视锥参与=" + frustumEvaluated + "；拒绝计数 地面=" + rejFrustum[0]
                        + " 落差=" + rejFrustum[1] + " 视锥=" + rejFrustum[2]
                        + " 视线=" + rejFrustum[3] + "（候选数=" + cand + "）");
        Log.info("==========================================================");
    }

    /**
     * F4 候选带上候选点的个数（与 {@code SkyIslandGame} 的搜索规格同源）。
     *
     * <p>断言里凡是"9 个候选全部被某一条拒"的归因，都用它而不是写死 9 ——
     * 若候选规格将来改了，断言会跟着改，不会变成一条口径漂移的假断言。
     */
    private static int spawnCandidateCount() {
        return (int) Math.round(
                (SkyIslandGame.SPAWN_CANDIDATE_MAX - SkyIslandGame.SPAWN_CANDIDATE_MIN)
                        / SkyIslandGame.SPAWN_CANDIDATE_STEP) + 1;
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

    /**
     * M2.1：<b>F3 实体叠层 + 碰撞箱同步</b>的守门人。
     *
     * <h2>它补的是哪一段缺口</h2>
     * {@code SkyIslandGame.appendEntityDebugLines()} 与
     * {@code EntityRenderer.setDebugHitbox()} 都曾经是"定义了、没人登记"的接线：
     * 方法齐备、渲染分支也在，却没有任何断言跟着它们。于是"F3 打开后叠层有没有真的产出"
     * 与"碰撞箱开关有没有跟着 F3 走"这两件事在门禁里全是空白 —— 改坏了也不会红。
     *
     * <h2>为什么必须走产品的产出闸门</h2>
     * 断言通过 {@code Host.debugEntityOverlayLines()} 调用<b>产品自己的</b>
     * {@code appendEntityDebugLinesIfEnabled()}，不自造行格式、不在这里重写 {@code if}：
     * 若把那条闸门删掉或改成恒真，"overlay 关 → 0 行"就会变红。
     *
     * <h2>为什么"视锥 是 / 否 同时出现"才算真判定</h2>
     * 视锥字段若被写死成常量，"身前=是、身后=否"不可能同时成立 —— 这条断言专门堵死
     * "把字段填成字面量"。它用的是与 F4 刷怪同一份判定工具（{@link Frustum}）。
     */
    public void verifyDebugEntityOverlay() {
        Log.info("================ M2.1 自测：F3 实体叠层 + 碰撞箱同步 ================");
        Player player = host.player();
        World world = host.world();
        EntityManager entities = host.entities();

        // 摆位：站出生点、水平朝 -Z。放 3 只怪：2 只在正前方（视锥内）、1 只在身后（视锥外）。
        entities.clear();
        player.teleport(SPAWN_X, SPAWN_Y, SPAWN_Z);
        player.camera().setAngles(0, 0);
        player.step(world, PlayerIntent.NONE, GameLoop.FIXED_DT);
        entities.spawnMeleeMonster(SPAWN_X - 0.8, SPAWN_Y, SPAWN_Z - 4.0);
        entities.spawnMeleeMonster(SPAWN_X + 0.8, SPAWN_Y, SPAWN_Z - 5.0);
        entities.spawnMeleeMonster(SPAWN_X, SPAWN_Y, SPAWN_Z + 4.0);   // 身后 → 视锥外
        int alive = entities.aliveCount();

        List<String> on = host.debugEntityOverlayLines(true);
        List<String> off = host.debugEntityOverlayLines(false);

        // 断言①：overlay 开 → 每只存活实体一行 + 一行汇总
        int expectedOn = alive + 1;
        record("F3 开：实体叠层每只存活实体一行 + 一行汇总",
                on.size() == expectedOn,
                "存活=" + alive + "，产出行数=" + on.size() + "（期望 " + expectedOn + "）");

        int withBoth = 0;
        boolean hasFrustumYes = false;
        boolean hasFrustumNo = false;
        boolean hasLos = false;
        for (String line : on) {
            if (line.contains("视锥") && line.contains("视线")) {
                withBoth++;
            }
            if (line.contains("视锥 是")) {
                hasFrustumYes = true;
            }
            if (line.contains("视锥 否")) {
                hasFrustumNo = true;
            }
            if (line.contains("视线 是") || line.contains("视线 否")) {
                hasLos = true;
            }
        }
        record("F3 开：每行都带 视锥/视线 字段（来自真实判定）",
                withBoth == alive && hasLos,
                "含 视锥/视线 的行数=" + withBoth + "（存活=" + alive + "），视线字段可辨=" + hasLos);
        record("F3 开：视锥字段是真判定（身前=是、身后=否 同时出现）",
                hasFrustumYes && hasFrustumNo,
                "出现「视锥 是」=" + hasFrustumYes + "，出现「视锥 否」=" + hasFrustumNo);

        // 断言①（反向）：overlay 关 → 一行都不产出
        record("F3 关：实体叠层一行都不产出",
                off.isEmpty(),
                "产出行数=" + off.size() + (off.isEmpty() ? "" : "，首行=" + off.get(0)));

        // 断言①（上限口径）：实体数 > DEBUG_ENTITY_LINES_MAX 时只列上限只 + 汇总
        entities.clear();
        int overflow = SkyIslandGame.DEBUG_ENTITY_LINES_MAX + 2;
        for (int i = 0; i < overflow; i++) {
            entities.spawnMeleeMonster(SPAWN_X + (i % 3) - 1.0, SPAWN_Y, SPAWN_Z - 1.0 - i);
        }
        int many = entities.aliveCount();
        List<String> capped = host.debugEntityOverlayLines(true);
        int max = SkyIslandGame.DEBUG_ENTITY_LINES_MAX;
        record("F3 开：实体超上限时只列 DEBUG_ENTITY_LINES_MAX 只 + 汇总",
                many > max && capped.size() == max + 1,
                "存活=" + many + "，上限=" + max + "，产出行数=" + capped.size()
                        + "（期望 " + (max + 1) + "）");
        record("F3 开：汇总行写明 存活 N / 显示 M（不静默吞掉省略数）",
                !capped.isEmpty()
                        && capped.get(capped.size() - 1).contains("存活 " + many)
                        && capped.get(capped.size() - 1).contains("显示 " + max),
                "末行=\"" + (capped.isEmpty() ? "<无>" : capped.get(capped.size() - 1)) + "\"");

        // 断言②：EntityRenderer.setDebugHitbox 与 F3 状态同步（F3 开⇒true，关⇒false）。
        // 起点确定：先经产品闸门把 overlay 置为关，再连续切换两次，逐次断言碰撞箱跟随。
        host.debugEntityOverlayLines(false);
        boolean overlay1 = host.toggleDebugOverlay();     // 关 → 开
        boolean hitbox1 = host.debugHitboxEnabled();
        record("F3 切换：碰撞箱开关与 overlay 同步（第 1 次：开）",
                hitbox1 == overlay1 && overlay1,
                "overlay=" + overlay1 + "，EntityRenderer.isDebugHitbox=" + hitbox1);
        boolean overlay2 = host.toggleDebugOverlay();     // 开 → 关
        boolean hitbox2 = host.debugHitboxEnabled();
        record("F3 切换：碰撞箱开关与 overlay 同步（第 2 次：关）",
                hitbox2 == overlay2 && !overlay2,
                "overlay=" + overlay2 + "，EntityRenderer.isDebugHitbox=" + hitbox2);
        host.debugEntityOverlayLines(false);              // 复位：overlay 关
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

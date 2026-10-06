package com.skyisland.combat;

import com.skyisland.entity.EntityManager;
import com.skyisland.input.FrameInputQuantities;
import com.skyisland.item.FireMode;
import com.skyisland.item.ItemRegistry;
import com.skyisland.player.Player;
import com.skyisland.player.PlayerIntent;
import com.skyisland.testutil.SourceScan;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ 三把枪的<b>开火模式语义</b>在同一串输入下必须互不相同（2026-10-03 主理人裁定第 1 / 15 条）。
 *
 * <h2>它守的是什么</h2>
 * 步枪落地时把 {@code fireMode} 定成了 {@link FireMode#AUTO}，理由是
 * "2 发/秒配 SINGLE 意味着每秒点两下"。主理人 2026-10-03 推翻了这条：
 *
 * <pre>
 *   步枪改 SINGLE。语义：每次真实 PRIMARY_ACTION press 最多发射 1 发。
 *   不得因为按住左键自动连续射击。SMG 保持 AUTO。
 * </pre>
 *
 * <p>于是三枪的模式分布是 <b>手枪 SINGLE / SMG AUTO / 步枪 SINGLE</b>。
 * 这里立刻浮现一个必须正面回答的问题：
 * <b>手枪与步枪同为 SINGLE，"三者语义明确不同"该怎么证？</b>
 *
 * <h2>为什么必须做成"同一串输入 → 三个不同结果"的矩阵</h2>
 * 只断言 {@code spec.fireMode()} 的字段值是不够的 ——
 * 那正是本项目反复踩的坑：字段被读了、值也被断言了，
 * 但只要"读字段"与"用字段"之间有一步没接上，行为就还是错的，而测试全绿。
 *
 * <p>而"一个序列让三把枪打出三个不同数字"是更强的判据：
 * 它要求<b>模式</b>（按下沿 vs 电平）与<b>节奏</b>（0.25 s / 0.1 s / 0.5 s 节流）
 * 两个维度<b>同时</b>由数据决定。本类用两条序列把它们分开钉住：
 *
 * <ul>
 *   <li><b>序列 A「按住 1 秒、不给按下沿」</b> —— 只测模式：
 *       手枪 0 发 / SMG 10 发 / 步枪 0 发。
 *       它把"步枪不是 AUTO"钉死：若把步枪改回 AUTO，1 秒会打出 2 发（2.0 发/秒），本断言立刻变红。</li>
 *   <li><b>序列 B「每 0.3 秒点一下、共 10 下」</b> —— 测模式 + 节奏：
 *       手枪 10 发（节流 0.25 s，每下都过）/ SMG 10 发（电平，每下一发）/ 步枪 5 发（节流 0.5 s，隔一下才过）。
 *       它把手枪与步枪分开：两者同为 SINGLE，但节奏由各自的 {@code fireRate} 决定。</li>
 * </ul>
 *
 * <p>合起来，任意两把枪都至少在一条序列上取值不同 —— 这就是"三者语义明确不同"的可执行定义。
 *
 * <h2>关于第 15 条：按下沿必须走既有 Frame → Logic 通道</h2>
 * {@link #rifleOneClickIsOneShotAtHighFrameRate()} 用真实
 * {@link FrameInputQuantities} 模拟"300 FPS 渲染 / 60 Hz 逻辑"（5 个渲染帧才轮到 1 个逻辑步），
 * 点击刻意落在<b>没有逻辑步的渲染帧</b>上：
 * 吞点击（0 逻辑步的帧丢掉按下沿）与重复击发（一帧多逻辑步重复发放）都会让它变红。
 * 另有 {@link #noDirectRenderFrameBooleanIsReadByTheCombatLayer()} 从源码层钉住
 * "没有新造一条直接读渲染帧布尔的旁路"。
 *
 * <p><b>反向验证（TEMP_REVERSE_VERIFY）</b>：把 {@code ItemRegistry} 里步枪的
 * {@code FireMode.SINGLE} 改回 {@code FireMode.AUTO}，
 * {@link #rifleHeldDoesNotAutoFire()}、{@link #theSameIntentSequenceProducesDifferentShotsPerGun()}
 * 与 {@link #rifleIsSingleShotNotAuto()} 必须同时变红。
 */
class FireModeInputSemanticsTest {

    /** 逻辑步长恒为 1/60 s（与 GameLoop.FIXED_DT 一致）。 */
    private static final double DT = 1.0 / 60.0;

    /** 序列 A 的长度：按住 1 秒 = 60 个逻辑步。 */
    private static final int HOLD_STEPS = 60;

    /** 序列 B 的点击间隔：18 步 = 0.3 秒（落在手枪 0.25 s 与步枪 0.5 s 节流之间）。 */
    private static final int TAP_INTERVAL_STEPS = 18;

    /** 序列 B 的点击次数。 */
    private static final int TAP_COUNT = 10;

    /** 高帧率模拟：300 FPS 渲染 / 60 Hz 逻辑 → 5 个渲染帧一个逻辑步。 */
    private static final int RENDER_FRAMES_PER_LOGIC_STEP = 5;

    private static World world() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    /** 造一个手持指定枪 + 指定后备弹药的玩家。 */
    private static Player armedPlayer(String gunStableId, String ammoStableId, int reserve) {
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.inventory().add(ItemRegistry.runtimeIdOf(gunStableId), 1);
        player.inventory().add(ItemRegistry.runtimeIdOf(ammoStableId), reserve);
        return player;
    }

    /** 手枪 / SMG 共用手枪弹（v2 §6.1）。 */
    private static Player armedPlayer(String gunStableId) {
        return armedPlayer(gunStableId, ItemRegistry.PISTOL_AMMO_ID, 24);
    }

    /** 步枪用自己的步枪弹（v2 §6.2）。 */
    private static Player armedWithRifle() {
        return armedPlayer(ItemRegistry.RIFLE_ID, ItemRegistry.RIFLE_AMMO_ID, 20);
    }

    /**
     * 走一次真实换弹把弹匣补满（不直接改 {@code GunState} 内部值）。
     *
     * <p>循环条件写成"还在换弹就继续推"而不是固定步数：三把枪的换弹时间不同
     * （手枪 1.2 s / SMG 1.5 s / 步枪 2.0 s），固定步数要么对慢的枪不够、
     * 要么让"换弹时间"这个被验的量悄悄变成"步数够不够"的附带结论。
     */
    private static void loadMagazine(CombatController combat, World world, Player player) {
        combat.step(world, player,
                PlayerIntent.combat(0, 0, false, 0, 0, false, false, true), DT,
                CombatController.Listener.NONE);
        PlayerIntent idle = idle();
        for (int i = 0; i < 240 && combat.gunFor(player).isReloading(); i++) {
            combat.step(world, player, idle, DT, CombatController.Listener.NONE);
        }
        assertFalse(combat.gunFor(player).isReloading(), "前提：换弹必须已经走完");
    }

    /** 什么都不按的意图。 */
    private static PlayerIntent idle() {
        return PlayerIntent.combat(0, 0, false, 0, 0, false, false, false);
    }

    /** 按住左键但<b>没有</b>按下沿：长按在"按下那一帧之后"所有帧的样子。 */
    private static PlayerIntent held() {
        return PlayerIntent.combat(0, 0, false, 0, 0, true, false, false);
    }

    /** 点一下：电平 + 按下沿同时为真（真实点击的第一帧就是这样）。 */
    private static PlayerIntent tap() {
        return PlayerIntent.combat(0, 0, false, 0, 0, true, false, false).withAttackPressed(true);
    }

    // ============================================================ 模式本体

    /**
     * 三把枪声明的模式必须是 手枪 SINGLE / SMG AUTO / 步枪 SINGLE。
     *
     * <p>它单独存在（而不是只靠下面的行为断言）是为了让失败信息<b>指向数据</b>：
     * 行为断言红的时候，看一眼这条就知道是"模式被改了"还是"模式没被用上"。
     */
    @Test
    void rifleIsSingleShotNotAuto() {
        assertEquals(FireMode.SINGLE, ItemRegistry.pistol().gun().fireMode(),
                "手枪是 SINGLE（PRD 5.4.1 / v2 §10）");
        assertEquals(FireMode.AUTO, ItemRegistry.smg().gun().fireMode(),
                "SMG 是 AUTO（v2 §10：按住连发）");
        assertEquals(FireMode.SINGLE, ItemRegistry.rifle().gun().fireMode(),
                "★ 2026-10-03 主理人裁定：步枪是 SINGLE，每次 press 最多 1 发，按住不得连发");

        assertNotEquals(ItemRegistry.smg().gun().fireMode(),
                ItemRegistry.rifle().gun().fireMode(),
                "步枪的模式必须与 SMG 不同 —— 否则这把枪在输入层与 SMG 完全无法区分");
    }

    // ============================================================ 序列 A：只测模式

    /**
     * 序列 A：<b>按住左键 1 秒、全程不给按下沿</b> —— 手枪 0 / SMG 10 / 步枪 0。
     *
     * <p>步枪这条是最要紧的：它是 2.0 发/秒，一旦 {@code fireMode} 被改回 AUTO，
     * 1 秒会稳稳打出 2 发，本断言立刻变红 —— 而"改回 AUTO"正是这条裁定要防的回归方向。
     */
    @Test
    void theSameHeldSequenceSeparatesAutoFromSingle() {
        assertEquals(0, shotsForHeldSequence(ItemRegistry.PISTOL_ID),
                "手枪 SINGLE：按住 1 秒不连发");
        assertEquals(10, shotsForHeldSequence(ItemRegistry.SMG_ID),
                "SMG AUTO：1 秒 / 0.1 秒每发 = 10 发");
        assertEquals(0, shotsForHeldSequence(ItemRegistry.RIFLE_ID),
                "★ 步枪 SINGLE：按住 1 秒必须 0 发（若为 AUTO 会是 2 发）");
    }

    /** 跑序列 A：补满弹匣 → 按住 {@link #HOLD_STEPS} 步 → 返回击发数。 */
    private static int shotsForHeldSequence(String gunStableId) {
        World world = world();
        Player player = gunStableId.equals(ItemRegistry.RIFLE_ID)
                ? armedWithRifle() : armedPlayer(gunStableId);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);
        for (int i = 0; i < HOLD_STEPS; i++) {
            combat.step(world, player, held(), DT, CombatController.Listener.NONE);
        }
        return combat.shotsFired();
    }

    /**
     * 步枪：按住左键 1 秒<b>一发都不打</b>，弹匣也不动。
     *
     * <p>与 {@link #theSameHeldSequenceSeparatesAutoFromSingle()} 的差别是它同时看
     * <b>弹匣余量</b>：只数 {@code shotsFired} 的话，万一有人把"计数"与"消耗弹匣"
     * 拆成两处，计数停了、弹匣却还在扣 —— 那正是"看起来没连发、实际在连发"。
     */
    @Test
    void rifleHeldDoesNotAutoFire() {
        World world = world();
        Player player = armedWithRifle();
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);
        int before = combat.gunFor(player).magazineAmmo();
        assertEquals(10, before, "前提：步枪弹匣已补满 10（PRD 5.4.1）");

        for (int i = 0; i < HOLD_STEPS; i++) {
            combat.step(world, player, held(), DT, CombatController.Listener.NONE);
        }
        assertEquals(0, combat.shotsFired(),
                "★ 步枪 SINGLE：按住左键 1 秒不得连发（2026-10-03 裁定）");
        assertEquals(before, combat.gunFor(player).magazineAmmo(),
                "按住左键不得消耗步枪弹匣");
    }

    // ============================================================ 序列 B：模式 + 节奏

    /**
     * 序列 B：<b>每 0.3 秒点一下、共 10 下</b> —— 手枪 10 / SMG 10 / 步枪 5。
     *
     * <p>0.3 秒这个间隔是刻意挑的：它<b>大于</b>手枪的 0.25 秒节流（每下都过）、
     * <b>小于</b>步枪的 0.5 秒节流（隔一下才过）。于是同为 SINGLE 的两把枪
     * 在同一串点击下也必须打出<b>不同</b>的弹数 —— "节奏由各自 fireRate 决定"
     * 这条因此是可证伪的，而不是一句注释。
     */
    @Test
    void theSameTapSequenceSeparatesPistolFromRifleByFireRate() {
        assertEquals(10, shotsForTapSequence(ItemRegistry.PISTOL_ID),
                "手枪 SINGLE + 0.25 s 节流：10 下点击全部过（弹匣 12 装得下）");
        assertEquals(10, shotsForTapSequence(ItemRegistry.SMG_ID),
                "SMG AUTO + 0.1 s 节流：每下一发");
        assertEquals(5, shotsForTapSequence(ItemRegistry.RIFLE_ID),
                "★ 步枪 SINGLE + 0.5 s 节流：0.3 s 间隔下隔一下才过 → 10 下打出 5 发");
    }

    /** 跑序列 B：点一下 → 空闲到下一次点击 → 返回击发数。 */
    private static int shotsForTapSequence(String gunStableId) {
        World world = world();
        Player player = gunStableId.equals(ItemRegistry.RIFLE_ID)
                ? armedWithRifle() : armedPlayer(gunStableId);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);
        for (int tap = 0; tap < TAP_COUNT; tap++) {
            combat.step(world, player, tap(), DT, CombatController.Listener.NONE);
            for (int i = 1; i < TAP_INTERVAL_STEPS; i++) {
                combat.step(world, player, idle(), DT, CombatController.Listener.NONE);
            }
        }
        return combat.shotsFired();
    }

    /**
     * ★ 综合判据：任意两把枪，至少在一条序列上的结果不同。
     *
     * <p>这条断言的价值在于它把"三者语义明确不同"从形容词变成了可执行的等式：
     * 手枪与步枪同为 SINGLE，靠<b>序列 B（节奏）</b>分开；
     * SMG 与另外两把靠<b>序列 A（模式）</b>分开。
     * 若将来有人把三把枪的 {@code fireRate} 也统一掉，本断言会在手枪/步枪这一对上变红。
     */
    @Test
    void theSameIntentSequenceProducesDifferentShotsPerGun() {
        int[][] table = {
                {shotsForHeldSequence(ItemRegistry.PISTOL_ID),
                        shotsForTapSequence(ItemRegistry.PISTOL_ID)},
                {shotsForHeldSequence(ItemRegistry.SMG_ID),
                        shotsForTapSequence(ItemRegistry.SMG_ID)},
                {shotsForHeldSequence(ItemRegistry.RIFLE_ID),
                        shotsForTapSequence(ItemRegistry.RIFLE_ID)},
        };
        String[] names = {"手枪", "SMG", "步枪"};

        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                boolean differsOnAnyAxis =
                        table[i][0] != table[j][0] || table[i][1] != table[j][1];
                assertTrue(differsOnAnyAxis,
                        names[i] + " 与 " + names[j] + " 必须在至少一条输入序列上结果不同 —— "
                                + "实测 按住1秒/点击10下 = "
                                + table[i][0] + "/" + table[i][1] + " vs "
                                + table[j][0] + "/" + table[j][1]);
            }
        }
    }

    // ============================================================ 步枪的按下沿可靠性（裁定第 15 条）

    /**
     * 步枪：<b>按一下打一发</b>，且每次之间走满自己的 0.5 秒节流。
     *
     * <p>这是"每次 press 最多 1 发"的正向表述：三次 press → 三发，一发不多。
     */
    @Test
    void rifleFiresExactlyOneShotPerPress() {
        World world = world();
        Player player = armedWithRifle();
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        for (int shot = 1; shot <= 3; shot++) {
            combat.step(world, player, tap(), DT, CombatController.Listener.NONE);
            assertEquals(shot, combat.shotsFired(), "第 " + shot + " 次 press 必须打出一发");
            // 走满 0.5 秒节流（30 步），再点下一次
            for (int i = 0; i < 32; i++) {
                combat.step(world, player, idle(), DT, CombatController.Listener.NONE);
            }
        }
        assertEquals(7, combat.gunFor(player).magazineAmmo(), "10 − 3 = 7");
    }

    /**
     * 步枪：快速点击仍受自己的 0.5 秒节流（不吞点击、也不因点击密集而连发）。
     *
     * <p>连续 10 步都给按下沿 = 1/6 秒，远小于 0.5 秒冷却 → 只能打出 1 发。
     * 它同时排除了"把按下沿累积起来一次性倾泻"这种实现。
     */
    @Test
    void rifleRapidClicksAreThrottledByItsOwnFireRate() {
        World world = world();
        Player player = armedWithRifle();
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        for (int i = 0; i < 10; i++) {
            combat.step(world, player, tap(), DT, CombatController.Listener.NONE);
        }
        assertEquals(1, combat.shotsFired(),
                "10 步 = 1/6 秒 < 0.5 秒冷却 → 只打出 1 发（节流由 GunState 负责）");
        assertTrue(combat.gunFor(player).fireCooldownRemaining() > 0, "冷却必须仍在走");
    }

    /**
     * <b>高帧率下的 1 点击 = 1 发</b>（300 FPS 渲染 / 60 Hz 逻辑）。
     *
     * <p>模拟的是最容易出事的那半帧：点击发生在<b>没有逻辑步的渲染帧</b>上。
     * 按下沿若留在逐逻辑步复用的意图里，这一下会被下一帧整体覆盖掉（吞点击）；
     * 若被重复发放，"点一下"会变成多发。这里点击刻意落在逻辑步<b>之间</b>
     * （帧号 2 / 202 / 402，而逻辑步只在 5 的倍数帧上跑），两种错法都会让它变红。
     */
    @Test
    void rifleOneClickIsOneShotAtHighFrameRate() {
        World world = world();
        Player player = armedWithRifle();
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        FrameInputQuantities q = new FrameInputQuantities();
        int[] clickFrames = {2, 202, 402};
        int totalFrames = 620;

        for (int frame = 0; frame < totalFrames; frame++) {
            q.beginFrame();
            boolean held = false;
            boolean pressed = false;
            for (int click : clickFrames) {
                // 一次点击 = 按下沿一帧 + 电平持续 6 帧（0.02 秒）后松开
                if (frame == click) {
                    pressed = true;
                }
                if (frame >= click && frame < click + 6) {
                    held = true;
                }
            }
            if (pressed) {
                q.accumulateDiscrete(false, false, true, -1);
            }
            if (frame % RENDER_FRAMES_PER_LOGIC_STEP != 0) {
                continue; // 本渲染帧没有轮到逻辑步：帧级量必须留在容器里等
            }
            PlayerIntent base = PlayerIntent.combat(0, 0, false, 0, 0, held, false, false);
            combat.step(world, player, q.apply(base), DT, CombatController.Listener.NONE);
        }

        assertEquals(3, combat.shotsFired(),
                "300 FPS / 60 Hz 下 3 次点击必须正好打出 3 发 —— "
                        + "落在无逻辑步帧上的点击不得被吞，同一次点击也不得被重复发放");
        assertEquals(7, combat.gunFor(player).magazineAmmo(), "10 − 3 = 7");
    }

    /**
     * 步枪：一帧内多个逻辑步不得把同一次按下重复发放（v2 §14.2 第五条）。
     */
    @Test
    void riflePressIsNotRepeatedAcrossLogicStepsInOneFrame() {
        World world = world();
        Player player = armedWithRifle();
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        FrameInputQuantities q = new FrameInputQuantities();
        q.beginFrame();
        q.accumulateDiscrete(false, false, true, -1);

        PlayerIntent frameIntent = PlayerIntent.combat(0, 0, false, 0, 0, false, false, false);
        for (int step = 0; step < 3; step++) {
            combat.step(world, player, q.apply(frameIntent), DT, CombatController.Listener.NONE);
        }
        assertEquals(1, combat.shotsFired(), "一帧 3 个逻辑步，但同一次点击只开一枪");
        assertEquals(9, combat.gunFor(player).magazineAmmo(), "只消耗了 1 发");
    }

    // ============================================================ 第 15 条：禁止旁路

    /**
     * 裁定第 15 条："{@code attackPressed} 必须走既有 Frame → 一次性缓冲 → Logic tick，
     * <b>不得新增一个直接读渲染帧的布尔</b>"。
     *
     * <p>这里从源码层钉住两件事：
     * <ol>
     *   <li>{@code CombatController} 不得依赖窗口 / GLFW / 输入层的原始状态 ——
     *       它只能从 {@code PlayerIntent} 读意图。一旦有人为了"修一个时序 bug"
     *       在这里直接摸输入设备，这条会立刻变红。</li>
     *   <li>开火判据 {@code fireRequested} 的方法体<b>只能</b>出现 {@code intent}，
     *       不得出现任何别的输入来源。</li>
     * </ol>
     */
    @Test
    void noDirectRenderFrameBooleanIsReadByTheCombatLayer() {
        String combat = SourceScan.withoutComments(
                SourceScan.readMain("com/skyisland/combat/CombatController.java"));

        assertFalse(combat.contains("org.lwjgl"),
                "战斗层不得依赖窗口 / GLFW —— 它必须能脱离窗口被测试驱动");
        assertFalse(combat.contains("com.skyisland.input.InputState"),
                "战斗层不得直接读输入层原始状态 —— 那是绕过 Frame→Logic latch 的旁路");
        assertFalse(combat.contains("com.skyisland.input.InputMapper"),
                "战斗层不得直接读输入映射器 —— 同上");

        String body = SourceScan.methodBody(
                SourceScan.readMain("com/skyisland/combat/CombatController.java"),
                "private static boolean fireRequested(");
        assertTrue(body.contains("intent.attackPressed()"),
                "SINGLE 必须消费 intent.attackPressed()（按下沿）");
        assertTrue(body.contains("intent.attackHeld()"),
                "AUTO 必须消费 intent.attackHeld()（电平）");
        assertTrue(body.contains("gun.spec().fireMode()"),
                "分派依据必须是当前枪的数据 fireMode，不得按枪种硬编码");
        assertFalse(body.contains("PISTOL"),
                "开火分派里不得出现任何具体枪种的名字（否则加第四把枪要改这里）");
        assertFalse(body.contains("RIFLE"), "同上");
        assertFalse(body.contains("SMG"), "同上");
    }
}

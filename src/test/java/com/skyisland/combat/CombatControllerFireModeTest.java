package com.skyisland.combat;

import com.skyisland.entity.EntityManager;
import com.skyisland.input.FrameInputQuantities;
import com.skyisland.item.ItemRegistry;
import com.skyisland.player.Player;
import com.skyisland.player.PlayerIntent;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.ui.Localization;
import com.skyisland.world.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开火模式分派（{@code SINGLE} / {@code AUTO}）—— v2 §7.1 / §7.2 / §7.3 / §14.2 / §14.3。
 *
 * <h2>这个类守的是什么</h2>
 * M2 只有手枪，{@link CombatController#step} 直接读 {@code attackHeld}（按住即持续开火），
 * 射速由 {@code GunState} 节流。加入 SMG 后，"单发"与"全自动"必须变成两种<b>行为</b>，
 * 而不是两条数据字段：v2 §7.1 要求 SINGLE 消费 {@code attackPressed}（按下沿），
 * §7.2 要求 AUTO 消费 {@code attackHeld}（电平）。
 *
 * <p>这里每一条都是<b>行为断言</b>（数 {@code shotsFired} 与弹匣余量），不是字段透传断言 ——
 * 因为"接口上读了 attackPressed 却没把它接到开火判定上"这类错误，
 * 只断言字段是抓不住的。
 *
 * <h2>为什么必须为"帧 ⟷ 逻辑步"的粒度单独写用例</h2>
 * 逻辑步与渲染帧不是一对一：一帧可能跑 0 个或 2 个逻辑步。
 * 按下沿若处理错，会同时出现两个方向相反的症状 ——
 * 0 逻辑步的帧把点击<b>丢掉</b>（"按了左键不开火"）、
 * 一帧多个逻辑步把同一次点击<b>重复消费</b>（"点一下变连发"）。
 * 这两条正是 v2 §7.3 与 §14.2 点名要覆盖的。
 *
 * <p><b>反向验证（TEMP_REVERSE_VERIFY）</b>：把 {@link CombatController} 的
 * {@code fireRequested} 对 SINGLE 改回 {@code attackHeld}，
 * {@link #singleHeldDoesNotAutoFire()} 与
 * {@link #singleFiresOncePerPressNotPerStep()} 必须变红。
 */
class CombatControllerFireModeTest {

    /** 逻辑步长恒为 1/60 s（与 GameLoop.FIXED_DT 一致）。 */
    private static final double DT = 1.0 / 60.0;

    private static final int RELOAD_STEPS = 120;

    private static World world() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    /** 造一个手持指定枪 + 24 发后备弹药的玩家。 */
    private static Player armedPlayer(String gunStableId) {
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.inventory().add(ItemRegistry.runtimeIdOf(gunStableId), 1);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), 24);
        return player;
    }

    /** 走一次真实换弹把弹匣补满（不直接改 GunState 内部值）。 */
    private static void loadMagazine(CombatController combat, World world, Player player) {
        combat.step(world, player,
                PlayerIntent.combat(0, 0, false, 0, 0, false, false, true), DT,
                CombatController.Listener.NONE);
        PlayerIntent idle = PlayerIntent.combat(0, 0, false, 0, 0, false, false, false);
        for (int i = 0; i < RELOAD_STEPS; i++) {
            combat.step(world, player, idle, DT, CombatController.Listener.NONE);
        }
    }

    /**
     * 构造一个"按住左键"的意图。
     *
     * <p>刻意用一个工厂而不是裸构造：AUTO 只认 attackHeld、SINGLE 只认 attackPressed，
     * 用例必须能分别把两者置真 —— 若两处都置真，就分不清测试到底在测哪条路径了。
     */
    private static PlayerIntent held() {
        return PlayerIntent.combat(0, 0, false, 0, 0, true, false, false);
    }

    /** 按住左键<b>且</b>带按下沿的意图：模拟"刚按下的第一步"（真实帧级 latch 会这样发放）。 */
    private static PlayerIntent heldWithPress() {
        return PlayerIntent.combat(0, 0, false, 0, 0, true, false, false).withAttackPressed(true);
    }

    /** 什么都不按的意图。 */
    private static PlayerIntent idle() {
        return PlayerIntent.combat(0, 0, false, 0, 0, false, false, false);
    }

    // ============================================================ SINGLE（v2 §7.1 / §14.2）

    /**
     * 手枪：<b>按住左键不连发</b> —— SINGLE 必须消费"按下沿"，而不是电平。
     *
     * <p>这是 v2 §14.2 的第一条。若实现读 attackHeld，按住 20 步会打出约 5 发
     * （0.25 秒节流 × 20/60 秒 ≈ 1.3 发，但每步都尝试 → 节流一过就又打一发），
     * 本断言立刻变红。
     */
    @Test
    void singleHeldDoesNotAutoFire() {
        World world = world();
        Player player = armedPlayer(ItemRegistry.PISTOL_ID);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        int before = combat.gunFor(player).magazineAmmo();
        // 按住左键但不给按下沿：这正是"长按"在后续逻辑步里的样子
        // （按下沿只在按下的那一帧发放一次）
        for (int i = 0; i < 20; i++) {
            combat.step(world, player, held(), DT, CombatController.Listener.NONE);
        }

        assertEquals(0, combat.shotsFired(), "SINGLE 按住不放不得连发（v2 §7.1）");
        assertEquals(before, combat.gunFor(player).magazineAmmo(),
                "SINGLE 长按不得消耗弹匣");
    }

    /**
     * 手枪：<b>按一下打一发</b> —— 每次给按下沿就打出一发（v2 §14.2 第二条）。
     *
     * <p>每次之间走满射速节流（0.25 秒 = 16 步），确保"没打出来"不是因为冷却。
     */
    @Test
    void singleFiresOneShotPerAttackPressed() {
        World world = world();
        Player player = armedPlayer(ItemRegistry.PISTOL_ID);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);
        assertEquals(12, combat.gunFor(player).magazineAmmo(), "前提：手枪弹匣已补满 12 发");

        for (int shot = 1; shot <= 3; shot++) {
            combat.step(world, player, heldWithPress(), DT, CombatController.Listener.NONE);
            assertEquals(shot, combat.shotsFired(), "第 " + shot + " 次点击必须打出一发");
            // 走满 0.25 秒节流，再点下一次
            for (int i = 0; i < 17; i++) {
                combat.step(world, player, idle(), DT, CombatController.Listener.NONE);
            }
        }
        assertEquals(9, combat.gunFor(player).magazineAmmo(), "12 − 3 = 9 发打过之后剩 9");
    }

    /**
     * 手枪：同一逻辑步内<b>按住 + 按下沿同时为真</b>也只打一发。
     *
     * <p>它挡的是"把 attackHeld 与 attackPressed 用 {@code ||} 拼起来"这类实现：
     * 那样在"按下沿为真的那一步"会走两次开火路径（或退化成电平），
     * 长按就会连发。这里断言"一步最多一发"这一条不变式。
     */
    @Test
    void singleHeldAndPressedTogetherStillFiresOncePerStep() {
        World world = world();
        Player player = armedPlayer(ItemRegistry.PISTOL_ID);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        combat.step(world, player, heldWithPress(), DT, CombatController.Listener.NONE);
        assertEquals(1, combat.shotsFired(), "一步之内最多一发");

        // 接下来保持电平但不给按下沿：不得再发
        for (int i = 0; i < 30; i++) {
            combat.step(world, player, held(), DT, CombatController.Listener.NONE);
        }
        assertEquals(1, combat.shotsFired(),
                "按下沿只属于第一步；后续电平不得继续开火（否则 SINGLE 退化成 AUTO）");
    }

    /** 手枪：快速点击仍受射速节流（v2 §14.2 第三条）。 */
    @Test
    void singleRapidClicksAreStillThrottledByFireRate() {
        World world = world();
        Player player = armedPlayer(ItemRegistry.PISTOL_ID);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        // 每步都点：连续 10 步给按下沿，但 0.25 秒冷却内只应打出 1 发
        for (int i = 0; i < 10; i++) {
            combat.step(world, player, heldWithPress(), DT, CombatController.Listener.NONE);
        }
        assertEquals(1, combat.shotsFired(),
                "10 步 = 1/6 秒 < 0.25 秒冷却 → 只能打出 1 发（节流由 GunState 负责）");
        assertTrue(combat.gunFor(player).fireCooldownRemaining() > 0, "冷却必须仍在走");
    }

    /**
     * v2 §14.2 第四条：<b>0 logic-step 的渲染帧里按下的左键不能丢</b>。
     *
     * <p>走真实链路：{@code FrameInputQuantities.accumulateDiscrete}（这一帧没有逻辑步，
     * 没有 {@code apply}）→ 下一帧终于跑逻辑步 → {@code apply} 发放按下沿。
     * 这正是 v2 §7.3 要求的"复用现有 Frame→Logic 发放框架，不另造第二套脆弱逻辑"。
     */
    @Test
    void singlePressSurvivesARenderFrameWithoutLogicStep() {
        World world = world();
        Player player = armedPlayer(ItemRegistry.PISTOL_ID);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        FrameInputQuantities q = new FrameInputQuantities();

        // 第 1 帧：玩家点了左键，但这一帧没轮到逻辑步（apply 从未被调用）
        q.beginFrame();
        q.accumulateDiscrete(false, false, true, -1);
        assertTrue(q.hasPending(), "没被取走的按下沿必须还留在容器里");

        // 第 2 帧：终于跑了逻辑步 —— 这一下点击必须变成一发子弹
        q.beginFrame();
        PlayerIntent intent = q.apply(PlayerIntent.combat(0, 0, false, 0, 0, false, false, false));
        assertTrue(intent.attackPressed(), "前提：按下沿已被发放到意图上");
        combat.step(world, player, intent, DT, CombatController.Listener.NONE);

        assertEquals(1, combat.shotsFired(), "跨帧保留下来的点击必须开出一枪（v2 §7.3）");
    }

    /**
     * v2 §14.2 第五条：<b>一帧多个逻辑步不得把同一次按下重复发放</b>。
     *
     * <p>同一帧跑 3 个逻辑步（每个都用 {@code q.apply(frameIntent)} 拿意图），
     * 只有第一个逻辑步应当拿到按下沿 —— 因此总共只开一枪。
     * 若按下沿被重复发放，"点一下"会变成三发（单发半自动退化成连发）。
     */
    @Test
    void singlePressIsNotRepeatedAcrossLogicStepsInOneFrame() {
        World world = world();
        Player player = armedPlayer(ItemRegistry.PISTOL_ID);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        FrameInputQuantities q = new FrameInputQuantities();
        q.beginFrame();
        q.accumulateDiscrete(false, false, true, -1);

        PlayerIntent frameIntent = PlayerIntent.combat(0, 0, false, 0, 0, false, false, false);
        for (int step = 0; step < 3; step++) {
            combat.step(world, player, q.apply(frameIntent), DT, CombatController.Listener.NONE);
        }

        assertEquals(1, combat.shotsFired(),
                "一帧 3 个逻辑步，但同一次点击只开一枪（v2 §7.3 / §14.2）");
        assertEquals(11, combat.gunFor(player).magazineAmmo(), "只消耗了 1 发");
    }

    // ============================================================ AUTO（v2 §7.2 / §14.3）

    /**
     * SMG：按住左键<b>连续开火</b>，节奏受 fireRate 节流（v2 §14.3）。
     *
     * <p>10 发/秒 = 每发 0.1 秒 = 每 6 个逻辑步一发。
     * 推 60 步（1 秒）应打出约 10 发（首步即发，第 7/13/... 步各一发 → 10 发）。
     */
    @Test
    void autoFiresContinuouslyWhileHeld() {
        World world = world();
        Player player = armedPlayer(ItemRegistry.SMG_ID);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);
        assertEquals(24, combat.gunFor(player).magazineAmmo(), "前提：SMG 弹匣已补满 24 发");

        for (int i = 0; i < 60; i++) {
            combat.step(world, player, held(), DT, CombatController.Listener.NONE);
        }

        assertEquals(10, combat.shotsFired(),
                "1 秒 / 0.1 秒每发 = 10 发（SMG 的 fireRate 节流）");
        assertEquals(14, combat.gunFor(player).magazineAmmo(), "24 − 10 = 14");
    }

    /**
     * SMG：<b>松开左键立即停止</b>（v2 §14.3 第二条）。
     *
     * <p>先按住打出若干发，再改成不按：计数器必须停住，不再增长。
     */
    @Test
    void autoStopsImmediatelyWhenReleased() {
        World world = world();
        Player player = armedPlayer(ItemRegistry.SMG_ID);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        for (int i = 0; i < 30; i++) {
            combat.step(world, player, held(), DT, CombatController.Listener.NONE);
        }
        int afterHolding = combat.shotsFired();
        assertTrue(afterHolding > 0, "按住时必须已经打出若干发");

        for (int i = 0; i < 60; i++) {
            combat.step(world, player, idle(), DT, CombatController.Listener.NONE);
        }
        assertEquals(afterHolding, combat.shotsFired(),
                "松开左键后必须立即停止开火（v2 §14.3）");
    }

    /**
     * SMG：<b>按下沿不是它的开火条件</b> —— 只给 attackPressed（不给 attackHeld）时不开火。
     *
     * <p>它把两把枪的语义<b>朝相反方向</b>各钉一次：
     * 手枪只认按下沿（见 {@link #singleHeldDoesNotAutoFire}），
     * SMG 只认电平。若有人把两者的判据写反或写成一个 {@code ||}，必有一条变红。
     */
    @Test
    void autoIgnoresAttackPressedWithoutHeld() {
        World world = world();
        Player player = armedPlayer(ItemRegistry.SMG_ID);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        for (int i = 0; i < 30; i++) {
            // attackHeld = false，只给按下沿
            combat.step(world, player, idle().withAttackPressed(true), DT,
                    CombatController.Listener.NONE);
        }
        assertEquals(0, combat.shotsFired(),
                "AUTO 只认 attackHeld（电平）：不给电平就不开火（v2 §7.2）");
    }

    /**
     * SMG：换弹期间拒绝开火（v2 §14.3 / §12「换弹期间开火 → 拒绝」）。
     *
     * <p>并且要与手枪一致地给出 {@code MSG_RELOAD_BLOCKS_FIRE} 提示 ——
     * 这条反馈在 M2 就有，不能因为加了 AUTO 而丢。
     */
    @Test
    void autoCannotFireWhileReloading() {
        World world = world();
        Player player = armedPlayer(ItemRegistry.SMG_ID);
        CombatController combat = new CombatController(new EntityManager());
        // 不打满：让弹匣不满，换弹才能开始
        combat.step(world, player,
                PlayerIntent.combat(0, 0, false, 0, 0, false, false, true), DT,
                CombatController.Listener.NONE);
        assertTrue(combat.gunFor(player).isReloading(), "前提：已进入换弹态");

        List<String> messages = new ArrayList<>();
        CombatController.Listener recorder = new CombatController.Listener() {
            @Override
            public void onShotFired(double mx, double my, double mz, double ex, double ey,
                                    double ez, boolean hitAnything) {
            }

            @Override
            public void onBlockHit(double x, double y, double z, double nx, double ny,
                                   double nz, int blockRuntimeId) {
            }

            @Override
            public void onEntityHit(com.skyisland.entity.Entity entity, int damage,
                                    double distance) {
            }

            @Override
            public void onDryFire() {
            }

            @Override
            public void onReloadRequest(GunState.ReloadOutcome outcome) {
            }

            @Override
            public void onReloadCompleted(int magazineAmmo, int magazineSize) {
            }

            @Override
            public void onMessage(String textKey, Object... args) {
                messages.add(textKey);
            }
        };

        for (int i = 0; i < 5; i++) {
            combat.step(world, player, held(), DT, recorder);
        }
        assertEquals(0, combat.shotsFired(), "换弹期间 SMG 不得开火");
        assertTrue(messages.contains(Localization.MSG_RELOAD_BLOCKS_FIRE),
                "换弹期间按左键必须给出「换弹中不能开火」提示（M2 既有反馈不得丢失）");
    }

    // ============================================================ 共用弹药（v2 §6.1）

    /**
     * 两把枪共用 {@code skyisland:pistol_ammo}：切枪后换弹读的是同一份后备。
     *
     * <p>它同时证明"加第二把枪没有引入第二套弹药模型"（v2 §6.1），
     * 并顺带覆盖 §14.6 的"切枪后 GunState 同步"—— 两把枪各自维护弹匣，
     * 但后备弹药来自同一件 Item。
     *
     * <p><b>口径显式取 Combat Prototype（无限）</b>：本用例关心的性质是
     * "两把枪读到同一格后备 + 弹匣按枪各存一份"，与"弹药会不会被扣掉"无关。
     * 显式指定后它不再随控制器默认口径（M3 Story 8 起是 Survival）漂移 ——
     * 否则同一个用例名底下，被验的性质会从"共用弹药池"悄悄换成"消耗量"。
     */
    @Test
    void bothGunsShareTheSameAmmoItem() {
        World world = world();
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        // 手枪在槽 0、SMG 在槽 1、弹药在槽 2（add 会依次填入快捷栏 27..35）
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID), 1);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.SMG_ID), 1);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), 24);
        assertEquals(ItemRegistry.PISTOL_AMMO_ID,
                ItemRegistry.smg().gun().ammoId(),
                "SMG 的弹药必须就是手枪弹（v2 §6.1）");

        CombatController combat = new CombatController(new EntityManager());
        combat.setReserveMode(GunState.ReserveMode.PROTOTYPE);
        loadMagazine(combat, world, player);
        assertEquals(12, combat.gunFor(player).magazineAmmo(), "持手枪时弹匣 12");

        // 切到 SMG（槽 1），它的弹匣是独立的、初始为空 → 换弹补满到 24
        player.inventory().selectSlot(1);
        assertEquals(ItemRegistry.SMG_ID, player.inventory().selectedStack().item().id());
        GunState smg = combat.gunFor(player);
        assertNotNull(smg, "切到 SMG 必须能取到它自己的 GunState");
        assertEquals(24, smg.magazineSize(), "SMG 的弹匣是 24（与手枪的 12 不同）");
        assertEquals(0, smg.magazineAmmo(), "SMG 的弹匣状态是独立的（刚切过来还没上膛）");

        combat.step(world, player,
                PlayerIntent.combat(0, 0, false, 0, 0, false, false, true), DT,
                CombatController.Listener.NONE);
        // SMG 换弹 1.5 秒 = 90 步，RELOAD_STEPS 是按手枪 1.2 秒算的，因此多推几步走完它自己的时间线。
        for (int i = 0; i < RELOAD_STEPS + 20; i++) {
            combat.step(world, player, idle(), DT, CombatController.Listener.NONE);
        }
        assertEquals(24, smg.magazineAmmo(), "SMG 换弹补满到自己的 24");
        assertEquals(24, player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID),
                "PROTOTYPE 无限后备：换弹只读后备、不写背包（两把枪同口径）");

        // 切回手枪：它自己的弹匣必须还是刚才那 12 发（GunState 按枪各存一份）
        player.inventory().selectSlot(0);
        assertEquals(12, combat.gunFor(player).magazineAmmo(),
                "切回手枪时它自己的弹匣仍是 12 —— 弹匣按枪各存一份");
    }

    /** 手持非枪械时，无论按下沿还是电平都不产生任何枪械事件。 */
    @Test
    void nonGunProducesNoFireInEitherMode() {
        World world = world();
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.inventory().add(TestWorlds.stone(), 10);
        CombatController combat = new CombatController(new EntityManager());

        for (int i = 0; i < 10; i++) {
            combat.step(world, player, heldWithPress(), DT, CombatController.Listener.NONE);
        }
        assertEquals(0, combat.shotsFired());
        assertFalse(player.isDead());
    }
}

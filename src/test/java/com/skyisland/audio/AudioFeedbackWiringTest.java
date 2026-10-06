package com.skyisland.audio;

import com.skyisland.combat.CombatController;
import com.skyisland.combat.GunState;
import com.skyisland.entity.EntityManager;
import com.skyisland.entity.MeleeMonster;
import com.skyisland.item.ItemRegistry;
import com.skyisland.player.Player;
import com.skyisland.player.PlayerIntent;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AudioFeedback} 的接线测试 —— <b>"在哪一步该响什么"这条业务规则的可执行版本</b>。
 *
 * <h2>为什么必须有它</h2>
 * M2.1 真实发生过一种失败：整个 {@code audio} 包被完整实现（1600 余行、类注释详尽、
 * 单独编译通过），而游戏主类里<b>没有任何一行创建过它</b>。那种状态下玩家听不到任何声音，
 * 但这件事与"这台机器没有声卡"在日志上一模一样，既无法归因也无法在门禁里变红。
 *
 * <p>因此本类不满足于"直接调监听器方法、断言它调了 audio.play"。它<b>驱动真实的
 * {@link CombatController}</b>：喂进真实的 {@link PlayerIntent}、真实的固定步长、
 * 真实的玩家与背包。被绕开的只有 GL 与窗口 —— 与 M2 战斗自测同一口径。
 * 这样断言才落在"玩家按了左键"这一层，而不是"我调了一个我自己写的方法"。
 *
 * <h2>为什么不碰真实声卡</h2>
 * 一律 {@code open(false)}。本类关心的是"事件触发链"，不是"扬声器有没有响"；
 * 后者由门禁运行里的 OpenAL 状态行举证（见 M2.1 报告）。
 */
class AudioFeedbackWiringTest {

    private static final double DT = 1.0 / 60.0;

    /** 一次完整换弹所需的步数（1.2 s = 72 步，多给 8 步余量让浮点误差落地）。 */
    private static final int RELOAD_STEPS = 80;

    private static final PlayerIntent RELOAD_KEY =
            PlayerIntent.combat(0, 0, false, 0, 0, false, false, true);
    /**
     * 手枪的一次开火按键：v2 §7.1 起 SINGLE 消费"按下沿"，因此必须带 {@code attackPressed}。
     *
     * <p>同时保留 {@code attackHeld}（模拟手按在键上），与真实帧级 latch 的发放形态一致 ——
     * 若只给电平，手枪（SINGLE）不会开火，本类所有"打一枪听声音"的用例都会变成
     * "什么都没发生"，症状是音频计数恒为 0。
     */
    private static final PlayerIntent FIRE_KEY =
            PlayerIntent.combat(0, 0, false, 0, 0, true, false, false).withAttackPressed(true);
    private static final PlayerIntent IDLE =
            PlayerIntent.combat(0, 0, false, 0, 0, false, false, false);

    private static World world() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    /** 手持手枪 + 24 发后备弹药的玩家（与开局装备一致）。 */
    private static Player armedPlayer() {
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID), 1);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), 24);
        return player;
    }

    private static AudioManager silentAudio() {
        AudioManager audio = new AudioManager();
        audio.open(false);
        return audio;
    }

    /**
     * 把"音频 → 产品反馈"串成产品真正使用的那条链（下游用 NONE 占位）。
     *
     * @param player 持枪者 —— 枪械音效（击发 / 空仓 / 换弹）必须由他的手持枪决定，
     *               因此 2026-10-03 起 {@link AudioFeedback} 需要显式拿到玩家
     */
    private static CombatController.Listener chainFor(AudioManager audio, Player player) {
        return AudioFeedback.wrap(audio, player).andThen(CombatController.Listener.NONE);
    }

    // ============================================================ 真实链路上的映射

    @Test
    void emptyMagazineRaisesGunEmptyThroughTheRealController() {
        AudioManager audio = silentAudio();
        World world = world();
        Player player = armedPlayer();
        CombatController combat = new CombatController(new EntityManager());

        // 开局弹匣为空（PRD 5.7.1），按左键应当是"空枪"而不是"开火"
        combat.step(world, player, FIRE_KEY, DT, chainFor(audio, player));

        assertEquals(1, audio.audit().countOf(AudioEvent.GUN_EMPTY),
                "空弹匣按左键必须触发 gun_empty");
        assertEquals(0, audio.audit().countOf(AudioEvent.GUN_FIRE),
                "空枪不得触发 gun_fire（那会让玩家以为打出去了）");
    }

    @Test
    void reloadKeyRaisesReloadThroughTheRealController() {
        AudioManager audio = silentAudio();
        World world = world();
        Player player = armedPlayer();
        CombatController combat = new CombatController(new EntityManager());

        combat.step(world, player, RELOAD_KEY, DT, chainFor(audio, player));

        assertEquals(1, audio.audit().countOf(AudioEvent.RELOAD),
                "按 R 并被受理必须立刻触发 reload（反馈要落在按键那一刻，不是 1.2 秒后）");
    }

    @Test
    void reloadingAFullMagazineMakesNoSound() {
        AudioManager audio = silentAudio();
        World world = world();
        Player player = armedPlayer();
        CombatController combat = new CombatController(new EntityManager());
        CombatController.Listener chain = chainFor(audio, player);

        // 先上膛
        combat.step(world, player, RELOAD_KEY, DT, chain);
        for (int i = 0; i < RELOAD_STEPS; i++) {
            combat.step(world, player, IDLE, DT, chain);
        }
        assertEquals(1, audio.audit().countOf(AudioEvent.RELOAD));
        assertEquals(12, combat.gunFor(player).magazineAmmo(), "前提：上膛后弹匣满");

        // 满弹匣再按 R：产品口径是"无操作"，因此不得再响一声
        combat.step(world, player, RELOAD_KEY, DT, chain);
        assertEquals(1, audio.audit().countOf(AudioEvent.RELOAD),
                "满弹匣按 R 是无操作 —— 响一声会让玩家以为自己换了一次弹");
    }

    @Test
    void firingAHitEnemyRaisesBothGunFireAndHitEnemy() {
        AudioManager audio = silentAudio();
        World world = world();
        Player player = armedPlayer();
        EntityManager entities = new EntityManager();
        MeleeMonster monster = entities.spawnMeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, -5.5);
        CombatController combat = new CombatController(entities);
        CombatController.Listener chain = chainFor(audio, player);

        // 上膛
        combat.step(world, player, RELOAD_KEY, DT, chain);
        for (int i = 0; i < RELOAD_STEPS; i++) {
            combat.step(world, player, IDLE, DT, chain);
        }

        // 开枪：正前方 6 格的怪必须被命中
        combat.step(world, player, FIRE_KEY, DT, chain);

        // ★ 前提校验：先证明"这一枪真的打中了"。少了它，hit_enemy 的断言有可能
        //   因为"根本没打中所以没响"而通过一个错误的原因。
        assertEquals(12, monster.health(),
                "前提：正前方 6 格的怪必须被击中（20 − 8 = 12），否则 hit_enemy 的断言没有意义");
        assertEquals(1, audio.audit().countOf(AudioEvent.GUN_FIRE), "开火必须触发 gun_fire");
        assertEquals(1, audio.audit().countOf(AudioEvent.HIT_ENEMY),
                "命中实体必须触发 hit_enemy（它是唯一为『打中了』而存在的听觉信号）");
    }

    @Test
    void blockHitsDeliberatelyStaysSilent() {
        AudioManager audio = silentAudio();
        Player player = armedPlayer();
        AudioFeedback feedback = AudioFeedback.wrap(audio, player);

        feedback.onBlockHit(1.0, 64.0, 1.0, 0.0, 1.0, 0.0, TestWorlds.stone());

        assertEquals(0, audio.audit().size(),
                "M2.1 的五个名额不含方块命中 —— 这条断言守的是那个预算决定，"
                        + "而不是『这里忘了写』");
        assertFalse(audio.audit().everHeard(AudioEvent.HIT_ENEMY),
                "打在墙上不得发出命中怪物的音（否则玩家会回头找怪）");
    }

    // ============================================================ 与下游监听器串联

    @Test
    void andThenForwardsEveryEventToTheDownstreamListener() {
        AudioManager audio = silentAudio();
        Player player = armedPlayer();
        List<String> downstream = new ArrayList<>();
        // Listener 的 7 个方法全部是抽象的，这里逐个实现并记录（留空的方法也必须写出，
        // 否则"下游根本没收到"与"下游收到了但没记"会混在一起）
        CombatController.Listener next = new CombatController.Listener() {
            @Override
            public void onShotFired(double mx, double my, double mz,
                                    double ex, double ey, double ez, boolean hitAnything) {
                downstream.add("onShotFired");
            }

            @Override
            public void onBlockHit(double x, double y, double z,
                                   double nx, double ny, double nz, int blockRuntimeId) {
                downstream.add("onBlockHit");
            }

            @Override
            public void onEntityHit(com.skyisland.entity.Entity entity, int damage, double distance) {
                downstream.add("onEntityHit");
            }

            @Override
            public void onDryFire() {
                downstream.add("onDryFire");
            }

            @Override
            public void onReloadRequest(GunState.ReloadOutcome outcome) {
                downstream.add("onReloadRequest:" + outcome);
            }

            @Override
            public void onReloadCompleted(int magazineAmmo, int magazineSize) {
                downstream.add("onReloadCompleted");
            }

            @Override
            public void onMessage(String textKey, Object... args) {
                downstream.add("onMessage:" + textKey);
            }
        };

        CombatController.Listener chain = AudioFeedback.wrap(audio, player).andThen(next);
        chain.onShotFired(0, 0, 0, 1, 0, 0, true);
        chain.onDryFire();
        chain.onReloadRequest(GunState.ReloadOutcome.STARTED);

        // 音频侧照常发声
        assertEquals(1, audio.audit().countOf(AudioEvent.GUN_FIRE));
        assertEquals(1, audio.audit().countOf(AudioEvent.GUN_EMPTY));
        assertEquals(1, audio.audit().countOf(AudioEvent.RELOAD));

        // 下游侧一个事件都不能少 —— 用"替换"而不是"串联"会让
        // "开了音频之后粒子没了"变成一种静默缺陷
        assertEquals(List.of("onShotFired", "onDryFire", "onReloadRequest:STARTED"), downstream,
                "串联必须把全部事件原样转发给下游");
    }

    @Test
    void andThenWithNullReturnsTheAudioFeedbackItself() {
        AudioManager audio = silentAudio();
        Player player = armedPlayer();
        CombatController.Listener chain = AudioFeedback.wrap(audio, player).andThen(null);

        chain.onDryFire();

        assertEquals(1, audio.audit().countOf(AudioEvent.GUN_EMPTY),
                "下游为 null 时音频侧仍必须工作");
    }

    @Test
    void wrapRejectsNull() {
        Player player = armedPlayer();
        assertThrows(IllegalArgumentException.class, () -> AudioFeedback.wrap(null, player));
    }

    // ============================================================ 玩家受伤（轮询）

    @Test
    void theFirstHealthSampleOnlyEstablishesABaseline() {
        AudioManager audio = silentAudio();
        Player player = armedPlayer();
        AudioFeedback feedback = AudioFeedback.wrap(audio, player);

        feedback.poll(player);

        assertEquals(0, audio.audit().countOf(AudioEvent.PLAYER_HURT),
                "首次采样只记基线 —— 否则进入世界的第一个逻辑步会凭空响一声");
        assertEquals(player.health(), feedback.healthBaseline());
    }

    @Test
    void aHealthDropRaisesPlayerHurtExactlyOnce() {
        AudioManager audio = silentAudio();
        World world = world();
        Player player = armedPlayer();
        AudioFeedback feedback = AudioFeedback.wrap(audio, player);

        feedback.poll(player);
        int before = player.health();
        player.hurt(world, 3);
        assertTrue(player.health() < before, "前提：hurt 必须真的扣血");
        feedback.poll(player);

        assertEquals(1, audio.audit().countOf(AudioEvent.PLAYER_HURT), "掉血必须触发 player_hurt");

        feedback.poll(player);
        feedback.poll(player);
        assertEquals(1, audio.audit().countOf(AudioEvent.PLAYER_HURT),
                "血量不再变化时不得重复发声（若写成边沿触发，这里会涨到 3）");
    }

    @Test
    void healingDoesNotRaisePlayerHurt() {
        AudioManager audio = silentAudio();
        World world = world();
        Player player = armedPlayer();
        AudioFeedback feedback = AudioFeedback.wrap(audio, player);

        feedback.poll(player);
        player.hurt(world, 4);
        feedback.poll(player);
        assertEquals(1, audio.audit().countOf(AudioEvent.PLAYER_HURT));

        // 回血（重生）之后基线必须跟着上升，且不得发声
        player.healFull();
        feedback.poll(player);
        assertEquals(1, audio.audit().countOf(AudioEvent.PLAYER_HURT),
                "回血不是受伤，不得发声");
        assertEquals(player.health(), feedback.healthBaseline(), "基线必须跟上新的血量");
    }

    @Test
    void resettingTheBaselineMakesTheNextSampleSilentAgain() {
        AudioManager audio = silentAudio();
        World world = world();
        Player player = armedPlayer();
        AudioFeedback feedback = AudioFeedback.wrap(audio, player);

        feedback.poll(player);
        player.hurt(world, 5);
        feedback.poll(player);
        assertEquals(1, audio.audit().countOf(AudioEvent.PLAYER_HURT));

        feedback.resetHealthBaseline();
        feedback.poll(player);
        assertEquals(1, audio.audit().countOf(AudioEvent.PLAYER_HURT),
                "重置基线后那一次采样只记基线（读档/重生时防止跨会话串起掉血事件）");
    }

    @Test
    void pollingNullIsAHarmlessNoOp() {
        AudioManager audio = silentAudio();
        Player player = armedPlayer();
        AudioFeedback feedback = AudioFeedback.wrap(audio, player);

        feedback.poll(null);

        assertEquals(0, audio.audit().size());
    }

    // ============================================================ 顺序与"不吞事件"

    /**
     * 串联链必须<b>先发声、后转发</b>，且七个回调一个都不能被吞。
     *
     * <p><b>这条断言抓的是什么：</b>{@link AudioFeedback#andThen} 有两种写错的方式 ——
     * ① "替换"而不是"串联"（下游收不到，表现为"开了音频之后曳光没了"）；
     * ② 顺序颠倒（先转发再发声，表现为"听到声音时画面已经结算完了"）。
     * 两者都不会让编译失败，也都不会让"声音能听见"变红。
     *
     * <p>判据不靠计数，而是<b>在转发的那一刻读一次音频侧已经记下几条</b>
     * （{@code audioSizeAtDelegate}）。顺序一旦颠倒，第一个元素会从 1 变成 0。
     * 期望序列 {@code [1,2,3,4,4,4,4]} 同时也是"哪几个事件该发声"的书面记录：
     * 击发 / 命中 / 空仓 / 换弹四个有声，换弹完成、方块命中、文案提示三个无声。
     * （M2.2：原先"换弹取消"也是无声事件之一；该回调已随"移动打断换弹"废止，
     * 因此这里从 8 个回调 / 8 个期望值收紧为 7 个。）
     */
    @Test
    void andThenPlaysTheSoundBeforeDelegatingAndForwardsAllSevenEvents() {
        AudioManager audio = silentAudio();
        Player player = armedPlayer();
        AudioFeedback feedback = AudioFeedback.wrap(audio, player);
        List<String> order = new ArrayList<>();
        List<Integer> audioSizeAtDelegate = new ArrayList<>();

        CombatController.Listener chained = feedback.andThen(new CombatController.Listener() {
            private void record(String tag) {
                order.add(tag);
                audioSizeAtDelegate.add(audio.audit().size());
            }

            @Override
            public void onShotFired(double mx, double my, double mz,
                                    double ex, double ey, double ez, boolean hitAnything) {
                record("shot");
            }

            @Override
            public void onBlockHit(double x, double y, double z,
                                   double nx, double ny, double nz, int blockRuntimeId) {
                record("block");
            }

            @Override
            public void onEntityHit(com.skyisland.entity.Entity entity, int damage, double distance) {
                record("entity");
            }

            @Override
            public void onDryFire() {
                record("dry");
            }

            @Override
            public void onReloadRequest(GunState.ReloadOutcome outcome) {
                record("reloadReq");
            }

            @Override
            public void onReloadCompleted(int magazineAmmo, int magazineSize) {
                record("reloadDone");
            }

            @Override
            public void onMessage(String textKey, Object... args) {
                record("message");
            }
        });

        chained.onShotFired(0, 0, 0, 1, 1, 1, true);
        chained.onEntityHit(null, 8, 6);
        chained.onDryFire();
        chained.onReloadRequest(GunState.ReloadOutcome.STARTED);
        chained.onReloadCompleted(12, 12);
        chained.onBlockHit(0, 0, 0, 0, 1, 0, 1);
        chained.onMessage("k");

        // 音频侧：四个有声事件各响一次，三个无声事件一次都不响
        assertEquals(1, audio.audit().countOf(AudioEvent.GUN_FIRE));
        assertEquals(1, audio.audit().countOf(AudioEvent.HIT_ENEMY));
        assertEquals(1, audio.audit().countOf(AudioEvent.GUN_EMPTY));
        assertEquals(1, audio.audit().countOf(AudioEvent.RELOAD));
        assertEquals(4, audio.audit().size(),
                "M2.1 的五个音里，这七个回调只该产生四条记录");

        // 下游侧：七个事件一个不少、次序原样
        assertEquals(List.of("shot", "entity", "dry", "reloadReq",
                        "reloadDone", "block", "message"),
                order, "串联必须把七个事件全部原样转发给下游，且次序不变");

        // 顺序：转发的那一刻，该响的已经响完了
        assertEquals(List.of(1, 2, 3, 4, 4, 4, 4), audioSizeAtDelegate,
                "发声必须发生在转发之前：顺序颠倒时首位会是 0");
    }

    /**
     * 一场最基本的交火，听觉序列的<b>可执行形式</b>。
     *
     * <p>这条断言就是 M2.1 通过标准"能听见战斗"本身。它不检查任何内部状态，
     * 只读最终记录下来的事件序列 —— 因此它无法因为"我调了一个我自己写的方法"而变绿。
     *
     * <p><b>注意序列以 RELOAD 开头，这是对的而不是噪声：</b>PRD 5.7.1 规定开局弹匣为空，
     * 所以任何"打出一发"的完整过程都必然以一次换弹开始。删掉这个前缀，
     * 这条断言就会退化成"我假设弹匣是满的"——而那正是 M2 可发现性缺口里
     * {@code combat_shots_fired = 0} 的成因。
     */
    @Test
    void aFullCombatExchangeProducesTheExpectedSequence() {
        World world = world();
        Player player = armedPlayer();
        EntityManager entities = new EntityManager();
        entities.spawnMeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, -5.5);
        CombatController combat = new CombatController(entities);

        AudioManager audio = silentAudio();
        AudioFeedback feedback = AudioFeedback.wrap(audio, player);
        CombatController.Listener chain = feedback.andThen(CombatController.Listener.NONE);

        // ① 换弹（开局弹匣为空）
        combat.step(world, player, RELOAD_KEY, DT, chain);
        for (int i = 0; i < RELOAD_STEPS; i++) {
            combat.step(world, player, IDLE, DT, chain);
        }
        assertEquals(1, audio.audit().countOf(AudioEvent.RELOAD), "前提：上膛确实发生且只响一次");

        // ② 打光 12 发中的 3 发（每发之间等过射速节流）
        for (int shot = 0; shot < 3; shot++) {
            combat.step(world, player, FIRE_KEY, DT, chain);
            for (int i = 0; i < 16; i++) {
                combat.step(world, player, IDLE, DT, chain);
            }
        }

        // ③ 玩家挨一下
        feedback.poll(player);
        player.hurt(world, 3);
        feedback.poll(player);

        assertEquals("RELOAD>GUN_FIRE>HIT_ENEMY>GUN_FIRE>HIT_ENEMY>GUN_FIRE>HIT_ENEMY>PLAYER_HURT",
                audio.audit().idSequence(),
                "一场最基本的交火的完整听觉序列 —— 这条断言是 M2.1 通过标准「能听见战斗」的可执行形式");
        assertFalse(audio.audit().everHeard(AudioEvent.GUN_EMPTY), "还有弹药时不该响空仓");
        assertEquals(1, audio.audit().countOf(AudioEvent.RELOAD), "这一场只在开头换了一次弹");
        assertEquals(3, audio.audit().countOf(AudioEvent.HIT_ENEMY),
                "三发都必须真的打中（12 → 4）：命中数少一发就说明弹道或射速节流被改坏了");
    }
}

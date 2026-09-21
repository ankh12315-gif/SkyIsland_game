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
    private static final PlayerIntent FIRE_KEY =
            PlayerIntent.combat(0, 0, false, 0, 0, true, false, false);
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

    /** 把"音频 → 产品反馈"串成产品真正使用的那条链（下游用 NONE 占位）。 */
    private static CombatController.Listener chainFor(AudioManager audio) {
        return AudioFeedback.wrap(audio).andThen(CombatController.Listener.NONE);
    }

    // ============================================================ 真实链路上的映射

    @Test
    void emptyMagazineRaisesGunEmptyThroughTheRealController() {
        AudioManager audio = silentAudio();
        World world = world();
        Player player = armedPlayer();
        CombatController combat = new CombatController(new EntityManager());

        // 开局弹匣为空（PRD 5.7.1），按左键应当是"空枪"而不是"开火"
        combat.step(world, player, FIRE_KEY, DT, chainFor(audio));

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

        combat.step(world, player, RELOAD_KEY, DT, chainFor(audio));

        assertEquals(1, audio.audit().countOf(AudioEvent.RELOAD),
                "按 R 并被受理必须立刻触发 reload（反馈要落在按键那一刻，不是 1.2 秒后）");
    }

    @Test
    void reloadingAFullMagazineMakesNoSound() {
        AudioManager audio = silentAudio();
        World world = world();
        Player player = armedPlayer();
        CombatController combat = new CombatController(new EntityManager());
        CombatController.Listener chain = chainFor(audio);

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
        CombatController.Listener chain = chainFor(audio);

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
        AudioFeedback feedback = AudioFeedback.wrap(audio);

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
        List<String> downstream = new ArrayList<>();
        // Listener 的 8 个方法全部是抽象的，这里逐个实现并记录（留空的方法也必须写出，
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
            public void onReloadCancelled() {
                downstream.add("onReloadCancelled");
            }

            @Override
            public void onMessage(String textKey, Object... args) {
                downstream.add("onMessage:" + textKey);
            }
        };

        CombatController.Listener chain = AudioFeedback.wrap(audio).andThen(next);
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
        CombatController.Listener chain = AudioFeedback.wrap(audio).andThen(null);

        chain.onDryFire();

        assertEquals(1, audio.audit().countOf(AudioEvent.GUN_EMPTY),
                "下游为 null 时音频侧仍必须工作");
    }

    @Test
    void wrapRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> AudioFeedback.wrap(null));
    }

    // ============================================================ 玩家受伤（轮询）

    @Test
    void theFirstHealthSampleOnlyEstablishesABaseline() {
        AudioManager audio = silentAudio();
        AudioFeedback feedback = AudioFeedback.wrap(audio);
        Player player = armedPlayer();

        feedback.poll(player);

        assertEquals(0, audio.audit().countOf(AudioEvent.PLAYER_HURT),
                "首次采样只记基线 —— 否则进入世界的第一个逻辑步会凭空响一声");
        assertEquals(player.health(), feedback.healthBaseline());
    }

    @Test
    void aHealthDropRaisesPlayerHurtExactlyOnce() {
        AudioManager audio = silentAudio();
        AudioFeedback feedback = AudioFeedback.wrap(audio);
        World world = world();
        Player player = armedPlayer();

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
        AudioFeedback feedback = AudioFeedback.wrap(audio);
        World world = world();
        Player player = armedPlayer();

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
        AudioFeedback feedback = AudioFeedback.wrap(audio);
        World world = world();
        Player player = armedPlayer();

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
        AudioFeedback feedback = AudioFeedback.wrap(audio);

        feedback.poll(null);

        assertEquals(0, audio.audit().size());
    }
}

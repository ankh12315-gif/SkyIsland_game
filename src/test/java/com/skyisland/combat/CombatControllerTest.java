package com.skyisland.combat;

import com.skyisland.entity.Entity;
import com.skyisland.entity.EntityManager;
import com.skyisland.entity.MeleeMonster;
import com.skyisland.item.ItemRegistry;
import com.skyisland.player.Player;
import com.skyisland.player.PlayerIntent;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.ui.Localization;
import com.skyisland.world.World;
import com.skyisland.world.block.BlockRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 战斗闭环测试：{@link CombatController} 把"意图 + 手持物 + 世界 + 实体"
 * 变成"开火 / 换弹 / 命中结算"。
 *
 * <p><b>为什么这一层必须有自己的测试，而不是靠"启动游戏打一枪看看"：</b>
 * PRD 12.3 给 M2 的通过标准里有四条是<u>数值与规则</u>（单发伤害 8、换弹 1.2 秒、
 * 最近命中不得穿墙、距离衰减 ≤32 格 100% / 每格 ×0.9 / 下限 20%）。
 * 靠肉眼试玩无法证明"衰减恰好是 0.9 的幂"或"隔着一格石头打不中"，
 * 也无法在改参数之后立刻发现回归。这里的每一条断言都直接对应一条通过标准，
 * 并把它钉在一个确定性的世界与确定的步数上。
 *
 * <p><b>与窗口无关：</b>本类只驱动 {@code Player} / {@code World} / {@code Entity}，
 * 不碰 GL，因此在无窗口环境（CI）下可完整运行 —— 与 {@code PlayerPhysicsTest}
 * 的取向一致（本机确认无法向窗口注入合成输入，见 TECH_DESIGN_v0.1.1 §T′ TR7）。
 */
class CombatControllerTest {

    /** 逻辑步长恒为 1/60 s（与 GameLoop.FIXED_DT 一致）。 */
    private static final double DT = 1.0 / 60.0;

    /**
     * 把一次换弹走完的步数。
     *
     * <p>1.2 秒 = 72 步，但倒计时是"每步累减 1/60"，浮点误差可能让第 72 步还剩
     * 1e-16 秒。测试关心的是"1.2 秒后确实完成了"，不是"恰好第 72 步完成" ——
     * 后者由 {@code GunState} 自己的精确边界测试负责（见 CombatCoreTest）。
     */
    private static final int RELOAD_STEPS = 80;

    private static World world() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    /** 一个足够大的世界，用来验证"超出有效射程"的距离衰减。 */
    private static World bigWorld() {
        return TestWorlds.flatWorld(-3, -3, 3, 3);
    }

    /** 造一个"手持手枪 + 24 发后备弹药"的玩家（与开局装备一致）。 */
    private static Player armedPlayer(double x, double y, double z) {
        Player player = new Player(x, y, z);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID), 1);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), 24);
        return player;
    }

    /** 无事件记录器：只需要"不会 NPE"的场景用它。 */
    private static final CombatController.Listener QUIET = CombatController.Listener.NONE;

    /**
     * 手枪的一次"开火"意图。
     *
     * <p><b>v2 §7.1 起这里是 {@code attackPressed}（按下沿）而不是 {@code attackHeld}（电平）：</b>
     * 手枪是 {@code SINGLE}，只认按下沿。本类里所有"打一枪看看结果"的用例都走这个工厂，
     * 于是"手枪不再是按住就发"这件事只需要在一处表达。
     * 按下沿之外仍然带上 {@code attackHeld}（模拟"手还按在键上"），
     * 这正是真实帧级 latch 发放那一步的样子。
     */
    private static PlayerIntent firePistol() {
        return PlayerIntent.combat(0, 0, false, 0, 0, true, false, false).withAttackPressed(true);
    }

    // ============================================================ 事件记录器

    /**
     * 把监听器回调记下来，供断言读取。
     *
     * <p>用记录器而不是"让 controller 返回一个结果对象"的理由见
     * {@link CombatController.Listener} 的说明；这里额外保存 {@code textKeys}
     * （而不是拼好的文案），因此断言不依赖具体中文措辞 ——
     * 改文案不会让业务测试变红，而"该不该提示"仍然可断言。
     */
    private static final class Recorder implements CombatController.Listener {
        int shots;
        int blockHits;
        int entityHits;
        int dryFires;
        final List<GunState.ReloadOutcome> reloadRequests = new ArrayList<>();
        int reloadCompleted;

        /** 本步的曳光：{mx,my,mz,ex,ey,ez,hitAnything}。 */
        double[] lastTracer;
        double[] lastHitPoint;
        double[] lastHitNormal;
        int lastBlockRuntimeId = -1;
        Entity lastEntity;
        int lastDamage;
        double lastDistance;
        final List<String> textKeys = new ArrayList<>();

        @Override
        public void onShotFired(double mx, double my, double mz,
                                double ex, double ey, double ez, boolean hitAnything) {
            shots++;
            lastTracer = new double[]{mx, my, mz, ex, ey, ez, hitAnything ? 1 : 0};
        }

        @Override
        public void onBlockHit(double x, double y, double z,
                               double nx, double ny, double nz, int blockRuntimeId) {
            blockHits++;
            lastHitPoint = new double[]{x, y, z};
            lastHitNormal = new double[]{nx, ny, nz};
            lastBlockRuntimeId = blockRuntimeId;
        }

        @Override
        public void onEntityHit(Entity entity, int damage, double distance) {
            entityHits++;
            lastEntity = entity;
            lastDamage = damage;
            lastDistance = distance;
        }

        @Override
        public void onDryFire() {
            dryFires++;
        }

        @Override
        public void onReloadRequest(GunState.ReloadOutcome outcome) {
            reloadRequests.add(outcome);
        }

        @Override
        public void onReloadCompleted(int magazineAmmo, int magazineSize) {
            reloadCompleted++;
        }

        @Override
        public void onMessage(String textKey, Object... args) {
            textKeys.add(textKey);
        }
    }

    private static void step(CombatController combat, World world, Player player,
                             PlayerIntent intent, Recorder recorder) {
        combat.step(world, player, intent, DT, recorder);
    }

    /** 把弹匣打满（走一次真实换弹，不直接改 GunState 内部值）。 */
    private static void loadMagazine(CombatController combat, World world, Player player) {
        PlayerIntent reload = PlayerIntent.combat(0, 0, false, 0, 0, false, false, true);
        step(combat, world, player, reload, new Recorder());
        PlayerIntent idle = PlayerIntent.combat(0, 0, false, 0, 0, false, false, false);
        for (int i = 0; i < RELOAD_STEPS; i++) {
            step(combat, world, player, idle, new Recorder());
        }
    }

    // ============================================================ 通过标准 2 / 6：伤害与衰减

    @Test
    void singleShotDealsEightDamageAtEffectiveRange() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        EntityManager entities = new EntityManager();
        MeleeMonster monster = entities.spawnMeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, -5.5);
        CombatController combat = new CombatController(entities);
        loadMagazine(combat, world, player);

        Recorder recorder = new Recorder();
        step(combat, world, player,
                firePistol(), recorder);

        assertEquals(1, recorder.shots, "按住左键且弹匣有弹必须击发");
        assertEquals(1, recorder.entityHits, "正前方 6 格的怪必须被打中");
        assertSame(monster, recorder.lastEntity);
        assertEquals(8, recorder.lastDamage, "PRD 12.3：手枪单发伤害 8（有效射程内不衰减）");
        assertEquals(12, monster.health(), "20 − 8 = 12");
        assertEquals(1, combat.entityHits());
        assertEquals(8, combat.totalDamageDealt());
    }

    @Test
    void damageFallsOffAtNinetyPercentPerBlockBeyondRange() {
        World world = bigWorld();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        EntityManager entities = new EntityManager();
        // 有效射程 32 格，这里放到 45 格外（世界够大，避免落在未加载区块上）
        MeleeMonster monster = entities.spawnMeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, -45.5);
        CombatController combat = new CombatController(entities);
        loadMagazine(combat, world, player);

        Recorder recorder = new Recorder();
        step(combat, world, player,
                firePistol(), recorder);

        assertEquals(1, recorder.entityHits, "45 格仍在 32 格射程的射线可达范围内，只是衰减了");
        int expected = DamageFalloff.damage(8, recorder.lastDistance, 32);
        assertEquals(expected, recorder.lastDamage,
                "超出有效射程后必须按 ×0.9/格 衰减，且下限 20%");
        assertTrue(recorder.lastDamage < 8, "45 格处的伤害必须低于基础伤害 8");
        assertEquals(0.9, DamageFalloff.MULTIPLIER_PER_BLOCK, 1e-12, "每超 1 格 ×0.9");
        assertEquals(0.20, DamageFalloff.MIN_MULTIPLIER, 1e-12, "最低退至 20%");
        assertEquals(1.0, DamageFalloff.multiplier(32, 32), 1e-12, "射程内不衰减");
    }

    // ============================================================ 通过标准 4：最近命中 / 不得穿墙

    @Test
    void cannotShootThroughASolidWall() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        EntityManager entities = new EntityManager();
        MeleeMonster monster = entities.spawnMeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, -5.5);
        CombatController combat = new CombatController(entities);
        loadMagazine(combat, world, player);

        // 在玩家与怪物之间砌一堵 2 格高的墙（玩家眼高 1.62 → 必须挡住 (0,64) 与 (0,65)）
        int stone = TestWorlds.stone();
        assertTrue(world.applySavedBlock(0, TestWorlds.SURFACE_BLOCK_Y + 1, -3, (short) stone));
        assertTrue(world.applySavedBlock(0, TestWorlds.SURFACE_BLOCK_Y + 2, -3, (short) stone));

        Recorder recorder = new Recorder();
        step(combat, world, player,
                firePistol(), recorder);

        assertEquals(1, recorder.shots, "墙不影响击发");
        assertEquals(0, recorder.entityHits, "PRD 12.2：最近命中是方块时不得结算实体伤害");
        assertEquals(20, monster.health(), "隔着实体方块不能命中怪物");
        assertEquals(1, recorder.blockHits, "这一枪应记为方块命中（溅射粒子 + 撞击反馈）");
        assertEquals(0, combat.entityHits());
    }

    @Test
    void blockHitFeedbackCarriesPointNormalAndBlockId() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        // 向下 60° 看：射线会落在前方地面方块的上表面，命中面法线必须是 +Y。
        // 用"向下看"而不是"水平看"是因为平坦世界的地表只在脚下 ——
        // 水平射线会一路飞出已加载区块，那条路径测不到"命中方块"。
        player.camera().setAngles(0, -60);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        Recorder recorder = new Recorder();
        step(combat, world, player,
                firePistol(), recorder);

        assertNotNull(recorder.lastHitPoint, "命中方块必须给出溅射粒子的落点");
        assertEquals(1, recorder.blockHits);
        assertEquals(1.0, recorder.lastHitNormal[1], 1e-9, "打在地表上 → 面法线为 +Y");
        assertEquals(0.0, recorder.lastHitNormal[0], 1e-9);
        assertEquals(0.0, recorder.lastHitNormal[2], 1e-9);
        // 溅射粒子要用"被命中方块的颜色"作为数据来源，因此 id 必须是一个真实方块
        assertTrue(recorder.lastBlockRuntimeId > 0,
                "命中方块必须带出有效的方块 runtimeId（粒子取色用）");
        assertFalse(BlockRegistry.byRuntimeId(recorder.lastBlockRuntimeId).isAir(),
                "取色的方块不能是空气");
    }

    @Test
    void tracerEndsAtTheHitPointWhenSomethingIsHit() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        EntityManager entities = new EntityManager();
        entities.spawnMeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, -5.5);
        CombatController combat = new CombatController(entities);
        loadMagazine(combat, world, player);

        Recorder recorder = new Recorder();
        step(combat, world, player,
                firePistol(), recorder);

        double[] t = recorder.lastTracer;
        assertNotNull(t);
        assertEquals(1.0, t[6], 1e-9, "打中了东西 → hitAnything 为真");
        // 曳光终点 = 射线终点（M2 无枪械模型，曳光自眼睛出发）
        assertEquals(player.eyePosition().x, t[0], 1e-9);
        assertEquals(player.eyePosition().y, t[1], 1e-9);
        assertEquals(player.eyePosition().z, t[2], 1e-9);
        // 怪物在 z = −5.5，终点必须落在它附近，而不是画到 32 格之外
        assertTrue(t[5] > -6.0 && t[5] < -4.5,
                "曳光应结束在命中点附近，实际 z=" + t[5]);
    }

    @Test
    void tracerStillExistsWhenNothingIsHit() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        // 抬头看天：这条射线不会命中任何方块或实体
        player.camera().setAngles(0, 60);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        Recorder recorder = new Recorder();
        step(combat, world, player,
                firePistol(), recorder);

        assertEquals(1, recorder.shots);
        assertEquals(0, recorder.entityHits);
        assertEquals(0, recorder.blockHits);
        assertEquals(0.0, recorder.lastTracer[6], 1e-9, "打空 → hitAnything 为假");

        // 打空也要有曳光，否则玩家分不清"没打中"与"没开枪"。
        // 终点必须落在最大射程处，因此"枪口 → 终点"的距离应等于手枪的有效射程 32 格。
        double[] t = recorder.lastTracer;
        double dx = t[3] - t[0];
        double dy = t[4] - t[1];
        double dz = t[5] - t[2];
        double ray = CombatController.rayRange(ItemRegistry.pistol().gun());
        assertEquals(ray, Math.sqrt(dx * dx + dy * dy + dz * dz), 1e-6,
                "打空时曳光必须画到射线末端（见 RAY_RANGE_MULTIPLIER 的说明）");
        assertTrue(ray > ItemRegistry.pistol().gun().range(),
                "射线必须长于有效射程，否则 PRD 的距离衰减规则永远不可达");
        assertTrue(dy > 0, "抬头射出的曳光终点必须在枪口上方");
    }

    // ============================================================ 通过标准 3：换弹 1.2 秒

    @Test
    void reloadTakesTwelveTenthsOfASecondAndLeavesTheReserveAlone() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        CombatController combat = new CombatController(new EntityManager());
        // 本用例验的是"换弹耗时 + 完成前不转移"这两条<b>与口径无关</b>的规则
        // （见方法名：leavesTheReserveAlone 描述的是原型口径那一半）。
        // 因此显式选 Combat Prototype 口径，而不是依赖控制器的默认值 ——
        // M3 Story 8 把默认值收紧成了 Survival，若这里不显式指定，
        // 本用例会从"验无限不扣弹"悄悄变成"验有限扣弹"，而方法名仍在说相反的话。
        combat.setReserveMode(GunState.ReserveMode.PROTOTYPE);

        GunState gun = combat.gunFor(player);
        assertNotNull(gun, "手持手枪必须能取到枪械状态");
        assertEquals(0, gun.magazineAmmo(), "开局弹匣为空（需要先上一次膛）");
        assertEquals(12, gun.magazineSize());
        assertEquals(1.2, gun.spec().reloadSeconds(), 1e-9, "PRD 12.3：换弹耗时 1.2 秒");
        assertTrue(gun.reserveInfinite(), "显式选定的 PROTOTYPE 口径 = 无限后备");

        Recorder recorder = new Recorder();
        PlayerIntent reload = PlayerIntent.combat(0, 0, false, 0, 0, false, false, true);
        PlayerIntent idle = PlayerIntent.combat(0, 0, false, 0, 0, false, false, false);

        step(combat, world, player, reload, recorder);
        assertEquals(List.of(GunState.ReloadOutcome.STARTED), recorder.reloadRequests);
        assertEquals(0, gun.magazineAmmo(), "规则④：完成前不得提前转移弹药");
        int reserveBefore = player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID);
        assertEquals(24, reserveBefore);

        for (int i = 0; i < RELOAD_STEPS; i++) {
            step(combat, world, player, idle, recorder);
        }
        assertEquals(1, recorder.reloadCompleted);
        assertEquals(12, gun.magazineAmmo(), "补满到弹匣容量 12");
        assertEquals(24, player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID),
                "PROTOTYPE 无限后备：换弹只读后备、不写背包（有限口径的对应断言见 "
                        + "survivalReloadSpendsExactlyTheAmmoItLoads）");
    }

    /**
     * M2.2 修订：<b>边走边换</b> —— 换弹期间持续给"移动"意图，换弹照样走完全程。
     *
     * <h2>这条用例替代了什么</h2>
     * M2.2 之前这里是 {@code movingCancelsReloadAndChangesNothing}：按 R 之后推 10 步移动意图，
     * 断言 {@code gun.isReloading() == false} 且收到 {@code onReloadCancelled}
     * （旧 PRD 5.4.3「移动打断换弹 = 取消换弹」）。该规则已被废止，
     * PRD 5.4.3 改写为「移动不打断换弹」，{@code CombatController.step} 也移除了
     * {@code boolean moving = intent.hasMovement()} 与随后的取消分支。
     *
     * <p><b>为什么这条必须放在 {@code CombatController} 这一层：</b>
     * 只有这里能拿到 {@link PlayerIntent} —— 也就是说只有在这里，"移动"才是一个
     * 能被喂进去的输入。{@code GunState} 的 {@code tick} 已不再接收任何移动信号
     * （它连这个参数都没有了，见 {@code CombatCoreTest#reloadRunsToCompletionOnAFixedStepClock}）。
     * 两级测试合起来才覆盖了完整的主张：底层"换弹只由时间驱动"，
     * 上层"移动意图喂进来也不会改变这条时间线"。
     *
     * <p>刺激序列刻意保持与旧用例一致（按 R → 一路推移动意图），
     * 只把期望反过来：旧期望"被取消"，新期望"走完全程"。
     * <b>反向验证</b>：把 {@code if (intent.hasMovement()) { gun.cancelReload(); ... }} 放回
     * {@code step}，{@code reloadCompleted} 会停在 0、弹匣停在 0 —— 本用例立刻变红。
     */
    @Test
    void walkingDoesNotInterruptReload() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        CombatController combat = new CombatController(new EntityManager());
        // 口径显式取 PROTOTYPE：本用例验的是"移动不打断换弹"这条时间线性质，
        // 与"弹药从哪来"无关。显式指定后，它不会随控制器默认口径（Survival）漂移。
        combat.setReserveMode(GunState.ReserveMode.PROTOTYPE);
        GunState gun = combat.gunFor(player);

        Recorder recorder = new Recorder();
        step(combat, world, player,
                PlayerIntent.combat(0, 0, false, 0, 0, false, false, true), recorder);
        assertEquals(List.of(GunState.ReloadOutcome.STARTED), recorder.reloadRequests);
        assertTrue(gun.isReloading(), "按 R 之后必须进入换弹态");
        assertEquals(0, gun.magazineAmmo(), "规则④：完成前不得提前转移弹药");

        // 边走边换：全程推"按住 W"的移动意图（这是旧口径下会取消换弹的刺激）。
        PlayerIntent walking = PlayerIntent.combat(1f, 0, false, 0, 0, false, false, false);
        for (int i = 0; i < RELOAD_STEPS / 2; i++) {
            step(combat, world, player, walking, recorder);
        }
        assertTrue(gun.isReloading(), "走过一半路程时换弹必须仍在进行（1.2 秒还没走满）");
        assertEquals(0, gun.magazineAmmo(), "换弹中途弹药仍未转移（规则④）");

        for (int i = 0; i < RELOAD_STEPS - RELOAD_STEPS / 2; i++) {
            step(combat, world, player, walking, recorder);
        }
        assertEquals(1, recorder.reloadCompleted, "边走边换：换弹必须走完全程（旧口径下会被取消 → 0）");
        assertFalse(gun.isReloading(), "走完之后换弹态应当退出");
        assertEquals(12, gun.magazineAmmo(), "边走边换完成后弹匣补满 12");
        // 全程没有任何"取消"这件事可发生 —— 该事件在 M2.2 已从监听器接口删除，
        // 因此这里只能断言它的对立面：完成事件恰好到了一次，弹药该在的地方都在。
        assertEquals(24, player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID),
                "PROTOTYPE 无限后备：边走边换同样只读后备、不写背包");
    }

    /**
     * Combat Prototype / Debug 口径（v2 §5.3、§19-7）：无限后备下"部分填充"不发生 ——
     * 背包里几乎没弹也能把弹匣补满，且背包里的那几发一发不动。
     *
     * <p>本用例在 M2.1 之前叫 {@code partialReloadLoadsOnlyWhatTheReserveHas}，
     * 断言的是 PRD 5.4.3 规则③（{@code load = min(缺口, 后备)}）。
     * 规则③本身没有作废，它在正式玩法口径（Survival）下是常态，由
     * {@link CombatCoreTest#partialReloadFillsExactlyWhatIsAvailable} 与
     * {@link #survivalReloadSpendsExactlyTheAmmoItLoads} 守着；
     * 这里守的是 Debug 口径下"玩家不会被弹药卡住"。
     *
     * <p><b>口径必须显式指定：</b>Story 8 之后控制器的默认值已是 Survival，
     * 不再指定就会读到有限口径、与用例名说着相反的事。
     */
    @Test
    void infiniteReserveFillsTheMagazineEvenWithAlmostNoAmmoInTheBag() {
        World world = world();
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID), 1);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), 5);
        CombatController combat = new CombatController(new EntityManager());
        combat.setReserveMode(GunState.ReserveMode.PROTOTYPE);

        Recorder recorder = new Recorder();
        PlayerIntent reload = PlayerIntent.combat(0, 0, false, 0, 0, false, false, true);
        PlayerIntent idle = PlayerIntent.combat(0, 0, false, 0, 0, false, false, false);
        step(combat, world, player, reload, recorder);
        for (int i = 0; i < RELOAD_STEPS; i++) {
            step(combat, world, player, idle, recorder);
        }

        GunState gun = combat.gunFor(player);
        assertEquals(12, gun.magazineAmmo(), "PROTOTYPE：无限后备下弹匣直接补满 12，不再部分填充");
        assertEquals(5, player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID),
                "背包里的 5 发一发不少（换弹只读后备）");
        assertEquals(1, recorder.reloadCompleted);
    }

    @Test
    void fullMagazineReloadIsANoOp() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        Recorder recorder = new Recorder();
        step(combat, world, player,
                PlayerIntent.combat(0, 0, false, 0, 0, false, false, true), recorder);
        assertEquals(List.of(GunState.ReloadOutcome.ALREADY_FULL), recorder.reloadRequests,
                "v0.3.2 已废止「满弹匣换弹」：满弹匣按 R 无操作");
        assertTrue(recorder.textKeys.contains(Localization.MSG_MAGAZINE_FULL));
    }

    /**
     * Debug / Prototype 口径：把背包里的弹全部抽干之后，按 R <b>仍然</b>能换弹
     * （v2 §19-7「Debug 模式仍可无限备弹」）。
     *
     * <p>本用例在 M2.1 之前是
     * {@code fullMagazineReloadIsANoOpAndZeroReserveIsRefused} 的后半段，
     * 断言的是"后备为 0 → NO_RESERVE + 提示『没有后备弹药』"。
     * 无限后备使那条路径不可达。M3 Story 8 之后，那条路径在<b>正式玩法</b>里是常态
     * （见 {@link #survivalRefusesReloadOnceTheAmmoPoolIsDry}），
     * 而本用例守的是另一侧：调试口径下 NO_RESERVE 必须仍然不可达 ——
     * 否则"原型阶段连续试玩在第 25 发之后中断"会重新出现，那正是 M2.1-A 要消除的东西。
     *
     * <p><b>口径显式指定（Story 8 前它依赖的是控制器的默认值）</b>：
     * 显式之后本用例验的是"Debug 口径仍然无限"，而不是"默认值恰好是无限"。
     */
    @Test
    void emptiedInventoryStillReloadsUnderInfiniteReserve() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        CombatController combat = new CombatController(new EntityManager());
        combat.setReserveMode(GunState.ReserveMode.PROTOTYPE);
        loadMagazine(combat, world, player);

        Recorder recorder = new Recorder();
        // 抽干后备，再打掉一发让弹匣不满
        player.inventory().consumeItem(ItemRegistry.PISTOL_AMMO_ID,
                player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID));
        assertEquals(0, player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID), "前提：后备已抽干");
        step(combat, world, player,
                firePistol(), recorder);
        assertEquals(11, combat.gunFor(player).magazineAmmo());

        // 按 R：无限后备下必须被受理
        step(combat, world, player,
                PlayerIntent.combat(0, 0, false, 0, 0, false, false, true), recorder);
        assertEquals(GunState.ReloadOutcome.STARTED,
                recorder.reloadRequests.get(recorder.reloadRequests.size() - 1),
                "Debug 口径：无限后备下空背包也能换弹（NO_RESERVE 不可达，v2 §19-7）");
        assertFalse(recorder.textKeys.contains(Localization.MSG_NO_RESERVE),
                "无限后备下不得出现「没有后备弹药」提示");

        // 走完换弹
        for (int i = 0; i < RELOAD_STEPS; i++) {
            step(combat, world, player,
                    PlayerIntent.combat(0, 0, false, 0, 0, false, false, false), recorder);
        }
        assertEquals(12, combat.gunFor(player).magazineAmmo(), "换弹完成后弹匣满");
        assertEquals(0, player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID),
                "背包本来就是空的，换完仍然是空的 —— 换弹只读后备、不写背包");
    }

    // ============================================================ 空枪 / 非枪 / 死亡

    @Test
    void emptyMagazineReportsDryFireAndNeverSpendsReserve() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        CombatController combat = new CombatController(new EntityManager());

        Recorder recorder = new Recorder();
        step(combat, world, player,
                firePistol(), recorder);

        assertEquals(0, recorder.shots, "弹匣为空时不得击发");
        assertEquals(1, recorder.dryFires);
        assertEquals(24, player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID),
                "空枪不得动后备弹药");
        assertTrue(recorder.textKeys.contains(Localization.MSG_OUT_OF_AMMO),
                "PRD 6.7：弹药为 0 时提示「弹药不足」");
        assertEquals(1, combat.dryFires());
    }

    @Test
    void holdingSomethingOtherThanAGunProducesNoCombatEventsAtAll() {
        World world = world();
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.inventory().add(TestWorlds.stone(), 10);
        CombatController combat = new CombatController(new EntityManager());

        Recorder recorder = new Recorder();
        for (int i = 0; i < 10; i++) {
            step(combat, world, player,
                    PlayerIntent.combat(0, 0, false, 0, 0, true, true, true), recorder);
        }

        assertEquals(0, recorder.shots);
        assertEquals(0, recorder.dryFires, "手持方块按左键是挖掘，不是空枪");
        assertTrue(recorder.textKeys.isEmpty(), "手持非枪械不得产生任何枪械提示");
        assertNull(combat.gunFor(player));
    }

    @Test
    void deadPlayerDoesNotAdvanceOrSpendTheGun() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);
        assertEquals(12, combat.gunFor(player).magazineAmmo());

        player.hurt(world, 999);
        assertTrue(player.isDead());

        Recorder recorder = new Recorder();
        for (int i = 0; i < 10; i++) {
            step(combat, world, player,
                    firePistol(), recorder);
        }
        assertEquals(0, recorder.shots, "倒下期间不得继续开火");
        assertEquals(12, combat.existingGun(player).magazineAmmo(), "倒下期间不得消耗弹药");
    }

    // ============================================================ 死亡移除

    @Test
    void monsterDiesAfterEnoughHitsAndIsRemovedByTheManager() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        EntityManager entities = new EntityManager();
        MeleeMonster monster = entities.spawnMeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, -5.5);
        CombatController combat = new CombatController(entities);
        loadMagazine(combat, world, player);

        Recorder recorder = new Recorder();
        PlayerIntent fire = firePistol();
        PlayerIntent idle = PlayerIntent.combat(0, 0, false, 0, 0, false, false, false);

        // 20 点生命 / 每发 8 点 → 3 发。射速 4 发/秒 → 每发之间等 0.25 秒 = 15 步，
        // 这里推 17 步：多走两步是为了让"冷却恰好走完"这件事不依赖浮点边界
        // （射速节流本身由 CombatCoreTest 的精确用例负责）。
        assertEquals(20, monster.health());
        step(combat, world, player, fire, recorder);
        assertEquals(1, combat.shotsFired());
        assertEquals(12, monster.health(), "第 1 发：20 − 8");
        for (int i = 0; i < 17; i++) {
            step(combat, world, player, idle, recorder);
        }

        step(combat, world, player, fire, recorder);
        assertEquals(2, combat.shotsFired());
        assertEquals(4, monster.health(), "第 2 发：12 − 8");
        for (int i = 0; i < 17; i++) {
            step(combat, world, player, idle, recorder);
        }

        step(combat, world, player, fire, recorder);
        assertEquals(3, combat.shotsFired());
        assertEquals(0, monster.health(), "第 3 发：生命归零");
        assertFalse(monster.isAlive());

        // 清理发生在 EntityManager 的帧末，而不是 hurt() 里 ——
        // 这样"本步刚死"的实体在同一个逻辑步里仍然可被读到（避免 foreach 中途改集合）。
        entities.tick(world, player, DT);
        assertEquals(0, entities.aliveCount(), "生命归零的实体必须被清理");
        assertEquals(0, entities.size());
        assertEquals(1, entities.totalRemoved());
    }
}

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
        int reloadCancelled;

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
        public void onReloadCancelled() {
            reloadCancelled++;
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
                PlayerIntent.combat(0, 0, false, 0, 0, true, false, false), recorder);

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
                PlayerIntent.combat(0, 0, false, 0, 0, true, false, false), recorder);

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
                PlayerIntent.combat(0, 0, false, 0, 0, true, false, false), recorder);

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
                PlayerIntent.combat(0, 0, false, 0, 0, true, false, false), recorder);

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
                PlayerIntent.combat(0, 0, false, 0, 0, true, false, false), recorder);

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
                PlayerIntent.combat(0, 0, false, 0, 0, true, false, false), recorder);

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

        GunState gun = combat.gunFor(player);
        assertNotNull(gun, "手持手枪必须能取到枪械状态");
        assertEquals(0, gun.magazineAmmo(), "开局弹匣为空（需要先上一次膛）");
        assertEquals(12, gun.magazineSize());
        assertEquals(1.2, gun.spec().reloadSeconds(), 1e-9, "PRD 12.3：换弹耗时 1.2 秒");
        assertTrue(gun.reserveInfinite(), "M2.1：产品默认口径是无限后备");

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
        // M2.1-A：无限后备下换弹不从背包扣弹，24 发<b>一发不少</b>。
        // 改动前这条断言是 12（24 − 12），它随产品口径一起变，不是被放宽后凑绿。
        assertEquals(24, player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID),
                "M2.1 无限后备：换弹只读后备、不写背包");
    }

    @Test
    void movingCancelsReloadAndChangesNothing() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        CombatController combat = new CombatController(new EntityManager());
        GunState gun = combat.gunFor(player);

        Recorder recorder = new Recorder();
        step(combat, world, player,
                PlayerIntent.combat(0, 0, false, 0, 0, false, false, true), recorder);
        assertTrue(gun.isReloading());

        // 边动边换弹：推 10 步
        for (int i = 0; i < 10; i++) {
            step(combat, world, player,
                    PlayerIntent.combat(1f, 0, false, 0, 0, false, false, false), recorder);
        }
        assertFalse(gun.isReloading(), "PRD 5.4.3：移动打断换弹");
        assertEquals(1, recorder.reloadCancelled);
        // "取消"必须等价于"什么都没发生"：弹药从未提前转移，因此不需要任何回滚
        assertEquals(0, gun.magazineAmmo(), "弹匣内弹药回到换弹前的状态");
        assertEquals(24, player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID),
                "后备弹药一发都不能少");
    }

    /**
     * M2.1-A：无限后备下"部分填充"不再发生 —— 背包里几乎没弹也能把弹匣补满，
     * 且背包里的那几发一发不动。
     *
     * <p>本用例在 M2.1 之前叫 {@code partialReloadLoadsOnlyWhatTheReserveHas}，
     * 断言的是 PRD 5.4.3 规则③（{@code load = min(缺口, 后备)}）。
     * 规则③本身没有作废，它只是不再走产品的默认口径，
     * 因此那条语义改由 {@link CombatCoreTest#partialReloadFillsExactlyWhatIsAvailable}
     * 用显式构造的有限口径继续守 —— 这里守的是"产品口径下玩家不会被弹药卡住"。
     */
    @Test
    void infiniteReserveFillsTheMagazineEvenWithAlmostNoAmmoInTheBag() {
        World world = world();
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_ID), 1);
        player.inventory().add(ItemRegistry.runtimeIdOf(ItemRegistry.PISTOL_AMMO_ID), 5);
        CombatController combat = new CombatController(new EntityManager());

        Recorder recorder = new Recorder();
        PlayerIntent reload = PlayerIntent.combat(0, 0, false, 0, 0, false, false, true);
        PlayerIntent idle = PlayerIntent.combat(0, 0, false, 0, 0, false, false, false);
        step(combat, world, player, reload, recorder);
        for (int i = 0; i < RELOAD_STEPS; i++) {
            step(combat, world, player, idle, recorder);
        }

        GunState gun = combat.gunFor(player);
        assertEquals(12, gun.magazineAmmo(), "M2.1：无限后备下弹匣直接补满 12，不再部分填充");
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
     * M2.1-A：把背包里的弹全部抽干之后，按 R <b>仍然</b>能换弹。
     *
     * <p>本用例在 M2.1 之前是
     * {@code fullMagazineReloadIsANoOpAndZeroReserveIsRefused} 的后半段，
     * 断言的是"后备为 0 → NO_RESERVE + 提示『没有后备弹药』"。
     * 无限后备使那条路径不可达，因此这里断言的是<b>反面</b>：
     * 换弹必须能开始、必须能补满、背包必须仍然是空的，且
     * {@link Localization#MSG_NO_RESERVE} <b>不得</b>出现 ——
     * 如果它出现了，说明有人把口径改回了有限，而"改回有限"会让原型阶段的
     * 连续试玩在第 25 发之后中断，那正是 M2.1-A 要消除的东西。
     */
    @Test
    void emptiedInventoryStillReloadsUnderInfiniteReserve() {
        World world = world();
        Player player = armedPlayer(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        CombatController combat = new CombatController(new EntityManager());
        loadMagazine(combat, world, player);

        Recorder recorder = new Recorder();
        // 抽干后备，再打掉一发让弹匣不满
        player.inventory().consumeItem(ItemRegistry.PISTOL_AMMO_ID,
                player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID));
        assertEquals(0, player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID), "前提：后备已抽干");
        step(combat, world, player,
                PlayerIntent.combat(0, 0, false, 0, 0, true, false, false), recorder);
        assertEquals(11, combat.gunFor(player).magazineAmmo());

        // 按 R：无限后备下必须被受理
        step(combat, world, player,
                PlayerIntent.combat(0, 0, false, 0, 0, false, false, true), recorder);
        assertEquals(GunState.ReloadOutcome.STARTED,
                recorder.reloadRequests.get(recorder.reloadRequests.size() - 1),
                "M2.1：无限后备下空背包也能换弹（NO_RESERVE 不可达）");
        assertFalse(recorder.textKeys.contains(Localization.MSG_NO_RESERVE),
                "无限后备下不得再出现「没有后备弹药」提示");

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
                PlayerIntent.combat(0, 0, false, 0, 0, true, false, false), recorder);

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
                    PlayerIntent.combat(0, 0, false, 0, 0, true, false, false), recorder);
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
        PlayerIntent fire = PlayerIntent.combat(0, 0, false, 0, 0, true, false, false);
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

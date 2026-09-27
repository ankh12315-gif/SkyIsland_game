package com.skyisland.combat;

import com.skyisland.entity.Entity;
import com.skyisland.entity.EntityManager;
import com.skyisland.item.FireMode;
import com.skyisland.item.GunSpec;
import com.skyisland.item.Item;
import com.skyisland.item.ItemRegistry;
import com.skyisland.physics.RaycastHit;
import com.skyisland.player.Player;
import com.skyisland.player.PlayerIntent;
import com.skyisland.ui.Localization;
import com.skyisland.util.Log;
import com.skyisland.world.World;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;

/**
 * 枪械玩法接线（M2）：把"意图 + 手持物 + 世界 + 实体"变成 "开火 / 换弹 / 命中结算"。
 *
 * <h2>它为什么单独成类，而不是写在 SkyIslandGame 里</h2>
 * 主循环类里已经混了窗口、菜单、设置、自测、测量五件事。战斗结算若也进去，
 * 就只能靠"启动一个真窗口、刷一只怪、对着它开一枪"来验证 ——
 * 而本项目的既定做法恰恰相反：把可判定的逻辑挤到不依赖 GL 与窗口的类里，
 * 再用确定性测试举证（{@code Player}、{@code EntityManager}、{@code GunState} 都是这么做的）。
 *
 * <h2>为什么用监听器而不是"返回一个结果 record"</h2>
 * 一步之内可能同时发生"命中方块 + 打空弹匣 + 换弹完成"。
 * 塞进一个 record 会得到一个二十来个组件的位置参数表 —— 那正是
 * {@code PlayerIntentCopyTest} 专门在防的一类错误（相邻字段写反且编译器沉默）。
 * 监听器把"发生了什么"拆成互不相干的事件，每个事件的参数都在 6 个以内，
 * 测试里实现一个记录用的监听器即可断言全部行为。
 *
 * <h2>依赖方向</h2>
 * 本类<b>不认识渲染层</b>：粒子、曳光、音效全部由监听器的实现方（游戏主循环）
 * 决定。因此 {@code combat} 包不依赖 {@code render} 包，表现层换实现也不影响这里。
 */
public final class CombatController {

    /** 战斗事件接收方。测试用 {@link #NONE} 或自建记录器。 */
    public interface Listener {

        /**
         * 成功击发。曳光从枪口画到 {@code (endX, endY, endZ)}。
         *
         * @param hitAnything 是否打中了东西（方块或实体）—— 打空时曳光应画到最大射程处
         */
        void onShotFired(double muzzleX, double muzzleY, double muzzleZ,
                         double endX, double endY, double endZ, boolean hitAnything);

        /** 命中方块：在命中点沿面法线溅射（法线指向射线来向，与 {@link RaycastHit} 同口径）。 */
        void onBlockHit(double x, double y, double z,
                        double nx, double ny, double nz, int blockRuntimeId);

        /** 命中实体，伤害已结算（{@code entity} 的受击闪白由 {@code Entity.hurt} 自己维护）。 */
        void onEntityHit(Entity entity, int damage, double distance);

        /** 弹匣为空：播放空枪反馈并提示「弹药不足」。 */
        void onDryFire();

        /**
         * 一次<b>换弹请求</b>的结果（玩家按了 R）。
         *
         * <p>只表示"这次按键被受理成什么"，<b>不表示换弹已经完成</b> ——
         * 完成走下面的 {@link #onReloadCompleted(int, int)}。把两件事合成一个回调的话，
         * "已受理"与"已完成"会共用一个枚举，迟早有人把 {@code STARTED} 当成完成。
         */
        void onReloadRequest(GunState.ReloadOutcome outcome);

        /** 换弹走到终点，弹药已转移进弹匣（PRD 5.4.3 规则④：只在完成这一刻转移）。 */
        void onReloadCompleted(int magazineAmmo, int magazineSize);

        /**
         * 需要显示给玩家的一行短提示。
         *
         * <p><b>参数是"文案 key + 占位符实参"，不是已经拼好的字符串</b>：
         * PRD 6.7 硬性禁止"在 Java UI 代码里散落中文字符串字面量"。
         * 由本类拼字符串就等于把文案散落在 combat 包里；
         * 交出 key，由游戏层查 {@code Localization} 取文案，则文案只有一份来源。
         * 至于为什么 combat 会引用 {@code Localization} 的 key 常量：
         * i18n key 是所有层共享的词汇表，允许引用 key 不等于允许引用文案。
         */
        void onMessage(String textKey, Object... args);

        /** 什么都不做的实现：不关心反馈的调用方与测试用。 */
        Listener NONE = new Listener() {
            @Override
            public void onShotFired(double muzzleX, double muzzleY, double muzzleZ,
                                    double endX, double endY, double endZ, boolean hitAnything) {
            }

            @Override
            public void onBlockHit(double x, double y, double z,
                                   double nx, double ny, double nz, int blockRuntimeId) {
            }

            @Override
            public void onEntityHit(Entity entity, int damage, double distance) {
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
            }
        };
    }

    private final EntityManager entities;

    /**
     * 射线长度相对"有效射程"的倍率。
     *
     * <p><b>这不是可调的审美参数，而是"衰减规则能否发生"的前提。</b>
     * PRD 5.4.3 同时规定了两件事：
     * <ul>
     *   <li>手枪的有效射程 = 32 格（"有效"= 100% 伤害的那一段）；</li>
     *   <li>「有效射程内 100% 伤害；<b>超出后</b>每超 1 格伤害 ×0.9，最低退至 20%」。</li>
     * </ul>
     * 如果射线长度就等于有效射程，那么"超出有效射程的目标"<b>根本不会被射线碰到</b>，
     * 第二条规则永远不会执行 —— 它会变成一段读起来很合理、却永远跑不到的代码，
     * 而"距离衰减"这条 M2 通过标准也就无从举证。
     * （这正是给本类写第一版测试时暴露出来的问题：把怪放在 45 格处，一枪打出去
     * 什么都没发生 —— 不是衰减算错了，是射线根本没到。）
     *
     * <p>因此射线必须比有效射程更长。<b>取 2 倍的理由：</b>
     * {@code 0.9^32 ≈ 3.4%}，早已撞到 20% 的下限 —— 也就是说从 32 格延伸到 64 格的
     * 过程中，整条衰减曲线（含下限截断）都会被走到，再延长只会白白增加整数步进次数。
     */
    public static final double RAY_RANGE_MULTIPLIER = 2.0;

    /** 一次射击的射线长度 = 有效射程 × {@link #RAY_RANGE_MULTIPLIER}。 */
    public static double rayRange(GunSpec spec) {
        return spec.range() * RAY_RANGE_MULTIPLIER;
    }

    /**
     * 每把枪一份运行时状态，按<b>物品 runtimeId</b> 索引。
     *
     * <p>为什么不只存一份：切槽走开再切回来，弹匣必须是"刚才那半匣"。
     * 只存一份的话，切成方块再切回枪，弹匣会被重置 —— 而这既不符合直觉，
     * 也让"换弹 → 切枪 → 切回"这条最容易被玩家试出来的路径失去意义。
     * M2 只有一把枪，但按 id 索引的成本只是一个小 Map。
     */
    private final Map<Integer, GunState> gunStates = new HashMap<>();

    /**
     * 本局的后备弹药口径（v2 §5.3：由上层显式传入的 Combat Rule / Run Mode 配置）。
     *
     * <h2>为什么这个开关在控制器上，而不是在 {@link GunState} 里留一个默认值</h2>
     * M2.1 把"无限后备"做成了 {@code GunState} 的全局默认常量。那样一来，
     * "正式玩法是什么口径"这件事只能有一个取值 —— 而 M3 要求<b>两种口径同时存在</b>：
     * 正式 Survival 有限（v2 §19-6）、战斗原型 / 调试无限（v2 §19-7）。
     * 于是本类把口径收成一个字段：{@link #gunFor} 建枪状态时按<b>当前口径</b>传入，
     * Debug 模式只需要在装配期把它换成 {@link GunState.ReserveMode#PROTOTYPE}。
     *
     * <p><b>默认值是 {@link GunState.ReserveMode#SURVIVAL}（有限）</b>：
     * 这是 M3 的正式游戏口径 —— 不显式配置就是正式玩法，而不是"不配置就送无限炮弹"。
     * 想拿无限，必须像 {@code M2CombatSelfTest} 那样<b>显式声明自己是原型</b>。
     */
    private GunState.ReserveMode reserveMode = GunState.ReserveMode.SURVIVAL;

    /**
     * 设定本局的后备弹药口径，并<b>丢弃已建出的枪状态</b>。
     *
     * <p><b>为什么必须同时清状态：</b>{@code GunState.reserveMode} 是 {@code final}
     * （一次会话里口径只能有一个答案，见 {@code GunState} 类注释），
     * 已建出的状态不会跟着改。若只改字段不清状态，症状会是
     * "装配期设了口径、玩家手里那把枪却仍是旧口径" —— 一个只在时序上看得出来、
     * 靠读代码几乎看不出的失效。清掉之后，下一次 {@link #gunFor} 会用新口径重建。
     *
     * <p>它在装配期被调用（{@code SkyIslandGame} 在自测对象建好之后、主循环之前），
     * 那时还没有任何枪状态，因此"清空"是无副作用的。
     */
    public void setReserveMode(GunState.ReserveMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("后备弹药口径不得为 null（v2 §5.3：必须显式指定）");
        }
        this.reserveMode = mode;
        gunStates.clear();
    }

    /** 当前后备弹药口径（自测 / 测试据此断言"正式口径是有限、Debug 口径是无限"）。 */
    public GunState.ReserveMode reserveMode() {
        return reserveMode;
    }

    // ---- 统计（自测断言用：只能由"真的发生了"来推进） ----
    private int shotsFired;
    private int dryFires;
    private int blockHits;
    private int entityHits;
    private int totalDamageDealt;
    private int lastDamage;
    private double lastDistance;

    public CombatController(EntityManager entities) {
        this.entities = entities;
    }

    // ============================================================ 主更新

    /**
     * 推进一个逻辑步的枪械逻辑。
     *
     * <p><b>顺序与理由：</b>
     * <ol>
     *   <li><b>先 tick 枪械（含换弹推进）</b>：换弹完成这件事必须在本步的
     *       开火判定<b>之前</b>结算，否则"换弹刚好完成的同一帧按左键"会打不出去 ——
     *       而玩家看到的是弹匣已经满了；</li>
     *   <li><b>再处理换弹请求</b>：先于开火，因为同一帧按 R 与左键时，
     *       "开始换弹"优先（换弹期间本来就不允许开枪，反过来会让这次换弹请求被吃掉）；</li>
     *   <li><b>最后开火</b>。</li>
     * </ol>
     */
    public void step(World world, Player player, PlayerIntent intent,
                     double dt, Listener listener) {
        GunState gun = gunFor(player);
        if (gun == null) {
            return;
        }

        // ① 推进时间：换弹进度只由时间决定，移动不打断（PRD 5.4.3，M2.2 修订）
        int completedBefore = gun.reloadsCompleted();
        gun.tick(dt, player.inventory());
        if (gun.reloadsCompleted() > completedBefore) {
            listener.onReloadCompleted(gun.magazineAmmo(), gun.magazineSize());
            listener.onMessage(Localization.MSG_RELOAD_DONE);
        }

        // ② 换弹请求
        if (intent.reloadPressed()) {
            GunState.ReloadOutcome outcome = gun.tryStartReload(player.inventory());
            listener.onReloadRequest(outcome);
            switch (outcome) {
                case STARTED -> listener.onMessage(Localization.MSG_RELOADING);
                case ALREADY_FULL -> listener.onMessage(Localization.MSG_MAGAZINE_FULL);
                case NO_RESERVE -> listener.onMessage(Localization.MSG_NO_RESERVE);
                case ALREADY_RELOADING -> { /* 已在换弹，重复按键不提示 */ }
            }
        }

        // ③ 开火：按当前枪的开火模式取"这一次是否要尝试击发"（v2 §7.1 / §7.2）
        //
        // M2 只有手枪（SINGLE），当时这里读的是 attackHeld（按住即持续开火），
        // 射速由 GunState 节流。v2 §7.1 明确改写：SINGLE 必须消费"按下沿" attackPressed ——
        // 长按左键只会按射速反复走"新一次开火"，那是连发而不是半自动。
        // AUTO（SMG）保留原来的 attackHeld 语义：按住按 fireRate 连发。
        //
        // 分派依据是**当前枪的数据**（spec.fireMode），因此本类对"是不是手枪"零感知：
        // 再加第三把枪时这里一行都不用改。
        if (!fireRequested(intent, gun)) {
            return;
        }
        GunState.ShotOutcome shot = gun.tryFire();
        switch (shot) {
            case FIRED -> resolveShot(world, player, gun, listener);
            case NO_AMMO -> {
                dryFires++;
                listener.onDryFire();
                listener.onMessage(Localization.MSG_OUT_OF_AMMO);
            }
            case COOLDOWN -> { /* 节流中：不是玩家可感知的事件，不产生任何反馈 */ }
            case RELOADING -> listener.onMessage(Localization.MSG_RELOAD_BLOCKS_FIRE);
        }
    }

    /**
     * 本逻辑步"是否要尝试开火"，由当前枪的 {@link GunSpec#fireMode()} 决定。
     *
     * <ul>
     *   <li>{@link FireMode#SINGLE} —— 只认<b>按下沿</b> {@link PlayerIntent#attackPressed()}：
     *       一次点击最多一发，长按不会连发（v2 §7.1）；</li>
     *   <li>{@link FireMode#AUTO} —— 认<b>电平</b> {@link PlayerIntent#attackHeld()}：
     *       按住即持续尝试，实际节奏由 {@code GunState} 按 {@code fireRate} 节流（v2 §7.2）。</li>
     * </ul>
     *
     * <p><b>为什么按下沿必须来自 intent 而不是在这里自己记边沿：</b>
     * 逻辑步与渲染帧的粒度不匹配（一帧可能 0 个或多个逻辑步）。
     * 在本类里记"上一逻辑步按没按"会把"一帧多逻辑步"变成"只发一发"、
     * 把"0 逻辑步的帧"变成"整次点击被丢掉"。按下沿的正确来源是帧级 latch
     * （{@code FrameInputQuantities}），它保证"一个物理点击最多发放一次、
     * 不因 0 逻辑步的帧丢失、不因一帧多逻辑步重复发放"（v2 §7.3）。
     */
    private static boolean fireRequested(PlayerIntent intent, GunState gun) {
        return switch (gun.spec().fireMode()) {
            case SINGLE -> intent.attackPressed();
            case AUTO -> intent.attackHeld();
        };
    }

    /**
     * 结算一次已击发的射击：射线 → 最近命中 → 伤害 / 粒子 → 曳光。
     *
     * <p><b>射线起点是眼睛而不是枪口模型位置</b>：M2 没有枪械模型，
     * 而挖掘与放置用的都是 {@code Player.raycastTarget} 的"眼睛 → 视线方向"射线。
     * 让射击共用同一个口径，就不存在"准星指着 A、子弹打到 B"这类两套射线不一致的问题 ——
     * 这正是 M1 已经解决过一次的事（准星即射线）。
     */
    private void resolveShot(World world, Player player, GunState gun, Listener listener) {
        shotsFired++;
        Vector3d origin = player.eyePosition();
        Vector3d direction = new Vector3d(player.camera().forward()).normalize();
        double range = gun.spec().range();
        double ray = rayRange(gun.spec());

        Hitscan.Result result = Hitscan.resolve(world, origin, direction, ray, entities.all());

        // 曳光终点：打中了就是命中点，没打中就是最大射程处
        // （"打空也要有曳光"是必要的：没有它，玩家无法区分"没打中"与"没开枪"）
        double endDistance = result.hitAnything() ? result.distance() : ray;
        double endX = origin.x + direction.x * endDistance;
        double endY = origin.y + direction.y * endDistance;
        double endZ = origin.z + direction.z * endDistance;
        listener.onShotFired(origin.x, origin.y, origin.z, endX, endY, endZ, result.hitAnything());

        if (result.hitEntity()) {
            Entity target = result.entity();
            int damage = DamageFalloff.damage(gun.spec().damage(), result.distance(), range);
            target.hurt(damage);
            entityHits++;
            totalDamageDealt += damage;
            lastDamage = damage;
            lastDistance = result.distance();
            listener.onEntityHit(target, damage, result.distance());
            Log.info("[战斗] 命中 %s：距离 %.2f 格 → 伤害 %d（基础 %d），目标生命 %d/%d",
                    target.typeId(), result.distance(), damage, gun.spec().damage(),
                    target.health(), target.maxHealth());
        } else if (result.blockHit() != null) {
            RaycastHit blockHit = result.blockHit();
            blockHits++;
            lastDistance = result.distance();
            listener.onBlockHit(endX, endY, endZ,
                    blockHit.faceNormalX(), blockHit.faceNormalY(), blockHit.faceNormalZ(),
                    blockHit.blockRuntimeId());
        }
    }

    // ============================================================ 状态

    /**
     * 取当前手持枪械对应的运行时状态；手持物不是枪时返回 {@code null}。
     *
     * <p>枪的运行时状态<b>随手持物惰性创建</b>：不做"开局给所有枪建状态"，
     * 因为那样会让"玩家还没拿到枪就先有一份弹匣状态"这种中间状态存在。
     *
     * <p><b>M3 Story 8：建状态时按本局口径传入 {@link #reserveMode}。</b>
     * 这一行就是"正式玩法是有限后备、Debug 是无限后备"的落地点 ——
     * 它不再读 {@code GunState} 的全局默认值，而是读本控制器显式配置的口径，
     * 因此两条口径可以同时存在于同一个进程里（自测走无限、正式玩法走有限）。
     */
    public GunState gunFor(Player player) {
        if (player == null || player.isDead()) {
            // 倒下期间不推进枪械：死亡必须真的冻结操作（PRD 5.3）。
            // 不挡这一条的话，"临死前按住左键"会在倒计时里继续消耗弹药并画曳光。
            return null;
        }
        int runtimeId = player.inventory().selectedStack().itemRuntimeId();
        Item item = ItemRegistry.byRuntimeId(runtimeId);
        if (item == null || !item.isGun()) {
            return null;
        }
        return gunStates.computeIfAbsent(runtimeId, id -> new GunState(item, reserveMode));
    }

    /**
     * 只读查询：当前手持枪械的运行时状态。<b>不创建、不产生副作用。</b>
     *
     * <p>与 {@link #gunFor} 分成两个方法，是因为它俩的调用场景对"副作用"的要求相反：
     * 玩法步进需要"第一次拿枪时把状态建出来"，而 HUD 每渲染帧都会读一次 ——
     * 那是在渲染路径上，任何对象创建都是不该发生的（3000 FPS 下就是每秒 3000 次），
     * 而且会给"玩家从未持有过的枪"凭空建出一份弹匣状态。
     */
    public GunState existingGun(Player player) {
        if (player == null) {
            return null;
        }
        return gunStates.get(player.inventory().selectedStack().itemRuntimeId());
    }

    /** 当前弹匣内弹药（未持枪时为 0）。 */
    public int magazineAmmo(Player player) {
        GunState gun = gunFor(player);
        return gun == null ? 0 : gun.magazineAmmo();
    }

    public int shotsFired() {
        return shotsFired;
    }

    public int dryFires() {
        return dryFires;
    }

    public int blockHits() {
        return blockHits;
    }

    public int entityHits() {
        return entityHits;
    }

    public int totalDamageDealt() {
        return totalDamageDealt;
    }

    /** 最近一次命中的伤害（打中实体时有效）。 */
    public int lastDamage() {
        return lastDamage;
    }

    /** 最近一次命中的距离（格）。 */
    public double lastDistance() {
        return lastDistance;
    }

    /** 清空全部枪械状态（自测脚本重置 / 读档时调用）。 */
    public void resetGuns() {
        gunStates.clear();
    }
}

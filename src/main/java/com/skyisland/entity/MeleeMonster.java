package com.skyisland.entity;

import com.skyisland.player.Player;
import com.skyisland.util.Coords;
import com.skyisland.util.Log;
import com.skyisland.world.World;

/**
 * 近战怪（PRD 5.5.1 / 5.5.2）。MVP 唯一的怪物。
 *
 * <p><b>7 步行为逐条落地：</b>
 * <ol>
 *   <li>检测玩家 —— 水平距离 ≤ {@link #CHASE_RANGE}；</li>
 *   <li>进入范围后追击；</li>
 *   <li>朝玩家<b>水平方向</b>移动（不做垂直瞄准，不跳跃）；</li>
 *   <li>前方碰撞 → 依次尝试 ±45° / ±90° / ±135° 偏转；</li>
 *   <li>前方为虚空 → <b>不踏入</b>（{@link #wouldFallIntoVoid}）；</li>
 *   <li>接近后攻击（水平 ≤ {@link #ATTACK_RANGE} <b>且</b> 竖直 Δy ≤ {@link #ATTACK_VERTICAL_RANGE}，冷却 {@link #ATTACK_COOLDOWN_SECONDS}）；</li>
 *   <li>无法到达时不做全地图寻路（允许卡住）。</li>
 * </ol>
 *
 * <p><b>为什么"不踏入虚空"要单独判而不用"能不能站立"：</b>
 * 两者在空岛地形上几乎等价，但语义不同 —— 第 5 步禁止的是"自己走进虚空"，
 * 而"前方有台阶所以过不去"属于第 7 步允许的卡住。
 * 用 {@code hasCollisionAt(前方脚下)} 只回答前者，不会把台阶误判成虚空而触发无谓的偏转。
 *
 * <p>第 7 步的意义需要说清楚：MVP <u>故意</u>不做寻路。
 * 体素世界里玩家可以实时挖墙、搭桥、封路，任何基于静态网格的寻路都会立刻失效，
 * 而实时重算的代价与收益不成比例。所以"怪物被两格高的墙挡住"不是 bug，是设计边界；
 * Alpha 引入更多怪种时再一并处理更完整的寻路。
 */
public final class MeleeMonster extends Entity {

    public static final String TYPE_ID = "skyisland:melee_monster";

    /** PRD 5.5.1：生命 20。 */
    public static final int MAX_HEALTH = 20;

    /** PRD 5.5.1：每次攻击伤害 4。 */
    public static final int ATTACK_DAMAGE = 4;

    /** PRD 5.5.1：移动速度 2.0 格/秒。 */
    public static final double MOVE_SPEED = 2.0;

    /** 追击范围（格）。PRD 未给值，取 24 —— 【MVP 内测值，需试玩调优】。 */
    public static final double CHASE_RANGE = 24.0;

    /** 攻击距离（格）：碰撞箱半宽 0.3 + 玩家半宽 0.3 + 容差。 */
    public static final double ATTACK_RANGE = 1.6;

    /**
     * 攻击判定的<b>竖直</b>容差（格）。
     *
     * <p>{@link #ATTACK_RANGE} 只回答"水平方向够不够近"，它由碰撞箱半宽推导、是<b>纯水平口径</b>。
     * 只看水平会导致"怪站在塔底、玩家站在 7 格高的台上（水平距离 ≈ 0）也被咬中"——
     * 这正是 2026-09-22 试玩里玩家"看不见怪却一直掉血"的成因
     * （见 {@code docs/testing/M2_1_PLAYTEST_EVIDENCE_2026-09-22.md} §4.2）。
     * 本条只给<b>攻击</b>加竖直门；{@code chasing}（追击）仍只用水平距离 ——
     * "怪可以追一个它够不着的东西"是有意保留的行为（类注释第 7 步：允许卡住）。
     *
     * <p><b>取值 1.5 的推导</b>（Δy = 玩家脚底 y − 怪脚底 y）：
     * <ul>
     *   <li><b>必须容忍一级台阶</b>：Δy = 1.0 —— 怪站在地面、玩家站在旁边一格高的台阶上；</li>
     *   <li><b>必须容忍玩家跳跃中途</b>：{@link Player#JUMP_HEIGHT} = 8.95²/(2×32) = 1.2499 格。
     *       取 1.25 —— 怪站地面、玩家原地起跳到最高点，脚底高 1.25，仍在怪的躯干高度
     *       （怪身高 1.8）之内，此时咬中是合理的；</li>
     *   <li><b>必须拒绝数格落差</b>：Δy = 2.0 时玩家脚底（2.0）已高过怪的头（1.8），
     *       站在地面的怪在几何上够不到 —— 试玩记录的 7 格落差属于这一类。</li>
     * </ul>
     * 1.5 同时满足三者（≥ 1.25 且 < 2.0），并在 1.25 之上留 0.25 的浮点 / 台阶余量。
     * 判据用 {@code Math.abs(dy)}，对"玩家在怪上方"与"玩家在怪下方"同等生效。
     */
    public static final double ATTACK_VERTICAL_RANGE = 1.5;

    /** 攻击冷却（秒）。PRD 未给值，取 1.0 —— 【MVP 内测值，需试玩调优】。 */
    public static final double ATTACK_COOLDOWN_SECONDS = 1.0;

    /** 碰撞受阻时依次尝试的偏转角（度），先小角度再大角度。 */
    private static final double[] DEFLECT_ANGLES_DEG = {45, -45, 90, -90, 135, -135};

    /**
     * 一次攻击摆臂的时长（秒）。
     *
     * <p>比攻击冷却 {@link #ATTACK_COOLDOWN_SECONDS}（1.0 s）短得多是刻意的：
     * 挥出去那一下应当在冷却刚开始时就结束，剩下的时间是"收回"。
     * 若把摆臂拉满 1 秒，看起来会像慢动作挥拳。
     */
    public static final double ATTACK_SWING_SECONDS = 0.35;

    private double attackCooldown = 0;
    private int attackCount = 0;

    /**
     * 最近一次咬击的水平距离 / 竖直偏移（格）。
     *
     * <p>把 §4.2 那条"隔空咬人"从<b>代码静读</b>变成<b>可断言的事实</b>：日志行会打印它们，
     * 自测也会断言"同层咬击时水平 ≤ {@link #ATTACK_RANGE}、竖直 ≈ 0"。
     * 咬击发生前为 {@code NaN}（表示"本次会话尚未咬过"，而不是"咬在 0 格处"）。
     */
    private double lastBiteHorizontalDistance = Double.NaN;
    private double lastBiteVerticalOffset = Double.NaN;

    /** 朝向（度），口径同 {@link com.skyisland.player.Camera}：0 = 朝 -Z。 */
    private double facingDeg = 0;

    /** 累计水平行走距离（格）：渲染层的步态相位来源。 */
    private double walked = 0;

    private boolean chasing = false;
    private boolean deflectedThisStep = false;
    private boolean voidBlockedThisStep = false;

    public MeleeMonster(double x, double y, double z) {
        super(x, y, z, MAX_HEALTH);
        this.halfWidth = 0.3;
        this.height = 1.8;
    }

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    /** 累计攻击次数（自测断言用）。 */
    public int attackCount() {
        return attackCount;
    }

    /** 最近一次咬击的水平距离（格）；尚未咬击时为 {@code NaN}。 */
    public double lastBiteHorizontalDistance() {
        return lastBiteHorizontalDistance;
    }

    /** 最近一次咬击的竖直偏移 Δy = 玩家脚底 − 怪脚底（格）；尚未咬击时为 {@code NaN}。 */
    public double lastBiteVerticalOffset() {
        return lastBiteVerticalOffset;
    }

    /** 本步是否处于追击状态。 */
    public boolean isChasing() {
        return chasing;
    }

    /** 上一步是否因碰撞而偏转（第 4 步）。 */
    public boolean wasDeflected() {
        return deflectedThisStep;
    }

    /** 上一步是否因前方虚空而拒绝前进（第 5 步）。 */
    public boolean wasVoidBlocked() {
        return voidBlockedThisStep;
    }

    @Override
    public double facingDeg() {
        return facingDeg;
    }

    @Override
    public double walkDistance() {
        return walked;
    }

    /**
     * 攻击摆动强度 0..1：一次攻击内 0 → 1 → 0（{@code sin} 半周），
     * 摆动结束后恒为 0。
     *
     * <p><b>为什么由攻击冷却反推而不额外存一个计时器：</b>
     * {@code attackCooldown} 已经完整记录了"距上次攻击过了多久"，
     * 再存一份计时器就多了一处必须与它同步的状态 —— 一旦不同步，
     * 症状是"摆臂和伤害对不上"，而那正是最难归因的一类表现 bug。
     */
    @Override
    public double attackSwing01() {
        if (attackCooldown <= 0) {
            return 0;
        }
        double elapsed = ATTACK_COOLDOWN_SECONDS - attackCooldown;
        double u = elapsed / ATTACK_SWING_SECONDS;
        if (u >= 1.0) {
            return 0;
        }
        return Math.sin(Math.PI * u);
    }

    @Override
    public void tick(World world, Player player, double dt) {
        decayHurtFlash(dt);
        if (!alive) {
            return;
        }

        attackCooldown = Math.max(0, attackCooldown - dt);
        deflectedThisStep = false;
        voidBlockedThisStep = false;

        applyGravity(dt);

        double dx = player.position().x - position.x;
        double dz = player.position().z - position.z;
        double dy = player.position().y - position.y;   // 竖直偏移（脚底对脚底）
        double horizontal = Math.hypot(dx, dz);

        // ---- 朝向：始终面朝玩家 ----
        // 渲染层用这个角度把怪物转过去，于是"眼睛那一面"就是它真正对准的方向。
        // 选"始终朝向"而不是"只在追击范围内朝向"，是因为可读性优先：
        // 玩家看到一只怪，第一件事就是判断"它是不是冲我来的"，而朝向正是这个信号。
        if (horizontal > 1e-6) {
            facingDeg = Math.toDegrees(Math.atan2(-dx, -dz));
        }

        // ---- 第 1 步：检测玩家 ----
        chasing = horizontal <= CHASE_RANGE;

        // ---- 第 6 步：接近玩家后攻击 ----
        if (horizontal <= ATTACK_RANGE) {
            // 攻击门 = 水平够近（外层已判）AND 竖直够近。竖直门只进攻击判定，不进 chasing ——
            // 一只怪可以追一个它够不着的东西（第 7 步：允许卡住），但不可以隔空咬人。
            //
            // 注意：竖直门<u>不能</u>并进外层 if 条件。否则"水平相邻但竖直很远"时会落到下面的
            // else-if 追击分支，而那里的 {@code mx = dx / horizontal} 在 horizontal ≈ 0 时会算出
            // NaN，把怪的位置污染成 NaN。外层条件因此保持"纯水平"，竖直判定只包住咬击本身。
            boolean withinVerticalReach = Math.abs(dy) <= ATTACK_VERTICAL_RANGE;
            // 玩家已倒下则不再补刀：否则死亡倒计时的 3 秒里会被反复命中，
            // 「已倒下」这个状态就失去了意义。
            if (withinVerticalReach && attackCooldown <= 0 && !player.isDead()) {
                // 标注来源为"近战"，把"被怪咬"与"摔落"在日志里区分开（归因仪器）。
                player.hurt(world, ATTACK_DAMAGE, Player.DamageCause.MELEE);
                attackCooldown = ATTACK_COOLDOWN_SECONDS;
                attackCount++;
                // ★ 咬击几何仪器：把"隔空咬人"变成日志里可读的一行 + 自测可断言的两个量。
                //   生命值的变化仍由 Player.hurt 的那一行记录，这里不重复。
                lastBiteHorizontalDistance = horizontal;
                lastBiteVerticalOffset = dy;
                Log.info("[战斗] 近战咬击：水平 %.2f 格 / 垂直 Δy %.2f 格", horizontal, dy);
            }
        } else if (chasing) {
            // ---- 第 2 / 3 步：追击，朝玩家水平方向移动 ----
            double step = MOVE_SPEED * dt;
            double mx = dx / horizontal * step;
            double mz = dz / horizontal * step;

            if (!tryStep(world, mx, mz)) {
                // ---- 第 4 步：前方碰撞时尝试左右偏转 ----
                deflectedThisStep = true;
                for (double angle : DEFLECT_ANGLES_DEG) {
                    double rad = Math.toRadians(angle);
                    double nx = mx * Math.cos(rad) - mz * Math.sin(rad);
                    double nz = mx * Math.sin(rad) + mz * Math.cos(rad);
                    if (tryStep(world, nx, nz)) {
                        break;
                    }
                }
                // 全部方向都不可行 → 停住（第 7 步：允许卡住，不做全地图寻路）
            }
        }

        applyVertical(world, dt);

        // ---- 边界：掉入虚空立即移除，不掉落任何物品（PRD 5.5.2）----
        if (Coords.isVoidDeath(position.y)) {
            alive = false;
        }
    }

    private boolean tryStep(World world, double mx, double mz) {
        if (wouldFallIntoVoid(world, mx, mz)) {
            voidBlockedThisStep = true;
            return false;
        }
        if (blockedBy(world, mx, mz)) {
            return false;
        }
        moveHorizontal(world, mx, mz);
        walked += Math.hypot(mx, mz);
        return true;
    }

    /**
     * 第 5 步：目标位置的<b>脚下</b>是否有支撑。
     *
     * <p>只查脚下一格，不做"再往前若干格"的预判 —— MVP 的速度是 2.0 格/秒、
     * 单帧位移约 0.033 格，一步一查已经足够，多步预判只会让"贴着崖边走"变得不可能。
     */
    private boolean wouldFallIntoVoid(World world, double dx, double dz) {
        int bx = (int) Math.floor(position.x + dx);
        int by = (int) Math.floor(position.y) - 1;
        int bz = (int) Math.floor(position.z + dz);
        return !world.hasCollisionAt(bx, by, bz);
    }
}

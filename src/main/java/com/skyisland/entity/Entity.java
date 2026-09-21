package com.skyisland.entity;

import com.skyisland.physics.AABB;
import com.skyisland.player.Player;
import com.skyisland.world.World;
import org.joml.Vector3d;

/**
 * 实体基类（M2 首次引入）。
 *
 * <p><b>为什么 M1 没有实体而 M2 必须引入：</b>M1 世界里唯一会动的东西是玩家。
 * M2 的通过标准第 1 条是"能在体素场景中完成一场基础枪战"，
 * 而枪战需要三个此前不存在的东西：<b>一个可被命中的目标</b>、
 * <b>一个沿射线比较远近的容器</b>、<b>一个承受伤害并死亡的状态机</b>。
 * 这三件事共同定义了"实体"这个概念在项目里的最小含义。
 *
 * <p><b>位置语义与玩家一致：脚底中心</b>（TECH_DESIGN §I.4）。
 * 沿用同一语义的好处是 {@link AABB#ofFeetCenter} 与碰撞求解可以原样复用，
 * 不必在两套坐标系之间做换算 —— 那类换算正是"看起来对但边界差一格"的来源。
 *
 * <p><b>受击闪白在这里，而不是在渲染层：</b>
 * PRD 5.4.3 要求"命中怪物：怪物受击闪白"。把闪白做成渲染层自己维护的计时器，
 * 会让"怪物被打中了"这个事实只存在于画面里 —— 测试无法断言它。
 * 这里把闪白做成实体上的状态，渲染层只负责把它读出来画成白色。
 */
public abstract class Entity {

    /** 位置（脚底中心）。 */
    protected final Vector3d position = new Vector3d();

    protected double halfWidth = 0.3;
    protected double height = 1.8;

    /** 垂直速度（格/秒），向下为负。 */
    protected double velocityY = 0;

    protected final int maxHealth;
    protected int health;

    protected boolean alive = true;

    /** 受击闪白剩余时间（秒）；> 0 表示正在闪白。 */
    protected double hurtFlashSeconds = 0;

    /** 闪白持续时间（秒）。0.15 s 是"看得见但不刺眼"的经验值。 */
    public static final double HURT_FLASH_DURATION = 0.15;

    /**
     * 闪白强度 0..1（1 = 刚被打中，0 = 不闪）。
     *
     * <p>渲染层需要的是"混多少白"，而不是"还剩几秒"。把除法放在这里而不是渲染器里，
     * 是为了让{@code HURT_FLASH_DURATION}只有一处定义 —— 渲染器自己除一遍的话，
     * 改时长时就会漏改一处，表现为"闪白强度对不上，但看起来只是有点怪"。
     */
    public double hurtFlash01() {
        if (hurtFlashSeconds <= 0) {
            return 0;
        }
        return Math.min(1.0, hurtFlashSeconds / HURT_FLASH_DURATION);
    }

    protected Entity(double x, double y, double z, int maxHealth) {
        this.position.set(x, y, z);
        this.maxHealth = maxHealth;
        this.health = maxHealth;
    }

    // ------------------------------------------------------------ 基本信息

    /** 与 PRD / 存档一致的 stable ID，例如 {@code skyisland:melee_monster}。 */
    public abstract String typeId();

    public Vector3d position() {
        return position;
    }

    public double halfWidth() {
        return halfWidth;
    }

    public double height() {
        return height;
    }

    public AABB boundingBox() {
        return AABB.ofFeetCenter(position.x, position.y, position.z, halfWidth, height);
    }

    public int health() {
        return health;
    }

    public int maxHealth() {
        return maxHealth;
    }

    public boolean isAlive() {
        return alive;
    }

    // ------------------------------------------------------------ 受击

    /**
     * 承受一次伤害。
     *
     * @param amount 伤害值；非正数直接忽略（避免"0 伤害也触发闪白"这类假反馈）
     */
    public void hurt(int amount) {
        if (!alive || amount <= 0) {
            return;
        }
        health -= amount;
        hurtFlashSeconds = HURT_FLASH_DURATION;
        if (health <= 0) {
            health = 0;
            alive = false;
        }
    }

    /** 受击闪白剩余时间（秒）。 */
    public double hurtFlashSeconds() {
        return hurtFlashSeconds;
    }

    /** 是否正在受击闪白。 */
    public boolean isHurtFlashing() {
        return hurtFlashSeconds > 0;
    }

    // ------------------------------------------------------------ 渲染所需的状态

    /**
     * 朝向（度）。口径与 {@link com.skyisland.player.Camera} 一致：
     * {@code 0 = 朝 -Z}，增大 = 向左转（俯视逆时针）。
     *
     * <p><b>为什么放在实体上而不是渲染层：</b>朝向是"它面朝哪边"这个事实，
     * 渲染层只能<b>读</b>出来。放在渲染层里就得靠"上一帧位置减这一帧位置"反推，
     * 而那在实体静止时没有定义 —— 表现为怪物站住后朝向随机。
     * 基类默认 0（不转向）；需要朝向的子类重写。
     */
    public double facingDeg() {
        return 0;
    }

    /**
     * 累计水平行走距离（格）。渲染层据此算步态相位。
     *
     * <p>用<b>距离</b>而不是时间算相位，是为了让"停下来"自动等价于"停止摆动"，
     * 不需要额外的"是否在移动"标志。基类默认 0（不摆动）。
     */
    public double walkDistance() {
        return 0;
    }

    /**
     * 攻击摆动强度 0..1（0 = 没在攻击）。渲染层据此做摆臂 / 前冲。
     *
     * <p>与 {@link #hurtFlash01()} 同一套取向：动画的进度是实体的状态，
     * 渲染层只负责把它读出来画成姿态。基类默认 0。
     */
    public double attackSwing01() {
        return 0;
    }

    // ------------------------------------------------------------ 逐帧

    /**
     * 推进一帧。
     *
     * @param world  世界（碰撞与方块查询）
     * @param player 玩家（AI 的追击目标）
     * @param dt     固定逻辑步长
     */
    public abstract void tick(World world, Player player, double dt);

    protected void applyGravity(double dt) {
        velocityY -= EntityPhysics.GRAVITY * dt;
        if (velocityY < -EntityPhysics.TERMINAL_VELOCITY) {
            velocityY = -EntityPhysics.TERMINAL_VELOCITY;
        }
    }

    /**
     * 应用垂直位移（重力结果）。<b>与水平位移分开调用</b>。
     *
     * <p>分开的理由是 AI 需要"先试探多个水平方向、选中一个再走"：
     * 若水平与垂直绑在一次调用里，每次试探偏转都会顺带再落一次重力，
     * 同一帧内下落量会随试探次数变化 —— 表现为怪物在拐角处下沉得比别处快。
     */
    protected void applyVertical(World world, double dt) {
        EntityPhysics.MoveResult r = EntityPhysics.move(world, position,
                0, velocityY * dt, 0, halfWidth, height);
        if (r.onGround() && velocityY < 0) {
            velocityY = 0;
        }
    }

    /** 探测水平位移是否会撞上方块（纯几何试探，不移动、不改状态）。 */
    protected boolean blockedBy(World world, double dx, double dz) {
        return EntityPhysics.collides(world, boundingBox().moved(dx, 0, dz));
    }

    /** 执行水平位移。 */
    protected EntityPhysics.MoveResult moveHorizontal(World world, double dx, double dz) {
        return EntityPhysics.move(world, position, dx, 0, dz, halfWidth, height);
    }

    /** 每帧末统一衰减闪白计时，避免每个子类各写一遍。 */
    protected void decayHurtFlash(double dt) {
        if (hurtFlashSeconds > 0) {
            hurtFlashSeconds = Math.max(0, hurtFlashSeconds - dt);
        }
    }

    @Override
    public String toString() {
        return String.format("%s@(%.2f,%.2f,%.2f) hp=%d/%d%s",
                typeId(), position.x, position.y, position.z, health, maxHealth,
                alive ? "" : " DEAD");
    }
}

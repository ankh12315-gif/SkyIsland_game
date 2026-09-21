package com.skyisland.render.viewmodel;

/**
 * 第一人称手持物的<b>动画状态</b>：idle bob / use swing / fire recoil / slot pop / ADS / 换弹下压。
 *
 * <h2>为什么状态机住在渲染层</h2>
 * 这六种动作全都是"表现"，没有一条影响命中判定、伤害或物品数量 ——
 * 把它们放进 {@code Player} 会让"我开了一枪"这件事在逻辑与画面上各有一份真相。
 * 因此本类只从 {@link ViewmodelModel} 读状态，不写回任何游戏对象。
 *
 * <h2>帧率无关</h2>
 * 全部动画都以<b>秒</b>推进（{@code dt}），没有一处用帧计数。
 * 逻辑 60 Hz、渲染上千 FPS 是本项目的常态，用帧计数的下场是
 * "在 3000 FPS 下后坐一闪就没、在 60 FPS 下慢得离谱"。
 * {@code dt} 另外被夹到 0.1 秒上限：切后台再回来时的巨大 dt 会让动画瞬移。
 *
 * <h2>为什么开火用"累计次数"触发</h2>
 * 见 {@link ViewmodelModel#shotCount}。本类只比较前后两帧的差值：
 * 既不漏读（渲染帧比逻辑帧密），也不重复触发（不像布尔标志那样需要"消费"）。
 */
public final class ViewmodelPose {

    /** 切槽弹出的时长（秒）。 */
    public static final double POP_SECONDS = 0.18;

    /** 开火后坐的时长（秒）。 */
    public static final double RECOIL_SECONDS = 0.24;

    /** 挖掘 / 放置的挥动频率（Hz）。 */
    public static final double SWING_HZ = 2.6;

    /** ADS 的趋近速率（1/秒）。指数趋近，因此与帧率无关。 */
    public static final double AIM_RATE_PER_SEC = 14.0;

    /** idle bob 的频率（Hz）。 */
    public static final double BOB_HZ = 1.1;

    private static final double BOB_X = 0.010;
    private static final double BOB_Y = 0.014;

    /** 切槽时下沉的最大幅度（格）。 */
    private static final double POP_DROP = 0.070;

    /** 切槽时放大的最大比例（"弹出感"）。 */
    private static final double POP_SCALE = 0.14;

    /** 后坐：向相机方向的位移（格）。 */
    private static final double RECOIL_Z = 0.055;

    /** 后坐：枪口上扬的角度（弧度）。 */
    private static final double RECOIL_PITCH = 0.34;

    /** 换弹时下沉的幅度（格）与翻转角度（弧度）。 */
    private static final double RELOAD_DROP = 0.075;
    private static final double RELOAD_ROLL = 0.42;

    /** 挥动的最大角度（弧度）。 */
    private static final double SWING_ANGLE = 0.55;

    private int lastSlot = -1;
    private int lastShot = 0;

    private double pop;          // 剩余秒
    private double recoil;       // 剩余秒
    private double swingAmp;     // 0..1
    private double swingPhase;   // 0..1，循环
    private double aim;          // 0..1

    private double time;
    private double moveAmt;
    private boolean reloading;
    private double reloadProgress;

    /**
     * 推进一帧。
     *
     * @param dt 距上一帧的秒数；负值按 0 处理，超过 0.1 按 0.1 处理
     */
    public void update(ViewmodelModel m, double dt) {
        double step = Math.max(0.0, Math.min(0.1, dt));

        if (lastSlot < 0) {
            lastSlot = m.slot;                 // 第一帧只记录，不弹（否则一进游戏就抖一下）
        } else if (m.slot != lastSlot) {
            lastSlot = m.slot;
            pop = POP_SECONDS;
        }
        if (pop > 0) {
            pop = Math.max(0.0, pop - step);
        }

        if (m.shotCount != lastShot) {
            if (m.shotCount > lastShot) {
                recoil = RECOIL_SECONDS;
            }
            lastShot = m.shotCount;
        }
        if (recoil > 0) {
            recoil = Math.max(0.0, recoil - step);
        }

        swingPhase += step * SWING_HZ;
        if (swingPhase >= 1.0) {
            swingPhase -= Math.floor(swingPhase);   // 保持数值有界，长时间游玩不丢精度
        }
        double swingTarget = m.mining ? 1.0 : 0.0;
        double swingRate = m.mining ? 8.0 : 5.0;    // 起手快、收手慢
        swingAmp += (swingTarget - swingAmp) * Math.min(1.0, step * swingRate);

        double aimTarget = m.aiming ? 1.0 : 0.0;
        aim += (aimTarget - aim) * Math.min(1.0, step * AIM_RATE_PER_SEC);

        time = m.timeSeconds;
        moveAmt = Math.max(0.0, Math.min(1.0, m.moveSpeed01));
        reloading = m.reloading;
        reloadProgress = Math.max(0.0, Math.min(1.0, m.reloadProgress01));
    }

    // ------------------------------------------------------------ 派生量

    /** 切槽动画的进度 0..1（1 = 已结束）。 */
    public double popProgress01() {
        return pop <= 0 ? 1.0 : 1.0 - pop / POP_SECONDS;
    }

    /** 切槽时先沉下去再弹回（格，恒 ≤ 0）。 */
    public double popDrop() {
        return -POP_DROP * Math.sin(Math.PI * popProgress01());
    }

    /** 切槽时的"弹出"放大系数（1 = 无缩放）。 */
    public double popScale() {
        return 1.0 + POP_SCALE * Math.sin(Math.PI * popProgress01());
    }

    /**
     * 后坐强度 0..1：击发的瞬间为 1，之后指数衰减。
     *
     * <p>用 {@code exp(-5u)} 而不是线性：真实的后坐是"猛地一下 + 缓慢回正"，
     * 线性回正在画面上读起来像匀速抬枪，没有"gun kick"的味道。
     */
    public double recoil01() {
        if (recoil <= 0) {
            return 0;
        }
        double u = 1.0 - recoil / RECOIL_SECONDS;
        return Math.exp(-5.0 * u);
    }

    /** 挥动角度（弧度），正负交替。 */
    public double swingAngleRad() {
        return Math.sin(swingPhase * 2.0 * Math.PI) * SWING_ANGLE * swingAmp;
    }

    /** ADS 混合量 0..1。 */
    public double aim01() {
        return aim;
    }

    /** idle bob 的横向位移（格）。 */
    public double bobX() {
        return BOB_X * Math.sin(2.0 * Math.PI * BOB_HZ * time) * moveAmt;
    }

    /** idle bob 的纵向位移（格，恒 ≤ 0 —— 只在"落脚"时下沉，不做上抛）。 */
    public double bobY() {
        return -BOB_Y * (0.5 - 0.5 * Math.cos(4.0 * Math.PI * BOB_HZ * time)) * moveAmt;
    }

    /** 换弹下压（格，恒 ≤ 0）：进弹时沉下去，完成时回到原位。 */
    public double reloadDrop() {
        if (!reloading) {
            return 0;
        }
        return -RELOAD_DROP * Math.sin(Math.PI * reloadProgress);
    }

    /** 换弹时的翻转角（弧度）：枪身侧过去让"弹匣"朝向视线。 */
    public double reloadRollRad() {
        if (!reloading) {
            return 0;
        }
        return RELOAD_ROLL * Math.sin(Math.PI * reloadProgress);
    }

    // ------------------------------------------------------------ 合成

    /** 锚点 X（髋射 → ADS 线性插值 + bob）。 */
    public double anchorX() {
        return ViewmodelGeometry.HIP_X
                + (ViewmodelGeometry.ADS_X - ViewmodelGeometry.HIP_X) * aim
                + bobX() * (1.0 - 0.75 * aim);
    }

    /** 锚点 Y（髋射 → ADS + bob + 切槽下沉 + 换弹下压）。 */
    public double anchorY() {
        return ViewmodelGeometry.HIP_Y
                + (ViewmodelGeometry.ADS_Y - ViewmodelGeometry.HIP_Y) * aim
                + bobY() * (1.0 - 0.75 * aim)
                + popDrop()
                + reloadDrop();
    }

    /** 锚点 Z（髋射 → ADS；ADS 时略远一点，于是看起来更小、更不挡视线）。 */
    public double anchorZ() {
        return ViewmodelGeometry.HIP_Z
                + (ViewmodelGeometry.ADS_Z - ViewmodelGeometry.HIP_Z) * aim;
    }

    /**
     * 绕 Y 轴：基础朝向，但<b>瞄准时回正</b>。
     *
     * <p>回正是两条理由叠加的结果：一是举枪瞄准时枪管本来就该与视线平行
     * （不平行的话玩家会觉得"准星和枪不是一个方向"）；二是基础朝向那 17°
     * 会把枪管末端往屏幕中心方向推，ADS 收拢之后正好有把它推过中心线的风险 ——
     * 而手持物越过屏幕中心就等于盖住准星。
     */
    public double yawRad() {
        return ViewmodelGeometry.BASE_YAW * (1.0 - 0.85 * aim) + reloadRollRad() * 0.35;
    }

    /** 绕 X 轴：基础俯角 + 后坐上扬 + 挥动。 */
    public double pitchRad() {
        return ViewmodelGeometry.BASE_PITCH + RECOIL_PITCH * recoil01() + swingAngleRad();
    }

    /** 绕 Z 轴：基础侧倾 + 换弹翻转 + bob 带来的轻微摆动。 */
    public double rollRad() {
        return ViewmodelGeometry.BASE_ROLL + reloadRollRad()
                + 0.03 * Math.sin(2.0 * Math.PI * BOB_HZ * time) * moveAmt;
    }

    /** 后坐：沿 +Z（朝相机）的位移。 */
    public double recoilPush() {
        return RECOIL_Z * recoil01();
    }

    /** 整体缩放（切槽弹出 + ADS 时略缩）。 */
    public double scale() {
        return popScale() * (1.0 - 0.08 * aim);
    }
}

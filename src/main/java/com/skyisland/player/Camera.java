package com.skyisland.player;

import com.skyisland.item.RecoilProfile;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 第一人称相机（M1 指令 B7）：yaw / pitch / 鼠标视角 / FOV / 视图矩阵 / 投影矩阵。
 *
 * <p><b>角度约定（写死，不再讨论）：</b>
 * <ul>
 *   <li>单位是<u>度</u>；{@code yaw = 0, pitch = 0} 时朝向 <b>-Z</b>；</li>
 *   <li>{@code yaw} 增大 = 向左转（俯视逆时针）；</li>
 *   <li>{@code pitch} 增大 = 向上看。</li>
 * </ul>
 * 前向量因此为 {@code (-sin(yaw)·cos(pitch), sin(pitch), -cos(yaw)·cos(pitch))}。
 *
 * <p><b>为什么 pitch 必须夹紧：</b>pitch 到 ±90° 时前向量与世界上方向平行，
 * {@code lookAt} 的 up 向量退化，视图矩阵会出现 NaN 或突然翻转。
 * 夹到 ±89.5° 既保留"几乎垂直"的观感，又让矩阵始终良态。
 * 夹紧是相机的<u>不变量</u>，所以放在 {@link #addLook} 内部而不是调用方。
 *
 * <p><b>位置语义：</b>相机位置 = 玩家眼睛位置（脚底 + 眼高）。
 * 这个换算由 {@link Player} 负责，相机本身只认自己拿到的坐标 ——
 * 这样相机也能被自测脚本直接驱动，不需要先造一个玩家。
 */
public final class Camera {

    public static final double DEFAULT_FOV_DEG = 70.0;
    public static final double DEFAULT_NEAR = 0.05;
    public static final double DEFAULT_FAR = 512.0;

    /**
     * 鼠标灵敏度：度 / 像素。M1 为固定值，设置项属 M3。
     *
     * <p><b>为什么这个常量住在 {@link Camera} 而不是 {@code InputMapper}：</b>
     * 灵敏度是"像素 → 角度"的换算系数，属于相机的角度语义；输入层只负责把
     * "鼠标动了多少像素"交出来，不解释它意味着转多少度。放在相机侧之后，
     * 自测脚本可以<u>绕过输入层</u>直接驱动相机，而不必依赖 {@code InputMapper}。
     */
    public static final double SENSITIVITY_DEG_PER_PIXEL = 0.12;

    /** pitch 上限：留 0.5° 余量避免 up 向量退化。 */
    public static final double MAX_PITCH_DEG = 89.5;

    private static final double MIN_PITCH_DEG = -MAX_PITCH_DEG;

    /*
     * M2.1：开火后坐力 —— 一组"纯视觉"的角度参数与状态。
     *
     * ---------------------------------------------------------------------------
     * 决策：后坐力写在独立字段里，绝不与 yawDeg / pitchDeg 混合。
     * ---------------------------------------------------------------------------
     * 三条理由，每一条单独都足以否决"直接把 pitchDeg 加上去"：
     *   ① yawDeg/pitchDeg 是<b>玩家意图的权威值</b>，且被 SaveManager 存档读写。
     *      把后坐力写进去等于把"上一枪抬起来的那 0.9°"持久化 —— 读档回来视角不一样了，
     *      而且每次开火都在污染存档数据；
     *   ② addLook() 是鼠标的唯一入口，且持有"pitch 必须夹紧"这条<u>不变量</u>。
     *      从别的路径改 pitch 会让不变量的持有者从一个变成两个 ——
     *      PlayerLookSensitivityTest 断言的正是这条不变量；
     *   ③ Hitscan 用 {@link #forward()}，而 forward 由 updateBasis() 算出。
     *      本次把后坐力<b>只</b>接进 {@link #updateView()}： forward 与准星都不受影响，
     *      于是"子弹去哪"与"准星指的是什么"仍然逐位一致，代价只是画面本身抬了一下
     *      （见 {@link #addRecoilPitch(double)} 里对这一取舍的解释）。
     */

    /*
     * ★ 2026-10-03：这三个常量<b>已删除</b>，取而代之的是 {@link RecoilProfile}
     * （{@code com.skyisland.item.RecoilProfile#PISTOL / SMG / RIFLE}）。
     *
     * 删除它们的理由不是"整理常量"，而是它们本身就是一条死接线的证据：
     * {@code GunPresentationSpec.recoilProfileId} 这个键从 M3 Story 5 起就挂在每把枪上，
     * 但只要后坐的三个数仍写死在这里，那个键就<b>没有任何生产读者</b> ——
     * 拿步枪开一枪，画面抬起的角度与手枪逐位相同。
     *
     * 手枪那份档案的数值与这三个常量<b>逐值相等</b>（0.9° / 1.8° / 5.0° 每秒），
     * 因此本轮改造对手枪的画面零影响；SMG 与步枪则第一次拿到自己的曲线。
     *
     * 三个数现在分别是：
     *   单发抬枪  {0.90, 0.35, 1.60}°   累计上限 {1.80, 2.60, 2.20}°
     *   回落速度  {5.00, 2.40, 4.00}°/s
     * 全都两两不同 —— 这是"后坐真的由数据决定"能被证伪的前提（同值巧合）。
     */

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Vector3f forward = new Vector3f(0, 0, -1);
    private final Vector3f right = new Vector3f(1, 0, 0);
    private final Vector3f up = new Vector3f(0, 1, 0);

    private final Vector3d eye = new Vector3d();
    private final Vector3d lookTarget = new Vector3d();

    private double yawDeg = 0;
    private double pitchDeg = 0;
    /**
     * M2.1：开火后坐力带来的<b>临时</b>抬枪角度（度，非负）。
     *
     * <p>它是"当前这一帧的视图要不要往上抬一点"的唯一来源，
     * 既不进 {@link #updateBasis()}（不改 {@link #forward()}）、也不参与存档。
     */
    private double recoilPitchDeg = 0;
    private double fovDeg = DEFAULT_FOV_DEG;

    private int viewportWidth = 1;
    private int viewportHeight = 1;

    // ------------------------------------------------------------ 位置

    public void setPosition(double x, double y, double z) {
        eye.set(x, y, z);
    }

    public Vector3dc eyePosition() {
        return eye;
    }

    public double x() {
        return eye.x;
    }

    public double y() {
        return eye.y;
    }

    public double z() {
        return eye.z;
    }

    // ------------------------------------------------------------ 角度

    public double yawDeg() {
        return yawDeg;
    }

    public double pitchDeg() {
        return pitchDeg;
    }

    public void setAngles(double yaw, double pitch) {
        this.yawDeg = yaw;
        this.pitchDeg = clampPitch(pitch);
        updateBasis();
    }

    // ------------------------------------------------------------ M2.1：开火后坐力

    /**
     * 打出一发实弹：给相机叠一次抬枪角，幅度与上限都来自<b>当前这把枪的后坐档案</b>。
     *
     * <p><b>它<u>不</u>改 {@link #pitchDeg}、不碰 {@link #forward()}</b>，
     * 所以不会产生任何玩法后果 —— 这是本次改造的核心取舍，三条理由写在类注释里。
     * 一句话总结取舍的代价：在后坐力尚未回落的那段时间里，
     * "画面中心"与"射线中心"会暂时相差最多 {@link RecoilProfile#maxPitchDeg()}。
     * <b>这是有意为之</b>：只有"视图被顶了一下、而准星与子弹方向不动"，
     * 才能既给出击发反馈，又不让玩家因为打了连发而真的瞄不准
     * （玩家的手不需要补偿，画面自己会回来）。
     * 若把后坐力加进 {@code pitchDeg}，则"连发 = 视角持续上飘"，
     * 玩家必须反向压枪 —— 那是另一个游戏的玩法，不是本作现在要的。
     *
     * <p><b>为什么参数是整份档案而不是一个 {@code double} 角度：</b>
     * 只传角度的话，"累计上限"这个量就得留在相机里，于是它又变回一个全局常量 ——
     * 而 SMG 那份档案的上限（2.6°）与步枪的（2.2°）是不同的。
     * 传整份档案之后，{@code 抬枪量 / 上限 / 回落速度} 三个量<b>只能</b>来自同一把枪。
     *
     * @param profile 当前手持枪的后坐档案；{@code null} 按"没有后坐"处理
     */
    public void addRecoil(RecoilProfile profile) {
        if (profile == null) {
            return;
        }
        recoilPitchDeg = Math.min(profile.maxPitchDeg(), recoilPitchDeg + profile.pitchDegPerShot());
    }

    /**
     * 每逻辑步推进后坐力回落（由游戏主循环调用，与 {@code dt} 同源）。
     *
     * <p><b>为什么必须显式传出 {@code dt}，而不是"每帧衰减一个固定量"：</b>
     * 后者会让后坐力的持续时间与帧率绑定 —— 3000 FPS 下它会在 3 毫秒内消失，
     * 表现为"高端机上看不到后坐力"。这正是本项目反复强调的
     * "帧率不得影响行为"（§C.4′）在同一类问题上的又一次出现。
     *
     * <p><b>回落速度同样来自档案：</b>手枪 5.0°/s（0.18 秒归零），
     * SMG 只有 2.4°/s —— 那是"扫射时看得见的累积"的数据来源，
     * 若这里回落到全局常量，SMG 那份档案就只有一半生效。
     *
     * <p>用<b>线性</b>而不是指数衰减：线性回落会很干脆地到达精确的 0，
     * 不会留下"永远差一点回不去"的残差 —— 而残差的后果是
     * 玩家静止瞄准时准星与画面永远差 0.05°，那是无法归因的"手感不好"。
     */
    public void decayRecoil(double dt, RecoilProfile profile) {
        if (recoilPitchDeg <= 0.0 || !(dt > 0.0) || profile == null) {
            return;
        }
        double drop = profile.recoverDegPerSec() * dt;
        // 先减后夹，而不是 Math.max(0, recoil - drop) 之后再夹：
        // 两者在这里等价，但"恒定不能为负"应当由最后一次赋值保证，
        // 这样即使将来有人引入方向的负后坐力，改动也不会悄悄突破不变量。
        recoilPitchDeg = Math.max(0.0, recoilPitchDeg - drop);
    }

    /** 当前剩余的后坐力抬枪角度（度）。调试读数与自测断言用。 */
    public double recoilPitchDeg() {
        return recoilPitchDeg;
    }

    /** 清掉后坐力（读档 / 传送 / 重生的场景：新的位置不该带着上一处的抖动）。 */
    public void clearRecoil() {
        recoilPitchDeg = 0.0;
    }

    /**
     * 应用一次鼠标位移。
     *
     * @param deltaXPixels 向右为正
     * @param deltaYPixels 向下为正
     * @param sensitivity  度 / 像素
     */
    public void addLook(double deltaXPixels, double deltaYPixels, double sensitivity) {
        yawDeg -= deltaXPixels * sensitivity;
        pitchDeg = clampPitch(pitchDeg - deltaYPixels * sensitivity);
        // 把 yaw 收敛到 (-180,180]：长时间旋转后仍保持数值可读，避免浮点精度缓慢流失
        yawDeg = wrapDegrees(yawDeg);
        updateBasis();
    }

    private static double clampPitch(double pitch) {
        if (pitch > MAX_PITCH_DEG) {
            return MAX_PITCH_DEG;
        }
        if (pitch < MIN_PITCH_DEG) {
            return MIN_PITCH_DEG;
        }
        return pitch;
    }

    private static double wrapDegrees(double deg) {
        double d = deg % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        } else if (d <= -180.0) {
            d += 360.0;
        }
        return d;
    }

    /** 依据 yaw/pitch 重算前向量与右向量。 */
    private void updateBasis() {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cosPitch = Math.cos(pitch);
        float fx = (float) (-Math.sin(yaw) * cosPitch);
        float fy = (float) Math.sin(pitch);
        float fz = (float) (-Math.cos(yaw) * cosPitch);
        forward.set(fx, fy, fz).normalize();
        // right = forward × worldUp
        forward.cross(0f, 1f, 0f, right);
        if (right.lengthSquared() < 1e-8f) {
            right.set(1, 0, 0);
        } else {
            right.normalize();
        }
    }

    public Vector3fc forward() {
        return forward;
    }

    public Vector3fc right() {
        return right;
    }

    public Vector3fc up() {
        return up;
    }

    // ------------------------------------------------------------ 矩阵

    public double fovDeg() {
        return fovDeg;
    }

    public void setFovDeg(double fov) {
        this.fovDeg = Math.max(30.0, Math.min(110.0, fov));
    }

    /**
     * 依据帧缓冲尺寸更新投影矩阵。
     *
     * <p>用 <b>framebuffer</b> 尺寸而不是窗口尺寸：高 DPI 下两者不同，
     * 用窗口尺寸会把画面拉伸（M0 已记录该差异为 0，但不能依赖它）。
     */
    public void updateProjection(int framebufferWidth, int framebufferHeight) {
        this.viewportWidth = Math.max(1, framebufferWidth);
        this.viewportHeight = Math.max(1, framebufferHeight);
        float aspect = (float) viewportWidth / (float) viewportHeight;
        projection.identity().perspective((float) Math.toRadians(fovDeg), aspect,
                (float) DEFAULT_NEAR, (float) DEFAULT_FAR);
    }

    /**
     * 依据当前位置与朝向更新视图矩阵。
     *
     * <p><b>M2.1：本方法是后坐力唯一的生效点。</b>
     * 视图用的俯仰角 = 玩家意图（{@code pitchDeg}）+ 后坐力（{@code recoilPitchDeg}），
     * 并且在这里<b>再夹一次</b> {@link #clampPitch}：
     * 玩家抬头到 89.5° 时再开一枪，二者相加会越过 ±90°，
     * 而 {@code lookAt} 的 up 向量在那一刻正交化 —— 矩阵会 NaN。
     * 夹紧的位置在这里而不是 {@link #addRecoilPitch}：
     * 因为"不能超过视界"是<u>视图矩阵</u>的性质，不是后坐力数值的性质
     * （后坐力 0.9° 本身永远是合法的）。
     */
    public void updateView() {
        double effectivePitchDeg = clampPitch(pitchDeg + recoilPitchDeg);
        double pitch = Math.toRadians(effectivePitchDeg);
        double yaw = Math.toRadians(yawDeg);
        double cosPitch = Math.cos(pitch);
        lookTarget.set(
                eye.x - Math.sin(yaw) * cosPitch,
                eye.y + Math.sin(pitch),
                eye.z - Math.cos(yaw) * cosPitch);
        // ★ 用 float 标量重载而不是 (Vector3d, Vector3d, up) 重载：
        //   JOML 的 Matrix4f.lookAt 只有 float 与 (Vector3fc, Vector3fc, Vector3fc) 两组重载，
        //   传 Vector3d 会编译不过。这里把 double 显式窄化 —— 视图矩阵本来就是 float 管线，
        //   而【角度】仍在 double 里累积（yawDeg/pitchDeg），精度损失不会被逐步放大。
        view.identity().lookAt(
                (float) eye.x, (float) eye.y, (float) eye.z,
                (float) lookTarget.x, (float) lookTarget.y, (float) lookTarget.z,
                0f, 1f, 0f);
    }

    public Matrix4fc viewMatrix() {
        return view;
    }

    public Matrix4fc projectionMatrix() {
        return projection;
    }

    public int viewportWidth() {
        return viewportWidth;
    }

    public int viewportHeight() {
        return viewportHeight;
    }

    @Override
    public String toString() {
        return String.format("Camera(%.2f,%.2f,%.2f yaw=%.1f pitch=%.1f)",
                eye.x, eye.y, eye.z, yawDeg, pitchDeg);
    }
}

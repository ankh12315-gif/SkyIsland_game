package com.skyisland.item;

import java.util.List;

/**
 * 一把枪的<b>视觉后坐档案</b>（2026-10-03：{@code GunPresentationSpec#recoilProfileId()} 的读者）。
 *
 * <h2>它为什么存在</h2>
 * M3 Story 5 给 {@link GunPresentationSpec} 挂了一个 {@code recoilProfileId} 键，
 * 但到本轮为止<b>没有任何一行生产代码读它</b> —— 后坐的三个数仍写死在
 * {@code player.Camera} 的三个常量里（0.9° / 1.8° / 5.0°/s），
 * 手持物的后坐动画也仍写死在 {@code render.viewmodel.ViewmodelPose} 里。
 * 那是一次标准形态的<b>死接线</b>：键被构造、被校验、被单测断言"非空白"，
 * 唯独没有消费者。于是"三把枪后坐手感不同"在代码上是不成立的 ——
 * 拿步枪开一枪，画面抬起的角度与手枪逐位相同。
 *
 * <p>本类就是那个缺失的消费者：{@code recoilProfileId → RecoilProfile → Camera / ViewmodelPose}。
 *
 * <h2>三份档案的定型口径（主理人 2026-10-03 裁定第 13 条）</h2>
 * <ul>
 *   <li><b>PISTOL</b> —— 中等的单发反馈。数值<b>逐值等于</b> M2.1 起就有的那组常量
 *       （0.9° / 上限 1.8° / 5.0°每秒），因此本轮改造对<b>手枪画面零影响</b>；
 *       0.9°/发 × 4 发每秒 = 3.6°/s，低于 5.0°/s 的回落速度 → 连打也不累积。</li>
 *   <li><b>SMG</b> —— 每发更轻、但持续射击时累积更明显。
 *       0.35°/发 × 10 发每秒 = 3.5°/s，高于 2.4°/s 的回落速度 →
 *       净 +1.1°/s，一匣 24 发（2.4 秒）正好顶到 2.6° 的上限。
 *       这就是"单发不太跳、扫下去越来越抬"的数据来源。</li>
 *   <li><b>RIFLE</b> —— 每发更重、且回落干净。
 *       1.6°/发，回落 4.0°/s → 0.40 秒归零，而步枪的射击间隔是 0.5 秒 ——
 *       <b>下一发之前上一发的后坐必然已经完全回正</b>，于是它是"每一下都顶一下、然后归位"，
 *       而不是 SMG 那种叠着走。1.6° 是手枪 0.9° 的 1.78 倍 —— 在 70° FOV、
 *       720p 下约等于 16 像素的画面位移，肉眼可辨。</li>
 * </ul>
 *
 * <h2>★ 为什么三份档案的数值必须两两不同</h2>
 * 这是本项目最贵的一课（"同值巧合"）：若三把枪共用同一组数值，
 * 那么"读了档案"与"读了常量"在行为上<b>再也分不出来</b> ——
 * 测试会全绿，而接线其实是断的。这里的三个 {@code pitchDegPerShot}
 * 是 {0.9, 0.35, 1.6}，三个 {@code recoverDegPerSec} 是 {5.0, 2.4, 4.0}，
 * 全部互不相同 → "后坐真的由数据决定"第一次能被<b>真实内容</b>证伪。
 *
 * <h2>为什么"累积"没有做成一个独立字段</h2>
 * 累积是 {单发抬枪量} 与 {回落速度} 两个量的<b>比值结果</b>，不是第三个独立维度。
 * 再引入一个 {@code accumulation} 字段会让它与那两个量互相矛盾
 * （例如把 accumulation 设成 2 倍、却把回落速度调到极高，实际根本不累积）。
 * 这里的做法是只留两个可直接测量的量，累积由它们算出来，因此永远自洽。
 *
 * @param id                 查表键，与 {@link GunPresentationSpec#recoilProfileId()} 对齐
 * @param pitchDegPerShot    每发实弹给相机的抬枪角度（度）
 * @param maxPitchDeg        相机后坐累计上限（度）
 * @param recoverDegPerSec   相机后坐回落速度（度/秒）
 * @param viewmodelSeconds   手持物后坐动画时长（秒）
 * @param viewmodelPushZ     手持物后坐时朝相机的位移（格）
 * @param viewmodelPitchRad  手持物后坐时枪口上扬的角度（弧度）
 */
public record RecoilProfile(
        String id,
        double pitchDegPerShot,
        double maxPitchDeg,
        double recoverDegPerSec,
        double viewmodelSeconds,
        double viewmodelPushZ,
        double viewmodelPitchRad
) {

    /**
     * 手枪：中等单发反馈。
     *
     * <p>逐值等于 M2.1 的 {@code Camera.RECOIL_PITCH_PER_SHOT_DEG / MAX_RECOIL_PITCH_DEG /
     * RECOIL_RECOVER_DEG_PER_SEC}，以及 {@code ViewmodelPose} 的
     * {@code RECOIL_SECONDS / RECOIL_Z / RECOIL_PITCH} —— 本轮改造<b>不得</b>改变手枪的任何一帧画面。
     */
    public static final RecoilProfile PISTOL = new RecoilProfile(
            "pistol", 0.90, 1.80, 5.00, 0.24, 0.055, 0.34);

    /** SMG：每发更轻（0.35°），回落更慢（2.4°/s）→ 扫射时看得见的累积。 */
    public static final RecoilProfile SMG = new RecoilProfile(
            "smg", 0.35, 2.60, 2.40, 0.16, 0.030, 0.20);

    /** 步枪：每发更重（1.6°），回落干净（4.0°/s → 0.40 s 归零，短于 0.5 s 的射击间隔）。 */
    public static final RecoilProfile RIFLE = new RecoilProfile(
            "rifle", 1.60, 2.20, 4.00, 0.34, 0.085, 0.52);

    /**
     * 兜底档案：非枪械（没有 {@link GunPresentationSpec}）时用。
     *
     * <p>它是 {@link #PISTOL} 而不是一套"零后坐"：回落逻辑每步都会跑，
     * 用零值会让"没有档案"变成一种<b>新的、从未被验收过的</b>后坐表现。
     * 沿用既有曲线则保证"没有枪"这一路径与 M2.2 的画面完全一致。
     */
    public static final RecoilProfile DEFAULT = PISTOL;

    /** 全部已注册档案（自测 / 审计用：断言三份齐全且两两不同）。 */
    public static final List<RecoilProfile> ALL = List.of(PISTOL, SMG, RIFLE);

    public RecoilProfile {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("recoilProfileId 不得为空");
        }
        requirePositive(pitchDegPerShot, "pitchDegPerShot");
        requirePositive(maxPitchDeg, "maxPitchDeg");
        requirePositive(recoverDegPerSec, "recoverDegPerSec");
        requirePositive(viewmodelSeconds, "viewmodelSeconds");
        if (viewmodelPushZ < 0) {
            throw new IllegalArgumentException("viewmodelPushZ 不得为负: " + viewmodelPushZ);
        }
        if (viewmodelPitchRad < 0) {
            throw new IllegalArgumentException("viewmodelPitchRad 不得为负: " + viewmodelPitchRad);
        }
        if (pitchDegPerShot > maxPitchDeg) {
            throw new IllegalArgumentException(
                    "单发抬枪量 " + pitchDegPerShot + " 超过了累计上限 " + maxPitchDeg
                            + " —— 那样第一发就会被上限截断，上限失去意义");
        }
    }

    private static void requirePositive(double value, String name) {
        if (!(value > 0.0) || Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " 必须是有限正数: " + value);
        }
    }

    /**
     * 按查表键取档案。
     *
     * <p><b>未知键直接抛异常，而不是回落到 {@link #DEFAULT}。</b>
     * 静默回落正是本项目反复打击的那类失效：一把新枪写错了键，
     * 表现层会"看起来正常"地用别人的曲线，而没有任何一行会说不。
     * 抛出来则是在装配/测试期就把这条数据错误暴露掉。
     */
    public static RecoilProfile byId(String id) {
        if (id == null) {
            return DEFAULT;
        }
        for (RecoilProfile profile : ALL) {
            if (profile.id.equals(id)) {
                return profile;
            }
        }
        throw new IllegalArgumentException(
                "未知的后坐档案键: " + id + "（已注册: pistol / smg / rifle）");
    }

    /**
     * 持续射击到"回落追不上抬枪"时，理论上能顶到的累计角度（度）。
     *
     * <p>它把"累积是否看得见"变成一个可以断言的数：
     * 净增长率 = {@code pitchDegPerShot × fireRate − recoverDegPerSec}。
     * 手枪为负（不累积）、SMG 与步枪需结合各自射速判断 ——
     * 见 {@code RecoilProfileTest} 里按实际 {@code fireRate} 算的那组断言。
     */
    public double netDegPerSecAt(double fireRate) {
        return pitchDegPerShot * fireRate - recoverDegPerSec;
    }
}

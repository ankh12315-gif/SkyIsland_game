package com.skyisland.item;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecoilProfile} —— 三把枪的后坐档案（2026-10-03 裁定第 13 条）。
 *
 * <h2>它守的三件事</h2>
 * <ol>
 *   <li><b>手枪零回归。</b>PISTOL 那一份必须逐值等于被它取代的三个旧常量
 *       （{@code Camera.RECOIL_PITCH_PER_SHOT_DEG=0.9 / MAX_RECOIL_PITCH_DEG=1.8 /
 *       RECOIL_RECOVER_DEG_PER_SEC=5.0}）与 {@code ViewmodelPose} 的三个旧常量
 *       （0.24 s / 0.055 / 0.34）。把"数据化"做成一次视觉回归是最常见的翻车方式。</li>
 *   <li><b>三份档案两两不同。</b>这是本项目"同值巧合"血债的直接产物：
 *       只要三把枪共用一组数，"读了档案"与"读了常量"在行为上就再也分不出来。</li>
 *   <li><b>累积是可算出来的，不是另一条字段。</b>由 {单发抬枪量 × 射速 − 回落速度}
 *       的符号判断：手枪不累积、SMG 累积、步枪在两发之间完全回正。</li>
 * </ol>
 *
 * <p><b>反向验证（TEMP_REVERSE_VERIFY R3）</b>：把步枪的 {@code recoilProfileId}
 * 改回 {@code "pistol"}，{@link #theThreeGunsResolveToThreeDifferentProfiles()} 与
 * {@link #rifleKicksHarderThanPistolByAPerceptibleMargin()} 必须变红。
 */
class RecoilProfileTest {

    /** M2.1 起的旧常量（被 PISTOL 档案取代，逐值钉在这里防回归）。 */
    private static final double LEGACY_PITCH_PER_SHOT = 0.9;
    private static final double LEGACY_MAX_PITCH = 1.8;
    private static final double LEGACY_RECOVER_PER_SEC = 5.0;
    private static final double LEGACY_VIEWMODEL_SECONDS = 0.24;
    private static final double LEGACY_VIEWMODEL_PUSH_Z = 0.055;
    private static final double LEGACY_VIEWMODEL_PITCH_RAD = 0.34;

    @Test
    void pistolProfileIsByteForByteTheLegacyConstants() {
        assertEquals(LEGACY_PITCH_PER_SHOT, RecoilProfile.PISTOL.pitchDegPerShot(), 1e-9,
                "手枪单发抬枪必须仍是 0.9°（数据化不得改变手枪画面）");
        assertEquals(LEGACY_MAX_PITCH, RecoilProfile.PISTOL.maxPitchDeg(), 1e-9,
                "手枪累计上限必须仍是 1.8°");
        assertEquals(LEGACY_RECOVER_PER_SEC, RecoilProfile.PISTOL.recoverDegPerSec(), 1e-9,
                "手枪回落速度必须仍是 5.0°/s");
        assertEquals(LEGACY_VIEWMODEL_SECONDS, RecoilProfile.PISTOL.viewmodelSeconds(), 1e-9,
                "手枪手持物后坐时长必须仍是 0.24 s");
        assertEquals(LEGACY_VIEWMODEL_PUSH_Z, RecoilProfile.PISTOL.viewmodelPushZ(), 1e-9,
                "手枪手持物后坐位移必须仍是 0.055 格");
        assertEquals(LEGACY_VIEWMODEL_PITCH_RAD, RecoilProfile.PISTOL.viewmodelPitchRad(), 1e-9,
                "手枪手持物上扬角必须仍是 0.34 弧度");
    }

    @Test
    void theThreeGunsResolveToThreeDifferentProfiles() {
        RecoilProfile pistol = RecoilProfile.byId(ItemRegistry.pistol().presentation().recoilProfileId());
        RecoilProfile smg = RecoilProfile.byId(ItemRegistry.smg().presentation().recoilProfileId());
        RecoilProfile rifle = RecoilProfile.byId(ItemRegistry.rifle().presentation().recoilProfileId());

        assertSame(RecoilProfile.PISTOL, pistol, "手枪必须解析到 PISTOL 档案");
        assertSame(RecoilProfile.SMG, smg, "SMG 必须解析到 SMG 档案");
        assertSame(RecoilProfile.RIFLE, rifle, "★ 步枪必须解析到 RIFLE 档案，绝不是 PISTOL");

        RecoilProfile[] all = {pistol, smg, rifle};
        String[] names = {"手枪", "SMG", "步枪"};
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                assertNotEquals(all[i].id(), all[j].id(),
                        names[i] + " 与 " + names[j] + " 的后坐档案不得是同一份");
            }
        }
    }

    /** ★ 六个维度全部两两不同 —— 任何一个维度撞值都会让该维度回到"不可证伪"。 */
    @Test
    void everyDimensionIsPairwiseDistinctAcrossTheThreeProfiles() {
        assertAllDistinct("单发抬枪量", RecoilProfile.PISTOL.pitchDegPerShot(),
                RecoilProfile.SMG.pitchDegPerShot(), RecoilProfile.RIFLE.pitchDegPerShot());
        assertAllDistinct("累计上限", RecoilProfile.PISTOL.maxPitchDeg(),
                RecoilProfile.SMG.maxPitchDeg(), RecoilProfile.RIFLE.maxPitchDeg());
        assertAllDistinct("回落速度", RecoilProfile.PISTOL.recoverDegPerSec(),
                RecoilProfile.SMG.recoverDegPerSec(), RecoilProfile.RIFLE.recoverDegPerSec());
        assertAllDistinct("手持物后坐时长", RecoilProfile.PISTOL.viewmodelSeconds(),
                RecoilProfile.SMG.viewmodelSeconds(), RecoilProfile.RIFLE.viewmodelSeconds());
        assertAllDistinct("手持物后坐位移", RecoilProfile.PISTOL.viewmodelPushZ(),
                RecoilProfile.SMG.viewmodelPushZ(), RecoilProfile.RIFLE.viewmodelPushZ());
        assertAllDistinct("手持物上扬角", RecoilProfile.PISTOL.viewmodelPitchRad(),
                RecoilProfile.SMG.viewmodelPitchRad(), RecoilProfile.RIFLE.viewmodelPitchRad());
    }

    private static void assertAllDistinct(String what, double a, double b, double c) {
        Set<Double> values = new HashSet<>();
        values.add(a);
        values.add(b);
        values.add(c);
        assertEquals(3, values.size(),
                what + " 在三份档案里必须两两不同（实际 " + a + " / " + b + " / " + c + "）—— "
                        + "同值会让『读档案』与『读常量』在测试上不可区分");
    }

    /**
     * 步枪每发必须比手枪<b>明显</b>更重（裁定第 13 条：步枪与手枪要有可感知的视觉差异）。
     *
     * <p>判据取 1.4 倍而不是"只要不等"：0.9° 与 0.91° 在 720p 下差 0.1 像素，
     * 那不叫"可感知"。1.6 / 0.9 ≈ 1.78 倍 —— 约 16 像素对 9 像素，肉眼能分。
     */
    @Test
    void rifleKicksHarderThanPistolByAPerceptibleMargin() {
        double ratio = RecoilProfile.RIFLE.pitchDegPerShot() / RecoilProfile.PISTOL.pitchDegPerShot();
        assertTrue(ratio >= 1.4,
                "步枪的单发抬枪必须是手枪的 1.4 倍以上（实际 " + ratio + " 倍）—— "
                        + "差一点点不叫『可感知的视觉差异』");
        assertTrue(RecoilProfile.RIFLE.viewmodelPushZ()
                        >= RecoilProfile.PISTOL.viewmodelPushZ() * 1.4,
                "步枪的手持物后坐位移也必须明显更大");
        assertTrue(RecoilProfile.RIFLE.viewmodelPitchRad()
                        >= RecoilProfile.PISTOL.viewmodelPitchRad() * 1.4,
                "步枪的手持物上扬角也必须明显更大");
    }

    /** SMG 每发更轻 —— 与"重"的步枪朝相反方向取值，两边各钉一次。 */
    @Test
    void smgKicksLighterPerShotThanPistol() {
        assertTrue(RecoilProfile.SMG.pitchDegPerShot() < RecoilProfile.PISTOL.pitchDegPerShot(),
                "SMG 每发必须比手枪轻（裁定第 13 条：每发更轻、但持续射击时累积更明显）");
    }

    /**
     * ★ 累积由 {单发抬枪量 × 射速 − 回落速度} 的符号决定，而不是第三个字段。
     *
     * <p>手枪 4 发/秒、SMG 10 发/秒、步枪 2 发/秒是 PRD 5.4.1 的冻结值；
     * 把三把枪各自的实际射速代进去，得到的正是裁定第 13 条描述的那三种手感。
     */
    @Test
    void accumulationFollowsFromFireRateAndRecoveryNotFromASeparateFlag() {
        double pistolRate = ItemRegistry.pistol().gun().fireRate();
        double smgRate = ItemRegistry.smg().gun().fireRate();
        double rifleRate = ItemRegistry.rifle().gun().fireRate();

        assertTrue(RecoilProfile.PISTOL.netDegPerSecAt(pistolRate) < 0,
                "手枪：4 发/秒 × 0.9° = 3.6°/s < 5.0°/s → 连打也不累积");
        assertTrue(RecoilProfile.SMG.netDegPerSecAt(smgRate) > 0,
                "★ SMG：10 发/秒 × 0.35° = 3.5°/s > 2.4°/s → 扫射时看得见的累积");
        assertTrue(RecoilProfile.RIFLE.netDegPerSecAt(rifleRate) < 0,
                "步枪：2 发/秒 × 1.6° = 3.2°/s < 4.0°/s → 每发之后都会回正");
    }

    /**
     * 步枪"回落干净"的可执行判据：<b>一发</b>之后到下一发之前，后坐必须<b>完全</b>归零。
     *
     * <p>用单发抬枪量而不是累计上限来算：上限（2.2°）是防失控的兜底，
     * 步枪的净增长为负（3.2°/s < 4.0°/s）因此永远顶不到它；
     * 真正会被玩家一轮一轮看到的是"一发抬多少、多久归零"。
     */
    @Test
    void rifleRecoversCompletelyBeforeItsNextShot() {
        double rifleRate = ItemRegistry.rifle().gun().fireRate();
        double interval = 1.0 / rifleRate;
        double secondsToZero =
                RecoilProfile.RIFLE.pitchDegPerShot() / RecoilProfile.RIFLE.recoverDegPerSec();
        assertTrue(secondsToZero <= interval,
                "步枪一发回零需要 " + secondsToZero + " s，必须 ≤ 射击间隔 " + interval
                        + " s —— 否则下一发会叠在上一发的残留上（那就变成 SMG 那种累积手感了）");
    }

    /** SMG 一匣（24 发 / 2.4 秒）之内必须真的顶到它自己的上限 —— 否则"累积"是句空话。 */
    @Test
    void smgReachesItsCapWithinOneMagazine() {
        int magazine = ItemRegistry.smg().gun().magazineSize();
        double smgRate = ItemRegistry.smg().gun().fireRate();
        double seconds = magazine / smgRate;
        double accumulated = RecoilProfile.SMG.netDegPerSecAt(smgRate) * seconds;
        assertTrue(accumulated >= RecoilProfile.SMG.maxPitchDeg(),
                "SMG 打完一匣（" + magazine + " 发 / " + seconds + " s）应累积到 "
                        + accumulated + "°，必须达到上限 " + RecoilProfile.SMG.maxPitchDeg() + "°");
    }

    // ============================================================ 档案本身的护栏

    @Test
    void everyRegisteredGunResolvesToAKnownProfile() {
        for (String id : new String[]{ItemRegistry.PISTOL_ID, ItemRegistry.SMG_ID, ItemRegistry.RIFLE_ID}) {
            String key = ItemRegistry.byName(id).presentation().recoilProfileId();
            assertTrue(RecoilProfile.ALL.stream().anyMatch(p -> p.id().equals(key)),
                    "枪 " + id + " 的 recoilProfileId '" + key + "' 必须能解析到一份已注册档案");
        }
    }

    @Test
    void byIdRejectsUnknownKeysInsteadOfSilentlyFallingBack() {
        assertThrows(IllegalArgumentException.class, () -> RecoilProfile.byId("shotgun"),
                "未知档案键必须抛异常 —— 静默回落到手枪曲线会让『写错了键』变成一场看不见的表现错误");
        assertSame(RecoilProfile.DEFAULT, RecoilProfile.byId(null),
                "null 表示『非枪械』，此时沿用既有曲线（不制造一套没被验收过的零后坐）");
    }

    @Test
    void theProfileRejectsNonsense() {
        assertThrows(IllegalArgumentException.class,
                () -> new RecoilProfile(" ", 1, 2, 3, 0.2, 0.05, 0.3), "空白 id");
        assertThrows(IllegalArgumentException.class,
                () -> new RecoilProfile("x", 0, 2, 3, 0.2, 0.05, 0.3), "单发抬枪为 0");
        assertThrows(IllegalArgumentException.class,
                () -> new RecoilProfile("x", 1, 2, 0, 0.2, 0.05, 0.3), "回落速度为 0");
        assertThrows(IllegalArgumentException.class,
                () -> new RecoilProfile("x", 3, 2, 3, 0.2, 0.05, 0.3),
                "单发抬枪超过上限（那样第一发就被截断，上限失去意义）");
    }

    /** 三份档案齐全（将来加第四把枪时，这里会提醒它必须先有自己的档案）。 */
    @Test
    void allThreeProfilesAreRegistered() {
        assertEquals(3, RecoilProfile.ALL.size());
        Set<String> ids = new HashSet<>();
        RecoilProfile.ALL.forEach(p -> ids.add(p.id()));
        assertEquals(3, ids.size(), "档案 id 不得重复");
        assertFalse(ids.contains(""), "档案 id 不得是空串");
    }
}

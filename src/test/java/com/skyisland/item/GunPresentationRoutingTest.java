package com.skyisland.item;

import com.skyisland.audio.AudioEvent;
import com.skyisland.audio.AudioFeedback;
import com.skyisland.audio.AudioManager;
import com.skyisland.audio.GunAudio;
import com.skyisland.combat.CombatController;
import com.skyisland.entity.EntityManager;
import com.skyisland.player.Camera;
import com.skyisland.player.Player;
import com.skyisland.player.PlayerIntent;
import com.skyisland.render.viewmodel.MuzzleAnchor;
import com.skyisland.render.viewmodel.ViewmodelModel;
import com.skyisland.render.viewmodel.ViewmodelPose;
import com.skyisland.testutil.SourceScan;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ {@link GunPresentationSpec} 九个键的<b>路由</b>总验收（2026-10-03 裁定第 11 / 12 / 13 / 14 条）。
 *
 * <h2>它关的是哪三条死接线</h2>
 * 到本轮为止，{@code GunPresentationSpec} 上的 {@code fireSoundId / emptySoundId /
 * reloadSoundId / recoilProfileId} <b>四个键没有任何生产读者</b>：
 * <ul>
 *   <li>三声枪响写在 {@code AudioFeedback} 里的 {@code AudioEvent.GUN_FIRE / GUN_EMPTY /
 *       RELOAD} 三个硬编码常量上；</li>
 *   <li>后坐的三个数写在 {@code Camera} 与 {@code ViewmodelPose} 的常量上。</li>
 * </ul>
 * 于是"三把枪的表现各不相同"在代码上是不成立的。本类逐条钉住它们现在的路由。
 *
 * <h2>★ 关于"三把枪共用同一条音轨"为什么不是同值假绿</h2>
 * 裁定第 12 条允许共用音轨，但要求路径真的经过 id。
 * 共用一个值立刻带来本项目最熟悉的陷阱：<b>"读了数据"与"读了常量"不可区分</b>。
 * 因此这里的可证伪性不由"三把枪听起来不同"承担，而由三层证据合成：
 * <ol>
 *   <li><b>解析层</b>（{@link #changingRiflesFireSoundIdChangesOnlyRifle()}）：
 *       只改步枪的 {@code fireSoundId}，只有步枪的解析结果跟着变；</li>
 *   <li><b>源码层</b>（{@link #audioFeedbackDoesNotHardcodeAnyGunSound()}）：
 *       {@code AudioFeedback} 的三个回调体里不得再出现那三个硬编码常量，
 *       且必须调用 {@code *SoundId()}；</li>
 *   <li><b>端到端</b>（{@link #eachGunProducesItsOwnSoundsThroughTheRealController()}）：
 *       三把枪各自经真实 {@code CombatController} 打出击发 / 空仓 / 换弹三声。</li>
 * </ol>
 *
 * <p><b>反向验证（TEMP_REVERSE_VERIFY R3）</b>：把步枪的 {@code fireSoundId} 换成
 * {@code "gun_fire"} 之外的值，或把它的 {@code recoilProfileId} 改成 {@code "pistol"}，
 * 本类中带 ★ 的断言必须变红。
 */
class GunPresentationRoutingTest {

    private static final double DT = 1.0 / 60.0;

    private static World world() {
        return TestWorlds.flatWorld(-1, -1, 1, 1);
    }

    private static AudioManager silentAudio() {
        AudioManager audio = new AudioManager();
        audio.open(false);
        return audio;
    }

    /** 手持指定枪 + 它自己的弹药的玩家（步枪用步枪弹，另两把用手枪弹）。 */
    private static Player armedWith(String gunId, int reserve) {
        Player player = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        String ammoId = gunId.equals(ItemRegistry.RIFLE_ID)
                ? ItemRegistry.RIFLE_AMMO_ID : ItemRegistry.PISTOL_AMMO_ID;
        player.inventory().add(ItemRegistry.runtimeIdOf(gunId), 1);
        player.inventory().add(ItemRegistry.runtimeIdOf(ammoId), reserve);
        return player;
    }

    /** 给一把枪的表现规格换一个音效键（其余不变）—— 只改一个键，才能说是"只影响这一把"。 */
    private static GunPresentationSpec withFireSound(GunPresentationSpec base, String soundId) {
        return new GunPresentationSpec(base.viewmodelId(), base.iconId(),
                base.muzzleForward(), base.muzzleRight(), base.muzzleDown(),
                soundId, base.emptySoundId(), base.reloadSoundId(), base.recoilProfileId());
    }

    private static GunPresentationSpec withRecoilProfile(GunPresentationSpec base, String profileId) {
        return new GunPresentationSpec(base.viewmodelId(), base.iconId(),
                base.muzzleForward(), base.muzzleRight(), base.muzzleDown(),
                base.fireSoundId(), base.emptySoundId(), base.reloadSoundId(), profileId);
    }

    // ============================================================ 音效路由（第 11 / 12 条）

    @Test
    void allThreeGunsResolveTheirThreeSoundKeysThroughTheId() {
        for (String gunId : List.of(ItemRegistry.PISTOL_ID, ItemRegistry.SMG_ID, ItemRegistry.RIFLE_ID)) {
            GunPresentationSpec pres = ItemRegistry.byName(gunId).presentation();
            assertEquals(AudioEvent.GUN_FIRE, GunAudio.fireEventOf(pres),
                    gunId + " 的 fireSoundId 必须经 id 解析到 gun_fire");
            assertEquals(AudioEvent.GUN_EMPTY, GunAudio.emptyEventOf(pres),
                    gunId + " 的 emptySoundId 必须经 id 解析到 gun_empty");
            assertEquals(AudioEvent.RELOAD, GunAudio.reloadEventOf(pres),
                    gunId + " 的 reloadSoundId 必须经 id 解析到 reload");
        }
    }

    /**
     * ★ 裁定第 12 条要的那条证据：<b>改步枪的 {@code fireSoundId}，只有步枪的读取结果跟着变</b>。
     *
     * <p>它是"路径真的经过 id"唯一的直接证明。若 {@code GunAudio} 里写的是
     * {@code return AudioEvent.GUN_FIRE;}，改哪个键都不会有任何一件事发生变化 ——
     * 本断言立刻变红。
     */
    @Test
    void changingRiflesFireSoundIdChangesOnlyRifle() {
        GunPresentationSpec pistol = ItemRegistry.pistol().presentation();
        GunPresentationSpec smg = ItemRegistry.smg().presentation();
        GunPresentationSpec rifle = ItemRegistry.rifle().presentation();

        GunPresentationSpec modifiedRifle = withFireSound(rifle, AudioEvent.HIT_ENEMY.id());

        assertEquals(AudioEvent.HIT_ENEMY, GunAudio.fireEventOf(modifiedRifle),
                "把步枪的 fireSoundId 改成 hit_enemy，步枪的击发音必须跟着变");
        assertEquals(AudioEvent.GUN_FIRE, GunAudio.fireEventOf(pistol),
                "手枪不受影响");
        assertEquals(AudioEvent.GUN_FIRE, GunAudio.fireEventOf(smg),
                "SMG 不受影响");

        // 反向：改手枪的，步枪与 SMG 不动 —— 两个方向都走一遍，才排除"永远返回某一个事件"。
        GunPresentationSpec modifiedPistol = withFireSound(pistol, AudioEvent.UI_DENIED.id());
        assertEquals(AudioEvent.UI_DENIED, GunAudio.fireEventOf(modifiedPistol));
        assertEquals(AudioEvent.GUN_FIRE, GunAudio.fireEventOf(rifle), "步枪不受影响");
        assertEquals(AudioEvent.GUN_FIRE, GunAudio.fireEventOf(smg), "SMG 不受影响");
    }

    /** 三个键各自独立：改空仓音不得动击发音（防"三个键取同一个字段"这类错位）。 */
    @Test
    void theThreeSoundKeysAreReadIndependently() {
        GunPresentationSpec pres = ItemRegistry.pistol().presentation();
        GunPresentationSpec modified = new GunPresentationSpec(pres.viewmodelId(), pres.iconId(),
                pres.muzzleForward(), pres.muzzleRight(), pres.muzzleDown(),
                pres.fireSoundId(), AudioEvent.UI_OPEN.id(), AudioEvent.UI_CLOSE.id(),
                pres.recoilProfileId());
        assertEquals(AudioEvent.GUN_FIRE, GunAudio.fireEventOf(modified), "击发音不受影响");
        assertEquals(AudioEvent.UI_OPEN, GunAudio.emptyEventOf(modified), "空仓音走 emptySoundId");
        assertEquals(AudioEvent.UI_CLOSE, GunAudio.reloadEventOf(modified), "换弹音走 reloadSoundId");
    }

    /**
     * 源码层：{@code AudioFeedback} 的三个枪械回调<b>不得</b>再硬编码那三个事件常量。
     *
     * <p>它挡的是"改回 {@code audio.play(AudioEvent.GUN_FIRE)}"这条最省事的回归路径 ——
     * 那条路径下本类其余断言<b>全部仍然通过</b>（因为三把枪共用同一条音轨），
     * 而死键就悄悄回来了。只有源码断言抓得住它。
     */
    @Test
    void audioFeedbackDoesNotHardcodeAnyGunSound() {
        String source = SourceScan.readMain("com/skyisland/audio/AudioFeedback.java");
        String code = SourceScan.withoutComments(source);

        assertFalse(code.contains("AudioEvent.GUN_FIRE"),
                "AudioFeedback 不得再硬编码 AudioEvent.GUN_FIRE —— 击发音必须来自 fireSoundId");
        assertFalse(code.contains("AudioEvent.GUN_EMPTY"),
                "AudioFeedback 不得再硬编码 AudioEvent.GUN_EMPTY —— 空仓音必须来自 emptySoundId");
        assertFalse(code.contains("AudioEvent.RELOAD"),
                "AudioFeedback 不得再硬编码 AudioEvent.RELOAD —— 换弹音必须来自 reloadSoundId");

        // 各自的读法：AudioFeedback 把三个键交给 GunAudio 解析，GunAudio 才是"读键"的那一层。
        // 分两层是被接口逼出来的（Listener 回调参数里没有枪），因此两层都要钉：
        //   少了前者 → 声音不再由当前这把枪决定；少了后者 → 声音不再由键决定。
        assertTrue(SourceScan.methodBody(source, "private AudioEvent fireEvent()")
                        .contains("GunAudio.fireEventOf"),
                "fireEvent() 必须把查表交给 GunAudio（不得自己写死某个 AudioEvent）");
        assertTrue(SourceScan.methodBody(source, "private AudioEvent emptyEvent()")
                        .contains("GunAudio.emptyEventOf"),
                "emptyEvent() 必须把查表交给 GunAudio");
        assertTrue(SourceScan.methodBody(source, "private AudioEvent reloadEvent()")
                        .contains("GunAudio.reloadEventOf"),
                "reloadEvent() 必须把查表交给 GunAudio");

        String gunAudio = SourceScan.readMain("com/skyisland/audio/GunAudio.java");
        assertTrue(SourceScan.methodBody(gunAudio, "public static AudioEvent fireEventOf(")
                        .contains("fireSoundId()"), "GunAudio 必须读 fireSoundId()");
        assertTrue(SourceScan.methodBody(gunAudio, "public static AudioEvent emptyEventOf(")
                        .contains("emptySoundId()"), "GunAudio 必须读 emptySoundId()");
        assertTrue(SourceScan.methodBody(gunAudio, "public static AudioEvent reloadEventOf(")
                        .contains("reloadSoundId()"), "GunAudio 必须读 reloadSoundId()");
    }

    /**
     * 端到端：三把枪各自经真实 {@code CombatController} 打出击发 / 空仓 / 换弹三声。
     *
     * <p>它证明的不是"三声不同"，而是"链对每一把枪都通" ——
     * 步枪用的是它自己的弹药与它自己的表现规格，中途任何一处拿错枪都会断在这里。
     */
    @Test
    void eachGunProducesItsOwnSoundsThroughTheRealController() {
        for (String gunId : List.of(ItemRegistry.PISTOL_ID, ItemRegistry.SMG_ID, ItemRegistry.RIFLE_ID)) {
            AudioManager audio = silentAudio();
            World world = world();
            Player player = armedWith(gunId, 24);
            CombatController combat = new CombatController(new EntityManager());
            CombatController.Listener chain =
                    AudioFeedback.wrap(audio, player).andThen(CombatController.Listener.NONE);

            // ① 上膛 → reload
            combat.step(world, player, PlayerIntent.combat(0, 0, false, 0, 0, false, false, true),
                    DT, chain);
            for (int i = 0; i < 240 && combat.gunFor(player).isReloading(); i++) {
                combat.step(world, player, PlayerIntent.combat(0, 0, false, 0, 0, false, false, false),
                        DT, chain);
            }
            assertEquals(1, audio.audit().countOf(AudioEvent.RELOAD), gunId + "：上膛必须响一次 reload");

            // ② 打一发 → gun_fire（SINGLE 两把都要带按下沿；SMG 是 AUTO，给电平即可）
            PlayerIntent fire = PlayerIntent.combat(0, 0, false, 0, 0, true, false, false)
                    .withAttackPressed(true);
            combat.step(world, player, fire, DT, chain);
            assertEquals(1, audio.audit().countOf(AudioEvent.GUN_FIRE), gunId + "：一发实弹必须响一次 gun_fire");

            // ③ 打空 → gun_empty
            for (int i = 0; i < 3000; i++) {
                combat.step(world, player, fire, DT, chain);
            }
            assertTrue(audio.audit().countOf(AudioEvent.GUN_EMPTY) > 0,
                    gunId + "：打空之后必须响 gun_empty（它是『按了没开枪』的唯一信号）");
        }
    }

    @Test
    void audioEventTableProvidesTheIdLookup() {
        assertEquals(AudioEvent.GUN_FIRE, AudioEvent.byId("gun_fire"));
        assertEquals(AudioEvent.GUN_EMPTY, AudioEvent.byId("gun_empty"));
        assertEquals(AudioEvent.RELOAD, AudioEvent.byId("reload"));
        assertTrue(AudioEvent.has("gun_fire"));
        assertFalse(AudioEvent.has("gun_fire_rifle"), "未收录的 id 必须报 false 而不是抛异常");
    }

    // ============================================================ 后坐路由（第 13 条）

    /** 相机的抬枪量与上限都来自档案，三把枪三份读数。 */
    @Test
    void cameraRecoilComesFromTheProfileNotFromAGlobalConstant() {
        assertEquals(0.90, oneShotKick(RecoilProfile.PISTOL), 1e-9, "手枪 0.9°");
        assertEquals(0.35, oneShotKick(RecoilProfile.SMG), 1e-9, "SMG 0.35°");
        assertEquals(1.60, oneShotKick(RecoilProfile.RIFLE), 1e-9, "★ 步枪 1.6°");

        assertNotEquals(oneShotKick(RecoilProfile.PISTOL), oneShotKick(RecoilProfile.RIFLE), 1e-9,
                "步枪与手枪的抬枪量必须不同 —— 相同就意味着 recoilProfileId 没被读");
    }

    private static double oneShotKick(RecoilProfile profile) {
        Camera camera = new Camera();
        camera.addRecoil(profile);
        return camera.recoilPitchDeg();
    }

    /** 累计上限也来自档案：SMG 能顶到 2.6°，手枪最多 1.8°。 */
    @Test
    void theAccumulationCapComesFromTheProfile() {
        Camera smg = new Camera();
        Camera pistol = new Camera();
        for (int i = 0; i < 40; i++) {
            smg.addRecoil(RecoilProfile.SMG);
            pistol.addRecoil(RecoilProfile.PISTOL);
        }
        assertEquals(RecoilProfile.SMG.maxPitchDeg(), smg.recoilPitchDeg(), 1e-9,
                "SMG 连打必须顶到它自己档案的 2.6°");
        assertEquals(RecoilProfile.PISTOL.maxPitchDeg(), pistol.recoilPitchDeg(), 1e-9,
                "手枪连打最多 1.8°");
        assertNotEquals(smg.recoilPitchDeg(), pistol.recoilPitchDeg(), 1e-9,
                "两者的累计上限必须不同");
    }

    /** 回落速度也来自档案：步枪（4.0°/s）明显快于 SMG（2.4°/s）。 */
    @Test
    void theRecoveryRateComesFromTheProfile() {
        Camera rifle = new Camera();
        Camera smg = new Camera();
        rifle.addRecoil(RecoilProfile.RIFLE);
        smg.addRecoil(RecoilProfile.SMG);
        double rifleBefore = rifle.recoilPitchDeg();
        double smgBefore = smg.recoilPitchDeg();

        rifle.decayRecoil(0.1, RecoilProfile.RIFLE);
        smg.decayRecoil(0.1, RecoilProfile.SMG);

        assertEquals(rifleBefore - RecoilProfile.RIFLE.recoverDegPerSec() * 0.1,
                rifle.recoilPitchDeg(), 1e-9, "步枪按 4.0°/s 回落");
        assertEquals(smgBefore - RecoilProfile.SMG.recoverDegPerSec() * 0.1,
                smg.recoilPitchDeg(), 1e-9, "SMG 按 2.4°/s 回落");
        assertTrue(rifle.recoilPitchDeg() < rifleBefore, "回落必须真的在减");
    }

    /** 手持物的后坐动画：换档案就换手感（{@code ViewmodelPose} 不再是三个写死的常量）。 */
    @Test
    void viewmodelRecoilComesFromTheHeldGunsProfile() {
        double pistolPush = firstFrameRecoilPushFor("pistol");
        double smgPush = firstFrameRecoilPushFor("smg");
        double riflePush = firstFrameRecoilPushFor("rifle");

        assertEquals(RecoilProfile.PISTOL.viewmodelPushZ(), pistolPush, 1e-9);
        assertEquals(RecoilProfile.SMG.viewmodelPushZ(), smgPush, 1e-9);
        assertEquals(RecoilProfile.RIFLE.viewmodelPushZ(), riflePush, 1e-9);
        assertTrue(riflePush > pistolPush * 1.4,
                "★ 步枪的手持物后坐必须明显大于手枪（" + riflePush + " vs " + pistolPush + "）");
        assertTrue(smgPush < pistolPush,
                "SMG 的手持物后坐必须比手枪轻");
    }

    private static double firstFrameRecoilPushFor(String recoilProfileId) {
        ViewmodelModel model = new ViewmodelModel();
        model.gunRecoilProfileId = recoilProfileId;
        model.shotCount = 1;
        ViewmodelPose pose = new ViewmodelPose();
        pose.update(model, 0.0);
        assertSame(RecoilProfile.byId(recoilProfileId), pose.recoilProfile(),
                "姿势必须记住开枪那把枪的档案");
        return pose.recoilPush();
    }

    /** 手持物上扬角同样来自档案。 */
    @Test
    void viewmodelRecoilPitchComesFromTheProfile() {
        double pistolPitch = recoilPitchFor("pistol");
        double riflePitch = recoilPitchFor("rifle");
        double delta = riflePitch - pistolPitch;
        assertEquals(RecoilProfile.RIFLE.viewmodelPitchRad() - RecoilProfile.PISTOL.viewmodelPitchRad(),
                delta, 1e-9,
                "步枪与手枪的枪口上扬角之差必须等于两份档案之差（0.52 − 0.34）");
        assertTrue(delta > 0.1, "差值必须大到看得出来");
    }

    private static double recoilPitchFor(String recoilProfileId) {
        ViewmodelModel model = new ViewmodelModel();
        model.gunRecoilProfileId = recoilProfileId;
        model.shotCount = 1;
        ViewmodelPose pose = new ViewmodelPose();
        pose.update(model, 0.0);
        return pose.pitchRad();
    }

    /**
     * {@code ViewmodelModel} 必须从 {@code presentation().recoilProfileId()} 取键，
     * 非枪一律置 null（否则"上一帧拿步枪、这一帧换成石头"会留下步枪的后坐）。
     */
    @Test
    void viewmodelModelCarriesTheRecoilProfileIdOfTheHeldGun() {
        ViewmodelModel model = new ViewmodelModel();
        model.apply(com.skyisland.player.ItemStack.of(ItemRegistry.rifle().runtimeId(), 1), 0);
        assertEquals(ItemRegistry.RIFLE_RECOIL_PROFILE_ID, model.gunRecoilProfileId,
                "持步枪时必须带上步枪的后坐档案键");

        model.apply(com.skyisland.player.ItemStack.of(ItemRegistry.coal().runtimeId(), 5), 1);
        assertEquals(null, model.gunRecoilProfileId, "换成非枪械后必须清空（不留陈旧的档案键）");
    }

    // ============================================================ 枪口路由（第 14 条）

    /**
     * ★ 步枪的火光不得再从手枪的枪口冒出来。
     *
     * <p>枪口偏移的三个数早就是数据化的（{@code MuzzleAnchor}），这里补的是
     * "三把枪算出来的点确实两两不同"这条端到端证据 ——
     * 它把"数据不同"与"落点不同"连起来，中间任何一处没接都会掉到同一个点上。
     */
    @Test
    void rifleMuzzleIsNotThePistolMuzzle() {
        Vector3d eye = new Vector3d(0.5, 65.0, 0.5);
        Vector3f fwd = new Vector3f(0, 0, -1);
        Vector3f right = new Vector3f(1, 0, 0);
        Vector3f up = new Vector3f(0, 1, 0);

        double[] pistol = new double[3];
        double[] smg = new double[3];
        double[] rifle = new double[3];
        MuzzleAnchor.compute(pistol, eye, fwd, right, up, ItemRegistry.pistol().presentation());
        MuzzleAnchor.compute(smg, eye, fwd, right, up, ItemRegistry.smg().presentation());
        MuzzleAnchor.compute(rifle, eye, fwd, right, up, ItemRegistry.rifle().presentation());

        assertNotEquals(pistol[0], rifle[0], 1e-9, "步枪枪口的 X 不得与手枪相同");
        assertNotEquals(pistol[2], rifle[2], 1e-9, "步枪枪口的 Z 不得与手枪相同");
        assertNotEquals(smg[2], rifle[2], 1e-9, "步枪枪口也不得与 SMG 相同");
        assertTrue(rifle[2] < pistol[2] - 0.2,
                "步枪枪管最长，火光必须明显更靠前（ rifle.z=" + rifle[2] + " pistol.z=" + pistol[2] + "）");
    }

    // ============================================================ 九键审计

    /**
     * ★ 九个键<b>每一个</b>都必须在生产代码里有读者 —— 这是"不再产生新的死键"的护栏。
     *
     * <p>它与 {@code WeaponDataWiringTest#everyGunSpecComponentHasAReaderInProductionCode}
     * 是同一把尺子，量的是 {@code GunPresentationSpec}：本轮之前四个键（三个音效 + 后坐）
     * 一个读者都没有，而它们照样被构造、被校验、被单测断言"非空白"。
     * 把这条断言留在这里，下一把枪再挂新键时就必须先给出读者。
     */
    @Test
    void everyPresentationComponentHasAReaderInProductionCode() {
        String code = SourceScan.allMainCodeExcept("item/GunPresentationSpec.java");

        List<String> components = List.of(
                "viewmodelId", "iconId",
                "muzzleForward", "muzzleRight", "muzzleDown",
                "fireSoundId", "emptySoundId", "reloadSoundId",
                "recoilProfileId");

        List<String> dead = new ArrayList<>();
        for (String component : components) {
            if (!code.contains(component + "()")) {
                dead.add(component);
            }
        }
        assertTrue(dead.isEmpty(),
                "以下 GunPresentationSpec 键在生产代码里没有任何读者（死键）：" + dead
                        + " —— 它们会被构造、被校验、被单测断言，却永远不会影响任何一帧画面");
    }
}

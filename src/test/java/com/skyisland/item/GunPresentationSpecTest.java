package com.skyisland.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GunPresentationSpec} 与手枪迁移的护栏（WEAPON-DOC-001 v2 §4 / §14.6 中不涉及 SMG 的部分）。
 *
 * <h2>这个类守住的两件事</h2>
 * <ol>
 *   <li><b>手枪的枪口偏移必须逐值等于 M2.2 的全局常量。</b>Story 5 把"火光从哪冒"
 *       从游戏主类的常量搬到了每把枪的数据上。这次搬迁最容易发生的回归是
 *       "顺手把 0.55/0.20/0.12 抄错一位"——它不会抛异常，只会让火光落点悄悄偏一点，
 *       而偏一点的枪口在试玩里会被读成"枪感怪"，不会有人怀疑是数据抄错。</li>
 *   <li><b>表现是"键"不是资源，且校验必须生效。</b>非法（空键 / 非有限偏移）必须在
 *       构造期立刻崩，否则坏数据会一路流进渲染与音频层，表现为"某一枪突然没声音"。</li>
 * </ol>
 *
 * <p>本类<b>不</b>测几何绘制与音频播放（那些归 viewmodel / audio 的既有测试），
 * 只测"数据接缝存在且值正确"。
 */
class GunPresentationSpecTest {

    /**
     * M2.2 的枪口偏移基线，逐值与 {@code SkyIslandGame.MUZZLE_FORWARD/RIGHT/DOWN} 相同。
     *
     * <p>刻意在测试里写成字面量而不是引用 {@code SkyIslandGame} —— 后者拖入 GLFW/LWJGL，
     * 会让本无 GL 的用例带上原生依赖。等价性靠这条断言钉住：注册表里的手枪表现
     * 一旦与这组数字不符，火光位置就变了。
     */
    private static final double BASELINE_FORWARD = 0.55;
    private static final double BASELINE_RIGHT = 0.20;
    private static final double BASELINE_DOWN = 0.12;

    /** 一份合法的"基准"构造，供各非法用例只改一个字段来复用。 */
    private static GunPresentationSpec valid() {
        return new GunPresentationSpec(
                "pistol", "pistol",
                0.55, 0.20, 0.12,
                "gun_fire", "gun_empty", "reload", "pistol");
    }

    // ============================================================ 手枪逐字段（v2 §4 / §14.6）

    @Test
    void pistolCarriesAPresentationSpec() {
        Item pistol = ItemRegistry.pistol();
        assertNotNull(pistol.presentation(),
                "手枪必须携带表现规格 —— 这是「表现与逻辑分层」的接缝（v2 §4.1）");
    }

    @Test
    void pistolPresentationMatchesEveryFieldOfTheBaseline() {
        GunPresentationSpec pres = ItemRegistry.pistol().presentation();
        assertEquals(ItemRegistry.PISTOL_VIEWMODEL_ID, pres.viewmodelId(),
                "viewmodelId 必须指向手枪轮廓键");
        assertEquals(ItemRegistry.PISTOL_ICON_ID, pres.iconId(),
                "iconId 必须指向手枪图标键");
        assertEquals(BASELINE_FORWARD, pres.muzzleForward(), 1e-9,
                "muzzleForward 必须 = M2.2 MUZZLE_FORWARD (0.55)，否则枪口闪光位置会跳");
        assertEquals(BASELINE_RIGHT, pres.muzzleRight(), 1e-9,
                "muzzleRight 必须 = M2.2 MUZZLE_RIGHT (0.20)");
        assertEquals(BASELINE_DOWN, pres.muzzleDown(), 1e-9,
                "muzzleDown 必须 = M2.2 MUZZLE_DOWN (0.12)");
        assertEquals(ItemRegistry.SOUND_GUN_FIRE, pres.fireSoundId(),
                "fireSoundId 必须指向现有开火音效键");
        assertEquals(ItemRegistry.SOUND_GUN_EMPTY, pres.emptySoundId(),
                "emptySoundId 必须指向现有空仓音效键");
        assertEquals(ItemRegistry.SOUND_RELOAD, pres.reloadSoundId(),
                "reloadSoundId 必须指向现有换弹音效键");
        assertEquals(ItemRegistry.PISTOL_RECOIL_PROFILE_ID, pres.recoilProfileId(),
                "recoilProfileId 必须指向现有后坐表现键");
    }

    /**
     * 音效键必须是 {@code audio.AudioEvent} 里真实存在的稳定 id ——
     * 否则"键指向一个不存在的事件"，表现层解析时要么静默无声、要么崩。
     *
     * <p>这里只做字符串层面的核对（不 import audio 包以免牵动音频类）：
     * 三条键的取值就是 M2.1 音频事件表里的三个 id。
     */
    @Test
    void soundIdsPointAtRealAudioEventIds() {
        GunPresentationSpec pres = ItemRegistry.pistol().presentation();
        assertEquals("gun_fire", pres.fireSoundId());
        assertEquals("gun_empty", pres.emptySoundId());
        assertEquals("reload", pres.reloadSoundId());
    }

    /** {@link ItemRegistry#pistolPresentation()} 与手枪物品上挂的必须是同一个值（逐字段相等）。 */
    @Test
    void registryFactoryAndItemAgreeOnThePistolPresentation() {
        assertEquals(ItemRegistry.pistolPresentation(), ItemRegistry.pistol().presentation(),
                "手枪的表现规格只能有一个来源");
    }

    // ============================================================ SMG 表现（v2 §4.2 / §14.6）

    /**
     * SMG 必须携带表现规格，且逐字段等于 v2 §4.2 的要求。
     *
     * <p><b>反向验证（TEMP_REVERSE_VERIFY）</b>：把 {@code ItemRegistry.SMG_VIEWMODEL_ID}
     * 改成 {@code "pistol"}（即"逻辑上是 SMG、右手仍是一模一样的手枪模型"），
     * {@link #theTwoGunsDifferInViewmodelIconAndMuzzle()} 会立刻变红。
     */
    @Test
    void smgCarriesItsOwnPresentationSpec() {
        Item smg = ItemRegistry.smg();
        assertNotNull(smg.presentation(), "SMG 必须携带表现规格（v2 §4.2）");

        GunPresentationSpec pres = smg.presentation();
        assertEquals(ItemRegistry.SMG_VIEWMODEL_ID, pres.viewmodelId(), "viewmodelId 指向 SMG 轮廓键");
        assertEquals(ItemRegistry.SMG_ICON_ID, pres.iconId(), "iconId 指向 SMG 图标键");
        assertEquals(ItemRegistry.SMG_MUZZLE_FORWARD, pres.muzzleForward(), 1e-9);
        assertEquals(ItemRegistry.SMG_MUZZLE_RIGHT, pres.muzzleRight(), 1e-9);
        assertEquals(ItemRegistry.SMG_MUZZLE_DOWN, pres.muzzleDown(), 1e-9);
        assertEquals(ItemRegistry.SMG_RECOIL_PROFILE_ID, pres.recoilProfileId(),
                "后坐表现键与手枪不同（+ 射速不同，合起来满足 v2 §4.2 第④项）");
        // 音效复用 M2.1 已有事件（M3 不新增音频资源）；第④项由"射击节奏"满足
        assertEquals(ItemRegistry.SOUND_GUN_FIRE, pres.fireSoundId());
        assertEquals(ItemRegistry.SOUND_GUN_EMPTY, pres.emptySoundId());
        assertEquals(ItemRegistry.SOUND_RELOAD, pres.reloadSoundId());
    }

    /**
     * v2 §14.6 的核心断言：pistol 与 smg 在 Viewmodel / 图标 / 枪口位置三处都必须不同。
     *
     * <p>这三条是"玩家能看出换了枪"的数据前提。任一条相等 → 对应的表现项就没差异。
     * <b>反向验证（TEMP_REVERSE_VERIFY）</b>：把 SMG 的 viewmodelId 改成 pistol，本条必红。
     */
    @Test
    void theTwoGunsDifferInViewmodelIconAndMuzzle() {
        GunPresentationSpec pistol = ItemRegistry.pistol().presentation();
        GunPresentationSpec smg = ItemRegistry.smg().presentation();

        assertNotEquals(pistol.viewmodelId(), smg.viewmodelId(),
                "两把枪的 Viewmodel 轮廓键必须不同（v2 §4.2 第①项）");
        assertNotEquals(pistol.iconId(), smg.iconId(),
                "两把枪的图标键必须不同（v2 §4.2 第②项）");

        boolean muzzleDiffers =
                Math.abs(pistol.muzzleForward() - smg.muzzleForward()) > 1e-9
                        || Math.abs(pistol.muzzleRight() - smg.muzzleRight()) > 1e-9
                        || Math.abs(pistol.muzzleDown() - smg.muzzleDown()) > 1e-9;
        assertTrue(muzzleDiffers,
                "两把枪的枪口偏移必须至少有一处不同（v2 §4.2 第③项 / §14.6）");
        // 实际三个分量都不同：SMG 枪管更长、持得更外张
        assertNotEquals(pistol.muzzleForward(), smg.muzzleForward(), 1e-9);
        assertNotEquals(pistol.muzzleRight(), smg.muzzleRight(), 1e-9);
        assertNotEquals(pistol.muzzleDown(), smg.muzzleDown(), 1e-9);

        assertNotEquals(pistol.recoilProfileId(), smg.recoilProfileId(),
                "后坐表现键必须不同（v2 §4.2 第④项的前半）");
    }

    /** 所有枪口偏移必须是有限值 —— NaN 会污染整个粒子系统的坐标（v2 §4.1）。 */
    @Test
    void smgMuzzleOffsetsAreFinite() {
        GunPresentationSpec pres = ItemRegistry.smg().presentation();
        assertTrue(Double.isFinite(pres.muzzleForward()));
        assertTrue(Double.isFinite(pres.muzzleRight()));
        assertTrue(Double.isFinite(pres.muzzleDown()));
    }

    /** {@link ItemRegistry#smgPresentation()} 与 SMG 物品上挂的必须是同一个值（逐字段相等）。 */
    @Test
    void registryFactoryAndItemAgreeOnTheSmgPresentation() {
        assertEquals(ItemRegistry.smgPresentation(), ItemRegistry.smg().presentation(),
                "SMG 的表现规格只能有一个来源");
    }

    /** 两把枪的表现规格不得相等（否则说明 SMG 直接复用了手枪那一份）。 */
    @Test
    void theTwoGunPresentationsAreNotTheSameRecord() {
        assertNotEquals(ItemRegistry.pistolPresentation(), ItemRegistry.smgPresentation(),
                "SMG 不得直接复用 pistolPresentation()");
    }

    // ============================================================ 非枪械不带表现规格

    @Test
    void nonGunItemsHaveNoPresentation() {
        assertNull(ItemRegistry.coal().presentation(), "煤炭不是枪，不应有表现规格");
        assertNull(ItemRegistry.pistolAmmo().presentation(), "弹药不是枪，不应有表现规格");
        assertNull(ItemRegistry.empty().presentation(), "空槽不应有表现规格");
    }

    /**
     * 把表现规格挂到非枪械物品上必须被构造期拦下 —— 否则"表现层按枪查 presentation"
     * 这条接缝会失去意义。
     */
    @Test
    void constructingANonGunItemWithPresentationIsRejected() {
        GunPresentationSpec pres = valid();
        assertThrows(IllegalArgumentException.class,
                () -> new Item(999_998, "skyisland:bad_probe", ItemKind.MATERIAL, null, 64,
                        null, pres),
                "非枪械物品携带表现规格应当立刻失败");
    }

    // ============================================================ 构造校验

    @Test
    void validBaselineConstructsFine() {
        assertEquals("pistol", valid().viewmodelId());
    }

    @Test
    void rejectsNullViewmodelId() {
        assertThrows(IllegalArgumentException.class, () -> new GunPresentationSpec(
                null, "pistol", 0.55, 0.20, 0.12,
                "gun_fire", "gun_empty", "reload", "pistol"));
    }

    @Test
    void rejectsBlankViewmodelId() {
        assertThrows(IllegalArgumentException.class, () -> new GunPresentationSpec(
                "   ", "pistol", 0.55, 0.20, 0.12,
                "gun_fire", "gun_empty", "reload", "pistol"));
    }

    @Test
    void rejectsNullIconId() {
        assertThrows(IllegalArgumentException.class, () -> new GunPresentationSpec(
                "pistol", null, 0.55, 0.20, 0.12,
                "gun_fire", "gun_empty", "reload", "pistol"));
    }

    @Test
    void rejectsBlankIconId() {
        assertThrows(IllegalArgumentException.class, () -> new GunPresentationSpec(
                "pistol", "  ", 0.55, 0.20, 0.12,
                "gun_fire", "gun_empty", "reload", "pistol"));
    }

    @Test
    void rejectsBlankSoundId() {
        assertThrows(IllegalArgumentException.class, () -> new GunPresentationSpec(
                "pistol", "pistol", 0.55, 0.20, 0.12,
                "", "gun_empty", "reload", "pistol"));
    }

    @Test
    void rejectsBlankRecoilProfileId() {
        assertThrows(IllegalArgumentException.class, () -> new GunPresentationSpec(
                "pistol", "pistol", 0.55, 0.20, 0.12,
                "gun_fire", "gun_empty", "reload", null));
    }

    /** 偏移必须有限：NaN 会污染整个粒子系统的坐标。 */
    @Test
    void rejectsNonFiniteMuzzleOffsets() {
        assertThrows(IllegalArgumentException.class, () -> new GunPresentationSpec(
                "pistol", "pistol", Double.NaN, 0.20, 0.12,
                "gun_fire", "gun_empty", "reload", "pistol"));
        assertThrows(IllegalArgumentException.class, () -> new GunPresentationSpec(
                "pistol", "pistol", 0.55, Double.POSITIVE_INFINITY, 0.12,
                "gun_fire", "gun_empty", "reload", "pistol"));
        assertThrows(IllegalArgumentException.class, () -> new GunPresentationSpec(
                "pistol", "pistol", 0.55, 0.20, Double.NEGATIVE_INFINITY,
                "gun_fire", "gun_empty", "reload", "pistol"));
    }

    /** 负值偏移是合法的表现调参（把枪口调高一点 / 偏左一点都允许）。 */
    @Test
    void allowsNegativeMuzzleOffsets() {
        GunPresentationSpec pres = new GunPresentationSpec(
                "pistol", "pistol", -0.55, -0.20, -0.12,
                "gun_fire", "gun_empty", "reload", "pistol");
        assertEquals(-0.55, pres.muzzleForward(), 1e-9);
    }

    // ============================================================ 身份

    @Test
    void itemIdentityIsByRuntimeIdNotPresentation() {
        // presentation 不参与 equals/hashCode（Item 的相等性仍只认 runtimeId）
        assertSame(ItemRegistry.pistol(), ItemRegistry.byName(ItemRegistry.PISTOL_ID));
    }
}

package com.skyisland.render.viewmodel;

import com.skyisland.item.GunPresentationSpec;
import com.skyisland.item.ItemRegistry;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 枪口锚点几何的护栏 —— <b>"枪口闪光用拿的那把枪的表现数据，而不是全局常量"的可执行形式</b>。
 *
 * <h2>这条接缝为什么值得测</h2>
 * M2.1 缺陷 A 是"枪口闪光被生成在眼睛处、被相机吞进立方体里糊成白光"。
 * 修复它靠的是"眼睛 + 前向×0.55 + 右向×0.20 − 上向×0.12"这段几何，
 * 但那段几何当时写在游戏主类里、用的是<u>全局常量</u>，而且没有任何断言看得见它 ——
 * 因此当时既无法证明它对了、也无法证明它没退化。
 *
 * <p>Story 5 把偏移搬到每把枪的 {@link GunPresentationSpec#muzzleForward() 表现规格} 上，
 * 并把几何抽成无 GL 的纯函数 {@link MuzzleAnchor}。于是本类可以：
 * <ol>
 *   <li>证明起点不是眼睛（火光确实被推出去了）；</li>
 *   <li><b>证明落点随数据走</b>：改 presentation 的 muzzleForward，落点必须随之变化 ——
 *       这一条正是"用的是数据而不是常量"的判据（若代码仍读常量，改数据它不会动）。</li>
 * </ol>
 */
class MuzzleAnchorTest {

    private static final Vector3d EYE = new Vector3d(0, 0, 0);
    /** yaw=0：前向 −Z、右向 +X、上向 +Y（与 Camera 的约定一致）。 */
    private static final Vector3f FWD = new Vector3f(0, 0, -1);
    private static final Vector3f RIGHT = new Vector3f(1, 0, 0);
    private static final Vector3f UP = new Vector3f(0, 1, 0);

    private static GunPresentationSpec pres(double f, double r, double d) {
        return new GunPresentationSpec("v", "i", f, r, d, "gun_fire", "gun_empty", "reload", "p");
    }

    @Test
    void muzzleIsPushedAwayFromTheEyeByTheConfiguredOffsets() {
        double[] out = new double[3];
        MuzzleAnchor.compute(out, EYE, FWD, RIGHT, UP, pres(0.55, 0.20, 0.12));

        // 前向(−Z)×0.55 → z = −0.55；右向(+X)×0.20 → x = +0.20；−上向×0.12 → y = −0.12
        assertEquals(0.20, out[0], 1e-9, "x 必须 = right×0.20（火光落在屏幕中心右侧）");
        assertEquals(-0.12, out[1], 1e-9, "y 必须 = −down×0.12（枪握在右下，火光在中心下方）");
        assertEquals(-0.55, out[2], 1e-9, "z 必须 = forward×0.55（火光在眼睛前方）");
        assertNotEquals(EYE, new Vector3d(out[0], out[1], out[2]),
                "枪口绝不能等于眼睛 —— 那正是缺陷 A（火光被相机吞进立方体里）");
    }

    /**
     * 落点必须随 presentation 的 muzzleForward 变化 —— 这是"数据驱动"的判据。
     *
     * <p>若实现仍读全局常量，改这里的表现数据落点不会动，本断言会红。
     */
    @Test
    void muzzleMovesWithThePresentationDataNotAGlobalConstant() {
        double[] a = new double[3];
        double[] b = new double[3];
        MuzzleAnchor.compute(a, EYE, FWD, RIGHT, UP, pres(0.55, 0.20, 0.12));
        MuzzleAnchor.compute(b, EYE, FWD, RIGHT, UP, pres(0.90, 0.20, 0.12));

        assertNotEquals(a[2], b[2], "改 muzzleForward 后，枪口的 z 必须随之变化");
        assertEquals(-0.90, b[2], 1e-9, "新的前向偏移 0.90 必须精确体现在落点上");
    }

    @Test
    void rightAndDownOffsetsAlsoTrackTheData() {
        double[] out = new double[3];
        MuzzleAnchor.compute(out, EYE, FWD, RIGHT, UP, pres(1.0, 0.0, 0.0));
        assertEquals(0.0, out[0], 1e-9, "right=0 → 无右偏");
        assertEquals(0.0, out[1], 1e-9, "down=0 → 无下偏");

        MuzzleAnchor.compute(out, EYE, FWD, RIGHT, UP, pres(1.0, 0.35, 0.40));
        assertEquals(0.35, out[0], 1e-9);
        assertEquals(-0.40, out[1], 1e-9);
    }

    /** 用真实手枪的数据算一遍：必须以眼睛为起点、前方 0.55 格。 */
    @Test
    void realPistolDataProducesTheExpectedMuzzle() {
        double[] out = new double[3];
        MuzzleAnchor.compute(out, EYE, FWD, RIGHT, UP, ItemRegistry.pistol().presentation());

        assertEquals(0.20, out[0], 1e-9);
        assertEquals(-0.12, out[1], 1e-9);
        assertEquals(-0.55, out[2], 1e-9);
    }

    /** 标量重载与 record 重载必须是同一段几何（兜底路径与数据路径不能各算各的）。 */
    @Test
    void scalarOverloadMatchesThePresentationOverload() {
        double[] viaRecord = new double[3];
        double[] viaScalars = new double[3];
        MuzzleAnchor.compute(viaRecord, EYE, FWD, RIGHT, UP, pres(0.55, 0.20, 0.12));
        MuzzleAnchor.compute(viaScalars, EYE, FWD, RIGHT, UP, 0.55, 0.20, 0.12);

        assertEquals(viaRecord[0], viaScalars[0], 1e-12);
        assertEquals(viaRecord[1], viaScalars[1], 1e-12);
        assertEquals(viaRecord[2], viaScalars[2], 1e-12);
    }
}

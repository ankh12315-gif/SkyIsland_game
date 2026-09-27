package com.skyisland.render.viewmodel;

import com.skyisland.item.GunPresentationSpec;
import org.joml.Vector3d;
import org.joml.Vector3fc;

/**
 * 枪口世界坐标的<b>纯计算</b>（WEAPON-DOC-001 v2 §4.2 第 3 点、§14.6）。
 *
 * <h2>为什么从 SkyIslandGame 里抽出来</h2>
 * M2.2 这一步写成"眼睛 + 前向×{@code MUZZLE_FORWARD} + 右向×{@code MUZZLE_RIGHT}
 * − 上向×{@code MUZZLE_DOWN}"，三个 {@code MUZZLE_*} 是 {@code SkyIslandGame} 里的
 * <b>全局常量</b> —— 只有一把枪时看不出问题，一旦有两把枪，"所有枪共用同一个枪口位置"
 * 就会让"左手感不同的枪却从同一点冒火光"。M3 把枪口偏移挂到每把枪的
 * {@link GunPresentationSpec#muzzleForward()} 上（数据化），本类负责那段几何换算。
 *
 * <p>抽成独立类而不是留在游戏主类里，唯一理由是<b>可测</b>：{@code SkyIslandGame} 拖入
 * GLFW/LWJGL，任何引用它的单测都要先加载原生库。把这段纯几何搬到这里，
 * "枪口确实落在眼睛前方而不是眼睛处"就能在无 GL 的单测里直接断言
 * （见 {@code MuzzleAnchorTest}）—— 这正是 M2.1 缺陷 A（火光生在眼睛里、被相机吞掉）
 * 当初没能在单测层被发现的原因。
 *
 * <p><b>为什么不复用第一人称 viewmodel 的枪口几何：</b>viewmodel 的坐标是<b>视图空间</b>
 * 的屏幕摆位（见 {@link ViewmodelGeometry}），它的"枪口"是为观看服务的画面元素；
 * 而这里的枪口是<b>世界空间</b>里粒子特效的生成点。两者恰好都叫"枪口"，
 * 但一个要贴在屏幕上、一个要落在世界里，硬绑在一起只会让"改屏幕摆位"动到"粒子出生点"。
 */
public final class MuzzleAnchor {

    private MuzzleAnchor() {
    }

    /**
     * 按"眼睛 + 前向×forward + 右向×right − 上向×down"算出枪口世界坐标，写入 {@code out}。
     *
     * <p>方向依据（与 M2.1 缺陷 A 的推导一致）：{@code right} 指向屏幕右、
     * {@code up} 恒为世界 +Y；因此"−up"即向下，枪口落在屏幕中心右下方。
     *
     * @param out  长度 ≥ 3 的输出数组，写入 {@code [x, y, z]}
     * @param eye  眼睛（射线起点）世界坐标
     * @param fwd  视线前向单位向量
     * @param right 视线右向单位向量
     * @param up   世界上方向单位向量
     * @param pres 手持枪的表现规格，提供 muzzleForward/Right/Down
     */
    public static void compute(double[] out, Vector3d eye,
                               Vector3fc fwd, Vector3fc right, Vector3fc up,
                               GunPresentationSpec pres) {
        compute(out, eye, fwd, right, up,
                pres.muzzleForward(), pres.muzzleRight(), pres.muzzleDown());
    }

    /**
     * 同 {@link #compute(double[], Vector3d, Vector3fc, Vector3fc, Vector3fc, GunPresentationSpec)}，
     * 但直接用三个偏移量。
     *
     * <p>存在的理由：手持物品<u>没有</u>表现规格时（如将来某种非枪的射击道具），
     * 调用方仍需要一个稳定的枪口 —— 这条路径不该被迫构造一个假的
     * {@link GunPresentationSpec} 只为读三个数字。
     */
    public static void compute(double[] out, Vector3d eye,
                               Vector3fc fwd, Vector3fc right, Vector3fc up,
                               double forward, double rightOffset, double down) {
        out[0] = eye.x + fwd.x() * forward + right.x() * rightOffset - up.x() * down;
        out[1] = eye.y + fwd.y() * forward + right.y() * rightOffset - up.y() * down;
        out[2] = eye.z + fwd.z() * forward + right.z() * rightOffset - up.z() * down;
    }
}

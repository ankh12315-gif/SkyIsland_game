package com.skyisland.combat;

import org.joml.Vector3d;

/**
 * 弹丸散布方向生成（{@code GunSpec.pelletCount} / {@code GunSpec.spreadRad} 的唯一落地点）。
 *
 * <h2>它解决的是什么问题</h2>
 * M3 的两把枪（手枪 / 冲锋枪）都是"单弹丸 + 无散布"，因此"一次击发 = 一条射线"。
 * 但这不等于 {@code pelletCount} / {@code spreadRad} 可以没有读者：
 * 若战斗层不读它们，那么将来把霰弹枪写进注册表（{@code pelletCount=6, spreadRad=0.08}）
 * 只会得到<b>一把伤害 6 倍的手枪</b> —— 单发、无散布、打出去只有一条弹道，
 * 而数据表上却写着"6 丸 6 伤"。这种缺陷不会崩、不会报错，只会让枪"手感不对"。
 *
 * <p>因此本类把"第 i 颗弹丸朝哪个方向"这件事独立成<b>纯函数</b>：
 * 它不碰世界、不碰实体、不分配对象，可以在单测里用任意 {@code pelletCount} /
 * {@code spreadRad} 直接核对几何性质（在锥内、不共线、零散布时与准星严格同向）。
 *
 * <h2>为什么是确定性的螺旋，而不是随机数</h2>
 * <ul>
 *   <li><b>可测</b>：同一组入参必须给出同一组方向，否则"散布在锥内"这类断言
 *       只能靠统计，而统计断言在少量样本上必然不稳。</li>
 *   <li><b>可复现</b>：自测与真人试玩报告里出现"这一枪散了"时，能重放出来。</li>
 *   <li><b>不需要 Random 状态</b>：{@code Random} 是每局状态，会进存档口径讨论；
 *       而弹丸方向是"本发子弹的纯函数"，不该有跨发状态。</li>
 * </ul>
 *
 * <h2>零分配</h2>
 * 两个方法都把结果写进调用方给的向量，类内不 {@code new} 任何对象。
 * 这直接服务于 v2 §15「禁止因高射速增加每发分配」：散布采样发生在<b>每发循环</b>里，
 * 是唯一会随射速放大的分配点，因此它必须是零分配的。
 *
 * <p><b>前置条件：</b>{@code forward} 必须是单位向量（{@link #basis} 与 {@link #offset}
 * 都不会再做归一化 —— 那是每颗弹丸一次的额外开销）。
 */
public final class ShotSpread {

    /**
     * 黄金角（弧度 ≈ 2.39996）。
     *
     * <p>用它做方位角的步进，第 i 个采样点的方位角是 {@code i × 黄金角}。
     * 黄金角与 2π 的比值具有最差的有理逼近性质，因此<b>任意前 n 个点都不会共线或扎堆</b> ——
     * 用等分角（{@code 2πi/n}）在 n 较大时会形成规则的星形图案，
     * 而用一个小步长会全挤在一侧。
     */
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    /**
     * 方位参考轴的选择阈值。
     *
     * <p>由 {@code forward} 与一个固定参考轴叉乘构造"右"向量时，若两者接近平行，
     * 叉积会退化成零向量（归一化后得到 NaN，整个弹道变成 NaN）。视线接近垂直向下
     * （{@code |forward.x| ≈ 0}）在挖矿时是常态，因此必须换轴。
     * 取 0.9 而不是 1.0：{@code |x|=0.9} 时叉积长度仍有 {@code sqrt(1-0.81)=0.436}，
     * 归一化数值上是安全的。
     */
    private static final double PARALLEL_GUARD = 0.9;

    private ShotSpread() {
    }

    /**
     * 由前向向量构造一组正交基，供 {@link #offset} 使用。
     *
     * <p>结果写进调用方给的 {@code right} / {@code up}，<b>不产生任何临时对象</b>。
     * 三者构成右手系：{@code right × forward = up}。
     *
     * @param forward 单位前向向量（准星方向）
     * @param right   输出：单位右向量
     * @param up      输出：单位上向量
     */
    public static void basis(Vector3d forward, Vector3d right, Vector3d up) {
        if (Math.abs(forward.x) < PARALLEL_GUARD) {
            // forward × (1,0,0) = (0, fz, −fy)
            right.set(0.0, forward.z, -forward.y);
        } else {
            // forward × (0,1,0) = (−fz, 0, fx)
            right.set(-forward.z, 0.0, forward.x);
        }
        right.normalize();
        // up = right × forward（对单位正交基，结果自动是单位向量，但仍归一化一次以吃掉浮点误差）
        up.set(right).cross(forward).normalize();
    }

    /**
     * 计算第 {@code index} 颗弹丸的世界方向，写进 {@code out}。
     *
     * <p><b>索引 0 恒落在正中心</b>（与 {@code spreadRad} 无关）。这是刻意的：
     * 准星指着的地方必须<b>至少</b>有一颗弹丸按原方向飞出去，
     * 否则"我把准星压在它头上"这件事会变成"整圈都偏了，中心一发没打中"。
     * 其余 {@code total−1} 颗按面积均匀（半径取 {@code sqrt}，而不是线性）在锥内螺旋展开。
     *
     * <p><b>几何保证（由单测钉住）：</b>
     * <ol>
     *   <li>{@code spreadRad == 0} 或 {@code total <= 1} → {@code out} 逐位等于 {@code forward}
     *       —— 这正是 M3 两把枪"准星指向即命中"不回退的依据；</li>
     *   <li>任意入参下，{@code out} 与 {@code forward} 的夹角 {@code <= spreadRad}
     *       （严格小于，除索引 0 的 0 之外）；</li>
     *   <li>同一组入参给出同一组方向（确定性）。</li>
     * </ol>
     *
     * @param forward   单位前向向量（准星方向）
     * @param right     {@link #basis} 算出的单位右向量
     * @param up        {@link #basis} 算出的单位上向量
     * @param spreadRad 散布半角（弧度）；0 表示无散布
     * @param index     第几颗（0 起）
     * @param total     本发共有几颗（{@code >= 1}）
     * @param out       输出向量（可与 {@code forward} 之外的任何对象别名）
     */
    public static void offset(Vector3d forward, Vector3d right, Vector3d up,
                             double spreadRad, int index, int total, Vector3d out) {
        if (!(spreadRad > 0.0) || total <= 1 || index <= 0) {
            out.set(forward);
            return;
        }
        int spreadCount = total - 1;                        // 参与散布的弹丸数
        int k = Math.min(index, spreadCount) - 1;           // 0 起，越界一律退到最外圈
        double theta = GOLDEN_ANGLE * k;                    // 方位角
        // 半径取 sqrt((k+0.5)/spreadCount)：落在环上的点因此"按面积"均匀分布，
        // 而不是全部挤在圆心附近（线性半径下，圆心附近的点密度会明显偏高）。
        double radius = spreadRad * Math.sqrt((k + 0.5) / spreadCount);
        double sin = Math.sin(radius);
        double cos = Math.cos(radius);
        double dx = Math.cos(theta) * sin;                  // 沿 right 的分量
        double dy = Math.sin(theta) * sin;                  // 沿 up 的分量
        out.set(
                forward.x * cos + right.x * dx + up.x * dy,
                forward.y * cos + right.y * dx + up.y * dy,
                forward.z * cos + right.z * dx + up.z * dy);
    }
}

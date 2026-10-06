package com.skyisland.combat;

import com.skyisland.testutil.SourceScan;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹丸散布的几何性质（{@link ShotSpread}）。
 *
 * <h2>为什么这些断言必须存在（M3 接线修正）</h2>
 * M3 的两把枪都是"单弹丸 + 无散布"。这意味着 {@link ShotSpread} 在<b>今天的产品里
 * 永远只跑最简的一次迭代</b>，没有任何真人玩家能通过试玩发现它算错了。
 * 也就是说，它是典型的"只能靠单测保住的代码" —— 一旦写错，症状要等到
 * 第一把霰弹枪上线才会出现，而那时错误已经被埋在很多次提交之下。
 *
 * <p>因此这里不测"跑一遍不崩"，而是测四条几何不变式：
 * <ol>
 *   <li><b>基向量正交归一</b>，且视线垂直向下（挖矿时的常态）也不退化；</li>
 *   <li>{@code spreadRad = 0} 时方向与准星<b>逐位相同</b> —— 这是"两把枪手感不回退"的依据；</li>
 *   <li>任意入参下所有弹丸都在散布锥内、且都是单位向量；</li>
 *   <li>有散布时方向真的分开了（不是"看起来像散布、实际全部重叠"）。</li>
 * </ol>
 */
class ShotSpreadTest {

    /** 纯浮点复合运算的容差；方向由 cos/sin 复合成，误差在 1e-12 量级。 */
    private static final double EPS = 1e-12;

    private static Vector3d unit(double x, double y, double z) {
        return new Vector3d(x, y, z).normalize();
    }

    /**
     * 视线方向的代表性样本。
     *
     * <p>刻意包含四个"退化位形"：正前、正后、正右、正下、正上。
     * 其中 {@code (0,±1,0)}（抬头看天 / 低头挖矿）与 {@code (±1,0,0)} 是构造基向量时
     * 最容易退化成零向量（归一化后得到 NaN）的方向 —— 挖矿时视线就是朝下的。
     */
    private static List<Vector3d> forwards() {
        return List.of(
                unit(0, 0, -1), unit(0, 0, 1),
                unit(1, 0, 0), unit(-1, 0, 0),
                unit(0, -1, 0), unit(0, 1, 0),
                unit(0.5, -0.6, 0.6245), unit(0.99, 0.01, -0.14));
    }

    private static double angleBetween(Vector3d a, Vector3d b) {
        double cosine = a.dot(b) / (a.length() * b.length());
        return Math.acos(Math.max(-1.0, Math.min(1.0, cosine)));
    }

    // ============================================================ 基向量

    @Test
    void basisStaysOrthonormalEvenWhenTheViewIsStraightDown() {
        for (Vector3d forward : forwards()) {
            Vector3d right = new Vector3d();
            Vector3d up = new Vector3d();
            ShotSpread.basis(forward, right, up);
            String at = "（forward=" + forward + "）";

            assertEquals(1.0, right.length(), 1e-9, "right 必须是单位向量" + at);
            assertEquals(1.0, up.length(), 1e-9, "up 必须是单位向量" + at);
            assertEquals(0.0, forward.dot(right), 1e-9, "right 必须垂直于 forward" + at);
            assertEquals(0.0, forward.dot(up), 1e-9, "up 必须垂直于 forward" + at);
            assertEquals(0.0, right.dot(up), 1e-9, "up 必须垂直于 right" + at);
            // 右手系：right × forward = up。offset 里的复合旋转依赖这条，
            // 方向搞反了不会崩，只会让散布相对准星左右/上下镜像。
            assertTrue(new Vector3d(right).cross(forward).distance(up) < 1e-9,
                    "right × forward 必须等于 up（右手系）" + at);
        }
    }

    // ============================================================ 零散布（M3 两把枪的现状）

    @Test
    void zeroSpreadSendsEveryPelletExactlyAlongTheCrosshair() {
        for (Vector3d forward : forwards()) {
            Vector3d right = new Vector3d();
            Vector3d up = new Vector3d();
            Vector3d out = new Vector3d();
            ShotSpread.basis(forward, right, up);
            for (int total : new int[]{1, 2, 6, 12}) {
                for (int index = 0; index < total; index++) {
                    ShotSpread.offset(forward, right, up, 0.0, index, total, out);
                    assertEquals(0.0, out.distance(forward), EPS,
                            "spreadRad=0 时第 " + index + "/" + total + " 颗必须与准星逐位同向"
                                    + "（" + forward + "）");
                }
            }
        }
    }

    /**
     * {@code pelletCount = 1} 时必须完全忽略 {@code spreadRad}。
     *
     * <p>这条不是"零散布"的重复：它钉的是<b>另一个入参组合</b>（1 颗 + 有散布）。
     * 若实现写成"先按 spreadRad 随机一个方向，再复制 pelletCount 份"，
     * 那么单弹丸的枪会得到"子弹随机偏一点"的行为 —— 两把手枪/SMG 就都不准了，
     * 而这在"准星指向即命中"的验收里是立刻不通过、但很难归因的一类缺陷。
     */
    @Test
    void aSinglePelletIgnoresSpreadEntirely() {
        Vector3d forward = unit(0.3, -0.5, 0.81);
        Vector3d right = new Vector3d();
        Vector3d up = new Vector3d();
        Vector3d out = new Vector3d();
        ShotSpread.basis(forward, right, up);

        ShotSpread.offset(forward, right, up, 0.5, 0, 1, out);

        assertEquals(0.0, out.distance(forward), EPS,
                "只有 1 颗弹丸时散布无从谈起：方向必须就是准星方向");
    }

    // ============================================================ 锥内 / 单位长度

    @Test
    void everyPelletStaysInsideTheSpreadConeAndKeepsUnitLength() {
        for (double spread : new double[]{0.01, 0.08, 0.20, 0.60}) {
            for (Vector3d forward : forwards()) {
                Vector3d right = new Vector3d();
                Vector3d up = new Vector3d();
                Vector3d out = new Vector3d();
                ShotSpread.basis(forward, right, up);
                int total = 6;
                for (int index = 0; index < total; index++) {
                    ShotSpread.offset(forward, right, up, spread, index, total, out);
                    // 单位长度：offset 是"旋转 forward"，旋转不改变长度。
                    // 长度漂了意味着基向量不正交（复合旋转退化成仿射变换）。
                    assertEquals(1.0, out.length(), 1e-9,
                            "方向必须是单位向量（spread=" + spread + "，forward=" + forward + "）");
                    double angle = angleBetween(forward, out);
                    assertTrue(angle <= spread + 1e-9,
                            "第 " + index + " 颗越出散布锥：夹角 " + angle + " > spreadRad " + spread
                                    + "（forward=" + forward + "）");
                }
            }
        }
    }

    // ============================================================ 中心一颗 + 真的分开

    @Test
    void theFirstPelletIsAlwaysDeadCenter() {
        Vector3d forward = unit(0, -1, 0);   // 低头挖矿：基向量最易退化的位形
        Vector3d right = new Vector3d();
        Vector3d up = new Vector3d();
        Vector3d out = new Vector3d();
        ShotSpread.basis(forward, right, up);

        ShotSpread.offset(forward, right, up, 0.30, 0, 8, out);

        assertEquals(0.0, out.distance(forward), EPS,
                "准星指着的地方必须至少有一颗弹丸按原方向飞出，"
                        + "否则「我把准星压在它头上」会变成「整圈都偏了、中心一发没打中」");
    }

    @Test
    void aNonZeroSpreadActuallySeparatesThePellets() {
        double spread = 0.20;
        Vector3d forward = unit(0, 0, -1);
        Vector3d right = new Vector3d();
        Vector3d up = new Vector3d();
        ShotSpread.basis(forward, right, up);

        int total = 6;
        List<Vector3d> directions = new ArrayList<>();
        double maxAngle = 0;
        for (int index = 0; index < total; index++) {
            Vector3d out = new Vector3d();
            ShotSpread.offset(forward, right, up, spread, index, total, out);
            directions.add(out);
            maxAngle = Math.max(maxAngle, angleBetween(forward, out));
        }

        // ① 真的散开了：最外那颗至少要走到锥的一半以上（sqrt 面积分布下约 0.96 × spread）。
        assertTrue(maxAngle > 0.5 * spread,
                "最外一颗只偏了 " + maxAngle + " rad（spreadRad=" + spread + "）："
                        + "散布看起来接上了，实际没起作用");

        // ② 六颗方向互不重合（没有两颗挤在一起 → 螺旋展开有效，不是"全部沿同一条线"）。
        int distinct = 0;
        for (int i = 0; i < directions.size(); i++) {
            boolean unique = true;
            for (int j = 0; j < i; j++) {
                if (directions.get(i).distance(directions.get(j)) < 1e-9) {
                    unique = false;
                    break;
                }
            }
            if (unique) {
                distinct++;
            }
        }
        assertEquals(total, distinct,
                "6 颗弹丸必须给出 6 个互不相同的方向，实测不同方向数 = " + distinct);
    }

    @Test
    void thePatternIsDeterministicForTheSameInputs() {
        Vector3d forward = unit(0.1, 0.2, -0.97);
        Vector3d rightA = new Vector3d();
        Vector3d upA = new Vector3d();
        Vector3d rightB = new Vector3d();
        Vector3d upB = new Vector3d();
        ShotSpread.basis(forward, rightA, upA);
        ShotSpread.basis(forward, rightB, upB);

        for (int index = 0; index < 6; index++) {
            Vector3d a = new Vector3d();
            Vector3d b = new Vector3d();
            ShotSpread.offset(forward, rightA, upA, 0.15, index, 6, a);
            ShotSpread.offset(forward, rightB, upB, 0.15, index, 6, b);
            assertEquals(0.0, a.distance(b), 0.0,
                    "同一组入参必须给出逐位相同的方向（第 " + index + " 颗）——"
                            + "散布若带随机数，试玩报告里的「这一枪散了」就无法重放");
        }
    }

    // ============================================================ 零分配

    /**
     * 结构性证据：{@link ShotSpread} 不分配任何对象，并且<b>把结果写进调用方给的向量</b>。
     *
     * <p>散布采样位于"每发 × 每弹丸"的内层循环，是整条射击链上唯一会随射速与弹丸数
     * <b>相乘</b>放大的分配点，因此它是 v2 §15「禁止因高射速增加每发分配」的直接对象。
     */
    @Test
    void theHelperAllocatesNothingAndWritesIntoTheCallersVector() {
        String code = SourceScan.withoutComments(
                SourceScan.readMain("com/skyisland/combat/ShotSpread.java"));

        assertFalse(code.contains("new Vector3d"),
                "ShotSpread 必须零分配：它跑在每发循环里（v2 §15）");
        assertTrue(code.contains("void offset("),
                "offset 必须是 void 并写进调用方给的 out —— 返回新对象就等于每颗弹丸分配一次");
        assertTrue(code.contains("void basis("), "basis 同理：写进调用方给的 right / up");

        // 行为侧的证据：真的一次调用就把给定向量改写了。
        Vector3d forward = unit(0, 0, -1);
        Vector3d right = new Vector3d();
        Vector3d up = new Vector3d();
        Vector3d out = new Vector3d(9, 9, 9);
        ShotSpread.basis(forward, right, up);
        ShotSpread.offset(forward, right, up, 0.25, 3, 6, out);
        assertTrue(out.distance(new Vector3d(9, 9, 9)) > 0.5,
                "out 必须被就地改写（而不是原地不动 / 换成一个新对象）");
        assertEquals(1.0, out.length(), 1e-9, "改写后的方向仍是单位向量");
    }
}

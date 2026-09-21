package com.skyisland.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * 视锥剔除（TECH_DESIGN §G.8）。
 *
 * <p><b>算法：Gribb &amp; Hartmann 平面提取。</b>从 {@code proj × view} 的六个平面
 * 依次判定 AABB：只要盒子完全落在任意一个平面外侧，它就不可能可见。
 * 不需要求矩阵逆，也不需要构造 8 个角点。
 *
 * <p><b>为什么用 AABB 而不是包围球：</b>区块是 16×16×128 的长条，
 * 外接球半径 = {@code sqrt(8² + 64² + 8²) ≈ 65} —— 远大于实际体积。
 * 用球体剔除会把大量实际不可见的区块判为可见，等同于没剔除。
 *
 * <p><b>判定用的是"正向顶点"（p-vertex）：</b>对每个平面取 AABB 上沿平面法线最远的那个角，
 * 若它仍在平面内侧，则整个盒子在内侧。这个做法会<u>保守地多判可见</u>
 * （盒子在视锥外但贴近时可能被保留），对剔除来说方向是对的：
 * 多画一点不会出错，错剔会出现"该看见的区块消失"。
 *
 * <p><b>平面提取委托给 JOML（{@code Matrix4f#frustumPlane}），不手写行组合。</b>
 * 这里记一次真实事故，因为它是本类最容易重犯的错：
 * 手写版把 JOML 的 {@code mXY()} 当成"第 X 行第 Y 列"，而 JOML 实际是
 * <u>"第 X 列第 Y 行"</u>（与其列优先存储一致）。这两者对透视矩阵恰好互为转置，
 * 于是提取出的是转置矩阵的六个平面：近平面 {@code d = 0.83}、远平面 {@code d = 1.25}
 * （正确值是 {@code 0.1} / {@code 100}）—— 结果是"只有相机正前方约 1 格厚的一层薄板
 * 内才算可见"。
 *
 * <p>这个 bug 之所以能藏住，是因为游戏里相机<u>位于区块内部</u>，而区块 AABB
 * 跨越了那层薄板，于是 p-vertex 判定恰好放行 —— 画面上看不出异常。
 * 它只在把相机放到空处、或直接对平面方程断言时才暴露。
 * 因此本类现在把提取交给 JOML，并在 {@code FrustumTest} 里用
 * "NDC 真值逐点对照 + 钉住近/远平面的 d 必须等于 near/far" 两道断言守住它。
 *
 * <p><b>已知低效点（§G.8 记录）：</b>区块在 y 上占满 128 层而实际地形只占 8–14 层。
 * MVP 先用全高 AABB；若实测绘制调用成为瓶颈，再引入 heightMap 收缩。
 */
public final class Frustum {

    /** 6 个平面 × 4 个系数（a, b, c, d），顺序：左(−X)、右(+X)、下(−Y)、上(+Y)、近(−Z)、远(+Z)。 */
    private final float[] planes = new float[6 * 4];

    private final Matrix4f projView = new Matrix4f();

    /** 提取平面时的复用缓冲（避免每次 update 分配 6 个 Vector4f）。 */
    private final Vector4f planeBuffer = new Vector4f();

    /**
     * 由投影矩阵与视图矩阵更新视锥。
     *
     * @return {@code false} 表示传入的矩阵不可用（含 NaN），此时视锥被置为"全部可见"，
     *         调用方不需要特判 —— 剔除失效只会多画，不会少画
     */
    public boolean update(Matrix4fc projection, Matrix4fc view) {
        projection.mul(view, projView);
        if (!projView.isFinite()) {
            setAllVisible();
            return false;
        }
        for (int i = 0; i < 6; i++) {
            projView.frustumPlane(i, planeBuffer);
            int base = i * 4;
            planes[base] = planeBuffer.x;
            planes[base + 1] = planeBuffer.y;
            planes[base + 2] = planeBuffer.z;
            planes[base + 3] = planeBuffer.w;
        }
        return true;
    }

    private void setAllVisible() {
        for (int i = 0; i < 6; i++) {
            int base = i * 4;
            planes[base] = 0;
            planes[base + 1] = 0;
            planes[base + 2] = 0;
            planes[base + 3] = Float.POSITIVE_INFINITY;
        }
    }

    /** 轴对齐包围盒是否与视锥相交（保守：可能多判可见）。 */
    public boolean intersectsAABB(float minX, float minY, float minZ,
                                 float maxX, float maxY, float maxZ) {
        for (int i = 0; i < 6; i++) {
            int base = i * 4;
            float a = planes[base];
            float b = planes[base + 1];
            float c = planes[base + 2];
            float d = planes[base + 3];
            // 正向顶点：沿法线最远的那个角
            float px = a >= 0 ? maxX : minX;
            float py = b >= 0 ? maxY : minY;
            float pz = c >= 0 ? maxZ : minZ;
            if (a * px + b * py + c * pz + d < 0) {
                return false;
            }
        }
        return true;
    }

    /** 供自测断言引用：某个平面方程的系数（只读拷贝）。 */
    public float[] plane(int index) {
        return new float[]{
                planes[index * 4], planes[index * 4 + 1],
                planes[index * 4 + 2], planes[index * 4 + 3]};
    }
}

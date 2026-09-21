package com.skyisland.render;

import java.util.Arrays;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视锥剔除测试（TECH_DESIGN §G.8）。
 *
 * <p><b>为什么不用"渲染一帧看有没有区块消失"来验证：</b>剔除错误的两种表现
 * ——"该看见的消失"与"不该画的仍画了"—— 前者是事故，后者只是浪费。
 * 二者在画面上都很难定位到具体是哪个平面判错。因此这里直接用
 * {@code proj × view} 构造一个<u>已知几何</u>的视锥（相机在原点、朝 −Z），
 * 再用完全在某个平面外侧的盒子去逼出漏判。
 *
 * <p><b>关于"保守地多判可见"：</b>正向顶点法允许盒子在视锥<u>外侧</u>但贴近边界时仍被判为可见。
 * 这是刻意选择（少画 = 事故，多画 = 浪费）。所以负例全部选用"明显在外侧"的盒子，
 * 不构造边界上的刁钻用例 —— 那种断言会把一个正确的实现判为失败。
 */
class FrustumTest {

    private static final float NEAR = 0.1f;
    private static final float FAR = 100f;

    /** 相机在原点、朝 −Z、上方向 +Y（JOML 右手系）。 */
    private static Frustum cameraLookingDownNegativeZ() {
        Matrix4f projection = new Matrix4f()
                .perspective((float) Math.toRadians(70.0), 16f / 9f, NEAR, FAR);
        Matrix4f view = new Matrix4f().lookAt(0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f);
        Frustum frustum = new Frustum();
        assertTrue(frustum.update(projection, view), "合法矩阵必须返回 true");
        return frustum;
    }

    // ============================================================ 正向

    @Test
    void boxDirectlyInFrontIsVisible() {
        Frustum f = cameraLookingDownNegativeZ();
        assertTrue(f.intersectsAABB(-1, -1, -10, 1, 1, -5), "正前方的盒子必须可见");
    }

    @Test
    void boxContainingTheCameraIsKept() {
        Frustum f = cameraLookingDownNegativeZ();
        // 盒子把相机本身包在里面：近平面恰好穿过相机，保守实现必须保留而不是剔除
        assertTrue(f.intersectsAABB(-1, -1, -1, 1, 1, 1),
                "包住视点的盒子不能被剔除 —— 剔除它等于把玩家所在处整片抹掉");
    }

    @Test
    void largeBoxStraddlingTheFrustumIsKept() {
        Frustum f = cameraLookingDownNegativeZ();
        assertTrue(f.intersectsAABB(-50, -50, -50, 50, 50, -5),
                "横跨视锥的大盒子（区块就是这种形态）必须可见");
    }

    // ============================================================ 反向

    @Test
    void boxBehindTheCameraIsCulled() {
        Frustum f = cameraLookingDownNegativeZ();
        assertFalse(f.intersectsAABB(-1, -1, 5, 1, 1, 10), "相机背后的盒子必须被剔除");
    }

    @Test
    void boxFarToTheSideIsCulled() {
        Frustum f = cameraLookingDownNegativeZ();
        assertFalse(f.intersectsAABB(100, -1, -10, 110, 1, -5), "远在右侧之外的盒子必须被剔除");
        assertFalse(f.intersectsAABB(-110, -1, -10, -100, 1, -5), "远在左侧之外的盒子必须被剔除");
    }

    @Test
    void boxAboveAndBelowTheFrustumIsCulled() {
        Frustum f = cameraLookingDownNegativeZ();
        assertFalse(f.intersectsAABB(-1, 100, -10, 1, 110, -5), "高得离谱的盒子必须被剔除");
        assertFalse(f.intersectsAABB(-1, -110, -10, 1, -100, -5), "低得离谱的盒子必须被剔除");
    }

    @Test
    void boxBeyondTheFarPlaneIsCulled() {
        Frustum f = cameraLookingDownNegativeZ();
        assertFalse(f.intersectsAABB(-1, -1, -500, 1, 1, -400),
                "超过远平面的盒子必须被剔除（否则会白画远处的区块）");
    }

    @Test
    void boxInFrontOfTheNearPlaneIsCulled() {
        Frustum f = cameraLookingDownNegativeZ();
        // 盒子整体位于相机与近平面之间（z ∈ [-0.05, -0.01]，近平面在 z = -0.1）
        assertFalse(f.intersectsAABB(-0.01f, -0.01f, -0.05f, 0.01f, 0.01f, -0.01f),
                "贴在相机与近平面之间的盒子必须被剔除（这块区域不参与渲染）");
    }

    // ============================================================ 方向相关性

    @Test
    void visibilityDependsOnDirectionNotEmptyProximity() {
        Frustum forward = cameraLookingDownNegativeZ();

        // 同一个盒子：在 −Z 方向可见
        assertTrue(forward.intersectsAABB(-2, -2, -20, 2, 2, -10));

        // 把相机转 180°，同一个盒子落到背后 → 必须被剔除
        Matrix4f projection = new Matrix4f()
                .perspective((float) Math.toRadians(70.0), 16f / 9f, NEAR, FAR);
        Matrix4f view = new Matrix4f().lookAt(0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f);
        Frustum backward = new Frustum();
        assertTrue(backward.update(projection, view));
        assertFalse(backward.intersectsAABB(-2, -2, -20, 2, 2, -10),
                "相机转身后同一个盒子必须被剔除 —— 否则说明剔除只看了距离");
    }

    // ============================================================ 退化与防御

    @Test
    void nonFiniteMatrixDegradesToAllVisible() {
        Matrix4f projection = new Matrix4f();
        projection.m00(Float.NaN);
        Matrix4f view = new Matrix4f().lookAt(0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f);

        Frustum f = new Frustum();
        assertFalse(f.update(projection, view), "含 NaN 的矩阵必须返回 false");

        // 失效时退化为"全部可见"：多画一点，绝不出现"该看见的消失"
        assertTrue(f.intersectsAABB(1000, 1000, 1000, 1010, 1010, 1010),
                "视锥失效后必须保守地判为可见");
        assertTrue(f.intersectsAABB(-1, -1, -10, 1, 1, -5));
    }

    @Test
    void planesAreNormalisedSoDistanceReadsInWorldUnits() {
        Frustum f = cameraLookingDownNegativeZ();
        for (int i = 0; i < 6; i++) {
            float[] p = f.plane(i);
            assertEquals(4, p.length);
            double length = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            assertEquals(1.0, length, 1e-5,
                    "平面 " + i + " 的法线未归一化 —— 归一化后 d 的量纲才与世界单位一致");
        }
    }

    // ============================================================ 回归守门人

    /**
     * ★ 把"数学真值"与"实现"绑在一起，这条比任何"我推导的平面应该是……"都可靠。
     *
     * <p>用 {@code proj × view} 把点变换到 NDC：{@code |x|,|y|,|z|} 全都不超过 1
     * 就说明该点在视锥内 —— 这是 OpenGL 裁剪的<u>定义</u>，不依赖任何关于
     * Gribb-Hartmann、也不依赖任何关于矩阵元素索引顺序的理解。
     * 然后用同一个点去问视锥，两者必须一致。
     *
     * <p><b>为什么必须有这一条：</b>曾出现过"平面提取把矩阵的列当成行"的转置错误
     * （见 {@link Frustum} 的类注释）。它把可见范围缩成相机正前方约 1 格厚的薄板，
     * 而在"相机位于区块内部"的实际游戏里因为区块 AABB 跨越薄板而看不出来。
     * 只要有一个点直接取 5 格外，转置立刻暴露。
     */
    @Test
    void frustumAgreesWithNdcGroundTruth() {
        Matrix4f projection = new Matrix4f()
                .perspective((float) Math.toRadians(70.0), 16f / 9f, NEAR, FAR);
        Matrix4f view = new Matrix4f().lookAt(0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f);
        Matrix4f projView = projection.mul(view, new Matrix4f());

        Frustum frustum = new Frustum();
        assertTrue(frustum.update(projection, view));

        // 构造时就把 near/far 两端和明显的"错"点都放进来
        float[][] points = {
                {0f, 0f, -0.05f},                              // 比近平面还近 → 外
                {0f, 0f, -0.5f}, {0f, 0f, -1f}, {0f, 0f, -2f},
                {0f, 0f, -5f},                                 // ★ 事故点：转置时被判为外
                {0f, 0f, -10f}, {0f, 0f, -50f}, {0f, 0f, -99f},
                {0f, 0f, -0.1f}, {0f, 0f, -100f},              // 正好在近/远平面上
                {0f, 0f, -200f},                               // 超出远平面 → 外
                {3f, 0f, -5f}, {-3f, 0f, -5f}, {0f, 3f, -5f},  // 侧向仍在视野内
                {100f, 0f, -5f},                               // 远在右侧 → 外
                {0f, 0f, 0.5f},                                // 相机背后 → 外
        };

        int mismatches = 0;
        for (float[] p : points) {
            boolean byNdc = ndcInside(projView, p[0], p[1], p[2]);
            boolean byFrustum = frustum.intersectsAABB(
                    p[0] - 1e-3f, p[1] - 1e-3f, p[2] - 1e-3f,
                    p[0] + 1e-3f, p[1] + 1e-3f, p[2] + 1e-3f);
            if (byNdc != byFrustum) {
                mismatches++;
                System.out.println("NDC 与视锥判定不一致：" + Arrays.toString(p)
                        + " NDC=" + byNdc + " 视锥=" + byFrustum);
            }
        }
        assertEquals(0, mismatches,
                "视锥判定必须与 NDC 真值逐点一致 —— 不一致说明平面提取或 p-vertex 判定写错了");
    }

    /**
     * ★ 转置错误的直接指纹：近/远平面的常数项必须分别等于 {@code −near} 与 {@code +far}。
     *
     * <p>转置之后近平面会变成 {@code d ≈ 0.83}、远平面 {@code d ≈ 1.25}
     * （正确值是 {@code 0.1} 与 {@code 100}），量纲直接离谱。
     * 有了这条，即使"正前方可见"那类用例因为相机恰好在盒内而侥幸通过，也能立刻发现。
     */
    @Test
    void nearAndFarPlaneOffsetsEqualTheConfiguredDistances() {
        Frustum f = cameraLookingDownNegativeZ();

        float[] near = f.plane(4);
        assertEquals(0f, near[0], 1e-6);
        assertEquals(0f, near[1], 1e-6);
        assertEquals(-1f, near[2], 1e-6, "近平面的法线应指向 −Z（朝视线的来向）");
        assertEquals(-NEAR, near[3], 1e-4, "近平面常数项必须等于 −near（本例 −0.1）");

        float[] far = f.plane(5);
        assertEquals(0f, far[0], 1e-6);
        assertEquals(0f, far[1], 1e-6);
        assertEquals(1f, far[2], 1e-6, "远平面的法线应指向 +Z");
        assertEquals(FAR, far[3], 1e-3, "远平面常数项必须等于 far（本例 100）");
    }

    /**
     * 左右/上下四个侧面的斜率必须与视场角一致，而不是随便一个数。
     *
     * <p>垂直 FOV 70°、宽高比 16:9 ⇒ {@code tan(hFov/2) = tan(35°) × 16/9}。
     * 水平侧面（左/右）的法线分量比 {@code |c| / |a|} 必须等于它。
     */
    @Test
    void sidePlaneSlopesMatchTheFieldOfView() {
        Frustum f = cameraLookingDownNegativeZ();

        double tanHalfVertical = Math.tan(Math.toRadians(70.0) / 2.0);
        double tanHalfHorizontal = tanHalfVertical * 16.0 / 9.0;

        float[] left = f.plane(0);
        assertEquals(tanHalfHorizontal, Math.abs(left[2] / left[0]), 1e-4,
                "左侧面的斜率必须对应水平视场角");

        float[] top = f.plane(3);
        assertEquals(tanHalfVertical, Math.abs(top[2] / top[1]), 1e-4,
                "上侧面的斜率必须对应垂直视场角");
    }

    // ============================================================ 辅助

    /** 把点变换到 NDC 并判断是否落在裁剪体内（w ≤ 0 视为在相机后方 → 不可见）。 */
    private static boolean ndcInside(Matrix4f projView, float x, float y, float z) {
        Vector4f v = new Vector4f(x, y, z, 1f);
        projView.transform(v);
        if (v.w <= 1e-9f) {
            return false;
        }
        float ndcX = v.x / v.w;
        float ndcY = v.y / v.w;
        float ndcZ = v.z / v.w;
        return ndcX >= -1f && ndcX <= 1f
                && ndcY >= -1f && ndcY <= 1f
                && ndcZ >= -1f && ndcZ <= 1f;
    }

    /** 剔除必须是"单调"的：缩小盒子不会让可见变成不可见。 */
    @Test
    void shrinkingABoxNeverMakesItMoreVisible() {
        Frustum f = cameraLookingDownNegativeZ();
        assertTrue(f.intersectsAABB(-1, -1, -10, 1, 1, -5));
        // 缩到完全在视锥内的一小块
        assertTrue(f.intersectsAABB(-0.1f, -0.1f, -6, 0.1f, 0.1f, -5.5f));
        // 把一个远在左侧的盒子缩小，仍然不可见
        assertFalse(f.intersectsAABB(100, -1, -10, 110, 1, -5));
        assertFalse(f.intersectsAABB(105, -0.1f, -8, 106, 0.1f, -7));
    }
}

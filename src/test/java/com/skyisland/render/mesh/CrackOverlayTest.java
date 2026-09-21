package com.skyisland.render.mesh;

import com.skyisland.physics.RaycastHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 裂纹叠加层的纯几何测试（PRD_v0.3.1 §挖掘「破坏反馈」：10 段裂纹）。
 *
 * <p>这些测试都不需要 GL 上下文 —— {@link CrackOverlay#segmentCount} 与
 * {@link CrackOverlay#buildVertices} 是纯函数。这不是巧合，而是刻意的分层：
 * 裂纹最容易出错的地方（段数映射、面基绕序、越界、图案不稳定）
 * 全是纯几何问题，把它们做成纯函数就能在没有窗口的机器上跑测试。
 * 真正只能靠窗口验证的只剩"这些顶点确实被提交给了 GPU"，
 * 而那一条由 {@code M1_5UiSelfTest} 的 MINE_BY_MOUSE 阶段负责。
 */
class CrackOverlayTest {

    private static final float EPS = 1e-4f;

    /** 每段 6 顶点 × 每顶点 7 float = 42 个 float。 */
    private static final int FLOATS_PER_SEGMENT = 6 * 7;

    /** 沿法线偏移会让贴面"探出"方块面一点点，断言范围要留出这个量。 */
    private static final double OUTWARD_TOLERANCE = 0.01;

    /** 构造一个命中结果：默认命中 (10, 64, -3) 的 +Y 面。 */
    private static RaycastHit hit(int nx, int ny, int nz) {
        return new RaycastHit(10, 64, -3, 1, nx, ny, nz, 1.5,
                10 + nx, 64 + ny, -3 + nz, false);
    }

    private static final int[][] NORMALS = {
            {0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
    };

    // ============================================================ 进度 → 段数

    @Test
    @DisplayName("进度为 0 / 负数 / NaN 时不画裂纹")
    void noCracksForNonPositiveProgress() {
        assertEquals(0, CrackOverlay.segmentCount(0.0));
        assertEquals(0, CrackOverlay.segmentCount(-0.5));
        assertEquals(0, CrackOverlay.segmentCount(Double.NaN),
                "NaN 必须走「不画」分支：NaN 参与比较恒为 false，"
                        + "Math.min 会把它漏过去，最终变成一个非法顶点数");
        assertEquals(0, CrackOverlay.segmentCount(Double.NEGATIVE_INFINITY));
    }

    @Test
    @DisplayName("第一段裂纹在进度刚大于 0 时就出现（向上取整，不是向下）")
    void firstSegmentAppearsImmediately() {
        assertEquals(1, CrackOverlay.segmentCount(0.001));
        assertEquals(1, CrackOverlay.segmentCount(0.099));
        assertEquals(1, CrackOverlay.segmentCount(0.1),
                "0.1 恰好是第 1 段的边界：ceil(0.1×10)=1，第 2 段从 0.100001 才开始");
        assertEquals(2, CrackOverlay.segmentCount(0.101));
    }

    @Test
    @DisplayName("段数映射与 PRD 的 10 段一致，且单调不减、上界为 10")
    void segmentMappingIsMonotonicAndCapped() {
        int previous = 0;
        for (int i = 0; i <= 400; i++) {
            double progress = i / 400.0;
            int n = CrackOverlay.segmentCount(progress);
            assertTrue(n >= previous, "进度 " + progress + " 处段数倒退了：" + previous + " → " + n);
            assertTrue(n <= CrackOverlay.MAX_SEGMENTS, "段数超过 10：" + n);
            previous = n;
        }
        assertEquals(10, CrackOverlay.segmentCount(1.0), "挖到底必须是 10 段");
        assertEquals(10, CrackOverlay.segmentCount(0.95));
        assertEquals(10, CrackOverlay.segmentCount(9.9),
                "超过 1 的进度要被截到 10，而不是继续往上长");
    }

    // ============================================================ 顶点生成

    @Test
    @DisplayName("0 段 / 无法线 / null 命中都不产生几何")
    void degenerateInputsProduceNothing() {
        float[] out = new float[CrackOverlay.CAPACITY_FLOATS];
        assertEquals(0, CrackOverlay.buildVertices(null, 5, out));
        assertEquals(0, CrackOverlay.buildVertices(hit(0, 1, 0), 0, out));
        assertEquals(0, CrackOverlay.buildVertices(hit(0, 1, 0), -3, out));
        RaycastHit noFace = new RaycastHit(0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, true);
        assertEquals(0, CrackOverlay.buildVertices(noFace, 5, out),
                "起点在方块内部时没有合法面，不能画裂纹");
    }

    @Test
    @DisplayName("顶点数量 = 段数 × 6，且不超过缓冲容量")
    void vertexCountMatchesSegments() {
        float[] out = new float[CrackOverlay.CAPACITY_FLOATS];
        for (int seg = 1; seg <= CrackOverlay.MAX_SEGMENTS; seg++) {
            int floats = CrackOverlay.buildVertices(hit(0, 1, 0), seg, out);
            assertEquals(seg * FLOATS_PER_SEGMENT, floats,
                    seg + " 段的 float 个数不对（每顶点 7 float × 每段 6 顶点）");
            assertTrue(floats <= out.length);
        }
    }

    @Test
    @DisplayName("裂纹贴在命中面上：法线方向偏移 FACE_OFFSET，另两轴落在方块范围内")
    void verticesLieOnTheHitFace() {
        float[] out = new float[CrackOverlay.CAPACITY_FLOATS];
        for (int[] n : NORMALS) {
            int floats = CrackOverlay.buildVertices(hit(n[0], n[1], n[2]),
                    CrackOverlay.MAX_SEGMENTS, out);
            assertEquals(CrackOverlay.MAX_SEGMENTS * FLOATS_PER_SEGMENT, floats);
            for (int v = 0; v < floats; v += 7) {
                // 顶点存的是"块内相对坐标 + 绝对 y"：还原成世界坐标再判断
                double wx = out[v] + 10;
                double wy = out[v + 1];
                double wz = out[v + 2] + (-3);
                String where = "法线(" + n[0] + "," + n[1] + "," + n[2] + ") 的第 " + (v / 7) + " 个顶点";

                // 沿法线方向：面坐标 + FACE_OFFSET（向外偏，避免 z-fighting）
                if (n[1] == 1) {
                    assertEquals(65 + CrackOverlay.FACE_OFFSET, wy, EPS, where + " 不在 +Y 面上");
                } else if (n[1] == -1) {
                    assertEquals(64 - CrackOverlay.FACE_OFFSET, wy, EPS, where + " 不在 -Y 面上");
                } else if (n[0] == 1) {
                    assertEquals(11 + CrackOverlay.FACE_OFFSET, wx, EPS, where + " 不在 +X 面上");
                } else if (n[0] == -1) {
                    assertEquals(10 - CrackOverlay.FACE_OFFSET, wx, EPS, where + " 不在 -X 面上");
                } else if (n[2] == 1) {
                    assertEquals(-2 + CrackOverlay.FACE_OFFSET, wz, EPS, where + " 不在 +Z 面上");
                } else {
                    assertEquals(-3 - CrackOverlay.FACE_OFFSET, wz, EPS, where + " 不在 -Z 面上");
                }

                // 面内两轴：必须落在 [方块起点, 方块终点]，否则裂纹会飘到相邻方块上
                assertWithinBlock(wx, 10, 11, where + " 的 x");
                assertWithinBlock(wy, 64, 65, where + " 的 y");
                assertWithinBlock(wz, -3, -2, where + " 的 z");
            }
        }
    }

    /** 断言坐标落在 [lo, hi] 内（含沿法线外偏的容差）。 */
    private static void assertWithinBlock(double value, double lo, double hi, String where) {
        assertTrue(value >= lo - OUTWARD_TOLERANCE && value <= hi + OUTWARD_TOLERANCE,
                where + " 越出方块范围：值=" + value + "，应在 [" + lo + ", " + hi + "] 内");
    }

    @Test
    @DisplayName("面的 u×v = 法线（从外侧看是 CCW，不会被背面剔除吃掉）")
    void quadWindingMatchesFaceNormal() {
        // 用第 1 个三角形的三个顶点算几何法线，与命中面法线比较。
        // 这是"绕序写反"的唯一廉价检测手段 —— 若绕序反了，开着剔除时会完全看不见，
        // 而"完全看不见"是最难归因的症状之一。
        float[] out = new float[CrackOverlay.CAPACITY_FLOATS];
        for (int[] n : NORMALS) {
            int floats = CrackOverlay.buildVertices(hit(n[0], n[1], n[2]), 1, out);
            assertEquals(FLOATS_PER_SEGMENT, floats);
            double ax = out[0] + 10, ay = out[1], az = out[2] - 3;
            double bx = out[7] + 10, by = out[8], bz = out[9] - 3;
            double cx = out[14] + 10, cy = out[15], cz = out[16] - 3;
            double e1x = bx - ax, e1y = by - ay, e1z = bz - az;
            double e2x = cx - ax, e2y = cy - ay, e2z = cz - az;
            double nx = e1y * e2z - e1z * e2y;
            double ny = e1z * e2x - e1x * e2z;
            double nz = e1x * e2y - e1y * e2x;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            assertTrue(len > 1e-9, "法线(" + n[0] + "," + n[1] + "," + n[2] + ") 的四边形退化成了一条线");
            nx /= len; ny /= len; nz /= len;
            assertEquals(n[0], nx, 1e-3, "几何法线 x 与面法线不一致（绕序反了）");
            assertEquals(n[1], ny, 1e-3, "几何法线 y 与面法线不一致（绕序反了）");
            assertEquals(n[2], nz, 1e-3, "几何法线 z 与面法线不一致（绕序反了）");
        }
    }

    @Test
    @DisplayName("同一方块的图案逐帧稳定（不闪烁），不同方块图案不同")
    void patternIsDeterministicPerBlock() {
        float[] first = new float[CrackOverlay.CAPACITY_FLOATS];
        float[] second = new float[CrackOverlay.CAPACITY_FLOATS];
        CrackOverlay.buildVertices(hit(0, 1, 0), 7, first);
        CrackOverlay.buildVertices(hit(0, 1, 0), 7, second);
        for (int i = 0; i < 7 * FLOATS_PER_SEGMENT; i++) {
            assertEquals(first[i], second[i], 0f,
                    "同一方块第 " + i + " 个分量两次生成不一致 —— 裂纹会逐帧抖动");
        }

        float[] otherBlock = new float[CrackOverlay.CAPACITY_FLOATS];
        RaycastHit neighbour = new RaycastHit(11, 64, -3, 1, 0, 1, 0, 1.5, 11, 65, -3, false);
        CrackOverlay.buildVertices(neighbour, 7, otherBlock);
        boolean differs = false;
        for (int i = 0; i < 7 * FLOATS_PER_SEGMENT && !differs; i++) {
            differs = Math.abs(first[i] - otherBlock[i]) > 1e-6f;
        }
        assertTrue(differs, "相邻方块拿到了完全相同的图案 —— 所有方块会长得一模一样");
    }

    @Test
    @DisplayName("调色板固定：颜色分量在合法范围，且不受面明暗影响（a=1）")
    void colourIsConstantAndShadeNeutral() {
        float[] out = new float[CrackOverlay.CAPACITY_FLOATS];
        int floats = CrackOverlay.buildVertices(hit(0, 1, 0), 3, out);
        for (int v = 0; v < floats; v += 7) {
            for (int c = 3; c < 6; c++) {
                assertTrue(out[v + c] >= 0f && out[v + c] <= 0.2f,
                        "裂纹颜色应接近黑，实际分量 " + out[v + c]);
            }
            assertEquals(1.0f, out[v + 6], 0f,
                    "a 是预乘明暗，裂纹必须为 1：否则裂纹会跟着面明暗一起变亮变暗");
        }
    }

    @Test
    @DisplayName("y 分量是绝对高度，x/z 是块内相对量（与 voxel.vert 的口径一致）")
    void vertexLayoutMatchesVoxelShaderConvention() {
        // voxel.vert 只给 x/z 加 uChunkOffset，y 直接使用 ——
        // 这个不对称是既有约定，写错了会导致"裂纹高度整体偏掉一个方块"。
        float[] out = new float[CrackOverlay.CAPACITY_FLOATS];
        RaycastHit top = new RaycastHit(10, 64, -3, 1, 0, 1, 0, 1.5, 10, 65, -3, false);
        int floats = CrackOverlay.buildVertices(top, 2, out);
        for (int v = 0; v < floats; v += 7) {
            assertTrue(out[v] >= 0f && out[v] <= 1f, "块内 x 应在 [0,1]，实际 " + out[v]);
            assertTrue(out[v + 2] >= 0f && out[v + 2] <= 1f, "块内 z 应在 [0,1]，实际 " + out[v + 2]);
            assertTrue(out[v + 1] > 60.0 && out[v + 1] < 70.0,
                    "y 应是绝对世界高度（约 65），实际 " + out[v + 1]);
        }
    }
}

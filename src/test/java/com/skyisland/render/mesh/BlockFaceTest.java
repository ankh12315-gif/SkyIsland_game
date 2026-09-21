package com.skyisland.render.mesh;

import com.skyisland.world.block.RenderType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 六个面的几何常量测试（TECH_DESIGN §F.6 / §G.4）。
 *
 * <p><b>为什么这个「纯常量」类值得单独测：</b>{@link BlockFace} 的文档里点明了
 * "环绕方向写反是网格化里最难靠肉眼发现的错误 —— 表现为整个面消失，
 * 而'面消失'与'面被剔除'的现象完全一样"。既然肉眼不可靠，就必须用
 * <u>叉积算出的几何法线</u>去对账面法线：渲染管线按 CCW 判定正面，
 * 顶点顺序一旦反了，被剔除的就是外表面本身。
 *
 * <p>因此本类断言的是四件事：
 * <ol>
 *   <li>六个面的法线两两不同、且是单位轴向；</li>
 *   <li>每个面的 4 个角点恰好构成一个单位正方形（边长全为 1、分量只取 0/1）；</li>
 *   <li>{@code (c1−c0) × (c2−c0)} 与声明的法线同向 —— 这就是 CCW 的机器可验证形式；</li>
 *   <li>明暗值的相对大小关系（顶最亮、底最暗），避免六面同亮导致立方体糊成一团。</li>
 * </ol>
 */
class BlockFaceTest {

    private static final double EPS = 1e-6;

    /** 三轴单位向量的字符串形式，便于把"法线错位"的失败信息写清楚。 */
    private static String normalOf(BlockFace f) {
        return "(" + f.dx() + "," + f.dy() + "," + f.dz() + ")";
    }

    // ============================================================ 法线

    @Test
    void normalsAreDistinctUnitAxes() {
        EnumSet<BlockFace> seen = EnumSet.noneOf(BlockFace.class);
        for (BlockFace f : BlockFace.values()) {
            int magnitude = Math.abs(f.dx()) + Math.abs(f.dy()) + Math.abs(f.dz());
            assertEquals(1, magnitude,
                    f + " 的法线必须是单位轴向（恰好一个分量为 ±1），实际 " + normalOf(f));
            assertTrue(seen.add(f), "法线重复 —— 两个面朝同一方向，其中一个必然永远不可见");
        }
        assertEquals(6, seen.size());
    }

    @Test
    void everyFaceNameMatchesItsNormal() {
        assertEquals("NEG_X", signName(BlockFace.NEG_X, -1, 0, 0));
        assertEquals("POS_X", signName(BlockFace.POS_X, 1, 0, 0));
        assertEquals("NEG_Y", signName(BlockFace.NEG_Y, 0, -1, 0));
        assertEquals("POS_Y", signName(BlockFace.POS_Y, 0, 1, 0));
        assertEquals("NEG_Z", signName(BlockFace.NEG_Z, 0, 0, -1));
        assertEquals("POS_Z", signName(BlockFace.POS_Z, 0, 0, 1));
    }

    private static String signName(BlockFace f, int dx, int dy, int dz) {
        assertEquals(dx, f.dx(), f + " 的 dx");
        assertEquals(dy, f.dy(), f + " 的 dy");
        assertEquals(dz, f.dz(), f + " 的 dz");
        return f.name();
    }

    // ============================================================ 角点几何

    @Test
    void eachFaceHasFourCornersFormingAUnitSquare() {
        for (BlockFace f : BlockFace.values()) {
            assertEquals(4, f.cornerCount(), f + " 必须是四边形");

            // 分量只允许 0 或 1：否则面会与相邻方块的面对不齐（出现裂缝或重叠）
            for (int i = 0; i < 4; i++) {
                for (int axis = 0; axis < 3; axis++) {
                    float v = f.corner(i, axis);
                    assertTrue(v == 0f || v == 1f,
                            f + " 的第 " + i + " 个角点轴 " + axis + " 分量是 " + v + "，只允许 0/1");
                }
            }

            // 四条边长度都为 1 —— 这是"恰好铺满一个面"的充要几何条件
            for (int i = 0; i < 4; i++) {
                int next = (i + 1) % 4;
                double edge = distance(f, i, next);
                assertEquals(1.0, edge, EPS, f + " 的第 " + i + " 条边不是单位长度（角点顺序错乱）");
            }
        }
    }

    private static double distance(BlockFace f, int a, int b) {
        double sum = 0;
        for (int axis = 0; axis < 3; axis++) {
            double d = f.corner(a, axis) - f.corner(b, axis);
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    /**
     * ★ 本类最重要的一条：用叉积验证"从外侧看逆时针"。
     *
     * <p>{@code (c1−c0) × (c2−c0)} 是三角形 (0,1,2) 按右手定则的法线。
     * 它与声明的外法线同向，意味着这个三角形从外侧看是逆时针的
     * —— 配合 {@code glFrontFace(GL_CCW)} + {@code glCullFace(GL_BACK)}，
     * 外表面会被保留。若此断言失败，那个面在画面上会整个消失。
     */
    @Test
    void cornerWindingIsCounterClockwiseWhenSeenFromOutside() {
        for (BlockFace f : BlockFace.values()) {
            double ux = f.corner(1, 0) - f.corner(0, 0);
            double uy = f.corner(1, 1) - f.corner(0, 1);
            double uz = f.corner(1, 2) - f.corner(0, 2);

            double vx = f.corner(2, 0) - f.corner(0, 0);
            double vy = f.corner(2, 1) - f.corner(0, 1);
            double vz = f.corner(2, 2) - f.corner(0, 2);

            double nx = uy * vz - uz * vy;
            double ny = uz * vx - ux * vz;
            double nz = ux * vy - uy * vx;

            assertEquals(f.dx(), nx, EPS, f + " 的环绕方向与法线 x 分量不一致（面会消失）");
            assertEquals(f.dy(), ny, EPS, f + " 的环绕方向与法线 y 分量不一致（面会消失）");
            assertEquals(f.dz(), nz, EPS, f + " 的环绕方向与法线 z 分量不一致（面会消失）");
        }
    }

    /** 第二个三角形 (0,2,3) 必须与第一个同向，否则半个面会被剔除。 */
    @Test
    void secondTriangleHasTheSameFacingAsTheFirst() {
        for (BlockFace f : BlockFace.values()) {
            assertEquals(normalComponent(f, 0, 2, 3, 0), f.dx(), EPS, f + " 第二个三角形的 x");
            assertEquals(normalComponent(f, 0, 2, 3, 1), f.dy(), EPS, f + " 第二个三角形的 y");
            assertEquals(normalComponent(f, 0, 2, 3, 2), f.dz(), EPS, f + " 第二个三角形的 z");
        }
    }

    private static double normalComponent(BlockFace f, int a, int b, int c, int axis) {
        double ux = f.corner(b, 0) - f.corner(a, 0);
        double uy = f.corner(b, 1) - f.corner(a, 1);
        double uz = f.corner(b, 2) - f.corner(a, 2);
        double vx = f.corner(c, 0) - f.corner(a, 0);
        double vy = f.corner(c, 1) - f.corner(a, 1);
        double vz = f.corner(c, 2) - f.corner(a, 2);
        return switch (axis) {
            case 0 -> uy * vz - uz * vy;
            case 1 -> uz * vx - ux * vz;
            default -> ux * vy - uy * vx;
        };
    }

    @Test
    void quadIndicesUseTheSharedDiagonalPattern() {
        assertEquals(6, BlockFace.QUAD_INDICES.length, "两个三角形共 6 个索引");
        assertTrue(BlockFace.QUAD_INDICES[0] == 0 && BlockFace.QUAD_INDICES[1] == 1
                        && BlockFace.QUAD_INDICES[2] == 2,
                "第一个三角形必须是 (0,1,2)");
        assertTrue(BlockFace.QUAD_INDICES[3] == 0 && BlockFace.QUAD_INDICES[4] == 2
                        && BlockFace.QUAD_INDICES[5] == 3,
                "第二个三角形必须是 (0,2,3) —— 共用对角线 0–2 才能只存 4 个顶点");
    }

    // ============================================================ 明暗

    @Test
    void shadeOrderingMakesVoxelStructureReadable() {
        for (BlockFace f : BlockFace.values()) {
            assertTrue(f.shade() > 0f && f.shade() <= 1f,
                    f + " 的明暗必须在 (0,1]，实际 " + f.shade());
        }
        assertEquals(1.0f, BlockFace.POS_Y.shade(), EPS, "顶面最亮");
        assertEquals(0.50f, BlockFace.NEG_Y.shade(), EPS, "底面最暗");
        assertTrue(BlockFace.POS_Y.shade() > BlockFace.NEG_Z.shade(),
                "顶面必须比侧面亮，否则立方体看不出体素结构");
        assertTrue(BlockFace.NEG_Z.shade() > BlockFace.NEG_Y.shade(), "侧面必须比底面亮");
        assertTrue(BlockFace.NEG_X.shade() < BlockFace.NEG_Z.shade(),
                "X 侧面与 Z 侧面要有区分度，否则相邻两面会在视觉上连成一片");
    }

    // ============================================================ 渲染类型

    @Test
    void invisibleRenderTypeGeneratesNoFace() {
        assertFalse(BlockFace.faceRenderable(RenderType.INVISIBLE), "空气不产生任何面");
        assertTrue(BlockFace.faceRenderable(RenderType.OPAQUE));
        assertTrue(BlockFace.faceRenderable(RenderType.TRANSPARENT),
                "透明方块仍然产生面，只是走另一个子网格");
    }
}

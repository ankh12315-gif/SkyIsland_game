package com.skyisland.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AABB 测试（TECH_DESIGN §I.2）。
 *
 * <p>最值得固化的是<b>"刚好贴着不算相交"</b>这条边界语义：玩家站在地面上时脚底 y
 * 恰好等于方块顶面 y，此时必须<u>不</u>相交，否则每一步都会被判定为撞进地面并被推向侧面。
 * 判定写成严格不等式之后这件事天然成立，而"加个 epsilon 更保险"的直觉恰恰会破坏它。
 */
class AABBTest {

    @Test
    void ofFeetCenterProducesTheDeclaredBox() {
        AABB box = AABB.ofFeetCenter(10.0, 64.0, 20.0, 0.3, 1.8);

        assertEquals(9.7, box.minX(), 1e-12);
        assertEquals(10.3, box.maxX(), 1e-12);
        assertEquals(64.0, box.minY(), 1e-12);
        assertEquals(65.8, box.maxY(), 1e-12);
        assertEquals(19.7, box.minZ(), 1e-12);
        assertEquals(20.3, box.maxZ(), 1e-12);

        assertEquals(10.0, box.centerX(), 1e-12);
        assertEquals(20.0, box.centerZ(), 1e-12);
        assertEquals(0.6, box.widthX(), 1e-12);
        assertEquals(1.8, box.heightY(), 1e-12);
    }

    @Test
    void boxOverlappingBlockIntersects() {
        AABB box = AABB.ofFeetCenter(0.5, 64.0, 0.5, 0.3, 1.8);
        // 脚底所在的两格都要被判为相交，脚底下面的那一格不算
        assertTrue(box.intersectsBlock(0, 64, 0));
        assertTrue(box.intersectsBlock(0, 65, 0));
        assertFalse(box.intersectsBlock(0, 63, 0), "脚底 y=64 恰好是方块 63 的顶面 → 不算相交");
        assertFalse(box.intersectsBlock(1, 64, 0), "横向也在外面");
        assertFalse(box.intersectsBlock(0, 66, 0), "头顶之上");
    }

    @Test
    void touchingExactlyOnAxisDoesNotIntersect() {
        // 一个宽 1 的盒子紧贴在 x=1 平面上
        AABB box = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        assertFalse(box.intersectsBlock(1, 0, 0),
                "maxX == bx 时不算相交 —— 这条一旦变成'算相交'，玩家每步都会被推离墙面");
        assertFalse(box.intersectsBlock(-1, 0, 0));
        assertTrue(box.intersectsBlock(0, 0, 0));
    }

    @Test
    void deeplyOverlappingBlockIntersects() {
        AABB box = new AABB(-5.0, -5.0, -5.0, 5.0, 5.0, 5.0);
        assertTrue(box.intersectsBlock(0, 0, 0));
        assertTrue(box.intersectsBlock(4, 4, 4));
        assertTrue(box.intersectsBlock(-5, -5, -5));
        assertFalse(box.intersectsBlock(5, 0, 0));
    }

    @Test
    void movedShiftsEveryBound() {
        AABB box = AABB.ofFeetCenter(0.5, 64.0, 0.5, 0.3, 1.8);
        AABB moved = box.moved(1.0, -2.0, 3.0);

        assertEquals(box.minX() + 1.0, moved.minX(), 1e-12);
        assertEquals(box.minY() - 2.0, moved.minY(), 1e-12);
        assertEquals(box.minZ() + 3.0, moved.minZ(), 1e-12);
        assertEquals(box.maxX() + 1.0, moved.maxX(), 1e-12);
        assertEquals(box.heightY(), moved.heightY(), 1e-12);
    }

    @Test
    void expandYExtendsDownwardsForGroundProbe() {
        AABB box = AABB.ofFeetCenter(0.5, 64.0, 0.5, 0.3, 1.8);
        AABB probe = box.expandY(0.02);

        assertEquals(63.98, probe.minY(), 1e-12);
        assertEquals(box.maxY(), probe.maxY(), 1e-12);
        assertTrue(probe.intersectsBlock(0, 63, 0), "外扩后应当能碰到脚下的方块（站立探测）");
    }

    @Test
    void expandedProbeDetectsGroundBelowTheFeet() {
        AABB probe = AABB.ofFeetCenter(0.5, 64.0, 0.5, 0.3, 1.8).expandY(0.02);
        assertTrue(probe.intersectsBlock(0, 63, 0));
    }

    @Test
    void negativeCoordinatesIntersectCorrectly() {
        // 脚底中心 x=z=-0.5、半宽 0.3 → 盒子占据 x,z ∈ [-0.8, -0.2]，完全落在方块 (-1,-1) 内
        AABB box = AABB.ofFeetCenter(-0.5, 64.0, -0.5, 0.3, 1.8);

        assertTrue(box.intersectsBlock(-1, 64, -1), "（这是 floor 而非截断的分水岭）");
        assertTrue(box.intersectsBlock(-1, 65, -1));
        assertFalse(box.intersectsBlock(0, 64, -1), "x 上界 -0.2 < 0，够不到方块 0");
        assertFalse(box.intersectsBlock(-1, 64, 0), "z 上界 -0.2 < 0，够不到方块 0");
        assertFalse(box.intersectsBlock(-1, 63, -1), "脚底 64.0 是方块 -1,63 的顶面 → 不相交");
    }

    @Test
    void toStringIsReadable() {
        String text = AABB.ofFeetCenter(0.5, 64.0, 0.5, 0.3, 1.8).toString();
        assertTrue(text.startsWith("AABB["), text);
        assertTrue(text.contains("64.000"), text);
    }
}

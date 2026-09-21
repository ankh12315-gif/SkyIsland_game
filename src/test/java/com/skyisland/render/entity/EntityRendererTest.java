package com.skyisland.render.entity;

import com.skyisland.entity.Entity;
import com.skyisland.entity.MeleeMonster;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体盒体几何（{@link EntityRenderer#buildBoxVertices} 是纯函数，不需要 GL 上下文）。
 *
 * <p><b>为什么这条几何值得单测：</b>M2 的实体是"实心盒体"，而盒体只有两个可能坏掉的方式，
 * 且两种都<u>不会</u>报错、只会让画面变错：
 * <ol>
 *   <li><b>尺寸取错</b> —— 盒体与碰撞箱不一致。玩家会打在"看起来明明没打到"的位置上，
 *       而瞄准手感正是 M2 要验证的东西；</li>
 *   <li><b>绕序写反</b> —— 现在关着背面剔除所以看不出来，但一旦有人为了省一半片元
 *       打开剔除，实体会<b>整体消失</b>，而"画面里没有东西"与"渲染没跑"无法区分。</li>
 * </ol>
 * 本类把这两件事都钉死：尺寸逐面核对，六个面的三角形法线必须背离盒体中心。
 *
 * <p>顶点布局沿用体素着色器的约定：{@code pos vec3 + color vec4} = 7 个 float。
 */
class EntityRendererTest {

    private static final int FLOATS_PER_VERTEX = 7;

    private static MeleeMonster monster() {
        return new MeleeMonster(0.5, 64.0, 0.5);
    }

    @Test
    void oneBoxIsThirtySixVerticesAndTheBufferGrowsExactlyAsMuch() {
        Entity entity = monster();
        float[] out = new float[EntityRenderer.floatsPerBox() * 2];

        int written = EntityRenderer.buildBoxVertices(entity, 0, 0, 1f, 1f, 1f, 0f, out, 0);

        assertEquals(EntityRenderer.VERTS_PER_BOX * FLOATS_PER_VERTEX, written,
                "一个盒体 = 6 面 × 2 三角形 × 3 顶点 = 36 顶点");
        assertEquals(EntityRenderer.VERTS_PER_BOX, 36, "顶点数是渲染层与缓冲容量的共同契约");
    }

    @Test
    void theBoxMatchesTheEntityBoundingBoxExactly() {
        Entity entity = monster();
        var box = entity.boundingBox();
        float[] out = new float[EntityRenderer.floatsPerBox()];

        EntityRenderer.buildBoxVertices(entity, 0, 0, 1f, 1f, 1f, 0f, out, 0);

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < out.length; i += FLOATS_PER_VERTEX) {
            minX = Math.min(minX, out[i]);
            minY = Math.min(minY, out[i + 1]);
            minZ = Math.min(minZ, out[i + 2]);
            maxX = Math.max(maxX, out[i]);
            maxY = Math.max(maxY, out[i + 1]);
            maxZ = Math.max(maxZ, out[i + 2]);
        }

        // 所见即所中：盒体必须与碰撞箱逐面重合（世界偏移恒为整数，因此可直接比）
        assertEquals(box.minX(), minX, 1e-5, "−X 面必须贴在碰撞箱上");
        assertEquals(box.maxX(), maxX, 1e-5, "+X 面必须贴在碰撞箱上");
        assertEquals(box.minY(), minY, 1e-5, "−Y 面必须贴在碰撞箱上");
        assertEquals(box.maxY(), maxY, 1e-5, "+Y 面必须贴在碰撞箱上");
        assertEquals(box.minZ(), minZ, 1e-5, "−Z 面必须贴在碰撞箱上");
        assertEquals(box.maxZ(), maxZ, 1e-5, "+Z 面必须贴在碰撞箱上");
    }

    @Test
    void theWorldOffsetIsAppliedToXAndZOnly() {
        Entity entity = monster();
        float[] none = new float[EntityRenderer.floatsPerBox()];
        float[] shifted = new float[EntityRenderer.floatsPerBox()];

        EntityRenderer.buildBoxVertices(entity, 0, 0, 1f, 1f, 1f, 0f, none, 0);
        EntityRenderer.buildBoxVertices(entity, 7, -3, 1f, 1f, 1f, 0f, shifted, 0);

        for (int i = 0; i < none.length; i += FLOATS_PER_VERTEX) {
            assertEquals(none[i] - 7, shifted[i], 1e-5, "世界偏移必须只作用于 x（精度约定）");
            assertEquals(none[i + 1], shifted[i + 1], 1e-5, "y 不走偏移：世界高度很小，不需要");
            assertEquals(none[i + 2] + 3, shifted[i + 2], 1e-5, "世界偏移必须只作用于 z");
        }
    }

    @Test
    void everyTriangleFacesOutwards() {
        Entity entity = monster();
        float[] out = new float[EntityRenderer.floatsPerBox()];
        EntityRenderer.buildBoxVertices(entity, 0, 0, 1f, 1f, 1f, 0f, out, 0);

        // 盒体中心：六个面的平均
        double cx = 0;
        double cy = 0;
        double cz = 0;
        for (int i = 0; i < out.length; i += FLOATS_PER_VERTEX) {
            cx += out[i];
            cy += out[i + 1];
            cz += out[i + 2];
        }
        int verts = out.length / FLOATS_PER_VERTEX;
        cx /= verts;
        cy /= verts;
        cz /= verts;

        for (int t = 0; t < verts; t += 3) {
            double ax = out[t * FLOATS_PER_VERTEX];
            double ay = out[t * FLOATS_PER_VERTEX + 1];
            double az = out[t * FLOATS_PER_VERTEX + 2];
            double bx = out[(t + 1) * FLOATS_PER_VERTEX];
            double by = out[(t + 1) * FLOATS_PER_VERTEX + 1];
            double bz = out[(t + 1) * FLOATS_PER_VERTEX + 2];
            double dx = out[(t + 2) * FLOATS_PER_VERTEX];
            double dy = out[(t + 2) * FLOATS_PER_VERTEX + 1];
            double dz = out[(t + 2) * FLOATS_PER_VERTEX + 2];

            // 三角形法线（右手系，与 GL_FRONT_FACE=CCW 一致）
            double nx = (by - ay) * (dz - az) - (bz - az) * (dy - ay);
            double ny = (bz - az) * (dx - ax) - (bx - ax) * (dz - az);
            double nz = (bx - ax) * (dy - ay) - (by - ay) * (dx - ax);

            // 面心（相对盒体中心的指向）
            double fx = (ax + bx + dx) / 3.0 - cx;
            double fy = (ay + by + dy) / 3.0 - cy;
            double fz = (az + bz + dz) / 3.0 - cz;

            double dot = nx * fx + ny * fy + nz * fz;
            assertTrue(dot > 0,
                    "第 " + (t / 3) + " 个三角形的绕序反了（法线指向盒体内部）—— "
                            + "一旦有人打开背面剔除，实体会整体消失，"
                            + "而「画面里没有东西」与「渲染没跑」无法区分");
        }
    }

    @Test
    void flashBlendsTowardWhiteAndZeroKeepsTheBaseColor() {
        Entity entity = monster();
        float[] plain = new float[EntityRenderer.floatsPerBox()];
        float[] flashed = new float[EntityRenderer.floatsPerBox()];

        EntityRenderer.buildBoxVertices(entity, 0, 0, 0.4f, 0.2f, 0.3f, 0f, plain, 0);
        EntityRenderer.buildBoxVertices(entity, 0, 0, 0.4f, 0.2f, 0.3f, 1f, flashed, 0);

        assertEquals(0.4f, plain[3], 1e-6, "闪白强度 0 → 颜色就是传入的方块/实体色");
        assertEquals(0.2f, plain[4], 1e-6);
        assertEquals(0.3f, plain[5], 1e-6);

        assertTrue(flashed[3] > plain[3], "闪白必须往亮处混");
        assertTrue(flashed[4] > plain[4]);
        assertTrue(flashed[5] > plain[5]);
        for (int i = 0; i < flashed.length; i += FLOATS_PER_VERTEX) {
            assertEquals(1.0f, flashed[i + 6], 1e-6, "实体是不透明几何，alpha 恒为 1");
        }
    }

    @Test
    void flashIsClampedSoAnOutOfRangeValueCannotProduceInvalidColors() {
        Entity entity = monster();
        float[] out = new float[EntityRenderer.floatsPerBox()];
        float[] reference = new float[EntityRenderer.floatsPerBox()];

        EntityRenderer.buildBoxVertices(entity, 0, 0, 0.4f, 0.2f, 0.3f, 5f, out, 0);
        EntityRenderer.buildBoxVertices(entity, 0, 0, 0.4f, 0.2f, 0.3f, 1f, reference, 0);

        assertEquals(reference[3], out[3], 1e-6, "大于 1 的闪白强度必须被夹取，不得外插");
        assertEquals(reference[4], out[4], 1e-6);
        assertEquals(reference[5], out[5], 1e-6);
    }

    @Test
    void aDeadEntityIsSkippedByTheBatchButNotByTheGeometry() {
        // 几何函数不做存活判定（它只写顶点）；"死了就不画"是 render(...) 的职责。
        // 这里把契约写清楚，避免以后有人把判定挪进几何函数而让单测与渲染行为分叉。
        MeleeMonster dead = monster();
        dead.hurt(999);
        float[] out = new float[EntityRenderer.floatsPerBox()];

        int written = EntityRenderer.buildBoxVertices(dead, 0, 0, 1f, 1f, 1f, 0f, out, 0);

        assertEquals(EntityRenderer.VERTS_PER_BOX * FLOATS_PER_VERTEX, written);
    }
}

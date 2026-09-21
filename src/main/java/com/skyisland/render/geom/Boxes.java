package com.skyisland.render.geom;

/**
 * 把"盒体"写成体素着色器顶点（{@code pos vec3 + color vec4} = 7 float/顶点）的纯函数写入器。
 *
 * <h2>为什么需要它</h2>
 * M2.1 有两处要画盒体、且都不是"轴对齐的世界方块"：
 * <ol>
 *   <li><b>怪物的 parts</b>——头/躯干/四肢是若干个分离的小盒，而且整体要随怪物朝向
 *       <b>绕 Y 轴旋转</b>；</li>
 *   <li><b>第一人称手持物</b>——它是视图空间的几何，要随姿态做 yaw/pitch/roll 三轴旋转。</li>
 * </ol>
 * 两者的顶点格式、绕序约定、旋转数学完全一样，各写一份的结果是"绕序表"这种
 * 最容易写错、又最难从画面上归因的东西出现两份。因此抽成一个共用工具。
 *
 * <h2>坐标系约定</h2>
 * 传入的 {@code minX..maxZ} 是<b>相对旋转中心（pivot）的局部坐标</b>；
 * 本类先按 R = Ry(yaw)·Rx(pitch)·Rz(roll) 旋转，再平移 {@code (pivotX, pivotY, pivotZ)}。
 * 于是：
 * <ul>
 *   <li>怪物：pivot 取实体脚底中心（世界偏移已减），局部 y 直接就是绝对高度；</li>
 *   <li>手持物：pivot 取屏幕右下角的锚点，局部坐标即以锚点为原点。</li>
 * </ul>
 * 旋转矩阵的行列式恒为 +1，因此绕序不变——下面那张面表只要对轴对齐盒体成立，
 * 对任意旋转后的盒体同样成立。
 *
 * <h2>绕序</h2>
 * 六个面的四个角按"从盒体外侧看逆时针"排列（与 {@code GL_FRONT_FACE = CCW} 一致），
 * 角编号 {@code x位 | y位<<1 | z位<<2}（0 = min，1 = max）。
 * 这张表的正确性由 {@code EntityRendererTest#everyTriangleFacesOutwards} 钉死：
 * 它逐个三角形算叉积，断言法线背离盒体中心。
 * 现在项目里关着背面剔除，但一旦有人为了省一半片元打开剔除，绕序反了的症状是
 * "<b>东西整体消失</b>"——而"画面里没有东西"与"渲染没跑"是无法区分的两件事。
 *
 * <h2>为什么不做分配</h2>
 * 本类每帧被调用的次数是"实体数 × parts 数"，在 3000 FPS 下是每秒几十万次。
 * 因此 8 个角不落地成数组，而是在写顶点时按角编号现算（36 次旋转而不是 8 次），
 * 换零分配：多出来的 28 次乘加远小于一次堆分配的代价。
 */
public final class Boxes {

    /** 顶点格式与 {@code voxel.vert} 一致：aPos(vec3) + aColor(vec4)。 */
    public static final int FLOATS_PER_VERTEX = 7;

    /** 每盒 6 面 × 2 三角形 × 3 顶点。 */
    public static final int VERTS_PER_BOX = 36;

    /** 每盒占用的 float 数。 */
    public static final int FLOATS_PER_BOX = VERTS_PER_BOX * FLOATS_PER_VERTEX;

    /**
     * 六个面的四个角，顺序为"从外侧看逆时针"。
     * 排列顺序与 {@code CrackOverlay} / 原 {@code EntityRenderer#quad} 完全一致。
     */
    private static final int[][] FACE_CORNERS = {
            {4, 5, 7, 6},   // +Z
            {1, 0, 2, 3},   // -Z
            {5, 1, 3, 7},   // +X
            {0, 4, 6, 2},   // -X
            {6, 7, 3, 2},   // +Y
            {0, 1, 5, 4},   // -Y
    };

    /** 一个四边形拆成两个三角形。 */
    private static final int[] TRIANGLE_ORDER = {0, 1, 2, 0, 2, 3};

    private Boxes() {
    }

    /**
     * 写一个盒体。
     *
     * @param minX..maxZ 相对 pivot 的局部坐标
     * @param pivotX/Y/Z 旋转中心，同时也是旋转之后加上的平移
     * @param brightness 写进 {@code aColor.a} 的值。{@code voxel.frag} 里是
     *                    {@code rgb * a}，所以它是<b>亮度</b>而不是不透明度 ——
     *                    枪口闪光就靠它做衰减
     * @return 新的写入位置
     */
    public static int write(float[] out, int start,
                            double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ,
                            double pivotX, double pivotY, double pivotZ,
                            double yawRad, double pitchRad, double rollRad,
                            float r, float g, float b, float brightness) {
        double cy = Math.cos(yawRad);
        double sy = Math.sin(yawRad);
        double cp = Math.cos(pitchRad);
        double sp = Math.sin(pitchRad);
        double cr = Math.cos(rollRad);
        double sr = Math.sin(rollRad);

        // R = Ry(yaw) · Rx(pitch) · Rz(roll)，行主序展开
        double m00 = cy * cr + sy * sp * sr;
        double m01 = -cy * sr + sy * sp * cr;
        double m02 = sy * cp;
        double m10 = cp * sr;
        double m11 = cp * cr;
        double m12 = -sp;
        double m20 = -sy * cr + cy * sp * sr;
        double m21 = sy * sr + cy * sp * cr;
        double m22 = cy * cp;

        int p = start;
        for (int[] face : FACE_CORNERS) {
            for (int k = 0; k < TRIANGLE_ORDER.length; k++) {
                int corner = face[TRIANGLE_ORDER[k]];
                double lx = (corner & 1) != 0 ? maxX : minX;
                double ly = (corner & 2) != 0 ? maxY : minY;
                double lz = (corner & 4) != 0 ? maxZ : minZ;

                out[p] = (float) (m00 * lx + m01 * ly + m02 * lz + pivotX);
                out[p + 1] = (float) (m10 * lx + m11 * ly + m12 * lz + pivotY);
                out[p + 2] = (float) (m20 * lx + m21 * ly + m22 * lz + pivotZ);
                out[p + 3] = r;
                out[p + 4] = g;
                out[p + 5] = b;
                out[p + 6] = brightness;
                p += FLOATS_PER_VERTEX;
            }
        }
        return p;
    }
}

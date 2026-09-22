package com.skyisland.render.entity;

import com.skyisland.entity.MeleeMonster;
import com.skyisland.player.Player;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 怪物 parts 模型（{@link MonsterModel}）—— 纯几何，不需要 GL 上下文。
 *
 * <p><b>这一组测试存在的唯一理由：</b>
 * parts 模型是"为了好看"而引入的，而好看的东西最容易在"看起来没问题"的掩护下
 * 悄悄越过一条硬边界 —— 这条边界就是<b>视觉必须仍在碰撞箱之内</b>。
 * 越界的后果不是画面变丑，而是玩家打在"看起来明明没打到"的空气上，
 * 而这类不一致在射击手感里是致命的（原 {@code EntityRenderer} 的类注释：
 * "宁可丑，不可不一致"）。
 *
 * <p>因此本类不测"好看"，只测三件可判定的事：
 * <ol>
 *   <li><b>结构</b>——parts 的数量与相对位置（头在上、腿在下、眼睛只长在正面）；</li>
 *   <li><b>尺寸</b>——静止时的总高恰好等于碰撞箱的高（1.80）；</li>
 *   <li><b>包络</b>——在整个动画包络（步态相位 × 攻击摆动 × 朝向）上扫一遍，
 *       parts 的并集恒在碰撞箱之内。</li>
 * </ol>
 * 第 3 条最关键：它是"动画会不会把模型甩出碰撞箱"的唯一保险，
 * 而那正是靠肉眼调参时最容易漏掉的（调的时候只看一帧）。
 */
class MonsterModelTest {

    private static final double EPS = 1e-5;

    private static final int HEAD = MonsterModel.PART_HEAD;
    private static final int TORSO = MonsterModel.PART_TORSO;
    private static final int LEG_L = MonsterModel.PART_LEG_LEFT;
    private static final int LEG_R = MonsterModel.PART_LEG_RIGHT;
    private static final int ARM_L = MonsterModel.PART_ARM_LEFT;
    private static final int ARM_R = MonsterModel.PART_ARM_RIGHT;
    private static final int EYE_L = MonsterModel.PART_EYE_LEFT;
    private static final int EYE_R = MonsterModel.PART_EYE_RIGHT;

    private static float[] parts(double walkPhase, double attackSwing) {
        float[] out = new float[MonsterModel.PART_COUNT * MonsterModel.FLOATS_PER_PART];
        MonsterModel.write(out, 0, walkPhase, attackSwing);
        return out;
    }

    private static double min(float[] p, int part, int axis) {
        return p[part * MonsterModel.FLOATS_PER_PART + axis];
    }

    private static double max(float[] p, int part, int axis) {
        return p[part * MonsterModel.FLOATS_PER_PART + 3 + axis];
    }

    // ============================================================ 结构

    @Test
    void theModelIsOneHeadOneTorsoTwoLegsTwoArmsAndTwoEyes() {
        float[] p = parts(0, 0);
        assertEquals(MonsterModel.PART_COUNT, p.length / MonsterModel.FLOATS_PER_PART,
                "parts 数 = 头 + 躯干 + 双腿 + 双臂 + 双眼");
        assertEquals(8, MonsterModel.PART_COUNT);
    }

    @Test
    void headSitsOnTopOfTheTorsoAndLegsAreAtTheVeryBottom() {
        float[] p = parts(0, 0);

        // 头在躯干之上，且两者之间留出一道"脖子"缝 —— 读起来才是一个独立的头，
        // 而不是躯干顶上长了个包
        assertTrue(min(p, HEAD, 1) > max(p, TORSO, 1),
                "头的底边必须在躯干顶边之上（否则看不出头/身体是两截）");
        assertTrue(min(p, HEAD, 1) - max(p, TORSO, 1) < 0.10,
                "脖子缝不能大到看起来像头浮在空中");

        // 腿从地面（y = 0）开始，且顶端被躯干盖住（不能出现"腿和身体之间断一截"）
        assertEquals(0.0, min(p, LEG_L, 1), EPS, "左脚必须踩在脚底中心平面上");
        assertEquals(0.0, min(p, LEG_R, 1), EPS, "右脚必须踩在脚底中心平面上");
        assertTrue(max(p, LEG_L, 1) > min(p, TORSO, 1),
                "腿的顶端必须与躯干重叠，否则会出现一道横断缝");

        // 头比躯干窄：类人剪影的关键特征是"头—肩"的宽窄变化
        double headWidth = max(p, HEAD, 0) - min(p, HEAD, 0);
        double torsoWidth = max(p, TORSO, 0) - min(p, TORSO, 0);
        assertTrue(headWidth < torsoWidth, "头必须比躯干窄，否则读起来是一个整块");
    }

    @Test
    void armsHangOutsideTheTorsoAndLegsAreSplitLeftAndRight() {
        float[] p = parts(0, 0);

        assertTrue(min(p, ARM_L, 0) < min(p, TORSO, 0), "左臂必须在躯干左侧之外");
        assertTrue(max(p, ARM_R, 0) > max(p, TORSO, 0), "右臂必须在躯干右侧之外");
        assertTrue(max(p, LEG_L, 0) < min(p, LEG_R, 0), "两条腿之间必须有缝（否则是一条裙子）");
        assertTrue(min(p, LEG_L, 0) > -MonsterModel.HALF_WIDTH - EPS, "左腿不得超出碰撞箱");
        assertTrue(max(p, LEG_R, 0) < MonsterModel.HALF_WIDTH + EPS, "右腿不得超出碰撞箱");
    }

    @Test
    void eyesExistOnlyOnTheFrontFaceAndStickOutJustEnoughToBeSeen() {
        float[] p = parts(0, 0);
        double headFront = min(p, HEAD, 2);

        for (int eye : new int[]{EYE_L, EYE_R}) {
            assertTrue(max(p, eye, 2) < 0,
                    "眼睛必须只长在正面（局部 -Z）—— 背面不能有，否则分不出正反");
            assertTrue(min(p, eye, 2) < headFront,
                    "眼睛必须从脸部凸出一点，否则会被头的表面 z-fighting 掉");
            assertTrue(min(p, eye, 2) > headFront - 0.06,
                    "眼睛不能凸出太多（会变成两根犄角）");
            assertTrue(max(p, eye, 2) > headFront,
                    "眼睛的后半截要埋进头里，否则看起来是浮在脸前的两片");
            // 眼睛在头的高度范围内、且在头的左右范围之外吗？——必须在头的框内
            assertTrue(min(p, eye, 1) > min(p, HEAD, 1), "眼睛必须在头的高度范围内");
            assertTrue(max(p, eye, 1) < max(p, HEAD, 1), "眼睛必须在头的高度范围内");
            assertTrue(min(p, eye, 0) > min(p, HEAD, 0), "眼睛必须在头的左右范围内");
            assertTrue(max(p, eye, 0) < max(p, HEAD, 0), "眼睛必须在头的左右范围内");
        }
        assertTrue(max(p, EYE_L, 0) < min(p, EYE_R, 0), "两只眼睛必须分开（一眼看出是一对）");
    }

    // ============================================================ 尺寸

    @Test
    void atRestTheTotalHeightIsExactlyTheCollisionBoxHeight() {
        float[] p = parts(0, 0);

        double top = max(p, HEAD, 1);
        double bottom = Math.min(min(p, LEG_L, 1), min(p, LEG_R, 1));

        assertEquals(MonsterModel.HEIGHT, top, 1e-4,
                "静止时头顶点必须恰好等于碰撞箱高度 1.80 —— 这就是「总高度与碰撞箱一致」");
        assertEquals(1.80, top, 1e-4, "碰撞箱高度必须仍然是 M2 定下的 1.80（M2.1 不许动它）");
        assertEquals(0.0, bottom, EPS, "脚底必须贴在碰撞箱底面上");
    }

    @Test
    void atRestTheModelFillsTheFullCollisionBoxWidth() {
        float[] p = parts(0, 0);
        double minX = Math.min(min(p, ARM_L, 0), min(p, LEG_L, 0));
        double maxX = Math.max(max(p, ARM_R, 0), max(p, LEG_R, 0));

        assertEquals(-MonsterModel.HALF_WIDTH, minX, 1e-4, "左边界应当正好用满碰撞箱");
        assertEquals(MonsterModel.HALF_WIDTH, maxX, 1e-4, "右边界应当正好用满碰撞箱");
    }

    // ============================================================ 动画包络

    @Test
    void theUnionNeverLeavesTheCollisionBoxAcrossTheWholeAnimationEnvelope() {
        double worstMinY = Double.MAX_VALUE;
        double worstMaxY = -Double.MAX_VALUE;
        double worstAbsX = 0;
        double worstAbsZ = 0;

        for (int i = 0; i <= 64; i++) {
            double phase = i * (2 * Math.PI) / 64 * 2;    // 扫两个完整步态周期
            for (int j = 0; j <= 10; j++) {
                float[] p = parts(phase, j / 10.0);
                double minX = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE;
                double minY = Double.MAX_VALUE;
                double maxY = -Double.MAX_VALUE;
                double minZ = Double.MAX_VALUE;
                double maxZ = -Double.MAX_VALUE;
                for (int part = 0; part < MonsterModel.PART_COUNT; part++) {
                    minX = Math.min(minX, min(p, part, 0));
                    maxX = Math.max(maxX, max(p, part, 0));
                    minY = Math.min(minY, min(p, part, 1));
                    maxY = Math.max(maxY, max(p, part, 1));
                    minZ = Math.min(minZ, min(p, part, 2));
                    maxZ = Math.max(maxZ, max(p, part, 2));
                }
                worstMinY = Math.min(worstMinY, minY);
                worstMaxY = Math.max(worstMaxY, maxY);
                worstAbsX = Math.max(worstAbsX, Math.max(-minX, maxX));
                worstAbsZ = Math.max(worstAbsZ, Math.max(-minZ, maxZ));
            }
        }

        assertTrue(worstMaxY <= MonsterModel.HEIGHT + EPS,
                "头顶点在任何动画状态下都不得超出碰撞箱顶（实测最高 " + worstMaxY + "）—— "
                        + "超出就意味着玩家能打到「看起来没东西」的位置");
        assertTrue(worstMinY >= -MonsterModel.BOB_AMPLITUDE - EPS,
                "脚底最多只能下沉一个起伏幅度（实测最低 " + worstMinY + "）");
        assertTrue(worstAbsX <= MonsterModel.HALF_WIDTH + EPS,
                "左右不得超出碰撞箱半宽（实测 " + worstAbsX + "）");
        assertTrue(worstAbsZ <= MonsterModel.HALF_WIDTH + EPS,
                "前后不得超出碰撞箱半深（实测 " + worstAbsZ + "）—— "
                        + "攻击前冲必须做在脚印之内");
    }

    @Test
    void theWorstRotationBulgeStaysUnderTheDocumentedTolerance() {
        // 部件撑满 ±0.30 之后，绕 Y 轴旋转必然在四个角上鼓出去一点
        // （方盒套方盒的几何必然）。这里把"最坏鼓包"钉死在容差之内，
        // 免得以后有人把手臂加深/前摆加大，让鼓包悄悄长到不可接受。
        double worst = 0;
        for (int i = 0; i <= 64; i++) {
            double phase = i * (4 * Math.PI) / 64;
            for (int j = 0; j <= 10; j++) {
                float[] p = parts(phase, j / 10.0);
                for (int part = 0; part < MonsterModel.PART_COUNT; part++) {
                    for (int sx = 0; sx < 2; sx++) {
                        for (int sz = 0; sz < 2; sz++) {
                            double x = sx == 0 ? min(p, part, 0) : max(p, part, 0);
                            double z = sz == 0 ? min(p, part, 2) : max(p, part, 2);
                            worst = Math.max(worst, Math.hypot(x, z));
                        }
                    }
                }
            }
        }
        double limit = MonsterModel.HALF_WIDTH * MonsterModel.HORIZONTAL_ROTATION_TOLERANCE;
        assertTrue(worst <= limit,
                "最坏旋转鼓包 " + worst + " 超过了容差 " + limit
                        + " —— 把手臂加深或把前摆加大会让它继续变大");
        assertTrue(worst <= MonsterModel.HALF_WIDTH * 1.20,
                "容差本身不得放宽：它是「方盒套方盒必然鼓出」的量，不是给偷懒留的余量");
    }

    @Test
    void walkBobOnlyGoesDownSoTheHeadNeverPokesOut() {
        boolean sawFullHeight = false;
        boolean sawLowered = false;
        for (int i = 0; i <= 32; i++) {
            float[] p = parts(i * (2 * Math.PI) / 32, 0);
            double top = max(p, HEAD, 1);
            assertTrue(top <= MonsterModel.HEIGHT + EPS,
                    "第 " + i + " 个相位上头顶点超出了碰撞箱：" + top);
            if (Math.abs(top - MonsterModel.HEIGHT) < 1e-4) {
                sawFullHeight = true;
            }
            if (top < MonsterModel.HEIGHT - 1e-3) {
                sawLowered = true;
            }
        }
        assertTrue(sawFullHeight, "步态中必须存在「站直」的相位，否则怪物等于一直矮着一截");
        assertTrue(sawLowered, "步态中必须存在下沉的相位，否则没有起伏可言");
    }

    @Test
    void legsSwingInOppositeDirectionsAndStayInsideTheFootprint() {
        float[] rest = parts(0, 0);
        float[] mid = parts(Math.PI / 2, 0);

        double restLegL = min(rest, LEG_L, 2);
        double midLegL = min(mid, LEG_L, 2);
        double midLegR = min(mid, LEG_R, 2);

        assertTrue(midLegL > restLegL + 1e-3, "左腿必须向前/后摆出去（相位 π/2 时最远）");
        assertTrue(midLegR < restLegL - 1e-3, "右腿必须朝反方向摆（否则是并腿跳）");
        assertTrue(Math.abs(midLegL) <= MonsterModel.HALF_WIDTH + EPS
                        && Math.abs(midLegR) <= MonsterModel.HALF_WIDTH + EPS,
                "摆腿不得把脚甩出碰撞箱");
    }

    @Test
    void attackingSwingsTheArmsForwardWithoutLeavingTheFootprint() {
        float[] rest = parts(0, 0);
        float[] swing = parts(0, 1);

        assertTrue(max(swing, ARM_L, 2) < max(rest, ARM_L, 2) - 1e-3,
                "攻击时手臂必须朝正面（-Z）摆出去");
        assertTrue(min(swing, ARM_L, 2) > -MonsterModel.HALF_WIDTH - EPS,
                "摆臂的最前端仍必须在碰撞箱内 —— 前冲是靠摆臂表达的，不是靠平移身体");
        assertTrue(max(swing, HEAD, 1) <= MonsterModel.HEIGHT + EPS,
                "攻击时头顶点仍不得超出碰撞箱");
    }

    // ============================================================ 配色（可读性判据）

    /** 判据用的 luma 系数：Rec.709（0.2126R + 0.7152G + 0.0722B）。 */
    private static double luma(float[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** 体色必须落在的中亮区间：下界避开极暗并成一块，上界让整体仍暗于天空（luma 0.610）。 */
    private static final double BODY_LUMA_MIN = 0.28;
    private static final double BODY_LUMA_MAX = 0.58;

    /** 屏幕上相邻的两个 part 至少要拉开的 luma 落差（一个可分辨的台阶）。 */
    private static final double ADJACENT_LUMA_GAP = 0.06;

    /** 眼睛相对任何体色至少要高出的绝对 luma。 */
    private static final double EYE_MARGIN = 0.25;

    /** 闪白后最暗的 part 相对未闪白最亮的 part 至少要高出的绝对 luma。 */
    private static final double FLASH_MARGIN = 0.25;

    /**
     * 眼睛必须明显亮于任何体色 —— 这是「一眼看出正面 / 看出是脸」的唯一依据。
     *
     * <p><b>为什么这条从旧的「亮 2.5 倍」改成「绝对差 + 较低倍率」：</b>
     * 旧判据是 {@code eyeLum > 2.5 × bodyLum}。旧体色极暗（头 luma 0.234），
     * 2.5 倍很轻松。但体色一旦被抬进中亮区间以修好剪影可读性，这条就<b>数学上不可能</b>
     * 再成立：眼睛是屏幕像素，luma 上限为 1.0（纯白），要凑出 2.5 倍需要体色不高于 0.4，
     * 而那正是本里程碑要修掉的「极暗区间」。两条要求无法同时满足，
     * 因此保留「眼睛必须明显更亮」的意图，换成一个量纲正确、且与新要求不冲突的判据：
     * 绝对差大于等于 {@link #EYE_MARGIN}，且亮出至少 1.3 倍。
     */
    @Test
    void eyesStayClearlyBrighterThanAnyBodyPart() {
        double eyeLum = 0;
        for (int eye : new int[]{EYE_L, EYE_R}) {
            eyeLum = Math.max(eyeLum, luma(MonsterModel.color(eye)));
        }
        for (int part : new int[]{HEAD, TORSO, LEG_L, ARM_L}) {
            double lum = luma(MonsterModel.color(part));
            assertTrue(eyeLum - lum >= EYE_MARGIN,
                    "眼睛与 " + part + " 号部件的 luma 差必须大于等于 " + EYE_MARGIN
                            + "，实测 " + (eyeLum - lum) + "（眼睛 " + eyeLum + "，体色 " + lum + "）");
            assertTrue(eyeLum >= lum * 1.3,
                    "眼睛还必须比 " + part + " 号部件亮至少 30%（实测 " + (eyeLum / lum) + " 倍）");
        }
    }

    @Test
    void bodyColorsAreAllReddishAndTheThreeGroupsAreDistinguishable() {
        int[] groups = {HEAD, TORSO, LEG_L, ARM_L};
        for (int part : groups) {
            float[] c = MonsterModel.color(part);
            assertTrue(c[0] > c[1] && c[0] > c[2],
                    "第 " + part + " 号部件必须是偏红的（主色砖红）");
        }
        float[] head = MonsterModel.color(HEAD);
        float[] torso = MonsterModel.color(TORSO);
        float[] leg = MonsterModel.color(LEG_L);
        assertTrue(head[0] > torso[0] && torso[0] > leg[0],
                "头 > 躯干 > 腿 的明度梯度要成立，于是三段结构靠颜色也能读出来");
        assertTrue(MonsterModel.isEye(EYE_L) && MonsterModel.isEye(EYE_R));
        assertTrue(!MonsterModel.isEye(HEAD));
    }

    /**
     * 四个体色必须全部落在中亮区间 —— 把「整具身体挤在极暗带里并成一块」钉死。
     *
     * <p>旧配色的 luma 是 头 0.234 / 躯干 0.175 / 臂 0.142 / 腿 0.121，四个值全部小于 0.28，
     * 因此本条在旧配色上<b>必然失败</b>（等价回退实验的举证见交付报告）。
     * 上界 0.58 让整体仍暗于天空色（0.46/0.63/0.86 → luma 0.610），
     * 怪物在天空背景上仍是「一块比天空暗的红」，危险读法不变。
     */
    @Test
    void bodyPartsSitInAMidLegibleLumaBand() {
        for (int part : new int[]{HEAD, TORSO, LEG_L, ARM_L}) {
            double lum = luma(MonsterModel.color(part));
            assertTrue(lum >= BODY_LUMA_MIN && lum <= BODY_LUMA_MAX,
                    "第 " + part + " 号部件的 luma " + lum + " 必须落在 ["
                            + BODY_LUMA_MIN + ", " + BODY_LUMA_MAX
                            + "] 之内：低了会并成一块黑影，高了会与天空糊在一起");
        }
    }

    /**
     * 屏幕上相邻的 part 之间必须有足够的 luma 落差 —— 这是「分件读得出来」的量化判据。
     *
     * <p>「相邻」指在 8 个 part 的静止布局里共享一段边界、因而在远处会并成一片的四对：
     * 头 与 躯干（脖子缝）、躯干 与 臂（手臂贴躯干外侧，共享整条竖边）、
     * 躯干 与 腿（腿顶被躯干压住）、臂 与 腿（在 y=0.72 处相接）。
     * 旧配色里躯干 与 臂只差 0.033、臂 与 腿 0.021、躯干 与 腿 0.054、头 与 躯干 0.059，
     * 最小相邻落差 0.021 小于 0.06，因此本条在旧配色上<b>必然失败</b>。
     */
    @Test
    void adjacentPartsAreSeparatedByEnoughLuma() {
        int[][] adjacent = {{HEAD, TORSO}, {TORSO, ARM_L}, {TORSO, LEG_L}, {ARM_L, LEG_L}};
        double worst = Double.MAX_VALUE;
        for (int[] pair : adjacent) {
            double gap = Math.abs(luma(MonsterModel.color(pair[0])) - luma(MonsterModel.color(pair[1])));
            worst = Math.min(worst, gap);
        }
        assertTrue(worst >= ADJACENT_LUMA_GAP,
                "屏幕上相邻 part 的最小 luma 落差 " + worst + " 小于判据 " + ADJACENT_LUMA_GAP
                        + " —— 相邻分件会并成一片，8 个 part 的剪影结构读不出来");
    }

    /**
     * 受击闪白必须明显亮于最亮的体色 —— 否则「打中了」这个即时信号会被忽略。
     *
     * <p>这里走真正的渲染路径取闪白后的顶点色（{@code flash = 1} 时全体混到闪白目标色），
     * 因此它钉的是「实体渲染里那三个 FLASH 常量」与体色之间的真实关系，
     * 而不是把常量值抄一份到测试里。
     */
    @Test
    void hurtFlashStaysClearlyAboveTheUnflashedBody() {
        MeleeMonster monster = new MeleeMonster(0.5, 64.0, 0.5);
        EntityRenderer renderer = new EntityRenderer();
        float[] plain = new float[MonsterModel.PART_COUNT * EntityRenderer.floatsPerBox()];
        float[] flashed = new float[plain.length];
        renderer.buildMonsterVertices(monster, 0, 0, 0f, plain, 0);
        renderer.buildMonsterVertices(monster, 0, 0, 1f, flashed, 0);

        double bodyMax = 0;
        double flashMin = Double.MAX_VALUE;
        // 只看四个体色（头/躯干/腿/臂），不含眼睛：眼睛本来就接近上限亮度，
        // 把它当「体色亮度」会让这条判据变成「眼睛够不够亮」，与实物不符。
        for (int part : new int[]{HEAD, TORSO, LEG_L, ARM_L}) {
            // 每个盒体 252 个 float：每顶点 7 个（pos vec3 + color vec4），
            // 所以第 4..6 个才是 rgb —— 用 part 直接当颜色下标会读到 position。
            int v = part * EntityRenderer.floatsPerBox() + 3;
            bodyMax = Math.max(bodyMax, luma(new float[]{plain[v], plain[v + 1], plain[v + 2]}));
            flashMin = Math.min(flashMin, luma(new float[]{flashed[v], flashed[v + 1], flashed[v + 2]}));
        }
        assertTrue(flashMin - bodyMax >= FLASH_MARGIN,
                "闪白后最暗的 part（luma " + flashMin + "）仍须比未闪白最亮的 part（luma "
                        + bodyMax + "）高出 " + FLASH_MARGIN + "，否则受击信号会看不见");
    }

    // ============================================================ 渲染路径（带朝向）

    @Test
    void oneMonsterWritesExactlyPartCountBoxes() {
        MeleeMonster monster = new MeleeMonster(0.5, 64.0, 0.5);
        EntityRenderer renderer = new EntityRenderer();
        float[] out = new float[MonsterModel.PART_COUNT * EntityRenderer.floatsPerBox()];

        int written = renderer.buildMonsterVertices(monster, 0, 0, 0f, out, 0);

        assertEquals(MonsterModel.PART_COUNT * EntityRenderer.floatsPerBox(), written,
                "一只怪必须正好写出 PART_COUNT 个盒体（多一个说明 parts 表被加长而缓冲没跟上）");
        assertEquals(written / 7 / 36, MonsterModel.PART_COUNT);
    }

    /**
     * 绕 Y 轴转满 360°（每 15° 一个朝向）之后，parts 的并集仍必须落在碰撞箱里。
     *
     * <h2>这条测试以前为什么是「假阳性」（留痕：本项目反复栽的坑）</h2>
     * 旧版本用 {@code double dy = out[i + 1] - TestWorlds.SURFACE_FEET_Y;} 把顶点高度
     * 减去一个<b>常量脚底高度</b>，再断言 {@code dy <= HEIGHT + 1e-3}（<b>单边上界</b>）。
     * 两处一起，使它<b>结构上不可能</b>发现"渲染器忘记把世界 Y 加回去"这个缺陷：
     * <ol>
     *   <li><b>锚点错了</b>：渲染器把 pivotY 写成 {@code 0.0} 时，{@code out[i+1]} 恰好等于
     *       局部高度；减掉常量 64 得到 {@code 局部高度 − 64}。这不是"离脚底多高"，
     *       而是一个恒为负的量 —— 它根本没参与"世界 Y 是否等于实体位置"这件事；</li>
     *   <li><b>只有上界</b>：缺陷把几何整体往<b>低</b>的方向推了 62 格，
     *       而 {@code -62.2 ≤ 1.8} 恒真，上界对此完全失明。</li>
     * </ol>
     * 结果：整具怪被画到地下、屏幕上零像素，而这条跑得最勤的几何测试一直全绿。
     *
     * <h2>现在为什么能变红</h2>
     * 锚点改用<b>实体的世界 Y</b>（{@code monster.position().y}，即脚底中心；这也是
     * {@link MonsterModel} 的局部原点），于是读数变成真正的"离脚底的高度"，
     * 并同时压三条约束：
     * <ul>
     *   <li><b>上界</b>（头不得戳出碰撞箱）：{@code worldDy <= HEIGHT + 1e-3}；</li>
     *   <li><b>下界</b>（<u>抓本缺陷的那一条</u>）：脚底最多只能因为行走起伏下沉一个
     *       {@link MonsterModel#BOB_AMPLITUDE}；渲染器丢掉世界 Y 时实测约 −64，
     *       立刻跌破下界并变红；</li>
     *   <li><b>存在性</b>：必须真的存在接近顶部的顶点（{@code >= 1.5}），
     *       否则"整只怪塌到脚底"也能满足上面那条上界。</li>
     * </ul>
     *
     * <p>前置条件：{@code monster.position().y} 必须非零，否则"减 0"会退化成旧口径。
     * 夹具用 {@link TestWorlds#SURFACE_FEET_Y}（= 64），天然满足。
     */
    @Test
    void rotatedPartsStillFitInsideTheCollisionBox() {
        // 走真正的渲染路径（含朝向旋转），核对"转过去之后"仍然不越界。
        // 这是 parts 模型唯一可能悄悄变坏的地方：局部 AABB 合法，
        // 但旋转之后各 part 的并集变大 —— 而调参时只看 yaw = 0 那一帧是发现不了的。
        EntityRenderer renderer = new EntityRenderer();
        float[] out = new float[MonsterModel.PART_COUNT * EntityRenderer.floatsPerBox()];
        World world = TestWorlds.flatWorld();
        double worstAbsX = 0;
        double worstAbsZ = 0;
        double worstMinY = Double.MAX_VALUE;
        double worstMaxY = -Double.MAX_VALUE;

        for (int deg = 0; deg < 360; deg += 15) {
            double rad = Math.toRadians(deg);
            // 把玩家放在怪物的某个方向上，tick 一次即让怪物转向他
            MeleeMonster monster;
            Player player;
            monster = new MeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
            player = new Player(0.5 + Math.sin(rad) * 6.0,
                    TestWorlds.SURFACE_FEET_Y, 0.5 + Math.cos(rad) * 6.0);
            monster.tick(world, player, 1.0 / 60.0);

            int written = renderer.buildMonsterVertices(monster, 0, 0, 0f, out, 0);
            assertEquals(MonsterModel.PART_COUNT * EntityRenderer.floatsPerBox(), written);

            // ★ 锚点 = 实体的世界 Y（脚底中心），不是某个常量。
            //   旧版本减的是常量 SURFACE_FEET_Y —— 那恰好就是"渲染器忘记加的那一项"，
            //   于是缺陷被减掉了，详见方法 javadoc。
            double cx = monster.position().x;
            double cy = monster.position().y;
            double cz = monster.position().z;
            for (int i = 0; i < written; i += 7) {
                double dx = out[i] - cx;
                double worldDy = out[i + 1] - cy;   // 离脚底的世界高度
                double dz = out[i + 2] - cz;
                worstAbsX = Math.max(worstAbsX, Math.abs(dx));
                worstAbsZ = Math.max(worstAbsZ, Math.abs(dz));
                worstMinY = Math.min(worstMinY, worldDy);
                worstMaxY = Math.max(worstMaxY, worldDy);
            }
        }

        double limit = MonsterModel.HALF_WIDTH * MonsterModel.HORIZONTAL_ROTATION_TOLERANCE;
        assertTrue(worstAbsX <= limit,
                "任何朝向下左右都不得超出旋转容差（实测 " + worstAbsX + "，容差 " + limit + "）");
        assertTrue(worstAbsZ <= limit,
                "任何朝向下前后都不得超出旋转容差（实测 " + worstAbsZ + "，容差 " + limit + "）");
        // 上界：头不得戳出碰撞箱顶。
        assertTrue(worstMaxY <= MonsterModel.HEIGHT + 1e-3,
                "任何朝向下头顶点都不得超出碰撞箱（实测 " + worstMaxY + "）—— "
                        + "垂直方向没有容差：绕 Y 轴转不改变 y，超了就是模型做错了");
        // ★ 下界：抓「渲染器丢掉世界 Y」的那一条。旧口径只测上界，对"整体沉到地下"完全失明。
        assertTrue(worstMinY >= -MonsterModel.BOB_AMPLITUDE - 1e-3,
                "任何朝向下最低的顶点都不得低于脚底超过行走起伏幅度"
                        + "（实测最低 " + worstMinY + "，下界 " + (-MonsterModel.BOB_AMPLITUDE) + "）—— "
                        + "跌破下界说明渲染出的几何整体沉到了脚底之下，"
                        + "最常见的原因是渲染器没有把世界 Y（pivotY = position().y）加回去");
        // ★ 存在性：确认"确实有部件在顶部"，否则"整只怪塌到脚底"也能满足上面的上界。
        assertTrue(worstMaxY >= 1.5,
                "必须存在接近头部的顶点（实测最高 " + worstMaxY + "）—— "
                        + "若整只模型塌到脚底附近，上面的上界断言会失去意义");
    }

    @Test
    void theMonsterFacesThePlayerSoTheEyesPointAtHim() {
        World world = TestWorlds.flatWorld();
        // 每只怪只 tick 一次：第二 tick 起它已经朝玩家走过一步，位置变了，
        // 于是"朝向"不再是纯粹的"玩家在哪个方向"
        MeleeMonster east = new MeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        east.tick(world, new Player(8.5, TestWorlds.SURFACE_FEET_Y, 0.5), 1.0 / 60.0);
        assertEquals(-90.0, east.facingDeg(), 0.001,
                "玩家在 +X 时怪物必须转向 +X（yaw = -90°），否则眼睛不会朝着玩家");

        MeleeMonster north = new MeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        north.tick(world, new Player(0.5, TestWorlds.SURFACE_FEET_Y, -8.5), 1.0 / 60.0);
        assertEquals(0.0, north.facingDeg(), 0.001,
                "玩家在 -Z 时朝向应为 0（与 Camera 的 yaw = 0 朝 -Z 同口径）");
    }

    @Test
    void flashWhitensEveryPartNotJustTheTorso() {
        MeleeMonster monster = new MeleeMonster(0.5, 64.0, 0.5);
        EntityRenderer renderer = new EntityRenderer();
        float[] plain = new float[MonsterModel.PART_COUNT * EntityRenderer.floatsPerBox()];
        float[] flashed = new float[plain.length];

        renderer.buildMonsterVertices(monster, 0, 0, 0f, plain, 0);
        renderer.buildMonsterVertices(monster, 0, 0, 1f, flashed, 0);

        for (int i = 0; i < plain.length; i += 7) {
            assertTrue(flashed[i + 3] >= plain[i + 3] - 1e-6,
                    "闪白后红色分量不得变暗（第 " + (i / 7) + " 个顶点）");
            assertTrue(flashed[i + 6] > 0.99f, "闪白不得动 alpha（实体恒为不透明）");
        }
        // 至少要有相当比例的顶点真的变亮了 —— 否则"闪白只作用在一个 part 上"也能通过
        int brighter = 0;
        for (int i = 0; i < plain.length; i += 7) {
            if (flashed[i + 3] > plain[i + 3] + 1e-3) {
                brighter++;
            }
        }
        assertTrue(brighter > plain.length / 7 * 0.5,
                "闪白必须覆盖大多数部件（实测 " + brighter + " 个顶点变亮）");
    }

    @Test
    void walkingAdvancesTheGaitPhaseAndStandingStillFreezesIt() {
        World world = TestWorlds.flatWorld();
        MeleeMonster monster = new MeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        Player far = new Player(0.5, TestWorlds.SURFACE_FEET_Y, -12.0);

        assertEquals(0.0, monster.walkDistance(), 0.0, "刚出生时一步没走");
        for (int i = 0; i < 30; i++) {
            monster.tick(world, far, 1.0 / 60.0);
        }
        assertTrue(monster.walkDistance() > 1e-3,
                "追击玩家之后必须累计出行走距离（步态相位由此而来）");

        double frozen = monster.walkDistance();
        Player adjacent = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        for (int i = 0; i < 30; i++) {
            monster.tick(world, adjacent, 1.0 / 60.0);
        }
        assertEquals(frozen, monster.walkDistance(), 1e-9,
                "进入攻击距离后不再移动，行走距离必须停住（否则会原地踏步）");
    }

    @Test
    void attackSwingRisesAndFallsBackToZeroWithinOneCooldown() {
        World world = TestWorlds.flatWorld();
        MeleeMonster monster = new MeleeMonster(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
        Player adjacent = new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.6);

        assertEquals(0.0, monster.attackSwing01(), 0.0, "没攻击时不该有摆动");
        monster.tick(world, adjacent, 1.0 / 60.0);
        assertTrue(monster.attackSwing01() >= 0.0 && monster.attackSwing01() <= 1.0,
                "摆动强度必须夹在 0..1");

        boolean sawPeak = false;
        int steps = (int) Math.ceil(MeleeMonster.ATTACK_SWING_SECONDS * 60) + 2;
        for (int i = 0; i < steps; i++) {
            monster.tick(world, adjacent, 1.0 / 60.0);
            sawPeak |= monster.attackSwing01() > 0.5;
        }
        assertTrue(sawPeak, "摆动必须真的摆到过一半以上，否则看不出是挥击");
        // 摆动窗口过完之后必须归零（剩下的冷却时间是"收回"，不该一直抖）
        assertEquals(0.0, monster.attackSwing01(), 0.0);
    }
}

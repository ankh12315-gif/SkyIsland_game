package com.skyisland.render.viewmodel;

import com.skyisland.item.ItemRegistry;
import com.skyisland.player.ItemStack;
import com.skyisland.world.block.BlockRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第一人称手持物（viewmodel）—— 纯几何与纯动画，不需要 GL 上下文。
 *
 * <p><b>这里最重要的是两条"布局护栏"而不是任何精度断言：</b>
 * 手持物是一个贴在屏幕上的 3D 物体，它有两种坏法，且两种都<u>不会</u>报错、
 * 只会让画面变得难用：
 * <ol>
 *   <li><b>遮住准星</b> —— 准星的全部意义是"指出屏幕正中心那一个点"。
 *       被自己的枪盖住，玩家就等于在凭感觉开枪，而这在试玩里会被记成
 *       "枪法不准"而不是"UI 挡住了";</li>
 *   <li><b>跑出右半屏</b> —— 第一人称手持物约定在右下，
 *       跑到左半屏会与快捷栏、生命条打架。</li>
 * </ol>
 * 这两条都由 {@link #theHeldItemNeverCoversTheCrosshairAndStaysOnTheRightHalf()} 在
 * <b>整个动画包络</b>上扫一遍断言（不是只看静止那一帧）——
 * 因为"ADS 收拢"和"后坐上扬"恰好是最容易把它推进屏幕中心的两个动作。
 */
class ViewmodelRendererTest {

    private static final int FLOATS_PER_VERTEX = 7;
    private static final int FLOATS_PER_BOX = 36 * FLOATS_PER_VERTEX;

    /**
     * 准星禁区的半边长（NDC）。
     *
     * <p>准星本身在 720p 下是 6×uiScale 像素的四条短臂，按 uiScale = 2 算约 12 像素，
     * 折算成 NDC 约 0.033；取 0.06 是给它留出两倍的余量 —— 余量不能太大，
     * 否则"枪管尖几乎顶到准星"这种观感问题会被判成通过。
     */
    private static final double CROSSHAIR_EXCLUSION = 0.06;

    private static final double ASPECT = 16.0 / 9.0;

    private static ViewmodelModel model(ViewmodelKind kind) {
        ViewmodelModel m = new ViewmodelModel();
        m.kind = kind;
        m.visible = true;
        m.timeSeconds = 0.0;
        return m;
    }

    private static int write(ViewmodelModel m, ViewmodelPose pose, float[] out) {
        return ViewmodelGeometry.write(out, 0, m, pose);
    }

    private static void settle(ViewmodelModel m, ViewmodelPose pose, int frames) {
        for (int i = 0; i < frames; i++) {
            pose.update(m, 1.0 / 60.0);
        }
    }

    // ============================================================ 跟随快捷栏

    @Test
    void theViewmodelFollowsTheSelectedHotbarSlot() {
        ViewmodelModel m = new ViewmodelModel();
        int stoneId = BlockRegistry.stone().runtimeId();
        m.apply(ItemStack.of(stoneId, 32), 4);

        assertEquals(4, m.slot, "槽位必须来自 inventory.selectedSlot()");
        assertEquals(ViewmodelKind.BLOCK, m.kind, "石头是方块物品 → BLOCK 形态");

        m.apply(ItemStack.of(stoneId, 32), 7);
        assertEquals(7, m.slot, "换槽之后模型必须跟着换");
    }

    @Test
    void switchingSlotsProducesAPopThatSettlesBackToRest() {
        ViewmodelModel m = model(ViewmodelKind.GUN);
        ViewmodelPose pose = new ViewmodelPose();

        m.slot = 0;
        pose.update(m, 1.0 / 60.0);
        assertEquals(0.0, pose.popDrop(), 1e-9, "第一帧只记录槽位，不该弹一下");

        m.slot = 3;
        pose.update(m, 1.0 / 60.0);
        assertTrue(pose.popDrop() < 0, "换槽必须有一个下沉（弹出感的前半段）");
        assertTrue(pose.popScale() > 1.0, "换槽时必须放大一点，才有弹出的味道");

        // 走完整个 POP_SECONDS 之后必须完全回位 —— 否则手持物会永久偏低
        settle(m, pose, (int) (ViewmodelPose.POP_SECONDS * 60) + 4);
        assertEquals(0.0, pose.popDrop(), 1e-9, "弹出结束后必须回到原位");
        assertEquals(1.0, pose.popScale(), 1e-9);
    }

    @Test
    void theBlockColorComesFromTheHeldBlock() {
        ViewmodelModel m = new ViewmodelModel();
        m.apply(ItemStack.of(BlockRegistry.stone().runtimeId(), 1), 0);

        assertEquals(BlockRegistry.stone().colorR(), m.colorR, 1e-6,
                "手里的方块颜色必须与世界里那个方块同色");
        assertEquals(BlockRegistry.stone().colorG(), m.colorG, 1e-6);
        assertEquals(BlockRegistry.stone().colorB(), m.colorB, 1e-6);
    }

    @Test
    void anEmptySlotIsAnEmptyHand() {
        ViewmodelModel m = new ViewmodelModel();
        m.apply(ItemStack.EMPTY, 0);
        assertEquals(ViewmodelKind.EMPTY, m.kind);

        m.apply(null, 0);
        assertEquals(ViewmodelKind.EMPTY, m.kind, "null 也必须视作空手，不允许 NPE");
    }

    // ============================================================ 三种形态

    @Test
    void gunBlockAndEmptyProduceThreeDifferentGeometries() {
        float[] gun = new float[ViewmodelGeometry.MAX_BOXES * FLOATS_PER_BOX];
        float[] block = new float[gun.length];
        float[] empty = new float[gun.length];

        ViewmodelPose p1 = new ViewmodelPose();
        ViewmodelPose p2 = new ViewmodelPose();
        ViewmodelPose p3 = new ViewmodelPose();

        ViewmodelModel mg = model(ViewmodelKind.GUN);
        ViewmodelModel mb = model(ViewmodelKind.BLOCK);
        ViewmodelModel me = model(ViewmodelKind.EMPTY);
        p1.update(mg, 1.0 / 60.0);
        p2.update(mb, 1.0 / 60.0);
        p3.update(me, 1.0 / 60.0);

        int gunFloats = write(mg, p1, gun);
        int blockFloats = write(mb, p2, block);
        int emptyFloats = write(me, p3, empty);

        assertEquals(ViewmodelGeometry.partCount(ViewmodelKind.GUN) * FLOATS_PER_BOX, gunFloats);
        assertEquals(ViewmodelGeometry.partCount(ViewmodelKind.BLOCK) * FLOATS_PER_BOX, blockFloats);
        assertEquals(ViewmodelGeometry.partCount(ViewmodelKind.EMPTY) * FLOATS_PER_BOX, emptyFloats);

        assertNotEquals(gunFloats, blockFloats, "枪与方块的几何不能一样");
        // 方块与空手都是 2 个盒体（一坨 + 一只手 vs 手掌 + 手指），
        // 数量相同但形状与配色必须不同 —— 因此这里比内容而不是比个数
        assertTrue(!java.util.Arrays.equals(block, empty),
                "方块与空手的顶点不能一致（否则「空手」看起来是「举着一块空气」）");

        assertTrue(ViewmodelGeometry.partCount(ViewmodelKind.GUN) >= 4,
                "枪至少要有枪身/枪管/握把/手四个部件，否则读不出是一把枪");
    }

    @Test
    void theKindIsChosenFromTheItem() {
        assertEquals(ViewmodelKind.GUN, ViewmodelKind.of(ItemRegistry.pistol()));
        assertEquals(ViewmodelKind.BLOCK,
                ViewmodelKind.of(ItemRegistry.byRuntimeId(BlockRegistry.stone().runtimeId())));
        assertEquals(ViewmodelKind.EMPTY, ViewmodelKind.of(null));
    }

    // ============================================================ 手枪 vs SMG 轮廓（v2 §4.2 第①项）

    /**
     * SMG 的轮廓必须与手枪<b>明显不同</b>（v2 §4.2 第①项）。
     *
     * <p>判据：同一个 {@link ViewmodelKind#GUN} 形态下，顶点数组不得相等。
     * 它拦掉的正是 v2 §4.2 点名禁止的那种实现 —— "逻辑上是 SMG，右手仍是一模一样的手枪模型"。
     */
    @Test
    void thePistolAndTheSmgHaveDifferentViewmodelSilhouettes() {
        ViewmodelModel pistol = gunModel("pistol");
        ViewmodelModel smg = gunModel("smg");
        assertEquals("pistol", pistol.gunViewmodelId);
        assertEquals("smg", smg.gunViewmodelId);

        float[] outPistol = new float[ViewmodelGeometry.MAX_BOXES * FLOATS_PER_BOX];
        float[] outSmg = new float[outPistol.length];

        ViewmodelPose p1 = new ViewmodelPose();
        ViewmodelPose p2 = new ViewmodelPose();
        p1.update(pistol, 0);
        p2.update(smg, 0);

        int pistolFloats = write(pistol, p1, outPistol);
        int smgFloats = write(smg, p2, outSmg);

        assertTrue(pistolFloats > 0 && smgFloats > 0, "两把枪都必须画出东西");
        assertTrue(!java.util.Arrays.equals(outPistol, outSmg),
                "两把枪的 Viewmodel 顶点完全一致 —— 玩家分不出自己拿的是哪把枪"
                        + "（v2 §4.2 明令禁止）");
    }

    /** {@code apply} 必须把枪的 presentation().viewmodelId() 记下来，非枪置 null。 */
    @Test
    void applyCarriesTheGunViewmodelKeyAndClearsItForNonGuns() {
        ViewmodelModel m = new ViewmodelModel();
        m.apply(ItemStack.of(ItemRegistry.smg().runtimeId(), 1), 1);
        assertEquals(ViewmodelKind.GUN, m.kind);
        assertEquals(ItemRegistry.SMG_VIEWMODEL_ID, m.gunViewmodelId,
                "枪械轮廓键必须来自 presentation().viewmodelId()（表现资源统一由它指路）");

        m.apply(ItemStack.of(BlockRegistry.stone().runtimeId(), 1), 0);
        assertEquals(ViewmodelKind.BLOCK, m.kind);
        assertNull(m.gunViewmodelId, "换成方块后必须清掉枪械轮廓键，否则会留下陈旧的键");

        m.apply(ItemStack.EMPTY, 0);
        assertNull(m.gunViewmodelId, "空手同样不得残留枪械轮廓键");
    }

    /**
     * SMG 轮廓同样必须通过两条布局护栏（右半屏 + 不盖准星），且在整个动画包络上扫描。
     *
     * <p>这是"轮廓不是随便放大就行"的可执行约束：SMG 更长更宽的剪影最容易在 ADS
     * 收拢时越过屏幕中线、或长枪管顶进准星。
     */
    @Test
    void theSmgSilhouetteStaysOnTheRightHalfAndClearOfTheCrosshair() {
        float[] out = new float[ViewmodelGeometry.MAX_BOXES * FLOATS_PER_BOX];
        float[] ndc = new float[2];

        for (boolean aiming : new boolean[]{false, true}) {
            for (boolean reloading : new boolean[]{false, true}) {
                for (double reloadProgress : new double[]{0.0, 0.5, 1.0}) {
                    for (double move : new double[]{0.0, 1.0}) {
                        for (double time : new double[]{0.0, 0.17, 0.41, 0.63}) {
                            ViewmodelModel m = gunModel("smg");
                            m.aiming = aiming;
                            m.reloading = reloading;
                            m.reloadProgress01 = reloadProgress;
                            m.moveSpeed01 = move;
                            m.timeSeconds = time;
                            m.shotCount = 1;          // 最坏情况：正在后坐 + 枪口闪光
                            m.colorR = 0.5f;
                            m.colorG = 0.5f;
                            m.colorB = 0.5f;

                            ViewmodelPose pose = new ViewmodelPose();
                            pose.update(m, 0.0);
                            settle(m, pose, 60);

                            int floats = write(m, pose, out);
                            assertTrue(floats > 0);
                            for (int i = 0; i < floats; i += FLOATS_PER_VERTEX) {
                                ViewmodelGeometry.toNdc(out[i], out[i + 1], out[i + 2], ASPECT, ndc);
                                String where = "SMG aim=" + aiming + " reload=" + reloadProgress
                                        + " move=" + move + " t=" + time;
                                assertTrue(ndc[0] > 0.0,
                                        "SMG 必须留在右半屏（" + where + " 处 NDC x = " + ndc[0] + "）");
                                boolean inCrosshairZone =
                                        Math.abs(ndc[0]) < CROSSHAIR_EXCLUSION
                                                && Math.abs(ndc[1]) < CROSSHAIR_EXCLUSION;
                                assertTrue(!inCrosshairZone,
                                        "SMG 不得盖住准星（" + where + " 处 NDC = ("
                                                + ndc[0] + ", " + ndc[1] + ")）");
                            }
                        }
                    }
                }
            }
        }
    }

    /** 造一个"手持指定枪"的模型（走 {@code apply}，因此键来自 presentation）。 */
    private static ViewmodelModel gunModel(String viewmodelId) {
        if ("smg".equals(viewmodelId)) {
            return model(ViewmodelKind.GUN, ItemRegistry.smg().runtimeId(), ItemRegistry.SMG_VIEWMODEL_ID);
        }
        return model(ViewmodelKind.GUN, ItemRegistry.pistol().runtimeId(),
                ItemRegistry.PISTOL_VIEWMODEL_ID);
    }

    private static ViewmodelModel model(ViewmodelKind kind, int itemRuntimeId, String gunViewmodelId) {
        ViewmodelModel m = new ViewmodelModel();
        m.apply(ItemStack.of(itemRuntimeId, 1), 0);
        m.kind = kind;
        m.gunViewmodelId = gunViewmodelId;
        m.visible = true;
        m.timeSeconds = 0.0;
        return m;
    }

    // ============================================================ 布局护栏

    @Test
    void theHeldItemNeverCoversTheCrosshairAndStaysOnTheRightHalf() {
        float[] out = new float[ViewmodelGeometry.MAX_BOXES * FLOATS_PER_BOX];
        float[] ndc = new float[2];

        for (ViewmodelKind kind : ViewmodelKind.values()) {
            for (boolean aiming : new boolean[]{false, true}) {
                for (boolean mining : new boolean[]{false, true}) {
                    for (boolean reloading : new boolean[]{false, true}) {
                        for (double reloadProgress : new double[]{0.0, 0.5, 1.0}) {
                            for (double move : new double[]{0.0, 1.0}) {
                                for (double time : new double[]{0.0, 0.17, 0.41, 0.63}) {
                                    ViewmodelModel m = model(kind);
                                    m.aiming = aiming;
                                    m.mining = mining;
                                    m.reloading = reloading;
                                    m.reloadProgress01 = reloadProgress;
                                    m.moveSpeed01 = move;
                                    m.timeSeconds = time;
                                    m.shotCount = 1;          // 最坏情况：正在后坐
                                    m.colorR = 0.5f;
                                    m.colorG = 0.5f;
                                    m.colorB = 0.5f;

                                    ViewmodelPose pose = new ViewmodelPose();
                                    pose.update(m, 0.0);       // 首帧即取"刚开火"的极值
                                    // ADS 需要几帧才收敛，取收敛后的状态
                                    settle(m, pose, 60);

                                    int floats = write(m, pose, out);
                                    assertTrue(floats > 0);
                                    for (int i = 0; i < floats; i += FLOATS_PER_VERTEX) {
                                        ViewmodelGeometry.toNdc(out[i], out[i + 1], out[i + 2],
                                                ASPECT, ndc);
                                        String where = kind + " aim=" + aiming
                                                + " mining=" + mining + " reload=" + reloadProgress
                                                + " move=" + move + " t=" + time;
                                        assertTrue(ndc[0] > 0.0,
                                                "手持物必须留在右半屏（" + where + " 处 NDC x = "
                                                        + ndc[0] + "）");
                                        boolean inCrosshairZone =
                                                Math.abs(ndc[0]) < CROSSHAIR_EXCLUSION
                                                        && Math.abs(ndc[1]) < CROSSHAIR_EXCLUSION;
                                        assertTrue(!inCrosshairZone,
                                                "手持物不得盖住准星（" + where + " 处 NDC = ("
                                                        + ndc[0] + ", " + ndc[1] + ")）—— "
                                                        + "准星指出的是屏幕正中心那一个点，"
                                                        + "被自己的枪盖住等于瞄准了个寂寞");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void theHeldItemStaysClearOfTheBottomHotbarBand() {
        // 快捷栏占屏幕底部约 12%（720p 下 20×uiScale 的槽 + 10×uiScale 的下边距）。
        // 手持物压在上面会把"我在按哪个槽"这件事糊掉。
        float[] out = new float[ViewmodelGeometry.MAX_BOXES * FLOATS_PER_BOX];
        float[] ndc = new float[2];

        for (ViewmodelKind kind : ViewmodelKind.values()) {
            ViewmodelModel m = model(kind);
            m.moveSpeed01 = 1.0;
            ViewmodelPose pose = new ViewmodelPose();
            settle(m, pose, 60);
            int floats = write(m, pose, out);
            for (int i = 0; i < floats; i += FLOATS_PER_VERTEX) {
                ViewmodelGeometry.toNdc(out[i], out[i + 1], out[i + 2], ASPECT, ndc);
                assertTrue(ndc[1] > -0.92,
                        kind + " 的底部伸进了快捷栏带（NDC y = " + ndc[1] + "）");
            }
        }
    }

    // ============================================================ 动作

    @Test
    void aimingPullsTheModelTowardTheScreenCenter() {
        ViewmodelModel m = model(ViewmodelKind.GUN);
        ViewmodelPose pose = new ViewmodelPose();
        settle(m, pose, 60);
        double hipX = pose.anchorX();
        double hipZ = pose.anchorZ();

        m.aiming = true;
        settle(m, pose, 120);
        double adsX = pose.anchorX();
        double adsZ = pose.anchorZ();

        assertEquals(1.0, pose.aim01(), 1e-3, "ADS 必须完全收敛");
        assertTrue(adsX < hipX && adsX >= 0,
                "ADS 时手持物必须向屏幕中心收，但仍留在右半屏（hip " + hipX + " → ads " + adsX + "）");
        assertTrue(adsZ < hipZ,
                "ADS 时手持物应当略远一点（于是看起来更小、更不挡视线）");
    }

    @Test
    void firingKicksTheModelBackAndAddsAMuzzleFlash() {
        ViewmodelModel m = model(ViewmodelKind.GUN);
        ViewmodelPose pose = new ViewmodelPose();
        settle(m, pose, 60);

        float[] idle = new float[ViewmodelGeometry.MAX_BOXES * FLOATS_PER_BOX];
        int idleFloats = write(m, pose, idle);
        assertEquals(0.0, pose.recoil01(), 1e-9, "没开火时没有后坐");

        m.shotCount++;                      // 逻辑层打了一发
        pose.update(m, 1.0 / 60.0);
        assertTrue(pose.recoil01() > 0.5, "开火瞬间必须有一个明显的后坐");

        float[] firing = new float[idle.length];
        int firingFloats = write(m, pose, firing);
        assertEquals(idleFloats + FLOATS_PER_BOX, firingFloats,
                "开火时必须多出一个枪口闪光的盒体");

        // 后坐必须衰减回零 —— 否则枪会永久上扬
        settle(m, pose, (int) (ViewmodelPose.RECOIL_SECONDS * 60) + 4);
        assertEquals(0.0, pose.recoil01(), 1e-9);
    }

    @Test
    void theMuzzleFlashIsNotDrawnAsABlackBoxWhenThereIsNoRecoil() {
        ViewmodelModel m = model(ViewmodelKind.GUN);
        ViewmodelPose pose = new ViewmodelPose();
        settle(m, pose, 120);

        float[] out = new float[ViewmodelGeometry.MAX_BOXES * FLOATS_PER_BOX];
        int floats = write(m, pose, out);
        for (int i = 0; i < floats; i += FLOATS_PER_VERTEX) {
            assertTrue(out[i + 6] > 0.0f,
                    "任何盒体的亮度都必须 > 0 —— voxel.frag 里 aColor.a 是亮度，"
                            + "亮度 0 会画出一块纯黑糊在枪口上");
        }
    }

    @Test
    void reloadingDipsTheModelDownAndBringsItBack() {
        ViewmodelModel m = model(ViewmodelKind.GUN);
        ViewmodelPose pose = new ViewmodelPose();

        m.reloading = true;
        m.reloadProgress01 = 0.0;
        pose.update(m, 1.0 / 60.0);
        assertEquals(0.0, pose.reloadDrop(), 1e-9, "刚按下换弹时还没沉下去");

        m.reloadProgress01 = 0.5;
        pose.update(m, 1.0 / 60.0);
        assertTrue(pose.reloadDrop() < 0, "换弹进行中必须下沉");
        assertTrue(Math.abs(pose.reloadRollRad()) > 0, "换弹时枪身要侧过去一点");

        m.reloadProgress01 = 1.0;
        pose.update(m, 1.0 / 60.0);
        assertEquals(0.0, pose.reloadDrop(), 1e-9, "换弹完成必须回到原位（否则枪会越换越低）");

        m.reloading = false;
        pose.update(m, 1.0 / 60.0);
        assertEquals(0.0, pose.reloadDrop(), 1e-9, "不换弹时完全没有下压");
    }

    @Test
    void miningSwingsAndStopsWhenReleased() {
        ViewmodelModel m = model(ViewmodelKind.BLOCK);
        ViewmodelPose pose = new ViewmodelPose();

        m.mining = true;
        settle(m, pose, 30);
        double amplitude = 0;
        for (int i = 0; i < 60; i++) {
            pose.update(m, 1.0 / 60.0);
            amplitude = Math.max(amplitude, Math.abs(pose.swingAngleRad()));
        }
        assertTrue(amplitude > 0.2, "挖掘时必须摆出可见的角度（实测 " + amplitude + "）");

        m.mining = false;
        settle(m, pose, 60);
        assertEquals(0.0, pose.swingAngleRad(), 1e-3, "松开左键后摆动必须收住");
    }

    @Test
    void walkingBobsTheModelAndStandingStillDoesNot() {
        ViewmodelModel m = model(ViewmodelKind.GUN);
        ViewmodelPose standing = new ViewmodelPose();
        m.moveSpeed01 = 0.0;
        settle(m, standing, 60);
        assertEquals(0.0, standing.bobX(), 1e-9, "站住不动时没有 bob");
        assertEquals(0.0, standing.bobY(), 1e-9);

        m.moveSpeed01 = 1.0;
        ViewmodelPose walking = new ViewmodelPose();
        settle(m, walking, 60);
        double amp = 0;
        for (int i = 0; i < 120; i++) {
            walking.update(m, 1.0 / 60.0);
            m.timeSeconds += 1.0 / 60.0;
            amp = Math.max(amp, Math.abs(walking.bobX()));
        }
        assertTrue(amp > 1e-3, "走动时必须产生 bob（实测 " + amp + "）");
    }

    @Test
    void anAbsurdDeltaTimeCannotLaunchTheAnimation() {
        // 切后台再回来时 dt 可能是好几秒。不夹住的话 aim/pop 会一步跳到极端值，
        // 表现为"切回窗口的瞬间枪在外面乱飞一帧"。
        ViewmodelModel m = model(ViewmodelKind.GUN);
        m.aiming = true;
        m.slot = 5;
        ViewmodelPose pose = new ViewmodelPose();
        pose.update(m, 1000.0);

        assertTrue(pose.aim01() <= 1.0 + 1e-9, "ADS 混合量必须夹在 0..1");
        assertTrue(pose.aim01() >= 0.0);
        assertTrue(pose.popDrop() <= 0.0, "下沉量恒为非正");
    }
}

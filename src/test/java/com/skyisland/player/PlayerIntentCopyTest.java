package com.skyisland.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlayerIntent} 的"复制并替换一个字段"系列方法的字段保真。
 *
 * <p><b>为什么这些方法需要专门的测试：</b>{@code PlayerIntent} 是<b>位置参数</b>的
 * 16 组件 record，而"复制并替换一个字段"只能靠手写一个完整的构造调用。
 * 一旦把两个相邻的 boolean 写反（例如 {@code savePressed} 与
 * {@code screenshotPressed}），编译器不会有任何意见，运行时表现为
 * "按 F5 结果截了图"——而且只在被替换过的那条路径上发生。
 *
 * <p><b>M2 起本测试做了三处加强（原来的写法有一个真实漏洞）：</b>
 * <ol>
 *   <li>原来 {@link #rich()} 把 8 个布尔<b>全部设为 {@code true}</b>，
 *       于是"两个布尔互换"这件事在断言里<b>根本不可见</b> —— 两边都是 true，
 *       比对必然通过。现在改成"相邻布尔两两取值相反"的位模式，
 *       再配合 {@link #assertUntouchedBooleans} 里的字面量期望值，
 *       相邻位置写反必然被抓到。</li>
 *   <li>原来没有任何测试覆盖 M2 新增的 {@link PlayerIntent#withRespawn()}。
 *       它和 {@code withLook} 是同一类风险（手抄 15 个组件），必须有同样的护栏。</li>
 *   <li><b>v2 §7.3 新增</b>：{@link PlayerIntent#withAttackPressed} —— 左键按下沿
 *       （SINGLE 半自动开火）与 {@code attackHeld}（电平）是<b>相邻</b>且<b>同键不同义</b>，
 *       是本文件最需要防"写反"的一对。位模式里刻意让二者取值相反。</li>
 * </ol>
 *
 * <p><b>为什么断言用字面量而不是"和源对象比"：</b>和源对象比只能证明"没变"，
 * 证明不了"没和邻居调换" —— 调换之后源与目标恰好都错成同一个样子。
 * 字面量期望值把"这一位应该是几"写死，才是真正的钉子。
 */
class PlayerIntentCopyTest {

    /**
     * 每个字段都取一个<b>在相邻位置上互不相同</b>的值：9 个布尔位模式
     * {@code T F T F T F T F T} 保证"相邻两个写反"能被字面量断言区分开。
     *
     * <p>其中最要紧的一对是 {@code attackHeld / attackPressed}：
     * 它们同为左键、语义相反（电平 vs 按下沿），写反在本项目里的症状是
     * "半自动枪按住连发 / 全自动枪点一下只打一发"。
     */
    private static PlayerIntent rich() {
        return new PlayerIntent(
                0.75f, -0.25f, true,
                33.5, -12.25,
                /* attackHeld        */ true,
                /* attackPressed     */ false,
                /* usePressed        */ true,
                /* useHeld           */ false,
                /* reloadPressed     */ true,
                /* respawnPressed    */ false,
                /* toggleDebug       */ true,
                /* savePressed       */ false,
                /* screenshot        */ true,
                7, 4);
    }

    /** 与 {@link #rich()} 相同的取值，但 {@code respawnPressed = false} —— 用来观察它被置真。 */
    private static PlayerIntent richWithoutRespawn() {
        PlayerIntent r = rich();
        return new PlayerIntent(r.moveForward(), r.moveStrafe(), r.jump(),
                r.lookDeltaX(), r.lookDeltaY(),
                r.attackHeld(), r.attackPressed(), r.usePressed(), r.useHeld(), r.reloadPressed(),
                false, r.toggleDebugPressed(), r.savePressed(), r.screenshotPressed(),
                r.hotbarScroll(), r.hotbarSlot());
    }

    @Test
    @DisplayName("withLook 只替换视角位移，其余 14 个字段逐位保真")
    void withLookPreservesEveryOtherComponent() {
        PlayerIntent src = rich();

        PlayerIntent out = src.withLook(101.5, -202.5);

        assertNotSame(src, out, "必须是副本，不得原地修改");
        assertEquals(101.5, out.lookDeltaX(), 1e-12);
        assertEquals(-202.5, out.lookDeltaY(), 1e-12);
        assertEquals(src.hotbarScroll(), out.hotbarScroll(), "滚轮不属于视角通道，不得被顺手改动");
        assertEquals(src.attackPressed(), out.attackPressed(),
                "复制视角不得把左键按下沿吃掉");
        assertEquals(src.respawnPressed(), out.respawnPressed());
        assertUntouched(src, out);
    }

    @Test
    @DisplayName("withScroll 只替换滚轮增量，其余 14 个字段逐位保真")
    void withScrollPreservesEveryOtherComponent() {
        PlayerIntent src = rich();

        PlayerIntent out = src.withScroll(-9);

        assertEquals(-9, out.hotbarScroll());
        assertEquals(src.lookDeltaX(), out.lookDeltaX(), 1e-12,
                "视角不属于滚轮通道，不得被顺势清零");
        assertEquals(src.lookDeltaY(), out.lookDeltaY(), 1e-12);
        assertEquals(src.attackPressed(), out.attackPressed(),
                "复制滚轮不得把左键按下沿吃掉");
        assertEquals(src.respawnPressed(), out.respawnPressed());
        assertUntouched(src, out);
    }

    @Test
    @DisplayName("连续替换视角与滚轮互不覆盖（帧级量分两个通道下发）")
    void withLookAndWithScrollCompose() {
        PlayerIntent out = rich().withLook(5, 6).withScroll(3);

        assertEquals(5, out.lookDeltaX(), 1e-12);
        assertEquals(6, out.lookDeltaY(), 1e-12);
        assertEquals(3, out.hotbarScroll());
        assertEquals(rich().attackPressed(), out.attackPressed());
        assertEquals(rich().respawnPressed(), out.respawnPressed());
        assertUntouched(rich(), out);
    }

    @Test
    @DisplayName("清零视角位移后 hasLook 为 false（多逻辑步复用的依据）")
    void zeroedLookReportsNoLook() {
        PlayerIntent out = rich().withLook(0, 0);

        assertTrue(rich().hasLook());
        assertFalse(out.hasLook());
    }

    // ============================================================ 离散帧级量通道
    //
    // 下面几个 with* 与 withLook / withScroll 是同一类风险：16 个位置组件手抄一遍。
    // 它们的意义在于把"一次性语义"（左键开火 / 右键放置 / R 换弹 / 数字键选槽）
    // 从逐逻辑步复用的 frameIntent 里搬进帧级通道
    // （见 FrameInputQuantitiesTest 与 SkyIslandGame#beginFrame）。
    // 搬错的症状分别是"点左键不开火""按右键不放置""按 R 不换弹"，而且只在真实窗口下才看得见。

    @Test
    @DisplayName("withAttackPressed 只替换左键按下沿，其余字段逐位保真（与 attackHeld 不得互换）")
    void withAttackPressedFlipsOnlyThatFlag() {
        // rich() 的 attackPressed = false，置真观察它单独变化
        PlayerIntent out = rich().withAttackPressed(true);

        assertTrue(out.attackPressed());
        assertTrue(out.attackHeld(),
                "按下沿与电平是同一个物理键的两个语义，不得互相覆盖（v2 §7.3）");
        assertOthersUntouched(rich(), out);
    }

    @Test
    @DisplayName("withAttackPressed 不得把 attackHeld 电平当成按下沿（相邻布尔防写反）")
    void withAttackPressedKeepsHeldDistinct() {
        // attackHeld=false 而 attackPressed=true：二者必须各自独立
        PlayerIntent src = rich().withAttackPressed(false);
        PlayerIntent out = src.withAttackPressed(true);

        assertTrue(out.attackPressed(), "按下沿应为真");
        assertTrue(out.attackHeld(), "电平仍应保留 rich() 的 attackHeld=true，不得被按下沿覆盖");
    }

    @Test
    @DisplayName("withUsePressed 只替换右键按下沿，其余字段逐位保真")
    void withUsePressedFlipsOnlyThatFlag() {
        // rich() 的 usePressed = true，改成 false 观察它单独变化
        PlayerIntent out = rich().withUsePressed(false);

        assertFalse(out.usePressed());
        assertFalse(out.useHeld(), "按下沿与电平是同一个物理键的两个语义，不得互相覆盖");
        assertOthersUntouched(rich(), out);
    }

    @Test
    @DisplayName("withReloadPressed 只替换换弹按下沿，其余字段逐位保真")
    void withReloadPressedFlipsOnlyThatFlag() {
        // rich() 的 reloadPressed = true，改成 false 观察它单独变化
        PlayerIntent out = rich().withReloadPressed(false);

        assertFalse(out.reloadPressed());
        assertOthersUntouched(rich(), out);
    }

    @Test
    @DisplayName("withHotbarSlot 只替换选槽，其余字段逐位保真")
    void withHotbarSlotReplacesOnlyThatField() {
        PlayerIntent out = rich().withHotbarSlot(6);

        assertEquals(6, out.hotbarSlot());
        assertEquals(7, out.hotbarScroll(), "选槽不得顺手改动滚轮增量");
        assertOthersUntouched(rich(), out);
    }

    @Test
    @DisplayName("beginFrame 里的那一串组合调用互不覆盖（视图 / 滚轮 / 三个按下沿 / 选槽六个通道）")
    void frameIntentCompositionKeepsEveryChannelSeparate() {
        PlayerIntent out = rich().withLook(0, 0).withScroll(0)
                .withUsePressed(false).withReloadPressed(false).withAttackPressed(false)
                .withHotbarSlot(-1);

        assertEquals(0, out.lookDeltaX(), 1e-12);
        assertEquals(0, out.lookDeltaY(), 1e-12);
        assertEquals(0, out.hotbarScroll());
        assertFalse(out.usePressed());
        assertFalse(out.reloadPressed());
        assertFalse(out.attackPressed(), "左键按下沿必须被清零，交由帧级通道一次性发放");
        assertEquals(-1, out.hotbarSlot());
        // 连续量必须原样保留 —— 这是"逐逻辑步复用 frameIntent"成立的前提
        assertEquals(0.75f, out.moveForward(), 1e-6);
        assertEquals(-0.25f, out.moveStrafe(), 1e-6);
        assertTrue(out.jump());
        assertTrue(out.attackHeld(), "左键电平（AUTO）不在帧级通道里，必须原样留着");
        assertFalse(out.useHeld(), "瞄准电平不在帧级通道里，必须原样留着");
    }

    @Test
    @DisplayName("withRespawn 把 respawnPressed 置真，且不碰其余 15 个字段")
    void withRespawnFlipsOnlyTheRespawnFlag() {
        PlayerIntent out = richWithoutRespawn().withRespawn();

        assertTrue(out.respawnPressed(), "withRespawn 的全部意义就是这一位");
        assertUntouched(richWithoutRespawn(), out);
    }

    @Test
    @DisplayName("withRespawn 对已经为真的意图是幂等的")
    void withRespawnIsIdempotent() {
        PlayerIntent once = richWithoutRespawn().withRespawn();
        PlayerIntent twice = once.withRespawn();

        assertTrue(twice.respawnPressed());
        assertEquals(once, twice, "重复注入强制重生不得叠加成别的状态");
    }

    /**
     * 逐字段比对"不该被碰"的 14 个组件。
     *
     * <p>字面量 + 源对象<b>双重</b>比对：字面量抓"和邻居调换"，源对象抓"被顺手改动"。
     * 少了任何一侧，就有一类错误是静默的。
     */
    private static void assertUntouched(PlayerIntent expected, PlayerIntent actual) {
        // ---- 与源对象一致 ----
        assertEquals(expected.moveForward(), actual.moveForward(), 1e-6);
        assertEquals(expected.moveStrafe(), actual.moveStrafe(), 1e-6);
        assertEquals(expected.jump(), actual.jump());
        assertEquals(expected.attackPressed(), actual.attackPressed());
        assertEquals(expected.usePressed(), actual.usePressed());
        assertEquals(expected.useHeld(), actual.useHeld());
        assertEquals(expected.reloadPressed(), actual.reloadPressed());
        assertEquals(expected.hotbarSlot(), actual.hotbarSlot());

        // ---- 额外的字面量钉子（相邻布尔写反时源对象比对会失效） ----
        assertEquals(0.75f, actual.moveForward(), 1e-6);
        assertEquals(-0.25f, actual.moveStrafe(), 1e-6);
        assertTrue(actual.jump());
        assertTrue(actual.attackHeld(), "attackHeld 不该被任何 with* 改动");
        assertFalse(actual.attackPressed(), "attackPressed 是按下沿通道，不得被电平通道覆盖");
        assertTrue(actual.usePressed(), "usePressed 是按下沿通道");
        assertFalse(actual.useHeld(), "useHeld 是瞄准电平通道，不得与 usePressed 互换");
        assertTrue(actual.reloadPressed());
        assertTrue(actual.toggleDebugPressed());
        assertFalse(actual.savePressed());
        assertTrue(actual.screenshotPressed());
        assertEquals(4, actual.hotbarSlot());
    }

    /**
     * 与 {@link #assertUntouched} 同源，但把"会被新 {@code with*} 有意改动"的位
     * （{@code attackPressed} / {@code usePressed} / {@code reloadPressed} / {@code hotbarSlot}）
     * 排除在外。
     *
     * <p>同样保留字面量钉子：源对象比对只能证明"没变"，证明不了"没和邻居调换"。
     */
    private static void assertOthersUntouched(PlayerIntent expected, PlayerIntent actual) {
        assertEquals(expected.moveForward(), actual.moveForward(), 1e-6);
        assertEquals(expected.moveStrafe(), actual.moveStrafe(), 1e-6);
        assertEquals(expected.jump(), actual.jump());
        assertEquals(expected.attackHeld(), actual.attackHeld());
        assertEquals(expected.useHeld(), actual.useHeld());
        assertEquals(expected.respawnPressed(), actual.respawnPressed());
        assertEquals(expected.toggleDebugPressed(), actual.toggleDebugPressed());
        assertEquals(expected.savePressed(), actual.savePressed());
        assertEquals(expected.screenshotPressed(), actual.screenshotPressed());
        assertEquals(expected.lookDeltaX(), actual.lookDeltaX(), 1e-12);
        assertEquals(expected.lookDeltaY(), actual.lookDeltaY(), 1e-12);

        assertTrue(actual.attackHeld(), "attackHeld 不该被任何 with* 改动");
        assertFalse(actual.useHeld(), "useHeld 是瞄准电平通道，不得与 usePressed 互换");
        assertFalse(actual.respawnPressed(), "F9 的边沿动作不得被吃掉");
        assertTrue(actual.toggleDebugPressed());
        assertFalse(actual.savePressed());
        assertTrue(actual.screenshotPressed());
    }
}

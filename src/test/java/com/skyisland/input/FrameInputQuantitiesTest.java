package com.skyisland.input;

import com.skyisland.player.PlayerIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帧级输入量的发放规则（M1.5 缺陷回归）。
 *
 * <p>本类守护的是两个方向相反、且都<u>只在真窗口里</u>才能观察到的缺陷：
 * 零逻辑步的帧不能丢位移，多逻辑步的帧不能重复施加位移。
 * 它们的历史是：M1 的门禁全绿，但真实鼠标视角在 120 Hz 下丢一半输入、
 * 在 40 FPS 下速度翻倍 —— 因为 M1 自测注入的是"逻辑步粒度"的意图，
 * 从构造上就绕开了"帧 ⟷ 逻辑步"的粒度错配。
 */
class FrameInputQuantitiesTest {

    private static final PlayerIntent BASE = PlayerIntent.moving(1f, 0f, false);

    @Test
    @DisplayName("一帧一个逻辑步：位移被完整施加一次")
    void singleStepFrameAppliesOnce() {
        FrameInputQuantities q = new FrameInputQuantities();
        q.beginFrame();
        q.accumulate(100, 0, 0);

        PlayerIntent out = q.apply(BASE);

        assertEquals(100, out.lookDeltaX(), 1e-12);
        assertEquals(1f, out.moveForward(), 1e-6, "其他字段必须原样保留");
        assertFalse(q.hasPending(), "施加过就不该再有待发放量");
    }

    @Test
    @DisplayName("一帧多个逻辑步：同一份位移只施加给第一个逻辑步")
    void multipleStepsInOneFrameApplyOnce() {
        FrameInputQuantities q = new FrameInputQuantities();
        q.beginFrame();
        q.accumulate(100, 0, 2);

        PlayerIntent first = q.apply(BASE);
        PlayerIntent second = q.apply(BASE);
        PlayerIntent third = q.apply(BASE);

        assertEquals(100, first.lookDeltaX(), 1e-12);
        assertEquals(2, first.hotbarScroll());
        assertEquals(0, second.lookDeltaX(), 1e-12, "第二个逻辑步不得重复施加同一份位移");
        assertEquals(0, second.hotbarScroll(), "第二个逻辑步不得重复切槽");
        assertEquals(0, third.lookDeltaX(), 1e-12);
        // 连续量不受影响：移动键按住就应该每个逻辑步都生效
        assertEquals(1f, second.moveForward(), 1e-6);
        assertEquals(1f, third.moveForward(), 1e-6);
    }

    @Test
    @DisplayName("一帧零个逻辑步：位移留到下一个真正执行的逻辑步，不丢")
    void frameWithoutLogicStepCarriesOver() {
        FrameInputQuantities q = new FrameInputQuantities();

        // 第 1 帧：有位移，但没有逻辑步（apply 从未被调用）
        q.beginFrame();
        q.accumulate(100, 0, 0);
        assertTrue(q.hasPending());

        // 第 2 帧：又有 7 像素位移，这一帧终于跑了逻辑步
        q.beginFrame();
        q.accumulate(7, 0, 0);
        PlayerIntent out = q.apply(BASE);

        assertEquals(107, out.lookDeltaX(), 1e-12, "两帧的位移都必须被保留");
        assertFalse(q.hasPending());
    }

    @Test
    @DisplayName("跨多帧零逻辑步也不会丢失（连续 5 帧无逻辑步）")
    void manyFramesWithoutLogicStepsAccumulate() {
        FrameInputQuantities q = new FrameInputQuantities();
        for (int i = 0; i < 5; i++) {
            q.beginFrame();
            q.accumulate(20, -4, 1);
        }
        PlayerIntent out = q.apply(BASE);

        assertEquals(100, out.lookDeltaX(), 1e-12);
        assertEquals(-20, out.lookDeltaY(), 1e-12);
        assertEquals(5, out.hotbarScroll());
    }

    @Test
    @DisplayName("已发放之后再积累的量属于下一帧，仍会被发放")
    void accumulationAfterApplicationBelongsToNextFrame() {
        FrameInputQuantities q = new FrameInputQuantities();
        q.beginFrame();
        q.accumulate(100, 0, 0);
        q.apply(BASE);

        // 同一帧内又来了位移（GKFW 回调与逻辑步同帧时才可能），它不得在本帧被用掉
        q.accumulate(50, 0, 0);
        assertEquals(0, q.apply(BASE).lookDeltaX(), 1e-12, "本帧已经发放过，不再追加");
        assertTrue(q.hasPending(), "新积累的量留给下一帧");

        q.beginFrame();
        assertEquals(50, q.apply(BASE).lookDeltaX(), 1e-12);
    }

    @Test
    @DisplayName("discard 会丢弃待发放量（进入菜单时防止回到游戏甩视角）")
    void discardDropsPending() {
        FrameInputQuantities q = new FrameInputQuantities();
        q.beginFrame();
        q.accumulate(500, -300, 3);
        assertTrue(q.hasPending());

        q.discard();

        assertFalse(q.hasPending());
        assertEquals(0, q.apply(BASE).lookDeltaX(), 1e-12);
        assertEquals(0, q.apply(BASE).hotbarScroll());
    }

    @Test
    @DisplayName("没有位移时也不改变意图（避免制造无意义的意图副本）")
    void emptyAccumulationLeavesIntentSemanticallyUnchanged() {
        FrameInputQuantities q = new FrameInputQuantities();
        q.beginFrame();

        PlayerIntent out = q.apply(BASE);

        assertEquals(BASE.moveForward(), out.moveForward(), 1e-6);
        assertEquals(0, out.lookDeltaX(), 1e-12);
        assertEquals(BASE.hotbarSlot(), out.hotbarSlot());
        assertFalse(out.hasLook());
    }

    @Test
    @DisplayName("beginFrame 不会清掉上一帧留下的待发放量（这是不丢输入的前提）")
    void beginFrameKeepsPending() {
        FrameInputQuantities q = new FrameInputQuantities();
        q.beginFrame();
        q.accumulate(100, 0, 0);

        q.beginFrame();

        assertTrue(q.hasPending(), "换帧不等于丢弃");
        assertEquals(100, q.pendingLookX(), 1e-12);
        assertEquals(100, q.apply(BASE).lookDeltaX(), 1e-12);
    }

    // ============================================================ M2 缺陷回归：离散帧级量
    //
    // 下面这四条守护的是"一次性语义"（右键放置 / R 换弹 / 数字键选槽）。
    // 它们曾经留在逐逻辑步复用的 frameIntent 里，于是同时具备两个毛病：
    // 零逻辑步的帧丢、多逻辑步的帧重复施加。
    // 现场数字：某次人工试玩 52 秒里 blocks_placed = 0 <b>且</b> placement_rejected = 0 ——
    // 连"被拒绝"都没发生过，说明点击根本没走到放置代码。

    @Test
    @DisplayName("右键按下沿：零逻辑步的帧不会把它丢掉（放置无反应的直接原因）")
    void usePressedSurvivesFrameWithoutLogicStep() {
        FrameInputQuantities q = new FrameInputQuantities();

        // 第 1 帧：玩家点了右键，但这一帧没轮到逻辑步
        q.beginFrame();
        q.accumulateDiscrete(true, false, -1);
        assertTrue(q.hasPending(), "没被取走就必须还在");

        // 第 2 帧：终于跑了逻辑步，这一下点击必须生效
        q.beginFrame();
        q.accumulateDiscrete(false, false, -1);
        PlayerIntent out = q.apply(BASE);

        assertTrue(out.usePressed(), "跨帧保留下来的点击必须发放");
        assertFalse(q.hasPending());
    }

    @Test
    @DisplayName("一帧多个逻辑步：一次点击只放置一次、一次换弹只换一次")
    void discreteEdgesApplyOnlyToFirstLogicStep() {
        FrameInputQuantities q = new FrameInputQuantities();
        q.beginFrame();
        q.accumulateDiscrete(true, true, -1);

        PlayerIntent first = q.apply(BASE);
        PlayerIntent second = q.apply(BASE);

        assertTrue(first.usePressed(), "第一个逻辑步拿到点击");
        assertTrue(first.reloadPressed(), "第一个逻辑步拿到换弹");
        assertFalse(second.usePressed(), "第二个逻辑步不得再放一次");
        assertFalse(second.reloadPressed(), "第二个逻辑步不得再换一次");
        // 连续量（移动/跳跃/按住攻击）不受本机制影响，仍逐逻辑步生效。
        // 注意：useHeld（右键电平 = 瞄准）不经过本类，它由 frameIntent 逐逻辑步复用，
        // 这里刻意不断言它 —— 本类的职责边界就是"帧级量"，越界断言会掩盖真正的问题。
        assertEquals(1f, second.moveForward(), 1e-6);
    }

    @Test
    @DisplayName("数字键选槽同样走帧级通道（此前按 1/2/3 时常无反应）")
    void hotbarSlotUsesFrameChannel() {
        FrameInputQuantities q = new FrameInputQuantities();
        q.beginFrame();
        q.accumulateDiscrete(false, false, 4);

        PlayerIntent out = q.apply(BASE);
        assertEquals(4, out.hotbarSlot());
        assertEquals(-1, q.apply(BASE).hotbarSlot(), "第二个逻辑步不得再选一次");
    }

    @Test
    @DisplayName("同一帧内连点两次右键：合并为一次（不重复放置）")
    void repeatedClicksInOneFrameCollapse() {
        FrameInputQuantities q = new FrameInputQuantities();
        q.beginFrame();
        q.accumulateDiscrete(true, false, -1);
        q.accumulateDiscrete(true, false, -1);

        assertTrue(q.apply(BASE).usePressed());
        assertFalse(q.apply(BASE).usePressed());
    }

    @Test
    @DisplayName("discard 会丢弃待发放的离散量（菜单里点的那几下不该带进游戏变成放置）")
    void discardDropsPendingDiscreteEdges() {
        FrameInputQuantities q = new FrameInputQuantities();
        q.beginFrame();
        q.accumulateDiscrete(true, true, 2);
        assertTrue(q.hasPending());

        q.discard();

        assertFalse(q.hasPending());
        PlayerIntent out = q.apply(BASE);
        assertFalse(out.usePressed());
        assertFalse(out.reloadPressed());
        assertEquals(-1, out.hotbarSlot());
    }
}

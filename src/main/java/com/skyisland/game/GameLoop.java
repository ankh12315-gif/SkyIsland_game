package com.skyisland.game;

import com.skyisland.util.Log;

/**
 * 固定逻辑步长主循环（TECH_DESIGN_v0.1 §C）。
 *
 * <p><b>本类刻意不 import 任何 LWJGL / OpenGL 类</b> —— 它只负责「时间推进」，
 * 不负责「事件轮询」与「绘制」。轮询与绘制由调用方通过 {@link FrameCallbacks}
 * 注入。这样做的收益：
 * <ul>
 *   <li>逻辑层可在无 GL 环境（单元测试）中运行（TECH_DESIGN §B.2 第 5 条）</li>
 *   <li>时间推进逻辑可被独立测试，而它的正确性（防死亡螺旋）最需要测试</li>
 * </ul>
 *
 * <p><b>方案：固定逻辑步长 + 渲染插值。</b>逻辑恒以 60 Hz 推进，与渲染帧率解耦。
 * 变步长会让物理与 AI 结果依赖帧率（同样的输入在不同帧率下跳跃高度不同），
 * 破坏可复现性 —— 而可复现性是 PRD 14.3 的联机预留前提。
 */
public final class GameLoop {

    /** 固定逻辑步长：60 Hz。 */
    public static final double FIXED_DT = 1.0 / 60.0;

    /** 单帧最多补的逻辑步数（死亡螺旋保护）。 */
    public static final int MAX_STEPS_PER_FRAME = 5;

    /** 单帧最大计入的帧间隔。断点调试 / 系统挂起后回填一大段逻辑毫无意义。 */
    public static final double MAX_FRAME_DELTA = 0.25;

    /**
     * 帧率上限。0 = 不限速（尽可能快，用于性能测量）。
     *
     * <p>M0 采用 0：门禁要求「FPS 显著高于 60」，必须能观察到循环的真实上限。
     * VSync 关闭时 {@code glfwSwapBuffers} 不阻塞，因此不限速即可测出真实吞吐。
     */
    private final double frameRateCap;

    private final FrameStats stats = new FrameStats();

    private double accumulator = 0.0;
    private long lastFrameNanos = 0;

    /** 每个逻辑步实际执行的次数（用于观察 TPS 是否达标）。 */
    private long totalLogicSteps = 0;

    public GameLoop(double frameRateCapFps) {
        this.frameRateCap = frameRateCapFps;
    }

    public FrameStats stats() {
        return stats;
    }

    /**
     * 主循环。返回时表示 {@code callbacks.shouldClose()} 已为 true 且已完成收尾。
     *
     * <p>顺序严格对应 TECH_DESIGN §C.1 的伪代码，不得调换：
     * 轮询 → 帧间隔保护 → 累加 → 固定步逻辑 → 插值因子 → 渲染 → 提交 → 统计。
     */
    public void run(FrameCallbacks callbacks) {
        lastFrameNanos = System.nanoTime();
        accumulator = 0.0;

        while (!callbacks.shouldClose()) {
            long frameStartNanos = System.nanoTime();
            double rawDelta = (frameStartNanos - lastFrameNanos) / 1e9;
            lastFrameNanos = frameStartNanos;

            // ---- 1) 帧间隔保护 ----
            // ★ 统计用「原始间隔」，逻辑推进用「钳制后间隔」。
            //   若把钳制值交给统计，max 会被压成上限值（如 250 ms），
            //   把一个 1.4 秒的真实停顿伪装成"刚好触顶" —— 这正是 M0 首轮掩盖真相的方式。
            double frameDelta = rawDelta;
            if (rawDelta > MAX_FRAME_DELTA) {
                Log.warn("帧间隔被钳制: %.3f s （超过上限 %.3f s，多半由断点调试/系统挂起/窗口拖动导致）",
                        rawDelta, MAX_FRAME_DELTA);
                frameDelta = MAX_FRAME_DELTA;
                accumulator = 0.0;   // 明确丢弃欠账，避免下一帧继续追
                stats.recordClamp();
            }

            // ---- 2) 输入：轮询事件并冻结本帧意图 ----
            callbacks.pollEvents();
            callbacks.beginFrame();

            // ---- 3) 固定步长逻辑推进 ----
            accumulator += frameDelta;
            int steps = 0;
            while (accumulator >= FIXED_DT && steps < MAX_STEPS_PER_FRAME) {
                callbacks.stepLogic(FIXED_DT);
                stats.recordLogicStep();
                totalLogicSteps++;
                accumulator -= FIXED_DT;
                steps++;
            }
            if (steps == MAX_STEPS_PER_FRAME && accumulator >= FIXED_DT) {
                // 单帧逻辑耗时过长，欠账已无法在合理步数内追平 —— 丢弃余量
                stats.recordOverrun();
                accumulator = 0.0;
            }

            // ---- 4) 渲染插值因子 ----
            double alpha = accumulator / FIXED_DT;   // ∈ [0, 1)
            if (alpha < 0) {
                alpha = 0;
            }
            if (alpha >= 1) {
                alpha = 0.999999;
            }

            // ---- 5) 渲染（只读状态） ----
            callbacks.render(alpha);

            // ---- 6) 呈现与统计 ----
            callbacks.endFrame();
            stats.recordFrame(rawDelta);   // 原始间隔，不钳制

            // ---- 7) 限速（可选） ----
            if (frameRateCap > 0) {
                sleepToTarget(frameStartNanos);
            }
        }
    }

    /**
     * 空闲策略（TECH_DESIGN §C.5）：先把大部分剩余时间 sleep 掉，
     * 再用短自旋吃掉剩余的亚毫秒误差。
     *
     * <p>纯自旋会吃满一个物理核（笔记本散热与续航都受影响）；
     * 纯 {@code Thread.sleep} 精度约 1–15 ms，不稳定。
     */
    private void sleepToTarget(long frameStartNanos) {
        long targetNanos = frameStartNanos + (long) (1e9 / frameRateCap);
        long remaining = targetNanos - System.nanoTime();
        if (remaining > 2_000_000L) {                 // > 2 ms 才值得 sleep
            try {
                Thread.sleep((remaining - 1_000_000L) / 1_000_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        while (System.nanoTime() < targetNanos) {
            Thread.onSpinWait();
        }
    }

    public long totalLogicSteps() {
        return totalLogicSteps;
    }

    /** 调用方实现的帧钩子。 */
    public interface FrameCallbacks {

        /** 窗口是否应关闭。 */
        boolean shouldClose();

        /** 轮询输入事件（GLFW 必须每帧调用，否则窗口会被判定为无响应）。 */
        void pollEvents();

        /** 帧开始：冻结本帧的意图快照。 */
        void beginFrame();

        /**
         * 推进一个固定步长的游戏逻辑。
         *
         * @param fixedDt 恒为 {@link #FIXED_DT}，<b>不得</b>使用帧间隔
         */
        void stepLogic(double fixedDt);

        /**
         * 渲染一帧。<b>只允许读取游戏状态</b>，不得修改（TECH_DESIGN §B.2）。
         *
         * @param alpha 插值因子 ∈ [0,1)
         */
        void render(double alpha);

        /** 帧结束：提交缓冲（swap buffers）。 */
        void endFrame();
    }
}

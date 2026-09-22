package com.skyisland.render.fx;

import com.skyisland.render.fx.CombatFxModel.Particle;
import com.skyisland.render.fx.CombatFxModel.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CombatFxModel} 的单元测试（PRD_v0.3.2 §5.2 破坏特效 / §5.4.3 击中反馈与弹道表现）。
 *
 * <h2>为什么不测 {@code CombatFxRenderer}</h2>
 * 本工程的测试运行在<b>无窗口</b>环境（CI 与本地都跑 {@code mvn test}），
 * 拿不到 GL 上下文 —— 任何 {@code glGenBuffers} 之类的调用都会直接抛
 * {@code IllegalStateException}（LWJGL 无法解析函数指针）。因此
 * "顶点确实被提交给 GPU"这一条只能靠带窗口的自测脚本（{@code *SelfTest}）举证，
 * 与 {@code CrackOverlayTest} 的取舍完全一致。
 * 这也正是本工程把"数据/几何"与"GL 调用"分层的原因：
 * 特效最容易错的部分（数量确定性、寿命语义、容量上限、状态推进）全在这一侧，
 * 于是它们可以在没有窗口的机器上被完整覆盖。
 *
 * <p>本测试类只覆盖 {@link CombatFxModel}。
 */
class CombatFxModelTest {

    /** 浮点比较容差：位置/速度都是格与格/秒量级，1e-9 远小于观感差异。 */
    private static final double EPS = 1e-9;

    /** 固定的确定性种子，取一个高位也有 1 的值，避免"只用低 32 位"的实现恰好蒙对。 */
    private static final long SEED = 0x0123_4567_89AB_CDEFL;

    // ============================================================ 1. 破坏粒子数量

    @Test
    @DisplayName("破坏粒子数恒在 [8,12] 闭区间，且 lastBreakParticleCount 与实际生成数一致")
    void breakParticleCountAlwaysInRange() {
        for (long seed = 0; seed < 200; seed++) {
            CombatFxModel model = new CombatFxModel();
            model.spawnBlockBreak(4, 70, -9, 0.3f, 0.5f, 0.2f, seed);
            int count = model.particleCount();
            assertTrue(count >= CombatFxModel.BREAK_PARTICLES_MIN
                            && count <= CombatFxModel.BREAK_PARTICLES_MAX,
                    "seed=" + seed + " 生成的粒子数 " + count + " 越出 [8,12]");
            assertEquals(count, model.lastBreakParticleCount(), "seed=" + seed + " 的读数与实际不符");
            assertEquals(count, model.totalBreakParticles(), "seed=" + seed + " 的累计数应等于首次生成数");
        }
    }

    @Test
    @DisplayName("同 seed 完全复现：粒子数与每个粒子的位置/初速度逐位相同")
    void sameSeedReproducesBitForBit() {
        CombatFxModel first = new CombatFxModel();
        CombatFxModel second = new CombatFxModel();
        first.spawnBlockBreak(3, 70, -8, 0.2f, 0.6f, 0.3f, SEED);
        second.spawnBlockBreak(3, 70, -8, 0.2f, 0.6f, 0.3f, SEED);

        assertEquals(first.particleCount(), second.particleCount(),
                "同 seed 得到不同的粒子数 —— 表现不可复现");
        assertEquals(first.particles().size(), second.particles().size());

        List<Particle> a = first.particles();
        List<Particle> b = second.particles();
        for (int i = 0; i < a.size(); i++) {
            Particle pa = a.get(i);
            Particle pb = b.get(i);
            String where = "同 seed 的第 " + i + " 个粒子";
            // 逐位（doubleToLongBits）而不是容差比较：这里要断言的是"确定性"，
            // 用 1e-6 的容差会把"每次略有不同"放过去，而那种抖动正是要防的回归。
            assertSameBits(pa.x(), pb.x(), where + " 的 x");
            assertSameBits(pa.y(), pb.y(), where + " 的 y");
            assertSameBits(pa.z(), pb.z(), where + " 的 z");
            assertSameBits(pa.vx(), pb.vx(), where + " 的 vx");
            assertSameBits(pa.vy(), pb.vy(), where + " 的 vy");
            assertSameBits(pa.vz(), pb.vz(), where + " 的 vz");
            assertSameBits(pa.size(), pb.size(), where + " 的 size");
            assertSameBits(pa.life(), pb.life(), where + " 的 life");
        }

        // 反过来再确认一次：同一实例重复调用同一 seed，也该得到同样的数量
        CombatFxModel repeat = new CombatFxModel();
        repeat.spawnBlockBreak(3, 70, -8, 0.2f, 0.6f, 0.3f, SEED);
        assertEquals(first.particleCount(), repeat.particleCount());
    }

    @Test
    @DisplayName("数量不是常数：扫一批 seed 能取到区间内的多个值，且两端都能取到")
    void countVariesWithSeed() {
        Set<Integer> distinct = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            CombatFxModel model = new CombatFxModel();
            model.spawnBlockBreak(0, 0, 0, 1f, 1f, 1f, seed);
            distinct.add(model.particleCount());
        }
        assertTrue(distinct.size() >= 2,
                "40 个 seed 只取到 " + distinct + " —— 数量退化成了常数，PRD 的\"8–12 个随机\"没落实");
        // 更强的一条：两端都取得到，说明映射真的铺满了整个闭区间，
        // 而不是"实现里写了区间但实际永远返回中值"。
        assertTrue(distinct.contains(CombatFxModel.BREAK_PARTICLES_MIN),
                "40 个 seed 都没取到下限 " + CombatFxModel.BREAK_PARTICLES_MIN + "，实际集合 " + distinct);
        assertTrue(distinct.contains(CombatFxModel.BREAK_PARTICLES_MAX),
                "40 个 seed 都没取到上限 " + CombatFxModel.BREAK_PARTICLES_MAX + "，实际集合 " + distinct);
    }

    @Test
    @DisplayName("破坏粒子都在方块中心附近（不会飘到相邻方块里）")
    void breakParticlesStayNearBlockCentre() {
        CombatFxModel model = new CombatFxModel();
        model.spawnBlockBreak(10, 64, -3, 0.6f, 0.6f, 0.6f, SEED);
        for (Particle p : model.particles()) {
            assertWithin(p.x(), 10, "x");
            assertWithin(p.y(), 64, "y");
            assertWithin(p.z(), -3, "z");
        }
    }

    /** 断言坐标落在 [block−0.6, block+1.6] 内 —— 这是任务书钉死的"方块中心附近"口径。 */
    private static void assertWithin(double value, int block, String axis) {
        assertTrue(value >= block - 0.6 && value <= block + 1.6,
                axis + " = " + value + " 不在方块 " + block + " 的 [−0.6, +1.6] 范围内");
    }

    // ============================================================ 2. 曳光

    @Test
    @DisplayName("曳光存活 0.05 秒：0.049 秒时仍在，越过 0.05 秒后消失")
    void tracerLivesExactlyTracerSeconds() {
        CombatFxModel model = new CombatFxModel();
        model.spawnTracer(0.5, 65.6, 0.5, 12.4, 65.1, 3.2);
        assertEquals(1, model.tracerCount());
        assertEquals(CombatFxModel.TRACER_SECONDS, model.tracers().get(0).life(), EPS,
                "曳光初始寿命必须等于 PRD 规定的 0.05 秒");

        model.tick(0.049);
        assertEquals(1, model.tracerCount(), "0.049 秒还没到 0.05 秒，曳光不该消失");
        assertTrue(model.tracers().get(0).life() > 0, "剩余寿命必须为正");

        model.tick(0.002);
        assertEquals(0, model.tracerCount(),
                "累计 0.051 秒已超过 0.05 秒，曳光必须被移除（life <= 0 即除）");
    }

    @Test
    @DisplayName("曳光边界：恰好 tick(0.05) 就应消失（life <= 0 的边界语义）")
    void tracerBoundaryIsInclusive() {
        CombatFxModel model = new CombatFxModel();
        model.spawnTracer(0, 0, 0, 1, 0, 0);
        model.tick(CombatFxModel.TRACER_SECONDS);
        assertEquals(0, model.tracerCount(),
                "life 减到恰好 0 必须算作\"已结束\"：用 life < 0 判断会留下一条零寿命的曳光，"
                        + "下一帧才消失，表现为轨迹比 0.05 秒长一帧");
    }

    @Test
    @DisplayName("两端点重合的曳光不产生非法数据（退化输入不抛异常）")
    void degenerateTracerIsHarmless() {
        CombatFxModel model = new CombatFxModel();
        model.spawnTracer(5, 5, 5, 5, 5, 5);
        assertEquals(1, model.tracerCount(), "模型不判退化：它只存数据，退化由渲染层跳过");
        model.tick(0.01);
        assertEquals(1, model.tracerCount());
    }

    // ============================================================ 3. 寿命与重力

    @Test
    @DisplayName("重力按 −16 格/秒² 作用：竖直速度单调递减，高度先升后降")
    void gravitySlowsAndBendsTheArc() {
        CombatFxModel model = new CombatFxModel();
        model.spawnBlockBreak(0, 80, 0, 1f, 1f, 1f, SEED);

        final double dt = 0.01;
        // 抽 30 个 tick（= 0.3 秒）：寿命最短也有 0.4 秒，因此不会被移除；
        // 而最快的粒子顶点也在 0.22 秒处，所以这 0.3 秒必然覆盖"升—降"两段。
        final int ticks = 30;

        List<Double> heights = new ArrayList<>();
        List<Double> verticalSpeeds = new ArrayList<>();
        heights.add(model.particles().get(0).y());
        verticalSpeeds.add(model.particles().get(0).vy());

        for (int i = 0; i < ticks; i++) {
            Particle before = model.particles().get(0);
            model.tick(dt);
            Particle after = model.particles().get(0);
            assertEquals(before.vy() + CombatFxModel.GRAVITY * dt, after.vy(), 1e-9,
                    "第 " + i + " 个 tick 的竖直速度不符合半隐式欧拉的 vy += g·dt");
            heights.add(after.y());
            verticalSpeeds.add(after.vy());
        }
        // 0.3 秒远短于最短寿命 0.4 秒，因此被跟踪的粒子全程都在
        assertTrue(model.particleCount() > 0, "粒子在 0.3 秒内不该被移除");

        // 竖直速度严格递减（重力持续把它往回拉）
        for (int i = 0; i < verticalSpeeds.size() - 1; i++) {
            assertTrue(verticalSpeeds.get(i + 1) < verticalSpeeds.get(i),
                    "竖直速度在第 " + i + " 个 tick 没有下降：" + verticalSpeeds.get(i)
                            + " → " + verticalSpeeds.get(i + 1));
        }

        // 高度：先严格上升、后严格下降，拐点必须在中间（两边都有数据）
        int peak = 0;
        for (int i = 1; i < heights.size(); i++) {
            if (heights.get(i) > heights.get(peak)) {
                peak = i;
            }
        }
        assertTrue(peak > 0, "粒子从没上升过 —— 竖直初速度必须为正（向上的半球）");
        assertTrue(peak < heights.size() - 1, "粒子从没下降过 —— 重力没起作用或采样窗口太短");
        for (int i = 0; i < peak; i++) {
            assertTrue(heights.get(i + 1) > heights.get(i),
                    "顶点之前高度不该回落：第 " + i + " 个 tick " + heights.get(i) + " → " + heights.get(i + 1));
        }
        for (int i = peak; i < heights.size() - 1; i++) {
            assertTrue(heights.get(i + 1) < heights.get(i),
                    "顶点之后高度必须下降：第 " + i + " 个 tick " + heights.get(i) + " → " + heights.get(i + 1));
        }
    }

    @Test
    @DisplayName("粒子寿命耗尽后被移除，且列表中不存在 life <= 0 的残留")
    void expiredParticlesAreRemoved() {
        CombatFxModel model = new CombatFxModel();
        model.spawnBlockBreak(0, 64, 0, 1f, 1f, 1f, SEED);
        assertTrue(model.particleCount() > 0);

        // 最长寿命 0.8 秒，tick 单步上限 0.25 秒 → 4 步必然清空
        for (int i = 0; i < 5; i++) {
            model.tick(CombatFxModel.MAX_STEP_SECONDS);
            for (Particle p : model.particles()) {
                assertTrue(p.life() > 0.0,
                        "第 " + i + " 步后仍存在 life=" + p.life() + " 的粒子 —— 负寿命残留");
            }
        }
        assertEquals(0, model.particleCount(), "寿命耗尽后粒子必须全部移除");
        assertEquals(0, model.particles().size());
    }

    @Test
    @DisplayName("半隐式欧拉的顺序：速度先更新、位置再按新速度前进")
    void semiImplicitEulerOrder() {
        CombatFxModel model = new CombatFxModel();
        model.spawnBlockBreak(0, 64, 0, 1f, 1f, 1f, SEED);
        Particle before = model.particles().get(0);
        double dt = 0.05;
        model.tick(dt);
        Particle after = model.particles().get(0);
        double expectedVy = before.vy() + CombatFxModel.GRAVITY * dt;
        assertSameBits(expectedVy, after.vy(), "竖直速度");
        assertSameBits(before.y() + expectedVy * dt, after.y(),
                "位置必须用更新后的速度积分（显式欧拉在大 dt 下会发散，这里刻意选半隐式）");
        assertSameBits(before.x() + before.vx() * dt, after.x(), "水平速度不受重力影响");
        assertSameBits(before.z() + before.vz() * dt, after.z(), "水平速度不受重力影响");
    }

    // ============================================================ 4. 颜色

    @Test
    @DisplayName("颜色原样传递（不参与任何调制）：破坏粒子与溅射粒子都一样")
    void colourIsPassedThroughUnchanged() {
        float r = 0.25f, g = 0.5f, b = 0.75f;

        CombatFxModel breakModel = new CombatFxModel();
        breakModel.spawnBlockBreak(1, 1, 1, r, g, b, SEED);
        assertTrue(breakModel.particleCount() > 0);
        for (Particle p : breakModel.particles()) {
            assertEquals(r, p.r(), "破坏粒子 r 被改动了");
            assertEquals(g, p.g(), "破坏粒子 g 被改动了");
            assertEquals(b, p.b(), "破坏粒子 b 被改动了");
        }

        CombatFxModel hitModel = new CombatFxModel();
        hitModel.spawnBlockHit(1.5, 2.5, 3.5, 0.0, 1.0, 0.0, r, g, b, SEED);
        assertEquals(CombatFxModel.HIT_PARTICLES, hitModel.particleCount());
        for (Particle p : hitModel.particles()) {
            assertEquals(r, p.r(), 0f, "溅射粒子 r 被改动了");
            assertEquals(g, p.g(), 0f, "溅射粒子 g 被改动了");
            assertEquals(b, p.b(), 0f, "溅射粒子 b 被改动了");
        }
    }

    // ============================================================ 5. 容量上限

    @Test
    @DisplayName("粒子容量上限生效：狂刷后不超上限，且被淘汰的是最旧的")
    void particleCapacityEvictsOldest() {
        CombatFxModel model = new CombatFxModel();
        // 第一批是纯红，用来识别"最旧的"
        model.spawnBlockBreak(0, 0, 0, 1.0f, 0.0f, 0.0f, 7L);
        assertTrue(model.particleCount() > 0);

        // 每次命中生成 5 个，跑 400 批 = 2000 个，是上限 512 的近 4 倍 ——
        // 必须远远超过容量，否则"刚满载就停手"只会淘汰掉最初的两三个，
        // 断言"最旧的被淘汰"就会因为样本太少而假失败。
        long seed = 1000;
        for (int i = 0; i < 400; i++) {
            model.spawnBlockHit(0.5, 0.5, 0.5, 0.0, 1.0, 0.0, 0.0f, 0.0f, 1.0f, seed++);
            assertTrue(model.particleCount() <= CombatFxModel.MAX_PARTICLES,
                    "狂刷过程中粒子数越过了上限：" + model.particleCount());
        }
        assertEquals(CombatFxModel.MAX_PARTICLES, model.particleCount(),
                "持续生成后应稳定停在上限");

        for (Particle p : model.particles()) {
            assertFalse(p.r() == 1.0f && p.g() == 0.0f && p.b() == 0.0f,
                    "最旧的红色粒子仍然存在 —— 淘汰的不是最旧的");
        }
        // 最新的（蓝）必须还在：淘汰策略是"丢最旧"，不是"丢最新"
        boolean hasBlue = false;
        for (Particle p : model.particles()) {
            hasBlue |= p.b() == 1.0f;
        }
        assertTrue(hasBlue, "最新的粒子被淘汰了 —— 淘汰方向反了");
    }

    @Test
    @DisplayName("曳光容量上限生效，淘汰顺序精确为\"保留最近 64 条\"")
    void tracerCapacityEvictsOldest() {
        CombatFxModel model = new CombatFxModel();
        for (int i = 0; i < 100; i++) {
            model.spawnTracer(i, 0, 0, i, 1, 0);
        }
        assertEquals(CombatFxModel.MAX_TRACERS, model.tracerCount());
        List<Tracer> tracers = model.tracers();
        assertEquals(36.0, tracers.get(0).x1(), EPS,
                "淘汰后剩下的第一条应是第 36 条（100 − 64）—— 否则淘汰的就不是最旧的");
        assertEquals(99.0, tracers.get(tracers.size() - 1).x1(), EPS,
                "最后生成的一条必须在列表末尾");
    }

    // ============================================================ 6. 清空与只读视图

    @Test
    @DisplayName("clear() 清空粒子与曳光，但保留累计读数")
    void clearEmptiesLiveStateOnly() {
        CombatFxModel model = new CombatFxModel();
        model.spawnBlockBreak(0, 0, 0, 1f, 1f, 1f, SEED);
        model.spawnTracer(0, 0, 0, 5, 5, 5);
        int particlesBefore = model.particleCount();
        int totalBefore = model.totalBreakParticles();
        long callsBefore = model.totalSpawnCalls();
        assertTrue(particlesBefore > 0);
        assertEquals(1, model.tracerCount());

        model.clear();

        assertEquals(0, model.particleCount(), "clear() 后不该还有粒子");
        assertEquals(0, model.tracerCount(), "clear() 后不该还有曳光");
        assertEquals(0, model.lastBreakParticleCount(), "clear() 后最近一次的数量读数应归零");
        assertEquals(totalBefore, model.totalBreakParticles(),
                "累计生成数是整个会话的自测证据，clear() 不应抹掉它");
        assertEquals(callsBefore, model.totalSpawnCalls());
        // 清空之后还能继续用
        model.spawnTracer(0, 0, 0, 1, 0, 0);
        assertEquals(1, model.tracerCount());
    }

    @Test
    @DisplayName("particles() / tracers() 是只读视图：任何写入都抛 UnsupportedOperationException")
    void listsAreReadOnlyViews() {
        CombatFxModel model = new CombatFxModel();
        model.spawnBlockBreak(0, 0, 0, 1f, 1f, 1f, SEED);
        model.spawnTracer(0, 0, 0, 1, 0, 0);

        List<Particle> particles = model.particles();
        List<Tracer> tracers = model.tracers();
        int particleCount = model.particleCount();

        assertThrows(UnsupportedOperationException.class, particles::clear,
                "调用方能清空内部列表 —— 只读视图没生效");
        assertThrows(UnsupportedOperationException.class,
                () -> particles.add(particles.get(0)));
        assertThrows(UnsupportedOperationException.class,
                () -> particles.remove(0));
        assertThrows(UnsupportedOperationException.class, tracers::clear);
        assertThrows(UnsupportedOperationException.class,
                () -> tracers.add(tracers.get(0)));

        assertEquals(particleCount, model.particleCount(),
                "写入尝试被拒绝后内部状态必须毫发无损");
        assertEquals(1, model.tracerCount());
    }

    // ============================================================ 7. tick 的退化输入

    @Test
    @DisplayName("tick(0) / tick(-1) 不推进任何状态（负数 dt 不能\"倒着长寿命\"）")
    void nonPositiveDtDoesNothing() {
        CombatFxModel model = new CombatFxModel();
        model.spawnBlockBreak(0, 64, 0, 1f, 1f, 1f, SEED);
        model.spawnTracer(0, 0, 0, 1, 0, 0);

        double life = model.particles().get(0).life();
        double y = model.particles().get(0).y();
        double tracerLife = model.tracers().get(0).life();

        model.tick(0.0);
        model.tick(-1.0);
        model.tick(-0.0001);
        model.tick(Double.NaN);

        assertEquals(life, model.particles().get(0).life(), 0.0,
                "非正 dt 不该改变寿命（负数会把它越减越长）");
        assertEquals(y, model.particles().get(0).y(), 0.0, "非正 dt 不该改变位置");
        assertEquals(tracerLife, model.tracers().get(0).life(), 0.0);
    }

    @Test
    @DisplayName("超长帧安全：tick(10) 被截断到 0.25 秒，不产生 NaN / 负寿命，也不清空全部特效")
    void hugeDtIsClamped() {
        CombatFxModel model = new CombatFxModel();
        model.spawnBlockBreak(0, 64, 0, 1f, 1f, 1f, SEED);
        int before = model.particleCount();

        model.tick(10.0);

        assertTrue(model.particleCount() > 0,
                "一次 10 秒的卡顿把所有特效抹掉了 —— dt 没有被截断到 MAX_STEP_SECONDS");
        assertTrue(model.particleCount() <= before);
        for (Particle p : model.particles()) {
            assertTrue(p.life() > 0.0, "存在负寿命残留：life=" + p.life());
            assertTrue(p.life() >= p.maxLife() - CombatFxModel.MAX_STEP_SECONDS - EPS,
                    "寿命被推进了超过一个 MAX_STEP：life=" + p.life() + " maxLife=" + p.maxLife());
            assertFinite(p.x(), "x");
            assertFinite(p.y(), "y");
            assertFinite(p.z(), "z");
            assertFinite(p.vx(), "vx");
            assertFinite(p.vy(), "vy");
            assertFinite(p.vz(), "vz");
        }
    }

    @Test
    @DisplayName("长时间连续推进（含长帧交替）后状态始终有限，且寿命不会倒长")
    void longRunStaysSane() {
        CombatFxModel model = new CombatFxModel();
        double[] steps = {0.016, 0.25, 0.0, -3.0, 0.6, 10.0, 0.001};

        for (int frame = 0; frame < 400; frame++) {
            // 每 10 帧破坏一次，制造持续的生成与消亡
            if (frame % 10 == 0) {
                model.spawnBlockBreak(frame % 7, 64, -frame, 0.5f, 0.5f, 0.5f, frame);
                model.spawnTracer(0, 0, 0, frame, 1, 1);
            }
            model.tick(steps[frame % steps.length]);

            assertTrue(model.particleCount() <= CombatFxModel.MAX_PARTICLES);
            assertTrue(model.tracerCount() <= CombatFxModel.MAX_TRACERS);
            for (Particle p : model.particles()) {
                assertTrue(p.life() > 0.0, "帧 " + frame + " 出现负寿命残留");
                assertTrue(p.life() <= p.maxLife() + EPS, "寿命不该变长（帧 " + frame + "）");
                assertFinite(p.x(), "x");
                assertFinite(p.y(), "y");
                assertFinite(p.z(), "z");
                assertFinite(p.vy(), "vy");
            }
            for (Tracer t : model.tracers()) {
                assertTrue(t.life() > 0.0, "帧 " + frame + " 出现负寿命曳光");
                assertTrue(t.life() <= CombatFxModel.TRACER_SECONDS + EPS);
            }
        }
        // 总量守恒的一个弱形式：长期运行后累计数必须增长
        assertTrue(model.totalSpawnCalls() > 0);
        assertTrue(model.totalBreakParticles() > 0);
    }

    // ============================================================ 辅助

    /** 逐位比较两个 double —— 断言"确定性"必须用这个，不能用容差。 */
    private static void assertSameBits(double expected, double actual, String message) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual),
                message + "：期望位模式 " + Long.toHexString(Double.doubleToLongBits(expected))
                        + "，实际 " + Long.toHexString(Double.doubleToLongBits(actual))
                        + "（期望值 " + expected + "，实际值 " + actual + "）");
    }

    private static void assertFinite(double value, String name) {
        assertFalse(Double.isNaN(value), name + " 是 NaN");
        assertFalse(Double.isInfinite(value), name + " 是无穷大");
    }

    // ============================================================ 8. M2.1：枪口闪光

    /**
     * 枪口闪光的存活窗口（M2.1）。
     *
     * <p><b>为什么必须在这里被单测钉住：</b>M2.1 交付过一版"四个方法全部只定义、从不被调用"
     * 的死接线（编译过、单测全绿、门禁全绿）。闪光只活 3 帧，"它到底生没生成、
     * 活了多久"在带窗口的自测里几乎不可观测 —— 纯状态机这一层才是它唯一能被举证的地方。
     */
    @Test
    @DisplayName("枪口闪光存活 0.05 秒（60 Hz 下 3 帧），且带尺寸与累计读数")
    void muzzleFlashLivesThreeFramesAndIsCounted() {
        CombatFxModel model = new CombatFxModel();
        assertEquals(0, model.flashCount());
        assertEquals(0.0, model.hitMarker01(), 0.0);

        model.spawnMuzzleFlash(1.5, 65.0, -2.5, 1.0, 65.0, -2.0, 0.0, 0.0, -1.0);

        assertEquals(1, model.flashCount(), "一发实弹必须生成一个枪口闪光");
        assertEquals(1, model.totalMuzzleFlashes(), "累计读数必须同步推进（自测靠它举证）");
        CombatFxModel.Flash flash = model.flashes().get(0);
        assertEquals(CombatFxModel.MUZZLE_FLASH_SECONDS, flash.life(), EPS,
                "初始寿命必须等于 MUZZLE_FLASH_SECONDS");
        assertEquals(CombatFxModel.MUZZLE_FLASH_SIZE, flash.size(), EPS,
                "尺寸必须来自常量，渲染层不得自带尺寸");
        // 缺陷 A：闪光必须真的离开眼睛（否则关闭背面剔除后会被相机吞进盒子内部糊成白屏）
        CombatFxModel.MuzzleFlashSample sample = model.lastMuzzleFlashSample();
        assertNotNull(sample, "必须留档枪口与眼睛的相对关系，否则这条断言在收尾时无从读取");
        assertTrue(sample.distanceFromEye() > 0.1,
                "枪口必须离开眼睛：distanceFromEye=" + sample.distanceFromEye());
        assertTrue(sample.forwardDot() > 0.0,
                "枪口必须在眼睛<u>前方</u>（沿视线的投影为正），实测 " + sample.forwardDot());

        model.tick(0.049);
        assertEquals(1, model.flashCount(), "0.049 秒还没到 0.05 秒，闪光不该消失");
        model.tick(0.002);
        assertEquals(0, model.flashCount(), "累计 0.051 秒后闪光必须被移除");
        // 累计读数不受 clear() 影响：它是"整个会话生成过多少"的证据
        assertEquals(1, model.totalMuzzleFlashes());
    }

    @Test
    @DisplayName("枪口闪光容量上限生效，且淘汰的是最旧的")
    void muzzleFlashCapacityEvictsOldest() {
        CombatFxModel model = new CombatFxModel();
        for (int i = 0; i < CombatFxModel.MAX_FLASHES + 5; i++) {
            model.spawnMuzzleFlash(i, 0, 0, i, 0, 1, 0, 0, 1);
        }
        assertEquals(CombatFxModel.MAX_FLASHES, model.flashCount(),
                "持续生成后应稳定停在上限");
        assertEquals(5.0, model.flashes().get(0).x(), EPS,
                "最旧的 5 条应已被淘汰（保留最近 MAX_FLASHES 条）");
    }

    // ============================================================ 9. M2.1：命中标记

    /**
     * 命中标记的生命周期（M2.1）。
     *
     * <p>这是"准星瞬时变化"这件<u>看起来只能靠眼睛判别</u>的事的唯一举证点：
     * 强度是一个随时间线性衰减到 0 的纯函数，因此"命中时 = 1、0.18 秒后 = 0"
     * 可以在没有窗口的机器上逐帧断言。HUD 只镜像这个数值，自己不计时。
     */
    @Test
    @DisplayName("命中标记：命中瞬间 = 1，线性衰减，0.18 秒后归零且不再为负")
    void hitMarkerDecaysFromOneToZero() {
        CombatFxModel model = new CombatFxModel();
        model.spawnHitMarker();
        assertEquals(1.0, model.hitMarker01(), EPS, "刚命中时强度为 1");
        assertEquals(1, model.totalHitMarkers());

        model.tick(CombatFxModel.HIT_MARKER_SECONDS / 2);
        double half = model.hitMarker01();
        assertTrue(half > 0.0 && half < 1.0, "半程应当是中间强度，实测 " + half);

        model.tick(CombatFxModel.HIT_MARKER_SECONDS);
        assertEquals(0.0, model.hitMarker01(), 0.0,
                "越过 HIT_MARKER_SECONDS 后必须恰好归零（UI 用不到负值）");
        // 再推进也不能变负：负值会让 HUD 的斜臂算出负长度
        model.tick(1.0);
        assertEquals(0.0, model.hitMarker01(), 0.0);
    }

    @Test
    @DisplayName("连续命中是\"刷新\"而不是\"叠加\"（强度恒被夹在 1）")
    void repeatedHitsRefreshRatherThanStack() {
        CombatFxModel model = new CombatFxModel();
        model.spawnHitMarker();
        model.tick(CombatFxModel.HIT_MARKER_SECONDS / 2);
        model.spawnHitMarker();
        assertEquals(1.0, model.hitMarker01(), EPS,
                "连打两发后强度必须回到 1，而不是 1.5 —— 渲染层只能按 0..1 解释");
        assertEquals(2, model.totalHitMarkers(), "累计次数仍要如实记录每一次命中");
    }

    @Test
    @DisplayName("命中实体生成一簇粒子，且与命中方块共用同一份确定性实现")
    void entityHitSpawnsBurstWithTheSameDeterminism() {
        CombatFxModel model = new CombatFxModel();
        model.spawnEntityHit(3.0, 64.5, -1.0, 0.6f, 0.1f, 0.12f, SEED);
        assertEquals(CombatFxModel.ENTITY_HIT_PARTICLES, model.particleCount(),
                "实体命中的粒子数由 ENTITY_HIT_PARTICLES 给出");
        assertEquals(1, model.totalEntityHitBursts());

        // 同 seed 必须与"打中方块"的前 N 个粒子逐位相同 —— 这就是"与 block break 粒子一致"的证据
        CombatFxModel block = new CombatFxModel();
        block.spawnBlockHit(3.0, 64.5, -1.0, 0.0, 1.0, 0.0, 0.6f, 0.1f, 0.12f, SEED);
        List<Particle> a = model.particles();
        List<Particle> b = block.particles();
        int shared = Math.min(a.size(), b.size());
        assertTrue(shared >= CombatFxModel.HIT_PARTICLES,
                "两套溅射至少要有 HIT_PARTICLES 个粒子可比，实际 " + shared);
        for (int i = 0; i < shared; i++) {
            assertSameBits(a.get(i).vx(), b.get(i).vx(), "第 " + i + " 个粒子的 vx");
            assertSameBits(a.get(i).vy(), b.get(i).vy(), "第 " + i + " 个粒子的 vy");
            assertSameBits(a.get(i).vz(), b.get(i).vz(), "第 " + i + " 个粒子的 vz");
        }
    }

    @Test
    @DisplayName("clear() 清空闪光与命中标记，但保留累计读数")
    void clearEmptiesMuzzleFlashesAndHitMarker() {
        CombatFxModel model = new CombatFxModel();
        model.spawnMuzzleFlash(0, 0, 0, 0, 0, 1, 0, 0, 1);
        model.spawnHitMarker();
        assertTrue(model.hitMarker01() > 0);

        model.clear();

        assertEquals(0, model.flashCount(), "clear() 后不该还有闪光");
        assertEquals(0.0, model.hitMarker01(), 0.0, "clear() 后不该还在显示命中标记");
        assertEquals(1, model.totalMuzzleFlashes(), "累计读数是会话级证据，clear() 不抹掉它");
        assertEquals(1, model.totalHitMarkers());
    }

    /** 保留一个反例断言，确保 PRD 的常数没被误改（改了就该有人来解释）。 */
    @Test
    @DisplayName("PRD 规定的常数与曳光时长未被改动")
    void prdConstantsAreIntact() {
        assertEquals(0.05, CombatFxModel.TRACER_SECONDS, 0.0, "PRD §5.4.3：曳光持续 0.05 秒");
        assertEquals(8, CombatFxModel.BREAK_PARTICLES_MIN, "PRD §5.2：破坏粒子 8–12 个");
        assertEquals(12, CombatFxModel.BREAK_PARTICLES_MAX);
        assertNotEquals(CombatFxModel.BREAK_PARTICLES_MIN, CombatFxModel.BREAK_PARTICLES_MAX);
    }
}

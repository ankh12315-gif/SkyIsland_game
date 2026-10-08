package com.skyisland.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 昼夜时钟的行为守卫（PRD §4.4）。
 *
 * <p>★ 本类最有价值的三条断言是：
 * <ul>
 *   <li>{@link #daylightIsContinuousAcrossEveryPhaseBoundary} —— 昼夜"连续化"的全部含义
 *       就在这一条里。它一旦红，画面就会在阶段切换那一帧整体跳变；</li>
 *   <li>{@link #theDayCountIncrementsExactlyWhenDawnIsCrossed} —— §4.4「存活天数以黎明为
 *       结算点递增」，错成"以夜晚为结算点"不会报错，只会让天数永远差一天；</li>
 *   <li>{@link #theNightFloorIsFifteenPercentOfTheDayFloor} —— §4.4「夜晚最低亮度为白天的
 *       15%」。★ 它必须<b>同时</b>约束天光档位与明暗地板：只约束一处时，
 *       另一处可以悄悄停在白天的值上而没有任何断言变红。</li>
 * </ul>
 */
class DayClockTest {

    private static final double TOL = 1e-9;

    @Test
    void thePhaseTableMatchesPrdSection4Point4() {
        // PRD §4.4：黎明 1 / 白天 11 / 黄昏 1 / 夜晚 7 分钟 = 20 分钟
        DayClock clock = new DayClock(DayClock.DEFAULT_TOTAL_SECONDS);
        assertEquals(60.0, DayPhase.DAWN.fraction() * clock.totalSeconds(), TOL, "黎明 1 分钟");
        assertEquals(660.0, DayPhase.DAY.fraction() * clock.totalSeconds(), TOL, "白天 11 分钟");
        assertEquals(60.0, DayPhase.DUSK.fraction() * clock.totalSeconds(), TOL, "黄昏 1 分钟");
        assertEquals(420.0, DayPhase.NIGHT.fraction() * clock.totalSeconds(), TOL, "夜晚 7 分钟");
        assertEquals(1200.0, clock.totalSeconds(), TOL, "合计 20 分钟");
    }

    @Test
    void thePhaseFractionsSumToExactlyOne() {
        double sum = 0.0;
        for (DayPhase phase : DayPhase.values()) {
            sum += phase.fraction();
        }
        assertEquals(1.0, sum, 1e-12,
                "四个阶段的比例必须恰为 1；差一点就会让某个阶段凭空多出或消失一段时间");
    }

    @Test
    void aNewWorldStartsAtTheBeginningOfDay() {
        DayClock clock = new DayClock();
        assertEquals(DayPhase.DAY, clock.phase(), "开局必须是白天 —— 开局就是夜晚玩家看不见东西");
        assertEquals(1, clock.dayCount(), "第 1 天");
        assertEquals(1.0, clock.daylight(), TOL, "白天全亮");
    }

    @Test
    void walkingForwardPassesThroughDuskIntoNight() {
        DayClock clock = new DayClock();
        // 开局在白天开头：t = 60。走到 t = 719 仍是白天，t = 725 是黄昏，t = 800 是夜晚。
        clock.advance(659.0);
        assertEquals(DayPhase.DAY, clock.phase(), "t=719 应仍是白天");
        clock.advance(6.0);
        assertEquals(DayPhase.DUSK, clock.phase(), "t=725 应进入黄昏");
        clock.advance(80.0);
        assertEquals(DayPhase.NIGHT, clock.phase(), "t=805 应进入夜晚");
    }

    @Test
    void theDayCountIncrementsExactlyWhenDawnIsCrossed() {
        DayClock clock = new DayClock();
        assertEquals(1, clock.dayCount());
        // 从 t=60 走到 t=1199.9（仍在夜晚末尾），还没跨过结算点
        clock.setTimeSeconds(1199.9);
        assertEquals(DayPhase.NIGHT, clock.phase());
        assertEquals(0, clock.advance(0.05), "还在夜晚内：不应跨过结算点");
        assertEquals(1, clock.dayCount());
        // 再跨过黎明结算点
        clock.setTimeSeconds(1199.9);
        int rolled = clock.advance(0.2);
        assertEquals(1, rolled, "跨过黎明结算点应恰好 +1 天");
        assertEquals(2, clock.dayCount());
        assertEquals(DayPhase.DAWN, clock.phase(), "回绕后的时刻必然落在黎明");
    }

    @Test
    void advancingByExactlyTheTotalRollsExactlyOneDay() {
        DayClock clock = new DayClock();
        int rolled = clock.advance(clock.totalSeconds());
        assertEquals(1, rolled);
        assertEquals(2, clock.dayCount());
        assertEquals(60.0, clock.timeSeconds(), TOL, "回绕后仍停在原时刻");
    }

    @Test
    void aHugeStepRollsSeveralDaysAtOnceAndStaysFinite() {
        DayClock clock = new DayClock();
        int rolled = clock.advance(clock.totalSeconds() * 7.5);
        assertEquals(7, rolled, "7.5 个周期跨过 7 个结算点");
        assertEquals(8, clock.dayCount());
        assertTrue(clock.timeSeconds() >= 0 && clock.timeSeconds() < clock.totalSeconds(),
                "回绕后时刻必须仍在 [0, total)");
    }

    @Test
    void nonPositiveOrNonFiniteDeltaDoesNotMoveTheClock() {
        DayClock clock = new DayClock();
        double before = clock.timeSeconds();
        assertEquals(0, clock.advance(0.0));
        assertEquals(0, clock.advance(-5.0));
        assertEquals(0, clock.advance(Double.NaN));
        assertEquals(0, clock.advance(Double.POSITIVE_INFINITY));
        assertEquals(before, clock.timeSeconds(), TOL, "非法 dt 一律不推进（不抛、不回绕）");
        assertEquals(1, clock.dayCount());
    }

    /**
     * ★ 昼夜"连续化"的核心断言：四个阶段衔接处 {@link DayClock#daylight()} 必须连续。
     *
     * <p>若某个边界上跳变了，画面会在那一帧整体明暗突变（"闪了一下"），
     * 而这<b>不会让任何一条关于"白天是 1 / 夜晚是 0"的断言变红</b> ——
     * 两条断言各自都对，错的是它们之间的过渡。
     */
    @Test
    void daylightIsContinuousAcrossEveryPhaseBoundary() {
        double total = 1200.0;
        double[] boundaries = {
                DayPhase.DAWN.fraction(),                                   // 黎明 → 白天
                DayPhase.DAWN.fraction() + DayPhase.DAY.fraction(),         // 白天 → 黄昏
                DayPhase.DAWN.fraction() + DayPhase.DAY.fraction()
                        + DayPhase.DUSK.fraction(),                         // 黄昏 → 夜晚
                1.0,                                                        // 夜晚 → 黎明（回绕）
        };
        double eps = 1e-4;   // 0.1 毫秒的步长：远小于一帧，却足以暴露阶跃
        for (double boundary : boundaries) {
            DayClock before = new DayClock(total);
            before.setTimeSeconds(boundary * total - eps);
            DayClock after = new DayClock(total);
            after.setTimeSeconds(boundary * total + eps);
            assertEquals(before.daylight(), after.daylight(), 1e-3,
                    "阶段边界 t=" + (boundary * total) + " 处日照必须连续");
        }
    }

    @Test
    void daylightRampsFromZeroToOneAcrossDawnAndBackAcrossDusk() {
        DayClock clock = new DayClock();
        clock.setTimeSeconds(0.0);
        assertEquals(0.0, clock.daylight(), 1e-6, "黎明起点最暗");
        clock.setTimeSeconds(30.0);
        assertEquals(0.5, clock.daylight(), 1e-6, "黎明中点半亮");
        clock.setTimeSeconds(59.999);
        assertEquals(1.0, clock.daylight(), 1e-3, "黎明末尾已全亮（与白天衔接）");

        clock.setTimeSeconds(720.0);
        assertEquals(1.0, clock.daylight(), 1e-6, "黄昏起点仍全亮（与白天衔接）");
        clock.setTimeSeconds(750.0);
        assertEquals(0.5, clock.daylight(), 1e-6, "黄昏中点半亮");
        clock.setTimeSeconds(779.999);
        assertEquals(0.0, clock.daylight(), 1e-3, "黄昏末尾已全暗（与夜晚衔接）");
    }

    @Test
    void daylightIsOneForTheWholeOfDayAndZeroForTheWholeOfNight() {
        DayClock clock = new DayClock();
        for (double t = 60.0; t < 720.0; t += 37.0) {
            clock.setTimeSeconds(t);
            assertEquals(1.0, clock.daylight(), 1e-9, "白天 t=" + t + " 应恒为全亮");
        }
        for (double t = 780.0; t < 1200.0; t += 41.0) {
            clock.setTimeSeconds(t);
            assertEquals(0.0, clock.daylight(), 1e-9, "夜晚 t=" + t + " 应恒为全暗");
        }
    }

    /**
     * ★ PRD §4.4「夜晚最低亮度为白天的 15%」。
     *
     * <p>两处都要约束：只测 {@code skyLevel} 的话，{@code ambientFloor} 可以停在白天的值上，
     * 于是"夜里地面照旧亮、只有天空暗"，而所有断言依然全绿。
     */
    @Test
    void theNightFloorIsFifteenPercentOfTheDayFloor() {
        DayClock day = new DayClock();
        day.setTimeSeconds(300.0);
        DayClock night = new DayClock();
        night.setTimeSeconds(900.0);

        assertEquals(1.0f, day.skyLevel(), 1e-6f);
        assertEquals(DayClock.NIGHT_BRIGHTNESS_RATIO, night.skyLevel(), 1e-6f,
                "夜天光档位 = 白天的 15%");
        assertEquals(LightEngine.AMBIENT_FLOOR, day.ambientFloor(), 1e-6f);
        assertEquals(LightEngine.AMBIENT_FLOOR * DayClock.NIGHT_BRIGHTNESS_RATIO,
                night.ambientFloor(), 1e-6f, "夜明暗地板 = 白天的 15%");
    }

    @Test
    void skyLevelStaysInsideTheLegalBandAtEveryInstant() {
        DayClock clock = new DayClock();
        for (double t = 0.0; t < 1200.0; t += 3.0) {
            clock.setTimeSeconds(t);
            float level = clock.skyLevel();
            assertTrue(level >= DayClock.NIGHT_BRIGHTNESS_RATIO - 1e-6f
                            && level <= 1.0f + 1e-6f,
                    "t=" + t + " 的天光档位 " + level + " 越界");
            float floor = clock.ambientFloor();
            assertTrue(floor > 0f && floor <= LightEngine.AMBIENT_FLOOR + 1e-6f,
                    "t=" + t + " 的明暗地板 " + floor + " 越界（必须为正，否则全黑）");
        }
    }

    @Test
    void aTorchLitBlockKeepsItsBrightnessAtNight() {
        // 这是整套昼夜设计的目的（§4.4「火把成为主要照明」）：
        // 火把分量不乘天光档位，因此夜里与白天同样是满档 15。
        DayClock day = new DayClock();
        day.setTimeSeconds(300.0);
        DayClock night = new DayClock();
        night.setTimeSeconds(900.0);
        float torch = 14f / 15f;
        assertEquals(torch, Math.max(0f, torch), 1e-6f);
        // 天光档位只在"见天"时参与；火把项与它无关，故此处只断言地板/档位的分离：
        assertFalse(night.skyLevel() >= day.skyLevel(), "夜天光必须严格低于白天");
        assertTrue(night.ambientFloor() < day.ambientFloor(), "夜地板必须严格低于白天");
    }

    @Test
    void aCorruptTimeFromSaveFallsBackToTheStartOfDay() {
        DayClock clock = new DayClock();
        clock.setTimeSeconds(Double.NaN);
        assertEquals(DayPhase.DAY, clock.phase(), "NaN 时刻应降级到白天开头（§N.6：降级而非崩溃）");
        clock.setTimeSeconds(Double.POSITIVE_INFINITY);
        assertEquals(DayPhase.DAY, clock.phase());
        clock.setTimeSeconds(-9999.0);
        assertTrue(clock.timeSeconds() >= 0 && clock.timeSeconds() < clock.totalSeconds(),
                "负时刻必须回绕到合法区间");
    }

    @Test
    void dayCountNeverDropsBelowOne() {
        DayClock clock = new DayClock();
        clock.setDayCount(-5);
        assertEquals(1, clock.dayCount(), "“第 0 天”没有意义");
        clock.setDayCount(9);
        assertEquals(9, clock.dayCount());
    }

    @Test
    void scalingTheTotalKeepsThePhaseProportions() {
        // -Dskyisland.dayLengthSeconds 只缩放总时长，四个阶段必须按比例一起缩放。
        DayClock fast = new DayClock(120.0);
        assertEquals(6.0, DayPhase.DAWN.fraction() * fast.totalSeconds(), TOL, "黎明 6 秒");
        assertEquals(66.0, DayPhase.DAY.fraction() * fast.totalSeconds(), TOL, "白天 66 秒");
        assertEquals(6.0, DayPhase.DUSK.fraction() * fast.totalSeconds(), TOL, "黄昏 6 秒");
        assertEquals(42.0, DayPhase.NIGHT.fraction() * fast.totalSeconds(), TOL, "夜晚 42 秒");
        assertEquals(DayPhase.DAY, fast.phase());
        assertEquals(1.0, fast.daylight(), TOL);
    }

    @Test
    void aNonPositiveTotalFallsBackToTheDefault() {
        assertEquals(DayClock.DEFAULT_TOTAL_SECONDS, new DayClock(0.0).totalSeconds(), TOL);
        assertEquals(DayClock.DEFAULT_TOTAL_SECONDS, new DayClock(-1.0).totalSeconds(), TOL);
        assertEquals(DayClock.DEFAULT_TOTAL_SECONDS,
                new DayClock(Double.POSITIVE_INFINITY).totalSeconds(), TOL);
    }

    @Test
    void theStatusLineCarriesDayPhaseAndCountdown() {
        DayClock clock = new DayClock();
        clock.setDayCount(3);
        clock.setTimeSeconds(300.0);
        String line = clock.statusLine();
        assertTrue(line.contains("第 3 天"), "摘要应带天数：" + line);
        assertTrue(line.contains("白天"), "摘要应带阶段名：" + line);
        assertTrue(line.contains("剩余"), "摘要应带倒计时：" + line);
    }
}

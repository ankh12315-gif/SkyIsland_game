package com.skyisland.save;

import com.skyisland.player.Player;
import com.skyisland.testutil.TestWorlds;
import com.skyisland.world.DayClock;
import com.skyisland.world.DayPhase;
import com.skyisland.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ M5a：昼夜时刻与天数的存档往返守卫。
 *
 * <h3>本类守着三条"只有旧存档能测出来"的性质</h3>
 * <ol>
 *   <li><b>缺字段 ≠ 字段是 0</b>：老存档没有 {@code worldTimeSeconds}，
 *       必须落到"白天开头"；若把缺失读成 {@code 0}，玩家每次开老存档都会被丢到
 *       黎明的第一秒（几乎全黑），而症状只是"开局黑了一下"。</li>
 *   <li><b>不传时钟的保存不得覆盖时刻</b>：{@code save(world, player)} 这个旧签名
 *       仍被几十个测试与若干调用点使用，若它顺手写了个默认值，
 *       玩家就会遇到"下次读档时刻被重置"。</li>
 *   <li><b>时刻可往返而阶段不可</b>：存时刻能还原整段过渡；存阶段名不能。</li>
 * </ol>
 */
class SaveManagerDayTimeTest {

    private static final String WORLD_NAME = "daytime-world";

    @TempDir
    Path root;

    private SaveManager manager() {
        return new SaveManager(root, WORLD_NAME);
    }

    private static World flatWorld() {
        return TestWorlds.flatWorld(0, 0, 0, 0);
    }

    private static Player freshPlayer() {
        return new Player(0.5, TestWorlds.SURFACE_FEET_Y, 0.5);
    }

    @Test
    void theTimeAndDayCountSurviveASaveLoadRoundTrip() {
        SaveManager sm = manager();
        World world = flatWorld();

        DayClock clock = new DayClock();
        clock.setTimeSeconds(900.0);   // 夜晚
        clock.setDayCount(4);

        sm.save(world, freshPlayer(), clock);

        DayClock restored = new DayClock();
        assertTrue(sm.applyWorldTime(restored), "存档带了时刻就应该被应用");
        assertEquals(900.0, restored.timeSeconds(), 1e-9, "时刻应逐位还原");
        assertEquals(4, restored.dayCount(), "天数应还原");
        assertEquals(DayPhase.NIGHT, restored.phase());
    }

    @Test
    void aTimeInsideDawnKeepsItsPositionInsideTheRamp() {
        SaveManager sm = manager();
        DayClock clock = new DayClock();
        clock.setTimeSeconds(30.0);    // 黎明中点，日照恰好 0.5
        clock.setDayCount(2);
        sm.save(flatWorld(), freshPlayer(), clock);

        DayClock restored = new DayClock();
        assertTrue(sm.applyWorldTime(restored));
        assertEquals(0.5, restored.daylight(), 1e-9,
                "存时刻才能还原过渡的位置；存阶段名只能得到'黎明'而丢失进度");
    }

    @Test
    void aLegacySaveWithoutTheFieldsFallsBackToTheStartOfDay() {
        SaveManager sm = manager();
        // 先做一次普通保存（不传时钟 ⇒ 不写这两个字段），模拟 M5a 之前的老存档。
        sm.save(flatWorld(), freshPlayer());

        String json = readLevelJson(sm);
        assertFalse(json.contains("worldTimeSeconds"),
                "不传时钟的保存不该写 worldTimeSeconds —— 否则它会成为一条不报错的路径");
        assertFalse(json.contains("\"dayCount\""), "不传时钟的保存不该写 dayCount");

        DayClock restored = new DayClock();
restored.setTimeSeconds(900.0);   // 人为放到夜晚，验证"缺字段时**不会**被覆盖"
assertFalse(sm.applyWorldTime(restored), "缺字段时 applyWorldTime 必须返回 false");
assertEquals(900.0, restored.timeSeconds(), 1e-9,
                "缺字段时的契约是**不动时钟**，而不是把它强行改写成某个值 —— "
                        + "强行改写会让'先看过一次 level.json 再决定要不要改'的调用点失效");
        // 契约的另一面：游戏里传进来的时钟是**刚 new 出来**的，它本身就停在白天开头。
        assertEquals(DayPhase.DAY, new DayClock().phase(),
                "新建时钟的默认时刻必须是白天开头 —— "
                        + "若默认落在黎明的第一秒，老存档玩家开局几乎全黑");
        assertEquals(1, new DayClock().dayCount());
    }

    @Test
    void aHandEditedZeroTimeIsNotTreatedAsMissing() {
        // ★ 判据是"字段不存在"而不是"值为 0"。手改成 0 的存档必须被如实采用，
        //   否则"玩家故意把时间设到黎明"这种合法操作会静默失效。
        SaveManager sm = manager();
        sm.save(flatWorld(), freshPlayer(), clockAt(0.0, 3));

        DayClock restored = new DayClock();
        assertTrue(sm.applyWorldTime(restored), "0 是合法时刻，不是缺失");
        assertEquals(0.0, restored.timeSeconds(), 1e-9);
        assertEquals(DayPhase.DAWN, restored.phase());
        assertEquals(3, restored.dayCount());
    }

    @Test
    void savingWithoutAClockLeavesAnEarlierTimeUntouched() {
        SaveManager sm = manager();
        DayClock clock = clockAt(800.0, 6);
        sm.save(flatWorld(), freshPlayer(), clock);

        // 旧签名：等价于"这次不更新时刻"
        sm.save(flatWorld(), freshPlayer());

        DayClock restored = new DayClock();
        assertTrue(sm.applyWorldTime(restored));
        assertEquals(800.0, restored.timeSeconds(), 1e-9,
                "旧签名不得把时刻覆盖成默认值 —— 症状是'每次读档都被重置到白天'");
        assertEquals(6, restored.dayCount());
    }

    @Test
    void applyWorldTimeToleratesANullClockAndAMissingWorld() {
        SaveManager sm = manager();
        assertFalse(sm.applyWorldTime(null), "null 时必须安静返回 false，不能抛");
        assertFalse(sm.applyWorldTime(new DayClock()), "世界里没有 level.json 时返回 false");
    }

    @Test
    void aCorruptTimeValueFallsBackInsteadOfThrowing() throws Exception {
        SaveManager sm = manager();
        sm.save(flatWorld(), freshPlayer(), clockAt(500.0, 2));

        // 手改成非法值：Gson 对 double 的非法字面量会跳过该键 ⇒ 读回来是 null。
        Path level = sm.worldDirectory().resolve(SaveFormat.LEVEL_FILE);
        String json = Files.readString(level, StandardCharsets.UTF_8)
                .replace("\"worldTimeSeconds\":500.0", "\"worldTimeSeconds\":\"不是数字\"");
        Files.writeString(level, json, StandardCharsets.UTF_8);

        DayClock restored = new DayClock();
        // 两种结果都算"安全"：要么被判为缺失（false），要么被如实采用后回绕。
        sm.applyWorldTime(restored);
        assertTrue(restored.timeSeconds() >= 0 && restored.timeSeconds() < restored.totalSeconds(),
                "无论读成什么，时刻都必须落在合法区间内");
    }

    // ============================================================ 辅助

    private static DayClock clockAt(double seconds, int days) {
        DayClock clock = new DayClock();
        clock.setTimeSeconds(seconds);
        clock.setDayCount(days);
        return clock;
    }

    private String readLevelJson(SaveManager sm) {
        try {
            return Files.readString(sm.worldDirectory().resolve(SaveFormat.LEVEL_FILE),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读不到 level.json", e);
        }
    }
}
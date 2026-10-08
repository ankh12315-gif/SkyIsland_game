package com.skyisland.save;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★<b>守卫 {@link LevelMeta#deferredFields()} 这份"本版本刻意未写入"名单。</b>
 *
 * <p><b>为什么这条测试必须存在（它是本项目最典型的"死代码陷阱"形态）</b>：
 * <ul>
 *   <li>{@code LevelMeta.deferredFields()} 此前<b>零测试守卫</b>
 *       （{@code grep -rn deferredFields src/test/} 返回空），而它却是报告与
 *       存档格式说明反复引用的<b>事实来源</b>；</li>
 *   <li>它列了 {@code worldTimeSeconds/dayPhase/dayCount/dayFactor} 四项，
 *       意思是"本版本<b>刻意</b>没写昼夜"。</li>
 * </ul>
 * ⇒一旦 M5a 实现了昼夜却<b>忘了改这份名单</b>，<b>没有任何东西会红</b>，
 * 而后来人读存档说明时会看到"昼夜是刻意未写入的"并据此做出错误判断。
 * 这与"注释能被源码扫描断言满足"是同一族问题：<b>文档/名单与现实脱节，无人发现。</b>
 *
 * <p>★<b>阅读须知</b>：下面那条
 * {@link #dayNightFieldsAreCurrentlyDeferred()} 在 <b>M5a 实现昼夜之后必然变红</b>，
 * <b>那是设计意图</b>，不是"测试坏了"。届时必须同步把四个字段名从
 * {@code LevelMeta.DEFERRED} 里删掉（并删掉本断言，或改写成"昼夜已不在名单里"）。
 * 这条约定已登记在 M5a 的实施说明里。
 */
class LevelMetaDeferredFieldsTest {

    /**★ 防"名单被清空"这种伪修复：空名单会让上面那个陷阱失去存在意义。 */
    @Test
    void deferredListIsNotEmpty() {
        assertFalse(LevelMeta.deferredFields().isEmpty(),
                "DEFERRED 名单不能为空 —— 全绿不代表对，也可能是"
                        + "「有人把名单清空了」，而那正是这份守卫要防的伪修复");
    }

    /**
     * ★<b>M5a 已落地昼夜，因此名单只剩"仍刻意未写入"的那几项。</b>
     *
     * <p><b>这条断言在 M5a 完成时变红过一次，那是它的设计意图</b>：
     * 它把"昼夜现在还没实现"钉成一条会红的断言，从而保证实现的那一刻
     * 就有人被逼着更新名单，而不是几个月后由别人踩坑。
     *
     * <p>M5a 的处置（按本断言自己的指示，<b>不是放宽</b>）：
     * <ul>
     *   <li>{@code worldTimeSeconds} / {@code dayCount} 已真正写入 ⇒ 从名单删除；</li>
     *   <li>{@code dayPhase} 仍刻意不写：它可由 {@code worldTimeSeconds} 推出，
     *       存它就是两份可能互相矛盾的事实；</li>
     *   <li>{@code dayFactor} 仍刻意不写：{@code DayClock} 不产生它
     *       （亮度是连续量，由时刻算出，不需要落盘）。</li>
     * </ul>
     *
     * <p>★ 现在这条断言的形态是<b>反过来钉住</b>：昼夜那两个字段<b>不得</b>回到名单里。
     * 否则将来有人"顺手清理"把它们加回去，就会重新制造
     * 「文档说刻意未写入、而现实已经写入」这个脱节。
     */
    @Test
    void dayNightFieldsThatAreNowWrittenMustStayOffTheList() {
        List<String> deferred = LevelMeta.deferredFields();
        for (String field : List.of("worldTimeSeconds", "dayCount")) {
            assertFalse(deferred.contains(field),
                    field + " 现在**真的被写入**（SaveManager#buildLevelMeta + LevelMeta 字段），"
                            + "不应再留在「本版本刻意未写入」名单里 —— "
                            + "留在名单上会让后来人以为昼夜没有持久化。"
                            + "若你确实把它移出了写入路径，请同时改 LevelMeta 的字段与本断言。");
        }
        // 仍未实现的两项要继续在名单上，否则就是"名单被清空"那种伪修复
        for (String field : List.of("dayPhase", "dayFactor")) {
            assertTrue(deferred.contains(field),
                    field + " 仍未写入（可由 worldTimeSeconds 推出 / 当前不产生），"
                            + "应继续留在 DEFERRED 名单里");
        }
    }

    /** 名单不得有重复项：重复会让"这个名字被列了几次"这个问题永远查不清。 */
    @Test
    void deferredListHasNoDuplicates() {
        List<String> deferred = LevelMeta.deferredFields();
        Set<String> distinct = new HashSet<>(deferred);
        assertEquals(distinct.size(), deferred.size(),
                "DEFERRED 有重复项: " + deferred);
    }

    /**
     * ★<b>名单与现实必须一致：{@code DEFERRED} 里的名字不应已存在于 {@link LevelMeta}。</b>
     *
     * <p><b>这是本测试类的核心判据</b>，它问的是<b>事实</b>而不是<b>名单内容</b>：
     * "你说这个字段刻意没写，那它现在<b>确实</b>没写吗？"
     *
     * <p>与 {@link #dayNightFieldsAreCurrentlyDeferred()} <b>正交</b>：
     * 前者问"名单里该有的名字在不在"，后者问"名单里的名字是不是已经在类里了"。
     * 一份实现完昼夜的 {@code LevelMeta} 应当让前者红、后者绿 ——<b>这正是期望结果</b>，
     * 说明两条断言问的不是同一件事。
     */
    @Test
    void deferredListMatchesTheFieldsActuallyAbsentFromLevelMeta() {
        Set<String> actualFields = new HashSet<>();
        for (Field f : LevelMeta.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            actualFields.add(f.getName());
        }
        for (String name : LevelMeta.deferredFields()) {
            assertFalse(actualFields.contains(name),
                    "DEFERRED 列了 " + name + "，但 LevelMeta 已经有同名字段"
                            + " —— 名单与现实脱节。"
                            + "症状：文档说「本版本刻意未写入 " + name + "」，"
                            + "而它其实早就写了，后来人会据此做出错误判断。");
        }
    }
}

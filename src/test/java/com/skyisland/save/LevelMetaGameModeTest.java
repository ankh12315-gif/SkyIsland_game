package com.skyisland.save;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.skyisland.game.GameMode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★<b>M4-S6 守卫：游戏模式存档字段（PRD_BLOCK_CREATIVE_v1.0.md §4.2 / §4.3）。</b>
 *
 * <p>本类把 §4.2 的四条【必须】逐条钉死，外加 §4.3「模式锁定」的技术断言：
 * <ol>
 *   <li>存档字段 {@code gameMode = survival | creative} 存在且可往返；</li>
 *   <li>旧存档<b>没有</b>该字段时按 survival 处理；</li>
 *   <li>模式一旦创建不可改（存档优先于命令行）；</li>
 *   <li>可读性：模式有中文显示名可打进启动日志。</li>
 * </ol>
 *
 * <h3>★ 本类最容易被"假绿"的一处，以及它为什么这样写</h3>
 * 第 2 条的判据若写成 {@code new LevelMeta().gameModeOrSurvival() == SURVIVAL}，
 * 它会在<b>任何</b>情况下为真 —— 因为字段初始值就是 survival，
 * 而这正是被测代码自己写的初始值。**它问的是"代码有没有把默认值设对"，
 * 不是"旧存档读出来是不是 survival"**。
 *
 * <p>真正能红的判据必须<b>经过 Gson 反序列化</b>：
 * {@link #aLegacyJsonWithoutTheFieldDeserializesToSurvival()} 手写一份
 * <b>字面上就没有 {@code gameMode} 键</b>的 JSON，让 Gson 真的走一遍
 * "字段缺失 → 保留 Java 侧初始值"这条路径。
 * 若有人把 {@code gameMode} 改成枚举类型、或把默认值写成 CREATIVE，
 * 这条会立刻变红，而上面那种写法不会。
 */
class LevelMetaGameModeTest {

    /** 与生产代码用同一个 Gson 配置（GsonBuilder + prettyPrinting，不影响字段映射）。 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ============================================================ §4.2 ①存档字段

    /** 【必须】字段名必须是 {@code gameMode} —— 存档格式的对外契约。 */
    @Test
    void theFieldIsNamedGameMode() {
        assertNotNull(declaredField("gameMode"),
                "level.json 里的模式字段名必须是 gameMode（PRD §4.2）。"
                        + "改名会让所有已存在的存档读不到模式，且无任何告警。");
    }

    /** 【必须】字段类型必须是 String —— 理由见 LevelMeta#gameMode 的类注释（Gson 枚举反序列化会炸掉整个存档）。 */
    @Test
    void theFieldIsAStringNotAnEnum() {
        assertEquals(String.class, declaredField("gameMode").getType(),
                "★ gameMode 必须是 String 而不是 GameMode："
                        + "Gson 反序列化枚举遇到手改过的值（\"survivall\"）会抛 JsonSyntaxException，"
                        + "而 readLevelMeta() 把任何 RuntimeException 转成"
                        + "「level.json 解析失败」—— 一个错别字会让整个存档读不出来，"
                        + "与 PRD §4.2「写错值一律留在生存模式」正好相反。");
    }

    /** 【必须】两个模式的落盘字面量必须是小写，且与 parse 互为逆运算。 */
    @Test
    void persistedLiteralsRoundTripThroughParse() {
        for (GameMode mode : GameMode.values()) {
            assertEquals(mode.persisted(), mode.persisted().toLowerCase(),
                    "落盘字面量应当是小写（与存档里其他 stable ID 一致）：" + mode);
            assertEquals(mode, GameMode.parse(mode.persisted()),
                    "parse(persisted()) 必须是逆运算：" + mode);
        }
        assertEquals("survival", GameMode.SURVIVAL.persisted());
        assertEquals("creative", GameMode.CREATIVE.persisted());
    }

    /** 【必须】模式能真正往返：写出去再读回来，还是同一个模式。 */
    @Test
    void gameModeSurvivesAJsonRoundTrip() {
        for (GameMode mode : GameMode.values()) {
            LevelMeta meta = new LevelMeta();
            meta.gameMode = mode.persisted();
            LevelMeta back = GSON.fromJson(GSON.toJson(meta), LevelMeta.class);
            assertEquals(mode, back.gameModeOrSurvival(),
                    "模式必须能经 JSON 往返：" + mode);
        }
    }

    // ============================================================ §4.2 ②缺省行为

    /**
     * ★★<b>本类的核心判据。</b>
     *
     * <p>它<b>手写字面量 JSON</b>（真的没有 {@code gameMode} 键），
     * 然后走 Gson 反序列化 —— 这才是"读取旧存档"这条真实路径。
     *
     * <p>PRD §4.2【必须】：「读取旧存档<b>没有</b>该字段时<b>按 survival 处理</b>」。
     */
    @Test
    void aLegacyJsonWithoutTheFieldDeserializesToSurvival() {
        String legacyJson = """
                {
                  "saveVersion": 2,
                  "worldName": "m2-play",
                  "worldSeed": 20260919,
                  "generatorId": "skyisland:test",
                  "generatorVersion": 1,
                  "createdAtMillis": 1757212800000,
                  "savedAtMillis": 1757212800000,
                  "productVersion": "0.3.2",
                  "modifiedChunkCount": 3
                }
                """;
        assertFalse(legacyJson.contains("gameMode"),
                "★ 这段 JSON 必须真的不含 gameMode 键 —— "
                        + "它是「旧存档」这一前提的唯一证据。"
                        + "若有人往里加了这个键，本测试就退化成"
                        + "「新存档能不能读」，而 §4.2 要验的是前者。");

        LevelMeta back = GSON.fromJson(legacyJson, LevelMeta.class);

        assertEquals(GameMode.SURVIVAL, back.gameModeOrSurvival(),
                "★ PRD §4.2【必须】：旧存档缺 gameMode 字段时必须按 survival 处理。"
                        + "这是全项目一致的安全侧口径（同 skyisland.infiniteReserve / "
                        + "skyisland.loadout 都是「写错一律留在有限侧」）。");
    }

    /**
     * ★ 字段<b>缺失</b>与字段<b>值为空/错字</b>是两种不同情况，但都必须落 survival。
     *
     * <p>分开测的理由：{@link #recordedGameMode()} 靠"空"判断缺失，
     * 而 {@code gameModeOrSurvival()} 靠 {@code parse} 判断值。
     * 若只测缺失，就<b>测不到</b>"值为空串"这条路；而 Gson 反序列化
     * 一个显式的 {@code "gameMode": ""} 会得到空串（不是默认值），
     * 那条路径必须自己走一遍。
     */
    @Test
    void blankAndMisspelledValuesAlsoLandOnSurvival() {
        for (String raw : new String[]{"", "  ", "survivall", "SURVIVAL_X",
                "creative_mode", "创造", "null", "0", "1", "true"}) {
            LevelMeta meta = GSON.fromJson("{\"gameMode\": \"" + raw + "\"}",
                    LevelMeta.class);
            assertEquals(GameMode.SURVIVAL, meta.gameModeOrSurvival(),
                    "写错的模式值必须落生存（安全侧），实际输入：" + raw);
        }
    }

    /** 大小写与首尾空白必须被容忍 —— 否则一个手写的存档会因为大小写不同而失效。 */
    @Test
    void parseToleratesCaseAndSurroundingWhitespace() {
        assertEquals(GameMode.CREATIVE, GameMode.parse("creative"));
        assertEquals(GameMode.CREATIVE, GameMode.parse("CREATIVE"));
        assertEquals(GameMode.CREATIVE, GameMode.parse("  Creative  "));
        assertEquals(GameMode.SURVIVAL, GameMode.parse("survival"));
        assertEquals(GameMode.SURVIVAL, GameMode.parse("Survival"));
    }

    /** 属性没给（null）必须落 survival，而不是返回 null。 */
    @Test
    void parseOfNullIsSurvivalNotNull() {
        assertEquals(GameMode.SURVIVAL, GameMode.parse(null),
                "parse(null) 必须是 SURVIVAL 而不是 null —— "
                        + "否则每个调用点都要判空，而漏一处就是 NPE。");
        assertEquals(GameMode.SURVIVAL, GameMode.DEFAULT);
    }

    /**
     * ★<b>字段默认值必须是 {@code null}，而不是 {@code "survival"}。</b>
     *
     * <p><b>本条是被一次真实红改写出来的。</b>最初的实现把默认值写成
     * {@code GameMode.DEFAULT.persisted()}（即 {@code "survival"}），理由是"缺省即生存"。
     * 它<b>确实</b>让 §4.2 的缺省行为成立，却同时抹掉了"这个存档<b>没有</b>该字段"
     * 这条信息 —— 因为 Gson 只覆盖 JSON 里出现过的键，缺失的键保留 Java 侧初始值。
     * 后果就是 {@link #recordedGameModeDistinguishesAbsentFromSurvival()} 那条红：
     * <b>{@code -Dskyisland.gameMode=creative} 对旧存档永久失效</b>，
     * 而任何只测新存档的测试都<b>永远测不到</b>它。
     *
     * <p>★ <b>为什么这条值得留在测试里</b>：它是"缺省行为"与"缺失信息"两个需求
     * <b>被同一条赋值顶替</b>的现场记录。后来人若觉得"写 survival 更直观"而改回去，
     * 本条会立刻变红，并在错误信息里告诉他为什么不能这么写。
     */
    @Test
    void theFieldDefaultIsNullSoThatAbsenceStaysObservable() {
        assertNull(new LevelMeta().gameMode,
                "★ gameMode 的默认值必须是 null 而不是 \"survival\"："
                        + "Gson 只覆盖 JSON 里出现过的键，写死字面量会让"
                        + "「旧存档没有该字段」与「旧存档写了 survival」无法区分，"
                        + "于是 -Dskyisland.gameMode=creative 对旧存档永久失效。");

        // 对照：缺省行为本身（§4.2）仍然成立，只是改由安全侧解析承担。
        assertEquals(GameMode.SURVIVAL, new LevelMeta().gameModeOrSurvival(),
                "★ 缺省行为不能因此丢：字段为 null 时 gameModeOrSurvival() 必须落 survival"
                        + "（§4.2【必须】）。");
    }

    // ============================================================ §4.2 / §4.3 ③模式锁定

    /**
     * ★★<b>§4.3「模式锁定」的核心判据：存档优先于命令行。</b>
     *
     * <p>本类最容易出的错是<b>反着实现</b>：让命令行覆盖存档。
     * 那看起来"更灵活"，实际上让 §4.3 那条不可回退的裁定形同虚设 ——
     * 一个 {@code -Dskyisland.gameMode=survival} 就能把创造存档降级，
     * 而玩家在那个世界里已经用无限方块盖了一片建筑。
     */
    @Test
    void anExistingSaveModeBeatsTheCommandLineInBothDirections() {
        // 创造存档 + survival 开关 → 仍是创造（不能被降级）
        assertEquals(GameMode.CREATIVE,
                GameMode.resolve(GameMode.CREATIVE, GameMode.SURVIVAL),
                "★ 创造存档不能被 -Dskyisland.gameMode=survival 降级："
                        + "世界里已有的无限方块建筑会立刻变成「本不该存在的建筑」（§4.3 情形 1）。");
        // 生存存档 + creative 开关 → 仍是生存（也不能被升级）
        assertEquals(GameMode.SURVIVAL,
                GameMode.resolve(GameMode.SURVIVAL, GameMode.CREATIVE),
                "生存存档不能被 -Dskyisland.gameMode=creative 升级："
                        + "那同样是「中途切换」，§4.3 裁定的是「不可改」，不是「只不可降级」。");
    }

    /**
     * ★<b>半锁是最坏的实现</b>：只锁一个方向会让后来人以为"改成创造也行"。
     * 本条用一组对称断言把"两个方向都锁"钉死。
     */
    @Test
    void lockingIsSymmetricNotHalfLocking() {
        GameMode[] all = GameMode.values();
        for (GameMode saved : all) {
            for (GameMode configured : all) {
                assertEquals(saved, GameMode.resolve(saved, configured),
                        "只要存档有值，配置一律不生效（对称）：存档=" + saved
                                + " 配置=" + configured);
            }
        }
    }

    /** 存档无值时才用配置 —— 这是 §4.1「新建世界时选创造」能落盘的前提。 */
    @Test
    void theCommandLineOnlyDecidesWhenTheSaveHasNoModeYet() {
        assertEquals(GameMode.CREATIVE, GameMode.resolve(null, GameMode.CREATIVE),
                "存档没有该字段时（首次创建），命令行必须能决定模式，否则 §4.1 无法落地。");
        assertEquals(GameMode.SURVIVAL, GameMode.resolve(null, GameMode.SURVIVAL));
        assertEquals(GameMode.SURVIVAL, GameMode.resolve(null, null),
                "两者都没有时落生存（§4.1 缺省即生存）。");
    }

    /**
     * ★★<b>{@code resolve} 的第一参数必须区分"字段缺失"与"字段是 survival"。</b>
     *
     * <p>这就是 {@link LevelMeta#recordedGameMode()} 存在的理由。
     * 若把 {@code gameModeOrSurvival()}（缺字段→SURVIVAL）当成 {@code resolve} 的输入，
     * 旧存档会被判成"已定为生存"，于是 {@code -Dskyisland.gameMode=creative}
     * 对<b>旧存档永久失效</b> —— 而新建存档的测试永远测不到它。
     */
    @Test
    void recordedGameModeDistinguishesAbsentFromSurvival() {
        LevelMeta legacy = GSON.fromJson("{\"saveVersion\": 2}", LevelMeta.class);
        assertNull(legacy.recordedGameMode(),
                "★ 缺字段的旧存档 recordedGameMode() 必须是 null，"
                        + "而不是 \"survival\" —— 这是「命令行对旧存档仍然有效」的前提。"
                        + "若这里返回 \"survival\"，则 resolve 永远看不到 null，"
                        + "旧存档将永久无法切创造。");

        LevelMeta survivalSave = GSON.fromJson("{\"gameMode\": \"survival\"}", LevelMeta.class);
        assertEquals("survival", survivalSave.recordedGameMode(),
                "确实写了 survival 的存档必须报告 survival（区别于上面的 null）。");

        // 两个存档在 gameModeOrSurvival() 上答案相同，在 recordedGameMode() 上答案不同 ——
        // 这正是"两个取值器不能合并"的证据。
        assertEquals(legacy.gameModeOrSurvival(), survivalSave.gameModeOrSurvival(),
                "对照：两者在 gameModeOrSurvival() 上必须一致（都是生存）。");
    }

    /** 显式的空串算"没有"（旧存档被手改成 "gameMode": "" 时不得卡住启动）。 */
    @Test
    void blankRecordedValueCountsAsAbsent() {
        LevelMeta blank = GSON.fromJson("{\"gameMode\": \"\"}", LevelMeta.class);
        assertNull(blank.recordedGameMode(), "空串按「没有」处理");
        assertNull(GSON.fromJson("{\"gameMode\": \"   \"}", LevelMeta.class)
                .recordedGameMode(), "纯空白按「没有」处理");
    }

    // ============================================================ §4.2 ④可读性

    /** 【必须】每个模式都要有非空中文名，否则启动日志那一行会打出空白。 */
    @Test
    void everyModeHasANonBlankChineseDisplayName() {
        assertEquals("生存", GameMode.SURVIVAL.displayName());
        assertEquals("创造", GameMode.CREATIVE.displayName());
        for (GameMode mode : GameMode.values()) {
            assertNotNull(mode.displayName());
            assertFalse(mode.displayName().isBlank(),
                    "模式必须有显示名（PRD §4.2「可读性」）：" + mode);
        }
    }

    /** 显示名不得与落盘字面量相同 —— 否则日志与存档看不出差别。 */
    @Test
    void displayNameDiffersFromThePersistedLiteral() {
        for (GameMode mode : GameMode.values()) {
            assertFalse(mode.displayName().equals(mode.persisted()),
                    "中文显示名与落盘字面量不应相同：" + mode);
        }
    }

    // ============================================================ §5.6 顺带钉住的能力边界

    /** §5.6：创造模式<b>不给枪</b>；生存模式才发枪。 */
    @Test
    void onlySurvivalGrantsWeapons() {
        assertTrue(GameMode.SURVIVAL.grantsWeapons(), "生存模式发枪");
        assertFalse(GameMode.CREATIVE.grantsWeapons(),
                "★ PRD §5.6：创造模式不包含枪械。");
    }

    /** 创造能力（飞行 / 瞬时破坏 / 放置不消耗 / 免疫伤害）只属于创造模式。 */
    @Test
    void creativeAbilitiesAreCreativeOnly() {
        assertFalse(GameMode.SURVIVAL.grantsCreativeAbilities());
        assertTrue(GameMode.CREATIVE.grantsCreativeAbilities());
    }

    // ============================================================ 与既有守卫的相容性

    /**
     * ★<b>与 P0b 的 {@code LevelMetaDeferredFieldsTest} 相容</b>：
     * {@code gameMode} 既然<b>已经写入</b>了，就绝不能出现在 DEFERRED 名单里。
     *
     * <p>这条是交叉守卫：两份测试各自都合理，但只有这一条在
     * "有人把 gameMode 误加进 DEFERRED"时才会红。
     */
    @Test
    void gameModeIsNotInTheDeferredList() {
        assertFalse(LevelMeta.deferredFields().contains("gameMode"),
                "★ gameMode 已经写入了 level.json，不能同时出现在"
                        + "「本版本刻意未写入」名单里 —— 那会让存档说明与现实脱节"
                        + "（这正是 P0b 那份守卫要防的症状）。");
    }

    /** 落盘字面量集合与枚举成员必须一一对应，不多不少。 */
    @Test
    void thePersistedLiteralSetMatchesTheEnumConstants() {
        Set<String> literals = new HashSet<>();
        for (GameMode mode : GameMode.values()) {
            assertTrue(literals.add(mode.persisted()),
                    "落盘字面量重复：" + mode.persisted());
        }
        assertEquals(2, literals.size(),
                "PRD §4.2 规定 gameMode 只有 survival | creative 两个取值；"
                        + "多出来的取值会让手改存档产生第四种模式。当前：" + literals);
    }

    /**
     * ★★<b>{@code parse(null)} 与 {@code resolve(null, …)} 对 null 的期望<b>相反</b>。</b>
     *
     * <p>本条把两处用法钉死，因为它<b>已经被违反过一次</b>：
     * {@code SaveManager#effectiveGameMode()} 曾写成
     * {@code GameMode.resolve(GameMode.parse(existing.recordedGameMode()), configured)}，
     * 而 {@code parse(null) == SURVIVAL} 让"缺字段"被提前翻译成"已定为生存"，
     * 于是 {@code -Dskyisland.gameMode=creative} 对旧存档永久失效。
     *
     * <p>★ <b>这个错误特别难发现的原因</b>：{@code parse(null) == SURVIVAL} 让那行代码
     * <b>看起来完全合理</b>（没有 null、没有异常、类型也对），
     * 且<b>任何只测新存档的测试都会绿</b> —— 新存档走的是"字段存在"分支。
     */
    @Test
    void parseAndResolveDisagreeOnNullByDesign() {
        // 第一种用法（"这个存档的模式是什么"）：null 必须给一个能用的值
        assertEquals(GameMode.SURVIVAL, GameMode.parse(null),
                "parse(null) = SURVIVAL 是安全侧契约（§4.2 缺省即生存）。");

        // 第二种用法（"有没有定过"）：null 必须原样传下去
        assertEquals(GameMode.CREATIVE, GameMode.resolve(null, GameMode.CREATIVE),
                "★ resolve 的第一参数为 null 时表示「尚未定死」，必须让配置说话。"
                        + "若把 parse(null) 塞进去，这里会变成 SURVIVAL —— "
                        + "这正是真实发生过的缺陷。");
    }

    private static Field declaredField(String name) {
        try {
            return LevelMeta.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("LevelMeta 没有字段 " + name, e);
        }
    }
}

package com.skyisland.save;

import com.skyisland.game.GameMode;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code level.json} 的数据形状（TECH_DESIGN §N.2 的 M1 子集）。
 *
 * <p><b>为什么是"可变 POJO + public 字段"而不是 record：</b>
 * Gson 反序列化 record 依赖构造器参数名（需要 {@code -parameters} 编译参数）
 * 或 {@code @JsonAdapter}，而 POJO 直接按字段名映射，最不容易在打包后失效。
 * 存档格式的解析路径上"少一个不确定因素"比"代码更现代"重要得多。
 *
 * <p><b>M1 未纳入的字段（§N.2 有、M1 不写）：</b>{@code worldTimeSeconds} /
 * {@code dayPhase} / {@code dayCount} / {@code dayFactor}（M1 无昼夜循环）、
 * {@code resourceCores}（资源核心的累积产出属 M2）、{@code settingsSnapshot}
 * （设置系统属 M3）。这些字段在 M2/M3 追加时<b>不需要提升 {@code saveVersion}</b> ——
 * 新增可选字段对旧存档是向后兼容的。这条判断写在这里，避免将来误升版本号。
 *
 * <p>★ <b>M5a 更新</b>：{@code worldTimeSeconds} 与 {@code dayCount}
 * <b>已经落地</b>（见下方字段说明与 {@link #deferredFields()}）。
 * 上面那段 M1 描述保留原样是为了记录演进，<b>当前事实以 {@link #deferredFields()} 为准</b> ——
 * 名单与现实脱节正是 {@code LevelMetaDeferredFieldsTest} 要防的那类问题。
 */
public final class LevelMeta {

    public int saveVersion = SaveFormat.SAVE_VERSION;

    public String worldName = SaveFormat.DEFAULT_WORLD_NAME;

    public long worldSeed;

    /** 生成器稳定 ID，用于判断"同一个世界的存档是否被别的生成器接管"。 */
    public String generatorId;

    /** 生成算法版本；与当前不符时加载必须<u>警告</u>而不是静默继续（§N.2）。 */
    public int generatorVersion;

    public long createdAtMillis;

    public long savedAtMillis;

    /** 写出该存档的程序版本（诊断用，不参与兼容判定）。 */
    public String productVersion;

    /** 增量文件数量（便于不打开目录就知道"这个存档动过几个区块"）。 */
    public int modifiedChunkCount;

    /**
     * ★ M5a：一天内的时刻（秒），{@code [0, 1200)}。PRD §4.4 的 20 分钟一个昼夜。
     *
     * <p><b>为什么落盘的是时刻而不是阶段名</b>：阶段可以从时刻推出来，反过来不行。
     * 存了现在是夜晚的话，玩家在夜晚中存档再读档，就会<b>凭空丢掉整个黄昏的过渡</b>；
     * 而 {@link com.skyisland.world.DayClock#advance(double)} 允许从夜晚中直接推进到黎明。
     *
     * <p><b>旧存档缺这个字段是正常路径</b>：{@link #deferredFields} 里登记过它，
     * 缺失时由 {@code SaveManager#applyWorldTime} 落到白天开头，即升级后的默认开局。
     */
    public Double worldTimeSeconds;

    /**
     * ★ M5a：存活天数（从 1 开始）。PRD §4.4：「存活天数以黎明为结算点递增」。
     *
     * <p>用 {@code Integer} 而不是 {@code int}：它与 {@link #worldTimeSeconds} 是同一件事
     * ——<b>"没有这个字段"必须与"这个字段是 0"可区分</b>，否则旧存档会被读成"第 0 天"，
     * 而 {@code DayClock#setDayCount} 只能把它抬回 1，于是玩家刚进游戏就莫名 +1 天。
     */
    public Integer dayCount;

    /**
     * ★ 游戏模式（PRD_BLOCK_CREATIVE §4.2：{@code gameMode = survival | creative}）。
     *
     * <h3>为什么落盘类型是 {@code String} 而不是 {@code GameMode}</h3>
     * 这是被一条具体故障逼出来的取舍，<b>不要"顺手改成枚举"</b>：
     * 若让 Gson 直接把该字段反序列化成枚举，一个手改过的存档
     * （{@code "survivall"}、多空格、带 BOM）会抛 {@code JsonSyntaxException}，
     * 而 {@code SaveManager#readLevelMeta()} 在读取路径上把任何 RuntimeException
     * 都转成"跳过并记录"（§N.6「存档损坏时降级而非崩溃」）——
     * <b>一个错别字会让整个存档被判为损坏、读不出来</b>。
     * 那与 §4.2 要求的「写错值一律留在生存模式」正好相反。
     *
     * <h3>为什么默认值是 {@code null} 而不是 {@code "survival"}</h3>
     * ★ <b>曾经的实现是 {@code "survival"}，它是一个真实缺陷，已修。</b>
     * 直觉上"默认值写生存更安全"，但 Gson 反序列化<b>只覆盖 JSON 里出现过的键</b>，
     * 缺失的键保留 Java 侧初始值 ⇒ 写死字面量会让"这个存档<b>没有</b>该字段"
     * 这条信息<b>被抹掉</b>，旧存档因此被误判为"已定为生存"，
     * <b>命令行 {@code -Dskyisland.gameMode=creative} 对旧存档永久失效</b>。
     * <p>改为 {@code null} 后：<b>缺失</b>由字段值表达，<b>缺省即生存</b>由
     * {@link #gameModeOrSurvival()} 的安全侧解析表达，两者不再互相顶替。
     *
     * <p>★ <b>不要</b>把默认值"顺手"改回字面量：那正是这个缺陷的原始形态，
     * 而且它<b>不会让任何只测新存档的测试变红</b>。
     *
     * <p><b>★ 这条设计是被一条真实缺陷逼出来的（不是预防性编程）。</b>
     * 最初的实现把本字段初始化成 {@code "survival"}，理由是"缺省即生存"。
     * 结果 {@link #recordedGameMode()} 对<b>旧存档</b>返回的是 {@code "survival"} 而非 {@code null}
     * —— 因为 Gson 反序列化<b>只覆盖 JSON 里出现过的键</b>，缺失的键保留 Java 侧初始值。
     * 于是 {@link GameMode#resolve} 永远看不到 {@code null}，
     * <b>{@code -Dskyisland.gameMode=creative} 对每一个旧存档永久失效</b>，
     * 而新建存档的测试<b>永远测不到</b>（新存档走的是"字段存在"那条分支）。
     *
     * <p><b>修法：默认 {@code null}。</b>于是"缺失"与"写了值"在 Java 侧天然可区分，
     * 而"缺失时按生存处理"由 {@link #gameModeOrSurvival()} 的
     * {@link GameMode#parse} 安全侧兜底完成 ——
     * <b>两个需求由两个不同的机制承担，不再互相顶替。</b>
     *
     * <p>这就是为什么本字段的默认值<b>不能</b>写成字面量 {@code "survival"}：
     * 那个字面量看起来更"安全"，实际却<b>抹掉了"旧存档"这个信息</b>。
     * 守卫见 {@code LevelMetaGameModeTest#recordedGameModeDistinguishesAbsentFromSurvival}。
     *
     * <p>语义判断一律走 {@link GameMode#parse(String)}（安全侧：错字落生存），
     * 真正的生效决策走 {@link GameMode#resolve}（存档优先，§4.3「不可切换」的技术实现）。
     */
    public String gameMode = null;

    // ★ M5a 已实现昼夜：worldTimeSeconds 与 dayCount 现在**真的被写入**，
    //   相应地从本名单移除（这是 LevelMetaDeferredFieldsTest 逼出来的改动，
    //   不是可选的整理）。dayPhase / dayFactor 仍未写入 ——
    //   dayPhase 可以由 worldTimeSeconds 推出（冗余），dayFactor 属于
    //   「若将来改成非连续天长」才会需要的字段，当前 DayClock 不产生它。
    private static final List<String> DEFERRED = new ArrayList<>(List.of(
            "dayPhase", "dayFactor", "resourceCores", "settingsSnapshot"));

    /** 供报告引用：本版本刻意未写入的 §N.2 字段。 */
    public static List<String> deferredFields() {
        return List.copyOf(DEFERRED);
    }

    /**
     * ★ 存档里<b>已存在</b>的 {@code gameMode} 字段值，<b>不做任何解析</b>。
     *
     * <p>为什么要把"原样取值"和"解析成模式"分成两个方法：
     * {@link #recordedGameMode()} 与 {@link #gameModeOrSurvival()} 回答的是
     * <b>两个不同的问题</b>，混淆它们就会让 §4.3 的"不可切换"在某些路径上失效。
     *
     * <table border="1">
     *   <tr><th>方法</th><th>回答的问题</th><th>缺字段时</th></tr>
     *   <tr><td>{@link #recordedGameMode()}</td>
     *       <td>"这个存档<b>已经</b>把模式定死了吗？"（§4.3 存档优先）</td>
     *       <td>返回 {@code null}</td></tr>
     *   <tr><td>{@link #gameModeOrSurvival()}</td>
     *       <td>"这个存档的模式是什么？"（§4.2 缺省即生存）</td>
     *       <td>返回 {@link GameMode#SURVIVAL}</td></tr>
     * </table>
     *
     * <p>★ <b>把两者混为一谈会出的错</b>：若用 {@link #gameModeOrSurvival()} 做存档优先判断，
     * 旧存档（无该字段）会返回 {@code SURVIVAL} 而不是 {@code null}，
     * 于是 {@link GameMode#resolve} 认为"存档已定为生存"，
     * <b>命令行 {@code -Dskyisland.gameMode=creative} 对旧存档永久失效</b> ——
     * 而 §4.1 要求"新建世界时选创造"能落盘。这是一条<b>只在旧存档上出现</b>的缺陷，
     * 新建存档的测试<b>永远测不到</b>它。
     *
     * @return 小写字面量；{@code null} = 该存档里<b>没有</b>这个字段
     */
    public String recordedGameMode() {
        return gameMode == null || gameMode.isBlank() ? null : gameMode;
    }

    /**
     * 本存档的模式（§4.2：缺字段 / 错字值一律落 {@link GameMode#SURVIVAL}）。
     *
     * <p>注意这里<b>不</b>返回 {@code null}：调用方要的是"能直接拿来判断的模式"，
     * 而"字段是否存在"是 {@link #recordedGameMode()} 的问题。
     */
    public GameMode gameModeOrSurvival() {
        return GameMode.parse(gameMode);
    }

    /** 逐字段自检，供单元测试断言"存档元数据完整"。 */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (saveVersion <= 0) {
            problems.add("saveVersion 非法: " + saveVersion);
        }
        if (generatorId == null || generatorId.isBlank()) {
            problems.add("generatorId 为空");
        }
        if (generatorVersion <= 0) {
            problems.add("generatorVersion 非法: " + generatorVersion);
        }
        if (worldName == null || worldName.isBlank()) {
            problems.add("worldName 为空");
        }
        return problems;
    }

    @Override
    public String toString() {
        return "LevelMeta(v" + saveVersion + " seed=" + worldSeed
                + " generator=" + generatorId + "#" + generatorVersion
                + " modifiedChunks=" + modifiedChunkCount + ")";
    }
}

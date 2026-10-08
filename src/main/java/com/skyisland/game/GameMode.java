package com.skyisland.game;

/**
 * 游戏模式（PRD_BLOCK_CREATIVE_v1.0.md §4.2：{@code gameMode = survival | creative}）。
 *
 * <h2>为什么它必须是一个"可解析的枚举"而不是一个 String</h2>
 * 存档里的 {@code gameMode} 以 <b>String</b> 落盘（见 {@code LevelMeta#gameMode}），
 * 语义判断全在这里。这个分工不是洁癖，是被一条具体的故障逼出来的：
 *
 * <p><b>如果让 Gson 直接把 {@code level.json} 里的字符串反序列化成枚举</b>，
 * 一个手改过的存档（{@code "survivall"}、多一个空格、被某个编辑器加了 BOM）会抛
 * {@code JsonSyntaxException}，而 {@code SaveManager#readLevelMeta()} 在读取路径上
 * <b>把任何 RuntimeException 都转成"跳过并记录"</b>（§N.6「存档损坏时降级而非崩溃」）——
 * 于是<b>一个错别字会让整个存档被判为损坏、读不出来</b>。
 *
 * <p>那与 PRD §4.2 的要求正好相反：它要的是「写错值一律留在生存模式」，
 * 而 Gson 枚举反序列化给的是「写错值一律读不出来」。<b>两条路都到不了 §4.2。</b>
 *
 * <h2>★ 与 {@link Loadout} 同一条安全侧原则，但多一条"存档优先"</h2>
 * <ul>
 *   <li><b>安全侧</b>：属性缺失 / 错字 / 空串 / 大小写混写，一律落
 *       {@link #SURVIVAL}（正式玩法）。理由与 {@code M1Config#parseInfiniteReserve}
 *       完全一致：两种错法的代价不对称 —— 错字导致"没进创造"只是一次白跑，
 *       而错字导致"正式存档开局就是创造"会让 §4.3 那条不可回退的裁定当场失效。</li>
 *   <li><b>存档优先</b>：见 {@link #resolve(GameMode, GameMode)}。
 *       §4.3 裁定「模式在存档创建时确定，永不切换」，所以命令行开关<b>只能在
 *       存档尚不存在时</b>起作用，打开老存档时开关一律不生效。</li>
 * </ul>
 *
 * <h2>为什么落盘字符串用全小写</h2>
 * {@link #PERSISTED_SURVIVAL} / {@link #PERSISTED_CREATIVE} 是小写字面量，
 * 与存档里其他 stable ID 的风格一致，也让"手改存档的人一眼看得出该写什么"。
 * <b>不要</b>用 {@link #name()}（大写）落盘：那会让本枚举重命名时静默改写存档格式。
 */
public enum GameMode {

    /** 生存模式：正式玩法。有限后备弹药、按配方获取、正常受伤。<b>产品默认。</b> */
    SURVIVAL("survival", "生存"),

    /** 创造模式：无限方块、瞬时破坏、放置不消耗、可飞行、免疫伤害（PRD §5.1–§5.5）。 */
    CREATIVE("creative", "创造");

    /** 玩家可见开关的属性名（§4.1 的"模式选择"在当前架构下的注入口）。 */
    public static final String SYSTEM_PROPERTY = "skyisland.gameMode";

    /** 缺省模式 = 生存（§4.1「缺省即生存，防止手滑进创造毁档」）。 */
    public static final GameMode DEFAULT = SURVIVAL;

    /** 落盘字面量。★ 不要改成 {@link #name()}，理由见类注释。 */
    private final String persisted;

    /** 启动日志用的中文名（PRD §4.2 要求打印「游戏模式 : 生存 / 创造」）。 */
    private final String displayName;

    GameMode(String persisted, String displayName) {
        this.persisted = persisted;
        this.displayName = displayName;
    }

    /** 落盘字面量（小写），{@code level.json} 里实际写的就是它。 */
    public String persisted() {
        return persisted;
    }

    /** 启动日志用的中文名：「生存」/「创造」。 */
    public String displayName() {
        return displayName;
    }

    /**
     * 解析命令行/存档里的原始值：<b>只有显式写 {@code creative} 才切创造</b>，其余一切落 {@link #SURVIVAL}。
     *
     * <p>接受的输入：{@code null}（字段/属性没给）、空串、首尾有空白、大小写混写。
     * <b>不接受的输入一律留在生存</b>，不抛异常、不返回 null、不记警告 ——
     * 因为旧存档缺这个字段是<b>正常</b>情况（§4.2），不是错误。
     *
     * ★★ <b>调用点必须分清两处，否则会静默失效（已发生过一次）：</b>
     * <table border="1">
     *   <tr><th>用法</th><th>对 {@code null} 的期望</th><th>正确写法</th></tr>
     *   <tr><td>「这个存档/配置的模式<b>是什么</b>」</td>
     *       <td>给一个能用的值 → {@link #SURVIVAL}</td>
     *       <td>{@code parse(raw)}</td></tr>
     *   <tr><td>「这个存档<b>有没有</b>定过模式」</td>
     *       <td><b>必须是 null</b>（"没定过"与"定成生存"是两件事）</td>
     *       <td>{@code raw == null ? null : parse(raw)}</td></tr>
     * </table>
     * 本方法把 {@code null} 变成 {@link #SURVIVAL} 对第一行是<b>正确</b>的，
     * 但对第二行是<b>错的</b> —— 而因为 {@code parse(null) == SURVIVAL}
     * 恰好"看起来能用"，这个错误<b>不会让任何只测新存档的测试变红</b>。
     * 现场见 {@code SaveManager#effectiveGameMode} 的注释。
     *
     * @param raw 原始值；{@code null} 表示"没有该字段"
     */
    public static GameMode parse(String raw) {
        if (raw == null) {
            return DEFAULT;
        }
        String trimmed = raw.trim();
        for (GameMode mode : values()) {
            if (mode.persisted.equalsIgnoreCase(trimmed)) {
                return mode;
            }
        }
        return DEFAULT;
    }

    /**
     * ★ <b>决定本次运行真正生效的模式 —— 唯一决策点。</b>
     *
     * <p><b>规则：存档里有就用存档的，没有才用配置给的。</b>
     *
     * <p>这不是"谁优先"的小事，它是 §4.3「模式一旦创建，永不切换」这条裁定的
     * <b>技术实现</b>。若改成"配置优先"，那么
     * {@code -Dskyisland.gameMode=survival} 就能把一个创造存档降级成生存，
     * 而玩家在那个世界里已经用无限方块盖了一片建筑 ——
     * §4.3 说的「切回生存后玩家面前是一堆本不该存在的建筑」会<b>真的发生</b>，
     * 而且发生的方式是一个看起来完全合理的命令行参数。
     *
     * <p><b>为什么两个方向都锁（不只锁降级）</b>：升级方向（survival 存档 + creative 开关）
     * 同样违反"不可切换"，只是后果轻一些。半锁的规则会让人以为"改成创造也行"，
     * 那比完全不锁更难排查。
     *
     * @param fromSave 存档里已记录的模式；{@code null} = 该存档还没有这个字段（首次创建）
     * @param fromConfig 命令行/配置给出的模式；{@code null} = 属性没给
     */
    public static GameMode resolve(GameMode fromSave, GameMode fromConfig) {
        return fromSave != null ? fromSave : (fromConfig != null ? fromConfig : DEFAULT);
    }

    /** 本模式是否允许创造能力（飞行 / 瞬时破坏 / 放置不消耗 / 免疫伤害）。 */
    public boolean grantsCreativeAbilities() {
        return this == CREATIVE;
    }

    /** 本模式是否发放枪械（§5.6：创造模式<b>不给枪</b>）。 */
    public boolean grantsWeapons() {
        return this == SURVIVAL;
    }
}

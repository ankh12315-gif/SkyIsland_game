package com.skyisland.audio;

/**
 * M2.1 的音频事件表（Event List）—— 全工程唯一允许"点歌"的地方。
 *
 * <p><b>为什么是枚举而不是字符串 id：</b>音效 id 若散落成字符串字面量，
 * 改名时漏一处的结果不是编译错误，而是"某个被漏改的触发点上这一声突然没了"，
 * 而这类缺失在<b>无声环境</b>下无法靠人耳发现 —— 恰恰是自动化门禁的运行环境。
 * 枚举让"呼唤点与曲目表必须同步"变成编译期性质。
 *
 * <h2>事件表（每个枚举常量是一条完整规格）</h2>
 * <table border="1">
 *   <caption>M2.1 音频事件清单</caption>
 *   <tr><th>事件</th><th>触发条件</th><th>优先级</th><th>变体</th><th>分层</th></tr>
 *   <tr><td>{@link #GUN_FIRE}</td>
 *       <td>{@code CombatController.Listener#onShotFired}（每次成功击发）</td>
 *       <td>高（它是玩家操作的主反馈）</td>
 *       <td>3 轮轮换，规避连发时的"打字机效应"</td>
 *       <td>低频冲击层 + 宽带噪声爆裂层</td></tr>
 *   <tr><td>{@link #GUN_EMPTY}</td>
 *       <td>{@code onDryFire}（弹匣为空仍按左键）</td>
 *       <td><b>最高</b>：它是唯一说明"按了没开枪"的信号</td>
 *       <td>2 轮轮换</td>
 *       <td>金属击针层（无低频，便于穿透枪声被听见）</td></tr>
 *   <tr><td>{@link #RELOAD}</td>
 *       <td>{@code onReloadRequest(STARTED)}（一次统合音）</td>
 *       <td>中</td>
 *       <td>2 轮轮换</td>
 *       <td>两段机械行程（退弹匣 / 推新弹匣）</td></tr>
 *   <tr><td>{@link #HIT_ENEMY}</td>
 *       <td>{@code onEntityHit}（每次命中实体，伤害已结算）</td>
 *       <td>高</td>
 *       <td>2 轮轮换</td>
 *       <td>中频短促"命中确认"层，刻意避开枪声的低频掩蔽带</td></tr>
 *   <tr><td>{@link #PLAYER_HURT}</td>
 *       <td>每个逻辑步采样玩家生命值，下降即触发</td>
 *       <td>高</td>
 *       <td>2 轮轮换</td>
 *       <td>低频闷响层 + 下行滑音层</td></tr>
 * </table>
 *
 * <h2>M2.2 增补：UI 音（四条）</h2>
 * <table border="1">
 *   <caption>M2.2 UI 音频事件清单</caption>
 *   <tr><th>事件</th><th>触发条件</th><th>优先级</th><th>变体</th><th>分层</th></tr>
 *   <tr><td>{@link #UI_OPEN}</td>
 *       <td>背包打开成功（{@code UiStateMachine#openInventory}）</td>
 *       <td>低（信息性）</td>
 *       <td>2 轮轮换</td>
 *       <td>上行双音滑音</td></tr>
 *   <tr><td>{@link #UI_CLOSE}</td>
 *       <td>背包关闭（{@code closeInventoryScreen}）</td>
 *       <td>低</td>
 *       <td>2 轮轮换</td>
 *       <td>下行双音滑音（与 OPEN 成对）</td></tr>
 *   <tr><td>{@link #UI_MOVE}</td>
 *       <td>一次<u>成功</u>的物品搬运（取 / 放 / 合并 / 交换 / Shift 搬运）</td>
 *       <td>低</td>
 *       <td>3 轮轮换（背包里会连续点很多下，2 个变体会听出重复）</td>
 *       <td>软起音短促点击</td></tr>
 *   <tr><td>{@link #UI_DENIED}</td>
 *       <td>操作被拒绝：无空位可搬、关屏时塞不下而丢弃</td>
 *       <td>中（这是唯一说明"你的操作没生效"的信号）</td>
 *       <td>2 轮轮换</td>
 *       <td>低频短促"嗯"（不含高频，刻意与 MOVE 拉开）</td></tr>
 * </table>
 *
 * <p><b>为什么 UI 音的 baseGain 全部压在 0.50 以下：</b>这是唯一一条跨类别的混音约束。
 * 背包可能被连续点几十下，而战斗音是稀缺事件（一枪一次）。若两者同响度，
 * 翻背包会盖住枪声与受伤音 —— 而那三个音才是"我该不该退"的信息源。
 * 汇总成一句：<b>交互音给确认，战斗音给决策，确认不得盖过决策。</b>
 *
 * <p><b>为什么不做菜单导航音：</b>M2.2 的菜单是键盘驱动的，每移动一格都有视觉高亮；
 * 而设置界面的滑杆按住方向键会连着走几十格。给它配音在缺少节流设计的情况下
 * 会变成"按住方向键持续蜂鸣"，比静音更糟。导航音属 M3 的 UI polish（需先定节流口径），
 * 这里显式登记而不是漏掉。
 *
 * <h2>为什么 RELOAD 是"一次统合音"而不是 start / end 两个音</h2>
 * 换弹全程 1.2 秒（PRD 5.4.3），而人的预期是"按下 R 的那一刻就有反应"。
 * 把唯一的机械声放在开始这一侧，按顺序优于把确定性推到 1.2 秒之后。
 * M2.2 起换弹不再被移动打断（可边走边换，见 PRD 5.4.3 修订），
 * 因此更不存在"开始了却中途没有收尾"的情况 —— 一声统合音覆盖的是一条
 * 必然走完的 1.2 秒时间线，再拆一个结束音只会变成冗余的第二声。
 * M2.1 只做一条最小反馈链，因此这里刻意不把 start / end 拆成两个 event。
 */
public enum AudioEvent {

    /** 成功击发。 */
    GUN_FIRE("gun_fire", 0.90f, 3),
    /** 空仓击针。 */
    GUN_EMPTY("gun_empty", 0.60f, 2),
    /** 换弹：退弹匣 + 推新弹匣，一次播完。 */
    RELOAD("reload", 0.75f, 2),
    /** 命中实体的即时确认音。 */
    HIT_ENEMY("hit_enemy", 0.65f, 2),
    /** 玩家受伤。 */
    PLAYER_HURT("player_hurt", 0.85f, 2),

    /** 面板打开（背包）。 */
    UI_OPEN("ui_open", 0.45f, 2),
    /** 面板关闭（背包）。 */
    UI_CLOSE("ui_close", 0.45f, 2),
    /** 一次成功的物品搬运。 */
    UI_MOVE("ui_move", 0.40f, 3),
    /** 操作被拒绝（无空位可搬 / 关屏塞不下）。 */
    UI_DENIED("ui_denied", 0.50f, 2);

    private final String id;
    private final float baseGain;
    private final int variants;

    /**
     * 下一个变体的下标（轮换游标）。
     *
     * <p><b>为什么游标放在枚举里而不让调用方传：</b>轮换的意义是"同一个调用点
     * 连着开三枪要听到三个略不同的音"。若由调用方保存下标，每个调用点都要
     * 额外维护一个 int —— 而漏维护的那个调用点会变成"永远播变体 0"，
     * 它在单元测试里完全看不出来（事件照样被记录了），只有耳朵能发现。
     */
    private int cursor;

    AudioEvent(String id, float baseGain, int variants) {
        this.id = id;
        this.baseGain = baseGain;
        this.variants = variants;
    }

    /** 稳定 id（日志与将来的音频资源名共用；刻意不含中文，避免牵动点阵字模）。 */
    public String id() {
        return id;
    }

    /**
     * 基准增益（0..1）。
     *
     * <p>它表达的是"这几个音之间原本的响度关系"，与玩家的音量滑杆<b>相乘</b>
     * 而不是被滑杆覆盖 —— 否则把音效音量调到 50% 会得到一个所有音一样响的
     * 平面混音，玩家就失去了"这一声比那一声重要"的信息。
     */
    public float baseGain() {
        return baseGain;
    }

    /** 可轮换的变体数量（>= 1）。 */
    public int variants() {
        return variants;
    }

    /** 取下一个变体下标（0 .. variants-1，轮换）。 */
    public int nextVariant() {
        int v = cursor;
        cursor = (cursor + 1) % variants;
        return v;
    }

    /** 把任意整数折到合法变体区间（不会因为被多次调用而抛异常）。 */
    public int normalizeVariant(int variant) {
        int v = variant % variants;
        return v < 0 ? v + variants : v;
    }
}

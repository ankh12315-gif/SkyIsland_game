package com.skyisland.player;

import com.skyisland.item.GunSpec;
import com.skyisland.item.Item;
import com.skyisland.item.ItemRegistry;
import com.skyisland.physics.AABB;
import com.skyisland.physics.DdaRaycaster;
import com.skyisland.physics.RaycastHit;
import com.skyisland.util.Coords;
import com.skyisland.util.Log;
import com.skyisland.world.World;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;
import org.joml.Vector3d;
import org.joml.Vector3fc;

import java.util.List;

/**
 * 玩家：碰撞 / 重力 / 跳跃 / 挖掘 / 放置 / 虚空判定（M1 指令 B8–B13）。
 *
 * <p><b>规格（TECH_DESIGN §I.1）：</b>碰撞箱 0.6 × 1.8 × 0.6，眼高 1.62，
 * 重力 32 格/秒²，起跳初速 8.95 格/秒（跳跃高度 1.25 格），行走 4.317 格/秒，
 * 向下终端速度 60 格/秒，reach 5.0。
 * 位置语义是<b>脚底中心</b>（§I.4），因此 AABB 由 {@code (x, y, z)} 直接推出。
 *
 * <p><b>碰撞求解：分轴 + 子步进。</b>
 * <ul>
 *   <li><b>分轴</b>（Y → X → Z）：一次只动一个轴，撞到就只把该轴的速度清零，
 *       于是"贴墙下滑""贴墙走"这类行为自然出现，不需要额外特例。
 *       一次性做三维求解会需要处理"到底该往哪个方向推"的歧义，反而更容易出角落卡死。</li>
 *   <li><b>子步进</b>：单轴单次推进不超过 0.4 格。终端速度 60 格/秒下一帧位移可达 1.0 格，
 *       不分子步会直接穿过一格厚的墙（隧穿）。这是"看起来没问题、偶发一次穿透"的典型来源。</li>
 * </ul>
 *
 * <p><b>本类不做的事（M1 范围内明确排除）：</b>sprint / crouch / 游泳 / 梯子 / 击退 /
 * 饥饿 / 生命值。虚空坠落是 M1 唯一的"死亡"，且只触发重生，不扣血、不掉落物品
 * （与 PRD 的完整死亡惩罚存在差距，已记入 M1 报告）。
 */
public final class Player {

    // ------------------------------------------------------------ 规格常量
    // 全部取自 TECH_DESIGN §I.1 的规格表。改任何一个都必须同步改那里，
    // 因为跳跃高度、终端速度这些值之间是有算术关系的（见下）。
    public static final double HALF_WIDTH = 0.3;
    public static final double HEIGHT = 1.8;
    public static final double EYE_HEIGHT = 1.62;
    public static final double GRAVITY = 32.0;

    /**
     * 起跳初速度。跳跃高度 = v² / (2g) = 8.95² / 64 = <b>1.25 格</b>。
     *
     * <p>这个 1.25 是"能跳上 1 格、跳不上 2 格"的临界余量：方块高度 1.0，
     * 留 0.25 格冗余给浮点误差与子步进。若改成 8.0，跳跃高度只有 1.0 格，
     * 会出现"贴着台阶边缘上不去"的经典手感问题。
     */
    public static final double JUMP_VELOCITY = 8.95;

    public static final double WALK_SPEED = 4.317;

    /** 向下终端速度（格/秒，正数表示大小）。防止单步位移过大导致隧穿。 */
    public static final double TERMINAL_VELOCITY = 60.0;

    public static final double REACH = 5.0;

    // ------------------------------------------------------------ 创造模式（M4-S8b，PRD §5）

    /**
     * 飞行水平速度（格/秒）。
     *
     * <p>取 {@link #WALK_SPEED} 的约 2 倍：飞行是用来<b>到达</b>的，
     * 按走路速度横穿自己刚建的东西会让人失去飞的动机。
     * PRD §5.4 没有指定数值，这里取一个"明显快于走、又慢到还能精确停在某一格"的值。
     */
    public static final double FLY_SPEED = 9.0;

    /** 飞行上升 / 下降速度（格/秒）；两者等速，垂直定位才对称可控。 */
    public static final double FLY_VERTICAL_SPEED = 6.0;

    /**
     * 双击空格的判定窗口（秒）。
     *
     * <p>0.3 是通用的人机工效值；它必须<b>明显大于</b>一次单跳的按键时长
     * （否则"想跳一下"会被判成双击而意外起飞），又必须<b>明显小于</b>
     * 两次意图跳跃之间的间隔（否则"跳→落地→再跳"会被误判成双击）。
     */
    public static final double DOUBLE_TAP_SECONDS = 0.3;

    /** 飞行垂直速度的逼近系数（与水平同口径：越大越跟手）。 */
    private static final double FLY_VERTICAL_ACCEL = 12.0;

    /**
     * 创造模式"按住左键连续破坏"的间隔（秒）。
     *
     * <p>PRD §5.2 只说"破坏瞬时"，没说"长按时的重复率"。
     * 若按逻辑步重复，按住左键扫一圈会以 <b>60 格/秒</b> 摧毁地形 ——
     * 那不是"瞬时"，那是"不可控"。取 0.15 秒（约 6.7 格/秒）让长按仍然是
     * 一个可以瞄准的动作，同时逐格点击不受任何影响（点一次立刻破坏）。
     */
    public static final double CREATIVE_BREAK_COOLDOWN_SECONDS = 0.15;

    // ------------------------------------------------------------ 瞄准（M2）

    /**
     * <b>★ 这里曾经有两个 ADS 全局常量（M3 接线修正已删除）：</b>
     * {@code AIM_MOVE_SPEED_RATIO = 0.60} 与 {@code AIM_FOV_RATIO = 45.0 / 70.0}。
     *
     * <p>它们的问题不是"值写错了"，而是<b>它们让 {@code GunSpec} 的两个字段变成死数据</b>：
     * v2 §10 的武器表里，手枪的 ADS 是 45° / ×0.60，冲锋枪是 48° / ×0.65 ——
     * 两个字段一直按表填进了注册表（{@code ItemRegistryTest} 也逐值断言过），
     * 但瞄准路径读的是这两个常量，因此<b>冲锋枪的 ADS 从来没有生效过</b>：
     * 拿 SMG 按右键，得到的是 45° / ×0.60 的手枪手感。
     * 文档承诺与实机行为不一致，而所有测试都是绿的 —— 因为测试断言的也是常量。
     *
     * <p>现在 ADS 的两个参数唯一来源是手持枪的 {@link com.skyisland.item.GunSpec}
     * （见 {@link #fovScale()} 与 {@link #aimMoveSpeedMult()}）。
     * 于是"两把枪 ADS 手感不同"是数据决定的，加第三把枪不需要动本类一行。
     *
     * <p><b>为什么 FOV 存"绝对目标角"而不是"收窄倍率"（v2 §5.2）：</b>
     * 设置的 FOV 是玩家可调的（60–90，默认 70）。PRD 的 70→45 描述的是
     * <u>默认值下</u>的实例；若写死绝对值 45，把基础 FOV 调成 90 的玩家一按右键，
     * 视场反而会<b>变宽</b>。按"目标角 ÷ 基础角"换算则在任何基础 FOV 下都保持
     * "瞄得更近"的语义。
     */

    /** 跳跃高度（格）—— 由 v²/(2g) 推出，供 HUD 与自测断言引用而不必各算一遍。 */
    public static final double JUMP_HEIGHT = (JUMP_VELOCITY * JUMP_VELOCITY) / (2 * GRAVITY);

    /** 水平加速度（每秒衰减系数）。地面响应快，空中控制弱 —— 让跳跃有重量感。 */
    private static final double GROUND_ACCEL = 18.0;
    private static final double AIR_ACCEL = 6.0;

    private static final double COLLISION_EPS = 1e-4;
    private static final double MAX_SUBSTEP = 0.4;
    private static final double GROUND_PROBE_DEPTH = 0.02;

    /**
     * lastSafePosition 的更新节流间隔。
     *
     * <p><b>PRD 5.3.1 明文规定 0.5 秒</b>（"稳定站立 ≥ 0.5 秒后更新"）；
     * M1 实现时写成了 0.25 秒，是 MVP_AUDIT 的 G-gap（MVP-SURV-012）之一，
     * Pre-M2 Corrective Closure 修正为 0.5。
     */
    private static final double SAFE_POSITION_INTERVAL = 0.5;

    /** 坠落伤害阈值（格）：坠落格数 ≤ 该值不计伤害（PRD 5.3）。 */
    public static final double FALL_DAMAGE_THRESHOLD = 3.0;

    /**
     * 坠落距离比较时使用的容差。
     *
     * <p>碰撞求解会给脚底留 {@code 1e-4} 的贴面余量（{@link #COLLISION_EPS}），
     * 因此"从某一格落到下一格"的实测落差是 {@code 1.0000 - 1e-4} 量级而非精确的 1。
     * 不补这个容差，一次干净的 4 格坠落会被算成 3.9999 格 → {@code floor(0.9999) = 0}，
     * 伤害凭空消失。
     */
    private static final double FALL_EPSILON = 1e-6;

    // ------------------------------------------------------------ 生命（M2）

    /** 生命上限（PRD 5.3：20 点 = 10 颗心）。 */
    public static final int MAX_HEALTH = 20;

    /** 死亡到重生的等待时间（PRD 5.3：「生命降至 0 → 播放死亡提示 → 3 秒后重生」）。 */
    public static final double RESPAWN_DELAY_SECONDS = 3.0;

    /** 重生点螺旋搜索半径（PRD 5.3.1 B：「沿 y = 64 平面螺旋搜索，半径 ≤ 16 格」）。 */
    public static final int RESPAWN_SEARCH_RADIUS = 16;

    /** 无合法重生点时的兜底地台方块（PRD 5.3.1 B：「生成 1 格临时木质地台，保证永不软锁」）。 */
    public static final String RESPAWN_FALLBACK_BLOCK_ID = "skyisland:oak_planks";

    private final Camera camera = new Camera();
    private final Inventory inventory = new Inventory();

    /**
     * 当前是否处于瞄准状态（持枪 + 按住右键）。
     *
     * <p>它是<b>派生状态</b>而不是可以任意设置的开关：每步由
     * {@code 意图的 useHeld} 与 {@code 手持物是不是枪} 共同决定。
     * 允许外部直接置位的话，就会出现"手里没枪但 FOV 是 45"这种自相矛盾的状态，
     * 而那时没有任何一条代码路径会把它改回来。
     */
    private boolean aiming;

    /**
     * "当前已生效的 ADS 视场是按哪一把枪算出来的"（物品 runtimeId）。
     *
     * <p><b>它解决的是"瞄准途中换枪"这条路径：</b>瞄准时按 1/2/3 切枪，
     * {@code useHeld} 一直是 true、{@code aiming} 一直是 true ——
     * 只比较 {@code want == aiming} 的话 {@link #applyFov()} 不会被调用，
     * 于是手枪（ADS 45°）切到冲锋枪（ADS 48°）后视场仍停在 45°，
     * 直到玩家松手再按一次右键才对。握手感像是"切枪后有一次无响应"。
     *
     * <p>存 runtimeId 而不是 {@code GunSpec} 引用：runtimeId 是"同一把枪"的
     * 稳定标识（v2 §11.2），而 {@code Item} 的 spec 是不可变值 —— 两者等价，
     * 但 runtimeId 是 int，比较更便宜，也不会因为线程/生命周期问题持有对象。
     * 不瞄准时置 {@code -1}（与 {@code ItemRegistry.EMPTY_RUNTIME_ID} 之外的哨兵值区分，
     * 因为空手 runtimeId 是 0，会与"瞄准空手"混淆）。
     */
    private int aimFovRuntimeId = -1;

    /**
     * 基础（非瞄准）视场角，来自设置。
     *
     * <p>存它是因为"瞄准"是相对基础值的收窄：{@code 实际 FOV = baseFov × fovScale()}。
     * 若只存"当前 FOV"，从瞄准退出时就没有可恢复的基准，只能再回查设置 ——
     * 那会让 Player 反向依赖设置模块。
     */
    private double baseFovDeg = Camera.DEFAULT_FOV_DEG;

    private final Vector3d position = new Vector3d();
    private final Vector3d velocity = new Vector3d();
    private final Vector3d lastSafePosition = new Vector3d();

    // ---- 生命状态（M2，PRD 5.3）----

    /**
     * 伤害来源。把「怎么死的」变成一句可读的事实。
     *
     * <p><b>为什么需要它：</b>{@link #hurt} 此前<b>不打印任何东西</b>，于是日志里只剩一个
     * 总数（首轮试玩的 {@code player_hurt=5}）——「被近战怪咬」与「摔落」在证据上
     * 完全无法区分，而这正是复盘时会被反复追问的第一个问题（见
     * {@code docs/testing/M2_1_PLAYTEST_EVIDENCE_2026-09-22.md} 第 4.1 节）。
     *
     * <p><b>本枚举只承载归因，不参与任何数值计算</b>：它既不改变伤害量，
     * 也不改变死亡判定，更不改变「虚空走 {@code die()} 而不是 {@code hurt()}」这条既有取舍。
     */
    public enum DamageCause {
        /** 近战怪咬击（{@code MeleeMonster.tick}）。 */
        MELEE("近战"),
        /** 落地结算的坠落伤害（{@link #fallDamageFor}）。 */
        FALL("坠落"),
        /** 坠入虚空（{@code checkVoid} → {@code die}），按 PRD 5.3 不结算普通坠落伤害。 */
        VOID("虚空"),
        /** 其它 / 未标注来源；也是既有调用点与测试的默认值。 */
        GENERIC("其它");

        private final String label;

        DamageCause(String label) {
            this.label = label;
        }

        /** 写进日志的中文名。 */
        public String label() {
            return label;
        }
    }

    private int health = MAX_HEALTH;
    private boolean dead = false;
    private double deathTimer = 0;

    /** 最近一次受到的伤害值（HUD 与自测用，不做伤害数字飘字）。 */
    private int lastDamageAmount = 0;

    /**
     * 最近一次受到伤害的<b>来源</b>；本次会话尚未受过伤时为 {@code null}。
     *
     * <p>纯状态记录：由 {@link #hurt} 与虚空致死路径写入，只被日志与自测读取。
     * 刻意<b>不</b>在重生时清零 —— 重生之后"最近一次伤害"仍然是那次致死伤，
     * 自测因此能在收尾阶段读它来回答"这一局是怎么死的"。
     */
    private DamageCause lastDamageCause;

    /**
     * 最近一次死亡的掉落物落点。
     *
     * <p>普通死亡 = 死亡点；虚空死亡 = lastSafePosition（PRD 5.3.1 A：
     * 「掉落物生成在 lastSafePosition 附近……严禁掉入虚空」）。
     */
    private final Vector3d lastDeathDropPosition = new Vector3d();

    /** 上一步的位置快照，仅供渲染插值（§C.1）。 */
    private final Vector3d previousPosition = new Vector3d();

    /** DDA 射线方向的可复用缓冲（避免每逻辑步分配一个 Vector3d）。 */
    private final Vector3d rayDirection = new Vector3d();

    private final double spawnX;
    private final double spawnY;
    private final double spawnZ;

    private boolean onGround;

    /**
     * 连续处于「合法站立」状态的累计时长（秒）。
     *
     * <p><b>重置口径（PRD 5.3.1 / L449 核对后改判为 B 案）：</b>PRD 列出的排除项只有
     * 「跳跃 / 坠落 / 虚空坠落」三种，三者共同的表现就是 {@code onGround == false}，
     * 本就会让计时作废；PRD <u>没有</u>要求「换一个方块列就重新计时」，
     * 而 TECH_DESIGN §I.6 要防的危害也只是「把悬空位置记成安全位置」。
     * 因此这里<b>只在离地或脚下不合法时清零</b>，行走 / 站立在同一面上的时间允许累计 ——
     * 否则从出生点一路走到崖边坠亡，lastSafePosition 会一直停在出生点，
     * 玩家被扔回很远的地方（与「最近一次合法站立位置」的语义矛盾）。
     */
    private double safePositionTimer;
    private int deaths;
    private double walkDistance;

    // ---- 坠落状态（Pre-M2 Corrective Closure E1）----

    /** 本次滞空已累计的下坠距离（格）。只在空中累加，落地结算后清零。 */
    private double fallDistance;

    /** 最近一次落地结算出的坠落伤害。M2 接入生命值后由它驱动 {@code damage()}。 */
    private int lastFallDamage;

    /** 最近一次落地结算时的实测坠落距离（格）。保留下来是为了让"距离→伤害"可被断言与排查。 */
    private double lastFallDistance;

    /**
     * 站立探测命中时，脚下那块地面所对应的<b>最终站立高度</b>（脚底 y）。
     *
     * <p><b>为什么需要它：</b>{@link #updateGroundState} 用的是<b>向下前瞻探测</b>
     * （{@code GROUND_PROBE_DEPTH = 0.02} 格）：只要脚底离地面 ≤ 0.02 格就判"站着"。
     * 于是探测判"站着"的那一刻，玩家其实<u>还悬在地面上方最多 0.02 格</u> ——
     * 这段间隙是真实会落下去的距离，但位移还没发生，增量式累加拿不到它。
     * 记下这个高度，结算时把残余间隙补进坠落距离，落差才等于标称格数。
     */
    private double groundRestY = Double.NaN;

    /**
     * 视角换算率：度 / 像素（M1.5 规格第 7 条）。
     *
     * <p><b>为什么它是玩家的状态而不是相机常量：</b>灵敏度倍数由用户在设置界面调整，
     * 且要求<b>立即生效</b>。把"基准 × 倍数"的结果存进玩家，注入点在
     * {@code SkyIslandGame#applySettings} 一处，之后每个逻辑步读的都是最新值 ——
     * 若改成每次读设置对象，物理层就要依赖设置模块（依赖方向变差），
     * 而且"改完设置何时生效"会变成"看哪一层缓存了旧值"这种难查的问题。
     *
     * <p>默认值等于 {@link Camera#SENSITIVITY_DEG_PER_PIXEL}，因此
     * <b>灵敏度 = 1.0 时本阶段的行为与 M1 完全一致</b>，M1 的 LOOK 断言不受影响。
     */
    private double lookDegPerPixel = Camera.SENSITIVITY_DEG_PER_PIXEL;

    // ---- 创造模式能力（M4-S8b，PRD §5.2 – §5.5）----

    /**
     * 创造能力总开关：瞬时破坏 / 放置不消耗 / 免疫伤害 / 虚空不死 / 可飞行，
     * 五条都由它一个开关控制（PRD §5「主理人全选，五项全做」）。
     *
     * <p><b>为什么是 {@code Player} 上的一个布尔，而不是让它去问游戏模式：</b>
     * 物理层不该依赖存档层。模式由 {@code SaveManager#effectiveGameMode()} 在装配期
     * 一次性定死（PRD §4.3 模式锁定），这里只持有那个决定的<b>结果</b> ——
     * 与 {@code CombatController#setReserveMode} 同一条口径。
     * 反过来若让 {@code Player} 每步去查模式，"本局会不会中途变"就会变成一个
     * 谁也说不清的问题（存档读两次可能给出两个答案）。
     */
    private boolean creativeMode;

    /**
     * ★ <b>本次运行内的创造会话</b>（PRD_BLOCK_CREATIVE §4.3 于 2026-10-08 由主理人修订）。
     *
     * <p>§4.3 原本裁定「模式在存档创建时确定，永不切换」。主理人要求
     * 「双击空格即进创造并直接起飞」，而这与那条裁定直接冲突 ——
     * 裁定的两条理由里，第②条（存档没有"这个方块是创造来的"标记，
     * 要真支持就是存档格式的破坏性变更）**仍然成立且无法绕过**。
     *
     * <p>⇒ 采用的折中：**会话不写进存档**。
     * <ul>
     *   <li>{@code level.json} 的 {@code gameMode} 全程不动，仍是 survival
     *       ⇒ §4.2「存档必须记录模式」与 §4.3「模式不可改」两条【必须】完整保留；</li>
     *   <li>退出游戏再进来，自动回到生存；</li>
     *   <li>仍会残留的：<b>用无限方块盖的建筑会留在世界里</b>。这是 §4.3
     *       理由①的残留风险，在不改存档格式的前提下<b>无法消除</b> ——
     *       主理人已知情并选择接受。</li>
     * </ul>
     *
     * <p>★ <b>为什么它必须与 {@link #creativeMode} 分成两个字段：</b>
     * 二者的<b>退出路径不同</b>。{@code creativeMode} 由存档定死，没有退出路径；
     * 本会话可以退出。若只用 {@code creativeMode} 一个布尔来表示"能不能退"，
     * 创造存档（也就是上一轮做的「SkyIsland 创造.exe」那个世界）会被双击空格
     * **退出创造** —— 那就是单键绕过 §4.3，比不做这个功能严重得多。
     */
    private boolean creativeSession;

    /**
     * 是否允许开启创造会话。
     *
     * <p>只有**生存存档**才允许（由装配期按 {@code effectiveGameMode()} 设定）。
     * 创造存档把它关掉，于是那个世界的双击空格行为与本功能引入之前<b>完全一致</b>
     * （只切飞行，没有退出路径）—— 这条不能省，省了就是 §4.3 的静默破口。
     */
    private boolean creativeSessionAllowed;

    /**
     * 创造会话状态变化时回调。<b>不带参数</b>，由监听方读 {@link #isCreativeSession()}。
     *
     * <p>★ 为什么不是 {@code BooleanConsumer}：本机 JDK 25 安装的
     * {@code java.util.function} 里<b>缺了 {@code BooleanConsumer} 与
     * {@code BooleanFunction} 两类</b>（{@code jimage list} 实测：整个包只有
     * {@code BooleanSupplier} 一个 Boolean*）。写那个类型会直接编译不过。
     * <p>更重要的是<b>它本来就更差</b>：回调参数只是"现在的状态"，
     * 而状态已经是 {@code Player} 的字段了 —— 让监听方自己读，
     * 就不会出现"参数与字段不一致"这种半途状态。
     */
    private Runnable creativeSessionListener;

    /** 是否正在飞行（PRD §5.4）。只有创造模式下才可能为 {@code true}。 */
    private boolean flying;

    /** 双击空格检测：上一次"跳键按下沿"的时刻（{@link #clockSeconds} 口径）。 */
    private double lastJumpTapSeconds = Double.NEGATIVE_INFINITY;

    /** 跳键在本步之前是否已经按住（用于取按下沿）。 */
    private boolean jumpWasHeld;

    /**
     * 玩家内部时钟（秒）。只为双击判定服务 ——
     * 它是"逻辑步累加"而不是墙钟，于是<b>帧率与逻辑步长的任何变化都不影响双击窗口</b>。
     */
    private double clockSeconds;

    /** 是否已经因坠入虚空而被"停在虚空底部"（创造模式，只用于把日志压成一条）。 */
    private boolean parkedInVoid;

    /** 上一次创造模式破坏的时刻（{@link #clockSeconds} 口径），用于长按重复率。 */
    private double lastCreativeBreakSeconds = Double.NEGATIVE_INFINITY;

    /** 创造模式免疫掉的伤害次数（PRD §5.5）。 */
    private long damageNegated;

    // ---- 挖掘状态（B10）----
    private boolean mining;
    private int miningX = Integer.MIN_VALUE;
    private int miningY;
    private int miningZ;
    private double miningProgressSeconds;
    private String miningTargetId = "-";
    private long blocksBroken;

    // ---- 放置状态（B11）----
    private long blocksPlaced;
    private int placementRejections;
    private String lastPlacementMessage = "(尚未尝试放置)";

    /** 上一步的射线命中结果，供 HUD 显示"瞄准的方块"。 */
    private RaycastHit currentTarget;

    public Player(double spawnX, double spawnY, double spawnZ) {
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.position.set(spawnX, spawnY, spawnZ);
        // previousPosition 必须一起播种：否则第一帧渲染会从 (0,0,0) 插值到出生点，
        // 画面在一瞬间从世界原点"飞"到出生点。
        this.previousPosition.set(spawnX, spawnY, spawnZ);
        this.lastSafePosition.set(spawnX, spawnY, spawnZ);
        this.camera.setPosition(spawnX, spawnY + EYE_HEIGHT, spawnZ);
        this.camera.updateView();
    }

    // ============================================================ 查询

    public Camera camera() {
        return camera;
    }

    public Inventory inventory() {
        return inventory;
    }

    public Vector3d position() {
        return position;
    }

    /** 上一次合法落脚点（拷贝，避免调用方改到内部状态）。 */
    public Vector3d lastSafePosition() {
        return new Vector3d(lastSafePosition);
    }

    public Vector3d velocity() {
        return velocity;
    }

    public boolean onGround() {
        return onGround;
    }

    public int deaths() {
        return deaths;
    }

    public double walkDistance() {
        return walkDistance;
    }

    public long blocksBroken() {
        return blocksBroken;
    }

    public long blocksPlaced() {
        return blocksPlaced;
    }

    public RaycastHit currentTarget() {
        return currentTarget;
    }

    public boolean isMining() {
        return mining;
    }

    /** 挖掘进度 [0,1]，供 HUD 与自测读取（B10 允许先用简单 debug 反馈）。 */
    public double miningProgressFraction() {
        if (!mining || currentTarget == null) {
            return 0;
        }
        Block b = BlockRegistry.byRuntimeId(currentTarget.blockRuntimeId());
        float h = b.hardness();
        if (!Float.isFinite(h) || h <= 0) {
            return 0;
        }
        return Math.min(1.0, miningProgressSeconds / h);
    }

    public String miningTargetId() {
        return miningTargetId;
    }

    public String lastPlacementMessage() {
        return lastPlacementMessage;
    }

    public int placementRejections() {
        return placementRejections;
    }

    public AABB bounds() {
        return AABB.ofFeetCenter(position.x, position.y, position.z, HALF_WIDTH, HEIGHT);
    }

    /** 供世界层做"放置会不会塞进玩家身体"的判定（依赖方向：玩家 → 世界，单向）。 */
    public boolean occupiesBlock(int bx, int by, int bz) {
        return bounds().intersectsBlock(bx, by, bz);
    }

    public Vector3d eyePosition() {
        return new Vector3d(position.x, position.y + EYE_HEIGHT, position.z);
    }

    // ============================================================ 主更新

    /**
     * 推进一个逻辑步。
     *
     * <p>顺序固定（与 TECH_DESIGN §C.2 一致）：视角 → 意图转速度 → 跳跃 →
     * 重力 → 分轴位移与求解 → 站立判定 → 虚空判定 → 挖掘/放置。
     * 顺序不可随意调换：例如"先挖掘后移动"会让玩家用上一帧的位置去挖方块；
     * 而"重力之后才跳跃"会让起跳初速在同一个逻辑步里先被重力削掉一截，
     * 跳跃高度随逻辑步长漂移。
     */
    public void step(World world, PlayerIntent intent, double dt) {
        previousPosition.set(position);   // 渲染插值的基准（TECH_DESIGN §C.1）

        // ---- 死亡期间只推进倒计时 ----
        // 已倒下的玩家不得再移动 / 挖掘 / 放置：死亡必须是一个真实状态，
        // 而不只是一个盖在画面上的浮层。否则「3 秒后重生」就只是装饰。
        if (dead) {
            updateDeathTimer(world, dt);
            refreshCamera();
            return;
        }

        updateLook(intent);
        updateAiming(intent);
        // ★ 飞行开关必须在移动之前判定：本步起飞就要用飞行的水平速度与无重力。
        updateFlightToggle(intent, dt);
        applyMovementInput(intent, dt);
        if (flying) {
            // ★ 飞行时"跳跃 + 重力"整条被"垂直速度可控"替代，而不是叠加在上面。
            applyFlightVertical(intent, dt);
        } else {
            applyJump(intent);
            applyGravity(dt);
        }
        // 本步开始时的滞空状态 == 上一步结束时的状态；必须在位移之前取，
        // 否则"落地那一步"会被当成"本来就站在地上"，最后一步的下坠量就丢了。
        boolean airborneAtStepStart = !onGround;
        double yBeforeIntegration = position.y;
        moveAllAxes(world, dt);
        updateGroundState(world);
        updateFallState(world, yBeforeIntegration - position.y, airborneAtStepStart);
        updateSafePosition(world, dt);
        checkVoid(world);
        updateMining(world, intent, dt);
        handlePlacement(world, intent);
        refreshCamera();

        if (intent.respawnPressed()) {
            Log.info("[玩家] 收到强制重生（F9）。");
            respawn(world, "强制重生");
        }
    }

    private void updateLook(PlayerIntent intent) {
        if (intent.hasLook()) {
            camera.addLook(intent.lookDeltaX(), intent.lookDeltaY(), lookDegPerPixel);
        }
    }

    // ============================================================ 瞄准（M2）

    /**
     * 依 PRD 5.4.3「瞄准」行更新瞄准状态：<b>持枪 + 按住右键</b>才算瞄准。
     *
     * <p>"手里必须是枪"这一条不是废话：右键在 M1 是放置方块，在 M2 是瞄准。
     * 若只看按键，那么手持泥土按右键时玩家会同时"放置方块并进入瞄准"——
     * FOV 突然收窄而手里只有一块泥土。把持有物并进判据之后，
     * 两个语义在同一次按键里自动互斥，不需要在放置代码里再写一次"如果拿的是枪就别放"。
     *
     * <p><b>★ 换枪也要重算 FOV（M3 接线修正）：</b>见 {@link #aimFovRuntimeId}。
     * 判据是"瞄准状态没变<b>且</b>手上那把枪没变"，而不是只看瞄准状态。
     */
    private void updateAiming(PlayerIntent intent) {
        ItemStack stack = inventory.selectedStack();
        Item held = stack.item();
        boolean want = intent.useHeld() && held.isGun();
        int fovRuntimeId = want ? stack.itemRuntimeId() : -1;
        if (want == aiming && fovRuntimeId == aimFovRuntimeId) {
            return;
        }
        boolean entering = want && !aiming;
        boolean leaving = aiming && !want;
        aiming = want;
        aimFovRuntimeId = fovRuntimeId;
        applyFov();
        Log.info("[玩家] 瞄准 %s（手持 %s）：FOV %.1f° / 基础 %.1f°（ADS 目标 %.1f°），移动速度 %.0f%%",
                entering ? "开始" : leaving ? "结束" : "换枪重算",
                held.id(), camera.fovDeg(), baseFovDeg,
                want ? held.gun().aimFovDeg() : baseFovDeg,
                aimMoveSpeedMult() * 100);
    }

    public boolean isAiming() {
        return aiming;
    }

    /**
     * 当前生效的 FOV 相对基础值的倍率：瞄准时 {@code min(1, 本枪 ADS 目标角 ÷ 基础 FOV)}，
     * 否则 1.0。供 HUD 与自测引用。
     *
     * <p>取 {@code min(1, …)} 是"瞄准只许变窄、不许变宽"的结构性保证：
     * 当基础 FOV 已经比 ADS 目标角还小时（例如玩家把 FOV 调到 60、拿的是手枪 45°→0.75
     * 仍是收窄；但若某把枪的 ADS 目标角 ≥ 基础 FOV，例如基础 60 的枪 ADS 目标是 65），
     * 直接相乘会让玩家按右键后视野<b>变宽</b>——一个明显是 bug 的观感。
     */
    public double fovScale() {
        if (!aiming) {
            return 1.0;
        }
        GunSpec spec = heldGunSpec();
        if (spec == null || !(baseFovDeg > 0)) {
            return 1.0;
        }
        return Math.min(1.0, spec.aimFovDeg() / baseFovDeg);
    }

    /**
     * 手持枪械的 ADS 移速倍率；非持枪为 1.0（不减速）。
     *
     * <p>它作用在<b>目标速度</b>上而不是作用在结果速度上：这样加速、减速、
     * 空中惯性都按同一条曲线走，松开右键时也不会出现"瞬间弹回原速"的突兀感。
     * （这条设计在 M2 就是这样，M3 只把"值从哪来"从常量换成枪的数据。）
     */
    private double aimMoveSpeedMult() {
        GunSpec spec = heldGunSpec();
        return spec == null ? 1.0 : spec.aimMoveSpeedMult();
    }

    /** 当前手持枪械的规格；手持非枪械（或空槽）时为 {@code null}。 */
    private GunSpec heldGunSpec() {
        return inventory.selectedStack().item().gun();
    }

    public double baseFovDeg() {
        return baseFovDeg;
    }

    /**
     * 设置基础 FOV（设置界面改值 / 启动时应用设置）。
     *
     * <p>立刻生效：设置页调完 FOV 若还处在瞄准中，下一次 {@code step} 之前
     * 画面就会用旧值渲染一帧 —— 这正是 M1.5 报告里"改完立即生效"要保证的事。
     */
    public void setBaseFovDeg(double fov) {
        baseFovDeg = fov;
        applyFov();
    }

    private void applyFov() {
        camera.setFovDeg(baseFovDeg * fovScale());
    }

    // ============================================================ 视角灵敏度（M1.5）

    /** 当前生效的"度 / 像素"。 */
    public double lookDegPerPixel() {
        return lookDegPerPixel;
    }

    /**
     * 设置"度 / 像素"。非有限值或非正值一律回落到 M1 的基准值 ——
     * 一个 0 或负数的换算率会让视角完全失灵，而且它是可持久化的设置，
     * 一旦写进文件就会每次都复现，因此这里必须兜住。
     */
    public void setLookDegPerPixel(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            Log.noteWarning("Player", "非法的视角换算率 " + value + "，回落基准 "
                    + Camera.SENSITIVITY_DEG_PER_PIXEL);
            lookDegPerPixel = Camera.SENSITIVITY_DEG_PER_PIXEL;
            return;
        }
        lookDegPerPixel = value;
    }

    /**
     * 把"想往哪走"变成水平速度。
     *
     * <p><b>本方法必须处理"没有移动输入"这一情形，而不是提前 return。</b>
     * M1 的写法是 {@code if (!intent.hasMovement()) return;}，于是松开按键之后
     * <b>没有人再把 {@code velocity.x/z} 拉回 0</b> —— 玩家会以
     * {@link #WALK_SPEED} 一直滑行下去，直到撞墙或被碰撞归零为止。
     * 这是一个只在"真正用手玩"时才会暴露的缺陷：M1 的脚本化自测每一步都显式给出意图，
     * 从未验证过"松开之后会不会停"，因此 27 项断言全绿也照样漏掉了它。
     * （发现途径：人工试玩反馈"按一下方向键之后会一直平移"，M1.5 收尾阶段。）
     *
     * <p><b>地面与空中的处置刻意不同：</b>
     * 地面上松开按键立刻按同一个 {@link #GROUND_ACCEL} 指数衰减到静止（加速与减速对称，
     * 滑行距离约 {@code v/k = 4.317/18 ≈ 0.24} 格，手感干净）；
     * 空中松开按键<b>保留水平动量</b> —— 跳到一半松手会"空中刹车"是体素生存游戏里
     * 明确的手感错误，落地后再由地面分支收住。
     */
    private void applyMovementInput(PlayerIntent intent, double dt) {
        double dirX = 0;
        double dirZ = 0;
        if (intent.hasMovement()) {
            Vector3fc forward = camera.forward();
            Vector3fc right = camera.right();

            // 水平前向量：把相机的俯仰投影掉，否则抬头看天时前进会变慢（甚至原地不动）
            double fx = forward.x();
            double fz = forward.z();
            double flen = Math.sqrt(fx * fx + fz * fz);
            if (flen < 1e-6) {
                // 垂直俯视：前向退化，用 yaw 重新推一个水平方向
                double yaw = Math.toRadians(camera.yawDeg());
                fx = -Math.sin(yaw);
                fz = -Math.cos(yaw);
                flen = 1.0;
            } else {
                fx /= flen;
                fz /= flen;
            }

            dirX = fx * intent.moveForward() + right.x() * intent.moveStrafe();
            dirZ = fz * intent.moveForward() + right.z() * intent.moveStrafe();
            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (len > 1e-6) {
                dirX /= len;
                dirZ /= len;
            } else {
                dirX = 0;
                dirZ = 0;
            }
        } else if (!onGround && !flying) {
            // 空中且没有输入：保持动量，不做任何衰减。
            // ★ 飞行例外：空中悬停不按键必须收住水平速度，否则"松开方向键继续飘"
            //   会让飞行无法精确停在一格上方 —— 而精确停住正是飞行唯一的用途。
            return;
        }

        double speed = flying ? FLY_SPEED : WALK_SPEED;
        double targetVx = dirX * speed;
        double targetVz = dirZ * speed;
        if (aiming) {
            // PRD 5.4.3：瞄准时移动速度降至 60%。
            // 只削"目标速度"而不削结果速度：加速 / 减速 / 空中惯性共用同一条曲线，
            // 松开右键时不会出现"速度瞬间弹回去"的突兀感。
            // ★ 倍率来自手持枪械（手枪 0.60 / 冲锋枪 0.65，v2 §10），不再是全局常量。
            double aimMult = aimMoveSpeedMult();
            targetVx *= aimMult;
            targetVz *= aimMult;
        }

        // 指数逼近：与帧率无关（用 exp(-k·dt) 而不是每帧固定比例），
        // 否则逻辑步与渲染帧率一变，"手感"就会跟着变。
        // 飞行时的水平控制与地面同档（跟手），空中惯性只属于"跳跃/坠落"那条路
        double accel = (onGround || flying) ? GROUND_ACCEL : AIR_ACCEL;
        double t = 1.0 - Math.exp(-accel * dt);
        velocity.x += (targetVx - velocity.x) * t;
        velocity.z += (targetVz - velocity.z) * t;
    }

    /**
     * 起跳。
     *
     * <p><b>飞行时不走这里。</b>飞行中空格是"上升"而不是"起跳"，
     * 若两条路都走，玩家在飞行中按住空格会同时获得上升速度与一次起跳初速，
     * 表现为"一按空格就往上弹一下" —— 而且只在恰好贴着地面时发生，极难归因。
     *
     * <p><b>为什么"是否在地面"用上一步的结果而不是本步重算：</b>
     * 站立判定依赖"位移已经发生"，而跳跃必须发生在位移<u>之前</u>。
     * 若为跳跃单独插一次探测，就会出现"踩在方块边缘时探测到地面、位移后其实离开地面"
     * 这类双次判定不一致。沿用上一步的结果（每步都会刷新一次）既快又自洽。
     *
     * <p>起跳<b>直接赋值而不是累加</b>：若写成 {@code velocity.y += JUMP_VELOCITY}，
     * 按住空格时每步都会叠加，玩家会像火箭一样飞起来。
     * 而"按住空格连跳"是允许的 —— 落地后每步都会重新赋值，表现为连续起跳。
     */
    private void applyJump(PlayerIntent intent) {
        if (flying) {
            return;
        }
        if (intent.jump() && onGround) {
            velocity.y = JUMP_VELOCITY;
            onGround = false;   // 立即离开地面态，避免同一步内被再次判定为站立
        }
    }

    // ============================================================ 创造模式能力（M4-S8b）

    public boolean isCreativeMode() {
        return creativeMode;
    }

    /**
     * 设定创造能力总开关（PRD §5.2 – §5.5 五项）。
     *
     * <p><b>关闭时必须同时停止飞行：</b>否则玩家会"以生存模式的身体继续飞着"，
     * 而生存模式既没有免疫也没有虚空保护 —— 一次飞行变成一次必死的下坠。
     * 这种"能力被拿走但状态还在"的残留是典型的半途状态，必须在这里一次性收干净。
     */
    public void setCreativeMode(boolean enabled) {
        if (creativeMode && !enabled && flying) {
            flying = false;
            if (velocity.y > 0) {
                velocity.y = 0;
            }
            Log.info("[玩家] 创造能力已关闭，飞行状态一并停止。");
        }
        this.creativeMode = enabled;
    }

    public boolean isFlying() {
        return flying;
    }

    // ---- 创造会话（§4.3 于 2026-10-08 修订）----

    /** 本次运行内是否处在创造会话中。 */
    public boolean isCreativeSession() {
        return creativeSession;
    }

    /** 本世界是否允许开创造会话（生存存档为 {@code true}，创造存档为 {@code false}）。 */
    public boolean isCreativeSessionAllowed() {
        return creativeSessionAllowed;
    }

    /**
     * 设定本世界是否允许开创造会话。
     *
     * <p><b>只在装配期调用一次</b>，判据是存档定死的模式而不是命令行 ——
     * 用命令行会又造出"创造存档被当成生存存档、于是能退出去"那个破口，
     * 那正是 §4.3 要防的（用 UI 绕过模式锁定）。
     */
    public void setCreativeSessionAllowed(boolean allowed) {
        this.creativeSessionAllowed = allowed;
    }

    /**
     * 创造会话变化时的回调（<b>不带参数</b>，状态由 {@link #isCreativeSession()} 读）。
     *
     * <p>★ 它存在的唯一理由是<b>创造面板</b>：面板在装配期按存档模式建好，
     * 中途进会话时如果不重建，玩家会拿到"能飞、能瞬时破坏，但背包里没有
     * 创造标签"的能力 —— 五项能力里少了一项，而缺的那项恰好是唯一能让
     * 玩家看见自己身处创造模式的界面证据。
     */
    public void setCreativeSessionListener(Runnable listener) {
        this.creativeSessionListener = listener;
    }

    /** 创造模式免疫掉的伤害次数（含坠落与怪物攻击）。创造能力关闭后不复位。 */
    public long damageNegatedCount() {
        return damageNegated;
    }

    /**
     * 直接设定飞行状态。
     *
     * <p><b>非创造模式一律拒绝</b>：飞行是创造能力的一部分（PRD §5.4），
     * 生存模式没有入口可以打开它。这条拒绝不是防御性代码 —— 它是
     * "飞行只有创造模式能开"这条规则<b>唯一</b>的落点，因此必须有断言盯着。
     */
    public void setFlying(boolean enabled) {
        if (enabled && !creativeMode) {
            Log.noteWarning("Player", "生存模式不允许开启飞行（PRD §5.4），已忽略。");
            return;
        }
        if (flying == enabled) {
            return;
        }
        flying = enabled;
        if (!flying) {
            // 停飞不保留上升速度：否则"关掉飞行"会变成"再往上冲一段"，
            // 而那一段高度在关闭创造能力后是要用坠落伤害去还的。
            if (velocity.y > 0) {
                velocity.y = 0;
            }
        }
    }

    /**
     * 双击空格切换飞行（PRD §5.4）。
     *
     * <p><b>为什么判"按下沿之间的间隔"而不是"按住时长"：</b>
     * 双击的语义是"两次独立的按下"，按住空格（连跳）不该起飞。
     * 判按住时长会把"长按空格连续起跳"误判成双击 —— 而那正是生存模式里最常见的操作。
     *
     * <p><b>为什么用逻辑步累加的 {@link #clockSeconds} 而不是墙钟：</b>
     * 窗口必须是"游戏内时间"，否则一次掉帧（墙钟跳 200 ms）会让两次正常跳跃
     * 变成一次双击。这与本项目"帧率不得影响行为"是同一条纪律。
     */
    private void updateFlightToggle(PlayerIntent intent, double dt) {
        clockSeconds += dt;
        boolean held = intent.jump();
        boolean tapped = held && !jumpWasHeld;
        jumpWasHeld = held;
        if (!creativeMode && !creativeSessionAllowed) {
            // 连"开会话"都不允许的世界（创造存档）：单击与双击都是跳跃，
            // 双击窗口不积累（否则切模式后第一次跳就起飞）。
            // ★ 这个分支的判据是"能不能开会话"，不是"当前是不是生存"——
            //   因为生存中的**会话**也可能落到这里之外，见下面的三态循环。
            lastJumpTapSeconds = Double.NEGATIVE_INFINITY;
            return;
        }
        if (!tapped) {
            return;
        }
        boolean doubleTap = clockSeconds - lastJumpTapSeconds <= DOUBLE_TAP_SECONDS;
        lastJumpTapSeconds = clockSeconds;
        if (!doubleTap) {
            return;
        }
        // ★ 记下这一次"双击"已被消费：不把 lastJumpTapSeconds 推远的话，
        //   第三次点击又会与第二次组成一次新的双击（连点 = 反复开关）。
        lastJumpTapSeconds = Double.NEGATIVE_INFINITY;
        applyDoubleTapSpace();
    }

    /**
     * ★ 双击空格的<b>三态循环</b>（PRD_BLOCK_CREATIVE §4.3 于 2026-10-08 修订）。
     *
     * <table border="1">
     *   <tr><th>进入前</th><th>双击空格后</th></tr>
     *   <tr><td>生存，没开会话</td><td><b>进创造会话 + 直接起飞</b></td></tr>
     *   <tr><td>会话中，在飞</td><td>停飞（仍能无限方块、免伤害，站着搭）</td></tr>
     *   <tr><td>会话中，没在飞</td><td><b>退出会话</b>，回生存能力</td></tr>
     *   <tr><td>创造存档（无会话）</td><td>只切飞行，<b>没有退出路径</b>（§4.3）</td></tr>
     * </table>
     *
     * <p><b>为什么用「同一个键走完三态」而不是"进创造"与"退创造"两个键：</b>
     * 退出路径若放在另一个键上，玩家会不知道它存在，于是被永久留在创造会话里；
     * 而被留在创造会话里的**后果不是"关不掉游戏"，而是"生存平衡被污染且无提示"**
     * —— 正是 §4.3 理由①描述的那个坏结果，只是不再有提示。
     * ⇒ 让同一个键既是入口也是出口：它天生可逆，且再按一次就回去了。
     *
     * <p><b>为什么"停飞"与"退出"要分成两态：</b>
     * 一次双击就把创造整个关掉，会让"我想站着无限方块搭一会儿"这个动作
     * 不得不顺带放弃免伤害与虚空保护 —— 而那两样在高空搭台时是刚需。
     */
    private void applyDoubleTapSpace() {
        if (!creativeMode) {
            // ---- 生存（未开会话）→ 开会话 + 起飞 ----
            creativeSession = true;
            creativeMode = true;
            // 走 setFlying 而不是直接置位：它带"首次起飞清上升速度"的收尾，
            // 直接写字段会绕过它，于是"贴着地面双击起飞"会先向上窜一截。
            setFlying(true);
            Log.info("[玩家] 双击空格：进入创造会话并起飞（本次运行有效，不写入存档）。");
            notifyCreativeSession(true);
            return;
        }
        if (creativeSession) {
            if (flying) {
                setFlying(false);
                Log.info("[玩家] 双击空格：飞行已关闭（仍在创造会话内）。");
                return;
            }
            // 会话中且没在飞 → 退出。setCreativeMode(false) 会一并停飞，
            // 避免留下"能力被拿走但状态还在"的半途态。
            creativeSession = false;
            setCreativeMode(false);
            Log.info("[玩家] 双击空格：已退出创造会话，回到生存能力（飞行已停）。");
            notifyCreativeSession(false);
            return;
        }
        // ---- 创造存档：行为与本功能引入之前完全一致（只切飞行）----
        setFlying(!flying);
        Log.info("[玩家] 双击空格：飞行已%s。", flying ? "开启" : "关闭");
    }

    private void notifyCreativeSession(boolean active) {
        if (creativeSessionListener != null) {
            creativeSessionListener.run();
        }
    }

    /**
     * 飞行时的垂直速度（PRD §5.4「垂直速度可控」）。
     *
     * <p>上升 = 空格，下降 = Shift（{@code sneak}），都不按 = 悬停。
     * 三种状态都走同一条指数逼近曲线，于是"松开空格"是平滑收住而不是急停 ——
     * 垂直方向的手感与水平方向（{@link #applyMovementInput}）因此保持一致。
     */
    private void applyFlightVertical(PlayerIntent intent, double dt) {
        double target = 0;
        if (intent.jump()) {
            target = FLY_VERTICAL_SPEED;
        } else if (intent.sneak()) {
            target = -FLY_VERTICAL_SPEED;
        }
        double t = 1.0 - Math.exp(-FLY_VERTICAL_ACCEL * dt);
        velocity.y += (target - velocity.y) * t;
    }

    private void applyGravity(double dt) {
        velocity.y -= GRAVITY * dt;
        if (velocity.y < -TERMINAL_VELOCITY) {
            velocity.y = -TERMINAL_VELOCITY;
        }
    }

    private void moveAllAxes(World world, double dt) {
        double beforeX = position.x;
        double beforeZ = position.z;

        moveAxis(world, 1, velocity.y * dt);   // Y 先：先确定是否站在地上
        moveAxis(world, 0, velocity.x * dt);
        moveAxis(world, 2, velocity.z * dt);

        walkDistance += Math.hypot(position.x - beforeX, position.z - beforeZ);
    }

    private void moveAxis(World world, int axis, double delta) {
        if (delta == 0) {
            return;
        }
        double remaining = delta;
        while (Math.abs(remaining) > 1e-9) {
            double step = Math.max(-MAX_SUBSTEP, Math.min(MAX_SUBSTEP, remaining));
            remaining -= step;
            switch (axis) {
                case 0 -> position.x += step;
                case 1 -> position.y += step;
                default -> position.z += step;
            }
            if (!resolveAxisCollision(world, axis, step)) {
                return;
            }
        }
    }

    /**
     * 求解单轴碰撞。
     *
     * @return false 表示发生了碰撞并已把位置吸附到接触面（该轴运动应当停止）
     */
    private boolean resolveAxisCollision(World world, int axis, double step) {
        AABB box = bounds();
        int minX = (int) Math.floor(box.minX());
        int maxX = (int) Math.floor(box.maxX());
        int minY = (int) Math.floor(box.minY());
        int maxY = (int) Math.floor(box.maxY());
        int minZ = (int) Math.floor(box.minZ());
        int maxZ = (int) Math.floor(box.maxZ());

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    if (!world.collidesWith(box.minX(), box.minY(), box.minZ(),
                                    box.maxX(), box.maxY(), box.maxZ(), bx, by, bz)) {
                        continue;
                    }
                    switch (axis) {
                        case 0 -> {
                            position.x = step > 0 ? bx - HALF_WIDTH - COLLISION_EPS
                                    : bx + 1 + HALF_WIDTH + COLLISION_EPS;
                            velocity.x = 0;
                        }
                        case 1 -> {
                            if (step > 0) {
                                position.y = by - HEIGHT - COLLISION_EPS;
                            } else {
                                // 贴面高度取<b>碰撞体顶面</b>而非 by+1：
                                // 半高台阶顶面在 by+0.5，写死 +1 会让玩家浮空半格。
                                position.y = world.collisionTopAt(bx, by, bz) + COLLISION_EPS;
                            }
                            velocity.y = 0;
                        }
                        default -> {
                            position.z = step > 0 ? bz - HALF_WIDTH - COLLISION_EPS
                                    : bz + 1 + HALF_WIDTH + COLLISION_EPS;
                            velocity.z = 0;
                        }
                    }
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 站立判定用"向下探测"而不是"上次下移是否被挡"。
     *
     * <p>原因：站在地面上静止时 {@code velocity.y} 可能被反复清零，Y 轴位移恰好为 0，
     * 于是"本次是否发生向下碰撞"永远为假，玩家会显示为"悬空"并无法跳。
     * 主动探测对静止与移动两种情形都成立。
     */
    private void updateGroundState(World world) {
        AABB box = bounds();
        AABB probe = new AABB(box.minX(), box.minY() - GROUND_PROBE_DEPTH, box.minZ(),
                box.maxX(), box.minY(), box.maxZ());
        int minX = (int) Math.floor(probe.minX());
        int maxX = (int) Math.floor(probe.maxX());
        int by = (int) Math.floor(probe.minY());
        int minZ = (int) Math.floor(probe.minZ());
        int maxZ = (int) Math.floor(probe.maxZ());
        boolean grounded = false;
        // 命中格的碰撞体顶面（半高台阶是 by+0.5，满方块是 by+1）
        double groundTop = by + 1;
        for (int bx = minX; bx <= maxX && !grounded; bx++) {
            for (int bz = minZ; bz <= maxZ && !grounded; bz++) {
if (world.collidesWith(probe.minX(), probe.minY(), probe.minZ(),
                                probe.maxX(), probe.maxY(), probe.maxZ(), bx, by, bz)) {
                        grounded = true;
                        groundTop = world.collisionTopAt(bx, by, bz);
                    }
            }
        }
        this.onGround = grounded;
        // 命中地面时同时记下"站定后脚底应在的高度"：碰撞吸附的落点就是
        // 该方块顶面 + COLLISION_EPS（与 resolveAxisCollision 的 case 1 同一套口径），
        // 坠落结算用它把前瞻探测跳过的残余间隙补回来（见 groundRestY 的注释）。
        if (grounded) {
            this.groundRestY = groundTop + COLLISION_EPS;
            if (velocity.y < 0) {
                velocity.y = 0;
            }
        }
    }

    /**
     * 坠落距离累计与落地结算（PRD 5.3：{@code 伤害 = max(0, floor(坠落格数 − 3))}）。
     *
     * <p><b>为什么必须在"落地事件"上结算一次，而不是空中每步扣血：</b>
     * 空中连续扣血会让"跳起来再落回原地"也被判伤，且伤害随帧率与步长漂移。
     *
     * <p><b>为什么只在 {@code descended > 0} 时累加：</b>
     * 跳跃的上升段不算坠落，否则"原地起跳再落地"会把上升高度也算成坠落距离。
     *
     * <p><b>本轮修复（T7）：</b>此前本方法<b>只算、只记、只打日志，从不施加伤害</b>
     * （{@code hurt()} 在 {@code src/main} 里曾只有近战怪一个调用点），于是
     * 「坠落 4 格造成 1 点伤害」这条 PRD 5.3【MVP 必须】在<b>活代码里根本不存在</b>。
     * 现在在落地事件上调用 {@link #hurt}，来源标注 {@link DamageCause#FALL}。
     *
     * @param world    世界；{@link #hurt} 需要它判定致命一击的掉落点是否合法
     * @param descended 本逻辑步实际下降的高度（格），上升为负
     */
    private void updateFallState(World world, double descended, boolean airborneAtStepStart) {
        if (flying) {
            // ★ 飞行中的升降不是"坠落"：不累计坠落距离，也不产生落地事件。
            //   否则玩家飞高之后关掉飞行，落地结算会把整段飞行高度当成坠落伤害
            //   （创造模式免疫了它，但那个数会留在 HUD 上，看起来像 bug）。
            fallDistance = 0;
            return;
        }
        // 只要"这一步的前后不同时站在地面上"，这段位移就算滞空位移。
        // 两个端点都要看：只看落地后的状态会漏掉落地那一步的最后一截，
        // 只看起跳前的状态会漏掉走下悬崖那一步的第一截。
        if (descended > 0 && (airborneAtStepStart || !onGround)) {
            fallDistance += descended;
        }
        if (!onGround || !airborneAtStepStart) {
            return;   // 只有"空中 → 站立"这一次跃迁才是一次落地事件
        }

        // ★ 补上前瞻探测漏掉的残余间隙：探测判"站着"时脚底还悬在地面上方最多 0.02 格，
        //   这段距离必然会在随后 1–2 步里落完，但此刻位移尚未发生、增量式累加拿不到它。
        //   不补它，一次干净的 5 格坠落会被量成 4.9956 格 → floor(1.9956) = 1，
        //   伤害凭空少 1 点 —— 这正是"5 格只算出 1 点"的根因（见 groundRestY 的注释）。
        double residual = position.y - groundRestY;
        if (residual > 0) {
            fallDistance += residual;
        }

        lastFallDistance = fallDistance;
        lastFallDamage = fallDamageFor(fallDistance);
        if (lastFallDamage > 0) {
            Log.info("[玩家] 落地结算坠落伤害：%.4f 格 → %d 点。",
                    fallDistance, lastFallDamage);
            // ★ 真正施加（PRD 5.3 MVP 必须）：null 世界不结算（纯公式 / 离线路径）。
            //   只在这一处（"空中 → 站立"的落地事件）结算一次：紧随其后的 fallDistance 清零
            //   已保证同一次落地不会被计两次；落地之后 airborneAtStepStart 变假，
            //   本方法在更上方就会提前 return，因此跨帧也不会重入。
            if (world != null) {
                hurt(world, lastFallDamage, DamageCause.FALL);
            }
        }
        fallDistance = 0;   // 落地即清零，不得带入下一次滞空
    }

    /**
     * PRD 5.3 的坠落伤害公式。
     *
     * <p>坠落 3 格 → 0；4 格 → 1；5 格 → 2。非有限值与负值一律按 0 处理
     * （悬空生成、NaN 位置等异常输入不得凭空造出伤害）。
     */
    public static int fallDamageFor(double fallDistance) {
        if (!Double.isFinite(fallDistance) || fallDistance <= FALL_DAMAGE_THRESHOLD) {
            return 0;
        }
        return (int) Math.floor(fallDistance - FALL_DAMAGE_THRESHOLD + FALL_EPSILON);
    }

    /** 本次滞空已累计的下坠距离（格）。落地结算后归零。 */
    public double fallDistance() {
        return fallDistance;
    }

    /** 最近一次落地结算出的坠落伤害（点）。 */
    public int lastFallDamage() {
        return lastFallDamage;
    }

    /** 最近一次落地结算时的实测坠落距离（格）。 */
    public double lastFallDistance() {
        return lastFallDistance;
    }

    private void updateSafePosition(World world, double dt) {
        boolean valid = onGround && isStandingSpotValid(world, position.x, position.y, position.z);
        if (!valid) {
            // 离地（或脚下不合法）→ 计时作废。
            // 这一条同时覆盖 PRD 5.3.1 的三类排除项 —— 跳跃、坠落、虚空坠落
            // 的共同表现就是 onGround == false，悬空位置永远不会被记成安全位置
            // （TECH_DESIGN §I.6 要防的正是这个）。
            //
            // 反过来：只要还站在合法地面上，行走 / 走位产生的时间<b>计入</b>这 0.5 秒。
            // PRD 排除的是"离地"，不是"移动"；换到另一个安全面必然先经过一次离地，
            // 计时照样从头再来，所以这里不需要额外的"换格重置"（B 案口径）。
            safePositionTimer = 0;
            return;
        }
        safePositionTimer += dt;
        if (safePositionTimer >= SAFE_POSITION_INTERVAL - 1e-9) {
            lastSafePosition.set(position.x, position.y, position.z);
            safePositionTimer = 0;
        }
    }

    /**
     * 该点是否是一个合法的落脚点。
     *
     * <p>只在"脚底这一格与头这一格都不是碰撞方块，且下方是实体"时才算数。
     * 这个检查同时服务于 {@code lastSafePosition} 的写入与重生点的读取 ——
     * 若不做，玩家可能在悬空平台的边缘反复记录一个"记录时合法、重生时已不合法"的位置。
     */
    public boolean isStandingSpotValid(World world, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        if (!world.hasCollisionAt(bx, by - 1, bz)) {
            return false;
        }
        return !world.hasCollisionAt(bx, by, bz) && !world.hasCollisionAt(bx, by + 1, bz);
    }

    private void checkVoid(World world) {
        // ★ PRD §5.5 的例外（必须写明）：创造模式下玩家可能主动飞下虚空，
        //   若按生存规则处理，"飞行"就变成"自杀"。裁定是不掉落、不死亡，
        //   视为"停在虚空底部"。生存模式的虚空死亡规则一字不改（走下面那条路）。
        if (creativeMode) {
            if (Coords.isVoidDeath(position.y)) {
                position.y = Coords.VOID_KILL_Y;
                if (velocity.y < 0) {
                    velocity.y = 0;
                }
                fallDistance = 0;
                if (!parkedInVoid) {
                    parkedInVoid = true;
                    Log.info("[玩家] 创造模式：已停在虚空底部 (y=%.2f)，不死亡、不掉落。", position.y);
                }
            } else if (position.y > Coords.VOID_KILL_Y + 1.0) {
                // ★ 只有"明确离开虚空"才复位提示标志。
                //   若紧贴阈值复位，玩家被夹在 y=-8 上会每一步都重打一次日志。
                parkedInVoid = false;
            }
            return;
        }
        if (Coords.isVoidDeath(position.y)) {
            // PRD 5.3：「坠落至 y < -8 直接死亡，<b>不结算普通坠落伤害</b>」。
            // 虚空是"直接死"，不是"挨一次致命伤" —— 所以走 die() 而不是 hurt()。
            // 若走 hurt(9999)，将来有人给坠落伤害加个减伤或护甲，虚空就会变得可以硬抗。
            Log.info("[玩家] 坠入虚空 (y=%.2f < %.1f)，直接死亡。", position.y, Coords.VOID_KILL_Y);
            die(world, "虚空坠落", true);
        }
    }

    // ============================================================ 生命与死亡（M2）

    public int health() {
        return health;
    }

    public int maxHealth() {
        return MAX_HEALTH;
    }

    public boolean isDead() {
        return dead;
    }

    public double deathTimer() {
        return deathTimer;
    }

    public int lastDamageAmount() {
        return lastDamageAmount;
    }

    /** 最近一次受到伤害的来源；本次会话尚未受伤时为 {@code null}（供日志与自测读取）。 */
    public DamageCause lastDamageCause() {
        return lastDamageCause;
    }

    /** 最近一次死亡时掉落物的落点（虚空死亡 = lastSafePosition，普通死亡 = 死亡点）。 */
    public Vector3d lastDeathDropPosition() {
        return new Vector3d(lastDeathDropPosition);
    }

    /**
     * 承受伤害（PRD 5.3：生命上限 20）。
     *
     * <p>这是<b>无来源标注</b>的既有入口，等价于 {@code hurt(world, amount, DamageCause.GENERIC)}。
     * 保留它是因为既有调用点与单测都按这个签名编译；新增的来源标注走下面的重载。
     *
     * @param amount 伤害值；非正数忽略
     */
    public void hurt(World world, int amount) {
        hurt(world, amount, DamageCause.GENERIC);
    }

    /**
     * 承受伤害，并记录<b>来源</b>（{@link DamageCause}）。
     *
     * <p><b>需要 {@link World}</b> 是为了在血量归零当帧就能判定掉落点是否合法 ——
     * 若把这件事推迟到重生那一刻，世界可能已经被改过，掉落点会指向一个"记录时合法、
     * 生成时已在虚空上方"的位置（PRD 5.3.1 A 明文禁止掉落物坠入虚空）。
     *
     * <p><b>本次改动是纯仪器化：</b>与旧实现相比，只在扣血之外多做了两件不改变任何行为的事 ——
     * 记下一个来源字段、打印一行归因日志。伤害量、阈值、冷却、死亡判定一律未动。
     *
     * @param amount 伤害值；非正数忽略
     * @param cause  伤害来源；{@code null} 视为 {@link DamageCause#GENERIC}
     */
    public void hurt(World world, int amount, DamageCause cause) {
        if (dead || amount <= 0) {
            return;
        }
        // ★ PRD §5.5：创造模式免疫伤害（怪物攻击与坠落伤害都走这里）。
        //   位置必须在这里而不是在调用方：调用方有三个（落地结算、实体 tick、战斗），
        //   在其中任何一侧加判断都会漏掉另外两个，而漏掉的表现是
        //   "创造模式被打死" —— 一个本不该存在的状态。
        if (creativeMode) {
            damageNegated++;
            return;
        }
        DamageCause source = cause == null ? DamageCause.GENERIC : cause;
        lastDamageCause = source;
        lastDamageAmount = amount;
        int before = health;
        health -= amount;
        // ★ 归因日志：此前本方法不打印任何东西，日志里只剩一个总数，无法区分
        //   "被怪咬死"与"摔死"。这一行就是"怎么死的"那句话的唯一来源。
        Log.info("[玩家] 受到 %d 点伤害（来源=%s，生命 %d→%d）",
                amount, source.label(), before, Math.max(0, health));
        if (health <= 0) {
            health = 0;
            die(world, "生命耗尽", false);
        }
    }

    /** 满血（调试 / 自测用；MVP 无自然回血，PRD 5.3.2 的自然回血属 Alpha）。 */
    public void healFull() {
        health = MAX_HEALTH;
    }

    /**
     * 从存档恢复生命值（M2 起{@code player.json} 才有这个字段）。
     *
     * <p><b>为什么是独立方法而不是 {@link #applyLoadedState} 的第 10 个参数</b>：
     * {@code applyLoadedState} 已经有 9 个参数、5 处调用点
     * （{@code SaveManager}、{@code SkyIslandGame.startNewWorld} 与三处自测）。
     * 为一个可独立追加的字段改签名，收益是"参数列表更整齐"，
     * 代价是<b>所有调用点都要复核</b> —— 而自测调用点漏改的症状是
     * "编译不过"，尚属可接受；真正危险的是改完之后**没人重新核对语义**。
     *
     * <p>★<b>夹到 {@code [1, MAX_HEALTH]} 而不是 {@code [0, MAX_HEALTH]}：</b>
     * 读档后 {@code health == 0} 会被 {@link #isDead()} 判成已死，
     * 玩家一进世界就进入死亡流程。存档里出现 0 只可能是坏档或旧版本产物，
     * 把它当"1 血"处理比"一进游戏就死"温和得多。
     *
     * <p>★ <b>刻意不写 {@code dead} 标志</b>：读档恢复的是"生命值"这一个数，
     * 而 {@code dead} 的语义是"这一局已经结束过"，由 {@code deaths} 与重生流程决定。
     * 两者混在一起会出现"血是 3、但标志说已死"这种自相矛盾的状态。
     */
    public void applyLoadedHealth(int loaded) {
        health = Math.max(1, Math.min(MAX_HEALTH, loaded));
    }

    private void die(World world, String cause, boolean voidDeath) {
        if (dead) {
            return;
        }
        dead = true;
        deathTimer = 0;
        health = 0;

        // ★ 纯仪器化：把"虚空致死"也归因到 DamageCause.VOID。
        //   只写一个供日志/自测读取的来源字段 —— 不改 die() vs hurt() 的路径选择
        //   （生命耗尽路径由 hurt() 先行写下来源，这里不覆盖，击杀来源因此得以保留），
        //   也不改任何数值。自测的 DEATH_AND_RESPAWN 阶段走的正是这条虚空路径。
        if (voidDeath) {
            lastDamageCause = DamageCause.VOID;
        }

        // 虚空死亡：掉落物落在 lastSafePosition；普通死亡：落在死亡点（PRD 5.3.1 A/B）。
        Vector3d base = voidDeath ? new Vector3d(lastSafePosition) : new Vector3d(position);
        lastDeathDropPosition.set(base);

        // 「严禁掉入虚空」：落点不合法就向附近螺旋搜索。
        if (world != null && !isStandingSpotValid(world, base.x, base.y, base.z)) {
            Vector3d safe = findNearestStandable(world, base.x, base.y, base.z, RESPAWN_SEARCH_RADIUS);
            if (safe != null) {
                lastDeathDropPosition.set(safe);
            } else {
                Log.noteWarning("Player", "死亡掉落点附近找不到合法位置，掉落物将被放弃（不得坠入虚空）。");
            }
        }
        velocity.set(0, 0, 0);
        mining = false;
        miningProgressSeconds = 0;
        // 倒下必须退出瞄准：死亡期间 step() 只推倒计时，updateAiming 不会被调用，
        // 不在这里清就会出现"躺在原地、视野仍是 45°"的僵硬画面。
        aiming = false;
        // 同时清掉"上次是按哪把枪算的"：否则重生后第一帧会白白多打一条"换枪重算"日志。
        aimFovRuntimeId = -1;
        applyFov();
        Log.info("[玩家] 已倒下（%s），%.1f 秒后重生。", cause, RESPAWN_DELAY_SECONDS);
    }

    /**
     * 死亡倒计时；期间玩家不可移动、不可挖掘、不可放置。
     *
     * <p><b>为什么判据带 {@code - 1e-9}：</b>倒计时靠"每步累加 dt"推进，
     * 而 {@code dt = 1/60} 在二进制下不是精确值 —— 累加 180 次的理论结果是 3.0，
     * 实测却可能落在 2.9999999999999996。若写死 {@code >= 3.0}，
     * "整 3 秒后重生"这条 PRD 5.3 会在第 181 步才生效，而 {@code deaths} 也随之延后一帧，
     * 使按秒断言的门禁出现一帧级的随机红绿。
     * 容差取 1e-9（亚纳秒级，远小于任何可感知或可累积的时间尺度），
     * 与 {@link #updateSafePosition} 的 {@code SAFE_POSITION_INTERVAL - 1e-9} 同一口径。
     */
    private void updateDeathTimer(World world, double dt) {
        deathTimer += dt;
        if (deathTimer >= RESPAWN_DELAY_SECONDS - 1e-9) {
            respawn(world, "死亡重生");
        }
    }

    /**
     * 重生（PRD 5.3.1 B）。
     *
     * <p><b>重生点是世界固定点 (0, 64, 0)，不是 lastSafePosition。</b>
     * M1 的实现把两者混为一谈 —— 重生态永远回 lastSafePosition。
     * PRD 把它们的职责分得很清楚：
     * <ul>
     *   <li><b>重生点</b>（B 节）= 固定中心 + 螺旋搜索，保证"永远不会被扔到随机远处"；</li>
     *   <li><b>lastSafePosition</b>（A 节）= <u>虚空死亡的掉落物落点</u>，不是重生态。</li>
     * </ul>
     * 混用之后，玩家从崖边坠亡会被扔回很远的上一次落脚处，
     * 而这恰恰是 A 节要防止的"掉落物回不去"问题的镜像。
     */
    public void respawn(World world, String reason) {
        Vector3d point = findRespawnPoint(world);
        position.set(point);
        previousPosition.set(point);
        velocity.set(0, 0, 0);   // B13：不得保留非法速度，否则重生瞬间会继续下坠
        onGround = false;
        safePositionTimer = 0;
        // 重生不得继承坠落状态：否则"从高空坠入虚空"的那段距离会被带进下一次落地结算
        fallDistance = 0;
        lastFallDistance = 0;
        lastFallDamage = 0;
        mining = false;
        miningProgressSeconds = 0;
        // 重生状态：生命 20/20（PRD 5.3「重生状态」行）
        health = MAX_HEALTH;
        dead = false;
        deathTimer = 0;
        lastDamageAmount = 0;
        deaths++;
        // 重生后立刻把安全点设在脚下：否则刚重生就又坠亡时，
        // 掉落物会指向重生之前那个已经失效的旧安全点。
        lastSafePosition.set(point);
        refreshCamera();
        Log.info("[玩家] 已重生于 (%.2f, %.2f, %.2f)，原因=%s，累计死亡=%d",
                point.x, point.y, point.z, reason, deaths);
    }

    /**
     * 依 PRD 5.3.1 B 求重生点：(0, 64, 0) 起沿 y = 64 平面螺旋搜索；
     * 16 格内仍无合法点则生成 1 格临时木质地台，保证永不软锁。
     */
    public Vector3d findRespawnPoint(World world) {
        Vector3d found = findNearestStandable(world, spawnX, spawnY, spawnZ, RESPAWN_SEARCH_RADIUS);
        if (found != null) {
            return found;
        }
        // ---- 兜底地台（PRD 5.3.1 B：「保证永不软锁」）----
        // 这是全项目唯一会在玩家脚下凭空造方块的地方，必须有充分理由：
        // 若连兜底都没有，一个被挖穿的世界会让玩家进入"重生即坠亡"的无限循环，
        // 存档就成了废档。用 applySavedBlock 而不是 placeBlock —— 后者要求邻接支撑，
        // 而这里恰恰是在没有支撑的虚空上方造地台。
        int bx = (int) Math.floor(spawnX);
        int by = (int) Math.floor(spawnY) - 1;
        int bz = (int) Math.floor(spawnZ);
        Block fallback = BlockRegistry.byName(RESPAWN_FALLBACK_BLOCK_ID);
        if (fallback != null && world.applySavedBlock(bx, by, bz, (short) fallback.runtimeId())) {
            Log.noteWarning("Player", "重生点 16 格内无合法位置，已生成临时地台于 ("
                    + bx + "," + by + "," + bz + ")。");
        } else {
            Log.noteWarning("Player", "无法生成临时地台，重生点可能仍不合法。");
        }
        return new Vector3d(spawnX, spawnY, spawnZ);
    }

    /**
     * 从给定点起螺旋搜索最近的可站立位置（沿同一 y 平面）。
     *
     * @return 合法位置（方块中心对齐）；搜索半径内找不到返回 {@code null}
     */
    public Vector3d findNearestStandable(World world, double x, double y, double z, int radius) {
        if (isStandingSpotValid(world, x, y, z)) {
            return new Vector3d(x, y, z);
        }
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // 只扫第 r 圈（切比雪夫距离 == r），内圈在前面几轮已经扫过
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    double px = cx + dx + 0.5;
                    double pz = cz + dz + 0.5;
                    if (isStandingSpotValid(world, px, cy, pz)) {
                        return new Vector3d(px, cy, pz);
                    }
                }
            }
        }
        return null;
    }

    // ============================================================ 挖掘（B10）

    /**
     * 方块被破坏时的回调（M2 引入）。
     *
     * <p><b>为什么需要这个回调，而不是让游戏层去轮询 {@code blocksBroken()}：</b>
     * 轮询只能知道"少了一个方块"，拿不到<b>哪一个</b>方块、<b>什么颜色</b>、
     * <b>有没有掉落被丢弃</b>。而 M2 破坏特效要的正是"在那一格、用那个方块的颜色、
     * 撒 8–12 个粒子"（PRD 5.2 表），PRD 5.7 还要求背包满时提示「背包已满」。
     * 事后从增量里猜位置，在"连着打碎两个方块"的情况下必然算错。
     *
     * <p>回调只传原始数据（坐标、方块、剩余量），<b>不涉及任何渲染类型</b>：
     * Player 因此仍然不认识粒子、曳光或 HUD。
     */
    public interface BlockBreakListener {
        /**
         * @param leftover 未能放入背包的掉落数量；&gt; 0 表示背包已满（PRD 5.7）
         */
        void onBlockBroken(int blockX, int blockY, int blockZ, Block block, int leftover);
    }

    private BlockBreakListener blockBreakListener;

    /** 设置破坏回调；传 {@code null} 表示不关心（测试与纯逻辑用法）。 */
    public void setBlockBreakListener(BlockBreakListener listener) {
        this.blockBreakListener = listener;
    }

    private void notifyBlockBroken(int bx, int by, int bz, Block block, int leftover) {
        if (blockBreakListener != null) {
            blockBreakListener.onBlockBroken(bx, by, bz, block, leftover);
        }
    }

    private void updateMining(World world, PlayerIntent intent, double dt) {
        RaycastHit hit = raycastTarget(world);
        currentTarget = hit;

        // M2：手持枪械时左键是"开火"，不是"挖掘"（PRD 5.4「使用左键挖掘或开枪」）。
        // 只拦"破坏"，不拦上面的射线 —— 准星高亮、放置目标判定、以及
        // CombatController 的射击都还要用 currentTarget 这条射线。
        // 放在这里而不是放在 handlePlacement 之前，是因为挖掘进度条也归本方法管：
        // 换个位置就会出现"举着枪挖方块，进度条还在涨"。
        if (inventory.selectedStack().item().isGun()) {
            resetMining();
            return;
        }

        if (!intent.attackHeld() || hit == null || !hit.hasFace()) {
            resetMining();
            return;
        }
        Block block = BlockRegistry.byRuntimeId(hit.blockRuntimeId());
        if (block.isAir()) {
            resetMining();
            return;
        }

        // 目标改变 → 进度重置（PRD：换目标必须重新开始）
        if (!mining || hit.blockX() != miningX || hit.blockY() != miningY || hit.blockZ() != miningZ) {
            mining = true;
            miningX = hit.blockX();
            miningY = hit.blockY();
            miningZ = hit.blockZ();
            miningProgressSeconds = 0;
            miningTargetId = block.id();
        }

        if (!block.isBreakable()) {
            // 不可破坏：保持在"挖掘中"以便 HUD 显示被拒，但进度不推进
            miningProgressSeconds = 0;
            miningTargetId = block.id() + "(不可破坏)";
            return;
        }

        // ★ PRD §5.2：创造模式破坏瞬时（不累积破坏时间）。
        //   注意它在"不可破坏"判定<b>之后</b> —— 资源核心在创造模式下同样挖不动
        //   （§5.2 明文：`breakable = false` 不被创造模式覆盖）。
        //   放到前面的话，创造模式就成了"用 UI 绕过 PRD 硬约束"的又一条路。
        if (creativeMode) {
            if (clockSeconds - lastCreativeBreakSeconds >= CREATIVE_BREAK_COOLDOWN_SECONDS) {
                lastCreativeBreakSeconds = clockSeconds;
                executeBreak(world, block);
            }
            resetMining();
            return;
        }

        miningProgressSeconds += dt;
        if (miningProgressSeconds >= block.hardness()) {
            executeBreak(world, block);
            resetMining();
        }
    }

    /**
     * 执行"破坏 + 按掉落表结算"。
     *
     * <p>抽成独立方法是为了让<b>生存模式的进度破坏</b>与<b>创造模式的瞬时破坏</b>
     * 走<b>同一段</b>结算代码 —— 掉落规则只有一份，就不会出现
     * "创造模式挖石头不掉圆石"这种只在一种模式下成立的偏差。
     */
    private void executeBreak(World world, Block block) {
        World.MutationResult r = world.breakBlock(miningX, miningY, miningZ,
                World.MutationCause.PLAYER_BREAK);
        if (r.success()) {
            // ------------------------------------------------------------
            // G13 修复：按方块的掉落表结算，不再一律掉落自身。
            //
            // M1 这里写的是 inventory.add(hit.blockRuntimeId(), 1)，
            // 于是"挖石头得到石头"，而 PRD 5.1 要求石头掉圆石、草方块掉泥土、
            // 玻璃与树叶不掉落。审计把这条记为 G13（方块掉落表完全未实现），
            // 根因是"掉落规则没有地方可写"—— 现在它写在方块的注册行里。
            //
            // 掉落物走 stable ID 查物品表：方块与物品是两套注册表，
            // 煤炭矿石掉的是"煤炭物品"，不是"煤炭矿石方块"。
            // ------------------------------------------------------------
            if (block.hasDrop()) {
                int dropItemId = ItemRegistry.runtimeIdOf(block.dropItemId());
                int leftover = inventory.add(dropItemId, block.dropCount());
                if (leftover > 0) {
                    Log.noteWarning("Player", "背包已满，丢弃 " + leftover + " 个 " + block.dropItemId());
                }
                notifyBlockBroken(miningX, miningY, miningZ, block, leftover);
            } else {
                Log.info("[挖掘] %s 无掉落（PRD 5.1）", block.id());
                notifyBlockBroken(miningX, miningY, miningZ, block, 0);
            }
            blocksBroken++;
        } else {
            Log.noteWarning("Player", "破坏被世界拒绝: " + r.reason());
        }
    }

    private void resetMining() {
        mining = false;
        miningProgressSeconds = 0;
        miningX = Integer.MIN_VALUE;
        miningTargetId = "-";
    }

    // ============================================================ 放置（B11）

    private void handlePlacement(World world, PlayerIntent intent) {
        if (!intent.usePressed()) {
            return;
        }
        RaycastHit hit = currentTarget;
        if (hit == null) {
            rejectPlacement("没有瞄准任何方块");
            return;
        }
        if (!hit.hasFace()) {
            rejectPlacement("视线起点位于方块内部，没有可用放置面");
            return;
        }
        ItemStack selected = inventory.selectedStack();
        if (selected.isEmpty()) {
            rejectPlacement("当前快捷栏槽位为空");
            return;
        }
        // M2 起快捷栏里可能出现非方块物品（手枪 / 弹药 / 煤炭）。
        // 拿着它们按右键必须是"明确拒绝"，而不是把 -1 当作方块 ID 传给世界
        // —— 那会让放置逻辑收到一个非法 ID，错误会推迟到很久以后才暴露。
        if (!selected.isBlockItem()) {
            rejectPlacement(selected.item().id() + " 不是方块，无法放置");
            return;
        }
        Block selectedBlock = selected.item().block();
        World.MutationResult r = world.placeBlock(
                hit.adjacentX(), hit.adjacentY(), hit.adjacentZ(),
                selectedBlock.runtimeId(),
                World.MutationCause.PLAYER_PLACE,
                this::occupiesBlock);
        if (r.success()) {
            // ★ PRD §5.3：创造模式放置不消耗。
            //   "不消耗"不能靠"面板给的是 ∞ 所以扣了也看不出来"来实现 ——
            //   那只是把消耗推迟 64 次，玩家建到第 65 格时手上会凭空变空。
            if (!creativeMode) {
                inventory.consumeSelected(1);
            }
            blocksPlaced++;
            lastPlacementMessage = "已放置 " + selectedBlock.id()
                    + " 于 (" + hit.adjacentX() + "," + hit.adjacentY() + "," + hit.adjacentZ() + ")";
        } else {
            rejectPlacement(r.reason());
        }
    }

    private void rejectPlacement(String reason) {
        placementRejections++;
        lastPlacementMessage = "放置失败: " + reason;
        Log.info("[放置] 被拒绝: %s", reason);
    }

    // ============================================================ 射线

    /** 从眼睛出发沿视线投射，reach = {@link #REACH}。 */
    public RaycastHit raycastTarget(World world) {
        // ★ DDA 要的是 double 精度方向：射线会在 5 格内累积上百次增量判断，
        //   用 float 方向在接近轴向（±1, 0, 0）时容易出现"差一格"的边界抖动。
        //   复用同一个 Vector3d 而不是每次 new：本方法每个逻辑步都被调用一次。
        Vector3fc f = camera.forward();
        rayDirection.set(f.x(), f.y(), f.z());
        return DdaRaycaster.castSolid(world, eyePosition(), rayDirection, REACH);
    }

    private void refreshCamera() {
        camera.setPosition(position.x, position.y + EYE_HEIGHT, position.z);
        camera.updateView();
    }

    /** 直接放置（自测脚本用）：不消耗背包、不考虑朝向。 */
    public World.MutationResult debugPlaceAt(World world, int x, int y, int z, int runtimeId) {
        return world.placeBlock(x, y, z, runtimeId, World.MutationCause.SELF_TEST, null);
    }

    // ============================================================ 渲染插值（§C.1）

    /**
     * 把相机放到"上一步与当前步之间"的插值位置，仅供渲染。
     *
     * <p><b>为什么必须做：</b>逻辑恒以 60 Hz 推进，而渲染帧率实测可到数千。
     * 如果渲染直接用逻辑位置，一帧内在同一个位置画十几次、然后突然跳一整步 ——
     * 表现为"移动时画面一顿一顿的"。插值把这一整步的位移摊到多个渲染帧上。
     *
     * <p><b>为什么它只改相机、不改 {@link #position}：</b>
     * 权威位置必须保持"整数倍逻辑步"的语义。射线、碰撞、放置全都读权威位置；
     * 若把插值位置写回去，玩家会在"没有被逻辑步推进"的帧里挖到不该挖的方块。
     * 下一个逻辑步开始时 {@link #refreshCamera()} 会把相机重置回权威位置。
     */
    public void applyInterpolatedCamera(double alpha) {
        double a = Math.max(0, Math.min(1, alpha));
        double x = previousPosition.x + (position.x - previousPosition.x) * a;
        double y = previousPosition.y + (position.y - previousPosition.y) * a;
        double z = previousPosition.z + (position.z - previousPosition.z) * a;
        camera.setPosition(x, y + EYE_HEIGHT, z);
        camera.updateView();
    }

    // ============================================================ 读档（B14）

    /**
     * 用存档数据覆盖玩家状态。
     *
     * <p>刻意做成"一次调用写全部"而不是若干个 setter：读档是一个<u>原子语义</u>的操作，
     * 分散的 setter 允许出现"位置改了但速度没清"这类中间状态，
     * 而读档后玩家立刻被物理步推进 —— 中间状态会被真的执行一次。
     * 因此这里同时把速度与挖掘状态清零，并同步相机（否则相机会停留在旧的视图矩阵上，
     * 表现为"读档后画面还朝着读档前的方向"）。
     *
     * @param lastSafe 长度 3 的数组 {x,y,z}
     */
    public void applyLoadedState(double x, double y, double z, double yaw, double pitch,
                                 double[] lastSafe, List<ItemStack> hotbar,
                                 int selectedSlot, int deaths) {
        position.set(x, y, z);
        previousPosition.set(position);
        velocity.set(0, 0, 0);
        if (lastSafe != null && lastSafe.length >= 3) {
            lastSafePosition.set(lastSafe[0], lastSafe[1], lastSafe[2]);
        } else {
            lastSafePosition.set(x, y, z);
        }
        camera.setAngles(yaw, pitch);
        inventory.restore(hotbar, selectedSlot);
        this.deaths = Math.max(0, deaths);
        this.onGround = false;
        this.safePositionTimer = 0;
        this.walkDistance = 0;
        this.fallDistance = 0;
        this.lastFallDistance = 0;
        this.lastFallDamage = 0;
        resetMining();
        currentTarget = null;
        // 读档后一律退出瞄准：存档里不存在"瞄准中"这个状态（它是每步从意图派生的），
        // 保留旧值会让读档瞬间的 FOV 与手持物不一致。
        aiming = false;
        aimFovRuntimeId = -1;
        applyFov();
        refreshCamera();
        Log.info("[玩家] 已应用存档状态: 位置 (%.3f, %.3f, %.3f)，视向 %.2f/%.2f，快捷栏选中 %d",
                x, y, z, yaw, pitch, selectedSlot + 1);
    }

    /**
     * 读档后的位置合法性校验（TECH_DESIGN §N.7）。
     *
     * <p>按 PRD 5.3.1：先判当前位置是否合法；不合法则沿地表<b>螺旋搜索</b>半径 16 内
     * 的第一个合法落脚点；仍找不到就生成 1 格临时木质地板并回到世界出生点。
     *
     * <p><b>坐标基准是整个方法最容易写错的地方：</b>PRD 说的"沿 y=64 平面螺旋"，
     * 按 {@code Coords} 的裁定 {@code y = 64} 是<b>顶面标高</b>，
     * 因此搜索检查的方块层是 {@code y = 63}（{@link Coords#WORLD_SURFACE_BLOCK_Y}），
     * 返回的站立位置是 {@code feetY = 64.0}（{@link Coords#WORLD_SURFACE_FEET_Y}）。
     * 这个 +1/−1 的转换必须显式写出来，不能靠"看起来差不多"。
     *
     * @return 是否发生过修正
     */
    public boolean sanitizePositionAfterLoad(World world) {
        if (isStandingSpotValid(world, position.x, position.y, position.z)) {
            return false;
        }
        int startX = (int) Math.floor(position.x);
        int startZ = (int) Math.floor(position.z);        int surfaceBlockY = Coords.WORLD_SURFACE_BLOCK_Y;
        double surfaceFeetY = Coords.WORLD_SURFACE_FEET_Y;

        for (int radius = 0; radius <= 16; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // 只检查"环"上的点，避免重复检查内圈
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    double candidateX = startX + dx + 0.5;
                    double candidateZ = startZ + dz + 0.5;
                    if (world.hasCollisionAt(startX + dx, surfaceBlockY, startZ + dz)
                            && !world.hasCollisionAt(startX + dx, surfaceBlockY + 1, startZ + dz)
                            && !world.hasCollisionAt(startX + dx, surfaceBlockY + 2, startZ + dz)) {
                        position.set(candidateX, surfaceFeetY, candidateZ);
                        previousPosition.set(position);
                        velocity.set(0, 0, 0);
                        lastSafePosition.set(candidateX, surfaceFeetY, candidateZ);
                        refreshCamera();
                        Log.noteWarning("Player", String.format(
                                "存档位置非法，螺旋搜索命中 (%.2f, %.2f, %.2f)（半径 %d）",
                                candidateX, surfaceFeetY, candidateZ, radius));
                        return true;
                    }
                }
            }
        }

        // ★ 兜底：生成 1 格临时木质地板。cause 必须是 SAVE_LOAD —— 它绕过"相邻支撑"校验，
        //   因为这块地板本来就是凭空生成的救援措施（§N.7 明确指出）。
        World.MutationResult placed = world.placeBlock(0, surfaceBlockY, 0,
                BlockRegistry.planks().runtimeId(), World.MutationCause.SAVE_LOAD, null);
        Log.noteWarning("Player", "螺旋搜索未找到合法落脚点，已生成临时地台于 (0,"
                + surfaceBlockY + ",0)：" + (placed.success() ? "成功" : placed.reason()));
        position.set(0.5, surfaceFeetY, 0.5);
        previousPosition.set(position);
        velocity.set(0, 0, 0);
        lastSafePosition.set(0.5, surfaceFeetY, 0.5);
        refreshCamera();
        return true;
    }

    /** 自测用：直接把玩家放到某处并清零速度。 */
    public void teleport(double x, double y, double z) {
        position.set(x, y, z);
        previousPosition.set(position);
        velocity.set(0, 0, 0);
        refreshCamera();
    }

    @Override
    public String toString() {
        return String.format("Player(%.2f,%.2f,%.2f vy=%.2f onGround=%s) %s",
                position.x, position.y, position.z, velocity.y, onGround, inventory);
    }
}

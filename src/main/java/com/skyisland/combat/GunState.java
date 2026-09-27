package com.skyisland.combat;

import com.skyisland.item.GunSpec;
import com.skyisland.item.Item;
import com.skyisland.item.ItemRegistry;
import com.skyisland.player.Inventory;

/**
 * 枪械运行时状态：弹匣 / 射速节流 / 换弹进度。M2 只服务手枪。
 *
 * <p><b>换弹规则按 PRD 5.4.3「换弹条件（v0.3.2 统一规则）」逐条实现：</b>
 * <ol>
 *   <li>弹匣<b>已满</b> → 按 R <b>无操作</b>；</li>
 *   <li>弹匣未满且后备弹药 &gt; 0 → 允许换弹；</li>
 *   <li>后备弹药不足 → <b>允许部分填充</b>，{@code load = min(容量 − 弹匣内, 后备)}；</li>
 *   <li>换弹<b>完成前不得提前转移弹药</b>。</li>
 * </ol>
 *
 * <p><b>"完成前不转移"这条不是优化，是正确性的前提。</b>
 * 直觉写法是"按 R 立刻把弹药从背包搬进弹匣，然后放个 1.2 秒的动画"。
 * 那样"换弹中途弹药还没到位"这件事就只能靠额外保存"换弹开始前的快照"来还原 ——
 * 而快照一旦漏掉某个字段就会静默丢子弹。
 * 先不搬、完成时一次搬，则"换弹进行到一半"读到的永远是"弹匣没变、背包没变"，
 * 不存在任何需要在半途回滚的中间态。
 *
 * <p><b>M2.2 修订：换弹不再被移动打断。</b>v0.3.2 原本规定"移动打断换弹 = 取消换弹"，
 * 现已<b>废止</b>（PRD 5.4.3 该行改写为"移动不打断换弹"）：按 R 之后照常行走，
 * 1.2 秒走满即完成上膛。因此本类<b>没有</b>取消换弹的方法，也没有"被打断计数" ——
 * 一次换弹只有一个终点：{@link #completeReload(Inventory)}。
 *
 * <p><b>满弹匣换弹已被 v0.3.2 废止</b>（PRD 5.4.3 明文："v0.3.1 的「满弹匣换弹允许、
 * 弹出剩余弹药不损失（回背包）」口径作废"）。用户裁决 A3 采纳了这条。
 * 本类不实现"弹出余弹"，也不保留任何相关分支。
 *
 * <h2>后备弹药口径：M2.1 Prototype 无限 → M3 Survival 有限</h2>
 * <p><b>决定（M2.1-A）：Combat Prototype / Debug 口径是无限后备</b>。
 * 这条改动只放宽"弹药从哪来"，<b>不动"必须由玩家按 R 上膛"这条节奏</b>：
 * 弹匣仍是 12 发，打空仍要按 R，换弹仍是 1.2 秒。
 * 战斗原型阶段要观测的是"枪战的手感与可读性"，而不是"资源管理的压力测试"；
 * 让玩家在 24 发之后只能站着挨咬，会让每一次批量试玩都在第 25 发戛然而止。
 *
 * <p><b>M3 Story 8 收紧了正式玩法口径</b>（v2 §5.3 / §19-6 / §19-7）：
 * 无限后备不再是一个全局默认值，而是 {@link ReserveMode#PROTOTYPE} 这一种
 * <b>由上层显式传入</b>的 Run Mode 配置。M3 Survival 正式游戏流程用的是
 * {@link ReserveMode#SURVIVAL}（有限后备、真实从 Inventory 扣减）。
 * 两种口径因此可以并存：正式玩法走有限，战斗原型自测走无限，互不污染。
 * 谁传什么见 {@code CombatController#setReserveMode} 与 {@code SkyIslandGame}。
 *
 * <p><b>为什么选"完全不消耗背包"，而不是"先扣再补回来"：</b>
 * <ol>
 *   <li><b>"补回来"在语义上说谎。</b>后备既然是无限的，就不该有一次临时的扣减：
 *       那会在两条失败路径上变成真的丢子弹 —— {@code consumeItem} 返回 false（库存不足）
 *       或补给过程中途失败时，"先扣"已经发生而"补回"没跑到；</li>
 *   <li><b>它凭空造出一个中间非法态。</b>存档里 {@code Slot.count <= 0} 会被
 *       {@code PlayerState.validate()} 判为非法（见 {@code save/PlayerState}）。
 *       每帧多一点 I/O 我不在乎，我在乎的是"存在某一瞬间存档是非法的"这件事：
 *       只要不允许扣到 0，这一类状态就根本不会被构造出来；</li>
 *   <li><b>口径要能被观察。</b>"背包里的手枪弹数量在任何操作后都不变"是一条
 *       能被测出来的性质，而"扣了又补"只能测最终结果 —— 中间过程无从举证。</li>
 * </ol>
 * 因此 {@link #completeReload(Inventory)} 在无限口径下<b>只读后备、不写背包</b>，
 * 直接把弹匣补到满。
 *
 * <p><b>为什么仍然保留有限后备那条代码路径：</b>把分支删掉会让
 * PRD 5.4.3 的规则②③（"后备 > 0 才允许换弹"、"部分填充 load = min(need, reserve)"）
 * 变成"读起来很合理、却永远不会执行"的代码 —— 那正是本项目反复吃过亏的一类缺陷。
 * 有限口径（M3 Survival）现在是<b>正式玩法口径</b>，由上层经
 * {@link #GunState(Item, ReserveMode)} 传入；无限口径（Prototype）同样只能显式传入。
 * 两条路径都必须被上层显式选择，规则不分主次。
 *
 * <h2>M3 泛化（WEAPON-DOC-001 v2 §5）：去手枪硬编码</h2>
 * <p>M2 这里焊死了两个"手枪"假设：两个把手枪写进状态类的静态工厂
 * （旧版的两个 {@code forXxx} 工厂），以及从
 * {@link ItemRegistry#PISTOL_AMMO_ID} 直接读后备弹药。M3 要落地第二把枪（SMG），
 * 两者都会让"每加一把枪再写一条 if"重演。
 * 现在：
 * <ul>
 *   <li>只保留 {@link #GunState(Item, ReserveMode)} 构造器 —— <b>没有单参便捷构造器</b>，
 *       因此"这一局是什么口径"不存在可以默认漂移的入口，调用方必须显式回答一次；
 *       <b>枪种信息全部来自 {@link Item#gun()} 的 {@link GunSpec}</b>，本类对"是不是手枪"零感知；</li>
 *   <li>后备弹药一律走 {@link GunSpec#ammoId()}，经 {@link ItemRegistry#byName(String)}
 *       取真实弹药 Item —— <b>不存在任何 {@code PISTOL_AMMO_ID} 字面量</b>。</li>
 * </ul>
 *
 * <p><b>构造期校验 ammoId 必须能解析到真实弹药：</b>若某把枪的 {@code ammoId} 在注册表里
 * 查不到，属于开发期数据错误，应当在<b>构造时立刻崩</b>（{@link IllegalStateException}，
 * 消息含 ammoId），而不是等到换弹时静默把所有扣减失败、表现为"打不完的子弹"。
 * 因此本类<b>不做任何静默 fallback</b>。
 */
public final class GunState {

    /**
     * 后备弹药口径：<b>由上层显式传入</b>的 Run Mode / Combat Rule 配置。
     *
     * <h2>为什么它是枚举而不是一个 {@code boolean}</h2>
     * M2.1 时"是否无限后备"是一个 {@code boolean}，并配了一个全局常量
     * {@code INFINITE_RESERVE_DEFAULT = true} 当产品口径。那条路线的问题不是"值错了"，
     * 而是<b>"一个全局默认值没有办法同时表达两种合法口径"</b>：
     * M3 要求正式玩法是有限后备（v2 §19-6），而战斗原型 / 调试仍是无限（v2 §19-7）。
     * 只要默认值只有一个取值，把正式玩法改对就必然把原型口径一起改错。
     *
     * <p>v2 §5.3 给的正解是「把『是否无限后备』从 {@code GunState} 的全局默认常量
     * 提升为上层明确传入的 Combat Rule / Run Mode 配置」—— 本枚举就是那个配置的类型。
     * 它把"这一局到底是什么口径"变成调用方在<b>建枪状态那一刻就必须回答</b>的问题，
     * 于是 {@link #GunState(Item, ReserveMode)} 这一层不存在任何默认值可以漂移。
     *
     * <h2>为什么放在这里，而不放到 {@code combat} 包外面（例如 game 包）</h2>
     * 它是<b>玩法规则</b>而非某个调用方的私有口味：规则的定义域应贴在执行它的规则载体
     * （{@code GunState}）旁边，否则 {@code game} 包就会成为唯一能说清"弹药怎么算"的地方，
     * 而 {@code CombatControllerTest} 这类不经过 {@code game} 的单测就必须反向依赖它。
     */
    public enum ReserveMode {
        /**
         * {@code true} —— 无限后备：换弹补满弹匣，<b>不读写背包</b>。
         *
         * <p>Combat Prototype / Debug 口径（v2 §5.3、§19-7）：
         * 原型阶段要观测"枪战的手感与可读性"，不是"资源管理的压力测试"。
         */
        PROTOTYPE(true),

        /**
         * {@code false} —— 有限后备：换弹按 PRD 5.4.3 规则②③从背包取弹并真实扣减。
         *
         * <p>M3 Survival 正式口径（v2 §5.3、§19-6）：弹药是资源，打光就是打光。
         */
        SURVIVAL(false);

        private final boolean infiniteReserve;

        ReserveMode(boolean infiniteReserve) {
            this.infiniteReserve = infiniteReserve;
        }

        /** 本口径下后备弹药是否无限。 */
        public boolean infiniteReserve() {
            return infiniteReserve;
        }

        /**
         * 由 {@code boolean} 取得对应口径。
         *
         * <p>保留这条映射是为了让既有的布尔语义（{@code true} = 无限）在测试与
         * 显式构造路径上继续可用，而不是让调用方去写 {@code == true ? PROTOTYPE : SURVIVAL}。
         */
        public static ReserveMode of(boolean infiniteReserve) {
            return infiniteReserve ? PROTOTYPE : SURVIVAL;
        }
    }

    /** 开始换弹的结果。 */
    public enum ReloadOutcome {
        /** 已开始换弹。 */
        STARTED,
        /** 弹匣已满 —— 按 R 无操作（规则①）。 */
        ALREADY_FULL,
        /**
         * 后备弹药为 0 —— 无法换弹（规则②的另一侧）。
         *
         * <p><b>只在 {@link ReserveMode#SURVIVAL}（有限后备）口径下才可能出现</b>：
         * {@link ReserveMode#PROTOTYPE} 下 {@link #tryStartReload(Inventory)} 不会返回它
         * （见类注释），因此原型也不会显示"没有后备弹药"这条提示。
         * 它在 M3 Survival（正式玩法）里是<b>常态之一</b>：弹药打光就是打光。
         */
        NO_RESERVE,
        /** 已在换弹中 —— 重复按键无操作。 */
        ALREADY_RELOADING
    }

    /** 一次开火尝试的结果。 */
    public enum ShotOutcome {
        /** 成功击发（弹匣已扣 1 发）。 */
        FIRED,
        /** 弹匣为空 —— 播放空枪音效并提示「弹药不足」（PRD 5.4.3）。 */
        NO_AMMO,
        /** 射速节流中（0.25 秒一发）。 */
        COOLDOWN,
        /** 换弹期间不得开枪（PRD 5.4.3：换弹期间不得开枪）。 */
        RELOADING
    }

    private final Item gun;
    private final GunSpec spec;

    private int magazineAmmo;
    private boolean reloading;
    private double reloadRemaining;
    private double fireCooldown;

    /**
     * 后备弹药口径：{@link ReserveMode#PROTOTYPE} = 无限。
     *
     * <p>是 {@code final} 而不是运行时可切：一次游戏会话里"弹药到底是资源还是无限"
     * 必须只有一个答案，否则"刚才那一匣是从背包扣的、这一匣不是"会成为无法解释的现象。
     */
    private final ReserveMode reserveMode;

    private int shotsFired;
    private int dryFires;
    private int reloadsCompleted;

    /**
     * 显式指定后备口径。<b>这是唯一的构造入口</b>（M3 Story 8 删除了单参便捷构造器）。
     *
     * <p><b>为什么连一个"便捷默认"都不留：</b>M2.1 的单参构造器读的是一个全局默认常量，
     * 于是"正式玩法是什么口径"这件事由 {@code GunState} 自己说了算 ——
     * 结果就是 M3 要把正式玩法改成有限时，唯一可动的旋钮同时也在改战斗原型。
     * v2 §5.3 明确要求把它提升为<b>上层显式传入</b>的配置：现在没有默认值，
     * 建状态的人必须在调用点回答"这一局是 Survival 还是 Prototype"，
     * 而这个回答在代码里是<b>可被测试断言</b>的（见 {@code CombatControllerTest}）。
     *
     * <p><b>构造期校验（M3 §5.2）：</b>本枪的 {@link GunSpec#ammoId()} 必须能在
     * {@link ItemRegistry} 里解析到真实弹药 Item，否则立刻抛 {@link IllegalStateException}。
     * 这条校验替代了 M2 对 {@code PISTOL_AMMO_ID} 的硬编码：
     * 后者"永远找得到"，前者把"数据写错"从运行期难查的症状提前成启动期的崩溃。
     */
    public GunState(Item gun, ReserveMode reserveMode) {
        if (gun == null || !gun.isGun()) {
            throw new IllegalArgumentException("GunState 只能用于枪械物品: " + gun);
        }
        if (reserveMode == null) {
            throw new IllegalArgumentException(
                    "GunState 的后备弹药口径必须显式指定（M3 §5.3）：传 null 等于引入一个隐式默认值");
        }
        this.gun = gun;
        this.spec = gun.gun();
        this.reserveMode = reserveMode;
        this.magazineAmmo = 0;
        if (ItemRegistry.byName(spec.ammoId()) == null) {
            throw new IllegalStateException(
                    "枪械 " + gun.id() + " 的 ammoId 在 ItemRegistry 中不存在: " + spec.ammoId()
                            + "（禁止静默 fallback —— 请修正 GunSpec.ammoId 或先注册该弹药）");
        }
    }

    /**
     * 显式指定后备口径（{@code boolean} 形式，{@code true} = 无限）。
     *
     * <p>存在的理由是<b>可读性与既有调用点</b>：测试里"我要验无限那条规则"
     * 写成 {@code new GunState(pistol, true)} 比 {@code ReserveMode.PROTOTYPE} 更短；
     * 语义完全等价于 {@link #GunState(Item, ReserveMode)}，不构成第二个默认值。
     */
    public GunState(Item gun, boolean infiniteReserve) {
        this(gun, ReserveMode.of(infiniteReserve));
    }

    public Item gun() {
        return gun;
    }

    public GunSpec spec() {
        return spec;
    }

    // ------------------------------------------------------------ 弹匣

    public int magazineAmmo() {
        return magazineAmmo;
    }

    public int magazineSize() {
        return spec.magazineSize();
    }

    /**
     * 后备弹药是否是无限口径（HUD 据此显示 {@code 12 / ∞} 而不是一个具体数字）。
     *
     * <p>让 HUD 读这个标志而不是自己去数背包：数是<b>渲染层的事</b>，
     * 但"这一局的弹药口径是什么"是<b>玩法规则</b>，规则必须只有一个来源。
     */
    public boolean reserveInfinite() {
        return reserveMode.infiniteReserve();
    }

    /** 本枪状态采用的后备口径（M3 §5.3 的 Run Mode 配置）。 */
    public ReserveMode reserveMode() {
        return reserveMode;
    }

    public boolean isMagazineFull() {
        return magazineAmmo >= spec.magazineSize();
    }

    /** 调试 / 自测用：直接设定弹匣内弹药（夹在 [0, 容量]）。 */
    public void setMagazineAmmo(int amount) {
        magazineAmmo = Math.max(0, Math.min(spec.magazineSize(), amount));
    }

    // ------------------------------------------------------------ 开火

    public boolean isReloading() {
        return reloading;
    }


    /** 换弹进度 0..1（HUD 进度条用）。 */
    public double reloadProgress01() {
        if (!reloading) {
            return 0;
        }
        return 1.0 - Math.max(0, reloadRemaining) / spec.reloadSeconds();
    }

    public double fireCooldownRemaining() {
        return fireCooldown;
    }

    /**
     * 尝试击发一次。
     *
     * <p>只消耗<b>弹匣内</b>弹药 —— 后备弹药在换弹完成时才进弹匣（见类注释）。
     */
    public ShotOutcome tryFire() {
        if (reloading) {
            return ShotOutcome.RELOADING;
        }
        if (fireCooldown > 1e-9) {
            return ShotOutcome.COOLDOWN;
        }
        if (magazineAmmo <= 0) {
            dryFires++;
            return ShotOutcome.NO_AMMO;
        }
        magazineAmmo--;
        fireCooldown = spec.shotInterval();
        shotsFired++;
        return ShotOutcome.FIRED;
    }

    // ------------------------------------------------------------ 换弹

    /**
     * 按 R：依 PRD 5.4.3 的四条规则决定是否开始换弹。
     *
     * <p>M2.1 的口径变化落在规则②这一条上：<b>无限后备时"后备 &gt; 0"恒真</b>，
     * 因此 {@link ReloadOutcome#NO_RESERVE} 不会被返回 —— 后备无限时"没子弹可装"
     * 这句话本身不成立。规则①③④（满匣无操作、部分填充、完成前不转移）不受影响。
     */
    public ReloadOutcome tryStartReload(Inventory inventory) {
        if (reloading) {
            return ReloadOutcome.ALREADY_RELOADING;
        }
        if (isMagazineFull()) {
            return ReloadOutcome.ALREADY_FULL;      // 规则①：满弹匣按 R 无操作
        }
        if (availableReserve(inventory) <= 0) {
            return ReloadOutcome.NO_RESERVE;        // 规则②的另一侧（PROTOTYPE 口径下不可达）
        }
        reloading = true;
        reloadRemaining = spec.reloadSeconds();
        return ReloadOutcome.STARTED;
    }

    /**
     * 推进时间。
     *
     * <p><b>没有 {@code moving} 参数，也不接受任何"I/O 之外的中断信号"。</b>
     * M2.2 废止"移动打断换弹"后，换弹进度只由时间推进决定：
     * 每步扣掉 {@code dt}，扣到 0 就完成上膛。玩家在换弹期间行走、跳跃、切视角，
     * 都不会改变这条时间线 —— 这正是"边走边换"的实现方式。
     *
     * @param inventory 换弹完成时用来取后备弹药（{@link ReserveMode#PROTOTYPE} 下只读不写）
     */
    public void tick(double dt, Inventory inventory) {
        if (fireCooldown > 0) {
            fireCooldown = Math.max(0, fireCooldown - dt);
        }
        if (!reloading) {
            return;
        }
        reloadRemaining -= dt;
        if (reloadRemaining <= 0) {
            completeReload(inventory);
        }
    }

    private void completeReload(Inventory inventory) {
        reloading = false;
        reloadRemaining = 0;
        int need = spec.magazineSize() - magazineAmmo;
        if (reserveMode.infiniteReserve()) {
            // PROTOTYPE 口径：弹匣补满，背包一个数都不动。
            // 之所以连"假装扣一下再补回"都不做，见类注释里的三条理由
            // （谎报语义 / 造出 count<=0 的非法中间态 / 中间过程不可举证）。
            if (need > 0) {
                magazineAmmo += need;
            }
            reloadsCompleted++;
            return;
        }
        int reserve = reserveAmmo(inventory);
        // 规则③：部分填充 load = min(弹匣容量 − 弹匣内弹药, 后备弹药)
        int load = Math.min(need, reserve);
        // 弹药 ID 数据化：走本枪 spec.ammoId()，不再硬编码 PISTOL_AMMO_ID（M3 §5.2）。
        // 构造期已校验该 ID 能解析到真实弹药，故这里不会拿到 null。
        if (load > 0 && inventory != null && inventory.consumeItem(spec.ammoId(), load)) {
            magazineAmmo += load;   // 规则④：只在完成这一刻转移
        }
        reloadsCompleted++;
    }

    /**
     * 本次判定用的"可用后备弹药"。
     *
     * <p>{@link ReserveMode#PROTOTYPE} 返回 {@link Integer#MAX_VALUE} 而不是某个"很大的数"：
     * 换弹的实际转移量是 {@code min(need, 可用)}，而 {@code need} 最多就是弹匣容量，
     * 因此这里只要是一个远大于容量的数就足够了 —— 用 {@code MAX_VALUE} 的额外好处是
     * "无限"这个值本身不会与任何真实库存数混淆（真实库存上限远小于它）。
     */
    private int availableReserve(Inventory inventory) {
        return reserveMode.infiniteReserve() ? Integer.MAX_VALUE : reserveAmmo(inventory);
    }

    /**
     * 后备弹药数量：读 {@link GunSpec#ammoId()} 对应的真实弹药在背包里的数量。
     *
     * <p>M3 §5.2：这里原来硬读 {@code ItemRegistry.PISTOL_AMMO_ID}，
     * 使本类只服务手枪。现在一律走 {@code spec.ammoId()} 字符串
     * （{@link Inventory#countOfItem(String)} 本就接受 stable ID），本类不再认识枪种。
     * ammoId 的存在性已在构造期校验（见 {@link #GunState(Item, boolean)}）。
     */
    private int reserveAmmo(Inventory inventory) {
        return inventory == null ? 0 : inventory.countOfItem(spec.ammoId());
    }

    // ------------------------------------------------------------ 统计

    public int shotsFired() {
        return shotsFired;
    }

    public int dryFires() {
        return dryFires;
    }

    public int reloadsCompleted() {
        return reloadsCompleted;
    }

    @Override
    public String toString() {
        return gun.id() + " 弹匣 " + magazineAmmo + "/" + spec.magazineSize()
                + (reloading ? String.format(" 换弹中(%.0f%%)", reloadProgress01() * 100) : "");
    }
}

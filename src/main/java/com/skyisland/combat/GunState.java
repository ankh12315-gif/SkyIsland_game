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
 * <h2>M2.1：后备弹药改为「无限」（Combat Prototype 口径）</h2>
 * <p><b>决定（M2.1-A）：默认口径是无限后备</b> —— 见 {@link #INFINITE_RESERVE_DEFAULT}。
 * 这条改动只放宽"弹药从哪来"，<b>不动"必须由玩家按 R 上膛"这条节奏</b>：
 * 弹匣仍是 12 发，打空仍要按 R，换弹仍是 1.2 秒。
 * （M2.2 起这句不再附加"可被移动打断" —— 见上文修订段：边走边换是允许的。）
 * 战斗原型阶段要观测的是"枪战的手感与可读性"，而不是"资源管理的压力测试"；
 * 让玩家在 24 发之后只能站着挨咬，会让每一次批量试玩都在第 25 发戛然而止。
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
 * 有限口径由 {@link #forPistolWithFiniteReserve()} 显式构造并保持单测覆盖，
 * 后续里程碑若要恢复"弹药作为资源"，改一个常量即可，不需要重新推规则。
 */
public final class GunState {

    /**
     * M2.1：新建枪械状态时后备弹药的默认口径 —— {@code true} = 无限。
     *
     * <p>它是 Combat Prototype 这一阶段的<b>产品口径</b>，不是调试开关：
     * 由 {@link #GunState(Item)} 单参构造器与 {@link #forPistol()} 采用，
     * 因此在正常游戏流程里没有第二个值。把它做成常量而不是散落在判断里的
     * 字面量 {@code true}，是为了让"改回有限"这件事只有一处可改。
     */
    public static final boolean INFINITE_RESERVE_DEFAULT = true;

    /** 开始换弹的结果。 */
    public enum ReloadOutcome {
        /** 已开始换弹。 */
        STARTED,
        /** 弹匣已满 —— 按 R 无操作（规则①）。 */
        ALREADY_FULL,
        /**
         * 后备弹药为 0 —— 无法换弹（规则②的另一侧）。
         *
         * <p><b>M2.1 起只在有限后备口径下才可能出现</b>：无限口径下
         * {@link #tryStartReload(Inventory)} 不会返回它（见类注释），
         * 因此产品也不会再显示"没有后备弹药"这条提示。
         * 枚举值保留是为了让有限口径的规则 ② 仍然可判定、可单测。
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
     * 后备弹药口径：{@code true} = 无限（Combat Prototype 默认）。
     *
     * <p>是 {@code final} 而不是运行时可切：一次游戏会话里"弹药到底是资源还是无限"
     * 必须只有一个答案，否则"刚才那一匣是从背包扣的、这一匣不是"会成为无法解释的现象。
     */
    private final boolean infiniteReserve;

    private int shotsFired;
    private int dryFires;
    private int reloadsCompleted;

    /**
     * 按 {@link #INFINITE_RESERVE_DEFAULT} 建一个枪械状态（正常流程走这里）。
     *
     * <p>保留一个单参构造器而不是让调用方到处写第二个实参：
     * {@link com.skyisland.combat.CombatController#gunFor} 在渲染路径上被调用，
     * "创建模式"这件事不该由它决定。
     */
    public GunState(Item gun) {
        this(gun, INFINITE_RESERVE_DEFAULT);
    }

    /**
     * 显式指定后备口径。
     *
     * <p><b>存在理由：让有限后备的规则保持可执行、可断言。</b>
     * 见类注释最后一段 —— 有限口径的第二参数不是为了给玩家用，
     * 而是为了让 PRD 5.4.3 的规则②③不至于退化成死代码。
     */
    public GunState(Item gun, boolean infiniteReserve) {
        if (gun == null || !gun.isGun()) {
            throw new IllegalArgumentException("GunState 只能用于枪械物品: " + gun);
        }
        this.gun = gun;
        this.spec = gun.gun();
        this.infiniteReserve = infiniteReserve;
        this.magazineAmmo = 0;
    }

    /** 手枪开局状态：弹匣为空（PRD 5.7.1 的初始物资另给弹药，需要先上一次膛）。 */
    public static GunState forPistol() {
        return new GunState(ItemRegistry.pistol());
    }

    /**
     * 手枪状态，<b>有限</b>后备口径（M2.1 起仅供单测使用）。
     *
     * <p>用它而不是把 {@link #INFINITE_RESERVE_DEFAULT} 改成 false：
     * 后者会把产品的口径改掉，而这里要的恰恰相反 —— 保留有限规则的可测性，
     * 同时让产品运行在无限口径上。
     */
    public static GunState forPistolWithFiniteReserve() {
        return new GunState(ItemRegistry.pistol(), false);
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
        return infiniteReserve;
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
            return ReloadOutcome.NO_RESERVE;        // 规则②的另一侧（无限口径下不可达）
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
     * @param inventory 换弹完成时用来取后备弹药（无限口径下只读不写）
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
        if (infiniteReserve) {
            // 无限口径：弹匣补满，背包一个数都不动。
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
        if (load > 0 && inventory != null && inventory.consumeItem(ItemRegistry.PISTOL_AMMO_ID, load)) {
            magazineAmmo += load;   // 规则④：只在完成这一刻转移
        }
        reloadsCompleted++;
    }

    /**
     * 本次判定用的"可用后备弹药"。
     *
     * <p>无限口径返回 {@link Integer#MAX_VALUE} 而不是某个"很大的数"：
     * 换弹的实际转移量是 {@code min(need, 可用)}，而 {@code need} 最多就是弹匣容量，
     * 因此这里只要是一个远大于容量的数就足够了 —— 用 {@code MAX_VALUE} 的额外好处是
     * "无限"这个值本身不会与任何真实库存数混淆（真实库存上限远小于它）。
     */
    private int availableReserve(Inventory inventory) {
        return infiniteReserve ? Integer.MAX_VALUE : reserveAmmo(inventory);
    }

    private int reserveAmmo(Inventory inventory) {
        return inventory == null ? 0 : inventory.countOfItem(ItemRegistry.PISTOL_AMMO_ID);
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

package com.skyisland.audio;

import com.skyisland.combat.CombatController;
import com.skyisland.combat.GunState;
import com.skyisland.entity.Entity;
import com.skyisland.item.GunPresentationSpec;
import com.skyisland.player.ItemStack;
import com.skyisland.player.Player;

/**
 * 战斗事件 → 音频事件的翻译层（M2.1）。
 *
 * <h2>为什么单独抽一层，而不是把 {@code audio.play(...)} 直接写在游戏主类里</h2>
 * "在哪一步该响什么"是一条<b>有对错的业务规则</b>，不是机械转发。
 * 抽出来之后它可以被两段证明：一是单测（本行的 {@code AudioFeedbackWiringTest}
 * 直接驱动 {@link CombatController}，断言"开火时确实触发了 gun_fire"），
 * 二是"这一层不认识 GL、不认识窗口"这件事实本身 —— 它在 CI 里也能跑。
 * 塞进主类的话，验证它就只剩"启动一个真窗口、拿枪打一枪、用耳朵听"这一条路，
 * 而无头门禁里根本走不通。
 *
 * <h2>依赖方向</h2>
 * 本类<b>单向依赖</b> {@code combat} 与 {@code player}，被 {@code game} 依赖；
 * {@code combat} 完全不认识 {@code audio} ——
 * 与"{@code combat} 不认识 {@code render}"是同一个理由：
 * 换向的话，"换一套音频实现"就会变成"改玩法代码"。
 *
 * <h2>为什么玩家受伤用轮询而不是回调</h2>
 * {@link Player#hurt} 目前的形态是" void 方法 + 一个 lastDamageAmount 字段"，
 * 没有受伤回调接口。给它加回调属于改动玩法核心类（且触及另一位成员的改动区），
 * 成本与收益不成比例。这里改成在每个逻辑步比对一次当前生命值：
 * <b>下降即视为受伤</b>。
 *
 * <p>轮询的代价是本类要多存一个字段（上一次看到的血量），收益是它顺带覆盖了
 * 一切会让血量下降的入口 —— 包括坠落伤害。首次采样只记基线不发声，
 * 否则"进入世界的第一个逻辑步"会因为基线还是空值而误响一声。
 */
public final class AudioFeedback implements CombatController.Listener {

    private final AudioManager audio;

    /**
     * 枪械音效的查表来源。
     *
     * <p><b>为什么必须持有玩家：</b>三个枪械音效（击发 / 空仓 / 换弹）在 2026-10-03 之前
     * 是写死的 {@code AudioEvent.GUN_FIRE / GUN_EMPTY / RELOAD}，
     * 而 {@code GunPresentationSpec} 的 {@code fireSoundId / emptySoundId / reloadSoundId}
     * 三个键<b>没有任何生产读者</b>（一次标准形态的死接线）。
     * 要让这三声真正由"当前这把枪"决定，本类就得能在事件发生的那一刻
     * 问到"玩家手里拿的是哪把枪" —— 而 {@link CombatController.Listener} 的回调
     * 参数里没有枪，只有坐标与结果。因此玩家是本类必要的第二个构造参数。
     *
     * <p>不缓存表现规格、每次现取：切槽换枪之后"手里那把"会变，
     * 而缓存的失效时机恰恰是最容易漏的地方（与 {@code SkyIslandGame#heldGunPresentation}
     * 同一条取舍）。
     */
    private final Player player;

    /** 上一次看到的玩家生命值；{@code Integer.MIN_VALUE} 表示"还没有基线"。 */
    private int lastHealth = Integer.MIN_VALUE;

    private AudioFeedback(AudioManager audio, Player player) {
        this.audio = audio;
        this.player = player;
    }

    /**
     * 把一个 {@link AudioManager} 包成本监听器。
     *
     * @param player 枪械音效的查表来源（持枪者）；必须非 null，理由见字段注释
     */
    public static AudioFeedback wrap(AudioManager audio, Player player) {
        if (audio == null) {
            throw new IllegalArgumentException("audio 不得为 null");
        }
        if (player == null) {
            throw new IllegalArgumentException(
                    "player 不得为 null —— 枪械音效必须由当前手持枪的表现规格决定，"
                            + "没有玩家就没有可查的那把枪");
        }
        return new AudioFeedback(audio, player);
    }

    /**
     * 当前手持枪械的表现规格；手持物不是枪时为 {@code null}。
     *
     * <p>与方法 {@link #poll} 分开取，是因为它的用途是"查表"：
     * 三处音效<b>都</b>经过它，于是"音效键被读了"这件事只有一处可以断，
     * 也只有一处需要被反向验证打红。
     */
    public GunPresentationSpec heldGunPresentation() {
        if (player == null) {
            return null;
        }
        ItemStack held = player.inventory().selectedStack();
        if (held == null || held.item() == null || !held.item().isGun()) {
            return null;
        }
        return held.item().presentation();
    }

    /**
     * 把本实例与一个下游监听器串起来：先发声音，再把同一个事件转发给 {@code next}。
     *
     * <p><b>为什么是合成一个新监听器而不是"替代"：</b>下游（粒子、HUD 提示、
     * 自测记录器）必须照常工作。替换会让"开了音频之后曳光没了"这种事成为可能，
     * 而这在本接口的语义下是完全静默的。
     */
    public CombatController.Listener andThen(CombatController.Listener next) {
        if (next == null) {
            return this;
        }
        AudioFeedback self = this;
        return new CombatController.Listener() {
            @Override
            public void onShotFired(double mx, double my, double mz,
                                    double ex, double ey, double ez, boolean hitAnything) {
                self.onShotFired(mx, my, mz, ex, ey, ez, hitAnything);
                next.onShotFired(mx, my, mz, ex, ey, ez, hitAnything);
            }

            @Override
            public void onBlockHit(double x, double y, double z,
                                   double nx, double ny, double nz, int blockRuntimeId) {
                self.onBlockHit(x, y, z, nx, ny, nz, blockRuntimeId);
                next.onBlockHit(x, y, z, nx, ny, nz, blockRuntimeId);
            }

            @Override
            public void onEntityHit(Entity entity, int damage, double distance) {
                self.onEntityHit(entity, damage, distance);
                next.onEntityHit(entity, damage, distance);
            }

            @Override
            public void onDryFire() {
                self.onDryFire();
                next.onDryFire();
            }

            @Override
            public void onReloadRequest(GunState.ReloadOutcome outcome) {
                self.onReloadRequest(outcome);
                next.onReloadRequest(outcome);
            }

            @Override
            public void onReloadCompleted(int magazineAmmo, int magazineSize) {
                self.onReloadCompleted(magazineAmmo, magazineSize);
                next.onReloadCompleted(magazineAmmo, magazineSize);
            }

            @Override
            public void onMessage(String textKey, Object... args) {
                self.onMessage(textKey, args);
                next.onMessage(textKey, args);
            }
        };
    }

    // ============================================================ 战斗事件 → 音频事件

    @Override
    public void onShotFired(double muzzleX, double muzzleY, double muzzleZ,
                            double endX, double endY, double endZ, boolean hitAnything) {
        audio.play(fireEvent());
    }

    @Override
    public void onBlockHit(double x, double y, double z,
                           double nx, double ny, double nz, int blockRuntimeId) {
        // M2.1 不为方块命中配音：本阶段只有五个名额，它们优先给了
        // "开火 / 空仓 / 换弹 / 命中怪物 / 玩家受伤"这五件与读懂性关系最大的事。
        // 方块命中的即时反馈目前由溅射粒子承担，视觉上已经足够。
    }

    @Override
    public void onEntityHit(Entity entity, int damage, double distance) {
        audio.play(AudioEvent.HIT_ENEMY);
    }

    @Override
    public void onDryFire() {
        audio.play(emptyEvent());
    }

    @Override
    public void onReloadRequest(GunState.ReloadOutcome outcome) {
        // ★ 换弹的机械声放在"按下 R 并被受理"这一刻，而不是 1.2 秒后的完成时刻。
        //   按键必须立刻有反应：延迟 1.2 秒才响一声的东西，反馈的是"换弹做完了"，
        //   但它错过了最需要确认的那个瞬间（我的按键被受理了吗）。
        if (outcome == GunState.ReloadOutcome.STARTED) {
            audio.play(reloadEvent());
        }
    }

    @Override
    public void onReloadCompleted(int magazineAmmo, int magazineSize) {
        // 见 onReloadRequest：统合音已经在开始时刻播过，这里刻意不再补第二声。
    }

    // ============================================================ 枪械音效查表

    /**
     * 击发音：来自当前手持枪的 {@code fireSoundId}。
     *
     * <p><b>这里刻意<b>不</b>写 {@code AudioEvent.GUN_FIRE}</b>：
     * 写了就等于把"三把枪共用同一条音轨"从"数据上的事实"降级成"代码上的巧合" ——
     * 那样 {@code fireSoundId} 这个键就重新变回死键。
     * 真正的取值只有一处：{@link GunAudio#fireEventOf}，由它去查 {@link AudioEvent#byId}。
     */
    private AudioEvent fireEvent() {
        return GunAudio.fireEventOf(heldGunPresentation());
    }

    /** 空仓音：来自当前手持枪的 {@code emptySoundId}。理由见 {@link #fireEvent}。 */
    private AudioEvent emptyEvent() {
        return GunAudio.emptyEventOf(heldGunPresentation());
    }

    /** 换弹音：来自当前手持枪的 {@code reloadSoundId}。理由见 {@link #fireEvent}。 */
    private AudioEvent reloadEvent() {
        return GunAudio.reloadEventOf(heldGunPresentation());
    }

    @Override
    public void onMessage(String textKey, Object... args) {
        // 文案提示由已有的事件提示通道承担（HUD 上的一行短消息），M2.1 不配音。
    }

    // ============================================================ 玩家受伤（轮询）

    /**
     * 每个逻辑步调用一次：血量下降 → {@link AudioEvent#PLAYER_HURT}。
     *
     * <p><b>必须放在实体 tick 之后</b>（游戏主循环里 {@code entities.tick} 的下一步）：
     * 怪物咬人是在实体 tick 里结算的，放在它之前会让这一声晚整整一个逻辑步 ——
     * 一帧听不出来，但它破坏了"被咬的同一帧就给出反馈"这条因果一致性，
     * 而这恰恰是玩家据以判断"我该退了"的东西。
     */
    public void poll(Player player) {
        if (player == null) {
            return;
        }
        int health = player.health();
        if (lastHealth == Integer.MIN_VALUE) {
            lastHealth = health;
            return;
        }
        if (health < lastHealth) {
            audio.play(AudioEvent.PLAYER_HURT);
        }
        lastHealth = health;
    }

    /** 丢弃已记录的血量基线（读档 / 重生后调用，避免把掉血事件跨会话串起来）。 */
    public void resetHealthBaseline() {
        lastHealth = Integer.MIN_VALUE;
    }

    /** 当前记录的血量基线（{@code Integer.MIN_VALUE} 表示尚无基线），供诊断与测试读取。 */
    public int healthBaseline() {
        return lastHealth;
    }
}

package com.skyisland.ui;

import com.skyisland.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 界面状态机（M1.5 规格第 2/3/8/10 条）。
 *
 * <p><b>本类只做一件事：决定"能不能从 A 走到 B"，并在合法的前提下记住必要的上下文。</b>
 * 它不读键鼠、不画界面、不碰游戏对象 —— 因此它可以被完整地单元测试
 * （包括所有非法迁移），而"ESC 在某个界面下到底该做什么"这类问题
 * 也变成了一句可读的迁移表，而不是散落在事件回调里的 if 链。
 *
 * <p><b>为什么非法迁移是"拒绝 + 告警"而不是抛异常：</b>
 * 状态机的驱动源是输入与脚本，二者都可能出现时序意外（例如一帧内同时收到
 * "关闭设置"与"退出到主菜单"）。抛异常会让一次界面误操作直接终止游戏；
 * 拒绝并告警则保证游戏能继续跑，同时把"发生过一次意料外的迁移请求"留在日志里 ——
 * 这比"看起来什么都没发生"有信息量得多。
 *
 * <p><b>设置菜单来源记忆：</b>设置可以从主菜单打开，也可以从暂停菜单打开。
 * 若不在进入时记住来源，"返回"就不知道该回哪儿 —— 常见错误实现是
 * "设置一律返回主菜单"，于是"暂停 → 设置 → 返回"会把玩家踢出游戏。
 */
public final class UiStateMachine {

    /** 迁移日志上限：只保留最近若干条，避免长时间运行后无界增长。 */
    private static final int HISTORY_LIMIT = 64;

    private final List<String> history = new ArrayList<>();

    private UiState state;

    /** 进入设置界面时所在的界面，用于"返回"。 */
    private UiState settingsOrigin = UiState.MAIN_MENU;

    private boolean quitRequested;

    private int pauseCount;
    private int resumeCount;
    private int rejectedTransitions;

    public UiStateMachine(UiState initial) {
        this.state = initial;
        record("INIT", initial);
        Log.info("[界面] 初始状态 = %s（物理推进=%s，光标锁定=%s）",
                initial.label(), initial.simulationRunning(), initial.mouseCaptured());
    }

    // ============================================================ 查询

    public UiState state() {
        return state;
    }

    public boolean isSimulationRunning() {
        return state.simulationRunning();
    }

    public boolean isMouseCaptured() {
        return state.mouseCaptured();
    }

    public boolean isQuitRequested() {
        return quitRequested;
    }

    /** 进入设置界面前的界面（仅当 {@code state() == SETTINGS} 时有意义）。 */
    public UiState settingsOrigin() {
        return settingsOrigin;
    }

    public int pauseCount() {
        return pauseCount;
    }

    public int resumeCount() {
        return resumeCount;
    }

    public int rejectedTransitions() {
        return rejectedTransitions;
    }

    public List<String> history() {
        return List.copyOf(history);
    }

    // ============================================================ 迁移

    /** 主菜单 → 开始游戏。 */
    public boolean startGame() {
        return transition(UiState.PLAYING, "开始游戏");
    }

    /**
     * 游玩中 → 暂停菜单。
     *
     * <p>从 {@link UiState#INVENTORY}（背包打开时）暂停也定义为<u>合法</u>：
     * 背包不冻结世界，因此"开着背包按暂停"与"游玩中按暂停"语义一致，都转到
     * {@link UiState#PAUSED}。但<b>本方法不处理光标持有物</b> —— 若玩家背包里
     * 正"拿着"某格物品（鼠标拖着的临时持有物），归位 / 丢弃由调用方在调用本方法前负责，
     * 状态机只管界面迁移、不碰背包数据。
     */
    public boolean pause() {
        if (state != UiState.PLAYING && state != UiState.INVENTORY) {
            return reject("暂停", UiState.PAUSED);
        }
        boolean ok = transition(UiState.PAUSED, "暂停");
        if (ok) {
            pauseCount++;
        }
        return ok;
    }

    /** 暂停菜单 → 回到游玩。 */
    public boolean resume() {
        if (state != UiState.PAUSED) {
            return reject("继续游戏", UiState.PLAYING);
        }
        boolean ok = transition(UiState.PLAYING, "继续游戏");
        if (ok) {
            resumeCount++;
        }
        return ok;
    }

    /**
     * 打开设置（可从主菜单或暂停菜单）。
     *
     * <p>从 {@link UiState#INVENTORY}（背包打开时）打开设置定义为<b>不合法</b>，返回
     * {@code false}：背包里再开设置会让"背包到底还开着没"变成歧义（关闭设置后该回到
     * 背包还是游玩？），因此必须先关背包（{@link #closeInventory()}）才能开设置。
     */
    public boolean openSettings() {
        if (state != UiState.MAIN_MENU && state != UiState.PAUSED) {
            return reject("打开设置", UiState.SETTINGS);
        }
        UiState origin = state;
        if (!transition(UiState.SETTINGS, "打开设置")) {
            return false;
        }
        settingsOrigin = origin;
        Log.info("[界面] 设置界面的来源界面 = %s（返回时将回到这里）", origin.label());
        return true;
    }

    /** 关闭设置，回到进入前的界面。 */
    public boolean closeSettings() {
        if (state != UiState.SETTINGS) {
            return reject("关闭设置", settingsOrigin);
        }
        return transition(settingsOrigin, "关闭设置（返回来源界面）");
    }

    /** 游玩中 → 背包界面。其它状态下为非法迁移，返回 {@code false} 且不改变状态。 */
    public boolean openInventory() {
        if (state != UiState.PLAYING) {
            return reject("打开背包", UiState.INVENTORY);
        }
        return transition(UiState.INVENTORY, "打开背包");
    }

    /** 背包界面 → 游玩中。其它状态下为非法迁移，返回 {@code false} 且不改变状态。 */
    public boolean closeInventory() {
        if (state != UiState.INVENTORY) {
            return reject("关闭背包", UiState.PLAYING);
        }
        return transition(UiState.PLAYING, "关闭背包");
    }

    /**
     * 在游玩与背包之间切换（通常绑在 {@code E} / 专用按键上）。
     *
     * <p>{@code PLAYING} → {@code INVENTORY}、{@code INVENTORY} → {@code PLAYING}
     * 为合法；其它状态返回 {@code false}（非法迁移，不改变状态、不记录历史）。
     */
    public boolean toggleInventory() {
        if (state == UiState.PLAYING) {
            return openInventory();
        }
        if (state == UiState.INVENTORY) {
            return closeInventory();
        }
        return reject("切换背包（开/关）", state);
    }

    /** 回到主菜单。可由暂停菜单或设置界面触发。 */
    public boolean backToMainMenu() {
        if (state != UiState.PAUSED && state != UiState.SETTINGS) {
            return reject("返回主菜单", UiState.MAIN_MENU);
        }
        return transition(UiState.MAIN_MENU, "返回主菜单");
    }

    /** 请求退出进程（真正退出由主循环收尾负责，见 {@code SkyIslandGame}）。 */
    public boolean requestQuit() {
        if (quitRequested) {
            return false;
        }
        quitRequested = true;
        record("QUIT", state);
        Log.info("[界面] 已请求退出（当前界面 %s）—— 由主循环收尾，不直接结束进程。", state.label());
        return true;
    }

    /**
     * ESC / 返回键的语义（M1.5 规格第 2 条）。
     *
     * <p>这条规则<u>只在这里</u>定义一次：
     * <ul>
     *   <li>{@code PLAYING} → 暂停（继续按 ESC 可回到游戏）；</li>
     *   <li>{@code PAUSED} → 继续游戏；</li>
     *   <li>{@code INVENTORY} → 关闭背包回到游玩（与 {@code PAUSED} 同级处理）；</li>
     *   <li>{@code SETTINGS} → 返回来源界面；</li>
     *   <li>{@code MAIN_MENU} → 无动作（主菜单是"家"，退出必须显式点"退出游戏"，
     *       否则一次误按 ESC 就结束了游戏）。</li>
     * </ul>
     *
     * @return 该次按键是否产生了状态变化
     */
    public boolean onEscape() {
        switch (state) {
            case PLAYING -> {
                Log.info("[界面] ESC：游玩中 → 暂停菜单");
                return pause();
            }
            case PAUSED -> {
                Log.info("[界面] ESC：暂停中 → 继续游戏");
                return resume();
            }
            case INVENTORY -> {
                Log.info("[界面] ESC：背包界面 → 继续游戏（关闭背包）");
                return closeInventory();
            }
            case SETTINGS -> {
                Log.info("[界面] ESC：设置界面 → 返回 %s", settingsOrigin.label());
                return closeSettings();
            }
            default -> {
                Log.info("[界面] ESC：主菜单下无动作（退出游戏需显式选择）");
                return false;
            }
        }
    }

    // ============================================================ 内部

    private boolean transition(UiState next, String reason) {
        if (state == next) {
            return reject(reason, next);
        }
        UiState from = state;
        state = next;
        record(reason, from, next);
        Log.info("[界面] %s → %s（%s）", from.label(), next.label(), reason);
        return true;
    }

    private boolean reject(String reason, UiState target) {
        rejectedTransitions++;
        Log.warn("[界面] 拒绝迁移：当前 %s，无法执行「%s」（目标 %s）",
                state.label(), reason, target.label());
        return false;
    }

    private void record(String reason, UiState to) {
        add(String.format("%s: %s -> %s", reason, "-", to.label()));
    }

    private void record(String reason, UiState from, UiState to) {
        add(String.format("%s: %s -> %s", reason, from.label(), to.label()));
    }

    private void add(String line) {
        history.add(line);
        if (history.size() > HISTORY_LIMIT) {
            history.remove(0);
        }
    }

    @Override
    public String toString() {
        return "UiStateMachine(" + state.label() + ")";
    }
}

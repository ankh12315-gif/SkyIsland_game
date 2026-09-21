package com.skyisland.ui;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * zh-CN 文案来源（PRD 6.7「术语统一与 Display Name 来源」）。
 *
 * <h2>为什么必须有这个类，而不是"在 UI 代码里直接写中文"</h2>
 * PRD v0.3.2 §6.7 把这条写成了<b>硬约束</b>：
 * <ul>
 *   <li>「玩家可见名称必须有统一的 Display Name 数据来源（localization lookup）」；</li>
 *   <li>「<b>禁止在 Java UI 代码里散落中文字符串字面量</b>」；</li>
 *   <li>「内部 stable ID 保持英文」—— {@code skyisland:pistol} 永远不变成 {@code skyisland:手枪}；</li>
 *   <li>「不得因为当前字体渲染能力（如现有 {@code BitmapFont} 仅覆盖 ASCII}）
 *       而把产品规格降级为英文」。</li>
 * </ul>
 * 最后一条是对 M1.5 的一次明确纠正：M1.5 把 HUD 文案从中文改成了英文，
 * 理由是"点阵字模只覆盖 ASCII"。那是把<b>实现缺口</b>当成了<b>产品决定</b>。
 * M2 的正确做法是补上 CJK 字体渲染（见 {@code CjkFont}），再把文案改回中文。
 *
 * <h2>为什么是"按 stable ID 查表"而不是"给 Block/Item 加 displayName 字段"</h2>
 * 两者都能满足"唯一数据来源"，但查表的<u>改动面只有这一个文件</u>：
 * 不用给 {@code Block} 的构造与 15 行注册表、{@code Item} 的 4 条注册逐个加参数 ——
 * 那类改动一旦漏掉一行，症状是"某个方块没有名字"，而且只在被查到时才暴露。
 * 查表的代价是"未知 id 需要回落策略"，这里用<b>把 stable ID 原样显示</b>回落：
 * 玩家看到 {@code skyisland:oak_log} 至少知道自己在看什么，
 * 而看到一个空字符串只会以为是渲染坏了。
 *
 * <h2>格式化的位置</h2>
 * 带参数的文案（如「击中 近战怪：12.5 格，伤害 8」）在这里一次性定义，
 * 调用方只传值。这样"改文案"不需要去搜调用点，也避免同一条提示在
 * 两处被拼成两种样子。
 */
public final class Localization {

    /** 当前唯一实现的语言（PRD 6.7：本轮只实现 zh-CN）。 */
    public static final String LANG = "zh-CN";

    // ============================================================ 文案 key
    // 全部集中在此，便于报告里逐条核对"哪一句玩家可见文案来自哪里"。

    // ---- HUD 标签 ----
    public static final String HUD_HEALTH = "hud.health";
    public static final String HUD_RELOADING = "hud.reloading";
    public static final String HUD_DEAD = "hud.dead";
    public static final String HUD_AMMO_FORMAT = "hud.ammo.format";
    /**
     * M2.1：后备弹药<b>无限</b>时的弹药格式 —— 形如 {@code 12 / ∞}。
     *
     * <p><b>为什么不复用 {@link #HUD_AMMO_FORMAT} 传一个哨兵值：</b>
     * 那个格式串（{@code "%d / %d"}）已被 {@code LocalizationTest} 逐字符钉死，
     * 只要在它的第二个位置传任何非数字就会迫使 HUD 自己去拼字符串 ——
     * 那正是 PRD 6.7 禁止的"UI 代码里散落文案"。两个格式串并存，
     * 渲染层就仍然只有一次 {@code Localization.text} 调用，没有分支拼接。
     *
     * <p><b>注意 {@code ∞}（U+221E）是一个必须被烘焙的字符：</b>
     * 它不在 {@link com.skyisland.render.ui.CjkFont} 的原始字符集里，
     * 由 {@code GenCjkFont} 链尾的符号补字体 {@code Segoe UI Symbol} 提供。
     * 未烘焙时 {@code BitmapFont} 会把它渲染成空白且<b>不报错</b> ——
     * 因此新增含 {@code ∞} 的文案后<b>必须重跑字形生成器</b>。
     */
    public static final String HUD_AMMO_FORMAT_INFINITE = "hud.ammo.format.infinite";

    // ---- 即时提示（PRD 6.7「即时提示」）----
    public static final String MSG_OUT_OF_AMMO = "msg.out_of_ammo";
    public static final String MSG_INVENTORY_FULL = "msg.inventory_full";
    public static final String MSG_AIM_HINT = "msg.aim_hint";
    public static final String MSG_FIRST_JOIN = "msg.first_join";
    public static final String MSG_RELOADING = "msg.reloading";
    public static final String MSG_RELOAD_DONE = "msg.reload_done";
    public static final String MSG_RELOAD_INTERRUPTED = "msg.reload_interrupted";
    public static final String MSG_MAGAZINE_FULL = "msg.magazine_full";
    public static final String MSG_NO_RESERVE = "msg.no_reserve";
    public static final String MSG_RELOAD_BLOCKS_FIRE = "msg.reload_blocks_fire";
    public static final String MSG_GEAR_GRANTED = "msg.gear_granted";
    public static final String MSG_HIT_ENTITY = "msg.hit_entity";
    public static final String MSG_SAVE_OK = "msg.save_ok";
    public static final String MSG_SAVE_FAILED = "msg.save_failed";

    // ---- 死亡文案（PRD 6.7「死亡文案」）----
    public static final String DEATH_TITLE = "death.title";
    public static final String DEATH_ITEMS_DROPPED = "death.items_dropped";
    public static final String DEATH_NO_DROP = "death.no_drop";

    // ---- 调试 overlay（开发者可见，仍按 PRD 要求走同一张表） ----
    public static final String DBG_MODE = "dbg.mode";

    private static final Map<String, String> TEXT = new HashMap<>();
    private static final Map<String, String> DISPLAY_NAMES = new HashMap<>();

    private static final String UNKNOWN_DISPLAY_SUFFIX = "（未本地化）";

    static {
        // ---------------------------------------------------------- 界面文案
        TEXT.put(HUD_HEALTH, "生命");
        TEXT.put(HUD_RELOADING, "换弹中");
        TEXT.put(HUD_DEAD, "你倒下了");
        // PRD 6.7「数值展示」：弹药显示为「弹匣 / 后备」，如「12 / 36」
        TEXT.put(HUD_AMMO_FORMAT, "%d / %d");
        // M2.1：后备弹药无限时的同一条读数（如「12 / ∞」）
        TEXT.put(HUD_AMMO_FORMAT_INFINITE, "%d / ∞");

        TEXT.put(MSG_OUT_OF_AMMO, "弹药不足");
        TEXT.put(MSG_INVENTORY_FULL, "背包已满");
        TEXT.put(MSG_AIM_HINT, "右键瞄准，R 换弹");
        TEXT.put(MSG_FIRST_JOIN, "WASD 移动，左键挖掘，右键放置");
        TEXT.put(MSG_RELOADING, "换弹中");
        TEXT.put(MSG_RELOAD_DONE, "换弹完成");
        TEXT.put(MSG_RELOAD_INTERRUPTED, "换弹被打断");
        TEXT.put(MSG_MAGAZINE_FULL, "弹匣已满");
        TEXT.put(MSG_NO_RESERVE, "没有后备弹药");
        TEXT.put(MSG_RELOAD_BLOCKS_FIRE, "换弹中，无法开火");
        TEXT.put(MSG_GEAR_GRANTED, "已获得 手枪 + 手枪弹 ×%d");
        TEXT.put(MSG_HIT_ENTITY, "击中 %s：%.1f 格，伤害 %d");
        TEXT.put(MSG_SAVE_OK, "已保存");
        TEXT.put(MSG_SAVE_FAILED, "保存失败");

        TEXT.put(DEATH_TITLE, "你倒下了");
        TEXT.put(DEATH_ITEMS_DROPPED, "物品已掉落");
        TEXT.put(DEATH_NO_DROP, "死亡掉落已关闭");

        TEXT.put(DBG_MODE, "模式");

        // ---------------------------------------------------------- Display Name
        // PRD 6.7 点名的四个是硬性示例（pistol/pistol_ammo/iron_ore/coal），
        // 其余按"MVP Block Registry 里的每一个都必须有名字"补齐 ——
        // 少一个的症状是玩家在准星读数里看到一串英文 id。
        DISPLAY_NAMES.put("skyisland:air", "空气");
        DISPLAY_NAMES.put("skyisland:stone", "石头");
        DISPLAY_NAMES.put("skyisland:cobblestone", "圆石");
        DISPLAY_NAMES.put("skyisland:grass_block", "草方块");
        DISPLAY_NAMES.put("skyisland:dirt", "泥土");
        DISPLAY_NAMES.put("skyisland:glass", "玻璃");
        DISPLAY_NAMES.put("skyisland:sand", "沙子");
        DISPLAY_NAMES.put("skyisland:log", "原木");
        DISPLAY_NAMES.put("skyisland:leaves", "树叶");
        DISPLAY_NAMES.put("skyisland:oak_planks", "橡木木板");
        // 系统方块（非玩家可放置）：资源核心 —— PRD 6.7 的术语表里点名过这个词
        DISPLAY_NAMES.put("skyisland:resource_core", "资源核心");
        DISPLAY_NAMES.put("skyisland:iron_ore", "铁矿石");
        DISPLAY_NAMES.put("skyisland:coal_ore", "煤矿石");
        DISPLAY_NAMES.put("skyisland:torch", "火把");
        DISPLAY_NAMES.put("skyisland:wooden_door", "木门");

        DISPLAY_NAMES.put("skyisland:pistol", "手枪");
        DISPLAY_NAMES.put("skyisland:pistol_ammo", "手枪弹");
        DISPLAY_NAMES.put("skyisland:coal", "煤炭");

        DISPLAY_NAMES.put("skyisland:melee_monster", "近战怪");
    }

    private Localization() {
    }

    /**
     * 取一条界面文案。
     *
     * <p>未登记的 key 返回 key 本身而不是空串/异常：开发期漏登记时，
     * 屏幕上会明晃晃地出现 {@code msg.xxx} —— 这比"少一行字"更容易被发现，
     * 也不会让一次忘登记把整帧渲染搞崩。
     */
    public static String text(String key) {
        String value = TEXT.get(key);
        return value == null ? key : value;
    }

    /** 取一条带占位符的文案并格式化（占位符用 {@code %s/%d/%.1f}）。 */
    public static String text(String key, Object... args) {
        String pattern = text(key);
        return String.format(Locale.ROOT, pattern, args);
    }

    /** 该 key 是否登记了文案（自测断言"每个 key 常量都登记了"用）。 */
    public static boolean hasText(String key) {
        return key != null && TEXT.containsKey(key);
    }

    /**
     * 取<b>未格式化</b>的文案模板；未登记返回 {@code null}。
     *
     * <p>与 {@link #text(String, Object...)} 分开是必要的：带占位符的模板
     * （如 {@code "%d / %d"}）在没有实参时调 {@code String.format} 会抛
     * {@code MissingFormatArgumentException}，于是"检查 key 是否登记"这件事
     * 会被一个格式异常打断。做完整性校验的工具需要一条不触发格式化的读法。
     */
    public static String rawText(String key) {
        return key == null ? null : TEXT.get(key);
    }

    /**
     * 稳定 id → 玩家可见显示名。
     *
     * <p><b>回落策略：原样显示 stable id 并加后缀「（未本地化）」</b>。
     * 不回落成空串（看起来像渲染坏了），也不回落成"未知物品"
     * （那样开发者就再也发现不了自己忘了登记）。
     */
    public static String displayName(String stableId) {
        if (stableId == null) {
            return "";
        }
        String name = DISPLAY_NAMES.get(stableId);
        return name != null ? name : stableId + UNKNOWN_DISPLAY_SUFFIX;
    }

    /** 是否已登记该 id 的显示名（自测断言"MVP 的每个物品/方块都有中文名"用）。 */
    public static boolean hasDisplayName(String stableId) {
        return stableId != null && DISPLAY_NAMES.containsKey(stableId);
    }

    /** 已登记的显示名条数（报告用）。 */
    public static int displayNameCount() {
        return DISPLAY_NAMES.size();
    }

    /** 已登记的文案条数（报告用）。 */
    public static int textCount() {
        return TEXT.size();
    }
}

package com.skyisland.game;

/**
 * 开局装备口径（{@code -Dskyisland.loadout=dev|survival}）。
 *
 * <h2>★ 它分开的是"试玩用的东西"与"正式玩法的东西"</h2>
 * 2026-10-02 为了让步枪链能被验证，开局装备里被塞进了<b>步枪 + 步枪弹 + 一整套原始材料</b>。
 * 那是一次<b>显式的临时偏离</b>：PRD 5.6.2 的配方链默认"材料从世界里挖"，
 * 而本轮范围裁定不做矿石生成，所以材料只能是发出来的。
 *
 * <p>问题是这个偏离<b>没有出口</b>：它挂在唯一一条开局装备路径上，
 * 于是"正式 M3 Survival 的新游戏"与"自测/试玩"共用了同一份发放表 ——
 * 玩家开一局新游戏，背包里就躺着一整套只有为了让断言能跑才存在的材料。
 * 那是把测试夹具当成了产品内容，而它最难被发现的地方在于：
 * 界面、合成、战斗全都因此"看起来能用"，于是没人有动力去补真正的获取链。
 *
 * <p>本枚举把两者拆开：
 * <ul>
 *   <li>{@link #SURVIVAL} —— <b>正式口径，产品默认</b>：M3 的两把枪（手枪 + 冲锋枪）与弹药，
 *       不含步枪、不含材料包；</li>
 *   <li>{@link #DEV} —— <b>DEV / TEST 口径</b>：在 Survival 之上追加步枪、步枪弹与
 *       {@code DEV / TRANSITION MATERIAL KIT}。门禁与 {@code play.bat} 用它。</li>
 * </ul>
 *
 * <h2>为什么"缺 / 写错一律留在 SURVIVAL"</h2>
 * 与 {@code -Dskyisland.infiniteReserve} 同一条原则（见 {@code M1Config#parseInfiniteReserve}）：
 * 手滑写成 {@code =1}、{@code =yes}、{@code =de v} 都不会把正式玩法翻成测试口径。
 * 两种错法的代价不对称 —— 错字导致"没拿到材料包"只是一次试玩少点材料，
 * 而错字导致"正式存档开局就发一把步枪和整套材料"会污染玩法本身。
 *
 * <h2>为什么不做成"细粒度开关"</h2>
 * 曾经考虑过 {@code grantRifle} / {@code grantKit} 两个独立布尔。
 * 否决理由：那会让"试玩口径"变成一个<b>组合</b>，于是门禁跑的那一套与玩家跑的那一套
 * 之间又有了一层"到底开没开某个开关"的疑问，而本枚举要消灭的正是这层疑问。
 * 两个口径、两个名字、一句话说得清：dev = 试玩，survival = 正式。
 */
public enum Loadout {

    /** 正式玩法口径：M3 的两把枪（手枪 + 冲锋枪）与它们的弹药。<b>产品默认。</b> */
    SURVIVAL,

    /**
     * DEV / TEST 口径：Survival 全部内容，外加步枪、步枪弹与过渡材料包。
     *
     * <p>它是<b>过渡性的</b>：等"矿石世界生成 + 正式合成获取链"闭合之后，
     * 材料包必须被移除（保留会让"从零采集"这条闭环失去意义），
     * 而步枪应当改由正式获取链提供。
     */
    DEV;

    /** 玩家可见开关的属性名。 */
    public static final String SYSTEM_PROPERTY = "skyisland.loadout";

    /** 缺省口径 = 正式玩法。 */
    public static final Loadout DEFAULT = SURVIVAL;

    /**
     * 解析开关：<b>只有显式写 {@code dev} 才切 DEV 口径</b>，其余一切（含错字）留在 SURVIVAL。
     *
     * @param raw 原始属性值；{@code null} = 属性没给（双击启动器的默认情形）
     */
    public static Loadout parse(String raw) {
        return raw != null && "dev".equalsIgnoreCase(raw.trim()) ? DEV : SURVIVAL;
    }

    /** 本口径是否发放步枪与步枪弹（步枪属 DEV 期的验证对象，不在 M3 Survival 内）。 */
    public boolean grantsRifle() {
        return this == DEV;
    }

    /** 本口径是否发放 DEV / TRANSITION MATERIAL KIT。 */
    public boolean grantsMaterialKit() {
        return this == DEV;
    }

    /** 启动日志用的中文说明（写在这里，免得每处调用各写一份口径描述）。 */
    public String describe() {
        return switch (this) {
            case SURVIVAL -> "SURVIVAL（正式：手枪 + 冲锋枪 + 弹药；无步枪、无材料包）";
            case DEV -> "DEV / TEST（正式内容 + 步枪 + 步枪弹 + 过渡材料包）";
        };
    }
}

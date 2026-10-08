package com.skyisland.world.block;

/**
 * 方块的**创造面板分组**（PRD_BLOCK_CREATIVE_v1.0.md §5.1「排序」行：
 * 「按 {@code RecipeCategory}-式的分类分组：自然 / 建材 / 矿物 / 作物」）。
 *
 * <h2>★ 为什么不能复用 {@link com.skyisland.craft.RecipeCategory}</h2>
 * 两者名字都叫"分类"，但<b>维度完全不同</b>，复用会让代码读起来像是对的：
 * <table border="1">
 *   <tr><th></th><th>{@code RecipeCategory}</th><th>本枚举</th></tr>
 *   <tr><td>分类对象</td><td><b>配方</b>（玩家合成什么）</td><td><b>方块</b>（世界里放什么）</td></tr>
 *   <tr><td>取值</td><td>BUILD / MATERIAL / AMMO / GUN / FOOD</td>
 *       <td>NATURAL / BUILDING / MINERAL / CROP</td></tr>
 * </table>
 * ★ 更要紧的是：{@code RecipeCategory} 里有 <b>AMMO / GUN / FOOD</b> 三个取值，
 * 而 <b>创造面板里不许出现枪械与弹药</b>（PRD §5.1 末行 / §5.6）——
 * 复用它会让"面板可能出现 GUN 分组"这种状态<b>在类型上就成立</b>。
 *
 * <h2>★ 分类是 UI 展示属性，不进 {@link Block}</h2>
 * 它不影响物理、不影响掉落、不影响存档。把一个纯展示字段塞进领域对象，
 * 会让"这个字段是谁的职责"变成一句需要查代码才能回答的话
 * —— 而这正是本项目反复付过学费的那类"读代码才知道它存在"。
 * 因此它由 {@link CreativePalette} 独占持有，{@link Block} 一字未改。
 *
 * <h2>为什么是枚举而不是字符串</h2>
 * 与 {@code RecipeCategory} 同一条理由：PRD 的四类是一个<b>封闭集合</b>，
 * 枚举让"PRD 新增了第五类"变成编译期错误，而不是一个谁都不会发现的拼写差异。
 */
public enum BlockCategory {

    /** 自然（泥土 / 草方块 / 沙 / 原木 / 树叶 …）。 */
    NATURAL("自然"),

    /** 建材（木板 / 玻璃 / 圆石 / 石砖 / 铁块 / 台阶 / 火把 / 木门 …）。 */
    BUILDING("建材"),

    /** 矿物（铁 / 煤 / 铜 / 晶体 / 金矿石 …）。 */
    MINERAL("矿物"),

    /** 作物（小麦 …）。 */
    CROP("作物");

    /** 面板上的中文分组标题。 */
    private final String displayName;

    BlockCategory(String displayName) {
        this.displayName = displayName;
    }

    /** 面板上的中文分组标题。 */
    public String displayName() {
        return displayName;
    }
}

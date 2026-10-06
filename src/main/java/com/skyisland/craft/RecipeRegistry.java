package com.skyisland.craft;

import com.skyisland.item.ItemRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配方注册表（PRD 5.6.2 的整张表）。
 *
 * <p><b>冻结语义与 {@link ItemRegistry} / {@code BlockRegistry} 一致：</b>
 * {@code bootstrap()} 结束后 {@code bootstrapped = true}，此后任何追加都抛异常。
 * 理由也一样 —— 配方在会话期内不该漂移，否则"同一个背包点两次合成得到不同结果"
 * 这类问题会以最难复现的形式出现。
 *
 * <p><b>本类最有价值的一条校验是 {@link #verify()} 的"悬空引用"检查：</b>
 * 每一条配方的产物与每一项材料都必须能在 {@link ItemRegistry} 里查到。
 * 这条检查的存在理由，正是本项目已经付过学费的那类缺陷 ——
 * <b>引用了不存在的东西，而没有任何地方会报错</b>：
 * <ul>
 *   <li>配方写 {@code skyisland:rifle_ammo} 但那个物品没注册 →
 *       合成的产物会静默变成"空槽"（{@code runtimeIdOf} 的降级路径），
 *       表现为"合成了但东西没了"；</li>
 *   <li>配方写 {@code skyisland:copper_ore} 但方块没登记 → 这条配方<b>永远无法满足</b>，
 *       而缺料提示会一直显示"缺少 铜矿石 ×1"，看起来像玩家没挖够。</li>
 * </ul>
 * 两种症状都指向"数据错了"，但都不会自己说出来。因此把它们变成启动期异常。
 *
 * <p><b>v2 §6.2 的规则在这里体现：</b>"以后真正落地 rifle 时，
 * {@code skyisland:rifle_ammo} 必须注册为 Item，再新增相应 Recipe，<b>禁止只添加字符串标签</b>。"
 * —— {@link #verify()} 就是"禁止只添加字符串标签"这条规则的执行者。
 */
public final class RecipeRegistry {

    // ---- 配方稳定 ID（TECH_DESIGN §L.5 的命名形状：skyisland:r<编号>_<产物>）----

    /** R01 木板：原木 ×1 → 木板 ×4。 */
    public static final String R01_OAK_PLANKS = "skyisland:r01_oak_planks";
    /** R02 木棍：木板 ×2 → 木棍 ×4。 */
    public static final String R02_STICK = "skyisland:r02_stick";
    /** R03 铁锭：铁矿石 ×1 + 煤炭 ×1 → 铁锭 ×1。 */
    public static final String R03_IRON_INGOT = "skyisland:r03_iron_ingot";
    /** R04 铜锭：铜矿石 ×1 + 煤炭 ×1 → 铜锭 ×1（【Alpha 必须】）。 */
    public static final String R04_COPPER_INGOT = "skyisland:r04_copper_ingot";
    /** R06 火药：煤炭 ×2 + 沙子 ×1 → 火药 ×2。 */
    public static final String R06_GUNPOWDER = "skyisland:r06_gunpowder";
    /** R07 火把：煤炭 ×1 + 木棍 ×1 → 火把 ×4。 */
    public static final String R07_TORCH = "skyisland:r07_torch";
    /** R08 玻璃：沙子 ×1 + 煤炭 ×1 → 玻璃 ×1。 */
    public static final String R08_GLASS = "skyisland:r08_glass";
    /** R11 手枪弹：铁锭 ×1 + 火药 ×1 → 手枪弹 ×8。 */
    public static final String R11_PISTOL_AMMO = "skyisland:r11_pistol_ammo";
    /** R12 步枪弹：铁锭 ×1 + 火药 ×2 → 步枪弹 ×6（【Alpha 必须】）。 */
    public static final String R12_RIFLE_AMMO = "skyisland:r12_rifle_ammo";
    /** R16 步枪：铁锭 ×8 + 铜锭 ×3 + 晶体 ×1 + 火药 ×6 + 木棍 ×2 → 步枪 ×1（【Alpha 必须】）。 */
    public static final String R16_RIFLE = "skyisland:r16_rifle";

    private static final List<Recipe> BY_ORDER = new ArrayList<>();
    private static final Map<String, Recipe> BY_ID = new HashMap<>();

    private static boolean bootstrapped = false;

    static {
        bootstrap();
    }

    private RecipeRegistry() {
    }

    // ============================================================ 注册

    /**
     * 本轮落地的配方集合 = <b>PRD 的 MVP 7 条 + 步枪链 3 条</b>。
     *
     * <p>为什么把 MVP 的 7 条一起做了：R16 步枪的材料链会穿过
     * {@code 木棍（R02）→ 木板（R01）}、{@code 铁锭（R03）}、{@code 火药（R06）} 三条 MVP 配方。
     * 只做步枪链那几条会让配方表出现"指向一条不存在的配方"的半截状态，
     * 而 MVP 那 7 条本来就一个都没实现过 —— 一并补齐后，"配方表 = PRD 5.6.2 的前半张"
     * 这件事才是一个完整、可核对的整体。
     *
     * <p>未实现的是 Alpha 的其余条目（R05 金锭 / R09 木门 / R10 面包 / R13 霰弹 /
     * R14 手枪 / R15 冲锋枪 / R17 霰弹枪 / R18 石砖）与【后续迭代】的 R19–R22。
     * 它们各自的输入物（金矿石 / 小麦 / 石英…）本轮没有登记，
     * 强行写进来会立刻被 {@link #verify()} 的悬空引用检查拒绝 —— 这正是那道检查的用途。
     */
    private static void bootstrap() {
        // ---------------- MVP 7 条（PRD 5.6.2，行号序）----------------

        // R01：原木 ×1 → 木板 ×4。原木与木板都是已登记的方块物品。
        register(new Recipe(R01_OAK_PLANKS, "skyisland:oak_planks", 4,
                List.of(new Recipe.Ingredient("skyisland:log", 1)),
                RecipeCategory.BUILD));
        // R02：木板 ×2 → 木棍 ×4。
        register(new Recipe(R02_STICK, "skyisland:stick", 4,
                List.of(new Recipe.Ingredient("skyisland:oak_planks", 2)),
                RecipeCategory.BUILD));
        // R03：铁矿石 ×1 + 煤炭 ×1 → 铁锭 ×1。
        // 注意输入是**铁矿石方块物品**（铁矿石掉落自身），不是"某种矿石物品"。
        register(new Recipe(R03_IRON_INGOT, "skyisland:iron_ingot", 1,
                List.of(new Recipe.Ingredient("skyisland:iron_ore", 1),
                        new Recipe.Ingredient("skyisland:coal", 1)),
                RecipeCategory.MATERIAL));
        // R06：煤炭 ×2 + 沙子 ×1 → 火药 ×2。
        register(new Recipe(R06_GUNPOWDER, "skyisland:gunpowder", 2,
                List.of(new Recipe.Ingredient("skyisland:coal", 2),
                        new Recipe.Ingredient("skyisland:sand", 1)),
                RecipeCategory.MATERIAL));
        // R07：煤炭 ×1 + 木棍 ×1 → 火把 ×4。
        register(new Recipe(R07_TORCH, "skyisland:torch", 4,
                List.of(new Recipe.Ingredient("skyisland:coal", 1),
                        new Recipe.Ingredient("skyisland:stick", 1)),
                RecipeCategory.BUILD));
        // R08：沙子 ×1 + 煤炭 ×1 → 玻璃 ×1。
        register(new Recipe(R08_GLASS, "skyisland:glass", 1,
                List.of(new Recipe.Ingredient("skyisland:sand", 1),
                        new Recipe.Ingredient("skyisland:coal", 1)),
                RecipeCategory.BUILD));
        // R11：铁锭 ×1 + 火药 ×1 → 手枪弹 ×8。
        // PRD v0.3 的修正：手枪弹永久改用**铁锭**（原铜锭）——
        // 否则 MVP 的"远征 → 弹药"链路在数据层不可能闭合（铜矿石属 Alpha）。
        register(new Recipe(R11_PISTOL_AMMO, "skyisland:pistol_ammo", 8,
                List.of(new Recipe.Ingredient("skyisland:iron_ingot", 1),
                        new Recipe.Ingredient("skyisland:gunpowder", 1)),
                RecipeCategory.AMMO));

        // ---------------- 步枪链 3 条（PRD 5.6.2，【Alpha 必须】）----------------

        // R04：铜矿石 ×1 + 煤炭 ×1 → 铜锭 ×1。
        register(new Recipe(R04_COPPER_INGOT, "skyisland:copper_ingot", 1,
                List.of(new Recipe.Ingredient("skyisland:copper_ore", 1),
                        new Recipe.Ingredient("skyisland:coal", 1)),
                RecipeCategory.MATERIAL));
        // R12：铁锭 ×1 + 火药 ×2 → 步枪弹 ×6。
        register(new Recipe(R12_RIFLE_AMMO, "skyisland:rifle_ammo", 6,
                List.of(new Recipe.Ingredient("skyisland:iron_ingot", 1),
                        new Recipe.Ingredient("skyisland:gunpowder", 2)),
                RecipeCategory.AMMO));
        // R16：铁锭 ×8 + 铜锭 ×3 + 晶体 ×1 + 火药 ×6 + 木棍 ×2 → 步枪 ×1。
        // 材料顺序逐字照抄 PRD 5.6.2 的"材料"列，便于逐项对照。
        register(new Recipe(R16_RIFLE, "skyisland:rifle", 1,
                List.of(new Recipe.Ingredient("skyisland:iron_ingot", 8),
                        new Recipe.Ingredient("skyisland:copper_ingot", 3),
                        new Recipe.Ingredient("skyisland:crystal", 1),
                        new Recipe.Ingredient("skyisland:gunpowder", 6),
                        new Recipe.Ingredient("skyisland:stick", 2)),
                RecipeCategory.GUN));

        bootstrapped = true;
        verify();
    }

    private static void register(Recipe recipe) {
        if (bootstrapped) {
            throw new IllegalStateException("配方注册表已冻结，不允许运行期追加配方: " + recipe.id());
        }
        if (BY_ID.containsKey(recipe.id())) {
            throw new IllegalStateException("配方 ID 重复注册: " + recipe.id());
        }
        BY_ORDER.add(recipe);
        BY_ID.put(recipe.id(), recipe);
    }

    /**
     * 校验<b>没有悬空引用</b>：每条配方的产物与每一项材料都必须是已登记的真实物品。
     *
     * <p>见类文档：这是"禁止只添加字符串标签"（v2 §6.2）的执行者。
     */
    private static void verify() {
        Set<String> referenced = new HashSet<>();
        for (Recipe r : BY_ORDER) {
            requireRegisteredItem(r.outputItemId(), "产物", r.id());
            referenced.add(r.outputItemId());
            for (Recipe.Ingredient ing : r.ingredients()) {
                requireRegisteredItem(ing.itemId(), "材料", r.id());
                referenced.add(ing.itemId());
            }
        }
    }

    private static void requireRegisteredItem(String stableId, String role, String recipeId) {
        if (ItemRegistry.byName(stableId) == null) {
            throw new IllegalStateException(
                    "配方引用了未登记的物品（悬空引用）：配方=" + recipeId
                            + " / " + role + "=" + stableId
                            + "。请先在 ItemRegistry 里登记该物品（或该物品对应的方块）。");
        }
    }

    // ============================================================ 查询

    /** 按稳定 ID 查配方；不存在返回 {@code null}。 */
    public static Recipe byId(String recipeId) {
        return BY_ID.get(recipeId);
    }

    /** 是否存在该配方。 */
    public static boolean has(String recipeId) {
        return BY_ID.containsKey(recipeId);
    }

    /** 全部配方（注册顺序 = PRD 5.6.2 的行序，便于逐行核对）。 */
    public static List<Recipe> all() {
        return Collections.unmodifiableList(BY_ORDER);
    }

    /** 已登记配方数量。 */
    public static int size() {
        return BY_ORDER.size();
    }

    // ---- 常用配方的直取（避免调用方到处写字符串）----

    /** R16 步枪。 */
    public static Recipe rifle() {
        return byId(R16_RIFLE);
    }

    /** R12 步枪弹。 */
    public static Recipe rifleAmmo() {
        return byId(R12_RIFLE_AMMO);
    }
}

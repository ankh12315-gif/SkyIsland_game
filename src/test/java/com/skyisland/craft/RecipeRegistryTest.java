package com.skyisland.craft;

import com.skyisland.item.ItemRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配方注册表测试：<b>把 PRD 5.6.2 的那张表钉进代码</b>。
 *
 * <p>这一组断言的存在理由很直接：配方是"设计数据"，它的权威来源是 PRD 那一行。
 * 如果只在 {@code RecipeRegistry} 里写一遍而没有测试对照，那么"某个数字被顺手改了"
 * 不会有人发现 —— 直到玩家发现步枪突然只要 4 个铁锭。
 */
class RecipeRegistryTest {

    // ============================================================ PRD 5.6.2 逐行对照

    @Test
    void theTenRecipesMatchPrd562Verbatim() {
        assertEquals(10, RecipeRegistry.size(), "本轮落地 10 条配方（MVP 7 条 + 步枪链 3 条）");

        // ---------------- MVP 7 条 ----------------
        assertRecipe(RecipeRegistry.R01_OAK_PLANKS, "skyisland:oak_planks", 4,
                RecipeCategory.BUILD, "skyisland:log", 1);
        assertRecipe(RecipeRegistry.R02_STICK, "skyisland:stick", 4,
                RecipeCategory.BUILD, "skyisland:oak_planks", 2);
        assertRecipe(RecipeRegistry.R03_IRON_INGOT, "skyisland:iron_ingot", 1,
                RecipeCategory.MATERIAL,
                "skyisland:iron_ore", 1, "skyisland:coal", 1);
        assertRecipe(RecipeRegistry.R06_GUNPOWDER, "skyisland:gunpowder", 2,
                RecipeCategory.MATERIAL,
                "skyisland:coal", 2, "skyisland:sand", 1);
        assertRecipe(RecipeRegistry.R07_TORCH, "skyisland:torch", 4,
                RecipeCategory.BUILD,
                "skyisland:coal", 1, "skyisland:stick", 1);
        assertRecipe(RecipeRegistry.R08_GLASS, "skyisland:glass", 1,
                RecipeCategory.BUILD,
                "skyisland:sand", 1, "skyisland:coal", 1);
        assertRecipe(RecipeRegistry.R11_PISTOL_AMMO, "skyisland:pistol_ammo", 8,
                RecipeCategory.AMMO,
                "skyisland:iron_ingot", 1, "skyisland:gunpowder", 1);

        // ---------------- 步枪链 3 条（【Alpha 必须】）----------------
        assertRecipe(RecipeRegistry.R04_COPPER_INGOT, "skyisland:copper_ingot", 1,
                RecipeCategory.MATERIAL,
                "skyisland:copper_ore", 1, "skyisland:coal", 1);
        assertRecipe(RecipeRegistry.R12_RIFLE_AMMO, "skyisland:rifle_ammo", 6,
                RecipeCategory.AMMO,
                "skyisland:iron_ingot", 1, "skyisland:gunpowder", 2);
        // R16 的 5 项材料顺序逐字照抄 PRD 5.6.2 的"材料"列。
        assertRecipe(RecipeRegistry.R16_RIFLE, "skyisland:rifle", 1,
                RecipeCategory.GUN,
                "skyisland:iron_ingot", 8,
                "skyisland:copper_ingot", 3,
                "skyisland:crystal", 1,
                "skyisland:gunpowder", 6,
                "skyisland:stick", 2);
    }

    /**
     * 断言一条配方的产物、数量、分类与材料表（{@code 物品, 数量, 物品, 数量, …}）。
     *
     * <p>材料顺序也一并断言：PRD 5.6.2 的"材料"列有固定书写顺序，
     * 缺料提示按同一顺序显示，因此顺序是产品行为的一部分，不只是排版。
     */
    private static void assertRecipe(String id, String outputId, int outputCount,
                                     RecipeCategory category, Object... itemCountPairs) {
        Recipe r = RecipeRegistry.byId(id);
        assertNotNull(r, "配方必须已登记: " + id);
        assertEquals(outputId, r.outputItemId(), "产物不符（" + id + "）");
        assertEquals(outputCount, r.outputCount(), "产出数量不符（" + id + "）");
        assertEquals(category, r.category(), "分类不符（" + id + "）");
        assertEquals(itemCountPairs.length / 2, r.ingredients().size(),
                "材料项数不符（" + id + "）");
        for (int i = 0; i < itemCountPairs.length; i += 2) {
            String expectedItem = (String) itemCountPairs[i];
            int expectedCount = (Integer) itemCountPairs[i + 1];
            Recipe.Ingredient actual = r.ingredients().get(i / 2);
            assertEquals(expectedItem, actual.itemId(),
                    "第 " + (i / 2 + 1) + " 项材料物品不符（" + id + "）");
            assertEquals(expectedCount, actual.count(),
                    "第 " + (i / 2 + 1) + " 项材料数量不符（" + id + "）");
        }
    }

    // ============================================================ 悬空引用（本类最重要的护栏）

    /**
     * 每条配方引用的每一个物品都必须是<b>真实注册</b>的 Item。
     *
     * <p>这条断言是本项目"死接线"教训的配方版：配方写了一个不存在的物品 ID 时，
     * 不会有任何地方报错 —— 合成的产物会静默变成空槽，或者那条配方永远无法满足。
     * {@code RecipeRegistry.verify()} 在 bootstrap 时已经会抛异常拦下它，
     * 这里再把整张表扫一遍，作为"这道检查确实在跑"的证据。
     */
    @Test
    void everyRecipeReferenceResolvesToARegisteredItem() {
        for (Recipe r : RecipeRegistry.all()) {
            assertNotNull(ItemRegistry.byName(r.outputItemId()),
                    "产物必须是已登记物品（悬空引用）: " + r.id() + " → " + r.outputItemId());
            for (Recipe.Ingredient ing : r.ingredients()) {
                assertNotNull(ItemRegistry.byName(ing.itemId()),
                        "材料必须是已登记物品（悬空引用）: " + r.id() + " ← " + ing.itemId());
            }
        }
        // 抽查一条具体的：步枪弹必须真的存在（v2 §6.2 的"禁止只添加字符串标签"）
        assertNotNull(ItemRegistry.rifleAmmo(), "步枪弹必须真实注册，否则 R12 就是空转");
    }

    @Test
    void recipeIdsAreUniqueAndNamespaced() {
        Set<String> seen = new HashSet<>();
        for (Recipe r : RecipeRegistry.all()) {
            assertTrue(r.id().startsWith("skyisland:"), "配方 ID 必须带命名空间: " + r.id());
            assertTrue(seen.add(r.id()), "配方 ID 重复: " + r.id());
        }
    }

    /** 按 ID 能取回同一条配方（存档 / 界面回读走这条路径）。 */
    @Test
    void lookupByIdRoundTrips() {
        for (Recipe r : RecipeRegistry.all()) {
            assertSame(r, RecipeRegistry.byId(r.id()));
            assertTrue(RecipeRegistry.has(r.id()));
        }
        assertNull(RecipeRegistry.byId("skyisland:r99_nope"));
        assertSame(RecipeRegistry.byId(RecipeRegistry.R16_RIFLE), RecipeRegistry.rifle(),
                "R16 步枪的直取入口必须与按 ID 查的结果一致");
        assertSame(RecipeRegistry.byId(RecipeRegistry.R12_RIFLE_AMMO), RecipeRegistry.rifleAmmo());
    }

    /** 注册顺序 = PRD 5.6.2 的书写顺序（MVP 7 条在前，步枪链 3 条在后）。 */
    @Test
    void registrationOrderFollowsThePrdTable() {
        List<String> ids = RecipeRegistry.all().stream().map(Recipe::id).toList();
        assertEquals(List.of(
                RecipeRegistry.R01_OAK_PLANKS,
                RecipeRegistry.R02_STICK,
                RecipeRegistry.R03_IRON_INGOT,
                RecipeRegistry.R06_GUNPOWDER,
                RecipeRegistry.R07_TORCH,
                RecipeRegistry.R08_GLASS,
                RecipeRegistry.R11_PISTOL_AMMO,
                RecipeRegistry.R04_COPPER_INGOT,
                RecipeRegistry.R12_RIFLE_AMMO,
                RecipeRegistry.R16_RIFLE), ids);
    }

    // ============================================================ Recipe 自身的构造校验

    @Test
    void aRecipeRejectsEmptyIngredients() {
        assertThrows(IllegalArgumentException.class, () -> new Recipe(
                "skyisland:r_bad", "skyisland:stone", 1, List.of(), RecipeCategory.BUILD),
                "空配方等于白送，PRD 里不存在这种配方");
    }

    @Test
    void aRecipeRejectsDuplicateIngredientsOfTheSameItem() {
        assertThrows(IllegalArgumentException.class, () -> new Recipe(
                "skyisland:r_bad", "skyisland:glass", 1,
                List.of(new Recipe.Ingredient("skyisland:coal", 1),
                        new Recipe.Ingredient("skyisland:coal", 2)),
                RecipeCategory.BUILD),
                "重复登记同一材料几乎一定是笔误，应在构造期拒绝而不是合并");
    }

    @Test
    void aRecipeRejectsItsOwnOutputAsAnIngredient() {
        assertThrows(IllegalArgumentException.class, () -> new Recipe(
                "skyisland:r_bad", "skyisland:coal", 1,
                List.of(new Recipe.Ingredient("skyisland:coal", 1)),
                RecipeCategory.MATERIAL),
                "自环配方（产物是自己的材料）不允许");
    }

    @Test
    void aRecipeRejectsNonPositiveCountsAndBlankIds() {
        assertThrows(IllegalArgumentException.class, () -> new Recipe(
                "", "skyisland:stone", 1,
                List.of(new Recipe.Ingredient("skyisland:coal", 1)), RecipeCategory.BUILD));
        assertThrows(IllegalArgumentException.class, () -> new Recipe(
                "skyisland:r_bad", "skyisland:stone", 0,
                List.of(new Recipe.Ingredient("skyisland:coal", 1)), RecipeCategory.BUILD));
        assertThrows(IllegalArgumentException.class, () -> new Recipe.Ingredient("skyisland:coal", 0));
        assertThrows(IllegalArgumentException.class, () -> new Recipe.Ingredient("  ", 1));
    }

    /** Recipe 的身份是它的 ID（同 ID 的两条记录相等）。 */
    @Test
    void recipeIdentityIsItsId() {
        Recipe a = new Recipe("skyisland:r_x", "skyisland:glass", 1,
                List.of(new Recipe.Ingredient("skyisland:sand", 1)), RecipeCategory.BUILD);
        Recipe b = new Recipe("skyisland:r_x", "skyisland:stone", 9,
                List.of(new Recipe.Ingredient("skyisland:coal", 1)), RecipeCategory.MATERIAL);
        assertEquals(a, b, "同 ID 即同一条配方");
        assertEquals(a.hashCode(), b.hashCode());
    }
}

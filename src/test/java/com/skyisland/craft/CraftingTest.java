package com.skyisland.craft;

import com.skyisland.item.ItemRegistry;
import com.skyisland.player.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合成逻辑测试（{@link Crafting}）。
 *
 * <p>这一组断言守的是三条容易写错、且写错之后症状不明显的规则：
 * <ol>
 *   <li><b>按"总拥有量"校验材料</b>，不是按单个槽位 ——
 *       写错的症状是"背包里明明有 8 个铁锭却合不了"（因为它们分散在 8 格里）；</li>
 *   <li><b>失败时背包一字不改</b> —— 写错的症状是"合失败但材料少了"；</li>
 *   <li><b>"产出放不下"必须按"扣完材料之后"的状态判断</b> ——
 *       写错的症状是"背包满时合成被拒，但其实合了就能放下"。</li>
 * </ol>
 */
class CraftingTest {

    private static int rid(String stableId) {
        return ItemRegistry.runtimeIdOf(stableId);
    }

    private static void give(Inventory inv, String stableId, int count) {
        int leftover = inv.add(rid(stableId), count);
        assertEquals(0, leftover, "测试准备阶段应当能把 " + stableId + " ×" + count + " 放进背包");
    }

    // ============================================================ 正常路径

    @Test
    void craftConsumesExactlyTheRecipeAmountsAndAddsTheOutput() {
        Inventory inv = new Inventory();
        give(inv, "skyisland:iron_ore", 3);
        give(inv, "skyisland:coal", 5);

        Crafting.Outcome out = Crafting.craft(inv, RecipeRegistry.byId(RecipeRegistry.R03_IRON_INGOT));

        assertEquals(Crafting.Result.CRAFTED, out.result());
        assertTrue(out.crafted());
        assertTrue(out.shortfalls().isEmpty());
        assertEquals(1, inv.countOfItem("skyisland:iron_ingot"), "R03 产出铁锭 ×1");
        assertEquals(2, inv.countOfItem("skyisland:iron_ore"), "只扣 1 个铁矿石");
        assertEquals(4, inv.countOfItem("skyisland:coal"), "只扣 1 个煤炭");
    }

    /**
     * ★ 材料拆散在多个槽位里仍然能合成（"按总拥有量"而非"按单槽"）。
     *
     * <p>R12 需要火药 ×2。这里刻意把火药分装成<b>两个各 1 个</b>的槽位 ——
     * 按槽位校验的实现会判成"只有 1 个火药"而拒绝合成，
     * 而那正是 TECH_DESIGN 点名过的那个 bug（玩家："明明有材料却合不了"）。
     */
    @Test
    void materialsSpreadAcrossSeveralSlotsStillCraft() {
        Inventory inv = new Inventory();
        give(inv, "skyisland:iron_ingot", 1);
        // 火药分两格，各 1 个
        inv.setSlot(0, com.skyisland.player.ItemStack.of(rid("skyisland:gunpowder"), 1));
        inv.setSlot(1, com.skyisland.player.ItemStack.of(rid("skyisland:gunpowder"), 1));
        assertEquals(2, inv.countOfItem("skyisland:gunpowder"), "前提：总和是 2");

        Crafting.Outcome out = Crafting.craft(inv, RecipeRegistry.rifleAmmo());

        assertEquals(Crafting.Result.CRAFTED, out.result(),
                "火药分散在两格里必须仍被算作 2 个 —— 这是『按总拥有量校验』的核心");
        assertEquals(6, inv.countOfItem("skyisland:rifle_ammo"), "R12 产出步枪弹 ×6");
        assertEquals(0, inv.countOfItem("skyisland:gunpowder"));
        assertEquals(0, inv.countOfItem("skyisland:iron_ingot"));
    }

    // ============================================================ 失败路径（原子性）

    @Test
    void craftRefusesAndLeavesInventoryUntouchedWhenMaterialsAreShort() {
        Inventory inv = new Inventory();
        give(inv, "skyisland:iron_ingot", 7);   // 差 1 个
        give(inv, "skyisland:copper_ingot", 3);
        give(inv, "skyisland:crystal", 1);
        give(inv, "skyisland:gunpowder", 6);
        give(inv, "skyisland:stick", 2);

        int before = inv.totalItemCount();
        List<com.skyisland.player.ItemStack> snapshot = inv.snapshot();

        Crafting.Outcome out = Crafting.craft(inv, RecipeRegistry.rifle());

        assertEquals(Crafting.Result.MISSING_INGREDIENTS, out.result());
        assertEquals(before, inv.totalItemCount(), "失败时物品总数不得变化");
        assertEquals(snapshot, inv.snapshot(), "失败时背包必须逐格不变（原子性）");
        assertEquals(0, inv.countOfItem("skyisland:rifle"), "不得凭空产出步枪");
    }

    @Test
    void anEmptyInventoryHasNoShortfallForNothingAndCannotCraft() {
        Inventory inv = new Inventory();
        Crafting.Outcome out = Crafting.craft(inv, RecipeRegistry.rifle());
        assertEquals(Crafting.Result.MISSING_INGREDIENTS, out.result());
        assertFalse(Crafting.canCraft(inv, RecipeRegistry.rifle()));
    }

    // ============================================================ "放不下"（含腾位边界）

    /**
     * 材料齐但背包<b>完全塞满</b>（且扣完材料仍腾不出空位）→ 拒绝，且一字不改。
     *
     * <p>构造：36 格全满，材料所在的两格各装着 64 个（扣 1 个还剩 63，格子不空），
     * 于是扣完之后依然没有任何空位可以放弹药。
     */
    @Test
    void craftingIntoAFullInventoryIsRefusedWithoutConsuming() {
        Inventory inv = new Inventory();
        for (int i = 0; i < Inventory.SLOT_COUNT; i++) {
            inv.setSlot(i, com.skyisland.player.ItemStack.of(rid("skyisland:stone"), 64));
        }
        // 把最后两格换成 R11 的材料（各 64 个，扣 1 个后仍是 63，格子不空）
        inv.setSlot(Inventory.SLOT_COUNT - 2, com.skyisland.player.ItemStack.of(rid("skyisland:iron_ingot"), 64));
        inv.setSlot(Inventory.SLOT_COUNT - 1, com.skyisland.player.ItemStack.of(rid("skyisland:gunpowder"), 64));

        assertEquals(Inventory.SLOT_COUNT, inv.usedSlotCount(), "前提：背包已满");

        Crafting.Outcome out = Crafting.craft(inv, RecipeRegistry.byId(RecipeRegistry.R11_PISTOL_AMMO));

        assertEquals(Crafting.Result.NO_ROOM, out.result());
        assertEquals(64, inv.countOfItem("skyisland:iron_ingot"), "被拒绝时不得扣材料");
        assertEquals(64, inv.countOfItem("skyisland:gunpowder"), "被拒绝时不得扣材料");
        assertEquals(0, inv.countOfItem("skyisland:pistol_ammo"));
    }

    /**
     * ★ 背包塞满，但扣完材料<b>刚好腾出一格</b> → 应当允许合成。
     *
     * <p>这条是与上一条配对的边界：只看"当前剩余空间"的实现会在这里错误地拒绝
     * （合成就该生效，而且它自己会腾出位置）。它把
     * {@link Crafting#freeSpaceAfterConsuming} 与 {@code Inventory.consumeItem}
     * 的扣减顺序**绑在一起**：谁改了顺序，这条会红。
     */
    @Test
    void craftingIntoAFullInventorySucceedsWhenConsumingFreesASlot() {
        Inventory inv = new Inventory();
        for (int i = 0; i < Inventory.SLOT_COUNT; i++) {
            inv.setSlot(i, com.skyisland.player.ItemStack.of(rid("skyisland:stone"), 64));
        }
        // 材料各放 1 个：扣掉之后这两格会变空，腾出的位置刚好够放产物
        inv.setSlot(Inventory.SLOT_COUNT - 2, com.skyisland.player.ItemStack.of(rid("skyisland:iron_ingot"), 1));
        inv.setSlot(Inventory.SLOT_COUNT - 1, com.skyisland.player.ItemStack.of(rid("skyisland:gunpowder"), 1));

        assertEquals(Inventory.SLOT_COUNT, inv.usedSlotCount(), "前提：背包已满");

        Crafting.Outcome out = Crafting.craft(inv, RecipeRegistry.byId(RecipeRegistry.R11_PISTOL_AMMO));

        assertEquals(Crafting.Result.CRAFTED, out.result(),
                "扣完材料会腾出空位，因此这次合成必须成功 —— 不能只看『当前』剩余空间");
        assertEquals(8, inv.countOfItem("skyisland:pistol_ammo"), "R11 产出手枪弹 ×8");
        assertEquals(0, inv.countOfItem("skyisland:iron_ingot"));
        assertEquals(0, inv.countOfItem("skyisland:gunpowder"));
    }

    // ============================================================ 缺料清单与文案

    @Test
    void missingListNamesExactlyTheShortfallInRecipeOrder() {
        Inventory inv = new Inventory();
        give(inv, "skyisland:iron_ingot", 5);   // 需 8，缺 3
        give(inv, "skyisland:copper_ingot", 3); // 够
        give(inv, "skyisland:crystal", 1);      // 够
        // 火药 0，需 6；木棍 0，需 2

        List<Crafting.Shortfall> missing = Crafting.missingFor(inv, RecipeRegistry.rifle());

        assertEquals(3, missing.size(), "只有 3 项不足（够的那些不得出现在清单里）");
        assertEquals("skyisland:iron_ingot", missing.get(0).itemId());
        assertEquals(3, missing.get(0).missing());
        assertEquals("skyisland:gunpowder", missing.get(1).itemId());
        assertEquals(6, missing.get(1).missing());
        assertEquals("skyisland:stick", missing.get(2).itemId());
        assertEquals(2, missing.get(2).missing());

        // 顺序必须与配方里材料的登记顺序一致（读起来与 PRD 5.6.2 那一行同序）
        assertEquals(RecipeRegistry.rifle().ingredients().get(0).itemId(), missing.get(0).itemId());
    }

    /** 缺料提示必须逐字等于 PRD 6.7 的空状态示例：{@code 缺少 铁锭 ×4}。 */
    @Test
    void describeMissingMatchesThePrd67Format() {
        Inventory inv = new Inventory();
        give(inv, "skyisland:iron_ingot", 4);   // 需 8，缺 4

        Crafting.Outcome out = Crafting.craft(inv, RecipeRegistry.rifle());
        assertFalse(out.crafted());

        String text = Crafting.describeMissing(out);
        assertTrue(text.startsWith("缺少 铁锭 ×4"),
                "PRD 6.7 的口径是『缺少 铁锭 ×4』，实际得到：" + text);
        assertTrue(text.contains("、"), "多项缺料用「、」连接，实际得到：" + text);
        assertTrue(text.contains("火药 ×6"), "实际得到：" + text);
    }

    @Test
    void describeMissingOfNothingIsAnEmptyString() {
        assertEquals("", Crafting.describeMissing(List.of()));
        assertEquals("", Crafting.describeMissing((List<Crafting.Shortfall>) null));
    }

    // ============================================================ 端到端：从原木到步枪

    /**
     * ★ 整条步枪链从原始材料走通：原木 → 木板 → 木棍；矿石 + 煤 → 锭；煤 + 沙 → 火药；
     * 最后 R16 产出步枪，R12 产出步枪弹。
     *
     * <p>这条断言的价值是"<b>按 PRD 的配方表真的能造出一把步枪</b>"这件事不再靠人工推演。
     * 任何一条配方的数量写错（例如 R16 的铁锭从 8 写成 4），这里都会因为材料不够而变红。
     *
     * <p>注意：晶体在本轮没有获取途径（配方链只到矿石方块，且不做矿石生成）。
     * 因此这里直接发放 1 个晶体，并在注释里标明它对应"晶体矿石掉落物"这一来源。
     * 缺的那一环是**世界生成**，不是配方。
     */
    @Test
    void theWholeRifleChainIsCraftableFromRawMaterials() {
        Inventory inv = new Inventory();
        // 原始材料：2 原木 / 9 铁矿石 / 3 铜矿石 / 20 煤 / 4 沙；晶体按"晶体矿石掉落"直接发放
        give(inv, "skyisland:log", 2);
        give(inv, "skyisland:iron_ore", 9);
        give(inv, "skyisland:copper_ore", 3);
        give(inv, "skyisland:coal", 20);
        give(inv, "skyisland:sand", 4);
        give(inv, "skyisland:crystal", 1);

        // R01 ×2：原木 ×1 → 木板 ×4（共 8 木板）
        craftOk(inv, RecipeRegistry.R01_OAK_PLANKS, 2);
        assertEquals(8, inv.countOfItem("skyisland:oak_planks"));
        assertEquals(0, inv.countOfItem("skyisland:log"));

        // R02 ×2：木板 ×2 → 木棍 ×4（共 8 木棍）
        craftOk(inv, RecipeRegistry.R02_STICK, 2);
        assertEquals(8, inv.countOfItem("skyisland:stick"));

        // R03 ×9：铁矿石 ×1 + 煤 ×1 → 铁锭 ×1
        craftOk(inv, RecipeRegistry.R03_IRON_INGOT, 9);
        assertEquals(9, inv.countOfItem("skyisland:iron_ingot"));

        // R04 ×3：铜矿石 ×1 + 煤 ×1 → 铜锭 ×1
        craftOk(inv, RecipeRegistry.R04_COPPER_INGOT, 3);
        assertEquals(3, inv.countOfItem("skyisland:copper_ingot"));

        // R06 ×4：煤 ×2 + 沙 ×1 → 火药 ×2（共 8 火药）
        craftOk(inv, RecipeRegistry.R06_GUNPOWDER, 4);
        assertEquals(8, inv.countOfItem("skyisland:gunpowder"));

        // R16 ×1：铁锭 ×8 + 铜锭 ×3 + 晶体 ×1 + 火药 ×6 + 木棍 ×2 → 步枪 ×1
        craftOk(inv, RecipeRegistry.R16_RIFLE, 1);

        assertEquals(1, inv.countOfItem("skyisland:rifle"), "必须真的造出一把步枪");
        assertEquals(1, inv.countOfItem("skyisland:iron_ingot"), "9 - 8 = 1 个铁锭剩余");
        assertEquals(0, inv.countOfItem("skyisland:copper_ingot"));
        assertEquals(0, inv.countOfItem("skyisland:crystal"));
        assertEquals(2, inv.countOfItem("skyisland:gunpowder"), "8 - 6 = 2 个火药剩余");
        assertEquals(6, inv.countOfItem("skyisland:stick"), "8 - 2 = 6 个木棍剩余");
        assertEquals(4, inv.countOfItem("skyisland:oak_planks"), "8 - 4 = 4 个木板剩余");

        // R12 ×1：铁锭 ×1 + 火药 ×2 → 步枪弹 ×6（刚好把剩下的材料用光）
        craftOk(inv, RecipeRegistry.R12_RIFLE_AMMO, 1);
        assertEquals(6, inv.countOfItem("skyisland:rifle_ammo"), "步枪弹 ×6（PRD 5.4.2）");
        assertEquals(0, inv.countOfItem("skyisland:iron_ingot"));
        assertEquals(0, inv.countOfItem("skyisland:gunpowder"));

        // 造出来的枪与弹必须是注册表里那两件东西
        assertEquals(rid("skyisland:rifle"), inv.snapshot().stream()
                        .filter(s -> !s.isEmpty() && s.item().id().equals("skyisland:rifle"))
                        .findFirst().orElseThrow().itemRuntimeId(),
                "造出的步枪必须是注册表里的 skyisland:rifle");
    }

    private static void craftOk(Inventory inv, String recipeId, int times) {
        Recipe recipe = RecipeRegistry.byId(recipeId);
        assertNotNull(recipe, "配方必须已登记: " + recipeId);
        for (int i = 0; i < times; i++) {
            Crafting.Outcome out = Crafting.craft(inv, recipe);
            assertEquals(Crafting.Result.CRAFTED, out.result(),
                    "第 " + (i + 1) + " 次合成应当成功：" + recipe.id()
                            + "（缺料：" + Crafting.describeMissing(out) + "）");
        }
    }
}

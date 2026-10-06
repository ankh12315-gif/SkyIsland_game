package com.skyisland.craft;

import com.skyisland.player.Inventory;
import com.skyisland.player.ItemStack;
import com.skyisland.testutil.SourceScan;
import com.skyisland.ui.Localization;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 背包内合成界面模型测试（{@link CraftingPanel}）。
 *
 * <h2>★ 这一组断言守的是"裁定第 5 条"关闭的那条死接线</h2>
 * {@code RecipeRegistry} 与 {@link Crafting} 曾经有完整的数据、完整的纯逻辑、
 * 完整的单测，而<b>整个产品里没有一个玩家可达的调用点</b> ——
 * 那种状态下"合成"在代码里存在、在游戏里不存在。
 * {@link CraftingPanel} 是第一个真正把它们接到界面上的类，
 * 因此这里的每一条断言都必须对着"<b>玩家看得到、点得到</b>的东西"写：
 * 行号、状态、文案、点击之后的背包变化。
 *
 * <h2>为什么必须有一条"界面不自己扣料"的源码扫描</h2>
 * 界面层自己实现一份扣料算法，是这类功能的默认写法，而它的下场必然是
 * 界面与逻辑各有一套口径：玩家在某个边界上遇到"界面说能合、仓库说不能"，
 * 或者更糟 ——<b>扣了料却没出货</b>。行为断言测不出这件事
 * （两套算法在绝大多数输入下结果相同），只能靠"界面里不许出现扣料调用"来钉。
 *
 * <p>这条扫描用 {@link SourceScan#withoutComments} 剥注释后再匹配：
 * 直接 {@code contains("consumeItem")} 会被"这里不许出现 consumeItem"这句注释本身满足。
 */
class CraftingPanelTest {

    private static int rid(String stableId) {
        return com.skyisland.item.ItemRegistry.runtimeIdOf(stableId);
    }

    private static void give(Inventory inv, String stableId, int count) {
        int leftover = inv.add(rid(stableId), count);
        assertEquals(0, leftover, "测试准备阶段应当能把 " + stableId + " ×" + count + " 放进背包");
    }

    /** 行号：按配方 ID 找，而不是硬编码下标（列表顺序是界面的事）。 */
    private static int rowOf(CraftingPanel panel, String recipeId) {
        for (int i = 0; i < panel.size(); i++) {
            if (recipeId.equals(panel.row(i).recipe().id())) {
                return i;
            }
        }
        throw new IllegalStateException("合成栏里没有配方 " + recipeId);
    }

    /** 把 36 格全部塞满石头，再把 {@code overrides} 里指定的格子换掉。 */
    private static Inventory fullInventory() {
        Inventory inv = new Inventory();
        for (int i = 0; i < Inventory.SLOT_COUNT; i++) {
            inv.setSlot(i, ItemStack.of(rid("skyisland:stone"), 64));
        }
        return inv;
    }

    // ============================================================ 可用性：配方真的进到了界面

    /**
     * ★ 合成栏列出的行数 = 注册表的配方数。
     *
     * <p>"注册了配方"与"界面上看得到配方"是两件事。这条断言是它们之间唯一的钉子：
     * 哪天有人往 {@link RecipeRegistry} 里加了第 11 条却忘了界面，它会立刻红。
     */
    @Test
    void thePanelListsEveryRegisteredRecipe() {
        CraftingPanel panel = new CraftingPanel();
        assertEquals(RecipeRegistry.all().size(), panel.size(),
                "合成栏必须列出注册表里的全部配方");
        assertTrue(panel.size() > 0, "配方表不能是空的 —— 那说明 bootstrap 没跑");
        for (int i = 0; i < panel.size(); i++) {
            assertEquals(RecipeRegistry.all().get(i), panel.row(i).recipe(),
                    "第 " + i + " 行应当与注册表同序");
            assertEquals(i, panel.row(i).index());
        }
    }

    /**
     * 刚建好、还没 refresh 的面板必须全部显示"缺料"。
     *
     * <p>反过来（默认可合成）会先闪一下"[合成]"再变成"缺料"，而那一闪
     * 足以让玩家相信"我点一下就能合出来"，于是他会一直点 —— 这正是裁定第 7 条
     * 禁止"点了没反应"要防的那类体验。安全初值只有一种。
     */
    @Test
    void aFreshPanelShowsEverythingAsMissing() {
        CraftingPanel panel = new CraftingPanel();
        assertTrue(panel.size() > 0);
        for (CraftingPanel.Row row : panel.rows()) {
            assertSame(CraftingPanel.Status.MISSING_MATERIALS, row.status(),
                    "未 refresh 的面板不得显示可合成: " + row.recipe().id());
            assertFalse(row.craftable());
        }
    }

    @Test
    void anOutOfRangeRowIsNullRatherThanAnException() {
        CraftingPanel panel = new CraftingPanel();
        assertNull(panel.row(-1));
        assertNull(panel.row(panel.size()));
        assertEquals("", panel.statusText(null));
        assertEquals("", panel.ingredientText(null, new Inventory()));
    }

    // ============================================================ 状态：可合成 / 缺料必须能区分

    @Test
    void aRecipeWhoseMaterialsArePresentIsCraftable() {
        Inventory inv = new Inventory();
        give(inv, "skyisland:log", 4);

        CraftingPanel panel = new CraftingPanel();
        panel.refresh(inv);

        int row = rowOf(panel, RecipeRegistry.R01_OAK_PLANKS);
        assertSame(CraftingPanel.Status.CRAFTABLE, panel.row(row).status());
        assertTrue(panel.row(row).craftable());
        assertTrue(panel.row(row).shortfalls().isEmpty());
        // 可合成时按钮文案就是 [合成]（PRD 6.7 的按钮形状；不写死字符串，读 Localization）
        assertEquals(Localization.text(Localization.CRAFT_ACTION), panel.statusText(panel.row(row)));
    }

    @Test
    void aRecipeWithMissingMaterialsSaysExactlyWhatIsMissing() {
        Inventory inv = new Inventory();
        give(inv, "skyisland:iron_ingot", 5);   // 需 8 → 缺 3
        // 其余全无

        CraftingPanel panel = new CraftingPanel();
        panel.refresh(inv);

        int row = rowOf(panel, RecipeRegistry.R16_RIFLE);
        CraftingPanel.Row r = panel.row(row);
        assertSame(CraftingPanel.Status.MISSING_MATERIALS, r.status());
        assertFalse(r.craftable());
        assertFalse(r.shortfalls().isEmpty(), "缺料时必须给出清单（界面要据此排版文案）");

        String text = panel.statusText(r);
        assertTrue(text.contains("缺少"), "缺料文案必须以『缺少』开头，实际=" + text);
        assertTrue(text.contains("铁锭 ×3"), "必须说清缺哪一项、缺几个，实际=" + text);
        assertFalse(text.equals(Localization.text(Localization.CRAFT_ACTION)),
                "缺料行绝不能显示成可合成按钮");
    }

    /**
     * 材料需求写成"拥有 / 需求"（{@code 铁锭 5/8}），而不是只写需求。
     *
     * <p>只写需求的话"还差几个"要玩家心算；两个数字并排是他一眼能读完的。
     */
    @Test
    void ingredientTextShowsBothWhatYouHaveAndWhatYouNeed() {
        Inventory inv = new Inventory();
        give(inv, "skyisland:iron_ingot", 5);
        give(inv, "skyisland:copper_ingot", 3);
        give(inv, "skyisland:crystal", 1);

        CraftingPanel panel = new CraftingPanel();
        panel.refresh(inv);
        String text = panel.ingredientText(panel.row(rowOf(panel, RecipeRegistry.R16_RIFLE)), inv);

        assertTrue(text.contains("铁锭 5/8"), "实际=" + text);
        assertTrue(text.contains("铜锭 3/3"), "够的那些也要显示出来，实际=" + text);
        assertTrue(text.contains("火药 0/6"), "一项都没有的显示 0/N，实际=" + text);
    }

    /**
     * ★ 裁定第 10 条：三条"人类可完成"的配方在过渡材料包口径下都必须是可合成的。
     *
     * <p>这一条把"玩家真的能用手里的东西造出点什么"变成断言，
     * 而不是留给试玩去发现。矿石世界生成还没做，因此材料来自过渡材料包 ——
     * 那正是它存在的理由（见 {@code SkyIslandGame} 的 DEV / TRANSITION 材料包注释）。
     */
    @Test
    void theThreeHumanCompletableRecipesAreCraftableFromTheTransitionKit() {
        Inventory inv = new Inventory();
        give(inv, "skyisland:log", 4);
        give(inv, "skyisland:iron_ore", 9);
        give(inv, "skyisland:copper_ore", 3);
        give(inv, "skyisland:coal", 20);
        give(inv, "skyisland:sand", 4);
        give(inv, "skyisland:crystal", 1);

        CraftingPanel panel = new CraftingPanel();
        panel.refresh(inv);

        assertSame(CraftingPanel.Status.CRAFTABLE,
                panel.row(rowOf(panel, RecipeRegistry.R01_OAK_PLANKS)).status(), "R01 木板");
        assertSame(CraftingPanel.Status.CRAFTABLE,
                panel.row(rowOf(panel, RecipeRegistry.R03_IRON_INGOT)).status(), "R03 铁锭");
        assertSame(CraftingPanel.Status.CRAFTABLE,
                panel.row(rowOf(panel, RecipeRegistry.R06_GUNPOWDER)).status(), "R06 火药");
        // 步枪自身在这一步仍缺料（要先炼锭、造火药）：这是"链"，不是"一键"
        assertSame(CraftingPanel.Status.MISSING_MATERIALS,
                panel.row(rowOf(panel, RecipeRegistry.R16_RIFLE)).status(), "R16 步枪");
    }

    // ============================================================ 点击：扣料 + 出货 + 刷新

    @Test
    void clickingACraftableRowConsumesAndProducesAndRefreshes() {
        Inventory inv = new Inventory();
        give(inv, "skyisland:log", 4);

        CraftingPanel panel = new CraftingPanel();
        panel.refresh(inv);
        int row = rowOf(panel, RecipeRegistry.R01_OAK_PLANKS);

        Crafting.Outcome outcome = panel.craft(row, inv);

        assertNotNull(outcome);
        assertEquals(Crafting.Result.CRAFTED, outcome.result());
        assertEquals(3, inv.countOfItem("skyisland:log"), "原木 −1");
        assertEquals(4, inv.countOfItem("skyisland:oak_planks"), "木板 +4");
        // 合成之后面板必须自己刷新：原木还剩 3，因此这一行仍然可合成
        assertSame(CraftingPanel.Status.CRAFTABLE, panel.row(row).status());

        // 把原木用完：行状态必须自己翻成缺料（缓存没刷就会一直显示 [合成]）
        for (int i = 0; i < 3; i++) {
            panel.craft(row, inv);
        }
        assertEquals(0, inv.countOfItem("skyisland:log"));
        assertEquals(16, inv.countOfItem("skyisland:oak_planks"), "4 个原木合计产出 16 个木板");
        assertSame(CraftingPanel.Status.MISSING_MATERIALS, panel.row(row).status(),
                "原木用尽后这一行必须变成缺料 —— 这就是『合成后刷新』的证据");
        assertTrue(panel.statusText(panel.row(row)).contains("缺少"));
    }

    @Test
    void clickingAnOutOfRangeRowDoesNothing() {
        Inventory inv = new Inventory();
        give(inv, "skyisland:log", 4);
        CraftingPanel panel = new CraftingPanel();
        panel.refresh(inv);

        assertNull(panel.craft(-1, inv));
        assertNull(panel.craft(panel.size(), inv));
        assertNull(panel.craft(0, null));
        assertEquals(4, inv.countOfItem("skyisland:log"), "越界点击不得扣任何材料");
    }

    /**
     * ★ 缺料的行点下去：背包<b>逐格不变</b>，而且状态文案仍然说明缺什么。
     *
     * <p>后半句是裁定第 7 条的直接落地："点了没反应"最常见的形态不是代码没写，
     * 而是只在成功分支给反馈。缺料时若界面纹丝不动，玩家会一直点下去 ——
     * 从他的角度看，这与"按钮坏了"无法区分。
     */
    @Test
    void clickingAMissingRowChangesNothingAndStillExplainsWhy() {
        Inventory inv = new Inventory();
        give(inv, "skyisland:iron_ingot", 5);

        CraftingPanel panel = new CraftingPanel();
        panel.refresh(inv);
        int row = rowOf(panel, RecipeRegistry.R16_RIFLE);

        int before = inv.totalItemCount();
        List<ItemStack> snapshot = inv.snapshot();

        Crafting.Outcome outcome = panel.craft(row, inv);

        assertEquals(Crafting.Result.MISSING_INGREDIENTS, outcome.result());
        assertFalse(outcome.crafted());
        assertEquals(before, inv.totalItemCount(), "缺料时物品总数不得变化");
        assertEquals(snapshot, inv.snapshot(), "缺料时背包必须逐格不变（原子性）");
        assertEquals(0, inv.countOfItem("skyisland:rifle"), "不得凭空产出");
        // 刷新之后仍然给出缺料原因（不是空白）
        assertTrue(panel.statusText(panel.row(row)).contains("缺少"));
    }

    /**
     * ★ 裁定第 9 条：产物放不下 → <b>整次合成拒绝</b>，绝不出现"扣了料但产物没了"。
     *
     * <p>构造与 {@code CraftingTest} 那条同口径（36 格全满、材料各 64 个，
     * 扣 1 个后格子不空），因此扣完材料<b>仍然没有空位</b>。
     * 这里的额外价值是：界面这一层也必须把"拒绝了"这件事体现出来 ——
     * 它调用的是同一个 {@link Crafting#craft}，于是拒绝是原子的，
     * 而界面还必须刷新（不刷的话会一直显示可合成，玩家再点一次还是失败且无反馈）。
     */
    @Test
    void craftingWithNoRoomForTheOutputIsRejectedAtomically() {
        Inventory inv = fullInventory();
        inv.setSlot(Inventory.SLOT_COUNT - 2, ItemStack.of(rid("skyisland:iron_ore"), 64));
        inv.setSlot(Inventory.SLOT_COUNT - 1, ItemStack.of(rid("skyisland:coal"), 64));
        assertEquals(Inventory.SLOT_COUNT, inv.usedSlotCount(), "前提：背包已满");

        CraftingPanel panel = new CraftingPanel();
        panel.refresh(inv);
        int row = rowOf(panel, RecipeRegistry.R03_IRON_INGOT);
        assertSame(CraftingPanel.Status.CRAFTABLE, panel.row(row).status(), "前提：材料齐备");

        int before = inv.totalItemCount();
        List<ItemStack> snapshot = inv.snapshot();

        Crafting.Outcome outcome = panel.craft(row, inv);

        assertEquals(Crafting.Result.NO_ROOM, outcome.result());
        assertEquals(before, inv.totalItemCount(), "被拒绝时物品总数不得变化");
        assertEquals(snapshot, inv.snapshot(), "被拒绝时背包必须逐格不变 —— 绝不能扣了料却没出货");
        assertEquals(0, inv.countOfItem("skyisland:iron_ingot"));
    }

    /**
     * 刷新的语义：<b>状态必须跟着背包变</b>。
     *
     * <p>{@link CraftingPanel} 为性能缓存状态（每帧跨槽计数在 3000 FPS 下是每秒几百万次
     * 无意义扫描），缓存带来的风险就是"忘记 refresh 会显示旧状态"。
     * 这条断言是那个取舍的代价清单上唯一的一条保险。
     */
    @Test
    void refreshReEvaluatesEveryRowAgainstTheCurrentInventory() {
        Inventory inv = new Inventory();
        CraftingPanel panel = new CraftingPanel();
        panel.refresh(inv);
        int row = rowOf(panel, RecipeRegistry.R03_IRON_INGOT);
        assertSame(CraftingPanel.Status.MISSING_MATERIALS, panel.row(row).status());

        give(inv, "skyisland:iron_ore", 1);
        assertSame(CraftingPanel.Status.MISSING_MATERIALS, panel.row(row).status(),
                "未 refresh 时不得偷偷变（缓存语义）");

        give(inv, "skyisland:coal", 1);
        panel.refresh(inv);
        assertSame(CraftingPanel.Status.CRAFTABLE, panel.row(row).status(),
                "refresh 之后必须反映当前背包");
    }

    // ============================================================ 扣料唯一权威（源码扫描）

    /**
     * ★ 界面层不许自己实现扣料算法：{@link Crafting} 是唯一权威。
     *
     * <p>三层扫描，逐层收紧：
     * <ol>
     *   <li>整个 {@code CraftingPanel} 里都不出现任何扣料 API；</li>
     *   <li>{@code craft} 方法体里必须有 {@code Crafting.craft(...)} 这一行；</li>
     *   <li>{@code refresh} 方法体里必须有 {@code Crafting.missingFor(...)} ——
     *       连"还差几个"都由 {@link Crafting} 算。</li>
     * </ol>
     */
    @Test
    void thePanelNeverImplementsItsOwnMaterialArithmetic() {
        String source = SourceScan.readMain("com/skyisland/craft/CraftingPanel.java");
        String code = SourceScan.withoutComments(source);

        assertFalse(code.contains("consumeItem"), "界面层不得自己扣材料");
        assertFalse(code.contains("consumeSelected"), "界面层不得自己扣材料");
        assertFalse(code.contains("removeItem"), "界面层不得自己删物品");
        assertFalse(code.contains("setSlot"), "界面层不得直接写背包格子");

        assertTrue(SourceScan.methodBody(source, "public Crafting.Outcome craft(")
                        .contains("Crafting.craft("),
                "craft() 必须把合成交给 Crafting.craft");
        assertTrue(SourceScan.methodBody(source, "public void refresh(")
                        .contains("Crafting.missingFor("),
                "refresh() 必须把缺料判定交给 Crafting.missingFor");
    }
}

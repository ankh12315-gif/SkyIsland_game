package com.skyisland.craft;

import com.skyisland.item.ItemRegistry;
import com.skyisland.player.Inventory;
import com.skyisland.player.ItemStack;
import com.skyisland.ui.Localization;
import com.skyisland.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 合成的<b>纯逻辑语义层</b>：给定背包与配方，回答"能不能合 / 缺什么 / 合了会怎样"。
 *
 * <p><b>无 GL、无渲染、无输入，可直接单测。</b>与 {@code InventoryInteraction} 同一分工：
 * 这一层给出权威答案，界面层只负责把答案画出来。本轮（范围裁定：数据层 + 合成逻辑）
 * 不实现合成界面，因此本类目前只有单测与自测调用者 —— 这是<b>刻意的</b>：
 * 这一层是可独立验证的，界面接上来时不需要改它一行。
 *
 * <h2>★ 材料校验必须按"总拥有量"，不是按"单个槽位"</h2>
 * 这是本项目在 TECH_DESIGN 里点名过的一条实现陷阱：
 * 若判断"有没有铁锭 ×4"时去看"是否存在某一格装有 4 个铁锭"，
 * 那么背包里 4 个各装 1 个铁锭的槽位会被判成"不够"——
 * 玩家会遇到"明明有材料却合不了"，而且这个 bug 只在材料被拆散时才出现。
 * 因此本类的全部计数都走 {@link Inventory#countOfItem(String)}（跨槽求和）与
 * {@link Inventory#consumeItem(String, int)}（跨槽扣减），
 * <b>绝不出现"按槽判断"的写法</b>。这条由 {@code CraftingTest} 的一个
 * "材料拆散在多个槽里仍应能合成"的用例钉住。
 *
 * <h2>原子性</h2>
 * {@link #craft} 只有三种结果，且**失败时背包一字不改**：
 * <ol>
 *   <li>材料不够 → {@link Result#MISSING_INGREDIENTS}，附完整缺料清单；</li>
 *   <li>材料够但产出放不下 → {@link Result#NO_ROOM}；</li>
 *   <li>两者都过 → {@link Result#CRAFTED}，扣材料、加产物。</li>
 * </ol>
 *
 * <p><b>"产出放不下"的判定为什么必须模拟"先扣材料之后"的状态：</b>
 * 背包塞满时，一次合成会先把某些槽清空（材料被扣光），腾出的空位可能刚好够放产物。
 * 若只看"当前"剩余空间，就会出现"背包满 → 合成被拒 → 但其实合了就能放下"
 * 这种自相矛盾的结果。见 {@link #freeSpaceAfterConsuming}。
 */
public final class Crafting {

    /** 一次合成尝试的结果分类。 */
    public enum Result {
        /** 材料齐、位置够，已完成扣料与产出。 */
        CRAFTED,
        /** 材料不足（含"完全没有"）。{@link Outcome#shortfalls()} 给出每一项的缺口。 */
        MISSING_INGREDIENTS,
        /** 材料齐了，但背包放不下产物 —— 背包一字未改。 */
        NO_ROOM
    }

    /**
     * 一项材料的缺口：还差多少个。
     *
     * <p>只有 {@code missing > 0} 的项会出现在清单里（够的那些不出现），
     * 于是"清单为空 ⟺ 可以合成"这条等价关系成立，{@link #canCraft} 直接依赖它。
     */
    public record Shortfall(String itemId, int missing) {
        public Shortfall {
            if (missing <= 0) {
                throw new IllegalArgumentException("缺口必须为正: " + itemId + " / " + missing);
            }
        }
    }

    /** 合成结果：分类 + 配方 + 缺料清单（仅 {@link Result#MISSING_INGREDIENTS} 时非空）。 */
    public record Outcome(Result result, Recipe recipe, List<Shortfall> shortfalls) {
        public Outcome {
            shortfalls = List.copyOf(shortfalls);
        }

        public boolean crafted() {
            return result == Result.CRAFTED;
        }
    }

    private Crafting() {
    }

    // ============================================================ 查询

    /**
     * 还缺哪些材料，按配方里材料的登记顺序返回；全都够时返回<b>空列表</b>。
     *
     * <p>顺序与配方一致是有意的：缺料提示会照这个顺序显示，
     * 于是它读起来与 PRD 5.6.2 那一行的"材料"列同序，便于核对。
     */
    public static List<Shortfall> missingFor(Inventory inv, Recipe recipe) {
        List<Shortfall> out = new ArrayList<>();
        for (Recipe.Ingredient ing : recipe.ingredients()) {
            int have = inv.countOfItem(ing.itemId());
            int gap = ing.count() - have;
            if (gap > 0) {
                out.add(new Shortfall(ing.itemId(), gap));
            }
        }
        return out;
    }

    /** 材料是否齐备（不看背包剩余空间 —— "放不下"是另一种失败）。 */
    public static boolean canCraft(Inventory inv, Recipe recipe) {
        return missingFor(inv, recipe).isEmpty();
    }

    // ============================================================ 合成

    /**
     * 尝试合成一次。失败时<b>背包一字不改</b>。
     *
     * <p>每次调用只产出<b>一份</b>配方的产出量（{@code recipe.outputCount()}）。
     * 连续合多份由调用方循环 —— 本项目不做"一次合到满"的语义，
     * 因为那会让"合了几份"变成一个需要额外解释的数字。
     */
    public static Outcome craft(Inventory inv, Recipe recipe) {
        List<Shortfall> missing = missingFor(inv, recipe);
        if (!missing.isEmpty()) {
            return new Outcome(Result.MISSING_INGREDIENTS, recipe, missing);
        }

        int outputRuntimeId = ItemRegistry.runtimeIdOf(recipe.outputItemId());
        if (freeSpaceAfterConsuming(inv, recipe, outputRuntimeId) < recipe.outputCount()) {
            return new Outcome(Result.NO_ROOM, recipe, List.of());
        }

        for (Recipe.Ingredient ing : recipe.ingredients()) {
            if (!inv.consumeItem(ing.itemId(), ing.count())) {
                // 不可达：上面刚用 countOfItem 逐个核对过总量，consumeItem 的判据与它同源。
                // 真出现说明 Inventory 的两条路径口径不一致 —— 那是必须被看见的缺陷，
                // 因此这里告警而不是静默继续。此时背包可能已被部分扣减，
                // 所以返回"材料不足"而不是假装成功。
                Log.noteWarning("合成",
                        "扣除材料失败（总量校验与扣除口径不一致）：" + ing.itemId()
                                + " ×" + ing.count() + "，配方 " + recipe.id());
                return new Outcome(Result.MISSING_INGREDIENTS, recipe, missingFor(inv, recipe));
            }
        }

        int leftover = inv.add(outputRuntimeId, recipe.outputCount());
        if (leftover > 0) {
            // 同样不可达：空间判定就是为"放得下"做的。留一条告警便于将来改接口时立刻发现。
            Log.noteWarning("合成",
                    "产物未能全部放入背包：剩余 " + leftover + " 个 " + recipe.outputItemId()
                            + "，配方 " + recipe.id());
        }
        return new Outcome(Result.CRAFTED, recipe, List.of());
    }

    /**
     * 在"先把本配方材料扣掉"的假想状态下，背包能放下多少个 {@code outputRuntimeId}。
     *
     * <p><b>为什么要模拟而不是直接数当前空位：</b>见类文档。这里按与
     * {@link Inventory#consumeItem(String, int)} <b>完全相同的顺序</b>
     * （从最后一个槽往前走、每格取 min(剩余需求, 该格数量)）演算一遍扣减结果，
     * 再统计能容纳产物的空间（同种未满堆的剩余空间 + 空格 × 堆叠上限）。
     *
     * <p>顺序必须一致这一点是**有意耦合**的：若 {@code consumeItem} 的扣减顺序将来改变，
     * 本函数可能算错一个"背包刚好塞满"的边界 ── 因此
     * {@code CraftingTest} 里有一个"背包满但扣完材料刚好腾出一格"的用例把它钉住。
     */
    static int freeSpaceAfterConsuming(Inventory inv, Recipe recipe, int outputRuntimeId) {
        int slots = Inventory.SLOT_COUNT;
        int[] ids = new int[slots];
        int[] counts = new int[slots];
        for (int i = 0; i < slots; i++) {
            ItemStack s = inv.slot(i);
            ids[i] = s.isEmpty() ? ItemRegistry.EMPTY_RUNTIME_ID : s.itemRuntimeId();
            counts[i] = s.count();
        }

        for (Recipe.Ingredient ing : recipe.ingredients()) {
            int id = ItemRegistry.runtimeIdOf(ing.itemId());
            int left = ing.count();
            for (int i = slots - 1; i >= 0 && left > 0; i--) {
                if (ids[i] != id || counts[i] <= 0) {
                    continue;
                }
                int take = Math.min(left, counts[i]);
                counts[i] -= take;
                left -= take;
            }
        }

        int perStack = ItemRegistry.maxStackOf(outputRuntimeId);
        int space = 0;
        for (int i = 0; i < slots; i++) {
            if (counts[i] <= 0) {
                space += perStack;                 // 被扣空 / 本来就是空的
            } else if (ids[i] == outputRuntimeId) {
                space += perStack - counts[i];     // 同种未满堆的剩余空间
            }
        }
        return space;
    }

    // ============================================================ 文案

    /**
     * 缺料清单 → 玩家可见文案，例如 {@code 缺少 铁锭 ×4}（PRD 6.7 的空状态示例）。
     *
     * <p>多项时用「、」连接，例如 {@code 缺少 铁锭 ×4、火药 ×2}。
     * 物品名走 {@link Localization#displayName(String)}（PRD 6.7：玩家可见名称必须有统一来源，
     * 禁止在 Java 代码里散落中文字面量）。
     */
    public static String describeMissing(List<Shortfall> shortfalls) {
        if (shortfalls == null || shortfalls.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Shortfall s : shortfalls) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(Localization.text(Localization.MSG_CRAFT_MISSING,
                    Localization.displayName(s.itemId()), s.missing()));
        }
        return sb.toString();
    }

    /** {@link #describeMissing} 的便利入口：直接给一次合成失败的缺料清单。 */
    public static String describeMissing(Outcome outcome) {
        return describeMissing(outcome.shortfalls());
    }
}

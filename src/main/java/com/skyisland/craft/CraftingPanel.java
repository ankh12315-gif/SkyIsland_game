package com.skyisland.craft;

import com.skyisland.item.ItemRegistry;
import com.skyisland.player.Inventory;
import com.skyisland.ui.Localization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 背包内<b>合成区域</b>的界面模型（2026-10-03：{@code RecipeRegistry} / {@code Crafting} 的第一个产品消费者）。
 *
 * <h2>★ 它关闭的是本项目登记在案的一条死接线</h2>
 * 上一轮把 PRD 5.6.2 的配方表与 {@link Crafting} 的纯逻辑都落地了，
 * 但<b>整个产品里没有任何一处调用它们</b> —— 没有界面、没有命令、没有按键。
 * 那是一种最难受的状态：数据在、逻辑对、单测全绿，而玩家<b>永远碰不到</b>。
 * 本类就是把它们接到"玩家点得到的地方"的那一段。
 *
 * <h2>本类<b>不</b>做任何扣料 / 加产物的计算</h2>
 * 材料够不够、扣多少、产物放不放得下，全部由 {@link Crafting} 裁决：
 * {@link #refresh} 走 {@link Crafting#missingFor}，{@link #craft} 走 {@link Crafting#craft}。
 *
 * <p>界面层自己实现一份"扣料算法"几乎是这类功能的默认写法，而它的下场必然是：
 * 界面与逻辑各有一套口径，玩家在某个边界上（材料拆散在多个槽 / 背包刚好塞满）
 * 遇到"界面说能合、仓库说不能"或"扣了料却没出货"。
 * 因此这里连"还差几个"都不自己数 —— 只把 {@link Crafting} 的缺料清单排版成一行字。
 *
 * <h2>为什么状态要缓存（{@link #refresh}）而不是每帧现算</h2>
 * 每帧对 10 条配方各跑一遍跨槽计数，等于每帧 36×N 次遍历：在 3000 FPS 下
 * 那是每秒几百万次没有意义的扫描。而"材料够不够"只会在两种时刻变化：
 * <b>背包内容变了</b>与<b>刚刚合成完</b>。因此本类要求调用方在这两种时刻
 * 显式 {@link #refresh}，其余时间只读缓存。
 *
 * <p>这个取舍的代价是"忘记 refresh 会显示旧状态"，因此
 * {@code CraftingPanelTest} 有一条专门钉住"refresh 之后状态必须跟着变"的断言。
 */
public final class CraftingPanel {

    /** 一行配方在界面上的状态（裁定第 7 条：至少要能区分这两种）。 */
    public enum Status {
        /** 材料齐备，点了就会真的合成。 */
        CRAFTABLE,
        /** 缺材料；{@link Row#shortfalls()} 给出每一项缺口。 */
        MISSING_MATERIALS
    }

    /**
     * 界面上的一行：配方 + 状态 + 缺料清单。
     *
     * @param index      在列表中的行号（也是点击命中判定用的下标）
     * @param recipe     对应配方
     * @param status     当前状态
     * @param shortfalls 缺料清单；{@link Status#CRAFTABLE} 时为空
     */
    public record Row(int index, Recipe recipe, Status status, List<Crafting.Shortfall> shortfalls) {
        public Row {
            shortfalls = List.copyOf(shortfalls);
        }

        public boolean craftable() {
            return status == Status.CRAFTABLE;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    public CraftingPanel() {
        refresh(null);
    }

    // ============================================================ 查询

    /** 当前可见的配方行数。 */
    public int size() {
        return rows.size();
    }

    /** 第 {@code index} 行；越界返回 {@code null}。 */
    public Row row(int index) {
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    /** 全部行（不可修改，按 {@link RecipeRegistry} 的注册顺序）。 */
    public List<Row> rows() {
        return Collections.unmodifiableList(rows);
    }

    /**
     * 按当前背包重算每一行的状态。
     *
     * <p>{@code inv} 为 {@code null} 时全部行按"缺料"处理（界面刚建好、
     * 还没有玩家数据时的初值）—— 那是唯一安全的初值：
     * 反过来（默认可合成）会先闪一下"能合"，再变成"不能合"。
     */
    public void refresh(Inventory inv) {
        rows.clear();
        List<Recipe> all = RecipeRegistry.all();
        for (int i = 0; i < all.size(); i++) {
            Recipe recipe = all.get(i);
            List<Crafting.Shortfall> missing =
                    inv == null ? Crafting.missingFor(emptyInventory(), recipe)
                            : Crafting.missingFor(inv, recipe);
            rows.add(new Row(i, recipe,
                    missing.isEmpty() ? Status.CRAFTABLE : Status.MISSING_MATERIALS,
                    missing));
        }
    }

    private static Inventory emptyInventory() {
        return new Inventory();
    }

    // ============================================================ 合成

    /**
     * 尝试合成第 {@code index} 行，并<b>在成功之后立即刷新</b>。
     *
     * <p><b>扣料与出货全部由 {@link Crafting#craft} 完成</b>，本方法只做三件界面该做的事：
     * 取配方、转交、按结果刷新。
     *
     * <p><b>为什么失败也要 refresh：</b>缺料与"放不下"的界面状态同样需要更新 ——
     * 尤其在"背包塞满"那一次，材料其实没被扣，界面若沿用旧缓存就会显示"能合"，
     * 而玩家再点一次还是失败，且<b>不会有任何反馈</b>（这正是裁定第 7 条禁止的情形）。
     *
     * @return {@link Crafting#craft} 的结果；行号越界时返回 {@code null}
     */
    public Crafting.Outcome craft(int index, Inventory inv) {
        Row target = row(index);
        if (target == null || inv == null) {
            return null;
        }
        Crafting.Outcome outcome = Crafting.craft(inv, target.recipe());
        refresh(inv);
        return outcome;
    }

    // ============================================================ 文案

    /**
     * 一行的材料需求，例如 {@code 铁锭 8/8 铜锭 3/3}（PRD 6.7 的形状）。
     *
     * <p>写成"拥有 / 需求"而不是只写需求：玩家需要同时看到"我有多少"与"要多少"，
     * 否则"还差几个"要心算。缺的那些项自然会显示为 {@code 3/8}。
     */
    public String ingredientText(Row row, Inventory inv) {
        if (row == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Recipe.Ingredient ing : row.recipe().ingredients()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            int have = inv == null ? 0 : inv.countOfItem(ing.itemId());
            sb.append(Localization.displayName(ing.itemId()))
                    .append(' ')
                    .append(have).append('/').append(ing.count());
        }
        return sb.toString();
    }

    /**
     * 一行的状态文案：可合成时是 {@code [合成]}，缺料时是 {@code 缺少 铁锭 ×3}。
     *
     * <p>缺料文案复用 {@link Crafting#describeMissing}（与日志 / 提示同源），
     * 于是"界面上看到的这句话"与"逻辑给出的缺料清单"不可能对不上。
     */
    public String statusText(Row row) {
        if (row == null) {
            return "";
        }
        return row.craftable()
                ? Localization.text(Localization.CRAFT_ACTION)
                : Crafting.describeMissing(row.shortfalls());
    }

    /** 产物的显示名（PRD 6.7：玩家可见名称的唯一来源是 Localization）。 */
    public static String outputName(Recipe recipe) {
        return Localization.displayName(recipe.outputItemId());
    }

    /** 产物的物品 runtimeId（图标绘制用；与背包格子共用 {@code ItemIcon}）。 */
    public static int outputRuntimeId(Recipe recipe) {
        return ItemRegistry.runtimeIdOf(recipe.outputItemId());
    }
}

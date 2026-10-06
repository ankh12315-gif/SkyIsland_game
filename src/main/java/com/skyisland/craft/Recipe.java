package com.skyisland.craft;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一条合成配方（PRD 5.6.2 的"一行"）。
 *
 * <p><b>为什么配方是"数据"而不是一串 {@code if}：</b>PRD 5.6.2 把配方写成一张表，
 * 表里每一行的形状完全相同（产物 + 数量 + 材料表 + 分类）。把这张表搬进代码时，
 * 如果写成 {@code if (玩家有铁锭 && 玩家有火药) {...}}，那么"PRD 改了一个数字"
 * 就变成"要在一堆条件里找那一行并且改对"。收成一个 record 之后，
 * 配方表与 {@link RecipeRegistry} 里的清单是**同构**的，可以逐行对照 PRD 核对。
 *
 * <p><b>所有字段都是 stable string ID，不是 runtimeId。</b>理由与存档一致：
 * runtimeId 会随方块数量位移（见 {@code BlockRegistry} 的说明），
 * 而配方是"设计数据"，它必须比一次注册顺序调整活得更久。
 *
 * <p><b>构造期校验：</b>非法配方一律抛 {@link IllegalArgumentException}，
 * 让它在注册表 bootstrap 时<b>立刻崩</b>，而不是等到玩家点了合成才表现为
 * "材料对了但合不出东西"。
 *
 * @param id           配方稳定 ID（如 {@code skyisland:r16_rifle}，与 TECH_DESIGN §L.5 同形）
 * @param outputItemId 产物物品的 stable ID
 * @param outputCount  产出数量（PRD 5.6.2 的"产出数量"列）
 * @param ingredients  材料表（PRD 5.6.2 的"材料"列）；不得为空、不得有重复物品
 * @param category     分类（PRD 5.6.2 的"分类"列）
 */
public record Recipe(
        String id,
        String outputItemId,
        int outputCount,
        List<Ingredient> ingredients,
        RecipeCategory category
) {

    /**
     * 一项材料：物品 stable ID + 需要的数量。
     *
     * <p>数量是"这一种物品总共要多少个"，<b>不是"某一格要有多少个"</b>。
     * 这条区分是整个合成系统最容易写错的地方，见 {@link Crafting} 的类文档。
     */
    public record Ingredient(String itemId, int count) {
        public Ingredient {
            if (itemId == null || itemId.isBlank()) {
                throw new IllegalArgumentException("材料物品 ID 不能为空: " + itemId);
            }
            if (count <= 0) {
                throw new IllegalArgumentException("材料数量必须为正: " + itemId + " ×" + count);
            }
        }
    }

    public Recipe {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("配方 ID 不能为空");
        }
        if (outputItemId == null || outputItemId.isBlank()) {
            throw new IllegalArgumentException("产物物品 ID 不能为空: " + id);
        }
        if (outputCount <= 0) {
            throw new IllegalArgumentException("产出数量必须为正: " + id + " → " + outputCount);
        }
        if (category == null) {
            throw new IllegalArgumentException("配方分类不能为空: " + id);
        }
        if (ingredients == null || ingredients.isEmpty()) {
            // 空配方的语义是"白送"，它没有任何材料门槛，也就无法被"缺料"这条规则覆盖。
            // PRD 5.6.2 里不存在这种配方，因此按非法处理而不是"允许但没人用"。
            throw new IllegalArgumentException("配方必须有至少一项材料: " + id);
        }
        Set<String> seen = new HashSet<>();
        for (Ingredient ing : ingredients) {
            if (ing == null) {
                throw new IllegalArgumentException("配方材料不得为 null: " + id);
            }
            if (!seen.add(ing.itemId())) {
                // 重复写同一材料（例如"铁锭 ×4 + 铁锭 ×3"）几乎一定是笔误，
                // 而且它会让"缺料清单"出现两行同物品的提示。合成时先合并再校验，
                // 不如在构造期直接拒绝，让写错的人在启动时就看见。
                throw new IllegalArgumentException(
                        "配方材料不得重复登记同一物品（请把数量合并到一条）: " + id + " / " + ing.itemId());
            }
            if (ing.itemId().equals(outputItemId)) {
                throw new IllegalArgumentException(
                        "配方的材料不得是产物本身（自环）: " + id + " / " + outputItemId);
            }
        }
        ingredients = List.copyOf(ingredients);
    }

    /** 同一配方的两条记录相等 ⇔ ID 相同（ID 是配方的身份）。 */
    @Override
    public boolean equals(Object o) {
        return o instanceof Recipe other && other.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

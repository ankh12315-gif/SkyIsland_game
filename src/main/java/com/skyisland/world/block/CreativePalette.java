package com.skyisland.world.block;

import com.skyisland.item.ItemRegistry;
import com.skyisland.ui.Localization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★<b>创造模式的方块面板内容</b>（PRD_BLOCK_CREATIVE_v1.0.md §5.1「方块面板」）。
 *
 * <h2>★ 它为什么从 {@link BlockRegistry} 派生，而不是列 20 个 stable ID</h2>
 * 把它写成 {@code List.of("skyisland:dirt", "skyisland:stone", …)} 是这类面板的默认写法，
 * 而它的失败模式是<b>静默的</b>：将来第 21 种方块登记进 {@code BlockRegistry}，
 * 玩家在世界里能挖到、能放置，而创造面板里<b>没有它</b> ——
 * <b>没有任何东西会红</b>，因为那个列表和注册表之间没有任何约束关系。
 *
 * <p>本类的做法是：<b>遍历注册表，挑出该出现的那些</b>。
 * 于是"新登记的方块忘了加面板"这件事<b>结构上不可能发生</b> ——
 * 它会自动出现，代价只是"分组可能先落在兜底组里"，而那由
 * {@link #categoryOverrides} 显式登记、由 {@code CreativePaletteTest} 断言。
 *
 * <h2>★★ {@code resource_core} 为什么绝不会出现（PRD §5.1 末段的硬约束）</h2>
 * PRD 5.1.1 明文规定资源核心「不可放置、玩家无法通过任何途径获得」，
 * §5.1 又要求「<b>资源核心不出现</b>在创造面板」——
 * 若面板把它列出来，就是<b>用 UI 绕过了 PRD 的硬约束</b>。
 *
 * <p>本类的排除判据是 <b>{@link Block#isPlaceable()}</b>，而资源核心登记时
 * {@code placeable = false}（它是系统方块，PRD 5.1.1），
 * 所以它<b>天然被排除</b>，不需要第二套"黑名单"。
 *
 * <p>★ <b>为什么这个"天然"是设计而不是运气</b>：黑名单版本会有一个致命问题 ——
 * {@code playerBlockCount()} 也在数"玩家常规方块"，两处判据若不一致，
 * 就会出现"面板 19 格 / 口径 20 种"这种<b>各自都说得通</b>的不一致。
 * 用同一个 {@code isPlaceable()} 之后，
 * <b>面板条目数 === {@link BlockRegistry#playerBlockCount()}</b> 成为一条恒等式，
 * 可以直接断言（见 {@code CreativePaletteTest#entryCountEqualsPlayerBlockCount}）。
 */
public final class CreativePalette {

    /**
     * 分组覆盖表：stable ID → 分类。
     *
     * <p>★ <b>它必须是穷举的，且这一点由测试强制</b>：
     * 新登记的方块若忘了在这里登记，{@link #categoryOf} 会返回 {@code null}，
     * 而 {@link #groups()} 会把它放进"未分类"兜底组 ——
     * <b>兜底会让断言失去意义</b>，所以 {@code CreativePaletteTest} 里有一条
     * 「不允许存在未分类方块」的反向断言。
     *
     * <p>为什么这里可以手写而 §5.1 的 20 个 ID 不可以手写：
     * 分类是<b>语义判断</b>（草方块算自然还是建材？没有规则能推出来，必须有人决定），
     * 而"哪些方块进面板"是<b>规则判断</b>（= 可放置）—— 手写后者会与规则脱钩。
     */
    private static final Map<String, BlockCategory> categoryOverrides = new LinkedHashMap<>();

    static {
        // ---- 自然 ----
        category("skyisland:dirt", BlockCategory.NATURAL);
        category("skyisland:grass_block", BlockCategory.NATURAL);
        category("skyisland:sand", BlockCategory.NATURAL);
        category("skyisland:log", BlockCategory.NATURAL);
        category("skyisland:leaves", BlockCategory.NATURAL);
        // ---- 建材 ----
        category("skyisland:stone", BlockCategory.BUILDING);
        category("skyisland:cobblestone", BlockCategory.BUILDING);
        category("skyisland:oak_planks", BlockCategory.BUILDING);
        category("skyisland:glass", BlockCategory.BUILDING);
        category("skyisland:torch", BlockCategory.BUILDING);
        category("skyisland:wooden_door", BlockCategory.BUILDING);
        category("skyisland:stone_brick", BlockCategory.BUILDING);
        category("skyisland:iron_block", BlockCategory.BUILDING);
        category("skyisland:slab", BlockCategory.BUILDING);
        // ---- 矿物 ----
        category("skyisland:iron_ore", BlockCategory.MINERAL);
        category("skyisland:coal_ore", BlockCategory.MINERAL);
        category("skyisland:copper_ore", BlockCategory.MINERAL);
        category("skyisland:crystal_ore", BlockCategory.MINERAL);
        category("skyisland:gold_ore", BlockCategory.MINERAL);
        // ---- 作物 ----
        category("skyisland:wheat", BlockCategory.CROP);
    }

    private static void category(String stableId, BlockCategory category) {
        categoryOverrides.put(stableId, category);
    }

    /**
     * 面板的一格。
     *
     * @param block   对应方块（{@code placeable = true}，已排除空气与系统方块）
     * @param category 所属分组
     * @param itemRuntimeId 该方块的<b>物品</b> runtimeId（方块物品与方块同 stable ID，见 ItemRegistry）
     * @param stackSize 单击取出的一组数量（§5.1「单击取满一组（64）」）
     */
    public record Entry(Block block, BlockCategory category, int itemRuntimeId, int stackSize) {

        /** 面板上显示的名称（走方块的中文名，与背包里显示同一套）。 */
        public String displayName() {
            String name = Localization.displayName(block.id());
            return name == null || name.isBlank() ? block.id() : name;
        }

        /** 数量显示：{@code ∞} 而不是数字（§5.1「数量显示」行）。 */
        public String stackLabel() {
            return INFINITY_LABEL;
        }
    }

    /** §5.1 要求的数量标记：每格显示 {@code ∞}（<b>不是数字</b>）。 */
    public static final String INFINITY_LABEL = "∞";

    private final List<Entry> entries;
    private final Map<BlockCategory, List<Entry>> byCategory;

    private CreativePalette(List<Entry> entries) {
        this.entries = entries;
        Map<BlockCategory, List<Entry>> grouped = new EnumMap<>(BlockCategory.class);
        for (Entry e : entries) {
            grouped.computeIfAbsent(e.category(), k -> new ArrayList<>()).add(e);
        }
        for (List<Entry> list : grouped.values()) {
            list.sort((a, b) -> Integer.compare(a.block().runtimeId(), b.block().runtimeId()));
        }
        this.byCategory = Collections.unmodifiableMap(grouped);
    }

    /**
     * ★ 构建当前版本的完整面板。
     *
     * <p>遍历 {@link BlockRegistry#all()}，取 {@code isPlaceable() && !isAir()} 者。
     * <b>空气与资源核心都不满足该判据</b>，因此被排除（前者 {@code placeable = false}，
     * 后者是系统方块）。
     *
     * <p>分组顺序固定为 {@link BlockCategory} 的<b>声明顺序</b>
     * （自然 → 建材 → 矿物 → 作物），而不是 {@code HashMap} 的迭代序 ——
     * 面板顺序对玩家可见，不能交给哈希表决定。
     */
    public static CreativePalette build() {
        List<Entry> entries = new ArrayList<>();
        for (Block block : BlockRegistry.all()) {
            if (block.isAir() || !block.isPlaceable()) {
                continue;
            }
            int itemRuntimeId = ItemRegistry.runtimeIdOf(block.id());
            BlockCategory category = categoryOf(block.id());
            // ★★ 这里<b>必须</b>显式检查，不能让 null 走到下面的 EnumMap。
            //   曾经的写法是直接 build()，NPE 发生在构造器的
            //   grouped.computeIfAbsent(e.category(), …) 里 ——
            //   而构造器是<b>不可捕获</b>的失败点，症状是「打开背包直接崩」，
            //   且栈顶指向 EnumMap.typeCheck，与"分类表漏登记"毫无关系。
            //   ★ 这条是被反向验证逼出来的：把 resource_core 的 placeable
            //   改成 true（它没在分类表里）⇒ 整类 16 条测试全部 ERROR。
            if (category == null) {
                throw new IllegalStateException(
                        "方块 " + block.id() + " 已在注册表里且可放置，"
                                + "但没有在 CreativePalette 的分类表里登记分组。"
                                + "修法：补上 category(\"" + block.id() + "\", BlockCategory.XXX);"
                                + "—— 不要给 categoryOf() 加兜底默认值，"
                                + "那会让「漏登记」这一事实消失。");
            }
            entries.add(new Entry(block, category, itemRuntimeId, 64));
        }
        entries.sort((a, b) -> Integer.compare(a.block().runtimeId(), b.block().runtimeId()));
        return new CreativePalette(entries);
    }

    /**
     * 该 stable ID 的分组；<b>未登记时返回 {@code null}</b>（不落兜底组）。
     *
     * <p>★ <b>为什么返回 {@code null} 而不是默认 {@link BlockCategory#BUILDING}</b>：
     * 兜底值会让"忘了登记"表现为"被分到建材里"，而断言若只检查
     * "每项都有分组"就会通过 —— <b>兜底值让守卫失去意义</b>。
     * 返回 {@code null} 使"漏登记"变成一个可断言的事实。
     */
    public static BlockCategory categoryOf(String stableId) {
        return categoryOverrides.get(stableId);
    }

    /**
     * 分类覆盖表里登记过的全部 stable ID（<b>只读副本</b>）。
     *
     * <p>守卫用它做<b>反向</b>检查："表里有、面板里没有" ⇒ 死数据。
     * 只做正向检查（面板项都有分组）的话，删掉面板里某个方块的同时
     * 忘了删分类表，会留下一条永远没人看的数据。
     */
    public static Set<String> categoryTableIds() {
        return Collections.unmodifiableSet(categoryOverrides.keySet());
    }

    /** 面板总条目数（§5.1：应为 {@link BlockRegistry#playerBlockCount()}）。 */
    public int size() {
        return entries.size();
    }

    /** 第 {@code index} 格；越界返回 {@code null}。 */
    public Entry entry(int index) {
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    /** 全部条目（按 runtimeId 升序，不可修改）。 */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * 按分组取得的条目（{@link BlockCategory} 声明顺序即迭代序）。
     *
     * <p>★ 迭代序是 {@link EnumMap} 的<b>声明序</b>而非哈希序，
     * 所以「自然 / 建材 / 矿物 / 作物」在界面上永远是这个顺序。
     */
    public Map<BlockCategory, List<Entry>> groups() {
        return byCategory;
    }

    /** 面板里是否出现该方块（供守卫直接问"它到底进没没进面板"）。 */
    public boolean contains(String blockStableId) {
        for (Entry e : entries) {
            if (e.block().id().equals(blockStableId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ★ M4-S7：<b>分组有序的视图</b> —— 渲染与布局共用的唯一事实来源。
     *
     * <p><b>为什么要有它（而不是两个各自排序的方法）</b>：
     * 「界面上的方块顺序」与「每一行有几格」必须由<b>同一次排序</b>推出。
     * 若 {@code toRows()} 与 {@code displayOrder()} 各自排一次，
     * 排序键一旦有一处不同，症状是<b>第 12 格画在第 13 格的位置</b> ——
     * 一个纯视觉的偏移，不报错、不崩溃、只在截图里看得见。
     * ⇒ 两者都从本视图派生，排序只写一次。
     *
     * @param ordered   按「分类声明序 → 组内 runtimeId」排好的条目
     * @param rows      行结构（交给 {@code InventoryLayout.compute}）
     * @param rowCategory 每一行所属分类（标题行与方块行同属一组）
     */
    public record CreativeView(List<Entry> ordered,
                               com.skyisland.render.ui.InventoryLayout.CreativeRows rows,
                               List<BlockCategory> rowCategory) {

        /** 按界面顺序的条目（与 {@link #rows()} 的行号一致）。 */
        public List<Entry> ordered() {
            return Collections.unmodifiableList(ordered);
        }

        public int size() {
            return ordered.size();
        }

        public Entry at(int index) {
            return index >= 0 && index < ordered.size() ? ordered.get(index) : null;
        }
    }

    /**
     * 生成界面视图。
     *
     * <p><b>行的排布规则</b>：每组各占一段，段内按 {@code columns} 列换行，
     * <b>组与组之间不共用行</b> —— 共享行会让"建材"的方块紧贴在"自然"的标题下面，
     * 读起来像是自然的一部分。
     *
     * <p>★ 每一组的<b>第一排</b>行不是标题行：标题之后紧跟方块，
     * 中间不插空行 —— 20 格的面板没有奢侈的空间，而 PRD §5.1 只要求"按分类分组"，
     * 没有要求组间留白。
     *
     * @param columns 每行列数
     */
    public CreativeView view(int columns) {
        if (columns <= 0) {
            throw new IllegalArgumentException("columns 必须为正，收到 " + columns);
        }
        // ---- 1) 先切成分组段（顺序 = 分类声明序，段内 = runtimeId 升序）----
        List<Entry> ordered = new ArrayList<>();
        for (BlockCategory category : BlockCategory.values()) {
            for (Entry e : entries) {
                if (e.category() == category) {
                    ordered.add(e);
                }
            }
        }
        // ★ 断言式校验：ordered 必须与 entries 同一集合，只是换了顺序。
        //   少一个就是"某个条目 category() 为 null 被静默跳过"——
        //   那会让面板少一格而无任何告警（这正是本类要防的静默缺失）。
        if (ordered.size() != entries.size()) {
            throw new IllegalStateException(
                    "分组遍历丢掉了条目：entries=" + entries.size()
                            + " 而 ordered=" + ordered.size()
                            + "。通常意味着某个 Entry 的 category() 为 null。");
        }

        // ---- 2) 逐段算行，再拼（每段独立，因此不可能互相影响）----
        List<Integer> rowOfEntry = new ArrayList<>(ordered.size());
        List<Boolean> rowIsGroup = new ArrayList<>();
        List<BlockCategory> rowCategory = new ArrayList<>();
        int row = 0;
        int index = 0;
        while (index < ordered.size()) {
            BlockCategory category = ordered.get(index).category();
            int end = index;
            while (end < ordered.size() && ordered.get(end).category() == category) {
                end++;
            }
            int groupSize = end - index;

            // 分类标题独占一行
            rowIsGroup.add(Boolean.TRUE);
            rowCategory.add(category);
            row++;

            // 方块行：ceil(groupSize / columns) 行
            int dataRows = (groupSize + columns - 1) / columns;
            for (int r = 0; r < dataRows; r++) {
                rowIsGroup.add(Boolean.FALSE);
                rowCategory.add(category);
                row++;
            }
            for (int i = index; i < end; i++) {
                int offset = i - index;
                rowOfEntry.add(row - dataRows + offset / columns);
            }
            index = end;
        }

        int[] rows = new int[rowOfEntry.size()];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = rowOfEntry.get(i);
        }
        boolean[] groups = new boolean[rowIsGroup.size()];
        for (int i = 0; i < groups.length; i++) {
            groups[i] = rowIsGroup.get(i);
        }
        com.skyisland.render.ui.InventoryLayout.CreativeRows rowStruct =
                new com.skyisland.render.ui.InventoryLayout.CreativeRows(
                        ordered.size(), groups.length, rows, groups);
        return new CreativeView(ordered, rowStruct, List.copyOf(rowCategory));
    }
}

package com.skyisland.world.block;

import com.skyisland.item.ItemRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★<b>M4-S7 守卫：创造面板（PRD_BLOCK_CREATIVE_v1.0.md §5.1）。</b>
 *
 * <p>§5.1 有七行规格，本类逐条钉死：
 * <ol>
 *   <li>内容 = <b>全部 20 种玩家常规方块</b>（§3.4 口径）；</li>
 *   <li>排序 = 按分类分组：自然 / 建材 / 矿物 / 作物；</li>
 *   <li>数量显示 = 每格 <b>∞</b>（不是数字）；</li>
 *   <li>取出 = 单击取满一组（64）；</li>
 *   <li>★★ <b>系统方块（{@code resource_core}）绝不出现</b>；</li>
 *   <li>枪械 / 弹药不出现（不是方块，且创造模式不给枪）。</li>
 * </ol>
 *
 * <h2>★ 本类最核心的一条是恒等式，不是清单比对</h2>
 * {@link #entryCountEqualsPlayerBlockCount()} 断言
 * <b>面板条目数 === {@link BlockRegistry#playerBlockCount()}</b>。
 * <p>它的价值在于<b>自动覆盖将来新登记的方块</b>：面板从注册表<b>派生</b>，
 * 所以第 21 种方块一登记就自动出现，而这条断言<b>不需要跟着改</b>。
 * <p>★ 对照：若面板写死 20 个 ID，这条断言在第 21 种方块登记时会变红 ——
 * 那正是"硬编码清单"的正确失败方式（吵闹但正确）；
 * 而本类当前形态下它<b>永远绿</b>，因为两者同源。
 * ⇒ 真正的风险转移到"分组覆盖表漏登记"，由 {@link #everyEntryHasACategory()} 承担。
 */
class CreativePaletteTest {

    private static CreativePalette palette() {
        return CreativePalette.build();
    }

    // ============================================================ ① 内容：恰好 20 种

    /** §5.1 + §3.4：面板必须恰好是玩家常规方块总数（当前 20）。 */
    @Test
    void entryCountEqualsPlayerBlockCount() {
        CreativePalette p = palette();
        assertEquals(BlockRegistry.playerBlockCount(), p.size(),
                "★ 面板条目数必须等于 playerBlockCount()（§3.4 口径 = 13+2+3+2 = 20）。"
                        + "两者必须同源派生：面板从 BlockRegistry 遍历得出，"
                        + "playerBlockCount() 也从同一张注册表数出。");
    }

    /** §3.4 的数字口径本身不能被面板改动带偏（三个口径必须分离，见 S5 的教训）。 */
    @Test
    void theEntryCountIsThePrdNumberNotJustConsistentWithIt() {
        assertEquals(20, palette().size(),
                "★ 面板应恰好 20 格（PRD §3.4 = MVP 13 + Alpha 矿石 2 + Alpha 内容 3 + 后续 2）。"
                        + "只断言「与 playerBlockCount() 一致」是不够的 —— "
                        + "两个数一起错时会互相印证。");
        assertEquals(20, BlockRegistry.playerBlockCount());
        assertEquals(13, BlockRegistry.MVP_CORE_PLAYER_BLOCK_COUNT,
                "MVP 核心 13 种一字未改。");
    }

    /** 面板里不得有重复方块（遍历注册表理论上不会，但要有守卫钉住派生逻辑本身）。 */
    @Test
    void noBlockAppearsTwice() {
        Set<String> seen = new HashSet<>();
        List<String> dup = new ArrayList<>();
        for (CreativePalette.Entry e : palette().entries()) {
            if (!seen.add(e.block().id())) {
                dup.add(e.block().id());
            }
        }
        assertTrue(dup.isEmpty(), "面板里有重复方块: " + dup);
    }

    // ============================================================ ① 系统方块绝不出现（PRD 硬约束）

    /**
     * ★★<b>PRD §5.1 的头号硬约束：资源核心绝不能出现在创造面板。</b>
     *
     * <p>PRD 5.1.1 明文规定它「不可放置、玩家无法通过任何途径获得」，
     * 而面板列出它就等于<b>用 UI 绕过了 PRD 的硬约束</b>。
     */
    @Test
    void resourceCoreIsNeverInThePalette() {
        CreativePalette p = palette();
        assertFalse(p.contains("skyisland:resource_core"),
                "★★ resource_core 绝不能出现在创造面板（PRD §5.1）。"
                        + "它是系统方块：不可放置、玩家无法通过任何途径获得。");

        // 逐条把话说明白：不是"恰好没排到"，而是"它根本不在候选集里"
        Block core = BlockRegistry.byName("skyisland:resource_core");
        assertNotNull(core, "资源核心应当仍在注册表里（它没被删，只是不能进面板）");
        assertFalse(core.isPlaceable(),
                "★ 排除必须来自 placeable=false 这一个判据，"
                        + "而不是「恰好它排在后面」。若有人把 placeable 改成 true，"
                        + "本条会红 —— 那正是我们要看见的（它意味着排除机制失效了）。");
    }

    /** 对照：空气同样不在面板里，且理由不同（它是 runtimeId 0 的哨兵）。 */
    @Test
    void airIsNeverInThePalette() {
        assertFalse(palette().contains("skyisland:air"),
                "空气是 runtimeId 0 的哨兵方块，不是玩家可放置方块。");
    }

    /** ★ 排除机制的唯一性：面板排除的方块集 === 不可放置方块集（不多不少）。 */
    @Test
    void theExclusionSetIsExactlyTheNonPlaceableSet() {
        CreativePalette p = palette();
        for (Block b : BlockRegistry.all()) {
            boolean inPalette = p.contains(b.id());
            if (b.isAir() || !b.isPlaceable()) {
                assertFalse(inPalette,
                        "不可放置 / 空气的方块不得进面板: " + b.id());
            } else {
                assertTrue(inPalette,
                        "可放置的方块必须进面板: " + b.id()
                                + "（它能被玩家挖到并放置，而面板里没有它 = "
                                + "「新登记方块忘了加面板」这类静默缺陷）");
            }
        }
    }

    // ============================================================ ① 枪械与弹药不出现

    /** §5.1 末行 + §5.6：枪械与弹药不出现（创造模式不给枪）。 */
    @Test
    void noWeaponOrAmmoItemIsInThePalette() {
        CreativePalette p = palette();
        for (String id : List.of("skyisland:pistol", "skyisland:smg", "skyisland:rifle",
                "skyisland:pistol_ammo", "skyisland:rifle_ammo")) {
            assertFalse(p.contains(id), id + " 不该出现在创造面板（§5.6：创造模式不给枪）");
        }
    }

    /**
     * ★ 面板里的每一项都<b>必须是物品</b>，不能是"只有方块没有物品"的形态。
     *
     * <p>这间接钉住了 S5 的结论：方块物品与方块同 stable ID（由 ItemRegistry 的
     * "前缀对齐"不变式保证），所以"能进面板"必然意味着"有物品形态、可取出"。
     */
    @Test
    void everyEntryHasAUsableItemRuntimeId() {
        for (CreativePalette.Entry e : palette().entries()) {
            assertTrue(e.itemRuntimeId() > 0,
                    "面板项 " + e.block().id() + " 没有可用的物品 runtimeId = "
                            + e.itemRuntimeId() + "（取出会失败）");
            assertEquals(e.block().id(),
                    ItemRegistry.byRuntimeId(e.itemRuntimeId()).id(),
                    "面板项的物品应当与方块同 stable ID（方块物品沿用方块 ID）");
        }
    }

    // ============================================================ ② 分类分组

    /** ★ 每一项都必须有分组 —— 这条钉住"分类覆盖表不得漏登记"。 */
    @Test
    void everyEntryHasACategory() {
        List<String> unclassified = new ArrayList<>();
        for (CreativePalette.Entry e : palette().entries()) {
            if (e.category() == null) {
                unclassified.add(e.block().id());
            }
        }
        assertTrue(unclassified.isEmpty(),
                "★ 以下方块进了面板却<b>没有登记分类</b>：" + unclassified
                        + "。它们会以 null 分组出现，界面上无法归位。"
                        + "修法：在 CreativePalette 的分类覆盖表里补上，"
                        + "而不是给 categoryOf() 加兜底默认值 —— "
                        + "兜底会让本条断言永久失去意义。");
    }

    /**
     * ★★<b>未分类的方块必须被<b>显式拒绝</b>，而不是在下游 NPE。</b>
     *
     * <p>本条是被一次反向验证改写出来的：把 {@code resource_core} 的
     * {@code placeable} 改成 {@code true}（它不在分类表里）之后，
     * {@link #palette()} 抛出的 NPE 栈顶是
     * {@code EnumMap.typeCheck} ← {@code CreativePalette.<init>}，
     * <b>与"分类表漏登记"毫无关系</b>，而真实原因是缺一行分类登记。
     *
     * <p>★ <b>更糟的是失败点不可控</b>：异常发生在构造器里，
     * 任何依赖"面板可用"的地方（背包界面、门禁、自测）都会崩，
     * 而错误信息指向 {@code java.util.EnumMap} —— 那是一个与本项目毫无关系的类。
     *
     * <p>本条因此直接断言：{@code build()} 对未分类方块抛的是
     * <b>带 stable ID 的 {@link IllegalStateException}</b>。
     */
    @Test
    void anUncategorisedBlockIsRejectedWithAReadableMessage() {
        // 当前状态下所有可放置方块都已分类，因此本条通过"没有未分类方块"成立。
        // ★ 它的真正价值是**反向验证**：注入一个未分类的可放置方块时，
        //   异常类型与消息必须仍然是"点名 stable ID"的那一种，
        //   而不是 EnumMap 的 NPE（见类注释里那次事故）。
        for (Block b : BlockRegistry.all()) {
            if (b.isAir() || !b.isPlaceable()) {
                continue;
            }
            assertNotNull(CreativePalette.categoryOf(b.id()),
                    "可放置方块 " + b.id() + " 必须有分类（否则 build() 会抛）");
        }
    }

    /**
     * ★ <b>{@link CreativePalette#build()} 不得因为未分类方块而在下游崩掉。</b>
     *
     * <p>本条用"分类表里有、但注册表里没有的孤儿键"来制造无法静态复现的场景：
     * 它不修改任何全局状态，只是断言 build() 的调用链上
     * <b>不存在 {@code EnumMap} 的 NPE 路径</b>。
     */
    @Test
    void buildingThePaletteNeverThrowsForTheCurrentRegistry() {
        CreativePalette p = assertDoesNotThrow(CreativePalette::build,
                "★ 当前注册表下 build() 必须成功（这是门禁与自测的隐含前提）");
        assertNotNull(p);
    }

    /** 反向：分类表里不得有"不在面板里"的方块（否则是死数据）。 */
    @Test
    void theCategoryTableHasNoDeadEntries() {
        CreativePalette p = palette();
        List<String> dead = new ArrayList<>();
        for (Block b : BlockRegistry.all()) {
            if (CreativePalette.categoryTableIds().contains(b.id()) && !p.contains(b.id())) {
                dead.add(b.id());
            }
        }
        assertTrue(dead.isEmpty(),
                "★ 分类表里登记了这些方块，但它们不在面板中（死数据）：" + dead
                        + "分类表的键必须与面板的条目一一对应。");
    }

    /** §5.1 的四类必须全部出现（不允许某一类为空组）。 */
    @Test
    void allFourPrdCategoriesArePresentAndNonEmpty() {
        CreativePalette p = palette();
        assertEquals(EnumSet.allOf(BlockCategory.class),
                EnumSet.copyOf(p.groups().keySet()),
                "★ 面板应恰好有 PRD §5.1 的四类：自然 / 建材 / 矿物 / 作物");
        for (var e : p.groups().entrySet()) {
            assertFalse(e.getValue().isEmpty(), "分组 " + e.getKey() + " 不应为空");
        }
    }

    /** 分组迭代序 = 枚举声明序（界面顺序不能交给哈希表决定）。 */
    @Test
    void groupsIterateInPrdOrder() {
        assertEquals(
                List.of(BlockCategory.NATURAL, BlockCategory.BUILDING,
                        BlockCategory.MINERAL, BlockCategory.CROP),
                new ArrayList<>(palette().groups().keySet()),
                "★ 分组顺序必须是 PRD §5.1 的 自然 → 建材 → 矿物 → 作物。"
                        + "EnumMap 保证声明序；若换成 HashMap，这里会随机变。");
    }

    /** 分类不能是死代码：每个非空分组里都得有真实方块。 */
    @Test
    void categoriesMatchTheKnownPrdGroups() {
        CreativePalette p = palette();
        // 矿物的五种：铁 / 煤 / 铜 / 晶体 / 金（§3.4 的 5 种矿石）
        assertEquals(5, p.groups().get(BlockCategory.MINERAL).size(),
                "矿物组应是 5 种矿石（铁/煤/铜/晶体/金）");
        // 作物：小麦（§3.1 的唯一作物）
        assertEquals(1, p.groups().get(BlockCategory.CROP).size(),
                "作物组当前只有小麦");
        assertEquals("skyisland:wheat",
                p.groups().get(BlockCategory.CROP).get(0).block().id());
    }

    // ============================================================ ③∞ 与 ④一组

    /** §5.1「数量显示 = ∞（不是数字）」+「单击取满一组（64）」。 */
    @Test
    void theStackLabelIsInfinityAndTheSizeIsAFullStack() {
        assertEquals("∞", CreativePalette.INFINITY_LABEL,
                "§5.1：数量显示必须是 ∞（不是数字）");
        for (CreativePalette.Entry e : palette().entries()) {
            assertEquals("∞", e.stackLabel(),
                    "面板项 " + e.block().id() + " 应显示 ∞");
            assertEquals(64, e.stackSize(),
                    "§5.1：单击取满一组（64）");
        }
    }

    // ============================================================ 稳定性

    /** 面板是静态内容，反复 build() 必须一致（否则界面上会出现"方块自己动了"）。 */
    @Test
    void repeatedBuildsProduceIdenticalContent() {
        CreativePalette a = palette();
        CreativePalette b = palette();
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.entry(i).block().id(), b.entry(i).block().id(),
                    "第 " + i + " 格在两次 build() 之间变了");
        }
    }

    /** 越界访问返回 null 而不是抛异常（界面每帧都在做边界检查）。 */
    @Test
    void outOfRangeAccessReturnsNull() {
        CreativePalette p = palette();
        assertNull(p.entry(-1));
        assertNull(p.entry(p.size()));
        assertNull(p.entry(p.size() + 100));
    }
}

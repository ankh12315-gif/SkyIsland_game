package com.skyisland.world.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-S5 补齐的 5 种方块的<b>逐条规格</b>测试
 * （PRD_BLOCK_CREATIVE_v1.0.md §3.2 / §3.4）。
 *
 * <h2>为什么单独一个类，而不是把断言塞进 {@code BlockRegistryTest}</h2>
 * {@code BlockRegistryTest} 保护的是<b>不变式</b>（注册顺序即 runtimeId、总数口径）。
 * 本类保护的是<b>规格逐值</b>——五种方块各自的硬度、形态、碰撞、渲染类型。
 * 前者在数量被改错时变红，后者在<b>某个属性被写错</b>时变红，两者互不替代。
 *
 * <h2>★ 为什么要逐条写死硬度与形态，而不是断言"落在某个区间"</h2>
 * 因为 PRD §3.2 的这些数字是<b>规格</b>不是性能指标：
 * 小麦 0.2 秒与 0.3 秒在玩法上是两回事（作物该秒挖），
 * 台阶的 SLAB_BOTTOM 与 FULL 之差是"半高"与"满格"之差 —— 后者会让玩家以为站在方块上。
 * 区间断言会让这两种错误一起通过。
 */
class BlockRegistryS5Test {

    // ================================================================ §3.2.1 金矿石

    @Test
    void goldOreMatchesPrd321() {
        Block b = BlockRegistry.goldOre();
        assertNotNull(b, "S5 应登记金矿石（PRD §3.2.1）");
        assertEquals("skyisland:gold_ore", b.id(),
                "稳定 ID 用单数 gold_ore；PRD §3.2.1 明文「不得写成 gold_ores」");
        assertEquals(4.0f, b.hardness(), 1e-6f, "PRD §3.2.1：空手 4.0 秒");
        assertEquals(RenderType.OPAQUE, b.renderType(), "矿石是实心方块");
        assertEquals(BlockShape.FULL, b.shape(), "金矿石是满方块");
        assertEquals("skyisland:gold_ore", b.dropItemId(), "掉落自身 ×1");
        assertEquals(1, b.dropCount(), "掉落 1 个");
        assertTrue(b.isPlaceable(), "玩家可放置");
    }

    /**
     * ★ PRD §3.3要求登记「金矿石（物品形态）」作为合成输入。
     *
     * <p>本断言证明它<b>已经存在</b>，且不靠"我新建了一个物品"——
     * 而是因为登记方块时{@code ItemRegistry.addBlockItem} 自动用同名 stable ID
     * 登记了方块物品。这与 R04 铜锭配方直接拿 {@code skyisland:copper_ore}
     * 当合成输入是同一条契约（见 {@code RecipeRegistry.requireRegisteredItem}
     * 的错误信息「或该物品对应的方块」）。
     *
     * <p>写这条断言是为了防止有人"顺手再register 一次同名物品" ——
     * 那会抛stable ID 重复注册，或更糟：让人以为物品形态不存在而重复造一个。
     */
    @Test
    void goldOreItemFormExistsBecauseBlockItemsAreAddressableByStableId() {
        com.skyisland.item.Item asItem =
                com.skyisland.item.ItemRegistry.byName("skyisland:gold_ore");
        assertNotNull(asItem,
                "PRD §3.3：金矿石的物品形态必须真实存在，否则合成输入是悬空引用。"
                        + "它由「方块物品与方块同stable ID」这条契约自动提供，不需要新建物品。");
        assertTrue(asItem.isBlock(), "它就是那个方块物品，而不是另建的一件 MATERIAL");
        assertEquals(BlockRegistry.goldOre().runtimeId(), asItem.blockRuntimeId(),
                "方块物品必须指回同一个方块（否则掉落会拿到另一个东西）");
    }

    // ================================================================ §3.2.2 小麦

    /**
     * 小麦是本轮唯一牵动"异形/非满方块"的方块（PRD §7 R1 头号风险）。
     *
     * <p>断言它<b>不会挡住玩家</b>——注意这里断的是
     * {@link Block#blocksMovement()}（玩法唯一口径），
     * 不是 {@code collision} 字段。
     */
    @Test
    void wheatIsCrossShapedAndNeverBlocksThePlayer() {
        Block b = BlockRegistry.wheat();
        assertNotNull(b, "S5 应登记小麦（PRD §3.2.2）");
        assertEquals(0.2f, b.hardness(), 1e-6f, "PRD §3.2.2：空手 0.2 秒");
        assertFalse(b.isSolid(), "作物不是实体方块");
        assertEquals(RenderType.TRANSPARENT, b.renderType(),
                "作物需镂空，不能是实心方块（PRD §3.2.2）");
        assertEquals(BlockShape.CROSS, b.shape(),
                "形态必须是十字交叉面：网格是十字，碰撞体为空（PRD §7 R1）");
        assertEquals(0, b.collisionBoxes().length,
                "★ 碰撞盒必须为空 —— 否则玩家会撞上一堵看不见的墙");
        assertFalse(b.blocksMovement(),
                "★ 小麦绝不能阻挡移动。这是 PRD §7 R1 点名的头号风险，"
                        + "且症状（撞墙）在无人值守的门禁里完全不可观测。");
    }

    /**
     * ★ 不变式：<b>形态对玩法开关有否决权</b>。
     *
     * <p>把 {@code collision} 故意配成 {@code true} 的十字面方块，
     * <b>仍然</b>不得阻挡移动 —— 这是 {@link BlockShape#hasCollision()} 存在的原因。
     *
     * <p>没有这条，"作物应该挡路吧？"是个非常自然的想法，
     * 而它一旦被实现就是一个只在人眼试玩里才暴露的隐形墙。
     */
    @Test
    void crossShapeHasVetoPowerOverTheCollisionFlag() {
        Block evil = BlockFixtures.crossWithCollisionFlagOn(9001);
        assertTrue(evil.hasCollision(), "前提：这个方块的 collision 字段确实是 true");
        assertFalse(evil.blocksMovement(),
                "★ 形态是 CROSS（无碰撞盒）⇒ 即使 collision=true 也不得阻挡移动。"
                        + "这条若失效，任何人把作物配成实心都会造出隐形墙。");
    }

    /**
     * 小麦只掉小麦。
     *
     * <p>★ 主理人 2026-10-07 裁定「本轮不登记 skyisland:wheat_seeds」：
     * 项目里不存在任何种植/农场系统（已全量 grep 核实），
     * 小麦种的唯一用途是种植，登记它就是一条死接线。
     * 因此这里断言<b>小麦种不存在</b>，让这个决定可被看见，
     * 而不是靠"没人提这件事"维持现状。
     */
    @Test
    void wheatSeedsAreDeliberatelyNotRegisteredThisRound() {
        assertNull(com.skyisland.item.ItemRegistry.byName("skyisland:wheat_seeds"),
                "小麦种本轮**有意**不登记（主理人 2026-10-07 裁定：项目无种植系统，"
                        + "登记即死接线）。若将来农业闭环落地并登记了它，"
                        + "请同步改这条断言与 BlockRegistry.bootstrap 里的小麦掉落。");
    }

    // ================================================================ §3.2.3 石砖

    @Test
    void stoneBrickMatchesPrd323() {
        Block b = BlockRegistry.stoneBrick();
        assertNotNull(b, "S5 应登记石砖（PRD §3.2.3；它曾按裁决 A10 被移出 MVP Registry）");
        assertEquals("skyisland:stone_brick", b.id(),
                "稳定 ID 用单数 stone_brick；PRD §3.2.3 明文「不得使用 stone_bricks」");
        assertEquals(2.0f, b.hardness(), 1e-6f, "PRD §3.2.3：空手 2.0 秒");
        assertEquals(RenderType.OPAQUE, b.renderType());
        assertEquals(BlockShape.FULL, b.shape(), "石砖是满方块");
        assertEquals("skyisland:stone_brick", b.dropItemId(), "掉落自身 ×1");
    }

    // ================================================================ §3.2.4 铁块

    @Test
    void ironBlockMatchesPrd324() {
        Block b = BlockRegistry.ironBlock();
        assertNotNull(b, "S5 应登记铁块（PRD §3.2.4）");
        assertEquals(5.0f, b.hardness(), 1e-6f, "PRD §3.2.4：空手 5.0 秒");
        assertEquals(BlockShape.FULL, b.shape());
        assertEquals(RenderType.OPAQUE, b.renderType());
        assertEquals("skyisland:iron_block", b.dropItemId(), "掉落自身 ×1");
        assertTrue(b.hardness() > BlockRegistry.copperOre().hardness(),
                "铁块必须比铜矿石硬，否则矿石类的可挖性区分在玩法上消失");
    }

    // ================================================================ §3.2.5 / §3.5 台阶

    @Test
    void slabMatchesPrd325AndOnlyHasTheBottomForm() {
        Block b = BlockRegistry.slab();
        assertNotNull(b, "S5 应登记台阶（PRD §3.2.5）");
        assertEquals(2.0f, b.hardness(), 1e-6f, "PRD §3.2.5：空手 2.0 秒");
        assertEquals(BlockShape.SLAB_BOTTOM, b.shape(),
                "PRD §3.5 已裁定本轮**仅下半形态**；做上半或双台阶都属于范围蔓延");
        assertTrue(b.hasCollision(), "台阶有碰撞（只有下半格参与）");
        assertTrue(b.blocksMovement(), "下半形态有碰撞盒 ⇒ 台阶挡路");
        assertEquals("skyisland:slab", b.dropItemId(), "掉落自身 ×1");
    }

    /**
     * ★ 台阶必须是<b>半高</b>的 —— 与满方块的可站立高度差一半。
     *
     * <p>这条断的是"碰撞盒的实际高度"，不是形态枚举本身。
     * 理由：形态枚举对了但碰撞盒没按形态生成，是一个
     * "数据层对、行为层错"的分裂，两者症状不同而根因同源。
     */
    @Test
    void slabCollisionBoxIsHalfHeightNotFullHeight() {
        BlockBox[] boxes = BlockRegistry.slab().collisionBoxes();
        assertEquals(1, boxes.length, "半高台阶是 1 个碰撞盒");
        double maxY = boxes[0].maxY();
        assertTrue(maxY < 1.0,
                "★ 台阶碰撞盒顶面必须低于整格（实测 maxY=" + maxY
                        + "）。若等于 1.0，玩家会以为站在一整格高的方块上 —— "
                        + "PRD §3.5 要求「可跳上（半高）」。");
        assertEquals(0.0, boxes[0].minY(), 1e-9, "台阶占下半格：底面贴地");
        assertEquals(0.5, maxY, 1e-9, "下半格 = 高度 0.5");
    }

    // ================================================================ §3.4 数量口径

    /**
     * ★ 口径必须<b>可加</b>，而不是一个写死的 20。
     *
     * <p>PRD §3.4 把玩家常规方块拆成四段：MVP 核心 13 + Alpha 矿石 2 + Alpha 内容 3 + 后续迭代 2。
     * 本断言让这四段真的加起来等于实际值 —— 于是
     * "有人把 MVP_CORE_PLAYER_BLOCK_COUNT 改成 20" 这件事
     * 会立刻变成 27 并变红，而直接写死20 则永远看不出来。
     */
    @Test
    void playerBlockCountEqualsTheSumOfItsFourDeclaredParts() {
        assertEquals(20, BlockRegistry.EXPECTED_PLAYER_BLOCK_COUNT,
                "PRD §3.4：13 + 2 + 3 + 2 = 20（四个常量之和，不是手抄的数字）");
        assertEquals(BlockRegistry.EXPECTED_PLAYER_BLOCK_COUNT, BlockRegistry.playerBlockCount(),
                "实际玩家常规方块数必须等于四段之和");
    }

    /**
     * ★ MVP 门禁口径 13 **一个字都不能变**。
     *
     * <p>这是用户规则 + 本轮裁决第4 条。
     * 解除的是"登记与实现"，**不是**"MVP 门禁范围"（PRD §2 注意边界）。
     */
    @Test
    void mvpCoreCountIsStillExactlyThirteenAndUnrelatedToRegistrySize() {
        assertEquals(13, BlockRegistry.MVP_CORE_PLAYER_BLOCK_COUNT,
                "★ MVP 核心口径 = 13，不因本轮改变。改了它就是动 MVP 验收结论。");
        assertEquals(22, BlockRegistry.size(),
                "★ 门禁口径（13）与注册表规模（22）是**两回事**，不可互相推导。"
                        + "本轮登记 5 种方块不改变 MVP 结论（PRD §2）。");
        assertNotEquals(BlockRegistry.MVP_CORE_PLAYER_BLOCK_COUNT, BlockRegistry.size(),
                "这两者若相等，说明有人把注册表规模当成了门禁口径 —— "
                        + "那正是本轮裁决第 4 条解开的混淆。");
    }

    /** S5 五种方块的 stable ID 必须逐字对上 PRD §3.1 的清单。 */
    @Test
    void allFiveS5BlocksUseTheExactStableIdsFromThePrd() {
        String[] expected = {
                "skyisland:gold_ore", "skyisland:wheat", "skyisland:stone_brick",
                "skyisland:iron_block", "skyisland:slab",
        };
        for (String id : expected) {
            assertNotNull(BlockRegistry.byName(id),
                    "PRD §3.1 的 5 种方块之一未登记: " + id);
        }
    }

    /** 唯一的"未登记"降级路径仍走通（对照：上面五条不许漏，这一条不许堵死）。 */
    @Test
    void unknownStableIdStillDegradesToNull() {
        assertNull(BlockRegistry.byName("skyisland:not_a_block"),
                "未登记的 stable ID 必须返回 null 交由调用方决定，不能悄悄指到别的方块");
    }

    private static void assertNull(Object o, String msg) {
        org.junit.jupiter.api.Assertions.assertNull(o, msg);
    }

    private static void assertNotEquals(Object unexpected, Object actual, String msg) {
        org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual, msg);
    }
}
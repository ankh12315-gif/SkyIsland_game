package com.skyisland.render.mesh;

import com.skyisland.world.block.Block;

/**
 * 方块 → 纹理数组层号的<b>单点定义</b>（M4-S3，PRD §6.3）。
 *
 * <h2>为什么必须是"单点"且"网格器只读"</h2>
 * PRD §6.3 明文规定：<b>"规则由 {@code BlockTextures} 单点定义，网格器只读，
 * 不许在网格器里 if/else 判方块名"</b>。
 * 理由很实在：三贴图规则（顶 / 侧 / 其余）意味着"同一个方块在不同的面上用不同的层"，
 * 一旦把这个判断写进 {@code ChunkMesher} 的双层循环里，
 * 那个循环就会长出"是不是草方块？原木呢？"的分支，
 * 而它本来应该是干净的"6 个面 × 剔除"。
 *
 * <p>本类只做一件事：给定方块与面，回答"用第几层"。
 * 它<b>不含任何 GL 代码</b>，因此可以被纯 JVM 单测直接覆盖 ——
 * 而"草方块顶面用错成侧图层"这类错误，是<b>画面上极难发现</b>的。
 *
 * <h2>层号分配：为什么按美术规格的清单顺序</h2>
 * 层的编号直接对应 {@code docs/art/BLOCK_TEXTURE_SPEC.md} §7.1 的 24 层清单，
 * <b>不按字母序、不按注册顺序</b>。这样"第 N 层"在美术文档、代码、截图里指的是同一个东西。
 *
 * <h2>★为什么末尾多出两层（26 而非 24）</h2>
 * <ol>
 *   <li>{@link #SYSTEM_CORE} —— {@code resource_core}。
 *       美术规格 §7.1 明确"不覆盖 resource_core，它保持现有纯色渲染，不占贴图层"。
 *       但接入纹理数组后<b>每个片元都会采样</b>，不存在"不采样的方块" ——
 *       若不给它一层，它就会去采第 0 层（石头），变成一块灰石头。
 *       因此必须为它单独开一层。这是规格与新架构的真实冲突，处置见报告。</li>
 *   <li>{@link #NEUTRAL_WHITE} —— 纯白层，给<b>非方块几何</b>（实体 / 粒子 / 曳光 /
 *       裂纹 / 手持物）使用。这些几何走同一个 {@code voxelShader}，因此也必须有一层可采。
 *       用纯白是因为片元算的是 {@code texel.rgb * vColor.rgb}：
 *       {@code texel = (1,1,1)} 时结果<b>恰好等于改动前的 {@code vColor.rgb}</b>，
 *       于是这五类几何的画面<b>逐像素不变</b>。这正是 S3"免费回归检测点"要保护的东西。</li>
 * </ol>
 * 主理人裁定"层数 ≥ 方块数"，26 ≥ 24，满足。
 */
public final class BlockTextureLayers {

    // ============================================================ 美术规格 §7.1 的 24 层

    public static final int STONE = 0;
    public static final int DIRT = 1;
    public static final int GRASS_TOP = 2;
    public static final int GRASS_SIDE = 3;
    public static final int SAND = 4;
    public static final int COBBLESTONE = 5;
    public static final int LOG_SIDE = 6;
    public static final int LOG_TOP = 7;
    public static final int OAK_PLANKS = 8;
    public static final int LEAVES = 9;
    public static final int GLASS = 10;
    public static final int TORCH_FULL = 11;
    public static final int DOOR_SIDE = 12;
    public static final int DOOR_TOP = 13;
    public static final int IRON_ORE = 14;
    public static final int COAL_ORE = 15;
    public static final int COPPER_ORE = 16;
    public static final int CRYSTAL_ORE = 17;
    public static final int GOLD_ORE = 18;
    public static final int STONE_BRICK = 19;
    public static final int IRON_BLOCK = 20;
    public static final int WHEAT = 21;
    public static final int SLAB_TOP = 22;
    public static final int SLAB_SIDE = 23;

    // ============================================================ 追加的两层（非美术规格）

    /**
     * {@code resource_core} 的<b>贴图层</b>（美术规格 §7.1 说它"不占贴图层"，见下方【重要】）。
     *
     * <p>【重要】<b>为什么它必须占层 —— 这与"它是不是系统方块"毫无关系</b>：
     * <b>纹理数组一旦接入，片元着色器里每个片块都会执行
     * {@code texture(uBlockAtlas, vec3(uv, layer))}</b>。
     * "不采样"在这种架构里<b>不存在</b> —— 不给它一层，它就会去采第 0 层（石头），
     * 于是资源核心在世界里显示成<b>一块灰石头</b>。
     *
     * <p>因此这层的存在理由是<b>技术必然</b>（每个方块都要有一层），
     * <b>不是</b>"系统方块可以被玩家获取"。
     *
     * <p><b>不要误读这条注释</b>：{@code resource_core} 依然是
     * {@code placeable = false} 的系统方块，
     * PRD §5.1 1 的"玩家无法通过任何途径获得、不得出现在创造面板"<b>依然有效且未被本轮改动</b>。
     * 贴图层只回答"画它时取哪张图"，<b>不回答"玩家能不能拿到它"</b> ——
     * 这两件事在代码里也必须是两件事：创造面板（S7）筛的是 {@code isPlaceable()}，
     * 不是"有没有贴图层"。
     */
    public static final int SYSTEM_CORE = 24;

    /**
     * 纯白层，供实体 / 粒子 / 裂纹 / 手持物采样。
     *
     * <p><b>与 {@link #SYSTEM_CORE} 同源的原因</b>：那五类几何走<b>同一个</b>
     * {@code voxelShader}，因此贴图接入后它们<b>也会被采样</b>。
     * 让它们写纯白层，于是片元算出的
     * {@code texel.rgb * vColor.rgb} 中 {@code texel.rgb = (1,1,1)}，
     * 结果<b>恰好等于</b>接入前的 {@code vColor.rgb} ——
     * 这五类几何的<b>画面因此逐像素不变</b>。
     *
     * <p>【重要】同上：这一层<b>与"玩家能否获得"无关</b>，
     * 它只是"非方块几何也要有层可采"的技术必然结果。
     */
    public static final int NEUTRAL_WHITE = 25;

    /** 纹理数组的层数。 */
    public static final int LAYER_COUNT = 26;

    /** 未登记映射时使用的兜底层（石头）。
     *
     * <p><b>为什么是石头而不是 0 号常量 {@link #STONE}</b>：
     * 二者数值相同，但语义不同 —— 0 是"石头这层的编号"，
     * 这里是"我们不知道这个方块该用哪层"。分开命名是为了让
     * "兜底生效了"在代码评审时一眼可见。
     */
    public static final int FALLBACK = STONE;

    private BlockTextureLayers() {
    }

    /**
     * 该方块<b>指定面</b>应使用的层号。
     *
     * <p>三贴图规则（PRD §6.3）在这里落地：
     * <ul>
     *   <li>顶面（{@link BlockFace#POS_Y}）→ {@code *_top}；</li>
     *   <li>底面（{@link BlockFace#NEG_Y}）→ 多数方块复用侧层，
     *       草方块<b>例外</b>：底面用 {@code dirt}（规格 §3.3 明确"零额外成本"）；</li>
     *   <li>四个侧面 → {@code *_side}。</li>
     * </ul>
     *
     * <p><b>为什么不用 switch 表达式而用 Map</b>：
     * 键是 stable string ID 而不是 {@code Block} 对象，
     * 因为 S5 登记新方块时 {@code Block} 还不存在。
     * 用 stable ID 也让"两个方块共用一层"这件事在表里一眼可见。
     */
    public static int layerFor(Block block, BlockFace face) {
        if (block == null) {
            return FALLBACK;
        }
        // 十字面作物只有一个朝向的层（美术规格 §3.20 裁定两向共用一张）
        if (block.shape().isCross()) {
            return layerForId(block.id(), BlockFace.POS_Y);
        }
        return layerForId(block.id(), face);
    }

    /**
     * <b>★防误读守卫</b>：{@link #SYSTEM_CORE} 这一层的存在<b>不是</b>
     * "系统方块可以被玩家获取"，也<b>不</b>触碰 PRD §5.1 的任何约束。
     *
     * <p><b>为什么需要这条守卫</b>：一个"贴图层"摆在系统方块旁边，
     * 后来人很容易读成"美术给它做了贴图，所以它可以被放进创造面板"——
     * 而创造面板（S7）若真的按"有没有贴图层"来筛，
     * 就会<b>绕过 PRD §5.1.1「资源核心绝不能出现在创造面板」</b>这条硬约束。
     *
     * <p><b>因此把两件事在代码里钉成两件事</b>：
     * 贴图层只回答"画它时取哪张图"，
     * 而"玩家能否获得"由 {@code Block#isPlaceable()} 回答。
     * 本方法断言二者<b>不可混用</b>。
     *
     * @return 恒为 true（断言失败时抛异常）
     */
    public static boolean systemCoreIsStillUnobtainable() {
        Block core = com.skyisland.world.block.BlockRegistry.byName("skyisland:resource_core");
        if (core == null) {
            throw new IllegalStateException(
                    "resource_core 不在注册表里 —— 若它被移除，请同步删除 SYSTEM_CORE 层"
                            + "（否则层号会错位，且没人知道为什么）");
        }
        if (core.isPlaceable()) {
            throw new IllegalStateException(
                    "resource_core 变成可放置了 —— PRD §5.1.1 明文规定它"
                            + "『不可放置、玩家无法通过任何途径获得』。"
                            + "★请注意：这与它有没有贴图层**无关**，"
                            + "贴图层只回答『画它时取哪张图』。");
        }
        return true;
    }

    /**
     * 按 stable ID + 面取层号（<b>网格器与单测的共同入口</b>）。
     *
     * <p>未登记的 ID 会落到 {@link #FALLBACK}，这是<b>有意</b>的：
     * 非方块几何（实体 / 粒子 / 曳光 / 裂纹 / 手持物）走同一个着色器，
     * 也必须有一层可采。兜底保证它<em>看起来</em>没坏。
     *
     * <p>★<b>但兜底掩盖了错误</b>：真实方块忘记登记时，
     * 它会<b>静默渲染成石头</b>（{@link #FALLBACK} 就是 {@link #STONE}），
     * 不报错、不崩，且任何"层号是否合法"的断言都抓不到
     *（{@code isValid(0)} 恒为 true）。
     * <b>要抓这种错误必须问来源而不是问结果</b> ——
     * 用 {@link #isExplicitlyMapped(String)}。
     *
     * @param face 不得为 {@code null}
     */
    public static int layerForId(String stableId, BlockFace face) {
        Integer layer = resolveMapped(stableId, face);
        return layer != null ? layer : FALLBACK;
    }

    /**
     * ★<b>这个 stable ID 是否被显式登记过</b>（"问来源"而非"问结果"）。
     *
     * <p><b>为什么必须单独有这个方法</b>：
     * {@link #layerForId} 对"未登记"和"已登记为石头"返回<b>同一个值</b>
     * （都是 {@link #FALLBACK} == {@link #STONE}），
     * 于是<b>任何基于返回值的断言都恒真</b>，无法区分二者。
     * 症状：S5 登记金矿石时若只改了 {@code BlockRegistry} 而漏改本表，
     * 玩家看到的是"金矿石变成了石头"，而全部门禁都是绿的。
     *
     * <p>因此本方法是 {@link #resolveMapped} 的薄封装，
     * <b>与 {@link #layerForId} 共用同一个 switch</b> ——
     * 这样"哪些 key 被登记了"只有<b>一个</b>真相源，
     * 不存在"switch 加了一张表"导致二者漂移的可能。
     *
     * @param face 不得为 {@code null}
     * @return true 表示该 ID 在本表里有显式条目
     */
    public static boolean isExplicitlyMapped(String stableId, BlockFace face) {
        return resolveMapped(stableId, face) != null;
    }

    /**
     * 映射表本体 —— <b>唯一的真相源</b>。
     *
     * <p>★<b>{@code null} 表示"本表没有这个 key"</b>，这是本设计的核心约定：
     * 它让"未登记"成为一个<b>可判定的事实</b>，
     * 而不是被 {@link #FALLBACK} 抹平成"石头"。
     *
     * <p>用 {@link Integer} 而非 {@code int} 正是为了让 {@code default -> null} 成立
     * （switch 表达式推导不出 {@code null} 的 int 目标类型）。
     */
    private static Integer resolveMapped(String stableId, BlockFace face) {
        if (stableId == null) {
            return null;
        }
        return switch (stableId) {
            case "skyisland:stone" -> STONE;
            case "skyisland:dirt" -> DIRT;
            // ★ 草方块：顶 / 侧 / 底三张不同的图（规格 §3.3）
            case "skyisland:grass_block" -> switch (face) {
                case POS_Y -> GRASS_TOP;
                case NEG_Y -> DIRT;
                default -> GRASS_SIDE;
            };
            case "skyisland:sand" -> SAND;
            case "skyisland:cobblestone" -> COBBLESTONE;
            // ★ 原木：顶面是年轮，侧面是树皮（规格 §3.6）
            case "skyisland:log" -> switch (face) {
                case POS_Y, NEG_Y -> LOG_TOP;
                default -> LOG_SIDE;
            };
            case "skyisland:oak_planks" -> OAK_PLANKS;
            case "skyisland:leaves" -> LEAVES;
            case "skyisland:glass" -> GLASS;
            case "skyisland:resource_core" -> SYSTEM_CORE;
            case "skyisland:iron_ore" -> IRON_ORE;
            case "skyisland:coal_ore" -> COAL_ORE;
            case "skyisland:copper_ore" -> COPPER_ORE;
            case "skyisland:crystal_ore" -> CRYSTAL_ORE;
            case "skyisland:torch" -> TORCH_FULL;
            case "skyisland:wooden_door" -> switch (face) {
                case POS_Y, NEG_Y -> DOOR_TOP;
                default -> DOOR_SIDE;
            };
            // ---- 以下 5 种方块在 S5 登记；此处预先给层，使 S5 只需登记不需改映射 ----
            case "skyisland:gold_ore" -> GOLD_ORE;
            case "skyisland:wheat" -> WHEAT;
            case "skyisland:stone_brick" -> STONE_BRICK;
            case "skyisland:iron_block" -> IRON_BLOCK;
            // ★ 台阶：顶面用 slab_top，侧面用 slab_side，底面用 slab_top
            case "skyisland:slab" -> switch (face) {
                case POS_Y -> SLAB_TOP;
                case NEG_Y -> SLAB_TOP;
                default -> SLAB_SIDE;
            };
            // ---- 非方块几何 ----
            case NEUTRAL_WHITE_KEY -> NEUTRAL_WHITE;
            // ★ 本表没有这个 key。**不要**在这里返回某个具体层 ——
            //   那会把"没登记"重新伪装成"登记了"。
            //   返回 null，让 isExplicitlyMapped() 能如实报告"未登记"，
            //   由 layerForId() 在外层决定要不要兜底。
            default -> null;
        };
    }

    /**
     * {@link #NEUTRAL_WHITE} 的"stable ID"形式。
     *
     * <p>它不是一个真方块，只是一个让非方块几何能通过同一张映射表取到层的键。
     * 用常量而非字面量，是为了让"这个键不存在于 {@code BlockRegistry}"这件事有名字。
     */
    public static final String NEUTRAL_WHITE_KEY = "skyisland:_neutral_white";

    /**
     * 层号是否在 {@link #LAYER_COUNT} 范围内。
     *
     * <p>越界层号会让 {@code texture()} 采样未定义内容 ——
     * 在多数驱动上表现为<b>黑块或随机色块</b>，不报任何错。
     * 因此这个检查由单测对全表逐条执行。
     */
    public static boolean isValid(int layer) {
        return layer >= 0 && layer < LAYER_COUNT;
    }

    /** 层号是否合法（供 {@code ChunkMesher} 在热路径上做廉价断言）。 */
    public static boolean isValid(int layer, String where) {
        if (!isValid(layer)) {
            throw new IllegalStateException(where + "：层号越界 " + layer
                    + "（合法范围 0.." + (LAYER_COUNT - 1) + "）。"
                    + "越界层号在 GPU 上表现为黑块或随机色，且不报错。");
        }
        return true;
    }

    /**
     * ★<b>这个方块"没有贴图层"是否属于正当豁免</b>。
     *
     * <p><b>为什么需要它</b>：{@code BlockRegistry} 里出现的 stable ID
     * <b>不等于</b>一个方块。至少两类 ID 合法地不需要贴图层：
     * <ul>
     *   <li><b>空气</b> —— 不渲染；</li>
     *   <li><b>非方块物品形态</b>（如 {@code skyisland:coal}、{@code skyisland:crystal}）
     *       —— 它们出现在 {@code register(...)} 的<b>掉落物参数位</b>上，是物品 ID 而非方块 ID。</li>
     * </ul>
     * 把豁免写成方法（而不是让单测自己写一份 if），是为了让
     * <b>"谁可以没有贴图层"成为唯一一份定义</b>，
     * 单测与生产代码不会各说各话。
     *
     * @param block 不得为 {@code null}
     * @return true 表示该方块**确实应当**没有贴图层
     */
    public static boolean isExemptFromHavingLayer(com.skyisland.world.block.Block block) {
        if (block.isAir()) {
            return true;
        }
        // ★★ 判据必须是「物品注册表里的这一项**不是方块物品**」，不是「能不能查到」。
        //
        // 【踩过的坑 / 脆绿灯】本方法原先写的是：
        //     return ItemRegistry.byName(blockId) != null;
        // 而 ItemRegistry.addBlockItem 用 block.id() 本身作 stable ID 登记方块物品，
        // 于是 byName 对**每一个方块**都非 null —— 豁免率 100%，
        // 「每个方块都显式登记了贴图层」这条守卫内层循环一次都不执行，
        // 恒真、恒绿，且它注释里点名的那个故障（S5 漏改 layerForId → 矿石变石头）
        // 它自己一个都抓不到。实测证据见 tmp/s5work/findings.md 发现 F1。
        //
        // 【正确判据】方块物品的 Item.block != null ⇒ isBlock() == true；
        // 真正的非方块物品形态（coal / crystal / 小麦种 …）block == null ⇒ isBlock() == false。
        // 只有后者才合法地不需要 per-face 贴图层 —— 因为它根本不参与面渲染。
        com.skyisland.item.Item asItem =
                com.skyisland.item.ItemRegistry.byName(block.id());
        return asItem != null && !asItem.isBlock();
    }
}

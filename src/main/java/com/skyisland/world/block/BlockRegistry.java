package com.skyisland.world.block;

import com.skyisland.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 方块注册表（TECH_DESIGN §F）。
 *
 * <p><b>为什么需要 registry 而不是 {@code enum}：</b>
 * <ul>
 *   <li>存档必须依赖 <b>stable string ID</b>（{@code skyisland:stone}），
 *       enum 常量重命名会让老存档失去含义；</li>
 *   <li>运行时要的是 <b>short</b>（数组下标、顶点数据），enum ordinal 恰好是 int
 *       且会随枚举顺序漂移，把"数据格式"绑在源码行号上；</li>
 *   <li>注册表可以只加载"当前里程碑真正需要的子集"，未登记的 ID 走明确的降级路径，
 *       而 enum 无法表达"这批常量这次不参与"。</li>
 * </ul>
 *
 * <p><b>M1 只登记 First Playable 验证所需的子集。</b>
 * 数据结构与 PRD 的 MVP Block Registry 兼容：扩容只需在 {@code bootstrap()} 里追加，
 * runtimeId 顺延分配，已登记的 ID 数值不变。
 *
 * <p><b>不变式：</b>{@code bootstrap()} 结束时校验"每个 Block 的 runtimeId == 它在列表中的下标"。
 * 这条由代码而非文档保证 —— 注册顺序就是 ID 顺序，不允许出现空洞或错位。
 */
public final class BlockRegistry {

    /** 运行时 ID 的类型上限（short 正数范围，留足扩容空间）。 */
    public static final int MAX_BLOCKS = 1024;

    /** 空气固定占用 runtimeId = 0：{@code short[]} 零初始化即空气，无需额外填充。 */
    public static final short AIR_RUNTIME_ID = 0;

    private static final List<Block> BY_RUNTIME_ID = new ArrayList<>();
    private static final Map<String, Block> BY_STABLE_ID = new HashMap<>();

    private static boolean bootstrapped = false;

    static {
        bootstrap();
    }

    private BlockRegistry() {
    }

    // ============================================================ 注册

    /**
     * 便利重载：<b>掉落自身 ×1</b>。
     *
     * <p>PRD 5.1 的多数方块都是这一行（泥土 / 圆石 / 原木 / 木板 / 沙子 / 火把 / 木门 …），
     * 让它们不必逐条把"我掉我自己"写出来，可以把注意力留给真正会变的几行。
     */
    private static Block register(String stableId,
                                  boolean solid,
                                  boolean transparent,
                                  boolean breakable,
                                  boolean placeable,
                                  boolean collision,
                                  float hardness,
                                  int lightEmission,
                                  RenderType renderType,
                                  int rgb) {
        return register(stableId, solid, transparent, breakable, placeable, collision,
                hardness, lightEmission, renderType, rgb, stableId, 1);
    }

    /**
     * 完整注册：显式给出掉落物（PRD 5.1 的「掉落物」列）。
     *
     * <p><b>这是 G13 的修复点。</b>审计发现 M1 的破坏结算一律
     * {@code inventory.add(自身, 1)}，与 PRD 的「草方块掉泥土 / 石头掉圆石 /
     * 玻璃与树叶无掉落」全数不符 —— 根因正是"掉落规则没有地方可写"。
     * 现在每个方块的掉落都必须在注册时给出，默认值只是"掉落自身"，
     * 凡是与默认不同的行都在 bootstrap 里显式写出，可逐行对照 PRD 核对。
     *
     * @param dropItemId 掉落物 stable ID；{@code null} = 无掉落
     * @param dropCount  掉落数量（{@code dropItemId} 为 null 时被忽略）
     */
    private static Block register(String stableId,
                                  boolean solid,
                                  boolean transparent,
                                  boolean breakable,
                                  boolean placeable,
                                  boolean collision,
                                  float hardness,
                                  int lightEmission,
                                  RenderType renderType,
                                  int rgb,
                                  String dropItemId,
                                  int dropCount) {
        if (bootstrapped) {
            throw new IllegalStateException("注册表已冻结，不允许运行期追加方块: " + stableId);
        }
        if (BY_RUNTIME_ID.size() >= MAX_BLOCKS) {
            throw new IllegalStateException("方块数量超过上限 " + MAX_BLOCKS);
        }
        if (BY_STABLE_ID.containsKey(stableId)) {
            throw new IllegalStateException("stable ID 重复注册: " + stableId);
        }
        int runtimeId = BY_RUNTIME_ID.size();
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        Block block = new Block(runtimeId, stableId, solid, transparent, breakable, placeable,
                collision, hardness, lightEmission, renderType, r, g, b, dropItemId, dropCount);
        BY_RUNTIME_ID.add(block);
        BY_STABLE_ID.put(stableId, block);
        return block;
    }

    /**
     * MVP 注册子集（Pre-M2 Corrective Closure，用户裁决 A10）。
     *
     * <p>硬度口径：徒手破坏所需秒数。矿石类偏硬（≥3 s），土/沙偏软（≈0.5 s），
     * 玻璃刻意做得脆（0.3 s）以便验证透明块在挖掘与网格更新上的行为。
     *
     * <p><b>数量口径（必须与 PRD 5.1 / 5.1.1 对齐）：</b>
     * PRD 的 MVP 方块集是 <b>13 种玩家常规 + 1 种系统方块</b>；
     * 当前 bootstrap 只注册了 <b>8 种玩家常规 + 1 种系统方块</b>（`air` 另占 runtimeId 0）。
     * 差额 5 种（原木 / 树叶 / 铁矿石 / 煤炭矿石 / 火把 / 木门中的其余项）登记在
     * `MVP_REQUIREMENTS_TRACEABILITY.md` 的 G1 缺口，建议最晚 M2 关闭（弹药闭环前置）。
     *
     * <p><b>`skyisland:stone_bricks` 已于本轮移除：</b>PRD 5.1 把石砖标为【Alpha 必须】，
     * PRD 9.4 更把它明文列入"MVP 明确不实现"。它原先挂在 MVP Registry 里属于范围蔓延
     * （MVP_AUDIT 登记的 EARLY/reservation 项之一），
     * 按用户裁决 A10 归 **Alpha / M4**，MVP Registry 不再注册它。
     * 移除后方块总数由 10 降为 <b>9</b>，`BlockRegistryTest` 的期望值同步改为 9。
     */
    private static void bootstrap() {
        // ---- 0：空气（必须第一个注册，占用 runtimeId = 0）----
        register("skyisland:air", false, true, false, false, false,
                0f, 0, RenderType.INVISIBLE, 0x000000);

        // ---- 地形方块 ----
        // 石头：PRD 5.1 —— 空手 1.5 秒，**掉圆石 ×1**（不是掉自身）。
        register("skyisland:stone", true, false, true, true, true,
                1.5f, 0, RenderType.OPAQUE, 0x7F7F7F,
                "skyisland:cobblestone", 1);
        register("skyisland:dirt", true, false, true, true, true,
                0.5f, 0, RenderType.OPAQUE, 0x8B5A2B);
        // 草方块：PRD 5.1 —— 空手 0.6 秒，**掉泥土 ×1**（不是掉自身）。
        register("skyisland:grass_block", true, false, true, true, true,
                0.6f, 0, RenderType.OPAQUE, 0x4C8B3F,
                "skyisland:dirt", 1);
        register("skyisland:sand", true, false, true, true, true,
                0.5f, 0, RenderType.OPAQUE, 0xD9CE8F);
        register("skyisland:cobblestone", true, false, true, true, true,
                2.0f, 0, RenderType.OPAQUE, 0x6E6E6E);
        register("skyisland:oak_planks", true, false, true, true, true,
                2.0f, 0, RenderType.OPAQUE, 0xB08A4A);
        // 注：`skyisland:stone_bricks` 原在此处注册，已按用户裁决 A10 移除（归 Alpha / M4）。

        // ---- 透明方块：用于验证 §G.2 的两个子网格与面剔除规则 ----
        // 玻璃：PRD 5.1 —— **无掉落**（挖掉就没了，不返还玻璃）。
        register("skyisland:glass", true, true, true, true, true,
                0.3f, 0, RenderType.TRANSPARENT, 0xBFE4F0,
                null, 0);

        // ---- 系统方块：不可破坏、有自发光（TECH_DESIGN §F.5）----
        // 不可破坏 = 不会有破坏结算，掉落显式写 null 而不是依赖"反正挖不动"。
        register("skyisland:resource_core", true, false, false, false, true,
                Float.POSITIVE_INFINITY, 15, RenderType.OPAQUE, 0xE8C34A,
                null, 0);

        // ================================================================
        // M2 补齐：PRD 5.1 前 13 行（MVP 玩家常规方块）中此前缺失的 6 种。
        //
        // 审计缺口 G1 / MVP-BLOCK-005/007/009/010/012/013 即为此 6 项。
        // 补齐后 MVP 方块口径达成 PRD 要求的 **13 种玩家常规 + 1 种系统方块**，
        // 一并关闭 M3-GATE 的 MVP-BLOCK-017 / MVP-SCOPE-001（方块数量口径）。
        //
        // 为什么必须放在 M2：铁矿石与煤炭矿石是弹药链的输入物
        // （近战怪掉落铁矿石 40% / 煤炭 30%，PRD 5.5.6），
        // 缺了它们 M2 的战斗就只是"打靶"，打不出资源闭环。
        //
        // 顺序要求：**追加在末尾，不得插队**。已登记方块的 runtimeId 是存档与顶点数据的口径，
        // 中间插入会让其后所有方块整体位移。
        // ================================================================
        register("skyisland:log", true, false, true, true, true,
                2.0f, 0, RenderType.OPAQUE, 0x6B4A2B);
        // 树叶：PRD 5.1 —— 硬度 0.2，**无掉落**（明文不掉落树苗）。
        // 走透明 pass：树叶本就该是半透明的，同时顺带验证"同种透明相邻不生成面"的剔除规则。
        register("skyisland:leaves", true, true, true, true, true,
                0.2f, 0, RenderType.TRANSPARENT, 0x3E7A33,
                null, 0);
        // 铁矿石：PRD 5.1 的「挖掘耗时」列为 3.5 秒（硬度列 3.0 是内部属性，
        // 首版无工具系统，验收以"空手耗时"为准），掉落自身 ×1。
        register("skyisland:iron_ore", true, false, true, true, true,
                3.5f, 0, RenderType.OPAQUE, 0x9A8C7A);
        // 煤炭矿石：PRD 5.1 —— 空手 3.0 秒，**掉煤炭（物品）×1**，不是掉自身。
        // 这是 MVP 第一种"方块掉非方块物品"的情形，也是物品注册表存在的第一个硬理由。
        register("skyisland:coal_ore", true, false, true, true, true,
                3.0f, 0, RenderType.OPAQUE, 0x4A4A4A,
                "skyisland:coal", 1);
        // 火把：PRD 5.1 —— 硬度 0.0（空手 0.1 秒），自发光。
        // M2 占位形态：按普通满方块处理（solid + collision + opaque），只加自发光。
        // 非满方块渲染（细杆 + 火焰贴图）属表现层，登记为 M4 的表现层欠项 ——
        // 在这里先做非满方块会同时牵动网格生成、碰撞体与放置规则三处，收益与风险不成比例。
        register("skyisland:torch", true, false, true, true, true,
                0.1f, 14, RenderType.OPAQUE, 0xFFC24A);
        // 木门：PRD 5.1 —— 硬度 1.0，掉落自身。
        // 右键开关与开启无碰撞是 MVP-BLOCK-016，归属 M3（那需要方块状态，不只是方块类型）。
        register("skyisland:wooden_door", true, false, true, true, true,
                1.0f, 0, RenderType.OPAQUE, 0x8B6334);

        // ================================================================
        // 步枪材料链（主理人 2026-10-02 显式放行；PRD 5.1 标【Alpha 必须】）
        //
        // 为什么加这两块方块：PRD 的配方 R04（铜锭）与 R16（步枪）的输入物是
        // **铜矿石**与**晶体**，而晶体是 {@code skyisland:crystal_ore} 的掉落物。
        // 没有这两块方块，配方表里就会出现指向不存在物品的悬空引用 ——
        // 那正是本项目明令禁止的"死接线"。
        //
        // **代价（必须知道）**：方块物品的 runtimeId 与方块 runtimeId 严格对齐，
        // 且方块物品排在物品表最前面，因此**每多一个方块，其后所有非方块物品的
        // runtimeId 整体 +1**（煤炭 15→17、手枪弹 16→18、手枪 17→19、SMG 18→20）。
        // 这条位移是安全的：PRD 12.3 规定存档只写 stable string ID，运行期不写 runtimeId。
        // 它由 {@code ItemRegistryTest} 的稳定性断言显式钉住，位移必须有人显式改数字。
        //
        // 本轮**不做矿石生成**（范围裁定：数据层 + 合成逻辑）：
        // 两块矿石登记后不会出现在世界里，材料由开局装备发放。
        // 生成器接入是独立的一步，见报告 §边界。
        // ================================================================
        // 铜矿石：PRD 5.1 —— 硬度 3.0（首版无工具系统，验收按"空手耗时"列 3.5 秒），
        // 掉落自身 ×1。颜色取铜锈的偏橙褐，与铁矿石的灰褐（0x9A8C7A）拉开色相。
        register("skyisland:copper_ore", true, false, true, true, true,
                3.5f, 0, RenderType.OPAQUE, 0x9A6B4A);
        // 晶体矿石：PRD 5.1 —— 硬度 5.0（空手 8.0 秒，最硬的可挖方块），
        // **掉晶体（物品）×1**（与煤炭矿石一样是"方块掉非方块物品"）。
        // 颜色取冷青蓝：它是最高阶的枪械材料，视觉上要与两种金属矿明显不同。
        register("skyisland:crystal_ore", true, false, true, true, true,
                8.0f, 0, RenderType.OPAQUE, 0x7FB8C8,
                "skyisland:crystal", 1);

        bootstrapped = true;
        verify();
    }

    /** 校验 runtimeId 与注册下标严格一致 —— 这是"注册顺序即 ID"这一不变式的守门人。 */
    private static void verify() {
        for (int i = 0; i < BY_RUNTIME_ID.size(); i++) {
            Block b = BY_RUNTIME_ID.get(i);
            if (b.runtimeId() != i) {
                throw new IllegalStateException(
                        "runtimeId 与注册下标不一致：index=" + i + " runtimeId=" + b.runtimeId()
                                + " id=" + b.id());
            }
        }
        if (BY_RUNTIME_ID.size() > Short.MAX_VALUE) {
            throw new IllegalStateException("方块数量超过 short 可表示范围");
        }
    }

    // ============================================================ 查询

    /** 空气。 */
    public static Block air() {
        return BY_RUNTIME_ID.get(AIR_RUNTIME_ID);
    }

    public static Block stone() {
        return byName("skyisland:stone");
    }

    public static Block dirt() {
        return byName("skyisland:dirt");
    }

    public static Block grass() {
        return byName("skyisland:grass_block");
    }

    public static Block sand() {
        return byName("skyisland:sand");
    }

    public static Block cobblestone() {
        return byName("skyisland:cobblestone");
    }

    public static Block planks() {
        return byName("skyisland:oak_planks");
    }

    // 注：`stoneBricks()` 取值器已随 `skyisland:stone_bricks` 一并移除（用户裁决 A10，归 Alpha / M4）。
    // 保留一个永远返回 null 的取值器比没有更危险 —— 调用方会拿到 null 再去读属性。
    // M4 重新登记该方块时，在这里一并恢复。

    public static Block glass() {
        return byName("skyisland:glass");
    }

    public static Block resourceCore() {
        return byName("skyisland:resource_core");
    }

    // ---- M2 补齐的 6 种方块（PRD 5.1 前 13 行）----

    public static Block log() {
        return byName("skyisland:log");
    }

    public static Block leaves() {
        return byName("skyisland:leaves");
    }

    public static Block ironOre() {
        return byName("skyisland:iron_ore");
    }

    public static Block coalOre() {
        return byName("skyisland:coal_ore");
    }

    public static Block torch() {
        return byName("skyisland:torch");
    }

    public static Block woodenDoor() {
        return byName("skyisland:wooden_door");
    }

    // ---- 步枪材料链新增的 2 种矿石（PRD 5.1【Alpha 必须】，2026-10-02 主理人放行）----

    /** 铜矿石（R04 铜锭的输入物）。 */
    public static Block copperOre() {
        return byName("skyisland:copper_ore");
    }

    /** 晶体矿石（掉落晶体，R16 步枪的稀有输入物）。 */
    public static Block crystalOre() {
        return byName("skyisland:crystal_ore");
    }

    /**
     * PRD 5.1 定义的 <b>MVP 核心玩家常规方块数</b>。
     *
     * <p>它<b>不是</b> {@link #playerBlockCount()} 的当前值 —— 2026-10-02 落地的步枪材料链
     * 追加了 2 种【Alpha 必须】矿石（铜矿石 / 晶体矿石），使当前值升到 15。
     * 把"MVP 核心 13"与"Alpha 追加"分成两个数字，是为了让
     * "PRD 的 MVP 口径没被改动"这件事仍然可断言（见 {@code BlockRegistryTest}）。
     */
    public static final int MVP_CORE_PLAYER_BLOCK_COUNT = 13;

    /** 步枪材料链追加的【Alpha 必须】矿石方块数（铜矿石 + 晶体矿石）。 */
    public static final int ALPHA_ORE_BLOCK_COUNT = 2;

    /**
     * 玩家常规方块数量（PRD 5.1 / 5.1.1 口径）。
     *
     * <p>PRD 的口径原本是 <b>13 种玩家常规方块 + 1 种系统方块</b>；
     * 2026-10-02 追加 2 种 Alpha 矿石后为 <b>15 + 1</b>。
     * 系统方块单独计数、不得当作玩家可用方块（PRD 第 8 章）。
     * 这个方法把口径写成可断言的数字，让"方块数量对不对"不再靠人工数表。
     */
    public static int playerBlockCount() {
        int n = 0;
        for (Block b : BY_RUNTIME_ID) {
            if (b.runtimeId() == AIR_RUNTIME_ID) {
                continue;
            }
            if (!b.isPlaceable()) {
                continue; // 系统方块（资源核心）不可放置，单独计数
            }
            n++;
        }
        return n;
    }

    /** 系统方块数量（PRD 5.1.1）。 */
    public static int systemBlockCount() {
        int n = 0;
        for (Block b : BY_RUNTIME_ID) {
            if (b.runtimeId() != AIR_RUNTIME_ID && !b.isPlaceable()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 按运行时 ID 查询。
     *
     * <p>越界 ID 属于数据损坏（例如存档被手改），**不静默返回空气**：
     * 静默降级会让"存档损坏"表现为"方块凭空消失"，从而无从追查。
     * 因此这里记录一次 WARNING 后返回空气，让程序能继续运行但不掩盖问题。
     */
    public static Block byRuntimeId(int runtimeId) {
        if (runtimeId < 0 || runtimeId >= BY_RUNTIME_ID.size()) {
            Log.noteWarning("BlockRegistry",
                    "非法的 runtimeId=" + runtimeId + "（合法范围 0.." + (BY_RUNTIME_ID.size() - 1) + "），"
                            + "本次按空气处理。这通常意味着存档或顶点数据被破坏。");
            return air();
        }
        return BY_RUNTIME_ID.get(runtimeId);
    }

    /** 按 stable string ID 查询；不存在时返回 {@code null}（由调用方决定是警告还是拒绝）。 */
    public static Block byName(String stableId) {
        return BY_STABLE_ID.get(stableId);
    }

    /** 已注册方块数量。 */
    public static int size() {
        return BY_RUNTIME_ID.size();
    }

    public static List<Block> all() {
        return Collections.unmodifiableList(BY_RUNTIME_ID);
    }

    /** stable ID → runtimeId；用于存档读取（未登记的 ID 返回空气并告警）。 */
    public static short runtimeIdOf(String stableId) {
        Block b = BY_STABLE_ID.get(stableId);
        if (b == null) {
            Log.noteWarning("BlockRegistry", "存档中出现未登记的方块 ID: " + stableId + "，按空气处理。");
            return AIR_RUNTIME_ID;
        }
        return (short) b.runtimeId();
    }
}

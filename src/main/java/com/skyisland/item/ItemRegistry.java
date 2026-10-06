package com.skyisland.item;

import com.skyisland.util.Log;
import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品注册表（TECH_DESIGN §L.1）。M2 引入。
 *
 * <p><b>核心不变式 —— 方块物品的 item runtimeId == block runtimeId。</b>
 * 初始化时按 {@link BlockRegistry#all()} 的顺序逐个生成方块物品，并断言
 * {@code block.runtimeId() == item.runtimeId()}。这条对齐带来两个好处：
 * <ol>
 *   <li>M1 的背包 / 存档 / 自测全部不需要改动：对方块物品，"物品 ID"与"方块 ID"是同一个数；</li>
 *   <li>方块数量扩容时，所有方块物品的 ID 自动跟着走，不会出现"物品表与方块表错位"。</li>
 * </ol>
 * 它由 {@link #verify()} 在构建期校验，一旦违反立刻抛异常而不是静默错位。
 *
 * <p><b>与 {@link BlockRegistry} 的关系是单向的：</b>ItemRegistry 依赖 BlockRegistry，
 * BlockRegistry 不认识 ItemRegistry。方块的掉落物因此用 <b>stable string ID</b> 表达
 * （见 {@link Block#dropItemId()}），而不是持有 {@link Item} ——
 * 否则两个类会互相 static 初始化，产生难以诊断的类初始化死锁。
 */
public final class ItemRegistry {

    /** 空槽固定占用 runtimeId = 0（与空气方块同号，语义一致：都是"这里没有东西"）。 */
    public static final int EMPTY_RUNTIME_ID = 0;

    public static final String PISTOL_AMMO_ID = "skyisland:pistol_ammo";
    public static final String PISTOL_ID = "skyisland:pistol";

    /**
     * SMG（M3 第二把验证枪，v2 §10 / §17 Story 6）。
     *
     * <p><b>只在表尾追加，不插队。</b>它与手枪共用 {@link #PISTOL_AMMO_ID}（v2 §6.1），
     * 因此 M3 不新增弹药 Item、不新增配方。
     */
    public static final String SMG_ID = "skyisland:smg";

    /** 煤炭：煤炭矿石的掉落物（PRD 5.1）。MVP 第一种"方块掉非方块物品"。 */
    public static final String COAL_ID = "skyisland:coal";

    // ============================================================ 步枪材料链（2026-10-02）
    //
    // 主理人显式放行（v2 §18 的"除非主理人后续显式放行"），范围=数据层 + 合成逻辑。
    // v2 §6.2「后续扩展规则」明文要求：真正落地 rifle 时
    //   · skyisland:rifle_ammo 必须注册为 Item；
    //   · 再新增相应 Recipe；
    //   · **禁止只添加字符串标签。**
    // 本组常量与下面的 register(...) 就是照这条规则落地的。

    /** 木棍（R02：木板 ×2 → 木棍 ×4）。R16 步枪的输入物之一。 */
    public static final String STICK_ID = "skyisland:stick";
    /** 铁锭（R03：铁矿石 ×1 + 煤炭 ×1）。R11 / R12 / R16 的输入物。 */
    public static final String IRON_INGOT_ID = "skyisland:iron_ingot";
    /** 铜锭（R04：铜矿石 ×1 + 煤炭 ×1）。PRD 5.6.2：铜定位为进阶金属，只用于进阶枪械。 */
    public static final String COPPER_INGOT_ID = "skyisland:copper_ingot";
    /** 晶体（晶体矿石的掉落物）。PRD：最稀有、最远岛屿才有的最高阶枪械材料。 */
    public static final String CRYSTAL_ID = "skyisland:crystal";
    /** 火药（R06：煤炭 ×2 + 沙子 ×1 → 火药 ×2）。所有弹药的共同输入物。 */
    public static final String GUNPOWDER_ID = "skyisland:gunpowder";
    /** 步枪弹（R12：铁锭 ×1 + 火药 ×2 → 6 发）。v2 §6.2：必须注册为真实 Item。 */
    public static final String RIFLE_AMMO_ID = "skyisland:rifle_ammo";
    /** 步枪（R16：铁锭 ×8 + 铜锭 ×3 + 晶体 ×1 + 火药 ×6 + 木棍 ×2）。 */
    public static final String RIFLE_ID = "skyisland:rifle";

    /** 步枪的 Viewmodel 查表键 —— 必须与手枪 / SMG 都不同（v2 §4.2 第①项）。 */
    public static final String RIFLE_VIEWMODEL_ID = "rifle";
    /** 步枪的 HUD / 背包图标查表键。 */
    public static final String RIFLE_ICON_ID = "rifle";
    /** 步枪的视觉后坐查表键。 */
    public static final String RIFLE_RECOIL_PROFILE_ID = "rifle";

    /**
     * 步枪的枪口前向偏移（格）。
     *
     * <p>三把枪的 muzzleOffset 必须两两不同（v2 §14.6）：手枪 0.55 / SMG 0.72 / 步枪 0.86。
     * 步枪的枪管最长，"火光从更远处冒出"这件事在数据上就看得见。
     */
    public static final double RIFLE_MUZZLE_FORWARD = 0.86;
    /** 步枪的枪口右向偏移（格）。比 SMG（0.24）略内收：长枪管端得更正。 */
    public static final double RIFLE_MUZZLE_RIGHT = 0.22;
    /** 步枪的枪口下向偏移（格）。比手枪（0.12）略低、比 SMG（0.15）略高。 */
    public static final double RIFLE_MUZZLE_DOWN = 0.13;

    /** 弹药的堆叠上限（PRD 5.4.2）。 */
    public static final int AMMO_MAX_STACK = 128;

    /** 默认堆叠上限（PRD 5.1：方块统一 64）。 */
    public static final int DEFAULT_MAX_STACK = 64;

    // ============================================================ 表现规格常量（v2 §4）

    /**
     * 手枪的 Viewmodel 查表键。
     *
     * <p>它指向"手枪轮廓"这一表现资源。当前第一人称渲染层按 {@code ViewmodelKind}
     * （枚举形态）取几何，因此这个键暂时等价于"用 {@code GUN} 形态"；
     * Story 6 引入 SMG 后，渲染层将改为按本键区分两把枪的轮廓。本 Story 只建立接缝，
     * 不改 {@code ViewmodelGeometry} 的几何形状。
     */
    public static final String PISTOL_VIEWMODEL_ID = "pistol";

    /** 手枪的 HUD / 背包图标查表键（当前由 {@code render.ui.ItemIcon} 按 kind 解析）。 */
    public static final String PISTOL_ICON_ID = "pistol";

    /**
     * 手枪的视觉后坐查表键。
     *
     * <p>对应 M2.2 的"每发上抬一个固定角度、随后按 {@code RECOIL_SECONDS} 回落"这一表现。
     * 键而非数值：后坐的幅度/曲线属表现调参，Story 6 起两把枪可以有不同手感。
     */
    public static final String PISTOL_RECOIL_PROFILE_ID = "pistol";

    /** 开火 / 空仓 / 换弹 音效键，与 {@code audio.AudioEvent} 的稳定 id 对齐。 */
    public static final String SOUND_GUN_FIRE = "gun_fire";
    public static final String SOUND_GUN_EMPTY = "gun_empty";
    public static final String SOUND_RELOAD = "reload";

    /**
     * 手枪的枪口前向偏移（格）。<b>必须等于 M2.2 的 {@code SkyIslandGame.MUZZLE_FORWARD}</b>。
     *
     * <p>刻意写成字面量而不是反向依赖 {@code game.SkyIslandGame}（那个类拖入 GLFW/LWJGL，
     * 会让本纯数据类及其单测带上原生依赖）。等价性由测试断言：两者都必须是 0.55。
     */
    public static final double PISTOL_MUZZLE_FORWARD = 0.55;
    /** 手枪的枪口右向偏移（格）。见 {@link #PISTOL_MUZZLE_FORWARD}，对应 {@code MUZZLE_RIGHT}=0.20。 */
    public static final double PISTOL_MUZZLE_RIGHT = 0.20;
    /** 手枪的枪口下向偏移（格）。见 {@link #PISTOL_MUZZLE_FORWARD}，对应 {@code MUZZLE_DOWN}=0.12。 */
    public static final double PISTOL_MUZZLE_DOWN = 0.12;

    // ---- SMG 的表现键（v2 §4.2 第①②③项：轮廓 / 图标 / 枪口位置必须与手枪不同）----

    /**
     * SMG 的 Viewmodel 查表键。
     *
     * <p>与手枪的 {@link #PISTOL_VIEWMODEL_ID} 不同值 —— 这是 v2 §4.2 第①项的判据：
     * 渲染层按本键取轮廓，两把枪因此得到不同的剪影，而不是"逻辑上是 SMG、
     * 右手仍是一模一样的手枪模型"（v2 §4.2 明令禁止）。
     */
    public static final String SMG_VIEWMODEL_ID = "smg";

    /** SMG 的 HUD / 背包图标查表键。两者不同 → 图标可分别绘制（v2 §4.2 第②项）。 */
    public static final String SMG_ICON_ID = "smg";

    /**
     * SMG 的视觉后坐查表键。
     *
     * <p>与手枪不同：SMG 是 10 发/秒的连发枪，若复用同一套"每发上抬固定角"的曲线，
     * 会得到一串几乎连成一条线的抖动。键不同 + 射速不同（10 /s vs 4 /s）
     * 合起来满足 v2 §4.2 第④项的"开火声音或射击节奏表现"（本 Story 采用节奏差异，
     * 音效键仍复用 M2.1 已有事件，见下）。
     */
    public static final String SMG_RECOIL_PROFILE_ID = "smg";

    /**
     * SMG 的枪口前向偏移（格）。
     *
     * <p>比手枪（0.55）更靠前：SMG 的枪管更长，火光应当从更远处冒出。
     * v2 §14.6 要求两把枪的 {@code muzzleOffset} 必须不同 —— 三个分量各自都不同于手枪。
     */
    public static final double SMG_MUZZLE_FORWARD = 0.72;
    /** SMG 的枪口右向偏移（格）。比手枪（0.20）更靠右：SMG 持得更外张。 */
    public static final double SMG_MUZZLE_RIGHT = 0.24;
    /** SMG 的枪口下向偏移（格）。比手枪（0.12）略低：长枪管贴得更低。 */
    public static final double SMG_MUZZLE_DOWN = 0.15;

    /**
     * 手枪的表现规格（v2 §4）。每次调用返回一份等价的新实例。
     *
     * <p>用方法而不是静态常量实例：{@link GunPresentationSpec} 是不可变 record，
     * 但"每把枪的表现集中在一处"这一点用方法表达更自然，且便于将来按参数化构造（Story 6）。
     * record 的 {@code equals} 是逐字段的，因此值相等性不受影响。
     */
    public static GunPresentationSpec pistolPresentation() {
        return new GunPresentationSpec(
                PISTOL_VIEWMODEL_ID,
                PISTOL_ICON_ID,
                PISTOL_MUZZLE_FORWARD,
                PISTOL_MUZZLE_RIGHT,
                PISTOL_MUZZLE_DOWN,
                SOUND_GUN_FIRE,
                SOUND_GUN_EMPTY,
                SOUND_RELOAD,
                PISTOL_RECOIL_PROFILE_ID);
    }

    /**
     * SMG 的表现规格（v2 §4.2）。
     *
     * <p>与 {@link #pistolPresentation()} 的差异就是"玩家能看出换了枪"这件事的数据来源：
     * {@code viewmodelId} 不同（轮廓）、{@code iconId} 不同（图标）、三个 muzzle 分量都不同
     * （火光落点）、{@code recoilProfileId} 不同（后坐手感）。
     *
     * <p><b>音效键刻意与手枪相同</b>（{@code gun_fire} / {@code gun_empty} / {@code reload}）：
     * M3 不新增音频资源，v2 §4.2 第④项的"开火声音<b>或</b>射击节奏表现"由
     * <b>射击节奏</b>满足 —— SMG 是 {@code AUTO} + 10 发/秒，手枪是 {@code SINGLE} + 4 发/秒，
     * 二者在手上的区别是"点一下"与"按住扫一段"，比换一条音效更可感知。
     */
    public static GunPresentationSpec smgPresentation() {
        return new GunPresentationSpec(
                SMG_VIEWMODEL_ID,
                SMG_ICON_ID,
                SMG_MUZZLE_FORWARD,
                SMG_MUZZLE_RIGHT,
                SMG_MUZZLE_DOWN,
                SOUND_GUN_FIRE,
                SOUND_GUN_EMPTY,
                SOUND_RELOAD,
                SMG_RECOIL_PROFILE_ID);
    }

    /**
     * 步枪的表现规格。
     *
     * <p>与手枪 / SMG 的差异（v2 §4.2 第①②③项：轮廓 / 图标 / 枪口位置必须两两不同）：
     * <ul>
     *   <li>{@code viewmodelId = "rifle"} —— 渲染层据此取<b>第三套</b>剪影
     *       （{@code ViewmodelGeometry.RIFLE_PARTS}）。这一条是硬性的：
     *       在该键被加入之前，未知键会<b>静默回退到手枪轮廓</b>，
     *       于是"逻辑是步枪、右手是手枪"——v2 §4.2 明令禁止的那种情况；</li>
     *   <li>{@code iconId = "rifle"} —— 图标层据此画第三套图样；</li>
     *   <li>三个 muzzle 分量与手枪、SMG <b>两两不同</b>（0.86 / 0.22 / 0.13）；</li>
     *   <li>{@code recoilProfileId = "rifle"} —— 与另外两把不同键。</li>
     * </ul>
     *
     * <p><b>音效键仍复用</b>（{@code gun_fire} / {@code gun_empty} / {@code reload}）：
     * 与 SMG 同一条理由 —— 本轮不新增音频资源，v2 §4.2 第④项的
     * "开火声音<b>或</b>射击节奏表现"由<b>射击节奏</b>满足：
     * 步枪是 2.0 发/秒，比手枪（4.0）更慢、比 SMG（10.0）慢得多，
     * 且它是唯一"高伤 + 慢速 + 远射"的组合，手感差异比换一条音效更可感知。
     */
    public static GunPresentationSpec riflePresentation() {
        return new GunPresentationSpec(
                RIFLE_VIEWMODEL_ID,
                RIFLE_ICON_ID,
                RIFLE_MUZZLE_FORWARD,
                RIFLE_MUZZLE_RIGHT,
                RIFLE_MUZZLE_DOWN,
                SOUND_GUN_FIRE,
                SOUND_GUN_EMPTY,
                SOUND_RELOAD,
                RIFLE_RECOIL_PROFILE_ID);
    }

    private static final List<Item> BY_RUNTIME_ID = new ArrayList<>();
    private static final Map<String, Item> BY_STABLE_ID = new HashMap<>();

    private static boolean bootstrapped = false;

    static {
        bootstrap();
    }

    private ItemRegistry() {
    }

    // ============================================================ 注册

    private static void bootstrap() {
        // ---- 0：空槽。它不是"一种物品"，只是槽位为空，因此 maxStack = 0 ----
        Item empty = new Item(EMPTY_RUNTIME_ID, "skyisland:empty", ItemKind.EMPTY, null, 0, null, null);
        BY_RUNTIME_ID.add(empty);
        BY_STABLE_ID.put(empty.id(), empty);

        // ---- 方块物品：runtimeId 与方块严格对齐（air 对应空槽，跳过）----
        for (Block b : BlockRegistry.all()) {
            if (b.runtimeId() == BlockRegistry.AIR_RUNTIME_ID) {
                continue;
            }
            addBlockItem(b);
        }

        // ---- 非方块物品（M2）----
        // 煤炭：煤炭矿石的掉落物（PRD 5.1）。它证明"方块 ≠ 物品"这条等式已经破掉。
        register(COAL_ID, ItemKind.MATERIAL, DEFAULT_MAX_STACK, null);
        // 手枪弹：铁锭 ×1 + 火药 ×1 → 8 发（PRD 5.4.2，v0.3 已由铜锭改铁锭）
        register(PISTOL_AMMO_ID, ItemKind.AMMO, AMMO_MAX_STACK, null);
        // 手枪：伤害 8 / 弹匣 12 / 射速 4.0 发每秒 / 有效射程 32 格（PRD 5.4.1）
        // 换弹 1.2 秒（PRD 5.4.3）。
        // M3 泛化后填全 GunSpec 字段（WEAPON-DOC-001 v2 §3.5）：SINGLE / 单弹丸 / 无散布 /
        // ADS 45° / 移速 ×0.60 / 衰减 0.90 起、最低 0.20 / 弹药 skyisland:pistol_ammo。
        // 这些值必须与 PRD 基线逐值一致 —— 它们是"手枪体验不回退"的验收依据。
        // MVP 为开局装备、不可合成（用户裁决 A2）；配方 R14 归 Alpha。
        //
        // 表现规格（v2 §4）：手枪的 GunPresentationSpec 承载 Viewmodel / 图标 / 枪口 /
        // 音效 / 后坐 的查表键。其 muzzleForward/Right/Down 必须逐值等于 M2.2 的
        // SkyIslandGame.MUZZLE_FORWARD/RIGHT/DOWN（0.55 / 0.20 / 0.12）——
        // 否则"把枪口位置数据化"这一步会顺手把火光位置挪走，构成一次视觉回归。
        // 这些键的取值来自现有表现资源：viewmodel/icon 走"手枪"这一标识，
        // 音效复用 M2.1 已有事件（gun_fire / gun_empty / reload）。
        register(PISTOL_ID, ItemKind.GUN, 1, new GunSpec(
                8, 12, 4.0, 32, 1.2,
                FireMode.SINGLE, 1, 0.0,
                45.0, 0.60, 0.90, 0.20,
                PISTOL_AMMO_ID), pistolPresentation());

        // ---- SMG：M3 第二把枪（v2 §10 武器表逐值照抄；§17 Story 6）----
        // AUTO / 伤害 5 / 弹匣 24 / 10 发每秒 / 有效射程 24 格 / 换弹 1.5 秒 /
        // 单弹丸 / 无散布 / ADS 48° / 移速 ×0.65 / 弹药与手枪共用 skyisland:pistol_ammo。
        //
        // 与手枪的差异是 v2 §10.1 选它的理由：SINGLE vs AUTO、attackPressed vs attackHeld、
        // 不同射速、不同弹匣、不同换弹时间、独立 Viewmodel / 图标、共用真实弹药 Item。
        //
        // falloff 沿用与手枪一致的 0.90 / 0.20：v2 §10 武器表未给 SMG 单独的衰减值，
        // 而 §3.4 要求 falloffPerUnit ∈ (0,1] / falloffFloor ∈ [0,1) —— 复用基线的
        // 0.90/0.20 是唯一有依据的取值（不自行发明未冻结的数值）。
        //
        // ★ 只在表尾追加：手枪的 runtimeId 必须逐值不变（v2 §11.2），
        //   存在性、顺序与"只 +1"由 ItemRegistryTest 断言。
        register(SMG_ID, ItemKind.GUN, 1, new GunSpec(
                5, 24, 10.0, 24, 1.5,
                FireMode.AUTO, 1, 0.0,
                48.0, 0.65, 0.90, 0.20,
                PISTOL_AMMO_ID), smgPresentation());

        // ================================================================
        // 步枪材料链（2026-10-02，主理人显式放行；PRD 5.6.2【Alpha 必须】）
        //
        // 全部**追加在表尾**：已在册物品（尤其手枪 / SMG）的 runtimeId 只受
        // "新增了 2 个方块"这一条影响（见 BlockRegistry 的说明），
        // 不受本组新增影响 —— 它们只是往后面排。
        //
        // 顺序与 PRD 5.6.2 的依赖方向一致：材料 → 弹药 → 枪。
        // 每一条都在 ItemRegistryTest 里对着 PRD 的数值/数量逐值钉住。
        // ================================================================

        // 木棍（R02：木板 ×2 → 木棍 ×4）。PRD 5.1 的材料类，堆叠 64。
        // 它是 R07 火把与 R16 步枪的输入物 —— 因为步枪要它，本轮必须一并登记。
        register(STICK_ID, ItemKind.MATERIAL, DEFAULT_MAX_STACK, null);
        // 铁锭（R03：铁矿石 ×1 + 煤炭 ×1 → 铁锭 ×1）。MVP 就有配方，此前从未登记。
        register(IRON_INGOT_ID, ItemKind.MATERIAL, DEFAULT_MAX_STACK, null);
        // 铜锭（R04：铜矿石 ×1 + 煤炭 ×1 → 铜锭 ×1）。
        // PRD 5.6.2 的口径：铜定位为**进阶金属**，只参与进阶枪械（R15–R17），
        // 不参与基础弹药合成 —— 手枪弹自 v0.3 起改用铁锭。
        register(COPPER_INGOT_ID, ItemKind.MATERIAL, DEFAULT_MAX_STACK, null);
        // 晶体（晶体矿石的掉落物 ×1）。最高阶的稀有材料，来源是最远的晶矿岛。
        register(CRYSTAL_ID, ItemKind.MATERIAL, DEFAULT_MAX_STACK, null);
        // 火药（R06：煤炭 ×2 + 沙子 ×1 → 火药 ×2）。所有弹药的共同输入物。
        register(GUNPOWDER_ID, ItemKind.MATERIAL, DEFAULT_MAX_STACK, null);
        // 步枪弹（R12：铁锭 ×1 + 火药 ×2 → 步枪弹 ×6）。
        // v2 §6.2 明文：落地步枪时 skyisland:rifle_ammo 必须注册为真实 Item，禁止只加字符串标签。
        // 堆叠上限与手枪弹一致（128，PRD 5.4.2）。
        register(RIFLE_AMMO_ID, ItemKind.AMMO, AMMO_MAX_STACK, null);

        // 步枪（R16：铁锭 ×8 + 铜锭 ×3 + 晶体 ×1 + 火药 ×6 + 木棍 ×2 → 步枪 ×1）。
        // PRD 5.4.1 武器表逐值照抄：伤害 14 / 弹匣 10 / 射速 2.0 发每秒 / 有效射程 48 格；
        // 换弹 2.0 秒（PRD 5.4.3）。
        //
        // fireMode = SINGLE（2026-10-03 主理人裁定，**已冻结，不是试玩可调项**）：
        //   同一次裁定冻结的还有 damage 14 / magazine 10 / fireRate 2.0 / range 48 / reload 2.0。
        //   裁定语义：**每次真实 PRIMARY_ACTION press 最多发射 1 发；按住左键不得自动连发。**
        //   因此三枪的开火模式分布是 手枪 SINGLE / SMG AUTO / 步枪 SINGLE ——
        //   它不是"SMG 之外的都叫 SINGLE"，而是两把 SINGLE 与一把 AUTO 在
        //   Input → PlayerIntent → CombatController 上必须各自可证伪
        //  （由 FireModeInputSemanticsTest 承担：同一串 intent 下三把枪的射弹数互不相同）。
        //
        //   ★ 反向钉子：曾经这里写的是 AUTO（"慢射速 AUTO = 按住慢慢喷"），已按裁定改回 SINGLE。
        //     若把它改回 AUTO，`rifleIsSingleShotNotAuto` 与门禁里的步枪射速断言必须变红。
        //
        // ADS = 40° / ×0.55 的取舍（★ 新增、待确认）：
        //   PRD 与 v2 都**没有**给步枪的 ADS 参数（v2 §10 只冻结了手枪 45 / SMG 48）。
        //   这里刻意取与另外两把**都不同**的值，而不是抄手枪的 45 / 0.60 ——
        //   理由正是 2026-10-02 接线轮的血债：注册表里的值一旦等于既有常量，
        //   "读了数据"与"读了常量"在行为上就再也分不出来。
        //   取 40° / ×0.55 后，ADS 的注册表口径变成 {45, 48, 40} 与 {0.60, 0.65, 0.55}，
        //   三个值互不相同 → 这条数据第一次能被**真实内容**端到端证伪（手枪 45° / SMG 48° / 步枪 40°）。
        //   语义上也自洽：最高伤、最远射程的枪，开镜收得最紧、移动代价最大。
        //
        // falloff 沿用基线的 0.90 / 0.20（与 SMG 同一条理由）：PRD 与 v2 均未给步枪的衰减，
        //   不自行发明未冻结的数值。衰减那对参数的可证伪性由合成 spec 的单测承担
        //  （WeaponDataWiringTest），不依赖在册枪种取到不同的值。
        register(RIFLE_ID, ItemKind.GUN, 1, new GunSpec(
                14, 10, 2.0, 48, 2.0,
                FireMode.SINGLE, 1, 0.0,
                40.0, 0.55, 0.90, 0.20,
                RIFLE_AMMO_ID), riflePresentation());

        bootstrapped = true;
        verify();
    }

    private static void addBlockItem(Block block) {
        if (block.runtimeId() != BY_RUNTIME_ID.size()) {
            throw new IllegalStateException(
                    "方块物品对齐失败：方块 " + block + " 的 runtimeId=" + block.runtimeId()
                            + "，但物品表当前长度为 " + BY_RUNTIME_ID.size()
                            + "。方块物品的 runtimeId 必须与方块 runtimeId 严格相等。");
        }
        if (BY_STABLE_ID.containsKey(block.id())) {
            throw new IllegalStateException("物品 stable ID 与已登记 ID 冲突: " + block.id());
        }
        Item item = new Item(block.runtimeId(), block.id(), ItemKind.BLOCK, block,
                DEFAULT_MAX_STACK, null, null);
        BY_RUNTIME_ID.add(item);
        BY_STABLE_ID.put(item.id(), item);
    }

    private static Item register(String stableId, ItemKind kind, int maxStack, GunSpec gun) {
        return register(stableId, kind, maxStack, gun, null);
    }

    private static Item register(String stableId, ItemKind kind, int maxStack, GunSpec gun,
                                 GunPresentationSpec presentation) {
        if (bootstrapped) {
            throw new IllegalStateException("注册表已冻结，不允许运行期追加物品: " + stableId);
        }
        if (BY_STABLE_ID.containsKey(stableId)) {
            throw new IllegalStateException("物品 stable ID 重复注册: " + stableId);
        }
        Item item = new Item(BY_RUNTIME_ID.size(), stableId, kind, null, maxStack, gun, presentation);
        BY_RUNTIME_ID.add(item);
        BY_STABLE_ID.put(stableId, item);
        return item;
    }

    /**
     * 校验两条不变式：
     * <ol>
     *   <li>每个物品的 runtimeId == 它在列表中的下标（无空洞、无错位）；</li>
     *   <li>方块物品与方块一一对应。</li>
     * </ol>
     */
    private static void verify() {
        for (int i = 0; i < BY_RUNTIME_ID.size(); i++) {
            Item it = BY_RUNTIME_ID.get(i);
            if (it.runtimeId() != i) {
                throw new IllegalStateException(
                        "物品 runtimeId 与注册下标不一致：index=" + i + " runtimeId=" + it.runtimeId()
                                + " id=" + it.id());
            }
            if (it.isBlock() && it.blockRuntimeId() != it.runtimeId()) {
                throw new IllegalStateException(
                        "方块物品的 runtimeId 未与方块对齐: item=" + it.id()
                                + " itemRuntimeId=" + it.runtimeId()
                                + " blockRuntimeId=" + it.blockRuntimeId());
            }
        }
    }

    // ============================================================ 查询

    public static Item empty() {
        return BY_RUNTIME_ID.get(EMPTY_RUNTIME_ID);
    }

    public static Item coal() {
        return byName(COAL_ID);
    }

    public static Item pistol() {
        return byName(PISTOL_ID);
    }

    /** SMG（v2 §10 第二把枪）。未注册时返回 {@code null} —— 与 {@link #byName} 同口径。 */
    public static Item smg() {
        return byName(SMG_ID);
    }

    public static Item pistolAmmo() {
        return byName(PISTOL_AMMO_ID);
    }

    // ---- 步枪材料链（2026-10-02）----
    //
    // 与 byName 同口径：未注册时返回 null（而不是抛异常），调用方决定是警告还是拒绝。

    /** 步枪（v2 §6.2 放行后的第三把枪）。 */
    public static Item rifle() {
        return byName(RIFLE_ID);
    }

    /** 步枪弹（v2 §6.2：必须是真实 Item，不是字符串标签）。 */
    public static Item rifleAmmo() {
        return byName(RIFLE_AMMO_ID);
    }

    /** 木棍（R02）。 */
    public static Item stick() {
        return byName(STICK_ID);
    }

    /** 铁锭（R03）。 */
    public static Item ironIngot() {
        return byName(IRON_INGOT_ID);
    }

    /** 铜锭（R04，进阶金属）。 */
    public static Item copperIngot() {
        return byName(COPPER_INGOT_ID);
    }

    /** 晶体（晶体矿石掉落物，最高阶材料）。 */
    public static Item crystal() {
        return byName(CRYSTAL_ID);
    }

    /** 火药（R06，弹药共同输入物）。 */
    public static Item gunpowder() {
        return byName(GUNPOWDER_ID);
    }

    /**
     * 按运行时 ID 查询。
     *
     * <p>与 {@link BlockRegistry#byRuntimeId(int)} 同口径：越界属于数据损坏，
     * 记录 WARNING 后返回空物品，让程序能继续跑但不掩盖问题。
     */
    public static Item byRuntimeId(int runtimeId) {
        if (runtimeId < 0 || runtimeId >= BY_RUNTIME_ID.size()) {
            Log.noteWarning("ItemRegistry",
                    "非法的物品 runtimeId=" + runtimeId + "（合法范围 0.."
                            + (BY_RUNTIME_ID.size() - 1) + "），本次按空物品处理。");
            return empty();
        }
        return BY_RUNTIME_ID.get(runtimeId);
    }

    /** 按 stable string ID 查询；不存在返回 {@code null}。 */
    public static Item byName(String stableId) {
        return BY_STABLE_ID.get(stableId);
    }

    /**
     * 按 stable string ID 查 runtimeId；未登记返回空槽 ID 并告警。
     *
     * <p>存档读取走这条路径：旧存档里的物品 ID 若在本版本已不存在，
     * 得到的是"空槽"而不是"指向另一个物品的错误数值"。
     */
    public static int runtimeIdOf(String stableId) {
        Item it = BY_STABLE_ID.get(stableId);
        if (it == null) {
            Log.noteWarning("ItemRegistry", "存档中出现未登记的物品 ID: " + stableId + "，按空槽处理。");
            return EMPTY_RUNTIME_ID;
        }
        return it.runtimeId();
    }

    /** 某物品的堆叠上限；空槽返回 0。 */
    public static int maxStackOf(int itemRuntimeId) {
        return byRuntimeId(itemRuntimeId).maxStack();
    }

    /** 已登记物品数量（含空槽）。 */
    public static int size() {
        return BY_RUNTIME_ID.size();
    }

    public static List<Item> all() {
        return Collections.unmodifiableList(BY_RUNTIME_ID);
    }
}

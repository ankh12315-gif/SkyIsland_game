package com.skyisland.render.ui;

import com.skyisland.item.Item;
import com.skyisland.item.ItemKind;
import com.skyisland.item.ItemRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * 程序化物品图标（M2.2 视觉语言基座）：<b>不引入任何纹理</b>，
 * 用若干轴对齐矩形拼出可区分的物品图样。
 *
 * <p><b>为什么是"纯数据 + 绘制分离"：</b>{@link #parts(int, float, float, float)} 只返回
 * 一组图元（{@link IconPart}），不含任何 GL 调用；{@link #draw(UiBatch, int, float, float, float, int)}
 * 才把这些图元交给 {@link UiBatch}。于是"五种物品是否肉眼可区分"可以脱离 OpenGL，
 * 在单测里用图元的几何与颜色直接断言（见 {@code ItemIconTest}）。
 *
 * <p><b>坐标口径：</b>{@code parts} 返回的图元坐标是<b>绝对像素</b>，落在
 * {@code (x, y, size, size)} 方框内。几何用"相对比例"定义（归一化到 0..1），
 * 再乘 {@code size} 落到像素 —— 因此与 uiScale 无关：调用方把已经乘过 uiScale 的
 * {@code size} 传进来即可（例如快捷栏里 {@code size = (20 - 6) * uiScale}）。
 *
 * <p><b>可区分性硬要求：</b>石头 / 泥土 / 草方块 / 手枪 / 手枪弹 必须一眼可分。
 * 方块类靠"底色调 + 顶部亮边 + 底部暗边"做出伪 3D 块面；
 * 枪与弹药则用<b>完全不同的形状 + 颜色</b>（横长枪身 vs 竖立弹壳）。
 */
public final class ItemIcon {

    private ItemIcon() {
    }

    /**
     * 一个图元：方框内绝对像素位置 {@code (x, y, w, h)} 与颜色 {@code (r, g, b, a)}。
     *
     * <p>坐标是绝对像素（已由调用方给定的 {@code x, y, size} 定位），颜色分量 0..1。
     */
    public record IconPart(float x, float y, float w, float h, float r, float g, float b, float a) {
    }

    // ============================================================ 对外接口

    /**
     * 该物品在 {@code (x, y, size, size)} 方框内的图元列表（绝对像素）。
     *
     * <p>空槽（{@code itemRuntimeId <= 0} 或对应物品为空）返回<b>空列表</b> ——
     * 调用方据此跳过绘制。
     */
    public static List<IconPart> parts(int itemRuntimeId, float x, float y, float size) {
        List<IconPart> out = new ArrayList<>();
        if (itemRuntimeId <= 0) {
            return out;
        }
        Item item = ItemRegistry.byRuntimeId(itemRuntimeId);
        if (item == null || item.isEmpty()) {
            return out;
        }

        switch (item.kind()) {
            case BLOCK -> blockParts(out, x, y, size, item);
            case GUN -> gunParts(out, x, y, size, item);
            case AMMO -> ammoParts(out, x, y, size);
            // 材料按"具体是哪种材料"分派（M4）：此前所有材料共用一个几何，
            // 单种材料（煤炭）时没问题，一旦有了铁锭/铜锭/晶体/火药/木棍，
            // 快捷栏里就会出现五格一模一样的图标 —— 玩家分不出自己拿的是什么。
            case MATERIAL -> materialParts(out, x, y, size, item);
            default -> blockParts(out, x, y, size, item); // EMPTY 不应出现（已上方拦截），兜底当方块
        }
        return out;
    }

    /** 遍历 {@link #parts} 调 {@link UiBatch#rect(float, float, float, float, float, float, float, float)}。 */
    public static void draw(UiBatch batch, int itemRuntimeId, float x, float y, float size, int scale) {
        for (IconPart p : parts(itemRuntimeId, x, y, size)) {
            batch.rect(p.x(), p.y(), p.w(), p.h(), p.r(), p.g(), p.b(), p.a());
        }
    }

    /**
     * 该物品的主色（rgba），供仍按"单色块"处理物品的老调用方使用。
     *
     * <p>方块用其 {@code Block} 顶点色；非方块按 {@link ItemKind} 取对应代表色。
     */
    public static float[] baseColor(int itemRuntimeId) {
        Item item = ItemRegistry.byRuntimeId(itemRuntimeId);
        if (item == null || item.isEmpty()) {
            return UiTheme.ITEM_MATERIAL_COLOR;
        }
        return switch (item.kind()) {
            case BLOCK -> {
                float r = item.block().colorR();
                float g = item.block().colorG();
                float b = item.block().colorB();
                yield new float[]{r, g, b, 1.0f};
            }
            case GUN -> {
                // 每把枪一个主色：手枪冷灰 / SMG 冷暗绿 / 步枪深胡桃木。
                String icon = iconIdOf(item);
                if (ItemRegistry.SMG_ICON_ID.equals(icon)) {
                    yield SMG_ICON_COLOR;
                }
                if (ItemRegistry.RIFLE_ICON_ID.equals(icon)) {
                    yield RIFLE_ICON_COLOR;
                }
                yield UiTheme.ITEM_GUN_COLOR;
            }
            case AMMO -> UiTheme.ITEM_AMMO_COLOR;
            case MATERIAL -> materialColor(item);
            default -> UiTheme.ITEM_MATERIAL_COLOR;
        };
    }

    /**
     * 材料的主色。
     *
     * <p>煤炭沿用既有的 {@link UiTheme#ITEM_MATERIAL_COLOR}（逐值不变，M2 的断言依赖它）；
     * 新增的 5 种材料各有自己的颜色 —— 手里的材料与快捷栏里的图标是同一个色，
     * 玩家据此认出"我拿的是同一件东西"（与 {@code ViewmodelKind} 的说明同源）。
     */
    private static float[] materialColor(Item item) {
        return switch (item.id()) {
            case ItemRegistry.IRON_INGOT_ID -> IRON_INGOT_COLOR;
            case ItemRegistry.COPPER_INGOT_ID -> COPPER_INGOT_COLOR;
            case ItemRegistry.CRYSTAL_ID -> CRYSTAL_COLOR;
            case ItemRegistry.GUNPOWDER_ID -> GUNPOWDER_COLOR;
            case ItemRegistry.STICK_ID -> STICK_COLOR;
            default -> UiTheme.ITEM_MATERIAL_COLOR;   // 煤炭
        };
    }

    // ============================================================ 图元构造

    /** 往列表追加一个归一化矩形（relX/relY/relW/relH 在 0..1，落在方框内）。 */
    private static void add(List<IconPart> out, float x, float y, float size,
                            float relX, float relY, float relW, float relH,
                            float r, float g, float b, float a) {
        out.add(new IconPart(
                x + relX * size,
                y + relY * size,
                relW * size,
                relH * size,
                r, g, b, a));
    }

    /** 把分量按系数缩放并 clamp 到 0..1（用于亮/暗边）。 */
    private static float shade(float c, float factor) {
        return Math.min(1.0f, Math.max(0.0f, c * factor));
    }

    // ---- 方块：底块 + 顶部亮边(×1.25) + 底部暗边(×0.72) ----

    private static void blockParts(List<IconPart> out, float x, float y, float size, Item item) {
        float r = item.block().colorR();
        float g = item.block().colorG();
        float b = item.block().colorB();
        // 主块（内缩 10%，留出与槽边的间隙）
        add(out, x, y, size, 0.10f, 0.10f, 0.80f, 0.80f, r, g, b, 1.0f);
        // 顶部亮边：伪 3D 受光面
        add(out, x, y, size, 0.10f, 0.10f, 0.80f, 0.16f,
                shade(r, 1.25f), shade(g, 1.25f), shade(b, 1.25f), 1.0f);
        // 底部暗边：伪 3D 背光面
        add(out, x, y, size, 0.10f, 0.74f, 0.80f, 0.16f,
                shade(r, 0.72f), shade(g, 0.72f), shade(b, 0.72f), 1.0f);
    }

    // ---- 枪：按 iconId 分派（v2 §4.2 第②项：两把枪的图标必须肉眼可分）----

    /**
     * 枪械图标：手枪 / SMG / 步枪各走一套几何 + 配色。
     *
     * <p><b>为什么按 {@link Item#presentation()} 的 {@code iconId} 而不是按 kind 分派：</b>
     * kind 只能回答"这是不是枪"，回答不了"是哪把枪"。M2 只有手枪时这没问题，
     * M3 加了 SMG 之后，若两把枪继续共用 {@code gunParts}，快捷栏里就会出现
     * 两个一模一样的图标 —— 玩家看不出自己选的是哪把枪（v2 §4.2 明令禁止）。
     *
     * <p>分派键取 {@code iconId}（而不是 item stable id）：图标是<b>表现资源</b>，
     * 它的解析规则应当与其它表现资源（viewmodel / 音效 / 后坐）一致 ——
     * 都由 {@code GunPresentationSpec} 里的键决定。stable id 是存档权威标识，
     * 不该兼任"用哪张图"的开关。
     *
     * <p>未知 iconId 兜底走手枪几何：宁可画错一把枪，也不要画出一格空白 ——
     * 空白会让玩家以为那一格是空的。与 {@code ViewmodelGeometry.gunParts} 同一条口径：
     * 兜底是防御，不是"可以忘记加分支"的理由 —— 加枪必被覆盖由
     * {@code ItemIconTest} 遍历注册表里每一把枪来保证。
     */
    private static void gunParts(List<IconPart> out, float x, float y, float size, Item item) {
        String icon = iconIdOf(item);
        if (ItemRegistry.SMG_ICON_ID.equals(icon)) {
            smgParts(out, x, y, size);
            return;
        }
        if (ItemRegistry.RIFLE_ICON_ID.equals(icon)) {
            rifleParts(out, x, y, size);
            return;
        }
        pistolParts(out, x, y, size);
    }

    /** 该物品的表现图标键；无 presentation（理论上枪必有）时返回 {@code null}。 */
    private static String iconIdOf(Item item) {
        var pres = item.presentation();
        return pres == null ? null : pres.iconId();
    }

    // ---- 手枪：横长枪身 + 右侧伸出的细枪管 + 浅色高光点（M2 既有几何，逐值不变）----

    private static void pistolParts(List<IconPart> out, float x, float y, float size) {
        float[] body = UiTheme.ITEM_GUN_COLOR;
        // 枪身（深灰，横长条，占中部）
        add(out, x, y, size, 0.12f, 0.46f, 0.60f, 0.18f,
                body[0], body[1], body[2], 1.0f);
        // 枪管（更深的细长条，向右伸出）
        add(out, x, y, size, 0.70f, 0.50f, 0.18f, 0.10f,
                shade(body[0], 0.6f), shade(body[1], 0.6f), shade(body[2], 0.6f), 1.0f);
        // 高光点（浅色，表现金属反光）
        add(out, x, y, size, 0.20f, 0.52f, 0.12f, 0.08f,
                0.70f, 0.72f, 0.78f, 1.0f);
    }

    // ---- SMG：更瘦长的枪身 + 更长的枪管 + 向下凸出的弹匣 + 不同高光位置 ----
    //
    // 相对手枪的四处可辨识差异（都写在图元签名里，由 ItemIconTest 断言不相等）：
    //   ① 枪身更瘦（高 0.14 vs 0.18）且更宽（0.66 vs 0.60）—— 侧影更"长条"；
    //   ② 枪管更长（0.26 vs 0.18）且更细（0.08 vs 0.10）—— 一眼是"长管连发枪"；
    //   ③ 多一个向下突出的弹匣矩形 —— 手枪完全没有这个部件；
    //   ④ 配色偏冷暗绿（连发枪的灰绿），而手枪是冷灰。

    /** SMG 图标主色：冷暗绿 —— 与手枪的冷灰刻意拉开色相，色觉障碍下靠形状也能分。 */
    private static final float[] SMG_ICON_COLOR = {0.28f, 0.34f, 0.30f};

    private static void smgParts(List<IconPart> out, float x, float y, float size) {
        float[] body = SMG_ICON_COLOR;
        // 机匣（冷暗绿，比手枪的枪身更宽更瘦）
        add(out, x, y, size, 0.10f, 0.44f, 0.66f, 0.14f,
                body[0], body[1], body[2], 1.0f);
        // 枪管（更长的细条，向右伸出得更远）
        add(out, x, y, size, 0.72f, 0.47f, 0.26f, 0.08f,
                shade(body[0], 0.6f), shade(body[1], 0.6f), shade(body[2], 0.6f), 1.0f);
        // 弹匣：向枪身<b>下方</b>凸出的一截 —— SMG 的独有剪影特征，手枪没有
        add(out, x, y, size, 0.30f, 0.58f, 0.14f, 0.24f,
                shade(body[0], 0.78f), shade(body[1], 0.78f), shade(body[2], 0.78f), 1.0f);
        // 高光点：位置比手枪更靠右（机匣靠前段），亮度略低
        add(out, x, y, size, 0.48f, 0.46f, 0.14f, 0.06f,
                0.62f, 0.66f, 0.64f, 1.0f);
    }

    // ---- 步枪：最长的枪管 + 机匣上方的瞄准镜 + 向后的枪托 ----
    //
    // 相对手枪与 SMG 的三处可辨识差异（由 ItemIconTest 断言三张图标两两不相等）：
    //   ① 枪管最长（伸到 0.94，SMG 是 0.98 但更细；手枪只到 0.88）—— 见下方具体值；
    //   ② **瞄准镜**：机匣上方的一段凸起矩形 —— 手枪与 SMG 都没有这个部件；
    //   ③ 配色是深胡桃木色（唯一暖色），与手枪的冷灰、SMG 的冷暗绿都不同色相。

    /** 步枪图标主色：深胡桃木 —— 三把枪里唯一偏暖的枪身色。 */
    private static final float[] RIFLE_ICON_COLOR = {0.38f, 0.28f, 0.19f};

    private static void rifleParts(List<IconPart> out, float x, float y, float size) {
        float[] wood = RIFLE_ICON_COLOR;
        // 枪托：从机匣向<b>后</b>下方伸出的一截（暖木色，与机匣同色但更暗）
        add(out, x, y, size, 0.02f, 0.52f, 0.12f, 0.16f,
                shade(wood[0], 0.85f), shade(wood[1], 0.85f), shade(wood[2], 0.85f), 1.0f);
        // 机匣：比手枪更长更厚
        add(out, x, y, size, 0.10f, 0.46f, 0.52f, 0.12f,
                wood[0], wood[1], wood[2], 1.0f);
        // 枪管：三把枪里最长的一条细管
        add(out, x, y, size, 0.62f, 0.49f, 0.32f, 0.06f,
                0.30f, 0.33f, 0.40f, 1.0f);
        // 瞄准镜：机匣<b>上方</b>的凸起 —— 步枪独有
        add(out, x, y, size, 0.24f, 0.34f, 0.22f, 0.12f,
                0.26f, 0.29f, 0.36f, 1.0f);
    }

    // ---- 弹药：竖立金铜弹壳 + 顶部深色弹头 ----

    private static void ammoParts(List<IconPart> out, float x, float y, float size) {
        float[] brass = UiTheme.ITEM_AMMO_COLOR;
        // 弹壳（金铜色，竖条）
        add(out, x, y, size, 0.42f, 0.32f, 0.16f, 0.52f,
                brass[0], brass[1], brass[2], 1.0f);
        // 弹头（深色，顶部一小段）
        add(out, x, y, size, 0.42f, 0.18f, 0.16f, 0.16f,
                shade(brass[0], 0.55f), shade(brass[1], 0.55f), shade(brass[2], 0.55f), 1.0f);
    }

    // ---- 材料：按"具体是哪种材料"分派 ----
    //
    // 在只有煤炭时，所有材料共用一套"近黑底 + 两个灰色高光点"的几何是够的
    // （见下 coalParts，逐值保留）。但 M4 引入 5 种新材料后，共用几何会让
    // 快捷栏里出现五格一模一样的图标 —— 玩家看不出自己拿的是铁锭还是火药。
    //
    // 材料的**分派键是 stable ID 而不是 iconId**：{@code presentation()} 只挂在枪械上
    // （见 Item 的构造不变式），材料没有 iconId 可用。这与
    // {@code Localization.displayName(stableId)} 同源 —— 材料的表现解析一律以 stable ID 为准。

    private static void materialParts(List<IconPart> out, float x, float y, float size, Item item) {
        switch (item.id()) {
            case ItemRegistry.IRON_INGOT_ID -> ingotParts(out, x, y, size, IRON_INGOT_COLOR);
            case ItemRegistry.COPPER_INGOT_ID -> ingotParts(out, x, y, size, COPPER_INGOT_COLOR);
            case ItemRegistry.CRYSTAL_ID -> crystalParts(out, x, y, size);
            case ItemRegistry.GUNPOWDER_ID -> gunpowderParts(out, x, y, size);
            case ItemRegistry.STICK_ID -> stickParts(out, x, y, size);
            default -> coalParts(out, x, y, size);   // 煤炭：M2 既有几何，逐值不变
        }
    }

    // 材料主色（图标与手里的物品同色，见 ViewmodelKind 的说明）。
    private static final float[] IRON_INGOT_COLOR = {0.72f, 0.74f, 0.78f};   // 亮钢灰
    private static final float[] COPPER_INGOT_COLOR = {0.72f, 0.45f, 0.26f}; // 铜橙
    private static final float[] CRYSTAL_COLOR = {0.45f, 0.78f, 0.86f};      // 冷青
    private static final float[] GUNPOWDER_COLOR = {0.30f, 0.30f, 0.33f};    // 深灰
    private static final float[] STICK_COLOR = {0.47f, 0.33f, 0.19f};        // 木褐

    /**
     * 锭的形状：下宽上窄的两段（金属锭的剪影）。
     *
     * <p>铁锭与铜锭共用这条几何、只换颜色 —— 它们是同一种"形制"、
     * 只靠材质区分的物品，这与玩家对"两种锭"的心智一致；
     * 可区分性由颜色承担，{@code ItemIconTest} 会断言两者颜色确实不同。
     */
    private static void ingotParts(List<IconPart> out, float x, float y, float size, float[] color) {
        // 主体（下段，宽）
        add(out, x, y, size, 0.14f, 0.44f, 0.72f, 0.26f,
                color[0], color[1], color[2], 1.0f);
        // 顶面（上段，窄）—— 打出"上表面受光"的伪 3D 感
        add(out, x, y, size, 0.24f, 0.32f, 0.52f, 0.14f,
                shade(color[0], 1.18f), shade(color[1], 1.18f), shade(color[2], 1.18f), 1.0f);
        // 底面暗边
        add(out, x, y, size, 0.14f, 0.62f, 0.72f, 0.10f,
                shade(color[0], 0.72f), shade(color[1], 0.72f), shade(color[2], 0.72f), 1.0f);
    }

    /** 晶体：上下尖、中间宽的宝石剪影（与"锭"完全不同的轮廓）。 */
    private static void crystalParts(List<IconPart> out, float x, float y, float size) {
        float[] c = CRYSTAL_COLOR;
        // 顶部尖端
        add(out, x, y, size, 0.42f, 0.12f, 0.16f, 0.14f,
                shade(c[0], 1.20f), shade(c[1], 1.20f), shade(c[2], 1.20f), 1.0f);
        // 主体（最宽）
        add(out, x, y, size, 0.28f, 0.26f, 0.44f, 0.30f,
                c[0], c[1], c[2], 1.0f);
        // 内高光棱
        add(out, x, y, size, 0.38f, 0.30f, 0.10f, 0.22f,
                0.86f, 0.96f, 1.00f, 1.0f);
        // 底部尖端
        add(out, x, y, size, 0.42f, 0.56f, 0.16f, 0.24f,
                shade(c[0], 0.78f), shade(c[1], 0.78f), shade(c[2], 0.78f), 1.0f);
    }

    /** 火药：一小堆颗粒（三个小块 + 顶上一个）。 */
    private static void gunpowderParts(List<IconPart> out, float x, float y, float size) {
        float[] c = GUNPOWDER_COLOR;
        // 底层三堆
        add(out, x, y, size, 0.16f, 0.56f, 0.22f, 0.22f, c[0], c[1], c[2], 1.0f);
        add(out, x, y, size, 0.40f, 0.54f, 0.22f, 0.26f,
                shade(c[0], 1.10f), shade(c[1], 1.10f), shade(c[2], 1.10f), 1.0f);
        add(out, x, y, size, 0.64f, 0.56f, 0.22f, 0.22f, c[0], c[1], c[2], 1.0f);
        // 顶上的一堆：堆成小丘
        add(out, x, y, size, 0.36f, 0.36f, 0.30f, 0.20f,
                shade(c[0], 0.85f), shade(c[1], 0.85f), shade(c[2], 0.85f), 1.0f);
    }

    /** 木棍：两根细长的褐色木条（竖长条 —— 与所有"块状"材料天然可分）。 */
    private static void stickParts(List<IconPart> out, float x, float y, float size) {
        float[] c = STICK_COLOR;
        add(out, x, y, size, 0.26f, 0.18f, 0.13f, 0.60f,
                c[0], c[1], c[2], 1.0f);
        add(out, x, y, size, 0.28f, 0.20f, 0.04f, 0.56f,
                shade(c[0], 1.25f), shade(c[1], 1.25f), shade(c[2], 1.25f), 1.0f);
        add(out, x, y, size, 0.56f, 0.24f, 0.13f, 0.58f,
                shade(c[0], 0.88f), shade(c[1], 0.88f), shade(c[2], 0.88f), 1.0f);
    }

    /** 煤炭：M2 既有几何（近黑底 + 两个灰色高光点），逐值不变。 */
    private static void coalParts(List<IconPart> out, float x, float y, float size) {
        float[] base = UiTheme.ITEM_MATERIAL_COLOR;
        // 底块
        add(out, x, y, size, 0.12f, 0.12f, 0.76f, 0.76f,
                base[0], base[1], base[2], 1.0f);
        // 高光点 1
        add(out, x, y, size, 0.30f, 0.34f, 0.16f, 0.16f,
                0.50f, 0.50f, 0.54f, 1.0f);
        // 高光点 2
        add(out, x, y, size, 0.52f, 0.52f, 0.18f, 0.18f,
                0.45f, 0.45f, 0.49f, 1.0f);
    }
}

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
            case GUN -> gunParts(out, x, y, size);
            case AMMO -> ammoParts(out, x, y, size);
            case MATERIAL -> materialParts(out, x, y, size);
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
            case GUN -> UiTheme.ITEM_GUN_COLOR;
            case AMMO -> UiTheme.ITEM_AMMO_COLOR;
            case MATERIAL -> UiTheme.ITEM_MATERIAL_COLOR;
            default -> UiTheme.ITEM_MATERIAL_COLOR;
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

    // ---- 枪：横长枪身 + 右侧伸出的细枪管 + 浅色高光点 ----

    private static void gunParts(List<IconPart> out, float x, float y, float size) {
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

    // ---- 材料（煤炭）：近黑底 + 两个灰色高光点（矿石块面感） ----

    private static void materialParts(List<IconPart> out, float x, float y, float size) {
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

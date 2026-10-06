package com.skyisland.render.ui;

import com.skyisland.item.ItemRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ItemIcon} 的护栏 —— <b>"五种物品肉眼可区分"这条硬要求的可执行形式</b>。
 *
 * <h2>为什么必须能脱离 GL 断言</h2>
 * M2 的快捷栏把每个物品都画成<b>一个纯色方块</b>（{@code HudRenderer.iconColor}）：
 * 手枪与手枪弹是同一块灰，石头与泥土也只差一点点亮度。玩家看不出自己手上拿的是什么，
 * 而这件事在"能跑、不崩、帧率正常"的尺度上完全不可见 —— 没有断言会因此变红。
 *
 * <p>因此 {@link ItemIcon#parts} 被设计成纯数据：它返回一组带颜色与几何的矩形，
 * 不碰 GL。于是"两者看起来一样"可以被写成一条关于<b>图元签名</b>的断言：
 * 数量、位置、尺寸、颜色任意一项不同，签名就不同。
 *
 * <h2>它证明到哪一步为止</h2>
 * 它证明两个图标<b>不是同一组图元</b>。它不证明"人一眼就能分辨"——
 * 那需要人。它拦掉的是"两件物品用了同一个图标"这类必然导致误认的实现错误，
 * 以及"改了颜色让两者又变回同一个"的回归。
 */
class ItemIconTest {

    /** 取一个足够大的方框，便于检查归一化比例是否越界。 */
    private static final float SIZE = 40f;

    /** PRD 点名的五种玩家最常看到的物品。 */
    private static final String[] FIVE_ITEMS = {
            "skyisland:stone",
            "skyisland:dirt",
            "skyisland:grass_block",
            ItemRegistry.PISTOL_ID,
            ItemRegistry.PISTOL_AMMO_ID,
    };

    private static int runtimeId(String stableId) {
        int rid = ItemRegistry.runtimeIdOf(stableId);
        assertTrue(rid > 0, "前提：物品必须已注册（否则本测试测的是空图标）: " + stableId);
        return rid;
    }

    /** 图元签名 = 数量 + 每个图元的几何与颜色的固定精度文本。 */
    private static String signature(List<ItemIcon.IconPart> parts) {
        StringBuilder sb = new StringBuilder();
        sb.append(parts.size()).append('|');
        for (ItemIcon.IconPart p : parts) {
            sb.append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f;",
                    p.x(), p.y(), p.w(), p.h(), p.r(), p.g(), p.b(), p.a()));
        }
        return sb.toString();
    }

    // ============================================================ 可区分性

    @Test
    void theFiveMostCommonItemsAllProduceDistinctIcons() {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String id : FIVE_ITEMS) {
            List<ItemIcon.IconPart> parts = ItemIcon.parts(runtimeId(id), 0f, 0f, SIZE);
            assertTrue(!parts.isEmpty(), "图标不得为空: " + id);
            String sig = signature(parts);
            for (Map.Entry<String, String> e : seen.entrySet()) {
                assertNotEquals(e.getValue(), sig,
                        "「" + id + "」与「" + e.getKey() + "」的图标完全一致 —— "
                                + "玩家会看不出手上拿的是哪一件（M2 快捷栏正是这么坏掉的）");
            }
            seen.put(id, sig);
        }
        assertEquals(FIVE_ITEMS.length, seen.size());
    }

    /**
     * 方块类的三件（石头 / 泥土 / 草）必须靠<b>颜色</b>分开，而不是只靠形状。
     *
     * <p>它们都是"底块 + 亮边 + 暗边"三图元，形状必然一致。若颜色也相同，
     * 上面的签名断言会通过（因为颜色分量参与签名）—— 所以这条断言专门盯住
     * "底块颜色互不相同"这件事，让"把石头和泥土调成同一个色"必然变红。
     */
    @Test
    void stoneDirtAndGrassAreToldApartByTheirBaseColour() {
        Map<String, float[]> bases = new LinkedHashMap<>();
        for (String id : new String[]{"skyisland:stone", "skyisland:dirt", "skyisland:grass_block"}) {
            int rid = runtimeId(id);
            List<ItemIcon.IconPart> parts = ItemIcon.parts(rid, 0f, 0f, SIZE);
            // 第一个图元就是底块（见 ItemIcon.blockParts）
            ItemIcon.IconPart base = parts.get(0);
            for (Map.Entry<String, float[]> e : bases.entrySet()) {
                float[] other = e.getValue();
                boolean same = Math.abs(base.r() - other[0]) < 1e-6
                        && Math.abs(base.g() - other[1]) < 1e-6
                        && Math.abs(base.b() - other[2]) < 1e-6;
                assertTrue(!same, "「" + id + "」与「" + e.getKey() + "」的底块颜色相同："
                        + base.r() + "," + base.g() + "," + base.b());
            }
            bases.put(id, new float[]{base.r(), base.g(), base.b()});
        }
    }

    /**
     * 枪与弹药的形状必须真的不同，而不只是"颜色不一样"。
     *
     * <p>判据：枪最宽的那个图元是<b>横</b>的（宽 &gt; 高），弹药最长的那个是<b>竖</b>的
     * （高 &gt; 宽）。这一条挡的是"有人为了省事把弹药也画成一条横线"——
     * 那时两者只剩颜色差异，色觉障碍的玩家就无法分辨。
     */
    @Test
    void theGunIsHorizontalAndTheAmmoIsVertical() {
        ItemIcon.IconPart gun = widest(ItemIcon.parts(runtimeId(ItemRegistry.PISTOL_ID), 0f, 0f, SIZE));
        ItemIcon.IconPart ammo = tallest(
                ItemIcon.parts(runtimeId(ItemRegistry.PISTOL_AMMO_ID), 0f, 0f, SIZE));

        assertTrue(gun.w() > gun.h(),
                "手枪最宽的图元必须是横长条（实际 " + gun.w() + " × " + gun.h() + "）");
        assertTrue(ammo.h() > ammo.w(),
                "弹药最长的图元必须是竖长条（实际 " + ammo.w() + " × " + ammo.h() + "）");
    }

    // ============================================================ 手枪 vs SMG（v2 §4.2 第②项）

    /**
     * 两把枪的图标必须<b>肉眼可分</b>（v2 §4.2 第②项）。
     *
     * <p>判据与"五种物品可区分"同口径：图元签名（数量 + 每个图元的几何与颜色）不得相等。
     * 它拦掉的是最容易发生的实现错误 —— 给 SMG 注册了 item、也配了 {@code iconId}，
     * 但 {@code ItemIcon} 仍然所有枪走同一段 {@code gunParts}，
     * 于是快捷栏里出现两个一模一样的手枪图标。
     */
    @Test
    void thePistolAndTheSmgHaveVisiblyDifferentIcons() {
        List<ItemIcon.IconPart> pistol =
                ItemIcon.parts(runtimeId(ItemRegistry.PISTOL_ID), 0f, 0f, SIZE);
        List<ItemIcon.IconPart> smg = ItemIcon.parts(runtimeId(ItemRegistry.SMG_ID), 0f, 0f, SIZE);

        assertTrue(!pistol.isEmpty(), "手枪的图标不得为空");
        assertTrue(!smg.isEmpty(), "SMG 的图标不得为空（v2 §4.2：不能只有一把枪有图标）");
        assertNotEquals(signature(pistol), signature(smg),
                "手枪与 SMG 的图标完全一致 —— 玩家在快捷栏里看不出自己选的是哪把枪");
    }

    /**
     * 两把枪的图标差异必须<b>同时</b>体现在形状与颜色上，而不是只有其中一处。
     *
     * <p>只差颜色 → 色觉障碍玩家分不出；只差形状 → 在小图标下（快捷栏 14px）差异被吃掉。
     * 两条一起看才说明"换枪这件事在 UI 上是可读的"。
     */
    @Test
    void theTwoGunIconsDifferInBothShapeAndColour() {
        List<ItemIcon.IconPart> pistol =
                ItemIcon.parts(runtimeId(ItemRegistry.PISTOL_ID), 0f, 0f, SIZE);
        List<ItemIcon.IconPart> smg = ItemIcon.parts(runtimeId(ItemRegistry.SMG_ID), 0f, 0f, SIZE);

        assertNotEquals(pistol.size(), smg.size(),
                "两把枪的图元数量不同（SMG 多一个弹匣部件）—— 形状差异的第一层证据");
        assertNotEquals(signatureGeometryOnly(pistol), signatureGeometryOnly(smg),
                "两把枪的图元几何必须不同，而不是只换了颜色");

        float[] pistolBase = ItemIcon.baseColor(runtimeId(ItemRegistry.PISTOL_ID));
        float[] smgBase = ItemIcon.baseColor(runtimeId(ItemRegistry.SMG_ID));
        assertTrue(Math.abs(pistolBase[0] - smgBase[0]) > 1e-3
                        || Math.abs(pistolBase[1] - smgBase[1]) > 1e-3
                        || Math.abs(pistolBase[2] - smgBase[2]) > 1e-3,
                "两把枪的主色必须不同（GUN 分支也要按枪分派，不能都返回同一个灰）");
    }

    /** 只含几何（不含颜色）的签名 —— 用来证明"形状也真的变了"。 */
    private static String signatureGeometryOnly(List<ItemIcon.IconPart> parts) {
        StringBuilder sb = new StringBuilder();
        for (ItemIcon.IconPart p : parts) {
            sb.append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f;", p.x(), p.y(), p.w(), p.h()));
        }
        return sb.toString();
    }

    /** SMG 的图标同样必须落在自己的方框内、且颜色分量合法。 */
    @Test
    void theSmgIconStaysInsideItsBoxWithValidColours() {
        float x = 7f;
        float y = 13f;
        List<ItemIcon.IconPart> parts = ItemIcon.parts(runtimeId(ItemRegistry.SMG_ID), x, y, SIZE);
        assertTrue(!parts.isEmpty());
        for (ItemIcon.IconPart p : parts) {
            assertTrue(p.w() > 0f && p.h() > 0f, "SMG 的图元必须有正的宽高");
            assertTrue(p.x() >= x - 1e-4 && p.x() + p.w() <= x + SIZE + 1e-4,
                    "SMG 的图元横向越界: x=" + p.x() + " w=" + p.w());
            assertTrue(p.y() >= y - 1e-4 && p.y() + p.h() <= y + SIZE + 1e-4,
                    "SMG 的图元纵向越界: y=" + p.y() + " h=" + p.h());
            assertTrue(p.r() >= 0f && p.r() <= 1f && p.g() >= 0f && p.g() <= 1f
                            && p.b() >= 0f && p.b() <= 1f && p.a() >= 0f && p.a() <= 1f,
                    "SMG 的颜色分量必须落在 0..1");
        }
    }

    private static ItemIcon.IconPart widest(List<ItemIcon.IconPart> parts) {
        ItemIcon.IconPart best = parts.get(0);
        for (ItemIcon.IconPart p : parts) {
            if (p.w() > best.w()) {
                best = p;
            }
        }
        return best;
    }

    private static ItemIcon.IconPart tallest(List<ItemIcon.IconPart> parts) {
        ItemIcon.IconPart best = parts.get(0);
        for (ItemIcon.IconPart p : parts) {
            if (p.h() > best.h()) {
                best = p;
            }
        }
        return best;
    }

    // ============================================================ 几何不变量

    @Test
    void everyIconStaysInsideItsOwnBox() {
        for (String id : FIVE_ITEMS) {
            float x = 7f;
            float y = 13f;
            for (ItemIcon.IconPart p : ItemIcon.parts(runtimeId(id), x, y, SIZE)) {
                assertTrue(p.w() > 0f && p.h() > 0f,
                        id + " 的图元必须有正的宽高: " + p.w() + " × " + p.h());
                assertTrue(p.x() >= x - 1e-4 && p.x() + p.w() <= x + SIZE + 1e-4,
                        id + " 的图元横向越界: x=" + p.x() + " w=" + p.w() + "（方框 [" + x + ","
                                + (x + SIZE) + "]）");
                assertTrue(p.y() >= y - 1e-4 && p.y() + p.h() <= y + SIZE + 1e-4,
                        id + " 的图元纵向越界: y=" + p.y() + " h=" + p.h() + "（方框 [" + y + ","
                                + (y + SIZE) + "]）");
                assertTrue(p.r() >= 0f && p.r() <= 1f && p.g() >= 0f && p.g() <= 1f
                                && p.b() >= 0f && p.b() <= 1f && p.a() >= 0f && p.a() <= 1f,
                        id + " 的颜色分量必须落在 0..1");
            }
        }
    }

    @Test
    void theIconScalesWithTheBoxItIsGiven() {
        int rid = runtimeId("skyisland:stone");
        List<ItemIcon.IconPart> small = ItemIcon.parts(rid, 0f, 0f, 20f);
        List<ItemIcon.IconPart> big = ItemIcon.parts(rid, 0f, 0f, 40f);

        assertEquals(small.size(), big.size(), "图元数量不应随尺寸变化");
        for (int i = 0; i < small.size(); i++) {
            assertEquals(small.get(i).w() * 2f, big.get(i).w(), 1e-3f,
                    "尺寸翻倍时图元宽度应当翻倍（i=" + i + "）");
            assertEquals(small.get(i).r(), big.get(i).r(), 1e-6f, "颜色不应随尺寸变化（i=" + i + "）");
        }
    }

    @Test
    void anEmptySlotDrawsNothing() {
        assertTrue(ItemIcon.parts(ItemRegistry.EMPTY_RUNTIME_ID, 0f, 0f, SIZE).isEmpty(),
                "空槽必须返回空列表 —— 调用方据此跳过绘制");
        assertTrue(ItemIcon.parts(-1, 0f, 0f, SIZE).isEmpty(), "非法 runtimeId 必须返回空列表");
        assertTrue(ItemIcon.parts(999_999, 0f, 0f, SIZE).isEmpty(), "未注册的 runtimeId 必须返回空列表");
    }

    @Test
    void baseColorIsAlwaysUsable() {
        for (String id : FIVE_ITEMS) {
            float[] c = ItemIcon.baseColor(runtimeId(id));
            assertEquals(4, c.length);
            assertTrue(c[3] > 0f, id + " 的主色必须不透明");
        }
        float[] fallback = ItemIcon.baseColor(999_999);
        assertEquals(4, fallback.length, "未注册物品也必须给出一份可用的主色（老调用方不能拿到 null）");
    }

    // ============================================================ 步枪与材料（2026-10-02）

    /**
     * 三把枪的图标必须<b>两两不同</b>（v2 §4.2 第②项）。
     *
     * <p>这里刻意同时断言"几何不同"与"主色不同"：只断言签名不同的话，
     * 一个只改了颜色的实现也能通过，而那正是最容易被误认的一种
     * （形状一样、颜色相近，隔着一格就分不出）。
     */
    @Test
    void theThreeGunIconsArePairwiseDistinct() {
        List<ItemIcon.IconPart> pistol = ItemIcon.parts(runtimeId(ItemRegistry.PISTOL_ID), 0f, 0f, SIZE);
        List<ItemIcon.IconPart> smg = ItemIcon.parts(runtimeId(ItemRegistry.SMG_ID), 0f, 0f, SIZE);
        List<ItemIcon.IconPart> rifle = ItemIcon.parts(runtimeId(ItemRegistry.RIFLE_ID), 0f, 0f, SIZE);

        assertTrue(!pistol.isEmpty(), "手枪图标不得为空");
        assertTrue(!smg.isEmpty(), "SMG 图标不得为空");
        assertTrue(!rifle.isEmpty(), "步枪图标不得为空");

        assertNotEquals(signature(pistol), signature(rifle),
                "手枪与步枪的图标完全相同 —— 快捷栏里两把枪会长得一模一样");
        assertNotEquals(signature(smg), signature(rifle), "SMG 与步枪的图标完全相同");
        assertNotEquals(pistol.size(), rifle.size(),
                "步枪的图元数应当与手枪不同（步枪多了瞄准镜那一块）");

        // 主色也必须不同（否则形状相近时就只能靠形状硬分）
        float[] pistolBase = ItemIcon.baseColor(runtimeId(ItemRegistry.PISTOL_ID));
        float[] smgBase = ItemIcon.baseColor(runtimeId(ItemRegistry.SMG_ID));
        float[] rifleBase = ItemIcon.baseColor(runtimeId(ItemRegistry.RIFLE_ID));
        assertTrue(colourDistance(pistolBase, rifleBase) > 0.05f,
                "手枪与步枪的主色太接近，实际：" + java.util.Arrays.toString(rifleBase));
        assertTrue(colourDistance(smgBase, rifleBase) > 0.05f,
                "SMG 与步枪的主色太接近，实际：" + java.util.Arrays.toString(rifleBase));
    }

    /**
     * 六种材料（煤炭 + 新增的 5 种）必须产生<b>六个互不相同</b>的图标。
     *
     * <p>这一条抓的是本次改动前那种实现：所有材料共用一套 {@code materialParts}。
     * 它在"只有煤炭"时完全没问题，一旦有了铁锭/铜锭/晶体/火药/木棍，
     * 快捷栏里就会出现五格一模一样的图标。
     */
    @Test
    void theSixMaterialsAllProduceDistinctIcons() {
        String[] materials = {
                ItemRegistry.COAL_ID,
                ItemRegistry.IRON_INGOT_ID,
                ItemRegistry.COPPER_INGOT_ID,
                ItemRegistry.CRYSTAL_ID,
                ItemRegistry.GUNPOWDER_ID,
                ItemRegistry.STICK_ID};

        Map<String, String> seen = new LinkedHashMap<>();
        for (String id : materials) {
            List<ItemIcon.IconPart> parts = ItemIcon.parts(runtimeId(id), 0f, 0f, SIZE);
            assertTrue(!parts.isEmpty(), "材料图标不得为空: " + id);
            String prev = seen.put(signature(parts), id);
            assertTrue(prev == null, "「" + id + "」与「" + prev + "」的图标完全相同");
        }
        assertEquals(materials.length, seen.size());

        // 铁锭与铜锭共用同一条几何、只换颜色 —— 因此它们的美色必须真的不同。
        float[] iron = ItemIcon.baseColor(runtimeId(ItemRegistry.IRON_INGOT_ID));
        float[] copper = ItemIcon.baseColor(runtimeId(ItemRegistry.COPPER_INGOT_ID));
        assertTrue(colourDistance(iron, copper) > 0.05f,
                "铁锭与铜锭几何相同，主色必须拉开；实际 iron=" + java.util.Arrays.toString(iron)
                        + " copper=" + java.util.Arrays.toString(copper));
    }

    private static float colourDistance(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
    }
}

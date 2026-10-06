package com.skyisland.render.mesh;

import com.skyisland.world.block.Block;
import com.skyisland.world.block.BlockRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方块贴图的<b>像素级</b>测试（M4-S3 / S4，PRD §9.1 判据 6、§6.4）。
 *
 * <h2>为什么能在纯 JVM 里测</h2>
 * {@link BlockTextures} <b>零GL 依赖</b>：它只把字符网格 + 调色板变成 RGBA 数组。
 * GL 上传在 {@link BlockTextureAtlas}，因此贴图内容可以被逐像素检查，
 * 不必起 OpenGL 上下文 —— 这也是把生成与上传<b>分成两个类</b>的主要理由。
 *
 * <h2>判据直接采用美术规格 §6 的自检清单</h2>
 * 规格那张表是美术侧给出的、<b>可直接编码</b>的阈值，
 * 本类逐条实现，避免"工程侧自己拍一套阈值"导致两边标准漂移。
 */
class BlockTexturesTest {

    /** 美术规格 §7.1 的 24 层（层号见 {@link BlockTextureLayers}）。 */
    private static final int[] ART_LAYERS = {
            BlockTextureLayers.STONE, BlockTextureLayers.DIRT,
            BlockTextureLayers.GRASS_TOP, BlockTextureLayers.GRASS_SIDE,
            BlockTextureLayers.SAND, BlockTextureLayers.COBBLESTONE,
            BlockTextureLayers.LOG_SIDE, BlockTextureLayers.LOG_TOP,
            BlockTextureLayers.OAK_PLANKS, BlockTextureLayers.LEAVES,
            BlockTextureLayers.GLASS, BlockTextureLayers.TORCH_FULL,
            BlockTextureLayers.DOOR_SIDE, BlockTextureLayers.DOOR_TOP,
            BlockTextureLayers.IRON_ORE, BlockTextureLayers.COAL_ORE,
            BlockTextureLayers.COPPER_ORE, BlockTextureLayers.CRYSTAL_ORE,
            BlockTextureLayers.GOLD_ORE, BlockTextureLayers.STONE_BRICK,
            BlockTextureLayers.IRON_BLOCK, BlockTextureLayers.WHEAT,
            BlockTextureLayers.SLAB_TOP, BlockTextureLayers.SLAB_SIDE,
    };

    private static float alphaAt(float[] px, int u, int v) {
        return px[(v * BlockTextures.SIZE + u) * 4 + 3];
    }

    private static int argbAt(float[] px, int u, int v) {
        int i = (v * BlockTextures.SIZE + u) * 4;
        return (Math.round(px[i] * 255) << 24) | (Math.round(px[i + 1] * 255) << 16)
                | (Math.round(px[i + 2] * 255) << 8) | Math.round(px[i + 3] * 255);
    }

    // ============================================================ 规格 §6 自检清单

    /** 断言 1：每层尺寸 == 16 × 16（精确）。 */
    @Test
    void everyLayerIsSixteenBySixteen() {
        for (int layer : ART_LAYERS) {
            assertEquals(BlockTextures.PIXELS * 4, BlockTextures.layerRgba(layer).length,
                    "层 " + layer + " 的像素数组长度不对");
        }
        assertEquals(16, BlockTextures.SIZE);
    }

    /**
     * 断言 2：每层<b>非纯色</b>（PRD §6.4）。
     *
     * <p><b>为什么这条是硬要求</b>：一张纯色贴图在屏幕上"看起来正常"，
     * 但它让 mipmap 与截图取证完全失去意义 ——
     * "改了之后画面有变化吗"这个问题对纯色贴图永远答"没有"。
     */
    @Test
    void everyLayerIsNonUniform() {
        for (int layer : ART_LAYERS) {
            assertTrue(BlockTextures.isNonUniform(layer),
                    "层 " + layer + " 是纯色 —— 违反 PRD §6.4「非纯色像素占比 > 0」");
        }
    }

    /** 断言 3：每层至少使用 3 个不同明度档（≥ 3）。 */
    @Test
    void everyLayerUsesAtLeastThreeDistinctTones() {
        for (int layer : ART_LAYERS) {
            int tones = BlockTextures.distinctTones(layer);
            assertTrue(tones >= 3,
                    "层 " + layer + " 只有 " + tones + " 个明度档（要求 ≥ 3）—— "
                            + "明度层次不足会让方块看起来像一张平贴纸");
        }
    }

    /** 断言 4：每层内对比 ≥ 30（最亮-最暗亮度差）。 */
    @Test
    void everyLayerHasEnoughInternalContrast() {
        for (int layer : ART_LAYERS) {
            int contrast = BlockTextures.internalContrast(layer);
            assertTrue(contrast >= 30,
                    "层 " + layer + " 的内对比只有 " + contrast + "（要求 ≥ 30）");
        }
    }

    /**
     * 断言 9（★ 最重要）：<b>生成确定性</b> —— 同一方块连续生成两次逐像素相同。
     *
     * <p>PRD §6.4 明文要求。理由不是洁癖：
     * <b>mipmap 与截图取证都会因此抖动</b> ——
     * 同一场景两次截图 diff 不为零，就无法用"截图是否变化"判断"我改的代码有没有生效"。
     */
    @Test
    void generationIsDeterministic() {
        for (int layer : ART_LAYERS) {
            float[] first = BlockTextures.layerRgba(layer);
            float[] second = BlockTextures.layerRgba(layer);
            assertEquals(first.length, second.length);
            for (int i = 0; i < first.length; i++) {
                assertEquals(first[i], second[i], 0.0f,
                        "层 " + layer + " 第 " + i + " 个 float 两次生成不一致 —— "
                                + "确定性被破坏（是否用了随机数/时间？）");
            }
        }
    }

    /** 断言 10：像素全部落在 [0,1]（超出范围会被 GL 截断，产生渗色）。 */
    @Test
    void allChannelsAreWithinUnitRange() {
        float[] all = BlockTextures.allLayersRgba();
        for (int i = 0; i < all.length; i++) {
            assertTrue(all[i] >= 0f && all[i] <= 1f,
                    "第 " + i + " 个 float = " + all[i] + "，超出 [0,1]");
        }
    }

    // ============================================================ 透明方块（本步核心）

    /**
     * 玻璃：<b>1px 外边框完全不透明</b>（美术规格 §3.9 硬规则 1）。
     *
     * <p>规格给的理由：若整张都半透明，玩家<b>在世界里找不到玻璃</b>。
     */
    @Test
    void glassBorderIsFullyOpaque() {
        float[] glass = BlockTextures.layerRgba(BlockTextureLayers.GLASS);
        for (int i = 0; i < BlockTextures.SIZE; i++) {
            assertEquals(1f, alphaAt(glass, i, 0), 1e-6, "玻璃上边框 (" + i + ",0) 必须完全不透明");
            assertEquals(1f, alphaAt(glass, i, BlockTextures.SIZE - 1), 1e-6,
                    "玻璃下边框 (" + i + ",15) 必须完全不透明");
            assertEquals(1f, alphaAt(glass, 0, i), 1e-6, "玻璃左边框 (0," + i + ") 必须完全不透明");
            assertEquals(1f, alphaAt(glass, BlockTextures.SIZE - 1, i), 1e-6,
                    "玻璃右边框 (15," + i + ") 必须完全不透明");
        }
    }

    /**
     * 玻璃：内部半透 ≈ 0.35（规格 §3.9）。
     *
     * <p>规格强调<b>不是 0</b>：mip 降采样会把边框白色向内渗透，
     * 内部取 0 的话，远处玻璃会变成一团发亮的白雾。
     */
    @Test
    void glassInteriorIsSemiTransparentNotZero() {
        float[] glass = BlockTextures.layerRgba(BlockTextureLayers.GLASS);
        float interior = alphaAt(glass, 8, 8);
        assertEquals(BlockTextures.GLASS_INTERIOR_ALPHA / 255f, interior, 1e-6,
                "玻璃内部 alpha 应为 " + BlockTextures.GLASS_INTERIOR_ALPHA + "/255（约 0.35）");
        assertTrue(interior > 0.05f,
                "玻璃内部 alpha 不得为 0 —— mip 会把边框白色向内渗透成白雾（规格 §3.9规则 2）");
        assertTrue(interior < 0.9f, "玻璃内部必须明显半透，否则玻璃退化成实心浅蓝砖");
    }

    /** 树叶与小麦：镂空像素 alpha = 0（<b>真镂空</b>，与玻璃相反）。 */
    @Test
    void leavesAndWheatHaveRealCutouts() {
        for (int layer : new int[]{BlockTextureLayers.LEAVES, BlockTextureLayers.WHEAT}) {
            float[] px = BlockTextures.layerRgba(layer);
            int cutouts = 0;
            for (int i = 0; i < BlockTextures.PIXELS; i++) {
                if (px[i * 4 + 3] == 0f) {
                    cutouts++;
                }
            }
            assertTrue(cutouts > 0,
                    "层 " + layer + " 没有镂空像素 —— 树叶/小麦必须真镂空，否则是实心方块");
            assertTrue(cutouts < BlockTextures.PIXELS,
                    "层 " + layer + " 全部镂空 —— 那这个方块在世界里根本看不见");
        }
    }

    /**
     * 树叶/小麦的镂空是<b>逐像素</b>的，不是"整块一个 alpha"。
     *
     * <p>这条守的是"透明改造真的做了"：若片元仍用 {@code uAlpha} 单一常数，
     * 这两层的 alpha 数据就<b>完全没被消费</b>，而所有其他断言照样通过。
     */
    @Test
    void cutoutLayersMixTransparentAndOpaquePixels() {
        for (int layer : new int[]{BlockTextureLayers.LEAVES, BlockTextureLayers.WHEAT}) {
            float[] px = BlockTextures.layerRgba(layer);
            boolean hasTransparent = false;
            boolean hasOpaque = false;
            for (int i = 0; i < BlockTextures.PIXELS; i++) {
                float a = px[i * 4 + 3];
                if (a == 0f) {
                    hasTransparent = true;
                } else if (a == 1f) {
                    hasOpaque = true;
                }
            }
            assertTrue(hasTransparent && hasOpaque,
                    "层 " + layer + " 必须同时含透明与不透明像素（逐像素 alpha）");
        }
    }

    // ============================================================ 规格 §6 的像素级一致性

    /**
     * 断言 6：{@code grass_side} 在<b>非草区域</b>与 {@code dirt} 层逐像素相同。
     *
     * <p>规格 §3.3 规则 3 要求。实现上用<b>引用</b>而非复制，使它结构性成立。
     */
    @Test
    void grassSideDirtRegionMatchesDirtLayerExactly() {
        float[] side = BlockTextures.layerRgba(BlockTextureLayers.GRASS_SIDE);
        float[] dirt = BlockTextures.layerRgba(BlockTextureLayers.DIRT);
        int compared = 0;
        for (int u = 0; u < BlockTextures.SIZE; u++) {
            for (int v = 0; v < BlockTextures.SIZE; v++) {
                // ★ 咬边阴影行虽然取的是泥土调色板的d1，
                // 但它**不是**dirt 层的对应像素 —— 规格规则 2 说它取 d1，
                // 而 dirt 层该处可能是别的档位。因此这一行也要排除。
                if (BlockTextures.isGrassPixel(u, v) || BlockTextures.isGrassShadowRow(u, v)) {
                    continue;
                }
                assertEquals(argbAt(dirt, u, v), argbAt(side, u, v),
                        "grass_side 的泥土像素 (" + u + "," + v + ") 与 dirt 层不一致");
                compared++;
            }
        }
        assertTrue(compared > 0, "本测试必须真的比到了一些泥土像素，否则是空断言");
    }

    /** 断言 7：{@code wheat} 层左右严格对称（规格 §3.20 的"两向共用一张"前提）。 */
    @Test
    void wheatLayerIsLeftRightSymmetric() {
        float[] wheat = BlockTextures.layerRgba(BlockTextureLayers.WHEAT);
        for (int v = 0; v < BlockTextures.SIZE; v++) {
            for (int u = 0; u < BlockTextures.SIZE; u++) {
                assertEquals(argbAt(wheat, u, v), argbAt(wheat, BlockTextures.SIZE - 1 - u, v),
                        "小麦层在 (" + u + "," + v + ") 处左右不对称 —— "
                                + "规格 §3.20 裁定两向共用一张贴图，前提正是这张图左右对称");
            }
        }
    }

    /**
     * 草边深度表：规格 §3.3 给出的 16 个值（逐条硬编码，<b>不复用实现</b>）。
     *
     * <p>★ 咬边阴影行<b>不算</b>草像素 —— 规格规则 2 说它取泥土调色板的 {@code d1}，
     * 若把它计入，草边会比规格多一行，而症状只是"草边略厚"。
     */
    @Test
    void grassEdgeDepthTableMatchesTheSpec() {
        int[] specDepth = {4, 3, 4, 2, 5, 3, 4, 3, 2, 4, 5, 3, 4, 2, 5, 4};
        for (int u = 0; u < specDepth.length; u++) {
            int measured = 0;
            for (int v = 0; v < BlockTextures.SIZE; v++) {
                if (BlockTextures.isGrassPixel(u, v)) {
                    measured++;
                } else {
                    break;
                }
            }
            assertEquals(specDepth[u], measured,
                    "第 " + u + " 列的草像素行数应为 " + specDepth[u] + "（规格 §3.3 深度表）");
        }
    }

    // ============================================================ 层映射

    @Test
    void everyRegisteredBlockMapsToAValidLayer() {
        for (Block block : BlockRegistry.all()) {
            for (BlockFace face : BlockFace.values()) {
                int layer = BlockTextureLayers.layerFor(block, face);
                assertTrue(BlockTextureLayers.isValid(layer),
                        block.id() + " 的 " + face + " 面映射到越界层号 " + layer
                                + "（越界层号在 GPU 上表现为黑块且不报错）");
            }
        }
    }

    /**
     * ★<b>本轮新增</b>：每个**应当有贴图层**的方块都必须在映射表里<b>显式登记</b>。
     *
     * <p><b>为什么上面那条 {@code everyRegisteredBlockMapsToAValidLayer} 不够</b>：
     * 它只问"层号是否合法"，而 {@code FALLBACK == STONE == 0}，
     * {@code isValid(0)} <b>恒为 true</b> ⇒ 它对"忘记登记"完全无感。
     * 症状：S5 登记金矿石时若只改 {@code BlockRegistry} 漏改 {@code layerForId}，
     * 玩家看到的是<b>"金矿石变成了石头"</b>，不报错、不崩、门禁全绿。
     *
     * <p><b>所以这里问的是"来源"而不是"结果"</b> ——
     * {@link BlockTextureLayers#isExplicitlyMapped} 直接查映射表有没有这个 key。
     *
     * <p><b>豁免项是显式的</b>（由生产代码 {@code isExemptFromHavingLayer} 定义）：
     * 空气不渲染；非方块物品形态（coal / crystal，它们在 {@code register(...)}
     * 的<b>掉落物参数位</b>上，是物品 ID 而非方块 ID）不参与面渲染。
     * <b>不要把它们当成漏登记</b>，也不要把这份 if 复制到测试里 ——
     * 让生产代码成为"谁可以没有贴图层"的唯一定义。
     */
    @Test
    void everyRealBlockIsExplicitlyMappedNotFallingBack() {
        List<String> unmapped = new ArrayList<>();
        for (Block block : BlockRegistry.all()) {
            if (BlockTextureLayers.isExemptFromHavingLayer(block)) {
                continue;
            }
            for (BlockFace face : BlockFace.values()) {
                if (!BlockTextureLayers.isExplicitlyMapped(block.id(), face)) {
                    unmapped.add(block.id() + " 的 " + face + " 面");
                }
            }
        }
        assertTrue(unmapped.isEmpty(),
                "以下方块在贴图层映射表里**没有显式登记**，会掉进兜底、"
                        + "被静默画成石头（玩家看到的是「矿石变成了石头」）：\n  "
                        + String.join("\n  ", unmapped)
                        + "\n修法：在 BlockTextureLayers.layerForId 的 switch 里给它加 case。"
                        + "★注意不要靠调 isValid 的阈值来「修」这个红 —— "
                        + "兜底是正当的安全网，缺登记才是 bug。");
    }

    /**
     * 兜底的**正当用途**必须仍然走通：非方块几何走同一个着色器，
     * 它需要一个可采的层。这是 {@code isExemptFromHavingLayer} 的对照组 ——
     * 上一条禁止"真方块没登记"，这一条保证"非方块几何能兜底"，两头都不能塌。
     */
    @Test
    void nonBlockGeometryStillGetsALayerThroughTheFallback() {
        int layer = BlockTextureLayers.layerForId(
                BlockTextureLayers.NEUTRAL_WHITE_KEY, BlockFace.POS_Y);
        assertEquals(BlockTextureLayers.NEUTRAL_WHITE, layer,
                "非方块几何（实体/粒子/曳光/裂纹/手持物）必须仍能取到纯白层 —— "
                        + "它走同一个 voxelShader，没层可采就画不出来");
        assertTrue(BlockTextureLayers.isValid(layer),
                "非方块几何拿到的层同样必须在合法范围内");
    }

    /** 三贴图规则（PRD §6.3）：草方块顶/侧/底必须是三个不同的层。 */
    @Test
    void grassBlockUsesThreeDifferentLayersPerFace() {
        var grass = BlockRegistry.grass();
        int top = BlockTextureLayers.layerFor(grass, BlockFace.POS_Y);
        int side = BlockTextureLayers.layerFor(grass, BlockFace.NEG_Z);
        int bottom = BlockTextureLayers.layerFor(grass, BlockFace.NEG_Y);

        assertEquals(BlockTextureLayers.GRASS_TOP, top, "草方块顶面必须是 grass_top");
        assertEquals(BlockTextureLayers.GRASS_SIDE, side, "草方块侧面必须是 grass_side");
        assertEquals(BlockTextureLayers.DIRT, bottom,
                "草方块底面必须复用 dirt（规格 §3.3「零额外成本」）");
        assertNotEquals(top, side, "顶面与侧面不能是同一层 —— 那三贴图规则就没生效");
    }

    /** 原木：顶面是年轮、侧面是树皮。 */
    @Test
    void logUsesDifferentLayersForTopAndSide() {
        var log = BlockRegistry.log();
        assertEquals(BlockTextureLayers.LOG_TOP, BlockTextureLayers.layerFor(log, BlockFace.POS_Y));
        assertEquals(BlockTextureLayers.LOG_SIDE, BlockTextureLayers.layerFor(log, BlockFace.POS_X));
    }

    /**
     * 未登记的 stable ID 会得到一个**合法**的兜底层，而不是抛异常或返回越界值。
     *
     * <p>★<b>本轮改写</b>：原断言只查 {@code isValid}，等于<b>把兜底正当化了</b> ——
     * 它让"忘记登记"和"正常兜底"在测试里长得一样，于是
     * {@link #everyRealBlockIsExplicitlyMappedNotFallingBack} 的红会被这条的绿掩盖。
     * 现在它改为断言"兜底是<b>显式</b>的"：
     * <ol>
     *   <li>它<b>不是</b>被显式登记的（即确实走了兜底路径，而非碰巧登记成石头）；</li>
     *   <li>它拿到的层仍然合法（兜底必须让画面没坏）。</li>
     * </ol>
     * 这样"没登记"就成了**可观察的事实**，而不是被绿光掩盖的状态。
     */
    @Test
    void unknownBlockFallsBackToAValidLayer() {
        int layer = BlockTextureLayers.layerForId("skyisland:does_not_exist", BlockFace.POS_Y);
        assertTrue(BlockTextureLayers.isValid(layer),
                "未知方块必须回落到合法层（得到 " + layer + "）");
        assertFalse(BlockTextureLayers.isExplicitlyMapped("skyisland:does_not_exist", BlockFace.POS_Y),
                "这个 ID 显然没被登记 ⇒ 必须走兜底路径。"
                        + "★若此断言变红，说明它被**误登记**成了某个层 —— "
                        + "那才是要修的问题，别来改这条断言。");
    }

    @Test
    void invalidLayerNumberIsRejectedLoudly() {
        assertFalse(BlockTextureLayers.isValid(-1));
        assertFalse(BlockTextureLayers.isValid(BlockTextureLayers.LAYER_COUNT));
        assertThrows(IllegalStateException.class,
                () -> BlockTextureLayers.isValid(BlockTextureLayers.LAYER_COUNT, "测试"),
                "越界层号必须**抛异常**而不是静默返回 —— GPU 上它表现为黑块且不报错");
    }

    /** 层数必须覆盖所有方块 + 两个追加层（美术规格 §7.1 的 24 + 本实现的 2）。 */
    @Test
    void layerCountCoversArtSpecPlusTwoExtra() {
        assertEquals(26, BlockTextureLayers.LAYER_COUNT,
                "应为美术 24 层 + resource_core + 纯白层");
        for (int layer = 0; layer < BlockTextureLayers.LAYER_COUNT; layer++) {
            assertEquals(BlockTextures.PIXELS * 4,
                    BlockTextures.layerRgba(layer).length,
                    "层 " + layer + " 像素数不对");
        }
    }

    /** 矿石类必须互相可分辨（PRD §9.2「尤其矿石类四种」）。 */
    @Test
    void oreLayersAreDistinguishableFromEachOther() {
        Set<Integer> spots = new HashSet<>();
        int[] ores = {BlockTextureLayers.IRON_ORE, BlockTextureLayers.COAL_ORE,
                BlockTextureLayers.COPPER_ORE, BlockTextureLayers.CRYSTAL_ORE,
                BlockTextureLayers.GOLD_ORE};
        for (int layer : ores) {
            // 取一个"矿点像素"（亮度最高的像素近似矿点/高光核）
            float[] px = BlockTextures.layerRgba(layer);
            int brightest = 0;
            int best = -1;
            for (int i = 0; i < BlockTextures.PIXELS; i++) {
                int lum = Math.round((0.299f * px[i * 4] + 0.587f * px[i * 4 + 1]
                        + 0.114f * px[i * 4 + 2]) * 255);
                if (lum > best) {
                    best = lum;
                    brightest = i;
                }
            }
            spots.add(argbAt(px, brightest % BlockTextures.SIZE, brightest / BlockTextures.SIZE));
        }
        assertEquals(ores.length, spots.size(),
                "五种矿石的高光像素颜色必须互不相同 —— 否则玩家分不出矿石种类");
    }

    // ============================================================ UV

    /** 每面 UV 必须取层内满幅区域 0..1（PRD §6.1），且 4 个角互不相同。 */
    @Test
    void everyFaceUsesFullRangeUv() {
        for (BlockFace face : BlockFace.values()) {
            Set<String> seen = new HashSet<>();
            for (int corner = 0; corner < MeshData.VERTICES_PER_FACE; corner++) {
                float[] uv = face.cornerUv(corner);
                assertEquals(2, uv.length);
                assertTrue(uv[0] == 0f || uv[0] == 1f,
                        face + " 角的 u 必须是 0 或 1（满幅区域），得到 " + uv[0]);
                assertTrue(uv[1] == 0f || uv[1] == 1f,
                        face + " 角的 v 必须是 0 或 1（满幅区域），得到 " + uv[1]);
                seen.add(uv[0] + "," + uv[1]);
            }
            assertEquals(4, seen.size(),
                    face + " 的 4 个角 UV 互不相同 —— 重复意味着贴图被压成一条");
        }
    }

    /** 侧面的 UV 上边必须朝上（v=0 在贴图顶边，规格 §2）——否则草边会挂到底面。 */
    @Test
    void sideFacesHaveGrassSideUpwards() {
        BlockFace side = BlockFace.NEG_Z;
        float[] bottom = side.cornerUv(0);
        float[] top = side.cornerUv(2);
        assertEquals(0f, top[1], 1e-6,
                "侧面的上边界 v 必须是 0（贴图顶边，规格 §2）—— "
                        + "若为 1，草方块的草边会挂在底面");
        assertEquals(1f, bottom[1], 1e-6, "侧面的下边界 v 必须是 1");
    }
}

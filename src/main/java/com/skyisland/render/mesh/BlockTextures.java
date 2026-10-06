package com.skyisland.render.mesh;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 24 张方块贴图的<b>程序化生成</b>（M4-S3 / S4，PRD §6）。
 *
 * <h2>为什么程序化生成而不用图片文件</h2>
 * PRD §6.4 的硬约束：<b>不引入任何外部图片文件</b>。项目至今零资源依赖，
 * 为一个功能破坏构建链不划算。附带收益是"改一行代码就能改一张贴图"，
 * 不需要美术重新导出 20 张 PNG。
 *
 * <h2>★铁律：albedo-only，绝不烘方向光</h2>
 * 引擎的 {@link BlockFace#shade()} 已经提供固定面明暗
 * （顶 1.00 / ±Z 0.85 / ±X 0.65 / 底 0.50）。
 * 若贴图内部再画"左上受光"，顶面会拿到 {@code l1 × 1.00} 过曝、底面拿到 {@code l1 × 0.50} 死黑 ——
 * <b>面明暗被算了两次，立体感变成脏污感</b>。
 * 因此本类只生成纯反照率，唯一的例外是 {@code oak_planks} 每道板的上沿 1px 微亮
 * （美术规格 §1.2 明确：那表达的是板材叠压的<b>物理台阶</b>，不是光源）。
 *
 * <h2>★ 确定性：同一方块每次生成必须逐像素一致</h2>
 * PRD §6.4 明文要求。理由不是"洁癖"：
 * <b>mipmap 与截图取证都会因此抖动</b> —— 同一场景两次截图diff 不为零，
 * 就无法用"截图是否变化"来判断"我改的代码有没有生效"。
 * 本类因此<b>不使用任何随机数 / seed / 当前时间</b>，
 * 图案全部来自规格文档里的定长字符网格 + 固定调色板。
 *
 * <h2>透明方块（本类最需要小心的部分）</h2>
 * 美术规格 §3.9 / §5.3 定了两条<b>相反</b>的规则：
 * <ul>
 *   <li><b>玻璃</b>：1px 外边框 <b>alpha = 255（完全不透明）</b>，内部 alpha ≈ 90（约 0.35）。
 *       理由：整张都半透明的话，玩家<b>在世界里找不到玻璃</b>。
 *       内部不取 0 是因为 mipmap 降采样会把边框白色向内渗透，远处玻璃会变成一团白雾。</li>
 *   <li><b>树叶 / 小麦</b>：镂空像素 <b>alpha = 0（真镂空）</b>。与玻璃相反。</li>
 * </ul>
 * 判定入口是 {@link #alphaFor}，它读每层的 {@code tone0} 语义 ——
 * <b>同一个字符 '0' 在不透明贴图里是最深色，在树叶/小麦里是空气</b>，
 * 由该层的调色板决定，<b>不允许实现时临时猜测</b>（美术规格 §2 原文要求）。
 *
 * <h2>本类零 GL 依赖，因此可被纯 JVM 像素级单测覆盖</h2>
 * 这是刻意的：PRD §9.1 判据 6"20 张贴图逐张非纯色（像素级单测）"
 * 在纯 JVM 里就能验证，不需要 GL 上下文。
 * GL 上传在 {@link BlockTextureAtlas}。
 */
public final class BlockTextures {

    /** 贴图边长（像素）。所有方块一致（PRD §6.1）。 */
    public static final int SIZE = 16;

    /** 每张贴图的像素数。 */
    public static final int PIXELS = SIZE * SIZE;

    /** 玻璃内部的 alpha（0-255）。美术规格 §3.9：约 90，约等于 0.35。 */
    public static final int GLASS_INTERIOR_ALPHA = 90;

    /** 完全不透明 / 完全镂空。 */
    public static final int ALPHA_OPAQUE = 255;

    public static final int ALPHA_CUTOUT = 0;

    /** 未映射到具体贴图的方块使用的纯色（层号 {@link BlockTextureLayers#NEUTRAL_WHITE}）。 */
    private static final int[] NEUTRAL_WHITE_RGBA = {255, 255, 255, 255};

    private BlockTextures() {
    }

    // ============================================================ 对外 API

    /**
     * 生成纹理数组<b>某一层</b>的 RGBA 像素（长度 {@link #PIXELS} × 4，行优先）。
     *
     * @param layer 见 {@link BlockTextureLayers}
     * @return {@code float[PIXELS * 4]}，每像素 RGBA ∈ [0,1]
     */
    public static float[] layerRgba(int layer) {
        float[] out = new float[PIXELS * 4];
        switch (layer) {
            case BlockTextureLayers.STONE -> writeGrid(out, 0, STONE, OPAQUE_STONE, false);
            case BlockTextureLayers.DIRT -> writeGrid(out, 0, DIRT, OPAQUE_DIRT, false);
            case BlockTextureLayers.GRASS_TOP -> writeGrid(out, 0, GRASS_TOP, OPAQUE_GRASS, false);
            case BlockTextureLayers.GRASS_SIDE -> writeGrassSide(out);
            case BlockTextureLayers.SAND -> writeGrid(out, 0, SAND, OPAQUE_SAND, false);
            case BlockTextureLayers.COBBLESTONE -> writeGrid(out, 0, COBBLESTONE, OPAQUE_COBBLE, false);
            case BlockTextureLayers.LOG_SIDE -> writeGrid(out, 0, LOG_SIDE, OPAQUE_LOG, false);
            // ★ 端面必须用 OPAQUE_LOG_TOP（年轮），不是树皮的 OPAQUE_LOG —— 见 OPAQUE_LOG_TOP 的注释
            case BlockTextureLayers.LOG_TOP -> writeGrid(out, 0, LOG_TOP, OPAQUE_LOG_TOP, false);
            case BlockTextureLayers.OAK_PLANKS -> writeGrid(out, 0, OAK_PLANKS, OPAQUE_PLANKS, false);
            case BlockTextureLayers.LEAVES -> writeGrid(out, 0, LEAVES, LEAF_PALETTE, true);
            case BlockTextureLayers.GLASS -> writeGlass(out);
            case BlockTextureLayers.TORCH_FULL -> writeGrid(out, 0, TORCH_FULL, TORCH_PALETTE, true);
            case BlockTextureLayers.DOOR_SIDE -> writeGrid(out, 0, DOOR_SIDE, OPAQUE_DOOR, false);
            case BlockTextureLayers.DOOR_TOP -> writeGrid(out, 0, DOOR_TOP, OPAQUE_DOOR, false);
            case BlockTextureLayers.IRON_ORE -> writeOre(out, ORE_STONE, IRON_SPOT, -1);
            case BlockTextureLayers.COAL_ORE -> writeOre(out, ORE_COAL, COAL_SPOT, -1);
            case BlockTextureLayers.COPPER_ORE -> writeOre(out, ORE_STONE, COPPER_SPOT, -1);
            case BlockTextureLayers.CRYSTAL_ORE -> writeOre(out, ORE_STONE, CRYSTAL_SPOT, CRYSTAL_CORE);
            case BlockTextureLayers.GOLD_ORE -> writeOre(out, ORE_STONE, GOLD_SPOT, GOLD_CORE);
            case BlockTextureLayers.STONE_BRICK -> writeGrid(out, 0, STONE_BRICK, OPAQUE_BRICK, false);
            case BlockTextureLayers.IRON_BLOCK -> writeGrid(out, 0, IRON_BLOCK, OPAQUE_IRON_BLOCK, false);
            case BlockTextureLayers.WHEAT -> writeGrid(out, 0, WHEAT, WHEAT_PALETTE, true);
            case BlockTextureLayers.SLAB_TOP -> writeGrid(out, 0, SLAB_TOP, OPAQUE_BRICK, false);
            case BlockTextureLayers.SLAB_SIDE -> writeGrid(out, 0, SLAB_SIDE, OPAQUE_BRICK, false);
            case BlockTextureLayers.SYSTEM_CORE -> writeSolid(out, SYSTEM_CORE_RGBA);
            case BlockTextureLayers.NEUTRAL_WHITE -> writeSolid(out, NEUTRAL_WHITE_RGBA);
            default -> throw new IllegalArgumentException("未定义的层号: " + layer);
        }
        return out;
    }

    /**
     * 生成整张纹理数组的像素（{@link BlockTextureLayers#LAYER_COUNT} × {@link #PIXELS} × 4）。
     *
     * <p>内存：26 层 × 16×16 × 4 float ≈ 26624 float ≈ 104 KB。
     * 与美术规格 §1.6 的"数十 KB 量级"承诺一致。
     */
    public static float[] allLayersRgba() {
        int layers = BlockTextureLayers.LAYER_COUNT;
        float[] all = new float[layers * PIXELS * 4];
        for (int layer = 0; layer < layers; layer++) {
            float[] src = layerRgba(layer);
            System.arraycopy(src, 0, all, layer * PIXELS * 4, PIXELS * 4);
        }
        return all;
    }

    // ============================================================ 写入器

    /** 整张纯色（仅用于两个非美术规格层）。 */
    private static void writeSolid(float[] out, int[] rgba) {
        for (int i = 0; i < PIXELS; i++) {
            out[i * 4] = rgba[0] / 255f;
            out[i * 4 + 1] = rgba[1] / 255f;
            out[i * 4 + 2] = rgba[2] / 255f;
            out[i * 4 + 3] = rgba[3] / 255f;
        }
    }

    /**
     * 按字符网格 + 调色板写入一张贴图。
     *
     * @param tone0IsCutout {@code true} 表示字符 {@code '0'} 是<b>镂空</b>（树叶 / 小麦 / 火把），
     *                      {@code false} 表示它是最深色
     */
    private static void writeGrid(float[] out, int offsetPixels, String[] grid,
                                  int[][] palette, boolean tone0IsCutout) {
        for (int v = 0; v < SIZE; v++) {
            String row = grid[v];
            for (int u = 0; u < SIZE; u++) {
                int tone = row.charAt(u) - '0';
                if (tone < 0 || tone >= palette.length) {
                    throw new IllegalStateException(
                            "网格字符越界: '" + row.charAt(u) + "'（调色板只有 "
                                    + palette.length + " 档）at (" + u + "," + v + ")");
                }
                int[] c = palette[tone];
                int alpha = alphaFor(tone, c, tone0IsCutout);
                int p = (offsetPixels + v * SIZE + u) * 4;
                out[p] = c[0] / 255f;
                out[p + 1] = c[1] / 255f;
                out[p + 2] = c[2] / 255f;
                out[p + 3] = alpha / 255f;
            }
        }
    }

    /**
     * <b>alpha 判定</b>（透明方块的核心，美术规格 §2 / §3.9）。
     *
     * <p>规则只有两条，靠调色板第4 项（alpha）承载：
     * <ul>
     *   <li>调色板第4 项为 {@link #ALPHA_CUTOUT} → 该档是<b>镂空</b>（树叶 / 小麦 / 火把的 tone0）；</li>
     *   <li>否则一律 {@link #ALPHA_OPAQUE}（含玻璃的半透内芯，
     *       它由 {@link #writeGlass} 单独处理，不走本方法）。</li>
     * </ul>
     * 之所以把 alpha 放进调色板而不是用单独一张"透明表"：
     * <b>让"这个字符是不是空气"与"这个字符什么颜色"写在同一处</b>，
     * 就不会出现"颜色查对了、alpha 忘了改"的分裂。
     */
    private static int alphaFor(int tone, int[] color, boolean tone0IsCutout) {
        if (tone0IsCutout && tone == 0) {
            return ALPHA_CUTOUT;
        }
        return color.length > 3 ? color[3] : ALPHA_OPAQUE;
    }

    /**
     * 玻璃：1px 外边框与高光带 <b>完全不透明</b>，内部半透。
     *
     * <p>调色板第 4 项刻意写 {@link #ALPHA_OPAQUE}，因此本方法只处理"内部"一档。
     * 边框判定用 {@link #isGlassBorder}（u或 v 落在最外圈）。
     */
    private static void writeGlass(float[] out) {
        for (int v = 0; v < SIZE; v++) {
            String row = GLASS[v];
            for (int u = 0; u < SIZE; u++) {
                int tone = row.charAt(u) - '0';
                int[] c = GLASS_PALETTE[tone];
                int alpha = isGlassBorder(u, v) ? ALPHA_OPAQUE : GLASS_INTERIOR_ALPHA;
                int p = (v * SIZE + u) * 4;
                out[p] = c[0] / 255f;
                out[p + 1] = c[1] / 255f;
                out[p + 2] = c[2] / 255f;
                out[p + 3] = alpha / 255f;
            }
        }
    }

    /** 是否是玻璃的 1px 外边框（美术规格 §3.9 硬规则 1）。 */
    static boolean isGlassBorder(int u, int v) {
        return u == 0 || u == SIZE - 1 || v == 0 || v == SIZE - 1;
    }

    /**
     * 草方块侧面：草边 + 泥土。
     *
     * <p>规格 §3.3 给了"草边深度表"（每列草像素行数），
     * 且要求 {@code v >= depth[u]+1} 的像素<b>与 dirt 层逐像素完全一致</b>。
     * 本方法<b>直接复用 DIRT 的调色板与网格</b>，因此那条"逐像素相同"是
     * <b>结构性成立</b>的，而不是靠两条数据恰好一致 ——
     * 后者一旦有人单独改 dirt 就会静默失效。
     */
    private static void writeGrassSide(float[] out) {
        for (int v = 0; v < SIZE; v++) {
            String grassRow = GRASS_SIDE[v];
            for (int u = 0; u < SIZE; u++) {
                int tone = grassRow.charAt(u) - '0';
                int[] c;
                if (isGrassPixel(u, v)) {
                    // 草色：规格 §3.3 规则 1 —— (v + u) % 3 == 0 用 l2，否则 l1
                    c = ((v + u) % 3 == 0) ? OPAQUE_GRASS[4] : OPAQUE_GRASS[3];
                } else if (isGrassShadowRow(u, v)) {
                    // 咬边阴影行：规格 §3.3 规则 2 —— 取**泥土**调色板的 d1。
                    // 这是全局唯一被允许的方向性明暗：它表达的是
                    // "草叶垂下来遮住泥土"的遮挡关系，不是光源。
                    c = OPAQUE_DIRT[1];
                } else {
                    c = OPAQUE_DIRT[DIRT_GRASS_SIDE[v].charAt(u) - '0'];
                }
                int p = (v * SIZE + u) * 4;
                out[p] = c[0] / 255f;
                out[p + 1] = c[1] / 255f;
                out[p + 2] = c[2] / 255f;
                out[p + 3] = 1f;
            }
        }
    }

    /**
     * 草边深度表（美术规格 §3.3，逐列给出草像素行数）。
     *
     * <p>写成显式常量而不是从字符网格里"推断"：
     * 规格要求"草边不得做成一条水平直线"，而推断逻辑一旦写错，
     * 症状是"草边齐平" —— 画面上只是"方块之间糊了一点"，几乎不可能被归因到此处。
     */
    private static final int[] GRASS_DEPTH = {
            4, 3, 4, 2, 5, 3, 4, 3, 2, 4, 5, 3, 4, 2, 5, 4
    };

    /**
     * 某像素是否取<b>草色</b>（美术规格 §3.3 规则 1）。
     *
     * <p><b>★ 注意咬边那一行不算草</b>：规格规则 2 规定
     * "若 {@code depth[u] < 5}，则 {@code v == depth[u]} 这一行取 {@code d1}（暗）"——
     * 那是"草压住泥土"的<b>阴影</b>行，颜色来自<b>泥土调色板</b>的 {@code d1}，
     * 不是草色。若把它算进草行数，草边就会<b>比规格多一行</b>，
     * 而症状只是"草边略厚一点"，几乎不可能归因。
     *
     * <p>因此判定是 {@code v < depth[u]}（不含边界行），
     * 而阴影行由 {@link #isGrassShadowRow} 单独识别。
     */
    static boolean isGrassPixel(int u, int v) {
        return v < GRASS_DEPTH[u];
    }

    /**
     * 某像素是否是"草压泥土"的<b>咬边阴影行</b>（规格 §3.3 规则 2）。
     *
     * <p>条件：{@code depth[u] < 5}（即草没有铺满整列）且 {@code v == depth[u]}。
     * 这一行取<b>泥土调色板的 {@code d1}</b>。
     */
    static boolean isGrassShadowRow(int u, int v) {
        return GRASS_DEPTH[u] < 5 && v == GRASS_DEPTH[u];
    }

    /**
     * 矿石：岩底 + 矿点。
     *
     * <p>矿点用<b>规格单独指定的颜色</b>，而不是调色板的 tone4 ——
     * 因为五种矿石的"tone4"在网格里同时表示"岩底最亮"与"矿点"，
     * 二者颜色不同（规格 §3.12-§3.16 的调色板行都写明了这一点）。
     *
     * @param coreColor 高光核颜色；{@code -1} 表示该矿石无高光核（此时 tone5 视为矿点本色）
     */
    private static void writeOre(float[] out, int[][] rock, int[] spot, int coreColor) {
        for (int v = 0; v < SIZE; v++) {
            // ★ 五种矿石共用同一张网格（规格 §3.12-§3.16 的网格字形相同），
            // 只有岩底与矿点颜色不同 —— 那正是"互相能分辨"的来源（规格 §4.2）。
            String row = IRON_ORE[v];
            for (int u = 0; u < SIZE; u++) {
                int tone = row.charAt(u) - '0';
                int[] c;
                if (tone == 4) {
                    c = spot;
                } else if (tone == 5) {
                    c = coreColor >= 0 ? new int[]{coreColor >> 16 & 0xFF,
                            coreColor >> 8 & 0xFF, coreColor & 0xFF, ALPHA_OPAQUE} : spot;
                } else {
                    c = rock[tone];
                }
                int p = (v * SIZE + u) * 4;
                out[p] = c[0] / 255f;
                out[p + 1] = c[1] / 255f;
                out[p + 2] = c[2] / 255f;
                out[p + 3] = 1f;
            }
        }
    }

    // ============================================================ 调色板（美术规格 §1.5 + §3）

    private static int[] rgb(int hex) {
        return new int[]{(hex >> 16) & 0xFF, (hex >> 8) & 0xFF, hex & 0xFF, ALPHA_OPAQUE};
    }

    private static int[] cutout(int hex) {
        return new int[]{(hex >> 16) & 0xFF, (hex >> 8) & 0xFF, hex & 0xFF, ALPHA_CUTOUT};
    }

    private static final int[][] OPAQUE_STONE = {
            rgb(0x595959), rgb(0x6B6B6B), rgb(0x7F7F7F), rgb(0x919191), rgb(0xA5A5A5)};
    private static final int[][] OPAQUE_DIRT = {
            rgb(0x613F1E), rgb(0x754C24), rgb(0x8B5A2B), rgb(0x9E6731), rgb(0xB57538)};
    private static final int[][] OPAQUE_GRASS = {
            rgb(0x35612C), rgb(0x407535), rgb(0x4C8B3F), rgb(0x579E48), rgb(0x63B552)};
    private static final int[][] OPAQUE_SAND = {
            rgb(0xB0A670), rgb(0xC4BA8A), rgb(0xD9CE8F), rgb(0xE9E1AE), rgb(0xF5EFC8)};
    private static final int[][] OPAQUE_COBBLE = {
            rgb(0x4A4A4A), rgb(0x606060), rgb(0x767676), rgb(0x8A8A8A), rgb(0x9C9C9C)};
    private static final int[][] OPAQUE_LOG = {
            rgb(0x4B341E), rgb(0x5A3E24), rgb(0x6B4A2B), rgb(0x7A5431), rgb(0x8B6038)};
    /**
     * 原木<b>端面</b>（顶/底）年轮调色板 —— 照抄美术规格 §3.6 的「调色板（top）」行。
     *
     * <p>★<b>它必须与 {@link #OPAQUE_LOG}（树皮色阶）分开，这是物理事实不是风格选择</b>：
     * 现实中木材的<b>横切面确实比树皮亮</b>（树皮遮光）。规格 §3.6 明确写道
     * 「{@code log_top} 的基色 {@code A87C46} <b>刻意亮于</b> {@code log_side} 的 {@code 6B4A2B}」。
     *
     * <p>★<b>为什么单独开这个常量（历史教训）</b>：
     * 两者曾共用 {@code OPAQUE_LOG}，而<b>网格字符是对的、档数是对的</b>，
     * 于是 {@code verify_grids.py}（查字符）与 {@code audit_spec.py}（查档数）
     * <b>两道守卫全绿</b>。实测差异：
     * <ul>
     *   <li>规格意图（端面色阶）：平均亮度 125.2 / 内对比 102.8 / 比树皮亮 <b>+49.7</b>（年轮清晰）；</li>
     *   <li>误用树皮色阶：平均亮度 76.3 / 内对比 47.0 / 比树皮亮 <b>+0.8</b>（几乎同色）。</li>
     * </ul>
     * ⇒ 误用后<b>年轮基本看不见</b>。该盲区由 {@code tmp/s3work/audit_palette.py}
     * （逐档比对<b>色值</b>）发现，已固化进
     * {@code docs/art/BLOCK_TEXTURE_SPEC.md} §3.6 的红框与 §9.1.4。
     */
    private static final int[][] OPAQUE_LOG_TOP = {
            rgb(0x6B4A2B), rgb(0x8A6238), rgb(0xA87C46), rgb(0xC49655), rgb(0xDCB268)};
    private static final int[][] OPAQUE_PLANKS = {
            rgb(0x7B6134), rgb(0x94743E), rgb(0xB08A4A), rgb(0xC99D54), rgb(0xE5B360)};
    private static final int[][] OPAQUE_DOOR = {
            rgb(0x614524), rgb(0x75532C), rgb(0x8B6334), rgb(0x9E713B), rgb(0xB58144)};
    private static final int[][] OPAQUE_BRICK = {
            rgb(0x5E5E5E), rgb(0x747474), rgb(0x8A8A8A), rgb(0x9D9D9D), rgb(0xB0B0B0)};
    /**
     * 铁块调色板（<b>5 档</b>）。
     *
     * <p>★规格缺口：美术规格 §3.18 的调色板表只写到 {@code 4=FFFFFF}，
     * 但它的网格里用了 {@code '5'}（铆钉左上角的<b>高光核</b>），
     * 而 §3.18 的"图案意图"原文就写着"4 个 2×2 铆钉（{@code l2} 底 + 左上角 {@code tone5} 高光）"。
     * <b>表格与网格自相矛盾</b>：照表格实现会在读到 {@code '5'} 时抛
     * "网格字符越界"。
     *
     * <p>处置：补第 5 档为 {@code FFFFFF}（纯白高光）。
     * 依据是"铁块是全场最亮方块，铆钉高光必须是纯白"，
     * 且它与第 4 档同为 {@code FFFFFF} 在视觉上无冲突（高光核本来就是最亮点）。
     * <b>此项已在报告 §5 登记，建议美术侧回填规格表。</b>
     */
    private static final int[][] OPAQUE_IRON_BLOCK = {
            rgb(0x9A9A9A), rgb(0x9A9A9A), rgb(0xD6D6D6), rgb(0xECECEC),
            rgb(0xFFFFFF), rgb(0xFFFFFF)};

    /** 树叶：tone0 是镂空（美术规格 §3.8）。 */
    private static final int[][] LEAF_PALETTE = {
            cutout(0x1C4418), rgb(0x27551F), rgb(0x316328), rgb(0x3B7531), rgb(0x46883B)};

    /** 玻璃：全部不透明 alpha 由 {@link #writeGlass} 按位置决定。 */
    private static final int[][] GLASS_PALETTE = {
            rgb(0x9FC8D8), rgb(0x9FC8D8), rgb(0xBFE4F0), rgb(0xD6F0F8), rgb(0xFFFFFF)};

    /** 火把：tone0 是镂空。 */
    private static final int[][] TORCH_PALETTE = {
            cutout(0x000000), rgb(0x6B4A2B), rgb(0x8A6238), rgb(0xE0A040),
            rgb(0xFFD070), rgb(0xFFF0C0)};

    /** 小麦：tone0 是镂空。 */
    private static final int[][] WHEAT_PALETTE = {
            cutout(0x000000), rgb(0x7E9A34), rgb(0x9CB84A), rgb(0xC8A63C), rgb(0xE4C060)};

    // ---- 矿石：岩底两套（亮岩 / 暗岩），矿点各自指定 ----

    private static final int[][] ORE_STONE = {
            rgb(0x5E5A55), rgb(0x77736C), rgb(0x918C84), rgb(0xA8A29A), rgb(0xBEB8B0)};
    private static final int[][] ORE_COAL = {
            rgb(0x2E2E2E), rgb(0x404040), rgb(0x545454), rgb(0x686868), rgb(0x7C7C7C)};
    private static final int[] IRON_SPOT = rgb(0xD8C4B4);
    private static final int[] COAL_SPOT = rgb(0x1E1E1E);
    private static final int[] COPPER_SPOT = rgb(0xE07030);
    private static final int[] CRYSTAL_SPOT = rgb(0xA8ECF8);
    private static final int[] GOLD_SPOT = rgb(0xF2D420);

    /**
     * 晶体 / 金矿石的矿点<b>高光核</b>（网格字符 {@code '5'}）。
     *
     * <p>规格 §3.15 / §3.16 单独指定了这两个颜色 ——
     * 它们<b>不是</b>由基色按系数派生出来的：矿点已经高饱和，
     * 再乘1.30 会溢出成白点，失去"金属"的可辨识度。
     */
    private static final int CRYSTAL_CORE = 0xD8FBFF;
    private static final int GOLD_CORE = 0xFFF066;

    /** {@code resource_core}（美术规格 §7.1 明确不占层，此处为新增层，见类注释）。 */
    private static final int[] SYSTEM_CORE_RGBA = rgb(0xE8C34A);

    // ============================================================ 网格数据
    //
    // ★★★ 本段由 tmp/s3work/gen_grids.py 从 docs/art/BLOCK_TEXTURE_SPEC.md
    //   **机器提取**而来，不是手抄的。
    //
    //   为什么必须机器提取：S3 首次实现时手工转录这 24 张网格，
    //   verify_grids.py 查出 24 张里有 19 张与规格不一致（多处凭印象编造）。
    //   而"贴图与规格不一致"的症状只是"画面略有不同"——
    //   人眼几乎不可能把它归因到"网格抄错了一格"。
    //
    //   因此：**改贴图请改美术规格文档，然后重跑生成脚本**，
    //   不要直接编辑本段。verify_grids.py 会在每次构建前核对逐字符一致性。
    //
    // 每张网格 16 行 × 16 列。字符含义见美术规格 §2：
    //   0 =最深档 **或** 透明像素（由该层的调色板决定，见 alphaFor）
    //   2 = 基色    4 = 最亮

    /** STONE —— stone 的 main 面。 */
    private static final String[] STONE = {
            "1122113322112233",
            "1122113322112233",
            "2211341111223302",
            "2211331111223322",
            "1130222211112211",
            "1133222211112211",
            "2222113301221122",
            "2222113314221122",
            "3311221122332211",
            "3311221122302211",
            "1142221133111133",
            "1122221133111133",
            "2211332211223422",
            "2211330211223322",
            "1133112222112211",
            "1133112422112211",
    };

    /** DIRT —— dirt 的 main 面。 */
    private static final String[] DIRT = {
            "3322112233112211",
            "3302112233112211",
            "2233221111331422",
            "2233221011331122",
            "1122111122223311",
            "1122140122223311",
            "2211221133110222",
            "2011221133112222",
            "1122332211241133",
            "1122032211221133",
            "3311113311222211",
            "3311113310222211",
            "2233221122331122",
            "2233221122331102",
            "1122112242113311",
            "1122112222013311",
    };
    /**
     * 草方块侧面在"非草"区域<b>使用 dirt 的网格</b>（规格 §3.3 规则 3）。
     *
     * <p>规格要求那一段与 {@code dirt} 层<b>逐像素相同</b>。
     * 用<b>引用</b>而非复制，使那条要求<b>结构性成立</b> ——
     * 若复制两份，某人单独改 dirt 就会让两者静默分叉，
     * 而症状只是"草方块侧面的泥土和泥土方块颜色略有差异"。
     *
     * <p>放在 {@link #DIRT} 定义<b>之后</b>是 Java 的前向引用限制所致。
     */
    private static final String[] DIRT_GRASS_SIDE = DIRT;


    /** GRASS_TOP —— grass_block 的 top 面。 */
    private static final String[] GRASS_TOP = {
            "1122112233112211",
            "1122412243112211",
            "2211332211221133",
            "2241332211221133",
            "1133221122023311",
            "1133221122223411",
            "3311023311112222",
            "3311223311112222",
            "2211141122331124",
            "2211111122331122",
            "1122112233142211",
            "1122110233112211",
            "2433221122113302",
            "2233221122113322",
            "3322112410332211",
            "3322112211332211",
    };

    /** GRASS_SIDE —— grass_block 的 side 面。 */
    private static final String[] GRASS_SIDE = {
            "4334334334334334",
            "3343343343343343",
            "3431433413433133",
            "4133314114314134",
            "1112311121321341",
            "1122140122223311",
            "2211221133110222",
            "2011221133112222",
            "1122332211241133",
            "1122032211221133",
            "3311113311222211",
            "3311113310222211",
            "2233221122331122",
            "2233221122331102",
            "1122112242113311",
            "1122112222013311",
    };

    /** SAND —— sand 的 main 面。 */
    private static final String[] SAND = {
            "1122112222112211",
            "1122112222132211",
            "2211222211222222",
            "2213222211222222",
            "1122221322111122",
            "1122211122111122",
            "2211221123222211",
            "2211221122222211",
            "2322112211221122",
            "2222112211221122",
            "1122222211112231",
            "1122222111112211",
            "2211221111222222",
            "2211223111222222",
            "1122112222112222",
            "1122112222112222",
    };

    /** COBBLESTONE —— cobblestone 的 main 面。 */
    private static final String[] COBBLESTONE = {
            "4222204333304222",
            "2212203313302212",
            "2422203433302422",
            "2222200000002222",
            "0000422222002222",
            "4330221222000000",
            "3310242222043333",
            "3430222222033133",
            "3330222222034333",
            "3330000000033333",
            "0000000433300000",
            "4222220331304222",
            "2212220343302212",
            "2422220333302422",
            "2222220000002222",
            "2222220000002222",
    };

    /** LOG_SIDE —— log 的 side 面。 */
    private static final String[] LOG_SIDE = {
            "1213212312312132",
            "1213212312314132",
            "1213212312314132",
            "0000002312314132",
            "1231231213214132",
            "1231431213212132",
            "1231431213212132",
            "1231431213212132",
            "3121421213212312",
            "3121320000000000",
            "3121321213212312",
            "3121321213212312",
            "1213212312312132",
            "1213212312312132",
            "0000000002312132",
            "1213212312312132",
    };

    /** LOG_TOP —— log 的 top 面。 */
    private static final String[] LOG_TOP = {
            "1111111111111111",
            "1111111101111111",
            "1122220222222211",
            "1122222222222211",
            "1122333333332211",
            "1122333333332211",
            "1122334044332211",
            "1122334444332211",
            "1022334444332211",
            "1122334444332201",
            "1122333333332211",
            "1122333333332211",
            "1122222222222211",
            "1122222222222211",
            "1111111110111111",
            "1111111111111111",
    };

    /** OAK_PLANKS —— oak_planks 的 main 面。 */
    private static final String[] OAK_PLANKS = {
            "4444444444444444",
            "3222232022322213",
            "3222232022322213",
            "0000000000000000",
            "4444444444444444",
            "2200122322213222",
            "2200122322213222",
            "0000000000000000",
            "4444444444444444",
            "2122322213202231",
            "2122322213202231",
            "0000000000000000",
            "4444444444444444",
            "2322200222231222",
            "2322200222231222",
            "0000000000000000",
    };

    /** LEAVES —— leaves 的 main 面。 */
    private static final String[] LEAVES = {
            "1122112222113311",
            "1122112224114311",
            "2433221122331122",
            "2233221102331122",
            "3311113311222214",
            "3301113311222211",
            "1122342211021103",
            "1122332211221133",
            "0211221133112422",
            "2211221433112222",
            "1122011122223311",
            "1122111122220311",
            "2234221111331122",
            "2233221111331122",
            "3322110233142211",
            "3322112233112211",
    };

    /** GLASS —— glass 的 main 面。 */
    private static final String[] GLASS = {
            "4444444444444444",
            "4111111111111114",
            "4111111111111114",
            "4111111111111114",
            "4111111111121114",
            "4111111111211114",
            "4111111132111114",
            "4111111311111114",
            "4111113111111114",
            "4111131111111114",
            "4111311111111114",
            "4113111111111114",
            "4111111111111114",
            "4111111111111114",
            "4111111111111114",
            "4444444444444444",
    };

    /** TORCH_FULL —— torch 的 main 面。 */
    private static final String[] TORCH_FULL = {
            "0000000550000000",
            "0000000440000000",
            "0000003443000000",
            "0000003443000000",
            "0000003443000000",
            "0000000210000000",
            "0000000210000000",
            "0000000210000000",
            "0000000210000000",
            "0000000210000000",
            "0000000210000000",
            "0000000210000000",
            "0000000210000000",
            "0000000210000000",
            "0000000210000000",
            "0000000210000000",
    };

    /** DOOR_SIDE —— wooden_door 的 side 面。 */
    private static final String[] DOOR_SIDE = {
            "1111111111111111",
            "1222222002222221",
            "1111111001111111",
            "1111111001111111",
            "1232222002222221",
            "1212222002212321",
            "1222243002423221",
            "1111111001111111",
            "1111111001111111",
            "1223242003422221",
            "1232122002222121",
            "1222222002222321",
            "1111111001111111",
            "1111111001111111",
            "1222222002222221",
            "1111111111111111",
    };

    /** DOOR_TOP —— wooden_door 的 top 面。 */
    private static final String[] DOOR_TOP = {
            "1000000000000001",
            "1033333333333301",
            "1022222222222201",
            "1011111111111101",
            "1000000000000001",
            "1033333333333301",
            "1022222222222201",
            "1011111111111101",
            "1000000000000001",
            "1033333333333301",
            "1022222222222201",
            "1011111111111101",
            "1000000000000001",
            "1033333333333301",
            "1022222222222201",
            "1011111111111101",
    };

    /** IRON_ORE —— iron_ore 的 main 面。 */
    private static final String[] IRON_ORE = {
            "1122113322112233",
            "1122113322112233",
            "2211331111443322",
            "2244431111443322",
            "1144422211112211",
            "1133222211112211",
            "2222113311221122",
            "2222113311221122",
            "3311221122332211",
            "3311221122334411",
            "1122244433114433",
            "1122244433111133",
            "2211332211223322",
            "2211332211223322",
            "1133112122112211",
            "1133112222112211",
    };

    /** COAL_ORE —— coal_ore 的 main 面。 */
    private static final String[] COAL_ORE = {
            "1122113322112233",
            "1122113322112233",
            "2214441111223322",
            "2214441111223322",
            "1134442211112211",
            "1133222211112211",
            "2222113311441122",
            "2222113311441122",
            "3311221122332211",
            "3311221122332211",
            "1122221133111133",
            "1144221133111133",
            "2244332211223322",
            "2211332211223322",
            "1133112122112211",
            "1133112222112211",
    };

    /** COPPER_ORE —— copper_ore 的 main 面。 */
    private static final String[] COPPER_ORE = {
            "1122113322112233",
            "1122113322112233",
            "2211331111443322",
            "2211331111443322",
            "1144422211442211",
            "1144422211112211",
            "2222113311221122",
            "2222113311221122",
            "3311221122332441",
            "3311221122332441",
            "1122221133111133",
            "1122224443111133",
            "2211334441223322",
            "2211332211223322",
            "1133112122112211",
            "1133112222112211",
    };

    /** CRYSTAL_ORE —— crystal_ore 的 main 面。 */
    private static final String[] CRYSTAL_ORE = {
            "1122113322112233",
            "1122113322112233",
            "2211331111223322",
            "2215431111223322",
            "1134422211112211",
            "1134422211112211",
            "2222113311221122",
            "2222113311221122",
            "3311221122332211",
            "3311221122542211",
            "1122221133441133",
            "1122221133441133",
            "2211335411223322",
            "2211334411223322",
            "1133112122112211",
            "1133112222112211",
    };

    /** GOLD_ORE —— gold_ore 的 main 面。 */
    private static final String[] GOLD_ORE = {
            "1122113322112233",
            "1122113322112233",
            "2254331111223322",
            "2244331111223322",
            "1133222211112211",
            "1133222215442211",
            "2222113314441122",
            "2222113311221122",
            "3311221122332211",
            "3311221122332211",
            "1122221133111133",
            "1122254133111133",
            "2211344211223542",
            "2211332211223442",
            "1133112122112211",
            "1133112222112211",
    };

    /** STONE_BRICK —— stone_brick 的 main 面。 */
    private static final String[] STONE_BRICK = {
            "3222232002322213",
            "2322223002232122",
            "2232212002223222",
            "2223122002212322",
            "0000000000000000",
            "3200232221300223",
            "2300223212200221",
            "1200222322200212",
            "2200221232200322",
            "0000000000000000",
            "3222132002312223",
            "2321223002132222",
            "2232222001223222",
            "2123222002222321",
            "0000000000000000",
            "0000000000000000",
    };

    /** IRON_BLOCK —— iron_block 的 main 面。 */
    private static final String[] IRON_BLOCK = {
            "1111111111111111",
            "1111111111111111",
            "1122222222222211",
            "1124444444444211",
            "1124222222224211",
            "1124254222544211",
            "1124244332444211",
            "1124223222224211",
            "1124222223224211",
            "1124222332224211",
            "1124254222544211",
            "1124244222444211",
            "1124444444444211",
            "1122222222222211",
            "1111111111111111",
            "1111111111111111",
    };

    /** WHEAT —— wheat 的 main 面。 */
    private static final String[] WHEAT = {
            "0000000440000000",
            "0000003443000000",
            "0000003443000000",
            "0000004444000000",
            "0000003443000000",
            "0000003333000000",
            "0000000110000000",
            "0000022112200000",
            "0000022112200000",
            "0000000110000000",
            "0000022112200000",
            "0000022112200000",
            "0000000110000000",
            "0000000110000000",
            "0000000110000000",
            "0000000110000000",
    };

    /** SLAB_TOP —— slab 的 top 面。 */
    private static final String[] SLAB_TOP = {
            "0000000000000000",
            "0212322001322220",
            "0232122002212230",
            "0222223002223120",
            "0122322002322220",
            "0000000000000000",
            "0200213222200220",
            "0200322122300210",
            "0200222231200230",
            "0200123222200220",
            "0000000000000000",
            "0132222002222230",
            "0221223002123220",
            "0222312002321220",
            "0232222002222230",
            "0000000000000000",
    };

    /** SLAB_SIDE —— slab 的 side 面。 */
    private static final String[] SLAB_SIDE = {
            "0000000000000000",
            "2212322021322222",
            "2232122032212232",
            "3222223022223122",
            "0000000000000000",
            "2200222232122232",
            "3200213222223222",
            "1200322122322212",
            "0000000000000000",
            "3222123222203222",
            "2222321222302122",
            "2132222232202231",
            "0000000000000000",
            "2222300222321222",
            "1232200132222232",
            "0000000000000000",
    };

    /**
     * 纯白层（{@link BlockTextureLayers#NEUTRAL_WHITE}）的 RGBA，供守卫断言。
     *
     * <p><b>它是"非方块几何画面不变"的唯一依据</b>：
     * 实体 / 粒子 / 裂纹 / 手持物采样这一层，
     * 于是 {@code texel.rgb = (1,1,1)}，
     * 片元算出的 {@code texel.rgb * vColor.rgb} 恰好等于改动前的 {@code vColor.rgb}。
     * 把它改成别的颜色，那五类几何的颜色就会静默被染 ——
     * 而症状只是"怪物颜色有点怪"，几乎不可能归因到纹理层。
     */
    public static float[] plainWhiteRgba() {
        return layerRgba(BlockTextureLayers.NEUTRAL_WHITE);
    }

    // ============================================================ 自检（供单测与启动日志使用）

    /**
     * 该层是否<b>非纯色</b>（PRD §6.4 硬要求：防止"忘了画"却看起来正常）。
     *
     * <p>判据：忽略 alpha 后，RGB 不全相同。
     * 纯色贴图会让mipmap 与截图取证失去意义 —— 因为"变了什么"看不出来。
     */
    public static boolean isNonUniform(int layer) {
        float[] px = layerRgba(layer);
        int r0 = Math.round(px[0] * 255);
        int g0 = Math.round(px[1] * 255);
        int b0 = Math.round(px[2] * 255);
        for (int i = 1; i < PIXELS; i++) {
            if (Math.round(px[i * 4] * 255) != r0
                    || Math.round(px[i * 4 + 1] * 255) != g0
                    || Math.round(px[i * 4 + 2] * 255) != b0) {
                return true;
            }
        }
        return false;
    }

    /** 该层用到的不同明度档数（美术规格 §6 自检清单第3 项，≥ 3）。 */
    public static int distinctTones(int layer) {
        float[] px = layerRgba(layer);
        Set<Integer> seen = new LinkedHashSet<>();
        for (int i = 0; i < PIXELS; i++) {
            seen.add(luminance255(px, i));
        }
        return seen.size();
    }

    /**
     * 某像素的亮度（0–255）。
     *
     * <p><b>★ 这里踩过一个运算符优先级坑，值得留痕：</b>
     * 初版写成 {@code Math.round(0.299f*r + 0.587f*g + 0.114f*b) * 255}，
     * 由于 {@code px} 是<b>归一化</b>的（0..1），{@code Math.round} 已经把亮度取整成0 或 1，
     * <b>再乘 255 只会得到 0 或 255 两个值</b>——
     * 于是"5 个明度档"被压成"2 档"，而断言 {@code distinctTones >= 3} 变红。
     *
     * <p><b>为什么难以发现</b>：症状是"石头只有 2 个明度档"，
     * 看起来像"美术的石头贴图对比度不够"，而实际是<b>测量代码错了</b>。
     * 正确写法是<b>先乘 255 再取整</b>。
     */
    private static int luminance255(float[] px, int pixelIndex) {
        return Math.round(255f * (0.299f * px[pixelIndex * 4]
                + 0.587f * px[pixelIndex * 4 + 1]
                + 0.114f * px[pixelIndex * 4 + 2]));
    }

    /** 该层的**内对比**（最亮-最暗亮度差，美术规格 §6 自检第 4 项）。 */
    public static int internalContrast(int layer) {
        float[] px = layerRgba(layer);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < PIXELS; i++) {
            int lum = luminance255(px, i);
            min = Math.min(min, lum);
            max = Math.max(max, lum);
        }
        return max - min;
    }
}

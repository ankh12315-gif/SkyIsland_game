package com.skyisland.render.mesh;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * 方块贴图的 GPU 侧：{@code GL_TEXTURE_2D_ARRAY} 的创建与上传（M4-S3，PRD §6.1）。
 *
 * <h2>为什么是 2D_ARRAY 而不是纹理图集（atlas）</h2>
 * 图集（把 24 张 16×16 拼成一张 128×96）需要处理"哪个方块落在图集的哪一格"，
 * 于是每个面都要带一组 UV，而<b>UV 必须在相邻方块之间做插值</b> ——
 * 一旦插值跨过图集边界就会串色（mipmap 下更严重）。
 * 纹理数组把层号变成<b>逐顶点属性</b>而不是逐纹素坐标，插值发生在整数层上，
 * 因此<b>结构上不可能串色</b>。代价是顶点多两个 float（S2 已付）。
 *
 * <h2>★ 为什么必须"最近邻 + mipmap"这个看似矛盾的组合</h2>
 * <ul>
 *   <li><b>最近邻</b>：像素美术（16×16）最怕线性插值 ——
 *       它会把 2×2 材质单元糊成一片模糊的灰噪点（美术规格 §1.3 正是为此裁定"2×2 单元"）。</li>
 *   <li><b>mipmap</b>：远处方块若没有 mip，会<b>疯狂闪烁</b>（像素级欠采样）。
 *       但 mip 会把玻璃边框的 alpha 向内渗透 —— 这是美术规格 §3.9 规则 2
 *       要求玻璃内部 alpha ≈ 0.35 <b>而不是 0</b>的原因。</li>
 * </ul>
 * 两者必须同时存在，只有一个都会出问题。
 *
 * <h2>为什么包一层而不是让调用方直接写 GL</h2>
 * 纹理创建涉及 7 步调用与 3 个 glEnable 参数，
 * 让 {@link ChunkRenderer} 每次绘制都重传一遍是明显的浪费。
 * 包一层后，"建一次、传一次、每帧只 bind" 成为结构。
 */
public final class BlockTextureAtlas {

    /** 绑定的纹理单元号。0 号单元通常被 UI 占用，因此放1 号。 */
    public static final int TEXTURE_UNIT = 1;

    /** 采样器 uniform 名（须与 voxel.frag 里的 uniform 名一致）。 */
    public static final String SAMPLER_UNIFORM = "uBlockAtlas";

    /** 一层贴图的边长（像素）。 */
    public static final int SIZE = BlockTextures.SIZE;

    /** 层数。 */
    public static final int LAYERS = BlockTextureLayers.LAYER_COUNT;

    private int textureId;
    private boolean uploaded;

    /**
     * 创建并上传整张纹理数组。<b>必须在 GL 上下文内调用。</b>
     *
     * <p><b>为什么像素来自 CPU 而不是 {@code glTexSubImage3D} 分层上传</b>：
     * 一次性上传整张只需一次 API 调用，且便于在纯 JVM 里用
     * {@link BlockTextures#allLayersRgba()} 做像素级断言 ——
     * GPU 上的内容无法在无上下文环境里检查。
     */
    public void create() {
        if (uploaded) {
            throw new IllegalStateException("纹理数组已创建，重复创建会泄漏 GL 对象");
        }
        textureId = GL13.glGenTextures();
        GL13.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureId);

        // ★ 每一层都必须独立设置 wrap 边界。
        // 若保留默认的 GL_REPEAT，在 mipmap 采样时坐标 1.0 会被映射到下一层的起点
        // ——表现为"某个方块的颜色渗进了邻居"。PRD §6.4 要求"全部落在 [0,1] 内，不跨层取样"。
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);

        // 最近邻：像素美术最忌讳线性插值（见类注释）
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_NEAREST_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        float[] pixels = BlockTextures.allLayersRgba();
        GL13.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8,
                SIZE, SIZE, LAYERS,
                0, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);

        //★★ mipmap 必须在 glTexImage3D **之后**生成 —— 这是 S4 靠截图抓到的第二个真bug。
        //
        //   初版把 glGenerateMipmap 写在 glTexImage3D **之前**：
        //   那一刻纹理还没有任何数据，生成出来的 mip 链全是空的。
        //   随后 glTexImage3D 只填了 **level 0**，高层仍是空的。
        //
        //   为什么画面是**纯黑而不是"远处才糊"**：
        //   MIN_FILTER 用了 GL_NEAREST_MIPMAP_LINEAR —— 该常量**总是采样 mip**，
        //   按纹理尺寸在 mip 链里选级。选到高层（空的）就是黑的。
        //   于是**整张图全黑**，而不是"近处正常、远处发糊"。
        //
        //   症状与"贴图没生成"完全一样，而 CPU 侧的 1212 个测试全绿
        //   —— 因为它们检查的是 BlockTextures 的像素，而这里错的是**上传顺序**。
        //   ★ 只有真实 GL 上下文里的截图能抓到这一类。
        GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);

        GL13.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
        uploaded = true;
    }

    /** 绑定到 {@link #TEXTURE_UNIT} 并让着色器能采到。 */
    public void bind() {
        if (!uploaded) {
            throw new IllegalStateException("纹理数组尚未创建");
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
        GL13.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureId);
    }

    /** GL 对象 id（自测断言用；未创建时为 0）。 */
    public int textureId() {
        return textureId;
    }

    public boolean isUploaded() {
        return uploaded;
    }

    /** 释放 GL 对象。必须在 GL 上下文销毁之前调用。 */
    public void dispose() {
        if (uploaded && textureId != 0) {
            GL13.glDeleteTextures(textureId);
        }
        textureId = 0;
        uploaded = false;
    }
}

package com.skyisland.render;

import com.skyisland.world.LightEngine;

/**
 * 体素顶点格式的<b>唯一真相源</b>（M4-S2，stride 28 → 36 字节）。
 *
 * <h2>为什么必须有这个类</h2>
 * S2 之前的现状是<b>五个</b>渲染器各自硬编码了"7 个 float"，
 * 并且各自硬编码了 {@code glVertexAttribPointer} 的偏移。
 * PRD §6.2 只登记了三处，<b>漏了两处</b>（见 {@link #CONSUMERS}）。
 *
 * <p><b>漏改的后果不是编译错，是画面静默损坏</b>：
 * 着色器按 36 字节 stride 读 {@code aLayer}，而某个渲染器仍按 28 字节上传 ——
 * {@code aLayer} 会读到下一个顶点的 position 字节，
 * 实体 / 粒子 / 裂纹 / 手持物的几何会错乱或整块消失。
 * 而<b>编译通过、全部单测全绿</b>，因为没有任何断言问过"五者是否一致"。
 *
 * <h2>为什么用"一个类"而不是"每个类各自改成 9"</h2>
 * 五个地方各写一个 {@code = 9} 与一个地方写 {@code = 9}，
 * 在<b>今天</b>等价，在<b>下一次</b>格式变更时不等价 ——
 * 后者只需要改一处，前者需要改五处且靠人记住。
 * 本类把这五处收敛成一个符号，让"漏改"从<b>需要靠自觉</b>变成<b>编译期不可能</b>。
 *
 * <h2>字段布局（交错，stride = 44 字节 = 11 个 float）</h2>
 * <pre>
 *   offset  0 : aPos        vec3   12 B   区块局部坐标（恒在 [0,16]）
 *   offset 12 : aColor      vec4   16 B   rgb = 方块顶点色，<b>a = 明暗（白天口径，烘焙值）</b>
 *   offset 28 : aLayerLight vec2    8 B   x = 纹理数组层号，<b>y = 打包光照</b>（M5a）
 *   offset 36 : aUv         vec2    8 B   S3 新增：该面在层内的 UV
 * </pre>
 *
 * <p><b>为什么 {@code aColor} 不能删</b>（PRD §6.2 硬要求）：
 * 它承载的是<b>明暗</b>（面明暗 × 光照），而贴图承载的是<b>表面图案</b>，
 * 两者是<b>乘算</b>关系 —— 贴图回答"这里画什么"，明暗回答"这里多亮"。
 * 删掉明暗的后果不是"少一个效果"，而是<b>整个体素结构消失</b>：
 * 所有面一样亮，方块会糊成一张平贴纸，看不出哪里是顶面哪里是侧面。
 *
 * <p><b>为什么 {@code aLayerLight} 用 vec2 而不是两个 float 属性</b>：
 * GLSL 的属性槽位有限（通常 16 个），而破碎动画这类后续特性还会要位置。
 * 两个 float 合成一个 vec2 只占 1 个槽位，且片元里可作为 {@code vec2} 一次传递。
 *
 * <h2>各消费方在 {@code aLayerLight.y} 上的口径</h2>
 * M5a 起该分量是 {@link #packLight} 打包的光照，只有 {@code ChunkMesher} 写真值；
 * 其余三个写入点写常量：
 * <ul>
 *   <li>{@link #LIGHT_SKY_EXPOSED} —— 实体 / 手持物（随昼夜变暗）；</li>
 *   <li>{@link #LIGHT_ALWAYS_LIT} —— 粒子 / 曳光 / 裂纹（不受昼夜影响）。</li>
 * </ul>
 *
 * <h2>★ 改派这一步为什么"画面应当逐像素一致"</h2>
 * 旧口径里 {@code (1 - ao)} 恒等于 1，而新口径在<b>白天</b>
 * （{@code uSkyLevel == 1} 且 {@code uAmbientFloor == uDayFloor}）时解析出来的明暗系数恰好为 1
 * —— 这是 {@code DayNightShadingTest} 穷举 32 种打包组合逐位验证的性质，
 * 也就是说<b>昼夜的首帧不会因为这次格式改动而改变任何一个像素</b>。
 * 若某天这条不成立了，说明公式被改坏了，而不是"白天应该稍微亮一点点"。
 */
public final class VertexFormat {

    /** 每个顶点的 float 个数（pos 3 + color 4 + layerLight 2 + uv 2）。 */
    public static final int FLOATS_PER_VERTEX = 11;

    /** 每个顶点的字节数（交错 stride）。 */
    public static final int VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    // ------------------------------------------------------------ 字段内偏移（float 下标）

    /** {@code aPos} 在顶点内的 float 下标。 */
    public static final int POS_OFFSET_FLOATS = 0;

    /** {@code aColor} 在顶点内的 float 下标。 */
    public static final int COLOR_OFFSET_FLOATS = 3;

    /** {@code aLayerLight} 在顶点内的 float 下标。 */
    public static final int LAYER_LIGHT_OFFSET_FLOATS = 7;

    /** {@code aUv} 在顶点内的 float 下标（S3 新增）。 */
    public static final int UV_OFFSET_FLOATS = 9;

    // ------------------------------------------------------------ 字段内偏移（字节，供 glVertexAttribPointer 用）

    /** {@code aPos} 字节偏移。 */
    public static final long POS_OFFSET_BYTES = POS_OFFSET_FLOATS * (long) Float.BYTES;

    /** {@code aColor} 字节偏移。 */
    public static final long COLOR_OFFSET_BYTES = COLOR_OFFSET_FLOATS * (long) Float.BYTES;

    /** {@code aLayerLight} 字节偏移。 */
    public static final long LAYER_LIGHT_OFFSET_BYTES = LAYER_LIGHT_OFFSET_FLOATS * (long) Float.BYTES;

    /** {@code aUv} 字节偏移。 */
    public static final long UV_OFFSET_BYTES = UV_OFFSET_FLOATS * (long) Float.BYTES;

    // ------------------------------------------------------------ 属性槽位（须与 voxel.vert 的 layout(location=...) 一致）

    /** {@code aPos} 的 attribute location。 */
    public static final int LOCATION_POS = 0;

    /** {@code aColor} 的 attribute location。 */
    public static final int LOCATION_COLOR = 1;

    /** {@code aLayerLight} 的 attribute location（S2 新增，M5a 改派 y 分量）。 */
    public static final int LOCATION_LAYER_LIGHT = 2;

    /** {@code aUv} 的 attribute location（S3 新增）。 */
    public static final int LOCATION_UV = 3;

    // ------------------------------------------------------------ 分量数

    /** {@code aPos} 分量数。 */
    public static final int POS_COMPONENTS = 3;

    /** {@code aColor} 分量数。 */
    public static final int COLOR_COMPONENTS = 4;

    /** {@code aLayerLight} 分量数。 */
    public static final int LAYER_LIGHT_COMPONENTS = 2;

    /** {@code aUv} 分量数。 */
    public static final int UV_COMPONENTS = 2;

    // ------------------------------------------------------------ 本步的占位值

    /**
     * 非方块几何（实体 / 粒子 / 裂纹 / 手持物）使用的 UV。
     *
     * <p>它们采样纯白层，UV 取何值都不影响结果。
     * 写固定值而不是"取面内坐标"，是为了让"这五个渲染器不关心 UV"成为显式事实。
     */
    public static final float DEFAULT_UV = 0f;

    /**
     * ★ <b>M5a：{@code aLayerLight.y} 的"见天"标志位（值 16）。</b>
     *
     * <p><b>为什么可以占用这个槽位</b>（它原本叫 {@code ao}，即环境光遮蔽）：
     * S2 起 ao 就被声明为"本步占位"，而四个写入点
     * （{@code ChunkMesher} / {@code Boxes} / {@code CombatFxRenderer} / {@code CrackOverlay}）
     * <b>全部</b>写同一个常量 {@code 0f} —— ao 从未被<b>计算</b>过一次，
     * 片元里的 {@code (1 - ao)} 恒等于 1，是一个乘了个 1 的死因子。
     * 而昼夜循环<b>必须</b>把"天光"与"火把"分两路放进顶点：
     * 只放一路的话，夜里火把会跟着天光一起变暗，
     * 而 PRD §4.4 明写「夜晚昏暗，<b>火把成为主要照明</b>」。
     * <p>换句话说：留着一个恒为 0 的空槽位不用，却要为了昼夜再加一个属性、涨 4 字节 stride、
     * 并重钉六个消费方的偏移 —— 那才是真的浪费。
     *
     * <p><b>打包口径</b>：{@code torchLevel + (skyExposed ? 16 : 0)}，取值 0–31 的<b>整数</b>。
     * 选整数而非分数（{@code sky + torch / 16}）不是因为省事，
     * 而是 float32 的 24 位尾数能<b>精确</b>表示 0–31 的每一个整数；
     * 分数方案要经过一次除法和一次减法才能还原，
     * 误差虽小，却让"这个值到底准不准"变成一个需要额外论证的问题。
     *
     * <p>解码在 {@code voxel.frag}：{@code skyN = step(16.0, packed)}、
     * {@code torch = mod(packed, 16.0)}。
     */
    public static final float SKY_FLAG_BIT = 16f;

    /**
     * 见天、无火把 —— <b>实体与手持物</b>使用。
     *
     * <p>它们在世界里，随昼夜一起变暗；白天时该值让渲染结果与改动前<b>逐位一致</b>。
     */
    public static final float LIGHT_SKY_EXPOSED = SKY_FLAG_BIT;

    /**
     * 永不受昼夜影响 —— <b>粒子 / 曳光 / 裂纹</b>使用。
     *
     * <p>它们是"表现"而不是"被照亮的物体"：夜里的曳光若跟着变暗，
     * 玩家在交火中就看不见自己射出去的弹道了 —— 那不是氛围，是可用性。
     */
    public static final float LIGHT_ALWAYS_LIT = LightEngine.MAX_LIGHT;

    /**
     * 把"是否见天"与"火把等级 [0,15]"打包成一个可被 {@code voxel.frag} 解码的 float。
     *
     * <p>★ 唯一的编码点。四个写入点里只有 {@code ChunkMesher} 需要真值，
     * 其余三个写上面的两个常量 —— 所以"某个几何忘了写光照"会写成 0，
     * 而 0 的含义是"<b>不见天、无火把</b>"，即夜里会变成最暗。
     * 它<b>不会</b>编译失败、也<b>不会</b>花屏，只会安静地让某个东西变暗，
     * 因此 {@link #LIGHT_ALWAYS_LIT} 与 {@link #LIGHT_SKY_EXPOSED} 必须显式写出、不给默认。
     */
    public static float packLight(boolean skyExposed, int torchLevel) {
        int torch = Math.max(0, Math.min(LightEngine.MAX_LIGHT, torchLevel));
        return torch + (skyExposed ? SKY_FLAG_BIT : 0f);
    }

    /**
     * <b>凡是写入体素顶点、或绑定体素属性槽位的类</b>（S2 新增，本步的守卫对象）。
     *
     * <p><b>这个清单是本步最重要的产出</b>。PRD §6.2 写的是"同步登记三处"，
     * 实际有<b>六个类</b>：除了 PRD 点名的 {@code MeshData} / {@code ChunkRenderer} /着色器，
     * 还有三个 PRD 漏掉的：
     * <ul>
     *   <li>{@code CrackOverlay} —— 挖掘裂纹，与体素同格式（贴面绘制）；</li>
     *   <li>{@code CombatFxRenderer} —— 粒子 / 曳光 / 闪光；</li>
     *   <li>{@code Boxes} → 被 {@code EntityRenderer} 与 {@code ViewmodelRenderer} 引用
     *       （手持物），它们通过 {@code Boxes.FLOATS_PER_VERTEX} 间接受影响。</li>
     * </ul>
     * 全部在 {@code Renderer.renderWorld} / {@code renderViewmodel} 里<b>共用同一个
     * {@code voxelShader}</b>，因此<b>一个格式、一个着色器，就必须一份 stride</b>。
     *
     * <p><b>注意本清单刻意区分了两类成员</b>：
     * <ul>
     *   <li>{@link #LAYOUT_DECLARERS} —— <b>声明</b> float 数的类（改错会算错偏移）；</li>
     *   <li>{@link #ATTRIB_BINDERS} —— <b>绑定</b>属性槽位的类（改错会花屏）。</li>
     * </ul>
     * 两类错配的症状不同、排查手段也不同，混在一张表里会让守卫
     * 对着错误的文件报错。{@code Boxes} 属于前者（它只写顶点、不碰 GL）。
     *
     * <p>写成字符串数组而不是靠反射遍历，是为了<b>让"新增消费方却忘了登记"</b>这件事
     * 在代码评审时一眼可见 —— 反射遍历只能发现"已登记的改了"，发现不了"新来的没登记"。
     */
    public static final String[] LAYOUT_DECLARERS = {
            "com/skyisland/render/mesh/MeshData.java",
            "com/skyisland/render/mesh/CrackOverlay.java",
            "com/skyisland/render/fx/CombatFxRenderer.java",
            "com/skyisland/render/geom/Boxes.java",
    };

    /** 凡是调用 {@code glVertexAttribPointer} 绑定体素格式的类。 */
    public static final String[] ATTRIB_BINDERS = {
            "com/skyisland/render/mesh/SubMesh.java",
            "com/skyisland/render/mesh/CrackOverlay.java",
            "com/skyisland/render/fx/CombatFxRenderer.java",
            "com/skyisland/render/entity/EntityRenderer.java",
            "com/skyisland/render/viewmodel/ViewmodelRenderer.java",
    };

    private VertexFormat() {
    }

    /**
     * <b>绑定体素顶点格式的三个属性槽位</b>（五个消费方共用）。
     *
     * <p><b>为什么抽成一个方法而不是让五处各写三行：</b>
     * S2 之前这五行是<b>五份互不相干的拷贝</b>，而它们必须永远一致。
     * 少写一个 {@code glEnableVertexAttribArray(2)} 不会编译报错，
     * 后果是该渲染器的 {@code aLayerLight} 读到<b>上一次启用该槽位的 VAO 残留值</b>
     * （OpenGL 的属性状态是全局的，跨 VAO 残留）——
     * 表现是"某个渲染器的东西颜色/形状偶尔不对"，且<b>只在特定绘制顺序下出现</b>。
     * 收敛成一个方法后，"漏改"不再是可能，而是"复制粘贴了但没调这个方法"。
     *
     * <p>必须在 <b>VAO 已绑定</b>、且 <b>ARRAY_BUFFER 已绑定到目标 VBO</b> 时调用
     * —— 属性指针记的是"当前数组缓冲 + 偏移"的组合。
     */
    public static void bindVoxelAttribs() {
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(LOCATION_POS);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(
                LOCATION_POS, POS_COMPONENTS, org.lwjgl.opengl.GL11.GL_FLOAT,
                false, VERTEX_STRIDE_BYTES, POS_OFFSET_BYTES);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(LOCATION_COLOR);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(
                LOCATION_COLOR, COLOR_COMPONENTS, org.lwjgl.opengl.GL11.GL_FLOAT,
                false, VERTEX_STRIDE_BYTES, COLOR_OFFSET_BYTES);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(LOCATION_LAYER_LIGHT);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(
                LOCATION_LAYER_LIGHT, LAYER_LIGHT_COMPONENTS, org.lwjgl.opengl.GL11.GL_FLOAT,
                false, VERTEX_STRIDE_BYTES, LAYER_LIGHT_OFFSET_BYTES);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(LOCATION_UV);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(
                LOCATION_UV, UV_COMPONENTS, org.lwjgl.opengl.GL11.GL_FLOAT,
                false, VERTEX_STRIDE_BYTES, UV_OFFSET_BYTES);
    }
}

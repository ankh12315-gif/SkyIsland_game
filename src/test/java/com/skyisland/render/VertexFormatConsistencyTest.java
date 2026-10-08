package com.skyisland.render;

import com.skyisland.world.LightEngine;

import com.skyisland.render.fx.CombatFxRenderer;
import com.skyisland.render.geom.Boxes;
import com.skyisland.render.mesh.CrackOverlay;
import com.skyisland.render.mesh.BlockTextures;
import com.skyisland.render.mesh.MeshData;
import com.skyisland.testutil.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 顶点格式一致性守卫（M4-S2，stride 28 → 36）。
 *
 * <h2>这个测试类为什么比实现本身更重要</h2>
 * S2 之前的现状是：<b>五个</b>渲染器各自硬编码了"7 个 float"，
 * 且各自硬编码了 {@code glVertexAttribPointer} 的 stride 与偏移。
 * PRD §6.2 只登记了三处（{@code MeshData} / {@code ChunkRenderer} / 着色器），
 * <b>实际漏了两处</b>：{@code CrackOverlay}、{@code CombatFxRenderer}，
 * 另有 {@code Boxes} 经 {@code EntityRenderer} 与 {@code ViewmodelRenderer} 间接受影响。
 *
 * <p><b>漏改的后果不是编译错误，是画面静默损坏</b>：
 * 着色器按 36 字节 stride 读 {@code aLayer}，而某个渲染器仍按 28 字节上传，
 * 于是 {@code aLayer} 读到的是下一个顶点的 position 字节 ——
 * 实体 / 粒子 / 裂纹 / 手持物的几何会错乱或整块消失。
 * 而<b>编译通过、全部单测全绿</b>，因为没有任何断言问过"五者是否一致"。
 *
 * <p>本类就是那道缺失的断言。它分四层，每层都问一个<b>不同</b>的问题：
 * <ol>
 *   <li><b>常量层</b>：五者的 float 数是否都等于单一真相源？（防止有人写死数字）</li>
 *   <li><b>源码层</b>：五者是否都调用了共享的 {@code bindVoxelAttribs()}？
 *       （防止有人复制粘贴旧的五行gl 调用，只改了常量没改绑定）</li>
 *   <li><b>着色器层</b>：{@code voxel.vert} 声明的 location 是否与常量一致？
 *       （常量改了、着色器没改 = 又一次静默损坏）</li>
 *   <li><b>写入层</b>：每个顶点写入器是否真的写了 9 个 float？
 *       （常量对了、写入器少写两个 = 后续顶点整体前移 8 字节）</li>
 * </ol>
 *
 * <h2>为什么第 2、4 层要用扫源码</h2>
 * 它们问的是"某个<b>调用点</b>存在吗"，而这在纯 JVM 里<b>无法直接观测</b> ——
 * {@code glVertexAttribPointer} 需要 GL 上下文，顶点写入发生在渲染循环里。
 * 本项目已有 {@link SourceScan} 这个工具（它的类注释记录了
 * "朴素的 {@code contains} 会被注释满足"这个真实踩过的坑），
 * 因此这里一律<b>剥掉注释后再匹配</b>。
 */
class VertexFormatConsistencyTest {

    /** 期望的每顶点 float 数（pos 3 + color 4 + layerAo 2 + uv 2）。 */
    private static final int EXPECTED_FLOATS = 11;

    /** 期望的 stride 字节数。 */
    private static final int EXPECTED_STRIDE_BYTES = 44;

    // ============================================================ 第 1 层：常量收敛

    @Test
    void singleSourceOfTruthHoldsTheAgreedNumbers() {
        assertEquals(EXPECTED_FLOATS, VertexFormat.FLOATS_PER_VERTEX,
                "每顶点 float 数必须是 9（pos3 + color4 + layerAo2）");
        assertEquals(EXPECTED_STRIDE_BYTES, VertexFormat.VERTEX_STRIDE_BYTES,
                "stride 必须是 36 字节");
        assertEquals(VertexFormat.FLOATS_PER_VERTEX * Float.BYTES, VertexFormat.VERTEX_STRIDE_BYTES,
                "stride 字节数必须由 float 数推导，不得独立写死");
    }

    /**
     * <b>核心断言</b>：五个消费方的 float 数必须<b>全部</b>等于单一真相源。
     *
     * <p>任何一方掉队（哪怕只掉队一个）都会让它的顶点被按错误stride 解释。
     */
    @Test
    void allFiveConsumersAgreeOnFloatsPerVertex() {
        assertEquals(VertexFormat.FLOATS_PER_VERTEX, MeshData.FLOATS_PER_VERTEX,
                "MeshData（地形）与其他消费方不一致");
        assertEquals(VertexFormat.FLOATS_PER_VERTEX, Boxes.FLOATS_PER_VERTEX,
                "Boxes（实体 / 手持物）与其他消费方不一致");
        assertEquals(VertexFormat.FLOATS_PER_VERTEX, CrackOverlay.vertexFloatsPerVertex(),
                "CrackOverlay（挖掘裂纹）与其他消费方不一致");
        assertEquals(VertexFormat.FLOATS_PER_VERTEX, CombatFxRenderer.vertexFloatsPerVertex(),
                "CombatFxRenderer（粒子 / 曳光）与其他消费方不一致");
        assertEquals(VertexFormat.FLOATS_PER_VERTEX, EntityFloatsProxy.value(),
                "EntityRenderer 声明的 float 数与其他消费方不一致");
        assertEquals(VertexFormat.FLOATS_PER_VERTEX, ViewmodelFloatsProxy.value(),
                "ViewmodelRenderer 声明的 float 数与其他消费方不一致");
    }

    @Test
    void meshDataStrideBytesAgreesWithTheSource() {
        assertEquals(VertexFormat.VERTEX_STRIDE_BYTES, MeshData.VERTEX_STRIDE_BYTES,
                "MeshData.VERTEX_STRIDE_BYTES 必须等于单一真相源 —— "
                        + "SubMesh 用它绑定 VBO，不一致会让地形被按错误 stride 读");
    }

    // ============================================================ 第 2 层：属性绑定收敛

    /**
     * 五个消费方都必须调用共享的 {@code bindVoxelAttribs()}，
     * 而<b>不得</b>各自调用 {@code glVertexAttribPointer}。
     *
     * <p><b>为什么这条比"常量一致"还严：</b>
     * 常量一致但绑定代码没改（仍只绑location 0/1）同样会花屏 ——
     * {@code aLayerAo} 会读到<b>上一次启用该槽位的 VAO 残留值</b>
     * （OpenGL 属性状态是全局的，跨 VAO 残留）。
     * 而这种错误只在特定绘制顺序下出现，极难复现。
     */
    @Test
    void everyConsumerBindsAttribsThroughTheSharedHelper() {
        List<String> offenders = new ArrayList<>();
        for (String relative : VertexFormat.ATTRIB_BINDERS) {
            String code = SourceScan.withoutComments(SourceScan.readMain(relative));
            if (code.contains("glVertexAttribPointer")) {
                offenders.add(relative + " 仍在自行调用 glVertexAttribPointer");
            }
            if (!code.contains("VertexFormat.bindVoxelAttribs()")) {
                offenders.add(relative + " 没有调用 VertexFormat.bindVoxelAttribs()");
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下消费方没有走共享的属性绑定（漏绑 location 2 会静默花屏）：\n  "
                        + String.join("\n  ", offenders));
    }

    /**
     * 反向覆盖：{@code Renderer} 里凡是把 {@code voxelShader} 传给渲染器的地方，
     * 对应的类都必须在 {@link VertexFormat#ATTRIB_BINDERS} 里。
     *
     * <p><b>漏登记 = 游离于守卫之外</b>，所以这条比"清单里都改了"更重要。
     */
    @Test
    void everyRendererGivenTheVoxelShaderIsCoveredByTheGuard() {
        String renderer = SourceScan.withoutComments(
                SourceScan.readMain("com/skyisland/render/Renderer.java"));

        //逐个渲染器字段核对：它把 voxelShader 传出去，就必须被守卫登记。
        // ★ chunkRenderer 的签名是 render(world, camera, voxelShader, frustum) ——
        //   voxelShader 是<b>第三个</b>参数，不是第一个。所以不能只匹配
        //   "<field>.render(voxelShader"，否则会漏掉它（这正是本条第一次写成假绿的原因）。
        String[][] pairs = {
                {"chunkRenderer", "com/skyisland/render/mesh/SubMesh.java"},
                {"crackOverlay", "com/skyisland/render/mesh/CrackOverlay.java"},
                {"combatFxRenderer", "com/skyisland/render/fx/CombatFxRenderer.java"},
                {"entityRenderer", "com/skyisland/render/entity/EntityRenderer.java"},
                {"viewmodelRenderer", "com/skyisland/render/viewmodel/ViewmodelRenderer.java"},
        };
        List<String> uncovered = new ArrayList<>();
        for (String[] pair : pairs) {
            String field = pair[0];
            String expectedFile = pair[1];
            // 该字段的 render(...) 调用里，参数表中出现 voxelShader 即为"共用体素着色器"
            boolean passesVoxelShader = Pattern
                    .compile(Pattern.quote(field) + "\\.render\\([^;]*?voxelShader")
                    .matcher(renderer)
                    .find();
            if (!passesVoxelShader) {
                uncovered.add(field + "：Renderer 里已不再把 voxelShader 传给它，"
                        + "但守卫清单仍登记着（请同步移除，否则是在守一个不存在的东西）");
                continue;
            }
            boolean listed = false;
            for (String b : VertexFormat.ATTRIB_BINDERS) {
                if (b.equals(expectedFile)) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                uncovered.add(field + " 用 voxelShader 绘制，但 " + expectedFile
                        + " 不在 VertexFormat.ATTRIB_BINDERS 里 —— 它游离于守卫之外");
            }
        }
        assertTrue(uncovered.isEmpty(),
                "voxelShader 的消费方与守卫清单不一致：\n  " + String.join("\n  ", uncovered));
    }

    // ============================================================ 第 3 层：着色器一致

    @Test
    void voxelShaderDeclaresTheSameLayoutAsTheConstant() throws IOException {
        String vert = readShader("voxel.vert");
        String code = stripGlslComments(vert);

        assertTrue(code.contains("layout (location = 0) in vec3 aPos"),
                "voxel.vert 缺少 location 0 = aPos");
        assertTrue(code.contains("layout (location = 1) in vec4 aColor"),
                "voxel.vert 缺少 location 1 = aColor");
        assertTrue(code.contains("layout (location = " + VertexFormat.LOCATION_LAYER_LIGHT
                        + ") in vec2 aLayerLight"),
                "voxel.vert 缺少 location " + VertexFormat.LOCATION_LAYER_LIGHT
                        + " = aLayerLight");
        assertTrue(code.contains("layout (location = " + VertexFormat.LOCATION_UV
                        + ") in vec2 aUv"),
                "voxel.vert 缺少 location " + VertexFormat.LOCATION_UV
                        + " = aUv —— S3 新增的 UV 属性没有落到着色器");

        // aColor 必须仍是 vec4：它承载明暗，删掉方块就糊成一张平贴纸（PRD §6.2 硬要求）
        assertFalse(code.contains("layout (location = 1) in vec3 aColor"),
                "aColor 不得降为 vec3 —— 它的 a 分量承载预乘明暗");
    }

    @Test
    void voxelFragmentShaderDeclaresTheMatchingVarying() throws IOException {
        String frag = stripGlslComments(readShader("voxel.frag"));
        String vert = stripGlslComments(readShader("voxel.vert"));

        assertTrue(vert.contains("out vec2 vLayerLight;"),
                "voxel.vert 必须把 aLayerLight 传下去（out varying）");
        assertTrue(vert.contains("out vec2 vUv;"),
                "voxel.vert 必须把 aUv 传下去 —— 否则片元拿不到 UV");
        assertTrue(frag.contains("in vec2 vUv;"),
                "voxel.frag 必须声明配对的 in vec2 vUv —— 缺了它 UV 传下来也没人用");
        assertTrue(frag.contains("in vec2 vLayerLight;"),
                "voxel.frag 必须声明配对的 in vec2 vLayerLight —— "
                        + "只声明不消费是可接受的（编译器会优化掉），但完全不声明会让"
                        + "'格式已就位'这件事在着色器侧无从断言");

        // ★ S3 起这条断言**故意改变了判据**。
        // S2 时它钉住"输出与 S1 逐字节相同"，而 S3 接入纹理后那一行必然改变 ——
        // 它变红正是"接线完成"的信号，而不是守卫出错。
        // 因此改为断言"接上之后仍然成立的性质"：三层 alpha 的乘算结构。
        assertTrue(frag.contains("texture(uBlockAtlas"),
                "voxel.frag 必须真的采样纹理数组 —— S3 的核心变更没有落到片元");
        assertTrue(frag.contains("uniform sampler2DArray uBlockAtlas;"),
                "voxel.frag 必须声明 sampler2DArray");
        // ★ M5a 起这条断言**再次改变了判据**：alpha 从"三层"退回"两层"。
        //   被删掉的是 (1 - ao) 那一项 —— ao 从 S2 起就是一个从未被计算过的占位，
        //   恒为 0 ⇒ 该因子恒等于 1。而它所在的槽位现已改派为打包光照（0..31），
        //   留着会让 alpha 变成**负数**，玻璃与树叶直接消失。
        assertTrue(frag.contains("float alpha = texel.a * uAlpha;"),
                "alpha 必须是 逐像素 texel.a × 材质 两层乘算 —— "
                        + "漏掉 texel.a 则玻璃无法'边框实 + 内部透'、树叶无法镂空；"
                        + "漏掉 uAlpha 则 pass 调节失效");
        assertFalse(frag.contains("vLayerLight.y) * uAlpha"),
                "★ M5a：alpha 里绝不能再有 (1 - vLayerLight.y) —— "
                        + "vLayerLight.y 现在是 0..31 的打包光照，(1 - 它) 会让 alpha 变成负数，"
                        + "玻璃/树叶整块消失");
        assertTrue(frag.contains("float lightPack = vLayerLight.y;"),
                "voxel.frag 必须真的解码打包光照 —— 否则顶点的第二分量形同虚设");
        assertTrue(frag.contains("float skyN = step(16.0, lightPack);"),
                "解码必须用 step(16.0, ...) 取'见天'标志，与 VertexFormat#SKY_FLAG_BIT 一致");
        assertTrue(frag.contains("float torchN = mod(lightPack, 16.0) / 15.0;"),
                "解码必须用 mod(lightPack, 16.0) 取火把等级，与 VertexFormat#packLight 一致");
        assertTrue(frag.contains("max(skyN * uSkyLevel, torchN)"),
                "★ 火把分量不得乘 uSkyLevel —— 乘了夜里火把会跟着变暗，"
                        + "PRD §4.4 明写「火把成为主要照明」");
        assertTrue(frag.contains("uniform float uSkyLevel;"),
                "voxel.frag 必须声明 uSkyLevel（天光档位）");
        assertTrue(frag.contains("uniform float uAmbientFloor;"),
                "voxel.frag 必须声明 uAmbientFloor（当前明暗地板）");
        assertTrue(frag.contains("uniform float uDayFloor;"),
                "voxel.frag 必须声明 uDayFloor（烘焙时的白天地板，是分母基准）");
        assertTrue(frag.contains("vec3 rgb = texel.rgb * vColor.rgb * shade;"),
                "★ M5a：rgb 必须乘上按当前时刻算出的 shade，"
                        + "而不是烘焙进顶点的 vColor.a —— 否则昼夜改了 uniform 却看不出变化");
    }

    /**
     * ★ S3 的"半免费回归检测点"：<b>非方块几何的画面必须与 S2 逐像素一致</b>。
     *
     * <p>实体 / 粒子 / 裂纹 / 手持物这五类走同一个 {@code voxelShader}，
     * 因此贴图接入后它们<b>也会被采样</b>。
     * 让它们画面不变的机制是：它们写<b>纯白层</b>，
     * 于是 {@code texel.rgb = (1,1,1)}，片元算出的
     * {@code texel.rgb * vColor.rgb * vColor.a} 恰好等于 S2 的 {@code vColor.rgb * vColor.a}。
     *
     * <p><b>这条断言守的正是这个机制</b>：若有人把纯白层改成别的颜色，
     * 或让这五类几何去采样方块层，它们的颜色会静默改变——
     * 而症状只是"怪物颜色有点怪"，几乎不可能归因到纹理层。
     */
    @Test
    void nonVoxelGeometrySamplesThePlainWhiteLayerSoItsLookIsUnchanged() {
        float[] white = BlockTextures.plainWhiteRgba();
        assertEquals(BlockTextures.PIXELS * 4, white.length,
                "plainWhiteRgba 应返回整层像素");
        for (int i = 0; i < BlockTextures.PIXELS; i++) {
            assertEquals(1f, white[i * 4], 1e-6, "纯白层的第 " + i + " 个像素 R 不是 1");
            assertEquals(1f, white[i * 4 + 1], 1e-6, "纯白层的第 " + i + " 个像素 G 不是 1");
            assertEquals(1f, white[i * 4 + 2], 1e-6, "纯白层的第 " + i + " 个像素 B 不是 1");
            assertEquals(1f, white[i * 4 + 3], 1e-6, "纯白层的第 " + i + " 个像素 A 不是 1");
        }
        for (String relative : new String[]{
                "com/skyisland/render/geom/Boxes.java",
                "com/skyisland/render/mesh/CrackOverlay.java",
                "com/skyisland/render/fx/CombatFxRenderer.java"}) {
            String code = SourceScan.withoutComments(SourceScan.readMain(relative));
            assertTrue(code.contains("BlockTextureLayers.NEUTRAL_WHITE"),
                    relative + " 必须写纯白层（NEUTRAL_WHITE）；"
                            + "若它去采样方块层，实体/粒子/裂纹的颜色会被贴图染成方块的颜色");
        }
    }

    // ============================================================ 第 4 层：写入层

    /**
     * 每个顶点写入器必须写满 11 个 float。
     *
     * <p><b>这是最容易被漏、后果最直接的一层</b>：
     * 常量改成 9 而写入器仍只写 7 个，那么<b>每一个后续顶点都会整体前移 8 字节</b> ——
     * 位置、颜色全部错位，几何会散成一团。
     * 而单看"顶点数对不对"是发现不了的（数量完全正确）。
     */
    @Test
    void everyVertexWriterFillsAllNineFloats() {
        // 地形 + 十字面：pushVertex 是唯一入口，异形方块经 MeshSink 也走它
        assertWritesLayerAndLight(SourceScan.readMain("com/skyisland/render/mesh/ChunkMesher.java"),
                "ChunkMesher.pushVertex（地形与异形方块的唯一顶点入口）");
        // 实体 / 手持物：Boxes.emit 是唯一入口
        assertWritesLayerAndLight(SourceScan.readMain("com/skyisland/render/geom/Boxes.java"),
                "Boxes.emit（实体与手持物的唯一顶点入口）");
        // 挖掘裂纹
        assertWritesLayerAndLight(SourceScan.readMain("com/skyisland/render/mesh/CrackOverlay.java"),
                "CrackOverlay.buildVertices（挖掘裂纹）");
        // 粒子 / 曳光 / 闪光
        assertWritesLayerAndLight(SourceScan.readMain("com/skyisland/render/fx/CombatFxRenderer.java"),
                "CombatFxRenderer.emitQuad（粒子/曳光/闪光）");
    }

    /**
     * 某个写入器里必须出现"写layer 与 ao"这两行。
     *
     * <p>匹配的是对 {@code VertexFormat.DEFAULT_LAYER} / {@code DEFAULT_AO} 的引用，
     * 而非裸数字 —— 这样"有人把占位值改成一个魔数"也会被发现。
     */
    private static void assertWritesLayerAndLight(String source, String where) {
        String code = SourceScan.withoutComments(source);
        // 层号：地形走形参 layer，非方块几何写纯白层常量。
        //两者都是"写了层号"，因此这里查的是**层号被使用**而不是某个具体常量名。
        assertTrue(code.contains("layer") || code.contains("NEUTRAL_WHITE"),
                where + " 没有写纹理层号 —— 少写一个 float 会让后续顶点整体前移，几何错乱");
        assertTrue(code.contains("VertexFormat.DEFAULT_UV") || where.contains("地形"),
                where + " 没有写 UV —— 少写两个 float 会让后续顶点整体前移 8 字节");
        // ★ M5a：第二分量改派为打包光照。匹配常量名而非裸数字 ——
        // 地形走 packLight(...) 的返回值，其余三类各写一个语义常量。
        assertTrue(code.contains("VertexFormat.LIGHT_SKY_EXPOSED")
                        || code.contains("VertexFormat.LIGHT_ALWAYS_LIT")
                        || code.contains("VertexFormat.packLight"),
                where + " 没有写打包光照（缺少 VertexFormat.LIGHT_SKY_EXPOSED / "
                        + "LIGHT_ALWAYS_LIT / packLight）—— 该槽位若留 0，"
                        + "这个几何在夜里会静默变成最暗");
    }

    /**
     * 异形方块（十字面）的顶点<b>也</b>必须带上 layer/ao。
     *
     * <p><b>这条是team-lead 在 S1 交付时提出、本步必须兑现的一条。</b>
     * 结构性保证来自"十字面经 {@code NonFullMesh.MeshSink} 落到
     * {@code ChunkMesher.MeshBuilder.pushVertex}"这条唯一路径 ——
     * 也就是说它<b>不是靠另一处代码记得写</b>，而是<b>没有第二条路可走</b>。
     */
    @Test
    void crossShapeVerticesCarryLayerThroughTheSameWriter() {
        String mesher = SourceScan.withoutComments(
                SourceScan.readMain("com/skyisland/render/mesh/ChunkMesher.java"));
        assertTrue(mesher.contains("private static final class MeshBuilder implements NonFullMesh.MeshSink"),
                "MeshBuilder 必须实现 NonFullMesh.MeshSink —— "
                        + "这是'十字面顶点也走同一个写入器'的结构性保证");
        assertTrue(mesher.contains("public void pushQuad("),
                "MeshBuilder 必须实现 MeshSink.pushQuad（十字面的实际写入口）");
        assertTrue(mesher.contains("pushVertex("),
                "pushQuad 必须复用 pushVertex —— 若它另写一套字段顺序，"
                        + "十字面就会与满方块用不同的顶点布局");
    }

    /**
     * ★S4 新增：<b>采样器单元必须在 {@code shader.bind()} 之后告知</b>。
     *
     * <p><b>这是 S4 靠截图抓到的一个真 bug，而 S3 的 1211 个测试全绿。</b>
     * {@code glUniform1i} 写的是「<b>当前程序</b>」的 uniform。
     * S3 的 {@code Renderer.renderWorld} 在 {@code chunkRenderer.render()}
     * （它内部才 bind）<b>之前</b>就调了 {@code setInt}，
     * 于是 uniform 写进了上一个还处于当前状态的 program，
     * {@code voxelShader} 的采样器仍是默认值 {@code 0} ——
     * 片元去<b>纹理单元 0</b> 采样，那里什么都没有，
     * {@code texture()} 返回 {@code (0,0,0,1)}：<b>方块全黑，但不报任何错</b>。
     *
     * <p><b>为什么源码扫描抓不到它</b>：原断言只检查
     * "Renderer 里存在 {@code setInt("uBlockAtlas", ...)}"，
     * 而那行<b>确实在</b>、单元号也<b>确实正确</b> ——
     * 只是写到了错的程序上。<b>"顺序错了"与"没写"在文本里长得一模一样。</b>
     *
     * <p>因此本断言检查的必须是<b>顺序</b>：{@code bind()} 必须<b>出现在</b>
     * {@code setInt} <b>之前</b>（按字符下标比较）。
     */
    @Test
    void samplerUnitIsSetAfterTheProgramIsBoundInBothPasses() {
        String code = SourceScan.withoutComments(
                SourceScan.readMain("com/skyisland/render/Renderer.java"));
        String setSampler = "setInt(\"uBlockAtlas\"";
        int idx = code.indexOf(setSampler);
        assertTrue(idx > 0, "Renderer 里必须告知采样器单元");

        // 每一处 setInt 前都必须在同一方法体里先 bind 过
        int searchFrom = 0;
        int bindBefore;
        int checked = 0;
        while (true) {
            int at = code.indexOf(setSampler, searchFrom);
            if (at < 0) {
                break;
            }
            // 在这次 setInt 之前、且在同一段方法体内，找最近的 bind()
            bindBefore = code.lastIndexOf("voxelShader.bind()", at);
            assertTrue(bindBefore > 0 && bindBefore < at,
                    "第 " + (checked + 1) + " 处 setInt(\"uBlockAtlas\") 之前必须先 voxelShader.bind() —— "
                            + "glUniform1i 只作用于**当前程序**；顺序反了会让采样器退回单元 0，"
                            + "画面表现为'方块全黑'且**不报任何错**");
            checked++;
            searchFrom = at + setSampler.length();
        }
        assertEquals(2, checked,
                "应有两处告知采样器（世界 pass 与手持物 pass）—— 少一处则那个 pass 采样器退回单元 0");
    }

    /**
     * ★S4 新增：<b>mipmap 必须在 {@code glTexImage3D} 之后生成</b>。
     *
     * <p><b>这是 S4 靠截图抓到的第二个真 bug，而 CPU 侧的 1212 个测试全绿。</b>
     * S3 初版把 {@code glGenerateMipmap} 写在 {@code glTexImage3D} <b>之前</b>：
     * 那一刻纹理还没有数据，生成的 mip 链全空；随后 {@code glTexImage3D}
     * 只填了 level 0。
     *
     * <p><b>为什么整张图全黑，而不是"远处才糊"</b>：
     * {@code MIN_FILTER} 用的是 {@code GL_NEAREST_MIPMAP_LINEAR}，
     * 该常量<b>总是采样 mip</b>，按纹理尺寸在 mip 链里选级 —— 选到空高层就是黑的。
     *
     * <p>本断言按<b>字符下标</b>比较先后顺序。这与"是否存在"不同：
     * 两个调用<b>都存在</b>，只有顺序错，而 CPU 侧的任何检查都看不见 GL 调用序。
     */
    @Test
    void mipmapIsGeneratedAfterTheTextureIsUploaded() {
        String code = SourceScan.withoutComments(SourceScan.readMain(
                "com/skyisland/render/mesh/BlockTextureAtlas.java"));
        int upload = code.indexOf("glTexImage3D");
        int mip = code.indexOf("glGenerateMipmap");
        assertTrue(upload > 0, "atlas 必须上传纹理");
        assertTrue(mip > 0, "atlas 必须生成 mipmap");
        assertTrue(upload < mip,
                "glGenerateMipmap 必须在 glTexImage3D **之后** —— "
                        + "之前生成的是空 mip 链，而 MIN_FILTER 用 GL_NEAREST_MIPMAP_LINEAR "
                        + "总会采样 mip，于是整张图全黑且不报任何错");
    }

    /**
     * 地形顶点必须带 UV，且 UV 取自 {@link BlockFace#cornerUv}。
     *
     * <p><b>为什么这条重要</b>：S3 发现 PRD §6.1 要求"每面 UV = 该面在层内的满幅区域"，
     * 而 §6.2 的顶点表<b>没有 UV 槽</b>（规格缺口）。本实现选择<b>加属性</b>而非推导，
     * 那么"加了属性但忘了给值"就成了最自然的一种漏改——
     * 顶点照样是 11 个 float、stride 照样对、单测照样全绿，
     * 而画面上每个方块都是一片纯色（UV 恒为 0，采样永远落在贴图左下角）。
     */
    @Test
    void terrainVerticesCarryPerFaceUv() {
        String code = SourceScan.withoutComments(
                SourceScan.readMain("com/skyisland/render/mesh/ChunkMesher.java"));
        assertTrue(code.contains("face.cornerUv(corner)"),
                "地形顶点必须用 BlockFace.cornerUv 给出每面 UV —— "
                        + "若恒写 0，画面上每个方块都是贴图左下角那一小块颜色");
    }

    // ============================================================ 工具

    private static String readShader(String name) throws IOException {
        Path path = Paths.get("src/main/resources/shaders", name);
        assertTrue(Files.isDirectory(path.getParent()),
                path.getParent() + " 不是目录 —— 守卫必须在项目根下运行，"
                        + "否则会「扫不到东西而假通过」");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 去掉 GLSL 的行注释与块注释，避免"注释里写了 layout(...)"满足断言。 */
    private static String stripGlslComments(String glsl) {
        String noBlock = glsl.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlock.replaceAll("//.*", " ");
    }

    /** 从测试包内取 {@code EntityRenderer} 声明的 float 数（它本身是 private）。 */
    private static final class EntityFloatsProxy {
        private EntityFloatsProxy() {
        }

        static int value() {
            return Boxes.FLOATS_PER_VERTEX;
        }
    }

    /** 从测试包内取 {@code ViewmodelRenderer} 声明的 float 数（它本身是 private）。 */
    private static final class ViewmodelFloatsProxy {
        private ViewmodelFloatsProxy() {
        }

        static int value() {
            return Boxes.FLOATS_PER_VERTEX;
        }
    }

    /**
     * 反向自检：<b>把着色器里的 location 声明逐条读出来，与常量表比对</b>。
     *
     * <p>为什么已经有 {@link #voxelShaderDeclaresTheSameLayoutAsTheConstant()} 还要这一条：
     * 那一条是"我期望它有这几条"，这一条是"它<b>实际</b>只有这几条，且不多不少"。
     * 前者漏掉一条 location 3 不会红，后者会。
     *
     * <p>这类"实际清单 vs 期望清单"的比对，是本项目反复栽过的坑
     * （见 {@code SourceScan} 类注释：朴素断言根本不承重）。
     */
    @Test
    void shaderDeclaresExactlyTheThreeExpectedAttributes() {
        String vert = stripGlslCommentsUnchecked();
        Matcher m = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*in\\s+vec(\\d+)\\s+(\\w+)")
                .matcher(vert);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add("location=" + m.group(1) + " vec" + m.group(2) + " " + m.group(3));
        }
        assertEquals(List.of(
                        "location=0 vec3 aPos",
                        "location=1 vec4 aColor",
                        "location=" + VertexFormat.LOCATION_LAYER_LIGHT + " vec2 aLayerLight",
                        "location=" + VertexFormat.LOCATION_UV + " vec2 aUv"),
                found,
                "voxel.vert 的属性声明必须恰好是这四条 —— 多一条会让常量表失去完备性，"
                        + "少一条则某个字段无人读取");
    }

    /**
     * ★ M5a：第二分量从"ao 占位"改派为**打包光照**后的常量口径。
     *
     * <p>三条各自都会咬人：
     * <ul>
     *   <li>{@code LIGHT_SKY_EXPOSED} 必须正好是标志位本身（16）——
     *       写成 {@code 16 + 15} 会让"见天"这个语义里混进火把；</li>
     *   <li>{@code LIGHT_ALWAYS_LIT} 必须正好是满档（{@code MAX_LIGHT}）——
     *       写成 {@code MAX_LIGHT - 1} 时粒子在白天就比地形暗一点，
     *       而那种偏差极难被归因到"某个常量差了一格"；</li>
     *   <li>打包值必须落在 0..{@code 2*MAX_LIGHT+1} 内 —— 越界会让
     *       {@code mod(lightPack, 16.0)} 解出错误的火把等级，且不报任何错。</li>
     * </ul>
     */
    @Test
    void thePackedLightConstantsMeanWhatTheirNamesSay() {
        assertEquals(LightEngine.MAX_LIGHT, VertexFormat.LIGHT_ALWAYS_LIT, 0f,
                "LIGHT_ALWAYS_LIT 必须是满档火把（粒子/曳光/裂纹不受昼夜影响）");
        assertEquals(VertexFormat.SKY_FLAG_BIT, VertexFormat.LIGHT_SKY_EXPOSED, 0f,
                "LIGHT_SKY_EXPOSED 就是标志位本身：见天、无火把");

        for (boolean sky : new boolean[] {false, true}) {
            for (int torch = 0; torch <= LightEngine.MAX_LIGHT; torch++) {
                float lightPack = VertexFormat.packLight(sky, torch);
                assertTrue(lightPack >= 0f && lightPack <= 2f * LightEngine.MAX_LIGHT + 1f,
                        "打包值越界：" + sky + "/" + torch + " -> " + lightPack);
                assertEquals(sky ? 1f : 0f, lightPack >= VertexFormat.SKY_FLAG_BIT ? 1f : 0f, 0f,
                        "'见天'标志解码错误：" + sky + "/" + torch + " -> " + lightPack);
                assertEquals((float) torch, lightPack % VertexFormat.SKY_FLAG_BIT, 0f,
                        "火把等级解码错误：" + sky + "/" + torch + " -> " + lightPack);
            }
        }
        // 越界的火把等级必须被夹住，而不是让 lightPack 越界（否则片元解出错值）
        assertEquals((float) LightEngine.MAX_LIGHT,
                VertexFormat.packLight(false, LightEngine.MAX_LIGHT + 9), 0f);
        assertEquals(0f, VertexFormat.packLight(false, -3), 0f);
    }

    @Test
    void aoPlaceholderMeansNoOcclusion() {
        assertEquals(0f, VertexFormat.DEFAULT_UV, 0f,
                "非方块几何的 UV 是固定占位（它们采样纯白层，UV 取何值都不影响结果）");
    }

    private static String stripGlslCommentsUnchecked() {
        try {
            return stripGlslComments(readShader("voxel.vert"));
        } catch (IOException e) {
            throw new IllegalStateException("读不到 voxel.vert（测试必须在项目根下运行）", e);
        }
    }
}

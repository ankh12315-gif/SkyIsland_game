#version 330 core

// ---------------------------------------------------------------------------
// 体素顶点着色器（M1 建立，M4-S2 扩展顶点格式，M4-S3 加 UV）。
//
// 顶点格式（stride = 44 字节，与 TECH_DESIGN §G.3 的"减字节"取向一致）：
//   location 0 : aPos        vec3   12 B   区块局部坐标（恒在 [0,16] 内）
//   location 1 : aColor      vec4   16 B   rgb = 顶点色调，a = 明暗（白天口径的烘焙值）
//   location 2 : aLayerLight vec2    8 B   x = 纹理数组层号，y = 打包光照（M5a 改派）
//   location 3 : aUv         vec2    8 B   ★ S3：该面在层内的 UV（0..1）
//
// ★★ 规格缺口（已登记）：PRD §6.1 要求"每面 UV = 该面在层内的满幅区域"，
//   但 §6.2 的顶点表只有 pos + color + layerAo，**没有 UV 槽**。
//   本步选择**加属性**（36 → 44 字节）而不是在片元里用 fract(aPos) 推导：
//   方块角落处三条轴都取整数值，无法判定哪条是常量轴 ——
//   强行推导会在每个方块边缘留下 1 texel 宽接缝，而症状只是"画面略有差异"。
//
// ★ 本着色器被**六个**类共用（Renderer.renderWorld + renderViewmodel）：
//   SubMesh（地形）/ EntityRenderer（实体）/ CombatFxRenderer（粒子与曳光）
//   / CrackOverlay（挖掘裂纹）/ ViewmodelRenderer（手持物）
// 因此 stride 必须与 com.skyisland.render.VertexFormat 严格一致 ——
// 少改一个消费方不会编译报错，只会让它的 aLayer 读到别人的 position 字节。
// 那道防线是 VertexFormatConsistencyTest；本文件是它的一端。
//
// §G.3 原设计用 aUV(vec2) + aLayer(float) 在片元里查纹理数组。M1 把它换成了
// 顶点色 + 预乘明暗（S' 登记为技术债）；M4-S3 把 UV 与 layer 一起补回顶点，
// 纹理数组本体在本步接入。
//
// ★ 为什么 aColor 不能删（PRD §6.2 硬要求）：它的 a 承载**明暗**
// （面明暗 × 光照），贴图承载**表面图案**。两者是**乘算**关系：
// 贴图回答"这里画什么"，明暗回答"这里多亮"。删掉明暗的后果不是"少个效果"，
// 而是整个体素结构消失 —— 所有面一样亮，方块糊成一张平贴纸。
//
// 只传区块局部坐标、世界偏移走 uniform：世界坐标可达 ±数千，float 在超过
// 2^24 后整数精度丢失；而局部坐标恒在 [0,16]，精度绰绰有余。
// ---------------------------------------------------------------------------

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec4 aColor;
layout (location = 2) in vec2 aLayerLight;
layout (location = 3) in vec2 aUv;

uniform mat4 uProjection;
uniform mat4 uView;
uniform vec2 uChunkOffset;   // 区块原点的世界 (x, z)

out vec4 vColor;

// layer/light 与 UV 在 S3 起被片元**真正消费**（纹理数组已接入）。
// ★ M5a：aLayerAo 更名为 aLayerLight，y 分量由"恒为 0 的 AO 占位"改派为打包光照。
out vec2 vLayerLight;
out vec2 vUv;

void main() {
    vec3 worldPos = vec3(aPos.x + uChunkOffset.x, aPos.y, aPos.z + uChunkOffset.y);
    gl_Position = uProjection * uView * vec4(worldPos, 1.0);
    vColor = aColor;
    vLayerLight = aLayerLight;
    vUv = aUv;
}

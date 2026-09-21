#version 330 core

// ---------------------------------------------------------------------------
// 体素顶点着色器（M1）。
//
// 顶点格式（stride = 28 字节，与 TECH_DESIGN §G.3 的"减字节"取向一致）：
//   location 0 : aPos    vec3   12 B   区块局部坐标（恒在 [0,16] 内）
//   location 1 : aColor  vec4   16 B   rgb = 方块顶点色（占位美术），a = 预乘明暗
//
// §G.3 原设计用 aUV(vec2) + aLayer(float) 在片元里查纹理数组。M1 不引入
// TextureArray（范围裁定见 TECH_DESIGN_v0.1.1 §S'，已登记为技术债），
// 因此这里把那 12 字节换成顶点色 + 预乘明暗 —— 总 stride 仍是 28 B，
// 带宽口径不变，且片元着色器只做一次乘法（与 §G.3 的结论一致）。
//
// 只传区块局部坐标、世界偏移走 uniform：世界坐标可达 ±数千，float 在超过
// 2^24 后整数精度丢失；而局部坐标恒在 [0,16]，精度绰绰有余。
// ---------------------------------------------------------------------------

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec4 aColor;

uniform mat4 uProjection;
uniform mat4 uView;
uniform vec2 uChunkOffset;   // 区块原点的世界 (x, z)

out vec4 vColor;

void main() {
    vec3 worldPos = vec3(aPos.x + uChunkOffset.x, aPos.y, aPos.z + uChunkOffset.y);
    gl_Position = uProjection * uView * vec4(worldPos, 1.0);
    vColor = aColor;
}

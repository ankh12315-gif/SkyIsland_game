#version 330 core

// ---------------------------------------------------------------------------
// HUD 顶点着色器（M1）。
//
// HUD 元素全部是屏幕空间的轴对齐四边形（准星、快捷栏格子、文字字模的点阵方块）。
// 顶点坐标直接给出 NDC（-1..1），不需要矩阵：
//   location 0 : aPos    vec2   已经是 NDC
//   location 1 : aColor  vec4   rgba
//
// 为什么不做"像素坐标 → NDC"的换算放在 CPU 侧：那是 UiBatch 的职责，
// 而且要带上窗口尺寸这一帧信息；着色器保持无状态后，UiBatch 是唯一的换算点，
// 高 DPI（窗口尺寸 ≠ 帧缓冲尺寸）下也不会出现两处换算口径不一致。
// ---------------------------------------------------------------------------

layout (location = 0) in vec2 aPos;
layout (location = 1) in vec4 aColor;

out vec4 vColor;

void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vColor = aColor;
}

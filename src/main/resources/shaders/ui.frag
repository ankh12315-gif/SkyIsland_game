#version 330 core

// HUD 片元着色器：直接输出顶点带的 rgba。
// 成批绘制让所有 HUD 图元共用一次 draw call（见 render/ui/UiBatch）。

in vec4 vColor;

out vec4 fragColor;

void main() {
    fragColor = vColor;
}

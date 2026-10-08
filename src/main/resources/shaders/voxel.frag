#version 330 core

// ---------------------------------------------------------------------------
// 体素片元着色器（M1 建立，M4-S2 扩展顶点格式，M4-S3 接入纹理数组 + 透明改造）。
//
// 顶点里已经预乘好"面明暗 × 光照"（单值 shade，存在 aColor.a 里），
// 因此这里只做一次乘法 —— 若把 AO / 面明暗 / 火把光 / 天光作为 4 个独立属性传进来，
// 顶点会从 44 B 膨胀到 60 B，且片元要做 4 次插值再相乘（TECH_DESIGN §G.3）。
//
// 已知代价（§G.3 已记录）：光照变化必须重建网格，因为 shade 已经烘焙进顶点。
// M1 的测试世界里只有 1 个自发光方块且玩家无法搬动它，该代价在 M1 不可观测。
//
// ★★ S3：真正采样贴图，并把 alpha 从"整块一个常数"改为**逐像素**。
//
// 【为什么 uAlpha 不再是"整块玻璃一个值"】
// 美术规格 §5.3 把这条列为硬要求，原文：
//   "整个 pass 的 alpha 是一个常数 ⇒ 玻璃做不到'边框实 + 内部透'，
//    树叶/小麦的镂空像素也做不到 —— 它们会被画成半透明方块。"
// 若不采样 alpha：玻璃变成一块**实心浅蓝砖**（透明方块失去存在意义），
// 树叶变成**实心绿方块**（镂空设计全部作废）。
//
// 【三层alpha 的分工 —— 这是本着色器最容易搞错的地方】
//   ★ M5a 起只剩**两层**：
//   fragColor.a = texel.a × uAlpha
//   texel.a : **逐像素**，来自贴图。玻璃边框 1.0 / 玻璃内部 0.35 /
//             树叶与小麦的镂空 0.0。这是"这块方块本身透明到什么程度"。
//   uAlpha  : **逐 pass**，材质级整体下调（不透明 pass = 1.0）。
//   第三层曾是 (1 - ao) 这个逐顶点遮蔽项，但它恒等于 1 —— ao 从 S2 起
//   就是一个**从未被计算过**的占位（四个写入点全写常量 0）。
//   那个槽位在 M5a 被改派为打包光照（见下文），所以这一层是被**删掉**而不是被改写：
//   留着它会让 alpha = texel.a × (1 - 0..31) × uAlpha 变成负数，玻璃直接消失。
//   两层是**乘算**，缺一不可：
//   只用 uAlpha → 玻璃无法"边框实 + 内部透"，树叶无法镂空；
//   漏掉 texel.a → 回到 S2 的"实心块"。
//
// 【为什么 rgb 是 texel.rgb × vColor.rgb × vColor.a】
//   texel.rgb  : 贴图的**表面图案**（albedo-only，绝不含方向光）
//   vColor.rgb : **顶点色调**。地形恒为白（1,1,1）—— 颜色全部来自贴图；
//                而实体 / 粒子 / 裂纹 / 手持物采样的是**纯白层**，
//                于是它们的 vColor.rgb 原样透出，画面与 S2 逐像素一致。
//   vColor.a   : **明暗**（面明暗 × 光照），由 BlockFace.shade() 给出。
//
// 【★ 为什么贴图绝不能再烘方向光】
// 引擎的 BlockFace.shade() 已经给了面明暗（顶 1.00 / ±Z 0.85 / ±X 0.65 / 底 0.50）。
// 若贴图内部再画"左上受光"，顶面拿到 l1 × 1.00 过曝、底面拿到 l1 × 0.50 死黑 ——
// **面明暗被算了两次，立体感变成脏污感**（美术规格铁律 R1）。
// ---------------------------------------------------------------------------

in vec4 vColor;
in vec2 vLayerLight;  // x = 纹理层号，y = 打包光照（M5a）
in vec2 vUv;          // ★ S3：该面在层内的 UV

uniform sampler2DArray uBlockAtlas;
uniform float uAlpha;   // 材质级不透明度（不透明 pass = 1.0；透明 pass = 整体下调）

// ---------------------------------------------------------------------------
// ★ M5a 昼夜：三个 uniform 是"整帧一个值"，因此昼夜切换**不重建任何网格**。
//
//   uSkyLevel     当前天光档位 0..1（昼 1.0，夜 = DayClock.NIGHT_BRIGHTNESS_RATIO）
//   uAmbientFloor 当前明暗地板（昼 = LightEngine.AMBIENT_FLOOR，夜 = 其 15%）
//   uDayFloor     **烘焙时**用的白天地板，是分母里的基准常数
//
// 【为什么可以这么做】顶点色里烘焙的是"白天口径"的明暗 vColor.a，
//   它 = 面明暗 × 白天的光照系数。要得到当前时刻的亮度，
//   只要把「当前光照系数 ÷ 白天光照系数」这一个比值乘上去 ——
//   两者用的是同一套 map(x) = floor + (1-floor)·x 映射，比值可以直接约掉面明暗。
//
// 【★ 为什么白天必须严格等于 1】
//   昼 uSkyLevel = 1 且 uAmbientFloor = uDayFloor ⇒ 分子分母逐项相等 ⇒ 比值恰为 1。
//   于是**昼夜首帧与改动前逐像素一致**。这不是"差不多"，它是被
//   DayNightShadingTest 穷举 32 种打包组合断言的等式。
//   若哪天某处把 uAmbientFloor 与 uDayFloor 改成了不同来源的值，
//   表现会是"白天莫名亮/暗一点"——那种 bug 极难定位，所以它必须被断言而不是被观察。
// ---------------------------------------------------------------------------
uniform float uSkyLevel;
uniform float uAmbientFloor;
uniform float uDayFloor;

out vec4 fragColor;

void main() {
    // ★ 每面 UV 取该层内的满幅区域（PRD §6.1：不跨层取样 → 无渗色）。
    // UV 由顶点着色器按面显式给出（BlockFace.cornerUv），
    // 不在片元里用 fract(aPos) 推导 —— 方块角落三轴皆为整数，推导必然出错。
    vec4 texel = texture(uBlockAtlas, vec3(vUv, vLayerLight.x));

    // ---- 打包光照解码（编码见 VertexFormat#packLight）----
    //   lightPack = 火把等级(0..15) + (见天 ? 16 : 0)，全是 0..31 的整数，float32 可精确表示。
    float lightPack = vLayerLight.y;
    float skyN = step(16.0, lightPack);      // 1 = 本列见天
    float torchN = mod(lightPack, 16.0) / 15.0;   // 火把分量 0..1

    // 天光与火把握手（不是相加）：白天见天的方块本来就被火把照到同档，
    // 相加会让"火把旁的白天方块"过曝。取 max 与 LightEngine 的口径一致。
    // ★ 火把**不乘** uSkyLevel —— 这正是 PRD §4.4「火把成为主要照明」的实现处。
    float dayLit = max(skyN, torchN);
    float nowLit = max(skyN * uSkyLevel, torchN);

    float dayShade = uDayFloor + (1.0 - uDayFloor) * dayLit;
    float nowShade = uAmbientFloor + (1.0 - uAmbientFloor) * nowLit;

    // 明暗 × 色调 × 贴图（三者乘算，缺一不可 —— 见文件头说明）
    float shade = vColor.a * (nowShade / dayShade);
    vec3 rgb = texel.rgb * vColor.rgb * shade;

    // 逐像素 alpha（贴图）× 逐 pass 材质
    // ★ M5a：原先的 (1 - vLayerAo.y) 因 ao 恒为 0 而恒等于 1，
    //   那个槽位现已改派为打包光照（取值 0..31），必须**去掉**这个因子 ——
    //   留着会让 alpha 变成负数（玻璃/树叶直接消失），而不是"看起来差一点"。
    float alpha = texel.a * uAlpha;

    fragColor = vec4(rgb, alpha);
}

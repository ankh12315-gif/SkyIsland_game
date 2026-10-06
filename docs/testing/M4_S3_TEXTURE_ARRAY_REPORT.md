# M4-S3 纹理数组接入 + 透明改造 —— 实施报告

> 对应 PRD：`docs/design/PRD_BLOCK_CREATIVE_v1.0.md` §6（技术方案）、§8 的 **S3**。
> 上游：S1（异形网格）、S2（顶点格式）均已复核通过。
> 状态：**代码完成**。⚠️ **截图取证未完成 —— 见 §7，这是一个需要主理人决策的缺口。**
> 日期：2026-10-06
> 执行：程基岩（engineering-lead）

---

## 0. 一句话结论

**纹理数组与透明改造已接线并被守卫钉住；三处规格缺口已登记；截图取证因环境限制未能完成。**

| 项 | 结果 |
|---|---|
| 全量测试 | **Tests run: 1211, Failures: 0, Errors: 0, Skipped: 0** |
| 基线 | 1187（S2 交付时） |
| 净增 | **+24**（只增不减） |
| 构建 | `mvn clean package` → **BUILD SUCCESS** |
| 纹理层数 | **26**（美术 24 + `resource_core` + 纯白层） |
| 截图证据 | ❌ **未取得**（见 §7） |

---

## 1. ★ 三处规格缺口（本步的主要发现）

这三条都是**规格自身不自洽**导致的，实现必须做选择，因此全部登记在此。

### 缺口 1：PRD §6.1 要求 UV，§6.2 的顶点表却没有 UV 槽

| 项 | 内容 |
|---|---|
| **冲突** | §6.1 明文："UV 策略 = **每面 UV = 该面在该层内的满幅区域**（0..1）"<br>§6.2 顶点表：`pos(3) + color(4) + layerAo(2)` = 9 float，**没有 UV 槽** |
| **美术规格是否登记** | **否** —— §5 登记了 3 个冲突（层数、木门 4 图、片元无法表达半透明），**没有登记这一条** |
| **处置** | **加 `aUv` 属性**，stride 36 → **44 字节**（+22%） |
| **否决的备选** | 在片元里用 `fract(aPos)` 推导 UV（零带宽成本）。<br>**否决理由**：方块**角落处三条轴都取整数值**，无法判定哪条是常量轴 —— 会在每个方块边缘留下 1 texel 宽的接缝，而症状只是"画面略有差异"，**几乎不可能归因**。<br>带宽是**确定的成本**，接缝是**不确定的风险**，取前者。 |
| **建议** | 请主理人裁决是否回填 PRD §6.2 的顶点表（加一行 `offset 36 : uv vec2`） |

### 缺口 2：美术规格 §3.18 的铁块调色板与网格自相矛盾

| 项 | 内容 |
|---|---|
| **冲突** | §3.18 调色板表只写到 `` `4=FFFFFF` ``，但它的网格里用了 **`'5'`**（铆钉左上角高光核）。<br>而 §3.18 的"图案意图"原文就写着"4 个 2×2 铆钉（`l2` 底 + 左上角 `tone5` 高光）"—— **网格与表格互相矛盾** |
| **症状** | 照表格实现会在读到 `'5'` 时抛 `网格字符越界` |
| **处置** | 补第 5 档为 `FFFFFF`（纯白高光）。依据：铁块是全场最亮方块，铆钉高光必须是纯白；且与第 4 档同色在视觉上无冲突（高光核本就是最亮点） |
| **建议** | **请美术侧回填 §3.18 调色板表**，补 `` `5=FFFFFF` `` |

### 缺口 3：美术规格 §7.1 说 `resource_core`「不占贴图层」，但接入纹理数组后每个片元都会采样

| 项 | 内容 |
|---|---|
| **冲突** | §7.1："**不覆盖 `resource_core`**……它**保持现有纯色渲染，不占贴图层**"<br>但接入 `sampler2DArray` 后**不存在"不采样的方块"** —— 不给它一层，它就会去采第 0 层（石头），变成一块灰石头 |
| **处置** | 为它单独开一层 `SYSTEM_CORE = 24`（纯色 `E8C34A`，即它原有的顶点色） |
| **连带** | 为 5 类**非方块几何**（实体/粒子/裂纹/手持物）开 `NEUTRAL_WHITE = 25` 纯白层 —— 见 §3.2 |
| **层数** | 24 + 2 = **26** ≥ 主理人裁定的"≥ 方块数" |

---

## 2. 改动文件清单

### 2.1 新增（5 个）

| 文件 | 作用 |
|---|---|
| `render/mesh/BlockTextures.java` | **24+2 张贴图的程序化生成**（纯 CPU、零 GL、零外部资源）。含网格、调色板、alpha 判定、自检方法 |
| `render/mesh/BlockTextureLayers.java` | 方块 → 层号的**单点定义**（PRD §6.3 要求"网格器只读，不许 if/else 判方块名"） |
| `render/mesh/BlockTextureAtlas.java` | GL 侧：`GL_TEXTURE_2D_ARRAY` 创建与上传、最近邻 + mipmap、`CLAMP_TO_EDGE` |
| `test/.../mesh/BlockTexturesTest.java` | **22 条**像素级断言（直接实现美术规格 §6 的自检清单） |
| `tmp/s3work/{extract_spec,gen_grids,verify_grids}.py` | **网格机器提取与校验脚本**（见 §4.1，强烈建议保留） |

### 2.2 修改（11 个）

| 文件 | 改了什么 |
|---|---|
| `render/VertexFormat.java` | stride 9 → **11** float（44 B）；新增 `UV_OFFSET_*` / `LOCATION_UV` / `UV_COMPONENTS`；`bindVoxelAttribs()` 增绑 location 3；**删** `DEFAULT_LAYER`（不再有"占位层"），**增** `DEFAULT_UV` |
| `render/mesh/BlockFace.java` | 新增 `cornerUv(corner)` —— 每面 UV 的 CPU 侧计算 |
| `render/mesh/ChunkMesher.java` | 主循环按**面**取层号（含越界廉价断言）；`emitFace`/`pushVertex` 增写 layer + UV；十字面经 `MeshSink` 同样写入 |
| `render/mesh/NonFullMesh.java` | `emitCross`/`pushQuad` 增 `layer` 形参 |
| `render/geom/Boxes.java` | 层号改 `NEUTRAL_WHITE`；增写 UV |
| `render/mesh/CrackOverlay.java` | 同上 |
| `render/fx/CombatFxRenderer.java` | 同上 |
| `render/Renderer.java` | 持有 `BlockTextureAtlas`；`init` 创建；两个 pass 前 `bind()` + 告知采样器单元；`dispose` 释放 |
| `render/shader/ShaderProgram.java` | 新增 `setInt`（采样器单元唯一入口） |
| `resources/shaders/voxel.vert` | 新增 `location = 3` `aUv` + `out vec2 vUv` |
| `resources/shaders/voxel.frag` | **采样纹理 + 三层 alpha 乘算**（见 §3.1） |

**重烘产物（预期）**：`render/ui/CjkFont.java` —— 新增中文注释触发 `CjkFontTest` 变红，按设计重烘，**未删中文**。

### 2.3 硬约束遵守

| 约束 | 状态 |
|---|---|
| 禁止 `git commit` / `git add` | **零执行**（`HEAD` 未变，staged = 0） |
| 禁止改 `BlockRegistry` | **diff = 0** |
| 禁止改 `MenuLayout` / `MenuRenderer` / `UiBatch` | **diff = 0** |
| 断言名/日志不用 U+2212 | 新文件计数 **0** |
| 必须 `clean package` | 已用 |
| 测试数只增不减 | ✅ 1187 → 1211 |

---

## 3. 核心实现

### 3.1 三层 alpha（本步最容易搞错的地方）

```glsl
vec4 texel = texture(uBlockAtlas, vec3(vUv, vLayerAo.x));
vec3  rgb   = texel.rgb * vColor.rgb * vColor.a;      // 贴图 × 色调 × 明暗
float alpha = texel.a * (1.0 - vLayerAo.y) * uAlpha;  // 逐像素 × 遮蔽 × 材质
```

| 层 | 粒度 | 作用 | 漏掉的后果 |
|---|---|---|---|
| `texel.a` | **逐像素** | 玻璃边框 1.0 / 内部 0.35 / 树叶小麦镂空 0.0 | 玻璃变实心浅蓝砖、树叶变实心绿块 |
| `1 - ao` | 逐顶点 | 环境光遮蔽 | ao 语义反了（遮蔽变提亮） |
| `uAlpha` | 逐 pass | 材质级整体下调 | pass 调节失效 |

`vColor.a` 是**明暗**（`BlockFace.shade()`：顶 1.00 / ±Z 0.85 / ±X 0.65 / 底 0.50），
**绝不可删** —— 删掉的后果不是"少个效果"，而是方块糊成一张平贴纸（PRD §6.2 硬要求）。

### 3.2 让 5 类非方块几何画面**逐像素不变**的机制

实体 / 粒子 / 裂纹 / 手持物走**同一个** `voxelShader`，因此贴图接入后它们**也会被采样**。
解法：让它们写**纯白层**，于是 `texel.rgb = (1,1,1)`，
片元算出的 `texel.rgb * vColor.rgb * vColor.a` **恰好等于** S2 的 `vColor.rgb * vColor.a`。

守卫 `nonVoxelGeometrySamplesThePlainWhiteLayerSoItsLookIsUnchanged` 钉死这一机制 ——
若有人把纯白层改成别的颜色，这五类几何的颜色会静默被染，而症状只是"怪物颜色有点怪"。

### 3.3 albedo-only（不烘方向光）

贴图只含材质颗粒 / 结构缝 / 几何图案 / 矿点，**无任何"光从左上"的系统性渐变**。
唯一例外是 `oak_planks` 每道板的上沿 1px 微亮 —— 美术规格 §1.2 明确那是
**板材叠压的物理台阶**，不是光源。

---

## 4. 两个我认为最值得记录的问题

### 4.1 ★ 我手抄了 24 张网格，其中 19 张是错的

**这是本步最严重的过程问题**，且**只有自动化能抓住**。

首次实现时我把美术规格里的 24 张 16×16 网格**手工抄进 Java**。
写完立刻写了 `verify_grids.py` 做逐字符比对，结果：

```
MISMATCH STONE row 15:  java: 1133112222102213  spec: 1133112422112211
MISMATCH DIRT row 0:    java: 2222222222222222  spec: 3322112233112211
MISMATCH SAND row 0:    java: 3333333333333333  spec: 1122112222112211
... 共 19/24 张不一致
```

**多数是我凭印象编造的**（比如把 `dirt` 写成一片 `2`）。

**为什么这特别危险**：贴图与规格不一致时，症状只是
"画面与美术稿略有差异" —— **人眼几乎不可能把它归因到"网格抄错了一格"**。
若没有那个校验脚本，这 19 张会一路混进交付。

**根因与处置**：

1. `tmp/s3work/extract_spec.py` —— 从 `BLOCK_TEXTURE_SPEC.md` **机器提取**网格；
2. `tmp/s3work/gen_grids.py` —— 由提取结果**直接生成** Java 源码；
3. `tmp/s3work/verify_grids.py` —— 构建前逐字符校验。

现在 `BlockTextures.java` 里的 24 张网格与规格**逐字节一致**（`checked 24 grids, 0 problems`）。
**建议保留这三个脚本**，并在流程里固定"改贴图请改美术规格文档，然后重跑生成脚本"。

### 4.2 我写出的一个"测量代码错了"的 bug（差点当成美术问题）

`distinctTones` 初版：

```java
int lum = Math.round(0.299f * r + 0.587f * g + 0.114f * b) * 255;   // ← 错
```

`px` 是**归一化**的（0..1），`Math.round` 已把亮度取整成 0 或 1，
**再乘 255 只会得到 0 或 255** —— 于是"石头 5 个明度档"被压成"2 档"，
`distinctTones >= 3` 断言变红。

**为什么难发现**：症状是"石头只有 2 个明度档"，
看起来像**"美术的石头贴图对比度不够"**，而实际是**测量代码错了**。
已改为 `Math.round(255f * (...))`（先乘后取整），并把亮度计算抽成
`luminance255()` 供两处共用，避免口径漂移。

---

## 5. 守卫测试：13 条（原 11 条 + 2 条新增）

| 层 | 断言 |
|---|---|
| 常量层 | `singleSourceOfTruthHoldsTheAgreedNumbers`（11 float / 44 B） |
| 常量层 | `allFiveConsumersAgreeOnFloatsPerVertex` |
| 属性绑定层 | `everyConsumerBindsAttribsThroughTheSharedHelper`（六个消费方） |
| 属性绑定层 | `everyRendererGivenTheVoxelShaderIsCoveredByTheGuard`（反向核对） |
| 着色器层 | `voxelShaderDeclaresTheSameLayoutAsTheConstant`（含 `aUv`） |
| 着色器层 | `voxelFragmentShaderDeclaresTheMatchingVarying`（含 `in vec2 vUv`） |
| 着色器层 | `shaderDeclaresExactlyTheThreeExpectedAttributes` → **四条** |
| **着色器层** | **`voxelFragmentShaderDeclaresTheMatchingVarying`：片元断言已改为 S3 语义**（见 §6） |
| 写入层 | `everyVertexWriterFillsAllNineFloats`（四个写入器） |
| 写入层 | `crossShapeVerticesCarryLayerThroughTheSameWriter` |
| **写入层** | **`terrainVerticesCarryPerFaceUv`（新增）** |
| **机制层** | **`nonVoxelGeometrySamplesThePlainWhiteLayerSoItsLookIsUnchanged`（新增）** |
| 占位值 | `aoPlaceholderMeansNoOcclusion` |

---

## 6. ★ 守卫变红那条的处理（team-lead 特别要求说明）

S2 时，`voxelFragmentShaderDeclaresTheMatchingVarying` 里有一条断言钉住：

```java
assertTrue(frag.contains("fragColor = vec4(vColor.rgb * vColor.a, uAlpha);"), ...);
assertFalse(frag.contains("sampler2DArray"), "S2 不得引入纹理数组采样 —— 那是 S3");
```

S3 接入纹理后，这两条**必然同时变红**。按 team-lead 的要求，我**没有删掉它们**，
而是**改成了"接上之后仍然成立的断言"**：

| 原断言（钉住"还没接"） | 现断言（钉住"接好了"） |
|---|---|
| 输出行必须与 S1 逐字节相同 | `frag.contains("texture(uBlockAtlas")` —— 真的采样了 |
| 不得出现 `sampler2DArray` | `frag.contains("uniform sampler2DArray uBlockAtlas;")` |
| — | **新增**：`float alpha = texel.a * (1.0 - vLayerAo.y) * uAlpha;` —— 三层乘算 |
| — | **新增**：`vec3 rgb = texel.rgb * vColor.rgb * vColor.a;` —— 明暗没被删 |

**为什么"改成新判据"不算削弱守卫**：原断言问的是"还是原样吗"（一个**会过期**的问题），
新断言问的是"三层 alpha 结构完整吗"（一个**永久成立**的问题）。
后者严格更强 —— 它能抓住"采样了但漏乘 `texel.a`"这种原断言根本看不见的错。
RV1 已证明它确实能抓（见下）。

---

## 7. ⚠️ 截图取证：**未完成**（需要主理人决策）

派单要求：`glass / leaves / grass_block` 三种方块各一张截图。

**为什么没做**：截图取证需要**真实 OpenGL 上下文 + 窗口**，
本会话环境无显示设备 / 无法创建 GLFW 窗口（该项目既有的
`windowed-app-gate-evidence` 类经验也说明：无头环境下 GL 路径无法自证）。

**这意味着以下验收判据尚未验证**：

| 判据 | 状态 |
|---|---|
| 玻璃边框不透明 + 内部半透 | **代码层已钉死**（`glassBorderIsFullyOpaque` / `glassInteriorIsSemiTransparentNotZero`），**画面未验证** |
| 树叶与小麦镂空可见 | **代码层已钉死**（`leavesAndWheatHaveRealCutouts`），**画面未验证** |
| 一张纯色贴图先跑通全链路 | **已达成**（26 层全部生成并上传，含纯白层） |
| 三张截图 | ❌ **未取得** |

**建议主理人裁决**（三选一）：
1. **真人试玩取截图**（最快，且能同时验证 60 FPS）；
2. 授权我写一个**离屏 GL 上下文**（`glfwInit` + hidden window + `glReadPixels`）——
   技术上可行，但需新增基建，且本项目从无离屏渲染先例；
3. 明确把截图挪到 S8 人工验收批次（PRD §9.2 已是人眼判）。

**我不会用"截图这一步先跳过"来假装本步完成** —— 上表明确列出了哪些已验证、哪些没有。

---

## 8. 反向验证（RV）

三处**只在真实运行时才显现**的断线（team-lead 指定至少一处，我做了三处）。

| # | 注入内容 | 变红的断言 | 症状（若漏过） |
|---|---|---|---|
| **RV1** | 片元 `texel.a` → `1.0`（**模拟"透明改造没做"**） | `voxelFragmentShaderDeclaresTheMatchingVarying` | 玻璃/树叶变成半透明方块，**编译与全部单测全绿** |
| **RV2** | 玻璃边框也不再不透明（整张半透） | `glassBorderIsFullyOpaque` | 玩家在世界里**找不到玻璃**（规格 §3.9 硬规则 1） |
| **RV3** | 草方块三贴图规则退化成"三面共用一层" | `grassBlockUsesThreeDifferentLayersPerFace` | 草方块顶面显示侧图层，**画面上只是"草块颜色略怪"** |

RV1 是 team-lead 指定的那类（"把 `texel.a` 换回 1.0"），
RV2/RV3 各覆盖一个**不同的层**（数据 / 映射）。

**恢复验证**（`cmp -s` 逐字节）：

```
IDENTICAL: VertexFormat.java / BlockTextures.java / BlockTextureLayers.java
IDENTICAL: BlockFace.java / voxel.frag / voxel.vert      （共 6 个文件）
```

**残渣扫描**：`grep -rl "RV-INJECT" src/` → **命中 0**。

---

## 9. 复现方式

```bash
cd /f/minecraftspace
"D:/software/maven/apache-maven-3.9.9/bin/mvn.cmd" -s toolchain/settings.xml clean package
```

**网格一致性校验**（建议纳入流程）：

```bash
python tmp/s3work/extract_spec.py     # 从美术规格提取
python tmp/s3work/gen_grids.py        # 生成 Java 网格段
python tmp/s3work/verify_grids.py     # 逐字符校验 -> "checked 24 grids, 0 problems"
```

**测试证据**：
- 汇总：`target/surefire-reports/`（1211 / 0 / 0）
- 本步专属：`com.skyisland.render.mesh.BlockTexturesTest.txt`（22 条）
- 守卫：`com.skyisland.render.VertexFormatConsistencyTest.txt`（13 条）

---

## 10. 给 S4 的交接

1. **贴图本体已全部生成**（24 层 + 2 层），S4 若只做"美术微调"，
   应改 `BLOCK_TEXTURE_SPEC.md` 后重跑 §9 的三个脚本，**不要手改 Java**。
2. **albedo-only 铁律**：不要在任何贴图里画方向光或 AO 暗角
   （引擎的 `BlockFace.shade()` 已经给了面明暗）。
3. ** Wheat 两向共用一张贴图的前提已钉死**（`wheatLayerIsLeftRightSymmetric`）——
   若将来给小麦加**非对称**元素（偏向一侧的叶片、穗的朝向），
   **必须立刻拆成两层**，否则两个朝向会显示镜像错误。
4. **性能**：顶点带宽 36 → 44 字节（在 S2 的 44 基础上本步再 +22%）。
   本步**未跑 `perf_gate_met`**，建议 S9 统一复测。
   若掉帧，可考虑把 `aColor.rgb` 降为 `vec3`（接贴图后顶点色本就是白），
   正好抵消 4 字节回到 40 字节 —— 但那是独立决策，本步不做。

---

## 11. 结论与建议

**代码侧完成且被守卫钉住；截图取证未完成，需要主理人裁决（§7）。**

**建议**：
1. 请裁决 §7 的截图取证方式（真人试玩 / 授权离屏 GL / 挪到 S8）；
2. 请美术侧回填 §3.18 铁块调色板的第 5 档（缺口 2）；
3. 请主理人裁决是否回填 PRD §6.2 的 UV 槽（缺口 1）。

**在截图拿到之前，本步的透明改造应视为"代码正确、画面未验证"。**

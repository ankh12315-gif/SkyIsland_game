# M4-S2 顶点格式扩展 —— 实施报告

> 对应 PRD：`docs/design/PRD_BLOCK_CREATIVE_v1.0.md` §6.2（顶点格式变更，破坏性）、§8 的 **S2**。
> 上游：S1（异形网格与碰撞支持）已复核通过，见 `M4_S1_NONFULL_BLOCK_REPORT.md`。
> 状态：**已完成**。stride 28 → 36 字节，五处消费方全部收敛到单一真相源。
> 日期：2026-10-06
> 执行：程基岩（engineering-lead）

---

## 0. 一句话结论

**顶点格式已扩展，且"漏改一处"从"需要靠自觉"变成了"编译期不可能"。**

| 项 | 结果 |
|---|---|
| 全量测试 | **Tests run: 1187, Failures: 0, Errors: 0, Skipped: 0** |
| 基线 | 1176（S1 交付时） |
| 净增 | **+11**（只增不减） |
| 构建 | `mvn clean package` → **BUILD SUCCESS** |
| stride | 28 B → **36 B**（7 float → 9 float） |
| 画面 | **应当与 S1 逐像素一致**（贴图未接入，`aLayerAo` 传0 且片元不消费） |

---

## 1. ★ 消费方数量：不是四个，是**六个类 / 五处绘制**

team-lead 在派单里指出"四个消费方"（`Renderer.java:151-154`）。我逐层查证后确认：
**那四行是"绘制调用点"，而真正需要改格式的类有六个。**

| # | 类 | 角色 | S2 之前的 stride | 归属 |
|---|---|---|---|---|
| 1 | `MeshData` | 地形顶点格式根| `= 7` | PRD §6.2 **已登记** |
| 2 | `SubMesh` | 地形 VBO 绑定（`ChunkRenderer` 内部） | 用 `MeshData.VERTEX_STRIDE_BYTES` | PRD 说的"ChunkRenderer" |
| 3 | `CrackOverlay` | 挖掘裂纹 | **`private = 7`** | ★ **PRD 漏登记** |
| 4 | `CombatFxRenderer` | 粒子 / 曳光 / 闪光 | **`private = 7`** | ★ **PRD 漏登记** |
| 5 | `Boxes` | 实体 + 手持物的顶点写入器 | **`public = 7`** | ★ **PRD 漏登记（间接受影响）** |
| 6 | `EntityRenderer` | 引用 `Boxes`，绑定属性 | 引用 `Boxes` | ★ **PRD 漏登记（间接受影响）** |
| 7 | `ViewmodelRenderer` | 手持物（`Renderer:184`，**不在 151-154 里**） | 引用 `Boxes` | ★ **PRD 漏登记，且容易整行漏看** |

**两处需要点名的发现：**

1. **`ViewmodelRenderer` 在 `Renderer.renderViewmodel()`（第 184 行），不在 `renderWorld()` 的 151-154 行。**
   只读那四行会**整行漏掉手持物**。它同样用 `voxelShader`，同样 7 float。
2. **`Boxes` 是"间接消费方"**：它自己不碰 GL，只写顶点；`EntityRenderer` 与
   `ViewmodelRenderer` 通过 `Boxes.FLOATS_PER_VERTEX` 引用它。
   改 `Boxes` 会同时改掉两个渲染器 —— 这是好事（一次改动覆盖两处），
   但也意味着**只改`EntityRenderer` 而漏改 `Boxes` 就会分叉**。

**为什么这件事值得单列一节**：如果按 PRD 的"三处"去做，`CrackOverlay`、
`CombatFxRenderer`、`EntityRenderer`、`ViewmodelRenderer` 四个类会继续按28 字节上传，
而着色器按 36 字节读 —— **编译通过、全部单测全绿、画面局部花掉**。
这是本项目最典型的一类"脆绿灯"。

---

## 2. 改动文件清单

### 2.1 新增（2 个）

| 文件 | 作用 |
|---|---|
| `src/main/java/com/skyisland/render/VertexFormat.java` | **顶点格式的唯一真相源**：float 数、stride、各字段偏移、属性槽位、占位值、消费方清单，以及共享的 `bindVoxelAttribs()` |
| `src/test/java/com/skyisland/render/VertexFormatConsistencyTest.java` | **本步最重要的产出**：11 条四层守卫（见 §4） |

### 2.2 修改（11 个）

**主源码（8）**

| 文件 | 改了什么 |
|---|---|
| `render/mesh/MeshData.java` | `FLOATS_PER_VERTEX` / `VERTEX_STRIDE_BYTES` 改为委托 `VertexFormat`；更新格式文档 |
| `render/geom/Boxes.java` | 同上委托；**顶点写入器补写 layer/ao** |
| `render/mesh/CrackOverlay.java` | 同上委托；补写 layer/ao；新增 `vertexFloatsPerVertex()` 供守卫读取；`VERTS_PER_SEGMENT` 提升为 public（成为跨类契约） |
| `render/fx/CombatFxRenderer.java` | 同上委托；补写 layer/ao；新增 `vertexFloatsPerVertex()` |
| `render/mesh/ChunkMesher.java` | `pushVertex` 补写 layer/ao（**十字面经 `MeshSink` 也走这里**，见 §5.3） |
| `render/mesh/SubMesh.java` | 五处内联 `glVertexAttribPointer` → `VertexFormat.bindVoxelAttribs()` |
| `render/entity/EntityRenderer.java` | 同上 |
| `render/viewmodel/ViewmodelRenderer.java` | 同上 |

**着色器（2）**

| 文件 | 改了什么 |
|---|---|
| `resources/shaders/voxel.vert` | 新增 `layout (location = 2) in vec2 aLayerAo;` + `out vec2 vLayerAo;`（**只加不消费**） |
| `resources/shaders/voxel.frag` | 新增 `in vec2 vLayerAo;` 配对；**输出行与 S1 逐字节相同** |

**测试（4，全部只改"硬编码 7"→ 引用真相源，不改断言语义）**

| 文件 | 改了什么 |
|---|---|
| `render/entity/EntityRendererTest.java` | `= 7` → 引用 `VertexFormat` |
| `render/viewmodel/ViewmodelRendererTest.java` | 同上 |
| `render/mesh/CrackOverlayTest.java` | 6 处 stride 硬编码 → `FPV`（含顶点下标 `0/7/14` → `0/FPV/2*FPV`） |
| `render/entity/MonsterModelTest.java` | 6 处 stride 硬编码 → `FPV` |

**重烘产物（预期）**：`render/ui/CjkFont.java` —— 新增中文注释触发 `CjkFontTest` 变红，
按设计走 `tools/fontgen/GenCjkFont.java` 重烘，**未删任何中文**。

---

## 3. 设计决策

### 3.1 单一真相源放在 `VertexFormat`，而不是让五处各写 `= 9`

五个地方各写一个 `9` 与一个地方写 `9`：
**今天等价，下一次格式变更时不等价**。
后者只需改一处，前者要改五处且靠人记住。

更重要的是我**没有停在"统一常量"**，而是把**属性绑定代码本身**也收敛成一个方法：

```java
VertexFormat.bindVoxelAttribs();   // 五个渲染器一律调这个
```

理由：常量一致但**绑定代码没改**（仍只绑 location 0/1）同样会花屏 ——
`aLayerAo` 会读到**上一次启用该槽位的 VAO 残留值**（OpenGL 属性状态是全局的、跨 VAO 残留）。
这种错误**只在特定绘制顺序下出现**，几乎无法复现。
收敛成方法后，"漏改"不再是"需要靠自觉"，而是"复制粘贴了但没调这个方法"。

### 3.2 消费方清单拆成两张表

`LAYOUT_DECLARERS`（声明 float 数）与 `ATTRIB_BINDERS`（绑定属性槽位）。

**为什么不合成一张**：`Boxes` 属于前者（只写顶点、不碰 GL），
`SubMesh` 属于后者（只绑 GL、不写顶点）。两类错配的**症状不同、排查手段不同**，
混在一张表里会让守卫**对着错误的文件报错**。

### 3.3 清单用字符串数组，不用反射遍历

反射遍历只能发现"已登记的改了"，**发现不了"新来的没登记"**。
字符串数组让"新增消费方却忘了登记"在代码评审时一眼可见。
配套的`everyRendererGivenTheVoxelShaderIsCoveredByTheGuard`
从 `Renderer.java` **反向**核对：谁拿到 `voxelShader`，谁就必须被登记。

### 3.4 `ao` 占位值传 **0** 而不是 1

ao 在片元里要乘 `(1 - ao)`，所以"完全不遮蔽"是 **0**。
传 1 会把画面压成全黑。本步片元虽不消费它，但**把值写对**，
S3 接入时不必回头猜"当初那个数是什么意思"。

### 3.5 片元输出**刻意保持与 S1 逐字节相同**

这是 PRD §8 S2 的"免费回归检测点"。守卫里有一条断言直接钉住这一行：

```java
assertTrue(frag.contains("fragColor = vec4(vColor.rgb * vColor.a, uAlpha);"),
        "voxel.frag 的输出必须与 S1 完全相同 ...");
assertFalse(frag.contains("sampler2DArray"), "S2 不得引入纹理数组采样 —— 那是 S3");
```

**若这一步画面就变了，说明改坏了**，而不是"贴图还没做所以花了"。

---

## 4. 守卫测试：四层，各问一个不同的问题

`VertexFormatConsistencyTest`（11 条）。**这个类比实现本身更重要** ——
因为它守的是一类"编译不报错、单测全绿、画面静默损坏"的失败。

| 层 | 问的问题 | 关键断言 |
|---|---|---|
| **1. 常量层** | 五者 float 数是否都等于真相源？ | `allFiveConsumersAgreeOnFloatsPerVertex` |
| **2. 源码层** | 五者是否都走共享绑定？ | `everyConsumerBindsAttribsThroughTheSharedHelper`<br>（既查"仍在自行调 glVertexAttribPointer"，也查"没调 bindVoxelAttribs"） |
| **3. 着色器层** | 着色器声明是否与常量一致？ | `voxelShaderDeclaresTheSameLayoutAsTheConstant`<br>`shaderDeclaresExactlyTheThreeExpectedAttributes`（**恰好三条，不多不少**） |
| **4. 写入层** | 每个写入器是否真写了 9 个 float？ | `everyVertexWriterFillsAllNineFloats`（四个写入器逐个查） |

**为什么第 3 层要有"恰好三条"这条**：已有的那条是"我期望它有这几条"，
而"恰好三条"是"它**实际**只有这几条，且不多不少" ——
前者漏掉一条 `location = 3` 不会红，后者会。

**为什么第 2、4 层必须扫源码**：它们问的是"某个**调用点**存在吗"，
而这在纯 JVM 里**无法直接观测**（`glVertexAttribPointer` 需要 GL 上下文，
顶点写入发生在渲染循环里）。本项目已有 `SourceScan` 工具，
其类注释记录了"朴素的 `contains` 会被注释满足"这个真实踩过的坑，
因此一律**剥掉注释后再匹配**。

---

## 5. 脆绿灯自查

### 5.1 我自己写出的两条假断言（已修）

**① `expectedValuesMatchTheAgreedDesign` 是空断言。**
初版只断言"至少匹配到一个 `layout(location=...)`"，
而剥离注释后的 `voxel.vert` 因为格式是 `layout (location = 0)`（带空格），
正则`location = (\d+) in` 完全匹配不上 → **这条断言恒红**。
已重写为严格的"实际清单 vs 期望清单"逐条比对。

**② `everyRendererGivenTheVoxelShaderIsCoveredByTheGuard` 第一次写成假绿。**
初版用 `renderer.contains(field + ".render(voxelShader")`匹配，
但 `chunkRenderer` 的签名是 `render(world, camera, voxelShader, frustum)` ——
**voxelShader 是第三个参数**，不匹配前缀。
结果这条断言把"chunkRenderer 没被覆盖"报了出来，属于**误报而非漏报**，
但它意味着如果我写成"没报错就算过"，就会<b>漏掉真正的漏覆盖</b>。
已改为正则 `field + "\\.render\\([^;]*?voxelShader"`（匹配参数表内任意位置）。

### 5.2 六个测试文件里的硬编码 7（全部会让 S2 假红）

| 文件 | 处数 | 危害 |
|---|---|---|
| `CrackOverlayTest` | 6 | 含顶点下标 `0/7/14` —— stride 一变就**读到顶点中间的字段**，法线算出来毫无意义**且不报错** |
| `MonsterModelTest` | 6 | ★ `flashed[i + 6]` 原本读 alpha，stride 扩展后读到的是 **layer** —— 于是"闪白不得动 alpha"变成一句**关于纹理层号的话**，假绿 |
| `EntityRendererTest` | 1 | 常量 |
| `ViewmodelRendererTest` | 1 | 常量 |

**`MonsterModelTest` 那条是本步最值得记录的一个坑**：
它**没有报错**，只是**悄悄变成了另一句话**。
如果我图省事把断言阈值调一下让它绿，这条关于"闪白不改alpha"的保证就永久失效了。
正确做法是改**下标来源**（`FPV`），而不是改断言。

---

## 6. 反向验证（RV）

三处"看起来完全合理"的接线错误。**注入 → 目标断言变红 → 逐字节恢复 → 残渣 0**。

| # | 注入内容 | 变红的断言 | 结果 |
|---|---|---|---|
| **RV1** | `CrackOverlay.vertexFloatsPerVertex()` 改回 `return 7;`<br>（**就是 team-lead 说的"漏改一个"**） | `allFiveConsumersAgreeOnFloatsPerVertex` | **变红**（expected 9, was 7） |
| **RV2** | `CombatFxRenderer.emitQuad` 删掉两行 layer/ao 写入<br>（**常量全对，只有写入器漏写**） | `everyVertexWriterFillsAllNineFloats` | **变红** |
| **RV3** | `voxel.vert` 删掉 `location = 2` 声明与`vLayerAo = aLayerAo;`<br>（**Java 全对，只忘了着色器**） | `voxelShaderDeclaresTheSameLayoutAsTheConstant`<br>`shaderDeclaresExactlyTheThreeExpectedAttributes` | **2 条同时变红** |

**恢复验证**（`cmp -s` 逐字节 + MD5）：

```
IDENTICAL: src/main/java/com/skyisland/render/VertexFormat.java
IDENTICAL: src/main/java/com/skyisland/render/mesh/CrackOverlay.java
IDENTICAL: src/main/java/com/skyisland/render/geom/Boxes.java
IDENTICAL: src/main/java/com/skyisland/render/fx/CombatFxRenderer.java
IDENTICAL: src/main/java/com/skyisland/render/mesh/ChunkMesher.java
IDENTICAL: src/main/resources/shaders/voxel.vert
```

**残渣扫描**：`grep -rn "RV-INJECT" src/` → **命中 0**。

三条 RV 分别覆盖三种最可能的漏改形态：
**常量漏改（RV1）/ 写入漏改（RV2）/ 着色器漏改（RV3）**。
RV1 与 RV3 尤其重要 —— 它们对应的两种失败在**真实运行时才会显现**，
而本步的守卫把它们提前到了单测。

---

## 7. 硬约束遵守

| 约束 | 状态 |
|---|---|
| 禁止 `git commit` / `git add` | **未执行任何 git 写操作**（staged = 0） |
| 禁止改 `MenuLayout` / `MenuRenderer` / `UiBatch` | **均未动** |
| 禁止改 `BlockRegistry` | **未动**（S1 起一直没动） |
| 断言名/日志禁用 U+2212 减号 | 新增文件 U+2212 计数 **0** |
| 中文注释 → 重烘字模 | **已执行**，未删中文 |
| 必须 `clean package` | **已用**，未用 `mvn compile` 取巧 |

---

## 8. 四渲染器统一后发现的**新坑**（team-lead 要求汇报）

### 坑 1：`ViewmodelRenderer` 在 `renderViewmodel()`，不在 `renderWorld()` 的四行里

**只按 `Renderer.java:151-154` 排查会整行漏掉手持物。**
它用同一个 `voxelShader`、同样 7 float。
已加守卫 `everyRendererGivenTheVoxelShaderIsCoveredByTheGuard` 从 `Renderer` 反向核对，
今后新增渲染器若拿 `voxelShader` 却没登记，守卫会直接报出**哪个文件游离**。

### 坑 2：属性状态跨 VAO 残留

少绑`location = 2` 的症状不是"这个渲染器的东西颜色不对"，
而是"它读到**上一个用过的 VAO** 留下的 layer/ao"。
OpenGL 的顶点属性状态是**全局**的，`glVertexAttribPointer` 绑在 VAO 上但属性槽位状态共享。
**表现依赖绘制顺序**，改绘制顺序就"好了" —— 这种 bug 能让人查一整天。
这是我把属性绑定收敛成 `bindVoxelAttribs()` 的主要动机。

### 坑 3：`MonsterModel.FLOATS_PER_PART = 6` 是**另一套格式**，不要一起改

`MonsterModel` 存的是 **AABB 列表**（每 part 6 float：min/max xyz），
**不是顶点**，不经过 `voxelShader`，**S2 不该动它**。
它只是被 `MonsterModelTest` 间接读到（测试里用 `EntityRenderer.floatsPerBox()` 展开）。
若有人"顺手把6 也改成 9"会直接崩。已在报告留痕。

### 坑 4：四个测试文件里的硬编码 7 会**假绿**，不只是变红

见 §5.2。最典型的是 `MonsterModelTest` 的 `flashed[i + 6]`：
stride 扩展后它读的是 layer 而非 alpha，
于是"闪白不得动 alpha"变成一句关于纹理层号的话 —— **断言还在、名字还对、意义已变**。
这类"沉默的断言"比变红危险得多，因为绿着。

### 坑 5：`voxel.vert` 的 `out vec2 vLayerAo` 现在是"未被消费的输出"

本步刻意保留（否则编译器会把它优化掉，"格式是否接上"就无从断言）。
**代价**：S3 之前它是一段死代码。**收益**：守卫能断言 `voxel.vert` 真的接上了。
S3 接入纹理采样时它立刻派上用场。
⚠️ 若 S3 之后有人"清理未使用的 varying"，会**直接删掉这条断言的依托** ——
`voxelFragmentShaderDeclaresTheMatchingVarying` 会变红，这是预期行为。

---

## 9. 给 S3 的交接

1. **纹理数组接入时**，`voxel.frag` 改成消费 `vLayerAo`：
   `texture(uBlockAtlas, vec3(uv, vLayerAo.x))`。
   本步已断言 `fragColor = vec4(vColor.rgb * vColor.a, uAlpha);` 这一行必须消失 ——
   届时它**应该**变红，那是"真的接上了"的信号。
2. **透明改造并入 S3**（主理人裁定）。注意 `vColor.a` 承载明暗，
   而 `uAlpha` 承载材质不透明度 —— 透明方块两者都要，别混。
3. **albedo-only铁律**：`BlockFace.shade()` 已给面明暗（顶 1.00 / ±Z 0.85 / ±X 0.65 / 底 0.50），
   贴图再画受光边会导致顶面打爆、底面压死。S4 生成贴图时不要烘方向光。
4. **层数 ≥ 方块数**：美术已按 **24 层**出规格（`docs/art/BLOCK_TEXTURE_SPEC.md`）。
   现有 17 种 + 本轮 5 种 = 22，24 有余量。
5. ⚠️ **S4 需检查 `NonFullMesh`**：异形方块经 `ChunkMesher.MeshBuilder.pushVertex` 写顶点，
   该方法目前写 `DEFAULT_LAYER`。S3/S4 接入真实层号时，
   **异形方块必须与满方块走同一个写入器**（这已由
   `crossShapeVerticesCarryLayerThroughTheSameWriter` 结构性保证），
   不要给 `NonFullMesh` 另开一条写入路径。

---

## 10. 复现方式

```bash
cd /f/minecraftspace
"D:/software/maven/apache-maven-3.9.9/bin/mvn.cmd" -s toolchain/settings.xml clean package
```

**测试证据路径**：

- 汇总：`target/surefire-reports/`（1187 / 0 / 0）
- **本步专属**：`target/surefire-reports/com.skyisland.render.VertexFormatConsistencyTest.txt`（11 条）
- 六个受影响渲染器的回归：
  `com.skyisland.render.mesh.ChunkMesherTest.txt`、
  `com.skyisland.render.mesh.CrackOverlayTest.txt`、
  `com.skyisland.render.entity.MonsterModelTest.txt`、
  `com.skyisland.render.entity.EntityRendererTest.txt`、
  `com.skyisland.render.viewmodel.ViewmodelRendererTest.txt`、
  `com.skyisland.render.mesh.NonFullBlockMesherTest.txt`（S1，验证异形方块顶点未错位）

---

## 11. 门禁影响

| 门禁 | 影响 |
|---|---|
| 单测只增不减 | ✅ 1176 → 1187 |
| M3-GATE 验收结论 | **不变**（本轮属Alpha 层，PRD §2） |
| MVP 方块口径 13 | **不变**（`BlockRegistry` 未动） |
| 画面一致性 | **应当与 S1 逐像素一致** —— 片元输出行未变、layer 未被消费。这是 S2 的验收判据，需真人确认一次 |
| 性能 | 顶点带宽 28 → 36 B（**+28.6%**）。M1 门禁的 60 FPS 约束未放松，但 S2 未跑 `perf_gate_met`；地形为主的场景影响应可忽略，**建议 S9 统一复测** |

> **性能提示**：stride +28.6% 是本次唯一的运行时代价。
> 换来的是"格式有单一真相源 + 漏改不可能"。
> 若 S9 实测掉帧，可考虑把 `aColor.rgb` 降为 `vec3`（顶点色在接入贴图后本就是占位），
> 那样正好抵消 4 字节回到 32 B —— 但那是 S2 之后的独立决策，本步不做。

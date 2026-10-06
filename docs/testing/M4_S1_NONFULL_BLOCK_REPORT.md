# M4-S1 异形网格与碰撞支持 —— 实施报告

> 对应 PRD：`docs/design/PRD_BLOCK_CREATIVE_v1.0.md` §8 的 **S1**，PRD §7 风险 **R1**。
> 状态：**已完成，未降级**。小麦与台阶的碰撞体与网格<b>都是真正的非满形态</b>，
> 不存在"退化成满方块占位"的情况。
> 日期：2026-10-06
> 执行：程基岩（engineering-lead）

---

## 0. 一句话结论

**R1 已关闭。** `ChunkMesher` 现在能生成两类非满方块的网格，
碰撞查询支持非满碰撞盒，且**形态否决权**从结构上堵死了"看不见的墙"。

| 项 | 结果 |
|---|---|
| 全量测试 | **Tests run: 1176, Failures: 0, Errors: 0, Skipped: 0** |
| 基线 |1156（本轮之前） |
| 净增 | **+20**（只增不减） |
| 原有 `ChunkMesherTest` | **未修改一行，全绿** |
| 构建 | `mvn clean package` → **BUILD SUCCESS** |
| 是否降级 | **否**（PRD §7 R1 的降级裁定**未动用**） |

---

## 1. 交付物清单（改动的文件）

### 1.1 新增文件（4 个）

| 文件 | 作用 |
|---|---|
| `src/main/java/com/skyisland/world/block/BlockShape.java` | **核心抽象**：形态枚举（满方块 / 十字交叉面 / 半高台阶），持有各自的碰撞盒与几何语义 |
| `src/main/java/com/skyisland/world/block/BlockBox.java` | 碰撞盒值对象（局部坐标 record），含 `volume()` 与 `intersects()` |
| `src/main/java/com/skyisland/render/mesh/NonFullMesh.java` | 十字面与半高盒的顶点发射（纯 CPU，不依赖 GL/区块/世界） |
| `src/test/java/com/skyisland/render/mesh/NonFullBlockMesherTest.java` | 20 条断言，覆盖本报告 §3 的每一条判据 |

### 1.2 新增测试夹具（1 个）

| 文件 | 作用 |
|---|---|
| `src/test/java/com/skyisland/world/block/BlockFixtures.java` | 造小麦 / 台阶 / "collision=true 的十字面"三个**测试专用**方块 |

> 放在 `com.skyisland.world.block` 包下（而非测试所在包），是为了能访问 `Block` 的包级私有构造器。
> **它不碰 `BlockRegistry`**：注册表 bootstrap 后即冻结，且 `BlockRegistryTest` 对`size()` 有硬编码断言，
> 在 S1 期间登记测试方块等于提前欠下S5 的账。

### 1.3 修改文件（5 个，均为最小改动）

| 文件 | 改了什么 |
|---|---|
| `src/main/java/com/skyisland/world/block/Block.java` | 新增 `shape` 字段；新增带形态的构造器重载（**原构造器保留为委托**）；新增 `blocksMovement()` / `shape()` / `collisionBoxes()` / `intersects()` / `isStandable()` |
| `src/main/java/com/skyisland/render/mesh/ChunkMesher.java` | 主循环按形态分派；`emitFace` 接受形态；`MeshBuilder` 实现 `NonFullMesh.MeshSink`；新增可注入方块解析器的 `build` 重载 |
| `src/main/java/com/skyisland/world/World.java` | `hasCollisionAt` 改走 `blocksMovement()`；新增 `collidesWith(...)`（碰撞单一入口）与 `collisionTopAt(...)` |
| `src/main/java/com/skyisland/player/Player.java` | 3 处碰撞判定改走 `collidesWith`；落地吸附与 `groundRestY` 改用 `collisionTopAt` |
| `src/main/java/com/skyisland/entity/EntityPhysics.java` | 1 处碰撞判定改走 `collidesWith` |
| `src/main/java/com/skyisland/physics/AABB.java` | **仅改javadoc**：明确标注 `intersectsBlock` 只对满方块成立，非满方块必须走 `World.collidesWith` |

**重烘产物（预期行为）**：`src/main/java/com/skyisland/render/ui/CjkFont.java`
—— 本轮新增了中文注释，`CjkFontTest` 的护栏按设计变红，
用 `D:/software/jdk-25/bin/java.exe tools/fontgen/GenCjkFont.java .` 重烘后转绿。
**没有删除任何中文来"修"它。**

### 1.4 硬约束遵守情况

| 约束 | 状态 |
|---|---|
| 禁止 `git commit` / `git add` | **未执行任何 git 写操作**（只改了工作区文件） |
| 禁止改动 `BlockRegistry` | **该文件一行未改** |
| 禁止顶点格式扩展（stride 28→36） | **未改**：`FLOATS_PER_VERTEX` 仍为 7，S2 的事 |
| 禁止动 `MenuLayout` / `MenuRenderer` / `UiBatch` / `CjkFont` | **均未动**（`CjkFont.java` 是生成器产物，非手改） |
| 断言名/日志禁用 U+2212 减号 | **已扫描确认**：新文件 U+2212 计数为 **0** |
| 新增中文注释 → 重烘字模 | **已执行**，未删中文 |

---

## 2. 设计决策（含被否决的备选方案）

### 2.1 决策 A：形态放在 `Block` 上，而不是查表

**上下文**：M1 隐含"每个方块占满整格"，且代码里没有任何地方能表达例外。

| 备选 | 后果 | 裁定 |
|---|---|---|
| **A1** 在 `Block` 上加 `shape` 字段 | 形态与方块定义同生命周期，不可能分叉 | **✅ 采用** |
| A2 外部查表（`Map<runtimeId, BlockShape>`） | 方块与形态两份真相，可各自漂移；且注册表冻结后无处补登记 | ❌ |
| A3 用 `BlockFace` 硬编码判断 | 把形态语义塞进渲染层，碰撞层读不到 → 渲染与碰撞分叉 | ❌ |

**理由**：网格与碰撞**必须读同一份真相**，否则就会出现"看起来是台阶、撞上去是满格"。
A2 的致命问题是它允许两份真相并存——而本轮要防的"隐形墙"恰恰就是两份真相不一致的产物。

### 2.2 决策 B：`collision` 字段对形态**没有否决权**（关键的反直觉决定）

`Block.blocksMovement()` 刻意写成 `collision && shape.hasCollision()`，即**形态可否决玩法开关**。

**为什么反直觉**：`collision=false` 时结论一样，看起来"直接返回 collision 字段"更简单。
但那样会留下一个真实且自然的误配路径：

> 有人登记小麦时 thinks"作物应该挡路吧？"→ `collision=true` → 玩家撞上一堵看不见的墙。

加上形态否决权后，**十字面方块无论布尔字段怎么配都不可能挡住玩家**。
`NonFullBlockMesherTest.crossShapeVetoesTheCollisionFlagEvenWhenItIsTrue`
用一个**故意把 `collision` 配成 true** 的十字面方块专门钉死这条（见 §4 反向验证 RV1）。

反向的组合（形态有碰撞盒、但 `collision=false`）仍然通行 —— 那正是玻璃/树叶的表达方式。

### 2.3 决策 C：碰撞查询的**单一入口**

新增 `World.collidesWith(...)`，并让 `Player` / `EntityPhysics` **全部改走它**，
不再出现"`hasCollisionAt(...) && box.intersectsBlock(...)`"这种手写组合。

**为什么**：那种组合对满方块是对的，对半高台阶会把台阶上方的空气也当障碍。
把"是否阻挡"与"几何相交"封在一个方法里，就不存在"某处忘了问开关"的漏网可能。
`AABB.intersectsBlock` 保留（纯几何、可独立单测），但 javadoc 已明确标注**只对满方块成立**。

### 2.4 决策 D：`ChunkMesher` 新增可注入解析器的重载

异形方块必须能在**正式登记之前**被**端到端**验证，否则无法排除
"辅助类算对了、但主循环压根没调用它"这种死接线。
而把测试方块塞进 `BlockRegistry` 会污染全局（它bootstrap 后冻结，且数量断言会变红）。

因此把 `runtimeId → Block` 提成一个参数（`IntFunction<Block>`），
生产路径一律 `BlockRegistry::byRuntimeId`。
`NonFullBlockMesherTest.fullBlockStillProducesSixFacesThroughTheSameEntryPoint`
用**逐float 比对**证明新重载与生产路径产出完全相同的顶点数据。

---

## 3. 测试判据（每条都对应PRD 的机器可判项）

PRD §9.1 判据 5「小麦碰撞体为空（玩家能走进去）」由下表覆盖。

| # | 断言 | 判据类型 |
|---|---|---|
| 1 | `wheatMeshIsFourCrossQuadsNotASixFacedCube` | 小麦 **= 4 面**（十字面），不是满方块 6 面 |
| 2 | `wheatVertexCountMatchesFourQuads` | 4 面 × 4 顶点 = 16 顶点；4 × 6 = 24 索引 |
| 3 | `wheatCrossQuadsSpanTheFullCellHeightOnDiagonals` | y ∈ [64,65] 竖直贯穿；x/z 只取格角（对角线） |
| 4 | `wheatEmitsAntipodalNormalPairsSoItIsVisibleFromBothSides` | 4 个法线**两两严格反号**（双向可见）；且**都非轴对齐**（排除退化成满方块面） |
| 5 | `wheatHasNoCollisionBoxAtAll` | `collisionBoxes().length == 0`；`collisionTopY() == 0` |
| 6 | `wheatCollisionBoxVolumeIsZero` | AABB 总体积 **== 0** |
| 7 | `wheatDoesNotBlockAPlayerStandingInsideIt` | 玩家身体在麦格内 → **不相交** |
| 8 | `crossShapeVetoesTheCollisionFlagEvenWhenItIsTrue` | 十字面否决 `collision=true`（RV1 靶点） |
| 9 | `slabMeshTopFaceSitsAtHalfHeight` | 台阶网格顶面 **y = 64.5**（不是 65.0） |
| 10 | `slabCollisionBoxIsHalfHeight` | 碰撞盒 y ∈ [0, **0.5**]，体积 0.5，`collisionTopY()==0.5` |
| 11 | `slabBlocksOnlyTheLowerHalfOfItsCell` | 下半格内→碰撞；**上半格（空气）→ 不碰撞** |
| 12 | `slabTopIsNotAtFullCellHeight` | 台阶顶面比满方块低半格 |
| 13 | `everyRegisteredBlockIsStillAFullCube` | 既有 17 种方块**形态全是 FULL** |
| 14 | `fullBlockStillProducesSixFacesThroughTheSameEntryPoint` | 注入解析器不改变满方块（逐 float 比对） |
| 15 | `slabNextToFullBlockStillCullsSharedFace` | 异形方块的剔除规则与满方块**同一套**（6+6-2=10） |
| 16 | `emptyChunkStillReturnsSharedEmptyMeshViaInjectedLookup` | 空区块早退未被绕过 |
| 17 | `collisionBoxesAlwaysStayInsideTheUnitCell` | 碰撞盒不越出 [0,1]³ |
| 18 | `shapesWithNoCollisionBoxMustNotBeFullCubes` | 无碰撞盒 ⇒ 不是满方块（不变式） |
| 19 | `shapeCatalogueCoversTheTwoFormsThisRoundNeeds` | 钉住两个形态常量就位 |
| 20 | `faceCountsAreExactNotLowerBounds` | 提醒：面数判据是**精确值**，不是 `>=` |

### 关于「脆绿灯自查」

逐条检查了"它现在绿，是因为真的对，还是因为恰好相等/恰好没触发"：

- **第 5/6 条（碰撞盒为空）**：小麦自己的 `collision=false` 就能让它不挡路，
  所以光靠第 5 条无法区分"靠字段"还是"靠形态"。→ 用**第 8 条**（`collision=true` 的十字面）做鉴别，
  这才是承重的那条。
- **第 9 条（台阶顶面 64.5）**：不能只断言 `maxY <= 65`，那会被"全塌到地面"的退化实现蒙混过关。
  → 断言的是**精确值 64.5**，且第 10/11 条从碰撞侧独立复核同一事实。
- **第 4 条（双向面）**：一开始我写成"检查第 0→1 条边朝上还是朝下"——
  查了之后发现**这个判据依赖顶点编号约定**，改一次编号就会假红，是脆绿灯。
  → 改成**法线叉积判反号**，与编号无关。实现没动，判据先改对了。
- **第 3 条**：不只断言"有4 个面"，还断言 y 范围与 x/z 只取边界值 ——
  这样"发了 4 个面但平铺在地面上"也会被抓住。

---

## 4. 反向验证（RV）

每一处都是"看起来完全合理"的接线错误。**注入 → 目标断言变红 → 逐字节恢复 → 残渣 0**。

| # | 注入内容 | 变红的断言 | 结果 |
|---|---|---|---|
| **RV1** | `blocksMovement()` 去掉形态否决，退化为`return collision;` | `crossShapeVetoesTheCollisionFlagEvenWhenItIsTrue` | **变红**（expected false, was true） |
| **RV2** | `SLAB_BOTTOM` 的碰撞盒从`SLAB_BOTTOM` 换成 `FULL` | `slabBlocksOnlyTheLowerHalfOfItsCell`<br>`slabCollisionBoxIsHalfHeight`<br>`slabTopIsNotAtFullCellHeight` | **3 条同时变红** |
| **RV3** | `ChunkMesher` 里删掉 `NonFullMesh.emitCross(...)` 调用（保留分支与 continue，制造**死接线**） | `wheatMeshIsFourCrossQuadsNotASixFacedCube`<br>`wheatVertexCountMatchesFourQuads`<br>`wheatCrossQuadsSpanTheFullCellHeightOnDiagonals`<br>`wheatEmitsAntipodalNormalPairs...` | **4 条同时变红** |

**恢复验证**（`cmp -s` 逐字节比对 + MD5）：

```
IDENTICAL: src/main/java/com/skyisland/world/block/BlockShape.java
IDENTICAL: src/main/java/com/skyisland/world/block/Block.java
IDENTICAL: src/main/java/com/skyisland/render/mesh/NonFullMesh.java
```

MD5 记录：`BlockShape` = `d4ee0c27f44a0306d0fc0f2a134b7499`（前后一致）
`Block` = `cc8b27379c4d069fcc0623c80227f1fe`（前后一致）

**残渣扫描**：在 `world/block/` 与 `render/mesh/` 下搜 `RV-INJECT| 反验 | TEMPORARY BREAK | XXX`
→ **命中 0**。`ChunkMesher` 的第三处注入用 Edit 精确回滚，逻辑与备份一致。

RV3 是本报告最值得强调的一条：它验证的不是"辅助类算得对"，
而是"**主循环真的调用了它**"。这类断线可以带着全绿的辅助类单测活下来。

---

## 5. 已知取舍与需要主理人知道的风险

### 5.1 本轮**没有**做的事（按S1 边界）

- **未登记小麦 / 台阶到 `BlockRegistry`** —— 那是 S5。本轮只提供能力。
- **未改顶点格式**（stride 仍28/7 float）—— 那是 S2。
  ⚠️ **S2 落地时请注意**：半高盒与十字面的顶点<b>仍然是纯色 + 明暗</b>，
  贴图层号字段是 S2 加的。届时 `NonFullMesh` 的两个发射点也要跟着写 layer 值，
  否则异形方块会显示成"纯色但和其它方块一样"——不难发现，但别忘了。
- **未做台阶上半形态**（PRD §3.5 已裁定本轮不做）。
- **未做火把细杆 / 木门异形** —— 不在本轮范围。

### 5.2 风险与限制

| # | 事项 | 说明 / 建议 |
|---|---|---|
| L1 | **十字面不做邻居剔除** | 一株小麦无论四周被什么包着，都固定发 4 个面。好处：两株相邻小麦的画面完全一致（不会因剔除而出现差异）；代价：密植农田的面数是满方块的 2/3 而非更低。**这是刻意取舍，不是遗漏。** 若 S9 性能复测发现农田密集区吃紧，可加"六邻全不透明才整体剔除"的优化，但**不要**改成逐面剔除。 |
| L2 | **十字面恒用侧面的明暗（0.85）** | 不用顶面明暗，因为它是竖直的植物。视觉上足够，且比逐面推光照便宜。 |
| L3 | **碰撞盒是数组但当前最多 1 个** | 为的是将来栅栏/双台阶不必再改API 形状（PRD §3.5 的 `slab_top` 就是多盒场景）。当前实现里 `collisionBoxes()` 返回**共享常量数组**，调用方<b>不得修改</b>。 |
| L4 | **放置规则未适配异形** | `World.placeBlock` 的"相邻支撑"仍走 `isSolidAt`。小麦 `solid=false`，因此**不能只靠另一株小麦作支撑放置**。PRD §3.5 规定台阶放在实心方块上半格或地面 —— 那需要**上层空间检查**，属S5/S8。<br>⚠️ **S5 请注意**：台阶是 `solid=true`，所以它**能**支撑上方方块，这与"实际只有半格高"存在语义落差。若将来出现"台阶上再放台阶"的预期行为偏差，根因在这里。 |
| L5 | **`Player.occupiesBlock` 仍用满格判定** | 它只服务"放置会不会塞进玩家身体"，对半高台阶略保守（可能多拒绝一次放置）。影响面小且偏安全，**未在本轮改动**，以免动到放置这条独立链路。 |
| L6 | **怪物 AI 的"前方是否有地"仍用布尔判定** | `MeleeMonster` 走 `hasCollisionAt`，对台阶（`collision=true`）判定正确，对小麦（无碰撞体）判定为"无地" —— 这正是期望行为（作物不是地面）。 |
| L7 | **U+2212 与 CJK 字模** | 本轮新增中文注释已重烘字模。若 S2–S9 新增中文，<b>同样必须重跑生成器</b>，否则 `CjkFontTest` 护栏会拦下。 |

### 5.3 给S5 的直接交接说明

S5 登记方块时，只需在 `BlockRegistry.register(...)` 里多传一个 `BlockShape`：

```java
// 小麦：collision=false + BlockShape.CROSS
register("skyisland:wheat", false, true, true, true, false,
         0.0f, 0, RenderType.TRANSPARENT, 0xD8B24C, null, 0, BlockShape.CROSS);

// 台阶：collision=true + BlockShape.SLAB_BOTTOM
register("skyisland:slab", true, false, true, true, true,
         2.0f, 0, RenderType.OPAQUE, 0x9A9A92, "skyisland:slab", 1, BlockShape.SLAB_BOTTOM);
```

`BlockRegistry` 的 `register` 目前有 12 参（掉自身 ×1 便利重载）与 14 参（完整重载）两个版本，
**需要新增一个带 `BlockShape` 的重载**（本轮刻意没加，因为没有调用方 ——
加了就是死代码）。届时 `BlockRegistryTest` 的 `size()` 17→22、
`playerBlockCount()` 15→20 需同步改（PRD §9.1 判据 1）。

> `Block` 侧已备好对应的包级私有构造器（16 参，带 `BlockShape`），
> 且**原 15 参构造器保留为委托到 `FULL`** —— 因此 S5 接入时，
> 既有 17 种方块<b>一行都不用改</b>，只有新增的那 5 种走新路径。

---

## 6. 复现方式

```bash
cd /f/minecraftspace
"D:/software/maven/apache-maven-3.9.9/bin/mvn.cmd" -s toolchain/settings.xml clean package
```

> 必须 `clean package`，不要用 `mvn compile`（增量编译不可信，会给假绿）。

**测试证据路径**：

- 汇总：`target/surefire-reports/`（1176 / 0 / 0）
- 本步专属：`target/surefire-reports/com.skyisland.render.mesh.NonFullBlockMesherTest.txt`
- 满方块回归（未改动）：`target/surefire-reports/com.skyisland.render.mesh.ChunkMesherTest.txt`
- 物理回归（碰撞判定改动的影响面）：`target/surefire-reports/com.skyisland.physics.AABBTest.txt`、
  `com.skyisland.player.PlayerPhysicsTest`（若存在）

---

## 7. 门禁影响

| 门禁 | 影响 |
|---|---|
| M3-GATE 验收结论 | **不变**。本轮属 Alpha/后续迭代层（PRD §2 明确"解除的是登记与实现，不是 MVP 门禁范围"）。 |
| MVP 方块口径 13 | **不变**。`BlockRegistry` 未改，13 这个数字没被动过 —— 这正是它作为"PRD 的 MVP 口径未被本轮改动"的证据。 |
| 单测只增不减 | ✅ 1156 → 1176 |
| 性能门禁 | 本轮**未重跑 `perf_gate_met`**。形态分派只在"非满方块"分支生效，满方块热路径新增的只有一次 `block.shape()` 枚举读与一次 `isFull()` 判断，开销可忽略。但按 PRD §8，**S9 仍需正式复测**。 |

---

## 8. 结论与建议

**S1 完成，R1 已关闭，无需降级。**

小麦与台阶都拿到了真正的非满形态：小麦是 4 面十字网格 + 空碰撞盒，
台阶是半高盒网格 + 半高碰撞盒。玩家既不会撞上看不见的墙，
也不会撞上台阶上方的空气。

**建议主理人批准 S2（顶点格式扩展）**，并把 §5.1 的一条提醒传达过去：
`NonFullMesh` 的两个发射点在 S2 需要补写 layer 值。
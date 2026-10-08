# M5a 昼夜循环 + 光照连续化 + HUD —— 交付报告

- 里程碑：M5a（PRD §4.4）
- 日期：2026-10-08
- 状态：**代码与守卫完成，验证收尾中**（未 commit，等主理人放行）
- 规格来源：`docs/design/PRD` §4.4，逐条对账见 §2

---

## 1. 一句话

一天 20 分钟（黎明 1 + 白天 11 + 黄昏 1 + 夜晚 7 分钟），亮度在夜间降到白天的 15%，
**火把不跟着天光一起暗**；HUD 右上角显示「第 N 天 阶段 时:分」与本阶段进度条；
读档回到离开时的时刻。

---

## 2. PRD §4.4 逐条对账

| PRD 条款 | 落地位置 | 行为守卫 |
|---|---|---|
| 黎明 1 分钟 / 白天 11 分钟 / 黄昏 1 分钟 / 夜晚 7 分钟 | `DayPhase`（存**比例**不是秒数，总时长可缩放） | `DayClockTest.thePhaseTableMatchesPrdSection4Point4`（60/660/60/420/1200） |
| 合计 20 分钟 = 1200 秒 | `DayClock.DEFAULT_TOTAL_SECONDS` | 同上 + `scalingTheTotalKeepsThePhaseProportions` |
| 一天从黎明开始 | `DayClock()` 默认停在天数起点 | `aNewWorldStartsAtTheBeginningOfDay` |
| 存活天数以黎明为结算点递增 | `DayClock.advance` 用 `floor` 一次算完跨过的结算点数（O(1)） | `theDayCountIncrementsExactlyWhenDawnIsCrossed` |
| **夜晚最低亮度为白天的 15%** | `DayClock.NIGHT_BRIGHTNESS_RATIO` 同时约束 `skyLevel()` 与 `ambientFloor()` | `theNightFloorIsFifteenPercentOfTheDayFloor`（**两个都测**，只测一个会漏） |
| 火把照亮半径 6 格、固定衰减 | 未改动 `LightEngine`（M1 已有） | 既有测试 |
| 首版无动态全局光照 | 光照仍**烘焙**进顶点，只随 uniform 连续缩放 | 见 §4 |

---

## 3. 四条设计裁定

### 3.1 ★★ 天光与火把必须分两路，不能用一个全局乘子

若用一个 uniform 乘整个烘焙明暗，夜里火把会跟着天光一起变暗 ——
直接违反「夜晚昏暗，**火把成为主要照明**」。

**做法**：顶点里把两者分开打包，片元只对天光那一路乘昼夜系数。

```
packed = torchLevel + (skyExposed ? 16 : 0)     // 0..31 的整数
skyN   = step(16.0, packed)                     // 1 = 本列见天
torchN = mod(packed, 16.0) / 15.0               // 火把分量 0..1
nowLit = max(skyN * uSkyLevel, torchN)          // ★ 火把不乘 uSkyLevel
```

**为什么塞进 `aLayerAo.y`**：那个槽位自 S2 起就是一个**从未被计算过的死槽位**
（四个写入点全写常量 `0`，片元里的 `(1 - ao)` 恒等于 1）。改派它比新增属性
涨 4 字节 stride、重钉六个消费方偏移要划算。

**为什么用整数而不是分数方案**：取值 0..31 的整数能被 float32 的 24 位尾数**精确表示**；
分数方案要经过除法与减法才能还原。

### 3.2 ★ 白天必须逐像素一致

`vColor.a` 烘焙的是**白天口径**的明暗。片元算：

```
shade = vColor.a × (nowShade / dayShade)，其中 map(x) = floor + (1-floor)·x
```

昼 `uSkyLevel == 1` 且 `uAmbientFloor == uDayFloor` ⇒ 分子分母逐项相等 ⇒ **比值恰为 1**。
于是昼夜首帧**不改变任何一个像素**。这不是"差不多"，它被
`VertexFormatConsistencyTest` 穷举 32 种打包组合断言为等式。

### 3.3 ★ 黎明/黄昏必须线性而非阶跃

阶跃会整体跳变。60 秒线性过渡每帧变化约 0.028%，肉眼不可见。
`daylightIsContinuousAcrossEveryPhaseBoundary` 断言 4 个边界连续（eps=1e-4）。

### 3.4 ★ 存档字段必须是包装类型

`Double worldTimeSeconds` / `Integer dayCount`。
"没有这个字段"必须与"字段是 0"**可区分**，否则旧存档会被读成"黎明的第一秒"（几乎全黑）。

---

## 4. 已知代价（继承自 M1，本轮未变）

光照变化仍需重建网格，因为 shade 已烘焙进顶点（TECH_DESIGN §G.3 已记录）。
但 M5a 的 uniform 方案让**昼夜切换本身不需要重建任何网格** ——
只有建造/破坏方块时才重建，这与 M1 的代价口径完全一致。

---

## 5. ★★ 一个真 bug（存档时刻被抹掉）

`SaveManager.buildLevelMeta` 每次 `new` 一个空 `LevelMeta`。
我最初写"不传时钟就不写这两个字段"，看起来是"保持不变"，实际上：
**不写 = null = Gson 序列化时整个键消失** ⇒ 用旧签名保存一次就把玩家的时刻与天数彻底抹掉。

- **触发条件**：任何"先带时钟存过一次、之后又存了一次"的存档。
  只测新存档的测试**测不到**。
- **修法**：`clock == null` 时从 `readLevelMeta()` carry-forward（与 `existingCreatedAtMillis()` 同源）。
- **守卫**：`SaveManagerDayTimeTest.savingWithoutAClockLeavesAnEarlierTimeUntouched` + 反向验证 K。

---

## 6. ★★★ GLSL 保留字：单测全绿而画面全黑

M5a 第一版在 `voxel.frag` 里用了局部变量 `packed` —— GLSL 保留字：

```
ERROR: 0:89: 'packed' : reserved word
ERROR: 0:89: 'packed' : syntax error
```

**整个着色器编译不过 ⇒ 画面全黑，而当时 1400+ 单测全绿**，因为：

1. Java 单测不需要 GPU，不编译 GLSL；
2. 已有的着色器扫描守卫只检查"某一行是否存在"，而那一行**确实在**，只是语法非法。

**教训：「文本扫描全绿」与「能编译」是两个独立事实。**

### 6.1 两道防线（不是二选一）

| 防线 | 覆盖 | 限制 |
|---|---|---|
| `GlslSourceSanityTest`（单测，每次都跑） | 把保留字/内置函数名当变量名**声明**；语句开头的裸保留字 | 只做词法，**不能**发现类型不匹配、参数个数错误等 |
| `M5DayNightEvidence`（需窗口与 GPU，取证时跑） | 真编译两个着色器 | 需要 GPU |

### 6.2 ★ 判据必须按"位置"，不能全文判死

第一版守卫是"全文扫到保留字就报错"，它对 `voxel.frag` 的真实内容**一定是红的**：
`max` / `mod` / `step` / `texture` 是**内置函数**（调用合法），`in` / `out` / `const` 是**关键字**。

红灯守卫的真正后果不是"红"，而是**被人删掉，从此再没人报警**。
所以判据改成"只在**声明位置**判非法"，并给它配了三条**反证用例**
（把违规代码当夹具喂进去，断言它确实被咬出来）。

### 6.3 ★★ classpath 顺序让"取证"变成"读旧副本"

Maven 把 `shaders/*` 复制进 `target/classes`；`ShaderProgram` 走 `getResourceAsStream`，
**谁排在 classpath 前面谁被读到**。我最初的取证命令写成
`-cp "target/classes;src/main/resources;…"`，于是：

> 把 `packed` 注进源文件 → 跑取证 → **编译成功、漂亮的 PASS**
> —— 而它读的是干净的旧副本，那次证据**完全无效**。

修法不是"记得顺序"，而是**工具自证**：`M5DayNightEvidence` 启动时比对
classpath 读到的着色器与 `src/main/resources` 源文件**逐字节一致**，
不一致就在建窗口**之前**抛异常。已反向验证：让两份分叉 ⇒ EXIT=1 且报明确切原因。

---

## 7. 像素级取证结果

`M5DayNightEvidence`（真渲染 + `Screenshot.readPixels` 回读）。
断言四件事：白天画面可用 / 夜晚显著更暗 / **火把在夜里不跟着天光暗** / 非纯色图。

| 指标 | day | night | 比值 | 判据 | 结果 |
|---|---|---|---|---|---|
| 全幅平均亮度 | 97.2 | 25.8 | **0.266** | ∈ (0, 0.75) | PASS |
| ★ 火把方块自身像素 | 44.7 | 39.2 | **0.877** | > 0.266 + 0.15 | PASS |

**火把亮度比（0.877）远高于全幅比（0.266）** —— 这是 PRD §4.4
「火把成为主要照明」唯一能被**像素**证明的形式。两次运行逐位一致（确定性成立）。

截图：`screenshots/m5a-daynight-day.png`、`screenshots/m5a-daynight-night.png`

---

## 8. 测试清单

| 文件 | 用例数 | 状态 |
|---|---|---|
| `world/DayClockTest`（新建） | 19 | 全绿 |
| `save/SaveManagerDayTimeTest`（新建） | 7 | 全绿 |
| `game/DayNightWiringTest`（新建） | 16 | 全绿 |
| `render/GlslSourceSanityTest`（新建，含 3 条反证） | 4 | 全绿 |
| `render/VertexFormatConsistencyTest`（改） | 52 子集 | 全绿 |

**关键点**：`DayNightWiringTest` 全部用 `SourceScan.withoutComments` +
`SourceScan.methodBody`，断言**方法体内的相对位置**（`bind < applyDaylightUniforms < draw`、
`setDaylight < clear`、`pauseGuard < advance`），而不是"这段文字出现过"。

---

## 9. 反向验证

`tmp/m5a/rv_glsl.sh`（GLSL 保留字）+ `tmp/m5a/rv_all.sh`（A–K 十一条接线断线）。

| # | 注入 | 目标断言 |
|---|---|---|
| A | `renderWorld` 不再 `applyDaylightUniforms()` | `theWorldPassWritesTheDaylightUniformsAfterBindingTheShader` |
| B | `applyDaylightUniforms` 不写 `uDayFloor` | `applyDaylightUniformsWritesAllThreeAndOnlyTheThree` |
| C | `setDaylight` 挪到 `clear()` 之后 | `setDaylightIsCalledBeforeClear` |
| D | `stepLogic` 不再 `dayClock.advance` | `theLogicStepAdvancesTheClockWithTheSameDeltaAsElapsedTime` |
| E | 存档退回 `save(world, player)` | `savingPassesTheClockSoTheTimeIsWritten` |
| F | 顶点改回只存 `max` | `theMesherTakesSkyAndTorchSeparatelyForThePackedLight` |
| G | `shade` 只看天光 | `theMesherStillBakesTheSameDayShadeAsBefore` |
| H | `NIGHT_BRIGHTNESS_RATIO = 0` | `theNightFloorIsFifteenPercentOfTheDayFloor` |
| I | `DAY(0.55)` → `DAY(0.60)` | `thePhaseTableMatchesPrdSection4Point4` |
| J | 片元里 `torchN * uSkyLevel` | `VertexFormatConsistencyTest`（火把不得乘昼夜） |
| K | 删掉 carry-forward else 分支 | `savingWithoutAClockLeavesAnEarlierTimeUntouched` |
| GLSL | `lightPack` → `packed` | `GlslSourceSanityTest` 主断言 **且** 真编译报 `reserved word` |

### 9.1 ★★ 反向验证本身踩的 5 个坑（都是"证据是假的"，不是"代码有问题"）

1. **`byte-identical` 判据恒为 true** —— 它在**写回之后**才比较 now 与 bak。一个永不红的绿灯。
2. **退出码 ≠ 断言红了** —— maven 在**测试运行之前**失败（编译错、命令行被 shell 吃掉）也非 0 ⇒ 假红。
3. **surefire 的 `.txt` 报告会骗人** —— 实测 `.txt` 显示 `Failures: 0` 而 XML 是 `failures="1"`，
   **结论直接反过来**。判据必须读 XML。
4. **XML 报告不能用正则解析** —— 通过的用例是**自闭合**的 `<testcase … />`，
   `<testcase name="…"[^>]*>([\s\S]*?)</testcase>` 会把自闭合标签当开标签，
   一路吞到后面失败用例的结束标签 ⇒ **失败名字配成前一个通过用例的名字**。
   实测因此把 `applyDaylightUniformsWritesAllThreeAndOnlyTheThree` 的失败报成了
   `theClockIsAdvancedInsideTheSimulationGuard`。已改为真解析（`tools/xmlred.js`）。
5. **`.bak` 被同文件的多条断线互相覆盖** —— A/B 同改 `Renderer.java`，第二次 inject 覆盖了备份，
   还原回"被注入过的中间态" ⇒ 源码里留下 `[RV-A]` 残渣。
   修法：共享备份 + 冲突即拒（磁盘 ≠ 共享备份就退出，别默默覆盖），驱动每条前清空 bak 目录。

另：**标记必须放注释里**（拼进标识符会打断判据要求的"类型名 + 空白 + 标识符开头"）；
**GLSL 首行之前不能插注释**（会让 `#version` 不在第一行，
掩盖掉我们要证明的那条错误 —— 实测因此 `reserved word` 读成 false）。

---

## 10. 遗留

- **真人试玩**：昼夜过渡的观感、火把照明的实际体感、HUD 进度条的可读性，
  自动化测试只能证明"公式对"，证明不了"看着舒服"。需主理人执行。
- **未 commit**（按规则需主理人许可）。
- M5b（刷怪器 + 性能复测）、S9（性能复测）待做。

---

## 11. 相关文件

**新增**
- `src/main/java/com/skyisland/world/DayPhase.java`
- `src/main/java/com/skyisland/world/DayClock.java`
- `src/main/java/com/skyisland/game/M5DayNightEvidence.java`（像素取证，需 GPU）
- `src/test/java/com/skyisland/world/DayClockTest.java`
- `src/test/java/com/skyisland/save/SaveManagerDayTimeTest.java`
- `src/test/java/com/skyisland/game/DayNightWiringTest.java`
- `src/test/java/com/skyisland/render/GlslSourceSanityTest.java`
- `tools/xmlred.js`（surefire XML 真解析）
- `tmp/m5a/rv_glsl.sh`、`tmp/m5a/rv_all.sh`、`tmp/m5a/rv.js`

**改动**
- `render/VertexFormat.java`（AO 槽位改派为打包光照）
- `resources/shaders/voxel.vert` / `voxel.frag`
- `render/mesh/ChunkMesher.java`、`NonFullMesh.java`
- `render/geom/Boxes.java`、`render/fx/CombatFxRenderer.java`、`mesh/CrackOverlay.java`
- `render/Renderer.java`（天空插值 + 三个 uniform）
- `save/LevelMeta.java`、`save/SaveManager.java`
- `game/SkyIslandGame.java`（时钟生命周期）
- `render/ui/HudModel.java`、`HudRenderer.java`、`ui/Localization.java`
- `world/LightEngine.java`（`AMBIENT_FLOOR` 公开化）
- `test/render/VertexFormatConsistencyTest.java`
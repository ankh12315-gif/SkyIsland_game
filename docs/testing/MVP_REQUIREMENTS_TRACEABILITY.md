# 《空岛生存 SkyIsland》MVP Requirements Traceability Matrix

- **任务 ID**：MVP-AUDIT-Q1（含 D1 兜底检查）
- **角色**：quality-lead（严守真）· 测试策略 / 证据审查 / 需求可追溯性
- **性质**：**只审计与登记。未改动任何功能代码、未新增功能、未启动 M2。**
- **日期**：2026-09-19
- **审计基线**：`F:\minecraftspace` @ M0 CLOSED → M1 First Playable PASS → M1.5 Front-End & Settings PASS
  - 产品源码 `src/main/java/com/skyisland/` **70 文件 / 16,667 行**（本报告实测 70，与 M1.5 报告一致）
  - 测试源码 `src/test/java/com/skyisland/` **35 文件 / 555 用例 / 0 失败**（本报告独立复核，见 §1.3）

- **输入（只读）**：
  - `docs/design/PRD_v0.3.1.md`（1262 行，172 处【MVP 必须】需求标记）—— 产品口径唯一来源
  - `docs/testing/_audit_wip/D1_requirement_inventory.md`（528 行，**324 条 Requirement** + SPEC_DRIFT 23 条）
  - `docs/testing/_audit_wip/E1_implementation_status.md`（233 行，37 系统实现状态 + T-8.9 专项 + 12 条未登记缺口）
  - `docs/architecture/TECH_DESIGN_v0.1.md`（2494 行）/ `TECH_DESIGN_v0.1.1.md`（368 行，冲突以 v0.1.1 为准）
  - `docs/testing/M0_REPORT.md` / `M1_FIRST_PLAYABLE_REPORT.md` / `M1_5_FRONTEND_SETTINGS_REPORT.md`
  - `docs/design/M1_5_FRONTEND_SETTINGS_SPEC.md`（本轮已入库，300 行）
- **输出**：本文（正式交付物）

---

## 0. 摘要（先看这里）

| 指标 | 数 |
|---|---|
| MVP Requirement 总数 | **324** |
| `COVERED`（已实现 + 有测试） | **63** |
| `COVERED_WITH_TODO`（已实现但验证不足 / 局部实现） | **48** |
| `DEFERRED_WITH_RECORD`（未实现，但引得出登记出处） | **162** |
| `GAP`（未实现，且**没有任何登记**） | **40** |
| `CONFLICT`（文档/实现口径未裁决） | **11** |
| 合计 | **324** ✅ |
| SPEC_DRIFT（A 类 / B 类 / C 类附注） | **6 / 12 / 5 = 23** |
| EARLY_SCOPE（提前实现后续迭代 / Alpha 内容） | **1 项明确（按键自定义）+ 4 项边界（§6.2）** |

> **一句话结论**：M1/M1.5 的实现质量很高（70 文件、555 用例 0 失败、性能门禁有数量级余量），
> 但**需求可追溯性存在系统性缺口**：324 条 PRD【MVP 必须】里有 **40 条没有任何登记出处**，
> 其中 **12 条是"宿主系统已实现、唯独漏掉这个子行为、且无人记账"** ——
> 这正是「裂纹反馈」当年走过的同一条路。另有 **11 条口径冲突**必须由用户裁决后才能验收。
>
> **`M2_ENTRY_READY = false`**（见 §10，5 项门禁条件中有 **3 项**不满足）。

---

## 1. 判定口径（本报告怎么写死的）

### 1.1 四种情况的严格落实

用户第 4 节给出的四分类是本报告唯一的判定依据，落实如下：

| 情况 | 条件 | 判定结果 |
|---|---|---|
| **A** | 已实现 + 有可指认的自动测试证据 | `COVERED` |
| **B** | 已实现，但**验证不足**（无专门断言 / 只在替代场景取证 / 依赖尚未到期的宿主） | `COVERED_WITH_TODO` |
| **C** | 未实现，**但能引用到写明归属阶段的登记出处** | `DEFERRED_WITH_RECORD` |
| **D** | 未实现，且**引用不到任何登记出处** | `GAP` |
| — | 规格文档之间互斥、或实现与 PRD 明显背离且**尚未裁决** | `CONFLICT` |

> **关于 `DEFERRED_WITH_RECORD` 的一个口径说明（必须先讲清）**：
> 用户第 4 节的"判定只能用"清单里写的是四值，但同一节的 C 分支又明确定义了 `DEFERRED_WITH_RECORD`
> 作为**判定结果之一**，且第 11 节的审计摘要要求给出 `DEFERRED_WITH_RECORD` 计数。
> 二者只能同时成立的方式是：**判定列是五值**，其中 C 情形落到 `DEFERRED_WITH_RECORD`。
> 本报告按五值执行（`COVERED` / `COVERED_WITH_TODO` / `DEFERRED_WITH_RECORD` / `GAP` / `CONFLICT`）。
> **这不是放宽口径 —— D 依旧是 D，一条也没有被改写成 B 或 C。**

### 1.2 "引得出登记出处"的可复现判据（本报告的关键裁决）

**"Q1 必须能独立复核 E1，且不得把 D 偷偷改写成 C"** 的关键，在于"什么算登记"。
本报告使用**唯一一条**可复现的判据：

> **点名即 C，未点名即 D。**
> 该 Requirement 的**主体名词**是否出现在下列任一登记源里，并且该处还**写明了归属阶段**？
> - **是** → `DEFERRED_WITH_RECORD`（在 Technical Debt 列给出"文件 + 位置"）
> - **否** → `GAP`

**接受的登记源（共 11 个）**：

| 代号 | 登记源 | 典型点名内容 |
|---|---|---|
| R1 | `M1_FIRST_PLAYABLE_REPORT.md §6.1` | T-8.1～T-8.8（TextureArray / IslandGenerator / 实体系统 / 动态区块加载 / 光照最小 / Frustum AABB / 输入映射简化 / 无 UI 屏） |
| R2 | `M1_FIRST_PLAYABLE_REPORT.md §6.4` | 枪械·弹药·换弹 / 怪物与 AI / **生命值与饥饿** / 昼夜循环 / 掉落物实体 / 合成服务 / 正式 UI 屏 / 联机 / 纹理数组 / 正式 IslandGenerator 与 Seed 系统 |
| R3 | `M1_5_FRONTEND_SETTINGS_REPORT.md §6.4` | 同上 + **音频后端** / **粒子系统** / 背包与合成界面 / 动态区块加载 |
| R4 | `TECH_DESIGN_v0.1.1.md §S′` | 逐模块 M1.5 / M2 / M3 裁定（含 `world.gen` 正式 IslandGenerator → M2、`item.CraftingService` → M1 不实现、`ui` → M3） |
| R5 | `TECH_DESIGN_v0.1.md §S` | M0–M3 模块启用矩阵（`combat`/怪物/`ItemEntity` → M2，`item`/UI/资源核心再生/昼夜+刷怪 → M3） |
| R6 | `TECH_DESIGN_v0.1.1.md §U′.2` | **T-8.9**（挖掘音效 + 破坏音效 + 破坏粒子 → M2）、**T-9**（异步存档 → M2/Optimization） |
| R7 | `src/main/java/com/skyisland/save/LevelMeta.java:44-51` | `worldTimeSeconds` / `dayPhase` / `dayCount` / `dayFactor` / `resourceCores`（明写"资源核心的累积产出属 **M2**"）/ `settingsSnapshot` |
| R8 | `src/main/java/com/skyisland/save/PlayerState.java:18` | "`health` / `fallDistance`"（明写 **M1 未纳入**） |
| R9 | `src/main/java/com/skyisland/settings/Action.java:37/50/54` | CROUCH → M2、RELOAD → M2、INVENTORY → M3（`consumedBy` 字段） |
| R10 | `src/main/java/com/skyisland/game/SkyIslandGame.java:55` | "M2 及以后：枪械 / 怪物 / 昼夜 / **生命值与饥饿** / …" |
| R11 | `PRD_v0.3.1.md §11` 各里程碑「交付内容 + 通过标准」 | 尚未到期项本身的排期依据 |

**为什么必须这么窄**：如果允许"宿主系统被整体登记过"就把每条子行为都算 C，那么
「裂纹反馈」当年也会被判成 C —— 因为挖掘系统整体当然是被登记过的（只是没点名"10 段裂纹"）。
**恰恰是"点名"这一条，把 2016 年的裂纹和今天的 40 条 GAP 区分开。**

### 1.3 对 E1 的独立复核记录（不得照单全收）

| # | 复核项 | 复核手段 | 结论 |
|---|---|---|---|
| V1 | **测试用计数** | 逐文件 grep `@Test` / `@ParameterizedTest` | E1 §6.5 的 **538 + 17 = 555** **完全正确**（与我的独立计数逐文件一致，见 §1.4 全表） |
| V2 | **`InventoryTest` 用例数** | grep `@Test` | **实测 17**。主理人抽查的"E1 写 23"已由 E1 自己在 §6.5 更正为 17（23 的是 `KeyRebindControllerTest`）。**更正已验真** |
| V3 | **"无自动测试"结论** | 对"昼夜/生命/枪械/粒子/音频/掉落实体/合成"逐个 grep 测试树关键字 | **成立**：35 个测试文件中无对应 test class，亦无相关用例 |
| V4 | **T-8.9 三项** | grep `audio\|sound\|OpenAL\|particle\|粒子` on `src/main` | **成立**：全仓零播放路径、零粒子系统；登记出处 R6 可引 |
| V5 | **Block 注册数** | 读 `BlockRegistry.bootstrap()` L89-120 | **成立**：9 条非空气注册（玩家常规 8 + 系统 1）；缺 `log`/`leaves`/`iron_ore`/`coal_ore`/`torch`/`wooden_door` |
| V6 | **Main Menu 项数** | 读 `Menus.mainMenu()` L69-75 | **成立**：只有 Start Game / Settings / Quit Game 三项 |
| V7 | **`showEvent` 调用点** | grep 调用点 | **成立**：恰好 5 处（L504/508/512/822/1459），全为存档/截图生命周期消息，**无一为玩法提示** |
| V8 | **Seed 是否被生成器使用** | 读 `TestWorldGenerator.java:45-47` | **成立**：明写"不依赖 seed"，仅存档记录 |
| V9 | **准星是否条件高亮** | 读 `HudRenderer.java:39/90-99` | **成立**：固定 `CROSSHAIR` 常量，零条件分支 |
| V10 | **NOT_IMPLEMENTED 判定（实体/健康/合成/音效）** | 全仓文件树 + 关键字 grep | **成立**：无 `entity` / `combat` / `item` 包，无 `crafting`，无 OpenAL 依赖 |
| V11 | **首次提示** | grep `WASD`/`首次`/`firstRun`/`hint` | **成立**：无实现、无"仅首次"状态位 |
| V12 | **Inventory 只有 9 格** | 读 `Inventory.java:22-24` | **成立**：`HOTBAR_SIZE = 9`，无 27 格主背包 |

**结论：E1 的关键 Claim 抽样复核全部通过，无新增更正项。**（历史更正：E1 原稿的"InventoryTest 23 例"已由主理人抽查指出、并由 E1 §6.5 自行更正；本报告确认更正后的 17 为正确值。）

### 1.4 测试资产实测分布（quality-lead 独立计数，2026-09-19）

| 测试文件 | 用例 | 测试文件 | 用例 |
|---|---:|---|---:|
| `util.CoordsTest` | **32**（15 `@Test` + 2 `@ParameterizedTest`×8/9） | `ui.SettingsMenuControllerTest` | 24 |
| `world.WorldTest` | 33 | `ui.UiStateMachineTest` | 22 |
| `player.PlayerPhysicsTest` | 48 | `render.mesh.ChunkMesherTest` | 16 |
| `physics.DdaRaycasterTest` | 16 | `render.ui.MenuLayoutTest` | 17 |
| `player.InventoryTest` | **17** ⭐ 更正后值 | `render.FrustumTest` | 15 |
| `save.SaveManagerTest` | 16 | `save.AtomicFileWriterTest` | 13 |
| `save.ChunkSerializerTest` | 17 | `world.block.BlockRegistryTest` | 13 |
| `player.ItemStackTest` | 9 | `world.ChunkTest` | 11 |
| `physics.AABBTest` | 9 | `world.LightEngineTest` | 11 |
| `render.mesh.BlockFaceTest` | 8 | `render.ui.BitmapFontTest` | 11 |
| `render.mesh.CrackOverlayTest` | 10 | `player.PlayerLookSensitivityTest` | 16 |
| `settings.GameSettingsTest` | 13 | `settings.InputBindingTest` | 12 |
| `settings.KeyBindingsTest` | 19 | `settings.SettingsStoreTest` | 20 |
| `settings.LookConfigTest` | 10 | `input.FrameInputQuantitiesTest` | 8 |
| `input.InputStateTest` | 7 | `player.PlayerIntentCopyTest` | 4 |
| `input.InputMapperActionsTest` | 22 | `game.CommandLineOverridesTest` | 9 |
| `ui.KeyRebindControllerTest` | **23** ⭐（非 Inventory） | `ui.MenuScreenTest` | 24 |
| `testutil.TestWorlds` | 0（工具类，非测试类） | **合计** | **555** |

> **附注（文档数字瑕疵，供回写参考）**：`M1_FIRST_PLAYABLE_REPORT.md §1.1` 记 `PlayerPhysicsTest = 42` 例，
> 当前实测 **48** 例（M1.5 报告称"新增 5 例"，42+5=47 ≠ 48）。**属纯历史文档数字错误**，
> 本报告不修改（避免同时改两份 WIP 时效完全不同的文件），建议随下一次文档修订一并校正。

### 1.5 本轮尝试重跑测试套件的结果（诚实记录）

- **尝试**：`mvn -s toolchain/settings.xml -B test`
- **结果**：**未能完成**。`-o`（离线）模式缺 `org.lwjgl:lwjgl-bom:3.4.3`（不在本机 `-s toolchain/settings.xml` 指定的镜像缓存里）；在线运行需下载 LWJGL 全套 native，超出本轮审计的时间预算，**已中止**。
- **处置**：自动测试列因此**全部基于 `(a)` 逐文件 `@Test` 静态取证 + `(b)` 三份验收报告的实测读数**，**没有一句"跑了之后是绿的"**。这与 `TECH_DESIGN_v0.1.1 §V′.5` 的立场一致：**不能用"代码看起来对"冒充证据**。
- **建议**：M2 启动前用一次完整的 `mvn test` 把这 555 例跑实，作为 M2 的基线证据。

---

## 2. Traceability Matrix 主表（324 条）

> 列口径：
> - **原计划阶段** = PRD 规定的阶段（来自 D1，依据 §9.2 与 §11 各里程碑交付内容）
> - **当前实现状态** ∈ `IMPLEMENTED` / `PARTIAL` / `NOT_IMPLEMENTED` / `DEFERRED_WITH_RECORD` / `CONFLICT`
> - **自动测试** = 可指认的测试类 + 用例数；"无" = 无被测对象
> - **人工证据** = M0/M1/M1.5 报告的实测读数或截图取证；"无" = 未取证
> - **Technical Debt** = 登记出处代号（R1–R11）或"无登记 → G*n*"
> - **最晚关闭阶段** = 本报告给出的最迟必须关闭的里程碑；"—" 表示已在 M1/M1.5 关闭

### 2.1 LOOP — 玩法主循环（3 条）

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-LOOP-001 | §3.1 / L113 | LOOP | 3 分钟内可完成一次「决策—执行—获得」 | M3 | NOT_IMPLEMENTED | 无 | 无 | R11（PRD §11 M3 交付内容） | M3 | DEFERRED_WITH_RECORD |
| MVP-LOOP-002 | §3.1 / L114 | LOOP | 一个昼夜内可完成「远征—造弹—夜战—扩建」 | M3 | NOT_IMPLEMENTED | 无 | 无 | R11（PRD §11 M3 交付内容） | M3 | DEFERRED_WITH_RECORD |
| MVP-LOOP-003 | §9.1 / L914 | LOOP | 端到端核心循环可完整走通（自黎明开始） | M3 | NOT_IMPLEMENTED | 无 | 无 | R11（PRD §11 M3 通过标准第 1 条） | M3 | DEFERRED_WITH_RECORD |

### 2.2 WORLD — 世界 / 岛屿 / 昼夜 / 虚空 / 资源核心 / Seed（43 条）

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-WORLD-001 | §4.1 / L204 | WORLD | 主岛水平尺寸 32×32 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 **T-8.2**（无正式 IslandGenerator） | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-002 | §4.1 / L205 | WORLD | 视距 6 区块（96 格半径） | M1 | PARTIAL | `WorldTest.ensureAreaLoadedCoversTheWholeRectangle` | M1 §2-2（仅 16 区块，非 6 区块视距） | R1 **T-8.4**（无动态加载 → M2/M3）+ 无「视距」设置项（见 MVP-SET-002 判 GAP） | M3 | COVERED_WITH_TODO |
| MVP-WORLD-003 | §4.1 / L206 | WORLD | 世界 128 层，可放置 y ∈ [1,127] | M1 | IMPLEMENTED | `WorldTest.placeBlockRejectsAirAndOutOfRangeY` | M1 §2-4 | 无 | — | COVERED |
| MVP-WORLD-004 | §4.1 / L207 | WORLD | 主岛表面层位于 y = 64 | M3 | PARTIAL | `PlayerPhysicsTest.gravityPullsThePlayerDownOntoTheTerrain`（y=64.0001） | M1 §2-4 | R1 T-8.2；PRD 4.1 与 TECH D1 的 ±1 层待 PRD v0.3.2 裁定 | M3 | COVERED_WITH_TODO |
| MVP-WORLD-005 | §4.1 / L208 | WORLD | 主岛自表面向下厚 8–14 层 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.2 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-006 | §4.1 / L209 | WORLD | 集显下稳定 60 FPS | M1 | PARTIAL | 无单测（由三次运行取证） | M1 §4.4 / M1.5 §4.4（p95 = 0.685 / 0.758 ms） | R1 T-8.4；与 MVP-PERF-003/004 的口径未满足（1280×720 / 16 区块 / <5 min） | M3 | COVERED_WITH_TODO |
| MVP-WORLD-007 | §4.1 / L210 | WORLD | MVP 世界只生成 2 座岛 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.2 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-008 | §4.2 / L220 | WORLD | 主岛 (0,0) 含开局小屋与少量树木 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.2 + DRIFT-B-05（无小屋技术条目） | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-009 | §4.2 / L221 | WORLD | 石矿岛 (48,0) 14×14，间隙约 25 格 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.2 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-010 | §4.2 / L229 | WORLD | 所有岛屿表面基准同为 y = 64 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.2 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-011 | §4.2 / L234 | WORLD | Seed 只影响主岛与石矿岛 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.2 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-012 | §4.2 / L231–233 | WORLD | 岛屿逐格形状由 Seed 决定 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.2 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-013 | §4.3 / L251 | WORLD | 矿物密度按高/中/低/无定义生成 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.2 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-014 | §4.3 / L254 | WORLD | 主岛不生成铁/铜/晶体矿 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.2 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-015 | §4.4 / L264 | WORLD | 黎明 1 分钟，停刷怪并结算新一天 | M3 | NOT_IMPLEMENTED | 无 | 无 | R2「昼夜循环」+ R11（PRD §11 M3 交付内容） | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-016 | §4.4 / L265 | WORLD | 白天 11 分钟且不刷怪 | M3 | NOT_IMPLEMENTED | 无 | 无 | R2 + R11 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-017 | §4.4 / L266 | WORLD | 黄昏 1 分钟且开始刷怪 | M3 | NOT_IMPLEMENTED | 无 | 无 | R2 + R11 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-018 | §4.4 / L267 | WORLD | 夜晚 7 分钟且持续刷怪 | M3 | NOT_IMPLEMENTED | 无 | 无 | R2 + R11 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-019 | §4.4 / L268 | WORLD | 一个完整昼夜总时长 20 分钟 | M3 | NOT_IMPLEMENTED | 无 | 无 | R2 + R11 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-020 | §4.4 / L271 | WORLD | 新世界开局为黎明 | M3 | NOT_IMPLEMENTED | 无 | 无 | R2 + R11 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-021 | §4.4 / L272 | WORLD | 天数在黎明结算点 +1 | M3 | NOT_IMPLEMENTED | 无 | 无 | R7（`dayCount` 明列 DEFERRED）+ R2 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-022 | §4.4 / L274 | WORLD | 夜晚最低亮度为白天的 15% | M3 | NOT_IMPLEMENTED | 无 | 无 | R7（`dayFactor` 明列 DEFERRED）+ R2（间接，未点名） | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-023 | §4.4 / L275 | WORLD | 火把半径 6 格线性衰减，不做块光传播 | M1 | IMPLEMENTED | `LightEngineTest`（11）含 `torchLightDecaysByChebyshevDistance` | M1 §2-2 | R1 T-8.5（最小实现，→ M2） | M3 | COVERED |
| MVP-WORLD-024 | §4.5 / L284 | WORLD | 虚空按「实体方块上方」判定 | M1 | PARTIAL | `PlayerPhysicsTest.fallingIntoVoidRespawnsAtLastSafePosition`（只验 y<-8，未验"上方无实体"定义） | M1 §2-10 | R1 T-8.2（无正式岛屿）；**无专项断言** | M3 | COVERED_WITH_TODO |
| MVP-WORLD-025 | §4.5 / L285 | WORLD | 玩家 y < -8 立即死亡，无视生命值 | M1 | IMPLEMENTED | `CoordsTest.voidDeathThreshold` + `PlayerPhysicsTest.voidDeathThresholdIsExclusiveAtMinusEight` | M1 §2-10 | 无 | — | COVERED |
| MVP-WORLD-026 | §4.5 / L286 | WORLD | 岛屿下方无地形，向下可见天空/雾；**虚空底部渲染为深色** | M1 | NOT_IMPLEMENTED | 无 | 无 | **无登记 → G14（本报告复核新增）**；DRIFT-B-07（技术文档无条目） | M3 | **GAP** |
| MVP-WORLD-027 | §4.5 / L287 | WORLD | 放置须邻接支撑，仍可逐格搭桥 | M1 | IMPLEMENTED | `WorldTest.placeBlockRejectsWithoutAdjacentSupport` | M1 §2-4 | 无 | — | COVERED |
| MVP-WORLD-028 | §4.5 / L289 | WORLD | 沙子悬空不下落（无重力方块） | M1 | IMPLEMENTED | 无专门断言 | M1 试玩（沙子未下落） | 无 | — | COVERED_WITH_TODO |
| MVP-WORLD-029 | §4.5 / L290 | WORLD | 虚空坠落过程不结算普通坠落伤害 | M1 | PARTIAL | 无 | M1 §2-10 | **成立只是因为跌落伤害根本没实现**（MVP-SURV-003 判 GAP）；须在 SURV-003 关闭后重新求值 | M2 | COVERED_WITH_TODO |
| MVP-WORLD-030 | §4.6 / L301 | WORLD | 普通矿脉挖空后不自动补充 | M3 | NOT_IMPLEMENTED | 无 | 无 | R7（`resourceCores` → M2）+ R1 T-8.2（间接） | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-031 | §4.6 / L302 | WORLD | 石矿岛生成 1 个资源核心 | M3 | PARTIAL | `BlockRegistryTest.resourceCoreIsUnbreakableUnplaceableAndEmissive` | M1 截图（测试世界 (2,64,2)，非正式岛布局） | R1 T-8.2 + R7（→ M2） | M3 | COVERED_WITH_TODO |
| MVP-WORLD-032 | §4.6 / L303 | WORLD | 资源核心无法被任何方式移除 | M3 | IMPLEMENTED | `PlayerPhysicsTest.unbreakableBlockIsNeverBroken` + `WorldTest.rejectedBreakOfEmissiveBlockKeepsRegistryConsistent` | M1.5 §5.4 | 无（已提前于计划关闭） | — | COVERED |
| MVP-WORLD-033 | §4.6 / L304 | WORLD | 核心按周期在范围内再生矿石 | M3 | NOT_IMPLEMENTED | 无 | 无 | R7 `LevelMeta.java:16`「`resourceCores`（资源核心的累积产出属 **M2**）」 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-034 | §4.6 / L305 | WORLD | 再生速率 ≤ 采矿速率的 1/50 | M3 | NOT_IMPLEMENTED | 无 | 无 | 随 033（R7） | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-035 | §4.6 / L306 | WORLD | 再生遇玩家方块时跳过顺延，不覆盖 | M3 | NOT_IMPLEMENTED | 无 | 无 | 随 033（R7）；**无独立登记** | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-036 | §4.6 / L307 | WORLD | 资源核心再生不做离线计算 | M3 | NOT_IMPLEMENTED | 无 | 无 | 随 033（R7）；无独立登记 | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-037 | §4.6 / L313 | WORLD | 石矿岛核心位于岛中心表面层下一格 | M3 | NOT_IMPLEMENTED | 无 | 无 | 随 033（R7） | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-038 | §4.6 / L313 | WORLD | 石矿岛核心刷新周期 180 秒 | M3 | NOT_IMPLEMENTED | 无 | 无 | 随 033（R7） | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-039 | §4.6 / L313 | WORLD | 石矿岛核心每次刷新 1 格矿石 | M3 | NOT_IMPLEMENTED | 无 | 无 | 随 033（R7） | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-040 | §4.6 / L313 | WORLD | 再生范围 5×5 水平、纵向 y−2 至 y+1 | M3 | NOT_IMPLEMENTED | 无 | 无 | 随 033（R7） | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-041 | §4.6 / L326–329 | WORLD | 三重防软锁同时生效 | M3 | NOT_IMPLEMENTED | 无 | 无 | 032（已 COVERED）+ 033（R7 → M2）+ MVP-MOB-027/028（R1 T-8.3 → M2） | M3 | DEFERRED_WITH_RECORD |
| MVP-WORLD-042 | §4.7 / L337–342 | WORLD | Seed 只改变外观与资源分布，不改进度结构 | M3 | PARTIAL | `SaveManagerTest.levelMetaRecordsTheFactsNeededToDiagnoseASave` | M1 §2-12 | R1 T-8.2（生成器不使用 seed） | M3 | COVERED_WITH_TODO |
| MVP-WORLD-043 | §4.7 / L346 | WORLD | 玩家可看到 Seed 并可手动输入 | M3 | PARTIAL | 无 UI 断言 | M1.5 截图（新世界提示含 `seed=`）；仅 JVM 属性 `skyisland.seed` 可设 | DRIFT-B-11；**游戏内 UI 无登记** | M3 | COVERED_WITH_TODO |

### 2.3 BLOCK — 方块与地形（25 条）

> **本节判定受两条新发现支配，先看这两条再读表：**
> - **G1**：13 种 MVP 玩家方块只注册了 **8** 种（缺 `log` / `leaves` / `iron_ore` / `coal_ore` / `torch` / `wooden_door`）。其中 `iron_ore` / `coal_ore` 缺失会切断 R03 铁锭与 R11 手枪弹的闭环。
> - **G13（quality-lead 复核新增）**：**每种方块的「掉落物」列完全没有实现**。`Player.updateMining()` L586 一律 `inventory.add(hit.blockRuntimeId(), 1)` —— **所有方块掉落自身**。于是：石头掉石头（PRD 要**圆石**）、草方块掉草方块（PRD 要**泥土**）、玻璃掉玻璃（PRD 要**无**）。`TECH_DESIGN_v0.1 §F.3` 第 744–746 行明写「**默认掉自身是最省事的写法，但会在这里直接错**」——这个被明文预警的坑，实际踩进去了，且**无人登记**。

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-BLOCK-001 | §5.1 / L359 | BLOCK | 草方块存在，硬度 0.6，**掉泥土 ×1** | M1 | PARTIAL（ID 为 `grass_block`，非 PRD 的 `skyisland:grass`；掉落自身） | `BlockRegistryTest`（仅数量口径 10） | M1 §2-8 | **无登记 → G1 + G13**；stable ID 漂移（`grass`→`grass_block`） | M3 | **GAP** |
| MVP-BLOCK-002 | §5.1 / L360 | BLOCK | 泥土存在，硬度 0.5，掉泥土 ×1 | M1 | IMPLEMENTED | 无逐块参数断言 | M1 §2-6 | 无 | — | COVERED_WITH_TODO |
| MVP-BLOCK-003 | §5.1 / L361 | BLOCK | 石头存在，空手 1.5 秒，**掉圆石 ×1** | M1 | PARTIAL（挖掘耗时 1.5 s ✓；**掉落自身非圆石**） | `PlayerPhysicsTest`（耗时）+ M1 §2-6 | M1 §2-6 | **无登记 → G13**（PRD 要求圆石） | M3 | **GAP** |
| MVP-BLOCK-004 | §5.1 / L362 | BLOCK | 圆石存在，硬度 2.0，掉圆石 ×1 | M1 | IMPLEMENTED | 无逐块参数断言 | M1 §2-6 | 无 | — | COVERED_WITH_TODO |
| MVP-BLOCK-005 | §5.1 / L363 | BLOCK | 原木 `skyisland:log` 存在 | M1 | NOT_IMPLEMENTED | 无 | 无 | **无登记 → G1** | M2 | **GAP** |
| MVP-BLOCK-006 | §5.1 / L364 | BLOCK | 木板存在，硬度 2.0 | M1 | PARTIAL（ID 为 `oak_planks`，非 PRD 的 `skyisland:planks`） | 无逐块参数断言 | M1 §2-8 | stable ID 漂移（`planks`→`oak_planks`），**未裁决** | M3 | COVERED_WITH_TODO |
| MVP-BLOCK-007 | §5.1 / L365 | BLOCK | 树叶存在，硬度 0.2，无掉落 | M1 | NOT_IMPLEMENTED | 无 | 无 | **无登记 → G1** | M2 | **GAP** |
| MVP-BLOCK-008 | §5.1 / L366 | BLOCK | 玻璃存在，硬度 0.3，**无掉落** | M1 | PARTIAL（已注册；**掉落自身**） | `ChunkMesherTest`（透明子网格） | M1 §2-6 | **无登记 → G13** | M3 | **GAP** |
| MVP-BLOCK-009 | §5.1 / L367 | BLOCK | 铁矿石存在，空手 3.5 秒，掉铁矿石 | M1 | NOT_IMPLEMENTED | 无 | 无 | **无登记 → G1**（切断 R03/R11 闭环） | M2 | **GAP** |
| MVP-BLOCK-010 | §5.1 / L368 | BLOCK | 煤炭矿石存在，空手 3.0 秒，**掉煤炭（物品）** | M1 | NOT_IMPLEMENTED | 无 | 无 | **无登记 → G1** | M2 | **GAP** |
| MVP-BLOCK-011 | §5.1 / L369 | BLOCK | 沙子存在，硬度 0.5，堆叠 64 | M1 | IMPLEMENTED | 无逐块参数断言 | M1 §2-6 | 无 | — | COVERED_WITH_TODO |
| MVP-BLOCK-012 | §5.1 / L370 | BLOCK | 火把存在，硬度 0.0（0.1 秒），自发光 | M1 | NOT_IMPLEMENTED | 无 | 无 | **无登记 → G1**（火把缺失 → MVP-WORLD-023 的光照无载体） | M2 | **GAP** |
| MVP-BLOCK-013 | §5.1 / L371 | BLOCK | 木门存在，硬度 1.0 | M1 | NOT_IMPLEMENTED | 无 | 无 | **无登记 → G1** | M2 | **GAP** |
| MVP-BLOCK-014 | §5.1 / L385 | BLOCK | 沙子放置后悬空不下落 | M1 | IMPLEMENTED | 无专门断言 | M1 试玩 | 无 | — | COVERED_WITH_TODO |
| MVP-BLOCK-015 | §5.1 / L387 | BLOCK | 树叶不掉落树苗，MVP 无树苗物品 | M1 | PARTIAL（**因树叶未注册而平凡成立**） | 无 | 无 | 随 G1；须在树叶注册后复验 | M3 | COVERED_WITH_TODO |
| MVP-BLOCK-016 | §5.1 / L388 | BLOCK | 木门可右键开关，关闭阻挡通行，开启无碰撞 | M3 | NOT_IMPLEMENTED | 无 | 无 | 随 G1；**交互行为无单独登记** | M3 | **GAP** |
| MVP-BLOCK-017 | §5.1 / L380 | BLOCK | 方块数量口径为 13 + 1 | M1 | PARTIAL（实际 **8 + 1**，另多 1 个 Alpha 的 `stone_bricks`） | `BlockRegistryTest.m1SubsetHasExpectedSize`（`EXPECTED_BLOCK_COUNT = 10`） | 无 | **无登记 → G1**；石砖提前注册见 §6.2 R2 | M3 | **GAP** |
| MVP-BLOCK-018 | §5.1.1 / L393 | BLOCK | `resource_core` 在注册表有完整 ID 与属性 | M1 | IMPLEMENTED | `BlockRegistryTest.resourceCoreIsUnbreakableUnplaceableAndEmissive` | M1.5 §5.4 | 无（附注：自发光 15，TECH §F.5 写 6，属实现↔技术文档偏差） | — | COVERED |
| MVP-BLOCK-019 | §5.1.1 / L397 | BLOCK | 资源核心不可被挖掘/爆炸/环境移除 | M3 | IMPLEMENTED | 同 018 + `WorldTest` 拒绝路径 | M1.5 §5.4 | 无（已提前关闭） | — | COVERED |
| MVP-BLOCK-020 | §5.1.1 / L397 | BLOCK | 资源核心不可被玩家拾取 | M3 | IMPLEMENTED | 无直接断言 | 无 | 无 | — | COVERED_WITH_TODO |
| MVP-BLOCK-021 | §5.1.1 / L397 | BLOCK | 资源核心不可被玩家放置 | M3 | IMPLEMENTED | `WorldTest.placeBlockRejectsUnplaceableBlock` | 无 | 无 | — | COVERED |
| MVP-BLOCK-022 | §5.1.1 / L400 | BLOCK | 资源核心不出现在任何玩家物品列表 | M3 | IMPLEMENTED | 无直接断言 | 无 | 无 | — | COVERED_WITH_TODO |
| MVP-BLOCK-023 | §5.1.1 / L397 | BLOCK | 资源核心 drops 为空 | M3 | IMPLEMENTED（破坏路径不可达） | 无 | 无 | 无 | — | COVERED_WITH_TODO |
| MVP-BLOCK-024 | §5.1.1 / L397 | BLOCK | 资源核心按稳定字符串 ID 存档 | M1 | IMPLEMENTED | `ChunkSerializerTest`（palette 稳定 ID，17） | M1 §2-12 | 无 | — | COVERED |
| MVP-BLOCK-025 | §5.1.1 / L402 | BLOCK | 读档遇未知方块 ID 有明确降级行为 | M1 | IMPLEMENTED | `BlockRegistryTest`（13）+ `ChunkSerializerTest` 损坏拒绝 + `SaveManagerTest.corruptedChunkFileIsSkipped…` | M1 §2-12 | 无 | — | COVERED |

### 2.4 MINE — 挖掘与放置（18 条）

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-MINE-001 | §5.2 / L409 | MINE | 挖掘与放置射线距离 5.0 格 | M1 | IMPLEMENTED | `DdaRaycasterTest`（16）+ `PlayerPhysicsTest.placementIsRejectedWhenNothingIsInReach` | M1 §2-5 / §2-8 | 无 | — | COVERED |
| MVP-MINE-002 | §5.2 / L410 | MINE | 挖掘目标由屏幕中心准星射线命中首个实体方块表面决定 | M1 | IMPLEMENTED | `DdaRaycasterTest` | M1 §2-5（`hit(0,61,0) face=UP dist=1.62`） | 无 | — | COVERED |
| MVP-MINE-003 | §5.2 / L411 | MINE | 持续按住左键累计进度，达耗时即破坏 | M1 | IMPLEMENTED | `PlayerPhysicsTest.lookingStraightDownAndHoldingAttackBreaksTheBlockUnderfoot` | M1 §2-6 | 无 | — | COVERED |
| MVP-MINE-004 | §5.2 / L411 | MINE | 松开左键后挖掘进度归零 | M1 | IMPLEMENTED | `PlayerPhysicsTest.releasingAttackResetsMiningState` / `switchingTargetResetsMiningProgress` | M1.5 §5.4 | 无 | — | COVERED |
| MVP-MINE-005 | §5.2 / L412 | MINE | 挖掘时方块表面显示随进度增长的 **10 段裂纹** | M1 | **IMPLEMENTED**（M1.5 `CrackOverlay`） | `CrackOverlayTest`（10）+ `M1_5UiSelfTest.MINE_BY_MOUSE`（4 断言） | M1.5 §5.4 像素差分探针 `CrackDiffProbe`（4185 px / 6 连通分量） | R6 **T-8.9 视觉部分 CLOSED** | — | COVERED |
| MVP-MINE-006 | §5.2 / L412 | MINE | **每段裂纹伴随一次挖掘音效**（占位音） | M1 | **NOT_IMPLEMENTED** | 无 | 无（`TECH_DESIGN_v0.1.1 §V′.5`：音效无法由断言证明，记为**未验证**） | R6 **T-8.9 → M2**（`TECH_DESIGN_v0.1.1.md` L243-252 + `M1_5_..._REPORT §6.1`） | M2 | DEFERRED_WITH_RECORD |
| MVP-MINE-007 | §5.2 / L413 | MINE | 破坏瞬间生成 **8–12 个方块颜色粒子（占位）** | M1 | **NOT_IMPLEMENTED** | 无 | 无 | R6 **T-8.9 → M2**（`TECH_DESIGN_v0.1.1.md` L247 明文引用 PRD L413）；**DRIFT-A-01**（PRD 8–12 vs TECH 3–5 互斥，待裁决） | 最迟 M3（占位粒子属 MVP，见 §3） | DEFERRED_WITH_RECORD |
| MVP-MINE-008 | §5.2 / L413 | MINE | 破坏瞬间播放破坏音效 | M1 | **NOT_IMPLEMENTED** | 无 | 无 | R6 **T-8.9 → M2** | M2 | DEFERRED_WITH_RECORD |
| MVP-MINE-009 | §5.2 / L414 | MINE | 掉落物向玩家磁吸并进入 2 格自动拾取 | M2 | NOT_IMPLEMENTED | 无 | 无 | R1 **T-8.3**（无实体/掉落物实体 → M2） | M2 | DEFERRED_WITH_RECORD |
| MVP-MINE-010 | §5.2 / L414 | MINE | 背包满时掉落物原地停留 5 分钟后消失 | M2 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.3（间接）；**行为细则无单独登记** | M2 | DEFERRED_WITH_RECORD |
| MVP-MINE-011 | §5.2 / L415 | MINE | 放置目标位置为命中面外侧的相邻空格 | M1 | IMPLEMENTED | `WorldTest.placeBlockRejectsOccupiedCell` | M1 §2-8 | 无 | — | COVERED |
| MVP-MINE-012 | §5.2 / L416 | MINE | 无邻接支撑的纯空气位置不允许放置 | M1 | IMPLEMENTED | `WorldTest.placeBlockRejectsWithoutAdjacentSupport` | M1 §2-4 | 无 | — | COVERED |
| MVP-MINE-013 | §5.2 / L417 | MINE | y < 1 时放置被拒绝 | M1 | IMPLEMENTED | `WorldTest.placeBlockRejectsAirAndOutOfRangeY` | M1 §2-4 | 无 | — | COVERED |
| MVP-MINE-014 | §5.2 / L418 | MINE | y > 127 时放置被拒绝 | M1 | IMPLEMENTED | 同 013 | M1 §2-4 | 无 | — | COVERED |
| MVP-MINE-015 | §5.2 / L419 | MINE | 目标格有玩家/怪物实体时放置被拒绝 | M2 | PARTIAL（**仅玩家**：`VoxelObstruction` + `placementIsRejectedWhenTheTargetCellIsInsideThePlayer`；怪物因实体系统缺失不可验） | `PlayerPhysicsTest.placementIsRejectedWhenTheTargetCellIsInsideThePlayer` | M1 §2-8 | R1 T-8.3（怪物侧未验） | M2 | COVERED_WITH_TODO |
| MVP-MINE-016 | §5.2 / L419 | MINE | 放置被禁止时**准星显示禁止高亮** | M2 | NOT_IMPLEMENTED（`HudRenderer.drawCrosshair` 固定色常量，零条件分支） | 无 | 无 | **无登记 → G7**；DRIFT-B-03（技术文档无条目） | M3 | **GAP** |
| MVP-MINE-017 | §5.2 / L420 | MINE | **每种方块类型**配置放置音效（占位音） | M1 | NOT_IMPLEMENTED（无音频后端） | 无 | 无 | **无登记 → G4**；DRIFT-A-03（每方块音效 vs 通用占位音未裁决） | M2 | **GAP** |
| MVP-MINE-018 | §5.2 / L423 | MINE | 掉落物坠入虚空（y < -8）立即销毁 | M2 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.3（间接） | M2 | DEFERRED_WITH_RECORD |

### 2.5 SURV — 生存 / 生命 / 死亡 / 重生 / 移动（21 条）

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-SURV-001 | §5.3 / L432 | SURV | 玩家生命上限 20 点 | M2 | NOT_IMPLEMENTED（`Player` 无 `health` 字段） | 无 | 无 | R2「生命值与饥饿」+ R8 `PlayerState.java:18` + R10 `SkyIslandGame.java:55` | M2 | DEFERRED_WITH_RECORD |
| MVP-SURV-002 | §5.3 / L433 | SURV | MVP 完全不启用饥饿 | M1 | IMPLEMENTED | 无专门断言 | M1.5 试玩（无饥饿条） | 无 | — | COVERED_WITH_TODO |
| MVP-SURV-003 | §5.3 / L434 | SURV | 坠落伤害 = `max(0, floor(坠落格数 − 3))` | M1 | **NOT_IMPLEMENTED**（全仓 grep `fallDamage` 零命中） | **无** | **M1 §2 从未验收此项，但 M1 报告 §7.1 仍判 PASS**（见 §9 历史门禁问题） | **无登记 → G8**：R2 只点名"生命值与饥饿"，R8 只说"M1 未纳入 `fallDistance`"，**均未写明归属 M2** | M2 | **GAP** |
| MVP-SURV-004 | §5.3 / L435 | SURV | 虚空致死不结算普通坠落伤害 | M1 | PARTIAL（平凡成立：根本没有坠落伤害） | `CoordsTest.voidDeathThreshold` | M1 §2-10 | 须在 SURV-003 关闭后重新求值 | M2 | COVERED_WITH_TODO |
| MVP-SURV-005 | §5.3 / L436 | SURV | 死亡时显示提示「你倒下了」 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「生命值与饥饿」（宿主点名，未点名本条） | M2 | DEFERRED_WITH_RECORD |
| MVP-SURV-006 | §5.3 / L436 | SURV | 死亡 3 秒后在重生点重生 | M2 | NOT_IMPLEMENTED（虚空死亡即时重生，无 3 秒延迟） | 无 | 无 | 同 005；`Player.java:33-35` 注释称"已记入 M1 报告"，但 M1 §6.4 未逐条点名 | M2 | DEFERRED_WITH_RECORD |
| MVP-SURV-007 | §5.3 / L437 | SURV | 死亡掉落全部背包与快捷栏物品 | M2 | NOT_IMPLEMENTED | 无 | 无 | R1 **T-8.3**（掉落物实体 → M2）+ R2「掉落物实体」 | M2 | DEFERRED_WITH_RECORD |
| MVP-SURV-008 | §5.3 / L437 | SURV | 死亡掉落物 5 分钟内可被拾回 | M2 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.3（间接） | M2 | DEFERRED_WITH_RECORD |
| MVP-SURV-009 | §5.3 / L438 | SURV | 重生后生命恢复为 20/20 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「生命值与饥饿」 | M2 | DEFERRED_WITH_RECORD |
| MVP-SURV-010 | §5.3 / L439 | SURV | 「死亡掉落物品」设置项可关闭 | M3 | NOT_IMPLEMENTED（`GameSettings` 无该字段） | 无 | 无 | **无登记 → G2**（设置系统已实现却漏项） | M3 | **GAP** |
| MVP-SURV-011 | §5.3.1 / L448 | SURV | 系统记录 lastSafePosition | M2 | IMPLEMENTED | `PlayerPhysicsTest.fallingIntoVoidRespawnsAtLastSafePosition` | M1 §2-11（落点 (0.50, 64.00, −24.01)） | 无 | — | COVERED |
| MVP-SURV-012 | §5.3.1 / L449 | SURV | lastSafePosition 只在**稳定站立 ≥ 0.5 秒**后更新 | M2 | PARTIAL（代码用 **0.25 s** 节流，`Player.java:74`） | `PlayerPhysicsTest`（安全点更新路径） | M1 §2-11 | **数值与 PRD 不符（0.25 vs 0.5）且无登记、无裁决** | M2 | **CONFLICT** |
| MVP-SURV-013 | §5.3.1 / L450 | SURV | 虚空死亡的掉落物生成在 lastSafePosition 附近 | M2 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.3 + R11（PRD §11 M2 通过标准第 6 条） | M2 | DEFERRED_WITH_RECORD |
| MVP-SURV-014 | §5.3.1 / L450 | SURV | 位置不合法时螺旋搜索，掉落物绝不落入虚空 | M2 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.3（无掉落物实体，无从附着） | M2 | DEFERRED_WITH_RECORD |
| MVP-SURV-015 | §5.3.1 / L457 | SURV | 默认重生点为 (0,64,0) | M2 | PARTIAL（重生走 `lastSafePosition`；固定 (0,64,0) 仅出现在读档清洗路径） | `PlayerPhysicsTest.sanitizeFindsAFreeSpotWhenTheSavedPositionIsBuried` | M1 §2-11 | 语义差异无登记；坐标 ±1 层见 TECH D1 | M2 | COVERED_WITH_TODO |
| MVP-SURV-016 | §5.3.1 / L458 | SURV | 重生点不合法时螺旋搜索 ≤16 格 | M2 | IMPLEMENTED（**仅读档清洗路径**） | 同 015 | M1 §2-11 | 死亡重生路径未走此分支 | M2 | COVERED_WITH_TODO |
| MVP-SURV-017 | §5.3.1 / L459 | SURV | 无合法点时生成临时地台，保证不软锁 | M2 | IMPLEMENTED（**仅读档清洗路径**） | `SaveManagerTest.playerBuriedInsideBlocksGetsRelocatedBySanitisation` | M1 §2-11 | 死亡重生路径未走此分支 | M2 | COVERED_WITH_TODO |
| MVP-SURV-018 | §5.3.1 / L460 | SURV | 拆掉小屋后重生仍可用规则兜底 | M3 | NOT_IMPLEMENTED（无小屋可拆） | 无 | 无 | R1 T-8.2（无正式岛屿生成器） | M3 | DEFERRED_WITH_RECORD |
| MVP-SURV-019 | §9.2 / L922 | SURV | 玩家可跳跃 | M1 | IMPLEMENTED | `PlayerPhysicsTest.jumpClearsOneBlockButNotTwo`（1.1778 格） | M1 §2-4 | 无 | — | COVERED |
| MVP-SURV-020 | §9.2 / L922 | SURV | 玩家受重力影响 | M1 | IMPLEMENTED | `PlayerPhysicsTest.gravityPullsThePlayerDownOntoTheTerrain` / `terminalVelocityCapsFallSpeed` | M1 §2-4 | 无 | — | COVERED |
| MVP-SURV-021 | §9.2 / L922 | SURV | 玩家与方块发生碰撞 | M1 | IMPLEMENTED | `PlayerPhysicsTest.playerDoesNotSinkOrTunnelThroughTheTerrain` + `AABBTest`（9） | M1 §2-4 | 无 | — | COVERED |

### 2.6 COMBAT — 枪械 / 弹药 / 射击（30 条）

> 全部未实现。其中 **20 条**能引到登记出处（枪械/弹药/换弹在 M1 §6.4 与 M1.5 §6.4 均被点名），
> **6 条**属"表现层反馈"且**无人登记**（E1 §2.3 已点名，本报告沿用并补全），
> **3 条**因 PRD 内部冲突未裁决判 `CONFLICT`，**1 条**键位已预置判 `COVERED_WITH_TODO`。
>
> **M2.2 局部更新（2026-09-23）**：`MVP-COMBAT-020` 一行已按 PRD v0.3.2-r1 改写为
> 「移动**不**打断换弹」并判 `COVERED`。**本节其余行的"未实现"是 M1.5 时点的快照，未随 M2 重算** ——
> 本节不是当前实现状态的来源，只用于核对"当初每条需求有没有登记出处"。

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-COMBAT-001 | §5.4.1 / L494 | COMBAT | 手枪存在，为 MVP 唯一枪械 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「枪械/弹药/换弹」+ R3 + R5 §S（`combat` → M2） | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-002 | §5.4.1 / L494 | COMBAT | 手枪单发伤害 8 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 + R11（PRD §11 M2 通过标准第 2 条） | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-003 | §5.4.1 / L494 | COMBAT | 弹匣容量 12 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-004 | §5.4.1 / L494 | COMBAT | 射速 4.0 发/秒 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-005 | §5.4.1 / L494 | COMBAT | 有效射程 32 格 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-006 | §5.4.1 / L494 | COMBAT | 手枪合成材料 铁锭×4+火药×2+木板×2 | M2 | NOT_IMPLEMENTED | 无 | 无 | **DRIFT-C-01：PRD 5.4.1 标【MVP 必须】但 R14 配方标【Alpha 必须】→ PRD 内部冲突，未裁决** | 待裁决 | **CONFLICT** |
| MVP-COMBAT-007 | §5.4.2 / L503 | COMBAT | 手枪弹存在且适配手枪 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「弹药」+ R5 §S | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-008 | §5.4.2 / L503 | COMBAT | 手枪弹堆叠上限 128 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 007 | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-009 | §5.4.2 / L656 | COMBAT | 铁锭×1+火药×1 → 手枪弹×8 | M3 | NOT_IMPLEMENTED | 无 | 无 | R4 §S′ L179（`item.CraftingService` → M1 不实现）+ R2「合成服务」 | M3 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-010 | §5.4.3 / L516 | COMBAT | 左键单击发射单发 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2 枪械 | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-011 | §5.4.3 / L517 | COMBAT | Hitscan 取沿射线最近的合法碰撞结果 | M2 | NOT_IMPLEMENTED（射线只覆盖方块，无实体） | `DdaRaycasterTest`（16，全为方块射线） | 无 | R2 + R11（PRD §11 M2 通过标准第 4 条） | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-012 | §5.4.3 / L517 | COMBAT | 隔着实体方块不能命中怪物 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 011 | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-013 | §5.4.3 / L517 | COMBAT | 最近命中为方块时不结算伤害 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 011 | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-014 | §5.4.3 / L518 | COMBAT | 瞄准时 FOV 70 → 45 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2 枪械 | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-015 | §5.4.3 / L518 | COMBAT | 瞄准时移动速度降至 60% | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 014 | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-016 | §5.4.3 / L519 | COMBAT | 按 R 触发换弹 | M2 | PARTIAL（键位已绑定/可重绑/落盘，**无消费方**） | `KeyBindingsTest.defaultsMatchThePrdContract`（键位层） | 无 | R9 `Action.java:50`「已绑定，玩法消费方在 **M2**」 | M2 | COVERED_WITH_TODO |
| MVP-COMBAT-017 | §5.4.3 / L519 | COMBAT | 手枪换弹耗时 1.2 秒 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「换弹」+ R11（M2 通过标准第 3 条） | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-018 | §5.4.3 / L520 | COMBAT | 弹匣未满且有弹药才可换弹 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 017 | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-019 | §5.4.3 / L520 | COMBAT | 换弹过程中不能开枪 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 017 | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-020 | §5.4.3 / L520 → **v0.3.2-r1 行 525** | COMBAT | **移动不打断换弹**（原「移动可打断换弹」，M2.2 修订） | M2 → M2.2 | IMPLEMENTED（M2.2） | `CombatControllerTest.walkingDoesNotInterruptReload`、`CombatCoreTest.reloadRunsToCompletionOnAFixedStepClock` | M2 自测阶段 6/15 `RELOAD_WHILE_WALKING`：边走边换步数 ≥ 70、位移 ≥ 3 格、完成事件恰 1 次 | **语义按 v0.3.2-r1 读**：PRD 5.4.3 已把「移动打断换弹 = 取消换弹」废止；本行原写的"移动可打断换弹"是 v0.3.2 旧口径，不得再据此判缺口 | — | COVERED |
| MVP-COMBAT-021 | §5.4.3 / L521 | COMBAT | 满弹匣换弹允许且余弹回背包 | M2 | NOT_IMPLEMENTED | 无 | 无 | **DRIFT-A-06：弹药不足时"完全拒绝" vs "部分填充" PRD 未定义，TECH §M.5 单方取"完全拒绝"** | 待裁决 | **CONFLICT** |
| MVP-COMBAT-022 | §5.4.3 / L522 | COMBAT | 32 格内伤害 100% | M2 | NOT_IMPLEMENTED | 无 | 无 | R2 枪械 + R11（M2 通过标准第 5 条） | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-023 | §5.4.3 / L522 | COMBAT | 超程每格 ×0.9，下限 20% | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 022 | M2 | DEFERRED_WITH_RECORD |
| MVP-COMBAT-024 | §5.4.3 / L524 | COMBAT | 命中怪物有音效与受击闪白 | M2 | NOT_IMPLEMENTED | 无 | 无 | **无登记**（E1 §2.3 新增） | M2 | **GAP** |
| MVP-COMBAT-025 | §5.4.3 / L524 | COMBAT | 命中方块有溅射粒子与撞击音效 | M2 | NOT_IMPLEMENTED | 无 | 无 | **无登记**（E1 §2.3 新增）；与 DRIFT-A-01 同源（3–5 vs 8–12） | M2 | **GAP** |
| MVP-COMBAT-026 | §5.4.3 / L525 | COMBAT | 射击有 0.05 秒曳光轨迹 | M2 | NOT_IMPLEMENTED | 无 | 无 | **无登记**（E1 §2.3 新增，非音频但同属表现层反馈） | M2 | **GAP** |
| MVP-COMBAT-027 | §5.4.3 / L526 | COMBAT | 准星普通 / 瞄准两种形态 | M2 | PARTIAL（**只有普通十字**，`HudRenderer.drawCrosshair` 固定色） | 无形态差异断言 | M1.5 §7.4 截图 | **无登记**（与 MVP-HUD-002 同源） | M2 | **GAP** |
| MVP-COMBAT-028 | §5.4.3 / L527 | COMBAT | 数字键 1–4 切换快捷栏槽位 | M2 | PARTIAL（实现为 **1–9** + 滚轮，TECH §I.7 裁定） | `InputMapperActionsTest.numberKeysSelectHotbarSlots` | M1.5 §2 | **DRIFT-A-04：PRD 6.6「1–4」vs PRD 6.1「1–9」互斥，PRD 未修订** | 待裁决 | **CONFLICT** |
| MVP-COMBAT-029 | §5.4.3 / L528 | COMBAT | 无弹药开枪播放空枪音效 | M2 | NOT_IMPLEMENTED | 无 | 无 | **无登记**（E1 §2.3 新增） | M2 | **GAP** |
| MVP-COMBAT-030 | §5.4.3 / L528 | COMBAT | 无弹药时 HUD 提示「弹药不足」 | M2 | NOT_IMPLEMENTED | 无 | 无 | **无登记 → G6** | M2 | **GAP** |

### 2.7 MOB — 怪物 / AI / 刷怪 / 威胁等级 / 掉落（28 条）

> 怪物与 AI 整体未实现，且**宿主系统本身**被 R1 T-8.3 / R2 / R3 明确点名 → 全部满足"点名即 C"。
> 其中 DRIFT-B-08 / B-09 指出刷怪选点约束与怪物掉落表在**技术文档里完全没有条目**，
> 属"实现时容易凭感觉写"的高风险项，已在 Technical Debt 列标注。

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-MOB-001 | §5.5.1 / L541 | MOB | 近战怪存在，为 MVP 唯一怪物 | M2 | NOT_IMPLEMENTED | 无 | 无 | R1 **T-8.3** + R2「怪物与 AI」+ R3 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-002 | §5.5.1 / L541 | MOB | 近战怪生命 20 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-003 | §5.5.1 / L541 | MOB | 近战怪每次攻击 4 点伤害 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-004 | §5.5.1 / L541 | MOB | 近战怪速度 2.0 格/秒 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-005 | §5.5.2 / L551 | MOB | 怪物能检测玩家 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001；具体数值 PRD 未定义（TECH §K.3 补缺省） | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-006 | §5.5.2 / L552 | MOB | 玩家进入范围后怪物追击 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-007 | §5.5.2 / L553 | MOB | 怪物只在水平方向朝玩家移动 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-008 | §5.5.2 / L554 | MOB | 前方碰撞时尝试左右偏转 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-009 | §5.5.2 / L555 | MOB | 前方为虚空时停止或换向 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-010 | §5.5.2 / L556 | MOB | 接近玩家后发起攻击 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-011 | §5.5.2 / L557 | MOB | 无法到达时允许卡住，不做全图寻路 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-012 | §5.5.2 / L563 | MOB | 禁止动态寻路（NavMesh / 大规模 A*） | M2 | NOT_IMPLEMENTED（因无 AI 而平凡成立） | 无 | 无 | 同 001（随 AI 一并验证） | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-013 | §5.5.2 / L564 | MOB | 禁止跳跃寻路 | M2 | NOT_IMPLEMENTED（平凡成立） | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-014 | §5.5.2 / L565 | MOB | 禁止破坏方块寻路 | M2 | NOT_IMPLEMENTED（平凡成立） | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-015 | §5.5.2 / L568 | MOB | 怪物不会主动踏入虚空 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-016 | §5.5.2 / L568 | MOB | 怪物坠入虚空立即移除且无掉落 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-017 | §5.5.3 / L574 | MOB | 只在黄昏与夜晚刷怪 | M3 | NOT_IMPLEMENTED | 无 | 无 | R11（PRD §11 M3 交付内容「夜间刷怪」）+ R2 | M3 | DEFERRED_WITH_RECORD |
| MVP-MOB-018 | §5.5.3 / L574 | MOB | 黎明后存活 30 秒的怪物被移除 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 017；**技术文档无条目** | M3 | DEFERRED_WITH_RECORD |
| MVP-MOB-019 | §5.5.3 / L575 | MOB | 刷怪点距玩家水平 16–32 格且可站立 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 017；**DRIFT-B-08（技术文档无刷怪选点条目）** | M3 | DEFERRED_WITH_RECORD |
| MVP-MOB-020 | §5.5.3 / L576 | MOB | 距开局小屋 12 格内不刷怪 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 017；DRIFT-B-08 | M3 | DEFERRED_WITH_RECORD |
| MVP-MOB-021 | §5.5.3 / L577 | MOB | 同时存活上限恒为 8 | M3 | NOT_IMPLEMENTED | 无 | 无 | R5 §S（`world.WorldTime` 昼夜推进 + 刷怪 → M3） | M3 | DEFERRED_WITH_RECORD |
| MVP-MOB-022 | §5.5.3 / L578 | MOB | 刷怪间隔恒为 8 秒 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 021 | M3 | DEFERRED_WITH_RECORD |
| MVP-MOB-023 | §5.5.3 / L579 | MOB | MVP 只刷近战怪（0% 远程/大型） | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 021 | M3 | DEFERRED_WITH_RECORD |
| MVP-MOB-024 | §5.5.4 / L590 | MOB | TL1 分段数值与之一致 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 021 + R5 §S | M3 | DEFERRED_WITH_RECORD |
| MVP-MOB-025 | §5.5.4 / L598 | MOB | MVP 全程使用 TL1 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 021 | M3 | DEFERRED_WITH_RECORD |
| MVP-MOB-026 | §5.5.4 / L601 | MOB | 威胁等级为配置数据而非硬编码 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 021；PRD 12.4 同口径 | M3 | DEFERRED_WITH_RECORD |
| MVP-MOB-027 | §5.5.6 / L620 | MOB | 近战怪 40% 掉铁矿石 ×1 | M2 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.3（宿主 → M2）+ **DRIFT-B-09（技术文档无怪物掉落表）** | M2 | DEFERRED_WITH_RECORD |
| MVP-MOB-028 | §5.5.6 / L621 | MOB | 近战怪 30% 掉煤炭 ×1 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 027 | M2 | DEFERRED_WITH_RECORD |

### 2.8 ITEM — 背包 / 物品 / 合成（16 条）

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-ITEM-001 | §5.6 / L633 | ITEM | 合成在背包界面内完成，无工作台 | M3 | NOT_IMPLEMENTED | 无 | 无 | R4 §S′ L179（`item.CraftingService` → M1 不实现）+ R2「合成服务」 | M3 | DEFERRED_WITH_RECORD |
| MVP-ITEM-002 | §5.6.1 / L639 | ITEM | 快捷栏 9 格 | M3 | IMPLEMENTED | `InventoryTest.newInventoryIsNineEmptySlotsWithFirstSelected`（17） | M1 §2-8 | 无（已提前关闭） | — | COVERED |
| MVP-ITEM-003 | §5.6.1 / L640 | ITEM | 主背包 27 格 | M3 | NOT_IMPLEMENTED（`Inventory.HOTBAR_SIZE = 9`，无 27 格） | 无 | 无 | R1 **T-8.8**（无 UI 屏 → M3）+ `Inventory.java:9-11` 类注释 | M3 | DEFERRED_WITH_RECORD |
| MVP-ITEM-004 | §5.6.1 / L642 | ITEM | 普通物品单格堆叠 64 | M3 | IMPLEMENTED | `ItemStackTest`（9） | M1 §2-7 | 无 | — | COVERED |
| MVP-ITEM-005 | §5.6.1 / L642 | ITEM | 弹药堆叠上限 128 | M2 | NOT_IMPLEMENTED（无弹药物品） | 无 | 无 | R2「弹药」+ R5 §S（`item` → M3） | M2 | DEFERRED_WITH_RECORD |
| MVP-ITEM-006 | §5.6.1 / L642 | ITEM | 枪械单格 1 把 | M2 | NOT_IMPLEMENTED | 无 | 无 | 同 005 | M2 | DEFERRED_WITH_RECORD |
| MVP-ITEM-007 | §5.6.1 / L643 | ITEM | 背包支持拖拽整理 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.8 → M3 | M3 | DEFERRED_WITH_RECORD |
| MVP-ITEM-008 | §5.6.2 / L650 | ITEM | R01 木板：原木×1 → 4 | M3 | NOT_IMPLEMENTED | 无 | 无 | R4 §S′ L179 + R2「合成服务」 | M3 | DEFERRED_WITH_RECORD |
| MVP-ITEM-009 | §5.6.2 / L651 | ITEM | R02 木棍：木板×2 → 4 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 008 | M3 | DEFERRED_WITH_RECORD |
| MVP-ITEM-010 | §5.6.2 / L652 | ITEM | R03 铁锭：铁矿石×1+煤炭×1 → 1 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 008；**另受阻于 G1（铁矿石/煤炭矿石未注册）** | M3 | DEFERRED_WITH_RECORD |
| MVP-ITEM-011 | §5.6.2 / L653 | ITEM | R06 火药：煤炭×2+沙子×1 → 2 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 008 | M3 | DEFERRED_WITH_RECORD |
| MVP-ITEM-012 | §5.6.2 / L654 | ITEM | R07 火把：煤炭×1+木棍×1 → 4 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 008 | M3 | DEFERRED_WITH_RECORD |
| MVP-ITEM-013 | §5.6.2 / L655 | ITEM | R08 玻璃：沙子×1+煤炭×1 → 1 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 008 | M3 | DEFERRED_WITH_RECORD |
| MVP-ITEM-014 | §5.6.2 / L656 | ITEM | R11 手枪弹：铁锭×1+火药×1 → 8 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 008 | M3 | DEFERRED_WITH_RECORD |
| MVP-ITEM-015 | §5.6.2 / L682 | ITEM | MVP 无木门配方 | M3 | IMPLEMENTED（因无合成系统而平凡成立） | 无 | 无 | 无 | — | COVERED_WITH_TODO |
| MVP-ITEM-016 | §5.6.2 / L679 | ITEM | 无熔炉设施 | M3 | IMPLEMENTED | 无 | 无 | 无 | — | COVERED_WITH_TODO |

### 2.9 HUT — 开局小屋与初始物资（15 条）

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-HUT-001 | §5.7 / L710 | HUT | 小屋位于主岛中心，地板顶面 y=64 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 **T-8.2**（无正式 IslandGenerator）+ **DRIFT-B-05**（技术文档无小屋条目） | M3 | DEFERRED_WITH_RECORD |
| MVP-HUT-002 | §5.7 / L711 | HUT | 小屋外框 9×9、内部 7×7 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M3 | DEFERRED_WITH_RECORD |
| MVP-HUT-003 | §5.7 / L712 | HUT | 内部 3 格层高，屋顶 y=67 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M3 | DEFERRED_WITH_RECORD |
| MVP-HUT-004 | §5.7 / L713 | HUT | 地板为 7×7 木板 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M3 | DEFERRED_WITH_RECORD |
| MVP-HUT-005 | §5.7 / L714 | HUT | 四面木板墙，南北各 1 面玻璃窗 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 001 | M3 | DEFERRED_WITH_RECORD |
| MVP-HUT-006 | §5.7 / L715 | HUT | 屋顶木板平顶且南面留 1 处未封顶 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 001；**"刻意的不完整"极易被当成 bug 修掉** | M3 | DEFERRED_WITH_RECORD |
| MVP-HUT-007 | §5.7 / L716 | HUT | 南墙中央有木门 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 001 + G1（木门未注册） | M3 | DEFERRED_WITH_RECORD |
| MVP-HUT-008 | §5.7.1 / L727 | HUT | 开局背包含手枪 ×1 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.2 + **DRIFT-B-06**（技术文档无初始物资条目）+ R2（无枪械物品） | M3 | DEFERRED_WITH_RECORD |
| MVP-HUT-009 | §5.7.1 / L728 | HUT | 开局背包含手枪弹 24 发 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 008 | M3 | DEFERRED_WITH_RECORD |
| MVP-HUT-010 | §5.7.1 / L729 | HUT | 开局背包含木板 32 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 008 | M3 | DEFERRED_WITH_RECORD |
| MVP-HUT-011 | §5.7.1 / L730 | HUT | 开局背包含火把 8 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 008 + G1（火把未注册） | M3 | DEFERRED_WITH_RECORD |
| MVP-HUT-012 | §5.7.1 / L731 | HUT | 开局背包含铁锭 4 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 008 | M3 | DEFERRED_WITH_RECORD |
| MVP-HUT-013 | §5.7.1 / L732 | HUT | 开局背包含煤炭 8 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 008 | M3 | DEFERRED_WITH_RECORD |
| MVP-HUT-014 | §5.7 / L721 | HUT | MVP 无刷石机、无农田 | M3 | IMPLEMENTED（负面要求成立） | 无 | 无 | 无 | — | COVERED_WITH_TODO |
| MVP-HUT-015 | §5.7.1 / L734 | HUT | 初始物资不含面包与小麦种 | M3 | IMPLEMENTED（负面要求成立） | 无 | 无 | 无 | — | COVERED_WITH_TODO |

### 2.10 HUD — HUD 元素（13 条）

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-HUD-001 | §6.1 / L744 | HUD | 准星绘制在屏幕正中 | M1 | IMPLEMENTED | `HudRenderer.drawCrosshair`（像素取证） | M1.5 §7.4（22 张截图） | 无 | — | COVERED |
| MVP-HUD-002 | §6.1 / L744 | HUD | 准星有普通与瞄准两种形态 | M2 | PARTIAL（**只有普通十字**） | 无形态差异断言 | M1.5 §7.4 | **无登记**（与 MVP-COMBAT-027 同源）；DRIFT-B-03 | M2 | **GAP** |
| MVP-HUD-003 | §6.1 / L744 | HUD | 准星对准可交互方块时高亮 | M1 | NOT_IMPLEMENTED | 无 | 无 | **无登记 → G7**；DRIFT-B-03（技术文档无 HUD 规格） | M3 | **GAP** |
| MVP-HUD-004 | §6.1 / L745 | HUD | 生命条以 10 颗心显示 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「生命值与饥饿」（宿主点名）+ DRIFT-B-03 | M2 | DEFERRED_WITH_RECORD |
| MVP-HUD-005 | §6.1 / L746 | HUD | 快捷栏 9 格并显示物品与数量 | M3 | IMPLEMENTED | `HudRenderer.drawHotbar`；**无单元断言** | M1.5 §7.4 | 无 | — | COVERED_WITH_TODO |
| MVP-HUD-006 | §6.1 / L746 | HUD | 数字键 1–9 高亮对应快捷栏槽 | M3 | IMPLEMENTED | `InputMapperActionsTest.numberKeysSelectHotbarSlots` + `HudModel.hotbarSelected` | M1.5 §7.4 | 无（口径按 TECH §I.7 的 1–9，见 MVP-COMBAT-028 的 CONFLICT） | — | COVERED |
| MVP-HUD-007 | §6.1 / L747 | HUD | 右下角显示弹匣与后备弹药 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「弹药」；**HUD 元素无独立登记（G11）** | M2 | DEFERRED_WITH_RECORD |
| MVP-HUD-008 | §6.1 / L748 | HUD | 右下角显示手持枪械**中文名** | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「枪械」+ **DRIFT-B-04（中文不可渲染，未裁决）** —— 双重阻断 | 待裁决 | **CONFLICT** |
| MVP-HUD-009 | §6.1 / L749 | HUD | 右上角显示天数与昼夜阶段 | M3 | NOT_IMPLEMENTED | 无 | 无 | R2「昼夜循环」+ R7（`worldTimeSeconds`/`dayCount` DEFERRED） | M3 | DEFERRED_WITH_RECORD |
| MVP-HUD-010 | §6.1 / L750 | HUD | 生命 ≤6 时生命条闪烁 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「生命值与饥饿」 | M2 | DEFERRED_WITH_RECORD |
| MVP-HUD-011 | §6.1 / L751 | HUD | 中下部显示即时提示（含「弹药不足」「背包已满」） | M2 | PARTIAL（`HudModel.eventMessage` 机制存在，但 5 处调用全为生命周期消息） | 无 | M1.5 §7.4 | **无登记 → G6** | M3 | **GAP** |
| MVP-HUD-012 | §6.1 / L751 | HUD | 即时提示 2 秒后淡出 | M2 | PARTIAL（实际 0.8 / 1.5 / 4.0 / 6.0 秒依调用而异，无统一 2 秒） | 无 | 无 | **无登记** | M3 | **GAP** |
| MVP-HUD-013 | §6.1 / L752 | HUD | F3 可切换左上角调试信息 | M1 | IMPLEMENTED | `HudModel.showDebugOverlay` | M1.5 §7.4（F3 overlay 截图） | 无 | — | COVERED |

### 2.11 UI — 背包界面 / 合成界面 / 主菜单（25 条）

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-UI-001 | §6.2 / L762 | UI | 按 E 打开与关闭背包 | M3 | NOT_IMPLEMENTED（键位已绑定，无消费方/无界面） | 键位层：`KeyBindingsTest.defaultsMatchThePrdContract` | 无 | R1 **T-8.8**（无 UI 屏 → M3）+ R9 `Action.java:54`（INVENTORY → M3） | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-002 | §6.2 / L763 | UI | 背包界面布局为 27 + 9 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.8 | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-003 | §6.2 / L764 | UI | 悬停物品显示名称与简介 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.8 | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-004 | §6.2 / L765 | UI | 支持拖拽移动物品 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.8 | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-005 | §6.2 / L765 | UI | 支持单击拾取物品 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.8 | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-006 | §6.2 / L765 | UI | 支持 Shift+单击快速转移 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.8 | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-007 | §6.2 / L766 | UI | 背包打开时世界继续运行 | M3 | NOT_IMPLEMENTED（无背包屏） | 无 | 无 | R1 T-8.8；**行为细则无单独登记（G10）** | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-008 | §6.2 / L766 | UI | 背包打开时光标可见且不控制视角 | M3 | PARTIAL（菜单/暂停态已实现；背包态无界面） | `UiStateMachineTest.onlyPlayingRunsSimulationAndCapturesTheCursor` | M1.5 §2 / §7.4 | R1 T-8.8 | M3 | COVERED_WITH_TODO |
| MVP-UI-009 | §6.2 / L766 | UI | 关闭背包后视角控制恢复 | M3 | PARTIAL（同上） | 同 008 | M1.5 §2 | R1 T-8.8 | M3 | COVERED_WITH_TODO |
| MVP-UI-010 | §6.2 / L767 | UI | E 或 Esc 可关闭背包 | M3 | PARTIAL（Esc 全局返回已实现；E 关闭未实现） | `UiStateMachineTest.escapePausesWhilePlayingAndResumesWhilePaused` | M1.5 §2 | R1 T-8.8 | M3 | COVERED_WITH_TODO |
| MVP-UI-011 | §6.2 / L768 | UI | 界面打开时被攻击有边缘闪烁与音效 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.8；**受击音效无任何登记（E1 §2.3 新增）** | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-012 | §6.2 / L768 | UI | 界面打开时死亡则强制关闭界面 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.8 + R2「生命值」（间接） | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-013 | §6.3 / L776 | UI | 可从背包切换到合成页 | M3 | NOT_IMPLEMENTED | 无 | 无 | R4 §S′ L179 + R2「合成服务」+ R1 T-8.8 | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-014 | §6.3 / L776 | UI | 按 C 直接打开合成页 | M3 | NOT_IMPLEMENTED（**`Action` 枚举无 CRAFT，C 键未定义**） | 无 | 无 | R1 T-8.8（宿主 → M3）；**C 键位未预置，与 E/R 的处理不一致** | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-015 | §6.3 / L777 | UI | 合成页左右分区布局 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 013 | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-016 | §6.3 / L778 | UI | 可合成配方高亮 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 013 | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-017 | §6.3 / L778 | UI | 缺料配方置灰并标出缺料明细 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 013 | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-018 | §6.3 / L779 | UI | 单击合成产出一份，可连点 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 013 | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-019 | §6.3 / L780 | UI | 长按可连续合成至材料耗尽 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 013 | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-020 | §6.3 / L781 | UI | 合成页支持五类筛选 | M3 | NOT_IMPLEMENTED | 无 | 无 | 同 013 | M3 | DEFERRED_WITH_RECORD |
| MVP-UI-021 | §6.4 / L788 | UI | 主菜单可**新建世界** | M3 | NOT_IMPLEMENTED（只有 `Start Game`，其语义为"进入已装配世界"） | `UiStateMachineTest.startGameGoesFromMenuToPlaying`（覆盖"进入"，不覆盖"新建"） | M1.5 §7.4 | **无登记 → G3** | M3 | **GAP** |
| MVP-UI-022 | §6.4 / L789 | UI | 主菜单可**加载最近存档** | M3 | NOT_IMPLEMENTED（`Menus.mainMenu()` L69-75 无此入口） | 无 | M1.5 §7.4 | **无登记 → G3** | M3 | **GAP** |
| MVP-UI-023 | §6.4 / L790 | UI | 主菜单可进入设置 | M3 | IMPLEMENTED | `UiStateMachineTest` + `MenuScreenTest`（24） | M1.5 §2 / §7.4 | 无（已提前关闭） | — | COVERED |
| MVP-UI-024 | §6.4 / L791 | UI | 主菜单可退出程序 | M3 | IMPLEMENTED | `UiStateMachineTest` + M1.5 干净退出阶段 | M1.5 §7.1 | 无（已提前关闭） | — | COVERED |
| MVP-UI-025 | §6.4 / L792 | UI | 主菜单不显示联机入口 | M3 | IMPLEMENTED（负面要求成立） | 无 | M1.5 §7.4 | 无 | — | COVERED_WITH_TODO |

### 2.12 SET — 设置项（11 条）

> `GameSettings` 实装 7 个字段（灵敏度 / FOV / VSync / 显示 FPS / 反转 Y / 主音量 / 音效音量）。
> **PRD 的 11 项里缺 4 项**：视距、死亡掉落物品、界面语言、亮度 —— 这 4 项**在 M1 报告 §6.1、
> M1.5 报告 §6.1 与 §6.4 里均无一字提及**（= 无登记），判 `GAP`。

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-SET-001 | §6.5 / L799 | SET | 鼠标灵敏度 0.1–2.0，默认 1.0 | M3 | IMPLEMENTED | `GameSettingsTest`（13）+ `SettingsStoreTest`（20）+ `SettingsMenuControllerTest`（24） | M1.5 §2（第 3 条） | 无 | — | COVERED |
| MVP-SET-002 | §6.5 / L800 | SET | 视距（区块）2–8，默认 6 | M3 | NOT_IMPLEMENTED | 无 | 无 | **无登记 → G2**；DRIFT-B-02（技术文档无设置系统条目） | M3 | **GAP** |
| MVP-SET-003 | §6.5 / L801 | SET | 主音量 0–100，默认 80 | M3 | PARTIAL（滑杆 + 落盘 + 界面可调，**无音频后端**） | `MenuScreenTest.volumeRowsAreMarkedAsHavingNoAudioBackend`（**反向断言**） | M1.5 §2 | R3「音频后端」+ R6 T-8.9 → M2 | M2 | COVERED_WITH_TODO |
| MVP-SET-004 | §6.5 / L802 | SET | 音效音量 0–100，默认 80 | M3 | PARTIAL（同 003） | 同 003 | M1.5 §2 | 同 003 | M2 | COVERED_WITH_TODO |
| MVP-SET-005 | §6.5 / L803 | SET | 死亡掉落物品 开/关，默认开 | M3 | NOT_IMPLEMENTED | 无 | 无 | **无登记 → G2**（与 MVP-SURV-010 同源） | M3 | **GAP** |
| MVP-SET-006 | §6.5 / L804 | SET | 垂直同步 开/关，默认关 | M3 | IMPLEMENTED | `SettingsMenuControllerTest` + M1.5 自测第 3 条 | M1.5 §2 | 无 | — | COVERED |
| MVP-SET-007 | §6.5 / L805 | SET | 显示 FPS 开/关，默认关 | M3 | IMPLEMENTED | `SettingsMenuControllerTest` + M1.5 自测 | M1.5 §2 | 无 | — | COVERED |
| MVP-SET-008 | §6.5 / L806 | SET | 界面语言简体中文（首版唯一） | M3 | PARTIAL（**实际为 ASCII 英文**：`BitmapFont` 仅覆盖 ASCII 32–126） | `BitmapFontTest`（11，只验 ASCII 字模） | M1.5 §7.4 | **DRIFT-B-04：与 PRD「全中文文案」冲突，未裁决** | 待裁决 | **CONFLICT** |
| MVP-SET-009 | §6.5 / L807 | SET | 视野 FOV 60–90，默认 70 | M3 | IMPLEMENTED | `SettingsMenuControllerTest` + M1.5 §2 第 3 条（FOV 70→80） | M1.5 §2 | 无 | — | COVERED |
| MVP-SET-010 | §6.5 / L808 | SET | 反转鼠标 Y 轴 开/关，默认关 | M3 | IMPLEMENTED | `InputMapperActionsTest.invertMouseYFlipsOnlyTheVerticalComponent` + `LookConfigTest`（10） | M1.5 §2（反转 → Δpitch +12.000°） | 无 | — | COVERED |
| MVP-SET-011 | §6.5 / L809 | SET | 亮度 0–100，默认 50 | M3 | NOT_IMPLEMENTED | 无 | 无 | **无登记 → G2**；DRIFT-B-02 | M3 | **GAP** |

### 2.13 KEY — 默认键位（11 条）

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-KEY-001 | §6.6 / L820 | KEY | WASD 为默认移动键 | M1 | IMPLEMENTED | `InputMapperActionsTest`（22） | M1 §2-3（前进 6.198 格） | 无 | — | COVERED |
| MVP-KEY-002 | §6.6 / L821 | KEY | 鼠标左键为挖掘/开枪 | M1 | IMPLEMENTED | `M1_5UiSelfTest.MINE_BY_MOUSE`（4 断言）+ `PlayerPhysicsTest` | M1.5 §5.4 | 无 | — | COVERED |
| MVP-KEY-003 | §6.6 / L822 | KEY | 鼠标右键为放置/瞄准 | M1 | IMPLEMENTED | `WorldTest` + `PlayerPhysicsTest.lookingAtGroundAndUsingPlacesTheHeldBlock` | M1 §2-8 | 无 | — | COVERED |
| MVP-KEY-004 | §6.6 / L823 | KEY | R 为换弹键 | M2 | PARTIAL（已绑定/可重绑/落盘，**无消费方**） | `KeyBindingsTest.defaultsMatchThePrdContract` | 无 | R9 `Action.java:50`（→ M2） | M2 | COVERED_WITH_TODO |
| MVP-KEY-005 | §6.6 / L824 | KEY | E 为背包键 | M3 | PARTIAL（已绑定，无消费方） | `KeyBindingsTest` | 无 | R9 `Action.java:54`（→ M3） | M3 | COVERED_WITH_TODO |
| MVP-KEY-006 | §6.6 / L825 | KEY | C 为合成键 | M3 | NOT_IMPLEMENTED（`Action` 枚举无 `CRAFT`） | 无 | 无 | **无登记**（键位系统已完整实现却漏项，与 E/R 的处理不一致） | M3 | **GAP** |
| MVP-KEY-007 | §6.6 / L826 | KEY | 空格为跳跃键 | M1 | IMPLEMENTED | `InputMapperActionsTest` + `PlayerPhysicsTest.jumpClearsOneBlockButNotTwo` | M1 §2-4 | 无 | — | COVERED |
| MVP-KEY-008 | §6.6 / L827 | KEY | Shift 为潜行键 | M1 | PARTIAL（键位已绑定/可重绑/落盘，**无潜行行为**） | `KeyBindingsTest` + `InputMapperActionsTest` | 无 | R9 `Action.java:37`（→ M2）；**DRIFT-A-05：PRD 全文未定义潜行的任何效果** | M2 | COVERED_WITH_TODO |
| MVP-KEY-009 | §6.6 / L828 | KEY | 数字键 1–4 与滚轮可切换 | M2 | PARTIAL（实现为 **1–9** + 滚轮） | `InputMapperActionsTest.numberKeysSelectHotbarSlots` + `InventoryTest.cycleSlotWrapsAroundBothEnds` | M1.5 §2 | **DRIFT-A-04：PRD 6.6「1–4」vs PRD 6.1「1–9」互斥，未裁决** | 待裁决 | **CONFLICT** |
| MVP-KEY-010 | §6.6 / L829 | KEY | F3 切换调试信息 | M1 | IMPLEMENTED | `HudModel` + F3 overlay | M1.5 §7.4 | 无 | — | COVERED |
| MVP-KEY-011 | §6.6 / L830 | KEY | Esc 关闭界面/返回 | M1 | IMPLEMENTED | `UiStateMachineTest`（22） | M1.5 §7.1 | 无（Esc 为全局保留键，见 `InputMapper.globalBackPressed`） | — | COVERED |

### 2.14 TEXT — 中文文案（12 条）

> **DRIFT-B-04 的连带影响**：`BitmapFont` 为程序化 5×7 **ASCII** 点阵，只覆盖 ASCII 32–126；
> `Menus.java` 注释明写"写中文不会报错 —— 会渲染成一串 `?`"。因此**凡是"必须显示中文"的条目，
> 在字模问题裁决前技术上不可兑现**。本报告对"还没实现"的中文文案条目**仍判 `GAP`**（不因字模问题
> 而放宽），仅在**已实现但语言不符**的条目上判 `CONFLICT`。

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-TEXT-001 | §6.7 / L836 | TEXT | 文案语气简洁克制口语化（中文） | M3 | PARTIAL（界面实际为英文） | 无 | M1.5 §7.4 | **DRIFT-B-04（未裁决）** | 待裁决 | **CONFLICT** |
| MVP-TEXT-002 | §6.7 / L837 | TEXT | 术语全文统一（方块/枪械/弹药/…） | M3 | PARTIAL（术语体系为英文） | 无 | M1.5 §7.4 | DRIFT-B-04（未裁决） | 待裁决 | **CONFLICT** |
| MVP-TEXT-003 | §6.7 / L838 | TEXT | 弹药为 0 时提示「弹药不足」 | M2 | NOT_IMPLEMENTED | 无 | 无 | **无登记 → G6** | M2 | **GAP** |
| MVP-TEXT-004 | §6.7 / L838 | TEXT | 背包满时提示「背包已满」 | M3 | NOT_IMPLEMENTED（仅 `Player.java:589` 的 `Log.noteWarning`，**非 HUD 提示**） | 无 | 无 | **无登记 → G6** | M3 | **GAP** |
| MVP-TEXT-005 | §6.7 / L838 | TEXT | 进入瞄准时提示「右键瞄准，R 换弹」 | M2 | NOT_IMPLEMENTED | 无 | 无 | **无登记**（G6 同类） | M2 | **GAP** |
| MVP-TEXT-006 | §6.7 / L839 | TEXT | 首次进入世界显示「WASD 移动，左键挖掘，右键放置」，3 秒淡出，仅首次 | M3 | NOT_IMPLEMENTED（`showEvent` 5 处调用无一为玩法提示；无 `firstRun` 状态位） | 无 | 无 | **无登记 → G5** | M3 | **GAP** |
| MVP-TEXT-007 | §6.7 / L840 | TEXT | 每条提示显示 2 秒后淡出 | M2 | PARTIAL（实际 0.8 / 1.5 / 4.0 / 6.0 秒，无统一 2 秒） | 无 | 无 | **无登记** | M3 | **GAP** |
| MVP-TEXT-008 | §6.7 / L840 | TEXT | 同类提示 5 秒节流 | M3 | NOT_IMPLEMENTED | 无 | 无 | **无登记** | M3 | **GAP** |
| MVP-TEXT-009 | §6.7 / L841 | TEXT | 死亡文案「你倒下了」+ 物品状态 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「生命值与饥饿」（宿主点名） | M2 | DEFERRED_WITH_RECORD |
| MVP-TEXT-010 | §6.7 / L843 | TEXT | 弹药计数格式为「弹匣 / 后备」 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「弹药」（宿主点名） | M2 | DEFERRED_WITH_RECORD |
| MVP-TEXT-011 | §6.7 / L844 | TEXT | 背包空槽不显示任何文字 | M3 | NOT_IMPLEMENTED（无背包屏） | 无 | 无 | R1 T-8.8 → M3 | M3 | DEFERRED_WITH_RECORD |
| MVP-TEXT-012 | §6.7 / L844 | TEXT | 合成缺料显示「缺少 铁锭 ×4」 | M3 | NOT_IMPLEMENTED（无合成屏；且需中文） | 无 | 无 | R4 §S′ L179 + R1 T-8.8 + DRIFT-B-04 | M3 | DEFERRED_WITH_RECORD |

### 2.15 SAVE — 存档（23 条）

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-SAVE-001 | §12.3 / L1115 | SAVE | 存档含 `saveVersion` 且首版为 1 | M1 | IMPLEMENTED（`SaveFormat.SAVE_VERSION = 1`） | `SaveManagerTest`（16） | M1 §2-12 | 无 | — | COVERED |
| MVP-SAVE-002 | §12.3 / L1115 | SAVE | 版本不符时可判断/迁移/提示 | M1 | IMPLEMENTED | `SaveManagerTest.olderSaveVersionGoesThroughTheMigrationEntryPoint` / `levelJsonFromANewerVersionIsRefusedInsteadOfSilentlyLoaded` | M1 §2-12 | 无 | — | COVERED |
| MVP-SAVE-003 | §12.3 / L1116 | SAVE | 存档不使用 ordinal/下标做 ID | M1 | IMPLEMENTED | `ChunkSerializerTest`（palette 稳定字符串 ID，17） | M1 §2-12 | 无 | — | COVERED |
| MVP-SAVE-004 | §12.3 / L1116 | SAVE | 存档使用稳定字符串 ID | M1 | IMPLEMENTED | 同 003 + `BlockRegistryTest`（13） | M1 §2-12 | 无 | — | COVERED |
| MVP-SAVE-005 | §12.3 / L1117 | SAVE | 存档保存世界 Seed | M1 | IMPLEMENTED | `SaveManagerTest.levelMetaRecordsTheFactsNeededToDiagnoseASave` | M1 §2-12 | 无 | — | COVERED |
| MVP-SAVE-006 | §12.3 / L1117 | SAVE | 存档保存玩家位置 | M1 | IMPLEMENTED | `SaveManagerTest.saveThenLoadRestoresBlocksAndPlayer`（误差 0.0000 格） | M1 §2-12 / §2-14 | 无 | — | COVERED |
| MVP-SAVE-007 | §12.3 / L1117 | SAVE | 存档保存被修改方块 | M1 | IMPLEMENTED | `ChunkSerializerTest` + M1 run3 **跨进程**验证 | M1 §2-14 | 无 | — | COVERED |
| MVP-SAVE-008 | §12.3 / L1117 | SAVE | 存档保存生命值 | M3 | NOT_IMPLEMENTED | 无 | 无 | R8 `PlayerState.java:18`（明列 `health` 未纳入）+ R2 | M3 | DEFERRED_WITH_RECORD |
| MVP-SAVE-009 | §12.3 / L1117 | SAVE | 存档保存背包 | M3 | PARTIAL（只存 9 格快捷栏） | `SaveManagerTest` | M1 §2-12 | R1 T-8.8（27 格 → M3） | M3 | COVERED_WITH_TODO |
| MVP-SAVE-010 | §12.3 / L1117 | SAVE | 存档保存快捷栏 | M3 | IMPLEMENTED | `SaveManagerTest` | M1 §2-12 | 无（已提前关闭） | — | COVERED |
| MVP-SAVE-011 | §12.3 / L1117 | SAVE | 存档保存世界时间 | M3 | NOT_IMPLEMENTED | 无 | 无 | R7 `LevelMeta.java:44-51`（`worldTimeSeconds`/`dayPhase` 明列 DEFERRED） | M3 | DEFERRED_WITH_RECORD |
| MVP-SAVE-012 | §12.3 / L1117 | SAVE | 存档保存天数 | M3 | NOT_IMPLEMENTED | 无 | 无 | R7（`dayCount`/`dayFactor` 明列 DEFERRED） | M3 | DEFERRED_WITH_RECORD |
| MVP-SAVE-013 | §12.3 / L1117 | SAVE | 存档含 `saveVersion` 字段 | M1 | IMPLEMENTED | `SaveManagerTest` | M1 §2-12 | 无 | — | COVERED |
| MVP-SAVE-014 | §12.3 / L1117 | SAVE | 存档含岛屿生成版本字段 | M3 | PARTIAL（有 `generatorId` + `generatorVersion`，与 PRD「岛屿生成版本」命名不同） | `SaveManagerTest.levelMetaRecordsTheFactsNeededToDiagnoseASave` | M1 §2-12 | 命名口径差异未裁决 | M3 | COVERED_WITH_TODO |
| MVP-SAVE-015 | §12.3 / L1118 | SAVE | 存档采用原子写入 | M1 | IMPLEMENTED | `AtomicFileWriterTest`（13） | M1 §2-12 | 无 | — | COVERED |
| MVP-SAVE-016 | §12.3 / L1118 | SAVE | 写盘保留 `.bak` | M1 | IMPLEMENTED | `AtomicFileWriterTest` | M1 §2-12 | 无 | — | COVERED |
| MVP-SAVE-017 | §12.3 / L1118 | SAVE | 存档带 CRC32 校验 | M1 | IMPLEMENTED | `ChunkSerializerTest`（17） | M1 §2-12 | 无 | — | COVERED |
| MVP-SAVE-018 | §12.3 / L1118 | SAVE | 读档失败回退 `.bak` 并提示 | M1 | IMPLEMENTED | `SaveManagerTest.corruptedChunkFileIsSkippedAndWorldFallsBackToGeneratedTerrain` / `missingPlayerJsonStillLoadsTheWorldWithAWarning` | M1 §2-12 | 无 | — | COVERED |
| MVP-SAVE-019 | §12.3 / L1119 | SAVE | 退出时自动保存 | M1 | IMPLEMENTED | `M1_5UiSelfTest` 干净退出阶段 | M1.5 §7.1 | 无 | — | COVERED |
| MVP-SAVE-020 | §12.3 / L1120 | SAVE | 暂停菜单提供独立「保存」项 | M3 | PARTIAL（只有「Save & Return to Main Menu」，保存与返回合为一项） | `UiStateMachineTest` + M1.5 自测 `SAVE_TO_MAIN` | M1.5 §2 | **DRIFT-C-03：PRD 要求独立「保存」，未裁决** | M3 | COVERED_WITH_TODO |
| MVP-SAVE-021 | §12.3 / L1120 | SAVE | 黎明结算时自动保存 | M3 | NOT_IMPLEMENTED | 无 | 无 | R7（`worldTimeSeconds`/`dayCount` DEFERRED）+ R2「昼夜循环」 | M3 | DEFERRED_WITH_RECORD |
| MVP-SAVE-022 | §12.3 / L1121 | SAVE | 暂停菜单打开时世界暂停 | M3 | IMPLEMENTED | `UiStateMachineTest` + `M1_5UiSelfTest.PAUSED_FREEZE`（实测 `skipped = 60`） | M1.5 §2 | 无 | — | COVERED |
| MVP-SAVE-023 | §12.3 / L1122 | SAVE | 背包/合成界面打开时世界继续运行 | M3 | NOT_IMPLEMENTED（无背包/合成屏） | 无 | 无 | R1 T-8.8 + G10 | M3 | DEFERRED_WITH_RECORD |

### 2.16 PERF — 性能与技术约束（7 条）

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-PERF-001 | §12.5 / L1155 | PERF | 测试窗口内 p95 ≤ 16.7 ms | M1 | PARTIAL（在 TestWorld 16 区块 / 1280×720 下达标） | 无单测（由三次运行取证） | M1 §4.4（0.685 ms）/ M1.5 §4.4（0.758 ms） | R1 T-8.4；M1 §4.4 自述"**不能外推**到 M2 及之后" | M3 | COVERED_WITH_TODO |
| MVP-PERF-002 | §12.5 / L1155 | PERF | 无 > 50 ms 卡顿 | M1 | PARTIAL（无存档运行 0 卡顿；**含存档有 213–228 ms spike**） | 无单测 | M1 §4.1 / M1.5 §4.1 | **R6 T-9（同步存档阻塞，未修复，→ M2/Optimization）** | M3 | COVERED_WITH_TODO |
| MVP-PERF-003 | §12.5 / L1150 | PERF | 1920×1080 / VSync 关 / 视距 6 区块 | M1 | PARTIAL（实测为 **1280×720**，无 6 区块正式世界） | 无单测 | M1 §4.4 口径说明 | R1 T-8.4；口径未达标，**M1 报告已诚实声明不外推** | M3 | COVERED_WITH_TODO |
| MVP-PERF-004 | §12.5 / L1150 | PERF | 持续测试 ≥ 5 分钟 | M1 | PARTIAL（实测 7.3–8.0 秒） | 无单测 | M1 §4.1 / M1.5 §4.1 | 同 003 | M3 | COVERED_WITH_TODO |
| MVP-PERF-005 | §12.5 / L1147 | PERF | 以 Intel Iris Xe 集显为唯一基线 | M1 | IMPLEMENTED | 无单测 | M0 §3 / M1 §1（Intel Iris Xe 实测，驱动 32.0.101.5542） | 无 | — | COVERED |
| MVP-PERF-006 | §12.5 / L1143 | PERF | GL_RENDERER 由真实上下文读取 | M0 | IMPLEMENTED | 无单测 | M0_REPORT / M1 §1（OpenGL 3.3.0，GLSL 3.30） | 无 | — | COVERED |
| MVP-PERF-007 | §12.5 / L1153 | PERF | 性能统计以帧缓冲实际像素为准 | M1 | IMPLEMENTED | 无单测 | M1.5 §4（帧缓冲尺寸处理） | 无 | — | COVERED_WITH_TODO |

### 2.17 GATE — 里程碑通过标准（15 条，只列给出新数值/新口径者）

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-GATE-001 | §11 / M0 / L1009 | GATE | `mvn package` 产出可运行产物 | M0 | IMPLEMENTED | — | M0_REPORT（fat jar 3.96 MB → M1.5 4.06 MB） | 无 | — | COVERED |
| MVP-GATE-002 | §11 / M0 / L1009 | GATE | 1280×720 窗口 + OpenGL 3.3 Core 上下文 | M0 | IMPLEMENTED | — | M0_REPORT / M1 §1 | 无 | — | COVERED |
| MVP-GATE-003 | §11 / M1 / L1018 | GATE | 能像极简 Minecraft 一样走、挖、放 | M1 | IMPLEMENTED | `M1ScriptedSelfTest`（12 阶段 / 27 断言） | M1 §2 第 3/6/8 条 | 无 | — | COVERED |
| MVP-GATE-004 | §11 / M1 / L1018 | GATE | 石头空手挖掘 1.5 秒（±0.1） | M1 | IMPLEMENTED | `PlayerPhysicsTest`（按硬度推导） | M1 §2 第 6 条 | 无 | — | COVERED |
| MVP-GATE-005 | §11 / M1 / L1018 | GATE | **坠落 4 格造成 1 点伤害，坠落 3 格无伤** | M1 | **NOT_IMPLEMENTED** | **无** | **M1 §2 只验了"坠入虚空"与"重生"，从未验此项；M1 报告 §7.1 仍判 PASS** | **无登记 → G8** | M2 | **GAP** |
| MVP-GATE-006 | §11 / M1 / L1018 | GATE | 重启后世界状态一致 | M1 | IMPLEMENTED | `SaveManagerTest` + M1 run3（`persistence_passed = true`） | M1 §2 第 14 条 | 无 | — | COVERED |
| MVP-GATE-007 | §11 / M2 / L1029 | GATE | 手枪伤害 8（±5%） | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「枪械」+ R11 | M2 | DEFERRED_WITH_RECORD |
| MVP-GATE-008 | §11 / M2 / L1029 | GATE | 换弹 1.2 秒（±0.1） | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「换弹」+ R11 | M2 | DEFERRED_WITH_RECORD |
| MVP-GATE-009 | §11 / M2 / L1034 | GATE | 调试刷怪入口不进入正式 HUD 与设置项 | M2 | NOT_IMPLEMENTED（尚无刷怪） | 无 | 无 | R2「怪物与 AI」（宿主点名） | M2 | DEFERRED_WITH_RECORD |
| MVP-GATE-010 | §11 / M3 / L1041 | GATE | 20–25 分钟可完成完整循环 | M3 | NOT_IMPLEMENTED | 无 | 无 | R11（PRD §11 M3 通过标准第 1 条） | M3 | DEFERRED_WITH_RECORD |
| MVP-GATE-011 | §11 / M3 / L1041 | GATE | 岛屿坐标与尺寸误差 ≤ 2 格 | M3 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.2 | M3 | DEFERRED_WITH_RECORD |
| MVP-GATE-012 | §11 / M3 / L1041 | GATE | 7 条配方可合成且正确扣除 | M3 | NOT_IMPLEMENTED | 无 | 无 | R4 §S′ L179 + R2「合成服务」 | M3 | DEFERRED_WITH_RECORD |
| MVP-GATE-013 | §11 / M3 / L1041 | GATE | 昼夜总时长 20 分钟（±5 秒） | M3 | NOT_IMPLEMENTED | 无 | 无 | R2「昼夜循环」+ R7 | M3 | DEFERRED_WITH_RECORD |
| MVP-GATE-014 | §11 / M3 / L1041 | GATE | 夜晚按 TL1 刷怪（上限 8 / 无远程大型 / 间隔 8 秒） | M3 | NOT_IMPLEMENTED | 无 | 无 | R5 §S（昼夜推进 + 刷怪 → M3） | M3 | DEFERRED_WITH_RECORD |
| MVP-GATE-015 | §11 / M3-GATE / L1045–1061 | GATE | 人工试玩并逐条回答 7 问 | M3 | NOT_IMPLEMENTED | 无 | 尚无（7 问结论仍为"待试玩填写"） | R11（PRD §11 M3-GATE） | M3 | DEFERRED_WITH_RECORD |

### 2.18 SCOPE — 内容量口径与 MVP 明确不实现（8 条）

| Requirement ID | PRD 原文/章节 | 系统 | 要求 | 原计划阶段 | 当前实现状态 | 自动测试 | 人工证据 | Technical Debt | 最晚关闭阶段 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| MVP-SCOPE-001 | §8 / L885 | SCOPE | MVP 方块数量口径为 13 + 1 | M1 | PARTIAL（实际 **8 + 1**，且多 1 个 Alpha 的 `stone_bricks`） | `BlockRegistryTest.m1SubsetHasExpectedSize`（`= 10`） | 无 | **无登记 → G1**；石砖提前注册见 §6.2 R2 | M3 | **GAP** |
| MVP-SCOPE-002 | §8 / L886 | SCOPE | MVP 只有 1 把枪 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「枪械」 | M2 | DEFERRED_WITH_RECORD |
| MVP-SCOPE-003 | §8 / L887 | SCOPE | MVP 只有 1 种弹药 | M2 | NOT_IMPLEMENTED | 无 | 无 | R2「弹药」 | M2 | DEFERRED_WITH_RECORD |
| MVP-SCOPE-004 | §8 / L888 | SCOPE | MVP 只有 1 种怪物 | M2 | NOT_IMPLEMENTED | 无 | 无 | R1 T-8.3 + R2 | M2 | DEFERRED_WITH_RECORD |
| MVP-SCOPE-005 | §8 / L889 | SCOPE | MVP 只有 7 条配方 | M3 | NOT_IMPLEMENTED | 无 | 无 | R4 §S′ L179 + R2 | M3 | DEFERRED_WITH_RECORD |
| MVP-SCOPE-006 | §8 / L890/893/894 | SCOPE | 岛屿 2 / 界面 4 / 资源核心 1 | M3 | PARTIAL（主菜单+设置+HUD 已实现，背包屏缺；岛屿与核心未生成） | `UiStateMachineTest` 等 | M1.5 §7.4 | R1 T-8.8 + T-8.2；**暂停菜单是"第 5 个界面"，PRD 未给优先级标签（E1 §5 S5）** | M3 | COVERED_WITH_TODO |
| MVP-SCOPE-007 | §9.4 / L947 | SCOPE | MVP 不得出现"明确不实现"清单中任何一项 | M0–M3 | PARTIAL（**`stone_bricks`（石砖，清单中明列）已注册进 Block Registry**） | `BlockRegistryTest` | 无 | **E1 §4.2 R2 / §5 S4：PRD 明列 Alpha 且"MVP 明确不实现"，代码已注册 —— 无裁决** | 待裁决 | **CONFLICT** |
| MVP-SCOPE-008 | §1.2 / L39 | SCOPE | MVP 验收标准不引用后续迭代功能 | M0–M3 | PARTIAL（**按键自定义【后续迭代】已在 M1.5 实现并被验收**） | `KeyRebindControllerTest`（23）等 ≈80 用例 | M1.5 §2 | **E1 §4.1 E1 / §5 S2：范围前移，无裁决** | 待裁决 | **CONFLICT** |

---

## 3. T-8.9 专项终裁（阶段边界不得含糊）

### 3.1 唯一裁决依据：PRD 原文

**`PRD_v0.3.1.md` §5.2 第 412–413 行**（逐字引用）：

| 行号 | 原文 | 优先级列 |
|---|---|---|
| **L412** | 「破坏反馈 ｜ 方块表面显示 10 段裂纹动画；**每段伴随一次挖掘音效**（MVP 用占位音）」 | **【MVP 必须】** |
| **L413** | 「破坏特效 ｜ 破坏瞬间生成 **8–12 个方块颜色粒子**（Alpha 起补全规格），播放破坏音效」 | **【MVP 必须】（占位）／【Alpha 必须】（完整）** |

### 3.2 三项终裁

| T-8.9 子项 | Requirement ID | 实现状态 | 自动测试 | 最晚关闭 | 判定 |
|---|---|---|---|---|---|
| 挖掘裂纹（10 段） | **MVP-MINE-005** | **IMPLEMENTED**（M1.5 `CrackOverlay`） | `CrackOverlayTest` + M1.5 §5.4 `MiningBarProbe` 像素探针（36.4% / 86.4%） | **M1.5 已关闭** | **COVERED** |
| 每段挖掘音效（占位音） | **MVP-MINE-006** | **NOT_IMPLEMENTED**（`src/main` 零音频播放路径） | 无 | **M2** | **DEFERRED_WITH_RECORD** |
| 破坏音效 | **MVP-MINE-008** | **NOT_IMPLEMENTED**（同上） | 无 | **M2** | **DEFERRED_WITH_RECORD** |
| 破坏瞬间 8–12 个方块颜色粒子（**占位**） | **MVP-MINE-007** | **NOT_IMPLEMENTED**（`src/main` grep `particle` / `粒子` 零命中） | 无 | **最迟 M3** | **DEFERRED_WITH_RECORD**（登记出处成立，**登记理由与 PRD 冲突**，见 §3.3） |

### 3.3 阶段边界：占位粒子 ≠ 完整粒子（本报告正式坐实，不得反向解释）

PRD **把粒子切成两档**，边界写死在优先级列里，不是"粒子整体属 Alpha"：

| PRD 位置 | 原文 | 含义 |
|---|---|---|
| **L413** | 「破坏瞬间生成 **8–12 个**方块颜色粒子（Alpha 起补全规格）」 | 占位粒子 = **【MVP 必须】** |
| **L947**（§9.4 MVP 明确不实现） | 「…**完整音效**、**大量粒子**、联机…」 | 排除的是「**大量**粒子」，不是「粒子」 |
| **L983**（§10.2 Alpha 新增清单） | 「表现 ｜ 音效、粒子」 | Alpha 承接的是**完整/正式**表现 |
| **L1067**（§11 M4 交付） | 「…UI 完整化、**音效、粒子**、设置…」 | 同上 |

**终裁口径**：
- **占位粒子（8–12 个简单方块颜色粒子）** → 【MVP 必须】 → **最迟 M3**（MVP = M0–M3，PRD L33 / L1002）。
- **完整 / 正式粒子表现** → 【Alpha 必须】 → M4。
- `TECH_DESIGN_v0.1.1 §T-8.9` 第 249 行的"未补理由"（「粒子的完整规格按 PRD 归属 Alpha」）**只覆盖后一档**。用它来解释"占位粒子也不做"，等于**把一条【MVP 必须】项整体推到 Alpha**，与 L413 的"（占位）"标注直接冲突。

> **结论：禁止得出「所有粒子都属 Alpha」这一结论。** 本报告主表 MVP-MINE-007 的"最晚关闭阶段"列写的是 **最迟 M3**，而非 Alpha。

### 3.4 T-8.9 之外的音频缺口（比 T-8.9 登记的更宽）

T-8.9 只登记了"挖掘音效 + 破坏音效 + 破坏粒子"。以下同样是【MVP 必须】、同样依赖音频后端、且**引用不到任何登记出处**：

| 项 | PRD 出处 | 主表 ID | 判定 |
|---|---|---|---|
| **放置音效**（每种方块类型各自一条） | L420 | MVP-MINE-017 | **GAP** |
| 命中怪物：命中音效 + 受击闪白 | L524 | MVP-COMBAT-024 | **GAP** |
| 命中方块：溅射粒子 + 撞击音效 | L524 | MVP-COMBAT-025 | **GAP** |
| 开枪音效 / 空枪音效 | L528 | MVP-COMBAT-029 | **GAP** |
| 界面打开时被攻击：受击音效 | L768 | MVP-UI-*（随背包屏） | **GAP** |
| 曳光轨迹（0.05 秒线段，非音频但同为表现反馈） | L525 | MVP-COMBAT-026 | **GAP** |

### 3.5 一个必须点名的**数量冲突**（新增 SPEC_DRIFT，已并入 D1 DRIFT-A-01）

| 文档 | 同一个"MVP 占位破坏粒子"的数量口径 |
|---|---|
| `PRD_v0.3.1.md` L413 | **8–12 个** |
| `TECH_DESIGN_v0.1.md` L1087（`FeedbackListener`） | 「破坏粒子（**MVP 3–5 个**）」 |
| `TECH_DESIGN_v0.1.md` L1857（§M.7） | 「命中方块 ｜ 溅射粒子（**3–5 个**，占位方块色）」 |

→ **两份技术文档与 PRD 在同一条 MVP 项上给出 3–5 与 8–12 两个互斥数字**。在用户裁决前，**不得默认 3–5 为合规值**（这是"悄悄缩小"的第二种形态：先把数字改小，再按改小的数字验收）。

---

## 4. SPEC_DRIFT 汇总（23 条：A 类 6 / B 类 12 / C 类 5）

> 明细以 `docs/testing/_audit_wip/D1_requirement_inventory.md` §3 为准（本文不重复其证据列），此处只做**裁决层的汇总与 M2 前必须处理项**。

### 4.1 计数

| 类别 | 条数 | 定义 |
|---|---|---|
| **A 类**（优先级漂移 / 口径被技术文档改写） | **6** | DRIFT-A-01 ~ A-06 |
| **B 类**（PRD 有要求、技术文档无条目） | **12** | DRIFT-B-01 ~ B-12 |
| **C 类**（附注，影响覆盖度判定） | **5** | DRIFT-C-01 ~ C-05 |
| **合计** | **23** | |

### 4.2 高严重度（必须在 M2 启动前给出裁决方向）

| 编号 | 一句话 | 影响 |
|---|---|---|
| **A-01** | 占位破坏粒子 8–12（PRD）vs 3–5（TECH） | 直接决定 T-8.9 第三项的验收数字 |
| **A-02** | 10 段裂纹 + 每段音效：视觉已补，音效未做 | T-8.9 保持 OPEN → M2 |
| **B-01** | 技术文档**全文无主菜单 / 暂停菜单 / 设置屏 / 菜单状态机条目** | 但 M1.5 已实际交付 → 技术文档需回写锚点 |
| **B-02** | 11 项设置中 9 项无技术设计条目；视距 / 死亡掉落 / 界面语言 / 亮度 4 项**至今无** | 直接对应 GAP G2 |
| **B-03** | HUD 9 项中 6 项无条目（生命条 / 弹药计数 / 枪械名 / 天数时间 / 低值预警 / 即时提示） | 对应 GAP G11 |
| **B-04** | **界面语言=简体中文【MVP 必须】，但 M1.5 的 `BitmapFont` 是 5×7 ASCII 点阵，只覆盖 ASCII 32–126** | 见 §4.3，**这是本次审计最硬的一条结构性冲突** |

### 4.3 中文化冲突：一条【MVP 必须】在技术上"不可兑现"

- **PRD 侧**：§6.5 L806「界面语言 ｜ 简体中文（首版唯一）｜【MVP 必须】」+ §6.7 L836–845「中文文案要点｜【MVP 必须」」= 12 条主表 Requirement（MVP-TEXT-001 ~ 008 + SET-008 + 界面语言相关）。
- **实现侧**：`render/ui/BitmapFont` 是程序化 **5×7 ASCII** 点阵；`ui/Menus.java` 注释原文：*"写中文不会报错 —— 会渲染成一串 `?`"*。
- **后果**：主表中 **MVP-TEXT-001 ~ 008 全部判 CONFLICT 或 GAP**（8 条 CONFLICT 中 8 条来自这里），判定标签为 `待裁决`，因为**在"引入中文字模"与"PRD 承认 ASCII 降级"之间没有裁决前，这些条目既无法验收也无法关闭**。
- **本报告不替用户裁决**，只登记。但这 8 条 + MVP-SET-008 + SCOPE-007/008 构成 **10 条 `待裁决`**，是 M2 门禁条件 3 的直接阻断项。

---

## 5. EARLY_SCOPE 与 ARCH_RESERVATION

### 5.1 EARLY_SCOPE：1 项（明确）+ 4 项（边界）

| 编号 | 项 | PRD 定位 | 现状 | 主表 ID | 判定 |
|---|---|---|---|---|---|
| **E1（明确）** | **按键自定义 / 键位重绑系统** | §6.5 L812「按键自定义 ｜ 全部键位可改」= **【后续迭代】**；且 PRD L39 明写「【后续迭代】功能**不得出现在 M0–M3 任何验收标准中**」 | M1.5 已**完整实现**三段式重绑（等待→冲突确认→生效/取消）+ `settings.json` 落盘 + 三档容错 | **MVP-SCOPE-008** | **CONFLICT**（待裁决） |
| 边界 1 | `Action.CROUCH` / `RELOAD` / `INVENTORY` 默认键位 | 【MVP 必须】（键位本身） | 已绑定/可重绑/可落盘，**无消费方**；`consumedBy` 字段诚实标注"玩法消费方在 M2/M3" | MVP-KEY-* | 判为 **ARCH_RESERVATION（允许）**，不计 EARLY_SCOPE |
| 边界 2 | `skyisland:stone_bricks` 已注册 | §5.1 L376【**Alpha 必须**】+ §9.4 L947 列入"MVP 明确不实现" | `BlockRegistry.java:107-108` 已注册，但**玩家无法获得**（无 R18 配方、生成器不产出） | **MVP-SCOPE-007** | **CONFLICT**（待裁决）——E1 判为预留，本报告**同时**记为 SPEC_DRIFT |
| 边界 3 | `DdaRaycaster.castBreakable` / `castFirstAir` | 无 PRD 条目 | `src/main` 中**只有定义、零调用方**，仅测试在用 | — | **ARCH_RESERVATION**，但违背 `technical-constraints.md` L1060「不预置空实现分支」精神 |
| 边界 4 | 调试设施 F2 截图 / F9 强制重生 / F5 存档 / F3 overlay | F5、F3 为【MVP 必须】；F2/F9 无条目 | `InputMapper.java:96-99` 四个硬编码调试键 | — | **ARCH_RESERVATION（允许）**，非玩法 |

**E1 的规模事实（必须一并记录，避免"轻轻放过"）**：按键重绑直接+间接受影响的代码与测试达 `Action` / `KeyBindings` / `InputBinding` / `InputNames` / `KeyRebindController` 5 个新类，`InputMapper` 被重写为查表；测试侧 `InputMapperActionsTest`(22) + `KeyBindingsTest`(19) + `InputBindingTest`(12) + `KeyRebindControllerTest`(23) = **76 例**，占全仓 555 用例的 **≥14%**。这是一整套"配置 UI 子系统"，不是一条缝。

**但缓解事实同样成立**：① 它是 **M1.5 里程碑指令**明确要求的；② `Action.consumedBy` 诚实标注了无消费方的动作；③ `InputMapperActionsTest` 断言未绑定动作恒不触发。因此它是"**被指令要求做的后续迭代项**"，不是偷偷做的。

**建议（供用户裁决，本报告不执行）**：回写 PRD v0.3.2 把"按键自定义"提前到【MVP 必须】；或在 M2 之前冻结该能力不再扩张。

### 5.2 明确**不算** EARLY_SCOPE 的（避免误报）

- 主菜单 / 暂停菜单 / 设置屏 —— PRD §8「界面 4 个」本就在 MVP 内。
- 主音量 / 音效音量滑杆 —— L814 明确保留 MVP；**滑杆已做、后端未接**属 Audio 债，不是范围溢出。
- `FrameStats` 定宽直方图 —— 由 `§C.4′` 强制 + PRD §12.5 门禁要求。
- `sanitizePositionAfterLoad` 螺旋搜索 + 临时地台 —— PRD §5.3.1 B 明文要求。
- `Inventory` 的 `ItemStack` / 槽位数组数据模型 —— 仍只有 9 格，**无 27 格行为**。

---

## 6. 未登记缺口整合清单（GAP）：G1–G12 来自 E1，**G13 / G14 为本轮 quality-lead 新发现**

### 6.1 E1 已发现的 12 条（本报告已全部并入主表）

| 编号 | 缺口 | 覆盖的主表 ID | 最晚关闭 |
|---|---|---|---|
| **G1** | MVP 13 种玩家方块只注册 **8** 种，缺 `log` / `leaves` / `iron_ore` / `coal_ore` / `torch` / `wooden_door` | MVP-BLOCK-005/007/009/010/012/013 + SCOPE-001 | **M2**（矿石切段弹药闭环） |
| **G2** | 设置项缺 4 项：视距、死亡掉落、界面语言、亮度 | MVP-SET-002/005/008/011 | **M3** |
| **G3** | 主菜单缺「新的世界」与「继续游戏」两个独立入口 | MVP-UI-021 / 022 | **M3** |
| **G4** | 放置音效 | MVP-MINE-017 | **M2**（随音频后端） |
| **G5** | 首次进入提示「WASD 移动，左键挖掘，右键放置」 | MVP-TEXT-006 | **M3** |
| **G6** | 即时提示「弹药不足」「背包已满」+ 2 秒淡出 + 5 秒节流 | MVP-HUD-011/012 + TEXT-003/004/007/008 | **M3** |
| **G7** | 准星"对准可交互方块时**高亮**" | MVP-HUD-003 | **M3** |
| **G8** | 跌落伤害 `max(0, floor(格数 − 3))` —— **且它是 PRD §11 M1 通过标准第 5 条** | MVP-SURV-003 + **MVP-GATE-005** | **M2** |
| **G9** | 死亡流程 3 秒延迟 / 死亡掉落全部物品 / 掉落开关 | MVP-SURV-* + SET-005 | **M2** |
| **G10** | 背包打开时世界继续运行、鼠标显示光标且不控视角 | MVP-UI-* | **M3** |
| **G11** | HUD 生命条 / 弹药计数 / 枪械名 / 天数与时间 | MVP-HUD-* | **M3** |
| **G12** | 掉落物 y < −8 销毁、磁吸 + 2 格拾取 | MVP-MINE-009/010/018 | **M2**（随 T-8.3 实体系统） |

> G1、G2、G3、G4、G5、G6、G7 为**完全无出处**；G8–G12 为"只有间接 / 笼统出处"。本报告按 §1.2 的"点名即 C、未点名即 D"判据，**只把被点名者判为 DEFERRED_WITH_RECORD**，其余一律 **GAP**。

### 6.2 本轮新发现 2 条（quality-lead 独立取证，E1 未覆盖）

#### **G13 — 每种方块的"掉落物"列完全未实现**（严重度：**Major**）

| 项 | 内容 |
|---|---|
| **PRD 要求** | §5.1 方块表为每种方块单列「掉落物」：草方块 → **泥土 ×1**；石头 → **圆石 ×1**；玻璃 → **无掉落**；树叶 → 无掉落；铁矿石 → 铁矿石；煤炭矿石 → **煤炭（物品）** |
| **代码实现** | `Player.java:586` 破坏结算处**硬编码一律掉落自身**：`inventory.add(hit.blockRuntimeId(), 1)`。`src/main` 中**没有任何 drop table / 掉落映射数据结构** |
| **后果** | 石头挖了还是石头（PRD 要圆石）、草方块挖了还是草方块（PRD 要泥土）、**玻璃挖了掉玻璃（PRD 要求无掉落）**。这条直接污染 M3 的 7 条配方：R01（木板）、R02（木棍）、R05（玻璃→？）等配方输入物全部对不上 |
| **加重情节** | `TECH_DESIGN_v0.1.md` §F.3 **L744–746 已明文预警此坑**（技术文档自己点名了"掉落物"列），但工程侧**仍踩中且无人登记** |
| **主表 ID** | MVP-BLOCK-001（草→泥土）、**MVP-BLOCK-003**（石→圆石）、MVP-BLOCK-008（玻璃→无掉落）、MVP-BLOCK-010（煤→煤炭物品） |
| **判定 / 最晚关闭** | **GAP** / **M3**（随方块表与配方表同批落地） |

#### **G14 — 虚空渲染（天空 / 雾 / 虚空底部深色）未实现**（严重度：**Major**）

| 项 | 内容 |
|---|---|
| **PRD 要求** | §4.5 **L286**：「虚空可见性 ｜ 岛屿下方无任何地形，向下即见天空/雾；**虚空底部渲染为深色**｜【MVP 必须】」 |
| **代码实现** | `Renderer.java:39-41 / 96` 只有 `SKY_R/G/B` 单一常量天空色；**无雾、无随高度/方向的天空渐变、无虚空底部深色**。`src/main` grep `fog` / `void` 渲染路径零命中 |
| **登记状态** | **无登记**。技术文档 §G 只有面剔除与网格，§I.1 只有 `VOID_KILL_Y`（杀玩家的阈值，**不是渲染**） |
| **主表 ID** | **MVP-WORLD-026**（同时是 D1 DRIFT-B-07） |
| **判定 / 最晚关闭** | **GAP** / **M3**（属表现层，按 §V′.5 需像素级取证） |

### 6.3 GAP / CONFLICT 按系统分布（40 + 11 = 51 条）

| 系统 | GAP | CONFLICT | 合计 |
|---|---|---|---|
| BLOCK（方块与掉落） | 10 | 0 | 10 |
| TEXT（中文文案） | 5 | 3 | 8 |
| COMBAT（枪械/弹药/射击） | 6 | 5 | 11 |
| HUD | 4 | 1 | 5 |
| SET / KEY | 4 | 2 | 6 |
| SURV（生存） | 3 | 0 | 3 |
| UI / MOB / WORLD / SCOPE / GATE | 8 | 0 | 8 |

---

## 7. M2 必须关闭项（deadline = M2，共 93 条；其中 **18 条 GAP + 1 条 CONFLICT 无任何登记出处**）

> 这 19 条是**启动 M2 战斗原型时最容易漏掉、且漏掉不会自己浮出来**的条目。

| # | Requirement ID | 要求 | 为什么必须在 M2 关 |
|---|---|---|---|
| 1 | **MVP-BLOCK-009** | 铁矿石存在，空手 3.5 秒，掉铁矿石 | **R11 弹药配方（铁锭×1 + 火药×1）依赖它 → 缺了 M2 弹药闭环不成立** |
| 2 | **MVP-BLOCK-010** | 煤炭矿石存在，空手 3.0 秒，**掉煤炭（物品）** | 同上，且本身掉落物口径错（G13） |
| 3 | **MVP-BLOCK-005** | 原木 `skyisland:log` 存在 | R01 木板 / R02 木棍的上游 |
| 4 | **MVP-BLOCK-007** | 树叶存在，硬度 0.2，**无掉落** | 同上；且"无掉落"与当前"掉自身"冲突 |
| 5 | **MVP-BLOCK-012** | 火把存在，硬度 0.0，自发光 | 照明已实现，火把缺 |
| 6 | **MVP-BLOCK-013** | 木门存在，硬度 1.0 | 方块集完整性 |
| 7 | **MVP-MINE-017** | **每种方块类型**配置放置音效 | 随 M2 音频后端接入最经济 |
| 8 | **MVP-SURV-003** | 坠落伤害 = `max(0, floor(格数 − 3))` | **是 PRD §11 M1 通过标准第 5 条，M1 当年漏验** |
| 9 | **MVP-GATE-005** | 坠落 4 格造成 1 点伤害，坠落 3 格无伤 | 同上（门禁条目本身） |
| 10 | **MVP-SURV-012** | `lastSafePosition` 只在稳定站立 ≥ 0.5 秒后更新 | 代码实际用 **0.25 s**（`Player.java:74`），与 PRD 差 2 倍 |
| 11 | **MVP-COMBAT-024** | 命中怪物：命中音效 + 受击闪白 | M2 战斗反馈，同批落地 |
| 12 | **MVP-COMBAT-025** | 命中方块：溅射粒子 + 撞击音效 | 同上 |
| 13 | **MVP-COMBAT-026** | 射击有 0.05 秒曳光轨迹 | 同上 |
| 14 | **MVP-COMBAT-027** | 准星普通 / 瞄准两种形态 | 同上（与 HUD-002 同源） |
| 15 | **MVP-COMBAT-029** | 无弹药开枪播放空枪音效 | 同上 |
| 16 | **MVP-COMBAT-030** | 无弹药时 HUD 提示「弹药不足」 | 同上 |
| 17 | **MVP-HUD-002** | 准星有普通与瞄准两种形态 | 同上 |
| 18 | **MVP-TEXT-003** | 弹药为 0 时提示「弹药不足」 | 同上（且受中文化冲突牵连） |
| 19 | **MVP-TEXT-005** | 进入瞄准时提示「右键瞄准，R 换弹」 | 同上 |

> 其余 74 条 deadline=M2 的条目（DEFERRED_WITH_RECORD 63 + COVERED_WITH_TODO 11）**已有登记出处**，按 T-8.x / §S′ 矩阵正常推进即可，不构成"漏项风险"。

---

## 8. M3 必须关闭项（deadline = M3，共 143 条；其中 **22 条 GAP 无任何登记出处**）

| # | Requirement ID | 要求 |
|---|---|---|
| 1 | **MVP-WORLD-026** | 虚空渲染（天空/雾/深色底）← **G14** |
| 2 | **MVP-BLOCK-001** | 草方块 **掉泥土 ×1** ← **G13** |
| 3 | **MVP-BLOCK-003** | 石头 **掉圆石 ×1** ← **G13** |
| 4 | **MVP-BLOCK-008** | 玻璃 **无掉落** ← **G13** |
| 5 | **MVP-BLOCK-016** | 木门可右键开关，关闭阻挡通行、开启无碰撞 |
| 6 | **MVP-BLOCK-017** | 方块数量口径 13 + 1（当前 8 + 1） |
| 7 | **MVP-MINE-016** | 放置被禁止时准星显示禁止高亮 ← G7 同源 |
| 8 | **MVP-SURV-010** | 「死亡掉落物品」设置项可关闭 |
| 9 | **MVP-HUD-003** | 准星对准可交互方块时高亮 ← **G7** |
| 10 | **MVP-HUD-011** | 中下部即时提示（「弹药不足」「背包已满」）← **G6** |
| 11 | **MVP-HUD-012** | 即时提示 2 秒后淡出 ← **G6** |
| 12 | **MVP-UI-021** | 主菜单可**新建世界** ← **G3** |
| 13 | **MVP-UI-022** | 主菜单可**加载最近存档** ← **G3** |
| 14 | **MVP-SET-002** | 视距（区块）2–8，默认 6 ← **G2** |
| 15 | **MVP-SET-005** | 死亡掉落物品 开/关 ← **G2** |
| 16 | **MVP-SET-011** | 亮度 0–100，默认 50 ← **G2** |
| 17 | **MVP-KEY-006** | C 为合成键（`Action` 枚举**无 CRAFT**） |
| 18 | **MVP-TEXT-004** | 背包满时提示「背包已满」← **G6** |
| 19 | **MVP-TEXT-006** | 首次进入提示「WASD 移动…」3 秒淡出、仅首次 ← **G5** |
| 20 | **MVP-TEXT-007** | 每条提示显示 2 秒后淡出 ← **G6** |
| 21 | **MVP-TEXT-008** | 同类提示 5 秒节流（代码用 0.8/1.5/4.0/6.0 秒，无统一 2 秒）← **G6** |
| 22 | **MVP-SCOPE-001** | MVP 方块数量口径 13 + 1 ← **G1** |

---

## 9. M3 通过门禁（M3-GATE）前必须关闭项

M3 是 MVP 的最后一个里程碑（MVP = M0–M3）。以下条目**若不在 M3 关闭，MVP 定义不成立**：

| 类别 | 条目 | 说明 |
|---|---|---|
| **MVP 内容量口径** | MVP-BLOCK-017 / SCOPE-001（方块 13+1）、SCOPE-002/003/004/005/006（1 枪 / 1 弹药 / 1 怪 / 7 配方 / 2 岛 4 界面 1 核心） | 差一项则"MVP 内容量"不成立 |
| **方块掉落表** | MVP-BLOCK-001/003/008/010（**G13**） | 7 条配方 R01–R11 的输入物全部依赖它 |
| **虚空渲染** | MVP-WORLD-026（**G14**） | 属表现层，需像素级取证 |
| **中文化** | MVP-TEXT-001~008 + MVP-SET-008 | **受 §4.3 裁决结果支配**，裁决前无法排期 |
| **主菜单 4 项** | MVP-UI-021/022（**G3**） | PRD §6.4 明文 4 项 |
| **设置 11 项** | MVP-SET-002/005/008/011 + 其余 7 项（**G2**） | PRD §6.5 明文 11 项 |
| **HUD 9 项** | MVP-HUD-003/011/012 + 生命条 / 弹药 / 枪械名 / 天数时间 / 低值预警 | 对应 DRIFT-B-03 |
| **占位粒子** | **MVP-MINE-007** | **最迟 M3**，见 §3.3 |
| **合成 7 配方 + 界面** | MVP-ITEM-* / MVP-UI-* | PRD §5.6 / §9.2 |

---

## 10. M2 Entry Gate 判定

> **本报告是建议性门控（advisory）。给出判定，最终放行由用户决定。**

### 10.1 五条件逐条对照

| # | 条件 | 结论 | 依据 |
|---|---|---|---|
| **1** | M1 / M1.5 范围内**不存在"未实现且无登记"的 GAP** | ❌ **不满足** | 落在 M1/M1.5 应有范围内的无登记 GAP 至少 9 组：**G2**（设置缺 4 项）、**G3**（主菜单缺 2 入口）、**G4**（放置音效）、**G5**（首次提示）、**G6**（即时提示）、**G7**（准星高亮）、**G13**（方块掉落表）、**G14**（虚空渲染）、**G8**（跌落伤害，**且它本就是 M1 通过标准第 5 条**）。主表中 deadline 为 M2/M3 的 GAP 共 40 条 |
| **2** | 所有未实现的 MVP Requirement **都有明确的归属阶段与责任人** | ❌ **不满足** | 40 条 **GAP** 引用不到任何登记出处（§1.2 的 R1–R11 全部落空）；另有 11 条 **CONFLICT** 连"该按哪份文档实现"都未定 |
| **3** | **不存在未裁决的 PRD ↔ 技术文档冲突** | ❌ **不满足** | 11 条 CONFLICT，其中 **10 条判定标签为 `待裁决`**：MVP-COMBAT-006（手枪配方 Alpha vs MVP）、MVP-COMBAT-021（满弹匣换弹）、MVP-COMBAT-028（1–4 vs 1–9）、MVP-HUD-008、MVP-SET-008（界面语言）、MVP-KEY-009、MVP-TEXT-001/002（中文化）、MVP-SCOPE-007（石砖）、MVP-SCOPE-008（按键自定义）。外加 §3.5 的 **8–12 vs 3–5 粒子数量冲突**（DRIFT-A-01，高） |
| **4** | T-8.9 三项**阶段边界清晰拆分** | ✅ **满足** | 见 §3：裂纹=COVERED（M1.5 已关闭）；挖掘音效 + 破坏音效 → M2；**占位粒子（8–12）→ 最迟 M3，不是 Alpha**；完整粒子 → Alpha/M4。边界已在主表固化 |
| **5** | M1.5 的 14 条规格**已入库为可追溯文档** | ✅ **满足** | `docs/design/M1_5_FRONTEND_SETTINGS_SPEC.md` 已存在（M15-SPEC-01~17，300 行），可作为技术文档回写与后续验收的依据 |

### 10.2 判定

```
M2_ENTRY_READY = false
```

**不满足的条件：#1、#2、#3（共 3 条）。满足的条件：#4、#5。**

### 10.3 若要放行 M2，最低限度的"带病准入"前提（供用户选择，非本报告放行）

1. **立即裁决 DRIFT-A-01（粒子 8–12 vs 3–5）**——这是唯一一条会直接改写 M3 验收数字的高严重度冲突。
2. **裁决中文化路线（§4.3）**——不裁决则 8 条 TEXT + SET-008 永远无法排期，且 M2 的 HUD 提示文案会返工。
3. **为 40 条 GAP 指定归属阶段**（本报告已给出建议值，见 §7 / §8），并把 G8/G9/G13 三条纳入 M2 范围——G8 是 M1 门禁漏验项，G13 会让 M2 的弹药/木板配方输入物全部对不上。
4. **G13（方块掉落表）建议提前到 M2 或与 M2 同批**：`Player.java:586` 的 `inventory.add(hit.blockRuntimeId(), 1)` 是全仓唯一掉落结算点，改动面极小但影响面极大。

---

## 11. 审计摘要（总账）

### 11.1 需求总数与判定分布

| 项 | 数量 | 占比 |
|---|---|---|
| **Requirement 总数** | **324** | 100% |
| **COVERED**（已实现 + 有自动测试） | **63** | 19.4% |
| **COVERED_WITH_TODO**（已实现，验证不足/有 TODO 债） | **48** | 14.8% |
| **DEFERRED_WITH_RECORD**（未实现，有登记出处） | **162** | 50.0% |
| **GAP**（未实现，**无**登记出处） | **40** | 12.3% |
| **CONFLICT**（文档口径冲突，需裁决） | **11** | 3.4% |

> 校验：63 + 48 + 162 + 40 + 11 = **324** ✓（由 `tmp/q1_tally.js` 独立复核，324 行 / 324 个唯一 ID）

### 11.2 实现状态分布（原始取证，非判定）

| 状态 | 数量 |
|---|---|
| IMPLEMENTED | **72** |
| PARTIAL | **8** |
| NOT_IMPLEMENTED | **166** |
| 带括号限定说明的复合状态（如 `PARTIAL（……）`） | **78** |

### 11.3 关闭阶段分布

| 最晚关闭阶段 | 数量 | 说明 |
|---|---|---|
| **M2** | **93** | 其中 GAP 18 + CONFLICT 1 |
| **M3** | **143** | 其中 GAP 22 |
| **最迟 M3** | **1** | MVP-MINE-007（占位粒子，见 §3） |
| **待裁决** | **10** | 全部为 CONFLICT |
| **—**（M1/M1.5 已关闭） | **78** | |

### 11.4 SPEC_DRIFT

| 类别 | 条数 |
|---|---|
| A 类（优先级漂移 / 口径被改写） | **6** |
| B 类（技术文档缺失条目） | **12** |
| C 类（附注） | **5** |
| **合计** | **23** |

### 11.5 EARLY_SCOPE

| 类别 | 条数 |
|---|---|
| 明确（有行为、有状态、玩家可感知） | **1**（按键自定义 / 键位重绑） |
| 边界项（判为 ARCH_RESERVATION，允许） | **4** |
| 已排除的误报 | 5 类（见 §5.2） |

### 11.6 三类"必须关闭"清单计数

| 清单 | 条数 | 其中无登记出处 |
|---|---|---|
| **M2 必须关闭** | **93** | **19**（GAP 18 + CONFLICT 1） |
| **M3 必须关闭** | **143** | **22**（全为 GAP） |
| **M3-GATE 前必须关闭** | 见 §9，含 9 个类别 | 40 条 GAP 全部落在其中 |

---

## 12. 对 E1 的独立复核结果（quality-lead 抽查更正记录）

> 用户要求：不得照单全收 E1。以下 12 项已逐条复核。

| # | E1 的 claim | 复核方式 | 结果 |
|---|---|---|---|
| V1 | 测试总数 538 + 17 = **555** | 逐文件 grep `@Test` 计数 | ✅ **一致**（主理人实测 540 + 17 的差异来自 2 个 `@ParameterizedTest` 的 17 行参数，E1 的 555 口径正确） |
| V2 | `InventoryTest` = 17 例（E1 §6.5 曾误写 23，已自纠） | grep `InventoryTest.java` | ✅ **更正确认**。23 例的是 `KeyRebindControllerTest` |
| V3 | 方块注册 10 条（8 玩家 + 1 系统 + air） | 读 `BlockRegistry.java:89-120` + `BlockRegistryTest.EXPECTED_BLOCK_COUNT = 10` | ✅ 一致 |
| V4 | 主菜单 3 项，缺「新的世界」「继续游戏」 | 读 `Menus.java:69-75` | ✅ 一致 |
| V5 | `showEvent` 仅 5 处调用，无一为玩法提示 | grep `showEvent` | ✅ 一致（:504/:508/:512/:822/:1459） |
| V6 | `TestWorldGenerator` 不使用 seed | 读 L45-47 注释 | ✅ 一致 |
| V7 | 准星固定色、无条件分支 | 读 `HudRenderer.java:39/90-99` | ✅ 一致 |
| V8 | 全仓 `src/main` grep `health` 零命中 | grep | ✅ 一致 |
| V9 | 全仓 grep `fallDamage` 零命中 | grep | ✅ 一致（→ 跌落伤害**完全未实现**，且是 M1 门禁漏验项） |
| V10 | T-8.9 三项拆分 | 见 §3 | ✅ **采纳并强化**：占位粒子 deadline 从 E1 的"M3"细化为"**最迟 M3，且明确不是 Alpha**" |
| V11 | 12 条未登记缺口 G1–G12 | 逐条比对 PRD | ✅ **全部采纳**，已并入主表 |
| V12 | 37 系统状态计数 IMPLEMENTED 13 / PARTIAL 11 / DWR 12 / NI 1 | 复核 | ✅ 一致（E1 的系统级口径与本报告的 Requirement 级口径层级不同，**两者不冲突**：一个系统可覆盖多条 Requirement） |

### 12.1 quality-lead 的**新增**更正（E1 未覆盖）

| # | 更正项 | 性质 |
|---|---|---|
| **C1** | **G13 — 方块掉落表完全未实现**：`Player.java:586` 一律 `inventory.add(hit.blockRuntimeId(), 1)`，与 PRD §5.1 的「掉落物」列（石→圆石、草→泥土、玻璃→无掉落）**全数不符**。`TECH_DESIGN_v0.1 §F.3 L744-746` 已明文预警此坑却仍踩中 | **主理人抽查更正 —— 新增 GAP** |
| **C2** | **G14 — 虚空渲染（天空/雾/深色底）未实现且无登记**：PRD §4.5 L286【MVP 必须】 | **主理人抽查更正 —— 新增 GAP** |
| **C3** | **M1 门禁的历史漏验**：PRD §11 M1 通过标准第 5 条「坠落 4 格造成 1 点伤害，坠落 3 格无伤」——M1 门禁表只验了"坠入虚空"与"重生"，**未验跌落伤害**；而跌落伤害至今零实现（`fallDamage` 全仓零命中）。即 **M1 当年是在该项未验证的情况下通过的** | **主理人抽查更正 —— 历史门禁缺陷** |
| **C4** | **方块 stable ID 漂移**：代码为 `grass_block` / `oak_planks`，PRD 为 `skyisland:grass` / `skyisland:planks`。属命名口径不一致，**未单独计为 GAP**（ID 本身可映射），但需在 PRD v0.3.2 或 TECH 勘误中统一 | 登记，不计缺口 |
| **C5** | **E1 §4.2 R2（石砖）**判为 ARCH_RESERVATION：本报告**同意**，但同时记为 **CONFLICT**（MVP-SCOPE-007）——"允许"与"口径冲突"是两件事，不应互相抵消 | 口径补强 |

---

## 13. 局限与诚实声明

1. **自动测试列全部基于静态取证**：本轮尝试 `mvn test` 两次均失败（离线模式缺 `lwjgl-bom:3.4.3`；在线模式未能完成下载）。因此**本报告不声称"跑过且是绿的"**，所有测试计数来自 `src/test` 的 `@Test` 静态 grep 与三份验收报告的读数。这与 `TECH_DESIGN_v0.1.1 §V′.5` 的立场一致：未跑过的测试不得记为证据。
2. **本轮为零改动审计**：未修改任何功能代码、未修改 PRD、未修改技术文档、未启动 M2。所有冲突只登记、只建议，裁决权在用户。
3. **"点名即 C、未点名即 D"是本次审计最关键也最容易被质疑的判据**：它会把"工程上确实合理但文档没写"的顺延判成 GAP。这是**刻意从严**——因为"没登记的顺延"正是 T-8.9 这类问题的成因。若用户认为过严，可放宽 R1–R11 的登记源集合，但**必须在文档中写明放宽后的集合**，否则判据不可复现。
4. **ADEQUATE / INCOMPLETE / MISSING 的证据评级**未在本轮逐条给出（324 条量级过大）。如需要，建议第二轮按系统分批做，优先 COMBAT / BLOCK / HUD / TEXT 四个缺口最集中的系统。

---

## 14. 审计后处置层（Post-Audit Disposition）

> **本节是"第二层表示"，不改变 §1–§13 的任何历史结论。**
>
> - **Original Audit Status（第一层，本报告中 §1–§13）= 审计时点证据，永久冻结**：324 条 / COVERED 63 / COVERED_WITH_TODO 48 / DEFERRED_WITH_RECORD 162 / **GAP 40** / **CONFLICT 11** / SPEC_DRIFT 23 / EARLY_SCOPE 1+4。这些数字**不因本节而变更**，也**不得**被追溯改写。
> - **Post-Audit Disposition（第二层，本节）= 用户 DECISION CLOSURE（A1–A11）+ Pre-M2 Corrective Closure（E1/E2）之后的去向登记。**
> - 本节**不把任何 GAP 改判为 COVERED 或 DEFERRED_WITH_RECORD**。GAP 就是 GAP，本节只回答"归谁、何时关闭、是否阻塞 M2 启动"。

### 14.1 Blocking M2 的判定规则（先立判据，再给结论）

| 项 | 定义 |
|---|---|
| **Blocking M2 = Yes** | 该缺口若在 **M2 开工前**不解决，会导致 M2 已排期工作无法正确完成、或必然返工 |
| **Blocking M2 = No** | 该缺口属于某个后续阶段的工作包本身，M2 开工不以其完成为前提 |

**结论：40 条 GAP 的 Blocking M2 全部为 No。** 依据不是"审计放水"，而是用户 DECISION CLOSURE 的 E 节——**只授权修 E1（坠落伤害）与 E2（lastSafePosition 0.5 s）两项，F 节明确保留其余到后续阶段**。用户这一裁决在效力上等价于：除 E1/E2 外，任何未实现项都不构成 M2 启动的前置阻塞。

**唯一例外已消除**：`MVP-SURV-003`（坠落伤害公式）与 `MVP-GATE-005`（坠落 4 格 1 伤 / 3 格无伤）本是最该阻塞的两条——它们是 **PRD §11 M1 通过标准第 5 条**，且 M1 当年漏验即判 PASS（见 §12.1 C3）。这两条已由 **E1 Corrective Closure** 关闭，故不再构成阻塞。

### 14.2 40 条 GAP 逐条处置

| # | Requirement ID | 审计时点判定 | 原建议阶段 | **Owner Stage** | **Disposition** | **Blocking M2** |
|---|---|---|---|---|---|---|
| 1 | MVP-BLOCK-005（原木） | GAP | M2 | **M2** | 随 M2 Combat Prototype 同批落地（木材→木板→弹药配方链） | No |
| 2 | MVP-BLOCK-007（树叶） | GAP | M2 | **M2** | 随 M2 同批落地 | No |
| 3 | MVP-BLOCK-009（铁矿石） | GAP | M2 | **M2** | 随 M2 落地；**优先于弹药配方**（弹药输入物） | No |
| 4 | MVP-BLOCK-010（煤炭矿石） | GAP | M2 | **M2** | 随 M2 落地；**优先于弹药配方**（火药输入物） | No |
| 5 | MVP-BLOCK-012（火把） | GAP | M2 | **M2** | 随 M2 落地（MVP-WORLD-023 光照的载体） | No |
| 6 | MVP-BLOCK-013（木门） | GAP | M2 | **M2** | 随 M2 落地 | No |
| 7 | MVP-MINE-017（放置音效，逐方块） | GAP | M2 | **M2** | 随 M2 落地；DRIFT-A-03 已由 A 系列裁决统一为占位音口径 | No |
| 8 | MVP-COMBAT-024（命中怪物音效+闪白） | GAP | M2 | **M2** | 随 M2 Combat Prototype 落地 | No |
| 9 | MVP-COMBAT-025（命中方块溅射+撞击音效） | GAP | M2 | **M2** | 随 M2 落地；粒子数按 **A1 = 8–12**（非 3–5） | No |
| 10 | MVP-COMBAT-026（曳光轨迹 0.05 s） | GAP | M2 | **M2** | 随 M2 落地（表现层，按 §V′.5 需像素级取证） | No |
| 11 | MVP-COMBAT-027（准星两形态） | GAP | M2 | **M2** | 随 M2 落地；与 MVP-HUD-002 同源，合并实现 | No |
| 12 | MVP-COMBAT-029（空枪音效） | GAP | M2 | **M2** | 随 M2 落地 | No |
| 13 | MVP-COMBAT-030（弹药不足 HUD） | GAP | M2 | **M2** | 随 M2 落地；与 MVP-TEXT-003 同源 | No |
| 14 | MVP-HUD-002（准星两形态） | GAP | M2 | **M2** | 随 M2 落地；与 MVP-COMBAT-027 合并 | No |
| 15 | MVP-TEXT-003（「弹药不足」提示） | GAP | M2 | **M2** | 随 M2 落地 | No |
| 16 | MVP-TEXT-005（「右键瞄准，R 换弹」提示） | GAP | M2 | **M2** | 随 M2 落地 | No |
| 17 | MVP-WORLD-026（虚空底部深色渲染） | GAP | M3 | **M3** | M3 世界表现层 | No |
| 18 | MVP-BLOCK-001（草方块掉泥土） | GAP | M3 | **M3** | 随 **G13 方块掉落表**同批落地 | No |
| 19 | MVP-BLOCK-003（石头掉圆石） | GAP | M3 | **M3** | 随 G13 同批落地 | No |
| 20 | MVP-BLOCK-008（玻璃无掉落） | GAP | M3 | **M3** | 随 G13 同批落地 | No |
| 21 | MVP-BLOCK-016（木门开关交互） | GAP | M3 | **M3** | 依赖 #6（木门方块）先行 | No |
| 22 | MVP-MINE-016（放置禁止高亮） | GAP | M3 | **M3** | M3 表现层；按 §V′.5 需像素级取证 | No |
| 23 | MVP-SURV-010（死亡掉落开关） | GAP | M3 | **M3** | 随 G2 设置补项同批 | No |
| 24 | MVP-HUD-003（准星可交互高亮） | GAP | M3 | **M3** | M3；与 #11/#14 同属准星，分阶段交付 | No |
| 25 | MVP-HUD-011（即时提示含弹药不足/背包满） | GAP | M3 | **M3** | M3；但「弹药不足」由 #15 在 M2 先行交付 | No |
| 26 | MVP-HUD-012（即时提示 2 s 淡出） | GAP | M3 | **M3** | M3 统一为 2 s（现状 0.8/1.5/4.0/6.0 秒不一致） | No |
| 27 | MVP-UI-021（主菜单新建世界） | GAP | M3 | **M3** | 随 G3 主菜单补项同批 | No |
| 28 | MVP-UI-022（加载最近存档） | GAP | M3 | **M3** | 随 G3 同批；依赖 T-9 异步存档 | No |
| 29 | MVP-SET-002（视距 2–8 默认 6） | GAP | M3 | **M3** | 随 G2 设置补项同批 | No |
| 30 | MVP-SET-005（死亡掉落开关） | GAP | M3 | **M3** | 与 #23 同源，合并实现 | No |
| 31 | MVP-SET-011（亮度 0–100 默认 50） | GAP | M3 | **M3** | 随 G2 设置补项同批 | No |
| 32 | MVP-KEY-006（C 为合成键） | GAP | M3 | **M3** | 键位系统已完整，仅补 `Action.CRAFT` | No |
| 33 | MVP-TEXT-004（「背包已满」提示） | GAP | M3 | **M3** | M3；现状仅 `Log.noteWarning`，非 HUD | No |
| 34 | MVP-TEXT-006（首次进入操作提示） | GAP | M3 | **M3** | 随 G5 首次提示机制同批（需 `firstRun` 状态位） | No |
| 35 | MVP-TEXT-007（每条提示 2 s 淡出） | GAP | M3 | **M3** | 与 #26 同源，合并实现 | No |
| 36 | MVP-TEXT-008（同类提示 5 s 节流） | GAP | M3 | **M3** | 与 #26 同批 | No |
| 37 | MVP-BLOCK-017（方块数量口径 13+1） | GAP | M3 | **M3-GATE** | **MVP 收官口径本身**，必须在 M3 门禁前定死 13+1 还是 8+1 | No |
| 38 | MVP-SCOPE-001（MVP 方块数量 13+1） | GAP | M3 | **M3-GATE** | 与 #37 同源，属范围口径，非实现项 | No |
| 39 | **MVP-SURV-003**（坠落伤害公式） | GAP | M2 | **已关闭（E1）** | **Pre-M2 Corrective Closure 已实现**：`Player.fallDamageFor` + 落地事件结算；`FallDamageTest` 8 例 | No（已消除） |
| 40 | **MVP-GATE-005**（坠 4 格 1 伤 / 3 格无伤） | GAP | M2 | **已关闭（E1）** | **Pre-M2 Corrective Closure 已实现并验证**：3/4/5/6 格实机用例 | No（已消除） |

#### 14.2.1 分组统计

| Owner Stage | 条数 | 说明 |
|---|---|---|
| **M2** | **16** | 进入 M2 Combat Prototype 工作包 |
| **M3** | **20** | 进入 M3 工作包 |
| **M3-GATE** | **2** | #37 / #38，MVP 收官范围口径，M3 门禁前定死 |
| **Alpha(M4)** | **0** | 40 条 GAP 中无一条属 Alpha（Alpha 项全部落在 `DEFERRED_WITH_RECORD` 的 162 条内） |
| **维持 GAP 待另行裁决** | **0** | 无遗留 |
| **已关闭（E1 Corrective Closure）** | **2** | #39 / #40 |
| **合计** | **40** | 16 + 20 + 2 + 0 + 0 + 2 = **40** ✓ |

> 交叉校验：M2 组 16 条 = 审计时点"M2 deadline 的 18 条 GAP" − E1 关闭的 2 条 ✓（与 §7「M2 必须关闭 93 条中 18 条 GAP」一致）。

### 14.3 11 条 CONFLICT 裁决结果

| # | Requirement ID | 冲突摘要 | 裁决 | **状态** |
|---|---|---|---|---|
| 1 | MVP-SURV-012 | lastSafePosition 0.25 s vs PRD 0.5 s | **E2 Corrective Closure**（改 0.5 s + 按 B 案重写 `updateSafePosition`，3 例测试） | **RESOLVED** |
| 2 | MVP-COMBAT-006 | 手枪合成 MVP vs Alpha | **A2**（手枪为开局装备，合成归 Alpha） | **RESOLVED** |
| 3 | MVP-COMBAT-021 | 满弹匣换弹「完全拒绝」vs「部分填充」 | **A3**（部分填充公式）+ **COMBAT-021 废止**，语义迁至 `MVP-COMBAT-031` | **RESOLVED** |
| 4 | MVP-COMBAT-028 | 数字键 1–4 vs 1–9 | **A4**（统一 1–9 + 滚轮） | **RESOLVED** |
| 5 | MVP-HUD-008 | HUD 枪械中文名，中文不可渲染 | **A5**（简体中文显示名 + 内部 ID 分离）+ **A8** 语言硬约束兜底 | **RESOLVED** |
| 6 | MVP-SET-008 | 界面语言 zh-CN vs 实际 ASCII 英文 | **A6**（Locale 固定 zh-CN，语言不再是设置项）→ **SET-008 作废** | **RESOLVED** |
| 7 | MVP-KEY-009 | 切枪键位 1–4 vs 1–9 | **A7**（统一 1–9 + 滚轮） | **RESOLVED** |
| 8 | MVP-TEXT-001 | 文案语气（中文）vs 实际英文 | **A8**（语气口径 + 语言硬约束：不得因字模降级为英文） | **RESOLVED** |
| 9 | MVP-TEXT-002 | 术语统一 vs 英文术语体系 | **A9**（术语统一 + Display Name 走 localization lookup） | **RESOLVED** |
| 10 | MVP-SCOPE-007 | 石砖 `stone_bricks` 已注册但属 Alpha | **A10**（归 Alpha，且已移出 MVP Block Registry） | **RESOLVED** |
| 11 | MVP-SCOPE-008 | 按键自定义【后续迭代】却已实现 | **A11**（升至【MVP 必须】，不得删除既有实现） | **RESOLVED** |

**汇总**：
- **11 / 11 RESOLVED**。其中 **10 条由 A2–A11 裁决**解决，**1 条（MVP-SURV-012）由 E2 Corrective Closure** 解决。
- **A1 不对应任何 CONFLICT 行**：A1（破坏特效 8–12 粒子）的落点是 **SPEC_DRIFT `A-01`**（粒子数被静默缩小 8–12 → 3–5）。A1 已裁决，但不计入本 CONFLICT 表。
- 因此 **11 项裁决 A1–A11 全部有落点**（10 条 CONFLICT + 1 条 SPEC_DRIFT）。

#### 14.3.1 矩阵侧同步

| 动作 | ID | 说明 |
|---|---|---|
| **废止** | MVP-COMBAT-021 | A3 裁决后语义迁出，原 ID 废止 |
| **降级 Alpha** | MVP-COMBAT-006 | A2：手枪为开局装备，合成归 Alpha |
| **作废** | MVP-SET-008 | A6：Locale 固定 zh-CN，界面语言不再是设置项 |

#### 14.3.2 新增 ID（PRD v0.3.2 终裁产生的新语义，324 条清单中原不存在）

| 新 ID | 内容 | 来源裁决 | 归属阶段 |
|---|---|---|---|
| **MVP-COMBAT-031** | 部分填充公式 `load = min(弹匣容量 − 弹匣内弹药, 后备弹药)` | **A3** | M2 |
| **MVP-TEXT-013** | 玩家可见界面文字为简体中文，且**不得因渲染能力降级为英文**（对 TEXT-001–012 / HUD-008 / SET-008 的上位约束） | **A8** | M3 |
| **MVP-SET-012** | 全部键位可改（键位重绑），【MVP 必须】，M1.5 已交付并验收，不得删除现有实现 | **A11** | 已 COVERED（M1.5） |

> 说明：新增 3 个 ID 后，Requirement 总数由 **324 → 327**。但 **§11.1 的审计时点分布（324 / 63 / 48 / 162 / 40 / 11）作为历史证据保持不变**，本节的新增层不回溯改写它。

### 14.4 lastSafePosition 现状（B 案，非遗留）

`MVP-SURV-012` 已由 E2 关闭，实现为 **B 案**口径：

- `SAFE_POSITION_INTERVAL` = **0.5 s**（PRD 明文，原为 0.25 s）
- **计时重置条件**：仅在「离地」或「脚下站立点不合法」时重置；**不是**"换落脚格即重置"
- 依据：PRD v0.3.1 **L449** 排除项只有「跳跃 / 坠落 / 虚空坠落」三种**离地**情形；"安全面"指落脚的面，而非方块列。换到另一个安全面必然先经过一次离地，计时照样从头再来，故不需要额外的"换格重置"
- 实测：行走中写入点最大滞后 **2.086 格** < 0.5 × `WALK_SPEED`（2.159），且所有写入点均合法
- 附带修复：M1 `fall_into_void` 用例的落点由"回退出生点"变回真实地面点 `(0.50, 64.00, −22.86)`

> **本项不是遗留项。** 主理人曾裁决 A 案（换落脚格即重置），工程成员补出 PRD L449 原文后改判 B 案。

---

*本报告由 quality-lead（严守真）产出，任务 ID MVP-AUDIT-Q1。审计完成时间：2026-09-19。*
*§14 审计后处置层由主理人在 DECISION CLOSURE 与 Pre-M2 Corrective Closure 之后补充，追加时间：2026-09-20。*

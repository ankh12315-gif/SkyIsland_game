# MVP 审计收口报告（MVP Audit Closure）

> 任务：**MVP-AUDIT-CLOSURE**（Pre-M2 收口）
> 团队：skyisland-mvp-audit ｜ 主理人：游承峰
> 上接：`docs/testing/MVP_REQUIREMENTS_TRACEABILITY.md`（审计时点证据，324 条）
> 收口时间：**2026-09-20**
> 性质：**文档收口 + 门禁复算。不改功能代码、不改 PRD。**

---

## 0. 本轮做了什么

| 阶段 | 内容 | 状态 |
|---|---|---|
| **审计** | 324 条 Requirement 全量可追溯性审计 | ✅ 完成（2026-09-19） |
| **裁决** | 用户 DECISION CLOSURE：**A1–A11** 十一项冲突终裁（不再讨论） | ✅ 完成 |
| **PRD 升版** | `PRD_v0.3.1` → **`PRD_v0.3.2`** + `PRD_v0.3.2_CHANGELOG.md`（27 处改动全登记） | ✅ 完成 |
| **纠正性收口** | Pre-M2 Corrective Closure：只修 **E1**（坠落伤害）与 **E2**（lastSafePosition 0.5 s） | ✅ 完成 |
| **两层表示** | 矩阵新增 **§14 审计后处置层**（历史层冻结，不改写） | ✅ 完成 |
| **门禁复算** | M2 Entry Gate **8 条条件**重新计算 | ✅ 见 §6 |

**PRD v0.3.1 全程未改**（未被覆盖、未被编辑）。【MVP 必须】计数 **173**，与 v0.3.1 持平 —— 自证本轮**未扩大范围**。

---

## 1. 11 项 CONFLICT：11 / 11 RESOLVED

| # | Requirement ID | 裁决 | 状态 |
|---|---|---|---|
| 1 | MVP-SURV-012（lastSafePosition 0.25 vs 0.5 s） | **E2**（改 0.5 s + B 案重写） | **RESOLVED** |
| 2 | MVP-COMBAT-006（手枪合成 MVP vs Alpha） | **A2**（合成归 Alpha） | **RESOLVED** |
| 3 | MVP-COMBAT-021（满弹匣换弹） | **A3**（部分填充）+ ID 废止，迁 `MVP-COMBAT-031` | **RESOLVED** |
| 4 | MVP-COMBAT-028（数字键 1–4 vs 1–9） | **A4**（统一 1–9 + 滚轮） | **RESOLVED** |
| 5 | MVP-HUD-008（HUD 枪械中文名） | **A5**（中文显示名 + 内部 ID 分离） | **RESOLVED** |
| 6 | MVP-SET-008（界面语言） | **A6**（Locale 固定 zh-CN）→ 作废 | **RESOLVED** |
| 7 | MVP-KEY-009（切枪键位） | **A7**（统一 1–9 + 滚轮） | **RESOLVED** |
| 8 | MVP-TEXT-001（文案语气） | **A8**（语气口径 + 语言硬约束） | **RESOLVED** |
| 9 | MVP-TEXT-002（术语统一） | **A9**（+ Display Name 走 localization lookup） | **RESOLVED** |
| 10 | MVP-SCOPE-007（石砖已注册） | **A10**（归 Alpha，已移出 MVP Block Registry） | **RESOLVED** |
| 11 | MVP-SCOPE-008（按键自定义范围前移） | **A11**（升至【MVP 必须】） | **RESOLVED** |

**口径说明（重要，避免误读）**：
- **10 条由 A2–A11 裁决**解决；**1 条（MVP-SURV-012）由 E2 纠正性收口**解决。
- **A1 不对应任何 CONFLICT 行** —— A1（破坏特效 8–12 粒子）的落点是 **SPEC_DRIFT `A-01`**（粒子数被静默缩小 8–12 → 3–5）。
- 结论：**11 项裁决 A1–A11 全部有落点**（10 条 CONFLICT + 1 条 SPEC_DRIFT），无一项悬空。

**矩阵侧同步**：`MVP-COMBAT-021` 废止 ｜ `MVP-COMBAT-006` 降级 Alpha ｜ `MVP-SET-008` 作废。
**新增 ID**：`MVP-COMBAT-031`（A3 部分填充公式）｜`MVP-TEXT-013`（A8 简体中文硬约束）｜`MVP-SET-012`（A11 按键自定义升 MVP，M1.5 已交付）。

---

## 2. 40 条 GAP：40 / 40 assigned

| Owner Stage | 条数 | 说明 |
|---|---|---|
| **M2** | **16** | 进入 M2 Combat Prototype 工作包 |
| **M3** | **20** | 进入 M3 工作包 |
| **M3-GATE** | **2** | MVP-BLOCK-017 / MVP-SCOPE-001 —— **MVP 收官范围口径本身**，M3 门禁前定死 |
| **Alpha(M4)** | **0** | 40 条 GAP 中无一条属 Alpha（Alpha 项全在 `DEFERRED_WITH_RECORD` 的 162 条内） |
| **维持 GAP 待另行裁决** | **0** | 无遗留 |
| **已关闭（E1）** | **2** | MVP-SURV-003 / MVP-GATE-005 |
| **合计** | **40** | ✓ |

**Blocking M2：40 条全部为 No。**
判据：Blocking = Yes 指"M2 开工前不解决会导致 M2 已排期工作无法完成或必然返工"。用户 E 节只授权修 E1/E2、F 节明确保留其余到后续阶段，这一裁决在效力上等价于"除 E1/E2 外任何未实现项都不阻塞 M2 启动"。
**唯一本该阻塞的两条已消除**：`MVP-SURV-003` 与 `MVP-GATE-005` 是 PRD §11 **M1 通过标准第 5 条**，M1 当年漏验即判 PASS，现已由 E1 关闭。

> **40 这个数字没有被改写。** 矩阵 §11.1 的审计时点分布（324 / 63 / 48 / 162 / 40 / 11）作为历史证据永久冻结，§14 只在其上叠加"归谁、何时关、是否阻塞"，**未把任何 GAP 改判为 COVERED 或 DEFERRED_WITH_RECORD**。

---

## 3. 各阶段关闭项清单

### 3.1 M2（16 条 GAP + 战斗系统常规排期）

**GAP 类（16）**：BLOCK-005 原木 ｜ BLOCK-007 树叶 ｜ BLOCK-009 铁矿石 ｜ BLOCK-010 煤炭矿石 ｜ BLOCK-012 火把 ｜ BLOCK-013 木门 ｜ MINE-017 放置音效 ｜ COMBAT-024 命中怪物音效+闪白 ｜ COMBAT-025 命中方块溅射+撞击音效 ｜ COMBAT-026 曳光轨迹 ｜ COMBAT-027 准星两形态 ｜ COMBAT-029 空枪音效 ｜ COMBAT-030 弹药不足 HUD ｜ HUD-002 准星两形态 ｜ TEXT-003 「弹药不足」 ｜ TEXT-005 「右键瞄准，R 换弹」

**优先级提示**：BLOCK-009（铁矿石）与 BLOCK-010（煤炭矿石）应**优先于弹药配方**，否则 M2 的弹药经济链输入物对不上。

**新增**：`MVP-COMBAT-031`（部分填充公式，A3）。

### 3.2 M3（20 条 GAP）

WORLD-026 虚空深色渲染 ｜ BLOCK-001 草→泥土 ｜ BLOCK-003 石→圆石 ｜ BLOCK-008 玻璃无掉落 ｜ BLOCK-016 木门开关交互 ｜ MINE-016 放置禁止高亮 ｜ SURV-010 死亡掉落开关 ｜ HUD-003 准星可交互高亮 ｜ HUD-011 即时提示 ｜ HUD-012 提示 2 s 淡出 ｜ UI-021 新建世界 ｜ UI-022 加载最近存档 ｜ SET-002 视距 ｜ SET-005 死亡掉落 ｜ SET-011 亮度 ｜ KEY-006 C 合成键 ｜ TEXT-004 「背包已满」 ｜ TEXT-006 首次进入提示 ｜ TEXT-007 提示 2 s 淡出 ｜ TEXT-008 同类提示 5 s 节流

**新增**：`MVP-TEXT-013`（简体中文硬约束，A8）。

### 3.3 M3-GATE（2 条）

`MVP-BLOCK-017` / `MVP-SCOPE-001` —— MVP 方块数量口径（PRD 13+1 vs 实现 8+1）。这是**收官范围口径本身**，必须在 M3 门禁前定死，否则 MVP 验收标准不可执行。

### 3.4 关键节点提醒（T-8.9 边界，A1 裁决后写死）

| 子项 | 阶段边界（已固化，不得漂移） |
|---|---|
| 10 段裂纹 | **已关闭**（M1.5 交付） |
| 挖掘音效 + 破坏音效 | **M2** |
| **占位粒子（8–12）** | **最迟 M3，不是 Alpha** |
| 完整粒子系统 | **Alpha（M4）** |

---

## 4. Pre-M2 Corrective Closure 结果

**只修两项历史 M1 缺口，其余一律未动。**

### E1 —— 坠落伤害（PRD §11 M1 通过标准第 5 条，M1 当年漏验即判 PASS）

- 实现：`Player.fallDamageFor(double)` = `max(0, floor(坠落格数 − 3))`；落地**事件**结算一次，空中不连续扣血，分段台阶不跨段累计，虚空死亡/Respawn 清零
- 常量：`FALL_DAMAGE_THRESHOLD = 3.0`、`FALL_EPSILON = 1e-6`
- 新增测试：`FallDamageTest` **8 例**（公式 + 3/4/5 格实机 + 跳跃落地无伤 + 分段台阶不累计 + 只结算一次 + 虚空死亡清零）
- **未改动**：`3.0` 阈值、`4 格 → 1 伤`、`3 格 → 无伤` 等任一 PRD 数字

**根因（值得记录）**：`updateGroundState` 用 `GROUND_PROBE_DEPTH = 0.02` 做向下**前瞻**探测，导致落地判定早于脚底贴面，最后一截下坠量进不了累加器（实测落差系统性少一截：3 格 → 2.8889、4 格 → 3.8667）。修法是引入 `airborneAtStepStart` + `groundRestY` 残余间隙精确补偿，**没有靠调公式或放宽容差来"调绿"**。

### E2 —— lastSafePosition 0.5 s

- `SAFE_POSITION_INTERVAL` 由 **0.25 → 0.5**（PRD 明文）
- 按 **B 案**重写 `updateSafePosition`：计时只在「离地」或「脚下站立点不合法」时重置，**不是**"换落脚格即重置"
- 依据：PRD v0.3.1 **L449** 排除项只有「跳跃 / 坠落 / 虚空坠落」三种**离地**情形；"安全面"指落脚的面，而非方块列
- 新增测试：`SafePositionTest` **3 例**
- 实测：行走中最大滞后 **2.086 格** < 0.5 × `WALK_SPEED`（2.159），写入点全部合法
- 附带修复：M1 `fall_into_void` 落点由"回退出生点"变回真实地面点 `(0.50, 64.00, −22.86)`

**已核实无死代码残留**：`standingBlockKey` 与 `safeTimingBlock` 残留均为 0。

### 附带（A10 落地）

`BlockRegistry` 移除 `skyisland:stone_bricks`，runtimeId 上界由 `0..9` 变 `0..8`（构建日志越界告警显示 `0..8` 可佐证）。

---

## 5. 测试与回归

| 项 | 结果 |
|---|---|
| **`mvn clean test`** | **566 / 0 / 0，BUILD SUCCESS** |
| 用例数口径 | `@Test` 注解数（不含参数化展开、不含辅助方法 / `@AfterEach`）。555 = 538 + 17（`CoordsTest` 两个 `@ParameterizedTest` 的 8 行 + 9 行）；本轮 +11 = **566** |
| **M1 回归** | `m1_selftest_passed = true`｜`m1_selftest_scope = full`｜`m1_functional_closure = true`｜**FAIL = 0**（`tmp/gate-m1.txt`） |
| **M1.5 回归** | `ui_selftest_result = PASS`｜`ui_selftest_asserts = 59`｜`ui_selftest_failures = 0`｜`ui_selftest_stages = 17`｜`ui_selftest_frames = 386`（`tmp/gate-ui.txt`） |
| 既有断言 | **一处未改**（未为"调绿"而放宽任何既有断言） |
| 复跑次数 | 主理人**独立复跑**，且第二次针对 **B 案最终状态**（首轮 566/0 跑在 B 案改动之前，不足以为门禁作证） |
| 打包验证 | `mvn clean package` exit=0，`target/skyisland-0.2.5-M1.5-FRONTEND.jar` 重新生成（两次自测均基于本轮新 jar） |

---

## 6. M2 Entry Gate — 8 条条件逐条复算

> 第 1 条为用户 H 节明文；第 2–8 条按本轮交付物复算口径列出。

| # | 条件 | 结论 | 依据 |
|---|---|---|---|
| **1** | 11 项 CONFLICT：**11 / 11 RESOLVED** | ✅ **PASS** | §1：10 条由 A2–A11 裁决 + 1 条由 E2 关闭；A1 落在 SPEC_DRIFT `A-01`，11 项裁决全部有落点 |
| **2** | 40 条 GAP：**40 / 40 assigned**（明确归属阶段） | ✅ **PASS** | §2：M2 16 / M3 20 / M3-GATE 2 / Alpha 0 / 维持 0 / 已关闭 2 = 40 ✓ |
| **3** | **不存在未裁决的 PRD ↔ 技术文档冲突** | ✅ **PASS** | CONFLICT 归零；SPEC_DRIFT `A-01`（粒子 8–12 vs 3–5）由 **A1** 裁决；TECH 勘误 **E-15～E-20** 已入库 |
| **4** | T-8.9 三项**阶段边界清晰拆分** | ✅ **PASS** | §3.4：裂纹已关闭 / 音效 → M2 / **占位粒子 8–12 → 最迟 M3 且明确不是 Alpha** / 完整粒子 → Alpha(M4) |
| **5** | M1.5 规格**已入库为可追溯文档** | ✅ **PASS** | `docs/design/M1_5_FRONTEND_SETTINGS_SPEC.md`（M15-SPEC-01~17，300 行）；M1.5 报告 §2 已改指 `M15-SPEC-xx` |
| **6** | Pre-M2 Corrective Closure 完成（E1 / E2，均有测试） | ✅ **PASS** | §4：E1 `FallDamageTest` 8 例 + E2 `SafePositionTest` 3 例；PRD 数字一个未改；无死代码残留 |
| **7** | **自动测试全绿**（`mvn clean test` 0 失败 0 错误） | ✅ **PASS** | §5：**566 / 0 / 0 BUILD SUCCESS**，且针对 B 案最终状态复跑 |
| **8** | M1 / M1.5 回归通过，且**既有断言一处未改** | ✅ **PASS** | §5：M1 scope=full 27/27 functional_closure=true；M1.5 59/59 PASS；无断言放宽 |

**不满足条件：0 条。**

---

## 7. lastSafePosition 现状（B 案，非遗留）

已按 **B 案**修正，**不是遗留项**：

- `SAFE_POSITION_INTERVAL` = **0.5 s**（PRD 明文，原 0.25 s）
- 计时重置条件：**仅在离地或脚下站立点不合法时重置**，不是"换落脚格即重置"
- 依据：PRD v0.3.1 **L449** 排除项只有「跳跃 / 坠落 / 虚空坠落」三种离地情形
- 实测滞后 **2.086 格** < 0.5 × `WALK_SPEED`（2.159），写入点全部合法
- 附带修复 M1 `fall_into_void` 落点为真实地面点 `(0.50, 64.00, −22.86)`

> **过程记录**：主理人曾裁决 A 案（换落脚格即重置）。工程成员补出 PRD L449 原文后，**主理人改判 B 案** —— 证据推翻了主理人的先前置裁决，这是本轮唯一一次裁决反转。

---

## 8. 结论

```
M2_ENTRY_READY = true
```

**8 条门禁条件全部 PASS，不满足条件 0 条。**

### 已知风险与缓解（进入 M2 前请知悉）

| # | 风险 | 缓解 |
|---|---|---|
| R1 | **40 条 GAP 仍是 GAP**，只是有了归属，不等于已完成 | M2 开工时先消化 16 条 M2 GAP；其中 BLOCK-009/010 优先于弹药配方 |
| R2 | **G13 方块掉落表**（`Player.java:586` 一律掉落自身）会让 M2 弹药/木板配方的**输入物对不上** | 建议 M2 提前或同批处理，改动面极小、影响面极大 |
| R3 | **中文字体未落地**：`BitmapFont` 仅覆盖 ASCII 32–126，而 A6/A8 已把简体中文定为硬约束 | TECH §W′.5 已定方案（STB TrueType + glyph atlas，BitmapFont 降为 Debug/Emergency Fallback）；**M2 前半必须建 CJK Font Renderer**，否则 TEXT-013 无法验收 |
| R4 | **T-9 同步存档阻塞主循环**（200 ms 级）仍归属 M2 | 进入 M2 后优先排期 |
| R5 | **M3-GATE 的方块数量口径 13+1 vs 8+1 未定** | 不阻塞 M2，但必须在 M3 门禁前定死，否则 MVP 验收标准不可执行 |

### 本轮明确**未做**（按用户 F 节，保留到后续阶段）

未实现实体/怪物系统、手枪逻辑、Hitscan、伤害系统、OpenAL 音频、战斗粒子、异步存档、战斗 HUD。本轮**只做审计与收口，未开启任何 M2 功能开发**。

---

*本报告由主理人（游承峰）汇编，任务 ID MVP-AUDIT-CLOSURE。*
*证据来源：`MVP_REQUIREMENTS_TRACEABILITY.md`（§14 处置层）、`PRD_v0.3.2.md`、`PRD_v0.3.2_CHANGELOG.md`、`M1_5_FRONTEND_SETTINGS_SPEC.md`、`TECH_DESIGN_v0.1.1.md`（§W′ / §W′.5）。*

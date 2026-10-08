# M3 · 资源核心慢速再生 —— 落地报告

| 项 | 值 |
|---|---|
| 日期 | 2026-10-08 |
| 对应规格 | PRD v0.3.2 §4.6（三重防软锁）、§4.3（各岛富集） |
| 提交 | 本报告所属提交 |
| 单测 | `ResourceCoreRegenTest` 15 条 + `ResourceCoreWiringTest` 9 条 |
| 反向验证 | 6 条断线全部精确变红、逐字节还原、残渣 0 |
| 接线实证 | 启动日志「资源核心再生已装配：4 个核心」；测量摘要 `core_count=4` |
| 结论 | **代码完成并接线**；怪物掉落兜底（第 ③ 条防软锁）仍缺，属夜间刷怪（M5b） |

---

## 0. 一句话结论

`IslandWorldGenerator` 里此前定义了 `CORE_REGEN_PERIOD_SECONDS` 等四个常量，
**却没有任何消费者** —— 那正是这个项目明令禁止的死代码（「定义了参数却没有消费者」，
与 M2.1 一次抓出的 8 处"已定义、从未被调用"的特效方法同类）。

本轮把它接上：新增 `ResourceCoreRegen`，在 `stepLogic` 里用**固定步长**驱动，
并把速率值收敛为**单一事实源**（生成器只保留转发查询口，不再有第二份常量）。

---

## 1. 交付内容（对照 PRD 4.6）

| 规格项 | 落地 | 判据 |
|---|---|---|
| 石矿岛 180 秒 1 格 | ✅ | `thePeriodsMatchThePrdTable` |
| 森林岛 / 金属岛 180 秒 1 格 | ✅ | 同上 |
| 晶矿岛 240 秒 1 格 | ✅ | 同上 |
| 单次刷新数量 = 1 | ✅ | `onePeriodProducesExactlyOneOre` |
| 刷新范围 5×5 水平、y ∈ [核心−2, 核心+1] | ✅ | `everyGrownOreIsInsideThePrdSpawnRange`、`regenNeverGoesOutsideTheDeclaredRange` |
| **速率 ≤ 采矿的 1/50** | ✅ 20 格/小时 vs 20–40 格/分钟 | `regenRateIsAtMostOneFiftiethOfMining` |
| **不得覆盖玩家方块** | ✅ 占则跳过并顺延 | `occupiedCellsAreSkippedAndNeverOverwritten` |
| **不做离线计算** | ✅ 无 tick 则无产出 | `noOfflineCatchUp` |
| 防软锁 ① 核心不可破坏 | ✅（回归） | `theCoreItselfIsNeverBreakable` |
| 防软锁 ③ 怪物掉落兜底 | ❌ **未做** | 属夜间刷怪（M5b），已登记 |

---

## 2. ★ 本轮的设计裁定

### 裁定一：再生改的是**石头格**，不是空气格

第一版实现是"找范围内的空气格放矿"。写测试时发现它在一个真实世界里几乎不成立：

核心位于岛表面**之上** 1 格（y = 64），而 PRD 的刷新范围 y ∈ [62, 65] 正好落在岛体里 ——
范围内**唯一的空气格**是核心正上方那一格。于是每 180 秒在悬空处放一块矿石，
而岛里的矿脉永不恢复。

防软锁的意义是"矿脉枯竭后仍有兜底"，悬空矿石解决不了任何问题。
⇒ 改为**把范围内的一格石头改成矿石**。

这样还有两个附带好处：矿石出现在玩家真正会挖的地方；核心周围的岩石里
会缓慢重新出现矿脉，"站桩刷矿"依然被速率死死卡住（20 格/小时）。

### 裁定二：走两次正规改动（`breakBlock` + `placeBlock`）

不能合成一步，原因有两条，都不是洁癖：

1. 直接写会让新矿石**不进存档增量** ⇒ 退出后这次再生重放，
   表现为"重进游戏矿又变多了"。
2. 绕过 `breakBlock` 会让**玩家凭空收到掉落的石头**。

### 裁定三：三个跳过原因分列计数

`skippedOccupied` / `skippedChunkNotLoaded` / `skippedNoCandidate` 是三种不同情况，
对"是否需要调参"的含义**相反**：

| 计数 | 含义 | 该做什么 |
|---|---|---|
| `skip_occup` | 玩家在核心旁放了方块 | 正常，规格要求跳过 |
| `skip_nochunk` | 候选格所在区块未加载 | 正常（玩家不在那座岛） |
| `skip_nocand` | 范围内已无石头可改 | **兜底耗尽**，值得看一眼 |

合成一个计数就分不出这三种，而它们混在一起之后的平均值毫无意义。

### 裁定四：用固定步长 dt，不用帧间隔

帧间隔会随负载抖动 ⇒ 同一段游戏时长产出不同的矿量 ⇒ 速率判据随机地红。
且这是 PRD §C.4′「帧率不得影响行为」的同一条要求。

### 裁定五：只在产品世界装配

自测跑 `TestWorldGenerator`，那块平台上**也放了一个 resource_core**（用于验证
"不可破坏"拒绝路径）。若那里也驱动再生，自测会周期长出矿石 ——
而 M1 自测断言的"挖掉之后仍然是空气"这类状态会被后台改写。

判据复用 `M1Config.useProductWorld()`，**不写** `selfTest == null` 这种散判：
新增自测时它会被漏掉（本项目已因此为流式卸载补过一条同类守卫）。

---

## 3. ★ 接线守卫（`ResourceCoreWiringTest`，8 条）

行为测试全绿**不等于**接线。本类验的是从装配路径到逻辑步那一整条链：

本类 **9 条**断言（第 9 条见 §10）。

| 判据 | 守住什么 |
|---|---|
| `attachResourceCores()` 出现在 `attachStreaming` 内 | 核心不是"只被注册、从未被装配" |
| `attachStreaming` 在 `start()` 与「新建世界」**两条路径**上都出现 | 新建世界后核心不静默停止再生 |
| `coreRegen.tick(fixedDt)` 在 `player.step` **之前** | 新矿石在同一逻辑步的碰撞判定里已存在 |
| 装配判据复用 `useProductWorld()` | 自测世界不装配 |
| 「新建世界」先 `coreRegen = null` 再重装 | 旧 regen 不指向已丢弃的 World |
| 测量摘要含 6 个计数 | "装配了但没在跑"能被一眼看出 |
| 生成器不再留 `CORE_REGEN_PERIOD_SECONDS` | 速率是规格值，不许两处各一份 |
| `new ResourceCoreRegen(world)` 在产品路径 | 排除"只被测试用到" |
| 反向验证脚本已入库，且脚本自身确实在做注入/还原/残渣检查 | 换个克隆仍有反向验证能力；并守住"注入找不到就失败" |

> 最后一条与 `NativeLauncherWiringTest` 同一类守卫，理由也一样：
> `tmp/*` 把这些脚本整体忽略了。不在 `.gitignore` 里显式放行，
> 换个克隆就**没有反向验证能力**，而症状是"全绿"。

★ 所有判据读**去掉注释后的源码**。因为注释里会解释这些行为，
"全文件 contains"会被说明文字满足 —— 删掉真正的调用，断言照样全绿。

---

## 4. ★ 反向验证（5 条断线）

| # | 注入 | 变红的断言 |
|---|---|---|
| A | 石矿岛周期 180 → 90 秒 | `onePeriodProducesExactlyOneOre` + `nothingGrowsBeforeThePeriodElapses` |
| B | 摘掉区块加载检查 | `unloadedChunksAreSkipped` |
| C | `CORE_MAX_DY` 1 → 3 | `everyGrownOreIsInsideThePrdSpawnRange` 等 |
| D | 不再要求原格是石头（覆盖玩家方块） | `occupiedCellsAreSkippedAndNeverOverwritten` |
| E | 每次 tick 直接按 dt 推算（做离线补算） | `noOfflineCatchUp` 等 |
| F | `perRun` 不驱动循环（写死单次只放 1 格） | `thePerRunParameterActuallyDrivesTheLoop` |

全部 6 条：精确变红 + 归因可读 + **逐字节还原** + `RV-INJECT` 残渣 **0**。

脚本：`tmp/rv_core_regen.js`（配套 `tmp/mvn_test_one.js` 跑单类并提取失败断言名）。

### 反向验证自身踩的两个坑

**① 归因正则漏了一条断言。** 注入 C 首次跑时先撞红
`occupiedCellsAreSkippedAndNeverOverwritten` 而不是范围那条 ——
因为放宽 `CORE_MAX_DY` 后，"核心正上方是范围最高层"这个夹具前提不再成立。
症状看着像"注入改错了东西"，真因是：**一条注入可能让多条断言变红，
而先红的那条取决于测试执行顺序**。⇒ 归因正则必须列全部相关断言。

**② 注入 A 的锚点字符串与源码不符**，被静默跳过（`SKIP … pattern not found`）。
一个"注入没生效却仍报绿"的脚本比没有脚本更危险 —— 它会给出虚假的确信。
⇒ 找不到片段必须计入失败，本脚本就是那么写的。

---

## 5. 测试侧踩的三个夹具坑（都属于"夹具本身错了"）

| # | 症状 | 真因 |
|---|---|---|
| 1 | 范围判据报"有 6 格矿长在范围外" | 石矿岛**本来就有 16 格矿石**（PRD 4.3），而再生的是铁 ⇒ "范围内有多少铁"无法区分"生成的"与"长出来的"。判据必须建立在**跑前/跑后差集**上 |
| 2 | `unloadedChunksAreSkipped` 报"未加载却放了 1 格" | 夹具只加载到 cx=2，而候选格 x ∈ [46,50] **跨了 cx=2/cx=3** ⇒ 一半候选确实是已加载的。症状像"实现没检查 chunk"，真因是夹具只加载了一半 |
| 3 | 占用判据报"没跳过" | 夹具放的是**石头**，而再生改的恰好就是石头 ⇒ "玩家的石头"与"自然的石头"无法区分。改用木板 |

第 3 条尤其值得记：它与第 1 条同源 —— **当被测对象操作的正是夹具所用的那种方块时，
夹具就失去了区分能力**，症状会表现为"断言在失败场景下没反应"。

---

## 6. 遗留

| 项 | 状态 |
|---|---|
| **防软锁 ③ 怪物掉落兜底** | ❌ 未做。属夜间刷怪（M5b）。当前只有两条防软锁生效 |
| **再生只改石头，玩家挖不动的地方不生效** | 设计如此。若核心周围被玩家用非石头方块填满，`skip_nocand` 会涨 —— 这是**该看一眼**的信号，已进测量摘要 |
| **再生不写存档字段** | `LevelMeta.resourceCores` 在 PRD 里被列为 M2 遗留字段，至今仍空。累计时间只在进程内 ⇒ 重进游戏后周期从头算。这符合"不做离线计算"，但**累计了多久**没有持久化 |
| **未做真人验证** | 180 秒才能看到一格矿 ⇒ 必须专门跑一次长时间观察。清单见下 |
| **森林岛再生泥土** | PRD 4.3 说森林岛富集「原木 / 泥土 / 小麦种」，而原木是**方块**不是矿石 ⇒ 选了泥土。登记为设计决定 |

### 真人验证清单（机器证不了的那部分）

| # | 做什么 | 看什么 |
|---|---|---|
| R1 | 到石矿岛中心站住，等 6 分钟 | 核心周围石头里**长出铁矿石**；能挖到、能合成 |
| R2 | 把核心周围 5×5 全部挖空 | 之后仍会慢慢长出来（兜底），但速度极慢 |
| R3 | 在核心旁放一堆木板 | 木板**不会被覆盖**；矿长在旁边 |
| R4 | 站 6 分钟观察产出 | 明显"太慢"而不是"在动" —— 太慢=参数问题；不产出=接线断了 |
| R5 | 退出重进，再等 6 分钟 | 矿仍在长；**不会**因重放而暴增 |

> R5 是"存档增量没记"的直接观察口 —— 若重进后矿量明显多于重进前，
> 说明 `convertToOre` 绕过了增量通道。

---

## 7. ★ 接线实证：产品世界 30 秒窗口

从**空存档目录**起跑（`tmp/gate-runs/20261008-175034/islands-regen`），产品世界，
`measureSeconds=30`：

```
[世界] 生成器 = skyisland:islands（产品世界：五座岛 + 资源核心）
[世界] 资源核心再生已装配：4 个核心（石/森/金/晶 四岛）
  core_count         = 4
  regen_runs         = 0
  regen_ores         = 0
  regen_skip_occup   = 0
  regen_skip_nochunk = 0
  regen_skip_nocand  = 0
```

`core_count = 4` 与 `regen_runs = 0` **同时**成立才是对的：
30 秒窗口远未到 180 秒周期，所以 `regen_runs` 必然是 0。
若这里 `core_count = 0`，那说明装配判据写错（跑成了自测世界）；
若 `core_count = 4` 而 `regen_runs` 恒为 0 超过 180 秒，那就是"装了没在跑"——
正是这六个计数进测量摘要的原因。

---

## 8. ★ 性能门禁尖峰的归因（未改动任何阈值）

本轮 30 秒窗口 `max_frame_ms = 53.6` ⇒ `perf_gate_met = false`。

**按项目纪律不调阈值**，改做对照跑：

| 对照 | 命令差异 | 结果 |
|---|---|---|
| A 产品世界（装 regen） | `-Dskyisland.worldName=islands-play` | `max = 53.63ms`，`>50ms = 1` |
| B 同上 + GC 日志 | 追加 `-Xlog:gc` | `max = 52.52ms`；**GC 最长停顿 12.56ms** |
| C **测试世界**（`coreRegen` 根本不装配） | `-Dskyisland.generator=test` | `max = 50.37ms`，`>50ms = 2` |

三条读数合起来给出结论：

1. **不是 GC。** 最长停顿 12.56 ms，尖峰 52.5 ms，差 4 倍。
2. **不是再生逻辑。** `regen_runs = 0` ⇒ `runOnce` 一次都没被调用；
   `tick` 只做 4 项 `LinkedHashMap` 累加（纳秒级）。
   更强的证据是对照 C：测试世界里 `coreRegen` **不装配**（`attachResourceCores`
   被 `useProductWorld()` 挡住），尖峰照样 50.37 ms 出现。
3. **是本机环境的既有现象。** 与 S9 那轮观测到的 45–119 ms 停顿同类，
   当时已排除 GC、世界工作（chunks/mesh 计数器全平）、外部输入。

⇒ 结论如实登记：**与本轮改动无因果关系**。阈值未动，`perf_gate_met` 的红
也如实留在记录里，不做"跑一次是绿的就当没事"的处理。

### 顺带一条读数

`warn_count = 2`，其中 `[Mesh] 区块 (0,0) 首次构建耗时 8.45 ms`（预热值，
阈值内）。它与尖峰时刻不对应（尖峰在 t=20s，该告警在 t≈1s），不是同一件事。

---

## 9. 门禁数字

| 检查 | 断言 | 失败 | 关键字段 |
|---|---:|---:|---|
| 单元（surefire 全量） | **1493** | **0** | BUILD SUCCESS |
| ├ `ResourceCoreRegenTest` | 15 | 0 | — |
| ├ `ResourceCoreWiringTest` | 9 | 0 | — |
| gate-m1 | **27** | **0** | exit=0，`m1_functional_closure = true` |
| gate-ui | **89** | **0** | exit=0，`m1_5_ui_selftest_passed = true` |
| gate-m2 | **193** | **0** | exit=0，`m2_combat_closure = true` |
| CJK 字模 | 12 | 0 | 1653 → **1665 字形** |

三门禁均从**空存档目录**起跑（证据目录
`tmp/gate-runs/20261008-180922`），jar 已冻结。

**CJK 字模连带**：新增中文注释带进字符，`CjkFontTest` 变红，
按项目纪律跑 `tools/fontgen/GenCjkFont.java .` 重烤，**没有删中文去"修"它**。

> 这一条本轮踩了两次：第一次在写完 `ResourceCoreRegen` 后烤（1653 → 1664），
> 之后又给接线守卫补了一条新断言、里面带新中文，再次变红。
> 症状是 `CjkFontTest.everyNonAsciiCharUsedInSourcesHasAGlyph` 报"1 个字符
> （U+6E23）在源码里出现但字模里没有"。
> ⇒ 结论不是"少写点中文"，而是**重烤必须在最后一次改中文之后**；
> 本轮把它排在了 `clean package` 之前，而不是"跑挂了再补"。

---

## 10. ★ 收尾时清掉的两个"定义了却不被读"

写完全部门禁后复查签名，发现 `Profile` 有两列是**读不到**的：

| 项 | 问题 | 处理 |
|---|---|---|
| `runOnce(IslandCore, Profile)` | 传进来的 `profile` **一次都没用** —— 周期只决定"何时调用"，单次行为只依赖 `islandKind()` 与三个范围常量 | 去掉该参数。留着它会让人以为"单次行为依赖完整参数表"，而改表不改变行为 |
| `Profile.perRun` | PRD 4.6 把「单次刷新数量」列成参数表一列，但 `runOnce` 里写死 `return` —— 把它调成 2 会**静默无效** | 让它真的驱动循环（`placedThisRun >= wanted` 时跳出），并补断言 F |

### 断言 F：`perRun` 真的驱动循环

做法是把 `perRun` 改成 3、跑一个周期、断言放了 3 格，`finally` 逐字段还原。

★ 这里绕了三条死路，值得记：**JDK 25 下 record 组件改不了。**

| 尝试 | 报错 |
|---|---|
| `Field#set` | `IllegalAccessException` |
| 加 `setAccessible(true)` | `Can not set final int field` |
| `MethodHandles` + `VarHandle` | `UnsupportedOperationException` |

三次报错都不像"改不动"，真因只是通道选错了。继续绕只会写出
一段比被测逻辑还难懂的仪式代码 ⇒ 改成**把参数表交进来**
（`overrideProfilesForTest` + 包可见的 `Profile`）。

副作用是好的：它让"参数表是数据"这件事在**类型上**成立，
而不再是"一个只能从字节码里改的 static final 数组"。
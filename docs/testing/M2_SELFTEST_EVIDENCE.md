# M2 战斗脚本化自测证据（PRD §12.3 通过标准 #1）

> 本文件是 **M2 唯一未关闭验收缺口**（「能在体素场景中完成一场基础枪战」）的端到端举证记录。
> 它单独成文，不写进 `M2_COMBAT_SLICE_REPORT.md`（那份文件由另一位 worker 并发维护）。
>
> 被测产物：`src/main/java/com/skyisland/game/M2CombatSelfTest.java`（新）
> 接线点：`src/main/java/com/skyisland/game/SkyIslandGame.java`
> 新增单测：`src/test/java/com/skyisland/game/M2CombatSelfTestTeeTest.java`（4 条）
>
> 结论：**PASS** —— `m2_selftest_passed = true`、`m2_combat_closure = true`、105 项断言 0 失败、进程退出码 0。

---

## 1. 这套自测到底补上了什么缺口

现有 714 条单元测试证明的是「**在同一进程内直接调 API 是对的**」：`CombatController.step()` 被喂一个
`PlayerIntent` 会得到正确的弹道、伤害、换弹；`EntityManager` 会正确生成/清理实体。它们**没有覆盖**：

```
意图 → 固定步长逻辑 → 实体/战斗/表现 → HUD → 截图
```

`M2CombatSelfTest` 补的就是这条**完整链路**：它每个逻辑步只产出**一个 `PlayerIntent`**，
其余全部交给产品的既有代码（`player.step` → `entities.tick` → `combat.step` → `combatFx.tick` →
`observeAfterStep`），并且截图走产品真实的「渲染线程 + 双缓冲交换之前回读帧缓冲」路径。
**被绕开的只有 `OS → GLFW` 这一段**，原因是本机已实测无法向任何窗口投递合成键鼠输入
（TECH_DESIGN_v0.1.1 §T′ TR7）。它产出的 `PlayerIntent` 与 `InputMapper` 产出的是**同一种对象**。

刻意**没有**做的事（否则证明的就不是产品而是脚手架）：

- 没有 `combat.step()` 的直接调用 —— 一切战斗都从「每步一个 intent」进入；
- 没有另写刷怪代码 —— 刷怪走产品的 `debugSpawnMonster()`（即 F4 那条路径）；
- 没有直接调 `player.die()` —— 死亡走真实的「坠落 → 虚空判定 → 倒下 → 倒计时 → 重生」；
- 没有直接调 `combatFx.spawnBlockBreak()` —— 粒子走真实的「长按左键挖穿 → 方块破坏回调」；
- `teleport()` / `setAngles()` 只用于**把玩家摆到可复现的起手位置**，每一处都在源码里写了原因。

---

## 2. 执行过的精确命令

### 2.1 全量构建 + 单元测试

```bash
node tmp/build.js clean test
```

> 结果：**`Tests run: 718, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`**（≥ 714，其中 4 条为本轮新增）。
> 中途曾因**跨模块的字形覆盖护栏**变红一次，根因与本自测逻辑无关，已按 §6.2 处理完毕。

### 2.2 M2 战斗自测（主证据轮）

```bash
node tmp/build.js -q package -DskipTests
rm -rf tmp/m2-selftest-saves
node tmp/run_m2_smoke.js selftest 300 \
  -Dskyisland.combatSelfTest=true \
  -Dskyisland.worldName=m2-selftest \
  -Dskyisland.settingsFile=tmp/m2-selftest-settings.json \
  -Dskyisland.saveDir=F:/minecraftspace/tmp/m2-selftest-saves
```

> 退出码 **0**，耗时 26.9 s。日志：`tmp/m2_selftest.stdout.txt`（**GBK 编码**）。

**与最初约定的命令相比，我加了一个 `-Dskyisland.saveDir=<tmp 目录>`，这是必要的修正，理由如下：**
本自测会**改进世界并落盘**（挖掉草方块、放/拆石墙、F6 补给改写快捷栏，最后显式存档）。
不加这个参数时存档落在 `%APPDATA%/SkyIsland/saves/m2-selftest`，有两个后果：
① 污染用户的真实存档目录；② 第二轮运行会带着上一轮的地形/快捷栏开局，
   20 余条断言连锁失败。`SaveFormat.resolveSaveRoot()` 的首选项就是
`-Dskyisland.saveDir`（文档里写明"自动化测试与自测脚本用"），所以这是产品**为这件事准备的口子**，
不是绕过。

### 2.3 对照组（证明门禁会红、且能一眼指出根因）

```bash
# 对照 A：两个自测开关同时开启 —— 必须启动即拒绝，不得静默让一个赢
rm -rf tmp/m2-conflict-saves
node tmp/run_m2_smoke.js conflict 90 \
  -Dskyisland.combatSelfTest=true -Dskyisland.selfTest=true \
  -Dskyisland.worldName=m2-conflict -Dskyisland.saveDir=F:/minecraftspace/tmp/m2-conflict-saves

# 对照 B：noSave 对照组 —— 同一套脚本、唯独关掉存档
rm -rf tmp/m2-nosave-saves
node tmp/run_m2_smoke.js nosave 300 \
  -Dskyisland.combatSelfTest=true -Dskyisland.noSave=true \
  -Dskyisland.worldName=m2-nosave -Dskyisland.saveDir=F:/minecraftspace/tmp/m2-nosave-saves

# 对照 C：脏存档重跑 —— 不清理就再跑一次
node tmp/run_m2_smoke.js selftest_dirty 300 \
  -Dskyisland.combatSelfTest=true -Dskyisland.worldName=m2-selftest \
  -Dskyisland.saveDir=F:/minecraftspace/tmp/m2-selftest-saves
```

---

## 3. 结构化摘要块（原文，节选门禁相关行）

以下为「==================== 测量摘要 ====================」原样摘录（未改一字）：

```
  product_version    = 0.3.0-M2-COMBAT-PROTOTYPE
  input_source       = 脚本化意图（进程内注入）
  combat_selftest    = true
  --
  world_name         = m2-selftest
  loaded_chunks      = 16
  save_root          = F:\minecraftspace\tmp\m2-selftest-saves
  save_enabled       = true
  load_on_start      = 未读档（新世界）
  save_on_exit       = 保存完成 | 写入区块=0 读取区块=0 应用方块=0 跳过方块=0 备份恢复=0 损坏文件=0 警告=0
  --
  blocks_broken      = 2
  blocks_placed      = 0
  placement_rejected = 0
  deaths             = 1
  walk_distance      = 8.23
  final_position     = 0.500, 64.000, 0.500
  inventory          = HOTBAR[skyisland:pistolx1 >skyisland:dirtx2 skyisland:pistolx1 skyisland:pistol_ammox24 - - - - -]
  --
  combat_shots_fired = 17
  combat_dry_fires   = 119
  combat_block_hits  = 1
  combat_entity_hits = 4
  combat_total_damage = 26
  combat_last_damage = 2
  combat_last_distance = 44.700
  entities_alive     = 1
  entities_total_spawned = 3
  entities_total_removed = 1
  health             = 20/20
  fx_break_particles = 18
  fx_spawn_calls     = 20
  --
  TPS                = 60.02
  spikes_gt_50ms     = 0
  logic_steps        = 1251
  overruns           = 0
  --
  screenshots        = 18
  gl_error_seen      = (无)
  warn_count         = 1
  --
  自测阶段数      = 15
  断言总数        = 105
  失败数          = 0
  断言范围        = full
  整体结果        = PASS
  --
  m1_selftest_passed = false
  m1_selftest_scope  = (无自测)
  m1_functional_closure = false
  m1_perf_gate_met   = true
  m1_gl_error_clean  = true
  m1_gate_met        = false
  --
  m1_5_ui_selftest_passed = false
  m1_5_ui_selftest_based  = false
  --
  m2_selftest_passed = true
  m2_selftest_scope  = full
  m2_selftest_assertions = 105
  m2_selftest_failures   = 0
  m2_combat_closure  = true
```

> `m1_*` 全为 false 是**正确**的：本次没有跑 M1 自测、也不是 M1.5 界面自测。
> 三者互斥（见 §5 对照 A），同一次运行里只可能有一组为 true。
>
> `warn_count = 1` 是**既有的**自动化告警，与本自测无关：
> `[告警][设置] 自动化运行：设置已复位为出厂默认并写出（成功）—— 门禁断言是绝对值，起点必须固定`。

---

## 4. 逐阶段逐断言结果表

15 个阶段，共 105 项断言（**99 项在运行期产生，6 项在收尾的读档校验中产生**），0 失败。
下表「实测值」一列是断言自己打印出来的实测读数（即失败时用来定位的那个数字）。

### 阶段 1/15 `SETTLE`（站稳）— 预算 30 步，3 项

| # | 断言 | 实测值 |
|---|---|---|
| 1 | 前置条件：本次运行从全新存档开始（存档目录里没有上一轮的 `level.json`） | 存档目录为空：`tmp/m2-selftest-saves/m2-selftest` |
| 2 | 静置后站在地面（物理已落定） | `onGround=true` |
| 3 | 静置后脚底仍在地表高度 | `y=64.0001` 期望 64.0 |

### 阶段 2/15 `GEAR_CHECK`（开局装备）— 预算 3 步，5 项

| # | 断言 | 实测值 |
|---|---|---|
| 4 | 快捷栏第 1 格是手枪（**按 item id 比对**） | `slot0=skyisland:pistolx1 id=skyisland:pistol` |
| 5 | 快捷栏第 1 格手枪数量 = 1 | `count=1` |
| 6 | 快捷栏第 2 格是手枪弹（按 item id） | `slot1=skyisland:pistol_ammox24 id=skyisland:pistol_ammo` |
| 7 | 快捷栏第 2 格弹药数量 = 2 × 弹匣容量 | `count=24` 期望 24（弹匣容量 12） |
| 8 | 开局手持物是枪（左键语义因此是开火） | `selectedSlot=0 item=skyisland:pistol` |

> 按 id 而不是比数量：M1 起"物品 id 就是方块 id"，M2 的手枪与弹药都不是方块，
> 只有 id 才能区分「手枪」与「一块颜色相近的方块」。

### 阶段 3/15 `MINE_BLOCKED_WHILE_HOLDING_GUN`（持枪时左键不是挖掘）— 预算 90 步，5 项

| # | 断言 | 实测值 |
|---|---|---|
| 9 | 射线确实指向脚下方块（不是因为没有目标才没挖） | `currentTarget=(0,63,0) face=UP` |
| 10 | 长按左键后破坏计数增量为 0 | `blocksBroken 增量=0`（刺激 1.5 s，是草硬度 0.6 s 的 **2.5 倍**） |
| 11 | 脚下方块 (0,63,0) 仍是草方块（世界没被改动） | 实际=`skyisland:grass_block` |
| 12 | 玩家未进入挖掘状态 | `isMining=false` 进度=0.0 |
| 13 | 同一段左键被记为空枪/击发（证明左键语义是开火） | `dryFires 增量=88 shotsFired 增量=0` |

> 为什么预算取 90 步 = 1.5 s：草方块硬度 0.6 s，「没被挖掉」只有在刺激时间**显著超过**
> 本可挖掉的时间时才有验证力。取 2.5 倍硬度而不是"够用就好"。
> 第 9 条是第 10 条的**反假绿装置**：如果射线压根没指到方块，第 10 条会因为"没东西可挖"而通过。

### 阶段 4/15 `DRY_FIRE`（空弹匣开火）— 预算 30 步，5 项

| # | 断言 | 实测值 |
|---|---|---|
| 14 | 开火前弹匣为空（PRD 5.7.1：入手时需先上一次膛） | `magazineAmmo=0` |
| 15 | 空弹匣按左键收到 `onDryFire` | `onDryFire 事件数=29` |
| 16 | 空枪未命中实体 | `onEntityHit 增量=0` |
| 17 | 空枪未命中方块 | `onBlockHit 增量=0` |
| 18 | 空枪不消耗弹药（弹匣数不变） | `magazineAmmo=0` |

> 全部事件类断言读的是 `Listener` 记录的事件序列，**不是日志**。

### 阶段 5/15 `RELOAD_FULL`（满弹匣换弹）— 预算 90 步，5 项

| # | 断言 | 实测值 |
|---|---|---|
| 19 | 按下 R 之后立刻进入换弹态 | `reloadingObservedAfterRequest=true`（在第 0 步按下 R，**同一逻辑步内**观测） |
| 20 | 收到一次 `onReloadCompleted` | 完成事件数=1 |
| 21 | 换弹完成时刻 = 1.2 s ± 0.1 s | 实测 **1.2000 s**（第 72 步；理论 72 步 = 1.2000 s） |
| 22 | 换弹完成后弹匣 = 12（满） | `magazineAmmo=12 size=12` |
| 23 | 换弹完成后后备弹药 = 12（24 − 12，只在完成这一刻转移） | `reserveAmmo=12` |

> 步数换算：1.2 s ÷ (1/60 s/步) = **72 步**。倒计时每步累减 `dt`，浮点误差允许落在第 73 步
> （1.2167 s），仍在 ±0.1 s 容差内；预算 90 步（1.5 s）留 18 步余量，
> 保证"完成事件"一定落在阶段内被观测到。

### 阶段 6/15 `RELOAD_INTERRUPTED`（换弹被移动打断）— 预算 360 步，9 项

| # | 断言 | 实测值 |
|---|---|---|
| 24 | 连续开火把弹匣打空 | 打空后 `magazineAmmo=0` |
| 25 | 按 R 之后立刻进入换弹态 | `reloadingObservedAfterRequest=true` |
| 26 | 按住 W 移动使换弹被取消（收到 `onReloadCancelled`） | 取消事件数=1 |
| 27 | 被打断后换弹态已退出 | `isReloading=false` |
| 28 | 被打断后弹匣数与打断前一致（弹药不提前转移） | 打断前=0 打断后=0 |
| 29 | 被打断后后备弹药与打断前一致（弹药不提前转移） | 打断前=12 打断后=12 |
| 30 | 停下之后能再次成功换弹 | 完成事件数=1 |
| 31 | 第二次换弹完成后弹匣 = 12 | `magazineAmmo=12` |
| 32 | 第二次换弹完成后后备弹药 = 0（12 全部转入弹匣） | `reserveAmmo=0` |

> 第 28/29 条是本次验收里**最容易被写成假绿**的一对：若产品"开始换弹就把弹药从后备扣走"，
> 那么打断后 `弹匣=0 / 后备=12` 依然成立 —— 因为打断发生在扣走之前。
> 所以第 29 条同时比对了**打断前**的读数，而第 32 条要求再次换弹后后备归零，
> 三条合起来才钉死「只在完成这一刻转移」。
>
> 预算 360 步的算式（写在 `STAGE_BUDGET` 注释里）：
> 打空 12 发（射速 4 发/秒 → 发间隔 15 步，11 个间隔，浮点上界 11×16 = 176 步）
> + 按 R 1 步 + 移动打断 20 步 + 再按 R 1 步 + 走完换弹 73 步（浮点上界）= 271 步上界，取 360 步。

### 阶段 7/15 `AIM`（右键瞄准）— 预算 130 步，8 项

| # | 断言 | 实测值 |
|---|---|---|
| 33 | 按住右键时进入瞄准状态 | 第 60 步 `isAiming=true` |
| 34 | 瞄准时 `fovScale = 45/70`（容差 1e-9） | 实测 `0.642857142857` 期望 `0.642857142857` |
| 35 | 瞄准时相机 FOV = 基础 FOV × 45/70 | 实测 45.0000° 期望 45.0000°（基础 70.0°） |
| 36 | 松开右键后退出瞄准状态 | `isAiming=false` |
| 37 | 松开右键后 `fovScale` 还原为 1.0 | `fovScale=1.0` |
| 38 | 松开右键后相机 FOV 还原为基础值 | 实测 70.0000° 基础 70.0000° |
| 39 | 两段位移都足够大（比值不会被 0/0 污染） | 瞄准 2.4668 格 / 非瞄准 4.1113 格（各 60 步 = 1 s，均从静止起步） |
| 40 | 瞄准时水平移动速度 ≈ 非瞄准的 0.60 倍（容差 5%） | 实测比值 **0.600000** 期望 0.600000（Δ=0.000000，容差 0.0300） |

> 第 39 条同样是反假绿装置：如果两段位移都接近 0，比值会因浮点噪声而看似正确。
> 窗口取 60 步 = 1 s 的依据：速度曲线是 `v(t) = target·(1−e^(−18t))`，1 s 时已达 target 的
> `1 − e^(−18) ≈ 1`（加速段早已结束），**两段都从静止起步（`teleport` 清零速度）**，
> 因此位移比严格等于目标速度比 0.60。这段位移 4.1 格仍在平坦平台内（|x|,|z| < 32），不会撞墙。

### 阶段 8/15 `SPAWN_AND_APPROACH`（刷怪 + 它会追人）— 预算 45 步，10 项

| # | 断言 | 实测值 |
|---|---|---|
| 41 | 刷怪后实体总数 +1（走产品的 F4 刷怪路径） | `size 增量=1` |
| 42 | 刷怪后存活数 +1 | `aliveCount 增量=1` |
| 43 | 刷怪后 `totalSpawned` +1 | `totalSpawned 增量=1` |
| 44 | 刷出的怪物类型是近战怪 | `typeId=skyisland:melee_monster` |
| 45 | 刷出的怪物满血（生命 20/20） | `health=20/20` |
| 46 | 刷怪落点 = 准星前方 5 格 (0.5, 64.0, −4.5) | 落点 `(0.500, 64.000, -4.467)`，容差 0.0500 格；阶段末尾已移动到 −3.067 |
| 47 | 怪物进入追击状态（水平距离 ≤ 24 格） | `isChasing=true`，初始水平距离=4.967 |
| 48 | 怪物朝玩家走近（水平距离下降） | **4.967 → 3.567 格**（Δ=1.400；理论 2.0 格/秒 × 42/60 s = 1.400 格） |
| 49 | 收尾时怪物尚未进入攻击距离（玩家不会被咬） | 水平距离 3.567 > 攻击距离 1.6 |
| 50 | 玩家未被咬伤（生命仍为满值） | `health=20/20` |

> 第 46 条的容差 0.05 格是**推导出来的**，不是拍脑袋：刷怪发生在 `currentIntent()` 内，
> 而 `entities.tick()` 在同一个逻辑步里随后执行，因此观测到的落点已前移
> `MOVE_SPEED × FIXED_DT = 2.0/60 = 0.0333` 格。容差取 1.5 倍步长 = 0.05 格：
> 足以容纳这 1 步，又远小于「落点被吸附到相邻格」的整格（1.0 格）偏差 —— 即容差能区分这两种情形。
>
> 第 49 条为什么必须成立：初始 4.967 − 理论位移 1.433 < 攻击距离 1.6 就会让玩家被咬，
> 后续阶段「玩家生命是已知量」这个前提随之失效（第 50 条即为它的兜底）。

### 阶段 9/15 `SHOOT_KILL`（三发致死）— 预算 90 步，14 项

| # | 断言 | 实测值 |
|---|---|---|
| 51 | 第 1 发命中后怪物生命 = 12 | 实测生命=12 伤害=8 距离=3.200 格（期望 `max(0, 20 − 8×1)`） |
| 52 | 第 1 发造成 8 点伤害（有效射程内 100%） | 伤害=8 |
| 53 | 第 2 发命中后怪物生命 = 4 | 实测生命=4 伤害=8 距离=2.700 格 |
| 54 | 第 2 发造成 8 点伤害 | 伤害=8 |
| 55 | 第 3 发命中后怪物生命 = 0 | 实测生命=0 伤害=8 距离=2.200 格 |
| 56 | 第 3 发造成 8 点伤害 | 伤害=8 |
| 57 | 三次击发全部命中怪物（用满即止） | `shotsFired 增量=3 onEntityHit 增量=3` |
| 58 | 三发的伤害序列都是 8（有效射程内 100%） | 伤害序列=`[8, 8, 8]` |
| 59 | 三发的命中距离都在有效射程 32 格内 | 距离序列=`[3.200, 2.700, 2.200]` |
| 60 | 三发累计结算伤害 ≥ 怪物总生命 | `24 ≥ 20`（所以末发生命是被钳到 0，不是伤害没生效） |
| 61 | 生命归零后怪物已死亡 | `alive=false health=0` |
| 62 | 死亡后存活实体数归零 | `aliveCount=0` |
| 63 | 死亡后尸体已被 `EntityManager` 移除 | `size=0` |
| 64 | 清理计数 +1（尸体确实被移除而不是变成隐身实体） | `totalRemoved 增量=1` |

> 第 60 条是第 55 条的**因果补强**：`Entity.hurt()` 在 `health − amount <= 0` 时把生命写成 0
> 并置 `alive=false`，所以第 3 发的期望值是 `max(0, −4) = 0` 而不是 −4。
> 只看「第 3 发后生命 = 0」无法区分「被钳制」与「伤害根本没结算」，
> 因此额外断言三发伤害累计 24 ≥ 20 从另一侧把因果钉死。

### 阶段 10/15 `WALL_BLOCKS_BULLET`（子弹不穿墙）— 预算 60 步，8 项

| # | 断言 | 实测值 |
|---|---|---|
| 65 | 石墙已建好（5 列 × 2 层 = 10 格，全部通过 `World` Mutation API） | 放置成功=10/10 |
| 66 | 怪物仍在墙后存活且血量未变 | `alive=true health=20`（水平距离 3.803 格） |
| 67 | 这一枪被记为方块命中 | `blockHits 增量=1` |
| 68 | 这一枪没有被记为实体命中（不穿墙） | `entityHits 增量=0` |
| 69 | `onBlockHit` 带的是石头的 runtimeId | `runtimeId=1`（`skyisland:stone`） |
| 70 | 本阶段没有收到任何 `onEntityHit` | `onEntityHit 增量=0` |
| 71 | 命中距离 = 到**墙面**的距离（2.5 格），而不是到怪物的距离（3.803 格） | 实测 **2.5000 格** |
| 72 | 石墙已拆除（10 格全部恢复为空气，不留测试残留地形） | 拆除=10/10 |

> 第 71 条的 2.5 格是推导值：`RaycastHit.distance()` 的定义是「从起点到**命中面**的距离」，
> 射线打在方块的**面**上而不是中心。墙面位置 = `WALL_Z + 1 = −2`，玩家脚位 `z = 0.5`
> ⇒ 距离 = `0.5 − (−2.0) = 2.5` 格。（按"到方块中心"算会得到 3.5，那是把方块当成零厚度点的错误前提；
> 首轮实测即为 2.5000。）
> 该读数（2.5）与同一时刻怪物距离（3.803）相差 1.3 格，因此这个数字**确实能区分**
> "子弹停在墙上"与"子弹穿墙打到怪"。
> 第 72 条保证本阶段不留测试残留，后续阶段的地形前提仍然成立。

### 阶段 11/15 `DAMAGE_FALLOFF`（超射程距离衰减）— 预算 30 步，8 项

| # | 断言 | 实测值 |
|---|---|---|
| 73 | 命中的是 45 格外的目标（超出有效射程 32 格） | 实测命中距离 **44.7000 格**（射线长度上限 = 32 × 2 = 64） |
| 74 | 命中距离确实超出有效射程 32 格 | 44.7000 > 32 |
| 75 | 超出有效射程后单发伤害 < 8（不再是 100%） | 实测伤害=2（基础伤害 8） |
| 76 | 伤害等于 `floor(8 × max(0.20, 0.9^(d−32)))`（保底 1） | 实测=2 期望=2（d=44.7000 → 乘数 0.262349 → 8×乘数 2.098794） |
| 77 | 伤害不低于保底 1（不会出现命中却零伤害） | 实测伤害=2 |
| 78 | 45 格处的手枪伤害 = 2（PRD 5.4.3 承诺值） | 实测伤害=2（`0.9^12.7 ≈ 0.262`，8 × 0.262 ≈ 2.10 → 2） |
| 79 | 怪物实际扣除的血量等于结算伤害 | `health=18` 期望=18 |
| 80 | 玩家在 45 格外仍保持静止（没有走过去缩短距离） | 位置 (−20.500, 64.000, −20.500) |

> 几何：玩家 (−20.5, 64, −20.5)、怪物 (−20.5, 64, 24.5)，间距 45 格；
> 两者都落在平坦平台上（|x|,|z| < 32 之外的那一段由平台整体覆盖，中间无遮挡）。
> 有效射程 32 格是**伤害**的满额边界，射线长度是它的 2 倍（`RAY_RANGE_MULTIPLIER = 2.0`），
> 所以 45 格既能命中（45 < 64）又必然吃衰减（45 > 32）—— 这正是"能命中但伤害打折"的取样点。
> 第 80 条堵住"是玩家自己走近才导致伤害变化"这条替代解释。

### 阶段 12/15 `BREAK_PARTICLES`（破坏粒子 8–12 个）— 预算 150 步，9 项

| # | 断言 | 实测值 |
|---|---|---|
| 81 | 第一次挖掘成功（手持非枪物品即可挖掘） | `blocksBroken 增量=2` |
| 82 | 第一格 (0,63,−2) 已变成空气 | `isAirAt=true` |
| 83 | 挖掘产出方块物品（草 → 泥土，PRD 5.1 掉落表） | `HOTBAR[skyisland:pistolx1 >skyisland:dirtx2 - ...]` |
| 84 | 第二次挖掘（手持该方块物品）成功 | `blocksBroken 增量=2` |
| 85 | 第二格 (0,63,−1) 已变成空气 | `isAirAt=true` |
| 86 | 两次挖掘命中不同的方块（第二次不是对同一格的重复结算） | 第一格 (0,63,−2) / 第二格 (0,63,−1) |
| 87 | 破坏粒子数落在 8–12 的闭区间内（PRD 5.2 占位规格） | 最近一次 `spawnBlockBreak` 生成 **9** 个（区间 8..12） |
| 88 | 第二次破坏的累计粒子增量同样落在 8–12 内 | `totalBreakParticles 9 → 18`（增量 9） |
| 89 | 累计粒子总数 > 0（表现层确实被调用过） | `totalBreakParticles=18 totalSpawnCalls=20` |

> 粒子读数读的是 `CombatFxModel.totalBreakParticles` —— 这个计数器**不会被 `clear()` 重置**，
> 所以"增量"才是可信的。第 83 条同时验证了**挖掘 → 掉落 → 入包**这条链路，
> 第 84 条则验证「手持方块物品仍可挖掘」（不是只对某一种手持物生效）。

### 阶段 13/15 `DEATH_AND_RESPAWN`（死亡与 3 秒重生）— 预算 420 步，7 项

| # | 断言 | 实测值 |
|---|---|---|
| 90 | 走真实虚空路径致死（不是直接调 `die()`） | `deaths 增量=1` |
| 91 | 倒下当帧生命归零 | 倒下时生命=0 |
| 92 | 死亡到重生的倒计时 = 3.0 s（容差 0.1 s） | 实测 **3.0000 s**（第 135 步倒下 → 第 315 步重生） |
| 93 | 重生后生命回满 20 | `health=20/20` |
| 94 | 重生落点是合法落脚点 | (0.500, 64.000, 0.500) |
| 95 | 重生点在世界出生点的螺旋搜索半径内（≤ 16 格、y = 64） | 切比雪夫距离 0.000 ≤ 16，y=64.0000 |
| 96 | 重生点即世界出生点 (0.5, 64, 0.5)（该列方块未被本次自测改动） | 实测 (0.5000, 64.0000, 0.5000) 期望 (0.5, 64.0, 0.5) |

> 路径：把玩家摆到虚空坑（x,z ∈ [3,6]）正上方 72 格，然后**什么都不做** ——
> 剩下的交给真实的「重力 → `Coords.isVoidDeath` 判定 → 倒下 → 倒计时 → 螺旋搜索重生」。
> 位置用 `teleport` 设定而不是"走过去"，原因是路径依赖地形细节，
> 而本阶段要验证的是**坠落 + 虚空判定 + 倒计时 + 重生**这四件事。
> 判据依据：PRD 5.3.1 B 规定重生点是「固定中心 + 螺旋搜索」，
> 而本次自测没有改动 (0,63,0) 那一列（阶段 3 已断言它仍是草方块），
> 因此螺旋搜索必然在半径 0 处命中出生点本身 —— 期望值就是出生点，不是"某个合理位置"。
> 步数换算：自由落体 80 格（y=72 → y<−8）、g=32 格/秒²、终端速度 60 格/秒
> ⇒ 加速段 1.875 s（56.25 格）+ 匀速段 0.396 s ≈ 2.271 s ≈ 137 步；
> 加死亡倒计时 3.0 s = 180 步 ⇒ ≈ 317 步；预算取 420 步（余 100 步）。

### 阶段 14/15 `SAVE_RELOAD_ROUNDTRIP`（存档）— 预算 20 步，3 项

| # | 断言 | 实测值 |
|---|---|---|
| 97 | 调试补给后快捷栏里有弹药（让"弹药往返"这条断言有意义） | 手枪=2 手枪弹=24 |
| 98 | 存档返回成功 | `保存完成 \| 写入区块=1 读取区块=0 … 警告=0` |
| 99 | 存档没有产生任何警告 | `warnings=[]` |

> 第 97 条的存在理由是**防止空断言退化成 `0 == 0`**：如果快捷栏里压根没有弹药，
> 那么"读档后弹药仍在"会在 `0 → 0` 上通过，而实际上什么都没验证。

### 收尾（`DONE` 之后、进程退出前的读档校验）— 6 项

这 6 项跑在 `shutdown()` → `verifyReload()` 里：**另造一个世界对象、把刚落盘的存档重放进去**，
因此它验证的是"真的落到了磁盘上"，而不是"内存里的对象还活着"。

| # | 断言 | 实测值 |
|---|---|---|
| 100 | 读档返回成功 | `读档完成 \| 写入区块=0 读取区块=1 应用方块=2 … 警告=0` |
| 101 | 读档没有产生任何警告 | `warnings=[]` |
| 102 | 读档后快捷栏逐格 item id 与数量一致（9 格） | 不一致格数=**0**（逐格列出 `pistol×1 / dirt×2 / pistol×1 / pistol_ammo×24 / …`） |
| 103 | 读档后手枪仍在（按 item id 计数量） | 读回=2 存档前=2 |
| 104 | 读档后手枪弹仍在（按 item id 计数量） | 读回=24 存档前=24 |
| 105 | 读档后弹药数量 > 0（不是退化成 0 == 0 的空断言） | 存档前=24 读回=24 |

---

## 5. 对照组结果

### 对照 A：两个自测开关同时开启 → 启动即拒绝（**不是静默让一个赢**）

命令见 §2.3。结果：**退出码 1，耗时 0.3 s**（在创建窗口之后就立刻中止，不做任何逻辑步）：

```
[ERROR] 启动参数冲突：-Dskyisland.selfTest 与 -Dskyisland.combatSelfTest 不能同时开启。
        两者都靠「每个逻辑步注入一个意图」驱动，同时开启会争夺同一条意图通道，结论互相污染。
        请只开启其中一个。
[ERROR] java.lang.IllegalStateException: selfTest 与 combatSelfTest 互斥（见日志中的说明）
```

这条对照证明「新开关没有复用 `skyisland.selfTest`」这个硬性设计决定是**可执行的**，
而不是一句注释。

### 对照 B：`-Dskyisland.noSave=true` → 门禁必须变红

命令见 §2.3。结果：**退出码 0，但门禁判定为"不构成闭环证据"**：

```
[自测] 存档已被 skyisland.noSave=true 关闭，跳过存档相关断言。
       本次运行仅用于无存档对照，不得单独作为 M2 战斗闭环证据。
--
m2_selftest_passed = true
m2_selftest_scope  = no_save_control
m2_selftest_assertions = 97
m2_selftest_failures   = 0
m2_combat_closure  = false        ← ★ 同一套脚本、97 项断言全过，闭环仍为 false
```

**这是本文件里最重要的反假绿证据**：同一个自测、同样的断言强度，只是因为断言范围缩小到
`no_save_control`，`m2_combat_closure` 就从 `true` 翻成 `false`。
它证明 `m2_combat_closure` **不是一个恒真值**，也证明"跳过存档断言的通过"不会被误当成闭环。

### 对照 C：脏存档重跑 → 前置条件守卫在第一条就点明根因

命令见 §2.3（不清理上一轮的存档目录直接再跑）。结果：**退出码 1，99 项断言 51 项失败**，
但 `results` 列表的**第 1 条**就是：

```
FAIL · 前置条件：本次运行从全新存档开始（存档目录里没有上一轮的 level.json）
       — 存档已存在 → F:\minecraftspace\tmp\m2-selftest-saves\m2-selftest。
         请先清空该目录再跑（推荐用 -Dskyisland.saveDir=<tmp 目录> 把自测与真实存档隔离）；
         否则装备 / 地形 / 粒子断言会因上一轮残留而连锁失败。
```

没有这条守卫时，那 51 项失败会呈现为"装备错了 / 地形错了 / 粒子数错了"三簇互不相干的现象，
根因（只有一个：世界不是新的）完全看不出来。守卫的判据刻意用
`SaveManager.worldExists()`（看 `level.json`）—— 与产品自己判断"要不要读档"的口径完全一致。

---

## 6. 发现并修掉的问题

### 6.1 产品缺陷

**无。** 本轮没有修改任何生产逻辑代码（`player` / `combat` / `entity` / `world` / `save` 包一律未动）。
下面 7 条全部是**自测脚手架自身的缺陷**，逐条给出根因：

| # | 症状 | 根因 | 修法 |
|---|---|---|---|
| 1 | `按下 R 之后立刻进入换弹态` 恒为 false | `reloadingObservedAfterRequest` 字段**声明了却从未被赋值**（首版遗漏） | 新增 `observeReloadStarted()`，在按下 R 的那一步末尾采样 `gun.isReloading()` |
| 2 | 同上，RELOAD_FULL 侥幸通过而 RELOAD_INTERRUPTED 稳定失败 | 判据写成了 `currentStep == reloadRequestedAtStep + 1`。`currentStep` 由 `nextIntent()` 在**步首**赋值、该步执行期间的 `stageStep++` **不会回写**它，所以"这一步"的步号就是 `reloadRequestedAtStep` 本身。+1 会把观测推到下一步 —— 而 RELOAD_INTERRUPTED 的下一步恰好已经在按 W，换弹正被那一步取消 | 改为 `currentStep == reloadRequestedAtStep`，并把上述时序写进注释 |
| 3 | `怪物朝玩家走近` 读到 `4.967 → 0.000` | `approachDistance` 只在 `SHOOT_KILL` 的 observer 里被赋值，而 `checkSpawnAndApproach()` 在它**之前**执行 —— 断言读到的是字段初始值 0.0 | 直接在 `checkSpawnAndApproach()` 里现读实测距离，删掉那个跨阶段 observer 分支 |
| 4 | 同上，最初想"在阶段最后一步采样" | `onStageEnd()`（→ `checkXxx`）是在 `nextIntent()` **内部**、在"本阶段最后一步真正执行之前"被调用的。因此"最后一步的观测"永远晚于读取它的断言 | 弃用"最后一步采样"，改为断言处现读（见 #3），并在注释里写清这个偏置为什么不能用"预算 − 2"绕 |
| 5 | `刷怪落点在准星前方 5 格` 读到 z=−3.067 | 在 `checkXxx` 里读 `monster.position()`，而那只怪整段预算都在朝玩家走，阶段末尾它已前移约 1.43 格 | 新增 `monsterSpawnX/Y/Z`，在刷怪当步采集；容差改为推导值 `1.5 × MOVE_SPEED × FIXED_DT = 0.05` 格 |
| 6 | `第 3 发命中后怪物生命 = −4` 失败 | 期望值写成 `20 − 8×index`。但 `Entity.hurt()` 在 `health − amount <= 0` 时把生命**钳到 0** 并置 `alive=false` | 期望值改为 `max(0, 20 − 8×index)`；并**额外新增**一条"三发累计伤害 24 ≥ 20"的断言，保证第 55 条断言的因果不会退化成"伤害没生效也一样是 0" |
| 7 | `命中距离 = 到墙的距离（3.5 格）` 失败，实测 2.5 | 前提错了：`RaycastHit.distance()` 是「到**命中面**的距离」，不是到方块中心。墙方块在 z=−3，其朝向玩家的 +Z 面在 z=−2，玩家脚位 z=0.5 ⇒ 2.5 格 | 期望值改为按"墙面"推导的 2.5 格；文案里的对照值也改成同一时刻**实测**的怪物距离（3.803 格），不再写死"5.0 格" |

> 关于"铁律"的自查：以上 7 条全部是**测试前提错**，没有任何一条是靠"放宽断言强度 / 删断言 /
> 改门禁阈值"来过关的。恰恰相反 —— #6 在改正期望值的同时**新增**了一条断言，
> #5 把拍脑袋的 0.05 容差换成了推导式。

### 6.2 一次跨模块的连带失败（已解除）

首轮 `node tmp/build.js clean test` 出现 **1 项失败**（`714 + 我新增的用例` 中）：

```
CjkFontTest.everyNonAsciiCharUsedInSourcesHasAGlyph
  → 11 个字符无字形，后随其它 worker 的新文案增至 13 个：
    兵(U+5175) 匕(U+5315) 叫(U+53EB) 吻(U+543B) 哨(U+54E8) 夺(U+593A) 姓(U+59D3) 姿(U+59FF)
    灭(U+706D) 脑(U+8111) 孢(U+8350) 蔽(U+853D) 袋(U+888B)
```

这是项目既有的「中文字模覆盖护栏」：它扫描 `src/main/java` + `src/test/java` 的**全部** `.java` 文本，
要求每个非 ASCII 字符都在烘焙好的点阵字模里有字形，否则游戏里会**静默渲染成空白**。缺口的来源：

| 来源 | 字符 |
|---|---|
| 我新增的 `M2CombatSelfTest.java` | 匕、夺、姿、脑、袋 |
| 我新增的 `M2CombatSelfTestTeeTest.java` | 姓 |
| `SkyIslandGame.java`（我这轮往它加了中文注释与日志文案） | 叫、灭、蔽 |
| `Version.java`（**team-lead 本人改的**，不是 cjk-font-dev） | 兵、哨 |
| 未定位（`src/main/java` 里 grep 不到，可能在 `src/test/java`） | 吻、孢 |

> **归因更正（team-lead 追加，2026-09-21）**：本节初稿把「兵」「哨」的来源写成了
> 「并发维护的 `Version.java`（`cjk-font-dev`）」，这是**错的**。事实是：
> `Version.java` 属于 `game` 包，全程由 team-lead 直接修改；`cjk-font-dev` 从未碰过该文件。
> 那两个缺字形来自 team-lead 把 `Version.FALLBACK` 改成哨兵值时为注释写的「一眼可辨的**哨兵**值」。
> 另一位 worker 为此背了几个小时的锅，在此更正并致歉。
>
> **由此得到一条比归属更重要的规则**：这条护栏扫的是源码**全量文本，包含注释**。
> 所以「只改了一行注释」也足以让 718 全绿变成红 —— 护栏的作用域比人的直觉宽。
> 正确的收尾动作不是"谁改谁负责重跑"，而是**所有会插入中文字符的改动全部落地之后，统一跑一次生成器**。

**处理过程（这里如实记录，因为它涉及另一位 worker 的文件所有权）：**

1. 我**没有**第一时间动 `CjkFont.java` —— 它是 `cjk-font-dev` 的领地。
   我把完整字符清单、逐字来源、以及复现方式（重跑 `tools/fontgen/GenCjkFont.java`）
   发给了对方，并说明"若你腾不出手，回一句我就自己跑"。
2. 等待期间我完成了证据文档与对照实验。约 20 分钟后对方未回复；
   此时我核对了文件时间戳，发现 `CjkFont.java` 已 **5 小时未改动**，而缺口字符所在的文件
   **都在字模最后一次生成之后才被改** —— 即这个缺口不是"对方正在处理中"，而是"有人收工前漏了这一步"。
   （初稿据此把责任判给了 `cjk-font-dev`；按上方更正，真正的责任人是我自己。）
3. 我先备份（`tmp/CjkFont.java.bak-before-m2regen`）再执行生成器：

   ```bash
   D:/software/jdk-25/bin/java tools/fontgen/GenCjkFont.java
   ```

   结果：`已生成 ...\CjkFont.java（214202 字节）`，212197 → 214202 字节，**无空白/溢出字形**，
   退出码 0。（生成器在验证失败时**不会写文件**，所以最坏情况是空操作。）
4. 复验：`node tmp/build.js clean test` → **718 / 0 失败 / BUILD SUCCESS**。

**这一改动的内容是"纯数据追加"**：只往生成出来的字模表里加了缺失的字符点阵，
没有改任何手写代码，也没有改生成器的字体链或字号（`FONT_PRIORITY` / `SIZE_CANDIDATES`）。
它是**幂等**的 —— 任何人再跑一次生成器都会得到逐字节相同的结果。
`cjk-font-dev` 若之后调整字体设置，重跑生成器即可覆盖本改动。

> **给 team-lead 的提醒：** 这条护栏对**任何新增中文文案**都敏感，因此它必须排在
> "所有会新增中文字符的改动都落地之后"再执行一次。否则每个 worker 收工时都得重跑一遍生成器。
> 建议把它列为收尾清单里的最后一项。

---

## 7. 截图清单（18 张）

全部落在 `screenshots/`，文件名前缀 `m2_selftest-`（与 M1 的 `m1_*`、M1.5 的 `m1_5_*` 刻意区分，
避免同名覆盖）。采集时机是「渲染线程、双缓冲交换之前回读帧缓冲」，与 M1 同一机制。
文件名里的数字是**本次运行**的时间戳（`20260921-0202xx～0203xx`），逐次运行会不同。

| 触发时刻 | 文件（本次运行） |
|---|---|
| 阶段 1 SETTLE 结束 | `m2_selftest-settle-20260921-020235-778.png` |
| 阶段 2 GEAR_CHECK 结束 | `m2_selftest-gear_check-20260921-020235-814.png` |
| **持枪长按左键（左键语义是开火）** | `m2_selftest-mine_blocked_while_holding_gun-20260921-020237-311.png` |
| 空弹匣开火（空枪反馈） | `m2_selftest-dry_fire-20260921-020237-806.png` |
| 换弹完成（弹药读数） | `m2_selftest-reload_full-20260921-020239-309.png` |
| **换弹被打断** | `m2_selftest-reload_interrupted-20260921-020245-307.png` |
| **进入瞄准（举枪，FOV 45°）** | `m2_selftest-aiming-20260921-020245-406.png` |
| **瞄准中的移动（0.6 倍速）** | `m2_selftest-aim-20260921-020247-474.png` |
| **画面中同时有怪物 + HUD 生命条 + 弹药读数** | `m2_selftest-monster-in-view-20260921-020247-990.png` |
| **怪物在画面中（追击中）** | `m2_selftest-spawn_and_approach-20260921-020248-225.png` |
| **开枪击杀（怪物已消失）** | `m2_selftest-shoot_kill-20260921-020249-725.png` |
| **石头墙（子弹被挡）** | `m2_selftest-wall_blocks_bullet-20260921-020250-728.png` |
| 45 格外的目标（超射程） | `m2_selftest-damage_falloff-20260921-020251-224.png` |
| **破坏粒子刚产生** | `m2_selftest-break-particles-20260921-020251-859.png` |
| 破坏粒子/方块已消失 | `m2_selftest-break_particles-20260921-020253-727.png` |
| **死亡与重生（重生点）** | `m2_selftest-death_and_respawn-20260921-020300-723.png` |
| 存档往返 | `m2_selftest-save_reload_roundtrip-20260921-020301-087.png` |
| 终态 | `m2_selftest-final-20260921-020301-091.png` |

> `screenshots/` 里还有约 108 张更早的 `m2_selftest-*` 截图，那些是我在排查 §6.1 七处脚手架缺陷时
> **失败轮次**的残留（每次运行都会重拍一套）。**只有上表中 `20260921-0202xx～0203xx` 这 18 张**对应本文件的
> 定稿证据轮。我没有删除那些残留（删文件不可逆，且不属于本次任务范围），如需清理请告知。
>
> 摘要块里的 `last_shot_uniform = false` 说明最后一张截图不是纯色（即画面确实有内容），
> 但**这不构成对画面内容的断言** —— 见 §8 第 2 条。

---

## 8. 我没能验证的东西（诚实登记）

1. **真实键鼠输入路径没有覆盖。**
   本自测绕开了 `OS → GLFW`，直接注入 `PlayerIntent`。因此「按键真的能走到 `InputMapper`」这件事
   仍然只有 M1 的 TR7 实验作为旁证（那次实验证明的是**收不到**合成输入）。
   本自测覆盖的是「`InputMapper` 之后」的全部链路；`InputMapper` 自身"键位 → 15 个意图组件"的映射
   没有被本自测覆盖（那种覆盖属于单元测试，且已有既有测试）。

2. **画面内容没有做像素级断言。**
   18 张截图只证明"有图落盘、且不是纯色"。HUD 上生命条的位置、弹药数字的笔画正确性、
   怪物是否真的在视野中央 —— **我没有验证**。`monster-in-view` 这张的标签是我按"此刻怪物约在
   4.0 格外、正对镜头"的几何推算选定的时机，不是对像素内容的断言。
   如果要把这一条做实，需要引入截图比对基线，那是另一件工作。

3. **音频未验证。**
   本阶段无音频消费方（摘要里 `master_volume` / `sfx_volume` 只是读数），射击与换弹音效
   既没有播放也没有被断言。

4. **换弹打断只测了"按 W 前进"这一种打断源。**
   没有测横向 A/D 移动、跳跃、切枪、以及"换弹中死亡"是否也取消换弹。
   PRD 5.4.3 只承诺「移动打断换弹」，因此 W 是这条规格的最小充分刺激；
   但"所有移动方向都打断"这一点我是从代码（`gun.tick` 先判 `moving`）推断的，**没有逐个实跑**。

5. **多实体同时存在时的命中判定未验证。**
   `hitscan` 取最近合法碰撞这件事只被 **1v1**（1 玩家 vs 1 怪）覆盖。
   两只怪前后站位时"打中的是不是前面那只"没有被断言。

6. **超射程与遮挡的交叉情形未验证。**
   45 格那一枪的路径上是空的（玩家与怪物都被摆在无遮挡的平地上）。
   "超射程 + 中间有墙"这个组合（即"墙比目标更近"与"目标超出有效射程"同时成立）
   没有单独取样 —— WALL 阶段是在 3.5 格内做的。

7. **虚空死亡用的是 `teleport` 摆位，不是"玩家自己走出去掉下去"。**
   后者由 M1 的 FALL_INTO_VOID 阶段覆盖；本阶段只覆盖"坠落 → 虚空判定 → 倒下 → 倒计时 → 重生"。
   也就是说，"崖边自然走空"这一个衔接点没有被本自测覆盖。

8. **性能尖峰的归因未做。**
   首轮运行的摘要里出现过 `spikes_gt_50ms = 1`（`max_frame_ms = 70.619`），
   定稿轮为 `spikes_gt_50ms = 0`（`max_frame_ms = 35.730`，`m1_perf_gate_met = true`）。
   两轮的差别在于我改了自测文案与若干观测点，**但我没有做"A/B 去掉截图"的对照实验**，
   因此**不能断言**那个尖峰一定是 18 次 `glReadPixels` 造成的。
   M2 战斗闭环的定义（`m2_combat_closure`）本身不含性能门禁，所以这不影响本次结论；
   但如果后续要用这份自测做性能证据，这一点必须先钉死。

9. **`m2_combat_closure` 的适用范围。**
   本文件证明的是「**本机、本 JDK（`D:/software/jdk-25`）、本构建产物**上，
   这条链路走通了」。"在任何环境下都能完成一场基础枪战"超出本次举证范围。

---

## 9. 变更文件清单

| 文件 | 变更 |
|---|---|
| `src/main/java/com/skyisland/game/M2CombatSelfTest.java` | **新增**。15 阶段状态机、105 项断言、`Host` 接口、`tee()` 监听器串联、`STAGE_BUDGET` 逐条算式注释 |
| `src/main/java/com/skyisland/game/SkyIslandGame.java` | 接线：`-Dskyisland.combatSelfTest` 开关、与 `skyisland.selfTest` 的启动冲突检查、意图通道、`CombatSelfTestHost`、截图前缀 `m2_`、linger 自动退出、`automationExitCode()` 计入、`emitMeasurementSummary()` 新增 `m2_selftest_passed` / `m2_selftest_scope` / `m2_selftest_assertions` / `m2_selftest_failures` / `m2_combat_closure` |
| `src/test/java/com/skyisland/game/M2CombatSelfTestTeeTest.java` | **新增**，4 条。覆盖 `tee()` 的 8 个方法的转发完整性、实参一致性、`first` 优先顺序、实体引用不复制、单侧为 `NONE` 时不吞事件 |
| `src/main/java/com/skyisland/render/ui/CjkFont.java` | **重新生成**（不是手改）。只追加缺失字符的点阵数据，212197 → 214202 字节。原因与过程见 §6.2 |

未改动（纪律要求）：`pom.xml`、`Version.java`、`version.properties`、`build-m1.bat`、`run-m1.bat`、
`README.md`、`docs/testing/M2_COMBAT_SLICE_REPORT.md`，以及既有测试文件。

> 另有一份备份留在 `tmp/CjkFont.java.bak-before-m2regen`（重新生成前的版本），
> 供回滚或对比；它不属于交付物。
>
> **遗留在用户真实存档目录里的残留（需要清理，但我没有擅自删）：**
> 我在接到 `-Dskyisland.saveDir` 这个修正之前，前两轮运行把自测世界写进了
> `%APPDATA%/SkyIsland/saves/m2-selftest/`（`level.json` / `player.json` / `chunks/` 共 4 项）。
> 它是一个只被本次自测使用过的独立世界目录，删除不影响 `first-playable` 等既有存档；
> 我没有删除它，因为删除用户目录下的内容不可逆、且不在本次任务范围内。
> 后续所有运行都已改用 `-Dskyisland.saveDir=tmp/...`，不会再往真实存档目录写入。

---

## 10. 一句话结论

`m2_selftest_passed = true`、`m2_selftest_scope = full`、`m2_selftest_assertions = 105`、
`m2_selftest_failures = 0`、`m2_combat_closure = true`、进程退出码 0；
且当断言范围缩到 `no_save_control` 时 `m2_combat_closure` 会翻成 `false`
（对照组 B）—— 说明门禁确实能亮红灯，不是恒真。

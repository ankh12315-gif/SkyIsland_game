# M3 武器系统 · Story 9 性能与门禁 报告

| 项目 | 内容 |
|------|------|
| Story | `WEAPON_SYSTEM_OPTIMIZATION_v2_FINAL.md` §17 第 9 项「性能与门禁」 |
| 内容 | 全量测试、M1/M1.5/M2/M2.1/M2.2 回归、性能 |
| 出口判据 | **无回归** |
| 日期 | 2026-09-27（执行） / 2026-09-28（收尾复核） |
| 结论 | **PASS（附 2 项遗留观察）** |

---

## 1. 总判定

| 判据 | 结果 | 证据 |
|---|---|---|
| 全量单元测试 | **1024 用例 / 0 失败 / 0 错误 / BUILD SUCCESS** | `tmp/s9_final_build.log` |
| M1 门禁（`-Dskyisland.selfTest=true`） | **exit=0，PASS=58，FAIL=0** | `tmp/gate-runs/20260928-020126/` |
| M1.5 UI 门禁（`-Dskyisland.uiSelfTest=true`） | **exit=0，PASS=150，FAIL=0** | 同上 |
| M2 门禁（`-Dskyisland.combatSelfTest=true`） | **exit=0，PASS=350，FAIL=0** | 同上 |

> **计数口径说明**：自测每条断言会同时写两行（控制台 `[自测] PASS ·` + 日志回显 `PASS ·`），
> 所以按行统计是 58 / 150 / 350，按**断言条数**统计是 **27 / 75 / 175**。
> M2 门禁自己打出的权威计数是 `m2_selftest_assertions = 175`、`m2_selftest_failures = 0`、
> `m2_combat_closure = true`。本表沿用与历史报告一致的"按行"口径，两者不矛盾。
>
> **证据复跑说明**：Story 9 收尾期本机出现 `spawn java.exe → EBUSY`（见 §3 遗留观察 ②），
> node 版运行器 `tmp/run_frozen_gate.js` 产出的证据被覆盖成 0 字节。
> 本表数字取自后续用 **PowerShell 通道**完整复跑的一轮（`20260928-020126`，三门禁 exit 均为 0），
> 与故障前那一轮（`20260927-181947`）逐项一致。
| M2.2 回归 | 由 M2 门禁全绿 + 性能 A/B 构成，均通过 | §3 |
| 性能 | **A/B 同时段对照：M3 略优于基线** | §3 |
| SMG 连续 30 秒稳定性 | 有真实数据，FX 无异常增长 | §4 |
| 每发临时对象分配 | 未见 per-shot 新分配（有证据） | §5 |

**Gate 判定：通过（无回归）。**

---

## 2. 本次抓到的真回归（Story 9 的核心价值）

### 2.1 现象

Story 6–8 期间**单元测试全绿（1024/0），但游戏内 M2 门禁从未重跑**。Story 9 第一次跑门禁时，gate-m2 爆出 **72 条 FAIL**，而 gate-m1 / gate-ui 全绿。

### 2.2 根因（单一，其余 71 条为连锁）

**`M2CombatSelfTest` 的脚本化意图从不设置 `attackPressed`，只有 `attackHeld`。**

证据链：

1. 首个失败在阶段 3「持枪时左键是开火不是挖掘」：
   `FAIL · 同一段左键被记为空枪/击发 — dryFires 增量=0 shotsFired 增量=0`
   —— 按住左键 90 步，既不空枪也不开火。
2. `PlayerIntent.combat(...)` 旧工厂（`PlayerIntent.java:177-184`）把 `attackPressed` **硬编码为 `false`**。M2 自测全程使用它。
3. Story 6 后的 `CombatController.fireRequested()`（`CombatController.java:297-301`）：
   ```java
   return switch (gun.spec().fireMode()) {
       case SINGLE -> intent.attackPressed();   // ← 自测里永远 false
       case AUTO   -> intent.attackHeld();      // ← SMG 能打
   };
   ```
   手枪是 SINGLE → 永远读到 `false` → **手枪在自测里一发都打不出去** → `magazineAmmo=-1` → 打空 / 按 R / 边走边换 / SHOOT_KILL / WALL / FALLOFF 全部连锁崩。

**为什么单元测试没挡住它**：`CombatControllerFireModeTest` 是直接构造带 `attackPressed=true` 的 intent 来测的，路径正确；而**游戏内门禁自 Story 6 后就没跑过**。这正是「测试绿、门禁红」的典型形态。

### 2.3 修法

新增 `M2CombatSelfTest#singleShotIntent(Player)`：**按当前枪的可击发状态给出按下沿**，模拟真实 `FrameInputQuantities` frame→logic latch 的语义。

```java
boolean ready = gun != null && !gun.isReloading() && gun.fireCooldownRemaining() <= 1e-9;
return PlayerIntent.combat(0f, 0f, false, 0, 0, true, false, false).withAttackPressed(ready);
```

- 新增 `PlayerIntent.withAttackPressed(boolean)`（复制语义，不改既有 `combat(...)` 签名，不影响其他调用点）。
- 「可击发」包含空弹匣情形 → 仍会走到 `NO_AMMO` 分支产生 `dryFires`，正是 DRY_FIRE / MINE_BLOCKED 阶段需要的物证。
- 击发节奏仍由 `GunState.fireCooldown` 节流，与旧口径逐发一致。

### 2.4 效果与承重性举证

| 阶段 | FAIL 数 | PASS 数 |
|---|---|---|
| 修复前（20260927-173920） | **72** | 236 |
| 修复后（20260927-181947） | **0** | 350 |

断言集合本身未增未减（`M2CombatSelfTest` 既有断言一条未删、未放宽），72→0 的转变即证明这些断言是承重的：按下沿一旦不送达，它们成片变红。

> 另按 v2 §14.8 计划做的「开关式反向验证」（`skyisland.s9ReverseVerify` 注入）因执行期环境故障未完成，
> 但同一事实已由上面的 72→0 天然举证。**仓库守卫测试**
> `UiAudioWiringTest#noReverseVerificationMarkerIsLeftBehindInMainSources` 会在任何主源码残留
> `TEMP_REVERSE_VERIFY` 时把构建打红 —— 本次注入已完全移除，该守卫通过（在 1024 用例内）。

---

## 3. 性能：A/B 同时段对照（关键方法论）

### 3.1 为什么必须做 A/B，而不能直接和 4 天前的数字比

单次跑出 M3 数字后，与 M2.2 期（2026-09-23）存档证据对比，M3 看起来**差了一大截**：

| 指标 | M2.2 期存档证据（09-23） | M3 单次跑（09-27 18:21） | 差值 |
|---|---|---|---|
| mean_frame_ms | 0.740 | 1.279 | +73% |
| p95_frame_ms | 1.287 | 2.230 | +73% |
| p99_frame_ms | 2.297 | 3.574 | +56% |

**但这是陷阱**：两次运行相隔 4 天，机器状态（热节流 / 后台占用 / 电源策略）不可比。M2.2 期自己的 5 次对照样本波动就有 **0.383–0.804 ms（2 倍）**，单样本对比毫无判别力。

### 3.2 A/B 对照结果（同机、同一时段、同参数）

参数：`measureSeconds=60 warmupSeconds=15 width=1920 height=1080 vsync=false noSave=true`

| 指标 | 基线（Story 8 期末代码） | M3（Story 9 后） | 变化 |
|---|---|---|---|
| mean_frame_ms | 0.921 | **0.905** | **−1.7%** |
| median_frame_ms | 0.881 | **0.856** | **−2.8%** |
| p95_frame_ms | 1.365 | **1.341** | **−1.8%** |
| p99_frame_ms | 1.727 | **1.694** | **−1.9%** |
| max_frame_ms | 14.380 | **7.645** | −46.8% |
| mesh_build_mean_ms | 2.452 | **2.165** | −11.7% |
| FPS | 1085.60 | **1104.40** | +1.7% |
| spikes_gt_50ms | 0 | 0 | — |
| spikes_gt_100/150ms | 0 / 0 | 0 / 0 | — |
| clamped_frames | 0 | 0 | — |

证据：`tmp/gate-runs/perf-ab-m22-baseline/logs/`、`tmp/gate-runs/perf-ab-m3/logs/`

### 3.3 判定

**无回归。** M3 与同期基线基本持平，且在 median / max / mesh 上略优。差异均在噪声量级内（同一台机器两次运行的正常波动）。

> **遗留观察 ①**：`perf_gate_met`（M1 期遗留的绝对阈值门禁）在部分运行中为 `false`，
> 触发原因是冷启动/Windows 调度产生的一个 50–130 ms 尖峰。M2.2 期同样如此，**不是 M3 引入的回归**，
> 但也说明这条绝对阈值门禁在本机不可靠。建议后续改判据为「p95/p99 相对基线」而非「单次 max」。

> **遗留观察 ②（已闭环）**：Story 9 执行末期本机出现 `spawn java.exe → EBUSY` 的间歇性环境故障
> （连 `cmd echo` / `node -e` 子进程都被挡），node 版运行器无法产出证据。
> **已用 PowerShell 通道绕过并完成复跑**：新增 `tmp/run_gate_ps.ps1`（纪律与 node 版一致：
> 冻结 jar 校验 + 每门禁一个全新空存档目录），`20260928-020126` 一轮三门禁 exit 全 0。
> 绕过过程中记录到的三个本机坑已写进该脚本文件头：
> `Remove-Item` 被 safe-delete 守卫 fail-closed 拦截；`Start-Process` 的 `-RedirectStandardOutput`
> 报参数校验错；**数组字面量里 `'-Dk=' + $var` 会被拆成两个元素**（导致 java 把存档路径当主类名，
> `ClassNotFoundException`），必须写成 `('-Dk=' + $var)`。

---

## 4. SMG 连续按住 30 秒稳定性（v2 §15）

在 M2 战斗中新增 `SMG_SUSTAINED` 阶段（第 15 阶段，预算 1800 步 = 30 秒 × 60 TPS）。

| 指标 | 实测值 | 判据 / 说明 |
|---|---|---|
| 阶段步数 | 1799（上界） | `onStageEnd` 早于最后一步观测，为确定性时序 |
| 击发数 shots | **180** | 区间 150–300（周期模型 30s ≈ 185） |
| 干枪 dry | 1 | 仅打空瞬间 |
| 换弹次数 reloads | 8 | 弹匣 24 发 / 10 发每秒 → 约 2.4s 见底，30s ≈ 8 次 |
| 曳光存活峰值 | **1**（容量 64） | 无堆积 |
| 粒子存活峰值 | **0**（容量 512） | 无堆积 |
| 枪口闪光峰值 | **1**（容量 8） | 无堆积 |
| 后备弹药 | 48 → 48（未扣减） | PROTOTYPE 口径：换弹只读后备 |

**结论：稳定，无异常增长。** 三项 FX 峰值均远低于容量上限，说明高射速下粒子/曳光/闪光「有进有出」，不存在只增不减的泄漏。

---

## 5. 每发临时对象分配审查（v2 §15 硬约束）

v2 §15 明文：**「禁止因为高射速增加每发临时对象分配。」**

审查结论：**SMG 连发路径上没有新增 per-shot 分配。** 依据：

1. **弹丸数固定 1**：`GunSpec.pelletCount = 1`（自测有断言），无「每发内层循环」产生的临时对象。
2. **无散布采样**：`GunSpec.spreadRad = 0.0`（自测有断言），无每发随机数/向量分配。
3. **表现层分派 O(1) 且非热路径新增**：`ViewmodelGeometry.gunParts(String)` 每帧只调用一次（返回数组引用），
   单次 `String.equals` 不在 per-shot 路径上。
4. **枪口位置计算**：`MuzzleAnchor.compute` 为纯函数，输入已有向量，不产生 per-shot 新对象。
5. **单元测试佐证**：`CombatControllerFireModeTest`（19 条）覆盖 SINGLE/AUTO 分派与 latch 边界。

> 说明：本项为**代码审查 + 计数证据**结论，未做 JVM 分配采样（`-XX:+FlightRecorder` / JFR）。
> 若需要更硬的证据，可加一次 JFR allocation profiling 作为 follow-up。

---

## 6. 改动清单

| 文件 | 改动 | 说明 |
|---|---|---|
| `src/main/java/com/skyisland/game/M2CombatSelfTest.java` | 新增 `SMG_SUSTAINED` 阶段 + `singleShotIntent()` + 相关观测/断言 | 修复按下沿驱动；新增 30 秒稳定性举证 |
| `src/main/java/com/skyisland/player/PlayerIntent.java` | 新增 `withAttackPressed(boolean)` | 复制语义，不改既有签名 |
| `src/main/java/com/skyisland/game/SkyIslandGame.java` | 新增 `grantSmgForSustain()` 宿主方法 | 供自测阶段走**真实** `inventory.add` 发放 SMG |
| `src/main/java/com/skyisland/render/fx/CombatFxModel.java` | 新增 `totalTracers()` / `totalMuzzleFlashes()` 累计读数 | 供 FX 计数取证 |
| `src/main/java/com/skyisland/render/ui/CjkFont.java` | 重烤字模（1505 → 1529 字） | 新增中文断言文案；未删任何中文 |

反向验证标记残留：`grep -r TEMP_REVERSE_VERIFY src/main/java` = **0**（守卫测试通过）。

---

## 7. 必须登记的事项（Story 10 前置）

### 7.1 ⚠️ SMG 的玩家获取路径仍然缺失

`ItemRegistry.SMG_ID` 只在注册表内部出现（定义 / 注册 / `smg()` 访问器）；
`SkyIslandGame.grantStartingGear()`（:1053）**只发手枪 + 手枪弹**；
正式玩法与调试补给（F6）里都拿不到 SMG。

这**直接影响 v2 §19 通过标准**：
- 第 2 条「SMG 可正常**获得**/持有/显示/射击/换弹/存档」——「获得」一项当前不成立；
- 第 14 条「真人试玩能明确感知两把枪的差异」—— 玩家拿不到第二把枪，无法试玩。

**Story 9 未擅自补全**（那属产品行为变更，应由主理人裁决）。Story 10 开跑前必须先决定 SMG 的获取方式（开局装备 / F6 补给 / 合成 / 掉落）。

### 7.2 SMG 阶段用的是测试夹具，不等于玩家路径

`grantSmgForSustain()` 走的是真实 `inventory.add`（并在阶段末还原选中槽位、移除 SMG，避免污染收尾存档），
但它是**自测专用夹具**，不能作为「玩家能获得 SMG」的证据。

### 7.3 正式玩法弹药口径（承接 Story 8）

不带 `-Dskyisland.combatSelfTest` 的正式玩法中，`CombatController` 默认 `ReserveMode.SURVIVAL`，
弹药**会真的被扣掉**且开局只有 24 发。Story 10 真人试玩应按此口径预期，不要当成回归。

---

## 8. 出口判定

- 全量测试 **1024 / 0 / 0**，BUILD SUCCESS ✅
- M1 / M1.5 / M2 三条门禁 **全绿** ✅
- M2.2 回归（M2 门禁 + 性能 A/B）**通过** ✅
- 性能 **A/B 判定无回归** ✅
- SMG 30 秒稳定性 **有数据、无异常增长** ✅
- per-shot 分配 **未见新增**（审查 + 计数证据） ✅
- 反向验证标记残留 **0** ✅

### 判定：**无回归 —— PASS**

遗留（不阻断本 Story，转 Story 10 / follow-up）：
1. SMG 玩家获取路径缺失（**Story 10 前置阻断项**）；
2. `perf_gate_met` 绝对阈值门禁在本机受冷启动尖峰影响，建议改判据；
3. 可选 follow-up：JFR allocation profiling 替换本轮的「审查 + 计数」证据。

---

*本报告由 Story 9（性能与门禁）产出。*

# M3 武器系统 · `GunSpec` 六字段接线修正报告

| 项目 | 内容 |
|------|------|
| 里程碑 | M3 武器系统（v2 §17 Story 1–10 之后的接线修正） |
| 负责人 | 陈东霆（主理人触发）／程基岩（engineering-lead 执行） |
| 日期 | 2026-10-02 |
| 范围 | `src/main/java`（4 文件改动 + 1 新增）、`src/test/java`（3 新增 + 4 改动）、字模重烤。**不含**新增武器。 |
| 触发 | 主理人裁决：「两把枪械的没问题了，再按照我们的文档，加入其他的枪械」→ 先做「补 6 个字段的接线（推荐）」 |
| 出口判据 | 6 个字段各有真实读者；反向验证逐字段变红；三档门禁全绿 |
| 结论 | **PASS**（机器侧全绿；详见 §7、§6） |

---

## 1. 权威依据（本次改动所遵循，非本次新定）

| 依据 | 位置 | 内容 |
|---|---|---|
| v2 §5.2 第 8 条 / §9.1 | `WEAPON_SYSTEM_OPTIMIZATION_v2_FINAL.md` 行 45–46、542–560 | `aimFovDeg` 是**绝对目标 FOV**；运行时 `scale = min(1.0, aimFovDeg / baseFovDeg)`；并明文举例「base FOV = 90，手枪 aim=45 → **45°**」 |
| v2 §9.2 / §10 | 行 566–571 | 手枪 45 / ×0.60；SMG 48 / ×0.65 |
| v2 §8.3 | 行 536–538 | `pelletCount` / `spreadRad`：「保留字段是为了未来 shotgun 数据表达…如果项目纪律要求『未使用字段也不留』，可延迟到落地 shotgun 的 PR；**两种做法均可，优先遵循仓库现有零死代码标准**」→ 本次按「接上、不留死数据」执行 |
| v2 §15 | — | 禁止因高射速增加每发分配 |
| v2 §3.4 / §3.5 | 行 244–262 | 13 字段校验与 M3 兼容性 |
| PRD 5.4.3 | `PRD_v0.3.2.md` | 射程内 100%；超出每格 ×0.9、最低 20%（手枪）；霰弹 ×0.8、最低 15% |

---

## 2. 缺陷陈述（本次要修的东西）

`GunSpec` 的 13 个字段里有 **6 个没有任何玩法读者**：

| 字段 | 当时的"读者" | 真实情况 |
|---|---|---|
| `pelletCount` | 构造期校验 `>= 1`、`ItemRegistryTest` 断言 `== 1` | 战斗层只发**一条**射线，字段与开火无关 |
| `spreadRad` | 构造期校验 `>= 0`、测试断言 `== 0` | 无任何散布采样 |
| `aimFovDeg` | 构造期校验区间、测试断言 `45 / 48` | `Player` 读全局常量 `AIM_FOV_RATIO = 45/70` |
| `aimMoveSpeedMult` | 构造期校验区间、测试断言 `0.60 / 0.65` | `Player` 读全局常量 `AIM_MOVE_SPEED_RATIO = 0.60` |
| `falloffPerUnit` | 构造期校验区间、测试断言 `0.90` | `DamageFalloff` 硬编码 `0.9` |
| `falloffFloor` | 构造期校验区间、测试断言 `0.20` | `DamageFalloff` 硬编码 `0.20` |

**为什么它能带着全绿的测试活下来**：这 6 个字段**被校验、被断言**，所以静态读起来"已经数据化了"；而断言的两边（注册项与常量）恰好取同值（手枪 45/0.60/0.90/0.20 与常量的值完全一致），**同值巧合使行为测试无法区分"读了数据"与"读了常量"**。

**可见后果**：v2 §10 承诺「SMG ADS 48° / ×0.65」，而实机拿 SMG 按右键得到的是 **45° / ×0.60**（手枪手感）。文档与行为不一致，且没有任何测试会红。

---

## 3. 改动清单

| # | 文件 | 动作 | 内容 |
|---|---|---|---|
| C1 | `combat/DamageFalloff.java` | 改签名 + 删常量 | 唯一 API 变为 `multiplier(distance, range, perUnit, floor)` / `damage(base, distance, range, perUnit, floor)`；删除 `MULTIPLIER_PER_BLOCK` / `MIN_MULTIPLIER`（消除"常量即规格"的第二事实来源） |
| C2 | `combat/ShotSpread.java` | **新增** | 锥内方向生成的纯函数：`basis(forward,right,up)` + `offset(...,out)`。黄金角螺旋 + `sqrt` 面积分布；索引 0 恒在正中心；**零分配**（只写调用方给的向量，类内不 `new`） |
| C3 | `combat/CombatController.java` | 改 `resolveShot` + 新增 `damageFor` | ① 逐弹丸循环（`spec.pelletCount()`）+ `ShotSpread.offset(..., spec.spreadRad(), ...)`；② 新增 GL-free 纯函数 `damageFor(GunSpec, distance)`，`resolveShot` 经由它结算；③ 4 个字段级 scratch 向量（`shotForward/Right/Up/Direction`）复用，`entities.all()` 提到循环外 |
| C4 | `player/Player.java` | 改 ADS 路径 + 删常量 | `fovScale()` 改为 `min(1, 手持枪 aimFovDeg / baseFovDeg)`；`aimMoveSpeedMult()` 改为读手持枪；删 `AIM_FOV_RATIO` / `AIM_MOVE_SPEED_RATIO`；新增 `aimFovRuntimeId`，用于**瞄准途中换枪时立刻重算 FOV**；死亡/读档一并将它复位 |
| T1 | `combat/ShotSpreadTest.java` | **新增**（8 用例） | 正交归一基（含正上/正下退化位形）、零散布逐位同向、单弹丸忽略散布、锥内含单位长度、首丸居中共、散布真的分开、确定性、零分配（结构 + 行为） |
| T2 | `combat/WeaponDataWiringTest.java` | **新增**（8 用例） | 手枪 vs SMG 的 ADS 目标角与移速倍率**行为对比**；瞄准途中换枪重算；`damageFor` 按 spec（合成枪 0.5/0.05）；`resolveShot` 读 `pelletCount`/`spreadRad`（方法体扫描）；`resolveShot` 经由 `damageFor`；`Player` 无 ADS 常量；**13 字段死数据审计** |
| T3 | `testutil/SourceScan.java` | **新增** | 可复用的源码扫描：状态机去注释（行/块/javadoc，且不被字符串字面量里的注释符号骗到）、`methodBody(source, signature)`、`allMainCodeExcept(...)` |
| T4 | `combat/CombatCoreTest.java` | 改 | 全部调用点补 `perUnit/floor` 参数（PRD 基线写成测试内字面量）；新增 `falloffCurveComesFromTheParametersNotFromAConstant`（霰弹口径 5% vs 手枪口径 52% 在同一距离给出不同伤害） |
| T5 | `combat/CombatControllerTest.java` | 改 | 期望值改为**从注册表中这把枪的数据**推出；删除对已删常量的断言 |
| T6 | `player/PlayerAimingTest.java` | 改（**含语义修正**） | 原「固定倍率 45/70」断言改写为「绝对目标 FOV」；新增 `aimingNeverWidensTheViewWhenTheBaseFovIsAlreadyNarrow`（`min(1, …)` 夹取） |
| T7 | `game/InfiniteReserveWiringTest.java` | 改（加固） | 私有去注释实现改为委托 `SourceScan.withoutComments`（原实现只按整行过滤，**行内块注释仍可满足扫描断言**） |
| T8 | `game/M2CombatSelfTest.java` | 改 | `checkAim` 新增「手持物自证（=手枪）」与「注册表 ADS 数据 45/0.60」两条；期望值改用 PRD 字面量；`checkFalloff` 新增「结算伤害 == `damageFor(手枪 spec, 实测距离)`」；SMG 段的「无每发内层循环」表述改为「弹丸循环恰好一次迭代」并重写分配量论证 |
| C5 | `render/ui/CjkFont.java` | 重烤 | 新增中文触发 `CjkFontTest`（仿 U+4EFF、剥 U+5265）→ 字模 1531 → **1533**，空白字形 0 |

---

## 4. 六字段 → 读者接线表（含取证方式）

| 字段 | 现在的唯一读者 | 取证等级 |
|---|---|---|
| `aimFovDeg` | `Player.fovScale()`（`min(1, …)`） | **行为**（手枪 45.0° vs SMG 48.0°，同值巧合被打破） |
| `aimMoveSpeedMult` | `Player.aimMoveSpeedMult()` | **行为**（实测步距比 0.60 vs 0.65，容差 5% < 两值差 7.7%） |
| `falloffPerUnit` | `CombatController.damageFor` → `DamageFalloff.multiplier` | 行为（合成枪 0.5）+ 方法体扫描 |
| `falloffFloor` | 同上 | 同上 |
| `pelletCount` | `CombatController.resolveShot` 的循环上界 | 方法体扫描 + `ShotSpreadTest` 对几何的行为取证（在册两把枪均为 1，**无法**行为区分） |
| `spreadRad` | `CombatController.resolveShot` → `ShotSpread.offset` | 同上（在册两把枪均为 0） |

> 依赖方向：`CombatController` 只经由 `damageFor` 结算；`resolveShot` 体内**不得**直接调 `DamageFalloff`（由 T2 的方法体扫描断言）。

---

## 5. 玩家可见行为变化（**必须**记入试玩与版本说明）

| # | 变化 | 依据 | 影响面 |
|---|---|---|---|
| B1 | **SMG 开镜：45° → 48°，移速 ×0.60 → ×0.65** | v2 §9.2 / §10 | 拿到 SMG 并 ADS 的玩家立刻可感。这是**修回文档承诺**，不是回退 |
| B2 | **改过 FOV 设置的玩家：ADS 落点变为绝对目标角**。基础 FOV 90 时，手枪 ADS 由 57.86° 变为 45.0° | v2 §5.2-8 / §9.1 明文 | 只有把 FOV 调离默认 70 的玩家可感；默认 70 下无变化 |
| B3 | 瞄准途中按 1/2 切枪，FOV 立刻按新枪重算（此前要松手再按一次右键） | 修复（`aimFovRuntimeId`） | 按住右键切枪可感 |

**未变化**（回归证据）：手枪 ADS 仍是 45.0° / ×0.60；两把枪的伤害、射程、弹匣、换弹、衰减曲线、单发一条射线、准星指向即命中 —— 均逐值不变（见 §7 的 m2 门禁 360 条全绿）。

---

## 6. 反向验证记录（v2 §14.8）

方式：往生产代码注入"看起来对、实际断开接线"的改动 → 跑相关测试 → 核对**恰好**目标用例变红 → 恢复 → 逐字节核对哈希。

| 轮 | 注入 | 期望失败集 | 实测 |
|---|---|---|---|
| R1 | `resolveShot`：`pelletCount`/`spreadRad` 改为**行内块注释**包住（行为等价），并把 `damageFor(spec,…)` 换成内联 `DamageFalloff.damage(…spec…)` | 2 条扫描用例 | **恰好 2 条红**（`resolveShotConsumesPelletCountAndSpreadRad`、`resolveShotRoutesDamageThroughDamageFor`）；其余含 `ShotSpreadTest` 8 条、`CombatControllerTest` 15 条**全绿** |
| R2 | `damageFor`：把 spec 的衰减值换成字面量 `0.9 / 0.20` | 1 条 | **恰好 1 条红**（`damageForReadsTheSpecsFalloffPairInsteadOfAHardcodedBaseline`）；`CombatControllerTest`/`CombatCoreTest` **全绿** —— 这正是"同值巧合"允许死数据存活的机制 |
| R3 | `Player`：`spec.aimFovDeg()` → `45.0`，`spec.aimMoveSpeedMult()` → `0.60`（行为对手枪等价） | 4 条 | **恰好 4 条红**（ADS 目标角、ADS 移速、换枪重算、`playerReadsAdsParametersFromTheHeldGunSpec`）；`PlayerAimingTest` 9 条**全绿**（手枪与写死值同值） |
| R4 | `Player.updateAiming`：判据退回 `if (want == aiming) return;` | 1 条 | **恰好 1 条红**（`switchingGunsStillAiming…`） |

恢复校验：`CombatController.java` = `3338797acf64499a…`、`Player.java` = `64bed60834c02f03…`（与注入前逐字节一致）；`src/main/java` 内 `TEMP_REVERSE_VERIFY` 残留 = **0**。

---

## 7. 机器侧证据

| 项 | 结果 |
|---|---|
| 全量单测 | **1047 / 0 / 0**（本次前 1029；新增 18 条 = `ShotSpreadTest` 8 + `WeaponDataWiringTest` 8 + `PlayerAimingTest` +1 + `CombatCoreTest` +1） |
| 冻结 jar | `tmp/selftest-jar/skyisland-frozen.jar`（5,753,553 B，比源码新：校验通过） |
| gate-m1 | exit=0，**58 PASS · / 0 FAIL ·**（不变） |
| gate-ui | exit=0，**150 PASS · / 0 FAIL ·**（不变） |
| gate-m2 | exit=0，**360 PASS · / 0 FAIL ·**（前 354，+6 行 = 新增 3 条断言 × 2 行） |
| m2 权威计数器 | `m2_selftest_passed=true`、`m2_selftest_scope=full`、`m2_selftest_assertions=**180**`（前 177）、`m2_selftest_failures=0`、`m2_combat_closure=true` |
| 字模 | 1533 字符，空白字形 0，溢出 0 |
| 证据目录 | `tmp/gate-runs/20261002-210815/` |

新增断言实测值（摘录）：

```
PASS · 瞄准时手持的是手枪 — 第 60 步手持=skyisland:pistol
PASS · 注册表里手枪的 ADS 数据 = 45° / ×0.60（v2 §10 武器表） — 实测 45.0° / ×0.60
PASS · 伤害等于 floor(8 × max(0.20, 0.9^(d−32)))（保底 1，PRD 字面口径） — 实测=2 期望=2（d=44.7000）
PASS · 结算伤害等于 CombatController.damageFor(手枪 spec, 实测距离) — 实测=2 期望=2
PASS · SMG 每发恰好一条射线（pelletCount=1 → 弹丸循环恰好一次迭代） — pelletCount=1
PASS · SMG 无散布采样（spreadRad=0 → 循环内不产生任何方向偏移） — spreadRad=0.0
```

启动/瞄准日志（新格式，供试玩归因）：

```
[玩家] 瞄准 开始（手持 skyisland:pistol）：FOV 45.0° / 基础 70.0°（ADS 目标 45.0°），移动速度 60%
[玩家] 瞄准 结束（手持 skyisland:pistol）：FOV 70.0° / 基础 70.0°（ADS 目标 70.0°），移动速度 60%
```

---

## 8. 边界与未做（诚实记录）

1. **未新增任何武器**。第 3 把枪（步枪）不在本次范围 —— v2 §18 STOP 规则要求主理人显式放行，且它需要第三套剪影几何（`ViewmodelGeometry.partsFor` 对未知键**静默回退手枪轮廓**）与独立图标，属独立工作项。
2. **`pelletCount` / `spreadRad` 已接线但无在册枪可做端到端区分**：两把枪都是 1 / 0，行为上与"写死单发无散布"不可区分。本次的举证等级是「纯函数几何行为测试 + 方法体扫描」，**不是**端到端差异化。真正的端到端区分要等霰弹枪（Backlog）。这一点已在 `ShotSpreadTest` 与报告里写明，不当作已验证。
3. **`Hitscan.resolve` 内部仍每条射线分配一个归一化方向向量**。它与弹丸数线性，属命中判定链而非本次引入的循环分配；若将来上多弹丸武器，那里是下一个优化点。
4. **`perf_gate_met` 绝对阈值仍不可靠**（登记待办，未在本次处理）。本次的性能主张限于"循环内零分配"这一结构事实。
5. SMG 的 48° / ×0.65 是 v2 §9.2 标注的**建议值**（"M3 人工试玩后可微调"）——本次只把它接上，未调参。

---

## 9. 连带更新

- `docs/testing/M3_WEAPON_S10_PLAYTEST.md`：§8 增补本轮接线修正的试玩口径（ADS 两把枪分别核对）。
- `tmp/` 证据档：`_rev1.log`…`_rev4.log`（反向验证四轮）、`_full_test2.log`、`gate_ps_out.txt`。
- Skill `skyisland-milestone-closure`：门禁期望值更新为 1047 / 58 / 150 / **360**（assertions **180**），§4.1 增补 `SourceScan` / `methodBody` 的用法与"行内块注释"这一新增陷阱。

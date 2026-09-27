> ⚠️ **本文件已作废（SUPERSEDED）。** 请以 `docs/design/WEAPON_SYSTEM_OPTIMIZATION_v2_FINAL.md`
> 为 M3 武器系统唯一实施基线。本文件的「M3 扩到 7 把枪」「提前实现散布」「狙击 aimZoom」等结论
> **全部撤销**；机枪 / 加特林 / 狙击枪仅为 Backlog，不进入 M3 承诺。
> 保留本文件仅作设计演进留痕，**不得据此实施**。
>
> 另：本文件 §2/§9 所述「移动不打断换弹」与现行口径一致（正确）；
> 但其 §11.1「4 把 → 7 把回写 PRD」的建议**已撤销**。

# 武器系统优化与设计文档（WEAPON-DOC-001）

| 项目 | 内容 |
|------|------|
| 文档类型 | 系统设计 / 架构优化（**纯文档，前瞻 M3**） |
| 项目 | SkyIsland（Java 21 + LWJGL 自研体素引擎，Maven fat-jar） |
| 当前版本 | `0.3.2-M2_2-UI-INVENTORY` |
| 作者 | 程基岩（engineering-lead） |
| 日期 | 2026-09-24 |
| 状态 | **设计草案（待主理人评审）**；本文件**只描述设计、不改任何 `src/` 代码、不 `git commit`** |
| 上游基线 | `docs/design/PRD_v0.3.2.md`（产品基线）、`docs/architecture/TECH_DESIGN_v0.1.md`、`docs/architecture/technical-constraints.md` |
| 关联边界 | `docs/M2_2_HANDOFF_PROMPT.md`（M2.2 明确**禁止实现第二把枪**；本文属 M3 前瞻，不涉及落地） |

---

## 0. 文档状态与纪律声明

1. **纯文档任务**：本文所有"实现""改动"均为**设计描述**。M2.2 里程碑边界（手交提示词第七段）明确禁止"第二把枪 / 合成 / 第二个怪物"等落地；本文严格遵守——**不修改、不新增任何 `src/` 文件，不提交**。
2. **代码侦察以代码为准**：以下设计基于实读源码（`GunSpec.java` / `GunState.java` / `CombatController.java` / `ItemRegistry.java` / `Player.java` / `PlayerIntent.java` / `InputMapper.java` / `Localization.java` / `HudRenderer.java` / `SkyIslandGame.java` 及相关测试）。本文给定的侦察结论与代码**一致**；唯一一处表述性差异见 §2 末注（"stats" 实为三个独立计数字段，非对象，无代码影响）。
3. **所有数值均为建议值**：第 7 章新增 3 把枪的数值一律标注"建议值，待平衡"，需 Alpha 试玩调优。
4. **PRD 偏离已显式登记**：第 11 章逐条记录对 PRD v0.3.2 的修订点（4 把 → 7 把、提前实现散布/全自动），供主理人回写 PRD。

---

## 1. 概述与目标

### 1.1 目标
把当前"**单枪（手枪）硬编码**"的武器系统，泛化为**数据驱动的多武器系统**，在 PRD 5.4.1 四把（手枪 / 冲锋枪 / 步枪 / 霰弹枪）基础上**净增**机枪 / 加特林 / 狙击枪，**共 7 把**可玩枪械，且状态层、换弹层、命中层对枪械种类零感知。

### 1.2 范围
- **保留** PRD 5.4.3 已落地的统一换弹规则（移动不打断、满匣无操作、部分填充、完成前不转移、换弹期间不开火）。
- **保留** M2.1 的"**无限备弹 + 有限弹匣**"产品口径（`GunState.INFINITE_RESERVE_DEFAULT = true`）。
- **泛化** `GunSpec`（加 `fireMode` / `pelletCount` / `spread` / `aimZoom` 等字段）、**去** `GunState` 的手枪硬编码、**数据驱动** `ItemRegistry` 注册。
- **提前**将 PRD 列为【后续迭代】的"弹道散布"与"全自动"拉入首版实现（决策 D，偏离见 §11）。

### 1.3 与 M2.2 边界的关系
M2.2 验收口径明确"**不做第二把枪**"。本文是 M3 Vertical Slice 的前瞻设计，与 M2.2 的执行边界**不冲突**：M2.2 只落地手枪 + UI/背包；M3 才按本文把武器系统扩展为 7 把。本文不要求 M2.2 做任何武器改动。

---

## 2. 现状与痛点

### 2.1 现状事实（已实读确认）
- **`GunSpec`**（`src/main/java/com/skyisland/item/GunSpec.java`）：`public record GunSpec(int damage, int magazineSize, double fireRate, int range, double reloadSeconds)`，含 5 项正值校验，`shotInterval() = 1.0 / fireRate`。**无** `fireMode` / `spread` / `pelletCount` / `aimZoom` 字段。
- **`GunState`**（`src/main/java/com/skyisland/combat/GunState.java`）：`INFINITE_RESERVE_DEFAULT = true`；构造器 `GunState(Item)` 与 `GunState(Item, boolean)`；工厂 `forPistol()`、`forPistolWithFiniteReserve()`；`tryFire()` 返回 `ShotOutcome{RELOADING,COOLDOWN,NO_AMMO,FIRED}`；`tryStartReload(Inventory)` 返回 `ReloadOutcome{STARTED,ALREADY_FULL,NO_RESERVE,ALREADY_RELOADING}`；`tick(dt, inv)`、`completeReload()`（无限：只补弹匣；有限：从背包扣）。
  - **手枪硬编码点**：`completeReload()`（行 313）与 `availableReserve()`（行 328）经 `reserveAmmo()`（行 331-333）**硬读 `ItemRegistry.PISTOL_AMMO_ID`**。有限后备口径只能消耗手枪弹。
- **`CombatController`**（`src/main/java/com/skyisland/combat/CombatController.java`）：`Map<Integer, GunState> gunStates` 以 `selectedStack().itemRuntimeId()` 为键；`gunFor(Player)` 按手持物惰性建 `GunState(item)`；`step()` 三步——① `tick`（换弹仅时间推进，移动不打断）→ ② 换弹请求（`intent.reloadPressed()`）→ ③ 开火（`intent.attackHeld()`）；`resolveShot()` 用 `Hitscan` + `DamageFalloff`，射线 = `range × 2.0`（`RAY_RANGE_MULTIPLIER`）。**状态层已按 runtimeId 分枪、对枪种零感知。**
- **`ItemRegistry`**（`src/main/java/com/skyisland/item/ItemRegistry.java`）：`bootstrap()` 后 `verify()` 冻结，`runtimeId == 列表下标`；`register()` 分配 `runtimeId = BY_RUNTIME_ID.size()`（**顺序敏感**）；常量 `PISTOL_AMMO_ID="skyisland:pistol_ammo"`、`PISTOL_ID="skyisland:pistol"`；手枪已注册（`register(PISTOL_ID, ItemKind.GUN, 1, new GunSpec(8,12,4.0,32,1.2))`）。SMG / rifle / shotgun 仅 PRD，**未进代码**。
- **弹药**：PRD 5.4.2 定义 `pistol_ammo` / `rifle_ammo` / `shell`；当前仅 `pistol_ammo` 注册。
- **瞄准**：`Player.updateAiming()`（`Player.java:461`）持枪 + 按住右键进入瞄准；`Player.fovScale()`（`:479`）返回 `AIM_FOV_RATIO = 45.0/70.0`（`:82`），移速在 `:583-584` 乘 `AIM_MOVE_SPEED_RATIO = 0.60`（`:72`）。**FOV/移速均为全局常量，无按枪区分。**

### 2.2 痛点
1. **`GunSpec` 缺字段**：无法表达开火模式、弹丸数、散布、开镜缩放——加一把新枪就要在 `CombatController` / `Player` 里加分支。
2. **`GunState` 手枪硬编码**：`reserveAmmo()` 硬读 `PISTOL_AMMO_ID`，有限后备口径只能消耗手枪弹；`forPistol()` / `forPistolWithFiniteReserve()` 把"手枪"焊死在战斗状态类里（仅 `CombatCoreTest` 引用，见 §10）。
3. **`ItemRegistry` 冻结顺序敏感**：`runtimeId = 下标`，**不能插队重排**；新武器只能追加在尾部，且必须保证既有 `pistol` 的 `runtimeId` 不变（存档/测试依赖）。
4. **仅手枪落地**：PRD 5.4.1 四把只实现一把，其余三把（SMG/rifle/shotgun）与新增三把（mg/gatling/sniper）都还没数据入口。
5. **开火模式语义混淆（重要）**：当前 `step()` 对所有枪都用 `intent.attackHeld()` 开火——**单发与全自动在代码里没有区别**（按住左键即按 `shotInterval` 连发）。PRD 5.4.3 要求"手枪单击=单发"，泛化必须显式区分 `SINGLE`（按下沿）/ `AUTO`（按电平）/ `BURST`。
6. **瞄准无按枪缩放**：狙击枪需要的"比通用 70→45 更窄的缩放 + 更低移速"目前没有数据入口。

> **侦察与代码一致性附注**：任务书侦察将 `GunState` 字段概括为"含 `stats`"；实读为三个独立 `int` 字段（`shotsFired` / `dryFires` / `reloadsCompleted`），并非名为 `stats` 的对象。属表述性差异，对本文设计无影响。

---

## 3. `GunSpec` 泛化设计

### 3.1 字段集合

**保留（现状 5 项）**：`damage`、`magazineSize`、`fireRate`、`range`、`reloadSeconds`。

**必含新增（决策 D + §4 弹药数据驱动）**：

| 字段 | 类型 | 默认 | 含义 |
|------|------|------|------|
| `fireMode` | `enum FireMode { SINGLE, AUTO, BURST }` | `SINGLE` | 开火模式（§6 驱动 `step()`） |
| `pelletCount` | `int` | `1` | 单次击发弹丸数（霰弹=6，其余=1） |
| `spread` | `double`（弧度） | `0.0` | 弹道散布半角（0 = 准星指向即命中） |
| `aimZoom` | `double`（目标 FOV，度） | `45.0` | 瞄准时目标视场角；默认=通用瞄准 45 |
| `ammoId` | `String`（stable ID，可空） | `null` | 弹药类型元数据：仅用于 HUD 标签 + 有限后备口径定位（§4/§8） |

**建议配套（推荐，非强制；用于完全消除硬编码，见 §11）**：

| 字段 | 类型 | 默认 | 含义 |
|------|------|------|------|
| `aimMoveSpeedMult` | `double` | `0.60` | 瞄准时移速倍率；默认=通用 0.60；狙击覆盖为更低（如 0.30） |
| `falloffPerUnit` | `double` | `0.90` | 距离衰减每超 1 格乘子；霰弹=0.80 |
| `falloffFloor` | `double` | `0.20` | 距离衰减下限；霰弹=0.15 |

> **为何 `ammoId` 必含、`aimMoveSpeedMult`/`falloff*` 仅建议**：`ammoId` 是 §4"弹药改由 spec 字段驱动"的硬要求；`aimMoveSpeedMult` 是决策 B"狙击进一步降低移速"的落地所需；`falloff*` 是把 PRD 5.4.3 霰弹衰减（0.8/15%）从 `DamageFalloff` 的硬编码双模提升为纯数据驱动的配套。三者均不改变"必含 4 字段"的口径，仅在落地时一并补齐以免留下新的硬编码分支。

### 3.2 校验规则（加入 compact 构造器）
- `damage > 0`、`magazineSize > 0`、`fireRate > 0`、`range > 0`、`reloadSeconds > 0`（现状保留）。
- `spread >= 0.0`（新增；负散布无意义，立即抛 `IllegalArgumentException`）。
- `pelletCount >= 1`（新增；<1 抛异常）。
- `aimZoom > 0.0`（新增；且实现层建议 `aimZoom <= baseFov`，否则开镜反而拉远，由 `Player.fovScale()` 保证 scale ≤ 1）。
- `aimMoveSpeedMult` 建议 `(0.0, 1.0]`；`falloffPerUnit` 建议 `(0.0, 1.0]`；`falloffFloor` 建议 `[0.0, 1.0)`。
- `ammoId`：可空；非空时须为合法 stable ID 字面量（非空字符串，命名遵循 `skyisland:<name>`）。

### 3.3 兼容性约束
- 手枪 `aimZoom` 必须 = `45.0`、`aimMoveSpeedMult` 必须 = `0.60`、`falloffPerUnit/falloffFloor` 必须 = `0.90/0.20`，使现有 `M2CombatSelfTest`（断言 `fovScale == AIM_FOV_RATIO`、`cameraFov == baseFov*AIM_FOV_RATIO`、移速比 `== AIM_MOVE_SPEED_RATIO`）**对 pistol 继续保持绿**（§6 详述）。

---

## 4. `GunState` 去手枪硬编码

### 4.1 改动点
1. **删除** `forPistol()`（`:156`）与 `forPistolWithFiniteReserve()`（`:167`）。有限后备的可测性改由既有双参构造器 `GunState(Item, boolean)` 提供（测试改用 `new GunState(ItemRegistry.pistol(), false)`；§10）。
2. **删除** `reserveAmmo(Inventory)`（`:331-333`）中对 `ItemRegistry.PISTOL_AMMO_ID` 的硬读。
3. **改写** `availableReserve()`（`:327-329`）与 `completeReload()` 有限分支（`:310-316`）：后备定位改为读取 **`gun.spec().ammoId()`**（或 `gun.ammoId()` 透传字段），即"哪把枪吃哪种弹药"由 spec 决定，而非写死手枪弹。
4. **保留** `INFINITE_RESERVE_DEFAULT = true` 与"无限备弹 + 有限弹匣"语义：`completeReload()` 无限分支**只读不写背包**（现状 `:300-308`），不变。
5. **保留** 有限后备那条代码路径（§4.2 理由），仅把"消耗哪种弹药"数据化。

### 4.2 为何保留有限后备路径
当前类注释已说明：删掉有限分支会让 PRD 5.4.3 规则②③（"后备>0 才允许换 / 部分填充 `load=min(need,reserve)`"）退化为死代码。泛化后这条规则对**任意枪**都必须可执行、可断言——把 `ammoId` 从手枪硬编码改为 spec 字段，反而让有限后备真正变成"枪无关"，测试应覆盖**非手枪**的一把枪以证明规则已数据驱动（§10）。

### 4.3 不变项
- 换弹四步时序（`tick` → `tryStartReload` → `completeReload`）、移动不打断、换弹期间不开火、满匣无操作——**全部不变**，复用 PRD 5.4.3（§9）。
- `ShotOutcome` / `ReloadOutcome` 枚举不变。

---

## 5. `ItemRegistry` 数据驱动注册

### 5.1 不变式（必须守住）
- `runtimeId == 列表下标`（`:125-139` `verify()` 断言）。
- `register()` 顺序敏感（`runtimeId = BY_RUNTIME_ID.size()`）。
- **禁止插队 / 重排**：任何重排都会让既有存档（按 stable ID 落盘，见 PRD 12.3，安全）与既有 `runtimeId` 依赖测试失效。
- 冻结后运行期不可追加（`register()` 在 `bootstrapped=true` 后抛异常，`:106-108`）。

### 5.2 稳定 ID 命名约定
- 枪械：`skyisland:<name>`，`<name>` 全小写无空格：`pistol` / `smg` / `rifle` / `shotgun` / `machinegun` / `gatling` / `sniper`。
- 弹药（仅元数据，见 §8）：复用既有 `pistol_ammo` / `rifle_ammo` / `shell`；新增三把枪建议沿用 `rifle_ammo` 标签或新增仅本地化用的 `skyisland:mg_ammo` / `skyisland:gatling_ammo` / `skyisland:sniper_ammo`（均**不进注册表**，仅作 HUD 标签来源）。

### 5.3 7 把武器注册顺序草案（runtimeId 稳定）
当前 `bootstrap()` 尾部顺序为：`empty(0)` → 方块物品(1..N) → `coal` → `pistol_ammo` → `pistol`。**所有新枪必须追加在 `pistol` 之后**，且彼此相对顺序一旦确定不得再改。

| 追加序 | stable ID | 旧/新 | 说明 |
|--------|-----------|-------|------|
| 既有 | `skyisland:pistol` | 旧（已落地） | runtimeId 固定不变 |
| 1 | `skyisland:smg` | 新（PRD Alpha） | 紧随 pistol 之后 |
| 2 | `skyisland:rifle` | 新（PRD Alpha） | — |
| 3 | `skyisland:shotgun` | 新（PRD Alpha） | — |
| 4 | `skyisland:machinegun` | 净增 | — |
| 5 | `skyisland:gatling` | 净增 | — |
| 6 | `skyisland:sniper` | 净增 | — |

> 相对顺序（smg/rifle/shotgun 之间、machinegun/gatling/sniper 之间）是自由但**一次性**的抉择；落地后以"按序追加 + runtimeId 锁定测试"（§10）冻结，后续只许在尾部继续追加，不许在中间插入或交换。

### 5.4 弹药适配
- 每把枪在 `register()` 时传入带 `ammoId` 的 `GunSpec`；`ammoId` 指向 §5.2 的弹药 stable ID（无需该弹药已注册为 `Item`，见 §8）。
- 现有 `pistol_ammo` 继续注册（被开局物资、存档、图标测试引用，§8）；新枪弹药**不新增 `Item` 注册**，仅用 stable ID 字符串驱动 HUD 本地化。

---

## 6. `CombatController` 接入点

状态层（`gunStates` 按 runtimeId 分枪）已武器无关，**核心改动有限且局部**。

### 6.1 `fireMode` 如何驱动 `step()` 的开火
- **现状缺陷**：`step()` 用 `intent.attackHeld()`（`:214`）对所有枪开火，单发/全自动无区分。
- **改造**：
  - `SINGLE`：`step()` 改为消费 **`intent.attackPressed()`（按下沿）**——每按一次左键击发一发，仍受 `fireCooldown` 节流（快速连点超 `shotInterval` 的部分被 `COOLDOWN` 吞掉，符合半自动手感）。
  - `AUTO`：维持 `intent.attackHeld()`（电平），按住即按 `shotInterval` 连发（现状行为，对应 PRD"冲锋枪按住连发"）。
  - `BURST`：消费 `attackPressed()` 沿，置 `burstRemaining = burstCount`（建议默认 3，需给 `GunState` 加 `burstRemaining` 字段），在 `tryFire()` 冷却归零时连续吐弹直至 `burstRemaining==0` 或松开；**当前 7 把均不分配 BURST**，枚举 + 最小接线预留，启用时再补 `burstCount` 字段。
- **输入层前置改动（必需）**：`PlayerIntent` 当前**只有** `attackHeld()`（电平）与 `usePressed()`（右键沿），**没有左键沿**。需在 `PlayerIntent` 加 `attackPressed()`，并在 `InputMapper.java:93-94` 处用既有 `actionPressed(in, bindings, Action.PRIMARY_ACTION)`（与 `usePressed` 同机制）填充。这是泛化的**硬前置**，否则 `SINGLE`/`BURST` 无法实现。

### 6.2 `spread` / `pelletCount` 如何注入 `resolveShot()`
- **现状**：`resolveShot()`（`:238-275`）方向 = `player.camera().forward().normalize()`，**单射线**，无随机。
- **改造**：
  - 引入**可注入的随机数源**（如 `java.util.Random`，`CombatController` 持有一个可被测试替换的种子化实例），保证可测。
  - 对 `i in [0, pelletCount)`：方向 = `forward` 经**随机锥扰动**（半角 = `spread` 弧度）后的单位向量；`spread==0` 时方向严格等于 `forward`（测试可断言"零散布=不偏"）。
  - 每个弹丸独立做 `Hitscan.resolve(...)` + `DamageFalloff`（用该枪 `falloffPerUnit/falloffFloor`，§3）；多弹丸伤害按命中聚合（霰弹近距多丸叠伤）。
  - 曳光/命中事件：`onShotFired` 仍每击发一次（终点取最远命中或最大射程）；`onEntityHit` 按实际命中弹丸逐条回报。
- **性能上限**：射速由 `shotInterval` 节流，单发弹丸数 ≤ `pelletCount`（霰弹 6），故每逻辑步射线数 ≤ 6，与现状同量级；高射速（加特林 20/s）因冷却分散到多帧，不会单帧爆量（§11 性能注）。

### 6.3 `aimZoom` 如何覆盖瞄准 FOV / 移速
- **现状**：`Player.fovScale()`（`:479`）返回 `aiming ? AIM_FOV_RATIO : 1.0`（`AIM_FOV_RATIO = 45.0/70.0`，`:82`）；移速在 `:583-584` 乘 `AIM_MOVE_SPEED_RATIO = 0.60`（`:72`）。
- **改造**（瞄准触发逻辑 `updateAiming()` `:461` 已要求"手持物是枪"，故手持 `spec` 可达）：
  - `fovScale()` 改为 `aiming ? (heldGun.spec().aimZoom() / baseFovDeg()) : 1.0`。`pistol.aimZoom()==45` ⇒ 仍得 `45/70`，**现有 `M2CombatSelfTest` 对 pistol 不变绿**（§3.3）。
  - 移速乘子改为 `heldGun.spec().aimMoveSpeedMult()`（默认 0.60）；狙击 `aimMoveSpeedMult=0.30` ⇒ 开镜移速降至 30%。
  - `AIM_FOV_RATIO` / `AIM_MOVE_SPEED_RATIO` 保留为"无枪/兜底"常量，或作为 `aimZoom=45 / aimMoveSpeedMult=0.60` 的文档别名。
- **`CombatController` 本身不碰 FOV**：瞄准 FOV / 移速在 `Player` 层处理，`CombatController` 只消费战斗语义，依赖方向不变（与现有"逻辑/渲染解耦"架构一致，PRD 14.3）。

---

## 7. 7 把枪械 Spec 表

> 下表 PRD 四把数值取自 PRD 5.4.1 / 5.4.2 / 5.4.3（已落地或 Alpha 待落地）；**新增三把（machinegun / gatling / sniper）全部为"建议值，待平衡"**。所有非 PRD 数值均标 ★。

| 枪械 | stable ID | fireMode | damage | mag | fireRate(/s) | range | reload(s) | pellet | spread(rad) | aimZoom | aimMoveSpd | ammoId | falloff | 来源/定位 |
|------|-----------|----------|--------|-----|--------------|-------|-----------|--------|-------------|---------|------------|--------|---------|-----------|
| 手枪 pistol | `skyisland:pistol` | SINGLE | 8 | 12 | 4.0 | 32 | 1.2 | 1 | 0 | 45 | 0.60 | `pistol_ammo` | 0.9/0.20 | PRD（已落地）·平衡基准/开局 |
| 冲锋枪 smg | `skyisland:smg` | AUTO | 5 | 24 | 10.0 | 24 | 1.5 | 1 | 0.015★ | 45 | 0.60 | `pistol_ammo` | 0.9/0.20 | PRD/Alpha·机动连发 |
| 步枪 rifle | `skyisland:rifle` | AUTO | 14 | 10 | 2.0 | 48 | 2.0 | 1 | 0 | 45 | 0.60 | `rifle_ammo` | 0.9/0.20 | PRD/Alpha·精确中距 |
| 霰弹枪 shotgun | `skyisland:shotgun` | SINGLE | 6/弹丸 | 6 | 1.25 | 12 | 2.5 | 6 | 0.08★ | 45 | 0.60 | `shell` | 0.8/0.15 | PRD/Alpha·近距爆发 |
| 机枪 machinegun | `skyisland:machinegun` | AUTO | 4★ | 40★ | 12.0★ | 32 | 3.0★ | 1 | 0.02★ | 45 | 0.60 | `rifle_ammo`★ | 0.9/0.20 | **新增**·压制 |
| 加特林 gatling | `skyisland:gatling` | AUTO | 3★ | 100★ | 20.0★ | 36 | 4.0★ | 1 | 0.03★ | 45 | 0.60 | `rifle_ammo`★ | 0.9/0.20 | **新增**·极限压制 |
| 狙击枪 sniper | `skyisland:sniper` | SINGLE | 40★ | 5★ | 0.8★ | 96★ | 3.0★ | 1 | 0 | 30★ | 0.30★ | `rifle_ammo`★ | 0.9/0.20 | **新增**·精确远程 |

### 7.1 设计理由：压制（mg / gatling） vs 精确（sniper / rifle）
- **压制组（machinegun / gatling）**：高射速（12 / 20）、大弹匣（40 / 100）、每发低伤（4 / 3）、轻散布（0.02 / 0.03 rad ≈ 1.1°/1.7°）、长换弹（3.0 / 4.0）。定位"弹雨覆盖、持续压制"，但单发弱、换弹空窗大、散布使其远距衰减。gatling 比 mg 更极端（更高射速/弹匣、更长换弹）。
- **精确组（sniper / rifle）**：高单发（40 / 14）、低射速（0.8 / 2.0）、小弹匣（5 / 10）、无/低散布、远射程（96 / 48）、强开镜（sniper 30° + 0.30 移速）。定位"中/远距点杀"。sniper 40 伤可一枪带走 MVP/Alpha 绝大多数怪（近战 20 / 冲锋 14 / 枪手 16 / 大型 40），以高换弹 + 小弹匣 + 慢射速制衡（§11 平衡风险）。
- **手枪**：PRD 基准，单击单发、无散布，平衡支点。
- **smg**：机动全自动、中射程、24 弹匣，填补"中距持续输出"。
- **shotgun**：6 弹丸近距爆发（满命中 ≈36），射程仅 12，散布 0.08 rad ≈ 4.6° 锥，远距快速衰减（0.8/0.15），适用面窄但近距致命。

### 7.2 数值边界检查（建议值亦须满足 §3.2 校验）
全部 `damage>0`、`mag>0`、`fireRate>0`、`range>0`、`reload>0`、`spread>=0`、`pellet>=1`、`aimZoom>0`、`aimZoom<=baseFov(70)`、`ammoId` 合法。DPS 粗估：pistol 32 / smg 50 / rifle 28 / shotgun 45(近) / mg 48 / gatling 60 / sniper 32(慢)。**均待 Alpha 试玩调优**。

---

## 8. 无限弹模型与弹药系统

### 8.1 无限备弹语义（决策 B，沿用现状）
- `INFINITE_RESERVE_DEFAULT = true`：换弹只补弹匣，不碰背包（`completeReload()` 无限分支只读不写）。
- 因此 `pistol_ammo` / `rifle_ammo` / `shell` **不被消耗**；弹药类型退化为**纯元数据**。

### 8.2 弹药类型仅用于 HUD 标签
- `ammoId`（§3）只是 stable ID 字符串，经 `Localization.displayName(id)` 取中文标签（如 `pistol_ammo → 手枪弹`）。
- **HUD 改造**：`SkyIslandGame.java:2468`（`hud.reserveAmmo = player.inventory().countOfItem(ItemRegistry.PISTOL_AMMO_ID)`）与 `HudRenderer.java:330` 必须改为读取**手持枪的 `ammoId`**；无限口径下 HUD 显示 `mag / ∞`（现状 `reserveInfinite()` 已支持），有限口径下显示 `mag / 后备数`。

### 8.3 弹药物品保留还是移除？
- **既有 `pistol_ammo`（`Item`）保留**：被开局物资（`SkyIslandGame.java:1019`）、`SaveManagerTest`、`ItemRegistryTest`、`ItemIconTest`、`PlayerAimingTest`、`AudioFeedbackWiringTest` 引用；移除会破坏测试与旧档。它继续注册，但在无限口径下仅是"展示用/不被消耗"的物品（开局给的 24 发永不递减——属已知展示性冗余，见 §11）。
- **新增三把枪的弹药不注册为 `Item`**：因无限口径不消耗、无需进背包/存档，仅用 stable ID 字符串驱动 HUD 本地化（§5.2）。避免为展示标签膨胀注册表与 `runtimeId`。
- **结论**：保留 `pistol_ammo`；`rifle_ammo` / `shell` 视 Alpha 落地需要决定是否注册（当前代码未注册）；mg/gatling/sniper 弹药仅作本地化字符串，不注册。

---

## 9. 复用 PRD 5.4.3 统一换弹规则（不重新造轮子）

`GunState` 的换弹逻辑对 7 把枪**完全一致**，逐条核对 PRD 5.4.3：

| PRD 5.4.3 规则 | 适用全部 7 把？ | 落点 |
|----------------|----------------|------|
| ① 弹匣已满 → 按 R 无操作（`ALREADY_FULL`） | ✅ | `tryStartReload` `:262-263` |
| ② 弹匣未满且后备>0 → 允许换弹 | ✅（无限口径下"后备>0"恒真，`NO_RESERVE` 不可达） | `:265-266` |
| ③ 后备不足 → 部分填充 `load=min(need,reserve)` | ✅（无限口径下 `load=need`，恒满补） | `completeReload` `:311-312` |
| ④ 完成前不提前转移弹药 | ✅ | `tick` 仅计时、`completeReload` 一次性转移 `:291-317` |
| 换弹与移动：移动不打断换弹 | ✅ | `tick(dt,...)` 无 `moving` 参数 `:283` |
| 换弹期间不得开火 | ✅ | `tryFire()` `RELOADING` `:233-234` |
| 满弹匣换弹（v0.3.2 废止） | ✅ 已废止 | 无分支 |
| 换弹时长按枪（`reloadSeconds`） | ✅ 各枪不同 | §7 表 |

**结论**：换弹是已验证、武器无关的资产，**7 把枪直接复用，零重写**。唯一需随泛化迁移的是有限分支的"消耗哪种弹药"数据化（§4.1-3），不改变上述任何规则。

---

## 10. 迁移与门禁

### 10.1 零死代码要求
- 删除 `GunState.forPistol()` / `forPistolWithFiniteReserve()` / `reserveAmmo()` 后，全仓 `grep forPistol / forPistolWithFiniteReserve / reserveAmmo`（`src/**`）必须**零残留引用**。
- 迁移 `CombatCoreTest.java` 中的调用点（共约 11 处 `forPistol()` / 2 处 `forPistolWithFiniteReserve()`）：改为 `new GunState(ItemRegistry.pistol())` 与 `new GunState(ItemRegistry.pistol(), false)`；并**新增一条"非手枪有限后备"测试**，证明规则②③已数据驱动（不只手枪）。

### 10.2 反向验证纪律（沿用项目铁律，见 M2.2 手交 §四）
- 每条新断言必须做**反向验证**：注入破坏 → 确认精确变红并可读归因 → 恢复 → 全绿；全仓 `grep TEMP_REVERSE_VERIFY` 无残留。
- **"无断言的行为不算通过"**：任何新行为（spread 零偏、aimZoom 覆盖、fireMode 分支、ammoId 驱动）都须有断言；技术文档里的预警 ≠ 登记。

### 10.3 中文串 → 重烤 CJK 字模（沿用 M2.2 手交 §一.7）
- 新增中文显示名：`冲锋枪`/`步枪`/`霰弹枪`/`机枪`/`加特林`/`狙击枪` + 弹药 `步枪弹`/`霰弹`/`机枪弹`★/`加特林弹`★/`狙击弹`★（★为仅本地化用）须写入 `Localization.DISPLAY_NAMES`（`Localization.java:275-276` 同款）。
- `CjkFontTest` 会扫全部源码（含注释）并断言字模覆盖；新增中文会使其变红——**预期内**。**绝不允许靠删中文"修"它**；统一跑 `D:/software/jdk-25/bin/java.exe tools/fontgen/GenCjkFont.java .` 重烤（1484→约 1510+ 字符）。

### 10.4 runtimeId 稳定性回归
- 新增"注册顺序锁定"测试：断言 `pistol` 的 `runtimeId` 与 M2.2 一致且 `ItemRegistry.size()` 增量 = 新增枪数；断言 `BY_RUNTIME_ID` 下标 == `runtimeId`（复用 `verify()` 思路）。保证"只追加、不插队"被强制。

### 10.5 其他门禁
- `GunSpec` 校验异常路径（负 `spread` / `pelletCount<1` / `aimZoom<=0`）须有断言。
- 现有 `M2CombatSelfTest` 瞄准三断言（FOV/scale/移速）对 pistol 必须保持绿（§3.3 / §6.3 兼容性约束）。
- `CombatControllerTest` 中无限口径"后备永为 24"与有限口径"消耗手枪弹"的既有断言须保持绿（§4.1 改写后由 `ammoId` 还原为 `pistol_ammo`）。

---

## 11. 风险与缓解 / 已知偏离

### 11.1 PRD 修订点（须回写 PRD v0.3.2）
- **枪械数量 4 → 7**：PRD 5.4.1（4 把）、§8 内容量清单（"枪械 MVP1/Alpha4"）、§10.2（"Alpha 4 把枪"）、M4 通过标准 #3（"4 把枪伤害与 5.4.1 一致"）均需修订为 7 把。
- **射击方式**：决策 D 将步枪/SMG 也定为 AUTO；PRD 5.4.3 原仅明确"SMG 按住连发"。属扩展，需在 PRD 注明。

### 11.2 已知偏离：提前实现"弹道散布"（决策 D vs PRD）
- **偏离事实**：PRD 5.4.3 第 535 行将"弹道散布"列入【后续迭代】，第 544 行设计说明明确"首版明确**不做**后坐力、弹道散布与配件改装……全部枪械的命中精度在首版为「准星指向即命中」，以保证手感可验证"。
- **偏离理由（决策 D，主理人已锁定）**：为让 mg/gatling 的"压制"手感与狙击的"精确"形成差异，需在首版引入 `spread`。代价是放弃了"准星指向即命中"这一"可验证"保证——但 `spread==0`（手枪/步枪/狙击）仍维持零散布、零偏，故"可验证"仅对带散布枪种弱化。
- **缓解**：spread 默认 0、可种子化 RNG（§6.2），保证"零散布=不偏"可断言；带散布枪种通过"命中分布随距离变宽"的对比测试举证，而非依赖纯几何确定性。

### 11.3 平衡性风险
- **狙击枪 40 伤 + 射程 96 + 无散布**：可一枪带走大型怪（40 HP），远距统治力强。制衡靠高换弹(3.0)/小弹匣(5)/慢射速(0.8)，仍**建议 Alpha 试玩调优**（降低 damage 或加 `aimZoom` 开镜前禁止开火等）。
- **加特林 20/s × 100 弹匣**：持续 DPS 60，空窗仅换弹 4.0s。需确认不会让近战怪失去威胁；可考虑 spin-up（开火延迟）作为后续迭代。
- **霰弹 6×6 近距**：满命中 36，近距过强（PRD 已以"射程仅 12"限制），需实测。

### 11.4 性能风险
- 高射速 + 多弹丸增加每步射线数（霰弹 6、gatling 20/s 但分散到帧）。须纳入 M5/里程碑性能门禁（PRD 12.5，集显 P95 ≤ 16.7ms）；`resolveShot` 的 `Hitscan` 已是热路径，散布采样须零分配（复用 `Vector3d` 实例）。

### 11.5 展示性冗余
- 无限口径下开局 24 发 `pistol_ammo` 永不递减，属"有物品但无意义"。建议：保留（低风险、保兼容）或在 PRD 5.7.1 标注其为"展示/情怀"项。不在本文范围内强制处理。

---

## 12. 实施里程碑切片（Story / PR 建议）

> 全部为 **M3 及之后** 的落地切片；M2.2 不执行其中任何一项。

| # | Story / PR | 内容 | 门禁要点 | 依赖 |
|---|-----------|------|----------|------|
| 1 | 泛化 `GunSpec` + `GunState`（不加新枪） | 加 §3 字段；删 `forPistol*`/`reserveAmmo` 硬读；有限后备改读 `spec.ammoId`；pistol 保持可玩 | 手枪全绿、零死代码 grep、`GunSpec` 校验断言、有限后备非手枪测试 | — |
| 2 | `ItemRegistry` 数据驱动 | `ammoId` 随 spec 注册；准备"尾部追加"路径；加 runtimeId 锁定测试 | runtimeId 稳定性回归、pistol runtimeId 不变 | 1 |
| 3 | 加新增 3 把 + 落地 SMG/rifle/shotgun | 按 §5.3 顺序注册 6 把；填 §7 数值（建议值）；`Localization` 加中文名 + CJK 重烤；HUD 改读手持枪 `ammoId` | 7 把 spec 校验通过、HUD 标签正确、CJK 测试绿 | 2 |
| 4 | `spread` + `aimZoom` 接线 | `resolveShot` 加可种子 RNG + 多弹丸；`Player.fovScale()`/移速读 `spec.aimZoom`/`aimMoveSpeedMult`；`PlayerIntent` 加 `attackPressed()` + `InputMapper` 填充；`step()` 按 `fireMode` 分支（SINGLE/BURST 用沿、AUTO 用电平） | 零散布不偏断言、aimZoom 覆盖断言、fireMode 三态断言、M2CombatSelfTest 对 pistol 仍绿 | 3 |
| 5 | 门禁回归 | 反向验证全部新断言；runtimeId 锁定；性能采样（高射速 + 多弹丸）纳入门禁；PRD 5.4.1/§8/M4 回写 7 把 | 三档门禁全绿、`grep TEMP_REVERSE_VERIFY` 无残留、"无断言行为不算通过" | 4 |

---

*本文为 WEAPON-DOC-001 设计草案，由工程负责人程基岩执笔。所有代码级结论已实读 `src/` 核实；数值为建议值待平衡；对 PRD v0.3.2 的偏离与修订点见第 11 章，待主理人评审后回写 PRD。本文不含任何代码改动与提交。*

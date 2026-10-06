# M3 武器线追加交付：步枪 + 材料链 + 合成逻辑（数据层）

- 交付日期：2026-10-02
- 版本：`0.3.2-M2_2-UI-INVENTORY`（未改）
- 授权依据：`docs/design/WEAPON_SYSTEM_OPTIMIZATION_v2_FINAL.md`（下称 v2）**§18 的"显式放行"通道**
- 范围裁定：主理人选择 **"数据层 + 合成逻辑"** —— 注册物品/方块 + 实现配方数据 + 可单测的纯合成逻辑；
  **不做合成界面，不做矿石生成**
- 与上一轮的关系：承接 `M3_WEAPON_WIRING_REPORT.md`（补完 `GunSpec` 6 个死字段接线）。
  本轮是**内容追加**，不是口径修订。

---

## 0. 一句话结论

第 3 把枪（步枪）连同它的整条材料链已落地并**可被机器验证**：
新增 2 种矿石方块、5 种材料、1 种弹药、1 把枪、10 条配方、1 套纯合成逻辑；
开局装备同步扩充（步枪 + 步枪弹 + 材料包），配方链有端到端单测证明"从原材料真能打出步枪"。
**单测 1080/0/0；三门禁 58 / 150 / 386 行 `PASS ·`，0 `FAIL ·`；m2 断言 193、失败 0、`combat_closure = true`。**

⚠️ 但本节交付**同时引入了 4 个"未冻结数值"与 1 处"显式临时偏离"**，见 §5 / §6 ——
按本项目治理方式，这些需要主理人明确裁定后才算闭合，**不要把它们当成已完成的设计**。

---

## 1. 授权依据与两处诚实偏离

### 1.1 为什么这一轮是"被允许的"

v2 §18 的 STOP 规则把 `rifle` 列在禁止清单里，但结尾有一句**放行条款**：

> 除非主理人后续显式放行。

本轮即在该条款下执行：主理人先后给出
「只加步枪（最小步）」→「加上步枪，连材料一起补上」→ 范围 =「数据层 + 合成逻辑」。
因此**本轮不构成偷跑**；但它是一次**显式例外**，必须在报告里留下名字。

v2 §6.2 的另一条规则在本轮被真正执行了：

> 以后真正落地 rifle 时，`skyisland:rifle_ammo` 必须注册为 Item，再新增相应 Recipe，**禁止只添加字符串标签**。

本轮把这句话变成了**启动期异常**（`RecipeRegistry.verify()` 的悬空引用检查），不只是照着做一遍。

### 1.2 偏离一：步枪在 PRD 里是【Alpha 必须】，不是【MVP 必须】

`PRD_v0.3.2 §5.4.1` 的枪械清单逐行写着：

| 枪械 | 稳定 ID | 伤害 | 弹匣 | 射速 | 有效射程 | 优先级 |
|---|---|---|---|---|---|---|
| 步枪 | `skyisland:rifle` | 14 | 10 | 2.0 | 48 | **【Alpha 必须】** |

**也就是说：把步枪放进"开局装备"已经越过了 PRD 的 MVP 边界。**
这不等于做错了 —— 主理人有权前移 —— 但它是一件**需要被登记的事**，
因为它和 SMG 进开局装备是同一类操作（M3 时 SMG 也是【Alpha 必须】却进了开局装备），
而 PRD 正文并没有随之更新。

**建议**：主理人在 PRD §1.1 修订记录里补一行，或明确"开局装备 = M3 试玩装备，不等于 MVP 交付边界"。
本轮**未自行改 PRD**（改 PRD 需要单独立项，见 §11 提交分组）。

### 1.3 偏离二：材料包直接发放（见 §6）

---

## 2. 本轮实际交付清单

### 2.1 方块层（+2）

| 方块 | 稳定 ID | 硬度 | 掉落 | 说明 |
|---|---|---|---|---|
| 铜矿石 | `skyisland:copper_ore` | 3.5 | 自身 | 铜锭（R04）的唯一来源 |
| 晶体矿石 | `skyisland:crystal_ore` | 8.0 | `skyisland:crystal` ×1 | 晶体的唯一来源；R16 步枪直接材料 |

- 只**尾部追加**在 `wooden_door` 之后（`BlockRegistry` 的注册顺序 = 运行时 ID 顺序）。
- 新增公开常量 `MVP_CORE_PLAYER_BLOCK_COUNT = 13` / `ALPHA_ORE_BLOCK_COUNT = 2`，
  `playerBlockCount()` 的 javadoc 重写为"13 + 2"的显式算式 ——
  否则"方块数从 13 变成 15"看起来像 MVP 方块表被悄悄改了。
- **方块总数 15 → 17**（13 玩家核心 + 2 Alpha 矿石 + `resource_core` + `air`）。

### 2.2 物品层（+7）

全部**尾部追加**。**新物品占据 `runtimeId` 21..27**：

| 物品 | 稳定 ID | 类型 | 堆叠 | runtimeId |
|---|---|---|---|---|
| 木棍 | `skyisland:stick` | MATERIAL | 64 | 21 |
| 铁锭 | `skyisland:iron_ingot` | MATERIAL | 64 | 22 |
| 铜锭 | `skyisland:copper_ingot` | MATERIAL | 64 | 23 |
| 晶体 | `skyisland:crystal` | MATERIAL | 64 | 24 |
| 火药 | `skyisland:gunpowder` | MATERIAL | 64 | 25 |
| 步枪弹 | `skyisland:rifle_ammo` | AMMO | 128 | 26 |
| 步枪 | `skyisland:rifle` | GUN | 1 | 27 |

步枪的 `GunSpec` **逐值照抄 PRD 5.4.1**：伤害 14 / 弹匣 10 / 2.0 发每秒 / 射程 48 / 换弹 2.0 秒。
未取值处（开火模式、ADS）见 §5。

### 2.3 ⚠️ runtimeId 位移：+2，不是 +1

这是本轮**最容易误判**的一条：

| 物品 | 旧 runtimeId | 新 runtimeId |
|---|---|---|
| 手枪 | 17 | **19** |
| 冲锋枪 | 18 | **20** |
| 煤炭 | 15 | **17** |

原因：`BlockRegistry` 的方块物品先注册，**每多 1 个方块，后面所有非方块物品的 runtimeId 整体 +1**。
本轮加了 **2 个方块**，所以全部非方块物品 **+2**（不是"只加了一堆物品所以 +N"）。

**为什么这不危险**：存档只写**稳定字符串 ID**（PRD 12.3），运行时 ID 不落盘。
**为什么仍然必须显式登记**：任何"数字被移动了却没人说"的地方，都是假绿灯的温床。

`ItemRegistryTest` 的 4 条断言被重新钉住并补了理由（`pistolRuntimeIdIsStable` 17→19、
`smgIsAppendedAfterThePistolWithoutShiftingIt` 17/18→19/20、
`registrySizeIsPinnedAndRuntimeIdsStillMatchTheirIndex` → 28）。注册表规模 19 → **28**：

```
空槽 1 + 方块物品 16 + 非方块物品 11 = 28
```

### 2.4 合成层（全新包 `com.skyisland.craft`）

| 文件 | 职责 |
|---|---|
| `Recipe.java` | 不可变 `record`：id / 产物 / 数量 / 材料表 / 类别。构造期校验：id 非空、数量为正、材料非空、**材料不重复**、**无自环** |
| `RecipeCategory.java` | `BUILD / MATERIAL / AMMO / GUN / FOOD` |
| `RecipeRegistry.java` | 10 条配方的登记与查询；`bootstrap()` 后**冻结**（与 `ItemRegistry` 同一条纪律） |
| `Crafting.java` | **纯逻辑**：`missingFor` / `canCraft` / `craft`（原子）/ `freeSpaceAfterConsuming` / `describeMissing` |

**10 条配方 = PRD 5.6.2 的 MVP 7 条 + 步枪链 3 条**，逐字照抄 PRD：

| 配方 | 产物 | 材料 | 类别 |
|---|---|---|---|
| R01 | 木板 ×4 | 原木 ×1 | BUILD |
| R02 | 木棍 ×4 | 木板 ×2 | BUILD |
| R03 | 铁锭 ×1 | 铁矿石 ×1 + 煤炭 ×1 | MATERIAL |
| R04 | 铜锭 ×1 | 铜矿石 ×1 + 煤炭 ×1 | MATERIAL |
| R06 | 火药 ×2 | 煤炭 ×2 + 沙子 ×1 | MATERIAL |
| R07 | 火把 ×4 | 煤炭 ×1 + 木棍 ×1 | BUILD |
| R08 | 玻璃 ×1 | 沙子 ×1 + 煤炭 ×1 | BUILD |
| R11 | 手枪弹 ×8 | 铁锭 ×1 + 火药 ×1 | AMMO |
| R12 | 步枪弹 ×6 | 铁锭 ×1 + 火药 ×2 | AMMO |
| R16 | 步枪 ×1 | 铁锭 ×8 + 铜锭 ×3 + 晶体 ×1 + 火药 ×6 + 木棍 ×2 | GUN |

**为什么把 MVP 那 7 条一起做了**：步枪的材料链会**穿过**木棍 → 木板、铁锭、火药这 3 条 MVP 配方。
只做步枪链那几条，配方表会停在"指向一条不存在的配方"的半截状态；
而 MVP 那 7 条本来就一条都没实现过。一并补齐后，"配方表 = PRD 5.6.2 的前半张"才是一个可核对整体。

未实现的是 Alpha 其余条目（R05/R09/R10/R13/R14/R15/R17/R18）与【后续迭代】的 R19–R22 ——
它们的输入物（金矿石 / 小麦 / 石英…）本轮没有登记，**强行写进来会被悬空引用检查当场拒绝**。

#### `RecipeRegistry.verify()`：本项目"死接线"缺陷的求解器

这是本轮**最有价值的一行防线**。它把两类"数据错了但不会自己说出来"的症状变成**启动期异常**：

| 症状 | 如果不检查会怎样 |
|---|---|
| 配方产物引用了没注册的物品 | 合成的产物静默变成空槽 → 表现为"合成了但东西没了" |
| 配方材料引用了没登记的方块 | 配方**永远无法满足**，缺料提示一直写"缺少 铜矿石 ×1"，看起来像玩家没挖够 |

v2 §6.2 的"禁止只添加字符串标签"，执行者就是这里。

#### `Crafting` 的两条语义（都写进了 javadoc）

1. **按"总拥有量"校验，不是按单格**。材料散在背包若干格里也必须能合成
   （`CraftingTest#materialsSpreadAcrossSeveralSlotsStillCraft` 钉住）。
2. **背包满时，"扣完材料刚好腾出一格"要能成功**。
   `freeSpaceAfterConsuming` 按**与 `Inventory.consumeItem` 同样的顺序**模拟扣减，
   否则会出现"明明能合成却提示背包满"。

### 2.5 开局装备（`SkyIslandGame.grantStartingGear`）

| 类别 | 内容 | 落格规则 |
|---|---|---|
| 枪 | 手枪 ×1、冲锋枪 ×1、步枪 ×1 | `add()`（快捷栏优先） |
| 弹药 | 手枪弹 ×24、步枪弹 ×20 | `add()` |
| 材料包 | 原木 ×4、铁矿石 ×9、铜矿石 ×3、煤炭 ×20、沙子 ×4、晶体 ×1 | **`addToMain()`（只进背包）** |

- 弹药量一律写成 `2 * magazineSize()`，不写字面量 —— 改弹匣容量只改一处。
- `Localization.MSG_GEAR_GRANTED` 扩为两参（`已获得 手枪 + 冲锋枪 + 步枪 + 手枪弹 ×%d + 步枪弹 ×%d，另发步枪材料包`）。

#### ★ 为什么材料必须走 `addToMain`（本轮新增的方法）

`Inventory.add()` 的契约是**快捷栏优先**（M1/M2 的既有断言与玩家手感都依赖它）。
但材料是**囤积物**，若走 `add()` 会发生两件坏事：

1. **切分点取决于"材料有几种"**：9 格快捷栏被 5 格装备占掉后只剩 4 格，
   于是 6 种材料里**前 4 种进快捷栏、后 2 种溢出到背包**。这个分界既不是设计意图，也无法向玩家解释。
2. **会改变 M1 门禁的行为**：`M1ScriptedSelfTest#firstBlockSlot`（扫快捷栏找"第一个方块物品"）
   会在挖掘之前就命中 `skyisland:log` / `skyisland:iron_ore`。

因此新增 `Inventory.addToMain(int, int)`：只填主背包 `0..26`，**不与快捷栏交互**，
返回未放入数量（口径与 `add` 一致）。两者共用同一份 `fillRegion`，只在"扫不扫快捷栏"上分叉。

结果：开局快捷栏 = `手枪 手枪弹 冲锋枪 步枪 步枪弹 + 4 个空格`，材料整齐待在背包里。
**"4 个空格"不是余数，是硬要求** —— 玩家挖到的第一块方块要能落进快捷栏（`M2CombatSelfTest` 有断言钉住）。

### 2.6 表现层（第 3 套剪影，不是复用）

| 位置 | 内容 |
|---|---|
| `ViewmodelGeometry` | `P_RIFLE_BODY` / `P_RIFLE_BARREL` 两个调色板行 + `RIFLE_PARTS`（6 个盒体，含**瞄准镜**与**长枪托**） |
| `ItemIcon` | `rifleParts` + 按稳定 ID 分派的材料图标（铁锭/铜锭/晶体/火药/木棍/煤炭 6 种几何各自不同） |
| `Localization` | 中文名 9 条（铜矿石/晶体矿石/木棍/铁锭/铜锭/晶体/火药/步枪弹/步枪）+ `MSG_CRAFT_MISSING` |

**为什么这一步不能省**：`ViewmodelGeometry.partsFor` 与 `ItemIcon.gunParts` 对未知 `viewmodelId`
**静默回退到手枪几何**。不加第三套剪影，症状是"逻辑说步枪、右手拿手枪" ——
正是 v2 §4.2 明令禁止的同款观感。旧的 `SMG_PARTS` 只在测试里被检查，
本轮把守卫改成**遍历 `ItemRegistry.all()` 里所有 `isGun()`** 并断言**恰好 3 把**被检查
（`everyGunSilhouetteStaysOnTheRightHalfAndClearOfTheCrosshair`）——
以后再加枪时"忘了加分支"会直接变红，而不是靠人记得。

---

## 3. 三条防线都真的响了（不是"设计得好看"）

这一轮的价值有一半在于：**新东西被既有守卫挡住了三次，而那三次都是对的**。

| # | 谁变红 | 真实原因 | 处理 |
|---|---|---|---|
| 1 | `BlockRegistryTest` / `ItemRegistryTest` 4 条 | 加 2 个方块 → 非方块物品 runtimeId 整体 +2 | 重新钉住并补理由（§2.3） |
| 2 | `DeadLocalizationKeyTest` | 我声明了 `MSG_CRAFT_DONE` / `MSG_CRAFT_NO_ROOM` 却**没有任何绘制点** | **删掉那两个 key**，只留 `MSG_CRAFT_MISSING`（`Crafting.describeMissing` 真的在用它）。守门人的原话：*"要么补上绘制点，要么删掉这个 key —— 但不要只是把它加进 `KNOWN_DEAD_KEYS`"* |
| 3 | `CjkFontTest` | 新增中文 18 字无字模（预期内） | 重烤 `CjkFont.java`（239852 → 243765 字节）。**没有删任何中文去"修"它** |

第 2 条特别值得记：**"声明了一个 key 但没人消费"正是本项目最贵的那类缺陷**，
守门人拦下的正是它自己。

---

## 4. 测试与门禁读数

### 4.1 单测

| 时点 | 读数 | 说明 |
|---|---|---|
| 上一轮收尾（接线） | **1076 / 0 / 0** | `tmp/_rifle_test4.log` |
| 本轮（第一次跑） | 1080 / **1** / 0 | 唯一失败 = `CjkFontTest`（预期内，重烤后消失） |
| **本轮最终** | **1080 / 0 / 0** | `tmp/_rifle_final.log`，BUILD SUCCESS |

本轮净增 **+4**（`InventoryTest` 的 4 条 `addToMain` 用例：不碰快捷栏 / 并入主背包同种堆 /
背包满时如实返回余量且不溢到快捷栏 / 忽略非正数量与空气）。

本轮相关测试类的最终条数：

| 测试类 | 条数 |
|---|---|
| `craft.RecipeRegistryTest`（新） | 10 |
| `craft.CraftingTest`（新） | 10 |
| `item.ItemRegistryTest` | 29 |
| `world.block.BlockRegistryTest` | 19 |
| `render.viewmodel.ViewmodelRendererTest` | 19 |
| `render.ui.ItemIconTest` | 12 |
| `player.InventoryTest` | 25 |

`CraftingTest` 里三条是**真正承重的**：
- `materialsSpreadAcrossSeveralSlotsStillCraft` —— 按总拥有量校验；
- `craftingIntoAFullInventorySucceedsWhenConsumingFreesASlot` —— 背包满但扣完刚好腾格的边界；
- `theWholeRifleChainIsCraftableFromRawMaterials` —— **端到端**：
  原木→木板→木棍、矿石+煤→锭、煤+沙→火药、R16→步枪、R12→步枪弹 ×6。

### 4.2 三门禁（冻结 jar，每档全新空存档目录）

run root：`tmp/gate-runs/20261002-231107/`（证据：`tmp/m2_gate-*.stdout.txt`、`tmp/gate_ps_out.txt`）

| 门禁 | 结果 | 断言数（权威计数器） | `PASS ·` 行数 |
|---|---|---|---|
| gate-m1 | exit **0** | 27 项，0 失败 | **58** / 0 `FAIL ·` |
| gate-ui | exit **0** | 75 项，0 失败 | **150** / 0 `FAIL ·` |
| gate-m2 | exit **0** | **193** 项，0 失败，`combat_closure = true` | **386** / 0 `FAIL ·` |

> **计数口径**：每条自测断言输出**两行**（控制台 + 日志回显），所以"按行"是"按断言"的两倍。
> m2 的权威计数器是 `m2_selftest_assertions`，不要去数行。

**m2 断言 180 → 193（+13）**，全部来自 `GEAR_CHECK` 阶段的扩充：

- 步枪在快捷栏第 4 格 + 数量 1；
- 步枪弹在快捷栏第 5 格 + 数量 = 2 × 弹匣容量；
- 材料包 6 种各自的 `countOfItem` 数值（原木 4 / 铁矿石 9 / 铜矿石 3 / 煤炭 20 / 沙子 4 / 晶体 1）；
- **「材料包没有污染快捷栏：第 6 格仍是空的」**；
- **「快捷栏仍有 4 个空格（挖到的第一块方块要能落进快捷栏）」**；
- **「开局快捷栏里没有方块物品」** —— 这条是**事后补的**，见 §8.2：它把 `BREAK_PARTICLES` 阶段
  一直隐式依赖的前提（"快捷栏里没有方块物品，所以 `firstBlockSlot` 能正确判定'玩家已挖到第一块'）
  变成了**自己说出来的断言**。补之前，这个前提只是"材料恰好进了背包"的副产品。

材料那 6 条**刻意用 `countOfItem`（全背包统计）而不是槽号** ——
"材料在不在背包里"和"材料在第几格"是两件事，用槽号断言会把不该承诺的实现细节钉死，
而且一旦填充顺序变了，失败信息会写成"材料没发"，真因只是排列顺序。

---

## 5. ⚠️ 需要主理人裁定的未冻结数值（4 项）

**这些值没有 PRD / v2 依据，是本轮自行选定并已在代码注释里标 ★ 的"试玩可调"。**
请裁定"保持 / 改值 / 另立规则"。

| # | 项 | 本轮取值 | 依据情况 | 备注 |
|---|---|---|---|---|
| 1 | 步枪 **开火模式** | `AUTO`（按住连发） | PRD 5.4.1 只给伤害/弹匣/射速/射程，**未给开火模式** | 射速 2.0 发/秒的 AUTO 与"半自动点射"手感差别很大 |
| 2 | 步枪 **ADS FOV** | 40° | PRD 只给"手持枪械 FOV 70→45"这一条通用规则 | 见下方"第三个值"说明 |
| 3 | 步枪 **ADS 移速倍率** | ×0.55 | 同上 | 同上 |
| 4 | **材料包数量** | 原木 4 / 铁矿石 9 / 铜矿石 3 / 煤炭 20 / 沙子 4 / 晶体 1 | 无依据，按"刚好够走完整条链"取 | 与 `CraftingTest#theWholeRifleChainIsCraftableFromRawMaterials` 用的是同一组数字，两者互为对照 |

### 关于第 2/3 项：40° / ×0.55 是**故意选的第三个值**

这不是随意取的数，而是为了打破本项目已经付过学费的 **「同值巧合」**：

> 手枪 ADS = 45° / ×0.60，SMG = 48° / ×0.65。
> 如果步枪也用 45°/×0.60，那么"ADS 是不是从**数据**里读的"就**在数学上无法证伪** ——
> 读数据与读硬编码常量给出完全一样的结果。

选 **40° / ×0.55** 之后，三把枪的 ADS 两两互异，于是
`theThreeGunsHavePairwiseDistinctAdsValues` 这条断言第一次**能被真实内容证伪**，
而不是只能靠合成一个假 `GunSpec` 来验。
同理也给了三把枪两两互异的 `viewmodelId` / `iconId` / 枪口常量 / 几何剪影。

**如果主理人裁定步枪 ADS 应等于手枪，那么这条防线会退回"同值巧合"状态，
必须另找证伪手段（源码扫描）—— 请把这一点一起裁定。**

---

## 6. 显式临时偏离：材料包直接发放

**偏离内容**：PRD 5.6.2 的配方链默认"材料从世界里挖"，本轮却**开局直接发一套原始材料**。

**为什么不得不这么做**：本轮范围裁定**不做矿石生成** ——
`skyisland:iron_ore` 虽在注册表里，但**世界上从不生成**（旧问题，本轮未动）；
铜矿石 / 晶体矿石是新登记的，同样不生成。
不发放的话，配方**永远无法满足**，缺料提示会一直显示"缺少 铜矿石 ×1"，
看起来像玩家没挖够，实际是世界里根本没有。

**移除条件（写死在注释里）**：一旦"矿石世界生成 + 合成界面"落地，这段发放**应当被删除** ——
保留会让"从零采集"这条闭环失去意义。

**登记位置**：`SkyIslandGame.grantStartingGear` 内 `materialKit` 表的注释块。

---

## 7. 反向验证（R1 轮）

**注入**：把 `grantStartingGear` 里材料包的 `addToMain(...)` 改回 `add(...)`
（并打 `TEMP_REVERSE_VERIFY` 标记），跑 gate-m2。

**结果：exit 1，`m2_selftest_failures = 4`，`combat_closure = false`。**

| # | 变红的断言 | 现场读数 | 归类 |
|---|---|---|---|
| 1 | 材料包没有污染快捷栏：第 6 格仍是空的 | `hotbarSlot(5)=skyisland:logx4` | ✅ **本次注入的靶心** |
| 2 | 快捷栏仍有 4 个空格 | `hotbar 5..8 = logx4 │ iron_orex9 │ copper_orex3 │ coalx20` | ✅ **本次注入的靶心** |
| 3 | 第一次挖掘前捕获到有效瞄准面 | `currentTarget 为空或没有可用面` | ⚠️ 同一根因的下游级联 |
| 4 | 第二次破坏的累计粒子增量同样落在 8–12 内 | `totalBreakParticles 0 → 18（增量 18）` | ⚠️ 同一根因的下游级联 |

**第 3/4 条的因果链（这是本轮发现的第二处"快捷栏必须干净"的隐式依赖）**：
`M2CombatSelfTest#intentForBreak` 的挖掘阶段每步都调 `firstBlockSlot(player)` ——
**扫快捷栏找"第一个方块物品"**。快捷栏一旦被材料占了，`firstBlockSlot` **在第 1 步就返回 5（原木）**，
于是阶段立刻切到"手持方块物品挖第二格"，**从未捕获 `breakTargetAX`**（→ 第 3 条红），
而 `breakParticlesAfterFirstBreak` 被记成 0、两次破坏共生成 18 个粒子（→ 第 4 条红）。

也就是说：**4 条红全部是同一个根因**，且第 1/2 条给出了精确、可读的现场读数。
这比"只红一条"更强 —— 它同时证明了"材料不污染快捷栏"这条要求的**玩家可见后果**不止一处。

**恢复**：注入已完全移除；`grep TEMP_REVERSE_VERIFY src/main/java` = **0 残留**
（全仓唯一的命中都在 `docs/` 与测试类 javadoc 的**说明文字**里，那是检查该标记的代码本身）。
恢复后重建（`clean package`，1080/0/0）并**重跑三门禁，全绿**。

---

## 8. 过程中被门禁挡下的两类真实缺陷

### 8.1 ui 门禁：`INVENTORY_INTERACT` 的两格重合（已修）

第一次跑 gate-ui 时 **exit 1**：

```
FAIL · 被取走的格子已空 — slot(0)=skyisland:logx4
（62 项断言，1 项失败 → 后续 4 个阶段被跳过）
```

**根因**：该阶段先 `invPickSlot = firstNonEmptySlot()`（扫绝对索引 0..35），
再 `invPlaceSlot = firstEmptySlot()`。旧代码下
"非空槽都在快捷栏 27..35、空槽都在主背包 0..26"这个**巧合**把两格天然分开了。
材料包进主背包后，"第一个非空槽"从 27 变成 **0**，而"第一个空槽"在取走之后**恰好也是 0** ——
两格重合 ⇒ 第二次点击把东西**原样放回去** ⇒
「被取走的格子已空」这条断言**在结构上不可能成立**。

**修法**：把"放下那一格必须与取走那一格不同"从**巧合**提升为**显式条件** ——
`firstEmptySlotOtherThan(invPickSlot)`（并把不再被调用的 `firstEmptySlot()` 一并删除，不留死代码）。
理由写进了该方法的 javadoc：**巧合不是契约。**

> 这一条是"夹具（fixture）假设"的典型：**产品是对的，测试的隐含前提被打破了。**
> 它值得单列，因为它展示了"改动内容"会怎样经由**测试夹具**反过来报错。

### 8.2 m2 门禁：`BREAK_PARTICLES` 的隐式前提（**已在本轮补上断言**）

`M2CombatSelfTest` 的挖掘阶段隐含假设"开局快捷栏里没有方块物品"
（代码注释里原本就写着"M2 开局装备只给枪 + 弹药，手里没有方块物品"）。
本轮通过 `addToMain` 保住了这个前提，**但最初它只是"材料恰好进了背包"的副产品 —— 没有任何断言说出来**。

按本项目那条判据（**"最隐蔽的假绿灯是那条行为根本没有对应断言"**），本轮**没有把它留到下一轮**，
而是在 `checkGear()` 里补了一条：

```
PASS · 开局快捷栏里没有方块物品（挖掘阶段的 firstBlockSlot 依赖这条前提）
     — hotbar 0..8 全是枪 / 弹药或空
```

它存在的价值由 §7 的 R1 注入定量证明：**注入 `add()` 之后，这个前提被破坏，
两条挖掘断言以"瞄准面为空 / 粒子增量 18"的样子变红 —— 现场指向玩法，真因是"快捷栏不干净"。**
有了这条断言，同样的破坏会**先在措辞上直接点名真因**，而不是让人去追挖掘逻辑。

---

## 9. 已知边界 / 技术债（诚实清单）

| # | 项 | 状态 |
|---|---|---|
| 1 | **矿石世界生成** | **本轮不做**。铁矿石旧有、铜/晶矿石新增，三种矿石在世界上都不生成 |
| 2 | **合成界面** | **本轮不做**。`Crafting` 是纯逻辑，只有单测在调它 |
| 3 | **合成表 / 合成 UI 的接线** | 因此 `Crafting` / `RecipeRegistry` 目前**没有产品调用点**（只有测试与 `verify()` 的启动期校验）。这不是"死接线"缺陷 —— 它是有意分步交付的第一半，但**下一轮必须补上调用点**，否则它们会变成第二类死代码 |
| 4 | 第二处死接线（**新发现，未处理**） | `GunPresentationSpec` 的 `fireSoundId` / `emptySoundId` / `reloadSoundId` / `recoilProfileId` 四个键**没有任何产品读取者**（只有 `GunPresentationSpecTest` 在读）。与上一轮修掉的 6 个 `GunSpec` 字段是同一类缺陷 |
| 5 | `Hitscan.resolve` 每条射线一次向量归一化分配 | 既有，未处理 |
| 6 | `perf_gate_met` 绝对阈值不可靠 | 既有，登记中 |
| 7 | F6 调试补给会再发整套装备（含材料包） | 既有行为；第二次的枪各占新格、弹药与材料**并入同种堆**（128/64 上限），因此存档往返数量仍是定值 |

---

## 10. 与 v2 §19 通过标准的关系

| §19 条目 | 本轮影响 |
|---|---|
| 第 2 条「SMG 可正常获得」 | **不变**（SMG 仍在开局装备） |
| 第 14 条「真人试玩能感知两把枪差异」 | **建议升为三把**，但真人试玩 §4-E（瞄准）**必须重跑** —— ADS 行为在上一轮接线修正后已变，本轮又加了第三组 ADS 值 |
| 第 15 条「未偷跑第 3 把枪及以后内容」 | **本轮已由主理人显式放行**，故不再是"偷跑"；但**第 4 把及以后仍一律不做**（霰弹枪同属禁止清单） |

---

## 11. 提交分组建议（**未提交 —— 按项目纪律等待用户许可**）

当前工作树 = 上一轮（接线）+ 本轮（步枪/材料/合成）两份改动混在一起。
建议**分成两个提交**，使每个提交都能独立解释自己：

### 提交 A —— 接线修正（上一轮，已单独有报告）

```
fix(weapon): 补完 GunSpec 6 个死字段接线（按枪 ADS / 弹丸散布 / 按枪距离衰减）
```

- `combat/CombatController.java`、`combat/DamageFalloff.java`、`combat/ShotSpread.java`（新）
- `player/Player.java`
- `testutil/SourceScan.java`（新）、`combat/ShotSpreadTest.java`（新）、`combat/WeaponDataWiringTest.java`（新）
- `combat/CombatControllerTest.java`、`combat/CombatCoreTest.java`、`player/PlayerAimingTest.java`
- `game/InfiniteReserveWiringTest.java`
- `docs/testing/M3_WEAPON_WIRING_REPORT.md`（新）

### 提交 B —— 步枪 + 材料链 + 合成逻辑（本轮）

```
feat(craft): 显式放行后落地步枪与整条材料链（v2 §18 / PRD 5.6.2）

- 方块 +2（铜矿石 / 晶体矿石，尾部追加）→ 非方块物品 runtimeId 整体 +2，测试重新钉住
- 物品 +7（木棍/铁锭/铜锭/晶体/火药/步枪弹/步枪），占据 runtimeId 21..27
- 新包 com.skyisland.craft：Recipe / RecipeCategory / RecipeRegistry（10 条配方）/ Crafting（纯逻辑）
  · RecipeRegistry.verify() 把"悬空引用"变成启动期异常（v2 §6.2 的执行者）
- Inventory.addToMain()：囤积物只进背包，保住"快捷栏留 4 格给玩家挖到的方块"
- 第 3 套 viewmodel 剪影 + 材料图标 + 中文名（CjkFont 重烤）
- 开局装备扩充；材料包走 addToMain
- M2CombatSelfTest 的 GEAR_CHECK 扩 13 条断言；M1_5UiSelfTest 取/放槽位不再重合
- 单测 1076 → 1080/0/0；三门禁 58 / 150 / 386 行 PASS·，m2 断言 193 / 失败 0
```

涉及文件：

- **主源码**：`world/block/BlockRegistry.java`、`item/ItemRegistry.java`、
  `craft/`（4 个新文件）、`player/Inventory.java`、`game/SkyIslandGame.java`、
  `game/M2CombatSelfTest.java`、`game/M1_5UiSelfTest.java`、`ui/Localization.java`、
  `render/viewmodel/ViewmodelGeometry.java`、`render/ui/ItemIcon.java`、`render/ui/CjkFont.java`
- **测试**：`craft/RecipeRegistryTest.java`（新）、`craft/CraftingTest.java`（新）、
  `item/ItemRegistryTest.java`、`world/block/BlockRegistryTest.java`、
  `player/InventoryTest.java`、`render/viewmodel/ViewmodelRendererTest.java`、`render/ui/ItemIconTest.java`
- **文档**：本文件

> `.workbuddy/` 是工作区记忆目录，**不要提交**。
> 两个提交都**尚未执行**；请确认分组与消息后我再提交。

---

## 12. 待主理人裁定清单（汇总）

| # | 待裁定 | 位置 |
|---|---|---|
| 1 | 步枪开火模式 = AUTO 是否成立（PRD 未给） | §5-1 |
| 2 | 步枪 ADS 40° / ×0.55 是否成立；若改为等于手枪，需另定"防同值巧合"手段 | §5-2/3 |
| 3 | 材料包数量是否认可（原木 4 / 铁矿石 9 / 铜矿石 3 / 煤炭 20 / 沙子 4 / 晶体 1） | §5-4 |
| 4 | "临时发放材料包"是否认可；确认移除条件 = 矿石生成落地时 | §6 |
| 5 | 步枪【Alpha 必须】却进 MVP 开局装备 —— 是否补 PRD 修订记录 / 明确"试玩装备 ≠ MVP 边界" | §1.2 |
| 6 | 真人试玩 §4-E（瞄准）**必须重跑**；试玩清单是否加入步枪 | §10 |
| 7 | 提交分组 A / B 是否照此执行 | §11 |

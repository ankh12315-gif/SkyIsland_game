# PRE-M3 INTEGRATION CLOSURE 报告

> 轮次：**M3 纵向切片之前的接线收口**（Pre-M3 Integration Closure）
> 日期：2026-10-03
> 唯一实施基线：`docs/design/WEAPON_SYSTEM_OPTIMIZATION_v2_FINAL.md`（v2）
> 状态：**已完成，停止，等待 M3 Vertical Slice 指令**

---

## 1. 本轮做了什么、明确没做什么

这一轮的目标只有一个：**把已经存在的数据层接进玩家真正碰得到的功能，并关掉已知的死接线**，做完就停在纵向切片之前。

**做了（4 项）**

| # | 任务 | 关闭的缺口类型 | 对应裁定条款 |
|---|------|----------------|--------------|
| 1 | 步枪开火模式 = `SINGLE`（并冻结 ADS 40° / ×0.55） | 数据值与玩法口径不符 | 1 / 2 / 15 |
| 2 | `GunPresentationSpec` 四个表现键接进生产代码 | **死接线**（sound ×3 + recoil） | 11 / 12 / 13 / 14 |
| 3 | `RecipeRegistry` / `Crafting` 接进背包界面 | **死接线**（合成侧零玩家可达调用点） | 5–10 |
| 4 | 开局装备拆成 DEV / SURVIVAL 两套口径 | 测试夹具被当成产品内容 | 3 / 4 |

**明确没做（本轮禁止项逐条核对）**

- ❌ **没有第 4 把枪**。仍然是 3 把：手枪 / 冲锋枪 / 步枪。
- ❌ 没有霰弹枪 / 机枪 / 加特林 / 狙击枪，没有 BURST，没有配件 / 过热 / spin-up。
- ❌ 没有正式矿石世界生成、完整资源岛、昼夜、自然刷怪、农业、饥饿。
- ❌ **没有新增配方**。配方表仍是 PRD 5.6.2 的 MVP 7 条 + 步枪链 3 条 = **10 条**。
- ❌ **没有新增 Block / Item**。`ItemRegistry` 规模仍是 **28**（裁定第 16 条），`runtimeId` 未发生位移。

---

## 2. 任务一：步枪开火模式 = SINGLE

**裁定**：每次真实的 `PRIMARY_ACTION` 按下至多打一发；按住不得连发。SMG 保持 `AUTO`。伤害 14 / 弹匣 10 / 射速 2.0 发每秒 / 射程 48 / 换弹 2.0 s 冻结不变。

**改动**：`ItemRegistry` 里步枪注册的 `fireMode` 由 `AUTO` 改为 `SINGLE`，并把原先"属试玩可调"的注释改写成**冻结裁定 + 反向钉子**。

**为什么必须补输入层回归**：如果只改注册表里那个枚举值，"玩家按住左键连发"这条**旧行为**会悄悄退化成单发，而任何只断言"注册表里写的是 SINGLE"的测试都会全绿。因此新增 `FireModeInputSemanticsTest`（10 项），用**两条互相独立的轴**把三把枪区分开：

- **模式轴（按住不产生按下沿）**：按住 1 秒 → 手枪 0 发 / SMG 10 发 / 步枪 0 发。
- **节奏轴（0.3 秒一次点按 ×10）**：手枪 10 / SMG 10 / **步枪 5**（单发 + 2.0 发每秒的射速节拍）。

只测其中一条都不够：只测"按住"无法排除"把所有枪都改成 AUTO"，只测"点按"无法排除"把所有枪的射速写成一样"。

**高帧率下的可靠性（第 15 条）**：`rifleOneClickIsOneShotAtHighFrameRate` 在 300 FPS / 60 Hz 逻辑下把点击放在**没有逻辑步的那一帧**上，仍然一按一发；`riflePressIsNotRepeatedAcrossLogicStepsInOneFrame` 确认一帧内多个逻辑步不会把一次按压重复消费。两处都走真实的 `Frame → FrameInputQuantities → Logic` 缓冲链，**没有引入任何裸渲染帧布尔**——`noDirectRenderFrameBooleanIsReadByTheCombatLayer` 扫源码钉住这一点（`CombatController` 里不得出现 `org.lwjgl` / `InputState` / `InputMapper`，且 `fireRequested` 方法体必须同时含 `intent.attackPressed()` / `intent.attackHeld()` / `gun.spec().fireMode()` 且不得含 `PISTOL` / `SMG` / `RIFLE` 三个字面量）。

**ADS 保持第三组值（第 2 条）**：步枪仍是 FOV `40.0°` / 移速 `×0.55`，**不回到手枪的 45 / ×0.60**。这不是风格选择，是**可证伪性**的安排：三把枪若取同一个值，"ADS 到底有没有从数据读"就在数学上不可区分（同值巧合），接线断了测试照样全绿。`theThreeGunsHavePairwiseDistinctAdsValues` 逐项钉住。

---

## 3. 任务二：GunPresentationSpec 四个死键闭合

`GunPresentationSpec` 里有 9 个表现键，其中 4 个此前**只有定义、只有校验、没有任何生产读取点**——`fireSoundId` / `emptySoundId` / `reloadSoundId` / `recoilProfileId`。本轮全部接通。

### 3.1 声音路由（第 12 条）

- `AudioEvent.byId(String)`（**未知键抛异常，不静默回退**）。
- 新增 `GunAudio`：`fireEventOf / emptyEventOf / reloadEventOf(GunPresentationSpec)`，唯一职责是把 `*SoundId()` 翻译成 `AudioEvent`。
- `AudioFeedback` 改为 `wrap(AudioManager, Player)`，持有玩家以便问出"当前手持枪的表现规格"，三个回调一律走 `GunAudio`。
- **`AudioFeedback` 里不再有 `AudioEvent.GUN_FIRE` / `GUN_EMPTY` / `RELOAD` 任何字面量。**

关于"三把枪共用同一段 WAV"：这是**有意的**，而本轮不靠"声音必须不同"来证明接线，而是靠三层证明——

1. **解析层**：改步枪的 `fireSoundId` 只有步枪读出的事件变，手枪 / SMG 不变。
2. **源码层**（两层都钉）：`AudioFeedback` 的三个方法体必须含 `GunAudio.fireEventOf/emptyEventOf/reloadEventOf`，**并且** `GunAudio` 的对应方法体必须含 `*SoundId()`。只查第一层会被"委托"满足，只查第二层则漏掉"委托断了"。
3. **端到端层**：完整回调链在真实输入下走一遍。

三把枪若共用一段 WAV，用行为断言是**分不出**"读了数据"与"读了常量"的——这正是本项目反复吃亏的那一点，所以只能把"读的是哪个键"钉在结构上。

### 3.2 后坐力档案（第 13 条）

新增 `RecoilProfile`（record，7 个分量：单发抬头角 / 累计上限 / 回落速度 + 三个表现层推力），三份档案：

| 档案 | 单发 | 上限 | 回落 | 表现推力 Z | 表现俯仰 |
|------|------|------|------|-----------|---------|
| PISTOL | 0.90° | 1.80° | 5.00°/s | 0.055 | 0.34 |
| SMG | 0.35° | 2.60° | 2.40°/s | 0.030 | 0.20 |
| RIFLE | **1.60°** | 2.20° | 4.00°/s | **0.085** | **0.52** |

- **手枪那份与被删掉的三个 `Camera` 常量逐位相同**（`RecoilProfileTest` 第一条就钉这个），所以这次数据化**不改变任何既有手感**。
- 六个维度**两两不同**，且步枪单发 ≥ 1.4 × 手枪 —— 这保证"步枪在视觉上明显重于手枪"是**可证伪的**，而不是靠同一个数蒙过去。
- `Camera` 的 `addRecoilPitch(double)` / `decayRecoil(double)` 双双改成收档案的版本；`Camera` 里**不再有任何后坐力常量**。
- `ViewmodelPose` 在开火当刻把档案快照下来，之后的推力/俯仰都从快照读（避免"一帧内换枪导致后坐力手感突变"）。

### 3.3 枪口偏移（第 14 条）

三把枪的枪口偏移已经是独立数据（`rifleMuzzleIsNotThePistolMuzzle` 钉住：X 坐标不同）。`ViewmodelRendererTest` 另外钉住三套剪影两两不同——加枪不加剪影的失败模式是"逻辑说步枪、右手拿手枪"。

### 3.4 审计

`GunPresentationRoutingTest#everyPresentationComponentHasAReaderInProductionCode` 扫全部主源码，断言 9 个表现键每一个都有生产读取点。

---

## 4. 任务三：Crafting 产品接线 ★ 本轮最高优先级

### 4.1 问题

`RecipeRegistry`（10 条配方）与 `Crafting`（纯逻辑：缺料 / 可合成 / 原子扣料）此前**在整个产品里零玩家可达调用点**。没有界面、没有命令、没有按键。那是最难受的一种状态：数据在、逻辑对、单测全绿，而玩家永远碰不到。

### 4.2 入口与形态（第 6 条）

复用既有背包界面，不新开屏幕：**E → 背包 → 右侧合成栏**。左 = 玩家背包（不动），右 = 配方列表。

- 几何：`InventoryLayout` 新增 `CRAFT_COLUMN_GAP / CRAFT_COLUMN_WIDTH / CRAFT_ROW_HEIGHT / CRAFT_TITLE_HEIGHT`，并把 `craftRowCount` 纳入布局缓存的失效条件（**新增一个重载而不是改旧的**：旧的 2 参 `compute` 委托到 0 行版本，既有调用点一行都不用改）。
- 栏宽 380 是**按最长那一行的文字反推**的（R16 步枪那行 ≈ 372 px），`InventoryLayoutTest` 有一条断言专门守"最长那一行必须放得进合成栏"。
- 每行：产物图标 + 中文名 + `×产出量` + 材料需求 `铁锭 8/8 铜锭 3/3 …` + 状态按钮。

### 4.3 状态与反馈（第 7 条）

至少区分 `CRAFTABLE` / `MISSING_MATERIALS` 两种状态。

- 可合成 → `[合成]`
- 缺料 → `缺少 铁锭 ×8、缺少 铜锭 ×3、缺少 火药 ×6、缺少 木棍 ×2`

缺料行点击时**必须出声 + 出字**（`UI_DENIED` + 把缺料清单写进提示条）。理由写在代码里：缺料时如果界面纹丝不动，玩家会一直点下去——从他的角度看，这与"按钮坏了"无法区分。**"点了没反应"最常见的形态不是代码没写，而是只在成功分支给反馈。**

### 4.4 扣料唯一权威（第 8 条）

新增 `CraftingPanel`，它**一行扣料代码都没有**：

- `refresh(Inventory)` → 只调 `Crafting.missingFor(...)`，然后把清单排版成一行字；连"还差几个"都不自己数。
- `craft(int, Inventory)` → 只调 `Crafting.craft(...)`，然后刷新。

`CraftingPanelTest#thePanelNeverImplementsItsOwnMaterialArithmetic` 逐条钉住：整个类里不得出现 `consumeItem` / `consumeSelected` / `removeItem` / `setSlot`；`craft()` 必须含 `Crafting.craft(`；`refresh()` 必须含 `Crafting.missingFor(`。（扫描前先剥注释，锚到方法体——否则一句"这里不许出现 consumeItem"的注释自己就把断言满足了。）

真实路径：**播种光标 → 注入原始鼠标左键 → `handleInventoryInput` → 像素换算 → `hitTestCraftAny` → `craftRow` → `Crafting.craft` → 背包变更 → 刷新**。合成区优先于槽位判定处理，两者几何上不重叠。

### 4.5 原子性（第 9 条）

- 材料够 → 扣一次、产一次。
- **产出放不下 → 整次合成拒绝**，材料一字不改。
- 门禁层证据：缺料行点击前后物品总数 `92 → 92`；三条配方走完的总净变化 `89 → 99`（期望 `+10`，由配方算得出：`4 × (4−1) − 1 − 1`）。
- 单测层：`craftingWithNoRoomForTheOutputIsRejectedAtomically` 用 36 格全满 + 材料各 64 个（扣 1 个后格子不空 ⇒ 扣完仍无空位）构造，逐格快照比对。

### 4.6 三条人类可完成配方（第 10 条）

门禁在真实输入下合出：`原木 ×4 → 木板 ×16`（4 次点击，R01）、`铁矿石 9→8 / 煤炭 20→19 / 铁锭 0→1`（R03）、`煤炭 19→17 / 沙子 4→3 / 火药 0→2`（R06）。同时验证**原木用尽后木板行自己变成 `缺少 原木 ×1`** ——这是"合成之后界面状态刷新"最硬的一条证据（缓存没刷就会一直显示 `[合成]`）。

---

## 5. 任务四：开局装备口径分离（DEV / SURVIVAL）

### 5.1 问题

2026-10-02 为了让步枪链可被验证，开局装备被加上了步枪 + 步枪弹 + 一整套原始材料。那是一次有理由的临时偏离，但它挂在**唯一一条**开局装备路径上，于是"正式 Survival 新游戏"与"自测 / 试玩"共用同一份发放表——玩家开一局正式新游戏，背包里就躺着一份只有为了让断言能跑才存在的材料包。

### 5.2 拆法

新增 `com.skyisland.game.Loadout`：

| 口径 | 内容 | 谁在用 |
|------|------|--------|
| `SURVIVAL`（**产品默认**） | 手枪 ×1 + 冲锋枪 ×1 + 手枪弹 = 2 × 弹匣容量。**无步枪、无材料包。** | 正式运行 |
| `DEV` | 上述全部 + 步枪 ×1 + 步枪弹 + `DEV / TRANSITION MATERIAL KIT` | 三个门禁 + `play-m3.bat` |

开关：`-Dskyisland.loadout=dev`。解析规则与 `-Dskyisland.infiniteReserve` **同一条安全侧原则**：只有显式 `dev` 才切 DEV，属性缺失 / `false` / 任何错字一律留在 `SURVIVAL`。理由是两种错法的代价不对称——错字导致"没拿到材料包"只是一次试玩少点材料；错字导致"正式存档开局就发一把步枪和整套材料"会污染玩法本身，那正是 v2 §19-15「未偷跑」要防的。

**否决过的方案**：不拆成 `grantRifle` / `grantKit` 两个独立布尔。那会让"试玩口径"变成一个**组合**，于是门禁跑的那一套与玩家跑的那一套之间又多了一层"到底开没开某个开关"的疑问——本枚举要消灭的正是这层疑问。

### 5.3 材料包的删除条件（第 3 条）

代码里把删除条件写死在注释里：**矿石世界生成 + 正式合成获取链闭合后删除本段**。保留会让"从零采集"这条闭环失去意义——玩家不用挖就能拿到全部材料，而"能不能挖到"恰恰是那条链唯一要证明的事。名称本身也改成了 `DEV / TRANSITION MATERIAL KIT`，让每一个读到它的人都知道这不是产品内容。

### 5.4 守卫

新增 `LoadoutWiringTest`（6 项），与 `InfiniteReserveWiringTest` 完全同一套四层结构：解析规则 → 属性到配置 → **枚举语义**（`SURVIVAL.grantsRifle()` 与 `grantsMaterialKit()` 必须都是 false）→ 配置到装配期（`grantStartingGear` 方法体必须含 `config.loadout()` 且同时用到两个谓词）→ 启动器（`play-m3.bat` **启动行**必须带 `-Dskyisland.loadout=dev`；两个门禁运行器各 3 处 / 1 处）。

判据锚定"启动行"而不是全文件，和弹药开关那条同一个坑：文件头的 `REM` 说明里会明文解释这个参数，全文件 `contains` 会被那段**说明文字**满足，于是"java 行上真的删掉了开关"也照样全绿。

---

## 6. 自动化测试与门禁取证（第 16 / 17 / 18 条）

### 6.1 单测基线：只增不减

| | 本轮前 | 本轮后 | 增量来源 |
|---|---|---|---|
| `mvn clean package` | 1080 / 0 / 0 | **1148 / 0 / 0** | +68 |

新增测试类：

| 类 | 用例 | 守的是什么 |
|----|------|-----------|
| `FireModeInputSemanticsTest` | 10 | 手枪 SINGLE / SMG AUTO / 步枪 SINGLE 的输入语义；高帧率一按一发；禁止裸帧布尔 |
| `RecoilProfileTest` | 12 | 手枪档案 == 旧常量；三份档案两两不同；回落 / 上限 / 累积 |
| `GunPresentationRoutingTest` | 14 | 声音路由、后坐力路由、枪口路由；9 键读取点审计 |
| `CraftingPanelTest` | 13 | 合成栏可用性 / 状态 / 扣料 / 产出 / 无空间原子拒绝 / 刷新 / 界面不自己算 |
| `LoadoutWiringTest` | 6 | DEV 与 SURVIVAL 两套装备的解析、接线、启动器与门禁 |
| `LauncherJarResolutionTest` | 4 | shade 别名不算第二个 jar；两个真 jar 仍须拦下；失败分支要能让人继续；定长后缀比较而非通配 |
| `PowerShellScriptEncodingTest` | 3 | 被跟踪的含中文 `.ps1` 必须带 UTF-8 BOM（§6.4） |
| `NativeLauncherWiringTest` | 6 | 桌面 exe 的世界名 / 开关 / jar 解析 / JDK 顺序 / 构建脚本入库 / **与 bat 不得漂移**（§7.2） |

### 6.2 三档门禁

运行器 `tmp/run_gate_ps.ps1`（PowerShell 版；本机 node 的 `spawnSync java.exe → EBUSY` 间歇性故障，PowerShell 直调 `& java` 仍可用），冻结 jar，**每个门禁一个全新的带时间戳空存档目录**。

| 门禁 | 本轮前 | 本轮后 | 权威计数 |
|------|--------|--------|---------|
| `gate-m1` | 58 行 `PASS` | **60 行 `PASS`，0 `FAIL`** | — |
| `gate-ui` | 150 行 `PASS` | **179 行 `PASS`，0 `FAIL`** | 新增阶段 16/21「背包内合成」 |
| `gate-m2` | 386 行 `PASS` | **387 行 `PASS`，0 `FAIL`** | `m2_selftest_assertions = 193`，`m2_selftest_failures = 0` |

本轮最终取证目录：`tmp/gate-runs/20261003-012045/`，三个门禁 exit=0（m1 15.8 s / ui 6.1 s / m2 57.8 s）。

合成阶段的门禁断言原文（摘自 `tmp/m2_gate-ui.stdout.txt`，阶段 16/21「背包内合成」）：

```
PASS · 合成栏列出了全部已注册配方 — 界面行数=10，注册表=10
PASS · 瞄准木板行时界面命中的就是木板行 — hover_row=0，aim=0
PASS · 瞄准步枪行时界面命中的就是步枪行 — hover_row=9，aim=9
PASS · 材料齐备的木板行显示可合成按钮 — 实际=[合成]
PASS · 缺料的步枪行显示缺什么（不是空白、也不是可合成） — 实际=缺少 铁锭 ×8、缺少 铜锭 ×3、缺少 火药 ×6、缺少 木棍 ×2
PASS · 点一次木板行：原木 -1 — 4 → 3
PASS · 点一次木板行：木板 +4（PRD 5.6.2 的 R01 产出量） — 0 → 4
PASS · 点缺料的步枪行后背包物品总数不变（没有偷偷扣料） — 92 → 92
PASS · 点缺料的步枪行后仍给出缺料提示（禁止点了没反应） — 实际=缺少 铁锭 ×8、…
PASS · 原木用尽后木板行自己变成缺料（合成后界面状态刷新） — 实际=缺少 原木 ×1
PASS · 木板产出总量 = 4 × 消耗原木数（不复制、不丢失） — 原木 4，木板 0 → 16
PASS · 合成阶段物品总数的净变化 = 三条配方算出的净值（不复制、不丢失） — 89 → 99（期望 +10）
PASS · 点铁锭行：铁矿石 -1、煤炭 -1、铁锭 +1 — 铁矿石 9→8，煤炭 20→19，铁锭 0→1
PASS · 点火药行：煤炭 -2、沙子 -1、火药 +2 — 煤炭 19→17，沙子 4→3，火药 0→2
PASS · 关闭背包后槽位物品总数与合成阶段结束时一致（关背包不凭空增减） — 99 → 99（取放前 89；差额来自本轮合成）
```

### 6.3 CJK 字模（第 17 条）

新增的正式面向玩家中文（`合成` / `[合成]` / `背包放不下产物，合成已取消（材料未扣除）`）全部进 `Localization`，并**重跑字模烘焙**而不是删中文：

```
D:/software/jdk-25/bin/java.exe tools/fontgen/GenCjkFont.java .
```

本轮共补齐 2 批（首批 6 字：悉 拎 摸 泻 熟 舞；次批 2 字：⑧ 锯）。`CjkFontTest` 12 项全绿。

---

## 7. 反向验证记录（第 19 条）

纪律：注入一处**看似合理**的断线 → 目标断言必须变红 → 逐字节恢复 → 残留标记必须为 0。守卫：`UiAudioWiringTest#noReverseVerificationMarkerIsLeftBehindInMainSources`（扫**整个** `src/main/java`，不是只扫一个文件）。

| 编号 | 注入的断线 | 变红的断言 | 对照 |
|------|-----------|-----------|------|
| **R2** | 步枪 `FireMode.AUTO` | **5 条**，跨 3 个类：`theSameHeldSequenceSeparatesAutoFromSingle`（期望 0 实际 2）、`rifleHeldDoesNotAutoFire`（0 vs 2）、`rifleIsSingleShotNotAuto`、`riflePressIsNotRepeatedAcrossLogicStepsInOneFrame`（期望 1 实际 0）、`ItemRegistryTest#rifleMatchesPrd541…` | — |
| **R3** | `riflePresentation()` 直接 `return pistolPresentation()` | **5 条**，跨 4 个类：`rifleMuzzleIsNotThePistolMuzzle`（X=0.7 相等）、`viewmodelModelCarriesTheRecoilProfileIdOfTheHeldGun`、`ItemRegistryTest#theThreeGunsHaveDistinctPresentationKeysAndMuzzles`、`RecoilProfileTest#theThreeGunsResolveToThreeDifferentProfiles`、`ViewmodelRendererTest#theThreeGunSilhouettesArePairwiseDifferent` | — |
| **R1** | `CraftingPanel.craft` 改成直接返回 `MISSING_INGREDIENTS`（不调 `Crafting.craft`） | 单测 **3 条**：`clickingACraftableRowConsumesAndProducesAndRefreshes`（期望 CRAFTED 实际 MISSING）、`craftingWithNoRoomForTheOutputIsRejectedAtomically`（期望 NO_ROOM 实际 MISSING）、`thePanelNeverImplementsItsOwnMaterialArithmetic`；**门禁 ui 变红**，失败信息自解释：`阶段「背包内合成…」在 20000 帧内达成条件 — 点了 60 次 R01 后原木仍有 4 个 —— 合成路径没有真的扣料` | **m1 60 PASS / m2 387 PASS 保持全绿**（证明这条红是这条线独有，不是环境噪声） |
| **R4** | 抽掉 `tmp/verify_m3_play.ps1` 的 UTF-8 BOM | `PowerShellScriptEncodingTest#everyTrackedNonAsciiPowerShellScriptCarriesAUtf8Bom`，失败信息精确到文件名与字节数：`这些 PowerShell 脚本含中文但**没有 UTF-8 BOM**：[tmp\verify_m3_play.ps1(1161 个非 ASCII 字节)]` | 恢复后逐字节校验 sha256 前缀 `2f05a3153e84141e`，临时 `.bak` / `.bak2` 已删 |

恢复后：`src/main` 里 `TEMP_REVERSE_VERIFY` / `riflePresentationReal` 残留 = **0**；被跟踪的 7 个启动/门禁工具文件里 `TEMP_REVERSE` / `@@` 类标记 = **0**。

---

## 7.1 追加：主理人报障的启动器失败与它的两个根因

主理人双击 `play-m3.bat` 撞到：

```
[ERROR] Expected exactly 1 jar in target\, found 2.
Build it first:  node tmp/build.js clean package
请按任意键继续. . .
```

**根因一（脆代理量）**：现场是 `target\` 下确实有两个匹配 `skyisland-*.jar` 的文件 —— `skyisland-<v>.jar` 与 `skyisland-<v>-shaded.jar`，**sha256 完全相同**（都是 5794458 B；另有 `original-<v>.jar` 是 shade 之前的 thin 备份 678889 B，本来就不匹配 glob）。守卫把 maven-shade-plugin 留下的**字节相同别名**当成了"第二个版本"，而它<b>不是一次偶发</b>：只要不 `clean` 就重新构建（开发与试玩的常态）别名就在那里。判据从"恰好 1 个**文件**"改成"恰好 1 个**真 jar**"，显式排除别名与备份；失败分支补 `dir /b`，让守卫的价值有一半落在失败信息里。同一规则同步到另外两个解析 jar 的脚本（`tmp/run_frozen_gate.js` / `tmp/verify_m3_play.ps1`）。

★ 这条守卫的**第一版修法自己也是错的**：`if /i "…" neq "skyisland-*-shaded"` 被误以为支持通配，实测 cmd 的 `if` 字符串比较**不做通配**（只有 `if exist` 做），别名被计入、`JARCOUNT=2`、守卫原样报错。改成延迟展开的定长后缀 `!CAND:~-11!=="-shaded.jar"`。**是"把那段原样抽出来喂合成夹具、用真实 `cmd /c` 跑"才抓到的，靠读代码看不出来。**

**根因二（编码）**：修完之后给 `tmp/verify_m3_play.ps1` 加了一段中文注释，脚本立刻跑不动，报一屏语法错误（`字符串缺少终止符: '。`）。Windows PowerShell 5.1 读**无 BOM** 的 `.ps1` 时按**系统 ANSI 代码页**解码（本机 GBK），而文件是 UTF-8；中文变乱码，且 GBK 双字节序列**会吞掉引号**。加 UTF-8 BOM 后解析通过（269 tokens）。

★ 这个坑**阴险在它"有时候"能跑**：仓库里 `tmp/run_gate_ps.ps1` 有 **88 行中文却一直能跑** —— 它的中文恰好没触发吞引号，**纯属侥幸**，是典型脆绿灯。修法一律是**加 BOM / 改编码**，绝不是删中文注释（与 `CjkFontTest` 同一条项目规矩）。守卫 `PowerShellScriptEncodingTest` 扫 `.gitignore` 的 `!tmp/*.ps1` 白名单（那正是"备用通道必须跨克隆存在"的三个文件），而不是扫 `tmp/` 下全部——一次性探针在磁盘上爱什么编码都无所谓，纳入守卫只会制造噪声。

---

## 7.2 追加：桌面那个 exe 改好了（"源码改了、没人重编"）

主理人随后要求把**桌面那个原生启动器**一并改好。侦察发现它带着**四个**独立问题，其中只有一个和 bat 有关：

| # | 问题 | 现场证据 | 修法 |
|---|------|----------|------|
| 1 | jar 解析**与 bat 一模一样的脆代理量** | 手工把主 jar 复制成 `-shaded.jar` 再跑桌面 exe，**一字不差**地报 `Expected exactly 1 jar ... found 2` | 新增 `w_is_shade_artefact()`：定长尾比较 `_wcsicmp(name+n-11, L"-shaded.jar")`；失败分支列出 `target\` 实际内容（对应 bat 的 `dir /b`） |
| 2 | 世界名锁死 **M2.1** | `kWorldName = L"m21-play"` —— 桌面入口开的是另一个存档（经过 12 次死亡、子弹已打光，只剩一把手枪） | `m3-play` / `tmp\m3-play-saves` / `tmp\m3-play-settings.json` |
| 3 | 跑的是 **JDK 23 而不是 25** | 查找顺序 `SKYISLAND_JDK > JAVA_HOME > 内置`，而本机 `JAVA_HOME` 指向 jdk-23。日志 / 门禁 / `play-m3.bat` 全都说 25 | 内置 jdk-25 提到 `JAVA_HOME` **之前**（`SKYISLAND_JDK > 内置 > JAVA_HOME > PATH`） |
| 4 | 缺两个试玩开关 | exe 从不传 `infiniteReserve` / `loadout` | 新增 `kExtraSwitches` 常量，两个开关收敛在一处，并打进启动信息首屏 |

**根因比上面四条都更深一层**：`.gitignore` 排除了 `launcher/*.exe`（为了避免 300 KB 二进制 diff），所以 **C 源码是唯一的入库产物，而"重编 exe"这一步没有任何机制会提醒你**。9-23 编的那个二进制就这么躺着，跨过两个里程碑，而 `play-m3.bat` 每次都在修 —— 两条入口各自"符合自己的定义"，**没有任何测试会红**。修法有三件：

- 新建入库的 `tmp/build_launcher.js`（并在 `.gitignore` 白名单它）：`windres` + `gcc` 产出 `SkyIsland.exe`（双击用，藏控制台）与 `SkyIsland-console.exe`（取证用，不藏）。
- 记录编译参数里两个**实测踩到**的点：`-municode` 会让 CRT 要求 `wWinMain`，而源码是 `int main`；`.rsrc merge failure: multiple non-default manifests` 是 mingw 链接器的**警告不是错误**（rc=0，产物正常），因为 gcc spec 总会链入 `default-manifest.o` 而 `.rc` 里也有同一份 manifest —— **不要**为了消掉它把 manifest 从 `.rc` 删掉（那份 manifest 声明了 asInvoker / dpiAware / longPathAware）。
- `launcher/skyisland_launcher.rc` 的 `Comments` 从 `mimics play-m2.bat` 改成 `mirrors play-m3.bat` —— 口径改了，资源里的说明也要跟着改，否则在属性页里读到的仍是 M2 的故事。

**守卫 `NativeLauncherWiringTest`（6 条）**，反向验证 **R5**：把 `kWorldName` 注回 `m21-play` → **2 条变红**，其中包括第 6 条"bat 与 exe 不得漂移"（那正是本类的落点：前五条各守一个字段，第 6 条守**关系**）。还原后逐字节 sha256 一致。

★ **为什么这六条读 C 源码而不读编出来的 exe**：`.exe` 不入库，fresh clone 里根本没有，断言一个可能不存在的二进制等于没有断言。★ 为什么不加"exe 时间戳比源码新"这条：它会把"我刚 `mvn package` 过"和"我重编过 launcher"混为一谈 —— 那是又一个脆代理量。

---

---

## 8. 本轮暴露并修掉的两个"夹具假设"

这两条是本轮真正的收获——**它们都不是产品缺陷，而是测试夹具的隐含前提被内容变更打破**，且失败信息都指向错误的方向。

### 8.1 `SMG_RELEASE_SETTLE_STEPS = 30` 写死了手枪的档案

DONE 阶段有一条断言"后坐力已精确回落到 0"，收尾实测残留 `0.06999999999999794`。

- 30 步这个数的来历是**手枪**的档案：单发 0.90° / 上限 1.80° / 回落 5.00°/s ⇒ 每步 0.0833°，30 步能落 2.5° > 上限 1.80°，**必然归零**。
- 后坐力数据化之后，回落速度随枪而变。SMG 档案（上限 2.60° / 回落 2.40°/s ⇒ 每步 0.040°）下 30 步只能落 1.20°，**落不完**。
- 而它**当时并没有红**：收尾断言靠的是"距最后一次开火已隔若干阶段"，于是残余多少取决于 1800 步结束的那一刻后坐力锯齿波正好停在哪个相位。实测 0.07° = 锯齿某处的 1.27° 落掉 1.20°。**一条夹具假设，靠一个没人写下来的时序巧合成立。**
- 修法：窗口改为**从档案推导**，`ceil(上限 × 60 / 回落) + 余量` = `ceil(2.60 / 0.040) + 8` = 73 步。"改档案"与"改窗口"从此绑在一起。

### 8.2 `INVENTORY_CLOSE` 的守恒基准被合成阶段顶掉了

原断言是"关闭背包后槽位物品总数仍与取放前一致"，基准取自 `INVENTORY_INTERACT` 阶段起点。加上合成阶段之后，物品总数合法地变化了（4 原木 → 16 木板净 +12，铁锭与火药各净 −1，`89 → 99`），于是"合成真的生效了"这条**好消息**以"物品总数变了"的样子让断言变红。

修法不是放宽，而是**分段守恒**：

| 段 | 断言 | 基准 |
|----|------|------|
| 取放段 | 两次点击总数不变 | `INVENTORY_INTERACT` 起点 |
| 合成段 | 净变化 = 三条配方算出的净值 | `INVENTORY_CRAFT` 起点 |
| 关背包段 | 关闭本身不增减 | 合成阶段结束时 |

**启发（与本项目既有教训同型）**：改内容之前先问"哪些测试依赖了当前的槽位/数量布局"。失败现场指向玩法，真因可能在夹具。

---

## 9. 真人试玩清单（17 项，待主理人执行）

启动：双击 **`play-m3.bat`**（DEV 口径：无限后备 + 步枪 + 过渡材料包 + 独立世界 `m3-play`）。
若背包里没有步枪/材料，说明存档已存在——**删掉 `tmp\m3-play-saves`（或换 worldName）再跑**。

| # | 操作 | 期望 | 判据 |
|---|------|------|------|
| 1 | 看快捷栏 | 1 手枪 / 2 手枪弹 / 3 冲锋枪 / 4 步枪 / 5 步枪弹，6–9 空 | 4 个空格是硬要求（挖到的第一块要落得进快捷栏） |
| 2 | 按 4 持步枪，**点一下**左键 | 恰好一发 | `SINGLE` |
| 3 | 按 4 后**按住**左键 | **不得连发** | `SINGLE` |
| 4 | 按 3 持冲锋枪，**按住**左键 | 持续连发 | `AUTO` |
| 5 | 按 1 持手枪，点一下 | 恰好一发 | `SINGLE` |
| 6 | 步枪单发后坐 vs 手枪单发后坐 | 步枪**明显更重**且回落更快 | 1.60° vs 0.90°，回落 4.00 vs 5.00 |
| 7 | 按 3 持冲锋枪长按 | 每发很轻，但持续累积抬枪 | 0.35°/发，上限 2.60° |
| 8 | 停下不开火 | 后坐力**精确回到 0**（画面不再有残差） | 线性回落 + `max(0, …)` 夹紧 |
| 9 | 三把枪分别瞄准（按住右键） | FOV / 移速三组不同：45/×0.60、48/×0.65、40/×0.55 | 数值必须真的不同 |
| 10 | 三把枪开火 | 枪口火光位置各不相同，**步枪的闪光不从手枪的枪口冒出** | 三套独立枪口偏移 |
| 11 | 三把枪换弹 | 声音与时长按枪不同（手枪 1.2 s / 冲锋枪 1.5 s / 步枪 2.0 s） | 事件按 `*SoundId` 路由 |
| 12 | 按 E 打开背包 | 左侧 36 格，**右侧合成栏**列出全部 10 条配方 | 每行：图标 + 名称 + `×产出` + 材料 `8/8` + 状态 |
| 13 | 点材料齐的那一行 | 扣材料、加产物、状态**立刻刷新** | 例如 `原木 ×4 → 木板 ×16` |
| 14 | 点缺料的那一行 | **必须出声出字**：`缺少 铁锭 ×8、…`，且背包**一点不变** | 禁止"点了没反应" |
| 15 | 合成铁锭与火药 | `铁矿石 9→8 / 煤炭 20→19 / 铁锭 0→1`；`煤炭 19→17 / 沙子 4→3 / 火药 0→2` | R03 / R06 |
| 16 | 换弹期间按住 W | 换弹**不被打断**（保持现状，非缺陷） | M2.2 口径 |
| 17 | 看启动日志 | 两行口径：`后备弹药口径` / `开局装备口径` | 遇到"怎么没步枪""怎么不扣弹"先看这两行 |

**执行前提醒**：清单第 2/3/6/8/9/10/11 项验证的是**本轮新接线的可感知行为**；第 12–15 项验证的是本轮最高优先级任务（Crafting 产品接线）。全部通过才算 §19-14 的人判给出。

---

## 9.5 追加（2026-10-04）：设置界面文字重叠已修，另立报告

主理人报障"设置界面的文字有一点重叠,做的好看一些"，已单独处理完毕。

**根因不是留白，是 2 倍字在任何分辨率下都排不下**（实测 5 档，1080p 缺 392px）；
已改为**文字倍数按式样定档**（`COVER`=2 / `PANEL`=1），并把"文字画在哪、用几倍字"
从渲染器收进 `MenuLayout`（`rowTextY` / `rowTextScale` / `rowRuleY` 三数组，渲染器只读不算）。

- 单测 **1156 / 0 / 0**（+8）
- 门禁 **m1 60 / ui 179 / m2 387 行 PASS，0 FAIL**（与本报告第 10 节基线**完全一致**）
- 反向验证 R6a/R6b 全部命中并逐字节恢复，**主源码残留标记 0**
- ★ 关键对照：把旧 bug 注回渲染器后 **`MenuLayoutTest` 25/25 依然全绿** ⇒ 布局测试结构上
  抓不到渲染器回归，故新增 `MenuRendererTextGeometryWiringTest` 站在两者之间

**完整根因、实测表、方案取舍与新增断言见 `docs/testing/SETTINGS_TEXT_OVERLAP_REPORT.md`。**
最终观感仍需主理人眼睛确认（同第 9 节的 17 项，属人眼与人手范围）。

---

## 10. 停止声明

本轮已按裁定完成全部 22 条：

- 4 项任务全部落地，**没有新增第 4 把枪**，**没有新增配方**，**`ItemRegistry` 仍是 28**。
- 单测 **1148 / 0 / 0**（基线 1080 / 0 / 0，只增不减）。
- 门禁 **m1 60 / ui 179 / m2 387 行 `PASS`，0 `FAIL`**；m2 权威计数 **193 断言 / 0 失败**。
- 五轮反向验证 R1 / R2 / R3 / R4 / R5 全部命中并逐字节恢复，**残留标记 0**。
- 两个入口（`play-m3.bat` 与桌面 exe）口径已对齐，由 `NativeLauncherWiringTest` 第 6 条钉住。
- CJK 字模已重烘焙，**没有删任何中文**。
- 唯一未完成项是**第 20 条真人试玩（17 项）**——它需要人眼与手，只能由主理人执行；本报告第 9 节即清单。

**Pre-M3 Integration Closure 已完成，等待 M3 Vertical Slice 指令。**

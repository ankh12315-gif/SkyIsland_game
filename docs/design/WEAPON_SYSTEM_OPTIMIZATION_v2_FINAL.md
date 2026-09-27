# 武器系统优化与设计文档（WEAPON-DOC-001 · v2 主理人裁定版）

| 项目 | 内容 |
|------|------|
| 文档类型 | 系统设计 / 架构优化（**M3 前瞻，主理人已裁决**） |
| 项目 | SkyIsland（Java 21 + LWJGL 自研体素引擎，Maven fat-jar） |
| 当前版本 | `0.3.2-M2_2-UI-INVENTORY` |
| 原作者 | 程基岩（engineering-lead） |
| 修订 | 主理人评审后定稿 |
| 日期 | 2026-09-27 |
| 状态 | **APPROVED FOR M3 IMPLEMENTATION**；本文件可直接作为 WorkBuddy 后续武器系统实施依据 |
| 上游基线 | `docs/design/PRD_v0.3.2.md`、`docs/architecture/TECH_DESIGN_v0.1.md`、`docs/architecture/technical-constraints.md` |
| 关联边界 | M2.2 仍禁止第二把枪；本文件从 **M3 Vertical Slice** 起生效 |

---

## 0. 主理人裁决摘要（本版冻结）

本版不采用原草案“**M3 直接扩到 7 把枪**”的路线。武器系统在 M3 的目标是：

> **先完成数据驱动泛化，再用“手枪 + 1 把新枪”验证多武器架构、有限弹药、生存资源闭环和第一人称表现层。**

冻结以下决策：

1. **M3 只落地 2 把可玩枪：手枪 + SMG。**
   - 手枪：既有基线。
   - SMG：作为第二把枪，验证 `AUTO`、不同弹匣/射速/换弹时长、独立 Viewmodel / 图标 / 音效表现。
   - 步枪 / 霰弹枪仍属于后续 Production/Alpha 内容。
   - 机枪 / 加特林 / 狙击枪只保留为 Backlog Proposal，**不写入 M3 实施任务，不回写 PRD 为承诺内容**。
2. **M3 Survival Mode 恢复有限后备弹药。**
   - M2.1 Combat Prototype 的无限后备弹只保留为调试/原型模式。
   - 正式 M3 核心循环必须让“资源 → 弹药 → 战斗”成立。
3. **移动不打断换弹（保持 M2.2 现状）。**
   - 这是 M2.2 用户亲口裁决 + 已实现提交 `b487188` 的既有口径：按 R 后移动/跳跃/转向**不**打断换弹，换弹走满即完成。
   - 与 `docs/design/PRD_v0.3.2.md` 行 526（PRD v0.3.2-r1 §5.4.3）一致；不回滚为“边跑边换弹被取消”。
4. **所有会在 Survival Mode 中实际消耗的 `ammoId` 必须对应真实注册的 `Item`。**
   - 禁止只注册一个字符串给 HUD，却让有限弹药路径无法从 Inventory 扣除。
5. **M3 只支持 `SINGLE` 与 `AUTO`。**
   - `BURST` 暂不实现；没有实际武器使用的功能不提前造死代码。
6. **新增 `GunPresentationSpec`。**
   - `GunSpec` 负责战斗逻辑。
   - `GunPresentationSpec` 负责 Viewmodel / 图标 / 枪口位置 / 音效 / 视觉后坐等表现。
7. **`attackPressed` 必须进入既有 Frame → Logic 一次性输入缓冲/发放链。**
   - 禁止直接使用“只活一个渲染帧”的裸布尔值，避免高 FPS 下偶发吞枪。
8. **ADS 使用“绝对目标 FOV”语义。**
   - `aimFovDeg` 为目标视场角；运行时使用 `min(1.0, aimFovDeg / baseFovDeg)`。
9. **原 PRD 的 4 把枪数量不因本文件自动修改为 7。**
   - M3 只验证 2 把。
   - Alpha/Production 再逐步补齐 PRD 中其余武器。

---

## 1. 概述与目标

### 1.1 M3 的真正目标

把当前“**单枪（手枪）存在少量硬编码**”的系统泛化为：

- 战斗逻辑对枪种零感知；
- 状态层按武器实例/运行时 ID 管理；
- 弹药由 `GunSpec.ammoId` 数据驱动；
- 开火模式由 `GunSpec.fireMode` 驱动；
- 瞄准参数由枪械数据驱动；
- 第一人称持枪、HUD 图标、枪口位置、音效等由独立表现配置驱动；
- 在 M3 只新增 **SMG** 一把武器，证明系统可以扩展，但不扩散内容范围。

### 1.2 M3 范围

**M3 必做：**

- `GunSpec` 泛化；
- `GunState` 去手枪硬编码；
- `GunPresentationSpec`；
- `SINGLE` / `AUTO`；
- `attackPressed` 的可靠帧→逻辑发放；
- M3 Survival Mode 有限备弹；
- 手枪 + SMG；
- 两把枪独立 Viewmodel / 图标 / 枪口 / 音效事件；
- HUD 读取当前枪械与当前弹药；
- 存档兼容；
- runtimeId 稳定；
- 自动测试 + 真人试玩。

**M3 不做：**

- 第 3 把及更多枪；
- `BURST`；
- 机枪 / 加特林 / 狙击枪；
- 过热、spin-up、复杂 recoil pattern；
- 武器配件；
- 正式武器美术生产；
- 完整平衡工程。

### 1.3 与 M3 Vertical Slice 的关系

武器系统不是 M3 的主角。M3 的主目标仍是：

> 主岛 / 小屋 → 石矿岛 → 采资源 → 背包 → 合成 → 制造弹药 → 昼夜 → 夜间怪物 → 守家 → 活到第二天。

因此武器扩展必须服务于这条 Vertical Slice，而不是把 M3 变成“军械库阶段”。

---

## 2. 现状与痛点（沿用实读代码结论）

### 2.1 `GunSpec`

当前：

```java
public record GunSpec(
    int damage,
    int magazineSize,
    double fireRate,
    int range,
    double reloadSeconds
)
```

不足：

- 无开火模式；
- 无弹丸数；
- 无散布入口；
- 无枪械独立 ADS FOV；
- 无弹药 ID；
- 无枪械独立 ADS 移速倍率。

### 2.2 `GunState`

当前已有：

- `GunState(Item)`；
- `GunState(Item, boolean)`；
- `ShotOutcome`；
- `ReloadOutcome`；
- 换弹计时；
- 无限/有限后备路径。

现存问题：

- 有限后备通过 `PISTOL_AMMO_ID` 硬编码；
- `forPistol()` / `forPistolWithFiniteReserve()` 将具体枪种焊进状态类；
- 当前默认无限后备适合 M2.1 原型，不适合作为 M3 Survival 默认。

### 2.3 `CombatController`

现状优势：

- `gunStates` 已按 `runtimeId` 分枪；
- Hitscan / DamageFalloff 已独立；
- 核心结构已有泛化基础。

现存问题：

- 所有枪都使用 `attackHeld()`；
- “单发”与“全自动”没有语义区别；
- `attackPressed()` 尚未形成可靠的逻辑步事件；
- 当前枪口/视觉表现与逻辑 Spec 尚未形成统一的数据接口。

### 2.4 `ItemRegistry`

必须保持：

- `runtimeId == BY_RUNTIME_ID 下标`；
- 新 Item 只允许**尾部追加**；
- 既有 `pistol` runtimeId 不变；
- stable ID 是存档权威标识。

### 2.5 第一人称表现层

M2.1 已具备：

- 右手持枪 Viewmodel；
- 开火后坐；
- reload dip；
- ADS；
- 枪口闪光；
- 音频链。

新问题是：增加第二把枪后，不能让所有武器继续共享“同一把手枪的视觉外观”。

---

## 3. `GunSpec` v2 设计

### 3.1 M3 字段集合

```java
public record GunSpec(
    int damage,
    int magazineSize,
    double fireRate,
    int range,
    double reloadSeconds,
    FireMode fireMode,
    int pelletCount,
    double spreadRad,
    double aimFovDeg,
    double aimMoveSpeedMult,
    double falloffPerUnit,
    double falloffFloor,
    String ammoId
) {}
```

### 3.2 `FireMode`

M3 只允许：

```java
public enum FireMode {
    SINGLE,
    AUTO
}
```

不实现：

```text
BURST
```

原因：当前没有任何 M3 武器需要三连发。等真正设计出 Burst 武器再加入，避免再次产生“写了但没调用”的死代码。

### 3.3 字段语义

| 字段 | 语义 |
|---|---|
| `damage` | 每弹丸基础伤害 |
| `magazineSize` | 弹匣容量 |
| `fireRate` | 每秒最大击发次数 |
| `range` | 有效射程基准 |
| `reloadSeconds` | 完整换弹耗时 |
| `fireMode` | `SINGLE` / `AUTO` |
| `pelletCount` | 单次击发弹丸数；M3 两把均为 1 |
| `spreadRad` | 散布半角；M3 建议全部为 0，先保持“准星指向即命中” |
| `aimFovDeg` | ADS 绝对目标 FOV |
| `aimMoveSpeedMult` | ADS 时玩家移速倍率 |
| `falloffPerUnit` | 超出有效射程后的逐格衰减倍率 |
| `falloffFloor` | 最低伤害比例 |
| `ammoId` | 对应真实弹药 Item stable ID |

### 3.4 校验

必须：

- `damage > 0`
- `magazineSize > 0`
- `fireRate > 0`
- `range > 0`
- `reloadSeconds > 0`
- `fireMode != null`
- `pelletCount >= 1`
- `spreadRad >= 0`
- `aimFovDeg > 0 && aimFovDeg < 180`
- `aimMoveSpeedMult > 0 && aimMoveSpeedMult <= 1`
- `falloffPerUnit > 0 && falloffPerUnit <= 1`
- `falloffFloor >= 0 && falloffFloor < 1`
- `ammoId != null && !ammoId.isBlank()`

### 3.5 M3 兼容性

手枪必须保持当前体验基线：

- damage = 8
- magazine = 12
- fireRate = 4.0
- range = 32
- reload = 1.2
- fireMode = SINGLE
- aimFov = 45
- aimMoveSpeedMult = 0.60
- falloff = 0.90 / 0.20
- ammoId = `skyisland:pistol_ammo`

---

## 4. `GunPresentationSpec`：逻辑与表现分层

### 4.1 为什么必须独立

`GunSpec` 是战斗规则，不应承载 Viewmodel 资源和音效资源。

新增：

```java
public record GunPresentationSpec(
    String viewmodelId,
    String iconId,
    Vec3 muzzleOffset,
    String fireSoundId,
    String emptySoundId,
    String reloadSoundId,
    String recoilProfileId
) {}
```

具体类型可按现有工程工具类调整，但职责必须保持。

### 4.2 M3 必须表现差异

手枪和 SMG 至少必须在以下 4 项中有明显区别：

1. Viewmodel 轮廓；
2. HUD / Inventory 图标；
3. 枪口位置；
4. 开火声音或射击节奏表现。

禁止出现：

> “逻辑上是 SMG，右手仍然是一模一样的手枪模型。”

### 4.3 不要求最终美术

M3 允许程序化 / 体素灰盒 Viewmodel。

目标是：

> 玩家一眼知道自己当前拿的是哪把枪。

---

## 5. `GunState` 去手枪硬编码

### 5.1 删除手枪工厂硬编码

删除：

- `forPistol()`
- `forPistolWithFiniteReserve()`
- `reserveAmmo()` 中对 `PISTOL_AMMO_ID` 的硬读

统一使用：

```java
new GunState(Item gun, boolean reserveInfinite)
```

### 5.2 有限弹药读取

有限模式：

```text
gun.spec().ammoId()
→ ItemRegistry.require(ammoId)
→ Inventory.count/remove
```

必须保证：

> Survival Mode 中所有实际消耗的 `ammoId` 都能在 ItemRegistry 找到真实 Item。

如果不存在：

- 开发/测试环境直接失败；
- 禁止静默显示 HUD 标签但实际无法扣弹。

### 5.3 无限后备只属于 Prototype / Debug

保留无限模式能力，但不再把它定义为正式 Survival 默认：

```text
M2.1 / Combat Debug:
reserveInfinite = true

M3 Survival:
reserveInfinite = false
```

建议把“是否无限后备”从 `GunState` 的全局默认常量提升为上层明确传入的 Combat Rule / Run Mode 配置。

### 5.4 换弹中断

冻结行为（**移动不打断换弹**）：

> **玩家产生移动输入时，当前 Reload 照常进行、不被取消。**

要求：

- 按 R 后移动 / 跳跃 / 转向**不**打断换弹，1.2 s（手枪）走满即完成；
- **弹药只在完成这一刻转移**；完成前不转移（见 §12 规则「完成前不转移」）；
- 换弹期间**不得开枪**；
- 满弹匣按 R 无操作；
- 不引入 `cancelReload()` —— 无取消口，因此不需要取消 API，符合项目零死代码标准。

口径来源：

- M2.2 实现提交 `b487188`；
- `docs/design/PRD_v0.3.2.md` 行 526（PRD v0.3.2-r1 §5.4.3「换弹与移动」）；
- 用户 2026-09-27 再次明确澄清：保持“不打断”现状，不回滚。

> 注：`GunState.tick` 收敛为 `tick(double, Inventory)`（无 `moving` 参数）；M2.2 已删除 `cancelReload()` / `reloadsCancelled` / `onReloadCancelled()` 等相关取消分支。

---

## 6. 弹药模型

### 6.1 M3 只需要一种实际弹药

M3 使用：

```text
skyisland:pistol_ammo
```

手枪与 SMG 共用该弹药。

这样：

- 不扩大 M3 配方数量；
- 不新增 `rifle_ammo` / `shell` 的资源链；
- 仍能验证“多枪共用弹药 Item”的数据驱动能力；
- 资源 → 弹药 → 战斗闭环保持简单。

### 6.2 后续扩展规则

以后真正落地 rifle / shotgun 时：

- `skyisland:rifle_ammo` 必须注册为 Item；
- `skyisland:shell` 必须注册为 Item；
- 再新增相应 Recipe。

禁止只添加字符串标签。

### 6.3 HUD

HUD 不再硬读 `PISTOL_AMMO_ID`。

改为：

```text
heldGun.spec().ammoId()
→ Inventory reserve count
```

Prototype 无限后备：

```text
12 / ∞
```

M3 Survival：

```text
12 / 24
```

---

## 7. 输入与开火语义

### 7.1 `SINGLE`

手枪：

> 鼠标左键按下一次，只发射 1 发。

必须消费：

```text
attackPressed
```

不能继续使用 `attackHeld()`。

### 7.2 `AUTO`

SMG：

> 按住左键，按 `fireRate` 连续射击。

消费：

```text
attackHeld
```

### 7.3 `attackPressed` 的可靠输入链

这是硬要求。

此前项目已经证明：

> Render FPS > Logic TPS 时，一次性输入若只存在一个渲染帧，会在“本帧 0 个逻辑步”时丢失。

因此：

```text
OS / GLFW
→ InputState edge
→ Frame-level one-shot latch/buffer
→ next eligible Logic Tick
→ PlayerIntent.attackPressed
```

要求：

- 一个物理点击最多发放一次；
- 不因 0 logic-step render frame 丢失；
- 不因一帧多个 logic step 重复发放；
- 失焦时清理未完成输入状态；
- 复用现有 Frame→Logic 发放框架，不另造第二套脆弱逻辑。

---

## 8. Hitscan / 散布 / 弹丸

### 8.1 M3 原则

M3 的目标是 Vertical Slice，不是枪械平衡实验。

因此冻结：

- 手枪 `pelletCount = 1`, `spreadRad = 0`
- SMG `pelletCount = 1`, `spreadRad = 0`

也就是说 M3 仍保持：

> **准星指向即命中。**

### 8.2 为什么不在 M3 提前做散布

原草案为了未来机枪/加特林提前引入随机散布，会增加：

- RNG；
- 可重复性；
- 命中分布测试；
- 调枪平衡；
- 手感争议。

这些都不是当前 M3 核心循环的必要条件。

### 8.3 `pelletCount` 字段为什么仍保留

保留字段是为了未来 shotgun 数据表达，不等于 M3 必须启用多弹丸。

如果项目纪律要求“未使用字段也不留”，则可把 `pelletCount/spreadRad` 延迟到真正落地 shotgun 的 PR；两种做法均可，优先遵循仓库现有零死代码标准。

---

## 9. ADS / FOV

### 9.1 语义

`aimFovDeg` 为：

> **绝对目标 FOV**

运行时：

```text
scale = min(1.0, aimFovDeg / playerBaseFovDeg)
```

因此：

- 玩家 base FOV = 70，手枪 aim=45 → 45°；
- 玩家 base FOV = 90，手枪 aim=45 → 45°；
- 若未来错误配置 aimFov > baseFov，则不会“开镜反而拉远”。

### 9.2 M3 两把枪

建议：

| 武器 | aimFovDeg | aimMoveSpeedMult |
|---|---:|---:|
| 手枪 | 45 | 0.60 |
| SMG | 48 | 0.65 |

SMG 数值属于建议值，M3 人工试玩后可微调。

---

## 10. M3 武器表（只冻结 2 把）

> 手枪参数来自既有基线；SMG 参数来自原设计草案/PRD 方向，M3 作为第二把验证枪，试玩可微调。

| 枪械 | stable ID | 模式 | damage | mag | fireRate(/s) | range | reload | pellet | spread | aimFov | aimMove | ammoId | 定位 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
| 手枪 | `skyisland:pistol` | SINGLE | 8 | 12 | 4.0 | 32 | 1.2s | 1 | 0 | 45 | 0.60 | `skyisland:pistol_ammo` | 开局基准 |
| SMG | `skyisland:smg` | AUTO | 5 | 24 | 10.0 | 24 | 1.5s | 1 | 0 | 48★ | 0.65★ | `skyisland:pistol_ammo` | 近中距持续输出 |

★：M3 试玩可调，不在进入 M3 前继续纸面优化。

### 10.1 为什么选 SMG，而不是 Shotgun

M3 第二把枪优先选择 SMG，因为它以最低额外复杂度验证：

- `SINGLE` vs `AUTO`；
- `attackPressed` vs `attackHeld`；
- 不同射速；
- 不同弹匣；
- 不同换弹时间；
- 独立 Viewmodel / 图标；
- 共用真实弹药 Item；
- 多枪切换。

而 Shotgun 会额外引入：

- 多弹丸；
- 散布；
- shell Item；
- 新配方；
- 近距多丸伤害聚合。

这些放到 M3-GATE 之后更合适。

---

## 11. `ItemRegistry` 与稳定 ID

### 11.1 M3 只新增一个枪 Item

在现有 `pistol` 后：

```text
skyisland:smg
```

只追加，不插队。

### 11.2 runtimeId 锁定

新增测试：

- `pistol.runtimeId` 与 M2.2 一致；
- `smg.runtimeId > pistol.runtimeId`；
- `runtimeId == BY_RUNTIME_ID index`；
- registry size 只增加预期数量。

### 11.3 存档

存档继续以 stable ID 为权威。

新增 SMG 后验证：

```text
拿到 SMG
→ 放进 Hotbar / Inventory
→ Save
→ Exit
→ Relaunch
→ 同槽位仍为 skyisland:smg
```

---

## 12. 换弹统一规则（M3 冻结版）

| 规则 | M3 口径 |
|---|---|
| 弹匣已满按 R | 无操作 |
| 弹匣未满且后备 > 0 | 开始换弹 |
| 后备不足一个整弹匣 | 部分填充 `load = min(need, reserve)` |
| 完成前 | 不提前转移弹药 |
| 换弹期间开火 | 拒绝 |
| **移动输入出现** | **不打断换弹**（进度照走） |
| 换弹完成时 | 弹药转移（`弹匣 += load`，`后备 -= load`） |
| M2.1 Debug 无限备弹 | 保留能力 |
| M3 Survival | 有限备弹 |

必须对 pistol 与 SMG 都成立。

---

## 13. 后续武器 Backlog（不属于 M3 承诺）

以下只保留为方向，不进入 M3：

| 武器 | 阶段建议 | 需要的新机制 |
|---|---|---|
| Rifle | Production / Alpha | 新弹药 Item；更长射程 |
| Shotgun | Production / Alpha | pelletCount；spread；shell；多丸聚合 |
| Sniper | Production 后段 | 强 ADS；远距平衡；可能限制 hip-fire |
| Machinegun | 后续 | 持续压制；更强 recoil / 移速惩罚 |
| Gatling | 更后 | spin-up / heat 至少一个，否则与机枪只有数值差 |

重要：

> Backlog 不等于 PRD 承诺，不得因为本文存在这些名字就自动实现。

---

## 14. 测试与反向验证

### 14.1 `GunSpec`

至少测试：

- pistol 全字段；
- smg 全字段；
- 非法 damage；
- 非法 mag；
- 非法 fireRate；
- 非法 reload；
- 非法 aimFov；
- 非法 ammoId；
- `FireMode` 不可空。

### 14.2 `SINGLE`

必须证明：

- 按住左键不连续喷手枪；
- 点击一次只发一发；
- 快速点击仍受 cooldown；
- 0 logic-step render frame 中的 press 不丢；
- 一帧多 logic-step 不重复发放同一次 press。

### 14.3 `AUTO`

必须证明：

- SMG 按住连续开火；
- 松开立即停止；
- fireRate 节流正确；
- 换弹中不能开火。

### 14.4 Reload

至少：

- full mag R 无操作；
- partial reload；
- insufficient reserve 部分填充；
- movement does **not** interrupt reload（边走边换，走满完成）；
- 未完成前不转移 ammo，完成时才转移；
- pistol / smg 都通过。

### 14.5 Ammo

必须证明：

- M2.1 Debug infinite 不扣 Inventory；
- M3 Survival finite 会扣 Inventory；
- HUD reserve 与 Inventory 一致；
- ammoId 不存在时测试必须失败，不允许静默 fallback。

### 14.6 Presentation

必须证明：

- pistol / smg Viewmodel 不同；
- icon 不同；
- muzzleOffset 不同；
- 切枪后 HUD、Viewmodel、GunState 同步；
- 枪口特效从对应枪口位置发出，不回归“火光生成在相机眼睛处”的 D9 类缺陷。

### 14.7 存档

- pistol 状态存读；
- smg Item stable ID 存读；
- Inventory / Hotbar 槽位保持；
- 世界重启后当前选中枪正确。

### 14.8 反向验证

沿用项目铁律：

> 新断言必须做破坏注入 → 精确变红 → 恢复 → 全绿。

尤其检查：

- 把 `SINGLE` 临时改回 `attackHeld`，测试必须红；
- 临时注入“移动取消 reload”的取消分支，`walkingDoesNotInterruptReload` 类测试必须红；
- 把 SMG viewmodelId 改成 pistol，Presentation 测试必须红；
- 把 ammoId 改成不存在 ID，有限备弹测试必须红。

---

## 15. 性能约束

M3 武器层只增加 1 把 AUTO 枪，不应形成明显性能压力。

仍需记录：

- Combat update time；
- Hitscan time；
- tracer / muzzle FX count；
- Audio event count；
- p95 / p99 frame time；
- GC；
- 连续按住 SMG 30 秒的稳定性。

禁止因为高射速增加每发临时对象分配。

---

## 16. PRD 一致性处理

### 16.1 本版不再建议“4 把 → 7 把”

原草案 §11.1 的 7 枪扩张建议撤销。

PRD v0.3.2 的长期武器内容仍以原产品路线为基线。

M3 仅把第二把枪提前作为：

> **架构验证枪 / Vertical Slice 战斗差异验证**

不要因此把 machinegun / gatling / sniper 写进 PRD 承诺。

### 16.2 无限弹口径

PRD/技术文档需要明确区分：

- Prototype Debug：无限后备；
- Survival Vertical Slice：有限后备。

避免以后再次出现“无限弹导致资源循环失去意义”的规格漂移。

### 16.3 Reload

所有文档统一为：

> **移动不打断换弹。**

权威来源：`docs/design/PRD_v0.3.2.md` 行 526（PRD v0.3.2-r1 §5.4.3）、M2.2 实现提交 `b487188`。

若发现任何仍写“移动打断换弹”/“立即取消换弹”的文档，一律以本口径修正。

---

## 17. M3 实施切片（WorkBuddy 执行顺序）

> 以下顺序冻结。每个 Story 单独跑测试；不允许第 1 步失败却继续堆后续功能。

| # | Story | 内容 | Gate |
|---|---|---|---|
| 1 | 文档一致性 Closure | 搜索并统一“移动是否打断换弹”“M3 是否无限弹”“7 枪是否承诺”等旧口径 | 0 known conflicts |
| 2 | 泛化 `GunSpec` | 加 M3 必需字段；只保留 SINGLE/AUTO | pistol 全量回归 |
| 3 | 泛化 `GunState` | 去 pistol ammo 硬编码；显式 infinite/finite；移动取消 reload | finite/infinite + cancel 全绿 |
| 4 | 输入可靠性 | `attackPressed` 接入既有 frame→logic latch | 单发不丢、不重复 |
| 5 | `GunPresentationSpec` | 表现层配置；先迁移 pistol | pistol 视觉/音频无回归 |
| 6 | 新增 SMG | registry 尾部追加；spec + presentation + 中文名 + icon/viewmodel | pistol + smg 都可玩 |
| 7 | HUD / Inventory / Save | 当前枪、弹药、图标、存档全部数据驱动 | save round-trip |
| 8 | Survival Ammo | M3 游戏模式切有限后备；弹药真实消耗 | 资源→弹药→战斗链成立 |
| 9 | 性能与门禁 | 全量测试、M1/M1.5/M2/M2.1/M2.2 回归、性能 | 无回归 |
| 10 | 真人试玩 | 手枪/SMG切换、换弹、资源消耗、战斗手感 | PASS / PASS WITH TODO |

---

## 18. WorkBuddy STOP 规则

完成本文件的武器泛化后：

**允许继续的只有 M3 Vertical Slice 既定内容**：

- 主岛；
- 小屋；
- 石矿岛；
- 资源采集；
- 配方；
- 昼夜；
- 夜间刷怪；
- 资源核心；
- Save/Load 扩展。

**禁止自行继续扩枪：**

- rifle；
- shotgun；
- machinegun；
- gatling；
- sniper；
- burst；
- weapon attachments。

除非主理人后续显式放行。

---

## 19. M3 武器模块通过标准

只有以下全部成立，才可认为“武器系统泛化”完成：

1. 手枪仍保持 M2.1 基线体验；
2. SMG 可正常获得/持有/显示/射击/换弹/存档；
3. 手枪单击单发，不因高 FPS 吞输入；
4. SMG 按住全自动；
5. 移动**不**打断换弹（边走边换，走满即完成）；
6. M3 Survival 弹药有限并真实从 Inventory 消耗；
7. Debug 模式仍可无限备弹；
8. 两把枪的 Viewmodel / 图标 / 枪口位置有明显差异；
9. HUD 不硬编码手枪；
10. ammoId 不硬编码手枪弹；
11. runtimeId 稳定；
12. Save/Load round-trip 通过；
13. 全量测试与历史 Gate 无回归；
14. 真人试玩能明确感知两把枪的差异；
15. 未偷跑第 3 把枪及以后内容。

---

## 20. 最终结论

本版最终采用：

> **“先泛化、少扩枪、恢复生存弹药、表现与逻辑分层、输入可靠优先”**

而不是原草案的：

> “M3 一次性扩到 7 把枪”。

这使武器系统重新服从 SkyIsland 的核心开发目标：

> **先证明 20–25 分钟空岛生存 Vertical Slice 好玩，再进入 Full Production 扩充武器内容。**

---

*WEAPON-DOC-001 v2：主理人裁定版。可直接交给 WorkBuddy 作为 M3 武器系统实施依据。若代码实读发现本文件中的具体类名/行号已因 M2.2 后续提交发生变化，以“职责与冻结口径”为准，不得借机改变上述产品裁决。*

# S8b 创造五项能力 + 飞行 —— 落地报告

> 阶段：M4-ART（PRD_BLOCK_CREATIVE_v1.0 §8）→ S8 的第二半，依赖 S8a（区块流式）。
> 日期：2026-10-08　|　代码：`player/Player.java`（能力主体）、`game/SkyIslandGame.java`（门控 + HUD + 提示）、
> `player/PlayerIntent.java`（第 18 个组件 `sneak`）、`input/InputMapper.java`（Shift 采样）、`ui/Localization.java`（两条提示）
> 守卫：`CreativeAbilitiesTest`(17) / `CreativeAbilitiesWiringTest`(11)
> 单测总量 **1407 / 0 失败 / 0 错误**；门禁 m1 60 / ui 179 / m2 387 行 PASS，0 FAIL。

---

## 1. 逐条对账（PRD §5.2–§5.5）

| PRD 条款 | 规格 | 落地位置 | 行为守卫 | 断线 |
|---|---|---|---|---|
| §5.2 破坏瞬时 | 立即完成，仍掉落 | `Player.updateMining` 的 `if (creativeMode)` 分支 + `executeBreak(world, block)` | `creativeBreaksABlockInASingleStep` | J |
| §5.2 资源核心例外 | `breakable = false` **不被**创造模式覆盖 | 同一分支排在 `isBreakable()` 判定**之后** | `theResourceCoreIsStillUnbreakableInCreativeMode` | J |
| §5.2 冷却 | 按住不放不应每步一个 | `CREATIVE_BREAK_COOLDOWN_SECONDS = 0.15` | `holdingAttackIsRateLimitedInsteadOfBreakingEveryStep` | — |
| §5.3 放置不消耗 | 面板 ∞、手持不减 | `Player.handlePlacement` 的 `if (!creativeMode)` | `creativePlacementDoesNotConsumeTheStack` | M |
| §5.4 可飞行 | 双击空格切换 | `Player.updateFlightToggle(intent, dt)` | `doubleTappingJumpTogglesFlight` / `aSingleJumpDoesNotToggleFlight` | O |
| §5.4 垂直可控 + 无重力 | 空格上升 / Shift 下降 | `Player.applyFlightVertical` **替换** `applyJump` + `applyGravity` | `flyingFollowsTheVerticalKeysAndIgnoresGravity` | N |
| §5.4 仅创造 | 生存模式拒绝起飞 | `setFlying` 的 `if (enabled && !creativeMode)` | `flightIsRefusedWithoutCreativeMode` | P |
| §5.4 关闭即停飞 | 关创造不得以生存身体继续飞 | `setCreativeMode(false)` 连带 `flying = false` | `turningCreativeOffStopsFlight` | Q |
| §5.5 免疫伤害 | 怪物攻击不扣血 | `Player.hurt(World, int, DamageCause)` 开头 | `creativeModeNegatesAllDamage` | K |
| §5.5 虚空例外 | 不死亡，停在虚空底部 | `Player.checkVoid` 开头整段例外 | `creativeModeParksAtTheBottomOfTheVoidInsteadOfDying` / `thePlayerCanFlyOutOfTheVoid` | L |
| §5.4 落地不受伤 | 飞行不累计坠落距离 | `updateFallState` 开头 `if (flying) { fallDistance = 0; return; }` | `flyingDoesNotAccumulateFallDistance` | — |

> **每一条都配了生存模式对照**（`survivalStillNeedsTheFullMiningTime` / `survivalPlacementConsumesExactlyOne` /
> `survivalModeStillTakesDamage` / `survivalModeStillDiesInTheVoid`）。
> 没有对照的"创造模式成立"证明不了任何事 —— 它也可能只是因为那条代码路径根本没被调用。

---

## 2. 四条设计裁定

### ★ 裁定一：能力开关放 `Player`，不放 `SkyIslandGame`

五个能力里有四个（破坏 / 免疫 / 虚空 / 飞行）都要在**物理步内部**做决定，
而物理步在 `Player.step()` 里。把开关放在游戏层意味着每一步都要回问游戏层一次，
并且新增任何一条"只影响物理"的规则都得改两个文件。
放 `Player` 上后，`SkyIslandGame` 只有一句门控：

```java
player.setCreativeMode(saveManager.effectiveGameMode() == GameMode.CREATIVE);
```

★ 这句的门控判据与 S7 创造面板**是同一个取值**（`effectiveGameMode()`，存档定死），
不是 `-Dskyisland.gameMode` —— 否则「创造存档 + survival 开关」会让"有面板却没能力"。
守卫：`CreativeAbilitiesWiringTest#theGameSwitchesTheAbilitiesWithTheSameGateAsThePalette`。

### ★ 裁定二：瞬时破坏分支必须排在 `isBreakable()` 之后

排到前面的代码看起来完全合理（`if (creativeMode) { break; return; }`），
但后果是**创造模式能挖掉资源核心** —— PRD §5.2 明文写了 `breakable = false` 不被创造覆盖，
§5.1.1 又写了资源核心"玩家无法通过任何途径获得"。
也就是说：一个看起来无害的分支顺序，会变成**用 UI 绕过 PRD 硬约束**的又一条路。
守卫：`theInstantBreakBranchSitsAfterTheUnbreakableCheck`（扫描 `updateMining` 方法体，剥注释）。

### ★ 裁定三：飞行必须**替换**「跳跃 + 重力」，不能叠加

叠加的表现是：飞行中按住空格会同时拿到上升速度与起跳初速，
**一按就往上弹一下**，而且只在贴地时发生 —— 试玩时会被当成"飞行手感有点怪"，
没人会想到是跳跃没有被替换掉。
守卫：`flightReplacesJumpAndGravityInsteadOfStackingOnTopOfThem`。

配套的两条：

- **飞行开关必须排在移动之前判定**（`theFlightToggleRunsBeforeMovement`）。
  否则本步起飞却仍按走路速度移动，起飞那一步会"顿"一下。
- **飞行中的升降不得累计坠落距离**（`flyingDoesNotAccumulateFallDistance`）。
  否则"飞高 → 关飞行 → 落地"会结算一大截坠落伤害。创造模式免疫了它，
  但那个数会留在 HUD 上，看起来像 bug。

### ★ 裁定四：双击判据是"两次**按下沿**之间的间隔"，不是"按住时长"

生存模式里最常见的操作是**长按空格连跳**。
若用"按住时长 ≥ T 判为双击"，长按会被误判成双击 —— 生存模式于是变成了飞行模式。
正确判据：记录上一次**按下沿**的时刻，本次按下沿与它的间隔 ≤ `DOUBLE_TAP_SECONDS`（0.3 s）才算双击。
守卫：`aSingleJumpDoesNotToggleFlight`。

### ★ 附带裁定：`sneak` 是第 18 个组件，且必须给兼容构造

PRD §5.4 只写了"垂直速度可控"，**没指定下降键**。不补则玩家飞上去下不来 ——
飞行会变成一个只能上不能下的功能。取 Shift（与主流一致）需要把 `sneak` 传进 `PlayerIntent`。

`PlayerIntent` 是 record，组件里已有多个 `boolean`：**两个 boolean 换位置是静默的**，
编译器不报错，运行时语义全错。因此改动方式是**尾部追加 + 保留旧 16 参兼容构造**（默认 `sneak=false`），
而不是在中间插入。这样十几个自测调用点一行都不用改，也就没有"改漏一个"的机会。

---

## 3. 反向验证（9 条断线，全部 `ASSERTION_FAIL`，逐字节还原，残渣 0）

| # | 注入 | 变红的断言 |
|---|---|---|
| I | `start()` 不再把能力开关交给玩家（五项能力永不生效） | `theGameSwitchesTheAbilitiesWithTheSameGateAsThePalette` |
| J | 瞬时破坏分支挪到「不可破坏」判定**之前** | `theInstantBreakBranchSitsAfterTheUnbreakableCheck` + `theResourceCoreIsStillUnbreakableInCreativeMode` |
| K | `hurt()` 不再免疫创造模式伤害 | `playerHurtCarriesTheImmunityGuard` + `creativeModeNegatesAllDamage` |
| L | `checkVoid()` 不再有创造模式例外（飞下虚空即死） | `checkVoidCarriesTheCreativeException` + `creativeModeParksAtTheBottomOfTheVoidInsteadOfDying` |
| M | 放置无论什么模式都消耗 | `placementSkipsConsumptionOnlyInCreativeMode` + `creativePlacementDoesNotConsumeTheStack` |
| N | 飞行不再替换「跳跃 + 重力」而是叠加 | `flightReplacesJumpAndGravityInsteadOfStackingOnTopOfThem` + `flyingFollowsTheVerticalKeysAndIgnoresGravity` |
| O | `step()` 不再驱动飞行开关（双击永远无效） | `theFlightToggleRunsBeforeMovement` + `doubleTappingJumpTogglesFlight` |
| P | `setFlying()` 不再拒绝非创造模式 | `setFlyingRefusesSurvivalMode` + `flightIsRefusedWithoutCreativeMode` |
| Q | 关掉创造能力时不停飞 | `turningCreativeOffAlsoStopsFlight` + `turningCreativeOffStopsFlight` |

★ 其中 **J 必须成对替换**（把创造分支插到前面 + 删掉原位置那一块）。
只做前一半会留下一段走不到的死代码，编译得过但语义不是"顺序错了" —— 那是假注入。

★ **注入脚本必须探测目标文件的行尾**（`Player.java` 是 CRLF、`SkyIslandGame.java` 是 LF）。
本轮第一轮注入 J–N、Q 全部 `NOT FOUND` 就是这个原因：脚本按 LF 拼模式串，
而 CRLF 文件里每一行的末尾多一个 `\r`。脚本加了 `adapt(text, crlf)` 后才命中。

还原后 `byte-identical=true`、`marker-free=true` 全部成立；全仓 `[RV-?]` 残渣 **0**。

---

## 4. 实测（创造模式 15 秒冒烟，1280×720，新世界）

```
游戏模式(配置)      : 创造
游戏模式(生效)      : 创造（由命令行 -Dskyisland.gameMode 指定（本存档首次创建，将写盘））
loaded_chunks       = 81
stream_chunks_load  = 81
stream_chunks_drop  = 0
perf_gate_met       = true
EXIT=0
```

创造模式没有给性能账增加任何新项：区块数与 S8a 站立基线一致（81），零 churn。
飞行本身是纯运动学计算，不触碰网格与光照 —— 它带来的额外开销是**每步几次浮点比较**。

---

## 5. 与 S8a 的关系（为什么必须先做流式）

飞行让玩家 20 秒就能飞出旧世界 64×64 的边界。在 S8a 之前，
`World.chunks` 是无界 `LinkedHashMap` 且没有卸载路径 ——
**先做飞行等于埋一颗内存定时炸弹**，表现是"越玩越卡"，
而玩家只会说"这游戏优化不行"。

反过来，S8a 的卸载也让本轮多了一条必须检查的东西：
**飞行中穿过的区块被卸载时，玩家的改动必须已经落盘**。
这条由 S8a 的 `World.ChunkUnloadListener` 保证（`releaseMesh` + `saveChunk`），
与 §5.3「放置不消耗」互不干涉：放置不消耗指的是**手持数量**，不是存档。

---

## 6. 遗留

- **飞行的真人手感未验证**：双击阈值 0.3 s、`FLY_SPEED = 9.0`、`FLY_VERTICAL_SPEED = 6.0`
  都是按走路速度（4.5）的两倍与 1.5 倍估的，需要真人试玩确认"不晕、不飘"。
- **`stream_chunks_drop` 仍然没有非 0 证据**（与 S8a 同源）：需要一次真人飞行/行走的长时运行。
- **飞行中穿过区块边界的观感**未验证：理论上流式是 (x,z) 纯函数、确定性重建，
  飞行速度快会加大"一帧内跨多个区块"的概率，需要看是否有可见的地形弹出。
- PRD §5.6 明确不做的五项（枪械 / 一键破坏整片 / 无限实体 / 联机 / 专属方块）**均未实现**，符合范围。
- 未做：创造模式专属 HUD 美化（当前只在 debug HUD 加了一行「创造模式 飞行=开/关 免疫伤害 N 次」）。

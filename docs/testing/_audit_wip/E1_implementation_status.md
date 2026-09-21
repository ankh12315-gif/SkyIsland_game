# E1 · 代码实现状态与债务登记扫描（MVP Specification Coverage Audit）

- **角色**：engineering-lead（技术 / 实现状态取证）
- **任务 ID**：MVP-AUDIT-E1
- **性质**：**只审计与登记。未改动任何功能代码，未新增功能，未启动 M2。**
- **审计基线**：`F:\minecraftspace` @ M0 CLOSED → M1 PASS → M1.5 PASS
  - 产品源码 `src/main/java/com/skyisland/` **70 文件 / 16,667 行**
  - 测试源码 `src/test/java/com/skyisland/` **35 文件 / 555 用例 / 0 失败**
- **权威来源（只读）**：
  - `docs/design/PRD_v0.3.1.md`（1262 行）
  - `docs/architecture/TECH_DESIGN_v0.1.1.md`（勘误增补，与 v0.1 冲突时以其为准）
  - `docs/architecture/TECH_DESIGN_v0.1.md`
  - `docs/architecture/technical-constraints.md`
  - `docs/testing/M0_REPORT.md` / `M1_FIRST_PLAYABLE_REPORT.md` / `M1_5_FRONTEND_SETTINGS_REPORT.md`

---

## 0. 取证口径声明（先说清楚我是怎么判的）

1. **状态词的用法**（本文件只填状态词，最终判定由 quality-lead 复核）：
   - `IMPLEMENTED` —— 该系统的 PRD 规格在代码里有可指认的类/方法，且有可执行证据（单测或脚本化自测）。
   - `PARTIAL` —— 系统存在且可跑，但 PRD 规格中的**一部分子项**没有对应实现。
   - `NOT_IMPLEMENTED` —— 代码中**无对应实现**，且**引用不到任何登记出处**。
   - `DEFERRED_WITH_RECORD` —— 代码中**无对应实现**，但**能引用到明确登记出处**（写明归属阶段）。
   - `CONFLICT` —— 有实现/有登记，但**规格文档之间互相矛盾**，无法判定应以哪一份为准。
2. **`DEFERRED_WITH_RECORD` 的门槛**：必须能引到"某文件某段写明归属 M2/M3/Alpha"。
   引用不到一律记 `NOT_IMPLEMENTED`，并在 §3 单列。
3. **"无对应实现"的写法**：本文件不使用"未见明显实现"一类措辞；
   没有就是"代码中无对应实现"，并给出我搜过的位置（类/方法名或全局 grep 结论）。
4. **本轮未重新构建**（按任务约束），源码事实全部来自 Read / Grep / Glob 静态取证。

---

## 1. 交付物 1：系统实现状态事实表（37 项）

> 任务书列出 37 个系统名（书面上写"34 个"），本表按任务书逐项列出，不做删减。

| # | 系统 | 状态 | 代码实现证据（`src/main/java/com/skyisland/...`） | 自动测试证据（`src/test/java/com/skyisland/...`） | 技术债登记出处 | PRD 原计划阶段 |
|---|------|------|-----------------------------------------------|-----------------------------------------------|----------------|----------------|
| 1 | **世界 World** | `PARTIAL` | `world/World.java`：`getOrLoadChunk` / `ensureAreaLoaded` / `breakBlock` / `placeBlock` / `MutationResult` / `pollMeshRebuilds`；区块范围由 `world/gen/TestWorldGenerator.java:61-62` 固定为 `cx,cz ∈ [-2,1]`（16 区块） | `world/WorldTest`：`getOrLoadChunkIsMemoized`、`chunkAtReturnsNullForUnloadedChunk`、`ensureAreaLoadedCoversTheWholeRectangle`、`unloadChunkRemovesItAndMarksNeighbours` | `M1_FIRST_PLAYABLE_REPORT.md §6.1` **T-8.4**（无动态区块加载/卸载，归属 M2/M3） | PRD §4.1 视距 6 区块【MVP 必须】；M1 门禁 |
| 2 | **Chunk** | `IMPLEMENTED` | `world/Chunk.java`（16×16×128）；`render/mesh/ChunkMesher.java`、`ChunkRenderer.java`、`ChunkMesh.java`；`save/ChunkSerializer.java`（增量 + palette + CRC） | `world/ChunkTest`（11 例）、`render/mesh/ChunkMesherTest`（16 例）、`save/ChunkSerializerTest`（17 例） | 动态加载/卸载部分见 T-8.4；Chunk 本体无债 | PRD §1.3 / §11 M1 交付内容 |
| 3 | **Block（Registry / 方块集）** | `PARTIAL` | `world/block/BlockRegistry.java:89-120` `bootstrap()`：共注册 **10** 条（`air` + 玩家常规 **8** 种：`stone/dirt/grass_block/sand/cobblestone/oak_planks/stone_bricks/glass` + 系统方块 `resource_core`）。**代码中无** `log` / `leaves` / `iron_ore` / `coal_ore` / `torch` / `wooden_door` 的注册 | `world/block/BlockRegistryTest`：`m1SubsetHasExpectedSize`（`EXPECTED_BLOCK_COUNT = 10`，第 27/48 行）、`resourceCoreIsUnbreakableUnplaceableAndEmissive` | **无登记**：M1 报告 §6.1 T-8.1～T-8.8 与 M1.5 报告 §6.1 均未登记"MVP 13 种玩家方块只注册了 8 种" | PRD §5.1 / §5.1.1 / §8：**MVP 13 种玩家常规 + 1 种系统方块**；M1–M3 |
| 4 | **挖掘** | `IMPLEMENTED` | `player/Player.java:550-596` `updateMining()`：reach=5.0（`REACH`，L61）、按硬度累计 `miningProgressSeconds`、松手/换目标 `resetMining()`（L598）、不可破坏方块进度恒 0（L574）；射线 `DdaRaycaster.castSolid` | `player/PlayerPhysicsTest`：`lookingStraightDownAndHoldingAttackBreaksTheBlockUnderfoot`、`miningProgressIsVisibleWhileDigging`、`releasingAttackResetsMiningState`、`switchingTargetResetsMiningProgress`、`unbreakableBlockIsNeverBroken`；`physics/DdaRaycasterTest`（16 例） | 无 | PRD §5.2 挖掘判定/破坏进度【MVP 必须】；M1 |
| 5 | **破坏反馈（10 段裂纹 + 每段音效）** | `PARTIAL` | **视觉已实现**：`render/mesh/CrackOverlay.java`（10 段，`ceil(progress×10)`，方块坐标哈希定图案，沿法线外偏 `2.5e-3`，面局部坐标钳 `[0.06,0.94]`）。**音效：代码中无对应实现** —— 全仓 grep `audio/sound/音效/OpenAL` 仅命中 `CrackOverlay` 注释与 `ui/Menus.java:117` 的 `"Audio (no audio backend in M1.5)"` 标签，无任何播放路径 | `render/mesh/CrackOverlayTest`（**10 例**：`noCracksForNonPositiveProgress` / `firstSegmentAppearsImmediately` / `segmentMappingIsMonotonicAndCapped` / `quadWindingMatchesFaceNormal` / `patternIsDeterministicPerBlock` …）+ `M1_5UiSelfTest` 阶段 `MINE_BY_MOUSE` | `TECH_DESIGN_v0.1.1.md` **§T-8.9**（第 243-252 行）+ `M1_5_FRONTEND_SETTINGS_REPORT.md §6.1`（登记源头）→ 建议归属 M2 | PRD **第 412 行**【MVP 必须】；PRD §11 M1 交付内容含"挖掘进度与破坏反馈" |
| 6 | **破坏特效（8–12 占位粒子 + 破坏音效）** | `DEFERRED_WITH_RECORD` | **代码中无对应实现**：无粒子系统、无粒子类、无粒子渲染路径；音效同上（无音频后端）。全仓 grep `particle/粒子` 在 `src/main` 中零命中（仅 `CrackOverlay` 注释提及） | 无自动测试（无被测对象） | `TECH_DESIGN_v0.1.1.md §T-8.9`（第 247 行明文引用 PRD 第 412/413 行）+ `M1_5_FRONTEND_SETTINGS_REPORT §6.1`（第 252-256 行）→ 归属 M2 | PRD **第 413 行**：【MVP 必须】（**占位**）／【Alpha 必须】（完整）。**阶段边界见 §2** |
| 7 | **放置** | `IMPLEMENTED` | `player/Player.java:607-638` `handlePlacement()`；`world/World.java#placeBlock`：相邻支撑校验、y∈[1,127]、与玩家 AABB 冲突校验（`world/VoxelObstruction.java`） | `player/PlayerPhysicsTest`：`lookingAtGroundAndUsingPlacesTheHeldBlock`、`placementIsRejectedWhenTheSelectedSlotIsEmpty`、`placementIsRejectedWhenNothingIsInReach`、`placementIsRejectedWhenTheTargetCellIsInsideThePlayer`；`world/WorldTest`：`placeBlockRejectsWithoutAdjacentSupport`、`placeBlockRejectsOccupiedCell`、`placeBlockRejectsAirAndOutOfRangeY`、`placeBlockRejectsUnplaceableBlock` | 放置**音效**缺 → 见 §3 G4（无登记） | PRD §5.2 放置目标判定/邻接支撑/上下限/实体冲突【MVP 必须】；M1 |
| 8 | **拾取** | `PARTIAL` | **只有"直接入包"**：`player/Player.java:586` `inventory.add(hit.blockRuntimeId(), 1)`。`Inventory.java:17` 注释自述"M1 没有 `ItemEntity`（属 M2），破坏方块后物品直接入包"。**代码中无**：掉落物实体、飞向玩家、2 格半径自动拾取、背包满原地停留 5 分钟后消失 | `player/InventoryTest`：`addFillsFirstEmptySlotThenStacks`、`addReturnsLeftoverWhenInventoryIsFull`、`addSpillsIntoAdditionalSlotsWhenStackIsFull` | `M1_FIRST_PLAYABLE_REPORT.md §6.1` **T-8.3**（无实体系统 / 掉落物实体 → M2）；§6.4 列"掉落物实体"明确不在 M1 范围 | PRD §5.2 掉落拾取【MVP 必须】；实体 → M2 |
| 9 | **Inventory（背包）** | `PARTIAL` | `player/Inventory.java`：**只有 9 格**（`HOTBAR_SIZE = 9`，L22；类注释 L9-11 自述"M1 不做 27 格完整背包界面"）。**代码中无** 27 格主背包、无拖拽移动/排列 | `player/InventoryTest`（17 例）、`player/ItemStackTest`（9 例，堆叠上限 64） | `TECH_DESIGN_v0.1.1.md §S′`（第 178 行：`ui（HUD / InventoryScreen / …）| ❌（M3）`）+ `M1_FIRST_PLAYABLE_REPORT §6.1` **T-8.8**（M3） | PRD §5.6.1：快捷栏 9 / 主背包 27 / 拖拽整理，均【MVP 必须】 |
| 10 | **Hotbar** | `IMPLEMENTED` | `player/Inventory.java`：`selectSlot` / `cycleSlot`（滚轮循环，L66-72）/ `consumeSelected`；`render/ui/HudRenderer.java:102-135` `drawHotbar`（9 格 + 选中高亮 + 数量文字）；数字键 `InputMapperActionsTest` 覆盖 | `player/InventoryTest`：`cycleSlotWrapsAroundBothEnds`、`selectSlotIgnoresOutOfRangeIndices`、`consumeSelectedOnlyTouchesTheSelectedSlot`、`newInventoryIsNineEmptySlotsWithFirstSelected`；`input/InputMapperActionsTest.numberKeysSelectHotbarSlots` | 无 | PRD §5.6.1 快捷栏 9【MVP 必须】；§6.1 HUD 快捷栏 |
| 11 | **Player Physics** | `IMPLEMENTED` | `player/Player.java`：AABB 0.6×1.8×0.6（L42-43）、眼高 1.62、重力 32、起跳 8.95、行走 4.317、终端速度 60、分轴求解 + 0.4 格子步进（L382-457）、向下探测站立（L466）、渲染插值（L682） | `player/PlayerPhysicsTest`（48 例）：`gravityPullsThePlayerDownOntoTheTerrain`、`playerDoesNotSinkOrTunnelThroughTheTerrain`、`terminalVelocityCapsFallSpeed`、`jumpClearsOneBlockButNotTwo`、`diagonalMovementIsNotFasterThanStraightMovement`、`releasingMoveKeyStopsThePlayerOnGround` …；`physics/AABBTest`（9 例） | 跌落伤害缺 → 见 §3 **G8** | PRD §9.2 玩家【MVP 必须】；M1 |
| 12 | **Mouse Look** | `IMPLEMENTED` | `player/Camera.java`（`addLook` / yaw-pitch 钳制）；`settings/LookConfig.java`（灵敏度倍数 + 反转 Y）；`player/Player.java:263-289`（`lookDegPerPixel` 注入与非法值回落） | `player/PlayerLookSensitivityTest`（16 例）、`settings/LookConfigTest`（10 例）、`input/InputMapperActionsTest.invertMouseYFlipsOnlyTheVerticalComponent` | 无 | PRD §6.5 鼠标灵敏度 / 反转鼠标 Y【MVP 必须】；§6.6 移动 |
| 13 | **Input Mapping** | `IMPLEMENTED` | `settings/Action.java`（9 个逻辑动作 + `consumedBy` 字段）、`settings/KeyBindings.java`、`settings/InputBinding.java`、`settings/InputNames.java`、`input/InputMapper.java`（查动作表）、`input/FrameInputQuantities.java`（帧级量发放）、`input/InputState.java`（边沿/电平 + 失焦释放） | `input/InputMapperActionsTest`（22 例）、`input/InputStateTest`（**7 例**）、`input/FrameInputQuantitiesTest`（8 例）、`player/PlayerIntentCopyTest`（**4 例**） | 无（但"按键自定义"本身是【后续迭代】→ 见 §4 E1） | PRD §6.6 默认键位【MVP 必须】；**按键自定义 = PRD §6.5 第 812 行【后续迭代】** |
| 14 | **Settings（设置项）** | `PARTIAL` | `settings/GameSettings.java`：**已实现 6 项 MVP 设置**：鼠标灵敏度（0.1–2.0）、FOV（60–90）、垂直同步、显示 FPS、反转鼠标 Y、主音量 / 音效音量（**仅 UI 与落盘，无音频后端**）。`settings/SettingsStore.java`（`settings.json` 三档容错）。**代码中无**：视距（2–8）、死亡掉落物品开关、界面语言、亮度 | `settings/GameSettingsTest`（13 例，`summaryLinesMentionTheHonestAudioLimitation`）、`settings/SettingsStoreTest`（**20 例**）、`ui/SettingsMenuControllerTest`（24 例）、`ui/MenuScreenTest`（24 例） | 缺失项 **无登记**：M1 §6.1 / M1.5 §6.1 / M1.5 §6.4 均未登记"视距/死亡掉落/界面语言/亮度 未实现" | PRD §6.5（11 项 MVP 设置项） |
| 15 | **Main Menu** | `PARTIAL` | `ui/Menus.java:71-73`：只有 `Start Game` / `Settings` / `Quit Game`；`ui/UiStateMachine.java` + `ui/UiState.java`（`MAIN_MENU`）；`ui/MenuScreen.java`、`render/ui/MenuLayout.java`、`MenuRenderer.java` | `ui/UiStateMachineTest`：`freshMachineStartsAtMainMenu`、`startGameGoesFromMenuToPlaying`、`escapeDoesNothingAtTheMainMenu`；`ui/MenuScreenTest.mainMenuStartsWithStartGameSelected`、`pauseMenuOffersResumeSettingsSaveAndQuit`；`render/ui/MenuLayoutTest`（17 例） | **无登记**：PRD §6.4 的"新的世界 / 继续游戏"两个独立入口未实现，无任何登记出处 | PRD §6.4 新的世界 / 继续游戏 / 设置 / 退出游戏【MVP 必须】；关于 = 后续迭代 |
| 16 | **Pause Menu** | `IMPLEMENTED` | `ui/Menus.java:82-86`（Resume / Settings / Save & Return to Main Menu / Quit）；`ui/UiState.java`（`PAUSED`：`simulationRunning=false`）；`game/SkyIslandGame.java:1092` 注释"Game Logic / Physics / Entity / World Time 全部暂停" | `ui/UiStateMachineTest`：`escapePausesWhilePlayingAndResumesWhilePaused`、`onlyPlayingRunsSimulationAndCapturesTheCursor`；`game/M1_5UiSelfTest` 阶段 `PAUSE` / `PAUSED_FREEZE`（要求"确实有逻辑步被拒绝"，实测 `skipped=60`） | 无 | PRD §12.3「暂停时世界」「Esc 暂停菜单提供保存」；PRD §8「界面 4 个」未列暂停菜单（**口径不一致，见 §5 S5**） |
| 17 | **Save** | `IMPLEMENTED`（M1 字段集） | `save/SaveManager.java#save`：`level.json` + `player.json` + `chunks/*.bin` 增量；`save/AtomicFileWriter.java`（临时文件→落盘→原子改名 + `.bak`）；`save/ChunkSerializer.java`（CRC32 + palette 稳定字符串 ID）；`save/SaveFormat.java:22` `SAVE_VERSION = 1`。触发点：F5（`InputMapper.java:98`）、暂停菜单"Save & Return"、退出收尾 | `save/SaveManagerTest`（**16 例**：`saveThenLoadRestoresBlocksAndPlayer`、`levelMetaRecordsTheFactsNeededToDiagnoseASave`、`unmodifiedWorldWritesNoChunkFilesAtAll`、`olderSaveVersionGoesThroughTheMigrationEntryPoint`）、`save/AtomicFileWriterTest`（13 例）、`save/ChunkSerializerTest`（17 例） | 未纳入字段：**世界时间 / 天数 / 生命 / 资源核心累积** —— `save/LevelMeta.java:14-16` 注释明文"M1 未纳入的字段：`worldTimeSeconds` / `dayPhase` / `dayCount` / `dayFactor`（M1 无昼夜循环）、`resourceCores`（属 M2）"；`save/PlayerState.java:18` "`health` / `fallDistance`" | PRD §9.2 / §11 M3「Save/Load 扩展（Seed / 时间 / 天数 / 方块改动）」；§12.3 |
| 18 | **Load** | `IMPLEMENTED` | `save/SaveManager.java#loadInto`；`player/Player.java:704-788`（`applyLoadedState` 原子写全部 + `sanitizePositionAfterLoad` 螺旋搜索半径 16 + 兜底生成 1 格临时木质地台，与 PRD §5.3.1 B 一致） | `save/SaveManagerTest`：`playerBuriedInsideBlocksGetsRelocatedBySanitisation`、`levelJsonFromANewerVersionIsRefusedInsteadOfSilentlyLoaded`、`corruptedChunkFileIsSkippedAndWorldFallsBackToGeneratedTerrain`、`missingPlayerJsonStillLoadsTheWorldWithAWarning`；`player/PlayerPhysicsTest`：`sanitizeFindsAFreeSpotWhenTheSavedPositionIsBuried`、`applyLoadedStateRestoresEverythingAtOnce` | 无 | PRD §12.3 崩溃防护 / 稳定 ID；M1 |
| 19 | **Void Death** | `IMPLEMENTED` | `util/Coords.java`（`VOID_KILL_Y` / `isVoidDeath`）；`player/Player.java:517-522` `checkVoid()`：`y < -8 → respawn`，`deaths++` | `player/PlayerPhysicsTest`：`fallingIntoVoidRespawnsAtLastSafePosition`、`voidDeathThresholdIsExclusiveAtMinusEight`、`respawnCountsEveryDeath`；`util/CoordsTest.voidDeathThreshold` | 无 | PRD §4.5 / §5.3 虚空致死【MVP 必须】；M1 |
| 20 | **Respawn** | `PARTIAL` | `player/Player.java:525-546` `respawn()`：回 `lastSafePosition` → 不合法回出生点 → 速度清零 → `deaths++`；`updateSafePosition`（L489，0.25 s 节流）；`sanitizePositionAfterLoad` 螺旋搜索 + 临时地台（L742-788）。**代码中无**：PRD §5.3 的"生命降至 0 → 播放死亡提示「你倒下了」→ **3 秒后**传送重生"、"死亡掉落全部背包与快捷栏物品，5 分钟内可拾回"、"切换死亡掉落开关" | `player/PlayerPhysicsTest`：`fallingIntoVoidRespawnsAtLastSafePosition`、`forcedRespawnIsDrivenThroughTheIntentPath`、`isStandingSpotValidRequiresSolidBelowAndFreeSpaceAbove` | `Player.java:33-35` 类注释自述"虚空坠落是 M1 唯一的死亡，且只触发重生，不扣血、不掉落物品（与 PRD 的完整死亡惩罚存在差距，**已记入 M1 报告**）"。**但 M1 报告 §6.4 只列了"生命值与饥饿 / 掉落物实体"，未逐条登记"3 秒重生延迟 / 死亡掉落 / 死亡掉落开关"** → 见 §3 G9 | PRD §5.3 / §5.3.1【MVP 必须】；M2（Death / Respawn） |
| 21 | **Resource Core** | `PARTIAL` | **方块层已实现**：`world/block/BlockRegistry.java:115` `resource_core`（`breakable=false`、`placeable=false`、`hardness=+∞`、`lightEmission=15`）；测试世界放置于 `(2,64,2)`（`TestWorldGenerator.java:153`）；挖掘拒绝路径 `Player.java:574`；自发光进 `LightEngine` 光源表。**再生行为：代码中无对应实现** —— 无 180 秒计时、无 5×5 范围再生、无占用冲突跳过、无 `resourceCores` 存档字段 | `world/block/BlockRegistryTest.resourceCoreIsUnbreakableUnplaceableAndEmissive`；`player/PlayerPhysicsTest.unbreakableBlockIsNeverBroken`；`world/WorldTest.rejectedBreakOfEmissiveBlockKeepsRegistryConsistent`；`world/WorldTest.emissiveSourcesAreRegisteredFromGeneration` | 再生部分：`save/LevelMeta.java:16`「`resourceCores`（资源核心的累积产出属 **M2**）」；`technical-constraints.md` 第 1054 行「全岛资源再生 / 复杂资源分布 … 延后到 **Alpha**」 | PRD §4.6【MVP 必须】（石矿岛 1 个简化核心，180 s / 1 格）；§11 M3 |
| 22 | **Seed** | `PARTIAL` | **字段与存档已实现**：`world/World.java:68/151-157`、`save/LevelMeta.worldSeed`、`save/ChunkSerializer.serialize(..., seed)`、`SkyIslandGame.java:162`（`skyisland.seed`，默认 20260919）、日志/摘要输出 seed。**但生成器不使用它**：`world/gen/TestWorldGenerator.java:45-47` 注释自述"地形是 (x,z) 的纯函数，不使用随机数，因此**不依赖 seed**" | `save/SaveManagerTest.levelMetaRecordsTheFactsNeededToDiagnoseASave`（含 seed）；`M1ScriptedSelfTest` 第 592-593 行用 `world.seed()` 另造世界验证重载 | `M1_FIRST_PLAYABLE_REPORT.md §6.1` **T-8.2**（无正式 `IslandGenerator`，只有 `TestWorldGenerator` → M2）；`TECH_DESIGN_v0.1.1 §S′` 第 175 行同 | PRD §4.7 Seed 作用范围【MVP 必须】（控制岛屿轮廓 / 矿脉位置 / 树木位置） |
| 23 | **昼夜** | `DEFERRED_WITH_RECORD` | **代码中无对应实现**：无世界时间、无天数、无阶段划分、无 `dayFactor`。`SkyIslandGame.elapsedSeconds`（L263）是**测量窗口计时器**，不是世界时间（L1104 只在 `stepLogic` 里累加，用于预热/统计/自动退出）。`LightEngine.java:13-14` 注释自述"`dayFactor` 恒为 1.0 —— M1 明确不含昼夜循环" | 无自动测试（无被测对象） | `M1_FIRST_PLAYABLE_REPORT.md §6.4`（"昼夜循环"列入明确不在 M1 范围）；`M1_5_FRONTEND_SETTINGS_REPORT §6.4`（同）；PRD §11 **M3 交付内容**含"昼夜" | PRD §4.4【MVP 必须】；§11 M3 |
| 24 | **Player Health** | `DEFERRED_WITH_RECORD` | **代码中无对应实现**：`Player` 无 `health` 字段（类注释 L34 明列排除），无生命条、无跌落伤害结算、无死亡流程（`skyisland` 全仓 grep `health` 在 `src/main` 中零命中） | 无自动测试（无被测对象） | `M1_FIRST_PLAYABLE_REPORT.md §6.4`（"生命值与饥饿"明确不在 M1 范围）；`save/PlayerState.java:18`「M1 未纳入的字段：`health` / `fallDistance`」；`game/SkyIslandGame.java:55`「**M2 及以后**：枪械 / 怪物 / 昼夜 / 生命值与饥饿 / …」 | PRD §5.3 生命上限 20 / 跌落伤害 / 死亡流程【MVP 必须】；M2（Damage / Death / Respawn） |
| 25 | **Pistol（手枪）** | `DEFERRED_WITH_RECORD` | **代码中无对应实现**：无 `combat` / `item` / 枪械包，无 `skyisland:pistol` 物品，无开火/弹匣/瞄准/曳光/准星切换 | 无自动测试 | `M1_FIRST_PLAYABLE_REPORT.md §6.4`（"枪械 / 弹药 / 换弹"）；`M1_5_FRONTEND_SETTINGS_REPORT §6.4`（同）；PRD §11 **M2 交付内容** | PRD §5.4.1 手枪【MVP 必须】；§11 M2 |
| 26 | **Ammo（弹药）** | `DEFERRED_WITH_RECORD` | **代码中无对应实现**：无弹药物品、无弹匣、无后备弹药计数（`ItemStack` 只有 `blockRuntimeId` + `count`，无物品类型抽象） | 无自动测试 | 同上（M1 §6.4 / M1.5 §6.4） | PRD §5.4.2 手枪弹【MVP 必须】；M2 |
| 27 | **Reload（换弹）** | `DEFERRED_WITH_RECORD` | **代码中无对应实现**：无换弹行为。仅有 `settings/Action.java:50` `RELOAD("reload", ..., "已绑定，玩法消费方在 M2")`（默认键 R，可重绑、可落盘，**无消费方**） | `ui/KeyRebindControllerTest`、`settings/KeyBindingsTest.defaultsMatchThePrdContract` 只覆盖"键位可配置"，不覆盖换弹行为 | 同上（M1 §6.4 / M1.5 §6.4）；`Action.java:45-49` 注释明文记录 | PRD §5.4.3 换弹【MVP 必须】（手枪 1.2 s）；M2 |
| 28 | **Hitscan** | `DEFERRED_WITH_RECORD` | **代码中无对应实现**：`physics/DdaRaycaster` 只有**方块**射线（`castSolid` / `castBreakable` / `castFirstAir`），无实体命中、无"取沿射线最近的合法碰撞结果"（PRD §12.2）、无距离衰减、无伤害结算 | `physics/DdaRaycasterTest`（16 例，全部是方块/空气射线，无实体相关用例） | 同上（M1 §6.4 / M1.5 §6.4）；PRD §11 M2 通过标准 4/5 | PRD §5.4.3 / §12.2【MVP 必须】；M2 |
| 29 | **Melee Monster（近战怪）** | `DEFERRED_WITH_RECORD` | **代码中无对应实现**：无 `entity` 包，无 `Entity` / `EntityManager` / `PlayerEntity`，无 `skyisland:melee_monster` | 无自动测试 | `M1_FIRST_PLAYABLE_REPORT.md §6.1` **T-8.3**（无实体系统 → M2）+ §6.4（"怪物与 AI"）；`M1_5_FRONTEND_SETTINGS_REPORT §6.4`；`TECH_DESIGN_v0.1.1 §S′` 第 177/179 行 | PRD §5.5.1【MVP 必须】；§11 M2 |
| 30 | **Monster AI** | `DEFERRED_WITH_RECORD` | **代码中无对应实现**：无 AI、无寻路、无状态机 | 无自动测试 | 同上（M1 §6.4 "怪物与 AI"；`technical-constraints.md` 第 1052 行"多怪物 AI 分支 … 延后到 Alpha"） | PRD §5.5.2【MVP 必须】（7 步行为）；M2 |
| 31 | **Spawn（刷怪）** | `DEFERRED_WITH_RECORD` | **代码中无对应实现**：无刷怪器、无 Threat Level、无刷怪时段/位置/上限/间隔、无怪物掉落表 | 无自动测试 | 同上（M1 §6.4 "怪物与 AI"）；PRD §11 **M3 交付内容**含"夜间刷怪"；`technical-constraints.md` 第 1057 行"威胁等级 TL1–TL4 … 延后到 Alpha" | PRD §5.5.3 / §5.5.4【MVP 必须】；M3 |
| 32 | **Crafting（合成）** | `DEFERRED_WITH_RECORD` | **代码中无对应实现**：无 `CraftingService`、无配方表、无 R01–R11、无合成界面（`Menus` 无合成页入口，`Action` 无 `CRAFT` 动作） | 无自动测试 | `TECH_DESIGN_v0.1.1 §S′` 第 179 行「`combat` / 怪物 / `ItemEntity` / `item.CraftingService` ｜ ❌ ｜ **M1 不实现**」；`M1_FIRST_PLAYABLE_REPORT §6.4` / `M1_5_FRONTEND_SETTINGS_REPORT §6.4`（"合成服务…背包与合成界面"） | PRD §5.6 / §9.2（MVP 7 条配方）【MVP 必须】；§11 M3 |
| 33 | **UI / HUD** | `PARTIAL` | **已实现**：准星（`HudRenderer.drawCrosshair`，固定色，无高亮）、快捷栏（`drawHotbar`）、挖掘进度条（`drawMiningBar`）、F3 调试 overlay + FPS/坐标/区块/目标方块 ID、事件提示（`HudModel.eventMessage` + 0.8 s 淡出，仅用于"Loaded save / New world / Screenshot queued / Save failed"）、主菜单 / 设置 / 暂停三屏。**代码中无**：生命条、低值预警、弹药计数、当前枪械名、天数与时间、即时提示（「弹药不足」「背包已满」）、背包屏、合成屏、准星高亮 | `render/ui/BitmapFontTest`（**11 例**）、`render/ui/MenuLayoutTest`（17 例）、`ui/MenuScreenTest`（24 例）、`ui/SettingsMenuControllerTest`（24 例）、`ui/UiStateMachineTest`（22 例）；HUD 像素证据见 M1.5 §5.4（`MiningBarProbe`：36.4% / 86.4%） | 背包屏/合成屏：`M1_FIRST_PLAYABLE_REPORT §6.1` **T-8.8** → M3。生命条/弹药/天数：**随 #24/#25/#23 的登记**。即时提示与准星高亮：**无登记**（§3 **G6 / G7**） | PRD §6.1 / §6.2 / §6.3【MVP 必须】；§8「界面 4 个」 |
| 34 | **Audio（音频后端）** | `DEFERRED_WITH_RECORD` | **代码中无对应实现**：无 OpenAL 依赖接入、无 `AudioDevice`、无任何播放调用。只有两个"诚实标签"：`ui/Menus.java:117` `MenuEntry.header("Audio (no audio backend in M1.5)")`；`settings/GameSettingsTest.summaryLinesMentionTheHonestAudioLimitation`。主音量/音效音量**只有滑杆与落盘，不驱动任何输出** | `ui/MenuScreenTest.volumeRowsAreMarkedAsHavingNoAudioBackend`（**反向断言**：断言它标注了"无后端"，不是断言能出声） | `M1_5_FRONTEND_SETTINGS_REPORT.md §6.4`（"音频后端"列入明确不在 M1.5 范围）+ §6.1（`TECH_DESIGN_v0.1.1 §T-8.9`，建议随 M2 音频后端接入）；`technical-constraints.md` 第 1058 行"完整音效 / 粒子系统 … 延后到 Alpha（**保留最小反馈**）" | PRD §6.5 主音量 / 音效音量【MVP 必须】；后端 → M2 |
| 35 | **Lighting（光照）** | `IMPLEMENTED` | `world/LightEngine.java`：天光列（`columnTop`）+ 火把固定径向衰减（`TORCH_RADIUS = 6`，切比雪夫距离，L39/L90/L177）；烘焙进顶点 shade（`render/mesh/ChunkMesher`）。**无 BFS 块光传播**（PRD 4.4 明令 MVP 不做）、无 `dayFactor`（无昼夜） | `world/LightEngineTest`（11 例：`skyLightIsFullAtAndAboveColumnTopAndZeroBelow`、`torchLightDecaysByChebyshevDistance`、`shadeFactorIsMonotonicOverTheWholeRange`、`isEmissiveOnlyForNonAirLitBlocks`）；`render/mesh/ChunkMesherTest.faceBrightnessIsBakedIntoVertexAlpha` | `M1_FIRST_PLAYABLE_REPORT.md §6.1` **T-8.5**（光照为最小实现 → M2）；BFS 传播：PRD §4.4【后续迭代】；`technical-constraints.md` 第 1053 行同 | PRD §4.4 火把照明（MVP）：固定径向衰减半径 6，**不做全局光照传播** |
| 36 | **Performance（性能计量与门禁）** | `IMPLEMENTED` | `game/FrameStats.java`（定宽桶直方图 `BUCKET_MS=0.1` / 2000+1 桶，取代 M0 的环形缓冲）；`GameLoop.java`（固定 60 Hz 逻辑步 + 渲染插值 + `recordClamp()`）；`game/SkyIslandGame` 摘要输出（p95/p99/max/spikes/clamped/overruns） | `game/CommandLineOverridesTest`（**9 例**）；性能数字由三次证据运行产出（M1 §4 / M1.5 §4），并有对照实验（`tmp/JitterProbe.java`） | `TECH_DESIGN_v0.1.1 §C.4′`（10 条合同）；`M1_FIRST_PLAYABLE_REPORT §6.2` **T-9**（同步存档 214–228 ms 停顿，归属 M2/Optimization，**未修复**） | PRD §12.5 性能验收基准；M1 门禁 |
| 37 | **首次提示** | `NOT_IMPLEMENTED` | **代码中无对应实现**：`HudModel.eventMessage` 机制存在（可显示带淡出的提示），但全仓检索，**只有 5 处调用**：`SkyIslandGame.java:504`（Loaded save）、`:508`（Load failed）、`:512`（New world）、`:822`（Screenshot queued）、`:1459`（Save failed）。**没有任何"首次进入世界显示「WASD 移动，左键挖掘，右键放置」3 秒淡出、仅首次"的实现**，也没有"首次"判定状态（无 flags / 无 firstRun 字段） | 无自动测试 | **无登记**：M1 报告 §6.1 T-8.1～T-8.8、M1.5 报告 §6.1/§6.4、TECH_DESIGN v0.1.1 §U′ 全部检索，**没有任何一段提到"首次进入提示 / 操作提示 / 新手提示"** | PRD §6.7 第 839 行【MVP 必须】：首次进入世界显示最简操作提示「WASD 移动，左键挖掘，右键放置」，3 秒淡出，仅首次 |

### 1.1 状态计数（本表 37 项）

| 状态 | 计数 | 项 |
|------|------|-----|
| `IMPLEMENTED` | **13** | Chunk、挖掘、放置、Hotbar、Player Physics、Mouse Look、Input Mapping、Pause Menu、Save、Load、Void Death、Lighting、Performance |
| `PARTIAL` | **11** | 世界、Block、破坏反馈、拾取、Inventory、Settings、Main Menu、Respawn、Resource Core、Seed、UI/HUD |
| `DEFERRED_WITH_RECORD` | **12** | 破坏特效、昼夜、Player Health、Pistol、Ammo、Reload、Hitscan、Melee Monster、Monster AI、Spawn、Crafting、Audio |
| `NOT_IMPLEMENTED` | **1** | 首次提示 |
| `CONFLICT` | 0（表内） | 文档层冲突单列于 §5 |

---

## 2. 交付物 2：T-8.9 专项复核（本轮重点）

### 2.1 三项逐项取证

| T-8.9 子项 | PRD 出处 | 代码实现 | 自动测试 | 登记状态 |
|---|---|---|---|---|
| **每段挖掘音效** | 第 **412** 行「破坏反馈 \| 方块表面显示 10 段裂纹动画；**每段伴随一次挖掘音效**（MVP 用占位音）\| **【MVP 必须】**」 | **无实现**。全仓 `src/main` grep `audio / sound / 音效 / OpenAL` 零命中播放路径 | 无（`CrackOverlayTest` 只断言几何与颜色；M1.5 §5.4 的像素探针只能证明裂纹被光栅化，**证明不了声音**） | **已登记**：`TECH_DESIGN_v0.1.1.md §T-8.9`（第 243-252 行）+ `M1_5_FRONTEND_SETTINGS_REPORT §6.1`（登记源头），建议归属 **M2** |
| **破坏音效** | 第 **413** 行「破坏特效 \| 破坏瞬间生成 8–12 个方块颜色粒子，**播放破坏音效** \| **【MVP 必须】（占位）／【Alpha 必须】（完整）**」 | **无实现**（同 audio 结论） | 无 | **已登记**：同上（T-8.9 第 247 行显式引用第 413 行） |
| **破坏瞬间 8–12 个方块颜色粒子（占位）** | 第 **413** 行 | **无实现**。无粒子系统、无粒子类、无粒子渲染路径；`src/main` grep `particle / 粒子` 零命中 | 无 | **已登记，但登记理由与 PRD 冲突**：T-8.9 第 249 行写「粒子的完整规格**按 PRD 本身归属 Alpha**」—— 见 §2.2 |

### 2.2 阶段边界：占位粒子 ≠ 完整粒子（引 PRD 原文坐实）

**不能**得出"所有粒子都属于 Alpha"这种结论。PRD 原文把粒子切成两档，边界写死在表格的优先级列里：

| PRD 位置 | 原文 | 优先级 |
|---|---|---|
| **第 413 行**（§5.2 挖掘与放置） | 「破坏特效 \| 破坏瞬间生成 **8–12 个方块颜色粒子**（Alpha 起补全规格），播放破坏音效」 | **【MVP 必须】（占位）／【Alpha 必须】（完整）** |
| **第 947 行**（§9.4 MVP 明确不实现） | 「完整饥饿、4 把枪、4 种怪、其余 3 座岛、成就系统、完整农业、复杂资源再生、**完整音效**、**大量粒子**、联机、高级设置、复杂 AI、枪械后坐力、配件、天气、沙子重力、铜/金/晶体、树苗、石砖。」 | ——（"**大量**粒子"，不是"粒子"） |
| **第 983 行**（§10.2 Alpha 新增内容清单） | 「表现 \| **音效、粒子**」 | Alpha 承接的是"完整/正式表现" |
| **第 1067 行**（§11 M4 交付内容） | 「…UI 完整化、**音效、粒子**、设置、平衡调整」 | 同上 |

**结论口径（按 PRD 原文）**：
- **占位粒子（8–12 个简单方块颜色粒子）**：【MVP 必须】→ 最迟 **M3**（MVP = M0–M3，见第 33 行与第 1002 行的"MVP 里程碑（M0–M3）"口径）。
- **完整 / 正式粒子表现**：【Alpha 必须】→ **M4**（第 983 / 1067 行）。
- 因此 `TECH_DESIGN_v0.1.1 §T-8.9` 第 249 行的"未补理由"（「粒子的完整规格按 PRD 归属 Alpha」）**只覆盖两档中的后一档**，用它来解释"占位粒子也不做"属于**把【MVP 必须】项整体推到 Alpha**，与第 413 行的"（占位）"标注冲突。

### 2.3 T-8.9 之外：音频缺口比 T-8.9 登记的更宽

T-8.9 只登记了"挖掘音效 + 破坏音效 + 破坏粒子"。PRD 里同样标【MVP 必须】、且同样依赖音频后端、但**没有任何登记出处**的还有：

| 项 | PRD 出处 | 登记状态 |
|---|---|---|
| **放置音效**（每种方块类型配置放置音效，MVP 用占位音） | 第 **420** 行【MVP 必须】 | **无登记** |
| **命中方块：溅射粒子 + 撞击音效** | 第 **524** 行【MVP 必须】 | **无登记** |
| **命中怪物：命中音效 + 受击闪白** | 第 **524** 行【MVP 必须】 | **无登记** |
| **开枪音效 / 空枪音效 + HUD「弹药不足」** | 第 **528** 行【MVP 必须】 | **无登记** |
| **界面打开时被攻击：受击音效** | 第 **768** 行【MVP 必须】 | **无登记** |
| **曳光轨迹（0.05 秒线段）** | 第 **525** 行【MVP 必须】 | **无登记**（非音频，但同为表现层反馈） |

### 2.4 一个必须点名的数量冲突

| 文档 | 破坏粒子数量口径 |
|---|---|
| `PRD_v0.3.1.md` 第 **413** 行 | **8–12 个**（占位） |
| `TECH_DESIGN_v0.1.md` 第 **1087** 行（`FeedbackListener`） | 「破坏粒子（**MVP 3–5 个**）」 |
| `TECH_DESIGN_v0.1.md` 第 **1857** 行（§M.7 表现层反馈） | 「命中方块 \| 溅射粒子（**3–5 个**，占位方块色）」 |

→ 两份技术文档与 PRD 在**同一个 MVP 占位粒子**上给出 3–5 与 8–12 两个互斥数字。这是本轮新发现的 SPEC_DRIFT（§5 S1）。

---

## 3. 未登记缺口清单（"未实现且未登记"，本轮要抓的第二类问题）

> 这些都是 PRD 标【MVP 必须】、代码里没有、且**引用不到任何登记出处**的条目。
> 与 T-8.9 是同一类病：不会自己浮出来，只会被玩家撞到。

| 编号 | 缺口 | PRD 出处 | 状态 |
|---|---|---|---|
| **G1** | MVP 13 种玩家方块只注册 **8** 种；缺 `log`（原木）、`leaves`（树叶）、`iron_ore`（铁矿石）、`coal_ore`（煤炭矿石）、`torch`（火把）、`wooden_door`（木门） | §5.1 表格 / §8 第 885 行 / §9.3 第 941 行「13 种为能跑通核心循环的最小方块集，**不得再削减**」 | **无登记**。其中 `iron_ore` / `coal_ore` 缺失直接切断 R03 铁锭与 R11 弹药闭环 |
| **G2** | 设置项缺 4 项：视距（2–8）、死亡掉落物品开关、界面语言、亮度 | §6.5 第 800/803/806/809 行【MVP 必须】 | **无登记** |
| **G3** | 主菜单缺「新的世界」与「继续游戏」两个独立入口（只有 `Start Game`，其语义是"进入已加载/新建的世界"） | §6.4 第 788/789 行【MVP 必须】 | **无登记** |
| **G4** | 放置音效（见 §2.3） | §5.2 第 420 行【MVP 必须】 | **无登记** |
| **G5** | 首次进入提示「WASD 移动，左键挖掘，右键放置」（= 表中 #37） | §6.7 第 839 行【MVP 必须】 | **无登记** |
| **G6** | 即时提示「弹药不足」「背包已满」，2 秒淡出、同类 5 秒内不重复 | §6.1 第 751 行 / §6.7 第 840 行【MVP 必须】 | **无登记**（`eventMessage` 机制存在，但只服务存档/截图提示，未接任何玩法提示） |
| **G7** | 准星"对准可交互方块时**高亮**" | §6.1 第 744 行【MVP 必须】 | **无登记**（`HudRenderer.drawCrosshair` 用固定色常量 `CROSSHAIR`，L90-99 无任何条件分支） |
| **G8** | 跌落伤害公式 `max(0, floor(坠落格数 − 3))` | §5.3 第 434 行【MVP 必须】；**且是 PRD §11 M1 通过标准第 5 条**（"坠落 4 格造成 1 点伤害，坠落 3 格无伤"） | **无独立登记**。M1 报告 §6.4 只列"生命值与饥饿"，未点名跌落伤害；M1 门禁表（§2）第 10/11 行只验了"坠入虚空"与"重生"，**没有验跌落伤害** |
| **G9** | 死亡流程的 3 秒重生延迟、死亡掉落全部背包与快捷栏（5 分钟可拾回）、死亡掉落开关 | §5.3 第 436/437/439 行【MVP 必须】 | **部分无登记**：`Player.java:33-35` 注释称"已记入 M1 报告"，但 M1 报告 §6.4 只笼统列了"生命值与饥饿 / 掉落物实体"，**未逐条点名**这三项 |
| **G10** | 背包界面"打开时世界继续运行、鼠标显示光标且不再控制视角" | §6.2 第 766 行【MVP 必须】 | 背包屏整体未实现（T-8.8 → M3）；两条**行为细则**无单独登记 |
| **G11** | HUD「天数与时间」「生命条」「弹药计数」「当前枪械名」 | §6.1 第 745/747/748/749 行【MVP 必须】 | 随 #23/#24/#25 的登记间接覆盖，**无独立登记** |
| **G12** | 掉落物 y < −8 立即销毁；掉落物飞向玩家 + 2 格半径拾取（= 表中 #8 的实体部分） | §4.5 / §5.2 第 414 行【MVP 必须】 | 随 **T-8.3**（实体系统 → M2）间接覆盖 |

**计数：本轮发现"未实现且无登记"条目 12 条（G1–G12）**，其中 G1、G2、G3、G4、G5、G6、G7 为**完全无出处**，G8–G12 为"只有间接/笼统出处"。

### 3.1 建议的最晚关闭阶段（工程侧建议，供 quality-lead 汇编 traceability matrix 时引用）

| 缺口 | 建议最晚关闭阶段 | 工程侧理由 |
|---|---|---|
| G1 方块集（尤其 `iron_ore` / `coal_ore`） | **M2** | 手枪弹配方 R11（铁锭×1 + 火药×1）依赖铁矿石→铁锭；铁矿缺失则 M2 战斗原型的弹药闭环不成立。矿石方块同时需要 `drop` 定义与硬度数据 |
| G8 跌落伤害 | **M2** | 需要先有 `health` 字段（#24）；与 M2 的 Damage / Death / Respawn 同一批次落地最经济 |
| G9 死亡流程（3 s 延迟 / 死亡掉落 / 掉落开关） | **M2** | 同 G8，属 Death / Respawn 交付内容 |
| G4 放置音效、G5 首次提示、G6 即时提示、G7 准星高亮 | **M3**（G4 也可随 M2 音频后端） | 均为表现层；G4 若 M2 已接 OpenAL 则顺带做，否则最迟 M3 |
| G2 缺 4 项设置、G3 主菜单入口 | **M3** | 属 UI 完整化阶段（`T-8.8` 同批） |
| G10 背包打开时的世界/光标细则、G11 HUD 生命条/弹药/天数 | **M3** | 依赖 #23 昼夜与 #24 生命值；随 UI 完整化 |
| G12 掉落物销毁与拾取半径 | **M2** | 随 `T-8.3` 实体系统 |

### 3.2 关于本轮裁决的记录（team-lead 2026-09-19 转达，工程侧照办）

1. **不回写 PRD**。S1 / S2 一律只登记为 `SPEC_DRIFT` / `EARLY_SCOPE`，给出「PRD 原文 / 当前实现 / 建议处理」，最终由用户裁决。本文件未修改 `PRD_v0.3.1.md`、`TECH_DESIGN_v0.1.md`、`TECH_DESIGN_v0.1.1.md` 的任何内容。
2. **"入库"= 登记，不是实现**。G1–G12 本轮一行功能代码都不写。
3. **可以指出文档错误，但不能修改**：`TECH_DESIGN_v0.1.md` 第 1087 / 1857 行的「破坏粒子 MVP **3–5 个**」与 `PRD_v0.3.1.md` 第 413 行的「**8–12 个**」互斥，工程侧建议以 PRD 为准、TECH_DESIGN 后续勘误；`TECH_DESIGN_v0.1.1 §T-8.9` 第 249 行的"未补理由"覆盖不全（只覆盖"完整粒子"档）。这两条均已写入 §5 S1。**不修改**。

---

## 4. 交付物 3：EARLY_SCOPE（反方向 Scope Creep）与 ARCH_RESERVATION

> 判定标准：**PRD 标为 Alpha / 后续迭代**，但当前 M1/M1.5 代码里已经有实现的东西。
> 两类：`ARCH_RESERVATION`（接口/抽象/空实现/数据条目，允许）vs `EARLY_SCOPE`（有行为、有状态、玩家可感知）。

### 4.1 `EARLY_SCOPE`（1 项，明确）

#### E1 — 按键自定义 / 键位重绑系统【后续迭代】→ M1.5 已完整实现

| 项 | 内容 |
|---|---|
| **PRD 定位** | `PRD_v0.3.1.md` 第 **812** 行：「按键自定义 \| 全部键位可改 \| 见 6.6 \| **【后续迭代】**」。PRD §6.6 只是"**默认**键位"【MVP 必须】，不要求可改 |
| **已实现的东西** | 完整三段式重绑（等待 → 冲突确认 → 生效/取消）：`settings/Action.java`（9 个逻辑动作 + `consumedBy` 元信息）、`settings/KeyBindings.java`、`settings/InputBinding.java`、`settings/InputNames.java`、`ui/KeyRebindController.java`、`settings/SettingsStore`（`settings.json` 落盘）、`ui/Menus.java:124-131`（设置界面里 9 个可重绑行）、`ui/SettingsMenuController` + `ui/MenuScreen` 的重绑分支、`input/InputMapper` 从硬编码键码改为查动作表 |
| **为什么算 EARLY_SCOPE 而不是预留** | ① **有状态**：绑定表是持久化的玩家可修改状态（`settings.json`，带三档容错与 `.corrupt-*` 备份）；② **有行为**：冲突检测（`findOwner`）、ESC 取消、恢复默认、鼠标键与键盘跨命名空间；③ **玩家可感知**：改完立即改变操作方式，且会写盘、跨会话保留；④ **PRD 明确标了【后续迭代】**，而 PRD 第 39 行还写着「【后续迭代】功能**不得出现在 M0–M3（MVP）的任何验收标准中**」 |
| **是否在制造明显复杂度** | **是**。直接与间接受影响的代码 + 测试规模：`Action` / `KeyBindings` / `InputBinding` / `InputNames` / `KeyRebindController` 5 个新类；`InputMapper` 被重写为查表；`InputState` 增失焦释放；`SettingsStore` 的持久化契约因绑定表而扩大；测试侧 `InputMapperActionsTest`（22 例）+ `KeyBindingsTest`（19 例）+ `InputBindingTest`（12 例）+ `KeyRebindControllerTest`（23 例）= **76 例**，另有 `SettingsMenuControllerTest`（24 例）里的重绑分支未单独计入，合计占 555 用例的 **≥14%**。这是一整套"配置 UI 子系统"，不是一条缝 |
| **缓解事实（必须一并记录）** | ① 它是 **M1.5 里程碑指令**要求的（M1.5 报告 §2 按"代码引用的编号"列出第 4/5 条动作表与键位重绑三段式）；② `Action.java` 用 `consumedBy` 字段**诚实标注**了哪些动作还没有消费方；③ `InputMapperActionsTest` 断言未绑定动作恒不触发。因此它是"被指令要求做的后续迭代项"，而不是偷偷做的 |
| **建议** | **不删除**（本轮只登记）。交由 quality-lead / design-strategist 裁决：是回写 PRD 把"按键自定义"提前到 MVP，还是在 M2 之前冻结该能力不再扩张 |

### 4.2 `ARCH_RESERVATION`（4 项，允许）

| 编号 | 项 | PRD 定位 | 现状 | 为什么算预留 |
|---|---|---|---|---|
| **R1** | `Action.CROUCH` / `Action.RELOAD` / `Action.INVENTORY` 的默认键位 | CROUCH=§6.6 潜行【MVP】；RELOAD=§5.4.3【MVP】；INVENTORY=§6.2【MVP】 | 有默认键、可重绑、可落盘，**无消费方**。`Action.java:37/50/54` 的 `consumedBy` 字段明写"已绑定，玩法消费方在 M2/M3" | 纯数据表条目，无行为、无状态机；且有 `unconsumedActionsAreLabeledWithTheirMilestone` 单测守着 |
| **R2** | `skyisland:stone_bricks` 已注册进 Block Registry | §5.1 第 376 行「石砖 \| `skyisland:stone_brick` \| … \| **【Alpha 必须】**」；§9.4 第 947 行把石砖列入"MVP 明确不实现" | `BlockRegistry.java:107-108` 已注册（硬度 2.0、可破坏、可放置）。但**玩家无法获得**：无 R18 配方、世界生成器不产出 → 无玩法路径 | 边界项：它是"数据条目"而非玩法逻辑，判为预留。但它同时是一个 **SPEC_DRIFT**（PRD 说 MVP 不实现，代码已注册）→ 见 §5 S4 |
| **R3** | `DdaRaycaster.castBreakable` / `castFirstAir` | 无对应 PRD 条目 | 在 `src/main` 中**只有定义、零调用方**，只有测试在用（`DdaRaycasterTest.castBreakableSkipsUnbreakableBlocks`、`castFirstAirFindsTheHoleThePlayerDug`） | 投机性 API，无行为。注意它与 `technical-constraints.md` 的"统一说明"（第 1060 行：「以上模块**代码不写**（不预置空实现分支）」）精神相违背 |
| **R4** | 调试设施：F2 截图（`render/Screenshot.java`）、F9 强制重生、F5 手动存档、F3 overlay | F5 存档 = §12.3【MVP】；F3 = §6.1【MVP】；F2/F9 无 PRD 条目 | `InputMapper.java:96-99` 四个硬编码调试键（`debugKeysStayHardcodedAndUnaffectedByTheActionTable` 单测钉住） | 开发/取证设施，非玩法。PRD §11 M2 补充口径也预期"调试入口降级为开发开关" |

### 4.3 明确**不算** EARLY_SCOPE 的（避免误报）

- **主菜单 / 暂停菜单 / 设置屏**：PRD §8「界面 4 个」= 主菜单、HUD、背包、设置【MVP 必须】；暂停菜单由 §12.3「Esc 暂停菜单提供保存 / 暂停时世界」支撑。均在 MVP 内。
- **主音量 / 音效音量滑杆**：PRD §6.5【MVP 必须】（第 814 行明确"主音量 / 音效音量**保留 MVP**"）。滑杆已做、后端未接（属 Audio 债，不是范围溢出）。
- **`FrameStats` 定宽直方图 + 性能摘要**：由 `TECH_DESIGN_v0.1.1 §C.4′` 强制 + PRD §12.5 门禁要求。
- **`sanitizePositionAfterLoad` 螺旋搜索 + 临时地台**：PRD §5.3.1 B【MVP 必须】明文要求。
- **`Inventory` 的 `ItemStack` / 槽位数组数据模型**：类注释自述"数据结构不缩水，扩容只是把数组变成 27+9"。当前仍只有 9 格，无 27 格行为。

---

## 5. 我怀疑存在 SPEC_DRIFT 的地方（供 quality-lead / design-strategist 复核）

| 编号 | 冲突 | A 侧出处 | B 侧出处 | 备注 |
|---|---|---|---|---|
| **S1** | **破坏粒子阶段边界与数量**：T-8.9 把"占位粒子"整体推 Alpha；TECH_DESIGN v0.1 给 3–5 个；PRD 给 8–12 个且标【MVP 必须】（占位） | `TECH_DESIGN_v0.1.1.md` 第 249 行「粒子的完整规格按 PRD 归属 Alpha」；`TECH_DESIGN_v0.1.md` 第 1087 / 1857 行「3–5 个」 | `PRD_v0.3.1.md` 第 413 行「**8–12 个**方块颜色粒子（Alpha 起补全规格）…【MVP 必须】（占位）／【Alpha 必须】（完整）」 | **本轮最重要的一条**。详见 §2.2 / §2.4 |
| **S2** | **按键自定义**：PRD 标【后续迭代】，M1.5 完整实现 | `PRD_v0.3.1.md` 第 812 行【后续迭代】+ 第 39 行"不得出现在 M0–M3 验收标准中" | `M1_5_FRONTEND_SETTINGS_REPORT §2` + `settings/Action.java` 全表 | 见 §4.1 E1 |
| **S3** | **方块集口径**：PRD 要 13 玩家 + 1 系统；代码 8 玩家 + 1 系统 | `PRD_v0.3.1.md` 第 380 / 885 / 921 行 | `BlockRegistry.java:89-120`；`BlockRegistryTest:27` `EXPECTED_BLOCK_COUNT = 10` | 且缺的 6 种里有 `iron_ore` / `coal_ore`，切断弹药闭环 |
| **S4** | **石砖**：PRD 明列 Alpha 且"MVP 明确不实现"，代码已注册 | `PRD_v0.3.1.md` 第 376 行【Alpha 必须】+ 第 947 行 | `BlockRegistry.java:107-108` | 见 §4.2 R2 |
| **S5** | **界面数量口径**：PRD §8 说 MVP 界面 = 4 个（主菜单/HUD/背包/设置），不含暂停菜单；但 §12.3 要求 Esc 暂停菜单提供"保存" | `PRD_v0.3.1.md` 第 893 行 | `PRD_v0.3.1.md` 第 1120 行 | 暂停菜单是"第 5 个界面"，PRD 未给优先级标签 |
| **S6** | **生命值归属阶段不明确**：代码侧说 M2，PRD 的 M2 交付内容未列"生命值"（只列 Damage / Death / Respawn） | `SkyIslandGame.java:55`「M2 及以后：…生命值与饥饿…」 | `PRD_v0.3.1.md` 第 1028 行（M2 交付内容）；第 432 行【MVP 必须】 | 且第 1033 行只说"移除原 M2 验收中的**饥饿**条目"，未提生命值 |
| **S7** | **主菜单项**：PRD 要求 4 项（新的世界 / 继续游戏 / 设置 / 退出），代码 3 项（Start Game / Settings / Quit） | `PRD_v0.3.1.md` 第 788-791 行 | `ui/Menus.java:71-73` | 见 §3 G3 |
| **S8** | **M1.5 的 14 条规格原文不在仓库**，编号只能靠代码注释追溯 | `M1_5_FRONTEND_SETTINGS_REPORT.md §2` 第 64-68 行自述"其原文未落在仓库内…若后续把规格原文补进仓库，可直接替换本表第一列" | —— | traceability 断链；M1.5 §8 第 372 行已把它列为待办 |

---

## 6. 取证方法与已知局限

1. **本轮未重新构建**（按任务约束），所有源码事实来自静态取证（Read / Grep / Glob）。
2. **"无对应实现"的判定方式**：对每个系统做了全仓 `src/main` 的关键词 grep（如 `particle/粒子`、`audio/sound/音效/OpenAL`、`health`、`craft/recipe`、`monster/entity`、`seed`、`dayNight/worldTime`、`renderDistance/视距`），零命中或仅命中注释/标签时，才写"代码中无对应实现"。
3. **音频/粒子属于"无法用现有探针证明"的类别**：`TECH_DESIGN_v0.1.1 §V′.5` 的推论明确要求这类项"必须明确记为**未验证**"。因此本文件对音效类缺口一律记"无实现 + 无自动测试"，不用"代码看起来对"顶替。
4. **未覆盖**：`M0_REPORT.md` 与 `PRE_M1_CLOSURE.md` 只用于交叉确认已关闭项（T-2/T-3/T-4/T-6/T-6b），未从中提取新的实现状态。
5. **测试计数（本轮逐文件复核，2026-09-19 更正）**：
   - `src/test` 下 `@Test` 注解合计 **538** 个（逐文件 grep 计数）。
   - 另有 `util/CoordsTest` 的 **2 个 `@ParameterizedTest`**，其 `@CsvSource` 分别为 **8 行**（`toBlockMustFloor`，L33-42）与 **9 行**（`chunkDivisionMustUseFloorSemantics`，L71-81），共 **17** 次执行。
   - **538 + 17 = 555**，与 M1.5 报告 §1 的"34 个测试文件 / 555 用例"及任务书口径一致。**没有硬凑。**
   - 本文件所有"（N 例）"均为该文件的 `@Test` 注解数，**不含**参数化展开；也**不含**私有辅助方法（例如 `CoordsTest` 的 15 个 `@Test` + 2 个 `@ParameterizedTest`、`InputStateTest` 的 `press`/`release` 是 `private static` 辅助方法故只算 7 例、`CrackOverlayTest.assertWithinBlock` / `BitmapFontTest.assertArt` / `PlayerIntentCopyTest.assertSameRest` 同为辅助方法故分别为 10/11/4 例、`CommandLineOverridesTest.clear` 与 `SettingsStoreTest.clearOverride` 是 `@AfterEach` 故为 9/20 例）。
   - 本文件**不存在**"InventoryTest 23 例"的说法：`player/InventoryTest` 记为 **17 例**（与实测 L28/43/56/68/81/93/104/124/136/151/162/170/180/198/213/226/238 共 17 个 `@Test` 一致）。23 例的是 `ui/KeyRebindControllerTest`。

---

*本文件为审计工作稿（WIP），只做事实取证与登记，不含最终判定。判定列由 quality-lead 在 traceability matrix 中填写。*

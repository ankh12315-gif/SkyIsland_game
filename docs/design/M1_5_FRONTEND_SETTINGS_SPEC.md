# M1.5 Front-End & Settings Shell — 规格条目登记

- **文档性质**：**既有规格的正式入库**（不是重新设计）。本文件把 M1.5 里程碑的规格原文与验收方式从"只能靠代码注释追溯"提升为仓库内的正式文档。
- **上游**：`docs/design/PRD_v0.3.1.md`（产品口径唯一来源）、`docs/architecture/TECH_DESIGN_v0.1.md` + `v0.1.1`（技术基线，冲突以 v0.1.1 为准）
- **验收报告**：`docs/testing/M1_5_FRONTEND_SETTINGS_REPORT.md`（判定 **PASS**，构建产物 `0.2.5-M1.5-FRONTEND`）
- **日期**：2026-09-19
- **登记人**：文策渊（design-strategist），MVP-AUDIT-D1 轮次
- **登记依据**：M1.5 报告 §2「M1.5 规格条目对照」与 §3「三次证据运行的设计与结果」，以及 `src/main/java/com/skyisland/` 下 M1.5 实现类的**规格编号注释**与常量契约。

---

## 0. 编号口径与来源诚实声明（先读这一节）

### 0.1 为什么需要这个文件

M1.5 报告 §2 已明确记录：

> 「M1.5 的 14 条规格来自本轮的里程碑指令，其原文**未落在仓库内**；仓库里可追溯的编号引用是**代码与测试的注释**。」

也就是说，M1.5 是一个**规格先于文档、实现先于规格入库**的里程碑。这造成了两个已经付过学费的问题：

1. 编号无法被文档引用 —— 报告只能写"第 3/5/9 条"，读者必须回源码找；
2. 更严重的是，同类缺口的另一端：**PRD 明文【MVP 必须】的「破坏反馈」因为没有任何文档条目，既没实现也没登记，最后靠人工试玩才撞出来**（M1.5 报告 §5.4，登记为 **T-8.9**）。

### 0.2 本文件的两条编号

| 编号体系 | 含义 | 用途 |
|---|---|---|
| **M15-SPEC-01 … M15-SPEC-17** | 本文件新立的稳定编号，**今后一律引用这个** | 跨文档引用、Traceability Matrix、回归对照 |
| **第 N 条**（代码注释口径） | M1.5 里程碑指令的原始编号，只存在于源码注释里（如 `Menus` 第 9 条、`SettingsStore` 第 6 条、`LookConfig` 第 7 条） | 追溯用。**不得再用于新文档的正向引用** |

每条都同时给出两种编号，保证双向可查。

### 0.3 来源与可信度（诚实边界）

| 内容 | 来源 | 可信度 |
|---|---|---|
| 「代码注释中的原编号」列 | 源码 Javadoc 注释原文 | **一手**（注释里怎么写就怎么录） |
| 「规格原文（可验收行为）」列 | 由**实现契约**（常量、默认值、状态迁移、菜单项 id）+ M1.5 报告 §2/§3 的实测读数**反推**而成 | **反推**。**不是新设计** —— 本轮不发明任何新需求；凡规格原文与实现不一致处，在「备注」里显式标出 |
| 「验收方式」列 | M1.5 报告 §3 的三次运行设计 + §7.1 的门禁判定表 | 一手 |
| 「实现位置」列 | `src/main/java/com/skyisland/` 实际类与常量 | 一手 |

> **重要**：若后续找到 M1.5 里程碑指令的规格原文，应**替换**本文件「规格原文」列，并在修订记录里注明，**不要**另起一份文件。

### 0.4 M1.5 的既有技术债（读本文件前必知）

| 债项 | 内容 |
|---|---|
| **T-8.9** | 破坏反馈缺音效与破坏粒子（10 段裂纹的视觉部分已补）。见 M1.5 报告 §6.1 / `TECH_DESIGN_v0.1.1 §U′.2` |
| **T-9** | 存档同步写盘阻塞主循环（run B 的 213 ms spike 即来源于此），见 M1.5 报告 §6.2 |
| **点阵字模限制** | `BitmapFont` 只覆盖 ASCII 32–126，因此**所有菜单文案为英文**。见 `Menus.java` 类注释。这与 PRD 6.5「界面语言 = 简体中文（首版唯一）」冲突，已在 MVP-AUDIT-D1 的 SPEC_DRIFT 登记为 **B-04**（中文文案与字体；编号已统一至 324 版 Requirement 体系） |

---

## 1. 规格条目

### M15-SPEC-01 · Main Menu（主菜单）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 9 条**（`Menus.mainMenu()`）；界面流程另见**第 1/2/3/10 条**（`SkyIslandGame`） |
| **规格原文（可验收行为）** | 程序启动后进入主菜单封面；封面以已装配好的真实世界场景为背景（不另做背景图）；标题 `SKYISLAND`、副标题 `Voxel Survival Prototype`；菜单项三项：**Start Game**（生成/进入世界，瞬时）、**Settings**（进入设置界面）、**Quit Game**（关闭程序）。菜单光标可见、不控制视角；世界模拟不推进。 |
| **验收方式** | **自测阶段**：`INIT`（断言初始状态为主菜单且未推进模拟）、`START_GAME`（由"菜单项激活"这条产品路径进入游戏，不由直接调用状态迁移进入）、`OPEN_SETTINGS`、`BACK_TO_MAIN`。<br>**断言**：17 阶段 / 59 项断言全部通过，`ui_selftest_failures = 0`。<br>**人工步骤**：启动 → 目视主菜单 → 用鼠标点击（非键盘脚本）进入设置与返回。 |
| **实现位置** | `ui/Menus.java`（`mainMenu()`、常量 `ID_START_GAME` / `ID_OPEN_SETTINGS` / `ID_QUIT_GAME`）、`ui/MenuScreen.java`、`render/ui/MenuLayout.java`（几何与命中）、`render/ui/MenuRenderer.java`、`ui/UiStateMachine.java`（`startGame()`）、`game/SkyIslandGame.java` |
| **备注 / 与 PRD 的差异** | ⚠ **M1.5 主菜单只有 3 项，缺 PRD 6.4 的「继续游戏」（`MVP-UI-022`，【MVP 必须】）**。这是实现与 PRD 的已知差异，需在 M2/M3 补或显式登记。PRD 6.4 另要求"主菜单不显示局域网联机入口"（`MVP-UI-025`），本项满足。 |

---

### M15-SPEC-02 · Pause Menu（暂停菜单）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 2 条**（`Menus.pauseMenu()`、`UiStateMachine` 的 ESC 语义）；冻结语义见 `SkyIslandGame` 类注释「暂停的语义（规格第 2 条）」 |
| **规格原文（可验收行为）** | 游玩中按 ESC 进入暂停菜单；标题 `PAUSED`、副标题 `World time is frozen`；菜单项：**Resume**、**Settings**、**Save & Return to Main Menu**、（分隔）、**Quit Game**。暂停期间**世界模拟被拒绝推进**（`stepLogic` 被调用但立即返回并计数 `pausedStepSkips`），物理、世界时间、破坏与放置计数、世界改动计数**全部冻结**；渲染与菜单输入不受影响；光标释放（不锁定）。再按 ESC 从暂停恢复；从暂停菜单进入设置后"返回"必须回到**暂停菜单**而不是主菜单。 |
| **验收方式** | **自测阶段**：`PAUSE`（暂停计数 +1）、`PAUSED_FREEZE`（暂停期间被抑制的逻辑步 **≥ 60**，实测 `skipped = 60`）、`RESUME`（恢复计数 +1）、`SAVE_TO_MAIN`。<br>**断言**：`pausedStepSkips ≥ REQUIRED_PAUSED_SKIPS(60)`；冻结期间物理/世界时间/破坏计数/放置计数/世界改动计数**零增长**；`mouseCaptured` 在暂停态为 `false`。<br>**人工步骤**：游玩中按 ESC → 目视世界静止且光标出现 → 点 Resume 恢复。 |
| **实现位置** | `ui/Menus.java`（`pauseMenu()`、`ID_RESUME` / `ID_SAVE_TO_MAIN_MENU`）、`ui/UiStateMachine.java`（`pause()` / `resume()` / `settingsOrigin` 来源记忆）、`game/SkyIslandGame.java`（`pausedStepSkips`）、`render/Window.java`（光标模式由界面状态驱动） |
| **备注** | 「`stepLogic` 被调用但立即返回」与「没人调用逻辑步」在观测上**必须不同** —— 这是刻意的（依据 `TECH_DESIGN_v0.1.1 §A′.3 E-7` 的立场）。 |

---

### M15-SPEC-03 · Settings（设置界面）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 3 条**（`SettingsMenuController`、`GameSettings`、`MenuLayout` / `MenuScreen` / `MenuEntry` / `Menus` 的第 3/5/9 条） |
| **规格原文（可验收行为）** | 设置界面标题 `SETTINGS`、副标题 `Enter = toggle / rebind    Left-Right = adjust    Esc = back`。分四段：**Controls**（Mouse Sensitivity 滑杆 / Invert Mouse Y 开关 / Field of View 滑杆 / VSync 开关 / Show FPS 开关）、**Audio**（Master Volume / Sound Volume 滑杆，界面显式标注无音频后端）、**Key Bindings**（每个动作一行）、**Restore All Defaults** + **Back**。<br>交互：Enter = 开关切换或进入重绑；左右方向键 = 滑杆调整；Esc = 返回。<br>**所有改动立即生效**（不要求重开界面、不要求重启程序），并落盘。<br>值域一律在 setter 内**夹取**（clamp）而非拒绝：非有限值回退默认值；越界值夹到边界，并由 `SettingsStore` 记一条告警。<br>菜单项 id 全部为 `Menus` 中的常量，不得在别处写字面量。 |
| **验收方式** | **自测阶段**：`OPEN_SETTINGS`（主菜单 → 设置，走"菜单项激活"产品路径）、`SETTINGS_EDIT`（灵敏度 1.00 → 1.25、FOV 70 → 80、三个开关各翻转一次，且**立即生效**：换算率 `0.15000 度/像素`、相机 `FOV = 80.0`、`hud.showFps = true`）、`BACK_TO_MAIN`（设置 → 返回，且返回目标由来源决定）。<br>**断言**：`SettingsMenuControllerTest`、`GameSettingsTest`（范围钳制 / 默认值 / 深拷贝 / `isAllDefaults`）、`MenuLayoutTest`（几何与命中）。<br>**人工步骤**：进入设置 → 逐项改 → 立即观察画面变化 → Esc 返回正确来源界面。 |
| **实现位置** | `ui/SettingsMenuController.java`、`ui/Menus.java`（`settingsMenu()` / `settingsEntries()` 及各 `ID_*` 常量）、`settings/GameSettings.java`（范围常量 `MIN/MAX/DEFAULT/STEP_*` 与全部 setter 钳制）、`render/ui/MenuLayout.java`、`ui/MenuEntry.java`、`ui/MenuScreen.java`（`rebuild()` 保留选中项） |
| **备注 / 与 PRD 的差异** | ⚠ PRD 6.5 共 11 项【MVP 必须】设置项；M1.5 界面只暴露 **7 项**（灵敏度 / 反转 Y / FOV / VSync / 显示 FPS / 主音量 / 音效音量）。**未实现 4 项：视距（2–8）、死亡掉落物品、界面语言、亮度**（对应 `MVP-SET-002` / `MVP-SET-005` / `MVP-SET-008` / `MVP-SET-011`）。已在 MVP-AUDIT-D1 的 SPEC_DRIFT 登记为 **B-02**（设置系统缺技术条目；编号已统一至 324 版 Requirement 体系）。 |

---

### M15-SPEC-04 · Mouse Sensitivity（鼠标灵敏度）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 7 条**（`settings/LookConfig.java`、`player/Player.java` 的 `视角换算率：度 / 像素（M1.5 规格第 7 条）`） |
| **规格原文（可验收行为）** | 设置项「Mouse Sensitivity」范围 **0.1–2.0**、默认 **1.0**、步进 **0.05**。实际视角换算率 = 基准换算率 × 灵敏度倍数。基准（1.0 倍）下逐位等于 M1 的行为（M1 的"100 px 横移 → 12.000°"必须仍然成立）。倍数线性生效且**立即生效**，无需重启。 |
| **验收方式** | **自测阶段**：`LOOK_SENSITIVITY`。<br>**断言**：注入 100 px 横移 —— 1.5 倍 → Δyaw **18.000°**；0.5 倍 → **6.000°**（严格 **3:1**，容差 `YAW_TOLERANCE_DEG = 1.0`）。<br>**单测**：`LookConfigTest`（断言 1.0 倍逐位等于 M1 基准）、`PlayerLookSensitivityTest`。<br>**人工步骤**：把灵敏度拉到 0.1 与 2.0 各试一次，手感差异明显且无跳变。 |
| **实现位置** | `settings/LookConfig.java`（纯函数换算）、`settings/GameSettings.java`（`MIN_SENSITIVITY` / `MAX_SENSITIVITY` / `DEFAULT_SENSITIVITY` / `SENSITIVITY_STEP` / `adjustMouseSensitivity(int)`）、`player/Player.java`、`input/InputMapper.java`、`input/FrameInputQuantities.java`（帧级量发放） |
| **备注** | 本项与 `V′.1`（帧级输入量必须"发放"）强耦合：若鼠标位移被 `poll()` 就地取走，120 Hz 渲染 / 60 Hz 逻辑下约一半的帧会读到 0°。**`FrameInputQuantitiesTest` 是本项的隐性守门人。** |

---

### M15-SPEC-05 · Invert Y（反转鼠标 Y 轴）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 7 条**（`settings/LookConfig.java`、`settings/GameSettings.java`） |
| **规格原文（可验收行为）** | 设置项「Invert Mouse Y」开关，默认 **关**。开启后垂直视角方向取反（鼠标上移 → pitch 反向）。开关**立即生效**，且不影响灵敏度倍数（两个量正交）。 |
| **验收方式** | **自测阶段**：`LOOK_SENSITIVITY`。<br>**断言**：反转后注入 100 px 纵移 → Δpitch **+12.000°**（与未反转时符号相反、绝对值相同）。<br>**单测**：`LookConfigTest`、`settings/GameSettingsTest`（`setInvertMouseY` / `resetToDefaults`）。<br>**人工步骤**：开关翻转后立刻上下移动鼠标，方向立刻反转。 |
| **实现位置** | `settings/LookConfig.java`、`settings/GameSettings.java`（`invertMouseY` / `DEFAULT = false`）、`player/Camera.java`、`input/InputMapper.java` |
| **备注** | 与 M15-SPEC-04 共用同一条自测阶段；修复前这两条曾同时读到 `0.000°`（M1.5 报告 §5.1），是同一根因的两处症状。 |

---

### M15-SPEC-06 · FOV（视野）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 3 条**（`SettingsMenuController` 的 `ID_FOV` 分支；`GameSettings` 第 3/6/7 条） |
| **规格原文（可验收行为）** | 设置项「Field of View」范围 **60–90** 度、默认 **70**、步进 **1.0**。改动**立即作用于相机投影**，无需重启。菜单行显示为整数（`%.0f`）。 |
| **验收方式** | **自测阶段**：`SETTINGS_EDIT`。<br>**断言**：FOV 由 **70 → 80** 后，相机实际 `fovDeg` 读数为 **80.0**（与内存设置值一致）。<br>**单测**：`GameSettingsTest`（`MIN_FOV=60` / `MAX_FOV=90` / `DEFAULT_FOV=70` 与夹取行为）。<br>**人工步骤**：拖动 FOV 滑杆，画面视野即时变化。 |
| **实现位置** | `ui/SettingsMenuController.java`、`settings/GameSettings.java`、`player/Camera.java`、`render/Renderer.java`（投影矩阵） |
| **备注** | PRD 5.4.3 另规定瞄准时 FOV 由 70 收窄至 45 —— 那属 `MVP-COMBAT-014`（M2 枪械），**不在 M1.5 范围**。 |

---

### M15-SPEC-07 · VSync（垂直同步）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 3 条**（`SettingsMenuController` 的 `ID_VSYNC` 分支）；`render/Window.java`「运行期切换 VSync（M1.5 设置项）」 |
| **规格原文（可验收行为）** | 设置项「VSync」开关，默认 **关**（同时也是 PRD 12.5 的性能测试口径要求）。切换**在运行时立即生效**（不需要重建窗口）。 |
| **验收方式** | **自测阶段**：`SETTINGS_EDIT`（三个开关各翻转一次，VSync 为其中之一）。<br>**断言**：切换后帧间隔被钉在 ~16.7 ms（run A 四轮实测 p95 稳定在 13.5 / 13.8 / 14.4 / 13.6 ms）。<br>**单测**：`GameSettingsTest`（`vsync` 默认 `false`）。<br>**人工步骤**：开启 VSync 后画面不再撕裂且帧率被限制在刷新率附近。 |
| **实现位置** | `ui/SettingsMenuController.java`、`settings/GameSettings.java`、`render/Window.java`（`setVsync` 运行期切换） |
| **备注（重要）** | ⚠ **run A（界面自测）会主动切换 VSync，因此它的 p95 ≈ 13.6 ms 不是引擎稳态性能，不得用于性能门禁判定**。稳态性能只由 run C 判定（见 M15-SPEC-16 / M1.5 报告 §7.2）。**不得为了"调绿"而改这条脚本。** |

---

### M15-SPEC-08 · FPS（显示 FPS）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 3 条**（`SettingsMenuController` 的 `ID_SHOW_FPS` 分支） |
| **规格原文（可验收行为）** | 设置项「Show FPS」开关，默认 **关**。开启后 HUD 显示 FPS 读数；关闭后不显示。改动**立即生效**。 |
| **验收方式** | **自测阶段**：`SETTINGS_EDIT`。<br>**断言**：开关打开后 `hud.showFps = true`（读数路径可见）；关闭后为 `false`。<br>**单测**：`GameSettingsTest`（`showFps` 默认 `false`）。<br>**人工步骤**：开关翻转后 FPS 数字立刻出现/消失。 |
| **实现位置** | `ui/SettingsMenuController.java`、`settings/GameSettings.java`、`render/ui/HudModel.java`（`showFps`）、`render/ui/HudRenderer.java` |
| **备注** | 与 PRD 6.1 的「调试信息（FPS/坐标）按 F3 切换」（`MVP-HUD-013`）是**两条独立行为**：F3 是调试信息开关，本项是设置项开关。二者不应互相替代。 |

---

### M15-SPEC-09 · Audio（音量设置项 —— 当前无消费方）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 3 条**（`GameSettings` 的 `masterVolume` / `sfxVolume`）；`Menus` 的「Audio (no audio backend in M1.5)」段 |
| **规格原文（可验收行为）** | 设置界面提供两条音量滑杆：**Master Volume** 与 **Sound Volume**，范围 **0–100**、默认 **80**、步进 **5**。它们**完整地被保存、被读回、在界面上可调**，但 **M1.5 没有任何音频子系统作为消费方** —— 即调节后不产生任何听觉效果。界面必须在分组标题上显式写出 `Audio (no audio backend in M1.5)`，而不是让玩家对着一个无声滑杆猜。 |
| **验收方式** | **自测阶段**：`SETTINGS_PERSISTENCE`（音量随其余设置一并落盘且读回一致）。<br>**断言**：`GameSettingsTest`（范围钳制 `MIN_VOLUME=0` / `MAX_VOLUME=100` / `DEFAULT_VOLUME=80`）；`Menus.isVolumeSlider(...)` 供界面提示使用。<br>**人工步骤**：调节音量滑杆 → 数值变化 → **确认无声（这是本阶段的期望行为，不是缺陷）**。 |
| **实现位置** | `settings/GameSettings.java`、`ui/Menus.java`（`ID_MASTER_VOLUME` / `ID_SFX_VOLUME` / `isVolumeSlider()`）、`ui/SettingsMenuController.java`、`game/SkyIslandGame.java`（第 1033 行注释明写"没有可施加的对象"） |
| **备注（诚实边界）** | ⚠ **本项的实现边界是刻意保留的**：PRD 的音频属后续里程碑，M1.5 不引入 OpenAL。相关待办见 **T-8.9**（挖掘/破坏音效）与 `Action` 注释。PRD 6.5 把「音乐音量」标为 Alpha，另有一条【后续迭代】的「音量随距离衰减」—— 二者均不在本项范围。 |

---

### M15-SPEC-10 · Key Binding（键位绑定系统）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 4 条**（`settings/Action.java`「Key Binding System」）、**第 5 条**（`settings/KeyBindings.java`、`settings/InputBinding.java`）；`input/InputMapper.java`「从硬编码键码到读动作表」 |
| **规格原文（可验收行为）** | 输入分三段：**物理输入 → `Action`（逻辑动作）→ `PlayerIntent`**。重绑只改中间那段的映射表，游戏逻辑与渲染代码不引用具体键码。<br>M1.5 共登记 **11 个动作**：`move_forward` / `move_backward` / `move_left` / `move_right` / `jump` / `crouch` / `primary_action` / `secondary_action` / `reload` / `inventory` / `pause`。<br>每个动作有**不可变的落盘 id**（`settings.json` 的键名，发布后不得改）与**可随时改的界面 label**（纯 ASCII）。<br>**未绑定（none）的动作恒不触发**。<br>每个动作标注其消费阶段：8 个在 M1.5 已消费；`crouch` / `reload` 消费方在 M2；`inventory` 消费方在 M3。界面在键位行后显式显示该标注（如 `Reload [M2]`），避免"能改键"被误读成"改完有效果"。<br>默认键位与 PRD 6.6 一致。 |
| **验收方式** | **自测阶段**：`REBIND_ASSIGN`（无冲突直接生效）。<br>**断言**：`KeyBindingsTest`、`InputBindingTest`、`InputMapperActionsTest`（第 4/5/7 条）、`Action.byId(...)` 反查；未绑定动作恒不触发。<br>**人工步骤**：在设置界面逐行查看 11 个键位及其 `[M2]`/`[M3]` 标注。 |
| **实现位置** | `settings/Action.java`（含 `consumedBy` / `shortNote()` / `isConsumedHere()`）、`settings/KeyBindings.java`（`defaults()` 为默认表唯一来源、`restoreDefaults()`、`customized()`）、`settings/InputBinding.java`、`settings/InputNames.java`、`input/InputMapper.java`、`ui/Menus.java`（`BIND_PREFIX` / `bindId()` / `actionOfBindId()`） |
| **备注 / 与 PRD 的差异** | ⚠ PRD 6.5 把「按键自定义｜全部键位可改」标为**【后续迭代】**，而 M1.5 已完整实现。已在 MVP-AUDIT-D1 的 SPEC_DRIFT 登记为 **C-02**（键位自定义范围前移；编号已统一至 324 版 Requirement 体系），需用户裁决是提升优先级还是记为范围越界。 |

---

### M15-SPEC-11 · Rebinding Conflict（键位重绑三段式与冲突处理）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 5 条**（`ui/KeyRebindController.java`「三段式：等待输入 → （可能的）冲突确认 → 完成」） |
| **规格原文（可验收行为）** | 重绑流程严格三段：<br>**① 等待输入**：进入后捕获下一个物理输入；**等待中按 ESC 取消**，原绑定不变。<br>**② 冲突确认**：若该输入已被别的动作占用，弹出冲突对话框，**告知占用方是谁**（`conflictOwner`），由用户确认替换；确认后**原动作被解绑为 `(none)`**。<br>**③ 完成**：无冲突时直接生效；生效后界面立即刷新显示。<br>另提供 **Restore All Defaults**：一键把全部设置与键位复位到出厂默认（含键位表）。 |
| **验收方式** | **自测阶段**：`REBIND_ASSIGN`、`REBIND_CONFLICT`。<br>**断言**（四条）：① 无冲突 → `MOVE_FORWARD = UP`；② 冲突 → `conflictOwner=move_forward, pending=key:265 (UP)` → 确认替换后 `MOVE_FORWARD=(none)`；③ 等待中按 ESC → `RELOAD=R` **不变**；④ 恢复默认后**改动项 = 0**。<br>**单测**：`ui/KeyRebindControllerTest.java`。<br>**人工步骤**：把"前进"绑到已被"后退"占用的键 → 看到冲突提示且提示里写清占用方 → 确认 → 后退变为未绑定。 |
| **实现位置** | `ui/KeyRebindController.java`、`settings/KeyBindings.java`、`settings/InputBinding.java`、`ui/Menus.java`（`ID_RESTORE_DEFAULTS`）、`render/ui/MenuRenderer.java`（等待输入与冲突对话框绘制）、`settings/GameSettings.java`（`resetToDefaults()`） |
| **备注（设计理由）** | 「解绑原动作」而非"交换两个动作"是刻意选择：交换是隐含语义，玩家无法预测结果；解绑 + 显式重绑让每一步都可见。依据 `TECH_DESIGN_v0.1.1 §V′` 系列"不许静默"的立场。 |

---

### M15-SPEC-12 · Persistence（设置持久化与容错）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 6 条**（`settings/SettingsStore.java`） |
| **规格原文（可验收行为）** | 设置**独立于世界存档**，落在存档根目录的**同级**：`<配置根>/settings.json`（与 `saves/` 并列）。理由：设置是关于"这台机器上的这个人"，不是关于"这个世界"。<br>复用 `AtomicFileWriter`（文件永远是旧版或新版，不会是半截）。<br>**三档容错，取向是"能起来"**：<br>① 文件不存在 → 用默认值并立刻写出；<br>② 能解析但个别字段坏了 → 逐字段回退默认 + 告警，**其余字段照常生效**；<br>③ 完全无法解析 → 把原文件**改名**（不是删除）为 `settings.json.corrupt-<时间戳>` 留证据，然后用默认值继续跑 + 告警。<br>载入结果以 `LoadResult(settings, status, path, notes)` 返回（不只是写日志），供报告摘录。 |
| **验收方式** | **自测阶段**：`SETTINGS_PERSISTENCE`、`CORRUPT_FALLBACK`。<br>**断言**：① 写盘 → 换路径重新读回一致（内存与磁盘同为 `1.00 / 70 / SPACE`）；② 构造损坏文件 → 状态为 `RECOVERED_FROM_CORRUPT` + 生成 `.corrupt-*` 备份 + **不崩溃**。<br>**单测**：`settings/SettingsStoreTest.java`（报告 §2 标注"第 6 条"）。<br>**人工步骤**：改设置 → 退出 → 重启 → 确认设置仍在；手工把 `settings.json` 改成乱码 → 重启 → 确认不崩溃且回到默认值。 |
| **实现位置** | `settings/SettingsStore.java`（`FILE_NAME` / `Status` 枚举 / `LoadResult` / `.bak` / `.corrupt-<时间戳>`）、`save/AtomicFileWriter.java`、`settings/GameSettings.java`、`settings/KeyBindings.java` |
| **备注** | 三条退出路径（主菜单退出 / 暂停菜单保存返回 / 关窗按钮）**都会写设置**（见 M15-SPEC-14）。 |

---

### M15-SPEC-13 · UI State Machine（界面状态机）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 8 条**（`ui/UiState.java`）、**第 2/3/8/10 条**（`ui/UiStateMachine.java`） |
| **规格原文（可验收行为）** | 四个状态：`MAIN_MENU` / `SETTINGS` / `PLAYING` / `PAUSED`。状态机只决定"能不能从 A 走到 B"，不读键鼠、不画界面、不碰游戏对象（因而可被完整单测，含全部非法迁移）。<br>**非法迁移 = 拒绝 + 告警，不是抛异常**（一次界面误操作不得终止游戏，但要留痕）。<br>**设置菜单来源记忆**：进入 SETTINGS 时记住来源界面，"返回"必须回来源（避免"暂停 → 设置 → 返回"把玩家踢出游戏）。<br>状态迁移的后果由游戏层施加：光标模式（菜单/暂停 = 释放，游玩 = 锁定）、HUD 可见性、是否推进模拟。<br>统计 `pauseCount` / `resumeCount` / `rejectedTransitions`，并保留最近 64 条迁移历史。 |
| **验收方式** | **自测阶段**：`INIT`（初始为 MAIN_MENU）、`OPEN_SETTINGS`、`BACK_TO_MAIN`、`START_GAME`、`PAUSE`、`RESUME`、`SAVE_TO_MAIN`、`QUIT`。<br>**断言**：初始状态为主菜单；`pauseCount` +1；`resumeCount` +1；`rejectedTransitions` 在合法流程中为 0；`mouseCaptured` 三态取值正确（菜单 false / 游玩 true / 暂停 false）。<br>**单测**：`ui/UiStateMachineTest.java`（含全部非法迁移）。<br>**人工步骤**：暂停 → 设置 → 返回 → 必须回到暂停菜单（不是主菜单）。 |
| **实现位置** | `ui/UiStateMachine.java`、`ui/UiState.java`、`render/Window.java`（光标模式由界面状态驱动）、`render/ui/HudRenderer.java`（`showGameplayHud` 受界面状态约束）、`game/SkyIslandGame.java`（把迁移后果施加到运行时） |
| **备注** | ESC 在 M1.5 是 **按下沿**判定（不是电平），否则按住的那几帧会重复触发"暂停/继续"翻转。依据 `TECH_DESIGN_v0.1.1 §A′.3 E-6`。 |

---

### M15-SPEC-14 · Clean Exit（干净退出）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 10 条**（`game/SkyIslandGame.java`：第 323 行"实现都会绕过存档与 native 资源释放 —— 这正是 M1.5 规格第 10 条禁止的"；第 1440 / 1449 行） |
| **规格原文（可验收行为）** | **三条退出路径必须走完整收尾，不绕过任何一步**：<br>① 主菜单「Quit Game」；② 暂停菜单「Save & Return to Main Menu」；③ 窗口关闭按钮（语义与"退出游戏"一致）。<br>收尾顺序：**保存（世界 + 设置）→ 释放 GL 资源 → 终止 GLFW**。<br>**禁止**用 `System.exit()` 或窗口回调直接掐断进程。 |
| **验收方式** | **自测阶段**：`QUIT`（主菜单退出 → 请求收尾）。<br>**断言**：`quitRequested` 由状态机置位；收尾三阶段均被执行（日志可查），退出后无残留 GL/native 告警。<br>**人工步骤**：三条路径各走一遍 → 确认每次都落盘（重进后世界与设置均保留）且进程干净退出。 |
| **实现位置** | `game/SkyIslandGame.java`（收尾流程、`quitRequested` 处理）、`ui/UiStateMachine.java`（`isQuitRequested()`）、`save/SaveManager.java`、`settings/SettingsStore.java`、`render/Renderer.java`（GL 资源释放）、`render/Window.java`（GLFW 终止） |
| **备注** | 退出时的同步存档会造成 **213–228 ms** 的主循环停顿（**T-9**），M1.5 范围内不修，已登记，建议归属 M2 / Optimization。**不得通过"改成不存档"来消掉这个尖峰。** |

---

### M15-SPEC-15 · Human Test（人工试玩）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 报告 §2 未在表中单列；依据报告 §5.1 / §5.3 / §5.4 与 §8 STOP 声明 |
| **规格原文（可验收行为）** | **M1.5 的验收包含一次真实人工试玩**（不是可选）。人工试玩只负责"手感与可玩性"以及对"看得见的东西"的最终确认，**不承担"功能是否存在"的举证责任**（后者由进程内脚本化自测承担，依据 `TECH_DESIGN_v0.1.1 §T′.1`）。<br>人工试玩在本轮实际产出三个**自动化未能发现**的问题，因此它是门禁的正式一环：<br>① **松键后玩家一直平移**（真实产品缺陷，两个根因）；<br>② **PRD【MVP 必须】的「破坏反馈」从未实现**（规格缺口，登记为 T-8.9）；<br>③ 帧级输入量错配（由 M1.5 的"帧粒度"注入器暴露，人工试玩的症状是"视角时灵时不灵"）。 |
| **验收方式** | **人工步骤**（无断言）：启动游戏 → 用真实键鼠走一遍 主菜单 → 设置 → 开始游戏 → 移动 → 挖掘 → 暂停 → 恢复 → 保存返回 → 退出。<br>**回归守门人**（人工发现后必须钉上可执行断言）：<br>· `PlayerPhysicsTest` 新增 5 例（松键 1 s 后水平速度 `< 1e-6`；0.25 s 后 `< WALK_SPEED × 0.02`；滑行距离吻合；空中保留动量；反向输入不翻到对侧）；<br>· `InputStateTest` 新增 7 例（失焦释放 W / 鼠标键 / 按下沿；不失焦不释放；失焦保留统计；失焦后可再按；可重复调用）；<br>· 界面自测新增 2 条（松键 30 逻辑步后水平速度 `3.923e-04 格/秒` < 1e-3；滑行 `0.2044 格` < 0.4）；<br>· `CrackOverlayTest` 10 例 + 界面自测 `MINE_BY_MOUSE` 阶段 4 条断言。 |
| **实现位置** | 无单一实现类。关联：`game/M1_5UiSelfTest.java`（阶段 `MOVEMENT_INPUT` / `MINE_BY_MOUSE`）、`player/Player.java`、`input/InputState.java`、`render/mesh/CrackOverlay.java` |
| **备注（教训，写在这里以免重犯）** | ① **验证"启动"的断言不能替代验证"停止"** —— 任何由输入驱动的持续状态，都必须有一条"输入撤走之后会收敛"的断言。<br>② **表现层"画出来了"只能由像素证明，不能由断言证明**（`TECH_DESIGN_v0.1.1 §V′.5`）。<br>③ 阈值必须与**物理时间常数**挂钩，不得写 `assertEquals(0.0, ...)`。 |

---

### M15-SPEC-16 · Gate（M1.5 门禁判定）

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 规格**第 11 / 12 条**（`game/M1_5UiSelfTest.java`「进程内脚本化自测」，`game/M1ScriptedSelfTest.java` 回归） |
| **规格原文（可验收行为）** | M1.5 门禁 = **A ∧ B ∧ C ∧ D ∧ E ∧ F ∧ G 七维全部通过**（不是"一次运行一个布尔值"）：<br>**A 前段界面闭环**：界面自测 17 阶段全部断言通过（59/59，0 失败）。<br>**B 玩法层未被改坏**：M1 自测 27 项全绿且 `m1_selftest_scope = full`。<br>**C 稳态性能**：`p95 ≤ 16.7 ms` 且 `> 50 ms 卡顿 = 0`（**只由 run C 判定**）。<br>**D 图形与资源健康**：三次运行均无 GL 错误、无 native 泄漏告警、退出走完整收尾。<br>**E 缺陷回归**：试玩暴露的每个缺陷各有可执行守门断言。<br>**F 单测**：`mvn test` 全绿（34 测试文件 / 555 用例 / 0 失败）。<br>**G 规格缺口记账**：发现的规格缺口已处置或登记（本轮为 T-8.9）。<br>**硬规则**：**不通过"改判定口径 / 改阈值 / 缩测量窗口"让门禁变绿。** |
| **验收方式** | 三次**互相独立**的证据运行：<br>**run A（界面自测）**：`-Dskyisland.uiSelfTest=true` → 17 阶段 / 59 断言 / 0 失败 / 364 帧 / 6.4 s 自动退出。<br>**run B（M1 自测回归）**：`-Dskyisland.selfTest=true` → 27 项全绿、`scope = full`、p95 = 0.623 ms。<br>**run C（真实输入稳态性能）**：`-Dskyisland.measureSeconds=8 -Dskyisland.noSave=true` → p95 = **0.758 ms**、`> 50 ms` 卡顿 **0**、`perf_gate_met = true`、TPS 60.12。<br>三次运行的设置文件、存档目录、日志目录、截图目录**各自独立**，互不污染。 |
| **实现位置** | `game/M1_5UiSelfTest.java`（17 阶段枚举 + 59 断言）、`game/M1ScriptedSelfTest.java`（M1 27 项）、`game/FrameStats.java`（定宽桶直方图）、`game/GameLoop.java`、`render/Screenshot.java`（每阶段抓帧）、`game/Version.java`（`0.2.5-M1.5-FRONTEND`） |
| **备注** | **run A 的 p95（13.6 ms）与 `spikes_gt_50ms = 8`、`max = 1315 ms` 不得被解释为"性能不达标"** —— 该脚本主动切换 VSync 且含窗口创建/写盘等一次性开销。正确的做法是**解释它，而不是修掉它**（报告 §7.2）。 |

---

### M15-SPEC-17 · 启动契约（命令行参数）—— 补充登记

| 项 | 内容 |
|---|---|
| **代码注释原编号** | 报告 §2 末行「启动契约（命令行参数）」；`game/SkyIslandGame.java` 的 `M1Config` 与「M1.5 新增 `uiSelfTest` 与 `startState`」 |
| **规格原文（可验收行为）** | 所有运行参数可被**系统属性**覆盖，便于自动化复现与不同门禁口径复用。<br>M1.5 新增两个：`skyisland.uiSelfTest`（跑前段界面自测）、`skyisland.startState`（`menu` / `playing`，决定初始界面）。<br>**规则**：自动化运行（自测或性能测量）一律从 `playing` 开始 —— 否则性能运行会停在主菜单里，永远达不到测量窗口、也永远不会退出。<br>**默认不自动退出**：只有显式给 `measureSeconds > 0` 或自测开关时才自动退出，不能靠默认值把人工试玩一起掐掉。<br>**M1 的 `main` 从不读 `args` 的缺陷在 M1.5 修复**（`main(args)` → 系统属性）。 |
| **验收方式** | **单测**：`game/CommandLineOverridesTest.java`。<br>**人工步骤**：`run-m1.bat -Dskyisland.uiSelfTest=true` / `-Dskyisland.selfTest=true` / `-Dskyisland.measureSeconds=8 -Dskyisland.noSave=true` 三条命令各自能正确改变行为。 |
| **实现位置** | `game/SkyIslandGame.java`（`record M1Config`、`fromSystemProperties()`、`main(args)`）、`game/CommandLineOverridesTest.java` |
| **备注** | 启动脚本 `run-m1.bat` 默认带 `--enable-native-access=ALL-UNNAMED`（噪音治理建议，**不是运行必需**，去掉它游戏必须仍能正常运行 —— 依据 `TECH_DESIGN_v0.1.1 §A′.2`）。 |

---

## 2. 与 PRD / 技术基线的对照

### 2.1 本文件覆盖的 PRD 条目

| M15-SPEC | 直接对应的 PRD Requirement（MVP-AUDIT-D1 编号） |
|---|---|
| 01 Main Menu | MVP-UI-021（新的世界）、MVP-UI-023（设置）、MVP-UI-024（退出游戏）、MVP-UI-025（无联机入口）；**MVP-UI-022（继续游戏）未实现** |
| 02 Pause Menu | MVP-SAVE-022（暂停时世界暂停）、MVP-SAVE-020（暂停菜单提供"保存"） |
| 03 Settings | MVP-SET-001、006、007、009、010、003、004（已实现 7 项）；**未实现 4 项：MVP-SET-002 视距 / MVP-SET-005 死亡掉落 / MVP-SET-008 界面语言 / MVP-SET-011 亮度** |
| 04 / 05 | MVP-SET-001、MVP-SET-010 |
| 06 | MVP-SET-009 |
| 07 | MVP-SET-006 |
| 08 | MVP-SET-007；与 MVP-HUD-013（F3 调试信息）是两条独立行为 |
| 09 | MVP-SET-003、MVP-SET-004（**无消费方**） |
| 10 / 11 | PRD 6.5「按键自定义」=【后续迭代】（**已实现，见 C-02**）；键位默认值对齐 MVP-KEY-001…011 |
| 12 | MVP-SAVE-015（原子写入）、MVP-SAVE-016 + MVP-SAVE-018（保留 `.bak` / 读取失败回退）——同一套契约在设置文件上的应用 |
| 13 | MVP-SAVE-022（暂停时世界暂停）、MVP-SAVE-023（界面打开时世界继续运行）、MVP-UI-008 / MVP-UI-009（鼠标显示光标且不再控制视角 / 关闭后恢复视角控制） |
| 14 | MVP-SAVE-019（退出时自动保存）、MVP-UI-024 |
| 15 / 16 | MVP-GATE-015（人工试玩 / M3-GATE）、M1.5 报告 §7.1 |

### 2.2 本文件**不**覆盖的（不要误读为"已完成"）

- PRD 6.1 的 HUD 9 项里，**只有**"准星"与"F3 调试信息"与 M1.5 有关；生命条 / 快捷栏 / 弹药计数 / 枪械名 / 天数时间 / 低血预警 / 即时提示 **7 项不在 M1.5 范围**（`_audit_wip/D1_requirement_inventory.md` SPEC_DRIFT **B-03**）。
- 背包界面（MVP-UI-001…012，按行为拆分，原 8 条）与合成界面（MVP-UI-013…020，按行为拆分，原 7 条）**均不在 M1.5 范围**。
- 破坏反馈的**音效**与破坏粒子不在 M1.5 范围（**T-8.9**）。
- 存档异步化不在 M1.5 范围（**T-9**）。

---

## 3. 修订记录

| 版本 | 日期 | 修订人 | 修订内容 |
|---|---|---|---|
| v1.0 | 2026-09-19 | 文策渊（design-strategist） | 首版登记。17 条规格条目入库（16 项任务书要求 + 1 项补充的启动契约）。编号体系 `M15-SPEC-01..17`；保留代码注释的「第 N 条」双向映射。来源为 M1.5 报告 §2/§3 与源码实现契约反推，**未新增任何需求**。 |

---

*本文件是 M1.5 Front-End & Settings Shell 的规格条目登记。上游：`docs/design/PRD_v0.3.1.md` / `docs/architecture/TECH_DESIGN_v0.1.md` / `TECH_DESIGN_v0.1.1.md`。*
*验收见 `docs/testing/M1_5_FRONTEND_SETTINGS_REPORT.md`；需求全量清单与 SPEC_DRIFT 见 `docs/testing/_audit_wip/D1_requirement_inventory.md`。*

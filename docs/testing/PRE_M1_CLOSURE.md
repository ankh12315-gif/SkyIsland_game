# Pre-M1 Closure 记录

- **项目**：SkyIsland（空岛生存）— Java + LWJGL 自研体素游戏引擎
- **阶段**：Pre-M1 Closure（M1 First Playable 之前的技术口径闭合）
- **日期**：2026-09-19
- **上游**：`docs/design/PRD_v0.3.1.md`、`docs/architecture/TECH_DESIGN_v0.1.md`、`docs/testing/M0_REPORT.md`
- **本轮产物**：`docs/architecture/TECH_DESIGN_v0.1.1.md`（新建）、本文件
- **结论**：**Phase A Gate 通过（7/7）**；T-2 / T-3 / T-4 / T-6 **全部 CLOSED**

---

## 1. 关闭项一览

| 项 | 内容 | 状态 | 证据位置 |
|---|---|---|---|
| **T-2** | 物理键盘人工确认 | **CLOSED** | 本文件 §2 |
| **T-3** | 冻结 Java 字节码策略 | **CLOSED** | §3；`TECH_DESIGN_v0.1.1` §A′.1 |
| **T-4** | native-access 决策 | **CLOSED** | §4；`TECH_DESIGN_v0.1.1` §A′.2 |
| **T-6** | M0 勘误回写 | **CLOSED** | §5；`TECH_DESIGN_v0.1.1` §C.4′ |
| T-1 | 安静环境零 `>50 ms` 验证 | **DEFERRED** → Optimization / Performance Certification | §7 |
| T-5 | LWJGL `Unsafe` → FFM 迁移 | **WATCHLIST** | §7 |

---

## 2. T-2：物理键盘确认

### 2.1 T-2 原本要回答的问题

M0 结束时，`PostMessageW` 通道已证明**窗口过程 → GLFW → 回调**这一段完整可用
（12 个键事件、扫描码逐一吻合、F3 触发真实语义响应），但**没有**证明
「按住真实键盘时，操作系统会把按键投递到本窗口」。M0 当时的证据缺口来自 AWT Robot
通道：鼠标移动恒有事件、键盘恒为 0。**这个缺口属于"未验证区域"，因此不能签字。**

### 2.2 本轮采用的方法：`SendInput`（与物理键盘同一条系统输入队列）

选择 `SendInput` 而不是继续用 `PostMessageW`，是因为前者**注入系统输入队列**，
由操作系统按前台规则投递；后者直接调用窗口过程，**绕过了队列**。
只有前者能回答"OS 会不会把按键送进来"。

探针（`tmp/t2_sendinput.ps1`）的关键设计：

1. **强制取得前台 + 键盘焦点**：`AttachThreadInput` 附加到当前前台线程后
   `BringWindowToTop` + `SetForegroundWindow` + `SetFocus`，失败时用 ALT 解锁技巧重试（最多 5 次）。
2. **不只看 `GetForegroundWindow`**：前台窗口 ≠ 拥有键盘焦点的窗口。
   必须用 `GetGUIThreadInfo().hwndFocus` 与 `hwndActive` **同时**验证。
3. **发射前自检**：`INPUT` 结构体 `Marshal.SizeOf == 40`（尺寸错会让 `SendInput` 静默失败，
   产生假阴性）。
4. **调用方隐藏控制台窗口**（`windowsHide`）：否则 `java.exe` / `powershell.exe` 自己的控制台
   会抢走激活状态，污染正在测量的焦点。

### 2.3 实测结果（4 轮）

前置条件全部满足——**这不是焦点问题**：

```
T2_PROBE input_struct_size=40 expected=40
T2_PROBE grab_attempts=1 (0=never settled)
T2_PROBE foreground_matches=True focus_matches=True active_matches=True
T2_PROBE recheck_foreground_matches=True recheck_focus_matches=True
T2_PROBE thread_id=16924 keyboard_layout=0x08040804 ime_open=False
```

| 轮次 | 时间 | 注入内容 | 事件数 | 应用侧实际收到 |
|---|---|---|---|---|
| 1 | 16:26 | W A S D SPACE 1 2 ESC（扫描码编码） | 16 | **仅 ESC** |
| 2 | 16:29 | 同上，加焦点双重验证 | 16 | **仅 ESC** |
| 3 | 16:31 | 三轮：A 扫描码 / B 扫描码 / C 虚拟键编码 | 44 | **仅 ESC** |
| 4 | 16:34 | 键类选择性探针（F3/W 交替）→ 切换 en-US 布局 → 全键集 | 28 | **0 个** |

第 3 轮中**两种编码（扫描码 / 虚拟键）全部丢失**，说明丢失点在编码层之下。

### 2.4 对照实验：把"引擎缺陷"与"环境约束"分开

**实验 A**：本进程自建 WinForms 窗口，用与上面完全相同的手法抢前台，然后注入 8 个按键。

```
CTRL form_is_foreground=False
CTRL after_grab foreground_and_focus_ok=True      <- 双重验证通过
CTRL   scan=0x11 sent=2   (W, 扫描码编码)
CTRL   scan=0x1E sent=2   (A)
CTRL   scan=0x1F sent=2   (S)
CTRL   scan=0x20 sent=2   (D)
CTRL   vk=0x57 sent=2     (W, 虚拟键编码)
CTRL   vk=0x41 sent=2     (A)
CTRL   vk=0x53 sent=2     (S)
CTRL   vk=0x44 sent=2     (D)
CTRL key_events_received=0
```

**结论**：一个**自己拥有**的窗口，在**前台 + 键盘焦点双重验证通过**的情况下，
注入 8 个按键收到 **0 个** `KeyDown`。**合成键盘输入被拦在窗口过程之前，
与 GLFW、与 SkyIsland 无关。**

**实验 B**：真实键盘事件的到达能力（对照组反向验证）

系统日志中存在**真实键盘**产生并被正确解码的事件：

```
键盘事件: PRESS    key=LEFT_ALT (342)  scancode=56  mods=0x4
键盘事件: RELEASE  key=LEFT_ALT (342)  scancode=56  mods=0x0
```

`LEFT_ALT` 不是任何注入器发出的（探针仅在首次抢前台失败时才发 ALT，而实测
`grab_attempts=1`，未走该分支）。这两组事件（15:33、16:26 两次运行）后面紧跟
`窗口失去焦点` 回调 —— 即**真实键盘 → OS 输入队列 → 本窗口 → GLFW → 回调**这条链路
**被真实数据走通了**。

### 2.5 T-2 判定：**CLOSED**

| 待验证环节 | 结论 | 证据 |
|---|---|---|
| 物理键盘被 OS 路由到本窗口 | **成立** | `LEFT_ALT (342) scancode=56 mods=0x4` 真实按键，两次运行 |
| 窗口过程 → GLFW → 回调（全键集） | **成立** | M0 `PostMessageW`：12 事件 / 6 键，扫描码 `0x11 0x1E 0x1F 0x20 0x39 0x3D` 与 GLFW 键值 `87 65 83 68 32 292` 逐一吻合，含 F3 语义响应 |
| 合成输入能否替代人工做自动化验证 | **不能（环境约束）** | 实验 A；见 `TECH_DESIGN_v0.1.1` §T′ TR7 |

**残留项（已定性，不阻塞）**：字母 / 数字键**被真实键盘按下时**的日志尚未被人眼看过一次。
这不是代码问题（同一条 WndProc → 键码表路径已被 ALT 与合成通道双向证明），
而是本机无法自动化。**建议用户执行一次 20 秒确认**：

```bat
run-m0.bat -Dskyisland.measureSeconds=0
```

窗口出现后**用鼠标点一下窗口**，依次按 `W A S D 空格 1 2`，再按 `ESC`。
随后在 `logs\skyisland-*.log` 中搜索「键盘事件」即可看到每个键的
`key / scancode / PRESS|RELEASE`，并且 `ESC` 必须让程序退出（退出码 0）。

### 2.6 由 T-2 顺带发现并修复的两个真实缺陷

#### I-14：ESC 退出使用电平判定，快速按点会被完全漏掉

M0 的退出路径是 `input.isKeyDown(GLFW_KEY_ESCAPE)` —— **电平**判定。
若"按下"与"抬起"落在**同一个 `glfwPollEvents()` 批次**内，批次结束后状态已回到 `false`，
**这次按键被完全丢弃，窗口不关闭**。

**实测**：探针注入的 ESC 在应用侧记录为

```
[16:26:19.423] 键盘事件: PRESS    key=ESCAPE (256)  scancode=1  mods=0x0
[16:26:19.423] 键盘事件: RELEASE  key=ESCAPE (256)  scancode=1  mods=0x0
```

**两条事件时间戳完全相同**（同一批次），程序未退出，直到 90 秒后被外部终止。
这解释了本轮 4 次运行"ESC 到达了却不退出"的现象。

**处置**：M1 起所有"按一下触发一次"的动作键改为**按下沿触发**（`TECH_DESIGN_v0.1.1` §A′.3）。

#### I-15：`InputState.onKey` 静默丢弃无法映射的键

原实现在 `key < 0 || key >= KEY_TABLE_SIZE` 时直接 `return`，不留任何记录。
后果是两种完全不同的根因产生**一模一样**的观测：

- (a) 事件根本没到达窗口过程；
- (b) 事件到达了，但 GLFW 无法把 scancode 映射为键码。

这正是本轮定位过程一度停滞的原因。

**处置**：已改为记录 `key=UNMAPPED` + scancode，并单独计数
`unmappableKeyEventCount`（`TECH_DESIGN_v0.1.1` §A′.3）。
改动后重新构建并通过：`Tests run: 32, Failures: 0`。

> 这两个缺陷属于同一类：**测量环节掩盖了真相**。与 M0 的 I-1…I-5 同源。

---

## 3. T-3：字节码策略冻结（CLOSED）

| 项 | 裁决 |
|---|---|
| 开发 / 构建 JDK | **JDK 25** |
| `maven.compiler.release` | **21** |
| 产品代码 | **不得直接依赖 Java 21 之后才存在的 API** |
| FFM | 本项目**不直接使用** |
| LWJGL 内部 native 实现 | 第三方内部实现，**不因此**要求产品代码升到 release 25 |

理由：游戏没有 FFM 的产品需求；为了辅助能力把运行基线锁死到 Java 25 没有收益，
并降低发行兼容性。M0 遇到的 `java.lang.foreign` 预览 API 问题**已用进程外方案绕过**，不再阻塞。

> 落地保障：`src/main` 中若出现 `import java.lang.foreign.*`，构建会直接失败
> （预览 API 默认禁用）。**这个失败是期望行为。**

---

## 4. T-4：native-access 决策（CLOSED）

| 项 | 裁决 |
|---|---|
| 开发 / 测试启动脚本默认参数 | **加入 `--enable-native-access=ALL-UNNAMED`** |
| 性质 | 消除 JDK 25 的 native-access 告警（J-1）；**不是游戏逻辑依赖** |
| 条件 | 去掉该参数后游戏必须仍能正常运行（最小参数集 = 空集） |
| LWJGL `Unsafe` 告警（J-2） | **不尝试**用 JVM 参数隐藏（实测无任何参数可抑制） |
| 已落地 | `run-m0.bat` 引入 `DEFAULT_JVM_ARGS`；校验为纯 ASCII + CRLF |

---

## 5. T-6：M0 勘误回写（CLOSED）

已产出 `docs/architecture/TECH_DESIGN_v0.1.1.md`：
**不重写 v0.1**，只写"被实测推翻 / 需收紧 / 需冻结"的部分，并给出引用替换表。

核心新增是 **§C.4′ Performance Measurement Contract（10 条）**，
把 M0 用三次错误结论换来的教训固化为硬约束，其中包括：

- 统计必须覆盖完整 measure window，不允许固定容量 ring buffer 只统计窗口末尾；
- P95 / P99 必须基于完整窗口直方图，且必须声明分辨率；
- FPS 必须由完整 frame count / elapsed 计算；
- raw frame delta 与 clamped logic delta 必须分开，clamp 次数独立记录；
- 测试脚手架不得同步运行在 game loop 内制造假卡顿；
- warm-up 数据不得进入统计；
- OS / 环境 spike 与持续引擎卡顿必须区分（必须提供对照实验）；
- **M0 的 FPS 不得作为 M1 / M2 / M3 的外推依据**。

同时把 v0.1 §S 的 M1 模块范围做了逐项裁定（`§S′`），
把 `entity.*`、`render.texture`、正式 `world.gen` 明确顺延并说明理由，
避免"M1 期间实现没有使用者的模块"这种范围蔓延。

---

## 6. Phase A Gate 检查表

| # | 条件 | 结果 | 证据 |
|---|---|:--:|---|
| 1 | T-2 CLOSED | ✅ | §2.5 |
| 2 | T-3 CLOSED | ✅ | §3 |
| 3 | T-4 CLOSED | ✅ | §4 |
| 4 | T-6 CLOSED | ✅ | §5 |
| 5 | `TECH_DESIGN_v0.1.1` 已生成 | ✅ | `docs/architecture/TECH_DESIGN_v0.1.1.md` |
| 6 | `mvn clean package` 仍 PASS | ✅ | `BUILD SUCCESS`；`Tests run: 32, Failures: 0, Errors: 0, Skipped: 0`；10 个主源文件 + 1 个测试源文件编译，**编译器告警 0 条** |
| 7 | M0 基础程序仍正常运行 | ✅ | 本轮 5 次启动全部正常：窗口/上下文建立、TPS 59.5–60.0、FPS 3300–4255、退出码 0、无残留进程 |

**Gate 判定：通过（7/7）。**

---

## 7. 不阻塞项（明确保留）

| 项 | 状态 | 处置原则 |
|---|---|---|
| **T-1** 安静环境下的零 `>50 ms` spike 验证 | **DEFERRED → Optimization / Performance Certification** | 不得为了关闭它升级依赖或重构工程 |
| **T-5** LWJGL `Unsafe` → FFM 后端迁移 | **WATCHLIST** | 跟踪 LWJGL 后续版本成熟度；当前无参数可抑制 |
| **T-8** M1 技术债（TextureArray / 正式 IslandGenerator / 实体系统缺失） | M1 报告登记 | 见 `TECH_DESIGN_v0.1.1` §U′.2 |
| 字母键的一次人工过目（T-2 残留） | 建议执行 | §2.5 的 20 秒步骤 |

---

## 附录：本轮新增/修改的文件

| 文件 | 性质 |
|---|---|
| `docs/architecture/TECH_DESIGN_v0.1.1.md` | **新建**：M0 勘误与决策增补 |
| `docs/testing/PRE_M1_CLOSURE.md` | **新建**：本文件 |
| `src/main/java/com/skyisland/input/InputState.java` | 修改：记录不可映射键 + 计数（I-15） |
| `src/main/java/com/skyisland/game/SkyIslandGame.java` | 修改：摘要输出 `不可映射键事件数` |
| `run-m0.bat` | 修改：默认 `--enable-native-access=ALL-UNNAMED`（T-4） |
| `tmp/t2_sendinput.ps1` | 新增：T-2 探针（SendInput + 焦点双重验证） |
| `tmp/run_t2.js` | 新增：T-2 运行器（windowsHide） |
| `tmp/control_sendinput.ps1` / `tmp/run_control.js` | 新增：对照实验 |
| `tmp/build.js` / `tmp/build.log` | 新增：构建运行器与日志 |

---

**Pre-M1 Closure 结束。进入 M1 First Playable / Voxel Sandbox。**

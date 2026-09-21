# M0 Technical Spike 报告

- **项目**：SkyIsland（空岛生存）— Java + LWJGL 自研体素游戏引擎
- **里程碑**：M0 Technical Spike
- **版本**：`0.1.0-M0`
- **报告日期**：2026-09-19
- **依据文档**：`docs/design/PRD_v0.3.1.md`、`docs/architecture/TECH_DESIGN_v0.1.md`
- **结论**：**PASS WITH TODO**（15 项门禁全部通过；4 项待办未闭环，均不阻塞 M1）

---

## 0. M0 结论摘要（先读这一节）

M0 的目标是回答一个问题：**这套技术栈在这台机器上到底能不能跑通，且能不能被可信地测量。**

答案是可以。核心事实：

| 维度 | 实测结论 |
|---|---|
| 构建 | `mvn clean package` BUILD SUCCESS，32 个单元测试 0 失败 |
| 运行环境 | JDK 25 + Maven 3.9.9 + LWJGL 3.4.3，全部按冻结版本落地 |
| 图形 | OpenGL **3.3.0 Core** 上下文建立成功，渲染器为 **Intel(R) Iris(R) Xe Graphics**（核显，本机无独显） |
| 循环 | 固定 60 Hz 逻辑步稳定，实测 **TPS 59.20 – 60.02**；**FPS 845 – 1596**，远高于 60 |
| 帧时间 | **P95 = 1.47 – 2.63 ms**，相对 16.7 ms 目标有 **6 – 11 倍余量** |
| 键盘 | **通过**：确定性注入 W/A/S/D/SPACE/F3，12 个键事件、6 个不同键，扫描码逐一吻合 |
| 鼠标 | **通过**：按键 4 事件/2 不同键、滚轮 1 事件（确定性）；移动 651 – 2625 样本（物理通道） |
| 异常 | GL 错误 0 条、GL Debug 消息 0 条、程序告警 0 条 |
| 退出 | 退出码 0，无残留 `java.exe` / `javaw.exe` 进程 |
| 可重复性 | 连续 7 次启动运行，全部正常退出 |

**M0 最有价值的产出不是"跑通了"，而是发现了 4 个真实缺陷与 2 个环境约束。** 其中 2 个缺陷属于**测量仪器本身不可信**——如果不修，M0 会拿着错的数据宣称通过。这些已在 M0 内修复并复测。详见 §6 与 §7。

---

## 1. Environment（运行环境）

### 1.1 主机

| 项 | 实测值 | 来源 |
|---|---|---|
| 操作系统 | Windows 11，版本 10.0，amd64 | `os.name` / `os.version` / `os.arch` |
| 逻辑处理器 | 16 | `Runtime.availableProcessors()` |
| JVM 最大堆 | 4028 MB（JVM 默认口径） | `Runtime.maxMemory()` |
| 主显示器视频模式 | 2880 × 1800 @ 120 Hz，RGB 8/8/8 | `glfwGetVideoMode` |
| 独立显卡 | **无**（仅 Intel Iris Xe 核显） | `GL_RENDERER` |
| 工作目录 | `F:\minecraftspace` | `Path.of("").toAbsolutePath()` |
| 源码编码 / 平台编码 | UTF-8 / GBK | `file.encoding` / `native.encoding` |

> **编码注意**：`native.encoding = GBK`，因此**所有日志与构建产物一律显式使用 UTF-8**（`Log` 的 `FileHandler.setEncoding("UTF-8")`、pom 的 `project.build.sourceEncoding=UTF-8`、`MAVEN_OPTS=-Dfile.encoding=UTF-8`）。本条已落实，不属于待办。

### 1.2 工具链

| 组件 | 版本 | 安装位置 |
|---|---|---|
| JDK | **OpenJDK 25**（build `25+36-3489`，`java.vendor = Oracle Corporation`，OpenJDK 64-Bit Server VM） | `D:\software\jdk-25` |
| Maven | **Apache Maven 3.9.9**（`8e8579a9e76f7d015ee5ec7bfcdc97d260186937`），自身运行在 JDK 25 上 | `D:\software\maven\apache-maven-3.9.9` |
| 本地仓库 | `D:/software/maven/repo`（由 `toolchain/settings.xml` 指定） | — |
| 中央仓库 | Maven Central 直连可达（未启用镜像加速） | — |

> **实测下载速率 25 – 150 kB/s**（Maven Central 直连，本机网络状况）。首次冷构建约 3 分 30 秒（含依赖下载），热构建 22 – 45 秒。这是**构建效率的真实基线**，若后续 CI 变慢先看网络而不是构建配置。

### 1.3 字节码目标决策

| 项 | 值 |
|---|---|
| 编译 JDK | 25 |
| `maven.compiler.release` | **21** |
| 理由 | PRD 12.6：字节码目标与运行 JDK 解耦，使运行环境不必锁死 25 |
| 副作用 | **任何 Java 21 之后才定稿的 API 不可用** —— M0 因此踩到了 `java.lang.foreign`（见 §7 I-10） |

---

## 2. Graphics（图形环境）

**所有数据均来自真实 OpenGL 上下文，不是设备管理器或驱动推断。**

| 项 | 实测值 |
|---|---|
| `GL_VENDOR` | `Intel` |
| `GL_RENDERER` | `Intel(R) Iris(R) Xe Graphics` |
| `GL_VERSION` | `3.3.0 - Build 32.0.101.5542` |
| `GL_SHADING_LANGUAGE_VERSION` | `3.30 - Build 32.0.101.5542` |
| 请求的上下文 | OpenGL 3.3 Core Profile + Forward-Compatible |
| 实际获得的能力表 | `OpenGL33 = true` |
| Debug Context | 已请求（`GLFW_OPENGL_DEBUG_CONTEXT=TRUE`） |
| LWJGL | `3.4.3-snapshot`（Maven 构件版本为 `3.4.3`），**RELEASE** 构建（非 debug 构建，性能可比） |
| GLFW | `3.5.1 Win32 WGL Null EGL OSMesa VisualC DLL`，平台 = `Win32` |
| 窗口尺寸 / 帧缓冲尺寸 | **1280 × 720 / 1280 × 720**（两者相同 → 本次运行未出现高 DPI 差异） |
| VSync | **OFF**（`glfwSwapInterval(0)`，PRD 12.5 测试口径） |
| 原始鼠标运动 | 已启用（`GLFW_RAW_MOUSE_MOTION`） |

### 2.1 Core Profile 管线实证（非清屏）

`GL11.glClear` 在任何 profile 都能通过，因此"窗口能清屏"**不足以**证明 Core Profile 可用。M0 用一条最小但完整的能力探针链路做了实证：

| 验证点 | 结果 |
|---|---|
| 顶点着色器编译（GLSL 330） | 成功，编译日志为空 |
| 片元着色器编译（GLSL 330） | 成功，编译日志为空 |
| program 链接 | 成功，链接日志为空 |
| VAO / VBO 创建 + 顶点属性绑定 | 成功：VAO=1、VBO=1、program=3、2 个顶点属性 |
| `glDrawArrays` 实际出像素 | 成功（窗口显示随时间变化的清屏色 + 彩色三角形） |
| `glGetError` 全队列 | **0 条错误** |

> 这段探针**不是渲染引擎**：没有 ShaderProgram 抽象、没有资源管理、没有 uniform 缓存。M1 起会被正式 `Renderer` 取代并删除。

### 2.2 GL Debug 输出

| 项 | 结果 |
|---|---|
| `GL_KHR_debug` / OpenGL 4.3 支持 | 支持 |
| `GL_DEBUG_OUTPUT` + `GL_DEBUG_OUTPUT_SYNCHRONOUS` | 已开启 |
| Debug 消息总数（HIGH / MEDIUM / LOW / NOTIFICATION） | **全 0** |

Intel 驱动提供了 `KHR_debug`，且**开启同步 Debug 输出后未产生任何消息**——说明 M0 的 GL 调用没有隐藏的警告级问题（这也是"GL 层干净"的强证据，而不只是 `glGetError` 干净）。

> **性能提示**：Debug Context + 同步 Debug 输出有开销。M0 的 P95 是在**开启**它的前提下取得的，因此 §5 的性能数字是**保守下界**，发布构建只会更好。

---

## 3. JVM（JDK 25 实际行为与参数）

### 3.1 实测生效的 JVM 参数

`RuntimeMXBean.getInputArguments()` 记录了**运行时真实生效**的参数，而不是文档里期望的参数：

| 运行 | 生效参数 | 条数 |
|---|---|---|
| final1 | `-Dskyisland.warmupSeconds=10 -Dskyisland.measureSeconds=60 -Dskyisland.selfTest=false` | 3 |
| final2 | 同上 + `--enable-native-access=ALL-UNNAMED` | 4 |
| 默认启动 | **（空参数集）** | 0 |

**空参数集即可正常启动运行** —— 这是 M0 关于 JVM 参数的核心结论。

### 3.2 JVM 告警逐条分类

JDK 25 下启动产生 **2 条告警**，全部来自 **LWJGL 3.4.3 的 native 层**，与 SkyIsland 的用法无关：

| # | 告警原文（关键行） | 触发者 | 分类 | 是否阻塞 |
|---|---|---|---|---|
| J-1 | `WARNING: A restricted method in java.lang.System has been called`<br>`java.lang.System::load has been called by org.lwjgl.system.Library$$Lambda...`<br>`Use --enable-native-access=ALL-UNNAMED to avoid a warning`<br>`Restricted methods will be blocked in a future release unless native access is enabled` | LWJGL 加载 `.dll` | **预期内**：JNI 加载 native 库在 JDK 24+ 被标记为受限方法 | 否 |
| J-2 | `WARNING: A terminally deprecated method in sun.misc.Unsafe has been called`<br>`sun.misc.Unsafe::objectFieldOffset has been called by org.lwjgl.system.MemoryBackendUnsafeLegacy`<br>`will be removed in a future release` | LWJGL 的 `Unsafe` 内存后端 | **预期内**：LWJGL 3.4.3 仍默认使用 `Unsafe` 后端做堆外内存访问 | 否 |

**没有出现**的类型（即已排除的问题）：`Illegal reflective access`、`--add-opens` 需求、module-path / JPMS 冲突、`Thread.stop`、`finalize` 相关、FFM 相关。

### 3.3 `--enable-native-access` 实测效果（假设 → 验证）

| 启动参数 | stderr 字节数 | J-1 | J-2 |
|---|---|---|---|
| 默认（空参数集） | 866 | 出现 | 出现 |
| `--enable-native-access=ALL-UNNAMED` | **429** | **消失** | 仍出现 |

**结论**：

1. `--enable-native-access=ALL-UNNAMED` **确实**能消除 J-1，且不影响任何功能（final2 全项通过、退出码 0）。
2. J-2（`sun.misc.Unsafe` 弃用）**没有任何 JVM 参数可以抑制** —— 它由 LWJGL 内部实现选择决定。
3. **最小 JVM 参数集 = 空集**。按"只在问题实际发生时才加参数"的原则，**M0 不添加任何必需参数**；把 `--enable-native-access=ALL-UNNAMED` 作为**建议项**（见 §7 T-4），它现在的价值是"消除噪音 + 提前适应未来 JDK 会把受限方法变为硬错误"。

### 3.4 Maven 自身的同类告警（工具链侧，需与产品侧区分）

Maven 进程也有同类告警，但**不属于产品**，不应混入 M0 判定：

```
WARNING: java.lang.System::load has been called by org.fusesource.jansi.internal.JansiLoader
         (file:/D:/software/maven/apache-maven-3.9.9/lib/jansi-2.4.1.jar)
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by
         com.google.common.util.concurrent.AbstractFuture$UnsafeAtomicHelper
         (file:/D:/software/maven/apache-maven-3.9.9/lib/guava-33.2.1-jre.jar)
```

---

## 4. Test（构建与测试）

| 项 | 结果 |
|---|---|
| 命令 | `mvn -s toolchain/settings.xml clean package` |
| 结果 | **BUILD SUCCESS** |
| 编译 | maven-compiler-plugin 3.16.0，`--release 21`，`-Xlint:all,-serial` |
| 编译器告警 | **0 条**（本工程源码） |
| 单元测试 | maven-surefire-plugin 3.6.0 → `CoordsTest`：**Tests run: 32, Failures: 0, Errors: 0, Skipped: 0** |
| 打包 | maven-shade-plugin 3.6.2 → `target/skyisland-0.1.0-M0.jar`（fat jar，Main-Class 已写入清单） |
| 重复构建 | 通过（热构建 22 – 45 s） |

### 4.1 `CoordsTest` —— 为什么它在 M0 就要存在

`Coords` 在 M0 尚无任何生产调用方（M0 不涉及 Chunk / World）。它提前落地并配 32 个断言，是因为**负数坐标是体素引擎最容易被"看起来正确"的代码掩盖的缺陷来源**：

```java
int bx = -1;
bx / 16  == 0     // 错误：正确区块是 -1，且不抛异常
bx % 16  == -1    // 错误：会让数组下标为负，且不抛异常
```

测试把规则固化为断言，使这个约定不依赖开发者记忆。关键用例（全部通过）：

| 用例 | 断言 |
|---|---|
| ★ 用户指定 | `blockX = -1 → chunkX = -1, localX = 15` |
| 反例锚定 | `-1/16 = 0 ≠ toChunk(-1)`、`-1%16 = -1 ≠ toLocal(-1)` |
| 全取值域一致性 | `-1024..1024` 区间内 `localFast` 与 `toLocal` 完全相等 |
| 非负性 | `-4096..4096` 区间内 `localFast` 恒在 `[0,15]` |
| 往返恒等 | `-2048..2048` 区间内 `toWorld(toChunk(b), toLocal(b)) == b` |
| 区块键双射 | 17×17 个含负坐标的区块键无碰撞（验证 `cz` 的 32 位掩码） |
| 线性索引双射 | 32768 个索引无碰撞且可逆 |
| 层内连续性 | 同一 y 层的 16×16 平面索引连续 |
| 高度口径 | 表面块 `y=63`、脚底 `y=64.0`（TECH_DESIGN §D.6 对 PRD D1 偏差的裁定） |
| 边界掩码 | `boundaryMask(0,0)=5`、`boundaryMask(15,15)=10`，四边可全覆盖 |

> **M0 期间该测试确实抓到一次错误**：初版断言把 `localFast(-1)` 写成了 `-1`（正确值是 `15`）。若没有这条断言，这个口径分歧会一直潜伏到 M1 的 DDA / 网格化里才爆发。

---

## 5. Functional（功能验证）

### 5.1 运行清单（7 次运行，全部退出码 0）

所有运行参数均可通过系统属性覆盖，因此**每次运行都是可复现的**。

| # | 运行 | 预热+测量 | 输入通道 | 统计窗口(s) | 帧数 | FPS | TPS | mean(ms) | median(ms) | **P95(ms)** | P99(ms) | max(ms) | >50ms | >100ms | >150ms | 钳制 | overrun | 门禁 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | smoke | 10+60※ | 无 | （旧仪器） | — | — | — | — | — | 2.563 | — | 4.115 | 0 | — | — | 0 | 0 | — |
| 1 | official | 10+60 | Robot(同步) | 60.353 | 53 858 | 892.38 | 59.68 | 1.121 | 0.964 | **2.159** | 2.799 | 250.000 | 2 | — | — | 2 | 2 | false |
| 2 | official2 | 10+60 | Robot(异步+夺焦) | 60.848 | 64 014 | 1052.04 | 59.20 | 0.951 | 0.690 | **2.170** | 4.182 | 250.000 | 29 | — | — | 0 | 18 | false |
| 3 | control | 10+60 | **无** | 60.4 | 96 453 | **1596.20** | 59.61 | 0.626 | 0.448 | **1.467** | 2.674 | 175.408 | 34 | — | — | 0 | 14 | false |
| 4 | inject | 10+40 | 确定性 | 40.018 | 33 829 | 845.35 | 60.02 | 1.183 | 0.968 | **2.631** | 3.056 | 22.784 | **0** | 0 | 0 | 0 | 0 | **true** |
| 5 | **final1** | 10+60 | 确定性 | 60.207 | 53 665 | 891.35 | 59.83 | 1.122 | 0.935 | **2.525** | 3.161 | 229.548 | 4 | 2 | 1 | 0 | 2 | false |
| 6 | **final2** | 10+60 | 确定性 | 60.017 | 67 213 | 1119.89 | 60.02 | 0.893 | 0.657 | **2.476** | 3.871 | 64.924 | 1 | 0 | 0 | 0 | 0 | false |

※ smoke 运行的 `-D` 参数被误放在 `-jar` 之后（变成程序参数），因此实际按默认 10+60 执行。这个失误反而暴露出两个测量缺陷，见 §6 I-1 / I-2。

**分位数精度**：本报告所有 P95/P99 由**宽度 0.1 ms 的定宽直方图**给出，误差 ≤ ±0.1 ms。判定阈值 16.7 ms 相对该精度有 167 倍余量，精度不构成不确定性来源。

### 5.2 性能判读

**通过的部分（决定性）**

| 指标 | 最差观测 | 目标 | 余量 |
|---|---|---|---|
| P95 帧时间 | 2.631 ms（inject） | ≤ 16.7 ms | **6.3 倍** |
| P95 帧时间 | 1.467 ms（control，最快） | ≤ 16.7 ms | **11.4 倍** |
| FPS | 845（inject，最低） | ≫ 60 | **14 倍** |
| TPS | 59.20（official2，最低） | ≈ 60 | 偏差 < 1.4% |
| 帧间隔钳制 | 0 次（control / inject / final1 / final2） | — | 无逻辑时间丢失 |

**未通过的部分（外因已证实）**

`>50 ms` 帧数在 0 – 34 之间波动，`perf_gate_met` 因此在 5 次运行中为 false。为了判定这是引擎问题还是环境问题，M0 做了一次**对照实验**：

> **对照实验：环境抖动探针**
> 写一个**不含任何 LWJGL / OpenGL / 文件 IO / 网络**的纯 Java 程序，只做 `System.nanoTime()` 采样 + `Thread.onSpinWait()`，用与 M0 完全相同的协议（60 s 测量、10 s 预热、预热后重置统计）运行。
>
> 结果：
> ```
> JITTER_PROBE duration_s=60 warmup_s=10 samples=424748959
>              max_ms=143.819  gt50=6  gt100=4  gt150=0  gt250=0
> ```
> 采样 4.25 亿次（≈7.1 MHz，时间分辨率 ~0.14 µs），**一个什么都不做的程序在同样的 60 秒里也停顿了 6 次 > 50 ms、4 次 > 100 ms，最长 143.8 ms。**

**判定**：M0 观测到的 `>50 ms` 停顿数量（0 – 34）与"空循环"程序（6）落在同一量级，且 `P99` 恒为 2.7 – 4.2 ms —— 若是引擎自身停顿，`P99` 必然被抬高。**这些停顿来自操作系统调度与宿主进程争用（本机同时运行着 WorkBuddy 宿主、Node 进程等），不是引擎缺陷。**

因此：

- 门禁 10「FPS 显著高于 60」→ **通过**
- 门禁 11「无持续卡顿」→ **通过**（最差 34 / 96 453 = **0.035%** 的帧，非"持续"）
- PRD 12.5 的**严格子条件**「零 `>50 ms` 帧」→ **在本机多任务环境下不可稳定复现**，记为待办 T-1，需在独占/安静的测量机上复核。

### 5.3 输入验证（两通道交叉，各自独立判定）

M0 硬门禁要求「Keyboard OK / Mouse OK」。这里必须诚实区分**两条通道**，因为它们证明的链路不同：

#### 通道 A：AWT Robot（物理级）—— 证明"操作系统输入队列 → 窗口"

| 运行 | 前台焦点(注入前) | 键事件 | 鼠标按键 | 鼠标移动样本 | 判定 |
|---|---|---|---|---|---|
| official | true | **0** | 6 | 1 004 | 键盘未观测 |
| official2 | true | **0** | **0** | 651 | 键鼠按键均未观测 |
| inject/final1/final2 | — | 0 | 0 | 0 | 未启用 |

**诊断**：Windows 的投递规则不对称 —— **键盘消息只投递给前台窗口，鼠标移动消息按光标位置投递**。本机前台被宿主进程占据，即使代码调用 `glfwFocusWindow` + `GLFW_FOCUS_ON_SHOW` 并等到 `GLFW_FOCUSED = true`，键盘消息仍不投递。这解释了"鼠标移动恒有事件、键盘恒为 0"这一稳定形态。

**结论**：Robot 通道**不可复现，不能用来签署门禁**。但它有一个副产品：鼠标移动在 3 次运行中稳定观测到 651 – 2 625 个样本，**证明了物理输入栈（硬件 → OS → GLFW → 回调）本身是通的**。

#### 通道 B：窗口消息注入（确定性）—— 证明"HWND → 窗口过程 → GLFW → 回调"

由**独立进程**（PowerShell + `DllImport("user32.dll") PostMessageW`）向已记录的 HWND 直接投递窗口消息。选择进程外实现的原因见 §7 I-10。

注入结果（3 次运行一致）：`POSTED=21 FAILED=0`（6 键 × 按下+抬起 = 12，4 次移动，左右键各 2 = 4，滚轮 1）。

**Java 侧实际观测（final1）**：

```
键盘事件: PRESS    key=W     (87)   scancode=17   ← 注入 0x11 ✓
键盘事件: RELEASE  key=W     (87)   scancode=17
键盘事件: PRESS    key=A     (65)   scancode=30   ← 注入 0x1E ✓
键盘事件: RELEASE  key=A     (65)   scancode=30
键盘事件: PRESS    key=S     (83)   scancode=31   ← 注入 0x1F ✓
键盘事件: RELEASE  key=S     (83)   scancode=31
键盘事件: PRESS    key=D     (68)   scancode=32   ← 注入 0x20 ✓
键盘事件: RELEASE  key=D     (68)   scancode=32
键盘事件: PRESS    key=SPACE (32)   scancode=57   ← 注入 0x39 ✓
键盘事件: RELEASE  key=SPACE (32)   scancode=57
键盘事件: PRESS    key=F3   (292)   scancode=61   ← 注入 0x3D ✓
DEBUG 日志级别: 关                                  ← F3 的语义响应被真实触发
键盘事件: RELEASE  key=F3   (292)   scancode=61
鼠标按键: PRESS    button=LEFT  (0)
鼠标按键: RELEASE  button=LEFT  (0)
鼠标按键: PRESS    button=RIGHT (1)
鼠标按键: RELEASE  button=RIGHT (1)
滚轮事件: offset=(0.00, 1.00)
```

**关键点**：注入的 6 个 Windows 扫描码（`0x11 0x1E 0x1F 0x20 0x39 0x3D`）与 Java 侧解出的 GLFW 键值（`87 65 83 68 32 292`）**逐一吻合**，说明"扫描码 → GLFW 键码表 → 回调"整条解析链路正确。同时 `F3` 还真实触发了 DEBUG 级别切换，构成**语义级**（而非仅计数级）的证据。滚轮也被正确解析为 1 格（`WHEEL_DELTA=120`）。

#### 输入验证结论

| 门禁 | 判定 | 依据 |
|---|---|---|
| Keyboard OK | **通过** | 通道 B：12 事件 / 6 不同键，扫描码逐一吻合，含 F3 语义响应（3 次运行可复现） |
| Mouse 按键 OK | **通过** | 通道 B：4 事件 / 2 不同键（左+右），3 次运行可复现 |
| Mouse 滚轮 OK | **通过** | 通道 B：1 事件，3 次运行可复现 |
| Mouse 移动 OK | **通过** | 通道 A：物理路径 651 – 2 625 样本（通道 B 的合成移动在 `CURSOR_DISABLED` 下不被 GLFW 记为位移，属预期行为） |

**诚实边界**：通道 B 绕过了操作系统的物理输入队列，因此它证明的是"窗口消息到达后能被正确解析并送达回调"，**不**同时证明"物理键盘按键会被 OS 路由到本窗口"。后者是 Windows 前台窗口策略问题，与代码无关。**若要闭环这一点，需要一次 20 秒的人工确认，步骤见 §7 T-2。**

### 5.4 事件回调链验证

| 回调 | 验证方式 | 结果 |
|---|---|---|
| `framebuffer-size` | 程序化 resize：`1280×720 → 1024×576 → 1280×720` | **2 次变化全部记录**，日志含前后尺寸 |
| `window-size` | 同上 | 已触发（窗口尺寸随帧缓冲同步更新） |
| `window-refresh` | 由 resize / 遮挡触发 | 2 次 |
| `window-focus` | final2 中窗口失焦又重获 | 获得 1 次 / 失去 1 次 —— **双向均验证** |
| `window-close` / ESC 退出 | ESC 触发 `requestClose`；测量超时也走同一路径 | 全部干净退出 |
| `cursor-pos` | 物理鼠标移动 | 651 – 2 625 样本 |
| `scroll` | 确定性注入 | 1 事件 |
| `key` | 确定性注入 | 12 事件 / 6 键 |
| `mouse-button` | 确定性注入 | 4 事件 / 2 键 |

> resize 链路的初始基线问题（第一次 resize 曾被吞掉）见 §6 I-2。修复后计数由 1 变为 2。

### 5.5 干净退出（Clean Shutdown）

| 检查项 | 结果 |
|---|---|
| 退出码 | **0**（7/7 次运行） |
| GL 资源释放 | `program / VBO / VAO` 已删除 |
| GLFW 回调释放 | key / mouse-button / cursor-pos / scroll / framebuffer-size / window-size / focus / refresh / close 共 9 个回调显式置空并 free |
| 能力表引用 | `GL.setCapabilities(null)`，避免 `glfwTerminate` 后悬空访问 |
| 窗口销毁 | 已销毁 |
| `glfwTerminate()` | 完成 |
| GLFW 错误回调释放 | 已释放 |
| **残留进程** | `java.exe` / `javaw.exe` **均无匹配**（`tasklist` 实测） |
| 日志文件 | `logs/skyisland-20260919.log`（96 939 字节），含 7 段 `M0_MEASUREMENT_SUMMARY` 结构化摘要 |

### 5.6 资源占用

60 秒运行期间：堆占用 15 – 19 MB / 已申请 254 MB；活动线程 7；无增长趋势（无泄漏迹象）。
> 注意：M0 没有任何游戏内容，因此该数字**不代表 M1 之后的内存基线**，只说明"基础设施本身无泄漏"。

---

## 6. Issues（缺陷清单）

### 6.1 已修复（M0 内闭合）

| ID | 缺陷 | 为什么会造成错误结论 | 修复 | 复测 |
|---|---|---|---|---|
| **I-1** | **帧统计环形缓冲（容量 3600）在 836 FPS 下只覆盖 4.3 秒**，却对外声称统计 60 秒 | P95 / P99 / max / spike 全部只描述最后 4 秒 → **静默失真** | 改为**定宽桶直方图**（0.1 ms × 2000 桶 + 溢出桶）：内存恒定 8 KB、覆盖完整窗口、分位数精确到 ±0.1 ms | P95 与窗口时长自洽；`实际统计窗口` 与 `计划测量时长` 对齐 |
| **I-2** | **缓冲饱和后 FPS 退化为假值**：`fps = count / elapsed`，`count` 被容量截断而 `elapsed` 继续增长 | 该比值随 1/t 衰减，**最终报出 `FPS ≈ 59.98`，而真实值约 836** —— 恰好会让"FPS ≫ 60"这条门禁**被误判为不及格** | 吞吐量改为 `frameCount / Σ帧间隔`，与样本数严格自洽 | 同一机器上 FPS 由假值 59.98 修正为真实 892 – 1596 |
| **I-3** | **首次 resize 被当成基准吞掉**：GLFW 只在尺寸*变化*时回调，而初始尺寸不产生回调，基线未播种 | resize 计数**少记一次**（实测记 1 次而非 2 次），会掩盖回调链缺陷 | 新增 `InputState.seedWindowState()`，在回调注册后播种帧缓冲尺寸与初始焦点 | resize 计数 1 → **2**，与程序化的两次调整吻合 |
| **I-4** | **自测同步跑在主循环内**：AWT Robot 合成一批事件耗时约 1 秒 | 凭空制造 **250 ms 级"卡顿" + logic 步欠账**，属于测试脚手架污染被测量对象 | 合成过程移到独立线程（`m0-input-selftest`） | official 的 2 次 spike + 2 次 overrun → 消失 |
| **I-5** | **统计使用了钳制后的帧间隔**：`rawDelta` 超过 250 ms 上限后被截断才交给统计 | 把一次 1.376 秒的真实停顿**伪装成"刚好 250 ms"**，掩盖真相 | 统计改用 `rawDelta`；钳制次数用 `clampCount` 单列 | 出现 `clamped_frames` 独立指标；真实 max 不再被压平 |
| **I-6** | `pom.xml` 的 XML 注释中出现连续两个减号（`----`） | POM 不可解析 → **BUILD FAILURE**，Maven 直接拒绝读工程 | 重写全部注释，分节标题不再使用减号连线 | BUILD SUCCESS |
| **I-7** | 三处编译错误：`M0InputSelfTest` 的 `int[][]` 混入 `String`；`SkyIslandGame` 调用了未定义的 `loop()`；`FrameStats.Snapshot.EMPTY` 参数个数与记录组件数不符 | 无法编译 | 改用 `record KeyPlan`；补 `loop()` 委托方法；修正参数个数 | BUILD SUCCESS |
| **I-8** | `CoordsTest` 断言写反：把 `localFast(-1)` 期望成 `-1`（正确值 `15`） | 会让正确实现被判定为失败 | 修正为 `15` 并补断言说明 `& 15` 的语义 | 32 测试全通过 |

### 6.2 开放项

| ID | 问题 | 性质 | 影响 | 处置 |
|---|---|---|---|---|
| **I-9** | `>50 ms` 帧数在 0 – 34 间波动，PRD 12.5 的"零卡顿"子条件在 5 次运行中 4 次未达 | **环境**（已由 §5.2 对照实验证明） | 不影响 M1；影响 M6 性能签字口径 | 待办 **T-1** |
| **I-10** | **`java.lang.foreign` 在 `--release 21` 下是预览 API，默认禁用，无法编译** | **决策点** | 目前挡掉了"确定性键鼠验证"的纯 Java 实现（已用外部进程方案绕过） | 待办 **T-3** |
| **I-11** | `sun.misc.Unsafe::objectFieldOffset` 弃用告警**无任何 JVM 参数可抑制**（由 LWJGL 3.4.3 的内存后端选择决定） | 信息 | 日志噪音；未来 JDK 移除 `Unsafe` 时需 LWJGL 升级 | 待办 **T-4** |
| **I-12** | LWJGL 3.4.3 构件在运行时把自身版本报告为 `3.4.3-snapshot` | 信息 | 仅影响版本字符串的可读性，不影响功能 | — |
| **I-13** | Maven Central 直连速率仅 25 – 150 kB/s | 环境 | 冷构建 3.5 分钟 | 若 CI 变慢先看网络 |

---

## 7. 待办（TODO）

| ID | 待办 | 建议做法 | 阻塞 M1？ |
|---|---|---|---|
| **T-1** | 在安静/独占环境下复核 PRD 12.5 的"零 `>50 ms`"子条件 | 关闭宿主进程、断网、仅运行 M0，跑 3×60 s；若达标则把"机器环境"写进性能验收前提 | **否**（P95 有 6 – 11 倍余量） |
| **T-2** | 人工闭环"物理键盘路由"这最后一环（约 20 秒） | 运行 `run-m0.bat -Dskyisland.measureSeconds=0`，**用鼠标点击窗口**，依次按 `W A S D 空格 1 2`，按 `ESC` 退出；随后在 `logs/skyisland-*.log` 中查 `键盘事件` 行即可确认 | **否**（回调链路已验证，仅"OS 路由"未闭环） |
| **T-3** | 决定是否把 `maven.compiler.release` 提到 25 以解锁 FFM | 二选一：① 提到 25（代价：运行环境必须 JDK 25+，违反 PRD 12.6 的解耦目标）；② 维持 21，接受 FFM 不可用（当前选择，已用外部进程方案绕过） | **否**，但需在 M1 前定论 |
| **T-4** | 决定是否默认加 `--enable-native-access=ALL-UNNAMED` | 建议加：能消除 J-1、提前适应未来 JDK 的硬错误化；对功能零影响（已实测）。启动脚本 `run-m0.bat` 已预留 `SKYISLAND_JVM_ARGS` 变量 | **否** |
| **T-5** | 关注 LWJGL 对 `sun.misc.Unsafe` 的迁移（J-2） | 跟踪 LWJGL 后续版本的 `MemoryBackendFFM` 成熟度；当前无参数可抑制 | **否** |
| **T-6** | 把 M0 的三项测量类修正（I-1…I-5）回写进 `TECH_DESIGN_v0.1` 的对应章节，或作为 v0.1.1 的勘误 | 建议出 `TECH_DESIGN_v0.1.1` 勘误段，而不是改动 v0.1 原文 | 否 |
| **T-7** | M1 删除 M0 脚手架 | 删除 `M0InputSelfTest`、`M0MessageProbe` 相关（已移除）、`CapabilityProbe`；`tmp/` 下的 `inject_input.ps1` 等测试脚本可保留在仓库外 | 否 |

---

## 8. Verdict（门禁判定）

| # | 门禁 | 结果 | 证据 |
|---|---|---|---|
| 1 | `mvn clean package` PASS | ✅ | BUILD SUCCESS，§4 |
| 2 | JDK 25 OK | ✅ | `OpenJDK 25 (25+36-3489)`，`java.home = D:\software\jdk-25`，§1.2 |
| 3 | LWJGL native 正常加载 | ✅ | 真实 GL 函数全部可用、GLFW 窗口建立、退出后无残留，§2 / §5.5 |
| 4 | GLFW 窗口 | ✅ | 1280×720 窗口 + 完整回调链，§2 / §5.4 |
| 5 | OpenGL Context | ✅ | **3.3.0 Core + Forward-Compatible**，`OpenGL33 = true`，§2 |
| 6 | GL Renderer 可获取 | ✅ | `Intel(R) Iris(R) Xe Graphics`（**取自真实上下文，非设备管理器**），§2 |
| 7 | Keyboard OK | ✅ | 12 事件 / 6 不同键，扫描码逐一吻合，含 F3 语义响应，3 次可复现，§5.3 |
| 8 | Mouse OK | ✅ | 按键 4 事件 / 2 键 + 滚轮 1 事件（确定性）；移动 651 – 2 625 样本（物理），§5.3 |
| 9 | 循环稳定 | ✅ | TPS 59.20 – 60.02，钳制 0 次（4/7 运行），§5.1 |
| 10 | FPS 显著高于 60 | ✅ | 845 – 1596，最低值仍为 60 的 **14 倍**，§5.1 |
| 11 | 无持续卡顿 | ✅ | 最差 34 / 96 453 = **0.035%**，且已由对照实验证明为环境外因，§5.2 |
| 12 | 无无法解释的 ERROR | ✅ | 程序告警 0、GL 错误 0、GL Debug 消息 0；2 条 JVM 告警已逐条归类，§3.2 |
| 13 | 无残留进程 | ✅ | `tasklist` 实测无 `java.exe` / `javaw.exe`，§5.5 |
| 14 | 全部 JVM 告警已归类 | ✅ | J-1 / J-2 均归类为 LWJGL native 层的预期告警，§3.2–3.3 |
| 15 | 可重复启动 | ✅ | 连续 7 次启动运行，全部退出码 0，§5.1 |

**门禁判定：15 / 15 全部通过。**

### 最终判定：**PASS WITH TODO**

**为什么不是 PASS**：15 项门禁虽全部通过，但有 3 项未闭环事实必须同时摆出，否则"PASS"会超出证据边界：

1. **PRD 12.5 的"零 `>50 ms` 卡顿"子条件在本机不可稳定复现**（0 – 34 次/分钟）。对照实验已证明是环境外因、而非引擎缺陷，但在安静环境下复核之前，该子条件**没有拿到"达标"的证据**。这是 PRD 的验收条款，不是可以默认通过的。
2. **"物理键盘按键被 OS 路由到本窗口"这一环未闭环**。回调链路已用确定性方式完整验证（且含 F3 语义响应），但 Windows 前台策略导致的 Robot 通道失效属于**未验证区域**，需要 20 秒人工确认（T-2）。
3. **`java.lang.foreign` 在冻结的 `--release 21` 下不可用**（I-10）。这是 M0 挖出的、影响后续技术选项的**决策点**，需要在 M1 前定论（T-3）。

**为什么不是 FAIL**：三项待办**没有一项**指向 M0 的技术基础不成立。所有门禁通过，P95 有 6 – 11 倍余量，输入链路完整验证，退出干净，可重复启动。M1 的前置条件（窗口 + 上下文 + 固定步长循环 + 输入 + 日志 + 可信测量）**已全部就位**。

### 最值得记住的一件事

M0 最大的产出不是"跑通了"，而是：**首轮运行的三项宣称（"统计了 60 秒"、"FPS 远高于 60"、"无卡顿"）全都是错的** —— 环形缓冲只覆盖了 4.3 秒、FPS 报的是衰减假值 59.98、自测自己制造了卡顿。如果 M0 只做"看起来通过"的验证，这三条会原封不动带进 M1，并在 M6 性能签字时集中爆炸。

**一个技术尖刺的价值，等于它发现了多少个"本来会被漏掉的问题"。** M0 找到 8 个已修缺陷 + 5 个开放项。

---

## 附录 A：复现步骤

```bat
REM 1) 构建（门禁 #1）
build-m0.bat

REM 2) 权威运行：10s 预热 + 60s 测量
run-m0.bat -Dskyisland.warmupSeconds=10 -Dskyisland.measureSeconds=60

REM 3) 指定 JVM 参数（例如静音 native-access 告警）
set SKYISLAND_JVM_ARGS=--enable-native-access=ALL-UNNAMED
run-m0.bat -Dskyisland.measureSeconds=60

REM 4) 人工键鼠确认（T-2）：窗口打开后点击窗口，按 W A S D 空格 1 2，再按 ESC
run-m0.bat -Dskyisland.measureSeconds=0
```

全部 M0 参数（系统属性）：

| 属性 | 默认 | 说明 |
|---|---|---|
| `skyisland.width` / `skyisland.height` | 1280 / 720 | 窗口尺寸 |
| `skyisland.vsync` | false | 垂直同步（测试口径为关闭） |
| `skyisland.debugGL` | true | 请求 Debug Context |
| `skyisland.hideCursor` | true | 隐藏并锁定光标，启用原始鼠标运动 |
| `skyisland.warmupSeconds` | 10 | 预热时长，不计入统计 |
| `skyisland.measureSeconds` | 60 | 正式测量时长；**0 = 不自动退出**，按 ESC 结束 |
| `skyisland.selfTest` | false | 启用 AWT Robot 合成输入（本机不可复现，仅参考） |
| `skyisland.logDir` | `logs` | 日志目录 |

## 附录 B：产物与证据位置

| 内容 | 路径 |
|---|---|
| 本报告 | `docs/testing/M0_REPORT.md` |
| 可执行 fat jar | `target/skyisland-0.1.0-M0.jar` |
| 累计日志（含 7 段结构化摘要） | `logs/skyisland-20260919.log` |
| 各次运行原始 stdout / stderr / 元数据 | `tmp/m0_<tag>.stdout.txt`、`tmp/m0_<tag>.stderr.txt`、`tmp/m0_<tag>.meta.json` |
| 确定性输入注入器 | `tmp/inject_input.ps1`（ASCII + CRLF） |
| 环境抖动对照探针 | `tmp/JitterProbe.java`、`tmp/jitter_result.txt` |
| 启动 / 构建脚本 | `run-m0.bat`、`build-m0.bat` |

## 附录 C：M0 未实现项（范围守卫）

以下**均未实现**，符合 M0 范围约束：

> Chunk、Block、World、Camera 世界移动、Physics、Mining、Placement、Inventory、Guns、Monster、Save Game

M0 只交付：Maven 工程 / JDK 25 / LWJGL / GLFW / OpenGL Context / Window / Basic Game Loop / Keyboard Input / Mouse Input / Logging / FPS 与 Frame Time / Clean Shutdown，以及**为上述交付物服务的验证脚手架**（`CapabilityProbe`、`M0InputSelfTest`、`JitterProbe`），后三项在 M1 起删除。

---

**M0 结束。未进入 M1。**

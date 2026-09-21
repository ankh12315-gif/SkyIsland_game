# 《空岛生存 SkyIsland》技术设计 TECH_DESIGN v0.1

| 项目 | 内容 |
|------|------|
| 文档路径 | `docs/architecture/TECH_DESIGN_v0.1.md` |
| 版本 | v0.1（首次技术设计） |
| 日期 | 2026-09-19 |
| **覆盖范围** | **M0 / M1 / M2 / M3（MVP 全阶段）** |
| **明确不覆盖** | M4（Alpha）、M5（RC）。这两阶段只写"架构预留说明"，不做实现设计 |
| 上游文档 | **`docs/design/PRD_v0.3.1.md`（唯一生效的产品口径来源）**、`docs/architecture/technical-constraints.md`（约束附录） |
| 开发顺序 | **PRD v0.3.1 → TECH_DESIGN v0.1 → M0 Technical Spike → M1** |
| 本文档的目的 | 回答"PRD 已确定**做什么**，Java + LWJGL 究竟**怎么实现**"。不复述 PRD 的玩法描述 |
| 冲突裁决 | 与 PRD v0.3.1 冲突时，**PRD v0.3.1 的产品规则优先**。技术方案可调整，玩法不得被偷偷改变 |

---

## 0. 四条支配全局的硬口径

本文档的每一处设计都受这四条支配。若某处看起来"过度谨慎"，先回到这里。

**口径 0.1 — 性能是硬约束，不是优化目标。**
基准机为 **Intel Iris Xe 集显（无独显）**，"视距 6 区块 + P95 帧时间 ≤ 16.7 ms + 无 > 50 ms 卡顿"是 **M1 门禁**（PRD 12.5）。Iris Xe 以**共享内存作为显存**，因此**显存带宽是首要瓶颈**，而不是核心数或填充率。任何取舍在此前提下优先选"更少字节"。

**口径 0.2 — 存档 ID 必须稳定。**
禁止 enum ordinal、数组下标、临时整数作为永久存档 ID（PRD 12.3）。入盘 ID 一律为字符串（`skyisland:stone`）。Runtime ID **只允许存在于运行时内存**。

**口径 0.3 — 世界改动只有一个入口。**
所有方块/实体变更必须经 `World` 的统一 mutation 入口。**任何模块直接 `chunk.blocks[index] = x` 都是禁止的**（§11.4）。绕开它 = 引入"方块改了但网格/光照/存档没跟上"的一整类幽灵 bug。

**口径 0.4 — 分层依赖单向。**
```
Input → PlayerIntent → Game Logic → GameState → Renderer
```
Renderer **只读** GameState，**不修改 World**；Input **不直接控制** Renderer；UI **不直接改** Chunk 内部数组。逻辑层不 import 任何 OpenGL 类。这是零成本项，也是将来接存档/回放/联机的唯一接缝。

---

## A. 技术栈冻结

### A.0 前置审查结论：项目当前不存在 Maven 工程

已对 `F:\minecraftspace` 做全深度扫描：**不存在任何 `pom.xml`**。当前目录只含 `docs/`、`tmp/`、`.toolchain/`、`README.md`。

因此：
- **不存在"现有 Maven 项目版本是否合理"的问题**，也无升级动作 —— 所有版本均为**首次冻结**。
- 本机原有的 `D:\software\maven\apache-maven-3.6.1-bin` 与 `D:\software` 下的自建 JDK **不属于本项目**，不予沿用（避免"我机器上正好有个旧版本"这类隐性耦合）。
- 本项目在本轮已实际安装并验证 **JDK 25**（`D:\software\jdk-25`）与 **Maven 3.9.9**（`D:\software\maven\apache-maven-3.9.9`）。

### A.1 版本锁定表（唯一版本来源）

任何模块不得自行引入其他版本。

| 组件 | **锁定版本** | 来源 / 核验方式 | 说明 |
|------|--------------|------------------|------|
| JDK（运行时） | **25**（`openjdk version "25" 2025-09-16`，build **`25+36-3489`**） | 本机实测 `D:\software\jdk-25`；`release` 文件 `IMPLEMENTOR="Oracle Corporation"` | PRD 12.6 要求 JDK 25 LTS。发行版差异见 A.4 |
| JDK（编译目标） | **`--release 21`** | PRD 12.6 | 字节码目标与运行 JDK **解耦**。JDK 25 编译 release 21 合法 |
| Maven | **3.9.9**（`8e8579a9e76f7d015ee5ec7bfcdc97d260186937`） | 本机实测 `mvn -v` 输出已确认 | 禁止 Gradle（PRD 12.6 / Q7） |
| **LWJGL** | **3.4.3** | Maven Central metadata `release=3.4.3`；`lwjgl`／`lwjgl-glfw`／`lwjgl-opengl` 主 jar 与 `natives-windows` 分类器 **全部 HEAD 200 实测可达** | 精确到 patch 版本，**禁止只写 "LWJGL 3"** |
| LWJGL 模块（MVP） | `lwjgl`、`lwjgl-glfw`、`lwjgl-opengl` | 同上 | 见 A.2 的完整性说明 |
| LWJGL 模块（M3 预留） | `lwjgl-stb`（3.4.3） | — | M3 起若不使用 JDK `ImageIO` 则引入 |
| **GLFW** | **LWJGL 3.4.3 内置的 GLFW 3.4.x native** | 随 `lwjgl-glfw-3.4.3-natives-windows.jar` 提供 | GLFW 版本**不单独锁定**，它由 LWJGL 版本决定。运行时会实测回报 `glfwGetVersion`（§19、M0_REPORT） |
| **OpenGL** | **3.3 Core Profile**（Forward-Compatible） | PRD 12.1 | 不使用任何废弃管线（无 `glBegin`、无固定管线矩阵） |
| **JOML** | **1.10.9** | Maven Central metadata `release=1.10.9`；jar HEAD 200 | 数学库（`Matrix4f`／`Vector3f`／视锥提取） |
| **JSON 序列化库** | **Gson 2.14.0** | Maven Central metadata `release=2.14.0`；jar HEAD 200（306 KB） | 见 A.3 的引入论证与使用边界 |
| **日志库** | **`java.util.logging`（JUL，JDK 内置）** | 零依赖 | 见 A.5 的决策论证 |
| **测试框架** | **JUnit Jupiter 6.1.3**（`junit-jupiter`）+ `maven-surefire-plugin 3.6.0` | Maven Central metadata `release=6.1.3` / `3.6.0` | 见 A.6 |
| 构建插件 | `maven-compiler-plugin` **3.16.0**、`maven-surefire-plugin` **3.6.0**、`maven-shade-plugin` **3.6.2** | Central metadata 稳定版末位（**刻意不取 `4.0.0-beta-*`**） | beta 版不进入冻结表 |
| 目标平台 | **Windows x64** | PRD 12.1 / 14.5 | 本机 Win11 家庭版 Build 26200 |
| 架构风格 | **普通面向对象 + 明确 System 分层** | 本节 A.7 | **禁止引入大型 ECS 框架** |

### A.2 LWJGL 依赖完整性（易错点）

LWJGL 的 jar 与 native 是**分开的 artifact**，必须**每个模块都显式声明 native 分类器**。只对 `lwjgl` 声明 native 是常见错误 —— 结果是 GLFW 在运行时抛 `UnsatisfiedLinkError`。

```xml
<properties>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
  <maven.compiler.release>21</maven.compiler.release>
  <lwjgl.version>3.4.3</lwjgl.version>
  <joml.version>1.10.9</joml.version>
  <gson.version>2.14.0</gson.version>
  <junit.version>6.1.3</junit.version>
  <lwjgl.natives>natives-windows</lwjgl.natives>
</properties>

<dependencyManagement>
  <dependencies>
    <!-- 用 BOM 统一 LWJGL 各模块版本：逐个写版本号极易造成 glfw 与 opengl 版本错配 -->
    <dependency>
      <groupId>org.lwjgl</groupId>
      <artifactId>lwjgl-bom</artifactId>
      <version>${lwjgl.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- 主 jar（compile 作用域） -->
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl</artifactId></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-glfw</artifactId></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-opengl</artifactId></dependency>

  <!-- 三个模块各自都需要 native（runtime 作用域）—— 必须逐个声明 -->
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl</artifactId>
    <classifier>${lwjgl.natives}</classifier><scope>runtime</scope></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-glfw</artifactId>
    <classifier>${lwjgl.natives}</classifier><scope>runtime</scope></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-opengl</artifactId>
    <classifier>${lwjgl.natives}</classifier><scope>runtime</scope></dependency>

  <dependency><groupId>org.joml</groupId><artifactId>joml</artifactId><version>${joml.version}</version></dependency>
  <dependency><groupId>com.google.code.gson</groupId><artifactId>gson</artifactId><version>${gson.version}</version></dependency>

  <dependency>
    <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
    <version>${junit.version}</version><scope>test</scope>
  </dependency>
</dependencies>
```

### A.3 为什么允许 Gson（对"不引入不必要框架"的交代）

PRD 12.1 的约束是"**不依赖 Unity / Unreal 等完整商业游戏引擎**"，不是"零第三方库"。但每增加一个依赖都是债务，必须交代：

- PRD 12.4 明确要求 Block / Item / Recipe / Gun / Ammo / Monster / ThreatLevel / Resource **配置化（JSON 驱动）**。
- **JDK 25 标准库不提供 JSON 解析器**。手写解析器的成本（转义、Unicode、数字精度、错误定位）远高于它省下的 300 KB。

| 备选 | 否决理由 |
|------|----------|
| 手写 JSON 解析器 | 成本高、易错、需自行处理 UTF-8 与转义，零收益 |
| `java.util.Properties` | 表达能力不足，无法表达配方这类嵌套结构 |
| 自研二进制配表 | 丧失可读性与可手改性，与 12.4 的"配置数据"意图相反 |
| **Gson 2.14.0** | ✅ 采用：无传递依赖、纯 Java、API 稳定、306 KB |

> **使用边界（硬约束）**：Gson **只允许被 `com.skyisland.data` 包 import**。其他任何包出现 `import com.google.gson.*` 即视为违规。目的：将来若需替换解析器，改动面收敛在一个包内。这条要作为代码评审的显式检查项。

### A.4 JDK 发行版实测说明（对 PRD 12.6 的事实性补充）

PRD 12.6 的期望是 **Temurin 25 LTS**。**实际安装的是 OpenJDK 25 GA（Oracle 构建的参考实现）**，原因是可达性：

| 来源 | 实测结果 |
|------|----------|
| `api.adoptium.net`（Temurin 官方 API） | HTTP 307 → GitHub Releases；本机实测吞吐 **约 48 KB/s**，211 MB 需约 **75 分钟**，判定**不可用** |
| `mirrors.tuna.tsinghua.edu.cn/Adoptium` | **HTTP 403**（多次重试均被拒） |
| `mirror.nju.edu.cn`／`mirrors.ustc.edu.cn`／`mirrors.pku.edu.cn`／`mirror.sjtu.edu.cn/Adoptium` | 对 `HEAD` 返回 200 但**无 `Content-Length`**；改 `GET` 实际返回 **0 字节** → 判定**假可用** |
| `mirrors.aliyun.com/adoptium`／`mirrors.zju.edu.cn` | HTTP 404 |
| **`repo.huaweicloud.com/openjdk/25/openjdk-25_windows-x64_bin.zip`** | **HTTP 200，`Content-Length` = 211.3 MB，实测吞吐 7.3–9.1 MB/s，24 秒完成** ✅ |

**影响评估**：
- OpenJDK 25 与 Temurin 25 是**同一份上游源码的不同构建**，本次用途（M0–M3 开发与性能验证）**无功能差异**。
- 已知差异仅在**长期安全补丁供给**。**不影响 MVP 开发，但 M5 发布前建议重新评估**：若届时有可达的 Temurin 通道，应切换并重跑一次 M0 验收以消除发行版差异。
- 该实测数据写入 `docs/testing/M0_REPORT.md`，并作为 PRD 12.6 的事实性补充。

### A.5 日志库决策：JDK 内置 `java.util.logging`（JUL）

| 备选 | 评价 |
|------|------|
| SLF4J 2.x + Logback | 能力最强，但引入 **2 个依赖 + 1 份 XML 配置**。MVP 只需要 3 个级别与 1 份格式，属过度配置 |
| Log4j2 | 同上，且体积更大 |
| **`java.util.logging`（JUL）** | ✅ **采用**。JDK 内置、零依赖、原生支持 `INFO/WARN/ERROR`、可通过自定义 `Formatter` 完全控制输出格式与双写（控制台 + 文件） |
| 自研 `System.out.println` | ❌ 无法分级、无法重定向、无法在测试中捕获。M0 必须能**分类记录 JVM 与 GL 告警**，这需要结构化日志 |

**关键实现约束**：所有代码**不直接调用 `Logger`**，而是通过 `com.skyisland.util.Log` 门面：

```java
public final class Log {
    public static void info(String fmt, Object... args);
    public static void warn(String fmt, Object... args);
    public static void error(String fmt, Object... args);
    public static void error(String msg, Throwable t);
    public static void debug(String fmt, Object... args);   // 仅开发构建输出
}
```

理由：既然将来可能换 SLF4J，就让替换点是**一个类**而不是 200 处调用点。这是 A.3 同一原则的应用。

### A.6 测试框架

- **JUnit Jupiter 6.1.3**（`release` 版本）+ `maven-surefire-plugin 3.6.0`。
- **M0 范围内只写"构建链烟雾测试"**，不写业务测试（M0 本就没有业务逻辑）：
  - `MavenBuildSmokeTest`：断言 `java.version` 前缀为 `25`、`--release 21` 编译产物可被加载。
  - `CoordinateMathTest`：断言 §D.4 的负坐标换算表（这是 M1 前必须冻结的规则，用测试固化它比写文档更可靠）。
- **这不算超出 M0 范围**：它们不涉及 Chunk / Block / World / Physics，作用是证明"工具链、编译器目标、测试链路"三者可用。M0 硬门禁第 1 条（`mvn clean package` PASS）需要它才能被真正验证。

### A.7 架构风格：普通面向对象 + System 分层

**明确禁止引入大型 ECS 框架**（如 Artemis、Ashley、Entitas 风格）。

理由：
- MVP 的实体种类只有 **3 种**（Player / MeleeMonster / ItemEntity），最大实体数在 TL1 下约 **50 个**（PRD 5.5.4 上限 8 只怪 + 掉落物）。ECS 的性能优势在这个量级上**完全不存在**。
- ECS 的收益在"数千实体 + 高频组件查询"，其代价是**数据与行为的分离使调试困难**（M0/M1 阶段最需要的是能直接打断点看清 `Player` 的状态）。
- 本项目真正需要的是**清晰的分层**，而不是组件化。分层用包结构与接口约束即可实现，零框架成本。

**M5 若实体数真的成为瓶颈，再重构** —— 但那时游戏已跑通，重构有回归测试保护。**现在重构没有保护。**

---

## B. 总体架构原则

### B.1 单向数据流

```
┌─────────────────────────────────────────────────────────────────────┐
│  Input (GLFW callbacks / polling)                                   │
│    ↓ 只产出原始输入状态，不解释语义                                  │
│  InputState (按键位、鼠标位移、鼠标按键)                              │
│    ↓ 由 InputMapper 映射                                              │
│  PlayerIntent (moveX, moveZ, jump, sneak, attack, use, reload, ...)  │
│    ↓ 仅 Logic 消费                                                    │
│  Game Logic (physics / ai / combat / items / spawning / light)       │
│    ↓ 唯一出口是 World mutation                                        │
│  GameState (World + EntityManager + Player + Inventory + Time)        │
│    ↓ 只读 + 插值                                                      │
│  Renderer (chunk mesh / entities / HUD)                              │
└─────────────────────────────────────────────────────────────────────┘
```

### B.2 六条禁止的依赖方向

| # | 禁止 | 为什么 |
|---|------|--------|
| 1 | Renderer 写 World | 会让行为依赖帧率；违反 §0.1 的可复现性 |
| 2 | Renderer 写 GameState（除渲染插值用的独立字段） | 同上 |
| 3 | Input 直接调用 Renderer | 会让输入延迟与渲染帧率耦合 |
| 4 | UI 直接改 Chunk 内部数组 | 违反 §0.3 统一入口 |
| 5 | Logic 层 import `org.lwjgl.opengl.*` | 违反 §0.4；逻辑层必须能在无 GL 环境（测试）下运行 |
| 6 | 任意模块直接 `chunk.blocks[index] = x` | 同 §0.3 |

### B.3 落地手段（不靠自觉）

| 手段 | 做法 |
|------|------|
| 包结构约束 | `render` 包可以被 `game` 调用；`game`／`world`／`entity`／`physics`／`combat`／`item` 包**不得** import `render` 或 `org.lwjgl.opengl`（`import` 检查可用评审 + 后续引入 `maven-enforcer` 的 `bannedDependencies` 规则） |
| 渲染位置字段隔离 | 实体持有 `renderX/renderY/renderZ` 与 `x/y/z` **两组字段**，插值只写前者（§13.3） |
| 单一 mutation 入口 | `World` 的等价于 `package-private` 可见性：`Chunk` 的写方法只被 `World` 调用 |
| 测试兜底 | `CoordinateMathTest` 固化负坐标规则（§D.4） |

---

## C. Game Loop

### C.1 方案：固定逻辑步长 + 渲染插值

```
fixedDt = 1.0 / 60.0        // 逻辑步长，恒定 16.6667 ms
accumulator = 0.0
MAX_FRAME_DELTA = 0.25      // 单帧最大计入 250 ms
MAX_STEPS_PER_FRAME = 5     // 防止 spiral of death
```

**伪代码（这是实现契约，不是示意）**：

```text
lastTime = nanoTime()
accumulator = 0.0

while (!window.shouldClose()) :
    now       = nanoTime()
    frameDelta = (now - lastTime) / 1e9
    lastTime  = now

    # --- 1) 帧间隔保护 ---
    if frameDelta > MAX_FRAME_DELTA :
        # 断点调试 / 系统挂起 / 窗口拖动 后回填一大段逻辑毫无意义，直接丢弃
        log.warn("frame delta clamped: %.3f s", frameDelta)
        frameDelta = MAX_FRAME_DELTA

    # --- 2) 输入：产生本帧的意图快照 ---
    input.poll()
    window.pollEvents()          # 必须每帧调用，否则 GLFW 会被判定为无响应
    intent = inputMapper.map(input.state())

    # --- 3) 固定步长逻辑推进 ---
    accumulator += frameDelta
    steps = 0
    while accumulator >= fixedDt && steps < MAX_STEPS_PER_FRAME :
        logic.processIntent(intent)      # 意图 → 速度（不直接改位置）
        logic.update(fixedDt)            # 物理 / AI / 战斗 / 掉落物 / 刷怪 / 资源核心
        logic.flushMutations()           # 步末统一广播方块改动
        accumulator -= fixedDt
        steps += 1
    if steps == MAX_STEPS_PER_FRAME && accumulator >= fixedDt :
        stats.recordOverrun(accumulator)
        accumulator = 0.0                # 明确丢弃欠账，避免下一帧继续追

    # --- 4) 渲染插值因子 ---
    alpha = accumulator / fixedDt        # ∈ [0, 1)
    entities.applyInterpolation(alpha)   # 只写 renderX/Y/Z

    # --- 5) 渲染（只读） ---
    renderer.render(alpha, gameState)

    # --- 6) 呈现与统计 ---
    window.swapBuffers()
    stats.recordFrame(frameDelta)
```

### C.2 逻辑步内的执行顺序（固定，不可随意调换）

顺序错误会导致"用上一步的位置做碰撞判定"这类隐性错误。

```
1. intent 冻结                        本步的意图快照（不可中途变化）
2. time.update(fixedDt)               世界时间、昼夜阶段、天数结算、dayFactor
3. player.applyIntent(intent)         意图 → 目标速度（不直接改位置）
4. entityManager.tickAI(fixedDt)      怪物 AI 决策 → 目标速度
5. physics.integrate(fixedDt)         统一积分：重力 → 位移 → 分轴碰撞求解
6. player.postPhysics(fixedDt)        落地检测、lastSafePosition、坠落伤害、虚空致死
7. combat.tick(fixedDt)               换弹计时、开火冷却、Hitscan 结算
8. itemEntities.tick(fixedDt)         磁吸、存活计时、虚空销毁
9. spawning.tick(fixedDt)             刷怪（仅黄昏/夜晚）、黎明清理
10. resourceCores.tick(fixedDt)       资源核心再生累计
11. world.flushMutations()            本步累计的方块改动统一广播
12. cleanupDeadEntities()             批量移除死亡实体
```

**第 11 步单独存在的原因**：一步内可能发生多次方块改动（沙子链式下落、爆炸）。合并到步末统一广播，可在广播前**按坐标去重**，使同一区域的光照/网格重算从多次降到一次。

### C.3 FPS 与 TPS 是两个独立指标

| 指标 | 含义 | 是否受 VSync 影响 | 用途 |
|------|------|-------------------|------|
| **FPS** | 每秒 `render()` 次数 | ✅ 受影响 | 画面流畅度 |
| **TPS** | 每秒逻辑步数 | ❌ 不受影响 | 模拟正确性 |

**游戏逻辑不得依赖瞬时 FPS。** 具体禁止：
- ❌ `if (fps < 30) reduceRenderDistance()` —— 会让行为依赖测量噪声
- ❌ 用 `frameDelta` 作为物理积分的 `dt`
- ❌ 在 `render()` 中推进任何计时器

正确做法：所有时间推进都走 `fixedDt`；`render(alpha)` 只做插值与绘制。

**TPS 的观测**：`steps` 计数器累计，`TPS = steps / elapsedSeconds`。若 TPS 持续 < 60，说明单步逻辑超时（M1 引入区块生成后需监控这一点）。

### C.4 帧时间统计

```java
public final class FrameStats {
    private final double[] samplesMs;   // 环形缓冲，容量 3600（60 FPS 下 = 60 秒）
    // 指标（在快照时一次性计算，不得每帧计算）
    double meanMs();
    double medianMs();
    double p95Ms();     // PRD 12.5 判定口径
    double p99Ms();
    double maxMs();
    int    spikeCount();      // > 50 ms 的样本数（PRD 12.5 判定口径之二）
    double tps();
    void   reset();
}
```

**为什么用 P95 而不是平均帧率**：PRD 12.5 的判定口径就是"第 95 百分位帧时间 ≤ 16.7 ms 且无 > 50 ms 卡顿"。平均帧率会把 1% 的 200 ms 卡顿平均掉 —— 而卡顿恰恰是玩家唯一能感知的部分。**统计必须与判定口径同构。**

**排序开销**：容量 3600 时排序约 0.1 ms。只在按 F3 显示或测试采点快照时计算，**不在每帧计算**。

### C.5 空闲策略

```
frameEnd → nextFrameTarget = frameStart + 1/60 s (VSync OFF 时为最小间隔)
remaining = nextFrameTarget - nanoTime()
if remaining > 2 ms:  Thread.sleep((remaining - 1) in ms)
然后自旋至目标时刻
```

纯自旋会吃满一个物理核（笔记本散热与续航都受影响）；纯 `Thread.sleep` 精度约 1–15 ms 不稳定。混合策略是标准做法。

**VSync OFF 是测试口径要求**（PRD 12.5）。开发运行时由设置项 `vsync` 控制（PRD 6.5 默认"关"）。

---

## D. 坐标系统

> **本章是全工程唯一的坐标口径来源。M1 开工前必须冻结。**

### D.1 方块坐标语义（最重要的定义）

> **方块坐标 = 该方块所占据立方体的最小角整数坐标。**
> 方块 `(x, y, z)` 占据半开区间 `[x, x+1) × [y, y+1) × [z, z+1)`。
>
> 因此"方块 `y` 的**顶面标高**" = `y + 1`。脚底世界坐标 `y` 落在方块 `floor(y)` 内部。

### D.2 三层坐标定义

| 层级 | 类型 | 用途 |
|------|------|------|
| **World Coordinate** | `double x, y, z` | 实体位置。**玩家与实体一律用 double，不用 float** |
| **Block Coordinate** | `int bx, by, bz` | 方块网格索引 |
| **Chunk Coordinate** | `int cx, cz` | 区块网格索引 |
| **Chunk Local Coordinate** | `int lx, ly, lz` | 区块内局部索引，`lx,lz ∈ [0,15]`，`ly ∈ [0,127]` |

**为什么实体用 `double` 而非 `float`**：`float` 有 24 位有效位，整数精度上限 2^24 = 16 777 216。世界坐标本身远小于该值，但**浮点减法在数值接近时精度骤降**：若相机在 x = 1000，一个 `float` 只有约 2^-13 ≈ 0.0001 的分辨率 —— 对碰撞检测（需要约 1e-4 精度）已在临界。用 `double` 是零成本的（现代 CPU 上 double 与 float 的算术吞吐相同，只有内存带宽有差异，而实体数量极少）。

### D.3 世界参数

| 参数 | 值 | 依据 |
|------|-----|------|
| 世界高度 | `y ∈ [0, 127]`，共 **128** 层 | PRD 4.1 |
| 可放置 / 可挖掘范围 | `y ∈ [1, 127]` | PRD 4.1 / 5.2 |
| 虚空致死线 | `y < -8`（世界坐标）立即死亡 | PRD 4.5 |
| 掉落物销毁线 | `y < -8` | PRD 4.5 / 5.2 |
| Chunk 尺寸 | **16 × 16 × 128** | PRD M1 |
| Chunk 垂直分层数 | **1**（全高覆盖 y = 0..127）→ **区块坐标只有 `(cx, cz)`，没有 `cy`** | 由尺寸直接推出 |
| 视距 | 6 区块（96 格半径） | PRD 4.1 |
| 坐标系手性 | 右手系；`+X` 东、`+Z` 南、`+Y` 上 | 体素惯例；决定 yaw/pitch 与面剔除符号 |
| 岛表面方块占据层 | **`y = 63`**（顶面标高 = 64） | 见 §D.6 的 1 层偏差裁定 |
| 主岛 | 32×32，中心 `(0, 0)` | PRD 4.2 |
| 石矿岛（MVP 唯一资源岛） | 14×14，中心 `(48, 0)` | PRD 4.2 |

### D.4 负坐标换算（**M1 前必须冻结**）

**Java 的 `/` 与 `%` 对负数是"向零截断"，不能直接用于区块划分。**

```java
// ✅ 正确：世界坐标 → 方块坐标
int bx = (int) Math.floor(x);          // 必须 floor
int by = (int) Math.floor(y);
int bz = (int) Math.floor(z);

// ✅ 正确：方块坐标 → 区块坐标
int cx = Math.floorDiv(bx, 16);
int cz = Math.floorDiv(bz, 16);

// ✅ 正确：方块坐标 → 区块局部坐标
int lx = Math.floorMod(bx, 16);        // 等价于 bx & 15（Java int 为二补码）
int lz = Math.floorMod(bz, 16);        // 等价于 bz & 15
int ly = by;                           // y 不分区块

// ✅ 正确：区块坐标 + 局部坐标 → 方块坐标（用乘加，不用"反向取模"）
int wx = cx * 16 + lx;
int wz = cz * 16 + lz;
```

**反例（错误写法及其后果）**：

| 错误写法 | `bx = -1` 时结果 | 后果 |
|----------|------------------|------|
| `int cx = bx / 16;` | `0`（正确应为 `-1`） | 负坐标区所有方块被塞进区块 0 的错误槽位 |
| `int lx = bx % 16;` | `-1`（正确应为 `15`） | 数组下标 `-1` → `ArrayIndexOutOfBoundsException` |
| `int bx = (int) x;` | `x = -0.5 → 0`（正确应为 `-1`） | 负半格位置的方块判定错误 |

**冻结用例（已写成 `CoordinateMathTest`，作为回归保护）**：

| `blockX` | `chunkX = floorDiv(bx,16)` | `localX = floorMod(bx,16)` |
|---------:|---------------------------:|---------------------------:|
| `-17` | `-2` | `15` |
| **`-16`** | **`-1`** | **`0`** |
| **`-1`** | **`-1`** | **`15`** |
| `0` | `0` | `0` |
| `1` | `0` | `1` |
| `15` | `0` | `15` |
| `16` | `1` | `0` |
| `17` | `1` | `1` |

> 用户示例 `blockX = -1 → chunkX = -1, localX = 15` **已包含在上表中并被测试覆盖**。

**`lx = bx & 15` 与 `Math.floorMod(bx, 16)` 等价，且位运算更快**，因此热点路径（DDA、网格化、碰撞）用 `&`，非热点路径用 `floorMod` 以保持可读性。**两者必须产生相同结果**，测试同时覆盖两种写法。

### D.5 区块内线性索引

```java
public static int index(int lx, int ly, int lz) {
    return (ly << 8) | (lz << 4) | lx;      // 值域 [0, 32767]
}
public static int unpackX(int i) { return i & 15; }
public static int unpackZ(int i) { return (i >> 4) & 15; }
public static int unpackY(int i) { return (i >> 8) & 127; }
```

选 `ly` 作最高位：网格化与光照更新都以"某一 y 层的 16×16 平面"为单位遍历，`ly` 高位可让同层数据在数组内连续，提升缓存命中率。

### D.6 【发现项 D1】PRD 的 y 坐标存在 1 层口径偏差

**这是本轮一致性核校发现的问题，不是新设计。**

| 出处 | 原文 | 隐含口径 |
|------|------|----------|
| PRD 4.1 | 「主岛基准面 y = 64（岛屿表面层）」「厚度 8–14 层（表面层 y = 64，自其向下 8–14 层；底部层 y = 57（8 层）至 y = 51（14 层）」 | 表面块**占据** y = 64 |
| PRD 5.7 | 「地板顶面 y = 64」「内部 3 格（y = 64 至 y = 66）」「屋顶 y = 67」 | 地板块**顶面标高** 64 → 地板块**占据** y = 63 |
| PRD 5.3.1 / M2 | 重生点 `(0, 64, 0)`；要求是**合法站立点** | 表面块**占据** y = 63（脚底 y = 64） |

**矛盾**：4.1 要求表面块占据 64；5.3.1 / 5.7 / M2 三处共同要求占据 63。**差 1 层。**

**本技术设计的裁定（取"证据更多"的一侧）**：

> **表面方块占据 `y = 63`，顶面标高 = 64（即 PRD 所称"基准面 y = 64"）。**
> - 玩家站上地表时脚底世界坐标 = **64.0**
> - 重生点 `(0, 64, 0)` → 实际站位 **`(0.5, 64.0, 0.5)`**（x/z 取方块中心，理由见 §11.5）
> - 岛屿厚度 8–14 层 → 底块占据 **`y = 56`（8 层）～ `y = 50`（14 层）**
> - 小屋（PRD 5.7 全部数值**无需改动**）：地板块占据 y = 63；内部空气 y = 64/65/66；屋顶块 y = 67

**需修正的只有 PRD 4.1 的 2 个数字**：`底部层 y = 57 / y = 51` → **`y = 56 / y = 50`**。

**裁定依据**：5.3.1 + M2（重生点必须是合法站立点）+ 5.7（内部三处数值同时自洽）构成**三处独立证据**指向同一侧；4.1 只有一处证据，且修正量最小。

> ⚠️ **待用户确认项**。替代方案是"表面块占据 y = 64"，但那需同步修改 5.3.1、5.7、M1、M2 共 4 处。**在用户裁定前，工程一律按本文档裁定实现**，并在代码中以单点常量定义：
> ```java
> public static final int WORLD_SURFACE_BLOCK_Y = 63;   // 表面块占据层
> public static final double WORLD_SURFACE_FEET_Y = 64.0; // 站在地表时的脚底 y
> ```
> 单点定义便于一次性翻转。
>
> 📌 建议登记为 `PRD v0.3.2` 微修补项。

---

## E. Chunk 数据结构

### E.1 设计取向：简单、稳定、可调试

**MVP 明确不做复杂压缩**（不做调色板、不做 bit-packing、不做分节）。

| 方案 | 内存（单区块） | 随机读取开销 | 决策 |
|------|----------------|--------------|------|
| `Block[][][]`（对象数组） | 32768 × 对象引用 + 对象体 → **数 MB**，且 GC 压力巨大 | 两次指针跳转 + 缓存不友好 | ❌ **禁止** |
| `int[]` | 32768 × 4 B = 128 KB | 一次数组访问 | ❌ 浪费一半（方块种类 MVP 仅 14 种） |
| **`short[]`** | **32768 × 2 B = 64 KB** | 一次数组访问 | ✅ **采用** |
| 调色板 + bit-packing | 约 8–16 KB | 需位解码（**热点路径**） | ❌ MVP 不做，见下 |

**为什么选 `short[]`**：MVP 方块种类为 **14 种**（13 玩家常规 + 1 系统）+ 空气 = **15 个 runtime ID**，`short` 保留 32767 的余量，足够支撑到 M5（届时约 30 种）。`int[]` 在 169 区块规模下会多占 10.8 MB 却无任何收益。

**为什么 MVP 不做调色板压缩**：压缩的价值在于区块方块种类少时把 2 B/格压到 1–2 bit/格；代价是**每次读写都要解码**，而体素引擎的热点恰恰是随机方块读取（碰撞检测每次移动读十几个方块、DDA 每步读一个）。在 MVP 规模下（169 区块 ≈ 16.4 MB），压缩省下的内存换不来任何东西，却要在最热路径上增加解码开销。**只有当内存实测成为瓶颈时才引入。**

### E.2 Chunk 字段

```java
public final class Chunk {
    public static final int SIZE_X = 16;
    public static final int SIZE_Z = 16;
    public static final int SIZE_Y = 128;
    public static final int VOLUME = SIZE_X * SIZE_Z * SIZE_Y;   // 32768

    private final int cx, cz;

    // ---- 方块存储 ----
    private final short[] blocks;        // 32768 × 2 B = 64 KB   runtime block id
    private final byte[]  light;         // 32768 × 1 B = 32 KB   光照 0..15（火把径向 + 天光结果）
    private final int[]   heightMap;     //   256 × 4 B =  1 KB    每列最高不透明方块 y+1（天光判定）

    // ---- 状态标志 ----
    private ChunkState state;            // EMPTY | GENERATED | MESHED
    private boolean dirty;               // 方块数据被改动（总标志）
    private boolean meshDirty;           // 网格需重建
    private boolean saveDirty;           // 需写回存档
    private boolean neighborDirty;       // 边界方块改动 → 相邻区块的网格也需重建
    private boolean lightDirty;          // 光照需重算

    private int  nonAirCount;            // 非空气方块数（维护计数，O(1) 判断是否需网格化）
    private long lastModifiedStep;       // 最近一次改动的逻辑步号（调试 + 脏块判定）
    private int  refCount;               // 引用计数：0 表示可卸载
}
```

**单区块占用 ≈ 97 KB**；视距 6 区块满载 169 个 → **约 16.4 MB**。在 15.7 GB 机器上完全可接受。

### E.3 四个 dirty 标志的分工（必须分开，不能合成一个）

| 标志 | 触发条件 | 消费者 | 若缺失会怎样 |
|------|----------|--------|--------------|
| `dirty` | 任意方块写入 | 总开关 | 无 |
| `meshDirty` | 方块写入 / 光照变化 | 网格重建队列 | 挖了方块但视觉不变 |
| **`saveDirty`** | 仅玩家或系统**永久**改动（**不含世界生成**） | 存档增量写入 | 把生成出来的整个世界都写进存档（PRD 明令禁止，§16） |
| **`neighborDirty`** | 改动位于 `lx/lz ∈ {0,15}`，或跨 y 边界 | 相邻区块的 `meshDirty` | 区块边界残留"不该存在的墙"（§11.3） |
| `lightDirty` | 火把增删 / `dayFactor` 变化 | 光照重算 | 火把不亮或挖掉火把后仍亮 |

**`saveDirty` 必须与世界生成解耦**：世界生成写入的方块**不是**存档内容（它们由 Seed 重算得出）。若把生成也标 `saveDirty`，存档体积将从几 KB 变成几十 MB，且违反 PRD「禁止保存整个未经修改的程序生成世界」。

```java
// 实现方式：由 mutation 的 cause 决定是否标 saveDirty
switch (cause) {
    case WORLDGEN, SAVE_LOAD -> { /* 不标 saveDirty */ }
    default -> chunk.markSaveDirty();
}
```

### E.4 生命周期状态机

```
        ┌─────────────┐
        │  UNLOADED   │  不在 loaded map 中
        └──────┬──────┘
               │ getOrGenerate()
               ↓
        ┌─────────────┐  生成完成，nonAirCount == 0
        │   EMPTY     │──────────────────────┐
        └─────────────┘                      │ 永不网格化，永不渲染
               │ 非空                        │
               ↓                             │
        ┌─────────────┐  meshDirty           │
        │ GENERATED   │<─────────────────┐   │
        └──────┬──────┘                  │   │
               │ 网格构建完成            │   │
               ↓                         │   │
        ┌─────────────┐  方块改动        │   │
        │  MESHED     │──────────────────┘   │
        └──────┬──────┘   (meshDirty=true)   │
               │ 超出视距 + 迟滞             │
               ↓                             │
        ┌─────────────┐                      │
        │  UNLOADING  │  先 flush 到存档      │
        └──────┬──────┘                      │
               └──────────────→ UNLOADED ←──┘
```

### E.5 加载 / 卸载规则

```java
public final class ChunkStore {
    private final Map<Long, Chunk> loaded = new HashMap<>();

    public static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    public Chunk get(int cx, int cz);              // 不存在返回 null（★不隐式加载）
    public Chunk getOrGenerate(int cx, int cz);    // 生成并缓存
    public void  unload(int cx, int cz);           // 卸载前必须先 flush 脏块到存档
    public Collection<Chunk> loaded();
}
```

**`get()` 不隐式加载是有意设计。** 隐式加载会让"玩家位置 → 需要哪些区块"这个决策散落到各处，导致跨区块访问（网格化边界采样、光照、DDA 跨区块、碰撞）意外触发整条生成链，造成难以定位的卡顿。**跨区块访问必须显式 `getOrGenerate`。**

**加载/卸载触发（MVP 同步实现）**：

```
每 N = 4 个逻辑步（约 15 次/秒）执行一次：
  chLoadedRadius = settings.renderDistance        // 默认 6
  chKeepRadius   = chLoadedRadius + 2             // 迟滞带 = 8
  needed = { (cx,cz) | chebyshev((cx,cz), playerChunk) <= chLoadedRadius }
  for p in needed:  if store.get(p) == null → store.getOrGenerate(p)
  for c in store.loaded():
      if chebyshev(c, playerChunk) > chKeepRadius → store.unload(c)   // 内部先 flush
```

**卸载迟滞（6 → 8）**：若加载与卸载共用同一半径，玩家沿视距边界来回走会产生"加载-卸载-加载"抖动。2 个区块的迟滞带成本可忽略。

**MVP 不做多线程区块生成**：MVP 世界只有 2 座岛，总面积 32² + 14² = **1220 格**，而 169 个区块共 **432 万格** → 空气占比 **> 99.97%**。同步生成完全够用，而线程池会立刻引入"网格化时区块被另一线程改写"的并发正确性问题。**多线程列为 M5 优化项。**

### E.6 方块读写（唯一的底层访问点）

```java
public final class Chunk {
    // 只读：任何层都可以调用
    public short getBlock(int lx, int ly, int lz) {
        return blocks[index(lx, ly, lz)];
    }

    // ★ 写：仅允许 com.skyisland.world.World 调用（package-private）
    short setBlockInternal(int lx, int ly, int lz, short id) {
        int i = index(lx, ly, lz);
        short old = blocks[i];
        if (old == id) return old;                 // ★ 幂等：相同值不触发任何副作用
        blocks[i] = id;
        if (old == BlockRegistry.AIR_ID && id != BlockRegistry.AIR_ID) nonAirCount++;
        else if (old != BlockRegistry.AIR_ID && id == BlockRegistry.AIR_ID) nonAirCount--;
        this.dirty = true;
        this.meshDirty = true;
        this.lightDirty = true;
        return old;
    }

    // Modified block tracking（用于存档增量，见 §16）
    public Map<Integer, String> modifiedBlocks();   // index → stable id；与生成态不同的方块
}
```

**幂等短路很重要**：沙子下落、爆炸等批量操作会反复写同一格。无幂等短路时每次都会标脏网格与光照，导致"一次操作把整片区域重算几十遍"。

**`Chunk` 不做任何范围校验与游戏规则校验**。范围（`y ∈ [0,127]`）与规则（相邻支撑、不可破坏）**统一在 `World` 层做**（§11）。校验分散在两层是"某条规则被漏检"的常见成因。

---

## F. Block Registry

### F.1 双层 ID 模型

| 层 | 类型 | 是否入盘 | 是否可重排 |
|----|------|----------|------------|
| **stableId** | `String`，如 `"skyisland:stone"` | ✅ **入盘** | ❌ 永不变更 |
| **runtimeId** | `short`（0..N），如 `3` | ❌ **不入盘** | ✅ 可随注册表变化 |

```java
public record BlockDefinition(
    String  stableId,        // "skyisland:stone"
    short   runtimeId,       // 运行时分配
    String  displayName,     // 中文名（PRD 6.7 术语统一）
    float   hardness,        // 破坏耗时基准（秒，空手）
    boolean solid,           // 是否参与碰撞
    boolean transparent,     // 是否透明（影响面剔除与半透明 pass 分流）
    boolean breakable,       // 可否被玩家破坏
    boolean placeable,       // 可否被玩家放置
    boolean collision,       // 是否有碰撞体（与 solid 分离：火把 solid=false 但有碰撞？见 F.4）
    RenderType renderType,   // CUBE | CROSS | NONE
    int     lightEmission,   // 自发光 0..15
    float[] faceShade,       // 6 面基础明暗系数（索引由 BlockFace.faceIndex 提供，非 ordinal）
    List<ItemStack> drop,    // 破坏掉落（ItemStack，非 BlockDefinition —— 见 §14.1）
    boolean systemBlock      // 是否系统方块（仅 skyisland:resource_core）
) {}

public enum RenderType { CUBE, CROSS, NONE }
```

### F.2 runtimeId 的确定性分配

```java
public final class BlockRegistry {
    public static final short AIR_ID = 0;          // 空气恒为 0，永不参与分配

    void build(List<BlockDefinition> defs) {
        // 按 stableId 字典序排序后从 1 开始依次分配
        defs.stream()
            .filter(d -> !d.stableId().equals("skyisland:air"))
            .sorted(Comparator.comparing(BlockDefinition::stableId))
            .forEachOrdered(this::assignNext);      // 重复 stableId / 超出 short → 立即抛异常
    }
}
```

**为什么按字典序分配**：runtimeId 不落盘，分配顺序理论自由。但排序分配带来两个实际好处：(1) **同一份 JSON 在任何机器上产生完全相同的运行时布局**，使"我的机器上没问题"这类问题不因 ID 漂移产生；(2) 日志里的 ID 稳定可读。

**冲突处理：立即抛异常，不静默降级。** 重复 `stableId`、`runtimeId` 超出 `short` 范围，都属配置错误，必须在启动时暴露。

### F.3 MVP 方块清单（15 项 = 空气 + 13 玩家常规 + 1 系统）

| # | stableId | 中文名 | hardness | solid | transparent | breakable | placeable | renderType | lightEmission | drop |
|---|----------|--------|---------:|:-----:|:-----------:|:---------:|:---------:|:----------:|--------------:|------|
| 0 | `skyisland:air` | 空气 | — | ❌ | ✅ | — | — | `NONE` | 0 | — |
| 1 | `skyisland:grass` | 草方块 | 0.6 | ✅ | ❌ | ✅ | ✅ | `CUBE` | 0 | 泥土 ×1 |
| 2 | `skyisland:dirt` | 泥土 | 0.5 | ✅ | ❌ | ✅ | ✅ | `CUBE` | 0 | 泥土 ×1 |
| 3 | `skyisland:stone` | 石头 | 1.5 | ✅ | ❌ | ✅ | ✅ | `CUBE` | 0 | **圆石 ×1** |
| 4 | `skyisland:cobblestone` | 圆石 | 2.0 | ✅ | ❌ | ✅ | ✅ | `CUBE` | 0 | 圆石 ×1 |
| 5 | `skyisland:log` | 原木 | 2.0 | ✅ | ❌ | ✅ | ✅ | `CUBE` | 0 | 原木 ×1 |
| 6 | `skyisland:planks` | 木板 | 2.0 | ✅ | ❌ | ✅ | ✅ | `CUBE` | 0 | 木板 ×1 |
| 7 | `skyisland:leaves` | 树叶 | 0.2 | ✅ | ❌ | ✅ | ✅ | `CUBE` | 0 | **空**（MVP 不掉树苗） |
| 8 | `skyisland:glass` | 玻璃 | 0.3 | ✅ | **✅** | ✅ | ✅ | `CUBE` | 0 | **空** |
| 9 | `skyisland:iron_ore` | 铁矿石 | 3.0 | ✅ | ❌ | ✅ | ✅ | `CUBE` | 0 | 铁矿石 ×1 |
| 10 | `skyisland:coal_ore` | 煤炭矿石 | 3.0 | ✅ | ❌ | ✅ | ✅ | `CUBE` | 0 | **煤炭 ×1（物品）** |
| 11 | `skyisland:sand` | 沙子 | 0.5 | ✅ | ❌ | ✅ | ✅ | `CUBE` | 0 | 沙子 ×1 |
| 12 | `skyisland:torch` | 火把 | 0.0 | ❌ | ✅ | ✅ | ✅ | `CROSS` | **14** | 火把 ×1 |
| 13 | `skyisland:wooden_door` | 木门 | 1.0 | ✅ | ❌ | ✅ | ✅ | `CUBE` | 0 | 木门 ×1 |
| 14 | `skyisland:resource_core` | 资源核心 | — | ✅ | ❌ | **❌** | **❌** | `CUBE` | **6** | **空** |

**三处容易实现错的地方：**

1. **`stone` 掉落圆石**：破坏逻辑必须走"查 `BlockDefinition.drop` 生成物品"，**不能默认掉落自身**。默认掉自身是最省事的写法，但会在这里直接错。
2. **`coal_ore` 掉落煤炭（物品）而非煤炭矿石（方块）**：`drop` 的元素类型是 **`ItemStack`**，不是 `BlockDefinition`。这个设计从第一天就必须正确（§14.1）。
3. **`leaves` 在 MVP 按不透明处理**（`transparent = false`）：这样相邻面自然被剔除，不需要专用分支。若设成 `true`，每次面剔除都要额外处理"同种透明方块相邻"，纯属自找麻烦。

### F.4 `solid` / `transparent` / `collision` 三个标志的区别

| 标志 | 含义 | 用在哪 |
|------|------|--------|
| `solid` | 是否参与面剔除的遮挡判定 + 是否可作为"相邻支撑" | 网格化、放置校验（§12.3） |
| `transparent` | 是否归入半透明渲染 pass | 渲染分流（§10.7） |
| `collision` | 是否有碰撞体 | 物理（§11.2） |

**为什么三者必须分开**（举两个例子）：
- **火把**：`solid = false`（不遮挡、不能用作搭桥支撑）、`transparent = true`（不写入不透明 pass）、`collision = false`。
- **玻璃**：`solid = true`（可作支撑、彼此遮挡删除内部面）、`transparent = true`（半透明 pass）、`collision = true`。

若把三者合成一个 `isSolid`，火把就会变成"可以搭桥的支撑点"，玻璃会被判为不遮挡而生成大量内部重叠面。**这是同一个布尔字段被复用造成的两类 bug，所以从数据结构上就要分开。**

### F.5 `skyisland:resource_core` 的正式登记（PRD 5.1.1 落地）

| 属性 | 值 | 强制点 |
|------|-----|--------|
| `breakable` | ❌ `false` | `World` 拒绝任何 `cause != WORLDGEN` 的移除请求（§11.5） |
| `placeable` | ❌ `false` | 不进入任何"玩家可放置方块"列表 |
| `collision` | ✅ `true` | 玩家会被它挡住 |
| `renderType` | `CUBE` | 必须参与渲染 |
| `drop` | 空 | 破坏路径根本不会到达 |
| `systemBlock` | ✅ `true` | 用于"物品注册表排除"（§14.2）与存档校验 |
| 存档 | ✅ 参与 | 按 stableId 序列化，随 `saveVersion` 演进 |

**不变量（写成断言，M1 起生效）**：

```java
assert registry.all().stream().filter(BlockDefinition::systemBlock)
    .allMatch(b -> !b.breakable() && !b.placeable() && b.drop().isEmpty() && b.collision());
```

**为什么用"系统方块"这个显式概念而不是把它当空气或普通方块**：
- 当**空气** → 同时破坏"参与渲染、参与碰撞、参与存档"三点（玩家看不见也碰不到资源核心）。
- 当**普通方块** → "不可破坏"这条规则会散落到挖掘逻辑里，迟早被某条新增路径（爆炸、怪物、调试指令）绕开。
- 显式登记 + 在 `World` 单一入口强制 → 只有一处需要正确。

### F.6 BlockFace

```java
public enum BlockFace {
    NEG_X(0, -1,  0,  0),
    POS_X(1,  1,  0,  0),
    NEG_Y(2,  0, -1,  0),
    POS_Y(3,  0,  1,  0),
    NEG_Z(4,  0,  0, -1),
    POS_Z(5,  0,  0,  1);

    public final int faceIndex;   // 0..5，显式写死
    public final int nx, ny, nz;  // 外法向
}
```

**`faceShade` 数组的索引必须用 `faceIndex`，禁止用 `ordinal()`。** 虽然当前两者恰好一致，但一旦有人调整枚举声明顺序，数组就会错位 —— 而 `ordinal()` 是隐式契约，编译器不会报错。这是 §0.2 同一原则在更小尺度上的应用。

**面朝向与剔除的符号**：`NEG_X` 的法向是 `(-1,0,0)`，即"方块朝 −X 方向的那个面"。放置方块时目标位置 = `hitBlock + face.normal`。**符号写反会导致放置的方块出现在玩家自己身体里。**

---

## G. Chunk Mesh

### G.1 M1 初版只做 Exposed Face Culling

**明确不做 Greedy Meshing。**

| 方案 | 顶点数（32×32 平坦岛面） | 实现复杂度 | 决策 |
|------|--------------------------|------------|------|
| **Exposed Face Culling** | 每格 2 三角形 → 2048 tri | 低 | ✅ **M1 采用** |
| Greedy Meshing | 同面积合并为 2 三角形 | 高（6 面 × 多层材质矩形合并） | **仅作为"性能无法通过时的下一层优化候选"** |

理由：MVP 世界总方块面积约 1220 格，暴露面剔除产生的顶点量在 Iris Xe 上完全不构成压力（估算 < 2 万三角形）。Greedy 的收益在"大片同材质平面"，而成本是"每次方块改动都要重建更大的合并单元" —— 在玩家频繁挖放的 MVP 阶段**反而可能更慢**。**M1 门禁实测未通过前不引入。**

### G.2 网格分裂：opaque / transparent 两个子网格

```java
public final class ChunkMesh {
    private SubMesh opaque;        // 不透明：石头、泥土、原木、树叶……
    private SubMesh transparent;   // 半透明：玻璃、火把（CROSS）
}

public final class SubMesh {
    int vao, vbo, ebo;
    int indexCount;                // 用 EBO 索引绘制，复用顶点
    boolean uploaded;
}
```

**必须拆成两个子网格**（不能合成一个）：
1. **渲染状态不同**：不透明 pass 开深度写入、关混合；半透明 pass 关深度写入、开混合（§10.7）。
2. **排序需求不同**：不透明无需排序；半透明需按距离由远及近。
3. **面剔除规则不同**：不透明方块被不透明邻居遮挡则剔除；玻璃与玻璃相邻时彼此的面都要剔除（§G.4）。

### G.3 顶点格式（按"减少字节"取向）

```java
// 交错布局，stride = 28 字节
// offset  0 : aPos    vec3  float  12 B   区块局部坐标（0..16 范围）
// offset 12 : aUV     vec2  float   8 B   面内 UV，0..1
// offset 20 : aLayer  float         4 B   纹理数组层号
// offset 24 : aShade  float         4 B   亮度系数，[0,1]，CPU 侧已预乘
public static final int VERTEX_STRIDE = 28;
```

```glsl
// 顶点着色器属性绑定
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aUV;
layout (location = 2) in float aLayer;
layout (location = 3) in float aShade;
```

**两个设计要点：**

1. **只传区块局部坐标，世界偏移走 uniform。**
   - 世界坐标可达 ±数千，`float` 在 > 2^24 时整数精度丢失；但区块局部坐标恒在 `[0, 16]`，精度绰绰有余。
   - 每区块只传一个 `vec2 chunkOffset` uniform，不必为每顶点存 3 个 float 的绝对坐标。**这是省字节与保精度的双赢，不是取舍。**
2. **`aShade` 在 CPU 侧预乘为单值**（面明暗 × AO × 光照）。
   - 若把 AO、面明暗、火把光、天光作为 4 个独立属性，顶点会从 28 B 膨胀到 44 B，且片元着色器要做 4 次插值再相乘。预乘为单值后**片元着色器只做一次乘法**。
   - **已知代价**：光照变化必须重建网格（shade 已烘焙进顶点）。若 M1 实测发现光照导致的网格重建过于频繁，替代方案是把光照改为逐顶点 `byte` 属性（顶点 32 B）并在片元中相乘。**M1 实测后按数据决定，不预先优化。**

### G.4 面剔除判定表

| 自身 | 邻居 | 是否生成该面 |
|------|------|--------------|
| 不透明 | 不透明 | ❌ 剔除 |
| 不透明 | 透明（玻璃/空气/火把） | ✅ 生成 |
| 透明（玻璃） | 不透明 | ❌ 剔除 |
| 透明（玻璃） | **同种（玻璃）** | ❌ 剔除 |
| 透明（玻璃） | 空气 / 其他透明 | ✅ 生成 |
| 火把（`CROSS`） | 任意 | ✅ 生成（火把不做面剔除） |

**核心循环：**

```
build(Chunk chunk, ChunkStore store) -> MeshData{ opaque, transparent }
  for ly in 0..127:
    for lz in 0..15:
      for lx in 0..15:
        b = chunk.getBlock(lx, ly, lz)
        if b == AIR: continue                      // 最常见分支，放最前
        def = registry.get(b)
        if def.renderType() == CROSS: emitCross(...); continue
        for face in BlockFace.values():            // 6 个方向
          n = neighborBlock(chunk, store, lx, ly, lz, face)
          ndef = registry.get(n)
          if (!ndef.transparent()) continue                     // 被完全遮挡
          if (n == b && def.transparent()) continue             // 同种透明相邻
          emitQuad(lx, ly, lz, face, def)
```

**两个必须做对的细节：**

1. **跨区块邻居查询。** `lx == 0/15` 或 `lz == 0/15` 的方块，其面朝向区块外。**不能简单视为 AIR**（会在每个区块边界生成多余面 → 交界处出现可见的"内部墙面"），也不能假定邻区块已加载。正确做法：`store.get()` 返回 `null` 时**视为 AIR（生成面）** —— **宁可多画一面，不可漏画**；邻区块加载后会重建本区块网格自行修正。
2. **叶子不参与"同种透明相邻"分支**：MVP 的 `leaves` 是 `transparent = false`（§F.3），因此走"被完全遮挡则剔除"的正常路径。

### G.5 Mesh rebuild / GPU upload / dirty queue

**明确禁止：每帧重新 Mesh 全世界。**

```
block mutation
   → World 标记 chunk.meshDirty = true（边界时同时标记相邻 chunk，§11.3）
   → MeshRebuildQueue 入队（按"到相机距离"排序）
   → 每帧限量处理：MAX_CHUNK_REBUILDS_PER_FRAME
   → 构建 MeshData（CPU，纯计算，可将来移入工作线程）
   → 上传 GPU（必须在渲染线程）
   → 原子替换 SubMesh 的 VAO/VBO/EBO 引用
```

```java
public final class MeshRebuildQueue {
    // M1 默认值，实测后调整（依据：单区块网格构建耗时 × 每帧预算）
    public static final int MAX_CHUNK_REBUILDS_PER_FRAME = 4;
    // 构建耗时超此阈值的区块记入日志（用于定位"某区块异常慢"）
    public static final long SLOW_REBUILD_WARN_NANOS = 8_000_000L;   // 8 ms
}
```

**`MAX_CHUNK_REBUILDS_PER_FRAME = 4` 的取值依据**：169 个区块全部重建需要约 42 帧（0.7 秒）。这是一个可接受的"加载地形"时间，且每帧只占用约 4/60 的预算。**数值在 M1 实测后调整**（若单区块构建 > 4 ms，需下调；若加载过慢，可上调或引入工作线程）。

**原子替换的含义**：必须**先构建完成，再替换**。若要重建的区块已有旧网格，**继续显示旧网格**直到新网格就绪 —— 不允许"先清空 VBO 再逐步写入"，那会在中途产生空帧（挖一下整片地形闪烁）。

### G.6 为什么用 EBO

`ChunkMesh` 使用 **VAO + VBO + EBO**：一个方块面 = 4 个顶点 + 6 个索引（2 三角形）。用 EBO 后顶点数从 6 降到 4，节省 33% 顶点带宽。

对内存带宽受限的 Iris Xe 而言，这个比例直接体现在性能上。**代价**：需要额外维护一个 EBO，且顶点复用要求"同一面的 4 个顶点连续写入"——这与 §G.4 的按面发射顺序天然一致，不增加复杂度。

### G.7 渲染 pass 顺序

```
render(alpha, gameState):
  1. camera.update(interpolatedPlayerPos, yaw, pitch)     // 只读 GameState
  2. frustum.update(camera.projView())
  3. // Pass 1 — 不透明
     glEnable(DEPTH_TEST); glDepthMask(true); glDisable(BLEND)
     for chunk in visibleSortedByDistance:
         glUniform2f(uChunkOffset, chunk.cx * 16, chunk.cz * 16)
         chunk.mesh.opaque.drawElements()
  4. // Pass 2 — 半透明（由远及近）
     glDepthMask(false); glEnable(BLEND); glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)
     for chunk in visibleSortedByDistanceReversed:
         chunk.mesh.transparent.drawElements()
     glDepthMask(true); glDisable(BLEND)
  5. // Pass 3 — 实体与手持物品
  6. // Pass 4 — HUD（正交投影，关深度测试）
```

**Pass 内不排序、Pass 间排序**：不透明 pass 依赖深度缓冲解决遮挡，逐三角形排序对不透明几何毫无收益且开销巨大（每帧排序几万三角形）。半透明必须排序，但**排序粒度是区块而非三角形**。

**已知限制（记录在案）**：同一区块内、不同深度的玻璃可能排序错误。在 MVP 只有玻璃一种半透明方块、玩家通常不建多层玻璃结构的条件下可接受。若 M3 试玩发现问题，解决方案是 OIT 或按三角形排序 —— **均属 M5，不在 MVP 范围。**

### G.8 视锥剔除

```java
public final class Frustum {
    private final float[] planes = new float[6 * 4];   // 左/右/底/顶/近/远
    void update(Matrix4fc projView);                   // Gribb-Hartmann 平面提取
    boolean intersectsAABB(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);
}
```

**用 AABB 而非球体**：区块是 **16×16×128 的长条**，外接球半径 = `sqrt(8² + 64² + 8²) ≈ 65`，远大于实际体积。用球体剔除会把大量实际不可见的区块判为可见，等同于没剔除。

**已知低效点**：区块在 y 上占满 128 层，而实际地形只占 8–14 层。可优化为"用 `heightMap` 收缩 AABB 的 y 范围"，但会让 AABB 随地形变化而变。**MVP 先用全高 AABB**；若 M1 实测绘制调用成为瓶颈，再引入收缩。

---

## H. World Mutation API

### H.1 概念接口

```java
public interface WorldMutator {
    Optional<BlockChange> setBlock(int x, int y, int z, short runtimeId, MutationCause cause);
    Optional<BlockChange> breakBlock(int x, int y, int z, MutationCause cause);   // 语义化封装
    Optional<BlockChange> placeBlock(int x, int y, int z, BlockDefinition def, MutationCause cause);
}

public record BlockChange(int x, int y, int z, short fromId, short toId,
                          MutationCause cause, long step) {}

public enum MutationCause {
    WORLDGEN,                // 世界生成（唯一允许放置/移除系统方块的来源）
    PLAYER_BREAK,            // 玩家挖掘
    PLAYER_PLACE,            // 玩家放置
    SAND_GRAVITY,            // 沙子下落（Alpha）
    RESOURCE_CORE_REGEN,     // 资源核心再生
    EXPLOSION,               // 爆炸（预留，MVP 无爆炸物）
    SAVE_LOAD,               // 从存档恢复
    DEV_COMMAND              // 调试指令
}
```

### H.2 校验规则表（按 cause 分流）

| 检查项 | WORLDGEN | PLAYER_BREAK | PLAYER_PLACE | RESOURCE_CORE_REGEN | SAVE_LOAD | DEV_COMMAND |
|--------|:--------:|:------------:|:------------:|:-------------------:|:---------:|:-----------:|
| `y ∈ [0, 127]` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 目标方块 `breakable`（移除时） | — | ✅ | — | — | — | ✅ |
| 目标方块 `placeable`（放置时） | — | — | ✅ | — | — | ✅ |
| `y ∈ [1, 127]` | — | — | ✅ | — | — | ✅ |
| 相邻支撑 | — | — | ✅ | — | — | ✅ |
| 实体冲突 | — | — | ✅ | ✅ | — | ✅ |
| 系统方块保护 | ✅ 允许 | ✅ 拒绝 | ✅ 拒绝 | ✅ 允许 | ✅ 允许 | ✅ 拒绝 |

**`WORLDGEN` 与 `SAVE_LOAD` 绕过大部分校验是有意的**：
- `WORLDGEN` 需放置系统方块、需在任意 y 写入（含 y = 0）、需大面积写入而不做逐格实体检查。
- `SAVE_LOAD` 需恢复"玩家被埋在方块里"这类异常状态而不被拒绝 —— 拒绝会导致存档打不开。
- **这两个 cause 只能由 `WorldGenerator` 与 `SaveManager` 使用。** 若玩家操作的代码路径上出现 `WORLDGEN`，那就是**绕过校验的后门** —— 这条要作为代码评审的显式检查项。

### H.3 mutation 后的必做处理

```java
public Optional<BlockChange> setBlockInternal(int x, int y, int z, short newId, MutationCause cause) {
    // ...（校验，见 H.2）...
    int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
    Chunk c = store.getOrGenerate(cx, cz);
    short oldId = c.setBlockInternal(x & 15, y, z & 15, newId);
    if (oldId == newId) return Optional.empty();          // 幂等，无实际变更

    // ★ 1) 当前 Chunk dirty
    c.markDirty();

    // ★ 2) Mesh dirty（由 c.setBlockInternal 已置位）
    // ★ 3) 边界情况下相邻 Chunk dirty —— 最容易漏的一条
    if ((x & 15) == 0)  store.markMeshDirtyIfLoaded(cx - 1, cz);
    if ((x & 15) == 15) store.markMeshDirtyIfLoaded(cx + 1, cz);
    if ((z & 15) == 0)  store.markMeshDirtyIfLoaded(cx, cz - 1);
    if ((z & 15) == 15) store.markMeshDirtyIfLoaded(cx, cz + 1);

    // ★ 4) Save dirty（仅非生成、非加载来源）
    if (cause != MutationCause.WORLDGEN && cause != MutationCause.SAVE_LOAD) {
        c.markSaveDirty();
    }

    // ★ 5) 光照 dirty
    c.markLightDirty();

    // ★ 6) 延迟到步末统一广播（§C.2 第 11 步）
    pendingChanges.add(new BlockChange(x, y, z, oldId, newId, cause, currentStep()));
    return Optional.of(pendingChanges.get(pendingChanges.size() - 1));
}
```

**第 3 步是必须做对的**：位于 `x & 15 == 0` 的方块被移除后，其左侧相邻区块（`cx - 1`）的方块需要显示出原本被遮挡的面。**若只标记本区块，会出现"区块边界处残留一整面不该存在的墙"。**

**`markMeshDirtyIfLoaded` 的 `IfLoaded` 很关键**：不能在此处触发相邻区块的生成 —— 那会让一次挖掘引发连锁生成。相邻区块未加载时，它加载后本来就会完整构建网格，无需在此标记。

### H.4 延迟广播（步末统一 flush）

```java
void flushMutations() {
    if (pendingChanges.isEmpty()) return;
    // 按坐标去重：一步内沙子链式下落可能写同一格两次
    Map<Long, BlockChange> deduped = new LinkedHashMap<>();
    for (BlockChange ch : pendingChanges) deduped.put(posKey(ch), ch);   // 保留最后一次
    pendingChanges.clear();

    for (BlockChange ch : deduped.values())
        for (BlockChangeListener l : listeners) l.onBlockChanged(ch);
}
```

**为什么要延迟到步末**：
1. **合并重复改动**：把同一区域的光照/网格重算从多次降到一次。
2. **避免"半完成状态"被观察到**：若立即广播且触发了光照重算，而同一步后续还有方块改动，光照会基于中间状态计算然后又被标脏 —— 纯浪费。
3. **顺序确定性**：LinkedHashMap 保留插入顺序，广播顺序 = 操作顺序。

**MVP 需要实现的监听器：**

| 监听器 | 职责 |
|--------|------|
| `MeshInvalidationListener` | 入队 MeshRebuildQueue（`Chunk.setBlockInternal` 已置 meshDirty） |
| `LightUpdateListener` | 调 `LightEngine.recomputeRegion(...)`（§H.5） |
| `SaveDeltaListener` | 记录脏区块（`saveDirty`），供存档增量写入 |
| `FeedbackListener` | 破坏/放置音效（MVP 占位）、破坏粒子（**MVP 8–12 个**） |

> **【已被 `TECH_DESIGN_v0.1.1` §W′.1 取代（E-15 / `DRIFT-A-01`）】**
> 上表原写「破坏粒子 MVP **3–5 个**」，与 `PRD_v0.3.2` 第 417 行的「**8–12 个**方块颜色粒子」互斥，
> 属**规格漂移**（技术文档不得反向覆盖产品规格）。现行口径：**8–12 个**，【MVP 必须】，
> **M2 建立粒子后端时交付**；完整/正式粒子表现才归 Alpha / M4。
> 另：`§M.7` 的「命中方块 溅射粒子 3–5 个」是**另一件事**（PRD 未规定数量，工程缺省保留），
> 不得再被引用为破坏粒子的口径。详见 `TECH_DESIGN_v0.1.1.md` §W′.1。
| `DropSpawnListener` | `PLAYER_BREAK` 时按 `BlockDefinition.drop` 生成 `ItemEntity` |

### H.5 光照更新（PRD 4.4：固定径向衰减，不做全局传播）

```
光照值 = clamp(max(skyLight, maxOverTorches(torchLight)), 0, 15)

skyLight(x,y,z)   = (y >= heightMap[x][z]) ? (int)(15 * dayFactor) : 0
torchLight(x,y,z) = max over torches t within radius 6 of:
                        max(0, TORCH_LEVEL - chebyshevDistance(t, (x,y,z)))
                    // TORCH_LEVEL = lightEmission = 14
```

```java
public final class LightEngine {
    /** 整体清除 + 重新叠加，而非增量传播 —— MVP 火把少，简单正确优先 */
    void recomputeRegion(int bx, int by, int bz, int radius, int lightLevel);
}
```

**为什么用"整体清除 + 重新叠加"而非 BFS 传播**：PRD 4.4 明确不做全局传播，光照只是固定径向衰减。增量更新需要处理"移除火把后区域要变暗" —— 而"变暗"无法通过叠加实现，必须清除后重算。区域半径 6 → 13³ = 2197 格，一次重算微秒级，且只在火把增删时发生。

**跨区块边界**：遍历所有相交的**已加载**区块。**不得在此触发大范围区块生成**；未加载区块在将来加载时自行计算光照。

**`dayFactor`（PRD 4.4）：**

| 阶段 | 时长 | dayFactor |
|------|------|-----------|
| 黎明 | 1 分钟 | 0.15 → 1.0 线性插值 |
| 白天 | 11 分钟 | 1.0 |
| 黄昏 | 1 分钟 | 1.0 → 0.15 线性插值 |
| 夜晚 | 7 分钟 | **0.15**（最低亮度为白天的 15%） |
| 合计 | **20 分钟** | 一完整昼夜 |

**`dayFactor` 只影响 `skyLight`，不影响火把光** —— 否则夜间连火把都会变暗，与 PRD「火把成为主要照明」的设计意图相反。

### H.6 玩家放置的完整校验（PRD 5.2 落地）

```java
for (int bx = targetX; ...) {
    if (targetY < 1 || targetY > 127)                              return FAIL_OUT_OF_RANGE;
    if (!isReplaceable(world.getBlock(tx, ty, tz)))                return FAIL_OCCUPIED;
    if (!hasSolidNeighbor(world, tx, ty, tz))                      return FAIL_NO_SUPPORT;   // v0.3 新增
    if (world.anyEntityIntersects(blockAABB(tx, ty, tz)))          return FAIL_ENTITY_OVERLAP;
    if (!def.placeable())                                          return FAIL_NOT_PLACEABLE;
    if (inventory.countOf(def.itemIdOrSelf()) < 1)                 return FAIL_NOT_HELD;
}
```

**校验顺序有意从"最便宜且最常失败"到"最昂贵"**：y 范围与空气检查是几次数组读取；实体冲突需遍历实体并做 AABB 测试；背包检查涉及物品遍历。顺序不影响正确性（全是纯判定），但影响高频调用的开销（玩家按住右键会每 tick 尝试放置）。

**「相邻支撑」这条是实现**容易漏**的一处，也是 v0.3 最重要的规则变更**（把悬空放置从允许改为禁止）：

```java
private boolean hasSolidNeighbor(World w, int x, int y, int z) {
    for (BlockFace f : BlockFace.values())
        if (w.getDefinition(x + f.nx, y + f.ny, z + f.nz).solid()) return true;
    return false;
}
```

**必须用 `solid` 而非 `transparent`**：火把是透明的但**非固体**，玩家不应能靠火把向外延伸搭桥。用错字段会让"火把搭桥"成为一种可利用的漏洞。

---

## I. 玩家物理

### I.1 玩家规格表

| 参数 | 值 | 依据 |
|------|-----|------|
| `WIDTH` × `HEIGHT` × `DEPTH` | **0.6 × 1.8 × 0.6** | 体素游戏惯例 |
| `EYE_HEIGHT`（相对脚底） | **1.62** | 同上 |
| `MAX_HEALTH` | **20**（= 10 颗心） | PRD 5.3 |
| `MOVE_SPEED` | **4.317 格/秒** | 体素惯例值，手感已充分验证 |
| `SNEAK_SPEED_MULT` | **0.3** | PRD 6.6 有键位但**未定义效果** → 见 §I.7 |
| `AIM_SPEED_MULT` | **0.6** | PRD 5.4.3 |
| `GRAVITY` | **32.0 格/秒²** | 与跳跃初速度配套，见下 |
| `JUMP_VELOCITY` | **8.95 格/秒** | 跳跃高度 = 8.95² / (2 × 32) = **1.25 格** |
| `TERMINAL_VELOCITY` | **-60 格/秒** | 防止单步位移过大 |
| `VOID_KILL_Y` | **-8.0**（世界坐标） | PRD 4.5 |
| `FALL_DAMAGE_THRESHOLD` | **3.0 格** | PRD 5.3 |
| `AIR_CONTROL` | **0.2** | 空中只能施加 20% 的水平控制 |
| `GROUND_FRICTION` | 立即到目标速度（无惯性滑行） | MVP 手感优先 |
| `FOV` | 70°（瞄准 45°） | PRD 6.5 / 5.4.3 |

### I.2 AABB 与碰撞世界接口

```java
public record AABB(double minX, double minY, double minZ,
                   double maxX, double maxY, double maxZ) {
    AABB offset(double dx, double dy, double dz);
    AABB union(AABB other);
    AABB expand(double dx, double dy, double dz);
    boolean intersects(AABB other);

    /** 以"脚底中心点"构造玩家碰撞盒 */
    static AABB ofPlayerFeet(double x, double feetY, double z) {
        return new AABB(x - 0.3, feetY, z - 0.3, x + 0.3, feetY + 1.8, z + 0.3);
    }
}

/** 逻辑层与体素存储之间的唯一接口：物理层不需要知道区块、光照、存档 */
public interface CollisionWorld {
    boolean isSolid(int bx, int by, int bz);      // 水平越界视为非固体（虚空）；y 越界视为非固体
    AABB blockAABB(int bx, int by, int bz);
    List<BlockPos> solidBlocksIn(AABB span);      // 遍历 span 覆盖的方块，返回 solid 者
    boolean anyEntityIntersects(AABB box);        // 放置方块时的实体冲突检测（PRD 5.2）
}
```

**为什么用接口而不是直接传 `World`**：物理层只需知道"哪些位置是固体"。这个接口同时是**将来把物理放到独立线程或服务端的接缝**（PRD 14.3），成本为零。

### I.3 碰撞求解：分轴 AABB resolution

**轴顺序固定为 `Y → X → Z`。**

```java
public void move(PhysicsBody body, double dx, double dy, double dz, CollisionWorld world) {
    // ★ 1) 子步细分：任一轴位移超过 0.5 格即拆分，防止高速穿墙
    int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) / 0.5);
    steps = Math.max(steps, 1);
    double sx = dx / steps, sy = dy / steps, sz = dz / steps;

    for (int i = 0; i < steps; i++) {
        // ★ 2) 轴顺序固定：Y → X → Z
        body.y += moveAxisY(body, sy, world);
        body.x += moveAxisX(body, sx, world);
        body.z += moveAxisZ(body, sz, world);
    }
}

// 以 Y 轴为例
private double moveAxisY(PhysicsBody body, double delta, CollisionWorld world) {
    if (delta == 0) return 0;
    AABB from = body.box();
    AABB span = from.union(from.offset(0, delta, 0));
    for (BlockPos p : world.solidBlocksIn(span)) {
        AABB b = world.blockAABB(p);
        if (!span.intersects(b)) continue;
        if (delta > 0) delta = Math.min(delta, b.minY() - from.maxY());   // 向上顶到方块底面
        else           delta = Math.max(delta, b.maxY() - from.minY());   // 向下踩到方块顶面
    }
    return delta;
}
```

**轴顺序取 `Y → X → Z` 的技术论证（这是"经技术论证后的固定顺序"）：**

| 论证点 | 说明 |
|--------|------|
| ① `onGround` 必须最早确定 | 水平移动的控制力（地面 100% / 空中 20%）、跳跃许可、`stableStandTimer` 全部依赖 `onGround`。若先解 X/Z，则水平移动使用的是**上一步**的 `onGround`，在"落地的那一步"会错误地施加空中控制 |
| ② 落点是最高影响的判决 | 它决定坠落伤害、跳跃、`lastSafePosition`。把它放在第一顺位，可确保同一步内"水平移动到一个方块上方并落下"被正确解析为"站在该方块上"，而不是延后一步 |
| ③ 反例（X → Y → Z 的具体失效） | 玩家从方块边缘水平移出并下落的同一步：X 轴先解时，玩家仍在旧 y（高于方块顶面），水平移动被判定为**无阻挡**通过；随后 Y 轴下落才发现脚下是新的方块。结果虽常正确，但当"移出后正下方恰是矮一格的地形"时，会先生成一次水平位移再竖直下落，**比 Y 优先多出一次位置修正**，表现为边缘处的轻微抖动 |
| ④ 顺序本身比选哪个更重要 | 严格来说两种顺序都可用，但**必须固定**。固定顺序 = 行为可复现（§C.1 的要求）。测试用例在 M1 固化 |

> 用户提示的顺序为 `X → Y → Z`。本设计采用 **`Y → X → Z`** 并给出上述论证；要求"固定顺序"这一条已满足。若用户要求必须为 `X → Y → Z`，改动点集中在 `Physics.move()` 一个方法内，无扩散。

**为什么必须逐轴分离，不能三轴同解**：三轴同时求解需计算滑块面/sweep，正确性对边界情况（同时贴墙贴地）极敏感。逐轴分离天然产生"沿墙滑动"效果（被截断的轴不影响其他轴），且**每轴只需一维截断**。

**为什么子步细分是正确性要求而非优化**：60 Hz 下终端速度 -60 格/秒 → 单步位移 **-1.0 格**，**必然穿透** 1 格厚的方块。0.5 格阈值是阈值与性能的折中：正常移动（4.317 格/秒）单步位移 0.072 格，**永不触发细分，零开销**。

### I.4 玩家位置语义

| 概念 | 定义 |
|------|------|
| `position` | **脚底中心点** `(x, feetY, z)` |
| `eyePosition` | `(x, feetY + 1.62, z)` |
| 站在地表 | `feetY = 64.0`（表面块占据 y = 63，顶面标高 64，§D.6） |
| **重生站位** | **`(0.5, 64.0, 0.5)`** |

### I.5 为什么重生点必须写 `(0.5, 64.0, 0.5)`

PRD 写的是 `(0, 64, 0)` —— 那是**方块坐标**。若把实体位置直接设为 `(0.0, 64.0, 0.0)`，玩家会站在方块 `(0,·,0)`、`(0,·,-1)`、`(-1,·,0)`、`(-1,·,-1)` 四个方块的**公共棱交点**上 —— 碰撞盒有一半悬在方块外，落点判定与边缘滑落都变得不确定，表现为"站在方块边缘抖动"（用户明确列为必须避免的现象）。

**实体位置必须取方块中心**：方块 `(bx, bz)` 的中心世界坐标为 `(bx + 0.5, bz + 0.5)`。这是 PRD 的方块坐标语义与实体坐标语义的差别，**必须在实现时补齐**。

### I.6 落地、坠落伤害与 lastSafePosition

```java
// 在 §C.2 第 6 步 player.postPhysics() 中执行
void postPhysics(PlayerEntity p, double dt) {
    boolean wasOnGround = p.onGround;
    p.onGround = (p.vy <= 0) && collidesVertically(p, -1e-4);

    if (!p.onGround) {
        if (p.vy < 0) p.fallDistance += -p.vy * dt;        // ★ 只累计下落段
        p.stableStandTimer = 0;
    } else {
        if (!wasOnGround && p.fallDistance > 0) {
            int dmg = (int) Math.max(0, Math.floor(p.fallDistance - 3.0));   // PRD 5.3
            if (dmg > 0) p.damage(new DamageSource(DamageKind.FALL, dmg));
            p.fallDistance = 0;
        }
        // PRD 5.3.1：落地并稳定站立 ≥ 0.5 秒才更新
        p.stableStandTimer += dt;
        if (p.stableStandTimer >= 0.5) {
            p.lastSafePosition = new Vec3d(p.x, p.y, p.z);
            p.stableStandTimer = 0.5;    // 钳制，避免无限增长
        }
    }

    // ★ 虚空致死优先于普通坠落伤害（PRD 4.5）
    if (p.y < VOID_KILL_Y) { p.killImmediately(DamageKind.VOID); return; }
}
```

**四个必须记对的细节：**

1. **`fallDistance` 只累计下落段**（`vy < 0`）。上升段不累计 —— 否则"跳起来再落地"会有伤害。
2. **虚空死亡优先于普通坠落伤害**（PRD 4.5「仅以触底判定致死，不结算普通坠落伤害」）。顺序写反的话，坠入虚空会先受一次巨额坠落伤害，死亡提示与"物品掉落位置"都会走错分支。
3. **`lastSafePosition` 只在"落地且稳定站立 ≥ 0.5 秒"时更新**（PRD 5.3.1）。若每帧更新，玩家从悬崖边缘开始坠落时最后几帧的**悬空位置**会被记成"安全位置"，虚空死亡的掉落物会生成在悬崖外、**直接掉进虚空** —— 恰好破坏这条规则要解决的唯一问题。
4. **`stableStandTimer` 需钳制**，否则长时间站立会使其无限增长（double 虽不会溢出，但失去语义）。

### I.7 【发现项 D2】PRD 未定义效果的两处键位

| 键位 | PRD 6.6 | 问题 | 本设计采用 |
|------|---------|------|------------|
| `Shift` | 「潜行」 | **全文未定义潜行的任何效果**（移速？防滑落？视高降低？） | MVP 只实现**移速 × 0.3**。**不实现"边缘防滑落"** —— 它需要额外边缘检测与位置钳制，属玩法功能，应先在 PRD 定义再实现 |
| 数字键 `1–4` | 「切换枪械」 | 与 PRD 6.1「数字键 1–9 高亮当前槽」冲突；且 MVP 只有 1 把枪，"切换"无对象 | 统一为 **数字键 1–9 选择快捷栏槽位**（含非枪械物品）。此为 6.1 的口径，也是通用体素游戏惯例。<br>**【已由 `PRD_v0.3.2` 第 532 / 1255 行终裁】**：数字键 **1–9** + 鼠标滚轮，**全文废止一切「仅 1–4」的描述** —— 本行结论不变，但性质已从"工程缺省值"升级为"产品终裁口径"（`TECH_DESIGN_v0.1.1` §W′.2 / E-17） |

> 两处均**不改变 PRD 的玩法意图**，只是把"未定义的部分"显式补上缺省值。建议在 `PRD v0.3.2` 把这两个键位的效果补写清楚。

### I.8 跳跃

```java
if (intent.jump() && p.onGround) {
    p.vy = JUMP_VELOCITY;        // 8.95
    p.onGround = false;
    p.stableStandTimer = 0;      // 起跳使"稳定站立计时"清零
}
```

**不做 jump 缓冲与土狼时间（coyote time）**：这两项是纯增量手感优化，需试玩数据调参。列入 M5。

---

## J. Raycast

### J.1 用途与统一入口

| 用途 | 起点 | 方向 | 长度 | 结果用途 |
|------|------|------|------|----------|
| 挖掘 / 放置 | 眼睛位置 | 相机前向 | **5.0**（PRD 5.2） | 命中方块 → 破坏 / 在其邻面放置 |
| 射击命中 | 眼睛位置 | 相机前向 | 枪械有效射程 | 与实体命中比较取最近（§15） |

```java
public record BlockRayHit(
    boolean hit,
    int blockX, int blockY, int blockZ,        // 命中的方块坐标
    BlockFace face,                            // 进入面 → 即该面的外法向
    double distance,                           // 沿射线的参数 t（格）
    Vector3f hitPoint,                         // 精确交点
    int adjacentX, int adjacentY, int adjacentZ // ★ 供放置使用的相邻位置 = block + face.normal
) {}

public interface BlockRaycaster {
    BlockRayHit cast(Vector3d origin, Vector3f direction, double maxDistance, World world);
}
```

**`adjacentPlacementPosition` 必须由 Raycast 一并返回**，而不是让放置逻辑自己算 `hit + face.normal`。理由：这个符号极易写反（写反后放置的方块会落在玩家身体里），而**只有一个地方算它**就只有一个地方可能错。

### J.2 算法：3D Voxel DDA（Amanatides & Woo）

**为什么用 DDA 而不是固定步长采样**：
- 固定小步长（如 0.01 格）在 5 格射程下要采样 500 次，且**仍可能漏掉薄方块**。
- DDA 精确按射线穿过的每个体素推进，**每格一次整数运算 + 一次比较**，**永不漏格**。射程 5 格时最多访问约 15 个方块。

```java
public static BlockRayHit cast(Vector3d o, Vector3f d, double maxDist, World world) {
    int bx = (int) Math.floor(o.x), by = (int) Math.floor(o.y), bz = (int) Math.floor(o.z);

    int stepX = d.x > 0 ? 1 : (d.x < 0 ? -1 : 0);
    int stepY = d.y > 0 ? 1 : (d.y < 0 ? -1 : 0);
    int stepZ = d.z > 0 ? 1 : (d.z < 0 ? -1 : 0);

    // ★ 分量为 0 时 tDelta = +∞ → 该轴永不成为"下一个穿越轴"
    double tDeltaX = stepX != 0 ? Math.abs(1.0 / d.x) : Double.POSITIVE_INFINITY;
    double tDeltaY = stepY != 0 ? Math.abs(1.0 / d.y) : Double.POSITIVE_INFINITY;
    double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / d.z) : Double.POSITIVE_INFINITY;

    double tMaxX = initialTMax(o.x, d.x, bx, stepX);
    double tMaxY = initialTMax(o.y, d.y, by, stepY);
    double tMaxZ = initialTMax(o.z, d.z, bz, stepZ);

    BlockFace entered = null;
    double t = 0;
    while (t <= maxDist) {
        if (world.getDefinition(bx, by, bz).solid()) {
            return BlockRayHit.hitAt(bx, by, bz, entered, t, o, d);
        }
        if (tMaxX < tMaxY && tMaxX < tMaxZ) {
            bx += stepX; t = tMaxX; tMaxX += tDeltaX; entered = (stepX > 0) ? BlockFace.NEG_X : BlockFace.POS_X;
        } else if (tMaxY < tMaxZ) {
            by += stepY; t = tMaxY; tMaxY += tDeltaY; entered = (stepY > 0) ? BlockFace.NEG_Y : BlockFace.POS_Y;
        } else {
            bz += stepZ; t = tMaxZ; tMaxZ += tDeltaZ; entered = (stepZ > 0) ? BlockFace.NEG_Z : BlockFace.POS_Z;
        }
    }
    return BlockRayHit.miss();
}

private static double initialTMax(double o, double d, int b, int step) {
    if (step > 0)      return (b + 1 - o) / d;
    else if (step < 0) return (b - o) / d;
    else               return Double.POSITIVE_INFINITY;
}
```

**三个必须处理对的边界条件：**

1. **分量为 0 → `tDelta = +∞`。** `1.0 / 0.0 = +∞`（IEEE 754）是正确的；但 `1.0 / -0.0 = -∞`，会让该轴被永久选中。因此**必须取绝对值，且 `step == 0` 时显式短路**。
2. **起点恰好落在方块平面上**：用 `Math.floor` 而非 `(int)` 取起始格（负数时 `(int)` 向零截断会出错）。循环第一轮即自检起点格，因此起点在实体内也能正确处理。
3. **命中面法向**：DDA 返回"从哪个面进入"，也就是**该面的外法向**。`NEG_X` 表示从 −X 侧进入。

**`solid` 的选取**：DDA 判定用的是 `solid()`（而非 `transparent()`）。火把 `solid = false`，因此**射线可以穿过火把** —— 与"火把不能作为搭桥支撑"（§H.6）保持一致。这一致性是有意的。

---

## K. Entity

### K.1 最低结构（明确不做完整 ECS）

```
Entity (abstract)
├─ PlayerEntity
├─ MeleeMonsterEntity
└─ ItemEntity
```

**明确不实现**：完整 ECS、Component Framework、组件注册表、系统调度器（理由见 §A.7）。

```java
public abstract class Entity {
    protected final int uniqueEntityId;        // 递增分配，仅运行时
    protected double x, y, z;                  // 脚底中心（逻辑位置）
    protected double prevX, prevY, prevZ;      // 上一步位置
    protected double renderX, renderY, renderZ;// 插值后的渲染位置（与逻辑位置严格分离）
    protected double vx, vy, vz;
    protected float yaw, pitch;
    protected AABB aabb;
    protected int health;
    protected boolean alive = true;
    protected boolean onGround;
    protected double fallDistance;

    public abstract EntityKind kind();               // PLAYER | MELEE_MONSTER | ITEM
    public abstract String stableKindId();           // 存档用稳定字符串（§0.2 同原则）
    public abstract void tick(EntityContext ctx, double fixedDt);
    public abstract AABB computeAABB();

    public void applyInterpolation(double alpha) {
        renderX = prevX + (x - prevX) * alpha;       // ★ 只写 renderX/Y/Z
        renderY = prevY + (y - prevY) * alpha;
        renderZ = prevZ + (z - prevZ) * alpha;
    }
}
```

**`prevX/Y/Z` 与 `renderX/Y/Z` 必须与 `x/y/z` 严格分离。**
- 若把插值结果写回 `x/y/z`，就违反 §B.2 第 1/2 条（Renderer 不写 GameState），逻辑结果会依赖帧率。
- 若 `prev` 在物理积分**之后**保存，则 `prev == curr`，插值失效，画面变成逐逻辑帧跳变。
- **正确的保存时机：在 §C.2 第 5 步（物理积分）之前保存 `prev`。**

### K.2 MVP 三种实体

| 实体 | `stableKindId` | PRD 依据 | 关键行为 |
|------|----------------|----------|----------|
| `PlayerEntity` | `skyisland:player` | 5.3 | 意图驱动、生命、`lastSafePosition`、死亡/重生、`GunInstance` |
| `MeleeMonsterEntity` | `skyisland:melee_monster` | 5.5.1 / 5.5.2 | HP 20、伤害 4、速度 2.0 格/秒、7 步 AI |
| `ItemEntity` | `skyisland:item` | 5.2 / 4.5 | 掉落物：磁吸、5 分钟消失、虚空销毁 |

### K.3 近战怪 7 步 AI（PRD 5.5.2 落地）

PRD 只给 7 步行为，**未给具体数值**。下表为工程缺省值，**须在 M3 试玩后校准**。

| 参数 | 缺省值 | 说明 |
|------|--------|------|
| `DETECT_RANGE` | 24.0 格 | 与 PRD 5.5.3「刷怪距离 16–32 格」相容 |
| `LOSE_RANGE` | 32.0 格 | 迟滞带，避免边界反复进出追击态 |
| `ATTACK_RANGE` | 1.5 格 | 怪体宽 0.6 + 玩家体宽 0.6 = 1.2，留 0.3 余量 |
| `ATTACK_COOLDOWN` | 1.0 秒 | 每秒 1 次 |
| `ATTACK_DAMAGE` | **4** | PRD 5.5.1 |
| `MOVE_SPEED` | **2.0 格/秒** | PRD 5.5.1 |
| `HEALTH` | **20** | PRD 5.5.1 |
| 垂直移动 | ❌ 不跳跃、不爬升 | PRD 5.5.2 禁止项 |

```
对应 PRD 5.5.2 的 7 步：
1. 检测玩家：horizontalDistance <= DETECT_RANGE → CHASING；> LOSE_RANGE → IDLE
2. 追击：目标速度 = 朝玩家的水平单位向量 × MOVE_SPEED
3. 朝玩家水平移动：只设 vx/vz，vy 完全交给重力（不跳跃、不飞行）
4. 前方碰撞时尝试左右偏转：
     期望方向 d；若 d 前方 0.5 格为 solid：
       依次尝试 rotate(d,+45°)、rotate(d,+90°)、rotate(d,-45°)、rotate(d,-90°)
       取第一个"前方非 solid 且其下方 1 格为 solid（有落脚点）"的方向
5. 前方为虚空则停止或换方向：
     若目标方向前方 0.5 格的下方 1 格无 solid（会踏空），视为不可走 → 回到第 4 步备选
     全部不可走 → 停止移动（★ 不主动踏入虚空）
6. 接近玩家后攻击：horizontalDistance <= ATTACK_RANGE 且冷却结束 → 造成 ATTACK_DAMAGE
7. 无法到达时不做全地图寻路（允许卡住）
```

**第 4、5 步是"7 步 AI"中唯一有难度的部分：**

- **必须检查"落脚点"，而非只看"前方有没有方块"。** 只检查前方非固体是不够的：虚空边缘的前方也是非固体，怪会直接走出去掉进虚空。必须同时检查"前方格的下方是固体"。**这就是 PRD 第 5 步「不主动踏入虚空」的实现含义。**
- **偏转角度的尝试顺序固定**，保证行为可复现。
- **不做动态寻路（PRD 5.5.2 明文禁止）**：怪遇凹形障碍或需绕大圈时会卡住。这是**设计上接受的结果，不是 bug**。任何试图"顺手加个 A*"的实现都违反边界声明。

**怪物坠入虚空（PRD 5.5.2 边界条件）**：

```java
if (monster.y < VOID_KILL_Y) { monster.remove(); }   // 立即移除，不掉落任何物品
```

### K.4 ItemEntity

| 参数 | 值 | 依据 |
|------|-----|------|
| `MAGNET_RADIUS` | 2.0 格 | PRD 5.2 |
| `MAGNET_ACCEL` | 12 格/秒² | 手感值 |
| `PICKUP_RADIUS` | 0.8 格 | 工程值 |
| `LIFETIME_SECONDS` | **300**（5 分钟） | PRD 5.2 |
| 虚空销毁 | `y < -8` 立即销毁 | PRD 4.5 / 5.2 |
| 生成初速 | 水平随机 0.5–1.5 格/秒 + 向上 1.5 格/秒 | 视觉表现 |
| 拾取失败提示节流 | 同类提示 5 秒内不重复 | PRD 6.7 |
| 与其他实体碰撞 | ❌ 不做 | MVP 简化 |

**"背包满则原地停留 5 分钟"的实现细节**：计时用 `age = (currentStep - spawnStep) * fixedDt`，`age >= 300` 即消失。**不做"玩家靠近时暂停计时"** —— 那会让玩家通过反复靠近永久保留掉落物，与规则意图（防止掉落物无限堆积）相反。

### K.5 EntityManager

```java
public final class EntityManager {
    private final List<Entity> entities = new ArrayList<>();

    public void tick(EntityContext ctx, double fixedDt);   // 遍历更新，之后批量移除 dead
    public void add(Entity e);
    public List<Entity> all();
    public List<Entity> inAABB(AABB box);                  // 放置冲突检测 / Hitscan 宽相
    public List<MeleeMonsterEntity> monsters();
    public int monsterCount();
}
```

**MVP 不做空间划分**（不建网格/四叉树/BVH）：PRD 5.5.4 的 TL1 上限是 8 只怪，加掉落物通常 < 50 个实体。对 50 个实体线性扫描 = 50 次 AABB 测试，远低于空间索引的维护成本。**空间划分列为 M5 可选优化，且只有实体数超过约 500 时才值得。**

**`tick` 中的并发修改**：`tick` 内部会移除实体（坠入虚空、到期）。必须用显式收集后批量移除（或 `removeIf`），**不允许在 for-each 中直接 `entities.remove()`**（`ConcurrentModificationException`）。

### K.6 为什么不现在做 ECS（明确论证）

| 论点 | 说明 |
|------|------|
| 规模不匹配 | 3 种实体、峰值约 50 个。ECS 的性能优势在此量级**完全不存在** |
| 调试成本 | ECS 的"数据与行为分离"使调试困难；M0/M1 最需要的是能直接打断点看清 `Player` 的完整状态 |
| 重构时机 | 若 M5 实测实体数成为瓶颈，那时游戏已跑通，重构有回归测试保护。**现在重构没有保护** |
| 需求真实度 | 无任何实测数据支持"实体会成为瓶颈"。**基于假设做架构重构是负债，不是投资** |

---

## L. Inventory / Item / Recipe

### L.1 物品与方块是两套注册表（关键架构决策）

```java
public record ItemDefinition(
    String  stableId,         // "skyisland:iron_ingot"
    short   runtimeId,
    String  displayName,      // 中文名（PRD 6.7 术语统一）
    int     maxStack,         // 默认 64；弹药 128；枪械 1
    ItemCategory category,    // BUILD | MATERIAL | AMMO | GUN | FOOD
    boolean blockItem,        // 是否可放置的方块物品
    String  blockStableId     // 仅 blockItem 时非 null，指向 BlockDefinition.stableId
) {}

public enum ItemCategory { BUILD, MATERIAL, AMMO, GUN, FOOD }
```

**为什么必须分开（四类情况，全部无法用单套 ID 表达）：**

1. **存在非方块物品**：木棍、铁锭、煤炭、火药、手枪、手枪弹 —— 无方块形态。
2. **存在"掉落物 ≠ 自身"的方块**：石头掉圆石、煤炭矿石掉**煤炭（物品）**、草方块掉泥土。**"方块 → 掉落物"是映射，不是恒等关系。**
3. **存在有方块但无物品**：`skyisland:resource_core` **没有物品形态**，玩家无法持有。
4. **堆叠上限不同**：方块统一 64，弹药 128，枪械 1。

> **反面设计（已否决）**：用一套 ID 空间混用物品与方块 ID。它会在第 3 点直接失效（需要一个"没有物品"的标记），并让第 2 点变成特例分支。分开建模的代价只是多一个注册表与一个映射。

**方块物品的自动注册**：

```java
for (BlockDefinition b : blockRegistry.all()) {
    if (b.systemBlock()) continue;              // ★ 系统方块无物品形态
    itemRegistry.register(new ItemDefinition(
        b.stableId(), ..., blockItem = true, blockStableId = b.stableId()));
}
```

### L.2 MVP 物品清单

| stableId | 中文名 | 类别 | maxStack | 来源 |
|----------|--------|------|---------:|------|
| 13 个方块物品 | 同方块名 | BUILD | 64 | 挖掘 / 合成 / 初始物资 |
| `skyisland:stick` | 木棍 | MATERIAL | 64 | R02 |
| `skyisland:iron_ingot` | 铁锭 | MATERIAL | 64 | R03 / 怪物掉落（Alpha） |
| `skyisland:coal` | 煤炭 | MATERIAL | 64 | 挖掘煤炭矿石 / 怪物掉落 |
| `skyisland:gunpowder` | 火药 | MATERIAL | 64 | R06 / 怪物掉落（Alpha） |
| `skyisland:pistol` | 手枪 | GUN | **1** | 初始物资 / R14（Alpha） |
| `skyisland:pistol_ammo` | 手枪弹 | AMMO | **128** | R11 / 初始物资 |

> `skyisland:iron_ore` 既是方块也是物品，已含在"13 个方块物品"内，**不额外注册**。

**明确不提前实现**：Alpha 的物品（铜锭、金锭、晶体、小麦、面包、步枪弹、霰弹、其余 3 把枪）**不注册**。数据结构（`ItemDefinition` 的 `blockStableId`、`ItemCategory.FOOD`）可预留，但**不写实际物品**。

### L.3 ItemStack 与 Inventory

```java
public final class ItemStack {
    private final short itemRuntimeId;
    private int count;
    public ItemStack copy();
    public boolean isEmpty();
    public boolean canMergeWith(ItemStack other);
    public int mergeFrom(ItemStack other);       // 返回实际接收数量
}

public final class Inventory {
    public static final int HOTBAR_SIZE = 9;
    public static final int MAIN_SIZE   = 27;
    public static final int TOTAL_SIZE  = 36;    // PRD 5.6.1

    private final ItemStack[] slots;             // [0..8] 快捷栏，[9..35] 主背包

    public int  countOf(short itemRuntimeId);    // 跨所有槽位计数（合成用）
    public boolean tryAdd(ItemStack stack);      // 先合并同类堆，再找空槽；满则返回 false
    public void remove(short itemRuntimeId, int count);
    public ItemStack getSlot(int i);
    public void swap(int a, int b);              // 拖拽整理
    public void quickMove(int from);             // Shift + 单击（PRD 6.2）
}
```

**`tryAdd` 的合并顺序**：**先遍历已有同类堆补满，再寻找空槽**。反过来（先找空槽）会导致挖到的方块散落在多个半堆槽位，体验明显劣化。

**`remove` 的扣减顺序**：**从后往前**（主背包 → 快捷栏）。若从前往后扣，合成会优先吃掉快捷栏里的材料 —— 而快捷栏是玩家手动排布的常用物品，被悄悄消耗会很恼人。

**背包满的判定**（PRD 5.2 / 6.7）：`tryAdd` 返回 false 时，掉落物**原地停留 5 分钟后消失**，并提示「背包已满」（同类提示 5 秒内不重复）。因此 `ItemEntity` 需要 `pickupFailedNotifiedStep` 字段实现节流。

### L.4 合成：无工作台的"背包内直接合成"

PRD 决策 9 与 Q2：**不做工作台、不做熔炉**。配方在背包界面内直接合成，因此配方是**无序的、按数量匹配**的，不存在 3×3 网格形状。

```java
public record Recipe(
    String stableId,             // "skyisland:r01_planks"
    ItemStack output,            // 产出（含数量）
    List<ItemStack> inputs,      // 材料清单（含数量）
    ItemCategory category        // PRD 6.3 分类筛选
) {}

public final class RecipeRegistry {
    public List<Recipe> craftable(Inventory inv);
    public List<RecipeStatus> allWithStatus(Inventory inv);   // UI 置灰用（PRD 6.3）
    public boolean craft(Recipe r, Inventory inv);
}

public record RecipeStatus(Recipe recipe, boolean craftable, List<MissingMaterial> missing) {}
public record MissingMaterial(String displayName, int required, int available) {}
```

**三条关键实现约束：**

1. **材料校验必须用"总拥有量"而非"单个槽位"。** 例如某配方需铁锭 ×4，若背包是 4 个各装 1 铁锭的槽位，**仍可合成**。按槽位校验是最常见的实现错误，会让玩家遇到"明明有材料却合不了"。
2. **`craft` 必须原子**：**先校验全部材料齐备，再一次性扣除**。边校验边扣时，遇到中途缺料会扣掉一部分材料却不产出 —— 最严重的玩家侧 bug 之一。
3. **缺料提示直接由 `missing` 渲染**为「缺少 铁锭 ×4」（PRD 6.7 空状态文案）。

### L.5 MVP 7 条配方

| stableId | 产出 | 材料 | category |
|----------|------|------|----------|
| `skyisland:r01_planks` | 木板 ×4 | 原木 ×1 | BUILD |
| `skyisland:r02_stick` | 木棍 ×4 | 木板 ×2 | BUILD |
| `skyisland:r03_iron_ingot` | 铁锭 ×1 | 铁矿石 ×1 + 煤炭 ×1 | MATERIAL |
| `skyisland:r06_gunpowder` | 火药 ×2 | 煤炭 ×2 + 沙子 ×1 | MATERIAL |
| `skyisland:r07_torch` | 火把 ×4 | 煤炭 ×1 + 木棍 ×1 | BUILD |
| `skyisland:r08_glass` | 玻璃 ×1 | 沙子 ×1 + 煤炭 ×1 | BUILD |
| `skyisland:r11_pistol_ammo` | 手枪弹 ×8 | **铁锭 ×1 + 火药 ×1** | AMMO |

> **R11 用铁锭而非铜锭**（PRD v0.3 修正 P01）。这条修正的工程意义是**硬性的**：MVP 唯一资源岛是石矿岛（富集石/煤/铁），铜矿石属 Alpha。若沿用铜锭，MVP 的"远征 → 弹药"链路**在数据层就不可能闭合**。配方数据必须与 PRD 5.6.2 逐字一致，并在 M3 验收时逐条打勾。

**开局初始物资**（PRD 5.7.1）—— **通过同一套 `Inventory.tryAdd` 注入，不走特例分支**：

```json
{ "items": [
  { "item": "skyisland:pistol",      "count": 1  },
  { "item": "skyisland:pistol_ammo", "count": 24 },
  { "item": "skyisland:planks",      "count": 32 },
  { "item": "skyisland:torch",       "count": 8  },
  { "item": "skyisland:iron_ingot",  "count": 4  },
  { "item": "skyisland:coal",        "count": 8  }
] }
```

**数据驱动的理由**：初始物资在 MVP→Alpha 之间已变更过两次（v0.2 有面包与小麦种，v0.3 移除）。写在 JSON 里，M4 调整时不需要改代码、不需要重新评审代码。

---

## M. Combat

### M.1 数据定义

```java
public record GunDefinition(
    String stableId,            // "skyisland:pistol"
    String displayName,         // "手枪"
    String ammoStableId,        // "skyisland:pistol_ammo"
    int    magazineSize,        // 12
    double fireIntervalSeconds, // 0.25（= 4.0 发/秒）
    double reloadSeconds,       // 1.2
    int    baseDamage,          // 8
    double effectiveRange,      // 32.0 格
    double falloffPerBlock,     // 0.9
    double minDamageFactor      // 0.20
) {}

public final class GunInstance {          // 玩家持有的一把枪的运行时状态
    private final GunDefinition def;
    private int magazineAmmo;             // 当前弹匣
    private boolean reloading;
    private double reloadRemaining;
    private double fireCooldown;
}
```

**MVP 只实现 Pistol。** SMG / Rifle / Shotgun **只允许数据结构预留（`GunDefinition` 字段已够用），不写实际玩法逻辑**。

| 枪械 | stableId | 伤害 | 弹匣 | 射速 | 有效射程 | MVP |
|------|----------|-----:|-----:|-----:|---------:|:---:|
| 手枪 | `skyisland:pistol` | **8** | **12** | **4.0 /秒** | **32 格** | ✅ |
| 冲锋枪 | `skyisland:smg` | 5 | 24 | 10.0 /秒 | 24 格 | ❌ Alpha |
| 步枪 | `skyisland:rifle` | 14 | 10 | 2.0 /秒 | 48 格 | ❌ Alpha |
| 霰弹枪 | `skyisland:shotgun` | 6×6 | 6 | 1.25 /秒 | 12 格 | ❌ Alpha |

### M.2 Hitscan：方块与实体取最近（PRD 12.2 的核心修正）

PRD v0.2 的「怪物优先于方块」是**错误规则，已废止**。正确规则：

> **同一射线同时计算方块命中与实体命中，取沿射线距离最近的合法碰撞结果。**

```java
public record HitResult(HitKind kind, BlockRayHit blockHit, Entity entity,
                        double distance, Vector3f point) {}
public enum HitKind { BLOCK, ENTITY, MISS }

public static HitResult fire(Vector3d eye, Vector3f dir, double maxRange,
                            World world, EntityManager entities, Entity shooter) {
    // 1) 方块命中（DDA）
    BlockRayHit bh = BlockRaycaster.cast(eye, dir, maxRange, world);
    double blockDist = bh.hit() ? bh.distance() : Double.POSITIVE_INFINITY;

    // 2) 实体命中（射线-AABB 板法），仅在方块距离内考虑
    double bestEntDist = Double.POSITIVE_INFINITY;
    Entity bestEnt = null;
    for (Entity e : entities.all()) {
        if (e == shooter || !e.alive()) continue;
        double t = rayAABB(eye, dir, e.aabb(), maxRange);
        if (t >= 0 && t < bestEntDist) { bestEntDist = t; bestEnt = e; }
    }

    // ★ 3) 取最近 —— 这一行是 PRD 12.2 的全部要点
    if (blockDist <= bestEntDist)
        return new HitResult(HitKind.BLOCK, bh, null, blockDist, bh.hitPoint());
    return new HitResult(HitKind.ENTITY, null, bestEnt, bestEntDist,
                         pointAt(eye, dir, bestEntDist));
}
```

**为什么"实体优先"是错的**：若先无条件检测实体并按命中，玩家可以**隔着墙射杀怪物** —— 射线在碰到墙之前确实"穿过"了墙后怪物的包围盒。玩家会立刻利用这个漏洞（对着墙清怪），且让掩体完全失去意义。**取最近是唯一正确的语义。**

**比较用 `blockDist <= bestEntDist`（方块优先）**：当两者恰好相等（射线擦着方块表面且同时穿过实体同一位置）时判为方块命中，避免玩家因站在墙角而"擦边命中"墙后的怪。

### M.3 射线-AABB 相交（板法 Slab Method）

```java
/** 返回沿射线第一个交点的参数 t（>=0）；不相交返回 -1 */
static double rayAABB(Vector3d o, Vector3f d, AABB b, double maxT) {
    double tmin = 0, tmax = maxT;
    for (int axis = 0; axis < 3; axis++) {
        double od = comp(o, axis), dd = comp(d, axis);
        double lo = minAxis(b, axis), hi = maxAxis(b, axis);
        if (Math.abs(dd) < 1e-9) {                       // ★ 必须处理平行
            if (od < lo || od > hi) return -1;
        } else {
            double t1 = (lo - od) / dd, t2 = (hi - od) / dd;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return -1;
        }
    }
    return tmin;
}
```

**必须处理"射线平行于某一对板"**（`|d| < ε`）。不处理会得到 `0/0 = NaN`，而 `NaN` 的一切比较均为 false，导致整个测试静默返回"不相交" —— 表现为"水平射击打不中与视线等高的怪物"，且**没有任何报错**。

### M.4 伤害结算

```
最终伤害 = baseDamage × distanceFalloff    （向下取整，最小 1）

distanceFalloff:
  distance <= effectiveRange  →  1.0                                  （100%）
  否则                        →  max(0.20, 0.9 ^ (distance - effectiveRange))
                                  （每超 1 格 ×0.9，最低 20%）
```

**`0.9 ^ n` 的数值行为**：`0.9^7 ≈ 0.478`、`0.9^14 ≈ 0.229`、`0.9^15 ≈ 0.206`。即**手枪 32 格射程外再飞 15 格（= 47 格）伤害即触底 20%**。用 `Math.pow` 一次性计算，**不要循环逐格乘**（极端距离下有性能与精度双重问题）。

**MVP 的数值关系**：对 HP 20 的近战怪需 **3 发**命中（8+8+8 = 24 > 20）。一秒可打 4 发，**连续命中 3 发约需 0.5 秒**。这个关系需在 M3 试玩验证手感，但**数值本身不属本技术设计的裁量范围**（属 PRD）。

### M.5 枪械状态机

```
        ┌──────────────────────────────────────────────┐
        ↓                                              │
    [ IDLE ] ──按下开枪──> [ FIRING ] ──弹匣空──> [ NEED_RELOAD ]
        │  ↑                                              │
        │  └────────── 冷却结束 ←──────────────────────────┘
        │
        └──按下 R（弹匣未满 且 有对应弹药）──> [ RELOADING ] ──1.2 秒──> [ IDLE ]
                                                   │
                                    玩家移动 / 死亡 → 打断 → [ IDLE ]
```

```java
void tick(double fixedDt, PlayerEntity p) {
    fireCooldown = Math.max(0, fireCooldown - fixedDt);
    if (reloading) {
        if (playerMovedThisStep(p)) { cancelReload(p); return; }   // PRD 5.4.3：可被移动打断
        reloadRemaining -= fixedDt;
        if (reloadRemaining <= 0) finishReload(p);
    }
}
```

**四个必须做对的点：**

1. **`fireCooldown` 用"剩余时间递减"而非"记录上次开火时刻"。** 后者在暂停/恢复、读档后会算出巨大冷却（"上次开火时刻"是旧的世界时间），表现为**读档后开不了枪**。递减计时器天然免疫。
2. **换弹可被移动打断**（PRD 5.4.3）。打断时 `reloading = false`，**弹匣保持换弹前数量**（不做部分填充）。
3. **满弹匣换弹允许，剩余弹药回背包**（PRD 5.4.3）。
   > ⚠️ **工程缺省值**：若背包中对应弹药不足，**只填能填的部分，还是完全拒绝？** PRD 未明确。本设计采用**完全拒绝**（不消耗、不改弹匣、提示「弹药不足」），避免"半填充"的数量账目歧义。**建议在 PRD v0.3.2 明确。**
4. **界面打开时禁止开枪**（PRD 6.2）：背包打开时"鼠标显示光标且不再控制视角"，准星方向已冻结，允许开枪会造成"对着旧准星方向射击"。由 `PlayerIntent.attack()` 在 GUI 打开时被抑制。

### M.6 瞄准（ADS）

| 效果 | 普通 | 瞄准 |
|------|------|------|
| FOV | 70° | **45°** |
| 移动速度 | 100% | **60%** |
| 准星 | 普通十字 | 密集十字 |

**FOV 变化必须做阻尼过渡**（约 0.15 秒插值），否则像"画面突然放大"。阻尼系数作为常量集中定义，便于试玩调整。

### M.7 表现层反馈（MVP 占位）

| 反馈 | MVP 实现 |
|------|----------|
| 曳光轨迹 | 持续 **0.05 秒**（PRD 5.4.3）的线段，从枪口沿射线方向到命中点 |
| 命中方块 | 溅射粒子（**3–5 个，工程缺省，PRD 未规定数量**；占位方块色）+ 撞击音效（占位） |
| 命中实体 | 命中音效 + 实体受击闪白（0.1 秒，uniform tint 实现） |
| 开枪音效 | 占位音 |
| 空枪 | 空枪音效 + HUD 提示「弹药不足」 |
| **破坏方块** | **8–12 个方块颜色粒子（占位）+ 破坏音效（占位）** —— `PRD_v0.3.2` 第 417 行；M2 建粒子后端时交付 |

> **【口径隔离（`TECH_DESIGN_v0.1.1` §W′.1，E-15 / E-16 / `DRIFT-A-01`）】**
> 「命中溅射 3–5 个」与「**破坏粒子 8–12 个**」是**两个不同的量**，长期被混为一谈，
> 是 MVP 破坏粒子数量被写错的直接原因。PRD 只规定了**破坏粒子 = 8–12**；
> 命中溅射 PRD 未给数量，3–5 作为工程缺省保留但不得反向引用。

**曳光与粒子属表现层，不修改任何逻辑状态。** 生命周期由渲染侧对象管理，**不进入 `EntityManager`** —— 它们不参与物理、AI、存档。

---

## N. Save System

### N.1 目录结构（冻结）

```
<saveRoot>/SkyIsland/saves/<世界名>/
├── level.json              世界元数据（版本、Seed、时间、天数、资源核心状态）
├── player.json             玩家状态（位置、生命、背包、快捷栏）
├── level.json.bak          上一版元数据（读取失败时回退）
├── player.json.bak         上一版玩家数据
└── chunks/
    ├── c.-1.0.bin          区块 (cx=-1, cz=0) 的改动增量
    ├── c.0.0.bin
    └── c.3.0.bin
```

**`<saveRoot>` 默认** = `%APPDATA%/SkyIsland/saves/`。
**便携模式**：若可执行文件同目录存在 `portable.txt`，改用 `./saves/`。零成本，便于打包分发后免安装试玩。

**元数据拆分理由**：`level.json` 与 `player.json` 分开，使"频繁变化的玩家状态"与"低频变化的世界元数据"各自独立原子写入。玩家死亡/重生频繁时只需重写 `player.json`（几百字节），不必重写整个世界元数据。

### N.2 level.json

```json
{
  "saveVersion": 1,
  "worldGenerationVersion": 1,
  "worldName": "新的世界",
  "worldSeed": -4127837465912038451,
  "createdAtMillis": 1758260000000,
  "savedAtMillis": 1758261500000,
  "worldTimeSeconds": 720.0,
  "dayPhase": "NIGHT",
  "dayCount": 2,
  "dayFactor": 0.15,
  "settingsSnapshot": {
    "renderDistance": 6, "fov": 70, "mouseSensitivity": 1.0, "vsync": false,
    "invertMouseY": false, "masterVolume": 80, "sfxVolume": 80,
    "dropItemsOnDeath": true, "showFps": false, "brightness": 50
  },
  "resourceCores": [
    { "x": 48, "y": 63, "z": 0, "accumulatedSeconds": 62.5,
      "oreBlockStableId": "skyisland:iron_ore" }
  ]
}
```

**字段设计要点：**

- **`saveVersion` 与 `worldGenerationVersion` 必须分开**（PRD 12.3 明确要求"岛屿生成版本"）。二者变更后果不同：
  - `saveVersion` 变 → 存档**格式**变更 → 需迁移代码或拒绝加载。
  - `worldGenerationVersion` 变 → **世界生成算法**变更 → 增量方块数据仍可读，但"未被改动过的区域"重新生成后会与玩家记忆中的地形不同。此时**必须警告"地形生成已变更，未探索区域可能与原世界不同"**，而不是静默加载。
- **`settingsSnapshot` 存在世界存档内**：PRD 6.5 的设置是全局的，但世界内记录一份快照。**读档后以快照为准还是以当前全局设置为准？** 本设计取**快照优先**（理由：玩家可能为某个卡顿的世界单独压低视距）。**此为工程缺省，建议在 PRD v0.3.2 明确。**

### N.3 player.json

```json
{
  "saveVersion": 1,
  "x": 0.5, "y": 64.0, "z": 0.5,
  "yaw": 0.0, "pitch": 0.0,
  "health": 20,
  "fallDistance": 0.0,
  "lastSafePosition": { "x": 0.5, "y": 64.0, "z": 0.5 },
  "selectedHotbarSlot": 0,
  "inventory": [
    { "slot": 0, "item": "skyisland:pistol",      "count": 1,  "magazineAmmo": 12 },
    { "slot": 1, "item": "skyisland:pistol_ammo", "count": 24 },
    { "slot": 2, "item": "skyisland:planks",      "count": 32 },
    { "slot": 3, "item": "skyisland:torch",       "count": 8  },
    { "slot": 4, "item": "skyisland:iron_ingot",  "count": 4  },
    { "slot": 5, "item": "skyisland:coal",        "count": 8  }
  ]
}
```

**两个易漏字段：**

- **`inventory` 用稀疏数组 + 显式 `slot` 索引**，**不依赖数组顺序**。否则一旦将来改动背包布局，旧存档物品会串位。
- **手枪需要额外记录 `magazineAmmo`**（弹匣内弹药）与 `count`（背包堆叠数）。PRD 12.3 的必存字段清单没有单列弹匣，但"背包"作为整体包含它。**若只存 `count`，读档后玩家的弹匣会丢失。**

### N.4 区块二进制格式（稀疏增量）

**核心洞察：世界生成是 Seed 确定的纯函数，因此只需存"与生成结果不同的那些方块"。**

```
偏移  类型      字段
0     char[4]   "SKIC"                 魔数
4     uint16    formatVersion          = 1
6     int32     cx
10    int32     cz
14    int32     worldGenerationVersion
18    uint16    paletteSize
20    ...       paletteSize × { uint16 strLen; byte[strLen] utf8 }   // 稳定字符串 ID 表
...   int32     deltaCount                 改动方块数
...   ...       deltaCount × { uint16 index; uint16 paletteIndex }   按 index 升序
末尾  uint32    crc32                      覆盖全部前置字节
```

**这个格式的关键属性：**

1. **体积极小。** 玩家一次典型游戏（20–25 分钟）约改动几百个方块，每条约 4 字节 → 500 条 = 2 KB。相比之下存完整区块是 64 KB → **节省约 30 倍**。
2. **自包含。** palette 让文件**不依赖方块注册表即可解析**（每区块用到的方块种类通常 < 10）。
3. **按 `index` 升序**，使加载时可用二分查找，且文件内容稳定可 diff。
4. **`index` 用 `uint16`** 恰好覆盖 0–32767 的区块体积（§D.5 的 `index()` 值域）。

> ⚠️ **一条必须纠正的错误做法**：**`blockId` 绝不能存运行时 ID。**
> §0.2 / PRD 12.3 明确禁止把运行时数值 ID 落盘。运行时 ID 由注册表按字典序分配，**只要新增一个方块（M4 会新增 5 个），所有大于它的 ID 都会位移**，旧存档里存的 ID 会指向错误的方块 —— 表现为"读档后石头变成玻璃"。
>
> **正确做法（已采用）**：文件头内嵌 `palette`（本次用到的稳定字符串 ID 表），增量里存 `uint16 paletteIndex`。
>
> | 备选 | 评价 |
> |------|------|
> | **A. 内嵌 palette（采用）** | 自包含、体积小、不依赖注册表顺序 |
> | B. 每条增量后跟完整字符串 | ❌ 体积膨胀（每方块 20+ 字节），浪费 |
>
> **加载时**：把 palette 中每个字符串经 `BlockRegistry.resolveByStableId(String)` 映射为 runtimeId。**若遇到未知 ID**（旧存档有、当前注册表没有的方块），PRD 5.1.1 要求"有明确降级行为"。本设计采用：**替换为空气 + 记录一条 `unknownBlock` 警告日志（含坐标与 stableId）**，并汇总到加载结束提示中。**不能静默丢弃** —— 那会让玩家看到"我盖的房子凭空消失"却不知原因。

### N.5 原子写入（临时文件 + flush + atomic rename + `.bak` + CRC32）

```java
public final class AtomicFileWriter {
    public static void write(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Path bak = target.resolveSibling(target.getFileName() + ".bak");

        try (FileChannel ch = FileChannel.open(tmp, CREATE, WRITE, TRUNCATE_EXISTING)) {
            ch.write(ByteBuffer.wrap(data));
            ch.force(true);                                   // ★ 必须：强制刷盘
        }
        if (Files.exists(target)) Files.move(target, bak, REPLACE_EXISTING);
        Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE);
    }
}
```

**为什么必须 `force(true)`**：`rename` 只是元数据操作。**若文件内容仍在页缓存未落盘，一次断电会导致重命名后的文件内容为空或半截** —— 少了这一步的"原子写入"是**假原子**。

**为什么还要保留 `.bak`**：`ATOMIC_MOVE` 保证"要么旧文件、要么新文件"，但**不保证新文件可解析**（可能内容错误或被截断）。`.bak` 是第二道防线。

### N.6 读取流程与容错

```
1. 读 level.json
   ├─ 不存在            → 新世界流程
   ├─ 解析失败          → 尝试 level.json.bak
   │                       ├─ 成功 → 提示「主存档损坏，已从备份恢复」
   │                       └─ 失败 → 提示「存档无法读取」，提供「放弃并新建」/「退出」
   └─ saveVersion 检查
        ├─ == 当前        → 正常加载
        ├─ <  当前        → 走迁移链（MVP 只有 v1，无迁移代码，但★迁移入口必须预留）
        └─ >  当前        → 拒绝加载，提示「存档由更新版本创建」
2. 逐区块读 chunks/c.<cx>.<cz>.bin
   ├─ CRC32 校验失败 → 跳过该文件，记 WARN，该区块回到"生成态"
   │                    （玩家在该区块的改动丢失，但世界仍可玩）
   └─ palette 出现未知 stableId → 替换为空气 + 记 WARN（见 N.4）
3. 世界生成：按 worldSeed 生成所有需要的区块
4. 应用增量：按 index 写回区块（cause = SAVE_LOAD）
5. 读 player.json → 恢复玩家
   ├─ 位置若在实体内（异常存档）→ 走 §N.7 的重置逻辑
   └─ worldGenerationVersion 与当前不符 → 提示「地形生成已变更，未探索区域可能与原世界不同」
6. 恢复 resourceCores 的 accumulatedSeconds
```

**"存档损坏时降级而非崩溃"是硬要求。** 玩家丢一个区块的改动可以接受；整个存档打不开、程序崩溃不可接受。

### N.7 玩家位置合法性校验

```java
Vec3d sanitizePlayerPosition(Vec3d pos, World world) {
    if (isValidStanding(pos, world)) return pos;
    // PRD 5.3.1：沿 y = 64 平面螺旋搜索，半径 ≤ 16
    Optional<Vec3d> found = spiralSearch(world, floor(pos.x), SURFACE, floor(pos.z), 16);
    if (found.isPresent()) return found.get();
    // PRD 5.3.1 兜底：生成 1 格临时木质地台
    world.placeBlock(0, WORLD_SURFACE_BLOCK_Y, 0, planksDef, MutationCause.SAVE_LOAD);
    return new Vec3d(0.5, WORLD_SURFACE_FEET_Y, 0.5);
}
```

**`SAVE_LOAD` cause 在此处是必需的**（§H.2）：它绕过"相邻支撑"校验，因为这块地台是凭空生成的兜底措施。

**`spiralSearch` 的坐标基准（本节最易写错的地方）**：PRD 说"沿 y = 64 平面螺旋"。按 §D.6 的裁定，`y = 64` 是**顶面标高**，因此：
- 搜索时检查的**方块层是 `y = 63`**（`WORLD_SURFACE_BLOCK_Y`）
- 返回的站立位置 `feetY = 64.0`（`WORLD_SURFACE_FEET_Y`）

**这个 +1/−1 的转换必须写注释。**

### N.8 保存触发时机（PRD 12.3）

| 触发 | 时机 | 是否阻塞主线程 |
|------|------|----------------|
| 退出世界 | 关闭窗口 / 返回主菜单之前 | ✅ 阻塞（必须完成，否则丢档） |
| 手动保存 | Esc 暂停菜单点「保存」 | ✅ 阻塞（MVP；暂停时阻塞无观感问题） |
| 黎明结算 | `dayPhase` 从 NIGHT 转到 DAWN 的瞬间 | ✅ 阻塞（此时世界时间暂停，天然安全点） |
| 自动保存 | MVP **不做** | — |

**"黎明结算保存"是免费得到的安全点**：PRD 4.4 的黎明阶段本来就有"结算新一天"的自然停顿，利用它保存不需要额外时机判断。**MVP 不做周期性自动保存**，因为没有任何时机像黎明一样天然安全。列入 M5。

### N.9 保存范围（PRD 明令：禁止保存整个世界）

| 存 | 不存 |
|----|------|
| `level.json`（版本、Seed、时间、天数、资源核心状态） | ❌ 未被修改的区块的完整方块数据 |
| `player.json`（位置、生命、背包、快捷栏、弹匣） | ❌ 世界生成即可重算的区块 |
| `chunks/*.bin`（**仅被玩家或系统永久修改过的方块**） | ❌ 光照缓存（可由火把 + dayFactor 重算） |
| — | ❌ 网格数据（可由方块重算） |

```java
// 只写 saveDirty 的区块
for (Chunk c : store.loaded()) {
    if (!c.isSaveDirty()) continue;
    byte[] data = ChunkSerializer.serialize(c);          // 稀疏增量（N.4）
    AtomicFileWriter.write(chunkPath(c.cx(), c.cz()), data);
    c.clearSaveDirty();
}
```

**优先策略是「Seed + Modified Chunks」**（用户明确要求）：世界由 Seed 纯函数生成，因此只记录被玩家挖过 / 放过 / 被系统永久修改的方块。**这就是 `saveDirty` 必须与世界生成解耦的原因**（§E.3）。

---

## O. 资源目录

### O.1 目录结构（冻结）

```
src/main/resources/
├── textures/
│   └── blocks/          方块纹理（16×16 PNG，打包为 GL_TEXTURE_2D_ARRAY）
├── shaders/
│   ├── chunk.vert
│   ├── chunk.frag
│   ├── cross.vert / cross.frag      火把等 CROSS 渲染
│   ├── entity.vert / entity.frag
│   └── hud.vert / hud.frag
├── sounds/              占位音效（MVP）
│   ├── dig.ogg
│   ├── place.ogg
│   ├── gunshot.ogg
│   └── hit.ogg
├── data/
│   ├── manifest.json        显式资源清单（见 O.3）
│   ├── blocks.json
│   ├── items.json
│   ├── recipes.json
│   ├── guns.json
│   ├── monsters.json
│   ├── threat_levels.json
│   └── starting_loadout.json
└── config/
    └── default_settings.json   设置项默认值（PRD 6.5）
```

### O.2 定位方式（jar 内可用的唯一方式）

```java
public final class ResourceManager {
    private static final ClassLoader LOADER = ResourceManager.class.getClassLoader();

    public InputStream open(String classpathPath) throws IOException;   // "assets/..." 或 "data/..."
    public Optional<InputStream> openIfPresent(String path);
    public List<String> readManifestEntries(String category);
}
```

**必须用 `ClassLoader.getResourceAsStream` 而非 `new File(path)`。** Maven Shade 产出的 fat jar 里资源位于 jar 内部，`java.io.File` **无法访问**。用 `new File("data/blocks.json")` 的代码**在 IDE 里能跑、打成 jar 后必然失败** —— 这是本项目最可能在 M5 才暴露的一类问题。**从第一天就用 ClassLoader。**

### O.3 为什么需要 `manifest.json`

**`ClassLoader` 无法遍历 jar 内的目录**（`getResources("data/")` 在不同环境行为不一致，且在 jar 内不返回文件列表）。因此资源加载**不做运行时目录扫描**，改为**显式清单**：

```json
{
  "formatVersion": 1,
  "data": ["data/blocks.json", "data/items.json", "data/recipes.json",
           "data/guns.json", "data/monsters.json", "data/threat_levels.json",
           "data/starting_loadout.json"],
  "loadOrder": ["blocks", "items", "recipes", "guns", "monsters", "threat_levels", "starting_loadout"],
  "shaders": ["shaders/chunk.vert", "shaders/chunk.frag", "..."],
  "textures": ["textures/blocks/stone.png", "..."]
}
```

**`loadOrder` 是必需的**，因为存在依赖：`items.json` 里 `blockItem` 引用 `blocks.json` 的 stableId；`recipes.json` 引用 items；`guns.json` 引用 ammo item。**加载顺序错误会得到"引用不存在的 ID"错误**。

**显式清单的额外好处**：把"哪些文件是必要的"变成显式声明，缺失文件在启动时立刻暴露（而不是玩家走到某功能才崩）。

### O.4 数据驱动的边界（重要约束）

> **规则：数值与内容数据配置化，核心算法仍然 Java 实现。**

| 应当 JSON 化 | **不应当** JSON 化 |
|--------------|---------------------|
| 方块属性（hardness、solid、掉落表） | 碰撞求解算法 |
| 物品属性（maxStack、category） | DDA 算法 |
| 配方（产出、材料、数量） | 面剔除判定逻辑 |
| 枪械数值（伤害、弹匣、射速、射程） | 伤害计算公式 |
| 怪物数值（HP、伤害、速度） | AI 7 步的状态机实现 |
| 威胁等级分段表（TL1–TL6） | 昼夜插值曲线 |
| 初始物资清单 | — |

**为什么要有这条边界**：把"核心算法"也 JSON 化的典型表现是引入一个"公式字符串"字段并自己解析它 —— 这等于手写一个表达式引擎，**既失去了 Java 的类型安全与调试能力，又没有获得真正的灵活性**（因为改公式仍需重新理解那套自定义语法）。数值配置化才是真正的收益（策划可改、不用重编译）。

---

## P. 推荐包结构

根据当前工程实际情况调整（当前无既存代码，因此按推荐结构建立）。**保持中等粒度：不要一个包几十个类，也不要每个类一个包。**

```
com.skyisland
├── game/            SkyIslandGame（入口）、GameLoop、GameState、TimeSystem、DevConfig、Version
├── input/           InputState、InputMapper、PlayerIntent、KeyBindings
├── world/           World（★ mutation 唯一入口）、WorldMutator、MutationCause、BlockChange、
│                    BlockChangeListener、WorldTime、DayPhase、LightEngine、ChunkStore
│   ├── chunk/       Chunk、ChunkPos、ChunkIndex、ChunkState、ChunkSerializer、MeshRebuildQueue
│   ├── block/       BlockDefinition、BlockRegistry、BlockFace、RenderType、BlockPos、CollisionWorldImpl
│   └── gen/         WorldGenerator、IslandGenerator、Structures（小屋）、SeedRandom、ResourceCoreSpec
├── render/          Renderer、Camera、Frustum、RenderContext、Window
│   ├── mesh/        ChunkMesh、SubMesh、ChunkMesher、VertexWriter、MeshData、MeshUploader
│   ├── shader/      ShaderProgram、ShaderSources、UniformCache
│   ├── texture/     TextureArray、TextureArrayBuilder、ProceduralTextureFallback
│   └── ui/          HudRenderer、CrosshairRenderer、DebugOverlay      （★ 渲染侧的 UI 绘制）
├── physics/         AABB、PhysicsBody、CollisionResolver、PlayerPhysics、FallDamage、
│                    BlockRaycaster、BlockRayHit、VoxelDDA
├── entity/          Entity、EntityKind、EntityManager、EntityContext、
│                    PlayerEntity、MeleeMonsterEntity、ItemEntity
│   └── ai/          MeleeMonsterAI、AIState
├── item/            ItemDefinition、ItemRegistry、ItemStack、Inventory、Hotbar、
│                    Recipe、RecipeRegistry、RecipeStatus、CraftingService
├── combat/          GunDefinition、GunInstance、GunState、Hitscan、HitResult、
│                    DamageSource、DamageKind、DamageCalculator、Tracer
├── save/            SaveManager、SaveFormat、AtomicFileWriter、Crc32、LevelMeta、PlayerState、
│                    ChunkSerializer、SaveResult
├── data/            JsonLoader、DataRegistry、BlockDefRecord、ItemDefRecord、RecipeDefRecord、
│                    GunDefRecord、MonsterDefRecord、ThreatLevelDefRecord、ManifestRecord
│                    （★ 唯一允许 import com.google.gson 的包）
├── ui/              Screen、InventoryScreen、CraftingScreen、PauseMenu、MainMenu、
│                    SettingsScreen、Tooltip、ChatMessage（即时提示）
└── util/            Log、MathUtil、Coords、RingBuffer、Timer、Assert、ResourceManager、Strings
```

**关键包边界（对应 §B.2 的禁止依赖）：**

| 包 | 可以 import | **禁止 import** |
|----|-------------|-----------------|
| `game` | 全部 | — |
| `render` | `world`、`entity`、`util`、JOML、LWJGL | ❌ `world.chunk` 的写方法（只能读 `getBlock`） |
| `world`、`entity`、`physics`、`combat`、`item` | `util`、JOML | ❌ `render`、❌ `org.lwjgl.opengl.*`、❌ `org.lwjgl.glfw.*` |
| `input` | `util`、LWJGL GLFW | ❌ `render`（Input 不直接控制 Renderer） |
| `ui` | `item`、`combat`、`data`、`render.ui` | ❌ `world.chunk` 内部数组、❌ 直接改方块（必须走 `World`） |
| `data` | `util`、Gson | ❌ 其他所有业务包（data 只做"定义 → record"的解析） |
| `save` | `world`、`entity`、`item`、`util`、Gson | ❌ `render` |

> **`ui` 包与 `render.ui` 包的区别**：`ui` 是**界面逻辑**（哪个界面打开、按钮状态、拖拽中的物品），`render.ui` 是**界面绘制**。这个分离保证"UI 逻辑可测试（无 GL 依赖）"。

**落地手段**：除代码评审核对外，M1 起可引入 `maven-enforcer-plugin` 的 `bannedDependencies` 规则禁止 `world` 包引入 `lwjgl-opengl`（模块化后可改用 JPMS `module-info.java` 强制）。**MVP 阶段先用评审 + 包注释约束，不引入工具。**

---

## Q. 错误处理与日志

### Q.1 日志分级与门面

```java
public final class Log {                       // 唯一对外日志入口（§A.5）
    public static void info (String fmt, Object... args);
    public static void warn (String fmt, Object... args);
    public static void error(String fmt, Object... args);
    public static void error(String msg, Throwable t);
    public static void debug(String fmt, Object... args);   // 仅开发构建
}
```

| 级别 | 用途 | 示例 |
|------|------|------|
| `INFO` | 正常流程与生命周期 | 启动、GL 上下文创建成功、世界加载完成、存档写入成功 |
| `WARN` | 可继续但需注意 | JVM native-access 告警、未知方块 ID、CRC 校验失败跳过区块、帧间隔被钳制、区块重建超阈值 |
| `ERROR` | 功能失败 | 存档写入失败、资源加载失败、着色器编译失败、GL 上下文创建失败 |
| `DEBUG` | 仅开发 | 每帧坐标、网格重建耗时、AI 状态切换 |

### Q.2 输出目标

| 构建类型 | 控制台 | 文件 |
|----------|:------:|------|
| 开发（`mvn exec:java` / IDE） | ✅ | `./logs/skyisland-YYYYMMDD.log` |
| 发布（fat jar） | ✅ | `%APPDATA%/SkyIsland/logs/skyisland-YYYYMMDD.log` |

**M0 必须支持写文件** —— M0 报告需要**完整捕获 JVM 与 GL 的告警清单**，而控制台输出在进程被终止时容易丢失。

### Q.3 M0 起必须记录的环境信息（启动横幅）

```
=== SkyIsland 启动 ===
[INFO] Version            : 0.1.0-M0
[INFO] Java version       : 25 (build 25+36-3489)
[INFO] Java vendor        : Oracle Corporation
[INFO] JVM name/version   : OpenJDK 64-Bit Server VM / 25+36-3489
[INFO] OS                 : Windows 11 10.0 (amd64)
[INFO] Available processors: 16
[INFO] Max heap           : 4096 MB
[INFO] Working dir        : D:\...\skyisland
[INFO] active JVM args    : <运行时真实生效的参数，非文档值>   ★
--- 图形 ---
[INFO] LWJGL version      : 3.4.3
[INFO] LWJGL build type   : <LWJGL 自报的 build 类型>
[INFO] GLFW version       : <glfwGetVersionString()>          ★ 运行时实测
[INFO] GLFW platform      : <Win32/WGL>
[INFO] OpenGL version     : <GL_VERSION>                      ★ 运行时实测
[INFO] OpenGL vendor      : <GL_VENDOR>                       ★ 运行时实测
[INFO] OpenGL renderer    : <GL_RENDERER>                     ★ 运行时实测（不得由系统设备名推断）
[INFO] GLSL version       : <GL_SHADING_LANGUAGE_VERSION>     ★ 运行时实测
[INFO] GL context flags   : Core=3.3 ForwardCompatible Debug=<on/off>
[INFO] Framebuffer size   : <fbW> × <fbH>                     ★ 与窗口尺寸区分（高 DPI）
[INFO] Window size        : <winW> × <winH>
[INFO] VSync              : OFF
[WARN] <JVM native access / Unsafe / FFM 告警原文逐条记录>     ★
[WARN] <GL debug message 逐条记录>
```

**`active JVM args` 必须是运行时真实值**：通过 `ManagementFactory.getRuntimeMXBean().getInputArguments()` 读取，而不是打印文档里写的期望值。**只有这样才能证明"最小参数集"是真的（§22）。**

**GLFW / OpenGL 版本必须由运行时读取**（`glfwGetVersionString()`、`glGetString`），**不得由 Windows 设备管理器名称或 LWJGL 版本推断**。用户明确要求，且这是唯一可信来源。

### Q.4 OpenGL Debug Context（开发版开启）

```java
// 仅开发构建
glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
// 创建上下文后
if (caps.OpenGL43 || caps.GL_KHR_debug) {
    glEnable(GL_DEBUG_OUTPUT);
    glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
    glDebugMessageCallback((source, type, id, severity, len, msg, userParam) -> {
        String s = memUTF8(msg, len);
        switch (severity) {
            case GL_DEBUG_SEVERITY_HIGH   -> Log.error("[GL] %s", s);
            case GL_DEBUG_SEVERITY_MEDIUM -> Log.warn ("[GL] %s", s);
            default                       -> Log.debug("[GL] %s", s);
        }
    }, 0);
}
```

**Debug Context 会带来性能开销，因此仅在开发构建开启**（一个常量 `DevConfig.DEBUG_CONTEXT`）。**M0 报告需记录实际 GL debug message 数量**：0 条为最理想。

### Q.5 异常处理原则

| 场景 | 处理 |
|------|------|
| **初始化失败**（GL 上下文、着色器编译、数据加载） | **快速失败（fail-fast）**：记 `ERROR` + 完整堆栈 + 退出码 1。半初始化的游戏状态无法安全运行 |
| **单帧逻辑异常** | 捕获并记 `ERROR`，**结束本次逻辑步**（不中断整个游戏），累计异常计数；超过阈值（如 100）则快速失败 |
| **资源加载失败（非必需资源）** | 记 `WARN` + 使用占位资源（如程序化生成纹理），继续运行 |
| **资源加载失败（必需，如 blocks.json）** | **快速失败**：缺它整个游戏无法运行 |
| **存档写入失败** | 记 `ERROR` + 通知玩家（HUD 提示"保存失败"），**不静默忽略** |
| **存档读取失败** | 走 §N.6 的降级链（`.bak` → 提示 → 用户选择） |
| **未知方块 stableId** | 替换为空气 + 记 `WARN`（§N.4） |

**"快速失败"与"降级继续"的分界原则**：**数据完整性失败的降级要谨慎，运行时可恢复的失败要宽容。** 缺 `blocks.json` 时"降级继续"只会产生一个跑不起来的游戏；而 CRC 校验失败时"拒绝加载整个存档"会让玩家丢掉能救的部分。

### Q.6 退出码

| 码 | 含义 |
|----|------|
| `0` | 正常退出 |
| `1` | 初始化失败（GL / 数据 / 配置） |
| `2` | 参数错误（未知命令行参数） |
| `3` | 运行时致命异常 |

---

## R. 设计审计（本轮自审）

按用户指定的 10 项逐条自审。**审计对象：本文档 A–Q 节全部内容。**

| # | 审计项 | 结论 | 证据 / 处置 |
|---|--------|------|-------------|
| 1 | 是否出现 Renderer 修改 World？ | **✅ 无** | §B.2 第 1 条明文禁止并列为评审检查项；§B.3 用"包边界 + 双字段隔离"落地；§C.1 要求 `render(alpha)` 只读；§K.1 用 `renderX/Y/Z` 与 `x/y/z` 严格分离，避免"插值写回逻辑位置"这一最常见的越界形式 |
| 2 | 是否依赖 enum ordinal 做存档 ID？ | **✅ 无** | §0.2 立为硬口径；§F.1 双层 ID（stableId 入盘、runtimeId 仅内存）；§F.2 按字典序确定性分配；§N.4 用**内嵌 palette 存 stableId**，明确否决"存 runtimeId"并给出"读档后石头变玻璃"的后果；§F.6 连 `faceShade` 数组都禁用 `ordinal()` 改用显式 `faceIndex` |
| 3 | 是否大量使用 Java Block Object？ | **✅ 无** | §E.1 明确否决 `Block[][][]`（给出"数 MB + GC 压力"的量化理由），采用 `short[]`（64 KB/区块）；`BlockDefinition` 是 **immutable record**，仅注册表持有，**每方块不持有对象引用** |
| 4 | 是否每帧重建所有 Chunk？ | **✅ 无** | §G.5 明确禁止，采用 `block mutation → mark dirty → MeshRebuildQueue → 限量上传`；`MAX_CHUNK_REBUILDS_PER_FRAME = 4`（M1 实测调整）；§G.5 还规定"旧网格继续显示直到新网格就绪"（原子替换），避免闪烁 |
| 5 | 是否提前实现 Alpha？ | **✅ 无** | §F.3 方块表只含 15 项（MVP）；§G.1 明确不做 Greedy Meshing；§K.1 明确不做 ECS；§L.2 明确**不注册**铜/金/晶体/小麦/面包等 Alpha 物品；§M.1 明确只实现 Pistol，Smg/Rifle/Shotgun **仅预留数据结构不写逻辑**；§H.2 的 `EXPLOSION` / `SAND_GRAVITY` cause 仅**预留枚举值**，无实现 |
| 6 | 是否存在负坐标错误风险？ | **已识别并封堵** | §D.4 是全工程唯一坐标口径来源，规定必须用 `Math.floor` / `Math.floorDiv` / `Math.floorMod`，并给出**三行反例表**（`bx/16`、`bx%16`、`(int)x` 各自的具体后果）；冻结 **8 行用例表**（含用户指定的 `-1 → cx=-1, lx=15`）并写成 `CoordinateMathTest` 固化为回归保护；§E.5 的 `ChunkStore.key()` 用 `cz & 0xFFFFFFFFL` 处理负 cz；§J.2 DDA 用 `Math.floor` 取起始格 |
| 7 | 是否 Save System 保存整个静态世界？ | **✅ 无** | §N.9 立表明示"存 / 不存"；采用**稀疏增量**（只存与生成态不同的方块，约 2 KB/区块 vs 64 KB）；§E.3 **强制 `saveDirty` 与世界生成解耦**（`WORLDGEN`/`SAVE_LOAD` 不标 `saveDirty`）；§N.9 明确不存光照缓存与网格数据（可重算）。策略为「Seed + Modified Chunks」 |
| 8 | 是否 Entity 架构过度复杂？ | **✅ 无** | §K.1 最低结构仅 `Entity` + 3 个子类；§K.6 用 4 条论点明确论证**不做 ECS**（规模、调试、重构时机、需求真实度）；§K.5 明确 MVP **不做空间划分**（给出"< 50 实体线性扫描"的量化依据）；无 Component / System 注册表 |
| 9 | 是否引入不必要框架？ | **✅ 无** | 依赖总数 **4 个**：LWJGL 3.4.3（必需）、JOML 1.10.9（数学）、Gson 2.14.0（JSON，§A.3 有完整选型论证 + 使用边界约束到单个包）、JUnit 6.1.3（test scope）。日志用 **JDK 内置 JUL**（§A.5，附 4 个备选的否决理由）；无 ECS 框架、无 DI 框架、无 lodash 式工具库、无 Gradle |
| 10 | 是否与 PRD v0.3.1 冲突？ | **发现 2 处 PRD 内部口径问题，0 处技术方案违背产品规则** | 见下 |

### R.1 与 PRD v0.3.1 的逐项核对结果

| 核对项 | PRD 依据 | 本文档 | 一致 |
|--------|----------|--------|:----:|
| 视距 6 区块 = 96 格 | 4.1 | §D.3 | ✅ |
| Chunk 16×16×128，y ∈ [0,127] | M1 / 4.1 | §D.3 / §E.2 | ✅ |
| 可放置 y ∈ [1,127] | 5.2 | §H.6 | ✅ |
| 虚空致死 y < −8 | 4.5 | §I.1 `VOID_KILL_Y = -8.0` | ✅ |
| 生命 20、MVP 无饥饿 | 5.3 | §I.1（**无饥饿字段**） | ✅ |
| 坠落伤害 `max(0, floor(Δ-3))` | 5.3 | §I.6 | ✅ |
| lastSafePosition 需稳定站立 ≥ 0.5 s | 5.3.1 | §I.6 | ✅ |
| 重生点 (0,64,0) + 螺旋 ≤16 + 临时地台 | 5.3.1 | §I.4 / §N.7 | ✅（坐标语义 +1 见 D1） |
| 挖掘 reach 5.0 | 5.2 | §J.1 | ✅ |
| 放置相邻支撑（六面之一） | 5.2 / v0.3 新增 | §H.6 | ✅ |
| 方块 13 玩家常规 + 1 系统 | 5.1 / 8 / 9.3 | §F.3（14 + 空气 = 15） | ✅ |
| `resource_core` 不可破坏/拾取/放置/无掉落/有碰撞/有渲染/参与存档 | 5.1.1 | §F.5 + 不变量断言 | ✅ |
| 方块稳定 ID `skyisland:*` | 5.1 / 12.3 | §F.1 | ✅ |
| 手枪 伤害 8 / 弹匣 12 / 4.0 发秒 / 32 格 / 换弹 1.2 s | 5.4.1 / 5.4.3 | §M.1 / §M.4 | ✅ |
| Hitscan 取最近（禁隔墙命中） | 12.2 | §M.2 | ✅ |
| 距离衰减 ×0.9/格，最低 20% | 5.4.3 | §M.4 | ✅ |
| 瞄准 FOV 70→45、移速 60% | 5.4.3 | §M.6 | ✅ |
| 换弹可被移动打断；满弹匣换弹余弹回背包 | 5.4.3 | §M.5 | ✅ |
| 近战怪 HP20 / 伤害4 / 速度2.0 | 5.5.1 | §K.3 | ✅ |
| AI 7 步；禁动态/跳跃/破坏方块寻路 | 5.5.2 | §K.3 | ✅ |
| 怪物坠虚空立即移除无掉落 | 5.5.2 | §K.3 | ✅ |
| TL1 上限 8 / 间隔 8 s / 0% 远程 / 0% 大型 | 5.5.4 | §C.2 第 9 步（数值由 JSON 驱动） | ✅ |
| 掉落物磁吸 2 格 / 5 分钟 / 虚空销毁 | 5.2 | §K.4 | ✅ |
| 背包 9 + 27 = 36；堆叠 64/128/1 | 5.6.1 | §L.3 | ✅ |
| MVP 7 条配方（R11 = 铁锭×1+火药×1） | 5.6.2 | §L.5 | ✅ |
| 初始物资 6 项（无面包/小麦种） | 5.7.1 | §L.5 | ✅ |
| 昼夜 20 分钟（1/11/1/7）、夜晚亮度 15% | 4.4 | §H.5 | ✅ |
| 火把半径 6 线性衰减，不做全局传播 | 4.4 | §H.5 | ✅ |
| 资源核心 180 s / 1 格 / 5×5 / 不离线 | 4.6 | §N.2 `resourceCores` 持久化 | ✅ |
| Seed 可见可输入；MVP 只作用主岛+石矿岛 | 4.7 | §N.2 `worldSeed` | ✅ |
| 存档 saveVersion + 岛屿生成版本 | 12.3 | §N.2（拆为 `saveVersion` / `worldGenerationVersion`） | ✅ |
| 原子写入 + `.bak` + CRC32 | 12.3 | §N.4 / §N.5 | ✅ |
| 界面打开时世界继续运行 | 6.2 / 12.3 | §M.5 第 4 点 | ✅ |
| 暂停时世界暂停 | 12.3 | §C.5 / §N.8 | ✅ |
| 数据驱动原则 | 12.4 | §O.4（附边界：算法不 JSON 化） | ✅ |
| 不依赖 Unity/Unreal | 12.1 | §A 技术栈 | ✅ |
| 禁 Gradle | 12.6 / Q7 | §A.1 | ✅ |
| 联机仅为架构预留，不实现 | 14.3 | §B.3 接缝（`CollisionWorld` 接口等） | ✅ |

### R.2 审计发现的 PRD 内部口径问题（**不是技术方案违背产品规则**）

| 编号 | 问题 | 性质 | 处置 |
|------|------|------|------|
| **D1** | PRD 4.1 与 5.3.1/5.7/M2 在 y 坐标口径上**差 1 层**（前者按"方块占据坐标"，后者按"顶面标高"） | **PRD 内部不一致** | 已在 §D.6 给出裁定（取"表面块占据 y = 63"，三处证据一侧），**仅需修正 PRD 4.1 的 2 个数字**（57/51 → 56/50）；代码中以 `WORLD_SURFACE_BLOCK_Y` / `WORLD_SURFACE_FEET_Y` 单点常量定义便于翻转；**建议登记 PRD v0.3.2** |
| **D2** | PRD 6.6 的 `Shift 潜行` **未定义任何效果**；数字键 `1–4 切换枪械` 与 6.1 `1–9 高亮当前槽` 冲突 | **PRD 缺口 / 内部不一致** | §I.7 给出工程缺省值（潜行 = 移速 ×0.3；数字键统一为 1–9 选槽），**不改变玩法意图**；**建议登记 PRD v0.3.2** |

**另有 3 处"PRD 未定义、本文档补缺省值"的地方**（均已在正文标注，属正常技术裁量，非冲突）：

| 位置 | 缺省内容 |
|------|----------|
| §K.3 | 近战怪 AI 的具体数值（探测/失去/攻击范围、冷却），PRD 5.5.2 只给 7 步行为 |
| §M.5 第 3 点 | 满弹匣换弹但背包弹药不足时：**完全拒绝** vs 部分填充 |
| §N.2 | 读档后设置以"存档快照"还是"当前全局设置"为准 |

### R.3 审计结论

- **技术方案与 PRD v0.3.1 的产品规则无冲突。** 全部 34 项玩法规则核对一致。
- **发现 2 处 PRD 内部口径问题（D1、D2）**，均已给出裁定与单点常量/缺省值，**不阻塞 M0**（M0 不涉及方块、坐标、存档），但 **D1 必须在 M1 开工前确认**（M1 涉及 Chunk 与坐标）。
- **0 处违反"禁止提前实现 Alpha"**。
- **不引入任何不必要框架**（依赖总数 4，日志用 JDK 内置）。

---

## S. M0–M3 模块启用矩阵

用于防止"提前实现 Alpha"与"里程碑范围蔓延"。**矩阵中未列的模块，一律不得在对应里程碑实现。**

| 模块 / 里程碑 | M0 | M1 | M2 | M3 |
|---------------|:--:|:--:|:--:|:--:|
| Maven 工程 / pom | ✅ | ✅ | ✅ | ✅ |
| `util.Log` + 启动横幅 | ✅ | ✅ | ✅ | ✅ |
| GLFW Window + GL 3.3 Core Context | ✅ | ✅ | ✅ | ✅ |
| GameLoop（固定步长 + accumulator + 插值） | ✅ | ✅ | ✅ | ✅ |
| `FrameStats`（FPS/TPS/mean/median/P95/P99/max/spike） | ✅ | ✅ | ✅ | ✅ |
| Keyboard / Mouse Input（原始状态 + 打印） | ✅ | ✅ | ✅ | ✅ |
| Clean Shutdown | ✅ | ✅ | ✅ | ✅ |
| GL Debug Context + GL 信息实测 | ✅ | ✅ | ✅ | ✅ |
| `input.InputMapper` / `PlayerIntent` | — | ✅ | ✅ | ✅ |
| `render.Camera` / `Frustum` | — | ✅ | ✅ | ✅ |
| `world.chunk`（Chunk / ChunkStore / 索引） | ❌ | ✅ | ✅ | ✅ |
| `world.block`（Registry / Definition） | ❌ | ✅ | ✅ | ✅ |
| `world.gen`（IslandGenerator / 小屋） | ❌ | ✅ | ✅ | ✅ |
| `render.mesh`（ChunkMesher / MeshRebuildQueue） | ❌ | ✅ | ✅ | ✅ |
| `render.texture`（TextureArray） | ❌ | ✅ | ✅ | ✅ |
| `physics`（AABB / CollisionResolver / PlayerPhysics） | ❌ | ✅ | ✅ | ✅ |
| `physics.BlockRaycaster`（DDA，reach 5.0） | ❌ | ✅ | ✅ | ✅ |
| `world.World`（mutation 唯一入口 + 校验） | ❌ | ✅ | ✅ | ✅ |
| `world.LightEngine` | ❌ | ✅ | ✅ | ✅ |
| `save`（基础 Save/Load：Seed + 位置 + 改动方块） | ❌ | ✅ | ✅ | ✅ |
| `entity.Entity` / `EntityManager` | ❌ | ✅ | ✅ | ✅ |
| `entity.PlayerEntity` | ❌ | ✅ | ✅ | ✅ |
| `combat`（GunInstance / Hitscan / DamageCalculator） | ❌ | ❌ | ✅ | ✅ |
| `entity.MeleeMonsterEntity` + `ai.MeleeMonsterAI` | ❌ | ❌ | ✅ | ✅ |
| `entity.ItemEntity`（掉落物） | ❌ | ❌ | ✅ | ✅ |
| `item`（ItemRegistry / Inventory / CraftingService） | ❌ | ❌ | — | ✅ |
| `world.gen.ResourceCoreSpec` + 资源核心再生 | ❌ | ❌ | — | ✅ |
| `world.WorldTime` 昼夜推进 + 刷怪 | ❌ | ❌ | — | ✅ |
| `ui`（HUD / InventoryScreen / CraftingScreen / PauseMenu / MainMenu） | ❌ | ❌ | — | ✅ |
| `save` 扩展（时间 / 天数 / 资源核心状态） | ❌ | ❌ | — | ✅ |
| Greedy Meshing | ❌ | ❌ | ❌ | ❌（仅 M1 门禁失败时的候选） |
| ECS 框架 | ❌ | ❌ | ❌ | ❌ |
| 多线程区块生成 | ❌ | ❌ | ❌ | ❌（M5 优化项） |
| 空间划分（BVH/四叉树） | ❌ | ❌ | ❌ | ❌（M5 候选） |
| Alpha 内容（铜/金/晶体/小麦/面包/3 把枪/3 种怪/4 岛） | ❌ | ❌ | ❌ | ❌ |

> **M0 的边界是本节第一段（8 项），并且仅此而已。** 其余一律属 M1 及以后。

---

## T. 已知风险与缓解

| 编号 | 风险 | 影响 | 缓解 |
|------|------|------|------|
| TR1 | **M1 性能门禁失败**（Iris Xe 集显 + 视距 6 区块达不到 P95 ≤ 16.7 ms） | M1 阻塞 | 已预留三层降级：① 顶点压缩（shade 拆为 byte 属性）② Greedy Meshing ③ AABB y 收缩；PRD 也允许降视距。**按实测数据逐层启用，不预先优化** |
| TR2 | 光照变化导致网格重建过于频繁（因 `aShade` 烘焙进顶点） | 挖放时卡顿 | §G.3 已记录替代方案（光照改为逐顶点 byte 属性 + 片元相乘）；由 `MeshRebuildQueue` 的队列长度指标驱动决策 |
| TR3 | 负坐标 bug 漏到 M1 后期 | 跨区块行为全面异常 | `CoordinateMathTest` 在 M0 即固化 8 行用例（**已包含在 M0 交付范围内**）；§E.5 的 `key()` 处理负 cz |
| TR4 | JDK 25 native-access 告警在 M1 引入更多 native 调用后恶化为错误 | 未来 JDK 版本可能阻断 | M0 实测记录真实告警清单并给出**最小参数集**；不预先写死参数（§22 要求） |
| TR5 | OpenJDK 参考实现（非 Temurin）缺少长期补丁 | M5 发布安全 | §A.4 记录差异与"M5 前重新评估/切换并重跑 M0"的行动项 |
| TR6 | 存档稀疏增量依赖世界生成确定性 | 生成算法调整后地形不一致 | `worldGenerationVersion` 独立于 `saveVersion`；版本不符时**警告而非静默加载**（§N.2 / §N.6） |

---

## U. 后续待办（不在本轮范围）

| 项 | 归属 | 说明 |
|----|------|------|
| `docs/architecture/ADR/` 架构决策记录 | M1 起 | 至少 3 条基础层 ADR（坐标口径、mutation 入口、存档格式） |
| `maven-enforcer` 包依赖禁令自动化 | M1 起 | 把 §P 的包边界从"评审约束"升级为"构建期强制" |
| PRD v0.3.2 微修补（D1、D2 及 3 处缺省值确认） | 建议 M1 前 | 见 §R.2 |
| 设置项 `musicVolume`（Alpha） | M4 | PRD 6.5 已归 Alpha |

---

*本文档为 `TECH_DESIGN v0.1`，覆盖 M0–M3。上游产品口径以 `docs/design/PRD_v0.3.1.md` 为准。*
*开发顺序：**PRD v0.3.1 → TECH_DESIGN v0.1 → M0 Technical Spike → M1**。*
*M0 实测结果见 `docs/testing/M0_REPORT.md`。*
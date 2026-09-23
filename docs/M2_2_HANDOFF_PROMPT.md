# 新会话继续任务：SkyIsland M2.2 UI/UX & Inventory Foundation 收尾

把下面【提示词正文】整段复制到新对话框即可继续。

---

## 提示词正文

继续推进 `F:\minecraftspace` 的 SkyIsland 项目（Java 21 + LWJGL 自研体素引擎，Maven 项目）。当前里程碑 **M2.2 UI/UX & Inventory Foundation**，版本号 `0.3.2-M2_2-UI-INVENTORY`。**大部分代码已落地并提交，剩下的是收尾**：提交剩余改动、写验收报告、跑最终门禁。**完成后停止，不得进入 M3。**

### 一、环境陷阱（不先读这段一定会白干）

1. **Maven 必须用绝对路径**：`D:\software\maven\apache-maven-3.9.9\bin\mvn.cmd -s toolchain/settings.xml`。
   PATH 里的 `mvn` 是 3.6.1 + 指向腾讯镜像（缺 lwjgl 3.4.3），**一定会失败**。
2. **Git 只在 PortableGit 里**：`C:\Users\Ankh\.workbuddy\binaries\PortableGit\versions\1.2.0\mingw64\bin\git.exe`（不在系统 PATH）。提交用 `-c user.name=lead -c user.email=lead@local`。
3. **Bash 工具不可靠**：shim 每次都报 `shell-runtime-bash-env.sh: line 3`，`ls`/`grep`/`tail`/`dirname` 常报 `command not found`。可靠做法是全部用 `node -e` 配 `fs` 做列目录/读文件/字符串过滤；**不要加管道**（`... | tail -25` 会让命令白跑，输出被丢只剩 exit 127）。
4. **`node -e` 里含 `//` 的正则会提前截断**（第一个 `/` 就结束字面量）。要检索就写成 `.js` 文件再用 node 跑，或改用 Grep 工具。
5. **同一文件的两处 Edit 不能放在同一条消息里**（会互相覆盖）。
6. **游戏日志是 GBK 编码**：用 node 读 stdout 文件时中文正则匹配会失效，需先按 GBK 解码。
7. **`CjkFontTest` 会扫全部源码文本（含注释）**并断言字模已覆盖。新增任何中文都会让它变红 —— 这是**预期内的**，收尾时跑 `D:/software/jdk-25/bin/java.exe tools/fontgen/GenCjkFont.java .` 统一重烤即可。**绝不允许靠删中文去"修"它。**

### 二、当前仓库状态（截至交接时）

HEAD = `de35217`，M2.2 已落地 12 个提交：

```
de35217 fix(render): M2.2 全屏压暗单点化（不再压暗 HUD）+ 背包提示改为操作提示
9a5c64e feat(audio): M2.2 UI 音效（open / close / move / denied）+ 接线存在性守卫
7fd5014 fix(ui): M2.2 菜单排版几何 —— 标题/副标题系统性重叠 + 纵向预算 + 背包标题占位
db720a8 chore(m2.2): 版本升 0.3.2-M2_2-UI-INVENTORY + 重烤 CJK 字模 1484→1505
5484493 test(m2.2): 四类新护栏（图标可区分 / 36 格命中 / 配色对比度 / 菜单接线）
e9161f9 feat(game): M2.2 接线 —— 背包输入路由、菜单项真实行为、HUD 分层
f3e3c03 fix(audio): M2.2 补回 AudioManager 产品构造器 + 恢复被整篇重写的接线测试
fa223db feat(render): M2.2 背包界面布局与渲染 pass
eddbc2a feat(save): M2.2 存档持久化 36 格 + v1→v2 槽位迁移
c31e4ff feat(ui): M2.2 菜单中文化 + 三屏重构（继续/新建世界、设置分三类）
1e267a3 feat(player): M2.2 背包扩到 27+9 格 + 鼠标交互语义
04791ca feat(ui): M2.2 视觉语言统一 —— UiTheme/UiMetrics/ItemIcon/SlotRenderer
```

**工作区未提交（9 改 + 3 新）**：

```
 M src/main/java/com/skyisland/game/M1_5UiSelfTest.java        ← 背包三阶段自测（12 条断言 + 截图）
 M src/main/java/com/skyisland/game/M2CombatSelfTest.java      ← 槽位口径修复 + 自证断言
 M src/main/java/com/skyisland/game/SkyIslandGame.java         ← 背包输入接线 / 鼠标换算 / 音效 / 压暗挂点 / startState
 M src/main/java/com/skyisland/render/Screenshot.java          ← 截图 alpha 修复（TYPE_INT_RGB）
 M src/main/java/com/skyisland/render/ui/CjkFont.java          ← 字模（1517 字符）
 M src/main/java/com/skyisland/settings/Action.java            ← INVENTORY 的 "消费方在 M3" 标注更正
 M src/main/java/com/skyisland/ui/Localization.java            ← INV_CURSOR_HINT 接线 + 提示文案
 M src/main/java/com/skyisland/ui/Menus.java
 M src/test/java/com/skyisland/ui/LocalizationTest.java
?? docs/M2_2_HANDOFF_PROMPT.md                              ← 本文件本身（交接产物）
?? docs/testing/M2_2_PLAYTEST_CHECKLIST.md
?? src/test/java/com/skyisland/render/ScreenshotTest.java
?? src/test/java/com/skyisland/ui/DeadLocalizationKeyTest.java
```

发布 jar 已构建：`target/skyisland-0.3.2-M2_2-UI-INVENTORY.jar`（5.7 MB）。最近一次全量单测 **924 / 0 失败 BUILD SUCCESS**。

### 三、剩余工作（4 项，按序做）

**1. 按层提交剩余改动**（一次一个逻辑单元，**只 `git add` 自己那组文件**，避免把并行遗留混进来）。建议分组：
   - 渲染层：`Screenshot.java` + `ScreenshotTest.java`（截图 PNG 原先写成 TYPE_INT_ARGB，alpha≈170–198，任何人打开 PNG 都会看到发灰的界面并以为 UI 坏了；改成 TYPE_INT_RGB 并加断言）
   - 文案层：`Localization.java` + `Menus.java` + `Action.java` + `LocalizationTest.java` + `DeadLocalizationKeyTest.java`（`INV_CURSOR_HINT` 登记了却无人消费 = 玩家唯一的知识来源没画出来；另新增死 key 守卫，把 7 个既有欠债 key 显式登记在白名单里）
   - 自测层：`M1_5UiSelfTest.java` + `M2CombatSelfTest.java` + `SkyIslandGame.java` + `CjkFont.java`
   - 文档层：`M2_2_PLAYTEST_CHECKLIST.md`

**2. 写验收报告 `docs/testing/M2_2_UI_INVENTORY_REPORT.md`**（尚未创建，是本里程碑的主要欠交付物）。文体对齐 `docs/testing/M2_1_MONSTER_VISIBILITY_CLOSURE.md`。

**3. 在最终代码上跑三档门禁**（用发布 jar，不要用旧冻结 jar）：

```
node tmp/run_frozen_gate.js        # 或按脚本注释用 SKYISLAND_JAR 指向 target/ 下的 jar
```
   期望：`gate-m1` 27/0、`gate-ui` 75/0、`gate-m2` 160/0，且 `m2_combat_closure = true`、`ui_selftest_failures = 0`。上次跑分别得到 27/0、75/0、160/0。

**4. 按下面的格式汇报。**

### 四、纪律（这几条是这个项目反复付学费换来的，不许绕过）

- **门禁每次必须从空存档目录跑**。复用 `saveDir` 会读上一轮存档，代价是三门禁**全红**（假红长得像真红，会让人去改没坏的代码）。修法：每次开带时间戳的新目录 `tmp/gate-runs/<stamp>/`，启动前断言为空。
- **不得为调绿改阈值**。性能合同是 1920×1080 / VSync off / P95 ≤ 16.7 ms **且** >50 ms 卡顿 = 0。若出现尖峰，做**对照跑**定位，不调判据。
- **每条新断言都要做反向验证**：注入破坏 → 确认它精确变红并给出可读归因 → 恢复 → 确认全绿 → 全仓 `grep TEMP_REVERSE_VERIFY` 无残留。失败的标准长相是"**断言在失败场景下仍能通过**"。
- **绿灯必须能解释自己为什么是绿**：报告里"绿"必须**附断言条数**。只看有没有输出，会把"崩在第 31 条"误读成"跑完了只是有点红"。
- **最隐蔽的假绿灯是"那条行为根本没有对应断言"**。本里程碑已撞到四次：方块挖掉不消失 / 右键放不下 / M1.5 假绿灯 / 五个特效方法全是死代码而 `totalSpawnCalls>0` 照样绿。
- **技术文档里的预警 ≠ 登记**。注释描述一条行为，不等于那条行为被登记成断言。
- **测量仪器必须先被验证**。本里程碑已证伪四种测"音高方向"的仪器（过零率 / 自相关 / 频带能量比 / 同包络扫频），结论是方向属性**只能靠真人听**，已转交试玩清单。
- **可见"类验收必须读帧缓冲像素**，判据自身要能自证（命中图文件名不在本轮起始快照内 = 身份门）。
- 跑自测时**鼠标静止**（外部输入混进测量窗会让性能数据不可归因）。跑长任务用**冻结 jar**。

### 五、已完成的验收证据（写报告时可直接引用）

- **单测**：924 / 0 失败。
- **三档门禁**：m1 27/0、ui 75/0、m2 160/0（UI 门从 60 → 75，多出的 15 条正是新增的三个背包阶段断言真的跑到了）。
- **背包界面截图**：`screenshots/m1_ui_selftest-inventory_open-*.png` 与 `...-inventory_interact-*.png`。交互态那张是强证据：手枪已从快捷栏取到主背包左上角（绝对槽 0），tooltip 显示「手枪 / 数量：1 / 上限：1」，悬停高亮在。
- **背包持久化**：`tmp/gate-runs/<stamp>/gate-ui-saves/first-playable/player.json` 里 `saveVersion: 2`，手枪在绝对槽 0、弹药 28、泥土 29 —— 取放操作被真实持久化。
- **UI 音效经真实 OpenAL 播出**：门禁日志 `OpenAL Soft, 8 sources`，会话计数 `ui_open=1, ui_close=1, ui_move=2`。
- **性能（背包全程开启）**：`p95_frame_ms = 1.112`、测量窗内 `>50ms = 0`、`perf_gate_met = true`。用 `-Dskyisland.startState=inventory` 做确定性测量，且已核对这轮**零外部输入事件**。
- **一个已闭合的归因**：首轮出现 134 ms 尖峰且 `perf_gate_met=false`。对照跑证明尖峰与"外部人手在测量窗内按 E 开关背包/点击鼠标"同现（对照跑 0 次输入、尖峰 0、`max=8.354ms`），**与背包渲染路径无因果关系**。
- **本轮修掉的真缺陷**：① 标题/副标题系统性重叠（12 行盒 vs 按字形高度算的占位；三档分辨率 9 种组合全中，720p 16px / 1080p 32px）；② 1080p 下 28 行设置菜单末行压到底部提示行（`uiScale = round(1080/720) = 2`，既有测试只在 720p 跑过）；③ 背包压暗把先画的 HUD 生命条一起压暗了 66%（实测满心 `(229,51,61) → (81,23,29)`）；④ 截图 PNG 带 alpha 导致交付物打开即发灰。

### 六、最终汇报格式（11 项，缺一不可）

1. Main Menu UI
2. Pause UI
3. Settings UI
4. Inventory 是否可用
5. Inventory 操作完成度
6. HUD 改造
7. Save/Load
8. Test Count
9. Human Playtest
10. Bug / Technical Debt
11. M3 Readiness

### 七、边界（不得越过）

不做合成 / 配方 / 第二个怪物 / 第二把枪 / 岛屿生成 / 昼夜循环 / 蹲下。**右键分堆属 M3**（写了不接线就是死代码，本项目明确禁止）。全部工作完成后停止，等待 M3 Vertical Slice 指令。

若全部通过，结论句写：**「M2.2 UI/UX & Inventory Foundation 已通过，等待 M3 Vertical Slice 指令。」**

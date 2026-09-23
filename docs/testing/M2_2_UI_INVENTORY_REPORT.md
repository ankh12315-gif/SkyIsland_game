# M2.2 · UI/UX & INVENTORY FOUNDATION 验收报告

| 项 | 值 |
|---|---|
| 里程碑 | **M2.2 UI/UX & Inventory Foundation** |
| 版本号 | `0.3.2-M2_2-UI-INVENTORY` |
| 提交 tip | `b3b5086`（本报告自身的提交见 §12；报告落笔时工作区**干净**） |
| 冻结 jar | `tmp/selftest-jar/skyisland-frozen.jar`（**5 736 020 B**，M2.2 主验收）→ 换弹口径修订后重烤为 **5 735 737 B**；源 = `target/skyisland-0.3.2-M2_2-UI-INVENTORY.jar` |
| 门禁 | `clean package` **924 / 0**；gate-m1 **27 / 0**；gate-ui **75 / 0**；gate-m2 **160 / 0**（`m2_combat_closure=true`、`ui_selftest_failures=0`）。换弹口径修订后（§12.1）**原数字全部复现** |
| 运行根目录 | M2.2 主验收 `tmp/gate-runs/20260923-103419`；换弹口径修订后复跑 **`tmp/gate-runs/20260923-111426`**（三门禁各自从**空**存档目录冷启动） |
| 性能 | 1920×1080 / VSync off / **背包全程开启**：p95 **0.606 ms**、`>50 ms = 0`、`perf_gate_met = true`（终版冻结 jar，`tmp/m2_m22_perf_invD.stdout.txt`） |
| 结论 | **代码与自动门禁 PASS**；**真人 Gate（试玩清单 A–K，共 81 条）待执行** |

---

## 0. 一句话结论

M2.2 的三屏中文菜单、36 格背包（取放 / Shift 搬运 / 光标持有堆 / tooltip / 存档持久化与 v1→v2 槽位迁移）、HUD 分层与四个 UI 音效已全部落地**并接线**。本轮收尾做了三件事：补上三处「登记了却没人用」的接线欠债、把两条**证据通道**（截图 alpha、性能归因）钉死、在终版代码上重跑全部门禁并留下可逐条核对的数字。

**唯一未关闭的验收项是真人试玩** —— 与 M2.1 的教训完全一致：怪物渲染错到「画在玩家脚下 62 格」这种程度时，**全部门禁依然全绿**。自动化证明不了「人坐在屏幕前看得见、点得动、手感对」。清单见 `docs/testing/M2_2_PLAYTEST_CHECKLIST.md`。

---

## 1. 交付范围（按规格章节）

> **诚实附注**：M2.2 的规格原文（`§4`–`§19`）**未入库**，只存在于上一轮对话里。本表的章节号沿用 `M2_2_PLAYTEST_CHECKLIST.md` 已经写死的映射（那份清单本身就是按规格逐条抄下来的），因此可反向核对。

| 规格 | 交付内容 | 断言 / 证据 |
|---|---|---|
| §4 主菜单 | 继续游戏 / 新建世界 / 设置 / 退出游戏四项；无存档时「继续游戏」退化为**灰色「尚无存档」说明行**，方向键跳过 | gate-ui（三屏导航 + 菜单接线断言）；清单 A1–A6 |
| §5 暂停菜单 | 「已暂停」+ 继续 / 保存并返回主菜单；回到游戏后**鼠标重新锁定** | gate-ui `PAUSE` / `PAUSED_FREEZE` / `RESUME`；清单 B1–B5 |
| §6 设置 | 三类分组**控制 → 显示 → 音频** + 恢复默认 / 返回；新增副标题「改动立即生效」 | gate-ui 设置组；清单 C1–C7 |
| §7/§8 背包可开 | 36 格（3×9 主背包 + 1×9 快捷栏 + 分隔线）；**开背包不暂停世界**；准星 / 挖掘条 / 快捷栏隐藏，**生命条保留** | gate-ui `INVENTORY_OPEN`（5 条）；清单 D1–D8 |
| §9–§12 操作 | 左键取放、Shift+左键搬运（原子性 + 失败提示去重）、光标持有堆（关屏塞回、总数守恒） | gate-ui `INVENTORY_INTERACT`（6 条）+ `INVENTORY_CLOSE`（4 条）；清单 E1–E13 |
| §13 堆叠规则 | 方块 64 / 弹药 128 / 枪 1 | 单测（既有堆叠语义）；清单 E14–E16 |
| §14/§15 HUD | 分层：**F3 调试浮层与玩法层分离**；五种物品图标可区分 | gate-ui HUD 分层断言；清单 F1–F8 |
| §17 存档 | 36 格持久化 + **v1→v2 槽位迁移** | 本轮存档实证（§3.2）；清单 G1–G7 |
| §18 UI 音效 | `open / close / move / denied` 四音，经真实 OpenAL 播出 | gate-ui 会话计数（§3.3）；清单 J1–J6 |
| §19 性能 | 见 §8 | 终版冻结 jar 复测 |

---

## 2. 门禁：绿必须能解释自己为什么是绿

**只看有没有输出，会把「崩在第 31 条」误读成「跑完了只是有点红」。因此下表每条绿都附断言条数。**

| 门禁 | 断言 | 失败 | 关键字段 | 产物 |
|---|---|---|---|---|
| 单元（surefire） | **924** | **0** | `BUILD SUCCESS`（`Total time: 01:31 min`） | `tmp/build.log` |
| gate-m1 | **27** | **0** | `m1_selftest_passed=true`、`m1_functional_closure=true` | `tmp/m2_gate-m1.stdout.txt` |
| gate-ui | **75** | **0** | `m1_5_ui_selftest_passed=true`、`ui_selftest_failures=0` | `tmp/m2_gate-ui.stdout.txt` |
| gate-m2 | **160** | **0** | `m2_combat_closure=true`、`m2_selftest_assertions=160`、`m2_selftest_failures=0` | `tmp/m2_gate-m2.stdout.txt` |

**净增可对账**：

- gate-ui **60 → 75（+15）**，恰好等于本轮新增的三个背包阶段断言之和：`INVENTORY_OPEN` 5 条 + `INVENTORY_INTERACT` 6 条 + `INVENTORY_CLOSE` 4 条。**多出来的 15 条说明这三个阶段是"真的跑到了"，不是挂个名字。**
- gate-m2 **159 → 160（+1）**，即「挖掘阶段手上持有的不是枪（槽位口径自证）」。
- 单元 **924**，含本轮新增 `ScreenshotTest`（3 条）与 `DeadLocalizationKeyTest`（4 条）；`CjkFontTest` 断言字模覆盖全部源码文本，字模已重烤 **1505 → 1517 字形**。

**WARN 全部为设计内行为，逐条说明（不掩盖）**：

| 门禁 | WARN | 为何不是缺陷 |
|---|---|---|
| 全部 | `自动化运行未指定 settingsFile，已改用 tmp/automated-settings/settings.json` | 门禁**故意**不触碰玩家真实设置 |
| 全部 | `设置已复位为出厂默认并写出` | 门禁断言是绝对值，起点必须固定 |
| gate-ui | `帧间隔被钳制: 1.336 s`（上限 0.250 s） | 改键绑定阶段在等一个**不会到来的按键**，帧间隔被钳制是其设计行为 |
| gate-ui | `[键位] 冲突：UP 已绑定到 move_forward，需用户确认是否替换` | 这正是 `rebind-conflict` 用例要触发的路径 |
| gate-ui | `设置文件损坏，已备份并恢复默认` | 这正是 `CORRUPT_FALLBACK` 用例要触发的路径 |
| gate-m2 | 4 条 `F4 刷怪失败：…（拒绝计数 地面=9 / 落差=9 / 视锥=9）` | **拒绝路径**的断言证据：四条硬条件各自都有一次"因它而拒"的负向对照 |

---

## 3. 验收证据

### 3.1 可见证据（framebuffer 截图）

本轮门禁（10:34 那一轮）产出的背包三态截图，均为**本轮新文件名**（身份门自证）：

```
screenshots/m1_ui_selftest-inventory_open-20260923-103442-477.png
screenshots/m1_ui_selftest-inventory_interact-20260923-103442-501.png
screenshots/m1_ui_selftest-inventory_close-20260923-103442-519.png
```

**交互态那张是强证据**：手枪已从快捷栏取到主背包左上角（绝对槽 0），tooltip 显示「手枪 / 数量：1 / 上限：1」，悬停高亮在。

同轮 M2.1 回归证据（怪可见性未退化）：

```
screenshots/m2_selftest-monster-in-view-20260923-103457-286.png
```

> **证据通道本身必须先被验证**（M2.1 教训：证据通道没验证过，"绿灯"就解释不了自己）。本轮因此补了 `ScreenshotTest` 三条断言（§6），因为验收截图一度带 alpha 被误读（§4 缺陷④）。

### 3.2 存档持久化（v2）

```
tmp/gate-runs/20260923-103419/gate-ui-saves/first-playable/player.json
```

```json
{
  "saveVersion": 2,
  "selectedSlot": 1,
  "inventory": [
    { "slot": 0,  "item": "skyisland:pistol",      "count": 1  },
    { "slot": 28, "item": "skyisland:pistol_ammo", "count": 24 },
    { "slot": 29, "item": "skyisland:dirt",        "count": 1  }
  ]
}
```

`saveVersion: 2` 且**手枪落在绝对槽 0**、弹药在**绝对槽 28**、泥土在**绝对槽 29** —— 即"取放操作被真实持久化"（v1 的"快捷栏第 4 格"= v2 的绝对第 31 格；既然最终落点是绝对 28/29，说明槽位是按 v2 口径写的，不是 v1 的 0..8 相对口径）。`inventory` 数组只列**非空格**，属该格式的设计。

### 3.3 UI 音效经真实 OpenAL 播出

gate-ui 日志：

```
[音频] OpenAL 就绪（设备=OpenAL Soft），source 池=8，已上传=20 个音
[音频] 会话事件计数：总计 4 次（ui_open=1, ui_close=1, ui_move=2, ui_denied=0）
[音频] OpenAL device / context 已释放（无残留 native 资源）
```

开着 1 次、关着 1 次、搬运 2 次 —— 与三个阶段走的行为一一对应（交互阶段点两次格子 = 取一次 + 放一次 = 2 次 `ui_move`）。**`ui_denied=0` 也是对的**：本轮门禁没有构造"背包满"的失败搬运。

### 3.4 性能

见 §8。

---

## 4. 本轮修掉的真缺陷（5 个，每个都有可复现的现象）

| # | 现象 | 根因 | 修法 | 若漏掉会怎样 |
|---|---|---|---|---|
| ① | 菜单标题与副标题**系统性重叠** | 占位按 12 行盒算，实际按字形高度画 | 纵向预算 + 按字形高度占位 | 三档分辨率 9 种组合全中（720p 16px / 1080p 32px） |
| ② | 1080p 下设置菜单**末行压到底部提示行** | `uiScale = round(1080/720) = 2`，既有测试只在 720p 跑过 | 补 1080p 覆盖 | 只有高分辨率机器上能看到 |
| ③ | 开背包把先画的 **HUD 生命条一起压暗 66%** | 压暗是"面板渲染的第一步"，而面板排在 HUD 之后 | 压暗改为 HUD **之前**的单点 pass | 实测满心 `(229,51,61) → (81,23,29)`，恰为 `1 − DIM.alpha`；直接违反"开背包仍要看见自己在挨打" |
| ④ | 交付的验收截图**打开即发灰** | PNG 写成 `TYPE_INT_ARGB`，把帧缓冲混合副产物 alpha（0.67–0.78）写进了文件 | alpha 钉死 255 + `TYPE_INT_RGB`，加 `ScreenshotTest` | 面板实测 `#151D29`（对比清晰）在看图工具里是一片浅灰，一度被当成"面板没画出来"——**证据被误读等于没有证据** |
| ⑤ | M2 战斗自测**挖掘阶段整段失败** | `Inventory.size()` 9→36 后，槽位扫描辅助方法返回**绝对索引**，而 `selectSlot` 只认 0..8 | 两个方法只扫 `hotbarSlot(0..8)`，并加前置条件断言 | "切到第一个非枪物品"实际选中了快捷栏第 1 格的**枪**（持枪左键 = 开火不是挖掘），7 条断言一起以"挖掘没生效"的样子失败 —— **失败现场指向玩法，真因在槽位口径** |

> ⑤ 与审计判据同族：**测量仪器必须先被验证**。加了"挖掘阶段手上持有的不是枪（槽位口径自证）"之后，同类错误今后会以**槽位口径**的措辞直接指出自己，而不是伪装成玩法缺陷。

---

## 5. 三处「登记了却没人用」的接线欠债

**最隐蔽的假绿灯是"那条行为根本没有对应断言"。**本轮清掉三处 `Localization` 登记齐全、但**没有任何绘制点/分支消费**的 key：

| key | 原状 | 后果 | 处置 |
|---|---|---|---|
| `INV_CURSOR_HINT`（左键取放 / Shift+左键快速移动 / E 或 Esc 关闭） | 登记齐全，**零消费方**；面板下方画的是另一条只说"怎么关"的 `HINT_INVENTORY` | **背包能用，玩家学不会** —— 玩家唯一的知识来源没被画出来 | 删 `HINT_INVENTORY`，面板下方改画 `INV_CURSOR_HINT` |
| `HINT_MAIN / HINT_PAUSE / HINT_SETTINGS` | 四个 `HINT_*` 全无消费方，`footerHint()` 返回**四条英文 ASCII 字面量** | 菜单中文化后，**中文标题 + 中文菜单项 + 英文底栏**同屏出现 | `footerHint()` 改为统一从 `Localization` 取；`PLAYING / INVENTORY` 返回空串是产品决定 |
| 设置屏**副标题** | `HINT_SETTINGS` 被塞进了 `MenuScreen` 的副标题参数（那是当时唯一能塞进去的空位） | **提示与副标题是两种东西**，顶替导致提示位置随排版漂移、真正的副标题位置空着 | 新增 `MENU_SETTINGS_SUBTITLE` |

> **两个 key 争一个绘制点时，被删掉的一定是信息更全的那个** —— 于是"能关但不知道怎么用"就成了最终形态。这就是为什么必须有一个**守门人**专门盯"有没有人用它"，而不是只盯"有没有文案"（后者 `LocalizationTest` 早已在做，方向恰好相反）。

另一个同族欠债：`Action.INVENTORY` 的 `consumedBy` 标注写着 **"界面消费方在 M3"**。M2.2 落地背包之后，这行标注会让设置界面显示 `Inventory [M3]` —— **等于告诉玩家这个键要等下个版本，而它现在就有用**。已同步摘掉。

---

## 6. 新增护栏与它们的自证

**每条新断言都做过反向验证：注入破坏 → 确认精确变红 + 可读归因 → 恢复 → 确认全绿。**

| 护栏 | 抓什么 | 反向自证方式 | 结果 |
|---|---|---|---|
| `ScreenshotTest`（3 条） | 写出的 PNG 必须**无 alpha 通道**、alpha≡255；RGB 逐像素不变；`isNearlyUniform` 仍能区分全同色/有内容 | 反向对照 `tmp/nc_*`：改回 `TYPE_INT_ARGB` → 断言红 | ✓ |
| `DeadLocalizationKeyTest`（4 条） | 每个文案 key 必须有消费方；已知欠债白名单**不得腐化/扩张** | ① 三条已知有消费方的 key 做**正向对照**（扫描器坏掉时会报告"全部无消费方"，那种"大丰收"其实是仪器坏了）；② 在**内存副本**上抹掉 `INV_TITLE` 的引用，核心判据必须变红 | ✓ |
| gate-ui `INVENTORY_*` 三阶段（15 条） | 见 §3.2 / §3.3；交互阶段走完 `光标像素 → DPI 换算 → hitTestAny → 交互语义` 全链路 | `inventorySlotCenterWindow` **故意**把逆换算独立写出：正向 `fb/win` 若被写成 `win/fb`，二者不再互逆，自测瞄准会落到别的格子并**当场暴露** | ✓ |
| `UiAudioWiringTest`（既有） | UI 音效的**接线存在性** | 扫源码残留标记 `TEMP_REVERSE_VERIFY`（`grep` 全仓：仅该测试自身的检查行存在，**无残留**） | ✓ |
| gate-m2「槽位口径自证」（1 条） | 挖掘阶段手上不得是枪 | 见 §4 ⑤ | ✓ |

**逆向验证残留检查**：全仓 `grep TEMP_REVERSE_VERIFY` 只命中 `UiAudioWiringTest.java:145`（那是**检查该标记的代码本身**），无任何注入残留。

---

## 7. 仪器失败记录：音高方向（唯一没有机器判据的属性）

UI 音效里 **open/close 的「方向」**（开=上行、关=下行）是本仓库**唯一一条没有机器判据**的音频属性。为它试过**四种信号域仪器，全部失败**：

| # | 仪器 | 失败形态 |
|---|---|---|
| ① | 过零率 | 量到的是**噪声亮度**而非基频：源码 `620→1271 Hz` 的**上行**，量出来却是 `2205→1423 Hz` 的**下行**，**符号是反的** |
| ② | 自相关基频 | 被噪声层抹平，且只有搜索下限之上才有读数 |
| ③ | 频带能量比 | 先在**平包络扫频**上自证通过；换成**与真实音同形状的衰减包络**后立刻失效。用真实音的两个变体比，甚至给出**相反的符号**（v0 = `+0.044`，v1 = `−0.043`） |
| ④ | 带负对照的差分比较 | 同样在两个变体上**符号相反** |

**根因**：合成器里有宽带"空气"噪声层（`scale(air, 0.14)`），它主导了任何高频统计量。而按源码，主音增益 `0.75`、噪声层 `0.14` 且还经过低通与包络衰减 —— **方向多半是听得见的，只是这四种仪器测不出**（这不是猜测：四种仪器在"合成扫频"上的自证都通过了，唯独换到真实音就失效，差的正是这层噪声）。

**结论**：方向属性**只能靠真人听**，已转交试玩清单 **§J J1–J3**。`J1–J3` 不是形式主义 —— 那是目前**唯一**的判据。

---

## 8. 性能合同与尖峰归因

**合同（不得为调绿改阈值）**：1920×1080 / VSync off / P95 ≤ 16.7 ms **且** `>50 ms 卡顿 = 0`。

五次测量的完整对账（**测量窗内是否混入外部输入**是唯一的自变量）：

| 跑次 | 初始状态 | jar | 测量窗内输入事件 | p95(ms) | max(ms) | >50ms | `perf_gate_met` |
|---|---|---|---|---|---|---|---|
| `m2_m22_perf` | PLAYING | frozen | **44** | 1.287 | **134.063** | 1 | **false** |
| `m2_m22_perf_ctlA` | PLAYING（对照） | frozen | **0** | 1.172 | 8.354 | 0 | **true** |
| `m2_m22_perf_inv` | INVENTORY | target | **30** | 1.668 | 88.609 | 1 | **false** |
| `m2_m22_perf_invC` | INVENTORY | target | **0** | 1.112 | 48.577 | 0 | **true** |
| **`m2_m22_perf_invD`** | **INVENTORY** | **frozen（终版）** | **0** | **0.606** | **19.154** | **0** | **true** |

**归因（对照跑定位，不调判据）**：**每一次出现 `>50 ms` 尖峰的测量窗内都存在外部键鼠事件**（44 / 30 条）；**两轮测量窗内 0 输入事件的跑次（ctlA / invD）都 `>50 ms = 0` 且 `perf_gate_met = true`**。⇒ 尖峰与**背包渲染路径无因果关系**，而是**测量窗内混进了人手操作**。

**终版复测（`invD`）的完整汇总**（`-Dskyisland.startState=inventory` 让测量窗全程处于背包态）：

```
预热 20s / 计划 90s / 实际统计窗口 90.017s
样本数_帧        = 234988
FPS              = 2610.49      TPS = 60.01
mean_frame_ms    = 0.383        median_frame_ms = 0.366
p95_frame_ms     = 0.606        p99_frame_ms    = 0.873
max_frame_ms     = 19.154
spikes_gt_50ms   = 0            spikes_gt_100ms = 0     spikes_gt_150ms = 0
clamped_frames   = 0
perf_gate_met    = true
inventory        = INV[ - ... - >skyisland:pistolx1 skyisland:pistol_ammo x24  - ... - ]
```

`inventory = INV[…]` 这一行是**背包态自证**：整个测量窗里背包确实是开着的（手枪在绝对槽 0、弹药在绝对槽 28）。

> **为什么用 `startState=inventory` 而不是"跑一会儿再按 E"**：开关动作本身会落在测量窗内，于是"尖峰来自背包渲染"和"尖峰来自外部交互"**无法区分** —— 首轮性能跑正是这样撞上尖峰的。从背包态启动把首次开销完全压进预热窗，测量窗里只剩稳态渲染成本。

---

## 9. 真人 Gate（A–K）—— **待执行**

**这是本轮唯一未关闭的验收项。** 前置：从冻结 jar 启动（长任务一律用冻结产物）、**鼠标静止**时跑自测、**每次开新存档目录**。

清单见 `docs/testing/M2_2_PLAYTEST_CHECKLIST.md`，覆盖：

| 组 | 内容 | 条数 |
|---|---|---|
| A | 主菜单（版本行 / 四项 / 无存档灰行 / 键盘鼠标一致） | 6 |
| B | 暂停菜单（冻结 / 恢复后锁定鼠标 / 保存返回后**菜单刷新**） | 5 |
| C | 设置（三分组 / **中文渲染逐行读** / 滑杆实时 / 恢复默认） | 7 |
| D | 背包能否打开（36 格 / 不暂停世界 / 准星隐藏 / **生命条保留** / 输入被吞） | 8 |
| E | **背包操作完成度（本里程碑核心）** | 16 |
| F | HUD 改造（五图标**逐个盯一秒**可区分 / 手持物与选中格一致） | 8 |
| G | 存档 / 读档（**v1→v2 迁移后位置不能错** / 新建世界不漏网格） | 7 |
| H | 性能（开背包 60s 不比不开更差 / 反复开关 20 次不泄漏） | 5 |
| I | M2.1 遗留真人项（**HUD 与菜单改过，必须重跑**） | 8 |
| J | UI 音效（**J1–J3 只能靠耳朵**，见 §7） | 6 |
| K | 换弹与移动（**M2.2 修订口径**：边走边换走满 1.2 s；反向对照"换弹期间仍不能开枪"） | 5 |

> **§K 是主验收之后追加的**（换弹口径修订，见 §12.1）。它的 K1 就是旧 A5 的反面：
> 旧 A5 期望"移动中按 R 会被打断"，K1 期望"一路走照样换完" —— **失败长相正好互换**。

**特别注意两类"假通过"**（清单原文）：

1. **"测试全绿"不是通过。** 这份清单存在的原因就是自动化测不出"看得见"。
2. **"代码里有"不是通过。** 若某个行为你**没亲眼看到**，就记"未验证"，而不是记"通过"。

---

## 10. 已知风险与技术债

| 项 | 状态 | 说明 |
|---|---|---|
| **真人 Gate A–K** | **未执行** | 本轮唯一还差的一步（§9） |
| 音高方向（open/close 上行/下行） | **无机器判据** | 四种仪器全失败（§7），只能靠真人听；若本机无声卡，记"未验证"而非"通过" |
| 背包**右键分堆** | **未做，属 M3** | 明确不做：写了不接线就是死代码，本项目禁止 |
| 合成 / 配方 / 第二种怪物与武器 / 岛屿生成 / 昼夜循环 / 蹲下 | **未动，仍归 M3** | 未静默缩小 |
| `KNOWN_DEAD_KEYS` 里的 6 条历史死 key | **有登记，未修** | `HUD_HEALTH / HUD_DEAD / MSG_SAVE_OK / MSG_SAVE_FAILED / DEATH_NO_DROP / DBG_MODE`；`DeadLocalizationKeyTest` 保证它们**不会被偷偷忘掉**，也不会被当成垃圾桶 |
| `pollMenuNav` 的坐标口径 | **未改，有既有欠债** | 菜单命中仍用帧缓冲口径（M1.5 路径）；背包**从一开始就走对了**（统一 `InventoryLayout.windowToFramebuffer`）。菜单侧的同源隐患未在 M2.2 范围内修 |
| C7 中文渲染：34 个笔画极密的字按 11/10px 重烘 | **设计行为** | 若某字明显小一号**不算缺陷**；**笔画缺失/被切边**才是真缺陷 |
| 本轮全部为**本地提交，未 push** | 待确认 | 见 §12 |

---

## 11. 最终汇报（11 项，缺一不可）

| # | 项 | 状态 |
|---|---|---|
| 1 | **Main Menu UI** | 完成：中文四项（继续 / 新建世界 / 设置 / 退出），无存档时"继续游戏"退化为灰行并跳过；底部提示已中文化 |
| 2 | **Pause UI** | 完成：「已暂停」+ 继续 / 保存并返回主菜单；世界时间冻结；恢复后鼠标重新锁定 |
| 3 | **Settings UI** | 完成：控制 → 显示 → 音频三分组 + 恢复默认 / 返回；新增副标题；全中文（含 CJK 点阵字模 1517 字形） |
| 4 | **Inventory 是否可用** | 可用：`E` 开关，36 格（27 主 + 9 快捷）；**开背包不暂停世界**，准星/挖掘条/快捷栏隐藏、生命条保留 |
| 5 | **Inventory 操作完成度** | 左键取放（整堆/并堆/互换）、Shift+左键搬运（**原子性** + 失败提示去重）、光标持有堆（关屏塞回 + 总数守恒）、tooltip；**右键分堆属 M3，未做** |
| 6 | **HUD 改造** | 完成分层：F3 调试浮层与玩法层分离；五种物品图标可区分；**压暗单点化**（不再压暗生命条） |
| 7 | **Save/Load** | 完成：36 格持久化 + **v1→v2 槽位迁移**；实证 `saveVersion: 2`、手枪绝对槽 0、弹药绝对槽 28、泥土绝对槽 29 |
| 8 | **Test Count** | 单元 **924 / 0**；gate-m1 **27 / 0**；gate-ui **75 / 0**；gate-m2 **160 / 0**（`m2_combat_closure=true`） |
| 9 | **Human Playtest** | **未执行** —— 见 §9，清单 A–K 共 81 条（§K 为换弹口径修订后新增的 5 条，见 §12.1） |
| 10 | **Bug / Technical Debt** | 见 §10：6 条历史死 key（有登记）、`pollMenuNav` 坐标口径旧债、右键分堆归 M3、音高方向无机器判据 |
| 11 | **M3 Readiness** | **就绪**：UI 状态机（MAIN_MENU / SETTINGS / PLAYING / PAUSED / INVENTORY）与渲染分层已稳定，背包模型与槽位渲染器可被 M3 合成界面复用；未越界实现任何 M3 内容 |

---

## 12. 提交链与门禁数字

M2.2 提交链（`3223258` → `b3b5086`，本报告为第 18 个）：

```
b3b5086  docs(m2.2): 真人试玩清单（A–J）+ 新会话交接提示词
7c95e71  test(m2.2)+feat(game): 背包三阶段自测 + 战斗槽位口径自证 + 接线与字模同步
6519b28  fix(ui): M2.2 背包操作提示接线 + 底部提示中文化 + 死 key 守门人
e2d68e2  fix(render): M2.2 截图 PNG 去除 alpha —— 验收证据不再被误读成发灰的界面
de35217  fix(render): M2.2 全屏压暗单点化（不再压暗 HUD）+ 背包提示改为操作提示
9a5c64e  feat(audio): M2.2 UI 音效（open / close / move / denied）+ 接线存在性守卫
7fd5014  fix(ui): M2.2 菜单排版几何 —— 标题/副标题系统性重叠 + 纵向预算 + 背包标题占位
db720a8  chore(m2.2): 版本升 0.3.2-M2_2-UI-INVENTORY + 重烤 CJK 字模 1484→1505
5484493  test(m2.2): 四类新护栏（图标可区分 / 36 格命中 / 配色对比度 / 菜单接线）
e9161f9  feat(game): M2.2 接线 —— 背包输入路由、菜单项真实行为、HUD 分层
f3e3c03  fix(audio): M2.2 补回 AudioManager 产品构造器 + 恢复被整篇重写的接线测试
fa223db  feat(render): M2.2 背包界面布局与渲染 pass
eddbc2a  feat(save): M2.2 存档持久化 36 格 + v1→v2 槽位迁移
c31e4ff  feat(ui): M2.2 菜单中文化 + 三屏重构（继续/新建世界、设置分三类）
1e267a3  feat(player): M2.2 背包扩到 27+9 格 + 鼠标交互语义
04791ca  feat(ui): M2.2 视觉语言统一 —— UiTheme/UiMetrics/ItemIcon/SlotRenderer
3223258  feat(ui): M2.2 增加 INVENTORY 界面状态与状态机迁移   ← M2.2 链起点
```

冻结 jar `tmp/selftest-jar/skyisland-frozen.jar` 由本轮 `clean package` 之后从
`target/skyisland-0.3.2-M2_2-UI-INVENTORY.jar` 复制而来（两道校验：`target/` 下恰好 1 个 jar + 冻结 jar 不比源码旧）。

| 门禁 | 断言 | 失败 |
|---|---|---|
| 单元（surefire） | 924 | 0 |
| gate-m1 | 27 | 0 |
| gate-ui | 75 | 0 |
| gate-m2 | 160 | 0（`m2_combat_closure=true`） |

---

## 12.1 收尾后追加（2026-09-23）：换弹不再被移动打断（PRD v0.3.2-r1）

> 本节记的是一次**在 M2.2 主验收之后**发生的口径修订。它**不重开 M2.2 的范围**（不新增玩法、不新增需求），
> 但因为它改的是 PRD【MVP 必须】行，必须有正式记录，且必须重跑门禁。

### 12.1.1 改了什么

| 项 | 内容 |
|---|---|
| **用户裁决** | 「换弹我想要在行走中也要能换弹」→ 采纳口径「**彻底边走边换**」：删掉整套"换弹被打断"机制，不留死代码 |
| **PRD** | `PRD_v0.3.2.md` 5.4.3 原「换弹打断（v0.3.2 新增）：移动打断换弹 = **取消换弹**」**作废** → 「换弹与移动：**移动不打断换弹**」（v0.3.2-r1 行 525）；新增 §1.1 修订记录行；表后补 4 条说明块 |
| **为什么废止** | 玩家无法从界面区分"被移动取消了换弹"与"换弹还没走完"（UI 上都是"没换完"），该规则**不可读**；且它把换弹从一条时间线变成带隐藏失败态的流程。规则④（完成前不转移弹药）原是为"取消零回滚成本"而设，取消口废止后它退化为更简单的不变量 |
| **未受影响** | 规则①②③、换弹时长 1.2 s、「换弹期间不得开枪」、满弹匣换弹废止 |

### 12.1.2 删掉的死代码（本项目禁止"读起来合理、永远不执行"的代码）

| 删除项 | 位置 |
|---|---|
| `cancelReload()`、`reloadsCancelled` 字段 + getter | `GunState.java` |
| `tick(double, boolean moving, Inventory)` 的 `moving` 参数与取消分支 | `GunState.java`（签名收敛为 `tick(double, Inventory)`） |
| `moving` / `cancelledBefore` 与取消回调分支 | `CombatController.step` |
| `Listener.onReloadCancelled()` + 全部 5 处实现 | `CombatController`、`AudioFeedback`、`SkyIslandGame`、`M2CombatSelfTest`、两处匿名类 |
| `Localization.MSG_RELOAD_INTERRUPTED`（含文案"换弹被打断"） | `Localization.java` —— 由死 key 守门人 `DeadLocalizationKeyTest` 保证必须整体删除而非加白名单 |
| 自测阶段 `RELOAD_INTERRUPTED` → **`RELOAD_WHILE_WALKING`** | `M2CombatSelfTest.java`（阶段数仍 15、编号仍 6/15，**刺激序列不变、期望相反**） |
| 文档口径同步 | `PRD_v0.3.2_CHANGELOG.md` §R1、`TECH_DESIGN_v0.1.1.md` §X′ + E-21、`README.md`、`MVP_REQUIREMENTS_TRACEABILITY.md`（`MVP-COMBAT-020` → `COVERED`）、`M2_1_PLAYTEST_CHECKLIST.md` A5 作废、`M2_2_PLAYTEST_CHECKLIST.md` 新增 §K |

### 12.1.3 断言：三段证据链

| 层级 | 断言 | 位置 |
|---|---|---|
| 内核（无移动概念） | `reloadRunsToCompletionOnAFixedStepClock` —— 按固定步长走 72 步必完成、弹药只在完成时转移 | `CombatCoreTest`（替代原 `movingCancelsReloadAndLosesNothing`） |
| 接线（有移动意图） | `walkingDoesNotInterruptReload` —— 全程喂"按住 W"意图，换弹仍完成 | `CombatControllerTest`（替代原 `movingCancelsReloadAndChangesNothing`） |
| 端到端（真窗口） | 自测阶段 6/15 `RELOAD_WHILE_WALKING`，9 条 | `M2CombatSelfTest` |

**为什么必须分三层**：移动是 `PlayerIntent` 的概念，`GunState` 根本收不到它 —— "收不到"没法用行为断言表达，只能靠"参数表里没有它"这个结构事实。所以内核层守"换弹是一条纯时间线"，接线层守"移动意图喂进来也不改变这条时间线"，端到端层守"真窗口里走了 5 格还照样换完"。

### 12.1.4 端到端实测读数（gate-m2，`tmp/gate-runs/20260923-111426`）

```
阶段 6/15 边走边换弹（移动不打断·弹药不提前转移）  预算 360 步
PASS · 连续开火把弹匣打空                              magazineAmmo=0
PASS · 按 R 之后立刻进入换弹态                          第 167 步按下 R，同一逻辑步内观测
PASS · 换弹期间全程按住 W 且未被取消（≥ 70 步）          边走边换步数=72（= 1.2 s / (1/60) 满值）
PASS · 换弹期间确实产生了水平位移（≥ 3.0 格）            位移=4.975 格（理论 ≈ 4.94，扣加速段）
PASS · 边走边换：换弹走完全程（收到一次 onReloadCompleted） 完成事件数=1
PASS · 换弹完成那一刻弹匣已补满 = 12                     magazineAmmo=12
PASS · 换弹走完后弹匣 = 12（完成这一刻才转移）            magazineAmmo=12
PASS · 换弹走完后换弹态已退出                            isReloading=false
PASS · 打空 + 边走边换全程后备弹药未被扣减（仍为 24）      打空后=24 换弹后=24
```

**旁证**：`reload` 音频事件 = **2**（`RELOAD_FULL` 与 `RELOAD_WHILE_WALKING` 各按一次 R 且被受理），
断言已由 `reload ≥ 1` 收紧为 `reload ≥ 2`。

### 12.1.5 反向验证（两轮，都做了"注入 → 确认精确变红 → 恢复"）

| # | 注入 | 结果 |
|---|---|---|
| A | 把取消分支加回 `CombatController.step`（`intent.hasMovement()` → `cancelReload`） | ① `walkingDoesNotInterruptReload` 在半程断言处**红**（`expected: <true> but was: <false>`）；② 同轮 M2 自测实测 `边走边换步数=1`、`完成事件数=0`、`magazineAmmo=0` → 阶段 6 红，并连锁让 SHOOT_KILL / WALL / FALLOFF / 命中音效共 25 条红（**因为这正是旧口径的真实后果**）；③ `CombatCoreTest` 28/0 **保持绿** —— 印证三层证据链各自独立 |
| B | 把完成判据改早（`reloadRemaining <= 0` → `<= 0.02`） | `reloadRunsToCompletionOnAFixedStepClock` 在「71 步仍在换弹」处**红**；`pistolReloadTakesExactlyOnePointTwoSeconds` 同时红 |

两轮注入标记（`TEMP_REVERSE_VERIFY`）已全部删除；`UiAudioWiringTest.noReverseVerificationMarkerIsLeftBehindInMainSources` 扫 `src/main/java` 复核**零残留**。

### 12.1.6 修订后门禁（最终代码，冷启动空存档目录）

| 检查 | 结果 |
|---|---|
| `clean package`（surefire 全量） | **924 / 0**，BUILD SUCCESS |
| gate-m1 | **27 / 0** |
| gate-ui | **75 / 0** |
| gate-m2 | **160 / 0**，`m2_selftest_passed=true`、`m2_combat_closure=true` |
| CJK 字模 | `CjkFontTest` **12 / 0** —— 本轮新增的中文**全部落在既有字模内**，未触发重烤 |
| 运行根目录 | `tmp/gate-runs/20260923-111426` |
| 冻结 jar | `tmp/selftest-jar/skyisland-frozen.jar`（5 735 737 B） |

> **数字与 M2.2 主验收完全一致（924 / 27 / 75 / 160）。** 换弹口径修订**没有**改变断言总数：
> 阶段 6 仍是 9 条（4 条被替换 + 1 条新增移位数），Listener 从 8 个方法减到 7 个，
> 对应的 `tee` 完整性测试与 `AudioFeedback.andThen` 顺序测试同步把"8 个事件 × 2 = 16 条"收紧为"7 × 2 = 14 条"。

### 12.1.7 提交状态

本次修订涉及 **18 个文件**（11 源码 + 7 文档），见 §12.1.2。**截至本报告更新时，它们尚未提交**
（工作区改动 = 这 18 个文件）—— 提交分组建议：

```
1) fix(combat): 换弹不再被移动打断（删 cancelReload / reloadsCancelled / onReloadCancelled / MSG_RELOAD_INTERRUPTED）
   GunState.java, CombatController.java, AudioFeedback.java, AudioEvent.java, Localization.java, SkyIslandGame.java
2) test(m2.2): 三段证据链 —— reloadRunsToCompletionOnAFixedStepClock / walkingDoesNotInterruptReload /
   自测阶段 RELOAD_WHILE_WALKING（含 tee 与音频顺序测试的 8→7 收紧）
   CombatCoreTest.java, CombatControllerTest.java, M2CombatSelfTest.java, M2CombatSelfTestTeeTest.java,
   AudioFeedbackWiringTest.java
3) docs(prd): v0.3.2-r1 —— 5.4.3「移动不打断换弹」+ 变更日志 §R1 + TECH §X′/E-21 + 溯源矩阵 + 试玩清单 §K
   PRD_v0.3.2.md, PRD_v0.3.2_CHANGELOG.md, TECH_DESIGN_v0.1.1.md, README.md,
   MVP_REQUIREMENTS_TRACEABILITY.md, M2_1_PLAYTEST_CHECKLIST.md, M2_2_PLAYTEST_CHECKLIST.md,
   M2_2_UI_INVENTORY_REPORT.md
```

---

## 13. STOP

本轮**只**关闭 M2.2 UI/UX & Inventory Foundation。**未进入 M3、未做合成 / 配方 / 第二个怪物 / 第二把枪 / 岛屿生成 / 昼夜循环 / 蹲下 / 右键分堆。**

**M2.2 UI/UX & Inventory Foundation 已通过（代码与自动门禁），等待真人试玩与 M3 Vertical Slice 指令。**

# M2.1 — Combat Feel & Readability 交付报告

- **构建号**：`0.3.1-M2_1-COMBAT-FEEL`（上一版 `0.3.0-M2-COMBAT-PROTOTYPE`）
- **产物**：`target/skyisland-0.3.1-M2_1-COMBAT-FEEL.jar`（5,681,281 字节）
- **冻结副本**：`tmp/selftest-jar/skyisland-frozen.jar`（门禁一律从它启动）
- **门禁证据目录**：`tmp/gate-runs/20260922-031147/`（**第三轮**：T7 坠落伤害 + 近战竖直判定）
- **本次修完后仍需你做的最后一件事**：**≥ 10 分钟人工试玩**，清单见
  [`M2_1_PLAYTEST_CHECKLIST.md`](./M2_1_PLAYTEST_CHECKLIST.md)

---

## 0. 一句话结论

**脚本侧全部通过**（首轮 818 → 第三轮 **821** 条单测 / 0 失败；三门禁全绿
**M1 27 / UI 60 / M2 129→135**；M2 自测 **135** 条断言 / 0 失败），
**但这一轮真正的产出不是"把功能做完"，而是抓出了 8 处"写完了、编译过了、门禁也绿了，
然而从来没有被调用过"的死代码** —— 其中 3 处是你在屏幕上**本应看到却完全看不到**的
枪口火光、命中标记、开火后坐力。

> **这是本项目第 4 次撞上同一条判据：`断言全绿 ≠ 无缺陷`。**
> 前三次分别抓到了方块挖掉不消失、右键放不下方块、M1.5 假绿灯。
> 这一次的形态最隐蔽：**不是已有的断言判错了，而是这几条行为根本没有对应的断言**。

### 0.1 后续：一次真实试玩又抓到 2 处缺陷（D9 / D10）

首轮试玩（用户实机）报出两条症状，都属"渲染得出来、却和有缺陷时长得一样"型：

- **「枪口火光有点刺眼」** → 缺陷 **D9**：枪口偏移只写在注释里、代码里不存在，
  火光被生成在**眼睛**处、被相机吞进立方体内部 → 满屏白光。
- **「生成出来的怪物找不到了」** → 缺陷 **D10**：F4 刷怪用了一条**只做水平搜索**的
  落脚点函数，玩家站在自建高塔上（y≈71）时地面在 7 格之下找不到 → 怪被留在半空自由落体。

两处都**在新版本里被修掉，并补上了会变红的断言**（见第 3 节表尾 D9/D10 与第 4.5 节）。
其中 D9 的断言在旧代码上距眼睛 **0.0000 格**、D10 的断言在旧代码上落到 **y≈73.99 的半空** ——
两者都是"旧代码必红、新代码才绿"的可证伪断言，不是"看起来更顺"的措辞。

### 0.2 第二轮：C7 配色裁决（关闭）+ 伤害来源仪器

在第一轮 D9/D10 之后又做了两件事，都是**在既有行为上补证据**，不改任何玩法数值：

1. **C7 裁决关闭（怪物配色）**：配色**保留红色系**（greybox = 占位美术 + 高辨识度），
   但把四个体色从"挤在极暗带"抬进**中亮区间**并拉开明暗台阶。before/after 配色表与
   Rec.709 luma 见 **§2-C**。调色只动颜色常量 —— **parts 布局 / 步态 / 攻击前冲 / 眼睛位置 /
   碰撞箱一律未动**。
2. **伤害来源仪器（让「怎么死的」可回答）**：首轮试玩日志只有一个总数 `player_hurt=5`，
   而 `Player.hurt()` 不打印任何东西 —— "被近战怪咬"与"摔落"在证据上无法区分。
   本轮给 `Player` 加了 `DamageCause` 归因（`近战 / 坠落 / 虚空 / 其它`）与一行归因日志，
   并补了一条**会变红**的自测断言（§4.5）。**纯仪器化**：未改任何伤害值、阈值、冷却或死亡判定。

> 第 4.1 节顺带登记一条**独立发现**（**不是**本轮引入）：`Player.fallDamageFor()` 算出的坠落伤害
> **从未被施加到生命值**（`updateFallState` 只计算与打印、不调用 `hurt()`）。它不在本轮改动范围，
> 作为待裁决项登记在 **§7（T7）**，并顺带解释了"为什么 `hurt()` 目前只有近战一个调用方"。

### 0.3 第三轮：两条**玩法语义**缺陷修复（T7 坠落伤害 + 缺陷 B 近战竖直判定）

用户明确授权的**缺陷修复轮**（非加功能，非 scope creep）。两处都是"脚本全绿、却与设计语义不符"的漏项：

1. **T7 —— 坠落伤害算了不施加**：`Player.updateFallState` 把 PRD 5.3【MVP 必须】的
   `伤害 = max(0, floor(坠落格数 − 3))` **算出来却不扣血** —— `hurt()` 在 `src/main` 里当时
   只有近战怪一个调用点。本轮在**落地事件**上补上
   `hurt(world, lastFallDamage, DamageCause.FALL)`，**每次落地只结算一次**
   （落地后 `fallDistance` 清零 + 该分支只在"空中 → 站立"这一次跃迁进入）。
   更要紧的是**测试侧**：`FallDamageTest` 8 条用例**只读 `lastFallDamage()` 这个中间量**，
   全文件里 `health` 与 `hurt` 出现 **0 次** —— 这正是"818 条全绿而机制不存在"的原因。
   本轮把断言**落到 `health()` 这个可观测后果上**。
2. **缺陷 B —— 近战攻击判定只有水平分量**：`MeleeMonster` 的咬击只看
   `horizontal ≤ ATTACK_RANGE`，**没有任何 y 项**；怪站在塔底（水平 ≈ 0.2 格）能咬到 7 格高台上的玩家
   （首轮试玩"看不见怪却一直掉血"的现场，见证据文档 §4.2）。
   本轮新增 `ATTACK_VERTICAL_RANGE = 1.5`（推导见常量 javadoc：容忍一级台阶 Δy=1.0 与跳跃顶点
   `Player.JUMP_HEIGHT = 8.95²/(2×32) = 1.2499`，拒绝数格落差 Δy=2.0），**只管攻击判定、不进 `chasing`**
   —— "怪可以追一个它够不着的东西"是有意保留的行为（类 javadoc 第 7 步：允许卡住）。
3. **咬击几何仪器**：每咬记一行 `[战斗] 近战咬击：水平 X.XX 格 / 垂直 Δy Y.YY 格`，
   并把最近一次咬击的几何量暴露成 `MeleeMonster.lastBiteHorizontalDistance()` /
   `lastBiteVerticalOffset()` —— **可断言**，不只是可打印
   （证据文档 §4.3 那条"每咬坐标未记录"由此补齐）。

> 两处改动都控制在最小面：**未改** `FALL_DAMAGE_THRESHOLD`、`fallDamageFor` 公式、
> `ATTACK_RANGE`、`ATTACK_DAMAGE`、`ATTACK_COOLDOWN_SECONDS`、`CHASE_RANGE`，也未动任何渲染 / 存档口径。
> 三条新断言全部做了"回退后必红"实验（§4.7）；两处改动都做了**逐字节复原**的等价回退。

---

## 1. 背景：本阶段是"接手一份被打断的交付"

M2.1 最初由三条并行线（工程 / 美术 / 音频）分工实现，三条线**全部**在运行约 36 分钟后
撞上 API 频率限制（配额于 08:00 重置）而中断，留下了**真实但不完整**的代码：
包建好了、类写好了、注释写得很详细，**唯独接线没有做完**。

因此本报告分两部分：**A) 补完功能**，**B) 修复被中断处留下的缺陷**。
第二部分远比第一部分重要 —— 那份"半成品"能编译、能让 815 条单测全绿、
能让 M1 与 UI 门禁全绿，**只有 M2 门禁炸了**，而它的报错指向的是渲染循环。

---

## 2. 交付内容（M2.1-A ~ M2.1-E）

### A. 弹药口径：后备无限（Combat Prototype 专用）

- `GunState` 增加 `reserveInfinite()`，`forPistol()` / `combat.gunFor(player)` 默认无限后备。
- **M2 的有限后备语义没有被删掉**，而是保留在 `forPistolWithFiniteReserve()` 里 ——
  刻意保住它是因为 PRD §5.4.3 的规则②③（"部分填充 `load = min(容量−弹匣, 后备)`"、
  "后备为 0 时按 R 拒绝"）必须仍然**有活代码承载**，否则它们会在 M3 恢复有限后备时
  变成一段没人验证过的历史。单测仍在演练这两条规则。
- HUD 显示 `12 / ∞`，由 `hud.reserveInfinite` 驱动。
- **开局弹匣仍为空、仍需按 R**（这是 v0.3.2 的既定口径，未改动）。

### B. 枪感

| 要素 | 实现 | 落地位置 |
|---|---|---|
| 枪口火光 | 世界空间亮方块，0.05 s，边长 **0.13** 格（D9 修正：生在**眼睛前方 0.55 格、右下偏移**的枪口，**不再生在眼睛处**）；alpha 0.90→**0.55**，颜色由近白改**暖橙** `(1.00,0.84,0.52)` | `CombatFxModel.spawnMuzzleFlash` → `CombatFxRenderer` 第三个 pass |
| 更亮更粗的曳光 | 半宽 0.015 → **0.040 格**，alpha 0.55 → **0.85** | `CombatFxRenderer.TRACER_HALF_WIDTH / TRACER_ALPHA` |
| 撞击粒子 | 方块命中 5 个 / 怪物命中 6 个，颜色取被击中物的本色 | `spawnBlockHit` / `spawnEntityHit` |
| 命中标记 | 0.18 s 时间线，**重复命中 = 刷新而不是叠加** | `spawnHitMarker` → `hud.hitMarker` → `HudRenderer` |
| 轻微后坐 | 单发 **0.9°**，上限 **1.8°**，线性回落 **5.0°/s**（0.18 s 精确归零） | `Camera.addRecoilPitch / decayRecoil` |

**后坐力最关键的设计取舍**：它**不改** `pitchDeg`、**不碰** `forward()`，
只作为独立的 `recoilPitchDeg` 参与视图矩阵。

- 代价：后坐力未回落的 0.18 s 内，"画面中心"与"射线中心"最多相差 1.8°。
- 收益：**只有"视图被顶了一下、而准星与子弹方向不动"，才能既给出击发反馈，
  又不让玩家因为打连发而真的瞄不准**（手不需要补偿，画面自己会回来）。
- 若把后坐力折进 `pitchDeg`，连发就变成"视角持续上飘"、玩家必须反向压枪 ——
  那是另一个游戏的玩法。回落用**线性**而非指数，是为了**精确到达 0**：
  留残差会让静止瞄准时画面永远差 0.05°，且无法归因。

### C. 怪物辨识度

`MonsterModel` 由"单盒体"改为 **8 个部件**（头 / 躯干 / 双腿 / 双臂 / 双眼）+ 步态与攻击动画：

- 行走起伏只向**下**（`bob ∈ [−0.03, 0]`），因此头顶点永不超过碰撞箱高度 1.80；
- 水平方向给 1.2 倍旋转容差并附了推导（方盒套方盒绕 Y 轴转必然在四角鼓出，属几何必然，非缺陷）；
- 垂直方向**一个都不给**：头顶点超过 1.80 就是"能打到看起来没东西的位置"，属红线；
- 攻击时躯干前冲 0.03 + 手臂前摆 0.08；
- 受击闪白**8 个部件全混**（不只是躯干），alpha 恒为 1。
- 碰撞箱与 M2 完全一致（宽 0.6 / 高 1.8），**击杀所需发数不变（3 发）**。

**配色（C7 已裁决关闭）**：由"挤在极暗带的一块深红"改为 **中亮区间的四级明暗台阶**，
色相仍偏红（`r > g`、`r > b`，保留"红 = 危险"的读法），上界刻意留在天空色之下。
Rec.709 luma（`0.2126R + 0.7152G + 0.0722B`；本项目不做 sRGB↔线性转换，实体顶点色直接写进
帧缓冲，故直接算在颜色分量上）：

| part | 旧 RGB | 旧 luma | 新 RGB | 新 luma |
|---|---|---|---|---|
| 头 HEAD | `0.46, 0.17, 0.20` | 0.234 | `0.92, 0.46, 0.38` | **0.552** |
| 躯干 TORSO | `0.33, 0.13, 0.16` | 0.175 | `0.78, 0.39, 0.33` | **0.469** |
| 臂 ARM | `0.25, 0.11, 0.14` | 0.142 | `0.64, 0.32, 0.27` | **0.384** |
| 腿 LEG | `0.19, 0.10, 0.12` | 0.121 | `0.50, 0.25, 0.21` | **0.300** |
| 眼 EYE | `1.00, 0.84, 0.30` | 0.835 | `1.00, 0.84, 0.30`（**未改**） | 0.835 |

- 旧配色四个体色 luma **全部 < 0.28**（极暗带），最小相邻落差仅 **0.021**；新配色全部落进
  **[0.28, 0.58]**、最小相邻落差 **0.083** —— 八件身体因此靠"结构 + 明暗台阶"在远处也能读出
  剪影，而无须改成灰色。天空色 luma ≈ 0.610 仍高于所有体色，怪物在天空背景上仍是"比天空暗的一块红"。
- **裁决**：保留红色（greybox = 占位美术 + 高辨识度），**不改成灰色**；清单第 C7 项据此
  标注为"已裁决关闭"。若实机里仍觉得灰色更好，那属于**独立的美术需求偏差**，请提出。
- 该配色的三条量化判据（中亮区间 / 相邻落差 / 眼神明显更亮）由 `MonsterModelTest` 钉死，
  且**可证伪**：把 `COLOR_EYE` 调暗到 `{0.80, 0.70, 0.28}`（luma 0.691）会让"眼睛明显更亮"
  立刻变红（实测差 0.139 < 判据 0.25），复原后转绿 —— 见 §4.6。

> **登记事实（不是本轮引入）：实体完全不接收光照。** `EntityRenderer.buildMonsterVertices`
> 给每个顶点写 `brightness = 1.0f`、`uAlpha = 1.0`，**绕开了地形那套
> `face.shade() × lights.shadeFactor()` 的明暗路径**。因此上面的 luma 就是像素最终呈现的明度，
> 不会被面明暗再压暗一次；怪物在背光 / 洞穴里也不会变暗。**本轮不改它**，作为已知属性登记在
> **§7（T8）**，供 M3 决定是否给实体接入光照。

### D. 第一人称手持物（viewmodel）

独立渲染 pass，**复用 `voxelShader` + 自行清一次深度**（`ui.vert` 是 NDC 直通、无矩阵
uniform，HUD 层画不了 3D，所以它不能挂在 HUD 上）。三种形态：枪 / 方块 / 空手。
动画：idle bob、开火后坐、切换槽位弹跳、挖掘挥动、换弹下压、ADS 向中心收拢。
由单测在**整条动画包络上**扫描两条硬约束：**永不遮挡准星**、**永不跑出右半屏**。

### E. 最小音频链

`audio` 包 8 个文件（`AudioManager` / `AudioFeedback` / `OpenAlBackend` / `PcmSynth` /
`RecordingAudioSink` / `AudioEvent` / `AudioBackend` / `AudioEventSink`），
`lwjgl-openal` 依赖（API + `natives-windows` 各一条）。

- **五个占位音**：`gun_fire` / `gun_empty` / `reload` / `hit_enemy` / `player_hurt`，
  由 `PcmSynth` 程序化合成（不引入任何音频资产）。
- **换弹音放在"按下 R 并被受理"那一刻**，而不是 1.2 秒后完成时 ——
  按键必须立刻有反应，延迟 1.2 s 才响的东西反馈的是"换弹做完了"，
  它错过了最需要确认的瞬间（"我的按键被受理了吗"）。
- **降级是设计里的头等公民**：无声卡 / 原生库没链上 / 设备被占用 → 记一条 WARN，
  播放侧摘掉，**事件照常记进 audit sink**。于是日志里的 `gun_fire=17` 证明触发链是通的，
  而 `player_hurt=0` 是一个可以立刻追问的事实 —— 两者不会再混成一句"我听不到声音"。
- 音量：主音量 × 音效音量**相乘**（"总闸 × 分项"的准确实现），接设置菜单两个滑杆。

---

## 3. 本阶段修掉的缺陷（首轮 8 处 D1–D8；补充 2 处 D9/D10；第三轮 1 处 D11）

> 全部是"**已定义、从未被调用/赋值**"型死代码，症状一致：**编译通过、单测全绿、门禁全绿**。

| # | 缺陷 | 症状 | 根因 | 现在的守门人 |
|---|---|---|---|---|
| **D1** | `CombatFxRenderer.init()` 从未分配 `tracerVao/tracerVbo/tracerStaging` | **门禁 m2 在 5.6 s 崩溃**，`upload()` 里 NPE（堆栈指向渲染循环，离真因隔了三层）；自测只跑到第 31 条断言 | 加入"枪口闪光"那一组时，把**曳光的分配整段顶掉**了。`dispose()` 释放三组、`init()` 只分配两组 —— 这个**不对称**就是指纹 | `CombatFxRendererLifecycleTest`（4 条结构断言） |
| **D2** | 整个 `audio` 包（8 文件 ≈1500 行）**完全未接线** | 无任何声音，且日志中计数全 0 | 主类从不创建 `AudioManager`、从不把 `AudioFeedback` 串进监听链、从不施加音量、从不 poll 玩家受伤 | M2 自测 6 条音频断言 + `AudioFeedbackWiringTest` |
| **D3** | `hud.reserveInfinite` 声明并被 `HudRenderer` 读取，**从未被赋值** | HUD 永远不会显示 `12 / ∞` | 与 D4~D8 同一类 | M2 自测（HUD 弹药口径） |
| **D4** | `spawnMuzzleFlash` 从未被调用 | **没有枪口火光** | 只写了 API 与注释，没接线 | M2 自测 `muzzle_flash ≥ 3` |
| **D5** | `spawnHitMarker` 从未被调用；`hud.hitMarker` 从未被赋值 | **没有命中标记** | 同上 | M2 自测 `hit_marker ≥ 3` + 与溅射数一致 |
| **D6** | `spawnEntityHit` 从未被调用 | **命中怪物没有溅射粒子** —— 而 `onEntityHit` 的注释里**已经写着**"溅射粒子承担即时反馈" | 注释描述了一条行为，但**没有登记成断言** | M2 自测 `entity_hit_burst ≥ 3` |
| **D7** | `Camera.addRecoilPitch` 从未被调用 | **完全没有后坐力**（`recoilPitchDeg` 恒为 0） | 同上 | M2 自测"观测峰值 > 0" |
| **D8** | `Camera.decayRecoil` / `clearRecoil` 从未被调用 | 若只修 D7，后坐力会**永久不回落**（这正是"只补一半"的典型后果） | 同上 | M2 自测"收尾精确回落到 0" + 重生清后坐 |
| **D9** | 枪口偏移 `MUZZLE_*` **只写在注释里、代码里不存在**；`spawnMuzzleFlash` 收到的是**眼睛**坐标 | **枪口火光刺眼**：0.16 格的闪光生在眼睛处，相机被吞进立方体内部，关闭背面剔除后内部面被光栅化成一片盖住准星的白 | 三处代码注释都写"见 SkyIslandGame 的 `MUZZLE_*` 偏移"，但那个常量从未被写下 —— 于是相机就在闪光里 | M2 自测"闪光距眼睛 ≥ 0.40 格" + "闪光在眼睛前方"（新增 2 条） |
| **D10** | F4 刷怪用 `Player.findNearestStandable` 找落点 —— 它**只沿同一 y 平面**做水平搜索 | **生成的怪物找不到**：玩家站在自建高塔（y≈71）上按 F4，怪被留在半空、自由落体到塔底 —— 玩家往下 7 格、往外 15 格，近战怪不会跳也不会寻路 | 调用点注释写"向下最多找 6 格"，但代码里**根本没有竖直搜索**（且实参是 3 不是 6）—— 注释描述了一条不存在的行为 | M2 自测 `verifySpawnGrounding`（新增 5 条） |
| **D11** | 近战攻击判定 `MeleeMonster.tick` **只有水平分量**（`horizontal = hypot(dx,dz)`），咬击条件缺竖直项 | **隔空咬人**：怪站在塔底（水平 ≈ 0.2 格）、玩家站在 7 格高的台上，照样被连咬致死 —— 首轮试玩"看不见怪却一直掉血"的现场 | `ATTACK_RANGE` 的 javadoc 只按"碰撞箱半宽 0.3 + 玩家半宽 0.3 + 容差"推导，是**纯水平口径**；判定里从未出现 y 项 | `EntityCombatTest.monsterCannotBiteAPlayerFarAbove`（旧代码必红）+ M2 自测 `verifyMeleeVerticalGate`（新增 6 条） |

#### D10 的日志证据（`logs/skyisland-20260922.log`）

- `01:19:39` 玩家在**高塔上**按 F4；周期性进度行在 `01:20:25` 报
  `Player(-9.03, 71.00, -5.82 vy=0.00 onGround=true)` —— 玩家站在 y≈71。
- 怪物 #1 生成于 `(-8.50, 70.00, -12.50)` —— **半空**，比地表（y=64）高约 6 格。
- 收尾摘要 `entities_alive=2, entities_total_spawned=2, entities_total_removed=0`
  —— 一只都没丢。"找不到了" = **够不着 + 看不见**，不是"被删除"。
- 玩家随后在 `01:20:27` 生命耗尽死亡（音频摘要 `player_hurt=5`），与"怪一直在正确工作"一致：
  怪落到塔底后就在原地（近战怪不会跳 / 不会寻路），玩家不下来就永远见不到它。
- 对照组：第二次生成于 `(0.63, 64.00, -10.19)`、落在地面上，表现正常 ——
  缺陷只在"玩家高于地表 3 格以上"（高塔 / 高台 / 山丘）时暴露。

**另外三处编译/日志级缺陷**：

- `PcmSynth.java` 把局部变量命名为 **`transient`**（Java 保留字）→ 连锁 **104 个编译错误**，
  后续整段被判定为"方法外部语句"。
- `OpenAlBackend.java` 缺 `AL_FALSE` 的静态导入。
- `applySettings()` 的日志**仍写着**"无音频后端，暂不产生听觉效果"（陈旧文案，
  会让"设置没生效"与"设置生效了但没声卡"无法区分）。现在它会同时报出
  **音频侧实际持有的增益**与**播放侧状态**。

### 为什么这批缺陷能一起活下来：三条"看起来成功"的读数

D4~D8 各自都有一个**看起来正常的邻近读数**，使既有断言照样通过：

- `totalSpawnCalls > 0`（曳光与破坏粒子还在正常累计）→ "表现层收到了事件"照样绿；
- `totalBreakParticles > 0` → "粒子系统在工作"照样绿；
- 自测的 SHOOT_KILL 阶段仍然逐发命中并致死 → "战斗闭环"照样绿。

**缺的那几条根本没有对应断言**，而不是断言判错了。因此本轮补的 7 条断言全部写成
**与播放环境无关的计数/峰值断言**，并且只有"接线真的存在"才能变绿。

---

## 4. 门禁与测试

### 4.1 单元测试

| 项 | 结果 |
|---|---|
| 用例总数 | **821**（首轮 818 → 第三轮 821） |
| 失败 | **0** |
| 本轮新增（第一轮） | `CombatFxRendererLifecycleTest`(4)、`PcmSynthTest`、`AudioManagerTest`、`AudioFeedbackWiringTest`、`RecordingAudioSinkTest`、`VersionTest` |
| 本轮新增（第二轮） | `MonsterModelTest` 的 3 条配色判据（中亮区间 / 相邻落差 / 眼神明显更亮） |
| 本轮新增（第三轮） | `FallDamageTest` 新增 1 条（`fallDamageActuallyReducesHealthAndIsAttributedToFall`）+ 5 条既有用例补上**真实掉血**断言；`EntityCombatTest` 新增 2 条（`monsterCannotBiteAPlayerFarAbove` / `monsterStillBitesAtSameLevelAndOneBlockStep`）→ 合计 **+3 用例**（818 → 821） |
| 被改写的既有用例 | `CombatCoreTest`(3)、`CombatControllerTest`(3)、`M2CombatSelfTest`(3 处) —— 它们断言的是**旧口径的有限后备语义** |

**关于 `CombatFxRendererLifecycleTest`（本轮最有价值的一条护栏）**：
它不写"某个缓冲非空"这种一次性补丁，而是断言三条**与具体组数无关**的结构性质，
因此以后再加第四组特效时仍然有效：

1. **有几组 VBO，就必须有几组暂存缓冲** —— 反射数 `*Vao` 与 `FloatBuffer` 字段的个数；
2. **分配后一组都不能缺** —— 遍历所有 `FloatBuffer` 字段报出为 null 的名字；
3. **容量必须来自它自己那条常量** —— 防另一半错误：把粒子容量错写成曳光的
   （缓冲大小对不上**不会报错**，只会在特效达到上限时静默截断）。

这需要把分配/释放从 `init()` / `dispose()`（含 GL 调用、无头跑不了）里**拆成纯堆外内存方法**，
否则"缓冲是否配齐"永远只能靠"开个真窗口打一枪"来发现 —— 而这次正是这样漏过去的。

### 4.2 三门禁（从冻结 jar 启动，每次开全新带时间戳的空存档目录）

证据目录：`tmp/gate-runs/20260922-031147/`（**第三轮**）。

| 门禁 | 结果 | 关键读数 |
|---|---|---|
| M1 功能 | **PASS=27 FAIL=0** | `m1_functional_closure = true` |
| M1.5 UI | **PASS=60 FAIL=0** | `ui_selftest_failures = 0` |
| M2 战斗 | **PASS=135 FAIL=0** | `m2_combat_closure = true`，`m2_selftest_failures = 0`，`m2_selftest_assertions = 135` |

**`m2_selftest_assertions` 的变化本身就是证据**：
**107（M2 基线）→ 114（补音频）→ 121（补表现层）→ 128（补 D9/D10 缺陷回归）→ 129（补伤害来源归因）→ 135（第三轮：补 D11 竖直判定 + 咬击几何）**，
且新增的 28 条全部 PASS。

> **为什么必须看断言条数**：M2.1 被打断的那一版里，门禁 m2 **也是"跑起来了"的** ——
> 它在 5.6 秒时崩溃，只跑到第 **31** 条断言。如果只看"有没有输出"或"有没有 FAIL 字样"，
> 会把它误读成"跑完了、只是有点红"。**"绿"必须能解释自己为什么是绿的。**

### 4.3 音频后端：这台机器上真的初始化成功了（不是降级路径）

```
[音频] OpenAL 就绪（设备=OpenAL Soft），source 池=8，已上传=11 个音
[音频] 会话事件计数：总计 144 次（gun_fire=17, gun_empty=119, reload=3, hit_enemy=4, player_hurt=1)
```

- 设备与 `source` 池是真实创建成功的，因此**播放路径真的被走过了**，不是"抓 Throwable 降级"。
- 原生库确实进了 fat jar（不是只在编译期可达）：
  `windows/x64/org/lwjgl/openal/OpenAL.dll` + `org/lwjgl/openal/**` 全套 API。
- `gun_empty=119` 远大于其余四项，是因为 DRY_FIRE 阶段**每个逻辑步**都产生一次空枪事件；
  这是阶段定义的直接后果，不是异常。

### 4.4 CJK 点阵字形

**1478 个字形，0 空白，0 溢出**，34 个降号重烘（12px 装不下，按 11/10 px 重烘）。
`CjkFontTest` 会扫描 `src/main` **与** `src/test` 的**全部文本（含注释）**，
因此**字形生成器必须是所有源码改动的最后一步**。

> **这条护栏已经连续三轮真的推动了一次重烘**：
> - 第一轮修 D9/D10 时往注释里加了一个"陈"字，门禁第一次运行就红在
>   `everyNonAsciiCharUsedInSourcesHasAGlyph`（报 `陈(U+9648)`），重跑生成器后 1469 → **1476**。
> - 第二轮加伤害来源仪器（注释/日志里出现"摔落"）又引入 **1** 个新字 **`摔`(U+6454)**，
>   重跑生成器后 1476 → **1477**。
> - 第三轮给 `ATTACK_VERTICAL_RANGE` 写推导 javadoc（"玩家站在**旁边**一格高的台阶上"）
>   引入 **1** 个新字 **`旁`(U+65C1)**，`mvn` 第一次构建即红
>   （`CjkFontTest.everyNonAsciiCharUsedInSourcesHasAGlyph:71 expected:<true> but was:<false> 旁(U+65C1)`），
>   重跑生成器后 1477 → **1478**（1478/0 空白/0 溢出）。
> 这正是"生成器必须是最后一步"的意思：它不靠人记得，靠测试记得 ——
> 而且它**真的又拦下了一次**，否则 `旁` 会以空白方块出现在 HUD 上。

### 4.5 D9/D10 的新断言：逐条证明它们能失败

新增的 7 条断言**不是"看起来更顺"的措辞**，而是"旧代码必红"。做法：把产品行为
临时还原成修复前的样子（枪口 = 眼睛、跳过接地吸附）再跑一次 M2 门禁 ——
**5 条红，且 `m2_combat_closure = false`**（`m2_selftest_failures = 5`）：

| 新断言 | 旧代码上的读数 | 判定 |
|---|---|---|
| 枪口闪光距眼睛 ≥ 0.40 格 | 距眼睛 **0.0000** 格 | 必红 ✅ |
| 枪口闪光在眼睛前方（点积 > 0） | 前向点积 **0.0000** | 必红 ✅ |
| 高于地表 8 格刷怪：怪站在合法落脚点 | 怪 **(0.50, 73.99, 0.46)**，`isStandingSpotValid=false` | 必红 ✅ |
| 下方无地面时刷怪返回 `null` | 竟生成了 **(4.50, 73.99, 4.46)** 的悬空怪 | 必红 ✅ |
| 下方无地面时实体数不变 | `alive 2→3`，`spawned 4→5` | 必红 ✅ |
| 高于地表 8 格刷怪：怪不为 `null` | 旧代码也返回一只（只是悬空）—— 本条是**陪衬断言**，用来保证下一条"站在落脚点"有意义 | 不因 D10 而红（如实登记） |
| 高于地表 8 格刷怪：实体计数 +1 | 同上：旧代码也会 +1 | 不因 D10 而红（如实登记） |

> 只剩两处旧代码能"碰巧满足"的断言（它们只是确认"确实生成了"），
> 其余 5 条都只有在**接地逻辑真的生效**时才会绿 —— 这就是"修好了"与"看起来像修好了"的区别。

### 4.6 第二轮新增断言：逐条证明它们能失败

第二轮新增 1 条自测断言 + 1 条单测判据，同样**逐条做了等价回退实验**，确认不是"看起来更顺"的措辞：

| 新断言 | 所属 | 回退方式 | 回退后的读数 | 判定 |
|---|---|---|---|---|
| 玩家受伤来源已归因（DEATH_AND_RESPAWN → 来源 = 虚空 `VOID`） | M2 自测（`m2_selftest_assertions` 128 → **129**） | 把 `Player.die()` 里"虚空写 `VOID`"那行改回不写（模拟旧代码不记录来源） | `lastDamageCause=null` → **FAIL**；`m2_selftest_failures=1`、`m2_combat_closure=false` | 必红 ✅ |
| 眼睛明显亮于任何体色（`eyesStayClearlyBrighterThanAnyBodyPart`） | 单测 `MonsterModelTest` | 把 `COLOR_EYE` 调暗成 `{0.80f, 0.70f, 0.28f}`（luma 0.691） | 差值 **0.138916** < 判据 0.25 → **FAIL**（`AssertionFailedError … expected: <true> but was: <false>`，`MonsterModelTest:314`） | 必红 ✅ |

- 两条都用**带 `finally` 复原**的脚本执行，跑完即删；复原后 `Player.java` / `MonsterModel.java`
  与改动前**逐字节相同**（脚本自检 `restored_byte_identical=true`），并各重跑一次确认转绿。
  证据：源断言回退 `tmp/nc_src_out.txt`，眼睛断言 `tmp/nc_eye_out.txt`。
- **为什么眼睛那条必须能证伪**：旧判据 `eyesAreMuchBrighterThanAnyBodyPart`（`eyeLum > 2.5 × bodyLum`）
  在体色抬进中亮区间后**数学上不可能成立**（眼睛 luma 上限 1.0，要凑 2.5 倍需体色 ≤ 0.4，
  而那正是本里程碑要修掉的极暗带），于是被放宽成"绝对差 ≥ 0.25 且 ≥ 1.3 倍"。
  **放宽的断言如果不能再失败，就只是装饰** —— 上表证明它仍然能红。

#### 表现层监听器仍是匿名内部类的债（T1 的一个具体后果）

D9 的断言之所以读 `CombatFxModel` 的"生成当刻留档"（`MuzzleFlashSample`）而不是直接读
`player.eyePosition()`，是因为闪光只活 3 帧、收尾时早已清空。这又一次说明
**表现层接线（`combatFeedback` 匿名内部类）单测够不到**，只能靠门禁自测 —— 见第 7 节 T1。

### 4.7 第三轮新增断言：逐条证明它们能失败

第三轮新增 3 条用例（+5 条既有用例补断言）+ 6 条自测断言，**全部做了等价回退实验**。
做法：把两处行为用**带 `finally` 复原**的脚本临时还原成"修复前"（`tmp/nc_revert.js`，
跑完即删），跑针对性单测 + M2 门禁，再复原。复原后 `Player.java` / `MeleeMonster.java`
与改动前**逐字节相同**（脚本自检 `restored_byte_identical=true`，见 `tmp/nc_restore_check.txt`）。
回退不是改措辞，是**把行为改回去**：

- **回退 A（T7）**：把 `Player.updateFallState` 里的落地 `hurt(...)` 调用去掉 → 回到"算完不施加"。
- **回退 B（缺陷 B）**：把 `MeleeMonster` 的竖直门 `Math.abs(dy) <= ATTACK_VERTICAL_RANGE` 置为 `true`
  → 回到"水平够近就咬"（保留几何仪器，使新用例仍可编译）。

| 新断言 | 所属 | 回退后的读数 | 判定 |
|---|---|---|---|
| `fallingFourBlocksDealsOneDamage`：坠落 4 格生命**真的降 1** | `FallDamageTest` | `expected: <1> but was: <0>`（生命 20，期望 19） | 必红 ✅ |
| `fallingFiveBlocksDealsTwoDamage`：坠落 5 格生命**真的降 2** | `FallDamageTest` | `expected: <2> but was: <0>`（生命 20，期望 18） | 必红 ✅ |
| `damageIsSettledOnlyOnLanding`：6 格坠落**恰好扣 3 且只扣一次** | `FallDamageTest` | `expected: <17> but was: <20>` | 必红 ✅ |
| `fallDamageActuallyReducesHealthAndIsAttributedToFall`：真扣血**且来源=FALL** | `FallDamageTest` | `expected: <1> but was: <0>` | 必红 ✅ |
| `monsterCannotBiteAPlayerFarAbove`：玩家高出 7 格不得被咬 | `EntityCombatTest` | `expected: <20> but was: <4>`（旧代码连咬 4 口：20→4） | 必红 ✅ |
| `玩家高出 7 格：生命未被咬伤` | M2 自测 | `health 20→4`（期望不变） | 必红 ✅ |
| `玩家高出 7 格：攻击计数为 0` | M2 自测 | `attackCount=4`（期望 0） | 必红 ✅ |

- 回退 A + B 一起跑 `FallDamageTest,EntityCombatTest`：**Tests run: 32, Failures: 5**。
- 回退 B 跑 M2 自测：**`m2_selftest_assertions=135`、`m2_selftest_failures=2`、`m2_combat_closure=false`**
  （exit code = 1，自测脚本自己把"判定为失败"写进日志）。
- 证据文件：`tmp/nc_unit_out.txt`（单测）、`tmp/nc_m2_out.txt`（M2 自测）、`tmp/nc_restore_check.txt`（逐字节复原）。

> **没在上面表里的两条新断言是"陪衬"，如实登记**：
> `fallingThreeBlocksDealsNoDamage`（3 格不掉血）、`jumpingAndLandingDealsNoDamage`（原跳不掉血）、
> `steppingDownStairsDoesNotAccumulate`（分段台阶不掉血）在旧代码上**也会绿** ——
> 它们的作用是**钉住下限**，即防止把"落地一律扣血"误当修好。
> 同理 `monsterStillBitesAtSameLevelAndOneBlockStep`（同层/一级台阶仍能咬）在旧代码上也绿 ——
> 它是**防过度收紧**的反向断言：修"隔空咬人"不能变成"贴近也咬不到"。

---

## 5. 人工试玩 Gate：**未执行**

按 spec，M2.1 的最后一个关口是 **≥ 10 分钟的真人在键鼠下的试玩**。
自测绕开了 `OS → GLFW` 这一段（本机合成键鼠送不到窗口，见 TECH_DESIGN_v0.1.1 §T′ TR7），
因此"真实鼠标的手感"只能由人来看。

- 启动：桌面 **「SkyIsland M2 一键启动.bat」**（转发到 `F:\minecraftspace\play-m2.bat`）
- 世界：`m21-play`（全新存档目录，会重新发放开局装备）
- 清单：[`M2_1_PLAYTEST_CHECKLIST.md`](./M2_1_PLAYTEST_CHECKLIST.md)
- **请格外用力看 B1 / B4 / B5、C7，以及第三轮新增的 F1 / F2** —— 就是上面 D4~D8、配色偏差，
  以及本轮 T7 坠落伤害 / 缺陷 B 近战竖直判定那几处。

**判定**：第 1 节全部条目要么打勾，要么被明确记为"环境限制"并给出替代证据。
任何**看得见但不符合描述**的现象都请记下"操作 + 看到的现象 + 期望的现象" ——
前三轮试玩里最值钱的两条缺陷都是靠"我明明看到它不对"抓出来的。

---

## 6. Scope 边界（本阶段刻意未做）

以下均**未触碰**，与 spec 的禁止清单一致：第二把枪、第二种怪、Crafting、资源岛、
生存经济、昼夜、正式 UI 重做、正式美术、完整音频资产、正式粒子艺术化、第三人称模型。

---

## 7. 技术债与顺延项（**不得静默缩小**）

| # | 项 | 说明与建议 |
|---|---|---|
| **T1** | 表现层监听器是**匿名内部类**，不可单测 | `combatFeedback` 是 `SkyIslandGame` 的匿名内部类，单测够不到它 —— 因此本轮只能靠门禁自测的计数断言兜住 D4~D8。**建议 M3 抽成具名的 `CombatFxFeedback`**（照 `AudioFeedback` 的样子，它正是因此才可单测），把这条从"只能靠门禁"变成"单测 + 门禁" |
| **T2** | `com.skyisland.player` **没有测试包** | `Camera` 的后坐力力学（1.8° 上限、NaN 拒绝、单调回落、精确归零）目前只被门禁自测覆盖，没有单测。建议补 `CameraRecoilTest`，重点断言**后坐力不改变 `pitchDeg` / `forward()`**（这条设计取舍最容易被"顺手修好"而破坏） |
| **T3** | `RecordingAudioSink` 无上限地保留每条事件 | 保留顺序是刻意的（"先空仓后开火"这种次序错乱是计数看不出来的）。代价约为 8 字节/事件 → 持续开火 1 小时约 115 KB，M2.1 判定为可接受 |
| **T4** | `AudioFeedback.resetHealthBaseline()` 已实现但**没有调用点** | 因为 `Player` 只在启动期创建一次（唯一读档路径在启动期），基线不可能变陈旧。**保留给 M3**（届时会有运行时读档 / 传送） |
| **T5** | `MonsterModel.color()` 返回内部常量数组的引用 | 已是文档化约定（调用方只读、不得持有），与 `HudRenderer.iconColor` 同口径 |
| **T6** | `play-m2.bat` 要求 `target\` 下**恰好 1 个 jar** | 多于 1 个（例如忘了 clean）会明确报错并退出，不会静默挑一个旧的 |
| **T7** | ~~`Player.fallDamageFor()` 算出的坠落伤害**从不施加到生命值**~~ | **✅ 已修（第三轮）**：`Player.updateFallState` 现在在**落地事件**上调用 `hurt(world, lastFallDamage, DamageCause.FALL)`，每次落地只结算一次（落地后 `fallDistance` 清零，且该分支只在"空中 → 站立"这一次跃迁进入）。**未改** `FALL_DAMAGE_THRESHOLD` 与 `fallDamageFor` 公式（仍与 PRD 5.3 逐字一致）；虚空死亡仍走 `die()`（不按普通坠落结算）。原先"818 条全绿而机制不存在"的根因是**测试只读 `lastFallDamage()` 这个中间量**，本轮已把断言落到 `health()`。证伪见 §4.7 |
| **T8** | **实体完全不接收光照** | `EntityRenderer.buildMonsterVertices` 恒定写 `brightness = 1.0f` / `uAlpha = 1.0`，**绕开地形那套 `face.shade() × lights.shadeFactor()`**。怪物在背光 / 洞穴里也不会变暗，其顶点色即最终像素值（这也是 §2-C 的 luma 能被直接当作屏幕明度的原因）。**本轮不改它**，由 M3 决定是否给实体接入光照 |

### M3 需要接手的既有顺延项（继承自 M2，未静默缩小）

G2 音效、G3 粒子占位规格、G4 昼夜/天数 HUD、G7 蹲下、G8 `saveVersion`、G9 开局弹匣为空、
**G11 试玩窗口下 2 次无归因的 >50 ms 尖峰（需对照跑）**。

> 其中 **G2「音效」在本轮被实质推进**（M2.1 已交付 5 个占位音与整条链路），
> 但**并未关闭**：M3 仍要做正式音量分组、距离衰减与音效资产。

---

## 8. M3 Readiness

| 项 | 状态 |
|---|---|
| 版本号 / `version.properties` / `Version.java` / 启动横幅 | 已升至 `0.3.1-M2_1-COMBAT-FEEL`，并有 `VersionTest` 防止将来漂移 |
| 冻结 jar 与门禁脚本 | 已更新；`run_frozen_gate.js` 改为**按 `target\skyisland-*.jar` 解析**而不是硬编码文件名（否则下次升版本会**静默用旧 jar 跑门禁**） |
| 启动器 | `play-m2.bat` 已对齐 M2.1 口径（旧头部曾写着"弹药不会恢复、只有 24 发"，对无限后备是**主动误导**） |
| 弹药回溯点 | M3 恢复有限后备时，只需把默认构造从 `forPistol()` 切到 `forPistolWithFiniteReserve()`，规则②③的代码与单测都还在 |
| 音频回溯点 | 播放侧可整体缺席、可 `-Dskyisland.audio=off`、可注入 `forwardSink` |
| HUD 口径 | `12 / ∞` 由 `hud.reserveInfinite` 单一来源驱动，M3 撤回时只改一个赋值 |
| 表现层 | 三组特效缓冲 + 三条 pass，有结构断言防止再加一组时漏分配 |
| 已知风险 | T1（表现层无法单测）是 M3 最值得先还的债：M3 的内容量更大，同一类"写了没接线"的缺陷还会再来 |

---

## 9. 收尾

- **脚本侧**：全部通过（**821** 单测 / 0 失败；三门禁 PASS=27 / 60 / **135**，FAIL 全为 0）。
  证据目录：`tmp/gate-runs/20260922-031147/`（M1 27/0、UI 60/0、M2 **135**/0，`m2_combat_closure=true`）。
  冻结检查：`target/skyisland-0.3.1-M2_1-COMBAT-FEEL.jar` 的 mtime **严格晚于** `src/main` + `src/test` 全部文件
  （`tmp/freeze_check.txt`）。
- **人工侧**：**待你试玩 ≥ 10 分钟**（清单见上；第三轮新增 **F1 / F2** 两条）。
- **第一轮追加**：修复首轮试玩报出的 **D9**（枪口火光刺眼）与 **D10**（F4 刷怪落点半空），
  并补 7 条"旧代码必红"的断言；`m2_selftest_assertions` **121 → 128**。
- **第二轮追加**：关闭 **C7**（怪物配色裁决，保留红系 + 抬高/拉开明度台阶）；
  给 `Player` 加 **伤害来源归因**（近战 / 坠落 / 虚空 / 其它）与一行归因日志，
  并补 1 条"旧代码必红"的自测断言（`128 → 129`）+ 1 条"仍可证伪"的单测判据（§4.6）。
  **纯仪器化，未改任何伤害数值 / 阈值 / 冷却 / 死亡判定。**
- **第三轮追加（本提交）**：修 **T7 坠落伤害**（落地结算 `hurt(..., FALL)`，每次落地一次）与
  **缺陷 B / D11 近战竖直判定**（新增 `ATTACK_VERTICAL_RANGE = 1.5`，只管攻击判定、不进 `chasing`），
  并补**咬击几何仪器**（每咬一行日志 + 两个可断言 accessor）。
  `m2_selftest_assertions` **129 → 135**；`FallDamageTest` 断言改落到 `health()`、`EntityCombatTest` 补竖直用例
  （单测 818 → 821）。三条核心新断言均以**回退实验**证明"旧代码必红"（§4.7），回退后逐字节复原。
  **两处都是玩法语义修复（用户授权），未改公式 / 阈值 / 冷却 / 距离常量。**
- **本阶段到此为止，未进入 M3。**

> M2.1 Combat Feel & Readability 已通过，等待 M3 Vertical Slice 指令。

---

# 附录：美术实现细节（owner：art-director / 林绘澄）

> 本附录是第 2 节 **C / D** 两个小节的**实现级**细节（精确结构、配色数值、容量推导、锚点与投影参数），
> 供 M3 接手与资产 / 规格对齐。**功能与验收结论以第 2 / 4 节为准，本附录不改变任何结论。**

## Monster Visual Upgrade

### 1. 部件结构 — 一个盒体 → 8 个 part（`render/entity/MonsterModel.java`）

| idx | 常量 | 局部 AABB（feet-center；z 负 = 正面） | 读出来的东西 |
|---|---|---|---|
| 0 | `PART_HEAD` | x `[-0.18, 0.18]`，y `[1.36, 1.80]`，z `[-0.18, 0.18]` | 头：比躯干窄，与躯干留 0.04 脖子缝 |
| 1 | `PART_TORSO` | x `[-0.22, 0.22]`，y `[0.66, 1.32]`，z `[-0.17, 0.17]` | 躯干 |
| 2 | `PART_LEG_LEFT` | x `[-0.20, -0.04]`，y `[0.00, 0.72]`，z `[-0.12, 0.12]` | 左腿 |
| 3 | `PART_LEG_RIGHT` | x `[0.04, 0.20]`，y `[0.00, 0.72]`，z `[-0.12, 0.12]` | 右腿（双腿间 0.08 缝） |
| 4 | `PART_ARM_LEFT` | x `[-0.30, -0.18]`，y `[0.72, 1.30]`，z `[-0.07, 0.07]` | 左臂（撑满脚印 ±0.30） |
| 5 | `PART_ARM_RIGHT` | x `[0.18, 0.30]`，y `[0.72, 1.30]`，z `[-0.07, 0.07]` | 右臂 |
| 6 | `PART_EYE_LEFT` | x `[-0.13, -0.05]`，y `[1.56, 1.68]`，z `[-0.21, -0.175]` | 左眼（只长正面） |
| 7 | `PART_EYE_RIGHT` | x `[0.05, 0.13]`，y `[1.56, 1.68]`，z `[-0.21, -0.175]` | 右眼 |

- 三个"一眼"：**是不是怪物** = 头/躯干/腿三段宽窄变化的类人剪影；**朝哪边** = 只有正面（−Z，从脸 `z=-0.18` 再凸出 0.03）有一对发光眼睛，背面什么都不加；**头/身/腿结构** = 三段独立尺寸 + 独立配色。
- 常量：`PART_COUNT = 8`、`FLOATS_PER_PART = 6`、`HALF_WIDTH = 0.30`、`HEIGHT = 1.80`。

### 2. 配色（主色砖红成阶；远距可读性口径）

| part | RGB | Rec.709 luma | 备注 |
|---|---|---|---|
| HEAD | `(0.92, 0.46, 0.38)` | ≈ 0.552 | 最亮体色 |
| TORSO | `(0.78, 0.39, 0.33)` | ≈ 0.469 | |
| ARM | `(0.64, 0.32, 0.27)` | ≈ 0.384 | |
| LEG | `(0.50, 0.25, 0.21)` | ≈ 0.300 | 最暗体色 |
| EYE | `(1.00, 0.84, 0.30)` | ≈ 0.835 | 高亮眼 |

- 体色在 luma 上拉成 **头 0.552 > 躯干 0.469 > 臂 0.384 > 腿 0.300** 的一级落差（最小相邻落差 ≈ 0.083），保证"分件"在任何距离都读得出 —— 此前八个 part 挤在 luma 0.12–0.23 的深红段，会并成一块影子（见任务 #10 / #11 与试玩清单 C7）。
- 主色仍满足 **r > g、r > b**（"红 = 危险"读法不变）；上界刻意留在天空色（luma ≈ 0.610）之下，怪物在天空背景下仍是"一块比天空暗的红"。
- 口径：管线不做 sRGB↔线性转换（无 `GL_FRAMEBUFFER_SRGB`、着色器无 gamma），顶点色即显示值，故 luma 直接按 Rec.709 系数算在颜色分量上；实体路径 `aColor.a`（亮度乘子）**恒为 1.0**，因此这些值不会被面明暗再压暗一次 —— **地形那套面明暗对实体不生效**。

### 3. 动态反馈（spec 允许的三条全做）

| 反馈 | 做法 | 不变量 |
|---|---|---|
| 行走起伏 | `bob = −0.03·(0.5−0.5cos 2φ)`，**只向下** `∈[−0.03, 0]`；相位来自**累计行走距离**（站住 → 相位停住，自动静止） | 头顶点 ∈ `[1.77, 1.80]`，**永不超过 1.80** |
| 攻击前冲 + 摆臂 | `s = attackSwing01`；躯干/头/眼 `dz = −0.03s`；双臂额外 `dz = −(0.03+0.08)s`、`dy = bob − 0.06s`；头 `dy = bob − 0.02s` | 最前端停在 `z = −0.24`（眼睛），仍在 ±0.30 内 |
| 双腿交替 | 左右腿 `dz = ±0.10·sin φ`（反相） | 腿在 ±0.30 内 |
| 受击闪白 | **8 个 part 全混**（含眼睛）向 `FLASH_R/G/B`，`aColor.a ≡ 1.0`；眼睛本就近乎全亮，闪白主作用在躯干四肢 | 整只怪一起亮 = "被打中"是一个整体信号 |
| 调试碰撞箱 | `static setDebugHitbox(boolean)`；`GL_LINES` 12 条棱（12×2 顶点，比 12 根细盒体便宜 18×），容量 = `MAX_ENTITIES` 个 AABB | 与实体同批量上限 |

- 朝向 `yaw` 取 `entity.facingDeg()`（与 `Camera` 同口径：`atan2(−dx, −dz)`），**"眼睛朝向"与"它朝谁走"是同一件事**。
- 为渲染加了三处实体状态挂钩（`entity/Entity.java`）：`facingDeg()` / `walkDistance()` / `attackSwing01()`（默认 0，`MeleeMonster` 覆写）。理由：这些是"表现用派生量"，放实体上比让渲染层反推 AI 内部计时器更稳，且不写进存档。

### 4. 碰撞箱不变式（本阶段最硬的一条）

- **逻辑碰撞箱一个字节没动**：`MeleeMonster` 仍 `halfWidth=0.3 / height=1.8`（`AABB.ofFeetCenter`，0.6×1.8×0.6），**击杀所需发数不变（3 发）**。
- 视觉 parts 并集 **恒 ⊂ 碰撞箱**：垂直方向 **0 容差**（起伏只向下，头永远 ≤1.80，红线）；水平方向给 `HORIZONTAL_ROTATION_TOLERANCE = 1.20`（±0.36）—— 因为**方盒套方盒绕 Y 轴转必然在四角鼓出**（撑满 ±0.30 的臂角点转 45° → √(0.30²+0.07²)≈0.308），属几何必然非缺陷；实测最坏 **0.3499 ≤ 0.36**。
- 取向：**宁可视觉略小于碰撞箱，绝不可视觉大于碰撞箱**（后者 = 打在"看起来没打到"的空气上，射击手感里最致命的一类不一致）。

### 5. `MAX_BOXES` 扩容量推导

每实体从"1 盒"变成"8 盒"，缓冲随之 ×8：

| 量 | 旧（M2 单盒） | 新（M2.1 八盒） |
|---|---|---|
| `MAX_ENTITIES` | 64 | 64 |
| `BOXES_PER_ENTITY` | 1 | `MonsterModel.PART_COUNT` = **8** |
| `MAX_BOXES` | 64 | 64×8 = **512** |
| `Boxes.FLOATS_PER_BOX` | 252（6 面×2 三角×3 顶点×7 float） | 252 |
| `CAPACITY_FLOATS` | 16,128 | 512×252 = **129,024** |
| VBO 字节 | ≈ 63 KiB | 129,024×4 = **516,096 B ≈ 504 KiB** |
| 调试线框容量 | — | 64×24×7 = 10,752 float |

- 512 盒 = "最多画 64 只满编怪"的硬上限；真超了说明刷怪逻辑出问题 —— 那时**少画一只**（`boxes + 8 > MAX_BOXES → break`）远好于越界写坏缓冲。
- 新增两个共享文件：`render/geom/Boxes.java`（纯函数盒写入 + 绕 Y 旋转，零分配）、`render/entity/MonsterModel.java`；改 `render/entity/EntityRenderer.java`（单盒回落保留给未知类型）、`entity/Entity.java`、`entity/MeleeMonster.java`。

## Viewmodel

### 1. 为什么是独立 pass（不能挂 HUD）

`ui.vert` 的属性只有 `vec2 aPos`、直接输出 NDC、**没有任何矩阵 uniform** —— 它画不了 3D。于是手持物**复用 `voxel` 着色器**（`aPos vec3 + aColor vec4`），自己设一套：`uProjection` = 窄 FOV 透视、`uView = 单位矩阵`、`uChunkOffset = (0,0)`。

- **投影**：`perspective(FOV 50°, aspect, NEAR 0.01, FAR 8)`。比世界 FOV（70°）窄，手持物不会被拉出夸张畸变；用**透视而非正交**，是因为"举枪时它离眼更近、看起来更大"正是玩家对枪械的空间直觉。
- **★ 先清一次 `GL_DEPTH_BUFFER_BIT`**：手持物离眼仅 0.75 m 却**不参与世界**；不清深度就会拿世界 pass 的深度做测试 —— 玩家贴墙时墙（<0.75 m）把手**整块切掉**，症状是"贴墙时手消失"，极难联想到深度缓冲。清完之后手持物独占深度区间。代价：手持物不再被地形遮挡（本来也不该被遮挡，它就长在眼睛上）。
- GL 状态（blend/cull/depth test/depth mask）进入前读、退出后原样写回（不重蹈 `CrackOverlay` 关掉剔除不恢复的坑）。
- `MAX_BOXES = 8`（枪 6 / 方块 2 / 空手 2，取 8 留余量）。

### 2. 屏幕布局（视图空间，单位米）

| 状态 | 锚点 `(x, y, z)` | 说明 |
|---|---|---|
| 髋射 HIP | `(0.235, −0.135, −0.75)` | 常态，屏幕右下 |
| 瞄准 ADS | `(0.13, −0.095, −0.80)` | 向中心收、略推远（更小、更不挡视线） |

- 基础朝向：`BASE_YAW 0.30`、`BASE_PITCH −0.05`、`BASE_ROLL −0.10`。
- **两条硬约束**（`ViewmodelRendererTest` 在整条动画包络上扫描断言，非肉眼）：
  1. 所有顶点 **恒在右半屏**（NDC `x > 0`）；
  2. 没有顶点落进**准星禁区**（`|NDC x|` 与 `|NDC y|` 同时 < 0.06 的中心方块）。
- ADS 的 `x` 只能收到 0.13、不能到 0：空手前伸手指 / 枪管沿 −Z 伸出十几厘米，绕 Y 轴的基础朝向会把这段长度换算成 −X 位移；锚点太靠中心时最左顶点会越过中线进禁区。0.13 是"够靠中心"与"绝不压准星"之间的实测平衡点。
- 另断言：不压底部 hotbar。

### 3. 三种形态与几何

| 形态 | `ViewmodelKind` | 盒体 | 内容 |
|---|---|---|---|
| 枪 | `GUN` | 6 | 机匣 / 枪管（更细长，第一识别特征）/ 握把 / 准星 / 握枪的手 / **枪口闪光** |
| 方块 | `BLOCK` | 2 | 0.115 立方体 + 托手 |
| 空手 | `EMPTY` | 2 | 手掌 + 前伸手指 |

- 枪口闪光**不是"一块黑的"**：`recoil01() < 0.05` 时整块几何被 `continue` 跳过（不写顶点）。因为 `voxel.frag` 里 `aColor.a` 是**亮度**而非不透明度，画一个亮度 0 的盒子会得到一块纯黑方块糊在枪口上。
- `ViewmodelKind.of(Item)`：gun→GUN；block/ammo/material→BLOCK；其余→EMPTY。方块 / 物品色从 `ItemStack` 取（ammo→暖黄，material→近黑）。

### 4. 动画（全部以**秒**推进，帧率无关；`dt` 夹到 0.1 s 防切后台瞬移）

| 动作 | 参数 | 触发 |
|---|---|---|
| idle bob | `BOB_HZ 1.1`（`bobX 0.010` / `bobY 0.014`，随 `moveSpeed01` 加幅） | 常驻 |
| use swing | `SWING_HZ 2.6`，最大 `0.55 rad` | 挖掘 / 放置 |
| fire recoil | `RECOIL_SECONDS 0.24`，指数回落；`RECOIL_Z 0.055` + `RECOIL_PITCH 0.34 rad` + 枪口闪光 | **用 `shotCount` 差值**触发（既不漏读渲染帧、也不像布尔那样需"消费"） |
| slot pop | `POP_SECONDS 0.18`，`POP_DROP 0.070` + `POP_SCALE 0.14` | 槽位 `lastSlot` 变化 |
| reload dip | `RELOAD_DROP 0.075` + `RELOAD_ROLL 0.42 rad`，进度由 `reloadProgress01` 驱动 | 换弹中 |
| ADS | `AIM_RATE_PER_SEC 14`（指数趋近），`aim01`：锚点 HIP→ADS、`yaw = BASE_YAW·(1−0.85·aim) + reloadRoll·0.35`、略增 scale | `player.isAiming()` |

- ADS 的 `yaw` 直线化（`1−0.85·aim`）是必需的：它让瞄准时**枪口指向屏幕中心**且最左顶点不越过中线 —— 这正是"永不压准星"能成立的原因。

### 5. 接线

- `Renderer`：新增 `renderViewmodel(ViewmodelModel)` + `viewmodelRenderer` 字段；`init()` 建、`dispose()` 销。调用序：**清屏 → 世界 → 手持物 → HUD → 菜单**。
- `SkyIslandGame.render()`：`renderWorld()` → `renderer.renderViewmodel(viewmodel)` → `renderHud()`（仅加这一处最小改动 + `updateViewmodel()`）。
- `updateViewmodel()` 从 `player.inventory()` / `combat.existingGun(player)`（`shotsFired()` / `isReloading()` / `reloadProgress01()`）/ `player.isAiming()` / `isMining()` / 速度算 `moveSpeed01` 填充 model；`viewmodel.visible = ui.state().gameplayHudVisible() && !player.isDead()`（菜单 / 死亡期**零 GL 调用**，连着色器都不绑）。
- 新文件（`render/viewmodel/**`，5 个）：`ViewmodelKind` / `ViewmodelModel` / `ViewmodelPose` / `ViewmodelGeometry` / `ViewmodelRenderer`。

### 6. 本模块单测（离线可跑，不依赖窗口）

`render/entity/MonsterModelTest`、`render/entity/EntityRendererTest`、`render/viewmodel/ViewmodelRendererTest` —— 三个类均在无 GL 环境用纯函数 + 顶点扫描举证：

- 结构（头在躯干上、臂在躯干外、双腿有缝、眼睛只在正面且在头顶边之下）；
- 尺寸（静止高 == 1.80、撑满 ±0.30）；
- **动画包络扫描**：任何步态 × 攻击 × yaw 组合下没有 part 越出碰撞箱（垂直 0 容差、水平 ≤ 1.20 容差）；
- 起伏只向下、双腿反相、攻击摆臂向前；渲染路径（一只怪 = 8 盒、旋转后仍适配、朝向玩家、闪白 8 part 全亮、站住相位冻结）；
- viewmodel：跟随槽位、切槽弹跳收敛、方块色取自手持、三形态几何不同、**整条包络下永不压准星 / 不越右半屏 / 不压 hotbar**、ADS 收拢、开火后坐 + 枪口闪光出现后衰减、换弹下压、挖掘挥动、行走起伏、超大 dt 夹紧。

> **验算方式（环境限制）**：本轮环境禁跑 `mvn`（构建由 engineering-lead 负责），改用
> `javac --release 21` 对 `target/classes` + 自组 classpath 编译、并用 `junit-platform-launcher`
> 跑自定义 runner 离线执行；三个测试类均通过。此路径**只覆盖纯函数逻辑**，
> 不覆盖 GL —— 渲染观感仍以第 5 节的人工试玩为准。

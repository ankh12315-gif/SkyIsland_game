# 设置界面文字重叠：根因、取舍与验证（2026-10-04）

玩家报障：**"设置界面的文字有一点重叠,做的好看一些"**。

本文件记录这一轮的全部结论。字段级细节查代码；这里只记**为什么这么定**，以及**哪些结论有证据、哪些还需要人眼**。

---

## 1. 根因（不是"留白没调好"）

追下去是一条链，每一环都可在代码里指认：

1. `BitmapFont` 把所有文字排进 `LINE_ROWS = 12` 行的**行盒**里；
   ASCII 的 7 行字形只是**居中**放在其中（`ASCII_ROW_OFFSET = 3`）。
2. 于是一行 **2 倍字**占的纵向空间是 `lineHeight(2 * scale)`，**不是** `7 * 2 * scale`。
3. 而当时的 `PANEL_ROW_HEIGHT` 只有 **16** —— 比行盒（24）**还小 8**。
4. 渲染器算出的 `textY = y + (rowH - box) / 2` 因此是**负数**，
   每对相邻行的文字盒压掉 **8px（720p）/ 16px（1080p）**。

### 为什么既有断言没抓到

`rowsAreOrderedAndNeverOverlap` 断言的是**行矩形**不重叠 —— 而行矩形**确实**不重叠。
溢出的是**文字行盒**，它可以越出行矩形而不违反那条断言。

> **没有任何一条断言问过"这行放得下自己的文字吗"。**
> 这是本项目第 4 次出现的同类盲区（脆绿灯），也是本轮补断言的直接动因。

---

## 2. 被证伪的一个方案（留档，因为它看起来很合理）

第一反应是"**行盒里上下有空白，压掉就行**"：加 `UiBatch.textCompact`，
只画 12 行里中部的 8 行（`COMPACT_TOP_SKIP = 2`），每行省 4px，25 行省 100px。

**被实测推翻**。`ProbeInk` 扫描主源码 **1580 个**非 ASCII 字符，逐字符量字模墨迹范围：

```
min top ink row    = 0
max bottom ink row = 11
```

**汉字字模是满 11×11 框的**，"笔画集中在中部"这个前提不成立。跳过顶部 2 行会切掉
「置」「警」这类满框字的顶/底笔画。

> 教训与本项目已有教训同源：**看起来合理的空间假设必须先量**。
> 结论：`textCompact` + `COMPACT_TOP_SKIP` / `COMPACT_ROWS` 已**删除**（71 行，零调用点）。

---

## 3. 关键实测：2 倍字在任何分辨率下都排不下

`tmp/probe/Budget.java`（已清理，结论留档）直接调真实 `Menus.settingsEntries` +
`BitmapFont.lineHeight`，不做任何手算。设置界面共 **28 行**（4 分节标题 / 3 空行 / 1 说明 / 20 可操作行），
其中 **25 行带字**：

| 分辨率 | uiScale | 可用高度 | 2 倍字需要 | 1 倍字需要 |
|---|---|---|---|---|
| 1280×720 | 1 | 584 | 600（**超 16**） | 312（余 272） |
| 1920×1080 | **2** | 808 | 1200（**超 392**） | 624（余 184） |
| 2560×1440 | 2 | 1168 | 1200（**超 32**） | 624（余 544） |
| 3840×2160 | 3 | 1752 | 1800（**超 48**） | 936（余 816） |
| 800×600 | 1 | 464 | 600（**超 136**） | 312（余 152） |

**1080p 最严重**：`uiScale = round(1080 / 720) = 2`，于是"2 倍字"实际是 **4 倍字**，缺 392px。

> **这一栏是本轮的核心结论：这不是留白没调好，是设计层面的不可能。**
> 无论怎么调间距，2 倍字都排不下。而 1 倍字在每一档都装得下且有富余。

---

## 4. 采用的方案：文字倍数按式样定档

| 式样 | 用在哪 | 文字倍数 | 行数 | 理由 |
|---|---|---|---|---|
| `COVER` | 主菜单 | **2 倍**（24px 行盒） | 4 | 行少而大，2 倍字绰绰有余 |
| `PANEL` | 设置 / 暂停 | **1 倍**（12px 行盒） | 28 | 行多而密，2 倍字排不下 |

两档都留在**整数倍**里，因此不存在"半个像素"—— 点阵字模按整数倍放大才不出锯齿。

### 为什么"排得下"优先于"看起来更大"

重叠的界面不是"不够好看"，是**读不了**。而 1 倍字在 1280×720 下有 272px 富余，
按行分下去每行多 9px → **行高 21px 配 12px 字，行距比约 1.8**。

实测逐行（720p，`tmp/probe/Dump.java`，已清理）：

```
 0 HEADER  y= 84 h=25 12px字  上留白5 下留白8   控制
 1 SLIDER  y=109 h=22 12px字  上留白5 下留白5   鼠标灵敏度
...
11 BINDING y=332 h=21 12px字  上留白4 下留白5   Place / Use
15 SPACER  y=416 h=12 （空行，只占高度）
16 HEADER  y=428 h=24 12px字  上留白4 下留白8   显示
```

**密而不挤** —— 比原来"两行字贴在一起"好得多。这就是"做的好看一些"的实际落点：
不是把字放大，是把字**放对**并给足行距。

### 已知代价（写明以免日后被当 bug "顺手修"）

面板式的**说明行（INFO）也是 1 倍**，与可操作行同大小。
点阵字模只有整数倍放大才不出锯齿，1 倍已是最小可用倍数，说明行无法再小。
它与可操作行的区分靠**颜色**（`UiTheme.INFO` 对 `UiTheme.ITEM`）而不是字号。
**若日后有人"顺手"把 INFO 放大，设置界面会直接退回排不下的状态。**

---

## 5. 预算不足时的三档退让顺序（写进代码注释）

| 顺序 | 被牺牲的 | 性质 |
|---|---|---|
| ① | **空行** | 纯装饰，高度是一个行盒 |
| ② | **每行富余** | 封顶 1 个行盒（`MAX_BREATHING_ROWS`） |
| ③ | **文字行盒**（`hardFloor`） | **压了就会重叠，永不牺牲** |

玩家看到的是"组间距先消失，然后行距变紧"，而不是"字开始叠字"。

> 若连 ③ 都放不下（窗口 < 约 450px 高，默认窗口不会走到），代码**明确不假装排得下**：
> 等比退让并保底 1px，让菜单仍可见，而不是抛异常或画到屏幕外。

---

## 6. 架构改动：布局与渲染彻底解耦

这是本轮**最贵也最值**的一条。旧结构下"行高"在 `MenuLayout`、而"文字画在哪"在
`MenuRenderer`，两边**各说各话**——那正是 bug 能溜过去的结构原因。

新结构：`MenuLayout` 把三件事**一次算完并存成数组**，渲染器**只读不算**：

```java
public int rowTextY(int index);      // 文字行盒顶边
public int rowTextScale(int index);  // 绝对倍数（含 uiScale）
public int rowRuleY(int index);      // 分节标题分隔线 y，非 HEADER 为 -1
```

`MenuRenderer.drawRow` 不再做**任何**垂直居中或倍数换算。
绘制与行高读的是同一个数，**不可能再脱节**。

已删除的失效常量：`PANEL_ROW_HEIGHT` / `HEADER_ROW_HEIGHT` / `SPACER_ROW_HEIGHT` /
`INFO_ROW_HEIGHT` / `LEGACY_PANEL_ROW_HEIGHT` / `MIN_ROW_HEIGHT` / `ROW_BREATHING`。

---

## 7. 修掉的一个自造 bug（边算边改分母）

上一轮遗留的纵向预算削余逻辑：

```java
// 错误写法
take = slack[i] * (cut / (double) totalSlack);
totalSlack -= take;          // ← 分母在同一次遍历里被自己改小
```

后果：后续行拿到的比例越来越小（实测几乎全是 0）→ `cut` 削不掉 → 八轮空转 →
落到"补余量到最胖那行"上 → **中间行保持 22px 而第 0 行涨到 39px**。症状极怪，但根因就是这个分母。

**修法**：比例在遍历**之前**算好，存 `int[] taken`，取整差额再二次补给。

> 又是一次"症状在玩法、根因在算术"的实例。

---

## 8. 新增断言（8 条）

`MenuLayoutTest`（25 条，+5）：

| 断言 | 守什么 |
|---|---|
| `everyRowIsTallEnoughForItsOwnTextBox` | 每行装得下自己的行盒（5 档分辨率） |
| `verticalCompressionNeverSqueezesARowBelowItsTextBox` | 压缩只压到"刚好装下"就停 |
| `textBoxesOfAdjacentRowsNeverOverlapAtAnySupportedResolution` | ★ **相邻行行盒互不侵入**（6 档分辨率） |
| `theHeaderSeparatorSitsBelowTheTextBox` | 分隔线读 `rowRuleY`，不越出本行 |
| `labelAndValueShareTheSameTextScaleWithinARow` | 倍数只由 `relativeTextScaleOf` 推出**一次** |

★ 最后一条带一个**曾经失手**的判据：`textBoxHeight` 写成
`rel * textScaleOf(style) * uiScale` 会把式样基准**乘两遍**，封面式于是变成 4 倍字。
现在断言 uiScale 翻倍时行盒必须**正好**翻倍，把"只乘一次"钉死。

`MenuRendererTextGeometryWiringTest`（3 条，**新增文件**）：

| 断言 | 守什么 |
|---|---|
| `drawRowTakesItsTextGeometryFromTheLayout` | `drawRow` 必须调 `rowTextY` / `rowTextScale` |
| `noRowDrawingMethodRecomputesTextGeometry` | 渲染器（`drawOverlay` 之外）不许出现 `BitmapFont.lineHeight` |
| `theOverlayExemptionCoversOnlyTheOverlay` | **豁免本身也是契约**，不许悄悄扩大 |

> 扫描断言先剥注释（`SourceScan.withoutComments`）：本文件自己就写着
> `lineHeight(2 * scale)` 这个反例，不剥就会被**注释里的反例满足**。

---

## 9. 反向验证 R6（两轮）

### R6a：把旧 bug 注回渲染器

注入 `int textY = y + (rowH - BitmapFont.lineHeight(2 * scale)) / 2;`

| 测试 | 结果 |
|---|---|
| `MenuLayoutTest` | **25 / 25 全绿** |
| `MenuRendererTextGeometryWiringTest` | **2 条变红** |

> **这一对照是本轮最重要的证据**：
> 渲染器带着**原样**的旧 bug，`MenuLayoutTest` 依然**全绿通过**。
> 布局测试在结构上**根本抓不到**渲染器的回归 —— 这就是为什么必须有那条接线守卫。

### R6b：把面板式改回 2 倍字

注入 `return style == Style.COVER ? 2 : 2;` → `MenuLayoutTest` **6 条变红**，
其中 `textBoxesOfAdjacentRowsNeverOverlap` 的失败信息是可读的实测值：

```
1280x720 下第 1 行（SLIDER）的文字行盒底部 134 压到了第 2 行（TOGGLE）的文字顶边 133，
重叠 -1px —— 这就是玩家说的文字重叠。标签="鼠标灵敏度"
```

两轮均**逐字节恢复**，`src/main/java` 内残留标记 **0**。

---

## 10. 机器侧收尾

| 项 | 结果 |
|---|---|
| 单测 | **1156 / 0 / 0**（基线 1148，+8：5 条布局 + 3 条接线守卫） |
| 门禁 | **m1 60 / ui 179 / m2 387 行 PASS，0 FAIL**（与基线**完全一致** ⇒ 无玩法回归） |
| m2 权威计数 | `m2_selftest_assertions=193`、`failures=0`、`combat_closure=true` |
| CJK 字模 | 已重烘（新增 6 字：侵富牲牺究胖），**没删任何中文** |
| 死代码 | `UiBatch.textCompact` + `COMPACT_*` 已删（71 行，零调用点） |
| 探针 | `tmp/probe/` 下本轮全部清理 |

---

## 11. 需要人眼的部分（机器证不了）

与第 9 节的 17 项真人试玩同性质。以下**不能由断言代替**：

- [ ] 1280×720：设置界面逐行看，**有没有任何两行贴在一起**
- [ ] 1920×1080：这是旧 bug 最严重的一档（原压 16px），重点看键位绑定那 8 行
- [ ] 主菜单：确认 **2 倍字仍在**（本轮只把面板式降到 1 倍）
- [ ] 选中行高亮条是否与该行文字**垂直居中**
- [ ] 分节标题下的分隔线是否**不压文字**
- [ ] 800×600（最小支持档）：28 行仍可读、可点
- [ ] 覆盖层（键位冲突对话框）：确认它仍**自行居中**且不受本轮改动影响

**最终效果需要主理人的眼睛确认。** 在此之前，本轮只可以说"几何上已证明不重叠"，
不说"已修好、好看"。

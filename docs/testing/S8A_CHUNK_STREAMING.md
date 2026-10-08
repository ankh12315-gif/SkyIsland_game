# S8a 区块流式加载 / 卸载 —— 落地报告

> 阶段：M4-ART（PRD_BLOCK_CREATIVE_v1.0 §8）→ S8 的第一半。
> 日期：2026-10-08　|　代码：`world/ChunkStreamer.java`、`world/World.java`（两个钩子）、
> `save/SaveManager.java`（单块落盘 + 按坐标回放）、`render/mesh/ChunkRenderer.java`（单块释放网格）
> 守卫：`ChunkStreamerTest`(12) / `WorldStreamingHookTest`(6) / `SaveManagerStreamingTest`(7) / `ChunkStreamingWiringTest`(10)
> 单测总量 **1379 / 0 失败 / 0 错误**；门禁 m1 60 / ui 179 / m2 387 行 PASS，0 FAIL。

---

## 1. 为什么把 S8 拆成两半

PRD §8 的 S8 是「创造五项能力」（§5.2–§5.5），其中 §5.4 是**可飞行**。
先做飞行的后果是：玩家 20 秒就能飞出旧世界的 64×64 边界，而世界是「固定 4×4 区块、全部常驻」的 ——
边界外一律是生成器返回的平地，`World.chunks` 又是无界 `LinkedHashMap`。于是：

> **不做流式就做飞行，等于埋一颗内存定时炸弹**，而且它的表现是"越玩越卡"，
> 玩家只会说"这游戏优化不行"，没人会想到是区块没卸载。

所以顺序是 **S8a 流式 → S8b 飞行与创造四能力**。

---

## 2. 三条必须一起成立的规则

| # | 规则 | 破了的表现 |
|---|---|---|
| 1 | **加载半径 ≠ 卸载半径**（卸载 = 加载 + `UNLOAD_MARGIN=1`） | 站在区块边界来回走 → 反复生成 + 卸载 → **规律性帧抖动**；地形是确定的，画面完全正常，**试玩看不出来** |
| 2 | **加载按切比雪夫距离由近及远** | 有预算时先加载远处 → 玩家脚下那一圈还没生成 → 跨区块时短暂掉进虚空 |
| 3 | **卸载必须连带清理**（释放 GPU 网格 + 落盘脏数据） | 只释放网格 → 走回来时挖掉的坑又长回来；只落盘 → 显存一路涨 |

### ★ 已修掉的那个 GPU 泄漏（第二泄漏点）

`ChunkRenderer.meshes` 是 `IdentityHashMap<Chunk, ChunkMesh>`，原来只有 `disposeAll()`。
`processRebuildQueue` 里那道"区块已卸载就释放"**只在它恰好还排在重建队列里时才走得通**，
而绝大多数被卸载的区块网格是干净的、根本不在队列里 —— 于是它的 VBO 会永远留在 map 里。

修法：把私有的 `disposeMesh` 提升为 **`public releaseMesh(Chunk)`**，
由 `World.ChunkUnloadListener` 在卸载时调用。卸载因此**只有一个漏斗**，清理无法被绕过。

---

## 3. 两个新钩子，为什么挂在 `World` 上

| 钩子 | 触发点 | 做什么 |
|---|---|---|
| `World.ChunkUnloadListener` | `unloadChunk` 末尾 | `renderer.releaseMesh(chunk)` + `saveManager.saveChunk(world, chunk)`（仅脏块） |
| `World.ChunkDeltaSource` | `getOrLoadChunk` 里，**入表之后** | `saveManager.applyChunkDelta(world, cx, cz)` |

挂在 `World` 而不是挂在 `ChunkStreamer` 上的理由：
「加载一个区块」和「卸载一个区块」各自只有一个入口。钩子挂在入口上，
将来新增任何加载/卸载点都**绕不过**清理；挂在流式器上则会。

### ★ 增量回放必须放在入表之后（重入）

`ChunkSerializer.applyTo` 内部会调 `getOrLoadChunk`。
若回放发生在**入表之前**，那一句会再生成一次同一个区块 → **无限递归**；
发生在入表之后，它拿到的是已在表里的那个，递归在第一层就收敛。
守卫：`WorldStreamingHookTest#theDeltaSourceSeesTheChunkAlreadyRegisteredSoReplayIsReentrant`。

### ★ 为什么不能"启动时统一回放一遍"

流式之后，玩家在远处建的东西**根本还没被生成**，统一回放会全部落到
「区块未加载，已跳过」这条分支上 —— 存档文件还在磁盘上，世界却不认它。
所以改成：**区块在生成的那一刻回放它自己的差异**。
这条同时解决了"下次启动出生点半径外的改动读不回来"。

---

## 4. 内存账（★ 用保留半径算，不是加载半径）

单区块体素 `short[16×16×128]` = **64 KB**。

| 半径 R | 加载方块 | 稳态（保留 R+1） | 视距 |
|---|---|---|---|
| 3 | 49 块 ≈ 3.1 MB | 81 块 ≈ 5.1 MB | 48 格 |
| **4（默认）** | 81 块 ≈ 5.2 MB | **121 块 ≈ 7.6 MB** | 64 格 |
| 6 | 169 块 ≈ 10.8 MB | 225 块 ≈ 14.4 MB | 96 格 |

> ★★ **这一条是本轮最容易算错的地方**：走动时的稳态集合是**保留**方块，不是加载方块 ——
> 已生成的那一圈要等玩家再走出一格才卸，于是它长期留在内存里。
> 用加载半径估会**低估近一倍**。低估的预算比没有预算更危险，因为它让人以为还有余量。
> 判据由 `ChunkStreamerTest#theDefaultRadiusHasAnAffordableResidentFootprint` 钉住（≤ 8 MB）。

默认半径可由 `-Dskyisland.chunkRadius=N` 覆盖，非法值回落默认并告警。

---

## 5. 实测（90 秒窗口，1280×720，新世界）

| 指标 | S8a 之前 | **S8a 之后** | 判据 | 结论 |
|---|---|---|---|---|
| `loaded_chunks` | 16（4×4 固定） | **81**（半径 4） | — | 按需加载生效 |
| 活对象（after GC） | 7.23 MB | **11.46 MB** | — | 占上限 4028 MB 的 **0.28%** |
| 内存趋势 | −0.237 MB | **−0.327 MB** | 不应为正 | **零泄漏** |
| p95 frame | 1.396 ms | **1.215 ms** | ≤ 16.7 ms | 预算的 **7.3%** |
| max frame | 7.916 ms | 42.018 ms | 无 >50 ms | ✔ |
| `spikes_gt_50ms` | 0 | **0** | =0 | ✔ |
| FPS | 1322 | **1606** | — | — |
| `perf_gate_met` | true | **true** | P95≤16.7 且 spike=0 | ✔ |
| `mesh_build_mean_ms` | 1.777 | 0.799 | — | ✔ |
| `stream_chunks_load / drop` | — | 81 / 0 | — | 站立不动，零churn |
| `delta_applied` | — | 0 | — | 新世界无存档增量 |

**★ 这笔账是能对上的**：81 − 16 = **65 个新增区块 × 64 KB = 4.16 MB**，
实测 11.46 − 7.23 = **4.23 MB**。差额 0.07 MB 是网格与光照快照的量级 ——
也就是说"多出来的内存"是**可解释的、有界的**，不是泄漏。

---

## 6. 反向验证（8 条断线，全部 `ASSERTION_FAIL`，逐字节还原，残渣 0）

| # | 注入 | 变红的断言 |
|---|---|---|
| A | `stepLogic` 里不再驱动流式 | `theStreamerRunsBeforePlayerPhysics` |
| B | 卸载回调不再释放 GPU 网格 | `unloadReleasesTheGpuMeshAndFlushesTheDirtyChunk` |
| C | 卸载回调不再落盘 | 同上（失败消息指向 `saveChunk`） |
| D | `World.unloadChunk` 不再通知监听者 | `worldsUnloadFunnelActuallyInvokesTheListener` + `theUnloadListenerFiresExactlyOnce…` + `theUnloadedChunkStillCarriesItsContent…` |
| E | 增量回放挪到入表之前 | `theDeltaSourceSeesTheChunkAlreadyRegistered…` + `theDeltaSourceCanActuallyMutate…` |
| F | `attachStreaming` 不再挂增量来源 | `attachStreamingWiresAllThreeHooksAtOnce` |
| G | 滞回量改成 0 | `hysteresisKeepsOneExtraRing…` + `resetLoadsTheWholeSquare…` |
| H | 加载顺序改成由远及近 | `budgetForcesLoadsToProceedNearestFirst` |

还原后 `byte-identical=true`、`marker-free=true` 全部成立；全仓 `[RV-?]` 残渣 **0**。

---

## 7. 自测期间为什么关掉卸载

`start()` 的第 7 节装配自测对象时显式 `chunkStreamer.setUnloadEnabled(false)`。
理由：自测脚本的断言读的是 `World` 的**当前**状态，让区块在它脚下消失会把
「断言红」变成「偶发红」，而偶发红没有任何诊断价值。
判据走 `isAutoVerification()`（覆盖脚本化 / 界面 / 战斗三种自测），
散着写 `selfTest == null` 之类会在新增自测时漏掉 —— 这条也写了守卫。

---

## 8. 遗留

- **`stream_chunks_drop` 在真实试玩中还没有非 0 的证据**（90 秒测量里玩家没走动）。
  需要一次"真人走一段路"的运行来证明卸载真的发生（属真人试玩清单）。
- 稳态常驻量随**行走方向**变化：只沿 +x 走时是 7 列 × 5 行 = 35 块，
  四向乱走才填满 121 块。上表的 121 是上界。
- 未做：地形生成的工作线程化（网格化还没被证明是瓶颈，提前并行化会让"卡顿来自哪里"不可归因）。

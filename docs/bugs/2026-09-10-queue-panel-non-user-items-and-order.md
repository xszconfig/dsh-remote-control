# [BUG] 排队面板混入系统注入消息 + 乐观项展示顺序与服务端投影不一致

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已修复 |
| 仓库/模块 | App（`BridgeClient.kt` / `App.kt` / `QueueItemLogic.kt`） |
| 发现方式 | 用户真机反馈（两条：非用户消息混入面板；排队消息疑似后进先出） |
| 日期 | 2026-09-10 |
| 相关 commit | app `0e430d5`（`fix/queue-collapse`） |
| 关联文档 | `docs/bugs/2026-09-07-steer-queue-item-not-found-message-vanished.md`（队列域前科） |

## 背景

- 桥侧 `queueItemsOf`（`src/core.ts`）是排队面板的「展示投影」：`nextTurn` → `placement=queued`，`nextStep` → `source.kind==='user' ? steering : context`。即「context」是系统注入（子代理收尾 `subagent-settled`/报告 `subagent-report`/LSP 反馈等经 `steer`/`inject` 进入 `nextStep`）的标记。
- 客户端 `sendMessage` 对运行中会话落乐观排队项（`id=local-<ts>`），随后由 `session_queue`/`subscribe` 服务端投影整体替换成真实 MessageId。
- 桥侧设计上「展示用 `queueItemsOf`（含 context 行，`🔧上下文` 标签），持久化用 `userQueueItemsOf`（只用户）」——这条把 context 行当作「可展示」是本次 Bug 1 的根。

## 现象

- Bug 1：面板出现「Background subagent ... reported ...」等系统注入消息，用户明确「这些不是用户发送的消息，不该展示在排队消息里」。
- Bug 2：用户观察到新消息排在旧消息前面（疑似 LIFO）。

## 排查过程

- Bug 1：读 `queueItemsOf` → `nextStep` 非用户项标为 `context`，但客户端 `handleSessionQueue`/`handleHistory` 用 `ev.items`/`ev.queue` 直接整体赋值 `queueItems`，未过滤 `context` → 面板 `forEach` 原样渲染 `context` 项。
- Bug 2：静态追链确认服务端顺序是 FIFO——DSH `followup` → `send(msg,"next-turn")` → `inbox.splice(target, Infinity, 0, [msg])` → `Math.min(Infinity,len)=len` 即 append；桥 `queueItemsOf` = `[...nextTurn, ...nextStep]`（nextTurn 在前、FIFO）；客户端 `handleSessionQueue` 整体替换、QueuePanel `forEach` 无 reversed。**未发现真正的 LIFO**。
- 真正的顺序缺陷在**乐观项插入位置**：`sendMessage` 用 `queueItems + opt` 把新排队项 append 到列表**末尾**，而服务端投影把新排队项放 `nextTurn`（在 `nextStep` 之前）。当面板里已有 steering/context 项时，乐观项会排到它们之后，与服务端 `[...nextTurn, ...nextStep]` 不一致——这是「组装顺序」缺陷，且配合 `session_queue` 整体替换会产生「新项位置漂移」的观感，疑似用户所见的「后进先出」。

## 根因分析（必须挖到深层，不允许停留在表面现象）

- **Bug 1 表面原因**：客户端未按 `placement` 过滤展示，直接渲染 `context`（系统注入）项。
- **Bug 1 深层根因**：「展示投影」与「用户可见队列」被混为一谈——`queueItemsOf` 为了在面板顺带显示 `🔧上下文`，把系统注入项也塞进了 `session_queue` 投影，客户端忠实渲染了服务端投影（铁律 6 下本无错），但服务端投影本身把「不该展示的注入项」标记为可展示，属**投影边界缺失**。
- **Bug 2 表面原因**：乐观项 `queueItems + opt` 直接 append 到列表末尾。
- **Bug 2 深层根因**：**乐观项与服务端投影是两条时间线，客户端在插入乐观项时没有对齐服务端的分区顺序（nextTurn 在前、nextStep 在后）**——乐观项用「追加到末尾」这种最简单策略，破坏了「服务端投影顺序 = 单一事实源」这一约束。与 `2026-09-07` 的「乐观项假 id 可误操作」同源：乐观状态与权威投影的收敛协议没有覆盖「顺序/分区」维度。
- **可推广教训**：本地乐观状态在插入/回放时必须**逐字段对齐权威投影的结构**（id 对齐 → 分区顺序对齐 → 是否可操作对齐），不能只对齐「有/无」。

## 解法

- Bug 1：新增 `userVisibleQueueItems(items) = items.filter { it.placement != "context" }`，在 `handleSessionQueue` 与 `handleHistory` 两个入队点过滤后再写 `queueItems`（仅过滤展示，不动服务端队列）。
- Bug 2：新增 `insertOptimisticQueued(items, opt)`——把乐观项插到「queued 段的末尾」（`steering/context` 之前），与服务端投影 `[...nextTurn, ...nextStep]` 一致；`sendMessage` 乐观入队改用此函数。

**核心 Code Diff**（`0e430d5`）：

```kotlin
// QueueItemLogic.kt
+ fun userVisibleQueueItems(items: List<QueueItemWire>): List<QueueItemWire> =
+     items.filter { it.placement != "context" }
+ fun insertOptimisticQueued(items: List<QueueItemWire>, opt: QueueItemWire): List<QueueItemWire> {
+     val insertAt = items.indexOfFirst { it.placement != "queued" }.let { if (it == -1) items.size else it }
+     return items.toMutableList().apply { add(insertAt, opt) }
+ }

// BridgeClient.kt
- updateView(ev.sessionId) { v -> v.copy(queueItems = ev.items) }
+ val visible = userVisibleQueueItems(ev.items)
+ updateView(ev.sessionId) { v -> v.copy(queueItems = visible) }
- queueItems = ev.queue,
+ queueItems = userVisibleQueueItems(ev.queue),
- s.copy(queueItems = s.queueItems + opt)
+ s.copy(queueItems = insertOptimisticQueued(s.queueItems, opt))
```

- 提交哈希：app `0e430d5`（`fix/queue-collapse`）。
- 回归测试：`QueueItemLogicTest` 新增 5 例（过滤 2 例 + 乐观项插入 3 例），共 10 例；`detektP0` + `testDebugUnitTest` + `assembleDebug` + DEX 门禁全绿。

## 后续改进计划

- [ ] **投影边界收口**：桥侧 `queueItemsOf` 的「context 行」在客户端已不再展示，可评估是否彻底从 `session_queue` 投影移除 context（或在 wire 加 `source` 字段替代 `placement` 的隐式编码），让「展示投影」与「用户可见队列」彻底同构，避免客户端再靠 `placement` 猜语义。
- [ ] **乐观项收敛协议**：`sendMessage` 的 msgId+ack 落地后，用服务端回传真实 id/顺序直接收敛乐观项，彻底消除「本地插入位置 vs 服务端分区顺序」这一整类竞态。
- [ ] 教训推广：审计其余乐观状态（PendingBubble 上屏、转盘定位）是否也有「对齐了有无、没对齐顺序/分区」的同类缺口。

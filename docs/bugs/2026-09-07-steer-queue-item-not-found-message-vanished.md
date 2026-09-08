# [BUG] 排队消息插队报「已不在队列中」且消息看似自动消失

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已修复 |
| 仓库/模块 | App（`BridgeClient.kt` / `App.kt` / 新增 `QueueItemLogic.kt`）+ 桥（`src/core.ts` queue_action） |
| 发现方式 | 用户报告（着急插队反而丢消息，性质恶劣） |
| 日期 | 2026-09-07 |
| 相关 commit | app `a7f835b`（feature/steer-queue-fix）；bridge `8ac3e39`（queue_action 日志随 coordinator 提交） |
| 关联文档 | `docs/bugs/2026-09-02-queue-snapshot-debounce-race.md`（队列域前科）；`docs/decisions/网络连接与弱网可靠性调研.md`（msgId+ack 幂等演进方向） |

## 背景

- 排队消息面板（`8a09f46`，08-29）：运行中发消息进入服务端 inbox 队列，面板可「插队(steer)/删除(remove)」，数据源是 `session_queue` 事件（服务端投影）。
- 乐观入队（`38a5f3a`，08-29）：为消除「发送瞬间 → 回环广播」的延迟，发送时本地先落一个**乐观排队项**，`id = "local-<毫秒时间戳>"`（本地造的假 id），等服务端 `session_queue` 到达后整体替换成真实 MessageId。
- 发送状态语义修正（`1eb461e`，09-06）：运行中发送**只进排队面板、不上屏**（IM 模式仅非运行中走 PendingBubble）。这让乐观项在排队面板更显眼、更早被用户点「插队」。

## 现象

- 用户原话：有排队消息想插队发送，点好几次没点出去，报「排队消息已不在队列中」；且消息**自动丢了**。之前队列一直正常。
- 服务端日志（10:56~10:59）：`type=queue_action session=session-ab78` 收到后**无「排队操作」成功日志** → 走了失败分支；随后多次 `inbox/spliced(deferred)` + `session_queue items=0` → 该消息已被 agent 下一轮领取消费。
- 手机当时离线（`/remote/connected=[]`、phone-logs count=0），客户端日志缺失。

## 排查过程

1. **代码定位**：桥 `queue_action`（`src/core.ts`）按 `cmd.itemId` 在 `inbox.nextTurn/nextStep` 精确匹配；找不到回 `queue-item-not-found`；steer 仅当 `target=next-turn && status=running`，否则 `steer-unavailable`；失败分支**不记 itemId**（日志缺口）。
2. **客户端链路**：`sendMessage` 对运行中会话落 `QueueItemWire(id="local-<ts>")` 乐观项；`handleError` 对 `queue-item-not-found` 无本地处理（只弹通用横幅）；`handleSessionQueue` 用 `ev.items` **整体替换**本地队列。
3. **版本追溯**（`git log -S`）：
   - `38a5f3a`（08-29）引入乐观项 `local-` 前缀 id；
   - `8a09f46`（08-29）引入 `queueItems = ev.items` 整体替换 + 插队/删除按钮；
   - `1eb461e`（09-06）「运行中仅排队不上屏」让乐观项在面板显眼、用户更早插队。
   - 结论：**不是单一版本"改坏"，而是 08-29 引入乐观入队时就埋下的潜在竞态**——乐观项的假 id 从未被门控，09-06 的语义调整把它**暴露**出来（用户着急插队时命中）。
4. **模拟器复现路径**：运行中发消息 → 在 `session_queue` 回环替换前立刻点「插队」→ 桥按 `local-<ts>` 找不到 → `queue-item-not-found`；随后该消息被本轮领取，`session_queue(items=0)` 整体替换本地队列 → 面板该条**静默消失**（无「正在处理」过渡态）。

## 根因分析（必须挖到深层，不允许停留在表面现象）

**5-Why**：

1. **为什么插队报「已不在队列中」？** 因为客户端对乐观项发 `queue_action` 用的是本地造的 `local-<ts>` id，服务端 inbox 里是真实 MessageId，两者不匹配。
2. **为什么乐观项能带上假 id 去操作？** 因为队列面板的「插队/删除」按钮从 `8a09f46` 起就用 `item.id` 直接调 `queue_action`，**从未对 `local-` 前缀的乐观项做「同步中禁止操作」门控**——乐观项与真实 id 是两条时间线，UI 却把它们当成同一实体可操作。
3. **为什么消息「消失」？** 因为 `handleSessionQueue` 用 `ev.items` 整体替换本地队列；消息被本轮领取后 `session_queue(items=0)`，乐观项被替换清空；而 `handleError` 对 `queue-item-not-found` **只弹通用横幅、不补状态**——「消费即消失」，没有「已被本轮领取/正在处理」的可见过渡态。
4. **为什么这些缺陷能长期共存？** 因为设计上把「队列操作」与「队列投影同步」**当成两个独立功能**分别实现，从未把「乐观项 → 真实 id 替换」这段竞态窗口当成一等场景；乐观项的语义边界（什么时候可操作、失效后如何呈现）没有定义。
5. **根本原因（状态归属/重放模型缺陷）**：**客户端维护了「乐观项」与「服务端投影」两套队列状态，却没有单一事实源与收敛协议**。乐观项是"本地推测"，服务端投影是"权威事实"，二者靠 `session_queue` 事件做**非原子的整体替换**来收敛；在替换窗口内对乐观项做任何操作都是对"推测状态"的误操作，而错误路径又不做状态补偿（不刷新、不标记），最终把"已被领取"渲染成"丢失"。

- **可推广教训**：任何「乐观/本地推测状态」必须 (a) 有明确的"不可操作/同步中"门控，直到被权威投影确认；(b) 被权威投影取代时要有可见过渡态（尤其"被消费"这种正向结局，绝不能呈现为"消失"）；(c) 错误路径要做状态补偿（刷新 + 明确语义），而不是只弹一条横幅。这与「待发送消息丢失 5-Why」（`46499ef`）同源：本地乐观状态 vs 服务端权威状态的收敛，是整个消息链路的头号竞态来源。

## 解法

- **a) 乐观项禁止操作**（App.kt QueuePanel）：`isSyncingQueueItem(item.id)`（`local-` 前缀）→ 不渲染「插队/删除」按钮，显示「同步中…」；等真实 id 替换后才可操作。根治 not-found 的第一入口。
- **b) queue-item-not-found 状态补偿**（BridgeClient.handleError）：主动 `subscribe` 刷新队列（拿权威投影）+ 横幅「排队消息已被本轮领取，正在处理中（未丢失）」，绝不静默消失。
- **c) steer-unavailable 明确原因**：桥错误文案改为「当前轮次不接受插队，消息仍在排队」；客户端横幅「…（可稍后再插队）」。
- **d) 桥失败路径补 itemId 日志**（src/core.ts queue_action 两个失败分支 `logger.warn` 带 item/action/target/status）。
- 纯逻辑抽到新文件 `QueueItemLogic.kt`（`isSyncingQueueItem` / `queueErrorBanner`），单测 `QueueItemLogicTest.kt` 5 例。

**核心 Code Diff**：

```diff
// App.kt QueuePanel：乐观项门控
+  if (isSyncingQueueItem(item.id)) {
+      Text("同步中…", style = ..., color = ...)
+  } else {
       if (item.placement == "queued") { TextButton(插队) }
       TextButton(删除)
+  }

// BridgeClient.handleError：状态补偿
+  val banner = queueErrorBanner(ev.code)
+  if (banner != null) {
+      if (ev.code == "queue-item-not-found") {
+          _session.value.currentSessionId?.let { sid ->
+              scope.launch { if (!connection.send(ClientCommand.Subscribe(sid))) pushConnectionError(...) }
+          }
+      }
+      pushBusinessError(banner)
+      return
+  }

// 桥 queue_action：失败分支补 itemId 日志 + 文案明确
+  logger.warn('QUEUE', `排队操作失败 queue-item-not-found item=${cmd.itemId.slice(0,8)} action=${cmd.action} session=...`)
+  send(ws, { type:'error', code:'steer-unavailable', message:'当前轮次不接受插队，消息仍在排队' })
```

- 提交哈希：app `a7f835b`（`feature/steer-queue-fix`）；bridge `8ac3e39`（queue_action 失败路径日志 + steer-unavailable 文案，随 coordinator 提交）。
- 回归测试：客户端 `QueueItemLogicTest`（local- 判定 2 例 + 错误横幅 3 例，`testDebugUnitTest` 全绿）；桥 smoke 新增 2 条失败路径断言（`queue-item-not-found` / `steer-unavailable` 文案，断言已就位、随「消息必达 0.14.0」工作提交）。

## 后续改进计划

- [ ] **乐观项收敛协议**：`sendMessage` 的 msgId+ack 幂等（决策「消息底座」）落地后，乐观项用服务端回传的真实 msgId 直接替换，进一步缩短「local- 不可操作」窗口。
- [ ] **队列操作状态机**：把「同步中 / 可操作 / 插队中 / 已被领取」显式建模为 `QueueItemWire` 的派生状态，而非靠 id 前缀猜。
- [ ] **错误码语义化**：`queue-item-not-found` / `steer-unavailable` 等错误应携带结构化字段（itemId/原因），客户端可精确标记而非全局刷新。
- [ ] 教训推广：审计其余「乐观上屏」（PendingBubble、转盘定位）是否有同类「推测状态可误操作」窗口。

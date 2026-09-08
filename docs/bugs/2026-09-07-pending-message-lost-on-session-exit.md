# [BUG] 发送失败（网络断开）后退出会话再回来，本地待发送消息直接消失

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已修复 |
| 仓库/模块 | App（`PendingMessages.kt` 状态机 + `BridgeClient.kt` 会话归约） |
| 发现方式 | 用户报告（P00：任何用户消息都是明确指令，丢了影响不可估量） |
| 日期 | 2026-09-07 |
| 相关 commit | `a7685ec`（`feature/pending-persist` 工作树分支，代码 + 单测） |
| 关联文档 | `docs/bugs/_TEMPLATE.md`；`AGENTS.md` 铁律 5（IM 发送状态）；`PendingStore.kt` 新增 |

## 背景

- `PendingMessages.kt`（commonMain）定义 IM 标准的发送状态机：`PendingMessage(localId/sessionId/text/status/createdAt)`
  三态 `Sending/Sent/Failed`。点发送 → `Sending`（乐观上屏 + 时间行小 Loading）；送达 bridge → `Sent`
  （等回显接棒）；`connection.send` 返回 false → `Failed`（时间行红色 ❗，点击重发）；服务端 `user_message`
  回显到达 → `matchPendingEcho` 按「同会话 + 同文本 + 时间相近」匹配 → 移除该 pending，回显作为权威气泡渲染。
- 该状态机是**纯内存态**：`pendingMessages` 挂在 `SessionUiState`（`MutableStateFlow`）上，从未落盘。
  语义修正 1eb461e 后：Agent 运行中（running）的新消息走服务端排队队列（`queueItems`），不再建 pending；
  只有**非运行中**（队列空、消息被立即消费）才走 PendingBubble 状态机。
- 存储基建已有成熟封装：`DraftCache`/`SessionCache`/`EventCache`/`DeviceStore` 全部是
  「commonMain 接口 + androidMain 落盘实现」，统一用 `Mutex` 串行化 + 临时文件 rename 原子写；
  但**没有**为 pending 建同款持久化封装。

## 现象

- 复现步骤：发送失败显示红色 ❗（网络断开）→ 用户退出到会话列表 → 再回到该会话 →
  **本地待发送消息直接消失了**（❗ 气泡不见，用户这条明确指令丢失）。
- 丢失路径不止「退出列表再回来」：`openSession()` 切换会话、`clearedForDisconnect()` 断连清理、
  App 杀进程重启，都会把内存态 `pendingMessages` 清空且不落盘不恢复。
- 影响：P00 —— 用户点过发送的消息是明确指令，失败后既不重发也不可见 = 静默丢指令，不可接受。

## 排查过程

1. **定位清空点**：grep `pendingMessages` 发现三处写空：`openSession()` 里 `pendingMessages = emptyList()`
   （切会话重置）、`clearedForDisconnect()` 里 `pendingMessages = emptyList()`（断连清理）、
   `SessionUiState` 默认值 `emptyList()`（App 启动）。全部是「内存清空、无落盘、无恢复」。
2. **排除其它假设**：`closeSession()`（退回列表）只清 `currentSessionId/events`，**不清** pending ——
   所以「退出列表再回来」的丢失不是 `closeSession` 直接清，而是回来时 `openSession` 无条件
   `pendingMessages = emptyList()` 覆盖了残留内存态。
3. **确认根因边界**：pending 是纯内存状态，生命周期被绑在「单次进程内、单连接内、单会话视图内」；
   任何跨视图/跨连接/跨进程的边界都会丢。存储封装（Draft/Event/Session）已证明落盘机制成熟，
   唯独 pending 没有接入。

## 根因分析（5-Why，挖到设计模型缺陷）

- **表面原因**：`pendingMessages` 是 `SessionUiState` 的内存字段，`openSession`/`clearedForDisconnect`
  无条件置空，且无落盘/恢复。
- **Why 1 —— 为什么切会话/断连会把 pending 清空？**
  因为 pending 被设计成「会话级易变状态」，与 `events/queueItems/todos` 等**服务端投影**同类，
  在「切会话重置会话级状态」和「断连清理易变数据」两处统一被清空。
- **Why 2 —— 为什么把「本地待发送消息」和「服务端投影」混为一类？**
  因为状态机只建模了**单连接生命周期**：pending 的出生（send）与归宿（sent 删除 / echo 去重 / failed）
  都被假设在同一段连接内闭环；设计时没有把「进程被杀 / 会话切换 / 断线重连」当作一等场景来枚举。
  pending 本质是「**不可再生本地数据 + 用户明确指令的投递凭证**」，与服务端可重新拉取的投影完全不同，
  却沿用了投影的「内存态、可丢弃」处理约定。
- **Why 3 —— 为什么设计阶段没考虑「进程/会话切换」这个边界？**
  设计输入（IM 发送语义铁律 5）只规定了「点发送立即上屏 / 成功消失 / 失败 ❗ 重发」的**在线**交互，
  没有把「用户消息是明确指令、必须持久到可恢复」这条**数据持久化边界**写进 PRD/规则清单。
  设计被「即时通讯的乐观 UI」框架牵引，默认一切在连接内发生、失败态只在本次运行内有效。
- **Why 4 —— 为什么「实现需求」skill 没拦住这个缺口？**
  「实现需求」skill 的 PRD 模板与规则细化清单，只要求枚举交互状态与在线行为，
  **没有强制要求**：凡是承载「用户明确指令 / 不可再生数据」的功能，必须显式枚举
  「断线 / 杀进程 / 重启 / 切换视图」四个边界并声明持久化策略。于是实现者按模板逐项核对时，
  该边界从未被触发，缺口被漏掉。
- **Why 5（可推广教训）**：根因不是「忘了写文件」，而是**「数据归属模型」错误**——
  把「本地事实源（用户指令）」当作「服务端投影的缓存」管理。正确模型是：本地待发送消息是
  **客户端拥有的第一手事实**（写前日志，send 前先落盘），服务端才是投递结果的事实源；
  两者必须有独立生命周期，本地事实源的持久化与恢复是**一等公民**，而不是投影的附属清空项。

## 解法

- **方案**：复用既有存储封装机制（commonMain 接口 + androidMain `Mutex`+`rename` 原子写），
  新增 `PendingStore`（按 sessionId 维度键）持久化待发送消息：
  1. **写前日志**：pending 创建（Sending）与重发（failed→sending）时，在真正 `connection.send` **之前**
     先落盘（send 前持久化，任何时刻断线/杀进程都有据可查）。
  2. **失败落盘**：`connection.send` false → `markPendingFailed` 后落盘 Failed。
  3. **成功即删**：`markPendingSent` 后落盘（`pendingForDisk` 过滤掉 Sent → 空列表 → 删除记录），
     消息已归服务端，回显由服务端历史承载。
  4. **恢复时 sending→failed**：`openSession`/App 启动载入该会话 pendings，`restorePendingFromDisk`
     把 sending 一律转 failed（发送结果未知，避免自动重发造成重复投递，交用户点 ❗ 手动决定）。
  5. **回显去重兼容**：回显匹配移除 pending 后同步清理持久化记录；持久化只写 Sending/Failed，
     与 `matchPendingEcho` 的内存语义不变。

**核心 Code Diff**（关键几行 before/after）：

```kotlin
// PendingMessages.kt —— 新增两个纯函数（commonTest 直测）
+ @Serializable
+ enum class PendingStatus { Sending, Sent, Failed }
+
+ @Serializable
+ data class PendingMessage(...)
+
+ fun pendingForDisk(list: List<PendingMessage>): List<PendingMessage> =
+     list.filter { it.status != PendingStatus.Sent }
+
+ fun restorePendingFromDisk(list: List<PendingMessage>): List<PendingMessage> =
+     list.map { if (it.status == PendingStatus.Failed) it else it.copy(status = PendingStatus.Failed) }
```

```kotlin
// BridgeClient.kt —— sendMessage：写前日志 + 成功即删 + 失败落盘（单协程内顺序执行，无交错竞态）
  scope.launch {
-     if (connection.send(ClientCommand.SendMessage(sid, text))) {
+     if (localId != null) pendingStore.save(sid, persistablePendingFor(sid))  // 写前落盘 sending
+     if (connection.send(ClientCommand.SendMessage(sid, text))) {
          if (localId != null) {
              _session.update { s -> s.copy(pendingMessages = markPendingSent(...)) }
+             pendingStore.save(sid, persistablePendingFor(sid))  // Sent 不落盘 → 删除记录
          }
      } else {
          if (localId != null) {
              _session.update { s -> s.copy(pendingMessages = markPendingFailed(...)) }
+             pendingStore.save(sid, persistablePendingFor(sid))
          }
          ...
```

```kotlin
// BridgeClient.kt —— openSession：恢复该会话 pendings（sending→failed）
  scope.launch {
+     val restored = restorePendingFromDisk(pendingStore.load(sessionId))
+     if (restored.isNotEmpty()) {
+         _session.update { s ->
+             if (s.currentSessionId == sessionId)
+                 s.copy(pendingMessages = restored + s.pendingMessages.filterNot { it.sessionId == sessionId })
+             else s
+         }
+         pendingStore.save(sessionId, restored)
+     }
      // 原事件缓存载入 + 订阅逻辑不变
  }
```

- 提交哈希：`a7685ec`（`feature/pending-persist` 工作树分支，不 merge，主对话统一合并）。
- 回归测试：新增 10 例单测 —— `PendingMessagesTest` 3 例（`restorePendingFromDisk` sending→failed /
  sent 防御转 failed / `pendingForDisk` 排除 Sent）；`PendingStoreTest` 5 例（持久化往返 / 空列表删记录 /
  多会话隔离 / 缺失返回空 / 路径字符消毒）；`BridgeClientTest` 2 例（发送失败落盘+重开恢复 /
  openSession 恢复 sending 为 failed）。

## 后续改进计划

- 待办：回填 commit 哈希；主对话统一 merge 到 main 并做真机复验。
- 教训推广（写入 skill，见本次报告）：「实现需求」skill 的 PRD 模板与规则细化清单强制要求——
  **凡是承载用户明确指令/不可再生数据的功能，必须枚举「断线/杀进程/重启/切换视图」边界并声明持久化策略**。
- 同类排查清单：凡 `SessionUiState` 里新增「本地事实源」字段（非服务端投影），必须自问：
  它是不是用户不可再生的输入？是 → 必须落盘 + 恢复，不能随 `clearedForDisconnect`/`openSession` 清空。

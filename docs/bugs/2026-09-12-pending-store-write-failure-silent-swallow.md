# [BUG] PendingStore 落盘写失败被静默吞——存储满时待发送消息仅存内存、杀进程即丢

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已修复 |
| 仓库/模块 | App（`PendingStore.android.kt` 写落盘 + `PendingStore.kt` 接口 + 发送/重放编排） |
| 发现方式 | 混沌工程调研（用户拍板 #60-2，高优） |
| 日期 | 2026-09-12 |
| 相关 commit | `4265433`（代码 + 单测，main 工作树） |
| 关联文档 | `docs/bugs/2026-09-07-pending-message-lost-on-session-exit.md`（P00 持久化修复，本次是其「写失败」分支的补齐） |

## 背景

- P00 铁律「任何用户消息都是明确指令，永不丢失」落地为 `PendingStore`（P00 修复 commit `a7685ec`）：
  发送前写前落盘 Sending、失败落盘 Failed、成功即删、断线/重启恢复。
- `PendingStore.android.kt` 的 `writeFile` 用 `try { ... } catch (_: Exception) { /* 持久化失败不影响在线功能 */ }`
  **把存储满/写失败/IO 异常静默吞掉**，`save`/`update` 不向调用方回报失败。
- 该「吞异常」的写法是照抄其它缓存的惯例（DraftCache/EventCache/SessionCache 都「写失败不影响在线功能」），
  但对「待发送消息」这一**不可再生用户指令**，写失败 = 用户消息可能丢失，语义完全不同。

## 现象

- 存储满（或文件系统只读/IO 异常）时，用户点发送：
  - 内存态 `pendingMessages` 正常上屏（Sending/Failed ❗），发送/重放照常走内存路径；
  - 但落盘静默失败，**磁盘上没有任何记录**；
  - 杀进程/重启后，该消息**彻底消失**——用户明确发过的指令丢失，违背 P00。

## 排查过程

1. **混沌工程调研**逐文件审查持久化封装，发现 `writeFile` 的 `catch (_: Exception) {}` 吞掉了所有写失败，
   且 `save`/`update` 的返回类型（`Unit` / `List`）不携带「是否成功落盘」信号。
2. **对照其它缓存**：DraftCache/EventCache/SessionCache 都是「写失败不影响在线功能」（可接受，因为它们是缓存/投影，
   服务端或用户可再生成），唯独 PendingStore 存的是「用户明确指令」这一不可再生数据，套用同款吞异常是错误的。
3. **确认机会式重试的可行性**：`update` 是「读磁盘 → transform → 写磁盘」的原子读-改-写，天然在每次状态变更时
   重写全量列表——只要把「上次写失败的残留列表」记住，下一次 `update` 就能把它一并补落盘。

## 根因分析（5-Why）

- **表面原因**：`writeFile` 的 `catch (_: Exception)` 静默吞写失败，`save`/`update` 不回报失败。
- **Why 1 —— 为什么写失败会被静默吞？** 因为 `writeFile` 照抄了其它缓存（Draft/Event/Session）的「写失败不影响在线功能」兜底写法。
- **Why 2 —— 为什么 PendingStore 要照抄缓存的吞异常惯例？** 因为 P00 修复落地时，把「待发送消息持久化」当作「又一个缓存」，
  沿用了统一的封装模板，没有区分「**不可再生用户数据**」与「**可再生投影/缓存**」两类完全不同的失败语义。
- **Why 3 —— 为什么没有区分这两类数据的失败语义？** 因为持久化接口只定义了 `load/save/update` 的「成功路径」，
  没有把「写失败」作为一等结果暴露给调用方，调用方（发送/重放/ack）也无从感知「这条消息其实没落盘」。
- **Why 4 —— 为什么「写失败」没被当作一等场景设计？** 设计关注点是「断线/杀进程/重启」这些**进程生命周期**边界，
  而「磁盘满/只读/IO 异常」是**资源边界**，P00 的边界枚举（断线/杀进程/重启/切换视图）没有覆盖它；
  混沌工程正是为了补这类「资源故障注入」盲区。
- **Why 5（可推广教训）**：**「不可再生数据」的持久化必须把「写失败」当作一等结果**——失败要显式告警 + 标记 +
  可重试，不能复用「缓存写失败可忽略」的语义。凡是承载用户明确指令/不可再生数据的功能，落盘失败都不得静默。

## 解法

- **方案**：
  1. `PendingMessage` 增 `persisted: Boolean = true`（序列化，默认 true；load 出来的恒为 true）。
  2. `PendingStore.save` 返回 `Boolean`、`update` 返回的列表里 `persisted` 反映本次是否真正落盘成功；
     `writeFile` 失败时 `ConnLog.warn` 告警（不吞）。
  3. `AndroidPendingStore` 内部维护 `dirtyWrites`（sessionId → 上次写失败的完整残留列表），
     下一次 `save`/`update` 时机会式重试补落盘（磁盘恢复后自动消除 transient full 残留）。
  4. 新增 `persistPendingUpdate`/`persistPendingSave` 统一入口：落盘 + 把 `persisted` 标志同步回 `SessionUiState.pendingMessages`。
  5. UI：时间行在 ❗/Loading 之外，对 `persisted=false` 的消息显示 ⚠️（amber），语义区分「发送失败 ❗」与「未落盘 ⚠️」。
- 为什么不阻断发送：发送/重放仍走内存路径（磁盘故障不应阻塞用户发消息）；落盘失败用 ⚠️ + 机会式重试兜底，
  而非同步阻塞发送（移动端弱网/磁盘抖动下体验优先）。

**核心 Code Diff**（关键几行 before/after）：

```kotlin
// PendingStore.android.kt —— 写失败不再静默
-    private fun writeFile(sessionId: String, pending: List<PendingMessage>) {
-        try {
+    private fun writeFile(sessionId: String, pending: List<PendingMessage>): Boolean = try {
             ...
-            if (pending.isEmpty()) { if (f.exists()) f.delete(); return }
-            val tmp = File(dir, f.name + ".tmp")
-            tmp.writeText(...); if (!tmp.renameTo(f)) { ... }
-        } catch (_: Exception) {
-            // 持久化失败不影响在线功能
-        }
+            if (pending.isEmpty()) { if (f.exists()) f.delete(); return true }
+            writeRaw(f, BridgeJson.encodeToString(ListSerializer, pending))
+            true
+        } catch (e: Exception) {
+            ConnLog.warn("PENDING", "待发送消息落盘失败 sessionId=$sessionId 条数=${pending.size}: ${e.message}")
+            false
+        }
+
+    // update 内机会式重试：脏写残留作为基准，磁盘恢复后一并补落盘
+    val hadDirty = dirtyWrites[sessionId] != null
+    val base = dirtyWrites[sessionId] ?: readFile(fileOf(sessionId))
+    val next = transform(base)
+    val ok = if (!(hadDirty || next != base)) true else writeFile(sessionId, next)
+    return if (ok) { dirtyWrites.remove(sessionId); next.map { it.copy(persisted = true) } }
+           else { dirtyWrites[sessionId] = next; next.map { it.copy(persisted = false) } }
```

```kotlin
// PendingPersist.kt —— 落盘 + persisted 标志回写内存统一入口（调用方由 pendingStore.update 改走此入口）
+ internal suspend fun persistPendingUpdate(store, session, sessionId, transform) {
+     val persisted = store.update(sessionId, transform)
+     syncPersistedFlags(session, sessionId, persisted)
+ }
```

- 提交哈希：`4265433`（代码 + 单测，main 工作树，只 add 本任务文件）。
- 回归测试：新增 4 例 —— `PendingStoreTest` 3 例（save 写失败返回 false 不吞 / update 写失败标记 persisted=false /
  写失败后机会式重试补落盘）+ `BridgeClientTest` 1 例（写失败后内存 pending 的 persisted=false，UI ⚠️ 依据）。
  正常路径（往返/删除/多会话/并发原子）回归全绿。

## 后续改进计划

- 待办：回填 commit 哈希；设备验证（模拟器只读化数据目录注入写失败，若可行）。
- 教训推广：其它「不可再生数据」的持久化（如有新增）一律走「写失败显式化 + persisted 标记 + 机会式重试」，
  不得复用缓存类「写失败可忽略」的吞异常惯例。
- 同类排查清单：审查 DraftCache/EventCache/SessionCache/DeviceStore 的 catch 语义，确认它们确实是「可忽略」的缓存/投影，
  而非不可再生数据（本次确认它们均可再生成或服务端可回源，维持现状）。

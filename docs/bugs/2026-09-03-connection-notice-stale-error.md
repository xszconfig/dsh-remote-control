# 提示条系统：错误提示「过期残留」+ 双机制并存

- 日期：2026-09-03
- 涉及仓库：app（`composeApp/src/commonMain/`）
- 关联 commit：`fix/connection-notice`（哈希见文末「解法」）

## 背景

手机 App 顶部有两套独立的提示条机制：

1. **重连横幅** `ReconnectBanner`（「检测到连接断开，正在自动重连…」+ Loading），由 `BridgeClient._reconnecting: Boolean` 驱动，`hello` 到达时清除。
2. **错误横幅** `ErrorBanner`（「…（连接已断开）（还有 N 条）」），由 `SessionUiState.errors: List<String>` 驱动，只追加、只有用户点 ✕ 才清空。

两套机制在 `MainScreen` 的同一 `Column` 里上下堆叠，无互斥、无优先级，可同时出现甚至互相矛盾（重连中 + 过期错误同屏）。

## 现象

1. 会话列表顶部出现误报提示条：「订阅会话失败（连接已断开）（还有两条）」，但会话订阅正常、消息同步正常。
2. 期望：状态感知类提示在真正出错时出现，连接恢复后自动消失。
3. 「（还有两条）」含义不明：是还有 2 条提示在排队、被这条挡住？队列里那两条是什么？

## 排查过程

1. **代码定位**：`grep` 出「订阅会话失败（连接已断开）」的产生点 —— `BridgeClient.kt` 的 `openSession` 与 `handle(Hello)` 重订阅，均因 `connection.send(Subscribe)` 返回 `false`（未连接/发送异常）触发 `pushError`。
2. **确认「连接已断开」是客户端本地文案**：桥侧 subscribe 错误只有 `not_found`（英文），从不发「连接已断开」；该文案在客户端 `pushError` 字面量里。
3. **日志还原**（`/remote/phone-logs` + `/remote/logs`）：还原出完整时序 —— 断开状态打开会话 → `发送 subscribe 失败：未连接`（错误 #1）；随后 `hello 到达 → 连接异常中断(Software caused connection abort) → register_device 异常（错误 #2）→ subscribe 异常（错误 #3）`；52 秒后重连成功 + 订阅恢复，但三条错误仍残留。「还有两条」= 错误 #1 + #2，内容分别是「订阅会话失败（连接已断开）」与「设备注册失败（连接已断开）」。

## 根因分析（深层）

表面现象是「hello 到达 → 重订阅 → 连接随即又断 → send 抛异常」的竞态产生了误报。但**深层根因是状态模型缺陷**，不是竞态本身：

1. **`errors` 是「只进不出」的累积队列**：`pushError` 只 `(errors + message).takeLast(MAX_ERRORS)` 追加，唯一出口是用户手动 `dismissErrors()`。连接恢复的权威信号（`hello` 到达）只清 `reconnecting`，从不碰 `errors`——于是任何一次瞬断期间的「连接已断开」错误都会永久残留，与「订阅正常」的事实相悖。
2. **「连接已断开」被当成持久业务错误存储**：这类错误本质是**瞬时连接状态信号**（可自动恢复），却被与 `not_found`/鉴权等真正业务错误混在一个 `List<String>` 里，无法区分生命周期，只能一起堆积、一起手动清。
3. **两套提示机制无统一状态机**：`reconnecting`（布尔）与 `errors`（列表）各管各的，没有「error → reconnecting → recovered 清空」的单一状态转移，导致重连中与过期错误可同屏矛盾。
4. **「还有 N 条」是 `errors.size - 1` 的死计数**：其余条目永远无法查看内容（无展开视图），对用户是黑盒。

## 解法

引入统一「连接状态提示槽」状态机，并把错误按生命周期分两类：

- 新增 `ConnectionNotice`（`Hidden` / `Reconnecting(attempt)` / `Error(message)`）单一槽，取代 `_reconnecting: Boolean`。
- `pushError` 拆分为：
  - `pushConnectionError`（「连接已断开」类）：重连中**静默**（不堆积、不覆盖 Reconnecting）；否则展示并记 `NoticeError(recoverable = true)`。
  - `pushBusinessError`（服务端错误码）：记 `NoticeError(recoverable = false)`，需手动清除。
- `handle(Hello)` 做**错误对账**：`reconcileErrorsOnHello` 清掉 `recoverable=true`，业务错误保留；槽收敛为 `Hidden`（恢复静默）或最新业务错误。
- `SessionUiState.errors: List<String>` → `List<NoticeError>`。
- App 端 `ErrorBanner` → `ConnectionErrorBanner`（保留「还有 N 条」计数）+ `ErrorHistorySheet`（底部 sheet 可展开全部历史，🔌=连接类 / ⚠️=业务类）。

核心 Code Diff（`BridgeClient.kt`）：

```kotlin
sealed interface ConnectionNotice {
    data object Hidden : ConnectionNotice
    data class Reconnecting(val attempt: Int) : ConnectionNotice
    data class Error(val message: String) : ConnectionNotice
}

internal fun reconcileErrorsOnHello(errors: List<NoticeError>): List<NoticeError> =
    errors.filterNot { it.recoverable }

// handle(Hello) 内：
val keptErrors = reconcileErrorsOnHello(_session.value.errors)
_session.update { it.copy(errors = keptErrors) }
_notice.value = keptErrors.lastOrNull()?.let { ConnectionNotice.Error(it.message) }
    ?: ConnectionNotice.Hidden
```

- 验证：`./gradlew :composeApp:assembleDebug :composeApp:testDebugUnitTest` 全绿（含新增「hello 后清除」「重连中不堆错误」用例）。
- 提交哈希：`1fd1ab6635a38229c40732fafd5c73e6405268d7`（本分支 `fix/connection-notice`）。

## 后续改进计划

- 「设备页连接失败残留」（`connection.info.state=Error` 与 `connectedDevice` 自相矛盾）是**另一条提示面**，与本槽无关，由并行的 `fix/` 分支处理；两者合并后，可考虑把 LandingScreen 的「连接失败」卡片也纳入统一状态机。
- 「还有 N 条」计数当前仍混计连接类与业务类错误；若希望计数只反映业务错误，可进一步把连接类错误移出 `errors`（纯槽态）——需用户拍板。

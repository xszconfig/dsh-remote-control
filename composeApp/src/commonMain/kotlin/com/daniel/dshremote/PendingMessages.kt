package com.daniel.dshremote

import kotlin.math.abs
import kotlinx.serialization.Serializable

/**
 * 消息发送状态机（IM 标准模式，纯客户端本地状态，无 Compose 依赖，commonTest 直测）。
 *
 * 生命周期（at-least-once + msgId 幂等）：
 * - 点发送 → [Sending]（用户气泡立即上屏 + 时间行小 Loading）；写前落盘（msgId 随记录持久化）。
 * - `connection.send` 返回 true → [Sent]（帧已写，Loading 消失；等服务端 ack / 回显接棒）。
 * - 服务端 `ack{msgId,ok:true}` 到达 → 删除该 pending（ack 先行，早于回显）；消息已归服务端。
 * - 服务端 `ack{msgId,ok:false}` / `error{msgId}` → [Failed]（红 ❗，计重放次数）。
 * - `connection.send` 返回 false → [Failed]。
 * - 服务端 user_message 回显到达 → 按「同会话 + 同文本 + 时间相近」匹配 → 移除该 pending
 *   （去重仍保留，作为旧桥/ack 丢失时的兜底）。
 * - 断线重连/会话打开 → 对 failed 且 retryCount < [MAX_AUTO_RETRY] 的 pending 自动重放（同 msgId）。
 * - 手动点 ❗ → 重置 retryCount，重新发送。
 *
 * 持久化契约（P00）：任何用户消息都是明确指令，Sending/Failed 必须落盘；
 * [Sent] 不落盘（帧已写桥、等 ack/回显）；恢复时 sending 一律转 failed（结果未知，交重放/手动）。
 */

/** 待发送消息状态：sending（Loading）/ sent（帧已写，无图标）/ failed（红 ❗，点击重发）。 */
@Serializable
enum class PendingStatus { Sending, Sent, Failed }

/** 一条本地待发送消息（乐观上屏 + 送达确认 + 失败重发）；序列化用于跨重启落盘。 */
@Serializable
data class PendingMessage(
    /** 客户端生成的消息幂等键（m-<sessionId 前缀>-<ts>-<seq>），重发复用、服务端据此去重。 */
    val msgId: String,
    val sessionId: String,
    val text: String,
    val status: PendingStatus,
    val createdAt: Long,
    /** 已自动重放次数；手动重发重置为 0。 */
    val retryCount: Int = 0,
)

/** 回显去重匹配时间窗口（毫秒）：echo 时间戳与本地 createdAt 相差在此内视为同一条，容忍时钟偏移。 */
const val PENDING_ECHO_MATCH_WINDOW_MS: Long = 60_000L

/** 断线自动重放次数上限：超过则保持 failed ❗ 交用户手点。 */
const val MAX_AUTO_RETRY: Int = 3

/** 自动重放退避基数（毫秒）；第 n 次重放前等待 base<<(n-1)，即 1s/2s/4s。 */
const val AUTO_RETRY_BACKOFF_BASE_MS: Long = 1_000L

/** 追加一条 sending 项（sendMessage 建 pending 时调用）。 */
fun addPending(list: List<PendingMessage>, p: PendingMessage): List<PendingMessage> = list + p

/** 状态迁移：failed → sending（手动重发点击；重置自动重放计数）。 */
fun markPendingSending(list: List<PendingMessage>, msgId: String): List<PendingMessage> =
    list.map { if (it.msgId == msgId) it.copy(status = PendingStatus.Sending, retryCount = 0) else it }

/** 状态迁移：sending → sent（送达 bridge）。 */
fun markPendingSent(list: List<PendingMessage>, msgId: String): List<PendingMessage> =
    list.map { if (it.msgId == msgId) it.copy(status = PendingStatus.Sent) else it }

/** 状态迁移：sending → failed（connection.send false）。 */
fun markPendingFailed(list: List<PendingMessage>, msgId: String): List<PendingMessage> =
    list.map { if (it.msgId == msgId) it.copy(status = PendingStatus.Failed) else it }

/** 移除一条（ack ok / 服务端回显到达后，由服务端权威气泡承载）。 */
fun removePending(list: List<PendingMessage>, msgId: String): List<PendingMessage> =
    list.filterNot { it.msgId == msgId }

/** 服务端 ack 确认送达：移除该条（ack 先行，早于回显）。 */
fun ackPendingOk(list: List<PendingMessage>, msgId: String): List<PendingMessage> =
    removePending(list, msgId)

/** 服务端拒绝（ack ok=false / error 带 msgId）：转 failed 并计重放次数 +1（与自动重放同配额）。 */
fun markPendingRejected(list: List<PendingMessage>, msgId: String): List<PendingMessage> =
    list.map { if (it.msgId == msgId) it.copy(status = PendingStatus.Failed, retryCount = it.retryCount + 1) else it }

/**
 * 服务端回显去重匹配：返回命中的 pending msgId（null = 未命中）。
 *
 * 规则：同 sessionId + 同 text + |echoTs - createdAt| 在 [PENDING_ECHO_MATCH_WINDOW_MS] 内；
 * 取最早（FIFO）命中的一条——同一文本连续快速发两条时，第一条回显匹配第一条 pending。
 */
fun matchPendingEcho(
    list: List<PendingMessage>,
    sessionId: String,
    text: String,
    echoTs: Long,
): String? =
    list.firstOrNull {
        it.sessionId == sessionId && it.text == text &&
            abs(echoTs - it.createdAt) < PENDING_ECHO_MATCH_WINDOW_MS
    }?.msgId

/**
 * 从磁盘恢复待发送消息：sending 一律转 failed。
 *
 * 原因：发送结果未知（可能已送达 bridge、也可能没发出去），自动重发会造成重复投递；
 * 转成 failed 交给自动重放（带次数上限）或用户点 ❗ 手动决定。Sent 理论上不会落盘，
 * 防御性也转 failed。retryCount 原样保留（跨重启不重置自动重放配额）。
 */
fun restorePendingFromDisk(list: List<PendingMessage>): List<PendingMessage> =
    list.map { if (it.status == PendingStatus.Failed) it else it.copy(status = PendingStatus.Failed) }

/** 该会话可自动重放的 failed pending（按序，retryCount < [MAX_AUTO_RETRY]）。 */
fun autoRetryablePendings(list: List<PendingMessage>, sessionId: String): List<PendingMessage> =
    list.filter {
        it.sessionId == sessionId && it.status == PendingStatus.Failed && it.retryCount < MAX_AUTO_RETRY
    }

/** 自动重放前迁移：failed → sending，计数 +1（同一 msgId 幂等重发）。 */
fun markPendingAutoRetry(p: PendingMessage): PendingMessage =
    p.copy(status = PendingStatus.Sending, retryCount = p.retryCount + 1)

/** 自动重放退避：第 attemptIndex 次（0-based）重放前等待 base<<attemptIndex，即 1s/2s/4s。 */
fun autoRetryBackoffMs(attemptIndex: Int): Long =
    AUTO_RETRY_BACKOFF_BASE_MS shl attemptIndex.coerceAtLeast(0)

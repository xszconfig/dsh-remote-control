package com.daniel.dshremote

import kotlin.math.abs

/**
 * 消息发送状态机（IM 标准模式，纯客户端本地状态，无 Compose 依赖，commonTest 直测）。
 *
 * 生命周期：
 * - 点发送 → [Sending]（用户气泡立即上屏 + 时间行小 Loading）。
 * - 送达 bridge → [Sent]（Loading 消失，不打勾；气泡保留，等服务端回显接棒）。
 * - 服务端 user_message 回显到达 → 按「同会话 + 同文本 + 时间相近」匹配 → 移除该 pending，
 *   由回显作为权威气泡渲染（避免同一消息显示两份）。
 * - 发送失败（connection.send false）→ [Failed]（时间行红色 ❗，点击重发）。
 * - 重发点击 → [Sending] → 再次进入送达/失败分支，循环取决于网络/服务状态。
 */

/** 待发送消息状态：sending（Loading）/ sent（已送达，无图标）/ failed（红 ❗，点击重发）。 */
enum class PendingStatus { Sending, Sent, Failed }

/** 一条本地待发送消息（乐观上屏 + 送达回显去重 + 失败重发）。 */
data class PendingMessage(
    val localId: String,
    val sessionId: String,
    val text: String,
    val status: PendingStatus,
    val createdAt: Long,
)

/** 回显去重匹配时间窗口（毫秒）：echo 时间戳与本地 createdAt 相差在此内视为同一条，容忍时钟偏移。 */
const val PENDING_ECHO_MATCH_WINDOW_MS: Long = 60_000L

/** 追加一条 sending 项（sendMessage 建 pending 时调用）。 */
fun addPending(list: List<PendingMessage>, p: PendingMessage): List<PendingMessage> = list + p

/** 状态迁移：failed → sending（重发点击）。 */
fun markPendingSending(list: List<PendingMessage>, localId: String): List<PendingMessage> =
    list.map { if (it.localId == localId) it.copy(status = PendingStatus.Sending) else it }

/** 状态迁移：sending → sent（送达 bridge）。 */
fun markPendingSent(list: List<PendingMessage>, localId: String): List<PendingMessage> =
    list.map { if (it.localId == localId) it.copy(status = PendingStatus.Sent) else it }

/** 状态迁移：sending → failed（connection.send false）。 */
fun markPendingFailed(list: List<PendingMessage>, localId: String): List<PendingMessage> =
    list.map { if (it.localId == localId) it.copy(status = PendingStatus.Failed) else it }

/** 移除一条（服务端回显到达后，由回显作为权威气泡）。 */
fun removePending(list: List<PendingMessage>, localId: String): List<PendingMessage> =
    list.filterNot { it.localId == localId }

/**
 * 服务端回显去重匹配：返回命中的 pending localId（null = 未命中）。
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
    }?.localId

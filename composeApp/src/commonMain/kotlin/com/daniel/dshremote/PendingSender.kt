package com.daniel.dshremote

import com.daniel.dshremote.protocol.ClientCommand
import com.daniel.dshremote.protocol.ServerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 待发送消息的「送达确认 + 断线自动重放」编排（从 BridgeClient 拆出，缓解大类）。
 * 持有连接、持久化 store 与会话状态投影，负责 idle 分支消息的 ack 处理与自动重放。
 * 状态迁移纯函数仍在 PendingMessages.kt；此处只做「读状态 → 调 store/连接 → 写状态」的编排。
 */
class PendingSender(
    private val scope: CoroutineScope,
    private val connection: ConnectionManager,
    private val pendingStore: PendingStore,
    private val session: MutableStateFlow<SessionUiState>,
) {

    /**
     * 断线自动重放：对会话内 retryCount < [MAX_AUTO_RETRY] 的 failed 消息按序自动重发（同 msgId 幂等）。
     * 每条消息在本次触发内最多补到 [MAX_AUTO_RETRY] 次，间隔按 retryCount 递增（1s/2s/4s）；
     * 达上限保持 failed ❗ 交用户手点。触发点：openSession 恢复后、hello 重连后。
     */
    fun autoReplaySession(sessionId: String) {
        val toReplay = autoRetryablePendings(session.value.pendingMessages, sessionId)
        if (toReplay.isEmpty()) return
        ConnLog.info("ACTION", "自动重放会话 $sessionId 的 ${toReplay.size} 条待发送消息（上限 $MAX_AUTO_RETRY 次）")
        toReplay.forEach { p ->
            scope.launch { replayWithRetries(sessionId, p.msgId) }
        }
    }

    /** 单条消息的自动重放循环：失败则退避重试，直到送达、达上限、或被 ack/回显/用户处理。 */
    private suspend fun replayWithRetries(sessionId: String, msgId: String) {
        while (true) {
            val cur = session.value.pendingMessages.firstOrNull { it.msgId == msgId } ?: return
            if (cur.retryCount >= MAX_AUTO_RETRY) return
            if (cur.status != PendingStatus.Sending && cur.status != PendingStatus.Failed) return
            val backoff = autoRetryBackoffMs(cur.retryCount)
            if (backoff > 0) delay(backoff)
            // delay 后复核：期间可能已被 ack/回显移除，或用户已手动处理、或已超上限
            val fresh = session.value.pendingMessages.firstOrNull { it.msgId == msgId } ?: return
            if (fresh.retryCount >= MAX_AUTO_RETRY) return
            if (fresh.status != PendingStatus.Sending && fresh.status != PendingStatus.Failed) return
            val next = markPendingAutoRetry(fresh)
            session.update { s ->
                s.copy(pendingMessages = s.pendingMessages.map { if (it.msgId == msgId) next else it })
            }
            persistPendingUpdate(pendingStore, session, sessionId) { list -> list.filterNot { it.msgId == msgId } + next }
            if (connection.send(ClientCommand.SendMessage(sessionId, next.text, msgId))) {
                ConnLog.info("ACTION", "自动重放送达 msgId=$msgId（第 ${next.retryCount} 次）")
                session.update { s -> s.copy(pendingMessages = markPendingSent(s.pendingMessages, msgId)) }
                persistPendingUpdate(pendingStore, session, sessionId) { list -> list.filterNot { it.msgId == msgId } }
                return
            }
            ConnLog.warn("ACTION", "自动重放失败 msgId=$msgId（第 ${next.retryCount} 次）ws=${connection.info.value.state}")
            session.update { s -> s.copy(pendingMessages = markPendingFailed(s.pendingMessages, msgId)) }
            persistPendingUpdate(pendingStore, session, sessionId) { list ->
                list.filterNot { it.msgId == msgId } + next.copy(status = PendingStatus.Failed)
            }
        }
    }

    /** 消息发送确认（ack 先行，早于回显）：ok=true 删除持久化记录；ok=false 转 failed 计重放次数。 */
    fun handleAck(ev: ServerEvent.Ack) {
        val p = session.value.pendingMessages.firstOrNull { it.msgId == ev.msgId }
        if (ev.ok) {
            ConnLog.info("ACTION", "ack ok msgId=${ev.msgId}，消息已归服务端，删除持久化记录")
            session.update { s -> s.copy(pendingMessages = ackPendingOk(s.pendingMessages, ev.msgId)) }
            if (p != null) {
                scope.launch { persistPendingUpdate(pendingStore, session, p.sessionId) { list -> list.filterNot { it.msgId == ev.msgId } } }
            }
        } else {
            ConnLog.warn("ACTION", "ack fail msgId=${ev.msgId}，服务端拒绝，转 failed 计次")
            rejectByMsgId(ev.msgId)
        }
    }

    /** 服务端拒绝（ack ok=false / error 带 msgId）：精确关联回 pending，转 failed 计次，消除静默失败。 */
    fun rejectByMsgId(msgId: String) {
        val p = session.value.pendingMessages.firstOrNull { it.msgId == msgId } ?: return
        session.update { s -> s.copy(pendingMessages = markPendingRejected(s.pendingMessages, msgId)) }
        scope.launch {
            persistPendingUpdate(pendingStore, session, p.sessionId) { list ->
                list.filterNot { it.msgId == msgId } + p.copy(status = PendingStatus.Failed, retryCount = p.retryCount + 1)
            }
        }
    }
}

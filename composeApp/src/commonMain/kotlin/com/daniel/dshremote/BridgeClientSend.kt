package com.daniel.dshremote

import com.daniel.dshremote.protocol.ApprovalDecision
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.BridgeJson
import com.daniel.dshremote.protocol.ClientCommand
import com.daniel.dshremote.protocol.QuestionAnswerItemWire
import com.daniel.dshremote.protocol.QuestionRequestWire
import com.daniel.dshremote.protocol.QueueItemWire
import com.daniel.dshremote.protocol.ServerLogEntry
import com.daniel.dshremote.protocol.ServerLogsResponse
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun BridgeClient.sendMessage(text: String) {
    val sid = _session.value.currentSessionId
    if (sid == null) {
        // 关键排查点：当前无会话时发送被静默丢弃，必须显式记录
        ConnLog.warn("CMD", "发送消息丢弃：无当前会话（sessionId=null）textLen=${text.length} 首20字=\"${text.take(20)}\"")
        return
    }
    // 乐观入队：会话运行中时新消息必然进服务端队列——立即在面板显示，
    // 不等 spliced 回环广播（服务端 session_queue 到达后自然替换）。
    val running = _session.value.sessions.any { it.id == sid && it.status == "running" }
    ConnLog.info(
        "CMD",
        "发送消息 sessionId=$sid textLen=${text.length} 首20字=\"${text.take(20)}\" " +
            "running=$running ws=${connection.info.value.state}",
    )
    if (running) {
        val opt = QueueItemWire(id = "local-${nowMillis()}", placement = "queued", text = text)
        _session.update { s ->
            if (s.currentSessionId == sid) s.copy(queueItems = insertOptimisticQueued(s.queueItems, opt)) else s
        }
    }
    // 语义（用户澄清）：仅 Agent 非运行中（队列空、消息被立即消费）才走 PendingBubble 状态机
    // （乐观上屏 + 时间行 Loading/❗）；运行中消息进排队队列，只在排队面板显示，不得上屏。
    // msgId 无条件生成：idle 走 pending 持久化+重放；running 仅作 wire 幂等键（阶段2 再落盘追踪）。
    val msgId = "m-${sid.take(8)}-${nowMillis()}-${pendingIdSeq++}"
    val pending: PendingMessage? = if (!running) {
        PendingMessage(
            msgId = msgId,
            sessionId = sid,
            text = text,
            status = PendingStatus.Sending,
            createdAt = nowMillis(),
        )
    } else {
        null
    }
    if (pending != null) {
        _session.update { s ->
            if (s.currentSessionId == sid) s.copy(pendingMessages = addPending(s.pendingMessages, pending)) else s
        }
    }
    scope.launch {
        // P00 写前日志（write-ahead）：先原子落盘 sending 再真正发送——任何时刻断线/杀进程，
        // 该消息（含 msgId）都已持久化，恢复后自动重放/手动重发复用同一 msgId 幂等。
        if (pending != null) {
            pendingStore.update(sid) { list -> list.filterNot { it.msgId == pending.msgId } + pending }
        }
        if (connection.send(ClientCommand.SendMessage(sid, text, msgId))) {
            if (pending != null) {
                ConnLog.info("ACTION", "发送送达 msgId=${pending.msgId}（sending→sent，等 ack/回显）")
                _session.update { s -> s.copy(pendingMessages = markPendingSent(s.pendingMessages, pending.msgId)) }
                // 帧已写桥 → 从持久化记录删除该条（ack ok 或回显接棒，服务端历史承载）
                pendingStore.update(sid) { list -> list.filterNot { it.msgId == pending.msgId } }
            }
        } else {
            ConnLog.error("CMD", "发送消息失败 sessionId=$sid textLen=${text.length} ws=${connection.info.value.state}")
            if (pending != null) {
                _session.update { s -> s.copy(pendingMessages = markPendingFailed(s.pendingMessages, pending.msgId)) }
                pendingStore.update(sid) { list ->
                    list.filterNot { it.msgId == pending.msgId } + pending.copy(status = PendingStatus.Failed)
                }
            }
            pushConnectionError("「${text.take(20)}」未发送：连接已断开")
            // 发送失败：回滚乐观排队项（仅运行中有；非运行中无 local- 项，filter 为 no-op）
            _session.update { s ->
                s.copy(queueItems = s.queueItems.filterNot { it.text == text && it.id.startsWith("local-") })
            }
        }
    }
}

/** 手动重发一条失败的待发送消息：failed → sending（重置自动重放计数）→ 重发（同 msgId 幂等）。 */
fun BridgeClient.retryMessage(msgId: String) {
    val p = _session.value.pendingMessages.firstOrNull { it.msgId == msgId } ?: return
    if (p.status != PendingStatus.Failed) return
    ConnLog.info("ACTION", "重发点击 msgId=$msgId sessionId=${p.sessionId} textLen=${p.text.length}")
    _session.update { s -> s.copy(pendingMessages = markPendingSending(s.pendingMessages, msgId)) }
    scope.launch {
        // 重发前先原子落盘 sending（重置 retryCount=0），断线/杀进程后可恢复为 failed
        pendingStore.update(p.sessionId) { list ->
            list.filterNot { it.msgId == msgId } + p.copy(status = PendingStatus.Sending, retryCount = 0)
        }
        if (connection.send(ClientCommand.SendMessage(p.sessionId, p.text, msgId))) {
            ConnLog.info("ACTION", "重发送达 msgId=$msgId（sending→sent，等 ack/回显）")
            _session.update { s -> s.copy(pendingMessages = markPendingSent(s.pendingMessages, msgId)) }
            pendingStore.update(p.sessionId) { list -> list.filterNot { it.msgId == msgId } }
        } else {
            ConnLog.error("CMD", "重发失败 msgId=$msgId ws=${connection.info.value.state}")
            _session.update { s -> s.copy(pendingMessages = markPendingFailed(s.pendingMessages, msgId)) }
            pendingStore.update(p.sessionId) { list ->
                list.filterNot { it.msgId == msgId } + p.copy(status = PendingStatus.Failed)
            }
            pushConnectionError("「${p.text.take(20)}」未发送：连接已断开")
        }
    }
}

fun BridgeClient.interrupt(sessionId: String, mode: String = "clear") {
    val s = _session.value
    val status = s.sessions.firstOrNull { it.id == sessionId }?.status
    ConnLog.info("CMD", "中断会话 sessionId=$sessionId mode=$mode status=$status modelWaitingSince=${s.modelWaitingSince}")
    scope.launch {
        if (!connection.send(ClientCommand.Interrupt(sessionId, mode))) pushConnectionError("中断指令发送失败（连接已断开）")
    }
}

/** 切换当前会话模型（下一步 prompt 组装边界生效，不打断当前推理；reasoningEffort 可选）。 */
fun BridgeClient.setModel(sessionId: String, provider: String, model: String, reasoningEffort: String? = null) {
    ConnLog.info("MODEL", "切换模型 sessionId=$sessionId provider=$provider model=$model effort=$reasoningEffort")
    scope.launch {
        if (!connection.send(ClientCommand.SetModel(sessionId, provider, model, reasoningEffort))) {
            pushConnectionError("切换模型指令发送失败（连接已断开）")
        }
    }
}

/** 排队消息操作：steer = 插队（注入当前轮）；remove = 移除排队消息。 */
/** 加载更早的一页历史（seq < 当前窗口最小 seq）。 */
fun BridgeClient.loadOlderPage(sessionId: String) {
    val s = _session.value
    if (s.currentSessionId != sessionId || s.loadingOlder || !s.hasMore) return
    val beforeSeq = s.events.minOfOrNull { it.seq } ?: return
    ConnLog.info("CMD", "翻页加载 sessionId=$sessionId beforeSeq=$beforeSeq 窗口=${s.events.size}")
    olderLoadStartAt[sessionId] = nowMillis()
    _session.update { it.copy(loadingOlder = true) }
    scope.launch {
        if (!connection.send(ClientCommand.HistoryPage(sessionId, beforeSeq, 300))) {
            _session.update { it.copy(loadingOlder = false) }
            pushConnectionError("加载更早消息失败（连接已断开）")
        }
    }
}

fun BridgeClient.sendQueueAction(sessionId: String, itemId: String, action: String) {
    ConnLog.info("CMD", "排队操作 sessionId=$sessionId itemId=$itemId action=$action")
    scope.launch {
        if (!connection.send(ClientCommand.QueueAction(sessionId, itemId, action))) {
            pushConnectionError("排队操作发送失败（连接已断开）")
        }
    }
}

/** 调试控制：resume / step / step_out / stop / variables（带引用）。 */
fun BridgeClient.sendDebugCommand(sessionId: String, action: String, variablesReference: String? = null) {
    ConnLog.info("CMD", "调试指令 sessionId=$sessionId action=$action variablesReference=$variablesReference")
    scope.launch {
        if (!connection.send(ClientCommand.DebugCommand(sessionId, action, variablesReference))) {
            pushConnectionError("调试指令发送失败（连接已断开）")
        }
    }
}

/** 裁决审批：bridge 持有走 approve；桌面端（mux）持有走 answer_approval。 */
fun BridgeClient.approve(approval: ApprovalRequestWire, decision: ApprovalDecision) {
    _session.update { it.copy(decidingApprovalId = approval.approvalId) }
    scope.launch {
        val command = if (approval.rpcId != null) {
            ConnLog.info("APPROVAL", "裁决桌面审批 approval=${approval.approvalId.take(8)} decision=${decision.wire}（answer_approval）")
            ClientCommand.AnswerApproval(
                rpcId = approval.rpcId,
                sessionId = approval.sessionId,
                approvalId = approval.approvalId,
                decision = decision,
            )
        } else {
            ConnLog.info("APPROVAL", "裁决审批 approval=${approval.approvalId.take(8)} decision=${decision.wire}（approve）")
            ClientCommand.Approve(approval.approvalId, decision)
        }
        if (!connection.send(command)) {
            _session.update { it.copy(decidingApprovalId = null) }
            pushConnectionError("审批决策发送失败（连接已断开）")
        }
    }
}

/** 提交提问答案（桌面端持有的 ask_user_question）。 */
fun BridgeClient.answerQuestion(question: QuestionRequestWire, answers: List<QuestionAnswerItemWire>) {
    _session.update { it.copy(decidingQuestionRpcId = question.rpcId) }
    ConnLog.info("QUESTION", "提交提问答案 rpc=${question.rpcId.take(8)} answers=${answers.size}")
    scope.launch {
        val sent = connection.send(
            ClientCommand.AnswerQuestion(
                rpcId = question.rpcId,
                sessionId = question.sessionId,
                answers = answers,
            ),
        )
        if (!sent) {
            _session.update { it.copy(decidingQuestionRpcId = null) }
            pushConnectionError("提问答案发送失败（连接已断开）")
        }
    }
}

/** 清空错误提示（含统一槽）。 */
fun BridgeClient.dismissErrors() {
    _session.update { it.copy(errors = emptyList()) }
    _notice.value = ConnectionNotice.Hidden
}

/** 关闭服务端重启通知横幅：记下该服务端标识的已读版本，并隐藏横幅。 */
fun BridgeClient.dismissBootNotice() {
    val boot = _session.value.serverBoot ?: return
    val device = _session.value.connectedDevice
    val key = device?.let { bootNoticeKey(it) } ?: "unknown"
    _session.update { it.copy(serverBoot = null) }
    scope.launch { bootNoticeCache.save(key, boot.version) }
}

/** 拉取服务端结构化连接日志（/remote/logs）。已连接时用当前设备，未连接时回退最近在线设备。 */
suspend fun BridgeClient.loadServerLogs(limit: Int = 300): List<ServerLogEntry>? {
    val device = _session.value.connectedDevice
        ?: devices.state.value.devices.maxByOrNull { it.lastSeenAt }
        ?: return null
    return try {
        val resp = logsClient.get("http://${device.host}:${device.port}/remote/logs?limit=$limit")
        BridgeJson.decodeFromString(ServerLogsResponse.serializer(), resp.bodyAsText()).entries
    } catch (e: Exception) {
        ConnLog.warn("LOG", "拉取服务端日志失败: ${e.message}")
        null
    }
}

// ---- 内部 ----

/** 连接类错误（「连接已断开」）：重连中静默（不堆积、不覆盖 Reconnecting 槽）；否则展示于槽并按可自动恢复入历史。 */
internal fun BridgeClient.pushConnectionError(message: String) {
    if (_notice.value is ConnectionNotice.Reconnecting) {
        // 重连中已用 Reconnecting 表达状态，抑制「连接已断开」类错误，避免矛盾同屏
        return
    }
    _session.update { it.copy(errors = (it.errors + NoticeError(message, recoverable = true)).takeLast(MAX_ERRORS)) }
    _notice.value = ConnectionNotice.Error(message)
}

/** 业务类错误（服务端错误码）：入历史、展示于槽、需手动清除（hello 不清）。internal 供单测。 */
internal fun BridgeClient.pushBusinessError(message: String) {
    _session.update { it.copy(errors = (it.errors + NoticeError(message, recoverable = false)).takeLast(MAX_ERRORS)) }
    if (_notice.value !is ConnectionNotice.Reconnecting) {
        _notice.value = ConnectionNotice.Error(message)
    }
}

package com.daniel.dshremote

import com.daniel.dshremote.protocol.ClientCommand
import com.daniel.dshremote.protocol.ServerEvent
import com.daniel.dshremote.protocol.StoredDevice
import com.daniel.dshremote.protocol.StoredEndpoint
import io.ktor.client.request.get
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 结果交付 D3：goal 终态 → 主动通知（幂等键 = goal.updatedAt，客户端侧）。 */
internal fun BridgeClient.notifyGoalDelivery(sessionId: String, updatedAt: Long, blocked: Boolean, blockedMessage: String?) {
    val s = _session.value
    val sess = s.sessions.firstOrNull { it.id == sessionId }
    notifications.onDelivery(sessionId, sess?.name, sess?.parentSessionId != null, "goal-$updatedAt", blocked, blockedMessage)
}

internal fun BridgeClient.handleApprovalRequest(ev: ServerEvent.ApprovalRequest) {
    val known = _session.value.approvals.any { it.approvalId == ev.approval.approvalId }
    ConnLog.info(
        "APPROVAL",
        "收到审批 approval=${ev.approval.approvalId.take(8)} tool=${ev.approval.toolName} " +
            "rpc=${ev.approval.rpcId?.take(8) ?: "bridge-held"}${if (known) "（重复，忽略）" else ""}",
    )
    if (!known) {
        platformVibrateApproval()
        notifications.onApprovalArrived(
            ev.approval.approvalId, ev.approval.sessionId, ev.approval.toolName, ev.approval.reason, ev.approval.command,
        )
    }
    _session.update { s ->
        s.copy(approvals = if (known) s.approvals else s.approvals + ev.approval)
    }
}

internal fun BridgeClient.handleApprovalResolved(ev: ServerEvent.ApprovalResolved) {
    notifications.onApprovalResolved(ev.approvalId)
    _session.update { s ->
        ConnLog.info("APPROVAL", "审批已解决 approval=${ev.approvalId.take(8)} outcome=${ev.outcome}")
        s.copy(
            approvals = s.approvals.filterNot { a -> a.approvalId == ev.approvalId },
            decidingApprovalId = s.decidingApprovalId.takeIf { it != ev.approvalId },
        )
    }
}

internal fun BridgeClient.handleApprovalSettledLegacy(ev: ServerEvent.ApprovalSettledLegacy) {
    notifications.onApprovalResolved(ev.approvalId)
    _session.update { s ->
        ConnLog.info("APPROVAL", "审批已解决（旧版事件）approval=${ev.approvalId.take(8)}")
        s.copy(
            approvals = s.approvals.filterNot { a -> a.approvalId == ev.approvalId },
            decidingApprovalId = s.decidingApprovalId.takeIf { it != ev.approvalId },
        )
    }
}

internal fun BridgeClient.handleQuestionRequest(ev: ServerEvent.QuestionRequest) {
    val known = _session.value.questions.any { it.rpcId == ev.question.rpcId }
    ConnLog.info(
        "QUESTION",
        "收到提问 rpc=${ev.question.rpcId.take(8)} questions=${ev.question.questions.size}${if (known) "（重复，忽略）" else ""}",
    )
    if (!known) {
        platformVibrateApproval()
        notifications.onQuestionArrived(
            ev.question.rpcId, ev.question.sessionId, ev.question.questions.size, ev.question.questions.firstOrNull()?.question,
        )
    }
    _session.update { s ->
        s.copy(questions = if (known) s.questions else s.questions + ev.question)
    }
}

internal fun BridgeClient.handleQuestionResolved(ev: ServerEvent.QuestionResolved) {
    notifications.onQuestionResolved(ev.rpcId)
    _session.update { s ->
        ConnLog.info("QUESTION", "提问已解决 rpc=${ev.rpcId.take(8)} outcome=${ev.outcome}")
        s.copy(
            questions = s.questions.filterNot { q -> q.rpcId == ev.rpcId },
            decidingQuestionRpcId = s.decidingQuestionRpcId.takeIf { it != ev.rpcId },
        )
    }
}

/** 服务端权威结果交付通知（实时）：去重消费 + 通知 + 回 confirm_delivery（服务端删台账）。 */
internal fun BridgeClient.handleDeliveryNotice(ev: ServerEvent.DeliveryNotice) {
    val d = ev.notice
    if (notifications.onDeliveryNotice(d)) {
        confirmDeliveries(listOf(d))
    }
}

/** 批量确认已消费的交付通知（幂等删除；抑制通知也要回确认，见 NotificationController.onDeliveryNotice）。 */
internal fun BridgeClient.confirmDeliveries(notices: List<com.daniel.dshremote.protocol.DeliveryNoticeWire>) {
    val items = notices.map { com.daniel.dshremote.protocol.DeliveryConfirmItemWire(it.sessionId, it.turnKey) }
    scope.launch {
        if (!connection.send(ClientCommand.ConfirmDelivery(items))) {
            ConnLog.warn("NOTIFY", "confirm_delivery 发送失败（连接已断开，服务端台账保留，重连补发兜底）")
        }
    }
}

internal fun BridgeClient.handleDeviceRegistered(ev: ServerEvent.DeviceRegistered) {
    val url = connection.currentUrl ?: return
    val ep = Pairing.endpointOf(url)
    val now = nowMillis()
    val existing = _session.value.connectedDevice
    val device = StoredDevice(
        deviceId = ev.deviceId,
        name = existing?.name?.takeIf { it.isNotBlank() } ?: ev.hostname.ifBlank { ep.host },
        host = ep.host,
        port = ep.port,
        token = ev.deviceToken,
        serverId = ev.serverId,
        hostname = ev.hostname,
        createdAt = existing?.createdAt ?: now,
        lastSeenAt = now,
        endpoints = mergeEndpoints(ep, ev.endpoints),
    )
    _session.update { it.copy(connectedDevice = device) }
    val key = deviceKey(device)
    // 注册成功拿到长期 token：此后断线都可自动重连（含扫码配对路径）
    reconnectProvider = {
        val stored = devices.state.value.devices.firstOrNull { deviceKey(it) == key }
        if (stored == null) null
        else {
            val eps = stored.endpoints.ifEmpty { listOf(StoredEndpoint(stored.host, stored.port)) }
            eps.map { Pairing.buildUrl(it.host, it.port) to stored.token }
        }
    }
    scope.launch { devices.upsert(device) }
}

internal fun BridgeClient.handleDeviceRevoked(ev: ServerEvent.DeviceRevoked) {
    scope.launch { devices.removeByDeviceId(ev.deviceId) }
}

internal fun BridgeClient.handleError(ev: ServerEvent.Error) {
    ConnLog.warn("EVENT", "服务端错误 ${ev.code}: ${ev.message}")
    // 队列操作失败：主动刷新 + 明确提示「正在处理」，绝不静默消失（见 QueueItemLogic.kt）。
    val banner = queueErrorBanner(ev.code)
    if (banner != null) {
        if (ev.code == "queue-item-not-found") {
            ConnLog.info("QUEUE", "插队/删除目标已不在队列，主动刷新队列")
            _session.value.currentSessionId?.let { sid ->
                scope.launch { if (!connection.send(ClientCommand.Subscribe(sid))) pushConnectionError("刷新队列失败（连接已断开）") }
            }
        }
        pushBusinessError(banner)
        return
    }
    pushBusinessError("${ev.code}: ${ev.message}")
    // 服务端拒绝消息且回带 msgId（如 not_running）：精确关联回 pending，消除静默失败
    ev.msgId?.let { pendingSender.rejectByMsgId(it) }
    // 审批裁决竞争失败（已被其他手机/桌面端处理）：本地同步清理
    if (ev.code == "not_found" && ev.message.startsWith("approval not found")) {
        val gone = Regex("approval not found: (\\S+)").find(ev.message)?.groupValues?.get(1)
        _session.update { s ->
            s.copy(
                approvals = s.approvals.filterNot { a -> a.approvalId == gone },
                decidingApprovalId = s.decidingApprovalId.takeIf { it != gone },
            )
        }
    }
    // 提问已被其他终端回答或回答被拒：本地同步清理
    if (ev.code == "not_found" && ev.message.startsWith("question ")) {
        val gone = Regex("question (?:not pending|answer rejected): (\\S+)").find(ev.message)?.groupValues?.get(1)
        _session.update { s ->
            s.copy(
                questions = s.questions.filterNot { q -> q.rpcId == gone },
                decidingQuestionRpcId = s.decidingQuestionRpcId.takeIf { it != gone },
            )
        }
    }
    // 鉴权类错误重连无意义（token 失效/被撤销），熔断重连循环
    if (ev.code.lowercase() in AUTH_FATAL_CODES) {
        reconnectProvider = null
    }
}

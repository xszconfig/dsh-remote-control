package com.daniel.dshremote

import com.daniel.dshremote.protocol.ClientCommand
import com.daniel.dshremote.protocol.LogEntryWire
import com.daniel.dshremote.protocol.ServerEvent
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun BridgeClient.handle(ev: ServerEvent) {
    when (ev) {
        is ServerEvent.Hello -> handleHello(ev)
        is ServerEvent.History -> handleHistory(ev)
        is ServerEvent.Event -> handleEvent(ev)
        is ServerEvent.SessionQueue -> handleSessionQueue(ev)
        is ServerEvent.ModelWaiting -> handleModelWaiting(ev)
        is ServerEvent.ModelWaitingDone -> handleModelWaitingDone(ev)
        is ServerEvent.DeepDivingTick -> handleDeepDivingTick(ev)
        is ServerEvent.TurnStatus -> handleTurnStatus(ev)
        is ServerEvent.ThinkDelta -> handleThinkDelta(ev)
        is ServerEvent.Diagnostics -> handleDiagnostics(ev)
        is ServerEvent.GoalUpdate -> handleGoalUpdate(ev)
        is ServerEvent.TodosUpdate -> handleTodosUpdate(ev)
        is ServerEvent.CommandsUpdate -> handleCommandsUpdate(ev)
        is ServerEvent.ModelsUpdate -> handleModelsUpdate(ev)
        is ServerEvent.ContextUsage -> handleContextUsage(ev)
        is ServerEvent.SkillsUpdate -> handleSkillsUpdate(ev)
        is ServerEvent.DebugState -> handleDebugState(ev)
        is ServerEvent.DebugOutput -> handleDebugOutput(ev)
        is ServerEvent.DebugVariables -> handleDebugVariables(ev)
        is ServerEvent.ServerBoot -> handleServerBoot(ev)
        is ServerEvent.LogsRequest -> handleLogsRequest(ev)
        is ServerEvent.AgentStatus -> handleAgentStatus(ev)
        is ServerEvent.SessionTitle -> handleSessionTitle(ev)
        is ServerEvent.SessionUpsert -> handleSessionUpsert(ev)
        is ServerEvent.ApprovalRequest -> handleApprovalRequest(ev)
        is ServerEvent.ApprovalResolved -> handleApprovalResolved(ev)
        is ServerEvent.ApprovalSettledLegacy -> handleApprovalSettledLegacy(ev)
        is ServerEvent.QuestionRequest -> handleQuestionRequest(ev)
        is ServerEvent.QuestionResolved -> handleQuestionResolved(ev)
        is ServerEvent.DeliveryNotice -> handleDeliveryNotice(ev)
        is ServerEvent.DeviceRegistered -> handleDeviceRegistered(ev)
        is ServerEvent.DeviceRevoked -> handleDeviceRevoked(ev)
        is ServerEvent.Ack -> pendingSender.handleAck(ev)
        is ServerEvent.Pong -> Unit // 判活 pong 由 ConnectionManager 内部消费，兜底忽略
        is ServerEvent.Error -> handleError(ev)
    }
}

/**
 * 把一次「会话详情投影」变换路由到正确目标：currentSessionId → 内联字段；
 * 根主会话（subagentReturnStack 栈底，子会话打开时）→ parentView；其余 → 丢弃。
 * 平板 route B「双 live」的事件路由核心（手机无子会话时栈恒空，行为不变）。
 */
internal fun BridgeClient.updateView(sessionId: String, transform: (SessionViewState) -> SessionViewState) {
    _session.update { s ->
        when (sessionId) {
            s.currentSessionId -> s.copyCurrentView(transform(s.currentView()))
            s.subagentReturnStack.firstOrNull() -> s.copy(parentView = transform(s.parentView))
            else -> s
        }
    }
}

internal fun BridgeClient.handleHello(ev: ServerEvent.Hello) {
    sawHelloThisConnection = true
    // hello = 连接已建立且完全同步的权威信号：重连成功后由这里清横幅，
    // 不依赖重连循环里「open 返回时 hello 是否恰好已到达」的竞态判断。
    val wasReconnecting = _notice.value is ConnectionNotice.Reconnecting
    // 错误对账：清掉「连接类」（可自动恢复）错误，业务错误保留（手动清除）
    val keptErrors = reconcileErrorsOnHello(_session.value.errors)
    _session.update { it.copy(errors = keptErrors) }
    // 槽收敛：重连/瞬断恢复后静默（不弹「已重新连接」）；若有业务错误则展示最新一条
    _notice.value = keptErrors.lastOrNull()?.let { ConnectionNotice.Error(it.message) }
        ?: ConnectionNotice.Hidden
    if (wasReconnecting) {
        _reconnectStatus.value = ""
        ConnLog.info("RECONNECT", "hello 到达，重连完成，横幅清除")
    }
    val allApprovals = (ev.pendingApprovals + ev.pendingRemoteApprovals)
        .distinctBy { it.approvalId }
    // 增量对账：服务端快照是唯一事实源，与当前列表 diff 出增/改/删
    val prev = _session.value
    val added = ev.sessions.count { n -> prev.sessions.none { it.id == n.id } }
    val updated = ev.sessions.count { n -> prev.sessions.any { it.id == n.id && it != n } }
    val removed = prev.sessions.count { o -> ev.sessions.none { it.id == o.id } }
    if (added + updated + removed > 0) {
        ConnLog.info("SYNC", "会话对账 新增=$added 更新=$updated 删除=$removed（共 ${ev.sessions.size} 条）")
    }
    ConnLog.info(
        "EVENT",
        "收到 hello: sessions=${ev.sessions.size} workspaces=${ev.workspaces.size} " +
            "pendingApprovals=${allApprovals.size} pendingQuestions=${ev.pendingQuestions.size}",
    )
    _session.update {
        it.copy(
            sessions = ev.sessions,
            agents = ev.agents,
            workspaces = ev.workspaces,
            // 服务端持有中的审批/提问是唯一事实源（重连/新连接后据此重建队列）
            approvals = allApprovals,
            decidingApprovalId = allApprovals
                .any { a -> a.approvalId == it.decidingApprovalId }
                .let { still -> if (still) it.decidingApprovalId else null },
            questions = ev.pendingQuestions,
            decidingQuestionRpcId = ev.pendingQuestions
                .any { q -> q.rpcId == it.decidingQuestionRpcId }
                .let { still -> if (still) it.decidingQuestionRpcId else null },
            selectedWorkspaceId = it.selectedWorkspaceId
                ?.takeIf { sel -> sel == UNGROUPED_KEY || ev.workspaces.any { w -> w.id == sel } },
            // 打开中的会话已被删除 → 关闭视图，避免停留幽灵会话
            currentSessionId = it.currentSessionId
                ?.takeIf { cid -> ev.sessions.any { s -> s.id == cid } },
            // 子代理返回链同样以服务端快照为准校验：栈中某层会话被删则其后代层一并失效
            subagentReturnStack = pruneSubagentReturn(
                it.subagentReturnStack,
                ev.sessions.map { s -> s.id }.toSet(),
            ),
            events = it.currentSessionId
                ?.takeIf { cid -> ev.sessions.none { s -> s.id == cid } }
                ?.let { emptyList() } ?: it.events,
        )
    }
    if (ev.serverId != null) registerIfNeeded(ev.serverId, ev.hostname)
    scheduleSessionCacheSave()
    // 重连/新连接后若停留在会话页：重新订阅以服务端历史为准（补齐断线期间事件）
    val sid = _session.value.currentSessionId
    if (sid != null) {
        scope.launch {
            if (!connection.send(ClientCommand.Subscribe(sid))) {
                pushConnectionError("订阅会话失败（连接已断开）")
            }
        }
        // 断线自动重放：重连成功后重放当前会话的 failed 消息（同 msgId 幂等，退避+上限）
        pendingSender.autoReplaySession(sid)
    }
    // 打开会话：通知点击直达优先（命中暂存目标则打开，未命中停留列表）；
    // 否则自动打开最近会话（hello 对账完成后、无打开会话且本连接内未手动关闭过时）。
    // 放在重订阅之后，避免重复订阅。
    if (pendingOpenSessionId != null) {
        maybeOpenPendingNotificationSession()
    } else {
        maybeAutoOpenRecentSession()
    }
    // 补发审批/提问通知：重连/新连接后按服务端快照重建待裁决队列时，对未通知过的项主动召回
    // （幂等：已通知过的不会重复；放在自动打开之后，让门控能正确识别「正在浏览该会话」）。
    allApprovals.forEach { a ->
        notifications.onApprovalArrived(a.approvalId, a.sessionId, a.toolName, a.reason, a.command)
    }
    ev.pendingQuestions.forEach { q ->
        notifications.onQuestionArrived(q.rpcId, q.sessionId, q.questions.size, q.questions.firstOrNull()?.question)
    }
    // 补发结果交付通知：hello.pendingDeliveries 逐条幂等消费（(sessionId,turnKey) 查持久化键），
    // 新增项批量 confirm_delivery（服务端删台账）；旧桥无该字段按空数组处理。
    val newDeliveries = ev.pendingDeliveries.filter { notifications.onDeliveryNotice(it) }
    if (newDeliveries.isNotEmpty()) {
        confirmDeliveries(newDeliveries)
    }
}

/** 连接建立后的自动打开：条件满足才打开，否则只记 INFO 埋点。 */
internal fun BridgeClient.maybeAutoOpenRecentSession() {
    val st = _session.value
    if (st.currentSessionId != null) {
        ConnLog.info("ACTION", "自动打开最近会话：跳过（已有会话 ${st.currentSessionId}）")
        return
    }
    if (userClosedSessionThisConnection) {
        ConnLog.info("ACTION", "自动打开最近会话：跳过（本连接内用户已手动关闭会话）")
        return
    }
    val candidate = pickRecentSession(st.sessions, st.selectedWorkspaceId)
    if (candidate == null) {
        ConnLog.info("ACTION", "自动打开最近会话：跳过（无候选）workspace=${st.selectedWorkspaceId}")
        return
    }
    ConnLog.info("ACTION", "自动打开最近会话 sessionId=${candidate.id} workspace=${st.selectedWorkspaceId}")
    openSession(candidate.id)
}

internal fun BridgeClient.handleHistory(ev: ServerEvent.History) {
    val wasLoadingOlder = _session.value.loadingOlder && ev.sessionId == _session.value.currentSessionId
    updateView(ev.sessionId) { v ->
        if (v.loadingOlder) {
            // 翻页响应：往前插入更早的一页（按 seq+type 去重——
            // think/正文同 seq，纯 seq 去重会丢行）
            val merged = (ev.events + v.events).distinctBy { "${it.seq}-${it.type}" }
            v.copy(
                events = merged,
                hasMore = ev.hasMore,
                historyTotal = ev.total,
                loadingOlder = false,
            )
        } else {
            // 订阅响应：该会话正在等模型 → 切进来立刻显示 Deep Diving（会话级，不串扰）。
            // 轮次起点优先用服务端 turnSince（中途切入也能显示标签），回退模型等待起点。
            v.copy(
                events = ev.events.bounded(),
                queueItems = userVisibleQueueItems(ev.queue),
                hasMore = ev.hasMore,
                historyTotal = ev.total,
                modelWaitingSince = ev.modelWaitingSince,
                divingTurnStart = ev.turnSince ?: ev.modelWaitingSince,
                goal = ev.goal,
                todos = ev.todos ?: emptyList(),
                commands = ev.commands,
            )
        }
    }
    if (ev.sessionId == _session.value.currentSessionId) {
        if (wasLoadingOlder) {
            val start = olderLoadStartAt.remove(ev.sessionId)
            val elapsed = start?.let { nowMillis() - it }
            ConnLog.info("CMD", "翻页返回 sessionId=${ev.sessionId} 新增=${ev.events.size} 条 hasMore=${ev.hasMore} 耗时=${elapsed ?: "?"}ms")
        } else {
            val start = sessionOpenStartAt.remove(ev.sessionId)
            val elapsed = start?.let { nowMillis() - it }
            ConnLog.info("CMD", "会话历史到达 sessionId=${ev.sessionId} 条数=${ev.events.size} hasMore=${ev.hasMore} 耗时=${elapsed ?: "?"}ms")
        }
        lastCacheSaveAt = 0
        scope.launch { eventCache.save(eventCacheKey(ev.sessionId), _session.value.events.takeLast(MAX_EVENTS)) }
        ConnLog.debug("CACHE", "会话 ${ev.sessionId} 历史窗口 ${_session.value.events.size} 条（排队 ${ev.queue.size}）")
    }
}

internal fun BridgeClient.handleEvent(ev: ServerEvent.Event) {
    // 服务端回显去重：真实用户消息（spliced 回显）到达 → 移除匹配的本地 pending，
    // 由回显作为权威气泡渲染（带服务端 seq/时间戳），避免同一消息显示两份。
    if (ev.event.type == "user_message" && !isInjectedUserMessage(ev.event.source)) {
        val matched = matchPendingEcho(
            _session.value.pendingMessages,
            ev.sessionId,
            ev.event.text ?: "",
            ev.event.timestamp,
        )
        if (matched != null) {
            ConnLog.info("ACTION", "回显去重匹配 msgId=$matched sessionId=${ev.sessionId}")
            _session.update { s -> s.copy(pendingMessages = removePending(s.pendingMessages, matched)) }
            // 回显已作为权威气泡上屏，同步清理该会话持久化记录（ack 已先行时此处多为 no-op）
            scope.launch {
                pendingStore.update(ev.sessionId) { list -> list.filterNot { it.msgId == matched } }
            }
        }
    }
    updateView(ev.sessionId) { v -> v.copy(events = (v.events + ev.event).bounded()) }
    if (ev.sessionId == _session.value.currentSessionId) scheduleCacheSave()
}

internal fun BridgeClient.handleSessionQueue(ev: ServerEvent.SessionQueue) {
    val visible = userVisibleQueueItems(ev.items)
    val queued = visible.count { it.placement == "queued" }
    // queuedCounts 是跨会话的全局计数（供列表行中断确认弹框），无论当前/后台都更新。
    _session.update { s ->
        s.copy(queuedCounts = if (queued == 0) s.queuedCounts - ev.sessionId else s.queuedCounts + (ev.sessionId to queued))
    }
    updateView(ev.sessionId) { v -> v.copy(queueItems = visible) }
}

internal fun BridgeClient.handleModelWaiting(ev: ServerEvent.ModelWaiting) {
    // Deep Diving 本轮计时：首轮模型请求记录本轮起点，后续请求沿用（不重置）
    updateView(ev.sessionId) { v ->
        v.copy(modelWaitingSince = ev.startedAt, divingTurnStart = v.divingTurnStart ?: ev.startedAt)
    }
}

internal fun BridgeClient.handleModelWaitingDone(ev: ServerEvent.ModelWaitingDone) {
    // 只清「等待模型」指示；Deep Diving 时钟是轮次级状态（锚定轮次起点），
    // 一轮中可能有多次模型调用，每次完成都会广播一次 model_waiting_done——
    // 若在这里清 deepDivingElapsed，时钟会在下一个 tick（≤1s）前短暂消失，
    // 正是用户看到的「计时器闪烁」。轮次级时钟只在 turn_status(closed) 清除。
    updateView(ev.sessionId) { v ->
        if (v.modelWaitingSince == ev.startedAt) v.copy(modelWaitingSince = null) else v
    }
}

internal fun BridgeClient.handleDeepDivingTick(ev: ServerEvent.DeepDivingTick) {
    // 会话隔离：等待时长只归属对应会话（服务端时钟秒数，本地不再计时）
    updateView(ev.sessionId) { v -> v.copy(deepDivingElapsed = ev.elapsedSeconds) }
}

internal fun BridgeClient.handleTurnStatus(ev: ServerEvent.TurnStatus) {
    updateView(ev.sessionId) { v ->
        // 与 DSH Web 对齐：整个轮次期间显示 Deep diving 标签（不只等模型时）；
        // 轮次结束清掉标签与计时。服务端为轮次生命周期的唯一权威。
        // 结果交付通知不再由此客户端推导（服务端 delivery_notice 权威，见 handleDeliveryNotice）。
        if (ev.open) v.copy(divingTurnStart = ev.since, deepDivingElapsed = 0)
        else v.copy(divingTurnStart = null, deepDivingElapsed = null)
    }
}

internal fun BridgeClient.handleThinkDelta(ev: ServerEvent.ThinkDelta) {
    updateView(ev.sessionId) { v -> v.copy(liveThink = ev.text.takeIf { it.isNotEmpty() }) }
}

internal fun BridgeClient.handleDiagnostics(ev: ServerEvent.Diagnostics) {
    updateView(ev.sessionId) { v ->
        // 会话隔离：诊断只归属触发它的会话；文件级替换（空集合 = 该文件已无问题）
        val rest = v.diagnostics.filterNot { it.path == ev.path }
        v.copy(diagnostics = (rest + ev.diagnostics).takeLast(100))
    }
}

internal fun BridgeClient.handleGoalUpdate(ev: ServerEvent.GoalUpdate) {
    // 会话隔离：目标变更只归属对应会话（goal=null 表示已清除 → 隐藏面板）
    updateView(ev.sessionId) { v -> v.copy(goal = ev.goal) }
    // 结果交付 D3：goal 终态（complete / blocked）主动通知（幂等键 = goal.updatedAt）
    val goal = ev.goal
    if (goal != null && (goal.phase == "complete" || goal.phase == "blocked")) {
        notifyGoalDelivery(ev.sessionId, goal.updatedAt, blocked = goal.phase == "blocked", blockedMessage = goal.blockedMessage)
    }
}

internal fun BridgeClient.handleTodosUpdate(ev: ServerEvent.TodosUpdate) {
    // 会话隔离：任务列表只归属对应会话（每会话一份）
    updateView(ev.sessionId) { v -> v.copy(todos = ev.todos) }
}

internal fun BridgeClient.handleCommandsUpdate(ev: ServerEvent.CommandsUpdate) {
    // 会话隔离：斜杠命令清单只归属对应会话（候选弹窗数据源，服务端权威）
    updateView(ev.sessionId) { v -> v.copy(commands = ev.commands) }
}

internal fun BridgeClient.handleModelsUpdate(ev: ServerEvent.ModelsUpdate) {
    // 会话隔离：模型目录快照只归属对应会话（服务端投影权威，客户端只渲染、不推算）。
    // current 可能为 null（未加载/占位），routable null = 未加载（不置灰输入框）。
    if (_session.value.currentSessionId == ev.sessionId) {
        _session.update { it.copy(models = ev.models) }
        ConnLog.info(
            "MODEL",
            "模型目录快照 sessionId=${ev.sessionId} current=${ev.models.current?.provider}/${ev.models.current?.model}" +
                " routable=${ev.models.routable} groups=${ev.models.groups.size} failures=${ev.models.failures.size}",
        )
    } else {
        ConnLog.debug("MODEL", "模型目录快照非当前会话，丢弃 sessionId=${ev.sessionId}")
    }
}

internal fun BridgeClient.handleContextUsage(ev: ServerEvent.ContextUsage) {
    // 会话隔离：上下文占用只归属对应会话；percent 由服务端算好（clamp 0-100），客户端零推算。
    if (_session.value.currentSessionId == ev.sessionId) {
        _session.update { it.copy(contextUsage = ev.usage) }
        ConnLog.info(
            "CTX",
            "上下文占用 sessionId=${ev.sessionId} percent=${ev.usage.percent}" +
                " projected=${ev.usage.projectedTokens} window=${ev.usage.contextWindow}",
        )
    } else {
        ConnLog.debug("CTX", "上下文占用非当前会话，丢弃 sessionId=${ev.sessionId}")
    }
}

/** 技能目录（全局、无 sessionId）：bridge 连接即下发 + skills/change 时广播，直接覆盖全量目录。 */
internal fun BridgeClient.handleSkillsUpdate(ev: ServerEvent.SkillsUpdate) {
    _session.update { it.copy(skills = ev.skills) }
    ConnLog.info("SKILL", "技能目录 skills=${ev.skills.size}")
}

internal fun BridgeClient.handleDebugState(ev: ServerEvent.DebugState) {
    updateView(ev.sessionId) { v ->
        // 会话隔离；离开 paused 时清空变量缓存（objectId 已失效）
        v.copy(debug = ev.debug, debugVars = if (ev.debug.state == "paused") v.debugVars else emptyMap())
    }
}

internal fun BridgeClient.handleDebugOutput(ev: ServerEvent.DebugOutput) {
    updateView(ev.sessionId) { v -> v.copy(debugOutput = (v.debugOutput + ev.line).takeLast(200)) }
}

internal fun BridgeClient.handleDebugVariables(ev: ServerEvent.DebugVariables) {
    updateView(ev.sessionId) { v ->
        v.copy(debugVars = v.debugVars + (ev.variablesReference to ev.variables))
    }
}

internal fun BridgeClient.handleServerBoot(ev: ServerEvent.ServerBoot) {
    // 「一个版本提示一次」：本地已读版本与服务端版本一致 → 不弹（保持隐藏）；
    // 版本变化（服务端升级）→ 重新展示。按服务端标识 key 隔离，不同电脑互不吞提示。
    val device = _session.value.connectedDevice
    val key = device?.let { bootNoticeKey(it) } ?: "unknown"
    scope.launch {
        val seen = bootNoticeCache.load(key)
        _session.update { st ->
            if (seen == ev.version) st.copy(serverBoot = null) else st.copy(serverBoot = ev)
        }
    }
}

internal fun BridgeClient.handleLogsRequest(ev: ServerEvent.LogsRequest) {
    // 桌面端要手机端日志：回传本地 ConnLog 环形缓冲（最近 500 条）
    val entries = ConnLog.snapshot().takeLast(500).map {
        LogEntryWire(ts = it.ts, level = it.level.label, tag = it.tag, message = it.message)
    }
    ConnLog.info("LOG", "桌面端请求手机日志 → 回传 ${entries.size} 条 (request=${ev.requestId.take(8)})")
    scope.launch {
        if (!connection.send(ClientCommand.UploadLogs(ev.requestId, entries))) {
            ConnLog.warn("LOG", "手机日志回传失败（连接已断开）")
        }
    }
}

internal fun BridgeClient.handleAgentStatus(ev: ServerEvent.AgentStatus) {
    _session.update { s ->
        s.copy(
            sessions = s.sessions.map { if (it.id == ev.sessionId) it.copy(status = ev.status) else it },
            agents = s.agents.map { if (it.sessionId == ev.sessionId) it.copy(status = ev.status) else it },
            // 轮次生命周期由服务端 turn_status 事件管理（本地不再推断轮次边界）
        )
    }
    scheduleSessionCacheSave()
}

internal fun BridgeClient.handleSessionTitle(ev: ServerEvent.SessionTitle) {
    _session.update { s ->
        s.copy(
            sessions = s.sessions.map {
                if (it.id == ev.sessionId) it.copy(name = ev.title) else it
            },
        )
    }
    scheduleSessionCacheSave()
}

internal fun BridgeClient.handleSessionUpsert(ev: ServerEvent.SessionUpsert) {
    // 列表增量：同 id 替换行，再按 updatedAt 倒序归位
    _session.update { s ->
        val rows = (s.sessions.filterNot { it.id == ev.session.id } + ev.session)
            .sortedByDescending { it.updatedAt }
        s.copy(sessions = rows)
    }
    scheduleSessionCacheSave()
}

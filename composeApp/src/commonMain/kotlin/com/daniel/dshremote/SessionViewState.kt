package com.daniel.dshremote

import com.daniel.dshremote.protocol.CommandWire
import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.QueueItemWire
import com.daniel.dshremote.protocol.ServerEvent

/**
 * 单个会话的「会话详情」投影（events/队列/面板/输入上下文等）。
 *
 * 引入动机（平板三栏 route B「双 live」）：平板中栏主会话 + 右栏子会话需要**同时**渲染两个
 * live 会话详情，而旧的 [SessionUiState] 只有一套（currentSessionId 专属）字段。
 * 本类把「会话详情」投影收敛为可复用的数据块，[SessionUiState] 保留 currentSessionId 的
 * 字段（手机路径零改动）+ 新增 parentView（子会话打开时主会话的投影）。
 */
data class SessionViewState(
    val events: List<EventProjection> = emptyList(),
    val hasMore: Boolean = false,
    val historyTotal: Int = 0,
    val loadingOlder: Boolean = false,
    val queueItems: List<QueueItemWire> = emptyList(),
    val pendingMessages: List<PendingMessage> = emptyList(),
    val modelWaitingSince: Long? = null,
    val divingTurnStart: Long? = null,
    val deepDivingElapsed: Long? = null,
    val todos: List<ServerEvent.TodoWire> = emptyList(),
    val commands: List<CommandWire> = emptyList(),
    val liveThink: String? = null,
    val goal: ServerEvent.GoalWire? = null,
    val debug: ServerEvent.DebugStateWire? = null,
    val debugOutput: List<String> = emptyList(),
    val debugVars: Map<String, List<ServerEvent.DebugVariableWire>> = emptyMap(),
    val diagnostics: List<ServerEvent.DiagnosticWire> = emptyList(),
)

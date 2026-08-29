package com.daniel.dshremote

import com.daniel.dshremote.protocol.AgentSummary
import com.daniel.dshremote.protocol.ApprovalDecision
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.BridgeJson
import com.daniel.dshremote.protocol.ClientCommand
import com.daniel.dshremote.protocol.DeviceStatus
import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.QuestionAnswerItemWire
import com.daniel.dshremote.protocol.QuestionRequestWire
import com.daniel.dshremote.protocol.ServerEvent
import com.daniel.dshremote.protocol.ServerLogEntry
import com.daniel.dshremote.protocol.ServerLogsResponse
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.StoredDevice
import com.daniel.dshremote.protocol.StoredEndpoint
import com.daniel.dshremote.protocol.WorkspaceSummary
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 设备列表条目 key（host:port 唯一标识一台桌面）。 */
fun deviceKey(device: StoredDevice): String = "${device.host}:${device.port}"
fun deviceKey(host: String, port: Int): String = "$host:$port"

/** 合并候选端点：当前连接端点置顶，去重（主端点优先保证快速重连）。 */
internal fun mergeEndpoints(primary: Endpoint, server: List<StoredEndpoint>): List<StoredEndpoint> {
    val list = mutableListOf(StoredEndpoint(primary.host, primary.port))
    for (e in server) if (e.host != primary.host || e.port != primary.port) list.add(e)
    return list.distinctBy { "${it.host}:${it.port}" }
}

/** 侧边栏「未分组」桶的虚拟 id。 */
const val UNGROUPED_KEY = "__ungrouped__"

/** lastSeenAt 落盘节流间隔：探测循环 12s 一次，但只有超过该间隔才真正写文件。 */
const val LAST_SEEN_PERSIST_INTERVAL_MS: Long = 10 * 60_000

/** 错误提示保留上限（只保留最近 N 条，防止无界增长）。 */
const val MAX_ERRORS = 20

/** 单个会话在内存中保留的事件上限（长会话防 OOM；只保留最新）。 */
const val MAX_EVENTS = 500

/** 视为「凭据失效」的服务端错误码：停止自动重连。 */
val AUTH_FATAL_CODES = setOf("auth", "unauthorized", "forbidden", "token", "device_revoked")

/**
 * 汇总多候选连接失败的可读诊断：逐候选列出原因；候选里含 127.0.0.1
 * （bridge 仅监听本机、依赖 USB adb reverse 的场景）时追加操作指引。
 */
internal fun buildConnectFailureDetail(failures: List<Pair<String, String>>): String {
    val sb = StringBuilder("所有候选地址均连接失败")
    failures.take(3).forEach { (host, reason) ->
        sb.append("\n· ").append(host).append("：").append(reason.ifBlank { "连接失败" })
    }
    if (failures.size > 3) sb.append("\n· …等 ").append(failures.size).append(" 个候选")
    if (failures.any { it.first == "127.0.0.1" }) {
        sb.append("\n提示：桌面端仅监听 127.0.0.1；USB 连接请先在电脑上执行 adb reverse tcp:3080 tcp:3080")
    }
    return sb.toString()
}

/** 历史事件裁剪到上限（保留最新）。 */
internal fun List<EventProjection>.bounded(): List<EventProjection> =
    if (size <= MAX_EVENTS) this else takeLast(MAX_EVENTS)

/**
 * 会话面状态：连接着哪台设备、桌面端来的会话/工作区/事件/审批。
 * （设备列表与连接生命周期分别在 DevicesUiState / ConnectionInfo。）
 */
data class SessionUiState(
    val connectedDevice: StoredDevice? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val agents: List<AgentSummary> = emptyList(),
    val workspaces: List<WorkspaceSummary> = emptyList(),
    /** null = 全部；UNGROUPED_KEY = 未分组。 */
    val selectedWorkspaceId: String? = null,
    val currentSessionId: String? = null,
    val events: List<EventProjection> = emptyList(),
    /** 待处理审批队列（服务端持有 → 手机裁决；可能多单排队）。 */
    val approvals: List<ApprovalRequestWire> = emptyList(),
    /** 正在发送裁决的审批 id（按钮置灰、发送失败时据此清理）。 */
    val decidingApprovalId: String? = null,
    /** 待回答提问队列（桌面端持有、bridge 经 mux 转发）。 */
    val questions: List<QuestionRequestWire> = emptyList(),
    /** 正在提交答案的提问 rpcId。 */
    val decidingQuestionRpcId: String? = null,
    val errors: List<String> = emptyList(),
)

/**
 * 断开连接时的状态清理：清掉服务端来的易变数据，但保留用户偏好
 * （selectedWorkspaceId——重连后 Hello 会重新校验其有效性），
 * 断开/重连不再是「一切归零」。
 */
internal fun SessionUiState.clearedForDisconnect(): SessionUiState = copy(
    connectedDevice = null,
    sessions = emptyList(),
    agents = emptyList(),
    workspaces = emptyList(),
    currentSessionId = null,
    events = emptyList(),
    approvals = emptyList(),
    decidingApprovalId = null,
    questions = emptyList(),
    decidingQuestionRpcId = null,
)

/**
 * 手机端的总编排：连接策略（候选回退）、协议事件归约到 [SessionUiState]、
 * 把指令派发给 [ConnectionManager]、把设备变更派发给 [DeviceRepository]。
 * 单条连接的收发在 ConnectionManager，设备资产在 DeviceRepository。
 */
class BridgeClient(private val scope: CoroutineScope, store: DeviceStore) {

    val connection = ConnectionManager(scope)
    val devices = DeviceRepository(scope, store)

    private val _session = MutableStateFlow(SessionUiState())
    val session: StateFlow<SessionUiState> = _session.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** 正在自动重连（UI 保留会话数据 + 显示横幅）。 */
    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    /** 重连进度文案（"第 2 次 · 4s 后重试"）。 */
    private val _reconnectStatus = MutableStateFlow("")
    val reconnectStatus: StateFlow<String> = _reconnectStatus.asStateFlow()

    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var registeredThisConnection = false
    private var sawHelloThisConnection = false

    /**
     * 重连计划提供器：返回 (url, token) 或 null（无有效凭据，放弃重连）。
     * 每次重试时现取——设备 token 可能在上一段连接里被 bridge 轮换过。
     */
    /** 重连候选列表（主端点在前）；null = 无凭据。 */
    private var reconnectProvider: (() -> List<Pair<String, String?>>?)? = null
    private var userDisconnect = false

    init {
        scope.launch {
            connection.events.collect { ev -> handle(ev) }
        }
        // 连接建立/断开 → 同步会话状态、设备探测开关与自动重连
        scope.launch {
            var wasConnected = false
            connection.info.collect { info ->
                val connected = info.state == ConnectionState.Connected
                if (connected && !wasConnected) {
                    registeredThisConnection = false
                    sawHelloThisConnection = false
                    devices.setPollingEnabled(false)
                }
                if (!connected && wasConnected) {
                    onConnectionLost()
                }
                wasConnected = connected
            }
        }
    }

    /** 连接断开后的策略：用户主动断开→清理；有凭据→自动重连；否则→清理。 */
    private fun onConnectionLost() {
        if (userDisconnect) {
            ConnLog.info("RECONNECT", "用户主动断开，不自动重连")
            finishSession("已断开")
            return
        }
        if (reconnectProvider != null) {
            ConnLog.info("RECONNECT", "连接断开且有凭据 → 启动自动重连")
            startReconnect()
        } else {
            ConnLog.info("RECONNECT", "连接断开且无凭据 → 清理会话")
            finishSession(null)
        }
    }

    /** 结束会话状态：清理易变数据、恢复设备探测。detail 非空时给出错误态。 */
    private fun finishSession(detail: String?) {
        reconnectJob?.cancel()
        reconnectJob = null
        _reconnecting.value = false
        _reconnectStatus.value = ""
        _session.update { it.clearedForDisconnect() }
        devices.setPollingEnabled(true)
        if (detail != null) connection.fail(detail) else connection.markDisconnected()
    }

    /** 指数退避自动重连；凭据失效（provider 返回 null）或鉴权错误时放弃。 */
    private fun startReconnect() {
        // 幂等：断开边沿可能多次触发，已有活跃循环时不得重启——
        // 否则新旧循环会在 ConnectionManager 里并发 open()，互相踩 ws/currentUrl 状态
        if (reconnectJob?.isActive == true) return
        _reconnecting.value = true
        reconnectJob = scope.launch {
            try {
                var attempt = 1
                var consecutiveFailures = 0
                while (isActive) {
                    val plan = reconnectProvider?.invoke() ?: break
                    val wait = reconnectDelayMs(attempt)
                    // 连续失败 3 次以上且候选含 127.0.0.1：给出 USB 隧道断开指引
                    val hint = if (consecutiveFailures >= 3 && plan.any { it.first.contains("127.0.0.1") }) {
                        "（USB 隧道可能已断开：请插好数据线并执行 adb reverse tcp:3080 tcp:3080）"
                    } else {
                        ""
                    }
                    _reconnectStatus.value = "第 $attempt 次 · ${wait / 1000}s 后重试$hint"
                    connection.markReconnecting(_reconnectStatus.value)
                    ConnLog.info("RECONNECT", "第 $attempt 次重试将在 ${wait / 1000}s 后执行$hint")
                    delay(wait)
                    if (!isActive) return@launch
                    val candidates = reconnectProvider?.invoke() ?: break
                    var established = false
                    for ((index, candidate) in candidates.withIndex()) {
                        val (url, token) = candidate
                        ConnLog.info("RECONNECT", "第 $attempt 次重连 · 候选 ${index + 1}/${candidates.size}: $url")
                        if (connection.open(url, token)) {
                            established = true
                            break
                        }
                        ConnLog.warn("RECONNECT", "候选失败 $url: ${connection.info.value.detail}")
                    }
                    if (established) consecutiveFailures = 0 else consecutiveFailures++
                    // 成功建立过连接且期间收到过 Hello → 重置退避并清除重连横幅
                    if (established && sawHelloThisConnection) {
                        attempt = 1
                        sawHelloThisConnection = false
                        _reconnecting.value = false
                        _reconnectStatus.value = ""
                        ConnLog.info("RECONNECT", "重连成功，横幅清除")
                    } else {
                        attempt++
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // 用户断开或放弃：finishSession 已另行处理状态
            }
            // 放弃重连：凭据已失效（取消场景由 finishSession 处理）
            if (reconnectProvider == null) {
                finishSession("自动重连已停止：配对凭据已失效，请重新扫码或手动连接")
            }
        }
    }

    // ---- 连接 ----

    fun startScan() {
        _scanning.value = true
    }

    fun stopScan() {
        _scanning.value = false
    }

    /** 扫码结果：支持 bridge 的 JSON payload 或裸 ws:// 地址。 */
    fun onQrScanned(text: String) {
        _scanning.value = false
        // 配对 URL 里是一次性 pair token，断开后不能重放；DeviceRegistered
        // 事件到达后会换成可重连的设备凭据提供器
        reconnectProvider = null
        userDisconnect = false
        connectJob?.cancel()
        reconnectJob?.cancel()
        _reconnecting.value = false
        connectJob = scope.launch { connectFromQr(text) }
    }

    fun connectDevice(device: StoredDevice) {
        val key = deviceKey(device)
        reconnectProvider = {
            // 每次重试取最新存储的设备记录（token 可能已被轮换）；
            // 候选列表按存储顺序逐个快试（主端点在前，多路由回退）
            val stored = devices.state.value.devices.firstOrNull { deviceKey(it) == key }
            if (stored == null) null
            else {
                val eps = stored.endpoints.ifEmpty { listOf(StoredEndpoint(stored.host, stored.port)) }
                eps.map { Pairing.buildUrl(it.host, it.port) to stored.token }
            }
        }
        userDisconnect = false
        // 首次直连同样走多候选（主端点优先、4s 快速失败逐路回退）：
        // USB 隧道失效时自动落到局域网/Tailscale 端点，无需等重连循环
        connectJob?.cancel()
        connectJob = scope.launch {
            if (hintName(device)) _session.update { it.copy(connectedDevice = device) }
            val eps = device.endpoints.ifEmpty { listOf(StoredEndpoint(device.host, device.port)) }
            var ok = false
            for ((index, e) in eps.withIndex()) {
                ConnLog.info("CONNECT", "直连候选 ${index + 1}/${eps.size}: ${e.host}:${e.port}")
                if (connection.open(Pairing.buildUrl(e.host, e.port), device.token)) {
                    ok = true
                    break
                }
                ConnLog.warn("CONNECT", "直连候选失败 ${e.host}:${e.port}: ${connection.info.value.detail}")
            }
            if (!ok && eps.any { it.host == "127.0.0.1" }) {
                connection.fail(
                    connection.info.value.detail.ifBlank { "连接失败" } +
                        "\n提示：USB 连接请先在电脑上执行 adb reverse tcp:3080 tcp:3080",
                )
            }
        }
    }

    fun connectManual(host: String, port: Int, token: String?) {
        reconnectProvider = { listOf(Pairing.buildUrl(host, port) to token) }
        userDisconnect = false
        connect(host, port, token, null)
    }

    fun disconnect() {
        userDisconnect = true
        scope.launch {
            connectJob?.cancel()
            finishSession(null)
            connection.close()
        }
    }

    /** 直连时是否已把 hint 设备名挂上会话状态（Hello 注册时取名称用）。 */
    private fun hintName(device: StoredDevice): Boolean {
        _session.update { it.copy(connectedDevice = device) }
        return true
    }

    private fun connect(host: String, port: Int, token: String?, hint: StoredDevice?) {
        connectJob?.cancel()
        connectJob = scope.launch {
            // 先挂上 hint 设备名：Hello 到达时 registerIfNeeded 才能取到存储的名称
            if (hint != null) _session.update { it.copy(connectedDevice = hint) }
            val ok = connection.open(Pairing.buildUrl(host, port), token)
            if (!ok && host == "127.0.0.1") {
                // bridge 仅监听本机：USB 场景下 127.0.0.1 不通几乎一定是没设 reverse
                connection.fail(
                    connection.info.value.detail.ifBlank { "连接失败" } +
                        "\n提示：USB 连接请先在电脑上执行 adb reverse tcp:$port tcp:$port",
                )
            }
        }
    }

    private suspend fun connectFromQr(text: String) {
        val urls = Pairing.parseQr(text)
        if (urls.isEmpty()) {
            ConnLog.warn("QR", "无法识别的二维码内容: ${text.take(80)}")
            connection.fail("无法识别的二维码内容")
            return
        }
        ConnLog.info("QR", "扫码得到 ${urls.size} 个候选地址: ${urls.joinToString(" | ")}")
        // 逐候选尝试并记录各自失败原因（refused/timeout/401…），全部失败时给出完整诊断
        val failures = mutableListOf<Pair<String, String>>()
        for (url in urls) {
            ConnLog.info("QR", "尝试候选: $url")
            if (connection.open(url, null)) return
            val reason = connection.info.value.detail
            failures.add(Pairing.endpointOf(url).host to reason)
            ConnLog.warn("QR", "候选失败 ${Pairing.endpointOf(url).host}: $reason")
        }
        connection.fail(buildConnectFailureDetail(failures))
    }

    // ---- 会话操作 ----

    /** 侧边栏切换工作区视图；null = 全部会话。 */
    fun selectWorkspace(workspaceId: String?) {
        _session.update { it.copy(selectedWorkspaceId = workspaceId) }
    }

    fun openSession(sessionId: String) {
        if (sessionId.isBlank()) return
        _session.update { it.copy(currentSessionId = sessionId, events = emptyList()) }
        scope.launch {
            if (!connection.send(ClientCommand.Subscribe(sessionId))) pushError("订阅会话失败（连接已断开）")
        }
    }

    /**
     * 关闭当前会话视图，返回会话列表。纯本地导航：协议没有「取消订阅」命令
     * （bridge 对 Subscribe(null) 会回 not_found 错误），未订阅会话的事件
     * 在 handle() 里按 sessionId 过滤丢弃，无需通知服务端。
     */
    fun closeSession() {
        _session.update { it.copy(currentSessionId = null, events = emptyList()) }
    }

    fun sendMessage(text: String) {
        val sid = _session.value.currentSessionId ?: return
        scope.launch {
            if (!connection.send(ClientCommand.SendMessage(sid, text))) {
                pushError("「${text.take(20)}」未发送：连接已断开")
            }
        }
    }

    fun interrupt(sessionId: String) {
        scope.launch {
            if (!connection.send(ClientCommand.Interrupt(sessionId))) pushError("中断指令发送失败（连接已断开）")
        }
    }

    /** 裁决审批：bridge 持有走 approve；桌面端（mux）持有走 answer_approval。 */
    fun approve(approval: ApprovalRequestWire, decision: ApprovalDecision) {
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
                pushError("审批决策发送失败（连接已断开）")
            }
        }
    }

    /** 提交提问答案（桌面端持有的 ask_user_question）。 */
    fun answerQuestion(question: QuestionRequestWire, answers: List<QuestionAnswerItemWire>) {
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
                pushError("提问答案发送失败（连接已断开）")
            }
        }
    }

    /** 清空错误提示。 */
    fun dismissErrors() {
        _session.update { it.copy(errors = emptyList()) }
    }

    // ---- 诊断日志 ----

    private val logsClient: HttpClient = createPingHttp()

    /** 拉取服务端结构化连接日志（/remote/logs；需已连接）。 */
    suspend fun loadServerLogs(limit: Int = 300): List<ServerLogEntry>? {
        val device = _session.value.connectedDevice ?: return null
        return try {
            val resp = logsClient.get("http://${device.host}:${device.port}/remote/logs?limit=$limit")
            BridgeJson.decodeFromString(ServerLogsResponse.serializer(), resp.bodyAsText()).entries
        } catch (e: Exception) {
            ConnLog.warn("LOG", "拉取服务端日志失败: ${e.message}")
            null
        }
    }

    // ---- 设备管理 ----

    fun forgetDevice(device: StoredDevice) {
        val key = deviceKey(device)
        scope.launch {
            devices.remove(key)
            val connected = _session.value.connectedDevice
            if (connected != null && deviceKey(connected) == key) {
                if (!connection.send(ClientCommand.RevokeDevice(device.deviceId))) {
                    pushError("撤销桌面端凭据失败（连接已断开）")
                }
            }
        }
    }

    // ---- 内部 ----

    private fun pushError(message: String) {
        _session.update { it.copy(errors = (it.errors + message).takeLast(MAX_ERRORS)) }
    }

    private fun registerIfNeeded(serverId: String, hostname: String?) {
        if (registeredThisConnection) return
        registeredThisConnection = true
        val phoneId = devices.ensurePhoneId()
        val host = connection.currentUrl?.let { Pairing.endpointOf(it).host } ?: return
        val hint = _session.value.connectedDevice
        val name = hint?.name?.takeIf { it.isNotBlank() }
            ?: hostname?.takeIf { it.isNotBlank() }
            ?: host
        scope.launch {
            if (!connection.send(
                    ClientCommand.RegisterDevice(
                        deviceId = phoneId,
                        name = name,
                        model = platformDeviceModel(),
                    ),
                )
            ) {
                pushError("设备注册失败（连接已断开）")
            }
        }
    }

    private fun handle(ev: ServerEvent) {
        when (ev) {
            is ServerEvent.Hello -> {
                sawHelloThisConnection = true
                val allApprovals = (ev.pendingApprovals + ev.pendingRemoteApprovals)
                    .distinctBy { it.approvalId }
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
                    )
                }
                if (ev.serverId != null) registerIfNeeded(ev.serverId, ev.hostname)
                // 重连/新连接后若停留在会话页：重新订阅以服务端历史为准（补齐断线期间事件）
                val sid = _session.value.currentSessionId
                if (sid != null) {
                    scope.launch {
                        if (!connection.send(ClientCommand.Subscribe(sid))) {
                            pushError("订阅会话失败（连接已断开）")
                        }
                    }
                }
            }
            is ServerEvent.History -> _session.update { it.copy(events = ev.events.bounded()) }
            is ServerEvent.Event -> _session.update { s ->
                if (ev.sessionId == s.currentSessionId) s.copy(events = (s.events + ev.event).bounded()) else s
            }
            is ServerEvent.AgentStatus -> _session.update { s ->
                s.copy(
                    sessions = s.sessions.map { if (it.id == ev.sessionId) it.copy(status = ev.status) else it },
                    agents = s.agents.map { if (it.sessionId == ev.sessionId) it.copy(status = ev.status) else it },
                )
            }
            is ServerEvent.SessionTitle -> _session.update { s ->
                s.copy(
                    sessions = s.sessions.map {
                        if (it.id == ev.sessionId) it.copy(name = ev.title) else it
                    },
                )
            }
            is ServerEvent.ApprovalRequest -> _session.update { s ->
                val known = s.approvals.any { it.approvalId == ev.approval.approvalId }
                ConnLog.info(
                    "APPROVAL",
                    "收到审批 approval=${ev.approval.approvalId.take(8)} tool=${ev.approval.toolName} " +
                        "rpc=${ev.approval.rpcId?.take(8) ?: "bridge-held"}${if (known) "（重复，忽略）" else ""}",
                )
                if (!known) platformVibrateApproval()
                s.copy(approvals = if (known) s.approvals else s.approvals + ev.approval)
            }
            is ServerEvent.ApprovalResolved -> _session.update { s ->
                ConnLog.info("APPROVAL", "审批已解决 approval=${ev.approvalId.take(8)} outcome=${ev.outcome}")
                s.copy(
                    approvals = s.approvals.filterNot { a -> a.approvalId == ev.approvalId },
                    decidingApprovalId = s.decidingApprovalId.takeIf { it != ev.approvalId },
                )
            }
            is ServerEvent.ApprovalSettledLegacy -> _session.update { s ->
                ConnLog.info("APPROVAL", "审批已解决（旧版事件）approval=${ev.approvalId.take(8)}")
                s.copy(
                    approvals = s.approvals.filterNot { a -> a.approvalId == ev.approvalId },
                    decidingApprovalId = s.decidingApprovalId.takeIf { it != ev.approvalId },
                )
            }
            is ServerEvent.QuestionRequest -> _session.update { s ->
                val known = s.questions.any { it.rpcId == ev.question.rpcId }
                ConnLog.info(
                    "QUESTION",
                    "收到提问 rpc=${ev.question.rpcId.take(8)} questions=${ev.question.questions.size}${if (known) "（重复，忽略）" else ""}",
                )
                if (!known) platformVibrateApproval()
                s.copy(questions = if (known) s.questions else s.questions + ev.question)
            }
            is ServerEvent.QuestionResolved -> _session.update { s ->
                ConnLog.info("QUESTION", "提问已解决 rpc=${ev.rpcId.take(8)} outcome=${ev.outcome}")
                s.copy(
                    questions = s.questions.filterNot { q -> q.rpcId == ev.rpcId },
                    decidingQuestionRpcId = s.decidingQuestionRpcId.takeIf { it != ev.rpcId },
                )
            }
            is ServerEvent.DeviceRegistered -> {
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
            is ServerEvent.DeviceRevoked -> {
                scope.launch { devices.removeByDeviceId(ev.deviceId) }
            }
            is ServerEvent.Error -> {
                ConnLog.warn("EVENT", "服务端错误 ${ev.code}: ${ev.message}")
                pushError("${ev.code}: ${ev.message}")
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
        }
    }
}

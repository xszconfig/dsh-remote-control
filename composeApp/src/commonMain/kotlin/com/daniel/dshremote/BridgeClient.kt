package com.daniel.dshremote

import com.daniel.dshremote.protocol.AgentSummary
import com.daniel.dshremote.protocol.ApprovalDecision
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.ClientCommand
import com.daniel.dshremote.protocol.DeviceStatus
import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.ServerEvent
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.StoredDevice
import com.daniel.dshremote.protocol.WorkspaceSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 设备列表条目 key（host:port 唯一标识一台桌面）。 */
fun deviceKey(device: StoredDevice): String = "${device.host}:${device.port}"
fun deviceKey(host: String, port: Int): String = "$host:$port"

/** 侧边栏「未分组」桶的虚拟 id。 */
const val UNGROUPED_KEY = "__ungrouped__"

/** lastSeenAt 落盘节流间隔：探测循环 12s 一次，但只有超过该间隔才真正写文件。 */
const val LAST_SEEN_PERSIST_INTERVAL_MS: Long = 10 * 60_000

/** 错误提示保留上限（只保留最近 N 条，防止无界增长）。 */
const val MAX_ERRORS = 20

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
    val approvals: List<ApprovalRequestWire> = emptyList(),
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

    private var connectJob: Job? = null
    private var registeredThisConnection = false

    init {
        scope.launch {
            connection.events.collect { ev -> handle(ev) }
        }
        // 连接建立/断开 → 同步会话状态与设备探测开关
        scope.launch {
            var wasConnected = false
            connection.info.collect { info ->
                val connected = info.state == ConnectionState.Connected
                if (connected && !wasConnected) {
                    registeredThisConnection = false
                    devices.setPollingEnabled(false)
                }
                if (!connected && wasConnected) {
                    _session.update { it.clearedForDisconnect() }
                    devices.setPollingEnabled(true)
                }
                wasConnected = connected
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
        connectJob?.cancel()
        connectJob = scope.launch { connectFromQr(text) }
    }

    fun connectDevice(device: StoredDevice) {
        connect(device.host, device.port, device.token, device)
    }

    fun connectManual(host: String, port: Int, token: String?) {
        connect(host, port, token, null)
    }

    fun disconnect() {
        scope.launch {
            connectJob?.cancel()
            connection.close()
        }
    }

    private fun connect(host: String, port: Int, token: String?, hint: StoredDevice?) {
        connectJob?.cancel()
        connectJob = scope.launch {
            // 先挂上 hint 设备名：Hello 到达时 registerIfNeeded 才能取到存储的名称
            if (hint != null) _session.update { it.copy(connectedDevice = hint) }
            connection.open(Pairing.buildUrl(host, port), token)
        }
    }

    private suspend fun connectFromQr(text: String) {
        val urls = Pairing.parseQr(text)
        if (urls.isEmpty()) {
            connection.fail("无法识别的二维码内容")
            return
        }
        for (url in urls) {
            if (connection.open(url, null)) return
        }
        connection.fail("所有候选地址均连接失败")
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

    fun approve(approvalId: String, decision: ApprovalDecision) {
        scope.launch {
            if (!connection.send(ClientCommand.Approve(approvalId, decision))) pushError("审批决策发送失败（连接已断开）")
        }
    }

    /** 清空错误提示。 */
    fun dismissErrors() {
        _session.update { it.copy(errors = emptyList()) }
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
                _session.update {
                    it.copy(
                        sessions = ev.sessions,
                        agents = ev.agents,
                        workspaces = ev.workspaces,
                        selectedWorkspaceId = it.selectedWorkspaceId
                            ?.takeIf { sel -> sel == UNGROUPED_KEY || ev.workspaces.any { w -> w.id == sel } },
                    )
                }
                if (ev.serverId != null) registerIfNeeded(ev.serverId, ev.hostname)
            }
            is ServerEvent.History -> _session.update { it.copy(events = ev.events) }
            is ServerEvent.Event -> _session.update { s ->
                if (ev.sessionId == s.currentSessionId) s.copy(events = s.events + ev.event) else s
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
            is ServerEvent.ApprovalRequest -> _session.update {
                it.copy(approvals = it.approvals + ev.approval)
            }
            is ServerEvent.ApprovalSettled -> _session.update {
                it.copy(approvals = it.approvals.filterNot { a -> a.approvalId == ev.approvalId })
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
                )
                _session.update { it.copy(connectedDevice = device) }
                scope.launch { devices.upsert(device) }
            }
            is ServerEvent.DeviceRevoked -> {
                scope.launch { devices.removeByDeviceId(ev.deviceId) }
            }
            is ServerEvent.Error -> pushError("${ev.code}: ${ev.message}")
        }
    }
}

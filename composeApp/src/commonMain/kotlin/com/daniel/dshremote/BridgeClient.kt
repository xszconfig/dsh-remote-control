package com.daniel.dshremote

import com.daniel.dshremote.protocol.AgentSummary
import com.daniel.dshremote.protocol.ApprovalDecision
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.BridgeJson
import com.daniel.dshremote.protocol.ClientCommand
import com.daniel.dshremote.protocol.DeviceStatus
import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.PingInfo
import com.daniel.dshremote.protocol.ServerEvent
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.StoredDevice
import com.daniel.dshremote.protocol.WorkspaceSummary
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class ConnectionState { Disconnected, Connecting, Connected, Error }

data class BridgeUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val connectionDetail: String = "",
    val scanning: Boolean = false,
    val devices: List<StoredDevice> = emptyList(),
    val deviceStatuses: Map<String, DeviceStatus> = emptyMap(),
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
 * 断开连接时的状态清理：清掉服务端来的易变数据，但保留用户偏好
 * （selectedWorkspaceId——重连后 Hello 会重新校验其有效性），
 * 断开/重连不再是「一切归零」。
 */
internal fun BridgeUiState.clearedForDisconnect(): BridgeUiState = copy(
    connectedDevice = null,
    sessions = emptyList(),
    agents = emptyList(),
    workspaces = emptyList(),
    currentSessionId = null,
    events = emptyList(),
    approvals = emptyList(),
)

/**
 * 与桌面端 bridge 的 WebSocket 客户端。手机是控制面：只收状态、发指令、做审批。
 * 所有状态经 [state] 单向流暴露给 UI。
 */
class BridgeClient(private val scope: CoroutineScope, private val store: DeviceStore) {

    private val wsClient = createWsHttp()
    private val pingClient = createPingHttp()
    private var ws: DefaultClientWebSocketSession? = null

    private val _state = MutableStateFlow(BridgeUiState())
    val state: StateFlow<BridgeUiState> = _state.asStateFlow()

    private var phoneId: String = ""
    private var registeredThisConnection = false
    private var currentUrl: String? = null
    private var connectJob: Job? = null

    init {
        scope.launch {
            val file = store.load()
            phoneId = file.phoneId
            _state.update { it.copy(devices = file.devices) }
            refreshStatuses()
            while (isActive) {
                delay(12_000)
                if (_state.value.connection == ConnectionState.Disconnected) refreshStatuses()
            }
        }
    }

    // ---- 连接 ----

    fun startScan() {
        _state.update { it.copy(scanning = true) }
    }

    fun stopScan() {
        _state.update { it.copy(scanning = false) }
    }

    /** 扫码结果：支持 bridge 的 JSON payload 或裸 ws:// 地址。 */
    fun onQrScanned(text: String) {
        _state.update { it.copy(scanning = false) }
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
            ws?.close()
        }
    }

    private fun connect(host: String, port: Int, token: String?, hint: StoredDevice?) {
        connectJob?.cancel()
        connectJob = scope.launch {
            attemptConnect(buildUrl(host, port, token), hint)
        }
    }

    private suspend fun connectFromQr(text: String) {
        val urls = Pairing.parseQr(text)
        if (urls.isEmpty()) {
            _state.update {
                it.copy(
                    connection = ConnectionState.Error,
                    connectionDetail = "无法识别的二维码内容",
                )
            }
            return
        }
        for (url in urls) {
            _state.update {
                it.copy(connection = ConnectionState.Connecting, connectionDetail = url.removePrefix("ws://"))
            }
            if (attemptConnect(url, null)) return
        }
        _state.update {
            it.copy(connection = ConnectionState.Error, connectionDetail = "所有候选地址均连接失败")
        }
    }

    /**
     * 建立一条 WS 连接并处理事件循环，连接断开后返回。
     * @return true 表示握手成功过（连接建立后又被关闭）；false 表示握手失败。
     */
    private suspend fun attemptConnect(url: String, hint: StoredDevice?): Boolean {
        registeredThisConnection = false
        currentUrl = url
        var established = false
        try {
            wsClient.webSocket(urlString = url) {
                established = true
                ws = this
                _state.update {
                    it.copy(connection = ConnectionState.Connected, connectionDetail = "", errors = emptyList())
                }
                send(ClientCommand.List)
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val ev = BridgeJson.decodeFromString(ServerEvent.serializer(), frame.readText())
                        handle(ev, hint)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!established) {
                _state.update {
                    it.copy(connection = ConnectionState.Error, connectionDetail = e.message ?: "connection failed")
                }
            }
        } finally {
            ws = null
            currentUrl = null
            if (established) {
                _state.update {
                    it.clearedForDisconnect().copy(
                        connection = ConnectionState.Disconnected,
                        connectionDetail = "",
                    )
                }
            }
        }
        return established
    }

    private fun buildUrl(host: String, port: Int, token: String?): String =
        Pairing.buildUrl(host, port, token)

    // ---- 会话操作 ----

    /** 侧边栏切换工作区视图；null = 全部会话。 */
    fun selectWorkspace(workspaceId: String?) {
        _state.update { it.copy(selectedWorkspaceId = workspaceId) }
    }

    fun openSession(sessionId: String) {
        if (sessionId.isBlank()) return
        _state.update { it.copy(currentSessionId = sessionId, events = emptyList()) }
        scope.launch {
            if (!send(ClientCommand.Subscribe(sessionId))) pushError("订阅会话失败（连接已断开）")
        }
    }

    /**
     * 关闭当前会话视图，返回会话列表。纯本地导航：协议没有「取消订阅」命令
     * （bridge 对 Subscribe(null) 会回 not_found 错误），未订阅会话的事件
     * 在 handle() 里按 sessionId 过滤丢弃，无需通知服务端。
     */
    fun closeSession() {
        _state.update { it.copy(currentSessionId = null, events = emptyList()) }
    }

    fun sendMessage(text: String) {
        val sid = _state.value.currentSessionId ?: return
        scope.launch {
            if (!send(ClientCommand.SendMessage(sid, text))) pushError("「${text.take(20)}」未发送：连接已断开")
        }
    }

    fun interrupt(sessionId: String) {
        scope.launch {
            if (!send(ClientCommand.Interrupt(sessionId))) pushError("中断指令发送失败（连接已断开）")
        }
    }

    fun approve(approvalId: String, decision: ApprovalDecision) {
        scope.launch {
            if (!send(ClientCommand.Approve(approvalId, decision))) pushError("审批决策发送失败（连接已断开）")
        }
    }

    /** 清空错误提示。 */
    fun dismissErrors() {
        _state.update { it.copy(errors = emptyList()) }
    }

    // ---- 设备管理 ----

    fun forgetDevice(device: StoredDevice) {
        val key = deviceKey(device)
        _state.update {
            it.copy(
                devices = it.devices.filterNot { deviceKey(it) == key },
                deviceStatuses = it.deviceStatuses - key,
            )
        }
        scope.launch {
            store.update { f -> f.copy(devices = f.devices.filterNot { deviceKey(it) == key }) }
            val connected = _state.value.connectedDevice
            if (connected != null && deviceKey(connected) == key) {
                if (!send(ClientCommand.RevokeDevice(device.deviceId))) {
                    pushError("撤销桌面端凭据失败（连接已断开）")
                }
            }
        }
    }

    suspend fun refreshStatuses() {
        val devices = _state.value.devices
        if (devices.isEmpty()) return
        _state.update { s ->
            s.copy(
                deviceStatuses = s.deviceStatuses +
                    devices.associate { deviceKey(it) to DeviceStatus.Checking },
            )
        }
        val outcomes = coroutineScope {
            devices.map { device -> async { ping(device) } }.awaitAll()
        }
        val statuses = outcomes.associate { it.key to it.status }
        val outcomeByKey = outcomes.associateBy { it.key }

        // 落盘节流：只有 serverId/hostname 补齐、或 lastSeenAt 超过节流间隔才改设备记录，
        // 数据没有实际变化时整轮不写文件（此前每 12s 必写一次）。
        val now = nowMillis()
        val updated = devices.map { d ->
            val o = outcomeByKey[deviceKey(d)] ?: return@map d
            if (o.status != DeviceStatus.Online && o.status != DeviceStatus.Changed) return@map d
            val bumpSeen = d.lastSeenAt < now - LAST_SEEN_PERSIST_INTERVAL_MS
            val fillServerId = d.serverId == null && o.serverId != null
            val fillHostname = d.hostname == null && o.hostname != null
            if (bumpSeen || fillServerId || fillHostname) {
                d.copy(
                    lastSeenAt = if (bumpSeen) now else d.lastSeenAt,
                    serverId = d.serverId ?: o.serverId,
                    hostname = d.hostname ?: o.hostname,
                )
            } else {
                d
            }
        }
        _state.update {
            it.copy(deviceStatuses = it.deviceStatuses + statuses, devices = updated)
        }
        if (updated != devices) {
            store.update { f -> f.copy(devices = updated) }
        }
    }

    private data class PingOutcome(
        val key: String,
        val status: DeviceStatus,
        val serverId: String?,
        val hostname: String?,
    )

    private suspend fun ping(device: StoredDevice): PingOutcome = try {
        val resp = pingClient.get("http://${device.host}:${device.port}/remote/ping")
        val info = BridgeJson.decodeFromString(PingInfo.serializer(), resp.bodyAsText())
        if (info.ok) {
            val changed = device.serverId != null && info.serverId != null &&
                device.serverId != info.serverId
            PingOutcome(deviceKey(device), if (changed) DeviceStatus.Changed else DeviceStatus.Online, info.serverId, info.hostname)
        } else {
            PingOutcome(deviceKey(device), DeviceStatus.Offline, null, null)
        }
    } catch (_: Exception) {
        PingOutcome(deviceKey(device), DeviceStatus.Offline, null, null)
    }

    // ---- 内部 ----

    private suspend fun send(cmd: ClientCommand): Boolean {
        val session = ws ?: return false
        return try {
            session.send(Frame.Text(BridgeJson.encodeToString(ClientCommand.serializer(), cmd)))
            true
        } catch (_: Exception) {
            // 通道已关闭等发送异常：上报给调用方，不再静默丢弃
            false
        }
    }

    /** 追加一条用户可见错误（封顶 MAX_ERRORS，只留最近）。 */
    private fun pushError(message: String) {
        _state.update { it.copy(errors = (it.errors + message).takeLast(MAX_ERRORS)) }
    }

    private fun handle(ev: ServerEvent, hint: StoredDevice?) {
        when (ev) {
            is ServerEvent.Hello -> {
                _state.update {
                    it.copy(
                        sessions = ev.sessions,
                        agents = ev.agents,
                        workspaces = ev.workspaces,
                        selectedWorkspaceId = it.selectedWorkspaceId
                            ?.takeIf { sel -> sel == UNGROUPED_KEY || ev.workspaces.any { w -> w.id == sel } },
                    )
                }
                if (ev.serverId != null) registerIfNeeded(ev.serverId, ev.hostname, hint)
            }
            is ServerEvent.History -> _state.update {
                it.copy(events = ev.events)
            }
            is ServerEvent.Event -> _state.update { s ->
                if (ev.sessionId == s.currentSessionId) s.copy(events = s.events + ev.event) else s
            }
            is ServerEvent.AgentStatus -> _state.update { s ->
                s.copy(
                    sessions = s.sessions.map { if (it.id == ev.sessionId) it.copy(status = ev.status) else it },
                    agents = s.agents.map { if (it.sessionId == ev.sessionId) it.copy(status = ev.status) else it },
                )
            }
            is ServerEvent.SessionTitle -> _state.update { s ->
                s.copy(
                    sessions = s.sessions.map {
                        if (it.id == ev.sessionId) it.copy(name = ev.title) else it
                    },
                )
            }
            is ServerEvent.ApprovalRequest -> _state.update {
                it.copy(approvals = it.approvals + ev.approval)
            }
            is ServerEvent.ApprovalSettled -> _state.update {
                it.copy(approvals = it.approvals.filterNot { a -> a.approvalId == ev.approvalId })
            }
            is ServerEvent.DeviceRegistered -> {
                val ep = Pairing.endpointOf(currentUrl ?: return)
                val host = ep.host
                val port = ep.port
                val now = nowMillis()
                val existing = _state.value.devices.firstOrNull { deviceKey(it) == deviceKey(host, port) }
                val device = StoredDevice(
                    deviceId = ev.deviceId,
                    name = existing?.name ?: ev.hostname.ifBlank { host },
                    host = host,
                    port = port,
                    token = ev.deviceToken,
                    serverId = ev.serverId,
                    hostname = ev.hostname,
                    createdAt = existing?.createdAt ?: now,
                    lastSeenAt = now,
                )
                val key = deviceKey(host, port)
                _state.update { s ->
                    s.copy(
                        devices = s.devices.filterNot { deviceKey(it) == key } + device,
                        connectedDevice = device,
                    )
                }
                scope.launch {
                    store.update { f -> f.copy(devices = f.devices.filterNot { deviceKey(it) == key } + device) }
                }
            }
            is ServerEvent.DeviceRevoked -> {
                _state.update { it.copy(devices = it.devices.filterNot { d -> d.deviceId == ev.deviceId }) }
                scope.launch {
                    store.update { f -> f.copy(devices = f.devices.filterNot { d -> d.deviceId == ev.deviceId }) }
                }
            }
            is ServerEvent.Error -> pushError("${ev.code}: ${ev.message}")        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun registerIfNeeded(serverId: String, hostname: String?, hint: StoredDevice?) {
        if (registeredThisConnection) return
        registeredThisConnection = true
        if (phoneId.isBlank()) phoneId = Uuid.random().toString()
        val host = Pairing.endpointOf(currentUrl ?: return).host
        val name = hint?.name?.takeIf { it.isNotBlank() }
            ?: hostname?.takeIf { it.isNotBlank() }
            ?: host
        scope.launch {
            if (!send(ClientCommand.RegisterDevice(deviceId = phoneId, name = name, model = platformDeviceModel()))) {
                pushError("设备注册失败（连接已断开）")
            }
        }
    }
}

package com.daniel.dshremote

import com.daniel.dshremote.protocol.ApprovalDecision
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.BridgeJson
import com.daniel.dshremote.protocol.CachedSessionSnapshot
import com.daniel.dshremote.protocol.ClientCommand
import com.daniel.dshremote.protocol.ContextUsageWire
import com.daniel.dshremote.protocol.DeviceStatus
import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.LogEntryWire
import com.daniel.dshremote.protocol.QuestionAnswerItemWire
import com.daniel.dshremote.protocol.QuestionRequestWire
import com.daniel.dshremote.protocol.QueueItemWire
import com.daniel.dshremote.protocol.ServerEvent
import com.daniel.dshremote.protocol.ServerLogEntry
import com.daniel.dshremote.protocol.ServerLogsResponse
import com.daniel.dshremote.protocol.SessionModelsWire
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.StoredDevice
import com.daniel.dshremote.protocol.StoredEndpoint
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 手机端的总编排：连接策略（候选回退）、协议事件归约到 [SessionUiState]、
 * 把指令派发给 [ConnectionManager]、把设备变更派发给 [DeviceRepository]。
 * 单条连接的收发在 ConnectionManager，设备资产在 DeviceRepository。
 */
class BridgeClient(
    internal val scope: CoroutineScope,
    store: DeviceStore,
    internal val eventCache: EventCache,
    internal val sessionCache: SessionCache,
    internal val draftCache: DraftCache,
    internal val bootNoticeCache: BootNoticeCache,
    internal val pendingStore: PendingStore,
    internal val notifiedKeysStore: NotifiedKeysStore,
) {
    val connection = ConnectionManager(scope)
    val devices = DeviceRepository(scope, store)

    internal val _session = MutableStateFlow(SessionUiState())
    val session: StateFlow<SessionUiState> = _session.asStateFlow()

    /** 待发送消息的送达确认 + 断线自动重放编排（拆到 PendingSender，缓解大类）。 */
    internal val pendingSender = PendingSender(scope, connection, pendingStore, _session)

    internal val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** 统一连接状态提示槽（单一槽：Hidden / Reconnecting / Error；取代旧 reconnecting 布尔）。 */
    internal val _notice = MutableStateFlow<ConnectionNotice>(ConnectionNotice.Hidden)
    val notice: StateFlow<ConnectionNotice> = _notice.asStateFlow()

    /** 重连进度文案（"第 2 次 · 4s 后重试"；仅用于日志与 markReconnecting 详情，不再上横幅）。 */
    internal val _reconnectStatus = MutableStateFlow("")

    internal var connectJob: Job? = null
    internal var reconnectJob: Job? = null
    internal var registeredThisConnection = false
    internal var sawHelloThisConnection = false
    /** 设备切换进行中：旧连接关闭时抑制自动重连（由新 connectJob 接管）。 */
    internal var switchingDevice = false
    /** 冷启动自动连接只尝试一次（每次进自动连接决策即消费）。 */
    internal var autoConnectAttempted = false
    /** 本会话内进入过扫码流程 → 用户选择交互式配对，不再自动连接。 */
    internal var scanStartedOnce = false
    /** 本连接生命周期内用户是否手动关闭过会话（退回列表）；新连接重置，防止重连 hello 把用户拽回会话。 */
    internal var userClosedSessionThisConnection = false

    /**
     * 重连计划提供器：返回 (url, token) 或 null（无有效凭据，放弃重连）。
     * 每次重试时现取——设备 token 可能在上一段连接里被 bridge 轮换过。
     */
    /** 重连候选列表（主端点在前）；null = 无凭据。 */
    internal var reconnectProvider: (() -> List<Pair<String, String?>>?)? = null
    internal var userDisconnect = false
    /** openSession → 订阅 History 到达计时（sessionId -> 打开时刻 ms）。 */
    internal val sessionOpenStartAt = mutableMapOf<String, Long>()
    /** loadOlderPage → HistoryPage 响应计时（sessionId -> 发起翻页时刻 ms）。 */
    internal val olderLoadStartAt = mutableMapOf<String, Long>()
    /** 本地待发送消息 msgId 自增序号（防同一毫秒内多条冲突）。 */
    internal var pendingIdSeq = 0L

    /** 主动通知编排器（三态门控 + 幂等去重 + 勿扰时段 + 幂等键持久化）。 */
    internal val notifications = NotificationController(
        host = object : NotificationHost {
            override fun isForeground() = platformIsAppForeground()
            override fun currentSessionId() = _session.value.currentSessionId
            override fun post(spec: NotificationSpec) = platformPostNotification(spec)
            override fun cancel(tag: String) = platformCancelNotification(tag)
        },
        keys = notifiedKeysStore,
        scope = scope,
    )
    /** 通知点击直达的待打开会话 id（hello 前暂存，hello 后命中则打开）。 */
    internal var pendingOpenSessionId: String? = null

    /** 按需前台服务编排器（running 代理保活；宿主平台能力见 androidMain）。 */
    internal val keepAlive = KeepAliveController(object : KeepAliveHost {
        override fun startOrUpdate(mainCount: Int, subCount: Int) = platformStartKeepAliveService(mainCount, subCount)
        override fun stop() = platformStopKeepAliveService()
    })

    // ---- 待发送消息持久化（P00：任何用户消息都是明确指令，不能丢）----
    //
    // 磁盘是「待发送消息」的事实源（内存 pendingMessages 只是 UI 投影，会被切会话/断连清空）。
    // 落盘只写 Sending/Failed；Sent 不落盘（发送成功即 update 删除该条，回显由服务端历史承载）。
    // 所有 upsert/删除走 PendingStore.update（原子读-改-写），并发发送不丢写、断连清空内存不误删磁盘。

    internal var cacheSaveJob: Job? = null
    internal var lastCacheSaveAt = 0L

    internal var sessionCacheSaveJob: Job? = null
    internal var lastSessionCacheSaveAt = 0L

    // ---- 诊断日志 ----

    internal val logsClient: HttpClient = createPingHttp()

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
                    switchingDevice = false
                    devices.setPollingEnabled(false)
                }
                if (!connected && wasConnected) {
                    onConnectionLost()
                }
                wasConnected = connected
            }
        }
        // 通知点击直达：MainActivity 写入目标会话 id → 打开对应会话（幂等消费后置空）
        scope.launch {
            NotificationLaunch.requestedSessionId.collect { sid ->
                if (sid != null) {
                    handleNotificationOpen(sid)
                    NotificationLaunch.requestedSessionId.value = null
                }
            }
        }
        // 前台服务保活：会话投影 + 连接态 + 前台态三路 combine → 计数 → 启停
        // （前台/后台转场也触发重算：转前台且 counts>0 → 启动/更新；转后台 → NOOP 维持；断连维持、重连校正）
        scope.launch {
            combine(_session, connection.info, platformAppForegroundFlow()) { s, info, foreground ->
                Triple(s.sessions, info.state == ConnectionState.Connected, foreground)
            }.collect { (sessions, connected, foreground) ->
                keepAlive.onProjectionChanged(sessions, connected, foreground)
            }
        }
    }

}

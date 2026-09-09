package com.daniel.dshremote

import com.daniel.dshremote.protocol.AgentSummary
import com.daniel.dshremote.protocol.ApprovalDecision
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.BridgeJson
import com.daniel.dshremote.protocol.CachedSessionSnapshot
import com.daniel.dshremote.protocol.ClientCommand
import com.daniel.dshremote.protocol.DeviceStatus
import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.LogEntryWire
import com.daniel.dshremote.protocol.QuestionAnswerItemWire
import com.daniel.dshremote.protocol.QuestionRequestWire
import com.daniel.dshremote.protocol.QueueItemWire
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

/** 错误分类：recoverable=true 表示「连接类」错误（hello 到达可自动清除）；false 表示业务类错误（需手动清除）。 */
data class NoticeError(val message: String, val recoverable: Boolean)

/** 统一「连接状态提示槽」：单一槽、三形态互斥展示，取代旧的 reconnecting 布尔 + errors 横幅两套独立机制。 */
sealed interface ConnectionNotice {
    /** 无提示（已恢复/未出错）。 */
    data object Hidden : ConnectionNotice
    /** 自动重连中（Loading 形态）。 */
    data class Reconnecting(val attempt: Int) : ConnectionNotice
    /** 错误形态（真实错误）。 */
    data class Error(val message: String) : ConnectionNotice
}

/** hello 到达后的错误对账：清掉「连接类」（可自动恢复）错误，保留业务类错误。 */
internal fun reconcileErrorsOnHello(errors: List<NoticeError>): List<NoticeError> =
    errors.filterNot { it.recoverable }

/**
 * 自动打开候选：当前工作区范围内（null=全部 / UNGROUPED_KEY=未分组 / 具体 id）的
 * 主会话（parentSessionId == null），按 updatedAt 降序取最近一个；无候选返回 null。
 * 过滤语义与 [com.daniel.dshremote.SessionList] 完全一致、排序与列表 updatedAt 倒序一致。
 */
internal fun pickRecentSession(sessions: List<SessionSummary>, workspaceId: String?): SessionSummary? =
    sessions
        .filter { s ->
            s.parentSessionId == null && when (workspaceId) {
                null -> true
                UNGROUPED_KEY -> s.workspaceId == null
                else -> s.workspaceId == workspaceId
            }
        }
        .maxByOrNull { it.updatedAt }

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
    /** 查看子代理会话时记录的返回目标（主会话 id）；返回键/← 回到主会话。 */
    val subagentReturnTo: String? = null,
    /** 子会话打开时，主会话（subagentReturnTo）的 live 投影；平板中栏渲染，手机忽略。 */
    val parentView: SessionViewState = SessionViewState(),
    val events: List<EventProjection> = emptyList(),
    /** 是否还有更早历史可翻页。 */
    val hasMore: Boolean = false,
    /** 会话事件总数。 */
    val historyTotal: Int = 0,
    /** 正在加载更早历史页。 */
    val loadingOlder: Boolean = false,
    /** 当前会话排队的消息（inbox 投影；空 = 无排队）。 */
    val queueItems: List<QueueItemWire> = emptyList(),
    /** 本地待发送消息（乐观上屏 + 送达回显去重 + 失败重发）；会话级，切会话重置。 */
    val pendingMessages: List<PendingMessage> = emptyList(),
    /** 各会话排队条数（placement=queued 的项数；会话级，供列表行中断确认弹框判定）。 */
    val queuedCounts: Map<String, Int> = emptyMap(),
    /** 当前会话模型请求开始时间（null = 未在等待模型）。 */
    val modelWaitingSince: Long? = null,
    /** 本轮对话开始时间（首个模型请求时间；Deep Diving 显示本轮总耗时，跨多次模型调用不重置）。 */
    val divingTurnStart: Long? = null,
    /** Deep Diving 已等待时长（服务端时钟秒数；null = 无等待；客户端不本地计时）。 */
    val deepDivingElapsed: Long? = null,
    /** 当前会话任务列表（DSH todo_write 清单；会话级，切会话重置）。 */
    val todos: List<com.daniel.dshremote.protocol.ServerEvent.TodoWire> = emptyList(),
    /** 当前会话可用的斜杠命令清单（服务端注册表权威；输入 "/" 时弹候选，会话级）。 */
    val commands: List<com.daniel.dshremote.protocol.CommandWire> = emptyList(),
    /** 思考流式实时行（reasoning-delta 节流推送；null = 无流式思考）。 */
    val liveThink: String? = null,
    /** 当前会话持久化目标（null = 无目标）；会话级，切会话重置、按 sessionId 过滤。 */
    val goal: com.daniel.dshremote.protocol.ServerEvent.GoalWire? = null,
    /** 当前会话调试会话状态（null = 无调试）；会话级隔离。 */
    val debug: com.daniel.dshremote.protocol.ServerEvent.DebugStateWire? = null,
    /** 调试进程输出流（最近 200 行）。 */
    val debugOutput: List<String> = emptyList(),
    /** 变量缓存：variablesReference → 变量列表（离开 paused 清空）。 */
    val debugVars: Map<String, List<com.daniel.dshremote.protocol.ServerEvent.DebugVariableWire>> = emptyMap(),
    /** LSP 诊断（跨会话全局：文件级最新集合，cap 100 条）。 */
    val diagnostics: List<com.daniel.dshremote.protocol.ServerEvent.DiagnosticWire> = emptyList(),
    /** 服务端重启通知（重连后 server_boot 推送；横幅展示，可关闭）。 */
    val serverBoot: com.daniel.dshremote.protocol.ServerEvent.ServerBoot? = null,
    /** 待处理审批队列（服务端持有 → 手机裁决；可能多单排队）。 */
    val approvals: List<ApprovalRequestWire> = emptyList(),
    /** 正在发送裁决的审批 id（按钮置灰、发送失败时据此清理）。 */
    val decidingApprovalId: String? = null,
    /** 待回答提问队列（桌面端持有、bridge 经 mux 转发）。 */
    val questions: List<QuestionRequestWire> = emptyList(),
    /** 正在提交答案的提问 rpcId。 */
    val decidingQuestionRpcId: String? = null,
    val errors: List<NoticeError> = emptyList(),
) {
    /** 把 currentSessionId 的内联投影打包成 [SessionViewState]（供 viewOf 复用）。 */
    fun currentView(): SessionViewState = SessionViewState(
        events = events,
        hasMore = hasMore,
        historyTotal = historyTotal,
        loadingOlder = loadingOlder,
        queueItems = queueItems,
        pendingMessages = pendingMessages,
        modelWaitingSince = modelWaitingSince,
        divingTurnStart = divingTurnStart,
        deepDivingElapsed = deepDivingElapsed,
        todos = todos,
        commands = commands,
        liveThink = liveThink,
        goal = goal,
        debug = debug,
        debugOutput = debugOutput,
        debugVars = debugVars,
        diagnostics = diagnostics,
    )

    /** 取某会话的 live 投影：当前会话→内联字段；主会话(子会话打开时)→parentView；其余→空。 */
    fun viewOf(sessionId: String): SessionViewState = when (sessionId) {
        currentSessionId -> currentView()
        subagentReturnTo -> parentView
        else -> SessionViewState()
    }

    /** 把 [SessionViewState] 回写到 currentSessionId 的内联投影字段（handler 路由用）。 */
    fun copyCurrentView(v: SessionViewState): SessionUiState = copy(
        events = v.events,
        hasMore = v.hasMore,
        historyTotal = v.historyTotal,
        loadingOlder = v.loadingOlder,
        queueItems = v.queueItems,
        pendingMessages = v.pendingMessages,
        modelWaitingSince = v.modelWaitingSince,
        divingTurnStart = v.divingTurnStart,
        deepDivingElapsed = v.deepDivingElapsed,
        todos = v.todos,
        commands = v.commands,
        liveThink = v.liveThink,
        goal = v.goal,
        debug = v.debug,
        debugOutput = v.debugOutput,
        debugVars = v.debugVars,
        diagnostics = v.diagnostics,
    )
}

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
    subagentReturnTo = null,
    parentView = SessionViewState(),
    events = emptyList(),
    hasMore = false,
    historyTotal = 0,
    loadingOlder = false,
    queueItems = emptyList(),
    pendingMessages = emptyList(),
    queuedCounts = emptyMap(),
    modelWaitingSince = null,
    divingTurnStart = null,
    deepDivingElapsed = null,
    todos = emptyList(),
    commands = emptyList(),
    liveThink = null,
    goal = null,
    debug = null,
    debugOutput = emptyList(),
    debugVars = emptyMap(),
    diagnostics = emptyList(),
    serverBoot = null,
    approvals = emptyList(),
    decidingApprovalId = null,
    questions = emptyList(),
    decidingQuestionRpcId = null,
)

/**
 * 直连失败时撤销 hint 阶段挂上的设备名（仅当它仍是这台设备），
 * 让 connectedDevice 与 conn.state 同源：连接失败 → 不再标「已连接 · 当前设备」。
 */
internal fun SessionUiState.clearConnectedDeviceIf(key: String): SessionUiState =
    if (connectedDevice?.let { deviceKey(it) } == key) copy(connectedDevice = null) else this

/**
 * 手机端的总编排：连接策略（候选回退）、协议事件归约到 [SessionUiState]、
 * 把指令派发给 [ConnectionManager]、把设备变更派发给 [DeviceRepository]。
 * 单条连接的收发在 ConnectionManager，设备资产在 DeviceRepository。
 */
class BridgeClient(
    private val scope: CoroutineScope,
    store: DeviceStore,
    private val eventCache: EventCache,
    private val sessionCache: SessionCache,
    private val draftCache: DraftCache,
    private val bootNoticeCache: BootNoticeCache,
    private val pendingStore: PendingStore,
) {

    val connection = ConnectionManager(scope)
    val devices = DeviceRepository(scope, store)

    private val _session = MutableStateFlow(SessionUiState())
    val session: StateFlow<SessionUiState> = _session.asStateFlow()

    /** 待发送消息的送达确认 + 断线自动重放编排（拆到 PendingSender，缓解大类）。 */
    private val pendingSender = PendingSender(scope, connection, pendingStore, _session)

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** 统一连接状态提示槽（单一槽：Hidden / Reconnecting / Error；取代旧 reconnecting 布尔）。 */
    private val _notice = MutableStateFlow<ConnectionNotice>(ConnectionNotice.Hidden)
    val notice: StateFlow<ConnectionNotice> = _notice.asStateFlow()

    /** 重连进度文案（"第 2 次 · 4s 后重试"；仅用于日志与 markReconnecting 详情，不再上横幅）。 */
    private val _reconnectStatus = MutableStateFlow("")

    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var registeredThisConnection = false
    private var sawHelloThisConnection = false
    /** 设备切换进行中：旧连接关闭时抑制自动重连（由新 connectJob 接管）。 */
    private var switchingDevice = false
    /** 冷启动自动连接只尝试一次（每次进自动连接决策即消费）。 */
    private var autoConnectAttempted = false
    /** 本会话内进入过扫码流程 → 用户选择交互式配对，不再自动连接。 */
    private var scanStartedOnce = false
    /** 本连接生命周期内用户是否手动关闭过会话（退回列表）；新连接重置，防止重连 hello 把用户拽回会话。 */
    private var userClosedSessionThisConnection = false

    /**
     * 重连计划提供器：返回 (url, token) 或 null（无有效凭据，放弃重连）。
     * 每次重试时现取——设备 token 可能在上一段连接里被 bridge 轮换过。
     */
    /** 重连候选列表（主端点在前）；null = 无凭据。 */
    private var reconnectProvider: (() -> List<Pair<String, String?>>?)? = null
    private var userDisconnect = false
    /** openSession → 订阅 History 到达计时（sessionId -> 打开时刻 ms）。 */
    private val sessionOpenStartAt = mutableMapOf<String, Long>()
    /** loadOlderPage → HistoryPage 响应计时（sessionId -> 发起翻页时刻 ms）。 */
    private val olderLoadStartAt = mutableMapOf<String, Long>()
    /** 本地待发送消息 msgId 自增序号（防同一毫秒内多条冲突）。 */
    private var pendingIdSeq = 0L

    /** 主动通知编排器（三态门控 + 幂等去重 + 勿扰时段；宿主平台能力见 androidMain）。 */
    private val notifications = NotificationController(object : NotificationHost {
        override fun isForeground() = platformIsAppForeground()
        override fun currentSessionId() = _session.value.currentSessionId
        override fun post(spec: NotificationSpec) = platformPostNotification(spec)
        override fun cancel(tag: String) = platformCancelNotification(tag)
    })
    /** 各会话最近一次 OPEN 轮次起点（turn_status 投影；结果交付「会话×轮次」幂等键）。 */
    private val turnStartBySession = mutableMapOf<String, Long>()
    /** 各会话最近一次「最终结论」时间戳（结果交付降噪：主会话 idle 需有 assistant_message）。 */
    private val lastAssistantTsBySession = mutableMapOf<String, Long>()
    /** 各会话最近一次非空 tool_result 时间戳（结果交付降噪：子代理 settle 允许 tool_result）。 */
    private val lastToolResultTsBySession = mutableMapOf<String, Long>()
    /** 通知点击直达的待打开会话 id（hello 前暂存，hello 后命中则打开）。 */
    private var pendingOpenSessionId: String? = null

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
    }

    /** 通知点击直达：会话已加载则立即打开；未加载（hello 未到）则暂存待 hello 后打开。 */
    private fun handleNotificationOpen(sessionId: String) {
        val target = resolveNotificationOpenTarget(sessionId, _session.value.sessions)
        if (target != null) {
            ConnLog.info("NOTIFY", "通知直达打开会话 session=${target.take(8)}")
            openSession(target)
            pendingOpenSessionId = null
        } else {
            pendingOpenSessionId = sessionId
            ConnLog.info("NOTIFY", "通知直达暂存待 hello session=${sessionId.take(8)}")
        }
    }

    /** hello 后消费暂存的直达会话：命中则打开，未命中则停留会话列表。 */
    private fun maybeOpenPendingNotificationSession() {
        val sid = pendingOpenSessionId ?: return
        val target = resolveNotificationOpenTarget(sid, _session.value.sessions)
        pendingOpenSessionId = null
        if (target != null) {
            ConnLog.info("NOTIFY", "通知直达打开会话 session=${target.take(8)}")
            openSession(target)
        } else {
            ConnLog.info("NOTIFY", "通知直达会话未找到 session=${sid.take(8)}，停留会话列表")
        }
    }

    /** 连接断开后的策略：用户主动断开→清理；有凭据→自动重连；否则→清理。 */
    private fun onConnectionLost() {
        if (switchingDevice) {
            ConnLog.info("RECONNECT", "设备切换中的旧连接关闭，不触发自动重连")
            switchingDevice = false
            return
        }
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
        _notice.value = ConnectionNotice.Hidden
        _reconnectStatus.value = ""
        _session.update { it.clearedForDisconnect() }
        devices.setPollingEnabled(true)
        if (detail != null) connection.fail(detail) else connection.markDisconnected()
    }

    /** 指数退避自动重连；凭据失效（provider 返回 null）或鉴权错误时放弃。 */
    /** 置槽为 Reconnecting：仅由重连循环在「真实发起新一轮尝试」时调用（internal 供单测注入重连态）。 */
    internal fun beginReconnectNotice(attempt: Int) {
        _notice.value = ConnectionNotice.Reconnecting(attempt)
    }

    private fun startReconnect() {
        // 幂等：断开边沿可能多次触发，已有活跃循环时不得重启——
        // 否则新旧循环会在 ConnectionManager 里并发 open()，互相踩 ws/currentUrl 状态。
        // 注意：此处【不】重断言 Reconnecting——重断言只在循环内真实发起新一轮尝试时进行，
        // 否则 onConnectionLost 触发的入口重断言会与「hello 已清槽」竞态，导致横幅在
        // 成功连接后仍残留（真机实测：hello 到达后横幅 ~1 分钟才消失）。
        if (reconnectJob?.isActive == true) return
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
                    beginReconnectNotice(attempt)
                    connection.markReconnecting(_reconnectStatus.value)
                    ConnLog.info("RECONNECT", "第 $attempt 次重试将在 ${wait / 1000}s 后执行$hint")
                    delay(wait)
                    if (!isActive) return@launch
                    val candidates = reconnectProvider?.invoke() ?: break
                    var established = false
                    for ((index, candidate) in plan.withIndex()) {
                        val (url, token) = candidate
                        ConnLog.info("RECONNECT", "第 $attempt 次重连 · 候选 ${index + 1}/${plan.size}: $url")
                        if (connection.open(url, token)) {
                            established = true
                            break
                        }
                        ConnLog.warn("RECONNECT", "候选失败 $url: ${connection.info.value.detail}")
                    }
                    if (established) consecutiveFailures = 0 else consecutiveFailures++
                    // 成功建立过连接且期间收到过 Hello → 重置退避（横幅由 hello 到达清除）
                    if (established && sawHelloThisConnection) {
                        attempt = 1
                        sawHelloThisConnection = false
                        // 横幅由 hello 到达统一清除（handle(Hello)），这里只重置退避。
                        ConnLog.info("RECONNECT", "重连成功，退避重置")
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
        ConnLog.info("CONNECT", "开始扫码")
        scanStartedOnce = true
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
        userClosedSessionThisConnection = false
        connectJob?.cancel()
        reconnectJob?.cancel()
        _notice.value = ConnectionNotice.Hidden
        connectJob = scope.launch { connectFromQr(text) }
    }

    /**
     * 冷启动自动连接：上次连接的设备（或无记录时的最近使用设备）在线 → 直接连接，
     * 免去每次手动点「连接」。只在完全空闲时触发一次；用户进过扫码流程则不打扰。
     */
    fun autoConnectOnce() {
        if (autoConnectAttempted || scanStartedOnce || switchingDevice) return
        if (_scanning.value) return
        val connState = connection.info.value.state
        if (connState == ConnectionState.Connected || connState == ConnectionState.Connecting || _notice.value is ConnectionNotice.Reconnecting || userDisconnect) return
        val st = devices.state.value
        if (st.devices.isEmpty()) return
        // 等首轮探测出结果再决策（Checking 未结束时先不动）
        val statuses = st.deviceStatuses
        if (statuses.isEmpty() || st.devices.any { statuses[deviceKey(it)] == DeviceStatus.Checking }) return
        autoConnectAttempted = true
        val target = st.devices.firstOrNull { deviceKey(it) == devices.lastConnectedKey }
            ?: st.devices.maxByOrNull { it.lastSeenAt }
        if (target == null) {
            ConnLog.info("AUTO", "无候选设备，跳过自动连接")
            return
        }
        val status = st.deviceStatuses[deviceKey(target)] ?: return
        if (status != DeviceStatus.Online && status != DeviceStatus.Changed) {
            ConnLog.info("AUTO", "上次设备离线（$status），等待手动连接: ${target.name}")
            return
        }
        ConnLog.info("AUTO", "上次设备在线 → 自动连接 ${target.name} (${target.host}:${target.port})")
        connectDevice(target)
    }

    fun connectDevice(device: StoredDevice) {
        ConnLog.info("CONNECT", "连接设备 ${device.name} (${device.host}:${device.port})")
        userClosedSessionThisConnection = false
        devices.rememberLastConnected(device)
        hydrateFromSessionCache(device)
        val key = deviceKey(device)
        // 已有连接/重连循环 → 先干净断开旧链路再连新设备（多设备切换）
        val wasConnected = connection.info.value.state == ConnectionState.Connected || _notice.value is ConnectionNotice.Reconnecting
        if (wasConnected) {
            ConnLog.info("CONNECT", "切换设备 → 先断开当前链路")
            switchingDevice = true
            userDisconnect = true // 抑制旧链路的自动重连
            reconnectJob?.cancel()
            reconnectJob = null
            _notice.value = ConnectionNotice.Hidden
            _reconnectStatus.value = ""
            connectJob?.cancel()
            connection.close()
            _session.update { it.clearedForDisconnect() }
        }
        userDisconnect = false
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
            if (!ok) {
                // 全部候选失败：撤销直连前 hintName 挂上的设备名，避免设备页同时出现
                // 「连接失败」横幅（conn.state=Error）与「已连接 · 当前设备」（connectedDevice 残留）自相矛盾。
                _session.update { it.clearConnectedDeviceIf(key) }
                if (eps.any { it.host == "127.0.0.1" }) {
                    connection.fail(
                        connection.info.value.detail.ifBlank { "连接失败" } +
                            "\n提示：USB 连接请先在电脑上执行 adb reverse tcp:3080 tcp:3080",
                    )
                }
            }
        }
    }

    fun connectManual(host: String, port: Int, token: String?) {
        ConnLog.info("CONNECT", "手动连接 $host:$port token=${if (token.isNullOrBlank()) "无" else "有"}")
        reconnectProvider = { listOf(Pairing.buildUrl(host, port) to token) }
        userDisconnect = false
        userClosedSessionThisConnection = false
        connect(host, port, token, null)
    }

    fun disconnect() {
        ConnLog.info("CONNECT", "用户断开连接")
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
        ConnLog.info("CMD", "打开会话 sessionId=$sessionId 前会话=${_session.value.currentSessionId}")
        sessionOpenStartAt[sessionId] = nowMillis()
        // 关键：切换会话必须清空全部会话级状态（Deep Diving/思考流/诊断/目标/调试/队列/分页），
        // 否则上个会话的指示条/诊断/目标/调试状态会串到新会话里展示。
        _session.update {
            it.copy(
                currentSessionId = sessionId,
                events = emptyList(),
                queueItems = emptyList(),
                pendingMessages = emptyList(),
                modelWaitingSince = null,
                divingTurnStart = null,
                deepDivingElapsed = null,
                todos = emptyList(),
                commands = emptyList(),
                liveThink = null,
                goal = null,
                debug = null,
                debugOutput = emptyList(),
                debugVars = emptyMap(),
                diagnostics = emptyList(),
                hasMore = false,
                loadingOlder = false,
                historyTotal = 0,
            )
        }
        scope.launch {
            // 恢复本地待发送消息：断线/杀进程/切换视图后不丢（P00）。sending 一律转 failed
            // （发送结果未知，避免自动重发导致重复投递，交用户点 ❗ 手动决定）。
            val restored = restorePendingFromDisk(pendingStore.load(sessionId))
            if (restored.isNotEmpty()) {
                _session.update { s ->
                    if (s.currentSessionId == sessionId) {
                        // 合并而非覆盖：openSession 已同步清空，但恢复期间的极短窗口内可能又发出新消息
                        val others = s.pendingMessages.filterNot { it.sessionId == sessionId }
                        s.copy(pendingMessages = restored + others)
                    } else {
                        s
                    }
                }
                // 回写已转换状态（sending→failed），保证下次重启不再重复转换
                pendingStore.save(sessionId, restored)
                ConnLog.info("PENDING", "会话 $sessionId 恢复待发送消息 ${restored.size} 条（sending→failed）")
            }
            // 断线自动重放：对恢复出的 failed 消息（retryCount < 上限）按序自动重发（同 msgId 幂等）
            pendingSender.autoReplaySession(sessionId)
            // 先渲染本地缓存（秒开），订阅返回后以服务端历史为准
            val key = eventCacheKey(sessionId)
            val cached = eventCache.load(key)
            if (cached.isNotEmpty()) {
                _session.update { s ->
                    if (s.currentSessionId == sessionId) s.copy(events = cached.bounded()) else s
                }
                ConnLog.info("CACHE", "会话 $sessionId 载入本地缓存 ${cached.size} 条")
            }
            if (!connection.send(ClientCommand.Subscribe(sessionId))) pushConnectionError("订阅会话失败（连接已断开）")
        }
    }

    /** 会话事件缓存 key：设备端点 + 会话 id（不同桌面互不串扰）。 */
    private fun eventCacheKey(sessionId: String): String {
        val device = _session.value.connectedDevice
        val prefix = device?.let { "${it.host}_${it.port}" } ?: "unknown"
        return "$prefix-$sessionId"
    }

    // ---- 输入框草稿（断线/重连/重启不丢打字）----

    private fun draftKey(sessionId: String): String {
        val device = _session.value.connectedDevice
        val prefix = device?.let { it.serverId ?: "${it.host}_${it.port}" } ?: "unknown"
        return "$prefix-$sessionId"
    }

    /** 载入某会话的待发送草稿。 */
    suspend fun loadDraft(sessionId: String): String? = draftCache.load(draftKey(sessionId))

    /** 保存草稿（空白 = 清除）；节流由 UI 层防抖负责。 */
    fun saveDraft(sessionId: String, text: String) {
        scope.launch { draftCache.save(draftKey(sessionId), text) }
    }

    // ---- 待发送消息持久化（P00：任何用户消息都是明确指令，不能丢）----
    //
    // 磁盘是「待发送消息」的事实源（内存 pendingMessages 只是 UI 投影，会被切会话/断连清空）。
    // 落盘只写 Sending/Failed；Sent 不落盘（发送成功即 update 删除该条，回显由服务端历史承载）。
    // 所有 upsert/删除走 PendingStore.update（原子读-改-写），并发发送不丢写、断连清空内存不误删磁盘。

    private var cacheSaveJob: Job? = null
    private var lastCacheSaveAt = 0L

    /** 事件到达后节流落盘（2s 节流，避免每条事件都写文件）。 */
    private fun scheduleCacheSave() {
        val sid = _session.value.currentSessionId ?: return
        val now = nowMillis()
        if (now - lastCacheSaveAt < 2_000) return
        lastCacheSaveAt = now
        cacheSaveJob?.cancel()
        cacheSaveJob = scope.launch {
            val events = _session.value.events
            if (events.isEmpty()) return@launch
            eventCache.save(eventCacheKey(sid), events)
        }
    }

    // ---- 会话/工作区元数据缓存（列表秒开 + 增量对账）----

    /** 会话元数据缓存 key：桌面指纹 serverId 优先（网络切换不变），退化为端点。 */
    private fun sessionCacheKeyOf(device: StoredDevice): String =
        device.serverId ?: "${device.host}_${device.port}"

    /** server_boot「已读版本」记忆 key：桌面指纹 serverId 优先，退化为端点（不同桌面互不吞提示）。 */
    private fun bootNoticeKey(device: StoredDevice): String =
        device.serverId ?: "${device.host}_${device.port}"

    /**
     * 连接开始时用本地缓存水合会话/工作区列表（打开 App 秒开，不实时拉）；
     * Hello 快照到达后做增量对账覆盖。仅在状态为空时水合，不打断在线数据。
     */
    private fun hydrateFromSessionCache(device: StoredDevice) {
        val cur = _session.value
        if (cur.sessions.isNotEmpty() || cur.workspaces.isNotEmpty()) return
        val key = sessionCacheKeyOf(device)
        scope.launch {
            val snap = sessionCache.load(key) ?: return@launch
            ConnLog.info("CACHE", "会话列表缓存命中：${snap.sessions.size} 会话 / ${snap.workspaces.size} 工作区")
            _session.update { s ->
                if (s.sessions.isNotEmpty() || s.workspaces.isNotEmpty()) s
                else s.copy(
                    sessions = snap.sessions,
                    workspaces = snap.workspaces,
                    selectedWorkspaceId = snap.selectedWorkspaceId ?: s.selectedWorkspaceId,
                )
            }
        }
    }

    private var sessionCacheSaveJob: Job? = null
    private var lastSessionCacheSaveAt = 0L

    /** 元数据变更后节流回写（2s），与事件缓存同节奏。 */
    private fun scheduleSessionCacheSave() {
        val device = _session.value.connectedDevice ?: return
        val now = nowMillis()
        if (now - lastSessionCacheSaveAt < 2_000) return
        lastSessionCacheSaveAt = now
        sessionCacheSaveJob?.cancel()
        val key = sessionCacheKeyOf(device)
        sessionCacheSaveJob = scope.launch {
            val s = _session.value
            sessionCache.save(
                key,
                CachedSessionSnapshot(
                    sessions = s.sessions,
                    workspaces = s.workspaces,
                    selectedWorkspaceId = s.selectedWorkspaceId,
                    savedAt = nowMillis(),
                ),
            )
        }
    }

    /**
     * 关闭当前会话视图，返回会话列表。纯本地导航：协议没有「取消订阅」命令
     * （bridge 对 Subscribe(null) 会回 not_found 错误），未订阅会话的事件
     * 在 handle() 里按 sessionId 过滤丢弃，无需通知服务端。
     */
    fun closeSession() {
        ConnLog.info("CMD", "关闭会话 current=${_session.value.currentSessionId} returnTo=${_session.value.subagentReturnTo}")
        // 子代理视图的关闭 = 回到主会话（返回键与 ← 按钮共用此路径）
        val returnTo = _session.value.subagentReturnTo
        if (returnTo != null) {
            _session.update { it.copy(subagentReturnTo = null, parentView = SessionViewState()) }
            openSession(returnTo)
            return
        }
        // 真正退回列表：标记本连接生命周期内用户手动关闭过会话，抑制后续 hello 自动打开
        userClosedSessionThisConnection = true
        _session.update { it.copy(currentSessionId = null, events = emptyList()) }
    }

    /** 从主会话进入子代理会话：记录返回目标，返回键/← 回到主会话。 */
    fun openSubagent(subagentId: String) {
        val parentId = _session.value.currentSessionId ?: return
        if (parentId == subagentId) return
        ConnLog.info("CMD", "打开子代理 subagentId=$subagentId parentId=$parentId")
        // 平板 route B：快照主会话投影到 parentView，子会话打开后中栏仍能 live 渲染主会话。
        _session.update { it.copy(subagentReturnTo = parentId, parentView = it.currentView()) }
        openSession(subagentId)
    }

    fun sendMessage(text: String) {
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
    fun retryMessage(msgId: String) {
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

    fun interrupt(sessionId: String, mode: String = "clear") {
        val s = _session.value
        val status = s.sessions.firstOrNull { it.id == sessionId }?.status
        ConnLog.info("CMD", "中断会话 sessionId=$sessionId mode=$mode status=$status modelWaitingSince=${s.modelWaitingSince}")
        scope.launch {
            if (!connection.send(ClientCommand.Interrupt(sessionId, mode))) pushConnectionError("中断指令发送失败（连接已断开）")
        }
    }

    /** 排队消息操作：steer = 插队（注入当前轮）；remove = 移除排队消息。 */
    /** 加载更早的一页历史（seq < 当前窗口最小 seq）。 */
    fun loadOlderPage(sessionId: String) {
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

    fun sendQueueAction(sessionId: String, itemId: String, action: String) {
        ConnLog.info("CMD", "排队操作 sessionId=$sessionId itemId=$itemId action=$action")
        scope.launch {
            if (!connection.send(ClientCommand.QueueAction(sessionId, itemId, action))) {
                pushConnectionError("排队操作发送失败（连接已断开）")
            }
        }
    }

    /** 调试控制：resume / step / step_out / stop / variables（带引用）。 */
    fun sendDebugCommand(sessionId: String, action: String, variablesReference: String? = null) {
        ConnLog.info("CMD", "调试指令 sessionId=$sessionId action=$action variablesReference=$variablesReference")
        scope.launch {
            if (!connection.send(ClientCommand.DebugCommand(sessionId, action, variablesReference))) {
                pushConnectionError("调试指令发送失败（连接已断开）")
            }
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
                pushConnectionError("审批决策发送失败（连接已断开）")
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
                pushConnectionError("提问答案发送失败（连接已断开）")
            }
        }
    }

    /** 清空错误提示（含统一槽）。 */
    fun dismissErrors() {
        _session.update { it.copy(errors = emptyList()) }
        _notice.value = ConnectionNotice.Hidden
    }

    /** 关闭服务端重启通知横幅：记下该服务端标识的已读版本，并隐藏横幅。 */
    fun dismissBootNotice() {
        val boot = _session.value.serverBoot ?: return
        val device = _session.value.connectedDevice
        val key = device?.let { bootNoticeKey(it) } ?: "unknown"
        _session.update { it.copy(serverBoot = null) }
        scope.launch { bootNoticeCache.save(key, boot.version) }
    }

    // ---- 诊断日志 ----

    private val logsClient: HttpClient = createPingHttp()

    /** 拉取服务端结构化连接日志（/remote/logs）。已连接时用当前设备，未连接时回退最近在线设备。 */
    suspend fun loadServerLogs(limit: Int = 300): List<ServerLogEntry>? {
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

    // ---- 设备管理 ----

    fun forgetDevice(device: StoredDevice) {
        ConnLog.info("CONNECT", "忘记设备 ${device.name} (${device.host}:${device.port})")
        val key = deviceKey(device)
        scope.launch {
            devices.remove(key)
            val connected = _session.value.connectedDevice
            if (connected != null && deviceKey(connected) == key) {
                if (!connection.send(ClientCommand.RevokeDevice(device.deviceId))) {
                    pushConnectionError("撤销桌面端凭据失败（连接已断开）")
                }
            }
        }
    }

    // ---- 内部 ----

    /** 连接类错误（「连接已断开」）：重连中静默（不堆积、不覆盖 Reconnecting 槽）；否则展示于槽并按可自动恢复入历史。 */
    internal fun pushConnectionError(message: String) {
        if (_notice.value is ConnectionNotice.Reconnecting) {
            // 重连中已用 Reconnecting 表达状态，抑制「连接已断开」类错误，避免矛盾同屏
            return
        }
        _session.update { it.copy(errors = (it.errors + NoticeError(message, recoverable = true)).takeLast(MAX_ERRORS)) }
        _notice.value = ConnectionNotice.Error(message)
    }

    /** 业务类错误（服务端错误码）：入历史、展示于槽、需手动清除（hello 不清）。internal 供单测。 */
    internal fun pushBusinessError(message: String) {
        _session.update { it.copy(errors = (it.errors + NoticeError(message, recoverable = false)).takeLast(MAX_ERRORS)) }
        if (_notice.value !is ConnectionNotice.Reconnecting) {
            _notice.value = ConnectionNotice.Error(message)
        }
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
                pushConnectionError("设备注册失败（连接已断开）")
            }
        }
    }

    internal fun handle(ev: ServerEvent) {
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
            is ServerEvent.ModelsUpdate -> ConnLog.debug("MODEL", "模型目录快照（阶段1占位，阶段3落状态）sessionId=${ev.sessionId}")
            is ServerEvent.ContextUsage -> ConnLog.debug("CTX", "上下文占用（阶段1占位，阶段3落状态）sessionId=${ev.sessionId}")
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
            is ServerEvent.DeviceRegistered -> handleDeviceRegistered(ev)
            is ServerEvent.DeviceRevoked -> handleDeviceRevoked(ev)
            is ServerEvent.Ack -> pendingSender.handleAck(ev)
            is ServerEvent.Pong -> Unit // 判活 pong 由 ConnectionManager 内部消费，兜底忽略
            is ServerEvent.Error -> handleError(ev)
        }
    }

    /**
     * 把一次「会话详情投影」变换路由到正确目标：currentSessionId → 内联字段；
     * subagentReturnTo（子会话打开时的主会话）→ parentView；其余 → 丢弃。
     * 平板 route B「双 live」的事件路由核心（手机无子会话时 subagentReturnTo 恒 null，行为不变）。
     */
    private fun updateView(sessionId: String, transform: (SessionViewState) -> SessionViewState) {
        _session.update { s ->
            when (sessionId) {
                s.currentSessionId -> s.copyCurrentView(transform(s.currentView()))
                s.subagentReturnTo -> s.copy(parentView = transform(s.parentView))
                else -> s
            }
        }
    }

    private fun handleHello(ev: ServerEvent.Hello) {
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
                // 子代理返回目标同样以服务端快照为准校验
                subagentReturnTo = it.subagentReturnTo
                    ?.takeIf { p -> ev.sessions.any { s -> s.id == p } },
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
    }

    /** 连接建立后的自动打开：条件满足才打开，否则只记 INFO 埋点。 */
    private fun maybeAutoOpenRecentSession() {
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

    private fun handleHistory(ev: ServerEvent.History) {
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

    private fun handleEvent(ev: ServerEvent.Event) {
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
        // 结果交付降噪：记录各会话「最终结论 / 非空工具产出」的最近时间戳（跨会话，非当前会话也记）
        when {
            ev.event.type == "assistant_message" -> lastAssistantTsBySession[ev.sessionId] = ev.event.timestamp
            ev.event.type == "tool_result" && !ev.event.toolResult.isNullOrBlank() ->
                lastToolResultTsBySession[ev.sessionId] = ev.event.timestamp
        }
        updateView(ev.sessionId) { v -> v.copy(events = (v.events + ev.event).bounded()) }
        if (ev.sessionId == _session.value.currentSessionId) scheduleCacheSave()
    }

    private fun handleSessionQueue(ev: ServerEvent.SessionQueue) {
        val visible = userVisibleQueueItems(ev.items)
        val queued = visible.count { it.placement == "queued" }
        // queuedCounts 是跨会话的全局计数（供列表行中断确认弹框），无论当前/后台都更新。
        _session.update { s ->
            s.copy(queuedCounts = if (queued == 0) s.queuedCounts - ev.sessionId else s.queuedCounts + (ev.sessionId to queued))
        }
        updateView(ev.sessionId) { v -> v.copy(queueItems = visible) }
    }

    private fun handleModelWaiting(ev: ServerEvent.ModelWaiting) {
        // Deep Diving 本轮计时：首轮模型请求记录本轮起点，后续请求沿用（不重置）
        updateView(ev.sessionId) { v ->
            v.copy(modelWaitingSince = ev.startedAt, divingTurnStart = v.divingTurnStart ?: ev.startedAt)
        }
    }

    private fun handleModelWaitingDone(ev: ServerEvent.ModelWaitingDone) {
        // 只清「等待模型」指示；Deep Diving 时钟是轮次级状态（锚定轮次起点），
        // 一轮中可能有多次模型调用，每次完成都会广播一次 model_waiting_done——
        // 若在这里清 deepDivingElapsed，时钟会在下一个 tick（≤1s）前短暂消失，
        // 正是用户看到的「计时器闪烁」。轮次级时钟只在 turn_status(closed) 清除。
        updateView(ev.sessionId) { v ->
            if (v.modelWaitingSince == ev.startedAt) v.copy(modelWaitingSince = null) else v
        }
    }

    private fun handleDeepDivingTick(ev: ServerEvent.DeepDivingTick) {
        // 会话隔离：等待时长只归属对应会话（服务端时钟秒数，本地不再计时）
        updateView(ev.sessionId) { v -> v.copy(deepDivingElapsed = ev.elapsedSeconds) }
    }

    private fun handleTurnStatus(ev: ServerEvent.TurnStatus) {
        // 结果交付：记录各会话轮次起点（跨会话）；open=false（turn/end）时按「会话×轮次」幂等发通知
        if (ev.open) {
            ev.since?.let { turnStartBySession[ev.sessionId] = it }
        }
        updateView(ev.sessionId) { v ->
            // 与 DSH Web 对齐：整个轮次期间显示 Deep diving 标签（不只等模型时）；
            // 轮次结束清掉标签与计时。服务端为轮次生命周期的唯一权威。
            if (ev.open) v.copy(divingTurnStart = ev.since, deepDivingElapsed = 0)
            else v.copy(divingTurnStart = null, deepDivingElapsed = null)
        }
        if (!ev.open) notifyTurnDelivery(ev.sessionId)
    }

    private fun handleThinkDelta(ev: ServerEvent.ThinkDelta) {
        updateView(ev.sessionId) { v -> v.copy(liveThink = ev.text.takeIf { it.isNotEmpty() }) }
    }

    private fun handleDiagnostics(ev: ServerEvent.Diagnostics) {
        updateView(ev.sessionId) { v ->
            // 会话隔离：诊断只归属触发它的会话；文件级替换（空集合 = 该文件已无问题）
            val rest = v.diagnostics.filterNot { it.path == ev.path }
            v.copy(diagnostics = (rest + ev.diagnostics).takeLast(100))
        }
    }

    private fun handleGoalUpdate(ev: ServerEvent.GoalUpdate) {
        // 会话隔离：目标变更只归属对应会话（goal=null 表示已清除 → 隐藏面板）
        updateView(ev.sessionId) { v -> v.copy(goal = ev.goal) }
        // 结果交付 D3：goal 终态（complete / blocked）主动通知（幂等键 = goal.updatedAt）
        val goal = ev.goal
        if (goal != null && (goal.phase == "complete" || goal.phase == "blocked")) {
            notifyGoalDelivery(ev.sessionId, goal.updatedAt, blocked = goal.phase == "blocked", blockedMessage = goal.blockedMessage)
        }
    }

    private fun handleTodosUpdate(ev: ServerEvent.TodosUpdate) {
        // 会话隔离：任务列表只归属对应会话（每会话一份）
        updateView(ev.sessionId) { v -> v.copy(todos = ev.todos) }
    }

    private fun handleCommandsUpdate(ev: ServerEvent.CommandsUpdate) {
        // 会话隔离：斜杠命令清单只归属对应会话（候选弹窗数据源，服务端权威）
        updateView(ev.sessionId) { v -> v.copy(commands = ev.commands) }
    }

    private fun handleDebugState(ev: ServerEvent.DebugState) {
        updateView(ev.sessionId) { v ->
            // 会话隔离；离开 paused 时清空变量缓存（objectId 已失效）
            v.copy(debug = ev.debug, debugVars = if (ev.debug.state == "paused") v.debugVars else emptyMap())
        }
    }

    private fun handleDebugOutput(ev: ServerEvent.DebugOutput) {
        updateView(ev.sessionId) { v -> v.copy(debugOutput = (v.debugOutput + ev.line).takeLast(200)) }
    }

    private fun handleDebugVariables(ev: ServerEvent.DebugVariables) {
        updateView(ev.sessionId) { v ->
            v.copy(debugVars = v.debugVars + (ev.variablesReference to ev.variables))
        }
    }

    private fun handleServerBoot(ev: ServerEvent.ServerBoot) {
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

    private fun handleLogsRequest(ev: ServerEvent.LogsRequest) {
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

    private fun handleAgentStatus(ev: ServerEvent.AgentStatus) {
        val prevStatus = _session.value.sessions.firstOrNull { it.id == ev.sessionId }?.status
        _session.update { s ->
            s.copy(
                sessions = s.sessions.map { if (it.id == ev.sessionId) it.copy(status = ev.status) else it },
                agents = s.agents.map { if (it.sessionId == ev.sessionId) it.copy(status = ev.status) else it },
                // 轮次生命周期由服务端 turn_status 事件管理（本地不再推断轮次边界）
            )
        }
        scheduleSessionCacheSave()
        // 结果交付 D1/D2 兜底：agent 由 running→idle（与 turn_status close 同源，幂等去重）
        if (prevStatus == "running" && ev.status == "idle") {
            notifyTurnDelivery(ev.sessionId)
        }
    }

    /** 结果交付降噪：该会话本轮是否有「最终结论 / 非空工具产出」（主会话只认 assistant_message）。 */
    private fun hasSubstantiveOutput(sessionId: String, isSubagent: Boolean): Boolean {
        val turnStart = turnStartBySession[sessionId]
        val assistant = lastAssistantTsBySession[sessionId]
        val toolResult = lastToolResultTsBySession[sessionId]
        fun Long?.afterTurn(): Boolean = this != null && (turnStart == null || this >= turnStart)
        return if (isSubagent) assistant.afterTurn() || toolResult.afterTurn() else assistant.afterTurn()
    }

    /** 结果交付 D1/D2：轮次结束 / agent 空闲 → 主动通知（幂等键 = 会话×轮次起点）。 */
    private fun notifyTurnDelivery(sessionId: String) {
        val s = _session.value
        val sess = s.sessions.firstOrNull { it.id == sessionId }
        val isSubagent = sess?.parentSessionId != null
        if (!hasSubstantiveOutput(sessionId, isSubagent)) {
            ConnLog.info("NOTIFY", "结果交付跳过（无实质产出）session=${sessionId.take(8)} subagent=$isSubagent")
            return
        }
        val turnKey = turnStartBySession[sessionId]?.toString() ?: "no-turn"
        notifications.onDelivery(sessionId, sess?.name, isSubagent, turnKey, blocked = false, blockedMessage = null)
    }

    /** 结果交付 D3：goal 终态 → 主动通知（幂等键 = goal.updatedAt）。 */
    private fun notifyGoalDelivery(sessionId: String, updatedAt: Long, blocked: Boolean, blockedMessage: String?) {
        val s = _session.value
        val sess = s.sessions.firstOrNull { it.id == sessionId }
        notifications.onDelivery(sessionId, sess?.name, sess?.parentSessionId != null, "goal-$updatedAt", blocked, blockedMessage)
    }

    private fun handleSessionTitle(ev: ServerEvent.SessionTitle) {
        _session.update { s ->
            s.copy(
                sessions = s.sessions.map {
                    if (it.id == ev.sessionId) it.copy(name = ev.title) else it
                },
            )
        }
        scheduleSessionCacheSave()
    }

    private fun handleSessionUpsert(ev: ServerEvent.SessionUpsert) {
        // 列表增量：同 id 替换行，再按 updatedAt 倒序归位
        _session.update { s ->
            val rows = (s.sessions.filterNot { it.id == ev.session.id } + ev.session)
                .sortedByDescending { it.updatedAt }
            s.copy(sessions = rows)
        }
        scheduleSessionCacheSave()
    }

    private fun handleApprovalRequest(ev: ServerEvent.ApprovalRequest) {
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

    private fun handleApprovalResolved(ev: ServerEvent.ApprovalResolved) {
        notifications.onApprovalResolved(ev.approvalId)
        _session.update { s ->
            ConnLog.info("APPROVAL", "审批已解决 approval=${ev.approvalId.take(8)} outcome=${ev.outcome}")
            s.copy(
                approvals = s.approvals.filterNot { a -> a.approvalId == ev.approvalId },
                decidingApprovalId = s.decidingApprovalId.takeIf { it != ev.approvalId },
            )
        }
    }

    private fun handleApprovalSettledLegacy(ev: ServerEvent.ApprovalSettledLegacy) {
        notifications.onApprovalResolved(ev.approvalId)
        _session.update { s ->
            ConnLog.info("APPROVAL", "审批已解决（旧版事件）approval=${ev.approvalId.take(8)}")
            s.copy(
                approvals = s.approvals.filterNot { a -> a.approvalId == ev.approvalId },
                decidingApprovalId = s.decidingApprovalId.takeIf { it != ev.approvalId },
            )
        }
    }

    private fun handleQuestionRequest(ev: ServerEvent.QuestionRequest) {
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

    private fun handleQuestionResolved(ev: ServerEvent.QuestionResolved) {
        notifications.onQuestionResolved(ev.rpcId)
        _session.update { s ->
            ConnLog.info("QUESTION", "提问已解决 rpc=${ev.rpcId.take(8)} outcome=${ev.outcome}")
            s.copy(
                questions = s.questions.filterNot { q -> q.rpcId == ev.rpcId },
                decidingQuestionRpcId = s.decidingQuestionRpcId.takeIf { it != ev.rpcId },
            )
        }
    }

    private fun handleDeviceRegistered(ev: ServerEvent.DeviceRegistered) {
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

    private fun handleDeviceRevoked(ev: ServerEvent.DeviceRevoked) {
        scope.launch { devices.removeByDeviceId(ev.deviceId) }
    }

    private fun handleError(ev: ServerEvent.Error) {
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
}

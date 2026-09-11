package com.daniel.dshremote

import com.daniel.dshremote.protocol.AgentSummary
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.ContextUsageWire
import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.QueueItemWire
import com.daniel.dshremote.protocol.QuestionRequestWire
import com.daniel.dshremote.protocol.SessionModelsWire
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.SkillWire
import com.daniel.dshremote.protocol.StoredDevice
import com.daniel.dshremote.protocol.WorkspaceSummary

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
    /**
     * 查看子代理会话时的多级返回链（栈底=根主会话，栈顶=立即父会话）。
     * 下钻 openSubagent 压栈、返回 closeSession 弹栈，逐级 C→B→A→主会话；空 = 无子代理打开。
     */
    val subagentReturnStack: List<String> = emptyList(),
    /** 子会话打开时，根主会话（subagentReturnStack 栈底）的 live 投影；平板中栏渲染，手机忽略。 */
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
    /** 当前会话模型目录快照（当前选择 + provider 分组 + routable；服务端投影权威，会话级切会话重置）。 */
    val models: SessionModelsWire? = null,
    /** 当前会话上下文窗口占用（percent 由服务端算好，客户端零推算；会话级切会话重置）。 */
    val contextUsage: ContextUsageWire? = null,
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
    /** 技能目录（全局、与会话无关）：bridge 连接即下发 skills_update + skills/change 时广播全量目录。 */
    val skills: List<SkillWire> = emptyList(),
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

    /** 取某会话的 live 投影：当前会话→内联字段；根主会话(子会话打开时)→parentView；其余→空。 */
    fun viewOf(sessionId: String): SessionViewState = when (sessionId) {
        currentSessionId -> currentView()
        subagentReturnStack.firstOrNull() -> parentView
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

    /**
     * 切换到指定会话：重置全部会话级状态（Deep Diving/思考流/诊断/目标/调试/队列/分页等），
     * 并携带子代理返回栈与父视图投影（BridgeClient 导航统一入口，切会话清栈由此保证）。
     */
    fun forSession(sessionId: String, subagentReturnStack: List<String>, parentView: SessionViewState): SessionUiState = copy(
        currentSessionId = sessionId,
        subagentReturnStack = subagentReturnStack,
        parentView = parentView,
        events = emptyList(),
        queueItems = emptyList(),
        pendingMessages = emptyList(),
        modelWaitingSince = null,
        divingTurnStart = null,
        deepDivingElapsed = null,
        todos = emptyList(),
        commands = emptyList(),
        models = null,
        contextUsage = null,
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
    subagentReturnStack = emptyList(),
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
    models = null,
    contextUsage = null,
    liveThink = null,
    goal = null,
    debug = null,
    debugOutput = emptyList(),
    debugVars = emptyMap(),
    diagnostics = emptyList(),
    skills = emptyList(),
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

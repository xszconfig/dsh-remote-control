package com.daniel.dshremote.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

// ---- 数据模型（对应 bridge 的 protocol.ts）----

@Serializable
data class SessionSummary(
    val id: String,
    /** 桌面端显示名称：持久化标题 → cwd basename → id。 */
    val name: String? = null,
    /** 所属工作区 id；null = 未分组。 */
    val workspaceId: String? = null,
    val cwd: String,
    val status: String,
    val agentCount: Int,
    val subagentCount: Int,
    val updatedAt: Long,
    /** 子代理会话所属的主会话 id；缺省 = 顶层（用户手动创建的）会话。 */
    val parentSessionId: String? = null,
)

@Serializable
data class WorkspaceSummary(
    val id: String,
    val title: String,
    val path: String,
    val sessionCount: Int,
)

/**
 * 会话/工作区元数据本地缓存快照：打开 App 先渲染，Hello 到达后做增量对账
 * （新增/更新/删除），不实时重拉。selectedWorkspaceId 一并持久化保留用户位置。
 */
@Serializable
data class CachedSessionSnapshot(
    val sessions: List<SessionSummary> = emptyList(),
    val workspaces: List<WorkspaceSummary> = emptyList(),
    val selectedWorkspaceId: String? = null,
    val savedAt: Long = 0L,
)

@Serializable
data class AgentSummary(
    val sessionId: String,
    val role: String,
    val status: String,
    val depth: Int,
)

@Serializable
data class EventProjection(
    val seq: Long,
    val type: String,
    val text: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val toolError: Boolean? = null,
    val timestamp: Long,
    /** 工具调用/结果关联 id：客户端据此把失败的命令标红。 */
    val callId: String? = null,
    /** 工具调用卡片形态（桌面端 presentCall 同源）：terminal=命令卡，generic/diff=通用卡。 */
    val toolCard: String? = null,
    /** 工具调用的一行描述（桌面端 ToolCallView.title；Bash 即命令文本）。 */
    val toolDesc: String? = null,
    /** 工具类别（read/edit/delete/move/search/execute/fetch/other），客户端选图标。 */
    val toolKind: String? = null,
    /** 文件变更 diff（桌面端 DiffCallView.diffs 同源）：展开工具卡时按行渲染红删绿增。 */
    val diffs: List<FileDiffWire>? = null,
)

@Serializable
data class FileDiffWire(
    val path: String,
    val oldText: String? = null,
    val newText: String,
)

@Serializable
data class ApprovalRequestWire(
    val approvalId: String,
    val sessionId: String,
    val toolName: String,
    /** 关联的工具调用 id（有则透传）。 */
    val callId: String? = null,
    /** 请求方的可读理由（主文案）。 */
    val reason: String? = null,
    /** 关联工具调用时提取的命令文本。 */
    val command: String? = null,
    val requestedAt: Long = 0,
    /**
     * 桌面端 apiproxy 持有时的 mux rpcId：裁决走 answer_approval；
     * 缺省 = bridge 持有，走 approve。
     */
    val rpcId: String? = null,
)

// ---- 提问（ask_user_question，桌面端持有，bridge 经 mux 转发）----

@Serializable
data class QuestionOptionWire(
    val label: String,
    val description: String? = null,
)

@Serializable
data class QuestionItemWire(
    val id: String,
    val question: String,
    val header: String? = null,
    val detail: String? = null,
    val options: List<QuestionOptionWire> = emptyList(),
    val multiSelect: Boolean = false,
)

@Serializable
data class QuestionRequestWire(
    val rpcId: String,
    val sessionId: String,
    val questions: List<QuestionItemWire>,
    val requestedAt: Long = 0,
)

@Serializable
data class QuestionAnswerItemWire(
    val id: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
)

/** 审批决策。wire 值与 bridge 协议一致："allowed-once" / "rejected"。 */
@Serializable(with = ApprovalDecision.Serializer::class)
enum class ApprovalDecision(val wire: String) {
    AllowedOnce("allowed-once"),
    Rejected("rejected"),
    ;

    companion object {
        fun fromWire(value: String): ApprovalDecision? = entries.firstOrNull { it.wire == value }
    }

    object Serializer : KSerializer<ApprovalDecision> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("ApprovalDecision", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: ApprovalDecision) = encoder.encodeString(value.wire)

        override fun deserialize(decoder: Decoder): ApprovalDecision {
            val value = decoder.decodeString()
            return fromWire(value) ?: throw SerializationException("未知审批决策: $value")
        }
    }
}

// ---- 设备 / 配对 ----

/** 手机可达的候选端点（多路由重连用）。 */
@Serializable
data class StoredEndpoint(
    val host: String,
    val port: Int,
)

/** 手机本地持久化的一台已连接桌面设备（含指纹 serverId）。 */
@Serializable
data class StoredDevice(
    /** 手机自身的设备 id（服务端按它签发长期 token）。 */
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String? = null,
    /** 桌面 DSH 的机器指纹。 */
    val serverId: String? = null,
    val hostname: String? = null,
    val createdAt: Long,
    val lastSeenAt: Long,
    /** 候选端点列表（主端点在前）；旧数据缺省时回退 host/port 单端点。 */
    val endpoints: List<StoredEndpoint> = emptyList(),
)

@Serializable
data class DeviceFile(
    val phoneId: String,
    val devices: List<StoredDevice> = emptyList(),
    /** 上次连接的设备 key（host_port）；冷启动自动连接候选。 */
    val lastConnectedKey: String? = null,
)

/** 设备列表里的在线状态（运行时计算，不持久化）。 */
enum class DeviceStatus { Checking, Online, Offline, Changed }

/** GET /remote/ping 响应。 */
@Serializable
data class PingInfo(
    val ok: Boolean = false,
    val version: String? = null,
    val serverId: String? = null,
    val hostname: String? = null,
    val sessions: Int = 0,
)

/** 二维码内容（/remote/pair 页面生成）。 */
@Serializable
data class PairQrPayload(
    val v: Int = 1,
    val t: String = "dsh-remote",
    val serverId: String? = null,
    val hostname: String? = null,
    val expiresAt: Long = 0,
    val urls: List<String> = emptyList(),
)

/** 服务端结构化日志条目（/remote/logs）。 */
@Serializable
data class ServerLogEntry(
    val seq: Long,
    val ts: Long,
    val level: String,
    val tag: String,
    val message: String,
)

@Serializable
data class ServerLogsResponse(
    val version: String = "",
    val entries: List<ServerLogEntry> = emptyList(),
)

/** 排队消息投影：placement = queued(下一轮)/steering(插队中)/context(系统注入)。 */
@Serializable
data class QueueItemWire(
    val id: String,
    val placement: String,
    val text: String,
)

/** 手机回传本地结构化连接日志（桌面端 /remote/phone-logs 拉取）。 */
@Serializable
data class LogEntryWire(
    val ts: Long,
    val level: String,
    val tag: String,
    val message: String,
)

// ---- 服务端事件（sealed 多态，type 字段判别）----

@Serializable
sealed interface ServerEvent {
    @Serializable
    @SerialName("hello")
    data class Hello(
        val version: String,
        val serverId: String? = null,
        val hostname: String? = null,
        val sessions: List<SessionSummary>,
        val agents: List<AgentSummary>,
        val workspaces: List<WorkspaceSummary> = emptyList(),
        /** 当前由 bridge 持有、等待手机裁决的审批（连接/重连时补发）。 */
        val pendingApprovals: List<ApprovalRequestWire> = emptyList(),
        /** 桌面端持有、bridge 经 mux 转发的审批（裁决走 answer_approval）。 */
        val pendingRemoteApprovals: List<ApprovalRequestWire> = emptyList(),
        /** 桌面端持有、bridge 经 mux 转发的提问（回答走 answer_question）。 */
        val pendingQuestions: List<QuestionRequestWire> = emptyList(),
    ) : ServerEvent

    @Serializable
    @SerialName("history")
    data class History(
        val sessionId: String,
        val events: List<EventProjection>,
        /** 该会话当前排队的消息；缺省 = 无排队。 */
        val queue: List<QueueItemWire> = emptyList(),
        /** 是否还有更早的历史可翻页（history_page 继续加载）。 */
        val hasMore: Boolean = false,
        /** 会话事件总数（展示用）。 */
        val total: Int = 0,
        /** 该会话正在进行的模型请求开始时间（null = 未在等待）；切会话后指示条不串扰。 */
        val modelWaitingSince: Long? = null,
        /** 该会话当前持久化目标（null = 无目标）；会话级状态，切换会话不串扰。 */
        val goal: GoalWire? = null,
        /** 该会话的任务列表（订阅响应携带；null = 无/旧版 bridge）。 */
        val todos: List<TodoWire>? = null,
    ) : ServerEvent

    @Serializable
    @SerialName("event")
    data class Event(val sessionId: String, val event: EventProjection) : ServerEvent

    @Serializable
    @SerialName("agent_status")
    data class AgentStatus(val sessionId: String, val status: String) : ServerEvent

    @Serializable
    @SerialName("session_title")
    data class SessionTitle(val sessionId: String, val title: String) : ServerEvent

    @Serializable
    @SerialName("session_queue")
    data class SessionQueue(val sessionId: String, val items: List<QueueItemWire>) : ServerEvent

    /** 桌面端请求手机回传本地日志（/remote/phone-logs 触发）。 */
    @Serializable
    @SerialName("logs_request")
    data class LogsRequest(val requestId: String) : ServerEvent

    /** 该会话的模型请求开始（Deep Diving 指示）。 */
    @Serializable
    @SerialName("model_waiting")
    data class ModelWaiting(val sessionId: String, val startedAt: Long) : ServerEvent

    /** 该会话的模型请求完成。 */
    @Serializable
    @SerialName("model_waiting_done")
    data class ModelWaitingDone(val sessionId: String, val startedAt: Long, val elapsedMs: Long) : ServerEvent

    /** Deep Diving 已等待时长推送（服务端时钟秒数，每秒广播；客户端不再本地计时）。 */
    @Serializable
    @SerialName("deep_diving_tick")
    data class DeepDivingTick(val sessionId: String, val elapsedSeconds: Long, val since: Long) : ServerEvent

    /** 思考流式增量（空 text = 清除实时思考行）。 */
    @Serializable
    @SerialName("think_delta")
    data class ThinkDelta(val sessionId: String, val text: String) : ServerEvent

    /** LSP 诊断推送：某文件的最新诊断集合（空 = 该文件已无问题）。 */
    @Serializable
    @SerialName("diagnostics")
    data class Diagnostics(val sessionId: String, val path: String, val diagnostics: List<DiagnosticWire>) : ServerEvent

    /** 会话持久化目标（goal/change 事件投影）。 */
    @Serializable
    data class GoalWire(
        val objective: String,
        val phase: String, // active / paused / blocked / complete
        val blockedCode: String? = null,
        val blockedMessage: String? = null,
        val maxGoalRounds: Int = 0,
        val roundsStarted: Int = 0,
        val updatedAt: Long = 0,
    )

    /** 目标变更推送（goal = null 表示已清除）。会话级，按 sessionId 隔离。 */
    @Serializable
    @SerialName("goal_update")
    data class GoalUpdate(val sessionId: String, val goal: GoalWire? = null) : ServerEvent

    /** 会话任务列表条目（DSH todo_write 清单投影）。 */
    @Serializable
    data class TodoWire(
        val content: String,
        val status: String, // pending / in_progress / completed
    )

    /** 会话任务列表推送（每会话一份）。会话级，按 sessionId 隔离。 */
    @Serializable
    @SerialName("todos_update")
    data class TodosUpdate(val sessionId: String, val todos: List<TodoWire> = emptyList()) : ServerEvent

    /** 调试断点（1-based 行号）。 */
    @Serializable
    data class DebugBreakpointWire(val path: String, val line: Int)

    @Serializable
    data class DebugScopeWire(val name: String, val variablesReference: String)

    @Serializable
    data class DebugFrameWire(
        val id: String,
        val name: String,
        val path: String,
        val line: Int,
        val scopes: List<DebugScopeWire> = emptyList(),
    )

    @Serializable
    data class DebugVariableWire(
        val name: String,
        val value: String,
        val type: String? = null,
        val hasChildren: Boolean = false,
        val variablesReference: String = "",
    )

    @Serializable
    data class DebugStoppedAt(val path: String, val line: Int)

    @Serializable
    data class DebugPausedWire(
        val reason: String,
        val stoppedAt: DebugStoppedAt? = null,
        val frames: List<DebugFrameWire> = emptyList(),
    )

    /** 调试会话状态快照（服务端投影为准，单向流）。 */
    @Serializable
    data class DebugStateWire(
        val state: String, // starting / running / paused / stopped
        val program: String,
        val cwd: String = "",
        val breakpoints: List<DebugBreakpointWire> = emptyList(),
        val paused: DebugPausedWire? = null,
        val error: String? = null,
    )

    @Serializable
    @SerialName("debug_state")
    data class DebugState(val sessionId: String, val debug: DebugStateWire) : ServerEvent

    @Serializable
    @SerialName("debug_output")
    data class DebugOutput(val sessionId: String, val line: String) : ServerEvent

    @Serializable
    @SerialName("debug_variables")
    data class DebugVariables(
        val sessionId: String,
        val variablesReference: String,
        val variables: List<DebugVariableWire> = emptyList(),
    ) : ServerEvent

    @Serializable
    data class DiagnosticWire(
        val path: String,
        val line: Int,
        val column: Int,
        val endLine: Int? = null,
        val endColumn: Int? = null,
        val severity: Int, // 1=error 2=warning 3=info 4=hint
        val message: String,
        val source: String? = null,
    )

    /** 服务端重启通知：重连后推送（版本 + 启动时间 + 新增功能说明）。 */
    @Serializable
    @SerialName("server_boot")
    data class ServerBoot(val version: String, val bootedAt: Long, val notes: List<String> = emptyList()) : ServerEvent

    /** 会话列表增量：新建/下线的会话行（hello 全量对账兜底）。 */
    @Serializable
    @SerialName("session_upsert")
    data class SessionUpsert(val session: SessionSummary) : ServerEvent

    @Serializable
    @SerialName("approval_request")
    data class ApprovalRequest(val approval: ApprovalRequestWire) : ServerEvent

    @Serializable
    @SerialName("approval_resolved")
    data class ApprovalResolved(
        val approvalId: String,
        val sessionId: String,
        val outcome: String,
    ) : ServerEvent

    /** 旧版 bridge（0.3.0）的事件名，保留以兼容混版本窗口。 */
    @Serializable
    @SerialName("approval_settled")
    data class ApprovalSettledLegacy(val approvalId: String, val outcome: String) : ServerEvent

    @Serializable
    @SerialName("question_request")
    data class QuestionRequest(val question: QuestionRequestWire) : ServerEvent

    @Serializable
    @SerialName("question_resolved")
    data class QuestionResolved(val rpcId: String, val sessionId: String, val outcome: String) : ServerEvent

    @Serializable
    @SerialName("device_registered")
    data class DeviceRegistered(
        val deviceId: String,
        val deviceToken: String,
        val serverId: String,
        val hostname: String,
        /** 桌面端下发的候选端点（127.0.0.1 + 局域网/Tailscale IP）。 */
        val endpoints: List<StoredEndpoint> = emptyList(),
    ) : ServerEvent

    @Serializable
    @SerialName("device_revoked")
    data class DeviceRevoked(val deviceId: String) : ServerEvent

    @Serializable
    @SerialName("error")
    data class Error(val code: String, val message: String) : ServerEvent
}

// ---- 客户端命令 ----

@Serializable
sealed interface ClientCommand {
    @Serializable
    @SerialName("list")
    data object List : ClientCommand

    @Serializable
    @SerialName("subscribe")
    data class Subscribe(val sessionId: String?) : ClientCommand

    @Serializable
    @SerialName("send_message")
    data class SendMessage(val sessionId: String, val text: String) : ClientCommand

    /** 历史分页：拉取 seq < beforeSeq 的最近一页（最多 limit 条）。 */
    @Serializable
    @SerialName("history_page")
    data class HistoryPage(val sessionId: String, val beforeSeq: Long, val limit: Int = 300) : ClientCommand

    @Serializable
    @SerialName("interrupt")
    data class Interrupt(val sessionId: String) : ClientCommand

    /** 排队消息操作：steer = 插队（作为 steering 注入当前轮）；remove = 移除。 */
    @Serializable
    @SerialName("queue_action")
    data class QueueAction(val sessionId: String, val itemId: String, val action: String) : ClientCommand

    /** 调试控制：resume / step / step_out / stop / variables（按引用拉变量）。 */
    @Serializable
    @SerialName("debug_command")
    data class DebugCommand(val sessionId: String, val action: String, val variablesReference: String? = null) : ClientCommand

    @Serializable
    @SerialName("upload_logs")
    data class UploadLogs(val requestId: String, val entries: kotlin.collections.List<LogEntryWire>) : ClientCommand

    @Serializable
    @SerialName("approve")
    data class Approve(val approvalId: String, val decision: ApprovalDecision) : ClientCommand

    @Serializable
    @SerialName("answer_approval")
    data class AnswerApproval(
        val rpcId: String,
        val sessionId: String,
        val approvalId: String,
        val decision: ApprovalDecision,
    ) : ClientCommand

    @Serializable
    @SerialName("answer_question")
    data class AnswerQuestion(
        val rpcId: String,
        val sessionId: String,
        val answers: kotlin.collections.List<QuestionAnswerItemWire>,
    ) : ClientCommand

    @Serializable
    @SerialName("register_device")
    data class RegisterDevice(val deviceId: String, val name: String, val model: String? = null) : ClientCommand

    @Serializable
    @SerialName("revoke_device")
    data class RevokeDevice(val deviceId: String) : ClientCommand
}

val BridgeJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    encodeDefaults = true
}

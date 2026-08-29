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
)

@Serializable
data class WorkspaceSummary(
    val id: String,
    val title: String,
    val path: String,
    val sessionCount: Int,
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
)

@Serializable
data class DeviceFile(
    val phoneId: String,
    val devices: List<StoredDevice> = emptyList(),
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
    data class History(val sessionId: String, val events: List<EventProjection>) : ServerEvent

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

    @Serializable
    @SerialName("interrupt")
    data class Interrupt(val sessionId: String) : ClientCommand

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

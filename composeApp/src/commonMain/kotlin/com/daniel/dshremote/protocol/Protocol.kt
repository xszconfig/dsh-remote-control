package com.daniel.dshremote.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    val reason: String? = null,
)

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
    @SerialName("approval_settled")
    data class ApprovalSettled(val approvalId: String, val outcome: String) : ServerEvent

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
    data class Approve(val approvalId: String, val decision: String) : ClientCommand

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

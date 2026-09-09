package com.daniel.dshremote

import com.daniel.dshremote.protocol.BridgeJson
import com.daniel.dshremote.protocol.ClientCommand
import com.daniel.dshremote.protocol.ServerEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ConnectionState { Disconnected, Connecting, Connected, Reconnecting, Error }

/** 命令的 wire 类型名（日志/诊断用）。 */
val ClientCommand.typeName: String
    get() = when (this) {
        ClientCommand.List -> "list"
        ClientCommand.Ping -> "ping"
        is ClientCommand.Subscribe -> "subscribe"
        is ClientCommand.SendMessage -> "send_message"
        is ClientCommand.SetModel -> "set_model"
        is ClientCommand.HistoryPage -> "history_page"
        is ClientCommand.Interrupt -> "interrupt"
        is ClientCommand.Approve -> "approve"
        is ClientCommand.AnswerApproval -> "answer_approval"
        is ClientCommand.AnswerQuestion -> "answer_question"
        is ClientCommand.ConfirmDelivery -> "confirm_delivery"
        is ClientCommand.QueueAction -> "queue_action"
        is ClientCommand.DebugCommand -> "debug_command"
        is ClientCommand.UploadLogs -> "upload_logs"
        is ClientCommand.RegisterDevice -> "register_device"
        is ClientCommand.RevokeDevice -> "revoke_device"
    }

/** 连接面状态：生命周期 + 给用户看的细节（正在尝试的地址/失败原因）。 */
data class ConnectionInfo(
    val state: ConnectionState = ConnectionState.Disconnected,
    val detail: String = "",
)

/** 重连退避：第 1 次等 1s，之后翻倍，封顶 30s（attempt 从 1 起）。 */
fun reconnectDelayMs(attempt: Int): Long =
    minOf(1_000L shl (attempt - 1).coerceIn(0, 5), 30_000L)

/**
 * 应用层判活心跳（假连接秒级判死）：
 * 客户端每 [PING_INTERVAL_MS] 发一条 [ClientCommand.Ping]（JSON `{"type":"ping"}`），桥回 `{"type":"pong"}`；
 * 若上一轮 ping 后 [PONG_TIMEOUT_MS] 内没收到 pong → 判定假连接，主动关闭触发重连。
 * wire 与 bridge 0.14.0 / mock-bridge 对齐。
 */
const val PING_INTERVAL_MS: Long = 20_000L
const val PONG_TIMEOUT_MS: Long = 10_000L

/**
 * 判定是否因 pong 超时应判死：上一轮 ping（lastPingAt）之后超过 [PONG_TIMEOUT_MS]
 * 仍未收到 pong（lastPongAt 未更新）。纯函数，commonTest 直测。
 */
fun pongTimedOut(lastPingAt: Long, lastPongAt: Long, now: Long, timeoutMs: Long = PONG_TIMEOUT_MS): Boolean =
    lastPingAt > lastPongAt && now - lastPingAt > timeoutMs

/**
 * 单条 WebSocket 连接的生命周期管理：握手、凭证头注入、事件解码、发送。
 * 不做策略（候选地址回退、自动重连等归 BridgeClient 编排）。
 *
 * 事件经 [events] 流出，状态经 [info] 暴露，本类不持有任何业务状态。
 */
class ConnectionManager(private val scope: CoroutineScope) {

    private val wsClient: HttpClient = createWsHttp()
    private var ws: DefaultClientWebSocketSession? = null
    private val openMutex = Mutex()

    private val _info = MutableStateFlow(ConnectionInfo())
    val info: StateFlow<ConnectionInfo> = _info.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    /** 当前连接的 URL（DeviceRegistered 解析 host/port 用）；未连接为 null。 */
    var currentUrl: String? = null
        private set

    /** 应用层判活：上轮 ping 发送时刻（0 = 未发过）；pong 到达时刻（0 = 未收到）。 */
    private var lastPingAt = 0L
    private var lastPongAt = 0L

    /**
     * 打开一条连接并阻塞处理事件流，直到连接关闭后返回。
     * 互斥串行化：上一条连接（含取消清理）完全退出后才允许下一条开始，
     * 防止重连循环与手动连接并发 open 互相踩 ws/currentUrl 状态。
     * @return true 表示握手成功过（建立后被关闭）；false 表示握手失败。
     */
    suspend fun open(url: String, token: String?): Boolean = openMutex.withLock {
        currentUrl = url
        var established = false
        ConnLog.info("CONNECT", "开始握手 $url${if (token != null) "（带凭证）" else "（无凭证）"}")
        _info.update { ConnectionInfo(ConnectionState.Connecting, url.removePrefix("ws://").removePrefix("wss://")) }
        try {
            wsClient.webSocket(
                urlString = url,
                request = {
                    Pairing.authHeader(token)?.let { headers.append("Authorization", it) }
                },
            ) {
                established = true
                ws = this
                lastPingAt = 0L
                lastPongAt = nowMillis()
                ConnLog.info("CONNECT", "握手成功，连接已建立")
                _info.update { ConnectionInfo(ConnectionState.Connected) }
                coroutineScope {
                    val heartbeat = launch { heartbeatLoop() }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                try {
                                    val ev = BridgeJson.decodeFromString(ServerEvent.serializer(), frame.readText())
                                    // 应用层 pong：判活信号，ConnectionManager 内部消费，不下发业务层
                                    if (ev is ServerEvent.Pong) {
                                        lastPongAt = nowMillis()
                                        ConnLog.debug("CONNECT", "收到应用层 pong")
                                        continue
                                    }
                                    _events.emit(ev)
                                } catch (e: Exception) {
                                    ConnLog.warn("WS", "事件解码失败（跳过该帧）: ${e.message}")
                                }
                            }
                        }
                    } finally {
                        heartbeat.cancel()
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!established) {
                ConnLog.warn("CONNECT", "握手失败: ${e.message}")
                _info.update { ConnectionInfo(ConnectionState.Error, e.message ?: "connection failed") }
            } else {
                ConnLog.warn("CONNECT", "连接异常中断: ${e.message}")
            }
        } finally {
            ws = null
            currentUrl = null
            if (established) {
                ConnLog.info("CONNECT", "连接关闭")
                _info.update { ConnectionInfo(ConnectionState.Disconnected) }
            }
        }
        established
    }

    /** 应用层判活心跳：每 [PING_INTERVAL_MS] 发 ping；上轮 ping 后 [PONG_TIMEOUT_MS] 无 pong → 判死主动关闭。 */
    private suspend fun heartbeatLoop() {
        while (currentCoroutineContext().isActive) {
            delay(PING_INTERVAL_MS)
            val s = ws ?: break
            val now = nowMillis()
            if (pongTimedOut(lastPingAt, lastPongAt, now)) {
                ConnLog.warn("CONNECT", "应用层 pong 超时 ${now - lastPingAt}ms，判定假连接，主动关闭触发重连")
                try {
                    s.close()
                } catch (_: Exception) {
                    // 关闭竞态忽略
                }
                break
            }
            lastPingAt = now
            try {
                s.send(Frame.Text(BridgeJson.encodeToString(ClientCommand.serializer(), ClientCommand.Ping)))
                ConnLog.debug("CONNECT", "发送应用层 ping")
            } catch (_: Exception) {
                break
            }
        }
    }

    /** 编排层用来上报「策略级」失败（如二维码无候选地址、全部候选失败）。 */
    fun fail(detail: String) {
        _info.update { ConnectionInfo(ConnectionState.Error, detail) }
    }

    /** 编排层标记进入自动重连等待（UI 据此保留会话数据并显示横幅）。 */
    fun markReconnecting(detail: String) {
        _info.update { ConnectionInfo(ConnectionState.Reconnecting, detail) }
    }

    /** 编排层标记回到未连接（重连取消/放弃时）。 */
    fun markDisconnected() {
        _info.update { ConnectionInfo(ConnectionState.Disconnected) }
    }

    /** 发送一条命令；未连接或通道已关时返回 false（不抛异常、不静默成功）。 */
    suspend fun send(cmd: ClientCommand): Boolean {
        val session = ws ?: run {
            ConnLog.warn("CMD", "发送 ${cmd.typeName} 失败：未连接")
            return false
        }
        return try {
            session.send(Frame.Text(BridgeJson.encodeToString(ClientCommand.serializer(), cmd)))
            ConnLog.debug("CMD", "已发送 ${cmd.typeName}")
            true
        } catch (e: Exception) {
            ConnLog.warn("CMD", "发送 ${cmd.typeName} 异常: ${e.message}")
            false
        }
    }

    /** 关闭当前连接（若无则无操作）。 */
    fun close() {
        val session = ws ?: return
        ConnLog.info("CONNECT", "主动关闭连接")
        scope.launch {
            try {
                session.close()
            } catch (_: Exception) {
                // 已在关闭流程中，忽略
            }
        }
    }
}

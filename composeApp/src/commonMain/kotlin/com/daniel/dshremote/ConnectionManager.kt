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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ConnectionState { Disconnected, Connecting, Connected, Error }

/** 连接面状态：生命周期 + 给用户看的细节（正在尝试的地址/失败原因）。 */
data class ConnectionInfo(
    val state: ConnectionState = ConnectionState.Disconnected,
    val detail: String = "",
)

/**
 * 单条 WebSocket 连接的生命周期管理：握手、凭证头注入、事件解码、发送。
 * 不做策略（候选地址回退、自动重连等归 BridgeClient 编排）。
 *
 * 事件经 [events] 流出，状态经 [info] 暴露，本类不持有任何业务状态。
 */
class ConnectionManager(private val scope: CoroutineScope) {

    private val wsClient: HttpClient = createWsHttp()
    private var ws: DefaultClientWebSocketSession? = null

    private val _info = MutableStateFlow(ConnectionInfo())
    val info: StateFlow<ConnectionInfo> = _info.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    /** 当前连接的 URL（DeviceRegistered 解析 host/port 用）；未连接为 null。 */
    var currentUrl: String? = null
        private set

    /**
     * 打开一条连接并阻塞处理事件流，直到连接关闭后返回。
     * @return true 表示握手成功过（建立后被关闭）；false 表示握手失败。
     */
    suspend fun open(url: String, token: String?): Boolean {
        currentUrl = url
        var established = false
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
                _info.update { ConnectionInfo(ConnectionState.Connected) }
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val ev = BridgeJson.decodeFromString(ServerEvent.serializer(), frame.readText())
                        _events.emit(ev)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!established) {
                _info.update { ConnectionInfo(ConnectionState.Error, e.message ?: "connection failed") }
            }
        } finally {
            ws = null
            currentUrl = null
            if (established) {
                _info.update { ConnectionInfo(ConnectionState.Disconnected) }
            }
        }
        return established
    }

    /** 编排层用来上报「策略级」失败（如二维码无候选地址、全部候选失败）。 */
    fun fail(detail: String) {
        _info.update { ConnectionInfo(ConnectionState.Error, detail) }
    }

    /** 发送一条命令；未连接或通道已关时返回 false（不抛异常、不静默成功）。 */
    suspend fun send(cmd: ClientCommand): Boolean {
        val session = ws ?: return false
        return try {
            session.send(Frame.Text(BridgeJson.encodeToString(ClientCommand.serializer(), cmd)))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 关闭当前连接（若无则无操作）。 */
    fun close() {
        val session = ws ?: return
        scope.launch {
            try {
                session.close()
            } catch (_: Exception) {
                // 已在关闭流程中，忽略
            }
        }
    }
}

package com.daniel.dshremote

import com.daniel.dshremote.protocol.BridgeJson
import com.daniel.dshremote.protocol.PairQrPayload

/** 从 ws://host:port/... URL 解出的连接端点。 */
data class Endpoint(val host: String, val port: Int, val token: String?)

object Pairing {
    /** 扫码结果解析：支持 bridge 配对 JSON、裸 ws(s):// 地址、http(s):// 自动升级为 ws；无法识别返回空表。 */
    fun parseQr(text: String): List<String> {
        val t = text.trim()
        if (t.startsWith("{")) {
            return try {
                val p = BridgeJson.decodeFromString(PairQrPayload.serializer(), t)
                if (p.t == "dsh-remote") p.urls else emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
        return when {
            t.startsWith("ws://") || t.startsWith("wss://") -> listOf(t)
            t.startsWith("http://") || t.startsWith("https://") ->
                listOf(t.replaceFirst("http", "ws"))
            else -> emptyList()
        }
    }

    /** 用存储的设备地址构造 WS 地址。凭证不进 URL（避免明文出现在日志/代理里），改走 Authorization 头。 */
    fun buildUrl(host: String, port: Int): String = "ws://$host:$port/remote/ws"

    /** 长期 token → Authorization 请求头的值；token 为空返回 null。 */
    fun authHeader(token: String?): String? =
        token?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    /** 解析 ws(s)://host:port/path?query 端点；端口缺省 3080，token 取 pair/token 参数。 */
    fun endpointOf(url: String): Endpoint {
        val bare = url.removePrefix("ws://").removePrefix("wss://")
        val hostPort = bare.substringBefore('/')
        val query = bare.substringAfter('?', missingDelimiterValue = "")
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', missingDelimiterValue = "").toIntOrNull() ?: 3080
        val token = query.split('&')
            .firstOrNull { it.startsWith("pair=") || it.startsWith("token=") }
            ?.substringAfter('=')
        return Endpoint(host, port, token)
    }
}

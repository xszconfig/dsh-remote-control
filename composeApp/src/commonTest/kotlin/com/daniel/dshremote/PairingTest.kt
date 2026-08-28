package com.daniel.dshremote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingTest {

    // ---- parseQr ----

    @Test
    fun parseQr_bridgeJsonPayload() {
        val qr = """{"v":1,"t":"dsh-remote","serverId":"srv","hostname":"mac",
            "urls":["ws://127.0.0.1:3080/remote/ws?pair=abc","ws://192.168.1.5:3080/remote/ws?pair=abc"]}"""
        val urls = Pairing.parseQr(qr)
        assertEquals(2, urls.size)
        assertTrue(urls.all { it.endsWith("pair=abc") })
    }

    @Test
    fun parseQr_rejectsJsonWithWrongType() {
        // t 不是 dsh-remote 的 JSON（比如别的 App 的二维码）不能连
        assertEquals(emptyList(), Pairing.parseQr("""{"v":1,"t":"other-app","urls":["ws://1.2.3.4:1/x"]}"""))
    }

    @Test
    fun parseQr_rejectsBrokenJson() {
        assertEquals(emptyList(), Pairing.parseQr("""{"v":1,"t":"dsh-remote"""))
    }

    @Test
    fun parseQr_bareWsUrl() {
        assertEquals(listOf("ws://10.0.0.5:3080/remote/ws"), Pairing.parseQr("ws://10.0.0.5:3080/remote/ws"))
        assertEquals(listOf("wss://t.example.com/remote/ws?token=xyz"), Pairing.parseQr("wss://t.example.com/remote/ws?token=xyz"))
    }

    @Test
    fun parseQr_httpUpgradesToWs() {
        // 排障库记录的隧道场景：二维码里是 https 地址
        assertEquals(listOf("wss://t.example.com/remote/ws?token=xyz"), Pairing.parseQr("https://t.example.com/remote/ws?token=xyz"))
        assertEquals(listOf("ws://h/remote/ws"), Pairing.parseQr("http://h/remote/ws"))
    }

    @Test
    fun parseQr_plainTextRejected() {
        assertEquals(emptyList(), Pairing.parseQr("随便一段文字"))
        assertEquals(emptyList(), Pairing.parseQr(""))
        assertEquals(emptyList(), Pairing.parseQr("  \n "))
    }

    // ---- buildUrl ----

    @Test
    fun buildUrl_withAndWithoutToken() {
        assertEquals("ws://192.168.1.5:3080/remote/ws?token=t1", Pairing.buildUrl("192.168.1.5", 3080, "t1"))
        assertEquals("ws://192.168.1.5:3080/remote/ws", Pairing.buildUrl("192.168.1.5", 3080, null))
        assertEquals("ws://192.168.1.5:3080/remote/ws", Pairing.buildUrl("192.168.1.5", 3080, ""))
    }

    // ---- endpointOf ----

    @Test
    fun endpointOf_fullUrl() {
        val ep = Pairing.endpointOf("ws://192.168.1.5:3080/remote/ws?pair=abc")
        assertEquals("192.168.1.5", ep.host)
        assertEquals(3080, ep.port)
        assertEquals("abc", ep.token)
    }

    @Test
    fun endpointOf_tokenParam() {
        val ep = Pairing.endpointOf("ws://127.0.0.1:3080/remote/ws?token=t1&other=1")
        assertEquals("t1", ep.token)
    }

    @Test
    fun endpointOf_defaultsAndEdgeCases() {
        // 无端口 → 默认 3080；无 query → token null
        Pairing.endpointOf("ws://127.0.0.1/remote/ws").let {
            assertEquals("127.0.0.1", it.host)
            assertEquals(3080, it.port)
            assertEquals(null, it.token)
        }
        // 非数字端口 → 默认 3080（不崩溃）
        assertEquals(3080, Pairing.endpointOf("ws://h:abc/x").port)
        // 域名 + 端口
        Pairing.endpointOf("wss://t.example.com:443/remote/ws").let {
            assertEquals("t.example.com", it.host)
            assertEquals(443, it.port)
        }
    }

    @Test
    fun endpointOf_buildUrl_roundTrip() {
        val url = Pairing.buildUrl("192.168.1.5", 3080, "secret")
        val ep = Pairing.endpointOf(url)
        assertEquals("192.168.1.5", ep.host)
        assertEquals(3080, ep.port)
        assertEquals("secret", ep.token)
    }
}

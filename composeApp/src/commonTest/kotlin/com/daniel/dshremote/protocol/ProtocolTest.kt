package com.daniel.dshremote.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {

    // ---- 服务端事件解码 ----

    @Test
    fun decode_hello() {
        val ev = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"hello","version":"1.2.0","serverId":"srv-1","hostname":"mac",
               "sessions":[{"id":"s1","name":"重构","workspaceId":"w1","cwd":"/a/b","status":"idle",
                            "agentCount":1,"subagentCount":0,"updatedAt":100}],
               "agents":[{"sessionId":"s1","role":"main","status":"idle","depth":0}],
               "workspaces":[{"id":"w1","title":"W","path":"/a","sessionCount":1}],
               "futureField":123}""",
        )
        val hello = assertIs<ServerEvent.Hello>(ev)
        assertEquals("1.2.0", hello.version)
        assertEquals("srv-1", hello.serverId)
        assertEquals(1, hello.sessions.size)
        assertEquals("重构", hello.sessions[0].name)
        assertEquals("w1", hello.sessions[0].workspaceId)
        assertEquals(1, hello.workspaces.size)
    }

    @Test
    fun decode_hello_minimalFields_ok() {
        // 旧版 bridge：无 serverId/hostname/workspaces 字段 → 走默认值，不报错
        val ev = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"hello","version":"0.1","sessions":[],"agents":[]}""",
        )
        val hello = assertIs<ServerEvent.Hello>(ev)
        assertNull(hello.serverId)
        assertNull(hello.hostname)
        assertEquals(emptyList(), hello.workspaces)
    }

    @Test
    fun decode_historyAndEvent() {
        val history = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"history","sessionId":"s1","events":[
                {"seq":1,"type":"user_message","text":"hi","timestamp":1},
                {"seq":2,"type":"tool_call","toolName":"bash","toolArgs":"{\"cmd\":true}","timestamp":2},
                {"seq":3,"type":"tool_result","toolResult":"ok","toolError":false,"timestamp":3}]}""",
        )
        val h = assertIs<ServerEvent.History>(history)
        assertEquals(3, h.events.size)
        assertEquals("user_message", h.events[0].type)
        assertNull(h.events[0].toolName)
        assertEquals("bash", h.events[1].toolName)
        assertEquals(false, h.events[2].toolError)

        val ev = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"event","sessionId":"s1","event":{"seq":4,"type":"assistant_message","text":"done","timestamp":4}}""",
        )
        val e = assertIs<ServerEvent.Event>(ev)
        assertEquals(4, e.event.seq)
    }

    @Test
    fun decode_approvalEvents() {
        val req = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"approval_request","approval":{"approvalId":"a1","sessionId":"s1","toolName":"bash","reason":"危险命令"}}""",
        )
        val r = assertIs<ServerEvent.ApprovalRequest>(req)
        assertEquals("a1", r.approval.approvalId)
        assertEquals("危险命令", r.approval.reason)

        val settled = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"approval_settled","approvalId":"a1","outcome":"allowed-once"}""",
        )
        val s = assertIs<ServerEvent.ApprovalSettled>(settled)
        assertEquals("allowed-once", s.outcome)
    }

    @Test
    fun decode_deviceAndErrorEvents() {
        val registered = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"device_registered","deviceId":"d1","deviceToken":"t1","serverId":"srv","hostname":"mac"}""",
        )
        assertEquals("t1", assertIs<ServerEvent.DeviceRegistered>(registered).deviceToken)

        val revoked = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"device_revoked","deviceId":"d1"}""",
        )
        assertEquals("d1", assertIs<ServerEvent.DeviceRevoked>(revoked).deviceId)

        val error = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"error","code":"auth","message":"token 失效"}""",
        )
        assertEquals("auth", assertIs<ServerEvent.Error>(error).code)
    }

    // ---- 客户端命令编码 ----

    @Test
    fun encode_approve_wireIsUnchanged() {
        // wire 必须与 bridge 协议完全一致：decision 是 "allowed-once" 而非枚举名
        val json = BridgeJson.encodeToString(
            ClientCommand.serializer(),
            ClientCommand.Approve("a1", ApprovalDecision.AllowedOnce),
        )
        assertEquals("""{"type":"approve","approvalId":"a1","decision":"allowed-once"}""", json)

        val reject = BridgeJson.encodeToString(
            ClientCommand.serializer(),
            ClientCommand.Approve("a2", ApprovalDecision.Rejected),
        )
        assertEquals("""{"type":"approve","approvalId":"a2","decision":"rejected"}""", reject)
    }

    @Test
    fun encode_otherCommands() {
        assertEquals("""{"type":"list"}""", BridgeJson.encodeToString(ClientCommand.serializer(), ClientCommand.List))
        assertEquals(
            """{"type":"subscribe","sessionId":null}""",
            BridgeJson.encodeToString(ClientCommand.serializer(), ClientCommand.Subscribe(null)),
        )
        assertEquals(
            """{"type":"send_message","sessionId":"s1","text":"你好"}""",
            BridgeJson.encodeToString(ClientCommand.serializer(), ClientCommand.SendMessage("s1", "你好")),
        )
        assertEquals(
            """{"type":"register_device","deviceId":"d1","name":"N","model":null}""",
            BridgeJson.encodeToString(ClientCommand.serializer(), ClientCommand.RegisterDevice("d1", "N")),
        )
    }

    // ---- 审批决策枚举 ----

    @Test
    fun approvalDecision_wireRoundTrip() {
        for (d in ApprovalDecision.entries) {
            assertEquals(d, ApprovalDecision.fromWire(d.wire))
        }
        assertNull(ApprovalDecision.fromWire("allowed-forever"))
        assertNull(ApprovalDecision.fromWire("ALLOWED-ONCE")) // 大小写敏感
    }

    @Test
    fun approvalDecision_unknownWireRejected() {
        assertFailsWith<Exception> {
            BridgeJson.decodeFromString(
                ClientCommand.serializer(),
                """{"type":"approve","approvalId":"a1","decision":"yolo"}""",
            )
        }
    }

    // ---- 配对二维码 ----

    @Test
    fun decode_pairQrPayload() {
        val p = BridgeJson.decodeFromString(
            PairQrPayload.serializer(),
            """{"v":1,"t":"dsh-remote","serverId":"srv","hostname":"mac","expiresAt":123,
               "urls":["ws://127.0.0.1:3080/remote/ws?pair=abc","ws://192.168.1.5:3080/remote/ws?pair=abc"]}""",
        )
        assertEquals(2, p.urls.size)
        assertTrue(p.urls[0].contains("pair=abc"))
    }
}

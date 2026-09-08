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
    fun decode_hello_lspAndWork() {
        // 对齐 TS EvHello 的 lsp/work 载荷（0.13.0 新增）：非空解析；旧版缺省 null
        val ev = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"hello","version":"0.13.0","sessions":[],"agents":[],
               "lsp":{"languages":["typescript","kotlin"]},
               "work":{"activity":"重构 bridge","pending":["补契约测试"]}}""",
        )
        val hello = assertIs<ServerEvent.Hello>(ev)
        assertEquals(listOf("typescript", "kotlin"), hello.lsp?.languages)
        assertEquals("重构 bridge", hello.work?.activity)
        assertEquals(listOf("补契约测试"), hello.work?.pending)
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
        val s = assertIs<ServerEvent.ApprovalSettledLegacy>(settled)
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

        // error 可回带 msgId：精确关联回 pending
        val errorWithMsg = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"error","code":"not_running","message":"未运行","msgId":"m-1"}""",
        )
        assertEquals("m-1", assertIs<ServerEvent.Error>(errorWithMsg).msgId)
    }

    @Test
    fun decode_ack() {
        val ok = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"ack","msgId":"m-1","ok":true}""",
        )
        val okAck = assertIs<ServerEvent.Ack>(ok)
        assertEquals("m-1", okAck.msgId)
        assertEquals(true, okAck.ok)

        val reject = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"ack","msgId":"m-1","ok":false}""",
        )
        assertEquals(false, assertIs<ServerEvent.Ack>(reject).ok)
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
            """{"type":"send_message","sessionId":"s1","text":"你好","msgId":"m-1"}""",
            BridgeJson.encodeToString(ClientCommand.serializer(), ClientCommand.SendMessage("s1", "你好", "m-1")),
        )
        assertEquals(
            """{"type":"register_device","deviceId":"d1","name":"N","model":null}""",
            BridgeJson.encodeToString(ClientCommand.serializer(), ClientCommand.RegisterDevice("d1", "N")),
        )
    }

    @Test
    fun encode_interrupt_mode() {
        // 缺省 mode = clear（旧行为）；encodeDefaults=true 会把缺省值也显式编码
        assertEquals(
            """{"type":"interrupt","sessionId":"s1","mode":"clear"}""",
            BridgeJson.encodeToString(ClientCommand.serializer(), ClientCommand.Interrupt("s1")),
        )
        assertEquals(
            """{"type":"interrupt","sessionId":"s1","mode":"keep"}""",
            BridgeJson.encodeToString(ClientCommand.serializer(), ClientCommand.Interrupt("s1", "keep")),
        )
        // 反向兼容：旧 wire（无 mode 字段）解码后回退 clear
        val legacy = BridgeJson.decodeFromString(
            ClientCommand.serializer(),
            """{"type":"interrupt","sessionId":"s1"}""",
        )
        assertEquals("clear", assertIs<ClientCommand.Interrupt>(legacy).mode)
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

    // ---- 输入区改版：模型目录 / 上下文占用 / set_model ----

    @Test
    fun decode_models_update() {
        val ev = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"models_update","sessionId":"s1","models":{
                "current":{"provider":"deepseek-official","model":"deepseek-chat"},
                "routable":true,
                "groups":[{"id":"deepseek-official","name":"DeepSeek","models":[
                    {"id":"deepseek-chat","name":"DeepSeek Chat","description":"通用对话"},
                    {"id":"deepseek-reasoner","name":"DeepSeek Reasoner",
                     "reasoning":{"efforts":[{"id":"low","name":"低"},{"id":"high","name":"高"}],"defaultEffort":"low"}}]}],
                "failures":[]}}""",
        )
        val m = assertIs<ServerEvent.ModelsUpdate>(ev)
        assertEquals("s1", m.sessionId)
        assertEquals("deepseek-official", m.models.current?.provider)
        assertEquals("deepseek-chat", m.models.current?.model)
        assertNull(m.models.current?.reasoningEffort)
        assertEquals(true, m.models.routable)
        assertEquals(1, m.models.groups.size)
        assertEquals("DeepSeek", m.models.groups[0].name)
        assertEquals(2, m.models.groups[0].models.size)
        val reasoner = m.models.groups[0].models[1]
        assertEquals(2, reasoner.reasoning?.efforts?.size)
        assertEquals("low", reasoner.reasoning?.defaultEffort)
        assertEquals("高", reasoner.reasoning?.efforts?.get(1)?.name)
    }

    @Test
    fun decode_models_update_minimal() {
        // 目录未加载（current/routable 为 null）→ 客户端显示占位，不崩溃
        val ev = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"models_update","sessionId":"s1","models":{"current":null,"routable":null,"groups":[],"failures":[]}}""",
        )
        val m = assertIs<ServerEvent.ModelsUpdate>(ev)
        assertNull(m.models.current)
        assertNull(m.models.routable)
        assertEquals(emptyList(), m.models.groups)
        assertEquals(emptyList(), m.models.failures)
    }

    @Test
    fun decode_context_usage() {
        val ev = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"context_usage","sessionId":"s1","usage":{
                "contextWindow":128000,"pressureTokens":50000,"projectedTokens":52000,"percent":41,
                "breakdown":{"systemTokens":8000,"toolsTokens":12000,"messageTokens":32000}}}""",
        )
        val u = assertIs<ServerEvent.ContextUsage>(ev)
        assertEquals(128000L, u.usage.contextWindow)
        assertEquals(50000L, u.usage.pressureTokens)
        assertEquals(52000L, u.usage.projectedTokens)
        assertEquals(41, u.usage.percent)
        assertEquals(8000L, u.usage.breakdown?.systemTokens)
        assertEquals(12000L, u.usage.breakdown?.toolsTokens)
        assertEquals(32000L, u.usage.breakdown?.messageTokens)
    }

    @Test
    fun decode_context_usage_minimal() {
        // 无 provider 上报 usage / 无 breakdown 投影 → 全缺省，客户端不渲染占用环
        val ev = BridgeJson.decodeFromString(
            ServerEvent.serializer(),
            """{"type":"context_usage","sessionId":"s1","usage":{}}""",
        )
        val u = assertIs<ServerEvent.ContextUsage>(ev)
        assertNull(u.usage.contextWindow)
        assertNull(u.usage.pressureTokens)
        assertNull(u.usage.projectedTokens)
        assertNull(u.usage.percent)
        assertNull(u.usage.breakdown)
    }

    @Test
    fun encode_set_model_wire() {
        assertEquals(
            """{"type":"set_model","sessionId":"s1","provider":"deepseek-official","model":"deepseek-chat","reasoningEffort":null}""",
            BridgeJson.encodeToString(
                ClientCommand.serializer(),
                ClientCommand.SetModel("s1", "deepseek-official", "deepseek-chat"),
            ),
        )
        assertEquals(
            """{"type":"set_model","sessionId":"s1","provider":"deepseek-official","model":"deepseek-reasoner","reasoningEffort":"high"}""",
            BridgeJson.encodeToString(
                ClientCommand.serializer(),
                ClientCommand.SetModel("s1", "deepseek-official", "deepseek-reasoner", "high"),
            ),
        )
    }

    @Test
    fun decode_set_model_legacy() {
        // 旧 wire（无 reasoningEffort）→ 回退 null（保留 provider/model 默认行为）
        val cmd = BridgeJson.decodeFromString(
            ClientCommand.serializer(),
            """{"type":"set_model","sessionId":"s1","provider":"openai","model":"gpt-4o"}""",
        )
        val s = assertIs<ClientCommand.SetModel>(cmd)
        assertEquals("s1", s.sessionId)
        assertEquals("openai", s.provider)
        assertEquals("gpt-4o", s.model)
        assertNull(s.reasoningEffort)
    }
}


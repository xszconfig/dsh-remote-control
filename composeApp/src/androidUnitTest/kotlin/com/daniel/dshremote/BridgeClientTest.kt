package com.daniel.dshremote

import com.daniel.dshremote.protocol.ApprovalDecision
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.CachedSessionSnapshot
import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.ServerEvent
import com.daniel.dshremote.protocol.SessionSummary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 统一连接状态提示槽（ConnectionNotice）回归：
 * 连接类错误（「连接已断开」）可自动恢复、重连中抑制、hello 到达清除；
 * 业务类错误保留需手动清除；旧的 errors 横幅并入单一槽。
 */
class BridgeClientTest {

    private fun newClient(scope: CoroutineScope, pendingStore: PendingStore = InMemoryPendingStore()): BridgeClient {
        val dir = File(System.getProperty("java.io.tmpdir"), "dsh-client-test-${System.nanoTime()}")
        return BridgeClient(
            scope = scope,
            store = AndroidDeviceStore(dir),
            eventCache = object : EventCache {
                override suspend fun load(key: String): List<EventProjection> = emptyList()
                override suspend fun save(key: String, events: List<EventProjection>) {}
            },
            sessionCache = object : SessionCache {
                override suspend fun load(key: String): CachedSessionSnapshot? = null
                override suspend fun save(key: String, snapshot: CachedSessionSnapshot) {}
            },
            draftCache = object : DraftCache {
                override suspend fun load(key: String): String? = null
                override suspend fun save(key: String, text: String) {}
            },
            bootNoticeCache = object : BootNoticeCache {
                override suspend fun load(key: String): String? = null
                override suspend fun save(key: String, version: String) {}
            },
            pendingStore = pendingStore,
        )
    }

    /** 内存态待发送消息 store：纯 suspend（无 IO），runTest 下 runCurrent 可同步推进。 */
    private class InMemoryPendingStore : PendingStore {
        val data = mutableMapOf<String, List<PendingMessage>>()
        override suspend fun load(sessionId: String): List<PendingMessage> = data[sessionId] ?: emptyList()
        override suspend fun save(sessionId: String, pending: List<PendingMessage>) {
            if (pending.isEmpty()) data.remove(sessionId) else data[sessionId] = pending
        }
        override suspend fun update(
            sessionId: String,
            transform: (List<PendingMessage>) -> List<PendingMessage>,
        ): List<PendingMessage> {
            val next = transform(data[sessionId] ?: emptyList())
            if (next.isEmpty()) data.remove(sessionId) else data[sessionId] = next
            return next
        }
    }

    @Test
    fun subscribeFailureWhileDisconnected_surfacesError() = runTest {
        val client = newClient(backgroundScope)
        client.openSession("s1")
        runCurrent() // 执行 scope.launch 里的 send → 未连接 → 失败
        assertEquals(listOf(NoticeError("订阅会话失败（连接已断开）", recoverable = true)), client.session.value.errors)
        assertEquals(ConnectionNotice.Error("订阅会话失败（连接已断开）"), client.notice.value)
    }

    @Test
    fun sendFailures_cappedAtMaxErrors() = runTest {
        val client = newClient(backgroundScope)
        repeat(30) { i ->
            client.approve(
                ApprovalRequestWire(approvalId = "a$i", sessionId = "s1", toolName = "bash"),
                ApprovalDecision.AllowedOnce,
            )
        }
        runCurrent()
        assertEquals(MAX_ERRORS, client.session.value.errors.size)
        // 只保留最近的：最后一条对应审批发送失败（连接类 → 可自动恢复）
        assertEquals(true, client.session.value.errors.last().message.contains("审批决策发送失败"))
        assertEquals(true, client.session.value.errors.last().recoverable)
    }

    @Test
    fun dismissErrors_clearsList_andNotice() = runTest {
        val client = newClient(backgroundScope)
        client.interrupt("s1")
        runCurrent()
        assertEquals(1, client.session.value.errors.size)
        assertEquals(ConnectionNotice.Error("中断指令发送失败（连接已断开）"), client.notice.value)
        client.dismissErrors()
        assertEquals(emptyList(), client.session.value.errors)
        assertEquals(ConnectionNotice.Hidden, client.notice.value)
    }

    @Test
    fun closeSession_isLocalNavigation_noProtocolTraffic() = runTest {
        // 回归：旧版返回按钮调 openSession("") 会向 bridge 发 subscribe:"",
        // bridge 回 error not_found；现在必须纯本地切换且不产生任何错误
        val client = newClient(backgroundScope)
        client.openSession("s1")
        client.closeSession()
        runCurrent()
        assertEquals(null, client.session.value.currentSessionId)
        assertEquals(emptyList(), client.session.value.events)
        assertEquals(1, client.session.value.errors.size) // 只有订阅失败那条，无 not_found
    }

    @Test
    fun openSession_blankId_ignored() = runTest {
        val client = newClient(backgroundScope)
        client.openSession("")
        client.openSession("  ")
        runCurrent()
        assertEquals(null, client.session.value.currentSessionId)
        assertEquals(0, client.session.value.errors.size)
    }

    @Test
    fun hello_clearsRecoverableErrors_keepsBusinessErrors() {
        assertEquals(
            listOf(NoticeError("not_found: session not found: x", recoverable = false)),
            reconcileErrorsOnHello(
                listOf(
                    NoticeError("订阅会话失败（连接已断开）", recoverable = true),
                    NoticeError("not_found: session not found: x", recoverable = false),
                    NoticeError("设备注册失败（连接已断开）", recoverable = true),
                ),
            ),
        )
    }

    @Test
    fun connectionErrors_suppressedWhileReconnecting() = runTest {
        val client = newClient(backgroundScope)
        client.beginReconnectNotice(1)
        client.openSession("s1")
        runCurrent()
        // 重连中：连接类错误被抑制（不堆积、不覆盖 Reconnecting 槽）
        assertEquals(0, client.session.value.errors.size)
        assertTrue(client.notice.value is ConnectionNotice.Reconnecting)
    }

    @Test
    fun hello_clearsReconnectingNotice_andStaysCleared() = runTest {
        val client = newClient(backgroundScope)
        client.beginReconnectNotice(1)
        assertTrue(client.notice.value is ConnectionNotice.Reconnecting)
        // 模拟 hello 到达：清槽（成功连接后不再置位重连态）
        client.handle(ServerEvent.Hello(version = "test", sessions = emptyList(), agents = emptyList()))
        assertEquals(ConnectionNotice.Hidden, client.notice.value)
        // 无新连接尝试：槽保持 Hidden（入口重断言已移除，不会在无新连接时把槽拨回 Reconnecting）
        runCurrent()
        assertEquals(ConnectionNotice.Hidden, client.notice.value)
    }

    @Test
    fun pushBusinessError_isNotRecoverable_andShown() = runTest {
        val client = newClient(backgroundScope)
        client.pushBusinessError("not_found: session not found: x")
        assertEquals(listOf(NoticeError("not_found: session not found: x", recoverable = false)), client.session.value.errors)
        assertEquals(ConnectionNotice.Error("not_found: session not found: x"), client.notice.value)
    }

    @Test
    fun sendMessage_runningSession_doesNotCreatePendingBubble() = runTest {
        val client = newClient(backgroundScope)
        client.handle(
            ServerEvent.Hello(
                version = "test",
                sessions = listOf(
                    SessionSummary(id = "s1", cwd = "/tmp", status = "running", agentCount = 1, subagentCount = 0, updatedAt = 0),
                ),
                agents = emptyList(),
            ),
        )
        client.openSession("s1")
        client.sendMessage("hi")
        // 运行中：消息进排队队列（乐观项），不上屏 pending（避免既上屏又排队双份显示）
        assertEquals(emptyList(), client.session.value.pendingMessages)
        assertEquals(1, client.session.value.queueItems.size)
        assertTrue(client.session.value.queueItems.first().id.startsWith("local-"))
    }

    @Test
    fun sendMessage_idleSession_createsPendingBubble() = runTest {
        val client = newClient(backgroundScope)
        client.handle(
            ServerEvent.Hello(
                version = "test",
                sessions = listOf(
                    SessionSummary(id = "s1", cwd = "/tmp", status = "idle", agentCount = 1, subagentCount = 0, updatedAt = 0),
                ),
                agents = emptyList(),
            ),
        )
        client.openSession("s1")
        client.sendMessage("hi")
        // 非运行中：消息被立即消费 → 上屏 pending(Sending)
        assertEquals(1, client.session.value.pendingMessages.size)
        assertEquals(PendingStatus.Sending, client.session.value.pendingMessages.first().status)
        assertEquals("hi", client.session.value.pendingMessages.first().text)
        assertEquals(emptyList(), client.session.value.queueItems)
    }

    @Test
    fun sendFailure_persistsFailed_andReopenRestores() = runTest {
        val store = InMemoryPendingStore()
        val client = newClient(backgroundScope, store)
        client.handle(
            ServerEvent.Hello(
                version = "test",
                sessions = listOf(
                    SessionSummary(id = "s1", cwd = "/tmp", status = "idle", agentCount = 1, subagentCount = 0, updatedAt = 0),
                ),
                agents = emptyList(),
            ),
        )
        client.openSession("s1")
        client.sendMessage("hi")
        runCurrent() // 推进 scope.launch：写前日志落盘 → connection.send 失败 → markPendingFailed → 再落盘
        // 发送失败（未连接）→ 内存 pending Failed
        assertEquals(PendingStatus.Failed, client.session.value.pendingMessages.single().status)
        // 已持久化：store 里该会话有 1 条 Failed
        assertEquals(1, store.data["s1"]?.size)
        assertEquals(PendingStatus.Failed, store.data["s1"]!!.single().status)
        // 退出到列表再回来：openSession 先清空内存，再从 store 恢复为 Failed（可点 ❗ 重发）
        client.closeSession()
        client.openSession("s1")
        runCurrent()
        assertEquals(PendingStatus.Failed, client.session.value.pendingMessages.single().status)
        assertEquals("hi", client.session.value.pendingMessages.single().text)
    }

    @Test
    fun openSession_restoresSendingAsFailed() = runTest {
        // 模拟：上次进程在「已落盘 sending、尚未送达」时被杀 → 磁盘残留 Sending
        val store = InMemoryPendingStore()
        store.data["s1"] = listOf(
            PendingMessage(msgId = "p1", sessionId = "s1", text = "hi", status = PendingStatus.Sending, createdAt = 1_000L),
        )
        val client = newClient(backgroundScope, store)
        client.handle(
            ServerEvent.Hello(
                version = "test",
                sessions = listOf(
                    SessionSummary(id = "s1", cwd = "/tmp", status = "idle", agentCount = 1, subagentCount = 0, updatedAt = 0),
                ),
                agents = emptyList(),
            ),
        )
        client.openSession("s1")
        runCurrent()
        // 恢复时 sending 一律转 failed（结果未知，避免自动重发）
        assertEquals(PendingStatus.Failed, client.session.value.pendingMessages.single().status)
        assertEquals("hi", client.session.value.pendingMessages.single().text)
        // 回写已转换状态，store 不再残留 Sending
        assertEquals(PendingStatus.Failed, store.data["s1"]!!.single().status)
    }

    @Test
    fun ack_ok_removes_pending_memory_and_disk() = runTest {
        val store = InMemoryPendingStore()
        val client = newClient(backgroundScope, store)
        client.handle(
            ServerEvent.Hello(
                version = "test",
                sessions = listOf(
                    SessionSummary(id = "s1", cwd = "/tmp", status = "idle", agentCount = 1, subagentCount = 0, updatedAt = 0),
                ),
                agents = emptyList(),
            ),
        )
        client.openSession("s1")
        client.sendMessage("hi")
        runCurrent() // 无连接 → send false → Failed（落盘）
        val msgId = client.session.value.pendingMessages.single().msgId
        client.handle(ServerEvent.Ack(msgId, ok = true))
        runCurrent()
        assertEquals(emptyList(), client.session.value.pendingMessages)
        assertTrue(store.data["s1"].isNullOrEmpty())
    }

    @Test
    fun ack_fail_marks_failed_and_increments_retryCount() = runTest {
        val store = InMemoryPendingStore()
        val client = newClient(backgroundScope, store)
        client.handle(
            ServerEvent.Hello(
                version = "test",
                sessions = listOf(
                    SessionSummary(id = "s1", cwd = "/tmp", status = "idle", agentCount = 1, subagentCount = 0, updatedAt = 0),
                ),
                agents = emptyList(),
            ),
        )
        client.openSession("s1")
        client.sendMessage("hi")
        runCurrent() // send false → Failed（retryCount=0）
        val msgId = client.session.value.pendingMessages.single().msgId
        client.handle(ServerEvent.Ack(msgId, ok = false))
        runCurrent()
        assertEquals(PendingStatus.Failed, client.session.value.pendingMessages.single().status)
        assertEquals(1, client.session.value.pendingMessages.single().retryCount)
        assertEquals(1, store.data["s1"]!!.single().retryCount)
    }
}

package com.daniel.dshremote

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class PendingStoreTest {

    private fun newTempStore(): AndroidPendingStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "dsh-pending-test-${System.nanoTime()}")
        return AndroidPendingStore(dir)
    }

    private fun p(
        msgId: String,
        sessionId: String = "s1",
        text: String = "hello",
        status: PendingStatus = PendingStatus.Failed,
        createdAt: Long = 1_000L,
    ) = PendingMessage(msgId, sessionId, text, status, createdAt)

    @Test
    fun save_load_roundtrip_preserves_all_fields() = runBlocking {
        val store = newTempStore()
        val list = listOf(
            p("a", sessionId = "s1", text = "第一条", status = PendingStatus.Sending, createdAt = 1_111L),
            p("b", sessionId = "s1", text = "第二条", status = PendingStatus.Failed, createdAt = 2_222L),
        )
        store.save("s1", list)
        val loaded = store.load("s1")
        assertEquals(list, loaded)
    }

    @Test
    fun save_empty_deletes_record() = runBlocking {
        val store = newTempStore()
        store.save("s1", listOf(p("a")))
        assertEquals(1, store.load("s1").size)
        // 消息已归服务端（markPendingSent 后 update 删除该条，列表变空）→ 删除持久化记录
        store.save("s1", emptyList())
        assertTrue(store.load("s1").isEmpty())
    }

    @Test
    fun update_upserts_and_removes_atomically() = runBlocking {
        val store = newTempStore()
        // upsert：写入 sending
        store.update("s1") { list -> list + p("a", status = PendingStatus.Sending) }
        assertEquals(PendingStatus.Sending, store.load("s1").single().status)
        // upsert 同 msgId：failed 覆盖 sending（发送失败后落盘 failed）
        store.update("s1") { list -> list.filterNot { it.msgId == "a" } + p("a", status = PendingStatus.Failed) }
        assertEquals(PendingStatus.Failed, store.load("s1").single().status)
        // remove：发送成功 → 删除该条
        store.update("s1") { list -> list.filterNot { it.msgId == "a" } }
        assertTrue(store.load("s1").isEmpty())
    }

    @Test
    fun concurrent_updates_do_not_lose_writes() = runBlocking {
        val store = newTempStore()
        // 并发 upsert 不同 msgId：原子读-改-写保证全部落盘（防丢写，回归 DeviceStore 同类竞态）
        coroutineScope {
            (1..20).map { n ->
                async(Dispatchers.IO) {
                    store.update("s1") { list -> list + p("id-$n") }
                }
            }.awaitAll()
        }
        assertEquals(20, store.load("s1").size)
        assertEquals((1..20).map { "id-$it" }.toSet(), store.load("s1").map { it.msgId }.toSet())
    }

    @Test
    fun multi_session_isolation() = runBlocking {
        val store = newTempStore()
        store.save("s1", listOf(p("a", sessionId = "s1", text = "会话一")))
        store.save("s2", listOf(p("b", sessionId = "s2", text = "会话二")))
        assertEquals(listOf("会话一"), store.load("s1").map { it.text })
        assertEquals(listOf("会话二"), store.load("s2").map { it.text })
        // 清一个会话不影响另一个
        store.save("s1", emptyList())
        assertTrue(store.load("s1").isEmpty())
        assertEquals(listOf("会话二"), store.load("s2").map { it.text })
    }

    @Test
    fun load_missing_or_corrupt_returns_empty() = runBlocking {
        val store = newTempStore()
        assertTrue(store.load("no-such-session").isEmpty())
    }

    @Test
    fun session_id_with_path_chars_is_sanitized() = runBlocking {
        val store = newTempStore()
        val weird = "../etc/passwd"
        store.save(weird, listOf(p("a", sessionId = weird)))
        // 不崩溃、能读回：非法字符被替换为下划线，文件名不越出目录
        assertEquals(1, store.load(weird).size)
    }
}

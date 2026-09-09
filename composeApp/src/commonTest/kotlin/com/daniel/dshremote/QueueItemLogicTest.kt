package com.daniel.dshremote

import com.daniel.dshremote.protocol.QueueItemWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueItemLogicTest {

    // ---- 乐观排队项（local- 前缀）判定：禁止插队/删除 ----

    @Test
    fun local_prefixed_optimistic_item_is_syncing() {
        // 乐观项尚未被服务端替换成真实 id → 同步中，禁止操作
        assertTrue(isSyncingQueueItem("local-1788666000000"))
        assertTrue(isSyncingQueueItem("local-1"))
    }

    @Test
    fun real_server_id_is_not_syncing() {
        // 真实 MessageId（uuid 或其它非 local- 前缀）→ 可操作
        assertFalse(isSyncingQueueItem("d96463dc-eb8d-4a5d-9701-a06b2bbed089"))
        assertFalse(isSyncingQueueItem("msg-abc"))
        assertFalse(isSyncingQueueItem(""))
    }

    // ---- 队列操作错误 → 用户可读横幅（绝不静默消失）----

    @Test
    fun queue_item_not_found_banner_marks_claimed_not_lost() {
        assertEquals(
            "排队消息已被本轮领取，正在处理中（未丢失）",
            queueErrorBanner("queue-item-not-found"),
        )
    }

    @Test
    fun steer_unavailable_banner_says_still_queued() {
        assertEquals(
            "当前轮次不接受插队，消息仍在排队（可稍后再插队）",
            queueErrorBanner("steer-unavailable"),
        )
    }

    @Test
    fun unknown_code_has_no_dedicated_banner() {
        // 无专属文案的错误走通用 code:message 横幅
        assertNull(queueErrorBanner("some-other-error"))
        assertNull(queueErrorBanner("not_found"))
    }

    // ---- 展示过滤：只显示用户来源项，过滤系统注入（context）----

    private fun item(id: String, placement: String) = QueueItemWire(id = id, placement = placement, text = "t-$id")

    @Test
    fun user_visible_filters_out_context_items() {
        val items = listOf(
            item("a", "queued"),
            item("s", "steering"),
            item("c1", "context"),
            item("c2", "context"),
            item("b", "queued"),
        )
        assertEquals(
            listOf("a", "s", "b"),
            userVisibleQueueItems(items).map { it.id },
        )
    }

    @Test
    fun user_visible_keeps_order_of_kept_items() {
        // 过滤只删 context，不动其余项的相对顺序（与服务端投影顺序一致）
        val items = listOf(item("a", "queued"), item("c", "context"), item("b", "queued"))
        assertEquals(listOf("a", "b"), userVisibleQueueItems(items).map { it.id })
    }

    // ---- 乐观项插入：插到 queued 段末尾（steering/context 之前），保证 FIFO ----

    @Test
    fun optimistic_insert_after_all_queued() {
        // 全 queued：插到末尾（FIFO）
        val items = listOf(item("a", "queued"), item("b", "queued"))
        assertEquals(
            listOf("a", "b", "opt"),
            insertOptimisticQueued(items, item("opt", "queued")).map { it.id },
        )
    }

    @Test
    fun optimistic_insert_before_steering_context() {
        // 存在 steering/context 时：新排队项插到它们之前（与服务端 [...nextTurn, ...nextStep] 一致）
        val items = listOf(item("a", "queued"), item("s", "steering"), item("c", "context"))
        assertEquals(
            listOf("a", "opt", "s", "c"),
            insertOptimisticQueued(items, item("opt", "queued")).map { it.id },
        )
    }

    @Test
    fun optimistic_insert_into_empty() {
        assertEquals(
            listOf("opt"),
            insertOptimisticQueued(emptyList(), item("opt", "queued")).map { it.id },
        )
    }
}

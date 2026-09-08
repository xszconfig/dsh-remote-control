package com.daniel.dshremote

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
}

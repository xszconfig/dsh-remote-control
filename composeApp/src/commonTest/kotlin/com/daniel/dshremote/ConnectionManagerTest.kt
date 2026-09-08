package com.daniel.dshremote

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 应用层 ping/pong 判活纯函数回归（假连接秒级判死）。 */
class ConnectionManagerTest {

    @Test
    fun pong_received_after_ping_not_timed_out() {
        // ping 后收到了 pong（lastPongAt >= lastPingAt）→ 不判死
        assertFalse(pongTimedOut(lastPingAt = 1_000L, lastPongAt = 1_050L, now = 5_000L))
    }

    @Test
    fun no_pong_within_timeout_not_dead_yet() {
        // ping 后 5s 无 pong，但尚未超过 10s 超时 → 暂不判死
        assertFalse(pongTimedOut(lastPingAt = 1_000L, lastPongAt = 0L, now = 6_000L, timeoutMs = 10_000L))
    }

    @Test
    fun no_pong_beyond_timeout_dead() {
        // ping 后 11s 无 pong（>10s 超时）→ 判死（假连接）
        assertTrue(pongTimedOut(lastPingAt = 1_000L, lastPongAt = 0L, now = 12_000L, timeoutMs = 10_000L))
    }

    @Test
    fun not_yet_pinged_not_timed_out() {
        // 尚未发过 ping（lastPingAt=0）→ 不判死
        assertFalse(pongTimedOut(lastPingAt = 0L, lastPongAt = 0L, now = 99_000L))
    }
}

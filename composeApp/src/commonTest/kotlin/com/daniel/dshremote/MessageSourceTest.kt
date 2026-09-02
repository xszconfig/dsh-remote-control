package com.daniel.dshremote

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageSourceTest {

    @Test
    fun real_user_input_renders_as_user_bubble() {
        assertFalse(isInjectedUserMessage(MessageSource.USER))
    }

    @Test
    fun injected_context_renders_as_context_row() {
        assertTrue(isInjectedUserMessage(MessageSource.INJECT))
    }

    @Test
    fun legacy_bridge_without_source_keeps_user_bubble() {
        // 老桥无 source 字段 → null → 向后兼容按用户消息呈现
        assertFalse(isInjectedUserMessage(null))
    }

    @Test
    fun unknown_future_source_never_falls_back_to_user_bubble() {
        // 前向安全：任何非 user 且非空的值（未来的 context/recall 等）都不得与用户消息混淆
        assertTrue(isInjectedUserMessage("context"))
        assertTrue(isInjectedUserMessage("recall"))
        assertTrue(isInjectedUserMessage("steer"))
    }
}

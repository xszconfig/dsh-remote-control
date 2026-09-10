package com.daniel.dshremote

import com.daniel.dshremote.protocol.ModelCatalogModelWire
import com.daniel.dshremote.protocol.ModelProviderGroupWire
import com.daniel.dshremote.protocol.ModelSelectionWire
import com.daniel.dshremote.protocol.SessionModelsWire
import com.daniel.dshremote.protocol.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputBarLogicTest {

    private fun session(id: String, status: String) =
        SessionSummary(id = id, cwd = "/tmp", status = status, agentCount = 1, subagentCount = 0, updatedAt = 0)

    // ---- 终止两态判定（agentRunning） ----

    @Test
    fun agentRunning_trueWhenStatusRunning() {
        assertTrue(isAgentRunning(listOf(session("s1", "running")), "s1", null))
    }

    @Test
    fun agentRunning_trueWhenWaitingModel() {
        assertTrue(isAgentRunning(listOf(session("s1", "idle")), "s1", modelWaitingSince = 123L))
    }

    @Test
    fun agentRunning_falseWhenIdleAndNotWaiting() {
        assertFalse(isAgentRunning(listOf(session("s1", "idle")), "s1", null))
    }

    @Test
    fun agentRunning_falseWhenSessionMissing() {
        assertFalse(isAgentRunning(emptyList(), "s1", null))
    }

    @Test
    fun agentRunning_notConfusedByAnotherSessionRunning() {
        // 会话隔离：其它会话 running 不影响目标会话的判定
        assertFalse(isAgentRunning(listOf(session("s2", "running")), "s1", null))
    }

    // ---- 模型入口短标签 ----

    @Test
    fun modelEntryLabel_nullModels_isPlaceholder() {
        assertEquals(MODEL_ENTRY_PLACEHOLDER, modelEntryLabel(null))
    }

    @Test
    fun modelEntryLabel_nullCurrent_isPlaceholder() {
        assertEquals(MODEL_ENTRY_PLACEHOLDER, modelEntryLabel(SessionModelsWire(current = null)))
    }

    @Test
    fun modelEntryLabel_currentInCatalog_showsProviderAndModel() {
        val models = SessionModelsWire(
            current = ModelSelectionWire("p1", "m1"),
            groups = listOf(
                ModelProviderGroupWire("p1", "DeepSeek", listOf(ModelCatalogModelWire("m1", "Chat"))),
            ),
        )
        assertEquals("DeepSeek · Chat", modelEntryLabel(models))
    }

    @Test
    fun modelEntryLabel_currentNotInCatalog_isPlaceholder() {
        // 目录是 advisory：current 不在目录组内时不合成过期行
        val models = SessionModelsWire(
            current = ModelSelectionWire("p1", "m9"),
            groups = listOf(
                ModelProviderGroupWire("p1", "DeepSeek", listOf(ModelCatalogModelWire("m1", "Chat"))),
            ),
        )
        assertEquals(MODEL_ENTRY_PLACEHOLDER, modelEntryLabel(models))
    }

    @Test
    fun modelEntryLabel_currentProviderMissing_isPlaceholder() {
        val models = SessionModelsWire(
            current = ModelSelectionWire("unknown-provider", "m1"),
            groups = listOf(
                ModelProviderGroupWire("p1", "DeepSeek", listOf(ModelCatalogModelWire("m1", "Chat"))),
            ),
        )
        assertEquals(MODEL_ENTRY_PLACEHOLDER, modelEntryLabel(models))
    }
}

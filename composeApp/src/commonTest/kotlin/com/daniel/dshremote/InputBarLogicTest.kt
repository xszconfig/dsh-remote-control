package com.daniel.dshremote

import com.daniel.dshremote.protocol.ContextUsageWire
import com.daniel.dshremote.protocol.ModelCatalogModelWire
import com.daniel.dshremote.protocol.ModelProviderGroupWire
import com.daniel.dshremote.protocol.ModelSelectionWire
import com.daniel.dshremote.protocol.SessionModelsWire
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.SkillWire
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

    // ---- 技能搜索过滤（filterSkills） ----

    private val skillA = SkillWire("code-lint", "一键跑 lint 拿结构化结果")
    private val skillB = SkillWire("dsh-restart", "重启本机 DSH 服务端", whenToUse = "用户说重启时")
    private val skillC = SkillWire("lark-doc", "飞书云文档读写")
    private val skillCatalog = listOf(skillA, skillB, skillC)

    @Test
    fun filterSkills_emptyOrBlankQuery_returnsAll() {
        assertEquals(skillCatalog, filterSkills(skillCatalog, ""))
        assertEquals(skillCatalog, filterSkills(skillCatalog, "   "))
    }

    @Test
    fun filterSkills_nameContains_caseInsensitive() {
        assertEquals(listOf(skillA), filterSkills(skillCatalog, "LINT"))
        assertEquals(listOf(skillB), filterSkills(skillCatalog, "restart"))
    }

    @Test
    fun filterSkills_descriptionContains() {
        assertEquals(listOf(skillB), filterSkills(skillCatalog, "服务端"))
        assertEquals(listOf(skillA), filterSkills(skillCatalog, "lint 拿"))
    }

    @Test
    fun filterSkills_whenToUseContains() {
        // whenToUse「适用」文案可搜：按「重启时」命中 dsh-restart
        assertEquals(listOf(skillB), filterSkills(skillCatalog, "重启时"))
    }

    @Test
    fun filterSkills_noMatch_returnsEmpty() {
        assertEquals(emptyList(), filterSkills(skillCatalog, "不存在的技能"))
    }

    @Test
    fun filterSkills_preservesOrder() {
        // 三技能名均含 "d"（code/dsh/doc），命中后按原目录顺序返回，不重排
        assertEquals(skillCatalog, filterSkills(skillCatalog, "d"))
    }

    // ---- 上下文占用分子与 token 紧凑格式 ----

    @Test
    fun contextUsedTokens_prefersProjectedThenPressure() {
        assertEquals(52000L, contextUsedTokens(ContextUsageWire(projectedTokens = 52000, pressureTokens = 50000)))
        assertEquals(50000L, contextUsedTokens(ContextUsageWire(pressureTokens = 50000)))
        assertEquals(null, contextUsedTokens(ContextUsageWire()))
        assertEquals(null, contextUsedTokens(null))
    }

    @Test
    fun formatContextTokens_buckets() {
        assertEquals("0", formatContextTokens(0))
        assertEquals("0", formatContextTokens(-5))
        assertEquals("517", formatContextTokens(517))
        assertEquals("999", formatContextTokens(999))
        assertEquals("1K", formatContextTokens(1000))
        assertEquals("12.2K", formatContextTokens(12_200))
        assertEquals("517K", formatContextTokens(517_000))
        assertEquals("1M", formatContextTokens(1_000_000))
        assertEquals("1.2M", formatContextTokens(1_200_000))
    }
}

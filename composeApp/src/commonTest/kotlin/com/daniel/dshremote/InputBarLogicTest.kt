package com.daniel.dshremote

import com.daniel.dshremote.protocol.ContextUsageWire
import com.daniel.dshremote.protocol.ModelCatalogModelWire
import com.daniel.dshremote.protocol.ModelProviderGroupWire
import com.daniel.dshremote.protocol.ModelReasoningEffortWire
import com.daniel.dshremote.protocol.ModelReasoningWire
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

    // ---- 模型入口短标签（模型显示名 · 推理强度显示名） ----

    private fun model(id: String, name: String, reasoning: ModelReasoningWire? = null) =
        ModelCatalogModelWire(id = id, name = name, reasoning = reasoning)

    private fun effort(id: String, name: String) = ModelReasoningEffortWire(id = id, name = name)

    @Test
    fun modelEntryLabel_nullModels_isPlaceholder() {
        assertEquals(MODEL_ENTRY_PLACEHOLDER, modelEntryLabel(null))
    }

    @Test
    fun modelEntryLabel_nullCurrent_isPlaceholder() {
        assertEquals(MODEL_ENTRY_PLACEHOLDER, modelEntryLabel(SessionModelsWire(current = null)))
    }

    @Test
    fun modelEntryLabel_modelNameOnly_whenNoEffort() {
        val models = SessionModelsWire(
            current = ModelSelectionWire("p1", "m1"),
            groups = listOf(ModelProviderGroupWire("p1", "DeepSeek", listOf(model("m1", "DeepSeek V4 Pro")))),
        )
        assertEquals("DeepSeek V4 Pro", modelEntryLabel(models))
    }

    @Test
    fun modelEntryLabel_modelAndEffortDisplayNames() {
        // 修复根因：显示「模型显示名 · 推理强度显示名」，不拼 provider 名（避免「Deepseek · Deepseek」重复）
        val models = SessionModelsWire(
            current = ModelSelectionWire("p1", "m1", "max"),
            groups = listOf(
                ModelProviderGroupWire(
                    "p1",
                    "DeepSeek",
                    listOf(
                        model(
                            "m1",
                            "DeepSeek V4 Pro",
                            ModelReasoningWire(efforts = listOf(effort("off", "Off"), effort("max", "Max")), defaultEffort = "off"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals("DeepSeek V4 Pro · Max", modelEntryLabel(models))
    }

    @Test
    fun modelEntryLabel_modelMissing_isPlaceholder() {
        // 目录是 advisory：model id 不在任何分组内 → 占位
        val models = SessionModelsWire(
            current = ModelSelectionWire("p1", "m9"),
            groups = listOf(ModelProviderGroupWire("p1", "DeepSeek", listOf(model("m1", "DeepSeek V4 Pro")))),
        )
        assertEquals(MODEL_ENTRY_PLACEHOLDER, modelEntryLabel(models))
    }

    @Test
    fun modelEntryLabel_effortMissing_showsModelOnly() {
        // effort 查不到 → 只显示模型名，不合成过期档位
        val models = SessionModelsWire(
            current = ModelSelectionWire("p1", "m1", "unknown-effort"),
            groups = listOf(
                ModelProviderGroupWire(
                    "p1",
                    "DeepSeek",
                    listOf(
                        model("m1", "DeepSeek V4 Pro", ModelReasoningWire(efforts = listOf(effort("max", "Max")))),
                    ),
                ),
            ),
        )
        assertEquals("DeepSeek V4 Pro", modelEntryLabel(models))
    }

    @Test
    fun modelEntryLabel_matchesByModelId_ignoresProvider() {
        // 查找按 model id 全量匹配，不依赖 provider 名
        val models = SessionModelsWire(
            current = ModelSelectionWire("unknown-provider", "m1"),
            groups = listOf(ModelProviderGroupWire("p1", "DeepSeek", listOf(model("m1", "DeepSeek V4 Pro")))),
        )
        assertEquals("DeepSeek V4 Pro", modelEntryLabel(models))
    }

    // ---- 当前模型定位 + effort 档位解析（右区 / 强度面板） ----

    @Test
    fun currentModelRef_nullModels_isNull() {
        assertEquals(null, currentModelRef(null))
    }

    @Test
    fun currentModelRef_nullCurrent_isNull() {
        assertEquals(null, currentModelRef(SessionModelsWire(current = null)))
    }

    @Test
    fun currentModelRef_resolvesProviderAndModel() {
        val models = SessionModelsWire(
            current = ModelSelectionWire("p1", "m1", "max"),
            groups = listOf(ModelProviderGroupWire("p1", "DeepSeek", listOf(model("m1", "DeepSeek V4 Pro")))),
        )
        val ref = currentModelRef(models)
        assertEquals("p1", ref?.provider)
        assertEquals("m1", ref?.model?.id)
    }

    @Test
    fun currentModelRef_modelMissing_isNull() {
        val models = SessionModelsWire(
            current = ModelSelectionWire("p1", "m9"),
            groups = listOf(ModelProviderGroupWire("p1", "DeepSeek", listOf(model("m1", "DeepSeek V4 Pro")))),
        )
        assertEquals(null, currentModelRef(models))
    }

    @Test
    fun currentModelEffortOptions_noReasoning_isNull() {
        val models = SessionModelsWire(
            current = ModelSelectionWire("p1", "m1"),
            groups = listOf(ModelProviderGroupWire("p1", "DeepSeek", listOf(model("m1", "DeepSeek V4 Pro")))),
        )
        assertEquals(null, currentModelEffortOptions(models))
    }

    @Test
    fun currentModelEffortOptions_emptyEfforts_isNull() {
        val models = SessionModelsWire(
            current = ModelSelectionWire("p1", "m1"),
            groups = listOf(
                ModelProviderGroupWire("p1", "DeepSeek", listOf(model("m1", "DeepSeek V4 Pro", ModelReasoningWire(efforts = emptyList())))),
            ),
        )
        assertEquals(null, currentModelEffortOptions(models))
    }

    @Test
    fun currentModelEffortOptions_returnsEffortsAndDefault() {
        val models = SessionModelsWire(
            current = ModelSelectionWire("p1", "m1", "max"),
            groups = listOf(
                ModelProviderGroupWire(
                    "p1",
                    "DeepSeek",
                    listOf(
                        model(
                            "m1",
                            "DeepSeek V4 Pro",
                            ModelReasoningWire(
                                efforts = listOf(effort("off", "Off"), effort("max", "Max")),
                                defaultEffort = "off",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val opts = currentModelEffortOptions(models)
        assertEquals(listOf("off", "max"), opts?.efforts?.map { it.id })
        assertEquals("off", opts?.defaultEffort)
    }

    @Test
    fun currentModelHasEffortOptions_trueOnlyWithNonEmptyEfforts() {
        val withEfforts = SessionModelsWire(
            current = ModelSelectionWire("p1", "m1"),
            groups = listOf(
                ModelProviderGroupWire(
                    "p1",
                    "DeepSeek",
                    listOf(model("m1", "M", ModelReasoningWire(efforts = listOf(effort("max", "Max"))))),
                ),
            ),
        )
        val without = SessionModelsWire(
            current = ModelSelectionWire("p1", "m1"),
            groups = listOf(ModelProviderGroupWire("p1", "DeepSeek", listOf(model("m1", "M")))),
        )
        assertTrue(currentModelHasEffortOptions(withEfforts))
        assertFalse(currentModelHasEffortOptions(without))
        assertFalse(currentModelHasEffortOptions(null))
    }

    @Test
    fun modelEntryParts_hasEffortOptionsFlag_whenNoEffortSelected() {
        // 有档位但未指定 → effort 为 null、hasEffortOptions=true（右区显示「强度」占位且可点）
        val models = SessionModelsWire(
            current = ModelSelectionWire("p1", "m1"),
            groups = listOf(
                ModelProviderGroupWire(
                    "p1",
                    "DeepSeek",
                    listOf(model("m1", "DeepSeek V4 Pro", ModelReasoningWire(efforts = listOf(effort("max", "Max"))))),
                ),
            ),
        )
        assertEquals(ModelEntryParts("DeepSeek V4 Pro", null, hasEffortOptions = true), modelEntryParts(models))
    }

    @Test
    fun modelEntryParts_placeholder_hasNoEffortOptions() {
        assertEquals(ModelEntryParts(MODEL_ENTRY_PLACEHOLDER, null, hasEffortOptions = false), modelEntryParts(null))
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

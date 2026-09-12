package com.daniel.dshremote

import com.daniel.dshremote.protocol.ContextUsageWire
import com.daniel.dshremote.protocol.ModelCatalogModelWire
import com.daniel.dshremote.protocol.ModelReasoningEffortWire
import com.daniel.dshremote.protocol.SessionModelsWire
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.SkillWire

/**
 * 输入区操作条纯函数（可单测）：终止两态判定 + 模型入口短标签 + 技能搜索过滤 + 上下文 token 选择/格式化。
 *
 * 铁律 6：这里只做「渲染所需」的纯变换，绝不做任何服务端投影之外的推算——
 * 上下文占用百分比/分子直接用服务端下发的 contextUsage 字段（projectedTokens/pressureTokens/percent），
 * 本文件只做字段回退选择与紧凑格式化，不计算占用。
 */

/** 模型入口占位标签：current 为 null 或不在目录组内（目录是 advisory）时显示。 */
internal const val MODEL_ENTRY_PLACEHOLDER = "选择模型"

/** 入口右区占位：当前模型有 effort 档位但尚未指定档位时，右区显示「强度」提示可点开强度面板。 */
internal const val MODEL_ENTRY_EFFORT_PLACEHOLDER = "强度"

/**
 * 模型入口结构化标签：模型显示名 + 推理强度显示名（可空 = 未指定）。
 * [hasEffortOptions]=当前模型是否有可选 reasoning.efforts 档位（决定入口右区是否显示）。
 */
internal data class ModelEntryParts(
    val name: String,
    val effort: String?,
    val hasEffortOptions: Boolean = false,
)

/**
 * 终止/运行态判定信号（复用现状，不新增信号源）：会话 running 或等待模型中即视为运行中。
 * 对应 App.kt ConversationComposer 的 agentRunning（PRD 2.0 术语）。
 */
internal fun isAgentRunning(sessions: List<SessionSummary>, sessionId: String, modelWaitingSince: Long?): Boolean =
    sessions.firstOrNull { it.id == sessionId }?.status == "running" || modelWaitingSince != null

/** 当前模型定位（provider id + 模型目录项）；current 为 null 或目录查不到时返回 null。 */
internal data class CurrentModelRef(val provider: String, val model: ModelCatalogModelWire)

/** 当前模型可选的推理强度档位（+ 默认档）；无 reasoning.efforts 时返回 null（→ 入口右区不显示）。 */
internal data class CurrentEffortOptions(val efforts: List<ModelReasoningEffortWire>, val defaultEffort: String?)

/** 按 model id 在 groups[].models[] 全量定位当前模型（不依赖 provider 名，目录是 advisory）。 */
internal fun currentModelRef(models: SessionModelsWire?): CurrentModelRef? {
    val current = models?.current ?: return null
    val group = models.groups.firstOrNull { g -> g.models.any { it.id == current.model } } ?: return null
    val model = group.models.firstOrNull { it.id == current.model } ?: return null
    return CurrentModelRef(group.id, model)
}

/** 当前模型的 effort 档位列表（供强度面板）；无档位返回 null。 */
internal fun currentModelEffortOptions(models: SessionModelsWire?): CurrentEffortOptions? {
    val model = currentModelRef(models)?.model ?: return null
    val reasoning = model.reasoning ?: return null
    if (reasoning.efforts.isEmpty()) return null
    return CurrentEffortOptions(reasoning.efforts, reasoning.defaultEffort)
}

/** 当前模型是否有可选强度档位（决定入口右区是否显示、右区是否可点开强度面板）。 */
internal fun currentModelHasEffortOptions(models: SessionModelsWire?): Boolean = currentModelEffortOptions(models) != null

/** 结构化标签：模型显示名 + 推理强度显示名（可空 = 未指定）。见 [modelEntryLabel] 的字符串拼装。 */
internal fun modelEntryParts(models: SessionModelsWire?): ModelEntryParts {
    val current = models?.current ?: return ModelEntryParts(MODEL_ENTRY_PLACEHOLDER, null)
    val ref = currentModelRef(models) ?: return ModelEntryParts(MODEL_ENTRY_PLACEHOLDER, null)
    val effort = current.reasoningEffort
        ?.let { eff -> ref.model.reasoning?.efforts?.firstOrNull { it.id == eff }?.name }
    return ModelEntryParts(ref.model.name, effort, hasEffortOptions = currentModelHasEffortOptions(models))
}

internal fun modelEntryLabel(models: SessionModelsWire?): String {
    val p = modelEntryParts(models)
    return if (p.effort != null) "${p.name} · ${p.effort}" else p.name
}

/**
 * 技能搜索过滤（可单测）：按 name/description（含可见的 whenToUse「适用」文案）做大小写不敏感子串匹配；
 * 空查询返回全量。contains 天然覆盖 startsWith（前缀也是子串），故统一用 contains。
 */
internal fun filterSkills(skills: List<SkillWire>, query: String): List<SkillWire> {
    val q = query.trim()
    if (q.isEmpty()) return skills
    return skills.filter { s ->
        s.name.contains(q, ignoreCase = true) ||
            s.description.contains(q, ignoreCase = true) ||
            s.whenToUse?.contains(q, ignoreCase = true) == true
    }
}

/**
 * 上下文占用分子（对齐桌面 ContextMeter usedTokens）：projectedTokens 优先，回退 pressureTokens；
 * 两者皆缺省时返回 null（服务端投影值，客户端零推算）。
 */
internal fun contextUsedTokens(usage: ContextUsageWire?): Long? {
    if (usage == null) return null
    return usage.projectedTokens ?: usage.pressureTokens
}

/**
 * 上下文 token 紧凑格式（对齐桌面 ContextMeter）：517 / 12.2K / 517K / 1.2M。
 * 刻意与 MetaFormat.formatTokens（小写 k）区分——桌面端后缀 K 大写，且不改动 MetaFormat 既有行为。
 */
internal fun formatContextTokens(n: Long): String {
    if (n < 0) return "0"
    if (n < 1000) return n.toString()
    if (n < 1_000_000) return compactContextToken(n, 1000, "K")
    return compactContextToken(n, 1_000_000, "M")
}

private fun compactContextToken(n: Long, div: Long, suffix: String): String {
    val whole = n / div
    val frac = (n % div) * 10 / div
    return if (frac == 0L) "${whole}$suffix" else "${whole}.${frac}$suffix"
}

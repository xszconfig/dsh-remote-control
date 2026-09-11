package com.daniel.dshremote

import com.daniel.dshremote.protocol.ContextUsageWire
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

/**
 * 终止/运行态判定信号（复用现状，不新增信号源）：会话 running 或等待模型中即视为运行中。
 * 对应 App.kt ConversationComposer 的 agentRunning（PRD 2.0 术语）。
 */
internal fun isAgentRunning(sessions: List<SessionSummary>, sessionId: String, modelWaitingSince: Long?): Boolean =
    sessions.firstOrNull { it.id == sessionId }?.status == "running" || modelWaitingSince != null

/**
 * 模型入口短标签：current 在目录组内 → 「provider 展示名 · 模型名」；否则回退占位。
 * 目录是 advisory：current 不在任何分组内时不合成过期行（对齐 DSH Web 语义）。
 */
internal fun modelEntryLabel(models: SessionModelsWire?): String {
    val current = models?.current ?: return MODEL_ENTRY_PLACEHOLDER
    val group = models.groups.firstOrNull { it.id == current.provider } ?: return MODEL_ENTRY_PLACEHOLDER
    val model = group.models.firstOrNull { it.id == current.model } ?: return MODEL_ENTRY_PLACEHOLDER
    return "${group.name} · ${model.name}"
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

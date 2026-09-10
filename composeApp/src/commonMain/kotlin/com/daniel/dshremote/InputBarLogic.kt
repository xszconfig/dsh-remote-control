package com.daniel.dshremote

import com.daniel.dshremote.protocol.SessionModelsWire
import com.daniel.dshremote.protocol.SessionSummary

/**
 * 输入区操作条纯函数（可单测）：终止两态判定 + 模型入口短标签。
 *
 * 铁律 6：这里只做「渲染所需」的纯变换，绝不做任何服务端投影之外的推算——
 * 上下文占用百分比直接用服务端下发的 [SessionModelsWire] 之外字段（contextUsage.percent），
 * 本文件不涉及任何占用计算。
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

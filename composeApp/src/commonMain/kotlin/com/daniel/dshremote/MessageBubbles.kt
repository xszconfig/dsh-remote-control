@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.daniel.dshremote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daniel.dshremote.protocol.CommandWire
import com.daniel.dshremote.protocol.EventProjection
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun EventBubble(e: EventProjection, allEvents: List<EventProjection>) {
    // 工具调用失败：同 callId 的结果带 toolError → 命令标红（与桌面端一致）
    val callFailed = e.type == "tool_call" && e.callId != null &&
        allEvents.any { it.type == "tool_result" && it.callId == e.callId && it.toolError == true }
    when (e.type) {
        "user_message" -> if (isInjectedUserMessage(e.source)) {
            // 注入的上下文/系统消息（AGENTS.md <system-reminder>、LSP 编译错误反馈、
            // 文件变更通知、cron、技能内容、压缩检查点、session 起始提醒等）——
            // 服务端投影已标 source=inject，渲染为弱化的「上下文」行，严禁用用户气泡
            // （铁律 6：分类在桥侧，客户端只渲染、不推算）。
            ContextRow(e)
        } else {
            Bubble(
                text = e.text ?: "",
                label = "你",
                ts = e.timestamp,
                alignEnd = true,
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        "assistant_message" -> Bubble(
            text = e.text ?: "",
            label = "Agent",
            ts = e.timestamp,
            alignEnd = false,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            markdown = true, // Agent 正文走 Markdown 渲染（代码块/表格/加粗等）
        )
        "tool_call" -> ToolCallCard(e, isError = callFailed)
        "tool_result" -> ToolResultCard(e)
        "think" -> ThinkCard(e)
        "command" -> CommandRow(e, allEvents)
        else -> Bubble(
            text = e.text ?: e.type,
            label = e.type,
            ts = e.timestamp,
            alignEnd = false,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 本地待发送消息气泡：用户气泡 + 时间行状态图标（Loading / ❗），回显到达后移除。 */
@Composable
internal fun PendingBubble(p: PendingMessage, onRetry: () -> Unit) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTimestamp(p.createdAt), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Spacer(Modifier.width(6.dp))
            Text("你", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = labelColor)
            if (p.status == PendingStatus.Sending || p.status == PendingStatus.Failed) {
                Spacer(Modifier.width(6.dp))
                PendingStatusIcon(p.status, onRetry)
            }
            if (!p.persisted) {
                // 落盘失败未持久化：与「发送失败 ❗」语义区分，⚠️ 只提示「本地未落盘，重启可能丢」
                Spacer(Modifier.width(6.dp))
                Text(
                    "⚠️",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusAmber,
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = 16.dp, bottomEnd = 4.dp,
            ),
        ) {
            Text(
                p.text.ifBlank { "…" },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** 待发送消息时间行状态图标：sending = 小 spinner；failed = 红色 ❗（点击重发）；sent = 不显示。 */
@Composable
internal fun PendingStatusIcon(status: PendingStatus, onRetry: (() -> Unit)?) {
    when (status) {
        PendingStatus.Sending -> CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
        )
        PendingStatus.Failed -> Text(
            "❗",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.clickable { onRetry?.invoke() },
        )
        PendingStatus.Sent -> Unit
    }
}

@Composable
internal fun Bubble(
    text: String,
    label: String,
    ts: Long,
    alignEnd: Boolean,
    container: Color,
    content: Color,
    labelColor: Color,
    markdown: Boolean = false,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (alignEnd) {
                Text(formatTimestamp(ts), style = MaterialTheme.typography.labelSmall, color = labelColor)
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = labelColor)
            } else {
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = labelColor)
                Spacer(Modifier.width(6.dp))
                Text(formatTimestamp(ts), style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
        }
        Spacer(Modifier.height(3.dp))
        // 长按复制：消息文本进剪贴板 + 长按触感反馈
        val clipboard = LocalClipboardManager.current
        val haptic = LocalHapticFeedback.current
        Surface(
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(AnnotatedString(text))
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            ),
            color = container,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (alignEnd) 16.dp else 4.dp,
                bottomEnd = if (alignEnd) 4.dp else 16.dp,
            ),
        ) {
            if (markdown) {
                // 开源 CommonMark 渲染（mikepenz/multiplatform-markdown-renderer，基于 JetBrains
                // CommonMark/GFM 解析）：代码块、表格、标题、加粗/斜体/行内代码、列表、引用等。
                // 字号：移动端档位显式映射（库默认 H1~H3 用 display 级 57/45/36sp，那是桌面大屏
                // 展示字号，手机上巨大——我们做的是手机 App，逐元素定号，不依赖库默认值）。
                Markdown(
                    // 表格前补空行：org.intellij.markdown 的 GFM 表格不支持打断段落，
                    // 缺空行会把表格塌成纯文本（见 MarkdownTableScroll.kt 的 normalizeMarkdownTables）。
                    content = normalizeMarkdownTables(text),
                    typography = markdownTypography(
                        h1 = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        h2 = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        h3 = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        h4 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        h5 = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        h6 = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        text = TextStyle(fontSize = 14.sp),
                        paragraph = TextStyle(fontSize = 14.sp),
                        ordered = TextStyle(fontSize = 14.sp),
                        bullet = TextStyle(fontSize = 14.sp),
                        list = TextStyle(fontSize = 14.sp),
                        code = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        inlineCode = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        quote = TextStyle(fontSize = 13.sp, fontStyle = FontStyle.Italic),
                        link = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
                    ),
                    colors = markdownColor(
                        text = content,
                        codeText = MarkdownCodeFg,
                        codeBackground = MarkdownCodeBg,
                        inlineCodeText = content,
                        inlineCodeBackground = content.copy(alpha = 0.14f),
                        linkText = AccentBlue,
                        tableText = content,
                        dividerColor = content.copy(alpha = 0.35f),
                    ),
                    // 表格用自定义组件：宽表格按真实内容宽度测量 + 横向滑动，
                    // 替换库默认的固定列宽 + 省略号截断（见 MarkdownTableScroll.kt）。
                    // 代码块用自定义组件：行号固定 + 长行不换行 + 代码区横向滑动（见 MarkdownCodeBlock.kt）。
                    components = markdownComponents(
                        table = { model -> ScrollableMarkdownTable(model) },
                        codeFence = { model -> MarkdownCodeFence(model.content, model.node) { code, _ -> ScrollableCodeBlock(code) } },
                        codeBlock = { model -> MarkdownCodeBlock(model.content, model.node) { code, _ -> ScrollableCodeBlock(code) } },
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                )
            } else {
                Text(
                    text.ifBlank { "…" },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                )
            }
        }
    }
}

@Composable
internal fun ToolCallCard(e: EventProjection, isError: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    // 与 dsh web 对齐的一行形态：[图标] 工具名 · 描述（Bash 的描述即命令文本）
    val icon = when {
        e.toolCard == "terminal" -> ">_"
        e.toolKind == "read" -> "📖"
        e.toolKind == "edit" -> "✏️"
        e.toolKind == "delete" -> "🗑"
        e.toolKind == "move" -> "📁"
        e.toolKind == "search" -> "🔍"
        e.toolKind == "execute" -> "⚡"
        e.toolKind == "fetch" -> "🌐"
        else -> "🛠"
    }
    val desc = e.toolDesc ?: ""
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).combinedClickable(
            onClick = { expanded = !expanded },
            onLongClick = {
                clipboard.setText(AnnotatedString(listOfNotNull(desc.ifBlank { null }, e.toolArgs).joinToString("\n")))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
        ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            // 失败的命令标红（与桌面端一致），正常命令保持青绿容器
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    icon,
                    fontSize = 13.sp,
                    fontFamily = if (e.toolCard == "terminal") FontFamily.Monospace else null,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    e.toolName ?: "tool",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (desc.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = (if (isError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (e.toolCard == "terminal") FontFamily.Monospace else null,
                        // 折叠时两行（原来一行截太短）；展开时全文
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Description 与时间戳之间留出空隙（不再用 weight 挤到最右）
                Spacer(Modifier.width(10.dp))
                Text(
                    formatTimestamp(e.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = (if (isError) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.7f),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (expanded) "▲" else "▼", fontSize = 10.sp, color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
            }
            AnimatedVisibility(visible = expanded) {
                if (e.diffs != null && e.diffs.isNotEmpty()) {
                    // Edit/Write 等文件变更：展开显示 Code Diff（红删绿增，与 DSH Web 对齐）
                    Column(Modifier.padding(top = 8.dp)) {
                        CodeDiffBlock(e.diffs)
                    }
                } else {
                    Text(
                        e.toolArgs ?: "(no args)",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

/** 思考流式实时行：一行持续刷新（与 DeepSeek Web 的 thinking 流式体验对齐）。 */
@Composable
internal fun LiveThinkRow(text: String) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("💭", fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                "思考中",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = AccentBlue,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "·",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Think（思考）步骤：一行浓缩展示（与 dsh web 对齐），长按复制全文。 */
@Composable
internal fun ThinkCard(e: EventProjection) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).combinedClickable(
            onClick = {},
            onLongClick = {
                clipboard.setText(AnnotatedString(e.text ?: ""))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
        ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("💭", fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                "思考",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "·",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                e.text ?: "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatTimestamp(e.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * 注入的上下文/系统消息行（AGENTS.md <system-reminder>、LSP 编译错误反馈、
 * 文件变更通知、cron、技能内容、压缩检查点、session 起始提醒等）。
 * 与用户消息严格区分：左侧弱化卡片 + 「上下文」标签，默认折叠单行，点击展开全文。
 */
@Composable
internal fun ContextRow(e: EventProjection) {
    val text = e.text ?: ""
    var expanded by remember(e.seq) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).combinedClickable(
            onClick = { expanded = !expanded },
            onLongClick = {
                clipboard.setText(AnnotatedString(text))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
        ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧩", fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "上下文",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTimestamp(e.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "收起 ▲" else "展开 ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentBlue,
                )
            }
            Text(
                text,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 斜杠命令候选弹窗：输入 "/" 时列出该会话可用的命令（名称 + 一句话说明）。
 * 数据来自服务端注册表（与 Web composer 同源），按已输入前缀过滤；
 * 点击行填入 "/命令名 "（尾空格就绪输入参数）并收起弹窗。
 * 移动端形态：贴在输入框上方、限高可滚动、每行名称 + 单行省略说明。
 */
@Composable
internal fun CommandCandidatePopup(candidates: List<CommandWire>, onPick: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(max = 208.dp),
        ) {
            candidates.forEachIndexed { index, cmd ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(cmd.name) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "/${cmd.name}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentBlue,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        cmd.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (index != candidates.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 斜杠命令行：/compact 等命令的执行与结果（与 Web composer 同一条服务端执行链）。
 * 协议上 command/run 与 command/done 是两行（同一 commandId），这里合并成一行呈现：
 * - running 行若存在后续 done 行 → 本行折叠（done 行已由桥补齐命令名，单行展示结果）；
 * - 只有 running 没有 done（执行中/异常中断）→ 显示"执行中…"；
 * - error 行（未注册命令等准入失败，瞬时）→ 错误样式。
 */
@Composable
internal fun CommandRow(e: EventProjection, allEvents: List<EventProjection>) {
    if (e.commandStatus == "running" && e.commandId != null) {
        val done = allEvents.lastOrNull {
            it.type == "command" && it.commandId == e.commandId && it.commandStatus == "done"
        }
        if (done != null) return // 结果由 done 行呈现，本行折叠
    }
    val isError = e.commandStatus == "error" || (e.commandStatus == "done" && e.commandOk != true)
    val icon = when {
        isError -> "⚠️"
        e.commandStatus == "running" -> "⏳"
        else -> "✅"
    }
    val accent = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val name = e.commandName?.let { "/$it" } ?: "命令"
    val args = e.commandArgs?.takeIf { it.isNotBlank() }
    val label = listOfNotNull(name, args).joinToString(" ")
    val body = when {
        e.commandStatus == "running" -> null
        e.text.isNullOrBlank() -> if (isError) "执行失败" else "完成"
        else -> e.text
    }
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (e.commandStatus == "running") {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "执行中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                } else {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatTimestamp(e.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            if (body != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent.copy(alpha = 0.9f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun ToolResultCard(e: EventProjection) {
    val isError = e.toolError == true
    val result = e.toolResult ?: "(empty)"
    var expanded by remember(e.seq) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).combinedClickable(
            onClick = { expanded = !expanded },
            onLongClick = {
                clipboard.setText(AnnotatedString(result))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
        ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isError) "⚠️ 出错" else "✓ 结果", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(
                    formatTimestamp(e.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "收起 ▲" else "展开 ▼（${result.length} 字符）",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentBlue,
                )
            }
            Text(
                result,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface,
                // 默认折叠成一行，点击展开全文（用户要求）
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

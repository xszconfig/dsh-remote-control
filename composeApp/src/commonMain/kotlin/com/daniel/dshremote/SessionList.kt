package com.daniel.dshremote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daniel.dshremote.protocol.SessionSummary
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// ---- 会话列表（按所选工作区过滤） ----

@Composable
internal fun SessionList(client: BridgeClient, state: SessionUiState) {
    val selected = state.selectedWorkspaceId
    // 只展示顶层（用户手动创建）会话；子代理会话挂在主会话里经右上角入口查看
    val visible = state.sessions.filter { s ->
        s.parentSessionId == null && when (selected) {
            null -> true
            UNGROUPED_KEY -> s.workspaceId == null
            else -> s.workspaceId == selected
        }
    }
    // 每个会话的后代总数（含孙代，沿 parentSessionId 链递归累加，铁律 6 服务端投影）；
    // 一次性算好，卡片徽标只查表，避免在 LazyColumn 逐项 O(n) 重复建树。
    val descendants = remember(state.sessions) { descendantCounts(state.sessions) }
    val title = when (selected) {
        null -> "全部会话"
        UNGROUPED_KEY -> "未分组"
        else -> state.workspaces.firstOrNull { it.id == selected }?.title ?: "会话"
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${visible.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("该工作区暂无会话", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { s ->
                    SessionCard(
                        s = s,
                        running = s.status == "running",
                        // 副标题只在「全部会话」视图显示所属工作区名（区分来源）；
                        // 已进入某个工作区时不再显示 cwd——上下文已明确
                        showWorkspace = selected == null && s.workspaceId != null,
                        workspaceTitle = state.workspaces.firstOrNull { it.id == s.workspaceId }?.title,
                        // 挂载的子代理后代总数（含孙代，对齐 DSH Web 的 🤖N 语义），
                        // 与服务端 live 计数无关
                        subagentCount = descendants[s.id] ?: 0,
                        queuedCount = state.queuedCounts[s.id] ?: 0,
                        onClick = { ConnLog.info("ACTION", "会话点击 id=${s.id} 标题=${sessionName(s)}"); client.openSession(s.id) },
                        onInterrupt = { mode -> client.interrupt(s.id, mode) },
                    )
                }
            }
        }
    }
}

/**
 * 中断确认弹框：仅当目标会话存在排队消息时出现。
 * 两个动作：终止并清空排队（clear）/ 仅终止循环不清空（keep），另加取消。
 */
@Composable
internal fun InterruptConfirmDialog(
    sessionId: String,
    queuedCount: Int,
    onClear: () -> Unit,
    onKeep: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("中断确认") },
        text = { Text("当前会话有 $queuedCount 条排队消息。终止当前循环时是否一并清空排队？") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(
                    onClick = { ConnLog.info("ACTION", "中断-清空排队 sessionId=$sessionId"); onClear() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("终止当前循环并清空排队消息") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { ConnLog.info("ACTION", "中断-仅终止 sessionId=$sessionId"); onKeep() },
                ) { Text("仅终止循环，不清空消息") }
            }
        },
        dismissButton = {
            TextButton(onClick = { ConnLog.info("ACTION", "中断-取消 sessionId=$sessionId"); onDismiss() }) { Text("取消") }
        },
    )
}

@Composable
internal fun SessionCard(
    s: SessionSummary,
    running: Boolean,
    showWorkspace: Boolean,
    workspaceTitle: String?,
    subagentCount: Int,
    queuedCount: Int,
    onClick: () -> Unit,
    onInterrupt: (mode: String) -> Unit,
) {
    // 副标题：仅「全部会话」视图显示工作区名（区分会话来源）；
    // 进入具体工作区后不再显示 cwd/工作区信息；挂载子代理时附 🤖N。
    val subtitle = buildString {
        if (showWorkspace && workspaceTitle != null) append("📁 $workspaceTitle")
        if (subagentCount > 0) {
            if (isNotEmpty()) append(" · ")
            append("🤖$subagentCount")
        }
    }
    var showInterruptConfirm by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (running) StatusGreen else StatusGray),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    sessionName(s),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTimestamp(s.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (subtitle.isNotEmpty() || running) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (running) {
                        OutlinedButton(
                            onClick = {
                                if (queuedCount > 0) {
                                    ConnLog.info("ACTION", "中断确认弹框出现 sessionId=${s.id} queued=$queuedCount")
                                    showInterruptConfirm = true
                                } else {
                                    onInterrupt("clear")
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = ButtonDefaults.ContentPadding,
                        ) {
                            Text("中断", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    if (showInterruptConfirm) {
        InterruptConfirmDialog(
            sessionId = s.id,
            queuedCount = queuedCount,
            onClear = { onInterrupt("clear"); showInterruptConfirm = false },
            onKeep = { onInterrupt("keep"); showInterruptConfirm = false },
            onDismiss = { showInterruptConfirm = false },
        )
    }
}

/** 会话显示名：持久化标题 → cwd basename → id 前缀（与桌面端一致）。 */
internal fun sessionName(s: SessionSummary): String =
    s.name?.takeIf { it.isNotBlank() }
        ?: basenameOf(s.cwd).ifBlank { s.id.take(12) }

internal fun basenameOf(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

/**
 * 子代理列表副标题：状态标志（空闲/运行中）+ 元信息（最后消息时间 / 运行时长 / token），
 * 各段用 · 分隔，过长可换行成多段。状态段保留原配色（运行中绿色），元信息段用次要色。
 * 所有元信息均来自服务端投影字段（铁律 6：客户端不做本地推算）。
 */
@Composable
internal fun SubagentSubtitle(sub: SessionSummary) {
    val running = sub.status == "running"
    val status = if (running) "运行中" else "空闲"
    val meta = subagentMetaSegments(sub, nowMillis()).joinToString(" · ")
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = if (running) StatusGreen else secondary)) {
                append(status)
            }
            if (meta.isNotEmpty()) {
                withStyle(SpanStyle(color = secondary)) {
                    append(" · ")
                    append(meta)
                }
            }
        },
        style = MaterialTheme.typography.labelSmall,
    )
}

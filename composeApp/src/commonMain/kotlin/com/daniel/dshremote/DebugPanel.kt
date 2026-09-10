package com.daniel.dshremote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daniel.dshremote.protocol.ServerEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// ================= 开发面板 · 调试 Tab =================

/** 调试面板内容：断点 / 调用栈 / 变量 / 输出（小屏紧凑，中文文案）。 */
@Composable
internal fun DebugPanelContent(
    view: SessionViewState,
    client: BridgeClient,
    sessionId: String,
) {
    val debug = view.debug
    if (debug == null) {
        Text(
            "当前无调试会话",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column {
        // 程序路径（单行省略）
        Text(
            "程序：${debug.program}",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 断点
        if (debug.breakpoints.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("断点", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            debug.breakpoints.forEach { bp ->
                Text(
                    "● ${bp.path.substringAfterLast('/')} : ${bp.line}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val paused = debug.paused
        if (paused == null) {
            // 未暂停：仅状态文本
            when (debug.state) {
                "starting" -> Text("启动中…", style = MaterialTheme.typography.bodyMedium)
                "running" -> Text("运行中…", style = MaterialTheme.typography.bodyMedium, color = StatusGreen)
                else -> Text(
                    "已停止" + (debug.error?.let { "：$it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (debug.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            PausedDebugSection(
                paused = paused,
                debugVars = view.debugVars,
                client = client,
                sessionId = sessionId,
            )
        }
        // 输出区（暂停中也显示，放面板最底部）
        if (view.debugOutput.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "输出（最近 ${view.debugOutput.size} 行）",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    view.debugOutput.takeLast(40).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 暂停态的调试详情：状态行 + 调用栈 + 变量 + 操作按钮。 */
@Composable
internal fun PausedDebugSection(
    paused: ServerEvent.DebugPausedWire,
    debugVars: Map<String, List<ServerEvent.DebugVariableWire>>,
    client: BridgeClient,
    sessionId: String,
) {
    // 选中的调用栈帧（默认第一帧；paused 快照更新时复位）
    var selectedFrameId by remember(paused) { mutableStateOf(paused.frames.firstOrNull()?.id) }
    val selectedFrame = paused.frames.firstOrNull { it.id == selectedFrameId }
        ?: paused.frames.firstOrNull()

    Column {
        // 状态行：⏸ 文件名:行号
        val stoppedAt = paused.stoppedAt
        Text(
            stoppedAt?.let { "⏸ ${it.path.substringAfterLast('/')}:${it.line}" } ?: "⏸ 已暂停",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = StatusAmber,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DebugCallStack(
            frames = paused.frames,
            selectedFrame = selectedFrame,
            onSelectFrame = { frame ->
                if (frame.id != selectedFrameId) {
                    selectedFrameId = frame.id
                    // 切换帧：自动拉取该帧每个 scope（跳过全局）的变量
                    frame.scopes.filter { it.name != "全局" }.forEach { scope ->
                        client.sendDebugCommand(sessionId, "variables", scope.variablesReference)
                    }
                }
            },
        )
        DebugScopesVariables(
            selectedFrame = selectedFrame,
            debugVars = debugVars,
            client = client,
            sessionId = sessionId,
        )
        // 操作按钮：继续 / 单步 / 跳出 / 停止
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { client.sendDebugCommand(sessionId, "resume") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("继续 ▶", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = { client.sendDebugCommand(sessionId, "step") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("单步 ⤵", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = { client.sendDebugCommand(sessionId, "step_out") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("跳出 ⤴", style = MaterialTheme.typography.labelMedium)
            }
        }
        TextButton(
            onClick = { client.sendDebugCommand(sessionId, "stop") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("停止调试", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 暂停态调用栈（最多 6 帧），点击帧切换并预拉变量。 */
@Composable
internal fun DebugCallStack(
    frames: List<ServerEvent.DebugFrameWire>,
    selectedFrame: ServerEvent.DebugFrameWire?,
    onSelectFrame: (ServerEvent.DebugFrameWire) -> Unit,
) {
    if (frames.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("调用栈", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        frames.take(6).forEach { frame ->
            val isSelected = frame.id == selectedFrame?.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelectFrame(frame) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (isSelected) "▸ " else "　",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${frame.name} · ${frame.path.substringAfterLast('/')}:${frame.line}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 暂停态变量：选中帧的 scopes（跳过全局），可展开变量节点。 */
@Composable
internal fun DebugScopesVariables(
    selectedFrame: ServerEvent.DebugFrameWire?,
    debugVars: Map<String, List<ServerEvent.DebugVariableWire>>,
    client: BridgeClient,
    sessionId: String,
) {
    val scopes = selectedFrame?.scopes?.filter { it.name != "全局" }.orEmpty()
    if (scopes.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("变量", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        scopes.forEach { scope ->
            val loaded = debugVars.containsKey(scope.variablesReference)
            Text(
                (if (loaded) "▾ " else "▸ ") + scope.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = AccentBlue,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { client.sendDebugCommand(sessionId, "variables", scope.variablesReference) }
                    .padding(vertical = 4.dp),
            )
            if (loaded) {
                debugVars[scope.variablesReference].orEmpty().forEach { v ->
                    VariableNode(
                        v = v,
                        depth = 0,
                        vars = debugVars,
                        onExpand = { ref -> client.sendDebugCommand(sessionId, "variables", ref) },
                    )
                }
            }
        }
    }
}

/** 递归渲染单个调试变量；hasChildren 可展开，子级按 depth 缩进显示。 */
@Composable
internal fun VariableNode(
    v: ServerEvent.DebugVariableWire,
    depth: Int,
    vars: Map<String, List<ServerEvent.DebugVariableWire>>,
    onExpand: (String) -> Unit,
) {
    val children = vars[v.variablesReference]
    Column(Modifier.fillMaxWidth().padding(start = (depth * 14).dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = v.hasChildren) { onExpand(v.variablesReference) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (v.hasChildren) {
                Text("▸", style = MaterialTheme.typography.labelSmall, color = AccentBlue)
                Spacer(Modifier.width(4.dp))
            }
            Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(
                v.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(" = ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                v.value,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (children != null) {
            children.forEach { child ->
                VariableNode(child, depth + 1, vars, onExpand)
            }
        }
    }
}

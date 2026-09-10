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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daniel.dshremote.protocol.ServerLogEntry
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// ================= 连接日志页（诊断基础组件） =================

@Composable
internal fun LogScreen(client: BridgeClient, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(0) } // 0=本机 1=服务端
    var levelFilter by remember { mutableStateOf<ConnLogLevel?>(null) }
    val localLogs by ConnLog.flow.collectAsState()
    var serverLogs by remember { mutableStateOf<List<ServerLogEntry>>(emptyList()) }
    var loadingServer by remember { mutableStateOf(false) }
    var lastRefresh by remember { mutableStateOf(0L) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val filter = levelFilter
    val shownLocal = if (filter == null) localLogs else localLogs.filter { it.level == filter }
    val shownServer = if (filter == null) serverLogs else serverLogs.filter { it.level == filter.label.lowercase() }

    Column(Modifier.fillMaxSize().statusBarsPadding().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("← 返回", fontWeight = FontWeight.SemiBold) }
            Text("连接日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val text = if (tab == 0) {
                    shownLocal.joinToString("\n") { "[${it.level.label}][${it.tag}] ${formatClock(it.ts)} ${it.message}" }
                } else {
                    shownServer.joinToString("\n") { "[${it.level}][${it.tag}] ${formatClock(it.ts)} ${it.message}" }
                }
                clipboard.setText(AnnotatedString(text))
                ConnLog.info("LOG", "日志已复制到剪贴板（${if (tab == 0) shownLocal.size else shownServer.size} 条）")
            }) { Text("复制") }
            if (tab == 0) {
                TextButton(onClick = { ConnLog.clear() }) { Text("清空") }
            } else {
                TextButton(
                    enabled = !loadingServer,
                    onClick = {
                        loadingServer = true
                        scope.launch {
                            val t0 = nowMillis()
                            serverLogs = client.loadServerLogs() ?: emptyList()
                            lastRefresh = nowMillis()
                            loadingServer = false
                            ConnLog.info("LOG", "已刷新服务端日志（${serverLogs.size} 条，耗时 ${nowMillis() - t0}ms）")
                        }
                    },
                ) { Text(if (loadingServer) "刷新中…" else "刷新") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(tab == 0, "本机") { ConnLog.info("ACTION", "日志页切换 tab=本机"); tab = 0 }
            Spacer(Modifier.width(6.dp))
            FilterChip(tab == 1, "服务端") {
                ConnLog.info("ACTION", "日志页切换 tab=服务端")
                tab = 1
                if (serverLogs.isEmpty() && !loadingServer) {
                    loadingServer = true
                    scope.launch {
                        val t0 = nowMillis()
                        serverLogs = client.loadServerLogs() ?: emptyList()
                        lastRefresh = nowMillis()
                        loadingServer = false
                        ConnLog.info("LOG", "已加载服务端日志（${serverLogs.size} 条，耗时 ${nowMillis() - t0}ms）")
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            FilterChip(levelFilter == null, "全部") { levelFilter = null }
            ConnLogLevel.entries.forEach { lv ->
                Spacer(Modifier.width(4.dp))
                FilterChip(levelFilter == lv, lv.label) { levelFilter = lv }
            }
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (tab == 0) {
            LocalLogList(shownLocal)
        } else {
            ServerLogList(
                shownServer = shownServer,
                loadingServer = loadingServer,
                lastRefresh = lastRefresh,
                onLoadOnce = {
                    loadingServer = true
                    scope.launch {
                        val t0 = nowMillis()
                        serverLogs = client.loadServerLogs() ?: emptyList()
                        loadingServer = false
                        ConnLog.info("LOG", "已拉取服务端日志（${serverLogs.size} 条，耗时 ${nowMillis() - t0}ms）")
                    }
                },
            )
        }
    }
}

/** 本机日志列表（空态提示 / 逆序 LazyColumn）。 */
@Composable
internal fun LocalLogList(shownLocal: List<ConnLogEntry>) {
    if (shownLocal.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无本地日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            items(shownLocal.asReversed(), key = { "l${it.seq}" }) { e ->
                LogRow(
                    time = formatClock(e.ts),
                    level = e.level.label,
                    levelColor = levelColor(e.level),
                    tag = e.tag,
                    message = e.message,
                )
            }
        }
    }
}

/** 服务端日志列表（加载中 / 空态 / 逆序 LazyColumn）。 */
@Composable
internal fun ServerLogList(
    shownServer: List<ServerLogEntry>,
    loadingServer: Boolean,
    lastRefresh: Long,
    onLoadOnce: () -> Unit,
) {
    if (loadingServer && shownServer.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
    } else if (shownServer.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂无服务端日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (lastRefresh == 0L) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onLoadOnce) { Text("拉取一次") }
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            items(shownServer.asReversed(), key = { "s${it.seq}" }) { e ->
                LogRow(
                    time = formatClock(e.ts),
                    level = e.level.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    levelColor = levelColorOf(e.level),
                    tag = e.tag,
                    message = e.message,
                )
            }
        }
    }
}

@Composable
internal fun FilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
internal fun LogRow(time: String, level: String, levelColor: Color, tag: String, message: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(time, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(level, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = levelColor)
        Spacer(Modifier.width(6.dp))
        Text("[$tag]", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = AccentBlue)
        Spacer(Modifier.width(6.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun levelColor(level: ConnLogLevel): Color = when (level) {
    ConnLogLevel.DEBUG -> LocalColorTokens.current.logDebug
    ConnLogLevel.INFO -> LocalColorTokens.current.logInfo
    ConnLogLevel.WARN -> LocalColorTokens.current.logWarn
    ConnLogLevel.ERROR -> LocalColorTokens.current.logError
}

@Composable
internal fun levelColorOf(level: String): Color = when (level) {
    "warn" -> LocalColorTokens.current.logWarn
    "error" -> LocalColorTokens.current.logError
    "info" -> LocalColorTokens.current.logInfo
    else -> LocalColorTokens.current.logDebug
}

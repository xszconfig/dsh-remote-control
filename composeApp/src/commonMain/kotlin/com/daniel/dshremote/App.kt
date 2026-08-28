package com.daniel.dshremote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daniel.dshremote.protocol.ApprovalDecision
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.DeviceStatus
import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.StoredDevice
import kotlinx.coroutines.launch

// ================= 根 =================

@Composable
fun App(client: BridgeClient) {
    val state by client.state.collectAsState()
    DshTheme {
        when {
            state.scanning -> QrScanner(
                onScanned = { client.onQrScanned(it) },
                onCancel = { client.stopScan() },
            )
            state.connection == ConnectionState.Connecting -> ConnectingScreen(client, state)
            state.connection == ConnectionState.Connected -> MainScreen(client, state)
            else -> LandingScreen(client, state)
        }
    }
}

// ================= 首页（未连接） =================

@Composable
private fun LandingScreen(client: BridgeClient, state: BridgeUiState) {
    var showManual by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("192.168.3.76") }
    var port by remember { mutableStateOf("3080") }
    var token by remember { mutableStateOf("") }
    var forgetTarget by remember { mutableStateOf<StoredDevice?>(null) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        // 品牌头部
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("dsh", fontWeight = FontWeight.Black, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("dsh Remote Control", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "手机遥控桌面端 DeepSeek Harness",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 连接错误提示
        if (state.connection == ConnectionState.Error) {
            Spacer(Modifier.height(14.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "连接失败：${state.connectionDetail}",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("设备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                "${state.devices.size} 台 · 已连接过的会自动记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        // 设备列表
        if (state.devices.isEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📱", fontSize = 28.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("还没有连接过的设备", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "扫码或手动连接一次，之后就会出现在这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(
                    state.devices.sortedWith(
                        compareByDescending<StoredDevice> { statusOf(state, it) == DeviceStatus.Online }
                            .thenByDescending { it.lastSeenAt },
                    ),
                    key = { deviceKey(it) },
                ) { d ->
                    DeviceCard(
                        device = d,
                        status = statusOf(state, d),
                        onClick = { client.connectDevice(d) },
                        onForget = { forgetTarget = d },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 手动连接表单
        AnimatedVisibility(visible = showManual) {
            Column(Modifier.padding(bottom = 8.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("Host") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = port, onValueChange = { port = it },
                        label = { Text("Port") },
                        modifier = Modifier.width(120.dp),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = token, onValueChange = { token = it },
                        label = { Text("Token（可选）") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { client.connectManual(host, port.toIntOrNull() ?: 3080, token.ifBlank { null }) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("连接", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // 底部操作
        Button(
            onClick = { client.startScan() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("📷  扫码连接", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { showManual = !showManual }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showManual) "收起手动连接" else "手动连接")
        }
        Spacer(Modifier.height(10.dp))
    }

    // 忘记设备确认
    forgetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("忘记这台设备？") },
            text = { Text("${target.name}（${target.host}:${target.port}）将从列表移除，桌面端也会撤销它的配对凭据。") },
            confirmButton = {
                Button(
                    onClick = { client.forgetDevice(target); forgetTarget = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("忘记") }
            },
            dismissButton = { TextButton(onClick = { forgetTarget = null }) { Text("取消") } },
        )
    }
}

private fun statusOf(state: BridgeUiState, device: StoredDevice): DeviceStatus =
    state.deviceStatuses[deviceKey(device)] ?: DeviceStatus.Checking

@Composable
private fun DeviceCard(
    device: StoredDevice,
    status: DeviceStatus,
    onClick: () -> Unit,
    onForget: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).clip(CircleShape)
                    .background(
                        when (status) {
                            DeviceStatus.Online -> StatusGreen
                            DeviceStatus.Offline -> StatusGray
                            DeviceStatus.Changed -> StatusOrange
                            DeviceStatus.Checking -> StatusAmber
                        },
                    ),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${device.host}:${device.port} · ${relativeTime(device.lastSeenAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    statusLabel(status),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (status) {
                        DeviceStatus.Online -> StatusGreen
                        DeviceStatus.Changed -> StatusOrange
                        DeviceStatus.Checking -> StatusAmber
                        DeviceStatus.Offline -> StatusGray
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            if (status == DeviceStatus.Checking) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    "连接 ⟶",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onForget) {
                Text("🗑", fontSize = 14.sp)
            }
        }
    }
}

private fun statusLabel(status: DeviceStatus): String = when (status) {
    DeviceStatus.Online -> "在线 · DSH Web 运行中"
    DeviceStatus.Checking -> "检测中…"
    DeviceStatus.Changed -> "在线 · 设备已更换（点击重连）"
    DeviceStatus.Offline -> "离线 · 无法探测到 DSH Web"
}

// ================= 连接中 =================

@Composable
private fun ConnectingScreen(client: BridgeClient, state: BridgeUiState) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(32.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("正在连接…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            state.connectionDetail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = { client.disconnect() }, shape = RoundedCornerShape(14.dp)) {
            Text("取消")
        }
    }
}

// ================= 主界面（已连接） =================

@Composable
private fun MainScreen(client: BridgeClient, state: BridgeUiState) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            WorkspaceDrawer(
                client = client,
                state = state,
                onSelect = { id ->
                    client.selectWorkspace(id)
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(
                client = client,
                state = state,
                onMenu = { scope.launch { drawerState.open() } },
            )
            state.errors.lastOrNull()?.let { lastError ->
                ErrorBanner(
                    message = lastError,
                    more = state.errors.size - 1,
                    onDismiss = { client.dismissErrors() },
                )
            }
            when (val sid = state.currentSessionId) {
                null -> SessionList(client, state)
                else -> Conversation(client, state, sid)
            }
        }
    }
    val approval = state.approvals.firstOrNull()
    if (approval != null) {
        ApprovalDialog(approval, onDecide = { d -> client.approve(approval.approvalId, d) })
    }}

// ---- 错误横幅 ----

@Composable
private fun ErrorBanner(message: String, more: Int, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚠️", fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (more > 0) "$message（还有 $more 条）" else message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onDismiss) { Text("✕", color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

// ---- 侧边栏：工作区菜单 ----

@Composable
private fun WorkspaceDrawer(
    client: BridgeClient,
    state: BridgeUiState,
    onSelect: (String?) -> Unit,
) {
    val ungrouped = state.sessions.count { it.workspaceId == null }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "工作区",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            DrawerEntry(
                label = "全部会话",
                badge = state.sessions.size,
                icon = "🗂",
                selected = state.selectedWorkspaceId == null,
                onClick = { onSelect(null) },
            )
            state.workspaces.forEach { w ->
                DrawerEntry(
                    label = w.title,
                    badge = w.sessionCount,
                    icon = "📁",
                    selected = state.selectedWorkspaceId == w.id,
                    onClick = { onSelect(w.id) },
                )
            }
            if (ungrouped > 0) {
                DrawerEntry(
                    label = "未分组",
                    badge = ungrouped,
                    icon = "📄",
                    selected = state.selectedWorkspaceId == UNGROUPED_KEY,
                    onClick = { onSelect(UNGROUPED_KEY) },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    state.connectedDevice?.name ?: "已连接",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    state.connectedDevice?.let { "${it.host}:${it.port}" } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DrawerEntry(
    label: String,
    badge: Int,
    icon: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        icon = { Text(icon, fontSize = 16.sp) },
        badge = { Text("$badge", style = MaterialTheme.typography.labelSmall) },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

// ---- 顶栏 ----

@Composable
private fun TopBar(client: BridgeClient, state: BridgeUiState, onMenu: () -> Unit) {
    val session = state.currentSessionId?.let { sid ->
        state.sessions.firstOrNull { it.id == sid }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (session != null) {
                TextButton(onClick = { client.closeSession() }) { Text("←", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            } else {
                TextButton(onClick = onMenu) { Text("☰", fontSize = 18.sp) }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session != null) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (session.status == "running") StatusGreen else StatusGray))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            sessionName(session),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(StatusGreen))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            state.connectedDevice?.name ?: "已连接",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    when {
                        session != null -> if (session.status == "running") "运行中" else "空闲"
                        else -> state.connectedDevice?.let { "${it.host}:${it.port} · ${state.sessions.size} 会话" } ?: ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { client.disconnect() }) {
                Text("断开", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// ---- 会话列表（按所选工作区过滤） ----

@Composable
private fun SessionList(client: BridgeClient, state: BridgeUiState) {
    val selected = state.selectedWorkspaceId
    val visible = state.sessions.filter { s ->
        when (selected) {
            null -> true
            UNGROUPED_KEY -> s.workspaceId == null
            else -> s.workspaceId == selected
        }
    }
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
                        showWorkspace = selected == null && s.workspaceId != null,
                        workspaceTitle = state.workspaces.firstOrNull { it.id == s.workspaceId }?.title,
                        onClick = { client.openSession(s.id) },
                        onInterrupt = { client.interrupt(s.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    s: SessionSummary,
    running: Boolean,
    showWorkspace: Boolean,
    workspaceTitle: String?,
    onClick: () -> Unit,
    onInterrupt: () -> Unit,
) {
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
                    relativeTime(s.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        if (showWorkspace && workspaceTitle != null) append("📁 $workspaceTitle · ")
                        if (s.cwd.isNotBlank()) append(basenameOf(s.cwd))
                        if (s.subagentCount > 0) append(" · 🤖${s.subagentCount}")
                    }.ifBlank { "(no cwd)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (running) {
                    OutlinedButton(
                        onClick = onInterrupt,
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

/** 会话显示名：持久化标题 → cwd basename → id 前缀（与桌面端一致）。 */
private fun sessionName(s: SessionSummary): String =
    s.name?.takeIf { it.isNotBlank() }
        ?: basenameOf(s.cwd).ifBlank { s.id.take(12) }

private fun basenameOf(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

// ================= 会话详情 =================

@Composable
private fun Conversation(client: BridgeClient, state: BridgeUiState, sessionId: String) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        if (state.events.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("💬", fontSize = 30.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("暂无事件，发条指令试试", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                reverseLayout = true,
            ) {
                items(state.events.asReversed(), key = { "${it.seq}-${it.type}" }) { e ->
                    EventBubble(e)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("发指令给 Agent…") },
                shape = RoundedCornerShape(22.dp),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        client.sendMessage(text)
                        input = ""
                    }
                },
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text("➤", fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun EventBubble(e: EventProjection) {
    when (e.type) {
        "user_message" -> Bubble(
            text = e.text ?: "",
            label = "你",
            ts = e.timestamp,
            alignEnd = true,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            labelColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
        )
        "assistant_message" -> Bubble(
            text = e.text ?: "",
            label = "Agent",
            ts = e.timestamp,
            alignEnd = false,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        "tool_call" -> ToolCallCard(e)
        "tool_result" -> ToolResultCard(e)
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

@Composable
private fun Bubble(
    text: String,
    label: String,
    ts: Long,
    alignEnd: Boolean,
    container: Color,
    content: Color,
    labelColor: Color,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (alignEnd) {
                Text(formatClock(ts), style = MaterialTheme.typography.labelSmall, color = labelColor)
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = labelColor)
            } else {
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = labelColor)
                Spacer(Modifier.width(6.dp))
                Text(formatClock(ts), style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
        }
        Spacer(Modifier.height(3.dp))
        Surface(
            color = container,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (alignEnd) 16.dp else 4.dp,
                bottomEnd = if (alignEnd) 4.dp else 16.dp,
            ),
        ) {
            Text(
                text.ifBlank { "…" },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
        }
    }
}

@Composable
private fun ToolCallCard(e: EventProjection) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🛠", fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(e.toolName ?: "tool", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    formatClock(e.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (expanded) "▲" else "▼", fontSize = 10.sp)
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    e.toolArgs ?: "(no args)",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ToolResultCard(e: EventProjection) {
    val isError = e.toolError == true
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                    formatClock(e.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val result = e.toolResult ?: "(empty)"
            Text(
                result,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = if (result.length > 400) 8 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ApprovalDialog(approval: ApprovalRequestWire, onDecide: (ApprovalDecision) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDecide(ApprovalDecision.Rejected) },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔐", fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text("需要审批", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text(
                    approval.toolName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                if (!approval.reason.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        approval.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onDecide(ApprovalDecision.AllowedOnce) }, shape = RoundedCornerShape(12.dp)) {
                Text("允许一次")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { onDecide(ApprovalDecision.Rejected) }, shape = RoundedCornerShape(12.dp)) {
                Text("拒绝")
            }
        },
    )
}

// ================= 工具 =================
// 时间格式化（formatClock / relativeTime）与 nowMillis 见 TimeFormat.kt


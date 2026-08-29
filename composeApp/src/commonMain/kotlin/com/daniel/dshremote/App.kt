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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.daniel.dshremote.protocol.QuestionAnswerItemWire
import com.daniel.dshremote.protocol.QuestionRequestWire
import kotlinx.coroutines.launch

// ================= 根 =================

@Composable
fun App(client: BridgeClient) {
    val scanning by client.scanning.collectAsState()
    val conn by client.connection.info.collectAsState()
    val devices by client.devices.state.collectAsState()
    val session by client.session.collectAsState()
    val reconnecting by client.reconnecting.collectAsState()
    val reconnectStatus by client.reconnectStatus.collectAsState()
    DshTheme {
        when {
            scanning -> QrScanner(
                onScanned = { client.onQrScanned(it) },
                onCancel = { client.stopScan() },
            )
            // 重连等待/重试期间保留会话界面，只加横幅提示
            conn.state == ConnectionState.Connected || reconnecting ->
                MainScreen(client, session, reconnecting, reconnectStatus)
            conn.state == ConnectionState.Connecting -> ConnectingScreen(client, conn)
            else -> LandingScreen(client, conn, devices)
        }
        // 审批/提问都是中断式强提醒：半屏弹窗覆盖所有界面（含首页/扫码/会话），
        // 不可下滑/返回关闭，直到裁决/回答或服务端解决。审批优先于提问。
        val approval = session.approvals.firstOrNull()
        val question = if (approval == null) session.questions.firstOrNull() else null
        if (approval != null) {
            ApprovalSheet(
                approval = approval,
                queueCount = session.approvals.size,
                deciding = session.decidingApprovalId == approval.approvalId,
                sessionTitle = session.sessions.firstOrNull { it.id == approval.sessionId }
                    ?.let { sessionName(it) },
                onDecide = { d -> client.approve(approval, d) },
            )
        } else if (question != null) {
            QuestionSheet(
                question = question,
                queueCount = session.questions.size,
                deciding = session.decidingQuestionRpcId == question.rpcId,
                sessionTitle = session.sessions.firstOrNull { it.id == question.sessionId }
                    ?.let { sessionName(it) },
                onSubmit = { answers -> client.answerQuestion(question, answers) },
            )
        }
    }
}

// ================= 首页（未连接） =================

@Composable
private fun LandingScreen(client: BridgeClient, conn: ConnectionInfo, devicesState: DevicesUiState) {
    var showManual by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
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
        if (conn.state == ConnectionState.Error) {
            Spacer(Modifier.height(14.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "连接失败：${conn.detail}",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("设备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                "${devicesState.devices.size} 台 · 已连接过的会自动记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        // 设备列表
        if (devicesState.devices.isEmpty()) {
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
            val sorted = remember(devicesState.devices, devicesState.deviceStatuses) {
                devicesState.devices.sortedWith(
                    compareByDescending<StoredDevice> { statusOf(devicesState, it) == DeviceStatus.Online }
                        .thenByDescending { it.lastSeenAt },
                )
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(sorted, key = { deviceKey(it) }) { d ->
                    DeviceCard(
                        device = d,
                        status = statusOf(devicesState, d),
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
                    onClick = { client.connectManual(host.trim(), port.toIntOrNull() ?: 3080, token.trim().ifBlank { null }) },
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

private fun statusOf(state: DevicesUiState, device: StoredDevice): DeviceStatus =
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
private fun ConnectingScreen(client: BridgeClient, conn: ConnectionInfo) {
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
            conn.detail,
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
private fun MainScreen(
    client: BridgeClient,
    state: SessionUiState,
    reconnecting: Boolean,
    reconnectStatus: String,
) {
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
            if (reconnecting) {
                ReconnectBanner(status = reconnectStatus, onCancel = { client.disconnect() })
            }
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
}

// ---- 重连横幅 ----

@Composable
private fun ReconnectBanner(status: String, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "连接已断开，正在自动重连（$status）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onCancel) {
                Text("取消", color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

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
    state: SessionUiState,
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
private fun TopBar(client: BridgeClient, state: SessionUiState, onMenu: () -> Unit) {
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
private fun SessionList(client: BridgeClient, state: SessionUiState) {
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
private fun Conversation(client: BridgeClient, state: SessionUiState, sessionId: String) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalSheet(
    approval: ApprovalRequestWire,
    queueCount: Int,
    deciding: Boolean,
    sessionTitle: String?,
    onDecide: (ApprovalDecision) -> Unit,
) {
    // 中断式半屏弹窗：禁止下滑关闭、禁止返回关闭、禁止点外部关闭——
    // 审批是强阻塞交互，用户必须给出裁决（或等待它被其他终端/超时解决）。
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = { /* 中断式：不响应关闭请求 */ },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        dragHandle = {
            // 警示条（镜像桌面端「等待审批」strip）
            Row(
                Modifier.fillMaxWidth().background(StatusAmber.copy(alpha = 0.16f)).padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(9.dp).clip(CircleShape).background(StatusAmber),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "等待审批",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = StatusAmber,
                    modifier = Modifier.weight(1f),
                )
                if (queueCount > 1) {
                    Text(
                        "还有 ${queueCount - 1} 个待审批",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusAmber,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(StatusAmber.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        ) {
            // 会话上下文（若可见）
            if (!sessionTitle.isNullOrBlank()) {
                Text(
                    "来自会话「$sessionTitle」",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }
            // 主文案：reason 优先，否则桌面端同款模板（透传语义与桌面端一致）
            Text(
                approval.reason?.takeIf { it.isNotBlank() }
                    ?: "工具 ${approval.toolName} 请求越权执行",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            // 工具名徽章
            Text(
                "🛠 ${approval.toolName}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            // 透传的命令文本（关联工具调用时）
            if (!approval.command.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "请求执行的命令",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(12.dp),
                ) {
                    Text(
                        approval.command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFB8E6B8),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            // 裁决按钮（镜像桌面端：拒绝 outline / 允许一次 primary）
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onDecide(ApprovalDecision.Rejected) },
                    enabled = !deciding,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(if (deciding) "处理中…" else "拒绝", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { onDecide(ApprovalDecision.AllowedOnce) },
                    enabled = !deciding,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(if (deciding) "处理中…" else "允许一次", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "此操作需你在手机上确认，桌面端将等待你的裁决",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ================= 提问弹窗（ask_user_question 透传） =================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionSheet(
    question: QuestionRequestWire,
    queueCount: Int,
    deciding: Boolean,
    sessionTitle: String?,
    onSubmit: (List<QuestionAnswerItemWire>) -> Unit,
) {
    // 与审批一致：中断式半屏弹窗，禁止下滑/返回/点外部关闭。
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    // 每个提问的本地选择状态（按 rpcId 复位）
    var selections by remember(question.rpcId) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var customs by remember(question.rpcId) { mutableStateOf<Map<String, String>>(emptyMap()) }

    val allAnswered = question.questions.all { item ->
        val sel = selections[item.id].orEmpty()
        val custom = customs[item.id].orEmpty()
        sel.isNotEmpty() || custom.isNotBlank()
    }

    ModalBottomSheet(
        onDismissRequest = { /* 中断式：不响应关闭请求 */ },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        dragHandle = {
            Row(
                Modifier.fillMaxWidth().background(StatusAmber.copy(alpha = 0.16f)).padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("💬", fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "等待回答",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = StatusAmber,
                    modifier = Modifier.weight(1f),
                )
                if (queueCount > 1) {
                    Text(
                        "还有 ${queueCount - 1} 个待回答",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusAmber,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(StatusAmber.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (!sessionTitle.isNullOrBlank()) {
                Text(
                    "来自会话「$sessionTitle」",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }
            question.questions.forEach { item ->
                // 透传：header / question / detail / options（label + description）
                if (!item.header.isNullOrBlank()) {
                    Text(
                        item.header,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    item.question,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (!item.detail.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (item.options.isNotEmpty()) {
                    item.options.forEach { option ->
                        val selected = selections[item.id].orEmpty().contains(option.label)
                        val clickable = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val cur = selections[item.id].orEmpty().toMutableSet()
                                if (item.multiSelect) {
                                    if (selected) cur.remove(option.label) else cur.add(option.label)
                                } else {
                                    cur.clear()
                                    cur.add(option.label)
                                }
                                selections = selections + (item.id to cur)
                            }
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .padding(12.dp)
                        Row(clickable, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (item.multiSelect) (if (selected) "☑" else "☐") else (if (selected) "◉" else "○"),
                                fontSize = 15.sp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                if (!option.description.isNullOrBlank()) {
                                    Text(
                                        option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                } else {
                    // 无选项 → 自由文本输入
                    OutlinedTextField(
                        value = customs[item.id].orEmpty(),
                        onValueChange = { customs = customs + (item.id to it) },
                        placeholder = { Text("输入你的回答…") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            Button(
                onClick = {
                    onSubmit(
                        question.questions.map { item ->
                            QuestionAnswerItemWire(
                                id = item.id,
                                selected = selections[item.id].orEmpty().toList(),
                                custom = customs[item.id]?.takeIf { it.isNotBlank() },
                            )
                        },
                    )
                },
                enabled = allAnswered && !deciding,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (deciding) "提交中…" else "提交", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "回答将回传给桌面端 Agent，继续执行后续操作",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ================= 工具 =================
// 时间格式化（formatClock / relativeTime）与 nowMillis 见 TimeFormat.kt

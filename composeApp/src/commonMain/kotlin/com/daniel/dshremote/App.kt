@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.daniel.dshremote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daniel.dshremote.protocol.ApprovalDecision
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.DeviceStatus
import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.ServerLogEntry
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.StoredDevice
import com.daniel.dshremote.protocol.QuestionAnswerItemWire
import com.daniel.dshremote.protocol.QuestionRequestWire
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Markdown 代码块固定配色（两种气泡底色上都清晰可读）。 */
private val MarkdownCodeBg = Color(0xFF14181F)
private val MarkdownCodeFg = Color(0xFFDCE4EF)

// ================= 根 =================

@Composable
fun App(client: BridgeClient) {
    val scanning by client.scanning.collectAsState()
    val conn by client.connection.info.collectAsState()
    val devices by client.devices.state.collectAsState()
    val session by client.session.collectAsState()
    val reconnecting by client.reconnecting.collectAsState()
    val reconnectStatus by client.reconnectStatus.collectAsState()
    val reconnectRoutes by client.reconnectRoutes.collectAsState()
    var showLogs by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    // 冷启动自动连接：设备列表/探测结果就绪后决策一次（上次设备在线则无缝直连）
    LaunchedEffect(devices.devices, devices.deviceStatuses) {
        client.autoConnectOnce()
    }
    DshTheme {
        // 页面栈原则（docs/ui-navigation-guidelines.md）：A→B→C 时每按一次返回
        // 只回上一级。覆盖层页面（设备页/日志页/扫码）都必须有返回处理，
        // 关闭覆盖层后底下的页面状态原样保留，自然回到上一级。
        if (showDevices) {
            // 设备页（连接态从侧边栏进入）：查看/切换设备、扫码/手动连接
            LandingScreen(
                client = client,
                conn = conn,
                devicesState = devices,
                onOpenLogs = { showLogs = true },
                onBack = { showDevices = false },
                currentDeviceKey = session.connectedDevice?.let { deviceKey(it) },
            )
            PlatformBackHandler(enabled = true) { showDevices = false }
        } else if (showLogs) {
            // 日志页：从会话详情/首页/设备页进入，返回键 = 关闭日志页回上一级
            LogScreen(client, onClose = { showLogs = false })
            PlatformBackHandler(enabled = true) { showLogs = false }
        } else {
            // 断线/重连期间**永不跳页**（docs/ui-navigation-guidelines.md）：
            // 只要有会话上下文（connectedDevice 还在）且不是首次 Connecting，
            // 就留在会话界面，用横幅表达连接状态；落地页只在冷启动或用户主动断开后出现。
            val keepSessionUi = conn.state == ConnectionState.Connected || reconnecting ||
                (session.connectedDevice != null && conn.state != ConnectionState.Connecting)
            when {
                scanning -> {
                    QrScanner(
                        onScanned = { client.onQrScanned(it) },
                        onCancel = { client.stopScan() },
                    )
                    // 扫码页返回 = 取消扫码（等同 ✕ 取消按钮），回到上一级页面
                    PlatformBackHandler(enabled = true) { client.stopScan() }
                }
                // 重连等待/重试期间保留会话界面，只加横幅提示
                keepSessionUi ->
                    MainScreen(
                        client = client,
                        state = session,
                        reconnecting = reconnecting,
                        reconnectStatus = reconnectStatus,
                        reconnectRoutes = reconnectRoutes,
                        onSelectRoute = { client.reconnectVia(it) },
                        onOpenLogs = { showLogs = true },
                        onOpenDevices = { showDevices = true },
                    )
                conn.state == ConnectionState.Connecting -> ConnectingScreen(client, conn)
                else -> LandingScreen(client, conn, devices, onOpenLogs = { showLogs = true })
            }
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
private fun LandingScreen(
    client: BridgeClient,
    conn: ConnectionInfo,
    devicesState: DevicesUiState,
    onOpenLogs: () -> Unit,
    /** 非空 = 连接态从侧边栏进入的设备页（带返回头）；null = 未连接落地页。 */
    onBack: (() -> Unit)? = null,
    /** 当前已连接设备的 key（设备页里标记「当前」）。 */
    currentDeviceKey: String? = null,
) {
    var showManual by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("3080") }
    var token by remember { mutableStateOf("") }
    var forgetTarget by remember { mutableStateOf<StoredDevice?>(null) }

    // 进入页面刷新一次在线状态（连接态下探测轮询是停的，切设备前需要准确状态）
    LaunchedEffect(Unit) { client.devices.refreshStatuses() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        if (onBack != null) {
            // 设备页头部（连接态从侧边栏进入）
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回", fontWeight = FontWeight.SemiBold) }
                Text("设备", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenLogs) { Text("📋 日志") }
            }
        } else {
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
                Column(Modifier.weight(1f)) {
                    Text("dsh Remote Control", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "手机遥控桌面端 DeepSeek Harness",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenLogs) { Text("📋 日志") }
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
                        isCurrent = currentDeviceKey != null && deviceKey(d) == currentDeviceKey,
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
    isCurrent: Boolean = false,
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
                    if (isCurrent) "已连接 · 当前设备" else statusLabel(status),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isCurrent -> StatusGreen
                        status == DeviceStatus.Online -> StatusGreen
                        status == DeviceStatus.Changed -> StatusOrange
                        status == DeviceStatus.Checking -> StatusAmber
                        else -> StatusGray
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            if (isCurrent) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = StatusGreen,
                )
            } else if (status == DeviceStatus.Checking) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    "连接 ⟶",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue,
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
    reconnectRoutes: List<String>,
    onSelectRoute: (String) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenDevices: () -> Unit,
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
                onOpenDevices = {
                    scope.launch { drawerState.close() }
                    onOpenDevices()
                },
            )
        },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(
                client = client,
                state = state,
                onMenu = { scope.launch { drawerState.open() } },
                onOpenLogs = onOpenLogs,
            )
            if (reconnecting) {
                ReconnectBanner(
                    status = reconnectStatus,
                    routes = reconnectRoutes,
                    onSelectRoute = onSelectRoute,
                    onCancel = { client.disconnect() },
                )
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
    // 系统返回键的导航语义（后注册者优先，覆盖抽屉内部自带的"返回关闭抽屉"）：
    // 抽屉展开（项目列表）→ 退出回桌面；会话列表 → 打开抽屉（查看多个项目）；
    // 会话详情 → 返回列表（由 Conversation 内的返回拦截处理）。
    PlatformBackHandler(enabled = drawerState.isOpen) { platformExitApp() }
    PlatformBackHandler(enabled = !drawerState.isOpen && state.currentSessionId == null) {
        scope.launch { drawerState.open() }
    }
}

// ---- 重连横幅 ----

@Composable
private fun ReconnectBanner(
    status: String,
    routes: List<String>,
    onSelectRoute: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            // 多路由选择：只提示、不跳页；点选某个路由立即用它重试
            if (routes.size > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    routes.forEach { url ->
                        val label = url
                            .removePrefix("ws://").removePrefix("wss://")
                            .substringBefore('/').ifBlank { url }
                        TextButton(onClick = { onSelectRoute(url) }) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = AccentBlue)
                        }
                    }
                }
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
    onOpenDevices: () -> Unit,
) {
    val ungrouped = state.sessions.count { it.parentSessionId == null && it.workspaceId == null }
    val mainSessions = state.sessions.count { it.parentSessionId == null }
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        // 侧边栏占屏幕 85% 宽（用户要求：70% 太窄）
        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.85f),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "工作区",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            DrawerEntry(
                label = "全部会话",
                badge = mainSessions,
                icon = "🗂",
                selected = state.selectedWorkspaceId == null,
                onClick = { onSelect(null) },
            )
            state.workspaces.forEach { w ->
                DrawerEntry(
                    label = w.title,
                    // 计数按会话列表实算（服务端 workspace.sessionCount 含 registry 残留，
                    // 与列表不一致会出现「外面 N 个、点进去没有」）；只计顶层会话
                    badge = state.sessions.count { it.parentSessionId == null && it.workspaceId == w.id },
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
            // 设备页入口：多设备切换的落点
            DrawerEntry(
                label = "设备",
                badge = null,
                icon = "🖥",
                selected = false,
                onClick = onOpenDevices,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DrawerEntry(
    label: String,
    badge: Int?,
    icon: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        icon = { Text(icon, fontSize = 16.sp) },
        badge = badge?.let { b -> { Text("$b", style = MaterialTheme.typography.labelSmall) } },
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
private fun TopBar(client: BridgeClient, state: SessionUiState, onMenu: () -> Unit, onOpenLogs: () -> Unit) {
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
                        session != null && session.parentSessionId != null ->
                            "🤖 子会话 · " + if (session.status == "running") "运行中" else "空闲"
                        session != null -> if (session.status == "running") "运行中" else "空闲"
                        else -> state.connectedDevice
                            ?.let { "${it.host}:${it.port} · ${state.sessions.count { s -> s.parentSessionId == null }} 会话" }
                            ?: ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 子代理入口（右上角）：主会话挂载的子代理下拉列表
            if (session != null && session.parentSessionId == null) {
                val subagents = state.sessions.filter { it.parentSessionId == session.id }
                if (subagents.isNotEmpty()) {
                    var subagentMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { subagentMenuOpen = true }) {
                            Text("🤖${subagents.size}", fontWeight = FontWeight.SemiBold)
                        }
                        DropdownMenu(
                            expanded = subagentMenuOpen,
                            onDismissRequest = { subagentMenuOpen = false },
                        ) {
                            Text(
                                "子代理会话",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            subagents.forEach { sub ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                sessionName(sub),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Text(
                                                if (sub.status == "running") "运行中" else "空闲",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (sub.status == "running") StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        subagentMenuOpen = false
                                        client.openSubagent(sub.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onOpenLogs) { Text("📋") }
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
    // 只展示顶层（用户手动创建）会话；子代理会话挂在主会话里经右上角入口查看
    val visible = state.sessions.filter { s ->
        s.parentSessionId == null && when (selected) {
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
                        // 副标题只在「全部会话」视图显示所属工作区名（区分来源）；
                        // 已进入某个工作区时不再显示 cwd——上下文已明确
                        showWorkspace = selected == null && s.workspaceId != null,
                        workspaceTitle = state.workspaces.firstOrNull { it.id == s.workspaceId }?.title,
                        // 挂载的子代理数（含冷会话），与服务端 live 计数无关
                        subagentCount = state.sessions.count { it.parentSessionId == s.id },
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
    subagentCount: Int,
    onClick: () -> Unit,
    onInterrupt: () -> Unit,
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
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // 草稿：进入会话时从磁盘载入未发送文本；输入变化防抖落盘。
    // 断线/重连、切会话、App 重启都不丢用户打字。
    LaunchedEffect(sessionId) {
        client.loadDraft(sessionId)?.takeIf { it.isNotEmpty() }?.let { input = it }
        snapshotFlow { input }
            .drop(1) // 跳过载入草稿触发的那次
            .debounce(600)
            .collect { text -> client.saveDraft(sessionId, text) }
    }
    // Deep Diving 指示：等待模型回复时显示 + 每秒跳动的等待时长
    var divingTick by remember { mutableStateOf(0L) }
    LaunchedEffect(state.modelWaitingSince) {
        if (state.modelWaitingSince != null) {
            while (true) {
                delay(1000)
                divingTick++
            }
        }
    }
    // 返回键 = 左上角 ←：回到会话列表，不退出应用
    PlatformBackHandler(enabled = true) { client.closeSession() }
    val listState = rememberLazyListState()
    val latestSeq = state.events.lastOrNull()?.seq
    // 新消息到达（含刚发出的消息回显）→ 滚到列表底部，始终展示最新内容。
    // reverseLayout 下 index 0 = 底部最新；以最后事件 seq 为键，
    // 列表达 MAX_EVENTS 上限后 size 不再增长也能继续触发。
    LaunchedEffect(latestSeq, sessionId) {
        if (state.events.isNotEmpty()) listState.scrollToItem(0)
    }
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
            // 上滑到最早一条附近时自动加载更早的历史页（reverseLayout 下最高 index = 最早）
            val shouldLoadOlder by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val topIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                    topIndex >= info.totalItemsCount - 3
                }
            }
            LaunchedEffect(shouldLoadOlder, state.hasMore, state.loadingOlder) {
                if (shouldLoadOlder && state.hasMore && !state.loadingOlder) {
                    client.loadOlderPage(sessionId)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                reverseLayout = true,
            ) {
                // 思考流式：一行持续刷新（reverseLayout 下首个 item = 最新位置，即底部）
                state.liveThink?.let { lt ->
                    item(key = "live-think") { LiveThinkRow(lt) }
                }
                items(state.events.asReversed(), key = { "${it.seq}-${it.type}" }) { e ->
                    EventBubble(e, state.events)
                }
                if (state.loadingOlder) {
                    item(key = "loading-older") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
        // Deep Diving：与 DSH Web 对齐——放在排队消息面板上方（不在列表顶部）；
        // 深 Seek 品牌蓝；计时显示本轮对话总耗时（跨多次模型调用不重置）。
        state.modelWaitingSince?.let { since ->
            val turnStart = state.divingTurnStart ?: since
            Surface(color = DeepSeekBlue.copy(alpha = 0.12f)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🤿", fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Deep Diving",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = DeepSeekBlue,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = DeepSeekBlue.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "本轮 ${(nowMillis() - turnStart) / 1000}s",
                        style = MaterialTheme.typography.labelMedium,
                        color = DeepSeekBlue.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            divingTick // 每秒重算时长
        }
        // 排队消息面板：运行中发出的新消息进入队列；可收起/展开，每条可插队/删除
        if (state.queueItems.isNotEmpty()) {
            var queueExpanded by remember { mutableStateOf(true) }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { queueExpanded = !queueExpanded }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "⏳ 排队中的消息（${state.queueItems.size}）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (queueExpanded) "收起 ▲" else "展开 ▼",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentBlue,
                        )
                    }
                    if (queueExpanded) {
                        for (item in state.queueItems) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    when (item.placement) {
                                        "steering" -> "⚡插队中"
                                        "context" -> "🔧上下文"
                                        else -> "排队"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (item.placement == "steering") StatusAmber
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (item.placement == "queued") {
                                    TextButton(onClick = { client.sendQueueAction(sessionId, item.id, "steer") }) {
                                        Text("插队", color = AccentBlue, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                TextButton(onClick = { client.sendQueueAction(sessionId, item.id, "remove") }) {
                                    Text("删除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
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
                placeholder = { Text("发指令给DeepSeek Harness") },
                shape = RoundedCornerShape(22.dp),
                maxLines = 4,
                // 无焦点也常显蓝色边框，让用户一眼知道这里是输入框；
                // 聚焦时全亮蓝，未聚焦用半透明蓝区分状态。
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                ),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        client.sendMessage(text)
                        input = ""
                        // 先清焦点再收键盘：焦点仍在输入框时直接 hide 会被 IME 拉回来，一闪一闪
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                },
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                // 48dp 圆钮配默认 24dp 水平内边距会把内容区挤成 0 宽（图标不可见），
                // 必须归零内边距让 20dp 纸飞机完整渲染。
                contentPadding = PaddingValues(0.dp),
            ) {
                SendIcon()
            }
        }
    }
}

/** 纸飞机发送图标：Material send 路径 Canvas 自绘，随主题着色，无额外图标依赖。 */
@Composable
private fun SendIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(20.dp)) {
        val s = size.width / 24f
        val path = Path().apply {
            moveTo(2.01f * s, 21f * s)
            lineTo(23f * s, 12f * s)
            lineTo(2.01f * s, 3f * s)
            lineTo(2f * s, 10f * s)
            lineTo(17f * s, 12f * s)
            lineTo(2f * s, 14f * s)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun EventBubble(e: EventProjection, allEvents: List<EventProjection>) {
    // 工具调用失败：同 callId 的结果带 toolError → 命令标红（与桌面端一致）
    val callFailed = e.type == "tool_call" && e.callId != null &&
        allEvents.any { it.type == "tool_result" && it.callId == e.callId && it.toolError == true }
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
            markdown = true, // Agent 正文走 Markdown 渲染（代码块/表格/加粗等）
        )
        "tool_call" -> ToolCallCard(e, isError = callFailed)
        "tool_result" -> ToolResultCard(e)
        "think" -> ThinkCard(e)
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
    markdown: Boolean = false,
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
                // 库默认正文字号偏大（16sp）→ 统一缩放到与旧默认 bodyMedium(14sp) 一致，
                // 标题/代码/表格等其余字号随正文等比缩放（×0.875）。
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * 0.875f),
                ) {
                    Markdown(
                        content = text,
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
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
private fun ToolCallCard(e: EventProjection, isError: Boolean) {
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
                    formatClock(e.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = (if (isError) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.7f),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (expanded) "▲" else "▼", fontSize = 10.sp, color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
            }
            AnimatedVisibility(visible = expanded) {
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

/** 思考流式实时行：一行持续刷新（与 DeepSeek Web 的 thinking 流式体验对齐）。 */
@Composable
private fun LiveThinkRow(text: String) {
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
private fun ThinkCard(e: EventProjection) {
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
                formatClock(e.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ToolResultCard(e: EventProjection) {
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
                    formatClock(e.timestamp),
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
                color = AccentBlue,
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
                        color = AccentBlue,
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

// ================= 连接日志页（诊断基础组件） =================

@Composable
private fun LogScreen(client: BridgeClient, onClose: () -> Unit) {
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
                            serverLogs = client.loadServerLogs() ?: emptyList()
                            lastRefresh = nowMillis()
                            loadingServer = false
                            ConnLog.info("LOG", "已刷新服务端日志（${serverLogs.size} 条）")
                        }
                    },
                ) { Text(if (loadingServer) "刷新中…" else "刷新") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(tab == 0, "本机") { tab = 0 }
            Spacer(Modifier.width(6.dp))
            FilterChip(tab == 1, "服务端") {
                tab = 1
                if (serverLogs.isEmpty() && !loadingServer) {
                    loadingServer = true
                    scope.launch {
                        serverLogs = client.loadServerLogs() ?: emptyList()
                        lastRefresh = nowMillis()
                        loadingServer = false
                        ConnLog.info("LOG", "已加载服务端日志（${serverLogs.size} 条）")
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
        } else {
            if (loadingServer && serverLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            } else if (shownServer.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无服务端日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (lastRefresh == 0L) {
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = {
                                loadingServer = true
                                scope.launch {
                                    serverLogs = client.loadServerLogs() ?: emptyList()
                                    loadingServer = false
                                }
                            }) { Text("拉取一次") }
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
    }
}

@Composable
private fun FilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
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
private fun LogRow(time: String, level: String, levelColor: Color, tag: String, message: String) {
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

private fun levelColor(level: ConnLogLevel): Color = when (level) {
    ConnLogLevel.DEBUG -> Color(0xFF8B93A7)
    ConnLogLevel.INFO -> Color(0xFF6E9BFF)
    ConnLogLevel.WARN -> Color(0xFFF2C14E)
    ConnLogLevel.ERROR -> Color(0xFFFF6B6B)
}

private fun levelColorOf(level: String): Color = when (level) {
    "warn" -> Color(0xFFF2C14E)
    "error" -> Color(0xFFFF6B6B)
    "info" -> Color(0xFF6E9BFF)
    else -> Color(0xFF8B93A7)
}

// ================= 工具 =================
// 时间格式化（formatClock / relativeTime）与 nowMillis 见 TimeFormat.kt

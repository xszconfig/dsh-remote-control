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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daniel.dshremote.protocol.ApprovalDecision
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.CommandWire
import com.daniel.dshremote.protocol.DeviceStatus
import com.daniel.dshremote.protocol.EventProjection
import com.daniel.dshremote.protocol.ServerEvent
import com.daniel.dshremote.protocol.ServerLogEntry
import com.daniel.dshremote.protocol.SessionSummary
import com.daniel.dshremote.protocol.StoredDevice
import com.daniel.dshremote.protocol.QuestionAnswerItemWire
import com.daniel.dshremote.protocol.QuestionItemWire
import com.daniel.dshremote.protocol.QuestionRequestWire
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Markdown 代码块固定配色（两种气泡底色上都清晰可读）。 */
private val MarkdownCodeBg = Color(0xFF14181F)
private val MarkdownCodeFg = Color(0xFFDCE4EF)

/** 子代理下拉列表：最多同时展示 10 条（移动端小屏上限，铁律 9），超过则列表内上下滚动。 */
private const val SUBAGENT_MENU_MAX_VISIBLE = 10
private val SUBAGENT_MENU_MAX_HEIGHT = 48.dp * SUBAGENT_MENU_MAX_VISIBLE

// ================= 根 =================

@Composable
fun App(client: BridgeClient) {
    val scanning by client.scanning.collectAsState()
    val conn by client.connection.info.collectAsState()
    val devices by client.devices.state.collectAsState()
    val session by client.session.collectAsState()
    val notice by client.notice.collectAsState()
    // 通知权限引导状态（未授权时首次触发通知被拦截 → 提示 rationale）
    val permPrompt by NotificationPermissionState.prompt.collectAsState()
    // 重连态派生自统一槽（用于 keepSessionUi 的「断线/重连不跳页」判断）
    val reconnecting = notice is ConnectionNotice.Reconnecting
    var showLogs by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    // 平板判定：宽度 ≥840dp（等价 WindowWidthSizeClass.Expanded）。仅 Expanded 启用三栏，
    // 手机/横屏/折叠屏（Compact/Medium）走现有单页流，零回归。
    val widthDp = LocalConfiguration.current.screenWidthDp
    val isTablet = isTabletLayout(widthDp)
    LaunchedEffect(isTablet) {
        ConnLog.info("ACTION", "平板布局切换 ${if (isTablet) "enabled" else "disabled"} widthDp=$widthDp")
    }
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
                    if (isTablet) {
                        TabletMainScreen(
                            client = client,
                            state = session,
                            notice = notice,
                            onOpenLogs = { showLogs = true },
                            onOpenDevices = { showDevices = true },
                        )
                    } else {
                        MainScreen(
                            client = client,
                            state = session,
                            notice = notice,
                            onOpenLogs = { showLogs = true },
                            onOpenDevices = { showDevices = true },
                        )
                    }
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
        // 通知权限引导（未授权时）：小弹窗说明用途，同意后申请；拒绝后转「去设置」
        if (permPrompt != NotificationPermissionPrompt.Hidden) {
            NotificationPermissionDialog(
                prompt = permPrompt,
                onAllow = { platformRequestNotificationPermission() },
                onDismiss = { NotificationPermissionState.prompt.value = NotificationPermissionPrompt.Hidden },
                onOpenSettings = {
                    platformOpenNotificationSettings()
                    NotificationPermissionState.prompt.value = NotificationPermissionPrompt.Hidden
                },
            )
        }
    }
}

/** 通知权限引导弹窗：Rationale=说明用途+允许；GoSettings=引导去系统设置开启。 */
@Composable
private fun NotificationPermissionDialog(
    prompt: NotificationPermissionPrompt,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val goSettings = prompt == NotificationPermissionPrompt.GoSettings
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (goSettings) "通知权限已关闭" else "开启通知提醒") },
        text = {
            Text(
                if (goSettings) {
                    "审批/提问与结果交付的通知提醒需要通知权限。请在系统设置中开启，否则锁屏或后台时将无法收到提醒。"
                } else {
                    "为在锁屏或后台及时提醒你处理审批、提问与结果交付，需要通知权限（仅用于本项目的提醒，不用于营销推送）。"
                }
            )
        },
        confirmButton = {
            TextButton(onClick = if (goSettings) onOpenSettings else onAllow) {
                Text(if (goSettings) "去设置" else "允许")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("暂不") }
        },
    )
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
        LandingHeader(onBack = onBack, onOpenLogs = onOpenLogs)

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

        DeviceListSection(
            client = client,
            devicesState = devicesState,
            currentDeviceKey = currentDeviceKey,
            onForget = { forgetTarget = it },
            listModifier = Modifier.weight(1f).fillMaxWidth(),
        )

        // 手动连接表单
        AnimatedVisibility(visible = showManual) {
            ManualConnectForm(
                client = client,
                host = host,
                port = port,
                token = token,
                onHostChange = { host = it },
                onPortChange = { port = it },
                onTokenChange = { token = it },
            )
        }

        // 底部操作
        Button(
            onClick = { ConnLog.info("ACTION", "扫码连接点击"); client.startScan() },
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
                    onClick = { ConnLog.info("ACTION", "忘记设备点击 ${target.name} (${target.host}:${target.port})"); client.forgetDevice(target); forgetTarget = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("忘记") }
            },
            dismissButton = { TextButton(onClick = { forgetTarget = null }) { Text("取消") } },
        )
    }
}

/** 落地页/设备页头部：连接态带「返回」，冷启动展示品牌头。 */
@Composable
private fun LandingHeader(onBack: (() -> Unit)?, onOpenLogs: () -> Unit) {
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
}

/** 设备列表：空态提示或已记录设备卡片（连接 / 标记当前 / 忘记）。 */
@Composable
private fun DeviceListSection(
    client: BridgeClient,
    devicesState: DevicesUiState,
    currentDeviceKey: String?,
    onForget: (StoredDevice) -> Unit,
    listModifier: Modifier,
) {
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
        LazyColumn(listModifier) {
            items(sorted, key = { deviceKey(it) }) { d ->
                DeviceCard(
                    device = d,
                    status = statusOf(devicesState, d),
                    isCurrent = currentDeviceKey != null && deviceKey(d) == currentDeviceKey,
                    onClick = { ConnLog.info("ACTION", "设备连接点击 ${d.name} (${d.host}:${d.port})"); client.connectDevice(d) },
                    onForget = { onForget(d) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 手动连接表单：Host / Port / Token + 连接按钮。 */
@Composable
private fun ManualConnectForm(
    client: BridgeClient,
    host: String,
    port: String,
    token: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
) {
    Column(Modifier.padding(bottom = 8.dp)) {
        OutlinedTextField(
            value = host, onValueChange = onHostChange,
            label = { Text("Host") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(
                value = port, onValueChange = onPortChange,
                label = { Text("Port") },
                modifier = Modifier.width(120.dp),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = token, onValueChange = onTokenChange,
                label = { Text("Token（可选）") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                ConnLog.info("ACTION", "手动连接点击 host=${host.trim()} port=${port.toIntOrNull() ?: 3080}")
                client.connectManual(host.trim(), port.toIntOrNull() ?: 3080, token.trim().ifBlank { null })
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("连接", fontWeight = FontWeight.SemiBold)
        }
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
                    "${device.host}:${device.port} · ${formatTimestamp(device.lastSeenAt)}",
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
        OutlinedButton(onClick = { ConnLog.info("ACTION", "断开连接点击"); client.disconnect() }, shape = RoundedCornerShape(14.dp)) {
            Text("取消")
        }
    }
}

// ================= 主界面（已连接） =================

@Composable
private fun MainScreen(
    client: BridgeClient,
    state: SessionUiState,
    notice: ConnectionNotice,
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
            // 统一连接状态提示槽：三形态互斥展示（重连中 / 错误 / 隐藏）
            when (val n = notice) {
                is ConnectionNotice.Reconnecting -> ReconnectBanner()
                is ConnectionNotice.Error -> ConnectionErrorBanner(
                    message = n.message,
                    more = state.errors.size - 1,
                    history = state.errors,
                    onDismiss = { client.dismissErrors() },
                )
                ConnectionNotice.Hidden -> Unit
            }
            // 服务端重启通知：重连后收到 server_boot → 横幅告知版本与新增功能（可关闭）
            state.serverBoot?.let { boot ->
                Surface(color = DeepSeekBlue.copy(alpha = 0.14f)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("✅", fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "服务端已重启 · v${boot.version}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = DeepSeekBlue,
                            )
                            if (boot.notes.isNotEmpty()) {
                                Text(
                                    boot.notes.joinToString("；"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "知道了",
                            style = MaterialTheme.typography.labelMedium,
                            color = AccentBlue,
                            modifier = Modifier.clickable { client.dismissBootNotice() },
                        )
                    }
                }
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

/** 平板三栏主界面：左栏=项目+会话合并侧栏；中栏=主会话；右栏=子代理会话（出现时左栏收起）。 */
@Composable
private fun TabletMainScreen(
    client: BridgeClient,
    state: SessionUiState,
    notice: ConnectionNotice,
    onOpenLogs: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    val pane = tabletPaneState(state.currentSessionId, state.subagentReturnTo)
    // 中栏渲染的会话：无子会话=当前主会话；有子会话=主会话（subagentReturnTo）。
    val midSessionId = state.subagentReturnTo ?: state.currentSessionId
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 连接/重启横幅与手机一致
        when (val n = notice) {
            is ConnectionNotice.Reconnecting -> ReconnectBanner()
            is ConnectionNotice.Error -> ConnectionErrorBanner(
                message = n.message,
                more = state.errors.size - 1,
                history = state.errors,
                onDismiss = { client.dismissErrors() },
            )
            ConnectionNotice.Hidden -> Unit
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // 左栏：项目（工作区过滤）+ 会话列表合并；子会话打开时收起。
            if (pane.leftVisible) {
                TabletLeftPane(
                    client = client,
                    state = state,
                    onOpenLogs = onOpenLogs,
                    onOpenDevices = onOpenDevices,
                    modifier = Modifier.width(280.dp).fillMaxHeight(),
                )
            }
            // 中栏：主会话（live）
            if (pane.midVisible && midSessionId != null) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(
                        client = client,
                        state = state,
                        onMenu = {},
                        onOpenLogs = onOpenLogs,
                        sessionId = midSessionId,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Conversation(client, state, midSessionId)
                }
            }
            // 右栏：子代理会话（live）
            if (pane.rightVisible && state.currentSessionId != null) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(
                        client = client,
                        state = state,
                        onMenu = {},
                        onOpenLogs = onOpenLogs,
                        sessionId = state.currentSessionId,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Conversation(client, state, state.currentSessionId)
                }
            }
        }
    }
    // 返回档 3：会话列表态 → 回桌面（档 1/2 由 Conversation 内部的 closeSession 处理）。
    PlatformBackHandler(enabled = state.currentSessionId == null) { platformExitApp() }
}

/** 平板左栏：设备头 + 工作区过滤 + 会话列表。 */
@Composable
private fun TabletLeftPane(
    client: BridgeClient,
    state: SessionUiState,
    onOpenLogs: () -> Unit,
    onOpenDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            // 设备头（与手机抽屉一致）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(state.connectedDevice?.name ?: "已连接", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        state.connectedDevice?.let { "${it.host}:${it.port}" } ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenDevices) { Text("🖥", fontSize = 16.sp) }
                TextButton(onClick = onOpenLogs) { Text("📋") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("工作区", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            WorkspaceFilter(state = state, onSelect = { client.selectWorkspace(it) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
            SessionList(client = client, state = state)
        }
    }
}

// ---- 重连横幅 ----

@Composable
private fun ReconnectBanner() {
    // 断线/重连只在这里用一条横幅表达（铁律 3）：固定文案 + loading 进度，
    // 不暴露候选路由/设备 IP，也不需要任何点击——重连成功（hello 到达）后自动消失。
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "检测到连接断开，正在自动重连…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- 统一连接状态错误横幅 + 错误历史 ----

@Composable
private fun ConnectionErrorBanner(
    message: String,
    more: Int,
    history: List<NoticeError>,
    onDismiss: () -> Unit,
) {
    var showHistory by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clickable { showHistory = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
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
    if (showHistory) {
        ErrorHistorySheet(
            history = history,
            onDismiss = { showHistory = false },
            onClear = {
                onDismiss()
                showHistory = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ErrorHistorySheet(
    history: List<NoticeError>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            Text("错误历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "${history.size} 条 · 连接类错误恢复后自动清除，业务类错误需手动清除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (history.isEmpty()) {
                Text("暂无错误", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                history.asReversed().forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(if (e.recoverable) "🔌" else "⚠️", fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            e.message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onClear,
                modifier = Modifier.align(Alignment.End),
            ) { Text("清空全部", color = MaterialTheme.colorScheme.error) }
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
            WorkspaceFilter(state = state, onSelect = onSelect)
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

/** 工作区过滤条目（全部会话 / 各工作区 / 未分组）：手机抽屉与平板左栏共用。 */
@Composable
private fun WorkspaceFilter(state: SessionUiState, onSelect: (String?) -> Unit) {
    val ungrouped = state.sessions.count { it.parentSessionId == null && it.workspaceId == null }
    val mainSessions = state.sessions.count { it.parentSessionId == null }
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
private fun TopBar(client: BridgeClient, state: SessionUiState, onMenu: () -> Unit, onOpenLogs: () -> Unit, sessionId: String? = null) {
    val session = (sessionId ?: state.currentSessionId)?.let { sid ->
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
                            // 标题占满剩余宽度（右上角只剩 🤖/📋 两个按钮），展示更完整
                            modifier = Modifier.weight(1f),
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
                        TextButton(onClick = {
                            ConnLog.info("ACTION", "子代理下拉打开 parentId=${session.id} 子代理数=${subagents.size}")
                            subagentMenuOpen = true
                        }) {
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
                            // 最多同时展示 10 条，超过则列表内上下滚动（高度上限 + 滚动）。
                            // 注意：不能用 LazyColumn——DropdownMenu 内容区以 width(IntrinsicSize.Max)
                            // 做固有尺寸测量，LazyColumn 是 SubcomposeLayout，固有测量会抛
                            // IllegalStateException；26 条以内用非懒布局无性能问题。
                            Column(
                                modifier = Modifier
                                    .heightIn(max = SUBAGENT_MENU_MAX_HEIGHT)
                                    .verticalScroll(rememberScrollState()),
                            ) {
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
                                                // 副标题：状态标志 + 元信息（最后消息时间 / 运行时长 / token），
                                                // 各段用 · 分隔；过长可换行成多段（服务端投影为准，客户端不做推算）。
                                                SubagentSubtitle(sub)
                                            }
                                        },
                                        onClick = {
                                            subagentMenuOpen = false
                                            ConnLog.info("ACTION", "子代理点击 subagentId=${sub.id} parentId=${session.id}")
                                            client.openSubagent(sub.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onOpenLogs) { Text("📋") }
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
private fun InterruptConfirmDialog(
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
private fun SessionCard(
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
private fun sessionName(s: SessionSummary): String =
    s.name?.takeIf { it.isNotBlank() }
        ?: basenameOf(s.cwd).ifBlank { s.id.take(12) }

private fun basenameOf(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

/**
 * 子代理列表副标题：状态标志（空闲/运行中）+ 元信息（最后消息时间 / 运行时长 / token），
 * 各段用 · 分隔，过长可换行成多段。状态段保留原配色（运行中绿色），元信息段用次要色。
 * 所有元信息均来自服务端投影字段（铁律 6：客户端不做本地推算）。
 */
@Composable
private fun SubagentSubtitle(sub: SessionSummary) {
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

// ================= 会话详情 =================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Conversation(client: BridgeClient, state: SessionUiState, sessionId: String) {
    // 会话详情投影：手机=currentSessionId；平板中栏=parentView（子会话打开时主会话 live）。
    val view = state.viewOf(sessionId)
    var input by remember { mutableStateOf("") }
    // 输入框焦点：斜杠命令候选弹窗只在聚焦时出现（草稿载入不误弹）
    var inputFocused by remember { mutableStateOf(false) }
    // 草稿：进入会话时从磁盘载入未发送文本；输入变化防抖落盘。
    // 断线/重连、切会话、App 重启都不丢用户打字。
    LaunchedEffect(sessionId) {
        client.loadDraft(sessionId)?.takeIf { it.isNotEmpty() }?.let { input = it }
        snapshotFlow { input }
            .drop(1) // 跳过载入草稿触发的那次
            .debounce(600)
            .collect { text -> client.saveDraft(sessionId, text) }
    }
    // 返回键 = 左上角 ←：回到会话列表，不退出应用
    PlatformBackHandler(enabled = true) { client.closeSession() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 转盘状态提升到 Conversation：滚底 effect 需读 phase 判断是否抑制强制滚底（新消息不打断转盘交互）。
    val dialState = remember(sessionId) { MessageDialState() }
    val latestSeq = view.events.lastOrNull()?.seq
    // 自动跟随底部状态机：
    // - 默认跟随（新消息到达 → 滚到底部；切会话重置为跟随）
    // - 用户上滑浏览历史 → 滚动停稳后暂停跟随（让他看）
    // - 用户滑回底部 → 恢复跟随（reverseLayout 下 canScrollForward=false 即底部）
    var followBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    // reverseLayout 下 index 0 = 底部：canScrollBackward=false 即已贴底。
                    // （不能用 canScrollForward——那是"上方还有更早历史"的方向，几乎恒为 true）
                    followBottom = !listState.canScrollBackward
                }
            }
    }
    // 切会话/打开会话：重置为跟随并定位底部
    LaunchedEffect(sessionId) {
        followBottom = true
        if (view.events.isNotEmpty()) listState.scrollToItem(0)
    }
    // 新消息到达（含刚发出的消息回显）→ 仅当处于跟随态才滚到列表底部。
    // reverseLayout 下 index 0 = 底部最新；以最后事件 seq 为键，
    // 列表达 MAX_EVENTS 上限后 size 不再增长也能继续触发。
    LaunchedEffect(latestSeq) {
        // 转盘处于任何非收起态时挂起强制滚底，避免新消息打断转盘交互；收起后恢复跟随。
        if (followBottom && view.events.isNotEmpty() && dialState.phase == DialPhase.Collapsed) {
            listState.scrollToItem(0)
        }
    }
    // 新 pending 上屏（刚发送）→ 跟随态滚到底部看到自己的消息（pending 不在 events 里，需单独触发）。
    val pendingCount = view.pendingMessages.count { it.sessionId == sessionId }
    LaunchedEffect(pendingCount) {
        if (followBottom && dialState.phase == DialPhase.Collapsed) {
            listState.scrollToItem(0)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ConversationMessageList(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                client = client,
                view = view,
                sessionId = sessionId,
                listState = listState,
                dialState = dialState,
                onJumpToBottom = {
                    followBottom = true
                    scope.launch { listState.scrollToItem(0) }
                },
            )
            ConversationPanels(view = view, client = client, sessionId = sessionId)
            ConversationDevPanel(view = view, client = client, sessionId = sessionId)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ConversationComposer(
                client = client,
                state = state,
                view = view,
                sessionId = sessionId,
                input = input,
                onInputChange = { input = it },
                inputFocused = inputFocused,
                onInputFocusedChange = { inputFocused = it },
                onFollowBottom = { followBottom = true },
            )
        }
    }
}

/** 消息列表区：空态提示，或 LazyColumn + 回到底部悬浮按钮 + 消息转盘（转盘必须挂载在列表 Box 内）。 */
@Composable
private fun ConversationMessageList(
    modifier: Modifier,
    client: BridgeClient,
    view: SessionViewState,
    sessionId: String,
    listState: LazyListState,
    dialState: MessageDialState,
    onJumpToBottom: () -> Unit,
) {
    val pendingForSession = view.pendingMessages.filter { it.sessionId == sessionId }
    if (view.events.isEmpty() && pendingForSession.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
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
        LaunchedEffect(shouldLoadOlder, view.hasMore, view.loadingOlder) {
            if (shouldLoadOlder && view.hasMore && !view.loadingOlder) {
                client.loadOlderPage(sessionId)
            }
        }
        // 「回到底部」按钮显隐：最新一条消息不在可见区（且列表已布局）→ 显示。
        // liveThink 流式行占 index 0 时最新消息在 index 1，否则在 index 0。
        val latestIndex = latestEventIndex(view.liveThink != null)
        val showJumpToBottom by remember(latestIndex, listState) {
            derivedStateOf {
                val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
                visible.isNotEmpty() && !latestMessageVisible(visible, latestIndex)
            }
        }
        // 转盘按需显示：showDial = 最新消息不可见 OR 非收起态（手指活动优先于「到底隐藏」）。
        // 隐藏时收起：不得在旋转中（手指还在转盘上滑动）强制 collapse；松手后由转盘内 2.5s 自动收起自然关闭。
        LaunchedEffect(showJumpToBottom) {
            val rotatingFinger = dialState.phase == DialPhase.Rotating && dialState.fingerDown
            if (!showJumpToBottom && dialState.phase != DialPhase.Collapsed && !rotatingFinger) {
                dialState.collapse()
            }
        }
        Box(modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                reverseLayout = true,
            ) {
                // 思考流式：一行持续刷新（reverseLayout 下首个 item = 最新位置，即底部）
                view.liveThink?.let { lt ->
                    item(key = "live-think") { LiveThinkRow(lt) }
                }
                // 本地待发送消息：乐观上屏的用户气泡（时间行带 Loading / ❗），回显到达后移除。
                items(pendingForSession.asReversed(), key = { "pending-${it.localId}" }) { p ->
                    PendingBubble(p, onRetry = { client.retryMessage(p.localId) })
                }
                items(view.events.asReversed(), key = { "${it.seq}-${it.type}" }) { e ->
                    EventBubble(e, view.events)
                }
                if (view.loadingOlder) {
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
            // 「回到底部」悬浮按钮：位于 Deep Diving 上方、右对齐（bottomEnd 即消息列表右下角）。
            // 淡入淡出（不做位移动画，避免突兀）；点击瞬间 scrollToItem(0) + 置回跟随态。
            JumpToBottomOverlay(
                visible = showJumpToBottom,
                onClick = onJumpToBottom,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp),
            )
            // 消息转盘：挂载在消息列表 Box 内，圆钮悬浮于左下角（Deep Diving 上方）；
            // 按需显示：上翻离开底部出现，展开/旋转中即使已到底部也保持挂载（不打断手指）。
            if (showDial(showJumpToBottom, dialState.phase)) {
                MessageDial(
                    dial = dialState,
                    view = view,
                    listState = listState,
                    onLoadOlder = { client.loadOlderPage(sessionId) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 面板区：Deep Diving 条 + 任务列表 + Goal + 排队消息（按上到下顺序，位于消息列表下方）。 */
@Composable
private fun ConversationPanels(view: SessionViewState, client: BridgeClient, sessionId: String) {
    DeepDivingBar(view)
    TodoPanel(view)
    GoalPanel(view)
    QueuePanel(view, client, sessionId)
}

/** Deep Diving：与 DSH Web 对齐——放在任务列表/排队消息面板上方（不在列表顶部）。 */
@Composable
private fun DeepDivingBar(view: SessionViewState) {
    // 深 Seek 品牌蓝；标签在整个轮次期间显示（服务端 turn_status），时钟在 ≥15s 后出现
    // （DSH Web showClock 阈值），时长只显示服务端推送的 deepDivingElapsed（不本地计时）。
    val divingVisible = view.divingTurnStart != null || view.modelWaitingSince != null
    if (divingVisible) {
        val elapsed = view.deepDivingElapsed ?: 0
        val showClock = elapsed >= 15
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
                if (showClock) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = DeepSeekBlue.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        formatDivingDuration(elapsed),
                        style = MaterialTheme.typography.labelMedium,
                        color = DeepSeekBlue.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 任务列表条：DSH 的 todo_write 清单（每会话一份），位于 Deep Diving 下方、Goal 上方。 */
@Composable
private fun TodoPanel(view: SessionViewState) {
    if (view.todos.isNotEmpty()) {
        var todosExpanded by remember { mutableStateOf(true) }
        // 与服务端 DSH Web progressLabel 完全对齐：已完成 → 进行中 → 待处理，
        // 零计数的段省略（"·" 连接）。
        val doneCount = view.todos.count { it.status == "completed" }
        val activeCount = view.todos.count { it.status == "in_progress" }
        val pendingCount = view.todos.size - doneCount - activeCount
        val progressSegments = buildList {
            if (doneCount > 0) add("$doneCount 已完成")
            if (activeCount > 0) add("$activeCount 进行中")
            if (pendingCount > 0) add("$pendingCount 待处理")
        }.joinToString(" · ")
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { todosExpanded = !todosExpanded }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "📋 任务（${view.todos.size}）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (progressSegments.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            progressSegments,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Text(
                        if (todosExpanded) "收起 ▲" else "展开 ▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentBlue,
                    )
                }
                if (todosExpanded) {
                    // 最多同屏 3 条：超出 3 条时列表区用固定高度（约 3 行高）并支持纵向滚动
                    val scrollState = rememberScrollState()
                    Column(
                        Modifier.fillMaxWidth().then(
                            if (view.todos.size > 3) Modifier.height(112.dp).verticalScroll(scrollState)
                            else Modifier
                        ),
                    ) {
                        view.todos.forEach { todo ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    when (todo.status) {
                                        "completed" -> "✅"
                                        "in_progress" -> "▶️"
                                        else -> "⏳"
                                    },
                                    fontSize = 13.sp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    todo.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (todo.status == "completed") {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Goal 面板：该会话的持久化目标（objective/阶段/轮次/阻塞原因）。位置：任务列表下方、排队消息上方。 */
@Composable
private fun GoalPanel(view: SessionViewState) {
    // 对齐 DSH Web：完成态（phase=complete）目标不再展示面板；goal=null（服务端已清除）同样不渲染。
    view.goal?.takeIf { it.phase != "complete" }?.let { goal ->
        var goalExpanded by remember { mutableStateOf(false) }
        val phaseColor = when (goal.phase) {
            "active" -> StatusGreen
            "paused" -> StatusAmber
            "blocked" -> MaterialTheme.colorScheme.error
            else -> StatusGray
        }
        val phaseLabel = when (goal.phase) {
            "active" -> "进行中"
            "paused" -> "已暂停"
            "blocked" -> "已阻塞"
            else -> "已完成"
        }
        Surface(color = phaseColor.copy(alpha = 0.10f)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { goalExpanded = !goalExpanded }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎯", fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Goal",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = phaseColor,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        phaseLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = phaseColor,
                    )
                    Spacer(Modifier.weight(1f))
                    if (goal.maxGoalRounds > 0) {
                        Text(
                            "第 ${goal.roundsStarted}/${goal.maxGoalRounds} 轮",
                            style = MaterialTheme.typography.labelSmall,
                            color = phaseColor.copy(alpha = 0.85f),
                        )
                    }
                }
                Text(
                    goal.objective,
                    style = MaterialTheme.typography.bodySmall,
                    // 小屏空间预算：折叠态只占一行，点开看全文
                    maxLines = if (goalExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (goal.phase == "blocked" && !goal.blockedMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "⛔ ${goal.blockedMessage}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 排队消息面板：运行中发出的新消息进入队列；可收起/展开，每条可插队/删除。 */
@Composable
private fun QueuePanel(view: SessionViewState, client: BridgeClient, sessionId: String) {
    if (view.queueItems.isNotEmpty()) {
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
                        "⏳ 排队中的消息（${view.queueItems.size}）",
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
                    // 最多同屏 3 条：超出 3 条时列表区用固定高度（约 3 条行高）并支持纵向滚动；
                    // 收起/展开逻辑不变（展开时才渲染列表区）。
                    val queueItems = view.queueItems
                    val scrollState = rememberScrollState()
                    Column(
                        Modifier.fillMaxWidth().then(
                            if (queueItems.size > 3) Modifier.height(160.dp).verticalScroll(scrollState)
                            else Modifier
                        ),
                    ) {
                        queueItems.forEach { item ->
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
                                    TextButton(onClick = {
                                        ConnLog.info("ACTION", "排队插队 itemId=${item.id} sessionId=$sessionId")
                                        client.sendQueueAction(sessionId, item.id, "steer")
                                    }) {
                                        Text("插队", color = AccentBlue, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                TextButton(onClick = {
                                    ConnLog.info("ACTION", "排队移除 itemId=${item.id} sessionId=$sessionId")
                                    client.sendQueueAction(sessionId, item.id, "remove")
                                }) {
                                    Text("删除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 开发状态行 + 详情面板：LSP 诊断 + 调试状态聚合为一条细行，点开进 ModalBottomSheet。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationDevPanel(view: SessionViewState, client: BridgeClient, sessionId: String) {
    // 绝不自动弹面板：只更新徽标，用户点开才进详情。
    var showDevPanel by remember { mutableStateOf(false) }
    if (view.diagnostics.isNotEmpty() || view.debug != null) {
        val errorCount = view.diagnostics.count { it.severity == 1 }
        val warnCount = view.diagnostics.count { it.severity == 2 }
        val debugSnap = view.debug
        val pausedAt = debugSnap?.paused?.stoppedAt
        val tint = when {
            errorCount > 0 -> MaterialTheme.colorScheme.error
            debugSnap?.state == "paused" -> StatusAmber
            warnCount > 0 -> StatusAmber
            debugSnap != null && debugSnap.error != null -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Surface(color = tint.copy(alpha = 0.10f)) {
            Row(
                Modifier.fillMaxWidth().clickable { showDevPanel = true }.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (view.diagnostics.isNotEmpty()) {
                    Text(if (errorCount > 0) "⛔" else "⚠️", fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        buildString {
                            append("${view.diagnostics.size} 诊断")
                            if (errorCount > 0) append("（$errorCount 错误）")
                            else if (warnCount > 0) append("（$warnCount 警告）")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (errorCount > 0) MaterialTheme.colorScheme.error else StatusAmber,
                    )
                }
                if (view.diagnostics.isNotEmpty() && debugSnap != null) {
                    Spacer(Modifier.width(10.dp))
                    Text("·", style = MaterialTheme.typography.labelMedium, color = tint.copy(alpha = 0.6f))
                    Spacer(Modifier.width(10.dp))
                }
                if (debugSnap != null) {
                    Text("🐛", fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (debugSnap.state) {
                            "paused" -> "暂停中 ${pausedAt?.path?.substringAfterLast('/') ?: ""}${pausedAt?.let { ":${it.line}" } ?: ""}"
                            "starting" -> "调试启动中…"
                            "running" -> "调试运行中"
                            else -> "调试已停止" + (debugSnap.error?.let { "（$it）" } ?: "")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("查看详情", style = MaterialTheme.typography.labelSmall, color = AccentBlue)
            }
        }
    }
    if (showDevPanel) {
        var devTab by remember { mutableStateOf(0) }
        ModalBottomSheet(
            onDismissRequest = { showDevPanel = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(bottom = 28.dp),
            ) {
                Text("开发面板", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                TabRow(selectedTabIndex = devTab) {
                    Tab(selected = devTab == 0, onClick = { devTab = 0 }, text = { Text("诊断 ${view.diagnostics.size}") })
                    Tab(selected = devTab == 1, onClick = { devTab = 1 }, text = { Text("调试") })
                }
                Spacer(Modifier.height(10.dp))
                if (devTab == 0) {
                    if (view.diagnostics.isEmpty()) {
                        Text("暂无诊断：Agent 编辑代码后，语言服务器的错误/警告会自动出现在这里。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        view.diagnostics.forEach { d ->
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                                Text(
                                    when (d.severity) { 1 -> "🔴"; 2 -> "🟡"; 3 -> "🔵"; else -> "⚪" },
                                    fontSize = 12.sp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.message, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "${d.path} : ${d.line}:${d.column}" + (d.source?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                } else {
                    DebugPanelContent(view, client, sessionId)
                }
            }
        }
    }
}

/** 输入区：斜杠命令候选弹窗 + 输入框 + 中断/发送按钮。 */
@Composable
private fun ConversationComposer(
    client: BridgeClient,
    state: SessionUiState,
    view: SessionViewState,
    sessionId: String,
    input: String,
    onInputChange: (String) -> Unit,
    inputFocused: Boolean,
    onInputFocusedChange: (Boolean) -> Unit,
    onFollowBottom: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var showInterruptConfirm by remember { mutableStateOf(false) }
    // 点「中断」那一刻的排队数快照：弹框正文必须用它，不能渲染时再读 queuedCounts——
    // 否则「点中断时队列非空 → 弹框出现 → 队列恰好被消费」会在正文显示「0 条」与用户所见不符。
    var confirmQueuedCount by remember { mutableStateOf(0) }
    // 斜杠命令候选弹窗：输入以 "/" 开头、还在敲命令名（未出现空白）且输入框聚焦时弹出。
    // 候选清单来自服务端注册表（subscribe/commands_update 下发），与 Web composer 同源；
    // 选中即填入 "/命令名 "（带尾空格，就绪输入参数），弹窗随之收起。
    val slashFragment = input.takeIf { it.startsWith("/") && it.none { ch -> ch.isWhitespace() } }
    if (inputFocused && slashFragment != null && view.commands.isNotEmpty()) {
        val partial = slashFragment.removePrefix("/")
        val candidates = view.commands.filter { it.name.startsWith(partial) }
        if (candidates.isNotEmpty()) {
            CommandCandidatePopup(
                candidates = candidates,
                onPick = { name -> onInputChange("/$name ") },
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f).onFocusChanged {
                onInputFocusedChange(it.isFocused)
                if (it.isFocused) {
                    ConnLog.throttled(ConnLogLevel.INFO, "ACTION", "input-focus-gain", 500) { "输入框获得焦点 sessionId=$sessionId" }
                } else {
                    ConnLog.throttled(ConnLogLevel.INFO, "ACTION", "input-focus-lost", 500) { "输入框失去焦点 sessionId=$sessionId" }
                }
            },
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
        // 与 DSH Web 对齐：运行中「终止」与「发送」并存——点终止中断当前推理；
        // 发送照常可用（消息进入排队队列，与桌面端行为一致）。非运行中只显示发送。
        val agentRunning = state.sessions.firstOrNull { it.id == sessionId }?.status == "running" ||
            view.modelWaitingSince != null
        if (agentRunning) {
            val queuedCount = state.queuedCounts[sessionId] ?: 0
            Button(
                onClick = {
                    ConnLog.info("ACTION", "中断点击 sessionId=$sessionId queued=$queuedCount")
                    if (queuedCount > 0) {
                        ConnLog.info("ACTION", "中断确认弹框出现 sessionId=$sessionId queued=$queuedCount")
                        confirmQueuedCount = queuedCount
                        showInterruptConfirm = true
                    } else {
                        client.interrupt(sessionId, "clear")
                    }
                },
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                StopIcon()
            }
            Spacer(Modifier.width(8.dp))
        }
        Button(
            onClick = {
                ConnLog.info("ACTION", "发送点击 sessionId=$sessionId 输入长度=${input.length}")
                val text = input.trim()
                if (text.isNotEmpty()) {
                    onFollowBottom() // 发送后重新跟随底部（要看到自己的消息与回复）
                    client.sendMessage(text)
                    onInputChange("")
                    // 先清焦点再收键盘：焦点仍在输入框时直接 hide 会被 IME 拉回来，一闪一闪
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
            },
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            // 48dp 圆钮配默认 24dp 水平内边距会把内容区挤成 0 宽（图标不可见），
            // 必须归零内边距让 20dp 图标完整渲染。
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(),
        ) {
            SendIcon()
        }
    }
    if (showInterruptConfirm) {
        InterruptConfirmDialog(
            sessionId = sessionId,
            queuedCount = confirmQueuedCount,
            onClear = { client.interrupt(sessionId, "clear"); showInterruptConfirm = false },
            onKeep = { client.interrupt(sessionId, "keep"); showInterruptConfirm = false },
            onDismiss = { showInterruptConfirm = false },
        )
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

/** 终止图标：圆角实心方块（Canvas 自绘，随主题着色）。 */
@Composable
private fun StopIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(16.dp)) {
        drawRoundRect(color = color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
    }
}

// ================= 开发面板 · 调试 Tab =================

/** 调试面板内容：断点 / 调用栈 / 变量 / 输出（小屏紧凑，中文文案）。 */
@Composable
private fun DebugPanelContent(
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
private fun PausedDebugSection(
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
private fun DebugCallStack(
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
private fun DebugScopesVariables(
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
private fun VariableNode(
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

@Composable
private fun EventBubble(e: EventProjection, allEvents: List<EventProjection>) {
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
                labelColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
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
private fun PendingBubble(p: PendingMessage, onRetry: () -> Unit) {
    val labelColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
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
private fun PendingStatusIcon(status: PendingStatus, onRetry: (() -> Unit)?) {
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
private fun ContextRow(e: EventProjection) {
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
private fun CommandCandidatePopup(candidates: List<CommandWire>, onPick: (String) -> Unit) {
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
private fun CommandRow(e: EventProjection, allEvents: List<EventProjection>) {
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
        dragHandle = { ApprovalDragHandle(queueCount) },
    ) {
        ApprovalSheetContent(
            approval = approval,
            sessionTitle = sessionTitle,
            deciding = deciding,
            onDecide = onDecide,
        )
    }
}

/** 审批弹窗警示条（镜像桌面端「等待审批」strip）。 */
@Composable
private fun ApprovalDragHandle(queueCount: Int) {
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
}

/** 审批弹窗正文：会话上下文 / 主文案 / 工具徽章 / 命令文本 / 裁决按钮。 */
@Composable
private fun ApprovalSheetContent(
    approval: ApprovalRequestWire,
    sessionTitle: String?,
    deciding: Boolean,
    onDecide: (ApprovalDecision) -> Unit,
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
        dragHandle = { QuestionDragHandle(queueCount) },
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
                QuestionItemView(
                    item = item,
                    selectedLabels = selections[item.id].orEmpty(),
                    customText = customs[item.id].orEmpty(),
                    onToggleOption = { label ->
                        val cur = selections[item.id].orEmpty().toMutableSet()
                        if (item.multiSelect) {
                            if (label in cur) cur.remove(label) else cur.add(label)
                        } else {
                            cur.clear()
                            cur.add(label)
                        }
                        selections = selections + (item.id to cur)
                    },
                    onCustomChange = { text -> customs = customs + (item.id to text) },
                )
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

/** 提问弹窗警示条（镜像审批「等待审批」strip）。 */
@Composable
private fun QuestionDragHandle(queueCount: Int) {
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
}

/** 单个提问：header / question / detail / options（label + description）或自由文本输入。 */
@Composable
private fun QuestionItemView(
    item: QuestionItemWire,
    selectedLabels: Set<String>,
    customText: String,
    onToggleOption: (String) -> Unit,
    onCustomChange: (String) -> Unit,
) {
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
            val selected = selectedLabels.contains(option.label)
            val clickable = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onToggleOption(option.label) }
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
            value = customText,
            onValueChange = onCustomChange,
            placeholder = { Text("输入你的回答…") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
    }
    Spacer(Modifier.height(14.dp))
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
private fun LocalLogList(shownLocal: List<ConnLogEntry>) {
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
private fun ServerLogList(
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
// 时间格式化：formatTimestamp 见 TimestampFormat.kt；
// 绝对时间戳 formatClock（日志页）与 nowMillis 见 TimeFormat.kt

/** Deep Diving 等待时长文案（服务端时钟秒数透传）。 */
private fun formatDivingDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "本轮 ${s}秒"
        s < 3600 -> {
            val m = s / 60
            val rem = s % 60
            if (rem == 0L) "本轮 ${m}分" else "本轮 ${m}分${rem}秒"
        }
        else -> {
            val h = s / 3600
            val m = (s % 3600) / 60
            if (m == 0L) "本轮 ${h}小时" else "本轮 ${h}小时${m}分"
        }
    }
}

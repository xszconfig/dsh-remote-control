package com.daniel.dshremote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ================= 主界面（已连接） =================

@Composable
internal fun MainScreen(
    client: BridgeClient,
    state: SessionUiState,
    notice: ConnectionNotice,
    onOpenLogs: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
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
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
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
                onOpenSettings = onOpenSettings,
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
internal fun TabletMainScreen(
    client: BridgeClient,
    state: SessionUiState,
    notice: ConnectionNotice,
    onOpenLogs: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val pane = tabletPaneState(state.currentSessionId, state.subagentReturnStack)
    // 中栏渲染的会话：无子会话=当前主会话；有子会话=根主会话（返回栈底，subagentReturnStack.first）。
    val midSessionId = state.subagentReturnStack.firstOrNull() ?: state.currentSessionId
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
                    onOpenSettings = onOpenSettings,
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
                        onOpenSettings = onOpenSettings,
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
                        onOpenSettings = onOpenSettings,
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
internal fun TabletLeftPane(
    client: BridgeClient,
    state: SessionUiState,
    onOpenLogs: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
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
            Spacer(Modifier.weight(1f))
            // 设置入口（侧边栏底部，通常位置）
            DrawerEntry(
                label = "设置",
                badge = null,
                icon = "⚙️",
                selected = false,
                onClick = onOpenSettings,
            )
        }
    }
}

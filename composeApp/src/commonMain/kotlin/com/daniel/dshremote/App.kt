package com.daniel.dshremote

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
    // 通知点击直达的 sheet 目标（sticky + 自愈回退 firstOrNull）
    val sheetTag by NotificationLaunch.requestedTag.collectAsState()
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
    // 主题三态：本地持久化；默认跟随系统
    var themeMode by remember { mutableStateOf(ThemePrefs.load()) }
    var showSettings by remember { mutableStateOf(false) }
    val darkTheme = resolveDarkTheme(themeMode, isSystemInDarkTheme())
    // 冷启动自动连接：设备列表/探测结果就绪后决策一次（上次设备在线则无缝直连）
    LaunchedEffect(devices.devices, devices.deviceStatuses) {
        client.autoConnectOnce()
    }
    DshTheme(darkTheme = darkTheme) {
        // 页面栈原则（docs/ui-navigation-guidelines.md）：A→B→C 时每按一次返回
        // 只回上一级。覆盖层页面（设备页/日志页/扫码）都必须有返回处理，
        // 关闭覆盖层后底下的页面状态原样保留，自然回到上一级。
        if (showSettings) {
            SettingsScreen(
                themeMode = themeMode,
                onThemeModeChange = { m -> themeMode = m; ThemePrefs.save(m) },
                onClose = { showSettings = false },
            )
            PlatformBackHandler(enabled = true) { showSettings = false }
        } else if (showDevices) {
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
                            onOpenSettings = { showSettings = true },
                        )
                    } else {
                        MainScreen(
                            client = client,
                            state = session,
                            notice = notice,
                            onOpenLogs = { showLogs = true },
                            onOpenDevices = { showDevices = true },
                            onOpenSettings = { showSettings = true },
                        )
                    }
                conn.state == ConnectionState.Connecting -> ConnectingScreen(client, conn)
                else -> LandingScreen(client, conn, devices, onOpenLogs = { showLogs = true })
            }
        }
        // 审批/提问都是中断式强提醒：半屏弹窗覆盖所有界面（含首页/扫码/会话），
        // 不可下滑/返回关闭，直到裁决/回答或服务端解决。审批优先于提问。
        // 通知点击直达：优先挂载通知 tag 指向的审批/提问，缺失则回退队首（自愈）。
        val target = parseNotificationSheetTarget(sheetTag)
        val approval = when (target) {
            is NotificationSheetTarget.Approval ->
                session.approvals.firstOrNull { it.approvalId == target.approvalId } ?: session.approvals.firstOrNull()
            else -> session.approvals.firstOrNull()
        }
        val question = if (approval == null) {
            when (target) {
                is NotificationSheetTarget.Question ->
                    session.questions.firstOrNull { it.rpcId == target.rpcId } ?: session.questions.firstOrNull()
                else -> session.questions.firstOrNull()
            }
        } else null
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

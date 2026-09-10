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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daniel.dshremote.protocol.DeviceStatus
import com.daniel.dshremote.protocol.StoredDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** 通知权限引导弹窗：Rationale=说明用途+允许；GoSettings=引导去系统设置开启。 */
@Composable
internal fun NotificationPermissionDialog(
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
internal fun LandingScreen(
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
internal fun LandingHeader(onBack: (() -> Unit)?, onOpenLogs: () -> Unit) {
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
internal fun DeviceListSection(
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
internal fun ManualConnectForm(
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

internal fun statusOf(state: DevicesUiState, device: StoredDevice): DeviceStatus =
    state.deviceStatuses[deviceKey(device)] ?: DeviceStatus.Checking

@Composable
internal fun DeviceCard(
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

internal fun statusLabel(status: DeviceStatus): String = when (status) {
    DeviceStatus.Online -> "在线 · DSH Web 运行中"
    DeviceStatus.Checking -> "检测中…"
    DeviceStatus.Changed -> "在线 · 设备已更换（点击重连）"
    DeviceStatus.Offline -> "离线 · 无法探测到 DSH Web"
}

// ================= 连接中 =================

@Composable
internal fun ConnectingScreen(client: BridgeClient, conn: ConnectionInfo) {
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

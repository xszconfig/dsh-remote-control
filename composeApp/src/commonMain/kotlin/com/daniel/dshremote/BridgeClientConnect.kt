package com.daniel.dshremote

import com.daniel.dshremote.protocol.ClientCommand
import com.daniel.dshremote.protocol.DeviceStatus
import com.daniel.dshremote.protocol.StoredDevice
import com.daniel.dshremote.protocol.StoredEndpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 连接断开后的策略：用户主动断开→清理；有凭据→自动重连；否则→清理。 */
internal fun BridgeClient.onConnectionLost() {
    if (switchingDevice) {
        ConnLog.info("RECONNECT", "设备切换中的旧连接关闭，不触发自动重连")
        switchingDevice = false
        return
    }
    if (userDisconnect) {
        ConnLog.info("RECONNECT", "用户主动断开，不自动重连")
        finishSession("已断开")
        return
    }
    if (reconnectProvider != null) {
        ConnLog.info("RECONNECT", "连接断开且有凭据 → 启动自动重连")
        startReconnect()
    } else {
        ConnLog.info("RECONNECT", "连接断开且无凭据 → 清理会话")
        finishSession(null)
    }
}

/** 结束会话状态：清理易变数据、恢复设备探测。detail 非空时给出错误态。 */
internal fun BridgeClient.finishSession(detail: String?) {
    reconnectJob?.cancel()
    reconnectJob = null
    _notice.value = ConnectionNotice.Hidden
    _reconnectStatus.value = ""
    _session.update { it.clearedForDisconnect() }
    devices.setPollingEnabled(true)
    if (detail != null) connection.fail(detail) else connection.markDisconnected()
}

/** 指数退避自动重连；凭据失效（provider 返回 null）或鉴权错误时放弃。 */
/** 置槽为 Reconnecting：仅由重连循环在「真实发起新一轮尝试」时调用（internal 供单测注入重连态）。 */
internal fun BridgeClient.beginReconnectNotice(attempt: Int) {
    _notice.value = ConnectionNotice.Reconnecting(attempt)
}

internal fun BridgeClient.startReconnect() {
    // 幂等：断开边沿可能多次触发，已有活跃循环时不得重启——
    // 否则新旧循环会在 ConnectionManager 里并发 open()，互相踩 ws/currentUrl 状态。
    // 注意：此处【不】重断言 Reconnecting——重断言只在循环内真实发起新一轮尝试时进行，
    // 否则 onConnectionLost 触发的入口重断言会与「hello 已清槽」竞态，导致横幅在
    // 成功连接后仍残留（真机实测：hello 到达后横幅 ~1 分钟才消失）。
    if (reconnectJob?.isActive == true) return
    reconnectJob = scope.launch {
        try {
            var attempt = 1
            var consecutiveFailures = 0
            while (isActive) {
                val plan = reconnectProvider?.invoke() ?: break
                val wait = reconnectDelayMs(attempt)
                // 连续失败 3 次以上且候选含 127.0.0.1：给出 USB 隧道断开指引
                val hint = if (consecutiveFailures >= 3 && plan.any { it.first.contains("127.0.0.1") }) {
                    "（USB 隧道可能已断开：请插好数据线并执行 adb reverse tcp:3080 tcp:3080）"
                } else {
                    ""
                }
                _reconnectStatus.value = "第 $attempt 次 · ${wait / 1000}s 后重试$hint"
                beginReconnectNotice(attempt)
                connection.markReconnecting(_reconnectStatus.value)
                ConnLog.info("RECONNECT", "第 $attempt 次重试将在 ${wait / 1000}s 后执行$hint")
                delay(wait)
                if (!isActive) return@launch
                val candidates = reconnectProvider?.invoke() ?: break
                var established = false
                for ((index, candidate) in plan.withIndex()) {
                    val (url, token) = candidate
                    ConnLog.info("RECONNECT", "第 $attempt 次重连 · 候选 ${index + 1}/${plan.size}: $url")
                    if (connection.open(url, token)) {
                        established = true
                        break
                    }
                    ConnLog.warn("RECONNECT", "候选失败 $url: ${connection.info.value.detail}")
                }
                if (established) consecutiveFailures = 0 else consecutiveFailures++
                // 成功建立过连接且期间收到过 Hello → 重置退避（横幅由 hello 到达清除）
                if (established && sawHelloThisConnection) {
                    attempt = 1
                    sawHelloThisConnection = false
                    // 横幅由 hello 到达统一清除（handle(Hello)），这里只重置退避。
                    ConnLog.info("RECONNECT", "重连成功，退避重置")
                } else {
                    attempt++
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // 用户断开或放弃：finishSession 已另行处理状态
        }
        // 放弃重连：凭据已失效（取消场景由 finishSession 处理）
        if (reconnectProvider == null) {
            finishSession("自动重连已停止：配对凭据已失效，请重新扫码或手动连接")
        }
    }
}

// ---- 连接 ----

fun BridgeClient.startScan() {
    ConnLog.info("CONNECT", "开始扫码")
    scanStartedOnce = true
    _scanning.value = true
}

fun BridgeClient.stopScan() {
    _scanning.value = false
}

/** 扫码结果：支持 bridge 的 JSON payload 或裸 ws:// 地址。 */
fun BridgeClient.onQrScanned(text: String) {
    _scanning.value = false
    // 配对 URL 里是一次性 pair token，断开后不能重放；DeviceRegistered
    // 事件到达后会换成可重连的设备凭据提供器
    reconnectProvider = null
    userDisconnect = false
    userClosedSessionThisConnection = false
    connectJob?.cancel()
    reconnectJob?.cancel()
    _notice.value = ConnectionNotice.Hidden
    connectJob = scope.launch { connectFromQr(text) }
}

/**
 * 冷启动自动连接：上次连接的设备（或无记录时的最近使用设备）在线 → 直接连接，
 * 免去每次手动点「连接」。只在完全空闲时触发一次；用户进过扫码流程则不打扰。
 */
fun BridgeClient.autoConnectOnce() {
    if (autoConnectAttempted || scanStartedOnce || switchingDevice) return
    if (_scanning.value) return
    val connState = connection.info.value.state
    if (connState == ConnectionState.Connected || connState == ConnectionState.Connecting || _notice.value is ConnectionNotice.Reconnecting || userDisconnect) return
    val st = devices.state.value
    if (st.devices.isEmpty()) return
    // 等首轮探测出结果再决策（Checking 未结束时先不动）
    val statuses = st.deviceStatuses
    if (statuses.isEmpty() || st.devices.any { statuses[deviceKey(it)] == DeviceStatus.Checking }) return
    autoConnectAttempted = true
    val target = st.devices.firstOrNull { deviceKey(it) == devices.lastConnectedKey }
        ?: st.devices.maxByOrNull { it.lastSeenAt }
    if (target == null) {
        ConnLog.info("AUTO", "无候选设备，跳过自动连接")
        return
    }
    val status = st.deviceStatuses[deviceKey(target)] ?: return
    if (status != DeviceStatus.Online && status != DeviceStatus.Changed) {
        ConnLog.info("AUTO", "上次设备离线（$status），等待手动连接: ${target.name}")
        return
    }
    ConnLog.info("AUTO", "上次设备在线 → 自动连接 ${target.name} (${target.host}:${target.port})")
    connectDevice(target)
}

fun BridgeClient.connectDevice(device: StoredDevice) {
    ConnLog.info("CONNECT", "连接设备 ${device.name} (${device.host}:${device.port})")
    userClosedSessionThisConnection = false
    devices.rememberLastConnected(device)
    hydrateFromSessionCache(device)
    val key = deviceKey(device)
    // 已有连接/重连循环 → 先干净断开旧链路再连新设备（多设备切换）
    val wasConnected = connection.info.value.state == ConnectionState.Connected || _notice.value is ConnectionNotice.Reconnecting
    if (wasConnected) {
        ConnLog.info("CONNECT", "切换设备 → 先断开当前链路")
        switchingDevice = true
        userDisconnect = true // 抑制旧链路的自动重连
        reconnectJob?.cancel()
        reconnectJob = null
        _notice.value = ConnectionNotice.Hidden
        _reconnectStatus.value = ""
        connectJob?.cancel()
        connection.close()
        _session.update { it.clearedForDisconnect() }
    }
    userDisconnect = false
    reconnectProvider = {
        // 每次重试取最新存储的设备记录（token 可能已被轮换）；
        // 候选列表按存储顺序逐个快试（主端点在前，多路由回退）
        val stored = devices.state.value.devices.firstOrNull { deviceKey(it) == key }
        if (stored == null) null
        else {
            val eps = stored.endpoints.ifEmpty { listOf(StoredEndpoint(stored.host, stored.port)) }
            eps.map { Pairing.buildUrl(it.host, it.port) to stored.token }
        }
    }
    // 首次直连同样走多候选（主端点优先、4s 快速失败逐路回退）：
    // USB 隧道失效时自动落到局域网/Tailscale 端点，无需等重连循环
    connectJob?.cancel()
    connectJob = scope.launch {
        if (hintName(device)) _session.update { it.copy(connectedDevice = device) }
        val eps = device.endpoints.ifEmpty { listOf(StoredEndpoint(device.host, device.port)) }
        var ok = false
        for ((index, e) in eps.withIndex()) {
            ConnLog.info("CONNECT", "直连候选 ${index + 1}/${eps.size}: ${e.host}:${e.port}")
            if (connection.open(Pairing.buildUrl(e.host, e.port), device.token)) {
                ok = true
                break
            }
            ConnLog.warn("CONNECT", "直连候选失败 ${e.host}:${e.port}: ${connection.info.value.detail}")
        }
        if (!ok) {
            // 全部候选失败：撤销直连前 hintName 挂上的设备名，避免设备页同时出现
            // 「连接失败」横幅（conn.state=Error）与「已连接 · 当前设备」（connectedDevice 残留）自相矛盾。
            _session.update { it.clearConnectedDeviceIf(key) }
            if (eps.any { it.host == "127.0.0.1" }) {
                connection.fail(
                    connection.info.value.detail.ifBlank { "连接失败" } +
                        "\n提示：USB 连接请先在电脑上执行 adb reverse tcp:3080 tcp:3080",
                )
            }
        }
    }
}

fun BridgeClient.connectManual(host: String, port: Int, token: String?) {
    ConnLog.info("CONNECT", "手动连接 $host:$port token=${if (token.isNullOrBlank()) "无" else "有"}")
    reconnectProvider = { listOf(Pairing.buildUrl(host, port) to token) }
    userDisconnect = false
    userClosedSessionThisConnection = false
    connect(host, port, token, null)
}

fun BridgeClient.disconnect() {
    ConnLog.info("CONNECT", "用户断开连接")
    userDisconnect = true
    scope.launch {
        connectJob?.cancel()
        finishSession(null)
        connection.close()
    }
}

/** 直连时是否已把 hint 设备名挂上会话状态（Hello 注册时取名称用）。 */
internal fun BridgeClient.hintName(device: StoredDevice): Boolean {
    _session.update { it.copy(connectedDevice = device) }
    return true
}

internal fun BridgeClient.connect(host: String, port: Int, token: String?, hint: StoredDevice?) {
    connectJob?.cancel()
    connectJob = scope.launch {
        // 先挂上 hint 设备名：Hello 到达时 registerIfNeeded 才能取到存储的名称
        if (hint != null) _session.update { it.copy(connectedDevice = hint) }
        val ok = connection.open(Pairing.buildUrl(host, port), token)
        if (!ok && host == "127.0.0.1") {
            // bridge 仅监听本机：USB 场景下 127.0.0.1 不通几乎一定是没设 reverse
            connection.fail(
                connection.info.value.detail.ifBlank { "连接失败" } +
                    "\n提示：USB 连接请先在电脑上执行 adb reverse tcp:$port tcp:$port",
            )
        }
    }
}

internal suspend fun BridgeClient.connectFromQr(text: String) {
    val urls = Pairing.parseQr(text)
    if (urls.isEmpty()) {
        ConnLog.warn("QR", "无法识别的二维码内容: ${text.take(80)}")
        connection.fail("无法识别的二维码内容")
        return
    }
    ConnLog.info("QR", "扫码得到 ${urls.size} 个候选地址: ${urls.joinToString(" | ")}")
    // 逐候选尝试并记录各自失败原因（refused/timeout/401…），全部失败时给出完整诊断
    val failures = mutableListOf<Pair<String, String>>()
    for (url in urls) {
        ConnLog.info("QR", "尝试候选: $url")
        if (connection.open(url, null)) return
        val reason = connection.info.value.detail
        failures.add(Pairing.endpointOf(url).host to reason)
        ConnLog.warn("QR", "候选失败 ${Pairing.endpointOf(url).host}: $reason")
    }
    connection.fail(buildConnectFailureDetail(failures))
}

// ---- 设备管理 ----

fun BridgeClient.forgetDevice(device: StoredDevice) {
    ConnLog.info("CONNECT", "忘记设备 ${device.name} (${device.host}:${device.port})")
    val key = deviceKey(device)
    scope.launch {
        devices.remove(key)
        val connected = _session.value.connectedDevice
        if (connected != null && deviceKey(connected) == key) {
            if (!connection.send(ClientCommand.RevokeDevice(device.deviceId))) {
                pushConnectionError("撤销桌面端凭据失败（连接已断开）")
            }
        }
    }
}

internal fun BridgeClient.registerIfNeeded(serverId: String, hostname: String?) {
    if (registeredThisConnection) return
    registeredThisConnection = true
    val phoneId = devices.ensurePhoneId()
    val host = connection.currentUrl?.let { Pairing.endpointOf(it).host } ?: return
    val hint = _session.value.connectedDevice
    val name = hint?.name?.takeIf { it.isNotBlank() }
        ?: hostname?.takeIf { it.isNotBlank() }
        ?: host
    scope.launch {
        if (!connection.send(
                ClientCommand.RegisterDevice(
                    deviceId = phoneId,
                    name = name,
                    model = platformDeviceModel(),
                ),
            )
        ) {
            pushConnectionError("设备注册失败（连接已断开）")
        }
    }
}

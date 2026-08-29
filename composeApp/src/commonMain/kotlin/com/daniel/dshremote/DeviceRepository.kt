@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.daniel.dshremote

import com.daniel.dshremote.protocol.BridgeJson
import com.daniel.dshremote.protocol.DeviceStatus
import com.daniel.dshremote.protocol.PingInfo
import com.daniel.dshremote.protocol.StoredDevice
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 设备面状态：已配对设备列表 + 各自的在线探测结果。 */
data class DevicesUiState(
    val devices: List<StoredDevice> = emptyList(),
    val deviceStatuses: Map<String, DeviceStatus> = emptyMap(),
)

/** 一次探测的结果。 */
data class PingResult(
    val key: String,
    val status: DeviceStatus,
    val serverId: String? = null,
    val hostname: String? = null,
)

/**
 * 探测结果合并进设备列表（纯函数）：
 * - 在线/已更换 → 按需补齐 serverId/hostname；lastSeenAt 只在超过
 *   [LAST_SEEN_PERSIST_INTERVAL_MS] 节流间隔时刷新（避免每 12s 落盘）；
 * - 无任何字段实际变化时原样返回（调用方据此跳过落盘）。
 */
fun applyPingResults(devices: List<StoredDevice>, results: List<PingResult>, now: Long): List<StoredDevice> {
    val byKey = results.associateBy { it.key }
    return devices.map { d ->
        val r = byKey[deviceKey(d)] ?: return@map d
        if (r.status != DeviceStatus.Online && r.status != DeviceStatus.Changed) return@map d
        val bumpSeen = d.lastSeenAt < now - LAST_SEEN_PERSIST_INTERVAL_MS
        val fillServerId = d.serverId == null && r.serverId != null
        val fillHostname = d.hostname == null && r.hostname != null
        if (bumpSeen || fillServerId || fillHostname) {
            d.copy(
                lastSeenAt = if (bumpSeen) now else d.lastSeenAt,
                serverId = d.serverId ?: r.serverId,
                hostname = d.hostname ?: r.hostname,
            )
        } else {
            d
        }
    }
}

/**
 * 设备资产管理：持久化（[DeviceStore]）、12s 在线探测轮询、增删。
 * 会话/连接状态不在此处——本类只关心「我配对过哪些桌面」。
 */
class DeviceRepository(
    private val scope: CoroutineScope,
    private val store: DeviceStore,
) {
    private val pingClient: HttpClient = createPingHttp()

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    /** 本机手机 id（服务端按它签发长期 token）。 */
    var phoneId: String = ""
        private set

    /** 上次连接的设备 key（冷启动自动连接候选，随连接动作持久化）。 */
    var lastConnectedKey: String? = null
        private set

    /** 取手机 id；空白时生成并持久化一个新的。 */
    fun ensurePhoneId(): String {
        if (phoneId.isBlank()) {
            phoneId = kotlin.uuid.Uuid.random().toString()
            val id = phoneId
            scope.launch { store.update { it.copy(phoneId = id) } }
        }
        return phoneId
    }

    /** 记录「上次连接」的设备，冷启动自动连接据此找目标。 */
    fun rememberLastConnected(device: StoredDevice) {
        val key = deviceKey(device)
        lastConnectedKey = key
        scope.launch { store.update { it.copy(lastConnectedKey = key) } }
    }

    /** 仅在未连接桌面时探测（已连接时在线状态没有意义）。 */
    @Volatile
    private var pollingEnabled: Boolean = true

    private var pollJob: Job? = null

    init {
        pollJob = scope.launch {
            val file = store.load()
            phoneId = file.phoneId
            lastConnectedKey = file.lastConnectedKey
            _state.update { it.copy(devices = file.devices) }
            refreshStatuses()
            while (isActive) {
                delay(12_000)
                if (pollingEnabled && _state.value.devices.isNotEmpty()) refreshStatuses()
            }
        }
    }

    /** 连接建立后停探测、断开后恢复。 */
    fun setPollingEnabled(enabled: Boolean) {
        pollingEnabled = enabled
        if (enabled) scope.launch { refreshStatuses() }
    }

    suspend fun refreshStatuses() {
        val devices = _state.value.devices
        if (devices.isEmpty()) return
        _state.update { s ->
            s.copy(
                deviceStatuses = s.deviceStatuses +
                    devices.associate { deviceKey(it) to DeviceStatus.Checking },
            )
        }
        val results = coroutineScope {
            devices.map { device -> async { ping(device) } }.awaitAll()
        }
        val updated = applyPingResults(devices, results, nowMillis())
        _state.update {
            it.copy(
                deviceStatuses = it.deviceStatuses + results.associate { r -> r.key to r.status },
                devices = updated,
            )
        }
        if (updated != devices) {
            store.update { f -> f.copy(devices = updated) }
        }
    }

    /** 新设备注册/更新凭据（同 host:port 覆盖旧记录）。 */
    suspend fun upsert(device: StoredDevice) {
        val key = deviceKey(device)
        _state.update { s -> s.copy(devices = s.devices.filterNot { deviceKey(it) == key } + device) }
        store.update { f -> f.copy(devices = f.devices.filterNot { deviceKey(it) == key } + device) }
    }

    suspend fun remove(key: String) {
        _state.update { s ->
            s.copy(
                devices = s.devices.filterNot { deviceKey(it) == key },
                deviceStatuses = s.deviceStatuses - key,
            )
        }
        store.update { f -> f.copy(devices = f.devices.filterNot { deviceKey(it) == key }) }
    }

    suspend fun removeByDeviceId(deviceId: String) {
        _state.update { s -> s.copy(devices = s.devices.filterNot { it.deviceId == deviceId }) }
        store.update { f -> f.copy(devices = f.devices.filterNot { it.deviceId == deviceId }) }
    }

    private suspend fun ping(device: StoredDevice): PingResult = try {
        val resp = pingClient.get("http://${device.host}:${device.port}/remote/ping")
        val info = BridgeJson.decodeFromString(PingInfo.serializer(), resp.bodyAsText())
        if (info.ok) {
            val changed = device.serverId != null && info.serverId != null &&
                device.serverId != info.serverId
            PingResult(
                deviceKey(device),
                if (changed) DeviceStatus.Changed else DeviceStatus.Online,
                info.serverId,
                info.hostname,
            )
        } else {
            PingResult(deviceKey(device), DeviceStatus.Offline)
        }
    } catch (_: Exception) {
        PingResult(deviceKey(device), DeviceStatus.Offline)
    }
}

package com.daniel.dshremote

import android.content.Context
import com.daniel.dshremote.protocol.BridgeJson
import com.daniel.dshremote.protocol.DeviceFile
import com.daniel.dshremote.protocol.StoredDevice
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 用 app 私有目录下的 JSON 文件持久化设备列表。 */
class AndroidDeviceStore(private val context: Context) : DeviceStore {

    private val file: File
        get() = File(context.filesDir, "devices.json")

    override suspend fun load(): DeviceFile = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext DeviceFile(phoneId = newId())
            val df = BridgeJson.decodeFromString(DeviceFile.serializer(), file.readText())
            if (df.phoneId.isBlank()) df.copy(phoneId = newId()) else df
        } catch (_: Exception) {
            DeviceFile(phoneId = newId())
        }
    }

    override suspend fun save(devices: List<StoredDevice>) = withContext(Dispatchers.IO) {
        try {
            val current = read()
            file.writeText(
                BridgeJson.encodeToString(DeviceFile.serializer(), current.copy(devices = devices)),
            )
        } catch (_: Exception) {
            // 持久化失败不影响在线功能
        }
    }

    private fun read(): DeviceFile = try {
        if (!file.exists()) DeviceFile(phoneId = newId())
        else BridgeJson.decodeFromString(DeviceFile.serializer(), file.readText())
    } catch (_: Exception) {
        DeviceFile(phoneId = newId())
    }

    private fun newId(): String = UUID.randomUUID().toString()
}

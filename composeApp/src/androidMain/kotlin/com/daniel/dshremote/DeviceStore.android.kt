package com.daniel.dshremote

import com.daniel.dshremote.protocol.BridgeJson
import com.daniel.dshremote.protocol.DeviceFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 用 app 私有目录下的 JSON 文件持久化设备列表。
 *
 * 并发安全：所有读写经 [mutex] 串行化，[cached] 内存副本是唯一事实来源，
 * save 期间的读-改-写不会互相覆盖（修复此前两处并发 save 丢设备/丢 token 的竞态）。
 * 落盘走临时文件 + rename，进程被杀也不会留下半截 JSON。
 */
class AndroidDeviceStore(private val dir: File) : DeviceStore {

    private val file: File
        get() = File(dir, "devices.json")

    private val mutex = Mutex()
    private var cached: DeviceFile? = null

    override suspend fun load(): DeviceFile = mutex.withLock {
        cached ?: readFromDisk().also { cached = it }
    }

    override suspend fun update(transform: (DeviceFile) -> DeviceFile): DeviceFile = mutex.withLock {
        val current = cached ?: readFromDisk().also { cached = it }
        val next = transform(current)
        if (next != current) {
            writeAtomically(next)
            cached = next
        }
        next
    }

    private fun readFromDisk(): DeviceFile = try {
        if (!file.exists()) DeviceFile(phoneId = newId())
        else BridgeJson.decodeFromString(DeviceFile.serializer(), file.readText())
            .let { if (it.phoneId.isBlank()) it.copy(phoneId = newId()) else it }
    } catch (_: Exception) {
        DeviceFile(phoneId = newId())
    }

    private fun writeAtomically(df: DeviceFile) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "devices.json.tmp")
            tmp.writeText(BridgeJson.encodeToString(DeviceFile.serializer(), df))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Exception) {
            // 持久化失败不影响在线功能；内存缓存仍保留最新数据
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}

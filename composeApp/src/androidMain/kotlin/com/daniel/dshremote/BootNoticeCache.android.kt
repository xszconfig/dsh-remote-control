package com.daniel.dshremote

import com.daniel.dshremote.protocol.BridgeJson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * server_boot「已读版本」落盘实现：单个 JSON 文件存 key→version 映射，
 * 临时文件 + rename 原子写（与 AndroidDraftCache 同一套机制）。
 */
class AndroidBootNoticeCache(private val dir: File) : BootNoticeCache {

    private val mutex = Mutex()
    private val file: File get() = File(dir, "boot-notices.json")
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    override suspend fun load(key: String): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) null
                else BridgeJson.decodeFromString(serializer, file.readText())[key]
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun save(key: String, version: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                dir.mkdirs()
                val all = try {
                    BridgeJson.decodeFromString(serializer, file.readText()).toMutableMap()
                } catch (_: Exception) {
                    mutableMapOf()
                }
                if (version.isBlank()) all.remove(key) else all[key] = version
                val body = BridgeJson.encodeToString(serializer, all)
                val tmp = File(dir, "boot-notices.json.tmp")
                tmp.writeText(body)
                if (!tmp.renameTo(file)) {
                    file.writeText(body)
                    tmp.delete()
                }
            } catch (_: Exception) {
                // 已读版本写失败不影响在线功能
            }
        }
    }
}

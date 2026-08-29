package com.daniel.dshremote

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 草稿缓存落盘实现：单个 JSON 文件存 key→text 映射，临时文件 + rename 原子写。
 */
class AndroidDraftCache(private val dir: File) : DraftCache {

    private val mutex = Mutex()
    private val file: File get() = File(dir, "drafts.json")
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    override suspend fun load(key: String): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) null
                else Json.decodeFromString(serializer, file.readText())[key]
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun save(key: String, text: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                dir.mkdirs()
                val all = try {
                    Json.decodeFromString(serializer, file.readText()).toMutableMap()
                } catch (_: Exception) {
                    mutableMapOf()
                }
                if (text.isBlank()) all.remove(key) else all[key] = text
                val body = Json.encodeToString(serializer, all)
                val tmp = File(dir, "drafts.json.tmp")
                tmp.writeText(body)
                if (!tmp.renameTo(file)) {
                    file.writeText(body)
                    tmp.delete()
                }
            } catch (_: Exception) {
                // 草稿写失败不影响在线功能
            }
        }
    }
}

package com.daniel.dshremote

import com.daniel.dshremote.protocol.BridgeJson
import com.daniel.dshremote.protocol.EventProjection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 事件缓存落盘实现：每个 key 一个 JSON 文件，临时文件 + rename 原子写。
 * key 非法字符替换为下划线，防止路径穿越。
 */
class AndroidEventCache(private val dir: File) : EventCache {

    private val mutex = Mutex()

    private fun fileOf(key: String): File =
        File(dir, "events-" + key.map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '_' }.joinToString("") + ".json")

    override suspend fun load(key: String): List<EventProjection> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val f = fileOf(key)
                if (!f.exists()) emptyList()
                else BridgeJson.decodeFromString(ListSerializer, f.readText())
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override suspend fun save(key: String, events: List<EventProjection>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                dir.mkdirs()
                val f = fileOf(key)
                val tmp = File(dir, f.name + ".tmp")
                tmp.writeText(BridgeJson.encodeToString(ListSerializer, events))
                if (!tmp.renameTo(f)) {
                    f.writeText(tmp.readText())
                    tmp.delete()
                }
            } catch (_: Exception) {
                // 缓存写入失败不影响在线功能
            }
        }
    }

    private companion object {
        val ListSerializer =
            kotlinx.serialization.builtins.ListSerializer(EventProjection.serializer())
    }
}

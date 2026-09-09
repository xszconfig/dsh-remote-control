package com.daniel.dshremote

import com.daniel.dshremote.protocol.BridgeJson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 已通知幂等键落盘实现：单文件 `notified-keys.json`（字符串数组），临时文件 + rename 原子写。
 * 并发安全：所有读写经 [mutex] 串行化；读-改-写（add/remove）在锁内完成，防并发覆盖丢写。
 */
class AndroidNotifiedKeysStore(private val dir: File) : NotifiedKeysStore {

    private val mutex = Mutex()
    private val file = File(dir, "notified-keys.json")

    override suspend fun load(): Set<String> = mutex.withLock {
        withContext(Dispatchers.IO) { readSet() }
    }

    override suspend fun add(key: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = readSet()
            if (key in current) return@withContext
            writeSet(current + key)
        }
    }

    override suspend fun remove(key: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = readSet()
            if (key !in current) return@withContext
            writeSet(current - key)
        }
    }

    private fun readSet(): Set<String> = try {
        if (!file.exists()) emptySet()
        else BridgeJson.decodeFromString(ListSerializer(String.serializer()), file.readText()).toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun writeSet(keys: Set<String>) {
        try {
            dir.mkdirs()
            if (keys.isEmpty()) {
                if (file.exists()) file.delete()
                return
            }
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(BridgeJson.encodeToString(ListSerializer(String.serializer()), keys.toList().sorted()))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Exception) {
            // 持久化失败不影响在线功能；内存态仍保留（但无法跨重启恢复）
        }
    }
}

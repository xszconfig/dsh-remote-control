package com.daniel.dshremote

import com.daniel.dshremote.protocol.BridgeJson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * 待发送消息落盘实现：每个 sessionId 一个 JSON 文件，临时文件 + rename 原子写。
 * sessionId 非法字符替换为下划线，防止路径穿越（与 AndroidSessionCache 同机制）。
 *
 * 并发安全：所有读写经 [mutex] 串行化；[update] 的读-改-写在锁内完成，
 * 并发 upsert/删除不会互相覆盖（与 AndroidDeviceStore 同一机制）。
 */
class AndroidPendingStore(private val dir: File) : PendingStore {

    private val mutex = Mutex()

    private fun fileOf(sessionId: String): File =
        File(
            dir,
            "pending-" +
                sessionId.map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '_' }
                    .joinToString("") + ".json",
        )

    override suspend fun load(sessionId: String): List<PendingMessage> = mutex.withLock {
        withContext(Dispatchers.IO) { readFile(fileOf(sessionId)) }
    }

    override suspend fun save(sessionId: String, pending: List<PendingMessage>) = mutex.withLock {
        withContext(Dispatchers.IO) { writeFile(sessionId, pending) }
    }

    override suspend fun update(
        sessionId: String,
        transform: (List<PendingMessage>) -> List<PendingMessage>,
    ): List<PendingMessage> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = readFile(fileOf(sessionId))
            val next = transform(current)
            if (next != current) writeFile(sessionId, next)
            next
        }
    }

    private fun readFile(f: File): List<PendingMessage> = try {
        if (!f.exists()) emptyList()
        else BridgeJson.decodeFromString(ListSerializer, f.readText())
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeFile(sessionId: String, pending: List<PendingMessage>) {
        try {
            dir.mkdirs()
            val f = fileOf(sessionId)
            if (pending.isEmpty()) {
                // 消息已归服务端/无待发送：删除持久化记录（回显由服务端历史承载）
                if (f.exists()) f.delete()
                return
            }
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(BridgeJson.encodeToString(ListSerializer, pending))
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Exception) {
            // 持久化失败不影响在线功能；内存态仍保留最新数据（但无法跨重启恢复）
        }
    }

    private companion object {
        val ListSerializer = ListSerializer(PendingMessage.serializer())
    }
}

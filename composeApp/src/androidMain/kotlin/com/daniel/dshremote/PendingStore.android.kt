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
 *
 * 写失败不再静默（P00 铁律「用户消息永不丢失」）：落盘失败记 ConnLog 告警、
 * 该批消息标记 persisted=false（UI ⚠️）；同时进 [dirtyWrites] 残留，下一次 save/update
 * 时机会式重试补落盘，磁盘恢复后自动消除 transient full 残留。
 */
class AndroidPendingStore(
    private val dir: File,
    /** 低层落盘（可注入测试桩模拟存储满/写失败）；默认临时文件 + rename 原子写。 */
    private val writeRaw: (File, String) -> Unit = { f, body ->
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
    },
) : PendingStore {

    private val mutex = Mutex()

    /** 上次写失败残留（sessionId -> 当时完整的待落盘列表），用于机会式重试补落盘。 */
    private val dirtyWrites = mutableMapOf<String, List<PendingMessage>>()

    private fun fileOf(sessionId: String): File =
        File(
            dir,
            "pending-" +
                sessionId.map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '_' }
                    .joinToString("") + ".json",
        )

    override suspend fun load(sessionId: String): List<PendingMessage> = mutex.withLock {
        withContext(Dispatchers.IO) {
            readFile(fileOf(sessionId)).map { it.copy(persisted = true) }
        }
    }

    override suspend fun save(sessionId: String, pending: List<PendingMessage>): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ok = writeFile(sessionId, pending)
            if (ok) dirtyWrites.remove(sessionId) else dirtyWrites[sessionId] = pending
            ok
        }
    }

    override suspend fun update(
        sessionId: String,
        transform: (List<PendingMessage>) -> List<PendingMessage>,
    ): List<PendingMessage> = mutex.withLock {
        withContext(Dispatchers.IO) {
            // 机会式重试：上次写失败的残留（内存态，比磁盘新）作为基准，磁盘恢复后一并补落盘
            val hadDirty = dirtyWrites[sessionId] != null
            val base = dirtyWrites[sessionId] ?: readFile(fileOf(sessionId))
            val next = transform(base)
            val needWrite = hadDirty || next != base
            val ok = if (!needWrite) true else writeFile(sessionId, next)
            if (ok) {
                dirtyWrites.remove(sessionId)
                next.map { it.copy(persisted = true) }
            } else {
                dirtyWrites[sessionId] = next
                next.map { it.copy(persisted = false) }
            }
        }
    }

    private fun readFile(f: File): List<PendingMessage> = try {
        if (!f.exists()) emptyList()
        else BridgeJson.decodeFromString(ListSerializer, f.readText())
    } catch (_: Exception) {
        emptyList()
    }

    /** 落盘；返回是否成功（false = 写失败，内存态仍有效但未持久化）。 */
    private fun writeFile(sessionId: String, pending: List<PendingMessage>): Boolean {
        return try {
            dir.mkdirs()
            val f = fileOf(sessionId)
            if (pending.isEmpty()) {
                // 消息已归服务端/无待发送：删除持久化记录（回显由服务端历史承载）
                if (f.exists()) f.delete()
                true
            } else {
                writeRaw(f, BridgeJson.encodeToString(ListSerializer, pending))
                true
            }
        } catch (e: Exception) {
            // 写失败显式化：不静默吞，告警 + 标记未落盘（P00 铁律）
            ConnLog.warn("PENDING", "待发送消息落盘失败 sessionId=$sessionId 条数=${pending.size}: ${e.message}")
            false
        }
    }

    private companion object {
        val ListSerializer = ListSerializer(PendingMessage.serializer())
    }
}

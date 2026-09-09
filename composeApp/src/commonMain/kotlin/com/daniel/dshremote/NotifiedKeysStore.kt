package com.daniel.dshremote

/**
 * 已通知幂等键持久化（防进程被杀/重启后重连重复通知）。
 *
 * 键集全局唯一（不分会话）：`approval:{id}` / `question:{rpcId}` / `delivery:{sessionId}:{turnKey}`。
 * 实现方负责：并发串行化（防丢写）与原子写（临时文件 + rename，防写一半损坏）——与 PendingStore 同机制。
 */
interface NotifiedKeysStore {
    /** 载入全部已通知键（首次启动后恢复）。 */
    suspend fun load(): Set<String>

    /** 新增一个已通知键（幂等：已存在则无副作用）。 */
    suspend fun add(key: String)

    /** 移除一个已通知键（如审批/提问已裁决撤回）。 */
    suspend fun remove(key: String)
}

package com.daniel.dshremote

import com.daniel.dshremote.protocol.CachedSessionSnapshot

/**
 * 会话/工作区元数据本地缓存（按 key 持久化，key 用桌面端 serverId 隔离）：
 * 打开 App 先渲染缓存实现秒开；Hello 快照到达后做增量对账并回写。
 * 与 EventCache 分工：这里存「列表元数据」，事件正文在 EventCache。
 */
interface SessionCache {
    suspend fun load(key: String): CachedSessionSnapshot?
    suspend fun save(key: String, snapshot: CachedSessionSnapshot)
}

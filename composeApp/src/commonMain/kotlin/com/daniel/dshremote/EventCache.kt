package com.daniel.dshremote

import com.daniel.dshremote.protocol.EventProjection

/**
 * 会话事件本地缓存（按 key 持久化）：打开会话先渲染缓存实现秒开，
 * 订阅返回后以服务端历史为准覆盖。断线/重连期间也先展示缓存。
 */
interface EventCache {
    suspend fun load(key: String): List<EventProjection>
    suspend fun save(key: String, events: List<EventProjection>)
}

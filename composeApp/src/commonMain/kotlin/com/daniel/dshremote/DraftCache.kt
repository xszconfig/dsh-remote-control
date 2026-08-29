package com.daniel.dshremote

/**
 * 输入框草稿缓存（按 key 持久化）：连接断开/重连、切会话、App 重启后，
 * 未发送的输入文本原样恢复，不丢用户打字。空文本 = 清除草稿。
 */
interface DraftCache {
    suspend fun load(key: String): String?
    suspend fun save(key: String, text: String)
}

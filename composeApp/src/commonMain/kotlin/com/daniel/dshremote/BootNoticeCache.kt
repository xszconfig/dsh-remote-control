package com.daniel.dshremote

/**
 * 「服务端已重启」横幅的已读版本记忆（按 key 持久化）：
 * key = 桌面端指纹（serverId 优先，退化端点），value = 已点「知道了」的 server_boot 版本号。
 * 语义：一个服务端标识 + 一个版本只提示一次；版本变化（服务端升级）→ 重新提示。
 * 跨重连、跨 App 重启均生效。
 */
interface BootNoticeCache {
    suspend fun load(key: String): String?
    suspend fun save(key: String, version: String)
}

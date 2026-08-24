package com.daniel.dshremote

import androidx.compose.runtime.Composable
import io.ktor.client.HttpClient

/** 当前时间（epoch 毫秒）。 */
expect fun currentTimeMillis(): Long

/** 本地时区相对 UTC 的偏移（分钟）。 */
expect fun localOffsetMinutes(): Int

/** 手机型号（用于注册到桌面端）。 */
expect fun platformDeviceModel(): String?

/** WebSocket 长连接客户端（无请求超时）。 */
expect fun createWsHttp(): HttpClient

/** HTTP 探测客户端（短超时）。 */
expect fun createPingHttp(): HttpClient

/** 全屏二维码扫描界面。 */
@Composable
expect fun QrScanner(onScanned: (String) -> Unit, onCancel: () -> Unit)

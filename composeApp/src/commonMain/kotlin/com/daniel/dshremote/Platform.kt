package com.daniel.dshremote

import androidx.compose.runtime.Composable
import io.ktor.client.HttpClient

/** 当前时间（epoch 毫秒）与时间格式化见 TimeFormat.kt（kotlinx-datetime）。 */

/** 手机型号（用于注册到桌面端）。 */
expect fun platformDeviceModel(): String?

/** WebSocket 长连接客户端（无请求超时）。 */
expect fun createWsHttp(): HttpClient

/** HTTP 探测客户端（短超时）。 */
expect fun createPingHttp(): HttpClient

/** 全屏二维码扫描界面。 */
@Composable
expect fun QrScanner(onScanned: (String) -> Unit, onCancel: () -> Unit)

/** 新审批到达的强提醒振动（中断式审批提示）。 */
expect fun platformVibrateApproval()

/** 系统返回键拦截（Android: OnBackPressedDispatcher，后注册者优先）。 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** 退出应用回到桌面（等效系统返回键走到根：finish 当前 Activity）。 */
expect fun platformExitApp()

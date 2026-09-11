package com.daniel.dshremote

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppContext.context = applicationContext
        AppContext.activity = this
        // 前台状态跟踪：主线程注册 + 回填当前态（首帧即正确，供 FGS 启动判定与通知门控）
        AppForeground.init()
        // 通知点击直达：冷启动读取 sessionId extra（BridgeClient 消费后打开对应会话）
        NotificationLaunch.requestedSessionId.value = intent?.getStringExtra(EXTRA_NOTIFY_SESSION_ID)
        val deviceStore = AndroidDeviceStore(applicationContext.filesDir)
        val eventCache = AndroidEventCache(File(applicationContext.filesDir, "event-cache"))
        val sessionCache = AndroidSessionCache(File(applicationContext.filesDir, "session-cache"))
        val draftCache = AndroidDraftCache(File(applicationContext.filesDir, "draft-cache"))
        val bootNoticeCache = AndroidBootNoticeCache(File(applicationContext.filesDir, "boot-notice-cache"))
        val pendingStore = AndroidPendingStore(File(applicationContext.filesDir, "pending-store"))
        val notifiedKeysStore = AndroidNotifiedKeysStore(File(applicationContext.filesDir, "notified-keys"))
        setContent {
            val client = remember {
                BridgeClient(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    store = deviceStore,
                    eventCache = eventCache,
                    sessionCache = sessionCache,
                    draftCache = draftCache,
                    bootNoticeCache = bootNoticeCache,
                    pendingStore = pendingStore,
                    notifiedKeysStore = notifiedKeysStore,
                )
            }
            App(client)
        }
    }

    /** 通知点击直达：热启动（Activity 已在栈顶 singleTop）时接收新 intent。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationLaunch.requestedSessionId.value = intent.getStringExtra(EXTRA_NOTIFY_SESSION_ID)
    }

    /** 通知权限申请结果：同意 → 隐藏引导；拒绝 → 转「去设置」。 */
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFY_PERMISSION_REQ_CODE) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            NotificationPermissionState.prompt.value =
                if (granted) NotificationPermissionPrompt.Hidden else NotificationPermissionPrompt.GoSettings
        }
    }
}

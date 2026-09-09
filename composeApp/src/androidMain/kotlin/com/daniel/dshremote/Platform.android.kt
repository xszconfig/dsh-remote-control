package com.daniel.dshremote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit

/** 应用级 Context 持有者（MainActivity.onCreate 注入；振动等平台能力使用）。 */
object AppContext {
    @Volatile
    var context: android.content.Context? = null

    /** 当前 Activity 引用（退出应用用；单 Activity 应用）。 */
    @Volatile
    var activity: android.app.Activity? = null
}

internal actual fun platformConnLog(level: ConnLogLevel, tag: String, message: String) {
    try {
        val fullTag = "dsh-conn/$tag"
        when (level) {
            ConnLogLevel.DEBUG -> android.util.Log.d(fullTag, message)
            ConnLogLevel.INFO -> android.util.Log.i(fullTag, message)
            ConnLogLevel.WARN -> android.util.Log.w(fullTag, message)
            ConnLogLevel.ERROR -> android.util.Log.e(fullTag, message)
        }
    } catch (_: Exception) {
        // 日志本身永不抛异常
    }
}

actual fun platformDeviceModel(): String? = Build.MODEL

actual fun platformVibrateApproval() {
    try {
        val context = AppContext.context ?: return
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) {
            // 强提醒：双短振 + 停顿 + 长振
            vibrator.vibrate(
                android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 500), -1),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 300, 200, 500), -1)
        }
    } catch (_: Exception) {
        // 振动失败不影响审批流程
    }
}

/** 消息转盘步进触感：单发短振 + 受控振幅（适中，明显轻于审批强提醒）。 */
actual fun platformVibrateTick(boundary: Boolean) {
    try {
        val context = AppContext.context ?: return
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) {
            // createOneShot(ms, amplitude)：amplitude 1~255；适中 ≈ 96，触底/顶稍重 ≈ 128。
            val ms = if (boundary) 30L else 15L
            val amplitude = if (boundary) 128 else 96
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(ms, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(if (boundary) 30L else 15L)
        }
    } catch (_: Exception) {
        // 触感失败不影响转盘功能
    }
}

// ---- 前台/后台状态跟踪（ProcessLifecycleOwner）+ 主动通知发送 ----

/** 应用前台状态：onStart 起算为前台，onStop 为后台/锁屏（ProcessLifecycleOwner 观测）。 */
internal object AppForeground {
    @Volatile
    private var foreground = false
    @Volatile
    private var registered = false

    private fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            registered = true
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) { foreground = true }
                    override fun onStop(owner: LifecycleOwner) { foreground = false }
                })
            } catch (_: Exception) {
                // 观测失败不阻塞通知流程；保守按后台处理（宁可发通知也不漏）
            }
        }
    }

    fun isForeground(): Boolean {
        ensureRegistered()
        return foreground
    }
}

internal actual fun platformIsAppForeground(): Boolean = AppForeground.isForeground()

/** 通知渠道 + 发送/撤销（平台 Notification.Builder 直用，零 androidx.core 依赖）。 */
internal object NotificationPoster {
    private const val CHANNEL_APPROVAL = "dsh_approval"
    private const val CHANNEL_DELIVERY = "dsh_delivery"
    private const val CHANNEL_DELIVERY_SILENT = "dsh_delivery_silent"

    private fun ensureChannels(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVAL, "审批与提问", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "需要你及时响应的审批与提问"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DELIVERY, "结果交付", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "异步会话的结果交付提醒"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DELIVERY_SILENT, "结果交付（夜间静默）", NotificationManager.IMPORTANCE_LOW).apply {
                description = "夜间勿扰时段的结果交付提醒（无声音振动）"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun channelIdFor(spec: NotificationSpec): String = when (spec.kind) {
        NotificationKind.APPROVAL, NotificationKind.QUESTION -> CHANNEL_APPROVAL
        NotificationKind.DELIVERY -> if (spec.silent) CHANNEL_DELIVERY_SILENT else CHANNEL_DELIVERY
    }

    private fun smallIconFor(spec: NotificationSpec): Int = when (spec.kind) {
        NotificationKind.APPROVAL, NotificationKind.QUESTION -> android.R.drawable.stat_sys_warning
        NotificationKind.DELIVERY -> android.R.drawable.stat_notify_chat
    }

    fun isGranted(context: android.content.Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun post(context: android.content.Context, spec: NotificationSpec) {
        try {
            if (!isGranted(context)) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            ensureChannels(nm)
            // 点击直达：PendingIntent 带 sessionId extra，拉起 MainActivity 后解析并打开对应会话
            val launch = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                spec.sessionId?.let { putExtra(EXTRA_NOTIFY_SESSION_ID, it) }
            }
            val contentIntent = PendingIntent.getActivity(
                context, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, channelIdFor(spec))
                .setSmallIcon(smallIconFor(spec))
                .setContentTitle(spec.title)
                .setContentText(spec.body)
                .setStyle(Notification.BigTextStyle().bigText(spec.body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build()
            nm.notify(spec.tag, spec.tag.hashCode(), notification)
        } catch (_: Exception) {
            // 通知失败不影响审批流程
        }
    }

    fun cancel(context: android.content.Context, tag: String) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.cancel(tag, tag.hashCode())
        } catch (_: Exception) {
            // 撤销失败忽略
        }
    }
}

internal actual fun platformPostNotification(spec: NotificationSpec) {
    val context = AppContext.context ?: return
    if (NotificationPoster.isGranted(context)) {
        NotificationPoster.post(context, spec)
    } else {
        // 未授权：提示 rationale（App.kt 渲染引导）；本条通知丢弃（审批仍可在页内弹窗/重连补发）
        NotificationPermissionState.prompt.value = NotificationPermissionPrompt.Rationale
    }
}

internal actual fun platformCancelNotification(tag: String) {
    val context = AppContext.context ?: return
    NotificationPoster.cancel(context, tag)
}

internal actual fun platformRequestNotificationPermission() {
    try {
        @Suppress("DEPRECATION")
        AppContext.activity?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFY_PERMISSION_REQ_CODE)
    } catch (_: Exception) {
        // 无 Activity 或系统拒绝，静默
    }
}

internal actual fun platformOpenNotificationSettings() {
    val context = AppContext.context ?: return
    try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        // 无设置页可跳则忽略
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}

actual fun platformExitApp() {
    try {
        AppContext.activity?.finish()
    } catch (_: Exception) {
        // 退出失败不阻塞主流程
    }
}

actual fun createWsHttp(): HttpClient = HttpClient(OkHttp) {
    install(WebSockets)
    engine {
        config {
            // 只限制建连阶段：二维码含多个候选地址（127.0.0.1 + LAN/隧道 IP），
            // 不可达候选必须快速失败回退到下一个。Ktor 的 HttpTimeout 插件
            // 不会把 connectTimeout 传给 OkHttp（默认 10s），必须在 engine 配置。
            // 不设 readTimeout——WS 长连接由 pingInterval 保活。
            connectTimeout(4, TimeUnit.SECONDS)
            pingInterval(30, TimeUnit.SECONDS)
        }
    }
}

actual fun createPingHttp(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = 3_500
        requestTimeoutMillis = 3_500
        socketTimeoutMillis = 3_500
    }
}

// ---- 二维码扫描（ZXing embedded + 相机运行时权限）----

@Composable
actual fun QrScanner(onScanned: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (!granted) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("需要相机权限才能扫码", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("授予权限") }
            }
        } else {
            ScannerPreview(onScanned)
        }
        // 顶部操作条
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("✕ 取消", color = Color.White) }
        }
        // 底部提示
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "对准桌面端「DSH 远程配对」页面上的二维码",
                color = Color(0xCCFFFFFF),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ScannerPreview(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    var torch by remember { mutableStateOf(false) }
    val view = remember {
        DecoratedBarcodeView(context).apply {
            val formats = listOf(BarcodeFormat.QR_CODE)
            setDecoderFactory(DefaultDecoderFactory(formats))
            setStatusText("")
            decodeSingle(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    val text = result.result?.text
                    if (!text.isNullOrBlank()) onScanned(text)
                }

                override fun possibleResultPoints(result: List<ResultPoint>) = Unit
            })
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.resume()
                Lifecycle.Event.ON_PAUSE -> view.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { view },
            modifier = Modifier.fillMaxSize(),
        )
        // 手电筒
        TextButton(
            onClick = {
                torch = !torch
                try {
                    if (torch) view.setTorchOn() else view.setTorchOff()
                } catch (_: Exception) {
                    torch = !torch
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 56.dp, end = 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0x66000000)),
        ) {
            Text(if (torch) "🔦 关灯" else "🔦 开灯", color = Color.White)
        }
    }
}

internal actual fun platformLoadThemeMode(): String? {
    val ctx = AppContext.context ?: return null
    return try {
        ctx.openFileInput("theme_mode.txt").bufferedReader().use { it.readText().trim() }
    } catch (_: Exception) {
        null
    }
}

internal actual fun platformSaveThemeMode(mode: String) {
    val ctx = AppContext.context ?: return
    try {
        ctx.openFileOutput("theme_mode.txt", android.content.Context.MODE_PRIVATE).use { it.write(mode.toByteArray()) }
    } catch (_: Exception) {
        // 持久化失败不阻塞（下次启动回退跟随系统）
    }
}

/** 同步窗口背景与系统栏图标色到当前主题（enableEdgeToEdge 后状态栏区域露出 window 背景）。 */
@Composable
actual fun SystemBarsSync(darkTheme: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            window.decorView.setBackgroundColor(if (darkTheme) 0xFF0B0F1A.toInt() else 0xFFF8F9FC.toInt())
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

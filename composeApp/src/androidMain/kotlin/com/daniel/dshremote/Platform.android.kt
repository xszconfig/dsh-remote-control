package com.daniel.dshremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

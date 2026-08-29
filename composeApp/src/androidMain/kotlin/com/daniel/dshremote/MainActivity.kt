package com.daniel.dshremote

import android.os.Bundle
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
        val deviceStore = AndroidDeviceStore(applicationContext.filesDir)
        setContent {
            val client = remember {
                BridgeClient(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    store = deviceStore,
                )
            }
            App(client)
        }
    }
}

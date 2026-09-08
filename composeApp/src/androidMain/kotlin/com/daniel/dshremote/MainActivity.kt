package com.daniel.dshremote

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
        val deviceStore = AndroidDeviceStore(applicationContext.filesDir)
        val eventCache = AndroidEventCache(File(applicationContext.filesDir, "event-cache"))
        val sessionCache = AndroidSessionCache(File(applicationContext.filesDir, "session-cache"))
        val draftCache = AndroidDraftCache(File(applicationContext.filesDir, "draft-cache"))
        val bootNoticeCache = AndroidBootNoticeCache(File(applicationContext.filesDir, "boot-notice-cache"))
        val pendingStore = AndroidPendingStore(File(applicationContext.filesDir, "pending-store"))
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
                )
            }
            App(client)
        }
    }
}

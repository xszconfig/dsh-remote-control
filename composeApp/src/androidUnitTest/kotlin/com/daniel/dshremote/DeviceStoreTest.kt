package com.daniel.dshremote

import com.daniel.dshremote.protocol.DeviceFile
import com.daniel.dshremote.protocol.StoredDevice
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class DeviceStoreTest {

    private fun newTempStore(): Pair<AndroidDeviceStore, File> {
        val dir = File(System.getProperty("java.io.tmpdir"), "dsh-store-test-${System.nanoTime()}")
        return AndroidDeviceStore(dir) to dir
    }

    private fun device(n: Int) = StoredDevice(
        deviceId = "dev-$n",
        name = "机$n",
        host = "192.168.1.$n",
        port = 3080,
        token = "t$n",
        serverId = "srv",
        hostname = "mac",
        createdAt = n.toLong(),
        lastSeenAt = n.toLong(),
    )

    @Test
    fun load_freshDirectory_getsStablePhoneId() = runBlocking {
        val (store, dir) = newTempStore()
        try {
            val f1 = store.load()
            assertTrue(f1.phoneId.isNotBlank())
            assertEquals(emptyList(), f1.devices)
            // 再 load 拿到同一 phoneId（内存缓存）
            assertEquals(f1.phoneId, store.load().phoneId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun update_persistsAndReloads() = runBlocking {
        val (store, dir) = newTempStore()
        try {
            store.update { it.copy(devices = listOf(device(1), device(2))) }
            // 新实例从磁盘读（模拟进程重启）
            val reloaded = AndroidDeviceStore(dir).load()
            assertEquals(listOf(device(1), device(2)), reloaded.devices)
            assertEquals(store.load().phoneId, reloaded.phoneId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun update_concurrentWriters_doNotLoseUpdates() = runBlocking {
        // 回归：旧实现是并发 save() 各自读旧文件再整体覆盖，丢设备；
        // 新实现 update() 串行化读-改-写，50 个并发添加必须全部落盘。
        val (store, dir) = newTempStore()
        try {
            coroutineScope {
                (1..50).map { n ->
                    async(Dispatchers.IO) {
                        store.update { f -> f.copy(devices = f.devices + device(n)) }
                    }
                }.awaitAll()
            }
            val saved = AndroidDeviceStore(dir).load()
            assertEquals(50, saved.devices.size)
            assertEquals((1..50).map { "dev-$it" }.toSet(), saved.devices.map { it.deviceId }.toSet())
            assertEquals(store.load().phoneId, saved.phoneId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun update_noChange_skipsDiskWrite() = runBlocking {
        val (store, dir) = newTempStore()
        try {
            store.update { it.copy(devices = listOf(device(1))) }
            val file = File(dir, "devices.json")
            val firstStamp = file.lastModified()
            // 等文件系统时间粒度过去再跑一次无变化 update
            var waited = 0
            while (file.lastModified() == firstStamp && waited < 3000) {
                Thread.sleep(50); waited += 50
            }
            val result = store.update { it } // 恒等变换
            assertEquals(listOf(device(1)), result.devices)
            assertEquals(firstStamp, file.lastModified()) // 未重写文件
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun load_corruptedFile_fallsBackToFresh() = runBlocking {
        val (store, dir) = newTempStore()
        try {
            store.update { it.copy(devices = listOf(device(1))) }
            File(dir, "devices.json").writeText("{ 半截 JSON")
            val f = store.load() // 内存缓存还在
            assertEquals(listOf(device(1)), f.devices)
            // 新实例读到损坏文件 → 返回空白而非崩溃
            val fresh = AndroidDeviceStore(dir).load()
            assertTrue(fresh.phoneId.isNotBlank())
            assertEquals(emptyList(), fresh.devices)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun load_blankPhoneId_regenerated() = runBlocking {
        val (store, dir) = newTempStore()
        try {
            store.update { it.copy(devices = listOf(device(1))) }
            // 磁盘上 phoneId 被清空（手工编辑/旧版本数据）
            File(dir, "devices.json").writeText(
                """{"phoneId":"","devices":[{"deviceId":"dev-1","name":"机1","host":"192.168.1.1",
                   "port":3080,"token":"t1","serverId":"srv","hostname":"mac","createdAt":1,"lastSeenAt":1}]}""",
            )
            val f = AndroidDeviceStore(dir).load()
            assertTrue(f.phoneId.isNotBlank())
            assertNotEquals("", f.phoneId)
            assertEquals(1, f.devices.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}

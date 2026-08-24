package com.daniel.dshremote

import com.daniel.dshremote.protocol.DeviceFile
import com.daniel.dshremote.protocol.StoredDevice

/** 已连接设备的本地持久化（含手机自身 phoneId）。 */
interface DeviceStore {
    suspend fun load(): DeviceFile
    suspend fun save(devices: List<StoredDevice>)
}

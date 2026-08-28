package com.daniel.dshremote

import com.daniel.dshremote.protocol.DeviceFile

/** 已连接设备的本地持久化（含手机自身 phoneId）。 */
interface DeviceStore {
    suspend fun load(): DeviceFile

    /**
     * 原子地读-改-写整个设备文件：实现方负责串行化并发更新（防丢写）与
     * 落盘的原子性（防写一半损坏）。返回更新后的文件内容。
     */
    suspend fun update(transform: (DeviceFile) -> DeviceFile): DeviceFile
}

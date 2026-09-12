package com.daniel.dshremote

/**
 * 待发送消息持久化（按 sessionId 维度键）：任何用户消息都是明确指令，
 * 发送中（Sending）/失败（Failed）都必须落盘——断线、切会话、杀进程、App 重启后
 * 该会话的待发送消息不能丢；恢复时 sending 一律转 failed（结果未知，交用户手动重发）。
 *
 * 落盘只写 Sending/Failed；[PendingStatus.Sent] 不落盘（消息已归服务端，回显由历史承载）。
 * 实现方负责：并发串行化（防丢写）与原子写（临时文件 + rename，防写一半损坏）。
 *
 * 写失败不再静默：save 返回 Boolean，update 返回的列表里 [PendingMessage.persisted]=false
 * 标记「未落盘」，供 UI 展示 ⚠️；实现方内部保留「脏写」记录，下一次 save/update 时机会式
 * 重试补落盘（磁盘恢复后自动消除 transient full 残留）。
 */
interface PendingStore {
    /** 载入某会话的全部待发送消息（Sending/Failed；读出的消息 persisted 恒为 true）。 */
    suspend fun load(sessionId: String): List<PendingMessage>

    /** 全量覆盖某会话的待发送消息；空列表 = 清除该会话记录（消息已归服务端）。返回是否成功落盘。 */
    suspend fun save(sessionId: String, pending: List<PendingMessage>): Boolean

    /**
     * 原子读-改-写某会话的待发送消息：实现方负责在串行化锁内完成「读 → transform → 落盘」，
     * 防止并发 upsert/删除互相覆盖丢写（与 [DeviceStore.update] 同一机制）。
     * 返回更新后的列表，其中 [PendingMessage.persisted] 反映本次是否真正落盘成功。
     */
    suspend fun update(
        sessionId: String,
        transform: (List<PendingMessage>) -> List<PendingMessage>,
    ): List<PendingMessage>
}

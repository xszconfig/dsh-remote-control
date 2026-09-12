package com.daniel.dshremote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 待发送消息落盘 + 「未落盘」标记回写内存的统一入口。
 * 落盘失败不再静默：store 返回的 persisted 标志（update）或 save 结果同步回
 * SessionUiState.pendingMessages，供 UI 显示 ⚠️（未持久化）；下次状态变更机会式重试落盘成功后自动清除。
 */
internal suspend fun persistPendingUpdate(
    store: PendingStore,
    session: MutableStateFlow<SessionUiState>,
    sessionId: String,
    transform: (List<PendingMessage>) -> List<PendingMessage>,
) {
    val persisted = store.update(sessionId, transform)
    syncPersistedFlags(session, sessionId, persisted)
}

/** 全量覆盖落盘（restore 回写等）：把 save 结果映射为 persisted 标志回写内存。 */
internal suspend fun persistPendingSave(
    store: PendingStore,
    session: MutableStateFlow<SessionUiState>,
    sessionId: String,
    pending: List<PendingMessage>,
) {
    val ok = store.save(sessionId, pending)
    syncPersistedFlags(session, sessionId, pending.map { it.copy(persisted = ok) })
}

/** 把落盘结果里的 persisted 标志同步回内存（按 msgId 对齐，仅影响该会话的消息）。 */
private fun syncPersistedFlags(
    session: MutableStateFlow<SessionUiState>,
    sessionId: String,
    persisted: List<PendingMessage>,
) {
    val flags = persisted.associate { it.msgId to it.persisted }
    session.update { s ->
        s.copy(
            pendingMessages = s.pendingMessages.map { m ->
                if (m.sessionId == sessionId) {
                    flags[m.msgId]?.let { m.copy(persisted = it) } ?: m
                } else {
                    m
                }
            },
        )
    }
}

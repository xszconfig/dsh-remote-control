package com.daniel.dshremote

import com.daniel.dshremote.protocol.CachedSessionSnapshot
import com.daniel.dshremote.protocol.ClientCommand
import com.daniel.dshremote.protocol.StoredDevice
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 通知点击直达：会话已加载则立即打开；未加载（hello 未到）则暂存待 hello 后打开。 */
internal fun BridgeClient.handleNotificationOpen(sessionId: String) {
    val target = resolveNotificationOpenTarget(sessionId, _session.value.sessions)
    if (target != null) {
        ConnLog.info("NOTIFY", "通知直达打开会话 session=${target.take(8)}")
        openSession(target)
        pendingOpenSessionId = null
    } else {
        pendingOpenSessionId = sessionId
        ConnLog.info("NOTIFY", "通知直达暂存待 hello session=${sessionId.take(8)}")
    }
}

/** hello 后消费暂存的直达会话：命中则打开，未命中则停留会话列表。 */
internal fun BridgeClient.maybeOpenPendingNotificationSession() {
    val sid = pendingOpenSessionId ?: return
    val target = resolveNotificationOpenTarget(sid, _session.value.sessions)
    pendingOpenSessionId = null
    if (target != null) {
        ConnLog.info("NOTIFY", "通知直达打开会话 session=${target.take(8)}")
        openSession(target)
    } else {
        ConnLog.info("NOTIFY", "通知直达会话未找到 session=${sid.take(8)}，停留会话列表")
    }
}

// ---- 会话操作 ----

/** 侧边栏切换工作区视图；null = 全部会话。 */
fun BridgeClient.selectWorkspace(workspaceId: String?) {
    _session.update { it.copy(selectedWorkspaceId = workspaceId) }
}

/** 打开会话（会话列表点击 / 通知直达 / 自动打开）：切换会话清空子代理返回栈。 */
fun BridgeClient.openSession(sessionId: String) = navigateToSession(sessionId, emptyList(), SessionViewState())
/** 内部导航：切到指定会话 + 重置会话级状态 + 携带给定子代理返回栈/父视图投影。 */
internal fun BridgeClient.navigateToSession(sessionId: String, subagentReturnStack: List<String>, parentView: SessionViewState) {
    if (sessionId.isBlank()) return
    ConnLog.info("CMD", "打开会话 sessionId=$sessionId 前会话=${_session.value.currentSessionId} 栈深=${subagentReturnStack.size}")
    sessionOpenStartAt[sessionId] = nowMillis()
    // 切换会话必须清空全部会话级状态（避免上个会话的指示条/诊断/目标/调试串到新会话），
    // 由 SessionUiState.forSession 统一重置并携带返回栈/父视图投影。
    _session.update { it.forSession(sessionId, subagentReturnStack, parentView) }
    scope.launch {
        // 恢复本地待发送消息：断线/杀进程/切换视图后不丢（P00）。sending 一律转 failed
        // （发送结果未知，避免自动重发导致重复投递，交用户点 ❗ 手动决定）。
        val restored = restorePendingFromDisk(pendingStore.load(sessionId))
        if (restored.isNotEmpty()) {
            _session.update { s ->
                if (s.currentSessionId == sessionId) {
                    // 合并而非覆盖：openSession 已同步清空，但恢复期间的极短窗口内可能又发出新消息
                    val others = s.pendingMessages.filterNot { it.sessionId == sessionId }
                    s.copy(pendingMessages = restored + others)
                } else {
                    s
                }
            }
            // 回写已转换状态（sending→failed），保证下次重启不再重复转换
            pendingStore.save(sessionId, restored)
            ConnLog.info("PENDING", "会话 $sessionId 恢复待发送消息 ${restored.size} 条（sending→failed）")
        }
        // 断线自动重放：对恢复出的 failed 消息（retryCount < 上限）按序自动重发（同 msgId 幂等）
        pendingSender.autoReplaySession(sessionId)
        // 先渲染本地缓存（秒开），订阅返回后以服务端历史为准
        val key = eventCacheKey(sessionId)
        val cached = eventCache.load(key)
        if (cached.isNotEmpty()) {
            _session.update { s ->
                if (s.currentSessionId == sessionId) s.copy(events = cached.bounded()) else s
            }
            ConnLog.info("CACHE", "会话 $sessionId 载入本地缓存 ${cached.size} 条")
        }
        if (!connection.send(ClientCommand.Subscribe(sessionId))) pushConnectionError("订阅会话失败（连接已断开）")
    }
}

/** 会话事件缓存 key：设备端点 + 会话 id（不同桌面互不串扰）。 */
internal fun BridgeClient.eventCacheKey(sessionId: String): String {
    val device = _session.value.connectedDevice
    val prefix = device?.let { "${it.host}_${it.port}" } ?: "unknown"
    return "$prefix-$sessionId"
}

// ---- 输入框草稿（断线/重连/重启不丢打字）----

internal fun BridgeClient.draftKey(sessionId: String): String {
    val device = _session.value.connectedDevice
    val prefix = device?.let { it.serverId ?: "${it.host}_${it.port}" } ?: "unknown"
    return "$prefix-$sessionId"
}

/** 载入某会话的待发送草稿。 */
suspend fun BridgeClient.loadDraft(sessionId: String): String? = draftCache.load(draftKey(sessionId))

/** 保存草稿（空白 = 清除）；节流由 UI 层防抖负责。 */
fun BridgeClient.saveDraft(sessionId: String, text: String) {
    scope.launch { draftCache.save(draftKey(sessionId), text) }
}

/** 事件到达后节流落盘（2s 节流，避免每条事件都写文件）。 */
internal fun BridgeClient.scheduleCacheSave() {
    val sid = _session.value.currentSessionId ?: return
    val now = nowMillis()
    if (now - lastCacheSaveAt < 2_000) return
    lastCacheSaveAt = now
    cacheSaveJob?.cancel()
    cacheSaveJob = scope.launch {
        val events = _session.value.events
        if (events.isEmpty()) return@launch
        eventCache.save(eventCacheKey(sid), events)
    }
}

// ---- 会话/工作区元数据缓存（列表秒开 + 增量对账）----

/** 会话元数据缓存 key：桌面指纹 serverId 优先（网络切换不变），退化为端点。 */
internal fun BridgeClient.sessionCacheKeyOf(device: StoredDevice): String =
    device.serverId ?: "${device.host}_${device.port}"

/** server_boot「已读版本」记忆 key：桌面指纹 serverId 优先，退化为端点（不同桌面互不吞提示）。 */
internal fun BridgeClient.bootNoticeKey(device: StoredDevice): String =
    device.serverId ?: "${device.host}_${device.port}"

/**
 * 连接开始时用本地缓存水合会话/工作区列表（打开 App 秒开，不实时拉）；
 * Hello 快照到达后做增量对账覆盖。仅在状态为空时水合，不打断在线数据。
 */
internal fun BridgeClient.hydrateFromSessionCache(device: StoredDevice) {
    val cur = _session.value
    if (cur.sessions.isNotEmpty() || cur.workspaces.isNotEmpty()) return
    val key = sessionCacheKeyOf(device)
    scope.launch {
        val snap = sessionCache.load(key) ?: return@launch
        ConnLog.info("CACHE", "会话列表缓存命中：${snap.sessions.size} 会话 / ${snap.workspaces.size} 工作区")
        _session.update { s ->
            if (s.sessions.isNotEmpty() || s.workspaces.isNotEmpty()) s
            else s.copy(
                sessions = snap.sessions,
                workspaces = snap.workspaces,
                selectedWorkspaceId = snap.selectedWorkspaceId ?: s.selectedWorkspaceId,
            )
        }
    }
}

/** 元数据变更后节流回写（2s），与事件缓存同节奏。 */
internal fun BridgeClient.scheduleSessionCacheSave() {
    val device = _session.value.connectedDevice ?: return
    val now = nowMillis()
    if (now - lastSessionCacheSaveAt < 2_000) return
    lastSessionCacheSaveAt = now
    sessionCacheSaveJob?.cancel()
    val key = sessionCacheKeyOf(device)
    sessionCacheSaveJob = scope.launch {
        val s = _session.value
        sessionCache.save(
            key,
            CachedSessionSnapshot(
                sessions = s.sessions,
                workspaces = s.workspaces,
                selectedWorkspaceId = s.selectedWorkspaceId,
                savedAt = nowMillis(),
            ),
        )
    }
}

/**
 * 关闭当前会话视图，返回会话列表。纯本地导航：协议没有「取消订阅」命令
 * （bridge 对 Subscribe(null) 会回 not_found 错误），未订阅会话的事件
 * 在 handle() 里按 sessionId 过滤丢弃，无需通知服务端。
 */
fun BridgeClient.closeSession() {
    val stack = _session.value.subagentReturnStack
    ConnLog.info("CMD", "关闭会话 current=${_session.value.currentSessionId} 返回栈深=${stack.size}")
    // 子代理视图的关闭 = 逐级返回：弹栈回到立即父会话（C→B→A→主会话→列表）
    val returnTo = subagentReturnPeek(stack)
    if (returnTo != null) {
        val newStack = subagentReturnPop(stack)
        val newParentView = if (newStack.isEmpty()) SessionViewState() else _session.value.parentView
        navigateToSession(returnTo, newStack, newParentView)
        return
    }
    // 真正退回列表：标记本连接生命周期内用户手动关闭过会话，抑制后续 hello 自动打开
    userClosedSessionThisConnection = true
    _session.update {
        it.copy(currentSessionId = null, events = emptyList(), parentView = SessionViewState())
    }
}

/** 从当前会话进入子代理会话：把当前会话压入返回栈，返回键/← 逐级弹栈回主会话。 */
fun BridgeClient.openSubagent(subagentId: String) {
    val cur = _session.value
    val parentId = cur.currentSessionId ?: return
    if (parentId == subagentId) return
    // 平板 route B：首次下钻（栈空）时快照根主会话投影到 parentView，
    // 之后中栏持续 live 渲染根主会话（栈底），更深下钻不再覆盖。
    val firstDrill = cur.subagentReturnStack.isEmpty()
    val newStack = subagentReturnPush(cur.subagentReturnStack, parentId)
    val newParentView = if (firstDrill) cur.currentView() else cur.parentView
    ConnLog.info("CMD", "打开子代理 subagentId=$subagentId parentId=$parentId 返回栈深=${newStack.size}")
    navigateToSession(subagentId, newStack, newParentView)
}

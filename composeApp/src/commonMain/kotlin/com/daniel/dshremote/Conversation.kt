package com.daniel.dshremote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// ================= 会话详情 =================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Conversation(client: BridgeClient, state: SessionUiState, sessionId: String) {
    // 会话详情投影：手机=currentSessionId；平板中栏=parentView（子会话打开时主会话 live）。
    val view = state.viewOf(sessionId)
    var input by remember { mutableStateOf(TextFieldValue("")) }
    // 输入框焦点：斜杠命令候选弹窗只在聚焦时出现（草稿载入不误弹）
    var inputFocused by remember { mutableStateOf(false) }
    // 草稿：进入会话时从磁盘载入未发送文本；输入变化防抖落盘。
    // 断线/重连、切会话、App 重启都不丢用户打字。
    LaunchedEffect(sessionId) {
        client.loadDraft(sessionId)?.takeIf { it.isNotEmpty() }?.let { input = TextFieldValue(it) }
        snapshotFlow { input }
            .drop(1) // 跳过载入草稿触发的那次
            .debounce(600)
            .collect { text -> client.saveDraft(sessionId, text.text) }
    }
    // 返回键 = 左上角 ←：回到会话列表，不退出应用
    PlatformBackHandler(enabled = true) { client.closeSession() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 转盘状态提升到 Conversation：滚底 effect 需读 phase 判断是否抑制强制滚底（新消息不打断转盘交互）。
    val dialState = remember(sessionId) { MessageDialState() }
    val latestSeq = view.events.lastOrNull()?.seq
    // 自动跟随底部状态机：
    // - 默认跟随（新消息到达 → 滚到底部；切会话重置为跟随）
    // - 用户上滑浏览历史 → 滚动停稳后暂停跟随（让他看）
    // - 用户滑回底部 → 恢复跟随（reverseLayout 下 canScrollForward=false 即底部）
    var followBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    // reverseLayout 下 index 0 = 底部：canScrollBackward=false 即已贴底。
                    // （不能用 canScrollForward——那是"上方还有更早历史"的方向，几乎恒为 true）
                    followBottom = !listState.canScrollBackward
                }
            }
    }
    // 切会话/打开会话：重置为跟随并定位底部
    LaunchedEffect(sessionId) {
        followBottom = true
        if (view.events.isNotEmpty()) listState.scrollToItem(0)
    }
    // 新消息到达（含刚发出的消息回显）→ 仅当处于跟随态才滚到列表底部。
    // reverseLayout 下 index 0 = 底部最新；以最后事件 seq 为键，
    // 列表达 MAX_EVENTS 上限后 size 不再增长也能继续触发。
    LaunchedEffect(latestSeq) {
        // 转盘处于任何非收起态时挂起强制滚底，避免新消息打断转盘交互；收起后恢复跟随。
        if (followBottom && view.events.isNotEmpty() && dialState.phase == DialPhase.Collapsed) {
            listState.scrollToItem(0)
        }
    }
    // 新 pending 上屏（刚发送）→ 跟随态滚到底部看到自己的消息（pending 不在 events 里，需单独触发）。
    val pendingCount = view.pendingMessages.count { it.sessionId == sessionId }
    LaunchedEffect(pendingCount) {
        if (followBottom && dialState.phase == DialPhase.Collapsed) {
            listState.scrollToItem(0)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ConversationMessageList(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                client = client,
                view = view,
                sessionId = sessionId,
                listState = listState,
                dialState = dialState,
                onJumpToBottom = {
                    followBottom = true
                    scope.launch { listState.scrollToItem(0) }
                },
            )
            ConversationPanels(view = view, client = client, sessionId = sessionId)
            ConversationDevPanel(view = view, client = client, sessionId = sessionId)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ConversationComposer(
                client = client,
                state = state,
                view = view,
                sessionId = sessionId,
                input = input,
                onInputChange = { input = it },
                inputFocused = inputFocused,
                onInputFocusedChange = { inputFocused = it },
                onFollowBottom = { followBottom = true },
            )
        }
    }
}

/** 消息列表区：空态提示，或 LazyColumn + 回到底部悬浮按钮 + 消息转盘（转盘必须挂载在列表 Box 内）。 */
@Composable
internal fun ConversationMessageList(
    modifier: Modifier,
    client: BridgeClient,
    view: SessionViewState,
    sessionId: String,
    listState: LazyListState,
    dialState: MessageDialState,
    onJumpToBottom: () -> Unit,
) {
    val pendingForSession = view.pendingMessages.filter { it.sessionId == sessionId }
    if (view.events.isEmpty() && pendingForSession.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("💬", fontSize = 30.sp)
                Spacer(Modifier.height(8.dp))
                Text("暂无事件，发条指令试试", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        // 上滑到最早一条附近时自动加载更早的历史页（reverseLayout 下最高 index = 最早）
        val shouldLoadOlder by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val topIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                topIndex >= info.totalItemsCount - 3
            }
        }
        LaunchedEffect(shouldLoadOlder, view.hasMore, view.loadingOlder) {
            if (shouldLoadOlder && view.hasMore && !view.loadingOlder) {
                client.loadOlderPage(sessionId)
            }
        }
        // 「回到底部」按钮显隐：最新一条消息不在可见区（且列表已布局）→ 显示。
        // liveThink 流式行占 index 0 时最新消息在 index 1，否则在 index 0。
        val latestIndex = latestEventIndex(view.liveThink != null)
        val showJumpToBottom by remember(latestIndex, listState) {
            derivedStateOf {
                val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
                visible.isNotEmpty() && !latestMessageVisible(visible, latestIndex)
            }
        }
        // 转盘按需显示：showDial = 最新消息不可见 OR 非收起态（手指活动优先于「到底隐藏」）。
        // 隐藏时收起：不得在旋转中（手指还在转盘上滑动）强制 collapse；松手后由转盘内 2.5s 自动收起自然关闭。
        LaunchedEffect(showJumpToBottom) {
            val rotatingFinger = dialState.phase == DialPhase.Rotating && dialState.fingerDown
            if (!showJumpToBottom && dialState.phase != DialPhase.Collapsed && !rotatingFinger) {
                dialState.collapse()
            }
        }
        Box(modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                reverseLayout = true,
            ) {
                // 思考流式：一行持续刷新（reverseLayout 下首个 item = 最新位置，即底部）
                view.liveThink?.let { lt ->
                    item(key = "live-think") { LiveThinkRow(lt) }
                }
                // 本地待发送消息：乐观上屏的用户气泡（时间行带 Loading / ❗），回显到达后移除。
                items(pendingForSession.asReversed(), key = { "pending-${it.msgId}" }) { p ->
                    PendingBubble(p, onRetry = { client.retryMessage(p.msgId) })
                }
                items(view.events.asReversed(), key = { "${it.seq}-${it.type}" }) { e ->
                    EventBubble(e, view.events)
                }
                if (view.loadingOlder) {
                    item(key = "loading-older") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
            // 「回到底部」悬浮按钮：位于 Deep Diving 上方、右对齐（bottomEnd 即消息列表右下角）。
            // 淡入淡出（不做位移动画，避免突兀）；点击瞬间 scrollToItem(0) + 置回跟随态。
            JumpToBottomOverlay(
                visible = showJumpToBottom,
                onClick = onJumpToBottom,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp),
            )
            // 消息转盘：挂载在消息列表 Box 内，圆钮悬浮于左下角（Deep Diving 上方）；
            // 按需显示：上翻离开底部出现，展开/旋转中即使已到底部也保持挂载（不打断手指）。
            if (showDial(showJumpToBottom, dialState.phase)) {
                MessageDial(
                    dial = dialState,
                    view = view,
                    listState = listState,
                    onLoadOlder = { client.loadOlderPage(sessionId) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 面板区：Deep Diving 条 + 任务列表 + Goal + 排队消息（按上到下顺序，位于消息列表下方）。 */
@Composable
internal fun ConversationPanels(view: SessionViewState, client: BridgeClient, sessionId: String) {
    DeepDivingBar(view)
    TodoPanel(view)
    GoalPanel(view)
    QueuePanel(view, client, sessionId)
}

/** Deep Diving：与 DSH Web 对齐——放在任务列表/排队消息面板上方（不在列表顶部）。 */
@Composable
internal fun DeepDivingBar(view: SessionViewState) {
    // 深 Seek 品牌蓝；标签在整个轮次期间显示（服务端 turn_status），时钟在 ≥15s 后出现
    // （DSH Web showClock 阈值），时长只显示服务端推送的 deepDivingElapsed（不本地计时）。
    val divingVisible = view.divingTurnStart != null || view.modelWaitingSince != null
    if (divingVisible) {
        val elapsed = view.deepDivingElapsed ?: 0
        val showClock = elapsed >= 15
        Surface(color = DeepSeekBlue.copy(alpha = 0.12f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🤿", fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Deep Diving",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = DeepSeekBlue,
                )
                if (showClock) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = DeepSeekBlue.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        formatDivingDuration(elapsed),
                        style = MaterialTheme.typography.labelMedium,
                        color = DeepSeekBlue.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 任务列表条：DSH 的 todo_write 清单（每会话一份），位于 Deep Diving 下方、Goal 上方。 */
@Composable
internal fun TodoPanel(view: SessionViewState) {
    if (view.todos.isNotEmpty()) {
        var todosExpanded by remember { mutableStateOf(true) }
        // 与服务端 DSH Web progressLabel 完全对齐：已完成 → 进行中 → 待处理，
        // 零计数的段省略（"·" 连接）。
        val doneCount = view.todos.count { it.status == "completed" }
        val activeCount = view.todos.count { it.status == "in_progress" }
        val pendingCount = view.todos.size - doneCount - activeCount
        val progressSegments = buildList {
            if (doneCount > 0) add("$doneCount 已完成")
            if (activeCount > 0) add("$activeCount 进行中")
            if (pendingCount > 0) add("$pendingCount 待处理")
        }.joinToString(" · ")
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { todosExpanded = !todosExpanded }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "📋 任务（${view.todos.size}）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (progressSegments.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            progressSegments,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Text(
                        if (todosExpanded) "收起 ▲" else "展开 ▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentBlue,
                    )
                }
                if (todosExpanded) {
                    // 最多同屏 3 条：超出 3 条时列表区用固定高度（约 3 行高）并支持纵向滚动
                    val scrollState = rememberScrollState()
                    Column(
                        Modifier.fillMaxWidth().then(
                            if (view.todos.size > 3) Modifier.height(112.dp).verticalScroll(scrollState)
                            else Modifier
                        ),
                    ) {
                        view.todos.forEach { todo ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    when (todo.status) {
                                        "completed" -> "✅"
                                        "in_progress" -> "▶️"
                                        else -> "⏳"
                                    },
                                    fontSize = 13.sp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    todo.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (todo.status == "completed") {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Goal 面板：该会话的持久化目标（objective/阶段/轮次/阻塞原因）。位置：任务列表下方、排队消息上方。 */
@Composable
internal fun GoalPanel(view: SessionViewState) {
    // 对齐 DSH Web：完成态（phase=complete）目标不再展示面板；goal=null（服务端已清除）同样不渲染。
    view.goal?.takeIf { it.phase != "complete" }?.let { goal ->
        var goalExpanded by remember { mutableStateOf(false) }
        val phaseColor = when (goal.phase) {
            "active" -> StatusGreen
            "paused" -> StatusAmber
            "blocked" -> MaterialTheme.colorScheme.error
            else -> StatusGray
        }
        val phaseLabel = when (goal.phase) {
            "active" -> "进行中"
            "paused" -> "已暂停"
            "blocked" -> "已阻塞"
            else -> "已完成"
        }
        Surface(color = phaseColor.copy(alpha = 0.10f)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { goalExpanded = !goalExpanded }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎯", fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Goal",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = phaseColor,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        phaseLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = phaseColor,
                    )
                    Spacer(Modifier.weight(1f))
                    if (goal.maxGoalRounds > 0) {
                        Text(
                            "第 ${goal.roundsStarted}/${goal.maxGoalRounds} 轮",
                            style = MaterialTheme.typography.labelSmall,
                            color = phaseColor.copy(alpha = 0.85f),
                        )
                    }
                }
                Text(
                    goal.objective,
                    style = MaterialTheme.typography.bodySmall,
                    // 小屏空间预算：折叠态只占一行，点开看全文
                    maxLines = if (goalExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (goal.phase == "blocked" && !goal.blockedMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "⛔ ${goal.blockedMessage}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 排队消息面板：运行中发出的新消息进入队列；可收起/展开，每条可插队/删除。 */
@Composable
internal fun QueuePanel(view: SessionViewState, client: BridgeClient, sessionId: String) {
    if (view.queueItems.isNotEmpty()) {
        // 默认折叠：只显示「⏳ 排队中的消息（N）」摘要行，用户点摘要行才展开列表。
        // 折叠态是纯本地 UI 状态（与服务端投影无关），用 remember(sessionId) 键控：
        // 切会话时 sessionId 变化 → 状态重置为折叠（进会话默认折叠、不跨会话串味）；
        // 同一会话内展开/收起状态随重组保留。
        var queueExpanded by remember(sessionId) { mutableStateOf(false) }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { queueExpanded = !queueExpanded }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "⏳ 排队中的消息（${view.queueItems.size}）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (queueExpanded) "收起 ▲" else "展开 ▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentBlue,
                    )
                }
                if (queueExpanded) {
                    // 最多同屏 3 条：超出 3 条时列表区用固定高度（约 3 条行高）并支持纵向滚动；
                    // 收起/展开逻辑不变（展开时才渲染列表区）。
                    val queueItems = view.queueItems
                    val scrollState = rememberScrollState()
                    Column(
                        Modifier.fillMaxWidth().then(
                            if (queueItems.size > 3) Modifier.height(160.dp).verticalScroll(scrollState)
                            else Modifier
                        ),
                    ) {
                        queueItems.forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    when (item.placement) {
                                        "steering" -> "⚡插队中"
                                        "context" -> "🔧上下文"
                                        else -> "排队"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (item.placement == "steering") StatusAmber
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                // 乐观排队项（id 以 local- 开头）尚未被服务端 session_queue 回环替换成真实 id：
                                // 此时 itemId 是本地造的，服务端 inbox 里没有它——直接插队/删除必报
                                // queue-item-not-found 且消息看似「消失」。必须等真实 id 替换后才可操作，
                                // 期间按钮禁用并显示「同步中…」。
                                if (isSyncingQueueItem(item.id)) {
                                    Text(
                                        "同步中…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    if (item.placement == "queued") {
                                        TextButton(onClick = {
                                            ConnLog.info("ACTION", "排队插队 itemId=${item.id} sessionId=$sessionId")
                                            client.sendQueueAction(sessionId, item.id, "steer")
                                        }) {
                                            Text("插队", color = AccentBlue, style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                    TextButton(onClick = {
                                        ConnLog.info("ACTION", "排队移除 itemId=${item.id} sessionId=$sessionId")
                                        client.sendQueueAction(sessionId, item.id, "remove")
                                    }) {
                                        Text("删除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 开发状态行 + 详情面板：LSP 诊断 + 调试状态聚合为一条细行，点开进 ModalBottomSheet。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationDevPanel(view: SessionViewState, client: BridgeClient, sessionId: String) {
    // 绝不自动弹面板：只更新徽标，用户点开才进详情。
    var showDevPanel by remember { mutableStateOf(false) }
    if (view.diagnostics.isNotEmpty() || view.debug != null) {
        val errorCount = view.diagnostics.count { it.severity == 1 }
        val warnCount = view.diagnostics.count { it.severity == 2 }
        val debugSnap = view.debug
        val pausedAt = debugSnap?.paused?.stoppedAt
        val tint = when {
            errorCount > 0 -> MaterialTheme.colorScheme.error
            debugSnap?.state == "paused" -> StatusAmber
            warnCount > 0 -> StatusAmber
            debugSnap != null && debugSnap.error != null -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Surface(color = tint.copy(alpha = 0.10f)) {
            Row(
                Modifier.fillMaxWidth().clickable { showDevPanel = true }.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (view.diagnostics.isNotEmpty()) {
                    Text(if (errorCount > 0) "⛔" else "⚠️", fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        buildString {
                            append("${view.diagnostics.size} 诊断")
                            if (errorCount > 0) append("（$errorCount 错误）")
                            else if (warnCount > 0) append("（$warnCount 警告）")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (errorCount > 0) MaterialTheme.colorScheme.error else StatusAmber,
                    )
                }
                if (view.diagnostics.isNotEmpty() && debugSnap != null) {
                    Spacer(Modifier.width(10.dp))
                    Text("·", style = MaterialTheme.typography.labelMedium, color = tint.copy(alpha = 0.6f))
                    Spacer(Modifier.width(10.dp))
                }
                if (debugSnap != null) {
                    Text("🐛", fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (debugSnap.state) {
                            "paused" -> "暂停中 ${pausedAt?.path?.substringAfterLast('/') ?: ""}${pausedAt?.let { ":${it.line}" } ?: ""}"
                            "starting" -> "调试启动中…"
                            "running" -> "调试运行中"
                            else -> "调试已停止" + (debugSnap.error?.let { "（$it）" } ?: "")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("查看详情", style = MaterialTheme.typography.labelSmall, color = AccentBlue)
            }
        }
    }
    if (showDevPanel) {
        var devTab by remember { mutableStateOf(0) }
        ModalBottomSheet(
            onDismissRequest = { showDevPanel = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(bottom = 28.dp),
            ) {
                Text("开发面板", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                TabRow(selectedTabIndex = devTab) {
                    Tab(selected = devTab == 0, onClick = { devTab = 0 }, text = { Text("诊断 ${view.diagnostics.size}") })
                    Tab(selected = devTab == 1, onClick = { devTab = 1 }, text = { Text("调试") })
                }
                Spacer(Modifier.height(10.dp))
                if (devTab == 0) {
                    if (view.diagnostics.isEmpty()) {
                        Text("暂无诊断：Agent 编辑代码后，语言服务器的错误/警告会自动出现在这里。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        view.diagnostics.forEach { d ->
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                                Text(
                                    when (d.severity) { 1 -> "🔴"; 2 -> "🟡"; 3 -> "🔵"; else -> "⚪" },
                                    fontSize = 12.sp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.message, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "${d.path} : ${d.line}:${d.column}" + (d.source?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                } else {
                    DebugPanelContent(view, client, sessionId)
                }
            }
        }
    }
}

// ================= 工具 =================
// 时间格式化：formatTimestamp 见 TimestampFormat.kt；
// 绝对时间戳 formatClock（日志页）与 nowMillis 见 TimeFormat.kt

/** Deep Diving 等待时长文案（服务端时钟秒数透传；只显示时长，不含「本轮」）。 */
internal fun formatDivingDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "${s}秒"
        s < 3600 -> {
            val m = s / 60
            val rem = s % 60
            if (rem == 0L) "${m}分" else "${m}分${rem}秒"
        }
        else -> {
            val h = s / 3600
            val m = (s % 3600) / 60
            if (m == 0L) "${h}小时" else "${h}小时${m}分"
        }
    }
}

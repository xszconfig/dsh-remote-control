package com.daniel.dshremote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daniel.dshremote.protocol.ContextUsageWire
import com.daniel.dshremote.protocol.ModelCatalogModelWire
import com.daniel.dshremote.protocol.ModelProviderGroupWire
import com.daniel.dshremote.protocol.SessionModelsWire
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** 输入区：斜杠命令候选弹窗 + 输入框（上）+ 操作条（下：模型入口 / 上下文环 / 终止 / 发送）。 */
@Composable
internal fun ConversationComposer(
    client: BridgeClient,
    state: SessionUiState,
    view: SessionViewState,
    sessionId: String,
    input: String,
    onInputChange: (String) -> Unit,
    inputFocused: Boolean,
    onInputFocusedChange: (Boolean) -> Unit,
    onFollowBottom: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var showInterruptConfirm by remember { mutableStateOf(false) }
    // 点「中断」那一刻的排队数快照：弹框正文必须用它，不能渲染时再读 queuedCounts——
    // 否则「点中断时队列非空 → 弹框出现 → 队列恰好被消费」会在正文显示「0 条」与用户所见不符。
    var confirmQueuedCount by remember { mutableStateOf(0) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showContextDetail by remember { mutableStateOf(false) }
    // 斜杠命令候选弹窗：输入以 "/" 开头、还在敲命令名（未出现空白）且输入框聚焦时弹出。
    // 候选清单来自服务端注册表（subscribe/commands_update 下发），与 Web composer 同源；
    // 选中即填入 "/命令名 "（带尾空格，就绪输入参数），弹窗随之收起。
    val slashFragment = input.takeIf { it.startsWith("/") && it.none { ch -> ch.isWhitespace() } }
    if (inputFocused && slashFragment != null && view.commands.isNotEmpty()) {
        val partial = slashFragment.removePrefix("/")
        val candidates = view.commands.filter { it.name.startsWith(partial) }
        if (candidates.isNotEmpty()) {
            CommandCandidatePopup(
                candidates = candidates,
                onPick = { name -> onInputChange("/$name ") },
            )
        }
    }
    // 终止/运行态信号复用现状（PRD 2.0 术语 agentRunning）：不新增信号源。
    val agentRunning = isAgentRunning(state.sessions, sessionId, state.modelWaitingSince)
    // 发送可点 = 输入非空；发送点击后立即清空输入框 → 自然置灰（PRD 2.5 解 A，不新增提交锁）。
    val canSend = input.trim().isNotEmpty()

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // 上：输入框独占一行（原样式不变）。
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f).onFocusChanged {
                    onInputFocusedChange(it.isFocused)
                    if (it.isFocused) {
                        ConnLog.throttled(ConnLogLevel.INFO, "ACTION", "input-focus-gain", 500) { "输入框获得焦点 sessionId=$sessionId" }
                    } else {
                        ConnLog.throttled(ConnLogLevel.INFO, "ACTION", "input-focus-lost", 500) { "输入框失去焦点 sessionId=$sessionId" }
                    }
                },
                placeholder = { Text("发指令给DeepSeek Harness") },
                shape = RoundedCornerShape(22.dp),
                maxLines = 4,
                // 无焦点也常显蓝色边框，让用户一眼知道这里是输入框；
                // 聚焦时全亮蓝，未聚焦用半透明蓝区分状态。
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                ),
            )
        }
        // 下：操作条（从左到右固定顺序：模型入口 → 上下文环 → 终止 → 发送）。
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ① 切换模型入口
            ModelEntry(
                label = modelEntryLabel(state.models),
                onClick = {
                    ConnLog.info("MODEL", "模型入口点击 sessionId=$sessionId placeholder=${state.models?.current == null}")
                    showModelSheet = true
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // ② 上下文窗口环形进度（percent==null 不渲染，占位空）
            ContextRing(
                usage = state.contextUsage,
                onClick = {
                    ConnLog.info("CTX", "上下文明细点击 sessionId=$sessionId")
                    showContextDetail = true
                },
            )
            Spacer(Modifier.width(8.dp))
            // ③ 终止按钮：常驻两态（running 红可点 / 非 running 灰不可点）
            val queuedCount = state.queuedCounts[sessionId] ?: 0
            Button(
                onClick = {
                    ConnLog.info("ACTION", "中断点击 sessionId=$sessionId queued=$queuedCount running=$agentRunning")
                    if (queuedCount > 0) {
                        ConnLog.info("ACTION", "中断确认弹框出现 sessionId=$sessionId queued=$queuedCount")
                        confirmQueuedCount = queuedCount
                        showInterruptConfirm = true
                    } else {
                        client.interrupt(sessionId, "clear")
                    }
                },
                enabled = agentRunning,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                StopIcon()
            }
            Spacer(Modifier.width(8.dp))
            // ④ 发送按钮（最右 · 核心）：非空可点、空/发送后置灰
            Button(
                onClick = {
                    ConnLog.info("ACTION", "发送点击 sessionId=$sessionId 输入长度=${input.length} trimLen=${input.trim().length}")
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        onFollowBottom() // 发送后重新跟随底部（要看到自己的消息与回复）
                        client.sendMessage(text)
                        onInputChange("")
                        // 先清焦点再收键盘：焦点仍在输入框时直接 hide 会被 IME 拉回来，一闪一闪
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                },
                enabled = canSend,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                // 48dp 圆钮配默认 24dp 水平内边距会把内容区挤成 0 宽（图标不可见），
                // 必须归零内边距让 20dp 图标完整渲染。
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                SendIcon()
            }
        }
    }
    if (showInterruptConfirm) {
        InterruptConfirmDialog(
            sessionId = sessionId,
            queuedCount = confirmQueuedCount,
            onClear = { client.interrupt(sessionId, "clear"); showInterruptConfirm = false },
            onKeep = { client.interrupt(sessionId, "keep"); showInterruptConfirm = false },
            onDismiss = { showInterruptConfirm = false },
        )
    }
    if (showModelSheet) {
        ModelSelectSheet(
            models = state.models,
            onSelect = { provider, model, effort ->
                ConnLog.info("MODEL", "模型切换提交 sessionId=$sessionId provider=$provider model=$model effort=$effort")
                client.setModel(sessionId, provider, model, effort)
                showModelSheet = false
            },
            onDismiss = { showModelSheet = false },
        )
    }
    if (showContextDetail) {
        ContextDetailDialog(
            usage = state.contextUsage,
            onDismiss = { showContextDetail = false },
        )
    }
}

/** 操作条① 模型入口：当前模型短标签（无则「选择模型」占位）+ 切换指示，≥40dp 触达。 */
@Composable
internal fun ModelEntry(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Text("▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 操作条② 上下文窗口环形进度：percent==null 不渲染（占位空）；percent 由服务端算好，客户端零推算。 */
@Composable
internal fun ContextRing(usage: ContextUsageWire?, onClick: () -> Unit) {
    val percent = usage?.percent
    if (percent == null) {
        // 无 provider 上报 pressure / 无路由容量 → 什么都不渲染，占位空保持操作条布局稳定
        Spacer(Modifier.size(40.dp))
        return
    }
    val clamped = percent.coerceIn(0, 100)
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { clamped / 100f },
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 3.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            "$clamped",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 模型选择弹窗：provider 分组 → 模型 → reasoning effort 档位（移动端半屏 Sheet）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSelectSheet(
    models: SessionModelsWire?,
    onSelect: (provider: String, model: String, reasoningEffort: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // null = 目录层；非 null = 已选中带 effort 的模型，进入 effort 二级选择
    var picked by remember { mutableStateOf<Pair<ModelProviderGroupWire, ModelCatalogModelWire>?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(bottom = 28.dp),
        ) {
            val groups = models?.groups ?: emptyList()
            val failures = models?.failures ?: emptyList()
            val current = models?.current

            if (picked == null) {
                Text("选择模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                if (groups.isEmpty() && failures.isEmpty()) {
                    Text("暂无可用模型目录（桌面端未上报或加载中）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    groups.forEach { group ->
                        Text(
                            group.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        )
                        group.models.forEach { m ->
                            ModelOptionRow(
                                name = m.name,
                                description = m.description,
                                selected = current != null && current.provider == group.id && current.model == m.id,
                                onClick = {
                                    if (m.reasoning?.efforts?.isNotEmpty() == true) {
                                        picked = group to m
                                    } else {
                                        onSelect(group.id, m.id, null)
                                    }
                                },
                            )
                        }
                    }
                    failures.forEach { f ->
                        Text(
                            "⚠ ${f.name}：${f.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            } else {
                val (group, model) = picked!!
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picked = null }) { Text("‹ 返回") }
                    Text(
                        "${model.name} · 推理档位",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                val efforts = model.reasoning?.efforts ?: emptyList()
                val defaultEffort = model.reasoning?.defaultEffort
                efforts.forEach { e ->
                    ModelOptionRow(
                        name = e.name + if (e.id == defaultEffort) "（默认）" else "",
                        description = e.description,
                        selected = false,
                        onClick = { onSelect(group.id, model.id, e.id) },
                    )
                }
                TextButton(onClick = { onSelect(group.id, model.id, null) }) { Text("不指定（用默认档）") }
            }
        }
    }
}

/** 模型/effort 单行：名称 + 说明 + 当前项高亮。 */
@Composable
internal fun ModelOptionRow(name: String, description: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text("✓", color = MaterialTheme.colorScheme.primary)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** 上下文占用分类明细弹窗：系统提示 / 工具 / 对话三类 token 数（标注近似组成）。 */
@Composable
internal fun ContextDetailDialog(usage: ContextUsageWire?, onDismiss: () -> Unit) {
    val breakdown = usage?.breakdown
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上下文占用（近似组成）") },
        text = {
            Column {
                if (breakdown == null) {
                    Text(
                        "暂无分类明细（桌面端未上报 breakdown 投影）。占用百分比为 provider 锚定的参考值，非计费/门控输入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val total = breakdown.systemTokens + breakdown.toolsTokens + breakdown.messageTokens
                    BreakdownRow("系统提示", breakdown.systemTokens, total)
                    BreakdownRow("工具", breakdown.toolsTokens, total)
                    BreakdownRow("对话", breakdown.messageTokens, total)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "三类按固定启发式估算，之和 ≠ 占用分子（占用为 provider 锚定的参考值，非计费/门控输入）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

/** 明细弹窗单行：类别名 + token 数 + 占三类之和的百分比（total=0 时不显示占比）。 */
@Composable
internal fun BreakdownRow(label: String, tokens: Long, total: Long) {
    val percent = if (total > 0) ((tokens * 100) / total).toInt() else null
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            formatTokens(tokens) + (percent?.let { " · $it%" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 纸飞机发送图标：Material send 路径 Canvas 自绘，随主题着色，无额外图标依赖。 */
@Composable
internal fun SendIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(20.dp)) {
        val s = size.width / 24f
        val path = Path().apply {
            moveTo(2.01f * s, 21f * s)
            lineTo(23f * s, 12f * s)
            lineTo(2.01f * s, 3f * s)
            lineTo(2f * s, 10f * s)
            lineTo(17f * s, 12f * s)
            lineTo(2f * s, 14f * s)
            close()
        }
        drawPath(path, color)
    }
}

/** 终止图标：圆角实心方块（Canvas 自绘，随主题着色）。 */
@Composable
internal fun StopIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(16.dp)) {
        drawRoundRect(color = color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
    }
}

package com.daniel.dshremote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daniel.dshremote.protocol.ContextUsageWire
import com.daniel.dshremote.protocol.SessionModelsWire
import com.daniel.dshremote.protocol.SkillWire
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue

/** 输入区：入口列表（上，横向 chips，可扩展）+ 输入框（中）+ 操作条（下：模型入口 / 上下文环 / 终止 / 发送）。 */
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
    // 输入框焦点请求器：技能选用后把焦点拉回输入框（用户接续输入提示词）时用。
    val inputFocusRequester = remember { FocusRequester() }
    var showInterruptConfirm by remember { mutableStateOf(false) }
    // 点「中断」那一刻的排队数快照：弹框正文必须用它，不能渲染时再读 queuedCounts——
    // 否则「点中断时队列非空 → 弹框出现 → 队列恰好被消费」会在正文显示「0 条」与用户所见不符。
    var confirmQueuedCount by remember { mutableStateOf(0) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showEffortSheet by remember { mutableStateOf(false) }
    var showContextDetail by remember { mutableStateOf(false) }
    var showSkillPanel by remember { mutableStateOf(false) }
    // 技能选用后需把焦点拉回输入框：面板收起（showSkillPanel=false）且本标记为 true 时才请求焦点，
    // 避免面板首次组合 / 手势关闭（onDismiss 无 pendingSkillFocus）时误抢焦点。
    var pendingSkillFocus by remember { mutableStateOf(false) }
    LaunchedEffect(showSkillPanel) {
        if (!showSkillPanel && pendingSkillFocus) {
            pendingSkillFocus = false
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }
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
    // 子会话只继承主会话模型，不提供切换（模型入口 readOnly，不显示 ▾、不可点开）。
    val isSubagent = state.sessions.firstOrNull { it.id == sessionId }?.parentSessionId != null
    // 发送可点 = 输入非空；发送点击后立即清空输入框 → 自然置灰（PRD 2.5 解 A，不新增提交锁）。
    val canSend = input.trim().isNotEmpty()

    // 发送逻辑共享：下方发送按钮 onClick 与键盘 ImeAction.Send 都走这里。
    // 内部已含非空守卫（text.isNotEmpty()）——发送后输入清空 → canSend 回 false → 按钮回灰、
    // imeAction 回 Default（回车恢复换行），自然置灰保护，不新增提交锁。
    val onSend: () -> Unit = {
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
    }

    // 输入框上方入口列表：数据驱动（后续加新入口只加数据项，不改容器结构）。
    val composerEntries = remember { listOf(ComposerEntry(id = "skills", label = "技能")) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // 上：入口列表（横向 chips，可横向滚动，≥40dp 触达）。
        ComposerEntryList(
            entries = composerEntries,
            onEntryClick = { entry ->
                when (entry.id) {
                    "skills" -> {
                        ConnLog.info("SKILL", "技能入口点击 sessionId=$sessionId")
                        showSkillPanel = true
                    }
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(6.dp))
        // 中：输入框独占一行（从左到右撑满）。
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth().focusRequester(inputFocusRequester).onFocusChanged {
                onInputFocusedChange(it.isFocused)
                if (it.isFocused) {
                    ConnLog.throttled(ConnLogLevel.INFO, "ACTION", "input-focus-gain", 500) { "输入框获得焦点 sessionId=$sessionId" }
                } else {
                    ConnLog.throttled(ConnLogLevel.INFO, "ACTION", "input-focus-lost", 500) { "输入框失去焦点 sessionId=$sessionId" }
                }
            },
            placeholder = { Text("给智能体发消息") },
            shape = RoundedCornerShape(22.dp),
            maxLines = 4,
            // 键盘发送：非空时回车/换行键变为「发送」，点击触发与下方发送按钮相同的 onSend 流程；
            // 空态保持 Default（回车换行）。多行取舍：非空 ImeAction.Send 下换行需 Shift+Enter
            //（Android 标准行为，部分 IME 可能不提供换行）——按用户要求「非空回车即发」优先。
            keyboardOptions = KeyboardOptions(imeAction = if (canSend) ImeAction.Send else ImeAction.Default),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            // 无焦点也常显蓝色边框，让用户一眼知道这里是输入框；
            // 聚焦时全亮蓝，未聚焦用半透明蓝区分状态。
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            ),
        )
        Spacer(Modifier.height(6.dp))
        // 下：操作条（从左到右固定顺序：模型入口 → 上下文环 → 终止 → 发送）。
        // 高度 48dp 与终止/发送 48dp 圆钮一致；不要加 padding(bottom)——会把按钮压成 42dp 高椭圆。
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ① 切换模型入口（子会话 readOnly：只展示继承模型，不可点开；主会话左右分区可选）
            ModelEntry(
                parts = modelEntryParts(state.models),
                readOnly = isSubagent,
                onModelClick = {
                    ConnLog.info("MODEL", "模型入口左区点击 sessionId=$sessionId placeholder=${state.models?.current == null}")
                    showModelSheet = true
                },
                onEffortClick = {
                    ConnLog.info("MODEL", "模型入口右区点击 sessionId=$sessionId")
                    showEffortSheet = true
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // ② 上下文窗口环形进度（percent==null 不渲染，占位空保持布局稳定）
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
            // ④ 发送按钮（最右 · 核心）：非空可点、空/发送后置灰；onClick 与键盘 ImeAction.Send 共用 onSend。
            Button(
                onClick = onSend,
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
    if (showEffortSheet) {
        EffortSelectSheet(
            models = state.models,
            onSelect = { provider, model, effort ->
                ConnLog.info("MODEL", "强度切换提交 sessionId=$sessionId provider=$provider model=$model effort=$effort")
                client.setModel(sessionId, provider, model, effort)
                showEffortSheet = false
            },
            onDismiss = { showEffortSheet = false },
        )
    }
    if (showContextDetail) {
        ContextDetailDialog(
            usage = state.contextUsage,
            onDismiss = { showContextDetail = false },
        )
    }
    if (showSkillPanel) {
        SkillPanel(
            skills = state.skills,
            onPick = { name ->
                ConnLog.info("SKILL", "技能选用 name=$name sessionId=$sessionId")
                // 选中技能 → 把「/技能名 」字面量写入输入框（带尾空格，就绪输入提示词），
                // 收起面板，焦点拉回输入框让用户接续输入提示词再发送。
                onInputChange("/$name ")
                showSkillPanel = false
                pendingSkillFocus = true
            },
            onDismiss = { showSkillPanel = false },
        )
    }
}

/** 输入框上方入口项：数据驱动的 id + 展示标签，后续加新入口只加数据项、不改容器结构。 */
internal data class ComposerEntry(val id: String, val label: String)

/** 输入框上方入口列表容器：横向 chips/按钮行，可横向滚动；只依赖 [ComposerEntry] 数据，不感知具体入口。 */
@Composable
internal fun ComposerEntryList(
    entries: List<ComposerEntry>,
    onEntryClick: (ComposerEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { entry ->
            ComposerEntryChip(label = entry.label, onClick = { onEntryClick(entry) })
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** 入口 chip：胶囊形，≥40dp 触达（移动端优先）。 */
@Composable
internal fun ComposerEntryChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 操作条① 模型入口：主会话拆成左区（模型显示名 → 模型面板）+ 右区（推理强度 → 强度面板）两个独立点击区，
 * 视觉仍是一条胶囊（区隔竖线 + 各自按压反馈）；[readOnly]（子会话）保持现状整块只读（不分区、不可点）。
 * 左区模型名过长截断、右区强度恒显；右区仅当当前模型有 reasoning.efforts 档位时存在，否则整块只显模型名。 */
@Composable
internal fun ModelEntry(
    parts: ModelEntryParts,
    readOnly: Boolean,
    onModelClick: () -> Unit,
    onEffortClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        if (readOnly) {
            // 子会话：整块只读展示（现状不变），不显示 ▾、不可点
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    parts.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (parts.effort != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "· ${parts.effort}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        } else if (parts.hasEffortOptions) {
            // 主会话 + 有强度档位：左区模型名 + 右区强度名，两个独立点击区，区隔竖线分界
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                        .clickable(onClick = onModelClick)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        parts.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.width(1.dp).height(20.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                )
                Row(
                    Modifier.fillMaxHeight()
                        .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                        .clickable(onClick = onEffortClick)
                        .padding(start = 8.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "· ${parts.effort ?: MODEL_ENTRY_EFFORT_PLACEHOLDER}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            // 主会话 + 无强度档位：整块只显模型名（可点开模型面板），右端保留 ▾ 提示可切换
            Row(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onModelClick)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    parts.name,
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

/** 技能面板：半屏 Sheet，顶部搜索框 + LazyColumn 浏览电脑端 DSH 全部技能；点某技能即 [onPick]（写入「/技能名 」到输入框）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillPanel(skills: List<SkillWire>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    // 面板固定高度 = 屏幕 1/2（用户拍板：半屏，不高不矮）；搜索框固定顶部、列表内部滚动。
    // 用固定 height 而非 heightIn：heightIn 下 weight(1f) 在列表从「少量过滤结果」恢复为全量时
    // 不会重新撑满剩余高度，导致清空 query 后列表不恢复（见 2026-09-12 USB 补验反馈）。
    val panelHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().height(panelHeight)) {
            Text(
                "技能",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("搜索技能（名称 / 描述）") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(8.dp))
            val filtered = filterSkills(skills, query)
            // 用 key(query) 强制列表子树在 query 变化时整体重建：清空搜索词时 filtered 从
            // 「少量命中」变回「全量」，若不重建 LazyColumn 会沿用旧 item 集合不刷新
            // （2026-09-12 USB 补验：清空 query 列表不恢复）。
            key(query) {
                if (filtered.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f).padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (skills.isEmpty()) "暂无可用技能（桌面端未上报）" else "没有匹配的技能",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(filtered, key = { it.name }) { skill ->
                            SkillRow(skill = skill, onClick = { onPick(skill.name) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 技能单行：名称（加粗）+ 描述（次要色）+ 适用（whenToUse，有则附一行小字）；点击整行触发 [onClick] 选用该技能。 */
@Composable
private fun SkillRow(skill: SkillWire, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(skill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(
            skill.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!skill.whenToUse.isNullOrBlank()) {
            Text(
                "适用：${skill.whenToUse}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** 模型选择面板：列 groups[].models[]（名称 + 说明 + 当前高亮），点某模型即选该模型默认档并收起（不再进强度二级导航）。
 * 移动端半屏 Sheet；每次选择直接 [onSelect]（已接 client.setModel），无保存/二次确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSelectSheet(
    models: SessionModelsWire?,
    onSelect: (provider: String, model: String, reasoningEffort: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                            onClick = { onSelect(group.id, m.id, null) },
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
        }
    }
}

/** 推理强度选择面板：对当前模型列 reasoning.efforts[]（名称 + 说明 + 当前高亮 + 默认档标记），
 * 点某档直接 set_model 收起；底部「不指定（用默认档）」→ onSelect(provider, model, null)。移动端半屏 Sheet。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EffortSelectSheet(
    models: SessionModelsWire?,
    onSelect: (provider: String, model: String, reasoningEffort: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentRef = currentModelRef(models)
    val options = currentModelEffortOptions(models)
    val current = models?.current
    val modelName = currentRef?.model?.name

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(bottom = 28.dp),
        ) {
            Text("推理强度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (modelName != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    modelName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (currentRef == null || options == null) {
                Text("当前无模型或无可选强度档位", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                options.efforts.forEach { e ->
                    ModelOptionRow(
                        name = e.name + if (e.id == options.defaultEffort) "（默认）" else "",
                        description = e.description,
                        selected = current != null && current.reasoningEffort == e.id,
                        onClick = { onSelect(currentRef.provider, currentRef.model.id, e.id) },
                    )
                }
                ModelOptionRow(
                    name = "不指定（用默认档）",
                    description = "按模型默认档位推理",
                    selected = current != null && current.reasoningEffort == null,
                    onClick = { onSelect(currentRef.provider, currentRef.model.id, null) },
                )
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

/** 上下文分段三色（对齐桌面 ContextMeter）：蓝灰=系统提示词 / 紫=工具 / 蓝=对话消息。 */
private val ContextSystemColor = Color(0xFF78909C)
private val ContextToolsColor = Color(0xFF9C27B0)
private val ContextMessageColor = Color(0xFF2196F3)

/** 上下文占用弹层（对齐桌面 DSH Web ContextMeter，信息不少项）：标题 + 大百分比 + 已用/总量 + 三色分段条 + 图例。 */
@Composable
internal fun ContextDetailDialog(usage: ContextUsageWire?, onDismiss: () -> Unit) {
    val percent = usage?.percent
    val used = contextUsedTokens(usage)
    val breakdown = usage?.breakdown
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上下文已用") },
        text = {
            Column {
                Text(
                    if (percent != null) "$percent%" else "—",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                val usedText = used?.let { "~${formatContextTokens(it)}" } ?: "—"
                val windowText = usage?.contextWindow?.let { formatContextTokens(it) } ?: "—"
                Text(
                    "$usedText / $windowText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (breakdown != null) {
                    Spacer(Modifier.height(14.dp))
                    ContextSegmentedBar(
                        system = breakdown.systemTokens,
                        tools = breakdown.toolsTokens,
                        messages = breakdown.messageTokens,
                    )
                    Spacer(Modifier.height(12.dp))
                    ContextLegendRow(ContextSystemColor, "系统提示词", breakdown.systemTokens)
                    ContextLegendRow(ContextToolsColor, "工具", breakdown.toolsTokens)
                    ContextLegendRow(ContextMessageColor, "对话消息", breakdown.messageTokens)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

/** 三色分段条形图：system/tools/messages 按占比分段（占比为 0 的段不渲染）。 */
@Composable
private fun ContextSegmentedBar(system: Long, tools: Long, messages: Long) {
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        if (system > 0) Box(Modifier.weight(system.toFloat()).fillMaxHeight().background(ContextSystemColor))
        if (tools > 0) Box(Modifier.weight(tools.toFloat()).fillMaxHeight().background(ContextToolsColor))
        if (messages > 0) Box(Modifier.weight(messages.toFloat()).fillMaxHeight().background(ContextMessageColor))
    }
}

/** 图例单行：色块 + 标签 + ~token 数。 */
@Composable
private fun ContextLegendRow(color: Color, label: String, tokens: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            "~${formatContextTokens(tokens)}",
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

package com.daniel.dshremote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daniel.dshremote.protocol.ApprovalDecision
import com.daniel.dshremote.protocol.ApprovalRequestWire
import com.daniel.dshremote.protocol.QuestionAnswerItemWire
import com.daniel.dshremote.protocol.QuestionItemWire
import com.daniel.dshremote.protocol.QuestionRequestWire
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovalSheet(
    approval: ApprovalRequestWire,
    queueCount: Int,
    deciding: Boolean,
    sessionTitle: String?,
    onDecide: (ApprovalDecision) -> Unit,
) {
    // 中断式半屏弹窗：禁止下滑关闭、禁止返回关闭、禁止点外部关闭——
    // 审批是强阻塞交互，用户必须给出裁决（或等待它被其他终端/超时解决）。
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = { /* 中断式：不响应关闭请求 */ },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        dragHandle = { ApprovalDragHandle(queueCount) },
    ) {
        ApprovalSheetContent(
            approval = approval,
            sessionTitle = sessionTitle,
            deciding = deciding,
            onDecide = onDecide,
        )
    }
}

/** 审批弹窗警示条（镜像桌面端「等待审批」strip）。 */
@Composable
internal fun ApprovalDragHandle(queueCount: Int) {
    Row(
        Modifier.fillMaxWidth().background(StatusAmber.copy(alpha = 0.16f)).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(9.dp).clip(CircleShape).background(StatusAmber),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "等待审批",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = StatusAmber,
            modifier = Modifier.weight(1f),
        )
        if (queueCount > 1) {
            Text(
                "还有 ${queueCount - 1} 个待审批",
                style = MaterialTheme.typography.labelSmall,
                color = StatusAmber,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(StatusAmber.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** 审批弹窗正文：会话上下文 / 主文案 / 工具徽章 / 命令文本 / 裁决按钮。 */
@Composable
internal fun ApprovalSheetContent(
    approval: ApprovalRequestWire,
    sessionTitle: String?,
    deciding: Boolean,
    onDecide: (ApprovalDecision) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
    ) {
        // 会话上下文（若可见）
        if (!sessionTitle.isNullOrBlank()) {
            Text(
                "来自会话「$sessionTitle」",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
        }
        // 主文案：reason 优先，否则桌面端同款模板（透传语义与桌面端一致）
        Text(
            approval.reason?.takeIf { it.isNotBlank() }
                ?: "工具 ${approval.toolName} 请求越权执行",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        // 工具名徽章
        Text(
            "🛠 ${approval.toolName}",
            style = MaterialTheme.typography.labelMedium,
            color = AccentBlue,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        // 透传的命令文本（关联工具调用时）
        if (!approval.command.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "请求执行的命令",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(12.dp),
            ) {
                Text(
                    approval.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = LocalColorTokens.current.approveCmdGreen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        // 裁决按钮（镜像桌面端：拒绝 outline / 允许一次 primary）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { onDecide(ApprovalDecision.Rejected) },
                enabled = !deciding,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(if (deciding) "处理中…" else "拒绝", fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = { onDecide(ApprovalDecision.AllowedOnce) },
                enabled = !deciding,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (deciding) "处理中…" else "允许一次", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "此操作需你在手机上确认，桌面端将等待你的裁决",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ================= 提问弹窗（ask_user_question 透传） =================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuestionSheet(
    question: QuestionRequestWire,
    queueCount: Int,
    deciding: Boolean,
    sessionTitle: String?,
    onSubmit: (List<QuestionAnswerItemWire>) -> Unit,
) {
    // 与审批一致：中断式半屏弹窗，禁止下滑/返回/点外部关闭。
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    // 每个提问的本地选择状态（按 rpcId 复位）
    var selections by remember(question.rpcId) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var customs by remember(question.rpcId) { mutableStateOf<Map<String, String>>(emptyMap()) }

    val allAnswered = question.questions.all { item ->
        val sel = selections[item.id].orEmpty()
        val custom = customs[item.id].orEmpty()
        sel.isNotEmpty() || custom.isNotBlank()
    }

    ModalBottomSheet(
        onDismissRequest = { /* 中断式：不响应关闭请求 */ },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        dragHandle = { QuestionDragHandle(queueCount) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (!sessionTitle.isNullOrBlank()) {
                Text(
                    "来自会话「$sessionTitle」",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }
            question.questions.forEach { item ->
                QuestionItemView(
                    item = item,
                    selectedLabels = selections[item.id].orEmpty(),
                    customText = customs[item.id].orEmpty(),
                    onToggleOption = { label ->
                        val cur = selections[item.id].orEmpty().toMutableSet()
                        if (item.multiSelect) {
                            if (label in cur) cur.remove(label) else cur.add(label)
                        } else {
                            cur.clear()
                            cur.add(label)
                        }
                        selections = selections + (item.id to cur)
                    },
                    onCustomChange = { text -> customs = customs + (item.id to text) },
                )
            }
            Button(
                onClick = {
                    onSubmit(
                        question.questions.map { item ->
                            QuestionAnswerItemWire(
                                id = item.id,
                                selected = selections[item.id].orEmpty().toList(),
                                custom = customs[item.id]?.takeIf { it.isNotBlank() },
                            )
                        },
                    )
                },
                enabled = allAnswered && !deciding,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (deciding) "提交中…" else "提交", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "回答将回传给桌面端 Agent，继续执行后续操作",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 提问弹窗警示条（镜像审批「等待审批」strip）。 */
@Composable
internal fun QuestionDragHandle(queueCount: Int) {
    Row(
        Modifier.fillMaxWidth().background(StatusAmber.copy(alpha = 0.16f)).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("💬", fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            "等待回答",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = StatusAmber,
            modifier = Modifier.weight(1f),
        )
        if (queueCount > 1) {
            Text(
                "还有 ${queueCount - 1} 个待回答",
                style = MaterialTheme.typography.labelSmall,
                color = StatusAmber,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(StatusAmber.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** 单个提问：header / question / detail / options（label + description）或自由文本输入。 */
@Composable
internal fun QuestionItemView(
    item: QuestionItemWire,
    selectedLabels: Set<String>,
    customText: String,
    onToggleOption: (String) -> Unit,
    onCustomChange: (String) -> Unit,
) {
    if (!item.header.isNullOrBlank()) {
        Text(
            item.header,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = AccentBlue,
        )
        Spacer(Modifier.height(6.dp))
    }
    Text(
        item.question,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    if (!item.detail.isNullOrBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
            item.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(10.dp))
    if (item.options.isNotEmpty()) {
        item.options.forEach { option ->
            val selected = selectedLabels.contains(option.label)
            val clickable = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onToggleOption(option.label) }
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .padding(12.dp)
            Row(clickable, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.multiSelect) (if (selected) "☑" else "☐") else (if (selected) "◉" else "○"),
                    fontSize = 15.sp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (!option.description.isNullOrBlank()) {
                        Text(
                            option.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    } else {
        // 无选项 → 自由文本输入
        OutlinedTextField(
            value = customText,
            onValueChange = onCustomChange,
            placeholder = { Text("输入你的回答…") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
    }
    Spacer(Modifier.height(14.dp))
}

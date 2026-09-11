package com.daniel.dshremote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** 子代理下拉列表：最多同时展示 10 条（移动端小屏上限，铁律 9），超过则列表内上下滚动。 */
internal const val SUBAGENT_MENU_MAX_VISIBLE = 10
internal val SUBAGENT_MENU_MAX_HEIGHT = 48.dp * SUBAGENT_MENU_MAX_VISIBLE

// ---- 侧边栏：工作区菜单 ----

@Composable
internal fun WorkspaceDrawer(
    client: BridgeClient,
    state: SessionUiState,
    onSelect: (String?) -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        // 侧边栏占屏幕 85% 宽（用户要求：70% 太窄）
        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.85f),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "工作区",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            WorkspaceFilter(state = state, onSelect = onSelect)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    state.connectedDevice?.name ?: "已连接",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    state.connectedDevice?.let { "${it.host}:${it.port}" } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 设备页入口：多设备切换的落点
            DrawerEntry(
                label = "设备",
                badge = null,
                icon = "🖥",
                selected = false,
                onClick = onOpenDevices,
            )
            Spacer(Modifier.weight(1f))
            // 设置入口（侧边栏底部，通常位置）
            DrawerEntry(
                label = "设置",
                badge = null,
                icon = "⚙️",
                selected = false,
                onClick = onOpenSettings,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 工作区过滤条目（全部会话 / 各工作区 / 未分组）：手机抽屉与平板左栏共用。 */
@Composable
internal fun WorkspaceFilter(state: SessionUiState, onSelect: (String?) -> Unit) {
    val ungrouped = state.sessions.count { it.parentSessionId == null && it.workspaceId == null }
    val mainSessions = state.sessions.count { it.parentSessionId == null }
    DrawerEntry(
        label = "全部会话",
        badge = mainSessions,
        icon = "🗂",
        selected = state.selectedWorkspaceId == null,
        onClick = { onSelect(null) },
    )
    state.workspaces.forEach { w ->
        DrawerEntry(
            label = w.title,
            // 计数按会话列表实算（服务端 workspace.sessionCount 含 registry 残留，
            // 与列表不一致会出现「外面 N 个、点进去没有」）；只计顶层会话
            badge = state.sessions.count { it.parentSessionId == null && it.workspaceId == w.id },
            icon = "📁",
            selected = state.selectedWorkspaceId == w.id,
            onClick = { onSelect(w.id) },
        )
    }
    if (ungrouped > 0) {
        DrawerEntry(
            label = "未分组",
            badge = ungrouped,
            icon = "📄",
            selected = state.selectedWorkspaceId == UNGROUPED_KEY,
            onClick = { onSelect(UNGROUPED_KEY) },
        )
    }
}

@Composable
internal fun DrawerEntry(
    label: String,
    badge: Int?,
    icon: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        icon = { Text(icon, fontSize = 16.sp) },
        badge = badge?.let { b -> { Text("$b", style = MaterialTheme.typography.labelSmall) } },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

// ---- 顶栏 ----

@Composable
internal fun TopBar(client: BridgeClient, state: SessionUiState, onMenu: () -> Unit, onOpenLogs: () -> Unit, sessionId: String? = null, onOpenSettings: () -> Unit = {}) {
    val session = (sessionId ?: state.currentSessionId)?.let { sid ->
        state.sessions.firstOrNull { it.id == sid }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (session != null) {
                TextButton(onClick = { client.closeSession() }) { Text("←", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            } else {
                TextButton(onClick = onMenu) { Text("☰", fontSize = 18.sp) }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session != null) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (session.status == "running") StatusGreen else StatusGray))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            sessionName(session),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 标题占满剩余宽度（右上角只剩 🤖/📋 两个按钮），展示更完整
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(StatusGreen))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            state.connectedDevice?.name ?: "已连接",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    when {
                        session != null && session.parentSessionId != null ->
                            "🤖 子会话 · " + if (session.status == "running") "运行中" else "空闲"
                        session != null -> if (session.status == "running") "运行中" else "空闲"
                        else -> state.connectedDevice
                            ?.let { "${it.host}:${it.port} · ${state.sessions.count { s -> s.parentSessionId == null }} 会话" }
                            ?: ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 子代理入口（右上角）：当前会话（主会话或子代理会话）的后代树，对齐 DSH Web 嵌套浏览。
            // 全量会话已由 hello 下发（parentSessionId = 直接父），客户端按 parentSessionId 归组建树，
            // 展开/折叠是 UI 本地状态、零请求（铁律 6：数据以服务端投影为准）。
            if (session != null) {
                val childrenByParent = remember(state.sessions) { childrenByParent(state.sessions) }
                val counts = remember(state.sessions) { descendantCounts(state.sessions) }
                val totalDescendants = counts[session.id] ?: 0
                if (totalDescendants > 0) {
                    var subagentMenuOpen by remember { mutableStateOf(false) }
                    // 展开态随当前会话变化重置（键控 session.id），避免跨会话串树
                    var expandedIds by remember(session.id) { mutableStateOf(setOf<String>()) }
                    Box {
                        TextButton(onClick = {
                            ConnLog.info("ACTION", "子代理下拉打开 parentId=${session.id} 后代数=$totalDescendants")
                            subagentMenuOpen = true
                        }) {
                            Text("🤖$totalDescendants", fontWeight = FontWeight.SemiBold)
                        }
                        DropdownMenu(
                            expanded = subagentMenuOpen,
                            onDismissRequest = { subagentMenuOpen = false },
                        ) {
                            Text(
                                "子代理会话",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            // 最多同时展示 10 条，超过则列表内上下滚动（高度上限 + 滚动）。
                            // 注意：不能用 LazyColumn——DropdownMenu 内容区以 width(IntrinsicSize.Max)
                            // 做固有尺寸测量，LazyColumn 是 SubcomposeLayout，固有测量会抛
                            // IllegalStateException；嵌套树用 DFS 预展平为单层列表渲染，任意深度。
                            val nodes = flattenSubagentTree(session.id, childrenByParent, counts, expandedIds)
                            Column(
                                modifier = Modifier
                                    .heightIn(max = SUBAGENT_MENU_MAX_HEIGHT)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                if (nodes.isEmpty()) {
                                    Text(
                                        "暂无子代理",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    )
                                }
                                nodes.forEach { node ->
                                    SubagentNodeRow(
                                        node = node,
                                        expanded = node.session.id in expandedIds,
                                        onToggle = {
                                            val willExpand = node.session.id !in expandedIds
                                            expandedIds = if (willExpand) expandedIds + node.session.id else expandedIds - node.session.id
                                            ConnLog.info("ACTION", "子代理展开切换 id=${node.session.id} 展开=$willExpand")
                                        },
                                        onOpen = {
                                            subagentMenuOpen = false
                                            ConnLog.info("ACTION", "子代理点击 subagentId=${node.session.id} parentId=${session.id}")
                                            client.openSubagent(node.session.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onOpenLogs) { Text("📋") }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 嵌套树单行：缩进（depth 层级）+ 展开箭头（仅「有后代」的节点显示，点击切换展开/折叠）
 * + 会话名/副标题 + 后代计数。点击行主体打开该子代理；箭头是独立热区，不会误触打开。
 */
@Composable
private fun SubagentNodeRow(
    node: SubagentNode,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = (4 + node.depth * 16).dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 展开箭头区：固定 24dp 占位，保证各层级名称左对齐；无后代时留白。
        Box(
            modifier = Modifier
                .size(24.dp)
                .then(if (node.hasChildren) Modifier.clickable(onClick = onToggle) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (node.hasChildren) {
                Text(
                    if (expanded) "▾" else "▸",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                sessionName(node.session),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            // 副标题：状态标志 + 元信息（最后消息时间 / 运行时长 / token），
            // 各段用 · 分隔；过长可换行成多段（服务端投影为准，客户端不做推算）。
            SubagentSubtitle(node.session)
        }
        if (node.hasChildren) {
            Text(
                "🤖${node.descendantCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

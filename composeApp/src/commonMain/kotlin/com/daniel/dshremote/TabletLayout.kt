package com.daniel.dshremote

/**
 * 平板三栏适配的纯逻辑（无 Compose 依赖，commonTest 直测）。
 *
 * 断点：宽度 ≥ [TABLET_MIN_WIDTH_DP]（840dp）判定为平板 Expanded，等价于 Compose
 * `WindowWidthSizeClass.Expanded`。仅 Expanded 启用三栏，Compact/Medium 保持手机单页流
 * （手机横屏/折叠屏落 Medium，不触发，保证手机零回归）。
 *
 * 返回三档语义（平板）：1=关子会话回主会话；2=关主会话回列表；3=回桌面。
 */

/** 平板三栏最小宽度（dp）。 */
const val TABLET_MIN_WIDTH_DP = 840

/** 宽度是否命中平板三栏布局。 */
fun isTabletLayout(widthDp: Int): Boolean = widthDp >= TABLET_MIN_WIDTH_DP

/** 平板三栏可见性 + 返回档位状态。 */
data class TabletPaneState(
    val leftVisible: Boolean,
    val midVisible: Boolean,
    val rightVisible: Boolean,
    val backLevel: Int,
)

/**
 * 由导航状态推导三栏可见性与返回档位。
 * - 子代理打开（subagentReturnTo != null）：左栏收起、中栏主会话 + 右栏子会话、返回档 1。
 * - 主会话打开（currentSessionId != null）：左栏 + 中栏、返回档 2。
 * - 会话列表（currentSessionId == null）：仅左栏、返回档 3。
 */
fun tabletPaneState(currentSessionId: String?, subagentReturnTo: String?): TabletPaneState = when {
    subagentReturnTo != null ->
        TabletPaneState(leftVisible = false, midVisible = true, rightVisible = true, backLevel = 1)
    currentSessionId != null ->
        TabletPaneState(leftVisible = true, midVisible = true, rightVisible = false, backLevel = 2)
    else ->
        TabletPaneState(leftVisible = true, midVisible = false, rightVisible = false, backLevel = 3)
}

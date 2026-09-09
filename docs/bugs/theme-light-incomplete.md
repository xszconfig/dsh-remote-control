# 浅色模式深色残留（会话列表/输入框/系统栏仍黑）——enableEdgeToEdge 窗口背景未随主题

## 背景
主题功能（浅色/深色/跟随系统）已上线：颜色盘 token 化 + `DshTheme(darkTheme)` 两套 ColorScheme。用户验收发现**浅色模式下仍有大量黑色残留**——会话列表背景、输入框等仍是黑，未变白。

## 现象
- 验收截图（`/tmp/theme-light-screens/light.png`，1260×2844）：整体 91% 已变浅（#F8F9FC），但**顶部条带（y80-300）有 23% 深色块**（#313131 13%、#222222 10%）；底部区域纯浅色。
- 用户口头反馈：会话列表背景、输入框是黑的。

## 排查
1. 像素审计：深色块集中在顶部条带（状态栏+顶栏区域），底部纯浅 → 排除「某组件硬编码深色」的全局性假设，指向**窗口/系统栏层**。
2. 代码审计：`grep Color(0x...)` 全 commonMain 仅剩 Theme.kt/ColorTokens.kt（token 定义）；`Color.Black/Gray` 仅 QR 扫码器与 DiffView 分隔线（主题无关）；**没有任何组件直接引用 DshDarkColors** → 排除 commonMain 组件未 token 化。
3. 定位平台层：`MainActivity` 用 `enableEdgeToEdge()`（默认 `SystemBarStyle.auto`），它把系统栏设为**透明**，但 Activity 主题 `Theme.Material.NoActionBar` 的 **windowBackground 仍是深色**。透明状态栏下，顶部条带露出的是 window 背景（深色），而非 Compose 的 `background` 浅色。

## 根因（5-Why）
1. 为什么浅色模式顶部还是黑？→ 状态栏区域显示的是 window 背景，不是 Compose 背景。
2. 为什么显示 window 背景？→ `enableEdgeToEdge()` 让内容画到系统栏后面（`setDecorFitsSystemWindows(false)`），状态栏透明。
3. 为什么 window 背景是黑？→ Activity 主题 `Theme.Material.NoActionBar` 默认 windowBackground 为深色，从未随 `DshTheme(darkTheme)` 切换。
4. 为什么没随主题切换？→ 主题态在 commonMain 的 `App()` 内，平台层 `MainActivity` 无感知，也没有 expect/actual 把主题变化同步给 window。
5. 根因：**「应用主题（Compose MaterialTheme）」与「窗口/系统栏主题（windowBackground + 系统栏图标色）」两套状态未打通**，浅色只切了前者，后者仍深。

## 解法
新增 `expect/actual` 桥接：`DshTheme` 内调用 `SystemBarsSync(darkTheme)`，Android 实现同步三件事：
1. `window.decorView.setBackgroundColor(...)` 窗口背景随主题（深=0xFF0B0F1A / 浅=0xFFF8F9FC）。
2. `WindowCompat.getInsetsController(...).isAppearanceLightStatusBars = !darkTheme`（浅色用深图标）。
3. `isAppearanceLightNavigationBars = !darkTheme`（导航栏同）。

### commit
（见本文件落盘时的 HEAD；下方 Diff 为核心改动）

```kotlin
// Theme.kt (commonMain)
fun DshTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val scheme = if (darkTheme) DshDarkColors else DshLightColors
    SystemBarsSync(darkTheme)   // ← 新增
    ...
}
@Composable expect fun SystemBarsSync(darkTheme: Boolean)

// Platform.android.kt (androidMain)
@Composable actual fun SystemBarsSync(darkTheme: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.decorView.setBackgroundColor(if (darkTheme) 0xFF0B0F1A.toInt() else 0xFFF8F9FC.toInt())
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !darkTheme
            c.isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
```

## 改进计划
1. 设备复验（等主对话「手机已释放」）：浅/深各截会话列表/会话页/输入区/设置/队列/转盘，`vision_dominant_colors` 分区像素审计确认无深色残留。
2. 若仍有个别屏深色残留，按「MaterialTheme.colorScheme 槽位 + ColorTokens」继续收敛（当前 commonMain 已零硬编码深色，预计仅系统栏层问题）。
3. 考虑 `enableEdgeToEdge` 显式传 `SystemBarStyle`（当前用默认 auto + 手动 icon 色，等价）。

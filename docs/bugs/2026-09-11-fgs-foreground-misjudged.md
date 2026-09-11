# [BUG] FGS 前台服务不启动 + delivery_notice 误报 presence=BACKGROUND（前台被误判后台）

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已修复 |
| 仓库/模块 | App（Platform.android.kt `AppForeground` / KeepAlive.kt `KeepAliveController` / BridgeClient.kt） |
| 发现方式 | 真机验收（统一验收 FAIL） |
| 日期 | 2026-09-11 |
| 相关 commit | `c7f14a6` |
| 关联文档 | `docs/decisions/后台保活与通知兜底方案.md`、`docs/prd/主动通知与审批提醒PRD.md` |

## 背景

- 「按需前台服务保活」（方案②）由 `AppForeground`（ProcessLifecycleOwner 观测前台态）+ `KeepAliveController`（`combine(_session, connection.info)` 驱动启停）实现，commit `084256b`。
- `AppForeground` 设计为**惰性自注册**：首次 `isForeground()` 调用时 `ensureRegistered()` 添加观察者。
- `delivery_notice` 通知门控也依赖 `NotificationHost.isForeground()` → 同一个 `AppForeground.isForeground()`。

## 现象

真机验收：2 个子代理 running，`dsh_keepalive` 通知/服务均未出现；同期 `delivery_notice` 全部被三态门控判定为 `presence=BACKGROUND`（App 实际一直在前台）。

## 排查过程

1. 假设①：`AppForeground.foreground` 初始 false，观察者注册时序未回填当前态 → 前台误判后台。
2. 读代码确认：`ensureRegistered()` 由 `isForeground()` 触发，而 `isForeground()` 的调用链是
   `KeepAliveController.onProjectionChanged → host.isForeground() → platformIsAppForeground()`，
   而 `onProjectionChanged` 由 `combine(...).collect` 在 `Dispatchers.Default`（**后台线程**）执行。
3. 锁定：`ProcessLifecycleOwner.get().lifecycle.addObserver(...)` = `LifecycleRegistry.addObserver`，
   **非线程安全、必须在主线程调用**，后台线程调用抛 `IllegalStateException`；且原实现 `registered = true`
   在 `addObserver` 之前置位、异常被 `catch` 吞掉 → 永不重试 → `foreground` 永久 false。
4. 假设②（次级）：`combine(_session, connection.info)` 缺「前台/后台转场」触发源 → 即便前台判定修复，
   转前台瞬间若无投影/连接变化也不会重算 `decideFgsAction`。

## 根因分析（必须挖到深层）

- **表面原因**：`addObserver` 在后台线程抛异常被吞 + `registered` 旗标错误地提前置位阻止重试 → 前台态永久 false。
- **深层根因**：**「状态归属 + 线程归属」模型缺陷**——`AppForeground` 的初始化动作（注册 Lifecycle 观察者）被
  放在一个「由后台协程按需触发」的路径上，而该动作的**正确执行前提是主线程**；「惰性自注册」这一优化假设了
  `isForeground()` 会在主线程首次被调，但实际首次调用来自 `Dispatchers.Default`。同时，`KeepAliveController`
  的「前台态」被当作**瞬时读值**（`host.isForeground()`）而非**流输入**，导致「前台/后台转场」本身不是一个
  触发源——状态机的重算只由投影/连接驱动，漏掉了「前台态」这一维度的变化边沿。
- **可推广教训**：① 平台 API（Lifecycle 注册/UI 操作）有线程归属，绝不能放在「惰性 + 后台协程」路径；
  ② 状态机的**每一个输入维度都必须显式进入驱动流**（combine 的输入 = 状态机的全部输入），
  不能有「读一次就完」的隐藏输入；③ 初始化旗标要在**成功之后**置位，失败要允许重试。

## 解法

- `AppForeground` 改为 **`StateFlow<Boolean>` 单一事实源**，新增 `init()`（**主线程**显式调用，
  `MainActivity.onCreate` 里执行）：注册观察者 + **立即回填 `currentState.isAtLeast(STARTED)`**（首帧即正确，
  双保险于观察者 replay）；`isForeground()` 只读 `_flow.value`。
- `combine` 增加 `platformAppForegroundFlow()` 作为**第三输入**，前台/后台转场也触发 `decideFgsAction` 重算；
  `KeepAliveController.onProjectionChanged(sessions, connected, foreground)` 收前台态为显式入参，
  `KeepAliveHost` 移除 `isForeground()`。

核心 Diff（before → after）：

```kotlin
// Platform.android.kt AppForeground（before：惰性 + 后台线程 addObserver + registered 提前置位）
private var foreground = false
private var registered = false
private fun ensureRegistered() {
    if (registered) return
    synchronized(this) {
        if (registered) return
        registered = true                        // ← 提前置位，失败不重试
        try { ProcessLifecycleOwner.get().lifecycle.addObserver(...) } catch (_: Exception) {}
    }
}
// after：StateFlow 源 + init 主线程 + 回填
private val _flow = MutableStateFlow(false)
val flow: StateFlow<Boolean> = _flow.asStateFlow()
fun init() {  // MainActivity.onCreate（主线程）
    if (initialized) return
    synchronized(this) {
        if (initialized) return
        initialized = true
        try {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { _flow.value = true }
                override fun onStop(owner: LifecycleOwner) { _flow.value = false }
            })
            _flow.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)  // ← 回填
        } catch (_: Exception) { initialized = false }
    }
}
fun isForeground(): Boolean = _flow.value
```

```kotlin
// BridgeClient.kt（before：两路 combine，前台态读一次）
combine(_session, connection.info) { s, info -> s.sessions to (info.state == ConnectionState.Connected) }
    .collect { (sessions, connected) -> keepAlive.onProjectionChanged(sessions, connected) }
// after：三路 combine（前台态入流）
combine(_session, connection.info, platformAppForegroundFlow()) { s, info, foreground ->
    Triple(s.sessions, info.state == ConnectionState.Connected, foreground)
}.collect { (sessions, connected, foreground) ->
    keepAlive.onProjectionChanged(sessions, connected, foreground)
}
```

- 提交哈希：`c7f14a6`（main）
- 回归测试：`KeepAliveTest` 增 4 例（前台转场 false→true 触发 START / 初始化回填即 START / 计数不变跳过更新 / 归零停止），共 13 例全绿。

## 后续改进计划

- 设备复验并入下一装机批次（本批先交付代码 + 单测证据）。
- 教训推广：排查「状态机不触发」类问题，先核对**驱动流的输入是否覆盖状态机全部输入维度**（本 bug 的 combine 缺前台态）；
  「线程归属」类问题，先核对平台 API 调用点是否在主线程（Lifecycle/UI/通知相关）。

# [BUG] 技能点选后键盘未自动弹起（焦点被 ModalBottomSheet 退场回收冲掉）

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已修复（真机复验通过） |
| 仓库/模块 | App（`Composer.kt` `ConversationComposer` / `Platform.kt` / `Platform.android.kt`） |
| 发现方式 | 真机验收（#57 技能点选预填，两小问题之一） |
| 日期 | 2026-09-12 |
| 相关 commit | 755f3a3（InputMethodManager 唤起）、bd74cf2（时序 tweak）、c4b2bd7（预填光标置末尾） |
| 关联文档 | `docs/reports/acceptance/2026-09-10-batch-reverify.md`（#57 节） |

## 背景

- #57 需求：点选技能 → 把 `/技能名 ` 字面量写入输入框（带尾空格，光标置末尾）→ 收起技能面板 → **焦点拉回输入框并自动弹起软键盘**，让用户直接接续输入提示词。
- 预填光标问题由 `c4b2bd7` 用 `TextFieldValue`（`selection = TextRange(length)`）修复。
- 键盘自动弹起先后试了多条路：`SoftwareKeyboardController.show()`（华为 IME 下不稳定）、
  `LaunchedEffect(inputFocused)`、`withFrameNanos`、不同 delay（120/320ms）、
  `InputMethodManager.showSoftInput`（`SHOW_IMPLICIT` / `SHOW_FORCED`，即 755f3a3）。
  以上全部「show 成功但键盘不弹」——`showSoftInput` 返回成功、IME 层确认 `showSuccess=true`，
  但 `mInputShown=false`、`mServedView=DecorView`、`EditText focused=false`。

## 现象

- 点选技能后输入框预填 `/code-lint `（尾空格）成功、焦点日志短暂出现「输入框获得焦点」，
  但软键盘不弹起；随后 `dumpsys input_method` 显示：
  ```
  mShowRequested=false mInputShown=false
  mServedView=DecorView@bb54486[MainActivity]   ← 服务的是 DecorView，不是 Compose 输入框
  EditText '/code-lint ' focused=false           ← 焦点已丢
  ```
- logcat 里 `HwInputMethodManagerService: showSoftInput end! showSuccess = true`
  —— IMM 层「成功」但 UI 不弹，是典型的「show 时焦点已丢 / 服务错 view」。

## 排查过程

1. 先用 `SoftwareKeyboardController.show()`：`kb=true` 打点显示 show 已调用，键盘不弹。
2. 换 `InputMethodManager.showSoftInput`（SHOW_IMPLICIT → SHOW_FORCED）：logcat 确认
   `showSoftInput end! showSuccess = true`，但 `mInputShown` 仍 false。
3. `dumpsys input_method` 对比：`mServedView=DecorView`（而非 `AndroidComposeView`），
   且 `EditText focused=false`。锁定两个症状：**焦点丢了** + **show 服务到了错误的 view**。
4. 推断：`platformShowSoftInput()` 里 `activity.currentFocus` 返回 null → 回退 `decorView`；
   而 `requestFocus()` 之后焦点又被清掉 → 形成「focused=true 后立刻回 false」的竞态。
5. 把 `LaunchedEffect` 时序从「requestFocus → delay(320) → show」改为「delay(380) →
   requestFocus → delay(160) → show」，真机复验通过（见下「解法」）。

排查受限点：华为 face 锁屏在自动化期间反复上锁（`deviceLocked=1`），一度挡住复验；
用锁屏密码 `325498` 解锁后才完成取证。

## 根因分析（必须挖到深层，不允许停留在表面现象）

- **表面原因**：`requestFocus()` 在 `showSkillPanel=false` 的**同一帧**立即执行——此刻
  `ModalBottomSheet` 仍在播退场动画（约 300ms）、还占着窗口焦点。等退场动画结束，
  sheet 的焦点回收把刚建立的输入框焦点清掉；随后 `platformShowSoftInput()` 运行时
  `activity.currentFocus == null`，回退到 `decorView`，IME 服务错 view，键盘不弹。
- **深层根因**：这是典型的「多个异步时间线竞争同一焦点状态、无单一事实源」问题——
  ① Compose 的 `FocusRequester.requestFocus()`（逻辑焦点）与 ② `ModalBottomSheet` 的
  退场生命周期（窗口焦点回收）是两条互不知情的异步时间线；代码用「固定 delay + 一次性
  requestFocus」试图对齐它们，但时序错位（requestFocus 跑在退场回收之前），导致焦点被
  后发生的退场回收覆盖。教训：**依赖「事件对称置清」的焦点/弹层时序，必须让 show 动作
  排在「所有可能清焦点的异步收尾」之后，且 show 之前要确认焦点真正落定到目标 view**
  （用 `currentFocus` 是否等于 ComposeView 作为证据，而非只看 show 的返回码）。

## 解法

- 把 show 之前的工作拆成两段等待：先等 sheet 退场释放焦点，再 requestFocus，再等
  window focus 落定，最后 show。这样 `activity.currentFocus` 能拿到 `AndroidComposeView`，
  `showSoftInput` 服务到正确的 Compose 输入连接。

- **核心 Code Diff**（`Composer.kt` `ConversationComposer` 内 `LaunchedEffect`）：

  ```kotlin
  // before（755f3a3）
  LaunchedEffect(showSkillPanel) {
      if (!showSkillPanel && pendingSkillFocus) {
          pendingSkillFocus = false
          inputFocusRequester.requestFocus()
          delay(320)
          platformShowSoftInput()
      }
  }

  // after（bd74cf2）
  LaunchedEffect(showSkillPanel) {
      if (!showSkillPanel && pendingSkillFocus) {
          pendingSkillFocus = false
          delay(380)                      // 等 ModalBottomSheet 退场动画完成并释放焦点
          inputFocusRequester.requestFocus()
          delay(160)                      // 等 FocusRequester → window focus 异步落定
          platformShowSoftInput()
      }
  }
  ```

- 提交哈希：`bd74cf2`（时序 tweak，main）；前置 `755f3a3`（InputMethodManager 唤起）与
  `c4b2bd7`（预填光标置末尾）。

- **真机复验证据**（华为 HBN-AL00 / Android 12，`192.168.3.84:5555`）：
  ```
  mShowRequested=true mShowExplicitlyRequested=true mShowForced=true mInputShown=true
  mServedView=androidx.compose.ui.platform.AndroidComposeView{72244d3 ...}
  mServedInputConnectionWrapper=...NullableInputConnectionWrapperApi25@... mServedView=AndroidComposeView...
  ```
  输入框 dump：`text='/code-lint '`（预填带尾空格）；追加 `run P0` 后 `text='/code-lint run P0'`
  ——光标在末尾（追加而非前置），两个问题同时通过。

## 后续改进计划

- [ ] 把「show 前确认 `activity.currentFocus` 为 ComposeView」封装成带重试的 helper，
      彻底摆脱固定 delay 的脆弱性（delay 值随设备/动画时长漂移仍可能复发）。
- [ ] 排查同类弹层（模型面板 `ModelEntry`、强度面板、上下文详情）收起后是否也需要
      「退场后聚焦」的时序，统一收口到一个 `awaitSheetDismissedAndFocus()` 工具。
- [ ] 教训推广：凡「弹层收起 + 焦点回填 + 键盘唤起」三段联动，先查本 doc；判断标准
      一律以 `mServedView` 是否等于 `AndroidComposeView`、`mInputShown` 是否为 true 为准。

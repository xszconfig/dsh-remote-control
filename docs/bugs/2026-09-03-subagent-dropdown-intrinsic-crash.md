# [BUG] 子代理下拉 DropdownMenu 内 LazyColumn 触发 intrinsic 测量崩溃

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已修复 |
| 仓库/模块 | App（`composeApp/src/commonMain/kotlin/com/daniel/dshremote/App.kt` 会话页 TopBar 子代理下拉） |
| 发现方式 | 真机验收（装机后必现崩溃） |
| 日期 | 2026-09-03 |
| 相关 commit | `99689390e16f5eb9e3abd8b214a2410781673d3f`（`9968939`） |
| 关联文档 | `docs/bugs/_TEMPLATE.md`；AGENTS.md 铁律 11（真机验收工作流） |

## 背景

- 会话页 TopBar 的「🤖N」子代理下拉：点击展开 `DropdownMenu`，列出当前会话的 N 个子代理（`subagents`，按 `parentSessionId` 过滤）。功能上做了「上限 10 条」的同屏约束（`SUBAGENT_MENU_MAX_VISIBLE = 10`、`SUBAGENT_MENU_MAX_HEIGHT = 48.dp * 10`）。
- 之前为「子代理可能很多」用了 `LazyColumn` + `heightIn(max = SUBAGENT_MENU_MAX_HEIGHT)`，意图是懒加载虚拟化 + 高度上限、超了在列表内滚动。
- 该下拉是最近新增功能（`11feae1` feat：子代理列表副标题元信息 + 上限 10 条滚动），构建与单测全绿后装机。

## 现象

- 真机打开会话页，点击「🤖N」子代理下拉，**必现崩溃**：

  ```
  IllegalStateException: Asking for intrinsic measurements of SubcomposeLayout
  layouts is not supported
  ```

- 屡次弹出「应用已停止运行」弹窗；崩溃只发生在真机运行、UI 实际布局阶段——**构建与 JVM 单测全绿，装机后才发现**。

## 排查过程

- 从崩溃栈定位到 `DropdownMenu` 内容区里的 `LazyColumn`：报错关键字 `intrinsic measurements … SubcomposeLayout` 直接指向「固有测量 + SubcomposeLayout」组合。
- 确认 material3 `DropdownMenu` 的内容区用 `width(IntrinsicSize.Max)` 做固有尺寸测量；`LazyColumn` 底层是 `SubcomposeLayout`，它**不支持固有测量**，一旦被放进做固有测量的容器，测量阶段直接抛异常。
- 全仓库排查同类风险：无其它 `DropdownMenu` + `IntrinsicSize` 组合；其余 5 处 `LazyColumn` 都位于普通滚动容器（如 `Scaffold`/`Column`）内，不涉及固有测量，安全。
- 取证受限点/教训点：这是**布局期**崩溃，JVM 单元测试不渲染 UI、不做布局，所以「单测全绿」完全覆盖不到它——只能靠真机验收（或 Compose UI instrumented 测试）兜底。

## 根因分析（必须挖到深层，不允许停留在表面现象）

- **表面原因**：`DropdownMenu` 的内容区用 `width(IntrinsicSize.Max)` 做固有尺寸测量，而放进它的 `LazyColumn`（`SubcomposeLayout`）不支持固有测量 → `IllegalStateException`。

- **深层根因（框架特性组合禁忌）**：`LazyColumn` 是 `SubcomposeLayout`，其测量依赖「按需 compose 子项」，无法在测量前给出固有尺寸（intrinsic），而 material3 `DropdownMenu` 恰好要求对内容做固有测量以确定宽度——**「把懒布局放进一个会做固有测量的容器」是 Compose 的经典组合禁忌**。更深的教训是：**单测不渲染 UI，布局期崩溃在 JVM 单测里不可见**——「编译通过 + 单测全绿」不等于「真机能跑」，这类问题必须靠真机验收 / Compose UI（instrumented）测试兜底。这正是本仓库架构评审里「无 UI 测试」缺口的第一次实战代价。

- **可推广教训**：凡是「懒布局（LazyColumn/LazyRow/LazyVerticalGrid…）放进会做 `IntrinsicSize`/固有测量的容器（DropdownMenu、`Row(IntrinsicSize)`、`Popup` 等）」都要直接判为非法组合；凡是「改了 UI 布局结构」的提交，不能只信单测，必须走真机验收或 Compose UI 测试。

## 解法

- 方案说明：把 `LazyColumn` 换成普通 `Column(heightIn(max) + verticalScroll)`。子代理列表有「上限 10 条」的同屏约束（`SUBAGENT_MENU_MAX_VISIBLE = 10`，实际是 26 条以内的小列表），用非懒布局没有任何性能问题，同时保留「高度上限 + 列表内滚动」的既有语义；`subagents.forEach { sub -> … }` 替代 `items(subagents, key = { it.id })`。没有用「给 DropdownMenu 换测量方式」这种 hack，因为约束本身是「小列表 + 滚动」，`Column` 是最贴合语义、最稳的选择。

- **核心 Code Diff**（`git show 9968939 -- composeApp/src/commonMain/kotlin/com/daniel/dshremote/App.kt` before/after）：

```diff
-                            // 最多同时展示 10 条，超过则列表内上下滚动（LazyColumn 虚拟化 + 高度上限）
-                            LazyColumn(modifier = Modifier.heightIn(max = SUBAGENT_MENU_MAX_HEIGHT)) {
-                                items(subagents, key = { it.id }) { sub ->
+                            // 最多同时展示 10 条，超过则列表内上下滚动（高度上限 + 滚动）。
+                            // 注意：不能用 LazyColumn——DropdownMenu 内容区以 width(IntrinsicSize.Max)
+                            // 做固有尺寸测量，LazyColumn 是 SubcomposeLayout，固有测量会抛
+                            // IllegalStateException；26 条以内用非懒布局无性能问题。
+                            Column(
+                                modifier = Modifier
+                                    .heightIn(max = SUBAGENT_MENU_MAX_HEIGHT)
+                                    .verticalScroll(rememberScrollState()),
+                            ) {
+                                subagents.forEach { sub ->
                                     DropdownMenuItem(
```

- 提交哈希：`99689390e16f5eb9e3abd8b214a2410781673d3f`（`9968939`，工作树 main）。

- 回归：本次为 UI 结构修复，JVM 单测不覆盖布局（正是本 bug 的盲区），已改由真机验收确认不再崩溃；后续计划用 Compose UI instrumented 测试补上可自动回归的兜底。

## 后续改进计划

- [ ] **Compose UI 冒烟测试（instrumented）仍在待办**：把「打开会话页 → 展开 🤖N 下拉 → 断言不崩溃且渲染 N 条」固化为 instrumented 测试，让这类「布局期/组合禁忌」崩溃能在装机前自动抓到，而不是靠人工真机验收。
- [ ] 同类组合（`IntrinsicSize` / `SubcomposeLayout`）已全仓库排查无他处，但建议纳入 code review 检查清单：新 UI 代码凡出现「Lazy* 布局」都要确认其父容器是否做固有测量。
- [ ] 教训推广：更新 UI 开发规约——「懒布局不进固有测量容器」；「UI 结构改动必须真机验收或 instrumented 测试，单测全绿不构成放行依据」。

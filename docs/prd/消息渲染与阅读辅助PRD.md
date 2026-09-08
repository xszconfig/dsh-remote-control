# 消息渲染与阅读辅助 PRD

> 归属：Markdown 解析展示域。覆盖 App 消息气泡内 Markdown 的**阅读体验规则**（代码块 / 表格的
> 排版与滚动），与 `主题与深色模式PRD.md`（配色 token）互补。移动端优先（铁律 9）。

## 一、代码块展示

### 规则

1. **一行代码 = 一行展示**：代码文本禁用自动换行（`softWrap = false`，每行独立文本），长行
   不再折 2-3 行。
2. **横向滑动**：代码区套 `horizontalScroll`，超宽长行左右滑动查看；横向手势只作用在代码区，
   竖向手势透传给外层消息列表（不干扰列表滚动）。
3. **行号**：行号固定列 + 代码区横滑（**取舍：行号固定、代码区横滑**，而非「整体横滑」）——
   往右看长行时行号始终可见，符合 IDE / 代码浏览习惯。行号右对齐、等宽字体、弱化色
   （`codeText.copy(alpha≈0.45)`），与代码行逐行对齐（同字号等宽，`padStart` 补位）。
4. 行号 1 起；空行保留占位（`" "`）以保持行高；Windows `\r\n` 去 `\r`。
5. 配色/字号沿用 `LocalMarkdownColors`/`LocalMarkdownTypography.code`（App 已定 12sp mono +
   深底浅字），与库默认一致。

### 实现

- `MarkdownCodeBlock.kt`：`ScrollableCodeBlock`（行号列 + `weight(1f).horizontalScroll` 代码区）
  + 纯函数 `buildCodeBlockLines`（按行拆分 + 右对齐行号）。
- 接入：`App.kt` `Markdown(components = markdownComponents(codeFence/codeBlock = ...))`，替换
  mikepenz 默认代码块（库默认无行号、且 `softWrap` 默认 true）。

## 二、表格展示（已实施，记录在案）

1. **横向滑动**：宽表格在气泡宽度内左右滑动查看全部列；单元格不换行、按真实内容宽度测量。
2. **表格前空行归一化**：`normalizeMarkdownTables` 在渲染前为表格块前补空行（org.intellij.markdown
   的 GFM 表格不支持打断段落）。
3. 详见 `MarkdownTableScroll.kt` 与 `docs/bugs/2026-09-07-markdown-table-needs-blank-line.md`。

## 三、验收口径

- 代码块：发一条含超长行代码的消息 → dump/截图验证「单行不折行 + 横向滑动可见尾部 + 行号固定」。
- 回归：普通代码块（多行/空行/`\r\n`）、表格横滑、表格前空行、竖向列表滚动不受横滑干扰。

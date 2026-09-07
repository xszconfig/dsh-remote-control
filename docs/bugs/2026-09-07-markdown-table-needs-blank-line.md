# [BUG] Markdown 表格前缺空行 → App 端整块塌成纯文本（竖线原样显示）

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已修复 |
| 仓库/模块 | App（`App.kt` 的 `Bubble()` 渲染入口 + `MarkdownTableScroll.kt`） |
| 发现方式 | 用户报告（手机 App 里 agent 消息的 Markdown 表格「解析失败」） |
| 日期 | 2026-09-07 |
| 相关 commit | `bb2ee9d`（fix 代码；该提交因并发碰撞同时合入了 `AGENTS.md` 的改动） |
| 关联文档 | `docs/ui-mobile-first.md` 教训 #6；本仓库 mikepenz markdown-renderer 0.28 |

## 背景

App 用 mikepenz `multiplatform-markdown-renderer` 0.28 渲染 agent 正文（`Bubble()` 里
`markdown = true` 分支调用 `Markdown(...)`）。该库底层是 JetBrains `org.intellij.markdown`
（`org.jetbrains:markdown` 0.7.3）的 GFM 解析器。此前已修复过「宽表格横向滑动」
（`MarkdownTableScroll.kt` 自定义 table 组件，commit 更早），本次是同一渲染域的第二类问题：
表格的**段落级解析**失败。

## 现象

- 用户消息里 `**P0 批次（直接补竞争短板，1-2 周）**` 这一行**紧跟着**一张 GFM 表格（中间无空行）：
  ```markdown
  **P0 批次（直接补竞争短板，1-2 周）**
  | 功能 | 竞品依据 | 我们现状 |
  |---|---|---|
  | ... | ... | ... |
  ```
- 在手机 App 里，这段内容没有渲染成表格，而是**整块显示为纯文本**（`|`、`---` 竖线原样露出），
  用户感知为「表格解析失败」。
- 同一输入在 DSH Web 会话页（unified + micromark + micromark-extension-gfm-table ≥2.1）
  **能正常渲染**——因为 Web 端的 GFM 表格支持打断段落（dynamic-interrupt hack）。

## 排查过程

1. **逐字符核对表格语法**：表头 3 列、分隔行 `|---|---|---|` 合法、表体 5 行各 3 列、
   单元格内 `**加粗**` 成对、无未转义 `|`、`「」❗✓→——（）` 等全角/emoji 均为普通文本
   → **排除「表格自身语法违规」**。
2. **Web 端引擎定位**：DSH Web 前端 bundle（`dsh-web-frontend/dist/assets/vendor-*.js`）含
   `gfmTable` / `tableDelimiter` / `_gfmTableDynamicInterruptHack` / `Unified` → 是
   unified + micromark + micromark-extension-gfm-table。用同栈 `remark-gfm` 2.1.1 实测原文：
   三种写法（表单独 / 加粗行紧跟表无空行 / 加粗行+空行+表）**全部解析出 1 个 table、6 行×3 格**。
3. **App 端引擎定位**：用仓库内 `org.intellij.markdown` 0.7.3 + `GFMFlavourDescriptor`
   跑 AST dump（临时单测探针，已删）：
   - `**P0 批次**\n| 功能 |…|`（无空行）→ 输出**单个 `PARAGRAPH`**（表格消失、竖线变 TEXT）；
   - `**P0 批次**\n\n| 功能 |…|`（有空行）→ 正确输出 `PARAGRAPH` + `TABLE`；
   - 两个连续表格之间无空行 → 被**合并成一个 TABLE**。

## 根因分析（必须挖到深层，不允许停留在表面现象）

- **表面原因**：`org.intellij.markdown` 的 GFM 表格实现**不支持打断段落**——表格块必须与
  前一个块之间有空行；否则表格头行 `| … |` 被当作上一段的 lazy continuation（继续段落），
  分隔行、表体行随之被并进段落，整块塌成纯文本。两个连续表格同理会被合并。
- **深层根因**：这是**两套渲染器对同一 Markdown 方言（GFM）的语义分歧**，而非生成侧语法错误：
  - Web 端（micromark-extension-gfm-table ≥2.1）紧跟 cmark-gfm，表格**可**打断段落；
  - App 端（org.intellij.markdown 0.7.3）**不可**打断段落。
  同一份 agent 输出，Web 正常、App 塌掉——本质是「生成端按 Web 的宽松规则写、App 端按更严
  的规则解析」的双端一致性缺口。可推广教训：**Markdown 方言特性（表格/围栏/列表等）在跨
  渲染器时，必须按「最严格的一方」作为生成规范**，而不是默认两端一致。

## 解法

- **方案**：不改解析器、不升级依赖（mikepenz 0.28 锁死底层 org.intellij.markdown），在
  **渲染入口对源码做轻量归一化**——识别「表格表头行（行首 `|` 且下一行是 GFM 分隔行）」，若
  其前一行非空，则在表头前自动补一个空行。幂等、只动表格块边界、不触碰表格内部与其它内容。
- 为什么不改库/不升级：升级 mikepenz 属重大选型（铁律 1）且会影响既有字号映射等约定；
  预处理是零依赖、可单测、风险最小的收敛点。

**核心 Code Diff**（`MarkdownTableScroll.kt` 新增 + `App.kt` 渲染入口一行）：

```kotlin
// App.kt Bubble() —— Markdown 调用处
Markdown(
-    content = text,
+    content = normalizeMarkdownTables(text), // 表格前补空行
     ...
)

// MarkdownTableScroll.kt
internal fun normalizeMarkdownTables(input: String): String {
    val lines = input.split('\n')
    if (lines.size < 2) return input
    val out = ArrayList<String>(lines.size + 4)
    for (i in lines.indices) {
        val line = lines[i]
        if (isTableHeaderStart(lines, i)) {          // 行首 `|` 且下一行是分隔行
            val prev = out.lastOrNull()
            if (prev != null && prev.isNotBlank()) out.add("")  // 前一行非空 → 补空行
        }
        out.add(line)
    }
    return out.joinToString("\n")
}
```

- 提交哈希：`bb2ee9d`（fix 代码；因并发提交碰撞，该 commit message 误标为 docs(agents)，fix 文件
  App.kt / MarkdownTableScroll.kt / MarkdownNormalizeTest.kt 均在其中）。
- 回归测试：`MarkdownNormalizeTest` 9 例（段落紧跟表、已有空行幂等、表内行不受影响、连续表格
  补空行、连续表格已分幂等、无表不变、`|` 非表头不误判、对齐冒号分隔行、空/单行不变）。

## 后续改进计划

- 待办：由主对话决定是否 `git rebase` 拆分 `bb2ee9d` 以还原干净的 fix commit message。
- 教训推广（写入生成规范）：**安全表格写法** = 表格前后各空一行（最关键，兼容
  org.intellij.markdown）、分隔行每列 ≥3 个 `-` 且列数与表头一致、单元格内 `|` 转义 `\|`、
  加粗/斜体成对不跨格、表格内不插空行/列表。
- 可选根治：约束 agent 生成端在输出 Markdown 时强制表格前空行（抹平双端差异的源头）。

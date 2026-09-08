# [BUG] 消息转盘在子代理会话不生效（父 agent 派活指令被判为上下文注入被排除）

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已修复 |
| 仓库/模块 | 桥（src/core.ts）+ App（MessageDialMath.kt / App.kt，无需改） |
| 发现方式 | 用户报告 |
| 日期 | 2026-09-06 |
| 相关 commit | 桥 `8ac3e39`（coordinator source.kind 判为用户） |
| 关联文档 | `docs/bugs/message-source-classification.md`（本 bug 的直接前因）、`docs/decisions/网络连接与弱网可靠性调研.md` |

## 背景

转盘目标集 `userMessageRefs` 只收 `type=="user_message" && !isInjectedUserMessage(source)`（即 wire `source=='user'`）。
`source` 字段来自此前修的 `message-source-classification`（P0：注入上下文消息不得渲染为用户气泡）——桥侧把
DSH `user/message` 节点的 `source.kind` 判成 `'user'`（kind==='user'）或 `'inject'`（其余），客户端据此
区分用户气泡与「上下文」弱化行、并据此决定转盘目标集。

## 现象

主会话转盘正常；**子代理会话里转盘不生效**（上翻不出现，或出现后几乎无目标）。用户在看子代理会话时想拨转盘
跳到自己（经父 agent）派发的指令，却定位不到。

## 排查过程

1. 从 `~/.dsh/sessions/--Users-xieshaoze-Code-dsh-remote-control--/` 找 2 个子代理会话（`f0ebf6dc`=分析 Jugg 无头 CLI 可行性、
   `cfd68bf6`=准备 Android 模拟器环境），解压 `session.jsonl.zstd` 统计 `user/message` 的 `source.kind`：

   | source.kind | 含义 | Jugg(f0ebf6dc) | 模拟器(cfd68bf6) |
   |---|---|---|---|
   | `user` | spawn 初始任务 | 1 | 1 |
   | `coordinator` | **父 agent relay（真正派活）** | 1（"用户已拍板：实施 Jugg MVP…这单交给你"） | 2（"新任务：增强装机进度…"、"华为安装器页面已变更…你负责这两个 skill"） |
   | `agent-instructions` | AGENTS.md 系统提醒 | 4 | 5 |
   | `skill-catalog` | skill 目录注入 | 3 | 4 |
   | `plugin` | 插件快照 | 1 | 1 |

2. 桥 `src/core.ts:2717-2718` 分类：`src === 'user' ? 'user' : 'inject'` → **coordinator 被归入 inject**。
3. 客户端 `isInjectedUserMessage(source)` = `source != null && source != "user"` → coordinator（inject）被排除出目标集。
4. hasMore 推断：Jugg ≈330 投影行 → hasMore=true（spawn 在尾部窗口外，需翻页狩猎）；模拟器 ≈272 行 → hasMore=false（spawn 在窗口内）。
   两种情况下目标集都只剩 spawn 一条，coordinator（父真正派活）缺失。

## 根因分析（必须挖到深层，不允许停留在表面现象）

- **表面原因**：wire `source='inject'` 把两类本质不同的 `user/message` 混为一谈，客户端转盘只认 `'user'`，导致子代理会话里
  父 agent 的 relay（coordinator）被当成「上下文注入」排除。
- **深层根因**：`message-source-classification` 修 bug 时把分类**压缩成了二值 `user/inject`**，丢失了 DSH 日志里本就存在、
  且可区分的 `source.kind` 细粒度（user / coordinator / agent-instructions / skill-catalog / plugin）。
  在主会话里二值够用（真实用户=user，其它全是上下文），但在子代理会话里「用户」这一角色是**父 agent**——其派活走的是
  `coordinator` 而非 `user`，于是二值分类在子代理维度上语义错位：把「用户指令（coordinator）」误判成「上下文（inject）」。
  可推广教训：**「用户」不是固定身份，而是会话视角的函数**——子代理视角下父 agent 就是用户；任何按角色过滤的分类
  在跨会话层级投影时必须保留足够粒度，不能把「角色」压成二元就丢掉「谁是用户」的信息。

## 解法

- 方案：**桥侧把 `source.kind === 'coordinator'` 与 `'user'` 同判为用户角色**（`src === 'user' || src === 'coordinator'`）。
  理由：coordinator = 父 agent 经 relay 派活，对子代理而言就是用户消息；且主会话不会出现 `kind='coordinator'` 的 `user/message`
  （coordinator 只存在于子代理的父→子 relay），故对主会话零影响。客户端 `userMessageRefs`/`isInjectedUserMessage` 无需改动
  （coordinator 现在走 `'user'`），渲染也顺带正确（父派活显示为用户气泡而非弱化上下文行）。

- 核心 Code Diff（桥 src/core.ts）：
```diff
-      const source: EventSource = src === 'user' ? 'user' : 'inject'
+      const source: EventSource = (src === 'user' || src === 'coordinator') ? 'user' : 'inject'
```

- 提交哈希：桥 `8ac3e39`（`fix(bridge): coordinator source.kind 判为用户——子代理会话父 relay 归用户气泡（转盘定位）`）；客户端无改动。

- 回归测试：桥 `test/smoke.mjs` 消息来源分类段补 2 例——`coordinator → source=user`、`skill-catalog → source=inject`
  （验证父 relay 命中为目标、系统上下文仍排除）。`node test/smoke.mjs` 该 6 条 source 断言全 PASS。
  （注：smoke 另有 4 条 LSP 相关 FAIL 为存量问题，与本次无关；contract-check 4 处 drift 为 msgId/ping/pong 并发改造未合入所致。）

## 后续改进计划

- 待办：真机/模拟器在子代理会话上翻 → 转盘定位到父派活指令的端到端复验（本次修复后）。
- 教训推广：凡是「同一事件类型承载多种角色/来源」的投影，保留 `source.kind` 全量粒度或至少按「会话视角」区分用户身份，
  不要压缩成二值后再在客户端按角色过滤——角色语义应在服务端投影层按视角一次性算清（铁律 6）。

# [BUG] 手机切换模型/推理强度后服务端不跟随切换

## 元信息

| 项 | 值 |
| --- | --- |
| 状态 | 已修复 |
| 仓库/模块 | 桥（src/core.ts `installSelectionFor`/`set_model`） |
| 发现方式 | 用户报告（真机实测：客户端选模型/切强度后服务端没跟着切） |
| 日期 | 2026-09-12 |
| 相关 commit | `1c082e5`（bridge v0.17.2） |
| 关联文档 | `docs/decisions/输入区操作条PRD.md`（2.2 模型切换）、`docs/decisions/子代理嵌套浏览对齐调研.md` |

## 背景

手机操作条的「选择模型 / 推理强度」通过 `set_model` 命令切模型。设计语义（已拍板）：**仅当前会话、下一步 prompt 组装边界生效（不打断当前推理）**，切换经 `installModelSelection` 把「可变选择 ref」耦合到 Agent 的 prompt 组装瀑布。

DSH 的模型选择是「每个 entry point 各自挂载自己的 selection ref」：
- DSH Web 走 apiproxy 的 `selectionFor(agent)`（`dsh-host-apiproxy/api-proxy.js` L866-896）——它维护 apiproxy 自己的 `selections` WeakMap，并 `installModelSelection(agent.ctx, selection)`。
- 桥（手机）走 `installSelectionFor(agent)`（`core.ts`）——桥自己维护 `selections` WeakMap，也 `installModelSelection(agent.ctx, ref)`。

两套 ref 是**各自独立的闭包 WeakMap**，dsh-agent 无「公开的权威 selection registry」（`model-selection.d.ts` 只导出 `installModelSelection` + `ModelSelection`/`ModelSelectionRef`），apiproxy 的 `selections` 不暴露到 cordis context。

## 现象

手机端选择模型或切换推理强度后，`models_update` 回显了新的 current（客户端入口显示变了），但**服务端下一轮实际用的还是旧模型**——用户看到「手机显示切了、但 Agent 仍用旧模型」。

## 排查过程

1. 链路核查（App → wire → 桥 → 服务端）：
   - App `client.setModel` → `connection.send(ClientCommand.SetModel(sessionId, provider, model, reasoningEffort))` ✓ 命令发出。
   - 桥 `set_model` handler（`core.ts` L2343）收到命令，`resolveCallConfig` 校验通过（未回 `model_unavailable`）✓。
   - `installSelectionFor(a).current = selected` 写的是**桥自己的 ref** ✓ 写入成功。
2. 疑点锁定：读 apiproxy `selectionFor` + `selectModel`（Web 切换），发现 Web 写的是 **apiproxy 自己的 ref**（`selectionFor(found.agent).current = selected`），与桥的 ref 不是同一个对象。
3. 读 dsh-agent `installModelSelection`（`model-selection.js`）：它只是 `agentCtx.on('system-prompt/assemble', …)` + `agentCtx.on('agent/request', …)`（**默认 append**）。桥与 apiproxy 各自调用，等于在**同一 agent.ctx 的 waterfall 上追加了两个 listener**。
4. 确认 Cordis waterfall 顺序：先注册（链头）的 listener 先执行，但其 apply 发生在 `await next()` 返回之后（**最后 apply = 覆盖后注册者**）。桥 `core.ts` 已在 `llm/stream`/`approval/request` 用过 `{ prepend: true }`，说明 `ctx.on` 支持 prepend。

## 根因分析（5-Why）

- **表面原因**：手机 set_model 写的桥 ref，被 apiproxy 的 ref 在 waterfall 里覆盖。
- **Why 1** 为什么下一轮用旧模型？→ 下一轮 prompt assembly 的最终 provider/model 来自 waterfall 最后 apply 的 ref。
- **Why 2** 为什么最后 apply 的是 apiproxy 而非桥？→ 对「Web 创建的会话」，apiproxy 在会话创建/恢复时（`composeAgent`→`installSelection`→`selectionFor`）先挂载（append，链头）；桥在手机 set_model 时才挂载（append，链尾）。链头最后 apply，覆盖链尾。
- **Why 3** 为什么桥和 apiproxy 各挂一个 ref？→ 两者是各自独立的 WeakMap，都调 `installModelSelection` 耦合到同一 agent.ctx。
- **Why 4** 为什么不能复用同一 ref？→ DSH 无「公开的权威 selection registry」，apiproxy 的 `selections` 是内部闭包，桥无法访问；dsh-agent 只暴露 `installModelSelection`（无 getter）。
- **深层根因（可推广教训）**：**状态归属模型缺陷**——「下一步用哪个模型」这一单一事实，被拆成多个 entry point 各自维护的私有 ref，且 waterfall 的「最后 apply 胜」依赖注册顺序（隐式、脆弱）。桥作为第二个 entry point 介入时，只能靠「抢占注册顺序」来让自己的写入生效，而不是写真正的单一权威存储。

## 解法

- **方案**：桥改用 **prepend** 挂载自己的 selection ref（`installModelSelectionPrepend`，逻辑与 dsh-agent 完全一致，仅 `on(..., { prepend: true })`）。桥的 listener 抢占 waterfall 链头 → 桥的 apply 最后执行 → 覆盖 apiproxy 的 ref → 手机切换生效。
- **为何不选别的**：无法复用 apiproxy 闭包 ref（无公开 API）；直接改 dsh-agent 的 `installModelSelection` 为 prepend 会全局改变 Web 语义、风险大；「桥 prepend」是本仓库内最小、可回滚的改动。
- **核心 Code Diff**（`src/core.ts`）：

```ts
// before：桥挂载用默认 append，被 apiproxy 链头覆盖
installModelSelection(scopedCtx ?? agent.ctx, ref)

// after：prepend 抢占链头，桥最后 apply 覆盖 apiproxy
const installModelSelectionPrepend = (agentCtx, selection) => {
  agentCtx.on('system-prompt/assemble', async (_a, _c, next) => {
    const selected = selection.current
    const assembled = await next()
    selection.assembled = selected
    if (selected === undefined) return assembled
    return { ...assembled, variables: { ...assembled.variables, provider: selected.provider, model: selected.model } }
  }, { prepend: true })
  agentCtx.on('agent/request', async (_p, next) => {
    const resolved = await next()
    const selected = selection.assembled
    if (selected === undefined) return resolved
    const { reasoningEffort: _e, ...rest } = resolved
    return { ...rest, provider: selected.provider, model: selected.model, ...(selected.reasoningEffort === undefined ? {} : { reasoningEffort: selected.reasoningEffort }) }
  }, { prepend: true })
}
// installSelectionFor 改用 installModelSelectionPrepend
```

- **提交哈希**：`1c082e5`（bridge v0.17.2）。
- **回归测试**：smoke 增断言「set_model 后 `system-prompt/assemble` 与 `agent/request` 均以 `prepend:true` 挂载」；163/163 全绿。

## Web 选择器 UI 显示滞后（验收口径澄清，2026-09-12 追问）

用户补充验收口径：手机切换后，**DSH Web 输入框下方的模型/强度选择器要看到变化**，而不只是「下一轮实际生效」。经只读调研，结论是**平台限制，无公开权威写入口让 Web UI 即时反映**：

- **Web UI 数据源**：`dsh-client-ui-model-selection` 的 `ModelDirectory.load()` → apiproxy `session.models` RPC（`api-proxy.js` L1895-1903）→ `selectionFor(agent).current`。该 getter 三级回退：`picked`（apiproxy 闭包内存，Web 自己切时写）→ `agent.session.requestHeader()?.config`（最近请求 header，只读，从日志折叠）→ `defaults.defaultModelSelection()`（部署默认）。
- **权威写入口**：**无公开入口**。① apiproxy 的 `selections` WeakMap / `selectionFor` 是 `apply` 内闭包，不暴露到 cordis context（grep 仅 3 处内部引用）；② `session.requestHeader()` 是只读 getter（`headerFold` 私有缓存，从 `request/header` 事件折叠）；③ `defaultModelSelection`（`ctx.agentDefaultModel`）桥可写，但被 requestHeader 覆盖，仅对「尚未跑过任何请求的空会话」有即时效果，且**违背已拍板「仅当前会话、不存默认」决策**，故不可用。
- **实际生效面（0.17.2）**：桥 prepend 抢占 waterfall 链头，保证「下一轮 prompt 组装/agent 请求用新值」，下一轮后 `request/header` 记录新值 → 之后 Web UI（在其 refetch 时）才显示新值。smoke 已断言「set_model 后以 prepend:true 挂载 system-prompt/assemble + agent/request」；「下一轮 requestHeader 变为新值」属 prepend waterfall 语义推论，**未真机核验**（本环境无真机，需装机批次验证）。
- **可选 workaround（均不根治，供决策，不擅自做）**：① 桥切后 emit `llm/adapters-updated` 触发 Web refetch——但 refetch 读的 apiproxy ref 的 fallback 仍要到下一轮 requestHeader 更新才变，只缩短「下一轮后」的显示延迟，不能「立即」显示；② 桥写 `agentDefaultModel.saveSelection`——被 requestHeader 覆盖且违背已拍板决策。故**如实报告为平台限制**，根治需 DSH 上游提供「单一权威 selection registry」供 apiproxy/桥共享读写。

## 后续改进计划

- **语义边界（已向主对话说明）**：本修复满足「仅当前会话、下一步 prompt 组装边界生效」语义；一个已知边缘——桥 prepend 抢占后，若用户**随后又回到 Web 切换模型**，桥的 picked 会暂时覆盖 Web 的切换（直到桥再次 set_model 或下一轮 requestHeader 记录 Web 选择）。用户主场景是「手机遥控、模型在手机切」，此边缘可接受；若需「双端任意切换都即时生效」，需上位到 DSH 的「单一权威 selection registry」（跨 apiproxy/桥共享），属 DSH 上游架构改动。
- **教训推广排查清单**：任何「桥复刻了 DSH Web 某个 handler 的写入」的功能，都要先查「Web 读/写的是不是同一个存储」；多 entry point 共享单一状态时，优先找公开 registry，找不到再显式处理注册顺序/覆盖关系，并写进 commit 与文档。

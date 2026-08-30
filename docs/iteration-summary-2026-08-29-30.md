# 功能迭代总结 2026-08-29 ~ 08-30（v0.11.4 → v0.11.9）

> 覆盖 dsh-remote-control（Android 客户端）+ dsh-remote-control-bridge（服务端插件）两个仓库。
> bridge 版本 0.11.4 → 0.11.9；冒烟测试 49 → 76 断言（76/76 通过）。
> 另见：`debug-protocol-cdp-vs-dap.md`（调试协议选型）、两仓库 `AGENTS.md`（协作铁律）。

## 版本时间线

| 版本 | 主题 | 关键内容 |
|---|---|---|
| 0.11.4 | LSP 真机诊断打通 | typescript-language-server 诊断推送手机 |
| 0.11.5 | 会话隔离 | Deep Diving/诊断按 sessionId 隔离，openSession 全量重置 |
| 0.11.6 | Goal 面板 | goal/change 投影广播 + 订阅携带；手机 Goal 条（Deep Diving 下、排队上） |
| 0.11.7 | Debug + Kotlin 通道 | Node Inspector 受控调试全链路；Kotlin LSP 通道（.kt/.kts） |
| 0.11.8 | 计时/todos/工具/队列 | deep_diving_tick、todos_update、lsp_query 工具、排队跨重启快照 |
| 0.11.9 | Agent 调试工具 | debug_start / debug_command（与手机按钮同通道） |

## 功能域总结

### 1. LSP 代码智能
- 语言覆盖：TS/JS（tsserver）、Python（pyright）、Rust（rust-analyzer）、C/C++（clangd）、**Kotlin（官方 JetBrains kotlin-lsp，IntelliJ 内核）**
- Kotlin 0 诊断六层根因修复：pull 诊断模式（diagnosticProvider 只推空 publishDiagnostics）→ 主动 textDocument/diagnostic；KotlinProblemHighlightFilter content-root 门控 → 向上找 gradle/pom 项目根导入；initialized 误带 id → 改 notify；didChange version Date.now() 溢出 int → 递增小整数；文件路径未 realpath（macOS /tmp→/private/tmp 软链）→ 统一 realpath + docPaths 映射回原路径；ready-for-test 后强制重同步 + 长窗口拉取
- 对齐 oh-my-pi（本地源码 /Users/xieshaoze/github/oh-my-pi）初始化时序：全量 CLIENT_CAPABILITIES、workspaceFolders、didChangeConfiguration
- **lsp_query Agent 工具**：diagnostics / hover / definition / references（OMP 同款，真实 server 四动作验证通过）
- 边界（官方限制）：松散单文件无诊断；KMP 项目级支持官方 "coming"；首次预热 4-5 分钟（x86_64 Rosetta）

### 2. Debug 受控调试（CDP / Node Inspector）
- 后端：`node --inspect-brk=0` → stderr 解析 WS 地址 → Debugger/Runtime 域驱动（断点/暂停/调用栈/变量/单步/跳出/输出）
- 踩坑与修复：-brk 需 Runtime.runIfWaitingForDebugger 释放（只发 resume 脚本不启动）；入口暂停单次自动恢复（双 resume 撞 "Can only perform operation while paused"）；断点 URL realpath 对齐；暂停帧 url 为空经 scriptId 查 scriptParsed 表；console 双通道去重；脚本跑完检测 "Waiting for the debugger to disconnect" 主动断开；**会话自然结束后注册表残留修复**（"已有调试进程在运行"卡死）
- 控制面：REST `/remote/debug/start|stop`（Agent）+ WS `debug_command`（手机按钮）+ **debug_start/debug_command Agent 工具**（0.11.9）
- 手机调试面板：开发状态行（诊断+调试聚合徽标）→ 开发面板抽屉（诊断/调试 Tab：调用栈帧切换自动拉变量、变量树递归惰性展开、继续▶/单步⤵/跳出⤴/停止、输出区）
- 选型论证见 `debug-protocol-cdp-vs-dap.md`（CDP-only 适合 Node 系 coding agent 场景；DAP seam 已预留）

### 3. Deep Diving（对齐 DSH Web 语义）
- 根因修正：早期"每次模型请求归零"看起来像本地乱跳 → 对齐 DSH Web 真实语义（读 dsh-client-ui-conversation 源码）：**锚点 = 当前 OPEN 轮次开始时间**、**标签整个轮次期间显示**、**≥15s 才显示时钟**（showClock 阈值）
- 服务端权威：turn/start → turn_status(open) + 立即 tick；turn/end → turn_status(closed)；ticker 每秒广播 elapsedSeconds（服务端时钟）
- 订阅响应携带 turnSince：中途切入会话立即显示标签
- 客户端：删除本地计时与轮次边界推断；格式按用户规范 秒→分→时（<60s N秒；≥60s X分Y秒；≥60min X小时Y分）

### 4. Goal / 任务列表（todos）
- Goal：ctx.goals.get(agent)（活会话）+ 投影快照（冷会话）；goal/change → goal_update 广播；**inject 修复**（属性直读触发 cordis "cannot get property without inject" 陷阱 → 改 ctx.get 软读——真实服务器上 Goal 曾全废）
- 任务列表：todos 投影（todo/write 全量快照）；todo/write → todos_update 广播；**turn/start 广播空 todos_update**（任务列表按轮次生命周期，下轮不残留旧任务）
- 手机 UI：Goal 条折叠单行可展开；任务列表条（Deep Diving 下、Goal 上，≤3 条滑动、✅/▶️/⏳ 状态图标、completed 弱化）

### 5. 排队消息
- 面板 ≤3 条显示 + 上下滑动（小屏空间预算）
- **跨重启快照恢复**：队列变化 2s 防抖快照进 work.json；重启后与活队列对比（id/文本去重），丢失才经 followup 重新注入，恢复后清快照（幂等）

### 6. 自动续跑（根治版）
- 旧问题：一次性定时器在 agent 晚挂载时永久放弃（"暂无 live agent，跳过"后不再试）
- 新机制：**每 20s 持续重试直到注入成功** + **指纹幂等**（activity+pending+sessionId 未变不重复注入）+ **agent/status running 即时触发** + **work.json sessionId 归属**（优先唤醒待办所属会话）
- 已验证：连续多次重启自动唤醒到正确会话
- 遗留优化（待做）：首次尝试与重试间隔 20s 偏慢，目标挂载后 ≤3s 唤醒（缩短间隔 + session/created、手机订阅等触发信号）

### 7. UI 细节
- 发送按钮 → Agent 运行中变红色终止按钮（interrupt）
- 顶栏去掉「断开」按钮，标题占满宽度
- 开发状态行聚合 LSP + 调试信号（无信号不占空间，绝不自动弹面板）

## 技术选型（用户拍板）
- Kotlin LSP：官方 JetBrains kotlin-lsp（brew tap / vsix 解包 ~/.dsh/kotlin-lsp）而非 fwcd 社区版
- 调试协议：CDP（Node Inspector 直连）而非 DAP（OMP 路线）——详见解 `debug-protocol-cdp-vs-dap.md`
- 协作铁律写入两仓库 AGENTS.md：重大架构/路线/选型决策必须先问用户

## 质量数据（48h 编译统计）
| 项目 | 编译次数 | 错误次数 | 错误率 | 最高频错误 |
|---|---|---|---|---|
| App (Kotlin) | 89 | 26 | 29.2% | Unresolved reference（8 次） |
| Bridge (TS) | 85 | 13 | 15.3% | TS2322/2345/2554 类型族（7 次） |

规律：错误主因是"新功能扩展后消费面没同步改全"（缺分支/缺 import/类型没对齐），绝大多数一次修复通过。

## 验收状态
- ✅ 服务端自测全过：冒烟 76/76；debug 真机同路径 8/8（断点→栈→变量→单步→继续→输出流）；lsp_query 四动作真实 server；Goal 订阅数据链路；自动续跑指纹证据
- ✅ 真机交互验收：排队 5 条（插队/删除/3 条滑动）、任务列表 5 条（图标/滚动/清空）
- ⏳ 待用户视觉验收：Deep Diving 标签全轮次 + 15s 时钟 + 任务列表轮次自动清空
- ⏳ 待做（明天）：自动续跑速度优化（≤3s 唤醒）

## 提交与推送
- 两仓库按用户指示已推送 GitHub（bridge `b13f142`、app `a4e1202` 批次）；其后又有若干本地 commit（Deep Diving 对齐、turnSince、调试注册表修复等），待用户指示再推。

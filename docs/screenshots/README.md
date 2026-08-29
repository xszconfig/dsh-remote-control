# 验收截图说明

本目录截图来自 **Android 模拟器（emulator-5554, API 31, arm64）+ `tools/mock-bridge` 模拟桌面端** 的真机链路端到端验证（非静态 mock 数据，全部走真实 WebSocket/HTTP 协议）。

复现方式：`cd tools/mock-bridge && npm install && PORT=3081 node mock-bridge.mjs`，然后 `adb reverse tcp:3081 tcp:3081`，App 手动连接 `127.0.0.1:3081` + token `mock-env-token-123`（或 seed 设备文件，见各 commit message 中的验证记录）。

| 截图 | 场景 | 验证点 |
|---|---|---|
| `01_landing_empty.png` | 首次启动，无设备 | 空态引导、扫码/手动连接入口 |
| `02_device_list_online.png` | 已配对设备列表 | 12s 探测 `/remote/ping` → 在线状态（绿点 + 状态文案）、相对时间 |
| `03_session_list.png` | 连接后的会话列表 | 顶栏设备名与连接信息、工作区徽标、运行状态、子代理计数、中断按钮 |
| `04_conversation.png` | 会话对话 | 消息气泡（你/Agent）、工具调用折叠卡、工具结果、时间戳（kotlinx-datetime） |
| `05_approval_dialog.png` | 远程审批弹窗 | 工具名 + 原因，允许一次/拒绝（ApprovalDecision 枚举，wire 值 `allowed-once`/`rejected`） |
| `06_reconnect_banner.png` | 桌面端掉线 | 自动重连横幅：退避计数（第 N 次 · Xs 后重试）、**会话数据保留**、取消按钮 |
| `07_approval_after_reconnect.png` | 桌面端恢复后 | 自动重连成功（device token 走 Bearer 头）→ 审批弹窗再次弹出，**断线期间审批不丢** |

## 对应的提交与验证记录

每个功能点独立 commit（含详细的问题/改法/效果说明与验证数据）：

```
fix(time)      时间格式化 kotlinx-datetime + 测试基建
refactor(protocol) ApprovalDecision 枚举 + 协议序列化测试
refactor(pairing)  配对/URL 解析提取纯函数 + 测试
fix(store)     设备持久化并发丢写竞态 + 原子写 + 写放大节流
fix(client)    发送失败可见化 + errors 封顶 + UI 错误横幅
fix(ui)        返回按钮纯本地导航（消除 openSession("") 协议 hack）
fix(state)     断开保留用户偏好（selectedWorkspaceId 等）
fix(auth)      长期 token 改走 Authorization Bearer 头（含 mock-bridge 工具）
refactor(arch) BridgeClient 拆分为 ConnectionManager/DeviceRepository/状态流
fix(session)   事件列表 500 条截断上限
feat(reconnect) 断线自动重连（指数退避 + 竞态修复 + 审批不丢）
```

单测：`./gradlew :composeApp:testDebugUnitTest` → **49/49 通过**。

## 已知问题（非本轮引入）

- debug 构建冷启动头十几秒在模拟器上 JIT/渲染负载高，期间快速操作可能触发系统 ANR 弹窗（点 Wait 即恢复）；C8 及更早版本同样存在，真机影响小得多。

# DSH Remote Control 连接架构说明

手机 App（`dsh-remote-control`）与桌面端（`dsh web` + `dsh-remote-control-bridge` 插件）之间的连接设计说明：建立、长链、协议、策略、重连、心跳，以及调试模式（USB）与线上模式（无线）的差异。

## 1. 总览

```
手机 App ──ws://<host>:3080/remote/ws──▶ [传输载体] ──▶ 127.0.0.1:3080 (dsh web)
   │                                        │
   │ 客户端命令（JSON 帧）                    │ 服务端事件（JSON 帧）
   └──────────────── 双向单条 WebSocket ──────┘
```

- 桌面端 `dsh web` **只监听 127.0.0.1**（DSH 内核安全设计，刻意禁止 `--host 0.0.0.0`）。
- 因此一切连接都必须**终结在桌面回环地址**；USB 与无线的区别只是中间这一跳的物理载体，协议/凭证/指令完全一致（传输无关设计）。

## 2. 连接如何建立

单条 WebSocket：`ws://<host>:<port>/remote/ws`（默认端口 3080）。三种发起路径：

| 路径 | 凭证 | 说明 |
|---|---|---|
| 扫码 | 二维码内一次性 `?pair=` token | 配对 JSON 或裸 `ws://` 地址，逐候选尝试 |
| 设备列表点击 | 长期 device token（`Authorization: Bearer` 头，不进 URL） | 已配对设备直连 |
| 手动输入 | 可选 token | host/port/token 表单 |

握手成功后手机发 `register_device` → 桌面签发**长期 token** 回传并落盘（`devices.json`），之后断线重连都靠它。首次扫码成功后会自动注册，无需手动配对。

## 3. 长链保持与心跳

- **协议层心跳**：OkHttp 引擎 `pingInterval(30s)`——每 30 秒发一次 WebSocket 协议 PING，桌面 `ws` 库按 RFC6455 自动回 PONG；用于保活 NAT/代理空闲超时并探活 TCP。
- **无读超时**：长连接不设 `readTimeout`（只设 `connectTimeout(4s)`，用于候选地址快速失败回退）。
- **应用层无自造心跳**：两端都不实现业务级 ping/pong 帧，完全依赖标准协议心跳。
- **设备在线探测**：HTTP `GET /remote/ping`（3.5s 超时），每 12 秒一轮、仅未连接时运行，驱动设备列表在线/离线绿点。
- **桥接 → 桌面 mux 旁路**：独立 WebSocket 下链（`/api/events.mux`，只读），断开指数退避重连（1s 起封顶 30s），重连后桌面重放未决帧。

## 4. 协议指令清单

**客户端 → 服务端（9 条）**

| 指令 | 作用 |
|---|---|
| `list` | 请求全量快照（等同 `hello`） |
| `subscribe` | 订阅会话并拉取历史事件 |
| `send_message` | 向会话发指令 |
| `interrupt` | 中断会话 |
| `approve` | 裁决 bridge 持有的审批 |
| `answer_approval` | 裁决桌面持有的审批（经 `/api/respond`） |
| `answer_question` | 回答桌面持有的提问（`ask_user_question`） |
| `register_device` | 设备配对 / 刷新长期 token |
| `revoke_device` | 撤销设备凭据 |

**服务端 → 客户端（15 类事件）**

`hello`（会话/工作区/Agent/三个挂起队列快照）· `history` · `event`（实时事件流）· `agent_status` · `session_title` · `approval_request` · `approval_resolved` · `approval_settled`（旧版兼容）· `question_request` · `question_resolved` · `device_registered` · `device_revoked` · `error`

**旁路**：桌面端（apiproxy）持有的审批/提问经 `/api/events.mux` WebSocket 下链推给桥接，桥接转发手机；手机回答后桥接 `POST /api/respond` 回传。手机与桌面 UI 先到先得。

## 5. 连接策略

- **逐候选回退**：二维码含多个候选地址（局域网 IP + Tailscale IP + 127.0.0.1…），按序尝试，4 秒建连超时快速跳过不可达候选。
- **失败诊断**：全部失败时聚合各候选失败原因；候选含 127.0.0.1 时附加「先执行 `adb reverse tcp:3080 tcp:3080`」的引导。
- **并发互斥**：`openMutex` 串行化所有连接打开，防止重连循环与手动连接并发互踩。
- **认证熔断**：收到 `auth` / `forbidden` / `device_revoked` 等错误码 → 判定凭据失效，放弃重连并提示重新配对。

## 6. 断线自动重连

- **会**，但仅限**有凭据**的连接（扫码配对后、设备列表点击、手动带 token）。用户主动点「断开」则不再重连。
- **指数退避**：1s → 2s → 4s → 8s → 16s → 封顶 30s。
- **幂等守护**：已有活跃重连循环时不重启第二个。
- **数据不丢**：重连期间保留会话/工作区/事件数据，顶部显示「连接已断开，正在自动重连（第 N 次 · Xs 后重试）」横幅，可手动取消。
- **凭据现取**：每次重试前重读本地存储的设备 token（防桌面轮换 token）。
- **成功复位**：重连成功并收到 `hello` 后退避计数归零。
- **挂起恢复**：重连后的 `hello` 携带三个挂起队列（bridge 审批 / 桌面审批 / 提问），手机弹窗不丢。

## 7. 调试模式 vs 线上模式

两种模式共用同一套协议与 App，**唯一区别是中间的传输载体**。

### 7.1 调试模式（USB 物理连接）

- 载体：USB-C 数据线 + Android 调试桥：`adb reverse tcp:3080 tcp:3080`
- 效果：手机上的 `127.0.0.1:3080` 被 adb 反向隧道映射到电脑的 `127.0.0.1:3080`
- 特点：零额外配置、延迟最低、仅限电脑旁边使用、依赖 adb 常驻
- App 侧填 `127.0.0.1:3080`（或扫二维码，候选里含 127.0.0.1）

### 7.2 线上模式（无线）

dsh 只绑回环，无线必须通过「终结在桌面 localhost 的隧道」：

| 方式 | 手机侧 | 桌面侧 | 特点 |
|---|---|---|---|
| **Tailscale（推荐）** | 装 Tailscale App 登录同一 tailnet | `tailscale serve --tcp=3080 tcp://localhost:3080` | 跨网可用（出门在外也行）、自动加密、MagicDNS 主机名 |
| SSH 隧道 | 装 Termius 等 SSH 客户端 | `ssh -L 3080:127.0.0.1:3080 user@host` | 同局域网或公网可达即可 |
| 局域网直连 | — | **不可行**（dsh 拒绝非回环绑定） | 被内核刻意封死 |

### 7.3 切换到无线（App 零改动）

1. 手机装 Tailscale App，登录电脑所在的 tailnet；桌面执行 `tailscale serve --tcp=3080 tcp://localhost:3080`
2. App 手动连接：host 填电脑的 Tailscale IP 或 MagicDNS 主机名，端口 3080
3. 配对一次 → 设备列表照常绿点、照常自动重连

扫码同样可用：桥接生成二维码时枚举系统全部网卡 IP，Tailscale 的 `100.x` 地址天然包含在候选列表里，手机多候选自动回退会试通。

### 7.4 两模式对照

| 维度 | 调试模式（USB） | 线上模式（无线） |
|---|---|---|
| 传输载体 | USB + adb reverse | Tailscale / SSH 隧道 |
| App 改动 | 无 | **无** |
| host 填写 | `127.0.0.1` | Tailscale IP / MagicDNS 名 / SSH 本地端口 |
| 在线探测 | `/remote/ping` 经 USB | `/remote/ping` 经隧道（同样生效） |
| 认证 | device token（Bearer） | 不变 |
| 安全模型 | 回环终结 | 回环终结 + 隧道加密，DSH 始终不暴露公网 |
| 使用范围 | 电脑旁 | 任何有网络的地方 |

## 8. 协议选型：为什么是 WebSocket

### 8.1 决策依据

对本项目而言，决定因素是**服务的推送形态**，而不是传输效率：

1. **服务端主动推送是刚需**。会话事件流（Agent 边想边刷屏）、审批/提问的**中断式弹窗**都必须「秒达」手机。轮询做不到低延迟 + 省电；纯推送协议（SSE）又是单向的。
2. **双向全双工**。手机既要收事件流，又要随时发指令（`send_message` / `interrupt` / `approve`）。SSE 只支持服务器→客户端，客户端指令得另开 HTTP 通道，两套状态要自己缝合。
3. **一条连接复用一切**。所有会话、审批、提问共用一条 WS，移动端省电、省连接数。
4. **穿隧道最友好**。它是单条 TCP：`adb reverse`、`tailscale serve`、`ssh -L` 都一行搞定；HTTP/2 多路复用流穿隧道要复杂得多。
5. **生态成熟**。Kotlin 侧 Ktor/OkHttp、Node 侧 `ws` 都是标准实现；内置协议级 ping/pong（30s 心跳直接复用）。
6. **协议简单**。纯 JSON 帧 + `type` 判别，TypeScript ↔ Kotlin 镜像协议成本极低（`protocol.ts` ↔ `Protocol.kt` 一一对应）。
7. **与 dsh 架构天然契合**。dsh 的 web server 原生提供 `registerUpgrade` 插槽，桥接插件「寄生」在现有 HTTP 端口上，无需另开端口。

### 8.2 其他候选协议

| 协议 | 形态 | 为什么不选 / 什么场景会选 |
|---|---|---|
| HTTP 轮询 | 客户端定时拉 | 最简但延迟高、耗电、流量浪费——被事件流形态否决 |
| HTTP 长轮询 | 挂起请求等事件 | 穿透性最好，但半双工、延迟仍高、移动端后台行为差 |
| SSE | 服务器单向推 | 形态上最接近（事件流），但客户端命令要另开通道；本项目最终形态 ≈「SSE + 反向命令通道」合并成的 WS |
| gRPC / gRPC-Web | 双向流 RPC | 能力等价（bidi streaming），但 KMP 依赖重、隧道/代理 HTTP/2 问题多 |
| WebRTC DataChannel | P2P 直连 | NAT 穿透强，但要信令服务、重连逻辑复杂——桌面工具用不上 |
| MQTT | 发布/订阅 broker | IoT 场景强（QoS、低功耗、多设备广播）；本项目是一对一控制面，无 broker 需求 |
| QUIC/HTTP3 | 未来向 | 移动网络切换无缝的潜力股，KMP 侧生态尚不成熟 |
| Unix socket | 同机进程间 | 跨设备场景直接排除 |

### 8.3 WebSocket 优劣势与本项目对策

**优势**：全双工、单连接、低延迟推送（中断式审批的命脉）；比轮询省电省流量；标准成熟（心跳/关闭语义内置）；HTTP Upgrade 握手复用现有端口、可带认证头（Bearer token）；单 TCP 隧道兼容性最佳。

**劣势与化解**：

| 劣势 | 本项目对策 |
|---|---|
| 长连接保活依赖（NAT 超时、网络切换即断） | 30s 协议 PING + 自动重连 |
| 移动网络切换/息屏被杀（Android 省电策略） | App 目前前台使用；后台场景需前台服务（未来可做） |
| 有状态连接，断线即丢上下文 | `hello` 全量快照 + 三个挂起队列在重连后补发 |
| 无内置 QoS/可靠投递（断线期间事件会丢） | 重连后以服务端日志为准重建（`subscribe` 拉历史） |
| 无请求-响应关联原语 | 事件驱动 + 命令制，避免 RPC 式配对 |
| 部分企业代理不支持 Upgrade | 隧道终结回环，完全规避 |
| 服务端水平扩展需 sticky session | 单机桌面工具，无此问题 |

**结论**：本系统的本质是「一个需要双向的实时事件管道」——WebSocket 是候选中在「双向、低延迟、单连接、隧道友好、生态成熟」五个维度上唯一没有短板的方案；它的短板（保活、断线、有状态）恰好都能用「心跳 + 快照重建」模式兜住，即本文第 3、6 节已实现的机制。


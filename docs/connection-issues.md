# 连接问题持续记录（Connection Issues Log）

> 作为 coding agent，连接是重中之重、基础中的基础。本文件持续记录连接层的问题现象、根因、修复与经验，后续排查一律先查这里。

## 维护约定

- 每条记录：**日期 / 现象 / 根因 / 修复（commit）/ 经验**
- 修复一律带 commit 引用，双仓库都有的分别标注（`app:` / `bridge:`）
- 排查优先用两端日志组件：手机「📋 日志」页（本机 + 服务端 Tab）或
  `adb logcat -s dsh-conn:V` / `curl http://127.0.0.1:3080/remote/logs`

## 记录

### 2026-08-29 日志页可达性矛盾（app: `d6d9aa7`）
- **现象**：日志页只在「未连接」落地页可达，而服务端日志 Tab 只在「已连接」时能拉取——诊断功能互相矛盾。
- **修复**：顶栏加 📋 入口（连接态可用）；`loadServerLogs` 未连接时回退最近在线设备（HTTP 不依赖 WS）；服务端 Tab 切换自动拉取。
- **经验**：诊断入口必须全状态可达，否则出问题时恰好打不开。

### 2026-08-29 隧道静默死亡（实测：多路由救场成功）
- **现象**：`adb reverse --remove` 后手机与服务端均无感知（~15s 内双方都以为还在线）。
- **根因**：隧道被移除时 socket 无 FIN/RST 送达；服务端旧构建无心跳、客户端无 pong 校验。
- **实测时间线**：16:08:44 移除隧道 → 16:09:16 前后 OkHttp pingInterval(30s) 的
  pong 超时判死生效 → 16:09:35 自动切到 Tailscale 端点重连成功（候选 1/3 127.0.0.1 快速失败）。
  检测延迟 ≈ 50s（一个 ping 周期 + 重连）。服务器日志可见「当前 2 个客户端」僵尸并存，
  待 `dshweb-restart` 后服务端心跳上线自动清理。
- **经验**：OkHttp 的 pingInterval 有 pong 超时判死语义（ktor 只是转发配置）；
  两端都要判活，单靠 TCP close 检测会在隧道类网络下双双失明。

### 2026-08-29 华为 logcat 缓冲区滚动覆盖（排查方法）
- **现象**：事后 `adb logcat -d` 查不到 App 的 dsh-conn 日志。
- **根因**：华为系统日志刷屏（每秒数十条 FrameRate/AGPService），main buffer 1-2 分钟即滚动覆盖。
- **结论**：手机端排查优先用 App 内置「📋 日志」页（内存环形缓冲 1500 条）；logcat 只适合实时抓取。
- **经验**：实时抓取用 `adb logcat -s 'dsh-conn*:V'`（通配符，`dsh-conn:V` 匹配不到 `dsh-conn/CONNECT`）。

### 2026-08-29 安装与「健康使用手机」联动
- **现象**：安装器（华为应用市场）与 dsh Remote 均触发「今日可用时长已用完」。
- **处理**：延时使用 → 今日使用不受限（无需 PIN，直接放行）。

### 2026-08-29 工具调用结果只显示「结果」（app: `70e1c9e` / bridge: `ae381a5`）
- **现象**：会话页工具结果卡片只有标签没有内容。
- **根因**：`tool/result` 事件真实文本嵌套在 `message.content[0].content[]`（`tool-result`
  容器块内层），bridge 的 `extractText` 只拼顶层 `text` 块。
- **修复**：递归提取容器块 + 4000 字符截断；客户端结果卡可点击展开。
- **经验**：透传协议时对容器型 ContentBlock 必须递归，不能假设文本在顶层。

### 2026-08-29 提问提交报「question answer rejected / not pending」（bridge: 未单独成 commit，随日志 commit）
- **现象**：手机半屏提问弹窗正常，选择+提交后报错，桌面端弹框不消失。
- **根因**：客户端 kotlinx `encodeDefaults=true` 会把 `custom: null` 显式序列化，
  桌面端 zod `z.string().optional()` 只接受缺省、拒绝 null → `bad-response`。
  此前 bridge 的报错文案硬编码为「not pending」，掩盖了真实拒绝原因。
- **修复**：bridge 侧剥离 null 字段归一化；错误文案区分「not pending / answer rejected」。
- **经验**：回传报文必须与服务端 schema 严格对齐（含 null vs 缺省语义）；
  错误消息要带真实原因，不要用笼统文案掩盖。

### 2026-08-29 提问弹窗收不到（bridge: `a387e37`）
- **现象**：`ask_user_question` 在桌面弹出，手机毫无反应。
- **根因**：`/api/events.mux` 是 **WebSocket 下行**，bridge 误用 fetch-SSE 永远收不到帧。
- **修复**：改 ws 客户端连接 `ws://127.0.0.1:<port>/api/events.mux`（只读，断线指数退避重连）。
- **经验**：dsh 的 /api 事件下链是 WS，不是 SSE；帧格式为 `{type:'server-request', rpcId, method, payload}`。

### 2026-08-29 手机回答无法回传桌面（bridge: `a387e37`）
- **现象**：answer_question 后桌面端无反应。
- **根因**：`POST /api/respond` 报文缺 `type: 'client-response'` 判别字段，被 zod 拒绝。
- **修复**：补齐完整报文形态。
- **经验**：dsh RPC 四象限全形态都带 `type` 判别字段，缺一不可。

### 2026-08-29 断线后「僵尸连接」（bridge: `2f8a62f`）
- **现象**：USB 隧道消失后，`/remote/connected` 仍显示手机在线；手机侧却在无限重连。
- **根因**：隧道中断时服务端收不到 close 帧；ws 服务端默认只被动响应 ping，不主动判活。
- **修复**：服务端 30s 心跳判活（ping/pong），超时 terminate 并记录日志。
- **经验**：任何长连接服务端都要做死连接探测，不能依赖客户端正常关闭。

### 2026-08-29 重连横幅永不消失（app: `8b9933a`）
- **现象**：重连成功后顶部「连接已断开，正在自动重连」横幅一直残留。
- **根因**：`startReconnect` 成功路径只重置退避计数，未复位 `reconnecting` 状态。
- **修复**：成功（established + hello）即复位状态；重连循环保持存活作为连接守护者。
- **经验**：状态机每个成功分支都要显式复位所有相关状态。

### 2026-08-29 USB 隧道断开后无限空转（app: `50508cd` / `ac37a59` / 多路由 `21ec49e`、bridge `eb6e486`）
- **现象**：拔 USB / adb server 重启后，手机一直「正在自动重连」但连不上，无任何提示。
- **根因**：`adb reverse` 隧道消失，手机 127.0.0.1:3080 空转；此前设备记录只有单端点。
- **修复**：
  - 连续失败 3 次且候选含 127.0.0.1 → 横幅给出 adb reverse 指引（`50508cd`）
  - 首次直连也走多候选快试（`ac37a59`）
  - 设备注册下发候选端点（127.0.0.1 + 局域网 + Tailscale IP），重连逐路回退（`21ec49e` / `eb6e486`）
- **经验**：USB 调试场景必须给用户可行动的指引；多路由是无线/有线切换的基础。

### 2026-08-29 断线期间会话事件丢失（app: `8b9933a` + 缓存 `27bcd89`）
- **现象**：断线期间产生的事件，重连后会话页看不到。
- **修复**：hello 后自动重新 subscribe 以服务端历史为准；本地事件缓存（`EventCache`）先渲染缓存秒开。
- **经验**：长连接断了就要靠「快照重建 + 本地缓存」双保险。

### 2026-08-29 桌面端 mux 下链断线（bridge: `a387e37`）
- **现象**：桌面持有的审批/提问转发偶发中断。
- **修复**：mux 客户端指数退避重连（1s 起封顶 30s），重连后桌面端重放未决帧。

### 2026-08-29 构建/推送被系统代理拖垮（环境问题）
- **现象**：Gradle 下载依赖报 `Connect to 127.0.0.1:7897 failed`；git push 卡死。
- **根因**：macOS 系统代理指向未运行的本地代理（Clash），JDK/git 走了失效代理；
  其后 github.com 443 TLS 被网络过滤（api.github.com 正常、TCP 443 通但 TLS 挂）。
- **修复**：Gradle 用 `-Dhttp.proxyHost=` 空值绕过；git 推送改 SSH（`ssh.github.com:443`）。
- **经验**：环境代理失效时先查 `scutil --proxy`；GitHub 主域不通时可走
  `ssh.github.com:443`（Ed25519 指纹 `SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU`）。

## 排查速查

1. `curl http://127.0.0.1:3080/remote/health`（版本/存活）与 `/remote/connected`（谁在线）
2. `curl "http://127.0.0.1:3080/remote/logs?limit=100"`（服务端结构化日志）
3. `adb logcat -s dsh-conn:V`（手机端镜像日志）或 App「📋 日志」页
4. `adb reverse --list`（USB 隧道是否还在）
5. 手机侧可达性：`adb shell` 后 `nc -z -w 3 127.0.0.1 3080`

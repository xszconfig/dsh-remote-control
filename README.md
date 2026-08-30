# dsh Remote Control

手机端 DeepSeek Harness 遥控器（Android，Kotlin Multiplatform + Compose Multiplatform）：连接桌面端 [`dsh-remote-control-bridge`](https://github.com/xszconfig/dsh-remote-control-bridge) 插件，实时查看会话、远程发指令、审批工具调用，把桌面 Agent 变成可随身带的遥控面板。

> 桌面端仓库：[dsh-remote-control-bridge](https://github.com/xszconfig/dsh-remote-control-bridge)

## 已具备功能

**连接与设备**

- **扫码连接**：相机扫码（ZXing），支持 bridge 配对 JSON 二维码与裸 `ws://` 地址，多候选地址自动回退
- **设备指纹与自动重连**：连接成功后自动注册设备（`register_device`），本地持久化 `host:port` + 长期 token + `serverId` 指纹；点击设备即自动连接
- **断线自动重连**：指数退避（1s→30s 封顶），重连期间保留会话数据与界面（顶部横幅提示、可取消）；凭据失效或鉴权错误自动熔断；断线恢复后审批请求不丢
- **设备列表在线探测**：每 12 秒探测 `/remote/ping`，显示 在线 / 离线 / 设备已更换；支持「忘记设备」

**会话与对话**

- **工作区侧边栏**：按工作区分组会话（与桌面端一致），支持 全部 / 各工作区 / 未分组 切换；子代理会话归组展示（`parentSessionId`）
- **实时对话**：消息气泡、工具调用折叠卡、工具结果（默认折叠一行、出错标红）、相对时间（kotlinx-datetime）、远程中断
- **Markdown 渲染**：Agent 正文按 CommonMark/GFM 渲染（开源库 mikepenz/multiplatform-markdown-renderer）——代码块、表格、标题、加粗/斜体、行内代码、列表、引用、链接；正文字号与普通消息一致
- **Code Diff**：Edit / Write 工具卡展开后按行渲染文件变更（LCS 行级 diff，删除行红底、新增行绿底，与 DSH Web 对齐），附 `+N −N` 统计
- **历史分页**：进会话先加载最新 300 条（按投影行），上滑自动翻更早历史页
- **思考流式**：thinking 过程一行实时刷新（100ms 节流推送），与 DeepSeek Web 体验对齐
- **Deep Diving 指示**：模型等待时在排队面板上方显示本轮总耗时（跨多次模型调用不重置，DeepSeek 品牌蓝）
- **排队消息面板**：运行中发出的新消息进入队列实时展示（乐观显示 + 服务端同步纠正），支持插队 / 删除
- **长按复制**：消息气泡 / 思考 / 工具卡 / 结果卡长按复制到剪贴板（带触感反馈）
- **草稿持久化**：未发送的输入自动落盘，断线/切会话/重启不丢

**审批与交互**

- **远程审批**：工具审批请求实时弹窗（「允许一次 / 拒绝」，类型安全枚举，wire 值与 bridge 一致）；桌面端持有的审批同样可裁决（`answer_approval`）
- **提问透传**：桌面端多选提问在手机上作答回传（`answer_question`）
- **发送可靠性**：所有指令发送失败即时提示，不静默丢弃；错误横幅可查看/关闭
- **输入体验**：输入框常显蓝色边框（聚焦全亮、未聚焦半透明）；发送后先清焦点再收键盘（不闪烁）

## 架构

```
commonMain/
  App.kt               # UI：设备首页、工作区侧边栏、会话列表、聊天（气泡/Markdown/Diff/队列面板/Deep Diving/思考流）、审批/提问弹窗、重连横幅
  BridgeClient.kt      # 编排层：连接策略、协议事件归约到 SessionUiState、发送/翻页/队列/审批命令、乐观显示
  ConnectionManager.kt # 单条 WS 连接生命周期：握手、Bearer 头、事件解码 SharedFlow、发送
  DiffView.kt          # LCS 行级 diff 渲染（红删绿增）
  DeviceRepository.kt  # 设备资产：持久化编排、12s 在线探测轮询
  DeviceStore.kt       # 设备持久化接口（原子 update）
  Pairing.kt           # 配对/URL 解析纯函数（parseQr / buildUrl / endpointOf）
  TimeFormat.kt        # 时间格式化（kotlinx-datetime）
  Theme.kt             # 深色主题（DeepSeek 品牌蓝等）
  EventCache.kt / SessionCache.kt / DraftCache.kt   # 事件窗口/会话列表/输入草稿的磁盘缓存
  ConnLog.kt           # 连接日志环形缓冲（桌面端 /remote/phone-logs 可拉取）
  protocol/            # 与 bridge 镜像的 JSON 协议（SessionUiState/事件/命令/设备）
androidMain/
  MainActivity.kt
  Platform.android.kt   # Ktor OkHttp 客户端 + ZXing 扫码
  DeviceStore.android.kt# JSON 文件持久化（Mutex 串行 + 临时文件原子写）
commonTest / androidUnitTest   # 53 个用例
```

- 技术栈：Kotlin 2.1 · Compose Multiplatform 1.7.3 · Ktor 3（WebSocket）· kotlinx.serialization · kotlinx-datetime · ZXing · multiplatform-markdown-renderer 0.28（CommonMark）
- **单向数据流**：会话/队列/思考流全部以服务端投影为唯一事实源，客户端只做乐观显示 + 接收广播纠正
- 连接方式：桌面端 dsh 出于安全只监听 `127.0.0.1`，手机经 USB `adb reverse tcp:3080 tcp:3080`、SSH 隧道或 Tailscale（`tailscale serve`）访问
- 内存防护：单会话事件上限 500 条、错误提示上限 20 条；探测落盘 10 分钟节流

## 构建与安装

```bash
# 需要 JDK 17+ 与 Android SDK（local.properties 指向 sdk.dir）
export JAVA_HOME=$(/usr/libexec/java_home -v 17)  # 或任意可用 JDK
./gradlew :composeApp:assembleDebug
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

> 若 Gradle 下载依赖时误走系统代理（macOS 系统代理指向未运行的本地代理时），可加：
> `-Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost= -Djava.net.useSystemProxies=false`

## 测试

```bash
./gradlew :composeApp:testDebugUnitTest   # 53 个用例：协议/配对/时间/状态归约/存储并发/发送可见性/退避
```

端到端验证可用 `tools/mock-bridge`（Node 实现的 bridge 协议子集模拟器，含鉴权日志与设备凭据持久化）：

```bash
cd tools/mock-bridge && npm install
PORT=3081 DSH_MOCK_TOKEN=<token> node mock-bridge.mjs
adb reverse tcp:3081 tcp:3081   # 模拟器内 127.0.0.1:3081 即可访问
```

验收截图见 `docs/screenshots/`。

## 使用流程

1. 桌面端启动 `dsh web`，浏览器侧边栏点 📱「连接移动端」，弹出配对二维码
2. 手机 App「扫码连接」扫描二维码
3. 配对成功后设备自动进入首页设备列表（在线状态实时探测）
4. 之后点击设备即可自动重连；无需再扫码。断线后 App 自动重连并保留会话上下文

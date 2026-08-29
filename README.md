# dsh Remote Control

手机端 DeepSeek Harness 遥控器（Android）：连接桌面端 [`dsh-remote-control-bridge`](https://github.com/xszconfig/dsh-remote-control-bridge) 插件，实时查看会话、远程发指令、审批工具调用。

> 桌面端仓库：[dsh-remote-control-bridge](https://github.com/xszconfig/dsh-remote-control-bridge)

## 已具备功能

- **深色主题 UI**：设备首页、会话列表、聊天详情、审批弹窗（Kotlin Multiplatform + Compose Multiplatform）
- **扫码连接**：相机扫码（ZXing），支持 bridge 配对 JSON 二维码与裸 `ws://` 地址，多候选地址自动回退
- **设备指纹与自动重连**：连接成功后自动向桌面注册设备（`register_device`），本地持久化 `host:port` + 长期 token + `serverId` 指纹；点击设备即自动连接
- **断线自动重连**：指数退避（1s→30s 封顶），重连期间保留会话数据与界面（顶部横幅提示、可取消）；凭据失效或鉴权错误自动熔断。断线恢复后审批请求不丢
- **设备列表在线探测**：每 12 秒探测 `/remote/ping`，显示 在线 / 离线 / 设备已更换；支持「忘记设备」（撤销桌面端凭据）
- **工作区侧边栏**：侧边栏菜单按工作区分组会话（与桌面端一致），支持 全部 / 各工作区 / 未分组 切换；断开重连后保持所选视图
- **会话名称**：显示桌面端同款名称（持久化标题 → cwd basename），标题变更实时推送（`session_title`）
- **实时对话**：消息气泡、工具调用折叠卡（参数展开）、工具结果（出错标红）、相对时间（kotlinx-datetime，正确处理时区/夏令时）、远程中断
- **远程审批**：桌面端工具审批请求实时弹窗，「允许一次 / 拒绝」（类型安全枚举，wire 值与 bridge 一致）
- **发送可靠性**：所有指令发送失败（未连接/通道关闭）即时提示，不再静默丢弃；错误横幅可查看/关闭
- **协议兼容**：kotlinx.serialization 协议与 bridge 镜像，对旧版本 bridge 完全兼容；长期 token 经 `Authorization: Bearer` 头传递（不进 URL）

## 架构

```
commonMain/
  App.kt               # UI（深色主题、设备首页、工作区侧边栏、会话、聊天、审批、错误/重连横幅）
  BridgeClient.kt      # 编排层：连接策略（扫码回退/自动重连）、协议事件归约到 SessionUiState
  ConnectionManager.kt # 单条 WS 连接生命周期：握手、Bearer 头、事件解码 SharedFlow、发送
  DeviceRepository.kt  # 设备资产：持久化编排、12s 在线探测轮询（纯函数 applyPingResults 合并）
  DeviceStore.kt       # 设备持久化接口（原子 update）
  Pairing.kt           # 配对/URL 解析纯函数（parseQr / buildUrl / endpointOf）
  TimeFormat.kt        # 时间格式化（kotlinx-datetime）
  protocol/            # 与 bridge 镜像的 JSON 协议（含 ApprovalDecision 枚举）
androidMain/
  MainActivity.kt
  Platform.android.kt   # Ktor OkHttp 客户端 + ZXing 扫码
  DeviceStore.android.kt# JSON 文件持久化（Mutex 串行 + 临时文件原子写）
commonTest / androidUnitTest
  ProtocolTest / PairingTest / TimeFormatTest
  SessionUiStateTest / BridgeClientTest / DeviceStoreTest   # 共 49 个用例
```

- 技术栈：Kotlin 2.1 · Compose Multiplatform 1.7.3 · Ktor 3（WebSocket）· kotlinx.serialization · kotlinx-datetime · ZXing
- 连接方式：桌面端 dsh 出于安全只监听 `127.0.0.1`，手机经 USB `adb reverse tcp:3080 tcp:3080`（或 SSH 隧道）访问
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
./gradlew :composeApp:testDebugUnitTest   # 49 个用例：协议/配对/时间/状态归约/存储并发/发送可见性/退避
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

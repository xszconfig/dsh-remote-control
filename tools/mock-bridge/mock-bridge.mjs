#!/usr/bin/env node
/**
 * DSH Remote Control 手机端的模拟桌面端 bridge。
 * 用途：无真机桌面环境时在本地做端到端验证 / 截图。
 *
 * 实现的协议子集（与 xszconfig/dsh-remote-control-bridge 的 src/index.ts 对齐）：
 * - GET  /remote/ping            → PingInfo（在线探测）
 * - GET  /remote/pair-info       → 配对二维码 JSON（PairQrPayload）
 * - WS   /remote/ws              → 鉴权（Bearer 头 / ?pair= / ?token=），
 *                                  hello / list / subscribe / history / event /
 *                                  agent_status / session_title / approval_request /
 *                                  approval_settled / device_registered / error
 *
 * 鉴权记录会输出到 stderr，格式：AUTH <method> <verdict> —— 用于验证
 * 手机端 token 是否按预期走 Authorization 头。
 */
import { WebSocketServer } from 'ws'
import http from 'node:http'
import crypto from 'node:crypto'
import fs from 'node:fs'

const PORT = Number(process.env.PORT || 3080)
const ENV_TOKEN = process.env.DSH_MOCK_TOKEN || 'mock-env-token-123'
const SERVER_ID = process.env.DSH_MOCK_SERVER_ID || 'mock-server-1'
const HOSTNAME = 'mock-mac'

const sessions = [
  { id: 'sess-alpha', name: '重构支付模块', workspaceId: 'ws-pay', cwd: '/Users/dev/code/payment', status: 'running', agentCount: 1, subagentCount: 2, updatedAt: Date.now() - 60_000 },
  { id: 'sess-beta', name: null, workspaceId: 'ws-pay', cwd: '/Users/dev/code/payment/web', status: 'idle', agentCount: 1, subagentCount: 0, updatedAt: Date.now() - 3_600_000 },
  { id: 'sess-gamma', name: null, workspaceId: null, cwd: '/tmp/scratch', status: 'idle', agentCount: 1, subagentCount: 0, updatedAt: Date.now() - 86_400_000 },
]
const workspaces = [
  { id: 'ws-pay', title: 'payment', path: '/Users/dev/code/payment', sessionCount: 2 },
  { id: 'ws-tool', title: 'tools', path: '/Users/dev/code/tools', sessionCount: 0 },
]
const devices = new Map() // token -> {deviceId, name, model, lastSeenAt}
// 与真实 bridge 一致：设备凭据持久化（重启不失效）
const DEVICES_FILE = new URL('./.mock-devices.json', import.meta.url)
function loadDevices() {
  try {
    const arr = JSON.parse(fs.readFileSync(DEVICES_FILE, 'utf8'))
    for (const [t, d] of arr) devices.set(t, d)
    console.error(`loaded ${devices.size} persisted device(s)`)
  } catch { /* 首次运行无文件 */ }
}
function persistDevices() {
  try {
    fs.writeFileSync(DEVICES_FILE, JSON.stringify([...devices]))
  } catch (e) { console.error('persist devices failed:', e.message) }
}
loadDevices()
const sessionEvents = {
  'sess-alpha': [
    { seq: 1, type: 'user_message', text: '把退款逻辑补上单测', timestamp: Date.now() - 300_000 },
    { seq: 2, type: 'assistant_message', text: '好的，我先看一下现有 refund 模块的结构。', timestamp: Date.now() - 295_000 },
    { seq: 3, type: 'tool_call', toolName: 'bash', toolArgs: '{"cmd":"rg -n refund src/"}', timestamp: Date.now() - 290_000 },
    { seq: 4, type: 'tool_result', toolResult: 'src/refund.ts:12\nsrc/refund.test.ts:40', toolError: false, timestamp: Date.now() - 288_000 },
    { seq: 5, type: 'assistant_message', text: '结构清楚了，接下来写单测。', timestamp: Date.now() - 200_000 },
  ],
  'sess-beta': [
    { seq: 1, type: 'user_message', text: 'web 目录是干嘛的', timestamp: Date.now() - 4_000_000 },
    { seq: 2, type: 'assistant_message', text: '这是收银台前端。', timestamp: Date.now() - 3_900_000 },
  ],
  'sess-gamma': [],
}
// 结果交付台账（模拟 bridge 0.15.0）：未确认投递的交付通知，重连经 hello.pendingDeliveries 补发；
// 手机回 confirm_delivery 后按 (sessionId, turnKey) 删除。
const pendingDeliveries = [
  { sessionId: 'sess-alpha', turnKey: 'mock-turn-0001', title: '结果已就绪', body: '「重构支付模块」本轮已完成', isSubagent: false, completedAt: Date.now() - 120_000 },
]
const pairTokens = new Map() // token -> expiry
function newPairToken() {
  const t = crypto.randomBytes(16).toString('hex')
  pairTokens.set(t, Date.now() + 10 * 60_000)
  return t
}
setInterval(() => {
  const now = Date.now()
  for (const [t, exp] of pairTokens) if (exp < now) pairTokens.delete(t)
}, 60_000).unref()

// ---- 可配置弱网注入（模拟好/中/差网络，测消息发送鲁棒性）----
// 控制接口：POST /remote/net-condition {profile: 好|中|差} 或 {latencyMs,jitterMs,lossRate,bandwidthBps}
// 注入点：mock 服务端 → 客户端的下行帧（丢包/延迟/抖动/限速），用于验证客户端 ack 超时/重发/回显去重。
const NET_PRESETS = {
  好: { latencyMs: 0, jitterMs: 0, lossRate: 0, bandwidthBps: Infinity },
  中: { latencyMs: 100, jitterMs: 30, lossRate: 0.02, bandwidthBps: 10 * 1024 * 1024 }, // 100ms / 2% / 10Mbps
  差: { latencyMs: 400, jitterMs: 100, lossRate: 0.10, bandwidthBps: 1 * 1024 * 1024 }, // 400ms / 10% / 1Mbps
}
let netProfile = '好'
let netCondition = { ...NET_PRESETS.好 }
// token bucket 限速：每连接的令牌（token = bit）
const wsTokens = new WeakMap()

function send(ws, obj) {
  if (ws.readyState !== 1) return
  const cond = netCondition
  // 1) 丢包
  if (cond.lossRate > 0 && Math.random() < cond.lossRate) {
    console.error(`NET drop ↓ type=${obj.type} (lossRate=${cond.lossRate})`)
    return
  }
  const payload = JSON.stringify(obj)
  const deliver = () => {
    if (ws.readyState !== 1) return
    // 2) 限速（token bucket，bandwidthBps 为 Infinity 时跳过）
    if (Number.isFinite(cond.bandwidthBps)) {
      let t = wsTokens.get(ws)
      if (!t) { t = { tokens: cond.bandwidthBps, last: Date.now() }; wsTokens.set(ws, t) }
      const now = Date.now()
      t.tokens = Math.min(cond.bandwidthBps, t.tokens + ((now - t.last) / 1000) * cond.bandwidthBps)
      t.last = now
      const sizeBits = payload.length * 8
      if (t.tokens < sizeBits) {
        const waitMs = ((sizeBits - t.tokens) / cond.bandwidthBps) * 1000
        t.tokens = 0
        setTimeout(() => { if (ws.readyState === 1) ws.send(payload) }, waitMs)
        return
      }
      t.tokens -= sizeBits
    }
    ws.send(payload)
  }
  // 3) 延迟 + 抖动（jitter 为均匀分布在 ±jitterMs 内的抖动）
  let delay = cond.latencyMs
  if (cond.jitterMs > 0) delay += (Math.random() * 2 - 1) * cond.jitterMs
  delay = Math.max(0, delay)
  if (delay > 0) setTimeout(deliver, delay)
  else deliver()
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`)
  if (url.pathname === '/remote/ping') {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ ok: true, version: 'mock-1.0', serverId: SERVER_ID, hostname: HOSTNAME, sessions: sessions.length }))
    return
  }
  if (url.pathname === '/remote/pair-info') {
    const pair = newPairToken()
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({
      v: 1, t: 'dsh-remote', serverId: SERVER_ID, hostname: HOSTNAME,
      expiresAt: Date.now() + 600_000,
      urls: [`ws://127.0.0.1:${PORT}/remote/ws?pair=${pair}`],
    }))
    return
  }
  if (url.pathname === '/remote/net-condition') {
    if (req.method === 'GET') {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ ok: true, profile: netProfile, condition: netCondition }))
      return
    }
    if (req.method === 'POST') {
      let body = ''
      req.on('data', (c) => { body += c.toString() })
      req.on('end', () => {
        let parsed
        try { parsed = JSON.parse(body) } catch {
          res.writeHead(400, { 'content-type': 'application/json' })
          res.end(JSON.stringify({ error: 'bad json' }))
          return
        }
        if (parsed.profile !== undefined && NET_PRESETS[parsed.profile] !== undefined) {
          netProfile = parsed.profile
          netCondition = { ...NET_PRESETS[parsed.profile] }
        } else if (parsed.latencyMs !== undefined || parsed.jitterMs !== undefined || parsed.lossRate !== undefined || parsed.bandwidthBps !== undefined) {
          netProfile = '自定义'
          netCondition = {
            latencyMs: parsed.latencyMs !== undefined ? Number(parsed.latencyMs) : netCondition.latencyMs,
            jitterMs: parsed.jitterMs !== undefined ? Number(parsed.jitterMs) : netCondition.jitterMs,
            lossRate: parsed.lossRate !== undefined ? Number(parsed.lossRate) : netCondition.lossRate,
            bandwidthBps: parsed.bandwidthBps !== undefined ? Number(parsed.bandwidthBps) : netCondition.bandwidthBps,
          }
        } else {
          res.writeHead(400, { 'content-type': 'application/json' })
          res.end(JSON.stringify({ error: 'need profile(好|中|差) or custom params(latencyMs/jitterMs/lossRate/bandwidthBps)' }))
          return
        }
        console.error(`NET condition → profile=${netProfile} ${JSON.stringify(netCondition)}`)
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ ok: true, profile: netProfile, condition: netCondition }))
      })
      return
    }
    res.writeHead(405, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ error: 'use GET or POST' }))
    return
  }
  res.writeHead(404).end()
})

const wss = new WebSocketServer({ noServer: true })
server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, `http://localhost:${PORT}`)
  if (url.pathname !== '/remote/ws') return socket.destroy()

  const qPair = url.searchParams.get('pair')
  const qToken = url.searchParams.get('token')
  const auth = req.headers.authorization
  const bearer = auth?.startsWith('Bearer ') ? auth.slice(7) : null

  let verdict = 'deny'
  let method = 'none'
  if (bearer === ENV_TOKEN) { verdict = 'env'; method = 'bearer-header' }
  else if (qToken === ENV_TOKEN) { verdict = 'env'; method = 'query-token' }
  else if (qPair && pairTokens.has(qPair)) { verdict = 'pair'; method = 'query-pair' }
  else if (bearer && [...devices.keys()].includes(bearer)) { verdict = 'device'; method = 'bearer-header' }
  else if (qToken && devices.has(qToken)) { verdict = 'device'; method = 'query-token' }

  console.error(`AUTH ${method} ${verdict} url=${url.pathname}${url.search ? ' HAS-QUERY-CRED' : ''}`)
  if (verdict === 'deny') {
    socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n')
    socket.destroy()
    return
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.on('message', (data) => {
      let cmd
      try { cmd = JSON.parse(data.toString()) } catch { return }
      console.error(`CMD ${cmd.type} ${JSON.stringify(cmd).slice(0, 160)}`)
      switch (cmd.type) {
        case 'list':
        case 'subscribe': {
          if (cmd.type === 'subscribe' && cmd.sessionId) {
            const s = sessions.find((x) => x.id === cmd.sessionId)
            if (!s) { send(ws, { type: 'error', code: 'not_found', message: `session not found: ${cmd.sessionId}` }); break }
            send(ws, { type: 'history', sessionId: s.id, events: sessionEvents[s.id] || [] })
          }
          break
        }
        case 'ping': {
          // 应用层心跳：与真实 bridge 0.14.0 对齐，客户端 ping → 服务端 pong
          send(ws, { type: 'pong' })
          break
        }
        case 'send_message': {
          const evts = sessionEvents[cmd.sessionId] || (sessionEvents[cmd.sessionId] = [])
          const seq = evts.length + 1
          evts.push({ seq, type: 'user_message', text: cmd.text, timestamp: Date.now() })
          // 送达确认：携带 msgId 才回 ack（对齐真实 bridge 0.14.0 语义）
          if (cmd.msgId !== undefined) send(ws, { type: 'ack', msgId: cmd.msgId, ok: true })
          send(ws, { type: 'event', sessionId: cmd.sessionId, event: evts[evts.length - 1] })
          setTimeout(() => {
            evts.push({ seq: seq + 1, type: 'assistant_message', text: `（mock 回复）收到：${cmd.text}`, timestamp: Date.now() })
            send(ws, { type: 'event', sessionId: cmd.sessionId, event: evts[evts.length - 1] })
          }, 800)
          break
        }
        case 'interrupt': {
          send(ws, { type: 'agent_status', sessionId: cmd.sessionId, status: 'idle' })
          break
        }
        case 'approve': {
          send(ws, { type: 'approval_settled', approvalId: cmd.approvalId, outcome: cmd.decision })
          break
        }
        case 'confirm_delivery': {
          let removed = 0
          for (const d of cmd.deliveries || []) {
            const i = pendingDeliveries.findIndex((p) => p.sessionId === d.sessionId && p.turnKey === d.turnKey)
            if (i >= 0) { pendingDeliveries.splice(i, 1); removed++ }
          }
          console.error(`CONFIRM_DELIVERY removed=${removed} remaining=${pendingDeliveries.length}`)
          break
        }
        case 'register_device': {
          const token = crypto.randomBytes(16).toString('hex')
          devices.set(token, { deviceId: cmd.deviceId, name: cmd.name, model: cmd.model, lastSeenAt: Date.now() })
          persistDevices()
          console.error(`REGISTERED device=${cmd.deviceId} name=${cmd.name} tokenIssued=${token.slice(0, 8)}…`)
          send(ws, { type: 'device_registered', deviceId: cmd.deviceId, deviceToken: token, serverId: SERVER_ID, hostname: HOSTNAME })
          break
        }
        case 'revoke_device': {
          for (const [t, d] of devices) if (d.deviceId === cmd.deviceId) devices.delete(t)
          persistDevices()
          send(ws, { type: 'device_revoked', deviceId: cmd.deviceId })
          break
        }
        default:
          send(ws, { type: 'error', code: 'bad_command', message: `unknown: ${cmd.type}` })
      }
    })
    ws.on('close', () => console.error('CLOSED'))

    send(ws, {
      type: 'hello', version: 'mock-1.0', serverId: SERVER_ID, hostname: HOSTNAME,
      sessions, agents: sessions.map((s) => ({ sessionId: s.id, role: 'main', status: s.status, depth: 0 })),
      workspaces,
      pendingDeliveries,
    })
    // 5s 后推送一次审批请求 + 标题更新，供 UI 验证
    setTimeout(() => {
      send(ws, {
        type: 'approval_request',
        approval: { approvalId: 'appr-1', sessionId: 'sess-alpha', toolName: 'bash', reason: 'rm -rf /tmp/scratch && 重建测试目录' },
      })
    }, 5_000)
    setTimeout(() => {
      send(ws, { type: 'session_title', sessionId: 'sess-beta', title: '收银台联调' })
    }, 7_000)
    // 9s 后推送一次结果交付通知（实时 delivery_notice 事件），供通知补投递验证
    setTimeout(() => {
      send(ws, {
        type: 'delivery_notice',
        notice: { sessionId: 'sess-beta', turnKey: 'mock-turn-0002', title: '结果已就绪', body: '「收银台联调」本轮已完成', isSubagent: false, completedAt: Date.now() },
      })
    }, 9_000)
  })
})

server.listen(PORT, '127.0.0.1', () => {
  console.error(`mock bridge listening on ws://127.0.0.1:${PORT}/remote/ws`)
  console.error(`env token: ${ENV_TOKEN}`)
})

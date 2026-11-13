# DSH 协议笔记（2026-11-13 全链路实测通过，DSH 0.2.2-rc.1）

> 这份文档只记录**有证据的**东西，并区分"已验证 / 源码文档 / 未知"。
> 2026-11-13 的突破：在**隔离实例**上拿到了 launch token，把整条链路跑通了。

## 1. 结论速览

| 项 | 状态 |
|---|---|
| `/connect?token=` 换 API Cookie | ✅ 实测通过 |
| mux WebSocket `/api/remote.mux` | ✅ 握手通过（承载 **stream**） |
| unary RPC（HTTP POST `/api/<endpoint>`） | ✅ **实测调通** `session/list`、`session/modelCatalog` |
| mux stream（订阅 `$events`） | ✅ **实测收到 item 帧**（JS 与 Java 两个实现都收到） |
| mux 帧格式 | ✅ 从服务端源码确认并验证 |
| stream 帧的取消/回放语义 | ⏳ 尚未验证（M3 才需要） |

**关键架构认知（之前搞错过）**：
- **unary 方法走 HTTP，不走 WebSocket**。对 unary 方法发 mux 的 open 帧会得到
  `unary Remote methods cannot be opened through the stream carrier`。
- **mux WebSocket 只承载 stream**（订阅、事件流）。
- 源码原话：`Unary transport and response envelopes belong to Connection; live Remote streams use the Gateway-owned WebSocket mux.`

## 2. 完整链路

```
① 拿凭证（HTTP）
   GET /connect?token=<launchToken>&interactions=answer|none
   ◄── 302 + Set-Cookie: dsh-auth-…（签名 API Cookie） + Location: "./"

② unary 调用（HTTP POST）  ← 列表、状态、模型目录这类一次性查询
   POST /api/<namespace/method>
   content-type: application/json
   { "type":"client-request", "rpcId":"<uuid>", "method":"<namespace/method>",
     "payload": { "args": { ...参数... } } }
   ◄── { "type":"server-response", "rpcId":"...",
         "result": { "ok":true, "value": ... } }
       | { "result": { "ok":false, "error": { "code","message","details" } } }

③ stream 订阅（WebSocket）  ← 事件流、实时更新
   WS /api/remote.mux   （带同一个 API Cookie）
```

## 3. unary 信封（两个必踩的坑，都实测过）

```jsonc
// 坑 1：payload 必须恰好含一个 args 字段
{ "payload": { "args": { ... } } }        // ✅
{ "payload": { ... } }                     // ❌ "Remote payload must contain exactly one plain-object args field"

// 坑 2：参数名按端点 descriptor 的 parameters，不是空对象就好
session/list          → payload.args = { "_request": {} }   // 可选 _request.cursor
session/modelCatalog  → payload.args = {}
```

参数错时报 `gateway/arguments-invalid`，消息会明确指出缺哪个字段
（例如 `args fields do not match the descriptor: missing "_request"`）。

## 4. mux 帧格式（已验证）

```
客户端 → 主机：
  { type:"open",   streamId, endpoint, payload }   payload = { args }
  { type:"item",   streamId, value }
  { type:"end",    streamId }
  { type:"cancel", streamId }

主机 → 客户端（服务端用 exactKeys 严格校验，多一个字段都非法）：
  { type:"item",  streamId [, value] }
  { type:"end",   streamId }
  { type:"error", streamId, error:{ code, message, details } }
```

所有调用都走同一条流：`open` → 若干 `item` → `end` / `error`。

## 5. 认证矩阵

| 方式 | 凭证来源 | 能力 | 证据 |
|---|---|---|---|
| **A 基础** | 无 | 只有 WebView 官方 UI | 裸访问一律 401 |
| **B API** | `dsh web` 打印的 `?token=` | **完整 RPC**（unary + mux） | ✅ 本次实测 |
| **C 插件** | 第三方插件配对二维码 | 设备通道 + `/pair-app` shell | 实测到设备门（403 fail-closed） |

关键事实：
- **launch token 每进程随机**，只打印在 stdout，磁盘/环境变量/浏览器历史都没有 → 第三方客户端必须由用户提供。
- **`/api/*` 整片鉴权**：不存在的 `/api/zzz-x` 也是 401；只有非 `/api` 的未知路径才 404。
  所以**不能用状态码判断路由是否存在**。
- 配对插件的设备身份走 `x-dsh-remote-device` header，且对 loopback 短路。

## 6. 已实现（两个语言、同一份认知）

| 实现 | 文件 | 状态 |
|---|---|---|
| JS | `protocol/dsh-protocol.mjs`（preflight / exchange / callUnaryHttp / openMux / collectStream） | ✅ 对真实实例验证 |
| CLI | `protocol/probe.mjs`（含 `--stream`） | ✅ 对真实实例验证 |
| Java | `Credential` / `Capabilities` / `ApiCookieExchanger` / `UnaryRpc` / `DshClient` / `WebSocketClient` / `ObserveEventStream` / `Json` / `SessionSummary` / `SessionProjection` / `SessionEvent` / `TranscriptBuilder` / `SessionTranscript` | ✅ `LiveSelfTest` 对真实实例验证 |
| 测试 | `SelfTest`（**74 项**纯逻辑）、`WsSelfTest`（RFC6455 对回显服务器）、`LiveSelfTest`（真实实例，含 mux stream） | ✅ |

## 7. 仍未验证

| 项 | 影响 | 怎么验证 |
|---|---|---|
| 会话级事件流（`session/observeEvent`）的 value 形状 | M3 原生聊天需要 | 先 `session/create` 造一个会话，再用 mux open 帧订阅它 |
| `session/prompt` 的多参数形状 | 发消息需要 | 按 descriptor 的 parameters 逐个填 |
| 附件上传（`/api/session/uploadFileBinary`） | 传文件需要 | 尚未涉及 |

### 已验证的 stream 细节（2026-11-13）

订阅网关内部事件流 `$events`（`payload.args = {}`），收到：

```json
{"type":"item","streamId":"…","value":{"type":"ready","clientId":"…","host":{"home":"C:\\Users\\kangf"}}}
```

- `ready` 正是源码里的 `REMOTE_EVENT_STREAM_READY`（"第一项证明 Host 事件源就绪"）
- 说明：`open` 帧 → 服务端回 `item` 帧这条路是通的，JS 探针和 Java 自写 WebSocket 都验证过
- 会话级事件流只差"有个会话"这一步，帧封装和上面完全一样

## 8. 升级 DSH 后怎么自检

```powershell
# 纯逻辑 + WebSocket 客户端
powershell -ExecutionPolicy Bypass -File protocol/selftest.ps1

# 对真实实例做只读联调（B 模式）
node protocol/probe.mjs --token-url "http://127.0.0.1:3080/?token=xxxx"
java -cp build/protocol-selftest com.dshmobile.protocol.LiveSelfTest "http://127.0.0.1:3080/?token=xxxx"
```

## 9. 会话数据模型（M3 原生聊天的基础，2026-11-13 实测）

三个端点拼出完整画面：

### 9.1 `session/projections` —— UI 状态（unary）

```jsonc
// args: { request: { sessionId } }
{
  "kind": "sequenced",
  "asOfSeq": 2,
  "values": {
    "title": null,                    // 会话标题（列表页那个）
    "agentPreset": "standard",
    "workingDirectory": "E:\\dsh-dev-home\\ws",
    "sessionStats": { "turns": 0, "steps": 0, "llmMs": 0, "toolMs": 0, "ttftMs": 0, ... },
    "tokenUsage": { "uncachedInputTokens": 0, "outputTokens": 0, "cacheReadTokens": 0, "cacheWriteTokens": 0 },
    "contextBreakdown": { "systemTokens": 0, "toolsTokens": 0, "messageTokens": 0 },
    "contextPressure": {},
    "modelSelection": { "lastUsed": null, "next": null },
    "permissions": { "currentValue": "danger-full-access" },
    "turnOutline": [],               // 轮次大纲 —— 原生 UI 的"对话列表"骨架
    "userQuestions": { "active": [], "settled": [] },
    "inbox": { "next-turn": [], "next-step": [] },
    "subagentCatalog": [],
    "goal": null
  }
}
```

**这就是原生 UI 的数据源**：标题、用量、上下文压力、权限、模型选择、工作目录都在里面。

### 9.2 `session/page` —— 历史事件分页（unary）

```jsonc
// args: { request: { address: { kind: "session", sessionId } } }   ← 注意是 address，不是 sessionId
{
  "cursor": 2,
  "hasMore": false,
  "records": [
    { "type": "event", "event": { "type": "permission/preset", "seq": 0, "time": 1794558651534,
                                  "data": { "preset": "danger-full-access" } } },
    { "type": "event", "event": { "type": "sandbox/mode", "seq": 1, "time": ..., "data": { "mode": ... } } },
    { "type": "event", "event": { "type": "approval/policy", "seq": 2, "time": ..., "data": { "policy": "never" } } }
  ]
}
```

**关键认知：DSH 的会话历史是一条"带序号的事件日志"**，不是消息数组。
每条记录 `{ type, event: { type, seq, time, data } }`，消息、工具调用、权限变更都是不同的事件类型。
客户端要做的是：读 `session/page` 回填历史 → 用 `session/observeEvent` 追增量 → 折叠成 UI 状态。

### 9.3 `session/observeEvent` —— 增量事件（stream）

```jsonc
// args: { request: { address: { kind: "session", sessionId } } }
// 首帧一定是就绪标记（对应源码 REMOTE_EVENT_STREAM_READY）
{ "type": "item", "streamId": "…", "value": { "type": "ready" } }
```

### 9.4 仍未验证：真实对话的事件形状

> ✅ **2026-11-13 已突破**：见下面的 §10 —— 即使隔离实例没有模型凭据，
> `session/prompt` 仍会被接受（返回 `{"accepted":true}`），用户消息与失败原因都会落成事件。
> 所以拿事件形状**不需要花任何额度**。

## 10. 事件词汇表与对话折叠（实测，M3 的基础）

### 10.1 怎么在不花额度的前提下抓到真实事件

1. 起隔离实例（`tools/dev-instance.ps1`）
2. `session/create` 造会话
3. **先**订阅 `session/observeEvent`（否则会错过首帧）
4. 发 `session/prompt`：`{ request: { requestId, sessionId, mode:"queue", content:[{type:"text",text:"…"}] } }`
5. 模型因缺凭据失败，但 `user/message` / `assistant/attempt` / `turn/end` 都已落盘

工具：`protocol/test/m3-message.mjs`

### 10.2 实测到的事件类型

| 事件 | data 结构 |
|---|---|
| `user/message` | `{ content:[{type:"text",text}], source:{kind:"user",rpcId}, role:"user", id }` + 记录级 `surfaceOp:"append"` |
| `system/message` | `{ turn, step, message:{role:"system", content:[…]} }`（很长，UI 不该渲染） |
| `assistant/attempt` | `{ turn, step, stream:[{type:"chunk",chunk:{type:"finish",reason:{kind:"error",failure:{message,code}}}}] }` |
| `turn/start` | `{ turn }` |
| `step/start` / `step/end` | `{ turn, step }` |
| `turn/end` | `{ turn, reason:{kind:"error", error:{message,code}} }` |
| `session/title` | `{ title, messageSeqs, source:{kind:"fallback"} }` ← **标题就来自这里** |
| `agent/inbox/spliced` | `{ target, start, inserted:[…] }`（用户输入入队，流里比 user/message 更早出现） |
| `request/header` / `request/context` | LLM 请求元信息（model/provider/tools） |
| `permission/preset` / `sandbox/mode` / `approval/policy` | 会话策略，seq 0-2 |

**工具调用**（形状来自官方 bundle 的渲染代码，事件本身在无模型适配器时不会产生）：

| 事件 | data 结构 |
|---|---|
| `tool/call` | `{ turn, callId, name, arguments }` —— 注意 **arguments 是 JSON 字符串**，不是对象 |
| `tool/result` | `{ message: { toolCallId, isError } }` |
| `tool/code-dispatch(-start)` / `tool/ptc-dispatch(-start)` | 代码执行/ptc 派发的旁路事件 |

**完整事件词汇表**（从 bundle 扫出来的 134 个 `namespace/name` 字面量，去掉 MIME 噪声）：
`agent/*`（created/disposed/error/pre-step/request/status/assistant-stream）、
`assistant/*`（attempt/chunk/live/live-chunk/message）、`approval/*`、`goal/*`、
`permission/preset`、`request/*`、`sandbox/mode`、`schedule/*`、`session/*`（40 余个）、
`step/start|end`、`subagent/*`、`system/message`、`tool/*`、`turn/start|end`、`user/message`。

失败的 `turn/end` 长这样（无模型适配器时）：

```json
{"type":"turn/end","seq":20,"data":{"turn":1,
  "reason":{"kind":"error","error":{"message":"no adapter registered for provider \"dsh-opencode-go\"","code":"NO_ADAPTER"}}}}
```

### 10.3 折叠规则（已实现于 `SessionTranscript`）

| 事件 | 处理 |
|---|---|
| `session/title` | 取 title（last-wins） |
| `user/message` 且 `source.kind === "user"` | 渲染为用户条目 |
| `user/message` 但 `source.kind` 是其它（如 `context`） | **过滤**——DSH 把运行时上下文注入（记忆快照、工具清单）也记成 user/message，不过滤会在对话里冒出几 KB 上下文块 |
| `system/message` | 只计数，不渲染 |
| `assistant/attempt` → `finish.reason.kind=error` | 渲染为错误条目（带 code） |
| `assistant/message` | 正常回复（**未实测**，按 message 形状兜底） |
| `turn/end` 的 error | 渲染为错误条目，但**与上一条去重** |
| `tool/call` | 渲染为工具卡片（工具名 + 参数），状态 `pending` |
| `tool/result` | 按 `message.toolCallId` 回填对应卡片状态 `done`/`error` |

实测效果（隔离实例，21 条事件）：

```
折叠出 2 个对话条目（跳过 1 条系统消息）
  [user]  probe from LiveSelfTest 1794559798720
  [error] no adapter registered for provider "dsh-opencode-go"（NO_ADAPTER）
标题=probe from LiveSelfTest 1794559798720（来自 session/title 事件的 fallback 标题）
```

### 10.4 还差什么才能"像官方一样"

- `assistant/message` 的真实形状（需要一次成功回复；成功后 `assistant/attempt.stream` 里应是 text delta chunk）
- 工具调用事件（`tool/*`）的卡片渲染

> 隔离实例挡住的是"成功回复"这一种事件，不影响其余形状的获取；
> 想要那一种，需要给隔离实例配模型凭据（会消耗额度）或读一个已有对话的实例。

### 10.5 实时增量（已实现并验证）

订阅 `session/observeEvent` 后，事件会持续推送。客户端的正确做法：

```
首次：session/page 读历史  ─┐
      session/observeEvent 订阅 ─┴→ TranscriptBuilder.apply(...)  按 seq 去重保序
之后：observeEvent 每来一个事件 → apply([event]) → 只重渲染对话区
```

- 历史与推送会**重叠**（同一事件两边都有），所以必须按 `seq` 去重；`TranscriptBuilder` 保证这点
- `ready` 帧表示"事件源就绪"，收到后才算真正连上
- 实测（隔离实例）：订阅 → 发一条消息 → 实时收到 9 个事件
  `{agent/inbox/spliced=2, turn/start=1, step/start=1, user/message=2, assistant/attempt=1, step/end=1, turn/end=1}`

实现：`ObserveEventStream`（Java，基于自写的 `WebSocketClient`）+ `TranscriptBuilder`。
App 的会话详情页据此实现"打开即看历史、随后实时追加"，离开页面即断开连接。

## 11. 端点清单（实测，可重算）

数字不再手写 —— 直接从 DSH 产物的 typert spec 里提取：

```bash
node protocol/test/dsh-methods.mjs          # 人或脚本都能读
node protocol/test/dsh-methods.mjs --json   # 机器可读
```

2026-11-13（DSH 0.2.2-rc.1）实测：**90 个端点 / 13 个命名空间**

| 命名空间 | 端点数 |
|---|---|
| session | 25 |
| account | 11 |
| workspace | 11 |
| schedule | 9 |
| terminal | 8 |
| goals | 7 |
| settings | 5 |
| credentials / llm / job | 各 3 |
| commands / subagents | 各 2 |
| skills | 1 |

`session` 的 25 个（本项目用到的标 ✅）：

```
✅ session/list            ✅ session/page          ✅ session/projections
✅ session/state           ✅ session/prompt        ✅ session/observeEvent
✅ session/create          ✅ session/modelCatalog
   session/attachment      session/canOpenWorkspacePath
   session/cancel          session/control         session/events
   session/follow          session/fork            session/initializeDefaultModel
   session/observeProjections  session/observeState  session/openWorkspacePath
   session/rename          session/search          session/selectModel
   session/updateQueue     session/wait            session/workspacePathApplications
```

> 早前文档里"约 260 个方法、session 32 个"是**估算且错误的** —— 那个数字来自对 spec 里
> 所有 `"x/y": {` 形式的粗扫描，把非端点的嵌套键也算进去了。现在以脚本输出为准。

### 提取脚本踩的坑

asar header 里文件的 `size` / `offset` 可能是**字符串**（大数值序列化所致），
直接 `Number.isFinite()` 会判 false，导致"文件明明在却读不到"。必须先 `Number()` 再判断。

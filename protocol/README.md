# protocol/ — DSH 通信协议层

把"怎么和一台 DSH 说话"从 UI 里剥出来的那一层。目的是：**DSH 内部 RPC 随版本变化时，只改这里。**

## 目录

```
protocol/
├── dsh-protocol.mjs              协议实现（Node/ESM）：预检 / 换 Cookie / HTTP unary / mux stream
├── probe.mjs                     CLI 探针（只读，CI 可用）
├── package.json                  ESM + 可选 ws 依赖
├── selftest.ps1                  自检入口（逻辑套件 + WebSocket 套件）
├── test/
│   ├── ws-echo-server.mjs        本地 WebSocket 回显服务器（给自检用）
│   ├── dsh-methods.mjs           从 DSH 产物里数出真实的 RPC 端点清单
│   ├── m3-probe.mjs              建会话 → 状态 → 订阅事件流
│   ├── m3-shape.mjs              投影/分页形状 + 端点参数解析
│   └── m3-message.mjs            订阅事件流 + 发 prompt（抓真实事件形状）
├── java/com/dshmobile/protocol/  协议层的 Java 实现（App 用；纯 JDK，无 Android 依赖）
│   ├── Credential.java           凭证：A 模式 / B 模式、token 解析
│   ├── Capabilities.java         能力分级：能不能开原生功能
│   ├── ApiCookieExchanger.java   /connect?token= → 签名 API Cookie
│   ├── UnaryRpc.java             HTTP unary 调用（信封 + 错误码）
│   ├── DshClient.java            一站式：tokenUrl → 会话 → 各端点封装
│   ├── WebSocketClient.java      手写 RFC 6455 客户端（无 OkHttp）
│   ├── ObserveEventStream.java   session/observeEvent 订阅
│   ├── Json.java                 极简 JSON 解析 + 转义
│   ├── SessionSummary.java       session/list 的一行
│   ├── SessionProjection.java    session/projections 的 values
│   ├── SessionEvent.java         session/page 的一条事件
│   ├── TranscriptBuilder.java    事件 → 对话（增量、按 seq 去重）
│   ├── SessionTranscript.java    折叠结果（不可变快照）
│   ├── MarkdownLite.java         围栏代码块分块（渲染用，纯函数）
│   ├── FileName.java             下载文件名推断（RFC5987 / URL / MIME 兜底）
│   ├── InstanceRecord.java       实例列表的持久化编解码（容错读，会丢用户数据的地方）
│   ├── Diagnostics.java          401/403/404/502/530/连不上 的文案与下一步（App 与探针共用）
│   ├── SelfTest.java             逻辑自检（纯 JVM，断言数见运行输出）
│   ├── WsSelfTest.java           WebSocket 自检（对回显服务器）
│   └── LiveSelfTest.java         对真实实例的联调（只读 + 可选 create）
└── README.md
```

JS 与 Java 两个实现**共用同一份协议认知**；任一发现新事实就同时更新。
把协议层做成独立源码根目录（`protocol/java`）而不是塞进 `app/src`，是为了让它
可以被其它 JVM 项目复用，也便于在没有 Android SDK 的环境下单独编译测试。

## 一条命令验证协议层

```powershell
powershell -ExecutionPolicy Bypass -File protocol/selftest.ps1
```

三步：

1. 用**纯 JDK**（不加载 `android.jar`）编译协议层 —— 这是"protocol 与 app 分离"的硬证据；
2. `SelfTest`：断言覆盖（token 解析、A/B 判定、能力分级、JSON 解析/转义、Markdown 分块、诊断文案、实例持久化、
   会话列表/投影解析、事件折叠、增量合并去重、工具调用状态、prompt 参数构造）；
3. `WsSelfTest`：起本地回显服务器，验证手写 WebSocket 的握手/掩码/收发/关闭。

## 对真实实例联调（需要 token）

```powershell
# 起一个隔离的一次性 DSH，拿到 TOKEN_URL
powershell -ExecutionPolicy Bypass -File tools/dev-instance.ps1

# JS 探针（只读）
node protocol/probe.mjs --token-url "<TOKEN_URL>"

# Java 联调：cookie → unary → mux stream（加 create 才会写数据）
java -cp build/protocol-selftest com.dshmobile.protocol.LiveSelfTest "<TOKEN_URL>" create
```

## 目前确认了什么（DSH 0.2.2-rc.1 实测）

| 事实 | 证据 |
|---|---|
| `GET /connect?token=<launchToken>` → 302 + 签名 API Cookie | ✅ 实测 |
| **unary 走 HTTP**：`POST /api/<ns/method>`，信封 `{type:"client-request",rpcId,method,payload:{args}}` | ✅ 实测调通 |
| payload 必须**恰好含一个 `args`**，且参数名按端点 descriptor（`session/list` 要 `{"_request":{}}`） | ✅ 实测（报错信息即证据） |
| **stream 走 WebSocket** `/api/remote.mux`，帧 `open/item/end/cancel` | ✅ 实测收到帧 |
| 对 unary 方法发 mux open 帧会被拒（`unary Remote methods cannot be opened through the stream carrier`） | ✅ 实测 |
| 会话历史是**带序号的事件日志**（`session/page` 的 `records[].event`），不是消息数组 | ✅ 实测 |
| launch token **每进程随机**，只在 dsh 的 stdout 里，不落盘 | ✅ 实测 |
| `/api/*` 整片鉴权：不存在的路径也 401，未知非 API 路径才 404 | ✅ 实测 |
| 配对插件设备身份走 `x-dsh-remote-device` header，对 loopback 短路 | ✅ 实测 |

完整细节（含事件词汇表、折叠规则、投影字段）见 [../docs/protocol.md](../docs/protocol.md)。

## 还没确认的

- `assistant/message`（成功回复）的真实形状 —— 需要一次真模型调用
- 断线重连 / 事件回放语义（`session/observation-ended` 之后怎么续）
- 附件上传（`/api/session/uploadFileBinary`）

## 设计约束

1. **默认只读**：探针不创建会话、不发消息、不改配置（`LiveSelfTest` 与 `m3-*.mjs` 的写操作只对隔离实例用）。
2. **降级优先**：任何一步失败都给出可执行的下一步，而不是抛栈。
3. **不假设能力**：上层通过 `Capabilities` 判断能开哪些原生功能；
   `native.*` 一律要求有 API Cookie。
4. **零第三方依赖**（Java 侧）：不引 OkHttp/Gson，因此能在纯 JDK 下编译和自检。

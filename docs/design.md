# 设计说明（含立项复盘）

> 这份文档回答"现在的架构为什么长这样"。
> 最早的立项方案（2026-11-13 上午）写在 §5「复盘」里 —— 保留当时的判断，
> 并逐条标注**后来发现哪里错了**。这样做比删掉它更有用：错误的判断往往比正确的结论更值得记录。

当前状态与能力矩阵见 [../README.md](../README.md) 与 [capabilities.md](capabilities.md)。

---

## 1. 目标与非目标

**目标**：一个开源的、能连**任意** DSH 实例的移动客户端，比手机浏览器体验好。

**非目标**：
- 不内置任何服务器，不代理流量，不收集数据
- 不替代官方 Web UI（原生只覆盖高频路径，冷门功能指路官方界面）
- 不追求 UI 复刻（原生界面是"够用、快、不崩"，不是像素级还原）

---

## 2. 关键约束（都是实测结论）

| 约束 | 事实 | 对方案的影响 |
|---|---|---|
| **没有长期凭据** | launch token 每进程随机生成，只打印在 `dsh web` 的 stdout，不落盘 | 做不到"用户零操作"的原生模式；必须由用户粘贴一次带 token 的地址 |
| **两条传输通道** | unary（列表/投影/发消息）走 `POST /api/<ns/method>`；实时事件走 WebSocket `/api/remote.mux` | 协议层必须同时实现 HTTP 与 WebSocket，不能只做一种 |
| **`/api/*` 整片鉴权** | 不存在的 `/api/xxx` 也返回 401，未知非 API 路径才 404 | **不能用状态码判断接口是否存在**；能力探测必须"带凭证实际调用" |
| **RPC 不是公开契约** | 方法是内部 `命名空间/方法`，实测 **90 个端点 / 13 个命名空间**（`session` 25 个），schema 内嵌在产物里 | 能力探测 + 自动降级是硬要求 |
| **版本漂移快** | 2026-10 至 11 期间从 0.2.1-rc.2 升到 0.2.2-rc.1 | 协议层独立、探针可回归；升级只改一层 |
| **插件不是主路径** | 远程访问能力来自第三方插件，但核心 `/connect?token=` 通道不依赖它 | 连接层以核心 token 路径为主，插件路径是可选增强 |
| **构建环境无 Gradle** | 构建机上没有 Android Studio / Gradle / androidx 缓存 | 手写构建流水线（aapt2 → javac → d8 → zipalign → apksigner） |

---

## 3. 分层（实际实现）

```
protocol/           协议层：纯 JDK，零 Android 依赖，可独立编译与自检
  java/...          Credential / ApiCookieExchanger / UnaryRpc / DshClient
                    WebSocketClient(RFC6455 手写) / ObserveEventStream
                    Json / SessionSummary / SessionProjection / SessionEvent
                    TranscriptBuilder / SessionTranscript / MarkdownLite
                    Diagnostics / Capabilities
   *.mjs            JS 侧同构实现 + probe.mjs（CI 可用探针）
   test/            一次性探针（m3-*）与回显服务器
app/                Android 外壳：纯 framework Views，零 androidx/Compose
  src/...           MainActivity / WebActivity / SessionsActivity
                    SessionDetailActivity / HelpActivity / Task / Preflight
tools/              开发辅助：dev-instance.ps1（隔离的一次性 DSH）
docs/               协议笔记、能力矩阵、构建踩坑、M0 实测、本文
```

**关键点**：`protocol/` 能在**不加载 android.jar** 的情况下编译并跑 114 项自检
（`protocol/selftest.ps1` 会真的这么做）。这是"协议层与 UI 解耦"的可执行证据，不是口号。

---

## 4. 决策记录（为什么这么选）

**D1 · 协议层独立成源码根**
DSH 升级时只想改一处，且希望别人能在非 Android 环境复用它。代价是多一层目录约定。

**D2 · 不用 Compose / Gradle，手写流水线**
构建机上没有 Android Studio，手写流水线可以完全避开 AGP 版本地狱与依赖解析。
代价：没有现成组件、布局全靠代码写、不能直接引第三方库（连 JSON 都自己写了 100 行）。
**什么时候该换**：需要复杂列表/动画、或要接 androidx 生态时，迁到 Gradle + Compose，
`app/src` 与 `app/res` 可原样复用。文档见 [build.md](build.md)。

**D3 · 会话用"事件日志 + 折叠"而不是消息数组**
DSH 的 `session/page` 返回的是带序号的事件日志（`user/message`、`assistant/attempt`、
`tool/call`…），历史和实时推送会重叠。所以用 `TranscriptBuilder` 按 `seq` 去重保序地折叠，
而不是假设"有个消息数组可以覆盖"。

**D4 · 诊断文案放协议层**
401/502/530 的解释是**协议知识**，App、探针、文档应该共用同一套说法。
之前 App 与 JS 各写一份，容易说岔；现在文案在 `protocol/java/.../Diagnostics.java`。

**D5 · 能力矩阵 + 文档一致性自检**
`Capabilities.matrixRows()` 是单一事实来源，`docs/capabilities.md` 由自检校验
（文档漏写能力会直接让自检红）。防止"代码加了能力、文档没跟上"。

**D6 · 用隔离实例做验证，绝不碰用户实例**
`tools/dev-instance.ps1` 把已安装的 profile **复制**到临时 `DSH_HOME`、改掉固定端口、
禁掉已知不兼容插件后再启动。所有写操作（建会话、发消息）只在那个一次性实例里做。

---

## 5. 复盘：立项时的判断，后来哪些错了

> 原方案写在 2026-11-13 上午，当时 M0 还没做。以下逐条对照实测结果。

| # | 当时的判断 | 实际发现 | 影响 |
|---|---|---|---|
| 1 | RPC 全部走 mux WebSocket `/api/remote.mux` | **unary 走 HTTP** `POST /api/<ns/method>`，mux **只承载 stream** | 传输层要实现两套；早期探针发 mux 帧调 unary 直接被拒 |
| 2 | 事件流端点是 `session/events` | 实际是 `session/observeEvent`（会话级）与 `$events`（网关级） | 探针早期一直在试错端点名 |
| 3 | 想用原生能力必须依赖配对插件（C 模式） | **B 模式（核心 token）完全不需要插件**，且能力更强 | 把 C 模式降为可选路径，通用性问题解决 |
| 4 | launch token 拿不到 → 无法验证/无法做原生 | 用**隔离实例**（复制 profile + 独立 `DSH_HOME` + 换端口）能合法拿到 token | 从"只能做 M1"变成"能做 M2/M3" |
| 5 | 干净 profile 起不了第二个实例（依赖装不上） | 复制已装好的 profile 即可，不需要联网装包 | 形成了 `tools/dev-instance.ps1` |
| 6 | 建议 Kotlin + Compose Multiplatform | 手写流水线 + framework Views 就够，且构建更可控 | 推迟 Compose 迁移；代价是组件要自己写 |
| 7 | M0 是 go/no-go 闸门：不过就只做 M1 | **过了**（含"模型失败但事件仍落盘"这个技巧），所以做了 M2/M3 | — |

**一条方法论收获**：卡在"需要凭证才能验证"时，先问"能不能造一个**用完即弃**的环境"。
隔离实例这条路把一个看似无解的限制变成了 30 秒的脚本。

---

## 6. 仍未验证 / 已知风险

| 项 | 现状 | 风险 |
|---|---|---|
| `assistant/message`（模型成功回复）的事件形状 | 未实测，按 message 形状兜底 | 真机上模型回复可能显示不全 |
| 断线重连 / 事件回放 | 未做，断开就显示"实时未连接" | 网络抖动后需要手动刷新 |
| 附件上传（原生） | 未做（WebView 里可以） | 原生模式不能传文件 |
| 真机验证 | **未做**（构建机无 adb / 设备） | 布局、滚动、键盘遮挡都还没经过真机检验 |
| RPC 兼容性 | 只验证了 0.2.2-rc.1 | DSH 升级后需要重跑 `protocol/selftest.ps1` + `probe.mjs` |

---

## 7. 如果重来，会怎么改

1. **更早做隔离实例**：它把"依赖用户配合"变成了"自己就能验证"，几乎每一个后续突破都建立在它上面。
2. **更早区分 unary / stream**：前几轮把两者都当 mux，浪费了不少试错。
3. **更早把"事件日志"当第一等公民**：一开始想找"消息数组"，找到的是事件流，折叠逻辑是后补的。
4. **文档与代码的自动一致性**（D5）应该在第一次写文档时就建立 —— 否则文档必然腐化。

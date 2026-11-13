# M0 协议验证 · 最终结论

> ⚠️ **阅读提示（2026-11-13 晚补记）：本文的"三个阻塞点"后来全部被绕过，结论已更新。**
>
> | 本文的阻塞点 | 后续结果 |
> |---|---|
> | 阻塞 1：拿不到 launch token | ✅ 解决：用**隔离实例**（复制已安装 profile 到临时 `DSH_HOME`、换端口）启动，能从 stdout 拿到 token。见 `tools/dev-instance.ps1` |
> | 阻塞 2：设备门不放行（C 插件模式） | ✅ 绕过：核心 **B 模式**（`/connect?token=`）完全不需要配对插件，能力更强 |
> | 阻塞 3：起不了第二个实例 | ✅ 解决：复制 profile 即可，不需要联网装包 |
>
> 因此本文"端到端 RPC 调用未跑通"的判定**已作废**：当前 `session/list`、`session/modelCatalog`、
> `session/create`、`session/page`、`session/projections`、`session/prompt`、
> `session/observeEvent` 全部实测调通。最新事实见 [protocol.md](protocol.md)，
> 架构与复盘见 [design.md](design.md)。

> 2026-11-13，对 DSH **0.2.2-rc.1** 实测。所有结论来自真实请求/源码，标注了证据。
> 实验环境：本机 loopback + 公网域名；一次性 `probe` profile（已删除）；未改动 desktop profile 与隧道配置。

---

## 一、判定

**传输与契约：完全摸清 ✅**
**端到端 RPC 调用：未跑通 ❌** —— 被三个独立的鉴权/分发问题挡住。
> ← **这条已作废**：三个阻塞点后来全部绕过，RPC 现已全部调通（见顶部提示）。

→ 结论不是"做不到"，而是：**认证是这类第三方客户端的真正天花板，不是协议。**

---

## 二、已确认的事实（可直接作为实现依据）

### 2.1 传输层

| 事实 | 证据 |
|---|---|
| RPC 通道是 **WebSocket `/api/remote.mux`** | 源码文档："The Client opens the Gateway-owned `/api/remote.mux` WebSocket" |
| 网关 `typertGateway`：**unary `invoke`** + **stream**（可取消的 async iterable） | `invokeRemote` / `RemoteNamespaceService` |
| 方法命名 `命名空间/方法`（`session/list`、`session/prompt`…），当时估"约 260 个"，实测是 **90 个 / 13 个命名空间**（session 25 个） | schema 注册表（内嵌 app.asar，含 JSON Schema + 源码位置） |
| 其它 WS 路径（来自配对页内嵌配置 `wsPaths`） | `/api/remote.mux`、`/sidebar/ws/terminal`、`/sidebar/ws/agent-terminals`、`/sidebar/ws/agent-opens`、`/api/dsh-ssh/terminal` |

### 2.2 官方给程序化客户端的通道

```
GET /connect?token=<launchToken>&interactions=answer|none
  → 立即创建 owner 权限的 Peer
  → 302 返回 Mux 目的地 + 签名 API Cookie
```

### 2.3 配对（remote-web-ui 插件）完整链路 —— 已全部打通到"设备门"

```
POST /api/pair/issue         （仅 loopback）→ { token, url, expiresAt, publicBaseUrl }
GET  /pair-accept?pair=<t>   → 303 + Set-Cookie: dsh_pair=<deviceId>; HttpOnly; SameSite=Lax
                               → Location: /pair-app?grant=<g>
GET  /pair-app?grant=<g>     → 200，54 KB 官方 shell（手机拿到的就是这份）
```

**关键发现：配对页内嵌了完整路由配置**

```js
var R = {
  remotePrefix: "/remote",  apiPrefix: "/api/",  pairPrefix: "/api/pair/",
  wsPaths: ["/api/remote.mux", "/sidebar/ws/terminal", ...],
  deviceHeader: "x-dsh-remote-device",   // ← 设备身份走 header
  deviceKey: "dsh-remote-device",        // ← 客户端 localStorage 里的键
  deviceQuery: "device",                 // ← 也可用 ?device=
  hostGrantGlobal: "__DSH_REMOTE_HOST_GRANT__"
};
// 且：if (host is localhost/127.x) return;   ← 设备门对 loopback 直接跳过
```

---

## 三、三个阻塞点（实测）

### 阻塞 1：核心 Peer 需要 launch token，而它拿不到

- launch token **每进程随机生成**，只出现在 `dsh web` 打印的 `?token=` URL 里
- **不落盘**：`~/.dsh`、`%LOCALAPPDATA%\@deepseek-aidsh-desktop` 全量扫描无命中；不在环境变量；Chrome 历史里也没有
- 结论：**第三方客户端无法自动获得核心身份，必须由用户把 token URL 交进来**

### 阻塞 2：设备门（配对身份）可用但不放行

实测的响应码分层很有信息量：

| 连接方式 | 结果 | 含义 |
|---|---|---|
| `ws://127.0.0.1/api/remote.mux` + 设备 cookie | **401** | loopback 被脚本跳过设备门，直接落到核心 → 需要 Peer 身份 |
| 同上 + `x-dsh-remote-device` header | **401** | 同上（loopback 短路） |
| `wss://dsh.example.com/api/remote.mux` + cookie + header | **403** | ✅ 穿过核心网关，**落在设备门**，被拒 |

403 的原因：插件姿态探测返回 `"posture":{"hosts":[{"host":"dsh.example.com","exposed":false}]}`
—— 它认为公网主机"未暴露"，于是对设备请求 **fail-closed**。心跳（`/api/pair/heartbeat` → 200）也没改变结果。

> 这解释了为什么手机能用而脚本不行：真机走的是完整 shell 流程（拿 `hostGrantGlobal`），
> 不是裸 cookie + header。这条路要继续验证，需要抓一次真机请求。

### 阻塞 3：无法用干净 profile 起第二个实例做实验

版本豁免已按授权授予并生效（`dsh: allowed @deepseek-ai/dsh-web-app@0.0.1-rc.1 for DSH 0.2.2-rc.1`），
但安装失败：

```
[ERR_PNPM_FETCH_404] GET https://registry.npmjs.org/@deepseek-ai%2Fdsh-client-ui-permission: Not Found
```

**`@deepseek-ai/dsh-client-ui-*` 等包不在 npm 上**，只随 app.asar 分发。
→ 任何"从 registry 干净引导一个 DSH 实例"的思路都不可行；验证只能针对**已运行的实例**。

---

## 四、对开源项目的决定性影响

原方案假设"能调用 RPC"，实测后必须修正为**能力按凭证等级分级**：

| 模式 | 用户操作 | 拿到的能力 | 依赖 |
|---|---|---|---|
| **A 基础**（默认） | 粘贴任意 DSH 地址 | WebView 承载官方 UI（L0） | 仅 DSH 核心 |
| **B API**（高级） | 粘贴 `dsh web` 输出的**带 token URL** | `/connect?token=` → API Cookie → **原生 RPC 全能力**（L1/L2） | 用户提供 launch token |
| **C 插件**（装了 remote-web-ui） | 扫码配对 | `/pair-app` shell + 设备门 | 第三方插件 |

**没有"用户零操作"的原生模式。** README 必须写清，否则用户会问"为什么我的原生能力不亮"。

同时得到一个正向结论：**L0（WebView 壳 + 多实例 + 诊断）不依赖任何鉴权技巧，永远可用** —— 这保证项目即使 L1/L2 断供，也有独立价值。

---

## 五、下一步

1. **M1 先行**：L0 保底版（WebView 壳 + 多实例 + 401/502/530 诊断 + 记住登录）—— 不受本次阻塞影响
2. **B 模式验证**（解锁 L1/L2 的关键）：由用户提供一个 `dsh web` 的 token URL，验证
   `/connect?token=` → WS → `session/list`。这一步跑通即可写 `docs/protocol.md`
3. **C 模式的正确做法**：不要再裸拼 cookie+header，而是抓一次**真机**的完整请求序列
   （含 `hostGrantGlobal` 如何使用），确认设备门的放行条件

## 六、实验环境说明（已清理）

- 一次性 profile `~/.dsh/profiles/probe` 已删除（1.3 KB，安装未成功）
- 测试用配对设备未注册进设备表（`deviceCount` 前后均为 4），`revoke` 返回 bad-payload 印证
- 用户的 `desktop` profile、端口固定补丁、Cloudflare 隧道均未改动，验收 401/401 正常

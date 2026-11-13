# dsh-mobile

**非官方**的 DeepSeek Harness（DSH）移动客户端。让手机用 DSH 比浏览器舒服：独立图标、无地址栏、多实例管理、断线原因说得清。

> ⚠️ 本项目与 DeepSeek 官方无关，不是官方客户端。它连接的是**你自己运行的** DSH 实例。

---

## 它解决什么问题

DSH 是桌面应用，官方 Web UI 在手机上能用，但：

- 浏览器里没有"应用"的感觉（地址栏、标签页、误触刷新）
- 想连**多台** DSH（本机、家里 NAS、公司机器）时，浏览器标签会乱成一团
- 连不上时只会看到一个错误码，不知道是 DSH 没开、端口变了、还是隧道掉了

本项目的 L0 层就是把这个体验做好；L1/L2 层在**能拿到凭证**的前提下接入原生能力。

## 能力分级（重要，先读这段）

DSH 的核心 RPC 没有面向第三方的长期凭据（实测见 `docs/`），所以本项目按**凭证等级**提供不同能力。
完整矩阵与降级行为见 [docs/capabilities.md](docs/capabilities.md)（**该文档与代码由自检强制一致**）。

| 模式 | 你需要做什么 | 你得到什么 |
|---|---|---|
| **A 基础模式**（默认） | 粘贴任意 DSH 地址（例如 `http://192.168.1.12:3080` 或你的公网域名） | WebView 承载官方 UI + 诊断 + 多实例 + 附件/JS 弹窗 |
| **A + B API 模式** | 再加一个 `dsh web` 输出里带 `?token=` 的启动地址 | 上面全部 **+ 原生会话列表/详情、实时事件、原生发消息、代码块渲染** |
| **C 插件模式**（依赖第三方插件） | 扫 `dsh-remote-web-ui` 的配对二维码 | 设备通道 + 官方 shell |

**没有"用户零操作"的原生模式**——因为 launch token 每进程随机、不落盘（原因见 [docs/m0-findings.md](docs/m0-findings.md)）。
能力探测失败时会**自动降级**（原生入口直接不显示，而不是显示了再报错），不会白屏。

## 连接准备

### 1. 让 DSH 监听可访问的地址

默认 DSH 只绑 `127.0.0.1`，手机连不上。两种做法：

```bash
# 局域网：监听所有网卡（注意同网段任何人都能访问，务必靠 DSH 自己的鉴权把关）
dsh web --host 0.0.0.0 --port 3080
```

或者用你自己的隧道（Cloudflare Tunnel / frp / Tailscale 等）把它暴露成一个 HTTPS 域名。

### 2. 在 App 里填地址

`http://192.168.1.12:3080` 或 `https://your-domain.example`。

## 构建

本项目刻意**不依赖 Gradle / androidx / Compose**，只用手写的命令行构建，零 Maven 依赖、可离线复现。

```powershell
# 一次性准备工具链（JDK 17 + Android build-tools + platform）
./toolchain/fetch-toolchain.ps1

# 构建 APK
./build.ps1
# 产物：dist/dsh-mobile-debug.apk
```

详见 [docs/build.md](docs/build.md)。

### 三件套检查（CI 可跑，都以退出码表达结果）

```powershell
powershell -ExecutionPolicy Bypass -File protocol/selftest.ps1   # 协议自检（断言数见输出）+ WebSocket 端到端
powershell -ExecutionPolicy Bypass -File tools/lint-app.ps1      # App 静态检查 5 条规则
powershell -ExecutionPolicy Bypass -File build.ps1               # 构建 APK
```

静态检查专门抓"编译通过、真机才炸"的问题（Activity 未释放、后台线程碰 View、连接泄漏等），
规则与踩坑记录见 [docs/audit.md](docs/audit.md)。

## 项目结构

```
protocol/            协议层（与 UI 解耦，DSH 升级时只改这里）
  dsh-protocol.mjs     预检 / 换 API Cookie / HTTP unary / mux stream / 能力清单
  probe.mjs            命令行探针（只读，CI 可用）
  java/                协议层的 Java 实现（纯 JDK，无 Android 依赖）+ 三套自检
app/                 Android 应用（纯 framework Views，零 androidx/Compose）
  src/               源码
  res/               资源
docs/                文档索引见 docs/README.md（含术语表）：设计说明（design.md）、
                     能力矩阵（capabilities.md）、协议笔记（protocol.md）、构建（build.md）、
                     M0 实测（m0-findings.md）、真机验收（device-checklist.md）、体检（audit.md）
tools/               开发辅助：dev-instance.ps1（一键起隔离的一次性 DSH 拿 token）、
                     lint-app.ps1（App 静态检查）
toolchain/           一次性工具链下载脚本（产物已 gitignore）
build.ps1            命令行构建脚本
LICENSE              Apache-2.0
```

## 协议探针

判断一台 DSH 现在处于什么状态，以及原生能力可不可行：

```bash
cd protocol

# 不需要任何凭证：只看可达性与鉴权状态
node probe.mjs --base http://127.0.0.1:3080

# 有 launch token 时：一路验证到 RPC
node probe.mjs --token-url "http://127.0.0.1:3080/?token=xxxxx"

# 验证 stream 通道（订阅网关内部事件流）
node probe.mjs --token-url "http://127.0.0.1:3080/?token=xxxxx" --stream '$events'

# 给 CI / 脚本消费
node probe.mjs --base https://dsh.example.com --json
```

退出码 `0` = 全部通过，`1` = 有步骤失败。探针是**只读**的：不创建会话、不发消息、不改配置。

## 状态

> ⚠️ **所有界面尚未在真机/模拟器上跑过**（构建机没有 adb）。
> 拿到 APK 后请照着 **[docs/device-checklist.md](docs/device-checklist.md)** 一步步验 ——
> 那份清单写了每步的预期现象、不符合时怎么查、以及哪些是已知限制不用当 bug 报。
>
> **完整的「已交付 / 已实测 / 尚未验证」三档清单见 [docs/status.md](docs/status.md)。**

| 里程碑 | 状态 |
|---|---|
| M0 协议验证 | ✅ 完成（当时结论"认证是天花板"**已被绕过** —— 用隔离实例拿到 token；复盘见 [docs/design.md](docs/design.md) §5） |
| M1 L0 保底版 | ✅ APK 可构建可安装：多实例 / 全屏 WebView / 诊断 / 连接预检 / 宽度切换（**待真机验证**） |
| M1.5 凭证分级落地 | ✅ protocol 层独立成源码根（纯 JDK 可编译）+ 自检；App 按 A/B 模式渲染，B 模式可实测换取 API Cookie |
| M1.6 mux 客户端就绪 | ✅ 自写 RFC 6455 客户端（无 OkHttp），已对标准 WebSocket 服务器端到端验证 |
| **M2 协议层打通** | ✅ **实测调通**：`/connect?token=` → API Cookie → HTTP unary → `session/list`、`session/modelCatalog`；JS 与 Java 两个实现都对着真实实例验证过 |
| **M2 原生会话列表** | ✅ 已接入 App：标题/状态/相对时间，长按可复制会话 ID、跳官方界面 |
| M2 原生切换会话 | ✅ 点会话 → 进入**原生详情页**（投影 + 对话 + 发送），不再跳官方 UI。DSH 没有"切换会话"的 URL 路由，所以切换 = 用自己的界面渲染目标会话 |
| **M3 前置：数据模型** | ✅ 实测拿到 `session/projections`（标题/用量/权限/轮次大纲）、`session/page`（**事件日志**分页）、`session/observeEvent`（增量流） |
| **M3a 原生会话详情** | ✅ 已接入 App：点会话 → 原生渲染投影（标题/目录/权限/统计/token/上下文构成）；`LiveSelfTest` 对真实实例验证过解析 |
| M3b 原生消息流 | ✅ **数据管线打通**：真实事件 21 条 → 折叠出「用户消息 + 错误(带 code)」，上下文注入已过滤、标题取自 `session/title`；详情页已渲染对话。**零额度消耗**（利用"模型失败但事件仍落盘"） |
| **M3b+ 实时增量** | ✅ `observeEvent` 订阅 → 按 seq 去重合并 → 详情页实时追加；实测订阅后收到 9 个实时事件。离开页面自动断开 |
| **M3c 工具卡片** | ✅ `tool/call` → 工具卡片（名字+参数，缩进样式）；`tool/result` 按 `toolCallId` 回填 完成/失败。形状取自官方 bundle（无模型适配器时不会产生该事件，故用自检覆盖） |
| **M3e 原生发送** | ✅ 详情页带输入框：`session/prompt` 发送、乐观渲染「发送中…」、真实事件到达后自动替换、失败显示错误行；JSON 转义有 6 条断言 |
| **M3f 新建会话** | ✅ 会话列表「＋ 新建」→ `session/create`（不指定 cwd，Host 决定）→ 直接进原生详情页。原生链路闭环：列表 → 新建 → 详情 → 发消息 |
| **M3g Markdown-lite** | ✅ 围栏代码块（``` / ~~~）用等宽字体 + 浅底色 + 横向滚动渲染，未闭合围栏按代码处理（流式时不跳变）；普通文本原样显示。10 条断言 |
| **M3h 导出全文** | ✅ 详情页「复制全文」把整段对话导成纯文本（标题/【你】/【DSH】/【错误】/【工具】分段）；格式化在协议层，4 条断言 |
| **M3i 增量渲染** | ✅ 实时事件只 **append 新增消息**，不重建整个对话区（仅工具状态回填时全量重建）；长对话不再随事件数量卡顿 |
| M3d 成功回复 | 🚧 `assistant/message` 的真实形状需要一次**成功回复**（隔离实例无模型凭据，目前只有兜底实现） |

### 开发辅助：起一个隔离的一次性 DSH

验证协议不需要碰你自己的实例：

```powershell
powershell -ExecutionPolicy Bypass -File tools/dev-instance.ps1
# 输出 TOKEN_URL=http://127.0.0.1:21387/?token=...
node protocol/probe.mjs --token-url "<TOKEN_URL>"

powershell -ExecutionPolicy Bypass -File tools/dev-instance.ps1 -Stop   # 用完清理
```

如果脚本找不到 `dsh.cmd`（不在 PATH、也不在默认安装位置），用 `-DshPath "<...>\resources\runtime\cli\bin\dsh.cmd"` 指定。

它会把已安装的 profile **复制**到临时 `DSH_HOME`、改掉固定端口、禁掉已知不兼容的插件再启动，
所以原实例和 `~/.dsh` 完全不受影响。

### M1 已实现

- 多实例增删改 + **记住上次打开**（启动页有「继续上次：<名字>」按钮；按 URL 记，删实例不会错位）
- **首启动空状态**：没有实例时给出添加指引，而不是一片空白
- **旋转屏幕不重建界面**（四个 Activity 都声明了 `configChanges`）：对话、输入框里没发出去的草稿、实时连接都不会因为转屏丢失
- **回到页面自动刷新**：返回会话列表会重拉一次；从后台回到详情页（间隔 ≥2 秒）会重连实时流，断线期间的新消息不会丢
- 全屏 WebView：无地址栏、返回键走网页历史
- **断线诊断**：401 / 403 / 404 / 502 / 530 / 连接失败 → 中文解释 + 排查方向
- **WebView 该有的都有**：JS `alert/confirm/prompt` 正常弹窗、**附件按钮可用**（`onShowFileChooser` + 系统文件选择器回传）、
  **下载可用**（`DownloadManager` + 自动带上 WebView 的 Cookie，存到应用下载目录）、
  **`window.open` / `target=_blank` 可用**（同源当前页打开，异源交给系统浏览器，不会弹空白窗口）、
  **渲染进程被系统回收时自动重建**（不处理会连 App 一起被杀）、
  顶部有加载进度条，麦克风/摄像头请求明确拒绝而不是静默挂起
- **连接预检**（实例长按 → 测试连接）：判断是网络、隧道还是鉴权问题
- 桌面/手机宽度切换（width≥1100 可绕开某些竖屏适配层问题）
- 清除登录态

### 已知限制

- 多实例**共用一份 cookie**（Android 单进程 WebView 限制；Cookie 按域名、**不区分端口**）。
  已做缓解：换实例时如果发现"这个域名上次是别的实例在用"，会提醒并支持一键清理该域名的登录态。
  真正的隔离需要独立进程，属于后续工作
- 原生界面的 Markdown 是**轻量实现**：代码块/表格/标题/引用/列表/加粗/斜体/行内代码/链接都支持，但不做 HTML 内嵌与对齐语义；需要精确排版请走官方界面
- 原生界面不渲染 `system/message` 与上下文注入（它们是几 KB 的提示词），只统计条数
- **尚未在真机/模拟器上运行验证过**（构建机无 adb）；布局、滚动、键盘遮挡都还没经过真机检验
- launch token 每次重启 DSH 都会变，原生功能需要用户手动更新实例里的"启动地址"

## 许可证

[Apache-2.0](LICENSE)

Copyright 2026 DSH Mobile contributors

本项目是**非官方**客户端：与 DeepSeek Harness 官方无关，不代表官方立场。
使用前请确认你连接的是自己的实例，并自行承担由此产生的风险。

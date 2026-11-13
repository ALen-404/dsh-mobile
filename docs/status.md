# 交付状态（截至 2026-11-13）

> 这份文档把"做完了什么"按**证据等级**分三档，避免把"代码写了"当成"验证过了"。
> 重新验证任一档的命令都写在每节末尾。

---

## A. 已交付（产物在仓库里，可直接查看/运行）

### 代码

| 部分 | 规模 | 内容 |
|---|---|---|
| `app/src`（Android 外壳） | 10 个类 / 2481 行 | MainActivity、WebActivity、SessionsActivity、SessionDetailActivity、HelpActivity、Task、Preflight、InstanceStore、WebCookies、Instance |
| `protocol/java`（协议层，纯 JDK） | 20 个类 / 3418 行 | 凭证/交换/unary/WebSocket/事件流/JSON/会话模型/折叠/Markdown/诊断/能力/文件名/实例编解码 + 3 套自检 |
| `protocol/*.mjs`（JS 侧 + 探针） | 7 个文件 | dsh-protocol、probe（CLI 探针）、dsh-methods（端点清点）、m3-*（一次性探针）、ws-echo-server |
| 脚本 | 4 个 `.ps1` | build（构建）、selftest（自检）、lint-app（静态检查）、dev-instance（隔离实例） |

### 产物

`dist/dsh-mobile-debug.apk` —— 68.5 KB，debug 签名，minSdk 21 / targetSdk 34，5 个 Activity。

### 功能（按阶段）

- **M1（A 模式）**：多实例增删改、继续上次、WebView 全屏 + 返回键走网页历史、401/403/404/502/530 诊断、连接预检、桌面/手机宽度切换、退出时清登录态、附件选择、下载（带 Cookie）、`window.open`、JS 弹窗、渲染进程崩溃恢复、加载进度条
- **M2（A + B 模式）**：`/connect?token=` 换 API Cookie、原生会话列表（标题/状态/相对时间）、原生会话详情（投影：权限/目录/统计/token/上下文构成）、按 seq 增量合并的实时事件、能力分级与降级
- **M3**：事件日志 → 对话折叠、实时追加、工具调用卡片、Markdown 渲染（代码块/表格/标题/引用/列表/加粗/斜体/行内代码/链接）、复制全文、原生发消息（乐观渲染 + 失败提示）、新建会话、**原生发送附件**（图片内联 base64、其它文件先上传拿 receiptId）、**断线自动重连**（1s→30s 指数退避）

### 文档（8 份 / 54.3 KB）

索引见 [README.md](README.md)：设计复盘、能力矩阵、协议笔记、构建踩坑、M0 实测、真机清单、体检规则、本文件。

### 重新验证

```powershell
powershell -ExecutionPolicy Bypass -File protocol/selftest.ps1   # 断言数见输出
powershell -ExecutionPolicy Bypass -File tools/lint-app.ps1      # 静态规则
powershell -ExecutionPolicy Bypass -File build.ps1               # 产出 APK，退出码 0
```

### CI 产出

- `.github/workflows/ci.yml`：每次 push 到 main / PR 都跑自检 + 静态检查，并把 APK 作为 artifact 上传
- `.github/workflows/release.yml`：推 `v*` tag 自动构建并创建 Release（`versionName` 取 tag，
  `versionCode` 取提交总数）
- 已实测通过：`ci` 与 `release` 两个工作流都跑通，Release 里的 APK 版本名与 tag 一致

---

## B. 已在**真实 DSH**上验证过（有实测证据）

> 全部在**隔离实例**上做（`tools/dev-instance.ps1`：复制 profile + 临时 `DSH_HOME` + 换端口）。
> 用户的 desktop profile、隧道、真实实例全程未被修改。

| 结论 | 证据 |
|---|---|
| `/connect?token=` → 302 + 签名 API Cookie | JS 探针与 `ApiCookieExchanger` 都跑通 |
| unary 走 `POST /api/<ns/method>`，payload 必须恰好含 `args` | `session/list`、`session/modelCatalog`、`session/create`、`session/page`、`session/projections`、`session/prompt` 全部调通 |
| mux WebSocket 只承载 stream；帧为 `open/item/end/cancel` | 订阅 `$events` 与 `session/observeEvent` 均收到 `item` 帧 |
| 会话历史是**带序号的事件日志**，不是消息数组 | `session/page` 返回 `records[].event`（含 `seq`/`type`/`data`） |
| 事件折叠可用：21 条事件 → 2 个对话条目 | 真实拿到 `[user] probe…` 与 `[error] no adapter…（NO_ADAPTER）` |
| 实时增量订阅可用 | 订阅后发一条消息，实时收到 9 个事件（`agent/inbox/spliced`/`turn/start`/…） |
| 上下文注入会被误记为 `user/message`，必须按 `source.kind` 过滤 | 实测：不过滤会冒出几 KB 的提示词文本 |
| 自写 RFC6455 客户端在真实 mux 上可用 | `LiveSelfTest` 第 4 步收到帧 |
| 端点清单：90 个 / 13 个命名空间（session 25） | `protocol/test/dsh-methods.mjs` 从产物提取 |
| 原生附件：上传 → receiptId → prompt 的 file 片段 → 事件日志解析为 attachment | Java `LiveSelfTest` 第 6 步，连跑两次稳定 |
| Markdown 块级/行内解析（代码块/表格/标题/引用/列表/加粗/斜体/行内代码/链接） | 21 条断言 |
| 失败路径的形状（模型不可用时） | `assistant/attempt.stream[].chunk.reason.failure` + `turn/end.reason.error` |

### 重新验证

```powershell
powershell -ExecutionPolicy Bypass -File tools/dev-instance.ps1        # 起隔离实例，输出 TOKEN_URL
# 找不到 dsh.cmd 时加：-DshPath "<...>\resources\runtime\cli\bin\dsh.cmd"
node protocol/probe.mjs --token-url "<TOKEN_URL>"                      # 只读探针
java -cp build/protocol-selftest com.dshmobile.protocol.LiveSelfTest "<TOKEN_URL>" create
powershell -ExecutionPolicy Bypass -File tools/dev-instance.ps1 -Stop  # 清理
```

---

## C. **尚未验证**（这是当前唯一的缺口）

| 未验证项 | 为什么没验 | 怎么才算验过 |
|---|---|---|
| **全部界面**（5 个 Activity、WebView 行为、键盘/滚动/布局） | 构建机没有 adb，也没有设备/模拟器 | 按 [device-checklist.md](device-checklist.md) 在真机上走一遍 |
| `assistant/message`（模型**成功**回复）的真实形状 | 隔离实例没有模型凭据，只能拿到失败路径 | 一次真模型调用：给隔离实例配凭据，或用已有对话实例的 token 读历史 |
| `tool/*` 事件的真实形状 | 同上（没有成功回复就不会有工具调用） | 同上一行；当前形状来自官方 bundle 的渲染代码，属于 `[DOC]` 级 |
| 原生附件的**图片**内联路径 | 已按 schema 实现（base64 内联），但没在真机上选过图 | 真机选一张图发出去 |
| 断线**重连期间**的历史补齐 | 重连逻辑已实现（退避 1s→30s），但只在离屏/切后台场景验证过 | 真机上关 WiFi 再开，看是否自动恢复 |
| 多实例 Cookie 真隔离 | Android 单进程 WebView 限制，只做了「提醒 + 清理」 | 需要独立进程方案 |
| Android 各版本差异（渲染进程回收、文件选择器实现） | 只在 API 34 编译，未在任何设备上跑 | 多机型验证 |

### 一句话结论

> **协议层与产物已实测；界面代码可构建可安装，但没有任何一行 UI 在真机上运行过。**

因此当前**不能**宣称"完整可用"。能明确说的是：
**"协议验证完毕、功能实现完毕、APK 可安装，界面验证待做。"**

---

## D. 把 C 变成 B 需要什么

1. **装一次 APK，按 [device-checklist.md](device-checklist.md) 走一遍** —— 覆盖 C 表里第一行和最后一行
2. **一次成功的模型回复** —— 覆盖 `assistant/message` 与 `tool/*`

这两件事都不需要改代码，只需要一次真机 + 一次真实调用。做完之后本文档的 C 表才能清空。

---

## E. 这次没有破坏什么

- 用户的 DSH 实例（`~/.dsh/profiles` 只有 `desktop`，每轮结束都复核 `local=401 public=401`）
- 隧道配置（`~/.cloudflared/config.yml`、supervisor、登录自启）
- 所有写操作只发生在隔离实例（`E:\dsh-dev-home`，用完删除）

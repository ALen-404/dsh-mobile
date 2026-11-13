# dsh-mobile

> 非官方 **DSH** 移动客户端（Android）。WebView 保底 + 原生会话/聊天，按凭证分两级能力。

[下载 APK](https://github.com/ALen-404/dsh-mobile/releases) · [文档索引](docs/README.md) · [真机验收清单](docs/device-checklist.md) · [交付状态](docs/status.md)

**这不是官方项目**：与 DeepSeek Harness 官方无关，不代表官方立场。它不内置任何服务器、不代理流量、不收集数据，所有请求都直接发往你自己填的 DSH 地址。

---

## 它解决什么

DSH 是桌面应用。官方 Web UI 在手机上"能用但别扭"：竖屏布局来自第三方适配层、窄屏下侧栏和会话列表容易点不动、切换会话要绕。

这个 App 做两件事：

1. **保底**：全屏 WebView 打开官方 UI，但把移动端该有的细节补齐（诊断、返回键、附件、下载、弹窗、崩溃恢复）。
2. **增强**：用 DSH 的 launch token 换 API 凭证后，**不经过网页**直接原生列出会话、看对话、实时收事件、发消息、传附件。

## 安装

从 [Releases](https://github.com/ALen-404/dsh-mobile/releases) 下载最新的 `dsh-mobile-*.apk`，允许「安装未知来源应用」后安装。

- 要求：Android 5.0+（minSdk 21）
- APK 用 debug 签名，适合自测；不用于上架
- 想自己构建 → 见[构建](#构建)

## 两种模式

| 模式 | 你需要填 | 你得到 |
|---|---|---|
| **A 模式** | 只填 DSH 地址（如 `http://192.168.1.5:3080` 或你的公网域名） | 全屏 WebView 打开官方 UI + 断线诊断 + 多实例管理 |
| **A + B 模式** | 再加一个**启动地址**（带 `?token=`） | 上面全部 **+ 原生会话列表 / 会话详情 / 实时事件 / 原生发消息 / 传附件** |

### 带 token 的地址从哪来

DSH 每次启动时会在终端打印一行：

```
dsh web: http://127.0.0.1:3080/?token=xxxxxxxx
```

把整行复制进实例的「启动地址」即可。**token 每次重启都会变，且不会写进磁盘** —— 这是 DSH 的设计（每进程随机生成），所以没法自动发现，只能手动粘一次。拿不到也不影响 A 模式。

> 桌面版 DSH（Electron）默认不显示这个地址。想用 A + B 模式，需要用命令行启动 DSH。

## 功能

**A 模式（只要地址）**

- 多实例增删改、记住上次打开、首启动引导
- 全屏 WebView：返回键走网页历史、加载进度条、手机/桌面宽度切换
- **断线诊断**：401 / 403 / 404 / 502 / 530 / 连不上，各自给出下一步
  （502 = 隧道通但本地端口没人监听；530 = 隧道没有可用连接器；401 = 正常鉴权）
- **官方 UI 该有的都补齐**：JS `alert/confirm/prompt`、附件选择、下载（自动带 Cookie）、
  `window.open`、渲染进程被回收时自动重建、麦克风/摄像头请求明确拒绝

**A + B 模式（再加启动地址）**

- 原生会话列表（标题/状态/相对时间）、新建会话、会话详情
- 会话投影：权限、工作目录、轮次统计、token 用量、上下文构成
- 对话：历史回填 + 实时增量（按 seq 去重保序；增量渲染不闪烁）
- **Markdown**：代码块、表格、标题、引用、列表（含嵌套）、加粗、斜体、行内代码、链接
- **原生发消息 + 发附件**：图片内联、其它文件先上传；发送失败有明确错误行
- **断线自动重连**：1s→2s→4s…（上限 30s）退避，重连时重新换 token 并补历史
- 工具调用卡片、复制全文

## 隐私与安全

- App **不收集任何数据**，没有统计、没有账号体系
- 带 `?token=` 的地址等同于你 DSH 的控制权：**不要发给别人**，不要把它加到别人的实例上
- 多实例的 Cookie 按**域名**共享（端口不参与隔离）。同域名不同端口的实例可能串登录态，
  App 会在切换时提醒并可一键清理该域名登录态；真正的隔离需要独立进程（未实现）

## 已知限制

- **界面尚未在真机/模拟器上跑过**（开发机没有 adb）。协议层与附件链路已在真实 DSH 上实测，
  但布局、滚动、键盘、点击这些都还没经过真机检验。请照[验收清单](docs/device-checklist.md)验一遍再当主力用
- `assistant/message`（模型**成功**回复）的事件形状未实测（隔离实例没有模型凭据，
  只能拿到失败路径），所以模型输出的渲染是按 schema 兜底实现的
- 原生模式**不能做**：事件回放、原生附件预览、多实例 Cookie 真隔离
- Markdown 是轻量实现：不做 HTML 内嵌、表格对齐语义
- **CI 尚未启用**：工作流已写好放在 `tools/github/`，启用需要给 gh token 加 `workflow` scope，见该目录 README

## 构建

不依赖 Gradle / androidx / Compose，一条命令行流水线（aapt2 → javac → d8 → zipalign → apksigner）。

```powershell
# 一次性准备工具链（JDK 17 + Android build-tools + platform）
./toolchain/fetch-toolchain.ps1

# 构建
./build.ps1
# 产物：dist/dsh-mobile-debug.apk
```

也支持用系统里已有的 JDK / Android SDK（CI 用这条路径）：

```powershell
./build.ps1 -JdkPath "C:\Java\jdk-17" -SdkPath "$env:ANDROID_SDK_ROOT"
./build.ps1 -VersionName "0.2.0" -VersionCode 7     # 注入版本号
```

详见 [docs/build.md](docs/build.md)（含 7 个踩过的坑）。

## 项目结构

```
protocol/          协议层：纯 JDK，零 Android 依赖，可独立编译与自检
  java/            Credential / ApiCookieExchanger / UnaryRpc / DshClient
                   WebSocketClient（手写 RFC6455）/ ObserveEventStream
                   Json / SessionProjection / SessionEvent / TranscriptBuilder
                   MarkdownLite / Base64Lite / Diagnostics / Capabilities …
  *.mjs            JS 侧同构实现 + probe.mjs（CLI 探针）
  test/            一次性探针（m3-*）、端点清单脚本、WS 回显服务器
app/               Android 外壳：纯 framework Views，零 androidx
docs/              设计复盘 / 能力矩阵 / 协议笔记 / 构建踩坑 / 真机清单 / 体检规则
tools/             开发辅助：dev-instance.ps1（隔离实例）、lint-app.ps1（静态检查）
tools/github/      GitHub Actions 工作流（ci / release）
build.ps1          构建脚本
```

## 开发与验证

三件套，都以退出码表达结果，可直接进 CI：

```powershell
powershell -ExecutionPolicy Bypass -File protocol/selftest.ps1   # 协议自检 + WebSocket 端到端
powershell -ExecutionPolicy Bypass -File tools/lint-app.ps1      # 6 条"真机才炸"的静态规则
powershell -ExecutionPolicy Bypass -File build.ps1               # 构建 APK
```

**不需要你自己的 DSH 也能验证协议** —— 用隔离实例（复制已安装 profile 到临时 `DSH_HOME`，
改端口、禁掉不兼容插件后再启动，绝不碰你的实例）：

```powershell
powershell -ExecutionPolicy Bypass -File tools/dev-instance.ps1
# 输出 TOKEN_URL=http://127.0.0.1:21387/?token=...
# 找不到 dsh.cmd 时加 -DshPath "<...>\resources\runtime\cli\bin\dsh.cmd"

node protocol/probe.mjs --token-url "<TOKEN_URL>"       # 只读探针
java -cp build/protocol-selftest com.dshmobile.protocol.LiveSelfTest "<TOKEN_URL>" create  # 写操作，仅隔离实例
powershell -ExecutionPolicy Bypass -File tools/dev-instance.ps1 -Stop   # 清理
```

## 文档

| 文件 | 内容 |
|---|---|
| [docs/README.md](docs/README.md) | 文档索引 + 术语表（先看这个） |
| [docs/design.md](docs/design.md) | 架构为什么长这样 + 立项复盘（当时哪些判断错了） |
| [docs/capabilities.md](docs/capabilities.md) | 能力矩阵（与代码由自检强制一致） |
| [docs/protocol.md](docs/protocol.md) | DSH 协议实测笔记：鉴权/传输/帧/事件/端点/附件 |
| [docs/device-checklist.md](docs/device-checklist.md) | 拿到 APK 后照着点的验收清单 |
| [docs/status.md](docs/status.md) | 已交付 / 已实测 / 尚未验证 三档清单 |
| [docs/build.md](docs/build.md) | 手写构建流水线 + 踩坑记录 |
| [docs/audit.md](docs/audit.md) | 真机问题体检规则 |
| [docs/m0-findings.md](docs/m0-findings.md) | 最早的协议验证（大部分结论已作废，顶部有提示） |

## 许可

[Apache-2.0](LICENSE)

Copyright 2026 dsh-mobile contributors

非官方项目。DSH 的内部 RPC 不是公开契约，随版本变化；本项目按「能探测则增强、否则降级」设计，
不承诺每个 DSH 版本都有原生能力。

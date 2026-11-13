# 真机问题体检清单

> 这份文档记录**什么样的 bug 会通过编译、只在真机上炸**，以及本项目怎么防。
> 规则可以机械检查，脚本是 [`tools/lint-app.ps1`](../tools/lint-app.ps1)。

## 为什么需要它

这个项目刻意不用 Gradle / android-lint（见 [build.md](build.md)），所以没有现成的静态分析。
但我们已经真的踩过下面这几类问题，于是把它们固化成 grep 级检查：

```powershell
powershell -ExecutionPolicy Bypass -File tools/lint-app.ps1
# 5 条规则全过 → exit 0；有任何一条违反 → exit 1
```

## 规则清单

| # | 规则 | 为什么 | 踩过的实例 |
|---|---|---|---|
| 1 | 每个 `*Activity.java` 必须有 `onDestroy`（或进白名单） | WebView / 流 / socket 不拆就是泄漏 | `WebActivity` 起初只销毁了弹窗用的临时 WebView，主 WebView 没销毁 |
| 2 | `new Thread` 只允许出现在 `Task.java` / `Preflight.java` | 各写各的线程 = 必然有人在后台线程碰 View | 曾经有 5 处手写 `new Thread` + `runOnUiThread` |
| 3 | `Task.run` 必须传 Activity（`Task.run(this, …)`） | 用户中途按返回后，回调仍会去碰已销毁的 View | 后台请求完成时页面已被销毁 → 偶发崩溃 |
| 4 | 用 `HttpURLConnection` 的文件必须在 `finally` 里清理 | 异常路径上不 `disconnect` 会漏连接 | `Preflight` 的 `disconnect()` 原本在 try 末尾 |
| 5 | 打开 `ObserveEventStream` 的文件必须能关掉它 | 一条物理连接只服务一条流，不关就是 socket 泄漏 | 收到 `end`/`error` 后原本不关连接 |
| 6 | 创建 WebView 的文件必须实现 `onRenderProcessGone` | API 26+ 上返回 false 会让系统**杀掉整个应用**（用户看到"点开就闪退"） | 低内存机型上渲染进程会被回收 |

白名单不是"豁免"，是**显式声明"这里确实没有长生命周期资源"**，并写上理由。改白名单需要说明为什么。

## 规则之外、但仍然重要的人工检查

这些没法可靠地 grep，review 时要看：

1. **主线程耗时**：`onCreate` / 点击回调里有没有同步网络或大文件 IO
2. **异常吞掉**：`catch (Exception ignored) {}` 是否吞掉了应该提示用户的错误
3. **Context 使用**：长生命周期对象是否持有 Activity（本项目的网络/协议层只用纯 Java 对象，天然规避）
4. **权限**：新增能力是否需要 Manifest 权限（当前只有 `INTERNET` / `ACCESS_NETWORK_STATE`）
5. **WebView 回调线程**：`WebViewClient` / `WebChromeClient` 都在主线程，可以放心改 View

## 已知的"刻意不做"

- 没有 `SwipeRefreshLayout`（需要 androidx）→ 用按钮 + `onResume` 自动刷新代替
- 没有下拉刷新 / 复杂动画 —— 零 androidx 的代价，见 [design.md](design.md) §4 D2
- 下载存应用专属目录而不是公共 `Download` —— 免存储权限，卸载即清理；想要公共目录需要额外权限与用户选择

## 新贡献者上手清单（2026-11-13 实测通过）

模拟"拿到仓库从零开始"，逐条真跑了一遍：

| 检查 | 结果 |
|---|---|
| README 引用的 13 个路径（脚本/文档/LICENSE） | ✅ 全部存在 |
| 6 份 `docs/*.md` 是否都在 README 里被提到 | ✅ 全部有入口 |
| 所有 `.ps1` 的语法解析 | ✅ 4 个脚本全部通过 |
| **缺工具链时**跑 `build.ps1` | ✅ 一行 `ERROR: missing JDK … (run toolchain/fetch-toolchain.ps1 first)`，退出码 1（不再是 PowerShell 栈） |
| 缺 `ws` 包时跑 `probe.mjs --base` | ✅ 预检仍可用；走到 mux 那步才报 `mux 通道需要 ws 包：npm i ws` |
| 从**其它目录**调用 `selftest.ps1` / `lint-app.ps1` / `probe.mjs` | ✅ 都正常（脚本内部用 `$PSScriptRoot`，不依赖 cwd） |
| 没有实例时跑 `dev-instance.ps1 -Stop` | ✅ 幂等，返回 0 |
| 三件套（自检 / 静态检查 / 构建） | ✅ 退出码全 0 |

这轮修掉的偏差：
- `build.ps1` 失败时会打印 PowerShell 栈 → 加 `trap`，只输出一行 `ERROR: …`
- `toolchain/fetch-toolchain.ps1` 同样加了 `trap`（保持一致）

## 死代码 / 未接线功能扫描（2026-11-13）

编译不会报"没人调用的方法"，所以单独扫了一遍：未使用的 import、未被调用的 private/public 成员。

| 发现 | 处理 |
|---|---|
| `LiveSelfTest.extractJsonString` | 已被 `DshClient.parseCreatedSessionId` 取代 → 删除 |
| `InstanceStore.setLastOpened(下标)` / `getLastOpened()` | 改成按 URL 记之后，这套下标存储只写不读 → 连同 `KEY_LAST_OPENED` 一起删除 |
| `Capabilities.explainMissing()` | 写了但没接进 UI → **接线**：没配 API 凭证的实例菜单里加「原生能力怎么开？」 |
| `Diagnostics.shortLabel()` | 只被测试用到 → **接线**：错误面板标题改为具体原因（如「隧道通但本地 DSH 没响应 (502)」），不再是笼统的「连接不上」 |
| 未使用的 import | 0 处 |

**经验**：整理死代码时优先看"写了但没接到 UI 上"的函数 —— 那通常是**功能漏做**，而不是多余代码。

## 判定函数的边界（2026-11-13 补）

两个"看起来一眼就对"的判定函数，实测都有洞：

| 函数 | 原来的实现 | 洞 | 现在 |
|---|---|---|---|
| `hasUsableUrl()` | `startsWith("http://") \|\| startsWith("https://")` | `"http://"` 通过检查，却没有主机 | 解析出主机，主机为空则 false |
| `hasApiCredential()` | `startsWith("http") && contains("token=")` | ① `"httpx://…?token=a"` 被当成有凭证；② `"?mytoken=a"`、`"?token="` 也算 | 严格校验协议 + query 里必须有名字**正好**是 `token` 且值非空的参数 |

这两个函数决定"走 A 模式还是 A+B 模式"，判错的后果是：入口显示出来但一点就报错（或反过来，明明能用却不显示）。
现在有 14 条边界断言覆盖（含大写协议、带端口/路径/锚点、多参数查询串）。

## 怎么在 CI 里用

```powershell
# 三件套：协议自检 + 静态检查 + 构建
powershell -ExecutionPolicy Bypass -File protocol/selftest.ps1     # 断言数见输出 + WebSocket
powershell -ExecutionPolicy Bypass -File tools/lint-app.ps1        # 5 条静态规则
powershell -ExecutionPolicy Bypass -File build.ps1                 # 产出 APK
```

三个脚本都以退出码表达结果（0 = 通过），可以直接串进 CI。

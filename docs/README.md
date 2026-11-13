# 文档索引

> 这个目录记录**为什么这么设计、验证过什么、哪里还没验证**。
> 所有"实测结论"都来自对真实 DSH 的请求，不是推测；推测的地方会明确标注。

## 想做什么 → 读哪份

| 你的目的 | 读这份 |
|---|---|
| 装到手机上试用 | [../README.md](../README.md) → [device-checklist.md](device-checklist.md) |
| 自己构建 APK | [build.md](build.md) |
| 搞清楚 A 模式 / B 模式有什么区别 | [capabilities.md](capabilities.md) |
| 想知道 DSH 的协议长什么样（端点/帧/事件） | [protocol.md](protocol.md) |
| 想改协议层 / DSH 升级后适配 | [protocol.md](protocol.md) + [../protocol/README.md](../protocol/README.md) |
| 想理解架构决策（以及走过哪些弯路） | [design.md](design.md) |
| 想贡献代码、担心踩真机坑 | [audit.md](audit.md) |
| 想看看最早的判定错在哪 | [m0-findings.md](m0-findings.md)（顶部有"结论已作废"提示） |
| 想知道现在到底交付到哪一步、哪些还没验证 | [status.md](status.md) |

## 术语表（统一说法，避免歧义）

| 术语 | 含义 | 别混用 |
|---|---|---|
| **实例** | 用户添加的一台 DSH，含名字 + 地址（+ 可选启动地址） | 不要叫"服务器/主机" |
| **地址** | 实例的访问地址，例如 `https://dsh.example.com`、`http://192.168.1.5:3080` | — |
| **启动地址** | 带 `?token=` 的那行地址，由 `dsh web` 启动时打印；**每次重启都会变** | 不要叫"token URL" |
| **凭证** | 本 App 访问 DSH 用的东西：由启动地址换来的 API Cookie | ⚠️ 不要和"模型凭据"混 |
| **模型凭据** | **DSH 自己**调模型用的 key（本 App 完全不涉及，只在解释"为什么隔离实例拿不到成功回复"时提到） | — |
| **会话** | DSH 里的 Session 实体（`session/list` 返回的一行） | — |
| **对话** | 把一个会话的事件日志折叠出来的消息序列 | 不要用"会话"指对话 |
| **事件日志** | DSH 会话历史的真实形态（`session/page` 的 `records[].event`），不是消息数组 | — |
| **投影** | `session/projections` 返回的 UI 状态（标题/用量/权限…） | — |
| **A 模式** | 只填地址，WebView 承载官方 UI | 不要叫"基础模式""L0"（正文里可解释，但术语统一用 A 模式） |
| **A + B 模式** | 再加启动地址，解锁原生会话/实时/发送。首次出现用全称，**后文可简称「B 模式」** | 不要单独说"B 模式"来指代它属于另一档能力 —— B 是 A 的增强，不是替代 |
| **隔离实例** | `tools/dev-instance.ps1` 起的一次性 DSH（复制 profile + 临时 HOME + 换端口），**绝不碰用户实例** | — |

## 文档清单

| 文件 | 一句话 |
|---|---|
| [design.md](design.md) | 架构为什么长这样 + 立项复盘（当时哪些判断错了） |
| [capabilities.md](capabilities.md) | 能力矩阵（与代码由自检强制一致） |
| [protocol.md](protocol.md) | DSH 协议实测笔记：传输/鉴权/帧格式/事件词汇/端点清单 |
| [build.md](build.md) | 手写构建流水线 + 7 个踩过的坑 |
| [audit.md](audit.md) | 真机问题体检规则（6 条静态检查）+ 上手清单 |
| [device-checklist.md](device-checklist.md) | **真机验收清单**（拿到 APK 后照着点） |
| [m0-findings.md](m0-findings.md) | 最早的 M0 结论（**大部分已作废**，顶部有提示） |
| [status.md](status.md) | 交付状态：已交付 / 已实测 / **尚未验证** 三档清单 |

## 维护约定

1. **不写死会变的数字**：断言数、端点个数、能力项都写成"见脚本输出"，或由脚本/自检校验。
   手写数字必然腐化（这个项目已经因此错过两次）。
2. **文档与代码的一致性尽量机械化**：
   - 能力矩阵 → `SelfTest` 校验 `capabilities.md` 覆盖所有能力名
   - 断言数、端点清单 → 由 `protocol/selftest.ps1` / `protocol/test/dsh-methods.mjs` 输出
3. **结论要标注证据等级**：实测 / 源码文档 / 未验证，三者不要混写在同一句里。
4. **作废的结论不要删**，加显式提示（见 `m0-findings.md` 顶部）—— 错误判断比正确结论更有教育意义。

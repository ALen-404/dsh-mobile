package com.dshmobile.protocol;

/**
 * 协议层自检（不需要 Android、不需要设备、不需要网络）。
 *
 * <p>存在的意义有两个：
 * <ol>
 *   <li>证明协议层确实是**纯 Java**、能被独立编译 —— 这是"protocol 与 app 分离"的硬证据；</li>
 *   <li>把 token 解析这类容易写错的纯逻辑钉住，防止以后改坏。</li>
 * </ol>
 *
 * <p>运行方式见 protocol/README.md：
 * <pre>
 *   javac -d out protocol/java/com/dshmobile/protocol/*.java
 *   java -cp out com.dshmobile.protocol.SelfTest
 * </pre>
 */
public final class SelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // --- token 解析 ---
        Credential c1 = Credential.fromTokenUrl("http://127.0.0.1:3080/?token=abc123", "http://fallback");
        check("解析 token", "abc123".equals(c1.token()));
        check("解析出 base", "http://127.0.0.1:3080".equals(c1.baseUrl));
        check("有凭证", c1.isPresent());
        check("kind=TOKEN_URL", c1.kind == Credential.Kind.TOKEN_URL);

        // --- 无 token 的地址应退化到 NONE ---
        Credential c2 = Credential.fromTokenUrl("http://127.0.0.1:3080/", "http://127.0.0.1:3080");
        check("无 token 时不算 B 模式", !c2.isPresent());

        // --- 空/非法输入 ---
        Credential c3 = Credential.fromTokenUrl("", "https://dsh.example.com");
        check("空输入退化为 A 模式", !c3.isPresent());
        check("空输入保留 base", "https://dsh.example.com".equals(c3.baseUrl));

        // --- 带额外参数与 hash ---
        Credential c4 = Credential.fromTokenUrl(
                "https://dsh.example.com/?interactions=none&token=XYZ%2B1#frag", "https://dsh.example.com");
        check("多参数里取 token", "XYZ+1".equals(c4.token()));
        check("带 hash 也正确", "https://dsh.example.com".equals(c4.baseUrl));

        // --- 能力分级 ---
        Capabilities a = Capabilities.forCredential(Credential.none("http://x"));
        check("A 模式有 webview", a.webview);
        check("A 模式没有原生会话", !a.nativeSession);
        check("A 模式标签", "A 模式".equals(a.label()));

        Capabilities b = Capabilities.forCredential(c1);
        check("B 模式有原生会话", b.nativeSession);
        check("B 模式标签", "A + B 模式".equals(b.label()));
        check("B 模式有原生聊天（历史/实时/发送已实现）", b.nativeChat);

        // --- 换取器在无凭证时应快速失败而不是崩 ---
        ApiCookieExchanger.Result r = ApiCookieExchanger.exchange(Credential.none("http://x"), 1000);
        check("无凭证换取失败", !r.ok);
        check("无凭证给出可执行建议", r.advice != null && r.advice.contains("token"));

        // --- JSON 解析器 ---
        Object node = Json.parse("{\"a\":[1,2,{\"b\":\"x\\u4e2d\"}],\"c\":true,\"d\":null}");
        check("JSON 对象", Json.str(node, "c", "").isEmpty());
        check("JSON 布尔", Json.bool(node, "c", false));
        check("JSON 路径取值", "x中".equals(Json.path(node, "a") instanceof java.util.List
                ? ((java.util.Map<?, ?>) ((java.util.List<?>) Json.get(node, "a")).get(2)).get("b") : null));
        check("JSON null 字段", Json.get(node, "d") == null);

        // --- session/list 解析（载荷形状来自官方 descriptor 的 result schema）---
        String envelope = "{\"type\":\"server-response\",\"rpcId\":\"x\",\"result\":{\"ok\":true,\"value\":{\"items\":["
                + "{\"sessionId\":\"aaaaaaaa-1111\",\"updatedAt\":100,\"running\":true,\"blank\":false,"
                + "\"agentAvailable\":true,\"cwd\":\"E:\\\\workspace\\\\demo\","
                + "\"projections\":{\"kind\":\"sequenced\",\"asOfSeq\":5,\"values\":{\"title\":\"修隧道\"}}},"
                + "{\"sessionId\":\"bbbbbbbb-2222\",\"updatedAt\":300,\"running\":false,\"blank\":true,"
                + "\"agentAvailable\":false,"
                + "\"projections\":{\"kind\":\"cached\",\"asOfSeq\":1,\"values\":{\"title\":null}}},"
                + "{\"sessionId\":\"cccccccc-3333\",\"updatedAt\":200,\"running\":false,\"blank\":false,"
                + "\"agentAvailable\":true,\"cwd\":\"/home/u/proj\","
                + "\"projections\":{\"kind\":\"cached\",\"asOfSeq\":2,\"values\":{\"title\":\"   \"}}}"
                + "]}}}";

        java.util.List<SessionSummary> sessions = SessionSummary.parseResponse(envelope);
        check("解析出 3 个会话", sessions.size() == 3);
        check("按 updatedAt 倒序", sessions.size() == 3
                && "bbbbbbbb-2222".equals(sessions.get(0).sessionId)
                && "cccccccc-3333".equals(sessions.get(1).sessionId)
                && "aaaaaaaa-1111".equals(sessions.get(2).sessionId));
        SessionSummary running = sessions.get(2);
        check("title 优先", "修隧道".equals(running.displayTitle()));
        check("running 徽标", "运行中".equals(running.badge()));
        check("无 title 时退回 sessionId", sessions.get(0).displayTitle().startsWith("bbbbbbbb"));
        check("空白 title 退回 cwd 末段", "proj".equals(sessions.get(1).displayTitle()));
        check("blank 徽标", "空会话".equals(sessions.get(0).badge()));

        // --- 容错：坏 JSON / 空响应不应该抛 ---
        check("坏 JSON 返回空列表", SessionSummary.parseResponse("{not json").isEmpty());
        check("空响应返回空列表", SessionSummary.parseResponse("").isEmpty());
        check("ok:false 返回空列表", SessionSummary.parseResponse(
                "{\"result\":{\"ok\":false,\"error\":{\"code\":\"x\"}}}").isEmpty());

        // --- session/projections 解析（结构来自 docs/protocol.md §9.1 实测）---
        String projectionEnvelope = "{\"type\":\"server-response\",\"rpcId\":\"x\",\"result\":{\"ok\":true,"
                + "\"value\":{\"kind\":\"sequenced\",\"asOfSeq\":42,\"values\":{"
                + "\"title\":\"修隧道\",\"agentPreset\":\"standard\",\"workingDirectory\":\"E:\\\\ws\","
                + "\"sessionStats\":{\"turns\":3,\"steps\":7,\"llmMs\":1500,\"toolMs\":500,\"ttftMs\":200},"
                + "\"tokenUsage\":{\"uncachedInputTokens\":100,\"outputTokens\":50,"
                + "\"cacheReadTokens\":900,\"cacheWriteTokens\":0},"
                + "\"contextBreakdown\":{\"systemTokens\":1000,\"toolsTokens\":200,\"messageTokens\":300},"
                + "\"permissions\":{\"currentValue\":\"danger-full-access\"},"
                + "\"turnOutline\":[{},{}],\"goal\":null}}}}";

        SessionProjection proj = SessionProjection.parse(projectionEnvelope, "session-abc");
        check("投影解析成功", proj != null);
        if (proj != null) {
            check("投影标题", "修隧道".equals(proj.displayTitle()));
            check("投影 asOfSeq", proj.asOfSeq == 42);
            check("投影 turns/steps", proj.turns == 3 && proj.steps == 7);
            check("输入 token = 未缓存 + 缓存读", proj.inputTokens() == 1000);
            check("总 token = 输入 + 输出", proj.totalTokens() == 1050);
            check("权限", "danger-full-access".equals(proj.permission));
            check("轮次大纲条数", proj.turnOutlineCount == 2);
            check("无 goal", !proj.hasGoal);
            check("统计行可渲染", proj.statsLine().contains("3 轮"));
        }
        check("投影坏响应返回 null", SessionProjection.parse("{bad", "s") == null);
        check("投影空响应返回 null", SessionProjection.parse("", "s") == null);

        // --- 模型名（composer 上的 chip 用它）---
        String withModel = "{\"result\":{\"ok\":true,\"value\":{\"asOfSeq\":1,\"values\":{"
                + "\"modelSelection\":{\"lastUsed\":{\"provider\":\"p\",\"model\":\"deepseek-v4.1-flash\"},"
                + "\"next\":null},\"turnOutline\":[]}}}}";
        SessionProjection pm = SessionProjection.parse(withModel, "s");
        check("取 lastUsed 的模型名", pm != null && "deepseek-v4.1-flash".equals(pm.modelName));
        String nextOnly = "{\"result\":{\"ok\":true,\"value\":{\"asOfSeq\":1,\"values\":{"
                + "\"modelSelection\":{\"lastUsed\":null,\"next\":{\"provider\":\"p\",\"model\":\"kimi-k3\"}},"
                + "\"turnOutline\":[]}}}}";
        check("lastUsed 为空时退回 next", "kimi-k3".equals(SessionProjection.parse(nextOnly, "s").modelName));
        String noModel = "{\"result\":{\"ok\":true,\"value\":{\"asOfSeq\":1,\"values\":{"
                + "\"modelSelection\":{\"lastUsed\":null,\"next\":null},\"turnOutline\":[]}}}}";
        check("都没有模型名时为空串", "".equals(SessionProjection.parse(noModel, "s").modelName));

        // --- 会话 ID 前缀处理（DSH 的 id 形如 session-<uuid>）---
        String noTitle = "{\"result\":{\"ok\":true,\"value\":{\"kind\":\"sequenced\",\"asOfSeq\":1,"
                + "\"values\":{\"title\":null,\"turnOutline\":[]}}}}";
        SessionProjection p2 = SessionProjection.parse(noTitle, "session-abcdef123456");
        check("无标题时剥掉 session- 前缀", p2 != null && "abcdef12".equals(p2.displayTitle()));
        String listNoTitle = "{\"result\":{\"ok\":true,\"value\":{\"items\":["
                + "{\"sessionId\":\"session-9f8e7d6c5b4a\",\"updatedAt\":1,\"running\":false,"
                + "\"blank\":false,\"agentAvailable\":true,\"projections\":{\"values\":{\"title\":null}}}]}}}";
        java.util.List<SessionSummary> one = SessionSummary.parseResponse(listNoTitle);
        check("列表里无标题也剥前缀", one.size() == 1 && "9f8e7d6c".equals(one.get(0).displayTitle()));

        // --- 会话事件 → 对话折叠（样本来自真实捕获的事件，见 docs/protocol.md §10）---
        String pageEnvelope = "{\"result\":{\"ok\":true,\"value\":{\"cursor\":21,\"hasMore\":false,\"records\":["
                + "{\"type\":\"event\",\"event\":{\"type\":\"permission/preset\",\"seq\":0,\"time\":1,"
                + "\"data\":{\"preset\":\"danger-full-access\"}}},"
                + "{\"type\":\"event\",\"event\":{\"type\":\"system/message\",\"seq\":7,\"time\":2,"
                + "\"data\":{\"turn\":1,\"step\":1,\"message\":{\"role\":\"system\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"You are an AI agent\"}]}}}},"
                + "{\"type\":\"event\",\"event\":{\"type\":\"user/message\",\"seq\":8,\"time\":3,"
                + "\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"hello probe\"}],"
                + "\"source\":{\"kind\":\"user\",\"rpcId\":\"probe-1\"},\"role\":\"user\",\"id\":\"m1\"},"
                + "\"surfaceOp\":\"append\"}},"
                + "{\"type\":\"event\",\"event\":{\"type\":\"user/message\",\"seq\":9,\"time\":4,"
                + "\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"<hindsight_knowledge>…</hindsight_knowledge>\"}],"
                + "\"source\":{\"kind\":\"context\"},\"role\":\"user\",\"id\":\"m-ctx\"}}},"
                + "{\"type\":\"event\",\"event\":{\"type\":\"session/title\",\"seq\":17,\"time\":4,"
                + "\"data\":{\"title\":\"hello probe\",\"messageSeqs\":[8],\"source\":{\"kind\":\"fallback\"}}}},"
                + "{\"type\":\"event\",\"event\":{\"type\":\"assistant/attempt\",\"seq\":18,\"time\":5,"
                + "\"data\":{\"turn\":1,\"step\":1,\"stream\":[{\"type\":\"chunk\",\"chunk\":{\"type\":\"finish\","
                + "\"reason\":{\"kind\":\"error\",\"failure\":{\"message\":\"no adapter registered\",\"code\":\"NO_ADAPTER\"}}}}]}}},"
                + "{\"type\":\"event\",\"event\":{\"type\":\"turn/end\",\"seq\":20,\"time\":6,"
                + "\"data\":{\"turn\":1,\"reason\":{\"kind\":\"error\","
                + "\"error\":{\"message\":\"no adapter registered\",\"code\":\"NO_ADAPTER\"}}}}},"
                + "{\"type\":\"event\",\"event\":{\"type\":\"step/end\",\"seq\":19,\"time\":7,"
                + "\"data\":{\"turn\":1,\"step\":1}}}"
                + "]}}}";

        java.util.List<SessionEvent> events = SessionEvent.parsePage(pageEnvelope);
        check("解析出 8 条事件", events.size() == 8);
        SessionTranscript transcript = SessionTranscript.fold(events);
        check("折叠出标题", "hello probe".equals(transcript.title));
        check("系统消息被计数而非渲染", transcript.systemMessages == 1);
        check("上下文注入被过滤", transcript.injectedContext == 1);
        check("对话条目 = 用户 + 错误", transcript.entries.size() == 2);
        if (transcript.entries.size() == 2) {
            check("第一条是用户消息", SessionTranscript.ROLE_USER.equals(transcript.entries.get(0).role)
                    && "hello probe".equals(transcript.entries.get(0).text));
            check("第二条是错误且带错误码", transcript.entries.get(1).isError()
                    && transcript.entries.get(1).text.contains("NO_ADAPTER"));
            check("同一错误去重（turn/end 不重复记一条）",
                    transcript.entries.stream().filter(e -> e.isError()).count() == 1);
        }
        check("事件解析坏响应返回空", SessionEvent.parsePage("{bad").isEmpty());

        // --- 增量合并（先历史、再推送；重叠 seq 要去重）---
        TranscriptBuilder builder = new TranscriptBuilder();
        int addedFirst = builder.apply(events); // 历史：user(8) + error(18)
        check("首次合并新增 2 条", addedFirst == 2);
        String laterPage = "{\"result\":{\"value\":{\"records\":["
                + "{\"type\":\"event\",\"event\":{\"type\":\"user/message\",\"seq\":30,\"time\":9,"
                + "\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"第二条\"}],"
                + "\"source\":{\"kind\":\"user\"},\"role\":\"user\",\"id\":\"m2\"}}},"
                + "{\"type\":\"event\",\"event\":{\"type\":\"user/message\",\"seq\":8,\"time\":3,"
                + "\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"hello probe\"}],"
                + "\"source\":{\"kind\":\"user\"},\"role\":\"user\",\"id\":\"m1\"}}}"
                + "]}}}";
        int addedSecond = builder.apply(SessionEvent.parsePage(laterPage));

        check("增量合并只加新的 1 条（seq 8 已见过不重复）", addedSecond == 1);
        SessionTranscript merged = builder.build();
        check("合并后共 3 条", merged.entries.size() == 3);
        check("增量追加在末尾", "第二条".equals(merged.entries.get(2).text));
        check("lastSeq 前进到 30", merged.lastSeq == 30 && builder.lastSeq() == 30);

        // --- 事件流的帧构造与解析 ---
        String openFrame = ObserveEventStream.buildOpenFrame("sid-1", "session-abc");
        check("open 帧含端点与 sessionId",
                openFrame.contains("session/observeEvent") && openFrame.contains("session-abc")
                        && openFrame.contains("\"type\":\"open\""));
        ObserveEventStream.Frame ready = ObserveEventStream.parseFrame(
                "{\"type\":\"item\",\"streamId\":\"sid-1\",\"value\":{\"type\":\"ready\"}}", "sid-1");
        check("解析 ready 帧", ready != null && "ready".equals(ready.valueType));
        ObserveEventStream.Frame eventFrame = ObserveEventStream.parseFrame(
                "{\"type\":\"item\",\"streamId\":\"sid-1\",\"value\":{\"type\":\"event\","
                        + "\"event\":{\"type\":\"turn/start\",\"seq\":4,\"time\":5,\"data\":{\"turn\":1}}}}",
                "sid-1");
        check("解析业务事件帧", eventFrame != null && eventFrame.event != null
                && "turn/start".equals(eventFrame.event.type) && eventFrame.event.seq == 4);
        check("别的流的帧被忽略", ObserveEventStream.parseFrame(
                "{\"type\":\"item\",\"streamId\":\"other\",\"value\":{\"type\":\"ready\"}}", "sid-1") == null);
        ObserveEventStream.Frame errFrame = ObserveEventStream.parseFrame(
                "{\"type\":\"error\",\"streamId\":\"sid-1\",\"error\":{\"code\":\"x\",\"message\":\"boom\",\"details\":{}}}",
                "sid-1");
        check("解析错误帧", errFrame != null && "boom".equals(errFrame.errorMessage));

        // --- 工具调用卡片（形状来自官方 bundle：tool/call{turn,callId,name,arguments}）---
        String toolPage = "{\"result\":{\"value\":{\"records\":["
                + "{\"type\":\"event\",\"event\":{\"type\":\"tool/call\",\"seq\":40,\"time\":10,"
                + "\"data\":{\"turn\":1,\"callId\":\"c1\",\"name\":\"read_file\","
                + "\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}},"
                + "{\"type\":\"event\",\"event\":{\"type\":\"tool/result\",\"seq\":41,\"time\":11,"
                + "\"data\":{\"message\":{\"toolCallId\":\"c1\",\"isError\":false}}}}"
                + "]}}}";
        TranscriptBuilder toolBuilder = new TranscriptBuilder();
        toolBuilder.apply(SessionEvent.parsePage(toolPage));
        SessionTranscript withTool = toolBuilder.build();
        check("工具调用折叠成一条", withTool.entries.size() == 1);
        if (withTool.entries.size() == 1) {
            SessionTranscript.Entry toolEntry = withTool.entries.get(0);
            check("工具条目带名字", toolEntry.isTool() && "read_file".equals(toolEntry.toolName));
            check("工具条目带参数", toolEntry.text.contains("a.txt"));
            check("tool/result 回填状态 done", "done".equals(toolEntry.toolStatus));
        }
        String toolErrPage = "{\"result\":{\"value\":{\"records\":["
                + "{\"type\":\"event\",\"event\":{\"type\":\"tool/call\",\"seq\":50,\"time\":12,"
                + "\"data\":{\"turn\":1,\"callId\":\"c9\",\"name\":\"bash\",\"arguments\":\"{}\"}}},"
                + "{\"type\":\"event\",\"event\":{\"type\":\"tool/result\",\"seq\":51,\"time\":13,"
                + "\"data\":{\"message\":{\"toolCallId\":\"c9\",\"isError\":true}}}}"
                + "]}}}";
        TranscriptBuilder errBuilder = new TranscriptBuilder();
        errBuilder.apply(SessionEvent.parsePage(toolErrPage));
        SessionTranscript withErrTool = errBuilder.build();
        check("失败的 tool/result 标记 error",
                !withErrTool.entries.isEmpty() && "error".equals(withErrTool.entries.get(0).toolStatus));

        // --- JSON 转义 + session/prompt 参数构造（发消息必须正确转义）---
        String nasty = "he said \"hi\"\nline2\\back\ttab";
        String escaped = Json.escape(nasty);
        check("转义处理了引号", escaped.contains("\\\""));
        Object rtNode = Json.parse("{\"v\":\"" + escaped + "\"}");
        check("转义后可被解析回来", nasty.equals(Json.str(rtNode, "v", "")));
        String promptArgs = DshClient.promptArgs("session-1", "req-1", nasty);
        Object paNode = Json.parse(promptArgs);
        check("promptArgs 可被解析", paNode != null);
        Object requestNode = Json.path(paNode, "request");
        check("promptArgs 的 sessionId 与 mode",
                "session-1".equals(Json.str(requestNode, "sessionId", ""))
                        && "queue".equals(Json.str(requestNode, "mode", "")));
        java.util.List<Object> content = Json.list(requestNode, "content");
        check("promptArgs 的 content 是一个文本片段", content.size() == 1
                && "text".equals(Json.str(content.get(0), "type", ""))
                && nasty.equals(Json.str(content.get(0), "text", "")));

        // --- session/create 的响应解析（真实响应形状）---
        check("解析 create 的 sessionId", "session-2a8f7a07-555b-44dd-8feb-2ad6093082e7".equals(
                DshClient.parseCreatedSessionId(
                        "{\"type\":\"server-response\",\"rpcId\":\"x\",\"result\":{\"ok\":true,"
                                + "\"value\":{\"sessionId\":\"session-2a8f7a07-555b-44dd-8feb-2ad6093082e7\","
                                + "\"agentPreset\":\"standard\"}}}")));
        check("create 响应缺 sessionId 时返回空", DshClient.parseCreatedSessionId(
                "{\"result\":{\"ok\":true,\"value\":{\"agentPreset\":\"standard\"}}}").isEmpty());
        check("create 响应坏 JSON 时返回空", DshClient.parseCreatedSessionId("{oops").isEmpty());
        check("create 响应 null 时返回空", DshClient.parseCreatedSessionId(null).isEmpty());

        // --- Markdown-lite：只认围栏代码块 ---
        java.util.List<MarkdownLite.Block> plain = MarkdownLite.parse("普通的一段话\n第二行");
        check("无代码块时是一整块文本", plain.size() == 1 && !plain.get(0).isCode()
                && plain.get(0).text.contains("第二行"));

        String withCode = "说明：\n```java\nint a = 1;\nint b = 2;\n```\n结束";
        java.util.List<MarkdownLite.Block> blocks = MarkdownLite.parse(withCode);
        check("文本+代码+文本 = 3 块", blocks.size() == 3);
        check("代码块识别正确", blocks.size() == 3 && blocks.get(1).isCode()
                && "java".equals(blocks.get(1).lang)
                && blocks.get(1).text.contains("int a = 1;"));
        check("代码块前后的文本都在", blocks.size() == 3
                && blocks.get(0).text.contains("说明") && blocks.get(2).text.contains("结束"));

        check("未闭合围栏当代码块", MarkdownLite.parse("开始\n```\nstill typing")
                .get(Math.max(0, MarkdownLite.parse("开始\n```\nstill typing").size() - 1)).isCode());
        check("~~~ 围栏也认", MarkdownLite.countCodeBlocks("```\na\n```\n~~~\nb\n~~~") == 2);
        check("空文本返回空列表", MarkdownLite.parse("").isEmpty());
        check("null 返回空列表", MarkdownLite.parse(null).isEmpty());
        check("纯代码块", MarkdownLite.parse("```\nonly code\n```").size() == 1
                && MarkdownLite.parse("```\nonly code\n```").get(0).isCode());
        check("代码块内的 ``` 之外的行原样保留", MarkdownLite.parse("```\n  indented\n\nblank\n```")
                .get(0).text.startsWith("  indented"));

        // --- Markdown 块级：标题 / 引用 / 列表 / 表格 ---
        java.util.List<MarkdownLite.Block> headed = MarkdownLite.parse("# 一级\n## 二级\n正文");
        check("标题层级", headed.size() == 3
                && "heading".equals(headed.get(0).type) && headed.get(0).level == 1
                && "一级".equals(headed.get(0).text)
                && headed.get(1).level == 2);
        check("标题后的正文仍是段落", "text".equals(headed.get(2).type) && "正文".equals(headed.get(2).text));

        java.util.List<MarkdownLite.Block> quoted = MarkdownLite.parse("> 第一行\n> 第二行\n\n正文");
        check("多行引用合并成一块", "quote".equals(quoted.get(0).type)
                && quoted.get(0).text.contains("第一行") && quoted.get(0).text.contains("第二行"));
        check("引用后面的正文", "text".equals(quoted.get(1).type));

        java.util.List<MarkdownLite.Block> listed = MarkdownLite.parse("- 甲\n- 乙\n  - 乙一\n1. 丙");
        check("无序列表项数", listed.size() == 4);
        check("无序列表标记", listed.size() == 4 && listed.get(0).isList()
                && !listed.get(0).ordered && "甲".equals(listed.get(0).text));
        check("缩进识别为第二层", listed.size() == 4 && listed.get(2).level == 2);
        check("有序列表识别", listed.size() == 4 && listed.get(3).ordered && "丙".equals(listed.get(3).text));

        String tableText = "| 名称 | 值 |\n| --- | --- |\n| a | 1 |\n| b | 2 |";
        java.util.List<MarkdownLite.Block> tab = MarkdownLite.parse(tableText);
        check("表格解析成一块", tab.size() == 1 && tab.get(0).isTable());
        check("表格行数（表头+2）", tab.size() == 1 && tab.get(0).rows.size() == 3);
        check("表格单元格内容", tab.size() == 1
                && "名称".equals(tab.get(0).rows.get(0).get(0))
                && "2".equals(tab.get(0).rows.get(2).get(1)));
        check("没有分隔行时不当表格", MarkdownLite.parse("a | b\nc | d").size() == 1
                && !MarkdownLite.parse("a | b\nc | d").get(0).isTable());
        check("无外框竖线的表格", MarkdownLite.parse("a | b\n--- | ---\n1 | 2")
                .get(0).isTable());

        // --- Markdown 行内 ---
        java.util.List<MarkdownLite.Inline> inl = MarkdownLite.parseInline("前 **粗** 中 *斜* `码` [链](https://e.x) 后");
        check("行内片段数", inl.size() == 9);
        check("行内加粗", inl.size() == 9 && "bold".equals(inl.get(1).type) && "粗".equals(inl.get(1).text));
        check("行内斜体", inl.size() == 9 && "italic".equals(inl.get(3).type));
        check("行内代码", inl.size() == 9 && "code".equals(inl.get(5).type) && "码".equals(inl.get(5).text));
        check("行内链接与目标", inl.size() == 9 && "link".equals(inl.get(7).type)
                && "链".equals(inl.get(7).text) && "https://e.x".equals(inl.get(7).url));
        check("行内代码里的星号不当强调",
                MarkdownLite.parseInline("`a*b*c`").size() == 1
                        && "code".equals(MarkdownLite.parseInline("`a*b*c`").get(0).type));
        check("未闭合强调当普通文本", MarkdownLite.parseInline("三个星号 ** 未闭合").size() >= 1);
        check("空行内文本返回空列表", MarkdownLite.parseInline("").isEmpty());

        // --- 用户消息里的附件如何显示（事件日志里是 attachment 对象）---
        String msgWithFile = "{\"result\":{\"value\":{\"records\":["
                + "{\"type\":\"event\",\"event\":{\"type\":\"user/message\",\"seq\":1,\"time\":1,"
                + "\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"看看这个\"},"
                + "{\"type\":\"file\",\"attachment\":{\"name\":\"报表.xlsx\",\"bytes\":39}}],"
                + "\"source\":{\"kind\":\"user\"},\"role\":\"user\"}}},"
                + "{\"type\":\"event\",\"event\":{\"type\":\"user/message\",\"seq\":2,\"time\":2,"
                + "\"data\":{\"content\":[{\"type\":\"image\",\"name\":\"shot.png\"}],"
                + "\"source\":{\"kind\":\"user\"},\"role\":\"user\"}}}"
                + "]}}}";
        SessionTranscript withFiles = SessionTranscript.fold(SessionEvent.parsePage(msgWithFile));
        check("带附件用户消息折叠成功", withFiles.entries.size() == 2);
        check("文件片段显示为附件名", withFiles.entries.size() == 2
                && withFiles.entries.get(0).text.contains("看看这个")
                && withFiles.entries.get(0).text.contains("[附件: 报表.xlsx]"));
        check("图片片段显示为图片", withFiles.entries.size() == 2
                && withFiles.entries.get(1).text.contains("[图片: shot.png]"));

        // --- 导出纯文本（「复制全文」的格式）---
        String exported = withTool.toPlainText();
        check("导出文本含工具块", exported.contains("【工具 · read_file】")
                && exported.contains("a.txt"));
        String exportedChat = transcript.toPlainText();
        check("导出文本带标题", exportedChat.startsWith("# hello probe"));
        check("导出文本含用户与错误段", exportedChat.contains("【你】\nhello probe")
                && exportedChat.contains("【错误】\nno adapter registered"));
        check("空对话导出为空串", SessionTranscript
                .fold(java.util.Collections.<SessionEvent>emptyList()).toPlainText().isEmpty());

        // --- 诊断文案（协议知识，App 与探针共用）---
        check("401 说明是正常鉴权", Diagnostics.explain(401, null).contains("需要鉴权")
                && Diagnostics.explain(401, null).contains("正常现象"));
        check("403 提到配对/姿态探测", Diagnostics.explain(403, null).contains("配对"));
        check("404 提到路径", Diagnostics.explain(404, null).contains("路径"));
        check("502 解释为隧道通但端口没人监听",
                Diagnostics.explain(502, null).contains("没人监听"));
        check("530 解释为隧道没连上", Diagnostics.explain(530, null).contains("隧道进程没在跑"));
        check("0 提示绑定地址", Diagnostics.explain(0, null).contains("127.0.0.1"));
        check("200 显示正常", Diagnostics.explain(200, null).contains("正常"));
        check("未知状态码带出数字", Diagnostics.explain(418, null).contains("418"));
        check("诊断文本会附上地址",
                Diagnostics.explain(502, "https://x.example/").endsWith("https://x.example/"));
        check("短标签覆盖常见码", Diagnostics.shortLabel(401).contains("401")
                && Diagnostics.shortLabel(502).contains("502")
                && Diagnostics.shortLabel(0).contains("连不上"));
        check("一行建议非空", !Diagnostics.advice(401).isEmpty()
                && !Diagnostics.advice(502).isEmpty()
                && !Diagnostics.advice(530).isEmpty());

        // --- 能力矩阵：A 模式全可用、B 模式看凭证 ---
        Capabilities plainCaps = Capabilities.forCredential(Credential.none("http://x:1"));
        Capabilities apiCaps = Capabilities.forCredential(
                Credential.fromTokenUrl("http://x:1/?token=abc", "http://x:1"));
        check("A 模式标签", "A 模式".equals(plainCaps.label()));
        check("A+B 模式标签", "A + B 模式".equals(apiCaps.label()));
        check("无凭证时不够原生能力", !plainCaps.nativeSession && !plainCaps.nativeChat);
        check("有凭证时具备原生能力", apiCaps.nativeSession && apiCaps.nativeChat);

        String[][] plainRows = plainCaps.matrixRows();
        String[][] apiRows = apiCaps.matrixRows();
        boolean aOk = true;
        boolean bOkPlain = true;
        for (String[] row : plainRows) {
            if ("A 模式".equals(row[0]) && !"是".equals(row[2])) {
                aOk = false;
            }
            if ("A + B 模式".equals(row[0]) && !"否".equals(row[2])) {
                bOkPlain = false;
            }
        }
        check("A 模式的能力都可用", aOk);
        check("无凭证时 B 模式能力都不可用", bOkPlain);
        boolean bOkApi = true;
        for (String[] row : apiRows) {
            if ("A + B 模式".equals(row[0]) && !"是".equals(row[2])) {
                bOkApi = false;
            }
        }
        check("有凭证时 B 模式能力都可用", bOkApi);

        // --- 文档一致性：能力矩阵里的每个能力名都必须出现在 docs/capabilities.md ---
        String repo = System.getProperty("dsh.repo");
        if (repo != null && !repo.isEmpty()) {
            java.io.File doc = new java.io.File(repo, "docs/capabilities.md");
            check("docs/capabilities.md 存在", doc.isFile());
            if (doc.isFile()) {
                String text;
                try {
                    text = new String(java.nio.file.Files.readAllBytes(doc.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    text = "";
                }
                check("文档非空", !text.isEmpty());
                StringBuilder missing = new StringBuilder();
                for (String[] row : apiRows) {
                    if (!text.contains(row[1])) {
                        missing.append(row[1]).append("；");
                    }
                }
                check("文档覆盖了所有能力（缺：" + (missing.length() == 0 ? "无" : missing.toString()) + "）",
                        missing.length() == 0);
                check("文档提到两种模式", text.contains("A 模式") && text.contains("A + B 模式"));
            }
        } else {
            check("跳过文档一致性校验（未设置 -Ddsh.repo）", true);
        }

        // --- 下载文件名推断（纯逻辑，原本只能真机试）---
        check("URL 最后一段作文件名", "report.pdf".equals(
                FileName.guess("https://x/api/download/report.pdf?token=1", null)));
        check("URL 里的中文百分号编码被还原", "会话导出.json".equals(
                FileName.guess("https://x/%E4%BC%9A%E8%AF%9D%E5%AF%BC%E5%87%BA.json", null)));
        check("Content-Disposition 带引号", "a b.txt".equals(
                FileName.guess("https://x/dl", "attachment; filename=\"a b.txt\"")));
        check("RFC5987 filename* 优先", "中文名.md".equals(
                FileName.guess("https://x/dl", "attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87%E5%90%8D.md")));
        check("去掉路径穿越", "passwd".equals(
                FileName.guess("https://x/../../etc/passwd", null)));
        check("无法推断时给 download", "download".equals(FileName.guess("https://x/", null)));
        check("按 MIME 补扩展名", "abc.pdf".equals(FileName.ensureExtension("abc", "application/pdf")));
        check("已有扩展名不重复补", "abc.zip".equals(FileName.ensureExtension("abc.zip", "application/pdf")));
        check("未知 MIME 不补", "abc".equals(FileName.ensureExtension("abc", "application/x-weird")));
        check("json 扩展名映射", "json".equals(FileName.extensionFor("application/json; charset=utf-8")));

        // --- 实例持久化（会丢用户数据的地方，之前完全没有测试）---
        java.util.List<InstanceRecord> recs = new java.util.ArrayList<>();
        recs.add(new InstanceRecord("家里 NAS", "http://192.168.1.12:3080", ""));
        recs.add(new InstanceRecord("带\"引号\"和\\反斜杠",
                "https://dsh.example.com/path?x=1&y=2",
                "http://127.0.0.1:3080/?token=abc123"));
        String encoded = InstanceRecord.encodeAll(recs);
        java.util.List<InstanceRecord> back = InstanceRecord.parseAll(encoded);
        check("实例记录往返数量一致", back.size() == 2);
        if (back.size() == 2) {
            check("名字原样往返（含中文）", "家里 NAS".equals(back.get(0).name));
            check("地址原样往返（含查询参数）", "https://dsh.example.com/path?x=1&y=2".equals(back.get(1).url));
            check("名字里的引号与反斜杠不破坏 JSON", "带\"引号\"和\\反斜杠".equals(back.get(1).name));
            check("token 地址往返", "http://127.0.0.1:3080/?token=abc123".equals(back.get(1).tokenUrl));
            check("凭证判定", !back.get(0).hasApiCredential() && back.get(1).hasApiCredential());
            check("地址形状判定", back.get(0).hasUsableUrl() && !new InstanceRecord("x", "", "").hasUsableUrl());
        }
        check("空列表编码成 []", "[]".equals(InstanceRecord.encodeAll(new java.util.ArrayList<InstanceRecord>())));

        // --- 配对设备（remote-web-ui 插件）：粘贴配对链接即可用原生能力 ---
        check("认出配对链接", PairCookie.looksLikePairUrl(
                "https://dsh2.earthonline.bar/pair-accept?pair=411f80c8"));
        check("token 地址不当配对链接", !PairCookie.looksLikePairUrl(
                "http://127.0.0.1:3080/?token=abc"));
        check("从整段文字里提取配对链接",
                "https://h.example.com/pair-accept?pair=abc".equals(PairCookie.extractPairUrl(
                        "在浏览器打开 https://h.example.com/pair-accept?pair=abc 就行")));
        check("配对链接尾巴的中文句号要剪掉",
                "https://h.example.com/pair-accept?pair=abc".equals(PairCookie.extractPairUrl(
                        "https://h.example.com/pair-accept?pair=abc。")));
        check("没有配对链接返回 null", PairCookie.extractPairUrl("随便一段话") == null);
        check("从 Cookie 头取设备 id",
                "53e608b386ccaee5".equals(PairCookie.deviceFromCookie(
                        "a=1; dsh_pair=53e608b386ccaee5; b=2")));
        check("设备 cookie 为空时返回空串", PairCookie.deviceFromCookie("dsh_pair=").isEmpty());
        check("没有设备 cookie 返回空串", PairCookie.deviceFromCookie("x=1").isEmpty());
        check("null Cookie 头返回空串", PairCookie.deviceFromCookie(null).isEmpty());

        // 配对实例：有 url + pairDevice 就算配了凭证（不需要 tokenUrl）
        InstanceRecord paired = new InstanceRecord("公网", "https://dsh.example.com", "", "dev-123");
        check("配对实例算有凭证", paired.hasApiCredential());
        java.util.List<InstanceRecord> pairedBack =
                InstanceRecord.parseAll(InstanceRecord.encodeAll(java.util.Collections.singletonList(paired)));
        check("配对设备能持久化", pairedBack.size() == 1
                && "dev-123".equals(pairedBack.get(0).pairDevice)
                && pairedBack.get(0).hasApiCredential());
        check("没有 pairDevice 的旧记录仍可解析", InstanceRecord.parseAll(
                "[{\"name\":\"x\",\"url\":\"http://h:1\",\"tokenUrl\":\"\"}]").get(0).pairDevice.isEmpty());
        check("null 列表编码成 []", "[]".equals(InstanceRecord.encodeAll(null)));
        check("坏 JSON 返回空列表", InstanceRecord.parseAll("{not json").isEmpty());
        check("不是数组返回空列表", InstanceRecord.parseAll("{\"a\":1}").isEmpty());
        check("空文本返回空列表", InstanceRecord.parseAll("").isEmpty());
        check("数组里的坏元素被跳过",
                InstanceRecord.parseAll("[1,\"x\",{\"name\":\"ok\",\"url\":\"http://a\"}]").size() == 1);
        check("缺字段时给默认值", "DSH".equals(InstanceRecord.parseAll("[{}]").get(0).name));

        // --- 地址/凭证判定的边界（决定"走 A 模式还是 A+B 模式"）---
        check("正常 http 地址可用", new InstanceRecord("a", "http://192.168.1.5:3080", "").hasUsableUrl());
        check("https 可用", new InstanceRecord("a", "https://dsh.example.com/x?y=1", "").hasUsableUrl());
        check("大写协议也算", new InstanceRecord("a", "HTTP://host:1", "").hasUsableUrl());
        check("只有协议头不算可用", !new InstanceRecord("a", "http://", "").hasUsableUrl());
        check("协议头带斜杠但无主机不算", !new InstanceRecord("a", "https:///path", "").hasUsableUrl());
        check("没有协议不算可用", !new InstanceRecord("a", "192.168.1.5:3080", "").hasUsableUrl());
        check("空地址不可用", !new InstanceRecord("a", "", "").hasUsableUrl());

        check("标准 token 地址算有凭证",
                new InstanceRecord("a", "http://h:1", "http://h:1/?token=abc").hasApiCredential());
        check("token 在查询串中间也算",
                new InstanceRecord("a", "http://h:1", "http://h:1/?x=1&token=abc&y=2").hasApiCredential());
        check("token 值为空不算凭证",
                !new InstanceRecord("a", "http://h:1", "http://h:1/?token=").hasApiCredential());
        check("名字相近的参数不算凭证",
                !new InstanceRecord("a", "http://h:1", "http://h:1/?mytoken=abc").hasApiCredential());
        check("协议不完整不算凭证",
                !new InstanceRecord("a", "http://h:1", "httpx://h/?token=abc").hasApiCredential());
        check("没有查询串不算凭证",
                !new InstanceRecord("a", "http://h:1", "http://h:1/").hasApiCredential());

        // --- Base64（自实现，为了兼容 minSdk 21；用已知向量校验）---
        check("Base64 编码空串", "".equals(Base64Lite.encode(new byte[0])));
        check("Base64 编码 'f'", "Zg==".equals(Base64Lite.encode("f".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        check("Base64 编码 'fo'", "Zm8=".equals(Base64Lite.encode("fo".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        check("Base64 编码 'foo'", "Zm9v".equals(Base64Lite.encode("foo".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        check("Base64 编码 'foob'", "Zm9vYg==".equals(Base64Lite.encode("foob".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        check("Base64 编码 'fooba'", "Zm9vYmE=".equals(Base64Lite.encode("fooba".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        check("Base64 编码 'foobar'", "Zm9vYmFy".equals(Base64Lite.encode("foobar".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        check("Base64 往返（含中文与二进制）", java.util.Arrays.equals(
                "中文🙂\u0001\u00FF".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Base64Lite.decode(Base64Lite.encode(
                        "中文🙂\u0001\u00FF".getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        check("Base64 解码忽略换行", "foobar".equals(new String(Base64Lite.decode("Zm9v\nYmFy"),
                java.nio.charset.StandardCharsets.UTF_8)));

        // --- 上传响应解析（实测形状）---
        String uploadBody = "{\"ok\":true,\"value\":{\"receiptId\":\"9ef0df49-7272-4b71\","
                + "\"file\":{\"attachmentId\":\"sha256:27d5\",\"name\":\"probe.txt\",\"bytes\":39}}}";
        check("解析上传 receiptId", "9ef0df49-7272-4b71".equals(DshClient.parseUploadReceiptId(uploadBody)));
        check("上传响应缺 receiptId 返回空", DshClient.parseUploadReceiptId("{\"ok\":true,\"value\":{}}").isEmpty());
        check("上传响应坏 JSON 返回空", DshClient.parseUploadReceiptId("{oops").isEmpty());

        // --- 附件载荷构造（prompt content）---
        Object filePartNode = Json.parse(DshClient.filePart("r-1"));
        check("文件片段结构", "file".equals(Json.str(filePartNode, "type", ""))
                && "r-1".equals(Json.str(filePartNode, "receiptId", "")));
        Object imagePartNode = Json.parse(DshClient.imagePart("image/png", "iVBORw0KGgo=", "a.png"));
        check("图片片段结构（内联 base64）", "image".equals(Json.str(imagePartNode, "type", ""))
                && "image/png".equals(Json.str(imagePartNode, "mediaType", ""))
                && "iVBORw0KGgo=".equals(Json.str(imagePartNode, "data", ""))
                && "a.png".equals(Json.str(imagePartNode, "name", "")));

        java.util.List<String> parts = new java.util.ArrayList<>();
        parts.add(DshClient.filePart("r-1"));
        Object withAttach = Json.parse(DshClient.promptArgs("s-1", "q-1", "看看这个", parts));
        java.util.List<Object> attachContent = Json.list(Json.path(withAttach, "request"), "content");
        check("带附件时 content 有两个片段", attachContent.size() == 2
                && "text".equals(Json.str(attachContent.get(0), "type", ""))
                && "file".equals(Json.str(attachContent.get(1), "type", "")));
        Object onlyAttach = Json.parse(DshClient.promptArgs("s-1", "q-1", "  ", parts));
        check("只有附件时也能构造（文本为空跳过）", Json.list(Json.path(onlyAttach, "request"), "content").size() == 1);

        // --- 查询参数编码 ---
        check("urlEncode 保留安全字符", "abc-._~123".equals(DshClient.urlEncode("abc-._~123")));
        check("urlEncode 编码空格与中文", "a%20b%20%E4%B8%AD".equals(DshClient.urlEncode("a b 中")));

        // --- 粘贴容错：从终端输出里提取启动地址 ---
        check("从 dsh 输出行提取",
                "http://127.0.0.1:3080/?token=abc123".equals(
                        TokenUrl.extract("dsh web: http://127.0.0.1:3080/?token=abc123")));
        check("纯地址直接提取",
                "http://192.168.1.5:3080/?token=xyz".equals(
                        TokenUrl.extract("http://192.168.1.5:3080/?token=xyz")));
        check("带引号也能提取",
                "https://dsh.example.com/?token=abc".equals(
                        TokenUrl.extract("  \"https://dsh.example.com/?token=abc\"  ")));
        check("带额外参数保留完整",
                "http://h:1/?token=a&interactions=none".equals(
                        TokenUrl.extract("http://h:1/?token=a&interactions=none")));
        check("token 为空不算", TokenUrl.extract("http://h:1/?token=") == null);
        check("没有 token 不算", TokenUrl.extract("http://h:1/") == null);
        check("没有地址返回 null", TokenUrl.extract("随便一段话") == null);
        check("null 返回 null", TokenUrl.extract(null) == null);
        check("token 打码", TokenUrl.mask("http://h:1/?token=abcdefghijkl")
                .contains("abcd\u2026kl")
                || TokenUrl.mask("http://h:1/?token=abcdefghijkl").contains("abcd…kl"));
        check("打码后仍保留主机", TokenUrl.mask("http://h:1/?token=abcdefghijkl").startsWith("http://h:1/?token="));

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("协议层自检通过 ✅");
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            failed++;
            System.out.println("  ❌ " + name);
        }
    }
}

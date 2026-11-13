package com.dshmobile.protocol;

/**
 * 对**真实 DSH 实例**的联调自检（需要一台已启动、带 token 的 DSH）。
 *
 * <p>与 {@link SelfTest}（纯逻辑、不需要网络）互补：这个验证的是
 * /connect → HTTP unary 这条真实链路在 Java 侧也能跑通，而不是只有 JS 探针能跑。
 *
 * <p>用法：
 * <pre>
 *   java -cp out com.dshmobile.protocol.LiveSelfTest "http://127.0.0.1:3080/?token=xxx"
 * </pre>
 * 退出码 0 = 通过。
 *
 * <p>只读：只调 session/list 与 session/modelCatalog，不创建会话、不发消息。
 */
public final class LiveSelfTest {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("用法: LiveSelfTest \"<带 token 的地址>\"");
            System.exit(2);
        }
        String tokenUrl = args[0];
        int failed = 0;

        try {
            System.out.println("[1/4] 换取 API Cookie …");
            DshClient.Session session = DshClient.connect(tokenUrl, 8000);
            System.out.println("  ✅ cookie=" + mask(session.cookie) + "  base=" + session.baseUrl);

            System.out.println("[2/4] session/list …");
            String list = DshClient.listSessions(session);
            boolean listOk = list.contains("\"ok\":true");
            System.out.println((listOk ? "  ✅ " : "  ❌ ") + trim(list, 300));
            if (!listOk) {
                failed++;
            }

            System.out.println("[3/4] session/modelCatalog …");
            String catalog = DshClient.modelCatalog(session);
            boolean catOk = catalog.contains("\"ok\":true");
            System.out.println((catOk ? "  ✅ " : "  ❌ ") + trim(catalog, 300));
            if (!catOk) {
                failed++;
            }

            System.out.println("[4/5] mux stream（订阅 $events，验证自写 WebSocket）…");
            boolean streamOk = checkStream(session);
            if (!streamOk) {
                failed++;
            }

            // 第 5 步是**写操作**（创建会话），只对隔离的开发实例启用：
            //   LiveSelfTest "<tokenUrl>" create
            if (args.length > 1 && "create".equals(args[1])) {
                System.out.println("[5/5] session/create + session/projections（写操作，仅隔离实例）…");
                if (!checkProjection(session)) {
                    failed++;
                }
            } else {
                System.out.println("[5/5] 跳过（未传 create；该步骤会创建会话）");
            }
        } catch (Exception e) {
            System.out.println("  ❌ " + e.getClass().getSimpleName() + ": " + e.getMessage());
            failed++;
        }

        System.out.println();
        System.out.println(failed == 0 ? "真实实例联调通过 ✅" : "真实实例联调失败 ❌");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static String mask(String cookie) {
        if (cookie == null || cookie.length() < 12) {
            return "(短)";
        }
        return cookie.substring(0, 12) + "…";
    }

    /** 建会话 → 读投影 → 用 SessionProjection 解析，确认真实数据结构与模型一致。 */
    private static boolean checkProjection(DshClient.Session session) {
        try {
            // 与 App 的「新建会话」走同一条路：不指定 cwd，由 Host 决定
            String created = DshClient.createSession(session);
            String sessionId = DshClient.parseCreatedSessionId(created);
            if (sessionId.isEmpty()) {
                System.out.println("  ❌ create 没返回 sessionId：" + trim(created, 200));
                return false;
            }
            System.out.println("      新会话 " + sessionId);

            String body = DshClient.projections(session, sessionId);
            SessionProjection p = SessionProjection.parse(body, sessionId);
            if (p == null) {
                System.out.println("  ❌ 投影解析返回 null：" + trim(body, 200));
                return false;
            }
            System.out.println("  ✅ 投影解析成功：title=" + p.displayTitle()
                    + " 权限=" + p.permission + " 工作目录=" + p.workingDirectory
                    + " asOfSeq=" + p.asOfSeq);
            // 真实数据的基本一致性检查
            boolean sane = p.asOfSeq >= 0 && p.turns >= 0 && p.totalTokens() >= 0;
            if (!sane) {
                System.out.println("  ❌ 投影字段不合理");
                return false;
            }

            // 发一条消息：模型必然失败（隔离实例无凭据），但用户消息与错误都会落成事件
            String probeText = "probe from LiveSelfTest " + System.currentTimeMillis();
            try {
                String accepted = DshClient.prompt(session, sessionId, probeText);
                System.out.println("      已发消息（模型适配器缺失，预期报错）："
                        + trim(accepted, 120));
            } catch (Exception e) {
                System.out.println("      prompt 返回错误（预期）：" + trim(e.getMessage(), 120));
            }
            // 读事件日志并折叠成对话（这才是原生聊天要渲染的东西）。
            // DSH 异步处理，落盘时间不定 —— **轮询**，不要固定 sleep（会假失败）。
            java.util.List<SessionEvent> events = new java.util.ArrayList<>();
            SessionTranscript transcript = SessionTranscript.fold(events);
            for (int attempt = 0; attempt < 12; attempt++) {
                Thread.sleep(1000);
                events = SessionEvent.parsePage(DshClient.page(session, sessionId));
                transcript = SessionTranscript.fold(events);
                boolean ready = transcript.entries.stream().anyMatch(
                        e -> SessionTranscript.ROLE_USER.equals(e.role) && e.text.contains(probeText));
                if (ready && events.size() > 3) {
                    break;
                }
            }
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (SessionEvent e : events) {
                counts.merge(e.type, 1, Integer::sum);
            }
            System.out.println("      事件类型分布：" + counts);
            System.out.println("  ✅ 事件 " + events.size() + " 条 → 折叠出 " + transcript.entries.size()
                    + " 个对话条目（跳过 " + transcript.systemMessages + " 条系统消息），标题="
                    + (transcript.title.isEmpty() ? "(无)" : transcript.title));
            for (SessionTranscript.Entry e : transcript.entries()) {
                System.out.println("      [" + e.role + "] " + trim(e.text, 120));
            }

            // 折叠结果的正确性检查：至少要有用户消息，且错误条目要能带出错误码
            boolean hasUser = transcript.entries.stream()
                    .anyMatch(e -> SessionTranscript.ROLE_USER.equals(e.role) && e.text.contains(probeText));
            boolean hasError = transcript.entries.stream().anyMatch(SessionTranscript.Entry::isError);
            if (!hasUser) {
                System.out.println("  ❌ 折叠结果里没有找到刚发的用户消息");
                return false;
            }
            if (!hasError) {
                System.out.println("  ⚠️  没有错误条目（可能这台实例真的有模型适配器）");
            }

            // 实时流：订阅 → 再发一条 → 收到增量事件
            if (!checkLiveStream(session, sessionId)) {
                return false;
            }
            // 附件：上传 → receiptId → 带附件的 prompt（App 的「＋」走的就是这条）
            return checkAttachment(session, sessionId);
        } catch (Exception e) {
            System.out.println("  ❌ " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 验证原生附件：{@code POST /api/session/uploadFileBinary} → receiptId → prompt 的 file 片段。
     *
     * <p>这是 App 里「＋ 选择文件」的完整路径；图片则走内联 base64（不经过这里）。
     */
    private static boolean checkAttachment(DshClient.Session session, String sessionId) {
        try {
            System.out.println("[6/6] 原生附件（上传 + 带附件发消息）…");
            byte[] data = "hello attachment from LiveSelfTest\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String name = "probe-" + System.currentTimeMillis() + ".txt";
            String uploadBody = DshClient.uploadFile(session, sessionId, name, data);
            String receiptId = DshClient.parseUploadReceiptId(uploadBody);
            if (receiptId.isEmpty()) {
                System.out.println("  ❌ 上传没有返回 receiptId：" + trim(uploadBody, 200));
                return false;
            }
            System.out.println("  ✅ 上传成功 receiptId=" + trim(receiptId, 12) + "…");

            java.util.List<String> parts = new java.util.ArrayList<>();
            parts.add(DshClient.filePart(receiptId));
            String accepted = DshClient.prompt(session, sessionId, "（附件联调）", parts);
            if (!accepted.contains("\"ok\":true")) {
                System.out.println("  ❌ 带附件的 prompt 被拒绝：" + trim(accepted, 200));
                return false;
            }
            System.out.println("  ✅ 带附件的消息已接受");

            // 事件日志里应该能看到附件被服务端解析成 attachment 对象。
            // DSH 是异步处理的（实测 ~3 秒落盘），所以**轮询**而不是固定 sleep ——
            // 固定等待会随机器负载出现假失败。
            boolean found = false;
            SessionTranscript transcript = null;
            int eventCount = 0;
            for (int attempt = 0; attempt < 10 && !found; attempt++) {
                Thread.sleep(1000);
                String pageBody = DshClient.page(session, sessionId);
                java.util.List<SessionEvent> events = SessionEvent.parsePage(pageBody);
                eventCount = events.size();
                transcript = SessionTranscript.fold(events);
                for (SessionTranscript.Entry e : transcript.entries()) {
                    if (SessionTranscript.ROLE_USER.equals(e.role) && e.text.contains(name)) {
                        found = true;
                        System.out.println("      事件里的用户消息：" + trim(e.text, 120));
                        break;
                    }
                }
            }
            if (!found) {
                System.out.println("  ⚠️  事件日志里没找到附件名，诊断：records=" + eventCount
                        + "，对话条目=" + (transcript == null ? -1 : transcript.entries.size()));
                if (transcript != null) {
                    for (SessionTranscript.Entry e : transcript.entries()) {
                        System.out.println("       [" + e.role + "] " + trim(e.text, 100));
                    }
                }
            } else {
                System.out.println("  ✅ 事件日志里能看到附件");
            }
            return true;
        } catch (Exception e) {
            System.out.println("  ❌ " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 验证 {@link ObserveEventStream}：订阅后能收到 ready 与业务事件。
     * 这是 App 详情页"实时追加"的底层能力。
     */
    private static boolean checkLiveStream(DshClient.Session session, String sessionId) {
        final java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch eventLatch = new java.util.concurrent.CountDownLatch(1);
        final java.util.List<String> types = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        ObserveEventStream stream = null;
        try {
            stream = ObserveEventStream.open(session.baseUrl, session.cookie, sessionId,
                    new ObserveEventStream.Listener() {
                        @Override
                        public void onReady() {
                            readyLatch.countDown();
                        }

                        @Override
                        public void onEvent(SessionEvent event) {
                            types.add(event.type);
                            eventLatch.countDown();
                        }

                        @Override
                        public void onEnd() {
                            // ignore
                        }

                        @Override
                        public void onError(Exception error) {
                            readyLatch.countDown();
                            eventLatch.countDown();
                        }
                    });
            boolean ready = readyLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!ready) {
                System.out.println("  ❌ 10 秒内没收到 ready 帧");
                return false;
            }
            System.out.println("  ✅ 收到 ready（流已就绪）");

            // 订阅生效后再发一条，验证增量能被推到
            DshClient.call(session, "session/prompt",
                    "{\"request\":{\"requestId\":\"probe-live-" + System.currentTimeMillis()
                            + "\",\"sessionId\":\"" + sessionId + "\",\"mode\":\"queue\",\"content\":"
                            + "[{\"type\":\"text\",\"text\":\"live stream check\"}]}}");

            boolean got = eventLatch.await(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!got) {
                System.out.println("  ❌ 15 秒内没收到业务事件");
                return false;
            }
            // 等一小会儿收集更多事件
            Thread.sleep(1200);
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            synchronized (types) {
                for (String t : types) {
                    counts.merge(t, 1, Integer::sum);
                }
            }
            System.out.println("  ✅ 实时收到 " + types.size() + " 个事件：" + counts);
            return true;
        } catch (Exception e) {
            System.out.println("  ❌ " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            if (stream != null) {
                stream.close();
            }
        }
    }

    /**
     * 订阅网关内部事件流 {@code $events}，验证两件事：
     * 自写的 WebSocket 客户端能在真实 mux 上收发；open/item 帧格式正确。
     * 收到任意一帧属于本流的 item 即视为成功。
     */
    private static boolean checkStream(DshClient.Session session) {
        String wsUrl;
        String mux = session.muxUrl;
        if (mux != null && mux.startsWith("http")) {
            wsUrl = mux.replaceFirst("^http", "ws");
        } else {
            wsUrl = session.baseUrl.replaceFirst("^http", "ws") + "/api/remote.mux";
        }

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] ok = {false};
        final String[] detail = {""};
        final String streamId = java.util.UUID.randomUUID().toString();

        try {
            WebSocketClient client = WebSocketClient.connect(
                    java.net.URI.create(wsUrl),
                    java.util.Collections.singletonMap("Cookie", session.cookie),
                    new WebSocketClient.Listener() {
                        @Override
                        public void onOpen(WebSocketClient c) {
                            String frame = "{\"type\":\"open\",\"streamId\":\"" + streamId
                                    + "\",\"endpoint\":\"$events\",\"payload\":{\"args\":{}}}";
                            try {
                                c.sendText(frame);
                            } catch (Exception e) {
                                detail[0] = "send: " + e.getMessage();
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onText(String message) {
                            if (message.contains("\"streamId\":\"" + streamId + "\"")) {
                                ok[0] = true;
                                detail[0] = trim(message, 240);
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onClose(int code, String reason) {
                            latch.countDown();
                        }

                        @Override
                        public void onError(Exception e) {
                            detail[0] = e.getMessage();
                            latch.countDown();
                        }
                    });

            boolean got = latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
            try {
                client.close();
            } catch (Exception ignored) {
                // ignore
            }
            if (ok[0]) {
                System.out.println("  ✅ 收到 mux 帧：" + detail[0]);
                return true;
            }
            System.out.println("  ❌ " + (got ? "收到帧但不匹配" : "8 秒内没有帧")
                    + (detail[0].isEmpty() ? "" : "（" + detail[0] + "）"));
            return false;
        } catch (Exception e) {
            System.out.println("  ❌ " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private static String trim(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}

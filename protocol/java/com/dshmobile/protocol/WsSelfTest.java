package com.dshmobile.protocol;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 客户端自检（纯 JVM，不需要 Android、不需要 DSH）。
 *
 * <p>为什么要单独测：这个客户端是手写的 RFC 6455 实现（App 不引 OkHttp），
 * 握手、掩码、分片、ping/pong 任何一处写错都会在真实环境里表现为"连上但没反应"，
 * 极难排查。先用一个标准 WebSocket 服务器把协议行为钉死。
 *
 * <p>用法（需要一个回显服务器）：
 * <pre>
 *   node protocol/test/ws-echo-server.mjs 19399
 *   java -cp out com.dshmobile.protocol.WsSelfTest ws://127.0.0.1:19399/
 * </pre>
 * 退出码 0 = 通过。
 */
public final class WsSelfTest {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "ws://127.0.0.1:19399/";
        String payload = "hello-dsh-" + System.currentTimeMillis();

        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] echoed = {false};
        final String[] closedReason = {""};

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-DSH-SelfTest", "1");

        System.out.println("连接 " + url);
        WebSocketClient client = WebSocketClient.connect(URI.create(url), headers,
                new WebSocketClient.Listener() {
                    @Override
                    public void onOpen(WebSocketClient c) {
                        System.out.println("  ✅ 握手成功（101）");
                        try {
                            c.sendText(payload);
                            System.out.println("  → 已发送文本帧（" + payload.length() + " 字节，带掩码）");
                        } catch (Exception e) {
                            System.out.println("  ❌ 发送失败：" + e.getMessage());
                            done.countDown();
                        }
                    }

                    @Override
                    public void onText(String message) {
                        // 服务器约定回显 "echo:<原文>"
                        String expected = "echo:" + payload;
                        if (expected.equals(message)) {
                            echoed[0] = true;
                            System.out.println("  ✅ 收到回显且内容一致");
                        } else {
                            System.out.println("  ❌ 回显不匹配：期望 " + expected + "，实际 " + message);
                        }
                        done.countDown();
                    }

                    @Override
                    public void onClose(int code, String reason) {
                        closedReason[0] = code + " " + reason;
                        done.countDown();
                    }

                    @Override
                    public void onError(Exception error) {
                        System.out.println("  ❌ 错误：" + error.getMessage());
                        done.countDown();
                    }
                });

        boolean finished = done.await(6, TimeUnit.SECONDS);
        client.close();

        if (!finished) {
            System.out.println("  ❌ 6 秒内没有收到回显");
        }
        boolean ok = echoed[0];
        System.out.println();
        System.out.println(ok ? "WebSocket 自检通过 ✅" : "WebSocket 自检失败 ❌ " + closedReason[0]);
        System.exit(ok ? 0 : 1);
    }
}

package com.dshmobile.protocol;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;

/**
 * 订阅一个会话的事件流：{@code session/observeEvent}（mux WebSocket）。
 *
 * <p>实测协议（docs/protocol.md §4、§10）：
 * <pre>
 * 发：{ type:"open", streamId, endpoint:"session/observeEvent",
 *       payload:{ args:{ request:{ address:{ kind:"session", sessionId } } } } }
 * 收：{ type:"item",  streamId, value:{ type:"ready" } }              ← 就绪
 *     { type:"item",  streamId, value:{ type:"event", event:{...} } } ← 业务事件
 *     { type:"end",   streamId } | { type:"error", streamId, error:{...} }
 * </pre>
 *
 * <p>线程模型：所有回调都在 WebSocket 的读线程上触发，UI 层需自行切主线程。
 *
 * <p>生命周期：一条物理连接只服务一条流，所以收到 {@code end} / {@code error} 后
 * 会主动关闭连接 —— 不关就是 socket 泄漏。
 */
public final class ObserveEventStream {

    /** 事件回调。 */
    public interface Listener {
        void onReady();

        void onEvent(SessionEvent event);

        void onEnd();

        void onError(Exception error);
    }

    private final WebSocketClient socket;
    private final String streamId;
    private final Listener listener;
    private volatile boolean closed;

    private ObserveEventStream(WebSocketClient socket, String streamId, Listener listener) {
        this.socket = socket;
        this.streamId = streamId;
        this.listener = listener;
    }

    public static ObserveEventStream open(String baseUrl, String cookie, String sessionId,
                                          Listener listener) throws IOException {
        String wsUrl = toWsUrl(baseUrl) + "/api/remote.mux";
        // 匿名回调需要引用到实例（用于关闭连接），用 holder 过渡一下
        final ObserveEventStream[] holder = new ObserveEventStream[1];

        WebSocketClient client = WebSocketClient.connect(
                URI.create(wsUrl),
                Collections.singletonMap("Cookie", cookie),
                new WebSocketClient.Listener() {
                    @Override
                    public void onOpen(WebSocketClient c) {
                        // 帧在 open() 里返回后立刻发送
                    }

                    @Override
                    public void onText(String message) {
                        ObserveEventStream s = holder[0];
                        if (s != null) {
                            s.dispatch(message);
                        }
                    }

                    @Override
                    public void onClose(int code, String reason) {
                        ObserveEventStream s = holder[0];
                        if (s != null && !s.closed) {
                            s.closed = true;
                            if (s.listener != null) {
                                s.listener.onEnd();
                            }
                        }
                    }

                    @Override
                    public void onError(Exception error) {
                        ObserveEventStream s = holder[0];
                        if (s != null && !s.closed) {
                            s.closed = true;
                            if (s.listener != null) {
                                s.listener.onError(error);
                            }
                        }
                    }
                });

        String streamId = java.util.UUID.randomUUID().toString();
        ObserveEventStream stream = new ObserveEventStream(client, streamId, listener);
        holder[0] = stream;
        client.sendText(buildOpenFrame(streamId, sessionId));
        return stream;
    }

    /** http(s) 基地址 → ws(s) 基地址。 */
    static String toWsUrl(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.startsWith("https://")) {
            return "wss://" + b.substring("https://".length());
        }
        if (b.startsWith("http://")) {
            return "ws://" + b.substring("http://".length());
        }
        return b;
    }

    /** 供单测/诊断：构造 open 帧文本。 */
    static String buildOpenFrame(String streamId, String sessionId) {
        String safeId = sessionId == null ? "" : sessionId.replace("\\", "").replace("\"", "");
        return "{\"type\":\"open\",\"streamId\":\"" + streamId + "\","
                + "\"endpoint\":\"session/observeEvent\","
                + "\"payload\":{\"args\":{\"request\":{\"address\":{"
                + "\"kind\":\"session\",\"sessionId\":\"" + safeId + "\"}}}}}";
    }

    /** 供单测：解析一条服务端帧。返回 null 表示与本次订阅无关或不认识。 */
    static Frame parseFrame(String text, String expectedStreamId) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Object root;
        try {
            root = Json.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
        String type = Json.str(root, "type", "");
        String streamId = Json.str(root, "streamId", "");
        if (!streamId.equals(expectedStreamId)) {
            return null;
        }
        return buildFrame(root, type);
    }

    /** 不做 streamId 过滤的版本（单条连接只订阅一条流时足够）。 */
    private static Frame parseFrameAny(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Object root;
        try {
            root = Json.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
        return buildFrame(root, Json.str(root, "type", ""));
    }

    private static Frame buildFrame(Object root, String type) {
        Frame f = new Frame();
        f.type = type;
        if ("item".equals(type)) {
            Object value = Json.get(root, "value");
            f.valueType = Json.str(value, "type", "");
            Object ev = Json.get(value, "event");
            if (ev != null) {
                String evType = Json.str(ev, "type", "");
                if (!evType.isEmpty()) {
                    f.event = new SessionEvent(evType,
                            Json.num(ev, "seq", 0L),
                            Json.num(ev, "time", 0L),
                            Json.get(ev, "data"));
                }
            }
        } else if ("error".equals(type)) {
            Object err = Json.get(root, "error");
            f.errorMessage = Json.str(err, "message", "");
        }
        return f;
    }

    private void dispatch(String message) {
        Frame f = parseFrameAny(message);
        if (f == null) {
            return;
        }
        switch (f.type) {
            case "item":
                if (listener != null) {
                    if ("ready".equals(f.valueType)) {
                        listener.onReady();
                    } else if (f.event != null) {
                        listener.onEvent(f.event);
                    }
                }
                break;
            case "end":
                closeQuietly();
                if (listener != null) {
                    listener.onEnd();
                }
                break;
            case "error":
                closeQuietly();
                if (listener != null) {
                    listener.onError(new IOException("stream error: " + f.errorMessage));
                }
                break;
            default:
                break;
        }
    }

    /** 关闭物理连接（幂等）。 */
    private void closeQuietly() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            socket.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    public void close() {
        closeQuietly();
    }

    public boolean isClosed() {
        return closed;
    }

    public String streamId() {
        return streamId;
    }

    /** 解析出来的服务端帧。 */
    static final class Frame {
        String type = "";
        String valueType = "";
        String errorMessage = "";
        SessionEvent event;
    }
}

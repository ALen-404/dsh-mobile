package com.dshmobile.protocol;

import java.io.IOException;

/**
 * 一站式客户端：从「带 token 的 URL」走到「能调 RPC」。
 *
 * <p>这正是 App 的 B 模式（API 模式）要做的事，抽出来是为了：
 * <ul>
 *   <li>App 里只写一行调用，不必自己拼 prepare/exchange/call 三步；</li>
 *   <li>能在纯 JVM 环境下对着真实 DSH 验证（见 {@link LiveSelfTest}）。</li>
 * </ul>
 */
public final class DshClient {

    /** 一次会话的运行时状态。 */
    public static final class Session {
        public final Credential credential;
        public final String baseUrl;
        /** /connect 换到的 Cookie 头内容。 */
        public final String cookie;
        /** mux 目的地（stream 通道用，通常相对路径 "./"）。 */
        public final String muxUrl;

        Session(Credential credential, String baseUrl, String cookie, String muxUrl) {
            this.credential = credential;
            this.baseUrl = baseUrl;
            this.cookie = cookie;
            this.muxUrl = muxUrl;
        }
    }

    private DshClient() {
    }

    /**
     * 用带 token 的 URL 建立会话（换取 API Cookie）。
     *
     * @param tokenUrl dsh 启动时打印的带 {@code ?token=} 的地址
     */
    public static Session connect(String tokenUrl, int timeoutMs) throws IOException {
        Credential cred = Credential.fromTokenUrl(tokenUrl, tokenUrl);
        if (!cred.isPresent()) {
            throw new IOException("这个地址里没有 token 参数，无法进入 B 模式（API 模式）");
        }
        ApiCookieExchanger.Result r = ApiCookieExchanger.exchange(cred, timeoutMs);
        if (!r.ok) {
            throw new IOException("换取 API Cookie 失败：" + r.advice);
        }
        return new Session(cred, cred.baseUrl, r.cookie, r.muxUrl);
    }

    /** 调一个 unary 方法，返回响应体原文。 */
    public static String call(Session session, String endpoint, String argsJson) throws IOException {
        return UnaryRpc.call(session.baseUrl, session.cookie, endpoint, argsJson, 15000);
    }

    /** 列出会话（unary）。 */
    public static String listSessions(Session session) throws IOException {
        return call(session, "session/list", "{\"_request\":{}}");
    }

    /** 模型目录（unary）。 */
    public static String modelCatalog(Session session) throws IOException {
        return call(session, "session/modelCatalog", "{}");
    }

    /** 会话投影：原生界面的数据源（标题/用量/权限/轮次大纲）。 */
    public static String projections(Session session, String sessionId) throws IOException {
        return call(session, "session/projections",
                "{\"request\":{\"sessionId\":\"" + sessionId.replace("\"", "") + "\"}}");
    }

    /**
     * 会话历史分页（unary）。
     *
     * <p>注意参数是 {@code address} 而不是 {@code sessionId} —— 传错服务端会报
     * {@code gateway/input-invalid}（实测）。
     */
    public static String page(Session session, String sessionId) throws IOException {
        return call(session, "session/page",
                "{\"request\":{\"address\":{\"kind\":\"session\",\"sessionId\":\""
                        + sessionId.replace("\"", "") + "\"}}}");
    }

    /**
     * 构造 {@code session/prompt} 的参数 JSON（可单测，不联网）。
     *
     * <p>实测形状（docs/protocol.md §10.1）：
     * {@code { request: { requestId, sessionId, mode, content:[{type:"text",text}] } }}
     */
    public static String promptArgs(String sessionId, String requestId, String text) {
        return "{\"request\":{\"requestId\":\"" + Json.escape(requestId)
                + "\",\"sessionId\":\"" + Json.escape(sessionId)
                + "\",\"mode\":\"queue\",\"content\":[{\"type\":\"text\",\"text\":\""
                + Json.escape(text) + "\"}]}}";
    }

    /**
     * 给会话发一条文本消息。
     *
     * @return 服务端响应（成功时形如 {@code {"accepted":true}}）
     */
    public static String prompt(Session session, String sessionId, String text) throws IOException {
        String requestId = "mobile-" + System.currentTimeMillis() + "-"
                + Integer.toHexString(text.hashCode());
        return call(session, "session/prompt", promptArgs(sessionId, requestId, text));
    }

    /** 创建一个新会话，返回响应体原文（从中取 sessionId）。 */
    public static String createSession(Session session, String cwd) throws IOException {
        return call(session, "session/create",
                "{\"request\":{\"cwd\":\"" + Json.escape(cwd) + "\"}}");
    }

    /**
     * 创建一个新会话，不指定工作目录（由 Host 决定默认目录）。
     *
     * <p>实测：`{"request":{}}` 即可，返回
     * {@code {"sessionId":"session-…","agentPreset":"standard"}}。
     */
    public static String createSession(Session session) throws IOException {
        return call(session, "session/create", "{\"request\":{}}");
    }

    /**
     * 从 {@code session/create} 的响应里取出 sessionId。
     *
     * @return 取不到返回空串（不抛异常，调用方据此报错）
     */
    public static String parseCreatedSessionId(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return "";
        }
        try {
            Object root = Json.parse(responseBody);
            Object value = Json.path(root, "result", "value");
            String id = Json.str(value, "sessionId", "");
            return id == null ? "" : id;
        } catch (RuntimeException e) {
            return "";
        }
    }
}

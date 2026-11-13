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
        /** /connect 换到的 Cookie 头内容（配对设备模式为空，改走设备头）。 */
        public final String cookie;
        /** mux 目的地（stream 通道用，通常相对路径 "./"）。 */
        public final String muxUrl;
        /**
         * 到 {@code /api} 为止的调用前缀：直连是 {@code <base>/api}，
         * 走 remote-web-ui 插件的配对网关是 {@code <base>/remote/api}。
         */
        public final String apiRoot;
        /** 每次请求要带的凭证头（Cookie / x-dsh-remote-device）。 */
        public final java.util.Map<String, String> headers;
        /** 配对设备的 id（token 模式为空）。 */
        public final String deviceId;

        Session(Credential credential, String baseUrl, String cookie, String muxUrl,
                String apiRoot, java.util.Map<String, String> headers, String deviceId) {
            this.credential = credential;
            this.baseUrl = baseUrl;
            this.cookie = cookie;
            this.muxUrl = muxUrl;
            this.apiRoot = apiRoot;
            this.headers = headers;
            this.deviceId = deviceId == null ? "" : deviceId;
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
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Cookie", r.cookie);
        return new Session(cred, cred.baseUrl, r.cookie, r.muxUrl,
                trimSlash(cred.baseUrl) + "/api", headers, "");
    }

    /**
     * 用<b>配对设备</b>建立会话（remote-web-ui 插件，粘贴 /pair-accept 链接即可）。
     *
     * <p>实测结论（读插件源码 + 协议约定）：配对后的手机拿到的是设备凭据，不是浏览器
     * auth cookie；插件的 {@code /remote} 网关把宿主 API 原样代理出来 ——
     * unary 走 {@code POST /remote/api/<ns/method>}（带 {@code x-dsh-remote-device} 头），
     * mux 走 {@code wss://host/remote/api/remote.mux?device=<id>}。
     * 配对设备是"完全控制"凭据（只有配对/自更新/插件安装留在本机）。
     *
     * @param origin   实例来源，如 {@code https://dsh.example.com}
     * @param deviceId 配对 cookie（默认名 {@code dsh_pair}）的值
     */
    public static Session connectWithDevice(String origin, String deviceId) throws IOException {
        if (origin == null || origin.isEmpty()) {
            throw new IOException("缺少实例地址");
        }
        if (deviceId == null || deviceId.isEmpty()) {
            throw new IOException("缺少配对设备 id（先打开 /pair-accept 链接完成配对）");
        }
        String base = trimSlash(origin);
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Cookie", DEVICE_COOKIE_NAME + "=" + deviceId);
        headers.put("x-dsh-remote-device", deviceId);
        String mux = base.replaceFirst("^http", "ws") + "/remote/api/remote.mux?device="
                + urlEncode(deviceId);
        return new Session(null, base, "", mux, base + "/remote/api", headers, deviceId);
    }

    /** 配对 cookie 的默认名（插件配置可改，默认值见插件 index.js）。 */
    public static final String DEVICE_COOKIE_NAME = "dsh_pair";

    /** 调一个 unary 方法，返回响应体原文。 */
    public static String call(Session session, String endpoint, String argsJson) throws IOException {
        return UnaryRpc.call(session.apiRoot, session.headers, endpoint, argsJson, 15000);
    }

    private static String trimSlash(String s) {
        String out = s == null ? "" : s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
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

    /* ---------------- 附件 ---------------- */

    /** 文件内容片段（先上传拿到 receiptId）。 */
    public static String filePart(String receiptId) {
        return "{\"type\":\"file\",\"receiptId\":" + Json.quote(receiptId) + "}";
    }

    /** 图片内容片段（直接内联 base64，不需要上传）。 */
    public static String imagePart(String mediaType, String base64, String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"image\",\"mediaType\":").append(Json.quote(mediaType))
                .append(",\"data\":").append(Json.quote(base64));
        if (name != null && !name.isEmpty()) {
            sb.append(",\"name\":").append(Json.quote(name));
        }
        return sb.append('}').toString();
    }

    /**
     * 构造带附件/图片的 prompt 参数。
     *
     * @param text    文本部分；可以为空（服务端允许"只有附件"）
     * @param extraParts 由 {@link #filePart} / {@link #imagePart} 生成的片段
     */
    public static String promptArgs(String sessionId, String requestId, String text,
                                    java.util.List<String> extraParts) {
        StringBuilder content = new StringBuilder("[");
        boolean first = true;
        if (text != null && !text.trim().isEmpty()) {
            content.append("{\"type\":\"text\",\"text\":").append(Json.quote(text)).append('}');
            first = false;
        }
        if (extraParts != null) {
            for (String part : extraParts) {
                if (part == null || part.isEmpty()) {
                    continue;
                }
                if (!first) {
                    content.append(',');
                }
                content.append(part);
                first = false;
            }
        }
        content.append(']');
        return "{\"request\":{\"requestId\":\"" + Json.escape(requestId)
                + "\",\"sessionId\":\"" + Json.escape(sessionId)
                + "\",\"mode\":\"queue\",\"content\":" + content + "}}";
    }

    /**
     * 上传一个文件到会话，返回**响应体原文**（用 {@link #parseUploadReceiptId} 取 receiptId）。
     *
     * <p>实测协议（2026-11-13）：
     * <pre>
     * POST /api/session/uploadFileBinary?sessionId=&lt;id&gt;&amp;name=&lt;文件名&gt;
     * content-type: application/octet-stream
     * body: 原始字节
     * → {"ok":true,"value":{"receiptId":"…","file":{"attachmentId":"sha256:…","name":"…","bytes":39}}}
     * </pre>
     */
    public static String uploadFile(Session session, String sessionId, String name, byte[] data)
            throws IOException {
        // 前缀跟着凭证类型走：直连是 /api，配对设备是 /remote/api
        String prefix = session.apiRoot.endsWith("/api")
                ? session.apiRoot.substring(0, session.apiRoot.length() - "/api".length())
                : session.baseUrl;
        StringBuilder query = new StringBuilder("/session/uploadFileBinary?sessionId=")
                .append(urlEncode(sessionId));
        if (name != null && !name.isEmpty()) {
            query.append("&name=").append(urlEncode(name));
        }
        String apiPath = session.apiRoot.substring(trimSlash(prefix).length()) + query;
        return UnaryRpc.postRaw(prefix, session.headers, apiPath, data, 60000);
    }

    /** 从上传响应里取 receiptId；取不到返回空串。 */
    public static String parseUploadReceiptId(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return "";
        }
        try {
            Object root = Json.parse(responseBody);
            String id = Json.str(Json.path(root, "value"), "receiptId", "");
            if (id.isEmpty()) {
                // 兼容外层再包一层 result 的形状
                id = Json.str(Json.path(root, "result", "value"), "receiptId", "");
            }
            return id;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 只做百分号编码（用于查询参数）。 */
    static String urlEncode(String value) {
        StringBuilder sb = new StringBuilder();
        byte[] bytes = (value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (safe) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    /**
     * 发一条带附件/图片的消息。
     *
     * @param extraParts 由 {@link #filePart} / {@link #imagePart} 生成的片段
     */
    public static String prompt(Session session, String sessionId, String text,
                                java.util.List<String> extraParts) throws IOException {
        String requestId = "mobile-" + System.currentTimeMillis() + "-"
                + Integer.toHexString(text == null ? 0 : text.hashCode());
        return call(session, "session/prompt", promptArgs(sessionId, requestId, text, extraParts));
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

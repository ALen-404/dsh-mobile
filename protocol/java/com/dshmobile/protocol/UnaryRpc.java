package com.dshmobile.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * unary RPC：HTTP POST /api/&lt;endpoint&gt;。
 *
 * <p>实测结论（2026-11-13，DSH 0.2.2-rc.1）：unary 方法**不走 mux WebSocket**，
 * 走 Connection 层的 HTTP RPC；mux 只承载 stream（订阅/事件）。
 * 如果对 unary 方法发 mux 的 open 帧，服务端会报
 * {@code unary Remote methods cannot be opened through the stream carrier}。
 *
 * <p>信封（来自源码 clientRequestSchema / serverResponseSchema）：
 * <pre>
 *   请求  POST /api/&lt;endpoint&gt;
 *         content-type: application/json
 *         { "type":"client-request", "rpcId":"&lt;uuid&gt;", "method":"&lt;endpoint&gt;",
 *           "payload": { "args": &lt;参数对象&gt; } }
 *   响应  { "type":"server-response", "rpcId":"...",
 *           "result": { "ok": true, "value": ... } }
 *         | { "result": { "ok": false, "error": { "code","message","details" } } }
 * </pre>
 *
 * <p>两个容易踩的点（都实测过）：
 * <ul>
 *   <li>payload 必须**恰好含一个** args 字段，否则报
 *       "Remote payload must contain exactly one plain-object args field"</li>
 *   <li>参数名按端点 descriptor 的 parameters 来的，例如 {@code session/list} 要
 *       {@code {"_request":{}}}，不是空对象</li>
 * </ul>
 *
 * <p>本类只做传输，不解析业务 JSON（调用方拿到响应体自行解析），
 * 以保持协议层零依赖、可被纯 JVM 环境使用。
 */
public final class UnaryRpc {

    /** 调用成功但业务返回 ok:false 时抛出。 */
    public static final class RpcException extends IOException {
        public final String code;

        RpcException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private UnaryRpc() {
    }

    /**
     * 执行一次 unary 调用。
     *
     * @param baseUrl   基础地址（不要带 token）
     * @param cookie    /connect 换到的 Cookie 头内容
     * @param endpoint  形如 {@code session/list}
     * @param argsJson  参数对象的 JSON 文本，例如 {@code {"_request":{}}}
     * @param timeoutMs 超时
     * @return 服务端响应体原文（调用方自行解析 value）
     */
    public static String call(String baseUrl, String cookie, String endpoint, String argsJson, int timeoutMs)
            throws IOException {
        if (cookie == null || cookie.isEmpty()) {
            throw new IOException("缺少 API Cookie：先走 /connect?token= 换取");
        }
        String root = baseUrl;
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        String url = root + "/api/" + endpoint;

        String rpcId = UUID.randomUUID().toString();
        String body = "{\"type\":\"client-request\",\"rpcId\":\"" + rpcId
                + "\",\"method\":\"" + jsonEscape(endpoint)
                + "\",\"payload\":{\"args\":" + (argsJson == null || argsJson.isEmpty() ? "{}" : argsJson) + "}}";

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("User-Agent", Credential.clientId());

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            String responseBody = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (status != 200) {
                throw new IOException("HTTP " + status + "：" + trim(responseBody, 300));
            }
            // 业务失败以 ok:false 表达，HTTP 仍是 200
            if (responseBody.contains("\"ok\":false")) {
                throw new RpcException(extractField(responseBody, "code"),
                        trim(extractField(responseBody, "message"), 300));
            }
            return responseBody;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /* ------------------------------------------------------------------ */

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 极简字段提取：只用于把错误信息捞出来，不做完整 JSON 解析。 */
    private static String extractField(String json, String field) {
        if (json == null) {
            return "";
        }
        String key = "\"" + field + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return "";
        }
        int start = i + key.length();
        StringBuilder sb = new StringBuilder();
        for (int p = start; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '\\' && p + 1 < json.length()) {
                sb.append(json.charAt(++p));
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String trim(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}

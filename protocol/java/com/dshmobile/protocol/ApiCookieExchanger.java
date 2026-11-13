package com.dshmobile.protocol;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 用 launch token 换签名 API Cookie。
 *
 * <p>协议（来自 DSH 源码内文档，见 protocol/README.md）：
 * <pre>
 * GET /connect?token=&lt;launchToken&gt;&amp;interactions=answer|none
 *   → 立即创建 owner 权限的 Peer
 *   → 302，Set-Cookie 带签名 API Cookie，Location 指向 Mux 目的地
 * </pre>
 *
 * <p>关键点：**不要跟随跳转**。我们要的是 302 响应头里的 Set-Cookie。
 *
 * <p>纯 Java 实现（{@code java.net}），不依赖 Android，便于复用与单测。
 */
public final class ApiCookieExchanger {

    /** 换取结果。失败时 {@link #advice} 给出可执行的下一步。 */
    public static final class Result {
        public final boolean ok;
        public final int status;
        /** 成功时为 Cookie 头内容（可能多段，用 "; " 连接）。 */
        public final String cookie;
        /** 成功时为 Mux 目的地。 */
        public final String muxUrl;
        /** 失败/成攻都给一句人话说明。 */
        public final String advice;

        Result(boolean ok, int status, String cookie, String muxUrl, String advice) {
            this.ok = ok;
            this.status = status;
            this.cookie = cookie;
            this.muxUrl = muxUrl;
            this.advice = advice;
        }
    }

    private ApiCookieExchanger() {
    }

    /**
     * 执行换取。会阻塞，调用方请放到后台线程。
     *
     * @param credential 必须含 token 地址
     * @param timeoutMs  连接/读取超时
     */
    public static Result exchange(Credential credential, int timeoutMs) {
        if (credential == null || !credential.isPresent()) {
            return new Result(false, 0, null, null,
                    "没有 API 凭证。需要在实例里填入 dsh web 输出中带 ?token= 的地址。");
        }

        HttpURLConnection conn = null;
        try {
            URI uri = URI.create(credential.tokenUrl);
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return new Result(false, 0, null, null, "只支持 http/https 地址。");
            }

            conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            // 关键：不跟随跳转，302 的响应头才是我们要的东西
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", Credential.clientId());
            conn.connect();

            int status = conn.getResponseCode();
            if (status != 302 && status != 303) {
                return new Result(false, status, null, null, explainStatus(status));
            }

            String cookie = joinCookies(conn.getHeaderFields());
            if (cookie == null || cookie.isEmpty()) {
                return new Result(false, status, null, null,
                        "302 但没有拿到 Set-Cookie。\n"
                                + "可能是地址不是 /connect 端点，或 token 已被其它客户端用掉（一次性）。");
            }

            String location = conn.getHeaderField("Location");
            return new Result(true, status, cookie, location,
                    "换取成功。Mux 目的地：" + (location == null ? "(未提供)" : location));
        } catch (IllegalArgumentException e) {
            return new Result(false, 0, null, null, "地址格式不对：" + e.getMessage());
        } catch (IOException e) {
            return new Result(false, 0, null, null, "连不上：" + e.getMessage()
                    + "\n检查网络互通、端口是否开放、DSH 是否只绑了 127.0.0.1。");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 把多段 Set-Cookie 压成一个 Cookie 请求头（只取 name=value）。 */
    private static String joinCookies(Map<String, List<String>> headers) {
        if (headers == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String key = e.getKey();
            if (key == null || !"set-cookie".equalsIgnoreCase(key)) {
                continue;
            }
            for (String raw : e.getValue()) {
                if (raw == null) {
                    continue;
                }
                int semi = raw.indexOf(';');
                String pair = (semi >= 0 ? raw.substring(0, semi) : raw).trim();
                if (pair.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(pair);
            }
        }
        return sb.toString();
    }

    /** 状态码 → 人话（与 App 里的 Diagnostics 文案保持一致口径）。 */
    private static String explainStatus(int status) {
        switch (status) {
            case 401:
                return "launch token 无效或已过期。\n"
                        + "token 是每个 dsh web 进程随机生成的，重启后旧 token 立即失效 —— 重新复制一次。";
            case 403:
                return "被拒绝（403）。该 token 可能不属于这个入口，或已被撤销。";
            case 404:
                return "这个地址上没有 /connect 端点。确认地址是 dsh web 打印的那一条。";
            case 502:
                return "隧道通着，但本地 DSH 没响应（502）—— 确认 DSH 在运行、端口没变。";
            case 530:
                return "隧道没有可用连接器（530）—— 隧道进程没在跑。";
            default:
                return "换取失败，HTTP " + status + "。";
        }
    }
}

package com.dshmobile.protocol;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个实例的凭证。对应 README 里的能力分级：
 *
 * <ul>
 *   <li>{@link Kind#NONE} —— A 基础模式。只有地址，走 WebView 承载官方 UI。</li>
 *   <li>{@link Kind#TOKEN_URL} —— B API 模式。多一个带 {@code ?token=} 的地址，
 *       用它换签名 API Cookie，之后才可能开原生能力。</li>
 * </ul>
 *
 * <p>这个类刻意**不依赖 Android**（只用 {@code java.net.URI}），这样协议层可以被
 * 其它 JVM 项目复用，也方便单测。
 */
public final class Credential {

    public enum Kind {
        NONE,
        TOKEN_URL
    }

    public final Kind kind;
    /** B 模式的带 token 地址（A 模式下为 null）。 */
    public final String tokenUrl;
    /** 基础地址（去掉 token 的 origin）。 */
    public final String baseUrl;

    private Credential(Kind kind, String tokenUrl, String baseUrl) {
        this.kind = kind;
        this.tokenUrl = tokenUrl;
        this.baseUrl = baseUrl;
    }

    public static Credential none(String baseUrl) {
        return new Credential(Kind.NONE, null, normalizeBase(baseUrl));
    }

    /**
     * 从用户输入的 token 地址构造。
     *
     * @param tokenUrl     dsh web 打印的完整地址，形如 http://127.0.0.1:3080/?token=abc
     * @param fallbackBase 解析失败时退回到的地址
     */
    public static Credential fromTokenUrl(String tokenUrl, String fallbackBase) {
        if (tokenUrl == null || tokenUrl.trim().isEmpty()) {
            return none(fallbackBase);
        }
        String t = tokenUrl.trim();
        if (!t.startsWith("http://") && !t.startsWith("https://")) {
            return none(fallbackBase);
        }
        String base = fallbackBase;
        try {
            URI u = URI.create(t);
            if (u.getScheme() != null && u.getAuthority() != null) {
                base = u.getScheme() + "://" + u.getAuthority();
            }
        } catch (Exception ignored) {
            // 解析失败就用 fallback
        }
        // 没有 token 参数就不是 B 模式 —— 否则会把一个普通地址误判成有凭证
        String token = queryParam(t, "token");
        if (token == null || token.isEmpty()) {
            return none(base);
        }
        return new Credential(Kind.TOKEN_URL, t, normalizeBase(base));
    }

    public boolean isPresent() {
        if (kind != Kind.TOKEN_URL || tokenUrl == null || tokenUrl.isEmpty()) {
            return false;
        }
        String token = queryParam(tokenUrl, "token");
        return token != null && !token.isEmpty();
    }

    /** 从 token 地址里取出 token 参数；取不到返回 null。 */
    public String token() {
        if (!isPresent()) {
            return null;
        }
        return queryParam(tokenUrl, "token");
    }

    /** 协议层所有请求都需要的 User-Agent 风格标识（便于服务端排查）。 */
    public static String clientId() {
        return "DSHMobile/0.1";
    }

    /* ------------------------------------------------------------------ */

    private static String normalizeBase(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        if (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        // 去掉偶发的路径尾巴，只保留 origin（token 地址带 ?token= 时也适用）
        int q = u.indexOf('?');
        if (q > 0) {
            u = u.substring(0, q);
        }
        return u;
    }

    /** 极简查询串解析，不引入 android.net.Uri，保持协议层纯 Java。 */
    public static String queryParam(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return null;
        }
        String query = url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(decode(pair), "");
            } else {
                params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return params.get(key);
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}

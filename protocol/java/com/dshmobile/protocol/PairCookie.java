package com.dshmobile.protocol;

/**
 * 配对链接与配对 cookie 的识别。
 *
 * <p>remote-web-ui 插件的配对流程（读源码确认）：
 * <ol>
 *   <li>桌面端生成二维码 → {@code https://<host>/pair-accept?pair=<一次性 token>}</li>
 *   <li>手机打开该链接 → 服务端 303 跳到 {@code /pair-app?grant=…}，
 *       同时 {@code Set-Cookie: dsh_pair=<deviceId>; Path=/; HttpOnly; Max-Age=1年}</li>
 *   <li>之后手机用 {@code dsh_pair} 作设备凭据，走插件网关 {@code /remote/api/*} 用完整宿主 API</li>
 * </ol>
 *
 * <p><b>关键约束</b>：{@code pair} token 是**一次性**的（short-lived, single-consume），
 * 所以不能先试探再使用；必须由用户真正打开一次。
 *
 * <p>纯函数、可单测（见 {@link SelfTest}）。
 */
public final class PairCookie {

    /** 插件默认的配对 cookie 名；配置可改，所以提供带名字的重载。 */
    public static final String DEFAULT_COOKIE = "dsh_pair";

    private PairCookie() {
    }

    /**
     * 这段文本像是配对链接吗（`/pair-accept?pair=…` 或任意带 `pair=` 的地址）？
     *
     * <p>用"像是"而不是严格解析：用户粘贴的可能是整段文字，也可能带引号。
     */
    public static boolean looksLikePairUrl(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String s = text.toLowerCase(java.util.Locale.ROOT);
        if (!s.contains("pair=") && !s.contains("/pair-accept")) {
            return false;
        }
        return s.contains("http://") || s.contains("https://");
    }

    /**
     * 从任意粘贴文本里提取配对链接（去掉引号/中文标点干扰）。
     *
     * @return 提取到的地址；找不到返回 null
     */
    public static String extractPairUrl(String text) {
        if (!looksLikePairUrl(text)) {
            return null;
        }
        String s = text.trim()
                .replace("\"", " ").replace("'", " ").replace("`", " ")
                .replace("，", " ").replace("。", " ").replace("\n", " ");
        for (String piece : s.split("\\s+")) {
            String low = piece.toLowerCase(java.util.Locale.ROOT);
            if ((low.startsWith("http://") || low.startsWith("https://"))
                    && (low.contains("pair=") || low.contains("/pair-accept"))) {
                return trimTrailing(piece);
            }
        }
        // 兜底：整段里截
        int start = s.indexOf("http");
        if (start < 0) {
            return null;
        }
        int end = start;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end))) {
            end++;
        }
        return trimTrailing(s.substring(start, end));
    }

    /**
     * 从 {@code Cookie} 头里取出设备 id。
     *
     * @param cookieHeader 形如 {@code "a=1; dsh_pair=abc; b=2"}
     * @param name         cookie 名（默认 {@link #DEFAULT_COOKIE}）
     * @return 设备 id；没有返回空串
     */
    public static String deviceFromCookie(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return "";
        }
        String key = (name == null || name.isEmpty()) ? DEFAULT_COOKIE : name;
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (key.equals(p.substring(0, eq).trim())) {
                return p.substring(eq + 1).trim();
            }
        }
        return "";
    }

    public static String deviceFromCookie(String cookieHeader) {
        return deviceFromCookie(cookieHeader, DEFAULT_COOKIE);
    }

    /** 去掉常见的尾部标点（中文句号、右括号之类）。 */
    private static String trimTrailing(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ',' || c == '.' || c == ')' || c == ']' || c == '>'
                    || c == '，' || c == '。' || c == '、' || c == '）' || c == '》') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }
}

package com.dshmobile.protocol;

/**
 * 从"粘贴进来的任意文本"里提取带 token 的启动地址。
 *
 * <p>用户实际会粘贴的东西五花八门：
 * <pre>
 *   dsh web: http://127.0.0.1:3080/?token=abc123
 *   http://192.168.1.5:3080/?token=abc123&amp;interactions=none
 *   "http://host:3080/?token=abc"      （带引号）
 *   https://dsh.example.com/?token=abc
 * </pre>
 * 所以这里做的是**容错提取**，而不是要求用户自己剪裁。
 *
 * <p>纯函数、可单测（见 {@link SelfTest}）。
 */
public final class TokenUrl {

    private TokenUrl() {
    }

    /**
     * 从文本里找第一个「http(s) 且带 token 参数」的地址。
     *
     * @return 提取到的地址；找不到返回 null
     */
    public static String extract(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return null;
        }
        // 去掉常见包裹符
        s = s.replace("\"", " ").replace("'", " ").replace("`", " ");
        int from = 0;
        while (true) {
            int start = indexOfScheme(s, from);
            if (start < 0) {
                return null;
            }
            int end = start;
            while (end < s.length() && !isDelimiter(s.charAt(end))) {
                end++;
            }
            String candidate = trimTrailingPunctuation(s.substring(start, end));
            if (hasTokenParam(candidate)) {
                return candidate;
            }
            from = end;
        }
    }

    /** 把地址里的 token 值打码，便于在界面上显示。 */
    public static String mask(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int idx = url.indexOf("token=");
        if (idx < 0) {
            return url;
        }
        int valueStart = idx + "token=".length();
        int valueEnd = valueStart;
        while (valueEnd < url.length() && url.charAt(valueEnd) != '&' && url.charAt(valueEnd) != '#') {
            valueEnd++;
        }
        String value = url.substring(valueStart, valueEnd);
        String masked = value.length() <= 6 ? "***" : value.substring(0, 4) + "…" + value.substring(value.length() - 2);
        return url.substring(0, valueStart) + masked + url.substring(valueEnd);
    }

    /** 地址是否带非空的 token 参数。 */
    public static boolean hasTokenParam(String url) {
        if (url == null) {
            return false;
        }
        int idx = url.indexOf("token=");
        if (idx < 0) {
            return false;
        }
        int valueStart = idx + "token=".length();
        if (valueStart >= url.length()) {
            return false;
        }
        char next = url.charAt(valueStart);
        return next != '&' && next != '#' && !Character.isWhitespace(next);
    }

    /* ------------------------------------------------------------------ */

    private static int indexOfScheme(String s, int from) {
        int http = s.indexOf("http://", from);
        int https = s.indexOf("https://", from);
        if (http < 0) {
            return https;
        }
        if (https < 0) {
            return http;
        }
        return Math.min(http, https);
    }

    private static boolean isDelimiter(char c) {
        return Character.isWhitespace(c) || c == '"' || c == '\'' || c == '`'
                || c == ')' || c == ']' || c == '}' || c == '<' || c == '>';
    }

    private static String trimTrailingPunctuation(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '.' || c == ',' || c == ';' || c == ')' || c == ']' || c == '}' || c == '\'' || c == '"') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }
}

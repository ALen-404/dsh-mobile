package com.dshmobile.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 JSON 解析器（零依赖）。
 *
 * <p>为什么自己写：协议层刻意不带 Android 依赖（{@code org.json} 是 Android 提供的，
 * 用了就没法在纯 JVM 里跑自检），也不想为此引入一个第三方 JSON 库。
 * 我们只需要把 DSH 的响应读成 Map/List 取值，不需要序列化、不需要流式解析。
 *
 * <p>支持：对象、数组、字符串（含反斜杠加 u 的 Unicode 转义、以及常见转义）、数字、true/false/null。
 * 不支持：注释、单引号、尾逗号（这些本来就不是合法 JSON）。
 *
 * <p>解析结果类型：{@code Map<String,Object>}、{@code List<Object>}、
 * {@code String}、{@code Double}、{@code Boolean}、{@code null}。
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    /** 解析一段 JSON 文本。格式错误抛 {@link IllegalArgumentException}。 */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON 文本为 null");
        }
        Json p = new Json(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.pos < p.src.length()) {
            throw new IllegalArgumentException("JSON 末尾有多余内容，位置 " + p.pos);
        }
        return v;
    }

    /** 便捷取值：把解析结果当对象，取某个键；不存在返回 null。 */
    @SuppressWarnings("unchecked")
    public static Object get(Object node, String key) {
        if (!(node instanceof Map)) {
            return null;
        }
        return ((Map<String, Object>) node).get(key);
    }

    /** 便捷取值：沿路径逐层取，任何一层缺失都返回 null。 */
    public static Object path(Object node, String... keys) {
        Object cur = node;
        for (String k : keys) {
            cur = get(cur, k);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    /** 便捷取值：字符串，没有就返回 fallback。 */
    public static String str(Object node, String key, String fallback) {
        Object v = get(node, key);
        return v instanceof String ? (String) v : fallback;
    }

    /** 便捷取值：数字，没有就返回 fallback。 */
    public static long num(Object node, String key, long fallback) {
        Object v = get(node, key);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

    /** 便捷取值：布尔，没有就返回 fallback。 */
    public static boolean bool(Object node, String key, boolean fallback) {
        Object v = get(node, key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    /** 便捷取值：数组，没有就返回空列表。 */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object node, String key) {
        Object v = get(node, key);
        return v instanceof List ? (List<Object>) v : new ArrayList<Object>();
    }

    /**
     * 把字符串转义成 JSON 字符串字面量的内容（不含首尾引号）。
     *
     * <p>拼装请求体时必须要它：用户输入里可能有引号、换行、反斜杠。
     */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * 把字符串转义并包成 JSON 字符串字面量（含首尾引号）。
     *
     * <p>写 JSON 时用它，避免手拼引号导致的转义遗漏。
     */
    public static String quote(String s) {
        return "\"" + escape(s) + "\"";
    }

    /* ------------------------------------------------------------------ */

    private Object readValue() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("JSON 意外结束");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            if (peek() != ':') {
                throw new IllegalArgumentException("对象缺少冒号，位置 " + pos);
            }
            pos++;
            skipWs();
            out.put(key, readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return out;
            }
            throw new IllegalArgumentException("对象格式错误，位置 " + pos);
        }
    }

    private List<Object> readArray() {
        List<Object> out = new ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipWs();
            out.add(readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return out;
            }
            throw new IllegalArgumentException("数组格式错误，位置 " + pos);
        }
    }

    private String readString() {
        if (peek() != '"') {
            throw new IllegalArgumentException("期望字符串，位置 " + pos);
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("字符串未闭合");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw new IllegalArgumentException("转义未完成");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (pos + 4 > src.length()) {
                        throw new IllegalArgumentException("unicode 转义不完整");
                    }
                    String hex = src.substring(pos, pos + 4);
                    pos += 4;
                    sb.append((char) Integer.parseInt(hex, 16));
                    break;
                default:
                    throw new IllegalArgumentException("未知转义 \\" + e);
            }
        }
    }

    private Double readNumber() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw new IllegalArgumentException("无法解析的值，位置 " + start);
        }
        return Double.valueOf(src.substring(start, pos));
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("JSON 意外结束");
        }
        return src.charAt(pos);
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw new IllegalArgumentException("期望 " + literal + "，位置 " + pos);
        }
        pos += literal.length();
    }
}

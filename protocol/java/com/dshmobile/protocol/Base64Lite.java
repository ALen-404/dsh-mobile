package com.dshmobile.protocol;

/**
 * 极简 Base64 编码（零依赖）。
 *
 * <p>为什么不用 {@code java.util.Base64}：它在 **Android API 26+** 才有，
 * 而本项目 minSdk 是 21；{@code android.util.Base64} 又是 Android 专有，
 * 用了协议层就不能在纯 JVM 里自检。所以自己写 30 行，两边都能跑、还能单测。
 */
public final class Base64Lite {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private Base64Lite() {
    }

    /** 标准 Base64（带 = 填充，无换行）。 */
    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(ALPHABET[(n >>> 18) & 0x3F]);
            sb.append(ALPHABET[(n >>> 12) & 0x3F]);
            sb.append(ALPHABET[(n >>> 6) & 0x3F]);
            sb.append(ALPHABET[n & 0x3F]);
            i += 3;
        }
        int remaining = data.length - i;
        if (remaining == 1) {
            int n = (data[i] & 0xFF) << 16;
            sb.append(ALPHABET[(n >>> 18) & 0x3F]);
            sb.append(ALPHABET[(n >>> 12) & 0x3F]);
            sb.append("==");
        } else if (remaining == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(ALPHABET[(n >>> 18) & 0x3F]);
            sb.append(ALPHABET[(n >>> 12) & 0x3F]);
            sb.append(ALPHABET[(n >>> 6) & 0x3F]);
            sb.append('=');
        }
        return sb.toString();
    }

    /** 解码（允许空白与 = 填充）。非法字符抛 IllegalArgumentException。 */
    public static byte[] decode(String text) {
        if (text == null || text.isEmpty()) {
            return new byte[0];
        }
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' || c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                continue;
            }
            clean.append(c);
        }
        int len = clean.length();
        if (len % 4 == 1) {
            throw new IllegalArgumentException("Base64 长度非法");
        }
        byte[] out = new byte[len * 3 / 4];
        int outIndex = 0;
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < len; i++) {
            int value = indexOf(clean.charAt(i));
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[outIndex++] = (byte) ((buffer >>> bits) & 0xFF);
            }
        }
        return out;
    }

    private static int indexOf(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 26;
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 52;
        }
        if (c == '+') {
            return 62;
        }
        if (c == '/') {
            return 63;
        }
        throw new IllegalArgumentException("非法 Base64 字符: " + c);
    }
}

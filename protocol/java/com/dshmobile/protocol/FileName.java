package com.dshmobile.protocol;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Locale;

/**
 * 从下载响应里推断文件名。
 *
 * <p>为什么要单独抽出来：这段逻辑有真实的边界情况（RFC 5987 的 {@code filename*=UTF-8''}、
 * 带引号的 {@code filename="…"}、URL 里的中文、路径穿越 {@code ..}），
 * 放在 Activity 里就只能靠真机试。抽成纯函数后可以单测。
 *
 * <p>不依赖 Android：MIME → 扩展名的兜底映射只覆盖常见类型，
 * 需要更全的映射时由调用方用平台的 MimeTypeMap 补。
 */
public final class FileName {

    private FileName() {
    }

    /**
     * 推断文件名（不含扩展名兜底）。
     *
     * <p>优先级：Content-Disposition 的 {@code filename*} → {@code filename} →
     * URL 最后一段 → {@code "download"}。会去掉路径成分，防止 {@code ../} 穿越。
     */
    public static String guess(String url, String contentDisposition) {
        String name = fromContentDisposition(contentDisposition);
        if (name == null || name.isEmpty()) {
            name = fromUrl(url);
        }
        return sanitize(name);
    }

    /** 常见 MIME 的扩展名（不含点）。未知返回 null。 */
    public static String extensionFor(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        String m = mimeType.toLowerCase(Locale.ROOT);
        int semi = m.indexOf(';');
        if (semi >= 0) {
            m = m.substring(0, semi).trim();
        }
        switch (m) {
            case "text/plain":
                return "txt";
            case "text/markdown":
                return "md";
            case "text/html":
                return "html";
            case "text/csv":
                return "csv";
            case "application/json":
                return "json";
            case "application/pdf":
                return "pdf";
            case "application/zip":
                return "zip";
            case "application/gzip":
                return "gz";
            case "application/x-tar":
                return "tar";
            case "image/png":
                return "png";
            case "image/jpeg":
                return "jpg";
            case "image/gif":
                return "gif";
            case "image/webp":
                return "webp";
            case "image/svg+xml":
                return "svg";
            case "audio/mpeg":
                return "mp3";
            case "video/mp4":
                return "mp4";
            case "application/octet-stream":
                return "bin";
            default:
                return null;
        }
    }

    /** 没有扩展名时，用 MIME 猜一个补上。 */
    public static String ensureExtension(String name, String mimeType) {
        if (name == null || name.isEmpty()) {
            name = "download";
        }
        if (name.contains(".")) {
            return name;
        }
        String ext = extensionFor(mimeType);
        return ext == null ? name : name + "." + ext;
    }

    /* ------------------------------------------------------------------ */

    private static String fromContentDisposition(String cd) {
        if (cd == null || cd.isEmpty()) {
            return null;
        }
        int star = cd.indexOf("filename*=");
        if (star >= 0) {
            String rest = cd.substring(star + "filename*=".length()).trim();
            int semi = rest.indexOf(';');
            if (semi >= 0) {
                rest = rest.substring(0, semi);
            }
            rest = rest.replace("\"", "").trim();
            int quote = rest.indexOf("''");
            if (quote >= 0) {
                String charset = rest.substring(0, quote);
                rest = rest.substring(quote + 2);
                try {
                    return URLDecoder.decode(rest, charset.isEmpty() ? "UTF-8" : charset);
                } catch (UnsupportedEncodingException e) {
                    return rest;
                } catch (IllegalArgumentException e) {
                    return rest;
                }
            }
            return rest;
        }
        int idx = cd.indexOf("filename=");
        if (idx >= 0) {
            String rest = cd.substring(idx + "filename=".length()).trim();
            int semi = rest.indexOf(';');
            if (semi >= 0) {
                rest = rest.substring(0, semi);
            }
            return rest.replace("\"", "").trim();
        }
        return null;
    }

    private static String fromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        // URL 里的中文/空格常是百分号编码，尽量还原
        try {
            last = URLDecoder.decode(last.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
            // 解不开就用原样
        }
        return last;
    }

    /** 只保留最后一段，去掉路径成分与控制字符。 */
    private static String sanitize(String name) {
        if (name == null) {
            return "download";
        }
        String n = name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c >= 0x20 && c != 0x7F && c != ':' && c != '*' && c != '?' && c != '"'
                    && c != '<' && c != '>' && c != '|') {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        if (out.isEmpty() || ".".equals(out) || "..".equals(out)) {
            return "download";
        }
        return out;
    }
}

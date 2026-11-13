package com.dshmobile;

import android.net.Uri;
import android.webkit.CookieManager;

/**
 * WebView Cookie 的最小工具集。
 *
 * <p>存在的理由：Android 单进程 WebView 共用**一个** CookieManager，而 Cookie 是
 * **按域名（不含端口）** 隔离的。于是两个实例如果恰好同域名不同端口（局域网里很常见：
 * 一台机器跑两个 DSH），它们的登录态会互相覆盖 —— 这就是 README 里那条"多实例可能串号"。
 *
 * <p>做不到真正的隔离（需要独立进程或独立 WebView 数据目录），所以这里只提供
 * **检测 + 按域名清理**，让 App 能在串号发生前提醒用户。
 */
final class WebCookies {

    private WebCookies() {
    }

    /** Cookie 的作用域键：只有主机名（端口不参与 Cookie 隔离）。 */
    static String hostKey(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 清掉某个域名下的全部 Cookie。
     *
     * <p>CookieManager 没有"按域名批量删除"的 API，只能先读出来再逐个置过期。
     * http / https 两种 scheme 都要处理（同一个域名下它们可能都有）。
     *
     * @return 清掉的 Cookie 条数
     */
    static int clearForHost(String host) {
        if (host == null || host.isEmpty()) {
            return 0;
        }
        CookieManager cm = CookieManager.getInstance();
        int cleared = 0;
        String[] schemes = {"https://", "http://"};
        for (String scheme : schemes) {
            String probe = scheme + host + "/";
            String cookie;
            try {
                cookie = cm.getCookie(probe);
            } catch (Exception e) {
                continue;
            }
            if (cookie == null || cookie.isEmpty()) {
                continue;
            }
            for (String part : cookie.split(";")) {
                String trimmed = part.trim();
                int eq = trimmed.indexOf('=');
                String name = eq > 0 ? trimmed.substring(0, eq).trim() : "";
                if (name.isEmpty()) {
                    continue;
                }
                try {
                    // 标准做法：同名 + Max-Age=0 + Path=/ → 立即失效
                    cm.setCookie(probe, name + "=; Max-Age=0; Path=/");
                    cleared++;
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        try {
            cm.flush();
        } catch (Exception ignored) {
            // ignore
        }
        return cleared;
    }

    /** 当前该域名下挂了多少 Cookie（用于判断"有没有需要清的东西"）。 */
    static int countForHost(String host) {
        if (host == null || host.isEmpty()) {
            return 0;
        }
        CookieManager cm = CookieManager.getInstance();
        int count = 0;
        String[] schemes = {"https://", "http://"};
        for (String scheme : schemes) {
            try {
                String cookie = cm.getCookie(scheme + host + "/");
                if (cookie == null || cookie.isEmpty()) {
                    continue;
                }
                for (String part : cookie.split(";")) {
                    if (part.trim().contains("=")) {
                        count++;
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return count;
    }
}

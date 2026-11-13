package com.dshmobile;

import android.os.Handler;
import android.os.Looper;

import com.dshmobile.protocol.Diagnostics;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 连接预检：判断一个实例当前处在什么状态。
 *
 * <p>只发一个 GET 请求，不改任何服务端状态。用途有两个：
 * <ul>
 *   <li>让用户在打开实例前就知道"是网络问题、隧道问题还是鉴权问题"</li>
 *   <li>为未来的能力探测（能不能开原生功能）打底</li>
 * </ul>
 *
 * <p>注意 401 是**正常**结果：DSH 只服务带鉴权的地址，裸访问根路径必然 401。
 * 所以判定"可用"的标准是 200 或 401，而不是 200。
 */
public final class Preflight {

    /** 预检结果。 */
    public static final class Result {
        public final int status;
        public final String advice;
        public final boolean usable;

        Result(int status, String advice, boolean usable) {
            this.status = status;
            this.advice = advice;
            this.usable = usable;
        }
    }

    public interface Callback {
        void onResult(Result result);
    }

    private Preflight() {
    }

    public static void check(final String baseUrl, final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                int status = 0;
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
                    String protocol = url.getProtocol();
                    if (!"http".equals(protocol) && !"https".equals(protocol)) {
                        throw new IllegalArgumentException("只支持 http/https");
                    }
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setRequestProperty("User-Agent", "DSHMobile/0.1 (preflight)");
                    conn.connect();
                    status = conn.getResponseCode();
                } catch (Exception e) {
                    status = 0;
                } finally {
                    // 必须放 finally：异常路径上不 disconnect 会漏连接
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Exception ignored) {
                            // ignore
                        }
                    }
                }
                final int s = status;
                boolean usable = s == 200 || s == 401 || s == 302 || s == 303;
                final Result r = new Result(s, Diagnostics.explain(s, null), usable);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(r);
                    }
                });
            }
        }, "dsh-preflight").start();
    }
}

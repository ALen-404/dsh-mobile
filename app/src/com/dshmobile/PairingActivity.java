package com.dshmobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dshmobile.protocol.DshClient;
import com.dshmobile.protocol.PairCookie;

/**
 * 配对页：用户粘贴 `…/pair-accept?pair=…` 后，真正在 WebView 里打开一次，
 * 拿到配对设备 cookie，然后<b>直接进原生界面</b>。
 *
 * <p>为什么必须用 WebView 打开一次：pair token 是**一次性**的，服务端只在这次响应里
 * 下发 `Set-Cookie: dsh_pair=<deviceId>`；看图省事用 HTTP 客户端也能拿到 cookie，
 * 但 WebView 能顺带把后续的跳转/服务端渲染都走完，跟用户手动操作等价。
 *
 * <p>拿到设备 id 后：
 * <ol>
 *   <li>探测一次 {@code /remote/api/session/list} —— 通了就存成"配对实例"，进 {@link ChatActivity}</li>
 *   <li>探测不通就存成 WebView 实例，退回官方界面（不让用户卡死）</li>
 * </ol>
 */
public class PairingActivity extends Activity {

    public static final String EXTRA_PAIR_URL = "pairUrl";

    private WebView web;
    private TextView statusText;
    private String pairUrl;
    private String origin;
    private boolean handled;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        pairUrl = getIntent().getStringExtra(EXTRA_PAIR_URL);
        if (pairUrl == null || pairUrl.isEmpty()) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        origin = originOf(pairUrl);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        root.setPadding(UiKit.dp(this, 18), UiKit.statusBarHeight(this) + UiKit.dp(this, 24),
                UiKit.dp(this, 18), UiKit.dp(this, 16));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        View logo = new View(this);
        logo.setBackground(UiKit.rounded(this, 8, UiKit.ACCENT));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 24), UiKit.dp(this, 24));
        logoLp.setMargins(0, 0, UiKit.dp(this, 10), 0);
        brand.addView(logo, logoLp);
        TextView title = UiKit.text(this, "正在配对…", 18, UiKit.TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        brand.addView(title);
        root.addView(brand);

        statusText = UiKit.text(this, "正在打开配对链接，请在下面完成必要的确认（如果有）。",
                12, UiKit.TEXT_SECONDARY);
        statusText.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 10));
        root.addView(statusText);

        web = new WebView(this);
        web.setBackgroundColor(UiKit.BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                onLanded(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // 渲染进程被系统回收：这条 WebView 已经不可用。返回 true 表示已处理，
                // 不让系统把整个 App 一起杀掉（配对页只是一次性的，直接提示重来即可）。
                handled = false;
                statusText.setText("页面渲染进程被系统回收了，请返回重新打开配对链接。");
                return true;
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        web.loadUrl(pairUrl);
    }

    /**
     * 页面加载完成时判断是不是已经到了配对成功页。
     *
     * <p>`/pair-app?grant=…` 是成功落点（拿到的 grant 会换成设备 cookie），
     * 之后 SPA 会 replaceState 到 `/`；两个都算成功。
     */
    private void onLanded(String url) {
        if (handled || url == null) {
            return;
        }
        if (!url.contains("/pair-app") && !isRootOfOrigin(url)) {
            return;
        }
        handled = true;
        String cookie = CookieManager.getInstance().getCookie(origin);
        final String device = PairCookie.deviceFromCookie(cookie);
        if (device.isEmpty()) {
            handled = false;
            statusText.setText("没拿到配对凭据，可能链接已过期（配对码是一次性的，需要重新生成二维码）。");
            return;
        }
        statusText.setText("配对成功（设备 " + mask(device) + "），正在验证原生能力…");
        verifyAndSave(device);
    }

    private void verifyAndSave(final String device) {
        Task.run(this, "dsh-pair-verify", new Task.Work<Boolean>() {
            @Override
            public Boolean run() {
                try {
                    DshClient.Session s = DshClient.connectWithDevice(origin, device);
                    DshClient.listSessions(s);
                    return Boolean.TRUE;
                } catch (Exception e) {
                    return Boolean.FALSE;
                }
            }
        }, new Task.Done<Boolean>() {
            @Override
            public void onResult(Boolean nativeOk, String error) {
                InstanceStore store = new InstanceStore(PairingActivity.this);
                boolean ok = Boolean.TRUE.equals(nativeOk);
                Instance inst = new Instance(hostOf(origin), origin,
                        "", ok ? device : "");
                int idx = store.indexOfUrl(origin);
                if (idx >= 0) {
                    store.update(idx, inst);
                } else {
                    store.add(inst);
                }
                store.setLastOpenedUrl(origin);
                store.setHostOwner(origin, WebCookies.hostKey(origin));
                if (!ok) {
                    statusText.setText("配对成功，但这台前端的原生接口不可用，已切到官方界面。");
                }
                statusText.postDelayed(() -> {
                    Intent it = new Intent(PairingActivity.this,
                            ok ? ChatActivity.class : WebActivity.class);
                    if (!ok) {
                        it.putExtra(WebActivity.EXTRA_INDEX, Math.max(0, store.indexOfUrl(origin)));
                    }
                    it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                    finish();
                }, ok ? 200 : 900);
            }
        });
    }

    private boolean isRootOfOrigin(String url) {
        try {
            Uri u = Uri.parse(url);
            Uri o = Uri.parse(origin);
            String path = u.getPath();
            return u.getHost() != null && u.getHost().equals(o.getHost())
                    && (path == null || path.isEmpty() || "/".equals(path));
        } catch (Exception e) {
            return false;
        }
    }

    private String originOf(String url) {
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme() == null ? "https" : u.getScheme();
            String port = u.getPort() > 0 ? ":" + u.getPort() : "";
            return scheme + "://" + u.getHost() + port;
        } catch (Exception e) {
            return url;
        }
    }

    private String hostOf(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? "DSH" : host;
        } catch (Exception e) {
            return "DSH";
        }
    }

    private String mask(String device) {
        if (device.length() <= 8) {
            return device;
        }
        return device.substring(0, 6) + "…";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
    }
}

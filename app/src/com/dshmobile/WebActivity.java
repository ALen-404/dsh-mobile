package com.dshmobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import com.dshmobile.protocol.Diagnostics;
import com.dshmobile.protocol.FileName;
import com.dshmobile.protocol.TokenUrl;

/**
 * 单个实例的浏览界面：全屏 WebView + 极简顶栏 + 失败诊断页。
 *
 * <p>L0 层的目标不是"复刻 UI"，而是把移动端浏览体验修好：
 * 无地址栏、返回键符合直觉、失败时告诉你到底是哪一层坏了。
 */
public class WebActivity extends Activity {

    public static final String EXTRA_INDEX = "index";

    /** 追加到 WebView UA 后面的标识（下载时也带上同一个 UA）。 */
    static final String UA_SUFFIX = " DSHMobile/0.1";

    private InstanceStore store;
    private Instance instance;
    private int index = -1;

    private WebView web;
    private android.widget.ProgressBar progress;
    private TextView errorHead;
    private FrameLayout container;
    private LinearLayout errorPanel;
    private TextView errorText;

    /** true = 强制桌面宽度（>=1100px），可绕开某些竖屏适配层的问题。 */
    private boolean desktopWidth = false;

    private String lastFailedUrl = "";
    private int lastFailedStatus = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new InstanceStore(this);

        index = getIntent().getIntExtra(EXTRA_INDEX, -1);
        List<Instance> all = store.load();
        if (index < 0 || index >= all.size()) {
            finish();
            return;
        }
        instance = all.get(index);

        container = new FrameLayout(this);
        container.addView(buildWebView(), match());
        container.addView(buildTopBar(), topBarParams());
        container.addView(buildErrorPanel(), match());
        setContentView(container);

        loadStart();
        maybeWarnCookieSharing();
    }

    /**
     * 提醒"登录态可能串号"。
     *
     * <p>WebView 的 Cookie 按**域名**共享（端口不参与隔离），所以两个实例如果是
     * 同域名不同端口（一台机器跑两个 DSH 时很常见），登录态会互相覆盖。
     *
     * <p>真正的隔离需要独立进程/独立 WebView 数据目录，当前不现实；这里退一步：
     * 记录"这个域名上次是哪个实例在用"，换人时提醒并按需清理。
     */
    private void maybeWarnCookieSharing() {
        String host = WebCookies.hostKey(instance.url);
        if (host.isEmpty()) {
            return;
        }
        String owner = store.getHostOwner(host);
        store.setHostOwner(host, instance.url);
        if (owner.isEmpty() || owner.equals(instance.url)) {
            return;
        }
        final int cookieCount = WebCookies.countForHost(host);
        if (cookieCount == 0) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("登录态可能串号")
                .setMessage("这个域名（" + host + "）上次是另一个实例在用：\n" + owner
                        + "\n\n浏览器 Cookie 按域名共享、不区分端口，所以当前这里可能留着 "
                        + cookieCount + " 条别的实例的登录信息。\n\n"
                        + "清除后需要重新配对/登录；不清除则可能看到另一个实例的登录态。")
                .setPositiveButton("清除并继续", (d, w) -> {
                    int cleared = WebCookies.clearForHost(host);
                    toast("已清除 " + cleared + " 条 Cookie");
                    if (web != null) {
                        web.reload();
                    }
                })
                .setNegativeButton("直接继续", null)
                .show();
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private FrameLayout.LayoutParams topBarParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        return lp;
    }

    private WebView buildWebView() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // 让 window.open / target=_blank 走到 onCreateWindow（否则点了没反应）
        s.setSupportMultipleWindows(true);
        s.setUserAgentString(s.getUserAgentString() + UA_SUFFIX);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new PopupChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                hideError();
                applyViewportPreference();
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req, android.webkit.WebResourceResponse resp) {
                // 只对主文档的错误做诊断，子资源（图片/脚本）401 之类不打扰用户
                if (req != null && req.isForMainFrame()) {
                    lastFailedStatus = resp.getStatusCode();
                    lastFailedUrl = req.getUrl().toString();
                    showError(lastFailedStatus, lastFailedUrl);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    lastFailedStatus = 0;
                    lastFailedUrl = req.getUrl().toString();
                    showError(0, lastFailedUrl);
                }
            }

            /**
             * 渲染进程被系统回收（低内存、崩溃）。
             *
             * <p>必须返回 true 并自己处理：API 26+ 上返回 false 会让系统**杀掉整个应用**，
             * 用户看到的就是"点开就闪退"。
             */
            @Override
            public boolean onRenderProcessGone(WebView view,
                                               android.webkit.RenderProcessGoneDetail detail) {
                handleRenderGone();
                return true;
            }
        });

        // 下载：必须自己处理 —— 不能丢给系统浏览器，因为 URL 需要 API Cookie
        web.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                enqueueDownload(url, userAgent, contentDisposition, mimeType);
            }
        });
        return web;
    }

    /**
     * 把下载交给系统 DownloadManager，并**带上当前 WebView 的 Cookie**。
     *
     * <p>为什么不能直接丢给浏览器：DSH 的下载链接走 {@code /api/...}，需要鉴权 Cookie，
     * 外部浏览器没有这个 Cookie，打开就是 401。
     *
     * <p>存到应用专属外部目录（{@code Android/data/<pkg>/files/Download}）：
     * 不需要任何存储权限，卸载应用时随之清理。
     */
    private void enqueueDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (url == null || url.isEmpty()) {
            return;
        }
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, "这种内联资源暂不支持下载", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String fileName = FileName.ensureExtension(
                    FileName.guess(url, contentDisposition), mimeType);
            android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                req.addRequestHeader("Cookie", cookie);
            }
            // 带上和 WebView 一致的 UA：有些服务端会按 UA 拒绝非浏览器请求
            req.addRequestHeader("User-Agent",
                    userAgent == null || userAgent.isEmpty() ? "DSHMobile" : userAgent);
            req.setMimeType(mimeType);
            req.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(this,
                    android.os.Environment.DIRECTORY_DOWNLOADS, fileName);
            req.setTitle(fileName);
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(this, "系统下载服务不可用", Toast.LENGTH_SHORT).show();
                return;
            }
            dm.enqueue(req);
            Toast.makeText(this, "开始下载：" + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    /* ------------------------------------------------------------------ */
    /* WebChromeClient：JS 弹窗 / 文件选择 / 权限                          */
    /* ------------------------------------------------------------------ */

    private static final int REQ_FILE_CHOOSER = 4001;
    /** 等待文件选择结果的回调；同一时刻只允许一个选择框。 */
    private ValueCallback<Uri[]> fileChooserCallback;

    /**
     * DSH 的官方 Web UI 会用到三样东西，WebView 默认都不支持：
     * <ul>
     *   <li>{@code alert/confirm/prompt} —— 默认 WebChromeClient 什么都不弹，
     *       页面看起来像"点了没反应"</li>
     *   <li>文件选择（附件按钮）—— 需要 {@code onShowFileChooser} + onActivityResult 回传</li>
     *   <li>媒体权限 —— 需要显式答复，否则请求永远挂着</li>
     * </ul>
     */
    private class DshChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (progress == null) {
                return;
            }
            if (newProgress >= 100) {
                progress.setVisibility(View.GONE);
            } else {
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(newProgress);
            }
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message,
                                 final android.webkit.JsResult result) {
            new AlertDialog.Builder(WebActivity.this)
                    .setMessage(message)
                    .setPositiveButton("确定", (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message,
                                   final android.webkit.JsResult result) {
            new AlertDialog.Builder(WebActivity.this)
                    .setMessage(message)
                    .setPositiveButton("确定", (d, w) -> result.confirm())
                    .setNegativeButton("取消", (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                  final android.webkit.JsPromptResult result) {
            final EditText input = new EditText(WebActivity.this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            if (defaultValue != null) {
                input.setText(defaultValue);
                input.setSelection(input.getText().length());
            }
            new AlertDialog.Builder(WebActivity.this)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("确定", (d, w) -> result.confirm(input.getText().toString()))
                    .setNegativeButton("取消", (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            // 上一次没结束的选择框先收尾，避免 WebView 卡在等待状态
            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(null);
            }
            fileChooserCallback = callback;

            Intent intent;
            try {
                intent = params != null ? params.createIntent() : null;
            } catch (Exception e) {
                intent = null;
            }
            if (intent == null) {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if (params != null) {
                    String[] types = params.getAcceptTypes();
                    if (types != null) {
                        for (String t : types) {
                            // 只接受标准 MIME（"image/*"）；扩展名（".png"）忽略
                            if (t != null && t.contains("/")) {
                                intent.setType(t);
                                break;
                            }
                        }
                    }
                }
            }
            try {
                startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE_CHOOSER);
            } catch (Exception e) {
                fileChooserCallback = null;
                Toast.makeText(WebActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                callback.onReceiveValue(null);
            }
            return true;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            // 只自动批准剪贴板之类无害的能力；音视频明确拒绝并告诉用户原因，
            // 免得页面一直挂着等待（DSH 官方 UI 正常也不需要摄像头/麦克风）。
            runOnUiThread(() -> {
                String[] resources = request.getResources();
                boolean wantsMedia = false;
                if (resources != null) {
                    for (String r : resources) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                                || PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                            wantsMedia = true;
                            break;
                        }
                    }
                }
                if (wantsMedia) {
                    Toast.makeText(WebActivity.this,
                            "页面请求了麦克风/摄像头，本版本不支持", Toast.LENGTH_SHORT).show();
                    request.deny();
                } else {
                    request.grant(resources);
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE_CHOOSER) {
            return;
        }
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                results = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    results[i] = clip.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
        }
    }

    private View buildTopBar() {
        // 外层竖直：上排按钮 + 底部一条细进度条
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(0xF2FFFFFF);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(2), dp(4), dp(2));

        TextView close = iconButton("‹");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(close);

        TextView title = new TextView(this);
        title.setText(instance.name);
        title.setSingleLine(true);
        title.setTextSize(14);
        title.setPadding(dp(6), 0, dp(6), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView reload = iconButton("⟳");
        reload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadStart();
            }
        });
        bar.addView(reload);

        final TextView more = iconButton("⋯");
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(more);
            }
        });
        bar.addView(more);

        outer.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 加载进度：WebView 不失焦、不占空间，只是一条 3dp 的线
        progress = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setVisibility(View.GONE);
        outer.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        return outer;
    }

    private TextView iconButton(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(20);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        tv.setTextColor(Color.parseColor("#333333"));
        return tv;
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, desktopWidth ? "切换为手机宽度" : "切换为桌面宽度");
        menu.getMenu().add(0, 2, 1, "复制地址");
        menu.getMenu().add(0, 3, 2, "清除本机登录状态");
        menu.getMenu().add(0, 4, 3, "用启动地址登录…");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    desktopWidth = !desktopWidth;
                    web.reload();
                    return true;
                case 2:
                    copy(instance.url);
                    return true;
                case 3:
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    toast("已清除 Cookie，请重新配对/登录");
                    web.reload();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private View buildErrorPanel() {
        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setBackgroundColor(0xFFFAFAFA);
        errorPanel.setPadding(dp(28), dp(28), dp(28), dp(28));

        errorHead = new TextView(this);
        errorHead.setText("连接不上");
        errorHead.setTextSize(20);
        errorPanel.addView(errorHead);

        errorText = new TextView(this);
        errorText.setTextSize(13);
        errorText.setPadding(0, dp(16), 0, dp(20));
        errorText.setTextIsSelectable(true);
        errorPanel.addView(errorText);

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setAllCaps(false);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadStart();
            }
        });
        errorPanel.addView(retry);

        // 401/403 时的真正出口：用启动地址登录（DSH 只服务带 token 的地址）
        Button tokenLogin = new Button(this);
        tokenLogin.setText("用启动地址登录");
        tokenLogin.setAllCaps(false);
        tokenLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptTokenLogin();
            }
        });
        errorPanel.addView(tokenLogin);

        Button back = new Button(this);
        back.setText("返回实例列表");
        back.setAllCaps(false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        errorPanel.addView(back);

        errorPanel.setVisibility(View.GONE);
        return errorPanel;
    }

    /**
     * 渲染进程挂了之后重建 WebView。
     *
     * <p>挂掉的 WebView 已经不可用，必须从容器移除并销毁，再换一个新的；
     * 否则后续所有调用都会异常。
     */
    private void handleRenderGone() {
        if (isFinishing()) {
            return;
        }
        WebView dead = web;
        web = null;
        if (dead != null) {
            try {
                if (container != null) {
                    container.removeView(dead);
                }
                dead.destroy();
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (progress != null) {
            progress.setVisibility(View.GONE);
        }
        // 重建并立刻重新加载
        WebView fresh = buildWebView();
        if (container != null) {
            container.addView(fresh, 0, match());
        }
        showErrorText("网页渲染进程被系统回收了（通常是内存不足）。\n\n"
                + "App 已自动重建页面，正在重新加载；如果一直停在这里，可以点「重试」。\n"
                + "反复出现的话，建议关掉其他应用，或改用系统浏览器打开这个地址。");
        // 回调本身就在主线程，直接重载即可
        loadStart();
    }

    private void loadStart() {
        if (instance.hasApiCredential()) {
            // B 模式：带 token 的地址先换取会话，再进 UI
            web.loadUrl(instance.tokenUrl);
        } else {
            web.loadUrl(instance.url);
        }
    }

    /**
     * 让用户粘贴 `dsh` 输出里的那一行，解析出启动地址后直接登录。
     *
     * <p>存在的原因：App 里打开 DSH 地址必然是 401（官方 UI 需要登录态），
     * 而浏览器里"已经登录"是因为有配对 Cookie。以前这个 401 是个死胡同 ——
     * 报错页只有"重试/返回"，用户不知道该干什么。现在这里有出口。
     */
    private void promptTokenLogin() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint("dsh web: http://192.168.1.5:3080/?token=…");
        input.setMinLines(2);

        new AlertDialog.Builder(this)
                .setTitle("用启动地址登录")
                .setMessage("在跑 DSH 的电脑上启动一次，终端会打印类似这样的一行：\n\n"
                        + "dsh web: http://127.0.0.1:3080/?token=xxxx\n\n"
                        + "把**整行**复制粘贴到下面（App 会自动提取地址）。\n\n"
                        + "找不到这行也没关系：桌面版 DSH 默认不显示它，可以用命令行启动 DSH。")
                .setView(input)
                .setPositiveButton("登录", (dialog, which) -> {
                    String tokenUrl = TokenUrl.extract(input.getText().toString());
                    if (tokenUrl == null) {
                        toast("没找到带 token 的地址，请粘贴完整那一行");
                        return;
                    }
                    // 记到实例上：下次直接用它，同时解锁原生能力
                    instance.tokenUrl = tokenUrl;
                    int idx = store.indexOfUrl(instance.url);
                    if (idx >= 0) {
                        store.update(idx, instance);
                    }
                    hideError();
                    toast("已保存启动地址，正在登录…");
                    web.loadUrl(tokenUrl);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showError(int status, String url) {
        if (errorHead != null) {
            errorHead.setText(status == 0 ? "连接不上" : Diagnostics.shortLabel(status));
        }
        errorText.setText(Diagnostics.explain(status, url));
        errorPanel.setVisibility(View.VISIBLE);
        errorPanel.bringToFront();
    }

    /** 直接显示一段自定义说明（不经过状态码翻译）。 */
    private void showErrorText(String message) {
        errorText.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
        errorPanel.bringToFront();
    }

    private void hideError() {
        errorPanel.setVisibility(View.GONE);
    }

    /**
     * 桌面宽度模式：把 viewport 钉在 1100px。
     *
     * <p>实测：DSH 的第三方竖屏适配层在 width >= 1100 时整体不激活，
     * 于是回落到官方桌面布局 —— 对于窄屏适配有问题的环境，这是一个有效的逃生口。
     */
    private void applyViewportPreference() {
        int width = desktopWidth ? 1100 : 0;
        String js = width == 0
                ? "(function(){var m=document.querySelector('meta[name=viewport]');if(m){m.setAttribute('content','width=device-width, initial-scale=1');}})()"
                : "(function(){var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}m.setAttribute('content','width=" + width + ", user-scalable=yes');})()";
        web.evaluateJavascript(js, null);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 弹窗用的临时 WebView 必须在这里销毁，否则会泄漏
        if (popupWeb != null) {
            try {
                popupWeb.destroy();
            } catch (Exception ignored) {
                // ignore
            }
            popupWeb = null;
        }
        // 主 WebView 也要销毁：否则它的渲染进程/JS 上下文会一直挂着
        if (web != null) {
            try {
                if (container != null) {
                    container.removeView(web);
                }
                web.stopLoading();
                web.setWebChromeClient(null);
                web.setWebViewClient(null);
                web.destroy();
            } catch (Exception ignored) {
                // ignore
            }
            web = null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 新窗口（window.open / target=_blank）                               */
    /* ------------------------------------------------------------------ */

    /** 为 popup 临时创建的 WebView（只用来截获目标 URL）。 */
    private WebView popupWeb;

    /**
     * 处理 {@code window.open} / {@code target=_blank}。
     *
     * <p>为什么需要：不实现这个回调时，这类链接**点了完全没反应**（WebView 默认不支持多窗口）。
     * 但直接说"支持多窗口"又不行 —— 会弹出一个无法使用的空白窗口，比不响应更糟。
     *
     * <p>做法：让 WebView 把"新窗口"交给一个临时 WebView，我们截获它想加载的 URL，
     * 然后按同源/异源分别处理（同源就在当前页打开，异源交给系统浏览器）。
     */
    private class PopupChromeClient extends DshChromeClient {
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      android.os.Message resultMsg) {
            if (popupWeb != null) {
                try {
                    popupWeb.destroy();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            popupWeb = new WebView(WebActivity.this);
            popupWeb.getSettings().setJavaScriptEnabled(false);
            popupWeb.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    handlePopupUrl(request.getUrl() == null ? "" : request.getUrl().toString());
                    return true;
                }

                @SuppressWarnings("deprecation")
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    handlePopupUrl(url);
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popupWeb);
            resultMsg.sendToTarget();
            return true;
        }
    }

    /** 决定一个"新窗口 URL"的去处。 */
    private void handlePopupUrl(String url) {
        if (url == null || url.isEmpty() || "about:blank".equals(url)) {
            toast("该页面尝试打开新窗口，本版本不支持内嵌窗口");
            return;
        }
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            // mailto: / tel: / 自定义 scheme —— 交给系统
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception e) {
                toast("这个链接无法打开：" + scheme);
            }
            return;
        }
        String currentHost = "";
        try {
            currentHost = Uri.parse(instance.url).getHost();
        } catch (Exception ignored) {
            // ignore
        }
        String targetHost = uri.getHost() == null ? "" : uri.getHost();
        if (currentHost != null && !currentHost.isEmpty() && currentHost.equalsIgnoreCase(targetHost)) {
            // 同源：直接在当前页打开，保持登录态与体验
            web.loadUrl(url);
        } else {
            // 异源：外部浏览器打开（避免把第三方页面放进来，也避免 cookie 串用）
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
                toast("已在浏览器打开");
            } catch (Exception e) {
                toast("没有可用的浏览器");
            }
        }
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("DSH", text));
            toast("已复制");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}

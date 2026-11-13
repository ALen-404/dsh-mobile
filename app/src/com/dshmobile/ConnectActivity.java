package com.dshmobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dshmobile.protocol.DshClient;
import com.dshmobile.protocol.PairCookie;
import com.dshmobile.protocol.TokenUrl;

/**
 * 首次启动的连接页 —— 一屏说清怎么连上自己的 DSH。
 *
 * <p>手机端复刻 DSH 之后，第一件事就是"这台手机怎么连上我电脑上的 DSH"。之前这步藏在
 * 多级菜单里、还要求用户理解 A/B 模式，体验很差。这里只留两条路：
 * <ul>
 *   <li><b>原生模式</b>（推荐）：粘贴 DSH 启动时打印的带 token 的地址</li>
 *   <li><b>官方界面模式</b>：只填地址，打开官方 UI 自己登录</li>
 * </ul>
 */
public class ConnectActivity extends Activity {

    private InstanceStore instanceStore;
    private EditText tokenField;
    private EditText addressField;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        instanceStore = new InstanceStore(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiKit.BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this, 22), UiKit.statusBarHeight(this) + UiKit.dp(this, 40),
                UiKit.dp(this, 22), UiKit.dp(this, 24));

        // 品牌
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        View logo = new View(this);
        logo.setBackground(UiKit.rounded(this, 10, UiKit.ACCENT));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 34), UiKit.dp(this, 34));
        logoLp.setMargins(0, 0, UiKit.dp(this, 12), 0);
        brand.addView(logo, logoLp);
        TextView brandName = UiKit.text(this, "DSH", 26, UiKit.TEXT_PRIMARY);
        brandName.setTypeface(Typeface.DEFAULT_BOLD);
        brand.addView(brandName);
        root.addView(brand);

        TextView subtitle = UiKit.text(this, "连接你自己的 DeepSeek Harness", 13, UiKit.TEXT_SECONDARY);
        subtitle.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 24));
        root.addView(subtitle);

        // 原生模式
        root.addView(sectionTitle("原生模式（推荐）"));
        TextView hint = UiKit.text(this,
                "在跑 DSH 的电脑上启动一次，终端会打印一行：\n"
                        + "dsh web: http://127.0.0.1:3080/?token=xxxx\n\n"
                        + "把整行粘贴到下面。token 每次重启都会变，只存在于那台电脑的终端里。",
                12, UiKit.TEXT_SECONDARY);
        hint.setLineSpacing(UiKit.dp(this, 3), 1f);
        hint.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 10));
        root.addView(hint);

        tokenField = new EditText(this);
        tokenField.setHint("dsh web: http://…/?token=…");
        tokenField.setTextColor(UiKit.TEXT_PRIMARY);
        tokenField.setHintTextColor(UiKit.TEXT_TERTIARY);
        tokenField.setTextSize(13);
        tokenField.setBackground(UiKit.rounded(this, 12, UiKit.SURFACE_2, UiKit.BORDER));
        tokenField.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10), UiKit.dp(this, 12), UiKit.dp(this, 10));
        tokenField.setMinLines(2);
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(tokenField, matchWrap(0, UiKit.dp(this, 6)));

        TextView connectBtn = primaryButton("连接并进入");
        connectBtn.setOnClickListener(v -> connectWithToken());
        root.addView(connectBtn, matchWrap(UiKit.dp(this, 10), UiKit.dp(this, 26)));

        // 分隔
        TextView sep = UiKit.text(this, "或者", 11, UiKit.TEXT_TERTIARY);
        sep.setGravity(Gravity.CENTER);
        sep.setPadding(0, 0, 0, UiKit.dp(this, 16));
        root.addView(sep);

        // 官方界面模式
        root.addView(sectionTitle("只用官方界面"));
        TextView hint2 = UiKit.text(this,
                "只填 DSH 的地址（局域网如 http://192.168.1.5:3080，或用隧道暴露的域名）。"
                        + "打开后是官方 Web 界面，登录/配对在界面里完成。",
                12, UiKit.TEXT_SECONDARY);
        hint2.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 10));
        root.addView(hint2);

        addressField = new EditText(this);
        addressField.setHint("http://192.168.1.5:3080");
        addressField.setTextColor(UiKit.TEXT_PRIMARY);
        addressField.setHintTextColor(UiKit.TEXT_TERTIARY);
        addressField.setTextSize(13);
        addressField.setSingleLine(true);
        addressField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressField.setBackground(UiKit.rounded(this, 12, UiKit.SURFACE_2, UiKit.BORDER));
        addressField.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10), UiKit.dp(this, 12), UiKit.dp(this, 10));
        root.addView(addressField, matchWrap(0, UiKit.dp(this, 6)));

        TextView webBtn = secondaryButton("只打开官方界面");
        webBtn.setOnClickListener(v -> connectWithAddressOnly());
        root.addView(webBtn, matchWrap(UiKit.dp(this, 10), UiKit.dp(this, 10)));

        TextView note = UiKit.text(this,
                "非官方项目，与 DSH 官方无关。App 不收集数据，所有请求直连你填的地址。",
                11, UiKit.TEXT_TERTIARY);
        note.setPadding(0, UiKit.dp(this, 26), 0, 0);
        root.addView(note);

        scroll.addView(root);
        setContentView(scroll);
    }

    /* ---------------- 动作 ---------------- */

    private void connectWithToken() {
        String pasted = tokenField.getText().toString();
        // 配对链接优先：粘贴 …/pair-accept?pair=… 就直接走配对流程，一步到位
        String pairUrl = PairCookie.extractPairUrl(pasted);
        if (pairUrl != null) {
            Intent it = new Intent(this, PairingActivity.class);
            it.putExtra(PairingActivity.EXTRA_PAIR_URL, pairUrl);
            startActivity(it);
            finish();
            return;
        }
        String tokenUrl = TokenUrl.extract(pasted);
        if (tokenUrl == null) {
            toast("没认出这是配对链接还是启动地址：配对链接形如 …/pair-accept?pair=…，"
                    + "启动地址形如 …/?token=…");
            return;
        }
        final String address = baseOf(tokenUrl);
        final String token = tokenUrl;
        // 先验证一下 token 能不能换到 cookie，避免"填了但用不了"
        toast("正在验证…");
        Task.run(this, "dsh-connect", new Task.Work<Void>() {
            @Override
            public Void run() throws Exception {
                DshClient.Session s = DshClient.connect(token, 8000);
                // 顺手取一次会话列表，确认这条链路真的通
                DshClient.listSessions(s);
                return null;
            }
        }, new Task.Done<Void>() {
            @Override
            public void onResult(Void value, String error) {
                if (error != null) {
                    showError("连接失败", error);
                    return;
                }
                Instance inst = new Instance(hostLabel(address), address, token);
                instanceStore.add(inst);
                instanceStore.setLastOpenedUrl(address);
                instanceStore.setHostOwner(WebCookies.hostKey(address), address);
                goHome();
            }
        });
    }

    private void connectWithAddressOnly() {
        String raw = addressField.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("请填写地址");
            return;
        }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "http://" + raw;
        }
        Instance inst = new Instance(hostLabel(raw), raw, "");
        instanceStore.add(inst);
        instanceStore.setLastOpenedUrl(raw);
        instanceStore.setHostOwner(WebCookies.hostKey(raw), raw);
        goHome();
    }

    private void goHome() {
        Intent it = new Intent(this, ChatActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(it);
        finish();
    }

    private void showError(String title, String body) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body + "\n\n常见原因：\n• token 已过期（DSH 重启后 token 会变，重新复制一次）\n"
                        + "• 手机和电脑不在同一网络\n• DSH 只绑了 127.0.0.1（要用 --host 0.0.0.0 启动）")
                .setPositiveButton("知道了", null)
                .show();
    }

    /* ---------------- 小工具 ---------------- */

    private String baseOf(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            String port = u.getPort() > 0 ? ":" + u.getPort() : "";
            return u.getScheme() + "://" + u.getHost() + port;
        } catch (Exception e) {
            return url;
        }
    }

    private String hostLabel(String address) {
        try {
            String host = java.net.URI.create(address).getHost();
            return host == null || host.isEmpty() ? "DSH" : host;
        } catch (Exception e) {
            return "DSH";
        }
    }

    private TextView sectionTitle(String s) {
        TextView v = UiKit.text(this, s, 12, UiKit.TEXT_TERTIARY);
        v.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 4));
        return v;
    }

    private TextView primaryButton(String label) {
        TextView v = UiKit.text(this, label, 15, 0xFFFFFFFF);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setBackground(UiKit.rounded(this, 12, UiKit.ACCENT));
        v.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 13), UiKit.dp(this, 12), UiKit.dp(this, 13));
        return v;
    }

    private TextView secondaryButton(String label) {
        TextView v = UiKit.text(this, label, 14, UiKit.TEXT_PRIMARY);
        v.setGravity(Gravity.CENTER);
        v.setBackground(UiKit.rounded(this, 12, UiKit.SURFACE_2, UiKit.BORDER));
        v.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12));
        return v;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin, int bottomMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, topMargin, 0, bottomMargin);
        return lp;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

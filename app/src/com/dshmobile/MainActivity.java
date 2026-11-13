package com.dshmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.dshmobile.protocol.ApiCookieExchanger;
import com.dshmobile.protocol.Capabilities;
import com.dshmobile.protocol.Credential;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实例列表：多台 DSH 的管理入口。
 *
 * <p>刻意不用 androidx / Compose：纯 framework Views，保证零外部依赖即可编译。
 */
public class MainActivity extends Activity {

    private InstanceStore store;
    private SimpleAdapter adapter;
    private final List<Map<String, String>> rows = new ArrayList<>();
    private List<Instance> instances = new ArrayList<>();
    private ListView listView;
    private TextView emptyView;
    private Button continueButton;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new InstanceStore(this);
        setContentView(buildUi());
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(20), dp(24), dp(12), dp(4));

        TextView header = new TextView(this);
        header.setText("DSH 实例");
        header.setTextSize(22);
        headerRow.addView(header, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView help = new TextView(this);
        help.setText("帮助");
        help.setTextSize(14);
        help.setAlpha(0.7f);
        help.setPadding(dp(12), dp(4), dp(4), dp(4));
        help.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new android.content.Intent(MainActivity.this, HelpActivity.class));
            }
        });
        headerRow.addView(help);

        root.addView(headerRow);

        TextView hint = new TextView(this);
        hint.setText("非官方客户端 · 连接你自己运行的 DSH");
        hint.setTextSize(12);
        hint.setPadding(dp(20), 0, dp(20), dp(16));
        hint.setAlpha(0.6f);
        root.addView(hint);

        // 「继续上次」：记住上次打开的实例，一键回到它
        continueButton = new Button(this);
        continueButton.setAllCaps(false);
        continueButton.setVisibility(View.GONE);
        continueButton.setOnClickListener(v -> {
            int index = store.indexOfUrl(store.getLastOpenedUrl());
            if (index >= 0) {
                openInstance(index);
            } else {
                toast("上次那个实例已经不在了");
                reload();
            }
        });
        LinearLayout.LayoutParams continueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        continueLp.setMargins(dp(16), 0, dp(16), dp(6));
        root.addView(continueButton, continueLp);

        ListView list = new ListView(this);
        list.setId(android.R.id.list);
        listView = list;
        adapter = new SimpleAdapter(
                this,
                rows,
                android.R.layout.simple_list_item_2,
                new String[]{"line1", "line2"},
                new int[]{android.R.id.text1, android.R.id.text2});
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openInstance(position);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showRowMenu(position);
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 空状态：一个实例都没有时给点指引，而不是一片空白
        emptyView = new TextView(this);
        emptyView.setText("还没有实例。\n\n点下面的「＋ 添加实例」，填上你运行中的 DSH 地址。\n\n"
                + "想要原生会话列表，再加一个启动地址（形如 http://127.0.0.1:3080/?token=…）。\n"
                + "那种地址由 dsh 启动时在终端打印，每次重启都会变。");
        emptyView.setTextSize(14);
        emptyView.setAlpha(0.7f);
        emptyView.setPadding(dp(24), dp(8), dp(24), dp(8));
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button add = new Button(this);
        add.setText("＋ 添加实例");
        add.setAllCaps(false);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditor(-1);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(8), dp(16), dp(16));
        root.addView(add, lp);

        return root;
    }

    private void reload() {
        instances = store.load();
        rows.clear();
        for (Instance i : instances) {
            Map<String, String> row = new HashMap<>();
            row.put("line1", i.name);
            Capabilities caps = Capabilities.forCredential(credentialOf(i));
            row.put("line2", i.url + "  ·  " + caps.label());
            rows.add(row);
        }
        adapter.notifyDataSetChanged();

        boolean empty = instances.isEmpty();
        if (listView != null) {
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
        if (emptyView != null) {
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }

        // 「继续上次」按钮：仅当记住的实例还在时显示
        if (continueButton != null) {
            int last = store.indexOfUrl(store.getLastOpenedUrl());
            if (last >= 0 && !empty) {
                continueButton.setText("继续上次：" + instances.get(last).name);
                continueButton.setVisibility(View.VISIBLE);
            } else {
                continueButton.setVisibility(View.GONE);
            }
        }
    }

    /** 把实例上的凭证字段转成协议层的 Credential。 */
    private Credential credentialOf(Instance inst) {
        return inst.hasApiCredential()
                ? Credential.fromTokenUrl(inst.tokenUrl, inst.url)
                : Credential.none(inst.url);
    }

    private void openInstance(int position) {
        if (position < 0 || position >= instances.size()) {
            return;
        }
        store.setLastOpenedUrl(instances.get(position).url);
        Intent it = new Intent(this, WebActivity.class);
        it.putExtra(WebActivity.EXTRA_INDEX, position);
        startActivity(it);
    }

    private void showRowMenu(final int position) {
        final Instance inst = instances.get(position);
        final boolean hasApi = inst.hasApiCredential();
        // 没有 API 凭证时也留一个入口说明"原生能力怎么开"，否则用户只会看到入口消失了。
        final String[] items = hasApi
                ? new String[]{"打开", "进入 DSH 界面", "测试连接", "验证 API 凭证", "编辑", "删除"}
                : new String[]{"打开", "测试连接", "原生能力怎么开？", "编辑", "删除"};

        new AlertDialog.Builder(this)
                .setTitle(inst.name)
                .setItems(items, (dialog, which) -> {
                    String action = items[which];
                    if ("打开".equals(action)) {
                        openInstance(position);
                    } else if ("进入 DSH 界面".equals(action)) {
                        openSessions(position);
                    } else if ("测试连接".equals(action)) {
                        testConnection(inst);
                    } else if ("验证 API 凭证".equals(action)) {
                        verifyApiCredential(inst);
                    } else if ("原生能力怎么开？".equals(action)) {
                        dialog("原生能力怎么开",
                                Capabilities.forCredential(credentialOf(inst)).explainMissing());
                    } else if ("编辑".equals(action)) {
                        showEditor(position);
                    } else {
                        store.remove(position);
                        reload();
                    }
                })
                .show();
    }

    /** 原生会话列表（B 模式）。能力由凭证决定，没有 token 时不出现在菜单里。 */
    private void openSessions(int position) {
        Intent it = new Intent(this, ChatActivity.class);
        it.putExtra(WebActivity.EXTRA_INDEX, position);
        startActivity(it);
    }

    /**
     * 验证 B 模式凭证：真的去跑一次 /connect?token= 换取。
     *
     * <p>这一步只验证"能不能换到 API Cookie"，不代表原生功能已经可用 ——
     * 后续还需要用这个 cookie 连 mux 并确认帧格式。所以文案上要说清楚。
     */
    private void verifyApiCredential(final Instance inst) {
        toast("正在换取 API Cookie…");
        final Credential cred = credentialOf(inst);
        Task.run(this, "dsh-api-verify", new Task.Work<ApiCookieExchanger.Result>() {
            @Override
            public ApiCookieExchanger.Result run() {
                return ApiCookieExchanger.exchange(cred, 8000);
            }
        }, new Task.Done<ApiCookieExchanger.Result>() {
            @Override
            public void onResult(ApiCookieExchanger.Result r, String error) {
                if (r == null) {
                    dialog("凭证校验失败", error == null ? "未知错误" : error);
                    return;
                }
                String title = r.ok ? "凭证有效" : "凭证不可用";
                String body = "状态码：" + (r.status == 0 ? "(无响应)" : String.valueOf(r.status))
                        + "\n\n" + r.advice
                        + (r.ok ? "\n\n下一步：长按这个实例 →「原生会话列表」，"
                                + "就能在 App 里直接看会话了。" : "");
                dialog(title, body);
            }
        });
    }

    private void dialog(String title, String body) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 连接预检：告诉用户是网络问题、隧道问题，还是只需要鉴权。 */
    private void testConnection(final Instance inst) {
        toast("正在测试 " + inst.url);
        Preflight.check(inst.url, new Preflight.Callback() {
            @Override
            public void onResult(Preflight.Result result) {
                String title = result.usable ? "可以连接" : "连不上";
                String head = "状态码：" + (result.status == 0 ? "(无响应)" : String.valueOf(result.status))
                        + "\n判定：" + (result.usable ? "服务在，且是 DSH 的正常响应" : "需要排查");
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(title)
                        .setMessage(head + "\n\n" + result.advice)
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
    }

    /** index < 0 表示新增。 */
    private void showEditor(final int index) {
        final Instance editing = index >= 0 && index < instances.size() ? instances.get(index) : null;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText name = new EditText(this);
        name.setHint("名字，例如 家里 NAS");
        name.setSingleLine(true);
        if (editing != null) {
            name.setText(editing.name);
        }
        box.addView(name);

        final EditText url = new EditText(this);
        url.setHint("地址，例如 http://192.168.1.12:3080");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        if (editing != null) {
            url.setText(editing.url);
        }
        box.addView(url);

        final EditText token = new EditText(this);
        token.setHint("（可选）dsh web 输出的带 token 的完整地址 —— 填了才能用原生能力");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        if (editing != null) {
            token.setText(editing.tokenUrl);
        }
        box.addView(token);

        TextView note = new TextView(this);
        note.setTextSize(11);
        note.setAlpha(0.6f);
        note.setPadding(0, dp(8), 0, 0);
        note.setText("提示：DSH 默认只监听 127.0.0.1，手机连不上。"
                + "请用 dsh web --host 0.0.0.0 启动，或通过你自己的隧道暴露成域名。");
        box.addView(note);

        new AlertDialog.Builder(this)
                .setTitle(editing == null ? "添加实例" : "编辑实例")
                .setView(box)
                .setPositiveButton("保存", (dialog, which) -> {
                    String n = name.getText().toString().trim();
                    String u = url.getText().toString().trim();
                    String t = token.getText().toString().trim();
                    if (u.isEmpty()) {
                        toast("地址不能为空");
                        return;
                    }
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        u = "http://" + u;
                    }
                    if (n.isEmpty()) {
                        n = u;
                    }
                    if (t.isEmpty()) {
                        t = "";
                    }
                    Instance inst = new Instance(n, u, t);
                    if (editing == null) {
                        store.add(inst);
                    } else {
                        store.update(index, inst);
                    }
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}

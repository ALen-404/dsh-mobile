package com.dshmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.dshmobile.protocol.DshClient;
import com.dshmobile.protocol.SessionSummary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原生会话列表（B 模式 / API 模式）。
 *
 * <p>数据来自 {@code session/list}（HTTP unary），解析走协议层的
 * {@link SessionSummary#parseResponse(String)} —— 那部分有 31 项自检覆盖。
 *
 * <p>已知限制：DSH 网页端的"切换会话"是应用内状态，没有 URL 路由（实测
 * {@code ?session=} 零命中）。所以这里的点击行为是**打开该实例的官方 UI**，
 * 由用户在官方界面里选会话；长按可以复制会话 ID 便于排查。
 * 真正的原生切换需要找到网页端的路由/状态入口，列在路线图里。
 */
public class SessionsActivity extends Activity {

    private InstanceStore store;
    private Instance instance;
    private int index = -1;

    private SimpleAdapter adapter;
    private final List<Map<String, String>> rows = new ArrayList<>();
    private List<SessionSummary> sessions = new ArrayList<>();

    private TextView statusLine;
    private Button refresh;
    /** onResume 首次跳过（onCreate 已经加载过）。 */
    private boolean firstResume;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new InstanceStore(this);
        index = getIntent().getIntExtra(WebActivity.EXTRA_INDEX, -1);
        List<Instance> all = store.load();
        if (index < 0 || index >= all.size()) {
            finish();
            return;
        }
        instance = all.get(index);
        setContentView(buildUi());
        load();
    }

    /**
     * 回到列表时自动刷新一次。
     *
     * <p>从详情页返回（或从后台切回来）时，列表里的"最后活动时间/运行中"很可能已经变了；
     * 没有下拉刷新组件（零 androidx），所以用 onResume 兜底。
     * onCreate 已经加载过，这里跳过第一次，避免重复请求。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (!firstResume) {
            firstResume = true;
            return;
        }
        if (instance != null) {
            load();
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("会话 · " + instance.name);
        title.setTextSize(20);
        title.setPadding(dp(20), dp(20), dp(20), dp(2));
        root.addView(title);

        statusLine = new TextView(this);
        statusLine.setTextSize(12);
        statusLine.setAlpha(0.65f);
        statusLine.setPadding(dp(20), 0, dp(20), dp(10));
        statusLine.setText("正在读取 session/list …");
        root.addView(statusLine);

        ListView list = new ListView(this);
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
                openDetail(position);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showSessionMenu(position);
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        Button create = new Button(this);
        create.setText("＋ 新建");
        create.setAllCaps(false);
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createSession();
            }
        });
        bar.addView(create, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button back = new Button(this);
        back.setText("返回");
        back.setAllCaps(false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        bar.addView(refresh, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.setMargins(dp(12), dp(4), dp(12), dp(12));
        root.addView(bar, barLp);

        return root;
    }

    private void load() {
        statusLine.setText("正在读取 session/list …");
        refresh.setEnabled(false);
        Task.run(this, "dsh-session-list", new Task.Work<List<SessionSummary>>() {
            @Override
            public List<SessionSummary> run() throws Exception {
                DshClient.Session session = DshClient.connect(instance.tokenUrl, 8000);
                return SessionSummary.parseResponse(DshClient.listSessions(session));
            }
        }, new Task.Done<List<SessionSummary>>() {
            @Override
            public void onResult(List<SessionSummary> value, String error) {
                refresh.setEnabled(true);
                applyResult(value == null ? new ArrayList<SessionSummary>() : value, error);
            }
        });
    }

    private void applyResult(List<SessionSummary> result, String error) {
        sessions = result;
        rows.clear();
        if (error != null) {
            statusLine.setText("读取失败：" + error);
        } else {
            statusLine.setText(result.isEmpty()
                    ? "这台 DSH 上还没有会话"
                    : "共 " + result.size() + " 个会话（按最近活动排序）");
        }
        for (SessionSummary s : result) {
            Map<String, String> row = new HashMap<>();
            row.put("line1", s.displayTitle());
            String badge = s.badge();
            String meta = relativeTime(s.updatedAt);
            row.put("line2", (badge.isEmpty() ? "" : badge + " · ") + s.shortId() + " · " + meta);
            rows.add(row);
        }
        adapter.notifyDataSetChanged();
    }

    private void showSessionMenu(final int position) {
        if (position < 0 || position >= sessions.size()) {
            return;
        }
        final SessionSummary s = sessions.get(position);
        new AlertDialog.Builder(this)
                .setTitle(s.displayTitle())
                .setMessage("会话 ID：" + s.sessionId + "\n工作目录：" + (s.cwd.isEmpty() ? "(未知)" : s.cwd))
                .setPositiveButton("复制 ID", (d, w) -> copy(s.sessionId))
                .setNeutralButton("打开官方界面", (d, w) -> openOfficialUi())
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 打开原生会话详情（渲染 projections + 对话）。 */
    private void openDetail(int position) {
        if (position < 0 || position >= sessions.size()) {
            return;
        }
        SessionSummary s = sessions.get(position);
        openDetailFor(s.sessionId, s.displayTitle());
    }

    private void openDetailFor(String sessionId, String title) {
        Intent it = new Intent(this, SessionDetailActivity.class);
        it.putExtra(WebActivity.EXTRA_INDEX, index);
        it.putExtra(SessionDetailActivity.EXTRA_SESSION_ID, sessionId);
        it.putExtra(SessionDetailActivity.EXTRA_TITLE, title);
        startActivity(it);
    }

    /**
     * 新建会话：{@code session/create} → 拿到 sessionId → 直接进原生详情页。
     *
     * <p>实测 `{"request":{}}` 就够（工作目录由 Host 决定），
     * 返回 `{"sessionId":"session-…","agentPreset":"standard"}`。
     */
    private void createSession() {
        statusLine.setText("正在创建会话…");
        refresh.setEnabled(false);
        Task.run(this, "dsh-create-session", new Task.Work<String>() {
            @Override
            public String run() throws Exception {
                DshClient.Session session = DshClient.connect(instance.tokenUrl, 8000);
                return DshClient.parseCreatedSessionId(DshClient.createSession(session));
            }
        }, new Task.Done<String>() {
            @Override
            public void onResult(String id, String error) {
                refresh.setEnabled(true);
                if (error != null) {
                    statusLine.setText("创建失败：" + error);
                    return;
                }
                if (id == null || id.isEmpty()) {
                    statusLine.setText("创建失败：服务端没有返回 sessionId");
                    return;
                }
                statusLine.setText("已创建 " + id);
                load();               // 让新会话出现在列表里
                openDetailFor(id, "新会话");
            }
        });
    }

    private void openOfficialUi() {
        Intent it = new Intent(this, WebActivity.class);
        it.putExtra(WebActivity.EXTRA_INDEX, index);
        startActivity(it);
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("DSH", text));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }
    }

    /** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / 日期。 */
    private String relativeTime(long millis) {
        if (millis <= 0) {
            return "时间未知";
        }
        long diff = System.currentTimeMillis() - millis;
        if (diff < 60_000L) {
            return "刚刚";
        }
        if (diff < 3_600_000L) {
            return (diff / 60_000L) + " 分钟前";
        }
        if (diff < 86_400_000L) {
            return (diff / 3_600_000L) + " 小时前";
        }
        if (diff < 7 * 86_400_000L) {
            return (diff / 86_400_000L) + " 天前";
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
        return fmt.format(new java.util.Date(millis));
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}

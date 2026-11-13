package com.dshmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dshmobile.protocol.Base64Lite;
import com.dshmobile.protocol.DshClient;
import com.dshmobile.protocol.MarkdownLite;
import com.dshmobile.protocol.ObserveEventStream;
import com.dshmobile.protocol.SessionEvent;
import com.dshmobile.protocol.SessionProjection;
import com.dshmobile.protocol.SessionSummary;
import com.dshmobile.protocol.SessionTranscript;
import com.dshmobile.protocol.TokenUrl;
import com.dshmobile.protocol.TranscriptBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * DSH 手机端主界面 —— 复刻桌面端的结构：
 *
 * <pre>
 * ┌───────────────┬─────────────────────────┐
 * │ 侧栏（抽屉）   │ 顶栏：会话标题 …        │
 * │ 品牌 / 新会话  ├─────────────────────────┤
 * │ 导航项         │ 对话（用户气泡在右、    │
 * │ 工作区 · 会话   │ 助手内容在左、命令行）  │
 * │ 用量           ├─────────────────────────┤
 * │ 实例 / 设置     │ 输入区 + 权限/模型 chip │
 * └───────────────┴─────────────────────────┘
 * </pre>
 *
 * <p>数据全部走协议层：`session/list`、`session/page`、`session/projections`、
 * `session/observeEvent`、`session/prompt`（含附件）。深色配色见 {@link UiKit}。
 *
 * <p>手机上侧栏做成可滑出的抽屉（无 androidx DrawerLayout，自己用 translationX + scrim 实现）。
 */
public class ChatActivity extends Activity {

    private InstanceStore store;
    private Instance instance;
    private String sessionId;
    private String sessionTitle = "";

    /* 视图 */
    private FrameLayout root;
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input;
    private TextView headerTitle;
    private TextView headerStatus;
    private LinearLayout drawer;
    private View scrim;
    private LinearLayout sessionListBox;
    private TextView modelChip;
    private TextView permChip;
    private TextView attachStatus;

    /* 数据 */
    private TranscriptBuilder builder = new TranscriptBuilder();
    private SessionProjection projection;
    private final List<SessionSummary> sessions = new ArrayList<>();
    private ObserveEventStream stream;
    private volatile boolean live;
    private volatile boolean streamClosedByUser = true;
    private long pausedAt;

    /* 渲染状态 */
    private int renderedEntries;
    private boolean pendingScroll = true;
    private String pendingUserText;
    private String pendingError;

    /* 附件 */
    private final List<String> pendingParts = new ArrayList<>();
    private final List<String> pendingPartNames = new ArrayList<>();
    private static final int REQ_ATTACH = 6101;
    private static final long MAX_ATTACH_BYTES = 8L * 1024 * 1024;
    private static final long MAX_INLINE_IMAGE_BYTES = 3L * 1024 * 1024;

    /* 重连 */
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable reconnectRunnable;
    private int reconnectAttempt;

    /* 抽屉动画 */
    private int drawerWidth;
    private boolean drawerOpen;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new InstanceStore(this);

        List<Instance> all = store.load();
        if (all.isEmpty()) {
            startActivity(new Intent(this, ConnectActivity.class));
            finish();
            return;
        }
        int last = store.indexOfUrl(store.getLastOpenedUrl());
        instance = all.get(last >= 0 ? last : 0);

        setContentView(buildUi());
        loadSessions();
        if (instance.hasApiCredential()) {
            String lastSession = store.getLastSessionId();
            if (!lastSession.isEmpty()) {
                openSession(lastSession, "");
            }
        } else {
            showConnectHint();
        }
    }

    /* ================================================================== */
    /* UI 构建                                                             */
    /* ================================================================== */

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(UiKit.BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(buildHeader());
        content.addView(buildTranscriptArea(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(buildComposer());
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scrim = new View(this);
        scrim.setBackgroundColor(UiKit.SCRIM);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeDrawer());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawerWidth = Math.min(UiKit.dp(this, 288),
                getResources().getDisplayMetrics().widthPixels - UiKit.dp(this, 56));
        drawer = buildDrawer();
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(drawer, dlp);
        drawer.setTranslationX(-drawerWidth);
        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(UiKit.SURFACE_1);

        View statusPad = new View(this);
        header.addView(statusPad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.statusBarHeight(this)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 6), UiKit.dp(this, 6), UiKit.dp(this, 8));

        TextView menu = UiKit.text(this, "☰", 18, UiKit.TEXT_PRIMARY);
        menu.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 6), UiKit.dp(this, 10), UiKit.dp(this, 6));
        menu.setOnClickListener(v -> toggleDrawer());
        row.addView(menu);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        headerTitle = UiKit.text(this, "DSH", 15, UiKit.TEXT_PRIMARY);
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(TextUtils.TruncateAt.END);
        headerStatus = UiKit.text(this, "", 11, UiKit.TEXT_TERTIARY);
        headerStatus.setSingleLine(true);
        titleBox.addView(headerTitle);
        titleBox.addView(headerStatus);
        row.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView more = UiKit.text(this, "⋯", 18, UiKit.TEXT_PRIMARY);
        more.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 6), UiKit.dp(this, 10), UiKit.dp(this, 6));
        more.setOnClickListener(this::showHeaderMenu);
        row.addView(more);

        header.addView(row);
        header.addView(UiKit.divider(this));
        return header;
    }

    private View buildTranscriptArea() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10),
                UiKit.dp(this, 14), UiKit.dp(this, 10));
        scroll.addView(messages);
        return scroll;
    }

    private View buildComposer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(UiKit.SURFACE_1);
        box.addView(UiKit.divider(this));

        attachStatus = UiKit.text(this, "", 11, UiKit.TEXT_TERTIARY);
        attachStatus.setVisibility(View.GONE);
        attachStatus.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 6), UiKit.dp(this, 14), 0);
        box.addView(attachStatus);

        // 输入框：圆角、多行，像桌面端那个 composer
        input = new EditText(this);
        input.setHint("发消息或创建任务…");
        input.setHintTextColor(UiKit.TEXT_TERTIARY);
        input.setTextColor(UiKit.TEXT_PRIMARY);
        input.setTextSize(15);
        input.setMaxLines(5);
        input.setMinLines(1);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setBackground(UiKit.rounded(this, 14, UiKit.SURFACE_2, UiKit.BORDER));
        input.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 9), UiKit.dp(this, 12), UiKit.dp(this, 9));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.setMargins(UiKit.dp(this, 12), UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 6));
        box.addView(input, inputLp);

        // 底部一排：附件 + 权限 chip + 模型 chip + 发送
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(UiKit.dp(this, 12), 0, UiKit.dp(this, 12), UiKit.dp(this, 8));

        TextView attach = UiKit.text(this, "＋", 18, UiKit.TEXT_SECONDARY);
        attach.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 4), UiKit.dp(this, 12), UiKit.dp(this, 4));
        attach.setOnClickListener(v -> pickAttachment());
        bar.addView(attach);

        permChip = UiKit.chip(this, "权限", false);
        permChip.setVisibility(View.GONE);
        bar.addView(permChip);

        modelChip = UiKit.chip(this, "模型", false);
        modelChip.setVisibility(View.GONE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.setMargins(UiKit.dp(this, 6), 0, 0, 0);
        bar.addView(modelChip, mlp);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView send = UiKit.text(this, "↑", 18, 0xFFFFFFFF);
        send.setGravity(Gravity.CENTER);
        send.setBackground(UiKit.rounded(this, 18, UiKit.ACCENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 36), UiKit.dp(this, 36));
        send.setOnClickListener(v -> sendMessage());
        bar.addView(send, slp);

        box.addView(bar);
        return box;
    }

    private LinearLayout buildDrawer() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.setBackgroundColor(UiKit.SURFACE_1);

        View pad = new View(this);
        d.addView(pad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.statusBarHeight(this)));

        // 品牌
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 8), UiKit.dp(this, 14), UiKit.dp(this, 8));
        View logo = new View(this);
        logo.setBackground(UiKit.rounded(this, 6, UiKit.ACCENT));
        brand.addView(logo, new LinearLayout.LayoutParams(UiKit.dp(this, 20), UiKit.dp(this, 20)));
        TextView name = UiKit.text(this, "  DSH", 15, UiKit.TEXT_PRIMARY);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        brand.addView(name);
        d.addView(brand);

        // 新会话
        TextView newSession = UiKit.text(this, "＋  新会话", 14, UiKit.TEXT_PRIMARY);
        newSession.setGravity(Gravity.CENTER_VERTICAL);
        newSession.setBackground(UiKit.rounded(this, 12, UiKit.SURFACE_2, UiKit.BORDER));
        newSession.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10),
                UiKit.dp(this, 12), UiKit.dp(this, 10));
        newSession.setOnClickListener(v -> createSession());
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMargins(UiKit.dp(this, 12), UiKit.dp(this, 4), UiKit.dp(this, 12), UiKit.dp(this, 10));
        d.addView(newSession, nlp);

        // 导航项（打开官方界面里的对应页；手机端不重画这些插件页）
        String[][] nav = {
                {"🧩", "插件"}, {"⏱", "自动化任务"}, {"📋", "任务看板"},
                {"🧠", "技能中心"}, {"💾", "记忆系统"},
        };
        for (String[] item : nav) {
            LinearLayout row = UiKit.row(this, item[0], item[1], 13, UiKit.TEXT_SECONDARY);
            row.setOnClickListener(v -> openOfficial(item[1]));
            d.addView(row);
        }
        d.addView(UiKit.divider(this));

        // 会话区标题
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.HORIZONTAL);
        sec.setGravity(Gravity.CENTER_VERTICAL);
        sec.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10), UiKit.dp(this, 10), UiKit.dp(this, 6));
        TextView secTitle = UiKit.text(this, "会话", 11, UiKit.TEXT_TERTIARY);
        sec.addView(secTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView refreshBtn = UiKit.text(this, "⟳", 13, UiKit.TEXT_TERTIARY);
        refreshBtn.setPadding(UiKit.dp(this, 8), 0, UiKit.dp(this, 8), 0);
        refreshBtn.setOnClickListener(v -> loadSessions());
        sec.addView(refreshBtn);
        d.addView(sec);

        ScrollView listScroll = new ScrollView(this);
        sessionListBox = new LinearLayout(this);
        sessionListBox.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(sessionListBox);
        d.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部：实例 + 设置
        d.addView(UiKit.divider(this));
        LinearLayout footer = UiKit.row(this, "⚙", instance.name + "  ·  设置", 13, UiKit.TEXT_SECONDARY);
        footer.setOnClickListener(v -> {
            closeDrawer();
            startActivity(new Intent(this, MainActivity.class));
        });
        d.addView(footer);
        return d;
    }

    /* ================================================================== */
    /* 抽屉                                                               */
    /* ================================================================== */

    private void toggleDrawer() {
        if (drawerOpen) {
            closeDrawer();
        } else {
            openDrawer();
        }
    }

    private void openDrawer() {
        drawerOpen = true;
        drawer.animate().translationX(0).setDuration(180).start();
        scrim.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(180).start();
        loadSessions();
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.animate().translationX(-drawerWidth).setDuration(160).start();
        scrim.animate().alpha(0f).setDuration(160).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
    }

    /* ================================================================== */
    /* 数据加载                                                            */
    /* ================================================================== */

    private void loadSessions() {
        if (!instance.hasApiCredential()) {
            renderSessionList();
            return;
        }
        Task.run(this, "dsh-sessions", new Task.Work<List<SessionSummary>>() {
            @Override
            public List<SessionSummary> run() throws Exception {
                DshClient.Session s = DshClient.connect(instance.tokenUrl, 8000);
                return SessionSummary.parseResponse(DshClient.listSessions(s));
            }
        }, new Task.Done<List<SessionSummary>>() {
            @Override
            public void onResult(List<SessionSummary> value, String error) {
                sessions.clear();
                if (value != null) {
                    sessions.addAll(value);
                }
                if (error != null) {
                    Toast.makeText(ChatActivity.this, "会话列表读取失败：" + error, Toast.LENGTH_SHORT).show();
                }
                renderSessionList();
            }
        });
    }

    private void renderSessionList() {
        sessionListBox.removeAllViews();
        if (sessions.isEmpty()) {
            TextView empty = UiKit.text(this, instance.hasApiCredential()
                    ? "还没有会话，点上面的「新会话」开始"
                    : "还没连接 DSH（在「设置」里填启动地址）", 12, UiKit.TEXT_TERTIARY);
            empty.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10), UiKit.dp(this, 14), UiKit.dp(this, 10));
            sessionListBox.addView(empty);
            return;
        }
        for (SessionSummary s : sessions) {
            sessionListBox.addView(buildSessionRow(s));
        }
    }

    private View buildSessionRow(final SessionSummary s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 8), UiKit.dp(this, 10), UiKit.dp(this, 8));
        boolean active = s.sessionId.equals(sessionId);
        row.setBackgroundColor(active ? UiKit.SURFACE_2 : Color.TRANSPARENT);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        if (s.running) {
            View dot = new View(this);
            dot.setBackground(UiKit.rounded(this, 3, UiKit.SUCCESS));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    UiKit.dp(this, 6), UiKit.dp(this, 6));
            dlp.setMargins(0, 0, UiKit.dp(this, 6), 0);
            top.addView(dot, dlp);
        }
        TextView title = UiKit.text(this, s.displayTitle(), 13,
                active ? UiKit.TEXT_PRIMARY : 0xFFD5D9E0);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView time = UiKit.text(this, relativeTime(s.updatedAt), 10, UiKit.TEXT_TERTIARY);
        top.addView(time);
        row.addView(top);

        row.setOnClickListener(v -> {
            openSession(s.sessionId, s.displayTitle());
            closeDrawer();
        });
        return row;
    }

    private void openSession(final String id, final String title) {
        sessionId = id;
        sessionTitle = title == null ? "" : title;
        store.setLastSessionId(id);
        // 切会话必须重置折叠器，否则新会话会带着旧会话的消息
        builder = new TranscriptBuilder();
        projection = null;
        renderedEntries = 0;
        messages.removeAllViews();
        headerTitle.setText(sessionTitle.isEmpty() ? "会话" : sessionTitle);
        headerStatus.setText("加载中…");
        closeStream();

        Task.run(this, "dsh-session", new Task.Work<Loaded>() {
            @Override
            public Loaded run() throws Exception {
                Loaded l = new Loaded();
                DshClient.Session s = DshClient.connect(instance.tokenUrl, 8000);
                l.baseUrl = s.baseUrl;
                l.cookie = s.cookie;
                l.projection = SessionProjection.parse(DshClient.projections(s, id), id);
                l.history = SessionEvent.parsePage(DshClient.page(s, id));
                return l;
            }
        }, new Task.Done<Loaded>() {
            @Override
            public void onResult(Loaded l, String error) {
                if (error != null) {
                    headerStatus.setText("读取失败：" + error);
                    return;
                }
                projection = l.projection;
                if (l.history != null) {
                    builder.apply(l.history);
                }
                if (sessionTitle.isEmpty() && l.projection != null) {
                    sessionTitle = l.projection.displayTitle();
                }
                headerTitle.setText(sessionTitle);
                renderAll();
                renderSessionList();
                startStream(l.baseUrl, l.cookie);
            }
        });
    }

    private static final class Loaded {
        SessionProjection projection;
        List<SessionEvent> history;
        String baseUrl;
        String cookie;
    }

    private void createSession() {
        closeDrawer();
        headerStatus.setText("正在创建会话…");
        Task.run(this, "dsh-create", new Task.Work<String>() {
            @Override
            public String run() throws Exception {
                DshClient.Session s = DshClient.connect(instance.tokenUrl, 8000);
                return DshClient.parseCreatedSessionId(DshClient.createSession(s));
            }
        }, new Task.Done<String>() {
            @Override
            public void onResult(String id, String error) {
                if (error != null || id == null || id.isEmpty()) {
                    headerStatus.setText("创建失败：" + (error == null ? "没有返回 sessionId" : error));
                    return;
                }
                sessionTitle = "";
                openSession(id, "");
                loadSessions();
            }
        });
    }

    /* ================================================================== */
    /* 渲染对话                                                            */
    /* ================================================================== */

    private void renderAll() {
        SessionTranscript t = builder.build();
        messages.removeAllViews();

        if (t.isEmpty() && projection == null) {
            TextView hint = UiKit.text(this, "还没有内容", 13, UiKit.TEXT_TERTIARY);
            hint.setPadding(0, UiKit.dp(this, 24), 0, 0);
            messages.addView(hint);
        }
        for (SessionTranscript.Entry e : t.entries()) {
            addEntry(e);
        }
        renderedEntries = t.entries().size();
        updateChips();
        headerStatus.setText(projection == null ? "" : projection.statsLine());
        if (pendingScroll) {
            pendingScroll = false;
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void appendEntries() {
        SessionTranscript t = builder.build();
        List<SessionTranscript.Entry> entries = t.entries();
        for (int i = renderedEntries; i < entries.size(); i++) {
            addEntry(entries.get(i));
        }
        renderedEntries = entries.size();
        if (pendingScroll) {
            pendingScroll = false;
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void updateChips() {
        if (projection == null) {
            permChip.setVisibility(View.GONE);
            modelChip.setVisibility(View.GONE);
            return;
        }
        if (!projection.permission.isEmpty()) {
            permChip.setText(prettyPermission(projection.permission) + " ▾");
            permChip.setVisibility(View.VISIBLE);
        }
        modelChip.setText(shortModel(projection) + " ▾");
        modelChip.setVisibility(View.VISIBLE);
    }

    private String shortModel(SessionProjection p) {
        String m = p.modelName;
        if (m == null || m.isEmpty()) {
            return "模型";
        }
        return m.length() > 22 ? m.substring(0, 22) + "…" : m;
    }

    private String prettyPermission(String value) {
        if ("danger-full-access".equals(value)) {
            return "完全权限";
        }
        if ("read-only".equals(value)) {
            return "只读";
        }
        if ("workspace-write".equals(value)) {
            return "工作区可写";
        }
        return value;
    }

    private void addEntry(SessionTranscript.Entry e) {
        switch (e.role) {
            case SessionTranscript.ROLE_USER:
                messages.addView(buildUserBubble(e.text));
                break;
            case SessionTranscript.ROLE_TOOL:
                messages.addView(buildToolRow(e));
                break;
            case SessionTranscript.ROLE_ERROR:
                messages.addView(buildNotice(e.text, UiKit.DANGER, UiKit.DANGER_SOFT));
                break;
            default:
                for (MarkdownLite.Block block : MarkdownLite.parse(e.text)) {
                    if (block.isCode()) {
                        messages.addView(buildCodeBlock(block));
                    } else if (block.isTable()) {
                        messages.addView(buildTable(block));
                    } else if (MarkdownLite.Block.TYPE_HEADING.equals(block.type)) {
                        messages.addView(buildHeading(block));
                    } else if (MarkdownLite.Block.TYPE_QUOTE.equals(block.type)) {
                        messages.addView(buildQuote(block));
                    } else if (block.isList()) {
                        messages.addView(buildListItem(block));
                    } else {
                        messages.addView(buildText(block.text));
                    }
                }
                break;
        }
    }

    private View buildUserBubble(String text) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.END);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.setMargins(0, UiKit.dp(this, 10), 0, UiKit.dp(this, 6));
        wrap.setLayoutParams(wlp);

        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextSize(15);
        bubble.setTextColor(UiKit.TEXT_PRIMARY);
        bubble.setBackground(UiKit.rounded(this, 14, UiKit.USER_BUBBLE));
        bubble.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 9),
                UiKit.dp(this, 12), UiKit.dp(this, 9));
        bubble.setTextIsSelectable(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);
        bubble.setMaxWidth(blp.width);
        wrap.addView(bubble, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private View buildText(String md) {
        TextView v = new TextView(this);
        v.setText(applyInline(md));
        v.setTextSize(15);
        v.setTextColor(UiKit.TEXT_PRIMARY);
        v.setTextIsSelectable(true);
        v.setMovementMethod(LinkMovementMethod.getInstance());
        v.setLineSpacing(UiKit.dp(this, 3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 4));
        v.setLayoutParams(lp);
        return v;
    }

    private View buildHeading(MarkdownLite.Block b) {
        TextView v = new TextView(this);
        v.setText(applyInline(b.text));
        int level = Math.max(1, Math.min(6, b.level));
        v.setTextSize(new int[]{0, 20, 18, 17, 16, 15, 14}[level]);
        v.setTextColor(UiKit.TEXT_PRIMARY);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextIsSelectable(true);
        v.setMovementMethod(LinkMovementMethod.getInstance());
        v.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 2));
        return v;
    }

    private View buildQuote(MarkdownLite.Block b) {
        TextView v = new TextView(this);
        v.setText(applyInline(b.text));
        v.setTextSize(14);
        v.setTextColor(UiKit.TEXT_SECONDARY);
        v.setBackground(UiKit.rounded(this, 8, UiKit.SURFACE_2));
        v.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 8),
                UiKit.dp(this, 10), UiKit.dp(this, 8));
        v.setMovementMethod(LinkMovementMethod.getInstance());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 4));
        v.setLayoutParams(lp);
        return v;
    }

    private View buildListItem(MarkdownLite.Block b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int depth = Math.max(1, b.level);
        row.setPadding(UiKit.dp(this, (depth - 1) * 14), UiKit.dp(this, 2), 0, UiKit.dp(this, 2));

        TextView marker = UiKit.text(this, b.ordered ? "1." : "•", 15, UiKit.TEXT_TERTIARY);
        marker.setPadding(0, 0, UiKit.dp(this, 6), 0);
        row.addView(marker);

        TextView text = new TextView(this);
        text.setText(applyInline(b.text));
        text.setTextSize(15);
        text.setTextColor(UiKit.TEXT_PRIMARY);
        text.setMovementMethod(LinkMovementMethod.getInstance());
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View buildCodeBlock(MarkdownLite.Block b) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(UiKit.rounded(this, 10, 0xFF101215, UiKit.BORDER));
        box.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 8),
                UiKit.dp(this, 10), UiKit.dp(this, 8));
        if (!b.lang.isEmpty()) {
            TextView lang = UiKit.text(this, b.lang, 10, UiKit.TEXT_TERTIARY);
            box.addView(lang);
        }
        TextView code = UiKit.mono(this, b.text, 12, 0xFFD7DBE0);
        code.setTextIsSelectable(true);
        code.setHorizontallyScrolling(true);
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(code);
        box.addView(h);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6));
        box.setLayoutParams(lp);
        return box;
    }

    private View buildTable(MarkdownLite.Block b) {
        android.widget.TableLayout table = new android.widget.TableLayout(this);
        int columns = 0;
        for (List<String> row : b.rows) {
            columns = Math.max(columns, row.size());
        }
        for (int r = 0; r < b.rows.size(); r++) {
            android.widget.TableRow tr = new android.widget.TableRow(this);
            for (int c = 0; c < columns; c++) {
                String text = c < b.rows.get(r).size() ? b.rows.get(r).get(c) : "";
                TextView cell = new TextView(this);
                cell.setText(applyInline(text));
                cell.setTextSize(12);
                cell.setTextColor(UiKit.TEXT_PRIMARY);
                cell.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 6),
                        UiKit.dp(this, 8), UiKit.dp(this, 6));
                if (r == 0) {
                    cell.setTypeface(Typeface.DEFAULT_BOLD);
                    cell.setBackgroundColor(UiKit.SURFACE_2);
                }
                tr.addView(cell);
            }
            table.addView(tr);
        }
        table.setBackground(UiKit.rounded(this, 8, Color.TRANSPARENT, UiKit.BORDER));
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(table);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6));
        h.setLayoutParams(lp);
        return h;
    }

    /** 工具调用行：像桌面端那样一条带边框的紧凑行。 */
    private View buildToolRow(SessionTranscript.Entry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(UiKit.rounded(this, 8, UiKit.SURFACE_2, UiKit.BORDER));
        row.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 7),
                UiKit.dp(this, 10), UiKit.dp(this, 7));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        String status = "done".equals(e.toolStatus) ? "✓"
                : "error".equals(e.toolStatus) ? "✕" : "…";
        int color = "done".equals(e.toolStatus) ? UiKit.SUCCESS
                : "error".equals(e.toolStatus) ? UiKit.DANGER : UiKit.TEXT_TERTIARY;
        TextView glyph = UiKit.text(this, status + " ", 12, color);
        head.addView(glyph);
        TextView name = UiKit.text(this, e.toolName == null ? "tool" : e.toolName, 12, UiKit.TEXT_PRIMARY);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(name);
        row.addView(head);

        if (e.text != null && !e.text.trim().isEmpty()) {
            TextView args = UiKit.mono(this, e.text, 11, UiKit.TEXT_SECONDARY);
            args.setMaxLines(3);
            args.setEllipsize(TextUtils.TruncateAt.END);
            args.setPadding(0, UiKit.dp(this, 3), 0, 0);
            row.addView(args);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 4));
        row.setLayoutParams(lp);
        return row;
    }

    private View buildNotice(String text, int color, int bg) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(color);
        v.setBackground(UiKit.rounded(this, 8, bg));
        v.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 8),
                UiKit.dp(this, 10), UiKit.dp(this, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6));
        v.setLayoutParams(lp);
        return v;
    }

    private CharSequence applyInline(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (MarkdownLite.Inline in : MarkdownLite.parseInline(text)) {
            int start = sb.length();
            sb.append(in.text);
            int end = sb.length();
            int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            switch (in.type) {
                case MarkdownLite.Inline.BOLD:
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, flags);
                    break;
                case MarkdownLite.Inline.ITALIC:
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, flags);
                    break;
                case MarkdownLite.Inline.CODE:
                    sb.setSpan(new TypefaceSpan("monospace"), start, end, flags);
                    sb.setSpan(new BackgroundColorSpan(0x33FFFFFF), start, end, flags);
                    break;
                case MarkdownLite.Inline.LINK:
                    sb.setSpan(new UnderlineSpan(), start, end, flags);
                    sb.setSpan(new ForegroundColorSpan(UiKit.ACCENT), start, end, flags);
                    final String url = in.url;
                    sb.setSpan(new ClickableSpan() {
                        @Override
                        public void onClick(View widget) {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                            } catch (Exception ignored) {
                                // ignore
                            }
                        }
                    }, start, end, flags);
                    break;
                default:
                    break;
            }
        }
        return sb;
    }

    /* ================================================================== */
    /* 发送 + 附件                                                         */
    /* ================================================================== */

    private void sendMessage() {
        final String text = input.getText().toString().trim();
        final List<String> parts = new ArrayList<>(pendingParts);
        if (text.isEmpty() && parts.isEmpty()) {
            return;
        }
        if (sessionId == null || sessionId.isEmpty()) {
            Toast.makeText(this, "先在左侧选一个会话，或点「新会话」", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!instance.hasApiCredential()) {
            promptTokenLogin();
            return;
        }
        input.setText("");
        pendingUserText = text.isEmpty() ? "（附件 " + pendingPartNames.size() + " 个）" : text;
        pendingError = null;
        pendingScroll = true;
        // 乐观渲染：直接飘一条用户气泡
        messages.addView(buildUserBubble(pendingUserText));
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));

        Task.run(this, "dsh-send", new Task.Work<Void>() {
            @Override
            public Void run() throws Exception {
                DshClient.Session s = DshClient.connect(instance.tokenUrl, 8000);
                DshClient.prompt(s, sessionId, text, parts);
                return null;
            }
        }, new Task.Done<Void>() {
            @Override
            public void onResult(Void value, String error) {
                if (error != null) {
                    pendingError = error;
                    messages.addView(buildNotice("发送失败：" + error, UiKit.DANGER, UiKit.DANGER_SOFT));
                } else {
                    pendingParts.clear();
                    pendingPartNames.clear();
                    renderAttachStatus();
                }
                pendingScroll = true;
            }
        });
    }

    private void pickAttachment() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择要发送的文件"), REQ_ATTACH);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ATTACH || resultCode != RESULT_OK || data == null) {
            return;
        }
        final List<Uri> uris = new ArrayList<>();
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        attachStatus.setVisibility(View.VISIBLE);
        attachStatus.setText("正在准备附件…");
        Task.run(this, "dsh-attach", new Task.Work<List<String[]>>() {
            @Override
            public List<String[]> run() throws Exception {
                List<String[]> out = new ArrayList<>();
                DshClient.Session s = DshClient.connect(instance.tokenUrl, 8000);
                for (Uri uri : uris) {
                    PickedFile f = PickedFile.read(ChatActivity.this, uri, MAX_ATTACH_BYTES);
                    if (f.isImage() && f.isSupportedImage() && f.bytes.length <= MAX_INLINE_IMAGE_BYTES) {
                        out.add(new String[]{f.name,
                                DshClient.imagePart(f.mimeType, Base64Lite.encode(f.bytes), f.name)});
                    } else {
                        String body = DshClient.uploadFile(s, sessionId, f.name, f.bytes);
                        String receipt = DshClient.parseUploadReceiptId(body);
                        if (receipt.isEmpty()) {
                            throw new IllegalStateException("上传没有返回 receiptId：" + f.name);
                        }
                        out.add(new String[]{f.name, DshClient.filePart(receipt)});
                    }
                }
                return out;
            }
        }, new Task.Done<List<String[]>>() {
            @Override
            public void onResult(List<String[]> value, String error) {
                if (error != null) {
                    attachStatus.setVisibility(View.VISIBLE);
                    attachStatus.setText("附件失败：" + error);
                    attachStatus.setTextColor(UiKit.DANGER);
                    return;
                }
                for (String[] item : value) {
                    pendingPartNames.add(item[0]);
                    pendingParts.add(item[1]);
                }
                renderAttachStatus();
            }
        });
    }

    private void renderAttachStatus() {
        if (pendingParts.isEmpty()) {
            attachStatus.setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder("附件：");
        for (int i = 0; i < pendingPartNames.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(pendingPartNames.get(i));
        }
        attachStatus.setVisibility(View.VISIBLE);
        attachStatus.setTextColor(UiKit.SUCCESS);
        attachStatus.setText(sb.toString());
    }

    /* ================================================================== */
    /* 实时流 + 重连                                                       */
    /* ================================================================== */

    private void startStream(String baseUrl, String cookie) {
        if (baseUrl == null || cookie == null || sessionId == null) {
            return;
        }
        streamClosedByUser = false;
        try {
            stream = ObserveEventStream.open(baseUrl, cookie, sessionId, new ObserveEventStream.Listener() {
                @Override
                public void onReady() {
                    live = true;
                    reconnectAttempt = 0;
                    runOnUiThread(() -> headerStatus.setText(statusText()));
                }

                @Override
                public void onEvent(SessionEvent event) {
                    int added = builder.apply(java.util.Collections.singletonList(event));
                    if (added > 0) {
                        pendingScroll = true;
                        runOnUiThread(ChatActivity.this::appendEntries);
                    } else if ("tool/result".equals(event.type)) {
                        runOnUiThread(ChatActivity.this::renderAll);
                    }
                }

                @Override
                public void onEnd() {
                    live = false;
                    runOnUiThread(() -> {
                        headerStatus.setText(statusText());
                        scheduleReconnect("流已结束");
                    });
                }

                @Override
                public void onError(Exception error) {
                    live = false;
                    final String msg = error == null ? "未知错误" : error.getMessage();
                    runOnUiThread(() -> scheduleReconnect(msg));
                }
            });
        } catch (Exception e) {
            scheduleReconnect(e.getMessage());
        }
    }

    private String statusText() {
        if (projection == null) {
            return live ? "实时连接中" : "未连接";
        }
        return projection.statsLine() + (live ? "  ·  实时" : "");
    }

    private void scheduleReconnect(String reason) {
        if (streamClosedByUser || isFinishing()) {
            return;
        }
        reconnectAttempt++;
        long delay = Math.min(1000L * (1L << Math.min(reconnectAttempt - 1, 5)), 30000L);
        headerStatus.setText("连接断开（" + reason + "），" + (delay / 1000) + "s 后重连");
        if (reconnectRunnable != null) {
            handler.removeCallbacks(reconnectRunnable);
        }
        reconnectRunnable = () -> {
            if (!isFinishing() && !streamClosedByUser && sessionId != null) {
                openSession(sessionId, sessionTitle);
            }
        };
        handler.postDelayed(reconnectRunnable, delay);
    }

    private void closeStream() {
        live = false;
        streamClosedByUser = true;
        if (reconnectRunnable != null) {
            handler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ignored) {
                // ignore
            }
            stream = null;
        }
    }

    /* ================================================================== */
    /* 菜单 / 杂项                                                         */
    /* ================================================================== */

    private void showHeaderMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "复制全文");
        menu.getMenu().add(0, 2, 1, "刷新");
        menu.getMenu().add(0, 5, 2, "粘贴启动地址…");
        menu.getMenu().add(0, 3, 3, "打开官方界面");
        menu.getMenu().add(0, 4, 4, "实例与设置");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    copyTranscript();
                    return true;
                case 2:
                    if (sessionId != null) {
                        openSession(sessionId, sessionTitle);
                    } else {
                        loadSessions();
                    }
                    return true;
                case 5:
                    promptTokenLogin();
                    return true;
                case 3:
                    openOfficial(null);
                    return true;
                case 4:
                    startActivity(new Intent(this, MainActivity.class));
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void copyTranscript() {
        SessionTranscript t = builder.build();
        if (t.isEmpty()) {
            Toast.makeText(this, "没有可复制的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DSH", t.toPlainText()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }
    }

    /** 官方 UI 里的插件页/界面：手机上直接开 WebView，不重画。 */
    private void openOfficial(String page) {
        Intent it = new Intent(this, WebActivity.class);
        int idx = store.indexOfUrl(instance.url);
        it.putExtra(WebActivity.EXTRA_INDEX, idx >= 0 ? idx : 0);
        startActivity(it);
    }

    private void showConnectHint() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 16), UiKit.dp(this, 18), UiKit.dp(this, 8));
        TextView title = UiKit.text(this, "还没连接 DSH", 15, UiKit.TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);
        TextView body = UiKit.text(this,
                "在这台手机上要连你自己的 DSH。两种方式：\n\n"
                        + "① 只用官方界面：在「实例与设置」里填地址即可（打开后按官方流程登录）。\n"
                        + "② 用原生会话/聊天：还需要粘贴 DSH 启动时打印的启动地址（带 token）。",
                13, UiKit.TEXT_SECONDARY);
        body.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 8));
        box.addView(body);
        new AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton("打开设置", (d, w) -> startActivity(new Intent(this, MainActivity.class)))
                .setNegativeButton("稍后", null)
                .show();
    }

    private void promptTokenLogin() {
        final EditText in = new EditText(this);
        in.setHint("dsh web: http://…/?token=…");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this)
                .setTitle("粘贴启动地址")
                .setMessage("在跑 DSH 的电脑上，启动时的终端会打印一行带 token 的地址。把整行粘贴进来即可。")
                .setView(in)
                .setPositiveButton("保存并连接", (d, w) -> {
                    String url = TokenUrl.extract(in.getText().toString());
                    if (url == null) {
                        Toast.makeText(this, "没找到带 token 的地址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    instance.tokenUrl = url;
                    int idx = store.indexOfUrl(instance.url);
                    if (idx >= 0) {
                        store.update(idx, instance);
                    }
                    loadSessions();
                    createSession();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String relativeTime(long millis) {
        if (millis <= 0) {
            return "";
        }
        long diff = System.currentTimeMillis() - millis;
        if (diff < 60_000L) {
            return "刚刚";
        }
        if (diff < 3_600_000L) {
            return (diff / 60_000L) + "分";
        }
        if (diff < 86_400_000L) {
            return (diff / 3_600_000L) + "小时";
        }
        return (diff / 86_400_000L) + "天";
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = System.currentTimeMillis();
        closeStream();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt >= 2000L && sessionId != null) {
            pausedAt = 0;
            openSession(sessionId, sessionTitle);
        }
        pausedAt = 0;
        List<Instance> all = store.load();
        int idx = store.indexOfUrl(store.getLastOpenedUrl());
        if (idx >= 0 && idx < all.size()) {
            instance = all.get(idx);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeStream();
    }
}

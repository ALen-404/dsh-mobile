package com.dshmobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dshmobile.protocol.DshClient;
import com.dshmobile.protocol.MarkdownLite;
import com.dshmobile.protocol.ObserveEventStream;
import com.dshmobile.protocol.SessionEvent;
import com.dshmobile.protocol.SessionProjection;
import com.dshmobile.protocol.SessionTranscript;
import com.dshmobile.protocol.TranscriptBuilder;

import java.util.List;

/**
 * 原生会话详情（B 模式）。
 *
 * <p>两段数据：
 * <ol>
 *   <li><b>投影</b>（{@code session/projections}）：标题、目录、权限、统计、token、上下文构成</li>
 *   <li><b>对话</b>（{@code session/page} 历史 + {@code session/observeEvent} 增量）：
 *       事件日志折叠成消息；增量按 seq 合并，所以能看到"实时"追加</li>
 * </ol>
 *
 * <p>流式渲染的可靠性来自 {@link TranscriptBuilder}：历史与推送会重叠（同一事件两边都有），
 * 它按 seq 去重保序；这部分逻辑有自检覆盖，不依赖 UI。
 */
public class SessionDetailActivity extends Activity {

    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String EXTRA_TITLE = "title";

    private InstanceStore store;
    private Instance instance;
    private int index = -1;
    private String sessionId;
    private String initialTitle;

    private LinearLayout content;
    private ScrollView scroll;
    private TextView status;
    private Button refresh;
    private EditText input;
    /** 渲染后是否自动滚到底部（新消息到达时）。 */
    private boolean pendingScroll = true;
    /** 已发出但还没在事件流里出现的用户消息（乐观渲染）。 */
    private String pendingUserText;
    /** 最近一次发送失败的原因。 */
    private String pendingError;

    private SessionProjection projection;
    private final TranscriptBuilder builder = new TranscriptBuilder();
    private ObserveEventStream stream;
    private volatile boolean live;
    /** 上次离开前台的时间（用于判断回来时要不要刷新）。 */
    private long pausedAt;

    /* 分容器渲染：事件频繁到达时只 append 新消息，不全量重建 */
    private LinearLayout infoBox;
    private LinearLayout messagesBox;
    private LinearLayout pendingBox;
    private TextView noteView;
    /** 已经把多少条对话条目渲染进 messagesBox。 */
    private int renderedEntries;

    public SessionDetailActivity() {
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new InstanceStore(this);
        index = getIntent().getIntExtra(WebActivity.EXTRA_INDEX, -1);
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        initialTitle = getIntent().getStringExtra(EXTRA_TITLE);

        List<Instance> all = store.load();
        if (index < 0 || index >= all.size() || sessionId == null) {
            finish();
            return;
        }
        instance = all.get(index);
        setContentView(buildUi());
        load();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView header = new TextView(this);
        header.setText(initialTitle == null ? "会话详情" : initialTitle);
        header.setTextSize(19);
        header.setPadding(dp(20), dp(18), dp(20), dp(2));
        root.addView(header);

        status = new TextView(this);
        status.setTextSize(12);
        status.setAlpha(0.65f);
        status.setPadding(dp(20), 0, dp(20), dp(8));
        status.setText("正在读取会话 …");
        root.addView(status);

        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), 0, dp(20), dp(10));

        // 分三个容器，便于增量渲染：信息区 / 消息区 / 待发区
        infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(infoBox);

        content.addView(buildChatHeader());

        messagesBox = new LinearLayout(this);
        messagesBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(messagesBox);

        pendingBox = new LinearLayout(this);
        pendingBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(pendingBox);

        noteView = new TextView(this);
        noteView.setTextSize(11);
        noteView.setAlpha(0.55f);
        noteView.setPadding(0, dp(14), 0, 0);
        content.addView(noteView);

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        input = new EditText(this);
        input.setHint("发消息给这个会话…");
        input.setTextSize(15);
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        inputRow.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button send = new Button(this);
        send.setText("发送");
        send.setAllCaps(false);
        send.setOnClickListener(v -> sendMessage());
        inputRow.addView(send, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.setMargins(dp(12), dp(4), dp(12), 0);
        root.addView(inputRow, inputLp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        Button back = new Button(this);
        back.setText("返回");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> load());
        bar.addView(refresh, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button open = new Button(this);
        open.setText("官方界面");
        open.setAllCaps(false);
        open.setOnClickListener(v -> openOfficialUi());
        bar.addView(open, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.setMargins(dp(12), dp(2), dp(12), dp(10));
        root.addView(bar, barLp);

        return root;
    }

    /* ------------------------------------------------------------------ */
    /* 加载：历史（投影 + 分页）→ 渲染 → 订阅增量                           */
    /* ------------------------------------------------------------------ */

    private void load() {
        closeStream();
        status.setText("正在读取会话 …");
        refresh.setEnabled(false);
        Task.run(this, "dsh-session-detail", new Task.Work<Loaded>() {
            @Override
            public Loaded run() throws Exception {
                Loaded loaded = new Loaded();
                DshClient.Session session = DshClient.connect(instance.tokenUrl, 8000);
                loaded.baseUrl = session.baseUrl;
                loaded.cookie = session.cookie;
                loaded.projection = SessionProjection.parse(
                        DshClient.projections(session, sessionId), sessionId);
                loaded.history = SessionEvent.parsePage(DshClient.page(session, sessionId));
                return loaded;
            }
        }, new Task.Done<Loaded>() {
            @Override
            public void onResult(Loaded loaded, String error) {
                refresh.setEnabled(true);
                if (error != null) {
                    renderError(error);
                    return;
                }
                projection = loaded.projection;
                if (loaded.history != null) {
                    builder.apply(loaded.history);
                }
                renderAll();
                startStream(loaded.baseUrl, loaded.cookie);
            }
        });
    }

    /** 一次加载要带出来的东西（避免用 Object[] 传参）。 */
    private static final class Loaded {
        SessionProjection projection;
        List<SessionEvent> history;
        String baseUrl;
        String cookie;
    }

    private void startStream(String baseUrl, String cookie) {
        if (baseUrl == null || cookie == null) {
            return;
        }
        try {
            stream = ObserveEventStream.open(baseUrl, cookie, sessionId, new ObserveEventStream.Listener() {
                @Override
                public void onReady() {
                    live = true;
                    runOnUiThread(() -> status.setText(statusLineText()));
                }

                @Override
                public void onEvent(SessionEvent event) {
                    int added = builder.apply(java.util.Collections.singletonList(event));
                    // 真实消息到达后，撤掉对应的乐观气泡
                    if (pendingUserText != null && "user/message".equals(event.type)) {
                        for (SessionTranscript.Entry e : builder.build().entries()) {
                            if (SessionTranscript.ROLE_USER.equals(e.role)
                                    && pendingUserText.equals(e.text)) {
                                pendingUserText = null;
                                break;
                            }
                        }
                    }
                    if (added > 0) {
                        pendingScroll = true;
                        // 增量：只 append 新条目，不重建整个消息区
                        runOnUiThread(SessionDetailActivity.this::appendEntries);
                    } else if ("tool/result".equals(event.type)) {
                        // 工具结果回填会改动已渲染的卡片状态，这种情况才全量重建
                        runOnUiThread(SessionDetailActivity.this::renderAll);
                    } else {
                        // 没有新增对话条目（turn/step 之类），只更新状态行与待发区
                        runOnUiThread(() -> {
                            status.setText(statusLineText());
                            renderPending();
                        });
                    }
                }

                @Override
                public void onEnd() {
                    live = false;
                    runOnUiThread(() -> status.setText(statusLineText()));
                }

                @Override
                public void onError(Exception error) {
                    live = false;
                    runOnUiThread(() -> status.setText("实时连接断开：" + error.getMessage()));
                }
            });
        } catch (Exception e) {
            status.setText("实时连接失败（历史仍可看）：" + e.getMessage());
        }
    }

    private String statusLineText() {
        String base = projection == null ? "" : "水位 asOfSeq=" + projection.asOfSeq + " · ";
        return base + (live ? "实时连接中 ★" : "实时未连接");
    }

    /* ------------------------------------------------------------------ */
    /* 渲染                                                                */
    /* ------------------------------------------------------------------ */

    /** 「对话」标题行（右侧是复制全文）。静态内容，只建一次。 */
    private View buildChatHeader() {
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setPadding(0, dp(20), 0, dp(6));

        TextView sep = new TextView(this);
        sep.setText("对话");
        sep.setTextSize(13);
        sep.setAlpha(0.6f);
        titleRow.addView(sep, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copyAll = new TextView(this);
        copyAll.setText("复制全文");
        copyAll.setTextSize(12);
        copyAll.setAlpha(0.7f);
        copyAll.setPadding(dp(12), 0, 0, 0);
        copyAll.setOnClickListener(v -> copyTranscript());
        titleRow.addView(copyAll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return titleRow;
    }

    private void renderAll() {
        SessionTranscript t = builder.build();

        // 信息区（投影）
        infoBox.removeAllViews();
        if (projection != null) {
            SessionProjection p = projection;
            String title = p.displayTitle();
            if ((p.title == null || p.title.trim().isEmpty()) && !t.title.isEmpty()) {
                title = t.title;
            }
            addRow("标题", title);
            addRow("工作目录", p.workingDirectory.isEmpty() ? "(未设置)" : p.workingDirectory);
            addRow("权限", p.permission.isEmpty() ? "(未知)" : p.permission);
            addRow("预设", p.agentPreset.isEmpty() ? "(无)" : p.agentPreset);
            addRow("轮次统计", p.statsLine());
            addRow("Token", "输入 " + p.inputTokens() + "（缓存读 " + p.cacheReadTokens + "）"
                    + " · 输出 " + p.outputTokens + " · 合计 " + p.totalTokens());
            addRow("上下文构成", "系统 " + p.systemTokens + " · 工具 " + p.toolsTokens
                    + " · 消息 " + p.messageTokens);
            addRow("会话 ID", sessionId);
        }
        status.setText(statusLineText());

        // 消息区（全量重建；只在初次加载 / 工具状态回填时走这里）
        messagesBox.removeAllViews();
        if (t.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（这个会话还没有消息）");
            empty.setTextSize(13);
            empty.setAlpha(0.55f);
            messagesBox.addView(empty);
        } else {
            for (SessionTranscript.Entry e : t.entries()) {
                addMessage(e);
            }
        }
        renderedEntries = t.entries().size();

        updateNote(t);
        renderPending();
        scrollToBottom();
    }

    /**
     * 只把**新条目**追加到消息区（实时事件走这里）。
     *
     * <p>为什么重要：一次模型回复会产生几十上百个事件，如果每个事件都全量重建，
     * 真机上会明显卡顿、滚动位置还会跳。这里只 append，不动已有 View。
     */
    private void appendEntries() {
        SessionTranscript t = builder.build();
        java.util.List<SessionTranscript.Entry> entries = t.entries();
        for (int i = renderedEntries; i < entries.size(); i++) {
            addMessage(entries.get(i));
        }
        renderedEntries = entries.size();
        updateNote(t);
        renderPending();
        scrollToBottom();
    }

    private void updateNote(SessionTranscript t) {
        noteView.setText("对话由事件日志折叠而来（跳过 " + t.systemMessages + " 条系统消息、"
                + t.injectedContext + " 条上下文注入）。工具调用卡片等仍在开发中，"
                + "完整效果请点「官方界面」。");
    }

    private void scrollToBottom() {
        if (pendingScroll && scroll != null) {
            pendingScroll = false;
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** 乐观气泡 / 发送失败提示（独立容器，重建代价很小）。 */
    private void renderPending() {
        pendingBox.removeAllViews();
        if (pendingUserText != null) {
            TextView role = new TextView(this);
            role.setTextSize(11);
            role.setAlpha(0.6f);
            role.setPadding(0, dp(12), 0, dp(2));
            role.setText("你 · 发送中…");
            pendingBox.addView(role);

            TextView body = new TextView(this);
            body.setText(pendingUserText);
            body.setTextSize(15);
            body.setAlpha(0.6f);
            pendingBox.addView(body);
        }
        if (pendingError != null) {
            TextView err = new TextView(this);
            err.setText(pendingError);
            err.setTextSize(13);
            err.setTextColor(0xFFB00020);
            err.setPadding(0, dp(10), 0, 0);
            pendingBox.addView(err);
        }
    }

    /** 加载失败：只留错误信息。 */
    private void renderError(String error) {
        infoBox.removeAllViews();
        messagesBox.removeAllViews();
        pendingBox.removeAllViews();
        renderedEntries = 0;
        addRow("错误", error);
        addRow("会话 ID", sessionId);
        noteView.setText("");
        status.setText("读取失败");
    }

    /** 把整段对话按纯文本复制（方便贴到别处 / 发给别人排查）。 */
    private void copyTranscript() {
        SessionTranscript t = builder.build();
        if (t.isEmpty()) {
            Toast.makeText(this, "没有可复制的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(android.content.ClipData.newPlainText("DSH 会话", t.toPlainText()));
        Toast.makeText(this, "已复制 " + t.entries().size() + " 条", Toast.LENGTH_SHORT).show();
    }

    private void addRow(String label, String value) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(11);
        l.setAlpha(0.55f);
        l.setPadding(0, dp(10), 0, 0);
        infoBox.addView(l);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(15);
        v.setTextIsSelectable(true);
        infoBox.addView(v);
    }

    private void addMessage(SessionTranscript.Entry entry) {
        TextView role = new TextView(this);
        role.setTextSize(11);
        role.setAlpha(0.6f);
        role.setPadding(0, dp(12), 0, dp(2));
        switch (entry.role) {
            case SessionTranscript.ROLE_USER:
                role.setText("你");
                break;
            case SessionTranscript.ROLE_ASSISTANT:
                role.setText("DSH");
                break;
            case SessionTranscript.ROLE_TOOL:
                role.setText("工具 · " + (entry.toolName == null ? "?" : entry.toolName)
                        + " · " + toolStatusText(entry.toolStatus));
                break;
            default:
                role.setText("错误");
                role.setTextColor(0xFFB00020);
                break;
        }
        messagesBox.addView(role);

        if (entry.isTool()) {
            TextView body = new TextView(this);
            body.setText(entry.text);
            body.setTextIsSelectable(true);
            // 工具参数用小一号字体 + 缩进，看起来像"卡片"
            body.setTextSize(12);
            body.setAlpha(0.8f);
            body.setPadding(dp(12), dp(6), dp(8), dp(6));
            body.setBackgroundColor(0x0D000000);
            messagesBox.addView(body);
            return;
        }

        // 普通消息：按 Markdown-lite 分块，代码块用等宽字体 + 横滚
        for (MarkdownLite.Block block : MarkdownLite.parse(entry.text)) {
            if (block.isCode()) {
                addCodeBlock(block);
            } else {
                TextView body = new TextView(this);
                body.setText(block.text);
                body.setTextSize(15);
                body.setTextIsSelectable(true);
                if (entry.isError()) {
                    body.setTextColor(0xFFB00020);
                }
                messagesBox.addView(body);
            }
        }
    }

    /** 代码块：等宽字体 + 浅底色 + 横向滚动（不折行，保持缩进可读）。 */
    private void addCodeBlock(MarkdownLite.Block block) {
        if (!block.lang.isEmpty()) {
            TextView lang = new TextView(this);
            lang.setText(block.lang);
            lang.setTextSize(10);
            lang.setAlpha(0.5f);
            lang.setPadding(dp(12), dp(8), 0, 0);
            messagesBox.addView(lang);
        }
        TextView code = new TextView(this);
        code.setText(block.text);
        code.setTextSize(12);
        code.setTypeface(Typeface.MONOSPACE);
        code.setHorizontallyScrolling(true);
        code.setTextIsSelectable(true);
        code.setPadding(dp(12), dp(8), dp(12), dp(8));
        code.setBackgroundColor(0x14000000);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(code);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        messagesBox.addView(scroller, lp);
    }

    private String toolStatusText(String status) {
        if ("done".equals(status)) {
            return "完成";
        }
        if ("error".equals(status)) {
            return "失败";
        }
        return "运行中…";
    }

    /* ------------------------------------------------------------------ */
    /* 发送消息                                                            */
    /* ------------------------------------------------------------------ */

    private void sendMessage() {
        if (input == null) {
            return;
        }
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        input.setText("");
        pendingUserText = text;
        pendingError = null;
        pendingScroll = true;
        renderAll();

        Task.run(this, "dsh-send", new Task.Work<Void>() {
            @Override
            public Void run() throws Exception {
                DshClient.Session session = DshClient.connect(instance.tokenUrl, 8000);
                DshClient.prompt(session, sessionId, text);
                return null;
            }
        }, new Task.Done<Void>() {
            @Override
            public void onResult(Void value, String error) {
                if (error != null) {
                    // 发送失败：把乐观气泡撤掉，换成错误提示
                    pendingUserText = null;
                    pendingError = "发送失败：" + error;
                }
                // 成功时保留乐观气泡，等事件流里的 user/message 到达后自动替换
                pendingScroll = true;
                renderAll();
            }
        });
    }

    /* ------------------------------------------------------------------ */

    private void closeStream() {
        live = false;
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ignored) {
                // ignore
            }
            stream = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开页面就断开，避免后台持续占用连接
        pausedAt = System.currentTimeMillis();
        closeStream();
    }

    /**
     * 回到前台时：断开期间可能已经产生了新消息，所以重新拉一次历史并重连实时流。
     *
     * <p>只隔很短时间（例如转屏、系统对话框）就不刷新，避免打断正在看的对话。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt >= 2000L && instance != null) {
            pausedAt = 0;
            load();
        }
        pausedAt = 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeStream();
    }

    private void openOfficialUi() {
        Intent it = new Intent(this, WebActivity.class);
        it.putExtra(WebActivity.EXTRA_INDEX, index);
        startActivity(it);
        Toast.makeText(this, "在官方界面里选会话", Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}

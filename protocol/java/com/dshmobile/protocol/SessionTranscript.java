package com.dshmobile.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把事件日志折叠成"可渲染的对话"（不可变快照）。
 *
 * <p>折叠规则来自**真实捕获的事件**（docs/protocol.md §10）：
 * <ul>
 *   <li>{@code session/title} → 标题（last-wins；DSH 没有真模型标题时用首条消息兜底）</li>
 *   <li>{@code user/message} 且 {@code source.kind == "user"} → 用户条目</li>
 *   <li>{@code user/message} 但来源是上下文注入 → 过滤（否则对话里会冒出几 KB 上下文块）</li>
 *   <li>{@code system/message} → 只计数不渲染</li>
 *   <li>{@code assistant/attempt} 的 {@code finish.reason.kind=error} → 错误条目</li>
 *   <li>{@code turn/end} 的 error → 错误条目（与上一条去重）</li>
 *   <li>{@code assistant/message} → 正常回复（**未实测**，按 message 形状兜底）</li>
 *   <li>{@code tool/call} → 工具条目（工具名 + 参数，状态 pending）</li>
 *   <li>{@code tool/result} → 按 {@code message.toolCallId} 回填已有工具条目的状态（done / error）</li>
 * </ul>
 *
 * <p>增量场景（先读历史、再收推送）请用 {@link TranscriptBuilder}，它按 seq 去重保序；
 * {@link #fold(List)} 只是一次性的便捷包装。
 */
public final class SessionTranscript {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_ERROR = "error";
    /** 工具调用（docs/protocol.md §10.2 的 tool/call）。 */
    public static final String ROLE_TOOL = "tool";

    /** 一条可渲染的对话条目。 */
    public static final class Entry {
        public final String role;
        public final String text;
        public final long seq;
        /** 工具名（仅 ROLE_TOOL 有）。 */
        public String toolName;
        /** 工具状态：pending / done / error（仅 ROLE_TOOL 有，随 tool/result 更新）。 */
        public String toolStatus;

        Entry(String role, String text, long seq) {
            this.role = role;
            this.text = text;
            this.seq = seq;
        }

        public boolean isError() {
            return ROLE_ERROR.equals(role);
        }

        public boolean isTool() {
            return ROLE_TOOL.equals(role);
        }

        @Override
        public String toString() {
            return role + "(" + seq + "): " + text;
        }
    }

    public final String title;
    public final List<Entry> entries;
    /** 被折叠掉的事件类型计数（调试用）。 */
    public final int systemMessages;
    public final int ignoredEvents;
    /** 被过滤掉的"上下文注入"消息数（它们也记成 user/message）。 */
    public final int injectedContext;
    /** 已折叠的最大 seq —— 增量合并时的起点。 */
    public final long lastSeq;

    SessionTranscript(String title, List<Entry> entries, int systemMessages, int ignoredEvents,
                      int injectedContext, long lastSeq) {
        this.title = title == null ? "" : title;
        this.entries = entries == null ? new ArrayList<>() : entries;
        this.systemMessages = systemMessages;
        this.ignoredEvents = ignoredEvents;
        this.injectedContext = injectedContext;
        this.lastSeq = lastSeq;
    }

    /** 一次性折叠（历史页用）。增量场景用 {@link TranscriptBuilder}。 */
    public static SessionTranscript fold(List<SessionEvent> events) {
        TranscriptBuilder builder = new TranscriptBuilder();
        builder.apply(events);
        return builder.build();
    }

    /**
     * 构造一条"乐观"的用户消息（还没落盘，seq 记 -1）。
     * 供 UI 在等待服务端确认时先渲染出来。
     */
    public static Entry pendingUser(String text) {
        return new Entry(ROLE_USER, text, -1L);
    }

    /** 构造一条本地错误条目（例如发送失败），seq 记 -1。 */
    public static Entry localError(String text) {
        return new Entry(ROLE_ERROR, text, -1L);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * 把整段对话导成纯文本（用于"复制全文"）。
     *
     * <p>格式刻意简单，方便贴到别处或发给别人排查：
     * <pre>
     * # 标题
     *
     * 【你】
     * ...
     *
     * 【DSH】
     * ...
     * </pre>
     */
    public String toPlainText() {
        StringBuilder sb = new StringBuilder();
        if (!title.isEmpty()) {
            sb.append("# ").append(title).append("\n\n");
        }
        for (Entry e : entries) {
            if (e.isTool()) {
                sb.append("【工具 · ").append(e.toolName == null ? "?" : e.toolName).append("】\n")
                        .append(e.text).append("\n\n");
            } else if (e.isError()) {
                sb.append("【错误】\n").append(e.text).append("\n\n");
            } else if (ROLE_USER.equals(e.role)) {
                sb.append("【你】\n").append(e.text).append("\n\n");
            } else {
                sb.append("【DSH】\n").append(e.text).append("\n\n");
            }
        }
        return sb.toString().trim();
    }
}

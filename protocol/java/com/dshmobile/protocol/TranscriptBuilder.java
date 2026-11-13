package com.dshmobile.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 增量折叠器：把"读到的历史"和"随后推来的事件"合并成一份对话。
 *
 * <p>为什么需要它：DSH 的会话是"事件日志 + 增量推送"——
 * <ol>
 *   <li>先 {@code session/page} 读历史（一次拿一批）</li>
 *   <li>再 {@code session/observeEvent} 持续收新事件</li>
 * </ol>
 * 两条路可能重叠（同一事件既在历史里、又被推送），所以要按 {@code seq} 去重且保序。
 *
 * <p>契约：{@link #apply(List)} 只接受 {@code seq > lastSeq} 的事件；乱序传入也安全
 * （内部排序后逐个合并）。纯逻辑，可单测。
 */
public final class TranscriptBuilder {

    private final List<SessionTranscript.Entry> entries = new ArrayList<>();
    private final java.util.Set<Long> seenSeq = new java.util.HashSet<>();
    /** callId → 工具条目，用于收到 tool/result 时回填状态。 */
    private final java.util.Map<String, SessionTranscript.Entry> toolEntries = new java.util.LinkedHashMap<>();
    private String title = "";
    private long lastSeq = -1;
    private int systemMessages;
    private int ignoredEvents;
    private int injectedContext;
    private String lastError = "";

    /** 合并一批事件（可以重复调用）。返回本次新增的条目数。 */
    public int apply(List<SessionEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        // 保序 + 只取没见过的 seq
        List<SessionEvent> fresh = new ArrayList<>();
        for (SessionEvent e : events) {
            if (e.seq <= lastSeq || seenSeq.contains(e.seq)) {
                continue;
            }
            fresh.add(e);
        }
        if (fresh.isEmpty()) {
            return 0;
        }
        fresh.sort((a, b) -> Long.compare(a.seq, b.seq));

        int before = entries.size();
        for (SessionEvent e : fresh) {
            seenSeq.add(e.seq);
            if (e.seq > lastSeq) {
                lastSeq = e.seq;
            }
            foldOne(e);
        }
        return entries.size() - before;
    }

    private void foldOne(SessionEvent e) {
        switch (e.type) {
            case "session/title": {
                String t = Json.str(e.data, "title", "");
                if (!t.isEmpty()) {
                    title = t;
                }
                break;
            }
            case "user/message": {
                // 只收真正的用户输入：DSH 把运行时上下文注入也记成 user/message
                String sourceKind = Json.str(Json.get(e.data, "source"), "kind", "");
                if (!sourceKind.isEmpty() && !"user".equals(sourceKind)) {
                    injectedContext++;
                    break;
                }
                String text = SessionEvent.textOfContent(Json.get(e.data, "content"));
                if (!text.trim().isEmpty()) {
                    entries.add(new SessionTranscript.Entry(SessionTranscript.ROLE_USER, text, e.seq));
                }
                break;
            }
            case "system/message": {
                systemMessages++;
                break;
            }
            case "assistant/message": {
                String text = SessionEvent.textOfContent(Json.get(e.data, "content"));
                if (text.isEmpty()) {
                    text = SessionEvent.textOfContent(Json.get(Json.get(e.data, "message"), "content"));
                }
                if (!text.trim().isEmpty()) {
                    entries.add(new SessionTranscript.Entry(SessionTranscript.ROLE_ASSISTANT, text, e.seq));
                }
                break;
            }
            case "assistant/attempt": {
                String failure = failureOfAttempt(e.data);
                addErrorOnce(failure, e.seq);
                break;
            }
            case "turn/end": {
                addErrorOnce(failureOfTurnEnd(e.data), e.seq);
                break;
            }
            case "tool/call": {
                // 实测形状：{ turn, callId, name, arguments }，arguments 是 JSON 字符串
                String callId = Json.str(e.data, "callId", "");
                String name = Json.str(e.data, "name", "tool");
                String args = Json.str(e.data, "arguments", "");
                if (args.length() > 400) {
                    args = args.substring(0, 400) + "…";
                }
                SessionTranscript.Entry entry = new SessionTranscript.Entry(
                        SessionTranscript.ROLE_TOOL, args, e.seq);
                entry.toolName = name;
                entry.toolStatus = "pending";
                entries.add(entry);
                if (!callId.isEmpty()) {
                    toolEntries.put(callId, entry);
                }
                break;
            }
            case "tool/result": {
                // 实测形状：{ message: { toolCallId, isError } }
                Object message = Json.get(e.data, "message");
                String callId = Json.str(message, "toolCallId", "");
                boolean isError = Json.bool(message, "isError", false);
                SessionTranscript.Entry entry = callId.isEmpty() ? null : toolEntries.get(callId);
                if (entry != null) {
                    entry.toolStatus = isError ? "error" : "done";
                } else if (isError) {
                    entries.add(new SessionTranscript.Entry(SessionTranscript.ROLE_ERROR,
                            "工具执行失败", e.seq));
                }
                break;
            }
            default:
                ignoredEvents++;
                break;
        }
    }

    private void addErrorOnce(String failure, long seq) {
        if (failure == null || failure.isEmpty() || failure.equals(lastError)) {
            return;
        }
        lastError = failure;
        entries.add(new SessionTranscript.Entry(SessionTranscript.ROLE_ERROR, failure, seq));
    }

    public String title() {
        return title;
    }

    public long lastSeq() {
        return lastSeq;
    }

    /** 冻结成不可变快照（给 UI 渲染）。 */
    public SessionTranscript build() {
        return new SessionTranscript(
                title,
                new ArrayList<>(entries),
                systemMessages,
                ignoredEvents,
                injectedContext,
                lastSeq);
    }

    /* ---- 失败信息提取（与 SessionTranscript 同规则）---- */

    static String failureOfAttempt(Object data) {
        if (!(data instanceof java.util.Map)) {
            return null;
        }
        for (Object item : Json.list(data, "stream")) {
            Object chunk = Json.get(item, "chunk");
            if (!"finish".equals(Json.str(chunk, "type", ""))) {
                continue;
            }
            Object reason = Json.get(chunk, "reason");
            if (!"error".equals(Json.str(reason, "kind", ""))) {
                continue;
            }
            Object failure = Json.get(reason, "failure");
            String message = Json.str(failure, "message", "");
            String code = Json.str(failure, "code", "");
            if (message.isEmpty()) {
                return code.isEmpty() ? null : code;
            }
            return code.isEmpty() ? message : message + "（" + code + "）";
        }
        return null;
    }

    static String failureOfTurnEnd(Object data) {
        if (!(data instanceof java.util.Map)) {
            return null;
        }
        Object reason = Json.get(data, "reason");
        if (!"error".equals(Json.str(reason, "kind", ""))) {
            return null;
        }
        Object error = Json.get(reason, "error");
        String message = Json.str(error, "message", "");
        String code = Json.str(error, "code", "");
        if (message.isEmpty()) {
            return null;
        }
        return code.isEmpty() ? message : message + "（" + code + "）";
    }
}

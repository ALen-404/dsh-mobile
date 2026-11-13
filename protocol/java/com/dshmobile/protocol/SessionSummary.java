package com.dshmobile.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 会话摘要（{@code session/list} 的一行）。
 *
 * <p>字段来自官方 descriptor 的 result schema（{@code SessionListValue.items[]}）：
 * {@code sessionId / updatedAt / running / blank / agentAvailable / cwd? / parentSessionId?}，
 * 标题在 {@code projections.values.title}（可能是 null）。
 *
 * <p>刻意做成纯数据 + 纯静态解析，方便单测 —— 见 {@link SelfTest} 里的样例断言。
 */
public final class SessionSummary {

    public final String sessionId;
    /** 会话标题；DSH 在第一次 title 事件之前是 null，这里归一成空串。 */
    public final String title;
    /** 最近活动时间（毫秒时间戳，0 表示未知）。 */
    public final long updatedAt;
    public final boolean running;
    public final boolean blank;
    public final boolean agentAvailable;
    /** 工作目录（可能为空）。 */
    public final String cwd;
    public final boolean subagent;

    SessionSummary(String sessionId, String title, long updatedAt, boolean running, boolean blank,
                   boolean agentAvailable, String cwd, boolean subagent) {
        this.sessionId = sessionId;
        this.title = title;
        this.updatedAt = updatedAt;
        this.running = running;
        this.blank = blank;
        this.agentAvailable = agentAvailable;
        this.cwd = cwd;
        this.subagent = subagent;
    }

    /** 列表显示用的标题：没有 title 就退回 cwd 的末段，再退回 sessionId 前 8 位。 */
    public String displayTitle() {
        if (title != null && !title.trim().isEmpty()) {
            return title.trim();
        }
        if (cwd != null && !cwd.isEmpty()) {
            String normalized = cwd.replace('\\', '/');
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            int slash = normalized.lastIndexOf('/');
            String base = slash >= 0 ? normalized.substring(slash + 1) : normalized;
            if (!base.isEmpty()) {
                return base;
            }
        }
        return shortId();
    }

    public String shortId() {
        if (sessionId == null) {
            return "(unknown)";
        }
        // DSH 的会话 ID 形如 session-<uuid>；直接截前 8 位只会得到 "session-"，
        // 所以先剥掉前缀再截。
        String id = sessionId.startsWith("session-") ? sessionId.substring("session-".length()) : sessionId;
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** 状态徽标，列表右侧显示。 */
    public String badge() {
        if (running) {
            return "运行中";
        }
        if (blank) {
            return "空会话";
        }
        if (subagent) {
            return "子会话";
        }
        return agentAvailable ? "已加载" : "";
    }

    /**
     * 解析 {@code session/list} 的**完整响应体**
     * （{@code {type, rpcId, result:{ok, value:{items:[...]}}}}）。
     *
     * @return 解析失败或无 items 时返回空列表，不抛异常（列表页不该因此崩溃）
     */
    @SuppressWarnings("unchecked")
    public static List<SessionSummary> parseResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return Collections.emptyList();
        }
        Object root;
        try {
            root = Json.parse(responseBody);
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
        Object items = Json.path(root, "result", "value", "items");
        if (!(items instanceof List)) {
            return Collections.emptyList();
        }
        List<SessionSummary> out = new ArrayList<>();
        for (Object raw : (List<Object>) items) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) raw;
            String sessionId = Json.str(item, "sessionId", "");
            if (sessionId.isEmpty()) {
                continue;
            }
            Object titleRaw = Json.path(item, "projections", "values", "title");
            String title = titleRaw instanceof String ? (String) titleRaw : "";
            boolean subagent = "subagent".equals(Json.str(item, "origin", ""));
            out.add(new SessionSummary(
                    sessionId,
                    title,
                    Json.num(item, "updatedAt", 0L),
                    Json.bool(item, "running", false),
                    Json.bool(item, "blank", false),
                    Json.bool(item, "agentAvailable", false),
                    Json.str(item, "cwd", ""),
                    subagent));
        }
        // 按最近活动排序
        Collections.sort(out, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out;
    }
}

package com.dshmobile.protocol;

/**
 * 会话投影（{@code session/projections} 的 values）—— 原生界面的数据源。
 *
 * <p>实测返回结构（DSH 0.2.2-rc.1，见 docs/protocol.md §9.1）：
 * <pre>
 * { "kind":"sequenced", "asOfSeq":2, "values":{
 *     "title": null, "agentPreset":"standard",
 *     "workingDirectory":"E:\\...",
 *     "sessionStats":{ "turns":0,"steps":0,"llmMs":0,"toolMs":0,"ttftMs":0,... },
 *     "tokenUsage":{ "uncachedInputTokens":0,"outputTokens":0,"cacheReadTokens":0,"cacheWriteTokens":0 },
 *     "contextBreakdown":{ "systemTokens":0,"toolsTokens":0,"messageTokens":0 },
 *     "permissions":{ "currentValue":"danger-full-access" },
 *     "turnOutline":[], "goal":null, ...
 * }}
 * </pre>
 *
 * <p>纯数据 + 纯静态解析，可单测（见 {@link SelfTest}）。
 */
public final class SessionProjection {

    public final String sessionId;
    public final String title;
    public final String agentPreset;
    public final String workingDirectory;
    public final String permission;
    /** 投影的序号水位。 */
    public final long asOfSeq;

    public final long turns;
    public final long steps;
    public final long llmMs;
    public final long toolMs;
    public final long ttftMs;

    public final long uncachedInputTokens;
    public final long outputTokens;
    public final long cacheReadTokens;
    public final long cacheWriteTokens;

    public final long systemTokens;
    public final long toolsTokens;
    public final long messageTokens;

    /** 轮次大纲条目数（原生 UI 的"对话列表"骨架）。 */
    public final int turnOutlineCount;
    public final boolean hasGoal;

    SessionProjection(String sessionId, String title, String agentPreset, String workingDirectory,
                      String permission, long asOfSeq, long turns, long steps, long llmMs, long toolMs,
                      long ttftMs, long uncachedInputTokens, long outputTokens, long cacheReadTokens,
                      long cacheWriteTokens, long systemTokens, long toolsTokens, long messageTokens,
                      int turnOutlineCount, boolean hasGoal) {
        this.sessionId = sessionId;
        this.title = title;
        this.agentPreset = agentPreset;
        this.workingDirectory = workingDirectory;
        this.permission = permission;
        this.asOfSeq = asOfSeq;
        this.turns = turns;
        this.steps = steps;
        this.llmMs = llmMs;
        this.toolMs = toolMs;
        this.ttftMs = ttftMs;
        this.uncachedInputTokens = uncachedInputTokens;
        this.outputTokens = outputTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.systemTokens = systemTokens;
        this.toolsTokens = toolsTokens;
        this.messageTokens = messageTokens;
        this.turnOutlineCount = turnOutlineCount;
        this.hasGoal = hasGoal;
    }

    public String displayTitle() {
        if (title != null && !title.trim().isEmpty()) {
            return title.trim();
        }
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            String normalized = workingDirectory.replace('\\', '/');
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            int slash = normalized.lastIndexOf('/');
            String base = slash >= 0 ? normalized.substring(slash + 1) : normalized;
            if (!base.isEmpty()) {
                return base;
            }
        }
        return shortSessionId();
    }

    /** 会话 ID 形如 session-&lt;uuid&gt;，剥掉前缀再截，避免显示成 "session-"。 */
    public String shortSessionId() {
        if (sessionId == null) {
            return "(未知会话)";
        }
        String id = sessionId.startsWith("session-") ? sessionId.substring("session-".length()) : sessionId;
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** 输入侧 token 总量（未命中缓存 + 缓存读）。 */
    public long inputTokens() {
        return uncachedInputTokens + cacheReadTokens;
    }

    public long totalTokens() {
        return inputTokens() + outputTokens;
    }

    public String statsLine() {
        return turns + " 轮 · " + steps + " 步 · LLM " + seconds(llmMs) + " · 工具 " + seconds(toolMs);
    }

    private static String seconds(long ms) {
        if (ms <= 0) {
            return "0s";
        }
        return String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    /**
     * 解析 {@code session/projections} 的完整响应体。
     *
     * @param sessionId 调用方已知的会话 ID（响应里不带，回填用）
     * @return 解析失败返回 null（详情页应显示错误，而不是崩）
     */
    public static SessionProjection parse(String responseBody, String sessionId) {
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }
        try {
            Object root = Json.parse(responseBody);
            Object values = Json.path(root, "result", "value", "values");
            if (values == null) {
                return null;
            }
            Object stats = Json.get(values, "sessionStats");
            Object usage = Json.get(values, "tokenUsage");
            Object breakdown = Json.get(values, "contextBreakdown");
            Object permissions = Json.get(values, "permissions");

            int outline = Json.list(values, "turnOutline").size();
            Object goal = Json.get(values, "goal");

            return new SessionProjection(
                    sessionId,
                    Json.str(values, "title", ""),
                    Json.str(values, "agentPreset", ""),
                    Json.str(values, "workingDirectory", ""),
                    permissions == null ? "" : Json.str(permissions, "currentValue", ""),
                    Json.num(Json.path(root, "result", "value"), "asOfSeq", 0L),
                    Json.num(stats, "turns", 0L),
                    Json.num(stats, "steps", 0L),
                    Json.num(stats, "llmMs", 0L),
                    Json.num(stats, "toolMs", 0L),
                    Json.num(stats, "ttftMs", 0L),
                    Json.num(usage, "uncachedInputTokens", 0L),
                    Json.num(usage, "outputTokens", 0L),
                    Json.num(usage, "cacheReadTokens", 0L),
                    Json.num(usage, "cacheWriteTokens", 0L),
                    Json.num(breakdown, "systemTokens", 0L),
                    Json.num(breakdown, "toolsTokens", 0L),
                    Json.num(breakdown, "messageTokens", 0L),
                    outline,
                    goal != null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

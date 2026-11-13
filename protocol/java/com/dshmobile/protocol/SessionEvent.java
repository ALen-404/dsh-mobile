package com.dshmobile.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话日志里的一条事件（{@code session/page} 的 records[].event）。
 *
 * <p>实测形状（DSH 0.2.2-rc.1，见 docs/protocol.md §10）：
 * <pre>
 * { "type":"user/message", "seq":8, "time":1794559332265,
 *   "data": { ... } }
 * </pre>
 *
 * <p>注意：**DSH 的会话历史是事件日志，不是消息数组。** 一条"对话"由
 * {@code user/message}、{@code system/message}、{@code assistant/attempt}（含流式 chunk）、
 * {@code turn/start|end} 等多个事件拼出来，需要上层折叠（见 {@link SessionTranscript}）。
 */
public final class SessionEvent {

    public final String type;
    public final long seq;
    public final long time;
    /** 原始 data 节点（Map/List/String/…），由具体事件类型决定形状。 */
    public final Object data;

    SessionEvent(String type, long seq, long time, Object data) {
        this.type = type;
        this.seq = seq;
        this.time = time;
        this.data = data;
    }

    /** 解析 {@code session/page} 的完整响应体，取出 records[].event。 */
    public static List<SessionEvent> parsePage(String responseBody) {
        List<SessionEvent> out = new ArrayList<>();
        if (responseBody == null || responseBody.isEmpty()) {
            return out;
        }
        try {
            Object root = Json.parse(responseBody);
            for (Object rec : Json.list(Json.path(root, "result", "value"), "records")) {
                Object ev = Json.get(rec, "event");
                if (ev == null) {
                    continue;
                }
                String type = Json.str(ev, "type", "");
                if (type.isEmpty()) {
                    continue;
                }
                out.add(new SessionEvent(
                        type,
                        Json.num(ev, "seq", 0L),
                        Json.num(ev, "time", 0L),
                        Json.get(ev, "data")));
            }
        } catch (RuntimeException e) {
            // 坏数据就当空历史，界面显示"读取失败"而不是崩
            return new ArrayList<>();
        }
        return out;
    }

    /**
     * 从 {@code content:[{type:"text",text:"…"}]} 里拼出纯文本。
     *
     * <p>非文本片段（图片/文件）会渲染成一个可读的占位：
     * 图片 → {@code [图片]}；文件 → {@code [附件: 文件名]}（名字在
     * {@code attachment.name} 里，事件日志里服务端会把 receiptId 解析成 attachment 对象）。
     */
    static String textOfContent(Object contentNode) {
        StringBuilder sb = new StringBuilder();
        if (contentNode instanceof List) {
            for (Object part : (List<?>) contentNode) {
                if (!(part instanceof Map)) {
                    continue;
                }
                String type = Json.str(part, "type", "");
                if ("text".equals(type)) {
                    String text = Json.str(part, "text", "");
                    if (sb.length() > 0 && !text.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(text);
                } else if (!type.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(labelOfPart(part, type)).append(']');
                }
            }
        }
        return sb.toString();
    }

    private static String labelOfPart(Object part, String type) {
        if ("file".equals(type)) {
            Object attachment = Json.get(part, "attachment");
            String name = Json.str(attachment, "name", "");
            if (name.isEmpty()) {
                name = Json.str(part, "name", "");
            }
            return name.isEmpty() ? "[附件" : "[附件: " + name;
        }
        if ("image".equals(type)) {
            String name = Json.str(part, "name", "");
            return name.isEmpty() ? "[图片" : "[图片: " + name;
        }
        return "[" + type;
    }

    @Override
    public String toString() {
        return type + "#" + seq;
    }
}

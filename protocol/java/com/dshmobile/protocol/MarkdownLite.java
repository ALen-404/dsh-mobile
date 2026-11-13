package com.dshmobile.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Markdown 分块：只认**围栏代码块**（``` 与 ~~~），其余原样保留。
 *
 * <p>为什么只做这一件事：DSH 的对话里最常见、也最影响可读性的就是代码块
 * （纯文本显示时缩进和等宽全丢了）。表格/嵌套列表/行内样式收益低、坑多，
 * 明确不做 —— 想看得更漂亮请用官方 Web 界面。
 *
 * <p>两个刻意的行为：
 * <ul>
 *   <li><b>未闭合的围栏当代码处理</b>：流式输出时经常只有开头没有结尾，
 *       如果当成普通文本，渲染会在下一帧突然跳变；当作代码反而稳定。</li>
 *   <li>代码块保留内部原样（含空行与缩进），只去掉块尾多余的空行。</li>
 * </ul>
 *
 * <p>纯函数，无依赖，可单测（见 {@link SelfTest}）。
 */
public final class MarkdownLite {

    /** 一个渲染块。 */
    public static final class Block {
        public static final String TYPE_TEXT = "text";
        public static final String TYPE_CODE = "code";

        public final String type;
        public final String text;
        /** 代码块的语言标注（```java 里的 java），没有则为空串。 */
        public final String lang;

        Block(String type, String text, String lang) {
            this.type = type;
            this.text = text;
            this.lang = lang == null ? "" : lang;
        }

        public boolean isCode() {
            return TYPE_CODE.equals(type);
        }

        @Override
        public String toString() {
            return type + "(" + (isCode() ? lang : "-") + "): " + text;
        }
    }

    private MarkdownLite() {
    }

    /** 把文本切成块序列。空输入返回空列表。 */
    public static List<Block> parse(String text) {
        List<Block> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder buf = new StringBuilder();
        String fence = null;
        String lang = "";

        for (String line : lines) {
            String trimmed = line.trim();
            if (fence == null) {
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    flushText(out, buf);
                    fence = trimmed.substring(0, 3);
                    lang = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
                    continue;
                }
                buf.append(line).append('\n');
            } else {
                if (trimmed.startsWith(fence)) {
                    out.add(new Block(Block.TYPE_CODE, stripTrailingNewlines(buf.toString()), lang));
                    buf.setLength(0);
                    fence = null;
                    lang = "";
                    continue;
                }
                buf.append(line).append('\n');
            }
        }

        if (fence != null) {
            // 未闭合：剩余内容按代码处理（流式输出中途的常见状态）
            out.add(new Block(Block.TYPE_CODE, stripTrailingNewlines(buf.toString()), lang));
        } else {
            flushText(out, buf);
        }
        return out;
    }

    /** 只返回代码块（测试/统计用）。 */
    public static int countCodeBlocks(String text) {
        int n = 0;
        for (Block b : parse(text)) {
            if (b.isCode()) {
                n++;
            }
        }
        return n;
    }

    private static void flushText(List<Block> out, StringBuilder buf) {
        if (buf.length() == 0) {
            return;
        }
        String value = stripTrailingNewlines(buf.toString());
        buf.setLength(0);
        if (!value.isEmpty()) {
            out.add(new Block(Block.TYPE_TEXT, value, ""));
        }
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }
}

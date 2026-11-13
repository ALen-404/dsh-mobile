package com.dshmobile.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 Markdown 解析：块级 + 行内。
 *
 * <p>支持范围（够渲染 DSH 对话即可，不是完整 CommonMark）：
 * <ul>
 *   <li><b>块</b>：围栏代码块（``` / ~~~）、标题（#..######）、引用（&gt;）、
 *       列表（{@code -}/{@code *}/{@code +}/{@code 1.}，按缩进识别嵌套层级）、
 *       GFM 管道表格、普通段落</li>
 *   <li><b>行内</b>：{@code **加粗**}、{@code *斜体*} / {@code _斜体_}、
 *       {@code `行内代码`}、{@code [文字](链接)}</li>
 * </ul>
 *
 * <p>刻意的取舍：不做 HTML 内嵌、不做水平线、不做任务列表、不做表格对齐语义。
 * 这些在手机上收益小、边界多；需要精确排版时走官方 Web 界面。
 *
 * <p>两个稳定性要求（流式输出会聊到一半）：
 * <ul>
 *   <li><b>未闭合的围栏当代码块</b>：否则下一帧内容会突然从"正文"跳成"代码"</li>
 *   <li><b>表格要求下一行是分隔行</b>：否则正文里的 {@code a | b} 会被误判成表格</li>
 * </ul>
 *
 * <p>纯函数，无依赖，可单测（见 {@link SelfTest}）。
 */
public final class MarkdownLite {

    /** 一个渲染块。 */
    public static final class Block {
        public static final String TYPE_TEXT = "text";
        public static final String TYPE_CODE = "code";
        public static final String TYPE_HEADING = "heading";
        public static final String TYPE_QUOTE = "quote";
        public static final String TYPE_LIST = "list";
        public static final String TYPE_TABLE = "table";

        public final String type;
        /** 纯文本内容（标题/引用/列表项/段落）。 */
        public final String text;
        /** 代码块的语言标注。 */
        public final String lang;
        /** 标题层级 1-6；列表嵌套层级 1 起；其它为 0。 */
        public final int level;
        /** 列表项是否有序。 */
        public final boolean ordered;
        /** 表格的行（每个单元格是纯文本）。 */
        public final List<List<String>> rows;

        Block(String type, String text, String lang, int level, boolean ordered,
              List<List<String>> rows) {
            this.type = type;
            this.text = text == null ? "" : text;
            this.lang = lang == null ? "" : lang;
            this.level = level;
            this.ordered = ordered;
            this.rows = rows == null ? new ArrayList<List<String>>() : rows;
        }

        public boolean isCode() {
            return TYPE_CODE.equals(type);
        }

        public boolean isTable() {
            return TYPE_TABLE.equals(type);
        }

        public boolean isList() {
            return TYPE_LIST.equals(type);
        }

        @Override
        public String toString() {
            return type + "(" + level + (ordered ? ",ol" : "") + "): " + text;
        }
    }

    /** 一段行内文本及其样式。 */
    public static final class Inline {
        public static final String PLAIN = "plain";
        public static final String BOLD = "bold";
        public static final String ITALIC = "italic";
        public static final String CODE = "code";
        public static final String LINK = "link";

        public final String type;
        public final String text;
        /** 仅 LINK 有：目标地址。 */
        public final String url;

        Inline(String type, String text, String url) {
            this.type = type;
            this.text = text == null ? "" : text;
            this.url = url == null ? "" : url;
        }

        @Override
        public String toString() {
            return type + ":" + text + (url.isEmpty() ? "" : "->" + url);
        }
    }

    private MarkdownLite() {
    }

    /* ------------------------------------------------------------------ */
    /* 块级解析                                                            */
    /* ------------------------------------------------------------------ */

    /** 把文本切成块序列。空输入返回空列表。 */
    public static List<Block> parse(String text) {
        List<Block> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder paragraph = new StringBuilder();
        String fence = null;
        String fenceLang = "";
        StringBuilder code = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (fence != null) {
                if (trimmed.startsWith(fence)) {
                    out.add(new Block(Block.TYPE_CODE, stripTrailing(code.toString()), fenceLang, 0, false, null));
                    code.setLength(0);
                    fence = null;
                    fenceLang = "";
                } else {
                    code.append(line).append('\n');
                }
                continue;
            }

            // 围栏代码块开始
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flushParagraph(out, paragraph);
                fence = trimmed.substring(0, 3);
                fenceLang = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
                continue;
            }

            // 空行 = 段落结束
            if (trimmed.isEmpty()) {
                flushParagraph(out, paragraph);
                continue;
            }

            // 标题
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && level < 6 && trimmed.charAt(level) == '#') {
                    level++;
                }
                if (level > 0 && (trimmed.length() == level || trimmed.charAt(level) == ' ')) {
                    flushParagraph(out, paragraph);
                    out.add(new Block(Block.TYPE_HEADING, trimmed.substring(level).trim(), "", level, false, null));
                    continue;
                }
            }

            // 引用（连续多行合并成一块）
            if (trimmed.startsWith(">")) {
                flushParagraph(out, paragraph);
                StringBuilder quote = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    String q = lines[i].trim().substring(1);
                    if (q.startsWith(" ")) {
                        q = q.substring(1);
                    }
                    if (quote.length() > 0) {
                        quote.append('\n');
                    }
                    quote.append(q);
                    i++;
                }
                i--;
                out.add(new Block(Block.TYPE_QUOTE, quote.toString(), "", 0, false, null));
                continue;
            }

            // 列表项（每个条目一块，嵌套层级由缩进推断）
            int[] listInfo = listItem(trimmed);
            if (listInfo != null) {
                flushParagraph(out, paragraph);
                int indentSpaces = countLeadingSpaces(line);
                int depth = indentSpaces / 2 + 1;
                String itemText = trimmed.substring(listInfo[0]);
                out.add(new Block(Block.TYPE_LIST, itemText, "", depth, listInfo[1] == 1, null));
                continue;
            }

            // 表格：当前行含 |，下一行是分隔行
            if (i + 1 < lines.length && trimmed.contains("|") && isTableSeparator(lines[i + 1])) {
                List<List<String>> rows = new ArrayList<>();
                rows.add(splitRow(trimmed));
                i += 2; // 跳过表头与分隔行
                while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
                    rows.add(splitRow(lines[i].trim()));
                    i++;
                }
                i--;
                flushParagraph(out, paragraph);
                out.add(new Block(Block.TYPE_TABLE, "", "", 0, false, rows));
                continue;
            }

            if (paragraph.length() > 0) {
                paragraph.append('\n');
            }
            paragraph.append(line);
        }

        if (fence != null) {
            // 未闭合：剩余内容按代码处理，保证流式输出时渲染不跳变
            out.add(new Block(Block.TYPE_CODE, stripTrailing(code.toString()), fenceLang, 0, false, null));
        } else {
            flushParagraph(out, paragraph);
        }
        return out;
    }

    /** 识别列表标记，返回 {@code [标记长度, 是否有序]}；不是列表返回 null。 */
    private static int[] listItem(String trimmed) {
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            return new int[]{2, 0};
        }
        int digits = 0;
        while (digits < trimmed.length() && Character.isDigit(trimmed.charAt(digits))) {
            digits++;
        }
        if (digits > 0 && digits + 1 < trimmed.length()
                && (trimmed.charAt(digits) == '.' || trimmed.charAt(digits) == ')')
                && trimmed.charAt(digits + 1) == ' ') {
            return new int[]{digits + 2, 1};
        }
        return null;
    }

    private static boolean isTableSeparator(String line) {
        String t = line.trim();
        if (t.isEmpty() || !t.contains("-")) {
            return false;
        }
        boolean hasPipe = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '|') {
                hasPipe = true;
            } else if (c != '-' && c != ':' && c != ' ') {
                return false;
            }
        }
        return hasPipe;
    }

    private static List<String> splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|")) {
            t = t.substring(0, t.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : t.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private static int countLeadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) {
            n += (line.charAt(n) == '\t') ? 2 : 1;
        }
        return n;
    }

    private static void flushParagraph(List<Block> out, StringBuilder paragraph) {
        if (paragraph.length() == 0) {
            return;
        }
        String value = stripTrailing(paragraph.toString());
        paragraph.setLength(0);
        if (!value.trim().isEmpty()) {
            out.add(new Block(Block.TYPE_TEXT, value, "", 0, false, null));
        }
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    /* ------------------------------------------------------------------ */
    /* 行内解析                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 解析行内样式。永远返回至少一段（空输入返回空列表）。
     *
     * <p>识别顺序即优先级：行内代码最优先（其中的 {@code *} 不当强调），其次是链接，
     * 然后是加粗（两个星号），最后是斜体。
     */
    public static List<Inline> parseInline(String text) {
        List<Inline> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);

            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    flushPlain(out, plain);
                    out.add(new Inline(Inline.CODE, text.substring(i + 1, end), ""));
                    i = end + 1;
                    continue;
                }
            }

            if (c == '[') {
                int close = text.indexOf(']', i + 1);
                if (close > i && close + 1 < text.length() && text.charAt(close + 1) == '(') {
                    int paren = text.indexOf(')', close + 2);
                    if (paren > close + 1) {
                        flushPlain(out, plain);
                        out.add(new Inline(Inline.LINK, text.substring(i + 1, close),
                                text.substring(close + 2, paren)));
                        i = paren + 1;
                        continue;
                    }
                }
            }

            if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end > i + 1) {
                    flushPlain(out, plain);
                    out.add(new Inline(Inline.BOLD, text.substring(i + 2, end), ""));
                    i = end + 2;
                    continue;
                }
            }

            if (c == '*' || c == '_') {
                int end = text.indexOf(c, i + 1);
                if (end > i + 1) {
                    flushPlain(out, plain);
                    out.add(new Inline(Inline.ITALIC, text.substring(i + 1, end), ""));
                    i = end + 1;
                    continue;
                }
            }

            plain.append(c);
            i++;
        }
        flushPlain(out, plain);
        return out;
    }

    private static void flushPlain(List<Inline> out, StringBuilder plain) {
        if (plain.length() == 0) {
            return;
        }
        out.add(new Inline(Inline.PLAIN, plain.toString(), ""));
        plain.setLength(0);
    }

    /** 只返回代码块数量（旧的便捷方法，保留给测试）。 */
    public static int countCodeBlocks(String text) {
        int n = 0;
        for (Block b : parse(text)) {
            if (b.isCode()) {
                n++;
            }
        }
        return n;
    }
}

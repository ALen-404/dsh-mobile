package com.dshmobile.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个 DSH 实例的持久化记录。
 *
 * <p>为什么放在协议层：实例列表的"存进去 / 读出来"是**会丢用户数据**的地方
 * （名字里有引号、地址里有查询参数、数据被写坏），但 App 层用 {@code org.json}，
 * 没法在纯 JVM 里测。这里改用协议层自己的 {@link Json}，于是可以真正写断言。
 *
 * <p>磁盘格式（与旧版本兼容，不要随意改键名）：
 * <pre>
 * [ {"name":"家里 NAS","url":"http://192.168.1.12:3080","tokenUrl":"http://…/?token=…"}, … ]
 * </pre>
 *
 * <p>读取策略：**容错优先** —— 坏数据（非 JSON、非数组、单项坏掉）返回能读出来的部分，
 * 而不是整体失败。丢一份配置比丢一条记录糟糕得多。
 */
public final class InstanceRecord {

    public final String name;
    public final String url;
    public final String tokenUrl;
    /**
     * 配对设备 id（remote-web-ui 插件的 {@code dsh_pair} cookie 值）。
     *
     * <p>有它就能走 {@code /remote} 网关用完整宿主 API —— 用户只粘贴一次
     * {@code /pair-accept?pair=…} 链接就能用原生界面，不需要 launch token。
     */
    public final String pairDevice;

    public InstanceRecord(String name, String url, String tokenUrl) {
        this(name, url, tokenUrl, "");
    }

    public InstanceRecord(String name, String url, String tokenUrl, String pairDevice) {
        this.name = name == null ? "" : name;
        this.url = url == null ? "" : url;
        this.tokenUrl = tokenUrl == null ? "" : tokenUrl;
        this.pairDevice = pairDevice == null ? "" : pairDevice;
    }

    /**
     * 地址形状是否可用（真正的可达性由连接测试负责）。
     *
     * <p>判定要点：必须是 http/https，且**主机非空**。
     * 只判断前缀是不够的 —— {@code "http://"} 这种能通过前缀检查，却根本连不上。
     */
    public boolean hasUsableUrl() {
        return hostOf(url) != null;
    }

    /**
     * 是否配了 API 凭证（带 token 的启动地址）。
     *
     * <p>判定要点：
     * <ul>
     *   <li>必须是 http/https（{@code "httpx://…?token=a"} 不算）</li>
     *   <li>query 里必须有一个**名字正好是 token** 且值非空的参数
     *       （{@code "?mytoken=a"}、{@code "?token="} 都不算）</li>
     * </ul>
     */
    public boolean hasApiCredential() {
        if (!pairDevice.isEmpty() && hostOf(url) != null) {
            return true;
        }
        if (hostOf(tokenUrl) == null) {
            return false;
        }
        int q = tokenUrl.indexOf('?');
        if (q < 0) {
            return false;
        }
        String query = tokenUrl.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq);
            String value = part.substring(eq + 1).trim();
            if ("token".equals(key) && !value.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取出主机名（不含端口/路径）；协议不对或主机为空返回 null。
     */
    static String hostOf(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        String u = rawUrl.trim();
        String rest;
        if (u.regionMatches(true, 0, "https://", 0, 8)) {
            rest = u.substring(8);
        } else if (u.regionMatches(true, 0, "http://", 0, 7)) {
            rest = u.substring(7);
        } else {
            return null;
        }
        int end = rest.length();
        for (char stop : new char[]{'/', '?', '#'}) {
            int idx = rest.indexOf(stop);
            if (idx >= 0 && idx < end) {
                end = idx;
            }
        }
        String hostPort = rest.substring(0, end);
        if (hostPort.isEmpty()) {
            return null;
        }
        int colon = hostPort.lastIndexOf(':');
        String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        return host.isEmpty() ? null : host;
    }

    /** 单条记录的 JSON。 */
    public String toJson() {
        return "{\"name\":" + Json.quote(name)
                + ",\"url\":" + Json.quote(url)
                + ",\"tokenUrl\":" + Json.quote(tokenUrl)
                + ",\"pairDevice\":" + Json.quote(pairDevice) + "}";
    }

    /** 把列表编码成 JSON 数组文本。 */
    public static String encodeAll(List<InstanceRecord> records) {
        StringBuilder sb = new StringBuilder("[");
        if (records != null) {
            for (int i = 0; i < records.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(records.get(i) == null ? "{}" : records.get(i).toJson());
            }
        }
        return sb.append(']').toString();
    }

    /**
     * 解析 JSON 数组文本。
     *
     * @return 解析出的记录；整体不是合法 JSON 时返回空列表，单条坏掉时跳过该条
     */
    public static List<InstanceRecord> parseAll(String json) {
        List<InstanceRecord> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        Object root;
        try {
            root = Json.parse(json);
        } catch (RuntimeException e) {
            return out;
        }
        if (!(root instanceof List)) {
            return out;
        }
        for (Object item : (List<?>) root) {
            if (!(item instanceof java.util.Map)) {
                continue;
            }
            String name = Json.str(item, "name", "DSH");
            String url = Json.str(item, "url", "");
            String tokenUrl = Json.str(item, "tokenUrl", "");
            String pairDevice = Json.str(item, "pairDevice", "");
            out.add(new InstanceRecord(name, url, tokenUrl, pairDevice));
        }
        return out;
    }
}

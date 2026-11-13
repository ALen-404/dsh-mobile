package com.dshmobile;

import android.content.Context;
import android.content.SharedPreferences;

import com.dshmobile.protocol.InstanceRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 实例列表的持久化（SharedPreferences + JSON 文本）。
 *
 * <p>编码/解码本身在协议层的 {@link InstanceRecord}（有自检覆盖：中文、引号、坏数据、缺字段）；
 * 这里只负责读写 SharedPreferences。
 */
public final class InstanceStore {

    private static final String PREF = "dsh_mobile";
    private static final String KEY_INSTANCES = "instances";
    private static final String KEY_LAST_OPENED_URL = "last_opened_url";
    private static final String KEY_HOST_OWNER_PREFIX = "host_owner:";
    private static final String KEY_LAST_SESSION = "last_session";

    private final SharedPreferences prefs;

    public InstanceStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<Instance> load() {
        List<Instance> out = new ArrayList<>();
        String raw = prefs.getString(KEY_INSTANCES, "");
        // 解析容错在协议层：坏数据返回能读出来的部分，而不是整体失败
        for (InstanceRecord record : InstanceRecord.parseAll(raw)) {
            out.add(Instance.fromRecord(record));
        }
        return out;
    }

    public void save(List<Instance> instances) {
        List<InstanceRecord> records = new ArrayList<>();
        if (instances != null) {
            for (Instance i : instances) {
                if (i != null) {
                    records.add(i.toRecord());
                }
            }
        }
        prefs.edit().putString(KEY_INSTANCES, InstanceRecord.encodeAll(records)).apply();
    }

    public void add(Instance instance) {
        List<Instance> all = load();
        all.add(instance);
        save(all);
    }

    public void update(int index, Instance instance) {
        List<Instance> all = load();
        if (index >= 0 && index < all.size()) {
            all.set(index, instance);
            save(all);
        }
    }

    public void remove(int index) {
        List<Instance> all = load();
        if (index >= 0 && index < all.size()) {
            all.remove(index);
            save(all);
        }
    }

    /**
     * 记住"上次打开的实例"，按 **URL** 记而不是下标。
     *
     * <p>下标在删除/重排实例后会错位（记住的是第 2 个，删除第 1 个后变成第 3 个）；
     * URL 是稳定的。
     */
    public void setLastOpenedUrl(String url) {
        prefs.edit().putString(KEY_LAST_OPENED_URL, url == null ? "" : url).apply();
    }

    public String getLastOpenedUrl() {
        String url = prefs.getString(KEY_LAST_OPENED_URL, "");
        return url == null ? "" : url;
    }

    /** 按 URL 找实例下标；找不到返回 -1。 */
    public int indexOfUrl(String url) {
        if (url == null || url.isEmpty()) {
            return -1;
        }
        List<Instance> all = load();
        for (int i = 0; i < all.size(); i++) {
            if (url.equals(all.get(i).url)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 记录"某个域名上次是哪个实例在用"。
     *
     * <p>用途：Cookie 按域名（不含端口）共享，两个实例同域名不同端口时会串登录态。
     * 打开实例时可以据此判断"这个域名上次是别人在用"，并提醒用户清理。
     */
    public void setHostOwner(String host, String instanceUrl) {
        if (host == null || host.isEmpty()) {
            return;
        }
        prefs.edit().putString(KEY_HOST_OWNER_PREFIX + host, instanceUrl == null ? "" : instanceUrl).apply();
    }

    public String getHostOwner(String host) {
        if (host == null || host.isEmpty()) {
            return "";
        }
        String owner = prefs.getString(KEY_HOST_OWNER_PREFIX + host, "");
        return owner == null ? "" : owner;
    }

    /** 记住上次打开的会话（下次启动直接进它）。 */
    public void setLastSessionId(String sessionId) {
        prefs.edit().putString(KEY_LAST_SESSION, sessionId == null ? "" : sessionId).apply();
    }

    public String getLastSessionId() {
        String id = prefs.getString(KEY_LAST_SESSION, "");
        return id == null ? "" : id;
    }
}

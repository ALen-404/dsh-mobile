package com.dshmobile;

import com.dshmobile.protocol.InstanceRecord;

/**
 * 一个 DSH 实例：用户给它起个名字，记住地址（可选记住 token URL）。
 *
 * <p>凭证模式与步骤见 README：A 基础模式只用到 url；B API 模式额外需要 tokenUrl。
 *
 * <p>持久化格式与容错逻辑在协议层的 {@link InstanceRecord}（那边有自检覆盖），
 * 这里只做内存模型和转换 —— 避免"测试测的是另一份实现"。
 */
public final class Instance {

    /** 显示名，例如「家里 NAS」「公司」。 */
    public String name;

    /** 基础地址，例如 http://192.168.1.12:3080 或 https://dsh.example.com 。 */
    public String url;

    /**
     * B 模式（原生能力）用的带 token URL，来自 `dsh web` 的输出。
     * 为空表示该实例只走 A 模式（WebView 承载官方 UI）。
     */
    public String tokenUrl;

    /**
     * 配对设备 id（remote-web-ui 插件的 {@code dsh_pair} cookie 值）。
     * 有它就能走 {@code /remote} 网关用原生能力，不需要 token。
     */
    public String pairDevice;

    public Instance(String name, String url) {
        this(name, url, "");
    }

    public Instance(String name, String url, String tokenUrl) {
        this(name, url, tokenUrl, "");
    }

    public Instance(String name, String url, String tokenUrl, String pairDevice) {
        this.name = name == null ? "" : name;
        this.url = url == null ? "" : url;
        this.tokenUrl = tokenUrl == null ? "" : tokenUrl;
        this.pairDevice = pairDevice == null ? "" : pairDevice;
    }

    /** 转成持久化记录。 */
    public InstanceRecord toRecord() {
        return new InstanceRecord(name, url, tokenUrl, pairDevice);
    }

    /** 从持久化记录还原。 */
    public static Instance fromRecord(InstanceRecord record) {
        return new Instance(record.name, record.url, record.tokenUrl, record.pairDevice);
    }

    /** 地址是否可用（只做最基本的形状检查，真正的可达性由连接测试负责）。 */
    public boolean hasUsableUrl() {
        return toRecord().hasUsableUrl();
    }

    /** 是否配了 API 凭证。 */
    public boolean hasApiCredential() {
        return toRecord().hasApiCredential();
    }

    /** 是否用配对设备（走 /remote 网关）而不是 launch token。 */
    public boolean isPaired() {
        return pairDevice != null && !pairDevice.isEmpty();
    }
}

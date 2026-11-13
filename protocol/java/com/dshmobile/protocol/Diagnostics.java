package com.dshmobile.protocol;

/**
 * 把 DSH / 隧道层的错误码翻译成人话。
 *
 * <p>这套映射不是猜的：2026-10 至 11 期间，同一台机器上反复出现过 502 / 530 / 401
 * 三种故障，每次根因都不同，而对外表现只差一个状态码。记录见 docs/m0-findings.md。
 *
 * <p>放在协议层（而不是 App 层）的原因：诊断文案是**协议知识**的一部分，
 * 探针脚本、App、文档应该共用同一套说法。之前 App 与 JS 探针各有一份，容易说岔。
 */
public final class Diagnostics {

    private Diagnostics() {
    }

    /**
     * 连接/加载失败时的诊断建议。
     *
     * @param status     HTTP 状态码；拿不到（网络层失败）时传 0
     * @param failingUrl 出错地址，可空
     */
    public static String explain(int status, String failingUrl) {
        StringBuilder sb = new StringBuilder();
        switch (status) {
            case 200:
                sb.append("正常（鉴权通过）。");
                break;
            case 401:
                sb.append("需要鉴权（401）。\n\n")
                        .append("DSH 只服务带 token 的地址，直接打开根地址必然 401 —— 这是正常现象，不是坏了。\n")
                        .append("• 用配对二维码进来的：重新扫一次\n")
                        .append("• 用公网域名的：确认配对没有过期\n")
                        .append("• 想让 App 走原生能力：填 dsh 启动时打印的带 token 地址");
                break;
            case 403:
                sb.append("被拒绝访问（403）。\n\n")
                        .append("• 该设备没有通过配对，或已过期\n")
                        .append("• 远端插件判定公网地址不可信（姿态探测 fail-closed）\n")
                        .append("• 隧道背后的 Host / Origin 校验不通过");
                break;
            case 404:
                sb.append("地址不对（404）。\n\n")
                        .append("• 检查有没有多写/少写路径\n")
                        .append("• 用隧道域名时，确认 ingress 指向正确的本地端口");
                break;
            case 500:
            case 502:
                sb.append("隧道/网关连不上本机的 DSH（502）。\n\n")
                        .append("含义很具体：隧道是通的，但它转发到的本地端口没人监听。\n")
                        .append("• DSH 没在运行\n")
                        .append("• 或者监听的端口变了（DSH 桌面版默认每次启动随机选端口）\n")
                        .append("• 修法：确认 DSH 监听的端口，并让隧道 ingress 指向它");
                break;
            case 530:
                sb.append("隧道没有可用的连接器（530）。\n\n")
                        .append("说明 cloudflared / frp 这类隧道进程没在跑。\n")
                        .append("• 启动隧道进程\n")
                        .append("• cloudflared 意外退出后不会自愈，建议做守护");
                break;
            case 0:
                sb.append("连不上这个地址。\n\n")
                        .append("• 手机和这台机器网络是否互通（同一 WiFi？VPN？）\n")
                        .append("• DSH 是否只绑了 127.0.0.1（那样手机永远连不上，要 --host 0.0.0.0）\n")
                        .append("• 防火墙是否放行该端口\n")
                        .append("• 用域名时：DNS 是否解析、证书是否有效");
                break;
            default:
                sb.append("请求失败，HTTP ").append(status).append("。\n\n")
                        .append("如果反复出现，把地址和状态码一起反馈。");
                break;
        }
        if (failingUrl != null && !failingUrl.isEmpty()) {
            sb.append("\n\n地址：").append(failingUrl);
        }
        return sb.toString();
    }

    /** 协议层的一行建议（供探针/日志用）。 */
    public static String advice(int status) {
        switch (status) {
            case 200:
                return "正常";
            case 401:
                return "需要鉴权：用 /connect?token= 换 API Cookie，或先配对";
            case 403:
                return "被拒绝：设备未配对，或姿态探测 fail-closed";
            case 404:
                return "路径不存在：检查地址";
            case 502:
                return "隧道通但本地端口没监听：DSH 没运行或端口变了";
            case 530:
                return "隧道没有可用连接器：cloudflared 没在跑";
            case 0:
                return "连不上：检查网络/绑定地址/防火墙/DNS";
            default:
                return "HTTP " + status;
        }
    }

    /** 单行摘要，列表/Toast 用。 */
    public static String shortLabel(int status) {
        switch (status) {
            case 401:
                return "需要重新配对 (401)";
            case 403:
                return "被拒绝 (403)";
            case 404:
                return "地址不存在 (404)";
            case 502:
                return "隧道通但本地 DSH 没响应 (502)";
            case 530:
                return "隧道没连上 (530)";
            case 0:
                return "连不上";
            default:
                return "HTTP " + status;
        }
    }
}

package com.dshmobile;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 关于 / 帮助。
 *
 * <p>存在的理由：这个 App 的两级能力（A 基础 / B 原生）对第一次用的人是反直觉的 ——
 * "为什么有的功能要我再填一个地址？那个带 token 的地址从哪来？"
 * 与其让用户猜，不如把机制和获取步骤写清楚。
 *
 * <p>同时也承担**免责说明**：非官方客户端、不要连别人的实例、出问题怎么排查。
 */
public class HelpActivity extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(12));

        title(root, "DSH Mobile");
        body(root, "一个非官方的 DSH 手机客户端。它不内置任何服务器，只负责让你在手机上连"
                + "你自己运行的 DSH。");

        section(root, "两种模式");
        body(root, "① A 基础模式（只要地址）\n"
                + "App 用全屏 WebView 打开官方界面，等同于手机浏览器，但补上了诊断、"
                + "返回键、附件选择等细节。任何 DSH 地址都能用。");
        body(root, "② A + B API 模式（再多一个带 token 的地址）\n"
                + "用 DSH 的 launch token 换取 API 凭证后，App 可以原生列出会话、查看会话、"
                + "实时接收事件、发消息 —— 不经过网页，体验接近原生聊天。"
                + "实例列表里会显示成「A + B 模式」。");

        section(root, "那个带 token 的地址从哪来");
        body(root, "DSH 每次启动时会在终端打印一行形如下面的地址（token 每次重启都会变）：\n\n"
                + "dsh web: http://127.0.0.1:3080/?token=xxxxxxxx\n\n"
                + "把整行复制下来就是「启动地址」。它只存在于启动它的那个终端里，"
                + "不会写进磁盘，所以没法自动发现 —— 需要你手动粘贴一次。");
        body(root, "注意：桌面版 DSH（Electron 应用）默认不把这一行显示给你。"
                + "想用 API 模式，需要用命令行启动 DSH（例如 dsh web / dsh --profile … "
                + "--no-open --port 3080），从它的终端输出里拿地址。拿不到也没关系，"
                + "基础模式的功能是完整的。");

        section(root, "添加实例时填什么");
        body(root, "· 地址：你的 DSH 地址，例如 https://dsh.example.com 或 http://192.168.1.5:3080\n"
                + "· 启动地址（可选）：上面那行带 token 的完整地址\n\n"
                + "只想浏览官方界面 → 只填「地址」。\n"
                + "想要原生会话列表 → 两个都填。");
        body(root, "注意：浏览器的登录信息按**域名**共享，不区分端口。"
                + "如果你在同一个域名的不同端口上跑着两个 DSH（例如同一台机器的 :3080 和 :8080），"
                + "它们的登录态会互相影响。App 在切换实例时会检测这种情况并提醒你清理。");

        section(root, "连不上时怎么判断");
        body(root, "长按实例 →「测试连接」，App 会告诉你卡在哪一层：\n\n"
                + "· 401：DSH 活着，只是需要登录/凭证 —— 正常现象\n"
                + "· 502：隧道或反向代理活着，但它连不到 DSH —— 检查 DSH 是否在跑\n"
                + "· 530：隧道本身没有健康连接 —— 检查 cloudflared/隧道进程\n"
                + "· 连接失败：网络不通、地址写错、或协议/端口不对\n\n"
                + "有 API 凭证的实例还可以用「验证 API 凭证」确认 token 是否还有效。");

        section(root, "安全与免责");
        body(root, "· 这是非官方项目，与 DSH 官方无关，也不代表官方立场。\n"
                + "· 只连你自己的 DSH 实例。不要把别人的地址加进来，也不要把带 token 的地址发给别人"
                + "（拿到 token 就等于拿到你 DSH 的控制权）。\n"
                + "· token 每次重启都会变，失效后需要重新粘贴。\n"
                + "· 本 App 不收集任何数据，所有请求都直接发往你填的地址。\n"
                + "· 软件按 Apache-2.0 提供，无任何担保，风险自负。");

        scroll.addView(root);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button close = new Button(this);
        close.setText("知道了");
        close.setAllCaps(false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.setMargins(dp(16), 0, dp(16), dp(12));
        wrapper.addView(close, closeLp);

        setContentView(wrapper);
    }

    private void title(LinearLayout root, String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(22);
        v.setPadding(0, 0, 0, dp(8));
        root.addView(v);
    }

    private void section(LinearLayout root, String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(15);
        v.setPadding(0, dp(22), 0, dp(4));
        root.addView(v);
    }

    private void body(LinearLayout root, String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(14);
        v.setAlpha(0.85f);
        v.setTextIsSelectable(true);
        v.setPadding(0, 0, 0, dp(6));
        root.addView(v);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}

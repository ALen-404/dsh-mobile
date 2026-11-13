package com.dshmobile.protocol;

/**
 * 能力分级：决定 App 该显示哪些入口。
 *
 * <p>这是"按凭证分级"这条设计约束的落点 —— 上层不该自己判断
 * "有没有 token"，而应该问这里要一份能力清单，然后照着渲染。
 *
 * <p>原则：**探测不到就降级，绝不假装可用**。
 *
 * <p>权威说明见 {@code docs/capabilities.md}；{@link SelfTest} 会校验两者一致
 * （文档漏写某个能力会直接报错），所以改能力时别只改一边。
 */
public final class Capabilities {

    /** A 模式：只要地址就能用（WebView 承载官方 UI）。 */
    public final boolean webview;

    /** 原生会话列表 / 详情 / 投影：需要 API Cookie（B 模式）。 */
    public final boolean nativeSession;

    /**
     * 原生聊天：历史回填 + 实时事件 + 发送 + Markdown-lite，需要 API Cookie。
     *
     * <p>已知不确定项：**成功回复**（{@code assistant/message}）的事件形状还没实测过
     * （隔离实例没有模型凭据，只能拿到失败路径）。所以聊天界面在真机上能不能完整显示
     * 模型输出，取决于这一点；其余（用户消息、错误、工具卡片）都已验证。
     */
    public final boolean nativeChat;

    private Capabilities(boolean webview, boolean nativeSession, boolean nativeChat) {
        this.webview = webview;
        this.nativeSession = nativeSession;
        this.nativeChat = nativeChat;
    }

    /**
     * 依据凭证推导能力。
     *
     * <p>注意：有 token 只是**必要条件**，不是充分条件 —— 真正能不能用，
     * 还要靠 {@link ApiCookieExchanger} 实测换取成功。这个类只表达"理论上可尝试"。
     */
    public static Capabilities forCredential(Credential credential) {
        boolean api = credential != null && credential.isPresent();
        return new Capabilities(true, api, api);
    }

    /** 列表/标题用的简短标签。 */
    public String label() {
        if (nativeSession) {
            return "A + B 模式";
        }
        return "A 模式";
    }

    /** 给用户看的说明：为什么某些功能没开。 */
    public String explainMissing() {
        if (nativeSession) {
            return "已配置 API 凭证：可尝试原生会话、实时事件与发送。";
        }
        return "当前只有地址，没有 API 凭证：只提供 WebView 承载官方 UI。\n"
                + "想要原生会话列表，请在实例里填入 dsh 启动输出中带 ?token= 的地址。";
    }

    /**
     * 能力矩阵：{模式, 能力, 是否可用}。
     *
     * <p>用途有两个：给文档生成/校验提供单一事实来源（见 {@link SelfTest}），
     * 也让以后加能力时必须同时更新这里。
     */
    public String[][] matrixRows() {
        String yes = "是";
        String no = "否";
        String api = nativeSession ? yes : no;
        return new String[][]{
                {"A 模式", "WebView 承载官方 UI", yes},
                {"A 模式", "多实例管理", yes},
                {"A 模式", "401/502/530 诊断与连接预检", yes},
                {"A 模式", "附件选择 / JS 弹窗", yes},
                {"A + B 模式", "原生会话列表", api},
                {"A + B 模式", "原生会话详情（投影）", api},
                {"A + B 模式", "历史回填 + 实时事件", api},
                {"A + B 模式", "原生发消息", api},
                {"A + B 模式", "工具调用卡片", api},
                {"A + B 模式", "Markdown（代码块/表格/列表/行内样式）", api},
                {"A + B 模式", "原生发送附件（图片内联、其它文件先上传）", api},
                {"A + B 模式", "断线自动重连（指数退避）", api},
        };
    }
}

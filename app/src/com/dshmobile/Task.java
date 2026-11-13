package com.dshmobile;

import android.os.Handler;
import android.os.Looper;

/**
 * 极小的后台任务辅助：跑一段可能失败的工作，回到主线程交结果。
 *
 * <p>存在的理由：本项目有 5 处「new Thread + runOnUiThread + catch Exception」的重复，
 * 而且错误文案散落各处。抽出来统一两件事：
 * <ol>
 *   <li>线程与主线程切换（不用写 runOnUiThread）</li>
 *   <li>{@link #describe(Throwable)} 把异常翻译成人话（HTTP 码、RPC 错误码、超时）</li>
 * </ol>
 *
 * <p>刻意不用 AsyncTask / ExecutorService：只依赖 android.os，保持"零 androidx"。
 */
public final class Task {

    /** 后台工作。抛异常即失败。 */
    public interface Work<T> {
        T run() throws Exception;
    }

    /** 主线程回调：value 与 error 恰有一个非 null（成功时 error 为 null）。 */
    public interface Done<T> {
        void onResult(T value, String error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Task() {
    }

    /** 在后台线程执行 work，完成后在主线程回调 done。 */
    public static <T> void run(final Work<T> work, final Done<T> done) {
        run("dsh-task", work, done);
    }

    /**
     * 带"页面还活着吗"保护的版本。
     *
     * <p>为什么需要：用户可能在请求进行中就按返回离开页面；后台线程完成后仍会把结果
     * post 回主线程去碰已经销毁的 View —— 真机上表现为偶发崩溃 / 空指针。
     * 这个重载在回调前检查 Activity 是否已 finish/destroy，是那种"不做就一定会踩"的防护。
     */
    public static <T> void run(final android.app.Activity owner, String name,
                               final Work<T> work, final Done<T> done) {
        run(name, work, new Done<T>() {
            @Override
            public void onResult(T value, String error) {
                if (owner == null) {
                    return;
                }
                if (owner.isFinishing()) {
                    return;
                }
                if (android.os.Build.VERSION.SDK_INT >= 17 && owner.isDestroyed()) {
                    return;
                }
                done.onResult(value, error);
            }
        });
    }

    public static <T> void run(String name, final Work<T> work, final Done<T> done) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                T value = null;
                String error = null;
                try {
                    value = work.run();
                } catch (Exception e) {
                    error = describe(e);
                }
                final T v = value;
                final String err = error;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        done.onResult(v, err);
                    }
                });
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把异常翻译成用户能看懂的一句话。
     *
     * <p>覆盖我们实际会遇到的几类：网络不可达、HTTP 状态码、RPC 业务错误码、超时。
     */
    public static String describe(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        String message = e.getMessage() == null ? "" : e.getMessage();
        String name = e.getClass().getSimpleName();

        // 业务错误码（来自 UnaryRpc.RpcException）
        try {
            if (e instanceof com.dshmobile.protocol.UnaryRpc.RpcException) {
                String code = ((com.dshmobile.protocol.UnaryRpc.RpcException) e).code;
                return "服务端拒绝" + (code == null || code.isEmpty() ? "" : "（" + code + "）")
                        + "：" + message;
            }
        } catch (NoClassDefFoundError ignored) {
            // 协议层被裁剪时不该影响这里
        }

        if (message.contains("HTTP 401") || message.contains("HTTP 403")) {
            return "凭证无效或已过期（" + message + "）—— 重新从 dsh 的启动输出里取带 token 的地址";
        }
        if (message.contains("HTTP 404")) {
            return "这台 DSH 上没有这个接口（" + message + "）—— 版本可能不匹配";
        }
        if (message.contains("HTTP 502") || message.contains("HTTP 530")) {
            return "隧道/服务不可用（" + message + "）—— 先确认 DSH 和隧道在跑";
        }
        if (name.contains("Timeout") || message.toLowerCase(java.util.Locale.ROOT).contains("timeout")
                || message.contains("timed out")) {
            return "请求超时 —— 网络慢或 DSH 没响应";
        }
        if (name.contains("UnknownHost") || name.contains("ConnectException")
                || message.contains("Failed to connect") || message.contains("ECONNREFUSED")) {
            return "连不上：" + message;
        }
        if (name.contains("SSL") || name.contains("Certificate")) {
            return "TLS 握手失败：" + message;
        }
        return name + (message.isEmpty() ? "" : "：" + message);
    }
}

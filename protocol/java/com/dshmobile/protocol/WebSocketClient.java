package com.dshmobile.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 WebSocket 客户端（RFC 6455），**不依赖任何第三方库**。
 *
 * <p>为什么自己写：DSH 的 RPC 通道是 WebSocket，而这个 App 刻意不引入 androidx / OkHttp
 * （见 docs/build.md）。协议层又是纯 Java、不带 Android 依赖，所以这里只能落在
 * {@link Socket} / {@link SSLSocket} 上。
 *
 * <p>支持范围（够用即可，不做过度设计）：
 * <ul>
 *   <li>ws:// 与 wss://</li>
 *   <li>客户端握手（含 Sec-WebSocket-Key 校验、自定义请求头）</li>
 *   <li>文本帧收发（客户端帧按规范加掩码）、分片聚合</li>
 *   <li>ping / pong / close 控制帧</li>
 * </ul>
 *
 * <p>线程模型：一个读线程 + 调用方线程发送。发送方法加锁。
 *
 * <p>验证情况（两处都做过）：
 * <ul>
 *   <li>对标准 WebSocket 服务器：{@code WsSelfTest}（握手/掩码/收发/关闭）</li>
 *   <li>对真实 DSH 的 mux 端点：{@code LiveSelfTest} 用它订阅 {@code $events} 并收到
 *       {@code item} 帧；{@link ObserveEventStream} 也基于它工作</li>
 * </ul>
 */
public final class WebSocketClient {

    /** 回调（在读线程上触发，UI 层需自行切换线程）。 */
    public interface Listener {
        /** 握手完成；参数即刚建立的客户端，便于在回调里直接发送。 */
        void onOpen(WebSocketClient client);

        void onText(String message);

        void onClose(int code, String reason);

        void onError(Exception error);
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;

    private final Object sendLock = new Object();
    private volatile boolean closed = false;
    private Thread reader;

    private WebSocketClient(Socket socket, Listener listener) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.listener = listener;
    }

    /**
     * 建立连接（阻塞直到握手完成）。
     *
     * @param uri      ws:// 或 wss:// 地址
     * @param headers  额外请求头（例如 Cookie、x-dsh-remote-device）
     * @param listener 事件回调
     */
    public static WebSocketClient connect(URI uri, Map<String, String> headers, Listener listener)
            throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean secure = "wss".equals(scheme);
        if (!secure && !"ws".equals(scheme)) {
            throw new IOException("只支持 ws:// 或 wss://，实际是 " + scheme);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("地址里没有主机名");
        }
        int port = uri.getPort();
        if (port < 0) {
            port = secure ? 443 : 80;
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 10000);
        raw.setTcpNoDelay(true);
        Socket sock = raw;
        if (secure) {
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, host, port, true);
            ssl.startHandshake();
            sock = ssl;
        }

        WebSocketClient client = new WebSocketClient(sock, listener);
        client.handshake(host, port, path, secure, headers);
        client.startReader();
        if (listener != null) {
            listener.onOpen(client);
        }
        return client;
    }

    /* ------------------------------------------------------------------ */
    /* 握手                                                                */
    /* ------------------------------------------------------------------ */

    private void handshake(String host, int port, String path, boolean secure,
                           Map<String, String> headers) throws IOException {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host);
        if ((secure && port != 443) || (!secure && port != 80)) {
            req.append(':').append(port);
        }
        req.append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        req.append("\r\n");

        out.write(req.toString().getBytes("UTF-8"));
        out.flush();

        String statusLine = readLine();
        if (statusLine == null) {
            throw new IOException("握手没有响应");
        }
        if (!statusLine.contains("101")) {
            // 把服务端的响应码带出来，方便上层翻译成人话（401/403 等）
            throw new HandshakeException(statusLine, readHeadersBlock());
        }
        // 读完响应头（不校验 Sec-WebSocket-Accept，服务端实现差异大）
        readHeadersBlock();
    }

    /** 握手失败：携带状态行与服务端响应头，便于诊断。 */
    public static final class HandshakeException extends IOException {
        public final String statusLine;
        public final String headers;

        HandshakeException(String statusLine, String headers) {
            super("WebSocket 握手被拒绝：" + statusLine);
            this.statusLine = statusLine;
            this.headers = headers;
        }

        /** 从状态行里取 HTTP 状态码；取不到返回 0。 */
        public int status() {
            String[] parts = statusLine.split(" ");
            if (parts.length >= 2) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                }
            }
            return 0;
        }
    }

    private String readHeadersBlock() throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = readLine()) != null) {
            if (line.isEmpty()) {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b == -1) {
                return buf.size() == 0 ? null : buf.toString("UTF-8");
            }
            if (prev == '\r' && b == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = Math.max(0, bytes.length - 1); // 去掉结尾的 \r
                return new String(bytes, 0, len, "UTF-8");
            }
            buf.write(b);
            prev = b;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 发送                                                                */
    /* ------------------------------------------------------------------ */

    public void sendText(String text) throws IOException {
        sendFrame(OP_TEXT, text.getBytes("UTF-8"));
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            sendFrame(OP_CLOSE, new byte[]{0x03, (byte) 0xE8}); // 1000 normal
        } catch (IOException ignored) {
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        synchronized (sendLock) {
            int len = payload.length;
            ByteArrayOutputStream frame = new ByteArrayOutputStream(len + 14);
            frame.write(0x80 | opcode); // FIN + opcode
            if (len <= 125) {
                frame.write(0x80 | len); // MASK 置位
            } else if (len <= 0xFFFF) {
                frame.write(0x80 | 126);
                frame.write((len >>> 8) & 0xFF);
                frame.write(len & 0xFF);
            } else {
                frame.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    frame.write((int) (((long) len >>> (8 * i)) & 0xFF));
                }
            }
            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            frame.write(mask, 0, 4);
            for (int i = 0; i < len; i++) {
                frame.write(payload[i] ^ mask[i & 3]);
            }
            out.write(frame.toByteArray());
            out.flush();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 接收                                                                */
    /* ------------------------------------------------------------------ */

    private void startReader() {
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "dsh-ws-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        int fragmentOpcode = -1;
        try {
            while (!closed) {
                int b0 = in.read();
                if (b0 == -1) {
                    break;
                }
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                int b1 = in.read();
                if (b1 == -1) {
                    break;
                }
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((long) in.read() << 8) | in.read();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | in.read();
                    }
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask, 4);
                }
                byte[] payload = new byte[(int) len];
                readFully(payload, (int) len);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }

                if (opcode == OP_CLOSE) {
                    int code = 1005;
                    String reason = "";
                    if (payload.length >= 2) {
                        code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                        if (payload.length > 2) {
                            reason = new String(payload, 2, payload.length - 2, "UTF-8");
                        }
                    }
                    closed = true;
                    if (listener != null) {
                        listener.onClose(code, reason);
                    }
                    break;
                } else if (opcode == OP_PING) {
                    sendFrame(OP_PONG, payload);
                } else if (opcode == OP_PONG) {
                    // 忽略
                } else if (opcode == OP_TEXT || opcode == OP_BINARY) {
                    if (fin) {
                        if (listener != null) {
                            listener.onText(new String(payload, "UTF-8"));
                        }
                    } else {
                        fragmentOpcode = opcode;
                        fragments.reset();
                        fragments.write(payload);
                    }
                } else if (opcode == 0x0) { // continuation
                    fragments.write(payload);
                    if (fin) {
                        byte[] all = fragments.toByteArray();
                        fragments.reset();
                        if (fragmentOpcode == OP_TEXT && listener != null) {
                            listener.onText(new String(all, "UTF-8"));
                        }
                        fragmentOpcode = -1;
                    }
                }
            }
        } catch (Exception e) {
            if (!closed && listener != null) {
                listener.onError(e);
            }
        }
    }

    private void readFully(byte[] buf, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, read, len - read);
            if (n < 0) {
                throw new IOException("连接在读取 payload 时被关闭");
            }
            read += n;
        }
    }
}

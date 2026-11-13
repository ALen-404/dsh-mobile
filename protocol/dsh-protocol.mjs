/**
 * dsh-protocol — DSH 通信协议层
 * ============================================================================
 * 把"怎么和一台 DSH 说话"从 UI 里剥出来。对外能力：
 *
 *   1. preflight()       判断地址能不能连（网络 / 隧道 / 鉴权），不需要凭证
 *   2. exchange()        用 launch token 换签名 API Cookie（官方给程序化客户端的通道）
 *   3. callUnaryHttp()   调 unary 端点（POST /api/<ns/method>，列表/投影/发消息都走这里）
 *   4. openMux()         打开 /api/remote.mux WebSocket（**只承载 stream**）
 *      collectStream()   在 mux 上订阅一条流并收帧
 *
 * 为什么单独一层：DSH 的 RPC 不是公开契约，随 nightly 版本变化。把它隔离在一个
 * 小模块里，升级时只改这里；上层（App / 探针）通过"能力探测 + 降级"来适配。
 *
 * 证据等级标注（重要，别把推测当事实）：
 *   [VERIFIED]  实测确认（2026-11-13，DSH 0.2.2-rc.1）
 *   [DOC]       来自 DSH 源码内的文档字符串
 * 目前本模块里的事实都已达到 [VERIFIED] 或 [DOC]；尚未验证的项写在 docs/protocol.md §7。
 */

import http from 'node:http';
import https from 'node:https';
import { URL } from 'node:url';

/* ------------------------------------------------------------------ */
/* 诊断：把状态码翻译成人话                                              */
/* 文案以 protocol/java/.../Diagnostics.java 为准（同一套协议知识，      */
/* App 与探针要说同样的话）；改一边记得同步另一边。                       */
/* ------------------------------------------------------------------ */

export function explain(status) {
  switch (status) {
    case 200:
      return '正常（鉴权通过，可以继续）';
    case 401:
      return '需要鉴权（正常现象，不是坏了）。DSH 只服务带 token 的地址：'
        + '浏览器会种 cookie，程序化客户端要用 /connect?token= 换 API Cookie。';
    case 403:
      return '被拒绝。可能是配对设备未放行（插件姿态探测 fail-closed），或 Host/Origin 校验不通过。';
    case 404:
      return '路径不存在。检查地址是否多写/少写路径。';
    case 502:
      return '隧道通着，但转发到的本地端口没人监听 —— DSH 没运行，或端口变了。';
    case 530:
      return '隧道没有可用连接器 —— cloudflared / frp 这类进程没在跑。';
    case 0:
      return '连不上。检查网络互通、DSH 是否只绑了 127.0.0.1、防火墙、DNS。';
    default:
      return `HTTP ${status}`;
  }
}

/* ------------------------------------------------------------------ */
/* HTTP 小工具                                                         */
/* ------------------------------------------------------------------ */

function request(urlString, { method = 'GET', headers = {}, body, followRedirect = false, timeout = 15000 } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlString);
    const mod = u.protocol === 'https:' ? https : http;
    const payload = body === undefined ? undefined : Buffer.from(body, 'utf8');
    const finalHeaders = { ...headers };
    if (payload !== undefined) {
      finalHeaders['content-length'] = String(payload.length);
    }
    const req = mod.request(
      {
        protocol: u.protocol,
        hostname: u.hostname,
        port: u.port || (u.protocol === 'https:' ? 443 : 80),
        path: u.pathname + u.search,
        method,
        headers: finalHeaders,
        timeout,
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          resolve({
            status: res.statusCode ?? 0,
            headers: res.headers,
            body: Buffer.concat(chunks).toString('utf8'),
            location: res.headers.location,
            setCookie: res.headers['set-cookie'] ?? [],
          });
        });
      },
    );
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
    if (payload !== undefined) {
      req.write(payload);
    }
    req.end();
  });
}

/* ------------------------------------------------------------------ */
/* unary 调用：HTTP POST /api/<endpoint>                               */
/* ------------------------------------------------------------------ */

/**
 * unary Remote 方法**不走 mux WebSocket**，走 Connection 层的 HTTP RPC。
 * （实测报错 `unary Remote methods cannot be opened through the stream carrier`，
 * 源码：`Unary transport and response envelopes belong to Connection`）
 *
 * 信封（源码 clientRequestSchema / serverResponseSchema）：
 *   请求  { type:"client-request",  rpcId, method, payload }
 *         payload 必须**恰好含一个** args 字段：{ args: {...} }
 *         （服务端报 "Remote payload must contain exactly one plain-object args field"）
 *   响应  { type:"server-response", rpcId, result: { ok:true, value } | { ok:false, error } }
 *
 * @param baseUrl  实例基础地址
 * @param cookie   /connect 换到的 API Cookie（整段 Cookie 头内容）
 * @param endpoint `命名空间/方法`，例如 session/list
 * @param args     参数对象，形状由端点 descriptor 的 parameters 决定
 */
export async function callUnaryHttp(baseUrl, cookie, endpoint, args = {}, { timeout = 15000 } = {}) {
  const { randomUUID } = await import('node:crypto');
  const url = `${baseUrl.replace(/\/+$/, '')}/api/${endpoint}`;
  const rpcId = randomUUID();
  const res = await request(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', Cookie: cookie },
    body: JSON.stringify({
      type: 'client-request',
      rpcId,
      method: endpoint,
      payload: { args },
    }),
    timeout,
  });

  if (res.status !== 200) {
    throw new Error(`HTTP ${res.status}：${res.body.slice(0, 200)}`);
  }
  let envelope;
  try {
    envelope = JSON.parse(res.body);
  } catch {
    throw new Error(`响应不是 JSON：${res.body.slice(0, 200)}`);
  }
  if (envelope.type !== 'server-response') {
    throw new Error(`信封类型不对：${envelope.type}`);
  }
  const result = envelope.result;
  if (!result || result.ok !== true) {
    const err = (result && result.error) || {};
    const e = new Error(err.message || '调用失败');
    e.code = err.code;
    e.details = err.details;
    throw e;
  }
  return result;
}

/* ------------------------------------------------------------------ */
/* 1. 预检                                                             */
/* ------------------------------------------------------------------ */

/**
 * 判断一个 DSH 基础地址处在什么状态。
 * [VERIFIED] 只要 HTTP 层可达，这个函数就能给出有意义的结论：
 *   - 200 → 已经有会话（比如浏览器 cookie 复用过）
 *   - 401 → 服务在，但需要鉴权（这是 DSH 的正常状态）
 *   - 502/530 → 隧道层问题，跟 DSH 本身无关
 *   - 0   → 网络层不通
 */
export async function preflight(baseUrl) {
  const url = new URL(baseUrl).origin + '/';
  try {
    const res = await request(url);
    return { reachable: true, status: res.status, advice: explain(res.status) };
  } catch (err) {
    return { reachable: false, status: 0, advice: explain(0), error: err.message };
  }
}

/* ------------------------------------------------------------------ */
/* 2. 用 launch token 换 API Cookie                                    */
/* ------------------------------------------------------------------ */

/**
 * [DOC] GET /connect?token=<launchToken>&interactions=answer|none
 *   → 立即创建 owner 权限的 Peer
 *   → 302，携带 Mux 目的地 + 签名 API Cookie
 *
 * 关键点：这里**不要跟随跳转** —— 我们要的是 302 里的 Set-Cookie。
 *
 * @param {string} tokenUrl  dsh web 打印出来的那个带 ?token= 的完整地址
 */
export async function exchange(tokenUrl) {
  const res = await request(tokenUrl, { followRedirect: false });
  if (res.status !== 302 && res.status !== 303) {
    return {
      ok: false,
      status: res.status,
      advice:
        res.status === 401
          ? 'launch token 无效或已过期（token 每进程随机生成，重启 dsh web 后旧的会失效）'
          : explain(res.status),
    };
  }
  const cookie = res.setCookie.map((c) => c.split(';')[0]).join('; ');
  return {
    ok: true,
    status: res.status,
    muxUrl: res.location, // [DOC] Mux 目的地
    cookie,
  };
}

/* ------------------------------------------------------------------ */
/* 3. mux WebSocket                                                    */
/* ------------------------------------------------------------------ */

/**
 * 打开 /api/remote.mux（**只用于 stream**；unary 请用 {@link callUnaryHttp}）。
 *
 * [VERIFIED] 路径存在且是 WebSocket。
 * [VERIFIED] 未鉴权一律 401（连不存在的 /api/xxx 也是 401，所以不能用状态码判断路由）。
 * [VERIFIED] 帧格式见 {@link buildOpenFrame}（已从服务端源码确认并对真实实例调通）。
 *
 * 依赖可选的 `ws` 包；没有时抛错而不是静默失败。
 */
export async function openMux(muxUrl, cookie, { headers = {} } = {}) {
  let WebSocket;
  try {
    ({ WebSocket } = await import('ws'));
  } catch {
    throw new Error(
      'mux 通道需要 ws 包：npm i ws（或全局安装后 NODE_PATH 指向它）',
    );
  }
  return new Promise((resolve, reject) => {
    const sock = new WebSocket(muxUrl, {
      headers: { Cookie: cookie, ...headers },
    });
    sock.once('open', () => resolve(sock));
    sock.once('error', (e) => reject(new Error(`mux 连接失败：${e.message}`)));
  });
}

/**
 * mux 帧格式（**已从官方实现源码确认**，见 docs/protocol.md）：
 *
 *   客户端 → 主机：
 *     { type: "open",   streamId, endpoint, payload }   payload = { args }
 *     { type: "item",   streamId, value }
 *     { type: "end",    streamId }
 *     { type: "cancel", streamId }
 *
 *   主机 → 客户端（服务端用 exactKeys 严格校验，多一个字段都算非法）：
 *     { type: "item",  streamId[, value] }
 *     { type: "end",   streamId }
 *     { type: "error", streamId, error: { code, message, details } }
 *
 * 所有调用（含 unary）都走同一条流：open → 若干 item → end/error。
 */
export function buildOpenFrame(streamId, endpoint, args = {}) {
  return { type: 'open', streamId, endpoint, payload: { args } };
}

export function buildEndFrame(streamId) {
  return { type: 'end', streamId };
}

export function buildCancelFrame(streamId) {
  return { type: 'cancel', streamId };
}

/**
 * 在一次流里调用一个 unary 端点：open → 收 item 直到 end/error。
 * 返回第一项（unary 语义）以及完整帧序列。
 */
export async function callUnary(sock, endpoint, args = {}, { timeoutMs = 8000 } = {}) {
  const { randomUUID } = await import('node:crypto');
  const streamId = randomUUID();
  const frames = [];

  return await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error(`${timeoutMs}ms 内没有收到 end/error（收到 ${frames.length} 帧）`));
    }, timeoutMs);

    const onMessage = (data) => {
      let frame;
      try {
        frame = JSON.parse(String(data));
      } catch {
        return;
      }
      if (frame.streamId !== streamId) return; // 忽略其它流的帧
      frames.push(frame);
      if (frame.type === 'end') {
        cleanup();
        const first = frames.find((f) => f.type === 'item');
        resolve({ value: first ? first.value : undefined, frames, streamId });
      } else if (frame.type === 'error') {
        cleanup();
        reject(Object.assign(new Error(frame.error.message), { code: frame.error.code, details: frame.error.details }));
      }
    };
    const cleanup = () => {
      clearTimeout(timer);
      sock.off('message', onMessage);
    };

    sock.on('message', onMessage);
    sock.send(JSON.stringify(buildOpenFrame(streamId, endpoint, args)));
  });
}

/**
 * 在 mux 上打开一条 stream 并收集帧，直到 end/error 或超时。
 *
 * 用途：验证 stream 语义（M3 原生聊天的前提）。
 * 注意 `$events` 是网关内部的"应用事件流"，payload 固定为 `{ args: {} }`，
 * 不需要会话 ID 就能验证 carrier 本身是否工作。
 */
export async function collectStream(sock, endpoint, args = {}, { durationMs = 5000 } = {}) {
  const { randomUUID } = await import('node:crypto');
  const streamId = randomUUID();
  const frames = [];
  let closed = false;

  return await new Promise((resolve) => {
    const finish = () => {
      if (closed) return;
      closed = true;
      clearTimeout(timer);
      sock.off('message', onMessage);
      try {
        sock.send(JSON.stringify(buildCancelFrame(streamId)));
      } catch {
        /* ignore */
      }
      resolve({ streamId, frames });
    };

    const timer = setTimeout(finish, durationMs);

    const onMessage = (data) => {
      let frame;
      try {
        frame = JSON.parse(String(data));
      } catch {
        return;
      }
      if (frame.streamId !== streamId) return;
      frames.push(frame);
      if (frame.type === 'end' || frame.type === 'error') finish();
    };

    sock.on('message', onMessage);
    sock.send(JSON.stringify(buildOpenFrame(streamId, endpoint, args)));
  });
}

/* ------------------------------------------------------------------ */
/* 能力清单：上层据此决定"能不能开原生功能"                              */
/* ------------------------------------------------------------------ */

export const CAPABILITIES = [
  { id: 'webview', requires: 'none', note: 'A 模式：只要有地址就能用' },
  { id: 'native.sessionList', requires: 'apiCookie', note: 'B 模式：需要 launch token' },
  { id: 'native.prompt', requires: 'apiCookie', note: 'B 模式' },
  { id: 'native.events', requires: 'muxStream', note: 'B 模式 + stream 帧格式' },
];

export function capabilitiesFor({ hasApiCookie }) {
  return CAPABILITIES.map((c) => ({
    ...c,
    available: c.requires === 'none' ? true : hasApiCookie,
  }));
}

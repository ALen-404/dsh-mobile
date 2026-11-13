#!/usr/bin/env node
/**
 * M3b 探针：真实对话的事件形状
 * ============================================================================
 * 思路：先挂上 session/observeEvent，再发一条 session/prompt。
 * 即使隔离实例没有模型凭据（模型调用必然失败），**用户消息本身通常已经落成事件**，
 * 于是可以不花任何额度就看到 message/* 事件的真实形状。
 *
 * 只对隔离开发实例使用（会创建会话并发送消息）。
 *
 * 用法：
 *   node protocol/test/m3-message.mjs --token-url "http://127.0.0.1:21387/?token=xxx"
 */

import fs from 'node:fs';
import { exchange, callUnaryHttp, openMux, collectStream } from '../dsh-protocol.mjs';

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const tokenUrl = arg('token-url', null);
if (!tokenUrl) {
  console.error('缺少 --token-url');
  process.exit(2);
}
const promptText = arg('text', 'hello from dsh-mobile protocol probe');
const base = new URL(tokenUrl).origin;

function cut(v, n) {
  const s = typeof v === 'string' ? v : JSON.stringify(v);
  return s && s.length > n ? s.slice(0, n) + '…' : s;
}

const ex = await exchange(tokenUrl);
if (!ex.ok) {
  console.log('❌ 换 cookie 失败：' + ex.advice);
  process.exit(1);
}
console.log('[1/5] cookie ok');

const created = await callUnaryHttp(base, ex.cookie, 'session/create', {
  request: { cwd: 'E:\\dsh-dev-home\\ws' },
});
const sessionId = (created.value || {}).sessionId;
console.log('[2/5] 会话 ' + sessionId);

const muxUrl =
  ex.muxUrl && ex.muxUrl.startsWith('http')
    ? ex.muxUrl.replace(/^http/, 'ws')
    : base.replace(/^http/, 'ws') + '/api/remote.mux';
const sock = await openMux(muxUrl, ex.cookie);
console.log('[3/5] mux 已连接，开始订阅 observeEvent');

// 先挂上流（不 await），再发 prompt —— 否则可能错过首帧
const streamPromise = collectStream(
  sock,
  'session/observeEvent',
  { request: { address: { kind: 'session', sessionId } } },
  { durationMs: 12000 },
);

// 等一小会儿让订阅生效
await new Promise((r) => setTimeout(r, 600));

console.log('[4/5] 发送 prompt（模型可能失败，但用户消息应已落事件）');
let promptError = null;
try {
  const res = await callUnaryHttp(
    base,
    ex.cookie,
    'session/prompt',
    {
      request: {
        requestId: 'probe-' + Date.now(),
        sessionId,
        mode: 'queue',
        content: [{ type: 'text', text: promptText }],
      },
    },
    { timeout: 20000 },
  );
  console.log('  prompt -> ' + cut(res.value, 300));
} catch (e) {
  promptError = e.message + (e.code ? '（' + e.code + '）' : '');
  console.log('  prompt 出错（预期之内，无模型凭据）：' + promptError);
}

const { frames } = await streamPromise;
console.log('[5/5] 事件流收到 ' + frames.length + ' 帧');
const types = new Map();
for (const f of frames) {
  const t = (f.value && (f.value.type || f.value.event?.type)) || f.type;
  types.set(t, (types.get(t) || 0) + 1);
}
console.log('  帧类型分布:');
for (const [t, c] of types) console.log('    ' + String(c).padStart(3) + 'x  ' + t);
console.log('  前 6 帧原文:');
for (const f of frames.slice(0, 6)) console.log('    - ' + cut(f, 400));

// 再读历史，看落盘的事件
try {
  const page = await callUnaryHttp(base, ex.cookie, 'session/page', {
    request: { address: { kind: 'session', sessionId } },
  });
  const records = (page.value && page.value.records) || [];
  console.log('\n历史记录 ' + records.length + ' 条：');
  const seen = new Map();
  for (const r of records) {
    const t = (r.event && r.event.type) || r.type;
    seen.set(t, (seen.get(t) || 0) + 1);
  }
  for (const [t, c] of seen) console.log('    ' + String(c).padStart(3) + 'x  ' + t);
  const msgLike = records.find((r) => /message|prompt|user/i.test((r.event && r.event.type) || ''));
  if (msgLike) {
    console.log('\n  最像"消息"的一条事件（完整）:');
    console.log('    ' + cut(msgLike, 1600));
  }
  fs.writeFileSync(
    'E:/workspace/小宝/dsh-mobile/build/x/m3-events.json',
    JSON.stringify({ sessionId, promptError, frames, records }, null, 2),
    'utf8',
  );
  console.log('\n  原始数据已存 build/x/m3-events.json');
} catch (e) {
  console.log('  session/page 读取失败：' + e.message);
}

try {
  sock.terminate();
} catch {
  /* ignore */
}
process.exit(0);

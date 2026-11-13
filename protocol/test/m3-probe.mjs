#!/usr/bin/env node
/**
 * M3 前置探针：会话级事件流
 * ============================================================================
 * 验证原生聊天需要的东西：
 *
 *   1. session/create   —— 造一个会话
 *   2. session/state    —— 读会话状态（原生 UI 要渲染的结构）
 *   3. session/observeEvent（stream）—— 订阅这个会话的事件流，看帧形状
 *
 * 只对**隔离的开发实例**使用（tools/dev-instance.ps1 起的那个），因为它会创建会话。
 *
 * 用法：
 *   node protocol/test/m3-probe.mjs --token-url "http://127.0.0.1:21387/?token=xxx"
 *   node protocol/test/m3-probe.mjs --token-url "..." --cwd "E:\\tmp\\ws"
 */

import fs from 'node:fs';
import { exchange, callUnaryHttp, openMux, collectStream } from '../dsh-protocol.mjs';

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const tokenUrl = arg('token-url', null);
if (!tokenUrl) {
  console.error('缺少 --token-url（从 tools/dev-instance.ps1 的输出里拿）');
  process.exit(2);
}
const cwd = arg('cwd', 'E:\\dsh-dev-home\\ws');
const base = new URL(tokenUrl).origin;

function cut(v, n) {
  const s = typeof v === 'string' ? v : JSON.stringify(v);
  return s && s.length > n ? s.slice(0, n) + '…' : s;
}

try {
  fs.mkdirSync(cwd, { recursive: true });
} catch {
  /* 目录已存在或无权限都由后面的调用暴露 */
}

console.log('[1/4] 换 API Cookie');
const ex = await exchange(tokenUrl);
if (!ex.ok) {
  console.log('  ❌ ' + ex.advice);
  process.exit(1);
}
console.log('  ✅ cookie=' + ex.cookie.slice(0, 14) + '…');

console.log('[2/4] session/create（cwd=' + cwd + '）');
const created = await callUnaryHttp(base, ex.cookie, 'session/create', { request: { cwd } });
const value = created.value || {};
const sessionId = value.sessionId || value.id;
console.log('  ✅ ' + cut(value, 400));
if (!sessionId) {
  console.log('  ❌ 没拿到 sessionId，后续步骤无法进行');
  process.exit(1);
}

console.log('[3/4] session/state');
const state = await callUnaryHttp(base, ex.cookie, 'session/state', { request: { sessionId } });
const stateValue = state.value || {};
console.log('  keys: ' + Object.keys(stateValue).join(', '));
const stateStr = JSON.stringify(stateValue);
console.log('  长度: ' + stateStr.length + '  片段: ' + cut(stateStr, 500));

console.log('[4/4] 订阅 session/observeEvent（4 秒）');
const muxUrl =
  ex.muxUrl && ex.muxUrl.startsWith('http')
    ? ex.muxUrl.replace(/^http/, 'ws')
    : base.replace(/^http/, 'ws') + '/api/remote.mux';
const sock = await openMux(muxUrl, ex.cookie);
const { frames } = await collectStream(
  sock,
  'session/observeEvent',
  { request: { address: { kind: 'session', sessionId } } },
  { durationMs: 4000 },
);
console.log('  收到 ' + frames.length + ' 帧');
for (const f of frames.slice(0, 8)) {
  console.log('   - ' + cut(f, 420));
}
try {
  sock.terminate();
} catch {
  /* ignore */
}
process.exit(0);

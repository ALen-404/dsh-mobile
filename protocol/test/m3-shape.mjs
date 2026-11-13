#!/usr/bin/env node
/**
 * M3 形状探针：消息历史 + 事件词汇表
 * ============================================================================
 * session/state 只给状态摘要（cursor/active/running/queue），不含消息。
 * 原聊天要读历史、要渲染事件，所以这里回答两个问题：
 *
 *   1. 读历史用哪个端点、参数长什么样（session/page / session/projections）
 *   2. session/observeEvent 会推送哪些事件类型（从官方 descriptor 的 result schema 读）
 *
 * 只对隔离开发实例运行（会创建会话）。
 *
 * 用法：
 *   node protocol/test/m3-shape.mjs --token-url "http://127.0.0.1:21387/?token=xxx"
 */

import fs from 'node:fs';
import { exchange, callUnaryHttp } from '../dsh-protocol.mjs';

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const tokenUrl = arg('token-url', null);
if (!tokenUrl) {
  console.error('缺少 --token-url');
  process.exit(2);
}
const base = new URL(tokenUrl).origin;
const remotesPath = arg('remotes', 'E:/workspace/小宝/dsh-mobile/build/x/remotes.js');

function cut(v, n) {
  const s = typeof v === 'string' ? v : JSON.stringify(v);
  return s && s.length > n ? s.slice(0, n) + '…' : s;
}

/* ---------------- 1. 从官方 spec 里读端点形状 ---------------- */

if (fs.existsSync(remotesPath)) {
  const spec = fs.readFileSync(remotesPath, 'utf8');

  function descriptor(endpoint) {
    const i = spec.indexOf('"' + endpoint + '": {');
    if (i < 0) {
      console.log('### ' + endpoint + ' -> 未找到');
      return;
    }
    const seg = spec.slice(i, i + 9000);
    const stream = /mode: "stream"/.test(seg.slice(0, 500));
    const names = [...seg.matchAll(/name: "([^"]+)"/g)].map((m) => m[1]);
    const uniq = [...new Set(names)].slice(0, 6);
    console.log('### ' + endpoint + '（stream=' + stream + '）参数名: ' + JSON.stringify(uniq));
    const j = seg.indexOf('jsonSchema: {');
    if (j >= 0) {
      console.log('    ' + seg.slice(j, j + 700).replace(/\s+/g, ' '));
    }
  }

  console.log('=== 端点形状（来自官方 descriptor）===');
  descriptor('session/page');
  descriptor('session/projections');

  console.log('\n=== observeEvent 的结果 schema：事件类型词汇 ===');
  const i = spec.indexOf('"session/observeEvent": {');
  if (i >= 0) {
    const seg = spec.slice(i, i + 20000);
    const types = [...seg.matchAll(/const: "([a-z][a-z0-9.\-\/]{2,40})"/g)].map((m) => m[1]);
    const uniq = [...new Set(types)];
    console.log('  出现的事件类型/字面量（前 40）:');
    for (const t of uniq.slice(0, 40)) console.log('    - ' + t);
    console.log('  合计去重: ' + uniq.length);
  }
} else {
  console.log('（跳过 spec 解析：找不到 ' + remotesPath + '）');
}

/* ---------------- 2. 实测 session/page ---------------- */

console.log('\n=== 实测：建会话后读历史 ===');
const ex = await exchange(tokenUrl);
if (!ex.ok) {
  console.log('  ❌ ' + ex.advice);
  process.exit(1);
}
const created = await callUnaryHttp(base, ex.cookie, 'session/create', {
  request: { cwd: 'E:\\dsh-dev-home\\ws' },
});
const sessionId = (created.value || {}).sessionId;
console.log('  sessionId = ' + sessionId);

for (const [endpoint, args] of [
  ['session/page', { request: { address: { kind: 'session', sessionId } } }],
  ['session/projections', { request: { sessionId } }],
]) {
  try {
    const r = await callUnaryHttp(base, ex.cookie, endpoint, args);
    console.log('  ✅ ' + endpoint + ' -> ' + cut(r.value, 700));
  } catch (e) {
    console.log('  ❌ ' + endpoint + ' -> ' + e.message + (e.code ? '（' + e.code + '）' : ''));
  }
}
process.exit(0);

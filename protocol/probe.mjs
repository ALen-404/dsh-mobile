#!/usr/bin/env node
/**
 * DSH 协议探针
 * ============================================================================
 * 回答三个问题（也是本项目 M0/M2/M3 的判定工具）：
 *
 *   1. 这台 DSH 现在处于什么状态？（可达 / 需要鉴权 / 隧道坏了）
 *   2. 给定一个 launch token，unary RPC 能不能调通？
 *   3. mux 上的 stream 能不能订阅、帧长什么样？
 *
 * 用法：
 *   node probe.mjs --base http://127.0.0.1:3080
 *   node probe.mjs --token-url "http://127.0.0.1:3080/?token=xxxx"
 *   node probe.mjs --token-url "..." --method session/list --args-file ./args.json
 *   node probe.mjs --token-url "..." --stream '$events' --duration 4000
 *
 * 退出码：0 = 全部通过；1 = 有检查没通过（CI 可用）。
 *
 * 探针默认**只读**：只做 GET /connect 与只读 RPC。stream 模式会订阅事件流，
 * 收完立刻发 cancel。
 */

import fs from 'node:fs';
import { preflight, exchange, openMux, callUnaryHttp, collectStream, explain } from './dsh-protocol.mjs';

/* ---------------- 参数 ---------------- */

function usage() {
  console.log(`用法：
  node probe.mjs --base <url>                      只做预检（不需要凭证）
  node probe.mjs --token-url "<带 token 的地址>"     预检 + 换 cookie + 调一个方法 / 订阅一条流

选项：
  --method <name>        要调的 unary 方法（默认 session/list）
  --args <json>          方法参数（注意 shell 引号）
  --args-file <path>     从文件读参数（推荐）
  --stream <endpoint>    改为验证 stream（例：'$events'）
  --duration <ms>        收集流的时长（默认 5000）
  --skip-mux             跳过 WebSocket 连接检查
  --json                 以 JSON 输出
  --help                 显示本帮助

已知端点参数形状（来自官方 descriptor）：
  session/list          payload.args = { "_request": {} }
  session/modelCatalog  payload.args = {}
  $events（stream）      payload.args = {}`);
}

function parseArgs(argv) {
  const out = {
    base: null,
    tokenUrl: null,
    json: false,
    method: 'session/list',
    args: '{"_request":{}}',
    argsFile: null,
    stream: null,
    duration: 5000,
    skipMux: false,
    timeoutMs: 8000,
  };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--base') out.base = argv[++i];
    else if (a === '--token-url') out.tokenUrl = argv[++i];
    else if (a === '--method') out.method = argv[++i];
    else if (a === '--args') out.args = argv[++i];
    else if (a === '--args-file') out.argsFile = argv[++i];
    else if (a === '--stream') out.stream = argv[++i];
    else if (a === '--duration') out.duration = Number(argv[++i]);
    else if (a === '--skip-mux') out.skipMux = true;
    else if (a === '--json') out.json = true;
    else if (a === '--help' || a === '-h') {
      usage();
      process.exit(0);
    }
  }
  return out;
}

const args = parseArgs(process.argv);

if (!args.base && !args.tokenUrl) {
  console.error('缺少 --base 或 --token-url，见 --help');
  process.exit(2);
}

const baseUrl = args.base || new URL(args.tokenUrl).origin;
const report = { base: baseUrl, steps: [], ok: true };

function step(name, ok, detail) {
  report.steps.push({ name, ok, detail });
  if (!ok) report.ok = false;
  if (!args.json) {
    console.log(`${ok ? '✅' : '❌'} ${name}`);
    if (detail) {
      for (const line of String(detail).split('\n')) console.log(`   ${line}`);
    }
  }
}

function truncate(s, n) {
  const str = typeof s === 'string' ? s : JSON.stringify(s);
  if (str === undefined) return String(s);
  return str.length > n ? str.slice(0, n) + '…' : str;
}

/* ---------------- 步骤 1：预检 ---------------- */

const pre = await preflight(baseUrl);
step(
  '预检（HTTP 可达性 + 鉴权状态）',
  pre.reachable && (pre.status === 200 || pre.status === 401),
  `status=${pre.status}  ${pre.advice}`,
);

if (!pre.reachable) {
  report.ok = false;
  if (args.json) console.log(JSON.stringify(report, null, 2));
  process.exit(1);
}

/* ---------------- 步骤 2：凭证 ---------------- */

if (!args.tokenUrl) {
  step(
    '换成 API Cookie',
    false,
    '未提供 --token-url。\n' +
      'launch token 每进程随机生成、不落盘，只能从 dsh 的 stdout 里拿：\n' +
      '  dsh --profile <名字> --no-open --host 0.0.0.0 --port 3080\n' +
      '终端会打印 "dsh web: http://...?token=..."。\n' +
      '没有它就只能用 A 模式（WebView 承载官方 UI）。',
  );
  if (args.json) console.log(JSON.stringify(report, null, 2));
  process.exit(1);
}

const ex = await exchange(args.tokenUrl);
step(
  '换成 API Cookie（GET /connect?token=）',
  ex.ok,
  ex.ok
    ? `拿到 cookie：${ex.cookie.slice(0, 24)}…\n   Mux 目的地：${ex.muxUrl}`
    : `status=${ex.status}  ${ex.advice}`,
);

if (!ex.ok) {
  if (args.json) console.log(JSON.stringify(report, null, 2));
  process.exit(1);
}

/* ---------------- 步骤 3：解析参数 ---------------- */

let callArgs;
try {
  callArgs = args.argsFile ? JSON.parse(fs.readFileSync(args.argsFile, 'utf8')) : JSON.parse(args.args);
} catch (err) {
  step('解析方法参数', false, `--args/--args-file 不是合法 JSON：${err.message}`);
  if (args.json) console.log(JSON.stringify(report, null, 2));
  process.exit(1);
}

/* ---------------- 步骤 4：mux（stream 通道）---------------- */

let muxSock = null;
if (args.skipMux) {
  step('连接 mux WebSocket', true, '已跳过（--skip-mux）');
} else {
  const muxUrl =
    ex.muxUrl && ex.muxUrl.startsWith('http')
      ? ex.muxUrl.replace(/^http/, 'ws')
      : `${baseUrl.replace(/^http/, 'ws')}/api/remote.mux`;
  try {
    muxSock = await openMux(muxUrl, ex.cookie);
    step('连接 mux WebSocket（承载 stream）', true, muxUrl);
  } catch (err) {
    step('连接 mux WebSocket（承载 stream）', false, `${err.message}`);
  }
}

/* ---------------- 步骤 5a：stream 验证 ---------------- */

if (args.stream) {
  if (!muxSock) {
    step(`订阅 stream ${args.stream}`, false, 'mux 未连接，无法订阅');
  } else {
    try {
      const { frames } = await collectStream(muxSock, args.stream, callArgs, {
        durationMs: args.duration,
      });
      const kinds = frames.map((f) => f.type).join(',') || '(无帧)';
      const sample = frames
        .slice(0, 3)
        .map((f) => truncate(f, 240))
        .join('\n');
      step(
        `订阅 stream ${args.stream}（${args.duration}ms）`,
        frames.length > 0,
        `收到 ${frames.length} 帧：${kinds}\n${sample}`,
      );
    } catch (err) {
      step(`订阅 stream ${args.stream}`, false, err.message);
    }
  }
} else {
  /* ---------------- 步骤 5b：unary 调用 ---------------- */

  try {
    const result = await callUnaryHttp(baseUrl, ex.cookie, args.method, callArgs);
    const value = result.value === undefined ? result : result.value;
    step(`调用 ${args.method}（HTTP unary）`, true, `返回：${truncate(value, 900)}`);
  } catch (err) {
    step(
      `调用 ${args.method}（HTTP unary）`,
      false,
      `${err.message}${err.code ? `\n   code=${err.code}` : ''}\n` +
        `参数形状不对时服务端回报 gateway/arguments-invalid —— 按端点 descriptor 的 parameters 填。\n` +
        `stream 端点（descriptor.mode === "stream"）不能走 HTTP，请用 --stream。`,
    );
  }
}

try {
  if (muxSock) muxSock.terminate();
} catch {
  /* ignore */
}

if (args.json) {
  console.log(JSON.stringify(report, null, 2));
} else {
  console.log('');
  console.log(report.ok ? '== 全部通过 ==' : '== 有步骤失败（见上）==');
}
process.exit(report.ok ? 0 : 1);

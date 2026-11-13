#!/usr/bin/env node
/**
 * 最小 WebSocket 回显服务器 —— 只给 WsSelfTest 用。
 *
 * 存在的意义：手写的 RFC 6455 客户端必须对着一个"标准实现"验证，
 * 否则握手 / 掩码 / 分片 / ping-pong 的错误只会在真实环境里变成
 * "连上了但没反应"，极难定位。
 *
 * 用法：
 *   node protocol/test/ws-echo-server.mjs [port]
 *
 * 行为：
 *   - 收到文本帧 → 回 "echo:<原文>"
 *   - 收到 ping   → 由 ws 库自动回 pong
 */

import { createServer } from 'node:http';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

/** ws 是可选依赖；本仓库不保证它已被安装，所以给出可读的失败信息。 */
function loadWs() {
  const candidates = [
    'ws',
    // 本机开发时的兜底路径（仓库外已有 ws 的情况）
    'E:/workspace/小宝/_recon/clientsmoke/node_modules/ws',
  ];
  for (const c of candidates) {
    try {
      return require(c);
    } catch {
      /* try next */
    }
  }
  console.error('找不到 ws 包。请在 protocol/ 下执行： npm i ws');
  process.exit(2);
}

const { WebSocketServer } = loadWs();
const port = Number(process.argv[2] || 19399);

const httpServer = createServer((req, res) => {
  res.writeHead(426, { 'content-type': 'text/plain' });
  res.end('WebSocket only\n');
});

const wss = new WebSocketServer({ server: httpServer });

wss.on('connection', (socket) => {
  console.log('client connected');
  socket.on('message', (data, isBinary) => {
    if (isBinary) {
      return;
    }
    const text = data.toString('utf8');
    console.log(`  <- ${text}`);
    const reply = `echo:${text}`;
    socket.send(reply);
    console.log(`  -> ${reply}`);
  });
  socket.on('close', () => console.log('client closed'));
});

httpServer.listen(port, '127.0.0.1', () => {
  console.log(`ws echo server listening on ws://127.0.0.1:${port}/`);
});

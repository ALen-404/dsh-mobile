#!/usr/bin/env node
/**
 * 从 DSH 产物里数出真实的 RPC 方法清单。
 * ============================================================================
 * 文档里"约 260 个方法、session 32 个"这类数字如果是手写的，DSH 升级后就会失真。
 * 这个脚本直接从 app.asar 里的 typert spec 提取，随时可以重算。
 *
 * 用法：
 *   node protocol/test/dsh-methods.mjs                       # 用默认的 Windows 安装路径
 *   node protocol/test/dsh-methods.mjs --asar "D:\x\app.asar"
 *   node protocol/test/dsh-methods.mjs --json                # 机器可读
 *
 * 只读：只解析 asar 头部和其中一个 JS 文件，不执行任何产物代码。
 */

import fs from 'node:fs';

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const asarPath = arg('asar', 'D:\\new dsh\\resources\\app.asar');
const asJson = process.argv.includes('--json');

/* ---------------- asar 解析（只读文件索引） ---------------- */

function readAsarEntry(asar, entryPath) {
  const fd = fs.openSync(asar, 'r');
  try {
    const head = Buffer.alloc(16);
    fs.readSync(fd, head, 0, 16, 0);
    const jsonLen = head.readUInt32LE(12);
    const headerBuf = Buffer.alloc(jsonLen);
    fs.readSync(fd, headerBuf, 0, jsonLen, 16);
    const header = JSON.parse(headerBuf.toString('utf8'));
    let dataStart = 16 + jsonLen;
    while (dataStart % 4 !== 0) {
      dataStart++;
    }
    let node = header;
    for (const part of entryPath.split('/')) {
      if (!node.files || !node.files[part]) {
        return null;
      }
      node = node.files[part];
    }
    // 注意：asar header 里的 size/offset 可能是**字符串**（大数值会被序列化成字符串），
    // 直接 Number.isFinite() 会判 false —— 必须先 Number()。
    const offset = Number(node.offset);
    const size = Number(node.size);
    if (!Number.isFinite(offset) || !Number.isFinite(size)) {
      return null;
    }
    const buf = Buffer.alloc(size);
    fs.readSync(fd, buf, 0, buf.length, dataStart + offset);
    return buf;
  } finally {
    fs.closeSync(fd);
  }
}

if (!fs.existsSync(asarPath)) {
  console.error('找不到 app.asar：' + asarPath);
  console.error('用 --asar 指定 DSH 安装目录下的 resources/app.asar');
  process.exit(2);
}

const specPath = 'dsh/node_modules/@deepseek-ai/dsh-api-remotes/lib/client.js';
const buf = readAsarEntry(asarPath, specPath);
if (!buf) {
  console.error('asar 里没有找到 ' + specPath + '（DSH 版本可能变了）');
  process.exit(2);
}
const spec = buf.toString('utf8');

/* ---------------- 提取端点 ---------------- */

// 端点形如： "session/list": {
const endpointRe = /"([a-z][a-z0-9-]*\/[a-zA-Z][a-zA-Z0-9-]*)"\s*:\s*\{/g;
const found = new Map();
let m;
while ((m = endpointRe.exec(spec)) !== null) {
  const endpoint = m[1];
  const ns = endpoint.slice(0, endpoint.indexOf('/'));
  found.set(endpoint, ns);
}

const byNamespace = new Map();
for (const [endpoint, ns] of found) {
  if (!byNamespace.has(ns)) {
    byNamespace.set(ns, []);
  }
  byNamespace.get(ns).push(endpoint.slice(ns.length + 1));
}
const namespaces = [...byNamespace.entries()]
  .sort((a, b) => b[1].length - a[1].length)
  .map(([ns, methods]) => ({ namespace: ns, count: methods.length, methods: methods.sort() }));

const total = found.size;

if (asJson) {
  console.log(JSON.stringify({ asar: asarPath, total, namespaces }, null, 2));
  process.exit(0);
}

console.log('DSH 产物: ' + asarPath);
console.log('RPC 端点总数: ' + total);
console.log('命名空间数: ' + namespaces.length);
console.log('');
console.log('按数量排序：');
for (const ns of namespaces) {
  console.log('  ' + String(ns.count).padStart(3) + '  ' + ns.namespace);
}
console.log('');
console.log('session 命名空间的方法：');
const session = namespaces.find((n) => n.namespace === 'session');
if (session) {
  for (const method of session.methods) {
    console.log('  session/' + method);
  }
}

#!/usr/bin/env node
/**
 * CamPush LAN 播放页自检（Node 18+，零依赖）
 * 1. 结构检查：单文件、≤64 KB、无外链、CSP、无 localStorage、关键 UI 文案
 * 2. 从 player.html 的 /*TESTABLE-START* / *END* 标记区提取纯函数，用 new Function 装载后做单测
 * 3. 对整段内联脚本与提取脚本执行 `node --check` 语法校验
 *
 * 用法：node tools/player-selftest/run.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const PLAYER = path.join(ROOT, 'lan-core', 'src', 'main', 'resources', 'com', 'campush', 'lan', 'player.html');
const MAX_BYTES = 65536;

let passed = 0;
const failures = [];
function ok(name) {
  passed++;
  console.log(`PASS  ${name}`);
}
function check(name, fn) {
  try {
    fn();
    ok(name);
  } catch (err) {
    failures.push(`${name}: ${err && err.message ? err.message : err}`);
    console.log(`FAIL  ${name}\n      -> ${err && err.message ? err.message : err}`);
  }
}
function assert(cond, msg) {
  if (!cond) throw new Error(msg || 'assertion failed');
}
function eq(actual, expected, msg) {
  if (actual !== expected) throw new Error(`${msg || 'value'}: expected ${expected}, got ${actual}`);
}
function deepBytes(actual, expected, msg) {
  assert(actual && actual.length === expected.length, `${msg || 'bytes'}: length ${actual && actual.length} != ${expected.length}`);
  for (let i = 0; i < expected.length; i++) {
    if (actual[i] !== expected[i]) throw new Error(`${msg || 'bytes'}: byte[${i}] ${actual[i]} != ${expected[i]}`);
  }
}

console.log('=== CamPush LAN player.html 自检 ===');
if (!fs.existsSync(PLAYER)) {
  console.error(`FAIL  找不到文件: ${PLAYER}`);
  process.exit(1);
}
const buffer = fs.readFileSync(PLAYER);
const html = buffer.toString('utf8');
console.log(`文件: ${path.relative(ROOT, PLAYER)}`);
console.log(`字节数: ${buffer.length}（上限 ${MAX_BYTES}）`);

function sliceBetween(text, a, b) {
  const i = text.indexOf(a);
  const j = text.indexOf(b);
  if (i < 0 || j < 0 || j <= i) return null;
  return text.slice(i + a.length, j);
}
const START = '/*TESTABLE-START*/';
const END = '/*TESTABLE-END*/';
const testable = sliceBetween(html, START, END);
const inlineScript = sliceBetween(html, '<script>', '</script>');

// ---------- 1. 结构与安全 ----------
check('文件大小 ≤64KB', () => assert(buffer.length <= MAX_BYTES && buffer.length > 1024, `size=${buffer.length}`));
check('包含 TESTABLE 标记且唯一', () => {
  assert(html.includes(START), '缺少 TESTABLE-START');
  assert(html.includes(END), '缺少 TESTABLE-END');
  eq(html.split(START).length - 1, 1, 'START 出现次数');
  eq(html.split(END).length - 1, 1, 'END 出现次数');
  assert(testable && testable.includes('parseFlvHeader'), '标记区未包含纯函数');
});
check('无外部资源引用', () => {
  const patterns = [
    [/https?:\/\//i, '出现 http(s):// 外链'],
    [/<script[^>]+src\s*=/i, '存在外链 script'],
    [/<link\b/i, '存在 link 标签'],
    [/@import/i, '存在 @import'],
    [/url\(\s*['"]?(?:https?:)?\/\//i, 'CSS 存在外链 url()'],
    [/<iframe\b/i, '存在 iframe'],
    [/<img[^>]+src\s*=\s*["'](?:https?:)?\/\//i, '存在外链图片']
  ];
  for (const [re, msg] of patterns) {
    assert(!re.test(html), msg);
  }
});
check('CSP meta 禁止外链脚本', () => {
  assert(/http-equiv=["']?Content-Security-Policy["']?/i.test(html), '缺少 CSP meta');
  assert(/default-src\s+'none'/i.test(html), 'CSP 缺少 default-src none');
  assert(/script-src\s+'unsafe-inline'/i.test(html), 'CSP 未限定 inline 脚本');
  assert(!/script-src[^;]*https?:/i.test(html), 'CSP 允许外部脚本源');
});
check('不持久化 token', () => {
  assert(!/localStorage/i.test(html), '出现 localStorage');
  assert(!/sessionStorage/i.test(html), '出现 sessionStorage');
  assert(!/document\.cookie/i.test(html), '出现 document.cookie');
});
check('安全基线：no eval / textContent', () => {
  assert(!/\beval\s*\(/.test(html), '出现 eval(');
  assert(!/\.innerHTML\s*=/.test(html), '出现 innerHTML 赋值');
  assert(!/new Function\s*\(/.test(html), '播放页不得使用 new Function');
});
check('关键 UI 文案与控件存在', () => {
  const must = ['仅同一局域网可观看', '需要观看口令', '直播已结束', '重试', '点击开启声音',
    'AP 隔离', '观看人数已满', '口令错误'];
  for (const s of must) assert(html.includes(s), `缺少文案: ${s}`);
  assert(/<video[^>]+playsinline/i.test(html), 'video 缺 playsinline');
  assert(/<video[^>]+muted/i.test(html), 'video 缺默认静音');
  assert(/id="pin"/.test(html), '缺少 PIN 输入');
  assert(/X-Page-Token/.test(html), '缺少 X-Page-Token 调用');
  assert(/\/api\/v1\/status/.test(html), '缺少 status 调用');
  assert(/\/api\/v1\/play-session/.test(html), '缺少 play-session 调用');
  assert(/\/live\.flv/.test(html), '缺少 /live.flv');
  assert(/\/stream\.mjpg/.test(html), '缺少 /stream.mjpg');
});
check('内联脚本可编译（语法）', () => {
  assert(inlineScript && inlineScript.length > 1000, '未找到内联脚本');
  new Function(inlineScript); // 仅编译，不执行
});
check('提取脚本 node --check 通过', () => {
  assert(testable && testable.length > 1000, '标记区太小');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'campush-player-'));
  const tmp = path.join(dir, 'extracted.js');
  fs.writeFileSync(tmp, `'use strict';\n${testable}\n`, 'utf8');
  execFileSync(process.execPath, ['--check', tmp], { stdio: 'pipe' });
  fs.rmSync(dir, { recursive: true, force: true });
});
check('整体内联脚本 node --check 通过', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'campush-inline-'));
  const tmp = path.join(dir, 'inline.js');
  fs.writeFileSync(tmp, inlineScript, 'utf8');
  execFileSync(process.execPath, ['--check', tmp], { stdio: 'pipe' });
  fs.rmSync(dir, { recursive: true, force: true });
});

// ---------- 2. 装载纯函数 ----------
const exportNames = [
  'concatAll', 'asciiBytes', 'u16be', 'u32be', 'u64be', 'zeros', 'mp4box', 'fullbox',
  'parsePathToken', 'parseFlvHeader', 'parseFlvTag', 'parseVideoTag', 'parseAudioTag',
  'parseAacAsc', 'AAC_RATES', 'parseAvcConfigRecord', 'buildAvcC', 'hex2', 'codecStringFromAvcC',
  'parseAvcNalus', 'parseSpsSize', 'fixTimestamp', 'buildFtyp', 'buildMoov', 'buildInitSegment',
  'makeTrun', 'buildMediaSegment'
];
let api;
check('装载 TESTABLE 纯函数', () => {
  const factory = new Function(`${testable}\n'use strict';\nreturn {${exportNames.join(',')}};`);
  api = factory();
  for (const n of exportNames) assert(api[n] !== undefined, `缺少导出 ${n}`);
});
if (!api) {
  console.error('\n无法装载纯函数，终止。');
  process.exit(1);
}
const {
  parsePathToken, parseFlvHeader, parseFlvTag, parseVideoTag, parseAudioTag, parseAacAsc,
  parseAvcConfigRecord, buildAvcC, codecStringFromAvcC, parseAvcNalus, parseSpsSize, fixTimestamp,
  buildInitSegment, buildMediaSegment, u32be
} = api;

// 测试用样本
const SPS = Uint8Array.from([0x67, 0x42, 0xC0, 0x1E, 0x8C, 0x68]);
const PPS = Uint8Array.from([0x68, 0xCE, 0x06, 0xE2]);
const VIDEO_CFG = {
  profile: 0x42, compat: 0xC0, level: 0x1E, lengthSize: 4,
  sps: [SPS], pps: [PPS], width: 1280, height: 720
};
const AUDIO_CFG = { sampleRate: 48000, channels: 1, asc: Uint8Array.from([0x11, 0x88]) }; // AAC-LC 48k mono

function avcRecordBytes(cfg, opts = {}) {
  const sps = cfg.sps[0], pps = cfg.pps[0];
  const numSps = opts.numSps === undefined ? 1 : opts.numSps;
  const numPps = opts.numPps === undefined ? 1 : opts.numPps;
  const head = Uint8Array.from([1, cfg.profile, cfg.compat, cfg.level, 0xFC | ((cfg.lengthSize || 4) - 1), 0xE0 | numSps]);
  const parts = [head];
  for (let i = 0; i < numSps; i++) parts.push(Uint8Array.from([0, sps.length]), sps);
  parts.push(Uint8Array.from([numPps]));
  for (let i = 0; i < numPps; i++) parts.push(Uint8Array.from([0, pps.length]), pps);
  let total = 0;
  for (const p of parts) total += p.length;
  const out = new Uint8Array(total);
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}
function flvTag(type, ts, data) {
  return Uint8Array.from([
    type,
    (data.length >> 16) & 0xFF, (data.length >> 8) & 0xFF, data.length & 0xFF,
    (ts >> 16) & 0xFF, (ts >> 8) & 0xFF, ts & 0xFF, (ts >>> 24) & 0xFF,
    0, 0, 0,
    ...data,
    ((11 + data.length) >>> 24) & 0xFF, ((11 + data.length) >> 16) & 0xFF,
    ((11 + data.length) >> 8) & 0xFF, (11 + data.length) & 0xFF
  ]);
}
// 极简 box 扫描器（测试侧实现，不依赖页面代码）
function u32(buf, off) {
  return (((buf[off] << 24) >>> 0) + ((buf[off + 1] << 16) | (buf[off + 2] << 8) | buf[off + 3])) >>> 0;
}
function fourcc(buf, off) {
  return String.fromCharCode(buf[off], buf[off + 1], buf[off + 2], buf[off + 3]);
}
function findBox(buf, start, end, type) {
  let p = start;
  while (p + 8 <= end) {
    const size = u32(buf, p);
    const t = fourcc(buf, p + 4);
    if (size < 8 || p + size > end) return null;
    if (t === type) return { type: t, start: p, end: p + size, size };
    p += size;
  }
  return null;
}
function collectBoxes(buf, start, end, type) {
  const out = [];
  let p = start;
  while (p + 8 <= end) {
    const size = u32(buf, p);
    const t = fourcc(buf, p + 4);
    if (size < 8 || p + size > end) break;
    if (t === type) out.push({ type: t, start: p, end: p + size, size });
    p += size;
  }
  return out;
}
function topBoxes(buf) { return collectBoxes(buf, 0, buf.length, null).filter(() => true); }
function topList(buf) {
  const out = [];
  let p = 0;
  while (p + 8 <= buf.length) {
    const size = u32(buf, p);
    if (size < 8 || p + size > buf.length) break;
    out.push({ type: fourcc(buf, p + 4), start: p, end: p + size, size });
    p += size;
  }
  return out;
}
function firstChild(buf, box, type) { return findBox(buf, box.start + 8, box.end, type); }

// ---------- 3. URL / FLV 解析 ----------
check('parsePathToken: /s/{pageToken}', () => {
  eq(parsePathToken('/s/eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJyN2sybTl4NHEifQ.sig'), 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJyN2sybTl4NHEifQ.sig', 'JWT 路径');
  eq(parsePathToken('/s/abc_123-XYZ'), 'abc_123-XYZ', '合法 token');
  eq(parsePathToken('/s/'), null, '空 token');
  eq(parsePathToken('/x/abc'), null, '错误前缀');
  eq(parsePathToken('/s/a/b'), null, '多余路径段');
  eq(parsePathToken('/s/a b'), null, '含空格');
  eq(parsePathToken(null), null, 'null');
  eq(parsePathToken(42), null, '非字符串');
});
check('parseFlvHeader 合法/非法', () => {
  const good = Uint8Array.from([0x46, 0x4C, 0x56, 1, 0x05, 0, 0, 0, 9]);
  const h = parseFlvHeader(good);
  assert(h, '合法头返回 null');
  eq(h.version, 1, 'version');
  eq(h.hasAudio, true, 'hasAudio');
  eq(h.hasVideo, true, 'hasVideo');
  eq(h.dataOffset, 9, 'dataOffset');
  eq(parseFlvHeader(Uint8Array.from([0, 0, 0, 1, 0, 0, 0, 0, 9])), null, '错误签名');
  eq(parseFlvHeader(good.slice(0, 5)), null, '长度不足');
  eq(parseFlvHeader(Uint8Array.from([0x46, 0x4C, 0x56, 2, 0, 0, 0, 0, 9])), null, '错误版本');
  eq(parseFlvHeader(Uint8Array.from([0x46, 0x4C, 0x56, 1, 0, 0, 0, 0, 3])), null, 'dataOffset<9');
});
check('parseFlvTag: 视频 tag 时间戳/边界', () => {
  const data = Uint8Array.from([0x17, 0x01, 0, 0, 0, 1, 2, 3, 4]);
  const buf = flvTag(9, 0x01010203, data);
  const tag = parseFlvTag(buf, 0);
  assert(tag, '返回 null');
  eq(tag.type, 9, 'type');
  eq(tag.dataSize, data.length, 'dataSize');
  eq(tag.timestampMs, 0x01010203 >>> 0, 'timestamp 24+8 位');
  eq(tag.nextOffset, 11 + data.length + 4, 'nextOffset');
  eq(parseFlvTag(buf.slice(0, 5), 0), null, '截断 tag 返回 null');
  eq(parseFlvTag(buf, -1), null, '负偏移返回 null');
  eq(parseFlvTag(null, 0), null, 'null 输入');
});
check('parseVideoTag: AVC 序列头 / NALU / 非 AVC', () => {
  const seq = Uint8Array.from([0x17, 0x00, 0, 0, 0, 0x01, 0x42]);
  const v = parseVideoTag(seq, 0, seq.length);
  eq(v.frameType, 1, 'frameType 关键帧');
  eq(v.codecId, 7, 'codecId AVC');
  eq(v.avcPacketType, 0, 'AVCPacketType 序列头');
  eq(v.cts, 0, 'cts');
  const nalu = Uint8Array.from([0x27, 0x01, 0, 0, 0, 0, 0, 0, 2, 0xAA, 0xBB]);
  const n = parseVideoTag(nalu, 0, nalu.length);
  eq(n.frameType, 2, 'frameType 非关键帧');
  eq(n.avcPacketType, 1, 'AVCPacketType NALU');
  eq(n.payload.length, 6, 'NALU 负载长度');
  const other = Uint8Array.from([0x12, 0x00, 0, 0, 0]);
  eq(parseVideoTag(other, 0, other.length).codecId, 2, '非 AVC codecId');
  eq(parseVideoTag(Uint8Array.from([0x17]), 0, 1), null, 'AVC 头不完整');
});
check('parseAudioTag + parseAacAsc', () => {
  const asc = Uint8Array.from([0x11, 0x88]);
  const hdr = parseAudioTag(Uint8Array.from([0xAF, 0x00]), 0, 2);
  eq(hdr.soundFormat, 10, 'AAC SoundFormat');
  eq(hdr.aacPacketType, 0, 'AACPacketType 序列头');
  const parsed = parseAacAsc(asc);
  assert(parsed, 'ASC 解析失败');
  eq(parsed.objectType, 2, 'AAC-LC objectType');
  eq(parsed.sampleRate, 48000, '采样率');
  eq(parsed.channels, 1, '声道数');
  deepBytes(parsed.asc, asc, 'ASC 原样保留');
  eq(parseAacAsc(Uint8Array.from([0x11])), null, 'ASC 过短');
  eq(parseAacAsc(Uint8Array.from([0x08, 0x00])), null, '非 AAC-LC');
});
check('parseAvcConfigRecord: 合法/非法', () => {
  const rec = avcRecordBytes(VIDEO_CFG);
  const cfg = parseAvcConfigRecord(rec);
  assert(cfg, '合法 avcC 返回 null');
  eq(cfg.profile, 0x42, 'profile');
  eq(cfg.compat, 0xC0, 'compat');
  eq(cfg.level, 0x1E, 'level');
  eq(cfg.lengthSize, 4, 'lengthSize');
  eq(cfg.sps.length, 1, 'SPS 数');
  eq(cfg.pps.length, 1, 'PPS 数');
  deepBytes(cfg.sps[0], SPS, 'SPS 字节');
  eq(parseAvcConfigRecord(avcRecordBytes(VIDEO_CFG, { numSps: 0 })), null, 'SPS 数为 0');
  eq(parseAvcConfigRecord(avcRecordBytes(VIDEO_CFG, { numPps: 0 })), null, 'PPS 数为 0');
  eq(parseAvcConfigRecord(Uint8Array.from([1])), null, '过短');
  eq(parseAvcConfigRecord(rec.slice(0, 9)), null, '截断');
  eq(parseAvcConfigRecord(null), null, 'null');
});
check('buildAvcC 往返 + codec string', () => {
  const cfg = parseAvcConfigRecord(avcRecordBytes(VIDEO_CFG));
  const built = buildAvcC(cfg);
  assert(built, 'buildAvcC 返回 null');
  deepBytes(built, avcRecordBytes(VIDEO_CFG), 'avcC 字节一致');
  eq(codecStringFromAvcC(cfg), 'avc1.42C01E', 'codec string');
  eq(buildAvcC({ sps: [], pps: [] }), null, '空 SPS 返回 null');
  eq(buildAvcC(null), null, 'null 输入');
});
check('parseAvcNalus: 长度前缀拆分/非法长度', () => {
  const payload = Uint8Array.from([0, 0, 0, 2, 0x65, 0x88, 0, 0, 0, 1, 0x06]);
  const nalus = parseAvcNalus(payload, 4);
  eq(nalus.length, 2, 'NALU 数量');
  deepBytes(nalus[0], Uint8Array.from([0x65, 0x88]), 'NALU1');
  deepBytes(nalus[1], Uint8Array.from([0x06]), 'NALU2');
  eq(parseAvcNalus(Uint8Array.from([0, 0, 0, 9, 1]), 4).length, 0, '长度越界返回空');
  eq(parseAvcNalus(payload, 3).length, 0, '非法 lengthSize');
  eq(parseAvcNalus(null, 4).length, 0, 'null');
});
check('parseSpsSize: 由 SPS 推导分辨率', () => {
  const sps320 = Uint8Array.from([0x67,0x42,0xC0,0x0C,0x8C,0x68,0x14,0x1F,0x26,0xE0,0x21,0xA0,0x20,0xF0,0x88,0x46,0xA0,0x01]);
  const s320 = parseSpsSize(sps320);
  assert(s320, '320x240 SPS 解析失败');
  eq(s320.width, 320, '320 宽');
  eq(s320.height, 240, '240 高');
  const sps720 = Uint8Array.from([0x67,0x42,0xC0,0x1F,0x8C,0x68,0x05,0x00,0x5B,0xA6,0xE0,0x21,0xA0,0x20,0xF0,0x88,0x46,0xA0,0x01]);
  const s720 = parseSpsSize(sps720);
  assert(s720, '720p SPS 解析失败');
  eq(s720.width, 1280, '720p 宽');
  eq(s720.height, 720, '720p 高');
  eq(parseSpsSize(null), null, 'null SPS');
  eq(parseSpsSize(Uint8Array.from([0x67])), null, '过短 SPS');
});
check('fixTimestamp 单调修正', () => {
  eq(fixTimestamp(0, 0), 0, '0/0');
  eq(fixTimestamp(1000, 900), 1000, '递增');
  eq(fixTimestamp(900, 1000), 1001, '回退时 +1');
  eq(fixTimestamp(-5, 10), 11, '负数按回退+1 修正');
  eq(fixTimestamp(NaN, 7), 7, 'NaN');
});

// ---------- 4. fMP4 结构 ----------
check('buildInitSegment: ftyp/moov/avc1/avcC/mp4a/esds/mvex', () => {
  const init = buildInitSegment({ video: VIDEO_CFG, audio: AUDIO_CFG });
  assert(init, 'init segment 为 null');
  const tops = topList(init);
  eq(tops.length, 2, '顶层 box 数');
  eq(tops[0].type, 'ftyp', '第一个 box');
  eq(tops[1].type, 'moov', '第二个 box');
  eq(tops[0].end, tops[1].start, 'ftyp/moov 连续');
  const moov = tops[1];
  const mvhd = firstChild(init, moov, 'mvhd');
  assert(mvhd, '缺少 mvhd');
  eq(u32(init, mvhd.start + 20), 1000, 'mvhd timescale=1000');
  const traks = collectBoxes(init, moov.start + 8, moov.end, 'trak');
  eq(traks.length, 2, '两条 trak（视频+音频）');
  const via = firstChild(init, traks[0], 'mdia');
  const vminf = firstChild(init, via, 'minf');
  const vstbl = firstChild(init, vminf, 'stbl');
  const vstsd = firstChild(init, vstbl, 'stsd');
  const avc1 = findBox(init, vstsd.start + 16, vstsd.end, 'avc1');
  assert(avc1, '缺少 avc1 sample entry');
  const avcC = findBox(init, avc1.start + 8 + 78, avc1.end, 'avcC');
  assert(avcC, '缺少 avcC');
  deepBytes(init.slice(avcC.start + 8, avcC.end), buildAvcC(parseAvcConfigRecord(avcRecordBytes(VIDEO_CFG))), 'avcC 内容');
  const aMdia = firstChild(init, traks[1], 'mdia');
  const aMdhd = firstChild(init, aMdia, 'mdhd');
  eq(u32(init, aMdhd.start + 20), 48000, '音频 mdhd timescale=48000');
  const aMinf = firstChild(init, aMdia, 'minf');
  const aStbl = firstChild(init, aMinf, 'stbl');
  const aStsd = firstChild(init, aStbl, 'stsd');
  const mp4a = findBox(init, aStsd.start + 16, aStsd.end, 'mp4a');
  assert(mp4a, '缺少 mp4a sample entry');
  const esds = findBox(init, mp4a.start + 8 + 28, mp4a.end, 'esds');
  assert(esds, '缺少 esds');
  assert(init.slice(esds.start, esds.end).includes(0x40), 'esds 缺少 AAC objectTypeIndication 0x40');
  const mvex = firstChild(init, moov, 'mvex');
  assert(mvex, '缺少 mvex');
  eq(collectBoxes(init, mvex.start + 8, mvex.end, 'trex').length, 2, 'trex 数量');
});
check('buildInitSegment: 无音频时仅视频轨（无 esds）', () => {
  const init = buildInitSegment({ video: VIDEO_CFG, audio: null });
  assert(init, 'init 为 null');
  const moov = topList(init)[1];
  eq(collectBoxes(init, moov.start + 8, moov.end, 'trak').length, 1, '仅一条 trak');
  const vStbl = firstChild(init, firstChild(init, firstChild(init, collectBoxes(init, moov.start + 8, moov.end, 'trak')[0], 'mdia'), 'minf'), 'stbl');
  const vStsd = firstChild(init, vStbl, 'stsd');
  const avc1Only = findBox(init, vStsd.start + 16, vStsd.end, 'avc1');
  eq(findBox(init, avc1Only.start + 8 + 78, avc1Only.end, 'esds'), null, '视频轨不应含 esds');
  eq(findBox(init, avc1Only.start + 8 + 78, avc1Only.end, 'mp4a'), null, '不应含 mp4a');
  const mvex = firstChild(init, moov, 'mvex');
  eq(collectBoxes(init, mvex.start + 8, mvex.end, 'trex').length, 1, 'trex 数量');
});
check('buildMediaSegment: moof/mfhd/traf/tfhd/tfdt/trun/mdat', () => {
  const v1 = { ptsMs: 0, isKey: true, bytes: Uint8Array.from([1, 2, 3]) };
  const v2 = { ptsMs: 33, isKey: false, bytes: Uint8Array.from([4, 5]) };
  const a1 = { ptsMs: 10, bytes: Uint8Array.from([9, 9]) };
  const seg = buildMediaSegment(7, [v1, v2], [a1], 48000);
  assert(seg, 'media segment 为 null');
  const tops = topList(seg);
  eq(tops.length, 2, '顶层 box 数');
  eq(tops[0].type, 'moof', '第一个 box');
  eq(tops[1].type, 'mdat', '第二个 box');
  eq(tops[0].end, tops[1].start, 'moof/mdat 连续');
  const moof = tops[0];
  const mfhd = firstChild(seg, moof, 'mfhd');
  eq(u32(seg, mfhd.start + 12), 7, 'mfhd sequence number');
  const trafs = collectBoxes(seg, moof.start + 8, moof.end, 'traf');
  eq(trafs.length, 2, '视频+音频 traf');
  const vtraf = trafs[0];
  const tfhd = firstChild(seg, vtraf, 'tfhd');
  eq(u32(seg, tfhd.start + 12), 1, '视频 trackId=1');
  eq(u32(seg, tfhd.start + 8) & 0x020000, 0x020000, 'tfhd default-base-is-moof');
  const tfdt = firstChild(seg, vtraf, 'tfdt');
  eq(seg[tfdt.start + 8], 1, 'tfdt version=1');
  const trun = firstChild(seg, vtraf, 'trun');
  eq(u32(seg, trun.start + 12), 2, '视频 sample_count=2');
  const videoDataOffset = u32(seg, trun.start + 16);
  eq(videoDataOffset, moof.size + 8, '视频 data_offset 指向 mdat 负载');
  eq(u32(seg, trun.start + 28), 0x02000000, '关键帧 sample flags');
  eq(u32(seg, trun.start + 40), 0x01010000, '非关键帧 sample flags');
  eq(u32(seg, trun.start + 24), 3, '首个 sample size');
  const audioTraf = trafs[1];
  const atrun = firstChild(seg, audioTraf, 'trun');
  eq(u32(seg, atrun.start + 12), 1, '音频 sample_count=1');
  eq(u32(seg, atrun.start + 16), moof.size + 8 + 5, '音频 data_offset');
  eq(u32(seg, atrun.start + 20), 1024, '音频 sample duration');
  eq(u32(seg, tops[1].start), 8 + 5 + 2, 'mdat size');
  deepBytes(seg.slice(tops[1].start + 8, tops[1].start + 11), v1.bytes, 'mdat 起始为视频帧');
});
check('buildMediaSegment: 空/非法输入返回 null', () => {
  eq(buildMediaSegment(1, [], [], 48000), null, '空视频');
  eq(buildMediaSegment(1, null, null, 48000), null, 'null 视频');
  const seg = buildMediaSegment(1, [{ ptsMs: 0, isKey: true, bytes: Uint8Array.from([1]) }], [], 48000);
  assert(seg, '仅视频应可生成');
  eq(collectBoxes(seg, topList(seg)[0].start + 8, topList(seg)[0].end, 'traf').length, 1, '仅视频一条 traf');
});
check('解析器对随机字节不抛异常', () => {
  let rnd = 123456789;
  function nextByte() { rnd = (rnd * 1103515245 + 12345) & 0x7FFFFFFF; return rnd & 0xFF; }
  for (let n = 0; n < 40; n++) {
    const len = n * 3;
    const b = new Uint8Array(len);
    for (let i = 0; i < len; i++) b[i] = nextByte();
    parseFlvHeader(b);
    parseFlvTag(b, 0);
    parseAvcConfigRecord(b);
    parseAacAsc(b);
    parseAvcNalus(b, 4);
    parseVideoTag(b, 0, len);
  }
});

// ---------- 5. 汇总 ----------
console.log('');
if (failures.length) {
  console.log(`结果: ${passed} 通过, ${failures.length} 失败`);
  for (const f of failures) console.log(`  - ${f}`);
  console.log('结论: 未通过');
  process.exit(1);
}
console.log(`结果: ${passed} 项全部通过（文件 ${buffer.length} 字节 ≤ ${MAX_BYTES}）`);
console.log('结论: 通过');

#!/usr/bin/env node
/*
 * 套用字串抽出規格檔。用法：node tools/i18n-apply.js <規格檔> [區塊註解]
 * 規格格式（逐行，@@ 開頭為指令）：
 *   @@STR key        下一行起為字串值（原樣，\n 寫成反斜線加 n），到下一個指令為止
 *   @@FILE 路徑      相對於 app/src/main，例如 java/com/ping/verbead/X.kt 或 res/layout/x.xml
 *   @@OLD / @@OLDALL 要被取代的原文（OLDALL 允許多處）
 *   @@NEW            取代後的文字；{{key}} 會換成實際使用的 key
 *   @@END            結束一組
 * 字串值與 strings.xml 既有值相同時，自動共用既有 key。
 */
const fs = require('fs');
const path = require('path');
const ROOT = path.join(__dirname, '..', 'app', 'src', 'main');
const stringsPath = path.join(ROOT, 'res', 'values', 'strings.xml');
const comment = process.argv[3] || 'added';

const lines = fs.readFileSync(process.argv[2], 'utf8').split(/\r?\n/);
const blocks = [];
let cur = null, mode = null;
for (const l of lines) {
  const m = l.match(/^@@(FILE|OLDALL|OLD|NEW|STR|END)\s*(.*)$/);
  if (m) {
    const [, kind, arg] = m;
    if (kind === 'FILE') blocks.push({ type: 'file', path: arg.trim() });
    else if (kind === 'END') { cur = null; mode = null; }
    else if (kind === 'STR') { cur = { type: 'str', key: arg.trim(), text: [] }; blocks.push(cur); mode = 'text'; }
    else if (kind === 'NEW') { cur.newText = []; mode = 'new'; }
    else { cur = { type: 'edit', all: kind === 'OLDALL', old: [], newText: null }; blocks.push(cur); mode = 'old'; }
    continue;
  }
  if (!cur) continue;
  if (mode === 'text') cur.text.push(l);
  else if (mode === 'old') cur.old.push(l);
  else if (mode === 'new') cur.newText.push(l);
}
const join = a => a.join('\n').replace(/\n+$/, '');

let strings = fs.readFileSync(stringsPath, 'utf8');
const eol = strings.includes('\r\n') ? '\r\n' : '\n';
const unesc = v => v.replace(/^"|"$/g, '').replace(/\\'/g, "'").replace(/&lt;/g, '<').replace(/&amp;/g, '&');
const byValue = new Map();
const keys = new Set();
for (const m of strings.matchAll(/<string name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)) { keys.add(m[1]); byValue.set(unesc(m[2]), m[1]); }
const alias = new Map();
const added = [];
for (const b of blocks.filter(x => x.type === 'str')) {
  const v = join(b.text);
  if (byValue.has(v)) { alias.set(b.key, byValue.get(v)); continue; }
  if (keys.has(b.key)) throw new Error('key 已存在：' + b.key);
  keys.add(b.key); byValue.set(v, b.key); alias.set(b.key, b.key); added.push([b.key, v]);
}
const resolve = s => s.replace(/\{\{([a-z0-9_]+)\}\}/g, (_, k) => { if (!alias.has(k)) throw new Error('未定義的 key：' + k); return alias.get(k); });

let file = null, data = null, fileEol = '\n', total = 0;
const flush = () => { if (file) fs.writeFileSync(file, data); };
for (const b of blocks) {
  if (b.type === 'file') {
    flush();
    file = path.join(ROOT, b.path);
    data = fs.readFileSync(file, 'utf8');
    fileEol = data.includes('\r\n') ? '\r\n' : '\n';
  } else if (b.type === 'edit') {
    const o = join(b.old).replace(/\n/g, fileEol);
    const n = resolve(join(b.newText)).replace(/\n/g, fileEol);
    const count = data.split(o).length - 1;
    if (count === 0 || (!b.all && count > 1)) throw new Error(`${path.basename(file)}：比對到 ${count} 處\n${o}`);
    data = data.split(o).join(n);
    total += count;
  }
}
flush();

const esc = v => {
  let s = v.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/'/g, "\\'");
  if (/^\s|\s$/.test(s)) s = '"' + s + '"';
  return s;
};
const block = [`    <!-- ${comment} -->`, ...added.map(([k, v]) => `    <string name="${k}">${esc(v)}</string>`)].join(eol);
strings = strings.replace(/\s*<\/resources>\s*$/, eol + eol + block + eol + '</resources>' + eol);
fs.writeFileSync(stringsPath, strings);
console.log(`編輯 ${total} 處，新增 ${added.length} 條字串，共用既有 ${alias.size - added.length} 條`);

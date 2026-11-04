#!/usr/bin/env node
/*
 * 依目前狀態重寫 tools/i18n-allowlist.txt（NoHardcodedCjkTest 的基準）。
 * 只該在「違規減少」之後執行；不要用它來放行新增的寫死文字。
 * 用法（專案根目錄）：  node tools/i18n-allowlist-gen.js
 */
const fs = require('fs');
const path = require('path');

const MAIN = path.join(__dirname, '..', 'app', 'src', 'main');
const CJK = /[぀-ヿ㐀-鿿가-힯]/;
const RULE_FILES = new Set(['java/com/ping/verbead/engine/SpokenNumberConverter.kt', 'java/com/ping/verbead/engine/lang/ZhTwRules.kt']);
const out = [];

(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) { walk(p); continue; }
    const rel = path.relative(MAIN, p).split(path.sep).join('/');
    if (RULE_FILES.has(rel)) continue;
    let n = 0;
    if (rel.endsWith('.kt')) {
      for (const line of fs.readFileSync(p, 'utf8').split(/\r?\n/)) {
        const t = line.trim();
        if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) continue;
        const lits = line.match(/"(?:[^"\\]|\\.)*"/g);
        if (lits && lits.some(x => CJK.test(x))) n++;
      }
    } else if ((rel.startsWith('res/layout/') || rel.startsWith('res/menu/')) && rel.endsWith('.xml')) {
      const attrs = fs.readFileSync(p, 'utf8').match(/android:(text|hint|contentDescription|title|label)="[^"@][^"]*"/g) || [];
      n = attrs.filter(x => CJK.test(x)).length;
    }
    if (n) out.push(`${rel} ${n}`);
  }
})(MAIN);

out.sort();
const header =
  '# 寫死中日韓文字的現有違規：「相對路徑 數量」。只許減少、不許增加（NoHardcodedCjkTest 會檢查）。\n' +
  '# 每個工作包完成後請重跑 node tools/i18n-allowlist-gen.js 調降。SpokenNumberConverter 為語言規則檔，永久例外。\n';
fs.writeFileSync(path.join(__dirname, 'i18n-allowlist.txt'), header + out.join('\n') + '\n');
console.log(`${out.length} 個檔案，合計 ${out.reduce((a, x) => a + Number(x.split(' ').pop()), 0)}`);

#!/usr/bin/env node
/*
 * 多語言化進度盤點：數出還寫死在程式與版面裡的中日韓文字。
 *
 * 用法（在專案根目錄）：  node tools/i18n-inventory.js
 *
 * 計算項目：
 *  - 版面（res/layout）裡 android:text／hint／contentDescription／title／label 寫死的中日韓文
 *  - Kotlin 字串字面值裡含中日韓字的行（排除註解）；語言規則檔列入白名單，不算介面文字
 *  - AndroidManifest 的 android:label
 *  - res/values/strings.xml 的條目數
 * 每完成一個工作包就重跑一次，數字應該只減不增。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', 'app', 'src', 'main');
const CJK = /[぀-ヿ㐀-鿿가-힯]/;

// 這些檔案的中日韓字是語言處理規則本身，不是要翻譯的介面文字
const RULE_FILES = new Set(['engine/SpokenNumberConverter.kt', 'engine/lang/ZhTwRules.kt']);

function walk(dir, ext, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, ext, out);
    else if (ext.some(x => p.endsWith(x))) out.push(p);
  }
  return out;
}
const rel = (p, base) => path.relative(base, p).split(path.sep).join('/');

// Kotlin
const ktBase = path.join(ROOT, 'java', 'com', 'ping', 'verbead');
const kt = [];
let ktTotal = 0;
for (const f of walk(ktBase, ['.kt'])) {
  const name = rel(f, ktBase);
  if (RULE_FILES.has(name)) continue;
  let n = 0;
  for (const line of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
    const t = line.trim();
    if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) continue;
    const lits = line.match(/"(?:[^"\\]|\\.)*"/g);
    if (lits && lits.some(x => CJK.test(x))) n++;
  }
  if (n) { kt.push([name, n]); ktTotal += n; }
}
kt.sort((a, b) => b[1] - a[1]);

// 版面
const layoutBase = path.join(ROOT, 'res', 'layout');
const layouts = [];
let layoutTotal = 0;
for (const f of walk(layoutBase, ['.xml'])) {
  const attrs = fs.readFileSync(f, 'utf8')
    .match(/android:(text|hint|contentDescription|title|label)="[^"@][^"]*"/g) || [];
  const n = attrs.filter(x => CJK.test(x)).length;
  if (n) { layouts.push([rel(f, layoutBase), n]); layoutTotal += n; }
}
layouts.sort((a, b) => b[1] - a[1]);

// Manifest 與字串資源
const manifest = fs.readFileSync(path.join(ROOT, 'AndroidManifest.xml'), 'utf8');
const manifestLabels = (manifest.match(/android:label="[^"@][^"]*"/g) || []).filter(x => CJK.test(x)).length;
const valuesDirs = fs.readdirSync(path.join(ROOT, 'res')).filter(d => d.startsWith('values'));
const stringCount = d => {
  const f = path.join(ROOT, 'res', d, 'strings.xml');
  return fs.existsSync(f) ? (fs.readFileSync(f, 'utf8').match(/<string /g) || []).length : null;
};

const fmt = rows => rows.map(([n, c]) => `  ${String(c).padStart(4)}  ${n}`).join('\n');
console.log(`Kotlin 含中日韓字串字面值的行：${ktTotal}（${kt.length} 個檔案）`);
console.log(fmt(kt));
console.log(`\n版面寫死的中日韓屬性：${layoutTotal}（${layouts.length} 個檔案）`);
console.log(fmt(layouts));
console.log(`\nManifest 寫死的 label：${manifestLabels}`);
console.log('\n字串資源：');
for (const d of valuesDirs) {
  const c = stringCount(d);
  if (c !== null) console.log(`  ${d}/strings.xml  ${c} 條`);
}
console.log(`\n待處理合計：${ktTotal + layoutTotal + manifestLabels}`);

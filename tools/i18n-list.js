#!/usr/bin/env node
/* 列出指定 Kotlin 檔案裡含中日韓字的字串字面值行。用法：node tools/i18n-list.js <檔案...> */
const fs = require('fs');
const CJK = /[぀-ヿ㐀-鿿가-힯]/;
for (const f of process.argv.slice(2)) {
  console.log('== ' + f);
  fs.readFileSync(f, 'utf8').split(/\r?\n/).forEach((l, i) => {
    const t = l.trim();
    if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) return;
    const m = l.match(/"(?:[^"\\]|\\.)*"/g);
    if (m && m.some(x => CJK.test(x))) console.log((i + 1) + ': ' + t);
  });
}

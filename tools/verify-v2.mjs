/** V2 自检：Tabler 类是否真的存在 + 变量完整性 + 改造后的类分布 */
import fs from 'node:fs';

const A = 'D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console';
const OUT = 'D:/java/zishuati/DomainPlane/tools/v2-verify.txt';

const min = fs.readFileSync(`${A}/assets/vendor/tabler.min.css`, 'utf8');
const want = ['.btn', '.btn-primary', '.btn-secondary', '.btn-danger', '.btn-outline-secondary',
    '.btn-outline-primary', '.btn-sm', '.form-control', '.form-select', '.table', '.table-responsive', '.badge', '.modal'];
const has = want.map((w) => `${w}\t${min.includes(w) ? 'OK' : 'MISSING'}`);

// 变量完整性
const files = {
    'console.css': fs.readFileSync(`${A}/assets/console.css`, 'utf8'),
    'console-tokens.css': fs.readFileSync(`${A}/assets/console-tokens.css`, 'utf8'),
    'vendor/tabler.min.css': min,
};
// 只统计「全局作用域」定义的变量：:root（含 :root,:host 形式）块内的才是到处可用的；
// 定义在 .badge{} / .empty{} 这类组件规则里的变量，别处引用会静默失效。
const defined = new Set();
for (const css of Object.values(files)) {
    for (const m of css.matchAll(/:root[^{]*\{([^}]*)\}/g)) {
        for (const v of m[1].matchAll(/(--[a-zA-Z0-9-]+)\s*:/g)) { defined.add(v[1]); }
    }
}
const used = new Set();
for (const name of ['console.css', 'console-tokens.css']) {
    for (const m of files[name].matchAll(/var\((--[a-zA-Z0-9-]+)/g)) { used.add(m[1]); }
}
const missing = [...used].filter((n) => !defined.has(n)).sort();

// 类分布
const pages = fs.readdirSync(A).filter((f) => f.endsWith('.html'));
const count = (re) => {
    let n = 0;
    for (const f of [...pages.map((p) => `${A}/${p}`), `${A}/assets/console.js`]) {
        const t = fs.readFileSync(f, 'utf8');
        n += (t.match(re) || []).length;
    }
    return n;
};

fs.writeFileSync(OUT, [
    '=== Tabler 类存在性 ===',
    ...has,
    '',
    '=== 变量完整性 ===',
    `used=${used.size} missing=${missing.length}`,
    ...missing.map((n) => `  ${n}`),
    '',
    '=== 改造后分布 ===',
    `btn 总数        ${count(/\bclass="[^"]*\bbtn\b/g)}`,
    `btn-primary     ${count(/\bbtn-primary\b/g)}`,
    `btn-secondary   ${count(/\bbtn-secondary\b/g)}`,
    `btn-outline-sec ${count(/\bbtn-outline-secondary\b/g)}`,
    `form-control    ${count(/\bform-control\b/g)}`,
    `form-select     ${count(/\bform-select\b/g)}`,
    `table 类        ${count(/class="[^"]*\btable\b/g)}`,
].join('\n'), 'utf8');
console.log('done');

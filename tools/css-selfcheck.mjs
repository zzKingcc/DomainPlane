/**
 * V1 自检：
 *  1) console.css / console-tokens.css 里引用的 var(--x) 是否都有定义
 *  2) 定义来源分布（console.css 自身 / tokens / tabler-props）
 *  3) 列出 @import 链是否指向真实存在的文件
 */
import fs from 'node:fs';
import path from 'node:path';

const A = 'D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console/assets';
const OUT = 'D:/java/zishuati/DomainPlane/tools/css-selfcheck.txt';

const files = {
    'console.css': fs.readFileSync(path.join(A, 'console.css'), 'utf8'),
    'console-tokens.css': fs.readFileSync(path.join(A, 'console-tokens.css'), 'utf8'),
    'vendor/tabler-props.css': fs.readFileSync(path.join(A, 'vendor/tabler-props.css'), 'utf8'),
};

// 已定义的变量
const defined = new Set();
for (const css of Object.values(files)) {
    for (const m of css.matchAll(/(--[a-zA-Z0-9-]+)\s*:/g)) { defined.add(m[1]); }
}

// 被引用的变量（只查我们自己写的两个文件）
const used = new Map();
for (const name of ['console.css', 'console-tokens.css']) {
    for (const m of files[name].matchAll(/var\((--[a-zA-Z0-9-]+)/g)) {
        if (!used.has(m[1])) { used.set(m[1], []); }
        used.get(m[1]).push(name);
    }
}

const missing = [...used.keys()].filter((n) => !defined.has(n)).sort();

// @import 目标是否存在
const imports = [];
for (const m of files['console.css'].matchAll(/@import\s+url\("([^"]+)"\)/g)) {
    const target = path.resolve(A, m[1]);
    imports.push(`${m[1]}\t${fs.existsSync(target) ? 'OK' : 'MISSING'}`);
}

// 仍残留的硬编码色值（V1 之后不该再新增，仅统计）
const hardcoded = [...files['console.css'].matchAll(/#[0-9a-fA-F]{3,8}\b/g)].map((m) => m[0]);
const hardcodedUniq = [...new Set(hardcoded)];

fs.writeFileSync(OUT, [
    `used vars: ${used.size}, defined vars: ${defined.size}`,
    `missing (引用但未定义): ${missing.length}`,
    ...missing.map((n) => `  ${n}  ← ${[...new Set(used.get(n))].join(',')}`),
    '--- @import ---',
    ...imports,
    `--- console.css 残留硬编码色值: ${hardcodedUniq.length} 种 ---`,
    hardcodedUniq.join(' '),
].join('\n'), 'utf8');
console.log('done');

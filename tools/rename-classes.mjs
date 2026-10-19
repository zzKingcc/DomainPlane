/**
 * V0 · 冲突类改名（一次性迁移脚本，幂等）
 *
 * 背景：console.css 里的 .card / .card-body / .row / .nav / .nav-item 与
 * Bootstrap（Tabler 内置）同名但语义不同，直接引 tabler.css 会互相污染。
 * 本脚本把这 5 个类统一加 `dp-` 前缀，覆盖三个位置：
 *   1) HTML / JS 字符串里的 class="..."（token 精确匹配，不误伤 card-head / model-card）
 *   2) JS 选择器字面量（'.card.model-card'）
 *   3) CSS 选择器（.card { ... }）
 *
 * 已带 dp- 前缀的不会被二次处理 —— 可以反复运行。
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console';
const REPORT = 'D:/java/zishuati/DomainPlane/tools/rename-report.txt';

const MAP = new Map([
    ['card', 'dp-card'],
    ['card-body', 'dp-card-body'],
    ['row', 'dp-row'],
    ['nav', 'dp-nav'],
    ['nav-item', 'dp-nav-item'],
]);

/** CSS 选择器替换：先长后短，负向前瞻挡住 dp- 前缀与 -suffix（.card-head 不会被碰） */
const CSS_RULES = [
    [/\.card-body(?![\w-])/g, '.dp-card-body'],
    [/\.nav-item(?![\w-])/g, '.dp-nav-item'],
    [/\.card(?![\w-])/g, '.dp-card'],
    [/\.nav(?![\w-])/g, '.dp-nav'],
    [/\.row(?![\w-])/g, '.dp-row'],
];

function collectFiles(dir, acc = []) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) { collectFiles(full, acc); }
        else if (/\.(html|js|css)$/.test(entry.name)) { acc.push(full); }
    }
    return acc;
}

const files = collectFiles(ROOT);
const lines = [];
let totalHits = 0;
let touchedFiles = 0;

for (const file of files.sort()) {
    const before = fs.readFileSync(file, 'utf8');
    let after = before;
    let hits = 0;

    if (file.endsWith('.css')) {
        for (const [re, to] of CSS_RULES) {
            after = after.replace(re, () => { hits += 1; return to; });
        }
    } else {
        // 1) class="..." 里的 token（HTML 与 JS 拼串都走这条）
        after = after.replace(/class="([^"]*)"/g, (_m, inner) => {
            const tokens = inner.split(/\s+/).map((t) => {
                if (MAP.has(t)) { hits += 1; return MAP.get(t); }
                return t;
            });
            return 'class="' + tokens.join(' ') + '"';
        });

        // 2) JS 选择器字面量：'.card.model-card' / '.nav-item'
        after = after.replace(/(['"])\.(card-body|nav-item|card|nav|row)\b/g, (_m, q, name) => {
            hits += 1;
            return q + '.' + MAP.get(name);
        });
    }

    if (after !== before) {
        fs.writeFileSync(file, after, 'utf8');
        touchedFiles += 1;
        totalHits += hits;
        lines.push(`${path.relative(ROOT, file).replace(/\\/g, '/')}\t${hits}`);
    }
}

const header = [
    'V0 改名报告 · ' + new Date().toISOString(),
    `扫描 ${files.length} 个文件，改动 ${touchedFiles} 个，替换 ${totalHits} 处`,
    '映射: .card→.dp-card  .card-body→.dp-card-body  .row→.dp-row  .nav→.dp-nav  .nav-item→.dp-nav-item',
    '---',
].join('\n');

fs.writeFileSync(REPORT, header + '\n' + lines.join('\n') + '\n', 'utf8');
console.log(header);
console.log(lines.join('\n'));

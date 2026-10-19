/**
 * V2-a：给按钮与表单控件追加 Tabler 组件类（幂等）
 *
 * 按钮映射：
 *   无 class / 未匹配      → btn btn-primary
 *   ghost                  → btn btn-sm btn-outline-secondary
 *   sec                    → btn btn-secondary
 *   danger                 → btn btn-danger
 *   btn-primary            → btn
 *   tune-save              → btn btn-sm btn-primary
 *   foot-out               → btn btn-sm btn-outline-secondary
 * 结构型按钮（modal-close / combo-btn / tune-bar-seg）不动。
 *
 * 表单映射：input(非 checkbox/radio/file/hidden) / textarea → form-control，select → form-select。
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console';
const OUT = 'D:/java/zishuati/DomainPlane/tools/v2-apply-report.txt';

const SKIP_BTN = ['modal-close', 'combo-btn', 'tune-bar-seg', 'tune-switch'];
const BTN_MAP = [
    [['ghost'], 'btn btn-sm btn-outline-secondary'],
    [['sec'], 'btn btn-secondary'],
    [['danger'], 'btn btn-danger'],
    [['btn-primary'], 'btn'],
    [['tune-save'], 'btn btn-sm btn-primary'],
    [['foot-out'], 'btn btn-sm btn-outline-secondary'],
];

function walk(dir, acc = []) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) { if (e.name !== 'vendor') walk(p, acc); }
        else if (/\.(html|js)$/.test(e.name)) acc.push(p);
    }
    return acc;
}

function tokensOf(tag) {
    const m = tag.match(/class="([^"]*)"/);
    return m ? m[1].split(/\s+/).filter(Boolean) : null;
}

/** 把 cls 合并进标签：有 class 属性就替换值，没有就插到标签名后 */
function withClass(tag, cls) {
    if (/class="[^"]*"/.test(tag)) {
        return tag.replace(/class="([^"]*)"/, (_m, old) => `class="${old.trim()} ${cls}"`);
    }
    return tag.replace(/^<([a-z]+)/i, (_m, name) => `<${name} class="${cls}"`);
}

const report = [];
let total = 0;

for (const file of walk(ROOT).sort()) {
    const src = fs.readFileSync(file, 'utf8');
    let hits = 0;
    let out = src.replace(/<(button|input|select|textarea)\b[^>]*>/g, (tag) => {
        const name = tag.match(/^<([a-z]+)/i)[1].toLowerCase();
        const toks = tokensOf(tag) || [];

        if (name === 'button') {
            if (toks.includes('btn')) { return tag; }
            if (SKIP_BTN.some((s) => toks.includes(s))) { return tag; }
            const rule = BTN_MAP.find(([keys]) => keys.some((k) => toks.includes(k)));
            hits += 1;
            return withClass(tag, rule ? rule[1] : 'btn btn-primary');
        }

        // 表单控件
        if (toks.includes('form-control') || toks.includes('form-select')) { return tag; }
        if (name === 'input') {
            const type = (tag.match(/type="([^"]*)"/) || [, 'text'])[1].toLowerCase();
            if (['checkbox', 'radio', 'file', 'hidden'].includes(type)) { return tag; }
            hits += 1;
            return withClass(tag, 'form-control');
        }
        if (name === 'select') { hits += 1; return withClass(tag, 'form-select'); }
        hits += 1;
        return withClass(tag, 'form-control');
    });

    if (out !== src) {
        fs.writeFileSync(file, out, 'utf8');
        total += hits;
        report.push(`${path.relative(ROOT, file).replace(/\\/g, '/')}\t${hits}`);
    }
}

fs.writeFileSync(OUT, [
    `total controls updated: ${total}`,
    '---',
    ...report,
].join('\n'), 'utf8');
console.log(`total=${total}`);

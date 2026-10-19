/**
 * V2 映射修正：
 *   ghost（白底 + 蓝字 + 浅边框）应映射 btn-outline-primary，之前错用了 outline-secondary
 *   sec（白底 + 灰字 + 浅边框）应映射 btn-outline-secondary，之前错用了实底的 btn-secondary
 * 幂等。
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console';
const OUT = 'D:/java/zishuati/DomainPlane/tools/v2-fix-report.txt';

function walk(dir, acc = []) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) { if (e.name !== 'vendor') walk(p, acc); }
        else if (/\.(html|js)$/.test(e.name)) acc.push(p);
    }
    return acc;
}

const report = [];
let total = 0;

for (const file of walk(ROOT).sort()) {
    const src = fs.readFileSync(file, 'utf8');
    let out = src;
    let hits = 0;

    // ghost / foot-out：outline-secondary → outline-primary
    out = out.replace(/class="([^"]*)"/g, (m, inner) => {
        const toks = inner.split(/\s+/).filter(Boolean);
        if (!toks.includes('btn-outline-secondary')) { return m; }

        // 侧栏退出按钮保持 secondary（白底灰字）
        const target = toks.includes('foot-out') ? 'btn-outline-secondary' : 'btn-outline-primary';
        const idx = toks.indexOf('btn-outline-secondary');
        if (toks[idx] === target) { return m; }
        toks[idx] = target;
        hits += 1;
        return `class="${toks.join(' ')}"`;
    });

    // sec：实底 secondary → 描边 secondary
    if (out.includes('sec btn btn-secondary')) {
        out = out.split('sec btn btn-secondary').join('sec btn btn-outline-secondary');
        hits += (src.match(/sec btn btn-secondary/g) || []).length;
    }

    if (out !== src) {
        fs.writeFileSync(file, out, 'utf8');
        total += hits;
        report.push(`${path.relative(ROOT, file).replace(/\\/g, '/')}\t${hits}`);
    }
}

fs.writeFileSync(OUT, [`total fixed: ${total}`, '---', ...report].join('\n'), 'utf8');
console.log(`fixed=${total}`);

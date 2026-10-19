/**
 * V2 前置：盘点所有按钮 / 表单控件的开标签，用于制定 class 映射表。
 * 只读不改。
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console';
const OUT = 'D:/java/zishuati/DomainPlane/tools/v2-controls.txt';

function walk(dir, acc = []) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) { if (e.name !== 'vendor') walk(p, acc); }
        else if (/\.(html|js)$/.test(e.name)) acc.push(p);
    }
    return acc;
}

const lines = [];
const classFreq = new Map();

for (const file of walk(ROOT).sort()) {
    const text = fs.readFileSync(file, 'utf8');
    const rows = text.split(/\r?\n/);
    rows.forEach((line, i) => {
        const tags = line.match(/<(button|input|select|textarea)\b[^>]*>/g) || [];
        for (const tag of tags) {
            if (tag.length > 200) continue;
            const cls = (tag.match(/class="([^"]*)"/) || [, ''])[1];
            for (const t of cls.split(/\s+/).filter(Boolean)) {
                classFreq.set(t, (classFreq.get(t) || 0) + 1);
            }
            lines.push(`${path.basename(file)}:${i + 1}\t${tag.replace(/\s+/g, ' ')}`);
        }
    });
}

const freq = [...classFreq.entries()].sort((a, b) => b[1] - a[1])
    .map(([k, v]) => `${k}\t${v}`);

fs.writeFileSync(OUT, [
    `控件开标签: ${lines.length}`,
    '=== 现有 class 频次 ===',
    ...freq,
    '=== 明细 ===',
    ...lines,
].join('\n'), 'utf8');
console.log(`tags=${lines.length} classes=${classFreq.size}`);

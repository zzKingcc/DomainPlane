/** 取 badge / empty / form-label / card-header 的完整规则，用于把自有类对齐到 Tabler 视觉 */
import fs from 'node:fs';

const min = fs.readFileSync('D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console/assets/vendor/tabler.min.css', 'utf8');
const OUT = 'D:/java/zishuati/DomainPlane/tools/tabler-visual-ref.txt';

const SELS = ['.badge', '.empty', '.empty-title', '.empty-subtitle', '.empty-header', '.form-label', '.card-header', '.card-body'];
const out = [];

for (const sel of SELS) {
    const re = new RegExp(sel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '(?![\\w-])\\{([^{}]*)\\}', 'g');
    const hits = [...min.matchAll(re)].map((m) => m[1].trim());
    out.push(`### ${sel}  (${hits.length} hit)`);
    hits.slice(0, 2).forEach((h) => out.push('  ' + h.slice(0, 400)));
    out.push('');
}

const vars = new Set();
for (const m of min.matchAll(/--tblr-(?:badge|empty)[a-z0-9-]*/g)) { vars.add(m[0]); }
out.push('=== badge/empty vars ===', [...vars].sort().join(' '));

fs.writeFileSync(OUT, out.join('\n'), 'utf8');
console.log('done');

/** 查 Tabler reboot 里 a / a:hover 的真实规则与权重 */
import fs from 'node:fs';

const min = fs.readFileSync('D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console/assets/vendor/tabler.min.css', 'utf8');
const OUT = 'D:/java/zishuati/DomainPlane/tools/tabler-a-rules.txt';

const out = [];
for (const sel of ['a', 'a:hover', 'a:not([class]):hover', 'a:not([class])']) {
    const re = new RegExp(sel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '(?![\\w-])\\{([^{}]*)\\}', 'g');
    const hits = [...min.matchAll(re)].map((m) => m[1].trim());
    out.push(`### ${sel}  (${hits.length})`);
    hits.forEach((h) => out.push('  ' + h.slice(0, 300)));
    out.push('');
}

// 也看看 .link 系列（将来可能用到，确认不被误伤）
for (const sel of ['.link', '.link:hover', '.link-primary']) {
    const re = new RegExp(sel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '(?![\\w-])\\{([^{}]*)\\}', 'g');
    const hits = [...min.matchAll(re)].map((m) => m[1].trim());
    out.push(`### ${sel}  (${hits.length})`);
    hits.forEach((h) => out.push('  ' + h.slice(0, 200)));
}

fs.writeFileSync(OUT, out.join('\n'), 'utf8');
console.log('done');

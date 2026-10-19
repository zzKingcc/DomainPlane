/** 查 Tabler 1.6 的按钮变体类到底叫什么 */
import fs from 'node:fs';

const min = fs.readFileSync('D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console/assets/vendor/tabler.min.css', 'utf8');
const OUT = 'D:/java/zishuati/DomainPlane/tools/btn-variants.txt';

const keys = ['.btn-danger', '.btn-outline', '.btn-outline-secondary', '.btn-ghost', '.btn-success',
    '.btn-secondary', '.btn-primary', '.btn-sm', '.btn-lg', '.btn-pill', '.btn-icon'];
const out = [];
for (const k of keys) {
    const all = [...min.matchAll(new RegExp(k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '(?![\\w-])', 'g'))];
    out.push(`${k}\tcount=${all.length}`);
    if (all.length) {
        const i = all[0].index;
        out.push(`  …${min.slice(Math.max(0, i - 30), i + 110)}…`);
    }
}

// 列出所有 .btn-* 变体名
const variants = new Set();
for (const m of min.matchAll(/\.btn-[a-z0-9-]+/g)) { variants.add(m[0]); }
out.push('', '=== 全部 .btn-* 类 ===', [...variants].sort().join(' '));

fs.writeFileSync(OUT, out.join('\n'), 'utf8');
console.log('done');

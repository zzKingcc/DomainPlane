/** 提取 tabler.min.css 里的 CSS 变量名清单，用于核对 token 映射 */
import fs from 'node:fs';

const SRC = 'D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console/assets/vendor/tabler.min.css';
const OUT = 'D:/java/zishuati/DomainPlane/tools/tabler-vars.txt';

const css = fs.readFileSync(SRC, 'utf8');
const names = new Set();
for (const m of css.matchAll(/--(?:tblr|bs)-[a-z0-9-]+/g)) { names.add(m[0]); }

const all = [...names].sort();
const interesting = all.filter((n) => /(bg|border|body|text|surface|gray)/.test(n));

fs.writeFileSync(OUT, [
    `total vars: ${all.length}`,
    '--- bg/border/body/text/surface ---',
    ...interesting,
    '--- all ---',
    ...all,
].join('\n'), 'utf8');
console.log(`vars=${all.length} interesting=${interesting.length}`);

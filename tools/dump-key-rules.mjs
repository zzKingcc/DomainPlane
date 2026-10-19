/** 取出 Tabler 关键组件的实际视觉参数，作为 V1 重写 console.css 的依据 */
import fs from 'node:fs';

const SRC = 'D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console/assets/vendor/tabler.min.css';
const OUT = 'D:/java/zishuati/DomainPlane/tools/tabler-key-rules.txt';

const css = fs.readFileSync(SRC, 'utf8');
const SEL = ['.card', '.card-body', '.card-header', '.btn', '.btn-primary', '.form-control',
    '.form-select', '.form-label', '.table', '.badge', '.navbar-vertical', '.page', '.h1, .h2, .h3'];

const out = [];
for (const sel of SEL) {
    // 转义选择器里的点，找 `sel { ... }` 的第一处
    const re = new RegExp(sel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\{([^{}]*)\\}');
    const m = css.match(re);
    out.push(`### ${sel}\n${m ? m[1].trim() : '(not found)'}\n`);
}

fs.writeFileSync(OUT, out.join('\n'), 'utf8');
console.log('done');

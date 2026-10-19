/** 取出指定 Tabler 变量的实际值（min.css 是压缩单行，props 是格式化文件，两边都查） */
import fs from 'node:fs';

const DIR = 'D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console/assets/vendor';
const OUT = 'D:/java/zishuati/DomainPlane/tools/tabler-values.txt';
const NAMES = [
    '--tblr-shadow-card', '--tblr-shadow-input', '--tblr-shadow',
    '--tblr-border-color-translucent', '--tblr-border-color', '--tblr-border-width',
    '--tblr-bg-surface', '--tblr-bg-surface-secondary', '--tblr-bg-surface-tertiary',
    '--tblr-body-bg', '--tblr-body-color', '--tblr-bg-forms',
    '--tblr-border-radius', '--tblr-border-radius-lg', '--tblr-body-font-size',
    '--tblr-font-weight-medium', '--tblr-primary', '--tblr-link-color',
];

const sources = {
    'tabler-props.css': fs.readFileSync(`${DIR}/tabler-props.css`, 'utf8'),
    'tabler.min.css': fs.readFileSync(`${DIR}/tabler.min.css`, 'utf8'),
};

const out = [];
for (const name of NAMES) {
    const hits = [];
    for (const [file, css] of Object.entries(sources)) {
        const re = new RegExp(name.replace(/[-[\]{}()*+?.,\\^$|#\s]/g, '\\$&') + ':([^;}]+)', 'g');
        const found = [...css.matchAll(re)].map((m) => m[1].trim());
        if (found.length) { hits.push(`${file}: ${[...new Set(found)].slice(0, 3).join(' | ')}`); }
    }
    out.push(`${name}\n  ${hits.length ? hits.join('\n  ') : '(none)'}`);
}
fs.writeFileSync(OUT, out.join('\n\n'), 'utf8');
console.log('done');

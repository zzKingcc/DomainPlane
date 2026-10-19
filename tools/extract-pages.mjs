/**
 * P1 · 批量外提页面内联脚本
 *
 * 对 console/*.html：把所有不带 src 的 <script> 块抽出来，合并写入
 * frontend/src/pages/<name>.js（IIFE 包裹），HTML 原位置替换为单个
 * <script src="assets/page-<name>.js"></script>。
 *
 * 前置检查（已做过）：全站只有 login.html 的 onsubmit="return false;"，
 * 没有依赖全局函数的内联事件属性，所以 IIFE 化不会破坏行为。
 *
 * 幂等：已经是外提形态（无内联 script）的页面会跳过。
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'D:/java/zishuati/DomainPlane';
const CONSOLE = `${ROOT}/domainplane-server/src/main/resources/static/console`;
const PAGES_DIR = `${ROOT}/domainplane-server/frontend/src/pages`;

fs.mkdirSync(PAGES_DIR, { recursive: true });

const pages = fs.readdirSync(CONSOLE)
    .filter((f) => f.endsWith('.html'))
    .filter((f) => f !== 'login.html')          // login 已手工外提
    .sort();

const report = [];

for (const file of pages) {
    const name = file.replace(/\.html$/, '');
    const full = `${CONSOLE}/${file}`;
    const html = fs.readFileSync(full, 'utf8');

    const blocks = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)];
    if (!blocks.length) {
        report.push(`${file}\tskip(no inline script)`);
        continue;
    }

    const body = blocks.map((m) => m[1].trim()).join('\n\n');
    const out = [
        '/* ==========================================================================',
        `   P1 · ${name} 页脚本`,
        `   自 ${file} 的内联 <script> 机械平移（${blocks.length} 块合并），逻辑未改。`,
        '   ========================================================================== */',
        '(function () {',
        body,
        '})();',
        '',
    ].join('\n');

    fs.writeFileSync(`${PAGES_DIR}/${name}.js`, out, 'utf8');

    let replaced = false;
    const newHtml = html.replace(/<script(?![^>]*\bsrc=)[^>]*>[\s\S]*?<\/script>/g, () => {
        if (replaced) { return ''; }
        replaced = true;
        return `<script src="assets/page-${name}.js"></script>`;
    });
    fs.writeFileSync(full, newHtml, 'utf8');

    report.push(`${file}\t${blocks.length} block(s) -> page-${name}.js`);
}

fs.writeFileSync(`${ROOT}/tools/extract-report.txt`, [
    `extracted ${pages.length} pages`,
    '---',
    ...report,
].join('\n'), 'utf8');
console.log(report.join('\n'));

/**
 * 评估引入 tabler.min.css 时，哪些「裸元素」规则会影响现有页面。
 * 现有 12 页用的是自定义类，Tabler 的类规则命中不到，真正会漂移的只有裸元素。
 */
import fs from 'node:fs';

const SRC = 'D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console/assets/vendor/tabler.min.css';
const OUT = 'D:/java/zishuati/DomainPlane/tools/tabler-reboot-impact.txt';

const css = fs.readFileSync(SRC, 'utf8');
const rules = [];
for (const m of css.matchAll(/(?:^|\})([^{}@]+)\{([^{}]*)\}/g)) {
    const sel = m[1].trim();
    const body = m[2].trim();
    if (!sel || /[.#\[]/.test(sel)) { continue; }
    if (!/^(body|html|h[1-6]|p|ul|ol|li|table|thead|tbody|tr|th|td|input|select|textarea|button|a|label|\*)\b/i.test(sel)) { continue; }
    rules.push(`${sel} { ${body.slice(0, 220)} }`);
}

fs.writeFileSync(OUT, [
    `raw-element rules in tabler.min.css: ${rules.length}`,
    '---',
    ...rules,
].join('\n'), 'utf8');
console.log(`rules=${rules.length}`);

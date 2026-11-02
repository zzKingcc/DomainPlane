/** 查 Tabler 里 toast / modal 的全部规则，重点看状态类（show/hide）与可见性 */
import fs from 'node:fs';

const min = fs.readFileSync(
  'D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console/assets/vendor/tabler.min.css',
  'utf8'
);

const out = [];
const re = /([^{}]+)\{([^{}]*)\}/g;
let m;
while ((m = re.exec(min)) !== null) {
  const sel = m[1].trim();
  const body = m[2].trim();
  if (!/\btoast\b|\bmodal\b|btn-danger|btn-close/.test(sel)) { continue; }
  // 只留与「显不显示 / 长什么样」有关的声明，掐掉一堆变量定义
  const keep = body.split(';').filter((d) => d && /display|opacity|visibility|transform|background-color|color:|position|z-index|width|height|box-shadow|border-radius|padding|font-size|flex|justify|align|inset|top:|left:|right:|bottom:|overflow|pointer-events|transition|gap|margin/.test(d));
  if (!keep.length) { continue; }
  out.push(sel + ' {\n  ' + keep.join(';\n  ') + ';\n}');
}
fs.writeFileSync('D:/java/zishuati/DomainPlane/tools/tabler-toast-modal-rules.txt', out.join('\n\n'), 'utf8');
console.log('rules=' + out.length);

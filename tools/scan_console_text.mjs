// decode \uXXXX in console artifacts and locate target texts
import fs from 'fs';
import path from 'path';
const dir = String.raw`D:\java\zishuati\DomainPlane\domainplane-server\src\main\resources\static\console\assets`;
const out = [];
for (const f of fs.readdirSync(dir)) {
  if (!f.endsWith('.js')) continue;
  const raw = fs.readFileSync(path.join(dir, f), 'utf8');
  const decoded = raw.replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
  const targets = ['三处存储', '清空表单', 'infra-settings.json', '不修改', '混合召回', '内置磁盘'];
  const hits = targets.filter(t => decoded.includes(t));
  out.push({ file: f, size: raw.length, hits });
  fs.writeFileSync(path.join(String.raw`D:\java\zishuati\DomainPlane\tools`, 'decoded_' + f), decoded);
}
console.log(JSON.stringify(out, null, 1));

/** models.js 的 P2 机械迁移：方法映射 + 删除手写的 code 判断块。
 *  特殊的三处（requiresConfirmation / resolve 的 baseUrl 业务判断 / models 的 success 判断）
 *  已手工改完，这里只处理剩下模式一致的部分。跑完必须 lint + 人工复核。 */
import fs from 'node:fs';

const F = 'D:/java/zishuati/DomainPlane/domainplane-server/frontend/src/pages/models.js';
const OUT = 'D:/java/zishuati/DomainPlane/tools/models-migrate-report.txt';

let s = fs.readFileSync(F, 'utf8');
const before = s;
let hits = { verb: 0, del: 0, noBody: 0, req: 0, codeBlock: 0, codeInline: 0 };

// 1) jsonRequest(path, 'PUT'|'POST', body) → api.put/post(path, body)
s = s.replace(/Console\.jsonRequest\((\s*'[^']+')\s*,\s*'(PUT|POST)'\s*,\s*/g, (_m, p, verb) => {
    hits.verb += 1;
    return `Console.api.${verb.toLowerCase()}(${p.trim()}, `;
});

// 2) jsonRequest(path, 'DELETE') → api.del(path)
s = s.replace(/Console\.jsonRequest\((\s*'[^']+')\s*,\s*'DELETE'\s*\)/g, (_m, p) => {
    hits.del += 1;
    return `Console.api.del(${p.trim()})`;
});

// 3) jsonRequest(path, 'GET') → api.get(path)；jsonRequest(path,'POST') 无 body → api.post(path, null)
s = s.replace(/Console\.jsonRequest\((\s*'[^']+')\s*,\s*'(GET|POST)'\s*\)/g, (_m, p, verb) => {
    hits.noBody += 1;
    return verb === 'GET' ? `Console.api.get(${p.trim()})` : `Console.api.post(${p.trim()}, null)`;
});

// 4) Console.request(path) → api.get(path)
s = s.replace(/Console\.request\((\s*'[^']+')/g, (_m, p) => {
    hits.req += 1;
    return `Console.api.get(${p.trim()})`;
});

// 5) 删掉多行的 `if (r && r.code && r.code !== 0) { throw new Error(...); }`
s = s.replace(/\n[ \t]*if \(r && r\.code && r\.code !== 0\) \{\s*\n[ \t]*throw new Error\(Console\.sanitizeError\([^;]*;\s*\n[ \t]*\}/g, () => {
    hits.codeBlock += 1;
    return '';
});

// 6) 删掉单行版
s = s.replace(/\n[ \t]*if \(r && r\.code && r\.code !== 0\) \{ throw new Error\([^}]*\}/g, () => {
    hits.codeInline += 1;
    return '';
});

if (s !== before) { fs.writeFileSync(F, s, 'utf8'); }

fs.writeFileSync(OUT, JSON.stringify(hits, null, 2) + `\nremaining Console.request=${(s.match(/Console\.request\(/g) || []).length}\nremaining Console.jsonRequest=${(s.match(/Console\.jsonRequest\(/g) || []).length}\nremaining codeCheck=${(s.match(/r\.code && r\.code !== 0/g) || []).length}\n`, 'utf8');
console.log(JSON.stringify(hits));

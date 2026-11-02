/**
 * P0 · 基线清单生成（静态扫描，不改任何文件）
 *
 * 产出三份：
 *   pages.md         页面 × URL × 关键 DOM 锚点          → 喂给 P3 冒烟
 *   api-calls.md     页面 → 接口调用                      → 改 console.js 时的影响面
 *   envelope-map.md  接口 → 信封形态（A/B/C/D）           → P2 的唯一施工图
 *
 * 信封判定按方法体内是否出现 put("code" / put("success"）分级：
 *   A = 只有 code   B = code + success 都有   C = 只有 success   D = 都没有（裸对象）
 *
 * 产物写进 .workbuddy/baseline（已 gitignore）：这三份是某一刻的扫描快照，
 * 只作核对用，不进 docs（见 docs/README.md 维护约定第 3 条「快照不留」）。
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'D:/java/zishuati/DomainPlane';
const CONSOLE = `${ROOT}/domainplane-server/src/main/resources/static/console`;
const CTRL = `${ROOT}/domainplane-server/src/main/java/com/zzkingcc/domainplane/server/controller`;
const OUT = `${ROOT}/.workbuddy/baseline`;

fs.mkdirSync(OUT, { recursive: true });

/* ---------------- 1. 页面清单 ---------------- */
const htmls = fs.readdirSync(CONSOLE).filter((f) => f.endsWith('.html')).sort();
const pageRows = [];
for (const f of htmls) {
    const text = fs.readFileSync(`${CONSOLE}/${f}`, 'utf8');
    const title = (text.match(/<title>([^<]*)<\/title>/) || [, ''])[1].trim();
    const ids = [...new Set([...text.matchAll(/\bid="([a-zA-Z][\w-]*)"/g)].map((m) => m[1]))];
    const anchors = ids.filter((id) => !/^(username|password|password2|submitBtn|result|note|banner)$/.test(id)).slice(0, 8);
    pageRows.push(`| \`${f}\` | \`/console/${f}\` | ${title} | ${anchors.map((a) => '`#' + a + '`').join(' ') || '—'} |`);
}

/* ---------------- 2. 接口调用清单 ---------------- */
const callRows = [];
const allFront = [...htmls.map((f) => `${CONSOLE}/${f}`), `${CONSOLE}/assets/console.js`];
for (const file of allFront) {
    const text = fs.readFileSync(file, 'utf8');
    const hits = [...text.matchAll(/Console\.(request|jsonRequest)\(\s*'([^']+)'/g)];
    if (!hits.length) { continue; }
    const paths = [...new Set(hits.map((m) => `${m[1]} ${m[2]}`))];
    callRows.push(`| \`${path.basename(file)}\` | ${paths.map((p) => '`' + p + '`').join('<br>')} |`);
}

/* ---------------- 3. 信封对照表 ---------------- */
const javaFiles = fs.readdirSync(CTRL).filter((f) => f.endsWith('.java')).sort();
const envRows = [];
const summary = { A: 0, B: 0, C: 0, D: 0 };

for (const f of javaFiles) {
    const lines = fs.readFileSync(`${CTRL}/${f}`, 'utf8').split(/\r?\n/);
    // 类级路径
    let base = '';
    for (let i = 0; i < lines.length; i++) {
        const m = lines[i].match(/@RequestMapping\("([^"]+)"\)/);
        if (m) { base = m[1]; break; }
        if (/^\s*public\s+class\s/.test(lines[i])) { break; }
    }
    // 方法级切分
    const marks = [];
    lines.forEach((line, i) => {
        const m = line.match(/@(Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/);
        if (m) {
            // 注解行通常不带方法签名，向下找第一行 public ... name(
            let name = '?';
            for (let j = i + 1; j < Math.min(i + 10, lines.length); j++) {
                const im = lines[j].match(/public\s+[\w<>, .\[\]]+\s+(\w+)\s*\(/);
                if (im) { name = im[1]; break; }
            }
            marks.push({ i, verb: m[1].toUpperCase(), sub: m[2], name });
        }
    });
    for (let k = 0; k < marks.length; k++) {
        const start = marks[k].i;
        const end = k + 1 < marks.length ? marks[k + 1].i : lines.length;
        const body = lines.slice(start, Math.min(end, start + 400)).join('\n');
        const hasCode = /put\("code"/.test(body);
        const hasSuccess = /put\("success"/.test(body);
        const kind = hasCode && hasSuccess ? 'B' : hasCode ? 'A' : hasSuccess ? 'C' : 'D';
        summary[kind] += 1;
        const route = (base + marks[k].sub).replace(/\/+/g, '/');
        envRows.push(`| ${kind} | ${marks[k].verb} | \`${route}\` | ${marks[k].name} | \`${f.replace('.java', '')}:${start + 1}\` |`);
    }
}

/* ---------------- 写文件 ---------------- */
const stamp = new Date().toISOString().slice(0, 10);

fs.writeFileSync(`${OUT}/pages.md`, [
    `# 管控台页面清单（P0 · ${stamp} 静态扫描）`,
    '',
    '| 文件 | URL | 标题 | 关键 DOM 锚点（前 8 个） |',
    '|---|---|---|---|',
    ...pageRows,
    '',
    '> 锚点取自页面里的静态 `id`；由 JS 动态生成的节点不在表内，P3 冒烟需要时另补。',
].join('\n'), 'utf8');

fs.writeFileSync(`${OUT}/api-calls.md`, [
    `# 页面 → 接口调用清单（P0 · ${stamp}）`,
    '',
    '| 文件 | 调用的接口（方法 路径） |',
    '|---|---|',
    ...callRows,
    '',
    '> 只收字面量路径；`Console.request(someVar)` 这类变量调用未计入。',
].join('\n'), 'utf8');

fs.writeFileSync(`${OUT}/envelope-map.md`, [
    `# 接口信封对照表（P0 · ${stamp} 静态扫描）`,
    '',
    '形态定义：**A** 只有 `code` · **B** `code` + `success` 双写 · **C** 只有 `success` · **D** 裸对象（无标识）',
    '',
    `统计：A=${summary.A} · B=${summary.B} · C=${summary.C} · D=${summary.D}`,
    '',
    '| 形态 | 方法 | 路径 | Java 方法 | 出处 |',
    '|---|---|---|---|---|',
    ...envRows,
    '',
    '> 判定依据是方法体内是否出现 `put("code"` / `put("success"`。',
    '> 一个方法里出现多种返回分支（成功走 success、失败走 code）时，本表取「出现了哪些键」的并集，',
    '> 实际分支差异要在 P2 逐个复核。',
].join('\n'), 'utf8');

console.log(`pages=${pageRows.length} calls=${callRows.length} endpoints=${envRows.length} A/B/C/D=${summary.A}/${summary.B}/${summary.C}/${summary.D}`);

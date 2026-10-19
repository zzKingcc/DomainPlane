/**
 * 从国内 npm 镜像拉取 @tabler/core，只取出需要的两个 CSS 文件到 vendor/。
 * jsdelivr 在本机网络下 ECONNRESET，改用 registry.npmmirror.com 的 tarball。
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const DIR = 'D:/java/zishuati/DomainPlane/domainplane-server/src/main/resources/static/console/assets/vendor';
const TMP = 'D:/java/zishuati/DomainPlane/tools/.tmp';
const TGZ = path.join(TMP, 'tabler-core-1.6.1.tgz');
const URL = 'https://registry.npmmirror.com/@tabler/core/-/core-1.6.1.tgz';
const WANTED = [
    'package/dist/css/tabler.min.css',
    'package/dist/css/tabler-props.css',
];

fs.mkdirSync(DIR, { recursive: true });
fs.mkdirSync(TMP, { recursive: true });

const res = await fetch(URL, { redirect: 'follow' });
if (!res.ok) { console.log(`download FAIL ${res.status}`); process.exit(1); }
const buf = Buffer.from(await res.arrayBuffer());
fs.writeFileSync(TGZ, buf);
console.log(`downloaded ${buf.length} bytes`);

execFileSync('C:/Windows/System32/tar.exe', ['-xzf', TGZ, '-C', TMP, ...WANTED], { stdio: 'pipe' });

for (const rel of WANTED) {
    const src = path.join(TMP, rel);
    const dst = path.join(DIR, path.basename(rel));
    fs.copyFileSync(src, dst);
    console.log(`${path.basename(rel)}\t${fs.statSync(dst).size} bytes`);
}

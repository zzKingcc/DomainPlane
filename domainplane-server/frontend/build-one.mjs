/**
 * 单页重建：只构建指定的页面，产物直写 Spring Boot 静态目录。
 *
 *   node build-one.mjs knowledge
 *   node build-one.mjs knowledge infra
 *
 * 为什么不直接跑 build.mjs：它会把 11 个页面一起重写，git diff 里就混进一堆
 * "没打算改"的行（多半是旧产物落后于 src）。改一页只重建一页，review 才看得清。
 *
 * 产物形态必须与 build.mjs 一致：iife / es2015 / 不 minify / charset ascii
 * （中文转 \uXXXX；静态资源本身是 UTF-8，但历史产物就是这个形态）。
 */
import { build } from 'esbuild';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.resolve(HERE, '../src/main/resources/static/console/assets');
const names = process.argv.slice(2);

if (!names.length) {
    console.error('usage: node build-one.mjs <page-name> [...]');
    process.exit(1);
}

for (const name of names) {
    const entry = path.join(HERE, 'src/pages', name + '.js');
    if (!fs.existsSync(entry)) {
        console.error('[frontend] 没有这个页面：' + entry);
        process.exit(1);
    }
    await build({
        entryPoints: [entry],
        bundle: true,
        format: 'iife',
        target: ['es2015'],
        outfile: path.join(OUT, 'page-' + name + '.js'),
        logLevel: 'info',
        minify: false,
        charset: 'ascii'
    });
}

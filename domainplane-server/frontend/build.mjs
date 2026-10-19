/**
 * P1 · 管控台前端构建
 *
 * 多 entry：console.js（共享库）+ page-*.js（每页一个入口）。
 * 产物直写 Spring Boot 静态目录，不进 jar 的源码留在 frontend/src。
 *
 *   node build.mjs          一次性构建
 *   node build.mjs --watch  增量监听
 */
import { build, context } from 'esbuild';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.resolve(HERE, '../src/main/resources/static/console/assets');

/** entry 名即产物名：console → console.js，page-login → page-login.js */
const ENTRIES = {
    console: path.join(HERE, 'src/console.js'),
    'page-login': path.join(HERE, 'src/pages/login.js'),
};

const options = {
    entryPoints: ENTRIES,
    bundle: true,
    format: 'iife',
    target: ['es2015'],
    outdir: OUT,
    logLevel: 'info',
    // 不加 minify：产物要能直接读，排查问题时不用回源映射
    minify: false,
};

const watch = process.argv.includes('--watch');

if (watch) {
    const ctx = await context(options);
    await ctx.watch();
    console.log('[frontend] watching ' + Object.keys(ENTRIES).join(', '));
} else {
    await build(options);
    console.log('[frontend] built ' + Object.keys(ENTRIES).join(', ') + ' -> ' + OUT);
}

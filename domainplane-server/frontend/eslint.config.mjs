/**
 * P3 · ESLint flat config
 *
 * 面向「ES5 平移过来的老代码」：规则刻意保持最小集，
 * 目标是挡住新错误（未声明变量、松散相等），不是一次性清洗历史。
 *
 * src/core/** 是 ESM（被 esbuild bundle），其余是 script 形态的 IIFE。
 */
import globals from 'globals';

const shared = {
    'no-unused-vars': 'warn',
    'no-undef': 'error',
    eqeqeq: ['error', 'smart'],    // 允许 == null 这类惯用写法
    'no-console': 'off',
};

export default [
    {
        files: ['src/core/**/*.js', 'src/console.js'],   // console.js 顶部有 import（注入 api 层）
        languageOptions: {
            ecmaVersion: 2022,
            sourceType: 'module',
            globals: { ...globals.browser },
        },
        rules: shared,
    },
    {
        files: ['src/**/*.js'],
        ignores: ['src/core/**', 'src/console.js'],
        languageOptions: {
            ecmaVersion: 2022,
            sourceType: 'script',          // 老代码是 IIFE / 顶层 script，不是 module
            globals: {
                ...globals.browser,
                Console: 'readonly',       // console.js 自己挂到 window 的出口
            },
        },
        rules: shared,
    },
];

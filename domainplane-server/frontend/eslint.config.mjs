/**
 * P3 · ESLint flat config
 *
 * 面向「ES5 平移过来的老代码」：规则刻意保持最小集，
 * 目标是挡住新错误（未声明变量、松散相等），不是一次性清洗历史。
 */
import globals from 'globals';

export default [
    {
        files: ['src/**/*.js'],
        languageOptions: {
            ecmaVersion: 2022,
            sourceType: 'script',          // 老代码是 IIFE / 顶层 script，不是 module
            globals: {
                ...globals.browser,
                // console.js 自己挂到 window 的出口，页面脚本里直接用
                Console: 'readonly',
            },
        },
        rules: {
            'no-unused-vars': 'warn',
            'no-undef': 'error',
            eqeqeq: ['error', 'smart'],    // 允许 == null 这类惯用写法
            'no-console': 'off',
        },
    },
];

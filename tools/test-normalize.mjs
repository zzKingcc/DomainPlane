/** P2 · normalize unit test: 4 envelope shapes + both exits.
 *  Test names are ASCII on purpose — PowerShell + node console.log mangles CJK in log files. */
import { normalize, createApi } from '../domainplane-server/frontend/src/core/request.js';

const results = [];
function check(name, cond, extra) {
    results.push(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra !== undefined ? '  -> ' + extra : ''}`);
}

const helpers = { sanitizeError: (t) => `[clean]${t}` };

/* ---- normalize: 3-tier fallback ---- */
check('A ok code:0', normalize({ code: 0, domains: [] }, helpers).ok === true);
check('A fail code:10001', normalize({ code: 10001, detail: 'boom' }, helpers).ok === false);
check('A fail picks detail and sanitizes',
    normalize({ code: 10001, detail: 'boom' }, helpers).message === '[clean]boom',
    normalize({ code: 10001, detail: 'boom' }, helpers).message);
check('B dual-key judged by code', normalize({ code: 0, success: true }, helpers).ok === true);
check('B code!=0 judged as fail', normalize({ code: 7, success: true }, helpers).ok === false);
check('C ok success:true', normalize({ success: true, message: 'done' }, helpers).ok === true);
check('C fail success:false', normalize({ success: false, error: 'nope' }, helpers).ok === false);
check('C fail picks error',
    normalize({ success: false, error: 'nope' }, helpers).message === '[clean]nope',
    normalize({ success: false, error: 'nope' }, helpers).message);
check('D bare object treated as ok', normalize({ domains: [], instances: [] }, helpers).ok === true);
check('D empty object also ok', normalize({}, helpers).ok === true);
check('C has no code field at all (must not be misjudged)',
    normalize({ success: false }).code === undefined);

/* ---- both exits ---- */
const api = createApi(() => Promise.resolve({ code: 0, domains: ['a'] }),
    () => Promise.resolve({ success: true, message: 'ok' }), helpers);

api.get('/x').then((data) => {
    check('api.get returns data (not envelope)', data && data.domains[0] === 'a');
    return api.post('/y', {}).then((d) => {
        check('api.post returns data', d.message === 'ok');
    });
}).then(() => {
    const bad = createApi(() => Promise.resolve({ code: 10001, detail: 'no perm' }),
        () => Promise.resolve({}), helpers);
    return bad.get('/x')
        .then(() => check('api.get should reject on fail', false))
        .catch((e) => check('api.get rejects with sanitized message', e.message === '[clean]no perm', e.message));
}).then(() => {
    const bad2 = createApi(() => Promise.resolve({}),
        () => Promise.resolve({ success: false, error: 'kaboom' }), helpers);
    return bad2.post('/y', {})
        .then(() => check('api.post should reject on fail', false))
        .catch((e) => check('C-class fail now rejects (used to slip into then)', e.message === '[clean]kaboom', e.message));
}).then(() => {
    const bare = createApi(() => Promise.resolve({ instances: [] }), () => Promise.resolve({}), helpers);
    return bare.get('/z').then((d) => check('D-class bare object passes through', Array.isArray(d.instances)));
}).then(() => {
    const rawApi = createApi(() => Promise.resolve({ code: 3, detail: 'x' }), () => Promise.resolve({}), helpers);
    return rawApi.raw('/z').then((r) => check('api.raw exposes ok/code/message/data', r.ok === false && r.code === 3));
}).then(() => {
    console.log(results.join('\n'));
    const failed = results.filter((r) => r.startsWith('FAIL')).length;
    console.log(`\n${results.length - failed}/${results.length} passed`);
    process.exit(failed ? 1 : 0);
});

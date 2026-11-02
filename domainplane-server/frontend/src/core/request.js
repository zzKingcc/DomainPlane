/* ==========================================================================
   P2 · 信封归一化层

   全站接口共有四种信封形态（分类与实测数量见 docs/管控台改造记录.md 第 2 节）：
     A  只有 code
     B  code + success 双写
     C  只有 success，无 code
     D  裸对象，没有任何标识

   「非 0 即失败」一条规则套全场会两头出错：
     - 沿用 r.code && r.code !== 0 → C 类失败被静默吞掉（漏报）
     - 改成 if (r.code !== 0) throw → C 类全部误报失败（误报）
   所以必须按 code → success → 裸对象 三级降级。
   ========================================================================== */

/** 文案优先级与 console.js 现状一致：detail 优先于 error，再优于 message。
 *  helpers.sanitizeError 由 console.js 注入，负责抹掉响应里可能带出的凭据。 */
function pick(raw, helpers) {
    if (!raw || typeof raw !== 'object') { return ''; }
    var text = raw.detail || raw.error || raw.message || '';
    if (helpers && typeof helpers.sanitizeError === 'function') {
        return helpers.sanitizeError(text);
    }
    return String(text || '');
}

/**
 * 归一化：返回 { ok, code?, message, data }
 * @param {*} raw 接口原始返回体
 * @param {{sanitizeError:Function}} [helpers] 复用 console.js 的凭据清洗
 */
export function normalize(raw, helpers) {
    if (raw && typeof raw.code === 'number') {
        return {
            ok: raw.code === 0,
            code: raw.code,
            message: pick(raw, helpers),
            data: raw,
        };
    }
    if (raw && typeof raw.success === 'boolean') {
        return {
            ok: raw.success,
            message: pick(raw, helpers),
            data: raw,
        };
    }
    // D 类：没有标识的裸对象，视为成功
    return { ok: true, message: '', data: raw };
}

/**
 * 造 api 层。request / jsonRequest 由 console.js 注入（它持有 fetch 与
 * 10002 → redirectToLogin 的分流逻辑，不在这里重复实现）。
 */
export function createApi(request, jsonRequest, helpers) {
    function settle(raw) {
        var n = normalize(raw, helpers);
        if (!n.ok) {
            throw new Error(n.message || '操作失败');
        }
        return n.data;
    }

    return {
        /** 成功返回 data，失败 reject（页面 .then 里不再出现信封判断） */
        get: function (path) { return request(path).then(settle); },
        post: function (path, body) { return jsonRequest(path, 'POST', body).then(settle); },
        put: function (path, body) { return jsonRequest(path, 'PUT', body).then(settle); },
        del: function (path) { return jsonRequest(path, 'DELETE').then(settle); },

        /** 需要自己拼文案 / 判断的场景：返回归一化后的完整对象 */
        raw: function (path) {
            return request(path).then(function (raw) {
                var n = normalize(raw, helpers);
                return { ok: n.ok, code: n.code, message: n.message, data: n.data };
            });
        },

        normalize: normalize,
    };
}

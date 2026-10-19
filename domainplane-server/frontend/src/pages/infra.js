/* ==========================================================================
   P1 · infra 页脚本
   自 infra.html 的内联 <script> 机械平移（1 块合并），逻辑未改。
   ========================================================================== */
(function () {
/* 字段表：[输入框 id, 接口字段名, 类型(s=字符串 / n=数字)]，一处定义驱动全页 */
    var MAP = {
        es: [
            ['esHost', 'host', 's'],
            ['esPort', 'port', 'n'],
            ['esScheme', 'scheme', 's'],
            ['esUsername', 'username', 's'],
            ['esPassword', 'password', 's'],
            ['esConnectTimeout', 'connectTimeout', 'n'],
            ['esSocketTimeout', 'socketTimeout', 'n']
        ],
        redis: [
            ['redisHost', 'host', 's'],
            ['redisPort', 'port', 'n'],
            ['redisPassword', 'password', 's'],
            ['redisDatabase', 'database', 'n']
        ]
    };
    var RESULT = { es: 'esResult', redis: 'redisResult' };
    var DIRTY = { es: 'esDirty', redis: 'redisDirty' };
    var BADGE = { es: 'esBadge', redis: 'redisBadge' };
    var LABEL = { es: 'Elasticsearch', redis: 'Redis' };
    var KEY_INPUT = { es: 'esPassword', redis: 'redisPassword' };
    var KEY_HELP = { es: 'esKeyHelp', redis: 'redisKeyHelp' };
    var KEY_EMPTY_HINT = { es: '未设置（留空则不发送认证头）', redis: '未设置（无密码模式）' };

    var saved = { es: {}, redis: {} };
    var CONFIGURED = { es: false, redis: false };
    var SOURCE = { es: '-', redis: '-' };

    function el(id) { return document.getElementById(id); }

    function group(which) {
        var out = {};
        MAP[which].forEach(function (f) {
            var raw = el(f[0]).value.trim();
            if (f[2] === 'n') {
                out[f[1]] = raw === '' ? null : Number(raw);
            } else {
                out[f[1]] = raw === '' ? null : raw;
            }
        });
        return out;
    }
    function collectAll() { return { es: group('es'), redis: group('redis') }; }

    function restore(which, src) {
        MAP[which].forEach(function (f) {
            var v = src[f[1]];
            el(f[0]).value = (v == null) ? '' : v;
        });
    }

    /* 只跟「已保存值」比对：口令框服务端从不回填，快照里本就是空，因此不会误报 */
    function refreshDirty(which) {
        var list = which ? [which] : ['es', 'redis'];
        list.forEach(function (w) {
            var dirty = JSON.stringify(group(w)) !== JSON.stringify(saved[w]);
            el(DIRTY[w]).style.display = dirty ? 'inline' : 'none';
        });
    }

    function setBadge(which, ok, text) {
        var node = el(BADGE[which]);
        node.textContent = text;
        node.className = 'mbadge ' + (ok ? 'ok' : 'off');
    }
    function setStatus(html, cls) {
        var node = el('infraStatus');
        node.className = 'status-line ' + cls;
        node.innerHTML = html;
    }
    function showResult(id, text, cls) {
        var box = el(id);
        box.textContent = text;
        box.className = 'result show ' + (cls || '');
    }
    /* 结构化结果：探测返回的不只是"连上了"，还有版本/发行版/兼容性判读/IK 状态 */
    function showHtmlResult(id, html, cls) {
        var box = el(id);
        box.innerHTML = html;
        box.className = 'result show ' + (cls || '');
    }
    function applyKeyHint(which, masked, isSet) {
        el(KEY_HELP[which]).textContent = isSet ? '当前已配置：' + masked : '当前未配置';
        el(KEY_INPUT[which]).placeholder = isSet ? '已配置，留空不变' : KEY_EMPTY_HINT[which];
    }

    /* ===== 探测结果渲染 ===== */
    function esc(v) { return Console.escapeHtml(v == null || v === '' ? '-' : v); }

    function facts(pairs) {
        return '<div class="facts">' + pairs.map(function (p) {
            return '<span class="fact">' + Console.escapeHtml(p[0]) + ' <b>' + esc(p[1]) + '</b></span>';
        }).join('') + '</div>';
    }

    /* IK 三态：可用 / 确认未安装 / 未探测。"查不到"绝不能渲染成"没有"。 */
    function ikChip(ik) {
        if (!ik) { return ''; }
        var tone = ik.available === true ? 'ok' : (ik.available === false ? 'no' : 'unknown');
        var label = ik.available === true ? 'IK 分词器：可用'
            : (ik.available === false ? 'IK 分词器：未安装' : 'IK 分词器：未探测');
        return '<span class="compat ' + tone + '">' + label + '</span>';
    }

    function renderEsProbe(data) {
        var compat = data.compatibility || {};
        var html = '<div class="probe">'
            + '<div class="summary">✅ ' + esc(data.message) + '</div>'
            + facts([['版本', data.version], ['发行版', data.buildFlavor], ['集群', data.clusterName]])
            + '<div class="facts">'
            + '<span class="compat ' + (compat.tone || 'unknown') + '">' + esc(compat.label) + '</span>'
            + ikChip(data.ik)
            + '</div>';
        if (compat.note) { html += '<div class="note">' + esc(compat.note) + '</div>'; }
        if (compat.hint) { html += '<div class="note strong">⚠️ ' + esc(compat.hint) + '</div>'; }
        if (data.ik && data.ik.note) {
            html += '<div class="note' + (data.ik.available === false ? ' strong' : '') + '">'
                + (data.ik.available === false ? '⚠️ ' : '') + esc(data.ik.note) + '</div>';
        }
        return html + '</div>';
    }

    function renderRedisProbe(data) {
        var databases = data.databases == null ? '-'
            : (data.database + ' / 共 ' + data.databases + ' 个库');
        var html = '<div class="probe">'
            + '<div class="summary">✅ ' + esc(data.message) + '</div>'
            + facts([['版本', data.version], ['拓扑', data.mode], ['库号', databases],
                     ['当前 key 数', data.dbSize]]);
        if (data.note) { html += '<div class="note strong">⚠️ ' + esc(data.note) + '</div>'; }
        return html + '</div>';
    }

    function renderStatus() {
        var esOk = CONFIGURED.es, rOk = CONFIGURED.redis;
        var msg;
        if (esOk && rOk) {
            msg = 'Elasticsearch 与 Redis 均已配置';
        } else if (esOk) {
            msg = 'Elasticsearch 已配置；Redis 未配置，会话记忆与中断断点不可用';
        } else if (rOk) {
            msg = 'Redis 已配置；Elasticsearch 未配置，知识库检索与灌库不可用';
        } else {
            msg = 'ES 与 Redis 均未配置。服务可以正常启动，但知识库与会话记忆不可用，请先填写地址。';
        }
        setStatus('<span>' + msg + '</span>'
            + '<span class="pill">ES ' + (esOk ? '✓ ' : '✗ ') + Console.escapeHtml(SOURCE.es) + '</span>'
            + '<span class="pill">Redis ' + (rOk ? '✓ ' : '✗ ') + Console.escapeHtml(SOURCE.redis) + '</span>',
            (esOk && rOk) ? 'ok' : 'warn');
    }

    function loadInfra() {
        setStatus('<span>加载中…</span>', 'warn');
        /* P2：/admin/infra GET 是 D 类裸对象 */
        Console.api.get('/admin/infra').then(function (s) {
            el('esHost').value = s.es.host || '';
            el('esPort').value = s.es.port == null ? '' : s.es.port;
            el('esScheme').value = s.es.scheme || '';
            el('esUsername').value = s.es.username || '';
            el('esConnectTimeout').value = s.es.connectTimeout == null ? '' : s.es.connectTimeout;
            el('esSocketTimeout').value = s.es.socketTimeout == null ? '' : s.es.socketTimeout;
            el('esPassword').value = '';
            applyKeyHint('es', s.es.passwordMasked, !!s.es.passwordSet);

            el('redisHost').value = s.redis.host || '';
            el('redisPort').value = s.redis.port == null ? '' : s.redis.port;
            el('redisDatabase').value = s.redis.database == null ? '' : s.redis.database;
            el('redisPassword').value = '';
            applyKeyHint('redis', s.redis.passwordMasked, !!s.redis.passwordSet);

            el('settingsFileHint').textContent = '设置文件：' + (s.settingsFile || '-')
                + '（知识库索引 ' + (s.indexPattern || '-') + '）';

            CONFIGURED.es = !!s.esConfigured;
            CONFIGURED.redis = !!s.redisConfigured;
            SOURCE.es = s.esSource || '-';
            SOURCE.redis = s.redisSource || '-';
            setBadge('es', CONFIGURED.es, CONFIGURED.es ? '已配置' : '未配置');
            setBadge('redis', CONFIGURED.redis, CONFIGURED.redis ? '已配置' : '未配置');
            renderStatus();

            /* 基线：dirty 比对与「清空表单」的参照都取自"刚填好的输入框" */
            saved.es = group('es');
            saved.redis = group('redis');
            refreshDirty();
        }).catch(function (e) {
            setStatus('<span>读取存储配置失败：' + Console.escapeHtml(e.message) + '</span>', 'warn');
        });
    }

    function testConnection(which, btn) {
        btn.disabled = true;
        showResult(RESULT[which], '测试中…（会真实建立一次连接，并探测版本与能力）', '');
        /* P2：/admin/infra/test 是 C 类（只有 success）。
           原来的 success 两态合并：业务失败由 api.post 的 reject 承担，
           message 已由 normalize 的 pick() 走过 sanitizeError（detail||error||message），
           所以这里直接显示 e.message，不必再手动洗 data.error。 */
        Console.api.post('/admin/infra/test?type=' + encodeURIComponent(which), collectAll())
            .then(function (data) {
                showHtmlResult(RESULT[which],
                    which === 'redis' ? renderRedisProbe(data) : renderEsProbe(data), 'ok');
            })
            .catch(function (e) {
                console.debug('[domainplane] 连接测试失败：', e);
                showResult(RESULT[which], '连接失败：' + e.message, 'err');
            })
            .then(function () { btn.disabled = false; });
    }

    /* 清空表单 = 只清本卡片输入框，纯前端：不发请求、不动已保存配置。
       保存接口是"留空即回落 yaml"，所以清空后再保存 = 把本卡片回退到 yaml 配置。
       口令清空后语义是"不修改"，不会把服务端已存的密码抹掉。 */
    function clearForm(which) {
        restore(which, {});
        refreshDirty(which);
        showResult(RESULT[which],
            '已清空「' + LABEL[which] + '」表单内容（仅本页，尚未生效）。'
            + '点「保存并生效」后，留空字段会回落到服务端 yaml 配置；口令留空表示保持不变。', '');
    }

    /* 保存接口是整对象覆盖：另一张卡片必须用已保存值占位，否则它已有的配置会被抹掉 */
    function buildPayload(which) {
        var all = collectAll();
        all[which === 'es' ? 'redis' : 'es'] = saved[which === 'es' ? 'redis' : 'es'];
        return all;
    }

    /* Redis 换地址或换库号 = 换数据源，历史会话与断点不迁移，必须先问一句 */
    function redisTargetChanged() {
        var cur = group('redis');
        return String(saved.redis.host == null ? '' : saved.redis.host) !== String(cur.host == null ? '' : cur.host)
            || String(saved.redis.database == null ? '' : saved.redis.database)
                !== String(cur.database == null ? '' : cur.database);
    }

    function afterSaved(which, data) {
        el(KEY_INPUT[which]).value = '';
        saved[which] = group(which);
        refreshDirty(which);
        applyKeyHint(which, data[which + 'PasswordMasked'], !!data[which + 'PasswordSet']);
        CONFIGURED[which] = which === 'es' ? !!data.esConfigured : !!data.redisConfigured;
        SOURCE[which] = (which === 'es' ? data.esSource : data.redisSource) || SOURCE[which];
        setBadge(which, CONFIGURED[which], CONFIGURED[which] ? '已配置' : '未配置');
        renderStatus();
    }

    function saveInfra(which, btn) {
        if (which === 'redis' && redisTargetChanged()) {
            var ok = window.confirm(
                '⚠️ 你修改了 Redis 的地址或库号，这等于换了一个数据源。\n\n'
                + '平台的历史会话记忆、以及未完成的检查点（等待人工确认的取消/退款单断点）'
                + '都留在原来的地址/库号里，不会自动迁移过来；'
                + '表现为"历史对话查不到、待确认的断点丢失"。\n\n'
                + '确认继续保存吗？');
            if (!ok) {
                showResult(RESULT.redis,
                    '已取消，本次未保存（配置保持原样）。换 Redis 前请确认旧库数据不再需要。', 'err');
                return;
            }
        }
        btn.disabled = true;
        showResult(RESULT[which], '保存中…', '');
        /* P2：/admin/infra POST 是 C 类，`if (!data.success)` 两态由 reject 承担 */
        Console.api.post('/admin/infra', buildPayload(which))
            .then(function (data) {
                afterSaved(which, data);
                var msg = '✅ 「' + LABEL[which] + '」已保存并立即生效（来源：'
                    + (which === 'es' ? data.esSource : data.redisSource) + '）。';
                showResult(RESULT[which], msg, 'ok');
            })
            .catch(function (e) {
                showResult(RESULT[which], '❌ 保存失败：' + e.message, 'err');
            })
            .then(function () { btn.disabled = false; });
    }

    Console.renderSidebar('infra');

    /* 任何输入变化都重算 dirty 标记 */
    var ALL_INPUTS = MAP.es.concat(MAP.redis).map(function (f) { return f[0]; });
    ALL_INPUTS.forEach(function (id) {
        el(id).addEventListener('input', function () { refreshDirty(); });
    });

    el('reloadBtn').addEventListener('click', loadInfra);
    el('testEsBtn').addEventListener('click', function () { testConnection('es', this); });
    el('testRedisBtn').addEventListener('click', function () { testConnection('redis', this); });
    el('clearEsBtn').addEventListener('click', function () { clearForm('es'); });
    el('clearRedisBtn').addEventListener('click', function () { clearForm('redis'); });
    el('saveEsBtn').addEventListener('click', function () { saveInfra('es', this); });
    el('saveRedisBtn').addEventListener('click', function () { saveInfra('redis', this); });

    Console.ready(loadInfra);
})();

/* ==========================================================================
   存储配置页脚本
   —— 01 Redis：平台自身基础设施，单例
   —— 02 连接：两张可编辑的表（关键字 / 向量），每行是一台具体实例
   —— 03 知识体：只读回显（被哪些连接用着），管理在「知识库」页

   两段是「用途」不是「产品」：ES 既能做关键字也能做向量，所以它可以在两张表里
   各出现一次，各填各的地址；地址一致时由后端按协议+主机+端口判定为同一台实例。

   v2 起知识体有自己的地址 /admin/bodies：它是数据实体（唯一名 + 冻结索引 + 原文落点），
   不该由连接池页来建。本页因此不再提交 bodies——提交就会把体的 slug / index 冲掉。
   ========================================================================== */
(function () {

    /* 字段表：[输入框 id, 接口字段名, 类型(s=字符串 / n=数字)]，一处定义驱动全页 */
    var REDIS_FIELDS = [
        ['redisHost', 'host', 's'],
        ['redisPort', 'port', 'n'],
        ['redisPassword', 'password', 's'],
        ['redisDatabase', 'database', 'n']
    ];
    var ES_FIELDS = [
        ['esHost', 'host', 's'],
        ['esPort', 'port', 'n'],
        ['esScheme', 'scheme', 's'],
        ['esUsername', 'username', 's'],
        ['esPassword', 'password', 's'],
        ['esConnectTimeout', 'connectTimeout', 'n'],
        ['esSocketTimeout', 'socketTimeout', 'n']
    ];
    var QD_FIELDS = [
        ['vectorUri', 'uri', 's'],
        ['vectorToken', 'token', 's'],
        ['vectorCollectionPrefix', 'collectionPrefix', 's'],
        ['vectorMetric', 'metric', 's']
    ];
    /* 2-2 的 ES 预设：与 2-1 同一套字段，独立的输入框（两个弹窗互不共享状态） */
    var VES_FIELDS = [
        ['vesHost', 'host', 's'],
        ['vesPort', 'port', 'n'],
        ['vesScheme', 'scheme', 's'],
        ['vesUsername', 'username', 's'],
        ['vesPassword', 'password', 's'],
        ['vesConnectTimeout', 'connectTimeout', 'n'],
        ['vesSocketTimeout', 'socketTimeout', 'n']
    ];

    var NAME_HELP = '连接名全局唯一（跨两张表也不能重名）；名称会出现在知识体的连接下拉里。';
    var FOOT_HINT_ES = '「测试连接」额外报版本、兼容性判读与 <b>IK 分词器</b>状态。';

    /* 服务端那一份：连接、知识体、Redis 已保存值 */
    var STATE = { redis: {}, connections: [], bodies: [], savedBodies: [] };
    var FLAGS = { es: false, redis: false, vector: false, esSource: '-', redisSource: '-', vectorSource: '-' };

    function el(id) { return document.getElementById(id); }

    /* 写文案 / 切显隐一律走这三个：模板里缺这个节点时跳过，不要让 null 抛出去。
       HTML 与 src 是两棵树，漏同步是常态（历史上就漏过 settingsFileHint / esFootHint
       这一批）；裸 el(id).textContent 一抛，loadInfra 后面的 renderConns / renderProfiles
       全不执行，页面上就永远停在"加载中…"，而且控制台之外看不出任何原因。 */
    function setText(id, value) {
        var node = el(id);
        if (node) { node.textContent = value; }
    }
    function setHtml(id, value) {
        var node = el(id);
        if (node) { node.innerHTML = value; }
    }
    function setHidden(id, hidden) {
        var node = el(id);
        if (node) { node.hidden = hidden; }
    }

    /* ===== 表单读写 ===== */
    /* 字段表驱动读写。模板里少一个输入框时不要整页挂掉：跳过缺失的那个，
       否则 openModal 里的异常会被事件派发吞掉，表现为"点新增没反应"。 */
    function readFields(fields) {
        var out = {};
        fields.forEach(function (f) {
            var node = el(f[0]);
            if (!node) { return; }
            var raw = node.value.trim();
            if (f[2] === 'n') {
                out[f[1]] = raw === '' ? null : Number(raw);
            } else {
                out[f[1]] = raw === '' ? null : raw;
            }
        });
        return out;
    }
    function writeFields(fields, src) {
        fields.forEach(function (f) {
            var node = el(f[0]);
            if (!node) { return; }
            var v = src ? src[f[1]] : null;
            node.value = (v == null) ? '' : v;
        });
    }

    /* ===== 提示与结果 ===== */
    function setStatus(html, cls) {
        var node = el('infraStatus');
        node.className = 'status-line ' + cls;
        node.innerHTML = html;
    }
    function showResult(id, text, cls) {
        var box = el(id);
        if (!box) { return; }
        box.textContent = text;
        box.className = 'result show ' + (cls || '');
    }
    function showHtmlResult(id, html, cls) {
        var box = el(id);
        if (!box) { return; }
        box.innerHTML = html;
        box.className = 'result show ' + (cls || '');
    }
    function hideResult(id) {
        /* 别用 showResult('') —— 那会挂上 .show，弹窗里留一条空灰条 */
        var box = el(id);
        if (!box) { return; }
        box.className = 'result';
        box.textContent = '';
    }
    function setNameHelp(id, text, isErr) {
        var node = el(id);
        node.className = 'help' + (isErr ? ' err' : '');
        node.textContent = text;
    }
    function esc(v) { return Console.escapeHtml(v == null || v === '' ? '-' : v); }

    /* ===== 探测结果渲染 ===== */
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

    function renderVectorProbe(data) {
        var html = '<div class="probe">'
            + '<div class="summary">✅ ' + esc(data.message) + '</div>'
            + facts([['版本', data.version], ['距离度量', data.metric],
                     ['已有集合数', data.collectionCount]]);
        /* note 与 message 常是同一句（探测接口把提示语当结论用），重复就只留 message */
        if (data.note && data.note !== data.message) {
            html += '<div class="note strong">⚠️ ' + esc(data.note) + '</div>';
        }
        return html + '</div>';
    }

    /* ===== 状态行 ===== */
    function renderStatus() {
        var esOk = FLAGS.es, rOk = FLAGS.redis, vOk = FLAGS.vector;
        var msg;
        if (esOk && rOk) {
            msg = vOk
                ? 'Redis、关键字连接与向量连接均已就绪'
                : 'Redis 与关键字连接已就绪；向量槽未绑定，知识库只走关键词路';
        } else if (esOk) {
            msg = '关键字连接已就绪；Redis 未配置，会话记忆与中断断点不可用';
        } else if (rOk) {
            msg = 'Redis 已配置；关键字槽未绑定，知识库检索与灌库不可用';
        } else {
            msg = 'Redis 与关键字连接均未配置。服务可以正常启动，但会话记忆与知识库不可用，请先填 Redis 地址。';
        }
        setStatus('<span>' + msg + '</span>'
            + '<span class="pill">Redis ' + (rOk ? '✓ ' : '✗ ') + Console.escapeHtml(FLAGS.redisSource) + '</span>'
            + '<span class="pill">关键字 ' + (esOk ? '✓ ' : '✗ ') + Console.escapeHtml(FLAGS.esSource) + '</span>'
            + '<span class="pill">向量 ' + (vOk ? '✓ ' : '✗ ') + Console.escapeHtml(FLAGS.vectorSource) + '</span>',
            (esOk && rOk) ? 'ok' : 'warn');
    }

    /* ===== 连接 ===== */
    function normConn(c) {
        /* 老配置没有 usage：按类型给个合理的默认（ES 归关键字、Qdrant 归向量） */
        c.usage = c.usage || (c.type === 'qdrant' ? 'vector' : 'keyword');
        c.name = c.name || c.id;
        return c;
    }
    function findConn(id) {
        for (var i = 0; i < STATE.connections.length; i++) {
            if (STATE.connections[i].id === id) { return STATE.connections[i]; }
        }
        return null;
    }
    function connById(id) { return id ? findConn(id) : null; }

    function addressOf(c) {
        if (c.type === 'qdrant') {
            var v = c.vector || {};
            return v.uri
                ? v.uri + (v.metric ? ' · ' + v.metric : '')
                    + (v.collectionPrefix ? ' · 前缀 ' + v.collectionPrefix : '')
                : '未填地址';
        }
        if (c.type === 's3') {
            var s3 = c.s3 || {};
            if (!s3.bucket || !s3.endpoint) { return '未填地址'; }
            return s3.bucket + ' @ ' + s3.endpoint + (s3.prefix ? ' · 前缀 ' + s3.prefix : '');
        }
        var es = c.es || {};
        return es.host
            ? es.host + ':' + (es.port || 9200) + ' · ' + (es.scheme || 'http')
                + (es.username ? ' · ' + es.username : '')
            : '未填地址';
    }

    /* 被哪些知识体引用（两路 + 原文落点）：改连接前先看影响面。
       原文的缺省值是 local，所以"没写 raw 的体"也算在用本地落点。 */
    function refsOf(id) {
        var names = [];
        STATE.bodies.forEach(function (b) {
            var isRaw = b.raw ? b.raw === id : (id === 'local');
            if (b.keyword === id || b.vector === id || isRaw) { names.push(b.name || b.id); }
        });
        return names;
    }

    function rowHtml(c) {
        var dot = c.type === 'qdrant' ? 'dot-vec' : (c.type === 's3' ? 'dot-blob' : 'dot-es');
        var refs = refsOf(c.id);
        var usable = c.usable !== false;
        return '<tr class="conn-row">'
            + '<td><span class="conn-name"><i class="conn-dot ' + dot + '"></i>'
            + Console.escapeHtml(c.id) + '</span></td>'
            + '<td class="mono">' + Console.escapeHtml(addressOf(c)) + '</td>'
            + '<td><span class="mbadge ' + (usable ? 'ok' : 'off') + '">'
            + (usable ? '已配置' : '未配置') + '</span></td>'
            + '<td><span class="refs">' + (refs.length
                ? refs.map(function (n) {
                    return '<span class="ref-chip">' + Console.escapeHtml(n) + '</span>';
                }).join('')
                : '<span class="none">未被引用</span>') + '</span></td>'
            + '<td class="ta-r nowrap">'
            + '<button class="linkbtn" data-edit="' + Console.escapeHtml(c.id) + '">编辑</button>'
            + '<button class="linkbtn danger" data-del="' + Console.escapeHtml(c.id) + '">删除</button>'
            + '</td></tr>';
    }

    function emptyRow(text) {
        return '<tr><td colspan="5" class="muted-sm">' + Console.escapeHtml(text) + '</td></tr>';
    }

    function renderConns() {
        var kw = [], vec = [], raw = [];
        STATE.connections.forEach(function (c) {
            // s3 不参与召回，只可能是原文落点：别塞进 2-1 / 2-2 两张召回路表
            if (c.type === 's3') { raw.push(c); return; }
            if (c.usage === 'vector') { vec.push(c); } else { kw.push(c); }
        });
        el('connBodyEs').innerHTML = kw.length ? kw.map(rowHtml).join('') : emptyRow('还没有关键字连接，点「+ 新增」加一台');
        el('connBodyVec').innerHTML = vec.length ? vec.map(rowHtml).join('') : emptyRow('还没有向量连接，点「+ 新增」加一台');
        /* 原文表第一行永远是内置本地盘（它不是一个连接，但知识体可以选它） */
        el('connBodyTxt').innerHTML = localRawRowHtml()
            + (raw.length ? raw.map(rowHtml).join('') : '');
        el('connCount').textContent = STATE.connections.length + ' 个连接';
    }

    /** 内置本地原文存储那一行：不可编辑、不可删除，但会被知识体引用 */
    function localRawRowHtml() {
        var refs = refsOf('local');
        return '<tr class="conn-row">'
            + '<td><span class="conn-name"><i class="conn-dot dot-blob"></i>本地原文存储</span></td>'
            + '<td class="mono">应用数据目录 · 内置</td>'
            + '<td><span class="mbadge ok">已启用</span></td>'
            + '<td><span class="refs">' + (refs.length
                ? refs.map(function (n) {
                    return '<span class="ref-chip">' + Console.escapeHtml(n) + '</span>';
                }).join('')
                : '<span class="none">未被引用</span>') + '</span></td>'
            + '<td class="ta-r"><span class="muted-sm">内置</span></td></tr>';
    }

    /* ===== 知识体 ===== */
    /* 一个体最多两路：关键字（全文检索，只能 ES）、向量（kNN，ES / Qdrant）。
       两路都填 = 混合召回；只填一路 = 单路。原文不在这里——它一律写内置磁盘，
       没有"配到哪台机器"这回事。后端保存时按同一套规则再拦一次（接口可以手工构造）。 */
    var SLOT_META = {
        keyword: {
            label: '关键字',
            hint: '全文检索（BM25）· 只能绑 Elasticsearch',
            empty: '（不做全文检索）',
            types: ['elasticsearch']
        },
        vector: {
            label: '向量',
            hint: 'kNN 语义检索 · Elasticsearch / Qdrant 都行',
            empty: '（不做语义检索）',
            types: ['elasticsearch', 'qdrant']
        }
    };
    var SLOT_ORDER = ['keyword', 'vector'];

    /* 这个体走几路——卡片头上那枚徽章 */
    function modesOf(b) {
        var hasKw = !!b.keyword;
        var hasVec = !!b.vector;
        if (hasKw && hasVec) { return { text: '关键字 + 向量', cls: 'ok' }; }
        if (hasKw) { return { text: '仅关键字', cls: 'ok' }; }
        if (hasVec) { return { text: '仅向量', cls: 'ok' }; }
        return { text: '未配', cls: 'off' };
    }

    function slotLine(label, control, hint, filled) {
        return '<div class="slot' + (filled ? '' : ' empty') + '">'
            + '<span class="slot-k">' + label + '</span>'
            + control
            + '<span class="slot-h">' + hint + '</span>'
            + '</div>';
    }

    /* 只读回显：体的增删改在「知识库」页（它是数据实体，不该由连接池页来建）。
       这里保留只读列表，是为了回答"我这台连接被谁用着"——删连接前必须能看见影响面。 */
    function bodyCard(b) {
        var modes = modesOf(b);
        var index = b.index || '';
        var slots = ['keyword', 'vector'].map(function (slot) {
            var id = slot === 'keyword' ? b.keyword : b.vector;
            var c = id ? findConn(id) : null;
            var text = id ? ((c && (c.name || c.location)) || id) : '未配';
            return '<div class="slot' + (id ? '' : ' empty') + '">'
                + '<span class="slot-k">' + SLOT_META[slot].label + '</span>'
                + '<span class="slot-v-ro' + (id ? '' : ' muted-sm') + '">'
                + Console.escapeHtml(text) + '</span>'
                + '<span class="slot-h">' + (id && c && c.usable === false ? '连接未填地址' : SLOT_META[slot].hint) + '</span>'
                + '</div>';
        }).join('');
        return '<article class="profile">'
            + '<header class="profile-head">'
            + '<span class="p-dot"></span>'
            + '<h4>' + Console.escapeHtml(b.name || b.id) + '</h4>'
            + '<span class="mbadge ' + modes.cls + '">' + modes.text + '</span>'
            + '<span class="spacer"></span>'
            + '<a class="linkbtn" href="knowledge.html">管理</a>'
            + '</header>'
            + (index ? '<div class="muted-sm" style="margin:2px 0 6px;">索引 <code>' + Console.escapeHtml(index) + '</code></div>' : '')
            + slots
            + '</article>';
    }

    function renderProfiles() {
        var box = el('profileList');
        if (!STATE.bodies.length) {
            box.innerHTML = '<div class="muted-sm">还没有知识体</div>';
        } else {
            box.innerHTML = STATE.bodies.map(bodyCard).join('');
        }
        el('profCount').textContent = STATE.bodies.length + ' 个知识体';
    }

    /* ===== 提交 ===== */
    /* 只提交连接与 Redis：知识体已经搬到 /admin/bodies，这里刻意不带 bodies——
       带上就是从"只读回显"反推着写配置，会把体的 slug / index / 原文落点冲掉。 */
    function body() {
        return {
            redis: readFields(REDIS_FIELDS),
            connections: STATE.connections
        };
    }

    async function postJson(payload) {
        /* P2：/admin/infra POST 是 C 类，`if (!data.success)` 两态由 reject 承担 */
        return Console.api.post('/admin/infra', payload);
    }

    /* 整份提交：先乐观改本地状态（好让"引用"立刻更新），失败再回滚。
       不做增量补丁是有意的——"少传一条当没动过"会让删掉的行在重启后复活。 */
    async function commit(rollback, resultId, okText, btn) {
        if (btn) { btn.disabled = true; btn.classList.add('btn-loading'); }
        showResult(resultId, '保存中…', '');
        try {
            var data = await postJson(body());
            /* 保存已成功，后面的"重新拉取"失败不能算保存失败——那会诱导用户再存一遍。
               这一条曾经是真坑：配置落库了、回读挂了，用户看到的是"保存失败"。 */
            try {
                await loadInfra(true);
            } catch (e) {
                Console.toast('已保存，但刷新页面数据失败：' + Console.sanitizeError(e.message),
                    { type: 'warning' });
                return data;
            }
            if (resultId) { showResult(resultId, '✅ ' + okText, 'ok'); }
            /* 自检结论跟着响应回来（保存路径只做落盘时，这个数组现在恒为空；
               「测试连接」的 redis 分支会带持久化建议，属于另一个接口） */
            var warns = (data && data.warnings) || [];
            if (warns.length) {
                Console.toast('已保存，但有 ' + warns.length + ' 条提醒：\n' + warns.join('\n'),
                    { type: 'warning', wide: true });
            } else {
                Console.toast(okText, { type: 'success' });
            }
            return data;
        } catch (e) {
            if (rollback) { rollback(); }
            renderConns();
            renderProfiles();
            showResult(resultId, '❌ 保存失败：' + e.message, 'err');
            return null;
        } finally {
            if (btn) { btn.disabled = false; btn.classList.remove('btn-loading'); }
        }
    }

    /* ===== 读取 ===== */
    async function loadInfra(quiet) {
        if (!quiet) { setStatus('<span>加载中…</span>', 'warn'); }
        /* P2：/admin/infra GET 是 D 类裸对象 */
        var s = await Console.api.get('/admin/infra');
        STATE.redis = s.redis || {};
        STATE.connections = (s.connections || []).map(normConn);
        STATE.bodies = s.bodies || [];

        writeFields(REDIS_FIELDS, {
            host: s.redis.host, port: s.redis.port, database: s.redis.database, password: null
        });
        applyRedisKeyHint(s.redis);

        FLAGS.es = !!s.esConfigured;
        FLAGS.redis = !!s.redisConfigured;
        FLAGS.vector = !!s.vectorSlotConfigured;
        FLAGS.esSource = s.esSource || '-';
        FLAGS.redisSource = s.redisSource || '-';
        FLAGS.vectorSource = s.vectorSlotSource || '-';
        renderStatus();

        /* 卡片头的小徽章与状态行同源，免得一处说已生效、一处说未配置 */
        var badge = el('redisBadge');
        badge.className = 'mbadge ' + (FLAGS.redis ? 'ok' : 'off');
        badge.textContent = FLAGS.redis ? '已生效' : '未配置';

        setText('settingsFileHint', '设置文件：' + (s.settingsFile || '-')
            + '（知识库索引 ' + (s.indexPattern || '-') + '）');
        renderConns();
        renderProfiles();
        return s;
    }

    function applyRedisKeyHint(redis) {
        var isSet = redis && redis.passwordSet;
        el('redisKeyHelp').textContent = isSet ? '当前已配置：' + redis.passwordMasked : '当前未配置';
        el('redisPassword').placeholder = isSet ? '已配置，留空不变' : '留空表示不修改';
    }
    function applyEsKeyHint(conn, helpId, inputId) {
        var es = (conn && conn.es) || {};
        var isSet = !!es.passwordSet;
        el(helpId).textContent = isSet ? '当前已配置：' + es.passwordMasked : '当前未配置';
        el(inputId).placeholder = isSet ? '已配置，留空不变' : '留空则不发送认证头';
    }
    function applyQdKeyHint(conn) {
        var v = (conn && conn.vector) || {};
        var isSet = !!v.tokenSet;
        el('vectorKeyHelp').textContent = isSet ? '当前已配置：' + v.tokenMasked : '当前未配置';
        el('vectorToken').placeholder = isSet ? '已配置，留空不变' : '留空则不发送鉴权头';
    }

    /* ===== 弹窗开关 ===== */
    function showModal(id) {
        el(id).hidden = false;
        document.body.style.overflow = 'hidden';
    }
    function hideModal(id) {
        el(id).hidden = true;
        document.body.style.overflow = '';
    }
    function resetPresetCards(pickId) {
        Array.prototype.forEach.call(
            document.querySelectorAll('#' + pickId + ' .preset-card'), function (c) {
                c.classList.remove('is-on');
            });
    }

    /* ===== 连接测试 ===== */
    function testConn(kind, payload, resultId, btn) {
        btn.disabled = true;
        showResult(resultId, '测试中…（会真实建立一次连接，并探测版本与能力）', '');
        /* P2：/admin/infra/test 是 C 类（只有 success）。
           业务失败由 api.post 的 reject 承担，message 已由 normalize 洗过（detail||error||message） */
        Console.api.post('/admin/infra/test?type=' + encodeURIComponent(kind), payload)
            .then(function (data) {
                var render = kind === 'redis' ? renderRedisProbe
                    : (kind === 'es' ? renderEsProbe
                        : (kind === 's3' ? renderS3Probe : renderVectorProbe));
                showHtmlResult(resultId, render(data), 'ok');
            })
            .catch(function (e) {
                console.debug('[domainplane] 连接测试失败：', e);
                showResult(resultId, '连接失败：' + e.message, 'err');
            })
            .then(function () { btn.disabled = false; });
    }

    /* ---------- 2-3 原文：S3 兼容对象存储（COS / OSS / MinIO 一套表单） ---------- */
    var s3Mode = 'new';
    var s3EditId = null;

    var S3_FIELDS = [
        ['s3Endpoint', 'endpoint', 's'],
        ['s3Bucket', 'bucket', 's'],
        ['s3Region', 'region', 's'],
        ['s3AccessKey', 'accessKey', 's'],
        ['s3SecretKey', 'secretKey', 's'],
        ['s3Prefix', 'prefix', 's']
    ];

    function openS3Modal(mode, id) {
        s3Mode = mode;
        s3EditId = id || null;
        var conn = connById(s3EditId);
        setText('connModalS3Title', mode === 'new' ? '新增对象存储连接' : ('编辑连接 · ' + id));
        setHidden('s3NameRow', mode !== 'new');
        hideResult('s3Result');
        setNameHelp('s3ConnNameHelp', '知识体的「原文落点」下拉里显示的就是这个名字。', false);
        if (mode === 'new') {
            el('s3ConnName').value = '';
            writeFields(S3_FIELDS, null);
            el('s3PathStyle').checked = false;
        } else {
            writeFields(S3_FIELDS, conn && conn.s3);
            el('s3PathStyle').checked = !!(conn && conn.s3 && conn.s3.pathStyle);
        }
        setText('saveS3Btn', mode === 'new' ? '加入连接' : '保存并生效');
        showModal('connModalS3');
    }

    function s3Payload() {
        var payload = readFields(S3_FIELDS) || {};
        payload.pathStyle = !!el('s3PathStyle').checked;
        return payload;
    }

    function renderS3Probe(data) {
        return '<b>连接正常</b> · ' + Console.escapeHtml(String(data.detail || '对象存储可达'));
    }

    async function saveS3(btn) {
        var name = s3Mode === 'new' ? el('s3ConnName').value.trim() : s3EditId;
        if (s3Mode === 'new') {
            if (!name) {
                setNameHelp('s3ConnNameHelp', '先给这个连接起个名字。', true);
                el('s3ConnName').focus();
                return false;
            }
            if (findConn(name)) {
                setNameHelp('s3ConnNameHelp', '已经有叫「' + name + '」的连接了，换一个。', true);
                el('s3ConnName').focus();
                return false;
            }
        }
        var conn = {
            id: name, name: name, type: 's3', usage: null,
            es: null, vector: null, s3: s3Payload()
        };
        var idx = -1;
        STATE.connections.forEach(function (c, i) { if (c.id === name) { idx = i; } });
        var prev = idx >= 0 ? STATE.connections[idx] : null;
        if (idx >= 0) { STATE.connections[idx] = conn; } else { STATE.connections.push(conn); }
        renderConns();
        var data = await commit(function () {
            if (prev) { STATE.connections[idx] = prev; } else { STATE.connections.pop(); }
        }, 's3Result', '连接「' + name + '」已保存并生效', btn);
        if (data) { hideModal('connModalS3'); }
        return !!data;
    }

    /* ---------- 2-1 关键字 ---------- */
    var esMode = 'new';
    var esEditId = null;

    function openEsModal(mode, id) {
        esMode = mode;
        esEditId = id || null;
        var conn = connById(esEditId);
        el('connModalEsTitle').textContent = mode === 'new' ? '新增关键字连接' : ('编辑连接 · ' + id);
        el('esPresetPick').hidden = mode !== 'new';
        setHidden('esPresetHint', mode !== 'new');
        el('esNameRow').hidden = mode !== 'new';
        /* 新增必须先点预设才铺表单（"选择之后可以配置对应内容"）；编辑跳过这一步 */
        el('esFormWrap').hidden = mode === 'new';
        resetPresetCards('esPresetPick');
        hideResult('esResult');
        setNameHelp('esConnNameHelp', NAME_HELP, false);
        if (mode === 'new') {
            el('esConnName').value = '';
            writeFields(ES_FIELDS, null);
            applyEsKeyHint(null, 'esKeyHelp', 'esPassword');
        } else {
            writeFields(ES_FIELDS, conn && conn.es);
            applyEsKeyHint(conn, 'esKeyHelp', 'esPassword');
        }
        el('saveEsBtn').textContent = mode === 'new' ? '加入连接' : '保存并生效';
        setHtml('esFootHint', FOOT_HINT_ES);
        showModal('connModalEs');
    }

    Array.prototype.forEach.call(
        document.querySelectorAll('#esPresetPick .preset-card'), function (card) {
            card.addEventListener('click', function () {
                Array.prototype.forEach.call(
                    document.querySelectorAll('#esPresetPick .preset-card'), function (c) {
                        c.classList.toggle('is-on', c === card);
                    });
                el('esFormWrap').hidden = false;
                el('esConnName').focus();
            });
        });

    async function saveEs(btn) {
        var name = esMode === 'new' ? el('esConnName').value.trim() : esEditId;
        if (esMode === 'new') {
            if (!name) {
                setNameHelp('esConnNameHelp', '先给这个连接起个名字。', true);
                el('esConnName').focus();
                return false;
            }
            if (findConn(name)) {
                setNameHelp('esConnNameHelp', '已经有叫「' + name + '」的连接了，换一个。', true);
                el('esConnName').focus();
                return false;
            }
        }
        var conn = {
            id: name, name: name, type: 'elasticsearch', usage: 'keyword',
            es: readFields(ES_FIELDS), vector: null
        };
        var idx = -1;
        STATE.connections.forEach(function (c, i) { if (c.id === name) { idx = i; } });
        var prev = idx >= 0 ? STATE.connections[idx] : null;
        if (idx >= 0) { STATE.connections[idx] = conn; } else { STATE.connections.push(conn); }
        renderConns();
        var data = await commit(function () {
            if (prev) { STATE.connections[idx] = prev; } else { STATE.connections.pop(); }
        }, 'esResult', '连接「' + name + '」已保存并生效', btn);
        if (data) { hideModal('connModalEs'); }
        return !!data;
    }

    /* ---------- 2-2 向量（两个预设：Qdrant / Elasticsearch） ---------- */
    var vecMode = 'new';
    var vecEditId = null;
    var vecPreset = 'qdrant';

    function openVecModal(mode, id) {
        vecMode = mode;
        vecEditId = id || null;
        var conn = connById(vecEditId);
        vecPreset = conn ? (conn.type === 'qdrant' ? 'qdrant' : 'es') : null;

        el('connModalVecTitle').textContent = mode === 'new' ? '新增向量连接' : ('编辑连接 · ' + id);
        el('vecPresetPick').hidden = mode !== 'new';
        setHidden('vecPresetHint', mode !== 'new');
        el('vecNameRow').hidden = mode !== 'new';
        resetPresetCards('vecPresetPick');
        hideResult('vectorResult');
        hideResult('vesResult');
        setNameHelp('vecConnNameHelp', NAME_HELP, false);

        if (mode === 'new') {
            el('vecConnName').value = '';
            el('vecFormQdrant').hidden = true;
            el('vecFormEs').hidden = true;
            writeFields(QD_FIELDS, null);
            writeFields(VES_FIELDS, null);
            applyQdKeyHint(null);
            applyEsKeyHint(null, 'vesKeyHelp', 'vesPassword');
        } else {
            var isQd = vecPreset === 'qdrant';
            el('vecFormQdrant').hidden = !isQd;
            el('vecFormEs').hidden = isQd;
            if (isQd) {
                writeFields(QD_FIELDS, conn.vector);
                applyQdKeyHint(conn);
            } else {
                writeFields(VES_FIELDS, conn.es);
                applyEsKeyHint(conn, 'vesKeyHelp', 'vesPassword');
            }
        }
        el('saveVectorBtn').textContent = mode === 'new' ? '加入连接' : '保存并生效';
        el('saveVecEsBtn').textContent = mode === 'new' ? '加入连接' : '保存并生效';
        setHtml('vectorFootHint', '「测试连接」额外报版本、距离度量与已有集合数。');
        showModal('connModalVec');
    }

    /* 预设卡：选谁铺谁的表单（Qdrant 填地址；ES 填这台实例的地址与认证） */
    Array.prototype.forEach.call(
        document.querySelectorAll('#vecPresetPick .preset-card'), function (card) {
            card.addEventListener('click', function () {
                vecPreset = card.getAttribute('data-preset');
                Array.prototype.forEach.call(
                    document.querySelectorAll('#vecPresetPick .preset-card'), function (c) {
                        c.classList.toggle('is-on', c === card);
                    });
                el('vecFormQdrant').hidden = vecPreset !== 'qdrant';
                el('vecFormEs').hidden = vecPreset !== 'es';
                el('vecConnName').focus();
            });
        });

    async function saveVec(btn, resultId) {
        var isEs = vecPreset === 'es';
        var name = vecMode === 'new' ? el('vecConnName').value.trim() : vecEditId;
        if (vecMode === 'new') {
            if (!name) {
                setNameHelp('vecConnNameHelp', '先给这个连接起个名字。', true);
                el('vecConnName').focus();
                return false;
            }
            if (findConn(name)) {
                setNameHelp('vecConnNameHelp', '已经有叫「' + name + '」的连接了，换一个。', true);
                el('vecConnName').focus();
                return false;
            }
        }
        var conn = {
            id: name, name: name, usage: 'vector',
            type: isEs ? 'elasticsearch' : 'qdrant',
            es: isEs ? readFields(VES_FIELDS) : null,
            vector: isEs ? null : readFields(QD_FIELDS)
        };
        var idx = -1;
        STATE.connections.forEach(function (c, i) { if (c.id === name) { idx = i; } });
        var prev = idx >= 0 ? STATE.connections[idx] : null;
        if (idx >= 0) { STATE.connections[idx] = conn; } else { STATE.connections.push(conn); }
        renderConns();
        var data = await commit(function () {
            if (prev) { STATE.connections[idx] = prev; } else { STATE.connections.pop(); }
        }, resultId, '连接「' + name + '」已保存并生效', btn);
        if (data) { hideModal('connModalVec'); }
        return !!data;
    }

    /* ===== Redis 保存（换地址/库号 = 换数据源，要先问一句） ===== */
    function redisTargetChanged() {
        var cur = readFields(REDIS_FIELDS);
        var tag = function (src) {
            return String(src.host == null ? '' : src.host) + '|' + String(src.database == null ? '' : src.database);
        };
        return tag(STATE.redis) !== tag(cur);
    }

    async function saveRedis(btn) {
        if (redisTargetChanged()) {
            var ok = await Console.confirm(
                '⚠️ 你修改了 Redis 的地址或库号，这等于换了一个数据源。\n\n'
                + '平台的历史会话记忆、以及未完成的检查点（等待人工确认的取消/退款单断点）'
                + '都留在原来的地址/库号里，不会自动迁移过来；'
                + '表现为"历史对话查不到、待确认的断点丢失"。\n\n'
                + '确认继续保存吗？',
                { title: '换 Redis 数据源', okText: '继续保存', danger: true });
            if (!ok) {
                showResult('redisResult',
                    '已取消，本次未保存（配置保持原样）。换 Redis 前请确认旧库数据不再需要。', 'err');
                return false;
            }
        }
        var data = await commit(null, 'redisResult', 'Redis 配置已保存并立即生效', btn);
        return !!data;
    }

    /* ===== 事件绑定 ===== */
    Console.renderSidebar('infra');

    el('reloadBtn').addEventListener('click', function () {
        loadInfra(false).catch(function (e) {
            setStatus('<span>读取存储配置失败：' + Console.escapeHtml(e.message) + '</span>', 'warn');
        });
    });
    el('saveRedisBtn').addEventListener('click', function () { saveRedis(this); });
    el('testRedisBtn').addEventListener('click', function () {
        testConn('redis', { redis: readFields(REDIS_FIELDS) }, 'redisResult', this);
    });

    Array.prototype.forEach.call(document.querySelectorAll('[data-add-conn]'), function (btn) {
        btn.addEventListener('click', function () {
            var seg = btn.getAttribute('data-add-conn');
            if (seg === 'es') { openEsModal('new'); }
            else if (seg === 's3') { openS3Modal('new'); }
            else { openVecModal('new'); }
        });
    });

    el('saveS3Btn').addEventListener('click', function () { saveS3(this); });
    el('cancelS3Btn').addEventListener('click', function () { hideModal('connModalS3'); });
    el('connModalS3Close').addEventListener('click', function () { hideModal('connModalS3'); });
    el('testS3Btn').addEventListener('click', function () {
        testConn('s3', {
            id: s3EditId || el('s3ConnName').value.trim(),
            name: el('s3ConnName').value.trim(),
            type: 's3',
            s3: s3Payload()
        }, 's3Result', this);
    });

    /* 表格里的编辑 / 删除（行是动态渲染的，用委托） */
    function bindTable(bodyId, seg) {
        el(bodyId).addEventListener('click', async function (e) {
            var t = e.target;
            if (!t.getAttribute) { return; }
            var edit = t.getAttribute('data-edit');
            if (edit) {
                if (seg === 'es') { openEsModal('edit', edit); }
                else if (seg === 's3') { openS3Modal('edit', edit); }
                else { openVecModal('edit', edit); }
                return;
            }
            var del = t.getAttribute('data-del');
            if (!del) { return; }
            var refs = refsOf(del);
            if (refs.length) {
                Console.toast('「' + del + '」正被知识体 ' + refs.join('、') + ' 使用，先在知识体里换掉再删',
                    { type: 'error' });
                return;
            }
            var ok = await Console.confirm('确定删除连接「' + del + '」吗？删除后立即生效。',
                { title: '删除连接', okText: '删除', danger: true });
            if (!ok) { return; }
            var idx = -1;
            STATE.connections.forEach(function (c, i) { if (c.id === del) { idx = i; } });
            if (idx < 0) { return; }
            var backup = STATE.connections.slice();
            STATE.connections.splice(idx, 1);
            renderConns();
            renderProfiles();
            /* 删除不在弹窗里，结果落在状态行，别用隐藏的 result 框 */
            setStatus('<span>正在删除连接「' + Console.escapeHtml(del) + '」…</span>', 'warn');
            try {
                await postJson(body());
                await loadInfra(true);
                Console.toast('已删除连接「' + del + '」', { type: 'success' });
            } catch (err) {
                STATE.connections = backup;
                renderConns();
                renderProfiles();
                setStatus('<span>删除失败：' + Console.escapeHtml(err.message) + '</span>', 'warn');
            }
        });
    }
    bindTable('connBodyEs', 'es');
    bindTable('connBodyVec', 'vector');
    bindTable('connBodyTxt', 's3');

    /* 2-1 弹窗 */
    el('saveEsBtn').addEventListener('click', function () { saveEs(this); });
    el('testEsBtn').addEventListener('click', function () {
        testConn('es', {
            connection: {
                id: esEditId || el('esConnName').value.trim() || '',
                type: 'elasticsearch', es: readFields(ES_FIELDS)
            }
        }, 'esResult', this);
    });
    el('clearEsBtn').addEventListener('click', function () {
        writeFields(ES_FIELDS, null);
        showResult('esResult', '已清空表单内容（仅本页，尚未生效）。口令留空表示保持不变。', '');
    });
    el('cancelEsBtn').addEventListener('click', function () { hideModal('connModalEs'); });
    el('connModalEsClose').addEventListener('click', function () { hideModal('connModalEs'); });
    el('connModalEs').addEventListener('click', function (e) {
        if (e.target === el('connModalEs')) { hideModal('connModalEs'); }
    });

    /* 2-2 弹窗 */
    el('saveVectorBtn').addEventListener('click', function () { saveVec(this, 'vectorResult'); });
    el('saveVecEsBtn').addEventListener('click', function () { saveVec(this, 'vesResult'); });
    el('testVectorBtn').addEventListener('click', function () {
        testConn('qdrant', {
            connection: {
                id: vecEditId || el('vecConnName').value.trim() || '',
                type: 'qdrant', vector: readFields(QD_FIELDS)
            }
        }, 'vectorResult', this);
    });
    el('clearVectorBtn').addEventListener('click', function () {
        writeFields(QD_FIELDS, null);
        showResult('vectorResult', '已清空表单内容（仅本页，尚未生效）。Token 留空表示保持不变。', '');
    });
    el('cancelVectorBtn').addEventListener('click', function () { hideModal('connModalVec'); });
    el('cancelVecEsBtn').addEventListener('click', function () { hideModal('connModalVec'); });
    el('connModalVecClose').addEventListener('click', function () { hideModal('connModalVec'); });
    el('connModalVec').addEventListener('click', function (e) {
        if (e.target === el('connModalVec')) { hideModal('connModalVec'); }
    });

    /* 2-3 原文：内置磁盘，无可配项（表格里只有一行只读说明） */

    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Escape') { return; }
        if (!el('connModalEs').hidden) { hideModal('connModalEs'); return; }
        if (!el('connModalVec').hidden) { hideModal('connModalVec'); }
    });


    /* 知识体在本页只读：没有交互处理器。增删改在 knowledge.html（/admin/bodies），
       所以这里也不再往 /admin/infra 提交 bodies。 */


    function findBody(bodyId) {
        var found = null;
        STATE.bodies.forEach(function (b) { if (b.id === bodyId) { found = b; } });
        return found;
    }

    /* 页面首次加载失败（后端没起、未登录）也要给出可读的原因 */
    Console.ready(function () {
        loadInfra(false).catch(function (e) {
            setStatus('<span>读取存储配置失败：' + Console.escapeHtml(e.message) + '</span>', 'warn');
        });
    });
})();

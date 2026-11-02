/* ==========================================================================
   mcp 页脚本 · 与 mcp.html 一一对应
   统计行 + connList 连接卡片（dp-list 网格，操作按钮收在卡底 .mcp-actions）
   + 新增/编辑、绑定域、工具目录三个遮罩层。
   旧版引用的 #connCount 已不存在（render 第一步 TypeError，整页卡在"加载中"），
   统计卡无人填，本次按现 HTML 重写。
   ========================================================================== */
(function () {
    var esc = Console.escapeHtml;

    var CONNS = [];
    var BINDINGS = { domainBindings: {}, connectionBindings: {} };
    var DOMAINS = [];
    var EP = { STREAMABLE_HTTP: 'Streamable HTTP', LEGACY_SSE: 'Legacy SSE' };
    var MCP_ICON = '<svg class="mcp-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 17H7A5 5 0 0 1 7 7h2"/><path d="M15 7h2a5 5 0 0 1 0 10h-2"/><line x1="8" y1="12" x2="16" y2="12"/></svg>';

    function setValue(id, v, tone) {
        var el = document.getElementById(id);
        if (el) {
            el.textContent = v;
            el.className = 'dp-stat-value' + (tone ? ' ' + tone : '');
        }
    }

    function statusTag(c) {
        if (!c.enabled) { return '<span class="tag off">已禁用</span>'; }
        if (c.ready) { return '<span class="tag ready">已就绪</span>'; }
        if (c.needsAuth) {
            return '<span class="tag approval" title="' + esc(c.lastError || '') + '">需授权</span>';
        }
        if (c.status === 'FAILED') {
            return '<span class="tag failed" title="' + esc(c.lastError || '') + '">连接失败</span>';
        }
        return '<span class="tag off">未连接</span>';
    }

    function sideEffectTag(se) {
        var cls = 'perm';
        if (se === 'READ') { cls = 'read'; }
        else if (se === 'WRITE') { cls = 'write'; }
        else if (se === 'DESTRUCTIVE') { cls = 'destructive'; }
        return '<span class="tag ' + cls + '">' + esc(se) + '</span>';
    }

    function renderStats() {
        var ready = 0, needsAuth = 0, failed = 0;
        CONNS.forEach(function (c) {
            if (c.enabled && c.ready) { ready++; }
            else if (c.enabled && c.needsAuth) { needsAuth++; }
            else if (c.enabled && c.status === 'FAILED') { failed++; }
        });
        setValue('statConns', String(CONNS.length));
        setValue('statReady', String(ready));
        setValue('statNeedsAuth', String(needsAuth), needsAuth ? 'warn' : '');
        setValue('statFailed', String(failed), failed ? 'err' : '');
    }

    function render() {
        var box = document.getElementById('connList');
        renderStats();
        if (!CONNS.length) {
            box.innerHTML = '<div class="dp-card"><span class="perm-none">还没有任何 MCP 连接。点「新增连接」接入一个外部 MCP server。</span></div>';
            return;
        }
        box.innerHTML = CONNS.map(function (c) {
            var bound = BINDINGS.connectionBindings[c.name] || [];
            var boundTags = bound.length
                ? bound.map(function (d) { return '<span class="tag public mono">' + esc(d) + '</span>'; }).join(' ')
                : '<span class="perm-none">未绑定任何域（工具对谁都不可见）</span>';
            var actions = (c.enabled
                ? '<button class="btn btn-sm btn-outline-secondary" data-toggle="' + esc(c.name) + '" data-to="false">停用</button>'
                : '<button class="btn btn-sm btn-outline-primary" data-toggle="' + esc(c.name) + '" data-to="true">启用</button>')
                + '<button class="btn btn-sm btn-outline-secondary" data-test="' + esc(c.name) + '">测试</button>'
                + '<button class="btn btn-sm btn-outline-secondary" data-tools="' + esc(c.name) + '">工具</button>'
                + (c.needsAuth ? '<button class="btn btn-sm btn-outline-secondary" data-oauth="' + esc(c.name) + '">授权</button>' : '')
                + '<button class="btn btn-sm btn-outline-secondary" data-bind="' + esc(c.name) + '">绑定域</button>'
                + '<button class="btn btn-sm btn-outline-secondary" data-edit="' + esc(c.name) + '">编辑</button>'
                + '<button class="btn btn-sm btn-outline-danger" data-del="' + esc(c.name) + '">删除</button>';
            return '<section class="dp-card model-card"><header class="card-head">' + MCP_ICON
                + '<h2 class="mono">' + esc(c.name) + '</h2>' + statusTag(c)
                + '<span class="spacer"></span><span class="card-note">工具 ' + (c.ready ? c.toolCount : 0)
                + ' · 策略 ' + esc(c.approvalPolicy) + '</span></header>'
                + '<div class="kv"><span class="k">地址</span><span class="v">' + esc(c.url) + '</span>'
                + '<span class="k">传输</span><span class="v">' + esc(EP[c.transport] || c.transport) + '</span>'
                + (c.hasHeaders ? '<span class="k">鉴权</span><span class="v">已配置（明文不回显）</span>' : '')
                + (c.oauthClientId
                    ? '<span class="k">OAuth</span><span class="v">client_id ' + esc(c.oauthClientId)
                        + (c.hasOauthClientSecret ? ' + secret' : '') + '（手工登记）</span>'
                    : '<span class="k">OAuth</span><span class="v">未登记（需要时走动态注册）</span>')
                + (c.callTimeoutMs ? '<span class="k">超时</span><span class="v">' + esc(c.callTimeoutMs) + ' ms</span>' : '')
                + ((c.toolAllowlist || []).length ? '<span class="k">白名单</span><span class="v">' + esc(c.toolAllowlist.join(', ')) + '</span>' : '')
                + (c.remark ? '<span class="k">备注</span><span class="v">' + esc(c.remark) + '</span>' : '')
                + '</div>'
                + '<div class="mcp-bound"><span class="k">绑定域</span>' + boundTags + '</div>'
                + '<div class="mcp-actions">' + actions + '</div></section>';
        }).join('');
        box.querySelectorAll('button[data-toggle]').forEach(function (b) {
            b.addEventListener('click', function () {
                toggle(b.getAttribute('data-toggle'), b.getAttribute('data-to') === 'true', b);
            });
        });
        box.querySelectorAll('button[data-test]').forEach(function (b) {
            b.addEventListener('click', function () { testConn(b.getAttribute('data-test'), b); });
        });
        box.querySelectorAll('button[data-oauth]').forEach(function (b) {
            b.addEventListener('click', function () { startOAuth(b.getAttribute('data-oauth')); });
        });
        box.querySelectorAll('button[data-tools]').forEach(function (b) {
            b.addEventListener('click', function () { showTools(b.getAttribute('data-tools')); });
        });
        box.querySelectorAll('button[data-bind]').forEach(function (b) {
            b.addEventListener('click', function () { openBind(b.getAttribute('data-bind')); });
        });
        box.querySelectorAll('button[data-edit]').forEach(function (b) {
            b.addEventListener('click', function () { openEdit(b.getAttribute('data-edit')); });
        });
        box.querySelectorAll('button[data-del]').forEach(function (b) {
            b.addEventListener('click', function () { delConn(b.getAttribute('data-del')); });
        });
    }

    function loadBindings() {
        return Console.api.get('/admin/mcp-bindings').then(function (r) {
            BINDINGS = r || { domainBindings: {}, connectionBindings: {} };
        });
    }

    function loadDomains() {
        return Console.api.get('/admin/domains').then(function (r) {
            DOMAINS = (r && r.domains) || [];
        }).catch(function () {
            DOMAINS = [];
        });
    }

    function load() {
        document.getElementById('connList').innerHTML = Console.loadingHtml('正在加载 MCP 连接…');
        Console.api.get('/admin/mcp-connections').then(function (r) {
            CONNS = (r && r.connections) || [];
            return loadBindings();
        }).then(render).catch(function (e) {
            document.getElementById('connList').innerHTML = '<div class="dp-card" style="color:var(--red);">读取失败：' + esc(e.message) + '</div>';
        });
    }

    function openCreate() {
        document.getElementById('editTitle').textContent = '新增 MCP 连接';
        document.getElementById('f_name').value = '';
        document.getElementById('f_name').disabled = false;
        document.getElementById('f_url').value = '';
        document.getElementById('f_transport').value = 'STREAMABLE_HTTP';
        document.getElementById('f_approval').value = 'ALL';
        document.getElementById('f_headers').value = '';
        document.getElementById('f_oauth_cid').value = '';
        document.getElementById('f_oauth_secret').value = '';
        document.getElementById('f_oauth_cname').value = '';
        document.getElementById('f_oauth_scope').value = '';
        document.getElementById('f_oauth_redirect').value = '';
        document.getElementById('f_timeout').value = '30000';
        document.getElementById('f_allow').value = '';
        document.getElementById('f_remark').value = '';
        document.getElementById('f_enabled').checked = true;
        document.getElementById('editModal').hidden = false;
        document.body.style.overflow = 'hidden';
    }

    function openEdit(name) {
        var c = CONNS.filter(function (x) { return x.name === name; })[0];
        if (!c) { return; }
        document.getElementById('editTitle').textContent = '编辑 MCP 连接';
        document.getElementById('f_name').value = c.name;
        document.getElementById('f_name').disabled = true;
        document.getElementById('f_url').value = c.url || '';
        document.getElementById('f_transport').value = c.transport || 'STREAMABLE_HTTP';
        document.getElementById('f_approval').value = c.approvalPolicy || 'ALL';
        document.getElementById('f_headers').value = '';
        document.getElementById('f_oauth_cid').value = c.oauthClientId || '';
        document.getElementById('f_oauth_secret').value = '';
        document.getElementById('f_oauth_cname').value = c.oauthClientName || '';
        document.getElementById('f_oauth_scope').value = c.oauthScope || '';
        document.getElementById('f_oauth_redirect').value = c.oauthRedirectUri || '';
        document.getElementById('f_timeout').value = c.callTimeoutMs || 30000;
        document.getElementById('f_allow').value = (c.toolAllowlist || []).join(', ');
        document.getElementById('f_remark').value = c.remark || '';
        document.getElementById('f_enabled').checked = c.enabled !== false;
        document.getElementById('editModal').hidden = false;
        document.body.style.overflow = 'hidden';
    }

    function closeEdit() {
        document.getElementById('editModal').hidden = true;
        document.body.style.overflow = '';
    }

    function saveEdit() {
        var name = document.getElementById('f_name').value.trim();
        var url = document.getElementById('f_url').value.trim();
        var headersRaw = document.getElementById('f_headers').value.trim();
        var headers = {};
        if (headersRaw) {
            try {
                headers = JSON.parse(headersRaw);
            } catch (e) {
                Console.toast('自定义请求头不是合法 JSON：' + e.message, { type: 'error' });
                return;
            }
        }
        var allowRaw = document.getElementById('f_allow').value.trim();
        var allow = allowRaw ? allowRaw.split(/[,\s]+/).filter(Boolean) : [];
        var timeout = parseInt(document.getElementById('f_timeout').value, 10);
        var body = {
            name: name,
            url: url,
            transport: document.getElementById('f_transport').value,
            approvalPolicy: document.getElementById('f_approval').value,
            headers: headers,
            callTimeoutMs: isNaN(timeout) ? 30000 : timeout,
            enabled: document.getElementById('f_enabled').checked,
            toolAllowlist: allow,
            remark: document.getElementById('f_remark').value.trim(),
            oauthClientId: document.getElementById('f_oauth_cid').value.trim(),
            oauthClientSecret: document.getElementById('f_oauth_secret').value,
            oauthClientName: document.getElementById('f_oauth_cname').value.trim(),
            oauthScope: document.getElementById('f_oauth_scope').value.trim(),
            oauthRedirectUri: document.getElementById('f_oauth_redirect').value.trim()
        };
        return Console.api.post('/admin/mcp-connections', body).then(function (r) {
            Console.toast((r && r.message) ? r.message : '已保存', { type: 'success' });
            closeEdit();
            load();
        }).catch(function (e) {
            Console.toast('保存失败：' + e.message, { type: 'error' });
        });
    }

    function toggle(name, enabled, btn) {
        Console.busy(btn, function () {
            return Console.api.post('/admin/mcp-connections/' + encodeURIComponent(name) + '/toggle', { enabled: enabled })
                .then(function () { load(); })
                .catch(function (e) {
                    Console.toast('操作失败：' + e.message, { type: 'error' });
                });
        }, enabled ? '启用中…' : '停用中…');
    }

    async function delConn(name) {
        var ok = await Console.confirm(
            '确认删除 MCP 连接 ' + name + '？\n\n其工具会被摘除、绑定会解绑（不删域）。',
            { title: '删除 MCP 连接', okText: '删除', danger: true }
        );
        if (!ok) { return; }
        Console.api.del('/admin/mcp-connections?name=' + encodeURIComponent(name)).then(function () {
            load();
        }).catch(function (e) {
            Console.toast('删除失败：' + e.message, { type: 'error' });
        });
    }

    async function testConn(name, btn) {
        var ok = await Console.confirm(
            '对 ' + name + ' 发起连通性测试（initialize + tools/list）？',
            { title: '连通性测试', okText: '开始测试', danger: false }
        );
        if (!ok) { return; }
        Console.busy(btn, function () {
            return Console.api.post('/admin/mcp-connections/' + encodeURIComponent(name) + '/test', null)
                .then(function (r) {
                    Console.toast(
                        (r && r.message) + (r && r.tools ? '\n工具：' + r.tools.join(', ') : ''),
                        { type: 'success', wide: true }
                    );
                    load();
                }).catch(function (e) {
                    Console.toast('测试失败：' + e.message, { type: 'error', wide: true });
                });
        }, '测试中…');
    }

    function startOAuth(name) {
        Console.api.post('/admin/mcp-connections/' + encodeURIComponent(name) + '/oauth/start', null)
            .then(function (r) {
                if (r && r.authorizeUrl) {
                    location.href = r.authorizeUrl;
                    return;
                }
                throw new Error('授权端点未返回授权链接');
            }).catch(function (e) {
                Console.toast('发起授权失败：' + e.message, { type: 'error' });
            });
    }

    function showTools(name) {
        document.getElementById('toolsConnName').textContent = name;
        document.getElementById('toolsBody').innerHTML = Console.loadingHtml('正在读取工具目录…');
        document.getElementById('toolsModal').hidden = false;
        document.body.style.overflow = 'hidden';
        Console.api.get('/admin/mcp-connections/' + encodeURIComponent(name) + '/tools').then(function (r) {
            var tools = (r && r.tools) || [];
            if (!tools.length) {
                document.getElementById('toolsBody').innerHTML = '<div class="perm-none">'
                    + ((r && r.ready) ? '该连接没有工具' : '连接未就绪，无法读取工具目录') + '</div>';
                return;
            }
            document.getElementById('toolsBody').innerHTML = tools.map(function (t) {
                return '<div class="dp-card" style="margin-bottom:8px;padding:10px 12px;">'
                    + '<div style="display:flex;gap:8px;align-items:center;">'
                    + '<span class="mono" style="font-weight:600;">' + esc(t.originalName) + '</span>'
                    + (t.allowed ? '' : '<span class="tag off" title="被工具白名单裁剪">已裁剪</span>')
                    + sideEffectTag(t.sideEffect)
                    + '<span class="spacer"></span><span class="card-note mono">→ ' + esc(t.mappedName) + '</span></div>'
                    + (t.description ? '<div class="card-note" style="margin-top:4px;">' + esc(t.description) + '</div>' : '')
                    + '</div>';
            }).join('');
        }).catch(function (e) {
            document.getElementById('toolsBody').innerHTML = '<div class="dp-card" style="color:var(--red);">读取失败：' + esc(e.message) + '</div>';
        });
    }

    var BIND_CONN = '';
    var BIND_PICK = [];

    function openBind(name) {
        BIND_CONN = name;
        var currently = BINDINGS.connectionBindings[name] || [];
        BIND_PICK = currently.slice();
        document.getElementById('bindConnName').textContent = name;
        renderBindCards();
        document.getElementById('bindModal').hidden = false;
        document.body.style.overflow = 'hidden';
    }

    function closeBind() {
        document.getElementById('bindModal').hidden = true;
        document.body.style.overflow = '';
    }

    function renderBindCards() {
        var box = document.getElementById('bindCardList');
        var empty = document.getElementById('bindEmpty');
        if (!DOMAINS.length) {
            box.innerHTML = '';
            empty.style.display = 'block';
            empty.textContent = '还没有任何域，请先到「域空间」创建。';
            return;
        }
        empty.style.display = 'none';
        box.innerHTML = DOMAINS.map(function (d) {
            var on = BIND_PICK.indexOf(d.name) >= 0;
            return '<div class="pick-card' + (on ? ' on' : '') + '" data-pick="' + esc(d.name) + '">'
                + '<div class="pc-name">' + esc(d.name) + (on ? ' ✓' : '')
                + (d.callable ? '' : ' <span class="tag approval" title="装配节点：只把工具传给后代">装配</span>')
                + '</div>'
                + '<div class="pc-note">来源 ' + esc(d.sourceLabel || d.source || '') + '</div></div>';
        }).join('');
        box.querySelectorAll('div[data-pick]').forEach(function (card) {
            card.addEventListener('click', function () {
                var d = card.getAttribute('data-pick');
                var i = BIND_PICK.indexOf(d);
                if (i >= 0) { BIND_PICK.splice(i, 1); } else { BIND_PICK.push(d); }
                renderBindCards();
            });
        });
    }

    /* 绑定按"每个域一份连接清单"的接口语义落地：对每个发生变化的家域各发一次 PUT */
    function saveBind() {
        var promises = [];
        DOMAINS.forEach(function (d) {
            var current = (BINDINGS.domainBindings[d.name] || []).slice();
            var want = BIND_PICK.indexOf(d.name) >= 0;
            var has = current.indexOf(BIND_CONN) >= 0;
            if (want === has) { return; }
            var next = want
                ? current.concat([BIND_CONN])
                : current.filter(function (x) { return x !== BIND_CONN; });
            promises.push(Console.api.put('/admin/mcp-bindings/' + encodeURIComponent(d.name), { connections: next }));
        });
        Promise.all(promises.map(function (p) {
            return p.catch(function (e) { return { __error: e.message }; });
        })).then(function (results) {
            var err = results.filter(function (r) { return r && r.__error; })[0];
            if (err) {
                Console.toast('部分绑定保存失败：' + err.__error, { type: 'error' });
            } else {
                Console.toast('绑定已保存', { type: 'success' });
            }
            closeBind();
            load();
        });
    }

    document.getElementById('createBtn').addEventListener('click', openCreate);
    document.getElementById('refreshBtn').addEventListener('click', load);
    document.getElementById('editClose').addEventListener('click', closeEdit);
    document.getElementById('editSaveBtn').addEventListener('click', function () {
        Console.busy(this, saveEdit, '保存中…');
    });
    document.getElementById('editClearBtn').addEventListener('click', openCreate);
    document.getElementById('bindClose').addEventListener('click', closeBind);
    document.getElementById('bindSaveBtn').addEventListener('click', saveBind);
    document.getElementById('bindClearBtn').addEventListener('click', function () {
        BIND_PICK = [];
        renderBindCards();
    });
    document.getElementById('toolsClose').addEventListener('click', function () {
        document.getElementById('toolsModal').hidden = true;
        document.body.style.overflow = '';
    });
    document.getElementById('editModal').addEventListener('click', function (e) {
        if (e.target === document.getElementById('editModal')) { closeEdit(); }
    });
    document.getElementById('bindModal').addEventListener('click', function (e) {
        if (e.target === document.getElementById('bindModal')) { closeBind(); }
    });

    Console.renderSidebar('mcp');
    Console.ready(function () {
        /* OAuth 回跳带 ?auth=ok|fail：报一次结果后清掉参数，刷新不再重复弹 */
        var q = new URLSearchParams(location.search);
        var auth = q.get('auth');
        if (auth === 'ok') {
            Console.toast('授权成功：连接 ' + (q.get('name') || '') + ' 已就绪，工具对绑定域可见', { type: 'success' });
        } else if (auth === 'fail') {
            Console.toast('授权失败：' + (q.get('reason') || '未知原因'), { type: 'error' });
        }
        if (auth) {
            history.replaceState(null, '', location.pathname);
        }
        loadDomains().then(load);
    });
})();

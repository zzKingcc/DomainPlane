/* ==========================================================================
   P1 · mcp 页脚本
   自 mcp.html 的内联 <script> 机械平移（1 块合并），逻辑未改。
   ========================================================================== */
(function () {
var esc = Console.escapeHtml;
    var CONNS = [];
    var BINDINGS = { domainBindings: {}, connectionBindings: {} };
    var DOMAINS = [];

    var EP = { STREAMABLE_HTTP: 'Streamable HTTP', LEGACY_SSE: 'Legacy SSE' };

    function statusTag(c) {
        if (!c.enabled) { return '<span class="tag off">已禁用</span>'; }
        if (c.ready) { return '<span class="tag ready">已就绪</span>'; }
        if (c.needsAuth) {
            return '<span class="tag approval" title="' + esc(c.lastError || '') + '">需授权</span>';
        }
        if (c.status === 'FAILED') { return '<span class="tag failed" title="' + esc(c.lastError || '') + '">连接失败</span>'; }
        return '<span class="tag off">未连接</span>';
    }

    function sideEffectTag(se) {
        var cls = 'perm';
        if (se === 'READ') { cls = 'read'; }
        else if (se === 'WRITE') { cls = 'write'; }
        else if (se === 'DESTRUCTIVE') { cls = 'destructive'; }
        return '<span class="tag ' + cls + '">' + esc(se) + '</span>';
    }

    function render() {
        var box = document.getElementById('connList');
        document.getElementById('connCount').textContent = CONNS.length + ' 个连接';
        if (!CONNS.length) {
            box.innerHTML = '<div class="dp-card"><span class="perm-none">还没有任何 MCP 连接。点「新增连接」接入一个外部 MCP server。</span></div>';
            return;
        }
        box.innerHTML = CONNS.map(function (c) {
            var bound = (BINDINGS.connectionBindings[c.name] || []);
            var boundTags = bound.length
                ? bound.map(function (d) { return '<span class="tag public mono">' + esc(d) + '</span>'; }).join(' ')
                : '<span class="perm-none">未绑定任何域（工具对谁都不可见）</span>';
            return '<section class="dp-card model-card">'
                + '<header class="card-head">'
                +   '<svg class="mcp-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"'
                +     ' stroke-linecap="round" stroke-linejoin="round"><path d="M9 17H7A5 5 0 0 1 7 7h2"/>'
                +     '<path d="M15 7h2a5 5 0 0 1 0 10h-2"/><line x1="8" y1="12" x2="16" y2="12"/></svg>'
                +   '<h2 class="mono">' + esc(c.name) + '</h2>'
                +   statusTag(c)
                +   '<span class="spacer"></span>'
                +   '<span class="card-note">工具 ' + (c.ready ? c.toolCount : 0) + ' · 策略 ' + esc(c.approvalPolicy) + '</span>'
                +   (c.enabled
                        ? '<button class="ghost btn btn-sm btn-outline-primary" data-toggle="' + esc(c.name) + '" data-to="false">停用</button>'
                        : '<button class="ghost btn btn-sm btn-outline-primary" data-toggle="' + esc(c.name) + '" data-to="true">启用</button>')
                +   '<button class="ghost btn btn-sm btn-outline-primary" data-test="' + esc(c.name) + '">测试</button>'
                +   '<button class="ghost btn btn-sm btn-outline-primary" data-tools="' + esc(c.name) + '">工具</button>'
                +   (c.needsAuth
                        ? '<button class="ghost btn btn-sm btn-outline-primary" data-oauth="' + esc(c.name) + '">授权</button>'
                        : '')
                +   '<button class="ghost btn btn-sm btn-outline-primary" data-bind="' + esc(c.name) + '">绑定域</button>'
                +   '<button class="ghost btn btn-sm btn-outline-primary" data-edit="' + esc(c.name) + '">编辑</button>'
                +   '<button class="ghost btn btn-sm btn-outline-primary" data-del="' + esc(c.name) + '">删除</button>'
                + '</header>'
                + '<div class="kv">'
                +   '<span class="k">地址</span><span class="v">' + esc(c.url) + '</span>'
                +   '<span class="k">传输</span><span class="v">' + esc(EP[c.transport] || c.transport) + '</span>'
                +   (c.hasHeaders ? '<span class="k">鉴权</span><span class="v">已配置（明文不回显）</span>' : '')
                +   (c.oauthClientId
                        ? '<span class="k">OAuth</span><span class="v">client_id ' + esc(c.oauthClientId)
                          + (c.hasOauthClientSecret ? ' + secret' : '') + '（手工登记）</span>'
                        : '')
                +   (c.lastError ? '<span class="k">错误</span><span class="v" style="color:var(--red);">' + esc(c.lastError) + '</span>' : '')
                + '</div>'
                + '<div style="margin-top:8px;"><span class="k" style="color:var(--muted);font-size:12px;">绑定域：</span> ' + boundTags + '</div>'
                + '</section>';
        }).join('');

        box.querySelectorAll('button[data-toggle]').forEach(function (b) {
            b.addEventListener('click', function () { toggle(b.getAttribute('data-toggle'), b.getAttribute('data-to') === 'true'); });
        });
        box.querySelectorAll('button[data-test]').forEach(function (b) {
            b.addEventListener('click', function () { testConn(b.getAttribute('data-test')); });
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

    /* ===== 列表 / 绑定加载 =====
       P2：/admin/mcp-bindings 与 /admin/mcp-connections 都是 A 类（只有 code），
           /admin/domains 是 D 类。原来这页读接口一律没判 code，失败会静默走 then。 */
    function loadBindings() {
        return Console.api.get('/admin/mcp-bindings').then(function (r) {
            BINDINGS = r || { domainBindings: {}, connectionBindings: {} };
        });
    }
    function loadDomains() {
        return Console.api.get('/admin/domains').then(function (r) {
            DOMAINS = (r && r.domains) || [];
        }).catch(function () { DOMAINS = []; });
    }
    function load() {
        document.getElementById('connList').innerHTML = '<div class="dp-card"><span class="perm-none">加载中…</span></div>';
        Console.api.get('/admin/mcp-connections').then(function (r) {
            CONNS = (r && r.connections) || [];
            return loadBindings();
        }).then(render).catch(function (e) {
            document.getElementById('connList').innerHTML =
                '<div class="dp-card" style="color:var(--red);">读取失败：' + esc(e.message) + '</div>';
        });
    }

    /* ===== 新增 / 编辑 ===== */
    var EDIT_NAME = '';
    function openCreate() {
        EDIT_NAME = '';
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
        EDIT_NAME = name;
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
            try { headers = JSON.parse(headersRaw); }
            catch (e) { alert('自定义请求头不是合法 JSON：' + e.message); return; }
        }
        var allowRaw = document.getElementById('f_allow').value.trim();
        var allow = allowRaw ? allowRaw.split(/[,\s]+/).filter(Boolean) : [];
        var timeout = parseInt(document.getElementById('f_timeout').value, 10);
        var body = {
            name: name, url: url,
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
        /* P2：/admin/mcp-connections POST 是 B 类（code + success），统一按 code 判。
           api.post 返回 data，所以 r.message 照旧能取到。 */
        Console.api.post('/admin/mcp-connections', body)
            .then(function (r) {
                alert(r && r.message ? r.message : '已保存');
                closeEdit();
                load();
            })
            .catch(function (e) { alert('保存失败：' + e.message); });
    }

    /* ===== 启停 / 删除 / 测试 ===== */
    function toggle(name, enabled) {
        Console.api.post('/admin/mcp-connections/' + encodeURIComponent(name) + '/toggle', null)
            .then(function () { load(); })
            .catch(function (e) { alert('操作失败：' + e.message); });
    }
    function delConn(name) {
        if (!confirm('确认删除 MCP 连接 ' + name + '？\n\n其工具会被摘除、绑定会解绑（不删域）。')) { return; }
        Console.api.del('/admin/mcp-connections?name=' + encodeURIComponent(name))
            .then(function () { load(); })
            .catch(function (e) { alert('删除失败：' + e.message); });
    }
    function testConn(name) {
        if (!confirm('对 ' + name + ' 发起连通性测试（initialize + tools/list）？')) { return; }
        /* P2：/admin/mcp-connections/{name}/test 是 A 类 */
        Console.api.post('/admin/mcp-connections/' + encodeURIComponent(name) + '/test', null)
            .then(function (r) {
                alert((r && r.message) + (r && r.tools ? '\n工具：' + r.tools.join(', ') : ''));
                load();
            })
            .catch(function (e) { alert('测试失败：' + e.message); });
    }

    /* ===== OAuth 授权：拿授权 URL 后浏览器跳转，管理员登录同意，回调落 token 并建连 ===== */
    function startOAuth(name) {
        Console.api.post('/admin/mcp-connections/' + encodeURIComponent(name) + '/oauth/start', null)
            .then(function (r) {
                if (r && r.authorizeUrl) { location.href = r.authorizeUrl; return; }
                throw new Error('授权端点未返回授权链接');
            })
            .catch(function (e) { alert('发起授权失败：' + e.message); });
    }

    /* ===== 工具目录 ===== */
    function showTools(name) {
        document.getElementById('toolsConnName').textContent = name;
        document.getElementById('toolsBody').innerHTML = '加载中…';
        document.getElementById('toolsModal').hidden = false;
        document.body.style.overflow = 'hidden';
        /* P2：/admin/mcp-connections/{name}/tools 是 A 类 */
        Console.api.get('/admin/mcp-connections/' + encodeURIComponent(name) + '/tools')
            .then(function (r) {
                var tools = (r && r.tools) || [];
                if (!tools.length) {
                    document.getElementById('toolsBody').innerHTML =
                        '<div class="perm-none">' + (r && r.ready ? '该连接没有工具' : '连接未就绪，无法读取工具目录') + '</div>';
                    return;
                }
                document.getElementById('toolsBody').innerHTML = tools.map(function (t) {
                    return '<div class="dp-card" style="margin-bottom:8px;padding:10px 12px;">'
                        + '<div style="display:flex;gap:8px;align-items:center;">'
                        +   '<span class="mono" style="font-weight:600;">' + esc(t.originalName) + '</span>'
                        +   (t.allowed ? '' : '<span class="tag off" title="被工具白名单裁剪">已裁剪</span>')
                        +   sideEffectTag(t.sideEffect)
                        +   '<span class="spacer"></span>'
                        +   '<span class="card-note mono">→ ' + esc(t.mappedName) + '</span>'
                        + '</div>'
                        + (t.description ? '<div class="card-note" style="margin-top:4px;">' + esc(t.description) + '</div>' : '')
                        + '</div>';
                }).join('');
            })
            .catch(function (e) {
                document.getElementById('toolsBody').innerHTML =
                    '<div class="dp-card" style="color:var(--red);">读取失败：' + esc(e.message) + '</div>';
            });
    }

    /* ===== 域绑定 ===== */
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
                +   '<div class="pc-name">' + esc(d.name) + (on ? ' ✓' : '')
                +     (d.callable ? '' : ' <span class="tag approval" title="装配节点：只把工具传给后代">装配</span>')
                +   '</div>'
                +   '<div class="pc-note">来源 ' + esc(d.sourceLabel || d.source || '') + '</div>'
                + '</div>';
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
    function saveBind() {
        // 逐域应用 diff：开=并入该域的连接集合；关=从集合剔除。保证连接级联解绑语义正确。
        var promises = [];
        DOMAINS.forEach(function (d) {
            var current = (BINDINGS.domainBindings[d.name] || []).slice();
            var want = BIND_PICK.indexOf(d.name) >= 0;
            var has = current.indexOf(BIND_CONN) >= 0;
            if (want === has) { return; }
            var next = want ? current.concat([BIND_CONN]) : current.filter(function (x) { return x !== BIND_CONN; });
            /* P2：/admin/mcp-bindings/{domain} 是 B 类。这里刻意保留批量语义：
               每个域的 PUT 各自 catch 成 { __error }，最后统一汇总提示，
               与「一个失败就整体回滚」是两种取舍，前者能保存成功的部分。 */
            promises.push(Console.api.put('/admin/mcp-bindings/' + encodeURIComponent(d.name), { connections: next }));
        });
        Promise.all(promises.map(function (p) {
            return p.catch(function (e) { return { __error: e.message }; });
        })).then(function (results) {
            var err = results.filter(function (r) { return r && r.__error; })[0];
            if (err) { alert('部分绑定保存失败：' + err.__error); }
            else { alert('绑定已保存'); }
            closeBind();
            load();
        });
    }

    document.getElementById('createBtn').addEventListener('click', openCreate);
    document.getElementById('refreshBtn').addEventListener('click', load);
    document.getElementById('editClose').addEventListener('click', closeEdit);
    document.getElementById('editSaveBtn').addEventListener('click', saveEdit);
    document.getElementById('editClearBtn').addEventListener('click', openCreate);
    document.getElementById('bindClose').addEventListener('click', closeBind);
    document.getElementById('bindSaveBtn').addEventListener('click', saveBind);
    document.getElementById('bindClearBtn').addEventListener('click', function () { BIND_PICK = []; renderBindCards(); });
    document.getElementById('toolsClose').addEventListener('click', function () {
        document.getElementById('toolsModal').hidden = true; document.body.style.overflow = '';
    });
    document.getElementById('editModal').addEventListener('click', function (e) {
        if (e.target === document.getElementById('editModal')) { closeEdit(); }
    });
    document.getElementById('bindModal').addEventListener('click', function (e) {
        if (e.target === document.getElementById('bindModal')) { closeBind(); }
    });

    Console.renderSidebar('mcp');
    Console.ready(function () {
        // OAuth 回调 302 回本页：auth=ok/fail 反馈授权结果，replaceState 清掉避免刷新重复弹
        var q = new URLSearchParams(location.search);
        var auth = q.get('auth');
        if (auth === 'ok') {
            alert('授权成功：连接 ' + (q.get('name') || '') + ' 已就绪，工具对绑定域可见');
        } else if (auth === 'fail') {
            alert('授权失败：' + (q.get('reason') || '未知原因'));
        }
        if (auth) { history.replaceState(null, '', location.pathname); }
        loadDomains().then(load);
    });
})();

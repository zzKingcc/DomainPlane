/* ==========================================================================
   mcp 页脚本 · 与 mcp.html 一一对应
   主从工作台：左侧连接列表 + 右侧状态条和四个 Tab（概览 / 工具 / 授权 / 绑定）。
   编辑走右侧抽屉，字段按 基础 / 传输与鉴权 / 治理 / 高级 OAuth 分组。

   两条硬约束：
   • 状态轮询只 patch 状态区，不整页重绘，编辑中的抽屉与勾选态不会被冲掉。
   • 全程 promise 链，不用 async/await（esbuild 会降级成 __async，源与产物对不上）。
   ========================================================================== */
(function () {
    var esc = Console.escapeHtml;
    var PROTOCOL = '2025-03-26';
    var POLL_MS = 15000;
    var EP = { STREAMABLE_HTTP: 'Streamable HTTP', LEGACY_SSE: 'Legacy SSE' };
    var ST = {
        READY: { label: '已就绪', cls: 'ready' },
        CONNECTING: { label: '连接中', cls: 'connecting' },
        NEEDS_AUTH: { label: '需授权', cls: 'needs' },
        FAILED: { label: '连接失败', cls: 'failed' },
        DISCONNECTED: { label: '未连接', cls: 'disc' },
        OFF: { label: '已禁用', cls: 'off' }
    };
    var PANES = { overview: 'paneOverview', tools: 'paneTools', auth: 'paneAuth', bind: 'paneBind' };

    var state = {
        conns: [], settingsFile: '',
        bindings: { domainBindings: {}, connectionBindings: {} },
        domains: [],
        selected: '', tab: 'overview',
        tools: [], toolsFor: '', toolsError: '', toolDetail: '',
        allowPick: [], bindPick: [], bindFor: '', bindTouched: false,
        editing: false, editName: '',
        lastSync: 0, listSig: ''
    };

    function el(id) { return document.getElementById(id); }
    function setText(id, v) { var n = el(id); if (n) { n.textContent = v; } }
    function setHtml(id, v) { var n = el(id); if (n) { n.innerHTML = v; } }
    function setHidden(id, h) { var n = el(id); if (n) { n.hidden = !!h; } }
    function val(id) { var n = el(id); return n ? String(n.value || '').trim() : ''; }
    function setVal(id, v) { var n = el(id); if (n) { n.value = v == null ? '' : String(v); } }
    function checked(id) { var n = el(id); return !!(n && n.checked); }
    function setChecked(id, v) { var n = el(id); if (n) { n.checked = !!v; } }
    function disable(id, on) { var n = el(id); if (n) { n.disabled = !!on; } }
    function num(v) { return (v === undefined || v === null || v === '') ? '—' : String(v); }
    function errText(e) { return (e && e.message) ? String(e.message) : '未知错误'; }
    function shortErr(s, n) { s = String(s || ''); return s.length > n ? s.slice(0, n) + '…' : s; }
    function timeOf(ms) {
        if (!ms) { return '—'; }
        var d = new Date(ms);
        function p(x) { return ('0' + x).slice(-2); }
        return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }
    function kv(k, v) { return '<div class="k">' + esc(k) + '</div><div class="v">' + v + '</div>'; }
    function findConn(name) {
        for (var i = 0; i < state.conns.length; i++) { if (state.conns[i].name === name) { return state.conns[i]; } }
        return null;
    }
    function boundOf(name) { return (state.bindings.connectionBindings || {})[name] || []; }
    function statusOf(c) {
        if (!c) { return 'DISCONNECTED'; }
        if (c.enabled === false) { return 'OFF'; }
        if (c.ready) { return 'READY'; }
        if (c.needsAuth) { return 'NEEDS_AUTH'; }
        if (c.status === 'FAILED') { return 'FAILED'; }
        if (c.status === 'CONNECTING') { return 'CONNECTING'; }
        return 'DISCONNECTED';
    }
    function chip(c) {
        var m = ST[statusOf(c)] || ST.DISCONNECTED;
        return '<span class="dp-mcp-s ' + m.cls + '"><i></i>' + m.label + '</span>';
    }
    function noteOf(c) {
        return '工具 ' + (c.ready ? num(c.toolCount) : '0') + ' · 域 ' + boundOf(c.name).length
            + ' · ' + (EP[c.transport] || c.transport || '—');
    }
    function sideEffectTag(se) {
        var cls = 'perm';
        if (se === 'READ') { cls = 'read'; }
        else if (se === 'WRITE') { cls = 'write'; }
        else if (se === 'DESTRUCTIVE') { cls = 'destructive'; }
        return '<span class="tag ' + cls + '">' + esc(se || '—') + '</span>';
    }
    function setStat(id, v, tone) {
        var n = el(id);
        if (n) { n.textContent = v; n.className = 'dp-stat-value' + (tone ? ' ' + tone : ''); }
    }

    // ==================== 统计与列表 ====================

    function renderStats() {
        var ready = 0, needs = 0, failed = 0, tools = 0;
        state.conns.forEach(function (c) {
            var s = statusOf(c);
            if (s === 'READY') { ready++; tools += Number(c.toolCount) || 0; }
            else if (s === 'NEEDS_AUTH') { needs++; }
            else if (s === 'FAILED') { failed++; }
        });
        setStat('statConns', String(state.conns.length));
        setStat('statReady', String(ready));
        setStat('statNeedsAuth', String(needs), needs ? 'warn' : '');
        setStat('statFailed', String(failed), failed ? 'err' : '');
        setStat('statTools', String(tools));
    }

    function visibleConns() {
        var q = val('connSearch').toLowerCase(), f = val('statusFilter');
        return state.conns.filter(function (c) {
            if (f && statusOf(c) !== f) { return false; }
            if (!q) { return true; }
            return (String(c.name || '') + ' ' + String(c.url || '')).toLowerCase().indexOf(q) >= 0;
        });
    }

    function itemHtml(c) {
        var s = statusOf(c);
        var bad = c.lastError && (s === 'FAILED' || s === 'NEEDS_AUTH');
        return '<div class="dp-mcp-item' + (c.name === state.selected ? ' on' : '') + '"'
            + ' data-name="' + esc(c.name) + '">'
            + '<div class="dp-mcp-item-top"><span class="dp-mcp-item-name mono">' + esc(c.name) + '</span>'
            + '<span class="dp-mcp-item-status">' + chip(c) + '</span></div>'
            + '<div class="dp-mcp-item-note">' + esc(noteOf(c)) + '</div>'
            + (bad ? '<div class="dp-mcp-item-err">' + esc(shortErr(c.lastError, 60)) + '</div>' : '')
            + '</div>';
    }

    function renderList() {
        var list = visibleConns();
        if (!list.length) { setHtml('connList', '<div class="dp-mcp-none">没有匹配的连接</div>'); return; }
        setHtml('connList', list.map(itemHtml).join(''));
    }

    /* 轮询只改状态徽标 / 摘要 / 错误行；集合变了才重建列表，避免冲掉选中与筛选 */
    function patchList() {
        var box = el('connList');
        if (!box) { return; }
        var byName = {};
        state.conns.forEach(function (c) { byName[c.name] = c; });
        box.querySelectorAll('.dp-mcp-item').forEach(function (row) {
            var c = byName[row.getAttribute('data-name')];
            if (!c) { return; }
            var s = row.querySelector('.dp-mcp-item-status');
            if (s) { s.innerHTML = chip(c); }
            var n = row.querySelector('.dp-mcp-item-note');
            if (n) { n.textContent = noteOf(c); }
            var old = row.querySelector('.dp-mcp-item-err');
            if (old) { old.remove(); }
            var st = statusOf(c);
            if (c.lastError && (st === 'FAILED' || st === 'NEEDS_AUTH')) {
                row.insertAdjacentHTML('beforeend', '<div class="dp-mcp-item-err">'
                    + esc(shortErr(c.lastError, 60)) + '</div>');
            }
        });
    }

    function patchStatus() {
        renderStats();
        var sig = state.conns.map(function (c) { return c.name; }).join('\u0001');
        if (sig !== state.listSig) { state.listSig = sig; renderList(); } else { patchList(); }
        setText('syncHint', '上次同步 ' + timeOf(state.lastSync));
        var c = findConn(state.selected);
        if (c && !state.editing) {
            renderStatusBar(c);
            if (state.tab === 'overview') { renderDiag(c); }
            if (state.tab === 'auth') { renderAuth(c); }
        }
    }

    // ==================== 详情：状态条与 Tab ====================

    function renderStatusBar(c) {
        var s = statusOf(c);
        var meta = '传输 ' + (EP[c.transport] || c.transport || '—')
            + ' · 协商协议 ' + (c.protocolVersion || '未协商')
            + ' · 工具 ' + (c.ready ? num(c.toolCount) : '0')
            + ' · 上次探测 ' + timeOf(c.lastAttemptMs)
            + ' · 页面同步 ' + timeOf(state.lastSync);
        var err = (s === 'FAILED' || s === 'NEEDS_AUTH') && c.lastError
            ? '<div class="dp-mcp-sb-err">' + esc(shortErr(c.lastError, 120))
                + (s === 'NEEDS_AUTH'
                    ? '<button class="btn btn-sm" type="button" data-act="oauth">去授权</button>'
                    : '<button class="btn btn-sm" type="button" data-act="test">重试测试</button>')
                + '</div>'
            : '';
        setHtml('statusBar',
            '<div class="dp-mcp-sb-top">'
            + '<div class="dp-mcp-sb-state">' + chip(c)
            + '<span class="dp-mcp-sb-name mono">' + esc(c.name) + '</span></div>'
            + '<div class="dp-mcp-sb-actions">'
            + '<button class="btn btn-sm btn-outline-primary" type="button" data-act="test">测试连接</button>'
            + '<button class="btn btn-sm btn-outline-secondary" type="button" data-act="edit">编辑</button>'
            + '<details class="dp-mcp-more"><summary class="btn btn-sm btn-outline-secondary">更多</summary>'
            + '<div class="dp-mcp-menu">'
            + '<button type="button" data-act="toggle">' + (c.enabled === false ? '启用' : '停用') + '</button>'
            + '<button type="button" data-act="oauth">发起授权</button>'
            + '<button type="button" data-act="delete">删除</button>'
            + '</div></details></div></div>'
            + '<div class="dp-mcp-sb-meta">' + esc(meta) + '</div>' + err);
    }

    function selectTab(tab) {
        state.tab = tab;
        var bar = el('tabBar');
        if (bar) {
            bar.querySelectorAll('.dp-mcp-tab').forEach(function (b) {
                b.classList.toggle('on', b.getAttribute('data-tab') === tab);
            });
        }
        Object.keys(PANES).forEach(function (t) { setHidden(PANES[t], t !== tab); });
        var c = findConn(state.selected);
        if (c) { renderTab(c); }
    }

    function renderTab(c) {
        if (state.tab === 'overview') { renderOverview(c); }
        else if (state.tab === 'tools') { renderTools(c); }
        else if (state.tab === 'auth') { renderAuth(c); }
        else { renderBind(c); }
    }

    function renderDetail() {
        var c = findConn(state.selected);
        setHidden('detailEmpty', !!c);
        setHidden('detailBody', !c);
        if (!c) { return; }
        renderStatusBar(c);
        selectTab(state.tab);
    }

    function renderOverview(c) {
        var bound = boundOf(c.name);
        var allow = c.toolAllowlist || [];
        var caps = Object.keys(c.capabilities || {});
        var keys = c.headerKeys || [];
        setHtml('paneOverview',
            '<div id="diagCard"></div>'
            + '<div class="dp-mcp-kv">'
            + kv('地址', esc(c.url || '—'))
            + kv('传输', esc(EP[c.transport] || c.transport || '—'))
            + kv('客户端基线', esc(PROTOCOL))
            + kv('协商协议', esc(c.protocolVersion || '未协商成功'))
            + kv('服务端能力', caps.length ? esc(caps.join('、')) : '未协商')
            + kv('上次探测', esc(timeOf(c.lastAttemptMs)))
            + kv('调用超时', c.callTimeoutMs ? esc(String(c.callTimeoutMs)) + ' ms' : '默认 30000 ms')
            + kv('审批策略', esc(c.approvalPolicy || '—'))
            + kv('请求头键', keys.length
                ? keys.map(function (k) { return '<span class="tag perm mono">' + esc(k) + '</span>'; }).join(' ')
                : '未配置')
            + kv('工具白名单', allow.length ? esc(allow.join(', ')) : '全部开放')
            + kv('绑定域', bound.length
                ? bound.map(function (d) { return '<span class="tag public mono">' + esc(d) + '</span>'; }).join(' ')
                : '未绑定')
            + kv('备注', esc(c.remark || '—'))
            + kv('配置文件', esc(state.settingsFile || '—'))
            + '</div>');
        renderDiag(c);
    }

    function renderDiag(c) {
        var s = statusOf(c);
        var has = !!c.lastError;
        /* 轮询会重建诊断卡：把「展开原文」的开合态抄回来，否则用户展开的原文 15s 后被收起 */
        var card = el('diagCard');
        var wasOpen = !!(card && card.querySelector('.dp-mcp-diag-raw[open]'));
        setHtml('diagCard',
            '<div class="dp-mcp-diag"><div class="dp-mcp-diag-head">诊断</div>'
            + '<div class="dp-mcp-diag-row"><span>运行状态</span><strong>' + esc(ST[s].label) + '</strong></div>'
            + '<div class="dp-mcp-diag-row"><span>最近错误</span>'
            + (has ? '<strong class="err">' + esc(shortErr(c.lastError, 100)) + '</strong>' : '<strong>无</strong>')
            + '</div>'
            + '<div class="dp-mcp-diag-row"><span>授权挑战</span>'
            + (c.authChallenge ? '<strong>' + esc(shortErr(c.authChallenge, 100)) + '</strong>'
                : '<strong>无</strong>') + '</div>'
            + '<div class="dp-mcp-diag-row"><span>上次同步</span><strong>'
                + esc(timeOf(state.lastSync)) + '</strong></div>'
            + (has ? '<details class="dp-mcp-diag-raw"' + (wasOpen ? ' open' : '')
                + '><summary>展开错误原文</summary><pre>' + esc(c.lastError) + '</pre></details>' : '')
            + '</div>');
    }

    function loadTools(name) {
        return Console.api.get('/admin/mcp-connections/' + encodeURIComponent(name) + '/tools')
            .then(function (r) {
                state.tools = (r && r.tools) || [];
                state.toolsFor = name;
                state.toolsError = '';
                afterTools(name);
            }).catch(function (e) {
                state.tools = [];
                state.toolsFor = name;
                state.toolsError = errText(e);
                afterTools(name);
            });
    }

    function afterTools(name) {
        var c = findConn(state.selected);
        if (c && c.name === name && state.tab === 'tools') { renderTools(c); }
        if (state.editing && state.editName === name) { renderAllowTools(); }
    }

    function renderTools(c) {
        if (state.toolsFor !== c.name) {
            setHtml('paneTools', Console.loadingHtml('正在读取工具目录…'));
            loadTools(c.name);
            return;
        }
        var retry = '<button class="btn btn-sm" type="button" data-act="reloadTools">重新读取</button>';
        if (state.toolsError) {
            setHtml('paneTools', '<div class="dp-mcp-err">读取工具目录失败：'
                + esc(state.toolsError) + '</div>' + retry);
            return;
        }
        if (!state.tools.length) {
            setHtml('paneTools', '<div class="dp-mcp-none">该连接没有工具</div>' + retry);
            return;
        }
        /* 五列 + 跨列详情行：右侧详情区只有 600 多像素，七列加行内展开会把列压成竖条。
           详情行占满整表宽度，Schema 才有地方铺开。 */
        setHtml('paneTools',
            '<div class="dp-mcp-tools-head"><span>' + state.tools.length + ' 个工具</span>'
            + '<span class="spacer"></span>' + retry
            + '<button class="btn btn-sm btn-outline-primary" type="button" data-act="editAllow">编辑白名单</button></div>'
            + '<div class="dp-mcp-table-wrap"><table class="table dp-mcp-table"><thead><tr>'
            + '<th>状态</th><th>工具</th><th>副作用</th><th>审批</th><th>说明</th>'
            + '</tr></thead><tbody>'
            + state.tools.map(function (t) {
                var open = state.toolDetail === t.originalName;
                var schema;
                try { schema = t.inputSchema ? JSON.stringify(t.inputSchema, null, 2) : '无'; }
                catch (e) { schema = '无'; }
                var domains = (t.boundDomains || []).join('、') || '未绑定';
                return '<tr><td>' + (t.allowed
                    ? '<span class="tag public">已开放</span>'
                    : '<span class="tag off">已裁剪</span>') + '</td>'
                    + '<td><div class="mono">' + esc(t.originalName || '—') + '</div>'
                    + '<div class="dp-mcp-tool-mapped mono">' + esc(t.mappedName || '—') + '</div></td>'
                    + '<td>' + sideEffectTag(t.sideEffect) + '</td>'
                    + '<td>' + (t.approvalRequired
                        ? '<span class="tag approval">需审批</span>'
                        : '<span class="tag off">免审批</span>') + '</td>'
                    + '<td>' + esc(shortErr(t.description || '—', 90))
                    + '<button class="dp-mcp-link" type="button" data-act="toolDetail" data-tool="'
                    + esc(t.originalName) + '">' + (open ? '收起参数' : '参数与来源') + '</button></td></tr>'
                    + '<tr class="dp-mcp-tool-detail"' + (open ? '' : ' hidden') + '><td colspan="5">'
                    + '<div class="dp-mcp-tool-meta">'
                    + '<div><b>输入参数</b><pre>' + esc(schema) + '</pre></div>'
                    + '<div><b>生效域</b> ' + esc(domains)
                    + ' · <b>幂等</b> ' + (t.idempotent ? '是' : '否')
                    + ' · <b>来源</b> <span class="mono">' + esc(t.source || '—') + '</span></div>'
                    + '</div></td></tr>';
            }).join('') + '</tbody></table></div>');
    }

    function renderAuth(c) {
        var need = statusOf(c) === 'NEEDS_AUTH';
        setHtml('paneAuth',
            '<div class="dp-mcp-kv">'
            + kv('当前状态', chip(c))
            + kv('授权挑战', esc(c.authChallenge || '无'))
            + kv('client_id', esc(c.oauthClientId || '未登记（走动态注册）'))
            + kv('client_secret', c.hasOauthClientSecret ? '已配置（不回显）' : '未配置')
            + kv('client 名称', esc(c.oauthClientName || '—'))
            + kv('scope', esc(c.oauthScope || '服务器默认'))
            + kv('回调地址', esc(c.oauthRedirectUri || '当前管控台地址'))
            + '</div>'
            + '<div class="dp-mcp-pane-actions">'
            + '<button class="btn btn-sm btn-outline-primary" type="button" data-act="oauth">'
            + (need ? '发起授权' : '重新授权') + '</button></div>');
    }

    function renderBind(c) {
        if (state.bindFor !== c.name || !state.bindTouched) {
            state.bindPick = boundOf(c.name).slice();
            state.bindFor = c.name;
        }
        if (!state.domains.length) {
            setHtml('paneBind', '<div class="dp-mcp-none">还没有任何域</div>');
            return;
        }
        var current = boundOf(c.name);
        var adds = state.bindPick.filter(function (d) { return current.indexOf(d) < 0; });
        var removes = current.filter(function (d) { return state.bindPick.indexOf(d) < 0; });
        var diff = (adds.length || removes.length)
            ? '新增 ' + (adds.length ? esc(adds.join('、')) : '无')
                + ' · 移除 ' + (removes.length ? esc(removes.join('、')) : '无')
            : '未改动';
        setHtml('paneBind',
            '<div class="dp-mcp-bind-grid">' + state.domains.map(function (d) {
                var on = state.bindPick.indexOf(d.name) >= 0;
                return '<div class="dp-mcp-bind-card' + (on ? ' on' : '') + '" data-pick="'
                    + esc(d.name) + '"><span class="mono">' + esc(d.name) + '</span>'
                    + (d.callable ? '' : '<span class="tag approval">装配</span>') + '</div>';
            }).join('') + '</div>'
            + '<div class="dp-mcp-diff">' + diff + '</div>'
            + '<div class="dp-mcp-pane-actions">'
            + '<button class="btn btn-sm btn-outline-primary" type="button" data-act="saveBind">保存绑定</button>'
            + '<button class="btn btn-sm btn-outline-secondary" type="button" data-act="bindClear">全部解绑</button>'
            + '</div>');
    }

    // ==================== 编辑抽屉 ====================

    function showFieldError(id, msg) {
        var n = el(id);
        if (!n || !n.closest) { return; }
        var row = n.closest('.f-row');
        if (!row) { return; }
        var old = row.querySelector('.dp-mcp-ferr');
        if (old) { old.remove(); }
        if (msg) { row.insertAdjacentHTML('beforeend', '<div class="dp-mcp-ferr">' + esc(msg) + '</div>'); }
    }

    function clearFieldErrors() {
        var box = el('editModal');
        if (box) { box.querySelectorAll('.dp-mcp-ferr').forEach(function (n) { n.remove(); }); }
    }

    function renderAllowTools() {
        if (state.toolsError) {
            setHtml('allowTools', '<div class="dp-mcp-none">工具目录读取失败，保存后可重试</div>');
            return;
        }
        if (!state.tools.length) {
            setHtml('allowTools', '<div class="dp-mcp-none">该连接暂无工具目录</div>');
            return;
        }
        setHtml('allowTools', state.tools.map(function (t) {
            var on = state.allowPick.indexOf(t.originalName) >= 0;
            return '<label class="dp-mcp-allow-item"><input type="checkbox" data-allow="'
                + esc(t.originalName) + '"' + (on ? ' checked' : '') + '>'
                + '<span class="mono">' + esc(t.originalName) + '</span>'
                + sideEffectTag(t.sideEffect) + '</label>';
        }).join(''));
    }

    function openCreate() {
        state.editName = ''; state.editing = true;
        setText('editTitle', '新增 MCP 连接');
        setVal('f_name', ''); disable('f_name', false);
        setVal('f_url', ''); setVal('f_remark', '');
        setChecked('f_enabled', true);
        setVal('f_transport', 'STREAMABLE_HTTP');
        setVal('f_timeout', '30000');
        setVal('f_headers', ''); setHtml('headerState', '');
        setVal('f_approval', 'ALL');
        setVal('f_oauth_cid', ''); setVal('f_oauth_secret', '');
        setVal('f_oauth_cname', ''); setVal('f_oauth_scope', ''); setVal('f_oauth_redirect', '');
        state.allowPick = [];
        state.tools = []; state.toolsFor = ''; state.toolsError = '';
        setHtml('allowTools', '<div class="dp-mcp-none">保存后可读取工具目录并勾选白名单</div>');
        clearFieldErrors();
        setHidden('editModal', false);
        document.body.style.overflow = 'hidden';
    }

    function openEdit(name) {
        var c = findConn(name);
        if (!c) { return; }
        state.editName = name; state.editing = true;
        setText('editTitle', '编辑 MCP 连接');
        setVal('f_name', c.name); disable('f_name', true);
        setVal('f_url', c.url || ''); setVal('f_remark', c.remark || '');
        setChecked('f_enabled', c.enabled !== false);
        setVal('f_transport', c.transport || 'STREAMABLE_HTTP');
        setVal('f_timeout', c.callTimeoutMs || 30000);
        setVal('f_headers', '');
        setHtml('headerState', c.hasHeaders ? '<span>已配置请求头（不回显）</span>' : '');
        setVal('f_approval', c.approvalPolicy || 'ALL');
        setVal('f_oauth_cid', c.oauthClientId || '');
        setVal('f_oauth_secret', '');
        setVal('f_oauth_cname', c.oauthClientName || '');
        setVal('f_oauth_scope', c.oauthScope || '');
        setVal('f_oauth_redirect', c.oauthRedirectUri || '');
        state.allowPick = (c.toolAllowlist || []).slice();
        clearFieldErrors();
        setHidden('editModal', false);
        document.body.style.overflow = 'hidden';
        setHtml('allowTools', Console.loadingHtml('正在读取工具目录…'));
        loadTools(name);
    }

    function closeEdit() {
        state.editing = false;
        setHidden('editModal', true);
        document.body.style.overflow = '';
    }

    function validate() {
        clearFieldErrors();
        var name = val('f_name'), url = val('f_url'), t = val('f_timeout');
        var ok = true;
        if (!name) { showFieldError('f_name', '连接名不能为空'); ok = false; }
        else if (name.indexOf('__') >= 0) { showFieldError('f_name', '不能包含连续双下划线'); ok = false; }
        if (!/^https?:\/\//i.test(url)) { showFieldError('f_url', '请填写 http(s) 地址'); ok = false; }
        if (t && (!/^\d+$/.test(t) || Number(t) <= 0)) {
            showFieldError('f_timeout', '超时必须是正整数毫秒'); ok = false;
        }
        return ok;
    }

    function saveEdit() {
        if (!validate()) { return Promise.resolve(); }
        var raw = val('f_headers');
        var headers = {};
        if (raw) {
            try {
                headers = JSON.parse(raw);
                if (!headers || typeof headers !== 'object' || Array.isArray(headers)) {
                    throw new Error('需要 JSON 对象');
                }
            } catch (e) {
                showFieldError('f_headers', '不是合法 JSON 对象：' + e.message);
                return Promise.resolve();
            }
        }
        var t = parseInt(val('f_timeout'), 10);
        var body = {
            name: val('f_name'),
            url: val('f_url'),
            transport: val('f_transport'),
            approvalPolicy: val('f_approval'),
            headers: headers,
            callTimeoutMs: isNaN(t) ? 30000 : t,
            enabled: checked('f_enabled'),
            toolAllowlist: state.allowPick.slice(),
            remark: val('f_remark'),
            oauthClientId: val('f_oauth_cid'),
            oauthClientSecret: val('f_oauth_secret'),
            oauthClientName: val('f_oauth_cname'),
            oauthScope: val('f_oauth_scope'),
            oauthRedirectUri: val('f_oauth_redirect')
        };
        return Console.api.post('/admin/mcp-connections', body).then(function (r) {
            closeEdit();
            var msg;
            if (r && r.ready) { msg = '已保存并连接就绪，工具 ' + num(r.toolCount) + ' 个'; }
            else if (r && r.needsAuth) { msg = '已保存，需要授权'; }
            else if (r && r.lastError) { msg = '已保存，连接未就绪：' + shortErr(r.lastError, 90); }
            else { msg = (r && r.message) || '已保存'; }
            Console.toast(msg, { type: 'success', wide: true });
            load();
        }).catch(function (e) {
            Console.toast('保存失败：' + errText(e), { type: 'error', wide: true });
        });
    }

    // ==================== 操作 ====================

    function toggleConn(c) {
        var to = c.enabled === false;
        return Console.api.post('/admin/mcp-connections/' + encodeURIComponent(c.name) + '/toggle',
            { enabled: to }).then(function () {
                Console.toast(to ? '已启用' : '已停用', { type: 'success' });
                load();
            }).catch(function (e) {
                Console.toast('操作失败：' + errText(e), { type: 'error' });
            });
    }

    function testConn(c) {
        return Console.api.post('/admin/mcp-connections/' + encodeURIComponent(c.name) + '/test', null)
            .then(function (r) {
                var lines = [(r && r.message) || '测试完成'];
                if (r && r.tools && r.tools.length) { lines.push('工具：' + r.tools.join(', ')); }
                if (r && r.lastError && !r.ready) { lines.push('错误：' + r.lastError); }
                Console.toast(lines.join('\n'), { type: (r && r.ready) ? 'success' : 'error', wide: true });
                load();
            }).catch(function (e) {
                Console.toast('测试失败：' + errText(e), { type: 'error', wide: true });
            });
    }

    function delConn(c) {
        return Console.confirm('确认删除 MCP 连接 ' + c.name + '？\n\n其工具会被摘除、绑定会解绑（不删域）。',
            { title: '删除 MCP 连接', okText: '删除', danger: true }).then(function (ok) {
                if (!ok) { return; }
                return Console.api.del('/admin/mcp-connections?name=' + encodeURIComponent(c.name))
                    .then(function () {
                        state.selected = '';
                        load();
                    }).catch(function (e) {
                        Console.toast('删除失败：' + errText(e), { type: 'error' });
                    });
            });
    }

    function startOAuth(name) {
        return Console.api.post('/admin/mcp-connections/' + encodeURIComponent(name) + '/oauth/start', null)
            .then(function (r) {
                if (r && r.authorizeUrl) { location.href = r.authorizeUrl; return; }
                throw new Error('授权端点未返回授权链接');
            }).catch(function (e) {
                Console.toast('发起授权失败：' + errText(e), { type: 'error' });
            });
    }

    function saveBind() {
        var c = findConn(state.selected);
        if (!c) { return; }
        var name = c.name;
        var calls = [];
        state.domains.forEach(function (d) {
            var cur = (state.bindings.domainBindings[d.name] || []).slice();
            var want = state.bindPick.indexOf(d.name) >= 0;
            var has = cur.indexOf(name) >= 0;
            if (want === has) { return; }
            var next = want ? cur.concat([name]) : cur.filter(function (x) { return x !== name; });
            calls.push({
                domain: d.name,
                p: Console.api.put('/admin/mcp-bindings/' + encodeURIComponent(d.name), { connections: next })
            });
        });
        if (!calls.length) { Console.toast('没有需要保存的改动', { type: 'success' }); return; }
        Promise.all(calls.map(function (x) {
            return x.p.then(function () { return null; })
                .catch(function (e) { return x.domain + '：' + errText(e); });
        })).then(function (errs) {
            var bad = errs.filter(Boolean);
            Console.toast(bad.length ? '部分域保存失败：\n' + bad.join('\n') : '绑定已保存',
                { type: bad.length ? 'error' : 'success', wide: true });
            state.bindTouched = false;
            load();
        });
    }

    function onAct(act, btn) {
        var c = findConn(state.selected);
        if (!c) { return; }
        if (act === 'test') { Console.busy(btn, function () { return testConn(c); }, '测试中…'); }
        else if (act === 'edit') { openEdit(c.name); }
        else if (act === 'toggle') { Console.busy(btn, function () { return toggleConn(c); }, '处理中…'); }
        else if (act === 'delete') { delConn(c); }
        else if (act === 'oauth') { startOAuth(c.name); }
        else if (act === 'reloadTools') { state.toolsFor = ''; loadTools(c.name); }
        else if (act === 'toolDetail') {
            var tool = btn.getAttribute('data-tool') || '';
            state.toolDetail = state.toolDetail === tool ? '' : tool;
            renderTools(c);
        }
        else if (act === 'editAllow') { openEdit(c.name); }
        else if (act === 'saveBind') { Console.busy(btn, function () { saveBind(); return Promise.resolve(); }, '保存中…'); }
        else if (act === 'bindClear') {
            state.bindTouched = true;
            state.bindPick = [];
            renderBind(c);
        }
    }

    function actTarget(e) {
        var t = e.target;
        return (t && t.closest) ? t.closest('[data-act]') : null;
    }

    function bindActions() {
        var create = el('createBtn');
        if (create) { create.addEventListener('click', openCreate); }
        var refresh = el('refreshBtn');
        if (refresh) { refresh.addEventListener('click', function () { load(); }); }
        var search = el('connSearch');
        if (search) { search.addEventListener('input', renderList); }
        var filter = el('statusFilter');
        if (filter) { filter.addEventListener('change', renderList); }

        var list = el('connList');
        if (list) {
            list.addEventListener('click', function (e) {
                var t = e.target;
                var row = (t && t.closest) ? t.closest('.dp-mcp-item') : null;
                if (!row) { return; }
                state.selected = row.getAttribute('data-name');
                list.querySelectorAll('.dp-mcp-item').forEach(function (r) {
                    r.classList.toggle('on', r === row);
                });
                state.bindTouched = false;
                state.toolsFor = '';
                state.toolDetail = '';
                renderDetail();
            });
        }

        var tabs = el('tabBar');
        if (tabs) {
            tabs.addEventListener('click', function (e) {
                var t = e.target;
                var b = (t && t.closest) ? t.closest('[data-tab]') : null;
                if (b) { selectTab(b.getAttribute('data-tab')); }
            });
        }

        ['statusBar', 'paneOverview', 'paneTools', 'paneAuth', 'paneBind'].forEach(function (id) {
            var box = el(id);
            if (!box) { return; }
            box.addEventListener('click', function (e) {
                var b = actTarget(e);
                if (b) { onAct(b.getAttribute('data-act'), b); }
            });
        });

        var bindPane = el('paneBind');
        if (bindPane) {
            bindPane.addEventListener('click', function (e) {
                var t = e.target;
                var card = (t && t.closest) ? t.closest('[data-pick]') : null;
                if (!card) { return; }
                var d = card.getAttribute('data-pick');
                var i = state.bindPick.indexOf(d);
                if (i >= 0) { state.bindPick.splice(i, 1); } else { state.bindPick.push(d); }
                state.bindTouched = true;
                var c = findConn(state.selected);
                if (c) { renderBind(c); }
            });
        }

        var allow = el('allowTools');
        if (allow) {
            allow.addEventListener('change', function (e) {
                var t = e.target;
                if (!t || !t.getAttribute('data-allow')) { return; }
                var name = t.getAttribute('data-allow');
                var i = state.allowPick.indexOf(name);
                if (t.checked && i < 0) { state.allowPick.push(name); }
                if (!t.checked && i >= 0) { state.allowPick.splice(i, 1); }
            });
        }

        var editClose = el('editClose');
        if (editClose) { editClose.addEventListener('click', closeEdit); }
        var editSave = el('editSaveBtn');
        if (editSave) {
            editSave.addEventListener('click', function () {
                Console.busy(this, saveEdit, '保存中…');
            });
        }
        var editClear = el('editClearBtn');
        if (editClear) { editClear.addEventListener('click', openCreate); }
        var editModal = el('editModal');
        if (editModal) {
            editModal.addEventListener('click', function (e) {
                if (e.target === editModal) { closeEdit(); }
            });
        }
    }

    // ==================== 加载与轮询 ====================

    function load() {
        setHtml('connList', Console.loadingHtml('正在加载 MCP 连接…'));
        return Promise.all([
            Console.api.get('/admin/mcp-connections').catch(function (e) { return { __error: e }; }),
            Console.api.get('/admin/mcp-bindings').catch(function () {
                return { domainBindings: {}, connectionBindings: {} };
            }),
            Console.api.get('/admin/domains').catch(function () { return { domains: [] }; })
        ]).then(function (rs) {
            if (rs[0] && rs[0].__error) {
                setHtml('connList', '<div class="dp-mcp-err">读取失败：'
                    + esc(errText(rs[0].__error)) + '</div>');
                return;
            }
            state.conns = (rs[0] && rs[0].connections) || [];
            state.settingsFile = (rs[0] && rs[0].settingsFile) || '';
            state.bindings = rs[1] || { domainBindings: {}, connectionBindings: {} };
            state.domains = (rs[2] && rs[2].domains) || [];
            state.lastSync = Date.now();
            if (!findConn(state.selected)) {
                state.selected = state.conns.length ? state.conns[0].name : '';
            }
            state.bindTouched = false;
            state.listSig = state.conns.map(function (c) { return c.name; }).join('\u0001');
            renderStats();
            renderList();
            setText('syncHint', '上次同步 ' + timeOf(state.lastSync));
            renderDetail();
        });
    }

    function poll() {
        if (!checked('autoRefresh') || state.editing) { return; }
        return Console.api.get('/admin/mcp-connections').then(function (r) {
            state.conns = (r && r.connections) || [];
            state.lastSync = Date.now();
            patchStatus();
        }).catch(function () { /* 轮询失败静默，不打断当前操作 */ });
    }

    Console.renderSidebar('mcp');
    Console.ready(function () {
        var q = new URLSearchParams(location.search);
        var auth = q.get('auth');
        if (auth === 'ok') {
            Console.toast('授权成功：连接 ' + (q.get('name') || '') + ' 已就绪，工具对绑定域可见',
                { type: 'success' });
        } else if (auth === 'fail') {
            Console.toast('授权失败：' + (q.get('reason') || '未知原因'), { type: 'error' });
        }
        if (auth) { history.replaceState(null, '', location.pathname); }
        bindActions();
        load().then(function () { setInterval(poll, POLL_MS); });
    });
})();

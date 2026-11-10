/* ==========================================================================
   P1 · instances 页脚本
   一个页面两块：工具目录（内置可改说明 / 远程与 MCP 只读）+ 实例熔断表。
   ========================================================================== */
(function () {
    var AUTO_INTERVAL = 10000;
    var timer = null;

    var TOOLS = [];          /* 工具目录快照，只由 loadTools 更新 */
    var openName = null;     /* 当前展开的工具 */
    var dirty = false;       /* 编辑区是否有未保存的输入 */
    var toolSeq = 0;         /* 工具区请求并发守卫 */

    function esc(s) {
        return Console.escapeHtml(s == null ? '' : String(s));
    }

    function fmtTime(ms) {
        if (!ms) { return '—'; }
        var d = new Date(ms);
        var p = function (n) { return (n < 10 ? '0' : '') + n; };
        return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }

    function fmtSilent(ms) {
        if (ms == null) { return ''; }
        if (ms < 1000) { return '刚刚'; }
        var s = Math.floor(ms / 1000);
        if (s < 60) { return s + ' 秒前'; }
        var m = Math.floor(s / 60);
        if (m < 60) { return m + ' 分钟前'; }
        return Math.floor(m / 60) + ' 小时前';
    }

    function el(id) {
        return document.getElementById(id);
    }

    /* ==================================================================
       实例熔断区：只读 + 断开 / 恢复两个动作。
       判据全在服务端（lastSeen + 超时窗），前端不再算一遍「多久算死」。

       这一块的轮询每 10 秒跑一次，只允许碰统计卡与 instanceBody。
       工具目录的编辑区在另一个 tbody，碰一下就会把用户写到一半的说明清空。
       ================================================================== */

    function stateTag(it) {
        var cls = it.state === 'ONLINE' ? 'read'
            : (it.rejecting ? 'destructive' : 'write');
        var label = it.stateLabel || it.state;
        if (it.rejecting) { label += '（重连返 410）'; }
        if (it.muted) { label += '（工具不可用）'; }
        return '<span class="tag ' + cls + '">' + esc(label) + '</span>';
    }

    function actionCell(it) {
        var id = esc(it.instanceId);
        if (it.rejecting) {
            return '<span class="muted">已吊销（只能由接口恢复）</span>';
        }
        if (it.muted) {
            return '<button class="btn btn-sm btn-outline-secondary" data-act="restore" data-id="' + id
                + '" title="实例仍在心跳，恢复即等待下次心跳重建副本">恢复工具调用</button>';
        }
        return '<button class="btn btn-sm btn-outline-secondary" data-act="mute" data-id="' + id
            + '" title="摘除工具副本，心跳照常受理">断开工具调用</button>';
    }

    function renderInstances(data) {
        var stats = data.stats || {};
        var list = data.instances || [];

        var online = el('statOnline');
        online.textContent = stats.onlineCount || 0;
        online.className = 'dp-stat-value' + ((stats.onlineCount || 0) ? '' : ' warn');
        var note = [];
        if (stats.mutedCount) { note.push(stats.mutedCount + ' 个已熔断'); }
        if (stats.drainingCount) { note.push(stats.drainingCount + ' 个清理中'); }
        if (stats.forceOfflineCount) { note.push(stats.forceOfflineCount + ' 个已强制下线'); }
        if (!note.length) { note.push((stats.instanceCount || 0) + ' 条在线表记录'); }
        el('statOnlineNote').textContent = note.join('，');

        el('statReplica').textContent = stats.remoteReplicaCount || 0;
        el('statReplica').className = 'dp-stat-value' + ((stats.remoteReplicaCount || 0) ? '' : ' warn');
        el('statReplicaNote').textContent = '同名多实例 = 多副本，调用时随机选一个';

        el('statTools').textContent = stats.registeredToolCount || 0;
        el('statTools').className = 'dp-stat-value';
        el('statToolsNote').textContent = '逻辑工具条目数（含本地 Bean 工具）';

        el('statTimeout').textContent = (stats.timeoutSeconds || 0) + ' 秒';
        el('statTimeout').className = 'dp-stat-value';

        var body = el('instanceBody');
        var wrap = el('tableWrap');
        var empty = el('emptyHint');
        if (!list.length) {
            body.innerHTML = '';
            wrap.style.display = 'none';
            empty.style.display = 'block';
            return;
        }
        wrap.style.display = 'block';
        empty.style.display = 'none';

        body.innerHTML = list.map(function (it) {
            var tools = (it.toolNames || []).map(function (n) {
                return '<span class="tag perm">' + esc(n) + '</span>';
            }).join('');
            return '<tr>'
                + '<td class="mono">' + esc(it.instanceId) + '</td>'
                + '<td class="mono">' + esc(it.endpoint) + '</td>'
                + '<td>' + stateTag(it) + '</td>'
                + '<td>' + fmtTime(it.lastSeen)
                +   '<div class="muted">' + esc(fmtSilent(it.silentMillis)) + '</div></td>'
                + '<td class="tools-cell">' + (tools || '<span class="muted">0</span>')
                +   '<div class="muted">共 ' + (it.toolCount || 0) + ' 个</div></td>'
                + '<td class="mono muted">' + esc(it.digest || '—') + '</td>'
                + '<td>' + actionCell(it) + '</td>'
                + '</tr>';
        }).join('');

        body.querySelectorAll('button[data-act]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                act(btn.getAttribute('data-act'), btn.getAttribute('data-id'), btn);
            });
        });
    }

    var loadSeq = 0;
    var LOADED = false;

    function load() {
        var state = el('loadState');
        var seq = ++loadSeq;
        if (!LOADED) {
            el('tableWrap').style.display = 'block';
            el('instanceBody').innerHTML = Console.loadingRowHtml(7, '正在读取实例…');
            state.textContent = '正在读取…';
        }
        return Console.api.get('/admin/instances').then(function (data) {
            if (seq !== loadSeq) { return; }
            LOADED = true;
            renderInstances(data);
            el('updatedAt').textContent = '更新于 ' + fmtTime(Date.now());
            state.textContent = '';
        }).catch(function (e) {
            if (seq !== loadSeq) { return; }
            state.textContent = '读取失败（下面仍是上次成功的数据）：' + e.message;
        });
    }

    var ACTIONS = {
        mute: {
            text: '断开实例「%s」的工具调用？\n\n'
                + '· 立即摘除它注册的全部工具副本，模型不再看到这些工具\n'
                + '· 心跳继续受理：它的进程不会被杀、连接也不会被掐断\n'
                + '· 随时可在控制台「恢复」；它自己重启也会自动重连回来\n'
                + '· 若某个域只由它声明，该域随之消失（再用此域请求会报 10004）\n'
                + '· 正在跑的会话不受影响，下一轮起看到的工具集已收缩'
        },
        restore: {
            text: '恢复实例「%s」的工具调用？\n\n'
                + '· 副本在它的下一次心跳时重建（默认 10 秒内）\n'
                + '· 心跳已停的实例恢复不了，那种情况它没有工具可调，等超时清表即可'
        }
    };

    async function act(action, instanceId, btn) {
        var spec = ACTIONS[action];
        if (!spec) { return; }
        var ok = await Console.confirm(spec.text.replace('%s', instanceId), {
            title: action === 'mute' ? '断开工具调用' : '恢复工具调用',
            okText: '确认',
            danger: action === 'mute'
        });
        if (!ok) { return; }
        Console.busy(btn, function () {
            return Console.api.post('/admin/instances/' + encodeURIComponent(instanceId) + '/' + action)
                .then(function (r) {
                    Console.toast(r.message || '已提交', { type: 'success' });
                    load();
                }).catch(function (e) {
                    Console.toast('操作失败：' + Console.sanitizeError(e.message), { type: 'error' });
                });
        }, action === 'mute' ? '断开中…' : '恢复中…');
    }

    function setupAutoRefresh() {
        var box = el('autoRefresh');
        function sync() {
            if (timer) { clearInterval(timer); timer = null; }
            if (box.checked) {
                timer = setInterval(function () {
                    if (!document.hidden) { load(); }
                }, AUTO_INTERVAL);
            }
        }
        box.addEventListener('change', sync);
        sync();
    }

    /* ==================================================================
       工具目录区
       全量行与详情行一次生成，筛选只切 display、不重建 DOM —— 输入永不丢失。
       只有首次加载、手动重新读取、保存成功后才重建。
       ================================================================== */

    function findTool(name) {
        for (var i = 0; i < TOOLS.length; i++) {
            if (TOOLS[i].name === name) { return TOOLS[i]; }
        }
        return null;
    }

    function findDetail(name) {
        var rows = el('toolBody').querySelectorAll('tr[data-detail]');
        for (var i = 0; i < rows.length; i++) {
            if (rows[i].getAttribute('data-detail') === name) { return rows[i]; }
        }
        return null;
    }

    function kindTag(kind) {
        var label = kind === 'LOCAL' ? '内置' : (kind === 'MCP' ? 'MCP' : '远程');
        var cls = kind === 'LOCAL' ? 'kind-local' : (kind === 'MCP' ? 'kind-mcp' : 'kind-remote');
        return '<span class="kind-tag ' + cls + '">' + label + '</span>';
    }

    function paramRow(p, editable) {
        var indent = (p.depth || 0) * 18;
        var head = '<div class="param-label"><code>' + esc(p.path) + '</code>'
            + ' <span class="muted">' + esc(p.type) + '</span>'
            + (p.overridden ? ' <span class="kind-tag kind-local">已改</span>' : '')
            + '</div>';
        var body = editable
            ? '<input type="text" class="form-control form-control-sm mono" data-param="'
                + esc(p.path) + '" value="' + esc(p.description) + '">'
            : '<div class="mono">' + esc(p.description) + '</div>';
        var baseline = (editable && p.overridden)
            ? '<div class="baseline-line">原文：' + esc(p.baselineDescription) + '</div>' : '';
        return '<div class="param-row" style="padding-left:' + indent + 'px">'
            + head + body + baseline + '</div>';
    }

    function detailHtml(t) {
        var editable = !!t.editable;
        var head = '<div class="tool-detail-head">工具说明'
            + (t.overridden ? ' <span class="kind-tag kind-local">已改</span>' : '')
            + (t.drift ? '<span class="drift-tag">上游说明已变化</span>' : '')
            + '</div>';
        var desc = editable
            ? '<textarea class="form-control mono tool-desc-input" data-tool-desc rows="3">'
                + esc(t.description) + '</textarea>'
                + (t.description !== t.baselineDescription
                    ? '<div class="baseline-line">原文：' + esc(t.baselineDescription) + '</div>' : '')
            : '<div class="mono">' + esc(t.description) + '</div>';
        var params = (t.params || []).map(function (p) {
            return paramRow(p, editable);
        }).join('');
        var foot = editable
            ? '<div class="tool-detail-foot">'
                + '<span class="tool-detail-status"></span>'
                + '<button type="button" class="btn btn-sm btn-outline-secondary" data-tool-act="close">收起</button>'
                + '<button type="button" class="btn btn-sm btn-outline-secondary" data-tool-act="reset"'
                +   (t.overridden ? '' : ' disabled') + '>恢复默认</button>'
                + '<button type="button" class="btn btn-sm btn-primary" data-tool-act="save">保存</button>'
                + '</div>'
            : '<div class="tool-detail-foot">'
                + '<button type="button" class="btn btn-sm btn-outline-secondary" data-tool-act="close">收起</button>'
                + '</div>';
        return '<div class="tool-detail">' + head + desc
            + '<div class="tool-detail-head">参数说明</div>'
            + (params || '<div class="muted">没有参数</div>')
            + foot + '</div>';
    }

    function toolRows(t) {
        var name = esc(t.name);
        var domains = (t.domains || []).join('、');
        return '<tr data-tool-row="' + name + '" data-tool="' + name + '">'
            + '<td class="mono tool-name">' + name + '</td>'
            + '<td>' + kindTag(t.kind) + '</td>'
            + '<td><div class="tool-desc">' + esc(t.description || '—') + '</div></td>'
            + '<td class="muted">' + ((t.params || []).length) + ' 个</td>'
            + '<td class="mono muted">' + esc(domains || '—') + '</td>'
            + '<td class="mono muted">' + (t.replicaCount || 0) + '</td>'
            + '<td><button type="button" class="btn btn-sm btn-outline-secondary" data-tool-act="toggle"'
            +   ' data-tool="' + name + '">' + (t.editable ? '编辑' : '查看') + '</button></td>'
            + '</tr>'
            + '<tr class="tool-detail-row" data-detail="' + name + '" style="display:none;">'
            +   '<td colspan="7">' + detailHtml(t) + '</td></tr>';
    }

    function matches(t, kind, q) {
        if (kind && t.kind !== kind) { return false; }
        if (q) {
            var hay = (String(t.name) + ' ' + String(t.description || '')).toLowerCase();
            if (hay.indexOf(q) < 0) { return false; }
        }
        return true;
    }

    /** 只切 display，不碰任何输入控件 */
    function applyView() {
        var kind = el('toolKind').value;
        var q = (el('toolSearch').value || '').trim().toLowerCase();
        var body = el('toolBody');
        body.querySelectorAll('tr[data-tool-row]').forEach(function (row) {
            var t = findTool(row.getAttribute('data-tool'));
            row.style.display = (t && matches(t, kind, q)) ? '' : 'none';
        });
        body.querySelectorAll('tr[data-detail]').forEach(function (row) {
            var name = row.getAttribute('data-detail');
            var t = findTool(name);
            row.style.display = (t && openName === name && matches(t, kind, q)) ? '' : 'none';
        });
    }

    function renderTools() {
        var counts = { LOCAL: 0, REMOTE: 0, MCP: 0 };
        TOOLS.forEach(function (t) {
            if (counts[t.kind] != null) { counts[t.kind]++; }
        });
        el('toolSummary').textContent = '内置 ' + counts.LOCAL
            + ' · 远程 ' + counts.REMOTE + ' · MCP ' + counts.MCP;

        var body = el('toolBody');
        if (!TOOLS.length) {
            body.innerHTML = '<tr><td colspan="7" class="muted">注册表里还没有工具</td></tr>';
            return;
        }
        body.innerHTML = TOOLS.map(toolRows).join('');
        applyView();
    }

    function loadTools() {
        var seq = ++toolSeq;
        if (!el('toolBody').children.length) {
            el('toolBody').innerHTML = Console.loadingRowHtml(7, '正在读取工具…');
        }
        return Console.api.get('/admin/tool-catalog').then(function (data) {
            if (seq !== toolSeq) { return; }
            TOOLS = (data && data.tools) || [];
            if (openName && !findTool(openName)) { openName = null; }
            renderTools();
            el('toolState').textContent = '更新于 ' + fmtTime(Date.now());
        }).catch(function (e) {
            if (seq !== toolSeq) { return; }
            el('toolState').textContent = '读取失败：' + Console.sanitizeError(e.message);
        });
    }

    function setStatus(detail, text, cls) {
        var box = detail.querySelector('.tool-detail-status');
        if (!box) { return; }
        box.textContent = text || '';
        box.className = 'tool-detail-status' + (cls ? ' ' + cls : '');
    }

    function saveText(name, btn) {
        var detail = findDetail(name);
        if (!detail) { return; }
        var desc = detail.querySelector('[data-tool-desc]');
        var paramDescriptions = {};
        detail.querySelectorAll('[data-param]').forEach(function (input) {
            paramDescriptions[input.getAttribute('data-param')] = input.value;
        });
        var payload = {
            description: desc ? desc.value : '',
            paramDescriptions: paramDescriptions
        };
        Console.busy(btn, function () {
            return Console.api.put('/admin/tool-catalog/' + encodeURIComponent(name) + '/text', payload)
                .then(function (r) {
                    dirty = false;
                    Console.toast(r.message || '已保存', { type: 'success' });
                    return loadTools();
                }).catch(function (e) {
                    setStatus(detail, '保存失败：' + Console.sanitizeError(e.message), 'err');
                });
        }, '保存中…');
    }

    function resetText(name, btn) {
        Console.confirm('恢复「' + name + '」的注解原文？当前写的说明会被清除。', {
            title: '恢复默认', okText: '恢复', danger: true
        }).then(function (ok) {
            if (!ok) { return; }
            Console.busy(btn, function () {
                return Console.api.del('/admin/tool-catalog/' + encodeURIComponent(name) + '/text')
                    .then(function (r) {
                        dirty = false;
                        Console.toast(r.message || '已恢复', { type: 'success' });
                        return loadTools();
                    }).catch(function (e) {
                        var detail = findDetail(name);
                        if (detail) { setStatus(detail, '恢复失败：' + Console.sanitizeError(e.message), 'err'); }
                    });
            }, '恢复中…');
        });
    }

    function manualReloadTools() {
        if (dirty && openName) {
            Console.confirm('有未保存的说明，重新读取会丢弃。继续？', {
                title: '重新读取', okText: '继续', danger: true
            }).then(function (ok) {
                if (ok) { dirty = false; openName = null; loadTools(); }
            });
            return;
        }
        loadTools();
    }

    function bindToolEvents() {
        var body = el('toolBody');
        body.addEventListener('click', function (e) {
            var btn = e.target.closest ? e.target.closest('button[data-tool-act]') : null;
            if (!btn) { return; }
            var action = btn.getAttribute('data-tool-act');
            var name = btn.getAttribute('data-tool');
            if (!name) {
                var detail = btn.closest('tr[data-detail]');
                name = detail ? detail.getAttribute('data-detail') : null;
            }
            if (!name) { return; }
            if (action === 'toggle') {
                openName = openName === name ? null : name;
                applyView();
            } else if (action === 'close') {
                openName = null;
                applyView();
            } else if (action === 'save') {
                saveText(name, btn);
            } else if (action === 'reset') {
                resetText(name, btn);
            }
        });
        body.addEventListener('input', function (e) {
            if (e.target.matches && e.target.matches('[data-tool-desc], [data-param]')) {
                dirty = true;
            }
        });
        el('toolKind').addEventListener('change', applyView);
        el('toolSearch').addEventListener('input', applyView);
        el('toolRefreshBtn').addEventListener('click', manualReloadTools);
    }

    Console.renderSidebar('instances');
    el('refreshBtn').addEventListener('click', load);
    bindToolEvents();
    Console.ready(function () {
        load();
        loadTools();
        setupAutoRefresh();
    });
})();

/* ==========================================================================
   F04-d/e · sessions 页脚本
   按 sessionId 查档：轮号分页拉归档，单轮展示对话内容 / 执行步骤 / 执行情况。
   会话列表表格按域筛选，点行回填查询条件并进入查档。
   查档只读；唯一写操作是「清空会话」（二次确认后按 sessionId 单会话清除）。
   ========================================================================== */
(function () {
    var esc = Console.escapeHtml;
    var PAGE_SIZE = 20;

    var state = {
        sessionId: '', domain: '', sessionKey: '',
        total: 0, from: 1, size: PAGE_SIZE,
        turns: [], reports: [], selected: null,
        tableSelected: ''
    };

    function el(id) { return document.getElementById(id); }
    function setHtml(id, html) { var n = el(id); if (n) { n.innerHTML = html; } }
    function setText(id, v) { var n = el(id); if (n) { n.textContent = v; } }
    function val(id) { var n = el(id); return n ? String(n.value || '').trim() : ''; }
    function disable(id, on) { var n = el(id); if (n) { n.disabled = !!on; } }

    function num(v) { return (v === undefined || v === null || v === '') ? '—' : String(v); }

    /* Redis 里时间是毫秒数值，Spring MVC 序列化出来可能是 ISO 串；两种都认 */
    function toMs(v) {
        if (typeof v === 'number') { return v; }
        if (v === null || v === undefined) { return NaN; }
        var s = String(v);
        if (/^-?\d+$/.test(s)) { return Number(s); }
        var t = Date.parse(s);
        return isNaN(t) ? NaN : t;
    }

    function when(v) {
        var ms = toMs(v);
        return isNaN(ms) ? '—' : new Date(ms).toLocaleString();
    }

    function dur(a, b) {
        var x = toMs(a), y = toMs(b);
        if (isNaN(x) || isNaN(y) || y < x) { return '—'; }
        var d = y - x;
        return d < 1000 ? (d + ' ms') : ((Math.round(d / 100) / 10) + ' s');
    }

    function short(s) {
        s = String(s || '');
        return s.length > 70 ? s.slice(0, 70) + '…' : s;
    }

    function textBlock(s) {
        s = String(s || '');
        if (!s) { return '<span class="dp-sessions-empty">—</span>'; }
        if (s.length <= 500) { return '<div class="dp-sessions-text">' + esc(s) + '</div>'; }
        return '<details class="dp-sessions-fold"><summary>展开全文（' + s.length + ' 字）</summary>'
            + '<pre class="dp-sessions-text">' + esc(s) + '</pre></details>';
    }

    function fact(label, value) {
        return '<div class="dp-sessions-fact"><div class="dp-sessions-fact-label">' + esc(label)
            + '</div><div class="dp-sessions-fact-value">' + value + '</div></div>';
    }

    function section(title, body) {
        return '<div class="dp-sessions-section"><div class="dp-sessions-section-title">'
            + esc(title) + '</div>' + body + '</div>';
    }

    function renderFacts(d) {
        setHtml('sessionFacts',
            fact('会话键', esc(d && d.sessionKey ? String(d.sessionKey) : '—'))
            + fact('轮数', esc(num(d ? d.total : null)))
            + fact('记忆条数', esc(num(d ? d.memoryMessages : null)))
            + fact('装配清单', esc(num(d ? d.assemblyReportTotal : null))));
    }

    function turnStatus(t) {
        if (!t.finishedAt) { return '进行中'; }
        var f = t.flags || {};
        if (f.aborted) { return '中止'; }
        if (f.approvalOutcome === 'REJECTED') { return '审批拒绝'; }
        return '完成';
    }

    function isBad(t) {
        if (!t.finishedAt || (t.flags && t.flags.aborted)) { return true; }
        var flow = t.flow || [];
        for (var i = 0; i < flow.length; i++) {
            if (flow[i].type === 'ERROR') { return true; }
        }
        return false;
    }

    function renderTurnList() {
        if (!state.turns.length) { setHtml('turnList', ''); return; }
        setHtml('turnList', state.turns.map(function (t) {
            var user = t.user || {};
            return '<div class="dp-sessions-turn' + (state.selected === t.turnId ? ' on' : '') + '"'
                + ' data-turn="' + esc(String(t.turnId)) + '">'
                + '<div class="dp-sessions-turn-head">'
                + '<span class="dp-sessions-turn-no">#' + esc(num(t.turnId)) + '</span>'
                + '<span class="dp-sessions-turn-time">' + esc(when(t.startedAt)) + '</span>'
                + (isBad(t) ? '<span class="dp-sessions-dot"></span>' : '')
                + '</div>'
                + '<div class="dp-sessions-turn-digest">' + esc(short(t.digest || user.text || '')) + '</div>'
                + '<div class="dp-sessions-turn-meta">' + esc(dur(t.startedAt, t.finishedAt))
                + ' · ' + esc(turnStatus(t)) + '</div>'
                + '</div>';
        }).join(''));
        bindTurnClicks();
        scrollSelected();
    }

    /* 默认选最新一轮，列表却从旧到新排：不滚一下选中行落在视口外 */
    function scrollSelected() {
        var box = el('turnList');
        if (!box) { return; }
        var on = box.querySelector('.dp-sessions-turn.on');
        if (!on) { return; }
        var br = box.getBoundingClientRect(), nr = on.getBoundingClientRect();
        if (nr.bottom > br.bottom) { box.scrollTop += nr.bottom - br.bottom + 8; }
        else if (nr.top < br.top) { box.scrollTop += nr.top - br.top - 8; }
    }

    function bindTurnClicks() {
        var box = el('turnList');
        if (!box) { return; }
        box.querySelectorAll('[data-turn]').forEach(function (n) {
            n.addEventListener('click', function () {
                state.selected = Number(n.getAttribute('data-turn'));
                box.querySelectorAll('.dp-sessions-turn').forEach(function (row) {
                    row.classList.toggle('on', row === n);
                });
                renderDetail();
                scrollSelected();
            });
        });
    }

    function renderPager() {
        var from = state.from, total = state.total, size = state.size;
        var end = state.turns.length ? from + state.turns.length - 1 : from - 1;
        setText('pageInfo', total ? (from + '–' + end + ' / ' + total + ' 轮') : '0 轮');
        disable('btnPrevPage', total === 0 || from <= 1);
        disable('btnNextPage', total === 0 || from + size > total);
    }

    function conversationHtml(t) {
        var u = t.user || {};
        var media = u.media || [];
        var mediaHtml = '';
        if (media.length) {
            mediaHtml = '<div class="dp-sessions-media">' + media.map(function (m) {
                return '<code>' + esc(String(m.attachmentId || m.ref || m.kind || '附件')) + '</code>';
            }).join('') + '</div>';
        }
        return '<div class="dp-sessions-line"><span class="dp-sessions-role">用户</span>'
            + '<div class="dp-sessions-line-body">' + textBlock(u.text || '') + mediaHtml + '</div></div>'
            + '<div class="dp-sessions-line"><span class="dp-sessions-role">助手</span>'
            + '<div class="dp-sessions-line-body">' + textBlock(t.answer || '') + '</div></div>';
    }

    function stepsHtml(t) {
        var flow = t.flow || [];
        if (!flow.length) { return '<div class="dp-sessions-empty">—</div>'; }
        var prev = t.startedAt;
        return '<div class="dp-sessions-steps">' + flow.map(function (f) {
            var d = dur(prev, f.at);
            prev = f.at;
            var type = String(f.type || '');
            var name = f.toolName ? '<code>' + esc(String(f.toolName)) + '</code>' : '';
            return '<div class="dp-sessions-step' + (type === 'ERROR' ? ' err' : '') + '">'
                + '<div class="dp-sessions-step-head">'
                + '<span class="dp-sessions-step-type">' + esc(type) + '</span>' + name
                + '<span class="dp-sessions-step-gap">' + esc(d) + '</span></div>'
                + '<div class="dp-sessions-step-body">' + textBlock(f.text || '') + '</div></div>';
        }).join('') + '</div>';
    }

    function droppedText(r) {
        var out = [];
        if (r.summaryDroppedLines > 0) { out.push('摘要 ' + r.summaryDroppedLines + ' 行'); }
        if (r.windowDroppedMessages > 0) { out.push('窗口 ' + r.windowDroppedMessages + ' 条'); }
        if (r.droppedCardTurnIds && r.droppedCardTurnIds.length) {
            out.push('卡片轮 ' + r.droppedCardTurnIds.join('/'));
        }
        return out.length ? out.join('、') : '无';
    }

    function factsHtml(t) {
        var usage = t.usage || {};
        var flags = t.flags || {};
        var report = null;
        for (var i = 0; i < state.reports.length; i++) {
            if (state.reports[i].turnId === t.turnId) { report = state.reports[i]; break; }
        }
        var tags = [];
        if (flags.truncated) { tags.push('已截断'); }
        if (flags.aborted) { tags.push('aborted'); }
        if (flags.indexed) { tags.push('indexed'); }
        if (flags.approvalOutcome) { tags.push(String(flags.approvalOutcome)); }
        var cells = [
            ['耗时', dur(t.startedAt, t.finishedAt)],
            ['状态', turnStatus(t)],
            ['输入 / 输出 / 合计', num(usage.promptTokens) + ' / ' + num(usage.completionTokens)
                + ' / ' + num(usage.totalTokens)],
            ['标记', tags.length ? tags.join('、') : '—'],
            ['装配预算', report ? num(report.totalBudget) : '—'],
            ['装配实际', report ? num(report.totalTokens) : '—'],
            ['窗口消息', report ? num(report.windowMessages) : '—'],
            ['丢弃', report ? droppedText(report) : '—']
        ];
        return '<div class="dp-sessions-facts-grid">' + cells.map(function (c) {
            return fact(c[0], esc(String(c[1])));
        }).join('') + '</div>';
    }

    function renderDetail() {
        var t = null;
        for (var i = 0; i < state.turns.length; i++) {
            if (state.turns[i].turnId === state.selected) { t = state.turns[i]; break; }
        }
        if (!t) { setHtml('turnDetail', ''); return; }
        setHtml('turnDetail',
            '<h3 class="card-title">轮 ' + esc(num(t.turnId)) + ' · ' + esc(when(t.startedAt)) + '</h3>'
            + section('对话内容', conversationHtml(t))
            + section('执行步骤', stepsHtml(t))
            + section('执行情况', factsHtml(t)));
    }

    function turnsUrl(from) {
        var u = '/admin/sessions/turns?sessionId=' + encodeURIComponent(state.sessionId)
            + '&size=' + state.size;
        if (state.domain) { u += '&domain=' + encodeURIComponent(state.domain); }
        if (from) { u += '&from=' + encodeURIComponent(String(from)); }
        return u;
    }

    /* 清空视图但保留输入框：查询切换会话与清空成功后都用这一套 */
    function resetView() {
        state.sessionKey = '';
        state.total = 0;
        state.from = 1;
        state.turns = [];
        state.reports = [];
        state.selected = null;
        setHtml('turnList', '');
        setHtml('turnDetail', '');
        renderFacts(null);
        renderPager();
    }

    function loadPage(from) {
        if (!state.sessionId) { return; }
        setHtml('turnList', Console.loadingHtml('正在读取轮次…'));
        Console.api.get(turnsUrl(from)).then(function (d) {
            if (!d || d.code !== 0) {
                setHtml('turnList', '');
                setHtml('turnDetail', '<div class="dp-sessions-error">'
                    + esc(d && d.error ? String(d.error) : '读取失败') + '</div>');
                return;
            }
            state.sessionKey = d.sessionKey || '';
            state.total = Number(d.total) || 0;
            state.from = Number(d.from) || 1;
            state.size = Number(d.size) || PAGE_SIZE;
            state.turns = d.turns || [];
            state.reports = d.assemblyReports || [];
            state.selected = state.turns.length
                ? state.turns[state.turns.length - 1].turnId : null;
            renderFacts(d);
            renderTurnList();
            renderPager();
            renderDetail();
        }).catch(function (e) {
            setHtml('turnList', '');
            setHtml('turnDetail', '<div class="dp-sessions-error">'
                + esc(e && e.message ? String(e.message) : '读取失败') + '</div>');
        });
    }

    // ==================== F04-e · 按域筛选的会话列表 ====================

    /* 域下拉每次都重填，但保留当前选中：后端返回的 domains 是全部域，不受筛选影响 */
    function fillDomainFilter(domains, current) {
        var sel = el('domainFilter');
        if (!sel) { return; }
        var html = '<option value="">全部域</option>';
        (domains || []).forEach(function (d) {
            var v = String(d || '');
            if (!v) { return; }
            html += '<option value="' + esc(v) + '"' + (v === current ? ' selected' : '')
                + '>' + esc(v) + '</option>';
        });
        sel.innerHTML = html;
        sel.value = current || '';
    }

    function renderSessionTable(list) {
        if (!list.length) {
            setHtml('sessionTableBody', '<tr><td colspan="7" class="dp-sessions-empty">'
                + '该域下没有会话档案</td></tr>');
            return;
        }
        setHtml('sessionTableBody', list.map(function (s) {
            var key = String(s.sessionKey || '');
            return '<tr class="dp-sessions-row' + (key === state.tableSelected ? ' on' : '') + '"'
                + ' data-key="' + esc(key) + '"'
                + ' data-sid="' + esc(String(s.sessionId || '')) + '"'
                + ' data-domain="' + esc(String(s.domain || '')) + '">'
                + '<td><code>' + esc(String(s.sessionId || '')) + '</code></td>'
                + '<td>' + esc(String(s.domain || '—')) + '</td>'
                + '<td>' + esc(num(s.archiveTurns)) + '</td>'
                + '<td>' + esc(num(s.memoryMessages)) + '</td>'
                + '<td>' + esc(num(s.vectorTurns)) + '</td>'
                + '<td>' + esc(num(s.assemblyReports)) + '</td>'
                + '<td><button class="btn btn-sm" type="button">查看</button></td>'
                + '</tr>';
        }).join(''));
        bindSessionRows();
    }

    function bindSessionRows() {
        var body = el('sessionTableBody');
        if (!body) { return; }
        body.querySelectorAll('[data-sid]').forEach(function (row) {
            row.addEventListener('click', function () {
                openSession(row.getAttribute('data-domain'), row.getAttribute('data-sid'),
                    row.getAttribute('data-key'));
            });
        });
    }

    function loadSessions() {
        var current = val('domainFilter');
        var limit = val('sessionLimit') || '50';
        var u = '/admin/sessions/storage?limit=' + encodeURIComponent(limit);
        if (current) { u += '&domain=' + encodeURIComponent(current); }
        Console.api.get(u).then(function (d) {
            var list = (d && d.sessions) || [];
            fillDomainFilter((d && d.domains) || [], current);
            renderSessionTable(list);
            setText('sessionCount', list.length + ' 个会话');
        }).catch(function (e) {
            setHtml('sessionTableBody', '<tr><td colspan="7" class="dp-sessions-error">'
                + esc(e && e.message ? String(e.message) : '读取失败') + '</td></tr>');
            setText('sessionCount', '');
        });
    }

    /* 表格只在加载时渲染一次，选中态就地切，别为一次点击整表重画 */
    function markTableSelection() {
        var body = el('sessionTableBody');
        if (!body) { return; }
        body.querySelectorAll('.dp-sessions-row').forEach(function (row) {
            row.classList.toggle('on', row.getAttribute('data-key') === state.tableSelected);
        });
    }

    /* 表格行 → 查询条 → 查档。带上域可以直接拼键，省掉一次 SCAN 反查 */
    function openSession(domain, sid, key) {
        if (!sid) { return; }
        var sidInput = el('sessionIdInput'), domInput = el('domainInput');
        if (sidInput) { sidInput.value = sid; }
        if (domInput) { domInput.value = domain || ''; }
        state.sessionId = sid;
        state.domain = domain || '';
        state.tableSelected = key || '';
        markTableSelection();
        resetView();
        loadPage(null);
        var split = document.querySelector('.dp-sessions-split');
        if (split && split.scrollIntoView) { split.scrollIntoView({ block: 'start' }); }
    }

    function clearSession() {
        var sid = val('sessionIdInput') || state.sessionId;
        if (!sid) { return; }
        var dom = val('domainInput') || state.domain;
        var key = state.sessionKey || sid;
        Console.confirm('清空会话「' + key + '」？\n\n'
            + '记忆、检查点、归档轮次、检索索引、滚动摘要、装配清单会一并删除，不可恢复。',
            { title: '清空会话', okText: '清空', danger: true }).then(function (ok) {
            if (!ok) { return; }
            disable('btnClear', true);
            Console.api.post('/admin/sessions/clear', { sessionId: sid, domain: dom })
                .then(function (res) {
                    disable('btnClear', false);
                    Console.toast(res.message || '已清空', { type: 'success', wide: true });
                    state.tableSelected = '';
                    resetView();
                    loadSessions();
                }).catch(function (e) {
                    disable('btnClear', false);
                    Console.toast('清空失败：' + (e && e.message ? e.message : '未知错误'),
                        { type: 'error', wide: true });
                });
        });
    }

    function bindActions() {
        var q = el('btnQuery');
        if (q) {
            q.addEventListener('click', function () {
                state.sessionId = val('sessionIdInput');
                state.domain = val('domainInput');
                state.tableSelected = '';
                markTableSelection();
                resetView();
                if (state.sessionId) { loadPage(null); }
            });
        }
        var ex = el('btnExport');
        if (ex) {
            ex.addEventListener('click', function () {
                var sid = val('sessionIdInput') || state.sessionId;
                if (!sid) { return; }
                var u = '/admin/sessions/export?sessionId=' + encodeURIComponent(sid);
                var dom = val('domainInput') || state.domain;
                if (dom) { u += '&domain=' + encodeURIComponent(dom); }
                window.location.href = u;
            });
        }
        var clr = el('btnClear');
        if (clr) {
            clr.addEventListener('click', clearSession);
        }
        var df = el('domainFilter');
        if (df) {
            df.addEventListener('change', loadSessions);
        }
        var sl = el('sessionLimit');
        if (sl) {
            sl.addEventListener('change', loadSessions);
        }
        var rs = el('btnRefreshSessions');
        if (rs) {
            rs.addEventListener('click', loadSessions);
        }
        var prev = el('btnPrevPage');
        if (prev) {
            prev.addEventListener('click', function () {
                loadPage(Math.max(1, state.from - state.size));
            });
        }
        var next = el('btnNextPage');
        if (next) {
            next.addEventListener('click', function () {
                loadPage(state.from + state.size);
            });
        }
        var sid = el('sessionIdInput');
        if (sid) {
            sid.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' && q) { q.click(); }
            });
        }
    }

    Console.renderSidebar('sessions');
    Console.ready(function () {
        renderFacts(null);
        renderPager();
        bindActions();
        loadSessions();
    });
})();

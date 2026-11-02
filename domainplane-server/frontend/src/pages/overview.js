/* ==========================================================================
   overview 页脚本 · 与 overview.html 一一对应
   8 张统计卡 / 运行质量三条占比条 / 工具风险环形图 / 请求趋势折线 / 待配置项。
   数据源三个：/admin/settings、/admin/domains、/admin/metrics(/history)。
   旧版是 overview.html 上一代 DOM（statToolsNote / v ok 这类）的遗留，
   与现页面完全对不上（引用不存在的节点整页报错），本次按现 HTML 重写。
   ========================================================================== */
(function () {
    var esc = Console.escapeHtml;

    function setText(id, v) {
        var el = document.getElementById(id);
        if (el) { el.textContent = v; }
        return el;
    }

    /* 统计卡的基类 dp-stat-value 必须保住：只追加状态色（warn/err），正常态无后缀 */
    function setValue(id, v, tone) {
        var el = setText(id, v);
        if (el) { el.className = 'dp-stat-value' + (tone ? ' ' + tone : ''); }
    }

    function setBar(id, pct, tone) {
        var el = document.getElementById(id);
        if (el) {
            el.style.width = Math.max(0, Math.min(100, pct)) + '%';
            el.className = 'dp-bar-fill ' + tone;
        }
    }

    function fmtPct(v) {
        if (!isFinite(v)) { return '—'; }
        return (Math.round(v * 1000) / 10) + '%';
    }

    function fmtBytes(n) {
        if (n === undefined || n === null || isNaN(n)) { return '—'; }
        var gb = n / 1073741824;
        if (gb >= 1) { return (Math.round(gb * 10) / 10) + ' GB'; }
        var mb = n / 1048576;
        if (mb >= 1) { return (Math.round(mb * 10) / 10) + ' MB'; }
        return Math.max(0, Math.round(n / 1024)) + ' KB';
    }

    function fmtUptime(s) {
        if (!s && s !== 0) { return '—'; }
        var d = Math.floor(s / 86400);
        var h = Math.floor((s % 86400) / 3600);
        var m = Math.floor((s % 3600) / 60);
        if (d > 0) { return d + ' 天 ' + h + ' 小时'; }
        if (h > 0) { return h + ' 小时 ' + m + ' 分'; }
        if (m > 0) { return m + ' 分钟'; }
        return s + ' 秒';
    }

    /* ===== 待配置项：settings + domains 两路结果都到了再判，避免闪出又收走 ===== */
    var pendingTodo = { settings: null, domains: null };

    function tryRenderTodo() {
        var s = pendingTodo.settings;
        var d = pendingTodo.domains;
        if (!s || !d) { return; }
        var items = [];
        if (!s.chatConfigured) {
            items.push({ txt: '对话模型尚未配置，无法开启对话', href: 'models.html', btn: '去配置' });
        }
        if (!s.embeddingConfigured) {
            items.push({
                txt: (s.embeddingProfileCount || 0) > 0
                    ? '已登记向量模型档案但未启用，知识库检索不可用'
                    : '向量模型尚未配置，知识库不可用',
                href: 'models.html', btn: '去配置'
            });
        }
        var st = d.stats || {};
        if (!st.toolCount) {
            items.push({ txt: '没有扫描到任何工具提供者，模型无工具可用', href: 'domains.html', btn: '去域空间' });
        }
        var box = document.getElementById('todoList');
        var card = document.getElementById('todoCard');
        if (!box || !card) { return; }
        if (!items.length) {
            card.hidden = true;
            box.innerHTML = '';
            return;
        }
        card.hidden = false;
        box.innerHTML = items.map(function (it) {
            return '<div class="dp-todo-item"><span class="dp-todo-dot"></span>'
                + '<span class="txt">' + esc(it.txt) + '</span>'
                + '<a class="btn btn-sm btn-outline-primary" href="' + it.href + '">' + esc(it.btn) + '</a>'
                + '</div>';
        }).join('');
    }

    /* ===== 模型状态（第 1 排两卡）：/admin/settings ===== */
    function loadModelStats() {
        Console.refreshModelsWarn().then(function (s) {
            var chatReady = !!s.chatConfigured;
            setValue('statChat', chatReady ? '已就绪' : '未配置', chatReady ? '' : 'warn');
            var embReady = !!s.embeddingConfigured;
            var embRegistered = s.embeddingProfileCount || 0;
            setValue('statEmb', embReady ? '已就绪' : (embRegistered ? '未启用' : '未配置'), embReady ? '' : 'warn');
            pendingTodo.settings = s;
            tryRenderTodo();
        }).catch(function () {
            setValue('statChat', '读取失败', 'err');
            setValue('statEmb', '读取失败', 'err');
        });
    }

    /* ===== 注册统计（第 1 排两卡）+ 工具风险构成：/admin/domains ===== */
    var DONUT_C = 283; /* r=45 的周长，与 console.css 的 dasharray 底数一致 */

    function setDonutArc(id, len, offset) {
        var el = document.getElementById(id);
        if (el) {
            el.setAttribute('stroke-dasharray', len + ' ' + DONUT_C);
            el.setAttribute('stroke-dashoffset', offset);
        }
    }

    function renderDonut(read, write, destructive) {
        var total = read + write + destructive;
        setText('donutTotal', total);
        if (!total) {
            setDonutArc('donutRead', 0, 0);
            setDonutArc('donutWrite', 0, 0);
            setDonutArc('donutDestructive', 0, 0);
            setText('legReadN', '0');
            setText('legWriteN', '0');
            setText('legDestructiveN', '0');
            setText('legReadP', '—');
            setText('legWriteP', '—');
            setText('legDestructiveP', '—');
            return;
        }
        var rl = read / total * DONUT_C;
        var wl = write / total * DONUT_C;
        setDonutArc('donutRead', rl, 0);
        /* dashoffset 负向累加 = 下一段接着上一段画（与 CSS 注释同口径） */
        setDonutArc('donutWrite', wl, -rl);
        setDonutArc('donutDestructive', destructive / total * DONUT_C, -(rl + wl));
        setText('legReadN', read);
        setText('legWriteN', write);
        setText('legDestructiveN', destructive);
        setText('legReadP', fmtPct(read / total));
        setText('legWriteP', fmtPct(write / total));
        setText('legDestructiveP', fmtPct(destructive / total));
    }

    function loadDomains() {
        Console.api.get('/admin/domains').then(function (r) {
            var st = (r && r.stats) || {};
            var toolCount = st.toolCount || 0;
            var domainCount = st.domainCount || 0;
            setValue('statTools', toolCount, toolCount ? '' : 'warn');
            setValue('statDomains', domainCount, domainCount ? '' : 'warn');
            /* 风险构成：域视图的工具表互相重叠（父域/子域都可见同一工具），
               按工具名去重后计数，总数与 statTools 对得上 */
            var seen = {};
            var read = 0, write = 0, destructive = 0;
            function acc(effect) {
                if (effect === 'READ') { read++; }
                else if (effect === 'WRITE') { write++; }
                else if (effect === 'DESTRUCTIVE') { destructive++; }
            }
            function accUnique(t) {
                if (t && t.name && !seen[t.name]) {
                    seen[t.name] = true;
                    acc(t.sideEffect);
                }
            }
            (r.globalTools || []).forEach(accUnique);
            (r.domains || []).forEach(function (d) {
                (d.tools || []).forEach(accUnique);
            });
            renderDonut(read, write, destructive);
            pendingTodo.domains = r;
            tryRenderTodo();
        }).catch(function () {
            setValue('statTools', '—', 'err');
            setValue('statDomains', '—', 'err');
        });
    }

    /* ===== 运行状态（第 2 排四卡）+ 运行质量：/admin/metrics ===== */
    function loadRuntime() {
        Console.api.get('/admin/metrics').then(function (m) {
            var counters = m.counters || {};
            var gauges = m.gauges || {};
            setValue('statUptime', fmtUptime(m.uptimeSeconds));
            setValue('statSessions', m.runningSessions === undefined ? '—' : String(m.runningSessions));
            var heapUsed = m.heapUsedBytes, heapMax = m.heapMaxBytes;
            if (heapMax > 0) {
                var pct = heapUsed / heapMax;
                setValue('statHeap', fmtBytes(heapUsed) + ' / ' + fmtBytes(heapMax), pct > 0.85 ? 'warn' : '');
            } else {
                setValue('statHeap', fmtBytes(heapUsed));
            }
            setValue('statMcpTools', gauges.mcpTools === undefined ? '—' : String(gauges.mcpTools));
            renderQuality(counters);
        }).catch(function () {
            setValue('statUptime', '—', 'err');
            setValue('statSessions', '—', 'err');
            setValue('statHeap', '—', 'err');
            setValue('statMcpTools', '—', 'err');
        });
    }

    function renderQuality(counters) {
        /* 会话召回命中率：越高越好 */
        var hits = counters.recallHits || 0;
        var searches = counters.recallSearches || 0;
        var hitPct = searches ? hits / searches : -1;
        setText('qHitPct', hitPct < 0 ? '—' : fmtPct(hitPct));
        setBar('qHitFill', hitPct < 0 ? 0 : hitPct * 100, hitPct < 0 ? '' : (hitPct >= 0.6 ? 'ok' : 'warn'));
        /* 工具调用失败率：越低越好 */
        var tf = counters.toolCalls || 0;
        var tfPct = tf ? (counters.toolFailures || 0) / tf : -1;
        setText('qTfPct', tfPct < 0 ? '—' : fmtPct(tfPct));
        setBar('qTfFill', tfPct < 0 ? 0 : tfPct * 100, tfPct < 0 ? '' : (tfPct <= 0.05 ? 'ok' : (tfPct <= 0.2 ? 'warn' : 'err')));
        /* MCP 调用失败率 */
        var mc = counters.mcpToolCalls || 0;
        var mcPct = mc ? (counters.mcpToolFailures || 0) / mc : -1;
        setText('qMcpPct', mcPct < 0 ? '—' : fmtPct(mcPct));
        setBar('qMcpFill', mcPct < 0 ? 0 : mcPct * 100, mcPct < 0 ? '' : (mcPct <= 0.05 ? 'ok' : (mcPct <= 0.2 ? 'warn' : 'err')));
    }

    /* ===== 请求趋势：/admin/metrics/history，累计值差分成速率 ===== */
    function loadTrend() {
        var card = document.getElementById('trendCard');
        Console.request('/admin/metrics/history?points=60').then(function (h) {
            var pts = (h && h.points) || [];
            /* 少于 2 个点画不出线：整卡隐藏（服务刚起来时窗口是空的） */
            if (!card || pts.length < 2) {
                if (card) { card.hidden = true; }
                return;
            }
            /* 差分成每分钟速率：相邻两点累计差 ÷ 间隔秒 × 60。
               对话请求 = chat + resume（都是"跑了一轮对话"） */
            var chatRate = [], toolRate = [];
            for (var i = 1; i < pts.length; i++) {
                var a = pts[i - 1].counters || {};
                var b = pts[i].counters || {};
                var dt = (pts[i].t - pts[i - 1].t) || 1;
                chatRate.push(((b.chatRequests || 0) - (a.chatRequests || 0)
                    + (b.resumeRequests || 0) - (a.resumeRequests || 0)) / dt * 60);
                toolRate.push(((b.toolCalls || 0) - (a.toolCalls || 0)) / dt * 60);
            }
            var maxRate = 0;
            chatRate.concat(toolRate).forEach(function (v) {
                if (v > maxRate) { maxRate = v; }
            });
            if (maxRate <= 0) { maxRate = 1; }
            var W = 600, H = 160, TOP = 12, BOTTOM = 14;
            function yOf(v) {
                return H - BOTTOM - v / maxRate * (H - TOP - BOTTOM);
            }
            function pathOf(arr) {
                var d = '';
                for (var j = 0; j < arr.length; j++) {
                    d += (j ? 'L' : 'M') + (j / (arr.length - 1) * W).toFixed(1) + ' ' + yOf(arr[j]).toFixed(1);
                }
                return d;
            }
            function areaOf(arr) {
                if (!arr.length) { return ''; }
                return pathOf(arr) + 'L' + W + ' ' + (H - BOTTOM) + 'L0 ' + (H - BOTTOM) + 'Z';
            }
            var grid = document.getElementById('trendGrid');
            if (grid) {
                var lines = '';
                for (var g = 0; g <= 3; g++) {
                    var y = TOP + g / 3 * (H - TOP - BOTTOM);
                    lines += '<line x1="0" y1="' + y + '" x2="' + W + '" y2="' + y + '"/>';
                }
                grid.innerHTML = lines;
            }
            document.getElementById('trendToolArea').setAttribute('d', areaOf(toolRate));
            document.getElementById('trendChatArea').setAttribute('d', areaOf(chatRate));
            document.getElementById('trendToolLine').setAttribute('d', pathOf(toolRate));
            document.getElementById('trendChatLine').setAttribute('d', pathOf(chatRate));
            var spanSec = pts[pts.length - 1].t - pts[0].t;
            setText('trendWindow', '（最近 ' + Math.max(1, Math.round(spanSec / 60)) + ' 分钟）');
            var peakChat = Math.max.apply(null, chatRate);
            var peakTool = Math.max.apply(null, toolRate);
            setText('trendPeak', '峰值 对话 ' + (Math.round(peakChat * 10) / 10) + ' · 工具 ' + (Math.round(peakTool * 10) / 10) + ' 每分');
            card.hidden = false;
        }).catch(function () {
            if (card) { card.hidden = true; }
        });
    }

    Console.renderSidebar('overview');
    Console.ready(function () {
        loadModelStats();
        loadDomains();
        loadRuntime();
        loadTrend();
    });
})();

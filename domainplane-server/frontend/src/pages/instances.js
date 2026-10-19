/* ==========================================================================
   P1 · instances 页脚本
   自 instances.html 的内联 <script> 机械平移（1 块合并），逻辑未改。
   ========================================================================== */
(function () {
/* 在线实例页：只读 + 断开 / 恢复工具调用两个动作。
       数据全部来自 /admin/instances，前端不自己算存活（判据是服务端的 lastSeen 与超时窗，
       前端再算一遍就会多出一个"多久算死"的副本，迟早与服务端不一致）。 */
    var AUTO_INTERVAL = 10000;
    var timer = null;

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

    /* 状态 → 现有的 tag 色：绿=正常、黄=过渡/待复核、红=已拒（复用 tag 的三个语义色，不新增样式） */
    function stateTag(it) {
        var cls = it.state === 'ONLINE' ? 'read'
            : (it.rejecting ? 'destructive' : 'write');
        var label = it.stateLabel || it.state;
        if (it.rejecting) { label += '（重连返 410）'; }
        if (it.muted) { label += '（工具不可用）'; }
        return '<span class="tag ' + cls + '">' + Console.escapeHtml(label) + '</span>';
    }

    function render(data) {
        var stats = data.stats || {};
        var list = data.instances || [];

        var online = document.getElementById('statOnline');
        online.textContent = stats.onlineCount || 0;
        online.className = 'v' + ((stats.onlineCount || 0) ? ' ok' : ' warn');
        var note = [];
        if (stats.mutedCount) { note.push(stats.mutedCount + ' 个已熔断'); }
        if (stats.drainingCount) { note.push(stats.drainingCount + ' 个清理中'); }
        if (stats.forceOfflineCount) { note.push(stats.forceOfflineCount + ' 个已强制下线'); }
        if (!note.length) { note.push((stats.instanceCount || 0) + ' 条在线表记录'); }
        document.getElementById('statOnlineNote').textContent = note.join('，');

        document.getElementById('statReplica').textContent = stats.remoteReplicaCount || 0;
        document.getElementById('statReplica').className = 'v' + ((stats.remoteReplicaCount || 0) ? ' ok' : ' warn');
        document.getElementById('statReplicaNote').textContent = '同名多实例 = 多副本，调用时随机选一个';

        document.getElementById('statTools').textContent = stats.registeredToolCount || 0;
        document.getElementById('statTools').className = 'v';
        document.getElementById('statToolsNote').textContent = '逻辑工具条目数（含本地 Bean 工具）';

        document.getElementById('statTimeout').textContent = (stats.timeoutSeconds || 0) + ' 秒';
        document.getElementById('statTimeout').className = 'v';

        var body = document.getElementById('instanceBody');
        var wrap = document.getElementById('tableWrap');
        var empty = document.getElementById('emptyHint');
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
                return '<span class="tag perm">' + Console.escapeHtml(n) + '</span>';
            }).join('');
            return '<tr>'
                + '<td class="mono">' + Console.escapeHtml(it.instanceId) + '</td>'
                + '<td class="mono">' + Console.escapeHtml(it.endpoint) + '</td>'
                + '<td>' + stateTag(it) + '</td>'
                + '<td>' + fmtTime(it.lastSeen)
                +   '<div class="muted">' + Console.escapeHtml(fmtSilent(it.silentMillis)) + '</div></td>'
                + '<td class="tools-cell">' + (tools || '<span class="muted">0</span>')
                +   '<div class="muted">共 ' + (it.toolCount || 0) + ' 个</div></td>'
                + '<td class="mono muted">' + Console.escapeHtml(it.digest || '—') + '</td>'
                + '<td>' + actionCell(it) + '</td>'
                + '</tr>';
        }).join('');

        body.querySelectorAll('button[data-act]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                act(btn.getAttribute('data-act'), btn.getAttribute('data-id'));
            });
        });
    }

    /* 操作列随状态变：这一页只做一件事，断开（恢复）某个实例的工具调用。
       按钮按 data-act 分派，避免每加一个动作就多绑一轮选择器。 */
    function actionCell(it) {
        var id = Console.escapeHtml(it.instanceId);
        if (it.rejecting) {
            return '<span class="muted">已吊销（只能由接口恢复）</span>';
        }
        if (it.muted) {
            return '<button class="primary btn btn-primary" data-act="restore" data-id="' + id
                + '" title="实例仍在心跳，恢复即等待下次心跳重建副本">恢复工具调用</button>';
        }
        return '<button class="write btn btn-primary" data-act="mute" data-id="' + id
            + '" title="摘除工具副本，心跳照常受理">断开工具调用</button>';
    }

    /* 并发守卫：轮询、手动"重新读取"、操作后的重载三者可能叠在一起，
       晚发出的请求不一定后返回。只让"最后一次发出"的结果落地，否则旧数据会覆盖新数据，
       表现出来就是"点了刷新但表格没变"。 */
    var loadSeq = 0;

    function load() {
        var state = document.getElementById('loadState');
        var seq = ++loadSeq;
        return Console.request('/admin/instances').then(function (data) {
            if (seq !== loadSeq) { return; }
            render(data);
            state.textContent = '更新于 ' + fmtTime(Date.now());
        }).catch(function (e) {
            if (seq !== loadSeq) { return; }
            /* 读取失败时保留上一次的快照（总比清空好），但把失败写在标题栏上，
               避免用户把"过期数据"当成"当前状态" */
            state.textContent = '读取失败（下面仍是上次成功的数据）：' + e.message;
        });
    }

    /* 三个动作共用一段确认 + 回调：差别只在文案与端点 */
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

    function act(action, instanceId) {
        var spec = ACTIONS[action];
        if (!spec || !confirm(spec.text.replace('%s', instanceId))) {
            return;
        }
        Console.jsonRequest('/admin/instances/' + encodeURIComponent(instanceId) + '/' + action).then(function (r) {
            alert(r.message || '已提交');
            load();
        }).catch(function (e) {
            alert('操作失败：' + e.message);
        });
    }

    function setupAutoRefresh() {
        var box = document.getElementById('autoRefresh');
        function sync() {
            if (timer) { clearInterval(timer); timer = null; }
            if (box.checked) {
                timer = setInterval(function () {
                    /* 页面被切到后台时不刷新：没人看的表格没必要每隔 10 秒打一次接口 */
                    if (!document.hidden) { load(); }
                }, AUTO_INTERVAL);
            }
        }
        box.addEventListener('change', sync);
        sync();
    }

    Console.renderSidebar('instances');
    document.getElementById('refreshBtn').addEventListener('click', load);
    /* 首次加载走 Console.ready：先确认登录态，未登录直接跳登录页，
       不会先打一轮受保护接口留下 401 日志（见 console.js ready 的注释） */
    Console.ready(function () {
        load();
        setupAutoRefresh();
    });
})();

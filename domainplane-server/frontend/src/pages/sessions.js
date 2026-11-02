/* ==========================================================================
   F04 · sessions 页脚本
   会话侧运维：体量盘点（看得见）+ 装配清单（它为什么忘了）+ 副本导出（搬得走）。
   全页只读：没有任何删除动作 —— 记忆与归档只增不减是硬约束。
   ========================================================================== */
(function () {
    var esc = Console.escapeHtml;

    function num(v) {
        return (v === undefined || v === null) ? '—' : String(v);
    }

    function when(ms) {
        if (!ms) { return '—'; }
        var d = new Date(Number(ms));
        return isNaN(d.getTime()) ? '—' : d.toLocaleString();
    }

    function loadStorage() {
        var box = document.getElementById('sessionList');
        /* tbody 初始是空的，先放一行转圈占位，数据到了整个 innerHTML 一起被替换 */
        box.innerHTML = Console.loadingRowHtml(7, '正在读取会话档案…');
        Console.api.get('/admin/sessions/storage?limit=20').then(function (data) {
            var rows = data.sessions || [];
            if (!rows.length) {
                box.innerHTML = '<tr><td colspan="7" class="hint">还没有任何会话档案。</td></tr>';
                return;
            }
            box.innerHTML = rows.map(function (s) {
                return '<tr>'
                    + '<td><code>' + esc(s.sessionId || '') + '</code></td>'
                    + '<td>' + esc(s.domain || '') + '</td>'
                    + '<td>' + num(s.memoryMessages) + '</td>'
                    + '<td>' + num(s.archiveTurns) + '</td>'
                    + '<td>' + num(s.vectorTurns) + '</td>'
                    + '<td>' + num(s.assemblyReports) + '</td>'
                    + '<td class="dp-actions">'
                    + '<button class="btn btn-sm" data-assembly="' + esc(s.sessionKey || '') + '">装配清单</button>'
                    + '<button class="btn btn-sm" data-export="' + esc(s.sessionKey || '') + '">导出副本</button>'
                    + '</td>'
                    + '</tr>';
            }).join('');
            bindRowActions();
        }).catch(function (e) {
            box.innerHTML = '<tr><td colspan="7" class="hint">读取会话体量失败：' + esc(e.message) + '</td></tr>';
        });
    }

    function bindRowActions() {
        var box = document.getElementById('sessionList');
        box.querySelectorAll('[data-assembly]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                loadAssembly(btn.getAttribute('data-assembly'));
            });
        });
        box.querySelectorAll('[data-export]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var key = btn.getAttribute('data-export');
                var parts = String(key).split('|');
                var url = '/admin/sessions/export?session=' + encodeURIComponent(parts[1] || key)
                    + (parts.length > 1 ? '&domain=' + encodeURIComponent(parts[0]) : '');
                window.location.href = url;
            });
        });
    }

    /**
     * 装配清单：每段给了多少 / 砍了多少。
     * lostRecall 为真即"相关内容召回了但被预算挤掉"，这是"它为什么忘了"的第一嫌疑。
     */
    function loadAssembly(sessionKey) {
        var box = document.getElementById('assemblyBox');
        var parts = String(sessionKey).split('|');
        var url = '/admin/sessions/assembly?session=' + encodeURIComponent(parts[1] || sessionKey)
            + (parts.length > 1 ? '&domain=' + encodeURIComponent(parts[0]) : '')
            + '&limit=10';
        box.className = 'card dp-panel';
        box.innerHTML = '<div class="card-body">' + Console.loadingHtml('正在读取装配清单…') + '</div>';
        Console.api.get(url).then(function (data) {
            var reports = data.reports || [];
            if (!reports.length) {
                box.innerHTML = '<div class="card-body"><div class="hint">这个会话还没有装配记录。</div></div>';
                return;
            }
            box.innerHTML = '<div class="card-body"><table class="table"><thead><tr>'
                + '<th>轮</th><th>总预算</th><th>摘要</th><th>卡片</th><th>窗口</th>'
                + '<th>提问</th><th>合计</th><th>丢弃</th>'
                + '</tr></thead><tbody>'
                + reports.map(function (r) {
                    var lost = r.windowDroppedMessages > 0
                        || (r.droppedCardTurnIds && r.droppedCardTurnIds.length > 0);
                    var dropped = [];
                    if (r.summaryDroppedLines > 0) { dropped.push('摘要 ' + r.summaryDroppedLines + ' 行'); }
                    if (r.windowDroppedMessages > 0) { dropped.push('窗口 ' + r.windowDroppedMessages + ' 条'); }
                    if (r.droppedCardTurnIds && r.droppedCardTurnIds.length > 0) {
                        dropped.push('卡片轮 ' + r.droppedCardTurnIds.join('/'));
                    }
                    return '<tr' + (lost ? ' class="dp-row-warn"' : '') + '>'
                        + '<td>' + num(r.turnId) + '<div class="hint">' + esc(when(r.at)) + '</div></td>'
                        + '<td>' + num(r.totalBudget) + '</td>'
                        + '<td>' + num(r.summaryTokens) + '<div class="hint">' + num(r.summaryLines) + ' 行</div></td>'
                        + '<td>' + num(r.cardTokens) + '<div class="hint">' + num(r.cardCount) + ' 张</div></td>'
                        + '<td>' + num(r.windowTokens) + '<div class="hint">' + num(r.windowMessages) + ' 条</div></td>'
                        + '<td>' + num(r.currentTokens) + '</td>'
                        + '<td>' + num(r.totalTokens) + '</td>'
                        + '<td>' + (dropped.length ? esc(dropped.join('、')) : '—') + '</td>'
                        + '</tr>';
                }).join('')
                + '</tbody></table></div>';
        }).catch(function (e) {
            box.innerHTML = '<div class="card-body"><div class="hint">读取装配清单失败：'
                + esc(e.message) + '</div></div>';
        });
    }

    /* 侧栏必须每页自己渲染一次：<aside id="sidebar"> 是空壳，漏了这句整条左侧全空 */
    Console.renderSidebar('sessions');
    Console.ready(function () {
        loadStorage();
    });
})();

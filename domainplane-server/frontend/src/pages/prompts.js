/* ==========================================================================
   P1 · prompts 页脚本
   自 prompts.html 的内联 <script> 机械平移（1 块合并），逻辑未改。
   ========================================================================== */
(function () {
var esc = Console.escapeHtml;
    var COLORS = { primary: '#2563eb', muted: '#6b7280', red: '#dc2626', amber: '#d97706' };

    /* 编辑中的值（保存时整对象提交） vs 服务端基线（用于脏标记与"清空"） */
    var model = { prompts: {} };
    var baseline = { prompts: {} };
    var domains = [];
    var currentDomain = null;

    function snapshot(o) { return JSON.stringify({ prompts: o.prompts || {} }); }

    function isDirty() { return snapshot(model) !== snapshot(baseline); }

    function setDirty() {
        document.getElementById('dirtyFlag').style.display = isDirty() ? 'inline' : 'none';
    }

    /* 完整路径 → 从根到自身的链，与服务端 Domains.chainOf 同构 */
    function chainOf(domain) {
        var segs = String(domain || '').split('.');
        var out = [];
        var cur = '';
        for (var i = 0; i < segs.length; i++) {
            cur = cur ? cur + '.' + segs[i] : segs[i];
            out.push(cur);
        }
        return out;
    }

    function counts() {
        document.getElementById('domainLen').textContent =
            currentDomain ? (model.prompts[currentDomain] || '').trim().length : 0;
    }

    /* 沿链拼接：根域在前、当前域在后，未配置的段跳过 */
    function compose(domain) {
        var parts = [];
        chainOf(domain).forEach(function (step) {
            var text = (model.prompts[step] || '').trim();
            if (text) { parts.push(text); }
        });
        return parts.join('\n\n');
    }

    function renderPreview() {
        if (!currentDomain) { return; }
        var text = compose(currentDomain);
        var from = chainOf(currentDomain).filter(function (step) {
            return (model.prompts[step] || '').trim();
        });
        document.getElementById('edChain').textContent = from.length
            ? '拼接自：' + from.join(' → ')
            : '';
        document.getElementById('preview').innerHTML =
            esc(text) || '（该域及其祖先链都未配置，本轮不会下发任何系统提示词）';
    }

    function openEditor(domain) {
        currentDomain = domain;
        document.getElementById('editorCard').style.display = 'block';
        document.getElementById('edDomain').textContent = '域：' + domain;
        var d = domains.filter(function (x) { return x.name === domain; })[0];
        document.getElementById('edTools').textContent = d
            ? ('可见工具 ' + d.tools.length + ' 个，来自 ' + d.providerCount + ' 个提供者')
            : '';
        document.getElementById('domainText').value = model.prompts[domain] || '';
        counts();
        renderPreview();
        document.getElementById('editorCard').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }

    function renderDomains() {
        var tbody = document.getElementById('domainBody');
        if (!domains.length) {
            tbody.innerHTML = '<tr><td colspan="6" class="perm-none">'
                + '当前没有任何域。根域 default（正常装配时必定存在）、'
                + '管控台人工创建、工具声明派生；若连 default 都没有，说明服务端未正常装配域注册表。</td></tr>';
            return;
        }
        tbody.innerHTML = domains.map(function (d) {
            var val = (model.prompts[d.name] || '').trim();
            var status = val
                ? '<span class="tag read">已配置</span>'
                : '<span class="tag write">缺提示词</span>';
            var toolNames = d.tools.map(function (t) { return t.name; });
            var chips = toolNames.slice(0, 6).map(function (n) {
                return '<span class="tag perm">' + esc(n) + '</span>';
            }).join('') + (toolNames.length > 6
                ? '<span class="card-note">等 ' + toolNames.length + ' 个</span>' : '');
            return '<tr class="domain-row" data-dom="' + esc(d.name) + '">'
                + '<td><b>' + esc(d.name) + '</b></td>'
                + '<td style="min-width:220px;">' + (chips || '<span class="perm-none">无</span>') + '</td>'
                + '<td>' + d.providerCount + '</td>'
                + '<td>' + val.length + ' 字</td>'
                + '<td>' + status + '</td>'
                + '<td><a href="javascript:void(0)" class="ghost" style="padding:4px 10px;">编辑</a></td>'
                + '</tr>';
        }).join('');

        Array.prototype.forEach.call(tbody.querySelectorAll('.domain-row'), function (row) {
            row.addEventListener('click', function () { openEditor(row.getAttribute('data-dom')); });
        });
    }

    function renderAll() {
        counts();
        renderDomains();
        renderPreview();
        setDirty();
    }

    function load() {
        /* P2：/admin/prompts 是 D 类裸对象，api.get 直接返回 data */
        Console.api.get('/admin/prompts').then(function (r) {
            baseline = { prompts: Object.assign({}, r.prompts || {}) };
            model = { prompts: Object.assign({}, baseline.prompts) };
            domains = r.domains || [];

            var orphans = r.orphanPrompts || [];
            var box = document.getElementById('orphanBox');
            if (orphans.length) {
                box.style.display = 'block';
                box.innerHTML = '<b>孤儿提示词条目：</b>' + orphans.map(esc).join('、')
                    + '<br>这些域从未被任何工具声明过，配置留着不生效、也不自动清理；若确认不再需要，可在下方编辑区之外手工删除配置项。';
            } else {
                box.style.display = 'none';
            }

            if (currentDomain && !domains.some(function (d) { return d.name === currentDomain; })) {
                currentDomain = null;
                document.getElementById('editorCard').style.display = 'none';
            }
            renderAll();
        }).catch(function (e) {
            document.getElementById('domainBody').innerHTML =
                '<tr><td colspan="6" style="color:' + COLORS.red + ';">加载失败: ' + esc(e.message) + '</td></tr>';
        });
    }

    document.getElementById('domainText').addEventListener('input', function (e) {
        if (!currentDomain) { return; }
        model.prompts[currentDomain] = e.target.value;
        counts();
        renderPreview();
        renderDomains();
        setDirty();
    });

    document.getElementById('clearBtn').addEventListener('click', function () {
        /* 清空 = 把编辑区归零，之后点「保存并生效」才会真正回落 yaml；不是"撤销" */
        model = { prompts: {} };
        document.getElementById('domainText').value = '';
        renderAll();
    });

    document.getElementById('refreshBtn').addEventListener('click', function () {
        if (isDirty() && !confirm('有未保存的修改，重新读取会丢弃它们。继续？')) { return; }
        load();
    });

    document.getElementById('saveBtn').addEventListener('click', function () {
        var el = document.getElementById('saveResult');
        el.textContent = '正在保存…';
        el.className = 'result show ok';

        var payload = { prompts: model.prompts };
        /* P2：/admin/prompts POST 是 C 类（只有 success）。
           原来的 `if (!r.success) {...} else {...}` 两分支合并成一条成功路径：
           失败由 api.post 的 reject 送进 catch，成功文案照旧取 r.message。 */
        Console.api.post('/admin/prompts', payload).then(function (r) {
            el.textContent = r.message || '已保存并生效';
            el.className = 'result show ok';
            load();
        }).catch(function (e) {
            el.textContent = e.message;
            el.className = 'result show err';
        });
    });

    Console.renderSidebar('prompts');
    Console.ready(load);
})();

/* ==========================================================================
   prompts 页脚本 · 与 prompts.html 一一对应
   左侧域列表（domainList）+ 右侧编辑器 + 统计行 + 孤儿条目提示。
   旧版是 prompts.html 上一代 DOM（整页表格 domainBody / editorCard）的遗留，
   引用的节点已不存在（整页报废），本次按现 HTML 重写。
   ========================================================================== */
(function () {
    var esc = Console.escapeHtml;

    function el(id) { return document.getElementById(id); }
    /* 提示性节点不保证存在：一律容错写入，缺节点时静默跳过 */
    function setText(id, v) { var n = el(id); if (n) { n.textContent = v; } }
    function setHtml(id, v) { var n = el(id); if (n) { n.innerHTML = v; } }
    function show(id, on) { var n = el(id); if (n) { n.style.display = on ? '' : 'none'; } }

    /* 编辑中的值（保存时整对象提交） vs 服务端基线（用于保存按钮的可用判定） */
    var model = { prompts: {} };
    var baseline = { prompts: {}, orphans: [] };
    var domains = [];
    var currentDomain = null;
    var LOADED = false;          /* 首次加载是否已完成（决定要不要放占位） */

    function setValue(id, v, tone) {
        var n = el(id);
        if (n) {
            n.textContent = v;
            n.className = 'dp-stat-value' + (tone ? ' ' + tone : '');
        }
    }

    function currentText() {
        return currentDomain ? (model.prompts[currentDomain] || '') : '';
    }

    function baselineText() {
        return currentDomain ? (baseline.prompts[currentDomain] || '') : '';
    }

    /* 保存按钮只在检测到当前域文本相对基线有改动时可点 */
    function updateSaveBtn() {
        var btn = el('saveBtn');
        if (btn) {
            btn.disabled = currentDomain === null || currentText() === baselineText();
        }
        show('dirtyFlag', currentDomain !== null && currentText() !== baselineText());
    }

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
        setText('domainLen', String(currentText().trim().length));
    }

    /* 生效提示词 = 沿域链从根域拼接 */
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
        setText('edChain', from.length ? '拼接自：' + from.join(' → ') : '');
        setHtml('preview', esc(text)
            || '（该域及其祖先链都未配置，本轮不会下发任何系统提示词）');
    }

    function openEditor(domain) {
        currentDomain = domain;
        show('editorEmpty', false);
        show('editorForm', true);
        setText('edDomain', domain);
        var d = domains.filter(function (x) { return x.name === domain; })[0];
        /* 工具数放卡片标题副行，不再写进 edChain —— 那行随后会被 renderPreview 覆盖 */
        setText('edTools', d ? '可见工具 ' + (d.tools || []).length + ' 个' : '');
        var ta = el('domainText');
        if (ta) { ta.value = model.prompts[domain] || ''; }
        counts();
        renderPreview();
        updateSaveBtn();
        renderDomainList();
    }

    function renderDomainList() {
        var box = el('domainList');
        if (!box) { return; }
        if (!domains.length) {
            box.innerHTML = '<div class="dp-domain-empty">当前没有任何域。若连根域 default 都没有，'
                + '说明服务端未正常装配域注册表。</div>';
            return;
        }
        box.innerHTML = '<div class="dp-domain-list">' + domains.map(function (d) {
            var val = (model.prompts[d.name] || '').trim();
            var tag = val
                ? '<span class="dp-domain-tag ok">已配置</span>'
                : '<span class="dp-domain-tag miss">缺提示词</span>';
            return '<div class="dp-domain-item' + (d.name === currentDomain ? ' on' : '') + '" data-dom="' + esc(d.name) + '">'
                + '<span class="dp-domain-name" title="' + esc(d.name) + '">' + esc(d.name) + '</span>'
                + tag + '</div>';
        }).join('') + '</div>';
        box.querySelectorAll('.dp-domain-item').forEach(function (item) {
            item.addEventListener('click', function () {
                openEditor(item.getAttribute('data-dom'));
            });
        });
    }

    function renderStats() {
        var total = domains.length;
        var missing = domains.filter(function (d) {
            return !(model.prompts[d.name] || '').trim();
        }).length;
        setValue('statDomains', String(total));
        setValue('statConfigured', String(total - missing));
        setValue('statMissing', String(missing), missing ? 'warn' : '');
        setValue('statOrphans', String(baseline.orphans.length), baseline.orphans.length ? 'warn' : '');
    }

    var ORPHAN_ICON = '<span class="ob-icon"><svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" '
        + 'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" '
        + 'stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>'
        + '<path d="M12 9v4M12 17h.01"/></svg></span>';

    function renderOrphans() {
        var box = el('orphanBox');
        if (!box) { return; }
        var orphans = baseline.orphans;
        if (!orphans.length) { box.style.display = 'none'; return; }
        box.style.display = 'flex';
        box.innerHTML = ORPHAN_ICON + '<div><b>孤儿提示词条目：</b>'
            + '<span class="ob-list">' + orphans.map(esc).join('、') + '</span>'
            + '<div>这些域从未被任何工具声明过，配置留着不生效、也不自动清理；'
            + '若确认不再需要，可在编辑区之外手工删除配置项。</div></div>';
    }

    function renderAll() {
        counts();
        renderDomainList();
        renderPreview();
        renderStats();
        updateSaveBtn();
    }

    function load() {
        /* 首次加载先给占位：左列表初始是空白，转圈比白屏直观。
           之后的 load（保存后的重拉）列表已有内容，交给顶部进度条。 */
        if (!LOADED) {
            setHtml('domainList', Console.loadingHtml('正在加载提示词配置…'));
        }
        return Console.api.get('/admin/prompts').then(function (r) {
            LOADED = true;
            baseline = { prompts: Object.assign({}, r.prompts || {}), orphans: r.orphanPrompts || [] };
            model = { prompts: Object.assign({}, baseline.prompts) };
            domains = r.domains || [];
            renderOrphans();
            if (currentDomain && !domains.some(function (d) { return d.name === currentDomain; })) {
                currentDomain = null;
                show('editorEmpty', true);
                show('editorForm', false);
                setText('edDomain', '—');
                setText('edTools', '');
            }
            renderAll();
        }).catch(function (e) {
            setHtml('domainList', '<div class="dp-domain-empty" style="color:var(--red);">加载失败: ' + esc(e.message) + '</div>');
        });
    }

    el('domainText').addEventListener('input', function (e) {
        if (!currentDomain) { return; }
        model.prompts[currentDomain] = e.target.value;
        counts();
        renderPreview();
        renderDomainList();
        updateSaveBtn();
    });

    el('refreshBtn').addEventListener('click', function () {
        if (currentDomain && currentText() !== baselineText()) {
            return Console.confirm(
                '有未保存的修改，重新读取会丢弃它们。继续？',
                { title: '放弃未保存的修改', okText: '继续读取', danger: true }
            ).then(function (ok) { if (ok) { load(); } });
        }
        load();
    });

    el('saveBtn').addEventListener('click', function () {
        var btn = this;
        Console.busy(btn, function () {
            var out = el('saveResult');
            if (out) { out.textContent = '正在保存…'; out.className = 'result show ok'; }
            return Console.api.post('/admin/prompts', { prompts: model.prompts }).then(function (r) {
                if (out) { out.textContent = r.message || '已保存并生效'; out.className = 'result show ok'; }
                load();
            }).catch(function (e) {
                if (out) { out.textContent = e.message; out.className = 'result show err'; }
            });
        }, '保存中…');
    });

    Console.renderSidebar('prompts');
    Console.ready(load);
})();

/* ==========================================================================
   prompts 页脚本 · 与 prompts.html 一一对应
   左侧域列表（domainList）+ 右侧编辑器 + 统计行 + 孤儿条目提示。
   旧版是 prompts.html 上一代 DOM（整页表格 domainBody / editorCard）的遗留，
   引用的节点已不存在（整页报废），本次按现 HTML 重写。
   ========================================================================== */
(function () {
    var esc = Console.escapeHtml;

    /* 编辑中的值（保存时整对象提交） vs 服务端基线（用于保存按钮的可用判定） */
    var model = { prompts: {} };
    var baseline = { prompts: {}, orphans: [] };
    var domains = [];
    var currentDomain = null;
    var LOADED = false;          /* 首次加载是否已完成（决定要不要放占位） */

    function setValue(id, v, tone) {
        var el = document.getElementById(id);
        if (el) {
            el.textContent = v;
            el.className = 'dp-stat-value' + (tone ? ' ' + tone : '');
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
        var btn = document.getElementById('saveBtn');
        if (btn) {
            btn.disabled = currentDomain === null || currentText() === baselineText();
        }
        var dirty = document.getElementById('dirtyFlag');
        if (dirty) {
            dirty.style.display = (currentDomain !== null && currentText() !== baselineText()) ? 'inline' : 'none';
        }
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
        document.getElementById('domainLen').textContent = currentText().trim().length;
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
        document.getElementById('edChain').textContent = from.length ? '拼接自：' + from.join(' → ') : '';
        document.getElementById('preview').innerHTML = esc(text)
            || '（该域及其祖先链都未配置，本轮不会下发任何系统提示词）';
    }

    function openEditor(domain) {
        currentDomain = domain;
        document.getElementById('editorEmpty').style.display = 'none';
        document.getElementById('editorForm').style.display = 'block';
        document.getElementById('edDomain').textContent = domain;
        var d = domains.filter(function (x) { return x.name === domain; })[0];
        document.getElementById('edChain').textContent = d ? '可见工具 ' + (d.tools || []).length + ' 个' : '';
        document.getElementById('domainText').value = model.prompts[domain] || '';
        counts();
        renderPreview();
        updateSaveBtn();
        renderDomainList();
    }

    function renderDomainList() {
        var box = document.getElementById('domainList');
        if (!domains.length) {
            box.innerHTML = '<div class="dp-domain-empty">当前没有任何域。若连根域 default 都没有，'
                + '说明服务端未正常装配域注册表。</div>';
            return;
        }
        box.innerHTML = domains.map(function (d) {
            var val = (model.prompts[d.name] || '').trim();
            var tag = val
                ? '<span class="dp-domain-tag ok">已配置</span>'
                : '<span class="dp-domain-tag miss">缺提示词</span>';
            return '<div class="dp-domain-item' + (d.name === currentDomain ? ' on' : '') + '" data-dom="' + esc(d.name) + '">'
                + '<span class="dp-domain-name" title="' + esc(d.name) + '">' + esc(d.name) + '</span>'
                + tag + '</div>';
        }).join('');
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
            var box0 = document.getElementById('domainList');
            if (box0) { box0.innerHTML = Console.loadingHtml('正在加载提示词配置…'); }
        }
        Console.api.get('/admin/prompts').then(function (r) {
            LOADED = true;
            baseline = { prompts: Object.assign({}, r.prompts || {}), orphans: r.orphanPrompts || [] };
            model = { prompts: Object.assign({}, baseline.prompts) };
            domains = r.domains || [];
            var orphans = baseline.orphans;
            var box = document.getElementById('orphanBox');
            if (orphans.length) {
                box.style.display = 'block';
                box.innerHTML = '<b>孤儿提示词条目：</b>' + orphans.map(esc).join('、')
                    + '<br>这些域从未被任何工具声明过，配置留着不生效、也不自动清理；'
                    + '若确认不再需要，可在下方编辑区之外手工删除配置项。';
            } else {
                box.style.display = 'none';
            }
            if (currentDomain && !domains.some(function (d) { return d.name === currentDomain; })) {
                currentDomain = null;
                document.getElementById('editorEmpty').style.display = 'block';
                document.getElementById('editorForm').style.display = 'none';
            }
            renderAll();
        }).catch(function (e) {
            var box = document.getElementById('domainList');
            if (box) {
                box.innerHTML = '<div class="dp-domain-empty" style="color:var(--red);">加载失败: ' + esc(e.message) + '</div>';
            }
        });
    }

    document.getElementById('domainText').addEventListener('input', function (e) {
        if (!currentDomain) { return; }
        model.prompts[currentDomain] = e.target.value;
        counts();
        renderPreview();
        renderDomainList();
        updateSaveBtn();
    });

    document.getElementById('refreshBtn').addEventListener('click', async function () {
        if (currentDomain && currentText() !== baselineText()) {
            var ok = await Console.confirm(
                '有未保存的修改，重新读取会丢弃它们。继续？',
                { title: '放弃未保存的修改', okText: '继续读取', danger: true }
            );
            if (!ok) { return; }
        }
        load();
    });

    document.getElementById('saveBtn').addEventListener('click', function () {
        var btn = this;
        Console.busy(btn, function () {
            var el = document.getElementById('saveResult');
            el.textContent = '正在保存…';
            el.className = 'result show ok';
            return Console.api.post('/admin/prompts', { prompts: model.prompts }).then(function (r) {
                el.textContent = r.message || '已保存并生效';
                el.className = 'result show ok';
                load();
            }).catch(function (e) {
                el.textContent = e.message;
                el.className = 'result show err';
            });
        }, '保存中…');
    });

    Console.renderSidebar('prompts');
    Console.ready(load);
})();

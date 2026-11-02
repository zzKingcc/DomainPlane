/* ==========================================================================
   知识库页
   ---------------------------------------------------------------------------
   页面的一等对象是「知识体」：域只是它的使用者，索引名只由域派生、可以被写在多台上。
   所以本页所有判断都围绕三件事：哪个体装了什么、这批域挂在哪台机器、我要动的到底是哪一份。

   数据来源只有两个只读接口：
   - /admin/kb/overview  汇总 + 每个体的两槽落点 / 绑定域 / 文档数 + 孤儿索引（与域空间页
                          共用同一份 60s 统计快照，页面不各自现扫存储）
   - /admin/kb/documents 文档清单（落点与知识体列由 overview 的绑定关系推算）
   上传前再问 /admin/kb/target 拿落点预检（纯读配置，不碰存储）。
   ========================================================================== */
(function () {
    var esc = Console.escapeHtml;

    /* 概览快照与文档清单各自到达，两处渲染都读这些状态，谁后到谁触发重绘 */
    var state = {
        overview: null,
        docs: [],
        target: null,
        bodyOfDomain: {},   /* 域 → 知识体 id（前端只为文档表补两列，判定以后端为准） */
        limits: null
    };

    function fmt(n) {
        if (n === undefined || n === null) { return '—'; }
        return Number(n).toLocaleString('zh-CN');
    }

    function setValue(id, v, tone) {
        var el = document.getElementById(id);
        if (el) { el.textContent = v; el.className = 'dp-stat-value' + (tone ? ' ' + tone : ''); }
    }

    function setNote(id, v) {
        var el = document.getElementById(id);
        if (el) { el.textContent = v || ''; }
    }

    function domainLabel(domain) {
        var d = domain || 'default';
        return '<span class="pill">' + esc(d) + (d === 'default' ? '（全域）' : '') + '</span>';
    }

    /* ==================== 落点 / 状态 / 召回模式 ==================== */

    /* 槽位六态必须分开表达：未配 / 连接已删 / 连接未填地址 / 连接未注册 / 能力不匹配 / 可用。
       合并成"不可用"会让用户无从知道该去存储配置补什么。 */
    function slotChip(slot) {
        if (!slot || slot.state === 'absent') {
            return '<span class="dp-chip mute">未配</span>';
        }
        var name = esc(slot.name || slot.connectionId || '');
        if (slot.state === 'ok') {
            return '<span class="dp-chip mono ok" title="' + esc((slot.type || '') + ' · ' + slot.connectionId) + '">' + name + '</span>';
        }
        var why = slot.state === 'missing' ? '连接已删除'
            : slot.state === 'blank' ? '连接未填地址'
            : slot.state === 'incapable' ? '该连接类型提供不了这一路（如关键字槽指到向量库）'
            : '连接未注册（存储配置保存后生效）';
        return '<span class="dp-chip mono warn" title="' + esc(why) + '">' + name + '</span>';
    }

    function modesText(modes) {
        if (!modes || !modes.length) { return '空壳'; }
        if (modes.length > 1) { return '混合'; }
        return modes[0] === 'keyword' ? '仅关键字' : '仅向量';
    }

    /* 生效模式 = 体两槽 × 全局策略。策略关掉的那一路照样显示出来（划掉），
       否则"配了两路、页面显示混合、实际只走一路"这个坑会重演。 */
    function modeCell(row) {
        var modes = row.modes || [];
        var effective = row.effectiveModes || [];
        if (!modes.length) {
            return '<span class="dp-chip mute">空壳</span>';
        }
        var text = effective.length ? modesText(effective) : '无生效路';
        var html = '<span class="dp-chip' + (effective.length ? '' : ' warn') + '">' + esc(text) + '</span>';
        if (effective.length < modes.length) {
            var closed = modes.filter(function (m) { return effective.indexOf(m) < 0; })
                .map(function (m) { return m === 'keyword' ? '关键字' : '向量'; }).join('/');
            html += '<span class="dp-chip warn" title="' + esc('全局策略关闭了 ' + closed + ' 路') + '">关' + closed + '</span>';
        }
        return html;
    }

    var STATUS_TEXT = {
        ok: ['正常', 'ok'],
        empty: ['空壳', 'warn'],
        dead: ['连接不可用', 'err'],
        missing: ['体已删除', 'err'],
        no_index: ['有绑定无索引', 'warn']
    };

    function statusCell(status) {
        var pair = STATUS_TEXT[status] || [status || '—', ''];
        return '<span class="dp-chip ' + pair[1] + '">' + esc(pair[0]) + '</span>';
    }

    /* ==================== 知识体视图 ==================== */

    function bodyEmptyRow(text) {
        return '<tr class="dp-doc-empty"><td colspan="8"><div>' + text + '</div></td></tr>';
    }

    function renderBodies() {
        var ov = state.overview;
        var tbody = document.getElementById('bodyBody');
        if (!tbody) { return; }
        if (!ov) { return; }

        var bodies = ov.bodies || [];
        var inUse = (ov.platform || {}).bodiesInUse || 0;
        var badge = document.getElementById('bodyCount');
        if (badge) {
            badge.textContent = bodies.length + ' 个';
            badge.className = 'mbadge' + (inUse > 0 ? ' ok' : '');
        }

        if (!bodies.length) {
            tbody.innerHTML = bodyEmptyRow('还没有知识体，先去<a class="dp-target-link" href="infra.html">存储配置</a>建一个');
            return;
        }

        tbody.innerHTML = bodies.map(function (b) {
            var slots = b.slots || [];
            var keyword = slots[0];
            var vector = slots[1];
            var domainList = (b.domains || []).map(function (d) {
                return d.domain + ' → ' + d.index + (d.exists ? '' : '（无索引）');
            }).join('\n');
            var domainsCell = b.domainCount
                ? '<span class="dp-chip mono" title="' + esc(domainList) + '">' + b.domainCount + ' 个</span>'
                : '<span class="dp-chip mute">未绑定</span>';
            return '<tr>'
                + '<td><b>' + esc(b.name || b.id) + '</b>'
                + (b.name && b.name !== b.id ? '<span class="dp-body-domain">' + esc(b.id) + '</span>' : '')
                + '</td>'
                + '<td>' + slotChip(keyword) + '</td>'
                + '<td>' + slotChip(vector) + '</td>'
                + '<td>' + modeCell(b) + '</td>'
                + '<td>' + domainsCell + '</td>'
                + '<td>' + fmt(b.documents) + ' / ' + fmt(b.chunks) + '</td>'
                + '<td>' + statusCell(b.status) + '</td>'
                + '<td class="ta-r"><button class="btn btn-sm btn-outline-secondary" data-body="'
                + esc(b.id) + '">看文档</button></td>'
                + '</tr>';
        }).join('');

        tbody.querySelectorAll('button[data-body]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var select = document.getElementById('filterBody');
                if (select) { select.value = btn.getAttribute('data-body'); }
                renderDocs();
            });
        });
    }

    function renderStats() {
        var ov = state.overview;
        if (!ov) { return; }
        var p = ov.platform || {};
        var stale = ov.stale ? '（旧数据）' : '';
        setValue('statDocs', fmt(p.documents));
        setValue('statChunks', fmt(p.chunks));
        setValue('statBodies', fmt(p.bodies) + ' 个', p.bodiesInUse ? '' : 'warn');
        setNote('statBodiesNote', (p.bodiesInUse || 0) + ' 个在用' + stale);
        /* 总览只剩「归属」口径：体视图各行之和会小于总数（解绑/悬空域的文档没有体可归），
           这是口径事实，不是算错——所以这里不做任何"校准"。 */
    }

    function loadOverview() {
        return Console.api.get('/admin/kb/overview').then(function (ov) {
            state.overview = ov;
            state.limits = ov.limits || null;
            state.bodyOfDomain = {};
            (ov.bodies || []).forEach(function (b) {
                (b.domains || []).forEach(function (d) {
                    state.bodyOfDomain[d.domain] = b.id;
                });
            });
            renderStats();
            renderBodies();
            fillBodyFilter();
            fillBackendFilter();
            fillDomainFilter();
            renderDocs();
        }).catch(function (e) {
            setValue('statDocs', '—', 'err');
            setValue('statChunks', '—', 'err');
            setValue('statBodies', '—', 'err');
            setNote('statBodiesNote', '读取失败：' + e.message);
            var tbody = document.getElementById('bodyBody');
            if (tbody) { tbody.innerHTML = bodyEmptyRow('读取知识体失败：' + esc(e.message)); }
        });
    }

    /* ==================== 文档表 ==================== */

    var DOC_EMPTY_ROW = '<tr class="dp-doc-empty"><td colspan="8"><div>'
        + '<div class="dp-doc-empty-icon"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M9 13h6M9 17h6"/></svg></div>'
        + '暂无文档，在上方上传后展示在这里</div></td></tr>';

    function bodyOfBodyId(bodyId) {
        var ov = state.overview;
        if (!ov) { return null; }
        var found = null;
        (ov.bodies || []).forEach(function (b) { if (b.id === bodyId) { found = b; } });
        return found;
    }

    /* 落点是<b>推算口径</b>：按归属域当前绑定体的两槽显示，不逐台查物理位置
       （换过体的域，老文档实际还在旧机器上，那是孤儿清理的事，文档表不冒充物理事实）。 */
    function backendCell(domain) {
        var bodyId = state.bodyOfDomain[domain];
        if (!bodyId) { return '<span class="dp-chip mute" title="该域未绑定知识体">—</span>'; }
        var row = bodyOfBodyId(bodyId);
        if (!row) { return '<span class="dp-chip mute">—</span>'; }
        var ids = [];
        (row.slots || []).forEach(function (s) {
            if (s.state === 'ok' && ids.indexOf(s.connectionId) < 0) { ids.push(s.connectionId); }
        });
        if (!ids.length) { return '<span class="dp-chip mute">—</span>'; }
        return ids.map(function (id) {
            return '<span class="dp-chip mono" title="落点（当前绑定）">' + esc(id) + '</span>';
        }).join('');
    }

    function bodyNameCell(domain) {
        var bodyId = state.bodyOfDomain[domain];
        if (!bodyId) { return '<span class="dp-chip mute">未绑定</span>'; }
        var row = bodyOfBodyId(bodyId);
        return '<span class="dp-chip">' + esc(row ? (row.name || row.id) : bodyId) + '</span>';
    }

    function currentFilters() {
        var pick = function (id) {
            var el = document.getElementById(id);
            return el ? el.value : '';
        };
        return { domain: pick('filterDomain'), body: pick('filterBody'), backend: pick('filterBackend') };
    }

    function matches(doc, f) {
        if (f.domain && (doc.domain || 'default') !== f.domain) { return false; }
        if (f.body) {
            var bodyId = state.bodyOfDomain[doc.domain || 'default'];
            if (bodyId !== f.body) { return false; }
        }
        if (f.backend) {
            var bodyId2 = state.bodyOfDomain[doc.domain || 'default'];
            var row = bodyId2 ? bodyOfBodyId(bodyId2) : null;
            var hit = row && (row.slots || []).some(function (s) {
                return s.state === 'ok' && s.connectionId === f.backend;
            });
            if (!hit) { return false; }
        }
        return true;
    }

    function setDocCount(n) {
        var el = document.getElementById('docCount');
        if (el) {
            el.textContent = n + ' 篇';
            el.className = 'mbadge' + (n > 0 ? ' ok' : '');
        }
    }

    function renderDocs() {
        var body = document.getElementById('docBody');
        if (!body) { return; }
        var f = currentFilters();
        var docs = state.docs.filter(function (d) { return matches(d, f); });
        setDocCount(docs.length);
        if (!docs.length) {
            body.innerHTML = state.docs.length
                ? '<tr class="dp-doc-empty"><td colspan="8"><div>当前筛选没有匹配的文档</div></td></tr>'
                : DOC_EMPTY_ROW;
            return;
        }
        body.innerHTML = docs.map(function (d) {
            return '<tr>'
                + '<td><code>' + esc(d.docId || '') + '</code></td>'
                + '<td>' + esc(d.fileName || '') + '</td>'
                + '<td>' + domainLabel(d.domain) + '</td>'
                + '<td>' + bodyNameCell(d.domain || 'default') + '</td>'
                + '<td>' + backendCell(d.domain || 'default') + '</td>'
                + '<td>' + (d.chunks || 0) + '</td>'
                + '<td>' + (d.exportPath
                    ? '<code class="path" title="点击复制">' + esc(d.exportPath) + '</code>'
                    : '<span class="hint">—</span>') + '</td>'
                + '<td class="ta-r"><button class="btn btn-sm btn-outline-danger" data-doc="'
                + esc(d.docId || '') + '">删除</button></td>'
                + '</tr>';
        }).join('');
        body.querySelectorAll('button[data-doc]').forEach(function (btn) {
            btn.addEventListener('click', function () { removeDoc(btn.getAttribute('data-doc')); });
        });
        body.querySelectorAll('code.path').forEach(function (code) {
            code.addEventListener('click', function () {
                if (navigator.clipboard) { navigator.clipboard.writeText(code.textContent); }
            });
        });
    }

    function loadDocuments() {
        var body = document.getElementById('docBody');
        if (body && !state.docs.length) {
            body.innerHTML = '<tr class="dp-doc-empty"><td colspan="8"><div>加载中…</div></td></tr>';
        }
        return Console.api.get('/admin/kb/documents').then(function (data) {
            state.docs = data.documents || [];
            renderDocs();
            applyFileChecks();
        }).catch(function (e) {
            setDocCount(0);
            if (body) {
                body.innerHTML = '<tr class="dp-doc-empty"><td colspan="8"><div>读取文档列表失败：'
                    + esc(e.message) + '</div></td></tr>';
            }
        });
    }

    function removeDoc(docId) {
        var bodyId = null;
        var backends = [];
        state.docs.forEach(function (d) {
            if (d.docId !== docId) { return; }
            bodyId = state.bodyOfDomain[d.domain || 'default'] || null;
            var row = bodyId ? bodyOfBodyId(bodyId) : null;
            (row && row.slots ? row.slots : []).forEach(function (s) {
                if (s.state === 'ok' && backends.indexOf(s.connectionId) < 0) { backends.push(s.connectionId); }
            });
        });
        var scope = backends.length
            ? '\n该 docId 的切片会在所有后端上一次删净（当前落点：' + backends.join('、') + '）。'
            : '';
        Console.confirm('确认删除该文档？删除后其文件名会被释放，可重新上传同名文档。' + scope,
            { title: '删除文档', okText: '删除', danger: true }).then(function (ok) {
            if (!ok) { return; }
            Console.api.del('/admin/kb/documents/' + encodeURIComponent(docId)).then(function () {
                loadDocuments();
                loadOverview();
            }).catch(function (e) {
                Console.toast('删除失败：' + e.message, { type: 'error' });
            });
        });
    }

    /* ==================== 筛选 ==================== */

    function resetSelect(select, placeholder) {
        if (!select) { return; }
        select.innerHTML = '<option value="">' + placeholder + '</option>';
    }

    function addOption(select, value, text) {
        if (!select) { return; }
        var opt = document.createElement('option');
        opt.value = value;
        opt.textContent = text;
        select.appendChild(opt);
    }

    /* 重建下拉会丢用户正在用的筛选值（上传/删除成功都会刷新 overview），
       所以每次重建前记住当前值、重建后恢复。 */
    function refillSelect(select, placeholder, fill) {
        if (!select) { return; }
        var keep = select.value;
        resetSelect(select, placeholder);
        fill(select);
        if (keep) {
            for (var i = 0; i < select.options.length; i++) {
                if (select.options[i].value === keep) {
                    select.value = keep;
                    break;
                }
            }
        }
    }

    function fillBodyFilter() {
        refillSelect(document.getElementById('filterBody'), '全部', function (select) {
            (state.overview && state.overview.bodies || []).forEach(function (b) {
                addOption(select, b.id, b.name || b.id);
            });
        });
    }

    function fillBackendFilter() {
        refillSelect(document.getElementById('filterBackend'), '全部', function (select) {
            var ov = state.overview;
            if (!ov) { return; }
            var ids = ((ov.platform || {}).backends || []).slice();
            (ov.bodies || []).forEach(function (b) {
                (b.slots || []).forEach(function (s) {
                    if (s.state === 'ok' && ids.indexOf(s.connectionId) < 0) { ids.push(s.connectionId); }
                });
            });
            ids.forEach(function (id) { addOption(select, id, id); });
        });
    }

    function fillDomainFilter() {
        refillSelect(document.getElementById('filterDomain'), '全部', function (select) {
            addOption(select, 'default', 'default');
        });
        Console.api.get('/admin/domains').then(function (data) {
            var select = document.getElementById('filterDomain');
            var keep = select ? select.value : '';
            (data.domains || []).forEach(function (d) {
                var name = typeof d === 'string' ? d : d.name;
                if (!name || name === 'default') { return; }
                addOption(select, name, name);
            });
            if (keep) {
                for (var i = 0; i < select.options.length; i++) {
                    if (select.options[i].value === keep) {
                        select.value = keep;
                        break;
                    }
                }
            }
        }).catch(function () { });
    }

    /* 域清单：上传下拉排除根域（留空即根域）；筛选下拉由 fillDomainFilter 单独铺 */
    function loadDomainOptions() {
        var select = document.getElementById('domainSelect');
        Console.api.get('/admin/domains').then(function (data) {
            (data.domains || []).forEach(function (d) {
                var name = typeof d === 'string' ? d : d.name;
                if (!name || name === 'default') { return; }
                addOption(select, name, name);
            });
        }).catch(function () { });
    }

    /* ==================== 上传落点预检 ==================== */

    function targetChips(t) {
        var chips = (t.slots || []).map(function (s) {
            if (s.state === 'absent') { return ''; }
            var label = (s.kind === 'keyword' ? '关键字 ' : '向量 ') + (s.name || s.connectionId);
            var tone = s.state === 'ok' ? 'ok' : 'warn';
            return '<span class="dp-chip mono ' + tone + '">' + esc(label) + '</span>';
        }).join('');
        return chips || '<span class="dp-chip mute">无落点</span>';
    }

    function renderTarget() {
        var box = document.getElementById('uploadTarget');
        if (!box) { return; }
        var t = state.target;
        if (!t) { box.innerHTML = ''; return; }
        if (!t.domainExists) {
            box.innerHTML = '<span class="dp-chip err">' + esc(t.reasonText || '域不存在') + '</span>'
                + '<a class="dp-target-link" href="domains.html">去域空间</a>';
            return;
        }
        if (!t.writable) {
            var jump = (t.reason === 'BODY_EMPTY' || t.reason === 'BODY_UNUSABLE') ? 'infra.html' : 'domains.html';
            var jumpText = jump === 'infra.html' ? '去存储配置' : '去域空间';
            box.innerHTML = '<span class="dp-chip err">' + esc(t.reasonText || '不可写入') + '</span>'
                + '<a class="dp-target-link" href="' + jump + '">' + jumpText + '</a>';
            return;
        }
        var html = targetChips(t);
        if (t.fanOut) {
            html += '<span class="dp-chip warn" title="关键字与向量在两台机器上，同一份切片要写两次">写两份</span>';
        }
        box.innerHTML = html;
    }

    function loadTarget() {
        var domain = document.getElementById('domainSelect').value || '';
        return Console.api.get('/admin/kb/target?domain=' + encodeURIComponent(domain))
            .then(function (t) {
                state.target = t;
                if (t.limits) { state.limits = t.limits; }
                renderTarget();
                applyFileChecks();
            }).catch(function (e) {
                state.target = null;
                var box = document.getElementById('uploadTarget');
                if (box) { box.innerHTML = '<span class="dp-chip err">预检失败：' + esc(e.message) + '</span>'; }
            });
    }

    function selectedFile() {
        var input = document.getElementById('fileInput');
        return input && input.files && input.files.length ? input.files[0] : null;
    }

    /* 文件级检查（大小 / 白名单 / 同名冲突）都在前端做：这三件事不需要再问一次后端，
       而且同名冲突今天要到"拿到导入锁之后"才炸——那是用户等了几十秒才被告知失败。 */
    function applyFileChecks() {
        var btn = document.getElementById('uploadBtn');
        var box = document.getElementById('uploadTarget');
        var reason = null;
        var file = selectedFile();
        var limits = state.limits;

        if (state.target && !state.target.writable) {
            reason = '域不可写';
        }
        if (!reason && file && limits) {
            var ext = (file.name.split('.').pop() || '').toLowerCase();
            var allowed = (limits.allowedExtensions || []).map(function (e) { return String(e).toLowerCase(); });
            if (allowed.length && allowed.indexOf(ext) < 0) {
                reason = '不支持的文件类型（白名单：' + allowed.join(' / ') + '）';
            } else if (limits.maxFileSize && file.size > limits.maxFileSize) {
                reason = '文件超过上限 ' + Math.round(limits.maxFileSize / 1048576 * 10) / 10 + ' MB';
            }
        }
        if (btn) { btn.disabled = !!reason; }

        if (reason && box && state.target) {
            renderTarget();
            box.innerHTML += '<span class="dp-chip err">' + esc(reason) + '</span>';
            return;
        }

        /* 同名文档：同名判定不区分大小写，且作用域是"该域索引内" */
        if (file && state.target && state.target.writable) {
            var domain = state.target.domain || 'default';
            var lower = file.name.toLowerCase();
            var dup = state.docs.some(function (d) {
                return (d.domain || 'default') === domain
                    && (d.fileNameLower || (d.fileName || '').toLowerCase()) === lower;
            });
            var chk = document.getElementById('replaceChk');
            if (dup) {
                if (chk && !chk.checked) {
                    chk.checked = true;
                    Console.toast('该域下已存在同名文档，已勾选「覆盖更新」', { type: 'warning' });
                }
                if (box) {
                    box.innerHTML = (box.innerHTML || '') + '<span class="dp-chip warn">同名已存在，将覆盖</span>';
                }
            }
        }
    }

    function upload() {
        var input = document.getElementById('fileInput');
        var btn = document.getElementById('uploadBtn');
        var box = document.getElementById('uploadResult');
        if (!input.files || !input.files.length) {
            Console.toast('请先选择文件', { type: 'warning' });
            return;
        }
        var form = new FormData();
        form.append('file', input.files[0]);
        var replace = document.getElementById('replaceChk').checked;
        var domain = document.getElementById('domainSelect').value || '';
        var query = '/admin/kb/documents?replace=' + (replace ? 'true' : 'false');
        if (domain) { query += '&domain=' + encodeURIComponent(domain); }

        btn.disabled = true;
        btn.classList.add('btn-loading');
        box.className = 'result show';
        box.textContent = '上传中…（切片 + 向量化，耗时随文件体积增长）';

        fetch(query, {
            method: 'POST',
            body: form,
            credentials: 'same-origin'
        }).then(function (resp) {
            return resp.json().then(function (data) { return { data: data }; })
                .catch(function () { return { data: { success: false, error: '响应解析失败' } }; });
        }).then(function (wrapped) {
            var data = wrapped.data;
            box.textContent = JSON.stringify(data, null, 2);
            box.className = 'result show ' + (data.success ? 'ok' : 'err');
            if (data.success) {
                input.value = '';
                loadDocuments();
                loadOverview();
            }
        }).catch(function (e) {
            box.textContent = '请求失败: ' + e.message;
            box.className = 'result show err';
        }).then(function () {
            btn.disabled = false;
            btn.classList.remove('btn-loading');
            applyFileChecks();
        });
    }

    /* ==================== 维护动作（P0 保持既有语义，作用域分级在 P1） ==================== */

    function rebuild() {
        Console.confirm('确认重建索引？重建会清空现有索引，已有文档需重新上传。',
            { title: '索引重建', okText: '重建', danger: true }).then(function (ok) {
            if (!ok) { return; }
            var btn = document.getElementById('rebuildBtn');
            var box = document.getElementById('rebuildResult');
            btn.disabled = true;
            btn.classList.add('btn-loading');
            box.className = 'result show';
            box.textContent = '重建中…（会建立真实连接并调用 embedding，耗时较长）';
            Console.api.post('/admin/kb/rebuild').then(function (data) {
                box.textContent = JSON.stringify(data, null, 2);
                box.className = 'result show ok';
                loadDocuments();
                loadOverview();
            }).catch(function (e) {
                box.textContent = '请求失败: ' + e.message;
                box.className = 'result show err';
            }).then(function () {
                btn.disabled = false;
                btn.classList.remove('btn-loading');
            });
        });
    }

    function loadBackends() {
        var select = document.getElementById('reindexTarget');
        Console.api.get('/admin/kb/backends').then(function (data) {
            var backends = data.backends || [];
            if (!backends.length) {
                addOption(select, '', '（无可用后端）');
                return;
            }
            backends.forEach(function (b) {
                var caps = b.capabilities || {};
                var tags = [];
                if (caps.vector) { tags.push('向量'); }
                if (caps.keyword) { tags.push('关键字'); }
                if (caps.storesText) { tags.push('存原文'); }
                addOption(select, b.id, b.id + (tags.length ? '（' + tags.join('/') + '）' : ''));
                var opt = select.lastChild;
                if (!caps.vector) { opt.disabled = true; }
            });
        }).catch(function (e) {
            addOption(select, '', '读取后端失败：' + e.message);
        });
    }

    function reindex() {
        var select = document.getElementById('reindexTarget');
        var target = select.value;
        if (!target) {
            Console.toast('请先选择目标后端', { type: 'warning' });
            return;
        }
        Console.confirm('确认把当前全部知识库内容重灌到后端「' + target + '」？'
            + '该操作会清空目标后端对应的 collection 并据原文重建，但不会改变任何域的检索落点。',
            { title: '跨后端重灌', okText: '重灌', danger: true }).then(function (ok) {
            if (!ok) { return; }
            var btn = document.getElementById('reindexBtn');
            var box = document.getElementById('reindexResult');
            btn.disabled = true;
            btn.classList.add('btn-loading');
            box.className = 'result show';
            box.textContent = '重灌中…（从 ChunkArchive 读原文 → 向量化 → 写入目标后端，耗时较长）';
            Console.api.post('/admin/kb/reindex', { targetBackendId: target }).then(function (data) {
                box.textContent = JSON.stringify(data, null, 2);
                box.className = 'result show ok';
                loadDocuments();
                loadOverview();
            }).catch(function (e) {
                box.textContent = '请求失败: ' + e.message;
                box.className = 'result show err';
            }).then(function () {
                btn.disabled = false;
                btn.classList.remove('btn-loading');
            });
        });
    }

    /* ==================== 接线 ==================== */

    Console.renderSidebar('knowledge');
    document.getElementById('uploadBtn').addEventListener('click', upload);
    document.getElementById('rebuildBtn').addEventListener('click', rebuild);
    document.getElementById('reindexBtn').addEventListener('click', reindex);
    document.getElementById('domainSelect').addEventListener('change', loadTarget);
    document.getElementById('fileInput').addEventListener('change', applyFileChecks);
    document.getElementById('filterDomain').addEventListener('change', renderDocs);
    document.getElementById('filterBody').addEventListener('change', renderDocs);
    document.getElementById('filterBackend').addEventListener('change', renderDocs);
    document.getElementById('filterClear').addEventListener('click', function () {
        ['filterDomain', 'filterBody', 'filterBackend'].forEach(function (id) {
            document.getElementById(id).value = '';
        });
        renderDocs();
    });

    Console.ready(function () {
        loadDomainOptions();
        loadBackends();
        loadOverview();
        loadDocuments();
        loadTarget();
        /* 向量模型卡仍走 /admin/settings（全局唯一，不属于任何知识体） */
        Console.api.get('/admin/settings').then(function (s) {
            var ok = !!s.embeddingConfigured;
            var registered = s.embeddingProfileCount || 0;
            setValue('statEmb', ok ? '已就绪' : (registered ? '未启用' : '未配置'), ok ? '' : 'warn');
            setNote('statEmbNote', ok ? (s.embeddingModelName || '') : (registered
                ? '已登记 ' + registered + ' 个档案，请到「模型设置」启用'
                : '检索与灌库不可用，请先配置向量模型'));
            Console.setModelsWarn(!s.chatConfigured);
        }).catch(function () {
            setValue('statEmb', '读取失败', 'err');
        });
    });
})();

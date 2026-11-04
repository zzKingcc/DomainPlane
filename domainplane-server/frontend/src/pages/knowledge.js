/* ==========================================================================
   知识库页（v2）

   页面的一等对象是「知识体」：一个体 = 一份数据 + 一个索引（名字创建时冻结）。
   域只是消费者，可以多个域共用一个体；删域不解绑之外不碰任何存储。

   所有动作都从知识体卡片发起：上传 / 看文档 / 重建 / 设置 / 绑定域 / 删除。
   数据只来自两个接口：
   - GET  /admin/bodies                 快照（体 + 槽位 + 绑定域 + 计数 + 无主索引）
   - GET  /admin/bodies/{id}/documents  某个体的文档清单

   上传前问 GET /admin/bodies/target?body= 做落点预检（纯读配置，不碰存储）。
   体本身的增删改走 /admin/bodies；连接池与 Redis 仍在「存储配置」页。
   ========================================================================== */
(function () {
    var esc = Console.escapeHtml;

    var state = {
        snap: null,
        limits: null,
        rawOptions: [],
        embeddingReady: false,
        embeddingNote: '',
        connections: [],
        domains: []
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

    /* ==================== 通用弹层（外观借 Tabler 的 .modal，行为自己写） ==================== */

    var opened = null;   /* { root, backdrop, prevOverflow, onClose } */

    function closeModal() {
        if (!opened) { return; }
        var m = opened;
        opened = null;
        document.removeEventListener('keydown', onModalKey);
        if (m.root.parentNode) { m.root.parentNode.removeChild(m.root); }
        if (m.backdrop.parentNode) { m.backdrop.parentNode.removeChild(m.backdrop); }
        document.body.style.overflow = m.prevOverflow;
        if (m.onClose) { m.onClose(); }
    }

    function onModalKey(e) {
        if (e.key === 'Escape' || e.key === 'Esc') {
            e.preventDefault();
            closeModal();
        }
    }

    /**
     * 打开一个弹层。
     * @param {{title:string, body:string, footer:string, size:string, onOpen:Function, onClose:Function}} opts
     */
    function openModal(opts) {
        closeModal();
        var prevOverflow = document.body.style.overflow;

        var root = document.createElement('div');
        root.className = 'modal dp-preview';
        root.setAttribute('tabindex', '-1');
        root.setAttribute('role', 'dialog');
        root.setAttribute('aria-modal', 'true');
        root.innerHTML = '<div class="modal-dialog ' + (opts.size || 'modal-lg') + ' modal-dialog-scrollable" role="document">'
            + '<div class="modal-content">'
            + '<div class="modal-header">'
            + '<h5 class="modal-title">' + esc(opts.title || '') + '</h5>'
            + '<button type="button" class="btn-close" data-dp-close="1" aria-label="关闭"></button>'
            + '</div>'
            + '<div class="modal-body">' + (opts.body || '') + '</div>'
            + '<div class="modal-footer">' + (opts.footer || '')
            + '<button type="button" class="btn btn-outline-secondary" data-dp-close="1">关闭</button>'
            + '</div>'
            + '</div></div>';

        var backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop show';

        opened = { root: root, backdrop: backdrop, prevOverflow: prevOverflow, onClose: opts.onClose };
        document.body.appendChild(root);
        document.body.appendChild(backdrop);
        root.classList.add('show');
        document.body.style.overflow = 'hidden';
        document.addEventListener('keydown', onModalKey);

        root.addEventListener('click', function (e) {
            var t = e.target;
            if (t === root || (t && t.getAttribute && t.getAttribute('data-dp-close'))) {
                closeModal();
            }
        });
        backdrop.addEventListener('click', closeModal);

        if (opts.onOpen) { opts.onOpen(root); }
        var first = root.querySelector('input, select, textarea, button.btn-primary');
        if (first) { first.focus(); }
        return root;
    }

    function copyText(text) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text);
        } else {
            var ta = document.createElement('textarea');
            ta.value = text;
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
        }
    }

    /* ==================== 状态 / 槽位 / 召回 ==================== */

    var STATUS_TEXT = {
        ok: ['正常', 'ok'],
        empty: ['未配落点', 'warn'],
        dead: ['落点不可用', 'err'],
        missing: ['体已删除', 'err'],
        no_index: ['有绑定无索引', 'warn']
    };

    function statusChip(status) {
        var pair = STATUS_TEXT[status] || [status || '—', ''];
        return '<span class="dp-chip ' + pair[1] + '">' + esc(pair[0]) + '</span>';
    }

    var SLOT_WHY = {
        missing: '连接已从存储配置里删除',
        blank: '连接还没填地址',
        unregistered: '连接没注册上（保存存储配置后生效）',
        incapable: '这个连接类型提供不了这一路（例如关键字槽指到了 Qdrant）'
    };

    function slotChip(slot, kindLabel) {
        if (!slot || slot.state === 'absent') {
            return '<span class="dp-chip mute">未配</span>';
        }
        var name = esc(slot.name || slot.connectionId || '');
        var label = '<span class="dp-slot-kind">' + esc(kindLabel) + '</span>';
        if (slot.state === 'ok') {
            return label + '<span class="dp-chip mono ok" title="' + esc((slot.type || '') + ' · ' + slot.connectionId) + '">' + name + '</span>';
        }
        return label + '<span class="dp-chip mono warn" title="' + esc(SLOT_WHY[slot.state] || slot.state) + '">' + name + '</span>';
    }

    function modeChips(b) {
        var modes = b.modes || [];
        var effective = b.effectiveModes || [];
        if (!modes.length) { return '<span class="dp-chip mute">空壳</span>'; }
        var out = modes.map(function (m) {
            var open = effective.indexOf(m) >= 0;
            var text = m === 'keyword' ? '关键字' : '向量';
            return '<span class="dp-chip ' + (open ? '' : 'warn') + '"'
                + (open ? '' : ' title="全局检索策略关闭了这一路"') + '>' + text + (open ? '' : '（策略关闭）') + '</span>';
        }).join('');
        if (b.fanOut) {
            out += '<span class="dp-chip warn" title="两路落在两台机器上，同一份切片要写两次">写两份</span>';
        }
        return out;
    }

    function rawChip(b) {
        var raw = b.raw || {};
        var label = raw.label || raw.location || '本地磁盘';
        var detail = raw.detail || '';
        return '<span class="dp-chip" title="' + esc(detail) + '">' + esc(label) + '</span>';
    }

    function domainChips(b) {
        var domains = b.domains || [];
        if (!domains.length) {
            return '<span class="dp-chip mute">未绑定任何域（没有对话能查到它）</span>';
        }
        var shown = domains.slice(0, 3).map(function (d) {
            return '<span class="dp-chip mono">' + esc(d) + '</span>';
        }).join('');
        if (domains.length > 3) {
            shown += '<span class="dp-chip" title="' + esc(domains.join('\n')) + '">+' + (domains.length - 3) + '</span>';
        }
        return shown;
    }

    /* ==================== 知识体卡片 ==================== */

    function bodyCards(bodies) {
        if (!bodies.length) {
            return '<div class="dp-kb-empty">'
                + '<div class="dp-kb-empty-icon"><svg xmlns="http://www.w3.org/2000/svg" width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="6" rx="8" ry="3"/><path d="M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6"/><path d="M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/></svg></div>'
                + '<h4>还没有知识体</h4>'
                + '<p>知识体是"一份可被多个域共用的资料"。先建一个，选好关键字 / 向量的落点，再往里传文档。</p>'
                + '<button class="btn btn-primary" data-act="create">新建知识体</button>'
                + '</div>';
        }
        return bodies.map(function (b) {
            var id = esc(b.id);
            var ghost = b.status === 'missing';
            return '<article class="dp-kb-card s-' + esc(b.status) + '" data-body="' + id + '">'
                + '<header class="dp-kb-card-head">'
                + '  <div class="dp-kb-title">'
                + '    <b>' + esc(b.name || b.id) + '</b>'
                + '    <code class="dp-kb-slug" title="唯一名（创建后不可改）">' + esc(b.slug || b.id) + '</code>'
                + '  </div>'
                + '  <div class="dp-kb-head-right">' + statusChip(b.status) + '</div>'
                + '</header>'
                + '<div class="dp-kb-index">'
                + '  <span class="dp-kb-label">索引</span>'
                + '  <code>' + esc(b.index || '（未分配）') + '</code>'
                + (b.index ? '<button class="dp-kb-copy" data-act="copy" data-text="' + esc(b.index) + '" title="复制索引名">复制</button>' : '')
                + '  ' + (b.existsIndex ? '<span class="dp-chip ok" title="后端上确实有这个索引">已就绪</span>'
                    : '<span class="dp-chip warn" title="后端上没有这个集合（还没上传过文档，或集合被删了）">无集合</span>')
                + '</div>'
                + '<div class="dp-kb-meta">'
                + '  <div class="dp-kb-row"><span class="dp-kb-label">召回</span><span class="dp-kb-vals">' + modeChips(b) + '</span></div>'
                + '  <div class="dp-kb-row">' + slotChip((b.slots || [])[0], '关键字') + '</div>'
                + '  <div class="dp-kb-row">' + slotChip((b.slots || [])[1], '向量') + '</div>'
                + '  <div class="dp-kb-row"><span class="dp-kb-label">原文</span><span class="dp-kb-vals">' + rawChip(b) + '</span></div>'
                + '</div>'
                + '<div class="dp-kb-stats">'
                + '  <span><b>' + fmt(b.documents) + '</b> 文档</span>'
                + '  <span><b>' + fmt(b.chunks) + '</b> 切片</span>'
                + '  <span><b>' + fmt((b.domains || []).length) + '</b> 域</span>'
                + '</div>'
                + '<div class="dp-kb-domains">' + domainChips(b) + '</div>'
                + '<footer class="dp-kb-actions">'
                + (ghost
                    ? '<span class="dp-kb-ghost-note">这个体的配置已被删除，仍有域绑在它上面。删掉绑定即可恢复。</span>'
                    : '<button class="btn btn-sm btn-primary" data-act="upload">上传</button>'
                      + '<button class="btn btn-sm btn-outline-secondary" data-act="docs">文档</button>'
                      + '<button class="btn btn-sm btn-outline-secondary" data-act="health">健康</button>'
                      + '<button class="btn btn-sm btn-outline-secondary" data-act="rebuild">重建</button>'
                      + '<button class="btn btn-sm btn-outline-secondary" data-act="settings">设置</button>')
                + '<button class="btn btn-sm btn-outline-secondary" data-act="bind">绑定域</button>'
                + '<button class="btn btn-sm btn-outline-danger" data-act="delete">删除</button>'
                + '</footer>'
                + '</article>';
        }).join('');
    }

    function renderSnapshot() {
        var snap = state.snap;
        var grid = document.getElementById('bodyGrid');
        if (!snap) { return; }
        var p = snap.platform || {};
        setValue('statDocs', fmt(p.documents), p.documents ? '' : 'mute');
        setNote('statDocsNote', (p.bodies || 0) + ' 个知识体共同累计');
        setValue('statChunks', fmt(p.chunks), p.chunks ? '' : 'mute');
        setNote('statChunksNote', snap.strategy === 'HYBRID' ? '混合召回（关键字 + 向量）'
            : snap.strategy === 'KEYWORD' ? '仅关键字召回' : '仅向量召回');
        setValue('statBodies', fmt(p.bodies) + ' 个', (p.bodiesInUse || 0) ? '' : 'warn');
        setNote('statBodiesNote', (p.bodiesInUse || 0) + ' 个在用 · ' + (p.bodiesUnbound || 0) + ' 个没绑定域');

        var noteEl = document.getElementById('snapshotNote');
        if (noteEl) {
            var bits = [];
            if (snap.stale) { bits.push('存储读取失败，显示的是旧数据'); }
            if (snap.generatedAt) {
                bits.push('快照 ' + new Date(snap.generatedAt).toLocaleTimeString('zh-CN'));
            }
            noteEl.textContent = bits.join(' · ');
            noteEl.className = 'dp-kb-snapshot' + (snap.stale ? ' warn' : '');
        }

        if (grid) { grid.innerHTML = bodyCards(snap.bodies || []); }

        var orphans = snap.orphans || [];
        var card = document.getElementById('orphanCard');
        if (card) { card.style.display = orphans.length ? '' : 'none'; }
        var badge = document.getElementById('orphanCount');
        if (badge) { badge.textContent = orphans.length + ' 个'; }
        var list = document.getElementById('orphanList');
        if (list) {
            list.innerHTML = orphans.map(function (o) {
                return '<div class="dp-kb-orphan-row">'
                    + '<code>' + esc(o.name) + '</code>'
                    + '<span class="dp-kb-orphan-owners">' + ((o.backends || []).map(function (x) {
                        return '<span class="dp-chip mono">' + esc(x) + '</span>';
                    }).join('') || '<span class="dp-chip mute">未知</span>') + '</span>'
                    + '</div>';
            }).join('');
        }
    }

    function loadSnapshot() {
        return Console.api.get('/admin/bodies').then(function (snap) {
            state.snap = snap;
            state.limits = snap.limits || state.limits;
            state.rawOptions = snap.rawOptions || [];
            renderSnapshot();
        }).catch(function (e) {
            setValue('statDocs', '—', 'err');
            setValue('statChunks', '—', 'err');
            setValue('statBodies', '—', 'err');
            setNote('statBodiesNote', '读取失败：' + e.message);
            var grid = document.getElementById('bodyGrid');
            if (grid) {
                grid.innerHTML = '<div class="dp-kb-empty"><h4>读取知识体失败</h4><p>' + esc(e.message) + '</p>'
                    + '<button class="btn btn-outline-secondary" data-act="reload">重试</button></div>';
            }
        });
    }

    function bodyById(id) {
        var snap = state.snap;
        if (!snap) { return null; }
        var found = null;
        (snap.bodies || []).forEach(function (b) { if (b.id === id) { found = b; } });
        return found;
    }

    /* ==================== 落点预检 ==================== */

    var REASON_TEXT = {
        NO_BODY: '没有给知识体',
        BODY_MISSING: '知识体不存在（可能刚被删）',
        BODY_EMPTY: '这个体一路都没配',
        BODY_UNUSABLE: '这个体的连接都不可用',
        EMBEDDING_NOT_CONFIGURED: '未启用向量模型'
    };

    function reasonFix(reason) {
        if (reason === 'EMBEDDING_NOT_CONFIGURED') {
            return { href: 'models.html', text: '去模型设置' };
        }
        if (reason === 'BODY_EMPTY' || reason === 'BODY_UNUSABLE' || reason === 'BODY_MISSING') {
            return { href: 'infra.html', text: '去存储配置' };
        }
        return null;
    }

    function targetHtml(t) {
        if (!t) { return ''; }
        if (!t.writable) {
            var fix = reasonFix(t.reason);
            return '<span class="dp-chip err">' + esc(t.reasonText || REASON_TEXT[t.reason] || '不可写入') + '</span>'
                + (fix ? '<a class="dp-target-link" href="' + fix.href + '">' + fix.text + '</a>' : '');
        }
        var html = (t.writeBackends || []).map(function (id) {
            return '<span class="dp-chip mono ok">' + esc(id) + '</span>';
        }).join('');
        if (t.fanOut) {
            html += '<span class="dp-chip warn" title="关键字与向量在两台机器上，同一份切片要写两次">写两份</span>';
        }
        return html;
    }

    /* ==================== 文档弹层 ==================== */

    function docsBodyHtml(b) {
        return '<div class="dp-docs-head">'
            + '<div><b>' + esc(b.name || b.id) + '</b> <code class="dp-kb-slug">' + esc(b.slug || b.id) + '</code></div>'
            + '<div><span class="dp-kb-label">索引</span> <code>' + esc(b.index || '—') + '</code></div>'
            + '</div>'
            + '<div class="dp-upload-row">'
            + '<input type="file" id="fileInput" accept=".txt,.md,.markdown,.docx,.doc,.pdf,.xls,.xlsx">'
            + '<label class="dp-op-check"><input type="checkbox" id="replaceChk"> 覆盖更新</label>'
            + '<button class="btn btn-primary btn-sm" id="uploadBtn">上传</button>'
            + '</div>'
            + '<div class="dp-target" id="uploadTarget"></div>'
            + '<div id="uploadResult" class="result"></div>'
            + '<div class="dp-doc-scroll dp-docs-scroll">'
            + '<table class="table dp-doc-table"><thead><tr>'
            + '<th style="width:30%">docId</th><th style="width:34%">文件名</th>'
            + '<th style="width:12%">切片</th><th style="width:24%" class="ta-r">操作</th>'
            + '</tr></thead><tbody id="docBody"><tr class="dp-doc-empty"><td colspan="4"><div>加载中…</div></td></tr></tbody></table>'
            + '</div>';
    }

    function openDocs(bodyId) {
        var b = bodyById(bodyId);
        if (!b) { return; }
        var root = openModal({
            title: '文档 · ' + (b.name || b.id),
            size: 'modal-xl',
            body: docsBodyHtml(b),
            footer: '<button class="btn btn-outline-secondary" id="docsRebuildBtn">从原文重建</button>',
            onOpen: function (r) {
                bindDocEvents(r, bodyId);
                loadDocs(bodyId);
            }
        });
        state.docsBodyId = bodyId;
        return root;
    }

    var docsCache = [];

    function loadDocs(bodyId) {
        var tbody = document.getElementById('docBody');
        if (!tbody) { return; }
        return Console.api.get('/admin/bodies/' + encodeURIComponent(bodyId) + '/documents')
            .then(function (data) {
                docsCache = data.documents || [];
                renderDocs();
            }).catch(function (e) {
                tbody.innerHTML = '<tr class="dp-doc-empty"><td colspan="4"><div>读取文档失败：' + esc(e.message) + '</div></td></tr>';
            });
    }

    function renderDocs() {
        var tbody = document.getElementById('docBody');
        if (!tbody) { return; }
        if (!docsCache.length) {
            tbody.innerHTML = '<tr class="dp-doc-empty"><td colspan="4"><div>这个知识体还没有文档，先在上方上传</div></td></tr>';
            return;
        }
        tbody.innerHTML = docsCache.map(function (d) {
            return '<tr>'
                + '<td><code>' + esc(d.docId || '') + '</code></td>'
                + '<td>' + esc(d.fileName || '') + '</td>'
                + '<td>' + fmt(d.chunks) + '</td>'
                + '<td class="ta-r">'
                + '<button class="btn btn-sm btn-outline-primary" data-view="' + esc(d.docId) + '">切片</button>'
                + '<button class="btn btn-sm btn-outline-danger" data-del="' + esc(d.docId) + '">删除</button>'
                + '</td></tr>';
        }).join('');
        tbody.querySelectorAll('button[data-view]').forEach(function (btn) {
            btn.addEventListener('click', function () { previewDoc(btn.getAttribute('data-view')); });
        });
        tbody.querySelectorAll('button[data-del]').forEach(function (btn) {
            btn.addEventListener('click', function () { removeDoc(btn.getAttribute('data-del')); });
        });
    }

    function bindDocEvents(root, bodyId) {
        var file = root.querySelector('#fileInput');
        var btn = root.querySelector('#uploadBtn');
        var replace = root.querySelector('#replaceChk');
        var result = root.querySelector('#uploadResult');

        function preflight() {
            var box = root.querySelector('#uploadTarget');
            Console.api.get('/admin/bodies/target?body=' + encodeURIComponent(bodyId)).then(function (t) {
                if (!opened) { return; }
                if (box) { box.innerHTML = targetHtml(t); }
                if (btn) { btn.disabled = !t.writable; }
                if (!t.writable && result) {
                    result.className = 'result show err';
                    result.textContent = (t.reasonText || REASON_TEXT[t.reason] || '不可写入')
                        + '：先处理落点，再上传。不会写入半个文档。';
                }
            }).catch(function (e) {
                if (box) { box.innerHTML = '<span class="dp-chip err">预检失败：' + esc(e.message) + '</span>'; }
                if (btn) { btn.disabled = true; }
            });
        }
        preflight();

        if (file) {
            file.addEventListener('change', function () {
                if (btn) { btn.disabled = false; }
                var limits = state.limits;
                var f = file.files && file.files[0];
                if (!f || !limits) { return; }
                var ext = (f.name.split('.').pop() || '').toLowerCase();
                var allowed = (limits.allowedExtensions || []).map(function (e) { return String(e).toLowerCase(); });
                if (allowed.length && allowed.indexOf(ext) < 0) {
                    if (result) {
                        result.className = 'result show err';
                        result.textContent = '不支持的文件类型（白名单：' + allowed.join(' / ') + '）';
                    }
                    if (btn) { btn.disabled = true; }
                } else if (limits.maxFileSize && f.size > limits.maxFileSize) {
                    if (result) {
                        result.className = 'result show err';
                        result.textContent = '文件超过上限 ' + Math.round(limits.maxFileSize / 1048576 * 10) / 10 + ' MB';
                    }
                    if (btn) { btn.disabled = true; }
                }
            });
        }

        if (btn) {
            btn.addEventListener('click', function () {
                if (!file || !file.files || !file.files.length) {
                    Console.toast('请先选择文件', { type: 'warning' });
                    return;
                }
                var form = new FormData();
                form.append('file', file.files[0]);
                var query = '/admin/bodies/' + encodeURIComponent(bodyId) + '/documents?replace='
                    + (replace && replace.checked ? 'true' : 'false');
                btn.disabled = true;
                btn.classList.add('btn-loading');
                if (result) {
                    result.className = 'result show';
                    result.textContent = '上传中…（切片 + 向量化，耗时随文件体积增长）';
                }
                fetch(query, { method: 'POST', body: form, credentials: 'same-origin' })
                    .then(function (resp) {
                        return resp.json().then(function (data) { return { status: resp.status, data: data }; })
                            .catch(function () { return { status: resp.status, data: null }; });
                    })
                    .then(function (w) {
                        var d = w.data || {};
                        var ok = w.status >= 200 && w.status < 300 && (d.code === 0 || d.code === undefined);
                        if (result) {
                            result.className = 'result show ' + (ok ? 'ok' : 'err');
                            result.textContent = ok
                                ? '导入成功：' + (d.fileName || '（未命名）') + '，' + fmt(d.chunks) + ' 个切片'
                                : '导入失败：' + (d.detail || d.message || d.error || ('HTTP ' + w.status));
                        }
                        if (ok) {
                            file.value = '';
                            loadDocs(bodyId);
                            loadSnapshot();
                        }
                    })
                    .catch(function (e) {
                        if (result) {
                            result.className = 'result show err';
                            result.textContent = '请求失败：' + e.message;
                        }
                    })
                    .then(function () {
                        btn.classList.remove('btn-loading');
                        preflight();
                    });
            });
        }

        var rebuild = root.querySelector('#docsRebuildBtn');
        if (rebuild) {
            rebuild.addEventListener('click', function () {
                closeModal();
                openRebuild(bodyId);
            });
        }
    }

    function previewDoc(docId) {
        var doc = null;
        docsCache.forEach(function (d) { if (d.docId === docId) { doc = d; } });
        if (!doc) { return; }
        var bodyId = state.docsBodyId;
        openModal({
            title: (doc.fileName || doc.docId) + ' · 切片预览',
            size: 'modal-lg',
            body: '<div class="dp-preview-body" id="previewBody"><div class="dp-preview-loading">读取切片…</div></div>',
            footer: '<span class="dp-preview-meta" id="previewMeta"></span>',
            onOpen: function (root) {
                Console.api.get('/admin/bodies/' + encodeURIComponent(bodyId) + '/documents/'
                    + encodeURIComponent(docId) + '/chunks?limit=100').then(function (data) {
                    var bodyEl = root.querySelector('#previewBody');
                    var metaEl = root.querySelector('#previewMeta');
                    if (!bodyEl) { return; }
                    var chunks = data.chunks || [];
                    var total = data.total || 0;
                    if (metaEl) {
                        var bits = ['共 ' + fmt(total) + ' 片'];
                        if (chunks.length < total) { bits.push('显示前 ' + chunks.length + ' 片'); }
                        bits.push(data.fromArchive ? '来源：原文档案' : '来源：索引现读（档案缺失）');
                        metaEl.textContent = bits.join(' · ');
                        metaEl.className = 'dp-preview-meta' + (data.fromArchive ? '' : ' warn');
                    }
                    if (!chunks.length) {
                        bodyEl.innerHTML = '<div class="dp-preview-empty">没有读到切片：原文档案与索引里都没有这个 docId 的内容。</div>';
                        return;
                    }
                    bodyEl.innerHTML = chunks.map(function (c) {
                        return '<div class="dp-chunk">'
                            + '<div class="dp-chunk-head"><span class="dp-chunk-seq">#' + esc(String(c.seq)) + '</span>'
                            + '<span class="dp-chunk-len">' + fmt(c.chars) + ' 字</span></div>'
                            + '<pre class="dp-chunk-text">' + esc(c.text || '') + '</pre>'
                            + '</div>';
                    }).join('');
                }).catch(function (e) {
                    var bodyEl = root.querySelector('#previewBody');
                    if (bodyEl) {
                        bodyEl.innerHTML = '<div class="dp-preview-empty">读取切片失败：' + esc(e.message) + '</div>';
                    }
                });
            }
        });
    }

    function removeDoc(docId) {
        var bodyId = state.docsBodyId;
        Console.confirm('删除这个文档？它会从索引里移除、原文档案一并删除，文件名随即释放（可再上传同名文件）。',
            { title: '删除文档', okText: '删除', danger: true }).then(function (ok) {
            if (!ok) { return; }
            Console.api.del('/admin/bodies/' + encodeURIComponent(bodyId) + '/documents/' + encodeURIComponent(docId))
                .then(function () {
                    loadDocs(bodyId);
                    loadSnapshot();
                }).catch(function (e) {
                    Console.toast('删除失败：' + e.message, { type: 'error' });
                });
        });
    }

    function openUpload(bodyId) {
        var b = bodyById(bodyId);
        openModal({
            title: '上传文档 · ' + (b ? (b.name || b.id) : bodyId),
            body: '<div class="dp-upload-row">'
                + '<input type="file" id="fileInput" accept=".txt,.md,.markdown,.docx,.doc,.pdf,.xls,.xlsx">'
                + '<label class="dp-op-check"><input type="checkbox" id="replaceChk"> 覆盖更新</label>'
                + '<button class="btn btn-primary btn-sm" id="uploadBtn">上传</button>'
                + '</div><div class="dp-target" id="uploadTarget"></div>'
                + '<div id="uploadResult" class="result"></div>',
            onOpen: function (root) {
                bindDocEvents(root, bodyId);
                if (root.querySelector('#docsRebuildBtn')) { /* 上传弹层没这个按钮 */ }
            }
        });
    }

    /* ==================== 新建 / 设置 ==================== */

    function connectionOptions(kind, selected) {
        var out = '<option value="">（这一路不配）</option>';
        (state.connections || []).forEach(function (c) {
            var capable = kind === 'keyword'
                ? c.type === 'elasticsearch'
                : (c.type === 'elasticsearch' || c.type === 'qdrant');
            if (!capable) { return; }
            out += '<option value="' + esc(c.id) + '"' + (c.id === selected ? ' selected' : '')
                + '>' + esc(c.name || c.id) + '（' + esc(c.type) + (c.usable ? '' : '，未填地址') + '）</option>';
        });
        return out;
    }

    function rawOptions(selected) {
        var opts = state.rawOptions || [];
        return opts.map(function (o) {
            return '<option value="' + esc(o.id) + '"' + (o.id === selected ? ' selected' : '')
                + (o.detail ? ' title="' + esc(o.detail) + '"' : '') + '>'
                + esc(o.label || o.id) + (o.available === false ? '（不可用）' : '') + '</option>';
        }).join('');
    }

    function formHtml(b, creating) {
        var rawId = (b && b.raw && b.raw.location) || 'local';
        return '<div class="dp-body-form">'
            + '<label class="dp-form-field"><span>唯一名（slug）<em>必填</em></span>'
            + '<input class="form-control" id="bodySlug" value="' + esc(b ? (b.slug || b.id) : '')
            + '"' + (creating ? '' : ' readonly') + ' placeholder="sales-kb" maxlength="40">'
            + '<small>' + (creating
                ? '小写字母 / 数字 / 连字符，2~40 位，首尾必须是字母或数字。索引名由它派生，创建后不可修改。'
                : '唯一名决定索引名，创建后不可修改（改名会等于换一份数据）。') + '</small></label>'
            + '<label class="dp-form-field"><span>展示名</span>'
            + '<input class="form-control" id="bodyName" value="' + esc(b ? (b.name || '') : '') + '" placeholder="销售知识库"></label>'
            + '<label class="dp-form-field"><span>说明</span>'
            + '<textarea class="form-control" id="bodyDesc" rows="2" placeholder="这份资料是给谁用的、多久更新一次">'
            + esc(b && b.description ? b.description : '') + '</textarea></label>'
            + '<div class="dp-form-grid">'
            + '<label class="dp-form-field"><span>关键字落点 <em>全文检索</em></span>'
            + '<select class="form-select" id="bodyKeyword">' + connectionOptions('keyword', b ? b.keyword : null) + '</select></label>'
            + '<label class="dp-form-field"><span>向量落点 <em>语义检索</em></span>'
            + '<select class="form-select" id="bodyVector">' + connectionOptions('vector', b ? b.vector : null) + '</select></label>'
            + '</div>'
            + (creating ? '' : '<div class="dp-form-hint">改落点不会直接生效：保存时先预演，'
                + '确认后把数据搬（或从原文重建）到新位置，写完校验通过才切换。</div>')
            + '<label class="dp-form-field"><span>原文落点</span>'
            + '<select class="form-select" id="bodyRaw">' + rawOptions(rawId) + '</select>'
            + '<small>原文是权威副本：删了索引能从这里重建。换落点会把档案与原始文件搬过去，'
            + '搬完校验通过才切。对象存储连接在「存储配置」页加。</small></label>'
            + '<div class="dp-form-hint">至少选一路。两路指向同一台机器时只写一份；分属两台时同一份切片写两份。</div>'
            + '</div>';
    }

    function readForm(root) {
        return {
            slug: root.querySelector('#bodySlug').value.trim(),
            name: root.querySelector('#bodyName').value.trim(),
            description: root.querySelector('#bodyDesc').value.trim(),
            keyword: root.querySelector('#bodyKeyword').value || null,
            vector: root.querySelector('#bodyVector').value || null,
            raw: root.querySelector('#bodyRaw').value || null
        };
    }

    function openCreate() {
        openModal({
            title: '新建知识体',
            body: formHtml(null, true),
            footer: '<button class="btn btn-primary" id="bodySave">创建</button>',
            onOpen: function (root) {
                root.querySelector('#bodySave').addEventListener('click', function () {
                    var f = readForm(root);
                    var btn = root.querySelector('#bodySave');
                    btn.disabled = true;
                    Console.api.post('/admin/bodies', f).then(function (res) {
                        closeModal();
                        Console.toast('已创建「' + (res.body && res.body.name || f.name || f.slug) + '」', { type: 'success' });
                        loadSnapshot();
                    }).catch(function (e) {
                        btn.disabled = false;
                        Console.toast('创建失败：' + e.message, { type: 'error', wide: true });
                    });
                });
            }
        });
    }

    /**
     * 设置：名字 / 说明直接保存；两路落点与原文落点都走「迁移」。
     *
     * 落点不能直接改（后端也会拒）：索引那一路要把数据从原文写满到新连接再切指针，
     * 原文那一路要把对象搬过去再切——直接改指针的唯一结果是"立刻查空、旧数据再也看不见"。
     */
    function openSettings(bodyId) {
        var b = bodyById(bodyId);
        if (!b) { return; }
        openModal({
            title: '设置 · ' + (b.name || b.id),
            body: formHtml(b, false),
            footer: '<button class="btn btn-primary" id="bodySave">保存</button>',
            onOpen: function (root) {
                root.querySelector('#bodySave').addEventListener('click', function () {
                    var f = readForm(root);
                    var btn = root.querySelector('#bodySave');
                    var from = { keyword: b.keyword || null, vector: b.vector || null };
                    var to = { keyword: f.keyword || null, vector: f.vector || null };
                    var changed = [];
                    ['keyword', 'vector'].forEach(function (k) {
                        if (from[k] !== to[k]) { changed.push(k); }
                    });
                    var rawFrom = (b.raw && b.raw.location) || 'local';
                    var rawTo = f.raw || 'local';
                    var rawChanged = rawFrom !== rawTo;
                    if (changed.length + (rawChanged ? 1 : 0) > 1) {
                        Console.toast('一次只改一项：索引落点（关键字 / 向量）与原文落点要分开迁',
                            { type: 'warning', wide: true });
                        return;
                    }
                    if (changed.length === 1 && !to[changed[0]]) {
                        Console.toast('不能把这一路清空：知识体至少要留一路。真要换掉就迁到另一台连接',
                            { type: 'warning', wide: true });
                        return;
                    }
                    btn.disabled = true;
                    saveMeta(bodyId, f).then(function () {
                        if (rawChanged) {
                            return migrateRaw(bodyId, rawTo, rawFrom);
                        }
                        if (!changed.length) { return false; }
                        return migrateRoute(bodyId, changed[0], to[changed[0]], from[changed[0]]);
                    }).then(function (moved) {
                        closeModal();
                        if (moved) {
                            Console.toast(rawChanged
                                ? '原文已搬到新落点；旧落点上的副本保留未删'
                                : '落点已切换：索引名不变，旧连接上的同名集合保留未删',
                                { type: 'success', wide: true });
                        } else if (changed.length || rawChanged) {
                            Console.toast('已保存（落点没有迁移）', { type: 'info' });
                        } else {
                            Console.toast('已保存', { type: 'success' });
                        }
                        loadSnapshot();
                    }).catch(function (e) {
                        btn.disabled = false;
                        Console.toast('保存失败：' + e.message, { type: 'error', wide: true });
                    });
                });
            }
        });
    }

    /** 只提交名字 / 说明：两路落点与原文落点都由迁移端点负责 */
    function saveMeta(bodyId, f) {
        return Console.api.put('/admin/bodies/' + encodeURIComponent(bodyId), {
            name: f.name,
            description: f.description
        });
    }

    /** 原文落点的人话名字（下拉选项由后端给，这里只做回显兜底） */
    function rawLabelOf(id) {
        var opts = state.rawOptions || [];
        for (var i = 0; i < opts.length; i++) {
            if (opts[i].id === id) { return opts[i].label || id; }
        }
        return id === 'local' ? '本地原文存储' : id;
    }

    /**
     * 换原文落点：先预演（后端只数对象），确认后把档案与原始文件搬到新落点。
     *
     * 与 migrateRoute 的差别：索引那一路的上游是原文（可以重建），原文这一路没有上游
     * ——搬不过去就是真丢了，所以文案要写明"旧副本保留"。
     *
     * @return Promise&lt;boolean&gt; true = 已搬完，false = 用户取消
     */
    function migrateRaw(bodyId, target, fromId) {
        var url = '/admin/bodies/' + encodeURIComponent(bodyId) + '/routes';
        return Console.api.put(url + '?dryRun=true', { raw: target }).then(function (plan) {
            if (!plan.ready) {
                throw new Error(plan.reason || '这个原文落点换不上去');
            }
            var lines = [
                '把原文从「' + rawLabelOf(fromId) + '」搬到「' + rawLabelOf(target) + '」？',
                '',
                '· ' + fmt(plan.chunksObjects) + ' 份切片档案 + ' + fmt(plan.fileObjects) + ' 个原始文件',
                '· 逐个复制并校验对象数，全部对上之后才切指针',
                '· 搬到一半失败会中止，原落点不变',
                '· 旧落点上的副本不会自动删除'
            ];
            return Console.confirm(lines.join('\n'), { title: '迁移原文', okText: '开始搬迁' })
                .then(function (ok) {
                    if (!ok) { return false; }
                    Console.toast('正在搬原文，对象多时会比较久…', { type: 'info' });
                    return Console.api.put(url, { raw: target }).then(function () { return true; });
                });
        });
    }

    /**
     * 换一路落点：先预演（后端只读），用户确认后正式迁移。
     *
     * 预演与执行用的是后端同一套判据（都在 /routes 上），所以"预演说能迁"
     * 就等于"执行不会被配置拒"——这是本页所有动作的统一口径。
     *
     * @return Promise&lt;boolean&gt; true = 已迁移，false = 用户取消
     */
    function migrateRoute(bodyId, kind, target, fromId) {
        var label = kind === 'keyword' ? '关键字' : '向量';
        var url = '/admin/bodies/' + encodeURIComponent(bodyId) + '/routes';
        return Console.api.put(url + '?dryRun=true', { kind: kind, connection: target })
            .then(function (plan) {
                if (!plan.ready) {
                    throw new Error(plan.reason || '这个落点换不上去');
                }
                var lines = [
                    '把' + label + '落点从「' + (fromId || '未配') + '」换到「' + target + '」？',
                    '',
                    '· ' + fmt(plan.documents) + ' 个文档从原文重建到新连接'
                        + (plan.recomputeVectors ? '（需要重新向量化）' : ''),
                    '· 索引名 ' + (plan.index || '') + ' 不变',
                    '· 写完并校验条数之后才切指针，迁移期间检索仍走旧连接',
                    '· 旧连接上的同名集合不会删除'
                ];
                if (plan.missingArchive) {
                    lines.push('· 有 ' + fmt(plan.missingArchive) + ' 个文档没有原文档案，迁不过去（会跳过）');
                }
                return Console.confirm(lines.join('\n'), { title: '迁移落点', okText: '开始迁移' })
                    .then(function (ok) {
                        if (!ok) { return false; }
                        Console.toast('正在迁移，文档多时会比较久…', { type: 'info' });
                        return Console.api.put(url, { kind: kind, connection: target })
                            .then(function () { return true; });
                    });
            });
    }

    /* ==================== 健康 ==================== */

    var HEALTH_STATE = {
        absent: ['未配', 'mute'],
        missing: ['连接已删', 'err'],
        blank: ['地址没填', 'warn'],
        unregistered: ['连接未就绪', 'warn'],
        unreachable: ['不可达', 'err'],
        error: ['读取失败', 'err'],
        no_index: ['集合不存在', 'warn'],
        ok: ['正常', 'ok']
    };

    function healthHtml(h) {
        function row(label, route) {
            var pair = HEALTH_STATE[route.state] || [route.state || '—', ''];
            return '<tr><td>' + esc(label) + '</td>'
                + '<td>' + esc(route.name || route.connectionId || '—') + '</td>'
                + '<td><span class="dp-chip ' + pair[1] + '">' + esc(pair[0]) + '</span></td>'
                + '<td>' + (route.exists ? '有' : '无') + '</td>'
                + '<td>' + (route.dimension == null ? '—' : route.dimension) + '</td></tr>';
        }
        var model = h.model || {};
        var pair = h.consistency === 'ok' ? ['ok', 'ok']
            : h.consistency === 'no_index' ? ['warn', 'warn']
            : h.consistency === 'unknown' ? ['mute', 'mute'] : ['err', 'err'];
        return '<div class="dp-kb-health-head">'
            + '<span class="dp-chip ' + pair[1] + '">' + esc(pair[0]) + '</span>'
            + '<span>' + esc(h.consistencyText || '') + '</span></div>'
            + '<table class="table dp-kb-health"><thead><tr>'
            + '<th>落点</th><th>连接</th><th>状态</th><th>集合</th><th>维度</th>'
            + '</tr></thead><tbody>'
            + row('关键字', h.keyword || {})
            + row('向量', h.vector || {})
            + '</tbody></table>'
            + '<div class="dp-form-hint">当前向量模型：'
            + (model.configured ? '维度 ' + (model.dimension == null ? '未知' : model.dimension) : '未启用')
            + ' · 索引 ' + esc(h.index || '—') + '</div>';
    }

    /**
     * 健康：两路落点 + 向量模型的三方维度对照。
     *
     * 单独一次请求：每一格都要连远程（问集合在不在、读 mapping 维度），
     * 塞进首屏会把知识库页拖成秒级。
     */
    function openHealth(bodyId) {
        var b = bodyById(bodyId);
        if (!b) { return; }
        openModal({
            title: '健康 · ' + (b.name || b.id),
            body: '<div class="dp-kb-loading">正在检查两路落点…</div>',
            onOpen: function (root) {
                Console.api.get('/admin/bodies/' + encodeURIComponent(bodyId) + '/health')
                    .then(function (h) {
                        root.querySelector('.modal-body').innerHTML = healthHtml(h);
                    }).catch(function (e) {
                        root.querySelector('.modal-body').innerHTML =
                            '<div class="dp-kb-empty"><h4>检查失败</h4><p>' + esc(e.message) + '</p></div>';
                    });
            }
        });
    }

    /* ==================== 绑定域 ==================== */

    function openBind(bodyId) {
        var b = bodyById(bodyId);
        if (!b) { return; }
        var bound = {};
        (b.domains || []).forEach(function (d) { bound[d] = true; });
        var names = (state.domains || []).slice();
        if (!names.length) {
            names = (b.domains || []).slice();
        }
        var rows = names.map(function (d) {
            return '<label class="dp-bind-row"><input type="checkbox" value="' + esc(d) + '"'
                + (bound[d] ? ' checked' : '') + '> <code>' + esc(d) + '</code></label>';
        }).join('');
        if (!rows) {
            rows = '<div class="dp-kb-note">还没有可绑定的域。先到「域空间」建域。</div>';
        }
        openModal({
            title: '绑定域 · ' + (b.name || b.id),
            body: '<div class="dp-kb-note">勾上的域可以检索到这份资料；一个体可以同时给多个域用。'
                + '没勾的域查不到它（不继承父域的知识体）。</div><div class="dp-bind-list">' + rows + '</div>',
            footer: '<button class="btn btn-primary" id="bindSave">保存绑定</button>',
            onOpen: function (root) {
                root.querySelector('#bindSave').addEventListener('click', function () {
                    var want = {};
                    root.querySelectorAll('.dp-bind-row input:checked').forEach(function (i) {
                        want[i.value] = true;
                    });
                    var toBind = Object.keys(want).filter(function (d) { return !bound[d]; });
                    var toUnbind = Object.keys(bound).filter(function (d) { return !want[d]; });
                    if (!toBind.length && !toUnbind.length) { closeModal(); return; }
                    var btn = root.querySelector('#bindSave');
                    btn.disabled = true;
                    var chain = Promise.resolve();
                    toBind.forEach(function (d) {
                        chain = chain.then(function () {
                            return Console.api.post('/admin/bodies/' + encodeURIComponent(bodyId) + '/bind', { domain: d });
                        });
                    });
                    toUnbind.forEach(function (d) {
                        chain = chain.then(function () {
                            return Console.api.post('/admin/bodies/' + encodeURIComponent(bodyId) + '/unbind', { domain: d });
                        });
                    });
                    chain.then(function () {
                        closeModal();
                        Console.toast('已更新绑定：+' + toBind.length + ' / -' + toUnbind.length, { type: 'success' });
                        loadSnapshot();
                    }).catch(function (e) {
                        btn.disabled = false;
                        Console.toast('绑定失败：' + e.message, { type: 'error', wide: true });
                        loadSnapshot();
                    });
                });
            }
        });
    }

    /* ==================== 重建 / 删除 ==================== */

    function openRebuild(bodyId) {
        var b = bodyById(bodyId);
        if (!b) { return; }
        openModal({
            title: '重建 · ' + (b.name || b.id),
            body: '<div class="dp-kb-note">重建只影响这个知识体的索引（<code>' + esc(b.index || '') + '</code>），'
                + '不动原文、不动其他体、也不改任何域的绑定。</div>'
                + '<div class="dp-rebuild-opt">'
                + '<label><input type="radio" name="rebuildFrom" value="archive" checked>'
                + ' <b>从原文重建</b><span>清空索引 → 从原文档案逐片重新向量化写回。文档不会丢，耗时随文档数增长。</span></label>'
                + '<label><input type="radio" name="rebuildFrom" value="reset">'
                + ' <b>重建成空索引</b><span>只清空。适合"这批资料全部作废、要重新上传"的场景；原文档案同样保留。</span></label>'
                + '</div>',
            footer: '<button class="btn btn-outline-danger" id="rebuildGo">开始重建</button>',
            onOpen: function (root) {
                root.querySelector('#rebuildGo').addEventListener('click', function () {
                    var from = root.querySelector('input[name="rebuildFrom"]:checked').value;
                    closeModal();
                    rebuildNow(bodyId, from);
                });
            }
        });
    }

    function rebuildNow(bodyId, from) {
        var b = bodyById(bodyId);
        Console.confirm('确认' + (from === 'archive' ? '从原文重建' : '清空') + '「'
            + (b ? (b.name || b.id) : bodyId) + '」的索引？'
            + (from === 'archive' ? '\n重建期间这个体暂时不可检索。' : '\n清空后需要重新上传文档。'),
            { title: '重建知识体', okText: '开始', danger: true }).then(function (ok) {
            if (!ok) { return; }
            Console.toast('已开始重建，可能需要几分钟…', { type: 'info' });
            Console.api.post('/admin/bodies/' + encodeURIComponent(bodyId) + '/rebuild', { from: from })
                .then(function (res) {
                    Console.toast(res.message || '重建完成', { type: 'success', wide: true });
                    loadSnapshot();
                }).catch(function (e) {
                    Console.toast('重建失败：' + e.message, { type: 'error', wide: true });
                });
        });
    }

    function openDelete(bodyId) {
        var b = bodyById(bodyId);
        if (!b) { return; }
        var bound = b.domains || [];
        openModal({
            title: '删除知识体 · ' + (b.name || b.id),
            body: '<div class="dp-kb-note">删除会移除<b>索引里的数据</b>，并从配置里去掉这个体。'
                + (bound.length
                    ? '<br><b class="dp-danger-text">还有 ' + bound.length + ' 个域绑着它（' + esc(bound.join('、')) + '）。</b>'
                      + '删除会同时解绑这些域，它们随即失去知识库。'
                    : '<br>当前没有域绑着它。')
                + '</div>'
                + '<label class="dp-op-check"><input type="checkbox" id="keepRaw" checked> 保留原文档案（推荐）</label>'
                + '<div class="dp-form-hint">保留原文后，以后可以再建一个同名体、把档案按原文重建回来；不保留则原文一并删除，不可恢复。</div>'
                + '<label class="dp-form-field"><span>输入 <code>' + esc(b.name || b.id) + '</code> 确认</span>'
                + '<input class="form-control" id="deleteConfirm" placeholder="' + esc(b.name || b.id) + '"></label>',
            footer: '<button class="btn btn-outline-danger" id="deleteGo">删除</button>',
            onOpen: function (root) {
                root.querySelector('#deleteGo').addEventListener('click', function () {
                    var typed = root.querySelector('#deleteConfirm').value.trim();
                    if (!typed) {
                        Console.toast('请输入知识体名称确认', { type: 'warning' });
                        return;
                    }
                    var keepRaw = root.querySelector('#keepRaw').checked;
                    var btn = root.querySelector('#deleteGo');
                    btn.disabled = true;
                    var url = '/admin/bodies/' + encodeURIComponent(bodyId)
                        + '?keepRaw=' + (keepRaw ? 'true' : 'false')
                        + '&confirm=' + encodeURIComponent(typed) + '&force=true';
                    Console.api.del(url).then(function (res) {
                        closeModal();
                        Console.toast('已删除「' + (b.name || b.id) + '」'
                            + (res.rawKept ? '（原文已保留）' : ''), { type: 'success', wide: true });
                        loadSnapshot();
                    }).catch(function (e) {
                        btn.disabled = false;
                        Console.toast('删除失败：' + e.message, { type: 'error', wide: true });
                        loadSnapshot();
                    });
                });
            }
        });
    }

    /* ==================== 运维区 ==================== */

    var OPS_STATUS = {
        ok: ['在用', 'ok'],
        legacy: ['遗留', 'warn'],
        orphan: ['无主', 'err']
    };

    /**
     * 运维区默认收起：展开才诊断。
     *
     * 诊断要逐台枚举集合、问维度与切片数，全部是远程调用；放进首屏等于每次打开知识库
     * 都多等几秒。事件只在这里绑一次（内容会整体重绘，委托给容器）。
     */
    function initOps() {
        var btn = document.getElementById('opsToggleBtn');
        var box = document.getElementById('opsBody');
        if (!btn || !box) { return; }
        btn.addEventListener('click', function () {
            if (!box.hidden) {
                box.hidden = true;
                btn.textContent = '展开';
                return;
            }
            box.hidden = false;
            btn.textContent = '收起';
            loadOps();
        });
        box.addEventListener('click', onOpsClick);
    }

    function loadOps() {
        var box = document.getElementById('opsBody');
        if (!box) { return; }
        box.innerHTML = '<div class="dp-kb-loading">正在诊断…（逐台枚举集合）</div>';
        Console.api.get('/admin/bodies/ops/diagnostics').then(function (d) {
            box.innerHTML = opsHtml(d);
        }).catch(function (e) {
            box.innerHTML = '<div class="dp-kb-empty"><h4>诊断失败</h4><p>' + esc(e.message) + '</p></div>';
        });
    }

    function opsHtml(d) {
        var s = d.summary || {};
        var head = '<div class="dp-kb-note">' + fmt(s.collections) + ' 个集合 · ' + fmt(s.chunks)
            + ' 条切片 · 无主 ' + fmt(s.orphans) + ' · 遗留 ' + fmt(s.legacy) + '</div>';
        var blocks = (d.backends || []).map(function (b) {
            var rows = (b.collections || []).map(function (c) {
                var pair = OPS_STATUS[c.status] || [c.status || '—', ''];
                var act = '';
                if (c.status === 'orphan') {
                    act = '<button class="linkbtn" data-adopt="' + esc(c.name) + '" data-conn="'
                        + esc(b.id) + '">认领</button>';
                } else if (c.status === 'legacy') {
                    act = '<button class="linkbtn danger" data-clean="' + esc(c.name) + '" data-conn="'
                        + esc(b.id) + '">清理</button>';
                }
                return '<tr><td class="mono">' + esc(c.name) + '</td>'
                    + '<td>' + (c.bodyName ? esc(c.bodyName) : '<span class="muted-sm">—</span>') + '</td>'
                    + '<td><span class="dp-chip ' + pair[1] + '">' + esc(pair[0]) + '</span></td>'
                    + '<td>' + (c.dimension == null ? '—' : c.dimension) + '</td>'
                    + '<td>' + fmt(c.chunks) + '</td>'
                    + '<td class="ta-r">' + act + '</td></tr>';
            }).join('');
            return '<div class="dp-ops-block">'
                + '<div class="dp-ops-block-head"><code>' + esc(b.id) + '</code>'
                + '<span class="muted-sm">' + esc(b.location || '') + '</span>'
                + (b.reachable ? '' : '<span class="dp-chip err">不可达</span>')
                + '</div>'
                + (b.reachable
                    ? (rows
                        ? '<table class="table dp-kb-health"><thead><tr><th>集合</th><th>归属</th>'
                            + '<th>状态</th><th>维度</th><th>切片</th><th class="ta-r">操作</th></tr></thead>'
                            + '<tbody>' + rows + '</tbody></table>'
                        : '<div class="muted-sm">这台连接上没有知识库集合</div>')
                    : '<div class="muted-sm">连不上，跳过这一台</div>')
                + '</div>';
        }).join('');
        return head + (blocks || '<div class="muted-sm">还没有可用的连接</div>') + mergeHtml();
    }

    function mergeHtml() {
        var bodies = (state.snap && state.snap.bodies) || [];
        var opts = bodies.map(function (b) {
            return '<option value="' + esc(b.id) + '">' + esc(b.name || b.id) + '</option>';
        }).join('');
        if (!opts) { return ''; }
        return '<div class="dp-ops-block">'
            + '<div class="dp-ops-block-head"><b>合并知识体</b>'
            + '<span class="muted-sm">把源体的原文重灌进目标体；源体不删</span></div>'
            + '<div class="dp-form-grid">'
            + '<label class="dp-form-field"><span>源体（提供原文）</span>'
            + '<select class="form-select" id="mergeSource">' + opts + '</select></label>'
            + '<label class="dp-form-field"><span>目标体（接收）</span>'
            + '<select class="form-select" id="mergeTarget">' + opts + '</select></label>'
            + '</div>'
            + '<label class="dp-op-check"><input type="checkbox" id="mergeReplace"> 同名文档覆盖</label>'
            + '<button class="btn btn-sm btn-outline-secondary" id="mergeGo">合并</button>'
            + '</div>';
    }

    function onOpsClick(e) {
        var t = e.target;
        if (!t || !t.getAttribute) { return; }
        var adopt = t.getAttribute('data-adopt');
        if (adopt) { openAdopt(t.getAttribute('data-conn'), adopt); return; }
        var clean = t.getAttribute('data-clean');
        if (clean) { cleanCollection(t.getAttribute('data-conn'), clean); return; }
        if (t.id === 'mergeGo') { openMerge(); }
    }

    /**
     * 认领无主集合：集合名原样当作体的冻结索引，已有数据不动。
     *
     * 唯一名必须新起（它是体的身份，也是以后新建索引的派生源），不跟着集合名走——
     * 集合名是 <code>domainplane_kb_&lt;slug&gt;_&lt;hash&gt;</code> 形态，直接拿来当 slug 不合规。
     */
    function openAdopt(connection, index) {
        openModal({
            title: '认领集合',
            body: '<div class="dp-kb-note">集合 <code>' + esc(index) + '</code> 会<b>原样</b>作为知识体的'
                + '冻结索引名：已有数据不动，也不用重传。</div>'
                + '<label class="dp-form-field"><span>唯一名（slug）<em>必填</em></span>'
                + '<input class="form-control" id="adoptSlug" placeholder="sales-kb" maxlength="40"></label>'
                + '<label class="dp-form-field"><span>展示名</span>'
                + '<input class="form-control" id="adoptName" placeholder="销售知识库"></label>',
            footer: '<button class="btn btn-primary" id="adoptGo">认领</button>',
            onOpen: function (root) {
                root.querySelector('#adoptGo').addEventListener('click', function () {
                    var slug = root.querySelector('#adoptSlug').value.trim();
                    var name = root.querySelector('#adoptName').value.trim();
                    if (!slug) {
                        Console.toast('先填唯一名', { type: 'warning' });
                        return;
                    }
                    var btn = root.querySelector('#adoptGo');
                    btn.disabled = true;
                    Console.api.post('/admin/bodies/ops/adopt', {
                        connection: connection, index: index, slug: slug, name: name
                    }).then(function () {
                        closeModal();
                        Console.toast('已认领为知识体「' + (name || slug) + '」', { type: 'success' });
                        loadSnapshot();
                        loadOps();
                    }).catch(function (err) {
                        btn.disabled = false;
                        Console.toast('认领失败：' + err.message, { type: 'error', wide: true });
                    });
                });
            }
        });
    }

    function cleanCollection(connection, index) {
        openModal({
            title: '清理遗留集合',
            body: '<div class="dp-kb-note">这个集合属于某个知识体，但那个体已经换到别的落点了。'
                + '删的只是这份残留副本，不影响该体当前的数据。</div>'
                + '<label class="dp-form-field"><span>输入集合名 <code>' + esc(index) + '</code> 确认</span>'
                + '<input class="form-control mono" id="cleanConfirm"></label>',
            footer: '<button class="btn btn-outline-danger" id="cleanGo">删除</button>',
            onOpen: function (root) {
                root.querySelector('#cleanGo').addEventListener('click', function () {
                    var typed = root.querySelector('#cleanConfirm').value.trim();
                    var btn = root.querySelector('#cleanGo');
                    btn.disabled = true;
                    var url = '/admin/bodies/ops/collections?connection=' + encodeURIComponent(connection)
                        + '&index=' + encodeURIComponent(index) + '&confirm=' + encodeURIComponent(typed);
                    Console.api.del(url).then(function () {
                        closeModal();
                        Console.toast('已清理「' + index + '」', { type: 'success' });
                        loadOps();
                    }).catch(function (err) {
                        btn.disabled = false;
                        Console.toast('清理失败：' + err.message, { type: 'error', wide: true });
                    });
                });
            }
        });
    }

    function openMerge() {
        var source = document.getElementById('mergeSource');
        var target = document.getElementById('mergeTarget');
        var replace = document.getElementById('mergeReplace');
        if (!source || !target) { return; }
        if (source.value === target.value) {
            Console.toast('源体和目标体不能是同一个', { type: 'warning' });
            return;
        }
        var srcName = source.options[source.selectedIndex].textContent;
        var tgtName = target.options[target.selectedIndex].textContent;
        Console.confirm('把「' + srcName + '」的原文合并进「' + tgtName + '」？\n\n'
            + '· 按目标体当前的向量模型重算每一片\n'
            + '· 源知识体不会被删除\n'
            + '· 同名文档' + (replace && replace.checked ? '覆盖' : '跳过'),
            { title: '合并知识体', okText: '开始合并' }).then(function (ok) {
            if (!ok) { return; }
            Console.toast('正在合并，文档多时会比较久…', { type: 'info' });
            Console.api.post('/admin/bodies/ops/merge', {
                source: source.value,
                target: target.value,
                replace: !!(replace && replace.checked)
            }).then(function (res) {
                Console.toast(res.message || '合并完成', { type: 'success', wide: true });
                loadSnapshot();
                loadOps();
            }).catch(function (err) {
                Console.toast('合并失败：' + err.message, { type: 'error', wide: true });
            });
        });
    }

    /* ==================== 接线 ==================== */

    function onGridClick(e) {
        var t = e.target;
        var btn = t && t.closest ? t.closest('[data-act]') : null;
        if (!btn) { return; }
        var act = btn.getAttribute('data-act');
        if (act === 'create') { openCreate(); return; }
        if (act === 'reload') { loadSnapshot(); return; }
        if (act === 'copy') {
            copyText(btn.getAttribute('data-text') || '');
            Console.toast('已复制索引名', { type: 'success' });
            return;
        }
        var card = btn.closest('.dp-kb-card');
        if (!card) { return; }
        var bodyId = card.getAttribute('data-body');
        if (act === 'upload') { openUpload(bodyId); }
        else if (act === 'docs') { openDocs(bodyId); }
        else if (act === 'health') { openHealth(bodyId); }
        else if (act === 'rebuild') { openRebuild(bodyId); }
        else if (act === 'settings') { openSettings(bodyId); }
        else if (act === 'bind') { openBind(bodyId); }
        else if (act === 'delete') { openDelete(bodyId); }
    }

    function loadConnections() {
        return Console.api.get('/admin/infra').then(function (data) {
            state.connections = data.connections || [];
        }).catch(function () { state.connections = []; });
    }

    function loadDomains() {
        return Console.api.get('/admin/domains').then(function (data) {
            state.domains = (data.domains || []).map(function (d) {
                return typeof d === 'string' ? d : (d.name || d.domain);
            }).filter(Boolean);
        }).catch(function () { state.domains = []; });
    }

    function loadEmbedding() {
        return Console.api.get('/admin/settings').then(function (s) {
            state.embeddingReady = !!s.embeddingConfigured;
            state.embeddingNote = s.embeddingModelName || '';
            setValue('statEmb', state.embeddingReady ? '已就绪'
                : (s.embeddingProfileCount ? '未启用' : '未配置'), state.embeddingReady ? '' : 'warn');
            setNote('statEmbNote', state.embeddingReady ? state.embeddingNote
                : (s.embeddingProfileCount ? '已登记档案，请到「模型设置」启用'
                    : '检索与灌库不可用，请先配置向量模型'));
            Console.setModelsWarn(!s.chatConfigured);
        }).catch(function () {
            setValue('statEmb', '读取失败', 'err');
        });
    }

    Console.renderSidebar('knowledge');
    var grid = document.getElementById('bodyGrid');
    grid.addEventListener('click', onGridClick);
    initOps();
    document.getElementById('createBodyBtn').addEventListener('click', openCreate);
    document.getElementById('refreshBtn').addEventListener('click', function () {
        loadSnapshot();
    });

    Console.ready(function () {
        loadEmbedding();
        loadConnections();
        loadDomains();
        loadSnapshot();
    });
})();

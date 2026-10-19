/* ==========================================================================
   P1 · knowledge 页脚本
   自 knowledge.html 的内联 <script> 机械平移（1 块合并），逻辑未改。
   ========================================================================== */
(function () {
var esc = Console.escapeHtml;

    /** 域标签：服务端缺字段时按根域算；根域即全域可见 */
    function domainLabel(domain) {
        var d = domain || 'default';
        return '<span class="pill">' + esc(d) + (d === 'default' ? '（全域）' : '') + '</span>';
    }

    /* 域清单走管控台同款 /admin/domains（COOKIE 载具），
       不能用 /api/agent/domains（HEADER/API-key 载具），管控台只带 Cookie，
       后者会被判 10002 弹回登录页。该端点返回 domains = [{name, source, ...}]，
       根域＝source === 'BUILTIN'（即 default）。 */
    function loadDomainOptions() {
        var select = document.getElementById('domainSelect');
        Console.request('/admin/domains').then(function (data) {
            (data.domains || []).forEach(function (d) {
                var name = (typeof d === 'string') ? d : d.name;
                if (!name || name === 'default') return;
                var opt = document.createElement('option');
                opt.value = name;
                opt.textContent = name;
                select.appendChild(opt);
            });
        }).catch(function () {
            // 拉不到域清单不影响上传：默认即根域
        });
    }

    function selectedDomain() {
        return document.getElementById('domainSelect').value || '';
    }

    function loadEmbeddingStatus() {
        var el = document.getElementById('embStatus');
        Console.request('/admin/settings').then(function (s) {
            var ok = !!s.embeddingConfigured;
            /* 名称缺省时不渲染空的 <code>：那看起来像"配上了但没显示出来" */
            var name = s.embeddingModelName ? '<code>' + esc(s.embeddingModelName) + '</code>' : '';
            el.className = 'status-line ' + (ok ? 'ok' : 'warn');
            el.innerHTML = ok
                ? '<span>向量模型已就绪' + (name ? '：' + name : '') + '</span>'
                    + '<span class="pill">' + esc(s.embeddingBaseUrl || s.chatBaseUrl || '复用对话地址') + '</span>'
                : '<span>向量模型未配置，上传与重建会失败。请先到「模型设置」的「向量模型」段，'
                    + '点击向量档案卡片上的<b>启用</b>。</span>';
            Console.setModelsWarn(!s.chatConfigured);
        }).catch(function (e) {
            el.className = 'status-line warn';
            el.textContent = '读取设置失败：' + e.message;
        });
    }

    function loadDocuments() {
        var box = document.getElementById('docList');
        Console.request('/admin/kb/documents').then(function (data) {
            var docs = data.documents || [];
            if (!docs.length) {
                box.className = 'hint';
                box.textContent = '暂无文档。请先在上方上传。';
                return;
            }
            var rows = docs.map(function (d) {
                return '<tr>'
                    + '<td><code>' + esc(d.docId || '') + '</code></td>'
                    + '<td>' + esc(d.fileName || '') + '</td>'
                    + '<td>' + domainLabel(d.domain) + '</td>'
                    + '<td>' + (d.chunks || 0) + '</td>'
                    + '<td>' + (d.exportPath
                        ? '<code class="path" title="点击复制">' + esc(d.exportPath) + '</code>'
                        : '<span class="hint">—</span>') + '</td>'
                    + '<td><button class="danger btn btn-danger" data-doc="' + esc(d.docId || '') + '">删除</button></td>'
                    + '</tr>';
            }).join('');
            box.className = '';
            box.innerHTML = '<table class="table"><thead><tr><th>docId</th><th>文件名</th><th>归属域</th><th>切片数</th><th>切片文件</th><th>操作</th>'
                + '</tr></thead><tbody>' + rows + '</tbody></table>';
            box.querySelectorAll('button[data-doc]').forEach(function (btn) {
                btn.addEventListener('click', function () { removeDoc(btn.getAttribute('data-doc')); });
            });
            box.querySelectorAll('code.path').forEach(function (code) {
                code.addEventListener('click', function () {
                    if (navigator.clipboard) { navigator.clipboard.writeText(code.textContent); }
                });
            });
        }).catch(function (e) {
            box.className = 'hint';
            box.textContent = '读取文档列表失败：' + e.message;
        });
    }

    function removeDoc(docId) {
        if (!confirm('确认删除该文档？删除后其文件名会被释放，可重新上传同名文档。')) return;
        Console.request('/admin/kb/documents/' + encodeURIComponent(docId), { method: 'DELETE' })
            .then(function () { loadDocuments(); })
            .catch(function (e) { alert('删除失败：' + e.message); });
    }

    function upload() {
        var input = document.getElementById('fileInput');
        var btn = document.getElementById('uploadBtn');
        var box = document.getElementById('uploadResult');
        if (!input.files || !input.files.length) {
            alert('请先选择文件');
            return;
        }
        var form = new FormData();
        form.append('file', input.files[0]);
        var replace = document.getElementById('replaceChk').checked;
        var domain = selectedDomain();
        var query = '/admin/kb/documents?replace=' + (replace ? 'true' : 'false');
        if (domain) { query += '&domain=' + encodeURIComponent(domain); }

        btn.disabled = true;
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
            }
        }).catch(function (e) {
            box.textContent = '请求失败: ' + e.message;
            box.className = 'result show err';
        }).then(function () {
            btn.disabled = false;
        });
    }

    function rebuild() {
        var btn = document.getElementById('rebuildBtn');
        var box = document.getElementById('rebuildResult');
        btn.disabled = true;
        box.className = 'result show';
        box.textContent = '重建中…（会建立真实 ES 连接并调用 embedding，耗时较长）';
        Console.request('/admin/kb/rebuild', { method: 'POST' }).then(function (data) {
            box.textContent = JSON.stringify(data, null, 2);
            box.className = 'result show ' + (data.success ? 'ok' : 'err');
            loadDocuments();
        }).catch(function (e) {
            box.textContent = '请求失败: ' + e.message;
            box.className = 'result show err';
        }).then(function () {
            btn.disabled = false;
        });
    }

    Console.renderSidebar('knowledge');
    document.getElementById('uploadBtn').addEventListener('click', upload);
    document.getElementById('rebuildBtn').addEventListener('click', rebuild);
    Console.ready(function () {
        loadEmbeddingStatus();
        loadDomainOptions();
        loadDocuments();
    });
})();

/* ==========================================================================
   domains 页脚本 · 与 domains.html 一一对应
   左侧域结构树（treeList，按 parentId 前端组树）+ 右侧选中域详情（detailBody）
   + 配置模型 / 配置 MCP 两个遮罩层。
   旧版是 domains.html 上一代 DOM（整页平铺卡片 domainList）的遗留，
   引用的 #domainList 已不存在（load 第一步就 TypeError，整页报废），本次按现 HTML 重写。
   ========================================================================== */
(function () {
    var esc = Console.escapeHtml;
    function setText(id, v) { var n = document.getElementById(id); if (n) { n.textContent = v; } }
    function setHtml(id, v) { var n = document.getElementById(id); if (n) { n.innerHTML = v; } }

    var DATA = null;             /* /admin/domains */
    var SELECTED = '';           /* 当前选中的域 */
    var EXPANDED = {};           /* 域名 -> true 表示展开子树 */
    var PROFILES = [];           /* /admin/model-profiles */
    var BINDINGS = {};           /* /admin/model-profiles 的域→档案绑定 */
    var MCP_BINDINGS = {};       /* /admin/mcp-bindings 的域→连接绑定 */
    var MCP_CONNS = [];          /* /admin/mcp-connections */
    var BIND_DOMAIN = '';
    var BIND_PICK = emptyPick();
    var GRACE_MS = null;         /* /admin/domains/storage 的 deleteGraceMs（软删宽限期） */
    /* /admin/domains 的 knowledgeBodies：能绑的知识体（每个体自己决定走几路）。
       域绑的是"一个体"，且**不继承**——父域绑了子域也不会跟着用。 */
    var KNOWLEDGE_BODIES = [];
    /* /admin/kb/stats：知识体维度的文档/切片数（异步补数，不参与域树首屏）。
       失败时 failed=true —— "取不到数"和"这里是 0"要分开说。 */
    var KB_STATS = null;
    var KIND_LABEL = { keyword: '关键字', vector: '向量' };
    var DOM_ICON = '<svg class="dom-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2 2 7l10 5 10-5-10-5z"/><path d="M2 12l10 5 10-5"/><path d="M2 17l10 5 10-5"/></svg>';
    var EP_LABEL = {
        chat: '对话', embedding: '向量', rerank: '重排',
        images: '生图', tts: '语音合成', asr: '语音识别', video: '视频'
    };
    var MOD_LABEL = {
        text: '文本', image: '图片', audio: '音频',
        video: '视频', file: '文件', embedding: '向量'
    };
    var BIND_FAMILIES = ['images', 'tts', 'asr', 'video'];
    var FAMILY_LABEL = {
        chat: '主模型（对话）', images: '生图', tts: '语音合成', asr: '语音识别', video: '生视频'
    };
    var SOURCE_TAG_CLS = { BUILTIN: 'src-builtin', MANUAL: 'src-manual', DERIVED: 'src-derived' };

    function num(n) { return (n == null) ? 0 : n; }

    function tagList(list, map) {
        return (list || []).map(function (k) {
            return '<span class="tag public">' + esc(map[k] || k) + '</span>';
        }).join('');
    }

    function aliasBase(modelName) {
        return (modelName || '').trim().replace(/\s+/g, '-') || 'model';
    }

    function dupNo(p) {
        var base = aliasBase(p.modelName);
        var alias = (p.alias || '').trim();
        if (alias === base) { return 0; }
        var m = alias.match(/-(\d+)$/);
        return (m && alias.slice(0, m.index) === base) ? parseInt(m[1], 10) : 0;
    }

    function modelLabel(p) {
        var n = dupNo(p);
        return (p.modelName || p.alias) + (n > 1 ? ' #' + n : '');
    }

    function sourceTag(d) {
        var s = String(d && d.source || '').toUpperCase();
        if (s === 'BUILTIN') {
            return '<span class="tag approval" title="根域：整棵域树的起点，不可删除">根域</span>';
        }
        if (s === 'MANUAL') {
            return '<span class="tag perm" title="人工创建，落盘保存，可删除">人工创建</span>';
        }
        return '<span class="tag" title="由工具的域声明派生：声明它的工具被断开后，域仍在，只是暂时没有工具">工具派生</span>';
    }

    function toolTag(t) {
        var cls = 'perm';
        var se = String(t.sideEffect || '').toLowerCase();
        if (se === 'read') { cls = 'read'; }
        else if (se === 'write') { cls = 'write'; }
        else if (se === 'destructive') { cls = 'destructive'; }
        var tip = [
            esc(t.category || ''),
            esc(t.provider || ''),
            t.requiresApproval ? '需人工确认' : ''
        ].filter(Boolean).join(' · ');
        return '<span class="tag ' + cls + '" title="' + tip + '">' + esc(t.name) + '</span>';
    }

    function candidatesFor(family) {
        var list = [];
        PROFILES.forEach(function (p) {
            if ((p.bindableFamilies || []).indexOf(family) < 0) { return; }
            list.push({
                alias: p.alias,
                label: modelLabel(p),
                note: p.baseUrl || '',
                /* 能不能看图：input 含 image。绑定弹窗里必须显式给出：
                   它决定"用户发图时图进谁的消息"，绑错了要等真发一张图才报 91001 */
                vision: (p.input || []).indexOf('image') >= 0,
                tags: tagList(p.endpoints, EP_LABEL) + tagList(p.input, MOD_LABEL)
            });
        });
        return list;
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

    function echoSwitch(d) {
        return '<label class="echo-switch" title="关闭后调用方只收到最终答复，不收到工具调用与执行结果">'
            + '<span class="tune-switch"><input type="checkbox" data-echo="' + esc(d.name) + '"'
            + (d.toolCallEcho === true ? ' checked' : '')
            + '><span class="tune-switch-track"><span class="tune-switch-knob"></span></span></span>'
            + '<span class="echo-switch-label">是否回写工具执行信息</span></label>';
    }

    function planSwitch(d) {
        return '<label class="echo-switch" title="打开后调用方在本轮第一个 TOKEN 前收到本轮执行计划（PLAN 事件）；规划本身做不做由全局开关 domainplane.planning.enabled 控制">'
            + '<span class="tune-switch"><input type="checkbox" data-plan="' + esc(d.name) + '"'
            + (d.planDisplay === true ? ' checked' : '')
            + '><span class="tune-switch-track"><span class="tune-switch-knob"></span></span></span>'
            + '<span class="echo-switch-label">是否展示执行计划</span></label>';
    }

    function resolveAlong(domain, family) {
        var chain = chainOf(domain);
        for (var i = chain.length - 1; i >= 0; i--) {
            var b = BINDINGS[chain[i]];
            var list = (b && b[family]) || [];
            if (list.length) { return { aliases: list, source: chain[i] }; }
        }
        return { aliases: [], source: null };
    }

    function primaryAliasOf(aliases) {
        var hit = (aliases || []).filter(function (a) {
            var p = PROFILES.filter(function (x) { return x.alias === a; })[0];
            return !!p && p.usable === true && p.bindable === true;
        })[0];
        return hit || null;
    }

    function shortName(domain) {
        var i = String(domain || '').lastIndexOf('.');
        return i < 0 ? String(domain || '') : domain.slice(i + 1);
    }

    /* ==================== 左侧域结构树 ==================== */
    function childrenMap() {
        var map = {};
        var names = {};
        ((DATA && DATA.domains) || []).forEach(function (d) { names[d.name] = true; });
        ((DATA && DATA.domains) || []).forEach(function (d) {
            var p = (d.parentId && names[d.parentId]) ? d.parentId : '';
            (map[p] || (map[p] = [])).push(d);
        });
        return map;
    }

    /* 当前选中域的完整路径：节点上只显示末段，短名字省下的宽度让给连线和标签。
       完整路径在树上方的路径条里补回，避免「sales 到底是哪个 sales」的歧义。 */
    function renderTreePath() {
        var box = document.getElementById('treePath');
        if (!box) { return; }
        if (!SELECTED) {
            box.innerHTML = '<span class="lead">当前路径</span><span class="seg">—</span>';
            return;
        }
        var segs = chainOf(SELECTED).map(shortName);
        box.innerHTML = '<span class="lead">当前路径</span>'
            + segs.map(function (s, i) {
                return (i ? '<span class="sep">›</span>' : '') + '<span class="seg">' + esc(s) + '</span>';
            }).join('');
    }

    function renderTree() {
        var box = document.getElementById('treeList');
        var domains = (DATA && DATA.domains) || [];
        if (!box) { return; }
        setText('domainCount', domains.length + ' 个域');
        renderTreePath();
        if (!domains.length) {
            box.innerHTML = '<div class="dp-domain-empty">当前没有任何域。根域 <span class="mono">default</span> 由服务端自建，'
                + '其余域用上方输入框新建，标识须为从根域出发的完整路径。</div>';
            return;
        }
        var map = childrenMap();
        box.innerHTML = '<div class="dp-tree">' + treeLevel(map[''] || [], map, 0, []) + '</div>';
        bindTreeEvents(box);
    }

    /* 扁平输出一列节点行：层级由 rails（竖干 + 肘形连线）画出来，不靠 padding 猜。
       prefix[k] = 第 k 层祖先后面还有没有兄弟节点；有则那一列继续画竖线，无则留空，
       这样最后一个分支下方不会拖出一条悬空的线。 */
    function treeLevel(list, map, depth, prefix) {
        var out = '';
        list.forEach(function (d, i) {
            var kids = map[d.name] || [];
            var hasKids = kids.length > 0;
            var expanded = EXPANDED[d.name] !== false;
            var isLast = i === list.length - 1;
            var rails = prefix.map(function (cont) {
                return '<i class="' + (cont ? 'line' : 'blank') + '"></i>';
            }).join('');
            if (depth > 0) { rails += '<i class="elbow ' + (isLast ? 'last' : 'more') + '"></i>'; }
            var tagCls = SOURCE_TAG_CLS[String(d.source || '').toUpperCase()] || 'src-derived';
            var srcLabel = d.source === 'BUILTIN' ? '根域' : (d.source === 'MANUAL' ? '人工' : '派生');
            var pending = d.pendingDelete ? ' <span class="tag destructive">待删除</span>' : '';
            var chev = hasKids
                ? '<button class="dp-tree-chev" data-chev="' + esc(d.name) + '" data-open="' + (expanded ? '1' : '0') + '"'
                    + ' title="' + (expanded ? '折叠' : '展开') + '">' + (expanded ? '▾' : '▸') + '</button>'
                : '<span class="dp-tree-chev leaf"></span>';
            out += '<div class="dp-tree-line"><div class="dp-tree-rail">' + rails + '</div>'
                + '<div class="dp-tree-row' + (d.name === SELECTED ? ' on' : '') + '" data-domain="' + esc(d.name) + '">'
                + chev
                + '<span class="dp-tree-joint ' + (d.callable ? 'on' : 'off') + '" title="'
                    + (d.callable ? '可调用单元' : '装配节点') + '"></span>'
                + '<span class="dp-tree-name" title="' + esc(d.name) + (d.pendingDelete ? '（待删除）' : '') + '">'
                    + esc(shortName(d.name)) + pending + '</span>'
                + '<span class="dp-tree-tag ' + tagCls + '">' + srcLabel + '</span>'
                + '<span class="dp-tree-count">' + num(d.toolCount) + '</span>'
                + '</div></div>';
            if (hasKids && expanded) {
                out += treeLevel(kids, map, depth + 1,
                    depth > 0 ? prefix.concat([!isLast]) : prefix.slice());
            }
        });
        return out;
    }

    function bindTreeEvents(box) {
        box.querySelectorAll('.dp-tree-row').forEach(function (row) {
            row.addEventListener('click', function () {
                selectDomain(row.getAttribute('data-domain'));
            });
        });
        box.querySelectorAll('.dp-tree-chev[data-chev]').forEach(function (btn) {
            btn.addEventListener('click', function (e) {
                e.stopPropagation();
                /* 按按钮自己记录的当前开合状态取反，别用 !EXPANDED[name]：
                   初始是 undefined，取反后还是 true，第一次点会「点了没反应」 */
                EXPANDED[btn.getAttribute('data-chev')] = btn.getAttribute('data-open') !== '1';
                renderTree();
            });
        });
    }

    function selectDomain(name) {
        SELECTED = name;
        renderTree();
        renderDetail();
    }

    function expandAncestors(name) {
        chainOf(name).slice(0, -1).forEach(function (seg) { EXPANDED[seg] = true; });
    }

    function currentDomain() {
        var domains = (DATA && DATA.domains) || [];
        for (var i = 0; i < domains.length; i++) {
            if (domains[i].name === SELECTED) { return domains[i]; }
        }
        return null;
    }

    /* ==================== 右侧详情卡 ==================== */
    /* ==================== 右侧详情卡 ==================== */
    function renderDetail() {
        var box = document.getElementById('detailBody');
        if (!box) { return; }
        var d = currentDomain();
        if (!d) {
            box.innerHTML = '<section class="card dp-panel"><div class="dp-panel-body">'
                + '<div class="dp-editor-empty"><span class="ee-icon">' + DOM_ICON + '</span>'
                + '<span>从左侧选一个域查看与配置。</span></div></div></section>';
            return;
        }
        var resolved = resolveAlong(d.name, 'chat');
        var primaryAlias = primaryAliasOf(resolved.aliases);
        var boundTags = resolved.aliases.length
            ? resolved.aliases.map(function (a) {
                var p = PROFILES.filter(function (x) { return x.alias === a; })[0];
                var label = p ? modelLabel(p) : a;
                var marks = '';
                if (p && (p.input || []).indexOf('image') >= 0) {
                    marks += ' <span class="tag read" title="输入模态含 image：能看图">可看图</span>';
                }
                if (a === primaryAlias) {
                    marks += ' <span class="tag perm" title="主模型：列表首个可用的档案，本轮对话由它回答">主</span>';
                }
                return '<span class="tag public mono">' + esc(label) + '</span>' + marks;
            }).join(' ')
                + (resolved.source === d.name ? '' : '<span class="card-note">继承自 ' + esc(resolved.source) + '</span>')
            : '<span class="tag write">无可调用模型</span>';
        var tools = (d.tools || []).map(toolTag).join('');
        var toolLine = tools || '<span class="perm-none">该域没有可见工具</span>';
        var mcpConns = (d.mcp && d.mcp.connections) || [];
        var mcpInherited = d.mcp && d.mcp.inheritedFrom;
        var mcpLine = (mcpConns.length || mcpInherited)
            ? '<div class="dp-meta-row"><span class="k">MCP 连接</span><div class="v">'
                + mcpConns.map(function (c) { return '<span class="tag public mono">' + esc(c) + '</span>'; }).join(' ')
                + (mcpInherited ? ' <span class="card-note">继承自 ' + esc(mcpInherited) + '</span>' : '')
                + '</div></div>'
            : '';
        var kb = d.knowledge || {};
        /* 主数字是本域可检索的量（本域 + 已绑知识体的上级域，与检索通道同口径）；
           体总量是基础设施视角，多个域共用一个体时只作为附注出现，免得被当成本域独有 */
        var stat = kb.body ? kbRetrievableOf(d.name) : null;
        var bodyStat = kb.body ? kbStatOf(kb.body) : null;
        var statNote = !kb.body ? ''
            : !KB_STATS ? '<span class="card-note">统计加载中…</span>'
            : KB_STATS.failed ? '<span class="card-note">统计不可用</span>'
            : '<span class="card-note">文档 ' + num(stat && stat.documents) + ' · 切片 ' + num(stat && stat.chunks)
                + (KB_STATS.stale ? '（旧数据）' : '') + '</span>'
                + (bodyStat && bodyStat.domainCount > 1
                    ? ' <span class="card-note">体共 ' + num(bodyStat.documents) + ' · ' + bodyStat.domainCount + ' 域共用</span>'
                    : '');
        var kbLine = '<div class="dp-meta-row"><span class="k">知识体</span><div class="v">' + kbSelect(d.name, kb) + statNote
            + (kb.bodyMissing ? ' <span class="tag destructive" title="这个域绑的知识体已被删，本站没有知识库">知识体已失效</span>' : '')
            + (kb.bodyDead ? ' <span class="tag destructive" title="知识体的连接全都不可用，检索工具已从这个域下线">知识体连接不可用</span>' : '')
            + (d.runningSessions ? ' <span class="tag approval">在飞会话 ' + num(d.runningSessions) + '</span>' : '')
            + '</div></div>';
        var parentLine = d.parentId
            ? '<div class="dp-meta-row"><span class="k">上级域</span><div class="v">'
                + '<button class="dp-jump" data-jump="' + esc(d.parentId) + '">' + esc(d.parentId) + '</button></div></div>'
            : '';
        var switches = d.callable
            ? echoSwitch(d) + planSwitch(d)
            : '<span class="card-note">装配节点不设入口开关</span>';
        var buttons = (d.hasChildren ? '' :
                '<button class="btn btn-sm btn-outline-secondary" data-callable="' + esc(d.name) + '" data-to="'
                    + (d.callable ? 'false' : 'true') + '">' + (d.callable ? '设为装配节点' : '设为可调用') + '</button>')
            + '<button class="btn btn-sm btn-outline-secondary" data-cfg="' + esc(d.name) + '">配置模型</button>'
            + '<button class="btn btn-sm btn-outline-secondary" data-mcpcfg="' + esc(d.name) + '">配置 MCP</button>'
            + (d.deletable && !d.pendingDelete
                ? '<button class="btn btn-sm btn-outline-danger" data-del="' + esc(d.name) + '">删除</button>' : '');
        box.innerHTML = '<section class="card dp-panel dp-detail-card">'
            + '<div class="dp-panel-head">'
            +   '<span class="dp-panel-icon">' + DOM_ICON + '</span>'
            +   '<div class="dp-panel-title"><h3 class="mono">' + esc(d.name) + '</h3>'
            +     '<div class="dp-panel-tags">' + sourceTag(d)
            +       (d.pendingDelete ? '<span class="tag destructive" title="处于软删除宽限期，到期后彻底清理">待删除</span>' : '')
            +       (d.callable
                        ? '<span class="tag read" title="可调用单元：能作为入口被调用">可调用</span>'
                        : '<span class="tag approval" title="装配节点：只把工具 / 提示词 / 模型 / 知识传给后代，不能直接当入口">装配节点</span>')
            +     '</div></div>'
            +   '<span class="spacer"></span>'
            +   '<div class="dp-head-actions">' + buttons + '</div>'
            + '</div>'
            + '<div class="dp-panel-body">'
            +   (d.pendingDelete ? pendingDeleteBox(d) : '')
            +   (d.hasChildren
                    ? '<p class="dp-kb-note">该域有 ' + num(d.directChildCount) + ' 个直接子域（全部后代 ' + num(d.childrenCount)
                        + ' 个），按"只有叶子域可作为可调用单元"的规则，它只能作装配节点，不能当入口。需要这一层入口请另建无子域的域。</p>'
                    : '')
            +   '<div class="dp-detail-strip">' + switches
            +     '<span class="spacer"></span><span class="card-note">工具 ' + num(d.toolCount)
            +       ' · 需确认 ' + num(d.approvalCount) + '</span></div>'
            +   '<div class="dp-meta-grid">' + parentLine
            +     '<div class="dp-meta-row"><span class="k">可调用模型</span><div class="v">' + boundTags + '</div></div>'
            +     capabilityLineOf(d.name) + mcpLine + kbLine
            +   '</div>'
            +   '<div class="dp-detail-section">'
            +     '<div class="dp-detail-section-head">可见工具 <span class="sub">含沿链继承</span></div>'
            +     '<div class="tool-tags">' + toolLine + '</div>'
            +   '</div>'
            + '</div></section>';
        bindDetailEvents(box);
    }

    function capabilityLineOf(domain) {
        var parts = [];
        BIND_FAMILIES.forEach(function (family) {
            var resolved = resolveAlong(domain, family);
            if (!resolved.aliases.length) { return; }
            var names = resolved.aliases.map(function (a) {
                var p = PROFILES.filter(function (x) { return x.alias === a; })[0];
                return '<span class="tag public mono">' + esc(p ? modelLabel(p) : a) + '</span>';
            }).join(' ');
            parts.push('<span class="k">' + esc(FAMILY_LABEL[family]) + '</span> ' + names
                + (resolved.source === domain ? '' : ' <span class="card-note">继承自 ' + esc(resolved.source) + '</span>'));
        });
        return parts.length
            ? '<div class="dp-meta-row"><span class="k">能力模型</span><div class="v">' + parts.join(' · ') + '</div></div>'
            : '';
    }

    function bindDetailEvents(box) {
        box.querySelectorAll('button[data-cfg]').forEach(function (b) {
            b.addEventListener('click', function () { openBind(b.getAttribute('data-cfg')); });
        });
        box.querySelectorAll('button[data-mcpcfg]').forEach(function (b) {
            b.addEventListener('click', function () { openMcpBind(b.getAttribute('data-mcpcfg')); });
        });
        box.querySelectorAll('button[data-callable]').forEach(function (b) {
            b.addEventListener('click', function () {
                toggleCallable(b.getAttribute('data-callable'), b.getAttribute('data-to') === 'true');
            });
        });
        box.querySelectorAll('button[data-del]').forEach(function (b) {
            b.addEventListener('click', function () { deleteDomain(b.getAttribute('data-del')); });
        });
        box.querySelectorAll('button[data-restore]').forEach(function (b) {
            b.addEventListener('click', function () { restoreDomain(b.getAttribute('data-restore'), b); });
        });
        box.querySelectorAll('button[data-purge]').forEach(function (b) {
            b.addEventListener('click', function () { purgeNow(b.getAttribute('data-purge'), b); });
        });
        box.querySelectorAll('input[data-echo]').forEach(function (input) {
            input.addEventListener('change', function () { saveEcho(input); });
        });
        box.querySelectorAll('input[data-plan]').forEach(function (input) {
            input.addEventListener('change', function () { savePlan(input); });
        });
        box.querySelectorAll('select[data-kb]').forEach(function (sel) {
            sel.addEventListener('change', function () { saveKb(sel); });
        });
        box.querySelectorAll('.dp-jump[data-jump]').forEach(function (b) {
            b.addEventListener('click', function () {
                var name = b.getAttribute('data-jump');
                expandAncestors(name);
                selectDomain(name);
            });
        });
    }

    function savePlan(input) {
        var domain = input.getAttribute('data-plan');
        var on = input.checked;
        input.disabled = true;
        Console.api.put('/admin/domains/' + encodeURIComponent(domain) + '/plan-display', { display: on })
            .catch(function (e) {
                input.checked = !on;
                Console.toast('保存失败：' + Console.sanitizeError(e.message), { type: 'error' });
            }).then(function () {
                input.disabled = false;
            });
    }

    function saveEcho(input) {
        var domain = input.getAttribute('data-echo');
        var on = input.checked;
        input.disabled = true;
        Console.api.put('/admin/domains/' + encodeURIComponent(domain) + '/tool-call-echo', { echo: on })
            .catch(function (e) {
                input.checked = !on;
                Console.toast('保存失败：' + Console.sanitizeError(e.message), { type: 'error' });
            }).then(function () {
                input.disabled = false;
            });
    }

    /* 知识体下拉：列出全部知识体。绑定只落盘不做校验，没配好的体也允许先绑——
       体此刻不可用时这个域暂时没有知识库，体在「知识库」页补好后自动生效。
       空 = 这个域没有知识库，**不去找父域借**——域链决定"查得到谁的数据"，
       不决定"用谁的知识体"。 */
    function kbSelect(domain, kb) {
        var bound = kb.body || '';
        var known = KNOWLEDGE_BODIES.some(function (b) { return b.id === bound; });
        var options = '';
        /* 绑的体已被删：补一条占位。不补的话下拉会静默显示成"不挂知识库"——
           用户看到的是"没配"，实际是"配了但被删了"，两种情况的处置完全不同 */
        if (bound && !known) {
            options += '<option value="' + esc(bound) + '" selected>'
                + esc(kb.bodyName || bound) + '（已删除）</option>';
        }
        options += '<option value=""' + (bound ? '' : ' selected') + '>（不挂知识库）</option>';
        KNOWLEDGE_BODIES.forEach(function (b) {
            /* 体的两路都在选项文案里：绑之前就能看出"这个体走几路"。
               空壳体（一路都没配）和连接坏了是两种状态，文案分开说 */
            var modes = (b.modes || []).map(function (k) { return KIND_LABEL[k] || k; }).join('+');
            var tail = modes || '未配连接';
            if (modes && b.usable === false) { tail += ' · 连接不可用'; }
            if (b.rawLabel && !(b.raw === 'local')) { tail += ' · 原文 ' + b.rawLabel; }
            options += '<option value="' + esc(b.id) + '"' + (b.id === bound ? ' selected' : '') + '>'
                + esc(b.name || b.id) + '（' + esc(tail) + '）</option>';
        });
        return '<select class="form-select dp-kb-select" data-kb="' + esc(domain) + '"'
            + ' title="' + esc(boundBodyTitle()) + '">' + options + '</select>';
    }

    /* 下拉的悬停说明：把"绑谁"说到数据层面（唯一名 + 冻结索引），而不是只给一个名字 */
    function boundBodyTitle() {
        return KNOWLEDGE_BODIES.map(function (b) {
            return (b.name || b.id) + ' · ' + (b.slug || b.id) + ' · ' + (b.index || '无索引')
                + (b.documents !== undefined ? ' · ' + num(b.documents) + ' 文档' : '');
        }).join('\n');
    }

    function saveKb(select) {
        var domain = select.getAttribute('data-kb');
        var body = select.value || null;
        select.disabled = true;
        Console.api.put('/admin/domains/' + encodeURIComponent(domain) + '/body', { body: body })
            .then(function () {
                Console.toast('「' + shortName(domain) + '」的知识体已更新', { type: 'success' });
            })
            .catch(function (e) {
                Console.toast('保存失败：' + Console.sanitizeError(e.message), { type: 'error' });
            })
            .then(function () {
                select.disabled = false;
                /* 无论成败都重拉：失败时下拉回到服务端的真实状态，不留在骗人的选项上 */
                load();
            });
    }

    /* ==================== 配置模型遮罩层 ==================== */
    function emptyPick() {
        return { chat: [], images: [], tts: [], asr: [], video: [] };
    }

    function openBind(domain) {
        BIND_DOMAIN = domain;
        BIND_PICK = emptyPick();
        var bound = BINDINGS[domain] || {};
        Object.keys(BIND_PICK).forEach(function (family) {
            BIND_PICK[family] = (bound[family] || []).slice();
        });
        document.getElementById('bindDomainName').textContent = domain;
        renderBindCards();
        document.getElementById('bindModal').hidden = false;
        document.body.style.overflow = 'hidden';
    }

    function closeBind() {
        document.getElementById('bindModal').hidden = true;
        document.body.style.overflow = '';
    }

    function renderBindCards() {
        var box = document.getElementById('bindCardList');
        var empty = document.getElementById('bindEmpty');
        if (!PROFILES.length) {
            box.innerHTML = '';
            empty.style.display = 'block';
            empty.textContent = '还没有任何模型档案，请先到「模型设置」添加。';
            return;
        }
        empty.style.display = 'none';
        var chatList = candidatesFor('chat');
        var visionNote = chatList.length && !chatList.some(function (c) { return c.vision; })
            ? '<div class="card-note" style="grid-column:1/-1;">当前没有可看图的档案（输入模态含 image），绑定后用户发图会报「没有可用的视觉模型」。模态读不出来的档案要在「模型设置」点一次「探测」补齐。</div>'
            : '';
        var html = sectionHead('chat', chatList.length) + visionNote + cardsHtml('chat', chatList);
        BIND_FAMILIES.forEach(function (family) {
            var list = candidatesFor(family);
            html += sectionHead(family, list.length) + cardsHtml(family, list);
        });
        box.innerHTML = html;
        box.querySelectorAll('div[data-pick]').forEach(function (card) {
            card.addEventListener('click', function () {
                var family = card.getAttribute('data-family');
                var alias = card.getAttribute('data-pick');
                var list = BIND_PICK[family] || (BIND_PICK[family] = []);
                var i = list.indexOf(alias);
                if (i >= 0) { list.splice(i, 1); } else { list.push(alias); }
                renderBindCards();
            });
        });
    }

    function sectionHead(family, count) {
        var note = count ? '' : ' · 没有可用于「' + esc(FAMILY_LABEL[family]) + '」的档案，到「模型设置」添加它、并点一次「探测」确认端点族';
        return '<div class="card-note" style="grid-column:1/-1; margin-top:8px;"><strong>' + esc(FAMILY_LABEL[family]) + '</strong>' + note + '</div>';
    }

    function cardsHtml(family, list) {
        var picked = BIND_PICK[family] || [];
        return list.map(function (c) {
            var on = picked.indexOf(c.alias) >= 0;
            return '<div class="pick-card' + (on ? ' on' : '') + '" data-pick="' + esc(c.alias) + '" data-family="' + esc(family) + '">'
                + '<div class="pc-name">' + esc(c.label) + (on ? ' ✓' : '')
                + (c.vision ? ' <span class="tag read" title="输入模态含 image：能看图">可看图</span>' : '')
                + '</div>'
                + (c.note ? '<div class="pc-note mono">' + esc(c.note) + '</div>' : '')
                + (c.tags ? '<div class="pc-tags">' + c.tags + '</div>' : '')
                + '</div>';
        }).join('');
    }

    function saveBind(pick) {
        var capabilities = {};
        BIND_FAMILIES.forEach(function (family) {
            capabilities[family] = pick[family] || [];
        });
        return Console.api.put('/admin/model-bindings/' + encodeURIComponent(BIND_DOMAIN), {
            aliases: pick.chat || [],
            capabilities: capabilities
        }).then(function () {
            closeBind();
            load();
        }).catch(function (e) {
            Console.toast('设置失败：' + e.message, { type: 'error' });
        });
    }

    /* ==================== 配置 MCP 遮罩层 ==================== */
    var MCP_BIND_DOMAIN = '';
    var MCP_BIND_PICK = [];

    function mcpStatusTag(c) {
        if (!c.enabled) {
            return '<span class="tag" title="已停用：不连接、不注册工具">已禁用</span>';
        }
        if (c.ready) { return '<span class="tag read">已就绪</span>'; }
        if (c.status === 'FAILED') {
            return '<span class="tag destructive" title="' + esc(c.lastError || '') + '">连接失败</span>';
        }
        return '<span class="tag">未连接</span>';
    }

    function openMcpBind(domain) {
        MCP_BIND_DOMAIN = domain;
        MCP_BIND_PICK = (MCP_BINDINGS[domain] || []).slice();
        document.getElementById('mcpBindDomainName').textContent = domain;
        renderMcpCards();
        document.getElementById('mcpBindModal').hidden = false;
        document.body.style.overflow = 'hidden';
    }

    function closeMcpBind() {
        document.getElementById('mcpBindModal').hidden = true;
        document.body.style.overflow = '';
    }

    function inheritedMcp(domain) {
        var chain = chainOf(domain);
        for (var i = chain.length - 2; i >= 0; i--) {
            var b = MCP_BINDINGS[chain[i]];
            if (b && b.length) { return { domain: chain[i], conns: b }; }
        }
        return null;
    }

    function renderMcpCards() {
        var box = document.getElementById('mcpBindCardList');
        var empty = document.getElementById('mcpBindEmpty');
        if (!MCP_CONNS.length) {
            box.innerHTML = '';
            empty.style.display = 'block';
            empty.textContent = '还没有任何 MCP 连接，请先到「MCP 连接」页添加。';
            return;
        }
        empty.style.display = 'none';
        var inherited = inheritedMcp(MCP_BIND_DOMAIN);
        box.innerHTML = MCP_CONNS.map(function (c) {
            var on = MCP_BIND_PICK.indexOf(c.name) >= 0;
            var viaAncestor = inherited && inherited.conns.indexOf(c.name) >= 0;
            return '<div class="pick-card' + (on ? ' on' : '') + '" data-mcpick="' + esc(c.name) + '">'
                + '<div class="pc-name">' + esc(c.name) + (on ? ' ✓' : '') + ' ' + mcpStatusTag(c) + '</div>'
                + (c.url ? '<div class="pc-note mono">' + esc(c.url) + '</div>' : '')
                + '<div class="pc-tags"><span class="tag">工具 ' + (c.ready ? c.toolCount : 0) + '</span>'
                + (viaAncestor ? '<span class="tag approval" title="已绑定在 ' + esc(inherited.domain) + '，其工具对本域可见，不必重复绑定">继承自 ' + esc(inherited.domain) + '</span>' : '')
                + '</div></div>';
        }).join('');
        box.querySelectorAll('div[data-mcpick]').forEach(function (card) {
            card.addEventListener('click', function () {
                var n = card.getAttribute('data-mcpick');
                var i = MCP_BIND_PICK.indexOf(n);
                if (i >= 0) { MCP_BIND_PICK.splice(i, 1); } else { MCP_BIND_PICK.push(n); }
                renderMcpCards();
            });
        });
    }

    function saveMcpBind(connections) {
        return Console.api.put('/admin/mcp-bindings/' + encodeURIComponent(MCP_BIND_DOMAIN), { connections: connections })
            .then(function () {
                closeMcpBind();
                load();
            }).catch(function (e) {
                Console.toast('设置失败：' + e.message, { type: 'error' });
            });
    }

    /* ==================== 新建 / 切换可调用 / 删除 ==================== */
    function createDomain() {
        var input = document.getElementById('newDomain');
        var id = (input.value || '').trim();
        if (!id) {
            Console.toast('请先填写域标识', { type: 'warning' });
            input.focus();
            return;
        }
        var callable = document.getElementById('newDomainCallable').checked;
        Console.api.post('/admin/domains', { id: id, callable: callable }).then(function () {
            input.value = '';
            EXPANDED[id] = true;
            SELECTED = id;
            load();
        }).catch(function (e) {
            Console.toast('创建失败：' + e.message, { type: 'error' });
        });
    }

    async function toggleCallable(id, callable) {
        var tip = callable
            ? '把 ' + id + ' 设为可调用单元？之后它可以作为入口被调用。'
            : '把 ' + id + ' 设为装配节点？之后它不能再作为入口，只给后代提供工具 / 提示词 / 模型 / 知识。';
        var ok = await Console.confirm(tip, {
            title: '切换可调用性',
            okText: '确认切换',
            danger: false
        });
        if (!ok) { return; }
        Console.api.put('/admin/domains/' + encodeURIComponent(id) + '/callable', { callable: callable })
            .then(function () { load(); })
            .catch(function (e) {
                Console.toast('切换失败：' + e.message, { type: 'error' });
            });
    }

    /* 删除 = 软删（宽限期），不是立即抹掉。弹窗必须把这三件事说清：
       什么时候真删、期间还允许什么、怎么后悔 —— 只写"确认删除"会让用户以为点下去就没了。 */
    async function deleteDomain(id) {
        var ok = await Console.confirm(
            '确认删除域 ' + id + '？\n\n'
            + '第一步（本操作）：标记为待删除，进入 ' + graceText(GRACE_MS || 0) + ' 宽限期\n'
            + '· 域及其子域都还在，知识库 / 提示词 / 模型绑定一个都不动\n'
            + '· 只是不再接受新会话（报 10011）；进行中的会话与待人工确认的断点照常收尾\n'
            + '· 宽限期内可以「撤销删除」，撤销后一切照旧\n\n'
            + '第二步（宽限期到点，自动执行，不可撤销）：\n'
            + '· 递归删除它的全部子域\n'
            + '· 知识体只解除绑定：原文、索引、切片全部保留，别的域（或以后新建的域）可以接着用\n'
            + '· 该域及其子域的提示词、模型绑定、工具声明记录会一并清理\n'
            + '· 绑定该域的调用方会在启动校验或首次调用时报「域不存在」',
            { title: '删除域', okText: '标记删除', danger: true }
        );
        if (!ok) { return; }
        Console.api.del('/admin/domains/' + encodeURIComponent(id)).then(function (r) {
            /* 软删后不把选中态清掉：留在这里，用户才看得到下面那条"待删除 + 撤销"说明条 */
            load();
            if (r && r.pendingDelete) {
                /* 用 success 而不是 warning：软删成功是一次正常完成的操作，
                   挂黄三角会被读成"删除没成功"，而它恰恰是"已进入宽限期、可反悔"的完成态 */
                Console.toast('已标记删除：' + graceText(GRACE_MS || 0) + '后自动彻底清除。'
                    + '这不是失败——期间可在右侧点「撤销删除」恢复。',
                    { type: 'success', title: '已进入删除宽限期', wide: true });
            }
        }).catch(function (e) {
            /* wide：服务端给的原因完整展示，别截成半句让人猜 */
            Console.toast('删除失败：' + e.message, { type: 'error', wide: true });
        });
    }

    /* 撤销软删：清标记即恢复，结构与数据从头到尾没动过 */
    function restoreDomain(id, btn) {
        Console.busy(btn, function () {
            return Console.api.post('/admin/domains/' + encodeURIComponent(id) + '/restore', {})
                .then(function () {
                    Console.toast('已撤销删除：' + id + ' 恢复正常', { type: 'success' });
                    load();
                }).catch(function (e) {
                    Console.toast('撤销失败：' + e.message, { type: 'error', wide: true });
                });
        }, '撤销中…');
    }

    /* 跳过宽限期立即硬删。强杀语义：不再等在飞会话收尾，也不可撤销 */
    async function purgeNow(id, btn) {
        var ok = await Console.confirm(
            '立即彻底删除域 ' + id + '？\n\n'
            + '· 跳过宽限期，现在就递归清除它和全部子域\n'
            + '· 提示词、模型绑定、工具声明一并清理；知识体只解绑，数据原样保留\n'
            + '· 不等在飞的会话收尾，也不可撤销（误删只能从历史版本库回滚）',
            { title: '立即彻底删除', okText: '彻底删除', danger: true }
        );
        if (!ok) { return; }
        Console.busy(btn, function () {
            return Console.api.del('/admin/domains/' + encodeURIComponent(id) + '?force=true')
                .then(function () {
                    if (SELECTED === id || chainOf(SELECTED).indexOf(id) >= 0) { SELECTED = ''; }
                    delete EXPANDED[id];
                    Console.toast('已彻底删除：' + id, { type: 'success' });
                    load();
                }).catch(function (e) {
                    /* 硬删任一步失败都是"抛异常中止、域未删除"（服务端顺序刻意如此），
                       这里必须明说没删成：否则用户会以为删了一半 */
                    Console.toast('彻底删除失败：' + e.message
                        + '\n（已中止，域与其数据均未删除）', { type: 'error', wide: true });
                });
        }, '删除中…');
    }

    /* 宽限期时长：删域是软删，不给这个数就无法在页面上说清"什么时候真删、还能不能后悔" */
    function loadGrace() {
        return Console.api.get('/admin/domains/storage').then(function (r) {
            GRACE_MS = (r && r.deleteGraceMs != null) ? r.deleteGraceMs : 0;
        }).catch(function () {
            GRACE_MS = null;      /* 取不到就不显示倒计时，只说"待删除" */
        });
    }

    /* 知识体维度的文档/切片数：唯一要碰存储的聚合，单独一个接口、单独一次异步补数。
       ES 不可达时服务端要烧一个 connectTimeout，所以绝不和域树绑在同一个 Promise 上 */
    function loadKbStats() {
        return Console.api.get('/admin/kb/stats').then(function (r) {
            KB_STATS = r || { bodies: [] };
        }).catch(function () {
            KB_STATS = { bodies: [], stale: true, failed: true };
        });
    }

    function kbStatOf(bodyId) {
        var list = (KB_STATS && KB_STATS.bodies) || [];
        for (var i = 0; i < list.length; i++) {
            if (list[i].id === bodyId) { return list[i]; }
        }
        return null;
    }

    /* 本域可检索的量（本域 + 已绑知识体的上级域）。缺省 = 0，不是"取不到" */
    function kbRetrievableOf(domain) {
        var m = (KB_STATS && KB_STATS.domains) || {};
        return m[domain] || null;
    }

    function graceText(ms) {
        if (!ms) { return '立即'; }
        if (ms >= 86400000) { return (ms / 86400000) + ' 天'; }
        if (ms >= 3600000) { return (ms / 3600000) + ' 小时'; }
        return Math.round(ms / 60000) + ' 分钟';
    }

    function leftText(ms) {
        if (ms == null) { return '—'; }
        if (ms <= 0) { return '已到期，随时清除'; }
        if (ms >= 3600000) { return Math.floor(ms / 3600000) + ' 小时 ' + Math.round((ms % 3600000) / 60000) + ' 分'; }
        if (ms >= 60000) { return Math.round(ms / 60000) + ' 分钟'; }
        return Math.round(ms / 1000) + ' 秒';
    }

    function remainingOf(d) {
        if (d.deletedAt == null || !GRACE_MS) { return null; }
        return d.deletedAt + GRACE_MS - Date.now();
    }

    /* 待删除说明条：软删不是"删了"，是"打了标记等宽限期"。
       页面上必须给出三件事：还剩多久、期间还允许什么、怎么后悔。 */
    function pendingDeleteBox(d) {
        var left = remainingOf(d);
        return '<div class="dp-pending-box">'
            + '<span class="tag destructive">待删除</span>'
            + '<span>已标记删除' + (GRACE_MS ? '，' + esc(graceText(GRACE_MS)) + '后彻底清除' : '')
            + (left == null ? '' : '（剩余 ' + esc(leftText(left)) + '）') + '。'
            + '期间它仍是完整的域：知识库 / 提示词 / 模型绑定都还在，只是<b>不再接受新会话</b>，'
            + '进行中的会话与等待人工确认的断点照常收尾。</span>'
            + '<span class="spacer"></span>'
            + '<button class="btn btn-sm btn-outline-primary" data-restore="' + esc(d.name) + '">撤销删除</button>'
            + '<button class="btn btn-sm btn-outline-danger" data-purge="' + esc(d.name) + '">立即彻底删除</button>'
            + '</div>';
    }
    function loadBindings() {
        return Console.api.get('/admin/model-profiles').then(function (r) {
            PROFILES = (r && r.profiles) || [];
            BINDINGS = (r && r.domainBindings) || {};
            return Promise.all([
                Console.api.get('/admin/mcp-bindings').then(function (m) {
                    MCP_BINDINGS = (m && m.domainBindings) || {};
                }).catch(function () { MCP_BINDINGS = {}; }),
                Console.api.get('/admin/mcp-connections').then(function (m) {
                    MCP_CONNS = (m && m.connections) || [];
                }).catch(function () { MCP_CONNS = []; })
            ]);
        }).catch(function (e) {
            Console.toast('模型档案加载失败：' + esc(e.message), { type: 'error' });
        });
    }

    function load() {
        /* 首次加载先给占位：域树与详情区是空的，白屏等着最不直观。
           之后的 load（增删改后的重拉）列表已有内容，交给顶部进度条即可。 */
        if (!DATA) {
            var treeBox = document.getElementById('treeList');
            if (treeBox) { treeBox.innerHTML = Console.loadingHtml('正在加载域空间…'); }
            var countEl = document.getElementById('domainCount');
            if (countEl) { countEl.textContent = '加载中…'; }
            var detailBox = document.getElementById('detailBody');
            if (detailBox) { detailBox.innerHTML = Console.loadingHtml('正在加载域详情…'); }
        }
        return Console.api.get('/admin/domains').then(function (r) {
            DATA = r || {};
            var domains = DATA.domains || [];
            KNOWLEDGE_BODIES = DATA.knowledgeBodies || [];
            /* 上次选中的域还在列表里就保留选中，否则落到第一个域 */
            if (!SELECTED || !domains.some(function (d) { return d.name === SELECTED; })) {
                SELECTED = domains.length ? domains[0].name : '';
            }
            if (SELECTED) { expandAncestors(SELECTED); }
            return Promise.all([loadBindings(), loadGrace()]);
        }).then(function () {
            renderTree();
            renderDetail();
            /* 知识库统计是异步补数：先渲染树与详情，数字到了再重绘一次。
               不能并进上面的 Promise.all —— ES 不可达时那个请求要烧一个 connectTimeout，
               整页会一起卡在"正在加载域空间…"上，这正是这次要修的东西。 */
            return loadKbStats().then(function () {
                renderTree();
                renderDetail();
            });
        }).catch(function (e) {
            var box = document.getElementById('treeList');
            if (box) {
                box.innerHTML = '<div class="dp-domain-empty" style="color:var(--red);">读取失败：' + esc(e.message) + '</div>';
            }
        });
    }

    /* ==================== 事件与入口 ==================== */
    document.getElementById('createDomainBtn').addEventListener('click', function () {
        Console.busy(this, createDomain);
    });
    document.getElementById('newDomain').addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { createDomain(); }
    });
    document.getElementById('refreshBtn').addEventListener('click', load);
    document.getElementById('treeExpandBtn').addEventListener('click', function () {
        ((DATA && DATA.domains) || []).forEach(function (d) { EXPANDED[d.name] = true; });
        renderTree();
    });
    document.getElementById('treeCollapseBtn').addEventListener('click', function () {
        ((DATA && DATA.domains) || []).forEach(function (d) { EXPANDED[d.name] = false; });
        renderTree();
    });
    document.getElementById('bindModalClose').addEventListener('click', closeBind);
    document.getElementById('bindSaveBtn').addEventListener('click', function () {
        Console.busy(this, function () { return saveBind(BIND_PICK); }, '保存中…');
    });
    document.getElementById('bindClearBtn').addEventListener('click', function () {
        Console.busy(this, function () { return saveBind(emptyPick()); }, '保存中…');
    });
    document.getElementById('bindModal').addEventListener('click', function (e) {
        if (e.target === document.getElementById('bindModal')) { closeBind(); }
    });
    document.getElementById('mcpBindModalClose').addEventListener('click', closeMcpBind);
    document.getElementById('mcpBindSaveBtn').addEventListener('click', function () {
        Console.busy(this, function () { return saveMcpBind(MCP_BIND_PICK.slice()); }, '保存中…');
    });
    document.getElementById('mcpBindClearBtn').addEventListener('click', function () {
        Console.busy(this, function () { return saveMcpBind([]); }, '保存中…');
    });
    document.getElementById('mcpBindModal').addEventListener('click', function (e) {
        if (e.target === document.getElementById('mcpBindModal')) { closeMcpBind(); }
    });
    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Escape') { return; }
        if (!document.getElementById('bindModal').hidden) { closeBind(); }
        if (!document.getElementById('mcpBindModal').hidden) { closeMcpBind(); }
    });

    Console.renderSidebar('domains');
    Console.ready(load);
})();

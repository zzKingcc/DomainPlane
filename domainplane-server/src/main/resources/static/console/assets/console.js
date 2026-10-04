/* ==========================================================================
   DomainPlane 管控台 · 共享脚本
   - 统一请求（固定 9527 / 同源相对路径两种模式）
   - 侧栏导航渲染（只写一遍，各页面复用）
   - 模型配置状态探测（驱动侧栏提示点）
   零依赖，直接用 <script src="assets/console.js"></script> 引入。
   ========================================================================== */
(function (global) {
    'use strict';

    /* ===== 服务端地址：一律同源相对路径 =====
       管控台必须从服务端自己的地址打开 —— 凭证是 HttpOnly Cookie，跨站时浏览器既不保存也不携带，
       表现成"登录成功却每个接口都 401"。
       这里刻意不写死端口：服务端换端口（或经反向代理）后，写死的地址会把整站接口打到另一个端口上，
       而页面自己完全看不出来哪里错了。 */
    function baseUrl() { return ''; }

    /* 是否通过 http/https 打开：用 file:// 直接预览 HTML 时，即使同源也发不出请求，
       这种访问方式要在页面上说清楚，而不是只留一行控制台报错 */
    var SERVED_BY_HTTP = /^https?:$/.test(location.protocol);

    /* ===== 统一请求 ===== */
    function request(path, options) {
        var url = baseUrl() + path;
        return fetch(url, options || {})
            .catch(function (e) {
                throw new Error('无法连接服务端 ' + (baseUrl() || location.origin) + '：' + e.message);
            })
            .then(function (resp) {
                return resp.text().then(function (raw) {
                    var data = null;
                    try {
                        data = JSON.parse(raw);
                    } catch (e) {
                        var isHtml = /^\s*</.test(raw);
                        throw new Error('接口 ' + url + ' 返回 ' + resp.status + '，且不是 JSON'
                            + (isHtml ? '（返回的是 HTML 页面，通常说明走到了其他服务的页面）' : '')
                            + '：' + sanitizeError(raw.slice(0, 120).replace(/\s+/g, ' ')));
                    }
                    /* 按响应体里的 code 分流，而不是按 HTTP 状态码：
                       AUTH_REQUIRED(10002) 才是"登录态失效"，而"账号或密码错误" AUTH_FAILED(10003)
                       的 HTTP 状态同样是 401。只看状态码的话，登录页输错密码会被直接判成会话失效、
                       刷新回登录页，用户永远看不到那句错误提示。
                       detail 优先于 error：前者是具体原因，后者是枚举里的通用文案。 */
                    if (data && data.code === 10002) {
                        redirectToLogin();
                        throw new Error('登录态已失效，正在跳转登录页…');
                    }
                    if (!resp.ok) {
                        throw new Error('接口 ' + url + ' 返回 ' + resp.status
                            + '：' + sanitizeError(data.detail || data.error || raw.slice(0, 120))
                            + (data.code ? '（' + data.code + '）' : ''));
                    }
                    return data;
                });
            });
    }

    function redirectToLogin() {
        var target = 'login.html';
        if (location.pathname.indexOf('/console/') < 0) {
            target = 'console/login.html';
        }
        location.replace(target);
    }

    function jsonRequest(path, method, body) {
        return request(path, {
            method: method || 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: body === undefined ? undefined : JSON.stringify(body)
        });
    }

    function escapeHtml(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    /* ===== 错误文案清洗 =====
       服务端的异常原文是排障的主要依据，不能一刀切抹掉；但其中三类片段是**凭据**，
       而这些异常会被原样渲染到页面上（ES / Redis 连接失败尤其常见）：
         · URL 里的 userinfo —— redis://user:pass@host:6379、http://user:pass@host
         · Windows 盘符绝对路径、常见 Unix 目录下的绝对路径（部署结构不该随错误一起公开）
       只替换命中的凭据片段，前后文原样保留 —— 排障仍看得到"连的是哪台、报的什么错"。 */
    var CRED_PATTERNS = [
        /* 协议://user:pass@host → 协议://***@host（host 保留：要靠它定位连的是哪台） */
        { re: /([a-zA-Z][a-zA-Z0-9+.-]*:\/\/)[^\/\s:@]+:[^\/\s@]+@/g,
          to: function (m, p1) { return p1 + '***@'; } },
        /* Windows 盘符绝对路径：C:\Users\... 、 D:/java/...
           ⚠️ 必须带前置边界：`redis://h:6379` 里的 `s:/` 会被裸正则当成盘符，
           把 host 和端口一起吃掉 —— 而 host 恰恰是排障要看的。 */
        { re: /(^|[\s"'（(])([A-Za-z]:[\\\/][^\s"'<>，。；、）)\]]{2,})/g,
          to: function (m, p1) { return p1 + '***'; } },
        /* 常见部署目录下的 Unix 绝对路径：/opt/... /var/... /home/... */
        { re: /(^|[\s"'（(])((?:\/(?:opt|usr\/local|var|etc|home|root|tmp|Users|data|app)\/[^\s"'<>，。；、）)\]]{2,}))/g,
          to: function (m, p1) { return p1 + '***'; } }
    ];

    function sanitizeError(s) {
        var out = String(s == null ? '' : s);
        if (!out) { return out; }
        CRED_PATTERNS.forEach(function (p) {
            out = out.replace(p.re, p.to);
        });
        return out;
    }

    /* ===== 导航定义：新增页面只需在这里加一项 + 落一个 html 文件 ===== */
    var ICONS = {
        overview: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/>'
            + '<rect x="14" y="14" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/>',
        models: '<rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6" rx="1"/>'
            + '<path d="M9 1v3M15 1v3M9 20v3M15 20v3M1 9h3M1 15h3M20 9h3M20 15h3"/>',
        infra: '<rect x="3" y="3" width="18" height="7" rx="2"/><rect x="3" y="14" width="18" height="7" rx="2"/>'
            + '<path d="M7 6.5h.01M7 17.5h.01"/>',
        knowledge: '<ellipse cx="12" cy="5" rx="9" ry="3"/>'
            + '<path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/><path d="M3 12c0 1.66 4 3 9 3s9-1.34 9-3"/>',
        /* 提示词设定：对话气泡 + 文本行（提示词是"写给模型的文字"） */
        prompts: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>'
            + '<path d="M8 9h8M8 12.5h5"/>',
        /* 域：三层堆叠 = 域的叠加空间（域是平行的命名空间，用"叠"而不是"箱"） */
        domains: '<path d="M12 2 2 7l10 5 10-5-10-5z"/><path d="M2 12l10 5 10-5"/><path d="M2 17l10 5 10-5"/>',
        /* 熔断工具调用：心跳线——实例还在不在跳，决定它的工具是否还能被调用 */
        instances: '<path d="M2 12h4l3-7 4 14 3-7h6"/>',
        account: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-6 8-6s8 2 8 6"/>'
    };

    var NAV = [
        { key: 'overview', label: '概览', href: 'overview.html', icon: 'overview' },
        { key: 'models', label: '模型设置', href: 'models.html', icon: 'models', warnAnchor: true },
        { key: 'infra', label: '存储配置', href: 'infra.html', icon: 'infra' },
        { key: 'domains', label: '域空间', href: 'domains.html', icon: 'domains' },
        { key: 'instances', label: '熔断工具调用', href: 'instances.html', icon: 'instances' },
        { key: 'prompts', label: '提示词设定', href: 'prompts.html', icon: 'prompts' },
        { key: 'knowledge', label: '知识库', href: 'knowledge.html', icon: 'knowledge' },
        { key: 'account', label: '账号', href: 'account.html', icon: 'account' }
    ];

    function renderSidebar(activeKey, rootId) {
        var root = document.getElementById(rootId || 'sidebar');
        if (!root) { return; }
        var items = NAV.map(function (n) {
            var cls = 'nav-item' + (n.key === activeKey ? ' active' : '');
            var dot = n.warnAnchor
                ? '<span class="dot-warn" data-role="models-warn" style="display:none;"'
                    + ' title="对话模型尚未配置完整"></span>'
                : '';
            return '<a class="' + cls + '" href="' + n.href + '"' + (n.key === activeKey ? ' aria-current="page"' : '') + '>'
                + '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"'
                + ' stroke-linecap="round" stroke-linejoin="round">' + ICONS[n.icon] + '</svg>'
                + '<span>' + n.label + '</span>' + dot + '</a>';
        }).join('');

        root.innerHTML =
            '<div class="brand">'
            +   '<h1>DomainPlane 管控台</h1>'
            +   '<div class="sub">服务端运维 / 管理员控制面</div>'
            + '</div>'
            + '<nav class="nav">' + items + '</nav>'
            + '<div class="sidebar-foot" id="sidebarFoot"></div>';

        renderAccountBox();
    }

    /* ===== 侧栏底部：当前账号 + 登出 =====
       登录态由服务端 /admin/session 判定（该端点免鉴权，否则连"要不要跳登录页"都判断不了）。 */
    function renderAccountBox() {
        var box = document.getElementById('sidebarFoot');
        if (!box) { return; }
        request('/admin/session').then(function (s) {
            box.innerHTML =
                '<div class="foot-user" title="' + escapeHtml(s.username || '') + '">'
                +   '<span class="dot-user"></span>'
                +   '<span class="foot-name">' + escapeHtml(s.username || '未登录') + '</span>'
                + '</div>'
                + '<a class="foot-link" href="account.html">账号与密码</a>'
                + '<button class="foot-out" id="logoutBtn" type="button">退出登录</button>';
            var btn = document.getElementById('logoutBtn');
            if (btn) {
                btn.addEventListener('click', function () {
                    jsonRequest('/admin/logout').then(redirectToLogin).catch(redirectToLogin);
                });
            }
        }).catch(function () {
            box.innerHTML = '<a class="foot-link" href="login.html">去登录</a>';
        });
    }

    /* ===== 登录守卫：每个页面加载时校验一次，未登录直接跳登录页 =====
       放在脚本加载期而不是某个页面里，避免"新增页面忘了加守卫"。login.html 通过
       window.CONSOLE_NO_AUTH_GUARD = true 显式关掉。

       全站只查一次（sessionPromise 共享），因为页面自己的数据加载（ready）也要用同一个结果。 */
    var sessionPromise = null;

    function checkSession() {
        if (!sessionPromise) {
            sessionPromise = request('/admin/session').catch(function () { return null; });
        }
        return sessionPromise;
    }

    function notLoggedIn(s) {
        return s && (!s.initialized || !s.authenticated);
    }

    function guardAuth() {
        if (global.CONSOLE_NO_AUTH_GUARD) { return; }
        checkSession().then(function (s) {
            if (notLoggedIn(s)) { redirectToLogin(); }
        });
    }

    /* ===== 页面数据加载的统一入口 =====
       ⚠️ 为什么必须有这一层：页面内联脚本在**解析期**就执行完了，而守卫挂在 DOMContentLoaded 上，
       两者是同一个 tick 里的先后关系。若页面直接发请求，未登录访问必然先打一轮受保护接口
       （每个 401 + 一条 WARN「未携带凭证」）才轮到守卫跳登录页。
       那批 401 是"守卫来晚一步"造成的噪音，会被误读成服务端鉴权有问题。

       用法：把页面里所有**首次加载**的请求放进回调，例如
           Console.ready(load);                      // 单个
           Console.ready(function () { loadA(); loadB(); });   // 多个 */
    function ready(fn) {
        warnIfCrossOrigin();
        if (global.CONSOLE_NO_AUTH_GUARD) { fn(); return; }
        checkSession().then(function (s) {
            if (notLoggedIn(s)) { redirectToLogin(); return; }
            fn();
        });
    }

    /* ===== 访问方式不对：显式提示，别让"登录成功却一直 401"被当成服务端 bug =====
       凭证受浏览器同源策略约束：页面不是从服务端地址打开时（例如用 file:// 直接预览 HTML、
       或托管在别的站点上），请求要么发不出去、要么不携带 Cookie ——
       于是登录接口**成功**、而每个受保护接口都回 401「未携带凭证」。
       这是访问方式问题，不是凭证逻辑问题，所以要在页面上说清而不是只留一行日志。 */
    function warnIfCrossOrigin() {
        if (SERVED_BY_HTTP || document.getElementById('originWarn')) { return; }
        var box = document.createElement('div');
        box.id = 'originWarn';
        box.className = 'origin-warn';
        box.innerHTML = '<b>当前页面不是通过 http/https 打开的（<code>'
            + escapeHtml(location.protocol) + '</code>），接口请求发不出去。</b><br>'
            + '管控台的凭证是 HttpOnly Cookie，必须同源才能带上，'
            + '因此只能用服务端自己的地址打开管控台：'
            + '<code>http://&lt;服务端主机&gt;:&lt;端口&gt;/admin.html</code>。';
        var main = document.querySelector('.main');
        if (main) {
            main.insertBefore(box, main.firstChild);
        } else {
            document.body.insertBefore(box, document.body.firstChild);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', guardAuth);
    } else {
        guardAuth();
    }

    /* ===== 对话模型是否就绪 → 侧栏提示点（各页面共用同一判据） ===== */
    var lastStatus = null;

    function refreshModelsWarn() {
        return request('/admin/settings').then(function (s) {
            lastStatus = s;
            setModelsWarn(!s.chatConfigured);
            return s;
        }).catch(function (e) {
            setModelsWarn(true);
            throw e;
        });
    }

    function setModelsWarn(on) {
        var dots = document.querySelectorAll('[data-role="models-warn"]');
        for (var i = 0; i < dots.length; i++) {
            dots[i].style.display = on ? 'block' : 'none';
        }
    }

    function lastSettings() { return lastStatus; }

    global.Console = {
        baseUrl: baseUrl,
        sameOrigin: function () { return SERVED_BY_HTTP; },
        request: request,
        jsonRequest: jsonRequest,
        escapeHtml: escapeHtml,
        sanitizeError: sanitizeError,
        renderSidebar: renderSidebar,
        ready: ready,
        warnIfCrossOrigin: warnIfCrossOrigin,
        refreshModelsWarn: refreshModelsWarn,
        setModelsWarn: setModelsWarn,
        lastSettings: lastSettings,
        redirectToLogin: redirectToLogin
    };
})(window);

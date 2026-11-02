/* ==========================================================================
   P1 · 登录页脚本
   自 login.html 的内联 <script> 机械平移，逻辑一行未改（只套了 IIFE 避免污染全局）。
   平移批次 1/12：login（135 行，最小页，用来验证构建链路）
   ========================================================================== */
(function () {
    var mode = 'login';           /* login | init | blocked */
    var resultEl = document.getElementById('result');
    var bannerEl = document.getElementById('banner');
    var noteEl = document.getElementById('note');
    var submitBtn = document.getElementById('submitBtn');

    function showResult(msg, ok) {
        resultEl.textContent = msg;
        resultEl.className = 'result show ' + (ok ? 'ok' : 'err');
    }

    function showBanner(msg, kind) {
        bannerEl.textContent = msg;
        bannerEl.className = 'banner ' + (kind || 'info');
        bannerEl.style.display = 'block';
    }

    function enterConsole() {
        location.replace('overview.html');
    }

    function applyMode(s) {
        if (s.initialized && s.authenticated) {
            enterConsole();
            return;
        }
        if (s.corrupted) {
            /* 账号文件存在但读不出来：服务端拒绝一切受保护请求，初始化入口也已关闭
               （账号数据就在文件里，允许重新初始化等于允许接管）。这里只告知，不给任何入口。
               ⚠️ 这一页是<b>未鉴权</b>的：不能出现任何凭证明文，也不能出现"删某个文件、重启后会怎样"
               这类运维内部操作指引，登录页是公开面，写在这里等于把它送给任何能打开浏览器的人。 */
            mode = 'blocked';
            document.getElementById('modeSub').textContent = '账号文件损坏，服务无法鉴权';
            document.getElementById('confirmField').style.display = 'none';
            document.getElementById('password').disabled = true;
            document.getElementById('password2').disabled = true;
            submitBtn.disabled = true;
            submitBtn.textContent = '不可用';
            noteEl.textContent = '服务端读不出账号文件，因此拒绝一切受保护请求，初始化入口也已关闭。'
                + '请联系部署方处理后重启服务。';
            return;
        }
        if (!s.initialized) {
            /* 无账号 = 连种子都没有：开放初始化入口（有账号后本入口永久关闭） */
            mode = 'init';
            document.getElementById('modeSub').textContent = '首次初始化：设置管理员账号';
            document.getElementById('confirmField').style.display = 'block';
            submitBtn.textContent = '完成初始化';
            noteEl.textContent = '当前服务端还没有账号，这里设置的账号密码将成为唯一的管控台账号。'
                + '本入口只在无账号时开放，之后永久关闭，也不存在注册与找回。';
        } else {
            mode = 'login';
            document.getElementById('modeSub').textContent = '登录以进入管控台';
            document.getElementById('confirmField').style.display = 'none';
            submitBtn.textContent = '登录';
            noteEl.textContent = '本产品不提供注册与密码找回。忘记密码请联系部署方处理。';
            if (s.defaultCredential) {
                /* 只说"还是初始密码"，<b>不说初始密码是什么</b>。这一页未鉴权，
                   把凭据写在页面上等于公开。初始值由部署方从部署文档/种子文件自行掌握。 */
                showBanner('当前仍在使用初始密码，建议尽快修改。是否修改由部署方决定。', 'warn');
            }
        }
        document.getElementById('username').value = s.username || '';
        document.getElementById('password').focus();
    }

    document.getElementById('loginForm').addEventListener('submit', function () {
        var username = document.getElementById('username').value.trim();
        var password = document.getElementById('password').value;
        var password2 = document.getElementById('password2').value;

        if (mode === 'blocked') { return; }
        if (!username) { showResult('账号不能为空', false); return; }
        if (!password) { showResult('密码不能为空', false); return; }
        if (mode === 'init' && password !== password2) {
            showResult('两次输入的密码不一致', false);
            return;
        }

        submitBtn.disabled = true;
        submitBtn.classList.add('btn-loading');
        showResult(mode === 'init' ? '正在初始化…' : '正在登录…', true);

        var path = mode === 'init' ? '/admin/init' : '/admin/login';
        /* P2：/admin/login 与 /admin/init 返回 code:0 + message。
           失败（含密码错误）走 reject，message 已清洗。 */
        Console.api.post(path, { username: username, password: password })
            .then(function (r) {
                if (r && r.defaultCredential) {
                    try { sessionStorage.setItem('domainplane.defaultCredential', '1'); } catch (e) { }
                }
                showResult((r && r.message) || '登录成功，正在进入管控台…', true);
                /* ⚠️ 服务端说"成功"不等于浏览器把 Cookie 存下了：跨站响应里的 Set-Cookie 会被直接丢弃，
                   于是出现"登录成功 + 随后每个接口都 401「未携带凭证」"这种最容易被误判成服务端 bug 的现象。
                   这里用 /admin/session 复核一次，把问题在源头点破，而不是跳进一个必然 401 的页面。 */
                /* P2：/admin/session 是 A 类（code:0） */
                return Console.api.get('/admin/session').then(function (s) {
                    if (s && s.authenticated) {
                        enterConsole();
                        return;
                    }
                    submitBtn.disabled = false;
                    submitBtn.classList.remove('btn-loading');
                    Console.warnIfCrossOrigin();
                    showResult('账号密码校验通过，但浏览器没有保存登录凭证（Cookie）。'
                        + '管控台必须从服务端自己的地址打开：'
                        + Console.baseUrl() + '/admin.html', false);
                });
            })
            .catch(function (e) {
                submitBtn.disabled = false;
                submitBtn.classList.remove('btn-loading');
                showResult(e.message, false);
            });
    });

    /* 不同源打开时先在页面上说明（此时连登录都不会生效），避免只留一行服务端日志 */
    Console.warnIfCrossOrigin();

    Console.api.get('/admin/session').then(applyMode).catch(function (e) {
        document.getElementById('modeSub').textContent = '无法连接服务端';
        showResult(e.message, false);
    });
})();

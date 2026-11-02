/* ==========================================================================
   account 页脚本 · 与 account.html 一一对应
   总览四卡 + 账号卡 + 登录记录定高表 + 修改密码遮罩层。

   数据只有一份（/admin/session），四卡与账号卡刻意不摆同一项：账号名只出现在
   账号卡，统计行只放会随时间变的四件事（密码状态 / 最近登录 / 来源 / 记录数）。
   时间是 ISO-8601（Instant.now().toString()），原样显示太机器，统一格式化成本地
   时间；解析不了就退回原串，"看不懂"也比"显示成 — 然后以为没登录"好。
   ========================================================================== */
(function () {
    var esc = Console.escapeHtml;

    /* ===== 时间 ===== */
    function pad(n) {
        return n < 10 ? '0' + n : '' + n;
    }

    function toDate(iso) {
        if (!iso) { return null; }
        var d = new Date(iso);
        return isNaN(d.getTime()) ? null : d;
    }

    function fmtFull(iso) {
        var d = toDate(iso);
        if (!d) { return iso ? String(iso) : '—'; }
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
            + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
    }

    function fmtShort(iso) {
        var d = toDate(iso);
        if (!d) { return iso ? String(iso) : '—'; }
        return pad(d.getMonth() + 1) + '-' + pad(d.getDate())
            + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    /* ===== 小工具 ===== */
    function set(id, text) {
        var el = document.getElementById(id);
        if (el) { el.textContent = text; }
    }

    /* 统计卡的值：占位 "—" 降权成灰细字（22px 粗体破折号会读成一条横线），
       有内容时补 title —— 22px 一行放不下就省略号，鼠标悬停能看全 */
    function setStat(id, text) {
        var el = document.getElementById(id);
        if (!el) { return; }
        el.textContent = text;
        el.classList.toggle('mute', text === '—');
        if (text === '—') {
            el.removeAttribute('title');
        } else {
            el.title = text;
        }
    }

    function setHtml(id, html) {
        var el = document.getElementById(id);
        if (el) { el.innerHTML = html; }
    }

    function chip(text, tone) {
        return '<span class="dp-chip ' + tone + '">' + esc(text) + '</span>';
    }

    /* ===== 登录记录表：空态 / 加载态都画在 tbody 里，卡片高度恒定 ===== */
    var COLS = 2;

    function placeholderRow(inner) {
        return '<tr class="dp-doc-empty"><td colspan="' + COLS + '"><div>' + inner + '</div></td></tr>';
    }

    var EMPTY_ROW = placeholderRow('<div class="dp-doc-empty-icon">'
        + '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
        + ' stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">'
        + '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg></div>暂无登录记录');

    function renderLogin(records) {
        var body = document.getElementById('loginBody');
        if (!body) { return; }
        set('loginCount', records.length ? '共 ' + records.length + ' 条' : '0 条');
        if (!records.length) {
            body.innerHTML = EMPTY_ROW;
            return;
        }
        body.innerHTML = records.map(function (r) {
            return '<tr><td>' + esc(fmtFull(r.at)) + '</td>'
                + '<td class="mono">' + esc(r.from || '—') + '</td></tr>';
        }).join('');
    }

    /* ===== 账号卡 + 四卡 ===== */
    function renderAccount(s) {
        var records = s.loginRecords || [];
        var last = records.length ? records[0] : null;   // 记录新的在前
        var lastAt = s.lastLoginAt || (last && last.at) || '';
        var lastFrom = s.lastLoginFrom || (last && last.from) || '';

        var pwd = document.getElementById('statPwd');
        if (pwd) {
            pwd.textContent = s.defaultCredential ? '初始密码' : '已修改';
            pwd.className = 'dp-stat-value' + (s.defaultCredential ? ' warn' : '');
        }
        setStat('statLast', lastAt ? fmtShort(lastAt) : '—');
        setStat('statFrom', lastFrom || '—');
        set('statRecords', String(records.length));

        var name = s.username || '—';
        set('acName', name);
        set('acAvatar', name.charAt(0));
        set('acFile', s.accountFile ? '账号文件 ' + s.accountFile : '账号文件 —');

        var chips = s.authenticated ? chip('已登录', 'ok') : chip('未登录', 'err');
        if (s.corrupted) {
            chips += chip('账号文件损坏', 'err');
        } else if (!s.initialized) {
            chips += chip('未初始化', 'warn');
        }
        setHtml('acChips', chips);

        renderLogin(records);
    }

    function loadSession() {
        var body = document.getElementById('loginBody');
        if (body) { body.innerHTML = placeholderRow('加载中…'); }
        /* /admin/session 是 A 类信封，api.get 直接返回 data */
        return Console.api.get('/admin/session').then(function (s) {
            renderAccount(s);
        }).catch(function (e) {
            set('loginCount', '—');
            if (body) { body.innerHTML = placeholderRow('读取账号信息失败：' + esc(e.message)); }
            Console.toast(e.message, { type: 'error' });
        });
    }

    /* ===== 修改密码遮罩层 ===== */
    var modal = document.getElementById('pwdModal');

    function openModal() {
        if (modal) {
            modal.hidden = false;
            var first = document.getElementById('oldPassword');
            if (first) { first.focus(); }
        }
    }

    function closeModal() {
        if (modal) { modal.hidden = true; }
    }

    var openBtn = document.getElementById('openPwdBtn');
    if (openBtn) { openBtn.addEventListener('click', openModal); }
    var closeBtn = document.getElementById('pwdModalClose');
    if (closeBtn) { closeBtn.addEventListener('click', closeModal); }
    if (modal) {
        /* 点遮罩空白处关闭；点面板内不关 */
        modal.addEventListener('click', function (e) {
            if (e.target === modal) { closeModal(); }
        });
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && !modal.hidden) { closeModal(); }
        });
    }

    function clearPwd() {
        ['oldPassword', 'newPassword', 'newPassword2'].forEach(function (id) {
            var input = document.getElementById(id);
            if (input) { input.value = ''; }
        });
        var el = document.getElementById('pwdResult');
        if (el) {
            el.className = 'result';
            el.textContent = '';
        }
    }

    var clearBtn = document.getElementById('clearPwdBtn');
    if (clearBtn) { clearBtn.addEventListener('click', clearPwd); }

    var saveBtn = document.getElementById('savePwdBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var oldPassword = document.getElementById('oldPassword').value;
            var newPassword = document.getElementById('newPassword').value;
            var again = document.getElementById('newPassword2').value;
            var el = document.getElementById('pwdResult');
            if (!oldPassword) {
                el.textContent = '请填写当前密码';
                el.className = 'result show err';
                return;
            }
            if (!newPassword) {
                el.textContent = '请填写新密码';
                el.className = 'result show err';
                return;
            }
            if (newPassword !== again) {
                el.textContent = '两次输入的新密码不一致';
                el.className = 'result show err';
                return;
            }
            el.textContent = '正在保存…';
            el.className = 'result show ok';
            saveBtn.disabled = true;
            saveBtn.classList.add('btn-loading');
            Console.api.post('/admin/password', { oldPassword: oldPassword, newPassword: newPassword })
                .then(function (r) {
                    el.textContent = (r && r.message) || '密码已修改，请重新登录';
                    el.className = 'result show ok';
                    Console.toast(el.textContent, { type: 'success' });
                    /* 改密码即踢人：签名密钥由密码哈希派生，本次登录也会被踢 */
                    setTimeout(function () {
                        location.replace('login.html');
                    }, 1600);
                }).catch(function (e) {
                    el.textContent = e.message;
                    el.className = 'result show err';
                }).then(function () {
                    saveBtn.disabled = false;
                    saveBtn.classList.remove('btn-loading');
                });
        });
    }

    Console.renderSidebar('account');
    Console.ready(loadSession);
})();

/* ==========================================================================
   P1 · account 页脚本
   自 account.html 的内联 <script> 机械平移（1 块合并），逻辑未改。
   ========================================================================== */
(function () {
var esc = Console.escapeHtml;

    function loadSession() {
        Console.request('/admin/session').then(function (s) {
            document.getElementById('acName').textContent = s.username || '—';
            document.getElementById('acLastAt').textContent = s.lastLoginAt || '—';
            document.getElementById('acLastFrom').textContent = s.lastLoginFrom || '—';
            document.getElementById('acPwd').textContent =
                s.defaultCredential ? '仍是初始密码' : '已修改';
        }).catch(function (e) {
            var el = document.getElementById('pwdResult');
            el.textContent = e.message;
            el.className = 'result show err';
        });
    }

    document.getElementById('clearPwdBtn').addEventListener('click', function () {
        ['oldPassword', 'newPassword', 'newPassword2'].forEach(function (id) {
            document.getElementById(id).value = '';
        });
        var el = document.getElementById('pwdResult');
        el.className = 'result';
        el.textContent = '';
    });

    document.getElementById('savePwdBtn').addEventListener('click', function () {
        var oldPassword = document.getElementById('oldPassword').value;
        var newPassword = document.getElementById('newPassword').value;
        var again = document.getElementById('newPassword2').value;
        var el = document.getElementById('pwdResult');

        if (!oldPassword) { el.textContent = '请填写当前密码'; el.className = 'result show err'; return; }
        if (!newPassword) { el.textContent = '请填写新密码'; el.className = 'result show err'; return; }
        if (newPassword !== again) { el.textContent = '两次输入的新密码不一致'; el.className = 'result show err'; return; }

        el.textContent = '正在保存…';
        el.className = 'result show ok';

        Console.jsonRequest('/admin/password', 'POST', {
            oldPassword: oldPassword,
            newPassword: newPassword
        }).then(function (r) {
            el.textContent = (r && r.message) || '密码已修改，请重新登录';
            el.className = 'result show ok';
            setTimeout(function () { location.replace('login.html'); }, 1600);
        }).catch(function (e) {
            el.textContent = e.message;
            el.className = 'result show err';
        });
    });

    Console.renderSidebar('account');
    Console.ready(loadSession);
})();

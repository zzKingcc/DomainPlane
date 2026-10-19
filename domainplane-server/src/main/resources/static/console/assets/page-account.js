(() => {
  // domainplane-server/frontend/src/pages/account.js
  (function() {
    var esc = Console.escapeHtml;
    function loadSession() {
      Console.request("/admin/session").then(function(s) {
        document.getElementById("acName").textContent = s.username || "\u2014";
        document.getElementById("acLastAt").textContent = s.lastLoginAt || "\u2014";
        document.getElementById("acLastFrom").textContent = s.lastLoginFrom || "\u2014";
        document.getElementById("acPwd").textContent = s.defaultCredential ? "\u4ECD\u662F\u521D\u59CB\u5BC6\u7801" : "\u5DF2\u4FEE\u6539";
      }).catch(function(e) {
        var el = document.getElementById("pwdResult");
        el.textContent = e.message;
        el.className = "result show err";
      });
    }
    document.getElementById("clearPwdBtn").addEventListener("click", function() {
      ["oldPassword", "newPassword", "newPassword2"].forEach(function(id) {
        document.getElementById(id).value = "";
      });
      var el = document.getElementById("pwdResult");
      el.className = "result";
      el.textContent = "";
    });
    document.getElementById("savePwdBtn").addEventListener("click", function() {
      var oldPassword = document.getElementById("oldPassword").value;
      var newPassword = document.getElementById("newPassword").value;
      var again = document.getElementById("newPassword2").value;
      var el = document.getElementById("pwdResult");
      if (!oldPassword) {
        el.textContent = "\u8BF7\u586B\u5199\u5F53\u524D\u5BC6\u7801";
        el.className = "result show err";
        return;
      }
      if (!newPassword) {
        el.textContent = "\u8BF7\u586B\u5199\u65B0\u5BC6\u7801";
        el.className = "result show err";
        return;
      }
      if (newPassword !== again) {
        el.textContent = "\u4E24\u6B21\u8F93\u5165\u7684\u65B0\u5BC6\u7801\u4E0D\u4E00\u81F4";
        el.className = "result show err";
        return;
      }
      el.textContent = "\u6B63\u5728\u4FDD\u5B58\u2026";
      el.className = "result show ok";
      Console.jsonRequest("/admin/password", "POST", {
        oldPassword,
        newPassword
      }).then(function(r) {
        el.textContent = r && r.message || "\u5BC6\u7801\u5DF2\u4FEE\u6539\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55";
        el.className = "result show ok";
        setTimeout(function() {
          location.replace("login.html");
        }, 1600);
      }).catch(function(e) {
        el.textContent = e.message;
        el.className = "result show err";
      });
    });
    Console.renderSidebar("account");
    Console.ready(loadSession);
  })();
})();

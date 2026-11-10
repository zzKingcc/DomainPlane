(() => {
  // domainplane-server/frontend/src/pages/login.js
  (function() {
    var mode = "login";
    var resultEl = document.getElementById("result");
    var bannerEl = document.getElementById("banner");
    var noteEl = document.getElementById("note");
    var submitBtn = document.getElementById("submitBtn");
    function showResult(msg, ok) {
      resultEl.textContent = msg;
      resultEl.className = "result show " + (ok ? "ok" : "err");
    }
    function showBanner(msg, kind) {
      bannerEl.textContent = msg;
      bannerEl.className = "banner " + (kind || "info");
      bannerEl.style.display = "block";
    }
    function enterConsole() {
      location.replace("overview.html");
    }
    function applyMode(s) {
      if (s.initialized && s.authenticated) {
        enterConsole();
        return;
      }
      if (s.corrupted) {
        mode = "blocked";
        document.getElementById("modeSub").textContent = "\u8D26\u53F7\u6587\u4EF6\u635F\u574F\uFF0C\u670D\u52A1\u65E0\u6CD5\u9274\u6743";
        document.getElementById("confirmField").style.display = "none";
        document.getElementById("password").disabled = true;
        document.getElementById("password2").disabled = true;
        submitBtn.disabled = true;
        submitBtn.textContent = "\u4E0D\u53EF\u7528";
        noteEl.textContent = "\u670D\u52A1\u7AEF\u8BFB\u4E0D\u51FA\u8D26\u53F7\u6587\u4EF6\uFF0C\u56E0\u6B64\u62D2\u7EDD\u4E00\u5207\u53D7\u4FDD\u62A4\u8BF7\u6C42\uFF0C\u521D\u59CB\u5316\u5165\u53E3\u4E5F\u5DF2\u5173\u95ED\u3002\u8BF7\u8054\u7CFB\u90E8\u7F72\u65B9\u5904\u7406\u540E\u91CD\u542F\u670D\u52A1\u3002";
        return;
      }
      if (!s.initialized) {
        mode = "init";
        document.getElementById("modeSub").textContent = "\u9996\u6B21\u521D\u59CB\u5316\uFF1A\u8BBE\u7F6E\u7BA1\u7406\u5458\u8D26\u53F7";
        document.getElementById("confirmField").style.display = "block";
        submitBtn.textContent = "\u5B8C\u6210\u521D\u59CB\u5316";
        noteEl.textContent = "\u5F53\u524D\u670D\u52A1\u7AEF\u8FD8\u6CA1\u6709\u8D26\u53F7\uFF0C\u8FD9\u91CC\u8BBE\u7F6E\u7684\u8D26\u53F7\u5BC6\u7801\u5C06\u6210\u4E3A\u552F\u4E00\u7684\u7BA1\u63A7\u53F0\u8D26\u53F7\u3002\u672C\u5165\u53E3\u53EA\u5728\u65E0\u8D26\u53F7\u65F6\u5F00\u653E\uFF0C\u4E4B\u540E\u6C38\u4E45\u5173\u95ED\uFF0C\u4E5F\u4E0D\u5B58\u5728\u6CE8\u518C\u4E0E\u627E\u56DE\u3002";
      } else {
        mode = "login";
        document.getElementById("modeSub").textContent = "\u767B\u5F55\u4EE5\u8FDB\u5165\u7BA1\u63A7\u53F0";
        document.getElementById("confirmField").style.display = "none";
        submitBtn.textContent = "\u767B\u5F55";
        noteEl.textContent = "\u672C\u4EA7\u54C1\u4E0D\u63D0\u4F9B\u6CE8\u518C\u4E0E\u5BC6\u7801\u627E\u56DE\u3002\u5FD8\u8BB0\u5BC6\u7801\u8BF7\u8054\u7CFB\u90E8\u7F72\u65B9\u5904\u7406\u3002";
        if (s.defaultCredential) {
          showBanner("\u5F53\u524D\u4ECD\u5728\u4F7F\u7528\u521D\u59CB\u5BC6\u7801\uFF0C\u5EFA\u8BAE\u5C3D\u5FEB\u4FEE\u6539\u3002\u662F\u5426\u4FEE\u6539\u7531\u90E8\u7F72\u65B9\u51B3\u5B9A\u3002", "warn");
        }
      }
      document.getElementById("username").value = s.username || "";
      document.getElementById("password").focus();
    }
    document.getElementById("loginForm").addEventListener("submit", function() {
      var username = document.getElementById("username").value.trim();
      var password = document.getElementById("password").value;
      var password2 = document.getElementById("password2").value;
      if (mode === "blocked") {
        return;
      }
      if (!username) {
        showResult("\u8D26\u53F7\u4E0D\u80FD\u4E3A\u7A7A", false);
        return;
      }
      if (!password) {
        showResult("\u5BC6\u7801\u4E0D\u80FD\u4E3A\u7A7A", false);
        return;
      }
      if (mode === "init" && password !== password2) {
        showResult("\u4E24\u6B21\u8F93\u5165\u7684\u5BC6\u7801\u4E0D\u4E00\u81F4", false);
        return;
      }
      submitBtn.disabled = true;
      submitBtn.classList.add("btn-loading");
      showResult(mode === "init" ? "\u6B63\u5728\u521D\u59CB\u5316\u2026" : "\u6B63\u5728\u767B\u5F55\u2026", true);
      var path = mode === "init" ? "/admin/init" : "/admin/login";
      Console.api.post(path, { username, password }).then(function(r) {
        if (r && r.defaultCredential) {
          try {
            sessionStorage.setItem("domainplane.defaultCredential", "1");
          } catch (e) {
          }
        }
        showResult(r && r.message || "\u767B\u5F55\u6210\u529F\uFF0C\u6B63\u5728\u8FDB\u5165\u7BA1\u63A7\u53F0\u2026", true);
        return Console.api.get("/admin/session").then(function(s) {
          if (s && s.authenticated) {
            enterConsole();
            return;
          }
          submitBtn.disabled = false;
          submitBtn.classList.remove("btn-loading");
          Console.warnIfCrossOrigin();
          showResult("\u8D26\u53F7\u5BC6\u7801\u6821\u9A8C\u901A\u8FC7\uFF0C\u4F46\u6D4F\u89C8\u5668\u6CA1\u6709\u4FDD\u5B58\u767B\u5F55\u51ED\u8BC1\uFF08Cookie\uFF09\u3002\u7BA1\u63A7\u53F0\u5FC5\u987B\u4ECE\u670D\u52A1\u7AEF\u81EA\u5DF1\u7684\u5730\u5740\u6253\u5F00\uFF1A" + Console.baseUrl() + "/admin.html", false);
        });
      }).catch(function(e) {
        submitBtn.disabled = false;
        submitBtn.classList.remove("btn-loading");
        showResult(e.message, false);
      });
    });
    Console.warnIfCrossOrigin();
    Console.api.get("/admin/session").then(applyMode).catch(function(e) {
      document.getElementById("modeSub").textContent = "\u65E0\u6CD5\u8FDE\u63A5\u670D\u52A1\u7AEF";
      showResult(e.message, false);
    });
  })();
})();

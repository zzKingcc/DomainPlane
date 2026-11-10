(() => {
  // domainplane-server/frontend/src/pages/account.js
  (function() {
    var esc = Console.escapeHtml;
    function pad(n) {
      return n < 10 ? "0" + n : "" + n;
    }
    function toDate(iso) {
      if (!iso) {
        return null;
      }
      var d = new Date(iso);
      return isNaN(d.getTime()) ? null : d;
    }
    function fmtFull(iso) {
      var d = toDate(iso);
      if (!d) {
        return iso ? String(iso) : "\u2014";
      }
      return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());
    }
    function fmtShort(iso) {
      var d = toDate(iso);
      if (!d) {
        return iso ? String(iso) : "\u2014";
      }
      return pad(d.getMonth() + 1) + "-" + pad(d.getDate()) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
    }
    function set(id, text) {
      var el = document.getElementById(id);
      if (el) {
        el.textContent = text;
      }
    }
    function setStat(id, text) {
      var el = document.getElementById(id);
      if (!el) {
        return;
      }
      el.textContent = text;
      el.classList.toggle("mute", text === "\u2014");
      if (text === "\u2014") {
        el.removeAttribute("title");
      } else {
        el.title = text;
      }
    }
    function setHtml(id, html) {
      var el = document.getElementById(id);
      if (el) {
        el.innerHTML = html;
      }
    }
    function chip(text, tone) {
      return '<span class="dp-chip ' + tone + '">' + esc(text) + "</span>";
    }
    var COLS = 2;
    function placeholderRow(inner) {
      return '<tr class="dp-doc-empty"><td colspan="' + COLS + '"><div>' + inner + "</div></td></tr>";
    }
    var EMPTY_ROW = placeholderRow('<div class="dp-doc-empty-icon"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg></div>\u6682\u65E0\u767B\u5F55\u8BB0\u5F55');
    function renderLogin(records) {
      var body = document.getElementById("loginBody");
      if (!body) {
        return;
      }
      set("loginCount", records.length ? "\u5171 " + records.length + " \u6761" : "0 \u6761");
      if (!records.length) {
        body.innerHTML = EMPTY_ROW;
        return;
      }
      body.innerHTML = records.map(function(r) {
        return "<tr><td>" + esc(fmtFull(r.at)) + '</td><td class="mono">' + esc(r.from || "\u2014") + "</td></tr>";
      }).join("");
    }
    function renderAccount(s) {
      var records = s.loginRecords || [];
      var last = records.length ? records[0] : null;
      var lastAt = s.lastLoginAt || last && last.at || "";
      var lastFrom = s.lastLoginFrom || last && last.from || "";
      var pwd = document.getElementById("statPwd");
      if (pwd) {
        pwd.textContent = s.defaultCredential ? "\u521D\u59CB\u5BC6\u7801" : "\u5DF2\u4FEE\u6539";
        pwd.className = "dp-stat-value" + (s.defaultCredential ? " warn" : "");
      }
      setStat("statLast", lastAt ? fmtShort(lastAt) : "\u2014");
      setStat("statFrom", lastFrom || "\u2014");
      set("statRecords", String(records.length));
      var name = s.username || "\u2014";
      set("acName", name);
      set("acAvatar", name.charAt(0));
      set("acFile", s.accountFile ? "\u8D26\u53F7\u6587\u4EF6 " + s.accountFile : "\u8D26\u53F7\u6587\u4EF6 \u2014");
      var chips = s.authenticated ? chip("\u5DF2\u767B\u5F55", "ok") : chip("\u672A\u767B\u5F55", "err");
      if (s.corrupted) {
        chips += chip("\u8D26\u53F7\u6587\u4EF6\u635F\u574F", "err");
      } else if (!s.initialized) {
        chips += chip("\u672A\u521D\u59CB\u5316", "warn");
      }
      setHtml("acChips", chips);
      renderLogin(records);
    }
    function loadSession() {
      var body = document.getElementById("loginBody");
      if (body) {
        body.innerHTML = placeholderRow("\u52A0\u8F7D\u4E2D\u2026");
      }
      return Console.api.get("/admin/session").then(function(s) {
        renderAccount(s);
      }).catch(function(e) {
        set("loginCount", "\u2014");
        if (body) {
          body.innerHTML = placeholderRow("\u8BFB\u53D6\u8D26\u53F7\u4FE1\u606F\u5931\u8D25\uFF1A" + esc(e.message));
        }
        Console.toast(e.message, { type: "error" });
      });
    }
    var modal = document.getElementById("pwdModal");
    function openModal() {
      if (modal) {
        modal.hidden = false;
        var first = document.getElementById("oldPassword");
        if (first) {
          first.focus();
        }
      }
    }
    function closeModal() {
      if (modal) {
        modal.hidden = true;
      }
    }
    var openBtn = document.getElementById("openPwdBtn");
    if (openBtn) {
      openBtn.addEventListener("click", openModal);
    }
    var closeBtn = document.getElementById("pwdModalClose");
    if (closeBtn) {
      closeBtn.addEventListener("click", closeModal);
    }
    if (modal) {
      modal.addEventListener("click", function(e) {
        if (e.target === modal) {
          closeModal();
        }
      });
      document.addEventListener("keydown", function(e) {
        if (e.key === "Escape" && !modal.hidden) {
          closeModal();
        }
      });
    }
    function clearPwd() {
      ["oldPassword", "newPassword", "newPassword2"].forEach(function(id) {
        var input = document.getElementById(id);
        if (input) {
          input.value = "";
        }
      });
      var el = document.getElementById("pwdResult");
      if (el) {
        el.className = "result";
        el.textContent = "";
      }
    }
    var clearBtn = document.getElementById("clearPwdBtn");
    if (clearBtn) {
      clearBtn.addEventListener("click", clearPwd);
    }
    var saveBtn = document.getElementById("savePwdBtn");
    if (saveBtn) {
      saveBtn.addEventListener("click", function() {
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
        saveBtn.disabled = true;
        saveBtn.classList.add("btn-loading");
        Console.api.post("/admin/password", { oldPassword, newPassword }).then(function(r) {
          el.textContent = r && r.message || "\u5BC6\u7801\u5DF2\u4FEE\u6539\uFF0C\u8BF7\u91CD\u65B0\u767B\u5F55";
          el.className = "result show ok";
          Console.toast(el.textContent, { type: "success" });
          setTimeout(function() {
            location.replace("login.html");
          }, 1600);
        }).catch(function(e) {
          el.textContent = e.message;
          el.className = "result show err";
        }).then(function() {
          saveBtn.disabled = false;
          saveBtn.classList.remove("btn-loading");
        });
      });
    }
    Console.renderSidebar("account");
    Console.ready(loadSession);
  })();
})();

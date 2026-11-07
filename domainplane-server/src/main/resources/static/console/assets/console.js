(() => {
  // src/core/request.js
  function pick(raw, helpers) {
    if (!raw || typeof raw !== "object") {
      return "";
    }
    var text = raw.detail || raw.error || raw.message || "";
    if (helpers && typeof helpers.sanitizeError === "function") {
      return helpers.sanitizeError(text);
    }
    return String(text || "");
  }
  function normalize(raw, helpers) {
    if (raw && typeof raw.code === "number") {
      return {
        ok: raw.code === 0,
        code: raw.code,
        message: pick(raw, helpers),
        data: raw
      };
    }
    if (raw && typeof raw.success === "boolean") {
      return {
        ok: raw.success,
        message: pick(raw, helpers),
        data: raw
      };
    }
    return { ok: true, message: "", data: raw };
  }
  function createApi(request, jsonRequest, helpers) {
    function settle(raw) {
      var n = normalize(raw, helpers);
      if (!n.ok) {
        throw new Error(n.message || "\u64CD\u4F5C\u5931\u8D25");
      }
      return n.data;
    }
    return {
      /** 成功返回 data，失败 reject（页面 .then 里不再出现信封判断） */
      get: function(path) {
        return request(path).then(settle);
      },
      post: function(path, body) {
        return jsonRequest(path, "POST", body).then(settle);
      },
      put: function(path, body) {
        return jsonRequest(path, "PUT", body).then(settle);
      },
      del: function(path) {
        return jsonRequest(path, "DELETE").then(settle);
      },
      /** 需要自己拼文案 / 判断的场景：返回归一化后的完整对象 */
      raw: function(path) {
        return request(path).then(function(raw) {
          var n = normalize(raw, helpers);
          return { ok: n.ok, code: n.code, message: n.message, data: n.data };
        });
      },
      normalize
    };
  }

  // src/core/feedback.js
  var ICON = {
    success: '<path d="M5 12l5 5l10 -10"/>',
    error: '<circle cx="12" cy="12" r="9"/><path d="M12 8v4"/><path d="M12 16h.01"/>',
    warning: '<path d="M10.24 3.6l-8.14 14.4a2 2 0 0 0 1.73 3h16.34a2 2 0 0 0 1.73 -3l-8.14 -14.4a2 2 0 0 0 -3.46 0z"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5"/><path d="M12 8h.01"/>'
  };
  var TYPE_META = {
    success: { title: "\u64CD\u4F5C\u6210\u529F", timeout: 4e3 },
    error: { title: "\u51FA\u9519\u4E86", timeout: 9e3 },
    warning: { title: "\u6CE8\u610F", timeout: 7e3 },
    info: { title: "\u63D0\u793A", timeout: 5e3 }
  };
  var MAX_TOASTS = 4;
  function createFeedback(options) {
    var opts = options || {};
    var doc = opts.document || (typeof document !== "undefined" ? document : null);
    var escapeHtml = opts.escapeHtml || function(s) {
      return String(s == null ? "" : s);
    };
    var win = opts.window || (doc ? doc.defaultView : null);
    if (!doc) {
      return {
        toast: function() {
        },
        confirm: function() {
          return Promise.resolve(false);
        }
      };
    }
    var stack = null;
    function ensureStack() {
      if (stack && stack.parentNode) {
        return stack;
      }
      stack = doc.createElement("div");
      stack.className = "dp-toast-stack";
      stack.setAttribute("aria-live", "polite");
      stack.setAttribute("aria-atomic", "false");
      doc.body.appendChild(stack);
      return stack;
    }
    function svg(paths) {
      return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + paths + "</svg>";
    }
    function dismissToast(el) {
      if (!el || el.__dpClosed) {
        return;
      }
      el.__dpClosed = true;
      if (el.__dpTimer) {
        clearTimeout(el.__dpTimer);
      }
      el.classList.add("dp-toast-hide");
      win.setTimeout(function() {
        if (el.parentNode) {
          el.parentNode.removeChild(el);
        }
      }, 200);
    }
    function toast(message, o) {
      var cfg = o || {};
      var type = TYPE_META[cfg.type] ? cfg.type : "info";
      var meta = TYPE_META[type];
      var box = ensureStack();
      var el = doc.createElement("div");
      el.className = "toast show dp-toast dp-toast-" + type + (cfg.wide ? " dp-toast-wide" : "");
      el.setAttribute("role", type === "error" ? "alert" : "status");
      el.innerHTML = '<div class="toast-header dp-toast-head"><span class="dp-toast-icon">' + svg(ICON[type]) + '</span><span class="dp-toast-title">' + escapeHtml(cfg.title || meta.title) + '</span><span class="dp-toast-spacer"></span><button type="button" class="btn-close" aria-label="\u5173\u95ED"></button></div><div class="toast-body dp-toast-body">' + escapeHtml(message) + "</div>";
      var closeBtn = el.querySelector(".btn-close");
      if (closeBtn) {
        closeBtn.addEventListener("click", function() {
          dismissToast(el);
        });
      }
      box.appendChild(el);
      var all = box.querySelectorAll(".toast");
      for (var i = 0; i < all.length - MAX_TOASTS; i++) {
        dismissToast(all[i]);
      }
      var timeout = typeof cfg.timeout === "number" ? cfg.timeout : meta.timeout;
      if (timeout > 0) {
        el.__dpTimer = win.setTimeout(function() {
          dismissToast(el);
        }, timeout);
      }
      return el;
    }
    var pending = null;
    function closeConfirm(result) {
      if (!pending) {
        return;
      }
      var p = pending;
      pending = null;
      if (p.modal && p.modal.parentNode) {
        p.modal.parentNode.removeChild(p.modal);
      }
      if (p.backdrop && p.backdrop.parentNode) {
        p.backdrop.parentNode.removeChild(p.backdrop);
      }
      doc.body.style.overflow = p.prevOverflow;
      doc.removeEventListener("keydown", onKeydown);
      if (p.prevFocus && p.prevFocus.focus) {
        try {
          p.prevFocus.focus();
        } catch (e) {
        }
      }
      p.resolve(result);
    }
    function onKeydown(e) {
      if (!pending) {
        return;
      }
      if (e.key === "Escape" || e.key === "Esc") {
        e.preventDefault();
        closeConfirm(false);
      }
    }
    function confirm(message, o) {
      var cfg = o || {};
      if (pending) {
        closeConfirm(false);
      }
      return new Promise(function(resolve) {
        var prevFocus = doc.activeElement;
        var prevOverflow = doc.body.style.overflow;
        var modal = doc.createElement("div");
        modal.className = "modal dp-confirm";
        modal.setAttribute("tabindex", "-1");
        modal.setAttribute("role", "dialog");
        modal.setAttribute("aria-modal", "true");
        modal.setAttribute("aria-labelledby", "dpConfirmTitle");
        modal.innerHTML = '<div class="modal-dialog" role="document"><div class="modal-content"><div class="modal-header"><h5 class="modal-title" id="dpConfirmTitle">' + escapeHtml(cfg.title || "\u786E\u8BA4\u64CD\u4F5C") + '</h5><button type="button" class="btn-close" data-dp-confirm="cancel" aria-label="\u5173\u95ED"></button></div><div class="modal-body dp-confirm-body">' + escapeHtml(message) + '</div><div class="modal-footer"><button type="button" class="btn btn-outline-secondary" data-dp-confirm="cancel">' + escapeHtml(cfg.cancelText || "\u53D6\u6D88") + '</button><button type="button" class="' + (cfg.danger === false ? "btn btn-outline-primary" : "btn btn-outline-danger") + '" data-dp-confirm="ok">' + escapeHtml(cfg.okText || "\u786E\u8BA4") + "</button></div></div></div>";
        var backdrop = doc.createElement("div");
        backdrop.className = "modal-backdrop show";
        pending = {
          resolve,
          prevFocus,
          prevOverflow,
          modal,
          backdrop
        };
        doc.body.appendChild(modal);
        doc.body.appendChild(backdrop);
        modal.classList.add("show");
        doc.body.style.overflow = "hidden";
        modal.addEventListener("click", function(e) {
          var t = e.target;
          var act = t && t.getAttribute ? t.getAttribute("data-dp-confirm") : null;
          if (act === "ok") {
            closeConfirm(true);
            return;
          }
          if (act === "cancel") {
            closeConfirm(false);
            return;
          }
          if (t === modal) {
            closeConfirm(false);
          }
        });
        backdrop.addEventListener("click", function() {
          closeConfirm(false);
        });
        doc.addEventListener("keydown", onKeydown);
        var okBtn = modal.querySelector('[data-dp-confirm="ok"]');
        if (okBtn) {
          okBtn.focus();
        }
      });
    }
    return {
      toast,
      confirm
    };
  }

  // src/console.js
  (function(global) {
    "use strict";
    function baseUrl() {
      return "";
    }
    var SERVED_BY_HTTP = /^https?:$/.test(location.protocol);
    function request(path, options) {
      var url = baseUrl() + path;
      progressStart();
      return fetch(url, options || {}).catch(function(e) {
        throw new Error("\u65E0\u6CD5\u8FDE\u63A5\u670D\u52A1\u7AEF " + (baseUrl() || location.origin) + "\uFF1A" + e.message);
      }).then(function(resp) {
        return resp.text().then(function(raw) {
          var data = null;
          try {
            data = JSON.parse(raw);
          } catch (e) {
            var isHtml = /^\s*</.test(raw);
            throw new Error("\u63A5\u53E3 " + url + " \u8FD4\u56DE " + resp.status + "\uFF0C\u4E14\u4E0D\u662F JSON" + (isHtml ? "\uFF08\u8FD4\u56DE\u7684\u662F HTML \u9875\u9762\uFF0C\u901A\u5E38\u8BF4\u660E\u8D70\u5230\u4E86\u5176\u4ED6\u670D\u52A1\u7684\u9875\u9762\uFF09" : "") + "\uFF1A" + sanitizeError(raw.slice(0, 120).replace(/\s+/g, " ")));
          }
          if (data && data.code === 10002) {
            redirectToLogin();
            throw new Error("\u767B\u5F55\u6001\u5DF2\u5931\u6548\uFF0C\u6B63\u5728\u8DF3\u8F6C\u767B\u5F55\u9875\u2026");
          }
          if (!resp.ok) {
            var reason = data ? sanitizeError(data.detail || data.error) : "";
            throw new Error(reason || "\u63A5\u53E3 " + url + " \u8FD4\u56DE " + resp.status + "\uFF1A" + sanitizeError(raw.slice(0, 120)));
          }
          return data;
        });
      }).then(
        function(v) {
          progressEnd();
          return v;
        },
        function(e) {
          progressEnd();
          throw e;
        }
      );
    }
    var progCount = 0;
    var progTimer = null;
    var progEl = null;
    var PROG_DELAY = 120;
    function ensureProgressEl() {
      if (progEl) {
        return progEl;
      }
      if (!document.body) {
        return null;
      }
      progEl = document.createElement("div");
      progEl.id = "dpProgress";
      document.body.appendChild(progEl);
      return progEl;
    }
    function progressStart() {
      progCount++;
      if (progTimer || progCount > 1) {
        return;
      }
      progTimer = setTimeout(function() {
        progTimer = null;
        if (progCount > 0) {
          var el = ensureProgressEl();
          if (el) {
            el.classList.add("on");
          }
        }
      }, PROG_DELAY);
    }
    function progressEnd() {
      progCount = Math.max(0, progCount - 1);
      if (progCount === 0) {
        if (progTimer) {
          clearTimeout(progTimer);
          progTimer = null;
        }
        if (progEl) {
          progEl.classList.remove("on");
        }
      }
    }
    function busy(btn, fn, loadingText) {
      if (!btn) {
        return Promise.resolve(typeof fn === "function" ? fn() : fn);
      }
      var origText = btn.textContent;
      var prevDisabled = btn.disabled;
      btn.disabled = true;
      btn.classList.add("btn-loading");
      if (loadingText) {
        btn.textContent = loadingText;
      }
      var p;
      try {
        p = Promise.resolve(typeof fn === "function" ? fn() : fn);
      } catch (e) {
        p = Promise.reject(e);
      }
      return p.then(
        function(v) {
          restore();
          return v;
        },
        function(e) {
          restore();
          throw e;
        }
      );
      function restore() {
        btn.disabled = prevDisabled;
        btn.classList.remove("btn-loading");
        if (loadingText) {
          btn.textContent = origText;
        }
      }
    }
    function loadingHtml(text) {
      return '<div class="dp-loading"><span class="dp-spin"></span><span>' + escapeHtml(text || "\u52A0\u8F7D\u4E2D\u2026") + "</span></div>";
    }
    function loadingRowHtml(colspan, text) {
      return '<tr><td colspan="' + (colspan || 1) + '"><div class="dp-loading dp-loading-inline"><span class="dp-spin"></span><span>' + escapeHtml(text || "\u52A0\u8F7D\u4E2D\u2026") + "</span></div></td></tr>";
    }
    function redirectToLogin() {
      var target = "login.html";
      if (location.pathname.indexOf("/console/") < 0) {
        target = "console/login.html";
      }
      location.replace(target);
    }
    function jsonRequest(path, method, body) {
      return request(path, {
        method: method || "POST",
        headers: { "Content-Type": "application/json" },
        body: body === void 0 ? void 0 : JSON.stringify(body)
      });
    }
    function escapeHtml(s) {
      return String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
    }
    var CRED_PATTERNS = [
      /* 协议://user:pass@host → 协议://***@host（host 保留：要靠它定位连的是哪台） */
      {
        re: /([a-zA-Z][a-zA-Z0-9+.-]*:\/\/)[^\/\s:@]+:[^\/\s@]+@/g,
        to: function(m, p1) {
          return p1 + "***@";
        }
      },
      /* Windows 盘符绝对路径：C:\Users\... 、 D:/java/...
         ⚠️ 必须带前置边界：`redis://h:6379` 里的 `s:/` 会被裸正则当成盘符，
         把 host 和端口一起吃掉，而 host 恰恰是排障要看的。 */
      {
        re: /(^|[\s"'（(])([A-Za-z]:[\\\/][^\s"'<>，。；、）)\]]{2,})/g,
        to: function(m, p1) {
          return p1 + "***";
        }
      },
      /* 常见部署目录下的 Unix 绝对路径：/opt/... /var/... /home/... */
      {
        re: /(^|[\s"'（(])((?:\/(?:opt|usr\/local|var|etc|home|root|tmp|Users|data|app)\/[^\s"'<>，。；、）)\]]{2,}))/g,
        to: function(m, p1) {
          return p1 + "***";
        }
      }
    ];
    function sanitizeError(s) {
      var out = String(s == null ? "" : s);
      if (!out) {
        return out;
      }
      CRED_PATTERNS.forEach(function(p) {
        out = out.replace(p.re, p.to);
      });
      return out;
    }
    var ICONS = {
      overview: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/>',
      models: '<rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6" rx="1"/><path d="M9 1v3M15 1v3M9 20v3M15 20v3M1 9h3M1 15h3M20 9h3M20 15h3"/>',
      infra: '<rect x="3" y="3" width="18" height="7" rx="2"/><rect x="3" y="14" width="18" height="7" rx="2"/><path d="M7 6.5h.01M7 17.5h.01"/>',
      knowledge: '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/><path d="M3 12c0 1.66 4 3 9 3s9-1.34 9-3"/>',
      /* 提示词设定：对话气泡 + 文本行（提示词是"写给模型的文字"） */
      prompts: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/><path d="M8 9h8M8 12.5h5"/>',
      /* 域：三层堆叠 = 域的叠加空间（域是平行的命名空间，用"叠"而不是"箱"） */
      domains: '<path d="M12 2 2 7l10 5 10-5-10-5z"/><path d="M2 12l10 5 10-5"/><path d="M2 17l10 5 10-5"/>',
      /* 熔断工具调用：心跳线，实例还在不在跳，决定它的工具是否还能被调用 */
      instances: '<path d="M2 12h4l3-7 4 14 3-7h6"/>',
      /* 会话档案：按会话 ID 查档（轮号分页 + 单轮内容 / 步骤 / 情况，全页只读） */
      sessions: '<rect x="3" y="4" width="18" height="5" rx="1"/><path d="M5 9v9a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9"/><path d="M10 13h4"/>',
      /* MCP 连接：链接符号，对接外部 MCP server */
      mcp: '<path d="M9 17H7A5 5 0 0 1 7 7h2"/><path d="M15 7h2a5 5 0 0 1 0 10h-2"/><line x1="8" y1="12" x2="16" y2="12"/>',
      account: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-6 8-6s8 2 8 6"/>'
    };
    var NAV = [
      { key: "overview", label: "\u6982\u89C8", href: "overview.html", icon: "overview" },
      { key: "models", label: "\u6A21\u578B\u8BBE\u7F6E", href: "models.html", icon: "models", warnAnchor: true },
      { key: "infra", label: "\u5B58\u50A8\u914D\u7F6E", href: "infra.html", icon: "infra" },
      { key: "mcp", label: "MCP \u8FDE\u63A5", href: "mcp.html", icon: "mcp" },
      { key: "domains", label: "\u57DF\u7A7A\u95F4", href: "domains.html", icon: "domains" },
      { key: "prompts", label: "\u63D0\u793A\u8BCD\u8BBE\u5B9A", href: "prompts.html", icon: "prompts" },
      { key: "knowledge", label: "\u77E5\u8BC6\u5E93", href: "knowledge.html", icon: "knowledge" },
      { key: "instances", label: "\u7194\u65AD\u5DE5\u5177\u8C03\u7528", href: "instances.html", icon: "instances" },
      { key: "sessions", label: "\u4F1A\u8BDD\u6863\u6848", href: "sessions.html", icon: "sessions" },
      { key: "account", label: "\u8D26\u53F7", href: "account.html", icon: "account" }
    ];
    function renderSidebar(activeKey, rootId) {
      var root = document.getElementById(rootId || "sidebar");
      if (!root) {
        return;
      }
      var items = NAV.map(function(n) {
        var cls = "dp-nav-item" + (n.key === activeKey ? " active" : "");
        var dot = n.warnAnchor ? '<span class="dot-warn" data-role="models-warn" style="display:none;" title="\u5BF9\u8BDD\u6A21\u578B\u5C1A\u672A\u914D\u7F6E\u5B8C\u6574"></span>' : "";
        return '<a class="' + cls + '" href="' + n.href + '"' + (n.key === activeKey ? ' aria-current="page"' : "") + '><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + ICONS[n.icon] + "</svg><span>" + n.label + "</span>" + dot + "</a>";
      }).join("");
      root.innerHTML = '<div class="brand"><h1>DomainPlane \u7BA1\u63A7\u53F0</h1><div class="sub">\u670D\u52A1\u7AEF\u8FD0\u7EF4 / \u7BA1\u7406\u5458\u63A7\u5236\u9762</div></div><nav class="dp-nav">' + items + '</nav><div class="sidebar-foot" id="sidebarFoot"></div>';
      renderAccountBox();
    }
    function renderAccountBox() {
      var box = document.getElementById("sidebarFoot");
      if (!box) {
        return;
      }
      request("/admin/session").then(function(s) {
        box.innerHTML = '<div class="foot-user" title="' + escapeHtml(s.username || "") + '"><span class="dot-user"></span><span class="foot-name">' + escapeHtml(s.username || "\u672A\u767B\u5F55") + '</span></div><a class="foot-link" href="account.html">\u8D26\u53F7\u4E0E\u5BC6\u7801</a><button class="foot-out btn btn-sm btn-outline-secondary" id="logoutBtn" type="button">\u9000\u51FA\u767B\u5F55</button>';
        var btn = document.getElementById("logoutBtn");
        if (btn) {
          btn.addEventListener("click", function() {
            jsonRequest("/admin/logout").then(redirectToLogin).catch(redirectToLogin);
          });
        }
      }).catch(function() {
        box.innerHTML = '<a class="foot-link" href="login.html">\u53BB\u767B\u5F55</a>';
      });
    }
    var sessionPromise = null;
    function checkSession() {
      if (!sessionPromise) {
        sessionPromise = request("/admin/session").catch(function() {
          return null;
        });
      }
      return sessionPromise;
    }
    function notLoggedIn(s) {
      return s && (!s.initialized || !s.authenticated);
    }
    function guardAuth() {
      if (global.CONSOLE_NO_AUTH_GUARD) {
        return;
      }
      checkSession().then(function(s) {
        if (notLoggedIn(s)) {
          redirectToLogin();
        }
      });
    }
    function ready(fn) {
      warnIfCrossOrigin();
      if (global.CONSOLE_NO_AUTH_GUARD) {
        fn();
        return;
      }
      checkSession().then(function(s) {
        if (notLoggedIn(s)) {
          redirectToLogin();
          return;
        }
        fn();
      });
    }
    function warnIfCrossOrigin() {
      if (SERVED_BY_HTTP || document.getElementById("originWarn")) {
        return;
      }
      var box = document.createElement("div");
      box.id = "originWarn";
      box.className = "origin-warn";
      box.innerHTML = "<b>\u5F53\u524D\u9875\u9762\u4E0D\u662F\u901A\u8FC7 http/https \u6253\u5F00\u7684\uFF08<code>" + escapeHtml(location.protocol) + "</code>\uFF09\uFF0C\u63A5\u53E3\u8BF7\u6C42\u53D1\u4E0D\u51FA\u53BB\u3002</b><br>\u7BA1\u63A7\u53F0\u7684\u51ED\u8BC1\u662F HttpOnly Cookie\uFF0C\u5FC5\u987B\u540C\u6E90\u624D\u80FD\u5E26\u4E0A\uFF0C\u56E0\u6B64\u53EA\u80FD\u7528\u670D\u52A1\u7AEF\u81EA\u5DF1\u7684\u5730\u5740\u6253\u5F00\u7BA1\u63A7\u53F0\uFF1A<code>http://&lt;\u670D\u52A1\u7AEF\u4E3B\u673A&gt;:&lt;\u7AEF\u53E3&gt;/admin.html</code>\u3002";
      var main = document.querySelector(".main");
      if (main) {
        main.insertBefore(box, main.firstChild);
      } else {
        document.body.insertBefore(box, document.body.firstChild);
      }
    }
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", guardAuth);
    } else {
      guardAuth();
    }
    var lastStatus = null;
    function refreshModelsWarn() {
      return request("/admin/settings").then(function(s) {
        lastStatus = s;
        setModelsWarn(!s.chatConfigured);
        return s;
      }).catch(function(e) {
        setModelsWarn(true);
        throw e;
      });
    }
    function setModelsWarn(on) {
      var dots = document.querySelectorAll('[data-role="models-warn"]');
      for (var i = 0; i < dots.length; i++) {
        dots[i].style.display = on ? "block" : "none";
      }
    }
    function lastSettings() {
      return lastStatus;
    }
    var api = createApi(request, jsonRequest, { sanitizeError });
    var feedback = createFeedback({ escapeHtml });
    global.Console = {
      baseUrl,
      sameOrigin: function() {
        return SERVED_BY_HTTP;
      },
      request,
      jsonRequest,
      api,
      escapeHtml,
      sanitizeError,
      toast: feedback.toast,
      confirm: feedback.confirm,
      busy,
      loadingHtml,
      loadingRowHtml,
      renderSidebar,
      ready,
      warnIfCrossOrigin,
      refreshModelsWarn,
      setModelsWarn,
      lastSettings,
      redirectToLogin
    };
  })(window);
})();

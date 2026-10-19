(() => {
  // domainplane-server/frontend/src/pages/mcp.js
  (function() {
    var esc = Console.escapeHtml;
    var CONNS = [];
    var BINDINGS = { domainBindings: {}, connectionBindings: {} };
    var DOMAINS = [];
    var EP = { STREAMABLE_HTTP: "Streamable HTTP", LEGACY_SSE: "Legacy SSE" };
    function statusTag(c) {
      if (!c.enabled) {
        return '<span class="tag off">\u5DF2\u7981\u7528</span>';
      }
      if (c.ready) {
        return '<span class="tag ready">\u5DF2\u5C31\u7EEA</span>';
      }
      if (c.needsAuth) {
        return '<span class="tag approval" title="' + esc(c.lastError || "") + '">\u9700\u6388\u6743</span>';
      }
      if (c.status === "FAILED") {
        return '<span class="tag failed" title="' + esc(c.lastError || "") + '">\u8FDE\u63A5\u5931\u8D25</span>';
      }
      return '<span class="tag off">\u672A\u8FDE\u63A5</span>';
    }
    function sideEffectTag(se) {
      var cls = "perm";
      if (se === "READ") {
        cls = "read";
      } else if (se === "WRITE") {
        cls = "write";
      } else if (se === "DESTRUCTIVE") {
        cls = "destructive";
      }
      return '<span class="tag ' + cls + '">' + esc(se) + "</span>";
    }
    function render() {
      var box = document.getElementById("connList");
      document.getElementById("connCount").textContent = CONNS.length + " \u4E2A\u8FDE\u63A5";
      if (!CONNS.length) {
        box.innerHTML = '<div class="dp-card"><span class="perm-none">\u8FD8\u6CA1\u6709\u4EFB\u4F55 MCP \u8FDE\u63A5\u3002\u70B9\u300C\u65B0\u589E\u8FDE\u63A5\u300D\u63A5\u5165\u4E00\u4E2A\u5916\u90E8 MCP server\u3002</span></div>';
        return;
      }
      box.innerHTML = CONNS.map(function(c) {
        var bound = BINDINGS.connectionBindings[c.name] || [];
        var boundTags = bound.length ? bound.map(function(d) {
          return '<span class="tag public mono">' + esc(d) + "</span>";
        }).join(" ") : '<span class="perm-none">\u672A\u7ED1\u5B9A\u4EFB\u4F55\u57DF\uFF08\u5DE5\u5177\u5BF9\u8C01\u90FD\u4E0D\u53EF\u89C1\uFF09</span>';
        return '<section class="dp-card model-card"><header class="card-head"><svg class="mcp-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 17H7A5 5 0 0 1 7 7h2"/><path d="M15 7h2a5 5 0 0 1 0 10h-2"/><line x1="8" y1="12" x2="16" y2="12"/></svg><h2 class="mono">' + esc(c.name) + "</h2>" + statusTag(c) + '<span class="spacer"></span><span class="card-note">\u5DE5\u5177 ' + (c.ready ? c.toolCount : 0) + " \xB7 \u7B56\u7565 " + esc(c.approvalPolicy) + "</span>" + (c.enabled ? '<button class="ghost btn btn-sm btn-outline-primary" data-toggle="' + esc(c.name) + '" data-to="false">\u505C\u7528</button>' : '<button class="ghost btn btn-sm btn-outline-primary" data-toggle="' + esc(c.name) + '" data-to="true">\u542F\u7528</button>') + '<button class="ghost btn btn-sm btn-outline-primary" data-test="' + esc(c.name) + '">\u6D4B\u8BD5</button><button class="ghost btn btn-sm btn-outline-primary" data-tools="' + esc(c.name) + '">\u5DE5\u5177</button>' + (c.needsAuth ? '<button class="ghost btn btn-sm btn-outline-primary" data-oauth="' + esc(c.name) + '">\u6388\u6743</button>' : "") + '<button class="ghost btn btn-sm btn-outline-primary" data-bind="' + esc(c.name) + '">\u7ED1\u5B9A\u57DF</button><button class="ghost btn btn-sm btn-outline-primary" data-edit="' + esc(c.name) + '">\u7F16\u8F91</button><button class="ghost btn btn-sm btn-outline-primary" data-del="' + esc(c.name) + '">\u5220\u9664</button></header><div class="kv"><span class="k">\u5730\u5740</span><span class="v">' + esc(c.url) + '</span><span class="k">\u4F20\u8F93</span><span class="v">' + esc(EP[c.transport] || c.transport) + "</span>" + (c.hasHeaders ? '<span class="k">\u9274\u6743</span><span class="v">\u5DF2\u914D\u7F6E\uFF08\u660E\u6587\u4E0D\u56DE\u663E\uFF09</span>' : "") + (c.oauthClientId ? '<span class="k">OAuth</span><span class="v">client_id ' + esc(c.oauthClientId) + (c.hasOauthClientSecret ? " + secret" : "") + "\uFF08\u624B\u5DE5\u767B\u8BB0\uFF09</span>" : "") + (c.lastError ? '<span class="k">\u9519\u8BEF</span><span class="v" style="color:var(--red);">' + esc(c.lastError) + "</span>" : "") + '</div><div style="margin-top:8px;"><span class="k" style="color:var(--muted);font-size:12px;">\u7ED1\u5B9A\u57DF\uFF1A</span> ' + boundTags + "</div></section>";
      }).join("");
      box.querySelectorAll("button[data-toggle]").forEach(function(b) {
        b.addEventListener("click", function() {
          toggle(b.getAttribute("data-toggle"), b.getAttribute("data-to") === "true");
        });
      });
      box.querySelectorAll("button[data-test]").forEach(function(b) {
        b.addEventListener("click", function() {
          testConn(b.getAttribute("data-test"));
        });
      });
      box.querySelectorAll("button[data-oauth]").forEach(function(b) {
        b.addEventListener("click", function() {
          startOAuth(b.getAttribute("data-oauth"));
        });
      });
      box.querySelectorAll("button[data-tools]").forEach(function(b) {
        b.addEventListener("click", function() {
          showTools(b.getAttribute("data-tools"));
        });
      });
      box.querySelectorAll("button[data-bind]").forEach(function(b) {
        b.addEventListener("click", function() {
          openBind(b.getAttribute("data-bind"));
        });
      });
      box.querySelectorAll("button[data-edit]").forEach(function(b) {
        b.addEventListener("click", function() {
          openEdit(b.getAttribute("data-edit"));
        });
      });
      box.querySelectorAll("button[data-del]").forEach(function(b) {
        b.addEventListener("click", function() {
          delConn(b.getAttribute("data-del"));
        });
      });
    }
    function loadBindings() {
      return Console.api.get("/admin/mcp-bindings").then(function(r) {
        BINDINGS = r || { domainBindings: {}, connectionBindings: {} };
      });
    }
    function loadDomains() {
      return Console.api.get("/admin/domains").then(function(r) {
        DOMAINS = r && r.domains || [];
      }).catch(function() {
        DOMAINS = [];
      });
    }
    function load() {
      document.getElementById("connList").innerHTML = '<div class="dp-card"><span class="perm-none">\u52A0\u8F7D\u4E2D\u2026</span></div>';
      Console.api.get("/admin/mcp-connections").then(function(r) {
        CONNS = r && r.connections || [];
        return loadBindings();
      }).then(render).catch(function(e) {
        document.getElementById("connList").innerHTML = '<div class="dp-card" style="color:var(--red);">\u8BFB\u53D6\u5931\u8D25\uFF1A' + esc(e.message) + "</div>";
      });
    }
    var EDIT_NAME = "";
    function openCreate() {
      EDIT_NAME = "";
      document.getElementById("editTitle").textContent = "\u65B0\u589E MCP \u8FDE\u63A5";
      document.getElementById("f_name").value = "";
      document.getElementById("f_name").disabled = false;
      document.getElementById("f_url").value = "";
      document.getElementById("f_transport").value = "STREAMABLE_HTTP";
      document.getElementById("f_approval").value = "ALL";
      document.getElementById("f_headers").value = "";
      document.getElementById("f_oauth_cid").value = "";
      document.getElementById("f_oauth_secret").value = "";
      document.getElementById("f_oauth_cname").value = "";
      document.getElementById("f_oauth_scope").value = "";
      document.getElementById("f_oauth_redirect").value = "";
      document.getElementById("f_timeout").value = "30000";
      document.getElementById("f_allow").value = "";
      document.getElementById("f_remark").value = "";
      document.getElementById("f_enabled").checked = true;
      document.getElementById("editModal").hidden = false;
      document.body.style.overflow = "hidden";
    }
    function openEdit(name) {
      var c = CONNS.filter(function(x) {
        return x.name === name;
      })[0];
      if (!c) {
        return;
      }
      EDIT_NAME = name;
      document.getElementById("editTitle").textContent = "\u7F16\u8F91 MCP \u8FDE\u63A5";
      document.getElementById("f_name").value = c.name;
      document.getElementById("f_name").disabled = true;
      document.getElementById("f_url").value = c.url || "";
      document.getElementById("f_transport").value = c.transport || "STREAMABLE_HTTP";
      document.getElementById("f_approval").value = c.approvalPolicy || "ALL";
      document.getElementById("f_headers").value = "";
      document.getElementById("f_oauth_cid").value = c.oauthClientId || "";
      document.getElementById("f_oauth_secret").value = "";
      document.getElementById("f_oauth_cname").value = c.oauthClientName || "";
      document.getElementById("f_oauth_scope").value = c.oauthScope || "";
      document.getElementById("f_oauth_redirect").value = c.oauthRedirectUri || "";
      document.getElementById("f_timeout").value = c.callTimeoutMs || 3e4;
      document.getElementById("f_allow").value = (c.toolAllowlist || []).join(", ");
      document.getElementById("f_remark").value = c.remark || "";
      document.getElementById("f_enabled").checked = c.enabled !== false;
      document.getElementById("editModal").hidden = false;
      document.body.style.overflow = "hidden";
    }
    function closeEdit() {
      document.getElementById("editModal").hidden = true;
      document.body.style.overflow = "";
    }
    function saveEdit() {
      var name = document.getElementById("f_name").value.trim();
      var url = document.getElementById("f_url").value.trim();
      var headersRaw = document.getElementById("f_headers").value.trim();
      var headers = {};
      if (headersRaw) {
        try {
          headers = JSON.parse(headersRaw);
        } catch (e) {
          alert("\u81EA\u5B9A\u4E49\u8BF7\u6C42\u5934\u4E0D\u662F\u5408\u6CD5 JSON\uFF1A" + e.message);
          return;
        }
      }
      var allowRaw = document.getElementById("f_allow").value.trim();
      var allow = allowRaw ? allowRaw.split(/[,\s]+/).filter(Boolean) : [];
      var timeout = parseInt(document.getElementById("f_timeout").value, 10);
      var body = {
        name,
        url,
        transport: document.getElementById("f_transport").value,
        approvalPolicy: document.getElementById("f_approval").value,
        headers,
        callTimeoutMs: isNaN(timeout) ? 3e4 : timeout,
        enabled: document.getElementById("f_enabled").checked,
        toolAllowlist: allow,
        remark: document.getElementById("f_remark").value.trim(),
        oauthClientId: document.getElementById("f_oauth_cid").value.trim(),
        oauthClientSecret: document.getElementById("f_oauth_secret").value,
        oauthClientName: document.getElementById("f_oauth_cname").value.trim(),
        oauthScope: document.getElementById("f_oauth_scope").value.trim(),
        oauthRedirectUri: document.getElementById("f_oauth_redirect").value.trim()
      };
      Console.api.post("/admin/mcp-connections", body).then(function(r) {
        alert(r && r.message ? r.message : "\u5DF2\u4FDD\u5B58");
        closeEdit();
        load();
      }).catch(function(e) {
        alert("\u4FDD\u5B58\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function toggle(name, enabled) {
      Console.api.post("/admin/mcp-connections/" + encodeURIComponent(name) + "/toggle", null).then(function() {
        load();
      }).catch(function(e) {
        alert("\u64CD\u4F5C\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function delConn(name) {
      if (!confirm("\u786E\u8BA4\u5220\u9664 MCP \u8FDE\u63A5 " + name + "\uFF1F\n\n\u5176\u5DE5\u5177\u4F1A\u88AB\u6458\u9664\u3001\u7ED1\u5B9A\u4F1A\u89E3\u7ED1\uFF08\u4E0D\u5220\u57DF\uFF09\u3002")) {
        return;
      }
      Console.api.del("/admin/mcp-connections?name=" + encodeURIComponent(name)).then(function() {
        load();
      }).catch(function(e) {
        alert("\u5220\u9664\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function testConn(name) {
      if (!confirm("\u5BF9 " + name + " \u53D1\u8D77\u8FDE\u901A\u6027\u6D4B\u8BD5\uFF08initialize + tools/list\uFF09\uFF1F")) {
        return;
      }
      Console.api.post("/admin/mcp-connections/" + encodeURIComponent(name) + "/test", null).then(function(r) {
        alert((r && r.message) + (r && r.tools ? "\n\u5DE5\u5177\uFF1A" + r.tools.join(", ") : ""));
        load();
      }).catch(function(e) {
        alert("\u6D4B\u8BD5\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function startOAuth(name) {
      Console.api.post("/admin/mcp-connections/" + encodeURIComponent(name) + "/oauth/start", null).then(function(r) {
        if (r && r.authorizeUrl) {
          location.href = r.authorizeUrl;
          return;
        }
        throw new Error("\u6388\u6743\u7AEF\u70B9\u672A\u8FD4\u56DE\u6388\u6743\u94FE\u63A5");
      }).catch(function(e) {
        alert("\u53D1\u8D77\u6388\u6743\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function showTools(name) {
      document.getElementById("toolsConnName").textContent = name;
      document.getElementById("toolsBody").innerHTML = "\u52A0\u8F7D\u4E2D\u2026";
      document.getElementById("toolsModal").hidden = false;
      document.body.style.overflow = "hidden";
      Console.api.get("/admin/mcp-connections/" + encodeURIComponent(name) + "/tools").then(function(r) {
        var tools = r && r.tools || [];
        if (!tools.length) {
          document.getElementById("toolsBody").innerHTML = '<div class="perm-none">' + (r && r.ready ? "\u8BE5\u8FDE\u63A5\u6CA1\u6709\u5DE5\u5177" : "\u8FDE\u63A5\u672A\u5C31\u7EEA\uFF0C\u65E0\u6CD5\u8BFB\u53D6\u5DE5\u5177\u76EE\u5F55") + "</div>";
          return;
        }
        document.getElementById("toolsBody").innerHTML = tools.map(function(t) {
          return '<div class="dp-card" style="margin-bottom:8px;padding:10px 12px;"><div style="display:flex;gap:8px;align-items:center;"><span class="mono" style="font-weight:600;">' + esc(t.originalName) + "</span>" + (t.allowed ? "" : '<span class="tag off" title="\u88AB\u5DE5\u5177\u767D\u540D\u5355\u88C1\u526A">\u5DF2\u88C1\u526A</span>') + sideEffectTag(t.sideEffect) + '<span class="spacer"></span><span class="card-note mono">\u2192 ' + esc(t.mappedName) + "</span></div>" + (t.description ? '<div class="card-note" style="margin-top:4px;">' + esc(t.description) + "</div>" : "") + "</div>";
        }).join("");
      }).catch(function(e) {
        document.getElementById("toolsBody").innerHTML = '<div class="dp-card" style="color:var(--red);">\u8BFB\u53D6\u5931\u8D25\uFF1A' + esc(e.message) + "</div>";
      });
    }
    var BIND_CONN = "";
    var BIND_PICK = [];
    function openBind(name) {
      BIND_CONN = name;
      var currently = BINDINGS.connectionBindings[name] || [];
      BIND_PICK = currently.slice();
      document.getElementById("bindConnName").textContent = name;
      renderBindCards();
      document.getElementById("bindModal").hidden = false;
      document.body.style.overflow = "hidden";
    }
    function closeBind() {
      document.getElementById("bindModal").hidden = true;
      document.body.style.overflow = "";
    }
    function renderBindCards() {
      var box = document.getElementById("bindCardList");
      var empty = document.getElementById("bindEmpty");
      if (!DOMAINS.length) {
        box.innerHTML = "";
        empty.style.display = "block";
        empty.textContent = "\u8FD8\u6CA1\u6709\u4EFB\u4F55\u57DF\uFF0C\u8BF7\u5148\u5230\u300C\u57DF\u7A7A\u95F4\u300D\u521B\u5EFA\u3002";
        return;
      }
      empty.style.display = "none";
      box.innerHTML = DOMAINS.map(function(d) {
        var on = BIND_PICK.indexOf(d.name) >= 0;
        return '<div class="pick-card' + (on ? " on" : "") + '" data-pick="' + esc(d.name) + '"><div class="pc-name">' + esc(d.name) + (on ? " \u2713" : "") + (d.callable ? "" : ' <span class="tag approval" title="\u88C5\u914D\u8282\u70B9\uFF1A\u53EA\u628A\u5DE5\u5177\u4F20\u7ED9\u540E\u4EE3">\u88C5\u914D</span>') + '</div><div class="pc-note">\u6765\u6E90 ' + esc(d.sourceLabel || d.source || "") + "</div></div>";
      }).join("");
      box.querySelectorAll("div[data-pick]").forEach(function(card) {
        card.addEventListener("click", function() {
          var d = card.getAttribute("data-pick");
          var i = BIND_PICK.indexOf(d);
          if (i >= 0) {
            BIND_PICK.splice(i, 1);
          } else {
            BIND_PICK.push(d);
          }
          renderBindCards();
        });
      });
    }
    function saveBind() {
      var promises = [];
      DOMAINS.forEach(function(d) {
        var current = (BINDINGS.domainBindings[d.name] || []).slice();
        var want = BIND_PICK.indexOf(d.name) >= 0;
        var has = current.indexOf(BIND_CONN) >= 0;
        if (want === has) {
          return;
        }
        var next = want ? current.concat([BIND_CONN]) : current.filter(function(x) {
          return x !== BIND_CONN;
        });
        promises.push(Console.api.put("/admin/mcp-bindings/" + encodeURIComponent(d.name), { connections: next }));
      });
      Promise.all(promises.map(function(p) {
        return p.catch(function(e) {
          return { __error: e.message };
        });
      })).then(function(results) {
        var err = results.filter(function(r) {
          return r && r.__error;
        })[0];
        if (err) {
          alert("\u90E8\u5206\u7ED1\u5B9A\u4FDD\u5B58\u5931\u8D25\uFF1A" + err.__error);
        } else {
          alert("\u7ED1\u5B9A\u5DF2\u4FDD\u5B58");
        }
        closeBind();
        load();
      });
    }
    document.getElementById("createBtn").addEventListener("click", openCreate);
    document.getElementById("refreshBtn").addEventListener("click", load);
    document.getElementById("editClose").addEventListener("click", closeEdit);
    document.getElementById("editSaveBtn").addEventListener("click", saveEdit);
    document.getElementById("editClearBtn").addEventListener("click", openCreate);
    document.getElementById("bindClose").addEventListener("click", closeBind);
    document.getElementById("bindSaveBtn").addEventListener("click", saveBind);
    document.getElementById("bindClearBtn").addEventListener("click", function() {
      BIND_PICK = [];
      renderBindCards();
    });
    document.getElementById("toolsClose").addEventListener("click", function() {
      document.getElementById("toolsModal").hidden = true;
      document.body.style.overflow = "";
    });
    document.getElementById("editModal").addEventListener("click", function(e) {
      if (e.target === document.getElementById("editModal")) {
        closeEdit();
      }
    });
    document.getElementById("bindModal").addEventListener("click", function(e) {
      if (e.target === document.getElementById("bindModal")) {
        closeBind();
      }
    });
    Console.renderSidebar("mcp");
    Console.ready(function() {
      var q = new URLSearchParams(location.search);
      var auth = q.get("auth");
      if (auth === "ok") {
        alert("\u6388\u6743\u6210\u529F\uFF1A\u8FDE\u63A5 " + (q.get("name") || "") + " \u5DF2\u5C31\u7EEA\uFF0C\u5DE5\u5177\u5BF9\u7ED1\u5B9A\u57DF\u53EF\u89C1");
      } else if (auth === "fail") {
        alert("\u6388\u6743\u5931\u8D25\uFF1A" + (q.get("reason") || "\u672A\u77E5\u539F\u56E0"));
      }
      if (auth) {
        history.replaceState(null, "", location.pathname);
      }
      loadDomains().then(load);
    });
  })();
})();

(() => {
  // domainplane-server/frontend/src/pages/mcp.js
  (function() {
    var esc = Console.escapeHtml;
    var PROTOCOL = "2025-03-26";
    var POLL_MS = 15e3;
    var EP = { STREAMABLE_HTTP: "Streamable HTTP", LEGACY_SSE: "Legacy SSE" };
    var ST = {
      READY: { label: "\u5DF2\u5C31\u7EEA", cls: "ready" },
      CONNECTING: { label: "\u8FDE\u63A5\u4E2D", cls: "connecting" },
      NEEDS_AUTH: { label: "\u9700\u6388\u6743", cls: "needs" },
      FAILED: { label: "\u8FDE\u63A5\u5931\u8D25", cls: "failed" },
      DISCONNECTED: { label: "\u672A\u8FDE\u63A5", cls: "disc" },
      OFF: { label: "\u5DF2\u7981\u7528", cls: "off" }
    };
    var PANES = { overview: "paneOverview", tools: "paneTools", auth: "paneAuth", bind: "paneBind" };
    var state = {
      conns: [],
      settingsFile: "",
      bindings: { domainBindings: {}, connectionBindings: {} },
      domains: [],
      selected: "",
      tab: "overview",
      tools: [],
      toolsFor: "",
      toolsError: "",
      toolDetail: "",
      allowPick: [],
      bindPick: [],
      bindFor: "",
      bindTouched: false,
      editing: false,
      editName: "",
      lastSync: 0,
      listSig: ""
    };
    function el(id) {
      return document.getElementById(id);
    }
    function setText(id, v) {
      var n = el(id);
      if (n) {
        n.textContent = v;
      }
    }
    function setHtml(id, v) {
      var n = el(id);
      if (n) {
        n.innerHTML = v;
      }
    }
    function setHidden(id, h) {
      var n = el(id);
      if (n) {
        n.hidden = !!h;
      }
    }
    function val(id) {
      var n = el(id);
      return n ? String(n.value || "").trim() : "";
    }
    function setVal(id, v) {
      var n = el(id);
      if (n) {
        n.value = v == null ? "" : String(v);
      }
    }
    function checked(id) {
      var n = el(id);
      return !!(n && n.checked);
    }
    function setChecked(id, v) {
      var n = el(id);
      if (n) {
        n.checked = !!v;
      }
    }
    function disable(id, on) {
      var n = el(id);
      if (n) {
        n.disabled = !!on;
      }
    }
    function num(v) {
      return v === void 0 || v === null || v === "" ? "\u2014" : String(v);
    }
    function errText(e) {
      return e && e.message ? String(e.message) : "\u672A\u77E5\u9519\u8BEF";
    }
    function shortErr(s, n) {
      s = String(s || "");
      return s.length > n ? s.slice(0, n) + "\u2026" : s;
    }
    function timeOf(ms) {
      if (!ms) {
        return "\u2014";
      }
      var d = new Date(ms);
      function p(x) {
        return ("0" + x).slice(-2);
      }
      return p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
    }
    function kv(k, v) {
      return '<div class="k">' + esc(k) + '</div><div class="v">' + v + "</div>";
    }
    function findConn(name) {
      for (var i = 0; i < state.conns.length; i++) {
        if (state.conns[i].name === name) {
          return state.conns[i];
        }
      }
      return null;
    }
    function boundOf(name) {
      return (state.bindings.connectionBindings || {})[name] || [];
    }
    function statusOf(c) {
      if (!c) {
        return "DISCONNECTED";
      }
      if (c.enabled === false) {
        return "OFF";
      }
      if (c.ready) {
        return "READY";
      }
      if (c.needsAuth) {
        return "NEEDS_AUTH";
      }
      if (c.status === "FAILED") {
        return "FAILED";
      }
      if (c.status === "CONNECTING") {
        return "CONNECTING";
      }
      return "DISCONNECTED";
    }
    function chip(c) {
      var m = ST[statusOf(c)] || ST.DISCONNECTED;
      return '<span class="dp-mcp-s ' + m.cls + '"><i></i>' + m.label + "</span>";
    }
    function noteOf(c) {
      return "\u5DE5\u5177 " + (c.ready ? num(c.toolCount) : "0") + " \xB7 \u57DF " + boundOf(c.name).length + " \xB7 " + (EP[c.transport] || c.transport || "\u2014");
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
      return '<span class="tag ' + cls + '">' + esc(se || "\u2014") + "</span>";
    }
    function setStat(id, v, tone) {
      var n = el(id);
      if (n) {
        n.textContent = v;
        n.className = "dp-stat-value" + (tone ? " " + tone : "");
      }
    }
    function renderStats() {
      var ready = 0, needs = 0, failed = 0, tools = 0;
      state.conns.forEach(function(c) {
        var s = statusOf(c);
        if (s === "READY") {
          ready++;
          tools += Number(c.toolCount) || 0;
        } else if (s === "NEEDS_AUTH") {
          needs++;
        } else if (s === "FAILED") {
          failed++;
        }
      });
      setStat("statConns", String(state.conns.length));
      setStat("statReady", String(ready));
      setStat("statNeedsAuth", String(needs), needs ? "warn" : "");
      setStat("statFailed", String(failed), failed ? "err" : "");
      setStat("statTools", String(tools));
    }
    function visibleConns() {
      var q = val("connSearch").toLowerCase(), f = val("statusFilter");
      return state.conns.filter(function(c) {
        if (f && statusOf(c) !== f) {
          return false;
        }
        if (!q) {
          return true;
        }
        return (String(c.name || "") + " " + String(c.url || "")).toLowerCase().indexOf(q) >= 0;
      });
    }
    function itemHtml(c) {
      var s = statusOf(c);
      var bad = c.lastError && (s === "FAILED" || s === "NEEDS_AUTH");
      return '<div class="dp-mcp-item' + (c.name === state.selected ? " on" : "") + '" data-name="' + esc(c.name) + '"><div class="dp-mcp-item-top"><span class="dp-mcp-item-name mono">' + esc(c.name) + '</span><span class="dp-mcp-item-status">' + chip(c) + '</span></div><div class="dp-mcp-item-note">' + esc(noteOf(c)) + "</div>" + (bad ? '<div class="dp-mcp-item-err">' + esc(shortErr(c.lastError, 60)) + "</div>" : "") + "</div>";
    }
    function renderList() {
      var list = visibleConns();
      if (!list.length) {
        setHtml("connList", '<div class="dp-mcp-none">\u6CA1\u6709\u5339\u914D\u7684\u8FDE\u63A5</div>');
        return;
      }
      setHtml("connList", list.map(itemHtml).join(""));
    }
    function patchList() {
      var box = el("connList");
      if (!box) {
        return;
      }
      var byName = {};
      state.conns.forEach(function(c) {
        byName[c.name] = c;
      });
      box.querySelectorAll(".dp-mcp-item").forEach(function(row) {
        var c = byName[row.getAttribute("data-name")];
        if (!c) {
          return;
        }
        var s = row.querySelector(".dp-mcp-item-status");
        if (s) {
          s.innerHTML = chip(c);
        }
        var n = row.querySelector(".dp-mcp-item-note");
        if (n) {
          n.textContent = noteOf(c);
        }
        var old = row.querySelector(".dp-mcp-item-err");
        if (old) {
          old.remove();
        }
        var st = statusOf(c);
        if (c.lastError && (st === "FAILED" || st === "NEEDS_AUTH")) {
          row.insertAdjacentHTML("beforeend", '<div class="dp-mcp-item-err">' + esc(shortErr(c.lastError, 60)) + "</div>");
        }
      });
    }
    function patchStatus() {
      renderStats();
      var sig = state.conns.map(function(c2) {
        return c2.name;
      }).join("");
      if (sig !== state.listSig) {
        state.listSig = sig;
        renderList();
      } else {
        patchList();
      }
      setText("syncHint", "\u4E0A\u6B21\u540C\u6B65 " + timeOf(state.lastSync));
      var c = findConn(state.selected);
      if (c && !state.editing) {
        renderStatusBar(c);
        if (state.tab === "overview") {
          renderDiag(c);
        }
        if (state.tab === "auth") {
          renderAuth(c);
        }
      }
    }
    function renderStatusBar(c) {
      var s = statusOf(c);
      var meta = "\u4F20\u8F93 " + (EP[c.transport] || c.transport || "\u2014") + " \xB7 \u534F\u5546\u534F\u8BAE " + (c.protocolVersion || "\u672A\u534F\u5546") + " \xB7 \u5DE5\u5177 " + (c.ready ? num(c.toolCount) : "0") + " \xB7 \u4E0A\u6B21\u63A2\u6D4B " + timeOf(c.lastAttemptMs) + " \xB7 \u9875\u9762\u540C\u6B65 " + timeOf(state.lastSync);
      var err = (s === "FAILED" || s === "NEEDS_AUTH") && c.lastError ? '<div class="dp-mcp-sb-err">' + esc(shortErr(c.lastError, 120)) + (s === "NEEDS_AUTH" ? '<button class="btn btn-sm" type="button" data-act="oauth">\u53BB\u6388\u6743</button>' : '<button class="btn btn-sm" type="button" data-act="test">\u91CD\u8BD5\u6D4B\u8BD5</button>') + "</div>" : "";
      setHtml(
        "statusBar",
        '<div class="dp-mcp-sb-top"><div class="dp-mcp-sb-state">' + chip(c) + '<span class="dp-mcp-sb-name mono">' + esc(c.name) + '</span></div><div class="dp-mcp-sb-actions"><button class="btn btn-sm btn-outline-primary" type="button" data-act="test">\u6D4B\u8BD5\u8FDE\u63A5</button><button class="btn btn-sm btn-outline-secondary" type="button" data-act="edit">\u7F16\u8F91</button><details class="dp-mcp-more"><summary class="btn btn-sm btn-outline-secondary">\u66F4\u591A</summary><div class="dp-mcp-menu"><button type="button" data-act="toggle">' + (c.enabled === false ? "\u542F\u7528" : "\u505C\u7528") + '</button><button type="button" data-act="oauth">\u53D1\u8D77\u6388\u6743</button><button type="button" data-act="delete">\u5220\u9664</button></div></details></div></div><div class="dp-mcp-sb-meta">' + esc(meta) + "</div>" + err
      );
    }
    function selectTab(tab) {
      state.tab = tab;
      var bar = el("tabBar");
      if (bar) {
        bar.querySelectorAll(".dp-mcp-tab").forEach(function(b) {
          b.classList.toggle("on", b.getAttribute("data-tab") === tab);
        });
      }
      Object.keys(PANES).forEach(function(t) {
        setHidden(PANES[t], t !== tab);
      });
      var c = findConn(state.selected);
      if (c) {
        renderTab(c);
      }
    }
    function renderTab(c) {
      if (state.tab === "overview") {
        renderOverview(c);
      } else if (state.tab === "tools") {
        renderTools(c);
      } else if (state.tab === "auth") {
        renderAuth(c);
      } else {
        renderBind(c);
      }
    }
    function renderDetail() {
      var c = findConn(state.selected);
      setHidden("detailEmpty", !!c);
      setHidden("detailBody", !c);
      if (!c) {
        return;
      }
      renderStatusBar(c);
      selectTab(state.tab);
    }
    function renderOverview(c) {
      var bound = boundOf(c.name);
      var allow = c.toolAllowlist || [];
      var caps = Object.keys(c.capabilities || {});
      var keys = c.headerKeys || [];
      setHtml(
        "paneOverview",
        '<div id="diagCard"></div><div class="dp-mcp-kv">' + kv("\u5730\u5740", esc(c.url || "\u2014")) + kv("\u4F20\u8F93", esc(EP[c.transport] || c.transport || "\u2014")) + kv("\u5BA2\u6237\u7AEF\u57FA\u7EBF", esc(PROTOCOL)) + kv("\u534F\u5546\u534F\u8BAE", esc(c.protocolVersion || "\u672A\u534F\u5546\u6210\u529F")) + kv("\u670D\u52A1\u7AEF\u80FD\u529B", caps.length ? esc(caps.join("\u3001")) : "\u672A\u534F\u5546") + kv("\u4E0A\u6B21\u63A2\u6D4B", esc(timeOf(c.lastAttemptMs))) + kv("\u8C03\u7528\u8D85\u65F6", c.callTimeoutMs ? esc(String(c.callTimeoutMs)) + " ms" : "\u9ED8\u8BA4 30000 ms") + kv("\u5BA1\u6279\u7B56\u7565", esc(c.approvalPolicy || "\u2014")) + kv("\u8BF7\u6C42\u5934\u952E", keys.length ? keys.map(function(k) {
          return '<span class="tag perm mono">' + esc(k) + "</span>";
        }).join(" ") : "\u672A\u914D\u7F6E") + kv("\u5DE5\u5177\u767D\u540D\u5355", allow.length ? esc(allow.join(", ")) : "\u5168\u90E8\u5F00\u653E") + kv("\u7ED1\u5B9A\u57DF", bound.length ? bound.map(function(d) {
          return '<span class="tag public mono">' + esc(d) + "</span>";
        }).join(" ") : "\u672A\u7ED1\u5B9A") + kv("\u5907\u6CE8", esc(c.remark || "\u2014")) + kv("\u914D\u7F6E\u6587\u4EF6", esc(state.settingsFile || "\u2014")) + "</div>"
      );
      renderDiag(c);
    }
    function renderDiag(c) {
      var s = statusOf(c);
      var has = !!c.lastError;
      var card = el("diagCard");
      var wasOpen = !!(card && card.querySelector(".dp-mcp-diag-raw[open]"));
      setHtml(
        "diagCard",
        '<div class="dp-mcp-diag"><div class="dp-mcp-diag-head">\u8BCA\u65AD</div><div class="dp-mcp-diag-row"><span>\u8FD0\u884C\u72B6\u6001</span><strong>' + esc(ST[s].label) + '</strong></div><div class="dp-mcp-diag-row"><span>\u6700\u8FD1\u9519\u8BEF</span>' + (has ? '<strong class="err">' + esc(shortErr(c.lastError, 100)) + "</strong>" : "<strong>\u65E0</strong>") + '</div><div class="dp-mcp-diag-row"><span>\u6388\u6743\u6311\u6218</span>' + (c.authChallenge ? "<strong>" + esc(shortErr(c.authChallenge, 100)) + "</strong>" : "<strong>\u65E0</strong>") + '</div><div class="dp-mcp-diag-row"><span>\u4E0A\u6B21\u540C\u6B65</span><strong>' + esc(timeOf(state.lastSync)) + "</strong></div>" + (has ? '<details class="dp-mcp-diag-raw"' + (wasOpen ? " open" : "") + "><summary>\u5C55\u5F00\u9519\u8BEF\u539F\u6587</summary><pre>" + esc(c.lastError) + "</pre></details>" : "") + "</div>"
      );
    }
    function loadTools(name) {
      return Console.api.get("/admin/mcp-connections/" + encodeURIComponent(name) + "/tools").then(function(r) {
        state.tools = r && r.tools || [];
        state.toolsFor = name;
        state.toolsError = "";
        afterTools(name);
      }).catch(function(e) {
        state.tools = [];
        state.toolsFor = name;
        state.toolsError = errText(e);
        afterTools(name);
      });
    }
    function afterTools(name) {
      var c = findConn(state.selected);
      if (c && c.name === name && state.tab === "tools") {
        renderTools(c);
      }
      if (state.editing && state.editName === name) {
        renderAllowTools();
      }
    }
    function renderTools(c) {
      if (state.toolsFor !== c.name) {
        setHtml("paneTools", Console.loadingHtml("\u6B63\u5728\u8BFB\u53D6\u5DE5\u5177\u76EE\u5F55\u2026"));
        loadTools(c.name);
        return;
      }
      var retry = '<button class="btn btn-sm" type="button" data-act="reloadTools">\u91CD\u65B0\u8BFB\u53D6</button>';
      if (state.toolsError) {
        setHtml("paneTools", '<div class="dp-mcp-err">\u8BFB\u53D6\u5DE5\u5177\u76EE\u5F55\u5931\u8D25\uFF1A' + esc(state.toolsError) + "</div>" + retry);
        return;
      }
      if (!state.tools.length) {
        setHtml("paneTools", '<div class="dp-mcp-none">\u8BE5\u8FDE\u63A5\u6CA1\u6709\u5DE5\u5177</div>' + retry);
        return;
      }
      setHtml(
        "paneTools",
        '<div class="dp-mcp-tools-head"><span>' + state.tools.length + ' \u4E2A\u5DE5\u5177</span><span class="spacer"></span>' + retry + '<button class="btn btn-sm btn-outline-primary" type="button" data-act="editAllow">\u7F16\u8F91\u767D\u540D\u5355</button></div><div class="dp-mcp-table-wrap"><table class="table dp-mcp-table"><thead><tr><th>\u72B6\u6001</th><th>\u5DE5\u5177</th><th>\u526F\u4F5C\u7528</th><th>\u5BA1\u6279</th><th>\u8BF4\u660E</th></tr></thead><tbody>' + state.tools.map(function(t) {
          var open = state.toolDetail === t.originalName;
          var schema;
          try {
            schema = t.inputSchema ? JSON.stringify(t.inputSchema, null, 2) : "\u65E0";
          } catch (e) {
            schema = "\u65E0";
          }
          var domains = (t.boundDomains || []).join("\u3001") || "\u672A\u7ED1\u5B9A";
          return "<tr><td>" + (t.allowed ? '<span class="tag public">\u5DF2\u5F00\u653E</span>' : '<span class="tag off">\u5DF2\u88C1\u526A</span>') + '</td><td><div class="mono">' + esc(t.originalName || "\u2014") + '</div><div class="dp-mcp-tool-mapped mono">' + esc(t.mappedName || "\u2014") + "</div></td><td>" + sideEffectTag(t.sideEffect) + "</td><td>" + (t.approvalRequired ? '<span class="tag approval">\u9700\u5BA1\u6279</span>' : '<span class="tag off">\u514D\u5BA1\u6279</span>') + "</td><td>" + esc(shortErr(t.description || "\u2014", 90)) + '<button class="dp-mcp-link" type="button" data-act="toolDetail" data-tool="' + esc(t.originalName) + '">' + (open ? "\u6536\u8D77\u53C2\u6570" : "\u53C2\u6570\u4E0E\u6765\u6E90") + '</button></td></tr><tr class="dp-mcp-tool-detail"' + (open ? "" : " hidden") + '><td colspan="5"><div class="dp-mcp-tool-meta"><div><b>\u8F93\u5165\u53C2\u6570</b><pre>' + esc(schema) + "</pre></div><div><b>\u751F\u6548\u57DF</b> " + esc(domains) + " \xB7 <b>\u5E42\u7B49</b> " + (t.idempotent ? "\u662F" : "\u5426") + ' \xB7 <b>\u6765\u6E90</b> <span class="mono">' + esc(t.source || "\u2014") + "</span></div></div></td></tr>";
        }).join("") + "</tbody></table></div>"
      );
    }
    function renderAuth(c) {
      var need = statusOf(c) === "NEEDS_AUTH";
      setHtml(
        "paneAuth",
        '<div class="dp-mcp-kv">' + kv("\u5F53\u524D\u72B6\u6001", chip(c)) + kv("\u6388\u6743\u6311\u6218", esc(c.authChallenge || "\u65E0")) + kv("client_id", esc(c.oauthClientId || "\u672A\u767B\u8BB0\uFF08\u8D70\u52A8\u6001\u6CE8\u518C\uFF09")) + kv("client_secret", c.hasOauthClientSecret ? "\u5DF2\u914D\u7F6E\uFF08\u4E0D\u56DE\u663E\uFF09" : "\u672A\u914D\u7F6E") + kv("client \u540D\u79F0", esc(c.oauthClientName || "\u2014")) + kv("scope", esc(c.oauthScope || "\u670D\u52A1\u5668\u9ED8\u8BA4")) + kv("\u56DE\u8C03\u5730\u5740", esc(c.oauthRedirectUri || "\u5F53\u524D\u7BA1\u63A7\u53F0\u5730\u5740")) + '</div><div class="dp-mcp-pane-actions"><button class="btn btn-sm btn-outline-primary" type="button" data-act="oauth">' + (need ? "\u53D1\u8D77\u6388\u6743" : "\u91CD\u65B0\u6388\u6743") + "</button></div>"
      );
    }
    function renderBind(c) {
      if (state.bindFor !== c.name || !state.bindTouched) {
        state.bindPick = boundOf(c.name).slice();
        state.bindFor = c.name;
      }
      if (!state.domains.length) {
        setHtml("paneBind", '<div class="dp-mcp-none">\u8FD8\u6CA1\u6709\u4EFB\u4F55\u57DF</div>');
        return;
      }
      var current = boundOf(c.name);
      var adds = state.bindPick.filter(function(d) {
        return current.indexOf(d) < 0;
      });
      var removes = current.filter(function(d) {
        return state.bindPick.indexOf(d) < 0;
      });
      var diff = adds.length || removes.length ? "\u65B0\u589E " + (adds.length ? esc(adds.join("\u3001")) : "\u65E0") + " \xB7 \u79FB\u9664 " + (removes.length ? esc(removes.join("\u3001")) : "\u65E0") : "\u672A\u6539\u52A8";
      setHtml(
        "paneBind",
        '<div class="dp-mcp-bind-grid">' + state.domains.map(function(d) {
          var on = state.bindPick.indexOf(d.name) >= 0;
          return '<div class="dp-mcp-bind-card' + (on ? " on" : "") + '" data-pick="' + esc(d.name) + '"><span class="mono">' + esc(d.name) + "</span>" + (d.callable ? "" : '<span class="tag approval">\u88C5\u914D</span>') + "</div>";
        }).join("") + '</div><div class="dp-mcp-diff">' + diff + '</div><div class="dp-mcp-pane-actions"><button class="btn btn-sm btn-outline-primary" type="button" data-act="saveBind">\u4FDD\u5B58\u7ED1\u5B9A</button><button class="btn btn-sm btn-outline-secondary" type="button" data-act="bindClear">\u5168\u90E8\u89E3\u7ED1</button></div>'
      );
    }
    function showFieldError(id, msg) {
      var n = el(id);
      if (!n || !n.closest) {
        return;
      }
      var row = n.closest(".f-row");
      if (!row) {
        return;
      }
      var old = row.querySelector(".dp-mcp-ferr");
      if (old) {
        old.remove();
      }
      if (msg) {
        row.insertAdjacentHTML("beforeend", '<div class="dp-mcp-ferr">' + esc(msg) + "</div>");
      }
    }
    function clearFieldErrors() {
      var box = el("editModal");
      if (box) {
        box.querySelectorAll(".dp-mcp-ferr").forEach(function(n) {
          n.remove();
        });
      }
    }
    function renderAllowTools() {
      if (state.toolsError) {
        setHtml("allowTools", '<div class="dp-mcp-none">\u5DE5\u5177\u76EE\u5F55\u8BFB\u53D6\u5931\u8D25\uFF0C\u4FDD\u5B58\u540E\u53EF\u91CD\u8BD5</div>');
        return;
      }
      if (!state.tools.length) {
        setHtml("allowTools", '<div class="dp-mcp-none">\u8BE5\u8FDE\u63A5\u6682\u65E0\u5DE5\u5177\u76EE\u5F55</div>');
        return;
      }
      setHtml("allowTools", state.tools.map(function(t) {
        var on = state.allowPick.indexOf(t.originalName) >= 0;
        return '<label class="dp-mcp-allow-item"><input type="checkbox" data-allow="' + esc(t.originalName) + '"' + (on ? " checked" : "") + '><span class="mono">' + esc(t.originalName) + "</span>" + sideEffectTag(t.sideEffect) + "</label>";
      }).join(""));
    }
    function openCreate() {
      state.editName = "";
      state.editing = true;
      setText("editTitle", "\u65B0\u589E MCP \u8FDE\u63A5");
      setVal("f_name", "");
      disable("f_name", false);
      setVal("f_url", "");
      setVal("f_remark", "");
      setChecked("f_enabled", true);
      setVal("f_transport", "STREAMABLE_HTTP");
      setVal("f_timeout", "30000");
      setVal("f_headers", "");
      setHtml("headerState", "");
      setVal("f_approval", "ALL");
      setVal("f_oauth_cid", "");
      setVal("f_oauth_secret", "");
      setVal("f_oauth_cname", "");
      setVal("f_oauth_scope", "");
      setVal("f_oauth_redirect", "");
      state.allowPick = [];
      state.tools = [];
      state.toolsFor = "";
      state.toolsError = "";
      setHtml("allowTools", '<div class="dp-mcp-none">\u4FDD\u5B58\u540E\u53EF\u8BFB\u53D6\u5DE5\u5177\u76EE\u5F55\u5E76\u52FE\u9009\u767D\u540D\u5355</div>');
      clearFieldErrors();
      setHidden("editModal", false);
      document.body.style.overflow = "hidden";
    }
    function openEdit(name) {
      var c = findConn(name);
      if (!c) {
        return;
      }
      state.editName = name;
      state.editing = true;
      setText("editTitle", "\u7F16\u8F91 MCP \u8FDE\u63A5");
      setVal("f_name", c.name);
      disable("f_name", true);
      setVal("f_url", c.url || "");
      setVal("f_remark", c.remark || "");
      setChecked("f_enabled", c.enabled !== false);
      setVal("f_transport", c.transport || "STREAMABLE_HTTP");
      setVal("f_timeout", c.callTimeoutMs || 3e4);
      setVal("f_headers", "");
      setHtml("headerState", c.hasHeaders ? "<span>\u5DF2\u914D\u7F6E\u8BF7\u6C42\u5934\uFF08\u4E0D\u56DE\u663E\uFF09</span>" : "");
      setVal("f_approval", c.approvalPolicy || "ALL");
      setVal("f_oauth_cid", c.oauthClientId || "");
      setVal("f_oauth_secret", "");
      setVal("f_oauth_cname", c.oauthClientName || "");
      setVal("f_oauth_scope", c.oauthScope || "");
      setVal("f_oauth_redirect", c.oauthRedirectUri || "");
      state.allowPick = (c.toolAllowlist || []).slice();
      clearFieldErrors();
      setHidden("editModal", false);
      document.body.style.overflow = "hidden";
      setHtml("allowTools", Console.loadingHtml("\u6B63\u5728\u8BFB\u53D6\u5DE5\u5177\u76EE\u5F55\u2026"));
      loadTools(name);
    }
    function closeEdit() {
      state.editing = false;
      setHidden("editModal", true);
      document.body.style.overflow = "";
    }
    function validate() {
      clearFieldErrors();
      var name = val("f_name"), url = val("f_url"), t = val("f_timeout");
      var ok = true;
      if (!name) {
        showFieldError("f_name", "\u8FDE\u63A5\u540D\u4E0D\u80FD\u4E3A\u7A7A");
        ok = false;
      } else if (name.indexOf("__") >= 0) {
        showFieldError("f_name", "\u4E0D\u80FD\u5305\u542B\u8FDE\u7EED\u53CC\u4E0B\u5212\u7EBF");
        ok = false;
      }
      if (!/^https?:\/\//i.test(url)) {
        showFieldError("f_url", "\u8BF7\u586B\u5199 http(s) \u5730\u5740");
        ok = false;
      }
      if (t && (!/^\d+$/.test(t) || Number(t) <= 0)) {
        showFieldError("f_timeout", "\u8D85\u65F6\u5FC5\u987B\u662F\u6B63\u6574\u6570\u6BEB\u79D2");
        ok = false;
      }
      return ok;
    }
    function saveEdit() {
      if (!validate()) {
        return Promise.resolve();
      }
      var raw = val("f_headers");
      var headers = {};
      if (raw) {
        try {
          headers = JSON.parse(raw);
          if (!headers || typeof headers !== "object" || Array.isArray(headers)) {
            throw new Error("\u9700\u8981 JSON \u5BF9\u8C61");
          }
        } catch (e) {
          showFieldError("f_headers", "\u4E0D\u662F\u5408\u6CD5 JSON \u5BF9\u8C61\uFF1A" + e.message);
          return Promise.resolve();
        }
      }
      var t = parseInt(val("f_timeout"), 10);
      var body = {
        name: val("f_name"),
        url: val("f_url"),
        transport: val("f_transport"),
        approvalPolicy: val("f_approval"),
        headers,
        callTimeoutMs: isNaN(t) ? 3e4 : t,
        enabled: checked("f_enabled"),
        toolAllowlist: state.allowPick.slice(),
        remark: val("f_remark"),
        oauthClientId: val("f_oauth_cid"),
        oauthClientSecret: val("f_oauth_secret"),
        oauthClientName: val("f_oauth_cname"),
        oauthScope: val("f_oauth_scope"),
        oauthRedirectUri: val("f_oauth_redirect")
      };
      return Console.api.post("/admin/mcp-connections", body).then(function(r) {
        closeEdit();
        var msg;
        if (r && r.ready) {
          msg = "\u5DF2\u4FDD\u5B58\u5E76\u8FDE\u63A5\u5C31\u7EEA\uFF0C\u5DE5\u5177 " + num(r.toolCount) + " \u4E2A";
        } else if (r && r.needsAuth) {
          msg = "\u5DF2\u4FDD\u5B58\uFF0C\u9700\u8981\u6388\u6743";
        } else if (r && r.lastError) {
          msg = "\u5DF2\u4FDD\u5B58\uFF0C\u8FDE\u63A5\u672A\u5C31\u7EEA\uFF1A" + shortErr(r.lastError, 90);
        } else {
          msg = r && r.message || "\u5DF2\u4FDD\u5B58";
        }
        Console.toast(msg, { type: "success", wide: true });
        load();
      }).catch(function(e) {
        Console.toast("\u4FDD\u5B58\u5931\u8D25\uFF1A" + errText(e), { type: "error", wide: true });
      });
    }
    function toggleConn(c) {
      var to = c.enabled === false;
      return Console.api.post(
        "/admin/mcp-connections/" + encodeURIComponent(c.name) + "/toggle",
        { enabled: to }
      ).then(function() {
        Console.toast(to ? "\u5DF2\u542F\u7528" : "\u5DF2\u505C\u7528", { type: "success" });
        load();
      }).catch(function(e) {
        Console.toast("\u64CD\u4F5C\u5931\u8D25\uFF1A" + errText(e), { type: "error" });
      });
    }
    function testConn(c) {
      return Console.api.post("/admin/mcp-connections/" + encodeURIComponent(c.name) + "/test", null).then(function(r) {
        var lines = [r && r.message || "\u6D4B\u8BD5\u5B8C\u6210"];
        if (r && r.tools && r.tools.length) {
          lines.push("\u5DE5\u5177\uFF1A" + r.tools.join(", "));
        }
        if (r && r.lastError && !r.ready) {
          lines.push("\u9519\u8BEF\uFF1A" + r.lastError);
        }
        Console.toast(lines.join("\n"), { type: r && r.ready ? "success" : "error", wide: true });
        load();
      }).catch(function(e) {
        Console.toast("\u6D4B\u8BD5\u5931\u8D25\uFF1A" + errText(e), { type: "error", wide: true });
      });
    }
    function delConn(c) {
      return Console.confirm(
        "\u786E\u8BA4\u5220\u9664 MCP \u8FDE\u63A5 " + c.name + "\uFF1F\n\n\u5176\u5DE5\u5177\u4F1A\u88AB\u6458\u9664\u3001\u7ED1\u5B9A\u4F1A\u89E3\u7ED1\uFF08\u4E0D\u5220\u57DF\uFF09\u3002",
        { title: "\u5220\u9664 MCP \u8FDE\u63A5", okText: "\u5220\u9664", danger: true }
      ).then(function(ok) {
        if (!ok) {
          return;
        }
        return Console.api.del("/admin/mcp-connections?name=" + encodeURIComponent(c.name)).then(function() {
          state.selected = "";
          load();
        }).catch(function(e) {
          Console.toast("\u5220\u9664\u5931\u8D25\uFF1A" + errText(e), { type: "error" });
        });
      });
    }
    function startOAuth(name) {
      return Console.api.post("/admin/mcp-connections/" + encodeURIComponent(name) + "/oauth/start", null).then(function(r) {
        if (r && r.authorizeUrl) {
          location.href = r.authorizeUrl;
          return;
        }
        throw new Error("\u6388\u6743\u7AEF\u70B9\u672A\u8FD4\u56DE\u6388\u6743\u94FE\u63A5");
      }).catch(function(e) {
        Console.toast("\u53D1\u8D77\u6388\u6743\u5931\u8D25\uFF1A" + errText(e), { type: "error" });
      });
    }
    function saveBind() {
      var c = findConn(state.selected);
      if (!c) {
        return;
      }
      var name = c.name;
      var calls = [];
      state.domains.forEach(function(d) {
        var cur = (state.bindings.domainBindings[d.name] || []).slice();
        var want = state.bindPick.indexOf(d.name) >= 0;
        var has = cur.indexOf(name) >= 0;
        if (want === has) {
          return;
        }
        var next = want ? cur.concat([name]) : cur.filter(function(x) {
          return x !== name;
        });
        calls.push({
          domain: d.name,
          p: Console.api.put("/admin/mcp-bindings/" + encodeURIComponent(d.name), { connections: next })
        });
      });
      if (!calls.length) {
        Console.toast("\u6CA1\u6709\u9700\u8981\u4FDD\u5B58\u7684\u6539\u52A8", { type: "success" });
        return;
      }
      Promise.all(calls.map(function(x) {
        return x.p.then(function() {
          return null;
        }).catch(function(e) {
          return x.domain + "\uFF1A" + errText(e);
        });
      })).then(function(errs) {
        var bad = errs.filter(Boolean);
        Console.toast(
          bad.length ? "\u90E8\u5206\u57DF\u4FDD\u5B58\u5931\u8D25\uFF1A\n" + bad.join("\n") : "\u7ED1\u5B9A\u5DF2\u4FDD\u5B58",
          { type: bad.length ? "error" : "success", wide: true }
        );
        state.bindTouched = false;
        load();
      });
    }
    function onAct(act, btn) {
      var c = findConn(state.selected);
      if (!c) {
        return;
      }
      if (act === "test") {
        Console.busy(btn, function() {
          return testConn(c);
        }, "\u6D4B\u8BD5\u4E2D\u2026");
      } else if (act === "edit") {
        openEdit(c.name);
      } else if (act === "toggle") {
        Console.busy(btn, function() {
          return toggleConn(c);
        }, "\u5904\u7406\u4E2D\u2026");
      } else if (act === "delete") {
        delConn(c);
      } else if (act === "oauth") {
        startOAuth(c.name);
      } else if (act === "reloadTools") {
        state.toolsFor = "";
        loadTools(c.name);
      } else if (act === "toolDetail") {
        var tool = btn.getAttribute("data-tool") || "";
        state.toolDetail = state.toolDetail === tool ? "" : tool;
        renderTools(c);
      } else if (act === "editAllow") {
        openEdit(c.name);
      } else if (act === "saveBind") {
        Console.busy(btn, function() {
          saveBind();
          return Promise.resolve();
        }, "\u4FDD\u5B58\u4E2D\u2026");
      } else if (act === "bindClear") {
        state.bindTouched = true;
        state.bindPick = [];
        renderBind(c);
      }
    }
    function actTarget(e) {
      var t = e.target;
      return t && t.closest ? t.closest("[data-act]") : null;
    }
    function bindActions() {
      var create = el("createBtn");
      if (create) {
        create.addEventListener("click", openCreate);
      }
      var refresh = el("refreshBtn");
      if (refresh) {
        refresh.addEventListener("click", function() {
          load();
        });
      }
      var search = el("connSearch");
      if (search) {
        search.addEventListener("input", renderList);
      }
      var filter = el("statusFilter");
      if (filter) {
        filter.addEventListener("change", renderList);
      }
      var list = el("connList");
      if (list) {
        list.addEventListener("click", function(e) {
          var t = e.target;
          var row = t && t.closest ? t.closest(".dp-mcp-item") : null;
          if (!row) {
            return;
          }
          state.selected = row.getAttribute("data-name");
          list.querySelectorAll(".dp-mcp-item").forEach(function(r) {
            r.classList.toggle("on", r === row);
          });
          state.bindTouched = false;
          state.toolsFor = "";
          state.toolDetail = "";
          renderDetail();
        });
      }
      var tabs = el("tabBar");
      if (tabs) {
        tabs.addEventListener("click", function(e) {
          var t = e.target;
          var b = t && t.closest ? t.closest("[data-tab]") : null;
          if (b) {
            selectTab(b.getAttribute("data-tab"));
          }
        });
      }
      ["statusBar", "paneOverview", "paneTools", "paneAuth", "paneBind"].forEach(function(id) {
        var box = el(id);
        if (!box) {
          return;
        }
        box.addEventListener("click", function(e) {
          var b = actTarget(e);
          if (b) {
            onAct(b.getAttribute("data-act"), b);
          }
        });
      });
      var bindPane = el("paneBind");
      if (bindPane) {
        bindPane.addEventListener("click", function(e) {
          var t = e.target;
          var card = t && t.closest ? t.closest("[data-pick]") : null;
          if (!card) {
            return;
          }
          var d = card.getAttribute("data-pick");
          var i = state.bindPick.indexOf(d);
          if (i >= 0) {
            state.bindPick.splice(i, 1);
          } else {
            state.bindPick.push(d);
          }
          state.bindTouched = true;
          var c = findConn(state.selected);
          if (c) {
            renderBind(c);
          }
        });
      }
      var allow = el("allowTools");
      if (allow) {
        allow.addEventListener("change", function(e) {
          var t = e.target;
          if (!t || !t.getAttribute("data-allow")) {
            return;
          }
          var name = t.getAttribute("data-allow");
          var i = state.allowPick.indexOf(name);
          if (t.checked && i < 0) {
            state.allowPick.push(name);
          }
          if (!t.checked && i >= 0) {
            state.allowPick.splice(i, 1);
          }
        });
      }
      var editClose = el("editClose");
      if (editClose) {
        editClose.addEventListener("click", closeEdit);
      }
      var editSave = el("editSaveBtn");
      if (editSave) {
        editSave.addEventListener("click", function() {
          Console.busy(this, saveEdit, "\u4FDD\u5B58\u4E2D\u2026");
        });
      }
      var editClear = el("editClearBtn");
      if (editClear) {
        editClear.addEventListener("click", openCreate);
      }
      var editModal = el("editModal");
      if (editModal) {
        editModal.addEventListener("click", function(e) {
          if (e.target === editModal) {
            closeEdit();
          }
        });
      }
    }
    function load() {
      setHtml("connList", Console.loadingHtml("\u6B63\u5728\u52A0\u8F7D MCP \u8FDE\u63A5\u2026"));
      return Promise.all([
        Console.api.get("/admin/mcp-connections").catch(function(e) {
          return { __error: e };
        }),
        Console.api.get("/admin/mcp-bindings").catch(function() {
          return { domainBindings: {}, connectionBindings: {} };
        }),
        Console.api.get("/admin/domains").catch(function() {
          return { domains: [] };
        })
      ]).then(function(rs) {
        if (rs[0] && rs[0].__error) {
          setHtml("connList", '<div class="dp-mcp-err">\u8BFB\u53D6\u5931\u8D25\uFF1A' + esc(errText(rs[0].__error)) + "</div>");
          return;
        }
        state.conns = rs[0] && rs[0].connections || [];
        state.settingsFile = rs[0] && rs[0].settingsFile || "";
        state.bindings = rs[1] || { domainBindings: {}, connectionBindings: {} };
        state.domains = rs[2] && rs[2].domains || [];
        state.lastSync = Date.now();
        if (!findConn(state.selected)) {
          state.selected = state.conns.length ? state.conns[0].name : "";
        }
        state.bindTouched = false;
        state.listSig = state.conns.map(function(c) {
          return c.name;
        }).join("");
        renderStats();
        renderList();
        setText("syncHint", "\u4E0A\u6B21\u540C\u6B65 " + timeOf(state.lastSync));
        renderDetail();
      });
    }
    function poll() {
      if (!checked("autoRefresh") || state.editing) {
        return;
      }
      return Console.api.get("/admin/mcp-connections").then(function(r) {
        state.conns = r && r.connections || [];
        state.lastSync = Date.now();
        patchStatus();
      }).catch(function() {
      });
    }
    Console.renderSidebar("mcp");
    Console.ready(function() {
      var q = new URLSearchParams(location.search);
      var auth = q.get("auth");
      if (auth === "ok") {
        Console.toast(
          "\u6388\u6743\u6210\u529F\uFF1A\u8FDE\u63A5 " + (q.get("name") || "") + " \u5DF2\u5C31\u7EEA\uFF0C\u5DE5\u5177\u5BF9\u7ED1\u5B9A\u57DF\u53EF\u89C1",
          { type: "success" }
        );
      } else if (auth === "fail") {
        Console.toast("\u6388\u6743\u5931\u8D25\uFF1A" + (q.get("reason") || "\u672A\u77E5\u539F\u56E0"), { type: "error" });
      }
      if (auth) {
        history.replaceState(null, "", location.pathname);
      }
      bindActions();
      load().then(function() {
        setInterval(poll, POLL_MS);
      });
    });
  })();
})();

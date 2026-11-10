(() => {
  var __async = (__this, __arguments, generator) => {
    return new Promise((resolve, reject) => {
      var fulfilled = (value) => {
        try {
          step(generator.next(value));
        } catch (e) {
          reject(e);
        }
      };
      var rejected = (value) => {
        try {
          step(generator.throw(value));
        } catch (e) {
          reject(e);
        }
      };
      var step = (x) => x.done ? resolve(x.value) : Promise.resolve(x.value).then(fulfilled, rejected);
      step((generator = generator.apply(__this, __arguments)).next());
    });
  };

  // domainplane-server/frontend/src/pages/instances.js
  (function() {
    var AUTO_INTERVAL = 1e4;
    var timer = null;
    var TOOLS = [];
    var openName = null;
    var dirty = false;
    var toolSeq = 0;
    function esc(s) {
      return Console.escapeHtml(s == null ? "" : String(s));
    }
    function fmtTime(ms) {
      if (!ms) {
        return "\u2014";
      }
      var d = new Date(ms);
      var p = function(n) {
        return (n < 10 ? "0" : "") + n;
      };
      return p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
    }
    function fmtSilent(ms) {
      if (ms == null) {
        return "";
      }
      if (ms < 1e3) {
        return "\u521A\u521A";
      }
      var s = Math.floor(ms / 1e3);
      if (s < 60) {
        return s + " \u79D2\u524D";
      }
      var m = Math.floor(s / 60);
      if (m < 60) {
        return m + " \u5206\u949F\u524D";
      }
      return Math.floor(m / 60) + " \u5C0F\u65F6\u524D";
    }
    function el(id) {
      return document.getElementById(id);
    }
    function stateTag(it) {
      var cls = it.state === "ONLINE" ? "read" : it.rejecting ? "destructive" : "write";
      var label = it.stateLabel || it.state;
      if (it.rejecting) {
        label += "\uFF08\u91CD\u8FDE\u8FD4 410\uFF09";
      }
      if (it.muted) {
        label += "\uFF08\u5DE5\u5177\u4E0D\u53EF\u7528\uFF09";
      }
      return '<span class="tag ' + cls + '">' + esc(label) + "</span>";
    }
    function actionCell(it) {
      var id = esc(it.instanceId);
      if (it.rejecting) {
        return '<span class="muted">\u5DF2\u540A\u9500\uFF08\u53EA\u80FD\u7531\u63A5\u53E3\u6062\u590D\uFF09</span>';
      }
      if (it.muted) {
        return '<button class="btn btn-sm btn-outline-secondary" data-act="restore" data-id="' + id + '" title="\u5B9E\u4F8B\u4ECD\u5728\u5FC3\u8DF3\uFF0C\u6062\u590D\u5373\u7B49\u5F85\u4E0B\u6B21\u5FC3\u8DF3\u91CD\u5EFA\u526F\u672C">\u6062\u590D\u5DE5\u5177\u8C03\u7528</button>';
      }
      return '<button class="btn btn-sm btn-outline-secondary" data-act="mute" data-id="' + id + '" title="\u6458\u9664\u5DE5\u5177\u526F\u672C\uFF0C\u5FC3\u8DF3\u7167\u5E38\u53D7\u7406">\u65AD\u5F00\u5DE5\u5177\u8C03\u7528</button>';
    }
    function renderInstances(data) {
      var stats = data.stats || {};
      var list = data.instances || [];
      var online = el("statOnline");
      online.textContent = stats.onlineCount || 0;
      online.className = "dp-stat-value" + (stats.onlineCount || 0 ? "" : " warn");
      var note = [];
      if (stats.mutedCount) {
        note.push(stats.mutedCount + " \u4E2A\u5DF2\u7194\u65AD");
      }
      if (stats.drainingCount) {
        note.push(stats.drainingCount + " \u4E2A\u6E05\u7406\u4E2D");
      }
      if (stats.forceOfflineCount) {
        note.push(stats.forceOfflineCount + " \u4E2A\u5DF2\u5F3A\u5236\u4E0B\u7EBF");
      }
      if (!note.length) {
        note.push((stats.instanceCount || 0) + " \u6761\u5728\u7EBF\u8868\u8BB0\u5F55");
      }
      el("statOnlineNote").textContent = note.join("\uFF0C");
      el("statReplica").textContent = stats.remoteReplicaCount || 0;
      el("statReplica").className = "dp-stat-value" + (stats.remoteReplicaCount || 0 ? "" : " warn");
      el("statReplicaNote").textContent = "\u540C\u540D\u591A\u5B9E\u4F8B = \u591A\u526F\u672C\uFF0C\u8C03\u7528\u65F6\u968F\u673A\u9009\u4E00\u4E2A";
      el("statTools").textContent = stats.registeredToolCount || 0;
      el("statTools").className = "dp-stat-value";
      el("statToolsNote").textContent = "\u903B\u8F91\u5DE5\u5177\u6761\u76EE\u6570\uFF08\u542B\u672C\u5730 Bean \u5DE5\u5177\uFF09";
      el("statTimeout").textContent = (stats.timeoutSeconds || 0) + " \u79D2";
      el("statTimeout").className = "dp-stat-value";
      var body = el("instanceBody");
      var wrap = el("tableWrap");
      var empty = el("emptyHint");
      if (!list.length) {
        body.innerHTML = "";
        wrap.style.display = "none";
        empty.style.display = "block";
        return;
      }
      wrap.style.display = "block";
      empty.style.display = "none";
      body.innerHTML = list.map(function(it) {
        var tools = (it.toolNames || []).map(function(n) {
          return '<span class="tag perm">' + esc(n) + "</span>";
        }).join("");
        return '<tr><td class="mono">' + esc(it.instanceId) + '</td><td class="mono">' + esc(it.endpoint) + "</td><td>" + stateTag(it) + "</td><td>" + fmtTime(it.lastSeen) + '<div class="muted">' + esc(fmtSilent(it.silentMillis)) + '</div></td><td class="tools-cell">' + (tools || '<span class="muted">0</span>') + '<div class="muted">\u5171 ' + (it.toolCount || 0) + ' \u4E2A</div></td><td class="mono muted">' + esc(it.digest || "\u2014") + "</td><td>" + actionCell(it) + "</td></tr>";
      }).join("");
      body.querySelectorAll("button[data-act]").forEach(function(btn) {
        btn.addEventListener("click", function() {
          act(btn.getAttribute("data-act"), btn.getAttribute("data-id"), btn);
        });
      });
    }
    var loadSeq = 0;
    var LOADED = false;
    function load() {
      var state = el("loadState");
      var seq = ++loadSeq;
      if (!LOADED) {
        el("tableWrap").style.display = "block";
        el("instanceBody").innerHTML = Console.loadingRowHtml(7, "\u6B63\u5728\u8BFB\u53D6\u5B9E\u4F8B\u2026");
        state.textContent = "\u6B63\u5728\u8BFB\u53D6\u2026";
      }
      return Console.api.get("/admin/instances").then(function(data) {
        if (seq !== loadSeq) {
          return;
        }
        LOADED = true;
        renderInstances(data);
        el("updatedAt").textContent = "\u66F4\u65B0\u4E8E " + fmtTime(Date.now());
        state.textContent = "";
      }).catch(function(e) {
        if (seq !== loadSeq) {
          return;
        }
        state.textContent = "\u8BFB\u53D6\u5931\u8D25\uFF08\u4E0B\u9762\u4ECD\u662F\u4E0A\u6B21\u6210\u529F\u7684\u6570\u636E\uFF09\uFF1A" + e.message;
      });
    }
    var ACTIONS = {
      mute: {
        text: "\u65AD\u5F00\u5B9E\u4F8B\u300C%s\u300D\u7684\u5DE5\u5177\u8C03\u7528\uFF1F\n\n\xB7 \u7ACB\u5373\u6458\u9664\u5B83\u6CE8\u518C\u7684\u5168\u90E8\u5DE5\u5177\u526F\u672C\uFF0C\u6A21\u578B\u4E0D\u518D\u770B\u5230\u8FD9\u4E9B\u5DE5\u5177\n\xB7 \u5FC3\u8DF3\u7EE7\u7EED\u53D7\u7406\uFF1A\u5B83\u7684\u8FDB\u7A0B\u4E0D\u4F1A\u88AB\u6740\u3001\u8FDE\u63A5\u4E5F\u4E0D\u4F1A\u88AB\u6390\u65AD\n\xB7 \u968F\u65F6\u53EF\u5728\u63A7\u5236\u53F0\u300C\u6062\u590D\u300D\uFF1B\u5B83\u81EA\u5DF1\u91CD\u542F\u4E5F\u4F1A\u81EA\u52A8\u91CD\u8FDE\u56DE\u6765\n\xB7 \u82E5\u67D0\u4E2A\u57DF\u53EA\u7531\u5B83\u58F0\u660E\uFF0C\u8BE5\u57DF\u968F\u4E4B\u6D88\u5931\uFF08\u518D\u7528\u6B64\u57DF\u8BF7\u6C42\u4F1A\u62A5 10004\uFF09\n\xB7 \u6B63\u5728\u8DD1\u7684\u4F1A\u8BDD\u4E0D\u53D7\u5F71\u54CD\uFF0C\u4E0B\u4E00\u8F6E\u8D77\u770B\u5230\u7684\u5DE5\u5177\u96C6\u5DF2\u6536\u7F29"
      },
      restore: {
        text: "\u6062\u590D\u5B9E\u4F8B\u300C%s\u300D\u7684\u5DE5\u5177\u8C03\u7528\uFF1F\n\n\xB7 \u526F\u672C\u5728\u5B83\u7684\u4E0B\u4E00\u6B21\u5FC3\u8DF3\u65F6\u91CD\u5EFA\uFF08\u9ED8\u8BA4 10 \u79D2\u5185\uFF09\n\xB7 \u5FC3\u8DF3\u5DF2\u505C\u7684\u5B9E\u4F8B\u6062\u590D\u4E0D\u4E86\uFF0C\u90A3\u79CD\u60C5\u51B5\u5B83\u6CA1\u6709\u5DE5\u5177\u53EF\u8C03\uFF0C\u7B49\u8D85\u65F6\u6E05\u8868\u5373\u53EF"
      }
    };
    function act(action, instanceId, btn) {
      return __async(this, null, function* () {
        var spec = ACTIONS[action];
        if (!spec) {
          return;
        }
        var ok = yield Console.confirm(spec.text.replace("%s", instanceId), {
          title: action === "mute" ? "\u65AD\u5F00\u5DE5\u5177\u8C03\u7528" : "\u6062\u590D\u5DE5\u5177\u8C03\u7528",
          okText: "\u786E\u8BA4",
          danger: action === "mute"
        });
        if (!ok) {
          return;
        }
        Console.busy(btn, function() {
          return Console.api.post("/admin/instances/" + encodeURIComponent(instanceId) + "/" + action).then(function(r) {
            Console.toast(r.message || "\u5DF2\u63D0\u4EA4", { type: "success" });
            load();
          }).catch(function(e) {
            Console.toast("\u64CD\u4F5C\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message), { type: "error" });
          });
        }, action === "mute" ? "\u65AD\u5F00\u4E2D\u2026" : "\u6062\u590D\u4E2D\u2026");
      });
    }
    function setupAutoRefresh() {
      var box = el("autoRefresh");
      function sync() {
        if (timer) {
          clearInterval(timer);
          timer = null;
        }
        if (box.checked) {
          timer = setInterval(function() {
            if (!document.hidden) {
              load();
            }
          }, AUTO_INTERVAL);
        }
      }
      box.addEventListener("change", sync);
      sync();
    }
    function findTool(name) {
      for (var i = 0; i < TOOLS.length; i++) {
        if (TOOLS[i].name === name) {
          return TOOLS[i];
        }
      }
      return null;
    }
    function findDetail(name) {
      var rows = el("toolBody").querySelectorAll("tr[data-detail]");
      for (var i = 0; i < rows.length; i++) {
        if (rows[i].getAttribute("data-detail") === name) {
          return rows[i];
        }
      }
      return null;
    }
    function kindTag(kind) {
      var label = kind === "LOCAL" ? "\u5185\u7F6E" : kind === "MCP" ? "MCP" : "\u8FDC\u7A0B";
      var cls = kind === "LOCAL" ? "kind-local" : kind === "MCP" ? "kind-mcp" : "kind-remote";
      return '<span class="kind-tag ' + cls + '">' + label + "</span>";
    }
    function paramRow(p, editable) {
      var indent = (p.depth || 0) * 18;
      var head = '<div class="param-label"><code>' + esc(p.path) + '</code> <span class="muted">' + esc(p.type) + "</span>" + (p.overridden ? ' <span class="kind-tag kind-local">\u5DF2\u6539</span>' : "") + "</div>";
      var body = editable ? '<input type="text" class="form-control form-control-sm mono" data-param="' + esc(p.path) + '" value="' + esc(p.description) + '">' : '<div class="mono">' + esc(p.description) + "</div>";
      var baseline = editable && p.overridden ? '<div class="baseline-line">\u539F\u6587\uFF1A' + esc(p.baselineDescription) + "</div>" : "";
      return '<div class="param-row" style="padding-left:' + indent + 'px">' + head + body + baseline + "</div>";
    }
    function detailHtml(t) {
      var editable = !!t.editable;
      var head = '<div class="tool-detail-head">\u5DE5\u5177\u8BF4\u660E' + (t.overridden ? ' <span class="kind-tag kind-local">\u5DF2\u6539</span>' : "") + (t.drift ? '<span class="drift-tag">\u4E0A\u6E38\u8BF4\u660E\u5DF2\u53D8\u5316</span>' : "") + "</div>";
      var desc = editable ? '<textarea class="form-control mono tool-desc-input" data-tool-desc rows="3">' + esc(t.description) + "</textarea>" + (t.description !== t.baselineDescription ? '<div class="baseline-line">\u539F\u6587\uFF1A' + esc(t.baselineDescription) + "</div>" : "") : '<div class="mono">' + esc(t.description) + "</div>";
      var params = (t.params || []).map(function(p) {
        return paramRow(p, editable);
      }).join("");
      var foot = editable ? '<div class="tool-detail-foot"><span class="tool-detail-status"></span><button type="button" class="btn btn-sm btn-outline-secondary" data-tool-act="close">\u6536\u8D77</button><button type="button" class="btn btn-sm btn-outline-secondary" data-tool-act="reset"' + (t.overridden ? "" : " disabled") + '>\u6062\u590D\u9ED8\u8BA4</button><button type="button" class="btn btn-sm btn-primary" data-tool-act="save">\u4FDD\u5B58</button></div>' : '<div class="tool-detail-foot"><button type="button" class="btn btn-sm btn-outline-secondary" data-tool-act="close">\u6536\u8D77</button></div>';
      return '<div class="tool-detail">' + head + desc + '<div class="tool-detail-head">\u53C2\u6570\u8BF4\u660E</div>' + (params || '<div class="muted">\u6CA1\u6709\u53C2\u6570</div>') + foot + "</div>";
    }
    function toolRows(t) {
      var name = esc(t.name);
      var domains = (t.domains || []).join("\u3001");
      return '<tr data-tool-row="' + name + '" data-tool="' + name + '"><td class="mono tool-name">' + name + "</td><td>" + kindTag(t.kind) + '</td><td><div class="tool-desc">' + esc(t.description || "\u2014") + '</div></td><td class="muted">' + (t.params || []).length + ' \u4E2A</td><td class="mono muted">' + esc(domains || "\u2014") + '</td><td class="mono muted">' + (t.replicaCount || 0) + '</td><td><button type="button" class="btn btn-sm btn-outline-secondary" data-tool-act="toggle" data-tool="' + name + '">' + (t.editable ? "\u7F16\u8F91" : "\u67E5\u770B") + '</button></td></tr><tr class="tool-detail-row" data-detail="' + name + '" style="display:none;"><td colspan="7">' + detailHtml(t) + "</td></tr>";
    }
    function matches(t, kind, q) {
      if (kind && t.kind !== kind) {
        return false;
      }
      if (q) {
        var hay = (String(t.name) + " " + String(t.description || "")).toLowerCase();
        if (hay.indexOf(q) < 0) {
          return false;
        }
      }
      return true;
    }
    function applyView() {
      var kind = el("toolKind").value;
      var q = (el("toolSearch").value || "").trim().toLowerCase();
      var body = el("toolBody");
      body.querySelectorAll("tr[data-tool-row]").forEach(function(row) {
        var t = findTool(row.getAttribute("data-tool"));
        row.style.display = t && matches(t, kind, q) ? "" : "none";
      });
      body.querySelectorAll("tr[data-detail]").forEach(function(row) {
        var name = row.getAttribute("data-detail");
        var t = findTool(name);
        row.style.display = t && openName === name && matches(t, kind, q) ? "" : "none";
      });
    }
    function renderTools() {
      var counts = { LOCAL: 0, REMOTE: 0, MCP: 0 };
      TOOLS.forEach(function(t) {
        if (counts[t.kind] != null) {
          counts[t.kind]++;
        }
      });
      el("toolSummary").textContent = "\u5185\u7F6E " + counts.LOCAL + " \xB7 \u8FDC\u7A0B " + counts.REMOTE + " \xB7 MCP " + counts.MCP;
      var body = el("toolBody");
      if (!TOOLS.length) {
        body.innerHTML = '<tr><td colspan="7" class="muted">\u6CE8\u518C\u8868\u91CC\u8FD8\u6CA1\u6709\u5DE5\u5177</td></tr>';
        return;
      }
      body.innerHTML = TOOLS.map(toolRows).join("");
      applyView();
    }
    function loadTools() {
      var seq = ++toolSeq;
      if (!el("toolBody").children.length) {
        el("toolBody").innerHTML = Console.loadingRowHtml(7, "\u6B63\u5728\u8BFB\u53D6\u5DE5\u5177\u2026");
      }
      return Console.api.get("/admin/tool-catalog").then(function(data) {
        if (seq !== toolSeq) {
          return;
        }
        TOOLS = data && data.tools || [];
        if (openName && !findTool(openName)) {
          openName = null;
        }
        renderTools();
        el("toolState").textContent = "\u66F4\u65B0\u4E8E " + fmtTime(Date.now());
      }).catch(function(e) {
        if (seq !== toolSeq) {
          return;
        }
        el("toolState").textContent = "\u8BFB\u53D6\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message);
      });
    }
    function setStatus(detail, text, cls) {
      var box = detail.querySelector(".tool-detail-status");
      if (!box) {
        return;
      }
      box.textContent = text || "";
      box.className = "tool-detail-status" + (cls ? " " + cls : "");
    }
    function saveText(name, btn) {
      var detail = findDetail(name);
      if (!detail) {
        return;
      }
      var desc = detail.querySelector("[data-tool-desc]");
      var paramDescriptions = {};
      detail.querySelectorAll("[data-param]").forEach(function(input) {
        paramDescriptions[input.getAttribute("data-param")] = input.value;
      });
      var payload = {
        description: desc ? desc.value : "",
        paramDescriptions
      };
      Console.busy(btn, function() {
        return Console.api.put("/admin/tool-catalog/" + encodeURIComponent(name) + "/text", payload).then(function(r) {
          dirty = false;
          Console.toast(r.message || "\u5DF2\u4FDD\u5B58", { type: "success" });
          return loadTools();
        }).catch(function(e) {
          setStatus(detail, "\u4FDD\u5B58\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message), "err");
        });
      }, "\u4FDD\u5B58\u4E2D\u2026");
    }
    function resetText(name, btn) {
      Console.confirm("\u6062\u590D\u300C" + name + "\u300D\u7684\u6CE8\u89E3\u539F\u6587\uFF1F\u5F53\u524D\u5199\u7684\u8BF4\u660E\u4F1A\u88AB\u6E05\u9664\u3002", {
        title: "\u6062\u590D\u9ED8\u8BA4",
        okText: "\u6062\u590D",
        danger: true
      }).then(function(ok) {
        if (!ok) {
          return;
        }
        Console.busy(btn, function() {
          return Console.api.del("/admin/tool-catalog/" + encodeURIComponent(name) + "/text").then(function(r) {
            dirty = false;
            Console.toast(r.message || "\u5DF2\u6062\u590D", { type: "success" });
            return loadTools();
          }).catch(function(e) {
            var detail = findDetail(name);
            if (detail) {
              setStatus(detail, "\u6062\u590D\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message), "err");
            }
          });
        }, "\u6062\u590D\u4E2D\u2026");
      });
    }
    function manualReloadTools() {
      if (dirty && openName) {
        Console.confirm("\u6709\u672A\u4FDD\u5B58\u7684\u8BF4\u660E\uFF0C\u91CD\u65B0\u8BFB\u53D6\u4F1A\u4E22\u5F03\u3002\u7EE7\u7EED\uFF1F", {
          title: "\u91CD\u65B0\u8BFB\u53D6",
          okText: "\u7EE7\u7EED",
          danger: true
        }).then(function(ok) {
          if (ok) {
            dirty = false;
            openName = null;
            loadTools();
          }
        });
        return;
      }
      loadTools();
    }
    function bindToolEvents() {
      var body = el("toolBody");
      body.addEventListener("click", function(e) {
        var btn = e.target.closest ? e.target.closest("button[data-tool-act]") : null;
        if (!btn) {
          return;
        }
        var action = btn.getAttribute("data-tool-act");
        var name = btn.getAttribute("data-tool");
        if (!name) {
          var detail = btn.closest("tr[data-detail]");
          name = detail ? detail.getAttribute("data-detail") : null;
        }
        if (!name) {
          return;
        }
        if (action === "toggle") {
          openName = openName === name ? null : name;
          applyView();
        } else if (action === "close") {
          openName = null;
          applyView();
        } else if (action === "save") {
          saveText(name, btn);
        } else if (action === "reset") {
          resetText(name, btn);
        }
      });
      body.addEventListener("input", function(e) {
        if (e.target.matches && e.target.matches("[data-tool-desc], [data-param]")) {
          dirty = true;
        }
      });
      el("toolKind").addEventListener("change", applyView);
      el("toolSearch").addEventListener("input", applyView);
      el("toolRefreshBtn").addEventListener("click", manualReloadTools);
    }
    Console.renderSidebar("instances");
    el("refreshBtn").addEventListener("click", load);
    bindToolEvents();
    Console.ready(function() {
      load();
      loadTools();
      setupAutoRefresh();
    });
  })();
})();

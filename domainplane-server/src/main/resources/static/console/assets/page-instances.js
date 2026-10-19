(() => {
  // domainplane-server/frontend/src/pages/instances.js
  (function() {
    var AUTO_INTERVAL = 1e4;
    var timer = null;
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
    function stateTag(it) {
      var cls = it.state === "ONLINE" ? "read" : it.rejecting ? "destructive" : "write";
      var label = it.stateLabel || it.state;
      if (it.rejecting) {
        label += "\uFF08\u91CD\u8FDE\u8FD4 410\uFF09";
      }
      if (it.muted) {
        label += "\uFF08\u5DE5\u5177\u4E0D\u53EF\u7528\uFF09";
      }
      return '<span class="tag ' + cls + '">' + Console.escapeHtml(label) + "</span>";
    }
    function render(data) {
      var stats = data.stats || {};
      var list = data.instances || [];
      var online = document.getElementById("statOnline");
      online.textContent = stats.onlineCount || 0;
      online.className = "v" + (stats.onlineCount || 0 ? " ok" : " warn");
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
      document.getElementById("statOnlineNote").textContent = note.join("\uFF0C");
      document.getElementById("statReplica").textContent = stats.remoteReplicaCount || 0;
      document.getElementById("statReplica").className = "v" + (stats.remoteReplicaCount || 0 ? " ok" : " warn");
      document.getElementById("statReplicaNote").textContent = "\u540C\u540D\u591A\u5B9E\u4F8B = \u591A\u526F\u672C\uFF0C\u8C03\u7528\u65F6\u968F\u673A\u9009\u4E00\u4E2A";
      document.getElementById("statTools").textContent = stats.registeredToolCount || 0;
      document.getElementById("statTools").className = "v";
      document.getElementById("statToolsNote").textContent = "\u903B\u8F91\u5DE5\u5177\u6761\u76EE\u6570\uFF08\u542B\u672C\u5730 Bean \u5DE5\u5177\uFF09";
      document.getElementById("statTimeout").textContent = (stats.timeoutSeconds || 0) + " \u79D2";
      document.getElementById("statTimeout").className = "v";
      var body = document.getElementById("instanceBody");
      var wrap = document.getElementById("tableWrap");
      var empty = document.getElementById("emptyHint");
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
          return '<span class="tag perm">' + Console.escapeHtml(n) + "</span>";
        }).join("");
        return '<tr><td class="mono">' + Console.escapeHtml(it.instanceId) + '</td><td class="mono">' + Console.escapeHtml(it.endpoint) + "</td><td>" + stateTag(it) + "</td><td>" + fmtTime(it.lastSeen) + '<div class="muted">' + Console.escapeHtml(fmtSilent(it.silentMillis)) + '</div></td><td class="tools-cell">' + (tools || '<span class="muted">0</span>') + '<div class="muted">\u5171 ' + (it.toolCount || 0) + ' \u4E2A</div></td><td class="mono muted">' + Console.escapeHtml(it.digest || "\u2014") + "</td><td>" + actionCell(it) + "</td></tr>";
      }).join("");
      body.querySelectorAll("button[data-act]").forEach(function(btn) {
        btn.addEventListener("click", function() {
          act(btn.getAttribute("data-act"), btn.getAttribute("data-id"));
        });
      });
    }
    function actionCell(it) {
      var id = Console.escapeHtml(it.instanceId);
      if (it.rejecting) {
        return '<span class="muted">\u5DF2\u540A\u9500\uFF08\u53EA\u80FD\u7531\u63A5\u53E3\u6062\u590D\uFF09</span>';
      }
      if (it.muted) {
        return '<button class="primary btn btn-primary" data-act="restore" data-id="' + id + '" title="\u5B9E\u4F8B\u4ECD\u5728\u5FC3\u8DF3\uFF0C\u6062\u590D\u5373\u7B49\u5F85\u4E0B\u6B21\u5FC3\u8DF3\u91CD\u5EFA\u526F\u672C">\u6062\u590D\u5DE5\u5177\u8C03\u7528</button>';
      }
      return '<button class="write btn btn-primary" data-act="mute" data-id="' + id + '" title="\u6458\u9664\u5DE5\u5177\u526F\u672C\uFF0C\u5FC3\u8DF3\u7167\u5E38\u53D7\u7406">\u65AD\u5F00\u5DE5\u5177\u8C03\u7528</button>';
    }
    var loadSeq = 0;
    function load() {
      var state = document.getElementById("loadState");
      var seq = ++loadSeq;
      return Console.api.get("/admin/instances").then(function(data) {
        if (seq !== loadSeq) {
          return;
        }
        render(data);
        state.textContent = "\u66F4\u65B0\u4E8E " + fmtTime(Date.now());
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
    function act(action, instanceId) {
      var spec = ACTIONS[action];
      if (!spec || !confirm(spec.text.replace("%s", instanceId))) {
        return;
      }
      Console.api.post("/admin/instances/" + encodeURIComponent(instanceId) + "/" + action).then(function(r) {
        alert(r.message || "\u5DF2\u63D0\u4EA4");
        load();
      }).catch(function(e) {
        alert("\u64CD\u4F5C\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function setupAutoRefresh() {
      var box = document.getElementById("autoRefresh");
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
    Console.renderSidebar("instances");
    document.getElementById("refreshBtn").addEventListener("click", load);
    Console.ready(function() {
      load();
      setupAutoRefresh();
    });
  })();
})();

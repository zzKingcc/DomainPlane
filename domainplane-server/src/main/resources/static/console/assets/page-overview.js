(() => {
  // domainplane-server/frontend/src/pages/overview.js
  (function() {
    var esc = Console.escapeHtml;
    function setText(id, v) {
      var el = document.getElementById(id);
      if (el) {
        el.textContent = v;
      }
      return el;
    }
    function setValue(id, v, tone) {
      var el = setText(id, v);
      if (el) {
        el.className = "dp-stat-value" + (tone ? " " + tone : "");
      }
    }
    function setBar(id, pct, tone) {
      var el = document.getElementById(id);
      if (el) {
        el.style.width = Math.max(0, Math.min(100, pct)) + "%";
        el.className = "dp-bar-fill " + tone;
      }
    }
    function fmtPct(v) {
      if (!isFinite(v)) {
        return "\u2014";
      }
      return Math.round(v * 1e3) / 10 + "%";
    }
    function fmtBytes(n) {
      if (n === void 0 || n === null || isNaN(n)) {
        return "\u2014";
      }
      var gb = n / 1073741824;
      if (gb >= 1) {
        return Math.round(gb * 10) / 10 + " GB";
      }
      var mb = n / 1048576;
      if (mb >= 1) {
        return Math.round(mb * 10) / 10 + " MB";
      }
      return Math.max(0, Math.round(n / 1024)) + " KB";
    }
    function fmtUptime(s) {
      if (!s && s !== 0) {
        return "\u2014";
      }
      var d = Math.floor(s / 86400);
      var h = Math.floor(s % 86400 / 3600);
      var m = Math.floor(s % 3600 / 60);
      if (d > 0) {
        return d + " \u5929 " + h + " \u5C0F\u65F6";
      }
      if (h > 0) {
        return h + " \u5C0F\u65F6 " + m + " \u5206";
      }
      if (m > 0) {
        return m + " \u5206\u949F";
      }
      return s + " \u79D2";
    }
    var pendingTodo = { settings: null, domains: null };
    function tryRenderTodo() {
      var s = pendingTodo.settings;
      var d = pendingTodo.domains;
      if (!s || !d) {
        return;
      }
      var items = [];
      if (!s.chatConfigured) {
        items.push({ txt: "\u5BF9\u8BDD\u6A21\u578B\u5C1A\u672A\u914D\u7F6E\uFF0C\u65E0\u6CD5\u5F00\u542F\u5BF9\u8BDD", href: "models.html", btn: "\u53BB\u914D\u7F6E" });
      }
      if (!s.embeddingConfigured) {
        items.push({
          txt: (s.embeddingProfileCount || 0) > 0 ? "\u5DF2\u767B\u8BB0\u5411\u91CF\u6A21\u578B\u6863\u6848\u4F46\u672A\u542F\u7528\uFF0C\u77E5\u8BC6\u5E93\u68C0\u7D22\u4E0D\u53EF\u7528" : "\u5411\u91CF\u6A21\u578B\u5C1A\u672A\u914D\u7F6E\uFF0C\u77E5\u8BC6\u5E93\u4E0D\u53EF\u7528",
          href: "models.html",
          btn: "\u53BB\u914D\u7F6E"
        });
      }
      var st = d.stats || {};
      if (!st.toolCount) {
        items.push({ txt: "\u6CA1\u6709\u626B\u63CF\u5230\u4EFB\u4F55\u5DE5\u5177\u63D0\u4F9B\u8005\uFF0C\u6A21\u578B\u65E0\u5DE5\u5177\u53EF\u7528", href: "domains.html", btn: "\u53BB\u57DF\u7A7A\u95F4" });
      }
      var box = document.getElementById("todoList");
      var card = document.getElementById("todoCard");
      if (!box || !card) {
        return;
      }
      if (!items.length) {
        card.hidden = true;
        box.innerHTML = "";
        return;
      }
      card.hidden = false;
      box.innerHTML = items.map(function(it) {
        return '<div class="dp-todo-item"><span class="dp-todo-dot"></span><span class="txt">' + esc(it.txt) + '</span><a class="btn btn-sm btn-outline-primary" href="' + it.href + '">' + esc(it.btn) + "</a></div>";
      }).join("");
    }
    function loadModelStats() {
      Console.refreshModelsWarn().then(function(s) {
        var chatReady = !!s.chatConfigured;
        setValue("statChat", chatReady ? "\u5DF2\u5C31\u7EEA" : "\u672A\u914D\u7F6E", chatReady ? "" : "warn");
        var embReady = !!s.embeddingConfigured;
        var embRegistered = s.embeddingProfileCount || 0;
        setValue("statEmb", embReady ? "\u5DF2\u5C31\u7EEA" : embRegistered ? "\u672A\u542F\u7528" : "\u672A\u914D\u7F6E", embReady ? "" : "warn");
        pendingTodo.settings = s;
        tryRenderTodo();
      }).catch(function() {
        setValue("statChat", "\u8BFB\u53D6\u5931\u8D25", "err");
        setValue("statEmb", "\u8BFB\u53D6\u5931\u8D25", "err");
      });
    }
    var DONUT_C = 283;
    function setDonutArc(id, len, offset) {
      var el = document.getElementById(id);
      if (el) {
        el.setAttribute("stroke-dasharray", len + " " + DONUT_C);
        el.setAttribute("stroke-dashoffset", offset);
      }
    }
    function renderDonut(read, write, destructive) {
      var total = read + write + destructive;
      setText("donutTotal", total);
      if (!total) {
        setDonutArc("donutRead", 0, 0);
        setDonutArc("donutWrite", 0, 0);
        setDonutArc("donutDestructive", 0, 0);
        setText("legReadN", "0");
        setText("legWriteN", "0");
        setText("legDestructiveN", "0");
        setText("legReadP", "\u2014");
        setText("legWriteP", "\u2014");
        setText("legDestructiveP", "\u2014");
        return;
      }
      var rl = read / total * DONUT_C;
      var wl = write / total * DONUT_C;
      setDonutArc("donutRead", rl, 0);
      setDonutArc("donutWrite", wl, -rl);
      setDonutArc("donutDestructive", destructive / total * DONUT_C, -(rl + wl));
      setText("legReadN", read);
      setText("legWriteN", write);
      setText("legDestructiveN", destructive);
      setText("legReadP", fmtPct(read / total));
      setText("legWriteP", fmtPct(write / total));
      setText("legDestructiveP", fmtPct(destructive / total));
    }
    function loadDomains() {
      Console.api.get("/admin/domains").then(function(r) {
        var st = r && r.stats || {};
        var toolCount = st.toolCount || 0;
        var domainCount = st.domainCount || 0;
        setValue("statTools", toolCount, toolCount ? "" : "warn");
        setValue("statDomains", domainCount, domainCount ? "" : "warn");
        var seen = {};
        var read = 0, write = 0, destructive = 0;
        function acc(effect) {
          if (effect === "READ") {
            read++;
          } else if (effect === "WRITE") {
            write++;
          } else if (effect === "DESTRUCTIVE") {
            destructive++;
          }
        }
        function accUnique(t) {
          if (t && t.name && !seen[t.name]) {
            seen[t.name] = true;
            acc(t.sideEffect);
          }
        }
        (r.globalTools || []).forEach(accUnique);
        (r.domains || []).forEach(function(d) {
          (d.tools || []).forEach(accUnique);
        });
        renderDonut(read, write, destructive);
        pendingTodo.domains = r;
        tryRenderTodo();
      }).catch(function() {
        setValue("statTools", "\u2014", "err");
        setValue("statDomains", "\u2014", "err");
      });
    }
    function loadRuntime() {
      Console.api.get("/admin/metrics").then(function(m) {
        var counters = m.counters || {};
        var gauges = m.gauges || {};
        setValue("statUptime", fmtUptime(m.uptimeSeconds));
        setValue("statSessions", m.runningSessions === void 0 ? "\u2014" : String(m.runningSessions));
        var heapUsed = m.heapUsedBytes, heapMax = m.heapMaxBytes;
        if (heapMax > 0) {
          var pct = heapUsed / heapMax;
          setValue("statHeap", fmtBytes(heapUsed) + " / " + fmtBytes(heapMax), pct > 0.85 ? "warn" : "");
        } else {
          setValue("statHeap", fmtBytes(heapUsed));
        }
        setValue("statMcpTools", gauges.mcpTools === void 0 ? "\u2014" : String(gauges.mcpTools));
        renderQuality(counters);
      }).catch(function() {
        setValue("statUptime", "\u2014", "err");
        setValue("statSessions", "\u2014", "err");
        setValue("statHeap", "\u2014", "err");
        setValue("statMcpTools", "\u2014", "err");
      });
    }
    function renderQuality(counters) {
      var hits = counters.recallHits || 0;
      var searches = counters.recallSearches || 0;
      var hitPct = searches ? hits / searches : -1;
      setText("qHitPct", hitPct < 0 ? "\u2014" : fmtPct(hitPct));
      setBar("qHitFill", hitPct < 0 ? 0 : hitPct * 100, hitPct < 0 ? "" : hitPct >= 0.6 ? "ok" : "warn");
      var tf = counters.toolCalls || 0;
      var tfPct = tf ? (counters.toolFailures || 0) / tf : -1;
      setText("qTfPct", tfPct < 0 ? "\u2014" : fmtPct(tfPct));
      setBar("qTfFill", tfPct < 0 ? 0 : tfPct * 100, tfPct < 0 ? "" : tfPct <= 0.05 ? "ok" : tfPct <= 0.2 ? "warn" : "err");
      var mc = counters.mcpToolCalls || 0;
      var mcPct = mc ? (counters.mcpToolFailures || 0) / mc : -1;
      setText("qMcpPct", mcPct < 0 ? "\u2014" : fmtPct(mcPct));
      setBar("qMcpFill", mcPct < 0 ? 0 : mcPct * 100, mcPct < 0 ? "" : mcPct <= 0.05 ? "ok" : mcPct <= 0.2 ? "warn" : "err");
    }
    function loadTrend() {
      var card = document.getElementById("trendCard");
      Console.request("/admin/metrics/history?points=60").then(function(h) {
        var pts = h && h.points || [];
        if (!card || pts.length < 2) {
          if (card) {
            card.hidden = true;
          }
          return;
        }
        var chatRate = [], toolRate = [];
        for (var i = 1; i < pts.length; i++) {
          var a = pts[i - 1].counters || {};
          var b = pts[i].counters || {};
          var dt = pts[i].t - pts[i - 1].t || 1;
          chatRate.push(((b.chatRequests || 0) - (a.chatRequests || 0) + (b.resumeRequests || 0) - (a.resumeRequests || 0)) / dt * 60);
          toolRate.push(((b.toolCalls || 0) - (a.toolCalls || 0)) / dt * 60);
        }
        var maxRate = 0;
        chatRate.concat(toolRate).forEach(function(v) {
          if (v > maxRate) {
            maxRate = v;
          }
        });
        if (maxRate <= 0) {
          maxRate = 1;
        }
        var W = 600, H = 160, TOP = 12, BOTTOM = 14;
        function yOf(v) {
          return H - BOTTOM - v / maxRate * (H - TOP - BOTTOM);
        }
        function pathOf(arr) {
          var d = "";
          for (var j = 0; j < arr.length; j++) {
            d += (j ? "L" : "M") + (j / (arr.length - 1) * W).toFixed(1) + " " + yOf(arr[j]).toFixed(1);
          }
          return d;
        }
        function areaOf(arr) {
          if (!arr.length) {
            return "";
          }
          return pathOf(arr) + "L" + W + " " + (H - BOTTOM) + "L0 " + (H - BOTTOM) + "Z";
        }
        var grid = document.getElementById("trendGrid");
        if (grid) {
          var lines = "";
          for (var g = 0; g <= 3; g++) {
            var y = TOP + g / 3 * (H - TOP - BOTTOM);
            lines += '<line x1="0" y1="' + y + '" x2="' + W + '" y2="' + y + '"/>';
          }
          grid.innerHTML = lines;
        }
        document.getElementById("trendToolArea").setAttribute("d", areaOf(toolRate));
        document.getElementById("trendChatArea").setAttribute("d", areaOf(chatRate));
        document.getElementById("trendToolLine").setAttribute("d", pathOf(toolRate));
        document.getElementById("trendChatLine").setAttribute("d", pathOf(chatRate));
        var spanSec = pts[pts.length - 1].t - pts[0].t;
        setText("trendWindow", "\uFF08\u6700\u8FD1 " + Math.max(1, Math.round(spanSec / 60)) + " \u5206\u949F\uFF09");
        var peakChat = Math.max.apply(null, chatRate);
        var peakTool = Math.max.apply(null, toolRate);
        setText("trendPeak", "\u5CF0\u503C \u5BF9\u8BDD " + Math.round(peakChat * 10) / 10 + " \xB7 \u5DE5\u5177 " + Math.round(peakTool * 10) / 10 + " \u6BCF\u5206");
        card.hidden = false;
      }).catch(function() {
        if (card) {
          card.hidden = true;
        }
      });
    }
    Console.renderSidebar("overview");
    Console.ready(function() {
      loadModelStats();
      loadDomains();
      loadRuntime();
      loadTrend();
    });
  })();
})();

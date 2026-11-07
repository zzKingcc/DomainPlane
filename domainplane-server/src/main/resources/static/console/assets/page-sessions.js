(() => {
  // src/pages/sessions.js
  (function() {
    var esc = Console.escapeHtml;
    var PAGE_SIZE = 20;
    var state = {
      sessionId: "",
      domain: "",
      sessionKey: "",
      total: 0,
      from: 1,
      size: PAGE_SIZE,
      turns: [],
      reports: [],
      selected: null
    };
    function el(id) {
      return document.getElementById(id);
    }
    function setHtml(id, html) {
      var n = el(id);
      if (n) {
        n.innerHTML = html;
      }
    }
    function setText(id, v) {
      var n = el(id);
      if (n) {
        n.textContent = v;
      }
    }
    function val(id) {
      var n = el(id);
      return n ? String(n.value || "").trim() : "";
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
    function toMs(v) {
      if (typeof v === "number") {
        return v;
      }
      if (v === null || v === void 0) {
        return NaN;
      }
      var s = String(v);
      if (/^-?\d+$/.test(s)) {
        return Number(s);
      }
      var t = Date.parse(s);
      return isNaN(t) ? NaN : t;
    }
    function when(v) {
      var ms = toMs(v);
      return isNaN(ms) ? "\u2014" : new Date(ms).toLocaleString();
    }
    function dur(a, b) {
      var x = toMs(a), y = toMs(b);
      if (isNaN(x) || isNaN(y) || y < x) {
        return "\u2014";
      }
      var d = y - x;
      return d < 1e3 ? d + " ms" : Math.round(d / 100) / 10 + " s";
    }
    function short(s) {
      s = String(s || "");
      return s.length > 70 ? s.slice(0, 70) + "\u2026" : s;
    }
    function textBlock(s) {
      s = String(s || "");
      if (!s) {
        return '<span class="dp-sessions-empty">\u2014</span>';
      }
      if (s.length <= 500) {
        return '<div class="dp-sessions-text">' + esc(s) + "</div>";
      }
      return '<details class="dp-sessions-fold"><summary>\u5C55\u5F00\u5168\u6587\uFF08' + s.length + ' \u5B57\uFF09</summary><pre class="dp-sessions-text">' + esc(s) + "</pre></details>";
    }
    function fact(label, value) {
      return '<div class="dp-sessions-fact"><div class="dp-sessions-fact-label">' + esc(label) + '</div><div class="dp-sessions-fact-value">' + value + "</div></div>";
    }
    function section(title, body) {
      return '<div class="dp-sessions-section"><div class="dp-sessions-section-title">' + esc(title) + "</div>" + body + "</div>";
    }
    function renderFacts(d) {
      setHtml(
        "sessionFacts",
        fact("\u4F1A\u8BDD\u952E", esc(d && d.sessionKey ? String(d.sessionKey) : "\u2014")) + fact("\u8F6E\u6570", esc(num(d ? d.total : null))) + fact("\u8BB0\u5FC6\u6761\u6570", esc(num(d ? d.memoryMessages : null))) + fact("\u88C5\u914D\u6E05\u5355", esc(num(d ? d.assemblyReportTotal : null)))
      );
    }
    function turnStatus(t) {
      if (!t.finishedAt) {
        return "\u8FDB\u884C\u4E2D";
      }
      var f = t.flags || {};
      if (f.aborted) {
        return "\u4E2D\u6B62";
      }
      if (f.approvalOutcome === "REJECTED") {
        return "\u5BA1\u6279\u62D2\u7EDD";
      }
      return "\u5B8C\u6210";
    }
    function isBad(t) {
      if (!t.finishedAt || t.flags && t.flags.aborted) {
        return true;
      }
      var flow = t.flow || [];
      for (var i = 0; i < flow.length; i++) {
        if (flow[i].type === "ERROR") {
          return true;
        }
      }
      return false;
    }
    function renderTurnList() {
      if (!state.turns.length) {
        setHtml("turnList", "");
        return;
      }
      setHtml("turnList", state.turns.map(function(t) {
        var user = t.user || {};
        return '<div class="dp-sessions-turn' + (state.selected === t.turnId ? " on" : "") + '" data-turn="' + esc(String(t.turnId)) + '"><div class="dp-sessions-turn-head"><span class="dp-sessions-turn-no">#' + esc(num(t.turnId)) + '</span><span class="dp-sessions-turn-time">' + esc(when(t.startedAt)) + "</span>" + (isBad(t) ? '<span class="dp-sessions-dot"></span>' : "") + '</div><div class="dp-sessions-turn-digest">' + esc(short(t.digest || user.text || "")) + '</div><div class="dp-sessions-turn-meta">' + esc(dur(t.startedAt, t.finishedAt)) + " \xB7 " + esc(turnStatus(t)) + "</div></div>";
      }).join(""));
      bindTurnClicks();
      scrollSelected();
    }
    function scrollSelected() {
      var box = el("turnList");
      if (!box) {
        return;
      }
      var on = box.querySelector(".dp-sessions-turn.on");
      if (!on) {
        return;
      }
      var br = box.getBoundingClientRect(), nr = on.getBoundingClientRect();
      if (nr.bottom > br.bottom) {
        box.scrollTop += nr.bottom - br.bottom + 8;
      } else if (nr.top < br.top) {
        box.scrollTop += nr.top - br.top - 8;
      }
    }
    function bindTurnClicks() {
      var box = el("turnList");
      if (!box) {
        return;
      }
      box.querySelectorAll("[data-turn]").forEach(function(n) {
        n.addEventListener("click", function() {
          state.selected = Number(n.getAttribute("data-turn"));
          box.querySelectorAll(".dp-sessions-turn").forEach(function(row) {
            row.classList.toggle("on", row === n);
          });
          renderDetail();
          scrollSelected();
        });
      });
    }
    function renderPager() {
      var from = state.from, total = state.total, size = state.size;
      var end = state.turns.length ? from + state.turns.length - 1 : from - 1;
      setText("pageInfo", total ? from + "\u2013" + end + " / " + total + " \u8F6E" : "0 \u8F6E");
      disable("btnPrevPage", total === 0 || from <= 1);
      disable("btnNextPage", total === 0 || from + size > total);
    }
    function conversationHtml(t) {
      var u = t.user || {};
      var media = u.media || [];
      var mediaHtml = "";
      if (media.length) {
        mediaHtml = '<div class="dp-sessions-media">' + media.map(function(m) {
          return "<code>" + esc(String(m.attachmentId || m.ref || m.kind || "\u9644\u4EF6")) + "</code>";
        }).join("") + "</div>";
      }
      return '<div class="dp-sessions-line"><span class="dp-sessions-role">\u7528\u6237</span><div class="dp-sessions-line-body">' + textBlock(u.text || "") + mediaHtml + '</div></div><div class="dp-sessions-line"><span class="dp-sessions-role">\u52A9\u624B</span><div class="dp-sessions-line-body">' + textBlock(t.answer || "") + "</div></div>";
    }
    function stepsHtml(t) {
      var flow = t.flow || [];
      if (!flow.length) {
        return '<div class="dp-sessions-empty">\u2014</div>';
      }
      var prev = t.startedAt;
      return '<div class="dp-sessions-steps">' + flow.map(function(f) {
        var d = dur(prev, f.at);
        prev = f.at;
        var type = String(f.type || "");
        var name = f.toolName ? "<code>" + esc(String(f.toolName)) + "</code>" : "";
        return '<div class="dp-sessions-step' + (type === "ERROR" ? " err" : "") + '"><div class="dp-sessions-step-head"><span class="dp-sessions-step-type">' + esc(type) + "</span>" + name + '<span class="dp-sessions-step-gap">' + esc(d) + '</span></div><div class="dp-sessions-step-body">' + textBlock(f.text || "") + "</div></div>";
      }).join("") + "</div>";
    }
    function droppedText(r) {
      var out = [];
      if (r.summaryDroppedLines > 0) {
        out.push("\u6458\u8981 " + r.summaryDroppedLines + " \u884C");
      }
      if (r.windowDroppedMessages > 0) {
        out.push("\u7A97\u53E3 " + r.windowDroppedMessages + " \u6761");
      }
      if (r.droppedCardTurnIds && r.droppedCardTurnIds.length) {
        out.push("\u5361\u7247\u8F6E " + r.droppedCardTurnIds.join("/"));
      }
      return out.length ? out.join("\u3001") : "\u65E0";
    }
    function factsHtml(t) {
      var usage = t.usage || {};
      var flags = t.flags || {};
      var report = null;
      for (var i = 0; i < state.reports.length; i++) {
        if (state.reports[i].turnId === t.turnId) {
          report = state.reports[i];
          break;
        }
      }
      var tags = [];
      if (flags.truncated) {
        tags.push("\u5DF2\u622A\u65AD");
      }
      if (flags.aborted) {
        tags.push("aborted");
      }
      if (flags.indexed) {
        tags.push("indexed");
      }
      if (flags.approvalOutcome) {
        tags.push(String(flags.approvalOutcome));
      }
      var cells = [
        ["\u8017\u65F6", dur(t.startedAt, t.finishedAt)],
        ["\u72B6\u6001", turnStatus(t)],
        ["\u8F93\u5165 / \u8F93\u51FA / \u5408\u8BA1", num(usage.promptTokens) + " / " + num(usage.completionTokens) + " / " + num(usage.totalTokens)],
        ["\u6807\u8BB0", tags.length ? tags.join("\u3001") : "\u2014"],
        ["\u88C5\u914D\u9884\u7B97", report ? num(report.totalBudget) : "\u2014"],
        ["\u88C5\u914D\u5B9E\u9645", report ? num(report.totalTokens) : "\u2014"],
        ["\u7A97\u53E3\u6D88\u606F", report ? num(report.windowMessages) : "\u2014"],
        ["\u4E22\u5F03", report ? droppedText(report) : "\u2014"]
      ];
      return '<div class="dp-sessions-facts-grid">' + cells.map(function(c) {
        return fact(c[0], esc(String(c[1])));
      }).join("") + "</div>";
    }
    function renderDetail() {
      var t = null;
      for (var i = 0; i < state.turns.length; i++) {
        if (state.turns[i].turnId === state.selected) {
          t = state.turns[i];
          break;
        }
      }
      if (!t) {
        setHtml("turnDetail", "");
        return;
      }
      setHtml(
        "turnDetail",
        '<h3 class="card-title">\u8F6E ' + esc(num(t.turnId)) + " \xB7 " + esc(when(t.startedAt)) + "</h3>" + section("\u5BF9\u8BDD\u5185\u5BB9", conversationHtml(t)) + section("\u6267\u884C\u6B65\u9AA4", stepsHtml(t)) + section("\u6267\u884C\u60C5\u51B5", factsHtml(t))
      );
    }
    function turnsUrl(from) {
      var u = "/admin/sessions/turns?sessionId=" + encodeURIComponent(state.sessionId) + "&size=" + state.size;
      if (state.domain) {
        u += "&domain=" + encodeURIComponent(state.domain);
      }
      if (from) {
        u += "&from=" + encodeURIComponent(String(from));
      }
      return u;
    }
    function resetView() {
      state.sessionKey = "";
      state.total = 0;
      state.from = 1;
      state.turns = [];
      state.reports = [];
      state.selected = null;
      setHtml("turnList", "");
      setHtml("turnDetail", "");
      renderFacts(null);
      renderPager();
    }
    function loadPage(from) {
      if (!state.sessionId) {
        return;
      }
      setHtml("turnList", Console.loadingHtml("\u6B63\u5728\u8BFB\u53D6\u8F6E\u6B21\u2026"));
      Console.api.get(turnsUrl(from)).then(function(d) {
        if (!d || d.code !== 0) {
          setHtml("turnList", "");
          setHtml("turnDetail", '<div class="dp-sessions-error">' + esc(d && d.error ? String(d.error) : "\u8BFB\u53D6\u5931\u8D25") + "</div>");
          return;
        }
        state.sessionKey = d.sessionKey || "";
        state.total = Number(d.total) || 0;
        state.from = Number(d.from) || 1;
        state.size = Number(d.size) || PAGE_SIZE;
        state.turns = d.turns || [];
        state.reports = d.assemblyReports || [];
        state.selected = state.turns.length ? state.turns[state.turns.length - 1].turnId : null;
        renderFacts(d);
        renderTurnList();
        renderPager();
        renderDetail();
      }).catch(function(e) {
        setHtml("turnList", "");
        setHtml("turnDetail", '<div class="dp-sessions-error">' + esc(e && e.message ? String(e.message) : "\u8BFB\u53D6\u5931\u8D25") + "</div>");
      });
    }
    function clearSession() {
      var sid = val("sessionIdInput") || state.sessionId;
      if (!sid) {
        return;
      }
      var dom = val("domainInput") || state.domain;
      var key = state.sessionKey || sid;
      Console.confirm(
        "\u6E05\u7A7A\u4F1A\u8BDD\u300C" + key + "\u300D\uFF1F\n\n\u8BB0\u5FC6\u3001\u68C0\u67E5\u70B9\u3001\u5F52\u6863\u8F6E\u6B21\u3001\u68C0\u7D22\u7D22\u5F15\u3001\u6EDA\u52A8\u6458\u8981\u3001\u88C5\u914D\u6E05\u5355\u4F1A\u4E00\u5E76\u5220\u9664\uFF0C\u4E0D\u53EF\u6062\u590D\u3002",
        { title: "\u6E05\u7A7A\u4F1A\u8BDD", okText: "\u6E05\u7A7A", danger: true }
      ).then(function(ok) {
        if (!ok) {
          return;
        }
        disable("btnClear", true);
        Console.api.post("/admin/sessions/clear", { sessionId: sid, domain: dom }).then(function(res) {
          disable("btnClear", false);
          Console.toast(res.message || "\u5DF2\u6E05\u7A7A", { type: "success", wide: true });
          resetView();
        }).catch(function(e) {
          disable("btnClear", false);
          Console.toast(
            "\u6E05\u7A7A\u5931\u8D25\uFF1A" + (e && e.message ? e.message : "\u672A\u77E5\u9519\u8BEF"),
            { type: "error", wide: true }
          );
        });
      });
    }
    function bindActions() {
      var q = el("btnQuery");
      if (q) {
        q.addEventListener("click", function() {
          state.sessionId = val("sessionIdInput");
          state.domain = val("domainInput");
          resetView();
          if (state.sessionId) {
            loadPage(null);
          }
        });
      }
      var ex = el("btnExport");
      if (ex) {
        ex.addEventListener("click", function() {
          var sid2 = val("sessionIdInput") || state.sessionId;
          if (!sid2) {
            return;
          }
          var u = "/admin/sessions/export?sessionId=" + encodeURIComponent(sid2);
          var dom = val("domainInput") || state.domain;
          if (dom) {
            u += "&domain=" + encodeURIComponent(dom);
          }
          window.location.href = u;
        });
      }
      var clr = el("btnClear");
      if (clr) {
        clr.addEventListener("click", clearSession);
      }
      var prev = el("btnPrevPage");
      if (prev) {
        prev.addEventListener("click", function() {
          loadPage(Math.max(1, state.from - state.size));
        });
      }
      var next = el("btnNextPage");
      if (next) {
        next.addEventListener("click", function() {
          loadPage(state.from + state.size);
        });
      }
      var sid = el("sessionIdInput");
      if (sid) {
        sid.addEventListener("keydown", function(e) {
          if (e.key === "Enter" && q) {
            q.click();
          }
        });
      }
    }
    Console.renderSidebar("sessions");
    Console.ready(function() {
      renderFacts(null);
      renderPager();
      bindActions();
    });
  })();
})();

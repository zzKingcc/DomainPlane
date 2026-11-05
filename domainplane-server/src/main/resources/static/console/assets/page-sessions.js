(() => {
  // src/pages/sessions.js
  (function() {
    var esc = Console.escapeHtml;
    function num(v) {
      return v === void 0 || v === null ? "\u2014" : String(v);
    }
    function when(ms) {
      if (!ms) {
        return "\u2014";
      }
      var d = new Date(Number(ms));
      return isNaN(d.getTime()) ? "\u2014" : d.toLocaleString();
    }
    function loadStorage() {
      var box = document.getElementById("sessionList");
      box.innerHTML = Console.loadingRowHtml(7, "\u6B63\u5728\u8BFB\u53D6\u4F1A\u8BDD\u6863\u6848\u2026");
      Console.api.get("/admin/sessions/storage?limit=20").then(function(data) {
        var rows = data.sessions || [];
        if (!rows.length) {
          box.innerHTML = '<tr><td colspan="7" class="hint">\u8FD8\u6CA1\u6709\u4EFB\u4F55\u4F1A\u8BDD\u6863\u6848\u3002</td></tr>';
          return;
        }
        box.innerHTML = rows.map(function(s) {
          return "<tr><td><code>" + esc(s.sessionId || "") + "</code></td><td>" + esc(s.domain || "") + "</td><td>" + num(s.memoryMessages) + "</td><td>" + num(s.archiveTurns) + "</td><td>" + num(s.vectorTurns) + "</td><td>" + num(s.assemblyReports) + '</td><td class="dp-actions"><button class="btn btn-sm" data-assembly="' + esc(s.sessionKey || "") + '">\u88C5\u914D\u6E05\u5355</button><button class="btn btn-sm" data-export="' + esc(s.sessionKey || "") + '">\u5BFC\u51FA\u526F\u672C</button></td></tr>';
        }).join("");
        bindRowActions();
      }).catch(function(e) {
        box.innerHTML = '<tr><td colspan="7" class="hint">\u8BFB\u53D6\u4F1A\u8BDD\u4F53\u91CF\u5931\u8D25\uFF1A' + esc(e.message) + "</td></tr>";
      });
    }
    function bindRowActions() {
      var box = document.getElementById("sessionList");
      box.querySelectorAll("[data-assembly]").forEach(function(btn) {
        btn.addEventListener("click", function() {
          loadAssembly(btn.getAttribute("data-assembly"));
        });
      });
      box.querySelectorAll("[data-export]").forEach(function(btn) {
        btn.addEventListener("click", function() {
          var key = btn.getAttribute("data-export");
          var parts = String(key).split("|");
          var url = "/admin/sessions/export?sessionId=" + encodeURIComponent(parts[1] || key) + (parts.length > 1 ? "&domain=" + encodeURIComponent(parts[0]) : "");
          window.location.href = url;
        });
      });
    }
    function loadAssembly(sessionKey) {
      var box = document.getElementById("assemblyBox");
      var parts = String(sessionKey).split("|");
      var url = "/admin/sessions/assembly?sessionId=" + encodeURIComponent(parts[1] || sessionKey) + (parts.length > 1 ? "&domain=" + encodeURIComponent(parts[0]) : "") + "&limit=10";
      box.className = "card dp-panel";
      box.innerHTML = '<div class="card-body">' + Console.loadingHtml("\u6B63\u5728\u8BFB\u53D6\u88C5\u914D\u6E05\u5355\u2026") + "</div>";
      Console.api.get(url).then(function(data) {
        var reports = data.reports || [];
        if (!reports.length) {
          box.innerHTML = '<div class="card-body"><div class="hint">\u8FD9\u4E2A\u4F1A\u8BDD\u8FD8\u6CA1\u6709\u88C5\u914D\u8BB0\u5F55\u3002</div></div>';
          return;
        }
        box.innerHTML = '<div class="card-body"><table class="table"><thead><tr><th>\u8F6E</th><th>\u603B\u9884\u7B97</th><th>\u6458\u8981</th><th>\u5361\u7247</th><th>\u7A97\u53E3</th><th>\u63D0\u95EE</th><th>\u5408\u8BA1</th><th>\u4E22\u5F03</th></tr></thead><tbody>' + reports.map(function(r) {
          var lost = r.windowDroppedMessages > 0 || r.droppedCardTurnIds && r.droppedCardTurnIds.length > 0;
          var dropped = [];
          if (r.summaryDroppedLines > 0) {
            dropped.push("\u6458\u8981 " + r.summaryDroppedLines + " \u884C");
          }
          if (r.windowDroppedMessages > 0) {
            dropped.push("\u7A97\u53E3 " + r.windowDroppedMessages + " \u6761");
          }
          if (r.droppedCardTurnIds && r.droppedCardTurnIds.length > 0) {
            dropped.push("\u5361\u7247\u8F6E " + r.droppedCardTurnIds.join("/"));
          }
          return "<tr" + (lost ? ' class="dp-row-warn"' : "") + "><td>" + num(r.turnId) + '<div class="hint">' + esc(when(r.at)) + "</div></td><td>" + num(r.totalBudget) + "</td><td>" + num(r.summaryTokens) + '<div class="hint">' + num(r.summaryLines) + " \u884C</div></td><td>" + num(r.cardTokens) + '<div class="hint">' + num(r.cardCount) + " \u5F20</div></td><td>" + num(r.windowTokens) + '<div class="hint">' + num(r.windowMessages) + " \u6761</div></td><td>" + num(r.currentTokens) + "</td><td>" + num(r.totalTokens) + "</td><td>" + (dropped.length ? esc(dropped.join("\u3001")) : "\u2014") + "</td></tr>";
        }).join("") + "</tbody></table></div>";
      }).catch(function(e) {
        box.innerHTML = '<div class="card-body"><div class="hint">\u8BFB\u53D6\u88C5\u914D\u6E05\u5355\u5931\u8D25\uFF1A' + esc(e.message) + "</div></div>";
      });
    }
    Console.renderSidebar("sessions");
    Console.ready(function() {
      loadStorage();
    });
  })();
})();

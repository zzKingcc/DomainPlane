(() => {
  // domainplane-server/frontend/src/pages/knowledge.js
  (function() {
    var esc = Console.escapeHtml;
    function domainLabel(domain) {
      var d = domain || "default";
      return '<span class="pill">' + esc(d) + (d === "default" ? "\uFF08\u5168\u57DF\uFF09" : "") + "</span>";
    }
    function loadDomainOptions() {
      var select = document.getElementById("domainSelect");
      Console.api.get("/admin/domains").then(function(data) {
        (data.domains || []).forEach(function(d) {
          var name = typeof d === "string" ? d : d.name;
          if (!name || name === "default") return;
          var opt = document.createElement("option");
          opt.value = name;
          opt.textContent = name;
          select.appendChild(opt);
        });
      }).catch(function() {
      });
    }
    function selectedDomain() {
      return document.getElementById("domainSelect").value || "";
    }
    function loadEmbeddingStatus() {
      var el = document.getElementById("embStatus");
      Console.api.get("/admin/settings").then(function(s) {
        var ok = !!s.embeddingConfigured;
        var name = s.embeddingModelName ? "<code>" + esc(s.embeddingModelName) + "</code>" : "";
        el.className = "status-line " + (ok ? "ok" : "warn");
        el.innerHTML = ok ? "<span>\u5411\u91CF\u6A21\u578B\u5DF2\u5C31\u7EEA" + (name ? "\uFF1A" + name : "") + '</span><span class="pill">' + esc(s.embeddingBaseUrl || s.chatBaseUrl || "\u590D\u7528\u5BF9\u8BDD\u5730\u5740") + "</span>" : "<span>\u5411\u91CF\u6A21\u578B\u672A\u914D\u7F6E\uFF0C\u4E0A\u4F20\u4E0E\u91CD\u5EFA\u4F1A\u5931\u8D25\u3002\u8BF7\u5148\u5230\u300C\u6A21\u578B\u8BBE\u7F6E\u300D\u7684\u300C\u5411\u91CF\u6A21\u578B\u300D\u6BB5\uFF0C\u70B9\u51FB\u5411\u91CF\u6863\u6848\u5361\u7247\u4E0A\u7684<b>\u542F\u7528</b>\u3002</span>";
        Console.setModelsWarn(!s.chatConfigured);
      }).catch(function(e) {
        el.className = "status-line warn";
        el.textContent = "\u8BFB\u53D6\u8BBE\u7F6E\u5931\u8D25\uFF1A" + e.message;
      });
    }
    function loadDocuments() {
      var box = document.getElementById("docList");
      Console.api.get("/admin/kb/documents").then(function(data) {
        var docs = data.documents || [];
        if (!docs.length) {
          box.className = "hint";
          box.textContent = "\u6682\u65E0\u6587\u6863\u3002\u8BF7\u5148\u5728\u4E0A\u65B9\u4E0A\u4F20\u3002";
          return;
        }
        var rows = docs.map(function(d) {
          return "<tr><td><code>" + esc(d.docId || "") + "</code></td><td>" + esc(d.fileName || "") + "</td><td>" + domainLabel(d.domain) + "</td><td>" + (d.chunks || 0) + "</td><td>" + (d.exportPath ? '<code class="path" title="\u70B9\u51FB\u590D\u5236">' + esc(d.exportPath) + "</code>" : '<span class="hint">\u2014</span>') + '</td><td><button class="danger btn btn-danger" data-doc="' + esc(d.docId || "") + '">\u5220\u9664</button></td></tr>';
        }).join("");
        box.className = "";
        box.innerHTML = '<table class="table"><thead><tr><th>docId</th><th>\u6587\u4EF6\u540D</th><th>\u5F52\u5C5E\u57DF</th><th>\u5207\u7247\u6570</th><th>\u5207\u7247\u6587\u4EF6</th><th>\u64CD\u4F5C</th></tr></thead><tbody>' + rows + "</tbody></table>";
        box.querySelectorAll("button[data-doc]").forEach(function(btn) {
          btn.addEventListener("click", function() {
            removeDoc(btn.getAttribute("data-doc"));
          });
        });
        box.querySelectorAll("code.path").forEach(function(code) {
          code.addEventListener("click", function() {
            if (navigator.clipboard) {
              navigator.clipboard.writeText(code.textContent);
            }
          });
        });
      }).catch(function(e) {
        box.className = "hint";
        box.textContent = "\u8BFB\u53D6\u6587\u6863\u5217\u8868\u5931\u8D25\uFF1A" + e.message;
      });
    }
    function removeDoc(docId) {
      if (!confirm("\u786E\u8BA4\u5220\u9664\u8BE5\u6587\u6863\uFF1F\u5220\u9664\u540E\u5176\u6587\u4EF6\u540D\u4F1A\u88AB\u91CA\u653E\uFF0C\u53EF\u91CD\u65B0\u4E0A\u4F20\u540C\u540D\u6587\u6863\u3002")) return;
      Console.api.del("/admin/kb/documents/" + encodeURIComponent(docId)).then(function() {
        loadDocuments();
      }).catch(function(e) {
        alert("\u5220\u9664\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function upload() {
      var input = document.getElementById("fileInput");
      var btn = document.getElementById("uploadBtn");
      var box = document.getElementById("uploadResult");
      if (!input.files || !input.files.length) {
        alert("\u8BF7\u5148\u9009\u62E9\u6587\u4EF6");
        return;
      }
      var form = new FormData();
      form.append("file", input.files[0]);
      var replace = document.getElementById("replaceChk").checked;
      var domain = selectedDomain();
      var query = "/admin/kb/documents?replace=" + (replace ? "true" : "false");
      if (domain) {
        query += "&domain=" + encodeURIComponent(domain);
      }
      btn.disabled = true;
      box.className = "result show";
      box.textContent = "\u4E0A\u4F20\u4E2D\u2026\uFF08\u5207\u7247 + \u5411\u91CF\u5316\uFF0C\u8017\u65F6\u968F\u6587\u4EF6\u4F53\u79EF\u589E\u957F\uFF09";
      fetch(query, {
        method: "POST",
        body: form,
        credentials: "same-origin"
      }).then(function(resp) {
        return resp.json().then(function(data) {
          return { data };
        }).catch(function() {
          return { data: { success: false, error: "\u54CD\u5E94\u89E3\u6790\u5931\u8D25" } };
        });
      }).then(function(wrapped) {
        var data = wrapped.data;
        box.textContent = JSON.stringify(data, null, 2);
        box.className = "result show " + (data.success ? "ok" : "err");
        if (data.success) {
          input.value = "";
          loadDocuments();
        }
      }).catch(function(e) {
        box.textContent = "\u8BF7\u6C42\u5931\u8D25: " + e.message;
        box.className = "result show err";
      }).then(function() {
        btn.disabled = false;
      });
    }
    function rebuild() {
      var btn = document.getElementById("rebuildBtn");
      var box = document.getElementById("rebuildResult");
      btn.disabled = true;
      box.className = "result show";
      box.textContent = "\u91CD\u5EFA\u4E2D\u2026\uFF08\u4F1A\u5EFA\u7ACB\u771F\u5B9E ES \u8FDE\u63A5\u5E76\u8C03\u7528 embedding\uFF0C\u8017\u65F6\u8F83\u957F\uFF09";
      Console.api.post("/admin/kb/rebuild").then(function(data) {
        box.textContent = JSON.stringify(data, null, 2);
        box.className = "result show ok";
        loadDocuments();
      }).catch(function(e) {
        box.textContent = "\u8BF7\u6C42\u5931\u8D25: " + e.message;
        box.className = "result show err";
      }).then(function() {
        btn.disabled = false;
      });
    }
    Console.renderSidebar("knowledge");
    document.getElementById("uploadBtn").addEventListener("click", upload);
    document.getElementById("rebuildBtn").addEventListener("click", rebuild);
    Console.ready(function() {
      loadEmbeddingStatus();
      loadDomainOptions();
      loadDocuments();
    });
  })();
})();

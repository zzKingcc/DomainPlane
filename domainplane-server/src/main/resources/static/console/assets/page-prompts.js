(() => {
  // domainplane-server/frontend/src/pages/prompts.js
  (function() {
    var esc = Console.escapeHtml;
    var COLORS = { primary: "#2563eb", muted: "#6b7280", red: "#dc2626", amber: "#d97706" };
    var model = { prompts: {} };
    var baseline = { prompts: {} };
    var domains = [];
    var currentDomain = null;
    function snapshot(o) {
      return JSON.stringify({ prompts: o.prompts || {} });
    }
    function isDirty() {
      return snapshot(model) !== snapshot(baseline);
    }
    function setDirty() {
      document.getElementById("dirtyFlag").style.display = isDirty() ? "inline" : "none";
    }
    function chainOf(domain) {
      var segs = String(domain || "").split(".");
      var out = [];
      var cur = "";
      for (var i = 0; i < segs.length; i++) {
        cur = cur ? cur + "." + segs[i] : segs[i];
        out.push(cur);
      }
      return out;
    }
    function counts() {
      document.getElementById("domainLen").textContent = currentDomain ? (model.prompts[currentDomain] || "").trim().length : 0;
    }
    function compose(domain) {
      var parts = [];
      chainOf(domain).forEach(function(step) {
        var text = (model.prompts[step] || "").trim();
        if (text) {
          parts.push(text);
        }
      });
      return parts.join("\n\n");
    }
    function renderPreview() {
      if (!currentDomain) {
        return;
      }
      var text = compose(currentDomain);
      var from = chainOf(currentDomain).filter(function(step) {
        return (model.prompts[step] || "").trim();
      });
      document.getElementById("edChain").textContent = from.length ? "\u62FC\u63A5\u81EA\uFF1A" + from.join(" \u2192 ") : "";
      document.getElementById("preview").innerHTML = esc(text) || "\uFF08\u8BE5\u57DF\u53CA\u5176\u7956\u5148\u94FE\u90FD\u672A\u914D\u7F6E\uFF0C\u672C\u8F6E\u4E0D\u4F1A\u4E0B\u53D1\u4EFB\u4F55\u7CFB\u7EDF\u63D0\u793A\u8BCD\uFF09";
    }
    function openEditor(domain) {
      currentDomain = domain;
      document.getElementById("editorCard").style.display = "block";
      document.getElementById("edDomain").textContent = "\u57DF\uFF1A" + domain;
      var d = domains.filter(function(x) {
        return x.name === domain;
      })[0];
      document.getElementById("edTools").textContent = d ? "\u53EF\u89C1\u5DE5\u5177 " + d.tools.length + " \u4E2A\uFF0C\u6765\u81EA " + d.providerCount + " \u4E2A\u63D0\u4F9B\u8005" : "";
      document.getElementById("domainText").value = model.prompts[domain] || "";
      counts();
      renderPreview();
      document.getElementById("editorCard").scrollIntoView({ behavior: "smooth", block: "nearest" });
    }
    function renderDomains() {
      var tbody = document.getElementById("domainBody");
      if (!domains.length) {
        tbody.innerHTML = '<tr><td colspan="6" class="perm-none">\u5F53\u524D\u6CA1\u6709\u4EFB\u4F55\u57DF\u3002\u6839\u57DF default\uFF08\u6B63\u5E38\u88C5\u914D\u65F6\u5FC5\u5B9A\u5B58\u5728\uFF09\u3001\u7BA1\u63A7\u53F0\u4EBA\u5DE5\u521B\u5EFA\u3001\u5DE5\u5177\u58F0\u660E\u6D3E\u751F\uFF1B\u82E5\u8FDE default \u90FD\u6CA1\u6709\uFF0C\u8BF4\u660E\u670D\u52A1\u7AEF\u672A\u6B63\u5E38\u88C5\u914D\u57DF\u6CE8\u518C\u8868\u3002</td></tr>';
        return;
      }
      tbody.innerHTML = domains.map(function(d) {
        var val = (model.prompts[d.name] || "").trim();
        var status = val ? '<span class="tag read">\u5DF2\u914D\u7F6E</span>' : '<span class="tag write">\u7F3A\u63D0\u793A\u8BCD</span>';
        var toolNames = d.tools.map(function(t) {
          return t.name;
        });
        var chips = toolNames.slice(0, 6).map(function(n) {
          return '<span class="tag perm">' + esc(n) + "</span>";
        }).join("") + (toolNames.length > 6 ? '<span class="card-note">\u7B49 ' + toolNames.length + " \u4E2A</span>" : "");
        return '<tr class="domain-row" data-dom="' + esc(d.name) + '"><td><b>' + esc(d.name) + '</b></td><td style="min-width:220px;">' + (chips || '<span class="perm-none">\u65E0</span>') + "</td><td>" + d.providerCount + "</td><td>" + val.length + " \u5B57</td><td>" + status + '</td><td><a href="javascript:void(0)" class="ghost" style="padding:4px 10px;">\u7F16\u8F91</a></td></tr>';
      }).join("");
      Array.prototype.forEach.call(tbody.querySelectorAll(".domain-row"), function(row) {
        row.addEventListener("click", function() {
          openEditor(row.getAttribute("data-dom"));
        });
      });
    }
    function renderAll() {
      counts();
      renderDomains();
      renderPreview();
      setDirty();
    }
    function load() {
      Console.api.get("/admin/prompts").then(function(r) {
        baseline = { prompts: Object.assign({}, r.prompts || {}) };
        model = { prompts: Object.assign({}, baseline.prompts) };
        domains = r.domains || [];
        var orphans = r.orphanPrompts || [];
        var box = document.getElementById("orphanBox");
        if (orphans.length) {
          box.style.display = "block";
          box.innerHTML = "<b>\u5B64\u513F\u63D0\u793A\u8BCD\u6761\u76EE\uFF1A</b>" + orphans.map(esc).join("\u3001") + "<br>\u8FD9\u4E9B\u57DF\u4ECE\u672A\u88AB\u4EFB\u4F55\u5DE5\u5177\u58F0\u660E\u8FC7\uFF0C\u914D\u7F6E\u7559\u7740\u4E0D\u751F\u6548\u3001\u4E5F\u4E0D\u81EA\u52A8\u6E05\u7406\uFF1B\u82E5\u786E\u8BA4\u4E0D\u518D\u9700\u8981\uFF0C\u53EF\u5728\u4E0B\u65B9\u7F16\u8F91\u533A\u4E4B\u5916\u624B\u5DE5\u5220\u9664\u914D\u7F6E\u9879\u3002";
        } else {
          box.style.display = "none";
        }
        if (currentDomain && !domains.some(function(d) {
          return d.name === currentDomain;
        })) {
          currentDomain = null;
          document.getElementById("editorCard").style.display = "none";
        }
        renderAll();
      }).catch(function(e) {
        document.getElementById("domainBody").innerHTML = '<tr><td colspan="6" style="color:' + COLORS.red + ';">\u52A0\u8F7D\u5931\u8D25: ' + esc(e.message) + "</td></tr>";
      });
    }
    document.getElementById("domainText").addEventListener("input", function(e) {
      if (!currentDomain) {
        return;
      }
      model.prompts[currentDomain] = e.target.value;
      counts();
      renderPreview();
      renderDomains();
      setDirty();
    });
    document.getElementById("clearBtn").addEventListener("click", function() {
      model = { prompts: {} };
      document.getElementById("domainText").value = "";
      renderAll();
    });
    document.getElementById("refreshBtn").addEventListener("click", function() {
      if (isDirty() && !confirm("\u6709\u672A\u4FDD\u5B58\u7684\u4FEE\u6539\uFF0C\u91CD\u65B0\u8BFB\u53D6\u4F1A\u4E22\u5F03\u5B83\u4EEC\u3002\u7EE7\u7EED\uFF1F")) {
        return;
      }
      load();
    });
    document.getElementById("saveBtn").addEventListener("click", function() {
      var el = document.getElementById("saveResult");
      el.textContent = "\u6B63\u5728\u4FDD\u5B58\u2026";
      el.className = "result show ok";
      var payload = { prompts: model.prompts };
      Console.api.post("/admin/prompts", payload).then(function(r) {
        el.textContent = r.message || "\u5DF2\u4FDD\u5B58\u5E76\u751F\u6548";
        el.className = "result show ok";
        load();
      }).catch(function(e) {
        el.textContent = e.message;
        el.className = "result show err";
      });
    });
    Console.renderSidebar("prompts");
    Console.ready(load);
  })();
})();

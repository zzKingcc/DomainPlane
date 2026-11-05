(() => {
  // domainplane-server/frontend/src/pages/prompts.js
  (function() {
    var esc = Console.escapeHtml;
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
    function show(id, on) {
      var n = el(id);
      if (n) {
        n.style.display = on ? "" : "none";
      }
    }
    var model = { prompts: {} };
    var baseline = { prompts: {}, orphans: [] };
    var domains = [];
    var currentDomain = null;
    var LOADED = false;
    function setValue(id, v, tone) {
      var n = el(id);
      if (n) {
        n.textContent = v;
        n.className = "dp-stat-value" + (tone ? " " + tone : "");
      }
    }
    function currentText() {
      return currentDomain ? model.prompts[currentDomain] || "" : "";
    }
    function baselineText() {
      return currentDomain ? baseline.prompts[currentDomain] || "" : "";
    }
    function updateSaveBtn() {
      var btn = el("saveBtn");
      if (btn) {
        btn.disabled = currentDomain === null || currentText() === baselineText();
      }
      show("dirtyFlag", currentDomain !== null && currentText() !== baselineText());
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
      setText("domainLen", String(currentText().trim().length));
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
      setText("edChain", from.length ? "\u62FC\u63A5\u81EA\uFF1A" + from.join(" \u2192 ") : "");
      setHtml("preview", esc(text) || "\uFF08\u8BE5\u57DF\u53CA\u5176\u7956\u5148\u94FE\u90FD\u672A\u914D\u7F6E\uFF0C\u672C\u8F6E\u4E0D\u4F1A\u4E0B\u53D1\u4EFB\u4F55\u7CFB\u7EDF\u63D0\u793A\u8BCD\uFF09");
    }
    function openEditor(domain) {
      currentDomain = domain;
      show("editorEmpty", false);
      show("editorForm", true);
      setText("edDomain", domain);
      var d = domains.filter(function(x) {
        return x.name === domain;
      })[0];
      setText("edTools", d ? "\u53EF\u89C1\u5DE5\u5177 " + (d.tools || []).length + " \u4E2A" : "");
      var ta = el("domainText");
      if (ta) {
        ta.value = model.prompts[domain] || "";
      }
      counts();
      renderPreview();
      updateSaveBtn();
      renderDomainList();
    }
    function renderDomainList() {
      var box = el("domainList");
      if (!box) {
        return;
      }
      if (!domains.length) {
        box.innerHTML = '<div class="dp-domain-empty">\u5F53\u524D\u6CA1\u6709\u4EFB\u4F55\u57DF\u3002\u82E5\u8FDE\u6839\u57DF default \u90FD\u6CA1\u6709\uFF0C\u8BF4\u660E\u670D\u52A1\u7AEF\u672A\u6B63\u5E38\u88C5\u914D\u57DF\u6CE8\u518C\u8868\u3002</div>';
        return;
      }
      box.innerHTML = '<div class="dp-domain-list">' + domains.map(function(d) {
        var val = (model.prompts[d.name] || "").trim();
        var tag = val ? '<span class="dp-domain-tag ok">\u5DF2\u914D\u7F6E</span>' : '<span class="dp-domain-tag miss">\u7F3A\u63D0\u793A\u8BCD</span>';
        return '<div class="dp-domain-item' + (d.name === currentDomain ? " on" : "") + '" data-dom="' + esc(d.name) + '"><span class="dp-domain-name" title="' + esc(d.name) + '">' + esc(d.name) + "</span>" + tag + "</div>";
      }).join("") + "</div>";
      box.querySelectorAll(".dp-domain-item").forEach(function(item) {
        item.addEventListener("click", function() {
          openEditor(item.getAttribute("data-dom"));
        });
      });
    }
    function renderStats() {
      var total = domains.length;
      var missing = domains.filter(function(d) {
        return !(model.prompts[d.name] || "").trim();
      }).length;
      setValue("statDomains", String(total));
      setValue("statConfigured", String(total - missing));
      setValue("statMissing", String(missing), missing ? "warn" : "");
      setValue("statOrphans", String(baseline.orphans.length), baseline.orphans.length ? "warn" : "");
    }
    var ORPHAN_ICON = '<span class="ob-icon"><svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><path d="M12 9v4M12 17h.01"/></svg></span>';
    function renderOrphans() {
      var box = el("orphanBox");
      if (!box) {
        return;
      }
      var orphans = baseline.orphans;
      if (!orphans.length) {
        box.style.display = "none";
        return;
      }
      box.style.display = "flex";
      box.innerHTML = ORPHAN_ICON + '<div><b>\u5B64\u513F\u63D0\u793A\u8BCD\u6761\u76EE\uFF1A</b><span class="ob-list">' + orphans.map(esc).join("\u3001") + "</span><div>\u8FD9\u4E9B\u57DF\u4ECE\u672A\u88AB\u4EFB\u4F55\u5DE5\u5177\u58F0\u660E\u8FC7\uFF0C\u914D\u7F6E\u7559\u7740\u4E0D\u751F\u6548\u3001\u4E5F\u4E0D\u81EA\u52A8\u6E05\u7406\uFF1B\u82E5\u786E\u8BA4\u4E0D\u518D\u9700\u8981\uFF0C\u53EF\u5728\u7F16\u8F91\u533A\u4E4B\u5916\u624B\u5DE5\u5220\u9664\u914D\u7F6E\u9879\u3002</div></div>";
    }
    function renderAll() {
      counts();
      renderDomainList();
      renderPreview();
      renderStats();
      updateSaveBtn();
    }
    function load() {
      if (!LOADED) {
        setHtml("domainList", Console.loadingHtml("\u6B63\u5728\u52A0\u8F7D\u63D0\u793A\u8BCD\u914D\u7F6E\u2026"));
      }
      return Console.api.get("/admin/prompts").then(function(r) {
        LOADED = true;
        baseline = { prompts: Object.assign({}, r.prompts || {}), orphans: r.orphanPrompts || [] };
        model = { prompts: Object.assign({}, baseline.prompts) };
        domains = r.domains || [];
        renderOrphans();
        if (currentDomain && !domains.some(function(d) {
          return d.name === currentDomain;
        })) {
          currentDomain = null;
          show("editorEmpty", true);
          show("editorForm", false);
          setText("edDomain", "\u2014");
          setText("edTools", "");
        }
        renderAll();
      }).catch(function(e) {
        setHtml("domainList", '<div class="dp-domain-empty" style="color:var(--red);">\u52A0\u8F7D\u5931\u8D25: ' + esc(e.message) + "</div>");
      });
    }
    el("domainText").addEventListener("input", function(e) {
      if (!currentDomain) {
        return;
      }
      model.prompts[currentDomain] = e.target.value;
      counts();
      renderPreview();
      renderDomainList();
      updateSaveBtn();
    });
    el("refreshBtn").addEventListener("click", function() {
      if (currentDomain && currentText() !== baselineText()) {
        return Console.confirm(
          "\u6709\u672A\u4FDD\u5B58\u7684\u4FEE\u6539\uFF0C\u91CD\u65B0\u8BFB\u53D6\u4F1A\u4E22\u5F03\u5B83\u4EEC\u3002\u7EE7\u7EED\uFF1F",
          { title: "\u653E\u5F03\u672A\u4FDD\u5B58\u7684\u4FEE\u6539", okText: "\u7EE7\u7EED\u8BFB\u53D6", danger: true }
        ).then(function(ok) {
          if (ok) {
            load();
          }
        });
      }
      load();
    });
    el("saveBtn").addEventListener("click", function() {
      var btn = this;
      Console.busy(btn, function() {
        var out = el("saveResult");
        if (out) {
          out.textContent = "\u6B63\u5728\u4FDD\u5B58\u2026";
          out.className = "result show ok";
        }
        return Console.api.post("/admin/prompts", { prompts: model.prompts }).then(function(r) {
          if (out) {
            out.textContent = r.message || "\u5DF2\u4FDD\u5B58\u5E76\u751F\u6548";
            out.className = "result show ok";
          }
          load();
        }).catch(function(e) {
          if (out) {
            out.textContent = e.message;
            out.className = "result show err";
          }
        });
      }, "\u4FDD\u5B58\u4E2D\u2026");
    });
    Console.renderSidebar("prompts");
    Console.ready(load);
  })();
})();

(() => {
  // src/pages/knowledge.js
  (function() {
    var esc = Console.escapeHtml;
    var state = {
      overview: null,
      docs: [],
      target: null,
      bodyOfDomain: {},
      /* 域 → 知识体 id（前端只为文档表补两列，判定以后端为准） */
      limits: null
    };
    function fmt(n) {
      if (n === void 0 || n === null) {
        return "\u2014";
      }
      return Number(n).toLocaleString("zh-CN");
    }
    function setValue(id, v, tone) {
      var el = document.getElementById(id);
      if (el) {
        el.textContent = v;
        el.className = "dp-stat-value" + (tone ? " " + tone : "");
      }
    }
    function setNote(id, v) {
      var el = document.getElementById(id);
      if (el) {
        el.textContent = v || "";
      }
    }
    function domainLabel(domain) {
      var d = domain || "default";
      return '<span class="pill">' + esc(d) + (d === "default" ? "\uFF08\u5168\u57DF\uFF09" : "") + "</span>";
    }
    function slotChip(slot) {
      if (!slot || slot.state === "absent") {
        return '<span class="dp-chip mute">\u672A\u914D</span>';
      }
      var name = esc(slot.name || slot.connectionId || "");
      if (slot.state === "ok") {
        return '<span class="dp-chip mono ok" title="' + esc((slot.type || "") + " \xB7 " + slot.connectionId) + '">' + name + "</span>";
      }
      var why = slot.state === "missing" ? "\u8FDE\u63A5\u5DF2\u5220\u9664" : slot.state === "blank" ? "\u8FDE\u63A5\u672A\u586B\u5730\u5740" : slot.state === "incapable" ? "\u8BE5\u8FDE\u63A5\u7C7B\u578B\u63D0\u4F9B\u4E0D\u4E86\u8FD9\u4E00\u8DEF\uFF08\u5982\u5173\u952E\u5B57\u69FD\u6307\u5230\u5411\u91CF\u5E93\uFF09" : "\u8FDE\u63A5\u672A\u6CE8\u518C\uFF08\u5B58\u50A8\u914D\u7F6E\u4FDD\u5B58\u540E\u751F\u6548\uFF09";
      return '<span class="dp-chip mono warn" title="' + esc(why) + '">' + name + "</span>";
    }
    function modesText(modes) {
      if (!modes || !modes.length) {
        return "\u7A7A\u58F3";
      }
      if (modes.length > 1) {
        return "\u6DF7\u5408";
      }
      return modes[0] === "keyword" ? "\u4EC5\u5173\u952E\u5B57" : "\u4EC5\u5411\u91CF";
    }
    function modeCell(row) {
      var modes = row.modes || [];
      var effective = row.effectiveModes || [];
      if (!modes.length) {
        return '<span class="dp-chip mute">\u7A7A\u58F3</span>';
      }
      var text = effective.length ? modesText(effective) : "\u65E0\u751F\u6548\u8DEF";
      var html = '<span class="dp-chip' + (effective.length ? "" : " warn") + '">' + esc(text) + "</span>";
      if (effective.length < modes.length) {
        var closed = modes.filter(function(m) {
          return effective.indexOf(m) < 0;
        }).map(function(m) {
          return m === "keyword" ? "\u5173\u952E\u5B57" : "\u5411\u91CF";
        }).join("/");
        html += '<span class="dp-chip warn" title="' + esc("\u5168\u5C40\u7B56\u7565\u5173\u95ED\u4E86 " + closed + " \u8DEF") + '">\u5173' + closed + "</span>";
      }
      return html;
    }
    var STATUS_TEXT = {
      ok: ["\u6B63\u5E38", "ok"],
      empty: ["\u7A7A\u58F3", "warn"],
      dead: ["\u8FDE\u63A5\u4E0D\u53EF\u7528", "err"],
      missing: ["\u4F53\u5DF2\u5220\u9664", "err"],
      no_index: ["\u6709\u7ED1\u5B9A\u65E0\u7D22\u5F15", "warn"]
    };
    function statusCell(status) {
      var pair = STATUS_TEXT[status] || [status || "\u2014", ""];
      return '<span class="dp-chip ' + pair[1] + '">' + esc(pair[0]) + "</span>";
    }
    function bodyEmptyRow(text) {
      return '<tr class="dp-doc-empty"><td colspan="8"><div>' + text + "</div></td></tr>";
    }
    function renderBodies() {
      var ov = state.overview;
      var tbody = document.getElementById("bodyBody");
      if (!tbody) {
        return;
      }
      if (!ov) {
        return;
      }
      var bodies = ov.bodies || [];
      var inUse = (ov.platform || {}).bodiesInUse || 0;
      var badge = document.getElementById("bodyCount");
      if (badge) {
        badge.textContent = bodies.length + " \u4E2A";
        badge.className = "mbadge" + (inUse > 0 ? " ok" : "");
      }
      if (!bodies.length) {
        tbody.innerHTML = bodyEmptyRow('\u8FD8\u6CA1\u6709\u77E5\u8BC6\u4F53\uFF0C\u5148\u53BB<a class="dp-target-link" href="infra.html">\u5B58\u50A8\u914D\u7F6E</a>\u5EFA\u4E00\u4E2A');
        return;
      }
      tbody.innerHTML = bodies.map(function(b) {
        var slots = b.slots || [];
        var keyword = slots[0];
        var vector = slots[1];
        var domainList = (b.domains || []).map(function(d) {
          return d.domain + " \u2192 " + d.index + (d.exists ? "" : "\uFF08\u65E0\u7D22\u5F15\uFF09");
        }).join("\n");
        var domainsCell = b.domainCount ? '<span class="dp-chip mono" title="' + esc(domainList) + '">' + b.domainCount + " \u4E2A</span>" : '<span class="dp-chip mute">\u672A\u7ED1\u5B9A</span>';
        return "<tr><td><b>" + esc(b.name || b.id) + "</b>" + (b.name && b.name !== b.id ? '<span class="dp-body-domain">' + esc(b.id) + "</span>" : "") + "</td><td>" + slotChip(keyword) + "</td><td>" + slotChip(vector) + "</td><td>" + modeCell(b) + "</td><td>" + domainsCell + "</td><td>" + fmt(b.documents) + " / " + fmt(b.chunks) + "</td><td>" + statusCell(b.status) + '</td><td class="ta-r"><button class="btn btn-sm btn-outline-secondary" data-body="' + esc(b.id) + '">\u770B\u6587\u6863</button></td></tr>';
      }).join("");
      tbody.querySelectorAll("button[data-body]").forEach(function(btn) {
        btn.addEventListener("click", function() {
          var select = document.getElementById("filterBody");
          if (select) {
            select.value = btn.getAttribute("data-body");
          }
          renderDocs();
        });
      });
    }
    function renderStats() {
      var ov = state.overview;
      if (!ov) {
        return;
      }
      var p = ov.platform || {};
      var stale = ov.stale ? "\uFF08\u65E7\u6570\u636E\uFF09" : "";
      setValue("statDocs", fmt(p.documents));
      setValue("statChunks", fmt(p.chunks));
      setValue("statBodies", fmt(p.bodies) + " \u4E2A", p.bodiesInUse ? "" : "warn");
      setNote("statBodiesNote", (p.bodiesInUse || 0) + " \u4E2A\u5728\u7528" + stale);
    }
    function loadOverview() {
      return Console.api.get("/admin/kb/overview").then(function(ov) {
        state.overview = ov;
        state.limits = ov.limits || null;
        state.bodyOfDomain = {};
        (ov.bodies || []).forEach(function(b) {
          (b.domains || []).forEach(function(d) {
            state.bodyOfDomain[d.domain] = b.id;
          });
        });
        renderStats();
        renderBodies();
        fillBodyFilter();
        fillBackendFilter();
        fillDomainFilter();
        renderDocs();
      }).catch(function(e) {
        setValue("statDocs", "\u2014", "err");
        setValue("statChunks", "\u2014", "err");
        setValue("statBodies", "\u2014", "err");
        setNote("statBodiesNote", "\u8BFB\u53D6\u5931\u8D25\uFF1A" + e.message);
        var tbody = document.getElementById("bodyBody");
        if (tbody) {
          tbody.innerHTML = bodyEmptyRow("\u8BFB\u53D6\u77E5\u8BC6\u4F53\u5931\u8D25\uFF1A" + esc(e.message));
        }
      });
    }
    var DOC_EMPTY_ROW = '<tr class="dp-doc-empty"><td colspan="8"><div><div class="dp-doc-empty-icon"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M9 13h6M9 17h6"/></svg></div>\u6682\u65E0\u6587\u6863\uFF0C\u5728\u4E0A\u65B9\u4E0A\u4F20\u540E\u5C55\u793A\u5728\u8FD9\u91CC</div></td></tr>';
    function bodyOfBodyId(bodyId) {
      var ov = state.overview;
      if (!ov) {
        return null;
      }
      var found = null;
      (ov.bodies || []).forEach(function(b) {
        if (b.id === bodyId) {
          found = b;
        }
      });
      return found;
    }
    function backendCell(domain) {
      var bodyId = state.bodyOfDomain[domain];
      if (!bodyId) {
        return '<span class="dp-chip mute" title="\u8BE5\u57DF\u672A\u7ED1\u5B9A\u77E5\u8BC6\u4F53">\u2014</span>';
      }
      var row = bodyOfBodyId(bodyId);
      if (!row) {
        return '<span class="dp-chip mute">\u2014</span>';
      }
      var ids = [];
      (row.slots || []).forEach(function(s) {
        if (s.state === "ok" && ids.indexOf(s.connectionId) < 0) {
          ids.push(s.connectionId);
        }
      });
      if (!ids.length) {
        return '<span class="dp-chip mute">\u2014</span>';
      }
      return ids.map(function(id) {
        return '<span class="dp-chip mono" title="\u843D\u70B9\uFF08\u5F53\u524D\u7ED1\u5B9A\uFF09">' + esc(id) + "</span>";
      }).join("");
    }
    function bodyNameCell(domain) {
      var bodyId = state.bodyOfDomain[domain];
      if (!bodyId) {
        return '<span class="dp-chip mute">\u672A\u7ED1\u5B9A</span>';
      }
      var row = bodyOfBodyId(bodyId);
      return '<span class="dp-chip">' + esc(row ? row.name || row.id : bodyId) + "</span>";
    }
    function currentFilters() {
      var pick = function(id) {
        var el = document.getElementById(id);
        return el ? el.value : "";
      };
      return { domain: pick("filterDomain"), body: pick("filterBody"), backend: pick("filterBackend") };
    }
    function matches(doc, f) {
      if (f.domain && (doc.domain || "default") !== f.domain) {
        return false;
      }
      if (f.body) {
        var bodyId = state.bodyOfDomain[doc.domain || "default"];
        if (bodyId !== f.body) {
          return false;
        }
      }
      if (f.backend) {
        var bodyId2 = state.bodyOfDomain[doc.domain || "default"];
        var row = bodyId2 ? bodyOfBodyId(bodyId2) : null;
        var hit = row && (row.slots || []).some(function(s) {
          return s.state === "ok" && s.connectionId === f.backend;
        });
        if (!hit) {
          return false;
        }
      }
      return true;
    }
    function setDocCount(n) {
      var el = document.getElementById("docCount");
      if (el) {
        el.textContent = n + " \u7BC7";
        el.className = "mbadge" + (n > 0 ? " ok" : "");
      }
    }
    function renderDocs() {
      var body = document.getElementById("docBody");
      if (!body) {
        return;
      }
      var f = currentFilters();
      var docs = state.docs.filter(function(d) {
        return matches(d, f);
      });
      setDocCount(docs.length);
      if (!docs.length) {
        body.innerHTML = state.docs.length ? '<tr class="dp-doc-empty"><td colspan="8"><div>\u5F53\u524D\u7B5B\u9009\u6CA1\u6709\u5339\u914D\u7684\u6587\u6863</div></td></tr>' : DOC_EMPTY_ROW;
        return;
      }
      body.innerHTML = docs.map(function(d) {
        return "<tr><td><code>" + esc(d.docId || "") + "</code></td><td>" + esc(d.fileName || "") + "</td><td>" + domainLabel(d.domain) + "</td><td>" + bodyNameCell(d.domain || "default") + "</td><td>" + backendCell(d.domain || "default") + "</td><td>" + (d.chunks || 0) + "</td><td>" + (d.exportPath ? '<code class="path" title="\u70B9\u51FB\u590D\u5236">' + esc(d.exportPath) + "</code>" : '<span class="hint">\u2014</span>') + '</td><td class="ta-r"><button class="btn btn-sm btn-outline-danger" data-doc="' + esc(d.docId || "") + '">\u5220\u9664</button></td></tr>';
      }).join("");
      body.querySelectorAll("button[data-doc]").forEach(function(btn) {
        btn.addEventListener("click", function() {
          removeDoc(btn.getAttribute("data-doc"));
        });
      });
      body.querySelectorAll("code.path").forEach(function(code) {
        code.addEventListener("click", function() {
          if (navigator.clipboard) {
            navigator.clipboard.writeText(code.textContent);
          }
        });
      });
    }
    function loadDocuments() {
      var body = document.getElementById("docBody");
      if (body && !state.docs.length) {
        body.innerHTML = '<tr class="dp-doc-empty"><td colspan="8"><div>\u52A0\u8F7D\u4E2D\u2026</div></td></tr>';
      }
      return Console.api.get("/admin/kb/documents").then(function(data) {
        state.docs = data.documents || [];
        renderDocs();
        applyFileChecks();
      }).catch(function(e) {
        setDocCount(0);
        if (body) {
          body.innerHTML = '<tr class="dp-doc-empty"><td colspan="8"><div>\u8BFB\u53D6\u6587\u6863\u5217\u8868\u5931\u8D25\uFF1A' + esc(e.message) + "</div></td></tr>";
        }
      });
    }
    function removeDoc(docId) {
      var bodyId = null;
      var backends = [];
      state.docs.forEach(function(d) {
        if (d.docId !== docId) {
          return;
        }
        bodyId = state.bodyOfDomain[d.domain || "default"] || null;
        var row = bodyId ? bodyOfBodyId(bodyId) : null;
        (row && row.slots ? row.slots : []).forEach(function(s) {
          if (s.state === "ok" && backends.indexOf(s.connectionId) < 0) {
            backends.push(s.connectionId);
          }
        });
      });
      var scope = backends.length ? "\n\u8BE5 docId \u7684\u5207\u7247\u4F1A\u5728\u6240\u6709\u540E\u7AEF\u4E0A\u4E00\u6B21\u5220\u51C0\uFF08\u5F53\u524D\u843D\u70B9\uFF1A" + backends.join("\u3001") + "\uFF09\u3002" : "";
      Console.confirm(
        "\u786E\u8BA4\u5220\u9664\u8BE5\u6587\u6863\uFF1F\u5220\u9664\u540E\u5176\u6587\u4EF6\u540D\u4F1A\u88AB\u91CA\u653E\uFF0C\u53EF\u91CD\u65B0\u4E0A\u4F20\u540C\u540D\u6587\u6863\u3002" + scope,
        { title: "\u5220\u9664\u6587\u6863", okText: "\u5220\u9664", danger: true }
      ).then(function(ok) {
        if (!ok) {
          return;
        }
        Console.api.del("/admin/kb/documents/" + encodeURIComponent(docId)).then(function() {
          loadDocuments();
          loadOverview();
        }).catch(function(e) {
          Console.toast("\u5220\u9664\u5931\u8D25\uFF1A" + e.message, { type: "error" });
        });
      });
    }
    function resetSelect(select, placeholder) {
      if (!select) {
        return;
      }
      select.innerHTML = '<option value="">' + placeholder + "</option>";
    }
    function addOption(select, value, text) {
      if (!select) {
        return;
      }
      var opt = document.createElement("option");
      opt.value = value;
      opt.textContent = text;
      select.appendChild(opt);
    }
    function refillSelect(select, placeholder, fill) {
      if (!select) {
        return;
      }
      var keep = select.value;
      resetSelect(select, placeholder);
      fill(select);
      if (keep) {
        for (var i = 0; i < select.options.length; i++) {
          if (select.options[i].value === keep) {
            select.value = keep;
            break;
          }
        }
      }
    }
    function fillBodyFilter() {
      refillSelect(document.getElementById("filterBody"), "\u5168\u90E8", function(select) {
        (state.overview && state.overview.bodies || []).forEach(function(b) {
          addOption(select, b.id, b.name || b.id);
        });
      });
    }
    function fillBackendFilter() {
      refillSelect(document.getElementById("filterBackend"), "\u5168\u90E8", function(select) {
        var ov = state.overview;
        if (!ov) {
          return;
        }
        var ids = ((ov.platform || {}).backends || []).slice();
        (ov.bodies || []).forEach(function(b) {
          (b.slots || []).forEach(function(s) {
            if (s.state === "ok" && ids.indexOf(s.connectionId) < 0) {
              ids.push(s.connectionId);
            }
          });
        });
        ids.forEach(function(id) {
          addOption(select, id, id);
        });
      });
    }
    function fillDomainFilter() {
      refillSelect(document.getElementById("filterDomain"), "\u5168\u90E8", function(select) {
        addOption(select, "default", "default");
      });
      Console.api.get("/admin/domains").then(function(data) {
        var select = document.getElementById("filterDomain");
        var keep = select ? select.value : "";
        (data.domains || []).forEach(function(d) {
          var name = typeof d === "string" ? d : d.name;
          if (!name || name === "default") {
            return;
          }
          addOption(select, name, name);
        });
        if (keep) {
          for (var i = 0; i < select.options.length; i++) {
            if (select.options[i].value === keep) {
              select.value = keep;
              break;
            }
          }
        }
      }).catch(function() {
      });
    }
    function loadDomainOptions() {
      var select = document.getElementById("domainSelect");
      Console.api.get("/admin/domains").then(function(data) {
        (data.domains || []).forEach(function(d) {
          var name = typeof d === "string" ? d : d.name;
          if (!name || name === "default") {
            return;
          }
          addOption(select, name, name);
        });
      }).catch(function() {
      });
    }
    function targetChips(t) {
      var chips = (t.slots || []).map(function(s) {
        if (s.state === "absent") {
          return "";
        }
        var label = (s.kind === "keyword" ? "\u5173\u952E\u5B57 " : "\u5411\u91CF ") + (s.name || s.connectionId);
        var tone = s.state === "ok" ? "ok" : "warn";
        return '<span class="dp-chip mono ' + tone + '">' + esc(label) + "</span>";
      }).join("");
      return chips || '<span class="dp-chip mute">\u65E0\u843D\u70B9</span>';
    }
    function renderTarget() {
      var box = document.getElementById("uploadTarget");
      if (!box) {
        return;
      }
      var t = state.target;
      if (!t) {
        box.innerHTML = "";
        return;
      }
      if (!t.domainExists) {
        box.innerHTML = '<span class="dp-chip err">' + esc(t.reasonText || "\u57DF\u4E0D\u5B58\u5728") + '</span><a class="dp-target-link" href="domains.html">\u53BB\u57DF\u7A7A\u95F4</a>';
        return;
      }
      if (!t.writable) {
        var jump = t.reason === "BODY_EMPTY" || t.reason === "BODY_UNUSABLE" ? "infra.html" : "domains.html";
        var jumpText = jump === "infra.html" ? "\u53BB\u5B58\u50A8\u914D\u7F6E" : "\u53BB\u57DF\u7A7A\u95F4";
        box.innerHTML = '<span class="dp-chip err">' + esc(t.reasonText || "\u4E0D\u53EF\u5199\u5165") + '</span><a class="dp-target-link" href="' + jump + '">' + jumpText + "</a>";
        return;
      }
      var html = targetChips(t);
      if (t.fanOut) {
        html += '<span class="dp-chip warn" title="\u5173\u952E\u5B57\u4E0E\u5411\u91CF\u5728\u4E24\u53F0\u673A\u5668\u4E0A\uFF0C\u540C\u4E00\u4EFD\u5207\u7247\u8981\u5199\u4E24\u6B21">\u5199\u4E24\u4EFD</span>';
      }
      box.innerHTML = html;
    }
    function loadTarget() {
      var domain = document.getElementById("domainSelect").value || "";
      return Console.api.get("/admin/kb/target?domain=" + encodeURIComponent(domain)).then(function(t) {
        state.target = t;
        if (t.limits) {
          state.limits = t.limits;
        }
        renderTarget();
        applyFileChecks();
      }).catch(function(e) {
        state.target = null;
        var box = document.getElementById("uploadTarget");
        if (box) {
          box.innerHTML = '<span class="dp-chip err">\u9884\u68C0\u5931\u8D25\uFF1A' + esc(e.message) + "</span>";
        }
      });
    }
    function selectedFile() {
      var input = document.getElementById("fileInput");
      return input && input.files && input.files.length ? input.files[0] : null;
    }
    function applyFileChecks() {
      var btn = document.getElementById("uploadBtn");
      var box = document.getElementById("uploadTarget");
      var reason = null;
      var file = selectedFile();
      var limits = state.limits;
      if (state.target && !state.target.writable) {
        reason = "\u57DF\u4E0D\u53EF\u5199";
      }
      if (!reason && file && limits) {
        var ext = (file.name.split(".").pop() || "").toLowerCase();
        var allowed = (limits.allowedExtensions || []).map(function(e) {
          return String(e).toLowerCase();
        });
        if (allowed.length && allowed.indexOf(ext) < 0) {
          reason = "\u4E0D\u652F\u6301\u7684\u6587\u4EF6\u7C7B\u578B\uFF08\u767D\u540D\u5355\uFF1A" + allowed.join(" / ") + "\uFF09";
        } else if (limits.maxFileSize && file.size > limits.maxFileSize) {
          reason = "\u6587\u4EF6\u8D85\u8FC7\u4E0A\u9650 " + Math.round(limits.maxFileSize / 1048576 * 10) / 10 + " MB";
        }
      }
      if (btn) {
        btn.disabled = !!reason;
      }
      if (reason && box && state.target) {
        renderTarget();
        box.innerHTML += '<span class="dp-chip err">' + esc(reason) + "</span>";
        return;
      }
      if (file && state.target && state.target.writable) {
        var domain = state.target.domain || "default";
        var lower = file.name.toLowerCase();
        var dup = state.docs.some(function(d) {
          return (d.domain || "default") === domain && (d.fileNameLower || (d.fileName || "").toLowerCase()) === lower;
        });
        var chk = document.getElementById("replaceChk");
        if (dup) {
          if (chk && !chk.checked) {
            chk.checked = true;
            Console.toast("\u8BE5\u57DF\u4E0B\u5DF2\u5B58\u5728\u540C\u540D\u6587\u6863\uFF0C\u5DF2\u52FE\u9009\u300C\u8986\u76D6\u66F4\u65B0\u300D", { type: "warning" });
          }
          if (box) {
            box.innerHTML = (box.innerHTML || "") + '<span class="dp-chip warn">\u540C\u540D\u5DF2\u5B58\u5728\uFF0C\u5C06\u8986\u76D6</span>';
          }
        }
      }
    }
    function upload() {
      var input = document.getElementById("fileInput");
      var btn = document.getElementById("uploadBtn");
      var box = document.getElementById("uploadResult");
      if (!input.files || !input.files.length) {
        Console.toast("\u8BF7\u5148\u9009\u62E9\u6587\u4EF6", { type: "warning" });
        return;
      }
      var form = new FormData();
      form.append("file", input.files[0]);
      var replace = document.getElementById("replaceChk").checked;
      var domain = document.getElementById("domainSelect").value || "";
      var query = "/admin/kb/documents?replace=" + (replace ? "true" : "false");
      if (domain) {
        query += "&domain=" + encodeURIComponent(domain);
      }
      btn.disabled = true;
      btn.classList.add("btn-loading");
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
          loadOverview();
        }
      }).catch(function(e) {
        box.textContent = "\u8BF7\u6C42\u5931\u8D25: " + e.message;
        box.className = "result show err";
      }).then(function() {
        btn.disabled = false;
        btn.classList.remove("btn-loading");
        applyFileChecks();
      });
    }
    function rebuild() {
      Console.confirm(
        "\u786E\u8BA4\u91CD\u5EFA\u7D22\u5F15\uFF1F\u91CD\u5EFA\u4F1A\u6E05\u7A7A\u73B0\u6709\u7D22\u5F15\uFF0C\u5DF2\u6709\u6587\u6863\u9700\u91CD\u65B0\u4E0A\u4F20\u3002",
        { title: "\u7D22\u5F15\u91CD\u5EFA", okText: "\u91CD\u5EFA", danger: true }
      ).then(function(ok) {
        if (!ok) {
          return;
        }
        var btn = document.getElementById("rebuildBtn");
        var box = document.getElementById("rebuildResult");
        btn.disabled = true;
        btn.classList.add("btn-loading");
        box.className = "result show";
        box.textContent = "\u91CD\u5EFA\u4E2D\u2026\uFF08\u4F1A\u5EFA\u7ACB\u771F\u5B9E\u8FDE\u63A5\u5E76\u8C03\u7528 embedding\uFF0C\u8017\u65F6\u8F83\u957F\uFF09";
        Console.api.post("/admin/kb/rebuild").then(function(data) {
          box.textContent = JSON.stringify(data, null, 2);
          box.className = "result show ok";
          loadDocuments();
          loadOverview();
        }).catch(function(e) {
          box.textContent = "\u8BF7\u6C42\u5931\u8D25: " + e.message;
          box.className = "result show err";
        }).then(function() {
          btn.disabled = false;
          btn.classList.remove("btn-loading");
        });
      });
    }
    function loadBackends() {
      var select = document.getElementById("reindexTarget");
      Console.api.get("/admin/kb/backends").then(function(data) {
        var backends = data.backends || [];
        if (!backends.length) {
          addOption(select, "", "\uFF08\u65E0\u53EF\u7528\u540E\u7AEF\uFF09");
          return;
        }
        backends.forEach(function(b) {
          var caps = b.capabilities || {};
          var tags = [];
          if (caps.vector) {
            tags.push("\u5411\u91CF");
          }
          if (caps.keyword) {
            tags.push("\u5173\u952E\u5B57");
          }
          if (caps.storesText) {
            tags.push("\u5B58\u539F\u6587");
          }
          addOption(select, b.id, b.id + (tags.length ? "\uFF08" + tags.join("/") + "\uFF09" : ""));
          var opt = select.lastChild;
          if (!caps.vector) {
            opt.disabled = true;
          }
        });
      }).catch(function(e) {
        addOption(select, "", "\u8BFB\u53D6\u540E\u7AEF\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function reindex() {
      var select = document.getElementById("reindexTarget");
      var target = select.value;
      if (!target) {
        Console.toast("\u8BF7\u5148\u9009\u62E9\u76EE\u6807\u540E\u7AEF", { type: "warning" });
        return;
      }
      Console.confirm(
        "\u786E\u8BA4\u628A\u5F53\u524D\u5168\u90E8\u77E5\u8BC6\u5E93\u5185\u5BB9\u91CD\u704C\u5230\u540E\u7AEF\u300C" + target + "\u300D\uFF1F\u8BE5\u64CD\u4F5C\u4F1A\u6E05\u7A7A\u76EE\u6807\u540E\u7AEF\u5BF9\u5E94\u7684 collection \u5E76\u636E\u539F\u6587\u91CD\u5EFA\uFF0C\u4F46\u4E0D\u4F1A\u6539\u53D8\u4EFB\u4F55\u57DF\u7684\u68C0\u7D22\u843D\u70B9\u3002",
        { title: "\u8DE8\u540E\u7AEF\u91CD\u704C", okText: "\u91CD\u704C", danger: true }
      ).then(function(ok) {
        if (!ok) {
          return;
        }
        var btn = document.getElementById("reindexBtn");
        var box = document.getElementById("reindexResult");
        btn.disabled = true;
        btn.classList.add("btn-loading");
        box.className = "result show";
        box.textContent = "\u91CD\u704C\u4E2D\u2026\uFF08\u4ECE ChunkArchive \u8BFB\u539F\u6587 \u2192 \u5411\u91CF\u5316 \u2192 \u5199\u5165\u76EE\u6807\u540E\u7AEF\uFF0C\u8017\u65F6\u8F83\u957F\uFF09";
        Console.api.post("/admin/kb/reindex", { targetBackendId: target }).then(function(data) {
          box.textContent = JSON.stringify(data, null, 2);
          box.className = "result show ok";
          loadDocuments();
          loadOverview();
        }).catch(function(e) {
          box.textContent = "\u8BF7\u6C42\u5931\u8D25: " + e.message;
          box.className = "result show err";
        }).then(function() {
          btn.disabled = false;
          btn.classList.remove("btn-loading");
        });
      });
    }
    Console.renderSidebar("knowledge");
    document.getElementById("uploadBtn").addEventListener("click", upload);
    document.getElementById("rebuildBtn").addEventListener("click", rebuild);
    document.getElementById("reindexBtn").addEventListener("click", reindex);
    document.getElementById("domainSelect").addEventListener("change", loadTarget);
    document.getElementById("fileInput").addEventListener("change", applyFileChecks);
    document.getElementById("filterDomain").addEventListener("change", renderDocs);
    document.getElementById("filterBody").addEventListener("change", renderDocs);
    document.getElementById("filterBackend").addEventListener("change", renderDocs);
    document.getElementById("filterClear").addEventListener("click", function() {
      ["filterDomain", "filterBody", "filterBackend"].forEach(function(id) {
        document.getElementById(id).value = "";
      });
      renderDocs();
    });
    Console.ready(function() {
      loadDomainOptions();
      loadBackends();
      loadOverview();
      loadDocuments();
      loadTarget();
      Console.api.get("/admin/settings").then(function(s) {
        var ok = !!s.embeddingConfigured;
        var registered = s.embeddingProfileCount || 0;
        setValue("statEmb", ok ? "\u5DF2\u5C31\u7EEA" : registered ? "\u672A\u542F\u7528" : "\u672A\u914D\u7F6E", ok ? "" : "warn");
        setNote("statEmbNote", ok ? s.embeddingModelName || "" : registered ? "\u5DF2\u767B\u8BB0 " + registered + " \u4E2A\u6863\u6848\uFF0C\u8BF7\u5230\u300C\u6A21\u578B\u8BBE\u7F6E\u300D\u542F\u7528" : "\u68C0\u7D22\u4E0E\u704C\u5E93\u4E0D\u53EF\u7528\uFF0C\u8BF7\u5148\u914D\u7F6E\u5411\u91CF\u6A21\u578B");
        Console.setModelsWarn(!s.chatConfigured);
      }).catch(function() {
        setValue("statEmb", "\u8BFB\u53D6\u5931\u8D25", "err");
      });
    });
  })();
})();

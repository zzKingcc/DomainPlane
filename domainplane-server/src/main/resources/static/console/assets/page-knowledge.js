(() => {
  // src/pages/knowledge.js
  (function() {
    var esc = Console.escapeHtml;
    var state = {
      snap: null,
      limits: null,
      rawOptions: [],
      embeddingReady: false,
      embeddingNote: "",
      connections: [],
      domains: []
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
    var opened = null;
    function closeModal() {
      if (!opened) {
        return;
      }
      var m = opened;
      opened = null;
      document.removeEventListener("keydown", onModalKey);
      if (m.root.parentNode) {
        m.root.parentNode.removeChild(m.root);
      }
      if (m.backdrop.parentNode) {
        m.backdrop.parentNode.removeChild(m.backdrop);
      }
      document.body.style.overflow = m.prevOverflow;
      if (m.onClose) {
        m.onClose();
      }
    }
    function onModalKey(e) {
      if (e.key === "Escape" || e.key === "Esc") {
        e.preventDefault();
        closeModal();
      }
    }
    function openModal(opts) {
      closeModal();
      var prevOverflow = document.body.style.overflow;
      var root = document.createElement("div");
      root.className = "modal dp-preview";
      root.setAttribute("tabindex", "-1");
      root.setAttribute("role", "dialog");
      root.setAttribute("aria-modal", "true");
      root.innerHTML = '<div class="modal-dialog ' + (opts.size || "modal-lg") + ' modal-dialog-scrollable" role="document"><div class="modal-content"><div class="modal-header"><h5 class="modal-title">' + esc(opts.title || "") + '</h5><button type="button" class="btn-close" data-dp-close="1" aria-label="\u5173\u95ED"></button></div><div class="modal-body">' + (opts.body || "") + '</div><div class="modal-footer">' + (opts.footer || "") + '<button type="button" class="btn btn-outline-secondary" data-dp-close="1">\u5173\u95ED</button></div></div></div>';
      var backdrop = document.createElement("div");
      backdrop.className = "modal-backdrop show";
      opened = { root, backdrop, prevOverflow, onClose: opts.onClose };
      document.body.appendChild(root);
      document.body.appendChild(backdrop);
      root.classList.add("show");
      document.body.style.overflow = "hidden";
      document.addEventListener("keydown", onModalKey);
      root.addEventListener("click", function(e) {
        var t = e.target;
        if (t === root || t && t.getAttribute && t.getAttribute("data-dp-close")) {
          closeModal();
        }
      });
      backdrop.addEventListener("click", closeModal);
      if (opts.onOpen) {
        opts.onOpen(root);
      }
      var first = root.querySelector("input, select, textarea, button.btn-primary");
      if (first) {
        first.focus();
      }
      return root;
    }
    function copyText(text) {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text);
      } else {
        var ta = document.createElement("textarea");
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand("copy");
        document.body.removeChild(ta);
      }
    }
    var STATUS_TEXT = {
      ok: ["\u6B63\u5E38", "ok"],
      empty: ["\u672A\u914D\u843D\u70B9", "warn"],
      dead: ["\u843D\u70B9\u4E0D\u53EF\u7528", "err"],
      missing: ["\u4F53\u5DF2\u5220\u9664", "err"],
      no_index: ["\u6709\u7ED1\u5B9A\u65E0\u7D22\u5F15", "warn"]
    };
    function statusChip(status) {
      var pair = STATUS_TEXT[status] || [status || "\u2014", ""];
      return '<span class="dp-chip ' + pair[1] + '">' + esc(pair[0]) + "</span>";
    }
    var SLOT_WHY = {
      missing: "\u8FDE\u63A5\u5DF2\u4ECE\u5B58\u50A8\u914D\u7F6E\u91CC\u5220\u9664",
      blank: "\u8FDE\u63A5\u8FD8\u6CA1\u586B\u5730\u5740",
      unregistered: "\u8FDE\u63A5\u6CA1\u6CE8\u518C\u4E0A\uFF08\u4FDD\u5B58\u5B58\u50A8\u914D\u7F6E\u540E\u751F\u6548\uFF09",
      incapable: "\u8FD9\u4E2A\u8FDE\u63A5\u7C7B\u578B\u63D0\u4F9B\u4E0D\u4E86\u8FD9\u4E00\u8DEF\uFF08\u4F8B\u5982\u5173\u952E\u5B57\u69FD\u6307\u5230\u4E86 Qdrant\uFF09"
    };
    function slotChip(slot, kindLabel) {
      if (!slot || slot.state === "absent") {
        return '<span class="dp-chip mute">\u672A\u914D</span>';
      }
      var name = esc(slot.name || slot.connectionId || "");
      var label = '<span class="dp-slot-kind">' + esc(kindLabel) + "</span>";
      if (slot.state === "ok") {
        return label + '<span class="dp-chip mono ok" title="' + esc((slot.type || "") + " \xB7 " + slot.connectionId) + '">' + name + "</span>";
      }
      return label + '<span class="dp-chip mono warn" title="' + esc(SLOT_WHY[slot.state] || slot.state) + '">' + name + "</span>";
    }
    function modeChips(b) {
      var modes = b.modes || [];
      var effective = b.effectiveModes || [];
      if (!modes.length) {
        return '<span class="dp-chip mute">\u7A7A\u58F3</span>';
      }
      var out = modes.map(function(m) {
        var open = effective.indexOf(m) >= 0;
        var text = m === "keyword" ? "\u5173\u952E\u5B57" : "\u5411\u91CF";
        return '<span class="dp-chip ' + (open ? "" : "warn") + '"' + (open ? "" : ' title="\u5168\u5C40\u68C0\u7D22\u7B56\u7565\u5173\u95ED\u4E86\u8FD9\u4E00\u8DEF"') + ">" + text + (open ? "" : "\uFF08\u7B56\u7565\u5173\u95ED\uFF09") + "</span>";
      }).join("");
      if (b.fanOut) {
        out += '<span class="dp-chip warn" title="\u4E24\u8DEF\u843D\u5728\u4E24\u53F0\u673A\u5668\u4E0A\uFF0C\u540C\u4E00\u4EFD\u5207\u7247\u8981\u5199\u4E24\u6B21">\u5199\u4E24\u4EFD</span>';
      }
      return out;
    }
    function rawChip(b) {
      var raw = b.raw || {};
      var label = raw.label || raw.location || "\u672C\u5730\u78C1\u76D8";
      var detail = raw.detail || "";
      return '<span class="dp-chip" title="' + esc(detail) + '">' + esc(label) + "</span>";
    }
    function domainChips(b) {
      var domains = b.domains || [];
      if (!domains.length) {
        return '<span class="dp-chip mute">\u672A\u7ED1\u5B9A\u4EFB\u4F55\u57DF\uFF08\u6CA1\u6709\u5BF9\u8BDD\u80FD\u67E5\u5230\u5B83\uFF09</span>';
      }
      var shown = domains.slice(0, 3).map(function(d) {
        return '<span class="dp-chip mono">' + esc(d) + "</span>";
      }).join("");
      if (domains.length > 3) {
        shown += '<span class="dp-chip" title="' + esc(domains.join("\n")) + '">+' + (domains.length - 3) + "</span>";
      }
      return shown;
    }
    function bodyCards(bodies) {
      if (!bodies.length) {
        return '<div class="dp-kb-empty"><div class="dp-kb-empty-icon"><svg xmlns="http://www.w3.org/2000/svg" width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="6" rx="8" ry="3"/><path d="M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6"/><path d="M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/></svg></div><h4>\u8FD8\u6CA1\u6709\u77E5\u8BC6\u4F53</h4><p>\u77E5\u8BC6\u4F53\u662F"\u4E00\u4EFD\u53EF\u88AB\u591A\u4E2A\u57DF\u5171\u7528\u7684\u8D44\u6599"\u3002\u5148\u5EFA\u4E00\u4E2A\uFF0C\u9009\u597D\u5173\u952E\u5B57 / \u5411\u91CF\u7684\u843D\u70B9\uFF0C\u518D\u5F80\u91CC\u4F20\u6587\u6863\u3002</p><button class="btn btn-primary" data-act="create">\u65B0\u5EFA\u77E5\u8BC6\u4F53</button></div>';
      }
      return bodies.map(function(b) {
        var id = esc(b.id);
        var ghost = b.status === "missing";
        return '<article class="dp-kb-card s-' + esc(b.status) + '" data-body="' + id + '"><header class="dp-kb-card-head">  <div class="dp-kb-title">    <b>' + esc(b.name || b.id) + '</b>    <code class="dp-kb-slug" title="\u552F\u4E00\u540D\uFF08\u521B\u5EFA\u540E\u4E0D\u53EF\u6539\uFF09">' + esc(b.slug || b.id) + '</code>  </div>  <div class="dp-kb-head-right">' + statusChip(b.status) + '</div></header><div class="dp-kb-index">  <span class="dp-kb-label">\u7D22\u5F15</span>  <code>' + esc(b.index || "\uFF08\u672A\u5206\u914D\uFF09") + "</code>" + (b.index ? '<button class="dp-kb-copy" data-act="copy" data-text="' + esc(b.index) + '" title="\u590D\u5236\u7D22\u5F15\u540D">\u590D\u5236</button>' : "") + "  " + (b.existsIndex ? '<span class="dp-chip ok" title="\u540E\u7AEF\u4E0A\u786E\u5B9E\u6709\u8FD9\u4E2A\u7D22\u5F15">\u5DF2\u5C31\u7EEA</span>' : '<span class="dp-chip warn" title="\u540E\u7AEF\u4E0A\u6CA1\u6709\u8FD9\u4E2A\u96C6\u5408\uFF08\u8FD8\u6CA1\u4E0A\u4F20\u8FC7\u6587\u6863\uFF0C\u6216\u96C6\u5408\u88AB\u5220\u4E86\uFF09">\u65E0\u96C6\u5408</span>') + '</div><div class="dp-kb-meta">  <div class="dp-kb-row"><span class="dp-kb-label">\u53EC\u56DE</span><span class="dp-kb-vals">' + modeChips(b) + '</span></div>  <div class="dp-kb-row">' + slotChip((b.slots || [])[0], "\u5173\u952E\u5B57") + '</div>  <div class="dp-kb-row">' + slotChip((b.slots || [])[1], "\u5411\u91CF") + '</div>  <div class="dp-kb-row"><span class="dp-kb-label">\u539F\u6587</span><span class="dp-kb-vals">' + rawChip(b) + '</span></div></div><div class="dp-kb-stats">  <span><b>' + fmt(b.documents) + "</b> \u6587\u6863</span>  <span><b>" + fmt(b.chunks) + "</b> \u5207\u7247</span>  <span><b>" + fmt((b.domains || []).length) + '</b> \u57DF</span></div><div class="dp-kb-domains">' + domainChips(b) + '</div><footer class="dp-kb-actions">' + (ghost ? '<span class="dp-kb-ghost-note">\u8FD9\u4E2A\u4F53\u7684\u914D\u7F6E\u5DF2\u88AB\u5220\u9664\uFF0C\u4ECD\u6709\u57DF\u7ED1\u5728\u5B83\u4E0A\u9762\u3002\u5220\u6389\u7ED1\u5B9A\u5373\u53EF\u6062\u590D\u3002</span>' : '<button class="btn btn-sm btn-primary" data-act="upload">\u4E0A\u4F20</button><button class="btn btn-sm btn-outline-secondary" data-act="docs">\u6587\u6863</button><button class="btn btn-sm btn-outline-secondary" data-act="health">\u5065\u5EB7</button><button class="btn btn-sm btn-outline-secondary" data-act="rebuild">\u91CD\u5EFA</button><button class="btn btn-sm btn-outline-secondary" data-act="settings">\u8BBE\u7F6E</button>') + '<button class="btn btn-sm btn-outline-secondary" data-act="bind">\u7ED1\u5B9A\u57DF</button><button class="btn btn-sm btn-outline-danger" data-act="delete">\u5220\u9664</button></footer></article>';
      }).join("");
    }
    function renderSnapshot() {
      var snap = state.snap;
      var grid2 = document.getElementById("bodyGrid");
      if (!snap) {
        return;
      }
      var p = snap.platform || {};
      setValue("statDocs", fmt(p.documents), p.documents ? "" : "mute");
      setNote("statDocsNote", (p.bodies || 0) + " \u4E2A\u77E5\u8BC6\u4F53\u5171\u540C\u7D2F\u8BA1");
      setValue("statChunks", fmt(p.chunks), p.chunks ? "" : "mute");
      setNote("statChunksNote", snap.strategy === "HYBRID" ? "\u6DF7\u5408\u53EC\u56DE\uFF08\u5173\u952E\u5B57 + \u5411\u91CF\uFF09" : snap.strategy === "KEYWORD" ? "\u4EC5\u5173\u952E\u5B57\u53EC\u56DE" : "\u4EC5\u5411\u91CF\u53EC\u56DE");
      setValue("statBodies", fmt(p.bodies) + " \u4E2A", p.bodiesInUse || 0 ? "" : "warn");
      setNote("statBodiesNote", (p.bodiesInUse || 0) + " \u4E2A\u5728\u7528 \xB7 " + (p.bodiesUnbound || 0) + " \u4E2A\u6CA1\u7ED1\u5B9A\u57DF");
      var noteEl = document.getElementById("snapshotNote");
      if (noteEl) {
        var bits = [];
        if (snap.stale) {
          bits.push("\u5B58\u50A8\u8BFB\u53D6\u5931\u8D25\uFF0C\u663E\u793A\u7684\u662F\u65E7\u6570\u636E");
        }
        if (snap.generatedAt) {
          bits.push("\u5FEB\u7167 " + new Date(snap.generatedAt).toLocaleTimeString("zh-CN"));
        }
        noteEl.textContent = bits.join(" \xB7 ");
        noteEl.className = "dp-kb-snapshot" + (snap.stale ? " warn" : "");
      }
      if (grid2) {
        grid2.innerHTML = bodyCards(snap.bodies || []);
      }
      var orphans = snap.orphans || [];
      var card = document.getElementById("orphanCard");
      if (card) {
        card.style.display = orphans.length ? "" : "none";
      }
      var badge = document.getElementById("orphanCount");
      if (badge) {
        badge.textContent = orphans.length + " \u4E2A";
      }
      var list = document.getElementById("orphanList");
      if (list) {
        list.innerHTML = orphans.map(function(o) {
          return '<div class="dp-kb-orphan-row"><code>' + esc(o.name) + '</code><span class="dp-kb-orphan-owners">' + ((o.backends || []).map(function(x) {
            return '<span class="dp-chip mono">' + esc(x) + "</span>";
          }).join("") || '<span class="dp-chip mute">\u672A\u77E5</span>') + "</span></div>";
        }).join("");
      }
    }
    function loadSnapshot() {
      return Console.api.get("/admin/bodies").then(function(snap) {
        state.snap = snap;
        state.limits = snap.limits || state.limits;
        state.rawOptions = snap.rawOptions || [];
        renderSnapshot();
      }).catch(function(e) {
        setValue("statDocs", "\u2014", "err");
        setValue("statChunks", "\u2014", "err");
        setValue("statBodies", "\u2014", "err");
        setNote("statBodiesNote", "\u8BFB\u53D6\u5931\u8D25\uFF1A" + e.message);
        var grid2 = document.getElementById("bodyGrid");
        if (grid2) {
          grid2.innerHTML = '<div class="dp-kb-empty"><h4>\u8BFB\u53D6\u77E5\u8BC6\u4F53\u5931\u8D25</h4><p>' + esc(e.message) + '</p><button class="btn btn-outline-secondary" data-act="reload">\u91CD\u8BD5</button></div>';
        }
      });
    }
    function bodyById(id) {
      var snap = state.snap;
      if (!snap) {
        return null;
      }
      var found = null;
      (snap.bodies || []).forEach(function(b) {
        if (b.id === id) {
          found = b;
        }
      });
      return found;
    }
    var REASON_TEXT = {
      NO_BODY: "\u6CA1\u6709\u7ED9\u77E5\u8BC6\u4F53",
      BODY_MISSING: "\u77E5\u8BC6\u4F53\u4E0D\u5B58\u5728\uFF08\u53EF\u80FD\u521A\u88AB\u5220\uFF09",
      BODY_EMPTY: "\u8FD9\u4E2A\u4F53\u4E00\u8DEF\u90FD\u6CA1\u914D",
      BODY_UNUSABLE: "\u8FD9\u4E2A\u4F53\u7684\u8FDE\u63A5\u90FD\u4E0D\u53EF\u7528",
      EMBEDDING_NOT_CONFIGURED: "\u672A\u542F\u7528\u5411\u91CF\u6A21\u578B"
    };
    function reasonFix(reason) {
      if (reason === "EMBEDDING_NOT_CONFIGURED") {
        return { href: "models.html", text: "\u53BB\u6A21\u578B\u8BBE\u7F6E" };
      }
      if (reason === "BODY_EMPTY" || reason === "BODY_UNUSABLE" || reason === "BODY_MISSING") {
        return { href: "infra.html", text: "\u53BB\u5B58\u50A8\u914D\u7F6E" };
      }
      return null;
    }
    function targetHtml(t) {
      if (!t) {
        return "";
      }
      if (!t.writable) {
        var fix = reasonFix(t.reason);
        return '<span class="dp-chip err">' + esc(t.reasonText || REASON_TEXT[t.reason] || "\u4E0D\u53EF\u5199\u5165") + "</span>" + (fix ? '<a class="dp-target-link" href="' + fix.href + '">' + fix.text + "</a>" : "");
      }
      var html = (t.writeBackends || []).map(function(id) {
        return '<span class="dp-chip mono ok">' + esc(id) + "</span>";
      }).join("");
      if (t.fanOut) {
        html += '<span class="dp-chip warn" title="\u5173\u952E\u5B57\u4E0E\u5411\u91CF\u5728\u4E24\u53F0\u673A\u5668\u4E0A\uFF0C\u540C\u4E00\u4EFD\u5207\u7247\u8981\u5199\u4E24\u6B21">\u5199\u4E24\u4EFD</span>';
      }
      return html;
    }
    function docsBodyHtml(b) {
      return '<div class="dp-docs-head"><div><b>' + esc(b.name || b.id) + '</b> <code class="dp-kb-slug">' + esc(b.slug || b.id) + '</code></div><div><span class="dp-kb-label">\u7D22\u5F15</span> <code>' + esc(b.index || "\u2014") + '</code></div></div><div class="dp-upload-row"><input type="file" id="fileInput" accept=".txt,.md,.markdown,.docx,.doc,.pdf,.xls,.xlsx"><label class="dp-op-check"><input type="checkbox" id="replaceChk"> \u8986\u76D6\u66F4\u65B0</label><button class="btn btn-primary btn-sm" id="uploadBtn">\u4E0A\u4F20</button></div><div class="dp-target" id="uploadTarget"></div><div id="uploadResult" class="result"></div><div class="dp-doc-scroll dp-docs-scroll"><table class="table dp-doc-table"><thead><tr><th style="width:30%">docId</th><th style="width:34%">\u6587\u4EF6\u540D</th><th style="width:12%">\u5207\u7247</th><th style="width:24%" class="ta-r">\u64CD\u4F5C</th></tr></thead><tbody id="docBody"><tr class="dp-doc-empty"><td colspan="4"><div>\u52A0\u8F7D\u4E2D\u2026</div></td></tr></tbody></table></div>';
    }
    function openDocs(bodyId) {
      var b = bodyById(bodyId);
      if (!b) {
        return;
      }
      var root = openModal({
        title: "\u6587\u6863 \xB7 " + (b.name || b.id),
        size: "modal-xl",
        body: docsBodyHtml(b),
        footer: '<button class="btn btn-outline-secondary" id="docsRebuildBtn">\u4ECE\u539F\u6587\u91CD\u5EFA</button>',
        onOpen: function(r) {
          bindDocEvents(r, bodyId);
          loadDocs(bodyId);
        }
      });
      state.docsBodyId = bodyId;
      return root;
    }
    var docsCache = [];
    function loadDocs(bodyId) {
      var tbody = document.getElementById("docBody");
      if (!tbody) {
        return;
      }
      return Console.api.get("/admin/bodies/" + encodeURIComponent(bodyId) + "/documents").then(function(data) {
        docsCache = data.documents || [];
        renderDocs();
      }).catch(function(e) {
        tbody.innerHTML = '<tr class="dp-doc-empty"><td colspan="4"><div>\u8BFB\u53D6\u6587\u6863\u5931\u8D25\uFF1A' + esc(e.message) + "</div></td></tr>";
      });
    }
    function renderDocs() {
      var tbody = document.getElementById("docBody");
      if (!tbody) {
        return;
      }
      if (!docsCache.length) {
        tbody.innerHTML = '<tr class="dp-doc-empty"><td colspan="4"><div>\u8FD9\u4E2A\u77E5\u8BC6\u4F53\u8FD8\u6CA1\u6709\u6587\u6863\uFF0C\u5148\u5728\u4E0A\u65B9\u4E0A\u4F20</div></td></tr>';
        return;
      }
      tbody.innerHTML = docsCache.map(function(d) {
        return "<tr><td><code>" + esc(d.docId || "") + "</code></td><td>" + esc(d.fileName || "") + "</td><td>" + fmt(d.chunks) + '</td><td class="ta-r"><button class="btn btn-sm btn-outline-primary" data-view="' + esc(d.docId) + '">\u5207\u7247</button><button class="btn btn-sm btn-outline-danger" data-del="' + esc(d.docId) + '">\u5220\u9664</button></td></tr>';
      }).join("");
      tbody.querySelectorAll("button[data-view]").forEach(function(btn) {
        btn.addEventListener("click", function() {
          previewDoc(btn.getAttribute("data-view"));
        });
      });
      tbody.querySelectorAll("button[data-del]").forEach(function(btn) {
        btn.addEventListener("click", function() {
          removeDoc(btn.getAttribute("data-del"));
        });
      });
    }
    function bindDocEvents(root, bodyId) {
      var file = root.querySelector("#fileInput");
      var btn = root.querySelector("#uploadBtn");
      var replace = root.querySelector("#replaceChk");
      var result = root.querySelector("#uploadResult");
      function preflight() {
        var box = root.querySelector("#uploadTarget");
        Console.api.get("/admin/bodies/target?body=" + encodeURIComponent(bodyId)).then(function(t) {
          if (!opened) {
            return;
          }
          if (box) {
            box.innerHTML = targetHtml(t);
          }
          if (btn) {
            btn.disabled = !t.writable;
          }
          if (!t.writable && result) {
            result.className = "result show err";
            result.textContent = (t.reasonText || REASON_TEXT[t.reason] || "\u4E0D\u53EF\u5199\u5165") + "\uFF1A\u5148\u5904\u7406\u843D\u70B9\uFF0C\u518D\u4E0A\u4F20\u3002\u4E0D\u4F1A\u5199\u5165\u534A\u4E2A\u6587\u6863\u3002";
          }
        }).catch(function(e) {
          if (box) {
            box.innerHTML = '<span class="dp-chip err">\u9884\u68C0\u5931\u8D25\uFF1A' + esc(e.message) + "</span>";
          }
          if (btn) {
            btn.disabled = true;
          }
        });
      }
      preflight();
      if (file) {
        file.addEventListener("change", function() {
          if (btn) {
            btn.disabled = false;
          }
          var limits = state.limits;
          var f = file.files && file.files[0];
          if (!f || !limits) {
            return;
          }
          var ext = (f.name.split(".").pop() || "").toLowerCase();
          var allowed = (limits.allowedExtensions || []).map(function(e) {
            return String(e).toLowerCase();
          });
          if (allowed.length && allowed.indexOf(ext) < 0) {
            if (result) {
              result.className = "result show err";
              result.textContent = "\u4E0D\u652F\u6301\u7684\u6587\u4EF6\u7C7B\u578B\uFF08\u767D\u540D\u5355\uFF1A" + allowed.join(" / ") + "\uFF09";
            }
            if (btn) {
              btn.disabled = true;
            }
          } else if (limits.maxFileSize && f.size > limits.maxFileSize) {
            if (result) {
              result.className = "result show err";
              result.textContent = "\u6587\u4EF6\u8D85\u8FC7\u4E0A\u9650 " + Math.round(limits.maxFileSize / 1048576 * 10) / 10 + " MB";
            }
            if (btn) {
              btn.disabled = true;
            }
          }
        });
      }
      if (btn) {
        btn.addEventListener("click", function() {
          if (!file || !file.files || !file.files.length) {
            Console.toast("\u8BF7\u5148\u9009\u62E9\u6587\u4EF6", { type: "warning" });
            return;
          }
          var form = new FormData();
          form.append("file", file.files[0]);
          var query = "/admin/bodies/" + encodeURIComponent(bodyId) + "/documents?replace=" + (replace && replace.checked ? "true" : "false");
          btn.disabled = true;
          btn.classList.add("btn-loading");
          if (result) {
            result.className = "result show";
            result.textContent = "\u4E0A\u4F20\u4E2D\u2026\uFF08\u5207\u7247 + \u5411\u91CF\u5316\uFF0C\u8017\u65F6\u968F\u6587\u4EF6\u4F53\u79EF\u589E\u957F\uFF09";
          }
          fetch(query, { method: "POST", body: form, credentials: "same-origin" }).then(function(resp) {
            return resp.json().then(function(data) {
              return { status: resp.status, data };
            }).catch(function() {
              return { status: resp.status, data: null };
            });
          }).then(function(w) {
            var d = w.data || {};
            var ok = w.status >= 200 && w.status < 300 && (d.code === 0 || d.code === void 0);
            if (result) {
              result.className = "result show " + (ok ? "ok" : "err");
              result.textContent = ok ? "\u5BFC\u5165\u6210\u529F\uFF1A" + (d.fileName || "\uFF08\u672A\u547D\u540D\uFF09") + "\uFF0C" + fmt(d.chunks) + " \u4E2A\u5207\u7247" : "\u5BFC\u5165\u5931\u8D25\uFF1A" + (d.detail || d.message || d.error || "HTTP " + w.status);
            }
            if (ok) {
              file.value = "";
              loadDocs(bodyId);
              loadSnapshot();
            }
          }).catch(function(e) {
            if (result) {
              result.className = "result show err";
              result.textContent = "\u8BF7\u6C42\u5931\u8D25\uFF1A" + e.message;
            }
          }).then(function() {
            btn.classList.remove("btn-loading");
            preflight();
          });
        });
      }
      var rebuild = root.querySelector("#docsRebuildBtn");
      if (rebuild) {
        rebuild.addEventListener("click", function() {
          closeModal();
          openRebuild(bodyId);
        });
      }
    }
    function previewDoc(docId) {
      var doc = null;
      docsCache.forEach(function(d) {
        if (d.docId === docId) {
          doc = d;
        }
      });
      if (!doc) {
        return;
      }
      var bodyId = state.docsBodyId;
      openModal({
        title: (doc.fileName || doc.docId) + " \xB7 \u5207\u7247\u9884\u89C8",
        size: "modal-lg",
        body: '<div class="dp-preview-body" id="previewBody"><div class="dp-preview-loading">\u8BFB\u53D6\u5207\u7247\u2026</div></div>',
        footer: '<span class="dp-preview-meta" id="previewMeta"></span>',
        onOpen: function(root) {
          Console.api.get("/admin/bodies/" + encodeURIComponent(bodyId) + "/documents/" + encodeURIComponent(docId) + "/chunks?limit=100").then(function(data) {
            var bodyEl = root.querySelector("#previewBody");
            var metaEl = root.querySelector("#previewMeta");
            if (!bodyEl) {
              return;
            }
            var chunks = data.chunks || [];
            var total = data.total || 0;
            if (metaEl) {
              var bits = ["\u5171 " + fmt(total) + " \u7247"];
              if (chunks.length < total) {
                bits.push("\u663E\u793A\u524D " + chunks.length + " \u7247");
              }
              bits.push(data.fromArchive ? "\u6765\u6E90\uFF1A\u539F\u6587\u6863\u6848" : "\u6765\u6E90\uFF1A\u7D22\u5F15\u73B0\u8BFB\uFF08\u6863\u6848\u7F3A\u5931\uFF09");
              metaEl.textContent = bits.join(" \xB7 ");
              metaEl.className = "dp-preview-meta" + (data.fromArchive ? "" : " warn");
            }
            if (!chunks.length) {
              bodyEl.innerHTML = '<div class="dp-preview-empty">\u6CA1\u6709\u8BFB\u5230\u5207\u7247\uFF1A\u539F\u6587\u6863\u6848\u4E0E\u7D22\u5F15\u91CC\u90FD\u6CA1\u6709\u8FD9\u4E2A docId \u7684\u5185\u5BB9\u3002</div>';
              return;
            }
            bodyEl.innerHTML = chunks.map(function(c) {
              return '<div class="dp-chunk"><div class="dp-chunk-head"><span class="dp-chunk-seq">#' + esc(String(c.seq)) + '</span><span class="dp-chunk-len">' + fmt(c.chars) + ' \u5B57</span></div><pre class="dp-chunk-text">' + esc(c.text || "") + "</pre></div>";
            }).join("");
          }).catch(function(e) {
            var bodyEl = root.querySelector("#previewBody");
            if (bodyEl) {
              bodyEl.innerHTML = '<div class="dp-preview-empty">\u8BFB\u53D6\u5207\u7247\u5931\u8D25\uFF1A' + esc(e.message) + "</div>";
            }
          });
        }
      });
    }
    function removeDoc(docId) {
      var bodyId = state.docsBodyId;
      Console.confirm(
        "\u5220\u9664\u8FD9\u4E2A\u6587\u6863\uFF1F\u5B83\u4F1A\u4ECE\u7D22\u5F15\u91CC\u79FB\u9664\u3001\u539F\u6587\u6863\u6848\u4E00\u5E76\u5220\u9664\uFF0C\u6587\u4EF6\u540D\u968F\u5373\u91CA\u653E\uFF08\u53EF\u518D\u4E0A\u4F20\u540C\u540D\u6587\u4EF6\uFF09\u3002",
        { title: "\u5220\u9664\u6587\u6863", okText: "\u5220\u9664", danger: true }
      ).then(function(ok) {
        if (!ok) {
          return;
        }
        Console.api.del("/admin/bodies/" + encodeURIComponent(bodyId) + "/documents/" + encodeURIComponent(docId)).then(function() {
          loadDocs(bodyId);
          loadSnapshot();
        }).catch(function(e) {
          Console.toast("\u5220\u9664\u5931\u8D25\uFF1A" + e.message, { type: "error" });
        });
      });
    }
    function openUpload(bodyId) {
      var b = bodyById(bodyId);
      openModal({
        title: "\u4E0A\u4F20\u6587\u6863 \xB7 " + (b ? b.name || b.id : bodyId),
        body: '<div class="dp-upload-row"><input type="file" id="fileInput" accept=".txt,.md,.markdown,.docx,.doc,.pdf,.xls,.xlsx"><label class="dp-op-check"><input type="checkbox" id="replaceChk"> \u8986\u76D6\u66F4\u65B0</label><button class="btn btn-primary btn-sm" id="uploadBtn">\u4E0A\u4F20</button></div><div class="dp-target" id="uploadTarget"></div><div id="uploadResult" class="result"></div>',
        onOpen: function(root) {
          bindDocEvents(root, bodyId);
          if (root.querySelector("#docsRebuildBtn")) {
          }
        }
      });
    }
    function connectionOptions(kind, selected) {
      var out = '<option value="">\uFF08\u8FD9\u4E00\u8DEF\u4E0D\u914D\uFF09</option>';
      (state.connections || []).forEach(function(c) {
        var capable = kind === "keyword" ? c.type === "elasticsearch" : c.type === "elasticsearch" || c.type === "qdrant";
        if (!capable) {
          return;
        }
        out += '<option value="' + esc(c.id) + '"' + (c.id === selected ? " selected" : "") + ">" + esc(c.name || c.id) + "\uFF08" + esc(c.type) + (c.usable ? "" : "\uFF0C\u672A\u586B\u5730\u5740") + "\uFF09</option>";
      });
      return out;
    }
    function rawOptions(selected) {
      var opts = state.rawOptions || [];
      return opts.map(function(o) {
        return '<option value="' + esc(o.id) + '"' + (o.id === selected ? " selected" : "") + (o.detail ? ' title="' + esc(o.detail) + '"' : "") + ">" + esc(o.label || o.id) + (o.available === false ? "\uFF08\u4E0D\u53EF\u7528\uFF09" : "") + "</option>";
      }).join("");
    }
    function formHtml(b, creating) {
      var rawId = b && b.raw && b.raw.location || "local";
      return '<div class="dp-body-form"><label class="dp-form-field"><span>\u552F\u4E00\u540D\uFF08slug\uFF09<em>\u5FC5\u586B</em></span><input class="form-control" id="bodySlug" value="' + esc(b ? b.slug || b.id : "") + '"' + (creating ? "" : " readonly") + ' placeholder="sales-kb" maxlength="40"><small>' + (creating ? "\u5C0F\u5199\u5B57\u6BCD / \u6570\u5B57 / \u8FDE\u5B57\u7B26\uFF0C2~40 \u4F4D\uFF0C\u9996\u5C3E\u5FC5\u987B\u662F\u5B57\u6BCD\u6216\u6570\u5B57\u3002\u7D22\u5F15\u540D\u7531\u5B83\u6D3E\u751F\uFF0C\u521B\u5EFA\u540E\u4E0D\u53EF\u4FEE\u6539\u3002" : "\u552F\u4E00\u540D\u51B3\u5B9A\u7D22\u5F15\u540D\uFF0C\u521B\u5EFA\u540E\u4E0D\u53EF\u4FEE\u6539\uFF08\u6539\u540D\u4F1A\u7B49\u4E8E\u6362\u4E00\u4EFD\u6570\u636E\uFF09\u3002") + '</small></label><label class="dp-form-field"><span>\u5C55\u793A\u540D</span><input class="form-control" id="bodyName" value="' + esc(b ? b.name || "" : "") + '" placeholder="\u9500\u552E\u77E5\u8BC6\u5E93"></label><label class="dp-form-field"><span>\u8BF4\u660E</span><textarea class="form-control" id="bodyDesc" rows="2" placeholder="\u8FD9\u4EFD\u8D44\u6599\u662F\u7ED9\u8C01\u7528\u7684\u3001\u591A\u4E45\u66F4\u65B0\u4E00\u6B21">' + esc(b && b.description ? b.description : "") + '</textarea></label><div class="dp-form-grid"><label class="dp-form-field"><span>\u5173\u952E\u5B57\u843D\u70B9 <em>\u5168\u6587\u68C0\u7D22</em></span><select class="form-select" id="bodyKeyword">' + connectionOptions("keyword", b ? b.keyword : null) + '</select></label><label class="dp-form-field"><span>\u5411\u91CF\u843D\u70B9 <em>\u8BED\u4E49\u68C0\u7D22</em></span><select class="form-select" id="bodyVector">' + connectionOptions("vector", b ? b.vector : null) + "</select></label></div>" + (creating ? "" : '<div class="dp-form-hint">\u6539\u843D\u70B9\u4E0D\u4F1A\u76F4\u63A5\u751F\u6548\uFF1A\u4FDD\u5B58\u65F6\u5148\u9884\u6F14\uFF0C\u786E\u8BA4\u540E\u628A\u6570\u636E\u642C\uFF08\u6216\u4ECE\u539F\u6587\u91CD\u5EFA\uFF09\u5230\u65B0\u4F4D\u7F6E\uFF0C\u5199\u5B8C\u6821\u9A8C\u901A\u8FC7\u624D\u5207\u6362\u3002</div>') + '<label class="dp-form-field"><span>\u539F\u6587\u843D\u70B9</span><select class="form-select" id="bodyRaw">' + rawOptions(rawId) + '</select><small>\u539F\u6587\u662F\u6743\u5A01\u526F\u672C\uFF1A\u5220\u4E86\u7D22\u5F15\u80FD\u4ECE\u8FD9\u91CC\u91CD\u5EFA\u3002\u6362\u843D\u70B9\u4F1A\u628A\u6863\u6848\u4E0E\u539F\u59CB\u6587\u4EF6\u642C\u8FC7\u53BB\uFF0C\u642C\u5B8C\u6821\u9A8C\u901A\u8FC7\u624D\u5207\u3002\u5BF9\u8C61\u5B58\u50A8\u8FDE\u63A5\u5728\u300C\u5B58\u50A8\u914D\u7F6E\u300D\u9875\u52A0\u3002</small></label><div class="dp-form-hint">\u81F3\u5C11\u9009\u4E00\u8DEF\u3002\u4E24\u8DEF\u6307\u5411\u540C\u4E00\u53F0\u673A\u5668\u65F6\u53EA\u5199\u4E00\u4EFD\uFF1B\u5206\u5C5E\u4E24\u53F0\u65F6\u540C\u4E00\u4EFD\u5207\u7247\u5199\u4E24\u4EFD\u3002</div></div>';
    }
    function readForm(root) {
      return {
        slug: root.querySelector("#bodySlug").value.trim(),
        name: root.querySelector("#bodyName").value.trim(),
        description: root.querySelector("#bodyDesc").value.trim(),
        keyword: root.querySelector("#bodyKeyword").value || null,
        vector: root.querySelector("#bodyVector").value || null,
        raw: root.querySelector("#bodyRaw").value || null
      };
    }
    function openCreate() {
      openModal({
        title: "\u65B0\u5EFA\u77E5\u8BC6\u4F53",
        body: formHtml(null, true),
        footer: '<button class="btn btn-primary" id="bodySave">\u521B\u5EFA</button>',
        onOpen: function(root) {
          root.querySelector("#bodySave").addEventListener("click", function() {
            var f = readForm(root);
            var btn = root.querySelector("#bodySave");
            btn.disabled = true;
            Console.api.post("/admin/bodies", f).then(function(res) {
              closeModal();
              Console.toast("\u5DF2\u521B\u5EFA\u300C" + (res.body && res.body.name || f.name || f.slug) + "\u300D", { type: "success" });
              loadSnapshot();
            }).catch(function(e) {
              btn.disabled = false;
              Console.toast("\u521B\u5EFA\u5931\u8D25\uFF1A" + e.message, { type: "error", wide: true });
            });
          });
        }
      });
    }
    function openSettings(bodyId) {
      var b = bodyById(bodyId);
      if (!b) {
        return;
      }
      openModal({
        title: "\u8BBE\u7F6E \xB7 " + (b.name || b.id),
        body: formHtml(b, false),
        footer: '<button class="btn btn-primary" id="bodySave">\u4FDD\u5B58</button>',
        onOpen: function(root) {
          root.querySelector("#bodySave").addEventListener("click", function() {
            var f = readForm(root);
            var btn = root.querySelector("#bodySave");
            var from = { keyword: b.keyword || null, vector: b.vector || null };
            var to = { keyword: f.keyword || null, vector: f.vector || null };
            var changed = [];
            ["keyword", "vector"].forEach(function(k) {
              if (from[k] !== to[k]) {
                changed.push(k);
              }
            });
            var rawFrom = b.raw && b.raw.location || "local";
            var rawTo = f.raw || "local";
            var rawChanged = rawFrom !== rawTo;
            if (changed.length + (rawChanged ? 1 : 0) > 1) {
              Console.toast(
                "\u4E00\u6B21\u53EA\u6539\u4E00\u9879\uFF1A\u7D22\u5F15\u843D\u70B9\uFF08\u5173\u952E\u5B57 / \u5411\u91CF\uFF09\u4E0E\u539F\u6587\u843D\u70B9\u8981\u5206\u5F00\u8FC1",
                { type: "warning", wide: true }
              );
              return;
            }
            if (changed.length === 1 && !to[changed[0]]) {
              Console.toast(
                "\u4E0D\u80FD\u628A\u8FD9\u4E00\u8DEF\u6E05\u7A7A\uFF1A\u77E5\u8BC6\u4F53\u81F3\u5C11\u8981\u7559\u4E00\u8DEF\u3002\u771F\u8981\u6362\u6389\u5C31\u8FC1\u5230\u53E6\u4E00\u53F0\u8FDE\u63A5",
                { type: "warning", wide: true }
              );
              return;
            }
            btn.disabled = true;
            saveMeta(bodyId, f).then(function() {
              if (rawChanged) {
                return migrateRaw(bodyId, rawTo, rawFrom);
              }
              if (!changed.length) {
                return false;
              }
              return migrateRoute(bodyId, changed[0], to[changed[0]], from[changed[0]]);
            }).then(function(moved) {
              closeModal();
              if (moved) {
                Console.toast(
                  rawChanged ? "\u539F\u6587\u5DF2\u642C\u5230\u65B0\u843D\u70B9\uFF1B\u65E7\u843D\u70B9\u4E0A\u7684\u526F\u672C\u4FDD\u7559\u672A\u5220" : "\u843D\u70B9\u5DF2\u5207\u6362\uFF1A\u7D22\u5F15\u540D\u4E0D\u53D8\uFF0C\u65E7\u8FDE\u63A5\u4E0A\u7684\u540C\u540D\u96C6\u5408\u4FDD\u7559\u672A\u5220",
                  { type: "success", wide: true }
                );
              } else if (changed.length || rawChanged) {
                Console.toast("\u5DF2\u4FDD\u5B58\uFF08\u843D\u70B9\u6CA1\u6709\u8FC1\u79FB\uFF09", { type: "info" });
              } else {
                Console.toast("\u5DF2\u4FDD\u5B58", { type: "success" });
              }
              loadSnapshot();
            }).catch(function(e) {
              btn.disabled = false;
              Console.toast("\u4FDD\u5B58\u5931\u8D25\uFF1A" + e.message, { type: "error", wide: true });
            });
          });
        }
      });
    }
    function saveMeta(bodyId, f) {
      return Console.api.put("/admin/bodies/" + encodeURIComponent(bodyId), {
        name: f.name,
        description: f.description
      });
    }
    function rawLabelOf(id) {
      var opts = state.rawOptions || [];
      for (var i = 0; i < opts.length; i++) {
        if (opts[i].id === id) {
          return opts[i].label || id;
        }
      }
      return id === "local" ? "\u672C\u5730\u539F\u6587\u5B58\u50A8" : id;
    }
    function migrateRaw(bodyId, target, fromId) {
      var url = "/admin/bodies/" + encodeURIComponent(bodyId) + "/routes";
      return Console.api.put(url + "?dryRun=true", { raw: target }).then(function(plan) {
        if (!plan.ready) {
          throw new Error(plan.reason || "\u8FD9\u4E2A\u539F\u6587\u843D\u70B9\u6362\u4E0D\u4E0A\u53BB");
        }
        var lines = [
          "\u628A\u539F\u6587\u4ECE\u300C" + rawLabelOf(fromId) + "\u300D\u642C\u5230\u300C" + rawLabelOf(target) + "\u300D\uFF1F",
          "",
          "\xB7 " + fmt(plan.chunksObjects) + " \u4EFD\u5207\u7247\u6863\u6848 + " + fmt(plan.fileObjects) + " \u4E2A\u539F\u59CB\u6587\u4EF6",
          "\xB7 \u9010\u4E2A\u590D\u5236\u5E76\u6821\u9A8C\u5BF9\u8C61\u6570\uFF0C\u5168\u90E8\u5BF9\u4E0A\u4E4B\u540E\u624D\u5207\u6307\u9488",
          "\xB7 \u642C\u5230\u4E00\u534A\u5931\u8D25\u4F1A\u4E2D\u6B62\uFF0C\u539F\u843D\u70B9\u4E0D\u53D8",
          "\xB7 \u65E7\u843D\u70B9\u4E0A\u7684\u526F\u672C\u4E0D\u4F1A\u81EA\u52A8\u5220\u9664"
        ];
        return Console.confirm(lines.join("\n"), { title: "\u8FC1\u79FB\u539F\u6587", okText: "\u5F00\u59CB\u642C\u8FC1" }).then(function(ok) {
          if (!ok) {
            return false;
          }
          Console.toast("\u6B63\u5728\u642C\u539F\u6587\uFF0C\u5BF9\u8C61\u591A\u65F6\u4F1A\u6BD4\u8F83\u4E45\u2026", { type: "info" });
          return Console.api.put(url, { raw: target }).then(function() {
            return true;
          });
        });
      });
    }
    function migrateRoute(bodyId, kind, target, fromId) {
      var label = kind === "keyword" ? "\u5173\u952E\u5B57" : "\u5411\u91CF";
      var url = "/admin/bodies/" + encodeURIComponent(bodyId) + "/routes";
      return Console.api.put(url + "?dryRun=true", { kind, connection: target }).then(function(plan) {
        if (!plan.ready) {
          throw new Error(plan.reason || "\u8FD9\u4E2A\u843D\u70B9\u6362\u4E0D\u4E0A\u53BB");
        }
        var lines = [
          "\u628A" + label + "\u843D\u70B9\u4ECE\u300C" + (fromId || "\u672A\u914D") + "\u300D\u6362\u5230\u300C" + target + "\u300D\uFF1F",
          "",
          "\xB7 " + fmt(plan.documents) + " \u4E2A\u6587\u6863\u4ECE\u539F\u6587\u91CD\u5EFA\u5230\u65B0\u8FDE\u63A5" + (plan.recomputeVectors ? "\uFF08\u9700\u8981\u91CD\u65B0\u5411\u91CF\u5316\uFF09" : ""),
          "\xB7 \u7D22\u5F15\u540D " + (plan.index || "") + " \u4E0D\u53D8",
          "\xB7 \u5199\u5B8C\u5E76\u6821\u9A8C\u6761\u6570\u4E4B\u540E\u624D\u5207\u6307\u9488\uFF0C\u8FC1\u79FB\u671F\u95F4\u68C0\u7D22\u4ECD\u8D70\u65E7\u8FDE\u63A5",
          "\xB7 \u65E7\u8FDE\u63A5\u4E0A\u7684\u540C\u540D\u96C6\u5408\u4E0D\u4F1A\u5220\u9664"
        ];
        if (plan.missingArchive) {
          lines.push("\xB7 \u6709 " + fmt(plan.missingArchive) + " \u4E2A\u6587\u6863\u6CA1\u6709\u539F\u6587\u6863\u6848\uFF0C\u8FC1\u4E0D\u8FC7\u53BB\uFF08\u4F1A\u8DF3\u8FC7\uFF09");
        }
        return Console.confirm(lines.join("\n"), { title: "\u8FC1\u79FB\u843D\u70B9", okText: "\u5F00\u59CB\u8FC1\u79FB" }).then(function(ok) {
          if (!ok) {
            return false;
          }
          Console.toast("\u6B63\u5728\u8FC1\u79FB\uFF0C\u6587\u6863\u591A\u65F6\u4F1A\u6BD4\u8F83\u4E45\u2026", { type: "info" });
          return Console.api.put(url, { kind, connection: target }).then(function() {
            return true;
          });
        });
      });
    }
    var HEALTH_STATE = {
      absent: ["\u672A\u914D", "mute"],
      missing: ["\u8FDE\u63A5\u5DF2\u5220", "err"],
      blank: ["\u5730\u5740\u6CA1\u586B", "warn"],
      unregistered: ["\u8FDE\u63A5\u672A\u5C31\u7EEA", "warn"],
      unreachable: ["\u4E0D\u53EF\u8FBE", "err"],
      error: ["\u8BFB\u53D6\u5931\u8D25", "err"],
      no_index: ["\u96C6\u5408\u4E0D\u5B58\u5728", "warn"],
      ok: ["\u6B63\u5E38", "ok"]
    };
    function healthHtml(h) {
      function row(label, route) {
        var pair2 = HEALTH_STATE[route.state] || [route.state || "\u2014", ""];
        return "<tr><td>" + esc(label) + "</td><td>" + esc(route.name || route.connectionId || "\u2014") + '</td><td><span class="dp-chip ' + pair2[1] + '">' + esc(pair2[0]) + "</span></td><td>" + (route.exists ? "\u6709" : "\u65E0") + "</td><td>" + (route.dimension == null ? "\u2014" : route.dimension) + "</td></tr>";
      }
      var model = h.model || {};
      var pair = h.consistency === "ok" ? ["ok", "ok"] : h.consistency === "no_index" ? ["warn", "warn"] : h.consistency === "unknown" ? ["mute", "mute"] : ["err", "err"];
      return '<div class="dp-kb-health-head"><span class="dp-chip ' + pair[1] + '">' + esc(pair[0]) + "</span><span>" + esc(h.consistencyText || "") + '</span></div><table class="table dp-kb-health"><thead><tr><th>\u843D\u70B9</th><th>\u8FDE\u63A5</th><th>\u72B6\u6001</th><th>\u96C6\u5408</th><th>\u7EF4\u5EA6</th></tr></thead><tbody>' + row("\u5173\u952E\u5B57", h.keyword || {}) + row("\u5411\u91CF", h.vector || {}) + '</tbody></table><div class="dp-form-hint">\u5F53\u524D\u5411\u91CF\u6A21\u578B\uFF1A' + (model.configured ? "\u7EF4\u5EA6 " + (model.dimension == null ? "\u672A\u77E5" : model.dimension) : "\u672A\u542F\u7528") + " \xB7 \u7D22\u5F15 " + esc(h.index || "\u2014") + "</div>";
    }
    function openHealth(bodyId) {
      var b = bodyById(bodyId);
      if (!b) {
        return;
      }
      openModal({
        title: "\u5065\u5EB7 \xB7 " + (b.name || b.id),
        body: '<div class="dp-kb-loading">\u6B63\u5728\u68C0\u67E5\u4E24\u8DEF\u843D\u70B9\u2026</div>',
        onOpen: function(root) {
          Console.api.get("/admin/bodies/" + encodeURIComponent(bodyId) + "/health").then(function(h) {
            root.querySelector(".modal-body").innerHTML = healthHtml(h);
          }).catch(function(e) {
            root.querySelector(".modal-body").innerHTML = '<div class="dp-kb-empty"><h4>\u68C0\u67E5\u5931\u8D25</h4><p>' + esc(e.message) + "</p></div>";
          });
        }
      });
    }
    function openBind(bodyId) {
      var b = bodyById(bodyId);
      if (!b) {
        return;
      }
      var bound = {};
      (b.domains || []).forEach(function(d) {
        bound[d] = true;
      });
      var names = (state.domains || []).slice();
      if (!names.length) {
        names = (b.domains || []).slice();
      }
      var rows = names.map(function(d) {
        return '<label class="dp-bind-row"><input type="checkbox" value="' + esc(d) + '"' + (bound[d] ? " checked" : "") + "> <code>" + esc(d) + "</code></label>";
      }).join("");
      if (!rows) {
        rows = '<div class="dp-kb-note">\u8FD8\u6CA1\u6709\u53EF\u7ED1\u5B9A\u7684\u57DF\u3002\u5148\u5230\u300C\u57DF\u7A7A\u95F4\u300D\u5EFA\u57DF\u3002</div>';
      }
      openModal({
        title: "\u7ED1\u5B9A\u57DF \xB7 " + (b.name || b.id),
        body: '<div class="dp-kb-note">\u52FE\u4E0A\u7684\u57DF\u53EF\u4EE5\u68C0\u7D22\u5230\u8FD9\u4EFD\u8D44\u6599\uFF1B\u4E00\u4E2A\u4F53\u53EF\u4EE5\u540C\u65F6\u7ED9\u591A\u4E2A\u57DF\u7528\u3002\u6CA1\u52FE\u7684\u57DF\u67E5\u4E0D\u5230\u5B83\uFF08\u4E0D\u7EE7\u627F\u7236\u57DF\u7684\u77E5\u8BC6\u4F53\uFF09\u3002</div><div class="dp-bind-list">' + rows + "</div>",
        footer: '<button class="btn btn-primary" id="bindSave">\u4FDD\u5B58\u7ED1\u5B9A</button>',
        onOpen: function(root) {
          root.querySelector("#bindSave").addEventListener("click", function() {
            var want = {};
            root.querySelectorAll(".dp-bind-row input:checked").forEach(function(i) {
              want[i.value] = true;
            });
            var toBind = Object.keys(want).filter(function(d) {
              return !bound[d];
            });
            var toUnbind = Object.keys(bound).filter(function(d) {
              return !want[d];
            });
            if (!toBind.length && !toUnbind.length) {
              closeModal();
              return;
            }
            var btn = root.querySelector("#bindSave");
            btn.disabled = true;
            var chain = Promise.resolve();
            toBind.forEach(function(d) {
              chain = chain.then(function() {
                return Console.api.post("/admin/bodies/" + encodeURIComponent(bodyId) + "/bind", { domain: d });
              });
            });
            toUnbind.forEach(function(d) {
              chain = chain.then(function() {
                return Console.api.post("/admin/bodies/" + encodeURIComponent(bodyId) + "/unbind", { domain: d });
              });
            });
            chain.then(function() {
              closeModal();
              Console.toast("\u5DF2\u66F4\u65B0\u7ED1\u5B9A\uFF1A+" + toBind.length + " / -" + toUnbind.length, { type: "success" });
              loadSnapshot();
            }).catch(function(e) {
              btn.disabled = false;
              Console.toast("\u7ED1\u5B9A\u5931\u8D25\uFF1A" + e.message, { type: "error", wide: true });
              loadSnapshot();
            });
          });
        }
      });
    }
    function openRebuild(bodyId) {
      var b = bodyById(bodyId);
      if (!b) {
        return;
      }
      openModal({
        title: "\u91CD\u5EFA \xB7 " + (b.name || b.id),
        body: '<div class="dp-kb-note">\u91CD\u5EFA\u53EA\u5F71\u54CD\u8FD9\u4E2A\u77E5\u8BC6\u4F53\u7684\u7D22\u5F15\uFF08<code>' + esc(b.index || "") + '</code>\uFF09\uFF0C\u4E0D\u52A8\u539F\u6587\u3001\u4E0D\u52A8\u5176\u4ED6\u4F53\u3001\u4E5F\u4E0D\u6539\u4EFB\u4F55\u57DF\u7684\u7ED1\u5B9A\u3002</div><div class="dp-rebuild-opt"><label><input type="radio" name="rebuildFrom" value="archive" checked> <b>\u4ECE\u539F\u6587\u91CD\u5EFA</b><span>\u6E05\u7A7A\u7D22\u5F15 \u2192 \u4ECE\u539F\u6587\u6863\u6848\u9010\u7247\u91CD\u65B0\u5411\u91CF\u5316\u5199\u56DE\u3002\u6587\u6863\u4E0D\u4F1A\u4E22\uFF0C\u8017\u65F6\u968F\u6587\u6863\u6570\u589E\u957F\u3002</span></label><label><input type="radio" name="rebuildFrom" value="reset"> <b>\u91CD\u5EFA\u6210\u7A7A\u7D22\u5F15</b><span>\u53EA\u6E05\u7A7A\u3002\u9002\u5408"\u8FD9\u6279\u8D44\u6599\u5168\u90E8\u4F5C\u5E9F\u3001\u8981\u91CD\u65B0\u4E0A\u4F20"\u7684\u573A\u666F\uFF1B\u539F\u6587\u6863\u6848\u540C\u6837\u4FDD\u7559\u3002</span></label></div>',
        footer: '<button class="btn btn-outline-danger" id="rebuildGo">\u5F00\u59CB\u91CD\u5EFA</button>',
        onOpen: function(root) {
          root.querySelector("#rebuildGo").addEventListener("click", function() {
            var from = root.querySelector('input[name="rebuildFrom"]:checked').value;
            closeModal();
            rebuildNow(bodyId, from);
          });
        }
      });
    }
    function rebuildNow(bodyId, from) {
      var b = bodyById(bodyId);
      Console.confirm(
        "\u786E\u8BA4" + (from === "archive" ? "\u4ECE\u539F\u6587\u91CD\u5EFA" : "\u6E05\u7A7A") + "\u300C" + (b ? b.name || b.id : bodyId) + "\u300D\u7684\u7D22\u5F15\uFF1F" + (from === "archive" ? "\n\u91CD\u5EFA\u671F\u95F4\u8FD9\u4E2A\u4F53\u6682\u65F6\u4E0D\u53EF\u68C0\u7D22\u3002" : "\n\u6E05\u7A7A\u540E\u9700\u8981\u91CD\u65B0\u4E0A\u4F20\u6587\u6863\u3002"),
        { title: "\u91CD\u5EFA\u77E5\u8BC6\u4F53", okText: "\u5F00\u59CB", danger: true }
      ).then(function(ok) {
        if (!ok) {
          return;
        }
        Console.toast("\u5DF2\u5F00\u59CB\u91CD\u5EFA\uFF0C\u53EF\u80FD\u9700\u8981\u51E0\u5206\u949F\u2026", { type: "info" });
        Console.api.post("/admin/bodies/" + encodeURIComponent(bodyId) + "/rebuild", { from }).then(function(res) {
          Console.toast(res.message || "\u91CD\u5EFA\u5B8C\u6210", { type: "success", wide: true });
          loadSnapshot();
        }).catch(function(e) {
          Console.toast("\u91CD\u5EFA\u5931\u8D25\uFF1A" + e.message, { type: "error", wide: true });
        });
      });
    }
    function openDelete(bodyId) {
      var b = bodyById(bodyId);
      if (!b) {
        return;
      }
      var bound = b.domains || [];
      openModal({
        title: "\u5220\u9664\u77E5\u8BC6\u4F53 \xB7 " + (b.name || b.id),
        body: '<div class="dp-kb-note">\u5220\u9664\u4F1A\u79FB\u9664<b>\u7D22\u5F15\u91CC\u7684\u6570\u636E</b>\uFF0C\u5E76\u4ECE\u914D\u7F6E\u91CC\u53BB\u6389\u8FD9\u4E2A\u4F53\u3002' + (bound.length ? '<br><b class="dp-danger-text">\u8FD8\u6709 ' + bound.length + " \u4E2A\u57DF\u7ED1\u7740\u5B83\uFF08" + esc(bound.join("\u3001")) + "\uFF09\u3002</b>\u5220\u9664\u4F1A\u540C\u65F6\u89E3\u7ED1\u8FD9\u4E9B\u57DF\uFF0C\u5B83\u4EEC\u968F\u5373\u5931\u53BB\u77E5\u8BC6\u5E93\u3002" : "<br>\u5F53\u524D\u6CA1\u6709\u57DF\u7ED1\u7740\u5B83\u3002") + '</div><label class="dp-op-check"><input type="checkbox" id="keepRaw" checked> \u4FDD\u7559\u539F\u6587\u6863\u6848\uFF08\u63A8\u8350\uFF09</label><div class="dp-form-hint">\u4FDD\u7559\u539F\u6587\u540E\uFF0C\u4EE5\u540E\u53EF\u4EE5\u518D\u5EFA\u4E00\u4E2A\u540C\u540D\u4F53\u3001\u628A\u6863\u6848\u6309\u539F\u6587\u91CD\u5EFA\u56DE\u6765\uFF1B\u4E0D\u4FDD\u7559\u5219\u539F\u6587\u4E00\u5E76\u5220\u9664\uFF0C\u4E0D\u53EF\u6062\u590D\u3002</div><label class="dp-form-field"><span>\u8F93\u5165 <code>' + esc(b.name || b.id) + '</code> \u786E\u8BA4</span><input class="form-control" id="deleteConfirm" placeholder="' + esc(b.name || b.id) + '"></label>',
        footer: '<button class="btn btn-outline-danger" id="deleteGo">\u5220\u9664</button>',
        onOpen: function(root) {
          root.querySelector("#deleteGo").addEventListener("click", function() {
            var typed = root.querySelector("#deleteConfirm").value.trim();
            if (!typed) {
              Console.toast("\u8BF7\u8F93\u5165\u77E5\u8BC6\u4F53\u540D\u79F0\u786E\u8BA4", { type: "warning" });
              return;
            }
            var keepRaw = root.querySelector("#keepRaw").checked;
            var btn = root.querySelector("#deleteGo");
            btn.disabled = true;
            var url = "/admin/bodies/" + encodeURIComponent(bodyId) + "?keepRaw=" + (keepRaw ? "true" : "false") + "&confirm=" + encodeURIComponent(typed) + "&force=true";
            Console.api.del(url).then(function(res) {
              closeModal();
              Console.toast("\u5DF2\u5220\u9664\u300C" + (b.name || b.id) + "\u300D" + (res.rawKept ? "\uFF08\u539F\u6587\u5DF2\u4FDD\u7559\uFF09" : ""), { type: "success", wide: true });
              loadSnapshot();
            }).catch(function(e) {
              btn.disabled = false;
              Console.toast("\u5220\u9664\u5931\u8D25\uFF1A" + e.message, { type: "error", wide: true });
              loadSnapshot();
            });
          });
        }
      });
    }
    function onGridClick(e) {
      var t = e.target;
      var btn = t && t.closest ? t.closest("[data-act]") : null;
      if (!btn) {
        return;
      }
      var act = btn.getAttribute("data-act");
      if (act === "create") {
        openCreate();
        return;
      }
      if (act === "reload") {
        loadSnapshot();
        return;
      }
      if (act === "copy") {
        copyText(btn.getAttribute("data-text") || "");
        Console.toast("\u5DF2\u590D\u5236\u7D22\u5F15\u540D", { type: "success" });
        return;
      }
      var card = btn.closest(".dp-kb-card");
      if (!card) {
        return;
      }
      var bodyId = card.getAttribute("data-body");
      if (act === "upload") {
        openUpload(bodyId);
      } else if (act === "docs") {
        openDocs(bodyId);
      } else if (act === "health") {
        openHealth(bodyId);
      } else if (act === "rebuild") {
        openRebuild(bodyId);
      } else if (act === "settings") {
        openSettings(bodyId);
      } else if (act === "bind") {
        openBind(bodyId);
      } else if (act === "delete") {
        openDelete(bodyId);
      }
    }
    function loadConnections() {
      return Console.api.get("/admin/infra").then(function(data) {
        state.connections = data.connections || [];
      }).catch(function() {
        state.connections = [];
      });
    }
    function loadDomains() {
      return Console.api.get("/admin/domains").then(function(data) {
        state.domains = (data.domains || []).map(function(d) {
          return typeof d === "string" ? d : d.name || d.domain;
        }).filter(Boolean);
      }).catch(function() {
        state.domains = [];
      });
    }
    function loadEmbedding() {
      return Console.api.get("/admin/settings").then(function(s) {
        state.embeddingReady = !!s.embeddingConfigured;
        state.embeddingNote = s.embeddingModelName || "";
        setValue("statEmb", state.embeddingReady ? "\u5DF2\u5C31\u7EEA" : s.embeddingProfileCount ? "\u672A\u542F\u7528" : "\u672A\u914D\u7F6E", state.embeddingReady ? "" : "warn");
        setNote("statEmbNote", state.embeddingReady ? state.embeddingNote : s.embeddingProfileCount ? "\u5DF2\u767B\u8BB0\u6863\u6848\uFF0C\u8BF7\u5230\u300C\u6A21\u578B\u8BBE\u7F6E\u300D\u542F\u7528" : "\u68C0\u7D22\u4E0E\u704C\u5E93\u4E0D\u53EF\u7528\uFF0C\u8BF7\u5148\u914D\u7F6E\u5411\u91CF\u6A21\u578B");
        Console.setModelsWarn(!s.chatConfigured);
      }).catch(function() {
        setValue("statEmb", "\u8BFB\u53D6\u5931\u8D25", "err");
      });
    }
    Console.renderSidebar("knowledge");
    var grid = document.getElementById("bodyGrid");
    grid.addEventListener("click", onGridClick);
    document.getElementById("createBodyBtn").addEventListener("click", openCreate);
    document.getElementById("refreshBtn").addEventListener("click", function() {
      loadSnapshot();
    });
    Console.ready(function() {
      loadEmbedding();
      loadConnections();
      loadDomains();
      loadSnapshot();
    });
  })();
})();

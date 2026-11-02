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

  // src/pages/domains.js
  (function() {
    var esc = Console.escapeHtml;
    var DATA = null;
    var SELECTED = "";
    var EXPANDED = {};
    var PROFILES = [];
    var BINDINGS = {};
    var MCP_BINDINGS = {};
    var MCP_CONNS = [];
    var BIND_DOMAIN = "";
    var BIND_PICK = emptyPick();
    var GRACE_MS = null;
    var KNOWLEDGE_BODIES = [];
    var KB_STATS = null;
    var KIND_LABEL = { keyword: "\u5173\u952E\u5B57", vector: "\u5411\u91CF" };
    var DOM_ICON = '<svg class="dom-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2 2 7l10 5 10-5-10-5z"/><path d="M2 12l10 5 10-5"/><path d="M2 17l10 5 10-5"/></svg>';
    var EP_LABEL = {
      chat: "\u5BF9\u8BDD",
      embedding: "\u5411\u91CF",
      rerank: "\u91CD\u6392",
      images: "\u751F\u56FE",
      tts: "\u8BED\u97F3\u5408\u6210",
      asr: "\u8BED\u97F3\u8BC6\u522B",
      video: "\u89C6\u9891"
    };
    var MOD_LABEL = {
      text: "\u6587\u672C",
      image: "\u56FE\u7247",
      audio: "\u97F3\u9891",
      video: "\u89C6\u9891",
      file: "\u6587\u4EF6",
      embedding: "\u5411\u91CF"
    };
    var BIND_FAMILIES = ["images", "tts", "asr", "video"];
    var FAMILY_LABEL = {
      chat: "\u4E3B\u6A21\u578B\uFF08\u5BF9\u8BDD\uFF09",
      images: "\u751F\u56FE",
      tts: "\u8BED\u97F3\u5408\u6210",
      asr: "\u8BED\u97F3\u8BC6\u522B",
      video: "\u751F\u89C6\u9891"
    };
    var SOURCE_TAG_CLS = { BUILTIN: "src-builtin", MANUAL: "src-manual", DERIVED: "src-derived" };
    function num(n) {
      return n == null ? 0 : n;
    }
    function tagList(list, map) {
      return (list || []).map(function(k) {
        return '<span class="tag public">' + esc(map[k] || k) + "</span>";
      }).join("");
    }
    function aliasBase(modelName) {
      return (modelName || "").trim().replace(/\s+/g, "-") || "model";
    }
    function dupNo(p) {
      var base = aliasBase(p.modelName);
      var alias = (p.alias || "").trim();
      if (alias === base) {
        return 0;
      }
      var m = alias.match(/-(\d+)$/);
      return m && alias.slice(0, m.index) === base ? parseInt(m[1], 10) : 0;
    }
    function modelLabel(p) {
      var n = dupNo(p);
      return (p.modelName || p.alias) + (n > 1 ? " #" + n : "");
    }
    function sourceTag(d) {
      var s = String(d && d.source || "").toUpperCase();
      if (s === "BUILTIN") {
        return '<span class="tag approval" title="\u6839\u57DF\uFF1A\u6574\u68F5\u57DF\u6811\u7684\u8D77\u70B9\uFF0C\u4E0D\u53EF\u5220\u9664">\u6839\u57DF</span>';
      }
      if (s === "MANUAL") {
        return '<span class="tag perm" title="\u4EBA\u5DE5\u521B\u5EFA\uFF0C\u843D\u76D8\u4FDD\u5B58\uFF0C\u53EF\u5220\u9664">\u4EBA\u5DE5\u521B\u5EFA</span>';
      }
      return '<span class="tag" title="\u7531\u5DE5\u5177\u7684\u57DF\u58F0\u660E\u6D3E\u751F\uFF1A\u58F0\u660E\u5B83\u7684\u5DE5\u5177\u88AB\u65AD\u5F00\u540E\uFF0C\u57DF\u4ECD\u5728\uFF0C\u53EA\u662F\u6682\u65F6\u6CA1\u6709\u5DE5\u5177">\u5DE5\u5177\u6D3E\u751F</span>';
    }
    function toolTag(t) {
      var cls = "perm";
      var se = String(t.sideEffect || "").toLowerCase();
      if (se === "read") {
        cls = "read";
      } else if (se === "write") {
        cls = "write";
      } else if (se === "destructive") {
        cls = "destructive";
      }
      var tip = [
        esc(t.category || ""),
        esc(t.provider || ""),
        t.requiresApproval ? "\u9700\u4EBA\u5DE5\u786E\u8BA4" : ""
      ].filter(Boolean).join(" \xB7 ");
      return '<span class="tag ' + cls + '" title="' + tip + '">' + esc(t.name) + "</span>";
    }
    function candidatesFor(family) {
      var list = [];
      PROFILES.forEach(function(p) {
        if ((p.bindableFamilies || []).indexOf(family) < 0) {
          return;
        }
        list.push({
          alias: p.alias,
          label: modelLabel(p),
          note: p.baseUrl || "",
          /* 能不能看图：input 含 image。绑定弹窗里必须显式给出：
             它决定"用户发图时图进谁的消息"，绑错了要等真发一张图才报 91001 */
          vision: (p.input || []).indexOf("image") >= 0,
          tags: tagList(p.endpoints, EP_LABEL) + tagList(p.input, MOD_LABEL)
        });
      });
      return list;
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
    function echoSwitch(d) {
      return '<label class="echo-switch" title="\u5173\u95ED\u540E\u8C03\u7528\u65B9\u53EA\u6536\u5230\u6700\u7EC8\u7B54\u590D\uFF0C\u4E0D\u6536\u5230\u5DE5\u5177\u8C03\u7528\u4E0E\u6267\u884C\u7ED3\u679C"><span class="tune-switch"><input type="checkbox" data-echo="' + esc(d.name) + '"' + (d.toolCallEcho === true ? " checked" : "") + '><span class="tune-switch-track"><span class="tune-switch-knob"></span></span></span><span class="echo-switch-label">\u662F\u5426\u56DE\u5199\u5DE5\u5177\u6267\u884C\u4FE1\u606F</span></label>';
    }
    function planSwitch(d) {
      return '<label class="echo-switch" title="\u6253\u5F00\u540E\u8C03\u7528\u65B9\u5728\u672C\u8F6E\u7B2C\u4E00\u4E2A TOKEN \u524D\u6536\u5230\u672C\u8F6E\u6267\u884C\u8BA1\u5212\uFF08PLAN \u4E8B\u4EF6\uFF09\uFF1B\u89C4\u5212\u672C\u8EAB\u505A\u4E0D\u505A\u7531\u5168\u5C40\u5F00\u5173 domainplane.planning.enabled \u63A7\u5236"><span class="tune-switch"><input type="checkbox" data-plan="' + esc(d.name) + '"' + (d.planDisplay === true ? " checked" : "") + '><span class="tune-switch-track"><span class="tune-switch-knob"></span></span></span><span class="echo-switch-label">\u662F\u5426\u5C55\u793A\u6267\u884C\u8BA1\u5212</span></label>';
    }
    function resolveAlong(domain, family) {
      var chain = chainOf(domain);
      for (var i = chain.length - 1; i >= 0; i--) {
        var b = BINDINGS[chain[i]];
        var list = b && b[family] || [];
        if (list.length) {
          return { aliases: list, source: chain[i] };
        }
      }
      return { aliases: [], source: null };
    }
    function primaryAliasOf(aliases) {
      var hit = (aliases || []).filter(function(a) {
        var p = PROFILES.filter(function(x) {
          return x.alias === a;
        })[0];
        return !!p && p.usable === true && p.bindable === true;
      })[0];
      return hit || null;
    }
    function shortName(domain) {
      var i = String(domain || "").lastIndexOf(".");
      return i < 0 ? String(domain || "") : domain.slice(i + 1);
    }
    function childrenMap() {
      var map = {};
      var names = {};
      (DATA && DATA.domains || []).forEach(function(d) {
        names[d.name] = true;
      });
      (DATA && DATA.domains || []).forEach(function(d) {
        var p = d.parentId && names[d.parentId] ? d.parentId : "";
        (map[p] || (map[p] = [])).push(d);
      });
      return map;
    }
    function renderTree() {
      var box = document.getElementById("treeList");
      var domains = DATA && DATA.domains || [];
      if (!box) {
        return;
      }
      document.getElementById("domainCount").textContent = domains.length + " \u4E2A\u57DF";
      if (!domains.length) {
        box.innerHTML = '<div class="dp-domain-empty">\u5F53\u524D\u6CA1\u6709\u4EFB\u4F55\u57DF\u3002\u6839\u57DF <span class="mono">default</span> \u7531\u670D\u52A1\u7AEF\u81EA\u5EFA\uFF0C\u5176\u4F59\u57DF\u7528\u4E0A\u65B9\u8F93\u5165\u6846\u65B0\u5EFA\uFF0C\u6807\u8BC6\u987B\u4E3A\u4ECE\u6839\u57DF\u51FA\u53D1\u7684\u5B8C\u6574\u8DEF\u5F84\u3002</div>';
        return;
      }
      var map = childrenMap();
      box.innerHTML = treeLevel(map[""] || [], map, 0);
      bindTreeEvents(box);
    }
    function treeLevel(list, map, depth) {
      return list.map(function(d) {
        var kids = map[d.name] || [];
        var expanded = !!EXPANDED[d.name] || depth === 0;
        var hasKids = kids.length > 0;
        var tagCls = SOURCE_TAG_CLS[String(d.source || "").toUpperCase()] || "src-derived";
        var srcLabel = d.source === "BUILTIN" ? "\u6839\u57DF" : d.source === "MANUAL" ? "\u4EBA\u5DE5" : "\u6D3E\u751F";
        var html = '<div class="dp-tree-row' + (d.name === SELECTED ? " on" : "") + '" data-domain="' + esc(d.name) + '">' + (hasKids ? '<button class="dp-tree-chev" data-chev="' + esc(d.name) + '" title="' + (expanded ? "\u6298\u53E0" : "\u5C55\u5F00") + '">' + (expanded ? "\u25BE" : "\u25B8") + "</button>" : '<span class="dp-tree-chev leaf"></span>') + '<span class="dp-tree-dot ' + (d.callable ? "on" : "off") + '" title="' + (d.callable ? "\u53EF\u8C03\u7528\u5355\u5143" : "\u88C5\u914D\u8282\u70B9") + '"></span><span class="dp-tree-name" title="' + esc(d.name) + (d.pendingDelete ? "\uFF08\u5F85\u5220\u9664\uFF09" : "") + '">' + esc(shortName(d.name)) + (d.pendingDelete ? ' <span class="tag destructive">\u5F85\u5220\u9664</span>' : "") + '</span><span class="dp-tree-tag ' + tagCls + '">' + srcLabel + '</span><span class="dp-tree-count">' + num(d.toolCount) + "</span></div>";
        if (hasKids && expanded) {
          html += '<div class="dp-tree-kids">' + treeLevel(kids, map, depth + 1) + "</div>";
        }
        return html;
      }).join("");
    }
    function bindTreeEvents(box) {
      box.querySelectorAll(".dp-tree-row").forEach(function(row) {
        row.addEventListener("click", function() {
          selectDomain(row.getAttribute("data-domain"));
        });
      });
      box.querySelectorAll(".dp-tree-chev[data-chev]").forEach(function(btn) {
        btn.addEventListener("click", function(e) {
          e.stopPropagation();
          var name = btn.getAttribute("data-chev");
          EXPANDED[name] = !EXPANDED[name];
          renderTree();
        });
      });
    }
    function selectDomain(name) {
      SELECTED = name;
      renderTree();
      renderDetail();
    }
    function expandAncestors(name) {
      chainOf(name).slice(0, -1).forEach(function(seg) {
        EXPANDED[seg] = true;
      });
    }
    function currentDomain() {
      var domains = DATA && DATA.domains || [];
      for (var i = 0; i < domains.length; i++) {
        if (domains[i].name === SELECTED) {
          return domains[i];
        }
      }
      return null;
    }
    function renderDetail() {
      var box = document.getElementById("detailBody");
      if (!box) {
        return;
      }
      var d = currentDomain();
      if (!d) {
        box.innerHTML = '<div class="card dp-panel"><div class="card-body"><div class="dp-editor-empty">\u4ECE\u5DE6\u4FA7\u9009\u4E00\u4E2A\u57DF\u67E5\u770B\u4E0E\u914D\u7F6E\u3002</div></div></div>';
        return;
      }
      var resolved = resolveAlong(d.name, "chat");
      var primaryAlias = primaryAliasOf(resolved.aliases);
      var boundTags = resolved.aliases.length ? resolved.aliases.map(function(a) {
        var p = PROFILES.filter(function(x) {
          return x.alias === a;
        })[0];
        var label = p ? modelLabel(p) : a;
        var marks = "";
        if (p && (p.input || []).indexOf("image") >= 0) {
          marks += ' <span class="tag read" title="\u8F93\u5165\u6A21\u6001\u542B image\uFF1A\u80FD\u770B\u56FE">\u53EF\u770B\u56FE</span>';
        }
        if (a === primaryAlias) {
          marks += ' <span class="tag perm" title="\u4E3B\u6A21\u578B\uFF1A\u5217\u8868\u9996\u4E2A\u53EF\u7528\u7684\u6863\u6848\uFF0C\u672C\u8F6E\u5BF9\u8BDD\u7531\u5B83\u56DE\u7B54">\u4E3B</span>';
        }
        return '<span class="tag public mono">' + esc(label) + "</span>" + marks;
      }).join(" ") + (resolved.source === d.name ? "" : '<span class="card-note">\u7EE7\u627F\u81EA ' + esc(resolved.source) + "</span>") : '<span class="tag write">\u65E0\u53EF\u8C03\u7528\u6A21\u578B</span>';
      var tools = (d.tools || []).map(toolTag).join("");
      var toolLine = tools ? '<div class="tool-tags">' + tools + "</div>" : '<span class="perm-none">\u8BE5\u57DF\u6CA1\u6709\u53EF\u89C1\u5DE5\u5177</span>';
      var mcpConns = d.mcp && d.mcp.connections || [];
      var mcpInherited = d.mcp && d.mcp.inheritedFrom;
      var mcpLine = mcpConns.length || mcpInherited ? '<div><span class="k">MCP \u8FDE\u63A5</span>' + mcpConns.map(function(c) {
        return '<span class="tag public mono">' + esc(c) + "</span>";
      }).join(" ") + (mcpInherited ? ' <span class="card-note">\u7EE7\u627F\u81EA ' + esc(mcpInherited) + "</span>" : "") + "</div>" : "";
      var kb = d.knowledge || {};
      var stat = kb.body ? kbRetrievableOf(d.name) : null;
      var bodyStat = kb.body ? kbStatOf(kb.body) : null;
      var statNote = !kb.body ? "" : !KB_STATS ? '<span class="card-note">\u7EDF\u8BA1\u52A0\u8F7D\u4E2D\u2026</span>' : KB_STATS.failed ? '<span class="card-note">\u7EDF\u8BA1\u4E0D\u53EF\u7528</span>' : '<span class="card-note">\u6587\u6863 ' + num(stat && stat.documents) + " \xB7 \u5207\u7247 " + num(stat && stat.chunks) + (KB_STATS.stale ? " \uFF08\u65E7\u6570\u636E\uFF09" : "") + "</span>" + (bodyStat && bodyStat.domainCount > 1 ? ' <span class="card-note">\u4F53\u5171 ' + num(bodyStat.documents) + " \xB7 " + bodyStat.domainCount + " \u57DF\u5171\u7528</span>" : "");
      var kbLine = '<div><span class="k">\u77E5\u8BC6\u4F53</span>' + kbSelect(d.name, kb) + statNote + (kb.bodyMissing ? ' <span class="tag destructive" title="\u8FD9\u4E2A\u57DF\u7ED1\u7684\u77E5\u8BC6\u4F53\u5DF2\u88AB\u5220\uFF0C\u672C\u7AD9\u6CA1\u6709\u77E5\u8BC6\u5E93">\u77E5\u8BC6\u4F53\u5DF2\u5931\u6548</span>' : "") + (kb.bodyDead ? ' <span class="tag destructive" title="\u77E5\u8BC6\u4F53\u7684\u8FDE\u63A5\u5168\u90FD\u4E0D\u53EF\u7528\uFF0C\u68C0\u7D22\u5DE5\u5177\u5DF2\u4ECE\u8FD9\u4E2A\u57DF\u4E0B\u7EBF">\u77E5\u8BC6\u4F53\u8FDE\u63A5\u4E0D\u53EF\u7528</span>' : "") + (d.runningSessions ? ' <span class="tag approval">\u5728\u98DE\u4F1A\u8BDD ' + num(d.runningSessions) + "</span>" : "") + "</div>";
      var parentLine = d.parentId ? '<div><span class="k">\u4E0A\u7EA7\u57DF</span><button class="dp-jump" data-jump="' + esc(d.parentId) + '">' + esc(d.parentId) + "</button></div>" : "";
      box.innerHTML = '<section class="card dp-panel dp-detail-card"><div class="card-body"><header class="card-head">' + DOM_ICON + '<h2 class="mono">' + esc(d.name) + "</h2>" + sourceTag(d) + (d.pendingDelete ? '<span class="tag destructive" title="\u5904\u4E8E\u8F6F\u5220\u9664\u5BBD\u9650\u671F\uFF0C\u5230\u671F\u540E\u5F7B\u5E95\u6E05\u7406">\u5F85\u5220\u9664</span>' : "") + (d.callable ? '<span class="tag read" title="\u53EF\u8C03\u7528\u5355\u5143\uFF1A\u80FD\u4F5C\u4E3A\u5165\u53E3\u88AB\u8C03\u7528">\u53EF\u8C03\u7528</span>' : '<span class="tag approval" title="\u88C5\u914D\u8282\u70B9\uFF1A\u53EA\u628A\u5DE5\u5177 / \u63D0\u793A\u8BCD / \u6A21\u578B / \u77E5\u8BC6\u4F20\u7ED9\u540E\u4EE3\uFF0C\u4E0D\u80FD\u76F4\u63A5\u5F53\u5165\u53E3">\u88C5\u914D\u8282\u70B9</span>') + '<span class="spacer"></span>' + (d.callable ? echoSwitch(d) + planSwitch(d) : "") + '<span class="card-note">\u5DE5\u5177 ' + num(d.toolCount) + " \xB7 \u9700\u786E\u8BA4 " + num(d.approvalCount) + "</span>" + (d.hasChildren ? "" : '<button class="btn btn-sm btn-outline-secondary" data-callable="' + esc(d.name) + '" data-to="' + (d.callable ? "false" : "true") + '">' + (d.callable ? "\u8BBE\u4E3A\u88C5\u914D\u8282\u70B9" : "\u8BBE\u4E3A\u53EF\u8C03\u7528") + "</button>") + '<button class="btn btn-sm btn-outline-secondary" data-cfg="' + esc(d.name) + '">\u914D\u7F6E\u6A21\u578B</button><button class="btn btn-sm btn-outline-secondary" data-mcpcfg="' + esc(d.name) + '">\u914D\u7F6E MCP</button>' + (d.deletable && !d.pendingDelete ? '<button class="btn btn-sm btn-outline-danger" data-del="' + esc(d.name) + '">\u5220\u9664</button>' : "") + "</header>" + (d.pendingDelete ? pendingDeleteBox(d) : "") + (d.hasChildren ? '<p class="dp-kb-note">\u8BE5\u57DF\u6709 ' + num(d.directChildCount) + " \u4E2A\u76F4\u63A5\u5B50\u57DF\uFF08\u5168\u90E8\u540E\u4EE3 " + num(d.childrenCount) + ' \u4E2A\uFF09\uFF0C\u6309"\u53EA\u6709\u53F6\u5B50\u57DF\u53EF\u4F5C\u4E3A\u53EF\u8C03\u7528\u5355\u5143"\u7684\u89C4\u5219\uFF0C\u5B83\u53EA\u80FD\u4F5C\u88C5\u914D\u8282\u70B9\uFF0C\u4E0D\u80FD\u5F53\u5165\u53E3\u3002\u9700\u8981\u8FD9\u4E00\u5C42\u5165\u53E3\u8BF7\u53E6\u5EFA\u65E0\u5B50\u57DF\u7684\u57DF\u3002</p>' : "") + '<div class="model-meta">' + parentLine + '<div><span class="k">\u53EF\u8C03\u7528\u6A21\u578B</span>' + boundTags + "</div>" + capabilityLineOf(d.name) + mcpLine + kbLine + "</div><h3>\u53EF\u89C1\u5DE5\u5177\uFF08\u542B\u6CBF\u94FE\u7EE7\u627F\uFF09</h3>" + toolLine + "</div></section>";
      bindDetailEvents(box);
    }
    function capabilityLineOf(domain) {
      var parts = [];
      BIND_FAMILIES.forEach(function(family) {
        var resolved = resolveAlong(domain, family);
        if (!resolved.aliases.length) {
          return;
        }
        var names = resolved.aliases.map(function(a) {
          var p = PROFILES.filter(function(x) {
            return x.alias === a;
          })[0];
          return '<span class="tag public mono">' + esc(p ? modelLabel(p) : a) + "</span>";
        }).join(" ");
        parts.push('<span class="k">' + esc(FAMILY_LABEL[family]) + "</span> " + names + (resolved.source === domain ? "" : ' <span class="card-note">\u7EE7\u627F\u81EA ' + esc(resolved.source) + "</span>"));
      });
      return parts.length ? '<div><span class="k">\u80FD\u529B\u6A21\u578B</span>' + parts.join(" \xB7 ") + "</div>" : "";
    }
    function bindDetailEvents(box) {
      box.querySelectorAll("button[data-cfg]").forEach(function(b) {
        b.addEventListener("click", function() {
          openBind(b.getAttribute("data-cfg"));
        });
      });
      box.querySelectorAll("button[data-mcpcfg]").forEach(function(b) {
        b.addEventListener("click", function() {
          openMcpBind(b.getAttribute("data-mcpcfg"));
        });
      });
      box.querySelectorAll("button[data-callable]").forEach(function(b) {
        b.addEventListener("click", function() {
          toggleCallable(b.getAttribute("data-callable"), b.getAttribute("data-to") === "true");
        });
      });
      box.querySelectorAll("button[data-del]").forEach(function(b) {
        b.addEventListener("click", function() {
          deleteDomain(b.getAttribute("data-del"));
        });
      });
      box.querySelectorAll("button[data-restore]").forEach(function(b) {
        b.addEventListener("click", function() {
          restoreDomain(b.getAttribute("data-restore"), b);
        });
      });
      box.querySelectorAll("button[data-purge]").forEach(function(b) {
        b.addEventListener("click", function() {
          purgeNow(b.getAttribute("data-purge"), b);
        });
      });
      box.querySelectorAll("input[data-echo]").forEach(function(input) {
        input.addEventListener("change", function() {
          saveEcho(input);
        });
      });
      box.querySelectorAll("input[data-plan]").forEach(function(input) {
        input.addEventListener("change", function() {
          savePlan(input);
        });
      });
      box.querySelectorAll("select[data-kb]").forEach(function(sel) {
        sel.addEventListener("change", function() {
          saveKb(sel);
        });
      });
      box.querySelectorAll(".dp-jump[data-jump]").forEach(function(b) {
        b.addEventListener("click", function() {
          var name = b.getAttribute("data-jump");
          expandAncestors(name);
          selectDomain(name);
        });
      });
    }
    function saveEcho(input) {
      var domain = input.getAttribute("data-echo");
      var on = input.checked;
      input.disabled = true;
      Console.api.put("/admin/domains/" + encodeURIComponent(domain) + "/tool-call-echo", { echo: on }).catch(function(e) {
        input.checked = !on;
        Console.toast("\u4FDD\u5B58\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message), { type: "error" });
      }).then(function() {
        input.disabled = false;
      });
    }
    function savePlan(input) {
      var domain = input.getAttribute("data-plan");
      var on = input.checked;
      input.disabled = true;
      Console.api.put("/admin/domains/" + encodeURIComponent(domain) + "/plan-display", { display: on }).catch(function(e) {
        input.checked = !on;
        Console.toast("\u4FDD\u5B58\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message), { type: "error" });
      }).then(function() {
        input.disabled = false;
      });
    }
    function kbSelect(domain, kb) {
      var bound = kb.body || "";
      var known = KNOWLEDGE_BODIES.some(function(b) {
        return b.id === bound;
      });
      var options = "";
      if (bound && !known) {
        options += '<option value="' + esc(bound) + '" selected>' + esc(kb.bodyName || bound) + "\uFF08\u5DF2\u5220\u9664\uFF09</option>";
      }
      options += '<option value=""' + (bound ? "" : " selected") + ">\uFF08\u4E0D\u6302\u77E5\u8BC6\u5E93\uFF09</option>";
      KNOWLEDGE_BODIES.forEach(function(b) {
        var modes = (b.modes || []).map(function(k) {
          return KIND_LABEL[k] || k;
        }).join("+");
        var tail = modes || "\u672A\u914D";
        if (b.usable === false) {
          tail += " \xB7 \u8FDE\u63A5\u4E0D\u53EF\u7528";
        }
        options += '<option value="' + esc(b.id) + '"' + (b.id === bound ? " selected" : "") + ">" + esc(b.name || b.id) + "\uFF08" + esc(tail) + "\uFF09</option>";
      });
      return '<select class="form-select" style="width:auto;min-width:12rem;display:inline-block;vertical-align:middle;" data-kb="' + esc(domain) + '">' + options + "</select>";
    }
    function saveKb(select) {
      var domain = select.getAttribute("data-kb");
      var body = select.value || null;
      select.disabled = true;
      Console.api.put("/admin/domains/" + encodeURIComponent(domain) + "/body", { body }).then(function() {
        Console.toast("\u300C" + shortName(domain) + "\u300D\u7684\u77E5\u8BC6\u4F53\u5DF2\u66F4\u65B0", { type: "success" });
      }).catch(function(e) {
        Console.toast("\u4FDD\u5B58\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message), { type: "error" });
      }).then(function() {
        select.disabled = false;
        load();
      });
    }
    function emptyPick() {
      return { chat: [], images: [], tts: [], asr: [], video: [] };
    }
    function openBind(domain) {
      BIND_DOMAIN = domain;
      BIND_PICK = emptyPick();
      var bound = BINDINGS[domain] || {};
      Object.keys(BIND_PICK).forEach(function(family) {
        BIND_PICK[family] = (bound[family] || []).slice();
      });
      document.getElementById("bindDomainName").textContent = domain;
      renderBindCards();
      document.getElementById("bindModal").hidden = false;
      document.body.style.overflow = "hidden";
    }
    function closeBind() {
      document.getElementById("bindModal").hidden = true;
      document.body.style.overflow = "";
    }
    function renderBindCards() {
      var box = document.getElementById("bindCardList");
      var empty = document.getElementById("bindEmpty");
      if (!PROFILES.length) {
        box.innerHTML = "";
        empty.style.display = "block";
        empty.textContent = "\u8FD8\u6CA1\u6709\u4EFB\u4F55\u6A21\u578B\u6863\u6848\uFF0C\u8BF7\u5148\u5230\u300C\u6A21\u578B\u8BBE\u7F6E\u300D\u6DFB\u52A0\u3002";
        return;
      }
      empty.style.display = "none";
      var chatList = candidatesFor("chat");
      var visionNote = chatList.length && !chatList.some(function(c) {
        return c.vision;
      }) ? '<div class="card-note" style="grid-column:1/-1;">\u5F53\u524D\u6CA1\u6709\u53EF\u770B\u56FE\u7684\u6863\u6848\uFF08\u8F93\u5165\u6A21\u6001\u542B image\uFF09\uFF0C\u7ED1\u5B9A\u540E\u7528\u6237\u53D1\u56FE\u4F1A\u62A5\u300C\u6CA1\u6709\u53EF\u7528\u7684\u89C6\u89C9\u6A21\u578B\u300D\u3002\u6A21\u6001\u8BFB\u4E0D\u51FA\u6765\u7684\u6863\u6848\u8981\u5728\u300C\u6A21\u578B\u8BBE\u7F6E\u300D\u70B9\u4E00\u6B21\u300C\u63A2\u6D4B\u300D\u8865\u9F50\u3002</div>' : "";
      var html = sectionHead("chat", chatList.length) + visionNote + cardsHtml("chat", chatList);
      BIND_FAMILIES.forEach(function(family) {
        var list = candidatesFor(family);
        html += sectionHead(family, list.length) + cardsHtml(family, list);
      });
      box.innerHTML = html;
      box.querySelectorAll("div[data-pick]").forEach(function(card) {
        card.addEventListener("click", function() {
          var family = card.getAttribute("data-family");
          var alias = card.getAttribute("data-pick");
          var list = BIND_PICK[family] || (BIND_PICK[family] = []);
          var i = list.indexOf(alias);
          if (i >= 0) {
            list.splice(i, 1);
          } else {
            list.push(alias);
          }
          renderBindCards();
        });
      });
    }
    function sectionHead(family, count) {
      var note = count ? "" : " \xB7 \u6CA1\u6709\u53EF\u7528\u4E8E\u300C" + esc(FAMILY_LABEL[family]) + "\u300D\u7684\u6863\u6848\uFF0C\u5230\u300C\u6A21\u578B\u8BBE\u7F6E\u300D\u6DFB\u52A0\u5B83\u3001\u5E76\u70B9\u4E00\u6B21\u300C\u63A2\u6D4B\u300D\u786E\u8BA4\u7AEF\u70B9\u65CF";
      return '<div class="card-note" style="grid-column:1/-1; margin-top:8px;"><strong>' + esc(FAMILY_LABEL[family]) + "</strong>" + note + "</div>";
    }
    function cardsHtml(family, list) {
      var picked = BIND_PICK[family] || [];
      return list.map(function(c) {
        var on = picked.indexOf(c.alias) >= 0;
        return '<div class="pick-card' + (on ? " on" : "") + '" data-pick="' + esc(c.alias) + '" data-family="' + esc(family) + '"><div class="pc-name">' + esc(c.label) + (on ? " \u2713" : "") + (c.vision ? ' <span class="tag read" title="\u8F93\u5165\u6A21\u6001\u542B image\uFF1A\u80FD\u770B\u56FE">\u53EF\u770B\u56FE</span>' : "") + "</div>" + (c.note ? '<div class="pc-note mono">' + esc(c.note) + "</div>" : "") + (c.tags ? '<div class="pc-tags">' + c.tags + "</div>" : "") + "</div>";
      }).join("");
    }
    function saveBind(pick) {
      var capabilities = {};
      BIND_FAMILIES.forEach(function(family) {
        capabilities[family] = pick[family] || [];
      });
      return Console.api.put("/admin/model-bindings/" + encodeURIComponent(BIND_DOMAIN), {
        aliases: pick.chat || [],
        capabilities
      }).then(function() {
        closeBind();
        load();
      }).catch(function(e) {
        Console.toast("\u8BBE\u7F6E\u5931\u8D25\uFF1A" + e.message, { type: "error" });
      });
    }
    var MCP_BIND_DOMAIN = "";
    var MCP_BIND_PICK = [];
    function mcpStatusTag(c) {
      if (!c.enabled) {
        return '<span class="tag" title="\u5DF2\u505C\u7528\uFF1A\u4E0D\u8FDE\u63A5\u3001\u4E0D\u6CE8\u518C\u5DE5\u5177">\u5DF2\u7981\u7528</span>';
      }
      if (c.ready) {
        return '<span class="tag read">\u5DF2\u5C31\u7EEA</span>';
      }
      if (c.status === "FAILED") {
        return '<span class="tag destructive" title="' + esc(c.lastError || "") + '">\u8FDE\u63A5\u5931\u8D25</span>';
      }
      return '<span class="tag">\u672A\u8FDE\u63A5</span>';
    }
    function openMcpBind(domain) {
      MCP_BIND_DOMAIN = domain;
      MCP_BIND_PICK = (MCP_BINDINGS[domain] || []).slice();
      document.getElementById("mcpBindDomainName").textContent = domain;
      renderMcpCards();
      document.getElementById("mcpBindModal").hidden = false;
      document.body.style.overflow = "hidden";
    }
    function closeMcpBind() {
      document.getElementById("mcpBindModal").hidden = true;
      document.body.style.overflow = "";
    }
    function inheritedMcp(domain) {
      var chain = chainOf(domain);
      for (var i = chain.length - 2; i >= 0; i--) {
        var b = MCP_BINDINGS[chain[i]];
        if (b && b.length) {
          return { domain: chain[i], conns: b };
        }
      }
      return null;
    }
    function renderMcpCards() {
      var box = document.getElementById("mcpBindCardList");
      var empty = document.getElementById("mcpBindEmpty");
      if (!MCP_CONNS.length) {
        box.innerHTML = "";
        empty.style.display = "block";
        empty.textContent = "\u8FD8\u6CA1\u6709\u4EFB\u4F55 MCP \u8FDE\u63A5\uFF0C\u8BF7\u5148\u5230\u300CMCP \u8FDE\u63A5\u300D\u9875\u6DFB\u52A0\u3002";
        return;
      }
      empty.style.display = "none";
      var inherited = inheritedMcp(MCP_BIND_DOMAIN);
      box.innerHTML = MCP_CONNS.map(function(c) {
        var on = MCP_BIND_PICK.indexOf(c.name) >= 0;
        var viaAncestor = inherited && inherited.conns.indexOf(c.name) >= 0;
        return '<div class="pick-card' + (on ? " on" : "") + '" data-mcpick="' + esc(c.name) + '"><div class="pc-name">' + esc(c.name) + (on ? " \u2713" : "") + " " + mcpStatusTag(c) + "</div>" + (c.url ? '<div class="pc-note mono">' + esc(c.url) + "</div>" : "") + '<div class="pc-tags"><span class="tag">\u5DE5\u5177 ' + (c.ready ? c.toolCount : 0) + "</span>" + (viaAncestor ? '<span class="tag approval" title="\u5DF2\u7ED1\u5B9A\u5728 ' + esc(inherited.domain) + '\uFF0C\u5176\u5DE5\u5177\u5BF9\u672C\u57DF\u53EF\u89C1\uFF0C\u4E0D\u5FC5\u91CD\u590D\u7ED1\u5B9A">\u7EE7\u627F\u81EA ' + esc(inherited.domain) + "</span>" : "") + "</div></div>";
      }).join("");
      box.querySelectorAll("div[data-mcpick]").forEach(function(card) {
        card.addEventListener("click", function() {
          var n = card.getAttribute("data-mcpick");
          var i = MCP_BIND_PICK.indexOf(n);
          if (i >= 0) {
            MCP_BIND_PICK.splice(i, 1);
          } else {
            MCP_BIND_PICK.push(n);
          }
          renderMcpCards();
        });
      });
    }
    function saveMcpBind(connections) {
      return Console.api.put("/admin/mcp-bindings/" + encodeURIComponent(MCP_BIND_DOMAIN), { connections }).then(function() {
        closeMcpBind();
        load();
      }).catch(function(e) {
        Console.toast("\u8BBE\u7F6E\u5931\u8D25\uFF1A" + e.message, { type: "error" });
      });
    }
    function createDomain() {
      var input = document.getElementById("newDomain");
      var id = (input.value || "").trim();
      if (!id) {
        Console.toast("\u8BF7\u5148\u586B\u5199\u57DF\u6807\u8BC6", { type: "warning" });
        input.focus();
        return;
      }
      var callable = document.getElementById("newDomainCallable").checked;
      Console.api.post("/admin/domains", { id, callable }).then(function() {
        input.value = "";
        EXPANDED[id] = true;
        SELECTED = id;
        load();
      }).catch(function(e) {
        Console.toast("\u521B\u5EFA\u5931\u8D25\uFF1A" + e.message, { type: "error" });
      });
    }
    function toggleCallable(id, callable) {
      return __async(this, null, function* () {
        var tip = callable ? "\u628A " + id + " \u8BBE\u4E3A\u53EF\u8C03\u7528\u5355\u5143\uFF1F\u4E4B\u540E\u5B83\u53EF\u4EE5\u4F5C\u4E3A\u5165\u53E3\u88AB\u8C03\u7528\u3002" : "\u628A " + id + " \u8BBE\u4E3A\u88C5\u914D\u8282\u70B9\uFF1F\u4E4B\u540E\u5B83\u4E0D\u80FD\u518D\u4F5C\u4E3A\u5165\u53E3\uFF0C\u53EA\u7ED9\u540E\u4EE3\u63D0\u4F9B\u5DE5\u5177 / \u63D0\u793A\u8BCD / \u6A21\u578B / \u77E5\u8BC6\u3002";
        var ok = yield Console.confirm(tip, {
          title: "\u5207\u6362\u53EF\u8C03\u7528\u6027",
          okText: "\u786E\u8BA4\u5207\u6362",
          danger: false
        });
        if (!ok) {
          return;
        }
        Console.api.put("/admin/domains/" + encodeURIComponent(id) + "/callable", { callable }).then(function() {
          load();
        }).catch(function(e) {
          Console.toast("\u5207\u6362\u5931\u8D25\uFF1A" + e.message, { type: "error" });
        });
      });
    }
    function deleteDomain(id) {
      return __async(this, null, function* () {
        var ok = yield Console.confirm(
          "\u786E\u8BA4\u5220\u9664\u57DF " + id + "\uFF1F\n\n\u7B2C\u4E00\u6B65\uFF08\u672C\u64CD\u4F5C\uFF09\uFF1A\u6807\u8BB0\u4E3A\u5F85\u5220\u9664\uFF0C\u8FDB\u5165 " + graceText(GRACE_MS || 0) + " \u5BBD\u9650\u671F\n\xB7 \u57DF\u53CA\u5176\u5B50\u57DF\u90FD\u8FD8\u5728\uFF0C\u77E5\u8BC6\u5E93 / \u63D0\u793A\u8BCD / \u6A21\u578B\u7ED1\u5B9A\u4E00\u4E2A\u90FD\u4E0D\u52A8\n\xB7 \u53EA\u662F\u4E0D\u518D\u63A5\u53D7\u65B0\u4F1A\u8BDD\uFF08\u62A5 10011\uFF09\uFF1B\u8FDB\u884C\u4E2D\u7684\u4F1A\u8BDD\u4E0E\u5F85\u4EBA\u5DE5\u786E\u8BA4\u7684\u65AD\u70B9\u7167\u5E38\u6536\u5C3E\n\xB7 \u5BBD\u9650\u671F\u5185\u53EF\u4EE5\u300C\u64A4\u9500\u5220\u9664\u300D\uFF0C\u64A4\u9500\u540E\u4E00\u5207\u7167\u65E7\n\n\u7B2C\u4E8C\u6B65\uFF08\u5BBD\u9650\u671F\u5230\u70B9\uFF0C\u81EA\u52A8\u6267\u884C\uFF0C\u4E0D\u53EF\u64A4\u9500\uFF09\uFF1A\n\xB7 \u9012\u5F52\u5220\u9664\u5B83\u7684\u5168\u90E8\u5B50\u57DF\n\xB7 \u8BE5\u57DF\u53CA\u5176\u5B50\u57DF\u7684\u77E5\u8BC6\u5E93\u7D22\u5F15\u4E0E\u5207\u7247\u9884\u89C8\u6587\u4EF6\u4F1A\u88AB\u5220\u9664\n\xB7 \u8BE5\u57DF\u53CA\u5176\u5B50\u57DF\u7684\u63D0\u793A\u8BCD\u3001\u6A21\u578B\u7ED1\u5B9A\u3001\u5DE5\u5177\u58F0\u660E\u8BB0\u5F55\u4F1A\u4E00\u5E76\u6E05\u7406\n\xB7 \u7ED1\u5B9A\u8BE5\u57DF\u7684\u8C03\u7528\u65B9\u4F1A\u5728\u542F\u52A8\u6821\u9A8C\u6216\u9996\u6B21\u8C03\u7528\u65F6\u62A5\u300C\u57DF\u4E0D\u5B58\u5728\u300D",
          { title: "\u5220\u9664\u57DF", okText: "\u6807\u8BB0\u5220\u9664", danger: true }
        );
        if (!ok) {
          return;
        }
        Console.api.del("/admin/domains/" + encodeURIComponent(id)).then(function(r) {
          load();
          if (r && r.pendingDelete) {
            Console.toast(
              "\u5DF2\u6807\u8BB0\u5220\u9664\uFF1A" + graceText(GRACE_MS || 0) + "\u540E\u81EA\u52A8\u5F7B\u5E95\u6E05\u9664\u3002\u8FD9\u4E0D\u662F\u5931\u8D25\u2014\u2014\u671F\u95F4\u53EF\u5728\u53F3\u4FA7\u70B9\u300C\u64A4\u9500\u5220\u9664\u300D\u6062\u590D\u3002",
              { type: "success", title: "\u5DF2\u8FDB\u5165\u5220\u9664\u5BBD\u9650\u671F", wide: true }
            );
          }
        }).catch(function(e) {
          Console.toast("\u5220\u9664\u5931\u8D25\uFF1A" + e.message, { type: "error", wide: true });
        });
      });
    }
    function restoreDomain(id, btn) {
      Console.busy(btn, function() {
        return Console.api.post("/admin/domains/" + encodeURIComponent(id) + "/restore", {}).then(function() {
          Console.toast("\u5DF2\u64A4\u9500\u5220\u9664\uFF1A" + id + " \u6062\u590D\u6B63\u5E38", { type: "success" });
          load();
        }).catch(function(e) {
          Console.toast("\u64A4\u9500\u5931\u8D25\uFF1A" + e.message, { type: "error", wide: true });
        });
      }, "\u64A4\u9500\u4E2D\u2026");
    }
    function purgeNow(id, btn) {
      return __async(this, null, function* () {
        var ok = yield Console.confirm(
          "\u7ACB\u5373\u5F7B\u5E95\u5220\u9664\u57DF " + id + "\uFF1F\n\n\xB7 \u8DF3\u8FC7\u5BBD\u9650\u671F\uFF0C\u73B0\u5728\u5C31\u9012\u5F52\u6E05\u9664\u5B83\u548C\u5168\u90E8\u5B50\u57DF\n\xB7 \u77E5\u8BC6\u5E93\u7D22\u5F15\u4E0E\u5207\u7247\u3001\u63D0\u793A\u8BCD\u3001\u6A21\u578B\u7ED1\u5B9A\u3001\u5DE5\u5177\u58F0\u660E\u4E00\u5E76\u6E05\u7406\n\xB7 \u4E0D\u7B49\u5728\u98DE\u7684\u4F1A\u8BDD\u6536\u5C3E\uFF0C\u4E5F\u4E0D\u53EF\u64A4\u9500\uFF08\u8BEF\u5220\u53EA\u80FD\u4ECE\u5386\u53F2\u7248\u672C\u5E93\u56DE\u6EDA\uFF09",
          { title: "\u7ACB\u5373\u5F7B\u5E95\u5220\u9664", okText: "\u5F7B\u5E95\u5220\u9664", danger: true }
        );
        if (!ok) {
          return;
        }
        Console.busy(btn, function() {
          return Console.api.del("/admin/domains/" + encodeURIComponent(id) + "?force=true").then(function() {
            if (SELECTED === id || chainOf(SELECTED).indexOf(id) >= 0) {
              SELECTED = "";
            }
            delete EXPANDED[id];
            Console.toast("\u5DF2\u5F7B\u5E95\u5220\u9664\uFF1A" + id, { type: "success" });
            load();
          }).catch(function(e) {
            Console.toast("\u5F7B\u5E95\u5220\u9664\u5931\u8D25\uFF1A" + e.message + "\n\uFF08\u5DF2\u4E2D\u6B62\uFF0C\u57DF\u4E0E\u5176\u6570\u636E\u5747\u672A\u5220\u9664\uFF09", { type: "error", wide: true });
          });
        }, "\u5220\u9664\u4E2D\u2026");
      });
    }
    function loadGrace() {
      return Console.api.get("/admin/domains/storage").then(function(r) {
        GRACE_MS = r && r.deleteGraceMs != null ? r.deleteGraceMs : 0;
      }).catch(function() {
        GRACE_MS = null;
      });
    }
    function loadKbStats() {
      return Console.api.get("/admin/kb/stats").then(function(r) {
        KB_STATS = r || { bodies: [] };
      }).catch(function() {
        KB_STATS = { bodies: [], stale: true, failed: true };
      });
    }
    function kbStatOf(bodyId) {
      var bodies = KB_STATS && KB_STATS.bodies || [];
      for (var i = 0; i < bodies.length; i++) {
        if (bodies[i].id === bodyId) {
          return bodies[i];
        }
      }
      return null;
    }
    function kbRetrievableOf(domain) {
      var m = KB_STATS && KB_STATS.domains || {};
      return m[domain] || null;
    }
    function graceText(ms) {
      if (!ms) {
        return "\u7ACB\u5373";
      }
      if (ms >= 864e5) {
        return ms / 864e5 + " \u5929";
      }
      if (ms >= 36e5) {
        return ms / 36e5 + " \u5C0F\u65F6";
      }
      return Math.round(ms / 6e4) + " \u5206\u949F";
    }
    function leftText(ms) {
      if (ms == null) {
        return "\u2014";
      }
      if (ms <= 0) {
        return "\u5DF2\u5230\u671F\uFF0C\u968F\u65F6\u6E05\u9664";
      }
      if (ms >= 36e5) {
        return Math.floor(ms / 36e5) + " \u5C0F\u65F6 " + Math.round(ms % 36e5 / 6e4) + " \u5206";
      }
      if (ms >= 6e4) {
        return Math.round(ms / 6e4) + " \u5206\u949F";
      }
      return Math.round(ms / 1e3) + " \u79D2";
    }
    function remainingOf(d) {
      if (d.deletedAt == null || !GRACE_MS) {
        return null;
      }
      return d.deletedAt + GRACE_MS - Date.now();
    }
    function pendingDeleteBox(d) {
      var left = remainingOf(d);
      return '<div class="dp-pending-box"><span class="tag destructive">\u5F85\u5220\u9664</span><span>\u5DF2\u6807\u8BB0\u5220\u9664' + (GRACE_MS ? "\uFF0C" + esc(graceText(GRACE_MS)) + "\u540E\u5F7B\u5E95\u6E05\u9664" : "") + (left == null ? "" : "\uFF08\u5269\u4F59 " + esc(leftText(left)) + "\uFF09") + '\u3002\u671F\u95F4\u5B83\u4ECD\u662F\u5B8C\u6574\u7684\u57DF\uFF1A\u77E5\u8BC6\u5E93 / \u63D0\u793A\u8BCD / \u6A21\u578B\u7ED1\u5B9A\u90FD\u8FD8\u5728\uFF0C\u53EA\u662F<b>\u4E0D\u518D\u63A5\u53D7\u65B0\u4F1A\u8BDD</b>\uFF0C\u8FDB\u884C\u4E2D\u7684\u4F1A\u8BDD\u4E0E\u7B49\u5F85\u4EBA\u5DE5\u786E\u8BA4\u7684\u65AD\u70B9\u7167\u5E38\u6536\u5C3E\u3002</span><span class="spacer"></span><button class="btn btn-sm btn-outline-primary" data-restore="' + esc(d.name) + '">\u64A4\u9500\u5220\u9664</button><button class="btn btn-sm btn-outline-danger" data-purge="' + esc(d.name) + '">\u7ACB\u5373\u5F7B\u5E95\u5220\u9664</button></div>';
    }
    function loadBindings() {
      return Console.api.get("/admin/model-profiles").then(function(r) {
        PROFILES = r && r.profiles || [];
        BINDINGS = r && r.domainBindings || {};
        return Promise.all([
          Console.api.get("/admin/mcp-bindings").then(function(m) {
            MCP_BINDINGS = m && m.domainBindings || {};
          }).catch(function() {
            MCP_BINDINGS = {};
          }),
          Console.api.get("/admin/mcp-connections").then(function(m) {
            MCP_CONNS = m && m.connections || [];
          }).catch(function() {
            MCP_CONNS = [];
          })
        ]);
      }).catch(function(e) {
        Console.toast("\u6A21\u578B\u6863\u6848\u52A0\u8F7D\u5931\u8D25\uFF1A" + esc(e.message), { type: "error" });
      });
    }
    function load() {
      if (!DATA) {
        var treeBox = document.getElementById("treeList");
        if (treeBox) {
          treeBox.innerHTML = Console.loadingHtml("\u6B63\u5728\u52A0\u8F7D\u57DF\u7A7A\u95F4\u2026");
        }
        var countEl = document.getElementById("domainCount");
        if (countEl) {
          countEl.textContent = "\u52A0\u8F7D\u4E2D\u2026";
        }
        var detailBox = document.getElementById("detailBody");
        if (detailBox) {
          detailBox.innerHTML = Console.loadingHtml("\u6B63\u5728\u52A0\u8F7D\u57DF\u8BE6\u60C5\u2026");
        }
      }
      return Console.api.get("/admin/domains").then(function(r) {
        DATA = r || {};
        var domains = DATA.domains || [];
        KNOWLEDGE_BODIES = DATA.knowledgeBodies || [];
        if (!SELECTED || !domains.some(function(d) {
          return d.name === SELECTED;
        })) {
          SELECTED = domains.length ? domains[0].name : "";
        }
        if (SELECTED) {
          expandAncestors(SELECTED);
        }
        return Promise.all([loadBindings(), loadGrace()]).then(function() {
          renderTree();
          renderDetail();
          // 知识库统计是异步补数：先渲染树与详情，数字到了再重绘一次。
          // 不能放进上面的 Promise.all —— ES 不可达时这个请求要烧一个 connectTimeout，
          // 整页会一起卡在"正在加载域空间…"上。
          return loadKbStats().then(function() {
            renderTree();
            renderDetail();
          });
        });
      }).catch(function(e) {
        var box = document.getElementById("treeList");
        if (box) {
          box.innerHTML = '<div class="dp-domain-empty" style="color:var(--red);">\u8BFB\u53D6\u5931\u8D25\uFF1A' + esc(e.message) + "</div>";
        }
      });
    }
    document.getElementById("createDomainBtn").addEventListener("click", function() {
      Console.busy(this, createDomain);
    });
    document.getElementById("newDomain").addEventListener("keydown", function(e) {
      if (e.key === "Enter") {
        createDomain();
      }
    });
    document.getElementById("refreshBtn").addEventListener("click", load);
    document.getElementById("treeExpandBtn").addEventListener("click", function() {
      (DATA && DATA.domains || []).forEach(function(d) {
        EXPANDED[d.name] = true;
      });
      renderTree();
    });
    document.getElementById("treeCollapseBtn").addEventListener("click", function() {
      EXPANDED = {};
      renderTree();
    });
    document.getElementById("bindModalClose").addEventListener("click", closeBind);
    document.getElementById("bindSaveBtn").addEventListener("click", function() {
      Console.busy(this, function() {
        return saveBind(BIND_PICK);
      }, "\u4FDD\u5B58\u4E2D\u2026");
    });
    document.getElementById("bindClearBtn").addEventListener("click", function() {
      Console.busy(this, function() {
        return saveBind(emptyPick());
      }, "\u4FDD\u5B58\u4E2D\u2026");
    });
    document.getElementById("bindModal").addEventListener("click", function(e) {
      if (e.target === document.getElementById("bindModal")) {
        closeBind();
      }
    });
    document.getElementById("mcpBindModalClose").addEventListener("click", closeMcpBind);
    document.getElementById("mcpBindSaveBtn").addEventListener("click", function() {
      Console.busy(this, function() {
        return saveMcpBind(MCP_BIND_PICK.slice());
      }, "\u4FDD\u5B58\u4E2D\u2026");
    });
    document.getElementById("mcpBindClearBtn").addEventListener("click", function() {
      Console.busy(this, function() {
        return saveMcpBind([]);
      }, "\u4FDD\u5B58\u4E2D\u2026");
    });
    document.getElementById("mcpBindModal").addEventListener("click", function(e) {
      if (e.target === document.getElementById("mcpBindModal")) {
        closeMcpBind();
      }
    });
    document.addEventListener("keydown", function(e) {
      if (e.key !== "Escape") {
        return;
      }
      if (!document.getElementById("bindModal").hidden) {
        closeBind();
      }
      if (!document.getElementById("mcpBindModal").hidden) {
        closeMcpBind();
      }
    });
    Console.renderSidebar("domains");
    Console.ready(load);
  })();
})();

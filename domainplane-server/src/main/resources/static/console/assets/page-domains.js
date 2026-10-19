(() => {
  // domainplane-server/frontend/src/pages/domains.js
  (function() {
    var esc = Console.escapeHtml;
    var DATA = null;
    var PROFILES = [];
    var BINDINGS = {};
    var MCP_BINDINGS = {};
    var MCP_CONNS = [];
    var BIND_DOMAIN = "";
    var BIND_PICK = emptyPick();
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
          /* 能不能看图：input 含 image（后端 ModelProfile.MOD_IMAGE）。这一条在绑定弹窗里
             必须显式给出，它决定"用户发图时图进谁的消息"，而绑错了要等用户真发一张图
             才报 91001 才发现，那时已经绑完保存过了。 */
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
    function resolveBinding(domain) {
      return resolveAlong(domain, "chat");
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
    function render() {
      var box = document.getElementById("domainList");
      var domains = DATA && DATA.domains || [];
      document.getElementById("domainCount").textContent = domains.length + " \u4E2A\u57DF";
      if (!domains.length) {
        box.innerHTML = '<div class="dp-card"><span class="perm-none">\u5F53\u524D\u6CA1\u6709\u4EFB\u4F55\u57DF\u3002\u6839\u57DF <span class="mono">default</span> \u662F\u6574\u68F5\u57DF\u6811\u7684\u8D77\u70B9\uFF0C\u5176\u4F59\u57DF\u7531\u4E0A\u65B9\u4EBA\u5DE5\u521B\u5EFA\u6216\u5DE5\u5177\u58F0\u660E\u6D3E\u751F\uFF0C\u6807\u8BC6\u987B\u4E3A\u4ECE\u6839\u57DF\u51FA\u53D1\u7684\u5B8C\u6574\u8DEF\u5F84\u3002</span></div>';
        return;
      }
      box.innerHTML = domains.map(function(d) {
        var resolved = resolveBinding(d.name);
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
        var mcpConns = MCP_BINDINGS[d.name] || [];
        var mcpLine = mcpConns.length ? '<div style="margin-top:6px;"><span class="k" style="color:var(--muted);font-size:12px;">MCP \u8FDE\u63A5\uFF1A</span> ' + mcpConns.map(function(c) {
          return '<span class="tag public mono">' + esc(c) + "</span>";
        }).join(" ") + "</div>" : "";
        return '<section class="dp-card model-card"><header class="card-head">' + DOM_ICON + '<h2 class="mono">' + esc(d.name) + "</h2>" + sourceTag(d) + (d.callable ? '<span class="tag read" title="\u53EF\u8C03\u7528\u5355\u5143\uFF1A\u80FD\u4F5C\u4E3A\u5165\u53E3\u88AB\u8C03\u7528">\u53EF\u8C03\u7528</span>' : '<span class="tag approval" title="\u88C5\u914D\u8282\u70B9\uFF1A\u53EA\u628A\u5DE5\u5177 / \u63D0\u793A\u8BCD / \u6A21\u578B / \u77E5\u8BC6\u4F20\u7ED9\u540E\u4EE3\uFF0C\u4E0D\u80FD\u76F4\u63A5\u5F53\u5165\u53E3">\u88C5\u914D\u8282\u70B9</span>') + '<span class="spacer"></span>' + (d.callable ? echoSwitch(d) : "") + '<span class="card-note">\u5DE5\u5177 ' + num(d.toolCount) + " \xB7 \u9700\u786E\u8BA4 " + num(d.approvalCount) + "</span>" + (d.hasChildren ? "" : '<button class="ghost btn btn-sm btn-outline-primary" data-callable="' + esc(d.name) + '" data-to="' + (d.callable ? "false" : "true") + '">' + (d.callable ? "\u8BBE\u4E3A\u88C5\u914D\u8282\u70B9" : "\u8BBE\u4E3A\u53EF\u8C03\u7528") + "</button>") + '<button class="ghost btn btn-sm btn-outline-primary" data-cfg="' + esc(d.name) + '">\u914D\u7F6E\u6A21\u578B</button><button class="ghost btn btn-sm btn-outline-primary" data-mcpcfg="' + esc(d.name) + '">\u914D\u7F6E MCP</button>' + (d.deletable ? '<button class="ghost btn btn-sm btn-outline-primary" data-del="' + esc(d.name) + '">\u5220\u9664</button>' : "") + "</header>" + (d.hasChildren ? '<div class="card-note" style="margin-top:6px;">\u8BE5\u57DF\u6709\u5B50\u57DF\uFF0C\u6309"\u53EA\u6709\u53F6\u5B50\u57DF\u53EF\u4F5C\u4E3A\u53EF\u8C03\u7528\u5355\u5143"\u7684\u89C4\u5219\uFF0C\u5B83\u53EA\u80FD\u4F5C\u88C5\u914D\u8282\u70B9\uFF08\u628A\u5DE5\u5177 / \u63D0\u793A\u8BCD / \u6A21\u578B / \u77E5\u8BC6\u4F20\u7ED9\u540E\u4EE3\uFF09\uFF0C\u4E0D\u80FD\u5F53\u5165\u53E3\u3002\u9700\u8981\u8FD9\u4E00\u5C42\u5165\u53E3\u8BF7\u53E6\u5EFA\u65E0\u5B50\u57DF\u7684\u57DF\u3002</div>' : "") + '<div class="model-meta"><div><span class="k">\u53EF\u8C03\u7528\u6A21\u578B</span>' + boundTags + "</div>" + capabilityLineOf(d.name) + '<div><span class="k">\u63D0\u793A\u8BCD</span>' + (d.hasPrompt ? '<span class="tag read">' + num(d.promptLength) + " \u5B57</span>" : '<span class="tag write">\u7F3A\u63D0\u793A\u8BCD</span>') + '</div><div><span class="k">\u5DE5\u5177</span></div></div><div style="margin-top:8px;">' + toolLine + mcpLine + "</div></section>";
      }).join("");
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
          toggleCallable(
            b.getAttribute("data-callable"),
            b.getAttribute("data-to") === "true"
          );
        });
      });
      box.querySelectorAll("button[data-del]").forEach(function(b) {
        b.addEventListener("click", function() {
          deleteDomain(b.getAttribute("data-del"));
        });
      });
      box.querySelectorAll("input[data-echo]").forEach(function(input) {
        input.addEventListener("change", function() {
          saveEcho(input);
        });
      });
    }
    function saveEcho(input) {
      var domain = input.getAttribute("data-echo");
      var on = input.checked;
      input.disabled = true;
      Console.jsonRequest(
        "/admin/domains/" + encodeURIComponent(domain) + "/tool-call-echo",
        "PUT",
        { echo: on }
      ).then(function(r) {
        if (r && r.code && r.code !== 0) {
          throw new Error(Console.sanitizeError(r.detail || r.error) || "\u4FDD\u5B58\u5931\u8D25");
        }
      }).catch(function(e) {
        input.checked = !on;
        alert("\u4FDD\u5B58\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message));
      }).then(function() {
        input.disabled = false;
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
      Console.jsonRequest(
        "/admin/model-bindings/" + encodeURIComponent(BIND_DOMAIN),
        "PUT",
        { aliases: pick.chat || [], capabilities }
      ).then(function(r) {
        if (r && r.code && r.code !== 0) {
          throw new Error(Console.sanitizeError(r.detail || r.error) || "\u8BBE\u7F6E\u5931\u8D25");
        }
        closeBind();
        loadBindings();
      }).catch(function(e) {
        alert("\u8BBE\u7F6E\u5931\u8D25\uFF1A" + e.message);
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
      Console.jsonRequest(
        "/admin/mcp-bindings/" + encodeURIComponent(MCP_BIND_DOMAIN),
        "PUT",
        { connections }
      ).then(function(r) {
        if (r && r.code && r.code !== 0) {
          throw new Error(Console.sanitizeError(r.detail || r.error) || "\u8BBE\u7F6E\u5931\u8D25");
        }
        closeMcpBind();
        loadBindings();
      }).catch(function(e) {
        alert("\u8BBE\u7F6E\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function createDomain() {
      var input = document.getElementById("newDomain");
      var id = (input.value || "").trim();
      if (!id) {
        alert("\u8BF7\u5148\u586B\u5199\u57DF\u6807\u8BC6");
        input.focus();
        return;
      }
      var callable = document.getElementById("newDomainCallable").checked;
      Console.jsonRequest("/admin/domains", "POST", { id, callable }).then(function(r) {
        if (r && r.code && r.code !== 0) {
          throw new Error(Console.sanitizeError(r.detail || r.error) || "\u521B\u5EFA\u5931\u8D25");
        }
        input.value = "";
        load();
      }).catch(function(e) {
        alert("\u521B\u5EFA\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function toggleCallable(id, callable) {
      var tip = callable ? "\u628A " + id + " \u8BBE\u4E3A\u53EF\u8C03\u7528\u5355\u5143\uFF1F\u4E4B\u540E\u5B83\u53EF\u4EE5\u4F5C\u4E3A\u5165\u53E3\u88AB\u8C03\u7528\u3002" : "\u628A " + id + " \u8BBE\u4E3A\u88C5\u914D\u8282\u70B9\uFF1F\u4E4B\u540E\u5B83\u4E0D\u80FD\u518D\u4F5C\u4E3A\u5165\u53E3\uFF0C\u53EA\u7ED9\u540E\u4EE3\u63D0\u4F9B\u5DE5\u5177 / \u63D0\u793A\u8BCD / \u6A21\u578B / \u77E5\u8BC6\u3002";
      if (!confirm(tip)) {
        return;
      }
      Console.jsonRequest(
        "/admin/domains/" + encodeURIComponent(id) + "/callable",
        "PUT",
        { callable }
      ).then(function(r) {
        if (r && r.code && r.code !== 0) {
          throw new Error(Console.sanitizeError(r.detail || r.error) || "\u5207\u6362\u5931\u8D25");
        }
        load();
      }).catch(function(e) {
        alert("\u5207\u6362\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function deleteDomain(id) {
      var ok = confirm("\u786E\u8BA4\u5220\u9664\u57DF " + id + "\uFF1F\n\n\xB7 \u4F1A\u9012\u5F52\u5220\u9664\u5B83\u7684\u5168\u90E8\u5B50\u57DF\n\xB7 \u7ED1\u5B9A\u8BE5\u57DF\u7684\u8C03\u7528\u65B9\u4F1A\u5728\u542F\u52A8\u6821\u9A8C\u6216\u9996\u6B21\u8C03\u7528\u65F6\u62A5\u300C\u57DF\u4E0D\u5B58\u5728\u300D\n\xB7 \u8BE5\u57DF\u53CA\u5176\u5B50\u57DF\u7684\u77E5\u8BC6\u5E93\u7D22\u5F15\u4E0E\u5207\u7247\u9884\u89C8\u6587\u4EF6\u4F1A\u88AB\u5220\u9664\n\xB7 \u8BE5\u57DF\u53CA\u5176\u5B50\u57DF\u7684\u63D0\u793A\u8BCD\u3001\u6A21\u578B\u7ED1\u5B9A\u3001\u5DE5\u5177\u58F0\u660E\u8BB0\u5F55\u4F1A\u4E00\u5E76\u6E05\u7406");
      if (!ok) {
        return;
      }
      Console.jsonRequest("/admin/domains/" + encodeURIComponent(id), "DELETE").then(function(r) {
        if (r && r.code && r.code !== 0) {
          throw new Error(Console.sanitizeError(r.detail || r.error) || "\u5220\u9664\u5931\u8D25");
        }
        load();
      }).catch(function(e) {
        alert("\u5220\u9664\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function loadBindings() {
      return Console.request("/admin/model-profiles").then(function(r) {
        PROFILES = r && r.profiles || [];
        BINDINGS = r && r.domainBindings || {};
        return Promise.all([
          Console.request("/admin/mcp-bindings").then(function(m) {
            MCP_BINDINGS = m && m.domainBindings || {};
          }).catch(function() {
            MCP_BINDINGS = {};
          }),
          Console.request("/admin/mcp-connections").then(function(m) {
            MCP_CONNS = m && m.connections || [];
          }).catch(function() {
            MCP_CONNS = [];
          })
        ]);
      }).then(render).catch(function(e) {
        document.getElementById("domainList").innerHTML = '<div class="dp-card" style="color:var(--red);">\u6A21\u578B\u5217\u8868\u52A0\u8F7D\u5931\u8D25\uFF1A' + esc(e.message) + "</div>";
      });
    }
    function load() {
      document.getElementById("domainList").innerHTML = '<div class="dp-card"><span class="perm-none">\u52A0\u8F7D\u4E2D\u2026</span></div>';
      Console.request("/admin/domains").then(function(r) {
        DATA = r || {};
        return loadBindings();
      }).catch(function(e) {
        document.getElementById("domainList").innerHTML = '<div class="dp-card" style="color:var(--red);">\u8BFB\u53D6\u5931\u8D25\uFF1A' + esc(e.message) + "</div>";
      });
    }
    document.getElementById("createDomainBtn").addEventListener("click", createDomain);
    document.getElementById("newDomain").addEventListener("keydown", function(e) {
      if (e.key === "Enter") {
        createDomain();
      }
    });
    document.getElementById("refreshBtn").addEventListener("click", load);
    document.getElementById("bindModalClose").addEventListener("click", closeBind);
    document.getElementById("bindSaveBtn").addEventListener("click", function() {
      saveBind(BIND_PICK);
    });
    document.getElementById("bindClearBtn").addEventListener("click", function() {
      saveBind(emptyPick());
    });
    document.getElementById("bindModal").addEventListener("click", function(e) {
      if (e.target === document.getElementById("bindModal")) {
        closeBind();
      }
    });
    document.getElementById("mcpBindModalClose").addEventListener("click", closeMcpBind);
    document.getElementById("mcpBindSaveBtn").addEventListener("click", function() {
      saveMcpBind(MCP_BIND_PICK.slice());
    });
    document.getElementById("mcpBindClearBtn").addEventListener("click", function() {
      saveMcpBind([]);
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

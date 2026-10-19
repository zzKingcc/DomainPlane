(() => {
  // domainplane-server/frontend/src/pages/models.js
  (function() {
    Console.renderSidebar("models");
    var PROFILES = [];
    var EMBEDDING = { alias: null };
    var VECTOR_PROFILES = [];
    var SETTINGS = null;
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
      embedding: "\u5411\u91CF",
      score: "\u76F8\u5173\u6027\u5206\u6570"
    };
    function pfEl(id) {
      return document.getElementById(id);
    }
    function val(id) {
      var v = pfEl(id).value.trim();
      return v === "" ? null : v;
    }
    function loadSettings() {
      return Console.api.get("/admin/settings").then(function(s) {
        SETTINGS = s;
        Console.setModelsWarn(!s.chatConfigured);
        renderProfiles();
      }).catch(function(e) {
        console.debug("[domainplane] \u8BFB\u53D6\u8BBE\u7F6E\u5931\u8D25\uFF1A", e);
      });
    }
    function loadProfiles() {
      return Console.api.get("/admin/model-profiles").then(function(r) {
        PROFILES = r && r.profiles || [];
        EMBEDDING = { alias: r && r.embeddingAlias || null };
        VECTOR_PROFILES = r && r.embeddingProfiles || [];
        pfEl("settingsFileHint").textContent = r && r.settingsFile || "\u2014";
        renderProfiles();
      }).catch(function(e) {
        pfEl("modelList").innerHTML = '<div class="dp-card" style="color:var(--red);">\u52A0\u8F7D\u5931\u8D25\uFF1A' + Console.escapeHtml(e.message) + "</div>";
      });
    }
    function kv(k, v) {
      return '<div><span class="k">' + k + "</span>" + v + "</div>";
    }
    function monoOrNone(v) {
      return v ? '<span class="mono">' + Console.escapeHtml(v) + "</span>" : '<span class="perm-none">\u672A\u914D\u7F6E</span>';
    }
    function modelTitle(name) {
      return "\u6A21\u578B\uFF1A" + monoOrNone(name);
    }
    function tagList(list, labelMap) {
      return (list || []).map(function(k) {
        return '<span class="tag public">' + Console.escapeHtml(labelMap[k] || k) + "</span>";
      }).join("");
    }
    function cardTags(p) {
      return tagList(p.endpoints, EP_LABEL);
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
    function dupBadge(p) {
      var n = dupNo(p);
      return n > 1 ? '<span class="mbadge mono">#' + n + "</span>" : "";
    }
    function isVisionProfile(p) {
      return (p.input || []).indexOf("image") >= 0;
    }
    function visionBadge(p) {
      return isVisionProfile(p) ? '<span class="mbadge ok">\u53EF\u770B\u56FE</span>' : "";
    }
    function halfIdentified(p) {
      return !!(p.endpoints || []).length && !(p.input || []).length;
    }
    function modalityCell(p, list) {
      var tags = tagList(list, MOD_LABEL);
      if (tags) {
        return tags;
      }
      return halfIdentified(p) ? '<span class="perm-none">\u672A\u8BC6\u522B \xB7 \u70B9\u300C\u63A2\u6D4B\u300D\u8865\u9F50</span>' : '<span class="perm-none">\u2014</span>';
    }
    function findProfile(alias) {
      if (!alias) {
        return null;
      }
      return PROFILES.filter(function(p) {
        return p.alias === alias;
      })[0] || VECTOR_PROFILES.filter(function(p) {
        return p.alias === alias;
      })[0] || null;
    }
    function isEmbeddingProfile(p) {
      return (p.endpoints || []).indexOf("embedding") >= 0;
    }
    function isChatProfile(p) {
      return p.bindable === true;
    }
    function thinkSwitch(p) {
      return '<label class="think-switch"><span class="tune-switch"><input type="checkbox" data-think="' + Console.escapeHtml(p.alias) + '"' + (p.returnThinking === true ? " checked" : "") + '><span class="tune-switch-track"><span class="tune-switch-knob"></span></span></span><span class="think-switch-label">\u662F\u5426\u56DE\u5199\u601D\u8003\u5185\u5BB9</span></label>';
    }
    function ctxCell(p) {
      var v = p.contextWindow == null ? "" : String(p.contextWindow);
      return '<input type="number" class="mono model-ctx form-control" data-ctx="' + Console.escapeHtml(p.alias) + '" min="1" step="1" value="' + Console.escapeHtml(v) + '" placeholder="\u672A\u58F0\u660E"><span class="ctx-unit">token</span>';
    }
    function embeddingEmptyBox() {
      return '<div class="empty-box"><div class="empty-title">\u5C1A\u672A\u914D\u7F6E\u5411\u91CF\u6A21\u578B</div><div class="empty-sub">\u77E5\u8BC6\u5E93\u7684\u6587\u6863\u5411\u91CF\u5316\u4E0E\u5411\u91CF\u68C0\u7D22\u90FD\u4F9D\u8D56\u5B83\uFF1B<br>BM25 \u5173\u952E\u8BCD\u68C0\u7D22\u4E0D\u4F9D\u8D56\u5411\u91CF\u6A21\u578B\uFF0C\u672A\u914D\u7F6E\u65F6\u90A3\u6761\u901A\u9053\u4ECD\u53EF\u7528\u3002</div></div>';
    }
    function vectorCard(p, active, prefix) {
      var actions = (active ? "" : '<button class="ghost btn btn-sm btn-outline-primary" data-enable="' + Console.escapeHtml(p.alias) + '"' + (p.usable ? "" : " disabled") + ">\u542F\u7528</button>") + '<button class="ghost btn btn-sm btn-outline-primary" data-probe="' + Console.escapeHtml(p.alias) + '">\u63A2\u6D4B</button><button class="ghost btn btn-sm btn-outline-primary" data-del="' + Console.escapeHtml(p.alias) + '">\u5220\u9664</button>';
      var adapterBadge = p.adapter ? '<span class="mbadge">\u5DF2\u9002\u914D</span>' : "";
      var hasTune = !!(p.adapter && (p.adapter.params || []).length);
      var tuneBox = hasTune ? adapterTuneBox(p, prefix) : "";
      var tuneCtl = hasTune ? '<span class="tune-status"></span><button type="button" class="tune-save btn btn-sm btn-primary" disabled>\u66F4\u65B0</button>' : "";
      return '<section class="dp-card model-card emb"><header class="card-head"><span class="model-dot"></span><h2>' + modelTitle(p.modelName || p.alias) + "</h2>" + cardTags(p) + (active ? '<span class="mbadge ok">\u5DF2\u542F\u7528</span>' : "") + (p.usable ? "" : '<span class="mbadge off">\u4E0D\u53EF\u7528</span>') + adapterBadge + '<span class="spacer"></span>' + tuneCtl + actions + '</header><div class="dp-card-body"><div class="model-meta">' + kv("\u5411\u91CF\u7EF4\u5EA6", p.dimensions == null ? '<span class="perm-none">\u6309\u6A21\u578B\u9ED8\u8BA4</span>' : p.dimensions + "") + kv("\u670D\u52A1\u5546\u5730\u5740", '<span class="mono">' + Console.escapeHtml(p.baseUrl || "-") + "</span>") + (p.apiKeyMasked ? kv("API Key", '<span class="mono">' + Console.escapeHtml(p.apiKeyMasked) + "</span>") : "") + "</div>" + tuneBox + "</div></section>";
    }
    function renderEmbedding() {
      var box = pfEl("embeddingList");
      if (!VECTOR_PROFILES.length) {
        box.innerHTML = embeddingEmptyBox();
        return;
      }
      var tuneSeq = 0;
      box.innerHTML = VECTOR_PROFILES.map(function(p) {
        return vectorCard(p, p.alias === EMBEDDING.alias, "eb" + tuneSeq++);
      }).join("");
      box.querySelectorAll("button[data-enable]").forEach(function(b) {
        b.addEventListener("click", function() {
          applyEmbedding(b.getAttribute("data-enable"));
        });
      });
      box.querySelectorAll("button[data-del]").forEach(function(b) {
        b.addEventListener("click", function() {
          deleteProfile(b.getAttribute("data-del"));
        });
      });
      box.querySelectorAll("button[data-probe]").forEach(function(b) {
        b.addEventListener("click", function() {
          probeProfile(b.getAttribute("data-probe"), b);
        });
      });
      box.querySelectorAll(".dp-card.model-card").forEach(bindTuneBox);
    }
    function askRebuildOrPurge(message) {
      var rebuild = window.confirm(message + "\n\n\u73B0\u5728\u7528\u65B0\u7684\u5411\u91CF\u628A\u77E5\u8BC6\u5E93\u91CD\u65B0\u7B97\u4E00\u904D\u5417\uFF1F\u6587\u6863\u90FD\u8FD8\u5728\uFF0C\u4E0D\u7528\u91CD\u65B0\u4E0A\u4F20\u3002");
      if (rebuild) {
        return "rebuild";
      }
      var purge = window.confirm(
        "\u6E05\u7A7A\u77E5\u8BC6\u5E93\uFF1F\n\n\u91CC\u9762\u7684\u6587\u6863\u548C\u5411\u91CF\u4F1A\u88AB\u5168\u90E8\u5220\u9664\uFF0C\u4EE5\u540E\u8981\u91CD\u65B0\u4E0A\u4F20\u3002"
      );
      return purge ? "purge" : null;
    }
    function applyEmbedding(alias) {
      Console.api.put("/admin/embedding-model", { alias }).then(function(r) {
        if (r && r.requiresConfirmation) {
          var choice = askRebuildOrPurge(r.message || "\u77E5\u8BC6\u5E93\u5DF2\u6709\u5185\u5BB9\u3002");
          if (!choice) {
            loadProfiles();
            return;
          }
          return Console.api.put(
            "/admin/embedding-model",
            { alias, mode: choice }
          );
        }
        return r;
      }).then(function(r) {
        if (!r) {
          return;
        }
        alert(r.message || "\u5DF2\u5207\u6362");
        loadProfiles();
        loadSettings();
      }).catch(function(e) {
        alert("\u5207\u6362\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function renderProfiles() {
      var box = pfEl("modelList");
      renderEmbedding();
      var models = PROFILES.filter(function(p) {
        return p.alias !== EMBEDDING.alias;
      });
      if (!models.length) {
        box.innerHTML = '<div class="empty-box"><div class="empty-title">\u8FD8\u6CA1\u6709\u914D\u7F6E\u4EFB\u4F55\u6A21\u578B</div><div class="empty-sub">\u70B9\u53F3\u4E0A\u89D2\u300C\u6DFB\u52A0\u6A21\u578B\u300D\u767B\u8BB0\u4E00\u4E2A\u8FDE\u63A5\uFF1B\u6A21\u578B\u8981\u7ED1\u5230\u57DF\u624D\u80FD\u88AB\u8C03\u7528\uFF08\u5728\u300C\u57DF\u7A7A\u95F4\u300D\u9875\u64CD\u4F5C\uFF09\u3002</div></div>';
      } else {
        var tuneSeq = 0;
        box.innerHTML = models.map(function(p) {
          var chat = isChatProfile(p);
          var noType = !(p.endpoints || []).length ? '<span class="mbadge off">\u672A\u58F0\u660E\u7C7B\u578B</span>' : "";
          var used;
          if ((p.usedByDomains || []).length) {
            used = p.usedByDomains.map(function(d) {
              return '<span class="tag perm">' + Console.escapeHtml(d) + "</span>";
            }).join("");
          } else if ((p.bindableFamilies || []).length) {
            used = '<span class="perm-none">\u672A\u7ED1\u5B9A\u4EFB\u4F55\u57DF</span>';
          } else {
            used = '<span class="perm-none">\u4E0D\u53C2\u4E0E\u57DF\u7ED1\u5B9A</span>';
          }
          var adapterBadge = p.adapter ? '<span class="mbadge">\u5DF2\u9002\u914D</span>' : "";
          var hasTune = !!(p.adapter && (p.adapter.params || []).length);
          var tuneBox = hasTune ? adapterTuneBox(p, "tb" + tuneSeq++) : "";
          var tuneCtl = hasTune ? '<span class="tune-status"></span><button type="button" class="tune-save btn btn-sm btn-primary" disabled>\u66F4\u65B0</button>' : "";
          var thinkCtl = chat ? thinkSwitch(p) : "";
          return '<section class="dp-card model-card"><header class="card-head"><span class="model-dot"></span><h2>' + modelTitle(p.modelName || p.alias) + "</h2>" + dupBadge(p) + noType + visionBadge(p) + cardTags(p) + adapterBadge + '<span class="spacer"></span>' + thinkCtl + tuneCtl + '<button class="ghost btn btn-sm btn-outline-primary" data-probe="' + Console.escapeHtml(p.alias) + '">\u63A2\u6D4B</button><button class="ghost btn btn-sm btn-outline-primary" data-del="' + Console.escapeHtml(p.alias) + '">\u5220\u9664</button></header><div class="dp-card-body"><div class="model-meta"><div><span class="k">\u8F93\u5165\u6A21\u6001</span>' + modalityCell(p, p.input) + '</div><div><span class="k">\u8F93\u51FA\u6A21\u6001</span>' + modalityCell(p, p.output) + '</div><div><span class="k">\u670D\u52A1\u5546\u5730\u5740</span><span class="mono">' + Console.escapeHtml(p.baseUrl || "-") + '</span></div><div><span class="k">\u88AB\u54EA\u4E9B\u57DF\u4F7F\u7528</span>' + used + "</div>" + (chat ? '<div><span class="k">\u4E0A\u4E0B\u6587\u957F\u5EA6</span>' + ctxCell(p) + "</div>" : "") + "</div>" + tuneBox + "</div></section>";
        }).join("");
      }
      box.querySelectorAll("button[data-del]").forEach(function(b) {
        b.addEventListener("click", function() {
          deleteProfile(b.getAttribute("data-del"));
        });
      });
      box.querySelectorAll("button[data-probe]").forEach(function(b) {
        b.addEventListener("click", function() {
          probeProfile(b.getAttribute("data-probe"), b);
        });
      });
      box.querySelectorAll("input[data-think]").forEach(function(input) {
        input.addEventListener("change", function() {
          saveReturnThinking(input.getAttribute("data-think"), input.checked, input);
        });
      });
      box.querySelectorAll("input[data-ctx]").forEach(function(input) {
        input.setAttribute("data-saved", input.value || "");
        input.addEventListener("change", function() {
          saveContextWindow(input.getAttribute("data-ctx"), input);
        });
      });
      box.querySelectorAll(".dp-card.model-card").forEach(bindTuneBox);
      pfEl("embeddingCount").textContent = VECTOR_PROFILES.length + " \u4E2A";
      pfEl("modelCount").textContent = models.length + " \u4E2A";
    }
    function openModal(title) {
      pfEl("profileModalTitle").textContent = title;
      combo.items = [];
      comboClose();
      updateSaveEnabled();
      pfEl("profileModal").hidden = false;
      document.body.style.overflow = "hidden";
    }
    function closeModal() {
      comboClose();
      pfEl("profileModal").hidden = true;
      if (pfEl("connModal").hidden) {
        document.body.style.overflow = "";
      }
    }
    var PRESETS = null;
    function loadPresets() {
      if (PRESETS !== null) {
        return Promise.resolve(PRESETS);
      }
      return Console.api.get("/admin/provider-presets").then(function(r) {
        PRESETS = r && r.presets || [];
        return PRESETS;
      });
    }
    function psMenu() {
      return pfEl("pfPresetMenu");
    }
    function psMenuOpen() {
      psMenu().style.display = "block";
      pfEl("pfPreset").setAttribute("aria-expanded", "true");
    }
    function psMenuClose() {
      psMenu().style.display = "none";
      pfEl("pfPreset").setAttribute("aria-expanded", "false");
    }
    function psMenuState(text) {
      psMenu().innerHTML = '<div class="combo-state">' + Console.escapeHtml(text) + "</div>";
    }
    function renderPsMenu() {
      var html = "";
      (PRESETS || []).forEach(function(p, i) {
        if (i === 0) {
          html += '<div class="combo-group">\u5185\u7F6E\u914D\u7F6E\u9879</div>';
        }
        var tip = p.note ? p.note : p.baseUrl;
        html += '<div class="combo-item" role="option" data-ps="' + i + '" title="' + Console.escapeHtml(tip) + '">' + Console.escapeHtml(p.label) + "</div>";
      });
      (CONNECTIONS || []).forEach(function(c, i) {
        if (i === 0) {
          html += '<div class="combo-group">\u81EA\u5B9A\u4E49\u914D\u7F6E\u9879</div>';
        }
        var tip = c.baseUrl;
        html += '<div class="combo-item" role="option" data-psc="' + Console.escapeHtml(c.name) + '" title="' + Console.escapeHtml(tip) + '">' + Console.escapeHtml(c.name) + "</div>";
      });
      if (!html) {
        psMenuState("\u6CA1\u6709\u53EF\u7528\u914D\u7F6E");
        return;
      }
      psMenu().innerHTML = html;
    }
    function openPsMenu() {
      psMenuOpen();
      renderPsMenu();
      loadPresets().then(function() {
        renderPsMenu();
        psMenuOpen();
      }).catch(function(e) {
        psMenuState("\u8BFB\u53D6\u9884\u8BBE\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message));
        psMenuOpen();
      });
      loadConnections(false).catch(function() {
        return null;
      }).then(function() {
        renderPsMenu();
      });
    }
    function applyConnection(name) {
      Console.api.post("/admin/provider-connections/resolve?name=" + encodeURIComponent(name), null).then(function(r) {
        if (!r || !r.baseUrl) {
          throw new Error(Console.sanitizeError(r && (r.detail || r.error) || "\u53D6\u914D\u7F6E\u5931\u8D25"));
        }
        pfEl("pfBaseUrl").value = r.baseUrl;
        scheduleMatch();
        var msg = "\u5DF2\u9009\u4E2D\u914D\u7F6E\u300C" + name + "\u300D\uFF0C\u5730\u5740\u5DF2\u586B\u5165\uFF08" + r.baseUrl + "\uFF09";
        if (r.hasApiKey && r.apiKey) {
          pfEl("pfApiKey").value = r.apiKey;
          msg += "\uFF0C\u5E76\u5E26\u5165\u4E86\u5BC6\u94A5";
        } else {
          msg += "\uFF1B\u8BE5\u6761\u672A\u914D\u5BC6\u94A5\uFF0CAPI Key \u4FDD\u6301\u539F\u6837";
        }
        readSay(true, msg);
      }).catch(function(e) {
        readSay(false, Console.sanitizeError(e.message));
        loadConnections(true);
      });
    }
    function applyPreset(idx) {
      var p = PRESETS[idx];
      if (!p) {
        return;
      }
      if (p.baseUrl) {
        pfEl("pfBaseUrl").value = p.baseUrl;
        scheduleMatch();
      }
      var msg = "\u5DF2\u9009\u4E2D\u9884\u8BBE\u300C" + p.label + "\u300D";
      if (p.baseUrl) {
        msg += "\uFF0C\u670D\u52A1\u5546\u5730\u5740\u5DF2\u586B\u5165\uFF08" + p.baseUrl + "\uFF09";
      } else {
        msg += "\uFF0C\u8BF7\u586B\u5199\u670D\u52A1\u5546\u5730\u5740";
      }
      if (p.note) {
        msg += "\u3002" + p.note;
      }
      if (p.needApiKey) {
        msg += "\n\u8BF7\u586B\u5199 API Key\uFF1A\u9884\u8BBE\u4E0D\u4EE3\u586B\u5BC6\u94A5\u3002";
      } else {
        msg += "\n\u8BE5\u9884\u8BBE\u65E0\u9700\u5BC6\u94A5\uFF0C\u53EF\u7559\u7A7A\u3002";
      }
      readSay(true, msg);
    }
    function resetForm() {
      ["pfBaseUrl", "pfApiKey", "pfModelName", "pfPreset"].forEach(function(id) {
        pfEl(id).value = "";
      });
      applyAdapter(null);
      var box = pfEl("profileResult");
      box.textContent = "";
      box.className = "result";
      box.style.color = "";
      var readBox = pfEl("readResult");
      readBox.textContent = "";
      readBox.style.color = "";
    }
    var combo = { items: [], visible: [], loading: false, active: -1 };
    function comboMenu() {
      return pfEl("pfModelMenu");
    }
    function comboOpen() {
      comboMenu().style.display = "block";
      pfEl("pfModelName").setAttribute("aria-expanded", "true");
    }
    function comboClose() {
      comboMenu().style.display = "none";
      pfEl("pfModelName").setAttribute("aria-expanded", "false");
      combo.active = -1;
    }
    function comboState(text) {
      comboMenu().innerHTML = '<div class="combo-state">' + Console.escapeHtml(text) + "</div>";
    }
    function comboRender() {
      var kw = pfEl("pfModelName").value.trim().toLowerCase();
      combo.visible = kw ? combo.items.filter(function(id) {
        return id.toLowerCase().indexOf(kw) >= 0;
      }) : combo.items.slice();
      if (!combo.visible.length) {
        comboState(combo.items.length ? "\u65E0\u5339\u914D\u9879" : "\u6CA1\u6709\u53EF\u9009\u6A21\u578B");
        return;
      }
      comboMenu().innerHTML = combo.visible.map(function(id, i) {
        return '<div class="combo-item' + (i === combo.active ? " active" : "") + '" role="option" data-id="' + Console.escapeHtml(id) + '">' + Console.escapeHtml(id) + "</div>";
      }).join("");
    }
    function comboPick(id) {
      pfEl("pfModelName").value = id;
      comboClose();
      scheduleMatch();
    }
    function catalogPayload() {
      return { chatBaseUrl: val("pfBaseUrl"), chatApiKey: val("pfApiKey") };
    }
    function comboLoad() {
      if (!(pfEl("pfBaseUrl").value || "").trim()) {
        combo.items = [];
        comboState("\u8BF7\u5148\u586B\u5199\u670D\u52A1\u5546\u5730\u5740");
        comboOpen();
        return;
      }
      if (combo.loading) {
        return;
      }
      combo.loading = true;
      comboState("\u52A0\u8F7D\u4E2D\u2026");
      comboOpen();
      Console.api.post("/admin/models?type=chat", catalogPayload()).then(function(data) {
        combo.items = data.models || [];
        comboRender();
        comboOpen();
      }).catch(function(e) {
        combo.items = [];
        comboState("\u62C9\u53D6\u5019\u9009\u5931\u8D25\uFF1A" + e.message);
      }).then(function() {
        combo.loading = false;
      });
    }
    (function() {
      var input = pfEl("pfModelName");
      pfEl("pfModelBtn").addEventListener("click", comboLoad);
      input.addEventListener("focus", function() {
        if (combo.items.length && comboMenu().style.display !== "block") {
          comboRender();
          comboOpen();
        }
      });
      input.addEventListener("input", function() {
        if (comboMenu().style.display === "block" && combo.items.length) {
          comboRender();
        }
      });
      input.addEventListener("keydown", function(ev) {
        if (ev.key === "Escape") {
          if (comboMenu().style.display === "block") {
            ev.stopPropagation();
            comboClose();
          }
          return;
        }
        if (comboMenu().style.display !== "block" || !combo.visible.length) {
          if (ev.key === "Enter") {
            ev.preventDefault();
          }
          return;
        }
        var n = combo.visible.length;
        if (ev.key === "ArrowDown" || ev.key === "ArrowUp") {
          ev.preventDefault();
          combo.active = ev.key === "ArrowDown" ? (combo.active + 1) % n : (combo.active - 1 + n) % n;
          comboRender();
          var act = comboMenu().querySelector(".combo-item.active");
          if (act && act.scrollIntoView) {
            act.scrollIntoView({ block: "nearest" });
          }
        } else if (ev.key === "Enter") {
          ev.preventDefault();
          if (combo.active >= 0 && combo.visible[combo.active]) {
            comboPick(combo.visible[combo.active]);
          } else {
            comboClose();
          }
        }
      });
      comboMenu().addEventListener("click", function(ev) {
        var item = ev.target.closest ? ev.target.closest(".combo-item") : null;
        if (item) {
          comboPick(item.getAttribute("data-id"));
        }
      });
      document.addEventListener("click", function(ev) {
        if (!ev.target.closest || !ev.target.closest("#pfModelCombo")) {
          comboClose();
        }
      });
    })();
    function updateSaveEnabled() {
      pfEl("saveProfileBtn").disabled = false;
    }
    function readSay(ok, text) {
      var el = pfEl("readResult");
      el.textContent = text;
      el.style.color = ok ? "" : "var(--red)";
    }
    function openAdd() {
      resetForm();
      openModal("\u6DFB\u52A0\u6A21\u578B");
      pfEl("pfBaseUrl").focus();
    }
    function deriveAlias(modelName) {
      var base = aliasBase(modelName);
      function taken(a) {
        return a === "default" || findProfile(a) !== null;
      }
      var alias = base, n = 2;
      while (taken(alias)) {
        alias = base + "-" + n;
        n++;
      }
      return alias;
    }
    function showResult(ok, message) {
      var box = pfEl("profileResult");
      box.className = message ? "result show " + (ok ? "ok" : "err") : "result";
      box.textContent = message || "";
    }
    var ADAPTER = null;
    var ADAPTER_SPECS = [];
    var matchTimer = null;
    function scheduleMatch() {
      if (matchTimer) {
        clearTimeout(matchTimer);
      }
      matchTimer = setTimeout(runMatch, 500);
    }
    function runMatch() {
      if (matchTimer) {
        clearTimeout(matchTimer);
        matchTimer = null;
      }
      var baseUrl = pfEl("pfBaseUrl").value.trim();
      var modelName = pfEl("pfModelName").value.trim();
      if (!baseUrl && !modelName) {
        applyAdapter(null);
        return;
      }
      Console.api.post("/admin/model-adapters/match", { baseUrl, modelName }).then(function(r) {
        applyAdapter(r && r.matched ? r.adapter : null);
      }).catch(function() {
        applyAdapter(null);
      });
    }
    function tuneFieldsReady() {
      return !!(val("pfBaseUrl") && val("pfApiKey") && val("pfModelName"));
    }
    function refreshTuneBox() {
      pfEl("adapterBar").hidden = !(ADAPTER && ADAPTER_SPECS.length && tuneFieldsReady());
    }
    function applyAdapter(adapter) {
      ADAPTER = adapter || null;
      if (!ADAPTER) {
        ADAPTER_SPECS = [];
        pfEl("adapterParams").innerHTML = "";
        refreshTuneBox();
        return;
      }
      ADAPTER_SPECS = ADAPTER.params || [];
      var cur = specDefaults(ADAPTER_SPECS);
      pfEl("adapterParams").innerHTML = ADAPTER_SPECS.map(function(spec, i) {
        return renderParamRow("ap", spec, i, cur[spec.key], cur);
      }).join("");
      refreshTuneBox();
    }
    function tuneParamEnabled(spec, values) {
      var w = spec.enabledWhen;
      if (!w) {
        return true;
      }
      var v = values ? values[w.key] : void 0;
      if (v === void 0 || v === null || v === "") {
        v = w.missing === void 0 ? void 0 : w.missing;
      }
      if (v === void 0 || v === null) {
        return false;
      }
      var want = String(v);
      return (w.values || []).some(function(x) {
        return String(x) === want;
      });
    }
    function tuneValuesNow(prefix, specs) {
      var out = {};
      (specs || []).forEach(function(spec, i) {
        var el = pfEl(prefix + "-" + i);
        if (!el) {
          return;
        }
        if (spec.type === "bool") {
          if (el.checked) {
            out[spec.key] = true;
          }
          return;
        }
        var v = (el.value || "").trim();
        if (v !== "") {
          out[spec.key] = v;
        }
      });
      return out;
    }
    function syncTuneEnabled(prefix, specs) {
      var values = tuneValuesNow(prefix, specs);
      (specs || []).forEach(function(spec, i) {
        if (!spec.enabledWhen) {
          return;
        }
        var el = pfEl(prefix + "-" + i);
        if (!el) {
          return;
        }
        var on = tuneParamEnabled(spec, values);
        if (!on) {
          if (spec.type === "bool") {
            el.checked = false;
          } else {
            el.value = "";
          }
        }
        el.disabled = !on;
        var bar = el.closest ? el.closest(".tune-bar") : null;
        if (bar) {
          refreshBar(bar);
        }
      });
    }
    function tuneDefaultText(spec) {
      return spec && spec.default != null ? "\u9ED8\u8BA4 " + spec.default : "\u670D\u52A1\u5546\u9ED8\u8BA4";
    }
    function specDefaults(specs) {
      var out = {};
      (specs || []).forEach(function(spec) {
        if (spec && spec.default != null && spec.default !== "") {
          out[spec.key] = spec.default;
        }
      });
      return out;
    }
    function renderBar(id, spec, value, on) {
      var opts = spec.options || [];
      var cur = value == null ? "" : String(value);
      var active = -1;
      for (var i = 0; i < opts.length; i++) {
        if (cur !== "" && String(opts[i].value) === cur) {
          active = i;
        }
      }
      var segs = opts.map(function(o, i2) {
        var cls = "tune-bar-seg";
        if (on && i2 <= active) {
          cls += " on";
        }
        if (on && i2 === active) {
          cls += " active";
        }
        return '<button type="button" class="' + cls + ' btn btn-primary"' + (on ? "" : " disabled") + ' data-bar-val="' + Console.escapeHtml(o.value) + '" title="' + Console.escapeHtml(o.label || o.value) + '">' + Console.escapeHtml(o.value) + "</button>";
      }).join("");
      var capText = !on ? "" : active >= 0 ? opts[active].label || cur : cur === "" ? tuneDefaultText(spec) : cur;
      var defAttr = spec.default == null ? "" : ' data-bar-def="' + Console.escapeHtml(String(spec.default)) + '"';
      return '<div class="tune-bar"' + defAttr + '><input type="hidden" id="' + id + '" value="' + Console.escapeHtml(cur) + '"' + (on ? "" : " disabled") + '><div class="tune-bar-track">' + segs + '</div><div class="tune-bar-cap"' + (on ? "" : " hidden") + ">" + Console.escapeHtml(capText) + "</div></div>";
    }
    function refreshBar(bar) {
      if (!bar || !bar.querySelector) {
        return;
      }
      var hid = bar.querySelector('input[type="hidden"]');
      if (!hid) {
        return;
      }
      var val2 = hid.value || "";
      var off = !!hid.disabled;
      var segs = Array.prototype.slice.call(bar.querySelectorAll(".tune-bar-seg"));
      var active = -1;
      for (var i = 0; i < segs.length; i++) {
        if (val2 !== "" && segs[i].getAttribute("data-bar-val") === val2) {
          active = i;
        }
      }
      for (var j = 0; j < segs.length; j++) {
        segs[j].disabled = off;
        if (segs[j].classList) {
          segs[j].classList.toggle("on", !off && j <= active);
          segs[j].classList.toggle("active", !off && j === active);
        }
      }
      var cap = bar.querySelector(".tune-bar-cap");
      if (cap) {
        if (off) {
          cap.textContent = "";
          cap.hidden = true;
        } else {
          var def = bar.getAttribute ? bar.getAttribute("data-bar-def") : null;
          cap.hidden = false;
          cap.textContent = active >= 0 ? segs[active].getAttribute("title") || val2 : val2 === "" ? def ? "\u9ED8\u8BA4 " + def : "\u670D\u52A1\u5546\u9ED8\u8BA4" : val2;
        }
      }
    }
    function barPick(bar, val2) {
      if (!bar || !bar.querySelector) {
        return;
      }
      var hid = bar.querySelector('input[type="hidden"]');
      if (!hid || hid.disabled) {
        return;
      }
      hid.value = hid.value === val2 ? "" : val2;
      refreshBar(bar);
      hid.dispatchEvent(new Event("change", { bubbles: true }));
    }
    function renderParamRow(prefix, spec, i, value, values) {
      var id = prefix + "-" + i;
      var on = tuneParamEnabled(spec, values);
      if (!on) {
        value = null;
      }
      var v = value == null ? "" : Array.isArray(value) ? value.join("\n") : String(value);
      var dis = on ? "" : " disabled";
      var ph = Console.escapeHtml(tuneDefaultText(spec));
      var control = "";
      if (spec.type === "bool") {
        control = '<label class="tune-switch"><input type="checkbox" id="' + id + '"' + (value === true || v === "true" ? " checked" : "") + dis + '><span class="tune-switch-track"><span class="tune-switch-knob"></span></span></label>';
      } else if (spec.type === "enum" && spec.display === "bar") {
        control = renderBar(id, spec, value, on);
      } else if (spec.type === "enum") {
        control = '<select class="form-select" id="' + id + '"' + dis + '><option value="">' + ph + "</option>" + (spec.options || []).map(function(o) {
          return '<option value="' + Console.escapeHtml(o.value) + '"' + (v === o.value ? " selected" : "") + ">" + Console.escapeHtml(o.label || o.value) + "</option>";
        }).join("") + "</select>";
      } else if (spec.type === "number" || spec.type === "int") {
        control = '<input type="number" class="mono form-control" id="' + id + '"' + (v === "" ? "" : ' value="' + Console.escapeHtml(v) + '"') + (spec.min != null ? ' min="' + spec.min + '"' : "") + (spec.max != null ? ' max="' + spec.max + '"' : "") + (spec.step != null ? ' step="' + spec.step + '"' : "") + dis + ' placeholder="' + ph + '">';
      } else if (spec.type === "stringList") {
        control = '<textarea class="mono form-control" id="' + id + '" rows="2"' + dis + ' placeholder="' + ph + '">' + (v === "" ? "" : Console.escapeHtml(v)) + "</textarea>";
      } else {
        control = '<input type="text" class="mono form-control" id="' + id + '"' + (v === "" ? "" : ' value="' + Console.escapeHtml(v) + '"') + dis + ' placeholder="' + ph + '">';
      }
      return '<div class="field tune-field' + (spec.display === "bar" ? " tune-field-wide" : "") + '"><label>' + Console.escapeHtml(spec.label) + (spec.wire ? ' <span class="tune-en">' + Console.escapeHtml(spec.wire) + "</span>" : "") + "</label>" + control + "</div>";
    }
    function collectTuning(prefix, specs) {
      var out = { params: {}, temperature: null, maxTokens: null, dimensions: null };
      (specs || []).forEach(function(spec, i) {
        var el = pfEl(prefix + "-" + i);
        if (!el || el.disabled) {
          return;
        }
        if (spec.type === "bool") {
          if (el.checked) {
            out.params[spec.key] = true;
          }
          return;
        }
        if (spec.type === "stringList") {
          var lines = (el.value || "").split("\n").map(function(s) {
            return s.trim();
          }).filter(function(s) {
            return s !== "";
          });
          if (lines.length) {
            out.params[spec.key] = lines;
          }
          return;
        }
        var v = (el.value || "").trim();
        if (v === "") {
          return;
        }
        if (spec.type === "number" || spec.type === "int") {
          var n = Number(v);
          if (isNaN(n)) {
            return;
          }
          if (spec.std) {
            if (spec.wire === "temperature") {
              out.temperature = n;
            } else if (spec.wire === "max_tokens") {
              out.maxTokens = Math.round(n);
            } else if (spec.wire === "dimensions") {
              out.dimensions = Math.round(n);
            }
          } else {
            out.params[spec.key] = n;
          }
        } else if (spec.std) {
        } else {
          out.params[spec.key] = v;
        }
      });
      return out;
    }
    function tuningByKey(prefix, specs) {
      var t = collectTuning(prefix, specs);
      var out = {};
      (specs || []).forEach(function(spec) {
        if (!spec.std) {
          if (t.params[spec.key] != null) {
            out[spec.key] = t.params[spec.key];
          }
          return;
        }
        var v = spec.wire === "max_tokens" ? t.maxTokens : spec.wire === "dimensions" ? t.dimensions : t.temperature;
        if (v != null) {
          out[spec.key] = v;
        }
      });
      return out;
    }
    function itemsByKey(items) {
      var out = {};
      (items || []).forEach(function(it) {
        out[it.key] = it.value;
      });
      return out;
    }
    function tuneSignature(byKey, specs) {
      return (specs || []).map(function(spec) {
        var v = byKey[spec.key];
        if (v == null || v === "") {
          return spec.key + "=";
        }
        if (spec.type === "number" || spec.type === "int") {
          var n = Number(v);
          return spec.key + "=" + (isNaN(n) ? String(v) : String(n));
        }
        if (spec.type === "bool") {
          return spec.key + "=" + (v === true || v === "true" ? "t" : "f");
        }
        if (spec.type === "stringList") {
          return spec.key + "=" + (Object.prototype.toString.call(v) === "[object Array]" ? v.join("") : String(v));
        }
        return spec.key + "=" + String(v);
      }).join("|");
    }
    function adapterTuneBox(p, prefix) {
      var ad = p.adapter;
      var specs = ad.params || [];
      var saved = itemsByKey(ad.items);
      var cur = specDefaults(specs);
      Object.keys(saved).forEach(function(k) {
        cur[k] = saved[k];
      });
      return '<div class="tune-box card-tune" data-tune-alias="' + Console.escapeHtml(p.alias) + '" data-tune-prefix="' + Console.escapeHtml(prefix) + '">' + specs.map(function(spec, i) {
        return renderParamRow(prefix, spec, i, cur[spec.key], cur);
      }).join("") + "</div>";
    }
    function bindTuneBox(card) {
      var box = card.querySelector(".tune-box[data-tune-prefix]");
      if (!box) {
        return;
      }
      var prefix = box.getAttribute("data-tune-prefix");
      var alias = box.getAttribute("data-tune-alias");
      var profile = findProfile(alias);
      var specs = profile && profile.adapter ? profile.adapter.params || [] : [];
      if (!prefix || !specs.length) {
        return;
      }
      var btn = card.querySelector(".tune-save");
      var status = card.querySelector(".tune-status");
      var current = function() {
        return tuneSignature(tuningByKey(prefix, specs), specs);
      };
      var base = current();
      var sync = function() {
        syncTuneEnabled(prefix, specs);
        if (btn) {
          btn.disabled = current() === base;
        }
      };
      ["input", "change"].forEach(function(ev) {
        card.addEventListener(ev, sync);
      });
      if (btn) {
        btn.addEventListener("click", function() {
          btn.disabled = true;
          if (status) {
            status.textContent = "";
            status.className = "tune-status";
          }
          saveTuning(alias, prefix, specs, function(ok, msg) {
            if (status) {
              status.textContent = msg;
              status.className = "tune-status " + (ok ? "ok" : "err");
            }
            if (ok) {
              base = current();
            }
            btn.disabled = ok;
          });
        });
      }
      sync();
    }
    function saveTuning(alias, prefix, specs, onDone) {
      var t = collectTuning(prefix, specs);
      var payload = {
        alias,
        params: t.params,
        temperature: t.temperature,
        maxTokens: t.maxTokens
      };
      var hasDim = (specs || []).some(function(s) {
        return s.wire === "dimensions";
      });
      if (hasDim) {
        payload.dimensions = t.dimensions;
      } else {
        var pf = findProfile(alias);
        if (pf && pf.dimensions != null) {
          payload.dimensions = pf.dimensions;
        }
      }
      Console.api.put("/admin/model-profiles/tuning", payload).then(function(r) {
        if (onDone) {
          onDone(true, r && r.message || "\u5DF2\u4FDD\u5B58");
        }
      }).catch(function(e) {
        if (onDone) {
          onDone(false, Console.sanitizeError(e.message));
        }
      });
    }
    function saveReturnThinking(alias, on, input) {
      input.disabled = true;
      Console.api.put("/admin/model-profiles/return-thinking", { alias, returnThinking: on }).then(function() {
        var p = findProfile(alias);
        if (p) {
          p.returnThinking = on;
        }
      }).catch(function(e) {
        input.checked = !on;
        alert("\u4FDD\u5B58\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message));
      }).then(function() {
        input.disabled = false;
      });
    }
    function saveContextWindow(alias, input) {
      var raw = (input.value || "").trim();
      var prev = input.getAttribute("data-saved") || "";
      var value = null;
      if (raw !== "") {
        value = Number(raw);
        if (!isFinite(value) || value <= 0 || Math.floor(value) !== value) {
          alert("\u4E0A\u4E0B\u6587\u957F\u5EA6\u5FC5\u987B\u662F\u6B63\u6574\u6570\uFF08\u5355\u4F4D token\uFF09\uFF1B\u7559\u7A7A\u8868\u793A\u672A\u58F0\u660E");
          input.value = prev;
          return;
        }
      }
      input.disabled = true;
      Console.api.put("/admin/model-profiles/context-window", { alias, contextWindow: value }).then(function() {
        var p = findProfile(alias);
        if (p) {
          p.contextWindow = value;
        }
        input.setAttribute("data-saved", value == null ? "" : String(value));
        input.value = value == null ? "" : String(value);
      }).catch(function(e) {
        input.value = prev;
        alert("\u4FDD\u5B58\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message));
      }).then(function() {
        input.disabled = false;
      });
    }
    function save() {
      if (!val("pfApiKey")) {
        showResult(false, "\u8BF7\u586B\u5199 API Key");
        return;
      }
      var modelName = pfEl("pfModelName").value.trim();
      if (!modelName) {
        showResult(false, "\u8BF7\u5148\u586B\u5199\u6A21\u578B\u540D");
        return;
      }
      var alias = deriveAlias(modelName);
      var body = {
        alias,
        input: [],
        output: [],
        baseUrl: pfEl("pfBaseUrl").value.trim(),
        apiKey: pfEl("pfApiKey").value,
        modelName,
        /* 新档案的思考回写默认关闭：要开，建成后拨卡片头部那颗开关
           （见 thinkSwitch / saveReturnThinking） */
        returnThinking: false
      };
      var tuning = collectTuning("ap", ADAPTER_SPECS);
      if (Object.keys(tuning.params).length) {
        body.params = tuning.params;
      }
      if (tuning.temperature != null) {
        body.temperature = tuning.temperature;
      }
      if (tuning.maxTokens != null) {
        body.maxTokens = tuning.maxTokens;
      }
      if (tuning.dimensions != null) {
        body.dimensions = tuning.dimensions;
      }
      Console.api.post("/admin/model-profiles", body).then(function() {
        closeModal();
        return loadProfiles();
      }).catch(function(e) {
        showResult(false, "\u4FDD\u5B58\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function deleteProfile(alias) {
      var target = findProfile(alias);
      var isEmb = target && isEmbeddingProfile(target);
      var isCurrentEmbedding = isEmb && EMBEDDING.alias === alias;
      var text = "\u786E\u8BA4\u5220\u9664\u6A21\u578B " + alias + "\uFF1F\n\n";
      if (isCurrentEmbedding) {
        text += "\u26A0\uFE0F \u5B83\u6B63\u662F\u5F53\u524D\u5411\u91CF\u6A21\u578B\uFF0C\u5220\u9664\u540E\u5411\u91CF\u914D\u7F6E\u4F1A\u4E00\u5E76\u53D6\u6D88\uFF0C\u77E5\u8BC6\u5E93\u7684\u704C\u5E93\u4E0E\u5411\u91CF\u68C0\u7D22\u5C06\u505C\u7528\uFF08\u5DF2\u704C\u5E93\u7684\u5411\u91CF\u4ECD\u5728\u7D22\u5F15\u91CC\uFF0C\u9700\u6362\u6A21\u578B\u540E\u91CD\u704C\uFF09\u3002\n\n";
      } else if (isEmb) {
        text += "\u5B83\u662F\u4E00\u4E2A\u5411\u91CF\u6863\u6848\uFF0C\u5220\u9664\u540E\u9700\u8981\u91CD\u65B0\u9009\u7528\u5411\u91CF\u6A21\u578B\u3002\n\n";
      } else {
        text += "\u8BE5\u6A21\u578B\u4F1A\u4ECE\u6240\u6709\u57DF\u7684\u7ED1\u5B9A\u91CC\u4E00\u5E76\u79FB\u9664\uFF08\u7EA7\u8054\u6E05\u7406\uFF09\uFF0C\u8FD9\u4E9B\u57DF\u5C06\u7ACB\u5373\u53D8\u4E3A\u300C\u65E0\u53EF\u8C03\u7528\u6A21\u578B\u300D\u3002\n\n";
      }
      if (!confirm(text)) {
        return;
      }
      Console.api.del("/admin/model-profiles?alias=" + encodeURIComponent(alias)).then(function(r) {
        if (r && r.message) {
          alert(r.message);
        }
        loadProfiles();
      }).catch(function(e) {
        alert("\u5220\u9664\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function probeProfile(alias, btn) {
      var ok = confirm("\u63A2\u6D4B\u300C" + alias + "\u300D\uFF1F\n\n\u4F1A\u53D1\u4E00\u7EC4\u6700\u5C0F\u8BF7\u6C42\u5B9E\u6D4B\u5B83\u7684\u7AEF\u70B9\u65CF\u4E0E\u6A21\u6001\uFF0C\u53EF\u80FD\u6D88\u8017\u5C11\u91CF\u989D\u5EA6\uFF1A\n\xB7 \u5BF9\u8BDD\uFF1Amax_tokens=1\uFF08\u7EA6 1 \u4E2A token\uFF09\n\xB7 \u5411\u91CF / \u91CD\u6392\uFF1A\u63D0\u4EA4\u4E00\u6761\u77ED\u6587\u672C\n\xB7 \u8BED\u97F3\u8BC6\u522B / \u8BED\u97F3\u5408\u6210\uFF1A\u6309\u6781\u77ED\u97F3\u9891\u8BA1\u8D39\n\xB7 \u751F\u56FE\uFF1A\u82E5\u8BE5\u670D\u52A1\u5546\u6709\u751F\u56FE\u7AEF\u70B9\uFF0C\u4F1A\u771F\u5B9E\u751F\u6210\u4E00\u5F20\u9ED8\u8BA4\u5C3A\u5BF8\u7684\u56FE\n\n\u5B9E\u6D4B\u786E\u8BA4\u5230\u7684\u4F1A\u5E76\u56DE\u6863\u6848\uFF08\u53EA\u589E\u4E0D\u51CF\uFF09\u3002\u7EE7\u7EED\uFF1F");
      if (!ok) {
        return;
      }
      if (btn) {
        btn.disabled = true;
      }
      Console.api.post("/admin/model-profiles/detect?alias=" + encodeURIComponent(alias), null).then(function(r) {
        return loadProfiles().then(function() {
          if (r && r.note && r.note.indexOf("\u672A\u786E\u8BA4") >= 0) {
            alert("\u63A2\u6D4B\u5B8C\u6210\uFF0C\u4F46\u6709\u4E9B\u9879\u6CA1\u80FD\u786E\u8BA4\uFF1A\n\n" + r.note);
          }
        });
      }).catch(function(e) {
        if (btn) {
          btn.disabled = false;
        }
        alert("\u63A2\u6D4B\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message));
      });
    }
    var CONNECTIONS = null;
    function loadConnections(force) {
      if (CONNECTIONS !== null && !force) {
        return Promise.resolve(CONNECTIONS);
      }
      return Console.api.get("/admin/provider-connections").then(function(r) {
        CONNECTIONS = r && r.connections || [];
        return CONNECTIONS;
      }).catch(function(e) {
        CONNECTIONS = null;
        throw e;
      });
    }
    function cnSay(ok, text) {
      var el = pfEl("cnResult");
      el.textContent = text;
      el.className = "result" + (text ? ok ? " show ok" : " show err" : "");
    }
    function renderConnectionList() {
      var box = pfEl("cnList");
      if (!CONNECTIONS || !CONNECTIONS.length) {
        box.innerHTML = '<div class="empty-box">\u8FD8\u6CA1\u6709\u81EA\u5B9A\u4E49\u914D\u7F6E\u9879\u3002\u6DFB\u52A0\u540E\uFF0C\u5B83\u4F1A\u51FA\u73B0\u5728\u6A21\u578B\u8868\u5355\u9876\u90E8\u7684\u9884\u8BBE\u4E0B\u62C9\u91CC\uFF08\u300C\u81EA\u5B9A\u4E49\u914D\u7F6E\u9879\u300D\u4E00\u6BB5\uFF09\uFF0C\u9009\u4E2D\u5373\u628A\u5730\u5740\u4E0E\u5BC6\u94A5\u4E00\u8D77\u586B\u5165\u3002</div>';
        return;
      }
      box.innerHTML = CONNECTIONS.map(function(c) {
        var key = c.hasApiKey ? c.maskedApiKey || "****" : "\uFF08\u65E0\u5BC6\u94A5\uFF09";
        return '<div class="dp-card"><div class="card-head"><div><b>' + Console.escapeHtml(c.name) + '</b><div class="mono hint" style="margin:2px 0 0;">' + Console.escapeHtml(c.baseUrl) + '</div></div><span class="spacer"></span><span class="mono hint" style="margin:0;">' + Console.escapeHtml(key) + '</span><button class="ghost btn btn-sm btn-outline-primary" data-cn-del="' + Console.escapeHtml(c.name) + '">\u5220\u9664</button></div></div>';
      }).join("");
    }
    function openConnModal() {
      pfEl("connModal").hidden = false;
      document.body.style.overflow = "hidden";
      cnSay(true, "");
      loadConnections(true).then(function() {
        renderConnectionList();
        pfEl("cnName").focus();
      }).catch(function(e) {
        renderConnectionList();
        cnSay(false, "\u8BFB\u53D6\u81EA\u5B9A\u4E49\u914D\u7F6E\u9879\u5931\u8D25\uFF1A" + e.message);
      });
    }
    function closeConnModal() {
      pfEl("connModal").hidden = true;
      if (pfEl("profileModal").hidden) {
        document.body.style.overflow = "";
      }
    }
    function addConnection() {
      var name = (pfEl("cnName").value || "").trim();
      var baseUrl = (pfEl("cnBaseUrl").value || "").trim();
      var secret = (pfEl("cnApiKey").value || "").trim();
      if (!name) {
        cnSay(false, "\u540D\u79F0\u4E0D\u80FD\u4E3A\u7A7A");
        return;
      }
      if (!baseUrl) {
        cnSay(false, "\u670D\u52A1\u5546\u5730\u5740\u4E0D\u80FD\u4E3A\u7A7A");
        return;
      }
      var btn = pfEl("cnAddBtn");
      btn.disabled = true;
      Console.api.post("/admin/provider-connections", { name, baseUrl, apiKey: secret }).then(function(r) {
        pfEl("cnName").value = "";
        pfEl("cnBaseUrl").value = "";
        pfEl("cnApiKey").value = "";
        pfEl("cnFileHint").textContent = r && r.settingsFile || "\u2014";
        cnSay(true, r && r.message || "\u5DF2\u6DFB\u52A0\u300C" + name + "\u300D");
        return loadConnections(true);
      }).then(function() {
        renderConnectionList();
      }).catch(function(e) {
        cnSay(false, Console.sanitizeError(e.message));
      }).then(function() {
        btn.disabled = false;
      });
    }
    function deleteConnection(name) {
      if (!confirm("\u5220\u9664\u81EA\u5B9A\u4E49\u914D\u7F6E\u300C" + name + "\u300D\uFF1F\n\n\u5DF2\u4FDD\u5B58\u7684\u6A21\u578B\u6863\u6848\u4E0D\u53D7\u5F71\u54CD\uFF1A\u5B83\u4EEC\u5404\u81EA\u4FDD\u5B58\u7684\u662F\u72EC\u7ACB\u526F\u672C\uFF0C\u4E0D\u662F\u5BF9\u8FD9\u4E00\u6761\u7684\u5F15\u7528\u3002")) {
        return;
      }
      Console.api.del("/admin/provider-connections?name=" + encodeURIComponent(name)).then(function(r) {
        cnSay(true, r && r.message || "\u5DF2\u5220\u9664\u300C" + name + "\u300D");
        return loadConnections(true);
      }).then(function() {
        renderConnectionList();
      }).catch(function(e) {
        cnSay(false, Console.sanitizeError(e.message));
      });
    }
    (function() {
      pfEl("pfPresetBtn").addEventListener("click", openPsMenu);
      psMenu().addEventListener("click", function(ev) {
        if (!ev.target.closest) {
          return;
        }
        var builtIn = ev.target.closest("[data-ps]");
        if (builtIn) {
          psMenuClose();
          pfEl("pfPreset").value = "";
          applyPreset(parseInt(builtIn.getAttribute("data-ps"), 10));
          return;
        }
        var custom = ev.target.closest("[data-psc]");
        if (custom) {
          psMenuClose();
          pfEl("pfPreset").value = "";
          applyConnection(custom.getAttribute("data-psc"));
        }
      });
      document.addEventListener("click", function(ev) {
        if (!ev.target.closest || !ev.target.closest("#pfPresetCombo")) {
          psMenuClose();
        }
      });
    })();
    pfEl("openConnBtn").addEventListener("click", openConnModal);
    pfEl("connModalClose").addEventListener("click", closeConnModal);
    pfEl("connModal").addEventListener("click", function(e) {
      if (e.target === pfEl("connModal")) {
        closeConnModal();
      }
    });
    pfEl("cnAddBtn").addEventListener("click", addConnection);
    pfEl("cnClearBtn").addEventListener("click", function() {
      pfEl("cnName").value = "";
      pfEl("cnBaseUrl").value = "";
      pfEl("cnApiKey").value = "";
      cnSay(true, "");
    });
    pfEl("cnList").addEventListener("click", function(e) {
      var btn = e.target.closest ? e.target.closest("[data-cn-del]") : null;
      if (btn) {
        deleteConnection(btn.getAttribute("data-cn-del"));
      }
    });
    pfEl("pfBaseUrl").addEventListener("input", scheduleMatch);
    pfEl("pfModelName").addEventListener("input", scheduleMatch);
    pfEl("pfBaseUrl").addEventListener("blur", runMatch);
    pfEl("pfModelName").addEventListener("blur", runMatch);
    pfEl("pfApiKey").addEventListener("input", refreshTuneBox);
    ["input", "change"].forEach(function(ev) {
      pfEl("adapterBar").addEventListener(ev, function() {
        syncTuneEnabled("ap", ADAPTER_SPECS);
      });
    });
    document.addEventListener("click", function(e) {
      var seg = e.target && e.target.closest ? e.target.closest(".tune-bar-seg") : null;
      if (!seg) {
        return;
      }
      var bar = seg.closest(".tune-bar");
      if (bar) {
        barPick(bar, seg.getAttribute("data-bar-val"));
      }
    });
    pfEl("addModelBtn").addEventListener("click", openAdd);
    pfEl("saveProfileBtn").addEventListener("click", save);
    pfEl("resetProfileFormBtn").addEventListener("click", resetForm);
    pfEl("profileModalClose").addEventListener("click", closeModal);
    pfEl("profileModal").addEventListener("click", function(e) {
      if (e.target === pfEl("profileModal")) {
        closeModal();
      }
    });
    document.addEventListener("keydown", function(e) {
      if (e.key !== "Escape") {
        return;
      }
      if (!pfEl("connModal").hidden) {
        closeConnModal();
        return;
      }
      if (!pfEl("profileModal").hidden) {
        closeModal();
      }
    });
    Console.ready(function() {
      loadSettings();
      loadProfiles();
    });
  })();
})();

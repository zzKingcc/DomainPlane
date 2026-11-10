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

  // domainplane-server/frontend/src/pages/models.js
  (function() {
    Console.renderSidebar("models");
    var PROFILES = [];
    var EMBEDDING = { alias: null };
    var SETTINGS = null;
    var PICKED_INPUT = [];
    var PICKED_OUTPUT = [];
    var INPUT_MODS = ["text", "image", "audio", "video", "file"];
    var OUTPUT_MODS = ["text", "image", "audio", "video", "file", "embedding", "score"];
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
    function setHint(id, value) {
      var el = pfEl(id);
      if (el) {
        el.textContent = value || "\u2014";
      }
    }
    function loadProfiles() {
      return Console.api.get("/admin/model-profiles").then(function(r) {
        PROFILES = r && r.profiles || [];
        var asg = r && r.assignments || {};
        EMBEDDING = { alias: asg.embedding || r && r.embeddingAlias || null };
        setHint("settingsFileHint", r && r.settingsFile);
        renderProfiles();
      }).catch(function(e) {
        pfEl("modelList").innerHTML = '<div class="dp-card" style="color:var(--red);">\u52A0\u8F7D\u5931\u8D25\uFF1A' + Console.escapeHtml(e.message) + "</div>";
        var sel = pfEl("embeddingSlot");
        sel.innerHTML = '<option value="">\u52A0\u8F7D\u5931\u8D25</option>';
        sel.disabled = true;
        setHint("embeddingSlotState", "\u2014");
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
      return (p.endpoints || []).map(function(k) {
        return '<button type="button" class="tag public tag-drop" title="\u79FB\u9664\u300C' + Console.escapeHtml(EP_LABEL[k] || k) + '\u300D\u8FD9\u4E2A\u80FD\u529B" data-drop-family="' + Console.escapeHtml(k) + '" data-drop-alias="' + Console.escapeHtml(p.alias) + '">' + Console.escapeHtml(EP_LABEL[k] || k) + '<span class="tag-x">\xD7</span></button>';
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
    function modelCard(p) {
      var active = p.alias === EMBEDDING.alias;
      var chat = isChatProfile(p);
      var isEmb = isEmbeddingProfile(p);
      var tuneable = !!(p.customParams && p.customParams.applies);
      var enableBtn = isEmb && !active ? '<button class="btn btn-sm btn-outline-primary" data-enable="' + Console.escapeHtml(p.alias) + '"' + (p.usable ? "" : " disabled") + ">\u542F\u7528</button>" : "";
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
      var thinkCtl = chat ? thinkSwitch(p) : "";
      var paramsBox = tuneable ? customParamsBox(p) : "";
      return '<section class="dp-card model-card' + (isEmb ? " emb" : "") + '"><header class="card-head"><span class="model-dot"></span><h2>' + modelTitle(p.modelName || p.alias) + "</h2>" + dupBadge(p) + noType + visionBadge(p) + cardTags(p) + (active ? '<span class="mbadge ok">\u5DF2\u542F\u7528</span>' : "") + (p.usable && p.declared !== false ? "" : '<span class="mbadge off">\u4E0D\u53EF\u7528</span>') + '<span class="spacer"></span>' + thinkCtl + enableBtn + '<button class="btn btn-sm btn-outline-secondary" data-probe="' + Console.escapeHtml(p.alias) + '">\u63A2\u6D4B</button><button class="btn btn-sm btn-outline-danger" data-del="' + Console.escapeHtml(p.alias) + '">\u5220\u9664</button></header><div class="dp-card-body"><div class="model-meta"><div><span class="k">\u8F93\u5165\u6A21\u6001</span>' + modalityCell(p, p.input) + '</div><div><span class="k">\u8F93\u51FA\u6A21\u6001</span>' + modalityCell(p, p.output) + "</div>" + kv("\u670D\u52A1\u5546\u5730\u5740", '<span class="mono">' + Console.escapeHtml(p.baseUrl || "-") + "</span>") + (p.apiKeyMasked ? kv("API Key", '<span class="mono">' + Console.escapeHtml(p.apiKeyMasked) + "</span>") : "") + '<div><span class="k">\u88AB\u54EA\u4E9B\u57DF\u4F7F\u7528</span>' + used + "</div></div>" + paramsBox + "</div></section>";
    }
    function renderSlots() {
      var sel = pfEl("embeddingSlot");
      var choices = PROFILES.filter(isEmbeddingProfile);
      var lead = EMBEDDING.alias ? "" : '<option value="" disabled>\u672A\u542F\u7528</option>';
      sel.innerHTML = lead + choices.map(function(p) {
        return '<option value="' + Console.escapeHtml(p.alias) + '"' + (p.usable ? "" : " disabled") + ">" + Console.escapeHtml(p.modelName || p.alias) + "</option>";
      }).join("");
      sel.value = EMBEDDING.alias || "";
      sel.disabled = !choices.length;
      var state;
      if (!choices.length) {
        state = "\u8FD8\u6CA1\u6709\u58F0\u660E\u300C\u5411\u91CF\u300D\u7528\u9014\u7684\u6863\u6848";
      } else if (!EMBEDDING.alias) {
        state = "\u672A\u542F\u7528 \xB7 \u53EF\u9009 " + choices.length + " \u4E2A";
      } else {
        var cur = findProfile(EMBEDDING.alias);
        state = "\u5DF2\u542F\u7528 \xB7 " + (cur && cur.modelName || EMBEDDING.alias);
      }
      setHint("embeddingSlotState", state);
    }
    function askRebuildOrPurge(message) {
      return __async(this, null, function* () {
        var rebuild = yield Console.confirm(
          message + "\n\n\u73B0\u5728\u7528\u65B0\u7684\u5411\u91CF\u628A\u77E5\u8BC6\u5E93\u91CD\u65B0\u7B97\u4E00\u904D\u5417\uFF1F\u6587\u6863\u90FD\u8FD8\u5728\uFF0C\u4E0D\u7528\u91CD\u65B0\u4E0A\u4F20\u3002",
          { title: "\u6362\u5411\u91CF\u6A21\u578B\uFF1A\u91CD\u704C\u77E5\u8BC6\u5E93", okText: "\u91CD\u65B0\u7B97\u4E00\u904D", cancelText: "\u4E0D\u91CD\u704C", danger: false }
        );
        if (rebuild) {
          return "rebuild";
        }
        var purge = yield Console.confirm(
          "\u6E05\u7A7A\u77E5\u8BC6\u5E93\uFF1F\n\n\u91CC\u9762\u7684\u6587\u6863\u548C\u5411\u91CF\u4F1A\u88AB\u5168\u90E8\u5220\u9664\uFF0C\u4EE5\u540E\u8981\u91CD\u65B0\u4E0A\u4F20\u3002",
          { title: "\u6362\u5411\u91CF\u6A21\u578B\uFF1A\u6E05\u7A7A\u77E5\u8BC6\u5E93", okText: "\u6E05\u7A7A", danger: true }
        );
        return purge ? "purge" : null;
      });
    }
    function applyEmbedding(alias) {
      Console.api.put("/admin/embedding-model", { alias }).then(function(r) {
        if (r && r.requiresConfirmation) {
          return askRebuildOrPurge(r.message || "\u77E5\u8BC6\u5E93\u5DF2\u6709\u5185\u5BB9\u3002").then(function(choice) {
            if (!choice) {
              loadProfiles();
              return null;
            }
            return Console.api.put(
              "/admin/embedding-model",
              { alias, mode: choice }
            );
          });
        }
        return r;
      }).then(function(r) {
        if (!r) {
          return;
        }
        Console.toast(r.message || "\u5DF2\u5207\u6362", { type: "success", wide: true });
        loadProfiles();
        loadSettings();
      }).catch(function(e) {
        Console.toast("\u5207\u6362\u5931\u8D25\uFF1A" + e.message, { type: "error" });
        loadProfiles();
      });
    }
    function renderProfiles() {
      var box = pfEl("modelList");
      renderSlots();
      if (!PROFILES.length) {
        box.innerHTML = '<div class="empty-box"><div class="empty-title">\u8FD8\u6CA1\u6709\u914D\u7F6E\u4EFB\u4F55\u6A21\u578B</div><div class="empty-sub">\u70B9\u53F3\u4E0A\u89D2\u300C\u6DFB\u52A0\u6A21\u578B\u300D\u767B\u8BB0\u4E00\u4E2A\u8FDE\u63A5\u5E76\u52FE\u9009\u7528\u9014\uFF1B\u5BF9\u8BDD\u6A21\u578B\u8981\u7ED1\u5230\u57DF\u624D\u80FD\u88AB\u8C03\u7528\uFF08\u5728\u300C\u57DF\u7A7A\u95F4\u300D\u9875\u64CD\u4F5C\uFF09\u3002</div></div>';
      } else {
        box.innerHTML = PROFILES.map(function(p) {
          return modelCard(p);
        }).join("");
      }
      box.querySelectorAll("button[data-del]").forEach(function(b) {
        b.addEventListener("click", function() {
          deleteProfile(b.getAttribute("data-del"));
        });
      });
      box.querySelectorAll("button[data-enable]").forEach(function(b) {
        b.addEventListener("click", function() {
          applyEmbedding(b.getAttribute("data-enable"));
        });
      });
      box.querySelectorAll("button[data-probe]").forEach(function(b) {
        b.addEventListener("click", function() {
          probeProfile(b.getAttribute("data-probe"), b);
        });
      });
      box.querySelectorAll("button[data-drop-family]").forEach(function(b) {
        b.addEventListener("click", function() {
          dropFamily(b.getAttribute("data-drop-alias"), b.getAttribute("data-drop-family"));
        });
      });
      box.querySelectorAll("input[data-think]").forEach(function(input) {
        input.addEventListener("change", function() {
          saveReturnThinking(input.getAttribute("data-think"), input.checked, input);
        });
      });
      box.querySelectorAll(".dp-params").forEach(bindParamsBox);
      pfEl("modelCount").textContent = PROFILES.length + " \u4E2A";
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
      ["pfBaseUrl", "pfApiKey", "pfModelName", "pfPreset", "pfParamsJson"].forEach(function(id) {
        pfEl(id).value = "";
      });
      setPickedModalities([], []);
      var box = pfEl("profileResult");
      box.textContent = "";
      box.className = "result";
      box.style.color = "";
      var readBox = pfEl("readResult");
      readBox.textContent = "";
      readBox.style.color = "";
    }
    function renderModalityChips(containerId, mods, picked) {
      var box = pfEl(containerId);
      if (!box) {
        return;
      }
      box.innerHTML = mods.map(function(m) {
        return '<button type="button" class="chip' + (picked.indexOf(m) >= 0 ? " on" : "") + '" data-mod="' + Console.escapeHtml(m) + '">' + Console.escapeHtml(MOD_LABEL[m] || m) + "</button>";
      }).join("");
    }
    function renderModalityGroups() {
      renderModalityChips("pfInputMods", INPUT_MODS, PICKED_INPUT);
      renderModalityChips("pfOutputMods", OUTPUT_MODS, PICKED_OUTPUT);
      var tune = pfEl("pfChatTune");
      if (tune) {
        tune.hidden = PICKED_OUTPUT.indexOf("text") < 0;
      }
    }
    function setPickedModalities(input, output) {
      PICKED_INPUT = (input || []).filter(function(m) {
        return INPUT_MODS.indexOf(m) >= 0;
      });
      PICKED_OUTPUT = (output || []).filter(function(m) {
        return OUTPUT_MODS.indexOf(m) >= 0;
      });
      renderModalityGroups();
    }
    function toggleModality(side, m) {
      var cur = side === "in" ? PICKED_INPUT : PICKED_OUTPUT;
      var next = cur.slice();
      var i = next.indexOf(m);
      if (i >= 0) {
        next.splice(i, 1);
      } else {
        next.push(m);
      }
      if (side === "in") {
        setPickedModalities(next, PICKED_OUTPUT);
      } else {
        setPickedModalities(PICKED_INPUT, next);
      }
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
    function customParamsBox(p) {
      var cp = p.customParams || {};
      return '<div class="dp-params" data-params-alias="' + Console.escapeHtml(p.alias) + `"><div class="dp-params-head"><span class="k">\u81EA\u5B9A\u4E49\u53C2\u6570</span><span class="dp-params-sub">JSON \xB7 \u7559\u7A7A\u5373\u4E0D\u53D1\u9001</span></div><textarea class="mono form-control dp-params-json" rows="3" spellcheck="false" placeholder='{"temperature": 0.7, "enable_thinking": false}'>` + Console.escapeHtml(cp.paramsJson || "") + '</textarea><div class="dp-params-foot"><span class="dp-params-status"></span><button type="button" class="btn btn-sm btn-outline-primary dp-params-save" disabled>\u4FDD\u5B58\u53C2\u6570</button></div></div>';
    }
    function bindParamsBox(boxEl) {
      var alias = boxEl.getAttribute("data-params-alias");
      var ta = boxEl.querySelector(".dp-params-json");
      var btn = boxEl.querySelector(".dp-params-save");
      var status = boxEl.querySelector(".dp-params-status");
      if (!alias || !ta || !btn) {
        return;
      }
      var base = (ta.value || "").trim();
      var sync = function() {
        btn.disabled = (ta.value || "").trim() === base;
      };
      ta.addEventListener("input", sync);
      btn.addEventListener("click", function() {
        if (status) {
          status.textContent = "";
          status.className = "dp-params-status";
        }
        btn.disabled = true;
        Console.api.put("/admin/model-profiles/custom-params", { alias, paramsJson: ta.value }).then(function(r) {
          base = (ta.value || "").trim();
          if (status) {
            status.textContent = r && r.message || "\u5DF2\u4FDD\u5B58";
            status.className = "dp-params-status ok";
          }
        }).catch(function(e) {
          if (status) {
            status.textContent = Console.sanitizeError(e.message);
            status.className = "dp-params-status err";
          }
        }).then(sync);
      });
      sync();
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
        Console.toast("\u4FDD\u5B58\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message), { type: "error" });
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
        /* 手选模态原样提交；不传 endpoints，让服务端按模态反查端点族。
           两组都空是允许的：档案落盘，卡片标「不可用」，等用户点「探测」。 */
        input: PICKED_INPUT.slice(),
        output: PICKED_OUTPUT.slice(),
        baseUrl: pfEl("pfBaseUrl").value.trim(),
        apiKey: pfEl("pfApiKey").value,
        modelName,
        /* 新档案的思考回写默认关闭：要开，建成后拨卡片头部那颗开关
           （见 thinkSwitch / saveReturnThinking） */
        returnThinking: false
      };
      var chatPicked = PICKED_OUTPUT.indexOf("text") >= 0;
      if (chatPicked) {
        var rawParams = (pfEl("pfParamsJson").value || "").trim();
        if (rawParams !== "") {
          var parsed;
          try {
            parsed = JSON.parse(rawParams);
          } catch (err) {
            showResult(false, "\u81EA\u5B9A\u4E49\u53C2\u6570\u4E0D\u662F\u5408\u6CD5 JSON\uFF1A" + err.message);
            return;
          }
          if (!parsed || typeof parsed !== "object" || Object.prototype.toString.call(parsed) === "[object Array]") {
            showResult(false, '\u81EA\u5B9A\u4E49\u53C2\u6570\u8981\u662F\u4E00\u4E2A JSON \u5BF9\u8C61\uFF0C\u5982 {"enable_thinking": false}');
            return;
          }
          body.params = parsed;
        }
      }
      var btn = pfEl("saveProfileBtn");
      btn.disabled = true;
      btn.classList.add("btn-loading");
      Console.api.post("/admin/model-profiles", body).then(function() {
        closeModal();
        return loadProfiles();
      }).catch(function(e) {
        showResult(false, "\u4FDD\u5B58\u5931\u8D25\uFF1A" + e.message);
      }).then(function() {
        btn.disabled = false;
        btn.classList.remove("btn-loading");
      });
    }
    function dropFamily(alias, family) {
      return __async(this, null, function* () {
        var label = EP_LABEL[family] || family;
        var ok = yield Console.confirm(
          "\u628A\u300C" + alias + "\u300D\u7684\u300C" + label + "\u300D\u80FD\u529B\u79FB\u9664\uFF1F\n\n\xB7 \u53EA\u52A8\u8FD9\u4E2A\u80FD\u529B\uFF0C\u5176\u5B83\u7AEF\u70B9\u65CF\u4E0E\u8FDE\u63A5\u4FE1\u606F\uFF08\u5730\u5740 / Key / \u6A21\u578B\u540D\uFF09\u4E0D\u53D8\n\xB7 \u6062\u590D\u65B9\u5F0F\uFF1A\u91CD\u65B0\u624B\u9009\u6A21\u6001\uFF0C\u6216\u518D\u70B9\u4E00\u6B21\u300C\u63A2\u6D4B\u300D\n\xB7 \u5982\u679C\u8FD9\u4E2A\u80FD\u529B\u6B63\u88AB\u67D0\u4E2A\u57DF\u7ED1\u5B9A\uFF0C\u90A3\u4E2A\u57DF\u4F1A\u7ACB\u523B\u53D8\u6210\u300C\u65E0\u53EF\u8C03\u7528\u300D",
          { title: "\u79FB\u9664\u80FD\u529B", okText: "\u79FB\u9664", danger: true }
        );
        if (!ok) {
          return;
        }
        Console.api.put(
          "/admin/model-profiles/families/remove",
          { alias, families: [family] }
        ).then(function(r) {
          Console.toast(r && r.message || "\u5DF2\u79FB\u9664", { type: "success", wide: true });
          return loadProfiles();
        }).catch(function(e) {
          Console.toast(Console.sanitizeError(e.message), { type: "error" });
        });
      });
    }
    function deleteProfile(alias) {
      return __async(this, null, function* () {
        var target = findProfile(alias);
        var isEmb = target && isEmbeddingProfile(target);
        var isCurrentEmbedding = isEmb && EMBEDDING.alias === alias;
        var text = "\u786E\u8BA4\u5220\u9664\u6A21\u578B " + alias + "\uFF1F\n\n";
        if (isCurrentEmbedding) {
          text += "\u26A0\uFE0F \u5B83\u6B63\u662F\u5F53\u524D\u5411\u91CF\u6A21\u578B\uFF0C\u5220\u9664\u540E\u5411\u91CF\u914D\u7F6E\u4F1A\u4E00\u5E76\u53D6\u6D88\uFF0C\u77E5\u8BC6\u5E93\u7684\u704C\u5E93\u4E0E\u5411\u91CF\u68C0\u7D22\u5C06\u505C\u7528\u3002\u5DF2\u704C\u5E93\u7684\u5411\u91CF\u4ECD\u5728\u7D22\u5F15\u91CC\uFF1A\u4E4B\u540E\u9009\u56DE\u540C\u4E00\u4E2A\u6A21\u578B\u5C31\u80FD\u76F4\u63A5\u590D\u7528\uFF0C\u6362\u6210\u522B\u7684\u6A21\u578B\u624D\u9700\u8981\u91CD\u704C\u3002\n\n";
        } else if (isEmb) {
          text += "\u5B83\u662F\u4E00\u4E2A\u5411\u91CF\u6863\u6848\uFF0C\u5220\u9664\u540E\u9700\u8981\u91CD\u65B0\u9009\u7528\u5411\u91CF\u6A21\u578B\u3002\n\n";
        } else {
          text += "\u8BE5\u6A21\u578B\u4F1A\u4ECE\u6240\u6709\u57DF\u7684\u7ED1\u5B9A\u91CC\u4E00\u5E76\u79FB\u9664\uFF08\u7EA7\u8054\u6E05\u7406\uFF09\uFF0C\u8FD9\u4E9B\u57DF\u5C06\u7ACB\u5373\u53D8\u4E3A\u300C\u65E0\u53EF\u8C03\u7528\u6A21\u578B\u300D\u3002\n\n";
        }
        var ok = yield Console.confirm(
          text,
          { title: "\u5220\u9664\u6A21\u578B", okText: "\u5220\u9664", danger: true }
        );
        if (!ok) {
          return;
        }
        Console.api.del("/admin/model-profiles?alias=" + encodeURIComponent(alias)).then(function(r) {
          if (r && r.message) {
            Console.toast(r.message, { type: "success" });
          }
          loadProfiles();
        }).catch(function(e) {
          Console.toast("\u5220\u9664\u5931\u8D25\uFF1A" + e.message, { type: "error" });
        });
      });
    }
    var PROBE_SHORT = {
      AUTH: "\u9274\u6743\u5931\u8D25",
      RATE_LIMIT: "\u88AB\u9650\u6D41",
      UPSTREAM: "\u4E0A\u6E38\u9519\u8BEF",
      TIMEOUT: "\u8D85\u65F6",
      NETWORK: "\u8FDE\u4E0D\u4E0A",
      CONFIG: "\u5730\u5740\u6216\u6A21\u578B\u540D\u6709\u8BEF",
      REQUEST_FORMAT: "\u8BF7\u6C42\u683C\u5F0F\u4E0D\u7B26",
      RESPONSE_SHAPE: "\u54CD\u5E94\u683C\u5F0F\u4E0D\u7B26",
      PROTOCOL: "\u9700 WebSocket",
      UNSUPPORTED: "\u7AEF\u70B9\u4E0D\u5B58\u5728",
      NOT_EXECUTED: "\u672A\u6267\u884C",
      OK: "\u6B63\u5E38"
    };
    function probeHttp(d) {
      var c = d && d.statusCode;
      return typeof c === "number" && c > 0 ? "HTTP " + c : "\u2014";
    }
    function probeResultHtml(r) {
      var diags = r && r.diagnostics || {};
      if (!Object.keys(diags).length) {
        return '<div class="probe-empty">\u672A\u8FD4\u56DE\u63A2\u6D4B\u660E\u7EC6</div>';
      }
      var ok = [], bad = [];
      Object.keys(diags).forEach(function(k) {
        var d = diags[k] || {};
        var name = Console.escapeHtml(d.label || k);
        if (d.state === "CONFIRMED") {
          ok.push('<span class="probe-chip">' + name + "</span>");
          return;
        }
        bad.push('<div class="probe-row"><span class="probe-name">' + name + '</span><span class="probe-code">' + probeHttp(d) + '</span><span class="probe-desc">' + Console.escapeHtml(PROBE_SHORT[d.category] || "\u672A\u786E\u8BA4") + "</span></div>");
      });
      return '<div class="probe-result"><div><div class="probe-sec-head ok">\u652F\u6301 <span class="probe-count">' + ok.length + "</span></div>" + (ok.length ? '<div class="probe-chips">' + ok.join("") + "</div>" : '<div class="probe-empty">\u65E0</div>') + '</div><div><div class="probe-sec-head no">\u4E0D\u652F\u6301 / \u672A\u786E\u8BA4 <span class="probe-count">' + bad.length + "</span></div>" + (bad.length ? '<div class="probe-rows">' + bad.join("") + "</div>" : '<div class="probe-empty">\u65E0</div>') + "</div></div>";
    }
    function probeProfile(alias, btn) {
      return __async(this, null, function* () {
        var ok = yield Console.confirm(
          "\u63A2\u6D4B\u300C" + alias + "\u300D\uFF1F\n\n\u8FD9\u662F\u672C\u9875\u552F\u4E00\u4F1A\u82B1 token \u7684\u64CD\u4F5C\uFF1A\u4F1A\u53D1\u4E00\u7EC4\u6700\u5C0F\u8BF7\u6C42\u5B9E\u6D4B\u5B83\u7684\u7AEF\u70B9\u65CF\u4E0E\u6A21\u6001\u3002\n\xB7 \u5BF9\u8BDD\uFF1A16 \u4E2A token\uFF08\u8BF7\u6C42\u4F53\u5F62\u6001\u4E0D\u88AB\u63A5\u53D7\u65F6\u81EA\u52A8\u6362\u4E00\u79CD\u518D\u8BD5\uFF09\n\xB7 \u5411\u91CF / \u91CD\u6392\uFF1A\u63D0\u4EA4\u4E00\u6761\u77ED\u6587\u672C\n\xB7 \u8BED\u97F3\u8BC6\u522B / \u8BED\u97F3\u5408\u6210\uFF1A\u6309\u6781\u77ED\u97F3\u9891\u8BA1\u8D39\n\xB7 \u751F\u56FE\uFF1A\u82E5\u8BE5\u670D\u52A1\u5546\u6709\u751F\u56FE\u7AEF\u70B9\uFF0C\u4F1A\u771F\u5B9E\u751F\u6210\u4E00\u5F20\u9ED8\u8BA4\u5C3A\u5BF8\u7684\u56FE\n\n\u6574\u6B21\u4E00\u6761\u90FD\u6CA1\u63A2\u6D4B\u5230\uFF08\u7F51\u7EDC\u95EE\u9898\u7B49\uFF09\u5219\u4FDD\u7559\u539F\u8BBE\u7F6E\u3002\u7EE7\u7EED\uFF1F",
          { title: "\u63A2\u6D4B\u6A21\u578B\u7AEF\u70B9\u4E0E\u6A21\u6001", okText: "\u5F00\u59CB\u63A2\u6D4B", danger: false }
        );
        if (!ok) {
          return;
        }
        var override = yield Console.confirm(
          "\u8FD9\u6B21\u5B9E\u6D4B\u7ED3\u679C\u600E\u4E48\u7528\uFF1F\n\n\xB7 \u300C\u53EA\u8865\u4E0D\u5220\u300D\uFF1A\u628A\u786E\u8BA4\u5230\u7684\u80FD\u529B\u5E76\u8FDB\u6863\u6848\uFF0C\u6CA1\u786E\u8BA4\u5230\u7684\u65E7\u58F0\u660E\u4FDD\u6301\u4E0D\u52A8\n\xB7 \u300C\u8986\u76D6\u5199\u56DE\u300D\uFF1A\u4EE5\u5B9E\u6D4B\u4E3A\u51C6\uFF0C\u6CA1\u786E\u8BA4\u5230\u7684\u7AEF\u70B9\u65CF\u4E0E\u6A21\u6001\u4F1A\u88AB\u6E05\u6389",
          { title: "\u5199\u56DE\u65B9\u5F0F", okText: "\u8986\u76D6\u5199\u56DE", cancelText: "\u53EA\u8865\u4E0D\u5220", danger: false }
        );
        if (btn) {
          btn.disabled = true;
          btn.classList.add("btn-loading");
          btn.setAttribute("data-orig-label", btn.textContent);
          btn.textContent = "\u63A2\u6D4B\u4E2D\u2026";
        }
        Console.api.post("/admin/model-profiles/detect?alias=" + encodeURIComponent(alias) + "&mode=" + (override ? "override" : "confirmed"), null).then(function(r) {
          return loadProfiles().then(function() {
            Console.confirm(probeResultHtml(r), {
              title: "\u63A2\u6D4B\u7ED3\u679C \xB7 " + (r && r.writeMode === "override" ? "\u5DF2\u8986\u76D6\u5199\u56DE" : "\u53EA\u8865\u4E0D\u5220"),
              okText: "\u77E5\u9053\u4E86",
              hideCancel: true,
              html: true,
              danger: false
            });
          });
        }).catch(function(e) {
          if (btn) {
            btn.disabled = false;
            btn.classList.remove("btn-loading");
            btn.textContent = btn.getAttribute("data-orig-label") || "\u63A2\u6D4B";
          }
          Console.toast("\u63A2\u6D4B\u5931\u8D25\uFF1A" + Console.sanitizeError(e.message), { type: "error" });
        });
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
        return '<div class="dp-card"><div class="card-head"><div><b>' + Console.escapeHtml(c.name) + '</b><div class="mono hint" style="margin:2px 0 0;">' + Console.escapeHtml(c.baseUrl) + '</div></div><span class="spacer"></span><span class="mono hint" style="margin:0;">' + Console.escapeHtml(key) + '</span><button class="btn btn-sm btn-outline-danger" data-cn-del="' + Console.escapeHtml(c.name) + '">\u5220\u9664</button></div></div>';
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
      btn.classList.add("btn-loading");
      Console.api.post("/admin/provider-connections", { name, baseUrl, apiKey: secret }).then(function(r) {
        pfEl("cnName").value = "";
        pfEl("cnBaseUrl").value = "";
        pfEl("cnApiKey").value = "";
        setHint("cnFileHint", r && r.settingsFile);
        cnSay(true, r && r.message || "\u5DF2\u6DFB\u52A0\u300C" + name + "\u300D");
        return loadConnections(true);
      }).then(function() {
        renderConnectionList();
      }).catch(function(e) {
        cnSay(false, Console.sanitizeError(e.message));
      }).then(function() {
        btn.disabled = false;
        btn.classList.remove("btn-loading");
      });
    }
    function deleteConnection(name) {
      return __async(this, null, function* () {
        var ok = yield Console.confirm(
          "\u5220\u9664\u81EA\u5B9A\u4E49\u914D\u7F6E\u300C" + name + "\u300D\uFF1F\n\n\u5DF2\u4FDD\u5B58\u7684\u6A21\u578B\u6863\u6848\u4E0D\u53D7\u5F71\u54CD\uFF1A\u5B83\u4EEC\u5404\u81EA\u4FDD\u5B58\u7684\u662F\u72EC\u7ACB\u526F\u672C\uFF0C\u4E0D\u662F\u5BF9\u8FD9\u4E00\u6761\u7684\u5F15\u7528\u3002",
          { title: "\u5220\u9664\u81EA\u5B9A\u4E49\u914D\u7F6E", okText: "\u5220\u9664", danger: true }
        );
        if (!ok) {
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
    pfEl("embeddingSlot").addEventListener("change", function() {
      var v = pfEl("embeddingSlot").value;
      applyEmbedding(v || null);
    });
    ["pfInputMods", "pfOutputMods"].forEach(function(id) {
      var box = pfEl(id);
      if (!box) {
        return;
      }
      box.addEventListener("click", function(e) {
        var chip = e.target && e.target.closest ? e.target.closest("[data-mod]") : null;
        if (chip) {
          toggleModality(id === "pfInputMods" ? "in" : "out", chip.getAttribute("data-mod"));
        }
      });
    });
    setPickedModalities([], []);
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
